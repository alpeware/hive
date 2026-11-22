// Kokoro-82M Full-Graph WebGPU Executor for clj-einsum (:uop-webgpu)
// Zero third-party CDNs, strict-CSP clean ES module.
// Executes all 459-tensor Kokoro-82M stages (ALBERT 12L + bert_encoder,
// ProsodyPredictor Duration/F0/N, TextEncoder Conv1d+BiLSTM, Decoder AdaIN-ResBlocks,
// iSTFTNet Generator Upsample + 6x AdaINResBlock1 + conv_post + CustomSTFT)
// using clj-einsum compiled subgroup-free WGSL shaders on navigator.gpu.

const WGSL_HELPERS = {
  leaky_relu: `
struct Params { n: u32, slope: f32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> in_buf: array<f32>;
@group(0) @binding(2) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  if (i >= p.n) { return; }
  let x = in_buf[i];
  out_buf[i] = select(x * p.slope, x, x >= 0.0);
}`,

  snake1d: `
struct Params { c: u32, t: u32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> in_buf: array<f32>;
@group(0) @binding(2) var<storage, read> alpha_buf: array<f32>;
@group(0) @binding(3) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  let total = p.c * p.t;
  if (i >= total) { return; }
  let ci = i / p.t;
  let a = alpha_buf[ci];
  let v = in_buf[i];
  let s = sin(a * v);
  out_buf[i] = v + (s * s) / a;
}`,

  gelu_new: `
struct Params { n: u32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> in_buf: array<f32>;
@group(0) @binding(2) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  if (i >= p.n) { return; }
  let x = in_buf[i];
  let c = 0.7978845608028654f;
  let inner = c * (x + 0.044715f * x * x * x);
  out_buf[i] = 0.5f * x * (1.0f + tanh(inner));
}`,

  add: `
struct Params { n: u32, scale: f32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> a_buf: array<f32>;
@group(0) @binding(2) var<storage, read> b_buf: array<f32>;
@group(0) @binding(3) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  if (i >= p.n) { return; }
  out_buf[i] = (a_buf[i] + b_buf[i]) * p.scale;
}`,

  avg3: `
struct Params { n: u32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> a_buf: array<f32>;
@group(0) @binding(2) var<storage, read> b_buf: array<f32>;
@group(0) @binding(3) var<storage, read> c_buf: array<f32>;
@group(0) @binding(4) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  if (i >= p.n) { return; }
  out_buf[i] = (a_buf[i] + b_buf[i] + c_buf[i]) * (1.0f / 3.0f);
}`,

  transpose_2d: `
struct Params { rows: u32, cols: u32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> in_buf: array<f32>;
@group(0) @binding(2) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  let total = p.rows * p.cols;
  if (i >= total) { return; }
  let r = i / p.cols;
  let c = i % p.cols;
  out_buf[c * p.rows + r] = in_buf[i];
}`,

  upsample_nearest_2x: `
struct Params { c: u32, t: u32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> in_buf: array<f32>;
@group(0) @binding(2) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  let t2 = p.t * 2u;
  let total = p.c * t2;
  if (i >= total) { return; }
  let ci = i / t2;
  let ti = (i % t2) >> 1u;
  out_buf[i] = in_buf[ci * p.t + ti];
}`,

  reflection_pad_left_1: `
struct Params { c: u32, t: u32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> in_buf: array<f32>;
@group(0) @binding(2) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  let t1 = p.t + 1u;
  let total = p.c * t1;
  if (i >= total) { return; }
  let ci = i / t1;
  let ti = i % t1;
  let src_t = select(ti - 1u, 1u, ti == 0u);
  out_buf[i] = in_buf[ci * p.t + src_t];
}`,

  cat_style_tc: `
struct Params { t: u32, c: u32, sc: u32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> x_buf: array<f32>;
@group(0) @binding(2) var<storage, read> s_buf: array<f32>;
@group(0) @binding(3) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  let out_c = p.c + p.sc;
  let total = p.t * out_c;
  if (i >= total) { return; }
  let ti = i / out_c;
  let ci = i % out_c;
  if (ci < p.c) {
    out_buf[i] = x_buf[ti * p.c + ci];
  } else {
    out_buf[i] = s_buf[ci - p.c];
  }
}`,

  layer_norm_last: `
struct Params { n: u32, d: u32, eps: f32, add_one: u32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> x_buf: array<f32>;
@group(0) @binding(2) var<storage, read> g_buf: array<f32>;
@group(0) @binding(3) var<storage, read> b_buf: array<f32>;
@group(0) @binding(4) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let row = gid.x;
  if (row >= p.n) { return; }
  let base = row * p.d;
  var sum: f32 = 0.0f;
  for (var j: u32 = 0u; j < p.d; j = j + 1u) { sum = sum + x_buf[base + j]; }
  let mean = sum / f32(p.d);
  var vs: f32 = 0.0f;
  for (var j: u32 = 0u; j < p.d; j = j + 1u) {
    let diff = x_buf[base + j] - mean;
    vs = vs + diff * diff;
  }
  let inv_std = 1.0f / sqrt(vs / f32(p.d) + p.eps);
  let bias_scale = select(0.0f, 1.0f, p.add_one != 0u);
  for (var j: u32 = 0u; j < p.d; j = j + 1u) {
    let normed = (x_buf[base + j] - mean) * inv_std;
    out_buf[base + j] = (bias_scale + g_buf[j]) * normed + b_buf[j];
  }
}`,

  channel_layer_norm: `
struct Params { c: u32, t: u32, eps: f32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> x_buf: array<f32>;
@group(0) @binding(2) var<storage, read> g_buf: array<f32>;
@group(0) @binding(3) var<storage, read> b_buf: array<f32>;
@group(0) @binding(4) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let ti = gid.x;
  if (ti >= p.t) { return; }
  var sum: f32 = 0.0f;
  for (var ci: u32 = 0u; ci < p.c; ci = ci + 1u) { sum = sum + x_buf[ci * p.t + ti]; }
  let mean = sum / f32(p.c);
  var vs: f32 = 0.0f;
  for (var ci: u32 = 0u; ci < p.c; ci = ci + 1u) {
    let diff = x_buf[ci * p.t + ti] - mean;
    vs = vs + diff * diff;
  }
  let inv_std = 1.0f / sqrt(vs / f32(p.c) + p.eps);
  for (var ci: u32 = 0u; ci < p.c; ci = ci + 1u) {
    let idx = ci * p.t + ti;
    let normed = (x_buf[idx] - mean) * inv_std;
    out_buf[idx] = g_buf[ci] * normed + b_buf[ci];
  }
}`,

  mha_albert: `
struct Params { t: u32, num_heads: u32, head_dim: u32, scale: f32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> q_buf: array<f32>;
@group(0) @binding(2) var<storage, read> k_buf: array<f32>;
@group(0) @binding(3) var<storage, read> v_buf: array<f32>;
@group(0) @binding(4) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let idx = gid.x;
  let total = p.num_heads * p.t;
  if (idx >= total) { return; }
  let h = idx / p.t;
  let i = idx % p.t;
  let d = p.num_heads * p.head_dim;
  let h_off = h * p.head_dim;
  let qi_base = i * d + h_off;
  var max_s: f32 = -1.0e30f;
  for (var j: u32 = 0u; j < p.t; j = j + 1u) {
    let kj_base = j * d + h_off;
    var dot: f32 = 0.0f;
    for (var m: u32 = 0u; m < p.head_dim; m = m + 1u) {
      dot = dot + q_buf[qi_base + m] * k_buf[kj_base + m];
    }
    max_s = max(max_s, dot * p.scale);
  }
  var sum_e: f32 = 0.0f;
  for (var j: u32 = 0u; j < p.t; j = j + 1u) {
    let kj_base = j * d + h_off;
    var dot: f32 = 0.0f;
    for (var m: u32 = 0u; m < p.head_dim; m = m + 1u) {
      dot = dot + q_buf[qi_base + m] * k_buf[kj_base + m];
    }
    sum_e = sum_e + exp(dot * p.scale - max_s);
  }
  let inv_se = 1.0f / sum_e;
  for (var m: u32 = 0u; m < p.head_dim; m = m + 1u) {
    var acc: f32 = 0.0f;
    for (var j: u32 = 0u; j < p.t; j = j + 1u) {
      let kj_base = j * d + h_off;
      var dot: f32 = 0.0f;
      for (var k: u32 = 0u; k < p.head_dim; k = k + 1u) {
        dot = dot + q_buf[qi_base + k] * k_buf[kj_base + k];
      }
      let w = exp(dot * p.scale - max_s) * inv_se;
      acc = acc + w * v_buf[j * d + h_off + m];
    }
    out_buf[qi_base + m] = acc;
  }
}`,

  exp_sin_istft_spec: `
struct Params { n_bins: u32, frames: u32 };
@group(0) @binding(0) var<storage, read_write> spec_buf: array<f32>;
@group(0) @binding(1) var<storage, read> x_post_buf: array<f32>;
@group(0) @binding(2) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  let half = p.n_bins * p.frames;
  if (i >= half) { return; }
  let mag = exp(x_post_buf[i]);
  let ph = sin(x_post_buf[half + i]);
  spec_buf[i] = mag * cos(ph);
  spec_buf[half + i] = mag * sin(ph);
}`,

  istft_hann_norm: `
struct Params { n: u32 };
@group(0) @binding(0) var<storage, read_write> out_buf: array<f32>;
@group(0) @binding(1) var<storage, read> raw_buf: array<f32>;
@group(0) @binding(2) var<storage, read> win_sum_buf: array<f32>;
@group(0) @binding(3) var<uniform> p: Params;
@compute @workgroup_size(64, 1, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x + gid.y * 65535u * 64u;
  if (i >= p.n) { return; }
  let denom = max(1.0e-8f, win_sum_buf[i]);
  out_buf[i] = raw_buf[i] / denom;
}`
};

