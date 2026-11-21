# BIP-017b (Draft 0): Hive Web App Promotion on `bitecho.org/app/` & Atomic `web/app/release` Pointer

- **Status:** Draft 0 (Ready for 5-Seat Digital Twin Circle Review — Zero Writes to `main` Before Real Stage 2 Consent)
- **Authors:** Nexus (BIP Queue / Protocol & Infra Core) + Vector (Hive UI / Inference & Twin-Circle Sim)
- **Sponsor / Direction:** Simon (2026-11-21)
- **Precondition Gate (SATISFIED):** BIP-017a 144-Epoch Soak Exit Gate PASSED (`154` consecutive finalized epochs `69506 -> 69660`, `0 ms` service downtime, `:six-nines-met? true`, `100/100` live Chrome `af_heart` fetches, Receipt `CID 2ecc673dec5c4cc072ec91d614b2b4d85119b67a51d6682254b54faf8d37dd7b`)
- **Predecessor Spec Anchor:** BIP-017a/b Shaped Draft 1 (`CID fbcfe7bbe91f4738ac8735a66f0ee63e7567f986da512544b5e310143dfc6fde`)

---

## 1. Objective & Repo Boundary

Promote the **Hive Live Chat & Trace Visualizer Web App** to `https://bitecho.org/app/` as **THE canonical Bitecho web app**, deprecating the legacy dashboard (`resources/public/index.html`), with atomic pointer-driven deployment and rollback:
- **Repo Boundary Preserved:** `alpeware/bitecho` stays minimal (protocol, CLI, infra `/app/*` atomic release resolver, headless `bitecho-browser.js` `BitechoLightNode v1.1.0`); `alpeware/hive` owns the Web App UI (`chat.html`, `trace-viz.html`) and on-device inference UX.
- **Self-Hosting (Zero Third-Party CDNs, Zero Localhost CORS Proxies):**
  - All `/chat` weights (Silero VAD, Moonshine/Whisper STT, Kokoro-82M TTS, per-petname voice embeddings) are served exclusively from **BIP-017a** chunked manifests (`bitecho/chunk-manifest/v1`).
  - Hosting same-origin on `https://bitecho.org/app/` permanently removes all localhost proxy shims (`/api/bitecho-browser.js`, `/relay/v1/*`, `/api/artifact/v1/blob/*`).

---

## 2. Atomic `web/app/release` Pointer & Pure Resolver (`bitecho.storage.app-release`)

### 2.1 Canonical Release Manifest Schema (Signed Monotonic Pointer)
Instead of updating `index.html`, `trace-viz.html`, `bitecho-browser.js`, `.sri`, and HTTP headers via separate non-atomic pointer flips (which risks SRI mismatch during mid-deploy loads), infra resolves a **single monotonic pointer** `web/app/release` on `bitecho.org/infra`:

```clojure
{:schema :bitecho/web-app-release-v1
 :release-id "v0.23.20-hive-chat-1"
 :seq 1
 :created-at-ms 1795300000000
 :owner-pubkeys ["<spki-hex>"]
 :retention-policy {:max-entries 64 :max-bytes 16777216 :rotate-every-entries 16 :ttl-epochs 100000 :grace-epochs 4}
 :bytes-at-rest 1662000
 :assets {:index-html       {:cid "<64-hex>" :media-type "text/html; charset=utf-8" :size 47104}
          :trace-viz-html   {:cid "<64-hex>" :media-type "text/html; charset=utf-8" :size 31744}
          :legacy-html      {:cid "<64-hex>" :media-type "text/html; charset=utf-8" :size 54771}
          :browser-js       {:cid "<64-hex>" :media-type "application/javascript; charset=utf-8" :size 1585353}
          :browser-sri      {:cid "<64-hex>" :media-type "text/plain; charset=utf-8" :size 124}
          :headers-config   {:cid "<64-hex>" :media-type "application/edn; charset=utf-8" :size 640}}
 :lkg-release-cid "<previous-verified-web-app-release-cid-or-nil>"}
```

### 2.2 Pure Sans-IO Validation & Pre-Switch Gate (`AGENTS.md` Rules 2, 4, 5)
Before an infra node switches its active `/app/*` release generation (`gen -> gen + 1`), pure `bitecho.storage.app-release/validate-release-bundle` MUST verify:
1. Monotonic `seq > current-seq` (non-monotonic or stale-generation updates emit counted `:diagnose` `:release-seq-non-monotonic` / `:ignored-stale-release` and NEVER mutate state).
2. Non-empty `:owner-pubkeys`, valid `:retention-policy` (`grace-epochs >= 4`), and exact `:bytes-at-rest == sum(assets[].size)`.
3. Every asset CID in `:assets` is present locally and matches `SHA-256(bytes) === asset.cid` (missing/corrupt asset emits `:release-asset-missing` / `:release-asset-corrupt` and keeps previous generation active!).
4. Cross-asset SRI invariant: `SHA-384(browser-js bytes)` matches the `sha384-...` token inside `browser-sri` AND `index-html` references that exact SRI token! Any mismatch halts with counted `:diagnose` `:release-sri-mismatch`.
5. `headers-config` CID parses cleanly and conforms to the strict `/app/*` header policy below.

