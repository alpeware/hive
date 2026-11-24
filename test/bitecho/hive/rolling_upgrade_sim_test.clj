(ns bitecho.hive.rolling-upgrade-sim-test
  "Strict TDD generative (`clojure.test.check`) tests for the pure Sans-IO
   Streamlet BFT Rolling-Upgrade SLA Simulator (`bitecho.hive.rolling-upgrade-sim`)."
  (:require [bitecho.hive.rolling-upgrade-sim :as sim]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private gen-committee-size
  (gen/elements [3 4 6 7]))

(def ^:private gen-epoch-ms
  (gen/elements [5000 10000]))

(def ^:private gen-policy
  (gen/elements sim/ALL-POLICIES))

(def ^:private gen-restart-mode
  (gen/elements [:fast :realistic]))

(defspec ^{:doc "Property 1 (Determinism): identical seed + config produces bit-identical metrics and trace."}
  prop-determinism
  40
  (prop/for-all [seed (gen/choose 1 100000)
                 n gen-committee-size
                 epoch-ms gen-epoch-ms
                 policy gen-policy
                 restart-mode gen-restart-mode
                 jitter (gen/elements [0.0 0.02])]
                (let [cfg {:seed seed
                           :committee-size n
                           :epoch-ms epoch-ms
                           :policy policy
                           :restart-mode restart-mode
                           :jitter-prob-per-epoch jitter
                           :record-trace? true}
                      r1 (sim/run-trial cfg)
                      r2 (sim/run-trial cfg)]
                  (= r1 r2))))

(defspec ^{:doc "Property 2 (Liveness / Termination): all 4 policies eventually converge all N/N nodes to :v2 within bounded epochs."}
  prop-liveness-termination
  40
  (prop/for-all [seed (gen/choose 1 100000)
                 n gen-committee-size
                 epoch-ms gen-epoch-ms
                 policy gen-policy
                 restart-mode gen-restart-mode
                 jitter (gen/elements [0.0 0.02])]
                (let [cfg {:seed seed
                           :committee-size n
                           :epoch-ms epoch-ms
                           :policy policy
                           :restart-mode restart-mode
                           :jitter-prob-per-epoch jitter
                           :max-epochs 400}
                      res (sim/run-trial cfg)]
                  (and (true? (:converged? res))
                       (<= (:epochs-run res) 400)
                       (>= (:release-latency-ms res) 0)
                       (>= (:stall-ms res) 0)
                       (>= (:max-single-stall-ms res) 0)
                       (<= (:max-single-stall-ms res) (:stall-ms res))))))

(defspec ^{:doc "Property 3 (Safety / Guard Dominance): under zero background jitter and T_restart < (N - 1) * epoch-ms, :post-rotation-full-quorum-guard achieves 0 quorum stalls, 0 missed-leader slots, and 0 ms stall for all N >= 3."}
  prop-full-quorum-guard-zero-stall-safety
  50
  (prop/for-all [seed (gen/choose 1 100000)
                 n gen-committee-size
                 epoch-ms gen-epoch-ms
                 skew-max (gen/elements [0 500 1500 4000])
                 restart-frac (gen/choose 1 95)]
                (let [max-safe-ms (dec (* (dec n) epoch-ms))
                      fixed-restart-ms (max 1000 (quot (* max-safe-ms restart-frac) 100))
                      cfg {:seed seed
                           :committee-size n
                           :epoch-ms epoch-ms
                           :policy :post-rotation-full-quorum-guard
                           :restart-mode :fixed
                           :fixed-restart-ms fixed-restart-ms
                           :pointer-skew-max-ms skew-max
                           :jitter-prob-per-epoch 0.0
                           :max-epochs 400}
                      res (sim/run-trial cfg)
                      diag (:diagnostics res)]
                  (and (true? (:converged? res))
                       (true? (:zero-stall? res))
                       (zero? (:stall-ms res))
                       (zero? (:max-single-stall-ms res))
                       (zero? (:quorum-stall diag))
                       (zero? (:missed-leader-slot diag))
                       (zero? (:finalization-stall diag))
                       (<= (:max-concurrent-restarting res) 1)))))

(defspec ^{:doc "Property 4 (Unguarded Cascade Proof): when T_restart > epoch-ms (e.g. 12s restart on 5s epochs), :post-rotation-unguarded triggers concurrent restarts (>= 2) and quorum stalls on N=3 (Q=2) and N=6 (Q=4), while :post-rotation-full-quorum-guard stays 0-stall on N=6."}
  prop-unguarded-cascade-proof
  30
  (prop/for-all [seed (gen/choose 1 100000)
                 n (gen/elements [3 6])
                 restart-ms (gen/choose 11000 14000)]
                (let [base-cfg {:seed seed
                                :committee-size n
                                :epoch-ms 5000
                                :restart-mode :fixed
                                :fixed-restart-ms restart-ms
                                :pointer-skew-max-ms 0
                                :jitter-prob-per-epoch 0.0
                                :max-epochs 300}
                      unguarded (sim/run-trial (assoc base-cfg :policy :post-rotation-unguarded))
                      full-guard (sim/run-trial (assoc base-cfg :policy :post-rotation-full-quorum-guard))]
                  (and (true? (:converged? unguarded))
                       (>= (:max-concurrent-restarting unguarded) 2)
                       (pos? (get-in unguarded [:diagnostics :quorum-stall]))
                       (pos? (:stall-ms unguarded))
                       (true? (:converged? full-guard))
                       ;; On N=6, (N-1)*5000 = 25000ms > 14000ms, so full guard achieves 0 stall!
                       (if (= n 6)
                         (and (zero? (:stall-ms full-guard))
                              (zero? (get-in full-guard [:diagnostics :quorum-stall])))
                         true)))))

(deftest test-quorum-size-streamlet
  (testing "Streamlet quorum Q = ceil(2N/3)"
    (is (= 2 (sim/quorum-size 3)))
    (is (= 3 (sim/quorum-size 4)))
    (is (= 4 (sim/quorum-size 5)))
    (is (= 4 (sim/quorum-size 6)))
    (is (= 5 (sim/quorum-size 7)))))

(deftest test-zero-silent-failures-diagnostics
  (testing "Deferred rolls and stalls are explicitly counted in :diagnostics"
    (let [cfg {:seed 42
               :committee-size 6
               :epoch-ms 5000
               :policy :post-rotation-full-quorum-guard
               :restart-mode :fixed
               :fixed-restart-ms 12000
               :pointer-skew-max-ms 0
               :jitter-prob-per-epoch 0.0}
          res (sim/run-trial cfg)
          diag (:diagnostics res)]
      (is (:converged? res))
      (is (zero? (:stall-ms res)))
      ;; Because T_restart (12s) spans > 2 epochs (5s each), subsequent leaders must defer
      (is (pos? (+ (:roll-deferred-prev-leader-down diag)
                   (:roll-deferred-quorum-guard diag)))))
    (let [cfg-prev {:seed 42
                    :committee-size 4
                    :epoch-ms 5000
                    :policy :post-rotation-prev-leader-guard
                    :restart-mode :fixed
                    :fixed-restart-ms 12000
                    :pointer-skew-max-ms 0
                    :jitter-prob-per-epoch 0.0}
          res-prev (sim/run-trial cfg-prev)
          res-full (sim/run-trial (assoc cfg-prev :policy :post-rotation-full-quorum-guard))]
      (is (:converged? res-prev))
      ;; On N=4 (Q=3) with 12s restart on 5s epochs:
      ;; prev-leader-guard checks (k-1) mod 4, so k=0 rolls at 5s [5s,17s), k=1 defers at 10s,
      ;; k=2 sees k=1 online at 15s and rolls [15s,27s) -> 0 & 2 overlap -> quorum stall!
      (is (>= (:max-concurrent-restarting res-prev) 2))
      (is (pos? (get-in res-prev [:diagnostics :quorum-stall])))
      (is (pos? (:stall-ms res-prev)))
      ;; Full quorum guard waits until all 4 peers are online -> 0 quorum stalls, 0 ms stall!
      (is (zero? (get-in res-full [:diagnostics :quorum-stall])))
      (is (zero? (:stall-ms res-full))))))

(deftest test-sweep-aggregation
  (testing "run-sweep aggregates metrics across policies and formats markdown"
    (let [results (sim/run-sweep {:committee-size 6
                                  :epoch-ms 5000
                                  :restart-mode :fast
                                  :jitter-prob-per-epoch 0.0}
                                 sim/ALL-POLICIES
                                 10)
          md (sim/format-sweep-markdown "Test Sweep" results)]
      (is (= 4 (count results)))
      (is (every? #(= 10 (:trials %)) results))
      (is (string? md))
      (is (.contains md "| Policy |")))))
