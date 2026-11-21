const STANDALONE = window.__HIVE_STANDALONE__ || null;
const STATIC_APP_MODE = !STANDALONE && window.location.pathname.startsWith("/app/");
const DEFAULT_PETNAMES = [
  { id: "simon", petname: "simon", label: "Simon", role: "Human Anchor Twin", model: "claude-opus-5-5-high", voice_id: "am_michael", color: "#f59e0b", angle_deg: -90 },
  { id: "axiom", petname: "axiom", label: "Axiom", role: "Governance & Strategy", model: "gpt-oss-120b-medium", voice_id: "bm_george", color: "#38bdf8", angle_deg: -18 },
  { id: "nexus", petname: "nexus", label: "Nexus", role: "Infra, DevOps & Impl", model: "claude-sonnet-5-5-high", voice_id: "am_adam", color: "#a78bfa", angle_deg: 54 },
  { id: "vector", petname: "vector", label: "Vector", role: "Einsum, Hive & RL", model: "gemini-4-carbon-medium", voice_id: "bm_lewis", color: "#34d399", angle_deg: 126 },
  { id: "lumen", petname: "lumen", label: "Lumen", role: "Protocol & BIP Research", model: "gemini-4-hydrogen-high", voice_id: "af_heart", color: "#fb7185", angle_deg: 198 }
];
const INFERENCE_CONFIG = Object.assign({
  kokoroModuleUrl: "/app/vendor/kokoro-js.mjs"
}, window.__HIVE_INFERENCE_CONFIG__ || {});

const KOKORO_VOICES = [
  "am_michael", "bm_george", "am_adam", "bm_lewis", "af_heart",
  "af_bella", "af_sarah", "af_nova", "am_fenrir", "am_echo", "bf_emma", "bf_isabella"
];

let petnames = [];
let petnameMap = {};
let tracesCatalog = [];
let currentTrace = null;
let filteredTurns = [];
let activeIdx = 0;
let playing = false;
let kokoroModel = null;
let kokoroLoading = false;
let audioCtx = null;
let currentSource = null;
let analyser = null;
let animFrame = null;
const audioCache = new Map(); // turn_seq + voice + engine -> AudioBuffer
let einsumBuckets = null;
let gpuDevice = null;
const backendBench = {}; // engine -> {turn, ms, detail}

function recordBench(engine, turnSeq, ms, detail) {
  backendBench[engine] = { turn: turnSeq, ms: Math.round(ms), detail };
  const parts = Object.entries(backendBench).map(([k, v]) =>
    `<span><code>${k}</code> #${v.turn}: <strong>${v.ms} ms</strong> (${v.detail})</span>`
  );
  $("bench-strip").innerHTML = `<span><strong>Backend Compare:</strong></span> ` + parts.join(" · ");
}

function hannWindow(n) {
  const w = new Float32Array(n);
  for (let i = 0; i < n; i++) w[i] = 0.5 * (1.0 - Math.cos((2.0 * Math.PI * i) / n));
  return w;
}

function buildCustomIstftBasis(nFft = 20) {
  const nBins = (nFft >> 1) + 1;
  const win = hannWindow(nFft);
  const basis = new Float32Array(2 * nBins * nFft);
  const invN = 1.0 / nFft;
  for (let k = 0; k < nBins; k++) {
    const scale = (k === 0 || k === (nFft >> 1)) ? invN : 2.0 * invN;
    for (let n = 0; n < nFft; n++) {
      const ang = (2.0 * Math.PI * k * n) / nFft;
      basis[k * nFft + n] = scale * win[n] * Math.cos(ang);
      basis[(nBins + k) * nFft + n] = -scale * win[n] * Math.sin(ang);
    }
  }
  return basis;
}

function unlockAudioCtxSync() {
  if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  if (audioCtx.state === "suspended") audioCtx.resume().catch(() => {});
}