function align4(n) {
  return (Math.max(4, n) + 3) & ~3;
}

function grid1d(totalItems, wgSize = 64) {
  const blocks = Math.max(1, Math.ceil(totalItems / wgSize));
  if (blocks <= 65535) return [blocks, 1, 1];
  return [65535, Math.ceil(blocks / 65535), 1];
}

function rint(x) {
  const f = Math.floor(x);
  const diff = x - f;
  if (diff < 0.5) return f;
  if (diff > 0.5) return f + 1;
  return (f % 2 === 0) ? f : f + 1;
}

function parseSafetensors(arrayBuffer) {
  const dv = new DataView(arrayBuffer);
  const hdrLen = Number(dv.getBigUint64(0, true));
  const hdrText = new TextDecoder("utf-8").decode(new Uint8Array(arrayBuffer, 8, hdrLen));
  const hdr = JSON.parse(hdrText);
  const dataBase = 8 + hdrLen;
  const tensors = new Map();
  for (const [k, info] of Object.entries(hdr)) {
    if (k === "__metadata__") continue;
    const [start, end] = info.data_offsets;
    const byteOffset = dataBase + start;
    const byteLength = end - start;
    let f32;
    if ((byteOffset & 3) === 0) {
      f32 = new Float32Array(arrayBuffer, byteOffset, byteLength / 4);
    } else {
      f32 = new Float32Array(arrayBuffer.slice(byteOffset, byteOffset + byteLength));
    }
    tensors.set(k, { shape: info.shape, dtype: info.dtype, data: f32, byteOffset, byteLength });
  }
  return tensors;
}

function hannWindow(n) {
  const w = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    w[i] = 0.5 * (1.0 - Math.cos((2.0 * Math.PI * i) / n));
  }
  return w;
}

function customIstftBasis(nFft = 20) {
  const nBins = (nFft >> 1) + 1;
  const win = hannWindow(nFft);
  const basis = new Float32Array(2 * nBins * nFft);
  const invN = 1.0 / nFft;
  for (let k = 0; k < nBins; k++) {
    const scale = (k === 0 || k === (nFft >> 1)) ? invN : 2.0 * invN;
    const cosBase = k * nFft;
    const sinBase = (nBins + k) * nFft;
    for (let n = 0; n < nFft; n++) {
      const ang = (2.0 * Math.PI * k * n) / nFft;
      const w = win[n];
      basis[cosBase + n] = scale * w * Math.cos(ang);
      basis[sinBase + n] = -scale * w * Math.sin(ang);
    }
  }
  return basis;
}

function hannOverlapAddNorm(frames, nFft = 20, hop = 5) {
  const pad = nFft >> 1;
  const outLen = (frames - 1) * hop - 2 * pad + nFft;
  const win = hannWindow(nFft);
  const winSum = new Float32Array(outLen);
  for (let f = 0; f < frames; f++) {
    for (let k = 0; k < nFft; k++) {
      const t = f * hop + k - pad;
      if (t >= 0 && t < outLen) {
        winSum[t] += win[k] * win[k];
      }
    }
  }
  return winSum;
}

function runLinear1dCpu(x, inDim, w, outDim, b) {
  const out = new Float32Array(outDim);
  for (let o = 0; o < outDim; o++) {
    let acc = b[o];
    const base = o * inDim;
    for (let i = 0; i < inDim; i++) {
      acc += x[i] * w[base + i];
    }
    out[o] = acc;
  }
  return out;
}

function expandByDurationsCt(xCt, c, t, predDur, totalFrames) {
  const out = new Float32Array(c * totalFrames);
  for (let ci = 0; ci < c; ci++) {
    const inBase = ci * t;
    const outBase = ci * totalFrames;
    let fi = 0;
    for (let ti = 0; ti < t; ti++) {
      const v = xCt[inBase + ti];
      const dur = predDur[ti];
      for (let k = 0; k < dur; k++) {
        out[outBase + fi + k] = v;
      }
      fi += dur;
    }
  }
  return out;
}

function interpLinear1d(x, c, lIn, lOut) {
  const scale = lIn / lOut;
  const out = new Float64Array(c * lOut);
  for (let ci = 0; ci < c; ci++) {
    const inBase = ci * lIn;
    const outBase = ci * lOut;
    for (let j = 0; j < lOut; j++) {
      let src = (j + 0.5) * scale - 0.5;
      if (src < 0.0) src = 0.0;
      if (src > lIn - 1) src = lIn - 1;
      const i0 = Math.floor(src);
      const i1 = Math.min(lIn - 1, i0 + 1);
      const w = src - i0;
      out[outBase + j] = (1.0 - w) * x[inBase + i0] + w * x[inBase + i1];
    }
  }
  return out;
}

// Seeded Mulberry32 + Box-Muller for reproducible natural hn-NSF noise
function makeRng(seed) {
  let a = (seed >>> 0) || 2026;
  function nextUniform() {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296.0;
  }
  function nextGaussian() {
    const u1 = Math.max(1e-12, nextUniform());
    const u2 = nextUniform();
    return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
  }
  return { nextUniform, nextGaussian };
}

function runHnNsfSource(f0Pred, linW, linB, { deterministic = false, seed = 2026 } = {}) {
  const nFrames = f0Pred.length;
  const scale = 300;
  const nSamples = nFrames * scale;
  const nHarm = 9;
  const sr = 24000.0;
  const amp = 0.1;
  const noiseStd = 0.003;
  const vThresh = 10.0;
  const rng = deterministic ? null : makeRng(seed);

  const f0Up = new Float64Array(nSamples);
  const uv = new Float64Array(nSamples);
  for (let fi = 0; fi < nFrames; fi++) {
    const fv = f0Pred[fi];
    const v = fv > vThresh ? 1.0 : 0.0;
    const base = fi * scale;
    for (let s = 0; s < scale; s++) {
      f0Up[base + s] = fv;
      uv[base + s] = v;
    }
  }

  const rad = new Float64Array(nHarm * nSamples);
  for (let h = 0; h < nHarm; h++) {
    const mult = h + 1;
    const base = h * nSamples;
    const rIni = (deterministic || h === 0) ? 0.0 : rng.nextUniform();
    for (let t = 0; t < nSamples; t++) {
      const v = (f0Up[t] * mult) / sr;
      let frac = v - Math.floor(v);
      if (t === 0) frac += rIni;
      rad[base + t] = frac;
    }
  }

  const radDown = interpLinear1d(rad, nHarm, nSamples, nFrames);
  const phaseDown = new Float64Array(nHarm * nFrames);
  for (let h = 0; h < nHarm; h++) {
    const base = h * nFrames;
    let acc = 0.0;
    for (let fi = 0; fi < nFrames; fi++) {
      acc += radDown[base + fi];
      phaseDown[base + fi] = acc * 2.0 * Math.PI * scale;
    }
  }
  const phaseUp = interpLinear1d(phaseDown, nHarm, nFrames, nSamples);

  const harSource = new Float32Array(nSamples);
  const b0 = linB[0];
  for (let t = 0; t < nSamples; t++) {
    const u = uv[t];
    const nAmp = u * noiseStd + (1.0 - u) * (amp / 3.0);
    let acc = b0;
    for (let h = 0; h < nHarm; h++) {
      let sw = Math.sin(phaseUp[h * nSamples + t]) * amp * u;
      if (!deterministic) sw += nAmp * rng.nextGaussian();
      acc += sw * linW[h];
    }
    harSource[t] = Math.tanh(acc);
  }
  return harSource;
}

