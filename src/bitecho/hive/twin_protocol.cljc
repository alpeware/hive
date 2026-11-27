(ns bitecho.hive.twin-protocol
  "Pure Sans-IO Digital Twin Wire Protocol, Prompt Builders & Verdict Parser (`.cljc`).

   Shared by BOTH the in-memory simulation shell and distributed Mealy agents
   running over the live Bitecho devnet so zero prompt formatting, verdict parsing,
   or envelope framing lives in any I/O shell."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:const PROTOCOL-VERSION
  "Canonical wire protocol identifier for distributed Hive digital twin runs."
  "hive-twin-v1")

(def ^:const ENVELOPE-PREFIX
  "Deterministic wire prefix tagging Hive digital twin envelopes inside Bitecho DMs."
  "HIVE-TWIN-V1 ")

(def advisory-invariants
  "Non-negotiable advisory-only flags stamped on every wire envelope."
  {:advisory-only? true
   :real-consent-required? true
   :simulated-consent-counts-as-real? false})

(def valid-envelope-kinds
  "Allowed wire envelope `:kind` values."
  #{:twin/turn-request
    :twin/turn-response
    :twin/amendment-request
    :twin/amendment-response})

;; ---------------------------------------------------------------------------
;; Wire Envelope Codec (Lossless EDN over Bitecho DM / In-Memory Fabric)
;; ---------------------------------------------------------------------------

(defn make-correlation-id
  "Deterministic idempotency key for a dispatched seat turn or amendment."
  [run-id round stage seat generation]
  (str run-id ":r" (long round) ":" (name stage) ":" (name seat) ":g" (long generation)))

(defn encode-envelope
  "Encodes a twin protocol map into a tagged wire string stamped with
   `PROTOCOL-VERSION` and hard advisory-only invariants."
  [envelope]
  (when-not (and (map? envelope) (contains? valid-envelope-kinds (:kind envelope)))
    (throw (ex-info "Invalid twin protocol envelope" {:envelope envelope})))
  (let [stamped (merge envelope
                       {:protocol PROTOCOL-VERSION}
                       advisory-invariants)]
    (str ENVELOPE-PREFIX (pr-str stamped))))

(defn decode-envelope
  "Decodes a tagged wire string back into a validated twin protocol map.
   Returns `nil` (never throws) for non-protocol messages (e.g. pleasantries or chatter)
   so agents can drop them with a counted `:diagnose` and zero ping-pong."
  [raw-text]
  (when (and (string? raw-text) (str/starts-with? raw-text ENVELOPE-PREFIX))
    (try
      (let [parsed (edn/read-string (subs raw-text (count ENVELOPE-PREFIX)))]
        (when (and (map? parsed)
                   (= PROTOCOL-VERSION (:protocol parsed))
                   (contains? valid-envelope-kinds (:kind parsed))
                   (true? (:advisory-only? parsed))
                   (true? (:real-consent-required? parsed))
                   (false? (:simulated-consent-counts-as-real? parsed)))
          parsed))
      (catch #?(:clj Exception :cljs :default) _
        nil))))

;; ---------------------------------------------------------------------------
;; Pure Prompt Builders (Extracted from twin-runner.clj)
;; ---------------------------------------------------------------------------