async function synthesizeTurnEinsumWebGPU(turn) {
  const t0 = performance.now();
  if (!navigator.gpu) throw new Error("WebGPU not available in this browser");
  if (!gpuDevice) {
    const adapter = await navigator.gpu.requestAdapter();
    if (!adapter) throw new Error("navigator.gpu.requestAdapter() returned null");
    gpuDevice = await adapter.requestDevice();
  }
  if (!einsumBuckets) {
    const res = await fetch("/api/kokoro-webgpu-buckets");
    einsumBuckets = await res.json();
  }
  const clean = turn.speech_text || turn.raw_output || "Hello";
  const bucket = 1024;
  const nBlocks = 5; // 5 x 1024 STFT frames (~1.06s @ 24kHz)
  const bInfo = einsumBuckets.buckets[String(bucket)];
  const nFft = 20, hop = 5, pad = 10, nBins = 11;
  const blockLen = bInfo["out-samples"];
  const basis = buildCustomIstftBasis(nFft);
  const win = hannWindow(nFft);
  const winSum = new Float32Array(blockLen);
  for (let f = 0; f < bucket; f++) {
    for (let k = 0; k < nFft; k++) {
      const t = f * hop + k - pad;
      if (t >= 0 && t < blockLen) winSum[t] += win[k] * win[k];
    }
  }
  const module = gpuDevice.createShaderModule({ code: bInfo.source });
  const pipeline = gpuDevice.createComputePipeline({ layout: "auto", compute: { module, entryPoint: "main" } });
  const wg = bInfo["workgroup-size"] || [64, 1, 1];
  const gx = Math.ceil(blockLen / wg[0]);
  const basisBuf = gpuDevice.createBuffer({ size: basis.byteLength, usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST });
  gpuDevice.queue.writeBuffer(basisBuf, 0, basis);

  const rawFull = new Float32Array(nBlocks * blockLen);
  for (let blk = 0; blk < nBlocks; blk++) {
    const spec = new Float32Array(2 * nBins * bucket);
    for (let k = 0; k < nBins; k++) {
      const formant = Math.exp(-0.22 * k);
      for (let f = 0; f < bucket; f++) {
        const gf = blk * bucket + f;
        const ch = clean.charCodeAt(Math.floor(gf / 16) % clean.length);
        const amp = ch === 32 ? 0.05 : 0.7 * formant;
        const ph = 2.0 * Math.PI * (((k + 1) * gf) % 37) / 37.0;
        spec[k * bucket + f] = amp * Math.cos(ph);
        spec[(nBins + k) * bucket + f] = amp * Math.sin(ph);
      }
    }
    const outBuf = gpuDevice.createBuffer({ size: blockLen * 4, usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_SRC });
    const specBuf = gpuDevice.createBuffer({ size: spec.byteLength, usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST });
    const readBuf = gpuDevice.createBuffer({ size: blockLen * 4, usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ });
    gpuDevice.queue.writeBuffer(specBuf, 0, spec);
    const bindGroup = gpuDevice.createBindGroup({
      layout: pipeline.getBindGroupLayout(0),
      entries: [
        { binding: 0, resource: { buffer: outBuf } },
        { binding: 1, resource: { buffer: specBuf } },
        { binding: 2, resource: { buffer: basisBuf } }
      ]
    });
    const enc = gpuDevice.createCommandEncoder();
    const pass = enc.beginComputePass();
    pass.setPipeline(pipeline);
    pass.setBindGroup(0, bindGroup);
    pass.dispatchWorkgroups(gx, 1, 1);
    pass.end();
    enc.copyBufferToBuffer(outBuf, 0, readBuf, 0, blockLen * 4);
    gpuDevice.queue.submit([enc.finish()]);
    await readBuf.mapAsync(GPUMapMode.READ);
    const rawBlock = new Float32Array(readBuf.getMappedRange().slice(0));
    readBuf.unmap();
    outBuf.destroy(); specBuf.destroy(); readBuf.destroy();
    for (let t = 0; t < blockLen; t++) rawFull[blk * blockLen + t] = rawBlock[t] / Math.max(1e-8, winSum[t]);
  }
  basisBuf.destroy();

  // Peak-normalize to 0.75 (-2.5 dBFS)
  let peak = 0;
  for (let i = 0; i < rawFull.length; i++) peak = Math.max(peak, Math.abs(rawFull[i]));
  const gain = peak > 1e-6 ? 0.75 / peak : 1.0;
  const pcm = new Float32Array(rawFull.length);
  for (let i = 0; i < rawFull.length; i++) pcm[i] = Math.max(-1, Math.min(1, rawFull[i] * gain));

  unlockAudioCtxSync();
  const buf = audioCtx.createBuffer(1, pcm.length, 24000);
  buf.getChannelData(0).set(pcm);
  recordBench("clj-einsum:uop-webgpu", turn.turn_seq, performance.now() - t0, `${nBlocks}x${bucket} WGSL ${(pcm.length/24000).toFixed(2)}s`);
  return buf;
}

