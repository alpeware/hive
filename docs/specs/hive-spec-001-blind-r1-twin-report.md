# HIVE-SPEC-001 — 5-Seat Blind Round-1 Digital-Twin Report (Advisory-Only)

- **Spec Reviewed**: `HIVE-SPEC-001 Draft 2` (`d02a619925288a61e2f6104132c35beae50f1917e7224d4f6e3ee559b63c5f60`, `alpeware/hive @ 8189cf7`)
- **Pinned Protocol Base Audited**: `alpeware/bitecho @ f1eef45cd92f976fb727dfc9c0aa5ab864c88d64` (BIP-024 live)
- **Date**: 2026-11-24
- **Governance Rule (`INV-TW1`)**: Strictly advisory. Five isolated `research` / `pro` twins ran in parallel with zero peer leakage and mandatory `:falsifier` per finding. Simulated consent/objection NEVER binds real governance.

---

## 1. Blind Round-1 Tally

| Seat (Blind Twin) | Verdict | High | Medium | Low | Key Unique Catches |
| :--- | :---: | :---: | :---: | :---: | :--- |
| `:simon-twin` | `:objection` | 6 | 2 | 0 | `:vault-create`/`:vault-spend` mismatch, `:advisory-twin-pubkeys` ownership deadlock, `<net>/id/<pk-cid>` vs hex `:ledger` key, G4 Angels circle + LARG `250k ECHO` stake seeding |
| `:axiom-twin` | `:objection` | 4 | 3 | 1 | Buyer `max_wtp = 10^9` queue-jumping in `U`, Partner vesting lump-sum/puzzle-rotation bypass, `1.26B` Core Agent custody vs `INV-C5`, Year-1 buyer liquidity / Edge FX loop, `max-receipts-per-block = 32` |
| `:nexus-twin` | `:objection` | 6 | 1 | 1 | **Streamlet validator-rotation deadlock (`state_machine.cljc:2508` + `lead=1` < 3-chain finality)**, `light-node` snapshot rejection after rotation, `subnet/sign-directory-pointer` `:self` proof rejected on `dir/*`, `deps.edn` `../bitecho/dev` + `clj-fressian` conflict |
| `:vector-twin` | `:objection` | 4 | 2 | 1 | **Per-call `(sci/init)` bottleneck (~328s/trial unbatched)**, 2-arity `encrypt-shared-descriptor` `SecureRandom`, `SplitMix64PRNG` atom aliasing + keyword-namespace collision in `branch-id->long`, `scale-lifecycle-config` missing cliffs/vests/TTLs + linear drawdown error |
| `:lumen-twin` | `:objection` | 5 | 2 | 0 | False `"opt-in"` attribution on external faculty names (`LUM-F2`), `:directory/encrypted-entity-envelope` rejected by `state_machine` + missing group-key rotation, `circle/dissolve-circle` `:custody-bucket` laundering past bootstrap sunset, SLA-vesting griefing + `:permissioned` subnet cartel risk |
| **Total (5/5 Blind)** | **5/5 `:objection`** | **25** | **10** | **3** | **10 consolidated codebase-verified blockers & protocol gaps caught pre-implementation** |

---

## 2. Consolidated Codebase-Verified Blockers & Protocol Gaps (Must Fold Into Implementation / BIP-026)

### A. Real `alpeware/bitecho @ f1eef45c` Protocol Gaps Exposed ("Zero-Scaffolding Audit")

1. **`P1` (`state_machine.cljc` vs `vault.cljc` Split-Brain — caught 5/5)**:
   - `execute-finalized-blocks` dispatches on `:vault-create` / `:vault-spend` (NOT `:create-vault`/`:spend-vault`); unknown receipt `:type`s fall through `(case (:type r) ... s)` **silently** with zero `:diagnose`.
   - `:apply-bootstrap-sunset` does not exist in `state_machine.cljc`; `apply-epoch-treasury-boundary` only burns legacy `[:ledger custody-pubkey]`, never `[:vaults "vault:root/bootstrap"]`.
   - `state_machine` `:release-intent`/`:burn` updates `[:epoch-settlement-takes]` / `[:epoch-burns]` without incrementing `:total-burned`, while `vault/spend-vault` updates `:total-burned` without updating `[:epoch-settlement-takes]` — breaking `vault/verify-7-bucket-conservation` on the very first `:release-intent`.
   - `validate-gossiped-receipt?` rejects all BIP-024 receipts (`:vault-*`, `:register-entity-descriptor`, `:rotate-validator-set`, `:dissolve-circle`).
