(ns bitecho.hive.ingress
  "HIVE-BIP-001 H3: Concrete Bitecho Ingress Driver (`IEventSource`).

   Wraps `bitecho-light-node.cjs wait-event --events-out <path> --cursor <n>`
   with atomic cursor persistence, canonical event normalization into M4 shape,
   and an explicit offer auto-answer policy with counted diagnostics."
  (:require [bitecho.hive.bindings :as bindings]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [mealy.event.source :as event-src]))

;; =============================================================================
;; 1. Cursor Persistence
;; =============================================================================

(defn read-cursor
  "Reads persisted integer cursor from `cursor-file`, returning 0 when absent."
  [cursor-file]
  (let [f (io/file cursor-file)]
    (if (and (.exists f) (pos? (.length f)))
      (try
        (let [v (edn/read-string (str/trim (slurp f)))]
          (cond
            (integer? v) (long v)
            (and (map? v) (integer? (:cursor v))) (long (:cursor v))
            :else 0))
        (catch Exception _ 0))
      0)))

(defn write-cursor!
  "Atomically persists `cursor` to `cursor-file` via temp-file rename."
  [cursor-file cursor]
  (let [f (io/file cursor-file)
        parent (.getParentFile f)]
    (when parent (.mkdirs parent))
    (let [tmp (io/file (or parent (io/file ".")) (str (.getName f) ".tmp"))]
      (spit tmp (pr-str {:cursor (long cursor) :updated-ms (System/currentTimeMillis)}))
      (.renameTo tmp f))
    (long cursor)))

;; =============================================================================
;; 2. Event Normalization & Offer Auto-Answer Policy (Pure)
;; =============================================================================

(defn normalize-bitecho-event
  "Pure function converting a raw Bitecho light-node event map into M4's
   canonical `{:id :kind :from :subnet-id :text :raw}` shape (or nil if non-message)."
  [raw-ev]
  (when (map? raw-ev)
    (let [etype (:event raw-ev)
          eid (or (:msg_id raw-ev) (str etype ":" (:event_id raw-ev)))]
      (case etype
        "broadcast-received"
        {:id eid
         :kind :broadcast
         :from (:from raw-ev)
         :subnet-id (or (:subnet_id raw-ev) "hive/poc")
         :text (str (or (:text raw-ev) ""))
         :raw raw-ev}

        "message-received"
        {:id eid
         :kind :dm
         :from (:from raw-ev)
         :subnet-id (:subnet_id raw-ev)
         :text (str (or (:text raw-ev) ""))
         :raw raw-ev}

        "p2p-offer-received"
        {:id eid
         :kind :p2p-offer
         :from (:from raw-ev)
         :session-id (:session_id raw-ev)
         :raw raw-ev}

        nil))))

(defn evaluate-offer-policy
  "Pure function evaluating a `p2p-offer-received` event against `policy`
   (`:all`, `:roster-only`, or `:none`). Never drops silently: every decision
   carries a structured `:diagnose` record."
  [policy roster-set raw-ev]
  (let [sid (or (:session_id raw-ev) (:session-id raw-ev))
        from (:from raw-ev)
        allow? (case (keyword (or policy :all))
                 :all (boolean (seq (str sid)))
                 :roster-only (boolean (and (seq (str sid)) (contains? (set roster-set) from)))
                 :none false
                 false)]
    (if allow?
      {:action :answer
       :session-id sid
       :from from
       :diagnose {:effect :diagnose :reason :p2p-offer-answered :session-id sid :from from}}
      {:action :ignore
       :session-id sid
       :from from
       :diagnose {:effect :diagnose :reason :p2p-offer-ignored :policy policy :session-id sid :from from}})))

;; =============================================================================
;; 3. Live Bitecho IEventSource
;; =============================================================================

(defn make-bitecho-ingress
  "Creates a live `mealy.event.source/IEventSource` over `bitecho-light-node.cjs wait-event`,
   persisting the cursor to `<state-dir>/ingress-cursor.edn` and applying `offer-policy`."
  [{:keys [node-bin light-node-js state-dir events-file cursor-file offer-policy roster-set ctl-handler exec-fn]}]
  (let [ev-file (str (or events-file (str state-dir "/events.jsonl")))
        cur-file (io/file (or cursor-file (str state-dir "/ingress-cursor.edn")))
        exec (or exec-fn bindings/default-exec-fn)
        policy (or offer-policy :all)
        roster (set (or roster-set #{}))]
    (reify event-src/IEventSource
      (poll-event! [_ cursor timeout-ms]
        (let [cur (long (or cursor (read-cursor cur-file)))
              cmd (cond-> [(or node-bin bindings/DEFAULT-NODE-BIN)
                           (or light-node-js bindings/DEFAULT-LIGHT-NODE-JS)
                           "wait-event"]
                    state-dir (into ["--state-dir" (str state-dir)])
                    true (into ["--events-out" ev-file
                                "--after-ts" (str cur)
                                "--timeout-ms" (str (long (or timeout-ms 60000)))]))
              {:keys [exit out]} (exec cmd)
              parsed (when (zero? (long (or exit -1)))
                       (try (json/read-str (str/trim (or out "")) :key-fn keyword)
                            (catch Exception _ nil)))
              raw-ev (if (map? (:event parsed)) (:event parsed) parsed)
              next-cur (long (max cur (long (or (:timestamp_ms raw-ev)
                                                (:next_cursor parsed)
                                                (:cursor parsed)
                                                (:event_id raw-ev)
                                                cur))))]
          (when (> next-cur cur)
            (write-cursor! cur-file next-cur))
          (let [norm (normalize-bitecho-event raw-ev)]
            (if (and (= :p2p-offer (:kind norm)) ctl-handler)
              (let [decision (evaluate-offer-policy policy roster raw-ev)]
                (when (= :answer (:action decision))
                  (ctl-handler {"cmd" "p2p-answer" "session_id" (:session-id decision)}))
                {:event nil :cursor next-cur :diagnose (:diagnose decision)})
              {:event norm :cursor next-cur}))))
      (close-source! [_] nil))))
