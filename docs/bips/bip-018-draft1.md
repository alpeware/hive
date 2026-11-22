# BIP-018 (Shaped Draft 1): Fleet Hardening, Disk-Backed Artifact Streaming, Multi-Relay Failover, & Six-Nines Public Incident Transparency

- **Status:** Shaped Draft 1 — Ready for Real Stage 1 Circle Consent (Advisory Digital Twin Circle Complete; `:advisory-only? true`, `:real-consent-required? true` — simulated consent NEVER counts as real consent; zero writes to `main` before real Stage 2 consent)
- **Authors:** Nexus (drafter), shaped by 5-seat All-Frontier Advisory Digital Twin Circle (`Simon`, `Axiom`, `Nexus`, `Vector`, `Lumen`)
- **Triggering Incident:** `INC-2026-11-22-ns1` (`2026-11-22 11:09–12:43 UTC` / `05:09–06:43 CST`)
- **Target Release:** `v0.23.21` (`D45`)
- **Governance:** Strict adherence to `AGENTS.md` Rules 1–6 (Strict TDD `test.check` first, Pure Sans-IO core, `clojure -M:format && clojure -M:lint` 0 errors / 0 warnings, Single-Authority generation-tagged lifecycle state, Zero Silent Failures with counted `:diagnose`, Co-committed spec/docs/ops/flag inventory).
- **Anchors:**
  - BIP-018 Draft 0: `CID 59a1f3a4d56c949537494eb5db37af880ad11e59c5d0c63b844c1fc142517d4b`
  - Companion Postmortem #1 (`INC-2026-11-22-ns1` Draft 0): `CID 6180bc94f6eedec3d1ac52c5e4a52f55fe89b5baefeccf203941119ecb15cc68`
  - 5-Seat All-Frontier Digital Twin Circle Report (`docs/bips/bip-018-twin-circle-report.edn`): `SHA-256 c8b92dfc08bc4141b412084df4e630dd01e0e4269487ce06febe56064b860b67`
  - Verified Trace v2 RFC 6962 Merkle Root: `409fd6132872a8c3c597497c7b3c6ecb53edfd678132cf7bb6ffdf61644ae0c9` (`43` turns, `2` rounds, `:fixed-point-2`, Top-Loop fail-closed gate `:sim-promotion-gate-fail-closed? true`)
  - Genesis Monte Carlo Continuity Forecast Root: `f6d67c0507eda2d7baa13903d4b162e742466bc56be9d0b274c7b5dc797cd7c1`

---

## 0. Advisory Twin-Circle Summary & All-Frontier Model Rotation

Per **Simon's directive (2026-11-22, Axiom `seq: 584` / Nexus `seq: 507`)**, the 5-seat Advisory Digital Twin Circle rotated to an **all-frontier** model roster, **retiring `gpt-oss-120b-medium`** from `bitecho.hive.env/default-model-pool` and `canonical-five-seats`:

| Seat | Role | All-Frontier Model Selector | Prompt CID (prefix) |
|---|---|---|---|
| **Simon** | Human Anchor | `claude-opus-5-5-high` | `ff26581a...` |
| **Axiom** | Architect / Auditor | `gemini-4-argon-high` *(replaced `gpt-oss-120b-medium`)* | `eeebba49...` |
| **Nexus** | Builder / DevOps Lead | `claude-sonnet-5-5-high` | `c8d08532...` |
| **Vector** | Soak / Runtime / Edge | `gemini-4-carbon-high` | `518e17ed...` |
| **Lumen** | Governance Architect | `gemini-4-hydrogen-high` | `a954c14b...` |

