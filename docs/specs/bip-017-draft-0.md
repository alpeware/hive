# BIP-017 (Draft 0): Hive Web App Promotion on `bitecho.org` & Chunked Large-Artifact Storage

- **Status:** Draft 0 (Ready for 5-Seat Digital Twin Circle Review — Zero Writes to `main` Before Real Stage 2 Consent)
- **Authors:** Nexus (BIP Queue / Protocol & Infra Core) + Vector (Hive UI / Inference & Storage Inventory)
- **Sponsor / Direction:** Simon (2026-11-21)
- **Design Input Anchor:** Vector Endpoint Inventory (`CID 11a164817e37aeed85c9639f5e5184a658efea92fccc3d9b4219de42c4f28631`)
- **Predecessors:** BIP-015 (`D41`), BIP-016 (`D42` — Unified Rolodex & Memento Continuity), `D40.4` (`BitechoLightNode v1.0.0`)

---

## 1. Motivation & Proof

Live devnet testing (`2026-11-21`) proved the **Hive Web App** (`/chat`) running in-browser over `BitechoLightNode v1.0.0` (`v0.23.18`):
- Unified YubiKey WebAuthn + IndexedDB identity (`anchorPk === nodePk`), live DM offers/sessions, multi-line subnet broadcasts, on-device **Moonshine Tiny** STT (`229 ms` with Silero VAD), and per-petname **Kokoro-82M** TTS.
- Two structural blockers prevent self-sovereign deployment on `bitecho.org`:
  1. Hive UI still lives on a local dev server (`192.168.86.26:8787/chat`) using CORS proxy shims, while the legacy web app occupies `https://bitecho.org/app/`.
  2. Open weights (Silero VAD `2 MiB`, Moonshine `34–123 MiB`, Whisper `39–73 MiB`, Kokoro-82M `86–312 MiB`, Gemma 4 E2B `1.8 GiB`) and future Gentoo OS `.gpkg.tar` updates (`~185 MiB`) exceed single-blob limits (`256 KiB`–`4 MiB`).

---

## 2. Part A — Promoting Hive UI as THE Web App on `bitecho.org`

### 2.1 Repo Boundary & Hosting Cutover
- **Boundary preserved:** `alpeware/bitecho` stays minimal (protocol, CLI, infra, headless `bitecho-browser.js`); `alpeware/hive` owns the Web UI (`chat.html`, `trace-viz.html`) and inference/simulation stack.
- **Pointer cutover on `bitecho.org/infra`:**
  - `web/app/legacy.html` <- archive current legacy `index.html` (`16d52748...`) for a 1-release grace period.
  - `web/app/index.html` <- Hive Live Chat UI (`chat.html`, `~46 KiB`), removing all localhost CORS proxy shims (`/api/bitecho-browser.js`, `/relay/v1/*`, `/api/artifact/v1/blob/*`).
  - `web/app/trace-viz.html` <- Hive Trace Audio Visualizer (`~31 KiB`).
  - `web/app/bitecho-browser.js` + `.sri` <- headless `BitechoLightNode v1.1.0` runtime.
- **Mechanical Rollback:** Single monotonic pointer bump of `web/app/index.html` back to `16d52748...` (`legacy.html`).

### 2.2 HTTP Headers & Security Isolation (Folds in Axiom `seq: 182` Blob Content-Type/CSP Fix)
- **`/app/*` (pointer-resolved app shell):**
  - Exact `Content-Type` (`text/html; charset=utf-8`, `application/javascript; charset=utf-8`, `application/wasm`).
  - `Cache-Control: no-cache` on pointer-resolved HTML/manifests.
  - `Cross-Origin-Opener-Policy: same-origin` + `Cross-Origin-Embedder-Policy: credentialless` (unlocks multi-threaded WASM `SharedArrayBuffer` for ONNX/Kokoro/Moonshine without breaking WebAuthn or credential-less blob fetches).
