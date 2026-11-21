# BIP-017b (Shaped Draft 1 — All 6 Twin-Circle Catches Resolved)

- **Status:** Shaped Draft 1 (Ready for Real Stage 1 Consent — Zero Writes to `main` Before Real Stage 2 Consent)
- **Authors:** Nexus (BIP Queue / Protocol & Infra Core) + Vector (Hive UI / Inference & Twin-Circle Sim)
- **Sponsor / Direction:** Simon (2026-11-21)
- **Precondition Gate (SATISFIED):** BIP-017a 144-Epoch Soak Exit Receipt (`CID 2ecc673dec5c4cc072ec91d614b2b4d85119b67a51d6682254b54faf8d37dd7b`, `154` consecutive finalized epochs `69506 -> 69660`, `0 ms` downtime, `:six-nines-met? true`, `100/100` live Chrome `af_heart` fetches)
- **Anchors:**
  - Draft 0: `CID c51e7e26b91ac477463b96fd5a511911c1a2b73f14482459313d85c106d4fb81`
  - 5-Seat Digital Twin Circle Trace (advisory-only, `:fixed-point-2`, 43 turns / 2 rounds, Merkle `410048ac...`): `CID ef480aa10425265e93e415af092c2c6c673ced83059c8f8a597e7d63215467ff`
  - Twin-Circle Patch & Shaped Checklist: `CID c761ff3e8f7fd61062274cf3d5beda2f2fc2828c189d222751412cf534917747`

---

## 1. Objective, Scope Hygiene & Repo Boundary (Twin-Circle Catches #5 & #6)