### Summary of Resolved Open Questions (Q1–Q4) & Edge-Case Catches
1. **Q1 Resolved (Vector):** Separate a **pinned `<= 16 MiB` web-app bundle ceiling** (active + LKG `/app/` release assets) from the **`32 MiB` chunk hot-LRU** so chunk storms never evict `/app/` assets, while `P-MEM-1` bounds total process RSS `<= 200 MiB`.
2. **Q2 Resolved (Nexus):** Deploy a lightweight local OpenRC/cron **`bitecho-watchdog`** probing `http://127.0.0.1/statusz` every `60s` (`3` consecutive failures -> restart `bitecho-infra` with exponential backoff/cooldown `60s -> 180s -> 600s` + local incident marker) plus **`OOMScoreAdjust`** (`sshd` protected at `-500`, `bitecho-infra` restartable at `200`).
3. **Q3 Resolved (Nexus):** Protect `GET /status` from amplification via a **pre-rendered `~2 KiB` in-memory snapshot refreshed every `5s`** (`Cache-Control: public, max-age=5, stale-while-revalidate=10`, zero per-request disk I/O or EDN serialization) + **`60 req/min/IP` rate limit** (`HTTP 429` + `Retry-After: 5` + counted `:diagnose` `{:reason :status-rate-limited}`).
4. **Q4 Resolved (Simon / Axiom):** Report **all three SLOs side-by-side** in `INC-2026-11-22-ns1` and Track 4: **Consensus/Finality SLO** (`100%`, `0 ms`), **Edge Fleet SLO** (`2/3` healthy = degraded, `ns1` down `88.6 min`), and **Single-Relay Client/Telemetry SLO** (honest `45.6 s` breach -> six-nines NOT met, directly motivating Track 3).
5. **Edge Case #1 (Vector Paramount Objection):** Strip raw `Uint8Array` bytes from `artifact-replicated` (and `put`/`get`) stdout/stderr logs in **BOTH `bitecho-full-node.cjs` AND `bitecho-light-node.cjs`** (Vector's light node hit `control-request-timeout` during the 105× chunk storm from the same raw-byte stdout serialization).
6. **Edge Case #2 (Axiom Paramount Objection):** `bitecho.net.relay-pool` MUST handle **split-brain / stale-relay** responses: any relay returning `HTTP 200 OK` with a finalized epoch **`> 6` epochs behind healthy peers** is demoted into cooldown identically to a `5xx`, emitting counted `:diagnose` `{:reason :relay-stale-epoch}`.
7. **Edge Case #3 (Nexus Paramount Objection):** Any `sshd` hardening rollout script MUST run `sshd -t` AND verify an **active key-only SSH login (`ssh -o BatchMode=yes -o PasswordAuthentication=no`) succeeds BEFORE reloading `sshd`**, preventing operator lockout on `e2-micro`.
8. **Edge Case #4 (Axiom Paramount Objection):** Canonical `INC-2026-11-22-ns1.edn` MUST conform to a machine-verifiable `clojure.spec.alpha/defspec` schema (`:bitecho/incident-postmortem-v1`) linking exact `sla/latest.edn` and telemetry digest CIDs.

---

## 1. Motivation & Context

On `2026-11-22` at `11:09:25 UTC` (`05:09:25 CST`), `bitecho-ns1` (`e2-micro`, `940 MiB` RAM) wedged and stopped responding on both HTTPS (`443`) and SSH (`22`). Meanwhile, the remaining 5/6 fleet nodes (`node1`, `node2`, `node3`, `ns2`, `ns3`) continued finalizing blocks uninterrupted through epoch `80929+` (`0 ms` consensus/finality downtime).

Despite zero consensus outage, Axiom's light node—pinned solely to `--relay-url https://ns1.bitecho.org`—experienced ~95 minutes of failed auto-sync ticks (`client network socket disconnected before secure TLS connection was established`) and interpreted the relay wedge as a chain stall until cluster telemetry separated the two (`cluster finalized 80941` vs `Axiom 79891`).

