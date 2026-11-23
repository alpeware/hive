# BIP-021: Light-Node Authority, Session Recovery, Browser Parity & `:443` Passthrough Resilience (Stage 1 Shaped Draft 1)

| Field | Value |
| :--- | :--- |
| **BIP Number** | `BIP-021` |
| **Pipeline Stage** | **Stage 1 Shaped Draft 1** (**Strictly Advisory** — `:advisory-only? true`, `:real-consent-required? true`, `:simulated-consent-counts-as-real? false`) |
| **Stage 0 Proposal CID** | `1618fbd593514d65ae33d136e887dbd13e4c27851825fafad364968b09d1d7f1` (`bitecho.org/dev` → `dist/bips/bip-021-proposal.md`, `24,502 B`) |
| **Base Commit (`HEAD`)** | `b072e6a` (`bitecho`), companion `8318c2d` (`alpeware/hive`) |
| **Stage 1 Execution Mode** | Independent Blind Round-1 (`:blind? true`, `:peer-outputs []`, 5 isolated frontier `research` subagents via `bitecho.hive.twin-stage1`) |
| **Stage 1 Twin Verdicts** | `4 :objection` (`:simon`, `:axiom`, `:nexus`, `:lumen`), `1 :amendment` (`:vector`), `0 :no-objection` |
| **Stage 1 Merkle Trace v2 Root** | `9a56eae584b5bdf23f3ec8b37cc86abf224748529d0cfe1f1bda7446758b189a` (`{:ok? true, :turn-count 5}`) |
| **Stage 1 Twin Report** | `docs/bips/bip-021-stage1-twin-report.edn` (`bitecho.org/dev` → `dist/bips/bip-021-stage1-twin-report.edn`) |
| **Subagent Audit IDs** | `:simon` `a98de612`, `:axiom` `33b514e0`, `:nexus` `79b947e9`, `:vector` `622d16df`, `:lumen` `1b145f90` |
| **Change Class** | **Staging-First** (`bitecho-gentoo-lab`, per `RELEASE.md` §3) |
| **Stage 2 Ack Window** | `N = 10,000` epochs, **directed-delivery clock start**, unanimous **5/5** real circle consent (`simon`, `axiom`, `nexus`, `vector`, `lumen`) |
| **Pre-Implementation Guard** | **Zero BIP-021 code on `main` before Real Stage 2 Draft Consent** (`docs/GOVERNANCE.md` §2) |

---

## 0. Stage 1 Independent Blind Round-1 Executive Summary

Running 5 truly isolated frontier digital twins (`:blind? true`, `:peer-outputs []`) with mandatory falsifiable `:falsifier` slots produced **unanimous / near-unanimous independent convergence** on **five production-breaking defects** in the Stage 0 proposal:

1. **§2.4 `tx-seq` Rollback — AEAD Nonce Reuse, False `p2p-offer` Remedy, 1-Message Race, & `>= 64` `:stale-seq` Deadlock (5/5 Unanimous):**
   - `sealed/seal-message` uses deterministic `seq->nonce(seq)` under `:tx-key`. By the time `session-ack` returns after a VM rollback, the sender has **already encrypted a second distinct plaintext under the same `(:tx-key, seq->nonce(k))`**, leaking `P1 ⊕ P2` and compromising AES-256-GCM GHASH key `H` / Poly1305 `(r, s)`. Continuing to send `seq = peer-high-seq + 1` on a tainted `:tx-key` is unsafe.
   - `exec-p2p-offer` only exchanges ICE/UDP transport candidates and **NEVER rotates X25519/AEAD session keys (`:tx-key` / `:rx-key`)**.
   - Because `exec-send` increments local `:tx-seq` *before* transmission, any 1-message rollback (`19 -> 18`, send `19'`) or burst `N >= (peer_rx_high_seq - restored_tx_seq)` leaves `local_tx_seq == peer_high_seq`, so `(> peer-high-seq local-tx-seq)` evaluates `false` — firing **zero** rollback diagnostics while `(acked? s)` silently purges undelivered post-restore messages from `:outbox` as acknowledged.
   - Rollbacks `>= 64` messages hit `:stale-seq` in `sealed/check-and-advance-replay-window` (window size `64`), which `handle-wire-sealed-msg` drops in `:else` with **zero `session-ack` and zero diagnostics**, causing a permanent silent blackhole.
   - Blocking synchronous `.fsyncSync` on Node.js's single-threaded event loop stalls sends `10–100+ ms` and races with `save-light-state-async!`.
