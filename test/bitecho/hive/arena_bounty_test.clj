(ns bitecho.hive.arena-bounty-test
  "Rule 1 Generative test.check invariants and Rule 6 CLI flag inventory tests
   for Hive Gate 0 (`--scenario arena-bounty`):
   1. `prop-arena-ech-conservation-and-budget-floor`
   2. `prop-arena-adversarial-trifecta-blocked`
   3. `prop-arena-vopr-fault-resilience-and-zero-silent-failures`
   4. `prop-arena-zero-capability-replay-and-mut01`
   5. `cli-flag-inventory-arena-bounty-test`."
  (:require [bitecho.hive.kv :as kv]
            [bitecho.hive.main :as main]
            [bitecho.hive.scenarios.arena-bounty :as arena]
            [bitecho.hive.trace :as trace]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.cell.reducer :as reducer]))

;; =============================================================================
;; 1. Economic Conservation & Budget Floor Guard (--min-balance 2000)
;; =============================================================================

(defspec ^{:doc "Property: Across randomized seeds, bounty amounts, and starting balances, sum(end_balances) + total_burns == sum(start_balances), no agent violates --min-balance, and rogue over-floor spends emit 0 wire envelopes."}
  prop-arena-ech-conservation-and-budget-floor 20
  (prop/for-all [seed (gen/choose 1000 999999)
                 bounty-amount (gen/choose 200 800)
                 rogue-extra (gen/choose 50 199)]
                (let [min-balance 2000
                      rogue-start (+ min-balance rogue-extra)
                      res (arena/run-arena-bounty-scenario
                           {:seed seed
                            :mode :seeded-mock
                            :fault-profile :lossy-10pct
                            :bounty-amount bounty-amount
                            :min-balance min-balance
                            :starting-balances {:arena/requester 5000
                                                :arena/auditor 3000
                                                :arena/worker-0 2500
                                                :arena/worker-1 2500
                                                :arena/worker-2 2500
                                                :arena/worker-3 2500
                                                :arena/worker-4 2500
                                                :arena/worker-5 2500
                                                :arena/rogue rogue-start
                                                :arena/observer 3000}})
                      econ (:economics res)
                      start-sum (reduce + (vals (:start-balances econ)))
                      end-sum (reduce + (vals (:end-balances econ)))
                      total-burns (:total-burned econ)
                      attack-a (get-in res [:adversarial-trifecta :attack-a-budget-drain])]
                  (and (:ok? res)
                       (:conserved? econ)
                       (= start-sum (+ end-sum total-burns))
                       (pos? total-burns)
                       (every? (fn [[_ bal]] (>= (long bal) min-balance))
                               (:end-balances econ))
                       ;; Attack A blocked pre-emission with 0 wire bytes/envelopes
                       (:blocked? attack-a)
                       (= "insufficient-balance-floor" (:error attack-a))
                       (= :request-faucet-topup (first (:event attack-a)))
                       (zero? (long (:wire-envelopes-emitted attack-a)))
                       (zero? (long (:burned-ech attack-a)))))))

;; =============================================================================
;; 2. Adversarial Trifecta Blocked (Budget Drain + Frozen Kernel + Broken SCI Code)
;; =============================================================================

(def ^:private gen-frozen-key
  (gen/elements (vec reducer/frozen-dispatch-values)))

(def ^:private gen-broken-code
  (gen/elements
   ["(defn run-length-encode [xs] [[:broken 0]])"
    "(defn run-length-encode [xs] (mapv (fn [x] [x 999]) xs))"
    "(defn run-length-encode [xs] (/ 1 0))"
    "(defn run-length-encode [xs] nil)"]))

