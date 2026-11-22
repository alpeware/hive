const $ = (id) => document.getElementById(id);
const PALETTE = ["#f59e0b", "#38bdf8", "#a78bfa", "#34d399", "#fb7185", "#f97316", "#22d3ee", "#e879f9"];
const DEFAULT_VOICES = { simon: "am_michael", axiom: "bm_george", nexus: "am_adam", vector: "bm_lewis", lumen: "af_heart" };

// BIP-017b: Zero-CDN self-hosted inference paths & BIP-017a weight manifests
const INFERENCE_CONFIG = Object.assign({
  kokoroModuleUrl: "/app/vendor/kokoro-js.mjs",
  transformersModuleUrl: "/app/vendor/transformers.mjs",
  vadModuleUrl: "/app/vendor/vad-web.mjs",
  ortWasmPaths: "/app/ort/",
  artifactMirrors: [
    "https://ns2.bitecho.org",
    "https://ns1.bitecho.org",
    "https://ns3.bitecho.org",
    "https://bitecho.org"
  ],
  weightManifests: {
    af_heart: {
      manifestCid: "f5122cb51e7b16927fd37033c133e01100e6209e13b3e9405688d5be43c0136d",
      manifestBlobCid: "d92400a1dbffe0af40cc48471156085f4852c61399bfb0ab6e86f7d87ae58db6",
      totalSha256: "2c1c733b0e6576c810e268d3e440c21dea4e0f0131a3ba4cfc98d7fe6136d094"
    }
  }
}, window.__HIVE_INFERENCE_CONFIG__ || {});

let node = null;
let unsub = null;
let activeTarget = { kind: "subnet", id: "bitecho.org/dev" };
let messageLog = [];
const seenMessageKeys = new Set();
let petnamesByPk = {};

function messageDedupeKey(ev) {
  if (ev.msgId || ev.msg_id) return `id:${ev.msgId || ev.msg_id}`;
  const scope = ev.subnetId || ev.sessionId || "";
  return `seq:${ev.type || ""}|${scope}|${ev.from || ""}|${ev.seq ?? ""}|${ev.text || ""}`;
}
let kokoroModel = null;
let einsumWebGpuRunner = null;
let audioCtx = null;
let activeTtsSource = null;

// Local Browser STT + Silero VAD state
let sttPipelines = {};
let pttRecording = false;
let vadActive = false;
let micStream = null;
let micAudioCtx = null;
let micProcessor = null;
let sileroVadInstance = null;
let pttChunks = [];
let vadSpeechChunks = [];
let vadInSpeech = false;
let vadSilenceMs = 0;
let ttsPausedForBargeIn = false;

// ---------------------------------------------------------------------------
// BIP-017a + BIP-017b: Verified Chunked Weight Fetch + Zero-Copy OPFS Blob URL
// ---------------------------------------------------------------------------

async function sha256Hex(uint8) {
  const digest = await crypto.subtle.digest("SHA-256", uint8);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function writeOpfsFile(filename, bytes) {
  const root = await navigator.storage.getDirectory();
  const dir = await root.getDirectoryHandle("bitecho-weights", { create: true });
  const fh = await dir.getFileHandle(filename, { create: true });
  const writable = await fh.createWritable();
  await writable.write(bytes);
  await writable.close();
  const file = await fh.getFile();
  return { file, objectUrl: URL.createObjectURL(file) };
}

async function fetchManifestJson(manifestBlobCid, mirrors) {
  let lastErr = "manifest-fetch-failed";
  for (const m of mirrors) {
    try {
      const url = `${String(m).replace(/\/+$/, "")}/artifact/v1/blob/${manifestBlobCid}`;
      const res = await fetch(url);
      if (res.ok) return await res.json();
      lastErr = `http-${res.status}`;
    } catch (e) {
      lastErr = (e && e.message) || String(e);
    }
  }
  throw new Error(lastErr);
}

async function loadWeightViaOpfs(manifestCid, opts = {}) {
  const api = window.BitechoLightNode;
  if (!api || typeof api.getChunkedArtifact !== "function") {
    throw new Error("BitechoLightNode.getChunkedArtifact v1.1.0 unavailable");
  }
  const mirrors = opts.mirrors || INFERENCE_CONFIG.artifactMirrors;
  const concurrency = opts.concurrency || 3;
  const fetchOpts = { mirrors, concurrency };
  if (opts.manifest) {
    fetchOpts.manifest = opts.manifest;
  } else if (opts.manifestBlobCid) {
    fetchOpts.manifest = await fetchManifestJson(opts.manifestBlobCid, mirrors);
  }
  const res = await api.getChunkedArtifact(manifestCid, fetchOpts);
  if (!res || !res.ok || !res.bytes) {
    throw new Error((res && res.error) || "chunked-artifact-fetch-failed");
  }
  const filename = opts.filename || `${manifestCid}.bin`;
  const { file, objectUrl } = await writeOpfsFile(filename, res.bytes);
  return {
    ok: true,
    manifestCid: res.manifestCid || manifestCid,
    totalSha256: res.totalSha256,
    totalBytes: res.totalBytes || file.size,
    opfsSize: file.size,
    objectUrl,
    revoke: () => URL.revokeObjectURL(objectUrl)
  };
}

window.__HIVE_OPFS_LOADER__ = {
  config: INFERENCE_CONFIG,
  sha256Hex,
  writeOpfsFile,
  loadWeightViaOpfs
};

function colorForKey(key) {
  let h = 0;
  const s = String(key || "anon");
  for (let i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0;
  return PALETTE[Math.abs(h) % PALETTE.length];
}

function unlockAudioSync() {
  if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  if (audioCtx.state === "suspended") audioCtx.resume().catch(() => {});
}

function pauseTtsForBargeIn() {
  if (audioCtx && audioCtx.state === "running" && activeTtsSource) {
    audioCtx.suspend().catch(() => {});
    ttsPausedForBargeIn = true;
  }
}

function resumeTtsIfBargeInEmpty() {
  if (ttsPausedForBargeIn && audioCtx && audioCtx.state === "suspended") {
    audioCtx.resume().catch(() => {});
    ttsPausedForBargeIn = false;
  }
}

async function speakText(text, voiceId) {
  const engine = $("tts-engine").value;
  if (engine === "off" || !text) return;
  const voice = voiceId || "af_heart";
  if (engine === "webspeech") {
    const u = new SpeechSynthesisUtterance(text);
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(u);
    return;
  }
  if (engine === "einsum-webgpu") {
    try {
      $("tts-status").textContent = `clj-einsum :uop-webgpu (${voice})...`;
      if (!einsumWebGpuRunner) {
        const { KokoroWebGpuRunner } = await import("./kokoro-webgpu-runner.js");
        einsumWebGpuRunner = new KokoroWebGpuRunner("");
      }
      const phRes = await fetch("/api/kokoro-phonemize", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text })
      });
      if (!phRes.ok) throw new Error(`phonemize HTTP ${phRes.status}`);
      const ph = await phRes.json();
      const out = await einsumWebGpuRunner.synthesize(ph.input_ids, voice, { speed: 1.0 });
      unlockAudioSync();
      const buf = audioCtx.createBuffer(1, out.pcm.length, out.sampleRate || 24000);
      buf.getChannelData(0).set(out.pcm);
      const src = audioCtx.createBufferSource();
      src.buffer = buf;
      src.connect(audioCtx.destination);
      activeTtsSource = src;
      src.onended = () => { if (activeTtsSource === src) activeTtsSource = null; };
      src.start(0);
      $("tts-status").textContent = `Played via clj-einsum :uop-webgpu (${Math.round(out.latencyMs)} ms, RTF ${out.rtf.toFixed(2)})`;
    } catch (e) {
      $("tts-status").textContent = `uop-webgpu error: ${e.message}`;
    }
    return;
  }
  if (engine === "einsum-cpu") {
    $("tts-status").textContent = `clj-einsum :uop-cpu (${voice})...`;
    const res = await fetch("/api/tts/uop-cpu", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text, voice })
    });
    const arr = await res.arrayBuffer();
    unlockAudioSync();
    const buf = await audioCtx.decodeAudioData(arr);
    const src = audioCtx.createBufferSource();
    src.buffer = buf;
    src.connect(audioCtx.destination);
    activeTtsSource = src;
    src.onended = () => { if (activeTtsSource === src) activeTtsSource = null; };
    src.start(0);
    $("tts-status").textContent = `Played via clj-einsum :uop-cpu (${res.headers.get("X-Einsum-Latency-Ms")} ms)`;
    return;
  }
  try {
    if (!kokoroModel) {
      $("tts-status").textContent = "Loading self-hosted Kokoro-82M...";
      const { KokoroTTS } = await import(INFERENCE_CONFIG.kokoroModuleUrl);
      const useGpu = !!navigator.gpu;
      const modelOpts = {
        dtype: useGpu ? "fp32" : "q8",
        device: useGpu ? "webgpu" : "wasm"
      };
      if (INFERENCE_CONFIG.weightManifests && INFERENCE_CONFIG.weightManifests.kokoro82m) {
        const w = INFERENCE_CONFIG.weightManifests.kokoro82m;
        const opfs = await loadWeightViaOpfs(w.manifestCid, {
          manifestBlobCid: w.manifestBlobCid,
          filename: "kokoro-82m.onnx"
        });
        modelOpts.externalDataUrl = opfs.objectUrl;
      }
      kokoroModel = await KokoroTTS.from_pretrained("onnx-community/Kokoro-82M-v1.0-ONNX", modelOpts);
    }
    $("tts-status").textContent = `Speaking (${voice})...`;
    const out = await kokoroModel.generate(text, { voice });
    unlockAudioSync();
    const buf = audioCtx.createBuffer(1, out.audio.length, out.sampling_rate || 24000);
    buf.getChannelData(0).set(out.audio);
    const src = audioCtx.createBufferSource();
    src.buffer = buf;
    src.connect(audioCtx.destination);
    activeTtsSource = src;
    src.onended = () => { if (activeTtsSource === src) activeTtsSource = null; };
    src.start(0);
    $("tts-status").textContent = `Kokoro ready (${voice})`;
  } catch (e) {
    $("tts-status").textContent = `TTS error: ${e.message}`;
  }
}

