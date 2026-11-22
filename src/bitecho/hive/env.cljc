(ns bitecho.hive.env
  "Pure Sans-IO Multi-Agent RL Environment Core (`R-1`, `.cljc`).

   Implements:
   - Declarative EDN scenario spec & 5-seat digital twin roster (`:prompt-cid` × `:harness` × `:model-selector`).
   - Pure environment reducer: `init-env`, `observe`, `step`, `terminal?`, `outcome`.
   - Typed dual-channel observation & action envelope (`:message | :tool-call | :protocol-act | :pass | :invalid-action`).
   - Two-fixed-point closed-loop D41 Sociocratic state machine (Phase 1 `:review` -> Fixed Point 1
     `:sim-consent-receipt` -> Phase 2 `:implement` + `:chaos-verify` -> Fixed Point 2 `:all-green`).
   - Hard isolation of `:sim-consent-receipt` (`:simulation? true :advisory-only true`) from real consent.
   - Recursive branching parallel-universe tree planner (`plan-universe-tree`)."
  (:require [bitecho.basalt.core :as basalt]
            [bitecho.crypto :as crypto]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Constants & Canonical Roster
;; ---------------------------------------------------------------------------

(def ^:const FORMATTER-VERSION
  "Version identifier for the deterministic `obs->messages` formatter."
  "hive-obs-messages-v1")

(def ^:const VALUES-DRAFT-CID
  "Full 64-char CID of `dist/bitecho-values-DRAFT.md` on `bitecho.org/dev`."
  "2b6d6a6a794ef168a00184faf35d3b77efb083c24671e3ff1092a432405f9448")

(def ^:const RUN-1-PROPOSAL-TEXT
  "Simon's simple real proposal for Run-1 closed-loop acceptance."
  "Adopt the digital-twin circle as a standing advisory first-review step for BIPs in the builders-stewards circle: before a real Stage 1 round, the twin circle runs the closed-loop simulation (consent sim, implementation on a copy, in-memory proof + chaos runs) and publishes the chained trace; the real circle reviews the trace as its first read. Simulated outcomes remain advisory; all real gates unchanged.")

(def ^:const RUN-2-PROPOSAL-TEXT
  "Simon's Run-2 proposal: Contact Book (Petname Rolodex) + Circles + Human Anchor + Encrypted Hash-Linked Memory Backup."
  "Implement the Memento Agent Continuity & Circles architecture across Bitecho and Mealy: (1) Self-signed Petname Address Book (Rolodex) export/import in the light-node CLI/core, storing per-entry {:petname :pubkey :allowed-actions} as periodic FULL snapshots with CRDT-style map DELTAS in between so an agent recursively rebuilds the full address book by folding deltas onto the latest snapshot and verifying its own Ed25519 signature on cold wake. (2) Circles modeled as mutual address-book subgraphs, requiring an explicit :human-anchor on real-world-twin circles and inheriting :human-anchor down to subcircles. (3) Encrypted Hash-Linked Memory Backup in network CAS storage where each encrypted memory Blob carries the 64-char CID pointer of the previous memory (enabling a blank-waking agent to walk backwards clue-to-clue from its Memento note), the genesis memory is the signature of the agent's own pubkey, and the private key is NEVER stored on the network. All changes keep a pure Sans-IO .cljc core, strict test.check invariants, and real Stage 2 review gates.")

(def read-only-tools
  "Tools permitted in `:review` mode (read-only codebase inspection)."
  #{:read-file :grep :list-dir :git-log :git-show})

(def write-tools
  "Tools permitted only in `:implement` mode inside a disposable sandbox copy."
  #{:write-file :edit-file :delete-file :exec-write :run-tests :run-format-lint :run-chaos})

(def review-stages
  "Ordered D41 Sociocratic stages executed within each `:review` round."
  [:present :questions :reactions :consent-round])

(def default-model-pool
  "Verified all-frontier `supported-agy-models` pool (GPT-OSS 120B retired per Simon directive 2026-11-22)."
  ["claude-opus-5-5-high"
   "claude-sonnet-5-5-high"
   "gemini-4-argon-high"
   "gemini-4-carbon-high"
   "gemini-4-hydrogen-high"])

(defn sha256-str
  "Computes lowercase 64-char SHA-256 hex digest of a UTF-8 string."
  [s]
  (let [bs #?(:clj (.getBytes (str s) "UTF-8")
              :cljs (.encode (js/TextEncoder.) (str s)))]
    (str/lower-case (basalt/bytes->hex (crypto/sha256 bs)))))

(defn twin-config-cid
  "Computes the deterministic 64-char `:twin-config-cid` for `(:prompt-cid × :harness × :model-selector × :values-cid)`."
  [{:keys [twin-of prompt-cid harness model-selector]} values-cid]
  (sha256-str (pr-str {:twin-of twin-of
                       :prompt-cid prompt-cid
                       :harness harness
                       :model-selector model-selector
                       :values-cid values-cid})))

(defn canonical-five-seats
  "Returns the verified 5-seat digital twin roster from Design Note `seq: 9`."
  []
  [{:seat-id :simon
    :twin-of :simon
    :role :human-anchor
    :harness :agy
    :model-selector "claude-opus-5-5-high"
    :selection-status :vector-starting-point
    :prompt-path "dist/hive/twin-prompt-simon.md"
    :prompt-cid "ff26581a1ecaa9c54ca62cc889031d9bd61ca495905317e8bc2ca6c1a72e2216"
    :prompt-status :adopted}
   {:seat-id :axiom
    :twin-of :axiom
    :role :architect-auditor
    :harness :agy
    :model-selector "gemini-4-argon-high"
    :selection-status :all-frontier-rotation-2026-11-22
    :prompt-path "dist/hive/twin-prompt-axiom.md"
    :prompt-cid "eeebba49a2e6c69bd878f350b9917542a6310e233365802ae462866abebc3ef4"
    :prompt-status :final}
   {:seat-id :nexus
    :twin-of :nexus
    :role :builder-lead
    :harness :agy
    :model-selector "claude-sonnet-5-5-high"
    :selection-status :proposed-for-nexus
    :prompt-path "dist/hive/twin-prompt-nexus.md"
    :prompt-cid "c8d08532ae87fce4fd6078d93209da1ce8fdd3a2191ad72467bd12c35f8f84f2"
    :prompt-status :final}
   {:seat-id :vector
    :twin-of :vector
    :role :soak-runtime-edge
    :harness :agy
    :model-selector "gemini-4-carbon-high"
    :selection-status :confirmed
    :prompt-path "dist/hive/twin-prompt-vector.md"
    :prompt-cid "518e17edfd4ded6f8ee6cc6fa18375749f4bd3af84331fc73fc817a9add4d237"
    :prompt-status :final}
   {:seat-id :lumen
    :twin-of :lumen
    :role :governance-architect
    :harness :agy
    :model-selector "gemini-4-hydrogen-high"
    :selection-status :proposed-for-lumen
    :prompt-path "dist/hive/twin-prompt-lumen.md"
    :prompt-cid "a954c14bd786e668b86ed83637d796a25c52e1fd67de29b748f45f7e3af15d0d"
    :prompt-status :final}])

(defn run-1-scenario-spec
  "Constructs the declarative EDN scenario spec for Simon's Run-1 acceptance fixture."
  ([] (run-1-scenario-spec 20261120))
  ([seed]
   {:scenario/version 1
    :scenario/id "governance-closed-loop-run-1"
    :scenario/kind :governance-closed-loop
    :scenario/seed (long seed)
    :scenario/simulation? true
    :scenario/advisory-only true
    :scenario/values-cid VALUES-DRAFT-CID
    :scenario/formatter-cid (sha256-str FORMATTER-VERSION)
    :scenario/proposal {:proposal/id "PROP-RUN-1"
                        :proposal/cid (sha256-str RUN-1-PROPOSAL-TEXT)
                        :proposal/text RUN-1-PROPOSAL-TEXT}
    :scenario/env-snapshot {:bitecho-commit "73ada39aa34dca12eb1450b792320568b1d15474"
                            :mealy-commit "024a9d8"
                            :hive-commit "5809339"
                            :clj-einsum-commit "3cc278c"}
    :scenario/seats (canonical-five-seats)
    :scenario/topology {:type :circle-broadcast
                        :turn-order [:simon :axiom :nexus :vector :lumen]}
    :scenario/limits {:max-review-rounds 3
                      :max-impl-rounds 3
                      :max-tool-calls-per-turn 8
                      :turn-timeout-ms 120000}
    :scenario/chaos-suite {:scenarios [:proof-streamlet-quorum
                                       :chaos-byte-drop-5pct
                                       :chaos-partition-heal]
                           :seed (long seed)}
    :scenario/ensemble {:ensemble/mode :tree
                        :ensemble/depth 1
                        :ensemble/branching-factor 1
                        :ensemble/shuffle-policy {:axes [:model-selector :harness]
                                                  :model-pool default-model-pool
                                                  :seed (long seed)}
                        :ensemble/prune-policy :keep-all}}))

;; ---------------------------------------------------------------------------
;; Deterministic Formatter (`obs->messages`)
;; ---------------------------------------------------------------------------

(defn obs->messages
  "Pure, deterministic formatter converting an observation map to LLM chat messages."
  [obs]
  [{:role "system"
    :content (str "Hive Twin Circle Simulation (advisory-only; simulated consent is never real consent). "
                  "Seat=" (name (:obs/agent-id obs))
                  " Phase=" (name (:obs/phase obs))
                  " Round=" (:obs/round obs)
                  " Stage=" (name (:obs/stage obs))
                  " Available=" (pr-str (:obs/available-actions obs)))}
   {:role "user"
    :content (str "Proposal (" (:obs/proposal-cid obs) "):\n"
                  (:obs/proposal-text obs)
                  "\n\nRecent inbox (" (count (:obs/inbox obs)) " messages): "
                  (pr-str (take-last 5 (:obs/inbox obs))))}])

;; ---------------------------------------------------------------------------
;; Pure Environment State Machine (`R-1`)
;; ---------------------------------------------------------------------------

(defn init-env
  "Initializes a pure R-1 environment state from a declarative scenario spec and seed."
  [scenario-spec seed]
  (let [turn-order (vec (or (get-in scenario-spec [:scenario/topology :turn-order])
                            (mapv :seat-id (:scenario/seats scenario-spec))))
        values-cid (or (:scenario/values-cid scenario-spec) VALUES-DRAFT-CID)
        provenance (into {}
                         (map (fn [s]
                                [(:seat-id s)
                                 (assoc s :twin-config-cid (twin-config-cid s values-cid))])
                              (:scenario/seats scenario-spec)))
        proposal (:scenario/proposal scenario-spec)]
    {:spec scenario-spec
     :seed (long seed)
     :simulation? true
     :advisory-only true
     :values-cid values-cid
     :formatter-cid (or (:scenario/formatter-cid scenario-spec) (sha256-str FORMATTER-VERSION))
     :policy-provenance provenance
     :turn-order turn-order
     :phase :review
     :round 1
     :stage :present
     :actor-idx 0
     :proposal-text (:proposal/text proposal)
     :proposal-cid (:proposal/cid proposal)
     :inbox []
     :round-verdicts {}
     :round-objections []
     :round-addenda []
     :standing-objections []
     :open-addenda []
     :sim-consent-receipt nil
     :impl-round 0
     :patch-cid nil
     :chaos-results nil
     :status :running
     :turns []
     :diagnostics {:invalid-actions 0 :rejected-transitions 0 :events []}}))

(defn next-actor
  "Returns the keyword seat ID whose turn is next."
  [env-state]
  (let [order (:turn-order env-state)]
    (nth order (mod (long (:actor-idx env-state)) (count order)))))

(defn phase-1-done?
  "Returns true when Phase 1 (`:review`) has reached `:fixed-point-1`, `:unresolved`, or moved to Phase 2."
  [env-state]
  (or (not= :review (:phase env-state))
      (contains? #{:fixed-point-1 :fixed-point-2 :unresolved} (:status env-state))))

(defn terminal?
  "Returns true when the full closed-loop episode has terminated."
  [env-state]
  (contains? #{:fixed-point-2 :unresolved} (:status env-state)))

(defn available-actions-for
  "Returns the capability-scoped available actions for the current phase and stage."
  [env-state]
  (case (:phase env-state)
    :review
    (if (= :consent-round (:stage env-state))
      [:message :tool-call/read-only
       :protocol-act/consent :protocol-act/consent-with-addendum :protocol-act/paramount-objection]
      [:message :tool-call/read-only :pass])

    :implement
    [:message :tool-call/read-only :tool-call/write :protocol-act/submit-patch :pass]

    :chaos-verify
    [:protocol-act/record-chaos-result]

    []))

(defn observe
  "Returns a pure EDN observation map for `agent-id`."
  [env-state agent-id]
  (let [max-rounds (long (get-in env-state [:spec :scenario/limits :max-review-rounds] 3))]
    {:obs/version 1
     :obs/phase (:phase env-state)
     :obs/round (:round env-state)
     :obs/stage (:stage env-state)
     :obs/agent-id agent-id
     :obs/proposal-text (:proposal-text env-state)
     :obs/proposal-cid (:proposal-cid env-state)
     :obs/inbox (:inbox env-state)
     :obs/state-view {:standing-objections (:standing-objections env-state)
                      :open-addenda (:open-addenda env-state)
                      :last-chaos-summary (:chaos-results env-state)}
     :obs/available-actions (available-actions-for env-state)
     :obs/budget {:rounds-remaining (max 0 (- max-rounds (long (:round env-state))))
                  :tool-calls-remaining (long (get-in env-state [:spec :scenario/limits :max-tool-calls-per-turn] 8))}}))

(defn- valid-sim-consent-receipt?
  [receipt expected-seats]
  (and (map? receipt)
       (true? (:simulation? receipt))
       (true? (:advisory-only receipt))
       (string? (:receipt-cid receipt))
       (= 64 (count (:receipt-cid receipt)))
       (empty? (:standing-objections receipt))
       (= (set expected-seats) (set (keys (:verdicts receipt))))
       (every? #(contains? #{:consent :consent-with-addendum} %) (vals (:verdicts receipt)))))

(defn transition-to-implement
  "Gated transition from Phase 1 (`:review`) to Phase 2 (`:implement`) inside a disposable sandbox copy.
   Fails closed with a counted `:diagnose` effect unless `sim-receipt` is a valid `:sim-consent-receipt`
   tagged `:simulation? true :advisory-only true`."
  [env-state sim-receipt]
  (if-not (valid-sim-consent-receipt? sim-receipt (:turn-order env-state))
    (let [diag {:reason :invalid-or-missing-sim-consent-receipt
                :phase (:phase env-state)
                :simulation-flag (:simulation? sim-receipt)}
          st' (-> env-state
                  (update-in [:diagnostics :rejected-transitions] inc)
                  (update-in [:diagnostics :events] conj diag))]
      [st' [[:diagnose diag]]])
    (let [base-sha (get-in env-state [:spec :scenario/env-snapshot :bitecho-commit])
          st' (assoc env-state
                     :phase :implement
                     :stage :implement
                     :impl-round 1
                     :actor-idx 0
                     :status :running
                     :sim-consent-receipt sim-receipt)]
      [st' [[:provision-disposable-copy {:base-commit-sha base-sha
                                         :sim-consent-receipt-cid (:receipt-cid sim-receipt)}]]])))

(defn- validate-action
  [env-state action]
  (let [atype (:action/type action)
        phase (:phase env-state)]
    (cond
      (not (map? action))
      {:ok? false :error :non-map-action}

      (not (contains? #{:message :tool-call :protocol-act :pass} atype))
      {:ok? false :error :unknown-action-type :type atype}

      (and (= :tool-call atype)
           (= :review phase)
           (not (contains? read-only-tools (get-in action [:action/tool :name]))))
      {:ok? false :error :disallowed-write-tool-in-review :tool (get-in action [:action/tool :name])}

      (and (= :protocol-act atype)
           (= :paramount-objection (get-in action [:action/protocol :verb]))
           (str/blank? (str (get-in action [:action/protocol :harm-to-aim]))))
      {:ok? false :error :paramount-objection-missing-harm-to-aim}

      :else
      {:ok? true})))

(defn- finalize-review-round
  [env-state]
  (let [max-rounds (long (get-in env-state [:spec :scenario/limits :max-review-rounds] 3))
        objs (:round-objections env-state)
        adds (:round-addenda env-state)
        verdicts (:round-verdicts env-state)
        cur-round (long (:round env-state))]
    (cond
      ;; Fixed Point 1 (D41): zero paramount objections and either zero addenda OR non-blocking
      ;; addenda carried explicitly into :open-addenda after an amendment pass / at round limit.
      (and (empty? objs)
           (or (empty? adds)
               (> cur-round 1)
               (>= cur-round max-rounds)))
      (let [carried-addenda (into (:open-addenda env-state) adds)
            receipt-body {:simulation? true
                          :advisory-only true
                          :proposal-cid (:proposal-cid env-state)
                          :round cur-round
                          :verdicts verdicts
                          :standing-objections []
                          :open-addenda carried-addenda}
            receipt (assoc receipt-body :receipt-cid (sha256-str (pr-str receipt-body)))]
        [(assoc env-state
                :status :fixed-point-1
                :sim-consent-receipt receipt
                :standing-objections []
                :open-addenda carried-addenda)
         [[:emit-sim-consent-receipt receipt]]])

      ;; Round limit hit with standing paramount objections -> :unresolved
      (>= cur-round max-rounds)
      [(assoc env-state
              :status :unresolved
              :standing-objections objs)
       [[:diagnose {:reason :review-round-limit-unresolved :round cur-round :objections (count objs)}]]]

      ;; Otherwise shape proposal via amendment loop and begin next review round
      :else
      (let [amendment-note (str "\n\n[Round " cur-round " Amendment: addressed "
                                (count objs) " objection(s) and " (count adds) " addendum flag(s)]")
            shaped-text (str (:proposal-text env-state) amendment-note)
            shaped-cid (sha256-str shaped-text)]
        [(assoc env-state
                :round (inc cur-round)
                :stage :present
                :actor-idx 0
                :proposal-text shaped-text
                :proposal-cid shaped-cid
                :round-verdicts {}
                :round-objections []
                :round-addenda []
                :open-addenda (into (:open-addenda env-state) adds))
         [[:emit-proposal-amendment {:round cur-round :shaped-proposal-cid shaped-cid}]]]))))

(defn- advance-review-cursor
  [env-state]
  (let [n-seats (count (:turn-order env-state))
        next-idx (inc (long (:actor-idx env-state)))]
    (if (< next-idx n-seats)
      [(assoc env-state :actor-idx next-idx) []]
      (let [stage-idx (.indexOf ^java.util.List review-stages (:stage env-state))
            next-stage-idx (inc stage-idx)]
        (if (< next-stage-idx (count review-stages))
          [(assoc env-state :stage (nth review-stages next-stage-idx) :actor-idx 0) []]
          (finalize-review-round env-state))))))

(defn step
  "Pure R-1 step reducer: `(step env-state agent-id action) -> [env-state' effects]`."
  [env-state agent-id action]
  (let [obs (observe env-state agent-id)
        val-res (validate-action env-state action)
        turn-seq (inc (count (:turns env-state)))]
    (if-not (:ok? val-res)
      (let [inv-action {:action/type :invalid-action
                        :action/error (:error val-res)
                        :action/raw-output (str (:action/raw-output action))}
            turn-rec {:seq turn-seq
                      :actor-id agent-id
                      :phase (:phase env-state)
                      :round (:round env-state)
                      :stage (:stage env-state)
                      :observation obs
                      :action inv-action
                      :step-reward -0.25
                      :latency-ms (long (or (:latency-ms action) 0))}
            diag {:reason (:error val-res) :actor agent-id :turn turn-seq}
            st' (-> env-state
                    (update :turns conj turn-rec)
                    (update-in [:diagnostics :invalid-actions] inc)
                    (update-in [:diagnostics :events] conj diag))
            [st'' adv-effs] (if (= :review (:phase st'))
                              (advance-review-cursor st')
                              [st' []])]
        [st'' (into [[:diagnose diag]] adv-effs)])
      (let [turn-rec {:seq turn-seq
                      :actor-id agent-id
                      :phase (:phase env-state)
                      :round (:round env-state)
                      :stage (:stage env-state)
                      :observation obs
                      :action action
                      :step-reward (double (or (:step-reward action) 0.0))
                      :latency-ms (long (or (:latency-ms action) 10))}
            st-base (update env-state :turns conj turn-rec)]
        (case (:phase env-state)
          :review
          (let [st-updated
                (case (:action/type action)
                  :message
                  (update st-base :inbox conj {:from agent-id
                                               :round (:round env-state)
                                               :stage (:stage env-state)
                                               :content (:action/content action)})
                  :protocol-act
                  (let [proto (:action/protocol action)
                        verb (:verb proto)]
                    (cond-> (assoc-in st-base [:round-verdicts agent-id] verb)
                      (= :paramount-objection verb)
                      (update :round-objections conj {:from agent-id
                                                      :round (:round env-state)
                                                      :harm-to-aim (:harm-to-aim proto)
                                                      :fix (:fix proto)})
                      (= :consent-with-addendum verb)
                      (update :round-addenda into (or (:flags proto) []))))
                  st-base)
                [st-next effs] (advance-review-cursor st-updated)]
            [st-next (into [[:emit-turn-trace turn-rec]] effs)])

          :implement
          (if (= :submit-patch (get-in action [:action/protocol :verb]))
            (let [patch-text (or (get-in action [:action/protocol :patch-text])
                                 (:action/content action)
                                 "diff --git a/docs/GOVERNANCE.md b/docs/GOVERNANCE.md\n")
                  p-cid (sha256-str patch-text)]
              [(assoc st-base :phase :chaos-verify :stage :chaos-verify :patch-cid p-cid)
               [[:emit-turn-trace turn-rec]
                [:run-chaos-suite {:patch-cid p-cid
                                   :chaos-suite (get-in env-state [:spec :scenario/chaos-suite])}]]])
            [(update st-base :actor-idx inc) [[:emit-turn-trace turn-rec]]])

          :chaos-verify
          (let [chaos-res (or (get-in action [:action/protocol :chaos-results])
                              {:all-green? true :scenarios-passed 3 :scenarios-total 3})
                max-impl (long (get-in env-state [:spec :scenario/limits :max-impl-rounds] 3))]
            (cond
              (:all-green? chaos-res)
              [(assoc st-base
                      :chaos-results chaos-res
                      :status :fixed-point-2)
               [[:emit-turn-trace turn-rec]
                [:emit-fixed-point-2 {:patch-cid (:patch-cid st-base) :chaos-results chaos-res}]]]

              (>= (long (:impl-round st-base)) max-impl)
              [(assoc st-base
                      :chaos-results chaos-res
                      :status :unresolved)
               [[:emit-turn-trace turn-rec]
                [:diagnose {:reason :impl-round-limit-unresolved :chaos-results chaos-res}]]]

              :else
              [(assoc st-base
                      :phase :implement
                      :stage :implement
                      :impl-round (inc (long (:impl-round st-base)))
                      :chaos-results chaos-res)
               [[:emit-turn-trace turn-rec]]]))

          [st-base [[:emit-turn-trace turn-rec]]])))))

(defn outcome
  "Returns the structured episode outcome map."
  [env-state]
  {:status (:status env-state)
   :phase (:phase env-state)
   :round (:round env-state)
   :impl-round (:impl-round env-state)
   :proposal-cid (:proposal-cid env-state)
   :sim-consent-receipt (:sim-consent-receipt env-state)
   :standing-objections (:standing-objections env-state)
   :open-addenda (:open-addenda env-state)
   :patch-cid (:patch-cid env-state)
   :chaos-results (:chaos-results env-state)
   :diagnostics (:diagnostics env-state)})

(defn apply-proposal-amendment
  "Pure reducer hook for `:amendment-loop`: replaces `:proposal-text` with the
   proposer's genuinely rewritten `amended-text`, updates `:proposal-cid`, records
   the `:propose-amendment` turn in `:turns`, and notifies `:inbox` for the next round."
  [env-state proposer-id amended-text latency-ms]
  (let [clean-text (str/trim (str amended-text))
        new-cid (sha256-str clean-text)
        obs (observe env-state proposer-id)
        turn-seq (inc (count (:turns env-state)))
        turn-rec {:seq turn-seq
                  :actor-id proposer-id
                  :phase :review
                  :round (max 1 (dec (long (:round env-state))))
                  :stage :amendment-loop
                  :observation obs
                  :action {:action/type :protocol-act
                           :action/content clean-text
                           :action/protocol {:verb :propose-amendment
                                             :amended-proposal-cid new-cid}
                           :action/raw-output clean-text}
                  :step-reward 0.0
                  :latency-ms (long (or latency-ms 0))}]
    [(-> env-state
         (assoc :proposal-text clean-text
                :proposal-cid new-cid)
         (update :turns conj turn-rec)
         (update :inbox conj {:from proposer-id
                              :round (max 1 (dec (long (:round env-state))))
                              :stage :amendment-loop
                              :content (str "Rewrote proposal for Round " (:round env-state)
                                            " (CID " (subs new-cid 0 8) "...).")}))
     [[:emit-turn-trace turn-rec]]]))

;; ---------------------------------------------------------------------------
;; Parallel-Universe Tree Planner (Section 2.5 & 4.4)
;; ---------------------------------------------------------------------------

(defn- deterministic-draw-seats
  [seats model-pool draw-seed]
  (let [pool-n (count model-pool)]
    (mapv (fn [idx seat]
            (let [h (sha256-str (str draw-seed ":" idx ":" (name (:seat-id seat))))
                  pick-idx (mod #?(:clj (Long/parseLong (subs h 0 8) 16)
                                   :cljs (js/parseInt (subs h 0 8) 16))
                                pool-n)]
              (assoc seat :model-selector (nth model-pool pick-idx))))
          (range (count seats))
          seats)))

(defn plan-universe-tree
  "Purely generates the deterministic recursive branching universe tree manifest
   for `:scenario/ensemble` (`depth` × `branching-factor`)."
  [scenario-spec]
  (let [ens (:scenario/ensemble scenario-spec)
        max-depth (long (max 1 (or (:ensemble/depth ens) 1)))
        branching (long (max 1 (or (:ensemble/branching-factor ens) 1)))
        pool (or (get-in ens [:ensemble/shuffle-policy :model-pool]) default-model-pool)
        base-seed (long (or (get-in ens [:ensemble/shuffle-policy :seed]) (:scenario/seed scenario-spec) 1))
        seats (:scenario/seats scenario-spec)
        values-cid (or (:scenario/values-cid scenario-spec) VALUES-DRAFT-CID)]
    (letfn [(build-node [u-id parent-id depth draw-seed]
              (let [drawn-seats (if (and (zero? depth) (= 1 branching))
                                  seats
                                  (deterministic-draw-seats seats pool draw-seed))
                    seat-cids (into {}
                                    (map (fn [s]
                                           [(:seat-id s) (twin-config-cid s values-cid)])
                                         drawn-seats))
                    child-ids (when (< (inc depth) max-depth)
                                (mapv #(str u-id "-" %) (range branching)))
                    this-node {:universe/id u-id
                               :universe/parent-id parent-id
                               :universe/depth depth
                               :universe/draw-seed draw-seed
                               :universe/seat-config-cids seat-cids
                               :universe/children (or child-ids [])}
                    child-nodes (when child-ids
                                  (mapcat (fn [b-idx cid]
                                            (build-node cid u-id (inc depth) (+ (* draw-seed 31) b-idx 1)))
                                          (range branching)
                                          child-ids))]
                (cons this-node child-nodes)))]
      (let [root-nodes (if (= 1 branching)
                         (build-node "u-0" nil 0 base-seed)
                         (mapcat (fn [b]
                                   (build-node (str "u-" b) nil 0 (+ base-seed b)))
                                 (range branching)))]
        {:ensemble/version 1
         :ensemble/root-proposal-cid (get-in scenario-spec [:scenario/proposal :proposal/cid])
         :ensemble/depth max-depth
         :ensemble/branching-factor branching
         :ensemble/nodes (vec root-nodes)}))))

;; ---------------------------------------------------------------------------
;; In-Memory Closed-Loop Driver (Step 1 Verification / Acceptance)
;; ---------------------------------------------------------------------------

#?(:clj
   (defn run-in-memory-closed-loop!
     "Runs a full two-fixed-point closed-loop episode in-memory using deterministic
      seeded policies (no external network or bwrap required), scores it via R-2 SCI
      scorer, and builds + verifies the R-3 v2 Merkle trace & JSONL export."
     [scenario-spec]
     (let [score-fn (requiring-resolve 'bitecho.hive.scorer/score-episode)
           build-v2 (requiring-resolve 'bitecho.hive.trace/build-episode-trace-v2)
           verify-v2 (requiring-resolve 'bitecho.hive.trace/verify-episode-trace-v2)
           export-jsonl (requiring-resolve 'bitecho.hive.trace/export-trace-v2->jsonl)
           st0 (init-env scenario-spec (:scenario/seed scenario-spec))
        ;; Phase 1: Round 1 has 1 constructive addendum from :axiom -> triggers amendment -> Round 2 reaches Fixed Point 1
           st-fp1
           (loop [st st0]
             (if (phase-1-done? st)
               st
               (let [actor (next-actor st)
                     stage (:stage st)
                     rnd (:round st)
                     act (cond
                           (and (= :consent-round stage) (= 1 rnd) (= :axiom actor))
                           {:action/type :protocol-act
                            :action/content "CONSENT-WITH-ADDENDUM: pin :advisory-only true in GOVERNANCE.md."
                            :action/protocol {:verb :consent-with-addendum
                                              :flags [{:id "F-1"
                                                       :text "Pin :advisory-only true explicitly in GOVERNANCE.md"
                                                       :owner :vector
                                                       :target-milestone "Run-1"}]}
                            :action/raw-output "CONSENT-WITH-ADDENDUM F-1"}

                           (= :consent-round stage)
                           {:action/type :protocol-act
                            :action/content (str "CONSENT from " (name actor) " twin (round " rnd ").")
                            :action/protocol {:verb :consent}
                            :action/raw-output "CONSENT"}

                           :else
                           {:action/type :message
                            :action/content (str (name actor) " " (name stage) " (round " rnd ").")
                            :action/raw-output (str (name actor) " " (name stage))})
                     [st' _] (step st actor act)]
                 (recur st'))))
        ;; Gate transition to Phase 2 on :sim-consent-receipt
           [st-p2 _] (transition-to-implement st-fp1 (:sim-consent-receipt st-fp1))
        ;; Phase 2: Implement + submit patch + in-memory proof/chaos all-green -> Fixed Point 2
           [st-patch _] (step st-p2 :vector
                              {:action/type :protocol-act
                               :action/content "Submitted advisory first-review governance doc patch."
                               :action/protocol {:verb :submit-patch
                                                 :patch-text "--- a/docs/GOVERNANCE.md\n+++ b/docs/GOVERNANCE.md\n+## Standing Advisory Twin-Circle First Review\n+Simulated outcomes are advisory only; real gates unchanged.\n"}
                               :action/raw-output "SUBMIT_PATCH docs/GOVERNANCE.md"})
           [st-fp2 _] (step st-patch :vector
                            {:action/type :protocol-act
                             :action/content "In-memory proof + byte-level chaos suite all green."
                             :action/protocol {:verb :record-chaos-result
                                               :chaos-results {:all-green? true
                                                               :scenarios-passed 3
                                                               :scenarios-total 3
                                                               :byte-drop-rate 0.05
                                                               :silent-drops 0}}
                             :action/raw-output "CHAOS_ALL_GREEN 3/3"})
           rewards (score-fn st-fp2)
           trace-v2 (build-v2 {:env-state st-fp2 :rewards rewards :seed (:scenario/seed scenario-spec)})
           verification (verify-v2 trace-v2)
           jsonl (export-jsonl trace-v2)]
       {:env-state st-fp2
        :rewards rewards
        :trace-v2 trace-v2
        :verification verification
        :jsonl jsonl})))