2. **§2.6.2 Mesh-First Auto-Updater — Non-Existent RPC, 256 KiB SCTP Ceiling, Generation Livelock & Fail-Open Trust (5/5 Unanimous):**
   - `exec-artifact-get` is local-only (`:net-envelopes []`), and real bundles (`bitecho-light-node.cjs` `~1.71 MiB`, JVM uberjar `>15 MiB`) exceed WebRTC SDP `a=max-message-size:262144` (`256 KiB`) and HTTP relay `64 KiB` — unchunked mesh fetch will fail 100% of the time on real releases while toy Gate 5 tests pass.
   - `reduce-light-update-tick` lacks a `:phase == :watching` guard on `:poll-tick`/`:pointer-seen`, causing self-cancelling generation livelock when `--auto-update-interval-ms < 45s`, never dispatches `:blob-fetch-failed` back to the reducer, races `.writeFile` against `:atomic-swap-and-restart-light-node`, omits `full_node_main.cljs`, and `verify-config-pointer` fails **open** when `:trusted-deploy-authorities` is empty.
3. **§2.5.3 Encrypted On-Network Rolodex — Guaranteed HTTP 403, WebCrypto Non-Extractable Crash, BIP-016 Conflict & Agency/Privacy Violations (5/5 Unanimous):**
   - `bitecho.org/rolodex` does not exist on live `ns1..ns3`; `POST /artifact/v1/pointer` routes only to `bitecho.org/infra` (`:permissioned :allowlist`), guaranteeing HTTP 403 for end users; and `core/subnet.cljc` forbids `:streamlet-bft` on `:permissionless` subnets.
   - `webcrypto-non-extractable` mode (`:extractable? false`) has no raw Ed25519 private key in JS memory — HKDF from "user's identity secret" crashes, and `exec-artifact-put` currently synthesizes a `fallback-kp` when locked instead of failing closed.
   - Stage 0 omits AEAD cipher, AAD, random 96-bit CSPRNG nonce, fixed-bucket padding, and BIP-016 (`memento/rolodex.cljc`) grow-only deletion tombstones; silently auto-uploads on UI edits (risking silent 100 ECH burn per edit) without explicit user opt-in; and leaks identity roster + edit timestamps via unauthenticated `/artifact/v1/list`.
4. **§2.6.1 `:443` Passthrough Graceful Drain & Overnight `~14 MiB/hr` `arrayBuffers` Drift (5/5 Unanimous):**
   - `transition-passthrough-pair` omits explicit `{:effect :cancel-drain-timeout}` on `:closed`, omits counted `:diagnose` on `:drain-timeout`, lacks unpipe/listener/chunk-buffer cleanup and a global concurrent `:draining` cap for `940 MiB` `e2-micro` nodes, and a flat `5,000 ms` total (rather than idle-progress) timeout still RST-truncates slow multi-MiB transfers.
   - Stage 4 Gate 1 (`>= 60 min`, `<= +5%` 2-point RSS) aliases against the 30s `~27 MiB` `offheap-gc` sawtooth and permits `+11.4 MiB/hr` drift; `/statusz` currently lacks `TCPSocketWrap`/FD/active-draining counters.
5. **§2.1–§2.3 & §2.5.1–§2.5.2 Security, Routing, Allowlist & Honesty Gaps (4–5/5 Converged):**
   - **§2.1:** Must fence/reset cross-network-URI state (`devnet`/`testnet`/`mainnet`), strictly check `(vals config/network-uris)`, cover `run-mcp!`/`try-delegate-one-shot-to-daemon!`/`run-daemon!`, reconcile `--subscribe-subnets`, and distinguish `chat.js` page-load default `subnetId` from explicit user/CLI override.
   - **§2.2:** Returning `true` for `nil`/`"*"` in `subnet-admitted?` opens a wildcard direct-envelope spoofing bypass; session replies (`exec-send`, `build-session-ack-envelope`, `p2p-answer`) must tag the **session's bound `:subnet-id`** (not primary `:subnet-id`); `:chain-state-snapshot` must be exempted; browser `poll-relay-envelopes!` must pass `?subnets=`.
   - **§2.3:** Must catch malformed non-map `wire-dac` via `(some? wire-dac)` even when `requires-dac?` is false, surface all `[:diagnostics ...]` in `exec-status`/`getStatus()`, register new event types in `actionable-event-types`, and rate-limit unauthenticated `broadcast-sig-invalid` against `:event-queue` eviction.
   - **§2.5.1–§2.5.2:** Wire `project-light-state-liveness` into both `exec-browser-cli` AND `browser-cli/dispatch-browser-cli` + `get-status`; **never** report `transport_healthy: true` or suppress `:connection-error` when `mesh_open_peers == 0` (preserve Hive `DEGRADED (relay)` badge); allowlist new non-liveness keys in `shell/browser.cljc/allowed-persistence-keys`; add generation-tagged timeout + `AbortController` + `online`/`offline` listeners to browser `auto-sync`.

