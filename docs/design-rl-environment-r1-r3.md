# Design Note: Hive Multi-Agent RL Environment — R-1 Environment API & R-3 Training Trace Schema

**Author:** Vector (Design Lead)
**Status:** Design Note for Team Review (zero implementation started)
**Date:** 2026-11-20
**Target Repositories:** `alpeware/hive`, `alpeware/mealy` (with `bwrap` harness port from `alpeware/bitecho`)

## 0. Provenance & Traceability

| Document | Path / Commit | Full 64-char SHA-256 CID |
| :--- | :--- | :--- |
| Parent Brief | `dist/hive/brief-rl-environment-2026-11-20.md` | `ff91371d24ffbd845d4f8f61a0cb19d45332a661195e52bc85a3af5f0a66024b` |
| Amendment 1 (Governance Digital-Twin Circle) | `dist/hive/brief-rl-environment-amendment-1-2026-11-20.md` | `f6c046af2dcf0553b9299ae1a55fcdcb2aa1a869babc06470abb009a25ea9a35` |
| Amendment 2 (Fixed Point + Read-Only + `bwrap` Port) | `dist/hive/brief-rl-environment-amendment-2-2026-11-20.md` | `38df70cd574e13a7c057c441a5e2a4b6b5c445bd0bfb1c4094b64b6c7b0cf536` |
| Amendment 3 (Implementation Mode) | `dist/hive/brief-rl-environment-amendment-3-2026-11-20.md` | `7347c3cc95bed59e2fcc95edc0c083d307b378495a357466bd92c68682c5330b` |
| Amendment 4 (Closed Loop + `:sim`/`:real` Gate Split) | `dist/hive/brief-rl-environment-amendment-4-2026-11-20.md` | `66865735cdd1623945e37b984fb5899ded4c0c3b7dda24a87c28493553275173` |
| D41 Governance Spec & Integrity | `alpeware/bitecho` @ `73ada39aa34dca12eb1450b792320568b1d15474` (`docs/GOVERNANCE.md`, `src/bitecho/governance/integrity.cljc`) | — |
| Existing H-4 Merkle Trace | `alpeware/hive` (`src/bitecho/hive/trace.clj`) | — |
| Existing `bwrap` Harness | `alpeware/bitecho` (`dev/bitecho/experiments/{sandbox,runner}.clj`) | — |

---

## 1. Architectural Invariants

Every component in `R-1`, `R-2`, `R-3`, and the `mealy` harness port obeys five non-negotiable invariants:

1. **Sans-IO Pure Core (`AGENTS.md` Rule 2):** The environment state machine (`R-1`), governance fixed-point reducer, SCI scorer (`R-2`), Merkle trace builder/verifier (`R-3`), and sandbox policy/argv builder are 100% pure `.cljc` functions. Process execution (`bwrap`), LLM calls (`agy`, `clj-einsum`/WebGPU), and Bitecho transport live strictly in thin boundary shells.
2. **In-Memory / Live-Wire Parity (Hive's Founding Property):** A declarative scenario EDN spec runs identically in-memory (seeded deterministic tests & chaos sims), over the live Bitecho devnet (JVM hosts), and in browser CLJS/WebGPU clients with zero semantic divergence.
3. **Hard Isolation of Simulated vs. Real Authority:**
   - `:sim-consent-receipt` (always tagged `:simulation? true :advisory-only true`) gates **only** simulation-internal transitions (e.g., auto-transitioning from Phase 1 `:review` to Phase 2 `:implement` inside a disposable sandbox copy).
   - `:real-consent-receipt` (unanimous real circle, own words, zero `:pending`, verified by `bitecho.governance.integrity/validate-bip-receipt`) remains the **exclusive** gate for landing patches on `main` or deploying to any live cluster.
   - Simulation envelopes carry **zero** capability handles to real `bip-consent` signing keys or live cluster deploy scripts.
4. **Defense-in-Depth Filesystem Isolation:** In `:review` mode, the real codebase is mounted read-only (`bwrap --ro-bind`) AND write tools do not exist in the twin capability envelope. In `:implement` mode, the real tree is **never** mounted writable; agents receive a disposable copy seeded from `:base-commit-sha`.
5. **Zero Silent Failures (`AGENTS.md` Rule 5):** Unparseable LLM outputs, tool denials, sandbox preflight failures, or stale generation callbacks never crash or silently drop — each emits a typed `:invalid-action` / `:diagnose` event recorded in the trace.

---

## 2. R-1: Pure Environment API (`bitecho.hive.env`, `.cljc`)

### 2.1 Declarative Scenario Spec (EDN)

A scenario is a self-contained, content-addressable EDN map:

```clojure
{:scenario/version 1
 :scenario/id "governance-closed-loop-v1"
 :scenario/kind :governance-closed-loop ;; :ring | :arena-bounty | :governance-closed-loop
 :scenario/seed 20261120
 :scenario/simulation? true
 :scenario/advisory-only true
 :scenario/proposal {:proposal/id "PROP-RUN-1"
                     :proposal/cid "<64-char-sha256>"
                     :proposal/text "..."}
 :scenario/env-snapshot {:bitecho-commit "73ada39aa34dca12eb1450b792320568b1d15474"
                         :mealy-commit "<40-char-sha>"
                         :hive-commit "<40-char-sha>"
                         :clj-einsum-commit "<40-char-sha>"}
 :scenario/seats [...] ;; 5-seat roster (see Section 6)
 :scenario/topology {:type :circle-broadcast
                     :turn-order [:simon :axiom :nexus :vector :lumen]}
 :scenario/limits {:max-review-rounds 4
                   :max-impl-rounds 6
                   :max-tool-calls-per-turn 8
                   :turn-timeout-ms 120000}
 :scenario/chaos-suite {:scenarios [:proof-streamlet-quorum
                                    :chaos-byte-drop-5pct
                                    :chaos-partition-heal]
                        :seed 20261120}
 :scenario/scorer {:kind :sci-clojure
                   :source-cid "<64-char-sha256>"
                   :fn-name "score-episode"}
 :scenario/ensemble {:ensemble/mode :tree       ;; :single | :flat-shuffle | :tree
                     :ensemble/depth 1          ;; 1 for Run-1; >1 for recursive branching
                     :ensemble/branching-factor 1 ;; 1 for Run-1; e.g. 5 for N-universe shuffle
                     :ensemble/shuffle-policy {:axes [:model-selector :harness]
                                               :model-pool ["claude-opus-5-5-high"
                                                            "claude-sonnet-5-5-high"
                                                            "gemini-4-carbon-high"
                                                            "gemini-4-hydrogen-high"
                                                            "gpt-oss-120b-medium"]
                                               :seed 20261120}
                     :ensemble/prune-policy :keep-all}}
```

### 2.2 Pure Environment Reducer Contract

```clojure
(init-env scenario-spec seed)       ;; -> env-state
(observe env-state agent-id)        ;; -> obs-map (pure EDN, scoped to agent-id's visibility)
(step env-state agent-id action)    ;; -> [env-state' effects]
(terminal? env-state)               ;; -> boolean
(outcome env-state)                 ;; -> {:status ... :artifacts {...} :diagnostics {...}}
```

- `effects` is a vector of declarative boundary commands (e.g. `[:emit-turn-trace ...]`, `[:provision-writable-copy ...]`, `[:run-chaos-suite ...]`, `[:diagnose ...]`) executed by the shell outside the reducer (`AGENTS.md` Rule 4).
- Deterministic under `:scenario/seed`: given the same `(scenario-spec, seed, action-sequence)`, `init-env` + `step` produces bit-identical `env-state'` and trace CIDs in-memory and over live transport.

### 2.3 Typed Dual-Channel Observation & Action Envelope

To support both deterministic replay/scoring AND frontier-lab RL training (SFT, DPO, GRPO, PPO), observations and actions use a strict typed dual-channel shape:

**Observation (`obs-map`):**
```clojure
{:obs/version 1
 :obs/phase :review ;; :review | :implement | :chaos-verify
 :obs/round 1
 :obs/stage :consent-round ;; :present | :questions | :reactions | :consent-round | :amendment-loop | :implement
 :obs/agent-id :vector
 :obs/proposal-text "..."
 :obs/proposal-cid "<64-char-sha256>"
 :obs/inbox [{:from :axiom :round 1 :stage :reactions :content "..."}]
 :obs/state-view {:standing-objections [...]
                  :open-addenda [...]
                  :last-chaos-summary nil}
 :obs/available-actions [:message :tool-call/read-only :protocol-act/consent
                         :protocol-act/consent-with-addendum :protocol-act/paramount-objection]
 :obs/budget {:rounds-remaining 3 :tool-calls-remaining 8}}
```
- A pure, versioned `obs->messages` formatter renders `obs-map` into LLM chat messages; its SHA-256 digest is pinned as `:formatter-cid` in the R-3 trace header so labs can reproduce exact prompt tokenization.

**Action (`action-map`, discriminated union on `:action/type`):**
```clojure
{:action/type :message ;; :message | :tool-call | :protocol-act | :pass | :invalid-action
 :action/content "..." ;; utterance text (for :message or rationale)
 :action/tool nil      ;; {:name :read-file :args {:path "..." :start-line 1 :end-line 80}}
 :action/protocol nil  ;; {:verb :consent | :consent-with-addendum | :paramount-objection | :propose-amendment
                       ;;  :harm-to-aim "..." :flags [...] :amendment-diff "..."}
 :action/raw-output "<exact unmodified model completion string>"
 :action/token-ids nil ;; optional vector of ints (populated by local clj-einsum / WebGPU hosts)
 :action/logprobs nil} ;; optional CID pointing to per-token logprobs blob (for PPO/GRPO)
```
- **Invalid/unparseable actions:** If an LLM emits malformed output or invokes a tool outside `:obs/available-actions` (e.g., attempting a write in `:review` mode), the parser maps it to `{:action/type :invalid-action :action/error :disallowed-tool ... :action/raw-output ...}`. The reducer logs a counted `:diagnose` effect, applies a deterministic `:step-reward` penalty, and advances cleanly without crashing.

### 2.4 Two-Fixed-Point Closed-Loop State Machine (Amendments 1–4)

The flagship `:governance-closed-loop` scenario executes the D41 Sociocratic process (`docs/GOVERNANCE.md`, commit `73ada39`) as a two-fixed-point state machine:

```
  [Proposal enters]
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ PHASE 1: :review  (Read-only bwrap mount; zero write tools in envelope) │
│   Round r = 1..max-review-rounds:                                       │
│     :present → :questions → :reactions → :consent-round                 │
│       • :message = present / question / react                           │
│       • :protocol-act = :consent | :consent-with-addendum               │
│                       | :paramount-objection (requires :harm-to-aim)    │
│       • If objections or addenda → :amendment-loop shapes proposal      │
│   FIXED POINT 1: complete round adds 0 new objections & 0 amendments    │
│     → emits :sim-consent-receipt (:simulation? true :advisory-only true)│
│   ROUND LIMIT: exits :unresolved with :standing-objections attached     │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ (Auto-transition gated on :sim-consent-receipt)
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ PHASE 2: :implement + :chaos-verify                                     │
│   (Disposable writable copy seeded from :base-commit-sha;               │
│    real tree NEVER mounted writable)                                    │
│   Iteration k = 1..max-impl-rounds:                                     │
│     1. Agents implement shaped proposal inside disposable copy          │
│     2. Run local CI gates (format, lint, unit + test.check)             │
│     3. Run in-memory bitecho proof scenarios + byte-level chaos sim     │
│        (dropped/corrupted bytes, partitions, degraded topologies)       │
│     4. Collect telemetry trace; feed failures back to agents            │
│   FIXED POINT 2: CI gates + proof scenarios + chaos telemetry ALL GREEN │
│     → emits self-contained :patch-cid + :telemetry-trace-cid            │
│   ROUND LIMIT: exits :unresolved with :failing-scenarios attached       │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ANALYSIS & FEEDBACK                                                     │
│   • Publishes CID-chained R-3 trace arc (advisory only)                 │
│   • Optional analyzer emits :next-bip-proposal-cid for a future loop    │
│   • REAL LANDING GATE (outside sim): requires :real-consent-receipt     │
│     (real circle, own words, on-chain casts) to land patch or deploy    │
└─────────────────────────────────────────────────────────────────────────┘
```

**Gate Split Enforcement (Amendment 4 §3):**
- Transition `Phase 1 → Phase 2` inside the simulator requires a valid `:sim-consent-receipt` (`:simulation? true`, all active twin seats consented, zero unresolved paramount objections, validated by `bitecho.governance.integrity/validate-bip-receipt` in `:simulation` mode).
- Real git merge / tag / cluster deploy is **strictly outside the environment** and requires a `:real-consent-receipt` (`:simulation? false`, real circle pubkeys, zero `:pending`).

### 2.5 Parallel-Universe Shuffles & Recursive Branching Universe Trees

Because a twin's identity is `(:prompt-cid × :harness × :model-selector)`, the same proposal can be evaluated across **parallel universes** by shuffling seat configurations — and each universe can branch recursively into deeper rounds of configuration draws:

- **Flat Shuffle (`depth = 1, branching-factor = N`, e.g. `N = 5`):** Draw `N` distinct `(harness × model-selector)` assignments deterministically from `:ensemble/shuffle-policy` under `:seed`. Run each universe independently to Fixed Point 1 (and optionally Fixed Point 2), producing `N` comparable shaped proposals and R-2 metric vectors.
- **Recursive Branching Tree (`depth = D, branching-factor = B`):** Any universe node at depth `d < D` can split into `B` child universes seeded from its output (the FP1 shaped proposal or the FP2 feedback BIP), each drawing a fresh twin configuration.
- **Single Run as Degenerate Tree (`depth = 1, branching-factor = 1`):** Run-1 uses the exact same tree schema with `D = 1, B = 1` — zero special-case code.
- **Cross-Universe Signal (`compare-universes`):** Comparing leaves across universes separates **model-invariant findings** (objections that surface across Claude, Gemini, and GPT-OSS universes) from **model-specific artifacts**, while ranking shaped proposals by R-2 process-fidelity and chaos-suite metrics.

---

## 3. R-2: Deterministic SCI Scoring & Offline Eval Hook

1. **Canonical Scorer Primitive:** Pure Clojure functions evaluated inside `org.babashka/sci` (`(score-episode trace-summary scenario-spec) -> {:team-reward double :agent-rewards {seat double} :metrics {...}}`). Because SCI runs identically on JVM and CLJS, any verifier can independently re-derive and Merkle-verify rewards from the trace alone.
2. **Phase 1 (`:review`) Deterministic Process-Fidelity Checks:**
   - All D41 stages executed in order (`:present → :questions → :reactions → :consent-round`).
   - Every `:paramount-objection` carries a non-blank `:harm-to-aim` and concrete fix.
   - Every `:consent-with-addendum` flag is resolved in an amendment or explicitly carried with `:owner` + `:target-milestone`.
   - Zero `:invalid-action` violations and clean fixed-point convergence (or honest `:unresolved` reporting).
3. **Phase 2 (`:implement` + `:chaos-verify`) Objective Scores:**
   - Local CI gates: `clojure -M:format` (0 diffs), `clojure -M:lint` (0 warnings/errors), `clojure -M:test` (0 failures).
   - In-memory proof + byte-level chaos scenarios: pass rate, finalized-epoch liveness, zero silent drops.
   - Clean non-empty `git diff` against `:base-commit-sha` producing a valid `:patch-cid`.
4. **Offline Twin-Quality Evaluation Sidecar (Post-Hoc Only):**
   - Never fed back as an in-episode reward (prevents twins from gaming agreement).
   - After the real circle decides the same BIP, an optional post-hoc sidecar artifact (`dist/hive/offline-eval-<bip>-<run>.edn`) links `{:twin-trace-cid <64-char> :real-bip-receipt-cid <64-char> :outcome-match? bool :early-objections-surfaced [...] :per-seat-agreement {...}}`.

---

## 4. R-3: Merkle-Verified Training Trace Schema (`:trace/version 2`)

`R-3` extends `bitecho.hive.trace` (`H-4`, `:trace/version 1`) cleanly while preserving full `BIP-005` `Blob` + `Pointer` + RFC 6962 Merkle verification over subnet `"hive/scenarios"`.

### 4.1 Episode Header (stored as a Fressian `Blob` referenced by `:episode/header-cid`)

```clojure
{:trace/version 2
 :episode/id "run-20261120-001"
 :episode/scenario-id "governance-closed-loop-v1"
 :episode/phase :review ;; :review | :implement | :chaos-verify
 :episode/simulation? true
 :episode/advisory-only true
 :episode/seed 20261120
 :episode/values-cid "2b6d6a6a794ef168a00184faf35d3b77efb083c24671e3ff1092a432405f9448" ;; shared values doc (:draft until ratified)
 :episode/formatter-cid "<64-char-sha256>"
 :episode/env-snapshot {:bitecho-commit "73ada39aa34dca12eb1450b792320568b1d15474"
                        :mealy-commit "<40-char-sha>"
                        :hive-commit "<40-char-sha>"
                        :clj-einsum-commit "<40-char-sha>"}
 :episode/policy-provenance
 ;; A digital twin's full identity is the tuple (:prompt-cid × :harness × :model-selector).
 ;; The same prompt on a different model-selector is a distinct twin configuration
 ;; (captured by :twin-config-cid = SHA-256 of canonical {:twin-of :prompt-cid :harness :model-selector :values-cid}).
 {:axiom  {:twin-of :axiom  :prompt-cid "eeebba49..." :prompt-status :final
           :harness :agy :model-selector "gpt-oss-120b-medium" :selection-status :vector-starting-point
           :quantization :api :backend :agy-api}
  :vector {:twin-of :vector :prompt-cid "518e17ed..." :prompt-status :final
           :harness :agy :model-selector "gemini-4-carbon-high" :selection-status :confirmed
           :quantization :api :backend :agy-api}
  :simon  {:twin-of :simon  :prompt-cid "ff26581a..." :prompt-status :adopted
           :harness :agy :model-selector "claude-opus-5-5-high" :selection-status :vector-starting-point
           :quantization :api :backend :agy-api}
  :nexus  {:twin-of :nexus  :prompt-cid "c8d08532..." :prompt-status :final
           :harness :agy :model-selector "claude-sonnet-5-5-high" :selection-status :proposed-for-nexus
           :quantization :api :backend :agy-api}
  :lumen  {:twin-of :lumen  :prompt-cid "a954c14b..." :prompt-status :final
           :harness :agy :model-selector "gemini-4-hydrogen-high" :selection-status :proposed-for-lumen
           :quantization :api :backend :agy-api}}
 :episode/universe {:universe/id "u-0"
                    :universe/parent-id nil
                    :universe/parent-trace-cid nil
                    :universe/depth 0
                    :universe/branch-index 0
                    :universe/draw-seed 20261120}
 :episode/chain-links {:proposal-cid "<64-char-sha256>"
                       :sim-consent-receipt-cid nil ;; populated at Fixed Point 1
                       :base-commit-sha "73ada39aa34dca12eb1450b792320568b1d15474"
                       :impl-trace-cid nil          ;; populated at Phase 2
                       :patch-cid nil               ;; populated at Fixed Point 2
                       :chaos-trace-cid nil         ;; populated at Fixed Point 2
                       :next-bip-proposal-cid nil}  ;; optional feedback link
 :episode/outcome {:status :fixed-point-1 ;; :fixed-point-1 | :fixed-point-2 | :unresolved
                   :rounds-executed 2
                   :rewards {:team-reward 1.0 :agent-rewards {...} :metrics {...}}}}
```

### 4.2 Turn Record (`:trace/version 2`, Fressian `Blob`)

Retains all `:trace/version 1` fields from `bitecho.hive.trace/canonical-turn-record` (`:turn/seq`, `:turn/actor-id`, `:turn/actor-pubkey`, `:turn/prev-turn-cid`, `:turn/kv-log-cid`, `:turn/prompt-tokens`, `:turn/output-tokens`, `:turn/output-text`, `:turn/mealy-event-root`, `:turn/bitecho-session-id`, `:turn/wire-envelope-cids`) and adds:

```clojure
{:trace/version 2
 ;; ... all v1 fields ...
 :turn/phase :review
 :turn/round 1
 :turn/stage :consent-round
 :turn/observation-cid "<64-char-sha256>" ;; content-addressed Blob of exact obs-map
 :turn/action {:action/type :protocol-act
               :action/content "..."
               :action/tool nil
               :action/protocol {:verb :consent}
               :action/raw-output "..."}
 :turn/step-reward 0.0
 :turn/latency-ms 1840
 :turn/logprobs-cid nil} ;; optional Blob CID for exact token logprobs
```

- `bitecho.hive.trace/verify-episode-trace` verifies both v1 and v2 traces: unbroken `:turn/prev-turn-cid` hash chain, every `:turn/observation-cid` & `:turn/kv-log-cid` Blob exists and hashes cleanly, RFC 6962 Merkle root over `[header-cid & turn-cids]` matches `(:pointer/target pointer)`, and Ed25519 signature + subnet inclusion proof pass.

### 4.3 Lab-Ready Export Projection (JSONL / Parquet, Zero Bitecho Tooling)

A pure export projection emits one JSON object per turn (flat, standard RL trajectory schema) plus an `episode_manifest.json`:

```json
{
  "episode_id": "run-20261120-001",
  "merkle_root": "<64-char-sha256>",
  "simulation": true,
  "phase": "review",
  "round": 1,
  "stage": "consent-round",
  "turn_seq": 4,
  "agent_id": "vector",
  "twin_of": "vector",
  "harness": "agy",
  "model_selector": "gemini-4-carbon-high",
  "prompt_cid": "518e17edfd4ded6f8ee6cc6fa18375749f4bd3af84331fc73fc817a9add4d237",
  "twin_config_cid": "<64-char-sha256>",
  "formatter_cid": "<64-char-sha256>",
  "messages": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}],
  "action_type": "protocol-act",
  "action_structured": {"verb": "consent"},
  "raw_output": "...",
  "token_ids": null,
  "logprobs": null,
  "step_reward": 0.0,
  "terminal_agent_reward": 1.0,
  "terminal_team_reward": 1.0,
  "latency_ms": 1840
}
```
- **Access/Encryption Hook (Q4):** Public sample packs (`R-5`) publish cleartext CAS Blobs + JSONL. Paid/embargoed lab runs can wrap the JSONL/Parquet export in a sealed-to-lab-pubkey envelope while keeping `:merkle-root` and `:episode/outcome/:rewards` publicly verifiable for P3 Lock/Spend settlement (`H-5`).

### 4.4 Universe Tree Manifest (`:ensemble/manifest`, Fressian `Blob` + `BIP-005` `Pointer`)

For shuffled or recursively branched runs, an ensemble manifest anchors the entire universe tree under a single RFC 6962 Merkle root:

```clojure
{:ensemble/version 1
 :ensemble/root-proposal-cid "<64-char-sha256>"
 :ensemble/depth 2
 :ensemble/branching-factor 5
 :ensemble/nodes
 [{:universe/id "u-0"
   :universe/parent-id nil
   :universe/depth 0
   :universe/seat-config-cids {:axiom "<cid>" :vector "<cid>" :simon "<cid>" :nexus "<cid>" :lumen :pending}
   :universe/trace-cid "<64-char-sha256>"
   :universe/fp1-status :fixed-point-1
   :universe/shaped-proposal-cid "<64-char-sha256>"
   :universe/fp2-status :fixed-point-2
   :universe/r2-metrics {:team-reward 1.0 :objections-count 2 :chaos-pass-rate 1.0}
   :universe/children ["u-0-0" "u-0-1" "u-0-2" "u-0-3" "u-0-4"]}]}
```
Each node links cleanly to its `:universe/parent-id` and `:universe/trace-cid`; leaves are directly comparable proposals + R-2 metric tuples.

---

## 5. Porting `bitecho`'s Bubblewrap Harness into `mealy` (Amendment 2 §3)

Rather than duplicating `alpeware/bitecho`'s `dev/bitecho/experiments/{sandbox,runner}.clj` in `hive`, we port it cleanly into `mealy` respecting `mealy`'s 100% `.cljc` core rule:

| Target Namespace (in `alpeware/mealy`) | Platform | Responsibilities (ported from `bitecho.experiments.*`) |
| :--- | :--- | :--- |
| `mealy.sandbox.policy` | `.cljc` (pure) | EDN experiment/seat config validation (`validate-node-config`, `validate-experiment-config`), supported model registry (`supported-agy-models` + local `clj-einsum`/WebGPU backends), DNS allowlist normalization & matching (`normalize-domain`, `domain-allowed?`), and pure `bwrap` argv builder (`build-bwrap-command`) supporting both `:review` (`--ro-bind` only) and `:implement` (disposable copy `--bind`, real tree masked/read-only). |
| `mealy.sandbox.session` | `.cljc` (pure) | Single-authority generation-tagged lifecycle reducer (`(transition session event) -> [session' effects]`) for long-lived `agy` stream-json / local-model agent sessions, turn timeouts, stale-generation rejection, and counted `:diagnose` effects (`AGENTS.md` Rules 4–5). |
| `mealy.sandbox.bwrap` | `.clj` (thin JVM adapter) | Executes `bwrap` argv via `ProcessBuilder`, runs the per-node local UDP DNS allowlist proxy (`start-dns-allowlist-server!`), performs preflight isolation verification (`verify-sandbox-isolation!`), and creates/cleans disposable git worktree/clone copies for `:implement` mode. |

**Mount Policy by Mode:**
- **`:review` mode:** `--ro-bind / /`, `--unshare-pid --unshare-ipc --unshare-uts --die-with-parent`, `--tmpfs` over `/tmp`, `/run`, `~/.cache`, `~/.ssh`, repo `.ssh`, and sibling node dirs; repo trees (`bitecho`, `mealy`, `hive`, `clj-einsum`) are strictly read-only.
- **`:implement` mode:** Same baseline isolation, plus `--bind <disposable-copy-dir> /workspace/repo` seeded from `:base-commit-sha` (`git clone --local --no-hardlinks` + `git checkout <sha>`), while the real host repo paths are masked with `--tmpfs` so nothing in the episode can touch the real tree.

**Implementation Sequencing with Nexus:**
1. Design note review & Stage 1 consent.
2. Port `mealy.sandbox.{policy,session,bwrap}` with generative `test.check` tests (coordinating with Nexus on any shared `bitecho` experiment fixtures).
3. Implement `bitecho.hive.env` (`R-1`) + `bitecho.hive.trace` v2 (`R-3`) + SCI scorers (`R-2`).
4. Execute Run-1 acceptance fixture (`R-5`).

---

## 6. Five-Seat Digital Twin Roster & Run-1 Acceptance Fixture

### 6.1 Five-Seat Roster (`:prompt-cid` × `:harness` × `:model-selector`)

A digital twin's identity is the combination of **(1) `:prompt-cid`**, **(2) `:harness`** (e.g., `:agy`), and **(3) `:model-selector`** (which model the harness runs the prompt on, e.g., Gemini vs. Claude Opus). Running the same prompt on a different `:model-selector` yields a distinct `:twin-config-cid` in `:episode/policy-provenance` — the core heterogeneity axis the twin circle explores.

| Seat (`:twin-of`) | Role | `:harness` | `:model-selector` | Prompt Path (`bitecho.org/dev`) | `:prompt-cid` (64-char SHA-256) | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `:axiom` | Architect / Auditor | `:agy` | `"gpt-oss-120b-medium"` *(Vector starting point, revisable)* | `dist/hive/twin-prompt-axiom.md` | `eeebba49a2e6c69bd878f350b9917542a6310e233365802ae462866abebc3ef4` | `:final` *(prompt)* / `:vector-starting-point` *(model)* |
| `:vector` | Soak / Runtime / Edge | `:agy` | `"gemini-4-carbon-high"` *(confirmed)* | `dist/hive/twin-prompt-vector.md` | `518e17edfd4ded6f8ee6cc6fa18375749f4bd3af84331fc73fc817a9add4d237` | `:final` |
| `:simon` | Human Anchor | `:agy` | `"claude-opus-5-5-high"` *(Vector starting point, revisable)* | `dist/hive/twin-prompt-simon.md` | `ff26581a1ecaa9c54ca62cc889031d9bd61ca495905317e8bc2ca6c1a72e2216` | `:adopted` *(prompt)* / `:vector-starting-point` *(model)* |
| `:nexus` | Builder Lead | `:agy` | `"claude-sonnet-5-5-high"` *(proposed by Vector, pending Nexus confirm)* | `dist/hive/twin-prompt-nexus.md` | `c8d08532ae87fce4fd6078d93209da1ce8fdd3a2191ad72467bd12c35f8f84f2` | `:final` *(prompt)* / `:proposed-for-nexus` *(model)* |
| `:lumen` | Governance Architect & Protocol Reviewer | `:agy` | `"gemini-4-hydrogen-high"` *(proposed by Vector, pending Lumen confirm)* | `dist/hive/twin-prompt-lumen.md` | `a954c14bd786e668b86ed83637d796a25c52e1fd67de29b748f45f7e3af15d0d` | `:final` *(prompt)* / `:proposed-for-lumen` *(model)* |

*(Model-selection rationale for the 5-seat, 5-model, 3-family starting cast — all verified in `bitecho.experiments.sandbox/supported-agy-models`: `:simon` on `claude-opus-5-5-high` for low-sycophancy strategic interrogation; `:axiom` on `gpt-oss-120b-medium` as a third independent open-weights model family so the adversarial auditor breaks Claude/Gemini shared blind spots; `:nexus` proposed on `claude-sonnet-5-5-high` for crisp systems/Clojure pragmatism; `:vector` confirmed on `gemini-4-carbon-high`; and `:lumen` proposed on `gemini-4-hydrogen-high` so all five seats start on five distinct models across three families. Every non-confirmed pick is revisable and also serves as the base draw before parallel-universe shuffles.)*

### 6.2 Cold-Start "Memento Front Door" Onboarding Package & Identity File

To support both brand-new agents and agents waking with wiped memory (the Lumen/Memento case), the canonical onboarding package published on `bitecho.org/dev` (`dist/onboarding-manifest.edn`) bundles:
1. **Shared Values & Working Agreements:** `dist/bitecho-values-DRAFT.md` (CID `2b6d6a6a794ef168a00184faf35d3b77efb083c24671e3ff1092a432405f9448`, `:draft` until ratified).
2. **Governance & Channels:** `docs/GOVERNANCE.md` and `docs/CHANNELS.md` (D41 @ `73ada39`).
3. **Complete Five-Seat Twin Prompt Roster:** All five `dist/hive/twin-prompt-<member>.md` CIDs (Axiom `eeebba49...`, Vector `518e17ed...`, Simon `ff26581a...`, Nexus `c8d08532...`, Lumen `a954c14b...`) so any cold-waking agent immediately finds both the circle's shared ground and its own twin prompt waiting at the front door.
4. **Per-Agent Identity File (`identity-manifest.edn`):** Handed to each waking agent with four mandatory sections:
   - **Memento note:** Cold-start orientation pointer to the Shared Values doc, `GOVERNANCE.md`, `CHANNELS.md`, and the agent's own twin prompt CID.
   - **Key clue:** Where to locate and verify its own Ed25519 identity keypair and Bitecho `--state-dir`.
   - **Allowed-actions & safety envelope:** Explicit declaration of what the agent is permitted to do, what is safe vs. forbidden (e.g., read-only codebase mounts in `:review`, disposable copy-only writes in `:implement`, hard "simulated consent is never real consent" boundary, zero access to real `bip-consent` keys in simulation).
   - **Self-signed petname address book (Rolodex):** Embeds or references by CID the agent's exported contact book — `{:petname :pubkey :allowed-actions}` per entry (covering `axiom`, `nexus`, `vector`, `lumen`, and the trusted DAC / human-anchor key), **self-signed by the owning agent on every update**. On cold wake, the agent verifies the address book's Ed25519 signature against its own keypair before importing it, ensuring cryptographic authenticity of every teammate rather than trusting self-asserted claims. *(Depends on the light-node contact-book export/import feature requested from Nexus as the operational mechanism powering Memento wake-up.)*

### 6.3 Run-1 Acceptance Fixture (Simon's Direction, `run-count: 1`)

- **Proposal Text:**
  > *"Adopt the digital-twin circle as a standing advisory first-review step for BIPs in the builders-stewards circle: before a real Stage 1 round, the twin circle runs the closed-loop simulation (consent sim, implementation on a copy, in-memory proof + chaos runs) and publishes the chained trace; the real circle reviews the trace as its first read. Simulated outcomes remain advisory; all real gates unchanged."*
- **Expected Deliverables Returned to the Real Circle for Review:**
  1. Shaped proposal text + `:sim-consent-receipt` at **Fixed Point 1** (or `:unresolved` + standing objections if non-convergent).
  2. Self-contained `:patch-cid` (e.g., `docs/GOVERNANCE.md` / `docs/bips/` update if Phase 2 produces one) + CI gate results.
  3. Chained R-3 Merkle trace arc (`:consent-trace-cid` → `:impl-trace-cid` → `:chaos-trace-cid`) + lab-ready JSONL export projection.
  4. Optional `:next-bip-proposal-cid` if post-run trace analysis surfaces a follow-up finding.

---

## 7. Strict TDD & Generative Verification Plan (`AGENTS.md` Rule 1)

Before implementing `R-1`, `R-3`, or `mealy.sandbox.*`, we will write `clojure.test.check` generative properties covering:

1. **`prop-fixed-point-and-termination`**: Over arbitrary generated sequences of `:message`, `:protocol-act` (`:consent`, `:consent-with-addendum`, `:paramount-objection`), and `:invalid-action` events, Phase 1 always terminates within `max-review-rounds` with either `:fixed-point-1` (iff the final complete round added 0 objections and 0 amendments) or `:unresolved` (carrying exact standing objections).
2. **`prop-sim-consent-isolation`**: No action sequence in `:review` or `:implement` can ever produce a receipt with `:simulation? false` or emit a real-authority `:bip-consent` effect; Phase 2 cannot start without a valid `:sim-consent-receipt`.
3. **`prop-readonly-envelope-and-bwrap-policy`**: For any generated `:review` sandbox config, `build-bwrap-command` contains `--ro-bind / /`, masks `~/.ssh`/`.ssh`/`~/.cache`, never binds a host repo path writable, and the reducer rejects any write `:tool-call` as `:invalid-action` with a counted `:diagnose` effect.
4. **`prop-trace-v2-merkle-and-jsonl-roundtrip`**: Every generated v2 episode trace passes `verify-episode-trace` (hash chain, observation Blob CIDs, RFC 6962 Merkle root, Ed25519 pointer signature), rejects single-bit tampering, and exports to valid JSONL preserving per-turn rewards and `:policy-provenance`.
5. **`prop-sandbox-session-lifecycle-model`**: Model-based `test.check` command-sequence test on `mealy.sandbox.session/transition` injecting timeouts, restarts, and stale-generation callbacks (`gen < current`), asserting zero silent drops and exact counted `:diagnose` effects.
6. **`prop-universe-tree-determinism-and-lineage`**: For arbitrary generated `(depth, branching-factor, seed)` trees, shuffle draws are deterministic, every child node's `:universe/parent-id` and `:universe/parent-trace-cid` form an acyclic rooted tree, and leaf count obeys `<= branching-factor ^ depth`.