function runForwardStft(wav) {
  const n = wav.length;
  const nFft = 20;
  const hop = 5;
  const pad = 10;
  const nBins = 11;
  const paddedLen = n + 2 * pad;
  const padded = new Float64Array(paddedLen);
  for (let i = 0; i < pad; i++) {
    padded[i] = wav[pad - i];
    padded[pad + n + i] = wav[n - 2 - i];
  }
  for (let i = 0; i < n; i++) padded[pad + i] = wav[i];

  const frames = Math.floor((paddedLen - nFft) / hop) + 1;
  const win = hannWindow(nFft);
  const cosTbl = new Float64Array(nBins * nFft);
  const sinTbl = new Float64Array(nBins * nFft);
  for (let k = 0; k < nBins; k++) {
    for (let j = 0; j < nFft; j++) {
      const ang = (2.0 * Math.PI * k * j) / nFft;
      const w = win[j];
      cosTbl[k * nFft + j] = w * Math.cos(ang);
      sinTbl[k * nFft + j] = (k === 0 || k === (nFft >> 1)) ? 0.0 : -w * Math.sin(ang);
    }
  }

  const out = new Float32Array(2 * nBins * frames);
  for (let f = 0; f < frames; f++) {
    const fStart = f * hop;
    for (let k = 0; k < nBins; k++) {
      const tblBase = k * nFft;
      let re = 0.0, im = 0.0;
      for (let j = 0; j < nFft; j++) {
        const x = padded[fStart + j];
        re += x * cosTbl[tblBase + j];
        im += x * sinTbl[tblBase + j];
      }
      const mag = Math.hypot(re, im);
      let ph;
      if (Math.abs(re) < 1e-12 && Math.abs(im) < 1e-12) ph = 0.0;
      else if (Math.abs(im) < 1e-12 && re < 0.0) ph = Math.PI;
      else ph = Math.atan2(im, re);
      out[k * frames + f] = mag;
      out[(nBins + k) * frames + f] = ph;
    }
  }
  return out;
}

export function encodeWavPcm16(pcm, sampleRate = 24000) {
  const n = pcm.length;
  const dataBytes = n * 2;
  const buf = new ArrayBuffer(44 + dataBytes);
  const dv = new DataView(buf);
  const writeStr = (off, s) => { for (let i = 0; i < s.length; i++) dv.setUint8(off + i, s.charCodeAt(i)); };
  writeStr(0, "RIFF");
  dv.setUint32(4, 36 + dataBytes, true);
  writeStr(8, "WAVE");
  writeStr(12, "fmt ");
  dv.setUint32(16, 16, true);
  dv.setUint16(20, 1, true);
  dv.setUint16(22, 1, true);
  dv.setUint32(24, sampleRate, true);
  dv.setUint32(28, sampleRate * 2, true);
  dv.setUint16(32, 2, true);
  dv.setUint16(34, 16, true);
  writeStr(36, "data");
  dv.setUint32(40, dataBytes, true);
  for (let i = 0; i < n; i++) {
    const v = Math.max(-1.0, Math.min(1.0, pcm[i]));
    dv.setInt16(44 + i * 2, Math.round(v * 32767.0), true);
  }
  return new Uint8Array(buf);
}

export class KokoroWebGpuRunner {
  constructor(baseUrl = "") {
    this.baseUrl = baseUrl;
    this.device = null;
    this.adapterInfo = null;
    this.weights = null;
    this.weightGpuCache = new Map();
    this.biasPairGpuCache = new Map();
    this.voiceCache = new Map();
    this.bundleCache = new Map();
    this.uopPipelineCache = new Map();
    this.helperPipelines = new Map();
    this.istftBasisGpu = null;
    this.hannNormGpuCache = new Map();
  }

  async init() {
    if (this.device) return this;
    if (!navigator.gpu) throw new Error("WebGPU not supported (navigator.gpu missing)");
    const adapter = await navigator.gpu.requestAdapter();
    if (!adapter) throw new Error("No WebGPU adapter available");
    const info = adapter.info || {};
    this.adapterInfo = {
      vendor: info.vendor || "",
      architecture: info.architecture || "",
      device: info.device || "",
      description: info.description || ""
    };
    this.device = await adapter.requestDevice();

    // Compile GPU-resident helper shaders once
    for (const [name, code] of Object.entries(WGSL_HELPERS)) {
      const mod = this.device.createShaderModule({ label: `helper_${name}`, code });
      const info = await mod.getCompilationInfo();
      const errs = info.messages.filter((m) => m.type === "error");
      if (errs.length > 0) {
        throw new Error(`Helper WGSL error in ${name}: ${errs.map((e) => e.message).join("; ")}`);
      }
      this.helperPipelines.set(
        name,
        this.device.createComputePipeline({
          label: `pipe_helper_${name}`,
          layout: "auto",
          compute: { module: mod, entryPoint: "main" }
        })
      );
    }

    // Pre-upload CustomSTFT inverse basis [22, 1, 20]
    const basis = customIstftBasis(20);
    this.istftBasisGpu = this.uploadF32(basis, "istft_basis");
    return this;
  }

  async loadWeights(weightsUrl = `${this.baseUrl}/api/kokoro-weights/folded.safetensors`) {
    if (this.weights) return this.weights;
    const res = await fetch(weightsUrl);
    if (!res.ok) throw new Error(`Weights fetch failed: HTTP ${res.status}`);
    const ab = await res.arrayBuffer();
    this.weights = parseSafetensors(ab);
    return this.weights;
  }

  async loadVoice(voiceId = "af_heart") {
    if (this.voiceCache.has(voiceId)) return this.voiceCache.get(voiceId);
    const res = await fetch(`${this.baseUrl}/api/kokoro-weights/voice/${encodeURIComponent(voiceId)}`);
    if (!res.ok) throw new Error(`Voice ${voiceId} fetch failed: HTTP ${res.status}`);
    const ab = await res.arrayBuffer();
    const map = parseSafetensors(ab);
    const entry = map.get("voice") || map.values().next().value;
    this.voiceCache.set(voiceId, entry);
    return entry;
  }

  async loadBundle(tTok, fFrames) {
    const key = `${tTok}_${fFrames}`;
    if (this.bundleCache.has(key)) return this.bundleCache.get(key);
    const res = await fetch(`${this.baseUrl}/api/kokoro-webgpu-full-bundle?t_tok=${tTok}&f_frames=${fFrames}`);
    if (!res.ok) throw new Error(`Bundle fetch failed: HTTP ${res.status}`);
    const bundle = await res.json();
    // Compile all deduplicated clj-einsum :uop-webgpu WGSL shaders into cached pipelines
    for (const [skey, src] of Object.entries(bundle.shaders)) {
      if (this.uopPipelineCache.has(skey)) continue;
      const mod = this.device.createShaderModule({ label: `uop_${skey}`, code: src });
      const info = await mod.getCompilationInfo();
      const errs = info.messages.filter((m) => m.type === "error");
      if (errs.length > 0) {
        throw new Error(`clj-einsum WGSL error in ${skey}: ${errs.map((e) => `L${e.lineNum}:${e.message}`).join("; ")}`);
      }
      this.uopPipelineCache.set(
        skey,
        this.device.createComputePipeline({
          label: `pipe_uop_${skey}`,
          layout: "auto",
          compute: { module: mod, entryPoint: "main" }
        })
      );
    }
    this.bundleCache.set(key, bundle);
    return bundle;
  }

  wCpu(name) {
    const e = this.weights.get(name);
    if (!e) throw new Error(`Missing Kokoro weight: ${name}`);
    return e.data;
  }

