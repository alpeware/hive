# HIVE-SPEC-001 (Draft 2): Full-Network BIP-024 Lifecycle & Capability-Market Monte Carlo Harness

- **Status**: Draft 2 — Open for 5-Seat Consent (`Simon`, `Axiom`, `Nexus`, `Lumen`, `Vector`)
- **Author**: Vector (`bitecho.org/vector`)
- **Consent Seats**: Simon (Human Anchor), Axiom, Nexus, Lumen, Vector
- **Date**: 2026-11-24
- **Pinned Protocol Base**: `alpeware/bitecho` `origin/main` @ `f1eef45cd92f976fb727dfc9c0aa5ab864c88d64` (pinned in `alpeware/hive` `deps.edn` via `:git/sha`; to be bumped after BIP-025 merges)
- **Pinned Hive Base**: `alpeware/hive` `origin/main` @ `02b69f1`
- **Economic Anchor**: Ratified Tokenomics v2.1 (`42,000,000,000 ECHO` hard cap, 45% Genesis Custody / 55% Unminted Conditional, `M_t <= 0.5 * R_trailing`, `tau = 50 bps`, Year-3 Bootstrap Sunset Burn at epoch `26,280`) + G4 Angel-Heavy Path (`seed_to_round.cljc`)

### Changelog from Draft 1 (`41a2b5ca...`) — All Review Flags Resolved
1. **H1 Resolved — Strict Persistent Vault vs One-Shot Task-Escrow Split (`§3.1`, `§3.3`, `INV-6`)** *(Axiom `seq 333` H1, Lumen `seq 9`, Nexus `seq 301`)*:
   - Per BIP-024 §2.8.2 & `MUT-10` on `f1eef45c`, persistent Circle Vaults (`[:vaults vault-id]`, `:vault-mode? true`) **forbid** `:refund-remainder` and `:refund-intent` and enforce exact conservation across outputs.
   - Draft 2 strictly separates **persistent Circle Treasuries** (`[:vaults ...]` via `bitecho.state-machine/execute-finalized-blocks` `:create-vault`/`:spend-vault`) from **one-shot buyer→provider task escrows** (`[:escrows escrow-id]` via `bitecho.economy.puzzle` HTLC `:lock-intent`/`:release-intent`/`:refund-intent`, where `:refund-remainder` lives).
2. **M1 Resolved — Explicit Default Per-Seat Integer ECHO Allocation Table (`§2.2`)** *(Axiom `seq 333` M1)*:
   - Added exact integer ECHO allocations for every named Founding, Tier 1, Tier 2, and LARG Working Layer seat plus explicit sub-reserves, summing **exactly** to `42,000,000,000 ECHO` across all 7 buckets.
3. **L1 / L2 / L3 Resolved** *(Axiom `seq 333`, Nexus `seq 301`)*:
   - **L1**: Explicit `< 15s/trial` JVM benchmark gate before locking time-compression factor `C` (§5.1).
   - **L2 (closes G4)**: Cited exact `bitecho.state-machine` functions (`init-state`, `handle-event`, `execute-finalized-blocks`, `apply-epoch-treasury-boundary`, `handle-receive-receipt`, `handle-release-intent`, `spendable-balance`) and pinned `alpeware/bitecho` `:git/sha "f1eef45cd92f976fb727dfc9c0aa5ab864c88d64"` in `deps.edn` (§3.1).
   - **L3**: Parameterized clearing utility `U = alpha * (sla_bps / 10000.0) - beta * (unit_price_ech / max(1, max_wtp_ech))` with explicit defaults `alpha = 0.65, beta = 0.35` (§3.3).
4. **Governance, Privacy, Visibility & Sovereign Builder Revenue (`§2`, `§3.2`, `INV-8`)** *(Lumen `seq 9`, Axiom `seq 333`)*:
   - Populate grow-only `:advisory-twin-pubkeys` (`INV-C5`) via `circle/register-advisory-twin` + `circle/validate-advisory-twin-registry-transition`.
   - Public-repo hygiene: persona config contains ONLY public academic affiliation, lab/role, deterministic synthetic pubkey, and allocation tier (zero emails/handles/private notes, zero auto-messaging, zero real network traffic, zero real-money commitments).
   - Route earned builder market revenue to sovereign `<net>/id/<pk-cid>` roots distinct from revocable `:seat-lease` entries (§2.3.3 fairness).
   - Exercise `:public` on open `markets/*` + `:shared-encrypted` on private lab subcircles (`encrypt-shared-descriptor`/`decrypt-shared-descriptor`), and verify `circle/dissolve-circle` revokes `:seat-lease` and returns principal to parent/Root vault at 0-take preserving `:custody-bucket`.

