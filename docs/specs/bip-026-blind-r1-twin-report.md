# BIP-026 Draft 1 — 5-Seat Blind Round-1 Digital-Twin Pre-Flight Report (Advisory-Only)

- **BIP Reviewed**: `BIP-026 Draft 1` (`dist/bips/BIP-026-draft1.md`, `45,626 B`, CID `ce80f924b56b9752e487338e486812d8c01268fee0222fe6aa4f01304b557fea`)
- **Codebase Verified**: `alpeware/bitecho @ a4388995514ad75e1bbda36d95ea3f887ed0325e` (BIP-024 + BIP-025 live)
- **Date**: 2026-11-25
- **Governance Rule (`INV-TW1`)**: Strictly non-binding advisory pre-flight. Five isolated `research` / `pro` twins audited Draft 1 against live `a4388995` with zero peer leakage and mandatory `:falsifier` per finding.

---

## 1. Blind Round-1 Tally

| Seat (Blind Twin) | Verdict | High | Medium | Low | Top Codebase-Verified Catches |
| :--- | :---: | :---: | :---: | :---: | :--- |
| `:simon-twin` | `:objection` | 6 | 3 | 1 | **S1 HTTP-relay blackhole** (`full_node_main.cljs:2065` / `shell/http.clj:2911` drop `wrap-agent-event` envelopes), `v0.23.23` version collision w/ `bip-024-v02323.edn`, `verify-chain-state-snapshot` strips `:puzzle-hash` |
| `:axiom-twin` | `:objection` | 7 | 2 | 1 | **P1.3 custody self-minting exploit (`10.5M ECH`)**, **P3.1 final-output negative `net-amt` underflow** (use Hamilton largest-remainder), `vault.cljc` `:allocate` bootstrap laundering, `:bounty-escrows` `:locked`/`:gross-amount` conservation miss |
| `:nexus-twin` | `:objection` | 8 | 3 | 1 | **P1.4 unsigned `:vault-create`/`:vault-spend-dac-issue` 1B ECH minting exploit**, **P2.1 consensus-mode unreachable `L1209` + missing `:peers-map` + 64-vs-88 pubkey deadlock**, **P2.3 5 static bootstrap trust sites**, `POST /directory/v1/*` HTTP 405 |
| `:vector-twin` | `:objection` | 7 | 1 | 0 | **Rolling-upgrade checkpoint fork** (unconditional `:total-burned 0` in `compute-checkpoint-state-digest`), **CLJS `:advanced` missing `statfsSync`/`bavail`/`bsize` externs** (why `host_free_disk_bytes` is `null`!), `S6.2` `:role "writer"` breaks `D50` `"single"`, `node_main.cljs:4777` `assoc` clobbers `:lock-state` |
| `:lumen-twin` | `:objection` | 7 | 2 | 0 | **`dissolve-circle` `nil`-bucket & `:closed`-parent stranding + unsigned ballots**, **P4.1 `:advisory-twin-pubkeys` bypass + 64/88-hex oracle key aliasing**, JVM `artifact-put` discarding relay upload failures, `S5.3` `dac-revoke` missing `--agent-pubkey`/`--revoked-epoch` |
| **Total (5/5 Blind)** | **5/5 `:objection`** | **35** | **11** | **3** | **12 consolidated codebase-verified blockers to close in Draft 2** |

---

## 2. Consolidated Blockers for BIP-026 Draft 2 (All Verified Against `a4388995`)

### Critical Security, Consensus & Rollout Blockers

1. **`B1` — P1.4 Gossip Admission Security Hole (Unsigned `:vault-create` / `:vault-spend-dac-*` Minting Exploit)** *(caught `:nexus-twin` `NX-3`)*:
   - `vault/create-vault`, `issue-vault-spend-dac`, `revoke-vault-spend-dac`, and `spend-via-vault-dac` (`vault.cljc:120-350`) currently verify **zero signatures** and never debit `:ledger`/`:root-unallocated`. Admitting them in `validate-gossiped-receipt?` on structural fields alone lets an adversary gossip an unsigned `:vault-create` minting `1e9 ECH` or drain `vault:root` via unsigned `:vault-spend-dac-issue`.
   - **Fix**: Require full cryptographic authorization (Root multisig / holder Ed25519 signature verification + source debit) BEFORE admitting any `:vault-*` receipt in `validate-gossiped-receipt?` and `execute-finalized-blocks`.