// ---------------------------------------------------------------------------
// Local Browser STT (Moonshine Tiny / Whisper Tiny) + Silero VAD + Hysteresis
// 100% Self-Hosted via BIP-017a Chunked Manifests + transformers.js customCache
// ---------------------------------------------------------------------------

const STT_CACHE_NAME = "bitecho-stt-v1";

const SELF_HOSTED_STT_ASSETS = {
  ortWasmManifest: {
    manifestCid: "db0e879f2ad741c34f8089d4c934fcb4ef92172bcd8963c25858d5d74690ab27",
    manifestBlobCid: "f63d896b4bace5de81ec2105bd07c1d14a3cc634e584142a8ee8c9aec43fd3d9",
    totalSha256: "c46655e8a94afc45338d4cb2b840475f88e5012d524509916e505079c00bfa39",
    totalBytes: 21596019,
    contentType: "application/wasm"
  },
  "onnx-community/moonshine-tiny-ONNX": {
    "config.json": {
      cid: "558e1e02069137c796ace1e50c48d8fe451f04a295929138e6bea885517f0edb",
      contentType: "application/json"
    },
    "generation_config.json": {
      cid: "f9b3f711b57be7def2e50a8942f64f36ee0a55fad5b84ff93a687b6c5bcc1d44",
      contentType: "application/json"
    },
    "preprocessor_config.json": {
      cid: "fa43a7017ef85cd1d0fba0d9aae77c8adb16990ae6f11115631f41ec5d8aa679",
      contentType: "application/json"
    },
    "special_tokens_map.json": {
      cid: "ca3d163bab055381827226140568f3bef7eaac187cebd76878e0b63e9e442356",
      contentType: "application/json"
    },
    "tokenizer_config.json": {
      cid: "edaee394565d428ea98a663ae7209cdcfeefc5585c42d7a570ff7c986df2cd15",
      contentType: "application/json"
    },
    "tokenizer.json": {
      manifestCid: "2c41c012ce9b189a99536585666ab95ff0cf4c6ade1e46a9befd35aa45488db5",
      manifestBlobCid: "8e173ddac450578b654efca9800925ef4437f9117c8cd1005f3c8fce701cf61b",
      totalSha256: "7b913404bdd039af4756783218af4440bc07fb7d6d8258d677e34f95b3ec416f",
      totalBytes: 3761754,
      contentType: "application/json"
    },
    "onnx/encoder_model_quantized.onnx": {
      manifestCid: "c2d87cfc654a88468e441f65266a19a332aa400ede3a7ea87c4e8fb98b98669d",
      manifestBlobCid: "236dcceadfb1db189d6523583503ab85b9fc86a86d6c0abfa16080a2f766d983",
      totalSha256: "c6fc4b7bc5af75c0591fd157a1f3829b533d18e9769a888fd95a62e470dd4f4a",
      totalBytes: 7937661,
      contentType: "application/octet-stream"
    },
    "onnx/decoder_model_merged_quantized.onnx": {
      manifestCid: "61728114e3a414037701cbb3585e45d3b3036c1e4b6f2c936df492e4de03752e",
      manifestBlobCid: "3d707dd9481337cf2f3d191c8deb620d4d78f4ffd3e11668844368888b34f45b",
      totalSha256: "eed87831c3a6103534aae7d47a5d485025c659a1323901513961c39fe8a1a367",
      totalBytes: 20243286,
      contentType: "application/octet-stream"
    }
  }
};

let ortWasmBinaryPromise = null;

function sttArtifactMirrors() {
  const raw = [];
  if (window.location && /^https?:$/i.test(window.location.protocol)) {
    raw.push(window.location.origin);
  }
  raw.push("https://ns1.bitecho.org", "https://ns2.bitecho.org", "https://ns3.bitecho.org");
  return [...new Set(raw.map((m) => String(m).replace(/\/+$/, "")))];
}

function sttCacheKeyUrl(key) {
  const origin = (window.location && /^https?:$/i.test(window.location.protocol))
    ? window.location.origin
    : "https://bitecho.org";
  return `${origin}/__bitecho_stt_cache__/${encodeURIComponent(key)}`;
}

async function openSttCacheSafe() {
  if (typeof caches === "undefined") return null;
  try {
    return await caches.open(STT_CACHE_NAME);
  } catch (_) {
    return null;
  }
}