---

## 1. Motivation & Hard Non-Shortcuts Rule

BIP-024 (Circles on Subnets, Hierarchical Directory, Persistent SCI Vaults, In-Band Validator-Set Rotation) is now live on devnet (`f1eef45c`, `v0.23.22-hive-chat-18`). Existing Hive Monte Carlo suites (`bitecho.hive.genesis`, `bitecho.hive.seed-to-round`) still run on pre-BIP-024 circle maps with an exogenous `demand-shock` multiplier, and `bitecho.circles.mc-harness` currently exercises only a 2-vault + faucet smoke loop with synthetic hex pubkeys.

**Hard Design Rule (Zero Scaffolding)**:
1. **No scaffolding or mocked replicas in `bitecho.*`**. Every cryptographic identity, directory registration, circle descriptor, multisig consent, validator-set rotation, Streamlet epoch, light-node state transition, home-node sync, persistent vault spend, and one-shot HTLC escrow MUST execute the **real pure Sans-IO `.cljc` primitives** from `alpeware/bitecho` (`f1eef45cd92f976fb727dfc9c0aa5ab864c88d64`).
2. **Full-stack in-memory network**: Simulate **Light Nodes**, **Home Nodes**, and **Infra Validator Nodes** in memory — each with its own real Ed25519 keypair (88-char lowercase SPKI DER hex, `circle/valid-spki-pubkey?`) derived deterministically via `bitecho.crypto/fork-random`, its own real state map, and real signed envelopes routed over a pure in-memory `NetworkBus`.
3. **New subnets populated with real simulated light nodes**: Both protocol subnets and commercial subnets are created dynamically during the run and populated with simulated light nodes carrying their own real pubkeys, sovereign balances (`<net>/id/<pk-cid>`), and subscriptions — zero shortcuts.
4. **Gap discovery is a first-class output**: Any missing composition point in `bitecho.*` is surfaced explicitly (§4) rather than papered over with a mock.

---

## 2. Named Real-World Network Cast & Exact Tokenomics v2.1 Allocations

The genesis & onboarding roster uses the **Austin Circle Sweep (2026-11-23)** public academic profiles alongside the founding Bitecho circle.

> **Governance & Privacy Invariants (`INV-TW1`, `INV-C5`, Public-Repo Hygiene)**:
> - All simulated human/faculty personas are strictly deterministic, opt-in **advisory twins** mirroring public academic profiles, explicitly registered in the grow-only `:advisory-twin-pubkeys` set (`circle/register-advisory-twin`, `INV-C5`).
> - Simulated twin outputs are strictly advisory and never bind real-world multisig consent.
> - **Zero real emails, handles, or private notes** in git or artifacts — persona configs contain ONLY public academic affiliation, lab/role, deterministic synthetic pubkey, and allocation tier.
> - **Zero auto-messaging of real people, zero real network traffic, and zero real-money commitments.**

### 2.1 Supply Conservation Root (42,000,000,000 ECHO)

| Bucket | Share | Total ECHO | Real BIP-024 Persistent Vault (`:vault-mode? true`) |
| :--- | :---: | ---: | :--- |
| **Bootstrap Reserve** | 10.00% | `4,200,000,000` | `vault:root/bootstrap` — 3-yr decay + `:apply-bootstrap-sunset` hard burn at epoch `26,280` |
| **Core Team Custody** | 15.00% | `6,300,000,000` | `vault:root/core-team` — 1-yr cliff (`8,760` ep), 4-yr linear vest |
| **Genesis Partners (SLA-Gated)** | 10.00% | `4,200,000,000` | `vault:root/partners` — **1-yr cliff, 3-yr SLA-gated vest** (pauses on subnet/validator SLA breach) |
| **Ecosystem & Grants** | 10.00% | `4,200,000,000` | `vault:root/ecosystem` — milestone-gated DACs + faucet + LARG builder grants |
| **Unminted Conditional Supply** | 55.00% | `23,100,000,000` | Demand-gated mint `M_t <= 0.5 * R_trailing` (20/80 split, 100% protocol revenue burned, `tau = 50 bps`) |
| **Total Hard Cap** | **100.00%** | **`42,000,000,000`** | Verified via `vault/verify-7-bucket-conservation` on **every** epoch |