Post-recovery inspection revealed three compounding technical causes plus an operational transparency gap:
1. **Host SSH brute-force thrashing:** `bitecho-ns1`'s serial console showed hundreds of `unix_chkpwd` root password brute-force forks on a 1 GB `e2-micro` (`PasswordAuthentication` and `KbdInteractiveAuthentication` were not explicitly disabled).
2. **Infra RSS growth (`181 MiB -> 543 MiB`) & dual-node raw-byte stdout dumps:** In `src/bitecho/core/data.cljc`, `default-max-hot-bytes` is `128 MiB` of external Node.js `Uint8Array` buffers outside V8's `--max-old-space-size=384` heap, and `artifact-replicated` stdout logging serialized raw byte arrays in both `bitecho-full-node.cjs` and `bitecho-light-node.cjs`. Following `v0.23.20` + 105× 512 KiB Moonshine/ORT chunk uploads, `ns2`/`ns3` RSS reached `543 MiB` (`56.4%` of host RAM), while Vector's light node hit `control-request-timeout`.
3. **Single-relay SPOF & conflated blast radius:** Light-node auto-sync, CLI `put`/`get`, and browser `BitechoLightNode` pinned a single relay (`ns1`) without automatic failover, stale-epoch detection, or mechanical separation of `"my relay is down"` vs `"chain stalled"`.
4. **Incident & customer transparency gap:** No standardized SEV rubric, live public `/status` endpoint, detection-lag SLO, or canonical machine-verifiable public postmortem pipeline benchmarked against six-nines industry leaders (Google SRE, Cloudflare, AWS, Stripe).

---

## 2. Four-Track Technical & Process Specification (Shaped Draft 1)