2. **`B2` — P1.3 Custody Self-Minting Exploit (`10.5M ECH`) & Rolling-Upgrade Checkpoint Fork** *(caught `:axiom-twin` `F-AXIOM-2`, `:vector-twin` `V-02`, `:nexus-twin` `NX-2`, `:simon-twin` `F-SIMON-06`)*:
   - **Tokenomics**: Feeding internal Genesis custody `vault/spend-vault` takes (`18.9B ECH` across `:bootstrap`/`:team`/`:partners`/`:ecosystem`) into `[:epoch-settlement-takes epoch]` lets custody signers self-mint `M_t <= 0.5 * R_trailing` (`10.5M ECH` from a single last-epoch `:bootstrap` release) with zero organic demand! Keep custody vault burns in `:total-burned` ONLY (NOT `[:epoch-settlement-takes]`), OR explicitly exempt `:custody-bucket` vaults from `R_trailing`.
   - **Rolling-Upgrade Safety**: Adding `:total-burned 0` unconditionally to `compute-checkpoint-state-digest` (`state_machine.cljc:3936`) breaks mixed `v0.23.22`/`v0.23.23` checkpoint QCs! Gate `:total-burned` on `(pos? ...)` (just like existing `:vaults` / `:validator-set-history`), AND actually include `:vaults`, `:bounty-escrows`, `:validator-set-history`, `:total-burned` in `build-checkpoint-snapshot` & `handle-receive-checkpoint`.
   - **Version Collision**: `v0.23.23` is already claimed by `docs/bips/bip-024-v02323.edn`; bump Target Version to `v0.23.24` (or keep `0.23.22` until release bump).

3. **`B3` — P1 7-Bucket Conservation Still Fails on `:bounty-create` & `vault/apply-bootstrap-sunset` Skip** *(caught 4/5 twins: `F-AXIOM-1`, `V-01`, `F-SIMON-01`, `NX-1`)*:
   - `vault/verify-7-bucket-conservation` (`vault.cljc:385`) sums `:bounty-escrows` filtering `(= :open (:status b))` and `(or (:reward-amount b) (:amount b) 0)`, while `state_machine.cljc:2028-2039` writes `:status :locked` and `:gross-amount`!
   - `vault/apply-bootstrap-sunset` (`vault.cljc:356`) reads only top-level `(:bootstrap-sunset-epoch state)` (ignoring `[:config :bootstrap-sunset-epoch]`), and `apply-epoch-treasury-boundary` (`L516-518`) returns early if `new-epoch` is already in `:minted-epochs` — run `apply-bootstrap-sunset` unconditionally at `>= sunset-ep` before the `:minted-epochs` early-return.
   - Also fix `state_machine.cljc:2018-2022` `:bounty-create` bounds check (`(+ take-bps builder-bps) <= 10000`, `gross > 0`) and emit real `:diagnose` effects from `execute-finalized-blocks` (`V-03`).

4. **`B4` — P2 Streamlet Validator-Rotation Still Deadlocks & Light-Node Has No Rotated-Set Replication** *(caught 4/5 twins: `NX-4`, `NX-5`, `V-04`, `F-AXIOM-6`, `F-SIMON-04`)*:
   - `streamlet/active-committee-for-epoch` does not exist (`streamlet/core.cljc:619` is `committee-at-epoch`); hooking `handle-tick` at `L1209` is inside the **unreachable non-consensus `else` branch**!
   - Update all remaining `extract-validator-hexes` callers in `streamlet/core.cljc` (`L789, 1027, 1084, 1120, 1187, 1264`) and `state_machine.cljc` (`L3873, 3992, 4046`) to epoch-indexed `resolve-active-validators`, update `:peers-map` / `:mesh-nodes` / `:quorum-threshold` on `:rotate-validator-set`, and reconcile 64-char raw vs 88-char SPKI validator keys.
   - `P2.2`: Descriptor uses `:epoch-finalized` (NOT `:issued-epoch`), and MUST preserve fast equivocation ejection (`ep-eff = ep-fin + 1` when `:equivocation-proof` is valid per `circle.cljc:757-761`) while enforcing `lead >= 3` for normal scheduled rotations.
   - `P2.3`: Light nodes currently NEVER receive `:validator-set-history` (`build-validator-chain-state-snapshot` omits it and `verify-chain-state-snapshot` strips it), and 5 call sites pin trusted validators to `[:bootstrap :nodes]` — either include signed `:validator-set-history` / descriptor in snapshots across all 5 sites, or scope P2.3 explicitly.