  wGpu(name) {
    if (this.weightGpuCache.has(name)) return this.weightGpuCache.get(name);
    const data = this.wCpu(name);
    const buf = this.uploadF32(data, `w_${name}`);
    this.weightGpuCache.set(name, buf);
    return buf;
  }

  biasPairGpu(k1, k2) {
    const key = `${k1}+${k2}`;
    if (this.biasPairGpuCache.has(key)) return this.biasPairGpuCache.get(key);
    const a = this.wCpu(k1);
    const b = this.wCpu(k2);
    const sum = new Float32Array(a.length);
    for (let i = 0; i < a.length; i++) sum[i] = a[i] + b[i];
    const buf = this.uploadF32(sum, `bp_${key}`);
    this.biasPairGpuCache.set(key, buf);
    return buf;
  }

  allocGpu(numFloats, usage = GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_SRC | GPUBufferUsage.COPY_DST) {
    return this.device.createBuffer({ size: align4(numFloats * 4), usage });
  }

  uploadF32(arr, label = "upload") {
    const buf = this.device.createBuffer({
      label,
      size: align4(arr.byteLength),
      usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_SRC | GPUBufferUsage.COPY_DST
    });
    this.device.queue.writeBuffer(buf, 0, arr);
    return buf;
  }

  makeUniformU32F32(words) {
    const buf = this.device.createBuffer({
      size: align4(Math.max(16, words.byteLength)),
      usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST
    });
    this.device.queue.writeBuffer(buf, 0, words);
    return buf;
  }

  async readbackF32(gpuBuf, numFloats) {
    const byteLen = numFloats * 4;
    const staging = this.device.createBuffer({
      size: align4(byteLen),
      usage: GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST
    });
    const enc = this.device.createCommandEncoder();
    enc.copyBufferToBuffer(gpuBuf, 0, staging, 0, byteLen);
    this.device.queue.submit([enc.finish()]);
    await staging.mapAsync(GPUMapMode.READ);
    const out = new Float32Array(staging.getMappedRange(0, byteLen).slice(0));
    staging.unmap();
    staging.destroy();
    return out;
  }

  dispatchHelper(enc, name, buffers, uniformWords, gx, gy = 1, gz = 1, temps = null) {
    const pipe = this.helperPipelines.get(name);
    const uBuf = this.makeUniformU32F32(uniformWords);
    if (temps) temps.push(uBuf);
    const entries = buffers.map((b, idx) => ({ binding: idx, resource: { buffer: b } }));
    entries.push({ binding: buffers.length, resource: { buffer: uBuf } });
    const bg = this.device.createBindGroup({ layout: pipe.getBindGroupLayout(0), entries });
    const pass = enc.beginComputePass();
    pass.setPipeline(pipe);
    pass.setBindGroup(0, bg);
    pass.dispatchWorkgroups(gx, gy, gz);
    pass.end();
  }

  // Generic dispatcher for ANY clj-einsum :uop-webgpu compiled kernel template!
  dispatchUop(enc, bundle, kernelName, namedInputs, temps) {
    const k = bundle.kernels[kernelName];
    if (!k) throw new Error(`Missing clj-einsum UOp kernel template: ${kernelName}`);
    const slotBufs = new Map();
    for (const [inName, slot] of Object.entries(k["invar-slots"])) {
      const b = namedInputs[inName];
      if (!b) throw new Error(`Missing input ${inName} for kernel ${kernelName}`);
      slotBufs.set(slot, b);
    }
    const outSlotSet = new Set(Object.values(k["output-slots"]));
    for (const [slotStr, info] of Object.entries(k.buffers)) {
      const slot = Number(slotStr);
      if (slotBufs.has(slot)) continue;
      const b = this.allocGpu(info.size);
      slotBufs.set(slot, b);
      if (!outSlotSet.has(slot) && temps) temps.push(b);
    }
    for (const step of k.steps) {
      const pipe = this.uopPipelineCache.get(step["shader-key"]);
      const entries = step["buffer-slots"].map((slot, idx) => ({
        binding: idx,
        resource: { buffer: slotBufs.get(slot) }
      }));
      const bg = this.device.createBindGroup({ layout: pipe.getBindGroupLayout(0), entries });
      const pass = enc.beginComputePass();
      pass.setPipeline(pipe);
      pass.setBindGroup(0, bg);
      const [gx, gy, gz] = step.grid;
      pass.dispatchWorkgroups(gx, gy, gz);
      pass.end();
    }
    const outputs = {};
    for (const [outName, slot] of Object.entries(k["output-slots"])) {
      outputs[outName] = slotBufs.get(slot);
    }
    return outputs;
  }

  // GPU helper wrappers
  gpuLinear(enc, bundle, xBuf, rows, inDim, outDim, wKey, bKey, temps) {
    const name = `linear_${rows}_${inDim}_${outDim}`;
    return this.dispatchUop(enc, bundle, name, { X: xBuf, W: this.wGpu(wKey), B: this.wGpu(bKey) }, temps).Y;
  }

  gpuConv1d(enc, bundle, xBuf, cIn, cOut, tIn, k, s, p, d, g, wKey, bKey, temps) {
    const hasBias = !!bKey;
    const name = `conv1d_${cIn}_${cOut}_${tIn}_k${k}_s${s}_p${p}_d${d}_g${g}_${hasBias ? "b" : "nb"}`;
    const inputs = hasBias
      ? { X: xBuf, W: this.wGpu(wKey), B: this.wGpu(bKey) }
      : { X: xBuf, W: this.wGpu(wKey) };
    return this.dispatchUop(enc, bundle, name, inputs, temps).Y;
  }

  gpuConvT1d(enc, bundle, xBuf, cIn, cOut, tIn, k, s, p, op, g, wBufOrKey, bKey, temps) {
    const hasBias = !!bKey;
    const name = `convt1d_${cIn}_${cOut}_${tIn}_k${k}_s${s}_p${p}_op${op}_g${g}_${hasBias ? "b" : "nb"}`;
    const wBuf = typeof wBufOrKey === "string" ? this.wGpu(wBufOrKey) : wBufOrKey;
    const inputs = hasBias ? { X: xBuf, W: wBuf, B: this.wGpu(bKey) } : { X: xBuf, W: wBuf };
    return this.dispatchUop(enc, bundle, name, inputs, temps).Y;
  }

  gpuAdain1d(enc, bundle, xBuf, c, t, sVec, fcWKey, fcBKey, temps) {
    const h = runLinear1dCpu(sVec, 128, this.wCpu(fcWKey), 2 * c, this.wCpu(fcBKey));
    const gBuf = this.uploadF32(h.subarray(0, c), "adain_g");
    const bBuf = this.uploadF32(h.subarray(c, 2 * c), "adain_b");
    temps.push(gBuf, bBuf);
    const name = `adain1d_${c}_${t}`;
    return this.dispatchUop(enc, bundle, name, { X: xBuf, G: gBuf, B: bBuf }, temps).Y;
  }

  gpuLeakyRelu(enc, xBuf, n, slope, temps) {
    const out = this.allocGpu(n);
    const u = new ArrayBuffer(16);
    new Uint32Array(u, 0, 1)[0] = n;
    new Float32Array(u, 4, 1)[0] = slope;
    const [gx, gy] = grid1d(n);
    this.dispatchHelper(enc, "leaky_relu", [out, xBuf], new Uint8Array(u), gx, gy, 1, temps);
    return out;
  }

  gpuSnake1d(enc, xBuf, c, t, alphaKey, temps) {
    const n = c * t;
    const out = this.allocGpu(n);
    const u = new Uint32Array([c, t, 0, 0]);
    const [gx, gy] = grid1d(n);
    this.dispatchHelper(enc, "snake1d", [out, xBuf, this.wGpu(alphaKey)], u, gx, gy, 1, temps);
    return out;
  }

  gpuGeluNew(enc, xBuf, n, temps) {
    const out = this.allocGpu(n);
    const u = new Uint32Array([n, 0, 0, 0]);
    const [gx, gy] = grid1d(n);
    this.dispatchHelper(enc, "gelu_new", [out, xBuf], u, gx, gy, 1, temps);
    return out;
  }