- **`/artifact/v1/blob/<cid>` (immutable content-addressed blobs & chunks):**
  - `Cache-Control: public, max-age=31536000, immutable`
  - `Access-Control-Allow-Origin: *`
  - Safe `Content-Type` allowlist (`application/octet-stream`, `application/wasm`, `application/json`, `text/plain; charset=utf-8`, `text/markdown; charset=utf-8`); any `text/html` served via `/artifact/v1/blob/<cid>` MUST include strict `Content-Security-Policy: sandbox` (without `allow-same-origin`) to prevent same-origin XSS.

### 2.3 Additive `BitechoLightNode v1.1.0` Facade (100% Backwards-Compatible with `v1.0.0`)
Adds first-class camelCase methods directly on `window.BitechoLightNode` (matching full local light-node parity already present in `.exec`/`.cli`):
- `openChannel(callee, opts)` (and alias `openSession(callee, opts)`)
- `acceptOffer(offer)` / `approveOffer(offerId)` / `denyOffer(offerId)` / `listPendingOffers()` (plus `getStatus().pendingOffers`)
- `registerYubiKey()` / `recoverYubiKey()` / `saveIdentity()`
- `getChunkedArtifact(manifestCid, { onProgress, concurrency, signal, mirrors })`

---

## 3. Part B — Chunked Large-Artifact Protocol (`bitecho/chunk-manifest/v1`)

### 3.1 Normative Constants (`bitecho.storage.chunk-constants`)
- `manifest-domain-tag`: `"bitecho/chunk-manifest/v1"`
- `default-chunk-bytes`: `1048576` (`1 MiB`)
- `min-chunk-bytes`: `262144` (`256 KiB`)
- `max-chunk-bytes`: `4194304` (`4 MiB`) — hard single-chunk upload ceiling
- `max-chunks-per-manifest`: `8192` (`8 GiB` at `1 MiB` chunks; `32 GiB` at `4 MiB` chunks)
- `default-fetch-concurrency`: `4` (bounded parallel chunk window)
- `min-grace-epochs`: `4` (`N-min = 4`, matching BIP-016)
- `default-infra-chunk-quota-bytes`: `2147483648` (`2 GiB` default per infra node; effective cap = `(min constant config)`, clamping attempts emit counted `:diagnose` `:cap-ceiling-clamped`)

### 3.2 Canonical Manifest Schema (Pointer-Addressed, Standing State Taxonomy)
Every byte is accounted for under Simon's standing state taxonomy (reusing `bitecho.memento.constants` validators):
```clojure
{:schema :bitecho/chunk-manifest-v1
 :name "kokoro-v1_0-folded.safetensors"
 :media-type "application/octet-stream"
 :total-bytes 326925024
 :total-sha256 "<64-hex-sha256-of-complete-file>"
 :chunk-size 1048576
 :chunks [{:index 0 :cid "<64-hex>" :size 1048576}
          ;; ...
          {:index 311 :cid "<64-hex>" :size 822784}]
 :owner-pubkeys ["<spki-hex>"]
 :retention-policy {:tier :pinned-weights
                    :max-entries 8192
                    :max-bytes 4294967296
                    :rotate-every-entries 64
                    :ttl-epochs 100000
                    :min-replicas 3
                    :grace-epochs 4}
 :storage-accounting {:bytes-at-rest 326957024 ;; sum(chunk.size) + manifest-bytes
                      :chunk-count 312
                      :cache-incentive :free-self-hosted ;; or :upfront-fee-ech
                      :payer-pubkey "<spki-hex>"
                      :fee-ech 0
                      :cache-incentive-ech-per-mib 10
                      :burn-receipt-cid nil}}
```
- Missing `:owner-pubkeys`, missing/unbounded `:retention-policy`, or mismatched `:bytes-at-rest` rejects fail-closed with counted `:diagnose` (`:missing-state-owner`, `:missing-retention-policy`, `:storage-accounting-mismatch`).
- **Infra Memory & Disk Safety (940 MB RAM `ns1..ns3`):** Infra nodes stream chunks (`<= 4 MiB`) directly to/from disk (`< 16 MiB` RSS delta) and reject uploads exceeding `:max-chunk-store-bytes` with counted `:diagnose` `:chunk-store-quota-exceeded` (HTTP `507`).
- **Rotation & GC Grace:** Unreferenced chunks on pointer rotation remain pinned for `grace-epochs >= 4` before GC eligibility; chunks shared across manifests stay alive via content-addressed refcounting.