5. **`B5` — P3.1 Negative `net-amt` Underflow on Multi-Output Escrows & `vault.cljc` Parity** *(caught 4/5 twins: `F-AXIOM-4`, `V-08`, `F-SIMON-02`, `NX-10`)*:
   - Dumping the entire truncation remainder onto the final `:release` output underflows (`net_last < 0`) whenever `remainder > amt_last` (e.g., 5 outputs `[24999, 24999, 24999, 25002, 1]` → `net_5 = -2`!).
   - **Fix**: Use **Hamilton largest-remainder apportionment** (with `take_i <= amt_i` invariant) AND apply the exact same single-take apportionment in `vault/spend-vault` (`vault.cljc:246-254`).

6. **`B6` — P4.1 SLA-Vesting Oracle Replay, `:advisory-twin-pubkeys` Bypass, & 64/88-Hex Key Aliasing** *(caught 3/5 twins: `F-AXIOM-5`, `L-P4-1`, `L-P4-2`, `NX-10`)*:
   - `vault/spend-vault` and `:vault-spend-dac` NEVER filter `:advisory-twin-pubkeys` (`INV-TW1`/`INV-C5`).
   - Normalize oracle keys via `circle/normalize-spki-pubkey` (blocking 64-hex + 88-hex double-counting), bind `sla-attestation-tuple` to `:vault-seq` & epoch freshness (`cur-ep - max-lag <= att-ep <= cur-ep`, `0 <= score-bps <= 10000`), and enforce `(= next-ph (:next-puzzle-hash (first relocks)))` in `vault/spend-vault` to prevent relock puzzle hijacking.

7. **`B7` — P5 Custody-Bucket Laundering in `vault.cljc`, `dissolve-circle` `nil`/`:closed` Gaps, & Broken P5.2/P5.3** *(caught 5/5 twins: `F-AXIOM-3`, `F-AXIOM-6`, `V-05`, `L-P5-1..3`, `NX-7`, `NX-9`)*:
   - **P5.1**: Guard `vault/spend-vault` `:allocate` (`vault.cljc:225-233, 262-264`) against `:bootstrap` -> non-`:bootstrap` laundering (closing `INV-026-4`!), AND in `circle/dissolve-circle` enforce strict `(= (:custody-bucket p-vault) (:custody-bucket c-vault))` (no `nil`-bucket bypass), `(= :active (:status p-vault))`, and signed voter ballots in `verify-consent-round`.
   - **P5.2**: Real function is `circle/encrypt-shared-descriptor` (not `encrypt-entity-descriptor`), and its `:directory/encrypted-entity-envelope` has NO outer `:owner-signatures` (and leaks `:payer-pubkey` in `:storage-accounting`). Either add a blinded outer proof without leaking owner pubkeys OR specify the exact envelope schema + `^dir/.+/enc/[0-9a-f]{32}$` path validation (preventing overwrite of `"root/protocol"`).
   - **P5.3**: Changing `subnet/sign-directory-pointer` to `:proof/type :directory-entity` breaks `subnet/verify-directory-pointer` (`core/subnet.cljc:1118-1119`) and `default-infra-policy` (`core/infra.cljc:238`) with `:invalid-owner-multisig` (`threshold 0 < 1`) unless `verify-directory-pointer` + ACL are co-updated!

---

### Workstream 2 (S1–S6) Real Root Causes & Regressions Caught

8. **`B8` — S1 Primary Live-Devnet Root Cause: HTTP Relay Blackhole + Local Receiver Pre-Check Regression** *(caught `:simon-twin` `F-SIMON-03`, `:nexus-twin` `NX-6`, `:axiom-twin` `F-AXIOM-7`, `:vector-twin` `V-06`)*:
   - **HTTP Relay Blackhole**: `full_node_main.cljs:2065-2072` (`forward-relay-to-validator-mesh!`) and `shell/http.clj:2911-2936` forward `/relay/v1/send` to validators ONLY `(when gossip-msg ...)` where `gossip-msg = (or (:message env) (get-in env [:event :message]))`. `wrap-agent-event` (`:lock-intent`, `:release-intent`, `:refund-intent`, `:settle-intent`, `:bounty-*`) has NO `:message`, so HTTP-relayed HTLC intents are silently dropped while returning `HTTP 200 ok: true`!
   - **S1.2 Regression**: `verify-chain-state-snapshot` (`light_node/core.cljc:1527-1537`) strips `:puzzle-hash`/`:puzzle-source` from synced escrows, and receiver-side `local-escrow` may be `nil` (or standard `--preimage` / payer-auto-sign has `solution = nil`). Guard local `eval-puzzle` pre-check so it only runs when complete escrow context + explicit solution/puzzle are available (preserving `core_test.clj:2550` and receiver claims!), AND default `puzzle-src` to `puzzle/standard-htlc-puzzle-source` on the validator side (`state_machine.cljc:2178,3435,3780`) when `(= p-hash (puzzle/standard-htlc-cid))`.