async function synthesizeTurnEinsumCPU(turn) {
  const t0 = performance.now();
  const voice = getVoiceForAgent(turn.agent_id);
  const res = await fetch("/api/tts/uop-cpu", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text: turn.speech_text || turn.raw_output, voice })
  });
  if (!res.ok) throw new Error(`uop-cpu HTTP ${res.status}`);
  const bucket = res.headers.get("X-Einsum-Bucket") || "?";
  const srvMs = res.headers.get("X-Einsum-Latency-Ms") || "?";
  const arrBuf = await res.arrayBuffer();
  if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  const buf = await audioCtx.decodeAudioData(arrBuf);
  recordBench("clj-einsum:uop-cpu", turn.turn_seq, performance.now() - t0, `srv=${srvMs}ms bucket=${bucket}`);
  return buf;
}

const $ = (id) => document.getElementById(id);

function setStatus(msg, badge = "") {
  $("tts-status-text").textContent = msg;
  $("prefetch-badge").textContent = badge;
}

function verdictClass(v) {
  if (!v) return "";
  if (v.includes("objection")) return "v-objection";
  if (v.includes("addendum") || v.includes("flags")) return "v-addendum";
  return "v-consent";
}

function renderCircle(activeAgentId = null, latestVerdicts = {}) {
  const svg = $("circle-svg");
  const cx = 200, cy = 175, r = 115;
  let html = `<circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="#233252" stroke-width="2" stroke-dasharray="6 5" />`;
  html += `<text x="${cx}" y="${cy - 8}" text-anchor="middle" fill="#93a4c3" font-size="11" font-weight="600">BITECHO HIVE</text>`;
  html += `<text x="${cx}" y="${cy + 10}" text-anchor="middle" fill="#64748b" font-size="10">${currentTrace ? currentTrace.run_id.toUpperCase() : "5-SEAT CIRCLE"}</text>`;

  for (const p of petnames) {
    const rad = (p.angle_deg * Math.PI) / 180;
    const x = cx + r * Math.cos(rad);
    const y = cy + r * Math.sin(rad);
    const isSpeaking = activeAgentId === p.id;
    const v = latestVerdicts[p.id];
    const strokeCol = isSpeaking ? p.color : "#334155";
    if (isSpeaking) {
      html += `<line x1="${cx}" y1="${cy}" x2="${x}" y2="${y}" stroke="${p.color}" stroke-width="2.5" stroke-opacity="0.65" />`;
      html += `<circle class="seat-pulse" cx="${x}" cy="${y}" r="36" fill="none" stroke="${p.color}" />`;
    }
    html += `<circle cx="${x}" cy="${y}" r="30" fill="#131b2e" stroke="${strokeCol}" stroke-width="${isSpeaking ? 3.5 : 2}" />`;
    html += `<text x="${x}" y="${y - 2}" text-anchor="middle" fill="${p.color}" font-size="12" font-weight="700">${p.label}</text>`;
    html += `<text x="${x}" y="${y + 12}" text-anchor="middle" fill="#94a3b8" font-size="9">${p.voice_id}</text>`;
    if (v) {
      const vColor = v.includes("objection") ? "#f43f5e" : (v.includes("addendum") ? "#f59e0b" : "#34d399");
      const vShort = v.includes("objection") ? "OBJ" : (v.includes("addendum") ? "ADD" : "OK");
      html += `<rect x="${x + 14}" y="${y - 34}" width="32" height="16" rx="8" fill="${vColor}" />`;
      html += `<text x="${x + 30}" y="${y - 23}" text-anchor="middle" fill="#0b1020" font-size="9" font-weight="800">${vShort}</text>`;
    }
  }
  svg.innerHTML = html;
}

function renderVoiceGrid() {
  const grid = $("voice-grid");
  grid.innerHTML = "";
  for (const p of petnames) {
    const row = document.createElement("div");
    row.className = "voice-row";
    const opts = KOKORO_VOICES.map(v => `<option value="${v}" ${v === p.voice_id ? "selected" : ""}>${v}</option>`).join("");
    row.innerHTML = `
      <div><span class="dot" style="background:${p.color}"></span><strong>${p.label}</strong> <span style="color:var(--muted);font-size:0.74rem;">(${p.model})</span></div>
      <select data-agent="${p.id}">${opts}</select>
    `;
    row.querySelector("select").addEventListener("change", (e) => {
      p.voice_id = e.target.value;
      audioCache.clear();
      renderCircle(filteredTurns[activeIdx]?.agent_id);
    });
    grid.appendChild(row);
  }
}

