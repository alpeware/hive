(ns bitecho.hive.twin-coordinator
  "Pure Sans-IO Single-Authority Digital Twin Coordinator & Seat Graphs (`.cljc`).

   Implements:
   - Single generation-tagged coordinator state machine (`init-coordinator`, `transition`)
     wrapping `bitecho.hive.env` (D41 Sociocratic two-fixed-point closed-loop).
   - Rule 4 & Rule 5 compliance: stale generations (`gen < current`), duplicate
     correlation IDs, wrong-seat responses, and timeouts never silently mutate or
     drop — every rejection increments `:dropped` and emits a counted `[:diagnose ...]`.
   - Transport-agnostic Mealy Hiccup graphs (`twin-seat-graph`, `twin-coordinator-graph`)
     so the EXACT same pure core executes in-memory over `bitecho.hive.transport`
     OR across distributed Mealy agents over the live Bitecho devnet."
  (:require [bitecho.hive.env :as env]
            [bitecho.hive.twin-protocol :as proto]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; 1. Single-Authority Coordinator State & Diagnostics Helpers (Rule 4 & 5)
;; ---------------------------------------------------------------------------

(defn init-coordinator
  "Initializes the single-authority generation-tagged coordinator state machine."
  [{:keys [spec seed run-id values-text seat-prompts seat-endpoints
           blind-round1? max-retries run-phase2? presenter-seat]
    :or {seed 20261126
         run-id "twin-run"
         values-text ""
         seat-prompts {}
         seat-endpoints {}
         blind-round1? false
         max-retries 2
         run-phase2? false
         presenter-seat :simon}}]
  (let [env-st (env/init-env spec seed)]
    {:run-id (str run-id)
     :seed (long seed)
     :generation 1
     :phase :idle
     :env env-st
     :values-text (str values-text)
     :seat-prompts seat-prompts
     :seat-endpoints seat-endpoints
     :blind-round1? (boolean blind-round1?)
     :max-retries (long (max 0 max-retries))
     :run-phase2? (boolean run-phase2?)
     :presenter-seat (keyword presenter-seat)
     :inflight nil
     :seen-correlation-ids #{}
     :token-log []
     :advisory-only? true
     :real-consent-required? true
     :simulated-consent-counts-as-real? false
     :diagnostics {:phase :idle
                   :generation 1
                   :last-error nil
                   :dropped 0
                   :by-reason {}
                   :events []}}))

(defn terminal?
  "Returns true when the coordinator has reached `:complete` or `:failed`."
  [st]
  (contains? #{:complete :failed} (:phase st)))

(defn- sync-diag-header
  [st]
  (-> st
      (assoc-in [:diagnostics :phase] (:phase st))
      (assoc-in [:diagnostics :generation] (:generation st))))

(defn- record-diagnose
  [st diag drop?]
  (let [reason (:reason diag)
        st' (-> st
                (cond-> drop? (update-in [:diagnostics :dropped] (fnil inc 0)))
                (assoc-in [:diagnostics :last-error] reason)
                (update-in [:diagnostics :by-reason reason] (fnil inc 0))
                (update-in [:diagnostics :events] conj diag)
                sync-diag-header)]
    [st' [[:diagnose diag]]]))

(defn- advance-auto-passes
  "Auto-passes non-presenter seats during `:present` (matching `twin-runner.clj`),
   returning `[env-st' pass-effects]`."
  [env-st presenter-seat]
  (loop [cur env-st
         effs []]
    (if (or (env/phase-1-done? cur)
            (not= :present (:stage cur))
            (= presenter-seat (env/next-actor cur)))
      [cur effs]
      (let [actor (env/next-actor cur)
            [cur' step-effs] (env/step cur actor {:action/type :pass
                                                  :action/content "Listening to presenter."
                                                  :action/raw-output "PASS"
                                                  :latency-ms 0})]
        (recur cur' (into effs step-effs))))))

(defn- dispatch-next-phase1-turn
  "Advances auto-passes and either dispatches the next seat turn or finishes Phase 1."
  [st]
  (let [[env-after-pass _] (advance-auto-passes (:env st) (:presenter-seat st))
        st-p (assoc st :env env-after-pass)]
    (if (env/phase-1-done? env-after-pass)
      (if (and (:run-phase2? st-p) (= :fixed-point-1 (:status env-after-pass)))
        (let [[env-p2 p2-effs] (env/transition-to-implement env-after-pass (:sim-consent-receipt env-after-pass))
              st-next (-> st-p
                          (assoc :env env-p2 :phase :awaiting-phase2-impl :inflight nil)
                          sync-diag-header)]
          [st-next (into [[:execute-phase2-impl {:run-id (:run-id st-p)
                                                 :proposal-text (:proposal-text env-p2)
                                                 :sim-consent-receipt (:sim-consent-receipt env-p2)}]]
                         p2-effs)])
        (let [st-done (-> st-p
                          (assoc :phase :complete :inflight nil)
                          sync-diag-header)]
          [st-done [[:emit-episode-complete {:run-id (:run-id st-p)
                                             :status (:status env-after-pass)
                                             :round (:round env-after-pass)}]]]))
      (let [actor (env/next-actor env-after-pass)
            rnd (:round env-after-pass)
            stage (:stage env-after-pass)
            obs (env/observe env-after-pass actor)
            blind? (and (:blind-round1? st-p) (= 1 rnd))
            prompt (proto/build-seat-turn-prompt (:values-text st-p)
                                                 (get (:seat-prompts st-p) actor "")
                                                 obs
                                                 blind?)
            gen-num (:generation st-p)
            corr-id (proto/make-correlation-id (:run-id st-p) rnd stage actor gen-num)
            ep (get (:seat-endpoints st-p) actor)
            wire-env {:kind :twin/turn-request
                      :run-id (:run-id st-p)
                      :generation gen-num
                      :correlation-id corr-id
                      :seat actor
                      :round rnd
                      :stage stage
                      :blind? blind?
                      :proposal-cid (:proposal-cid env-after-pass)
                      :prompt prompt}
            inflight {:kind :seat-turn
                      :correlation-id corr-id
                      :generation gen-num
                      :seat actor
                      :round rnd
                      :stage stage
                      :retries 0
                      :to-pubkey (:pubkey ep)
                      :session-id (:session-id ep)
                      :prompt prompt
                      :obs obs
                      :wire-envelope wire-env}
            st-next (-> st-p
                        (assoc :phase :awaiting-seat
                               :inflight inflight)
                        sync-diag-header)]
        [st-next [[:dispatch-seat-turn inflight]]]))))

(defn- dispatch-amendment-turn
  [st prev-round prev-proposal-text]
  (let [env-st (:env st)
        proposer (:presenter-seat st)
        round-turns (filter #(and (= prev-round (:round %))
                                  (contains? #{:reactions :consent-round} (:stage %)))
                            (:turns env-st))
        prompt (proto/build-amendment-prompt (:values-text st)
                                             (get (:seat-prompts st) proposer "")
                                             prev-proposal-text
                                             round-turns)
        gen-num (:generation st)
        corr-id (proto/make-correlation-id (:run-id st) prev-round :amendment-loop proposer gen-num)
        ep (get (:seat-endpoints st) proposer)
        wire-env {:kind :twin/amendment-request
                  :run-id (:run-id st)
                  :generation gen-num
                  :correlation-id corr-id
                  :seat proposer
                  :round prev-round
                  :stage :amendment-loop
                  :blind? false
                  :proposal-cid (:proposal-cid env-st)
                  :prompt prompt}
        inflight {:kind :amendment
                  :correlation-id corr-id
                  :generation gen-num
                  :seat proposer
                  :round prev-round
                  :stage :amendment-loop
                  :retries 0
                  :to-pubkey (:pubkey ep)
                  :session-id (:session-id ep)
                  :prompt prompt
                  :wire-envelope wire-env}
        st-next (-> st
                    (assoc :phase :awaiting-amendment
                           :inflight inflight)
                    sync-diag-header)]
    [st-next [[:dispatch-amendment inflight]]]))

;; ---------------------------------------------------------------------------
;; 2. Pure Coordinator Reducer: `(transition state event) -> [new-state effects]`
;; ---------------------------------------------------------------------------

(defn- validate-inflight-event
  [st expected-phase ev]
  (let [cur-gen (long (:generation st))
        ev-gen-raw (:generation ev)
        corr (:correlation-id ev)
        inflight (:inflight st)]
    (cond
      (not (integer? ev-gen-raw))
      {:ok? false :reason :missing-generation :detail {:current-gen cur-gen :correlation-id corr :seat (:seat ev)}}

      (< (long ev-gen-raw) cur-gen)
      {:ok? false :reason :stale-generation :detail {:event-gen (long ev-gen-raw) :current-gen cur-gen :correlation-id corr}}

      (and corr (contains? (:seen-correlation-ids st) corr))
      {:ok? false :reason :duplicate-response :detail {:correlation-id corr :seat (:seat ev)}}

      (or (not= expected-phase (:phase st))
          (nil? inflight)
          (not= (:seat inflight) (:seat ev))
          (not= (:correlation-id inflight) corr))
      {:ok? false :reason :unexpected-seat-response :detail {:phase (:phase st)
                                                             :expected-seat (:seat inflight)
                                                             :actual-seat (:seat ev)
                                                             :expected-corr (:correlation-id inflight)
                                                             :actual-corr corr}}

      :else
      {:ok? true})))

(defn transition
  "Pure single-authority coordinator reducer: `(transition state event) -> [new-state effects]`."
  [st ev]
  (case (:event/type ev)
    :start
    (if (not= :idle (:phase st))
      (record-diagnose st {:reason :invalid-start-phase :phase (:phase st)} true)
      (dispatch-next-phase1-turn st))

    :seat-response
    (let [check (validate-inflight-event st :awaiting-seat ev)]
      (if-not (:ok? check)
        (record-diagnose st (merge {:reason (:reason check)} (:detail check)) true)
        (let [inflight (:inflight st)
              actor (:seat inflight)
              stage (:stage inflight)
              prev-rnd (:round inflight)
              prev-proposal-text (get-in st [:env :proposal-text])
              raw (str (or (:raw-text ev) ""))
              lat-ms (long (or (:latency-ms ev) 0))
              action (if (= :consent-round stage)
                       (assoc (proto/parse-consent-verdict actor raw) :latency-ms lat-ms)
                       {:action/type :message
                        :action/content raw
                        :action/raw-output raw
                        :latency-ms lat-ms})
              [env' step-effs] (env/step (:env st) actor action)
              amend-eff (first (filter #(= :emit-proposal-amendment (first %)) step-effs))
              st-updated (cond-> (-> st
                                     (assoc :env env' :inflight nil)
                                     (update :seen-correlation-ids conj (:correlation-id inflight)))
                           (:session-id ev)
                           (assoc-in [:seat-endpoints actor :session-id] (:session-id ev))
                           (:token-usage ev)
                           (update :token-log conj (assoc (:token-usage ev) :seat actor :correlation-id (:correlation-id inflight))))]
          (if amend-eff
            (dispatch-amendment-turn st-updated prev-rnd prev-proposal-text)
            (dispatch-next-phase1-turn st-updated)))))

    :amendment-response
    (let [check (validate-inflight-event st :awaiting-amendment ev)]
      (if-not (:ok? check)
        (record-diagnose st (merge {:reason (:reason check)} (:detail check)) true)
        (let [inflight (:inflight st)
              proposer (:seat inflight)
              rewritten (let [t (str (or (:rewritten-text ev) (:raw-text ev) ""))]
                          (if (str/blank? t) (get-in st [:env :proposal-text]) t))
              lat-ms (long (or (:latency-ms ev) 0))
              [env' _] (env/apply-proposal-amendment (:env st) proposer rewritten lat-ms)
              st-updated (cond-> (-> st
                                     (assoc :env env' :inflight nil)
                                     (update :seen-correlation-ids conj (:correlation-id inflight)))
                           (:session-id ev)
                           (assoc-in [:seat-endpoints proposer :session-id] (:session-id ev)))]
          (dispatch-next-phase1-turn st-updated))))

    :seat-timeout
    (let [inflight (:inflight st)
          cur-gen (long (:generation st))
          ev-gen (long (or (:generation ev) cur-gen))]
      (cond
        (< ev-gen cur-gen)
        (record-diagnose st {:reason :stale-generation :event-gen ev-gen :current-gen cur-gen :kind :timeout} true)

        (nil? inflight)
        (record-diagnose st {:reason :unexpected-timeout :phase (:phase st)} true)

        :else
        (let [diag {:reason :seat-timeout
                    :seat (:seat inflight)
                    :correlation-id (:correlation-id inflight)
                    :generation cur-gen
                    :retries (:retries inflight)
                    :error (or (:error ev) "timeout")}
              [st-d diag-effs] (record-diagnose st diag false)
              next-retries (inc (long (:retries inflight)))
              next-gen (inc cur-gen)]
          (if (<= next-retries (:max-retries st-d))
            (let [new-corr (proto/make-correlation-id (:run-id st-d)
                                                      (:round inflight)
                                                      (:stage inflight)
                                                      (:seat inflight)
                                                      next-gen)
                  new-wire (assoc (:wire-envelope inflight)
                                  :generation next-gen
                                  :correlation-id new-corr)
                  new-inflight (assoc inflight
                                      :generation next-gen
                                      :correlation-id new-corr
                                      :retries next-retries
                                      :wire-envelope new-wire)
                  eff-kind (if (= :amendment (:kind inflight))
                             :dispatch-amendment
                             :dispatch-seat-turn)
                  st-next (-> st-d
                              (assoc :generation next-gen :inflight new-inflight)
                              sync-diag-header)]
              [st-next (conj diag-effs [eff-kind new-inflight])])
            ;; Retries exhausted -> fail loudly with fallback or terminal failure
            (let [st-fail (-> st-d
                              (assoc :generation next-gen :phase :failed :inflight nil)
                              sync-diag-header)
                  exhaust-diag {:reason :seat-retries-exhausted
                                :seat (:seat inflight)
                                :max-retries (:max-retries st-d)}]
              [st-fail (conj diag-effs [:diagnose exhaust-diag])])))))

    :phase2-patch-result
    (if (not= :awaiting-phase2-impl (:phase st))
      (record-diagnose st {:reason :unexpected-phase2-patch :phase (:phase st)} true)
      (let [patch-text (str (or (:patch-text ev) ""))
            patch-doc-path (or (:patch-doc-path ev) "docs/twin-circle-advisory-policy.md")
            [env' _] (env/step (:env st) :vector
                               {:action/type :protocol-act
                                :action/content (str "Generated advisory policy patch (" patch-doc-path ").")
                                :action/protocol {:verb :submit-patch :patch-text patch-text}
                                :action/raw-output (str "SUBMIT_PATCH " patch-doc-path)
                                :latency-ms (long (or (:latency-ms ev) 50))})
            st-next (-> st
                        (assoc :env env' :phase :awaiting-phase2-chaos)
                        sync-diag-header)]
        [st-next [[:execute-phase2-chaos {:run-id (:run-id st)
                                          :seed (:seed st)
                                          :patch-cid (:patch-cid env')}]]]))

    :phase2-chaos-result
    (if (not= :awaiting-phase2-chaos (:phase st))
      (record-diagnose st {:reason :unexpected-phase2-chaos :phase (:phase st)} true)
      (let [chaos-res (:chaos-results ev)
            ok? (boolean (:all-green? chaos-res))
            [env' _] (env/step (:env st) :vector
                               {:action/type :protocol-act
                                :action/content (if ok? "Proof + chaos suite all green." "Chaos failed.")
                                :action/protocol {:verb :record-chaos-result :chaos-results chaos-res}
                                :action/raw-output (str "CHAOS_RESULT all-green=" ok?)
                                :latency-ms (long (or (:latency-ms ev) 100))})
            st-done (-> st
                        (assoc :env env' :phase :complete :inflight nil)
                        sync-diag-header)]
        [st-done [[:emit-episode-complete {:run-id (:run-id st)
                                           :status (:status env')
                                           :round (:round env')}]]]))

    (record-diagnose st {:reason :unknown-coordinator-event :event-type (:event/type ev)} true)))

;; ---------------------------------------------------------------------------
;; 3. Transport-Agnostic Mealy Seat Responder Graph (`twin-seat-graph`)
;; ---------------------------------------------------------------------------

(defn twin-seat-step
  "Pure Mealy step function for a distributed Digital Twin seat agent.
   - Accepts ONLY valid `:twin/turn-request` or `:twin/amendment-request` envelopes
     for this seat; drops stray pleasantries/chatter with counted `:diagnose` (zero ping-pong).
   - Preserves `:session-id`, `:correlation-id`, `:generation`, `:run-id` verbatim on reply."
  [state in-port payload]
  (case in-port
    :incoming
    (let [decoded (proto/decode-envelope (:text payload))]
      (cond
        (nil? decoded)
        [state {:error [{:effect :diagnose
                         :reason :non-protocol-message-ignored
                         :seat (:seat-id state)
                         :from (:from payload)}]}]

        (not (contains? #{:twin/turn-request :twin/amendment-request} (:kind decoded)))
        [state {:error [{:effect :diagnose
                         :reason :unsupported-twin-kind-ignored
                         :seat (:seat-id state)
                         :kind (:kind decoded)}]}]

        (and (:coordinator-pubkey state) (not= (:coordinator-pubkey state) (:from payload)))
        [state {:error [{:effect :diagnose
                         :reason :unauthorized-coordinator
                         :seat (:seat-id state)
                         :expected (:coordinator-pubkey state)
                         :from (:from payload)}]}]

        (and (:seat-id state) (not= (:seat-id state) (:seat decoded)))
        [state {:error [{:effect :diagnose
                         :reason :wrong-seat-envelope-ignored
                         :expected (:seat-id state)
                         :actual (:seat decoded)}]}]

        (some? (:pending state))
        [state {:error [{:effect :diagnose
                         :reason :seat-busy-single-flight
                         :seat (:seat-id state)
                         :inflight-correlation-id (get-in state [:pending :envelope :correlation-id])
                         :dropped-correlation-id (:correlation-id decoded)}]}]

        :else
        (let [req-ctx {:from (:from payload)
                       :session-id (:session-id payload)
                       :envelope decoded}]
          [(assoc state :pending req-ctx :turn (inc (long (:turn state 0))))
           {:llm-req [{:prompt (:prompt decoded)}]}])))

    :model-reply
    (let [{:keys [from session-id envelope]} (:pending state)
          resp-kind (if (= :twin/amendment-request (:kind envelope))
                      :twin/amendment-response
                      :twin/turn-response)
          resp-env (cond-> {:kind resp-kind
                            :run-id (:run-id envelope)
                            :generation (:generation envelope)
                            :correlation-id (:correlation-id envelope)
                            :seat (:seat envelope)
                            :round (:round envelope)
                            :stage (:stage envelope)
                            :raw-text (str (or (:text payload) ""))
                            :latency-ms (long (or (:model-ms payload) 0))}
                     (some? (:token-accounting payload))
                     (assoc :token-usage (select-keys payload [:prompt-tokens :new-tokens :cumulative-tokens :token-accounting])))
          wire-str (proto/encode-envelope resp-env)]
      [(assoc state :last-response resp-env)
       {:net-send [(cond-> {:peer from
                            :message wire-str
                            :min-balance (:min-balance state)}
                     session-id (assoc :session-id session-id))]}])

    :net-reply
    (if (and (map? payload) (false? (:ok payload)))
      [(assoc state :pending nil)
       {:error [{:effect :diagnose :reason :net-command-failed :detail payload :turn (:turn state)}]}]
      [(assoc state :pending nil)
       {:done [{:response (:last-response state) :net-result payload :turn (:turn state)}]}])

    :net-rejected
    [(assoc state :pending nil)
     {:error [{:effect :diagnose :reason :budget-floor-rejected :detail payload :turn (:turn state)}]}]))

(defn twin-seat-graph
  "Declarative transport-agnostic Mealy Hiccup graph for any distributed twin seat."
  [{:keys [seat-id agent-name subnet-id min-balance coordinator-pubkey]
    :or {subnet-id "hive/poc" min-balance 2000}}]
  (let [nm (or agent-name (str "twin-" (name seat-id)))]
    [:agent {:id (keyword nm)
             :ins [:incoming-in]
             :outs [:reply-out :error-out]
             :requires {:llm {:type :intelligence/port}
                        :net {:type :net/bitecho}}}
     [:mealy {:id :seat-responder
              :init-state {:seat-id (keyword seat-id)
                           :agent-name nm
                           :subnet-id subnet-id
                           :min-balance (long min-balance)
                           :coordinator-pubkey coordinator-pubkey
                           :turn 0
                           :pending nil
                           :last-response nil}
              :ins [:incoming :model-reply :net-reply :net-rejected]
              :outs [:llm-req :net-send :done :error]
              :step twin-seat-step}]
     [:conn [:agent/in :incoming-in] [:seat-responder :incoming]]
     [:conn [:seat-responder :llm-req] [:cap/llm :generate]]
     [:conn [:cap/llm :reply] [:seat-responder :model-reply]]
     [:conn [:seat-responder :net-send] [:cap/net :send]]
     [:conn [:cap/net :reply] [:seat-responder :net-reply]]
     [:conn [:cap/net :rejected] [:seat-responder :net-rejected]]
     [:conn [:seat-responder :done] [:agent/out :reply-out]]
     [:conn [:seat-responder :error] [:agent/out :error-out]]]))

;; ---------------------------------------------------------------------------
;; 4. Transport-Agnostic Mealy Coordinator Graph (`twin-coordinator-graph`)
;; ---------------------------------------------------------------------------

(defn- effects->graph-outs
  [coord-st effects min-balance]
  (reduce (fn [outs [eff-type payload]]
            (case eff-type
              (:dispatch-seat-turn :dispatch-amendment)
              (let [wire-str (proto/encode-envelope (:wire-envelope payload))
                    send-cmd (cond-> {:peer (:to-pubkey payload)
                                      :message wire-str
                                      :min-balance min-balance}
                               (:session-id payload) (assoc :session-id (:session-id payload)))]
                (update outs :net-send conj send-cmd))

              :diagnose
              (update outs :error conj (assoc payload :effect :diagnose))

              :emit-episode-complete
              (update outs :done conj (assoc payload :coordinator-state coord-st))

              (:execute-phase2-impl :execute-phase2-chaos)
              (update outs :error conj {:effect :diagnose
                                        :reason :unhandled-phase2-effect-in-graph
                                        :effect-type eff-type
                                        :payload payload})

              outs))
          {:net-send [] :done [] :error []}
          effects))

(defn twin-coordinator-step
  "Pure Mealy step function wrapping `bitecho.hive.twin-coordinator/transition`
   so the coordinator runs over `:net/bitecho` identically in-memory or on live devnet."
  [state in-port payload]
  (case in-port
    :control
    (let [[coord' effs] (transition (:coord state) payload)
          outs (effects->graph-outs coord' effs (:min-balance state))]
      [(assoc state :coord coord') outs])

    :incoming
    (let [decoded (proto/decode-envelope (:text payload))]
      (cond
        (nil? decoded)
        (let [[coord' effs] (record-diagnose (:coord state)
                                             {:reason :non-protocol-message-ignored
                                              :from (:from payload)}
                                             true)]
          [(assoc state :coord coord') (effects->graph-outs coord' effs (:min-balance state))])

        (= :twin/turn-response (:kind decoded))
        (let [[coord' effs] (transition (:coord state)
                                        {:event/type :seat-response
                                         :correlation-id (:correlation-id decoded)
                                         :generation (:generation decoded)
                                         :seat (:seat decoded)
                                         :session-id (:session-id payload)
                                         :raw-text (:raw-text decoded)
                                         :latency-ms (:latency-ms decoded)
                                         :token-usage (:token-usage decoded)})]
          [(assoc state :coord coord') (effects->graph-outs coord' effs (:min-balance state))])

        (= :twin/amendment-response (:kind decoded))
        (let [[coord' effs] (transition (:coord state)
                                        {:event/type :amendment-response
                                         :correlation-id (:correlation-id decoded)
                                         :generation (:generation decoded)
                                         :seat (:seat decoded)
                                         :session-id (:session-id payload)
                                         :rewritten-text (:raw-text decoded)
                                         :latency-ms (:latency-ms decoded)})]
          [(assoc state :coord coord') (effects->graph-outs coord' effs (:min-balance state))])

        :else
        (let [[coord' effs] (record-diagnose (:coord state)
                                             {:reason :unsupported-twin-kind-ignored
                                              :kind (:kind decoded)
                                              :from (:from payload)}
                                             true)]
          [(assoc state :coord coord') (effects->graph-outs coord' effs (:min-balance state))])))

    :net-reply
    (if (and (map? payload) (false? (:ok payload)))
      [state {:error [{:effect :diagnose :reason :net-command-failed :detail payload}]}]
      [state {}])

    :net-rejected
    [state {:error [{:effect :diagnose :reason :budget-floor-rejected :detail payload}]}]))

(defn twin-coordinator-graph
  "Declarative transport-agnostic Mealy Hiccup graph for the Digital Twin Coordinator."
  [{:keys [coord-init-opts min-balance]
    :or {min-balance 2000}}]
  [:agent {:id :twin-coordinator
           :ins [:control-in :incoming-in]
           :outs [:done-out :error-out]
           :requires {:net {:type :net/bitecho}}}
   [:mealy {:id :coordinator
            :init-state {:coord (init-coordinator coord-init-opts)
                         :min-balance (long min-balance)}
            :ins [:control :incoming :net-reply :net-rejected]
            :outs [:net-send :done :error]
            :step twin-coordinator-step}]
   [:conn [:agent/in :control-in] [:coordinator :control]]
   [:conn [:agent/in :incoming-in] [:coordinator :incoming]]
   [:conn [:coordinator :net-send] [:cap/net :send]]
   [:conn [:cap/net :reply] [:coordinator :net-reply]]
   [:conn [:cap/net :rejected] [:coordinator :net-rejected]]
   [:conn [:coordinator :done] [:agent/out :done-out]]
   [:conn [:coordinator :error] [:agent/out :error-out]]])

(defn coordinator-state-of
  "Extracts the single-authority coordinator state from a bound `twin-coordinator-graph` instance."
  [graph-inst]
  (get-in graph-inst [:node-states :coordinator :coord]))