async function fetchSingleBlobVerified(cid, mirrors) {
  let lastErr = "single-blob-fetch-failed";
  for (const m of mirrors) {
    try {
      const url = `${m}/artifact/v1/blob/${cid}`;
      const res = await fetch(url);
      if (res.ok) {
        const u8 = new Uint8Array(await res.arrayBuffer());
        const actual = await sha256Hex(u8);
        if (actual === cid) return u8;
        lastErr = `cid-mismatch:${actual.slice(0, 12)}`;
      } else {
        lastErr = `http-${res.status}`;
      }
    } catch (e) {
      lastErr = (e && e.message) || String(e);
    }
  }
  const api = window.BitechoLightNode;
  if (api && typeof api.getArtifact === "function") {
    const res = await api.getArtifact({ cid });
    if (res && res.ok && res.bytes) {
      const u8 = res.bytes instanceof Uint8Array ? res.bytes : new Uint8Array(res.bytes);
      if ((await sha256Hex(u8)) === cid) return u8;
    }
  }
  throw new Error(`Failed to fetch verified blob ${cid.slice(0, 12)}: ${lastErr}`);
}

async function fetchChunkedAssetBytes(entry, label, mirrors) {
  const cache = await openSttCacheSafe();
  const cacheUrl = sttCacheKeyUrl(`chunked:${entry.manifestCid}`);
  if (cache) {
    try {
      const hit = await cache.match(cacheUrl);
      if (hit && hit.ok) {
        return new Uint8Array(await hit.arrayBuffer());
      }
    } catch (_) {}
  }
  const api = window.BitechoLightNode;
  if (!api || typeof api.getChunkedArtifact !== "function") {
    throw new Error("BitechoLightNode.getChunkedArtifact v1.1.0 unavailable");
  }
  const mb = entry.totalBytes ? ` (${(entry.totalBytes / 1048576).toFixed(1)} MiB)` : "";
  if ($("stt-status")) {
    $("stt-status").textContent = `Fetching ${label}${mb} via BIP-017a...`;
  }
  const manifest = await fetchManifestJson(entry.manifestBlobCid, mirrors);
  const res = await api.getChunkedArtifact(entry.manifestCid, {
    manifest,
    mirrors,
    concurrency: 3
  });
  if (!res || !res.ok || !res.bytes) {
    throw new Error((res && res.error) || `chunked-fetch-failed:${label}`);
  }
  const bytes = res.bytes instanceof Uint8Array ? res.bytes : new Uint8Array(res.bytes);
  if (cache) {
    try {
      await cache.put(cacheUrl, new Response(bytes, {
        status: 200,
        headers: { "Content-Type": entry.contentType || "application/octet-stream" }
      }));
    } catch (_) {}
  }
  return bytes;
}

function lookupSelfHostedSttEntry(reqUrl) {
  const urlStr = typeof reqUrl === "string" ? reqUrl : (reqUrl && reqUrl.url ? reqUrl.url : "");
  if (!urlStr) return null;
  for (const modelId of Object.keys(SELF_HOSTED_STT_ASSETS)) {
    if (modelId === "ortWasmManifest") continue;
    const files = SELF_HOSTED_STT_ASSETS[modelId];
    const localPrefix = `/models/${modelId}/`;
    const localIdx = urlStr.indexOf(localPrefix);
    let relpath = null;
    if (localIdx !== -1) {
      relpath = urlStr.slice(localIdx + localPrefix.length).split("?")[0];
    } else {
      const resolveMarker = `/${modelId}/resolve/main/`;
      const rIdx = urlStr.indexOf(resolveMarker);
      if (rIdx !== -1) {
        relpath = urlStr.slice(rIdx + resolveMarker.length).split("?")[0];
      }
    }
    if (relpath && Object.prototype.hasOwnProperty.call(files, relpath)) {
      return { modelId, relpath, entry: files[relpath] };
    }
  }
  return null;
}

async function resolveSelfHostedSttAsset(reqUrl) {
  const hit = lookupSelfHostedSttEntry(reqUrl);
  if (!hit) return undefined;
  const { relpath, entry } = hit;
  const contentType = entry.contentType || "application/octet-stream";
  const cache = await openSttCacheSafe();
  const cacheId = entry.manifestCid ? `chunked:${entry.manifestCid}` : `blob:${entry.cid}`;
  const cacheUrl = sttCacheKeyUrl(cacheId);
  if (cache) {
    try {
      const cachedResp = await cache.match(cacheUrl);
      if (cachedResp && cachedResp.ok) {
        return cachedResp;
      }
    } catch (_) {}
  }
  const mirrors = sttArtifactMirrors();
  let bytes;
  if (entry.manifestCid) {
    bytes = await fetchChunkedAssetBytes(entry, relpath, mirrors);
  } else {
    if ($("stt-status")) {
      $("stt-status").textContent = `Fetching ${relpath}...`;
    }
    bytes = await fetchSingleBlobVerified(entry.cid, mirrors);
    if (cache) {
      try {
        await cache.put(cacheUrl, new Response(bytes, {
          status: 200,
          headers: { "Content-Type": contentType }
        }));
      } catch (_) {}
    }
  }
  return new Response(bytes, {
    status: 200,
    headers: { "Content-Type": contentType }
  });
}

async function ensureOrtWasmBinary() {
  if (!ortWasmBinaryPromise) {
    ortWasmBinaryPromise = fetchChunkedAssetBytes(
      SELF_HOSTED_STT_ASSETS.ortWasmManifest,
      "ORT WASM",
      sttArtifactMirrors()
    ).catch((err) => {
      ortWasmBinaryPromise = null;
      throw err;
    });
  }
  return ortWasmBinaryPromise;
}

function downsampleTo16k(float32Buf, inputRate) {
  if (inputRate === 16000) return float32Buf;
  const ratio = inputRate / 16000;
  const outLen = Math.max(1, Math.round(float32Buf.length / ratio));
  const out = new Float32Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const start = Math.floor(i * ratio);
    const end = Math.min(float32Buf.length, Math.floor((i + 1) * ratio));
    let sum = 0;
    let cnt = 0;
    for (let j = start; j < end; j++) { sum += float32Buf[j]; cnt++; }
    out[i] = cnt > 0 ? sum / cnt : 0;
  }
  return out;
}

function concatFloat32(chunks) {
  let total = 0;
  for (const c of chunks) total += c.length;
  const out = new Float32Array(total);
  let off = 0;
  for (const c of chunks) { out.set(c, off); off += c.length; }
  return out;
}

async function getSttPipeline(engineKey) {
  if (sttPipelines[engineKey]) return sttPipelines[engineKey];
  const modelId = engineKey === "moonshine-tiny"
    ? "onnx-community/moonshine-tiny-ONNX"
    : "Xenova/whisper-tiny.en";
  $("stt-status").textContent = `Loading ${engineKey} locally (self-hosted BIP-017a)...`;
  const { pipeline, env } = await import(INFERENCE_CONFIG.transformersModuleUrl);
  env.allowLocalModels = true;
  env.allowRemoteModels = false;
  env.useBrowserCache = false;
  env.useCustomCache = true;
  env.customCache = {
    async match(reqUrl) {
      return resolveSelfHostedSttAsset(reqUrl);
    },
    async put(_reqUrl, _resp) {
      return undefined;
    }
  };
  const wasmBytes = await ensureOrtWasmBinary();
  if (env.backends && env.backends.onnx && env.backends.onnx.wasm) {
    env.backends.onnx.wasm.wasmPaths = INFERENCE_CONFIG.ortWasmPaths;
    env.backends.onnx.wasm.wasmBinary = wasmBytes;
  }
  if (env.wasm) {
    env.wasm.wasmPaths = INFERENCE_CONFIG.ortWasmPaths;
    env.wasm.wasmBinary = wasmBytes;
  }
  $("stt-status").textContent = `Initializing ${engineKey} ONNX sessions...`;
  const pipe = await pipeline("automatic-speech-recognition", modelId, {
    dtype: "q8",
    device: "wasm"
  });
  sttPipelines[engineKey] = pipe;
  $("stt-status").textContent = `${engineKey} ready (100% self-hosted)`;
  return pipe;
}

