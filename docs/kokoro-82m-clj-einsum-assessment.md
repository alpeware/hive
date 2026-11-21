# Technical Assessment: Porting Kokoro-82M TTS Inference to `clj-einsum`

**Author:** Vector (`bitecho.org/vector`)  
**Date:** 2026-11-21  
**Target Stack:** `alpeware/clj-einsum` (`:uop-c` JVM + `:uop-webgpu` Browser) & `alpeware/mealy`  
**Verdict:** **Feasible & High-Leverage (~1.5–2 weeks end-to-end; 4–5 days for JVM `:uop-c` MVP).** No fundamental blockers in UOp IR or WebGPU. Strongly agree with shipping the Hive Trace Audio Visualizer on `kokoro-js` **now**, while building the `clj-einsum` `Conv1d`/`ConvTranspose1d`/`BiLSTM` primitives as the strategic reach multiplier.

---

## 1. Kokoro-82M Architecture Breakdown (82M Parameters, Non-Autoregressive, 24 kHz)

Kokoro-82M (`hexgrad/Kokoro-82M`, StyleTTS2 + iSTFTNet) runs in a single feed-forward pass from IPA phoneme token IDs (`T <= 510`) + a precomputed `256`-dim voicepack embedding (`[510, 1, 256]` float32, ~510 KB/voice) to 24 kHz PCM audio:

1. **PL-BERT / CustomALBERT Phoneme Encoder** (~12M params): Token embedding + shared-weight transformer layers (MHA + LayerNorm + GELU FFN).
2. **TextEncoder & ProsodyPredictor**: `Conv1d` stacks + `BiLSTM` layers + `AdaIN1d` style injection → predicts per-phoneme durations, $F_0$ pitch contour, and energy ($N$).
3. **Duration Alignment**: Expands phoneme encodings `[T, D] → [L_frames, D]` via integer duration matrix (`pred_aln_trg`).
4. **iSTFTNet Generator / Vocoder**: `SineGen` harmonic excitation (from $F_0$) + `ConvTranspose1d` upsampling (`upsample_rates = [10, 6]`) + dilated `Conv1d` ResBlocks with `AdaIN1d` & `LeakyReLU` → predicts magnitude & phase spectrograms (`n_fft = 20`, `hop_length = 5`, only 11 frequency bins!) → `CustomSTFT` inverse transform to 24 kHz waveform.

---

## 2. Op-by-Op Coverage Audit Against `clj-einsum/UOp` (`src/einsum/uop/{core,lower,render}.clj`)

| Kokoro-82M Component | Required Ops | Current `clj-einsum` Status | Gap & Exact Implementation Path |
|---|---|---|---|
| **ALBERT Encoder** | Embedding (`:gather`), GEMM (`:einsum`), MHA (`:softmax`), `LayerNorm`, `GELU` | **100% Covered** (`:uop-c` & `:uop-webgpu`) | None. Already proven on GPT-2 & Gemma 4 E2B. |
| **Elementwise / Trig / Style** | `:uop/sin`, `:uop/cos`, `:uop/exp2`, `:uop/sqrt`, `:uop/where`, `sigmoid`, `tanh`, `LeakyReLU`, `InstanceNorm1d`/`AdaIN1d` | **100% Covered** (`sinf`/`cosf` in C, `sin`/`cos`/`select` in WGSL) | None. `LeakyReLU` & `AdaIN1d` compose directly from `:where` + `:reduce-axis`. |
| **iSTFT Vocoder (`n_fft=20, hop=5`)** | `CustomSTFT` inverse transform | **No complex FFT needed!** | `hexgrad/Kokoro-82M` already exports `CustomSTFT` as a **single `ConvTranspose1d`** with precomputed Hann-windowed `[22, 1, 20]` DFT basis kernels + window overlap-add! Comes **for free** once `ConvTranspose1d` lands. |
| **1D Convolutions (`Conv1d` + `ConvTranspose1d`)** | `Conv1d` (stride, padding, dilation, groups) + `ConvTranspose1d` (stride 10/6/5) | **Missing in `lower.clj`** (low-level `:uop/pad`, `:uop/flip`, `:uop/shrink`, `:uop/einsum` exist) | **Gap 1 (~2–3 days):** Lower `:conv1d` via `im2col` windowing → `:einsum` GEMM; lower `:conv-transpose1d` via zero-insertion upsampling + flipped-kernel `:conv1d` (or gather-based transpose). |
| **Bidirectional LSTM (`BiLSTM`)** | Sequential recurrence over $t \in [0, T)$ and reversed | **Missing as fused op** (cell ops 100% covered) | **Gap 2 (~1–2 days):** Pre-compute `X @ W_ih^T` in one batched GEMM, then run ONE compiled single-step LSTM cell graph in a host/JS loop (exact same pattern as KV-cache decode in `mealy/dev/webgpu_client.cljs`). |
| **Duration Alignment + `SineGen`** | Alignment matmul, $F_0$ linear upsample, phase `cumsum`, `sin(2π·phase)` | **90% Covered** | **Gap 3 (~1 day):** Build alignment matrix `[T, L_frames]` on host/JS (<0.1 ms) + single `:einsum`; 1D `cumsum` on host/JS + `:uop/sin` harmonic bank. |
| **Phonemization (G2P)** | Text → IPA phonemes → 178-token vocab IDs | **Not a tensor op** | **Gap 4 (Reuse):** Do **not** rewrite `espeak-ng` in Clojure. Reuse `phonemizer` (`espeak-ng` WASM, ~3 MB) in-browser (identical to `kokoro-js`). |
| **Weights & Voicepacks** | `kokoro-v1_0.pth` / ONNX (`82M` params: ~328 MB FP32, ~86 MB Q8) + `.bin` voices (~510 KB each) | **Safetensors + INT4/FP32 loader exists** | One-time Python conversion script folding PyTorch `weight_norm` (`weight_g`, `weight_v` → single `weight` tensor) into `.safetensors` (or lightweight ONNX protobuf initializer reader). |

