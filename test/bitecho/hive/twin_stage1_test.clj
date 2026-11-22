(ns bitecho.hive.twin-stage1-test
  "Strict TDD generative (`clojure.test.check`) tests for Stage 1 Advisory
   Digital Twin Pre-Shaping (`bitecho.hive.twin-stage1`):
   - Independent blind Round-1 per-seat isolation (zero peer leakage)
   - Mandatory schema-checked `:falsifier` slot (fail-closed + counted `:diagnose`)
   - Advisory-only hard invariants and `:unresolved` exit on round-limit
   - Deterministic RFC 6962 Merkle Trace v2 root & all-frontier roster (zero `gpt-oss-120b`)."
  (:require [bitecho.hive.twin-stage1 :as stage1]
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

(def ^:private gen-non-empty-str
  (gen/such-that (complement str/blank?)
                 (gen/fmap #(str "val-" %) gen/string-alphanumeric)
                 20))

(def ^:private gen-blank-or-nil
  (gen/elements [nil "" "   " "\t\n"]))

(def ^:private gen-verdict
  (gen/elements [:objection :amendment :no-objection]))

(def ^:private gen-proposal
  (gen/let [bip-num (gen/choose 19 999)
            title gen-non-empty-str
            body gen-non-empty-str]
    {:bip-id (str "BIP-" bip-num)
     :title title
     :body (str "Proposal body for BIP-" bip-num ": " body)}))

(def ^:private gen-valid-falsifier
  (gen/let [fm gen-non-empty-str
            pt gen-non-empty-str
            os gen-non-empty-str]
    {:failure-mode fm
     :prod-trigger pt
     :observable-signal os}))

(def ^:private gen-invalid-falsifier
  (gen/one-of
   [(gen/return nil)
    (gen/return {})
    (gen/return "not-a-map")
    (gen/let [bad gen-blank-or-nil
              pt gen-non-empty-str
              os gen-non-empty-str]
      {:failure-mode bad :prod-trigger pt :observable-signal os})
    (gen/let [fm gen-non-empty-str
              bad gen-blank-or-nil
              os gen-non-empty-str]
      {:failure-mode fm :prod-trigger bad :observable-signal os})
    (gen/let [fm gen-non-empty-str
              pt gen-non-empty-str
              bad gen-blank-or-nil]
      {:failure-mode fm :prod-trigger pt :observable-signal bad})]))

(def ^:private gen-prior-objections-by-seat
  (gen/let [s-objs (gen/vector gen-non-empty-str 1 2)
            a-objs (gen/vector gen-non-empty-str 1 2)
            n-objs (gen/vector gen-non-empty-str 1 2)
            v-objs (gen/vector gen-non-empty-str 1 2)
            l-objs (gen/vector gen-non-empty-str 1 2)]
    {:simon (mapv #(str "SECRET_SIMON_" %) s-objs)
     :axiom (mapv #(str "SECRET_AXIOM_" %) a-objs)
     :nexus (mapv #(str "SECRET_NEXUS_" %) n-objs)
     :vector (mapv #(str "SECRET_VECTOR_" %) v-objs)
     :lumen (mapv #(str "SECRET_LUMEN_" %) l-objs)}))

;; ---------------------------------------------------------------------------
;; 1. Blind Round-1 Isolation: Zero Peer Leakage
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: Round-1 envelopes are strictly isolated per seat with zero peer leakage."}
  prop-blind-round1-isolation-zero-peer-leakage
  40
  (prop/for-all [proposal gen-proposal
                 prior-by-seat gen-prior-objections-by-seat
                 injected-peer-secret gen-non-empty-str]
                (let [roster (stage1/canonical-stage1-roster)
                      envelopes (stage1/build-all-round1-envelopes
                                 {:roster roster
                                  :proposal proposal
                                  :prior-objections-by-seat prior-by-seat
                                  ;; Attempt to smuggle peer outputs into Round 1
                                  :peer-outputs [{:seat :axiom
                                                  :critique (str "LEAKED_PEER_" injected-peer-secret)}]})]
                  (and (= 5 (count envelopes))
                       (every?
                        (fn [env]
                          (let [seat (:seat env)
                                prompt-rec (stage1/build-blind-round1-prompt env)
                                prompt-text (:prompt-text prompt-rec)
                                other-seats (disj #{:simon :axiom :nexus :vector :lumen} seat)
                                own-objs (get prior-by-seat seat)
                                other-objs (mapcat #(get prior-by-seat %) other-seats)]
                            (and (= 1 (:round env))
                                 (true? (:blind? env))
                                 (= [] (:peer-outputs env))
                                 (= stage1/VALUES-CID (:episode/values-cid env))
                                 (true? (:advisory-only? env))
                                 (true? (:real-consent-required? env))
                                 (false? (:simulated-consent-counts-as-real? env))
                                 (= own-objs (:member-prior-objections env))
                                 (not (str/includes? prompt-text (str "LEAKED_PEER_" injected-peer-secret)))
                                 (every? #(not (str/includes? prompt-text %)) other-objs))))
                        envelopes)))))

;; ---------------------------------------------------------------------------
;; 2. Mandatory Schema-Checked :falsifier Slot (Fail-Closed + Counted :diagnose)
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: Missing or blank :falsifier fails closed with counted :diagnose and :incomplete status."}
  prop-mandatory-falsifier-fail-closed-with-counted-diagnose
  40
  (prop/for-all [proposal gen-proposal
                 bad-seat gen-seat-id
                 bad-falsifier gen-invalid-falsifier
                 valid-falsifier gen-valid-falsifier
                 verdict gen-verdict]
                (let [roster (stage1/canonical-stage1-roster)
                      runner (fn [env]
                               (let [seat (:seat env)]
                                 {:seat seat
                                  :twin-identity (:twin-identity env)
                                  :verdict verdict
                                  :critique (str "Critique from " (name seat))
                                  :falsifier (if (= seat bad-seat)
                                               bad-falsifier
                                               valid-falsifier)}))
                      res (stage1/run-stage1-pre-shaping
                           {:roster roster
                            :proposal proposal
                            :max-rounds 3
                            :seat-runner runner})
                      diags (get-in res [:diagnostics :events])
                      missing-falsifier-diags (filter #(= :missing-seat-falsifier (:reason %)) diags)]
                  (and (= :incomplete (:status res))
                       (pos? (get-in res [:diagnostics :count] 0))
                       (pos? (get-in res [:diagnostics :by-reason :missing-seat-falsifier] 0))
                       (some #(and (= :missing-seat-falsifier (:reason %))
                                   (= bad-seat (:seat %)))
                             missing-falsifier-diags)
                       (some #(and (= :diagnose (first %))
                                   (= :missing-seat-falsifier (:reason (second %)))
                                   (= bad-seat (:seat (second %))))
                             (:effects res))
                       (true? (:advisory-only? res))
                       (true? (:real-consent-required? res))
                       (false? (:simulated-consent-counts-as-real? res))))))

;; ---------------------------------------------------------------------------
;; 3. Stage 1 Advisory-Only Hard Invariant & :unresolved Exit on Round-Limit
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: Stage 1 is always advisory-only and exits :unresolved on round-limit, never silent consent."}
  prop-stage1-advisory-only-and-unresolved-on-round-limit
  35
  (prop/for-all [proposal gen-proposal
                 max-rounds (gen/choose 1 4)
                 converge-on-round (gen/one-of [(gen/return nil) (gen/choose 1 4)])
                 blocking-seat gen-seat-id
                 blocking-verdict (gen/elements [:objection :amendment])
                 falsifier gen-valid-falsifier]
                (let [seen-r1-peer-counts (atom [])
                      runner (fn [env]
                               (when (= 1 (:round env))
                                 (swap! seen-r1-peer-counts conj (count (:peer-outputs env))))
                               (let [seat (:seat env)
                                     rnd (:round env)
                                     converged? (and converge-on-round (>= rnd converge-on-round))
                                     verdict (if (or converged? (not= seat blocking-seat))
                                               :no-objection
                                               blocking-verdict)]
                                 {:seat seat
                                  :twin-identity (:twin-identity env)
                                  :verdict verdict
                                  :critique (str "Round " rnd " critique by " (name seat))
                                  :falsifier falsifier}))
                      res (stage1/run-stage1-pre-shaping
                           {:proposal proposal
                            :max-rounds max-rounds
                            :seat-runner runner})
                      expect-fp? (and converge-on-round (<= converge-on-round max-rounds))]
                  (and (true? (:advisory-only? res))
                       (true? (:real-consent-required? res))
                       (false? (:simulated-consent-counts-as-real? res))
                       (every? zero? @seen-r1-peer-counts)
                       (if expect-fp?
                         (and (= :fixed-point (:status res))
                              (= converge-on-round (:rounds-executed res)))
                         (and (= :unresolved (:status res))
                              (= max-rounds (:rounds-executed res))
                              (pos? (get-in res [:diagnostics :by-reason :stage1-round-limit-unresolved] 0))
                              (some #(and (= :diagnose (first %))
                                          (= :stage1-round-limit-unresolved (:reason (second %))))
                                    (:effects res))))))))

;; ---------------------------------------------------------------------------
;; 4. Trace v2 Merkle Determinism & All-Frontier Roster (Zero gpt-oss-120b)
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: Stage 1 RFC 6962 Merkle Trace v2 is deterministic and roster has zero gpt-oss-120b."}
  prop-stage1-trace-merkle-determinism-and-all-frontier-roster
  30
  (prop/for-all [proposal gen-proposal
                 falsifier gen-valid-falsifier]
                (let [roster (stage1/canonical-stage1-roster)
                      runner (fn [env]
                               {:seat (:seat env)
                                :twin-identity (:twin-identity env)
                                :verdict :no-objection
                                :critique (str "Independent check by " (name (:seat env)))
                                :falsifier falsifier})
                      res-a (stage1/run-stage1-pre-shaping {:roster roster
                                                            :proposal proposal
                                                            :max-rounds 2
                                                            :seat-runner runner})
                      res-b (stage1/run-stage1-pre-shaping {:roster roster
                                                            :proposal proposal
                                                            :max-rounds 2
                                                            :seat-runner runner})
                      ver-ok (stage1/verify-stage1-trace res-a)
                      tampered (assoc-in res-a [:trace :merkle-root]
                                         (apply str (repeat 64 "0")))
                      ver-bad (stage1/verify-stage1-trace tampered)
                      models (map :model-selector roster)]
                  (and (= 5 (count roster))
                       (= #{:simon :axiom :nexus :vector :lumen} (set (map :seat-id roster)))
                       (not-any? #(str/includes? (str/lower-case (str %)) "gpt-oss") models)
                       (= (get-in res-a [:trace :merkle-root])
                          (get-in res-b [:trace :merkle-root]))
                       (= 64 (count (get-in res-a [:trace :merkle-root])))
                       (= 2 (get-in res-a [:trace :trace/version]))
                       (true? (:ok? ver-ok))
                       (false? (:ok? ver-bad))))))

;; ---------------------------------------------------------------------------
;; 5. Unit Tests: Direct Live 5-Subagent Assembly & Retired Model Rejection
;; ---------------------------------------------------------------------------

(deftest test-retired-model-rejected-fail-closed
  (testing "Injecting gpt-oss-120b into roster fails closed with counted :diagnose"
    (let [bad-roster (assoc-in (stage1/canonical-stage1-roster) [1 :model-selector] "gpt-oss-120b")
          res (stage1/assemble-round1
               {:roster bad-roster
                :proposal {:bip-id "BIP-019" :title "Test" :body "Body"}
                :seat-outputs []})]
      (is (= :incomplete (:status res)))
      (is (pos? (get-in res [:diagnostics :by-reason :retired-model-selector] 0)))
      (is (some #(= :retired-model-selector (:reason (second %))) (:effects res))))))

(deftest test-live-five-subagent-round1-outputs-assembly
  (testing "Pre-collected 5 independent Round-1 outputs feed directly into run-stage1-pre-shaping"
    (let [proposal {:bip-id "BIP-019"
                    :title "Finalization stall recovery"
                    :body "Add term-convergence watchdog and generator case."}
          envelopes (stage1/build-all-round1-envelopes {:proposal proposal})
          r1-outputs (mapv (fn [env]
                             {:seat (:seat env)
                              :twin-identity (:twin-identity env)
                              :verdict :no-objection
                              :critique (str "Seat " (name (:seat env)) " verified invariants.")
                              :falsifier {:failure-mode "Stale leader term blocks quorum"
                                          :prod-trigger "Network partition heals with split terms"
                                          :observable-signal "finalized_epoch flat > 60s on /statusz"}})
                           envelopes)
          res (stage1/run-stage1-pre-shaping
               {:proposal proposal
                :max-rounds 2
                :round1-outputs r1-outputs})]
      (is (= :fixed-point (:status res)))
      (is (= 1 (:rounds-executed res)))
      (is (true? (:ok? (stage1/verify-stage1-trace res))))
      (is (true? (:advisory-only? res)))
      (is (true? (:real-consent-required? res)))
      (is (false? (:simulated-consent-counts-as-real? res))))))
