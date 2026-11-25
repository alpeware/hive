(ns bitecho.hive.seed-to-round-test
  "Strict TDD generative & unit tests for `bitecho.hive.seed-to-round` —
   Full Bitecho Seed-to-A-Round + Network Evolution Monte Carlo simulation
   anchored to ratified Tokenomics v2.1 (42B hard cap, BIP-009 theta* seed 1795112000)
   and pinned Genesis/Circle spec CID `8039db926a1d9ee5ab7b5a9c198512de20dd79def8e7687ed8e3c1b0e28d14d6`."
  (:require [bitecho.hive.genesis :as genesis]
            [bitecho.hive.seed-to-round :as s2r]
            [bitecho.hive.sim.lifecycle :as lifecycle]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defspec ^{:doc "42B ECHO hard-cap conservation and Cycle Deflation Theorem (M_t <= 0.5 * R_trailing) hold on every month of every trajectory."}
  prop-42b-hard-cap-conservation-every-month 40
  (prop/for-all [scenario-idx (gen/choose 0 5)
                 seed (gen/choose 1 1000000)]
                (let [scenario (nth (s2r/v21-vesting-scenarios) scenario-idx)
                      traj (s2r/simulate-seed-to-round-trajectory scenario seed)]
                  (and (= genesis/GENESIS-SPEC-CID (:spec-cid traj))
                       (= s2r/HARD-CAP-ECHO (:hard-cap traj))
                       (true? (:all-months-conserved? traj))
                       (every? (fn [m]
                                 (and (= s2r/HARD-CAP-ECHO (s2r/sum-supply-buckets m))
                                      (<= (long (:minted-this-month m))
                                          (long (Math/floor (* 0.5 (double (:trailing-burned-revenue m))))))))
                               (:monthly-trace traj))))))

(defspec ^{:doc "Genesis Partner SLA breaches strictly pause partner vesting (0 released in breached months)."}
  prop-sla-breach-pauses-partner-vesting 30
  (prop/for-all [seed (gen/choose 1 1000000)]
                (let [shock-scenario (first (filter #(= :v2.1-partner-sla-shock (:scenario-id %))
                                                    (s2r/v21-vesting-scenarios)))
                      traj (s2r/simulate-seed-to-round-trajectory shock-scenario seed)]
                  (every? (fn [m]
                            (if (:partner-sla-breach? m)
                              (zero? (long (:partners-vested-delta m)))
                              true))
                          (:monthly-trace traj)))))

(defspec ^{:doc "At Month 36 (epoch 26,280), all remaining Bootstrap Reserve is permanently burned to 0."}
  prop-year3-bootstrap-sunset-burn-exact 30
  (prop/for-all [scenario-idx (gen/choose 0 5)
                 seed (gen/choose 1 1000000)]
                (let [scenario (nth (s2r/v21-vesting-scenarios) scenario-idx)
                      traj (s2r/simulate-seed-to-round-trajectory scenario seed)
                      m36 (last (:monthly-trace traj))]
                  (and (= 36 (:month m36))
                       (= s2r/SUNSET-EPOCH (:epoch m36))
                       (zero? (long (:bootstrap-locked m36)))
                       (= s2r/HARD-CAP-ECHO (s2r/sum-supply-buckets m36))))))

(defspec ^{:doc "Multi-scenario Monte Carlo ensemble is deterministic, conserves 42B across all trials, and ranks highest path likelihood first."}
  prop-seed-to-round-mc-determinism-and-ranking 15
  (prop/for-all [seed (gen/choose 1 100000)
                 trials (gen/choose 10 25)]
                (let [r1 (s2r/run-seed-to-round-ensemble {:seed seed :trials-per-scenario trials})
                      r2 (s2r/run-seed-to-round-ensemble {:seed seed :trials-per-scenario trials})
                      best (:selected-future r1)
                      max-p (apply max (map :a-round-path-likelihood (:scenario-summaries r1)))]
                  (and (= r1 r2)
                       (= genesis/GENESIS-SPEC-CID (:spec-cid r1))
                       (= s2r/BIP009-THETA-SEED (:theta-anchor-seed r1))
                       (= 6 (count (:scenario-summaries r1)))
                       (every? :all-trials-conserved? (:scenario-summaries r1))
                       (= max-p (:a-round-path-likelihood best))
                       (seq (:surprising-findings r1))))))

(defspec ^{:doc "Parameterized k_bps (<= 5000), tau_bps (<= 200), and anchor-availability preserve 42B conservation and k_bps mint cap."}
  prop-sensitivity-sweep-conservation-and-bounds 35
  (prop/for-all [k-bps (gen/elements [1000 2500 3500 5000])
                 tau-bps (gen/elements [10 25 50 100 200])
                 anchor-p (gen/elements [0.50 0.70 0.85 0.95])
                 seed (gen/choose 1 100000)]
                (let [base (first (s2r/v21-vesting-scenarios))
                      sc (assoc base :k-bps k-bps :tau-bps tau-bps :anchor-availability anchor-p)
                      traj (s2r/simulate-seed-to-round-trajectory sc seed)
                      k-frac (/ (double k-bps) 10000.0)]
                  (and (true? (:all-months-conserved? traj))
                       (every? (fn [m]
                                 (and (= s2r/HARD-CAP-ECHO (s2r/sum-supply-buckets m))
                                      (<= (long (:minted-this-month m))
                                          (long (Math/floor (* k-frac (double (:trailing-burned-revenue m))))))))
                               (:monthly-trace traj))))))

(deftest unit-overnight-v21-ensemble-granularity-and-surprises
  (testing "Full 6-scenario x 50-trial ensemble produces granular metrics, surprises, and valid root CID"
    (let [res (s2r/run-seed-to-round-ensemble {:seed 1795112000 :trials-per-scenario 50})]
      (is (= 300 (:total-trajectories res)))
      (is (= 6 (count (:scenario-summaries res))))
      (is (every? :all-trials-conserved? (:scenario-summaries res)))
      (is (string? (:ensemble-root-cid res)))
      (is (= 64 (count (:ensemble-root-cid res))))
      (is (>= (count (:surprising-findings res)) 4))
      (is (some? (get-in res [:selected-future :scenario-id])))))

  (testing "Sweep #2 sensitivity grid interrogates 2-of-2 anchor cliff and deflationary crossover deterministically"
    (let [s1 (s2r/run-sensitivity-sweep {:seed 1795112000 :trials-per-cell 10})
          s2 (s2r/run-sensitivity-sweep {:seed 1795112000 :trials-per-cell 10})]
      (is (= s1 s2))
      (is (= 144 (count (:grid-cells s1))))
      (is (every? :all-trials-conserved? (:grid-cells s1)))
      (is (map? (:anchor-cliff-interrogation s1)))
      (is (map? (:deflationary-crossover-interrogation s1)))
      (is (= 64 (count (:sweep-root-cid s1)))))))

;; =============================================================================
;; V3 Endogenous Seed-to-A Piping Tests (Strict TDD)
;; =============================================================================

(defspec ^{:doc "V3 Endogenous seed_to_round preserves exact 42B ECHO 7-bucket conservation every month, Cycle Deflation Theorem (M_t <= k * R_trailing), Year-3 sunset burn to 0, and CRN determinism across agent-scale multipliers."}
  prop-endogenous-seed-to-round-42b-conservation-and-determinism 20
  (prop/for-all [scale (gen/elements [1.0 22.0 60.0 108.0 500.0])
                 k-bps (gen/elements [2500 5000])
                 tau-bps (gen/elements [25 50 100])]
                (let [trial {:scenario :baseline-g4-angel-heavy
                             :seed 20261124
                             :time-compression-factor 72
                             :epochs 450
                             :cleared-gmv-ech 7200000
                             :market-burned-ech 36000
                             :per-capability-gmv-ech {:llm-inference 3000000
                                                      :web-search-extract 1800000
                                                      :sandbox-compute 1200000
                                                      :eval-verify 600000
                                                      :storage-bytes-at-rest 300000
                                                      :bandwidth-bytes-in-motion 300000}
                             :per-subnet-gmv-ech {"root/markets/larg-tac" 2500000
                                                  "root/markets/midi-sim" 2000000
                                                  "root/markets/clear-games" 1500000
                                                  "root/markets/trustx-verify" 1200000}
                             :partner-vesting {:partner-larg-tac {:vested-ech 360000000 :vested-claims 32 :paused-claims 0}
                                               :partner-midi-sim {:vested-ech 360000000 :vested-claims 32 :paused-claims 0}}
                             :g4-angels {:onboarded? true :seed-closed? true :anchor-threshold 2}
                             :protocol-infra {:upgrades-executed 15 :upgrade-stalls 0 :rotations-verified 7}
                             :endogenous-providers-entered 2
                             :endogenous-providers-exited 0}
                      r1 (s2r/run-endogenous-seed-to-round
                          {:trial-telemetry trial
                           :agent-scale-multiplier scale
                           :k-bps k-bps
                           :tau-bps tau-bps})
                      r2 (s2r/run-endogenous-seed-to-round
                          {:trial-telemetry trial
                           :agent-scale-multiplier scale
                           :k-bps k-bps
                           :tau-bps tau-bps})
                      m36 (last (:monthly-trace r1))
                      k-frac (/ (double k-bps) 10000.0)]
                  (and (= r1 r2)
                       (true? (:all-months-conserved? r1))
                       (= 36 (count (:monthly-trace r1)))
                       (zero? (long (:bootstrap-locked m36)))
                       (= s2r/SUNSET-EPOCH (:epoch m36))
                       (every? (fn [m]
                                 (and (= s2r/HARD-CAP-ECHO (s2r/sum-supply-buckets m))
                                      (<= (long (:minted-this-month m))
                                          (long (Math/floor (* k-frac (double (:trailing-burned-revenue m))))))))
                               (:monthly-trace r1))))))

(deftest unit-endogenous-seed-to-round-lifecycle-piping-and-agent-scale-sweep
  (testing "Piping real lifecycle/run-full-network-trial telemetry into run-endogenous-seed-to-round across 550 / 1,500 / 2,700 active autonomous agents conserves 42B every month and scales GMV monotonically"
    (let [trial (lifecycle/run-full-network-trial
                 {:scenario :high-velocity-commercial
                  :seed 20261124
                  :time-compression-factor 144
                  :epochs 230})
          sweep (s2r/run-endogenous-scale-sweep
                 {:trial-telemetry trial
                  :target-agent-counts [550 1500 2700]})
          by-agents (into {} (map (juxt :target-active-agents identity) (:results sweep)))]
      (is (= 3 (count (:results sweep))))
      (is (true? (:all-conserved? sweep)))
      (doseq [n [550 1500 2700]]
        (let [r (get by-agents n)]
          (is (true? (:all-months-conserved? r)))
          (is (= n (:target-active-agents r)))
          (is (pos? (:mean-monthly-gmv-ech r)))
          (is (pos? (:cumulative-burned-revenue r)))
          (is (map? (:cumulative-per-capability-burn-ech r)))))
      ;; Monotonic GMV and burn scaling with fleet size
      (is (< (:mean-monthly-gmv-ech (get by-agents 550))
             (:mean-monthly-gmv-ech (get by-agents 1500))))
      (is (< (:mean-monthly-gmv-ech (get by-agents 1500))
             (:mean-monthly-gmv-ech (get by-agents 2700)))))))