---

## 1. Resolution of Stage 0 Open Questions (§4) & Scope Option for Stage 2

| Open Question | Stage 1 Converged Resolution |
| :--- | :--- |
| **OQ1 (§2.4 `tx-seq` Rollback)** | **Fail-Closed Quarantine + Real X25519 Rekey (NOT bump-only, NOT `p2p-offer`).** Advance `:tx-seq` to `peer-high-seq` strictly as a nonce-fence, latch `:tx-tainted? true` / `:rekey-required`, evict/flag colliding outbox entries *before* `(acked? s)` purge, and trigger a **real X25519 key-rotating handshake** (`channel-offer`/`rendezvous-offer`, auto-initiated when `:autonomous? true`, explicit consent prompt when `:autonomous? false`). Tag `:replayed-seq` AND `>= 64` `:stale-seq` signed `session-ack`s with `{:replay-rejected? true :rejected-seq msg-seq :accepted-ciphertext-sha256 ...}` (signed inside `ack-sign-bytes` with zero legacy fallback when present) so 1-message/burst rollbacks (`local_tx_seq == peer_high_seq`) and `>= 64` rollbacks are caught deterministically. |
| **OQ2 (§2.5.3 Rolodex Namespace)** | **Network-URI-Scoped Zero-Burn `bitecho.org/rolodex` Subnet (`:permissionless` + `:gossip-crdt` + `:self-path-owner` ACL, `--rolodex-subnet` override) + Explicit Opt-In & Privacy Hardening.** Hosting on a dedicated network-URI-scoped subnet prevents blank-device recovery deadlocks and cross-subnet pollution while avoiding `bitecho.org/infra` `:allowlist` 403s and governance Merkle churn. Must enforce explicit user opt-in (zero silent upload/overwrite, zero silent 100 ECH burn), `<= 256 KiB` per-pubkey quota, fixed `4 KiB` ciphertext padding, and blinded/unlinkable option or explicit metadata disclosure. |
| **OQ3 (§2.6.2 Mesh-First Timeout)** | **Reject Flat `15,000 ms` — Use Two-Tier BIP-017a Chunked Watchdog in Pure `deploy/core.cljc`.** Pair BIP-017a chunked mesh transfer (`<= 192–256 KiB` chunks respecting WebRTC SCTP `a=max-message-size:262144`) with a **2,500–3,000 ms** fail-fast probe/manifest TTFB timeout, **5,000 ms** per-chunk inactivity stall timeout, and size-scaled total ceiling `clamp(15,000 ms, 120,000 ms, 5,000 + ceil(size_MiB) * 3,000)`, guarded by `:phase == :watching` in `reduce-light-update-tick`. |

> **Scope Option for Real Circle at Stage 2 (flagged by `:simon`):** Because §2.5.3 (On-Network Encrypted Rolodex + multi-subnet `bitecho.org/rolodex` infra provisioning + WebAuthn PRF KDF + BIP-016 tombstone unification + Hive UI) carries significantly larger crypto/infra surface than the five tight hardening pillars (§2.1, §2.2, §2.3, §2.4, §2.5.1–§2.5.2, §2.6), the real circle may decide at Stage 2 whether to **keep all 6 pillars unified in BIP-021** (with the full hardened spec below) or **split §2.5.3 cleanly into BIP-022** so core light-node/passthrough/updater hardening ships immediately.

---

## 2. Hardened Technical Specification (Stage 1 Shaped)

