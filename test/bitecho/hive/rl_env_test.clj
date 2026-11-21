(ns bitecho.hive.rl-env-test
  "Strict TDD generative (`clojure.test.check`) and Run-1 acceptance tests for
   Hive RL Environment R-1 (`bitecho.hive.env`), R-2 (`bitecho.hive.scorer`),
   and R-3 (`bitecho.hive.trace` v2 Merkle + JSONL export)."
  (:require [bitecho.hive.env :as env]
            [bitecho.hive.scorer :as scorer]
            [bitecho.hive.trace :as trace]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; ---------------------------------------------------------------------------
;; Generators
;; ---------------------------------------------------------------------------

(def ^:private gen-seat-id
  (gen/elements [:simon :axiom :nexus :vector :lumen]))

(def ^:private gen-review-action
  (gen/one-of
   [(gen/fmap (fn [txt]
                {:action/type :message
                 :action/content txt
                 :action/raw-output txt})
              (gen/elements ["Looks clean." "Clarifying question on bounds." "Reacting to amendment."]))
    (gen/return {:action/type :protocol-act
                 :action/content "Consent."
                 :action/protocol {:verb :consent}
                 :action/raw-output "CONSENT"})
    (gen/fmap (fn [flag]
                {:action/type :protocol-act
                 :action/content (str "Consent with addendum: " flag)
                 :action/protocol {:verb :consent-with-addendum
                                   :flags [{:id "F-1" :text flag :owner :vector :target-milestone "R-1"}]}
                 :action/raw-output (str "CONSENT-WITH-ADDENDUM: " flag)})
              (gen/elements ["pin formatter CID" "verify byte-drop seed"]))
    (gen/fmap (fn [harm]
                {:action/type :protocol-act
                 :action/content (str "Paramount objection: " harm)
                 :action/protocol {:verb :paramount-objection
                                   :harm-to-aim harm
                                   :fix "Add explicit precondition check"}
                 :action/raw-output (str "PARAMOUNT-OBJECTION: " harm)})
              (gen/elements ["missing sim-consent isolation" "unbounded round count"]))
    (gen/return {:action/type :tool-call
                 :action/tool {:name :read-file :args {:path "docs/GOVERNANCE.md"}}
                 :action/raw-output "TOOL:read-file docs/GOVERNANCE.md"})
    (gen/return {:action/type :tool-call
                 :action/tool {:name :write-file :args {:path "src/foo.clj" :content "bad"}}
                 :action/raw-output "TOOL:write-file src/foo.clj"})]))

;; ---------------------------------------------------------------------------
;; 1. Generative Property: Fixed-Point Convergence & :unresolved Termination
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: Phase 1 terminates within max-review-rounds at :fixed-point-1 or :unresolved."}
  prop-fixed-point-and-termination
  40
  (prop/for-all [seed (gen/choose 1 100000)
                 max-rounds (gen/choose 1 4)
                 action-pool (gen/vector gen-review-action 5 30)]
                (let [spec (assoc-in (env/run-1-scenario-spec seed)
                                     [:scenario/limits :max-review-rounds] max-rounds)
                      init-st (env/init-env spec seed)
                      final-st
                      (loop [st init-st
                             idx 0]
                        (if (or (env/phase-1-done? st) (> idx 80))
                          st
                          (let [actor (env/next-actor st)
                                raw-act (nth action-pool (mod idx (count action-pool)))
                                ;; During non-consent stages, use :message; during consent-round, use sampled protocol-act
                                act (if (and (= :consent-round (:stage st))
                                             (= :protocol-act (:action/type raw-act)))
                                      raw-act
                                      (if (= :consent-round (:stage st))
                                        {:action/type :protocol-act
                                         :action/content "Consent"
                                         :action/protocol {:verb :consent}
                                         :action/raw-output "CONSENT"}
                                        raw-act))
                                [st' _effects] (env/step st actor act)]
                            (recur st' (inc idx)))))
                      out (env/outcome final-st)]
                  (and (env/phase-1-done? final-st)
                       (<= (:round final-st) (inc max-rounds))
                       (contains? #{:fixed-point-1 :unresolved} (:status out))
                       (if (= :fixed-point-1 (:status out))
                         (and (map? (:sim-consent-receipt out))
                              (true? (get-in out [:sim-consent-receipt :simulation?]))
                              (true? (get-in out [:sim-consent-receipt :advisory-only]))
                              (empty? (:standing-objections out)))
                         (seq (:standing-objections out)))))))

;; ---------------------------------------------------------------------------
;; 2. Generative Property: Hard Simulation vs. Real Consent Isolation
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: Hard isolation between :sim-consent-receipt and real consent."}
  prop-sim-consent-isolation
  30
  (prop/for-all [seed (gen/choose 1 100000)
                 actor gen-seat-id]
                (let [spec (env/run-1-scenario-spec seed)
                      st0 (env/init-env spec seed)
                      ;; Attempting to force Phase 2 without a valid :sim-consent-receipt must fail closed
                      [st-bad effects-bad] (env/transition-to-implement st0 nil)
                      ;; Attempting to inject a forged :simulation? false receipt must fail closed
                      forged {:simulation? false :advisory-only false :verdicts {:axiom :consent}}
                      [st-forged effects-forged] (env/transition-to-implement st0 forged)
                      ;; Normal step can never emit a real :bip-consent effect
                      [_ effects-step] (env/step st0 actor {:action/type :protocol-act
                                                            :action/protocol {:verb :consent}
                                                            :action/raw-output "CONSENT"})]
                  (and (= :review (:phase st-bad))
                       (some #(= :diagnose (first %)) effects-bad)
                       (= :review (:phase st-forged))
                       (some #(= :diagnose (first %)) effects-forged)
                       (not-any? #(= :real-bip-consent (first %)) effects-step)))))

;; ---------------------------------------------------------------------------
;; 3. Generative Property: Read-Only Capability Envelope in :review Mode
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: Read-only capability envelope in :review mode rejects all write tools."}
  prop-readonly-envelope-enforcement
  30
  (prop/for-all [seed (gen/choose 1 100000)
                 write-tool (gen/elements [:write-file :edit-file :delete-file :exec-write])]
                (let [spec (env/run-1-scenario-spec seed)
                      st0 (env/init-env spec seed)
                      actor (env/next-actor st0)
                      obs (env/observe st0 actor)
                      [st1 effects1] (env/step st0 actor {:action/type :tool-call
                                                          :action/tool {:name write-tool :args {:path "src/x.clj"}}
                                                          :action/raw-output (str "TOOL:" (name write-tool))})
                      last-turn (last (:turns st1))]
                  (and (not-any? #{:tool-call/write} (:obs/available-actions obs))
                       (= :invalid-action (get-in last-turn [:action :action/type]))
                       (neg? (:step-reward last-turn))
                       (pos? (get-in st1 [:diagnostics :invalid-actions] 0))
                       (some #(= :diagnose (first %)) effects1)))))

;; ---------------------------------------------------------------------------
;; 4. Generative Property: Trace v2 Merkle Verification & JSONL Export Round-Trip
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: Trace v2 Merkle verification, tamper rejection, and JSONL export roundtrip."}
  prop-trace-v2-merkle-and-jsonl-roundtrip
  25
  (prop/for-all [seed (gen/choose 1 100000)
                 n-turns (gen/choose 1 6)]
                (let [spec (env/run-1-scenario-spec seed)
                      st0 (env/init-env spec seed)
                      stN (reduce (fn [st _]
                                    (let [actor (env/next-actor st)
                                          [st' _] (env/step st actor {:action/type :message
                                                                      :action/content "Review turn"
                                                                      :action/raw-output "Review turn"})]
                                      st'))
                                  st0
                                  (range n-turns))
                      rewards (scorer/score-episode stN)
                      ep-trace (trace/build-episode-trace-v2 {:env-state stN :rewards rewards :seed seed})
                      ver-ok (trace/verify-episode-trace-v2 ep-trace)
                      ;; Tampering a turn's raw-output must fail verification
                      tampered (assoc-in ep-trace [:turns 0 :turn/action :action/raw-output] "TAMPERED")
                      ver-bad (trace/verify-episode-trace-v2 tampered)
                      jsonl-str (trace/export-trace-v2->jsonl ep-trace)
                      parsed-lines (mapv #(json/read-str % :key-fn keyword)
                                         (remove str/blank? (str/split-lines jsonl-str)))]
                  (and (:ok? ver-ok)
                       (not (:ok? ver-bad))
                       (= n-turns (count parsed-lines))
                       (every? #(and (= (:merkle-root ep-trace) (:merkle_root %))
                                     (true? (:simulation %))
                                     (string? (:twin_config_cid %))
                                     (number? (:terminal_team_reward %)))
                               parsed-lines)))))

;; ---------------------------------------------------------------------------
;; 5. Generative Property: Universe Tree Determinism & Acyclic Lineage
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: Universe tree shuffle determinism and acyclic rooted lineage."}
  prop-universe-tree-determinism-and-lineage
  25
  (prop/for-all [seed (gen/choose 1 100000)
                 depth (gen/choose 1 3)
                 branching (gen/choose 1 3)]
                (let [spec (-> (env/run-1-scenario-spec seed)
                               (assoc-in [:scenario/ensemble :ensemble/depth] depth)
                               (assoc-in [:scenario/ensemble :ensemble/branching-factor] branching))
                      tree-a (env/plan-universe-tree spec)
                      tree-b (env/plan-universe-tree spec)
                      nodes (:ensemble/nodes tree-a)
                      id-set (set (map :universe/id nodes))
                      leaves (filter #(empty? (:universe/children %)) nodes)
                      max-leaves (long (Math/pow branching depth))]
                  (and (= tree-a tree-b)
                       (pos? (count nodes))
                       (<= (count leaves) max-leaves)
                       (every? (fn [n]
                                 (or (nil? (:universe/parent-id n))
                                     (and (contains? id-set (:universe/parent-id n))
                                          (not= (:universe/id n) (:universe/parent-id n)))))
                               nodes)))))

;; ---------------------------------------------------------------------------
;; 6. End-to-End Acceptance Test: Run-1 Closed Loop In-Memory (5 Seats)
;; ---------------------------------------------------------------------------

(deftest test-run-1-in-memory-closed-loop-acceptance
  (testing "Run-1 simple proposal runs through Phase 1 (FP1) -> Phase 2 (FP2) -> SCI scoring -> v2 Merkle trace & JSONL"
    (let [result (env/run-in-memory-closed-loop! (env/run-1-scenario-spec 20261120))
          {:keys [env-state rewards trace-v2 verification jsonl]} result]
      (is (= :fixed-point-2 (get-in (env/outcome env-state) [:status])))
      (is (true? (get-in env-state [:sim-consent-receipt :simulation?])))
      (is (true? (get-in env-state [:sim-consent-receipt :advisory-only])))
      (is (= 5 (count (get-in env-state [:sim-consent-receipt :verdicts]))))
      (is (string? (:patch-cid (env/outcome env-state))))
      (is (= 64 (count (:patch-cid (env/outcome env-state)))))
      (is (true? (:ok? verification)))
      (is (= 2 (:trace/version trace-v2)))
      (is (pos? (:team-reward rewards)))
      (is (pos? (count (remove str/blank? (str/split-lines jsonl))))))))
