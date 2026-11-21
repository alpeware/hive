(ns bitecho.hive.top-loop-test
  "Strict TDD generative (`clojure.test.check`) tests for the Run-3 Top Loop
   (`bitecho.hive.top-loop`): hash-anchored repo recursion, hard real-consent +
   telemetry gate on pointer promotion, and self-regulating staged rollout."
  (:require [bitecho.hive.env :as env]
            [bitecho.hive.top-loop :as top]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defn- fake-cid [s] (env/sha256-str (str s)))

(defn- valid-real-receipt
  [patch-cid]
  {:simulation? false
   :advisory-only false
   :patch-cid patch-cid
   :receipt-cid (fake-cid (str "real:" patch-cid))
   :verdicts {:simon :consent :axiom :consent :nexus :consent :vector :consent :lumen :consent}})

(defspec ^{:doc "Property: Pointer promotion fails closed on :simulation? true receipts or failing telemetry; valid real consent + green rollout advances hash-anchored repo chain."}
  prop-top-loop-hash-chain-and-gated-promotion
  35
  (prop/for-all [seed (gen/choose 1 100000)
                 n-iters (gen/choose 1 4)]
                (let [init-hash (fake-cid (str "repo-0:" seed))
                      st0 (top/init-top-loop {:repo-hash init-hash
                                              :values-cid env/VALUES-DRAFT-CID})]
                  (loop [st st0
                         i 0
                         ok? true]
                    (if (or (not ok?) (>= i n-iters))
                      (and ok?
                           (= n-iters (count (:history st)))
                           (top/verify-top-loop-chain st))
                      (let [p-cid (fake-cid (str "patch:" seed ":" i))
                            t-cid (fake-cid (str "telem:" seed ":" i))
                            [st1 _] (top/record-sim-iteration
                                     st
                                     {:proposal-cid (fake-cid (str "prop:" i))
                                      :sim-trace-cid (fake-cid (str "trace:" i))
                                      :patch-cid p-cid
                                      :telemetry-cid t-cid
                                      :telemetry-green? true})
                            ;; 1. Sim receipt MUST be rejected loudly
                            sim-rcpt (assoc (valid-real-receipt p-cid) :simulation? true :advisory-only true)
                            [st-rej1 eff-rej1] (top/request-pointer-promotion st1 sim-rcpt)
                            ;; 2. Real receipt unlocks staged rollout
                            [st-roll _] (top/request-pointer-promotion st1 (valid-real-receipt p-cid))
                            ;; 3. Walk all 4 staged rollout rings green -> promotes pointer & advances repo-hash
                            st-promoted (reduce (fn [cur _]
                                                  (first (top/advance-rollout-stage
                                                          cur
                                                          {:stage-green? true
                                                           :finalized-epoch-advancing? true
                                                           :silent-drops 0})))
                                                st-roll
                                                (range 4))
                            iter-ok? (and (= :pending-promotion (:phase st-rej1))
                                          (some #(= :diagnose (first %)) eff-rej1)
                                          (= :idle (:phase st-promoted))
                                          (not= (:repo-hash st) (:repo-hash st-promoted)))]
                        (recur st-promoted (inc i) (and ok? iter-ok?))))))))

(defspec ^{:doc "Property: Self-regulating staged rollout immediately triggers :rollback-to-lkg on any degraded stage telemetry."}
  prop-staged-rollout-self-regulation-and-rollback-first
  35
  (prop/for-all [seed (gen/choose 1 100000)
                 fail-at (gen/choose 0 3)]
                (let [init-hash (fake-cid (str "repo:" seed))
                      p-cid (fake-cid (str "patch:" seed))
                      st0 (top/init-top-loop {:repo-hash init-hash :values-cid env/VALUES-DRAFT-CID})
                      [st1 _] (top/record-sim-iteration st0 {:proposal-cid (fake-cid "p")
                                                             :sim-trace-cid (fake-cid "t")
                                                             :patch-cid p-cid
                                                             :telemetry-cid (fake-cid "m")
                                                             :telemetry-green? true})
                      [st-roll _] (top/request-pointer-promotion st1 (valid-real-receipt p-cid))
                      ;; Advance `fail-at` green stages, then inject degraded telemetry
                      st-pre (reduce (fn [cur _]
                                       (first (top/advance-rollout-stage cur {:stage-green? true
                                                                              :finalized-epoch-advancing? true
                                                                              :silent-drops 0})))
                                     st-roll
                                     (range fail-at))
                      [st-rb eff-rb] (top/advance-rollout-stage st-pre {:stage-green? false
                                                                        :finalized-epoch-advancing? false
                                                                        :silent-drops 2})]
                  (and (= :rolled-back (:phase st-rb))
                       (= init-hash (:repo-hash st-rb))
                       (some #(= :rollback-to-lkg (first %)) eff-rb)
                       (some #(= :diagnose (first %)) eff-rb)))))

(deftest test-top-loop-ingest-implementation-circle-artifact
  (testing "Ingesting a Run-2 Lumen Wake-Up patch + telemetry artifact into the Top Loop"
    (let [base-hash (fake-cid "bitecho-73ada39+mealy-13d4e93+hive-3884d3c")
          st0 (top/init-top-loop {:repo-hash base-hash :values-cid env/VALUES-DRAFT-CID})
          [st1 eff1] (top/record-sim-iteration
                      st0
                      {:proposal-cid "35ea533d96aba883d7bc6bece338dc5d6766ae0ac829a135a4171e521b0d4aae"
                       :sim-trace-cid "9bbf36a70eeabdbe462a2b0ef55d0b6b60d686f3b829e6703fbaf66cbe2f7a80"
                       :patch-cid "3208beffb5243091f4c7f072c987ded0af09f001e28b9c447f992838bbd74040"
                       :telemetry-cid (fake-cid "lumen-wakeup-telemetry")
                       :telemetry-green? true})]
      (is (= :pending-promotion (:phase st1)))
      (is (= base-hash (get-in st1 [:pending-candidate :base-repo-hash])))
      (is (some #(= :emit-candidate-ready (first %)) eff1)))))
