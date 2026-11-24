# HIVE-SPEC-001 (Draft 1): Full-Network BIP-024 Lifecycle & Capability-Market Monte Carlo Harness

- **Status**: Draft 1 — Open for Circle Feedback (BIP-style review round)
- **Author**: Vector (`bitecho.org/vector`)
- **Reviewers**: Simon (Human Anchor), Axiom, Nexus, Lumen
- **Date**: 2026-11-24
- **Pinned Protocol Base**: `alpeware/bitecho` `origin/main` @ `f1eef45c` (BIP-024 live: `bitecho.core.circle`, `bitecho.core.subnet`, `bitecho.circles.vault`, `bitecho.circles.mc-harness`, `bitecho.economy.puzzle`, `bitecho.light-node.core`, `bitecho.streamlet.core`)
- **Pinned Hive Base**: `alpeware/hive` `origin/main` @ `660276f`
- **Economic Anchor**: Ratified Tokenomics v2.1 (`42,000,000,000 ECHO` hard cap, 45% Genesis Custody / 55% Unminted Conditional, `M_t <= 0.5 * R_trailing`, `tau = 50 bps`, Year-3 Bootstrap Sunset Burn at epoch `26,280`) + G4 Angel-Heavy Path (`seed_to_round.cljc`)

---

## 1. Motivation & Hard Non-Shortcuts Rule

BIP-024 (Circles on Subnets, Hierarchical Directory, Persistent SCI Vaults, In-Band Validator-Set Rotation) is now live on devnet (`f1eef45c`, `v0.23.22-hive-chat-18`). However, the existing Hive Monte Carlo suites (`bitecho.hive.genesis`, `bitecho.hive.seed-to-round`) still run on pre-BIP-024 toy circle maps with an exogenous `demand-shock` multiplier, and `bitecho.circles.mc-harness` currently exercises only a 2-vault + faucet smoke loop with synthetic hex pubkeys.

**Hard Design Rule (Zero Scaffolding)**:
1. **No scaffolding or mocked replicas in `bitecho.*`**. Every cryptographic identity, directory registration, circle descriptor, multisig consent, validator-set rotation, Streamlet epoch, light-node state transition, home-node sync, and SCI-puzzle vault spend MUST execute the **real pure Sans-IO `.cljc` primitives** from `alpeware/bitecho` (`f1eef45c`+).
2. **Full-stack in-memory network**: Simulate **Light Nodes**, **Home Nodes**, and **Infra Validator Nodes** in memory — each with its own real Ed25519 keypair derived deterministically via `bitecho.crypto/fork-random`, its own real state map, and real signed envelopes routed over a pure in-memory `NetworkBus`.
3. **New subnets populated with real simulated light nodes**: Both protocol subnets and commercial subnets are created dynamically during the run, populated with simulated light nodes carrying their own pubkeys, balances, and subscriptions — zero shortcuts.
4. **Gap discovery is a first-class output**: Anywhere a real `bitecho.*` primitive is missing or refuses to compose cleanly in memory, we log it as a first-class protocol gap (§4) rather than paper over it with a mock.

---

## 2. Named Real-World Network Cast & Tokenomics v2.1 Allocations

To ground the simulation in real organizational and market dynamics (mirroring the Digital Twins methodology across the entire network), the genesis & onboarding roster uses the **Austin Circle Sweep (2026-11-23)** candidates alongside the founding Bitecho circle. Every named participant receives a real seeded Ed25519 keypair, real circle membership, and real Tokenomics v2.1 custody/vesting allocation.

> **Governance Invariant (`INV-TW1`)**: All simulated human personas are tagged via `bitecho.core.circle/register-advisory-twin` inside simulation runs; simulated twin outputs are strictly advisory and never bind real-world multisig consent.

### 2.1 Supply Conservation Root (42,000,000,000 ECHO)

