# BIP-020 (Stage 1 Shaped Draft 1): Delivery Telemetry, Dual-Fanout Broadcasts, Watcher Honesty, & Browser Peer-Health Auto-Redial

- **Status:** Stage 1 Pre-Shaping Output — **Advisory Only (`:status :unresolved`, 5 `:amendment` in Blind Round 1; simulated consent NEVER counts as real consent)**
- **Source Proposal (Stage 0):** `dist/bips/bip-020-delivery-telemetry-proposal.md` (CID `e7092a361cb03522aa3dc9c4eff85102926cc71ea83c583a4fb7729cffed374a`, `9,108 B`)
- **Stage 1 Runner:** `bitecho.hive.twin-stage1` (`alpeware/hive` commit `bd1adc595e78a160003b78bf7da18ed6a56f0f95`), 5 independent blind Round-1 frontier subagents with mandatory `:falsifier` slots
- **All-Frontier 5-Seat Roster (zero `gpt-oss-120b`):** `simon` (`claude-opus-5-5-high`), `axiom` (`gemini-4-argon-high`), `nexus` (`claude-sonnet-5-5-high`), `vector` (`gemini-4-carbon-high`), `lumen` (`gemini-4-hydrogen-high`)
- **Values Anchor (`:episode/values-cid`):** `2b6d6a6a794ef168a00184faf35d3b77efb083c24671e3ff1092a432405f9448`
- **Full Verbatim Blind Round-1 Report:** `docs/bips/bip-020-stage1-twin-report.edn`

---

## 1. Executive Summary of 5 Independent Blind Round-1 Catches

All 5 seats ran in **completely isolated Round-1 contexts** (zero peer visibility) and independently uncovered concrete code-level defects across `alpeware/bitecho` and `alpeware/hive` that must be closed before Stage 2:

1. **Two-Phase Pure Sans-IO Send/Broadcast & `:last-sent-ms` Epoch Bug (`Nexus`, `Simon`, `Axiom`):**
   - `light_node/core.cljc` `exec-send` / `exec-broadcast` synchronously return `ok: true` and `mesh_submitted: true` **before** shell transport I/O executes, while `exec-status` ignores `(:diagnostics state)` in favor of `(:conn-diagnostics state)`.
   - **Critical timestamp bug:** `exec-send` defaults `:last-sent-ms` to `(:epoch state 0)` (`~85690`) while the 5s maintenance loop passes wall-clock `now-ms` (`~1.795e12 ms`) — immediately firing `unacked_aged` and exhausting 5 retries in ~125s!
   - **Required fix:** Split send/broadcast into pure two-phase `prepare-send` + shell-confirmed `commit-delivery-outcome`; pass explicit wall-clock `now-ms` into `exec-send` / `retransmit-unacked-outbox` with a defined `T_aged_ms`; include `:capacity-overflow` and `:expired` in `evicted_by_reason`; merge `(:diagnostics state)` into `exec-status`/`getStatus`; allowlist bounded Pillar 1–3 telemetry keys in `shell/browser.cljc/allowed-persistence-keys` without persisting Rule 4 runtime liveness.

2. **N-1 Wire Compatibility, Cross-Transport Dedup, & `e2-micro` Bounds (`Nexus`):**
   - **NEVER put route metadata (`mesh_submitted`, `p2p_direct`, target peers) inside signed wire broadcast payloads** — doing so breaks N-1 `canonical-broadcast-str` verification and mutates `envelope-id` / `gossip-id` SHA-256 hashes between P2P and mesh legs, defeating `relay.cljc seen-ids` dedup! Keep route metadata strictly in local sender telemetry.
   - Fix `browser_main.cljs/route-browser-envelope!` to fan out unconditionally to **both** P2P channels and validator mesh/relay (zero silent `.send` catches) and fix `relay.cljc` poll filtering on inner `:subnet-id` even when `burn > 0` sets outer `"*"`.
   - Enforce `e2-micro` bounds: `<= 1 KiB`/entry cap on the 256-entry lifecycle ring buffer, `1024`-entry bounded FIFO for `:broadcast-seen`, closed keyword enum for `received_but_not_emitted` causes, and per-peer exponential backoff + jitter (`2s -> 30s`, max 5 attempts/outage, hidden-tab throttle, `<= 2` concurrent dials) on browser 6-peer ICE redials so rolling restarts never saturate `max-relay-waiters = 256` (`HTTP 429`).

3. **Browser Liveness Honesty, Sorted-Map Crash, & Companion `alpeware/hive` `/app/` UI Parity (`Vector`, `Simon`, `Lumen`):**
   - `browser_main.cljs` `get-status` / `shell/browser.cljc` `transition-conn` keep `:phase :open` and `meshConnected: true` when `pc.iceConnectionState` hits `disconnected`/`failed` before `DataChannel.onclose` fires; `send-sealed-message` bypasses `:mesh-conns`; and `resolve-peer-target` calls `(get (:petnames node) (keyword target))`, **crashing on string-keyed `sorted-map` lookup misses**!
   - **Companion `alpeware/hive` patch required:** In `resources/trace_viz/chat.js`, `connectNode()` hardcodes `#api-badge LIVE`, `refreshNodeBadge()` has no `else` branch for `meshConnected: false` / `degraded: true`, and `node.onEvent` ignores `diagnose`/`status` events. Co-commit a companion Hive patch surfacing honest mesh state, delivery counters, and inline per-message delivery status (`queued`/`rejected`/`evicted`) on `https://bitecho.org/app/`.