(defn build-seat-turn-prompt
  "Pure deterministic prompt builder for a D41 seat turn.
   When `blind?` is true (e.g. blind Round 1), peer messages from `:obs/inbox`
   are mechanically excluded (`Peer Outputs Visible: []`)."
  ([values-text twin-prompt-text obs]
   (build-seat-turn-prompt values-text twin-prompt-text obs false))
  ([values-text twin-prompt-text obs blind?]
   (let [stage (:obs/stage obs)
         rnd (:obs/round obs)
         recent (when-not blind?
                  (->> (:obs/inbox obs)
                       (take-last 8)
                       (map (fn [{:keys [from round stage content]}]
                              (str "- [" (name from) " R" round "/" (name stage) "]: " content)))
                       (str/join "\n")))]
     (str "=== SHARED VALUES (Bitecho) ===\n"
          values-text
          "\n\n=== YOUR DIGITAL TWIN PROMPT ===\n"
          twin-prompt-text
          "\n\n=== SIMULATION CONTEXT (ADVISORY ONLY; SIMULATED CONSENT IS NEVER REAL CONSENT) ===\n"
          "Phase: " (name (:obs/phase obs)) " | Round: " rnd " | Stage: " (name stage)
          (when blind? " | BLIND ROUND 1 (Peer Outputs Visible: [])")
          "\n"
          "Current Proposal:\n" (:obs/proposal-text obs) "\n\n"
          (when (and (not blind?) (not (str/blank? recent)))
            (str "Prior Circle Discussion:\n" recent "\n\n"))
          (case stage
            :present "Task (PRESENT): In 2-3 concise sentences, frame or react to the proposal's core intent from your seat. Do not invoke any tools or create files."
            :questions "Task (CLARIFYING QUESTIONS): Ask 1-2 sharp clarifying questions (or say 'No clarifying questions.') in <= 3 sentences. Do not invoke any tools or create files."
            :reactions "Task (REACTION ROUND): Give your brief candid reaction (<= 3 sentences) from your seat. Do not invoke any tools or create files."
            :consent-round "Task (CONSENT ROUND): Start your response on line 1 with EXACTLY ONE of: CONSENT | CONSENT-WITH-ADDENDUM | PARAMOUNT-OBJECTION — followed by 1-3 sentences explaining your verdict (if addendum, list concrete flag F-1; if paramount objection, state concrete harm-to-aim and fix). Do not invoke any tools or create files."
            "Respond concisely in <= 3 sentences.")))))

(defn build-amendment-prompt
  "Pure deterministic prompt builder for the proposer's `:amendment-loop` rewrite."
  [values-text proposer-prompt current-proposal recent-turns]
  (let [feedback (->> recent-turns
                      (map (fn [{:keys [actor-id stage action]}]
                             (str "- [" (name actor-id) "/" (name stage) "]: "
                                  (or (:action/content action) (:action/raw-output action)))))
                      (str/join "\n"))]
    (str "=== SHARED VALUES (Bitecho) ===\n"
         values-text
         "\n\n=== YOUR DIGITAL TWIN PROMPT ===\n"
         proposer-prompt
         "\n\n=== AMENDMENT LOOP (ADVISORY SIMULATION) ===\n"
         "You are rewriting the proposal after Round feedback so it resolves EVERY paramount objection and addendum flag concretely.\n\n"
         "Previous Proposal:\n" current-proposal "\n\n"
         "Circle Objections, Addenda & Reactions:\n" feedback "\n\n"
         "Task: Output ONLY the complete, rewritten, self-contained proposal text (1-2 tight paragraphs, concrete and bounded: directly resolve every paramount objection and addendum flag with exact invariants, failure modes, and boundaries; affirm pure Sans-IO .cljc core + advisory-only boundary + real Stage 2 review gates). Do NOT include meta-commentary or invoke tools.")))

;; ---------------------------------------------------------------------------
;; Pure Consent Verdict Parser
;; ---------------------------------------------------------------------------

(defn parse-consent-verdict
  "Parses a twin's raw consent-round completion into a typed R-1 `:protocol-act`."
  [actor raw-text]
  (let [text (str (or raw-text ""))
        upper (str/upper-case text)]
    (cond
      (re-find #"PARAMOUNT[-_\s]OBJECTION" upper)
      {:action/type :protocol-act
       :action/content text
       :action/protocol {:verb :paramount-objection
                         :harm-to-aim (subs text 0 (min 280 (count text)))
                         :fix "Address objection in amendment round"}
       :action/raw-output text}

      (or (re-find #"CONSENT[-_\s]WITH[-_\s]ADDENDUM" upper)
          (re-find #"CONSENT[-_\s]WITH[-_\s]FLAGS" upper))
      {:action/type :protocol-act
       :action/content text
       :action/protocol {:verb :consent-with-addendum
                         :flags [{:id (str "F-" (name actor))
                                  :text (subs text 0 (min 240 (count text)))
                                  :owner actor
                                  :target-milestone "Run-1"}]}
       :action/raw-output text}

      :else
      {:action/type :protocol-act
       :action/content text
       :action/protocol {:verb :consent}
       :action/raw-output text})))