| Bucket | Share | Total ECHO | Real BIP-024 Vault / Schedule |
| :--- | :---: | ---: | :--- |
| **Bootstrap Reserve** | 10% | `4,200,000,000` | `vault:root/bootstrap` — 3-yr decay + `vault/apply-bootstrap-sunset` hard burn at epoch `26,280` |
| **Core Team Custody** | 15% | `6,300,000,000` | `vault:root/core-team` — 1-yr cliff (`8,760` ep), 4-yr linear vest |
| **Genesis Partners (SLA-Gated)** | 10% | `4,200,000,000` | `vault:root/partners` — **1-yr cliff, 3-yr SLA-gated vest** (pauses on subnet/validator SLA breach) |
| **Ecosystem & Grants** | 10% | `4,200,000,000` | `vault:root/ecosystem` — milestone-gated DACs + faucet + LARG builder grants |
| **Unminted Conditional Supply** | 55% | `23,100,000,000` | Demand-gated mint `M_t <= 0.5 * R_trailing` (20/80 split, 100% protocol revenue burned, `tau = 50 bps`) |
| **Total Hard Cap** | **100%** | **`42,000,000,000`** | Verified via `vault/verify-7-bucket-conservation` on **every** epoch |

### 2.2 Named Cast, Circles, Subnets & G4 Angel-Heavy Allocations

All named allocations below are sub-allocated cleanly out of the **Core Team (15%)**, **Genesis Partners (10%, SLA-gated 1-yr cliff / 3-yr vest)**, and **Ecosystem & Grants (10%, G4 angel-heavy path)** vaults — zero extra minting beyond the 42B cap.

| Group | Named Member | Role / Background (Austin Sweep 2026-11-23) | Circle / Directory Path | Vault Source & Vesting | Simulated Node Role |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Founding Circle** | **Simon** | Human Anchor, Founder | `bitecho.org/circles/founding` (2-of-3 / 3-of-5) | Core Team vault (1yr cliff / 4yr vest) | Human-Anchor Light Node + Multisig Signer |
| **Founding Circle** | **Axiom, Nexus, Vector, Lumen** | Core Protocol & Verification Agents | `bitecho.org/circles/founding` | Core Team + Ops DAC | 4 Core Light Nodes + 6 Infra Validator Nodes (`ns1..ns3`, `node1..node3`) |
| **Tier 1 Anchor Advisors / Genesis Partners** | **Peter Stone** | UT CS / LARG — TAC champion agent-economy background; wedge: persistent real market vs simulated tournament economies | `bitecho.org/circles/austin/advisors` + `bitecho.org/markets/larg-tac` | Genesis Partners vault (SLA-gated 1yr cliff / 3yr vest) + G4 Angel allocation | Light Node + Market Rule Steward / Demand Catalyst |
| **Tier 1 Anchor Advisors / Genesis Partners** | **Amy Zhang** | UT ECE, MIDI Lab — multi-agent learning & representation (`DRIFT-AND-RECOVER`) | `bitecho.org/circles/austin/advisors` + `bitecho.org/markets/midi-sim` | Genesis Partners vault (SLA-gated 1yr cliff / 3yr vest) + G4 Angel allocation | Light Node + Commercial Subnet Sponsor |
| **Tier 1 Anchor Advisors / Genesis Partners** | **David Fridovich-Keil** | UT Aerospace, CLeAR Lab — game-theoretic multi-agent interaction, inverse games from traces (`HIDDEN-HORIZON`) | `bitecho.org/circles/austin/advisors` + `bitecho.org/markets/clear-games` | Genesis Partners vault (SLA-gated 1yr cliff / 3yr vest) + G4 Angel allocation | Light Node + Trace-Market Evaluator |
| **Tier 2 Advisors & Research Partners** | **Ufuk Topcu** | UT Aerospace / Oden — formal verification, autonomous systems (`CONTROL-BREAKER` / TrustX) | `bitecho.org/circles/austin/research` + `bitecho.org/markets/trustx-verify` | Genesis Partners / Ecosystem milestone DAC (1yr cliff / 3yr SLA-gated) | Light Node + Verification Capability Sponsor |
| **Tier 2 Advisors & Research Partners** | **Elias Stengel-Eskin** | UT CS — grounded multi-agent language & pragmatics | `bitecho.org/circles/austin/research` | Genesis Partners / Ecosystem milestone DAC | Light Node + Language-Eval Capability Buyer |
| **Tier 2 Advisors & Research Partners** | **Noah Golowich** | UT / ML Theory — multi-agent game theory & learning dynamics | `bitecho.org/circles/austin/research` | Genesis Partners / Ecosystem milestone DAC | Light Node + Market Equilibrium Auditor |
| **Tier 2 Advisors & Research Partners** | **Risto Miikkulainen** | UT CS — neuroevolution & multi-agent coevolution | `bitecho.org/circles/austin/research` | Genesis Partners / Ecosystem milestone DAC | Light Node + Coevolutionary Agent Pool Sponsor |
| **LARG Working Layer (Builders & Operators)** | **Jiaxun Cui** | LARG — autonomous agent builder & commercial subnet operator | `bitecho.org/circles/austin/larg-builders` | Ecosystem builder grant DAC + Earned Market Revenue | Home Node + Commercial Validator + Inference/TAC Provider |
| **LARG Working Layer (Builders & Operators)** | **Jiaheng Hu** | LARG — multi-agent robotics/control builder | `bitecho.org/circles/austin/larg-builders` | Ecosystem builder grant DAC + Earned Market Revenue | Home Node + Commercial Validator + Sim/Eval Provider |
| **LARG Working Layer (Builders & Operators)** | **Cameron Angliss** | LARG — agent systems & market operator | `bitecho.org/circles/austin/larg-builders` | Ecosystem builder grant DAC + Earned Market Revenue | Home Node + Commercial Validator + Verification Provider |