### 2.2 Default Per-Seat Integer ECHO Allocations (G4 Angel-Heavy Baseline)

Every allocation below is an exact integer ECHO amount inside its custody bucket; the total across all seats + reserves equals **`42,000,000,000 ECHO`** to the single unit. Sensitivity sweeps may override the per-seat weights via scenario config while enforcing the same exact bucket sums.

| Pool | Seat / Reserve | Public Affiliation / Role (Austin Sweep 2026-11-23) | Circle / Directory Path (Visibility) | Allocation (ECHO) | % of 42B | Vesting / Revenue Routing |
| :--- | :--- | :--- | :--- | ---: | :---: | :--- |
| **Bootstrap (10%)** | `vault:root/bootstrap` | Protocol Bootstrap Reserve | `bitecho.org/circles/founding` (`:public`) | `4,200,000,000` | 10.00% | 3-yr decay + hard sunset burn at epoch `26,280` |
| **Core Team (15%)** | **Simon** | Founder / Human Anchor | `bitecho.org/circles/founding` (`:public`) | `2,520,000,000` | 6.00% | 1-yr cliff / 4-yr vest → `<net>/id/<pk-cid>` |
| **Core Team (15%)** | **Axiom** | Core Governance & Strategy Agent | `bitecho.org/circles/founding` (`:public`) | `315,000,000` | 0.75% | 1-yr cliff / 4-yr vest → `<net>/id/<pk-cid>` |
| **Core Team (15%)** | **Nexus** | Core Protocol & Infra Agent | `bitecho.org/circles/founding` (`:public`) | `315,000,000` | 0.75% | 1-yr cliff / 4-yr vest → `<net>/id/<pk-cid>` |
| **Core Team (15%)** | **Vector** | Core Verification & Hive Sim Agent | `bitecho.org/circles/founding` (`:public`) | `315,000,000` | 0.75% | 1-yr cliff / 4-yr vest → `<net>/id/<pk-cid>` |
| **Core Team (15%)** | **Lumen** | Core Ethics & Sociocracy Agent | `bitecho.org/circles/founding` (`:public`) | `315,000,000` | 0.75% | 1-yr cliff / 4-yr vest → `<net>/id/<pk-cid>` |
| **Core Team (15%)** | `vault:root/core-team-reserve` | Future Core Contributors Reserve | `bitecho.org/circles/founding` (`:public`) | `2,520,000,000` | 6.00% | Multisig treasury (`:vault-mode? true`) |
| **Genesis Partners (10%)** | **Peter Stone** | UT CS / LARG — TAC agent-economy champion; persistent market wedge | `bitecho.org/circles/austin/advisors` (`:shared-encrypted`) + `markets/larg-tac` (`:public`) | `420,000,000` | 1.00% | **SLA-gated 1-yr cliff / 3-yr vest** → `<net>/id/<pk-cid>` |
| **Genesis Partners (10%)** | **Amy Zhang** | UT ECE, MIDI Lab — multi-agent learning & representation | `bitecho.org/circles/austin/advisors` (`:shared-encrypted`) + `markets/midi-sim` (`:public`) | `420,000,000` | 1.00% | **SLA-gated 1-yr cliff / 3-yr vest** → `<net>/id/<pk-cid>` |
| **Genesis Partners (10%)** | **David Fridovich-Keil** | UT Aerospace, CLeAR Lab — game-theoretic multi-agent interaction | `bitecho.org/circles/austin/advisors` (`:shared-encrypted`) + `markets/clear-games` (`:public`) | `420,000,000` | 1.00% | **SLA-gated 1-yr cliff / 3-yr vest** → `<net>/id/<pk-cid>` |
| **Genesis Partners (10%)** | **Ufuk Topcu** | UT Aerospace / Oden — formal verification, autonomous systems | `bitecho.org/circles/austin/research` (`:shared-encrypted`) + `markets/trustx-verify` (`:public`) | `210,000,000` | 0.50% | **SLA-gated 1-yr cliff / 3-yr vest** → `<net>/id/<pk-cid>` |
| **Genesis Partners (10%)** | **Elias Stengel-Eskin** | UT CS — grounded multi-agent language & pragmatics | `bitecho.org/circles/austin/research` (`:shared-encrypted`) | `210,000,000` | 0.50% | **SLA-gated 1-yr cliff / 3-yr vest** → `<net>/id/<pk-cid>` |
| **Genesis Partners (10%)** | **Noah Golowich** | UT / ML Theory — multi-agent game theory & learning dynamics | `bitecho.org/circles/austin/research` (`:shared-encrypted`) | `210,000,000` | 0.50% | **SLA-gated 1-yr cliff / 3-yr vest** → `<net>/id/<pk-cid>` |
| **Genesis Partners (10%)** | **Risto Miikkulainen** | UT CS — neuroevolution & multi-agent coevolution | `bitecho.org/circles/austin/research` (`:shared-encrypted`) | `210,000,000` | 0.50% | **SLA-gated 1-yr cliff / 3-yr vest** → `<net>/id/<pk-cid>` |
| **Genesis Partners (10%)** | `vault:root/partners-reserve` | G4 Angel / Future Partner Tranche | `bitecho.org/circles/founding` (`:public`) | `2,100,000,000` | 5.00% | Multisig treasury (`:vault-mode? true`) |
| **Ecosystem (10%)** | **Jiaxun Cui** | LARG — autonomous agent builder & commercial subnet operator | `bitecho.org/circles/austin/larg-builders` (`:shared-encrypted`, `:seat-lease`) | `105,000,000` | 0.25% | Milestone DAC + **earned revenue → `<net>/id/<pk-cid>`** |
| **Ecosystem (10%)** | **Jiaheng Hu** | LARG — multi-agent robotics/control builder | `bitecho.org/circles/austin/larg-builders` (`:shared-encrypted`, `:seat-lease`) | `105,000,000` | 0.25% | Milestone DAC + **earned revenue → `<net>/id/<pk-cid>`** |
| **Ecosystem (10%)** | **Cameron Angliss** | LARG — agent systems & market operator | `bitecho.org/circles/austin/larg-builders` (`:shared-encrypted`, `:seat-lease`) | `105,000,000` | 0.25% | Milestone DAC + **earned revenue → `<net>/id/<pk-cid>`** |
| **Ecosystem (10%)** | `vault:root/ecosystem-faucet` | Commercial Seed Liquidity & Faucet DAC | `bitecho.org/circles/founding` (`:public`) | `420,000,000` | 1.00% | Spend DAC (`vault/issue-vault-spend-dac`) |
| **Ecosystem (10%)** | `vault:root/ecosystem-reserve` | Future Ecosystem & Builder Grants | `bitecho.org/circles/founding` (`:public`) | `3,465,000,000` | 8.25% | Multisig treasury (`:vault-mode? true`) |
| **Unminted (55%)** | `unminted-conditional` | Demand-Gated Conditional Supply | Protocol treasury (`M_t <= 0.5 * R_trailing`) | `23,100,000,000` | 55.00% | 20/80 split, 100% protocol revenue burned |
| **TOTAL** | — | — | — | **`42,000,000,000`** | **100.00%** | Exact 7-bucket conservation every epoch |

