# BIP-019 (Stage 1 Shaped Draft 1): `F-EPOCH-1` Finalization Stall — Provisional Incident Mitigation, Streamlet Depth Hardening, Client Recovery, & Consensus Duration Soak

- **Status:** Stage 1 Pre-Shaping Output — **Advisory Only (`:status :unresolved`, 4 `:amendment` + 1 `:objection` in Blind Round 1; simulated consent NEVER counts as real consent)**
- **Source Proposal (Stage 0a):** `dist/bips/bip-019-fepoch1-proposal.md` (CID `423b1d9c82a8e4bc6fc00829e3eaef3cac55e65b83e4883e3df29c05a629ad63`)
- **Stage 1 Runner:** `bitecho.hive.twin-stage1` (`alpeware/hive` commit `bd1adc595e78a160003b78bf7da18ed6a56f0f95`), **first live execution** of 5 independent blind Round-1 frontier subagents with mandatory `:falsifier` slots
- **All-Frontier 5-Seat Roster (zero `gpt-oss-120b`):** `simon` (`claude-opus-5-5-high`), `axiom` (`gemini-4-argon-high`), `nexus` (`claude-sonnet-5-5-high`), `vector` (`gemini-4-carbon-high`), `lumen` (`gemini-4-hydrogen-high`)
- **Values Anchor (`:episode/values-cid`):** `2b6d6a6a794ef168a00184faf35d3b77efb083c24671e3ff1092a432405f9448`
- **Verified RFC 6962 Merkle Trace Root:** `8d6795563059e8eeeb161d99aa6093ec40dd37b2e33bb68568a13be6d12759de`
- **Full Verbatim Blind Round-1 Report:** `docs/bips/bip-019-stage1-twin-report.edn`

---

## 1. Executive Summary of 5 Independent Blind Round-1 Catches

All 5 seats ran in **completely isolated Round-1 contexts** (zero peer visibility) and independently converged on five concrete, production-critical amendments that close real loopholes in Stage 0(a):

1. **Governance & Retroactive Ratification Agency (`Simon`, `Axiom`, `Lumen`):**
   - Reclassify live `b52a95c` strictly as `:provisional-incident-mitigation` (documenting why Rollback-First SOP could not repair corrupted depths without `repair-corrupt-block-depths`), NOT an unqualified "ship-first, ratify-after" precedent.
   - Require explicit affirmative **5/5 Stage 2 consent** (zero `:pending` records per `validate-bip-receipt`, barring silence-as-consent for retroactive consensus code) after recorded directed-session delivery acks, with an explicit **extension-by-objection** rule and a defined **rollback / superseding-hotfix unwind protocol** if any member files `:paramount-objection`.
   - Attach full 40-char commit SHAs and HTTP-200/SHA-256-verifiable 64-char CIDs (`:patch-cid`, `:patch-size-bytes`, 3-runtime `:bundle-sha256`, Run 1 failure receipt `5864bd20...`, Run 2 receipt `7b1a91a4...`, and all 6 validator/relay digests `node1..3` + `ns1..3`). Live probes during Blind Round 1 found `32614488...` (`b52a95c` bundle) returning **`HTTP 404`** on `ns1/ns2/ns3` and `pointer-subnet-mismatch` in `/statusz` — all cited CIDs MUST verify `HTTP 200` + SHA-256 prior to Stage 2 open.

2. **Remaining Epoch-as-Depth Consensus Bug in `streamlet/core.cljc` (`Nexus`, `Axiom`, `Vector`):**
   - Reordering `(contains? depths p-hash)` ahead of `(contains? executed-roots p-hash)` (required in **both** the 1-hop fast path AND the multi-hop ancestor-walk loop) still leaves **epoch-as-depth poisoning** intact whenever skipped epochs make `tip-depth < tip-epoch` (`85684` vs `85690`) and an uncached or pruned executed parent or `ingest-verified-ancestors` / checkpoint restore seeds `:block-depths` from `:last-executed-finalized-epoch` or `(:epoch block)`.
   - **Required fix:** Retain immutable topological `:executed-root-depths` across `prune-streamlet-state`, `rebind-restored-state`, and checkpoints; run `repair-corrupt-block-depths` at both restore and runtime admission with a counted **`:diagnose {:reason :corrupt-block-depth-repaired}`** surfaced in `/statusz` and `digest.edn` (Rule 5).

3. **Stateful VOPR / `test.check` Generator Gate Before Stage 2 Close (`Simon`, `Nexus`, `Vector`, `Axiom`):**
   - Do NOT defer a static corrupted-depth fixture to Stage 6. Land a seeded `clojure.test.check` command-sequence lifecycle generator **before Stage 2 closure** interleaving skipped epochs, branching forks, `finalize-prefix`, `execute-finalized-blocks`, `prune-streamlet-state` (`> 256` epochs), `ingest-verified-ancestors`, and `rebind-restored-state`, paired with a `MUT-*` non-vacuity mutant proving pre-fix code deadlocks while patched code heals.