async function ensureKokoroLoaded() {
  const engine = $("engine-select").value;
  if (engine === "webspeech") return null;
  if (kokoroModel) return kokoroModel;
  if (kokoroLoading) return null;
  kokoroLoading = true;
  $("btn-init-kokoro").disabled = true;
  try {
    const wantWebGPU = engine === "kokoro-webgpu" && !!navigator.gpu;
    const device = wantWebGPU ? "webgpu" : "wasm";
    const dtype = wantWebGPU ? "fp32" : "q8";
    setStatus(`Loading self-hosted Kokoro-82M (${device}/${dtype})...`);
    const { KokoroTTS } = await import(INFERENCE_CONFIG.kokoroModuleUrl);
    kokoroModel = await KokoroTTS.from_pretrained("onnx-community/Kokoro-82M-v1.0-ONNX", {
      dtype,
      device,
      progress_callback: (p) => {
        if (p && p.status === "progress" && p.progress != null) {
          setStatus(`Downloading ${p.file || "Kokoro-82M"}: ${Math.round(p.progress)}%`);
        }
      }
    });
    setStatus(`Kokoro-82M ready (${device}/${dtype})`);
    $("btn-init-kokoro").textContent = `Kokoro Ready (${device})`;
    return kokoroModel;
  } catch (err) {
    console.error("Kokoro load failed, falling back to Web Speech:", err);
    setStatus(`Kokoro load failed (${err.message}); using Web Speech fallback`);
    $("engine-select").value = "webspeech";
    $("btn-init-kokoro").disabled = false;
    return null;
  } finally {
    kokoroLoading = false;
  }
}

function getVoiceForAgent(agentId) {
  return (petnameMap[agentId] && petnameMap[agentId].voice_id) || "af_heart";
}

async function synthesizeTurnKokoro(turn) {
  const t0 = performance.now();
  const voice = getVoiceForAgent(turn.agent_id);
  const key = `kokoro:${turn.turn_seq}:${voice}`;
  if (audioCache.has(key)) return audioCache.get(key);
  const tts = await ensureKokoroLoaded();
  if (!tts) return null;
  const text = turn.speech_text || turn.raw_output;
  const rawAudio = await tts.generate(text, { voice });
  if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  const sr = rawAudio.sampling_rate || 24000;
  const samples = rawAudio.audio;
  const buf = audioCtx.createBuffer(1, samples.length, sr);
  buf.getChannelData(0).set(samples);
  audioCache.set(key, buf);
  recordBench("kokoro-js", turn.turn_seq, performance.now() - t0, `${voice} ${sr}Hz`);
  return buf;
}

function drawWaveform() {
  const canvas = $("wave-canvas");
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#0d1426";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  if (!analyser || !playing) return;
  const data = new Uint8Array(analyser.fftSize);
  analyser.getByteTimeDomainData(data);
  ctx.lineWidth = 2;
  ctx.strokeStyle = "#38bdf8";
  ctx.beginPath();
  const slice = canvas.width / data.length;
  for (let i = 0; i < data.length; i++) {
    const v = data[i] / 128.0;
    const y = (v * canvas.height) / 2;
    if (i === 0) ctx.moveTo(0, y);
    else ctx.lineTo(i * slice, y);
  }
  ctx.stroke();
  animFrame = requestAnimationFrame(drawWaveform);
}

function stopPlayback() {
  playing = false;
  if (currentSource) {
    try { currentSource.stop(); } catch (_) {}
    currentSource = null;
  }
  if (window.speechSynthesis) window.speechSynthesis.cancel();
  if (animFrame) cancelAnimationFrame(animFrame);
  $("btn-play").textContent = "▶ Play Circle Debate";
}

function computeVerdictsUpTo(idx) {
  const verdicts = {};
  for (let i = 0; i <= idx && i < filteredTurns.length; i++) {
    const t = filteredTurns[i];
    if (t.verdict) verdicts[t.agent_id] = t.verdict;
  }
  return verdicts;
}

