(ns bitecho.hive.seed-to-round-test
  "Strict TDD generative & unit tests for `bitecho.hive.seed-to-round` —
   Full Bitecho Seed-to-A-Round + Network Evolution Monte Carlo simulation
   anchored to ratified Tokenomics v2.1 (42B hard cap, BIP-009 theta* seed 1795112000)
   and pinned Genesis/Circle spec CID `8039db926a1d9ee5ab7b5a9c198512de20dd79def8e7687ed8e3c1b0e28d14d6`."
  (:require [bitecho.hive.genesis :as genesis]
            [bitecho.hive.seed-to-round :as s2r]
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

(deftest unit-overnight-v21-ensemble-granularity-and-surprises
  (testing "Full 6-scenario x 50-trial ensemble produces granular metrics, surprises, and valid root CID"
    (let [res (s2r/run-seed-to-round-ensemble {:seed 1795112000 :trials-per-scenario 50})]
      (is (= 300 (:total-trajectories res)))
      (is (= 6 (count (:scenario-summaries res))))
      (is (every? :all-trials-conserved? (:scenario-summaries res)))
      (is (string? (:ensemble-root-cid res)))
      (is (= 64 (count (:ensemble-root-cid res))))
      (is (>= (count (:surprising-findings res)) 4))
      (is (some? (get-in res [:selected-future :scenario-id]))))))
