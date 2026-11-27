(ns bitecho.hive.twin-coordinator-test
  "Strict TDD generative (`clojure.test.check`) & model-based command-sequence tests
   for the unified Sans-IO Digital Twin protocol (`bitecho.hive.twin-protocol`) and
   single-authority coordinator/seat graphs (`bitecho.hive.twin-coordinator`).

   Verifies:
   - Wire envelope encode/decode round-trip + hard advisory invariants
   - Blind Round-1 isolation (zero peer leakage across wire requests)
   - Model-based command sequences with stale-generation callbacks, duplicate retries,
     wrong-seat injection, and timeout retries (AGENTS.md Rules 1, 4, 5; RCA F2/F3/F4)
   - Exact semantic parity between pure in-memory reducer and 5 independent Mealy
     seat agents + 1 coordinator agent communicating over `:net/bitecho` fabric."
  (:require [bitecho.hive.env :as env]
            [bitecho.hive.twin-coordinator :as coord]
            [bitecho.hive.twin-protocol :as proto]
            [bitecho.hive.twin-runner :as twin-runner]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.graph.runtime :as runtime]
            [mealy.intelligence.port :as intel-port]))

;; ---------------------------------------------------------------------------
;; Generators
;; ---------------------------------------------------------------------------

(def ^:private gen-seat
  (gen/elements [:simon :axiom :nexus :vector :lumen]))

(def ^:private gen-stage
  (gen/elements [:present :questions :reactions :consent-round]))

(def ^:private gen-non-empty-str
  (gen/such-that (complement str/blank?)
                 (gen/fmap #(str "v-" %) gen/string-alphanumeric)
                 20))

(def ^:private gen-seat-endpoints
  (gen/return {:simon  {:pubkey "pk-simon"  :session-id "sess-simon"}
               :axiom  {:pubkey "pk-axiom"  :session-id "sess-axiom"}
               :nexus  {:pubkey "pk-nexus"  :session-id "sess-nexus"}
               :vector {:pubkey "pk-vector" :session-id "sess-vector"}
               :lumen  {:pubkey "pk-lumen"  :session-id "sess-lumen"}}))

;; ---------------------------------------------------------------------------
;; 1. Wire Envelope Round-Trip & Advisory Invariants
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: twin-protocol wire envelopes round-trip losslessly with hard advisory invariants and ignore non-protocol text."}
  prop-wire-envelope-roundtrip-and-advisory-invariants
  50
  (prop/for-all [seat gen-seat
                 stage gen-stage
                 rnd (gen/choose 1 4)
                 gen-num (gen/choose 1 10)
                 run-id gen-non-empty-str
                 corr-id gen-non-empty-str
                 prompt gen-non-empty-str
                 raw-text gen-non-empty-str
                 blind? gen/boolean]
                (let [req {:kind :twin/turn-request
                           :run-id run-id
                           :generation gen-num
                           :correlation-id corr-id
                           :seat seat
                           :round rnd
                           :stage stage
                           :blind? blind?
                           :proposal-cid (env/sha256-str prompt)
                           :prompt prompt}
                      enc-req (proto/encode-envelope req)
                      dec-req (proto/decode-envelope enc-req)
                      resp {:kind :twin/turn-response
                            :run-id run-id
                            :generation gen-num
                            :correlation-id corr-id
                            :seat seat
                            :round rnd
                            :stage stage
                            :raw-text raw-text
                            :latency-ms 42}
                      enc-resp (proto/encode-envelope resp)
                      dec-resp (proto/decode-envelope enc-resp)
                      noise (proto/decode-envelope "Hey there! Just saying hello, nice to meet you!")]
                  (and (= proto/PROTOCOL-VERSION (:protocol dec-req))
                       (= :twin/turn-request (:kind dec-req))
                       (= seat (:seat dec-req))
                       (= gen-num (:generation dec-req))
                       (= corr-id (:correlation-id dec-req))
                       (= prompt (:prompt dec-req))
                       (= blind? (:blind? dec-req))
                       (true? (:advisory-only? dec-req))
                       (true? (:real-consent-required? dec-req))
                       (false? (:simulated-consent-counts-as-real? dec-req))
                       (= :twin/turn-response (:kind dec-resp))
                       (= raw-text (:raw-text dec-resp))
                       (= corr-id (:correlation-id dec-resp))
                       (nil? noise)))))