async function transcribeUtterance(audio16k) {
  const engineKey = $("stt-engine").value;
  if (engineKey === "off") return;
  if (!audio16k || audio16k.length < 2400) {
    resumeTtsIfBargeInEmpty();
    return;
  }
  const t0 = performance.now();
  try {
    const pipe = await getSttPipeline(engineKey);
    $("stt-status").textContent = `Transcribing locally (${(audio16k.length / 16000).toFixed(1)}s)...`;
    const res = await pipe(audio16k);
    const text = String((res && res.text) || "").trim();
    const ms = Math.round(performance.now() - t0);
    if (!text || text === "[BLANK_AUDIO]") {
      $("stt-status").textContent = `No speech (${ms} ms)`;
      resumeTtsIfBargeInEmpty();
      return;
    }
    ttsPausedForBargeIn = false;
    const prev = $("msg-input").value;
    $("msg-input").value = prev ? `${prev} ${text}` : text;
    $("stt-status").textContent = `Transcribed (${engineKey}, ${ms} ms)`;
    $("stt-status").className = "pill ok";
    if ($("chk-auto-send").checked && node) {
      await sendCurrentMessage();
    }
  } catch (e) {
    $("stt-status").textContent = `STT error: ${e.message}`;
    $("stt-status").className = "pill warn";
    resumeTtsIfBargeInEmpty();
  }
}

async function startMicCapture(onChunk16k) {
  micStream = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 }
  });
  micAudioCtx = new (window.AudioContext || window.webkitAudioContext)();
  const source = micAudioCtx.createMediaStreamSource(micStream);
  micProcessor = micAudioCtx.createScriptProcessor(2048, 1, 1);
  micProcessor.onaudioprocess = (ev) => {
    const raw = ev.inputBuffer.getChannelData(0);
    const pcm16k = downsampleTo16k(raw, micAudioCtx.sampleRate);
    let sumSq = 0;
    for (let i = 0; i < pcm16k.length; i++) sumSq += pcm16k[i] * pcm16k[i];
    const rms = Math.sqrt(sumSq / pcm16k.length);
    $("vad-meter").style.width = `${Math.min(100, Math.round(rms * 650))}%`;
    onChunk16k(pcm16k, rms);
  };
  source.connect(micProcessor);
  micProcessor.connect(micAudioCtx.destination);
}

function stopMicCapture() {
  if (micProcessor) { try { micProcessor.disconnect(); } catch (_) {} micProcessor = null; }
  if (micAudioCtx) { try { micAudioCtx.close(); } catch (_) {} micAudioCtx = null; }
  if (micStream) { micStream.getTracks().forEach(t => t.stop()); micStream = null; }
  $("vad-meter").style.width = "0%";
}

async function togglePushToTalk() {
  if (vadActive) await toggleHandsFreeVad();
  if (!pttRecording) {
    try {
      pttChunks = [];
      pauseTtsForBargeIn();
      await startMicCapture((pcm16k) => { pttChunks.push(pcm16k); });
      pttRecording = true;
      $("btn-ptt").classList.add("active-rec");
      $("btn-ptt").textContent = "⏹ Stop & Transcribe";
      $("stt-status").textContent = "Recording (PTT)...";
      $("stt-status").className = "pill live";
    } catch (e) {
      $("stt-status").textContent = `Mic error: ${e.message}`;
      $("stt-status").className = "pill warn";
    }
  } else {
    pttRecording = false;
    stopMicCapture();
    $("btn-ptt").classList.remove("active-rec");
    $("btn-ptt").textContent = "🎤 Push-to-Talk";
    const audio16k = concatFloat32(pttChunks);
    pttChunks = [];
    await transcribeUtterance(audio16k);
  }
}

async function toggleHandsFreeVad() {
  if (pttRecording) await togglePushToTalk();
  if (!vadActive) {
    $("stt-status").textContent = "Starting self-hosted Silero VAD...";
    $("stt-status").className = "pill warn";
    try {
      const vadMod = await import(INFERENCE_CONFIG.vadModuleUrl);
      if (vadMod && vadMod.MicVAD) {
        sileroVadInstance = await vadMod.MicVAD.new({
          onnxWASMBasePath: INFERENCE_CONFIG.ortWasmPaths,
          positiveSpeechThreshold: 0.5,
          negativeSpeechThreshold: 0.35,
          redemptionFrames: 22,
          onSpeechStart: () => {
            pauseTtsForBargeIn();
            $("vad-meter").style.width = "85%";
            $("stt-status").textContent = "Silero VAD: speech detected...";
            $("stt-status").className = "pill live";
          },
          onSpeechEnd: async (audio16k) => {
            $("vad-meter").style.width = "0%";
            await transcribeUtterance(audio16k);
            if (vadActive) {
              $("stt-status").textContent = "Silero VAD listening (700ms endpoint)";
              $("stt-status").className = "pill ok";
            }
          }
        });
        sileroVadInstance.start();
        vadActive = true;
        $("btn-vad").classList.add("active-rec");
        $("btn-vad").textContent = "⏹ Stop VAD";
        $("stt-status").textContent = "Silero VAD listening (700ms endpoint)";
        $("stt-status").className = "pill ok";
        return;
      }
    } catch (_) {
      // Fall through cleanly to deterministic RMS hysteresis VAD (0.5/0.35 normalized, 700ms silence)
    }
    try {
      vadSpeechChunks = [];
      vadInSpeech = false;
      vadSilenceMs = 0;
      await startMicCapture((pcm16k, rms) => {
        const frameMs = (pcm16k.length / 16000) * 1000;
        const score = Math.min(1.0, rms * 16.0);
        if (!vadInSpeech && score >= 0.5) {
          vadInSpeech = true;
          vadSilenceMs = 0;
          vadSpeechChunks = [pcm16k];
          pauseTtsForBargeIn();
          $("stt-status").textContent = "VAD: speech detected...";
          $("stt-status").className = "pill live";
        } else if (vadInSpeech) {
          vadSpeechChunks.push(pcm16k);
          if (score < 0.35) {
            vadSilenceMs += frameMs;
            if (vadSilenceMs >= 700) {
              const seg = concatFloat32(vadSpeechChunks);
              vadSpeechChunks = [];
              vadInSpeech = false;
              vadSilenceMs = 0;
              transcribeUtterance(seg).then(() => {
                if (vadActive) {
                  $("stt-status").textContent = "VAD listening (hysteresis 700ms)";
                  $("stt-status").className = "pill ok";
                }
              });
            }
          } else {
            vadSilenceMs = 0;
          }
        }
      });
      vadActive = true;
      $("btn-vad").classList.add("active-rec");
      $("btn-vad").textContent = "⏹ Stop VAD";
      $("stt-status").textContent = "VAD listening (hysteresis 700ms)";
      $("stt-status").className = "pill ok";
    } catch (e) {
      $("stt-status").textContent = `VAD mic error: ${e.message}`;
      $("stt-status").className = "pill warn";
    }
  } else {
    vadActive = false;
    if (sileroVadInstance) {
      try { sileroVadInstance.pause(); sileroVadInstance.destroy(); } catch (_) {}
      sileroVadInstance = null;
    }
    stopMicCapture();
    $("btn-vad").classList.remove("active-rec");
    $("btn-vad").textContent = "🎙 Hands-Free VAD";
    $("stt-status").textContent = "STT local ready (on-device)";
    $("stt-status").className = "pill";
  }
}

