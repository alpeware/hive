(ns bitecho.hive.transport
  "HIVE-BIP-001 H6: Transport as a Parameter (:in-memory vs :live).

   Allows the EXACT same Mealy declarative agent graph definition to run either
   against a pure in-memory VOPR simulation fabric (`:transport :in-memory`) or
   against a live Bitecho light-node daemon (`:transport :live`), switched purely
   by configuration parameter.

   100% cross-platform .cljc (JVM, Node.js, Browser)."
  (:require [mealy.event.source :as event-src]))

;; =============================================================================
;; 1. Pure In-Memory Simulation Fabric (VOPR / Multi-Agent Sim)
;; =============================================================================

(defn make-in-memory-fabric
  "Creates a shared in-memory message + balance fabric for simulating multiple
   Mealy agents over `:net/bitecho` + `IEventSource` with zero network I/O."
  ([]
   (make-in-memory-fabric {}))
  ([opts]
   (atom {:balances (into {} (or (:balances opts) {}))
          :events []
          :sent []
          :seq 0})))

(defn fabric-deliver-dm!
  "Injects a direct message into the in-memory fabric addressed to `to-pubkey`."
  ([fabric from-pubkey to-pubkey text]
   (fabric-deliver-dm! fabric from-pubkey to-pubkey text nil))
  ([fabric from-pubkey to-pubkey text msg-id]
   (let [updated (swap! fabric
                        (fn [st]
                          (let [next-seq (inc (:seq st))
                                eid (or msg-id (str "sim-dm-" next-seq))
                                ev {:event_id next-seq
                                    :id eid
                                    :kind :dm
                                    :from from-pubkey
                                    :to to-pubkey
                                    :text (str text)}]
                            (-> st
                                (assoc :seq next-seq)
                                (update :events conj ev)))))]
     (last (:events updated)))))

(defn fabric-deliver-broadcast!
  "Injects a subnet broadcast into the in-memory fabric."
  ([fabric from-pubkey subnet-id text]
   (fabric-deliver-broadcast! fabric from-pubkey subnet-id text nil))
  ([fabric from-pubkey subnet-id text msg-id]
   (let [updated (swap! fabric
                        (fn [st]
                          (let [next-seq (inc (:seq st))
                                eid (or msg-id (str "sim-bcast-" next-seq))
                                ev {:event_id next-seq
                                    :id eid
                                    :kind :broadcast
                                    :from from-pubkey
                                    :subnet-id (str subnet-id)
                                    :text (str text)}]
                            (-> st
                                (assoc :seq next-seq)
                                (update :events conj ev)))))]
     (last (:events updated)))))

(defn make-in-memory-source
  "Constructs an `IEventSource` reading events for `self-pubkey` / `subnet-id`
   from `fabric` strictly after `cursor`."
  [fabric self-pubkey subnet-id]
  (reify event-src/IEventSource
    (poll-event! [_ cursor _timeout-ms]
      (let [cur (long (or cursor 0))
            evs (:events @fabric)
            matching (first (filter (fn [ev]
                                      (and (> (long (:event_id ev)) cur)
                                           (or (and (= :dm (:kind ev)) (= (:to ev) self-pubkey))
                                               (and (= :broadcast (:kind ev))
                                                    (or (nil? subnet-id)
                                                        (= (:subnet-id ev) subnet-id))))))
                                    evs))]
        (if matching
          {:event matching :cursor (long (:event_id matching))}
          {:event nil :cursor cur})))
    (close-source! [_] nil)))

(defn make-in-memory-bitecho-handler
  "Constructs a `:net/bitecho` `:handler-fn` backed by `fabric`."
  [fabric self-pubkey subnet-id]
  (fn [cmd-map]
    (let [verb (get cmd-map "cmd")]
      (case verb
        "status"
        (let [bal (long (get-in @fabric [:balances self-pubkey] 0))]
          {:ok true
           :pubkey self-pubkey
           :account {:balance bal :spendable bal}
           :balance bal
           :subnet_id subnet-id})

        "broadcast"
        (let [sid (or (get cmd-map "subnet_id") subnet-id "hive/poc")
              msg (str (or (get cmd-map "message") ""))
              ev (fabric-deliver-broadcast! fabric self-pubkey sid msg)]
          (swap! fabric update :sent conj {:cmd "broadcast" :from self-pubkey :subnet-id sid :message msg :msg-id (:id ev)})
          {:ok true :msg_id (:id ev) :subnet_id sid :burn 0 :zero_burn true})

        "send"
        (let [peer (str (or (get cmd-map "peer") ""))
              msg (str (or (get cmd-map "message") ""))
              ev (fabric-deliver-dm! fabric self-pubkey peer msg)]
          (swap! fabric update :sent conj {:cmd "send" :from self-pubkey :peer peer :message msg :msg-id (:id ev)})
          {:ok true :msg_id (:id ev) :peer peer :burn 0 :zero_burn true})

        {:ok false :error (str "unsupported-sim-verb:" verb)}))))

;; =============================================================================
;; 2. Parameterized Transport Builder (:in-memory vs :live)
;; =============================================================================

(defn build-agent-transport
  "Constructs `{:transport <kw> :event-source <IEventSource> :bitecho-handler <fn> :balance-fn <fn>}`
   parameterized by `:transport` (`:in-memory` or `:live`).
   For `:live`, expects `:live-factory` (injected by JVM shell `bitecho.hive.agent-runner`)
   so this namespace remains 100% pure cross-platform `.cljc`."
  [opts]
  (let [mode (keyword (or (:transport opts) :in-memory))]
    (case mode
      (:in-memory :sim)
      (let [fabric (or (:fabric opts) (make-in-memory-fabric {:balances {(:self-pubkey opts) (long (or (:initial-balance opts) 25000))}}))
            self-pk (str (or (:self-pubkey opts) "sim-agent"))
            sid (or (:subnet-id opts) "hive/poc")
            handler (make-in-memory-bitecho-handler fabric self-pk sid)
            source (make-in-memory-source fabric self-pk sid)]
        {:transport :in-memory
         :fabric fabric
         :event-source source
         :bitecho-handler handler
         :balance-fn (fn [] (long (get-in @fabric [:balances self-pk] 0)))})

      :live
      (if-let [factory (:live-factory opts)]
        (factory opts)
        (throw (ex-info "Live transport requires :live-factory in build-agent-transport opts"
                        {:transport mode})))

      (throw (ex-info (str "Unsupported transport mode: " mode)
                      {:transport mode :supported #{:in-memory :sim :live}})))))