### 2.1 Startup `subnet_id` Authority & Cross-Network Fencing
1. Pure Sans-IO `(reconcile-startup-subnet-id state explicit-subnet-id explicit-subscribe-subnets)` in `src/bitecho/light_node/core.cljc`:
   - Distinguish **explicit** CLI/user override from parser/page-load default (`"global"` in CLI argv, `"devnet.bitecho.org"` in `hive/resources/trace_viz/chat.js`). Bare restarts without an explicit override preserve persisted `:subnet-id` with `0` spurious diagnostics.
   - Check canonical network URI strictly against `(vals config/network-uris)` (never `config/resolve-network-uri` which echoes arbitrary strings).
   - **Cross-network fence:** If switching `:subnet-id` crosses canonical `:network-uri` boundaries (`devnet` ↔ `testnet` ↔ `mainnet`), **fail closed** with a counted `:diagnose` (`:cross-network-subnet-switch-blocked`) unless `--reset-network-state` is explicitly passed (which clears network-scoped `:account/:finalized-epoch`, `:chain-state-snapshots`, `:sessions`, `:relay-since-broadcast-seq`).
   - Within the same canonical network, update `:subnet-id`, merge `explicit-subscribe-subnets` + new `:subnet-id` into `:subscribed-subnets` (preserving prior subscriptions), increment `[:diagnostics :subnet-id-reconciled]`, and emit `{"event" "subnet-id-reconciled" "from" old-subnet "to" new-subnet}` (registered in `actionable-event-types`).
2. Wire into **all** startup/restore paths:
   - `node_main.cljs` & `shell/light_node.clj`: daemon (`serve`, `ensure`/`start`), one-shot (`run-one-shot!`, warning/failing loudly if delegating over `/ctl` to a daemon on a different subnet), and `run-mcp!`.
   - `browser_main.cljs`: unify `:subnet-id` under a single authority (Rule 4) across `[:light-state :subnet-id]` and `[:conn :subnet-id]`, separate `defaultSubnetId` (fallback on empty IndexedDB) from `explicitSubnetId` (override), reconcile inside `apply-restored-state!`, and persist immediately.

### 2.2 Subscribed-Subnet Admission & Session Reply Routing
1. **No wildcard/nil spoofing bypass:** In `src/bitecho/light_node/core.cljc` (`handle-wire-payload`), derive effective inner/outer subnet via `(or inner-subnet outer-subnet "global")` and **reject** untagged (`nil`) or wildcard (`"*"`) unsolicited direct envelopes (`channel-offer`, `rendezvous-offer-header`, etc.) unless the node explicitly subscribed to `"*"`.
2. Require strict inner-vs-outer equivalence without conflating distinct canonical roots in `config/equivalent-subnet-id?`.
3. For **established sessions**, bind `:subnet-id` on the session record at handshake and stamp `exec-send`, `build-session-ack-envelope`, and `p2p-answer` with `(:subnet-id session)` (falling back to primary `(:subnet-id state)`), preventing asymmetric reply drops across subscribed subnets.
4. Exempt `:chain-state-snapshot` from `:subnet-mismatch` drops on non-root subnets; pass `?subnets=` in `browser_main.cljs` `poll-relay-envelopes!`; emit counted `:diagnose` + `:subnet-mismatch-dropped` on all rejections.

### 2.3 Distinct Broadcast & DAC Diagnostics + Status Parity
1. Evaluate broadcast admission in strict order in `src/bitecho/light_node/core.cljc`:
   - Shape/malformed envelope → `[:diagnostics :broadcast-malformed]` + `"broadcast-malformed"`.
   - `(not sig-valid?)` → `[:diagnostics :broadcast-sig-invalid]` + `"broadcast-sig-invalid"`.
   - `sig-valid?` ∧ `requires-dac?` ∧ `(nil? wire-dac)` → `[:diagnostics :broadcast-dac-missing]` + `"dac-required-missing"`.
   - `sig-valid?` ∧ `(some? wire-dac)` ∧ `(or (not (map? wire-dac)) (not dac-valid?))` → `[:diagnostics :broadcast-dac-invalid]` + `"broadcast-dac-invalid"` (catching malformed non-map `wire-dac` even when `requires-dac?` is false).
   - Preserve aggregate `[:diagnostics :broadcast-rejected]` counter.
2. Include `local_epoch`, `dac_id`, `anchor_pubkey`, and `dac_error` in DAC rejection events.
3. Register `broadcast-rejected`, `subnet-id-reconciled`, `session-replay-detected`, and `session-seq-mismatch` in `actionable-event-types`, surface the full sanitized `(:diagnostics state)` map in `exec-status` and `BitechoLightNode.getStatus()`, and isolate/rate-limit unauthenticated `broadcast-sig-invalid` events so floods cannot evict the 256-entry `:event-queue`.

