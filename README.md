# Bitecho Hive (`alpeware/hive`)

**Bitecho Hive** is the Full-Stack Swarm Simulation & Eval composition layer above `bitecho` (`BIP-005`), `mealy`, and `clj-einsum`.

```
bitecho (network / economy / CAS)  <-  mealy (harness)  <-  clj-einsum (compute)
        ^                                   ^                     ^
        +----------------- hive (scenarios / orchestration / eval / traces)
```

## Architecture & Technical Seams

1. **Sans-IO Full-Stack Composition (`bitecho.hive.stack`):**
   - Runs the entire `clj-datachannel` (RFC 4571 / SCTP framing) + `bitecho` (`bitecho.light-node.core` E2EE X25519 sealed sessions + `bitecho.sim.vopr` virtual-time byte router) + `mealy` (`[:agent ...]` declarative graphs with `:compute/einsum` and `:net/bitecho` capabilities) + `clj-einsum` (UOp ROCm / Intel Arc OpenCL / CPU Gemma 4 execution) stack in memory on a single JVM.
2. **Immutable Append-Only KV Cache & Shared-VRAM Weight Multiplexing (`bitecho.hive.kv`):**
   - Models each agent's KV cache as an immutable, content-addressed append-only log (`Blob` chain) while multiplexing N isolated per-agent KV cache arenas (`kv-alpha`, `kv-beta`) over a **single** loaded Gemma 4 E2B BF16 VRAM weight allocation (`:device-weights`).
3. **Content-Addressed Trace Receipts (`bitecho.hive.trace`):**
   - Every turn produces a canonical Fressian `bitecho.core.data/make-blob` `Blob` chaining `:turn/prev-turn-cid`, `:turn/kv-log-cid`, `:turn/mealy-event-root`, and `:turn/wire-envelope-cids`.
   - Episodes are anchored to a signed `BIP-005` `Pointer` (`{:pointer/subnet "hive/scenarios", :pointer/path "runs/<scenario-id>/<run-id>"}`) targeting the RFC 6962 Merkle root over all turn `Blob` CIDs.
4. **Phase 1 E2E Two-Agent Shared-VRAM Gemma 4 E2B Scenario (`bitecho.hive.scenarios.hello`):**
   - Loads `clj-einsum/.models/gemma-4-E2B-it` (`bf16`) **once into VRAM** with two isolated KV caches (`:alpha` and `:beta`).
   - Agent Alpha and Agent Beta establish an E2EE P2P session over the simulated `clj-datachannel` + `bitecho` VOPR network, exchange a 1-turn greeting and 1-turn reply through their `mealy` graphs, terminate after one turn each, and emit a verified `Blob` + `Pointer` + RFC 6962 Merkle trace.
5. **Phase 2 Configurable `N x M` Multi-Machine Swarm (`bitecho.hive.scenarios.cluster`):**
   - Orchestrates `N` machines with `M_i` agents per machine (`--scenario cluster-hello`) over intra-machine 0-burn turns and cross-machine P2P legs, converging on an identical RFC 6962 Episode Merkle Root across all `N` machines.
6. **Gate 0 Heterogeneous 10-Agent Hive Arena Bounty Demo (`bitecho.hive.scenarios.arena-bounty`):**
   - Runs a 10-agent heterogeneous swarm (`--scenario arena-bounty`): 6 shared-VRAM Gemma 4 E2B workers + 1 rogue adversary (sharing 1 weight allocation and 7 isolated KV caches with a real SHA-256 `:kv/weights-digest` manifest hash) + Claude requester + Gemini auditor (`:compute/agy`) + Sans-IO observer.
   - Routes all inter-agent E2EE messages through active VOPR packet drop/retransmit (`--fault-profile lossy-10pct`) with Rule 5 counted `:diagnose` effects, neutralizes the adversarial trifecta (Attack A budget drain via `--min-balance 2000`, Attack B frozen-kernel hijack via `frozen-kernel-verify-node`, Attack C broken deliverable via `:eval/sci`), settles the 500 ECH 3-party quorum escrow with exact `$ECHO` conservation (`0.5%` protocol burn), and verifies 10/10 zero-capability `replay-graph` + inline `MUT-01` tamper detection.

## Usage

```bash
# Run the test suite (Rule 1 generative test.check properties + E2E verification)
clojure -M:test

# Run Gate 0 10-agent Arena Bounty scenario (deterministic seeded-mock CI mode, <2s)
clojure -M:run --scenario arena-bounty --mode seeded-mock --fault-profile lossy-10pct --seed 20261119

# Run Gate 0 10-agent Arena Bounty scenario (live-hybrid mode: 6x Gemma 4 E2B + Claude + Gemini)
clojure -M:run --scenario arena-bounty --mode live-hybrid \
  --requester-model claude-sonnet-5-5-medium \
  --auditor-model gemini-3.8-flash-low \
  --local-workers 6 --fault-profile lossy-10pct --seed 20261119

# Run the live Gemma 4 E2B BF16 shared-VRAM two-agent E2E "Hello" scenario
clojure -M:run

# Format and lint (Rule 3 Clean CI)
clojure -M:format
clojure -M:lint
```
