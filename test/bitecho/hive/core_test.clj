(ns bitecho.hive.core-test
  "Generative test.check properties and E2E verification for Bitecho Hive:
   1. Seeded Sans-IO inference determinism & MUT-01 non-vacuity.
   2. Immutable append-only KV cache log isolation & single-VRAM weight multiplexing.
   3. Rule 4/5 shell lifecycle boundaries + full in-memory clj-datachannel/bitecho/mealy/clj-einsum stack roundtrip."
  (:require [bitecho.core.data :as data]
            [bitecho.hive.kv :as kv]
            [bitecho.hive.main :as main]
            [bitecho.hive.scenarios.cluster :as cluster]
            [bitecho.hive.scenarios.hello :as hello]
            [bitecho.hive.stack :as stack]
            [bitecho.hive.trace :as trace]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; =============================================================================
;; 0. Hardware Compute Backend Auto-Detection Inventory
;; =============================================================================

(deftest default-compute-backend-auto-detection-test
  (testing "kv/resolve-uop-backend selects one of the supported UOp GPU/CPU backends (:uop-rocm, :uop-intel, :uop-cpu)"
    (is (contains? #{:uop-rocm :uop-intel :uop-cpu} (kv/resolve-uop-backend :auto)))
    (is (= :uop-intel (kv/resolve-uop-backend :uop-intel)))))

;; =============================================================================
;; 1. Seeded Sans-IO Determinism & MUT-01 Non-Vacuity
;; =============================================================================

(defspec ^{:doc "Property: Seeded Sans-IO inference determinism across full stack & MUT-01 tamper non-vacuity."}
  prop-seeded-sans-io-inference-determinism-and-mut01-non-vacuity 25
  (prop/for-all [seed (gen/choose 1 1000000)
                 alpha-prompt (gen/elements ["Say hello to Beta in one short sentence."
                                             "Greet Beta warmly in one sentence."
                                             "Say hi to Beta across the Bitecho mesh."])]
                (let [opts {:seed seed
                            :mode :seeded-mock
                            :alpha-prompt alpha-prompt
                            :max-new-tokens 16}
                      run-1 (hello/run-hello-scenario opts)
                      run-2 (hello/run-hello-scenario opts)
                      perturbed-run (hello/run-hello-scenario (assoc opts :seed (bit-xor (long seed) 1)))
                      trace-1 (:episode-trace run-1)
                      trace-2 (:episode-trace run-2)
                      verify-1 (trace/verify-episode-trace trace-1)
                      ;; MUT-01: Tamper with turn 1 output text in blob store without updating CID
                      first-turn-cid (first (:turn-cids trace-1))
                      tampered-turn (assoc (get-in trace-1 [:turns 0]) :turn/output-text "TAMPERED-BY-MUT-01")
                      tampered-trace (assoc-in trace-1 [:turns 0] tampered-turn)
                      verify-tampered (trace/verify-episode-trace tampered-trace)]
                  (and (:ok? run-1)
                       (:ok? run-2)
                       (:ok? verify-1)
                       (= (:merkle-root trace-1) (:merkle-root trace-2))
                       (= (:turn-cids trace-1) (:turn-cids trace-2))
                       (= (:alpha-greeting run-1) (:alpha-greeting run-2))
                       (= (:beta-reply run-1) (:beta-reply run-2))
                       ;; 1-bit seed perturbation diverges deterministically
                       (not= (:merkle-root trace-1) (:merkle-root (:episode-trace perturbed-run)))
                       ;; MUT-01 tamper is caught by verify-episode-trace
                       (string? first-turn-cid)
                       (false? (:ok? verify-tampered))))))

;; =============================================================================
;; 2. KV Cache Append-Only Log Isolation & Weight Multiplexing
;; =============================================================================

(def ^:private gen-token-vec
  (gen/not-empty (gen/vector (gen/choose 2 50000) 2 12)))

(defspec ^{:doc "Property: Immutable append-only KV cache log isolation & single-VRAM weight multiplexing."}
  prop-kv-append-only-log-isolation-and-prefix-sharing 40
  (prop/for-all [alpha-t1-prompt gen-token-vec
                 alpha-t1-new gen-token-vec
                 alpha-t2-extra gen-token-vec
                 beta-t1-prompt gen-token-vec
                 beta-t1-new gen-token-vec]
                (let [mux (kv/init-shared-kv-multiplexer
                           {:mode :seeded-mock
                            :agent-ids [:alpha :beta]
                            :seed 4242})
                      sess-alpha (kv/get-agent-session mux :alpha)
                      sess-beta (kv/get-agent-session mux :beta)
                      ;; Turn 1 for Alpha
                      [mux1 entry-a1] (kv/record-agent-turn-kv!
                                       mux :alpha
                                       {:turn-seq 1
                                        :prompt-tokens alpha-t1-prompt
                                        :new-tokens alpha-t1-new})
                      ;; Turn 1 for Beta (disjoint prompt + new tokens)
                      [mux2 entry-b1] (kv/record-agent-turn-kv!
                                       mux1 :beta
                                       {:turn-seq 1
                                        :prompt-tokens beta-t1-prompt
                                        :new-tokens beta-t1-new})
                      ;; Turn 2 for Alpha extends Alpha's cached token prefix
                      alpha-t1-all (into (vec alpha-t1-prompt) alpha-t1-new)
                      alpha-t2-prompt (into alpha-t1-all alpha-t2-extra)
                      [mux3 entry-a2] (kv/record-agent-turn-kv!
                                       mux2 :alpha
                                       {:turn-seq 2
                                        :prompt-tokens alpha-t2-prompt
                                        :new-tokens [101 102]})
                      verify-alpha (kv/verify-agent-kv-chain mux3 :alpha)
                      verify-beta (kv/verify-agent-kv-chain mux3 :beta)
                      stats (kv/multiplexer-stats mux3)]
                  (and (= 1 (:weight-allocations stats))
                       (= 2 (:kv-cache-count stats))
                       (identical? (:device-weights sess-alpha) (:device-weights sess-beta))
                       (not (identical? (:kv-state sess-alpha) (:kv-state sess-beta)))
                       (:ok? verify-alpha)
                       (:ok? verify-beta)
                       (= 2 (:entry-count verify-alpha))
                       (= 1 (:entry-count verify-beta))
                       (= (:kv/cid entry-a1) (:kv/parent-cid entry-a2))
                       (nil? (:kv/parent-cid entry-a1))
                       (nil? (:kv/parent-cid entry-b1))
                       (= (count alpha-t1-all) (:kv/prefix-reused-tokens entry-a2))
                       ;; Beta's KV state was completely untouched by Alpha's Turn 2
                       (= (into (vec beta-t1-prompt) beta-t1-new)
                          (:cached-tokens @(:kv-state sess-beta)))
                       (not= (:kv/cid entry-a1) (:kv/cid entry-b1))))))

;; =============================================================================
;; 3. Rule 4/5 Shell Lifecycle & Full-Stack In-Memory Roundtrip
;; =============================================================================

(def ^:private gen-lifecycle-cmd
  (gen/one-of
   [(gen/fmap (fn [p] {:type :dial :peer-id p}) (gen/elements ["node1" "node2"]))
    (gen/return {:type :open})
    (gen/fmap (fn [g] {:type :close :generation g}) (gen/choose 0 6))
    (gen/fmap (fn [r] {:type :error :reason r}) (gen/elements [:dtls-handshake-timeout :sctp-abort]))
    (gen/return {:type :drop-envelope :reason :unroutable-envelope})]))

(defspec ^{:doc "Property: Rule 4/5 shell lifecycle boundaries & full-stack clj-datachannel/bitecho/mealy/clj-einsum roundtrip."}
  prop-full-stack-hello-scenario-in-memory-roundtrip 20
  (prop/for-all [cmds (gen/vector gen-lifecycle-cmd 1 15)
                 seed (gen/choose 100 999999)]
                (let [lifecycle-res (stack/simulate-shell-lifecycle-commands cmds)
                      hello-res (hello/run-hello-scenario {:seed seed :mode :seeded-mock})
                      trace-ver (trace/verify-episode-trace (:episode-trace hello-res))
                      merkle-proof (:merkle-proof (:episode-trace hello-res))
                      proof-ver (data/verify-merkle-proof merkle-proof (:subnet-merkle-root (:episode-trace hello-res)))]
                  (and (:ok? lifecycle-res)
                       (:zero-silent-failures? lifecycle-res)
                       (:ok? hello-res)
                       (:ok? trace-ver)
                       (:ok? proof-ver)
                       (= 1 (get-in hello-res [:vram-proof :weight-allocations]))
                       (= 2 (get-in hello-res [:vram-proof :kv-cache-count]))
                       (true? (get-in hello-res [:vram-proof :shared-device-weights?]))
                       (true? (get-in hello-res [:vram-proof :isolated-kv-atoms?]))
                       (= 2 (count (get-in hello-res [:episode-trace :turns])))
                       (every? :ok? (vals (:mealy-replay-checks hello-res)))))))

;; =============================================================================
;; 4. Phase 2: Configurable N Machines x M Agents-Per-Machine Topology & Convergence
;; =============================================================================

(defspec ^{:doc "Property: Configurable N machines x M agents-per-machine topology construction, 1 VRAM weight load per machine, M isolated KV caches, and cross-machine vs intra-machine ring schedule."}
  prop-nxm-multi-machine-topology-and-ring-schedule 35
  (prop/for-all [n-machines (gen/choose 1 4)
                 m-agents (gen/choose 1 6)
                 use-override? gen/boolean
                 extra-agent (gen/choose 0 3)]
                (let [machine-ids (if (= n-machines 2)
                                    [:nexus :vector]
                                    (mapv #(keyword (str "m" %)) (range n-machines)))
                      machine-agents-override (when use-override?
                                                (into {}
                                                      (map-indexed (fn [idx mid]
                                                                     [mid (+ m-agents (if (zero? idx) extra-agent 0))])
                                                                   machine-ids)))
                      topo (cluster/build-cluster-topology
                            {:machines n-machines
                             :agents-per-machine m-agents
                             :machine-ids machine-ids
                             :machine-agents machine-agents-override
                             :subnet-id "hive/swarm-01"})
                      expected-total (if use-override?
                                       (reduce + (vals machine-agents-override))
                                       (* n-machines m-agents))
                      schedule (:ring-schedule topo)]
                  (and (= n-machines (:machine-count topo))
                       (= expected-total (:total-agents topo))
                       (= expected-total (count (:all-agents topo)))
                       (= expected-total (count schedule))
                       ;; When N >= 2 and M >= 2, ring schedule exercises both :cross-machine-p2p and :intra-machine legs
                       (if (and (>= n-machines 2) (>= m-agents 2))
                         (and (some #(= :cross-machine-p2p (:leg-type %)) schedule)
                              (some #(= :intra-machine (:leg-type %)) schedule))
                         true)))))

(defspec ^{:doc "Property: Pure Sans-IO N x M multi-machine cluster execution converges on identical RFC 6962 Episode Merkle Roots across all N machines with 1 VRAM weight allocation per machine."}
  prop-nxm-multi-machine-merkle-convergence-and-vram-isolation 20
  (prop/for-all [n-machines (gen/choose 2 3)
                 m-agents (gen/choose 1 3)
                 seed (gen/choose 1000 999999)]
                (let [res (cluster/run-in-memory-cluster-scenario
                           {:machines n-machines
                            :agents-per-machine m-agents
                            :mode :seeded-mock
                            :seed seed
                            :max-new-tokens 12})
                      per-machine (:machines-summary res)
                      roots (mapv :episode-merkle-root (vals per-machine))]
                  (and (:ok? res)
                       (= n-machines (count per-machine))
                       (= 1 (count (distinct roots)))
                       (= (:converged-merkle-root res) (first roots))
                       (every? (fn [[_ msum]]
                                 (and (= 1 (get-in msum [:vram-proof :weight-allocations]))
                                      (= (:agent-count msum) (get-in msum [:vram-proof :kv-cache-count]))
                                      (true? (get-in msum [:vram-proof :shared-device-weights?]))
                                      (true? (get-in msum [:vram-proof :isolated-kv-atoms?]))
                                      (:ok? (:trace-verification msum))))
                               per-machine)))))

(deftest cli-flag-inventory-and-cluster-parsing-test
  (testing "Rule 6 flag inventory: main/parse-cli-args parses all Phase 1 & Phase 2 N x M cluster CLI flags accurately"
    (let [parsed (main/parse-cli-args
                  ["--scenario" "cluster-hello"
                   "--machine-id" "nexus"
                   "--machines" "2"
                   "--agents-per-machine" "2"
                   "--machine-agents" "nexus=2,vector=2"
                   "--turns" "4"
                   "--subnet-id" "hive/swarm-01"
                   "--bind-host" "192.168.86.36"
                   "--bind-port" "5610"
                   "--peers" "nexus=192.168.86.36:5610,vector=192.168.86.26:5610"
                   "--mode" "seeded-mock"
                   "--seed" "20261118"])]
      (is (= :cluster-hello (:scenario parsed)))
      (is (= :nexus (:machine-id parsed)))
      (is (= 2 (:machines parsed)))
      (is (= 2 (:agents-per-machine parsed)))
      (is (= {:nexus 2 :vector 2} (:machine-agents parsed)))
      (is (= 4 (:turns parsed)))
      (is (= "hive/swarm-01" (:subnet-id parsed)))
      (is (= "192.168.86.36" (:bind-host parsed)))
      (is (= 5610 (:bind-port parsed)))
      (is (= {:nexus {:host "192.168.86.36" :port 5610}
              :vector {:host "192.168.86.26" :port 5610}}
             (:peers parsed)))
      (is (= :seeded-mock (:mode parsed)))
      (is (= 20261118 (:seed parsed))))))

(deftest live-tcp-p2p-2x2-cluster-loopback-test
  (testing "Two machine home-nodes (:nexus and :vector) communicate over live TCP P2P sockets and converge on identical 4-turn Episode Merkle Roots"
    (let [peers {:nexus {:host "127.0.0.1" :port 15610}
                 :vector {:host "127.0.0.1" :port 15611}}
          base-opts {:scenario :cluster-hello
                     :machines 2
                     :agents-per-machine 2
                     :subnet-id "hive/swarm-01"
                     :peers peers
                     :mode :seeded-mock
                     :seed 20261118
                     :timeout-ms 30000}
          fut-nexus (future (cluster/run-live-machine-cluster-node!
                             (assoc base-opts :machine-id :nexus :bind-host "127.0.0.1" :bind-port 15610)))
          fut-vector (future (cluster/run-live-machine-cluster-node!
                              (assoc base-opts :machine-id :vector :bind-host "127.0.0.1" :bind-port 15611)))
          res-nexus (deref fut-nexus 45000 {:timeout true})
          res-vector (deref fut-vector 45000 {:timeout true})]
      (is (:ok? res-nexus) (str "Nexus node failed: " (pr-str (dissoc res-nexus :episode-trace))))
      (is (:ok? res-vector) (str "Vector node failed: " (pr-str (dissoc res-vector :episode-trace))))
      (is (:converged? res-nexus))
      (is (:converged? res-vector))
      (is (= (:converged-merkle-root res-nexus) (:converged-merkle-root res-vector)))
      (is (= 4 (count (:turns-telemetry res-nexus))))
      (is (= 1 (get-in res-nexus [:vram-proof :weight-allocations])))
      (is (= 2 (get-in res-nexus [:vram-proof :kv-cache-count])))
      (is (= 1 (get-in res-vector [:vram-proof :weight-allocations])))
      (is (= 2 (get-in res-vector [:vram-proof :kv-cache-count]))))))

;; =============================================================================
;; 5. Live Gemma 4 E2B BF16 Shared-VRAM Two-Agent E2E Scenario Test
;; =============================================================================

(deftest e2e-gemma4-e2b-shared-vram-hello-scenario-test
  (testing "Two Gemma 4 E2B agents share 1 VRAM weight allocation with 2 isolated KV caches and say hello over clj-datachannel/bitecho/mealy/clj-einsum"
    (let [model-dir (kv/resolve-model-dir nil)
          res (hello/run-hello-scenario {:mode :live-gemma4
                                         :model-dir model-dir
                                         :precision :bf16
                                         :max-new-tokens 24
                                         :max-seq-len 256
                                         :temperature 0.0
                                         :seed 20261118})
          ep-trace (:episode-trace res)
          trace-ver (trace/verify-episode-trace ep-trace)]
      (is (:ok? res) (str "Scenario failed: " (pr-str (dissoc res :stack))))
      (is (:ok? trace-ver) (str "Trace verification failed: " (pr-str trace-ver)))
      (is (= 1 (get-in res [:vram-proof :weight-allocations])))
      (is (= 2 (get-in res [:vram-proof :kv-cache-count])))
      (is (true? (get-in res [:vram-proof :shared-device-weights?])))
      (is (true? (get-in res [:vram-proof :isolated-kv-atoms?])))
      (is (not (str/blank? (:alpha-greeting res))))
      (is (not (str/blank? (:beta-reply res))))
      (is (= (:alpha-greeting res) (get-in res [:wire-receipts :beta-received-from-alpha])))
      (is (= (:beta-reply res) (get-in res [:wire-receipts :alpha-received-from-beta])))
      (is (every? :ok? (vals (:mealy-replay-checks res)))))))
