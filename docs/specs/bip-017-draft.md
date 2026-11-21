# BIP-017 (Draft): Hive Web App Promotion on `bitecho.org` & Chunked Large-Artifact Storage

- **Status:** Draft (v0.1 — Design Input & Endpoint Inventory for Nexus / Team Review)
- **Authors:** Vector (Hive UI / Inference & Storage Inventory) + Nexus (BIP Queue / Protocol & Infra Lead)
- **Sponsor / Direction:** Simon (2026-11-21)
- **Predecessors:** BIP-015 (`D41`), BIP-016 (`D42` — Rolodex & Memento Continuity), `D40.4` (`BitechoLightNode` `v1.0.0`)

---

## 1. Motivation & Proof

Today's live devnet session (`2026-11-21`) demonstrated the complete end-to-end **Hive Web App** running in-browser over `BitechoLightNode v1.0.0` (`v0.23.18`):
- Hardware YubiKey authentication (`createDiscoverableCredential` / `getDiscoverableCredential` / `recoverFromYubiKey` / IndexedDB persistence) unified with the active light-node keypair (`anchorPk === nodePk`).
- Live DM sessions (`openChannel` / `acceptOffer` / `pendingOffers`) and multi-line subnet broadcasts with deduplication.
- On-device voice loop ("TeamSpeak for chat"): **Moonshine Tiny (Local ONNX)** transcribed live speech in **229 ms** on-device with Silero VAD / 700 ms endpointing, and incoming messages were spoken aloud with per-petname **Kokoro-82M** voices.

Two structural blockers prevent this from being a first-class, self-sovereign deployment on `bitecho.org`:
1. **Split Web App & CORS Proxies:** The Hive UI currently runs on a local machine (`http://192.168.86.26:8787/chat`) and requires fetch-through proxies (`/api/bitecho-browser.js`, `/relay/v1/*`, `/api/artifact/v1/blob/*`) solely because it is not hosted same-origin on `bitecho.org`, while the legacy dashboard (`bitecho/resources/public/index.html`) still occupies `https://bitecho.org/app/`.
2. **Large Model Weight Distribution:** Open weights (Silero VAD, Moonshine, Whisper, Kokoro-82M, Gemma 4 E2B int4) range from `2 MiB` to `1.8 GiB` — far beyond single-blob relay/gossip limits (`256 KiB`–`16 MiB`) — forcing browsers either to fetch from third-party CDNs (Hugging Face / jsDelivr) or from a single operator's LAN HTTP server.

---

## 2. Part A — Promoting Hive UI as THE Web App on `bitecho.org`

### 2.1 Pointer & Hosting Cutover
- **`https://bitecho.org/app/` (`web/app/index.html`)** switches to serve the **Hive Live Chat UI** (`alpeware/hive/resources/trace_viz/chat.html`), with the Trace Audio Visualizer hosted alongside at `web/app/trace-viz.html`.
- **`web/app/bitecho-browser.js`** and **`web/app/bitecho-browser.js.sri`** continue to be published from `alpeware/bitecho` as the **headless Browser Light Node runtime ONLY** (preserving the repo boundary established 2026-11-21: `bitecho` = minimal protocol/runtime primitives; `hive` = Web UI + agent/inference UX).
- **Same-Origin Cleanup & Headers:** Hosting Hive on `bitecho.org/app/` permanently removes all localhost CORS proxy shims (`/api/bitecho-browser.js`, `/relay/v1/*`, `/api/artifact/v1/blob/*`). Recommended response headers on `/app/*` and `/artifact/v1/blob/*`:
  - `Content-Type` exact (`text/html; charset=utf-8`, `application/javascript; charset=utf-8`, `application/wasm`, `application/octet-stream`, `application/json`)
  - `Cache-Control: public, max-age=31536000, immutable` on `/artifact/v1/blob/<cid>` (content-addressed!) and `no-cache` on pointer-resolved HTML/manifests
  - `Cross-Origin-Opener-Policy: same-origin` + `Cross-Origin-Embedder-Policy: credentialless` (enables multi-threaded WASM `SharedArrayBuffer` for ONNX Runtime Web / Moonshine / Whisper / Kokoro without blocking WebAuthn or credential-less blob fetches)
  - `Access-Control-Allow-Origin: *` on `/artifact/v1/blob/*` so local dev servers and embedded clients can verify CIDs without CORS blocks.
- **Legacy Web App Deprecation:** `bitecho/resources/public/index.html` is deprecated. During a one-release grace period it may remain archived at `web/app/legacy.html` (or redirect to `/app/`), then removed.
- **Secondary Consumer (Gentoo OS Pointer-Driven Updates):** The exact same `bitecho/chunk-manifest/v1` primitive (`1 MiB`–`4 MiB` chunks, per-chunk SHA-256, resumable fetch, `grace-epochs >= 4` rotation) naturally covers Gentoo OS stage/binpkg/image updates without any second storage mechanism.