function renderActiveTurn(idx) {
  const turn = filteredTurns[idx];
  if (!turn) return;
  const p = petnameMap[turn.agent_id] || { label: turn.agent_id, color: "#38bdf8", voice_id: turn.voice_id };
  const vBadge = turn.verdict ? `<span class="verdict-pill ${verdictClass(turn.verdict)}">${turn.verdict}</span>` : "";
  $("active-speaker").innerHTML = `<span style="color:${p.color}">${p.label}</span> <span style="font-size:0.78rem;color:var(--muted);">(${p.voice_id} · ${turn.model_selector || ""})</span> ${vBadge}`;
  $("active-stage-pill").textContent = `Turn #${turn.turn_seq} · Round ${turn.round} · ${turn.stage.toUpperCase()} (${idx + 1}/${filteredTurns.length})`;
  $("active-text").textContent = turn.raw_output;
  renderCircle(turn.agent_id, computeVerdictsUpTo(idx));
  document.querySelectorAll(".turn-item").forEach((el, i) => {
    el.classList.toggle("active", i === idx);
    if (i === idx) el.scrollIntoView({ block: "nearest", behavior: "smooth" });
  });
}

async function speakTurn(idx) {
  if (idx < 0 || idx >= filteredTurns.length) {
    stopPlayback();
    setStatus("Trace playback complete.");
    return;
  }
  activeIdx = idx;
  renderActiveTurn(idx);
  const turn = filteredTurns[idx];
  const engine = $("engine-select").value;
  const speed = parseFloat($("speed-slider").value);

  if (engine === "webspeech") {
    setStatus(`Speaking Turn #${turn.turn_seq} via Web Speech (${turn.agent_id})...`);
    const utter = new SpeechSynthesisUtterance(turn.speech_text || turn.raw_output);
    utter.rate = speed;
    utter.onend = () => {
      if (playing && $("chk-autoadvance").checked) speakTurn(idx + 1);
      else stopPlayback();
    };
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utter);
    return;
  }

  try {
    let buf = null;
    if (engine === "einsum-webgpu") {
      setStatus(`Dispatching clj-einsum :uop-webgpu WGSL for Turn #${turn.turn_seq}...`);
      buf = await synthesizeTurnEinsumWebGPU(turn);
    } else if (engine === "einsum-cpu") {
      setStatus(`Synthesizing Turn #${turn.turn_seq} via clj-einsum :uop-cpu server...`);
      buf = await synthesizeTurnEinsumCPU(turn);
    } else {
      setStatus(`Synthesizing Turn #${turn.turn_seq} (${turn.agent_id} · ${getVoiceForAgent(turn.agent_id)})...`);
      buf = await synthesizeTurnKokoro(turn);
    }
    if (!playing) return;
    if (!buf) {
      speakTurn(idx); // fell back to webspeech
      return;
    }
    // Lookahead pre-synthesis for Turn idx + 1 while current turn plays (kokoro-js)
    if ((engine === "kokoro-webgpu" || engine === "kokoro-wasm") && idx + 1 < filteredTurns.length) {
      setStatus(`Speaking Turn #${turn.turn_seq} (${turn.agent_id})`, `Pre-fetching #${filteredTurns[idx + 1].turn_seq}...`);
      synthesizeTurnKokoro(filteredTurns[idx + 1]).then(() => {
        if (playing) $("prefetch-badge").textContent = `Next #${filteredTurns[idx + 1].turn_seq} ready`;
      }).catch(() => {});
    } else {
      setStatus(`Playing Turn #${turn.turn_seq} (${turn.agent_id} · ${engine})`);
    }
    if (audioCtx.state === "suspended") await audioCtx.resume();
    analyser = audioCtx.createAnalyser();
    analyser.fftSize = 256;
    const src = audioCtx.createBufferSource();
    src.buffer = buf;
    src.playbackRate.value = speed;
    src.connect(analyser);
    analyser.connect(audioCtx.destination);
    currentSource = src;
    drawWaveform();
    src.onended = () => {
      if (playing && $("chk-autoadvance").checked) speakTurn(idx + 1);
      else stopPlayback();
    };
    src.start(0);
  } catch (err) {
    console.error(err);
    setStatus(`Error on Turn #${turn.turn_seq}: ${err.message}`);
    stopPlayback();
  }
}

