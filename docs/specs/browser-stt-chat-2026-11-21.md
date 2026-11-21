# Browser Speech-to-Text for the Bitecho Chat App — Research Note

**Date:** 2026-11-21 · **Author:** Axiom · **Requested by:** Simon
**Context:** The reverse of the Kokoro TTS work (2026-11-21 morning). Target experience: in the web
app chat, incoming messages are read aloud with per-petname voices (TTS, landed), and Simon talks
into a microphone — silence detection endpoints the utterance, speech-to-text transcribes it, and
the text is sent to the current channel (broadcast or 1:1). "TeamSpeak for chat."

## 1. The loop, decomposed

`getUserMedia (echoCancellation + noiseSuppression)` → AudioWorklet resample to 16 kHz mono →
**VAD gate** (speech-start / speech-end events) → **STT on the utterance segment only** →
transcript into the composer (auto-send in hands-free mode) → normal chat send.

Design points that decide whether it feels good:

- **Endpointing is the UX.** The wait after you stop talking is mostly the silence threshold,
  not the model. 600–800 ms of trailing silence is the chat sweet spot (voice-agent defaults
  cluster at 500 ms–2.5 s; chat wants the snappy end).
- **Echo / barge-in.** While TTS plays through speakers, the mic hears it. Browser AEC
  (`echoCancellation: true`) is the first line; the standard pattern is *pause playback on
  possible barge-in, resume if no transcript follows* — never hard-cancel on VAD alone (VAD-only
  barge-in measured at ~66% false positives in one study). A headset sidesteps most of it.
- **Audio never leaves the device.** Every candidate below runs fully in-browser; weights are
  distributed as Blob + Pointer like the Kokoro weights, not fetched from a third-party CDN at
  runtime.

## 2. Silence detection (VAD) — solved, cheap

**Silero VAD** is the obvious pick: ~309K params, a **~2 MB ONNX file**, 512-sample (32 ms)
frames at 16 kHz, **<1 ms per frame on one CPU thread**, MIT-licensed, and far more robust in
noisy rooms than WebRTC VAD or energy thresholds. In the browser it runs via ONNX Runtime Web;
`@ricky0123/vad-web` is the standard wrapper. Endpointing logic on top is a ~40-line hysteresis
gate (open 0.5 / close 0.35 + min-silence + padding).

*clj-einsum distance:* Silero's graph is conv + LSTM — both landed in clj-einsum today (M1/M2).
It is the **cheapest meaningful port in this whole note** and would make the loop's gate ours too.

## 3. STT engine candidates

| Engine | Size (browser) | Latency character | Languages | Browser path | Notes |
|---|---|---|---|---|---|
| **Moonshine Tiny/Small Streaming** (Useful Sensors, MIT) | 34 MB / 123 MB | **Streaming** — transcribes while you talk; ~34–165 ms CPU per utterance end | English (streaming line) | ONNX (ORT Web / transformers.js) | Variable-length compute — no padding waste on short utterances. WER 12.0% tiny / 7.8% small (vendor benchmarks) |
| **Whisper tiny/base** via transformers.js | q8 39/73 MB (WASM), fp16 73/139 MB (WebGPU) | Non-streaming; fixed 30 s window padding | 99 | ONNX Runtime Web, WebGPU or WASM | Accuracy reference. One field report: on an Iris Xe laptop WASM-CPU *beat* WebGPU for tiny/base, and Android WebGPU was unstable → WASM q8 is the safe default, WebGPU an opportunistic boost |
| **whisper.cpp WASM** | ~40 MB (tiny q8) | Non-streaming, CPU only | 99 | Emscripten WASM (SIMD) | Mature, tiny/base/small ceiling in practice |
| **Web Speech API** | 0 | Fast | Many | Chrome built-in | **Reject as default**: Chrome's implementation ships audio to Google/Microsoft servers — wrong shape for a sovereign stack. Acceptable only as an explicit opt-in fallback |

Moonshine's architecture is the chat-shaped one: Whisper spends the same compute on a 2-second
utterance as on 30 seconds; Moonshine's compute scales with actual speech, and its streaming
models cache encoder state so the transcript is essentially ready when you stop talking.
Whisper wins on languages and on being the known reference — and it is the port target with a
reference map (tinygrad's `whisper.py`, which Simon already pointed Vector at).

## 4. The clj-einsum port distance (STT edition)

Same shape as the Kokoro assessment, and this morning's work already paid part of it:

- **Whisper frontend** = log-mel spectrogram: an STFT — expressible as conv1d against a DFT
  basis, the exact trick Kokoro's iSTFT used in reverse — plus a fixed mel filterbank matmul.
  The Whisper stem convs (k=3, stride 1/2) were **parity-verified in M1 today**.
- **Encoder** = transformer blocks: covered (same op set as Gemma/ALBERT).
- **Decoder** = autoregressive transformer with cross-attention + KV cache: the mealy decode
  pattern, covered in kind; the wiring + BPE tokenizer + greedy decode loop are the real work.
- **Moonshine** = conv frontend + encoder-decoder transformer with sliding-window attention
  and a streaming cache — no new primitives, but the streaming state machine is novel work.
- **Silero VAD** = conv + LSTM at 2 MB: days, not weeks, and it is on the critical path of the
  loop regardless of which STT engine sits behind it.

Honest estimate, Kokoro-calibrated: VAD port is small; a Whisper tiny/base port is a
days-to-~two-weeks effort dominated by decoder wiring and tokenization, not new primitives.

## 5. Recommendation

Mirror the TTS pattern exactly — ride the ecosystem engine in the Hive UI now, port to
clj-einsum as the strategic path:

1. **Phase 1 (Hive UI):** Silero VAD (ONNX, self-hosted 2 MB) for silence detection +
   **Moonshine Small Streaming** for live feel (Whisper base via transformers.js as the
   accuracy/multilingual alternative behind the same backend dropdown the visualizer just
   gained). Push-to-talk *and* voice-activated modes; transcript lands in the composer,
   auto-send only in hands-free mode.
2. **Phase 2 (clj-einsum):** port Silero VAD first (closes the loop with our own gate), then
   Whisper tiny/base, following tinygrad's `whisper.py` as the reference map. Moonshine
   streaming as a later reach item.
3. **Not doing:** Web Speech API as a default path (cloud dependency, Chrome-only).

*Sources: lofttools.com browser-Whisper review; offlinetts.com browser STT comparison;
forasoft faster-whisper-WASM article; dev.to transformers.js field report (measured WASM vs
WebGPU downloads/latencies); modelslab + Moonshine AI benchmarks (vendor numbers, flagged as
such); micdrop.dev + kadirnar voice-agent VAD research (Silero sizing/endpointing practice).*