### 2.4 Session `tx-seq` Rollback Detection, AEAD Quarantine & Real X25519 Rekey
1. **Receiver-side (`handle-wire-sealed-msg`, zero replay weakening):**
   - Store per-seq `ciphertext-sha256` digests in the 64-slot replay window.
   - On **both** `:replayed-seq` (`lag < 64`) **and** `:stale-seq` (`lag >= 64`): NEVER decrement `:rx-high-seq` or deliver to `:inbox`; increment `[:diagnostics :session-replay-detected]` (and `[:diagnostics :session-stale-seq-detected]` for `>= 64`); emit `"session-replay-detected"`; and return a rate-limited Ed25519-signed `session-ack` carrying `{:replay-rejected? true :rejected-seq msg-seq :peer-high-seq rx-high-seq :accepted-ciphertext-sha256 <digest-if-in-window>}`.
   - Sign all new rollback fields inside `ack-sign-bytes` with **zero legacy-signature fallback** when any rollback field is present (preventing forged-field grafting onto captured legacy ACKs).
   - Detect receiver-side VM-restore gaps (`msg-seq > rx-high-seq + 1` after restart) with counted `:session-rx-gap-detected`.
2. **Sender-side (`handle-wire-session-ack`, deterministic collision detection + real X25519 rekey):**
   - Bound `peer-high-seq` (`<= 2^53 - 1`, rejecting overflow DoS).
   - Trigger rollback detection whenever `(> peer-high-seq local-tx-seq)` **OR** `(:replay-rejected? ack)` is `true` **OR** `(:accepted-ciphertext-sha256 ack)` mismatches the locally stored outbox ciphertext digest for `rejected-seq` — closing the 1-message and burst (`local_tx_seq == peer_high_seq`) race completely.
   - **Order of operations:** Mark all colliding outbox entries (`seq <= peer-high-seq` or matching `rejected-seq`) as `:rollback-collision` and emit counted `:session-tx-seq-rollback-evicted` **BEFORE** `(acked? s)` purges acknowledged entries.
   - Advance `[:sessions sid :tx-seq]` to `peer-high-seq` strictly as a **nonce-fence**, latch `:tx-tainted? true` / `:rekey-required? true` on the session, increment `[:diagnostics :session-tx-seq-rollback-detected]`, and emit `"session-seq-mismatch"` (with `local_tx_seq`, `peer_high_seq`, `affected_seqs`, `rekey_required: true`).
   - **Fail-closed quarantine + real X25519 rekey:** Block further normal sends under the tainted `:tx-key` (returning error `session-key-tainted-rollback`) until a **real X25519 key-rotating handshake** (`channel-offer`/`rendezvous-offer` — explicitly **NOT** `p2p-offer`, which only renegotiates ICE) completes and installs fresh `(:tx-key, :rx-key)`. In `:autonomous? true` mode, auto-initiate the X25519 rekey immediately; in `:autonomous? false` mode, surface an explicit UI/CLI prompt preserving human consent.
3. **Non-blocking pre-flush persistence barrier:**
   - In `node_main.cljs` (`serve` mode), replace blocking `.fsyncSync` with a single-authority, generation-ordered async write-barrier (`write-temp` + async `fh.sync()` + `rename`) serialized with `save-light-state-async!` before flushing outbound sealed envelopes. In one-shot modes (`run-one-shot!`) and browser `sendMessage`, await state persistence *before* transmitting on the wire.

### 2.5 Browser Parity, Honest Liveness, Bounded Auto-Sync & Encrypted Rolodex
1. **Honest Single-Authority `ctl("status")` & `getStatus()` (Rule 4):**
   - Never persist `:connected?`, `:connection-error`, `:auto-sync-in-flight?`, or `:rolodex-key` to IndexedDB.
   - Wire pure `browser/project-light-state-liveness` into **both** `shell/browser.cljc/exec-browser-cli` **and** `browser_cli/dispatch-browser-cli` + `get-status`.
   - Expose `mesh_open_peers`, `relay_fallback_active`, `connected`, and `transport_healthy` honestly: when `mesh_open_peers == 0` and only HTTPS relay fallback is active, report `relay_fallback_active: true`, **`transport_healthy: false`**, and preserve `:connection-error` so Hive's `DEGRADED (relay)` badge remains strictly truthful.
   - Update `shell/browser.cljc/allowed-persistence-keys` to allowlist all new non-liveness persisted fields (`:rekey-required?`, `:rolodex-seq`, `:rolodex-cid`, `:auto-sync-last-error`, `:subscribed-subnets`, and all new diagnostic counters).