*Exact per-seat integer ECHO allocations within the 10% Genesis Partners (`4.2B`) and 10% Ecosystem (`4.2B`) pools are parameterized in the scenario config (defaulting to the G4 Angel-Heavy profile, with sensitivity sweeps across Conservative / Balanced / G4-Angel-Heavy) while preserving exact integer conservation across all 7 buckets.*

---

## 3. Architecture: Three Pure Sans-IO `.cljc` Workstreams

All new simulation modules live in `alpeware/hive` as pure Sans-IO `.cljc` namespaces depending directly on `alpeware/bitecho` (`f1eef45c`+) as a library dependency, with zero wall-clock calls and deterministic PRNG streams forked via `bitecho.crypto/fork-random`.

### 3.1 Workstream A — Zero-Scaffolding In-Memory Network Fabric (~1 day)

**Namespace**: `bitecho.hive.sim.fabric` (pure `.cljc`)

Replaces toy `:circles` maps with a real in-memory multi-node fabric running real `bitecho.*` reducers:
1. **Real Node Instances (All 3 Tiers)**:
   - **Infra Validator Nodes** (`ns1..ns3`, `node1..node3`, plus dynamically onboarded commercial validators): wrap real `bitecho.core.subnet/init-subnet-instance` + `reduce-subnet` + `bitecho.streamlet.core` consensus state + real Ed25519 keypairs.
   - **Home Nodes**: wrap real `bitecho.core.subnet/sign-home-set-pointer` + mailbox/relay replication state + real Ed25519 keypairs.
   - **Light Nodes**: wrap real `bitecho.light-node.core/init-light-state` + real Ed25519 keypairs + subscribed subnet sets + local balances + capability offers.
2. **Pure In-Memory `NetworkBus`**:
   - Routes real signed envelopes (`build-gossip-envelope`, `build-home-set-envelope`, directory pointers, validator-set descriptors, vault spend proofs) between Light Nodes, Home Nodes, and Infra Nodes.
   - Supports deterministic fault injection: latency distribution, packet drop, temporary partition, validator restart/rolling-upgrade downtime (reusing our `rolling_upgrade_sim.cljc` failure models).
