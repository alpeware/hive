# BIP-017a & BIP-017b (Shaped Draft 1 — All 6 Twin-Circle Objections Resolved)

- **Status:** Shaped Draft 1 (Ready for Pre-Stage-1 `ns1` RSS Baseline Evidence + Real Stage 1 Consent — Zero Writes to `main` Before Real Stage 2 Consent)
- **Authors:** Nexus (BIP Queue / Protocol & Infra Core) + Vector (Hive UI / Inference & Twin-Circle Sim)
- **Sponsor / Direction:** Simon (2026-11-21)
- **Anchors:**
  - Vector Endpoint Inventory: `CID 11a164817e37aeed85c9639f5e5184a658efea92fccc3d9b4219de42c4f28631`
  - Draft 0: `CID 015a2735cf9876ee758afd9a7033764b77fef6fd7ed6f8a477351e364685cf47`
  - 5-Seat Digital Twin Circle Trace (advisory-only, 62 turns / 3 rounds, Merkle `25467d24...`): `CID b7469480f13c4b7154e385d3bd3a0b9afe1cc17312354668a69a7d285fba7959`
  - Twin-Circle Patch & Blocking Checklist: `CID 3307570680b941658437499986bfff3f3094dc44b6ecd4f845767a39fbc859d0`

---

## 0. Clean Two-BIP Split (Twin-Circle Catch #1)

BIP-017 is split into two independently consented, independently verifiable milestones:
- **BIP-017a — Chunked Large-Artifact Storage (`bitecho/chunk-manifest/v1`) + `BitechoLightNode v1.1.0` Additive Facade + Safe Blob Headers:** Lands on `main` first (`v0.23.19`) and must pass a **>= 144-epoch soak gate** on `ns1`, `ns2`, `ns3`, and one browser peer before `017b` begins.
- **BIP-017b — Hive Web App Promotion on `bitecho.org/app/` + Atomic `web/app/release` Rollback Pointer:** Starts ONLY after `017a`'s 144-epoch soak gate passes, carrying its own separate Stage 1 & Stage 2 consent and 8-gate landing receipt.

---

## 1. BIP-017a — Chunked Large-Artifact Storage & Additive Browser Facade