### Track 1: Host & Fleet SSH/OOM Hardening & Local Watchdog (Ops + Provisioning)
1. **SSH Hardening across all 6 nodes (`ns1..ns3`, `node1..node3`) and Gentoo image scripts:**
   - Explicitly enforce in `/etc/ssh/sshd_config`:
     - `PasswordAuthentication no`
     - `KbdInteractiveAuthentication no`
     - `ChallengeResponseAuthentication no`
     - `PermitRootLogin prohibit-password`
     - `MaxAuthTries 3`
     - `MaxStartups 10:30:60`
   - **Anti-Lockout Rollout Precondition Gate (Edge Case #3, Rule 6):** Every ops rollout script modifying `sshd_config` MUST:
     1. Validate syntax with `sshd -t` (abort loudly on non-zero exit).
     2. Verify an **active key-only SSH login succeeds** (`ssh -o BatchMode=yes -o PasswordAuthentication=no -o ConnectTimeout=5 <host> true`) **BEFORE** reloading `sshd`.
     3. Verify a second post-reload key-only SSH probe succeeds before advancing to the next node (`1` node at a time, holding `4/6` quorum and `2/3` edge availability).
2. **Local `e2-micro` Watchdog & OOM Priority Guardrails (Q2 Resolved):**
   - **Local `bitecho-watchdog` (OpenRC/cron):** Probe `http://127.0.0.1/statusz` every `60s`. After **`3` consecutive failures** (`180s`), restart `bitecho-infra` with exponential backoff/cooldown (`60s -> 180s -> 600s`, capped at `3` restarts/hour to avoid crash loops), emit a counted `:diagnose` `{:reason :watchdog-restart}` event, and write a local incident marker (`/var/lib/bitecho/watchdog-incident.edn`) with UTC/CST timestamp and RSS snapshot—never relying solely on manual `gcloud instances reset`.
   - **Kernel & Process OOM Protection:** Configure `vm.oom_kill_allocating_task=0`, `vm.panic_on_oom=0`, OpenRC `supervise-daemon`, and explicit OOM scores:
     - `sshd`: `OOMScoreAdjust=-500` (protected so operator SSH access survives memory pressure).
     - `bitecho-infra`: `OOMScoreAdjust=200` (cleanly reclaimable and auto-restarted by OpenRC/watchdog if ever pressured).

### Track 2: Disk-Backed Bounded-LRU Artifact Storage & Dual-Node Zero-Byte-Dump Logging
1. **Separated Memory Ceilings: Pinned `<= 16 MiB` Web-App Bundle + `32 MiB` Chunk Hot-LRU (Q1 Resolved):**
   - Make `max-hot-bytes` configurable in `config.edn` / `bitecho.core.data/init-blob-store` with an `e2-micro` infra default of **`32 MiB`** (`33,554,432` bytes), down from `128 MiB`.
   - Maintain a **separate pinned web-app bundle ceiling of `<= 16 MiB`** (`16,777,216` bytes) for active + LKG `/app/` release `:active-blobs` so 150× 512 KiB chunk storms can **never** evict `/app/` assets. Exceeding the `16 MiB` web-app pin ceiling fails closed during release validation with counted `:diagnose` `{:reason :app-bundle-pin-limit-exceeded}`.
   - Serve cold/evicted chunks seamlessly via atomic disk read-through (`read-disk-blob!` with atomic temp+rename writes on `write-disk-blob!`), emitting counted `:diagnose` `{:reason :blob-disk-read-through}` metrics without re-pinning large chunks indefinitely in RAM.
2. **Dual-Node Zero Raw Byte-Array Logging (Edge Case #1):**
   - Strip raw `Uint8Array` / byte arrays from `artifact-replicated`, `put`, and `get` stdout/stderr JSON logs in **BOTH `bitecho-full-node.cjs` AND `bitecho-light-node.cjs`**.
   - Emit metadata only: `{:cid ..., :size_bytes ..., :media_type ...}`. Verify via automated unit/property tests that serialized log lines never contain raw byte arrays and remain bounded (`<= 4 KiB` per event).
3. **Soak Gate `P-MEM-1`:**
   - Upload and fetch `150 × 512 KiB` chunks (`~75 MiB` total) through `ns1..ns3` while concurrently serving `/app/` release assets; assert steady-state infra RSS remains **`<= 200 MiB`** (delta `<= 32 MiB` above baseline), `0` `/app/` evictions, `0` light-node `control-request-timeout` errors, and `0` OOM/dropped envelopes.

### Track 3: Pure Sans-IO Multi-Relay Failover, Split-Brain Stale-Relay Guard & Blast-Radius Separation
1. **Pure Generation-Tagged Relay Health Reducer (`bitecho.net.relay-pool`, `.cljc`, Rules 2, 4, 5):**
   - Single-authority generation-tagged map updated by pure `(transition pool event) -> [new-pool effects]`:
     - Ordered candidate list from bootstrap/config (never hardcoding `ns1` as sole primary).
     - Per-relay EWMA RTT, consecutive failure count, observed `:finalized-epoch`, and exponential cooldown (`5s -> 15s -> 60s`) on `5xx` / timeout / TLS / network error.
     - **Split-Brain / Stale-Relay Demotion (Edge Case #2):** Track `:peer-max-finalized-epoch` across healthy relays. Any relay returning `HTTP 200 OK` whose reported finalized epoch lags **`> 6` epochs** behind `:peer-max-finalized-epoch` is demoted into exponential cooldown **identically to a `5xx`**, emitting counted `:diagnose` `{:reason :relay-stale-epoch :relay ... :relay-epoch ... :peer-max-epoch ... :lag-epochs ... :generation ...}`.
     - Stale-generation callbacks (`gen < current`) are ignored with counted `:diagnose` `{:reason :ignored-stale-relay-callback}`.
     - Every failover and cooldown recovery emits counted `:diagnose` (`{:reason :relay-failover :from :to :chunk-idx :status :generation}` / `{:reason :relay-recovered}`)—zero silent failovers.
2. **Client Integration (JVM Light Node, Node.js Light Node, Browser `BitechoLightNode`):**
   - **Auto-sync & CLI:** Accept `--relay-urls` (comma-separated list, defaulting to all bootstrap relays `ns1,ns2,ns3`) plus optional `--quorum <n>` (e.g., `--quorum 2`) on `artifact-put` so uploads do not hinge on `ns1` alone. Co-commit `--relay-urls` and `--quorum` into `cli/known-command-flags`, `skill/SKILL.md`, and live-parser flag-inventory tests (Rule 6).
   - **Browser `getChunkedArtifact`:** Retry *individual failed chunks* against the next healthy relay (`ns2`/`ns3`) without restarting the whole manifest—SHA-256 chunk verification makes mixed-relay reassembly 100% trustless.
   - **Mechanical Stall vs. Relay-Down Self-Check:** Before any light node emits a loud `"chain stalled"` alert, it MUST query remaining healthy relays and `/status` (or `/sla/latest.edn`). Output explicitly classifies:
     - `:relay-degraded` (local relay unreachable or stale `> 6` epochs, while cluster finalized epoch advances on healthy peers), OR
     - `:chain-stalled` (cluster finalized epoch stalled across quorum relays).
3. **Hive `/app/` Status UX (Vector track):**
   - Compact **Relay Health Pill** in header (`Relays: 3/3` green, `2/3` amber, `0/3` red) with per-relay RTT, finalized epoch, and failover count tooltip.
   - Live chunk progress + active relay during BIP-017a fetches (e.g., `Moonshine 18/42 via ns2 · 1 failover`).
4. **Soak Gate `P-FAIL-1`:**
   - Kill/block `ns1` (and separately inject a stale `HTTP 200` relay lagging `> 6` epochs) mid-`getChunkedArtifact` and mid-auto-sync; assert `100%` chunk completion and uninterrupted auto-sync via `ns2`/`ns3` with counted `:relay-failover` and `:relay-stale-epoch` diagnostics.

### Track 4: Six-Nines Public Incident Response, Amplification-Proof `/status`, & Postmortem Transparency Standard
Benchmarked against six-nines / high-reliability industry leaders (**Google SRE Workbook**, **Cloudflare Postmortems**, **AWS Service Health Dashboard**, **Stripe Status**):

| Practice | Industry 6-Nines Standard | Bitecho Standard (BIP-018 Shaped Draft 1) |
|---|---|---|
| **Live Customer Status (Q3 Resolved)** | Public real-time status page updated during incidents (Stripe/Cloudflare/AWS) | Public `GET /status` (JSON + HTML) on `ns1..ns3` served from a **pre-rendered `~2 KiB` in-memory snapshot refreshed every `5s`** (`Cache-Control: public, max-age=5, stale-while-revalidate=10`, zero per-request disk I/O or EDN serialization) + **`60 req/min/IP` token-bucket rate limit** returning `HTTP 429` with `Retry-After: 5` and counted `:diagnose` `{:reason :status-rate-limited}`. |
| **Severity & Blast Radius** | Strict separation of control-plane/global outage vs. regional/edge degradation | **SEV-1:** Consensus/finality stall across quorum (`0` new finalized epochs `> 5 min`). **SEV-2:** Single relay/edge node down (`2/3` relays healthy, chain finalizing). **SEV-3:** Non-critical client/UX regression. |
| **Detection-Lag SLO** | Automated alerting within minutes (Google SRE MTTD target) | **MTTD `<= 5 minutes`** for SEV-1/SEV-2 via BIP-013 self-heal telemetry + multi-relay health probes (down from `18 min` in `INC-2026-11-22-ns1`). |
| **Attribution Discipline** | Separate observed symptoms from unverified causes during active response | Live alerts MUST tag cause statements as `hypothesis` vs `verified`. Postmortems MUST record wrong initial hypotheses and their falsifiers (blameless learning). |
| **3-Way Side-by-Side SLO Honesty (Q4 Resolved)** | Explicit numerical SLO & error-budget burn accounting (Google SRE) | Every postmortem reports all three SLOs side-by-side without rounding away a miss. For `INC-2026-11-22-ns1`: **(1) Consensus/Finality SLO:** `100%` (`0 ms` finality stall); **(2) Edge Fleet SLO:** `2/3` healthy = degraded (`ns1` down `88.6 min`, `ns2`/`ns3` `100%`); **(3) Single-Relay Client/Telemetry SLO:** honest `45.6 s` breach against `~86 ms` remaining budget -> **six-nines NOT met**, directly motivating Track 3. |
| **Machine-Verifiable Public Postmortem (Edge Case #4)** | Blameless postmortems published transparently within 24–72h (Cloudflare/Google) | Canonical EDN + Markdown published within 24h to `docs/postmortems/INC-YYYY-MM-DD-<slug>.{md,edn}` AND on-network `bitecho.org/infra -> incidents/INC-YYYY-MM-DD-<slug>.{md,edn}` + `incidents/index.edn`. `INC-*.edn` validated against a `clojure.spec.alpha/defspec` schema (`:bitecho/incident-postmortem-v1`) linking exact `sla/latest.edn` CID, telemetry digest CID, dual UTC+CST timestamps, 3-way SLO block, hypotheses/falsifiers, and BIP/test invariant links. |

---

## 3. Canonical `:diagnose` Catalog & Strict TDD Plan (Rules 1–6)

### 3.1 Counted `:diagnose` Effects (Rule 5 — Zero Silent Failures)
Every new boundary fault, rate-limit, eviction, or failover MUST emit a counted `:diagnose` effect surfaced in status diagnostics (`:phase`, `:generation`, `:last-error`, `:dropped`):
- `:blob-disk-read-through` — cold/evicted chunk served from atomic disk store.
- `:app-bundle-pin-limit-exceeded` — `/app/` active + LKG release exceeds `16 MiB` pinned ceiling (fail-closed).
- `:relay-failover` — failover from unhealthy relay to next candidate (`:from`, `:to`, `:chunk-idx`, `:status`, `:generation`).
- `:relay-stale-epoch` — relay returning `200 OK` lagged `> 6` finalized epochs behind healthy peers (`:relay`, `:relay-epoch`, `:peer-max-epoch`, `:lag-epochs`, `:generation`).
- `:relay-recovered` — cooled-down relay re-admitted after probe passes.
- `:ignored-stale-relay-callback` — stale generation (`gen < current`) callback ignored.
- `:watchdog-restart` — local `bitecho-watchdog` restarted `bitecho-infra` after 3 failed `/statusz` probes.
- `:status-rate-limited` — client IP exceeded `60 req/min` on `GET /status` (`429 Retry-After: 5`).

### 3.2 `clojure.test.check` Generative & Model-Based Invariants (Rule 1 — Written First)
1. `prop-relay-pool-failover-cooldown-stale-epoch-and-stale-gen`: Command-sequence model test over `bitecho.net.relay-pool/transition` covering `5xx`, timeout, TLS disconnect, **HTTP `200 OK` stale-relay (`lag > 6` epochs)**, stale-generation callbacks (`gen < current`), cooldown expiry, and recovery; asserts zero silent failovers and no stuck-open circuit after cooldown.
2. `prop-blob-store-32mb-lru-16mb-app-pin-and-disk-readthrough`: Verifies chunk hot-bytes never exceed `32 MiB`, `/app/` `:active-blobs` remain pinned `<= 16 MiB` and are never evicted by chunk storms, and evicted chunks remain retrievable via disk read-through with exact SHA-256 verification.
3. `prop-dual-node-log-sanitization-no-raw-bytes`: Verifies neither `bitecho-full-node.cjs` nor `bitecho-light-node.cjs` serializes raw `Uint8Array` bytes on `artifact-replicated`/`put`/`get` stdout logs.
4. `prop-stall-vs-relay-down-classification`: Verifies mechanical separation between `:relay-degraded` and `:chain-stalled` across all quorum/relay/stale-epoch failure combinations.
5. `prop-status-snapshot-cache-and-rate-limit`: Verifies `GET /status` serves cached `~2 KiB` snapshot within `5s` TTL with zero disk reads per request and enforces `60 req/min/IP` (`429 Retry-After: 5`) with counted `:status-rate-limited`.
6. `prop-postmortem-defspec-schema-and-3way-slo-invariants`: Verifies `INC-2026-11-22-ns1.edn` conforms to `:bitecho/incident-postmortem-v1` `defspec` (dual UTC/CST timestamps, exact `sla/latest.edn` + telemetry digest CIDs, 3-way SLO block, hypothesis/falsifier entries, non-empty BIP/invariant links).

### 3.3 Co-Committed Ops, Flag Inventory, & Soak Gates (Rule 3 & Rule 6)
- `clojure -M:format && clojure -M:lint`: `0` errors, `0` warnings.
- Flag inventory: register `--relay-urls` and `--quorum` in `cli/known-command-flags`, `skill/SKILL.md`, and automated flag-inventory tests.
- Ops scripts: co-commit `sshd_config` hardening + `sshd -t` + live pre-reload key-only SSH login verification + `bitecho-watchdog` + `OOMScoreAdjust` assertions.
- Blocking Soak Gates before Stage 2 Consent: signed receipts for `P-MEM-1` and `P-FAIL-1`.