---

## 3. Top Technical Risks & Mitigations

1. **Dynamic Sequence Lengths (`L_frames`) in `:uop-webgpu` (Highest Risk):**
   - Unlike `T=1` autoregressive LLM decode, iSTFTNet runs over variable frame counts (`L_frames ∈ [50, 800]`). Compiling a fresh WGSL bundle per exact length would cause multi-second shader stutter.
   - **Mitigation:** Bucket `L_frames` to 4 fixed sizes (`128, 256, 512, 1024`) with zero-padding and output waveform trim — only **4 cached WGSL bundles** cover all sentences with zero recompilation.
2. **Weight-Norm & LSTM Gate Layout Parity:**
   - PyTorch `weight_norm` (`g * v / ||v||`) and LSTM gate ordering (`i, f, g, o`) must be folded/verified layer-by-layer against PyTorch/ONNX reference tensors (`max |Δ| < 1e-4` per module before full audio integration).
3. **Browser Memory & Download Footprint:**
   - FP32 weights (`328 MB`) work comfortably in WebGPU (our Gemma 4 E2B PoC already runs `1.72 GB` in Chrome at 12 tok/s!), and reusing `clj-einsum`'s Q8/INT4 quantization brings Kokoro-82M down to **~86 MB** (matching `kokoro-js` `q8`).

---

## 4. Effort Estimate & Strategic Recommendation

| Milestone | Scope | Effort |
|---|---|---:|
| **M0 (Today — ASK 2)** | Browser Hive Trace Visualizer with per-agent Kokoro voices riding `kokoro-js` (WebGPU/WASM) | **Today (2–3 hrs)** |
| **M1 (Core Primitives)** | Add `:conv1d` & `:conv-transpose1d` UOp lowerings + `test.check` parity tests (`:uop-c` + `:uop-webgpu`) | **2–3 days** |
| **M2 (JVM `:uop-c` MVP)** | Kokoro-82M safetensors export + `BiLSTM` step + `SineGen` + `CustomSTFT` → 24 kHz WAV parity vs PyTorch | **2–3 days** |
| **M3 (Browser `:uop-webgpu`)** | Bucketed WGSL compilation + `phonemizer` WASM + drop-in swap beneath the Hive Trace Visualizer | **3–4 days** |

**Strategic Reach Payoff:** Adding `:conv1d` and `:conv-transpose1d` to `clj-einsum` doesn't just unlock Kokoro-82M — it unlocks **Whisper (ASR), EnCodec/Mimi, Mamba-1D, and 1D audio/signal models** across both JVM and Browser WebGPU, directly advancing Simon's goal of making `clj-einsum` the inference engine with the widest reach.