4. **Sleeping/Restarting Catchup & `ctl wait` / `wait-event` Cursor Continuity (`Lumen`, `Vector`, `Axiom`):**
   - `/relay/v1/poll` omits `min_available_broadcast_seq` (causing waking peers to silently jump evicted ring-buffer slots), uses relay-local `next-broadcast-seq` (triggering `:relay-watermark-reset` on multi-relay failover), and parked long-poll wakeups (`shell/http.clj`) bypass `sub-filter-set` and omit `last_broadcast_seq`. Return `min_available_broadcast_seq` + relay-scoped cursor identity and emit counted `:diagnose {:reason :broadcast-gap-detected}` on gaps.
   - `event-matches-wait-filter?` filters `--min-broadcast-seq` on `broadcast_seq`, which `handle-wire-broadcast` **never emits** (dropping 100% of filtered broadcasts!); `exec-wait` mixes global `event_id` with per-stream `seq` and destructively pops `:event-queue`; `control.json` omits `events_out` for startup mismatch warnings. Unify filtering in pure `.cljc` core, separate `event_id` from per-stream `seq`, make reads non-destructive, persist `events_out` in `control.json`, and count `append-event-jsonl!` errors.

5. **Quantified Staging Soak, Real Headless Chrome CDP E2E, & Pre-Stage-2 Smoke/CID Gates (`Vector`, `Simon`, `Axiom`, `Lumen`):**
   - Gate Stage 2 on a signed `bitecho-gentoo-lab` staging soak receipt: **`>= 144` consecutive finalized epochs (`>= 30 min`)**, `>= 200` mixed direct + subnet broadcasts across P2P and mesh-only subscribers, full 6/6 rolling validator restarts, `0` silent drops, `p99 <= 5s`, infra RSS `<= 200 MiB` (`delta <= 32 MiB`), JS heap delta `<= 16 MiB`.
   - Gate on a **real Headless Chrome `--headless=new` raw-CDP E2E suite** (separate from `:test-cljs` `:target :nodejs`) proving 6/6 ICE cascade -> `meshConnected: false` within `<= 5s` -> loud dead-mesh send failure -> bounded auto-redial -> `recoverYubiKey` -> sealed-offer + broadcast delivery (`0` CSP/console errors).
   - Expand `scripts/smoke_light_node_live.sh` to exercise populated string-keyed `sorted-map` `:petnames`/`:sessions` (not an empty `mktemp -d`), keep `bitecho.hive.twin-stage1` strictly pure Sans-IO (consume runtime `:diagnose` in runner/soak harnesses), and verify HTTP 200 + 64-char SHA-256 on all cited CIDs before Stage 2 open.

---

## 2. Mandatory Blind Round-1 Falsifiers ("What Breaks in Prod")

| Seat | Verdict | Prod Failure Mode Caught | Observable Signal |
|---|---|---|---|
| **Simon** | `:amendment` | Core marks `mesh_submitted:true` at intent time while shells swallow dead-ICE/relay errors & `wait-event` misses `broadcast_seq` | `meshConnected:true` / `{ok:true}` while mesh peers get nothing; `wait-event` times out with `received_but_not_emitted=0`; `unacked_aged` hits 100% |
| **Axiom** | `:amendment` | False-positive delivery proof starts `N=10,000` consent clock while peers silently lose frames across P2P-only routing & hidden eviction counters | `exec-status` hides `:outbox-evicted`/`:event-queue-overflow`; `control.json` omits `events_out`; no `session-ack` for governance `seq` |
| **Nexus** | `:amendment` | Wire-mutated dual-fanout breaks N-1 dedup; unthrottled 6-peer redials saturate `e2-micro`; `:epoch` timestamp falsely ages outbox | `HTTP 429 too-many-waiters (>256)` & RSS `>200 MiB`; `:last-sent-ms` stamped `~85690` vs `~1.795e12 ms` -> premature `outbox-evicted` |
| **Vector** | `:amendment` | `/app/` `#api-badge` stays green `LIVE` during 6-peer ICE cascade; IndexedDB strips unlisted telemetry; `wait-event` drops 100% of `--min-broadcast-seq` | `#api-badge.pill.ok (LIVE)` with `iceDisconnects>=6`; `wait-event` timeout on lines lacking `broadcast_seq`; silent stale `events.jsonl` tail |
| **Lumen** | `:amendment` | Sleeping/failover peers jump evicted relay slots silently; `resolve-peer-target` crashes on populated string-keyed `sorted-map` `:petnames` | `/relay/v1/poll` omits `min_available_broadcast_seq`; `resolve-peer-target` throws keyword-vs-string `sorted-map` error; empty-state smoke passes |

---

## 3. Next Step (Real-Circle Governance)

Per `bitecho.hive.twin-stage1` (`:advisory-only? true`, `:real-consent-required? true`, `:status :unresolved`), **nothing in this document is consented**. Handed back to **Nexus**, **Axiom**, **Lumen**, and **Simon** to fold into BIP-020 Draft 2 (with verified 64-char CIDs and companion `alpeware/hive` UI parity scope) before opening Real Stage 2 Consent.