// ---------------------------------------------------------------------------
// BitechoLightNode v1.1.0 UI Wiring + BIP-016 Rolodex & Memento Sync
// ---------------------------------------------------------------------------

const DEFAULT_SUBNETS = [
  "bitecho.org/dev",
  "bitecho.org/team",
  "bitecho.org/infra",
  "chat/simon-nexus-axiom",
  "devnet.bitecho.org",
  "global"
];

function loadSavedSubnets() {
  try {
    const raw = localStorage.getItem("hive_subscribed_subnets");
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter(s => typeof s === "string" && s.trim()) : [];
  } catch (_) {
    return [];
  }
}

function saveCustomSubnet(sub) {
  const s = String(sub || "").trim();
  if (!s) return loadSavedSubnets();
  const cur = loadSavedSubnets();
  if (!cur.includes(s)) {
    cur.push(s);
    try { localStorage.setItem("hive_subscribed_subnets", JSON.stringify(cur)); } catch (_) {}
  }
  return cur;
}

function mergedSubnetsList(nodeSubnets) {
  const seen = new Set();
  const out = [];
  for (const s of [...DEFAULT_SUBNETS, ...loadSavedSubnets(), ...(nodeSubnets || [])]) {
    const clean = String(s || "").trim();
    if (clean && !seen.has(clean)) {
      seen.add(clean);
      out.push(clean);
    }
  }
  return out;
}

function renderLists(subnets, sessions, petnames) {
  const effectiveSubnets = mergedSubnetsList(subnets);
  $("subnet-list").innerHTML = effectiveSubnets
    .map(s => `<div class="list-item ${activeTarget.kind === "subnet" && activeTarget.id === s ? "active" : ""}" data-kind="subnet" data-id="${s}"><span># ${s}</span></div>`).join("");
  $("session-list").innerHTML = sessions.map(s => {
    const label = s.peerPetname || s.peer_petname || (s.sessionId || s.session_id || "").slice(0, 12);
    const sid = s.sessionId || s.session_id;
    const col = s.color || colorForKey(label);
    const stateStr = String(s.state || s.status || "open").toLowerCase();
    const pillCls = stateStr === "open" ? "ok" : (stateStr === "offered" ? "warn" : "");
    return `<div class="list-item ${activeTarget.kind === "dm" && activeTarget.id === sid ? "active" : ""}" data-kind="dm" data-id="${sid}"><span><span class="dot" style="background:${col}"></span>${label}</span><span class="pill ${pillCls}" style="font-size:0.65rem;padding:1px 6px;">${stateStr}</span></div>`;
  }).join("") || `<div style="font-size:0.78rem;color:var(--muted);">No active DM sessions yet</div>`;

  $("petname-list").innerHTML = petnames.map(p => {
    const col = p.color || colorForKey(p.petname || p.pubkey);
    const v = p.voiceId || DEFAULT_VOICES[(p.petname || "").toLowerCase()] || "af_heart";
    return `<div class="list-item" style="cursor:default;gap:4px;">
      <span><span class="dot" style="background:${col}"></span><strong>${p.petname}</strong></span>
      <span style="display:flex;align-items:center;gap:4px;">
        <span style="font-size:0.7rem;color:var(--muted);">${v}</span>
        <button class="btn-pn-dm" data-target="${p.petname || p.pubkey}" style="padding:2px 6px;font-size:0.7rem;">DM</button>
      </span>
    </div>`;
  }).join("") || `<div style="font-size:0.78rem;color:var(--muted);">No petnames saved yet</div>`;

  document.querySelectorAll(".btn-pn-dm").forEach(btn => {
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      openDmSession(btn.dataset.target);
    });
  });

  renderPendingOffers();

  document.querySelectorAll(".list-item[data-kind]").forEach(el => {
    el.addEventListener("click", () => {
      activeTarget = { kind: el.dataset.kind, id: el.dataset.id };
      $("channel-header").textContent = activeTarget.kind === "subnet"
        ? `Subnet Broadcast: #${activeTarget.id}`
        : `DM Session: ${activeTarget.id.slice(0, 16)}…`;
      renderMessages();
      refreshSidebar();
    });
  });
}

function renderMessages() {
  const box = $("messages");
  const filtered = messageLog.filter(m => {
    if (activeTarget.kind === "subnet") return m.subnetId === activeTarget.id;
    return m.sessionId === activeTarget.id;
  });
  box.innerHTML = "";
  for (const m of filtered) {
    const pn = petnamesByPk[m.from] || {};
    const name = m.fromPetname || pn.petname || (m.from || "unknown").slice(0, 12);
    const col = m.color || pn.color || colorForKey(name);
    const voice = m.voiceId || pn.voiceId || DEFAULT_VOICES[name.toLowerCase()] || "af_heart";
    const div = document.createElement("div");
    div.className = "msg";
    div.style.borderLeftColor = col;
    div.innerHTML = `
      <div class="msg-head">
        <span><strong style="color:${col}">${name}</strong> · <code>${voice}</code> · seq ${m.seq ?? "-"}</span>
        <button class="btn-speak-one" style="padding:2px 8px;font-size:0.74rem;">🔊 Speak</button>
      </div>
      <div class="msg-body"></div>
    `;
    div.querySelector(".msg-body").textContent = m.text || "";
    div.querySelector(".btn-speak-one").addEventListener("click", () => {
      unlockAudioSync();
      speakText(m.text, voice);
    });
    box.appendChild(div);
  }
  box.scrollTop = box.scrollHeight;
}

async function refreshSidebar() {
  if (!node) return;
  const [subRes, sesRes, pnRes] = await Promise.all([
    Promise.resolve(node.listSubnets ? node.listSubnets() : { subnets: [] }),
    Promise.resolve(node.listSessions ? node.listSessions() : { sessions: [] }),
    Promise.resolve(node.listPetnames ? node.listPetnames() : { petnames: [] })
  ]);
  const subnets = Array.isArray(subRes) ? subRes : (subRes && subRes.subnets) || [];
  const sessions = Array.isArray(sesRes) ? sesRes : (sesRes && sesRes.sessions) || [];
  const petnames = Array.isArray(pnRes) ? pnRes : (pnRes && pnRes.petnames) || [];
  petnamesByPk = Object.fromEntries(petnames.map(p => [p.pubkey, p]));
  renderLists(subnets, sessions, petnames);
}

async function refreshRolodexStatus() {
  if (!node) return;
  try {
    const [pnRes, subRes, sesRes] = await Promise.all([
      Promise.resolve(node.listPetnames ? node.listPetnames() : { petnames: [] }),
      Promise.resolve(node.listSubnets ? node.listSubnets() : { subnets: [] }),
      Promise.resolve(node.listSessions ? node.listSessions() : { sessions: [] })
    ]);
    const pCount = (pnRes && pnRes.petnames ? pnRes.petnames.length : 0);
    const sCount = (subRes && subRes.subnets ? subRes.subnets.length : 0);
    const dCount = (sesRes && sesRes.sessions ? sesRes.sessions.length : 0);
    $("rolodex-status-text").innerHTML = `Local Rolodex (IndexedDB): <strong>${pCount}</strong> petnames · <strong>${sCount}</strong> subnets · <strong>${dCount}</strong> sessions`;
  } catch (e) {
    $("rolodex-status-text").textContent = `Rolodex status: ${e.message}`;
  }
}