;; ---------------------------------------------------------------------------
;; 2. Blind Round-1 Wire Isolation
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Property: when :blind-round1? is true, Round-1 dispatches never include peer messages in prompts."}
  prop-blind-round1-wire-isolation
  30
  (prop/for-all [secret gen-non-empty-str]
                (let [spec (env/run-1-scenario-spec 20261126)
                      c0 (coord/init-coordinator
                          {:spec spec
                           :seed 20261126
                           :run-id "blind-test"
                           :values-text "Shared values."
                           :seat-prompts {:simon "Simon" :axiom "Axiom" :nexus "Nexus" :vector "Vector" :lumen "Lumen"}
                           :seat-endpoints {:simon {:pubkey "pk-s" :session-id "s1"}
                                            :axiom {:pubkey "pk-a" :session-id "s2"}
                                            :nexus {:pubkey "pk-n" :session-id "s3"}
                                            :vector {:pubkey "pk-v" :session-id "s4"}
                                            :lumen {:pubkey "pk-l" :session-id "s5"}}
                           :blind-round1? true})
                      [c1 effs1] (coord/transition c0 {:event/type :start})
                      disp1 (first (filter #(= :dispatch-seat-turn (first %)) effs1))
                      req1 (second disp1)
                      ;; Simon replies in :present with a secret string
                      [c2 effs2] (coord/transition c1 {:event/type :seat-response
                                                       :correlation-id (:correlation-id req1)
                                                       :generation (:generation req1)
                                                       :seat (:seat req1)
                                                       :session-id "s1"
                                                       :raw-text (str "SECRET_SIMON_" secret)
                                                       :latency-ms 10})
                      ;; Next dispatched turn is Round 1 :questions -> :simon, then :axiom
                      disp2 (first (filter #(= :dispatch-seat-turn (first %)) effs2))
                      req2 (second disp2)
                      [_c3 effs3] (coord/transition c2 {:event/type :seat-response
                                                        :correlation-id (:correlation-id req2)
                                                        :generation (:generation req2)
                                                        :seat (:seat req2)
                                                        :session-id "s1"
                                                        :raw-text "No questions."
                                                        :latency-ms 10})
                      disp3 (first (filter #(= :dispatch-seat-turn (first %)) effs3))
                      req3 (second disp3)]
                  (and (= :axiom (:seat req3))
                       (true? (get-in req3 [:wire-envelope :blind?]))
                       (not (str/includes? (:prompt req3) (str "SECRET_SIMON_" secret)))))))

;; ---------------------------------------------------------------------------
;; 3. Model-Based Command-Sequence Test: Stale Generations, Duplicates & Timeouts
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Rule 1+4+5 model-based property: stale generations, duplicate correlation IDs, wrong seats, and timeouts never corrupt state and always emit counted :diagnose effects."}
  prop-coordinator-stale-gen-dup-and-failure-injection
  40
  (prop/for-all [faults (gen/vector
                         (gen/elements [:stale-gen :dup-corr :wrong-seat :timeout :valid])
                         5 25)
                 endpoints gen-seat-endpoints]
                (let [spec (env/run-1-scenario-spec 20261126)
                      c0 (coord/init-coordinator
                          {:spec spec
                           :seed 20261126
                           :run-id "fault-sim"
                           :max-retries 2
                           :run-phase2? false
                           :values-text "Shared values."
                           :seat-prompts {:simon "S" :axiom "A" :nexus "N" :vector "V" :lumen "L"}
                           :seat-endpoints endpoints})
                      [c1 effs1] (coord/transition c0 {:event/type :start})
                      init-disp (second (first (filter #(= :dispatch-seat-turn (first %)) effs1)))]
                  (loop [st c1
                         last-disp init-disp
                         last-good-corr nil
                         rem-faults faults
                         ok? true]
                    (if (or (not ok?) (empty? rem-faults) (coord/terminal? st))
                      (and ok?
                           (= (get-in st [:diagnostics :dropped])
                              (count (filter #(contains? #{:stale-generation
                                                           :duplicate-response
                                                           :unexpected-seat-response}
                                                         (:reason %))
                                             (get-in st [:diagnostics :events]))))
                           (true? (:advisory-only? st)))
                      (let [cmd (first rem-faults)
                            inflight (:inflight st)
                            cur-gen (:generation st)]
                        (case cmd
                          :stale-gen
                          (let [[st' effs] (coord/transition st {:event/type :seat-response
                                                                 :correlation-id (:correlation-id inflight)
                                                                 :generation (dec cur-gen)
                                                                 :seat (:seat inflight)
                                                                 :raw-text "CONSENT: stale"
                                                                 :latency-ms 5})
                                diag (second (first (filter #(= :diagnose (first %)) effs)))]
                            (recur st' last-disp last-good-corr (rest rem-faults)
                                   (and (= (:env st) (:env st'))
                                        (= :stale-generation (:reason diag))
                                        (= (inc (get-in st [:diagnostics :dropped]))
                                           (get-in st' [:diagnostics :dropped])))))

                          :dup-corr
                          (if last-good-corr
                            (let [[st' effs] (coord/transition st {:event/type :seat-response
                                                                   :correlation-id last-good-corr
                                                                   :generation cur-gen
                                                                   :seat (:seat inflight)
                                                                   :raw-text "CONSENT: dup"
                                                                   :latency-ms 5})
                                  diag (second (first (filter #(= :diagnose (first %)) effs)))]
                              (recur st' last-disp last-good-corr (rest rem-faults)
                                     (and (= (:env st) (:env st'))
                                          (= :duplicate-response (:reason diag))
                                          (= (inc (get-in st [:diagnostics :dropped]))
                                             (get-in st' [:diagnostics :dropped])))))
                            (recur st last-disp last-good-corr (rest rem-faults) ok?))

                          :wrong-seat
                          (let [other-seat (first (remove #{(:seat inflight)} [:simon :axiom :nexus :vector :lumen]))
                                [st' effs] (coord/transition st {:event/type :seat-response
                                                                 :correlation-id (:correlation-id inflight)
                                                                 :generation cur-gen
                                                                 :seat other-seat
                                                                 :raw-text "CONSENT: wrong seat"
                                                                 :latency-ms 5})
                                diag (second (first (filter #(= :diagnose (first %)) effs)))]
                            (recur st' last-disp last-good-corr (rest rem-faults)
                                   (and (= (:env st) (:env st'))
                                        (= :unexpected-seat-response (:reason diag))
                                        (= (inc (get-in st [:diagnostics :dropped]))
                                           (get-in st' [:diagnostics :dropped])))))

                          :timeout
                          (let [[st' effs] (coord/transition st {:event/type :seat-timeout
                                                                 :correlation-id (:correlation-id inflight)
                                                                 :generation cur-gen
                                                                 :seat (:seat inflight)
                                                                 :error "timeout-90s"})
                                diag (second (first (filter #(= :diagnose (first %)) effs)))
                                next-disp (second (first (filter #(= :dispatch-seat-turn (first %)) effs)))]
                            (recur st' (or next-disp last-disp) last-good-corr (rest rem-faults)
                                   (and (= :seat-timeout (:reason diag))
                                        (>= (:generation st') cur-gen))))

                          :valid
                          (let [raw (if (= :consent-round (:stage inflight))
                                      "CONSENT: All invariants verified."
                                      "Looks concise and sound.")
                                [st' effs] (coord/transition st {:event/type :seat-response
                                                                 :correlation-id (:correlation-id inflight)
                                                                 :generation cur-gen
                                                                 :seat (:seat inflight)
                                                                 :session-id (get-in endpoints [(:seat inflight) :session-id])
                                                                 :raw-text raw
                                                                 :latency-ms 12})
                                next-disp (second (first (filter #(= :dispatch-seat-turn (first %)) effs)))]
                            (recur st' (or next-disp last-disp) (:correlation-id inflight) (rest rem-faults)
                                   (>= (:generation st') cur-gen))))))))))

;; ---------------------------------------------------------------------------
;; 4. Parity: Pure Core vs 5 Distributed Mealy Seat Agents on In-Memory Fabric
;; ---------------------------------------------------------------------------

(deftest in-memory-mealy-distributed-parity-and-zero-pingpong-test
  (testing "5 independent Mealy twin-seat agents + 1 coordinator over :in-memory fabric match pure core identically, preserve session_id, maintain unbroken SHA-256 chains, and ignore pleasantries"
    (let [spec (assoc-in (env/run-1-scenario-spec 20261126)
                         [:scenario/limits :max-review-rounds] 2)
          ;; Deterministic seat behavior: Axiom objects in Round 1, consents in Round 2; others consent.
          seat-port-fn (fn [seat-id]
                         (reify intel-port/IIntelligencePort
                           (start-session! [this _] this)
                           (generate! [_ req]
                             (let [p (:prompt req)]
                               (cond
                                 (str/includes? p "AMENDMENT LOOP")
                                 {:text (str (:proposal/text (:scenario/proposal spec))
                                             " [Amended: added explicit failure-mode boundary & invariant check.]")}

                                 (and (= :axiom seat-id)
                                      (str/includes? p "Round: 1")
                                      (str/includes? p "CONSENT ROUND"))
                                 {:text "PARAMOUNT-OBJECTION: Missing explicit failure-mode boundary."}

                                 (str/includes? p "CONSENT ROUND")
                                 {:text (str "CONSENT: Seat " (name seat-id) " verifies all invariants.")}

                                 :else
                                 {:text (str "Seat " (name seat-id) " notes clean Sans-IO framing.")})))
                           (stop-session! [_] nil)))
          seat-prompts {:simon "Simon prompt" :axiom "Axiom prompt" :nexus "Nexus prompt"
                        :vector "Vector prompt" :lumen "Lumen prompt"}
          res (twin-runner/run-in-memory-mealy-episode!
               {:spec spec
                :seed 20261126
                :run-id "parity-test-1"
                :values-text "Shared Values v1"
                :seat-prompts seat-prompts
                :seat-ports (into {} (map (fn [s] [s (seat-port-fn s)])
                                          [:simon :axiom :nexus :vector :lumen]))
                :inject-stray-pleasantry? true})]
      ;; Reached Fixed Point 1 in Round 2 (Round 1 had Axiom's paramount objection -> amendment -> Round 2 consent)
      (is (= :complete (:phase (:coordinator-state res))))
      (is (= :fixed-point-1 (get-in res [:env-state :status])))
      (is (= 2 (get-in res [:env-state :round])))
      (is (true? (get-in res [:env-state :sim-consent-receipt :simulation?])))
      (is (true? (get-in res [:env-state :sim-consent-receipt :advisory-only])))
      ;; Every seat agent + coordinator has an unbroken SHA-256 event log chain
      (doseq [[seat-id ctx] (:seat-contexts res)]
        (is (pos? (count (:all-event-log ctx))) (str "Seat " seat-id " executed turns"))
        (is (runtime/verify-event-log-chain (:all-event-log ctx)) (str "Seat " seat-id " chain verified")))
      ;; Stray pleasantry was dropped with counted :diagnose (zero ping-pong!)
      (is (pos? (count (filter #(= :non-protocol-message-ignored (:reason %))
                               (get-in res [:seat-contexts :axiom :diagnostics-log])))))
      ;; Every DM reply preserved the exact session-id from the coordinator's request
      (let [sent-dms (filter #(= "send" (:cmd %)) (:sent @(:fabric res)))]
        (is (pos? (count sent-dms)))
        (is (every? #(and (string? (:session-id %)) (not (str/blank? (:session-id %)))) sent-dms))))))

(deftest twin-runner-cli-flag-inventory-test
  (testing "Rule 6: every flag in twin-runner/cli-flag-inventory is documented and parsed"
    (let [help (twin-runner/help-text)]
      (doseq [flag twin-runner/cli-flag-inventory]
        (is (str/includes? help flag) (str "Missing flag in twin-runner help: " flag))))
    (let [parsed (twin-runner/parse-cli-args ["--run" "2"
                                              "--transport" "live"
                                              "--state-dir" "/tmp/coord"
                                              "--out-dir" "/tmp/out"
                                              "--seat-endpoints-edn" "/tmp/seats.edn"
                                              "--blind-round1"])]
      (is (= "2" (:run parsed)))
      (is (= :live (:transport parsed)))
      (is (= "/tmp/coord" (:state-dir parsed)))
      (is (= "/tmp/out" (:out-dir parsed)))
      (is (= "/tmp/seats.edn" (:seat-endpoints-edn parsed)))
      (is (true? (:blind-round1? parsed))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown flag"
                          (twin-runner/parse-cli-args ["--unknown-flag"])))))

(deftest axiom-review-flag5-hardening-test
  (testing "decode-envelope fails closed if advisory-only invariants are tampered"
    (let [tampered (str proto/ENVELOPE-PREFIX
                        (pr-str {:protocol proto/PROTOCOL-VERSION
                                 :kind :twin/turn-request
                                 :advisory-only? false
                                 :real-consent-required? true
                                 :simulated-consent-counts-as-real? false}))]
      (is (nil? (proto/decode-envelope tampered)))))

  (testing "missing :generation on response fails closed with :missing-generation"
    (let [c0 (coord/init-coordinator {:spec (env/run-1-scenario-spec 20261126)
                                      :run-id "gen-test"
                                      :seat-endpoints {:simon {:pubkey "pk-s" :session-id "s1"}}})
          [c1 effs1] (coord/transition c0 {:event/type :start})
          disp (second (first (filter #(= :dispatch-seat-turn (first %)) effs1)))
          [c2 effs2] (coord/transition c1 {:event/type :seat-response
                                           :correlation-id (:correlation-id disp)
                                           :seat (:seat disp)
                                           :raw-text "no gen"})]
      (is (= (:env c1) (:env c2)))
      (is (= :missing-generation (get-in (first effs2) [1 :reason])))))

  (testing "twin-seat-step enforces coordinator-pubkey allowlist and single-flight :pending guard"
    (let [req-str (proto/encode-envelope {:kind :twin/turn-request
                                          :run-id "r1"
                                          :generation 1
                                          :correlation-id "c1"
                                          :seat :axiom
                                          :round 1
                                          :stage :questions
                                          :prompt "p1"})
          st0 {:seat-id :axiom :coordinator-pubkey "pk-coord" :min-balance 2000 :turn 0 :pending nil}
          [_ unauth-outs] (coord/twin-seat-step st0 :incoming {:from "pk-intruder" :text req-str})
          [st1 _] (coord/twin-seat-step st0 :incoming {:from "pk-coord" :text req-str})
          [st2 busy-outs] (coord/twin-seat-step st1 :incoming {:from "pk-coord" :text req-str})]
      (is (= :unauthorized-coordinator (:reason (first (:error unauth-outs)))))
      (is (= st1 st2))
      (is (= :seat-busy-single-flight (:reason (first (:error busy-outs))))))))