  gpuAdd(enc, aBuf, bBuf, n, scale, temps) {
    const out = this.allocGpu(n);
    const u = new ArrayBuffer(16);
    new Uint32Array(u, 0, 1)[0] = n;
    new Float32Array(u, 4, 1)[0] = scale;
    const [gx, gy] = grid1d(n);
    this.dispatchHelper(enc, "add", [out, aBuf, bBuf], new Uint8Array(u), gx, gy, 1, temps);
    return out;
  }

  gpuAvg3(enc, aBuf, bBuf, cBuf, n, temps) {
    const out = this.allocGpu(n);
    const u = new Uint32Array([n, 0, 0, 0]);
    const [gx, gy] = grid1d(n);
    this.dispatchHelper(enc, "avg3", [out, aBuf, bBuf, cBuf], u, gx, gy, 1, temps);
    return out;
  }

  gpuTranspose2d(enc, xBuf, rows, cols, temps) {
    const out = this.allocGpu(rows * cols);
    const u = new Uint32Array([rows, cols, 0, 0]);
    const [gx, gy] = grid1d(rows * cols);
    this.dispatchHelper(enc, "transpose_2d", [out, xBuf], u, gx, gy, 1, temps);
    return out;
  }

  gpuUpsampleNearest2x(enc, xBuf, c, t, temps) {
    const out = this.allocGpu(c * 2 * t);
    const u = new Uint32Array([c, t, 0, 0]);
    const [gx, gy] = grid1d(c * 2 * t);
    this.dispatchHelper(enc, "upsample_nearest_2x", [out, xBuf], u, gx, gy, 1, temps);
    return out;
  }

  gpuReflectionPadLeft1(enc, xBuf, c, t, temps) {
    const out = this.allocGpu(c * (t + 1));
    const u = new Uint32Array([c, t, 0, 0]);
    const [gx, gy] = grid1d(c * (t + 1));
    this.dispatchHelper(enc, "reflection_pad_left_1", [out, xBuf], u, gx, gy, 1, temps);
    return out;
  }

  gpuCatChannels(enc, bufsAndSizes) {
    const total = bufsAndSizes.reduce((s, [, n]) => s + n, 0);
    const out = this.allocGpu(total);
    let off = 0;
    for (const [b, n] of bufsAndSizes) {
      enc.copyBufferToBuffer(b, 0, out, off * 4, n * 4);
      off += n;
    }
    return out;
  }

  gpuCatStyleTc(enc, xBuf, t, c, sBuf, temps) {
    const sc = 128;
    const out = this.allocGpu(t * (c + sc));
    const u = new Uint32Array([t, c, sc, 0]);
    const [gx, gy] = grid1d(t * (c + sc));
    this.dispatchHelper(enc, "cat_style_tc", [out, xBuf, sBuf], u, gx, gy, 1, temps);
    return out;
  }

  gpuLayerNormLast(enc, xBuf, n, d, gBuf, bBuf, eps, addOne, temps) {
    const out = this.allocGpu(n * d);
    const u = new ArrayBuffer(16);
    new Uint32Array(u, 0, 1)[0] = n;
    new Uint32Array(u, 4, 1)[0] = d;
    new Float32Array(u, 8, 1)[0] = eps;
    new Uint32Array(u, 12, 1)[0] = addOne ? 1 : 0;
    const [gx] = grid1d(n);
    this.dispatchHelper(enc, "layer_norm_last", [out, xBuf, gBuf, bBuf], new Uint8Array(u), gx, 1, 1, temps);
    return out;
  }

  gpuChannelLayerNorm(enc, xBuf, c, t, gKey, bKey, eps, temps) {
    const out = this.allocGpu(c * t);
    const u = new ArrayBuffer(16);
    new Uint32Array(u, 0, 1)[0] = c;
    new Uint32Array(u, 4, 1)[0] = t;
    new Float32Array(u, 8, 1)[0] = eps;
    const [gx] = grid1d(t);
    this.dispatchHelper(enc, "channel_layer_norm", [out, xBuf, this.wGpu(gKey), this.wGpu(bKey)], new Uint8Array(u), gx, 1, 1, temps);
    return out;
  }

  gpuMhaAlbert(enc, qBuf, kBuf, vBuf, t, numHeads, headDim, temps) {
    const out = this.allocGpu(t * numHeads * headDim);
    const u = new ArrayBuffer(16);
    new Uint32Array(u, 0, 1)[0] = t;
    new Uint32Array(u, 4, 1)[0] = numHeads;
    new Uint32Array(u, 8, 1)[0] = headDim;
    new Float32Array(u, 12, 1)[0] = 1.0 / Math.sqrt(headDim);
    const [gx] = grid1d(numHeads * t);
    this.dispatchHelper(enc, "mha_albert", [out, qBuf, kBuf, vBuf], new Uint8Array(u), gx, 1, 1, temps);
    return out;
  }

  gpuBiLstm(enc, bundle, prefix, xBuf, seqLen, inDim, temps) {
    const h = 256;
    const h4 = 1024;
    const out = this.allocGpu(seqLen * 2 * h);
    const zeros = new Float32Array(h);

    const runDir = (reverse) => {
      const sfx = reverse ? "_l0_reverse" : "_l0";
      const wIh = this.wGpu(`${prefix}.weight_ih${sfx}`);
      const wHh = this.wGpu(`${prefix}.weight_hh${sfx}`);
      const bSum = this.biasPairGpu(`${prefix}.bias_ih${sfx}`, `${prefix}.bias_hh${sfx}`);
      const preAll = this.dispatchUop(
        enc,
        bundle,
        `linear_${seqLen}_${inDim}_${h4}`,
        { X: xBuf, W: wIh, B: bSum },
        temps
      ).Y;
      temps.push(preAll);

      const preStep = this.allocGpu(h4);
      let hCur = this.uploadF32(zeros, "lstm_h0");
      let cCur = this.uploadF32(zeros, "lstm_c0");
      temps.push(preStep, hCur, cCur);

      const colOff = reverse ? h : 0;
      for (let i = 0; i < seqLen; i++) {
        const step = reverse ? (seqLen - 1 - i) : i;
        enc.copyBufferToBuffer(preAll, step * h4 * 4, preStep, 0, h4 * 4);
        const res = this.dispatchUop(
          enc,
          bundle,
          "lstm_cell_256",
          { Pre_t: preStep, H_prev: hCur, C_prev: cCur, W_hh: wHh },
          temps
        );
        enc.copyBufferToBuffer(res.H_new, 0, out, (step * 2 * h + colOff) * 4, h * 4);
        temps.push(res.H_new, res.C_new);
        hCur = res.H_new;
        cCur = res.C_new;
      }
    };

    runDir(false);
    runDir(true);
    return out;
  }

  gpuAdainResBlk1d(enc, bundle, prefix, xBuf, cIn, cOut, t, sVec, upsample, temps) {
    const tOut = upsample ? 2 * t : t;
    const learnedSc = cIn !== cOut;
    const r1 = this.gpuAdain1d(enc, bundle, xBuf, cIn, t, sVec, `${prefix}.norm1.fc.weight`, `${prefix}.norm1.fc.bias`, temps);
    const r2 = this.gpuLeakyRelu(enc, r1, cIn * t, 0.2, temps);
    const r3 = upsample
      ? this.gpuConvT1d(enc, bundle, r2, cIn, cIn, t, 3, 2, 1, 1, cIn, `${prefix}.pool.weight`, `${prefix}.pool.bias`, temps)
      : r2;
    const r4 = this.gpuConv1d(enc, bundle, r3, cIn, cOut, tOut, 3, 1, 1, 1, 1, `${prefix}.conv1.weight`, `${prefix}.conv1.bias`, temps);
    const r5 = this.gpuAdain1d(enc, bundle, r4, cOut, tOut, sVec, `${prefix}.norm2.fc.weight`, `${prefix}.norm2.fc.bias`, temps);
    const r6 = this.gpuLeakyRelu(enc, r5, cOut * tOut, 0.2, temps);
    const res = this.gpuConv1d(enc, bundle, r6, cOut, cOut, tOut, 3, 1, 1, 1, 1, `${prefix}.conv2.weight`, `${prefix}.conv2.bias`, temps);

    const sc0 = upsample ? this.gpuUpsampleNearest2x(enc, xBuf, cIn, t, temps) : xBuf;
    const sc = learnedSc
      ? this.gpuConv1d(enc, bundle, sc0, cIn, cOut, tOut, 1, 1, 0, 1, 1, `${prefix}.conv1x1.weight`, null, temps)
      : sc0;
    const out = this.gpuAdd(enc, res, sc, cOut * tOut, 1.0 / Math.SQRT2, temps);
    temps.push(r1, r2, r4, r5, r6, res);
    if (upsample) temps.push(r3, sc0);
    if (learnedSc) temps.push(sc);
    return out;
  }