Promote the **Hive Live Chat & Trace Visualizer Web App** to `https://bitecho.org/app/` as **THE canonical Bitecho web app**, deprecating the legacy dashboard, with atomic pointer-driven deployment and rollback:
- **Strict Single-Purpose Scope (Catch #5):** The `node_main.cljs` `HTTPS_PROXY` fix (`:agent false` + `Connection: close` across all 3 `createConnection` helpers: `http-get!`, `http-get-bytes!`, `http-post-json!`) is **isolated out of BIP-017b** into a standalone pre-`v0.23.20` housekeeping patch.
- **Two-Repo Separation (Catch #6):** `alpeware/bitecho` (infra atomic release resolver, pure Sans-IO core, CLI verbs, `legacy.js` extraction) and `alpeware/hive` (externalized `chat.js`/`trace-viz.js`, self-hosted inference JS/WASM, zero-proxy Hive UI) carry **separate reproducible `.patch` CIDs and separate 8-gate receipts**.

---

## 2. Atomic `web/app/release` Pointer & Pure Resolver (`bitecho.storage.app-release`, Catches #2 & #4)

### 2.1 Canonical Release Manifest Schema (Domain-Separated, 2-of-3 Quorum Signed)
Infra resolves a single monotonic pointer `web/app/release` on `bitecho.org/infra`:

```clojure
{:schema :bitecho/web-app-release-v1
 :release-id "v0.23.20-hive-chat-1"
 :seq 1
 :created-at-ms 1795300000000
 :genesis-anchor-cid "<64-hex-pinned-genesis-release-cid>"
 :owner-pubkeys ["<spki-hex-1>" "<spki-hex-2>" "<spki-hex-3>"]
 :quorum-threshold 2
 :retention-policy {:max-entries 64
                    :max-bytes 67108864 ;; >= 2x single release so active + LKG never evict each other
                    :rotate-every-entries 16
                    :ttl-epochs 100000
                    :grace-epochs 4}
 :bytes-at-rest 12500000
 :assets {:index-html     {:cid "<64-hex>" :media-type "text/html; charset=utf-8" :size 18432}
          :chat-js        {:cid "<64-hex>" :media-type "application/javascript; charset=utf-8" :size 32768 :sri "sha384-..."}
          :trace-viz-html {:cid "<64-hex>" :media-type "text/html; charset=utf-8" :size 14336}
          :trace-viz-js   {:cid "<64-hex>" :media-type "application/javascript; charset=utf-8" :size 20480 :sri "sha384-..."}
          :legacy-html    {:cid "<64-hex>" :media-type "text/html; charset=utf-8" :size 54771}
          :legacy-js      {:cid "<64-hex>" :media-type "application/javascript; charset=utf-8" :size 8192 :sri "sha384-..."}
          :browser-js     {:cid "<64-hex>" :media-type "application/javascript; charset=utf-8" :size 1585353 :sri "sha384-..."}
          :browser-sri    {:cid "<64-hex>" :media-type "text/plain; charset=utf-8" :size 124}
          :headers-config {:cid "<64-hex>" :media-type "application/edn; charset=utf-8" :size 768}
          ;; Self-hosted inference runtime JS + .wasm (ZERO cdn.jsdelivr.net!):
          ;; kokoro-js, transformers.js, vad-web, onnxruntime-web (.mjs + .wasm)
          }
 :weight-manifests {:silero-vad "<bip-017a-manifest-cid>"
                    :moonshine-tiny "<bip-017a-manifest-cid>"
                    :kokoro-82m "<bip-017a-manifest-cid>"
                    :af-heart "<bip-017a-manifest-cid>"}
 :lkg-release-cid "<previous-verified-release-cid-or-nil>"}
```

Detached quorum signature envelope (outside canonical bytes):
`{:release-cid "<SHA-256(\"bitecho/web-app-release/v1\" || canonical-edn)>", :sigs [{:owner-pubkey "..." :sig "..."} ...]}`.

### 2.2 Pure Sans-IO Pre-Switch Validation (`AGENTS.md` Rules 2, 4, 5 — Catch #4)
Before switching active `/app/*` generation (`gen -> gen + 1`), `bitecho.storage.app-release/validate-release-bundle` MUST verify fail-closed:
1. **CID-Pinned Genesis Anchor (Zero TOFU):** Node must have a configured `:genesis-anchor-cid`; missing/mismatched genesis emits counted `:diagnose` `:release-genesis-missing` and halts.
2. **Persisted Monotonic `current-seq` Floor:** Persisted on disk across restarts; `seq <= current-seq` or `gen < current-gen` emits `:release-seq-non-monotonic` / `:ignored-stale-release` with zero mutation.
3. **Domain-Separated Quorum Signatures (`2-of-3`):** At least `:quorum-threshold` distinct keys in `:owner-pubkeys` must have valid Ed25519 signatures over `SHA-256("bitecho/web-app-release/v1" || canonical-edn)`; failure emits `:release-sig-invalid`.
4. **Atomic `:lkg-release-cid` Retention Pin:** Retention `:max-bytes >= 2 * active-release-bytes` and `:lkg-release-cid` must remain pinned locally; eviction or insufficient cap emits `:release-lkg-evicted` / `:retention-bound-exceeded`.
5. **Generalized Executable Asset & Transitive SRI Check (Catch #2):**
   - Every asset CID in `:assets` exists locally and matches `SHA-256(bytes) === asset.cid` (`:release-asset-missing` / `:release-asset-corrupt`).
   - EVERY executable JS/WASM asset (`chat-js`, `trace-viz-js`, `legacy-js`, `browser-js`, self-hosted ONNX/Kokoro/Moonshine/VAD JS & `.wasm`) satisfies `SHA-384(bytes) === asset.sri`, and HTML `<script>` / `<script type="importmap">` references match those exact SRI hashes (plus SRI-verified `wasmBinary` and signed Worker blobs); any mismatch halts with `:release-sri-mismatch`.
6. **Strict Byte Accounting:** `:bytes-at-rest == sum(assets[].size)` (`:storage-accounting-mismatch`).

---

## 3. Strict CSP, Zero Inline Scripts & Zero-CDN Self-Hosting (Catches #1, #2, #3)

- **Zero Inline `<script>` (Catch #1):** Extract inline `<script type="module">` from `chat.html`, `trace-viz.html`, and `legacy.html` into external SRI-pinned `chat.js`, `trace-viz.js`, `legacy.js`; remove `/api/bitecho-browser.js` and all localhost proxy shims.
- **Zero Third-Party CDNs (Catch #2):** Self-host all inference JS & `.wasm` (`kokoro-js`, `transformers.js`, `vad-web`, `onnxruntime-web`) under `:assets`, and pin all model weights via BIP-017a manifests in `:weight-manifests`.
- **Pointer-Versioned `/app/*` HTTP Headers (Catch #3 — `blob:` added to `connect-src`!):**
  - `Cross-Origin-Opener-Policy: same-origin`
  - `Cross-Origin-Embedder-Policy: credentialless`
  - `Cache-Control: no-cache`
  - Exact `Content-Type` per asset (including `application/wasm`)
  - Strict CSP:
    `default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' blob: https://bitecho.org https://ns1.bitecho.org https://ns2.bitecho.org https://ns3.bitecho.org wss://bitecho.org; worker-src 'self' blob:; img-src 'self' data: blob:; media-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`
  *(Adding `blob:` to `connect-src` ensures ONNX Runtime Web `fetch()` of OPFS `URL.createObjectURL` `externalData` succeeds cleanly under `COOP/COEP` with zero CSP violations!)*

---

## 4. Zero-Copy OPFS -> ONNX, Chrome Ceilings & Pre-Fleet Rollback Dry-Run (Catch #6)

- **Headless Chrome Pass/Fail Gate (under `COOP: same-origin` + `COEP: credentialless`):**
  - **0 CSP violations** verified via CDP `Log` / `Audits` domains!
  - JS heap growth `<= 64 MiB` (CDP `Runtime.getHeapUsage`)
  - WASM linear memory `<= weight_bytes + 128 MiB`
  - WebGPU buffers `<= weight_bytes + 128 MiB`
  - YubiKey WebAuthn (`PublicKeyCredential`) verified non-blocked
  - Streaming `total-sha256` verified bit-exact.
- **Pre-Fleet Lab Rollback Dry-Run (Blocking Gate Before Any Fleet Node Moves):**
  1. Mint & verify `.lkg` `web/app/release` (`seq = N`, legacy `index.html` + `legacy.js` + `bitecho-browser.js` + `.sri` + legacy `headers-config`).
  2. Publish Hive `web/app/release` (`seq = N+1`).
  3. Pre-sign & execute monotonic rollback (`seq = N+2`, NEVER rewinding `seq`) back to `.lkg`, asserting exact bytes match `.sri`, `index.html`, and legacy `headers-config`, with `0` CSP violations.
- **Fleet Rollout:** 1 node at a time (`ns3 -> ns2 -> ns1 -> node3 -> node2 -> node1`) holding `4/6` quorum and `1/3` edge availability; rollback first on any regression.
- **Rule 6 CLI & Flag Inventory:** Register `web-release-publish`, `web-release-rollback`, `web-release-verify` and `--release-cid`, `--genesis-anchor-cid`, `--lkg-release-cid`, `--headers-config-cid` across `bitecho.light-node.cli`, `bitecho.browser-cli`, `skill/SKILL.md`, and live-parser inventory tests.