4. **Quantified Staging-First Consensus Finality Duration Soak (`Vector`, `Simon`, `Nexus`, `Axiom`):**
   - Remove 4-min / 41-epoch `P-MEM-2 Run 2` (`7b1a91a4...`) as consensus verification — it never crosses one `default-streamlet-retention-epochs` window (`256` epochs, `~26 min`).
   - Require a **Consensus Finality Duration Soak** on `bitecho-gentoo-lab` staging: **`>= 300` consecutive finalized epochs AND `>= 30–60` minutes** (crossing both `frozen-log-retention-epochs = 168` and `default-streamlet-retention-epochs = 256`) with injected skipped-epoch leader drop, staggered restarts/restore, and delayed older-parent fork arrival; assert `0` `:finalization-stall` intervals (`> 3` consecutive epochs), `epoch-finality-gap <= 3`, `0` finalized-epoch skew across all 6 nodes, and `0` cached-depth `> tip-depth` inversions.

5. **Client Stall Visibility, Browser Recovery, & Honest SLA Persistence (`Lumen`, `Axiom`):**
   - **SLA Erasure Bug Caught Live:** `observability.core/record-startup-event` resets `:active-stall-started-ms nil` on deploy restarts, erasing 96% of the ~20-min stall (`sla/latest.edn` recorded `45,766 ms` instead of `~1,200,000 ms`), and `record-stall-sample` (`head - finalized > 10`) masks the first `<= 10` stalled epochs. Preserve `:active-stall-started-ms` across restarts and report exact unrounded 3-way SLO error-budget math (`~1,200,000 ms` = **`13,888.9×`** 24h six-nines budget, **`463.0×`** 30d budget, **`38.05×`** annual budget; `:six-nines-met? false`).
   - **Client Recovery & Visibility:** During the 20-min stall, light-node `verify-chain-state-quorum` / `run-auto-sync-tick!` accepted `2f+1` agreement on frozen epoch `85690` and emitted `auto-sync ok:true synced:true`, while `browser_main.cljs` stopped at `meshConnected:false` (`:closed`) after rolling restarts with no automatic `connect-mesh` redial. Add head-vs-finalized lag + stall-duration checks with counted `:diagnose` to light-node/browser sync and bounded WebRTC `connect-mesh` auto-redial + HTTP `/relay/v1/chain-state` fallback polling.

---

## 2. Mandatory Blind Round-1 Falsifiers ("What Breaks in Prod")

| Seat | Verdict | Prod Failure Mode Caught | Observable Signal |
|---|---|---|---|
| **Simon** | `:amendment` | Unsoaked `b52a95c` corner case / "ship-first" precedent pins unconsented consensus code live | `:finalization-stall :ongoing? true`, `epoch-finality-gap > 3`, missing `:patch-cid` / `:generator-case-added?` |
| **Axiom** | `:amendment` | Retroactive `N=10,000` silence-as-consent ratifies un-diffed hotfix; SLA undercounts pre-threshold stall | `:finalization-stall :ongoing? true`, negative `:error-budget-remaining-ms`, `validate-bip-receipt` rejects `:pending-consent` / `:missing-patch-cid` |
| **Nexus** | `:amendment` | Uncached/pruned executed roots & `ingest-verified-ancestors` still convert epoch -> depth after skipped epochs | Flat `finalized-epoch`, `epoch-finality-gap > 10`, `:term` divergence while `:corrupt-block-depth-repaired` stays absent/0 |
| **Vector** | `:amendment` | Unquantified soak + static fixture misses `> 256`-epoch pruning + skipped-epoch fork deadlock | `:finalization-stall {:ongoing? true}`, `ns1/ns2/ns3` term divergence, client `:chain-stalled`, silent/climbing repair counter |
| **Lumen** | `:objection` | Circle ratifies HTTP 404 bundle while light nodes report `synced:true`, browsers stick `:closed`, & SLA erases 96% of stall | HTTP `404` on `32614488...`, `sla/latest.edn` `45,766 ms` vs `~1,200,000 ms`, `auto-sync ok:true synced:true` at frozen `85690`, browser `meshConnected:false` |

---

## 3. Next Step (Real-Circle Governance)

Per `bitecho.hive.twin-stage1` (`:advisory-only? true`, `:real-consent-required? true`, `:status :unresolved`), **nothing in this document is consented**. Handed back to **Axiom**, **Nexus**, **Lumen**, and **Simon** to fold into BIP-019's formal Stage 2 package (including verified 64-char `:patch-cid` + `:bundle-sha256` CIDs on `ns1/ns2/ns3`) before opening Real Stage 2 Consent.