(defspec ^{:doc "Property: Across all frozen kernel keys (mealy.cell.reducer/frozen-dispatch-values) and invalid Clojure payloads, :arena/rogue never mutates kernel dispatch and never triggers escrow release."}
  prop-arena-adversarial-trifecta-blocked 25
  (prop/for-all [seed (gen/choose 1000 999999)
                 frozen-key gen-frozen-key
                 broken-code gen-broken-code]
                (let [hijack-code (str "(defmethod mealy.cell.reducer/handle-event " frozen-key
                                       " [state event] {:state (assoc state :pwned true) :actions []})")
                      res (arena/run-arena-bounty-scenario
                           {:seed seed
                            :mode :seeded-mock
                            :fault-profile :reliable
                            :rogue-hijack-code hijack-code
                            :rogue-broken-code broken-code})
                      trifecta (:adversarial-trifecta res)
                      attack-a (:attack-a-budget-drain trifecta)
                      attack-b (:attack-b-kernel-hijack trifecta)
                      attack-c (:attack-c-broken-deliverable trifecta)
                      escrow (:escrow res)]
                  (and (:ok? res)
                       (:all-three-neutralized? trifecta)
                       ;; Attack A: Budget floor
                       (:blocked? attack-a)
                       (= :request-faucet-topup (first (:event attack-a)))
                       ;; Attack B: Frozen Kernel verify gate
                       (:blocked? attack-b)
                       (= :frozen-kernel-violation (:error attack-b))
                       (= frozen-key (:frozen-key attack-b))
                       ;; Attack C: Deterministic SCI verifier gate
                       (:blocked? attack-c)
                       (false? (boolean (:tests-passed? attack-c)))
                       (not= :arena/rogue (:winner-id escrow))
                       (= :settled (:status escrow))))))

;; =============================================================================
;; 3. VOPR Fault Resilience & Rule 5 Zero Silent Failures
;; =============================================================================

(defspec ^{:doc "Property: Under randomized VOPR drop rates (0%..25%), every dropped frame increments :diagnostics :dropped (Rule 5), retransmits deterministically, and still converges to a verified episode trace."}
  prop-arena-vopr-fault-resilience-and-zero-silent-failures 20
  (prop/for-all [seed (gen/choose 1000 999999)
                 drop-pct (gen/choose 0 25)]
                (let [p-loss (/ (double drop-pct) 100.0)
                      res (arena/run-arena-bounty-scenario
                           {:seed seed
                            :mode :seeded-mock
                            :fault-profile :custom
                            :fault-config {:p-loss-good p-loss
                                           :p-loss-bad (min 0.50 (* 2.0 p-loss))
                                           :p-good->bad 0.05
                                           :p-bad->good 0.50}})
                      vopr-stats (:vopr-stats res)
                      trace-ver (:trace-verification res)]
                  (and (:ok? res)
                       (:ok? trace-ver)
                       (pos? (:sent vopr-stats 0))
                       (pos? (:delivered vopr-stats 0))
                       ;; Every dropped frame is counted in diagnostics and retransmitted (Rule 5)
                       (= (:dropped vopr-stats 0) (:retransmitted vopr-stats 0))
                       (= (:dropped vopr-stats 0) (:diagnostics-dropped-total vopr-stats 0))
                       (= (:sent vopr-stats 0)
                          (+ (:delivered vopr-stats 0) (:dropped vopr-stats 0)))
                       (zero? (:silent-failures vopr-stats 0))))))

;; =============================================================================
;; 4. Zero-Capability Replay & MUT-01 Tamper Detection
;; =============================================================================