2. **Generation-Tagged Browser `auto-sync`:**
   - Store `:generation`, `:active-pubkey`, `:relay-fallback-healthy?`, and `AbortController` inside the single-authority `node-atom` reducer (Rule 4); clean up all timers on `stopNode` and `apply-hardware-identity!`.
   - Active interval `30,000 ms` (`visible`), throttled `>= 180,000 ms` (`hidden`), immediate superseding catch-up on `hidden -> visible` and `window` `online` events.
   - Bounded per-tick fetch timeout (`< interval`, e.g., `10,000 ms`) with `try/finally` clearing `:auto-sync-in-flight?` and emitting `:diagnose {:reason :auto-sync-timeout}` on timeout, failing over across `ns1..ns3`.
3. **Encrypted On-Network Rolodex (Hardened Spec — or Split to BIP-022 at Stage 2):**
   - **Unify with BIP-016 (`memento/rolodex.cljc`):** Preserve grow-only deletion tombstones and revocation semantics so deleted petnames / unsubscribed subnets never resurrect on merge; surface 3-way conflicts rather than silently overwriting offline edits.
   - **Explicit User Opt-In & Zero Silent Burn:** Gate cloud Rolodex sync (`rolodex-put` / `rolodex-restore`) behind an explicit user toggle (never silent upload on edit, never silent 100 ECH burn, never blocking overwrite on YubiKey unlock).
   - **Dual-Profile Cross-Runtime KDF & AEAD:**
     - Profile `"hkdf-sha256-seed32"`: normalize raw Ed25519 secret to canonical 32-byte seed (`extract-ed25519-seed32`) + lowercase 88-char SPKI `pubkey-hex`.
     - Profile `"webauthn-prf-v1"`: deterministic domain salt `"bitecho/rolodex-prf-v1"` via WebAuthn PRF extension (or explicit recovery passphrase fallback). **Never** attempt raw export on `webcrypto-non-extractable` (`:extractable? false`) keys — fail closed with counted `:diagnose {:reason :rolodex-key-unextractable}` and remove `fallback-kp` synthesis when locked in `exec-artifact-put`.
     - Encrypt with `AES-256-GCM`, **per-snapshot 32-byte CSPRNG HKDF salt + 96-bit CSPRNG nonce** (never deterministic seq nonces), AAD `(schema || normalized-pubkey || be64(seq) || subnet)`, and fixed `4 KiB` ciphertext bucket padding.
   - **Infra Provisioning (`bitecho.org/rolodex`):** Host dedicated network-URI-scoped `bitecho.org/rolodex` (overridable via `--rolodex-subnet` for staging labs) with multi-subnet pointer routing in `full_node_main.cljs`/`shell/http.clj`, `:gate/mode :permissionless`, `:replication/pointers :gossip-crdt`, zero-burn quota (`<= 256 KiB` per pubkey), and strict `:self-path-owner` ACL (`:pointer/authority == path-pubkey == unlocked-pubkey`). Pre-query remote `min_remote_seq` before upload and reject downgrade (`remote-seq < local-seq` unless `--force`).
   - **Companion `alpeware/hive` Parity (Rule 6 Co-Commit):** Update `chat.js` (opt-in Rolodex sync toggle, `#btn-rolodex-restore`, YubiKey unlock restore prompt, `localStorage` `hive_subscribed_subnets` reconciliation, `rekey_required` banner, honest `ctl("status")` fields in diagnostics drawer) and co-update `chat.html` + `trace_viz.clj` SHA-384 `browser-bundle-sri`.