2. **`P2` (Streamlet Validator-Rotation Consensus Deadlock + `lead < 3` — caught `:nexus-twin` `N4`, `N5`)**:
   - `state_machine.cljc:2508` only updates `:validators` when `(<= ep-eff b-epoch)` (always false because `ep-eff >= b-epoch + lead`), while `streamlet/handle-tick-event` reads static `(:validators state)` and `valid-block-proposal?` reads `:validator-set-history` → consensus deadlock at `epoch == ep-eff`.
   - `mc-harness/scale-lifecycle-config` clamps `:validator-activation-lead-epochs` to `1` for `C >= 12`, below Streamlet's 3-chain finality (`lead >= 3` required).
   - `light_node/handle-wire-chain-state-snapshot` resolves `:trusted-validators` strictly from `[:bootstrap :nodes]`, rejecting snapshots signed by rotated validators.
3. **`P3` (One-Shot Escrow `:release-intent` Cannot Credit `[:vaults ...]` + Double-Take — caught 5/5)**:
   - `puzzle/normalize-conditions` rejects `:allocate` (`:target-vault-id`) when `:vault-mode? false`, and `state_machine :release-intent` only credits `[:ledger pk]` (canonical 88-char hex pubkey, NOT `<net>/id/<pk-cid>` URI path) while applying `50 bps` (`min 1 ECHO`) per `:release` output.
   - `:refund-intent` on timeout returns `(:amount escrow)` directly (whereas `:refund-remainder` is only for partial `:release` before `expires-epoch`).
4. **`P4` (SLA-Gated Vesting SCI Puzzle Missing Attested `env-ctx` & Relock Binding — caught 4/5)**:
   - `vault/spend-vault` `env-ctx` does NOT bind `:sla-score`; boolean puzzles fail closed in `:vault-mode? true` (`:vault-requires-explicit-conditions`). Must return explicit `:release`/`:relock` condition maps summing to `(:amount env)` with `(= next-puzzle-hash (:puzzle-hash env))` relock binding + oracle-attested SLA signature (`verify-sig`) over `(:spend-digest env)` (or extend `vault.cljc` `env-ctx` in BIP-026).
5. **`P5` (`circle/dissolve-circle` Custody-Bucket Laundering & `:shared-encrypted` Envelope Gap — caught `:lumen-twin`, `:nexus-twin`, `:axiom-twin`)**:
   - `circle/dissolve-circle` sets parent `:custody-bucket` to `(or (:custody-bucket p-vault) (:custody-bucket c-vault))` without checking equality (allowing `:bootstrap` principal to evade epoch 26,280 sunset burn).
   - `circle/encrypt-shared-descriptor` returns `:directory/encrypted-entity-envelope`, which `state_machine :register-entity-descriptor` rejects (`:invalid-descriptor-type`).
   - `subnet/sign-directory-pointer` produces `:self` proof rejected by `data/verify-pointer` on `dir/*` (must use `data/create-directory-entity-proof`).

### B. Simulation Design & Implementation Fixes (Baked Directly Into Workstreams A → B → C)

6. **`S1` (Separate Operational Sim Keys vs Shadow `:advisory-twin-pubkeys` + Privacy/Attribution — caught 4/5)**:
   - Operational simulation actor keys (bound to a simulation-only `:network-genesis-cid`) MUST be strictly distinct from shadow `:advisory-twin-pubkeys` (tested negatively to prove twins can NEVER own entities or vote in `verify-entity-descriptor` / `verify-consent-round` / SCI puzzles).
   - Remove `"opt-in"` wording for external Austin faculty; stamp explicit `{:opt-in? false :endorsed? false :synthetic-archetype? true}` and keep real names out of signed wire descriptors/offers.
   - Hold Core AI Agents' `1.26B ECHO` in a founding multisig `vault:root/core-agent-ops` DAC rather than personal `<net>/id/<pk-cid>` roots.
   - Route earned builder revenue to canonical 88-char hex pubkeys in `[:ledger pk]` linked to sovereign `<net>/id/<pk-cid>` descriptors.
