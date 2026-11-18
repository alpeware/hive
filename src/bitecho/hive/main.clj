(ns bitecho.hive.main
  "CLI entrypoint for Bitecho Hive (`alpeware/hive`).
   Executes the live Two-Agent Shared-VRAM Gemma 4 E2B 'Hello' E2E scenario over the full
   in-memory `clj-datachannel` / `bitecho` / `mealy` / `clj-einsum` stack."
  (:require [bitecho.hive.kv :as kv]
            [bitecho.hive.scenarios.hello :as hello]))

(defn -main
  "Executes the Phase 1 Two-Agent Shared-VRAM Gemma 4 E2B E2E 'Hello' scenario."
  [& _args]
  (println "========================================================================")
  (println "  Bitecho Hive (alpeware/hive) — Phase 1 Full-Stack In-Memory E2E")
  (println "  Scenario: Two Gemma 4 E2B BF16 Agents (1 VRAM Weight Load, 2 KV Caches)")
  (println "========================================================================")
  (let [t0 (System/nanoTime)
        res (hello/run-hello-scenario
             {:mode :live-gemma4
              :model-dir (kv/resolve-model-dir nil)
              :precision :bf16
              :max-new-tokens 24
              :max-seq-len 256
              :temperature 0.0
              :seed 20261118
              :quiet false})
        elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
    (println "\n------------------------------------------------------------------------")
    (println "  E2E Scenario Verification Summary")
    (println "------------------------------------------------------------------------")
    (println (format "  • Overall Status              : %s (%.1f ms total)" (if (:ok? res) "PASSED" "FAILED") elapsed-ms))
    (println (format "  • Model                       : %s (%s)" (:model-dir res) (name (:precision res))))
    (println (format "  • Compute Backend             : %s (target=%s, platform=%s)"
                     (:backend res) (:uop-target res) (or (:platform res) (name (:uop-target res)))))
    (println (format "  • VRAM Weight Allocations     : %d (shared? %s, id=%d)"
                     (get-in res [:vram-proof :weight-allocations])
                     (get-in res [:vram-proof :shared-device-weights?])
                     (get-in res [:vram-proof :device-weights-identity])))
    (println (format "  • Isolated KV Caches          : %d (isolated-atoms? %s, isolated-arenas? %s)"
                     (get-in res [:vram-proof :kv-cache-count])
                     (get-in res [:vram-proof :isolated-kv-atoms?])
                     (get-in res [:vram-proof :isolated-kv-arenas?])))
    (println (format "  • Bitecho E2EE P2P Session ID : %s" (:bitecho-session-id res)))
    (println (format "  • Turn 1 (Alpha -> Beta)      : %s" (pr-str (:alpha-greeting res))))
    (println (format "  • Turn 1 Telemetry            : prefill=%.1f ms (%s toks), decode=%.1f ms (%s toks, %.2f tok/s)"
                     (double (or (get-in res [:turn1-telemetry :prefill-ms]) 0.0))
                     (str (or (get-in res [:turn1-telemetry :prompt-tokens]) "?"))
                     (double (or (get-in res [:turn1-telemetry :decode-ms]) 0.0))
                     (str (or (get-in res [:turn1-telemetry :new-tokens]) "?"))
                     (double (or (get-in res [:turn1-telemetry :decode-tok-s]) 0.0))))
    (println (format "  • Beta Inbox Wire Receipt     : %s" (pr-str (get-in res [:wire-receipts :beta-received-from-alpha]))))
    (println (format "  • Turn 2 (Beta -> Alpha)      : %s" (pr-str (:beta-reply res))))
    (println (format "  • Turn 2 Telemetry            : prefill=%.1f ms (%s toks), decode=%.1f ms (%s toks, %.2f tok/s)"
                     (double (or (get-in res [:turn2-telemetry :prefill-ms]) 0.0))
                     (str (or (get-in res [:turn2-telemetry :prompt-tokens]) "?"))
                     (double (or (get-in res [:turn2-telemetry :decode-ms]) 0.0))
                     (str (or (get-in res [:turn2-telemetry :new-tokens]) "?"))
                     (double (or (get-in res [:turn2-telemetry :decode-tok-s]) 0.0))))
    (println (format "  • Alpha Inbox Wire Receipt    : %s" (pr-str (get-in res [:wire-receipts :alpha-received-from-beta]))))
    (println (format "  • Turn 1 KV Log Blob CID      : %s" (get-in res [:kv-chain-checks :alpha :head-cid])))
    (println (format "  • Turn 2 KV Log Blob CID      : %s" (get-in res [:kv-chain-checks :beta :head-cid])))
    (println (format "  • Episode Trace Merkle Root   : %s" (get-in res [:episode-trace :merkle-root])))
    (println (format "  • Subnet Pointer Merkle Root  : %s" (get-in res [:episode-trace :subnet-merkle-root])))
    (println (format "  • BIP-005 Pointer Path        : %s/%s"
                     (get-in res [:episode-trace :pointer-subnet])
                     (get-in res [:episode-trace :pointer-path])))
    (println (format "  • Mealy Replay Checks         : alpha=%s, beta=%s"
                     (get-in res [:mealy-replay-checks :alpha :ok?])
                     (get-in res [:mealy-replay-checks :beta :ok?])))
    (println "========================================================================")
    (when-not (:ok? res)
      (System/exit 1))))