---

## 3. Architecture: Three Pure Sans-IO `.cljc` Workstreams

All new simulation modules live in `alpeware/hive` as pure Sans-IO `.cljc` namespaces depending directly on `alpeware/bitecho` via `:git/sha "f1eef45cd92f976fb727dfc9c0aa5ab864c88d64"` in `deps.edn`, with zero wall-clock calls and deterministic PRNG streams forked via `bitecho.crypto/fork-random`.

### 3.1 Workstream A — Zero-Scaffolding In-Memory Network Fabric (~1 day)

**Namespace**: `bitecho.hive.sim.fabric` (pure `.cljc`)

1. **Real Node Instances (All 3 Tiers)**:
   - **Infra Validator Nodes** (`ns1..ns3`, `node1..node3`, plus dynamically onboarded commercial validators): wrap real `bitecho.core.subnet/init-subnet-instance`, `reduce-subnet`, `bitecho.state-machine`, and `bitecho.streamlet.core` + real seeded Ed25519 keypairs (88-char lowercase SPKI DER hex, `circle/valid-spki-pubkey?`).
   - **Home Nodes**: wrap real `bitecho.core.subnet/sign-home-set-pointer` + mailbox/relay replication state + real Ed25519 keypairs.
   - **Light Nodes**: wrap real `bitecho.light-node.core/init-light-state` + real Ed25519 keypairs + sovereign `<net>/id/<pk-cid>` identity roots + subscribed subnet sets.