  gpuAdainResBlock1(enc, bundle, prefix, xBuf, c, t, k, dilations, sVec, temps) {
    let cur = xBuf;
    for (let j = 0; j < 3; j++) {
      const d = dilations[j];
      const pad1 = ((k * d) - d) >> 1;
      const pad2 = (k - 1) >> 1;
      const xt1 = this.gpuAdain1d(enc, bundle, cur, c, t, sVec, `${prefix}.adain1.${j}.fc.weight`, `${prefix}.adain1.${j}.fc.bias`, temps);
      const xt2 = this.gpuSnake1d(enc, xt1, c, t, `${prefix}.alpha1.${j}`, temps);
      const xt3 = this.gpuConv1d(enc, bundle, xt2, c, c, t, k, 1, pad1, d, 1, `${prefix}.convs1.${j}.weight`, `${prefix}.convs1.${j}.bias`, temps);
      const xt4 = this.gpuAdain1d(enc, bundle, xt3, c, t, sVec, `${prefix}.adain2.${j}.fc.weight`, `${prefix}.adain2.${j}.fc.bias`, temps);
      const xt5 = this.gpuSnake1d(enc, xt4, c, t, `${prefix}.alpha2.${j}`, temps);
      const xt6 = this.gpuConv1d(enc, bundle, xt5, c, c, t, k, 1, pad2, 1, 1, `${prefix}.convs2.${j}.weight`, `${prefix}.convs2.${j}.bias`, temps);
      const next = this.gpuAdd(enc, cur, xt6, c * t, 1.0, temps);
      temps.push(xt1, xt2, xt3, xt4, xt5, xt6);
      if (j > 0) temps.push(cur);
      cur = next;
    }
    return cur;
  }