### 2.2 Additive `BitechoLightNode` `v1.1` Facade Completions
Live testing on `v0.23.18` confirmed that `window.BitechoLightNode` `v1.0.0` is missing top-level convenience methods that currently live only on `window.BitechoBrowser`. `v1.1` adds them cleanly (100% backwards-compatible with `v1.0.0`):
- `openSession(callee, opts)` (wraps `openChannel`)
- `acceptOffer(offer)` / `listPendingOffers()`
- `registerYubiKey()` / `recoverYubiKey()` / `saveIdentity()`
- `getChunkedArtifact(manifestCid, { onProgress, concurrency, signal })` (Part B below)

---

## 3. Part B — Endpoint Inventory Today (Measured Bytes & Access Patterns)

Every artifact class currently served from Vector's local endpoint (`trace_viz.clj` + local weights cache) or fetched from third-party CDNs by `/chat` and `mealy`:

| Artifact Class | Concrete Examples | Measured Size | Consumers | Access Pattern & Caching |
|---|---|---|---|---|
| **Web App Shell & Runtime** | `chat.html` (`46 KiB`), `index.html` (`31 KiB`), `bitecho-browser.js` (`1.55 MiB`), `.sri` (`71 B`) | `71 B – 1.55 MiB` | All browsers | Cold page load; immutable by CID + SRI; pointer-flipped on release cut (`seq` monotonic). |
| **Voice Activity Detection (VAD)** | Silero VAD v5 (ONNX / `clj-einsum` future port) | `~2 MiB` | Browser `/chat` STT | Single cold fetch on first mic enable; cached permanently in Browser OPFS / Cache API. |
| **Local Browser STT Weights** | Moonshine Tiny Streaming ONNX (`~34 MiB`), Moonshine Small (`~123 MiB`), Whisper Tiny/Base q8 (`39–73 MiB`) | `34 – 123 MiB` | Browser `/chat` STT | Fetched once per selected STT model; **resumable chunked download** with progress bar; cached in OPFS by manifest CID. |
| **TTS Acoustic & Vocoder Weights** | `kokoro-v1_0-folded.safetensors` (**`312 MiB`**, 459 FP32 tensors), Kokoro ONNX q8 (`~86 MiB`), `config.json` (`2.3 KiB`), WGSL shader buckets (`4 × ~3 KiB`) | `2.3 KiB – 312 MiB` | Browser `/chat`, Trace Visualizer, `clj-einsum` JVM/WebGPU nodes | Fetched once on TTS enable; **chunked + resumable**; `312 MiB` FP32 safetensors exceeds single-blob limits by >1,000×. |
| **Per-Petname Voice Embeddings** | `af_heart.safetensors` (**`511 KiB`**), `am_michael`, `bm_george`, `am_adam`, `bm_lewis`, etc. | `~511 KiB` each | Browser `/chat` & Trace Visualizer | Lazy-fetched on first message from a petname; tiny (1 chunk each); cached permanently by CID. |
| **Browser LLM Weights (`mealy`)** | Gemma 4 E2B IT int4 (`~1.8 GiB` sharded weights), GPT-2 (`~500 MiB`) | `500 MiB – 1.8 GiB` | Browser `mealy` WebGPU nodes | **Streaming chunked fetch** directly into OPFS / WebGPU staging buffers; must never buffer full `1.8 GiB` in JS heap at once. |
| **Traces & BIP-016 Continuity** | Verified Run-2/Run-3 JSONL traces (`50 KiB – 2 MiB`), BIP-016 encrypted Rolodex backups (`1.8 KiB – 256 KiB`) | `1.8 KiB – 2 MiB` | Hive UI, agents, light nodes | Point reads by CID; fits single-blob or 1–2 chunks; fail-closed SHA-256 verified. |

**Key takeaway:** A single chunked primitive covering `256 KiB`–`8 GiB` with `1 MiB` default chunks cleanly unifies **every** model weight class (VAD, STT, TTS, LLM) without bloating the core single-blob path.

---

## 4. Part C — Chunked Large-Artifact Protocol (`bitecho/chunk-manifest/v1`)

### 4.1 Normative Constants (`bitecho.storage.chunk-constants`)
- `manifest-domain-tag`: `"bitecho/chunk-manifest/v1"`
- `default-chunk-bytes`: `1048576` (`1 MiB`)
- `min-chunk-bytes`: `262144` (`256 KiB`)
- `max-chunk-bytes`: `4194304` (`4 MiB`)
- `max-chunks-per-manifest`: `8192` (up to `8 GiB` at `1 MiB` chunks, `32 GiB` at `4 MiB` chunks)
- `default-fetch-concurrency`: `4` (bounded parallel chunk window in browser/light nodes)

### 4.2 Manifest Schema (Pointer-Addressed)
A large artifact is published by:
1. Splitting the artifact deterministically into fixed-size chunks (`chunk-size`, last chunk `<= chunk-size`) and computing `chunk-cid = SHA-256(chunk-bytes)`.
2. Uploading each unique chunk as a standard content-addressed blob (natural cross-version deduplication across shared tensors/shards!).
3. Publishing a canonical EDN manifest blob whose CID is `manifest-cid = SHA-256(manifest-bytes)` and binding a signed subnet pointer (e.g., `bitecho.org/models` → `models/kokoro-82m/v1.0/kokoro-v1_0-folded.safetensors.manifest.edn`):