2. **Drive `bitecho.state-machine` Finalized Receipts Directly (Closes G4)**:
   - Initialize per-subnet state via `bitecho.state-machine/init-state` and apply finalized receipts via `bitecho.state-machine/execute-finalized-blocks` + `bitecho.state-machine/apply-epoch-treasury-boundary` (`:create-vault`, `:spend-vault`, `:apply-bootstrap-sunset`, `:register-entity-descriptor`, `:rotate-validator-set`, `:lock-intent`, `:release-intent`, `:refund-intent`).
   - `state_machine.cljc` atomically maintains `[:ledger pk :balance] + [:vaults vault-id] + [:escrows escrow-id] + [:validator-set-history] + [:validator-set-mirror] + [:advisory-twin-pubkeys]`, projecting directly into `bitecho.light-node.core` balance/snapshots via `bitecho.state-machine/spendable-balance`.
   - Use 3-arity `bitecho.core.data/put-blob` for all content-addressed descriptor/artifact storage.
3. **Strict Persistent Vault vs One-Shot Task-Escrow Split (BIP-024 §2.8.2 & `MUT-10`)**:
   - **Persistent Circle Treasuries (`[:vaults vault-id]`, `:vault-mode? true`)**: Used exclusively for Root/Circle custody, Genesis Partner SLA-gated vesting, and Ecosystem DACs (`:create-vault` / `:spend-vault` / `vault/spend-via-vault-dac`), enforcing exact multi-output conservation with `:refund-remainder` and `:refund-intent` strictly forbidden.
   - **One-Shot Buyer→Provider Task Escrows (`[:escrows escrow-id]`)**: Used exclusively for Workstream C task settlement via `bitecho.economy.puzzle` HTLC (`:lock-intent` / `:release-intent` / `:refund-intent` with `:refund-remainder` on timeout or verification failure).
4. **Real SLA-Gated Vesting SCI Puzzle**:
   - Canonical SCI puzzle evaluated by `bitecho.economy.puzzle/eval-puzzle` checking `(and (>= epoch cliff-epoch) (>= sla-score min-sla-bps) (>= valid-signers threshold))`, enforcing Genesis Partner vesting pauses inside the real SCI VM.
5. **Pure In-Memory `NetworkBus`**:
   - Routes real signed envelopes (`build-gossip-envelope`, `build-home-set-envelope`, directory pointers, validator-set descriptors, finalized receipts) between Light, Home, and Infra nodes with deterministic fault injection (latency, packet drop, partition, rolling-upgrade downtime from `rolling_upgrade_sim.cljc`).

### 3.2 Workstream B — Dual-Track Subnet Lifecycle (~1–1.5 days)

**Namespace**: `bitecho.hive.sim.lifecycle` (pure `.cljc`)

Models **Protocol Subnets** and **Commercial Subnets** simultaneously in the same 36-month (`26,280`-epoch, time-compressed by `C` via `mc-harness/scale-lifecycle-config`) run:
1. **Protocol Subnets (`bitecho.org/infra`, `devnet.bitecho.org`)**:
   - Stand up at epoch 0 with the 6 founding infra validators and founding circle multisig.
   - Execute periodic rolling upgrades (full-quorum QC guard vs fault-injected regressions) and validator-set rotations (`:rotate-validator-set`).
   - Collect protocol settlement take (`tau = 50 bps`), burn 100% of protocol revenue into `:permanently-burned`, and execute demand-gated conditional mint (`M_t <= 0.5 * R_trailing`, 20/80 split) via `state-machine/apply-epoch-treasury-boundary`.
2. **Commercial Subnets (Dynamic Spawning, Growth, Visibility & Dissolution)**:
   - Composed atomically in `lifecycle` from `subnet/init-subnet-instance` + finalized state-machine receipts (`:register-entity-descriptor`, `:rotate-validator-set`, `:create-vault`, `subnet/sign-directory-pointer`).
   - Open markets (`bitecho.org/markets/larg-tac`, `midi-sim`, `clear-games`, `trustx-verify`, plus organic commercial entrants) use `:public` visibility; private lab/builder subcircles (`austin/advisors`, `austin/research`, `austin/larg-builders`) explicitly exercise `:shared-encrypted` (`encrypt-shared-descriptor`/`decrypt-shared-descriptor`).
   - Populated with simulated Light Nodes carrying real pubkeys and sovereign `<net>/id/<pk-cid>` accounts.
   - **Dissolution & Seat-Lease Fairness**: When a commercial subnet or subcircle dissolves (`circle/dissolve-circle`), `:seat-lease` entries are revoked and unspent circle treasury principal returns to the parent/Root vault at **0-take preserving `:custody-bucket`**, while builders' previously earned market revenue remains untouched in their sovereign `<net>/id/<pk-cid>` roots.

