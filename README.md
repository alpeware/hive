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

## Usage

```bash
# Run the test suite (Rule 1 generative test.check properties + E2E verification)
clojure -M:test

# Run the live Gemma 4 E2B BF16 shared-VRAM two-agent E2E "Hello" scenario
clojure -M:run

# Format and lint (Rule 3 Clean CI)
clojure -M:format
clojure -M:lint
```