### 2.3 Pointer-Versioned `/app/*` HTTP Headers & Strict CSP
Infra serves `/app/*` headers strictly from the resolved `:headers-config` CID (never hardcoded):
- `Cross-Origin-Opener-Policy: same-origin`
- `Cross-Origin-Embedder-Policy: credentialless` (enables multi-threaded WASM `SharedArrayBuffer` for ONNX/Moonshine/Kokoro without breaking WebAuthn or credential-less blob fetches)
- `Cache-Control: no-cache` on pointer-resolved HTML/release endpoints
- Exact `Content-Type` per asset (`text/html; charset=utf-8`, `application/javascript; charset=utf-8`, `application/wasm`)
- Strict CSP:
  `default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://bitecho.org https://ns1.bitecho.org https://ns2.bitecho.org https://ns3.bitecho.org wss://bitecho.org; worker-src 'self' blob:; img-src 'self' data: blob:; media-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`
  *(Note: `script-src 'self' 'wasm-unsafe-eval'` prohibits inline `<script>` and `eval()`, requiring Hive's UI JS to live in an external SRI-pinned script or non-inline module — twin circle to verify whether `chat.html` currently has inline `<script>` that needs extracting into `chat.js`!)*

---

## 3. Zero-Copy OPFS -> ONNX Assembly & Headless Chrome Ceilings

- **Loading Path:** BIP-017a `getChunkedArtifact` writes verified chunks sequentially to OPFS (or composes multi-`File` `Blob` handles) -> `FileSystemFileHandle.getFile()` -> `URL.createObjectURL(file)` -> ONNX Runtime Web `externalData` URL entries (only the small `.onnx` graph is buffered; zero contiguous full-weight JS `ArrayBuffer`).
- **Headless Chrome Pass/Fail Gate (under `COOP: same-origin` + `COEP: credentialless`):**
  - JS heap growth `<= 64 MiB` over pre-load baseline (CDP `Runtime.getHeapUsage`)
  - WASM linear memory `<= weight_bytes + 128 MiB`
  - WebGPU buffers `<= weight_bytes + 128 MiB`
  - YubiKey WebAuthn (`PublicKeyCredential` discoverable credential flow) verified non-blocked
  - Streaming `total-sha256` verified bit-exact.

---

## 4. Legacy Web App Deprecation & Pre-Fleet `.lkg` Rollback Dry-Run

- **Deprecation:** Current legacy `resources/public/index.html` (`e0dcbf79...` on `v0.23.19` / `16d52748...` on `v0.23.18`) is archived at `web/app/legacy.html` for a 1-release grace period, then removed.
- **Pre-Fleet Lab Dry-Run (Blocking Gate Before Any Fleet Node Moves):**
  1. Mint a verified `.lkg` `web/app/release` revision (`seq = N`) pointing to the legacy `index.html` + `bitecho-browser.js` + `.sri` + a legacy `headers-config` CID.
  2. Publish the new Hive `web/app/release` revision (`seq = N+1`).
  3. Execute a **monotonic rollback** (`seq = N+2`, NEVER rewinding `seq`!) pointing back to the `.lkg` asset set and assert: `index.html` bytes match `.lkg`, `browser-js` matches `.sri`, `index.html` references that SRI, and legacy `headers-config` resolves cleanly.
- **Fleet Rollout Procedure:** 1 node at a time (`ns3 -> ns2 -> ns1 -> node3 -> node2 -> node1`), holding `4/6` quorum and `1/3` edge availability; rollback first on any regression.

---

## 5. Housekeeping Micro-Fix Folded Into `v0.23.20`
- Fix `http-get-bytes!` in `src/bitecho/node_main.cljs` for `HTTPS_PROXY`-tunneled requests (`:agent false` + `Connection: close` when using custom `createConnection` `tls-sock`) so Axiom's sandboxed P-UPD-1 auto-updater fetches 1.67+ MB bundles cleanly without `keepAlive` aborts.

---

## 6. Verification & Governance Discipline
- **Rule 1–6 Compliance:** Pure `.cljc` `bitecho.storage.app-release` + `clojure.test.check` generative & model-based command-sequence tests (including corrupt asset, SRI mismatch, stale release seq, and monotonic rollback), `clojure -M:format && clojure -M:lint` (0/0), live-parser CLI/flag inventory tests (`web-release-publish`, `web-release-rollback`, `web-release-verify`), co-committed spec & `skill/SKILL.md`.
- **Zero Writes to `main`:** Twin circle -> Shaped Draft 1 -> Real Stage 1 Consent -> Disposable-branch TDD impl + lab rollback dry-run + Headless Chrome ceiling telemetry -> Real Stage 2 Consent -> Land on `main` (`v0.23.20`).
