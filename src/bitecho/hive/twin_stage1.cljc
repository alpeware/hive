(ns bitecho.hive.twin-stage1
  "Pure Sans-IO Stage 1 Advisory Digital Twin Pre-Shaping Runner (`.cljc`).

   Implements the formalized Stage 1 pipeline:
   - Independent Blind Round-1 per-seat context isolation (`isolate-round1-context`,
     `build-blind-round1-prompt`, `build-all-round1-envelopes`) with mechanically
     empty `:peer-outputs []` and zero cross-seat leakage.
   - Mandatory schema-checked `:falsifier` slot (`:failure-mode`, `:prod-trigger`,
     `:observable-signal` — \"what breaks in prod\") via `validate-round1-seat-output`
     and fail-closed `assemble-round1` emitting counted `:diagnose` effects.
   - All-frontier 5-seat roster (`:simon`, `:axiom`, `:nexus`, `:vector`, `:lumen`)
     with zero retired `gpt-oss-120b` models.
   - Round-2+ synthesis, fixed-point convergence, and `:unresolved` exit on
     round-limit (never silent consent) via `run-stage1-pre-shaping`.
   - Deterministic RFC 6962 Merkle Trace v2 root (`verify-stage1-trace`) and hard
     advisory-only flags (`:advisory-only? true`, `:real-consent-required? true`,
     `:simulated-consent-counts-as-real? false`)."
  (:require [bitecho.core.data :as data]
            [bitecho.hive.env :as env]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Constants & All-Frontier Roster
;; ---------------------------------------------------------------------------

(def ^:const VALUES-CID
  "Full 64-char SHA-256 CID of ratified `docs/VALUES.md`."
  env/VALUES-DRAFT-CID)

(def valid-verdicts
  "Allowed Stage 1 seat verdicts."
  #{:objection :amendment :no-objection})

(def required-falsifier-fields
  "Mandatory non-empty string fields in every seat's `:falsifier` map."
  [:failure-mode :prod-trigger :observable-signal])

(def advisory-invariants
  "Hard non-negotiable advisory-only flags stamped on every Stage 1 envelope, round, and trace."
  {:advisory-only? true
   :real-consent-required? true
   :simulated-consent-counts-as-real? false})

(defn retired-model?
  "Returns true if `model-selector` is blank or belongs to a retired model family (e.g. `gpt-oss-120b`)."
  [model-selector]
  (let [s (str/lower-case (str model-selector))]
    (or (str/blank? s)
        (str/includes? s "gpt-oss"))))

(defn seat->twin-identity
  "Constructs the canonical `:twin-identity` map (`prompt × harness × model-selector × values-cid`)."
  ([seat] (seat->twin-identity seat VALUES-CID))
  ([seat values-cid]
   (let [v-cid (or values-cid VALUES-CID)]
     {:twin-of (:twin-of seat)
      :role (:role seat)
      :prompt-cid (:prompt-cid seat)
      :harness (:harness seat)
      :model-selector (:model-selector seat)
      :values-cid v-cid
      :twin-config-cid (env/twin-config-cid seat v-cid)})))

(defn canonical-stage1-roster
  "Returns the verified 5-seat all-frontier digital twin roster enriched with `:twin-identity`."
  ([] (canonical-stage1-roster VALUES-CID))
  ([values-cid]
   (mapv (fn [seat]
           (assoc seat :twin-identity (seat->twin-identity seat values-cid)))
         (env/canonical-five-seats))))

(defn validate-roster
  "Validates a Stage 1 roster, returning a vector of diagnostic maps (empty when valid)."
  [roster]
  (if-not (and (sequential? roster) (seq roster))
    [{:reason :empty-roster}]
    (vec
     (mapcat
      (fn [seat]
        (let [seat-id (:seat-id seat)
              model (:model-selector seat)]
          (cond-> []
            (not (keyword? seat-id))
            (conj {:reason :invalid-roster-seat :seat seat-id})

            (retired-model? model)
            (conj {:reason :retired-model-selector :seat seat-id :model-selector model})

            (str/blank? (str (:prompt-cid seat)))
            (conj {:reason :missing-seat-prompt-cid :seat seat-id})

            (nil? (:harness seat))
            (conj {:reason :missing-seat-harness :seat seat-id}))))
      roster))))

;; ---------------------------------------------------------------------------
;; Normalization & Diagnostics Helpers
;; ---------------------------------------------------------------------------

(defn normalize-proposal
  "Normalizes a proposal map `{:bip-id :title :cid :body}`, deriving `:cid` if absent."
  [proposal]
  (let [bip-id (str (or (:bip-id proposal) "BIP-UNASSIGNED"))
        title (str (or (:title proposal) ""))
        body (str (or (:body proposal) ""))
        cid (if (str/blank? (str (:cid proposal)))
              (env/sha256-str (pr-str {:bip-id bip-id :title title :body body}))
              (str (:cid proposal)))]
    {:bip-id bip-id
     :title title
     :cid cid
     :body body}))

(defn- summarize-diagnostics
  [events]
  (let [ev-vec (vec events)]
    {:diagnostics {:count (count ev-vec)
                   :by-reason (frequencies (map :reason ev-vec))
                   :events ev-vec}
     :effects (mapv (fn [d] [:diagnose d]) ev-vec)}))

(defn- resolve-seat
  [seat roster]
  (if (map? seat)
    seat
    (first (filter #(= seat (:seat-id %)) roster))))

(defn- extract-own-prior-objections
  [seat-id member-prior-objections]
  (cond
    (map? member-prior-objections)
    (vec (or (get member-prior-objections seat-id) []))

    (sequential? member-prior-objections)
    (vec (filter (fn [entry]
                   (if (and (map? entry) (contains? entry :seat))
                     (= seat-id (:seat entry))
                     true))
                 member-prior-objections))

    :else
    []))

;; ---------------------------------------------------------------------------
;; Blind Round-1 Context Isolation
;; ---------------------------------------------------------------------------

(defn isolate-round1-context
  "Constructs a strictly isolated Round-1 input envelope for a single seat.
   Mechanically enforces `:round 1`, `:blind? true`, `:peer-outputs []`, and
   strips all foreign/peer fields so zero peer outputs can leak into Round 1."
  [{:keys [seat roster proposal member-prior-objections values-cid]}]
  (let [v-cid (or values-cid VALUES-CID)
        effective-roster (or roster (canonical-stage1-roster v-cid))
        seat-map (resolve-seat seat effective-roster)
        seat-id (or (:seat-id seat-map) (when (keyword? seat) seat))
        twin-id (or (:twin-identity seat-map)
                    (when seat-map (seat->twin-identity seat-map v-cid)))
        own-priors (extract-own-prior-objections seat-id member-prior-objections)]
    (merge
     {:episode/values-cid v-cid
      :seat seat-id
      :twin-identity twin-id
      :member-prior-objections own-priors
      :proposal (normalize-proposal proposal)
      :round 1
      :blind? true
      :peer-outputs []}
     advisory-invariants)))

(defn build-all-round1-envelopes
  "Builds the strictly isolated Round-1 input envelopes for every seat in `roster`
   ready for parallel independent per-seat execution. Ignores any smuggled peer outputs."
  [{:keys [roster proposal prior-objections-by-seat values-cid]}]
  (let [v-cid (or values-cid VALUES-CID)
        effective-roster (or roster (canonical-stage1-roster v-cid))]
    (mapv (fn [seat-map]
            (isolate-round1-context
             {:seat seat-map
              :roster effective-roster
              :proposal proposal
              :member-prior-objections (when (map? prior-objections-by-seat)
                                         (get prior-objections-by-seat (:seat-id seat-map)))
              :values-cid v-cid}))
          effective-roster)))

(defn build-blind-round1-prompt
  "Pure, deterministic formatter that turns an isolated Round-1 envelope into a
   self-contained blind Round-1 prompt with explicit `:falsifier` schema instructions."
  [envelope]
  (let [iso (if (and (true? (:blind? envelope))
                     (= 1 (:round envelope))
                     (empty? (:peer-outputs envelope)))
              envelope
              (isolate-round1-context envelope))
        prop (:proposal iso)
        priors (:member-prior-objections iso)
        twin-id (:twin-identity iso)
        prompt-text
        (str "=== STAGE 1 ADVISORY TWIN PRE-SHAPING (ROUND 1 — BLIND, ISOLATED) ===\n"
             "ADVISORY ONLY: Simulated consent NEVER counts as real consent. Real Stage 2 consent is required.\n"
             "Values CID: " (:episode/values-cid iso) "\n"
             "Seat: " (name (:seat iso)) " (Role: " (name (or (:role twin-id) :unknown)) ")\n"
             "Twin Identity: harness=" (name (or (:harness twin-id) :unknown))
             " model=" (:model-selector twin-id)
             " prompt-cid=" (:prompt-cid twin-id) "\n"
             "Peer Outputs Visible: [] (NONE — blind Round 1)\n\n"
             "Your Own Prior Objections / Focus:\n"
             (if (seq priors)
               (str/join "\n" (map #(str "- " %) priors))
               "- (none recorded)")
             "\n\nProposal: " (:bip-id prop) " — " (:title prop) "\n"
             "Proposal CID: " (:cid prop) "\n"
             "Body:\n" (:body prop) "\n\n"
             "REQUIRED OUTPUT SCHEMA (fail-closed):\n"
             "1. :verdict — one of :objection | :amendment | :no-objection\n"
             "2. :critique — non-empty technical critique grounded in Values CID\n"
             "3. :falsifier — MANDATORY map of \"what breaks in prod\":\n"
             "   {:failure-mode <non-empty string>\n"
             "    :prod-trigger <non-empty string>\n"
             "    :observable-signal <non-empty string>}\n")]
    {:envelope iso
     :prompt-text prompt-text
     :prompt-cid (env/sha256-str prompt-text)}))

;; ---------------------------------------------------------------------------
;; Mandatory Schema-Checked :falsifier Validation (Fail-Closed, Rule 5)
;; ---------------------------------------------------------------------------

(defn- non-blank-str?
  [x]
  (and (string? x) (not (str/blank? x))))

(defn validate-round1-seat-output
  "Validates a seat's Stage 1 output, enforcing the mandatory `:falsifier` slot
   (`:failure-mode`, `:prod-trigger`, `:observable-signal`), valid `:verdict`,
   non-empty `:critique`, and non-retired `:twin-identity`.
   Fails closed with a structured `:diagnose` map on any violation."
  ([seat-output] (validate-round1-seat-output nil seat-output))
  ([expected-seat seat-output]
   (cond
     (not (map? seat-output))
     (let [diag {:reason :non-map-seat-output :seat expected-seat}]
       {:ok? false :reason :non-map-seat-output :seat expected-seat :diagnose diag})

     :else
     (let [seat (or (:seat seat-output) expected-seat)
           twin-id (:twin-identity seat-output)
           verdict (:verdict seat-output)
           critique (:critique seat-output)
           falsifier (:falsifier seat-output)
           missing-falsifier-fields
           (if (map? falsifier)
             (vec (remove #(non-blank-str? (get falsifier %)) required-falsifier-fields))
             required-falsifier-fields)]
       (cond
         (not (keyword? seat))
         (let [diag {:reason :missing-seat-id :seat expected-seat}]
           {:ok? false :reason :missing-seat-id :seat expected-seat :diagnose diag})

         (and expected-seat (not= expected-seat seat))
         (let [diag {:reason :seat-mismatch :expected expected-seat :actual seat}]
           {:ok? false :reason :seat-mismatch :seat expected-seat :diagnose diag})

         (not (and (map? twin-id)
                   (non-blank-str? (:prompt-cid twin-id))
                   (some? (:harness twin-id))
                   (non-blank-str? (:model-selector twin-id))))
         (let [diag {:reason :missing-twin-identity :seat seat}]
           {:ok? false :reason :missing-twin-identity :seat seat :diagnose diag})

         (retired-model? (:model-selector twin-id))
         (let [diag {:reason :retired-model-selector
                     :seat seat
                     :model-selector (:model-selector twin-id)}]
           {:ok? false :reason :retired-model-selector :seat seat :diagnose diag})

         (not (contains? valid-verdicts verdict))
         (let [diag {:reason :invalid-verdict :seat seat :verdict verdict}]
           {:ok? false :reason :invalid-verdict :seat seat :diagnose diag})

         (not (non-blank-str? critique))
         (let [diag {:reason :missing-critique :seat seat}]
           {:ok? false :reason :missing-critique :seat seat :diagnose diag})

         (seq missing-falsifier-fields)
         (let [diag {:reason :missing-seat-falsifier
                     :seat seat
                     :missing-fields missing-falsifier-fields}]
           {:ok? false
            :reason :missing-seat-falsifier
            :seat seat
            :missing-fields missing-falsifier-fields
            :diagnose diag})

         :else
         {:ok? true
          :seat seat
          :output {:seat seat
                   :twin-identity twin-id
                   :verdict verdict
                   :critique (str/trim critique)
                   :falsifier {:failure-mode (str/trim (:failure-mode falsifier))
                               :prod-trigger (str/trim (:prod-trigger falsifier))
                               :observable-signal (str/trim (:observable-signal falsifier))}}})))))

;; ---------------------------------------------------------------------------
;; Round Assembly (Fail-Closed)
;; ---------------------------------------------------------------------------

(defn- chain-round-turns
  [round blind? proposal-cid outputs prev-cid]
  (reduce (fn [{:keys [records cids prev]} out]
            (let [turn-body {:round (long round)
                             :blind? (boolean blind?)
                             :proposal-cid proposal-cid
                             :prev-cid prev
                             :seat (:seat out)
                             :twin-identity (:twin-identity out)
                             :verdict (:verdict out)
                             :critique (:critique out)
                             :falsifier (:falsifier out)}
                  cid (env/sha256-str (pr-str turn-body))]
              {:records (conj records (assoc turn-body :turn/cid cid))
               :cids (conj cids cid)
               :prev cid}))
          {:records [] :cids [] :prev prev-cid}
          outputs))

(defn assemble-round1
  "Validates and assembles all seats' outputs for a Stage 1 round (defaults to Round 1, blind).
   Fails closed with `:status :incomplete` and counted `:diagnose` effects if the roster
   contains a retired model or ANY seat output is missing or lacks a valid `:falsifier`."
  [{:keys [roster seat-outputs proposal values-cid round prev-turn-cid]}]
  (let [v-cid (or values-cid VALUES-CID)
        rnd (long (or round 1))
        blind? (= 1 rnd)
        effective-roster (or roster (canonical-stage1-roster v-cid))
        norm-proposal (normalize-proposal proposal)
        roster-diags (validate-roster effective-roster)
        expected-seats (mapv :seat-id effective-roster)
        expected-set (set expected-seats)
        raw-outputs (vec (or seat-outputs []))
        outputs-by-seat (reduce (fn [acc o]
                                  (if (and (map? o) (keyword? (:seat o)))
                                    (update acc (:seat o) (fnil conj []) o)
                                    acc))
                                {}
                                raw-outputs)
        extra-diags (vec (keep (fn [o]
                                 (cond
                                   (not (map? o))
                                   {:reason :non-map-seat-output :seat nil}

                                   (not (contains? expected-set (:seat o)))
                                   {:reason :unexpected-seat-output :seat (:seat o)}

                                   :else nil))
                               raw-outputs))
        {:keys [valid-outputs seat-diags]}
        (reduce (fn [{:keys [valid-outputs seat-diags]} seat-id]
                  (let [entries (get outputs-by-seat seat-id)]
                    (cond
                      (empty? entries)
                      {:valid-outputs valid-outputs
                       :seat-diags (conj seat-diags {:reason :missing-seat-output :seat seat-id})}

                      (> (count entries) 1)
                      {:valid-outputs valid-outputs
                       :seat-diags (conj seat-diags {:reason :duplicate-seat-output
                                                     :seat seat-id
                                                     :count (count entries)})}

                      :else
                      (let [val-res (validate-round1-seat-output seat-id (first entries))]
                        (if (:ok? val-res)
                          {:valid-outputs (conj valid-outputs (:output val-res))
                           :seat-diags seat-diags}
                          {:valid-outputs valid-outputs
                           :seat-diags (conj seat-diags (:diagnose val-res))})))))
                {:valid-outputs [] :seat-diags []}
                expected-seats)
        all-diags (into (into roster-diags extra-diags) seat-diags)
        {:keys [diagnostics effects]} (summarize-diagnostics all-diags)
        {:keys [records cids prev]} (chain-round-turns rnd blind? (:cid norm-proposal) valid-outputs prev-turn-cid)
        round-body {:round rnd
                    :blind? blind?
                    :proposal-cid (:cid norm-proposal)
                    :episode/values-cid v-cid
                    :status (if (empty? all-diags) :complete :incomplete)
                    :turn-cids cids}
        round-cid (env/sha256-str (pr-str round-body))
        merkle-root (data/compute-rfc6962-merkle-root (into [round-cid] cids))]
    (merge
     {:status (if (empty? all-diags) :complete :incomplete)
      :round rnd
      :blind? blind?
      :episode/values-cid v-cid
      :proposal norm-proposal
      :seat-outputs valid-outputs
      :turn-records records
      :turn-cids cids
      :last-turn-cid prev
      :round-cid round-cid
      :merkle-root merkle-root
      :falsifiers (mapv #(select-keys % [:seat :falsifier]) valid-outputs)
      :objections (vec (filter #(= :objection (:verdict %)) valid-outputs))
      :amendments (vec (filter #(= :amendment (:verdict %)) valid-outputs))
      :no-objections (vec (filter #(= :no-objection (:verdict %)) valid-outputs))
      :diagnostics diagnostics
      :effects effects}
     advisory-invariants)))

;; ---------------------------------------------------------------------------
;; Round-2+ Synthesis, Fixed-Point, :unresolved Exit & Trace v2
;; ---------------------------------------------------------------------------

(defn- build-round-n-envelopes
  [roster proposal prior-objections-by-seat values-cid round prev-outputs]
  (let [norm-prop (normalize-proposal proposal)]
    (mapv (fn [seat-map]
            (let [seat-id (:seat-id seat-map)
                  twin-id (or (:twin-identity seat-map)
                              (seat->twin-identity seat-map values-cid))]
              (merge
               {:episode/values-cid values-cid
                :seat seat-id
                :twin-identity twin-id
                :member-prior-objections (extract-own-prior-objections
                                          seat-id
                                          (when (map? prior-objections-by-seat)
                                            (get prior-objections-by-seat seat-id)))
                :proposal norm-prop
                :round (long round)
                :blind? false
                :peer-outputs (vec prev-outputs)}
               advisory-invariants)))
          roster)))

(defn synthesize-shaped-proposal
  "Purely synthesizes a shaped proposal for the next Stage 1 round from the
   previous round's objections, amendments, and falsifiers."
  [proposal round-result]
  (let [norm (normalize-proposal proposal)
        rnd (:round round-result)
        objs (:objections round-result)
        amends (:amendments round-result)
        falsifiers (:falsifiers round-result)
        summary-lines
        (concat
         [(str "\n\n[Stage 1 Round " rnd " Synthesis: "
               (count objs) " objection(s), "
               (count amends) " amendment(s), "
               (count falsifiers) " prod falsifier(s)]")]
         (map (fn [o]
                (str "- OBJECTION (" (name (:seat o)) "): " (:critique o)))
              objs)
         (map (fn [a]
                (str "- AMENDMENT (" (name (:seat a)) "): " (:critique a)))
              amends)
         (map (fn [{:keys [seat falsifier]}]
                (str "- FALSIFIER (" (name seat) "): " (:failure-mode falsifier)))
              falsifiers))
        new-body (str (:body norm) (str/join "\n" summary-lines))]
    (normalize-proposal (assoc norm :body new-body :cid nil))))

(defn- build-stage1-trace-v2
  [{:keys [values-cid roster initial-proposal final-proposal status rounds-executed rounds]}]
  (let [header (merge
                {:trace/version 2
                 :trace/kind :stage1-twin-pre-shaping
                 :episode/values-cid values-cid
                 :initial-proposal-cid (:cid initial-proposal)
                 :final-proposal-cid (:cid final-proposal)
                 :status status
                 :rounds-executed (long rounds-executed)
                 :roster-cids (mapv (fn [s]
                                      {:seat-id (:seat-id s)
                                       :model-selector (:model-selector s)
                                       :twin-config-cid (get-in s [:twin-identity :twin-config-cid])})
                                    roster)}
                advisory-invariants)
        header-cid (env/sha256-str (pr-str header))
        all-turns (vec (mapcat :turn-records rounds))
        turn-cids (mapv :turn/cid all-turns)
        round-cids (mapv :round-cid rounds)
        leaf-cids (into [header-cid] turn-cids)
        merkle-root (data/compute-rfc6962-merkle-root leaf-cids)]
    {:trace/version 2
     :trace/kind :stage1-twin-pre-shaping
     :episode/values-cid values-cid
     :header (assoc header :header/cid header-cid)
     :header-cid header-cid
     :round-cids round-cids
     :turns all-turns
     :turn-cids turn-cids
     :leaf-cids leaf-cids
     :merkle-root merkle-root}))

(defn run-stage1-pre-shaping
  "Pure Stage 1 Advisory Digital Twin Pre-Shaping runner.

   Options:
   - `:roster` — 5-seat all-frontier roster (defaults to `(canonical-stage1-roster)`)
   - `:proposal` — `{:bip-id :title :cid :body}`
   - `:prior-objections-by-seat` — optional map `{seat-id [prior-obj ...]}`
   - `:values-cid` — defaults to `VALUES-CID`
   - `:max-rounds` — positive integer round limit (defaults to 3)
   - `:seat-runner` — pure function `(fn [envelope] -> seat-output)`
   - `:round1-outputs` — optional pre-collected 5 independent blind Round-1 outputs
     (for live 5-subagent parallel execution).

   Enforces:
   1. Blind Round-1 isolation (`:round 1 :blind? true :peer-outputs []`).
   2. Fail-closed `:falsifier` enforcement with counted `:diagnose` and `:status :incomplete`.
   3. Fixed-point convergence (`:status :fixed-point`) when a round has 0 objections & 0 amendments.
   4. `:status :unresolved` + counted `:diagnose` on round-limit (NEVER silent consent).
   5. Hard `:advisory-only? true`, `:real-consent-required? true`, `:simulated-consent-counts-as-real? false`.
   6. Deterministic RFC 6962 Merkle Trace v2 root."
  [{:keys [roster proposal prior-objections-by-seat values-cid max-rounds seat-runner round1-outputs]}]
  (let [v-cid (or values-cid VALUES-CID)
        effective-roster (or roster (canonical-stage1-roster v-cid))
        limit (long (max 1 (or max-rounds 3)))
        init-prop (normalize-proposal proposal)
        r1-envelopes (build-all-round1-envelopes
                      {:roster effective-roster
                       :proposal init-prop
                       :prior-objections-by-seat prior-objections-by-seat
                       :values-cid v-cid})]
    (loop [rnd 1
           cur-prop init-prop
           prev-outputs []
           prev-turn-cid nil
           rounds-acc []
           diags-acc []]
      (let [envelopes (if (= 1 rnd)
                        r1-envelopes
                        (build-round-n-envelopes effective-roster
                                                 cur-prop
                                                 prior-objections-by-seat
                                                 v-cid
                                                 rnd
                                                 prev-outputs))
            raw-outputs (cond
                          (and (= 1 rnd) (some? round1-outputs))
                          (vec round1-outputs)

                          (fn? seat-runner)
                          (mapv seat-runner envelopes)

                          :else
                          [])
            round-res (assemble-round1 {:roster effective-roster
                                        :seat-outputs raw-outputs
                                        :proposal cur-prop
                                        :values-cid v-cid
                                        :round rnd
                                        :prev-turn-cid prev-turn-cid})
            rounds' (conj rounds-acc round-res)
            diags' (into diags-acc (get-in round-res [:diagnostics :events]))]
        (cond
          ;; Fail-closed on incomplete/invalid round (e.g., missing :falsifier or retired model)
          (not= :complete (:status round-res))
          (let [{:keys [diagnostics effects]} (summarize-diagnostics diags')
                trace-v2 (build-stage1-trace-v2 {:values-cid v-cid
                                                 :roster effective-roster
                                                 :initial-proposal init-prop
                                                 :final-proposal cur-prop
                                                 :status :incomplete
                                                 :rounds-executed rnd
                                                 :rounds rounds'})]
            (merge
             {:status :incomplete
              :rounds-executed rnd
              :episode/values-cid v-cid
              :roster effective-roster
              :initial-proposal init-prop
              :final-proposal cur-prop
              :round1-envelopes r1-envelopes
              :rounds rounds'
              :diagnostics diagnostics
              :effects effects
              :trace trace-v2}
             advisory-invariants))

          ;; Fixed point: zero objections AND zero amendments in a complete round
          (and (empty? (:objections round-res))
               (empty? (:amendments round-res)))
          (let [{:keys [diagnostics effects]} (summarize-diagnostics diags')
                trace-v2 (build-stage1-trace-v2 {:values-cid v-cid
                                                 :roster effective-roster
                                                 :initial-proposal init-prop
                                                 :final-proposal cur-prop
                                                 :status :fixed-point
                                                 :rounds-executed rnd
                                                 :rounds rounds'})]
            (merge
             {:status :fixed-point
              :rounds-executed rnd
              :episode/values-cid v-cid
              :roster effective-roster
              :initial-proposal init-prop
              :final-proposal cur-prop
              :round1-envelopes r1-envelopes
              :rounds rounds'
              :diagnostics diagnostics
              :effects effects
              :trace trace-v2}
             advisory-invariants))

          ;; Round-limit reached with standing objections/amendments -> :unresolved (NEVER silent consent)
          (>= rnd limit)
          (let [unres-diag {:reason :stage1-round-limit-unresolved
                            :round rnd
                            :standing-objections (count (:objections round-res))
                            :standing-amendments (count (:amendments round-res))}
                {:keys [diagnostics effects]} (summarize-diagnostics (conj diags' unres-diag))
                trace-v2 (build-stage1-trace-v2 {:values-cid v-cid
                                                 :roster effective-roster
                                                 :initial-proposal init-prop
                                                 :final-proposal cur-prop
                                                 :status :unresolved
                                                 :rounds-executed rnd
                                                 :rounds rounds'})]
            (merge
             {:status :unresolved
              :rounds-executed rnd
              :episode/values-cid v-cid
              :roster effective-roster
              :initial-proposal init-prop
              :final-proposal cur-prop
              :round1-envelopes r1-envelopes
              :rounds rounds'
              :standing-objections (:objections round-res)
              :standing-amendments (:amendments round-res)
              :diagnostics diagnostics
              :effects effects
              :trace trace-v2}
             advisory-invariants))

          ;; Otherwise synthesize shaped proposal and advance to next round
          :else
          (let [next-prop (synthesize-shaped-proposal cur-prop round-res)]
            (recur (inc rnd)
                   next-prop
                   (:seat-outputs round-res)
                   (:last-turn-cid round-res)
                   rounds'
                   diags')))))))

;; ---------------------------------------------------------------------------
;; Pure Stage 1 Trace v2 Verifier
;; ---------------------------------------------------------------------------

(defn verify-stage1-trace
  "Purely verifies a `run-stage1-pre-shaping` result:
   1. Hard advisory-only flags are intact.
   2. Roster and all turns contain zero retired `gpt-oss-120b` models.
   3. Round-1 envelopes are strictly blind (`:round 1 :blind? true :peer-outputs []`).
   4. Header CID, turn CID hash chain, and RFC 6962 Merkle root match recomputed values."
  [stage1-result]
  (let [trace (:trace stage1-result)
        header (:header trace)
        turns (:turns trace)
        r1-envs (:round1-envelopes stage1-result)]
    (cond
      (not (and (map? stage1-result) (map? trace) (= 2 (:trace/version trace))))
      {:ok? false :error :malformed-stage1-trace}

      (not (and (true? (:advisory-only? stage1-result))
                (true? (:real-consent-required? stage1-result))
                (false? (:simulated-consent-counts-as-real? stage1-result))
                (true? (:advisory-only? header))
                (true? (:real-consent-required? header))
                (false? (:simulated-consent-counts-as-real? header))))
      {:ok? false :error :advisory-invariant-violated}

      (some #(retired-model? (:model-selector %)) (:roster stage1-result))
      {:ok? false :error :retired-model-in-roster}

      (some #(retired-model? (get-in % [:twin-identity :model-selector])) turns)
      {:ok? false :error :retired-model-in-turns}

      (and (seq r1-envs)
           (not-every? #(and (= 1 (:round %))
                             (true? (:blind? %))
                             (= [] (:peer-outputs %)))
                       r1-envs))
      {:ok? false :error :round1-blind-isolation-violated}

      :else
      (let [expected-hdr-cid (env/sha256-str (pr-str (dissoc header :header/cid)))
            chain-check
            (reduce (fn [{:keys [ok? prev cids]} t]
                      (if-not ok?
                        {:ok? false}
                        (let [recomputed (env/sha256-str (pr-str (dissoc t :turn/cid)))]
                          (if (and (= prev (:prev-cid t))
                                   (= recomputed (:turn/cid t)))
                            {:ok? true :prev recomputed :cids (conj cids recomputed)}
                            {:ok? false}))))
                    {:ok? true :prev nil :cids []}
                    turns)
            recomputed-root (when (:ok? chain-check)
                              (data/compute-rfc6962-merkle-root
                               (into [expected-hdr-cid] (:cids chain-check))))]
        (cond
          (not= expected-hdr-cid (:header-cid trace))
          {:ok? false :error :header-cid-mismatch}

          (not (:ok? chain-check))
          {:ok? false :error :turn-hash-chain-tampered}

          (not= recomputed-root (:merkle-root trace))
          {:ok? false :error :merkle-root-mismatch}

          :else
          {:ok? true
           :merkle-root recomputed-root
           :turn-count (count turns)})))))