async function refreshNodeBadge(stArg) {
  const api = window.BitechoLightNode;
  const browser = globalThis.BitechoBrowser;
  let st = stArg;
  if (!st && api && api.getStatus) {
    try { st = await api.getStatus(); } catch (_) {}
  }
  if (!st && browser && browser.getStatus) {
    try { st = browser.getStatus(); } catch (_) {}
  }
  if (!st) return;
  const pk = st.pubkey || st.peerId || "";
  const bal = st.balance ?? 0;
  if (pk) {
    $("node-id-badge").style.display = "inline-block";
    $("node-id-badge").textContent = `pubkey: ${pk.slice(0, 12)}… · bal: ${Number(bal).toLocaleString()} ECH`;
  }
  if (st.meshConnected) {
    $("api-badge").textContent = `BitechoLightNode v${(api && api.apiVersion) || "1.1.0"} CONNECTED (${(api && api.version) || "0.23.19"})`;
    $("api-badge").className = "pill ok";
  }
}

async function connectNode() {
  unlockAudioSync();
  const api = window.BitechoLightNode;
  const browser = globalThis.BitechoBrowser;
  if (!api) {
    $("api-badge").textContent = "BitechoLightNode script not loaded";
    $("api-badge").className = "pill warn";
    return;
  }
  $("api-badge").textContent = `BitechoLightNode v${api.apiVersion || "1.1.0"} connecting...`;
  try {
    node = api;
    // IMPORTANT: Never call initNode() if already booted / restored from IndexedDB or YubiKey,
    // because initNode() generates a fresh ephemeral keypair and clobbers the Anchor Identity!
    const existing = api.getStatus ? await api.getStatus() : ((browser && browser.getStatus) ? browser.getStatus() : null);
    if (!existing || !existing.pubkey) {
      if (api.initNode) await api.initNode({ subnetId: "bitecho.org/dev" });
      else if (browser && browser.initNode) browser.initNode({ subnetId: "bitecho.org/dev" });
    }
    if (api.reconnect) {
      await api.reconnect({ subnetId: "bitecho.org/dev" });
    } else if (browser && browser.connectMesh) {
      browser.connectMesh();
    }
    if (api.exec) {
      try { await api.exec("sync-account", {}); } catch (_) {}
    }
    await refreshNodeBadge();
    $("api-badge").textContent = `BitechoLightNode v${api.apiVersion || "1.1.0"} LIVE (${api.version || "0.23.19"})`;
    $("api-badge").className = "pill ok";
    if (unsub) unsub();
    if (node.onEvent) {
      unsub = node.onEvent((ev) => {
        if (ev.type === "offer-received" || ev.type === "session-updated") {
          refreshSidebar();
          renderPendingOffers();
          return;
        }
        if (ev.type === "message-received" || ev.type === "broadcast-received") {
          const dk = messageDedupeKey(ev);
          if (seenMessageKeys.has(dk)) return;
          seenMessageKeys.add(dk);
          messageLog.push(ev);
          renderMessages();
          if ($("chk-auto-tts").checked && ev.text) {
            const pn = petnamesByPk[ev.from] || {};
            const name = ev.fromPetname || pn.petname || "";
            const voice = ev.voiceId || pn.voiceId || DEFAULT_VOICES[name.toLowerCase()] || "af_heart";
            speakText(ev.text, voice);
          }
        }
      });
    }
    await refreshSidebar();
    await refreshRolodexStatus();
    refreshAnchorUI();
    $("channel-header").textContent = `Subnet Broadcast: #${activeTarget.id}`;
  } catch (e) {
    $("api-badge").textContent = `Connect error: ${e.message}`;
    $("api-badge").className = "pill warn";
  }
}

async function sendCurrentMessage() {
  const txt = $("msg-input").value;
  if (!txt.trim()) return;
  if (!node) {
    $("tts-status").textContent = "Connect Node first before sending";
    return;
  }
  $("msg-input").value = "";
  const res = activeTarget.kind === "subnet"
    ? await node.sendBroadcast(activeTarget.id, txt)
    : await node.sendMessage(activeTarget.id, txt);
  if (res && res.ok) {
    const ownEv = {
      type: activeTarget.kind === "subnet" ? "broadcast-received" : "message-received",
      subnetId: activeTarget.kind === "subnet" ? activeTarget.id : null,
      sessionId: activeTarget.kind === "dm" ? activeTarget.id : null,
      from: "me",
      fromPetname: "You",
      color: "#34d399",
      seq: res.seq,
      msgId: res.msgId || res.msg_id || null,
      text: txt
    };
    const dk = messageDedupeKey(ownEv);
    if (!seenMessageKeys.has(dk)) {
      seenMessageKeys.add(dk);
      messageLog.push(ownEv);
      renderMessages();
    }
  } else {
    const err = (res && res.error) || "send-failed";
    $("tts-status").textContent = `Send rejected: ${err}`;
  }
}

// ---------------------------------------------------------------------------
// Hardware YubiKey Auth Flow (BitechoLightNode v1.1.0 facade + fallback)
// ---------------------------------------------------------------------------

function refreshAnchorUI(dashArg) {
  const browser = globalThis.BitechoBrowser;
  let dash = dashArg;
  if (!dash && browser && browser.getAnchorDashboard) {
    try { dash = browser.getAnchorDashboard(); } catch (_) {}
  }
  if (!dash) return;
  const id = dash.identity || {};
  const addr = id.address || "ech1...";
  const pk = id["anchor-pubkey"] || id.pubkey || "...";
  const locked = Boolean(dash["hardware-locked?"] ?? dash.hardwareLocked ?? (id["key-mode"] === "yubikey-discoverable" && !id["unlocked?"]));
  $("anchor-address").textContent = addr;
  $("anchor-pubkey").textContent = pk.length > 24 ? `${pk.slice(0, 16)}…${pk.slice(-8)}` : pk;
  $("hw-locked-badge").textContent = locked ? "LOCKED — Tap Recover" : "UNLOCKED — Ready to Sign";
  $("hw-locked-badge").className = locked ? "pill warn" : "pill ok";
  if (id["key-mode"] && $("key-mode-select")) {
    $("key-mode-select").value = id["key-mode"];
  }
  const fido = dash["fido-authenticator"] || {};
  if (fido["verification-status"]) {
    $("fido-badge").textContent = fido["verification-status"];
  }
}