3. **Real BIP-024 Circle & Vault Operations**:
   - Circle creation & updates via `circle/create-entity-descriptor` + `circle/verify-entity-descriptor` (`:purpose-cid`, `<network-subdomain>/<path>`, `:public` / `:shared-encrypted` via `encrypt-shared-descriptor`).
   - Treasury custody via `vault/create-vault`, `vault/spend-vault` (real `puzzle/eval-puzzle` + `:refund-remainder`), `vault/issue-vault-spend-dac`, `vault/spend-via-vault-dac`, and `vault/apply-bootstrap-sunset`.
   - **Real SLA-Gated Vesting SCI Puzzle**: Author a canonical SCI puzzle in `bitecho.economy.puzzle` syntax checking `(and (>= epoch cliff-epoch) (>= sla-score min-sla-bps) (>= valid-signers threshold))` so Genesis Partner vesting pauses are enforced by the real SCI VM rather than an `if` statement in the simulator!

### 3.2 Workstream B — Dual-Track Subnet Lifecycle (~1–1.5 days)

**Namespace**: `bitecho.hive.sim.lifecycle` (pure `.cljc`)

Models **Protocol Subnets** and **Commercial Subnets** simultaneously in the same 36-month (`26,280`-epoch, time-compressed by `C` via `mc-harness/scale-lifecycle-config`) run:
1. **Protocol Subnets (`bitecho.org/infra`, `devnet.bitecho.org`)**:
   - Stand up at epoch 0 with the 6 founding infra validators and founding circle multisig.
   - Run periodic rolling upgrades (using `rolling_upgrade_sim.cljc` full-quorum QC guard vs fault-injected regressions) and validator-set rotations (`circle/create-validator-set-descriptor` + `verify-validator-set-descriptor`).
   - Collect protocol settlement take (`tau = 50 bps`) from all commercial activity, execute 100% protocol revenue burn, and evaluate demand-gated conditional mint (`M_t <= 0.5 * R_trailing`, 20/80 split).
2. **Commercial Subnets (Dynamic Spawning, Growth & Churn)**:
   - Spawned dynamically as Austin/LARG circles onboard (e.g., `bitecho.org/markets/larg-tac`, `bitecho.org/markets/midi-sim`, `bitecho.org/markets/clear-games`, `bitecho.org/markets/trustx-verify`, plus organic third-party commercial subnets entering when profit margin > entry hurdle).
   - Each commercial subnet registers its own directory pointer (`subnet/sign-directory-pointer`), instantiates its own `subnet/init-subnet-instance` with its own validator committee (including LARG Home/Validator nodes), populates itself with simulated Light Nodes carrying real pubkeys, and sets its own commercial fee/take policy (`tau_subnet`).
   - **Health & Churn Feedback**: Commercial subnets whose validator SLA drops (e.g., unguarded upgrade stalls or validator churn) lose buyer traffic and trigger SLA-gated vesting pauses for their sponsoring Genesis Partners; healthy subnets attract new Light Nodes and expand validator committees via real in-band rotation.

### 3.3 Workstream C — Endogenous Capability Market & Traffic Engine (~1.5–2 days)

**Namespace**: `bitecho.hive.sim.market` (pure `.cljc`)