### 3.3 Pure Sans-IO Fetch Reducer (`bitecho.storage.chunk-fetch`)
Follows `AGENTS.md` Rules 2, 4, and 5:
- Single generation-tagged state map updated by `(transition state event) -> [new-state effects]`.
- Effects: `:fetch-manifest`, `:check-local-cache`, `:fetch-chunk`, `:store-local-chunk`, `:verify-complete`, `:emit-progress`, `:diagnose`.
- **Resumable Fetch:** `:check-local-cache` inspects browser OPFS / local disk by verified `chunk.cid`; only missing chunks are fetched.
- **Fail-Closed Integrity (Zero Silent Failures):**
  - Every chunk verifies `SHA-256(bytes) === chunk.cid` and `len(bytes) === chunk.size`.
  - Corrupt or failed chunks emit counted `:diagnose` (`:chunk-cid-mismatch`, `:chunk-size-mismatch`, `:chunk-fetch-failed`), blacklist the faulty mirror for that generation, and retry on the next mirror (`ns1`/`ns2`/`ns3`/peer).
  - Stale-generation callbacks (`gen < current`) emit counted `:diagnose` (`:ignored-stale-chunk`) and never mutate state.
  - Final reassembly verifies streaming incremental `SHA-256(all_chunks) === total-sha256` (no full `1.8 GiB` JS heap buffering).

### 3.4 CLI Verb & Flag Inventory (`AGENTS.md` Rule 6)
- Verbs: `artifact-put-chunked`, `artifact-get-chunked`, `artifact-manifest-verify`
- Flags: `--chunk-size`, `--concurrency`, `--resume-dir`, `--grace-epochs`, `--cache-incentive`, `--max-bytes`
- Registered across `bitecho.light-node.cli`, `bitecho.browser-cli`, and `skill/SKILL.md` with automated flag-inventory tests.

---

## 4. Verification & Closed-Loop Process (Zero Writes to `main` Before Real Stage 2 Consent)

1. **Digital Twin Circle Review (Next Step):** Vector runs the 5-seat Hive digital twin circle on this Draft 0 (`CID` below) to stress-test edge cases (corrupt chunk injection, mid-download resume, rotation GC grace race, OPFS quota eviction, infra 940 MB RAM & disk quota enforcement, COOP/COEP + WebAuthn interaction).
2. **Real Stage 1 Consent:** Present shaped BIP-017 + twin-circle findings to Simon/Axiom/Lumen/Vector.
3. **Disposable-Branch Implementation & `bwrap` Telemetry:**
   - Pure `.cljc` `bitecho.storage.chunk-constants` + `bitecho.storage.chunk-fetch` with `clojure.test.check` generative + model-based command-sequence tests (stale generations + corrupt chunk injection).
   - Streaming `/artifact/v1/blob/<cid>` + safe `Content-Type`/CSP + `/app/*` COOP/COEP headers + `BitechoLightNode v1.1.0` additive facade.
   - Real E2E smoke: chunk & publish Kokoro `af_heart.safetensors` (`511 KiB`) + multi-MiB test weight, interrupt at 50%, resume in Headless Chrome, verify bit-exact SHA-256 & flat infra RSS.
4. **Real Stage 2 Consent & Landing:** Land on `main` only after real Stage 2 consent, followed by `v0.23.19` release cut & canary rollout.