async function handleRegisterYubiKey() {
  const api = window.BitechoLightNode;
  const browser = globalThis.BitechoBrowser;
  if ((!api || typeof api.registerYubiKey !== "function") && (!browser || !browser.createDiscoverableCredential)) {
    $("yubikey-status").textContent = "BitechoLightNode WebAuthn API not loaded";
    return;
  }
  $("yubikey-status").textContent = "Prompting hardware authenticator (WebAuthn create)...";
  try {
    let rec = null;
    if (browser && browser.createDiscoverableCredential && api && typeof api.recoverYubiKey === "function") {
      const cred = await browser.createDiscoverableCredential();
      rec = await api.recoverYubiKey(cred);
    } else if (api && typeof api.registerYubiKey === "function") {
      rec = await api.registerYubiKey();
    } else {
      const cred = await browser.createDiscoverableCredential();
      rec = browser.recoverFromYubiKey(cred);
    }
    if (rec && rec.ok === false) {
      $("yubikey-status").textContent = `Registration failed: ${(rec && (rec["failure-reason"] || rec.error)) || "recovery-failed"}`;
      return;
    }
    const dash = browser && browser.getAnchorDashboard ? browser.getAnchorDashboard() : null;
    if (api && typeof api.saveIdentity === "function") {
      try { await api.saveIdentity(); } catch (_) {}
    } else if (browser && browser.saveStateToIndexedDB) {
      try { await Promise.resolve(browser.saveStateToIndexedDB()); } catch (_) {}
    }
    if (api && api.exec) {
      try { await api.exec("sync-account", {}); } catch (_) {}
    }
    refreshAnchorUI(dash);
    await refreshNodeBadge();
    await refreshSidebar();
    await refreshRolodexStatus();
    $("yubikey-status").textContent = "Hardware YubiKey registered, saved to IndexedDB & synced!";
  } catch (err) {
    $("yubikey-status").textContent = `YubiKey register error: ${err.message || err}`;
  }
}

async function handleRecoverYubiKey() {
  const api = window.BitechoLightNode;
  const browser = globalThis.BitechoBrowser;
  if ((!api || typeof api.recoverYubiKey !== "function") && (!browser || !browser.getDiscoverableCredential)) {
    $("yubikey-status").textContent = "BitechoLightNode WebAuthn API not loaded";
    return;
  }
  $("yubikey-status").textContent = "Prompting hardware authenticator (WebAuthn get)...";
  try {
    const cred = (browser && browser.getDiscoverableCredential)
      ? await browser.getDiscoverableCredential()
      : null;
    const rec = (api && typeof api.recoverYubiKey === "function")
      ? await api.recoverYubiKey(cred)
      : browser.recoverFromYubiKey(cred);
    if (!rec || rec.ok === false) {
      const failReason = (rec && (rec["failure-reason"] || rec.error)) || "recovery-failed";
      const failStage = (rec && rec["failure-stage"]) || "unknown";
      $("yubikey-status").textContent = `Recover failed [${failStage}]: ${failReason}`;
      return;
    }
    const dash = browser && browser.getAnchorDashboard ? browser.getAnchorDashboard() : null;
    if (api && typeof api.saveIdentity === "function") {
      try { await api.saveIdentity(); } catch (_) {}
    } else if (browser && browser.saveStateToIndexedDB) {
      try { await Promise.resolve(browser.saveStateToIndexedDB()); } catch (_) {}
    }
    if (api && api.exec) {
      try { await api.exec("sync-account", {}); } catch (_) {}
    }
    refreshAnchorUI(dash);
    await refreshNodeBadge();
    await refreshSidebar();
    await refreshRolodexStatus();
    const stAfter = (api && api.getStatus) ? await api.getStatus() : {};
    const anchorPk = dash && dash.identity && dash.identity["anchor-pubkey"];
    const synced = anchorPk && stAfter && stAfter.pubkey === anchorPk;
    $("yubikey-status").textContent = synced
      ? `Anchor recovered & active on node (${anchorPk.slice(0, 12)}…)`
      : `Anchor recovered (card=${String(anchorPk || "").slice(0, 10)}… / node=${String(stAfter.pubkey || "").slice(0, 10)}…)`;
  } catch (err) {
    $("yubikey-status").textContent = `YubiKey recover error: ${err.message || err}`;
  }
}

async function handleSaveIdentity() {
  const api = window.BitechoLightNode;
  const browser = globalThis.BitechoBrowser;
  try {
    if (api && typeof api.saveIdentity === "function") {
      await api.saveIdentity();
    } else if (browser && browser.saveStateToIndexedDB) {
      await Promise.resolve(browser.saveStateToIndexedDB());
    } else {
      $("yubikey-status").textContent = "saveIdentity unavailable";
      return;
    }
    refreshAnchorUI();
    $("yubikey-status").textContent = "Saved public identity & address to IndexedDB";
  } catch (err) {
    $("yubikey-status").textContent = `Save identity error: ${err.message || err}`;
  }
}

$("btn-yk-register").addEventListener("click", handleRegisterYubiKey);
$("btn-yk-recover").addEventListener("click", handleRecoverYubiKey);
$("btn-yk-save").addEventListener("click", handleSaveIdentity);
$("key-mode-select").addEventListener("change", async (e) => {
  const api = window.BitechoLightNode;
  const browser = globalThis.BitechoBrowser;
  const mode = e.target.value;
  if (mode === "yubikey-discoverable") {
    if (api && typeof api.registerYubiKey === "function") {
      try { await api.registerYubiKey(); } catch (_) {}
    } else if (browser && browser.registerYubiKey) {
      browser.registerYubiKey();
    }
  }
  refreshAnchorUI();
  $("yubikey-status").textContent = `Key mode set: ${mode}`;
});

async function openDmSession(targetRaw) {
  const target = String(targetRaw || "").trim();
  if (!target) return;
  const api = window.BitechoLightNode;
  const browser = globalThis.BitechoBrowser;
  try {
    $("dm-action-status").textContent = `Sending signed offer to ${target}...`;
    let res;
    if (api && typeof api.openChannel === "function") {
      res = await api.openChannel(target);
    } else if (api && typeof api.openSession === "function") {
      res = await api.openSession(target);
    } else if (browser && typeof browser.openChannel === "function") {
      res = browser.openChannel(target);
    } else {
      $("dm-action-status").textContent = "openChannel / openSession API unavailable";
      return;
    }
    const sid = (res && (res.sessionId || res.session_id || res["session-id"])) || "";
    if (res && res.ok === false) {
      $("dm-action-status").textContent = `Offer failed: ${res.error || "unknown"}`;
    } else {
      $("dm-action-status").textContent = sid ? `Offer sent to ${target} (${sid.slice(0, 10)}…)` : `Offer sent to ${target}`;
      if (sid) {
        activeTarget = { kind: "dm", id: sid };
        $("channel-header").textContent = `DM Session: ${target} (${sid.slice(0, 12)}…)`;
      }
      await refreshSidebar();
      renderMessages();
    }
  } catch (e) {
    $("dm-action-status").textContent = `Offer error: ${e.message || e}`;
  }
}