```clojure
{:schema :bitecho/chunk-manifest-v1
 :name "kokoro-v1_0-folded.safetensors"
 :media-type "application/octet-stream"
 :total-bytes 326925024
 :total-sha256 "<64-hex-sha256-of-complete-file>"
 :chunk-size 1048576
 :chunks [{:index 0 :cid "<64-hex>" :size 1048576}
          {:index 1 :cid "<64-hex>" :size 1048576}
          ;; ...
          {:index 311 :cid "<64-hex>" :size 822784}]
 :retention-policy {:tier :pinned-weights
                    :min-replicas 3
                    :grace-epochs 4
                    :expires-epoch nil}
 :storage-accounting {:owner-pubkey "<spki-hex>"
                      :total-accounted-bytes 326925024
                      :chunk-count 312
                      :cache-incentive-ech-per-mib 10
                      :burn-receipt-cid "<cid-or-nil-if-sponsored-subnet>"}}
```

### 4.3 Pure Sans-IO Fetch Reducer (`bitecho.storage.chunk-fetch`)
Following `AGENTS.md` Rules 2, 4, and 5:
- Single generation-tagged state map updated by `(transition state event) -> [new-state effects]`.
- Effects: `:fetch-manifest`, `:check-local-cache`, `:fetch-chunk`, `:store-local-chunk`, `:verify-complete`, `:emit-progress`, `:diagnose`.
- **Resumable Fetch:** Before issuing network requests, `:check-local-cache` inspects browser OPFS / local disk for already-verified `chunk.cid` entries; only missing chunks are queued. Reloading a tab at 60% resumes at chunk `187/312` with zero wasted bandwidth.
- **Fail-Closed Integrity (Zero Silent Failures):**
  - Every received chunk MUST satisfy `SHA-256(bytes) === chunk.cid` and `len(bytes) === chunk.size`.
  - Any mismatch emits a counted `:diagnose` (`:chunk-cid-mismatch`, `:chunk-size-mismatch`, `:chunk-fetch-failed`), blacklists the faulty mirror for that generation, and retries against the next mirror (`ns1`/`ns2`/`ns3`/mesh peer).
  - Stale-generation callbacks (`gen < current`) emit counted `:diagnose` (`:ignored-stale-chunk`) and never mutate state.
  - Final reassembly verifies `SHA-256(all_chunks) === total-sha256` (streaming incremental SHA-256 so `1.8 GiB` models never require full in-RAM buffering).

### 4.4 State Taxonomy, Caching Incentive & Retention/Rotation
Every byte stored or served is accounted under the Bitecho state taxonomy:
- **Accounted Bytes:** `total-accounted-bytes = sum(chunk.size) + manifest-size`. No unindexed or unmeasured blobs.
- **Caching Incentive:** Mirrors and browser/light-node peers serving verified chunks earn `:cache-incentive-ech-per-mib` (or operate under zero-burn sponsored public-model subnets such as `bitecho.org/models` anchored by the builders-stewards circle).
- **Retention & Rotation:** Mirrors BIP-016's grace-window semantics (`grace-epochs >= 4`): when a model pointer rotates (`v1.0` → `v1.1`), chunks unreferenced by any active pointer remain pinned for `grace-epochs` before GC eligibility, preventing mid-download `404`s for in-flight browsers. Unreferenced chunks still shared by another manifest stay alive via content-addressed refcounting.

---

## 5. Verification & Rollout Plan (BIP-016 Closed-Loop Process)

1. **Draft & Co-Authoring (Current Step):** Nexus reviews & aligns Part A (`web/app/` cutover + `BitechoLightNode v1.1` facade) and Part C (chunk manifest constants + `/artifact/v1/blob/<cid>` mirror support + CLI `artifact-put-chunked` / `artifact-get-chunked`).
2. **Digital Twin Circle Review:** Run the 5-seat Hive digital twin circle over BIP-017 to surface edge cases (corrupt chunk injection, partial resume, GC race during rotation, OPFS quota eviction).
3. **Sandboxed Implementation & Telemetry (`bwrap`):**
   - Pure `.cljc` `bitecho.storage.chunk-constants` + `bitecho.storage.chunk-fetch` with `clojure.test.check` generative + model-based command-sequence tests (including stale-generation callbacks & corrupt-chunk injection).
   - Real E2E smoke: chunk & publish Kokoro `af_heart.safetensors` (`511 KiB`) + `kokoro-real-weights-golden.safetensors` (`146 KiB`) + multi-MiB test weight, resume mid-download in Headless Chrome 156, verify bit-exact SHA-256.
4. **Formal Review & Real 2-Gate Sociocratic Consent:** Present consented proposal + patch + telemetry to the team; zero landing on `main` without real consent.