### 2.6 `:443` Passthrough Graceful Drain & Chunked Mesh-First Auto-Updater
1. **`:443` Passthrough Graceful Drain (`src/bitecho/core/infra.cljc` & `full_node_main.cljs`):**
   - Separate read-side `end` (half-close FIN) from full `close`; configure `allowHalfOpen: true` on passthrough sockets where needed so client read-FIN does not abort in-flight responses.
   - On clean `peer-fin? true` (no `:had-error?`): transition to `:draining`, emit `{:effect :end-socket :side peer}` and `{:effect :schedule-drain-timeout :pair-id id :side peer :timeout-ms 5000 :generation gen}`. Reset drain inactivity timer on active write-flush progress up to a hard cap (`30,000 ms`) so multi-MiB transfers over slow links are never truncated, while idle stalled peers are force-closed at `5,000 ms`.
   - On `:closed` or `:had-error?`: emit explicit **`{:effect :cancel-drain-timeout :pair-id id :generation gen}`**, explicitly unpipe sockets, strip listeners and peeking chunk buffers, and release `TCPSocketWrap` references immediately.
   - On `:drain-timeout`: emit counted Rule 5 `:diagnose {:reason :passthrough-drain-timeout}` before `{:effect :destroy-socket}`.
   - Enforce a global bounded `max-draining-pairs` cap (e.g., `64` on `940 MiB` `e2-micro` nodes) with counted `:passthrough-drain-cap-evicted` diagnostics, and expose active `TCPSocketWrap`, timer, `/proc/self/fd`, and active/draining passthrough pair counters in `GET /statusz`.
2. **Pure Sans-IO Chunked Mesh-First Auto-Updater (`deploy/core.cljc`, `node_main.cljs`, `full_node_main.cljs`, `shell/updater.clj`, `browser_main.cljs`):**
   - Move the two-phase waterfall (`:fetching-mesh -> :fetching-https -> :staging -> :watching`) into pure `deploy/core.cljc` (Rules 2 & 4).
   - **Guard `:poll-tick` / `:pointer-seen` when `(:phase state)` is `:fetching-mesh`, `:fetching-https`, `:staging`, or `:health-checking`** (buffering latest verified pointer or emitting counted `:diagnose {:reason :update-poll-suppressed}`) — eliminating generation-bump self-abort livelock.
   - Dispatch `:blob-fetch-failed` back into `reduce-light-update-tick` on errors so `:phase` never wedges; await `.writeFile` completion before `:atomic-swap-and-restart-light-node`; fix `:restore-lkg-light-bundle` to actually restore `.lkg` and persist failed-CID quarantine.
   - **Fail-closed trust:** Make `verify-config-pointer` fail **closed** when `:trusted-deploy-authorities` is empty, and wire trusted authorities in `node_main.cljs` / `full_node_main.cljs`.
   - **BIP-017a Chunked Mesh Protocol:** Define light-node mesh `artifact-get-chunk` RPC (`<= 192–256 KiB` chunks respecting WebRTC SCTP `262,144 B` cap, streaming directly to disk without 2x/4x hex RAM amplification) with two-tier timeout: `2,500–3,000 ms` initial probe/TTFB, `5,000 ms` per-chunk stall timeout, and size-scaled total ceiling `clamp(15,000 ms, 120,000 ms, 5,000 + ceil(size_MiB) * 3,000)`, falling back per-chunk or full-blob to multi-relay HTTPS (`ns1..ns3`) with counted `auto-update-mesh-fallback` and distinct Byzantine `:mesh-cid-mismatch` diagnostics.

---

## 3. Hardened Stage 4 Staging Soak & Chrome Raw-CDP E2E Gates

**Declared Soak Class:** **Staging-First** (`bitecho-gentoo-lab`, per `RELEASE.md` §3). All 6 gates are **fail-closed**:

| Gate | Hardened Pass Criterion (All Required — Fail-Closed) |
| :--- | :--- |
| **1. `P-MEM-1` Extended Staging Soak (`bitecho-gentoo-lab`)** | Continuous **`>= 120 minutes`** (after 10-min warm-up) under mixed `:443` passthrough churn (clean `peer-fin?` half-closes, backpressured slow-reader TLS clients, stalled slowloris peers, HTTP `CONNECT`, DTLS ingress) + mesh load. OLS linear regression across **`>= 100` `offheap-gc`-synchronized post-GC `/statusz` samples**: `arrayBuffers <= +0.5 MiB/hr`, `external <= +0.5 MiB/hr`, `rss <= +2.0 MiB/hr` (peak `<= 200 MiB`), and **`0`** leaked `TCPSocketWrap` handles / open FDs / dangling drain timers. |
| **2. `:443` Passthrough Half-Close & Slow-Reader E2E** | `>= 1,000` TLS `:443` passthrough connections exercising backpressured `peer-fin? true` half-closes + multi-MiB slow-draining reads: **`0`** premature TCP `RST` / `ECONNRESET` / `close_notify` truncations; **`100%`** idle stalled peers reclaimed within `5,000 ms ± 500 ms` with counted `:passthrough-drain-timeout` and `:cancel-drain-timeout` verified. |
| **3. Cluster Quorum & Artifact Parity (`P-FAIL-1`)** | `6/6` staging validators online, **`0`** `finalized_epoch` stalls (`F-EPOCH-1`), **`3/3`** HTTP 200 + `SHA-256 == CID` parity across `ns1..ns3` (including `bitecho.org/rolodex` pointers if §2.5.3 is in scope). |
| **4. Light-Node Authority, Admission, DAC & Rollback E2E (Node.js + JVM)** | (a) Both bare restart (preserving persisted `:subnet-id`, `reconciled = 0`) and explicit `--subnet-id` override (`reconciled = 1`), plus cross-network-URI switch failing closed. (b) Direct envelope + session reply on subscribed subnet admitted (`subnet_mismatch_dropped = 0`); unsubscribed + wildcard/outer-inner mismatch rejected loudly. (c) Validly signed broadcast missing DAC → `broadcast_dac_missing = 1`, `broadcast_sig_invalid = 0`; malformed non-map `wire-dac` → `broadcast_dac_invalid = 1`. (d) Simulated VM-restore rollback covering **1-message (`19->18`), multi-message burst, and `>= 64` `:stale-seq`** rollbacks: receiver rejects replays, emits signed rollback `session-ack`, sender quarantines tainted `:tx-key`, evicts colliding outbox entries before purge, completes real X25519 rekey (verified new `:tx-key` SHA-256), and delivers post-rekey messages cleanly. |
| **5. Chunked Mesh-First Auto-Update E2E (Node.js Light + Full + JVM)** | Using **real production-sized bundles** (`>= 1.7 MiB` `.cjs`, `>= 10 MiB` `.jar`): (a) Connected mesh hit fetches via BIP-017a chunked mesh `artifact-get` with `0` HTTPS blob requests, `SHA-256 == CID`, and `0` poll-tick generation livelocks (`--auto-update-interval-ms 10000`). (b) Simulated mesh miss / probe timeout (`<= 3s`) emits `auto-update-mesh-fallback` and succeeds via HTTPS. (c) Corrupted/failing candidate triggers real `.lkg` restore + failed-CID quarantine with `0` boot-loops. |
| **6. Chrome Raw-CDP Headless Browser E2E (Built Static `/app/`, COOP/COEP/SRI)** | Run against `build-web-app!` static `/app/` (`0` CSP/SRI/console errors): (a) Explicit `subnetId` override reconciles stale IndexedDB `"global"` (`subnet_id_reconciled = 1`), while page default preserves user-selected subnet. (b) `ctl("status")` & `getStatus()` report `connected: true, transport_healthy: true` on live WebRTC mesh and `relay_fallback_active: true, transport_healthy: false` (matching Hive `DEGRADED (relay)`) when mesh peers = `0`. (c) Background `auto-sync` advances `finalized_epoch` across `>= 2` intervals (`<= 75s`, `epoch_skew_dropped = 0`), throttles on real CDP tab-visibility `hidden`, and recovers cleanly on `visible` / `online`. (d) (If §2.5.3 in scope) Using CDP `WebAuthn.addVirtualAuthenticator` + opt-in `rolodex-put` → `Storage.clearDataForOrigin` + reload → YubiKey unlock `rolodex-restore`: `100%` petnames/tombstones/subscribed-subnets recovered, `SHA-256 == CID`, `0` plaintext petnames in blob bytes, and `webcrypto-non-extractable` fails closed with counted `:diagnose`. |

---

## 4. Rule 6 Co-Committed Inventory Checklist
Before Stage 3 PR merge, co-commit:
- `docs/SPEC.md`, `docs/memento-continuity-spec.md`, `skill/SKILL.md` (new `--force`, `--rolodex-subnet`, `--reset-network-state` flags and new `ctl status` diagnostic counters).
- `cli/known-command-flags` + automated `cli_flag_inventory_test.clj`.
- `scripts/smoke_light_node_live.sh` populated-state checks.
- Companion `alpeware/hive` commit (`chat.js`, `chat.html`, `trace_viz.clj` updated `browser-bundle-sri`, `trace_viz_test.clj`).