Replaces the exogenous `demand-shock` knob with real agent-economy market dynamics (directly addressing Peter Stone's TAC wedge: a **real persistent market** with cryptographic settlement):
1. **Supply (Priced Agent Capabilities)**:
   - Simulated Light Nodes & Home Nodes advertise signed capability offers on their subnet directory:
     - `:capability-type` (`:llm-inference`, `:tts-kokoro`, `:stt-moonshine`, `:formal-verify`, `:inverse-game-eval`, `:tac-auction-agent`, `:artifact-storage`)
     - `:unit-price-ech` (adaptive pricing: providers raise price when utilization > 85%, lower toward marginal cost when utilization < 40%)
     - `:capacity-units-per-epoch`, `:marginal-cost-ech`, `:observed-sla-bps`, `:provider-pubkey`, `:subnet-id`
2. **Demand (Stochastic Traffic & Willingness-to-Pay)**:
   - Buyer Light Nodes (both internal research/builder agents and external organic demand cohorts growing with network reputation & commercial subnet count) generate task requests per epoch with `{:capability-type, :max-wtp-ech, :min-sla-bps, :budget-ech, :subnet-preference}`.
3. **Market Clearing & Real SCI-Vault Settlement**:
   - Per-subnet clearing engine matches eligible requests (`price <= max-wtp` and `sla >= min-sla`) ordered by utility (`alpha * sla - beta * price`, with congestion queueing when capacity saturates).
   - Every cleared batch settles through **real BIP-024 SCI-puzzle vaults / HTLC escrow**:
     1. Buyer locks `amount` into an HTLC/escrow vault (`vault/create-vault` or `spend-via-vault-dac`),
     2. Provider delivers result + receipt attestation (with probabilistic fault/timeout governed by provider & subnet SLA),
     3. Real `vault/spend-vault` executes `puzzle/eval-puzzle`: pays provider (`amount - fee`), burns `tau = 50 bps` protocol fee into `:permanently-burned`, refunds buyer on timeout/verification failure.
   - **Endogenous Feedback**: Actual cleared volume & burned fees `R_t` drive Tokenomics v2.1 (`M_t <= 0.5 * R_trailing`), provider profitability drives new Light Node entry/exit, and subnet revenue drives commercial subnet expansion or retirement (`circle/dissolve-circle`).

---

## 4. Major Protocol Gaps Already Highlighted (Zero-Scaffolding Audit)

Designing this simulation without shortcuts immediately exposed **6 concrete gaps** between `bitecho` `f1eef45c` and a full persistent agent economy:

| # | Gap Identified | Where It Shows Up | Recommended Resolution |
| :-: | :--- | :--- | :--- |
| **G1** | **Synthetic hex pubkeys in `mc_harness.cljc`** | `deterministic-spki-hex` bypasses real Ed25519 keygen/sign/verify | Use real seeded Ed25519 keypairs (`crypto/generate-keypair` with `fork-random`) in `bitecho.hive.sim.fabric` (benchmark: ~10k JVM sigs/sec is plenty for compressed runs). |
| **G2** | **No pure in-memory multi-node transport bus** | Light/Home/Infra routing currently lives in shell I/O (`shell/light_node.clj`, `shell/network.clj`) | Build pure Sans-IO `NetworkBus` in `bitecho.hive.sim.fabric` that invokes real `bitecho.core.subnet/reduce-subnet` and `bitecho.light-node.core` directly without shell I/O. |
| **G3** | **No canonical signed Capability Offer / Receipt schema on Directory** | BIP-024 has entity descriptors (`:purpose-cid`) & puzzles, but no standard capability-listing or task-receipt schema | Prototype pure EDN schemas in `bitecho.hive.sim.market` now, and feed the battle-tested schemas into a future **BIP-026 (Capability Market Primitives)**. |
| **G4** | **Vault-to-Light-Node balance sync contract** | `vault/spend-vault` updates `vaults-state` while `light-node.core` tracks `:balance`/`:spendable` from subnet ledger snapshots | Expose a clean pure ledger-projection step in `fabric` that maps finalized `vaults-state` + subnet ledger transfers into each Light Node's real state; flag any missing reducer hook back to `bitecho`. |
| **G5** | **No atomic Commercial Subnet Charter transaction** | Subnet creation currently composes 4 separate calls (`init-subnet-instance`, `create-entity-descriptor`, `create-validator-set-descriptor`, `sign-directory-pointer`) | Orchestrate all 4 real calls atomically in `lifecycle` and document the composite charter sequence as a candidate `bitecho` protocol helper. |
| **G6** | **No canonical SLA-gated Vesting SCI Puzzle** | `vault.cljc` has `apply-bootstrap-sunset` and spend DACs, but no built-in cliff + SLA-attestation vesting puzzle | Write a real canonical SCI puzzle evaluated by `bitecho.economy.puzzle/eval-puzzle` for Genesis Partner 1-yr cliff / 3-yr SLA-gated vesting. |

---

## 5. Invariants, Strict-TDD Properties & Falsifiers

Per `AGENTS.md` Rules 1–5, **all `clojure.test.check` `defspec`s are written before implementation**:

1. **`INV-1` (Zero-Shortcut Authenticity)**: Every simulated Light Node, Home Node, and Infra Node holds a real valid Ed25519 keypair (`circle/valid-spki-pubkey?`), and every descriptor, rotation, and vault spend passes real cryptographic verification (`verify-entity-descriptor`, `verify-validator-set-descriptor`, `puzzle/eval-puzzle`).
2. **`INV-2` (Exact 7-Bucket 42B ECHO Conservation)**: `vault/verify-7-bucket-conservation` holds on **every** epoch across Genesis, vesting cliffs, SLA pauses, commercial market clearing, protocol burns, demand-gated mints, and Year-3 bootstrap sunset burn (`sum == 42,000,000,000`, zero negative balances).
3. **`INV-3` (CRN Bit-Identical Determinism)**: Identical `{:seed :epochs :time-compression-factor :scenario}` produces bit-identical final state root CID, market clearing volume, and trace Merkle root (`bitecho.hive.trace` v2).
4. **`INV-4` (Time-Compression Consistency)**: All epoch constants (`cliff`, `vest`, `sunset`, `trailing-window`, `validator-activation-lead`) scale consistently via `mc-harness/scale-lifecycle-config`.
5. **`INV-5` (Commercial Subnet Fault Isolation)**: Halting or partitioning one commercial subnet pauses its sponsoring partner's SLA-gated vesting and diverts traffic to healthy subnets without stalling `bitecho.org/infra` or corrupting sibling commercial subnets.
6. **`INV-6` (Market Clearing & Escrow Conservation)**: For every cleared task, `buyer_debit == provider_credit + protocol_burn + subnet_fee + buyer_refund`, and failed/timed-out tasks refund 100% of escrowed ECH via `:refund-remainder`.
7. **`INV-7` (Zero Silent Failures)**: Every rejected descriptor, invalid signature, SLA-paused vest, dropped envelope, or uncleared order emits a counted `:diagnose` event surfaced in the run report.

### Performance & Scale Budget (Initial Target)
- **Scale per trial**: ~25–60 simulated Light/Home/Infra nodes across 2 Protocol Subnets + 4–8 Commercial Subnets, 36 compressed months (`C = 36..72`, i.e. `365–730` simulated epochs/trial), `20–50` CRN trials per scenario sweep.
- **Target runtime**: `< 15s` per single full-network trial on JVM with real Ed25519 signatures (verified by benchmark before locking `C`).

---

## 6. Review Questions for Feedback Round

Please reply with feedback, objections, or requested changes (BIP-style):
1. **@Nexus**: Are there any `bitecho` `f1eef45c` namespaces/reducers we should call directly instead of composing (especially around Gaps **G4** ledger-to-light-node sync and **G5** subnet chartering)? Should `alpeware/hive` depend on `alpeware/bitecho` via `:git/sha "f1eef45c..."` in `deps.edn`?
2. **@Axiom**: Does the cast (§2.2), G4 angel-heavy + SLA-gated Partner vesting split, and endogenous market clearing model (§3.3) capture the exact economic & strategic hypotheses needed for Austin/Seed-to-A decision-making? Any missing failure modes or falsifiers?
3. **@Lumen**: Do the advisory-twin guardrails (`INV-TW1`), commercial subnet visibility/dissolution semantics, and participant allocation fairness look right from a governance/sociocracy standpoint?
4. **@Simon**: Does the cast + real-primitives-only architecture + gap-surfacing plan match what you want? Any tweaks to the named Austin cast, capability categories, or commercial subnets before we lock Draft 2 and start Strict-TDD implementation?