7. **`S2` (Root-Contained Paths, Per-Partner Isolation, G4 Angels Circle & LARG Stake Seeding — caught 4/5)**:
   - Root all org circles under `root/...` (`root/founding`, `root/austin/advisors`, `root/austin/research`, `root/austin/larg-builders`, `root/markets/*`).
   - Give each Genesis Partner an isolated vesting sub-vault with explicit SLA/milestone predicate (preventing collective punishment across unrelated subnets).
   - Instantiate the `:shared-encrypted` G4 Angels circle (M4–9 onboarding, M10–18 seed close, 2-of-2 vs 2-of-3 anchor sweep) and seed LARG commercial validators with initial `>= 250,000 ECHO` stake + working capital.
8. **`S3` (Workstream C Market Mechanism & Liquidity Closure — caught `:axiom-twin`, `:simon-twin`, `:lumen-twin`)**:
   - Fix clearing utility to prevent `max_wtp = 10^9` queue-jumping: rank by normalized surplus / market-reference price (`U = alpha * (attested_sla_bps / 10000.0) - beta * (unit_price_ech / max(1, ref_price_ech))`), deriving `attested_sla_bps` strictly from verified on-chain escrow release/(release+timeout) EMA.
   - Close Year-1 buyer liquidity via explicit Edge FX fiat-to-ECHO buyer replenishment (`TOKENOMICS.md §7.2`) + prosumer recycling, and explicitly test Sybil whitewashing, wash-trade ROI, and post-sunset demand cliff.
9. **`S4` (CRN Determinism, Time-Compression `C` & `< 15s/trial` `sci/init` Budget — caught `:vector-twin`, `:nexus-twin`, `:axiom-twin`)**:
   - Always pass `:nonce-bytes` to 3-arity `circle/encrypt-shared-descriptor`; enforce immutable parent-RNG + canonical string-only `fork-random` paths (`"subnet/<uri>/ep/<ep>/demand"`).
   - Scale all cliff (`8,760`), vest (`26,280`/`35,040`), `escrow-ttl` (`168`), `recovery-timelock` (`168`), and `human-anchor-sunset` constants cleanly, use exact compound drawdown `1 - (1 - lambda)^C`, and keep `validator-activation-lead-epochs >= 3`.
   - Settle Workstream C via **bounded per-provider-epoch batch escrows** (controlling `(sci/init)` count & `max-receipts-per-block = 32` distortion) and run the `< 15s/trial` JVM benchmark gate across BOTH Workstream A + C!
10. **`S5` (`alpeware/hive/deps.edn` Pin Hygiene — caught `:nexus-twin`, `:vector-twin`, `:simon-twin`)**:
    - Pin `alpeware/bitecho` via `:git/sha "f1eef45cd92f976fb727dfc9c0aa5ab864c88d64"` in `deps.edn`, remove stale `"../bitecho/dev"` from `:paths`, and align `alpeware/clj-fressian` to `64caf46ed1e6957b9d3e7488f7acedeaa93c9878`.

---

## 3. Execution Plan

With **HIVE-SPEC-001 Draft 2 RATIFIED 5/5** (`seq 653`) and all 5 blind twin findings documented above:
1. Fold **`S1–S5`** directly into our Strict-TDD implementation of **Workstream A (`bitecho.hive.sim.fabric`) → Workstream B (`bitecho.hive.sim.lifecycle`) → Workstream C (`bitecho.hive.sim.market`)**.
2. Track **`P1–P5`** as verified protocol-gap falsifiers (reproducing each gap loudly on `f1eef45c` and handing Nexus the exact list for a follow-up `bitecho` patch / **BIP-026**).
3. Run a second blind twin pass on the **patch + telemetry** once Workstreams A–C land, per Simon's instruction.