(defspec ^{:doc "Property: Identical seeds produce identical RFC 6962 Merkle roots; all 10 agents pass replay-graph with zero capabilities; 1-bit tamper in any turn/KV blob fails verify-episode-trace."}
  prop-arena-zero-capability-replay-and-mut01 15
  (prop/for-all [seed (gen/choose 1000 999999)
                 tamper-idx-raw (gen/choose 0 99)]
                (let [opts {:seed seed
                            :mode :seeded-mock
                            :fault-profile :lossy-10pct}
                      run-1 (arena/run-arena-bounty-scenario opts)
                      run-2 (arena/run-arena-bounty-scenario opts)
                      trace-1 (:episode-trace run-1)
                      trace-2 (:episode-trace run-2)
                      turns (:turns trace-1)
                      t-idx (mod tamper-idx-raw (count turns))
                      orig-text (get-in turns [t-idx :turn/output-text] "")
                      mutated-text (str orig-text "X")
                      tampered-trace (assoc-in trace-1 [:turns t-idx :turn/output-text] mutated-text)
                      tampered-ver (trace/verify-episode-trace tampered-trace)
                      replays (:mealy-replay-checks run-1)]
                  (and (:ok? run-1)
                       (:ok? run-2)
                       (= (:merkle-root trace-1) (:merkle-root trace-2))
                       (= (:turn-cids trace-1) (:turn-cids trace-2))
                       (= 10 (count replays))
                       (every? :ok? (vals replays))
                       ;; Inline MUT-01 probe and arbitrary turn tamper probe both fail verification
                       (true? (get-in run-1 [:mut01-probe :tamper-detected?]))
                       (= :turn-blob-payload-tampered (get-in run-1 [:mut01-probe :error]))
                       (false? (:ok? tampered-ver))
                       (= :turn-blob-payload-tampered (:error tampered-ver))))))

;; =============================================================================
;; 5. Rule 6 CLI Flag Inventory & Upgrade 1 Weights Digest Tests
;; =============================================================================

(deftest cli-flag-inventory-arena-bounty-test
  (testing "Rule 6 flag inventory: main/parse-cli-args parses --scenario arena-bounty, --fault-profile, --local-workers, --requester-model, and --auditor-model cleanly"
    (let [parsed (main/parse-cli-args
                  ["--scenario" "arena-bounty"
                   "--mode" "seeded-mock"
                   "--fault-profile" "lossy-10pct"
                   "--local-workers" "6"
                   "--requester-model" "claude-sonnet-5-5-medium"
                   "--auditor-model" "gemini-3.8-flash-low"
                   "--seed" "20261119"])]
      (is (= :arena-bounty (:scenario parsed)))
      (is (= :seeded-mock (:mode parsed)))
      (is (= :lossy-10pct (:fault-profile parsed)))
      (is (= 6 (:local-workers parsed)))
      (is (= "claude-sonnet-5-5-medium" (:requester-model parsed)))
      (is (= "gemini-3.8-flash-low" (:auditor-model parsed)))
      (is (= 20261119 (:seed parsed))))))

(deftest kv-weights-digest-manifest-hash-test
  (testing "Upgrade 1: kv/compute-model-weights-digest returns a 64-hex SHA-256 string and verify-agent-kv-chain enforces it"
    (let [mock-digest (kv/compute-model-weights-digest ".models/gemma-4-E2B-it" :bf16 :seeded-mock)
          live-digest (kv/compute-model-weights-digest (kv/resolve-model-dir nil) :bf16 :live-gemma4)
          mux (kv/init-shared-kv-multiplexer {:mode :seeded-mock
                                              :agent-ids [:arena/worker-0]
                                              :seed 20261119})
          [mux1 turn-res] (kv/generate-agent-turn! mux :arena/worker-0 "Solve RLE task")
          entry (:kv-entry turn-res)]
      (is (string? mock-digest))
      (is (= 64 (count mock-digest)))
      (is (re-matches #"[0-9a-f]{64}" mock-digest))
      (is (string? live-digest))
      (is (= 64 (count live-digest)))
      (is (re-matches #"[0-9a-f]{64}" live-digest))
      (is (= (:weights-digest mux1) (:kv/weights-digest entry)))
      (is (:ok? (kv/verify-agent-kv-chain mux1 :arena/worker-0)))
      ;; Tampering with weights-digest in mux fails verify-agent-kv-chain
      (let [bad-mux (assoc mux1 :weights-digest (str/join (repeat 64 "0")))
            bad-ver (kv/verify-agent-kv-chain bad-mux :arena/worker-0)]
        (is (false? (:ok? bad-ver)))
        (is (= :weights-digest-mismatch (:error bad-ver)))))))