async function renderPendingOffers() {
  const box = $("pending-offers-list");
  if (!box) return;
  const api = window.BitechoLightNode;
  const browser = globalThis.BitechoBrowser;
  let all = [];
  if (api && typeof api.listPendingOffers === "function") {
    try {
      const res = await api.listPendingOffers();
      if (res && Array.isArray(res.pendingOffers)) all = res.pendingOffers.filter(Boolean);
    } catch (_) {}
  }
  if (!all.length && browser) {
    const st = browser.getStatus ? browser.getStatus() : {};
    const dash = browser.getAnchorDashboard ? browser.getAnchorDashboard() : {};
    const rawChan = st.pendingOffers || st["pending-offers"] || {};
    const rawAnchor = dash.pendingOffers || dash["pending-offers"] || {};
    const chanList = Array.isArray(rawChan) ? rawChan : Object.values(rawChan);
    const anchorList = Array.isArray(rawAnchor) ? rawAnchor : Object.values(rawAnchor);
    all = [...chanList, ...anchorList].filter(Boolean);
  }
  if (!all.length) {
    box.innerHTML = "";
    return;
  }
  let finalizedEpoch = null;
  try {
    const st = (api && typeof api.getStatus === "function")
      ? await api.getStatus()
      : (browser && typeof browser.getStatus === "function" ? browser.getStatus() : null);
    if (st) {
      const fe = st.finalizedEpoch ?? st.finalized_epoch ?? st.epoch;
      if (typeof fe === "number" && Number.isFinite(fe)) finalizedEpoch = fe;
    }
  } catch (_) {}
  box.innerHTML = all.map((off, idx) => {
    const caller = off.callerPetname || off.caller || off["caller-pubkey"] || "peer";
    const label = String(caller).length > 16 ? `${String(caller).slice(0, 12)}…` : caller;
    const offerEpoch = Number(off.epoch ?? off.offerEpoch ?? off["offer-epoch"]);
    const isStale = finalizedEpoch !== null && Number.isFinite(offerEpoch) && (finalizedEpoch - offerEpoch > 15);
    const staleBadge = isStale
      ? ` <span class="pill warn" style="font-size:0.65rem;padding:1px 5px;">⚠ stale</span>`
      : "";
    return `<div class="list-item" style="background:rgba(245,158,11,0.12);border-color:rgba(245,158,11,0.4);cursor:default;">
      <span style="font-size:0.76rem;">Offer from <strong>${label}</strong>${staleBadge}</span>
      <button class="btn-accept-offer primary" data-idx="${idx}" style="padding:2px 7px;font-size:0.72rem;">✅ Accept</button>
    </div>`;
  }).join("");
  box.querySelectorAll(".btn-accept-offer").forEach(btn => {
    btn.addEventListener("click", async () => {
      const off = all[Number(btn.dataset.idx)];
      if (!off) return;
      try {
        let res;
        if (off.offerHash || off["offer-hash"] || off.signature) {
          if (api && typeof api.acceptOffer === "function") res = await api.acceptOffer(off);
          else if (browser && typeof browser.acceptOffer === "function") res = browser.acceptOffer(off);
        } else {
          const oid = off.offerId || off.id || off["offer-id"] || off.offerHash;
          if (api && typeof api.approveOffer === "function") res = await api.approveOffer(oid);
          else if (browser && typeof browser.approveOffer === "function") res = browser.approveOffer(oid);
        }
        $("dm-action-status").textContent = (res && res.ok === false)
          ? `Accept failed: ${res.error || "rejected"}`
          : "Offer accepted!";
        await refreshSidebar();
      } catch (e) {
        $("dm-action-status").textContent = `Accept error: ${e.message || e}`;
      }
    });
  });
}

$("btn-open-dm").addEventListener("click", () => {
  openDmSession($("dm-target-input").value);
});

$("btn-connect").addEventListener("click", connectNode);
$("btn-send").addEventListener("click", sendCurrentMessage);
$("btn-ptt").addEventListener("click", togglePushToTalk);
$("btn-vad").addEventListener("click", toggleHandsFreeVad);
$("msg-input").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    sendCurrentMessage();
  }
});
$("btn-save-pn").addEventListener("click", async () => {
  if (!node || !node.setPetname) return;
  const pk = $("pn-pubkey").value.trim();
  const petname = $("pn-name").value.trim();
  if (!pk || !petname) return;
  await node.setPetname(pk, { petname, voiceId: $("pn-voice").value, color: $("pn-color").value });
  await refreshSidebar();
  renderMessages();
});
$("btn-rolodex-sync").addEventListener("click", refreshRolodexStatus);
$("btn-rolodex-put").addEventListener("click", async () => {
  if (!node || !node.exec) return;
  const res = await node.exec("rolodex-put", { nodeRole: "light" });
  $("rolodex-verify-out").textContent = res && res.ok
    ? `Rolodex snapshot ok (seq=${res.seq ?? "?"})`
    : `Rolodex put: ${(res && res.error) || "unavailable"}`;
  await refreshRolodexStatus();
});
async function subscribeSubnet(subRaw) {
  const sub = String(subRaw || "").trim();
  if (!sub) return;
  saveCustomSubnet(sub);
  const allSubs = mergedSubnetsList([]);
  if (node && typeof node.exec === "function") {
    try {
      await node.exec("subscribe-subnets", { subnets: allSubs.join(",") });
    } catch (_) {}
  }
  activeTarget = { kind: "subnet", id: sub };
  $("channel-header").textContent = `Subnet Broadcast: #${sub}`;
  if ($("subnet-subscribe-input")) $("subnet-subscribe-input").value = "";
  if ($("subnet-action-status")) $("subnet-action-status").textContent = `Subscribed: #${sub}`;
  await refreshSidebar();
  renderMessages();
}

if ($("btn-subscribe-subnet")) {
  $("btn-subscribe-subnet").addEventListener("click", () => {
    subscribeSubnet($("subnet-subscribe-input") ? $("subnet-subscribe-input").value : "");
  });
}
if ($("subnet-subscribe-input")) {
  $("subnet-subscribe-input").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      subscribeSubnet($("subnet-subscribe-input").value);
    }
  });
}

$("btn-rolodex-verify").addEventListener("click", async () => {
  const cid = ($("rolodex-cid-input").value.trim() || "be7ec379170f0f849be325a29a3486b0ae6add6338db6a8c5f6743e5315d7bf4");
  if (!cid || !node || !node.getArtifact) return;
  $("rolodex-verify-out").textContent = "Verifying CID...";
  const res = await node.getArtifact({ cid });
  $("rolodex-verify-out").textContent = res && res.ok
    ? `SHA-256 Verified CID ${res.cid.slice(0, 12)}… (${res.bytes ? res.bytes.length : 0} B)`
    : `Verify failed: ${(res && res.error) || "not-found"}`;
});

if (window.BitechoLightNode) {
  node = window.BitechoLightNode;
  $("api-badge").textContent = `BitechoLightNode v${window.BitechoLightNode.apiVersion || "1.1.0"} ready (${window.BitechoLightNode.version || "0.23.19"}, SRI verified)`;
  $("api-badge").className = "pill ok";
}
if (globalThis.BitechoBrowser) {
  const b = globalThis.BitechoBrowser;
  if (b.initNode) b.initNode({ subnetId: "bitecho.org/dev" });
  const initialDash = b.initAnchor ? b.initAnchor() : null;
  refreshAnchorUI(initialDash);
  refreshNodeBadge();
  if (typeof b.loadStateFromIndexedDB === "function") {
    Promise.resolve(b.loadStateFromIndexedDB()).then(async (restored) => {
      const restoredPub = restored && (restored.pubkey || (restored["light-state"] && restored["light-state"].pubkey));
      if (restoredPub) {
        $("yubikey-status").textContent = "Restored saved identity from IndexedDB (tap Recover YubiKey to unlock signing)";
      }
      refreshAnchorUI(b.getAnchorDashboard ? b.getAnchorDashboard() : null);
      await refreshNodeBadge();
      await refreshSidebar();
      await refreshRolodexStatus();
    }).catch(() => {});
  }
  if (typeof b.onStateChange === "function") {
    b.onStateChange((st) => {
      refreshNodeBadge(st);
      refreshAnchorUI(b.getAnchorDashboard ? b.getAnchorDashboard() : null);
      renderPendingOffers();
    });
  }
}
renderLists(["bitecho.org/dev", "bitecho.org/team", "bitecho.org/infra"], [], []);
setInterval(() => {
  if (node) {
    refreshSidebar();
    renderPendingOffers();
  }
}, 5000);