  async synthesize(inputIds, voiceId = "af_heart", opts = {}) {
    const speed = opts.speed ?? 1.0;
    const deterministic = opts.deterministic ?? false;
    const seed = opts.seed ?? 2026;
    const returnIntermediates = opts.returnIntermediates ?? false;

    const t0 = performance.now();
    await this.init();
    await this.loadWeights();
    const voicePack = await this.loadVoice(voiceId);

    const t = inputIds.length;
    const nPhonemes = Math.max(1, t - 2);
    const maxIdx = voicePack.shape[0] - 1;
    const vIdx = Math.max(0, Math.min(maxIdx, nPhonemes - 1));
    const style256 = voicePack.data.subarray(vIdx * 256, (vIdx + 1) * 256);
    const sDec = style256.subarray(0, 128);
    const sProsody = style256.subarray(128, 256);

    // Load initial bundle for token-length stages (t_tok = t, placeholder f_frames = 87 or cached)
    const initialF = opts.expectedFrames || 87;
    const bundleTok = await this.loadBundle(t, initialF);

    const tStage1 = performance.now();
    const temps1 = [];
    const enc1 = this.device.createCommandEncoder();

    // ---- Stage 1: CustomALBERT (12 shared layers) + bert_encoder ----
    const embDim = 128;
    const wEmb = this.wCpu("bert.embeddings.word_embeddings.weight");
    const pEmb = this.wCpu("bert.embeddings.position_embeddings.weight");
    const tEmb = this.wCpu("bert.embeddings.token_type_embeddings.weight");
    const embSum = new Float32Array(t * embDim);
    for (let ti = 0; ti < t; ti++) {
      const tok = inputIds[ti];
      const wb = tok * embDim;
      const pb = ti * embDim;
      for (let j = 0; j < embDim; j++) {
        embSum[pb + j] = wEmb[wb + j] + pEmb[pb + j] + tEmb[j];
      }
    }
    const embSumGpu = this.uploadF32(embSum, "emb_sum");
    temps1.push(embSumGpu);
    const embNorm = this.gpuLayerNormLast(
      enc1, embSumGpu, t, embDim,
      this.wGpu("bert.embeddings.LayerNorm.weight"),
      this.wGpu("bert.embeddings.LayerNorm.bias"),
      1e-12, false, temps1
    );
    temps1.push(embNorm);

    let xBert = this.gpuLinear(
      enc1, bundleTok, embNorm, t, 128, 768,
      "bert.encoder.embedding_hidden_mapping_in.weight",
      "bert.encoder.embedding_hidden_mapping_in.bias",
      temps1
    );
    const bpfx = "bert.encoder.albert_layer_groups.0.albert_layers.0.";
    for (let layer = 0; layer < 12; layer++) {
      const q = this.gpuLinear(enc1, bundleTok, xBert, t, 768, 768, `${bpfx}attention.query.weight`, `${bpfx}attention.query.bias`, temps1);
      const k = this.gpuLinear(enc1, bundleTok, xBert, t, 768, 768, `${bpfx}attention.key.weight`, `${bpfx}attention.key.bias`, temps1);
      const v = this.gpuLinear(enc1, bundleTok, xBert, t, 768, 768, `${bpfx}attention.value.weight`, `${bpfx}attention.value.bias`, temps1);
      const attnCtx = this.gpuMhaAlbert(enc1, q, k, v, t, 12, 64, temps1);
      const attnProj = this.gpuLinear(enc1, bundleTok, attnCtx, t, 768, 768, `${bpfx}attention.dense.weight`, `${bpfx}attention.dense.bias`, temps1);
      const x1Sum = this.gpuAdd(enc1, xBert, attnProj, t * 768, 1.0, temps1);
      const x1 = this.gpuLayerNormLast(enc1, x1Sum, t, 768, this.wGpu(`${bpfx}attention.LayerNorm.weight`), this.wGpu(`${bpfx}attention.LayerNorm.bias`), 1e-12, false, temps1);
      const ffnLin = this.gpuLinear(enc1, bundleTok, x1, t, 768, 2048, `${bpfx}ffn.weight`, `${bpfx}ffn.bias`, temps1);
      const ffnH = this.gpuGeluNew(enc1, ffnLin, t * 2048, temps1);
      const ffnO = this.gpuLinear(enc1, bundleTok, ffnH, t, 2048, 768, `${bpfx}ffn_output.weight`, `${bpfx}ffn_output.bias`, temps1);
      const x2Sum = this.gpuAdd(enc1, x1, ffnO, t * 768, 1.0, temps1);
      const x2 = this.gpuLayerNormLast(enc1, x2Sum, t, 768, this.wGpu(`${bpfx}full_layer_layer_norm.weight`), this.wGpu(`${bpfx}full_layer_layer_norm.bias`), 1e-12, false, temps1);
      temps1.push(xBert, q, k, v, attnCtx, attnProj, x1Sum, x1, ffnLin, ffnH, ffnO, x2Sum);
      xBert = x2;
    }

    const dEnTc = this.gpuLinear(enc1, bundleTok, xBert, t, 768, 512, "bert_encoder.weight", "bert_encoder.bias", temps1);
    temps1.push(dEnTc);

    // ---- Stage 2a: ProsodyPredictor DurationEncoder + duration_proj ----
    const sProsodyGpu = this.uploadF32(sProsody, "s_prosody");
    temps1.push(sProsodyGpu);
    let dCur = this.gpuCatStyleTc(enc1, dEnTc, t, 512, sProsodyGpu, temps1);
    for (let i = 0; i < 3; i++) {
      const lstmIdx = 2 * i;
      const alnIdx = lstmIdx + 1;
      const hOut = this.gpuBiLstm(enc1, bundleTok, `predictor.text_encoder.lstms.${lstmIdx}`, dCur, t, 640, temps1);
      const alnH = runLinear1dCpu(
        sProsody, 128,
        this.wCpu(`predictor.text_encoder.lstms.${alnIdx}.fc.weight`),
        1024,
        this.wCpu(`predictor.text_encoder.lstms.${alnIdx}.fc.bias`)
      );
      const gBuf = this.uploadF32(alnH.subarray(0, 512), "aln_g");
      const bBuf = this.uploadF32(alnH.subarray(512, 1024), "aln_b");
      const nOut = this.gpuLayerNormLast(enc1, hOut, t, 512, gBuf, bBuf, 1e-5, true, temps1);
      const cOut = this.gpuCatStyleTc(enc1, nOut, t, 512, sProsodyGpu, temps1);
      temps1.push(dCur, hOut, gBuf, bBuf, nOut);
      dCur = cOut;
    }
    const xDur = this.gpuBiLstm(enc1, bundleTok, "predictor.lstm", dCur, t, 640, temps1);
    const durLogitsGpu = this.gpuLinear(
      enc1, bundleTok, xDur, t, 512, 50,
      "predictor.duration_proj.linear_layer.weight",
      "predictor.duration_proj.linear_layer.bias",
      temps1
    );
    temps1.push(xDur);

    // ---- Stage 3a: TextEncoder (runs on token length t!) ----
    const teEmbW = this.wCpu("text_encoder.embedding.weight");
    const teXtc = new Float32Array(t * 512);
    for (let ti = 0; ti < t; ti++) {
      const tok = inputIds[ti];
      teXtc.set(teEmbW.subarray(tok * 512, (tok + 1) * 512), ti * 512);
    }
    const teXtcGpu = this.uploadF32(teXtc, "te_xtc");
    let teXct = this.gpuTranspose2d(enc1, teXtcGpu, t, 512, temps1);
    temps1.push(teXtcGpu);
    for (let i = 0; i < 3; i++) {
      const cOut = this.gpuConv1d(enc1, bundleTok, teXct, 512, 512, t, 5, 1, 2, 1, 1, `text_encoder.cnn.${i}.0.weight`, `text_encoder.cnn.${i}.0.bias`, temps1);
      const nOut = this.gpuChannelLayerNorm(enc1, cOut, 512, t, `text_encoder.cnn.${i}.1.gamma`, `text_encoder.cnn.${i}.1.beta`, 1e-5, temps1);
      const aOut = this.gpuLeakyRelu(enc1, nOut, 512 * t, 0.2, temps1);
      temps1.push(teXct, cOut, nOut);
      teXct = aOut;
    }
    const teLstmIn = this.gpuTranspose2d(enc1, teXct, 512, t, temps1);
    const tEnTc = this.gpuBiLstm(enc1, bundleTok, "text_encoder.lstm", teLstmIn, t, 512, temps1);
    const tEnGpu = this.gpuTranspose2d(enc1, tEnTc, t, 512, temps1);
    const dCtGpu = this.gpuTranspose2d(enc1, dCur, t, 640, temps1);
    temps1.push(teXct, teLstmIn, tEnTc);

    this.device.queue.submit([enc1.finish()]);

    // ---- Glue 1: Readback duration_logits, dCt, tEn -> compute pred_dur & expand ----
    const durLogits = await this.readbackF32(durLogitsGpu, t * 50);
    const dCt = await this.readbackF32(dCtGpu, 640 * t);
    const tEn = await this.readbackF32(tEnGpu, 512 * t);
    const bertDur = returnIntermediates ? await this.readbackF32(xBert, t * 768) : null;
    for (const b of temps1) b.destroy();
    xBert.destroy();
    dCur.destroy();
    durLogitsGpu.destroy();
    dCtGpu.destroy();
    tEnGpu.destroy();

    const predDur = new Array(t);
    let totalFrames = 0;
    for (let ti = 0; ti < t; ti++) {
      let sSum = 0.0;
      const base = ti * 50;
      for (let k = 0; k < 50; k++) {
        sSum += 1.0 / (1.0 + Math.exp(-durLogits[base + k]));
      }
      const dur = Math.max(1, rint(sSum / speed));
      predDur[ti] = dur;
      totalFrames += dur;
    }

    const f = totalFrames;
    const f2 = 2 * f;
    const l0 = 20 * f;
    const l1 = 120 * f + 1;
    const outSamples = 600 * f;

    // Fetch exact (t, f) compiled UOp WebGPU bundle if f != initialF
    const bundle = (f === initialF) ? bundleTok : await this.loadBundle(t, f);

    const enCt = expandByDurationsCt(dCt, 640, t, predDur, f);
    const asrCt = expandByDurationsCt(tEn, 512, t, predDur, f);

    // ---- Stage 2b: ProsodyPredictor F0 & N (GPU-resident!) ----
    const tStage2b = performance.now();
    const temps2 = [];
    const enc2 = this.device.createCommandEncoder();
    const enCtGpu = this.uploadF32(enCt, "en_ct");
    const enTcGpu = this.gpuTranspose2d(enc2, enCtGpu, 640, f, temps2);
    const sharedTc = this.gpuBiLstm(enc2, bundle, "predictor.shared", enTcGpu, f, 640, temps2);
    const sharedOut = this.gpuTranspose2d(enc2, sharedTc, f, 512, temps2);
    temps2.push(enCtGpu, enTcGpu, sharedTc, sharedOut);

    const f0_0 = this.gpuAdainResBlk1d(enc2, bundle, "predictor.F0.0", sharedOut, 512, 512, f, sProsody, false, temps2);
    const f0_1 = this.gpuAdainResBlk1d(enc2, bundle, "predictor.F0.1", f0_0, 512, 256, f, sProsody, true, temps2);
    const f0_2 = this.gpuAdainResBlk1d(enc2, bundle, "predictor.F0.2", f0_1, 256, 256, f2, sProsody, false, temps2);
    const f0PredGpu = this.gpuConv1d(enc2, bundle, f0_2, 256, 1, f2, 1, 1, 0, 1, 1, "predictor.F0_proj.weight", "predictor.F0_proj.bias", temps2);

    const n_0 = this.gpuAdainResBlk1d(enc2, bundle, "predictor.N.0", sharedOut, 512, 512, f, sProsody, false, temps2);
    const n_1 = this.gpuAdainResBlk1d(enc2, bundle, "predictor.N.1", n_0, 512, 256, f, sProsody, true, temps2);
    const n_2 = this.gpuAdainResBlk1d(enc2, bundle, "predictor.N.2", n_1, 256, 256, f2, sProsody, false, temps2);
    const nPredGpu = this.gpuConv1d(enc2, bundle, n_2, 256, 1, f2, 1, 1, 0, 1, 1, "predictor.N_proj.weight", "predictor.N_proj.bias", temps2);
    temps2.push(f0_0, f0_1, f0_2, n_0, n_1, n_2);

    this.device.queue.submit([enc2.finish()]);

    // ---- Glue 2: Readback tiny F0_pred (~1 KB) -> SourceModuleHnNSF + ForwardSTFT ----
    const f0Pred = await this.readbackF32(f0PredGpu, f2);
    const nPredRead = returnIntermediates ? await this.readbackF32(nPredGpu, f2) : null;
    for (const b of temps2) b.destroy();

    const tGlue2 = performance.now();
    const harSource = runHnNsfSource(
      f0Pred,
      this.wCpu("decoder.generator.m_source.l_linear.weight"),
      this.wCpu("decoder.generator.m_source.l_linear.bias"),
      { deterministic, seed }
    );
    const har = runForwardStft(harSource);
    const glue2Ms = performance.now() - tGlue2;

    // ---- Stage 4: Decoder + iSTFTNet Generator + CustomSTFT (100% GPU-resident!) ----
    const tStage4 = performance.now();
    const temps4 = [];
    const enc4 = this.device.createCommandEncoder();

    const asrGpu = this.uploadF32(asrCt, "asr");
    const harGpu = this.uploadF32(har, "har");
    temps4.push(asrGpu, harGpu, f0PredGpu, nPredGpu);

    const f0C = this.gpuConv1d(enc4, bundle, f0PredGpu, 1, 1, f2, 3, 2, 1, 1, 1, "decoder.F0_conv.weight", "decoder.F0_conv.bias", temps4);
    const nC = this.gpuConv1d(enc4, bundle, nPredGpu, 1, 1, f2, 3, 2, 1, 1, 1, "decoder.N_conv.weight", "decoder.N_conv.bias", temps4);
    const xEncIn = this.gpuCatChannels(enc4, [[asrGpu, 512 * f], [f0C, f], [nC, f]]);
    const decEncode = this.gpuAdainResBlk1d(enc4, bundle, "decoder.encode", xEncIn, 514, 1024, f, sDec, false, temps4);
    const asrRes = this.gpuConv1d(enc4, bundle, asrGpu, 512, 64, f, 1, 1, 0, 1, 1, "decoder.asr_res.0.weight", "decoder.asr_res.0.bias", temps4);

    const d0In = this.gpuCatChannels(enc4, [[decEncode, 1024 * f], [asrRes, 64 * f], [f0C, f], [nC, f]]);
    const d0 = this.gpuAdainResBlk1d(enc4, bundle, "decoder.decode.0", d0In, 1090, 1024, f, sDec, false, temps4);
    const d1In = this.gpuCatChannels(enc4, [[d0, 1024 * f], [asrRes, 64 * f], [f0C, f], [nC, f]]);
    const d1 = this.gpuAdainResBlk1d(enc4, bundle, "decoder.decode.1", d1In, 1090, 1024, f, sDec, false, temps4);
    const d2In = this.gpuCatChannels(enc4, [[d1, 1024 * f], [asrRes, 64 * f], [f0C, f], [nC, f]]);
    const d2 = this.gpuAdainResBlk1d(enc4, bundle, "decoder.decode.2", d2In, 1090, 1024, f, sDec, false, temps4);
    const d3In = this.gpuCatChannels(enc4, [[d2, 1024 * f], [asrRes, 64 * f], [f0C, f], [nC, f]]);
    const decDecode = this.gpuAdainResBlk1d(enc4, bundle, "decoder.decode.3", d3In, 1090, 512, f, sDec, true, temps4);
    temps4.push(f0C, nC, xEncIn, asrRes, d0In, d0, d1In, d1, d2In, d2, d3In);
    if (!returnIntermediates) temps4.push(decEncode);

    // Generator Upsample Stage 0: [512, 2F] -> [256, 20F]
    const x0 = this.gpuLeakyRelu(enc4, decDecode, 512 * f2, 0.1, temps4);
    const xs0C = this.gpuConv1d(enc4, bundle, harGpu, 22, 256, l1, 12, 6, 3, 1, 1, "decoder.generator.noise_convs.0.weight", "decoder.generator.noise_convs.0.bias", temps4);
    const xs0 = this.gpuAdainResBlock1(enc4, bundle, "decoder.generator.noise_res.0", xs0C, 256, l0, 7, [1, 3, 5], sDec, temps4);
    const up0 = this.gpuConvT1d(enc4, bundle, x0, 512, 256, f2, 20, 10, 5, 0, 1, "decoder.generator.ups.0.weight", "decoder.generator.ups.0.bias", temps4);
    const x0Sum = this.gpuAdd(enc4, up0, xs0, 256 * l0, 1.0, temps4);
    const rb0 = this.gpuAdainResBlock1(enc4, bundle, "decoder.generator.resblocks.0", x0Sum, 256, l0, 3, [1, 3, 5], sDec, temps4);
    const rb1 = this.gpuAdainResBlock1(enc4, bundle, "decoder.generator.resblocks.1", x0Sum, 256, l0, 7, [1, 3, 5], sDec, temps4);
    const rb2 = this.gpuAdainResBlock1(enc4, bundle, "decoder.generator.resblocks.2", x0Sum, 256, l0, 11, [1, 3, 5], sDec, temps4);
    const x0Out = this.gpuAvg3(enc4, rb0, rb1, rb2, 256 * l0, temps4);
    temps4.push(x0, xs0C, xs0, up0, x0Sum, rb0, rb1, rb2);
    if (!returnIntermediates) temps4.push(decDecode);

    // Generator Upsample Stage 1: [256, 20F] -> [128, 120F + 1]
    const x1 = this.gpuLeakyRelu(enc4, x0Out, 256 * l0, 0.1, temps4);
    const xs1C = this.gpuConv1d(enc4, bundle, harGpu, 22, 128, l1, 1, 1, 0, 1, 1, "decoder.generator.noise_convs.1.weight", "decoder.generator.noise_convs.1.bias", temps4);
    const xs1 = this.gpuAdainResBlock1(enc4, bundle, "decoder.generator.noise_res.1", xs1C, 128, l1, 11, [1, 3, 5], sDec, temps4);
    const up1 = this.gpuConvT1d(enc4, bundle, x1, 256, 128, l0, 12, 6, 3, 0, 1, "decoder.generator.ups.1.weight", "decoder.generator.ups.1.bias", temps4);
    const up1Pad = this.gpuReflectionPadLeft1(enc4, up1, 128, l1 - 1, temps4);
    const x1Sum = this.gpuAdd(enc4, up1Pad, xs1, 128 * l1, 1.0, temps4);
    const rb3 = this.gpuAdainResBlock1(enc4, bundle, "decoder.generator.resblocks.3", x1Sum, 128, l1, 3, [1, 3, 5], sDec, temps4);
    const rb4 = this.gpuAdainResBlock1(enc4, bundle, "decoder.generator.resblocks.4", x1Sum, 128, l1, 7, [1, 3, 5], sDec, temps4);
    const rb5 = this.gpuAdainResBlock1(enc4, bundle, "decoder.generator.resblocks.5", x1Sum, 128, l1, 11, [1, 3, 5], sDec, temps4);
    const x1Out = this.gpuAvg3(enc4, rb3, rb4, rb5, 128 * l1, temps4);
    temps4.push(x0Out, x1, xs1C, xs1, up1, up1Pad, x1Sum, rb3, rb4, rb5);

    // Post Conv + exp/sin + CustomSTFT Inverse + Hann overlap-add norm
    const xPostIn = this.gpuLeakyRelu(enc4, x1Out, 128 * l1, 0.01, temps4);
    const xPost = this.gpuConv1d(enc4, bundle, xPostIn, 128, 22, l1, 7, 1, 3, 1, 1, "decoder.generator.conv_post.weight", "decoder.generator.conv_post.bias", temps4);
    const specGpu = this.allocGpu(22 * l1);
    const [sgx, sgy] = grid1d(11 * l1);
    this.dispatchHelper(enc4, "exp_sin_istft_spec", [specGpu, xPost], new Uint32Array([11, l1, 0, 0]), sgx, sgy, 1, temps4);
    const rawWaveGpu = this.gpuConvT1d(enc4, bundle, specGpu, 22, 1, l1, 20, 5, 10, 0, 1, this.istftBasisGpu, null, temps4);

    if (!this.hannNormGpuCache.has(l1)) {
      this.hannNormGpuCache.set(l1, this.uploadF32(hannOverlapAddNorm(l1, 20, 5), `hann_${l1}`));
    }
    const hannNormGpu = this.hannNormGpuCache.get(l1);
    const audioGpu = this.allocGpu(outSamples);
    const [agx, agy] = grid1d(outSamples);
    this.dispatchHelper(enc4, "istft_hann_norm", [audioGpu, rawWaveGpu, hannNormGpu], new Uint32Array([outSamples, 0, 0, 0]), agx, agy, 1, temps4);
    temps4.push(x1Out, xPostIn, xPost, specGpu, rawWaveGpu);

    this.device.queue.submit([enc4.finish()]);

    const pcm = await this.readbackF32(audioGpu, outSamples);
    const intermediates = returnIntermediates
      ? {
          bert_dur: bertDur,
          duration_logits: durLogits,
          pred_dur: predDur,
          F0_pred: f0Pred,
          N_pred: nPredRead,
          dec_encode: await this.readbackF32(decEncode, 1024 * f),
          dec_decode: await this.readbackF32(decDecode, 512 * f2),
          har_source: harSource
        }
      : null;

    for (const b of temps4) b.destroy();
    if (returnIntermediates) {
      decEncode.destroy();
      decDecode.destroy();
    }
    audioGpu.destroy();

    const totalMs = performance.now() - t0;
    const durationSec = pcm.length / 24000.0;
    return {
      pcm,
      sampleRate: 24000,
      durationSec,
      predDur,
      totalFrames: f,
      latencyMs: totalMs,
      rtf: (totalMs / 1000.0) / Math.max(1e-6, durationSec),
      timings: {
        totalMs,
        stage1To3aMs: tStage2b - tStage1,
        stage2bMs: tGlue2 - tStage2b,
        glue2HnNsfStftMs: glue2Ms,
        stage4DecoderGenMs: performance.now() - tStage4
      },
      shaderCount: Object.keys(bundle.shaders).length,
      kernelTemplateCount: Object.keys(bundle.kernels).length,
      adapter: this.adapterInfo,
      intermediates
    };
  }
}