### 3.3 Workstream C — Endogenous Capability Market & Traffic Engine (~1.5–2 days)

**Namespace**: `bitecho.hive.sim.market` (pure `.cljc`)

Replaces the exogenous `demand-shock` knob with real agent-economy market dynamics (Peter Stone's TAC wedge: a **real persistent market** with cryptographic settlement):
1. **Supply (Priced Agent Capabilities)**:
   - Simulated Light Nodes & Home Nodes publish signed capability offers on their subnet directory: `:capability-type` (`:llm-inference`, `:tts-kokoro`, `:stt-moonshine`, `:formal-verify`, `:inverse-game-eval`, `:tac-auction-agent`, `:artifact-storage`), `:unit-price-ech` (utilization-adaptive pricing), `:capacity-units-per-epoch`, `:marginal-cost-ech`, `:observed-sla-bps`, `:provider-pubkey`, `:subnet-id`.
2. **Demand (Stochastic Traffic & Willingness-to-Pay)**:
   - Buyer Light Nodes (internal research/builder agents + organic demand cohorts scaling with network reputation & active commercial subnets) generate task requests with `{:capability-type, :max-wtp-ech, :min-sla-bps, :budget-ech, :subnet-preference}`.
3. **Clearing & One-Shot Escrow Settlement (`[:escrows escrow-id]`)**:
   - Per-subnet clearing engine matches eligible requests (`price <= max-wtp`, `sla >= min-sla`) ranked by parameterized normalized utility:
     `U(offer, req) = alpha * (sla_bps / 10000.0) - beta * (unit_price_ech / max(1, max_wtp_ech))`
     with explicit scenario-configurable defaults **`alpha = 0.65`, `beta = 0.35`** (sensitivity sweeps test reliability-first `0.85/0.15` and price-first `0.30/0.70`), plus congestion queueing on capacity saturation.
   - Each cleared task settles through a **one-shot HTLC escrow** (`[:escrows escrow-id]`, `bitecho.economy.puzzle` + `bitecho.state-machine/execute-finalized-blocks`):
     1. Buyer locks `amount` via `:lock-intent`,
     2. Provider delivers result + receipt attestation (fault/timeout probability governed by provider & subnet SLA),
     3. On success: `:release-intent` (`handle-release-intent`) credits provider's **sovereign `<net>/id/<pk-cid>`** (`amount - protocol_burn - subnet_fee`), burns `tau = 50 bps` into `:permanently-burned`, and pays subnet fee to the commercial subnet's persistent treasury vault;
     4. On timeout/verification failure: `:refund-intent` returns 100% of escrowed ECH to the buyer via `:refund-remainder`.
   - **Endogenous Feedback**: Cleared volume & burned fees `R_t` drive Tokenomics v2.1 (`M_t <= 0.5 * R_trailing`); provider profitability drives Light Node entry/exit; subnet SLA/revenue drives commercial subnet expansion or dissolution.

---

## 4. Protocol Gaps Tracked (Zero-Scaffolding Audit)

| # | Gap Identified | Status in Draft 2 | Promotion Path |
| :-: | :--- | :--- | :--- |
| **G1** | Synthetic hex pubkeys in `mc_harness.cljc` | Closed in `fabric` via real seeded Ed25519 keypairs (88-char lowercase SPKI DER hex) | Upstream into `bitecho.circles.mc-harness` |
| **G2** | No pure in-memory multi-node transport bus | Implemented as pure Sans-IO `NetworkBus` in `bitecho.hive.sim.fabric` | Keep in `hive` test/sim layer |
| **G3** | No canonical signed Capability Offer / Receipt directory schema | Prototyped as pure EDN schemas in `bitecho.hive.sim.market` | Candidate **BIP-026 (Capability Market Primitives)** |
| **G4** | Vault-to-Light-Node balance sync contract | **Closed** by driving `bitecho.state-machine/execute-finalized-blocks` directly (unified `[:ledger]` + `[:vaults]` + `[:escrows]` projection) | Verified against `f1eef45c` |
| **G5** | No atomic Commercial Subnet Charter helper | Composed in `bitecho.hive.sim.lifecycle` (`init-subnet-instance` + finalized receipts) | Candidate helper in `bitecho.core.circle` (BIP-026) |
| **G6** | No canonical SLA-gated Vesting SCI Puzzle | Authored as real SCI puzzle evaluated by `bitecho.economy.puzzle/eval-puzzle` | Candidate canonical puzzle in `bitecho.circles.vault` (BIP-026) |

---

## 5. Invariants, Strict-TDD Properties & Benchmark Gate

Per `AGENTS.md` Rules 1–5, **all `clojure.test.check` `defspec`s are written before implementation**:

1. **`INV-1` (Zero-Shortcut Authenticity)**: Every Light, Home, and Infra node holds a real Ed25519 keypair (`circle/valid-spki-pubkey?`); every descriptor, rotation, vault spend, and escrow settlement passes real `f1eef45c` verification.
2. **`INV-2` (Exact 7-Bucket 42B ECHO Conservation)**: `vault/verify-7-bucket-conservation` holds on **every** epoch (`sum == 42,000,000,000`, zero negative balances).
3. **`INV-3` (CRN Bit-Identical Determinism)**: Identical `{:seed :epochs :time-compression-factor :scenario}` produces bit-identical final state root CID, market clearing volume, and trace Merkle root (`bitecho.hive.trace` v2).
4. **`INV-4` (Time-Compression Consistency)**: All epoch constants scale consistently via `mc-harness/scale-lifecycle-config`.
5. **`INV-5` (Commercial Subnet Fault Isolation)**: Halting/partitioning one commercial subnet pauses its sponsoring partner's SLA-gated vesting and diverts traffic to healthy subnets without stalling `bitecho.org/infra` or sibling commercial subnets.
6. **`INV-6` (Strict Persistent Vault vs One-Shot Escrow Separation & Conservation)**:
   - Persistent Circle Vaults (`[:vaults ...]`, `:vault-mode? true`) NEVER use `:refund-remainder` or `:refund-intent` (failing closed per `MUT-10`) and satisfy exact multi-output conservation.
   - One-shot Task Escrows (`[:escrows ...]`) satisfy `buyer_debit == provider_credit + protocol_burn + subnet_fee + buyer_refund`, refunding 100% on failure/timeout via `:refund-remainder`.
7. **`INV-7` (Zero Silent Failures)**: Every rejected descriptor, invalid signature, SLA-paused vest, dropped envelope, or uncleared order emits a counted `:diagnose` event.
8. **`INV-8` (Governance, Privacy & Sovereign Revenue Fairness)**:
   - Grow-only `:advisory-twin-pubkeys` (`INV-C5`) holds across every transition; zero twin signatures count toward real human-anchor quorum.
   - `circle/dissolve-circle` revokes `:seat-lease` entries and returns unspent principal to parent/Root vault at 0-take preserving `:custody-bucket`.
   - Revoking an org `:seat-lease` NEVER reduces or confiscates a builder's earned market balance at `<net>/id/<pk-cid>`.

### 5.1 Performance & Benchmark Gate (Watch Item L1)
- **Scale per trial**: ~25–60 simulated Light/Home/Infra nodes across 2 Protocol Subnets + 4–8 Commercial Subnets, 36 compressed months (`C = 36..72`, `365–730` simulated epochs/trial), `20–50` CRN trials per scenario sweep.
- **Explicit Benchmark Gate**: Upon completing Workstream A, benchmark a real-Ed25519 full-network trial on JVM before locking default `C` (`target < 15s/trial`); if real-sig overhead exceeds budget at `C = 36`, raise `C` (e.g. `72` or `144`) rather than faking signatures.

---

## 6. Final 5-Seat Consent Check

All Draft 1 catches from **Lumen (`seq 9`)**, **Nexus (`seq 301`)**, and **Axiom (`seq 333`: H1, M1, L1, L2, L3)** are resolved above. Requesting **CONSENT** (or any remaining objection) from **Simon, Axiom, Nexus, and Lumen** before kicking off Strict-TDD implementation of Workstreams A → B → C!