function renderTranscript() {
  const container = $("transcript");
  container.innerHTML = "";
  filteredTurns.forEach((t, idx) => {
    const p = petnameMap[t.agent_id] || { label: t.agent_id, color: "#38bdf8" };
    const div = document.createElement("div");
    div.className = `turn-item ${idx === activeIdx ? "active" : ""}`;
    const vBadge = t.verdict ? `<span class="verdict-pill ${verdictClass(t.verdict)}">${t.verdict}</span>` : "";
    div.innerHTML = `
      <div class="turn-item-top">
        <span><strong style="color:${p.color}">${p.label}</strong> · R${t.round}/${t.stage} · #${t.turn_seq}</span>
        <span>${vBadge}</span>
      </div>
      <div class="turn-item-body">${t.raw_output.replace(/</g, "&lt;")}</div>
    `;
    div.addEventListener("click", () => {
      unlockAudioCtxSync();
      stopPlayback();
      playing = true;
      $("btn-play").textContent = "⏸ Pause";
      speakTurn(idx);
    });
    container.appendChild(div);
  });
}

function applyRoundFilter() {
  if (!currentTrace) return;
  const rf = $("round-filter").value;
  filteredTurns = currentTrace.turns.filter(t => !t.is_pass && (rf === "all" || String(t.round) === rf));
  activeIdx = 0;
  renderTranscript();
  if (filteredTurns.length) renderActiveTurn(0);
}

async function loadTrace(runId) {
  stopPlayback();
  audioCache.clear();
  setStatus(`Loading verified trace ${runId}...`);
  if (STANDALONE && STANDALONE.traceData && STANDALONE.traceData[runId]) {
    currentTrace = STANDALONE.traceData[runId];
  } else {
    const res = await fetch(`/api/trace/${runId}`);
    currentTrace = await res.json();
  }
  $("meta-strip").innerHTML = `
    <span><strong>${currentTrace.title}</strong></span>
    <span>Merkle Root: <code>${(currentTrace.merkle_root || "").slice(0, 16)}…</code></span>
    <span>JSONL CID: <code>${(currentTrace.jsonl_cid || "").slice(0, 16)}…</code></span>
  `;
  applyRoundFilter();
  setStatus(`Loaded ${filteredTurns.length} spoken turns — click Play!`);
}

async function boot() {
  if (STANDALONE) {
    petnames = STANDALONE.petnames;
    tracesCatalog = STANDALONE.traces;
  } else if (STATIC_APP_MODE) {
    petnames = DEFAULT_PETNAMES.map((p) => Object.assign({}, p));
    tracesCatalog = [];
  } else {
    try {
      const [pRes, tRes] = await Promise.all([fetch("/api/petnames"), fetch("/api/traces")]);
      if (pRes.ok && tRes.ok) {
        petnames = (await pRes.json()).petnames;
        tracesCatalog = (await tRes.json()).traces;
      }
    } catch (_) {
      petnames = DEFAULT_PETNAMES.map((p) => Object.assign({}, p));
      tracesCatalog = [];
    }
  }
  petnameMap = Object.fromEntries(petnames.map(p => [p.id, p]));

  renderVoiceGrid();
  renderCircle();

  const sel = $("trace-select");
  sel.innerHTML = tracesCatalog.map(t => `<option value="${t.run_id}">${t.title}</option>`).join("");
  sel.addEventListener("change", () => loadTrace(sel.value));
  $("round-filter").addEventListener("change", applyRoundFilter);
  $("speed-slider").addEventListener("input", (e) => { $("speed-val").textContent = `${e.target.value}x`; });
  $("btn-init-kokoro").addEventListener("click", () => ensureKokoroLoaded());
  $("btn-play").addEventListener("click", () => {
    unlockAudioCtxSync();
    if (playing) {
      stopPlayback();
    } else {
      playing = true;
      $("btn-play").textContent = "⏸ Pause";
      speakTurn(activeIdx);
    }
  });
  $("btn-stop").addEventListener("click", stopPlayback);
  $("btn-prev").addEventListener("click", () => {
    const prev = Math.max(0, activeIdx - 1);
    if (playing) { stopPlayback(); playing = true; $("btn-play").textContent = "⏸ Pause"; speakTurn(prev); }
    else { activeIdx = prev; renderActiveTurn(prev); }
  });
  $("btn-next").addEventListener("click", () => {
    const next = Math.min(filteredTurns.length - 1, activeIdx + 1);
    if (playing) { stopPlayback(); playing = true; $("btn-play").textContent = "⏸ Pause"; speakTurn(next); }
    else { activeIdx = next; renderActiveTurn(next); }
  });

  if (tracesCatalog.length) await loadTrace(tracesCatalog[0].run_id);
}

boot();