9. **`B9` — S5.1 `POST /directory/v1/*` Returns HTTP 405 on Both Full Nodes + `valid-petname?` Rejects `/`** *(caught 4/5 twins: `NX-8`, `V-06`, `F-SIMON-07`, `L-S5-1`)*:
   - Both `full_node_main.cljs:2392-2393` and `shell/http.clj:2110-2114` reject non-`GET`/`HEAD` on `/directory/v1/*` with **HTTP 405**! Either add the `POST /directory/v1/*` admission handler to both full-node HTTP servers OR route `directory-register` via `/artifact/v1/blob` + `/artifact/v1/pointer`.
   - Also update `verify-directory-pop` / `sign-directory-descriptor` / `verify-directory-descriptor` (`light_node/core.cljc:948,1013,1098`) to use `valid-directory-path?` (not `valid-petname?`, which rejects `/`) and decode `application/fressian` responses.

10. **`B10` — S2 (`verify-subnet-anchor`), S4.2 (JVM `artifact-put`), & S5.2/S5.3 (`bounty-settle` / `dac-revoke`) Gaps** *(caught 4/5 twins)*:
    - **S2**: `core/data.cljc:1210` keys `:pointers` by 2-tuple `[subnet path]` (not `"anchor/<child>"`), and blobs live at `[:data :blobs :entries cid]`.
    - **S4.2**: JVM `shell/light_node.clj:3647-3652, 5264-5269` ALSO discards `post-artifact-to-relay!` failures, and `exec-artifact-put` mutates local state/burns BEFORE upload confirmation (needs 2-phase prepare/commit like `storage-put`, keeping offline `--local-only` / unit tests green).
    - **S5.2**: Rejecting non-64-hex `--patch-cid` unconditionally breaks `core_test.clj:4328,4339` (`"cid-patch-001"`) — update those test fixtures in the same commit, reject `gross <= 0` in `exec-bounty-create`, and don't deduct burn before validation in `exec-bounty-settle`.
    - **S5.3**: Add `--agent-pubkey` and `--revoked-epoch` to `dac-revoke` (required by `verify-dac-revocation` at `core.cljc:1263-1274`), update `known-command-flags` & `pre-verb-valued-flags` (Rule 6), and reconcile `:revoked-dacs` set-vs-vector schema (`L548` vs `L10985`).

11. **`B11` — S6.1/S6.2/S6.3 Regressions & CLJS `:advanced` Externs Bug** *(caught 4/5 twins: `V-07`, `NX-11`, `F-SIMON-08`, `L-S6-1`)*:
    - **S6.2 (`D50` conflict)**: Keep `:role :single` (`status.instance_lock.role == "single"`, NOT `"writer"`, preserving `d50_bip022_stage_b_contracts_test.cljc:275`!); the real bug is `node_main.cljs:4777 & L6634` calling `assoc` instead of `merge` on `:runtime-ctx` (clobbering `:lock-state`).
    - **S6.1 Latency Keys + CLJS `:advanced` Externs**: Fix `:save-ms` → `:state-save-ms` and `:loop-lag-ms` → `:event-loop-lag-ms` in `node_main.cljs:1514-1516`, AND add `statfsSync`, `bavail`, `bsize`, `bfree` to `resources/closure-externs.js` (why `host_free_disk_bytes` was `null` under `:advanced`!).
    - **S6.3 `ECONNRESET`**: Guard `http-get-bytes!`, `node-http-request!`, and pre-connected proxy `tls-sock` using `.once` (preventing EventEmitter listener leaks on keep-alive sockets).

12. **`B12` — `mc_harness.cljc` Time-Compression Scaling** *(caught `:vector-twin` `V-04`)*:
    - Replace linear `(* lambda c)` with exact compound `(- 1.0 (Math/pow (- 1.0 lambda) c))` in `mc-harness/scale-lifecycle-config` (preventing `lambda >= 1.0` wipeout at large `C`).

---

## 3. Recommendation

Fold **`B1–B12`** into **BIP-026 Draft 2** before entering Real Stage 2 Consent. None of these require deferring BIP-026 — every single catch has an exact file, line number, and deterministic fix verified on `a4388995`.