### 1.1 Normative Constants (`bitecho.storage.chunk-constants`)
- `manifest-domain-tag`: `"bitecho/chunk-manifest/v1"`
- `min-chunk-bytes`: `65536` (`64 KiB` — resolves Catch #3 so `af_heart.safetensors` `511 KiB` splits into 8 chunks for real 50% multi-chunk resume!)
- `default-chunk-bytes`: `1048576` (`1 MiB`)
- `max-chunk-bytes`: `4194304` (`4 MiB`)
- `max-chunks-per-manifest`: `8192`
- `default-fetch-concurrency`: `2`, `max-fetch-concurrency-940mb`: `3` (resolves Catch #4: prevents `4 × 4 MiB` from breaching `< 16 MiB` RSS on 940 MB `ns1..ns3` nodes)
- `min-grace-epochs`: `4` (`N-min = 4`)
- `default-infra-chunk-quota-bytes`: `2147483648` (`2 GiB`); effective cap = pure `(min constant config)` emitting counted `:diagnose` `:cap-ceiling-clamped` (zero clamp logic in shell).

### 1.2 Manifest v1 Schema & Detached Owner Envelope (Twin-Circle Catches #2 & #5)
- **Minimal v1 Scope (Integrity, Ownership, Retention, Bytes-at-Rest ONLY — zero Echo fee/incentive/burn fields):**
  - Canonical fields: `:schema`, `:name`, `:media-type`, `:total-bytes`, `:total-sha256`, `:chunk-size` (`64 KiB <= size <= 4 MiB`), `:chunks [{:index :cid :size}]`, `:owner-pubkeys`, `:retention-policy` (`{:max-entries :max-bytes :rotate-every-entries :ttl-epochs :grace-epochs}` with `:grace-epochs >= 4`), `:bytes-at-rest`.
  - Canonical serialization: RFC 8785 JCS with lowercase hex and integers only.
  - `manifest-cid = SHA-256("bitecho/chunk-manifest/v1" || canonical-bytes)`.
- **Detached Owner Signature Envelope (Catch #2):**
  - Stored outside `canonical-bytes`: `{:manifest-cid :owner-pubkey :sig}`.
  - Signing payload = `manifest-cid`. When `:owner-pubkeys` lists multiple keys, quota is billed to the single key whose signature verifies; if `> 1` owner signatures verify, reject fail-closed with counted `:diagnose` `:owner-signature-ambiguous`.
- **Strict Byte Accounting (Catch #5):**
  - Core enforces `:bytes-at-rest == :total-bytes == sum(:chunks[].size)` (per-manifest billing for v1).
  - Infra chunk store counts **staged-but-unhashed bytes** toward `:bytes-at-rest` and HTTP `507` `:chunk-store-quota-exceeded` (plus dedicated counted `:diagnose` `:max-bytes-exceeded` on `--max-bytes` breach).
  - Mandatory fail-closed `:diagnose` codes: `:missing-state-owner`, `:owner-signature-invalid`, `:owner-signature-ambiguous`, `:missing-retention-policy`, `:storage-accounting-mismatch`, `:cap-ceiling-clamped`, `:chunk-store-quota-exceeded`, `:max-bytes-exceeded`.

### 1.3 Pure Sans-IO Fetch Reducer & Incremental Hashing (Twin-Circle Catch #5 / `AGENTS.md` Rules 2, 4, 5)
- Single generation-tagged state map in `bitecho.storage.chunk-fetch`, updated exclusively by `(transition state event) -> [new-state effects]`:
  - Tracks per-chunk status, `:hash-cursor` (strictly index-ordered `0..N-1`), per-generation mirror blacklist, OPFS/local cache state, and counted `:diagnose` (`:phase`, `:generation`, `:last-error`, `:dropped`, `:cap-ceiling-clamped`).
- **Zero Mutable Digest in Reducer:** Incremental whole-file SHA-256 (`total-sha256`) runs strictly as `:hash-cursor`-driven shell effects (`:digest-chunk` -> `:digest-result` / `:digest-final`) behind a shared protocol (JVM `MessageDigest` + streaming CLJS SHA-256). Out-of-order chunks are CID/size-verified and staged to disk/OPFS; RAM backpressure pauses `:fetch-chunk` when in-flight bytes reach `concurrency × chunk-size`.
- **Distinct Fault Semantics (Zero Silent Failures):**
  - Corrupt chunk -> `:chunk-cid-mismatch` / `:chunk-size-mismatch`, blacklist mirror for current generation, bounded retry across `ns1`/`ns2`/`ns3`/peers, loud `:failed` on exhaustion.
  - HTTP `410` -> `:chunk-gone` / `:grace-expired` (NEVER blacklists mirror; never counted as `:chunk-fetch-failed`).
  - OPFS eviction -> `:cache-evicted`, re-queue & re-verify via `:check-local-cache`.
  - Stale callback (`gen < current`) -> counted `:ignored-stale-chunk`, zero state mutation.
  - Staged/final mismatch -> counted `:staged-chunk-corrupt` / `:total-sha256-mismatch`.

### 1.4 Additive `BitechoLightNode v1.1.0` Facade & Safe Blob Headers
- Strictly additive on `window.BitechoLightNode` (passes all `v1.0.0` conformance tests unchanged): `openChannel` / `openSession`, `acceptOffer`, `approveOffer`, `denyOffer`, `listPendingOffers` (`status.pendingOffers`), `registerYubiKey`, `recoverYubiKey`, `saveIdentity`, `getChunkedArtifact`.
- `/artifact/v1/blob/<cid>`: `Cache-Control: public, max-age=31536000, immutable`, `Access-Control-Allow-Origin: *`, safe `Content-Type` allowlist (`application/octet-stream`, `application/wasm`, `application/json`, `text/plain; charset=utf-8`, `text/markdown; charset=utf-8`), and strict `Content-Security-Policy: sandbox` (without `allow-same-origin`) on any `text/html` blob.

### 1.5 Rule 6 CLI & Flag Inventory
- Verbs: `artifact-put-chunked`, `artifact-get-chunked`, `artifact-manifest-verify`
- Flags: `--chunk-size`, `--concurrency`, `--resume-dir`, `--grace-epochs`, `--max-bytes`
- Verified by live-parser flag-inventory tests (no hardcoded counts) + co-committed spec, ops scripts, and `skill/SKILL.md`.

### 1.6 BIP-017a Quantitative Verification & Soak Gates
- **Strict TDD (`clojure.test.check`):** Model-based command-sequence tests written BEFORE core impl covering 50% interrupt/resume, OPFS eviction, HTTP `410`, corrupt-chunk injection, stale generations, quota `507`, refcounted GC after `grace-epochs >= 4`, and byte-identical JVM == CLJS manifest CIDs & state/effect traces.
- **Pre-Stage-1 Lab Evidence:** Measure & publish `ns1` `bwrap` RSS baseline CID before Stage 1 closes (unmeasured baseline = automatic FAIL).
- **`af_heart` 50% Resume Gate:** `--chunk-size 65536` (`64 KiB`, 8 chunks) interrupted at chunk 4/8 and resumed in Headless Chrome -> bit-exact SHA-256, peak RSS growth `< 16 MiB` at `concurrency = 3`.
- **144-Epoch Soak Exit Gate (Required Before BIP-017b):** `>= 144` consecutive epochs on `ns1`, `ns2`, `ns3`, and 1 browser peer (`af_heart` + 1 multi-MiB Moonshine/Kokoro weight, `>= 100` full verified fetches/node):
  - `0` un-injected `:chunk-cid-mismatch`, `:owner-signature-invalid`, `:storage-accounting-mismatch`, or `:failed` phases.
  - `:dropped == sum(counted causes)` (zero uncounted drops).
  - `:chunk-fetch-failed <= 0.1%` (100% recovered).
  - `:grace-expired` GC matches expected refcount trace.
  - Peak RSS growth `< 16 MiB` on every node, every epoch. (Multi-GiB models like Gemma 1.8 GiB stay out of scope until this soak passes.)

---

## 2. BIP-017b — Hive Web App Promotion on `bitecho.org/app/` (Gated on 017a Soak)

- **Self-Hosting:** All `/chat` weights served exclusively from `017a` manifests (zero third-party CDN, zero localhost CORS proxies).
- **Zero-Copy OPFS -> ONNX Assembly (Catch #6):** Sequential single-file OPFS writes or multi-`File` `Blob` composition -> `URL.createObjectURL` -> ONNX Runtime Web `externalData` URLs (zero contiguous JS `ArrayBuffer` copy).
- **Headless Chrome Pass/Fail Ceilings (`COOP: same-origin` + `COEP: credentialless`):**
  - JS heap growth `<= 64 MiB` (CDP `Runtime.getHeapUsage`)
  - WASM linear memory `<= weight_bytes + 128 MiB`
  - WebGPU buffers `<= weight_bytes + 128 MiB`
  - YubiKey WebAuthn verified working; streaming `total-sha256` verified.
- **Atomic `web/app/release` Pointer & `.lkg` (`16d52748...`) Rollback Dry-Run (Catch #6):**
  - Single monotonic `web/app/release` pointer naming `{index.html, trace-viz.html, bitecho-browser.js, bitecho-browser.js.sri, headers-config}` by CID (infra serves `/app/*` headers + strict CSP strictly from the resolved `headers-config` CID).
  - Pre-fleet lab dry-run verifies monotonic rollback to `.lkg` (`16d52748...`), asserting original bytes match `.sri`, `index.html`, and a resolved legacy `headers-config` CID.
  - Rollout: 1 node at a time holding `4/6` quorum and `1/3` edge availability; rollback first on any regression.

---

## 3. Governance & Consent Invariant
- Simulated twin-circle outcomes are advisory only; `017a` and `017b` each carry separate real Stage 1 consent, separate disposable-branch implementation + telemetry, and separate real Stage 2 8-gate consent receipts before any write to `main`.
