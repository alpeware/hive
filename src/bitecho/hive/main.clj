(ns bitecho.hive.main
  "CLI entrypoint for Bitecho Hive (`alpeware/hive`).
   Supports:
   1. Phase 1 Two-Agent Shared-VRAM Gemma 4 E2B 'Hello' E2E scenario (`--scenario hello`, default).
   2. Phase 2 Configurable `N` Machines x `M` Agents-Per-Machine Multi-Machine Hive Swarm (`--scenario cluster-hello`),
      supporting both in-memory `N x M` multi-machine simulation and live cross-machine LAN P2P execution
      (`--machine-id <id> --machines <N> --agents-per-machine <M> --machine-agents <id=count,...> --peers <id=host:port,...>`)."
  (:require [bitecho.hive.kv :as kv]
            [bitecho.hive.scenarios.cluster :as cluster]
            [bitecho.hive.scenarios.hello :as hello]
            [clojure.string :as str]))

(defn- parse-machine-agents-flag
  "Parses `--machine-agents nexus=12,vector=10` into `{:nexus 12 :vector 10}`."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (into {}
          (keep (fn [entry]
                  (let [[k v] (str/split (str/trim entry) #"=" 2)]
                    (when (and (not (str/blank? k)) (not (str/blank? v)))
                      [(keyword (str/trim k)) (Long/parseLong (str/trim v))]))))
          (str/split s #","))))

(defn- parse-peers-flag
  "Parses `--peers nexus=192.168.86.36:5610,vector=192.168.86.26:5610` into
   `{:nexus {:host \"192.168.86.36\" :port 5610} :vector {:host \"192.168.86.26\" :port 5610}}`."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (into {}
          (keep (fn [entry]
                  (let [[k host-port] (str/split (str/trim entry) #"=" 2)]
                    (when (and (not (str/blank? k)) (not (str/blank? host-port)))
                      (let [[host port-str] (str/split (str/trim host-port) #":" 2)]
                        [(keyword (str/trim k))
                         {:host (str/trim host)
                          :port (Long/parseLong (str/trim (or port-str "5610")))}])))))
          (str/split s #","))))

(defn parse-cli-args
  "Parses command-line arguments for `bitecho.hive.main` into an options map."
  [args]
  (loop [rem-args (seq args)
         acc {:scenario :hello
              :mode :live-gemma4
              :precision :bf16
              :max-new-tokens 24
              :max-seq-len 256
              :temperature 0.0
              :seed 20261118
              :quiet false}]
    (if-not rem-args
      acc
      (let [flag (first rem-args)
            val-str (second rem-args)]
        (case flag
          "--scenario" (recur (nnext rem-args) (assoc acc :scenario (keyword val-str)))
          "--mode" (recur (nnext rem-args) (assoc acc :mode (keyword val-str)))
          "--backend" (recur (nnext rem-args) (assoc acc :backend (keyword val-str)))
          "--model-dir" (recur (nnext rem-args) (assoc acc :model-dir val-str))
          "--precision" (recur (nnext rem-args) (assoc acc :precision (keyword val-str)))
          "--machine-id" (recur (nnext rem-args) (assoc acc :machine-id (keyword val-str)))
          "--machines" (recur (nnext rem-args) (assoc acc :machines (Long/parseLong val-str)))
          "--agents-per-machine" (recur (nnext rem-args) (assoc acc :agents-per-machine (Long/parseLong val-str)))
          "--machine-agents" (recur (nnext rem-args) (assoc acc :machine-agents (parse-machine-agents-flag val-str)))
          "--turns" (recur (nnext rem-args) (assoc acc :turns (Long/parseLong val-str)))
          "--subnet-id" (recur (nnext rem-args) (assoc acc :subnet-id val-str))
          "--bind-host" (recur (nnext rem-args) (assoc acc :bind-host val-str))
          "--bind-port" (recur (nnext rem-args) (assoc acc :bind-port (Long/parseLong val-str)))
          "--peers" (recur (nnext rem-args) (assoc acc :peers (parse-peers-flag val-str)))
          "--max-new-tokens" (recur (nnext rem-args) (assoc acc :max-new-tokens (Long/parseLong val-str)))
          "--max-seq-len" (recur (nnext rem-args) (assoc acc :max-seq-len (Long/parseLong val-str)))
          "--temperature" (recur (nnext rem-args) (assoc acc :temperature (Double/parseDouble val-str)))
          "--seed" (recur (nnext rem-args) (assoc acc :seed (Long/parseLong val-str)))
          "--timeout-ms" (recur (nnext rem-args) (assoc acc :timeout-ms (Long/parseLong val-str)))
          "--quiet" (recur (next rem-args) (assoc acc :quiet true))
          (recur (next rem-args) acc))))))

(defn- print-phase1-hello-summary
  [res elapsed-ms]
  (println "\n------------------------------------------------------------------------")
  (println "  E2E Scenario Verification Summary")
  (println "------------------------------------------------------------------------")
  (println (format "  • Overall Status              : %s (%.1f ms total)" (if (:ok? res) "PASSED" "FAILED") elapsed-ms))
  (println (format "  • Model                       : %s (%s)" (:model-dir res) (name (:precision res))))
  (println (format "  • Compute Backend             : %s (target=%s, platform=%s)"
                   (:backend res) (:uop-target res) (or (:platform res) (name (or (:uop-target res) :cpu)))))
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
  (println "========================================================================"))

(defn- print-phase2-cluster-summary
  [res elapsed-ms]
  (let [topo (:topology res)]
    (println "\n------------------------------------------------------------------------")
    (println "  Phase 2 Multi-Machine Hive Swarm Verification Summary")
    (println "------------------------------------------------------------------------")
    (println (format "  • Overall Status              : %s (%.1f ms total)" (if (:ok? res) "PASSED" "FAILED") elapsed-ms))
    (println (format "  • Topology                    : %d Machines x %d Total Agents (%d Turns)"
                     (:machine-count topo) (:total-agents topo) (:turn-count topo)))
    (println (format "  • Shared Bitecho Subnet       : %s" (:subnet-id topo)))
    (when (:machine-id res)
      (println (format "  • Local Home-Node Machine     : %s (%s:%s) -> agents %s"
                       (name (:machine-id res))
                       (or (:bind-host res) "in-memory")
                       (or (:bind-port res) "-")
                       (pr-str (:local-agents res))))
      (println (format "  • Local Compute Backend       : %s (target=%s, platform=%s)"
                       (:backend res) (:uop-target res) (or (:platform res) (name (or (:uop-target res) :cpu)))))
      (println (format "  • Local VRAM Weight Allocs    : %d (shared? %s, KV caches=%d, isolated? %s)"
                       (get-in res [:vram-proof :weight-allocations])
                       (get-in res [:vram-proof :shared-device-weights?])
                       (get-in res [:vram-proof :kv-cache-count])
                       (get-in res [:vram-proof :isolated-kv-atoms?]))))
    (when-let [msums (:machines-summary res)]
      (doseq [[mid msum] msums]
        (println (format "  • Machine %-19s : weight-allocs=%d, kv-caches=%d, merkle-root=%s"
                         (name mid)
                         (get-in msum [:vram-proof :weight-allocations])
                         (get-in msum [:vram-proof :kv-cache-count])
                         (:episode-merkle-root msum)))))
    (when-let [pproofs (:peer-merkle-proofs res)]
      (doseq [[mid pinfo] pproofs]
        (println (format "  • Peer %-22s : backend=%s, weight-allocs=%s, kv-caches=%s, merkle=%s"
                         (name mid)
                         (:backend pinfo)
                         (get-in pinfo [:vram-proof :weight-allocations])
                         (get-in pinfo [:vram-proof :kv-cache-count])
                         (:episode-merkle-root pinfo)))))
    (doseq [t (:turns-telemetry res)]
      (println (format "  • Turn %d [%-17s] : %s -> %s | prefill=%.1f ms, decode=%.1f ms (%.2f tok/s) | text=%s"
                       (:turn/seq t)
                       (name (:leg-type t))
                       (cluster/agent-display-name (:actor-id t))
                       (cluster/agent-display-name (:target-id t))
                       (double (or (:prefill-ms t) 0.0))
                       (double (or (:decode-ms t) 0.0))
                       (double (or (:decode-tok-s t) 0.0))
                       (pr-str (:output-text t)))))
    (println (format "  • Converged Merkle Root       : %s (converged? %s)"
                     (:converged-merkle-root res)
                     (:converged? res)))
    (println "========================================================================")))

(defn -main
  "Executes the requested Bitecho Hive scenario (`--scenario hello` or `--scenario cluster-hello`)."
  [& args]
  (let [opts (-> (parse-cli-args args)
                 (update :model-dir kv/resolve-model-dir))
        t0 (System/nanoTime)]
    (if (or (= :cluster-hello (:scenario opts))
            (some? (:machine-id opts))
            (some? (:machines opts))
            (some? (:peers opts)))
      (do
        (println "========================================================================")
        (println "  Bitecho Hive (alpeware/hive) — Phase 2 N x M Multi-Machine Swarm")
        (println "========================================================================")
        (let [res (if (and (:machine-id opts) (seq (:peers opts)))
                    (cluster/run-live-machine-cluster-node! opts)
                    (cluster/run-in-memory-cluster-scenario opts))
              elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
          (print-phase2-cluster-summary res elapsed-ms)
          (shutdown-agents)
          (when-not (:ok? res)
            (System/exit 1))))
      (do
        (println "========================================================================")
        (println "  Bitecho Hive (alpeware/hive) — Phase 1 Full-Stack In-Memory E2E")
        (println "  Scenario: Two Gemma 4 E2B BF16 Agents (1 VRAM Weight Load, 2 KV Caches)")
        (println "========================================================================")
        (let [res (hello/run-hello-scenario opts)
              elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
          (print-phase1-hello-summary res elapsed-ms)
          (shutdown-agents)
          (when-not (:ok? res)
            (System/exit 1)))))))
