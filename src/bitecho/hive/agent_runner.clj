(ns bitecho.hive.agent-runner
  "HIVE-BIP-001 H1: Canonical One-Liner Agent Runner.

   Composes Mealy M1 (event-log continuation), M2 (`IIntelligencePort`),
   M4 (`IEventSource` + pure middleware), Hive H2 (concrete `:net/bitecho` &
   `:compute/einsum` / `:compute/agy` bindings), Hive H3 (Bitecho ingress driver),
   and Hive H6 (`:transport :in-memory | :live` parameter) into one runnable CLI."
  (:gen-class)
  (:require [bitecho.hive.bindings :as bindings]
            [bitecho.hive.ingress :as ingress]
            [bitecho.hive.transport :as transport]
            [bitecho.hive.twin-coordinator :as twin-coord]
            [clojure.string :as str]
            [mealy.agent.core :as agent-core]
            [mealy.event.source :as event-src]
            [mealy.graph.binder :as binder]
            [mealy.graph.runtime :as runtime]
            [mealy.intelligence.port :as intel-port]))

;; =============================================================================
;; 1. CLI Flag Inventory & Parsing (AGENTS.md Rule 6)
;; =============================================================================

(def cli-flag-inventory
  "Authoritative CLI flag inventory verified by automated flag-inventory tests."
  ["--name"
   "--mode"
   "--seat"
   "--coordinator-pubkey"
   "--max-dm-per-peer"
   "--transport"
   "--backend"
   "--model"
   "--einsum-backend"
   "--node-bin"
   "--light-node-js"
   "--offer-policy"
   "--state-dir"
   "--subnet-id"
   "--min-balance"
   "--max-turns"
   "--no-intro"
   "--help"])

(defn help-text
  "Returns CLI usage documentation listing every flag in `cli-flag-inventory`."
  []
  (str "Bitecho Hive Agent Runner (HIVE-BIP-001 H1)\n"
       "Usage: clojure -M:agent [options]\n\n"
       "Options:\n"
       "  --name <name>                    Agent petname / identifier (default: hive-agent)\n"
       "  --mode <default|twin-seat>       Agent graph mode: default or twin-seat (D41 Sans-IO responder)\n"
       "  --seat <seat-id>                 Digital twin seat ID when --mode twin-seat (simon|axiom|nexus|vector|lumen)\n"
       "  --coordinator-pubkey <hex>       Optional coordinator pubkey allowlist for --mode twin-seat\n"
       "  --max-dm-per-peer <n>            M4 DM loop-guard cap per peer per window (default: 8; 0 disables)\n"
       "  --transport <sim|live>           Transport mode: sim (in-memory) or live (Bitecho daemon)\n"
       "  --backend <agy|einsum|mock>      Intelligence backend\n"
       "  --model <model-id-or-path>       Model identifier or local weights path\n"
       "  --einsum-backend <backend>       clj-einsum UOp backend (default: uop-intel)\n"
       "  --node-bin <path>                Node.js binary override (env: BITECHO_NODE_BIN)\n"
       "  --light-node-js <path>           bitecho-light-node.cjs path (env: BITECHO_LIGHT_NODE_JS)\n"
       "  --offer-policy <roster-only|all|none> Inbound P2P offer policy (default: roster-only)\n"
       "  --state-dir <path>               Isolated light-node state directory (required for live)\n"
       "  --subnet-id <subnet>             Target subnet (default: hive/poc)\n"
       "  --min-balance <ech>              Minimum ECH balance floor guard (default: 2000)\n"
       "  --max-turns <n>                  Optional cap on turns before exiting\n"
       "  --no-intro                       Skip initial subnet introduction turn\n"
       "  --help                           Print this help message\n"))

(defn parse-cli-args
  "Parses CLI arguments strictly against `cli-flag-inventory`. Unknown flags throw loudly."
  [args]
  (loop [opts {:agent-name "hive-agent"
               :mode :default
               :seat nil
               :coordinator-pubkey nil
               :max-dm-per-peer-window 8
               :transport :live
               :backend :agy
               :model "claude-sonnet-5-5-medium"
               :einsum-backend :uop-intel
               :node-bin nil
               :light-node-js nil
               :offer-policy :roster-only
               :state-dir nil
               :subnet-id "hive/poc"
               :min-balance bindings/DEFAULT-MIN-BALANCE
               :max-turns nil
               :intro? true
               :help? false}
         rem args]
    (if (empty? rem)
      opts
      (let [[k v & more] rem]
        (case k
          "--name" (recur (assoc opts :agent-name v) more)
          "--mode" (recur (assoc opts :mode (case (str/lower-case (str v))
                                              "default" :default
                                              "twin-seat" :twin-seat
                                              (throw (ex-info (str "Invalid --mode: " v) {:value v}))))
                          more)
          "--seat" (recur (assoc opts :seat (keyword (str/lower-case (str v)))) more)
          "--coordinator-pubkey" (recur (assoc opts :coordinator-pubkey (str v)) more)
          "--max-dm-per-peer" (let [n (Long/parseLong (str v))]
                                (recur (assoc opts :max-dm-per-peer-window (when (pos? n) n)) more))
          "--transport" (recur (assoc opts :transport (case (str/lower-case (str v))
                                                        ("sim" "in-memory") :in-memory
                                                        "live" :live
                                                        (throw (ex-info (str "Invalid --transport: " v) {:value v}))))
                               more)
          "--backend" (recur (assoc opts :backend (keyword v)) more)
          "--model" (recur (assoc opts :model v) more)
          "--einsum-backend" (recur (assoc opts :einsum-backend (keyword v)) more)
          "--node-bin" (recur (assoc opts :node-bin v) more)
          "--light-node-js" (recur (assoc opts :light-node-js v) more)
          "--offer-policy" (recur (assoc opts :offer-policy (case (str/lower-case (str v))
                                                              "roster-only" :roster-only
                                                              "all" :all
                                                              "none" :none
                                                              (throw (ex-info (str "Invalid --offer-policy: " v) {:value v}))))
                                  more)
          "--state-dir" (recur (assoc opts :state-dir v) more)
          "--subnet-id" (recur (assoc opts :subnet-id v) more)
          "--min-balance" (recur (assoc opts :min-balance (Long/parseLong (str v))) more)
          "--max-turns" (recur (assoc opts :max-turns (Long/parseLong (str v))) more)
          "--no-intro" (recur (assoc opts :intro? false) (rest rem))
          "--help" (recur (assoc opts :help? true) (rest rem))
          (throw (ex-info (str "Unknown flag: " k) {:flag k :inventory cli-flag-inventory})))))))

;; =============================================================================
;; 2. Parameterized Mealy Agent Graph (Transport-Agnostic!)
;; =============================================================================

(defn- clean-reply
  [agent-name raw-text]
  (let [stripped (-> (or raw-text "")
                     agent-core/strip-thinking-trace
                     (str/replace #"<turn\|>.*" "")
                     (str/replace #"<eos>.*" "")
                     str/trim)
        body (if (str/blank? stripped) (str "Hello from " agent-name) stripped)]
    (if (str/starts-with? body (str "[" agent-name "]"))
      body
      (str "[" agent-name "] " body))))

(defn- format-prompt
  [{:keys [agent-name subnet-id]} ev]
  (case (:kind ev)
    :intro
    (str "You are " agent-name " on Bitecho subnet " subnet-id ". Introduce yourself in 1-2 plain sentences.")

    :dm
    (str "You are " agent-name ". Direct message from " (:from ev) ": \"" (:text ev) "\". Reply in 1-2 plain sentences.")

    :broadcast
    (str "You are " agent-name " on " (or (:subnet-id ev) subnet-id) ". Broadcast from " (:from ev) ": \"" (:text ev) "\". Reply in 1-2 plain sentences.")

    (str "Event: " (pr-str ev))))

(defn coordinator-step
  "Pure Mealy coordinator step for the Hive agent runner."
  [state in-port payload]
  (case in-port
    :incoming
    [(assoc state :pending-event payload :turn (inc (:turn state 0)))
     {:llm-req [{:prompt (format-prompt state payload)}]}]

    :model-reply
    (let [reply-text (clean-reply (:agent-name state) (:text payload))
          ev (:pending-event state)]
      (if (= :dm (:kind ev))
        [(assoc state :last-reply reply-text)
         {:net-send [(cond-> {:peer (:from ev)
                              :message reply-text
                              :min-balance (:min-balance state)}
                       (:session-id ev) (assoc :session-id (:session-id ev)))]}]
        [(assoc state :last-reply reply-text)
         {:net-broadcast [{:subnet-id (or (:subnet-id ev) (:subnet-id state))
                           :message reply-text
                           :min-balance (:min-balance state)}]}]))

    :net-reply
    (if (and (map? payload) (false? (:ok payload)))
      [(assoc state :pending-event nil)
       {:error [{:reason :net-command-failed :detail payload :turn (:turn state)}]}]
      [(assoc state :pending-event nil)
       {:done [{:reply (:last-reply state) :net-result payload :turn (:turn state)}]}])

    :net-rejected
    [(assoc state :pending-event nil)
     {:error [{:reason :budget-floor-rejected :detail payload :turn (:turn state)}]}]))

(defn hive-agent-graph
  "Declarative Mealy Hiccup graph requiring M2 `:intelligence/port` and `:net/bitecho`."
  [{:keys [agent-name subnet-id min-balance]}]
  [:agent {:id (keyword agent-name)
           :ins [:incoming-in]
           :outs [:reply-out :error-out]
           :requires {:llm {:type :intelligence/port}
                      :net {:type :net/bitecho}}}
   [:mealy {:id :coordinator
            :init-state {:agent-name agent-name
                         :subnet-id (or subnet-id "hive/poc")
                         :min-balance (long (or min-balance bindings/DEFAULT-MIN-BALANCE))
                         :turn 0
                         :pending-event nil
                         :last-reply nil}
            :ins [:incoming :model-reply :net-reply :net-rejected]
            :outs [:llm-req :net-send :net-broadcast :done :error]
            :step coordinator-step}]
   [:conn [:agent/in :incoming-in] [:coordinator :incoming]]
   [:conn [:coordinator :llm-req] [:cap/llm :generate]]
   [:conn [:cap/llm :reply] [:coordinator :model-reply]]
   [:conn [:coordinator :net-send] [:cap/net :send]]
   [:conn [:coordinator :net-broadcast] [:cap/net :broadcast]]
   [:conn [:cap/net :reply] [:coordinator :net-reply]]
   [:conn [:cap/net :rejected] [:coordinator :net-rejected]]
   [:conn [:coordinator :done] [:agent/out :reply-out]]
   [:conn [:coordinator :error] [:agent/out :error-out]]])

;; =============================================================================
;; 3. Runtime Initialization & Turn Execution (M1 + M2 + M4 + H2 + H3 + H6)
;; =============================================================================

(defn init-agent-runtime!
  "Binds `hive-agent-graph` (or `twin-seat-graph` when `:mode :twin-seat`) onto a
   parameterized device (`:in-memory` or `:live`), initializes M4 middleware state,
   and returns a runnable agent context."
  [{:keys [agent-name mode seat coordinator-pubkey self-pubkey subnet-id min-balance transport-bundle intelligence-port
           allowed-broadcast-senders max-per-window window-ms max-dm-per-peer-window]}]
  (let [sid (or subnet-id "hive/poc")
        min-bal (long (or min-balance bindings/DEFAULT-MIN-BALANCE))
        bal (long (if-let [bf (:balance-fn transport-bundle)] (bf) 25000))
        graph-ast (if (= :twin-seat mode)
                    (twin-coord/twin-seat-graph {:seat-id (or seat (keyword agent-name))
                                                 :agent-name agent-name
                                                 :subnet-id sid
                                                 :min-balance min-bal
                                                 :coordinator-pubkey coordinator-pubkey})
                    (hive-agent-graph {:agent-name agent-name :subnet-id sid :min-balance min-bal}))
        dev-ast [:device {:id (keyword (str agent-name "-device"))}
                 [:cap {:id :storage-cas :type :storage/cas}]
                 [:cap {:id :llm-port
                        :type :intelligence/port
                        :port intelligence-port}]
                 [:cap {:id :bitecho-net
                        :type :net/bitecho
                        :subnet-id sid
                        :min-balance min-bal
                        :balance bal
                        :handler-fn (:bitecho-handler transport-bundle)}]]
        dev-inst (binder/compile-device dev-ast)
        graph-inst (binder/bind-agent graph-ast dev-inst {})
        mw-state (event-src/init-middleware-state
                  (cond-> {:self-id self-pubkey
                           :allowed-broadcast-senders (or allowed-broadcast-senders #{})
                           :mention-tokens [agent-name]
                           :max-per-window (or max-per-window 10)
                           :window-ms (or window-ms 300000)}
                    (some? max-dm-per-peer-window)
                    (assoc :max-dm-per-peer-window max-dm-per-peer-window)))
        initial-cursor (long (if-let [rf (:read-cursor-fn transport-bundle)]
                               (rf)
                               0))]
    {:agent-name agent-name
     :self-pubkey self-pubkey
     :subnet-id sid
     :device-inst dev-inst
     :graph-inst graph-inst
     :transport-bundle transport-bundle
     :middleware-state mw-state
     :cursor initial-cursor
     :diagnostics-log []
     :all-event-log []}))

(defn step-agent-turn!
  "Executes one incoming event through M1 `continue-graph` + `run-graph!`,
   extending `:all-event-log` continuously without breaking the SHA-256 chain."
  [agent-ctx incoming-ev]
  (let [g0 (:graph-inst agent-ctx)
        prepared (if (zero? (long (:tx g0 0)))
                   g0
                   (runtime/continue-graph g0))
        executed (-> prepared
                     (runtime/enqueue-input :incoming-in incoming-ev)
                     runtime/run-graph!)
        seg (:event-log executed)]
    (assoc agent-ctx
           :graph-inst executed
           :all-event-log (into (:all-event-log agent-ctx) seg))))

(defn- commit-cursor-if-advanced!
  [transport-bundle prev-cursor next-cursor]
  (when (and (> (long next-cursor) (long prev-cursor))
             (ifn? (:commit-cursor-fn transport-bundle)))
    ((:commit-cursor-fn transport-bundle) (long next-cursor))))

(defn drain-agent-events!
  "Polls `IEventSource`, applies M4 middleware, executes accepted events via
   `step-agent-turn!`, commits cursor AFTER processing completes (F3), and
   records every ingress/middleware `:diagnose` in `:diagnostics-log` (F8)."
  ([agent-ctx]
   (drain-agent-events! agent-ctx {}))
  ([agent-ctx {:keys [max-events timeout-ms base-ts] :or {max-events 100 timeout-ms 0}}]
   (let [tx-bundle (:transport-bundle agent-ctx)
         source (:event-source tx-bundle)]
     (loop [ctx agent-ctx
            n 0]
       (if (>= n (long max-events))
         ctx
         (let [prev-cursor (:cursor ctx)
               {:keys [event cursor diagnose]} (event-src/poll-event! source prev-cursor timeout-ms)
               ctx-diag (cond-> ctx
                          diagnose (update :diagnostics-log conj diagnose))]
           (if-not event
             (do
               (commit-cursor-if-advanced! tx-bundle prev-cursor cursor)
               (assoc ctx-diag :cursor cursor))
             (let [now-ms (if base-ts (+ (long base-ts) (* n 1000)) (System/currentTimeMillis))
                   [mw' verdict] (event-src/apply-middleware (:middleware-state ctx-diag) event now-ms)
                   ctx-mw (cond-> (assoc ctx-diag :middleware-state mw')
                            (:diagnose verdict) (update :diagnostics-log conj (:diagnose verdict)))
                   ctx-after (if (= :accept (:action verdict))
                               (step-agent-turn! ctx-mw event)
                               ctx-mw)]
               (commit-cursor-if-advanced! tx-bundle prev-cursor cursor)
               (recur (assoc ctx-after :cursor cursor) (inc n))))))))))

(defn- make-live-transport-factory
  [opts]
  (let [cursor-file (str (:state-dir opts) "/ingress-cursor.edn")
        ctl-handler (bindings/make-bitecho-ctl-handler {:state-dir (:state-dir opts)
                                                        :node-bin (:node-bin opts)
                                                        :light-node-js (:light-node-js opts)})
        source (ingress/make-bitecho-ingress {:state-dir (:state-dir opts)
                                              :node-bin (:node-bin opts)
                                              :light-node-js (:light-node-js opts)
                                              :cursor-file cursor-file
                                              :ctl-handler ctl-handler
                                              :offer-policy (or (:offer-policy opts) :roster-only)
                                              :roster-set (:allowed-broadcast-senders opts)})]
    {:transport :live
     :event-source source
     :bitecho-handler ctl-handler
     :read-cursor-fn (fn [] (ingress/read-cursor cursor-file))
     :commit-cursor-fn (fn [c] (ingress/write-cursor! cursor-file c))
     :balance-fn (fn []
                   (let [st (ctl-handler {"cmd" "status"})]
                     (long (or (get-in st [:account :spendable])
                               (get-in st [:account :balance])
                               0))))}))

(defn- build-intelligence-port
  [opts]
  (case (:backend opts)
    :agy (bindings/make-agy-port {:model (:model opts) :working-dir (or (:state-dir opts) ".")})
    :einsum (bindings/make-einsum-port {:model (:model opts) :einsum-backend (:einsum-backend opts)})
    :mock (reify intel-port/IIntelligencePort
            (start-session! [this _] this)
            (generate! [_ req] {:text (str "mock-reply:" (:prompt req))})
            (stop-session! [_] nil))
    (throw (ex-info (str "Unsupported --backend: " (:backend opts)) {:backend (:backend opts)}))))

(defn -main
  "Canonical one-liner entrypoint (`clojure -M:agent ...`)."
  [& args]
  (let [opts (parse-cli-args args)]
    (if (:help? opts)
      (println (help-text))
      (let [tx (transport/build-agent-transport
                (assoc opts :live-factory make-live-transport-factory))
            self-pk (if (= :live (:transport tx))
                      (:pubkey ((:bitecho-handler tx) {"cmd" "status"}))
                      (str "sim-" (:agent-name opts)))
            port (build-intelligence-port opts)
            ctx0 (init-agent-runtime! (assoc opts
                                             :self-pubkey self-pk
                                             :transport-bundle tx
                                             :intelligence-port port))
            ctx1 (if (:intro? opts)
                   (step-agent-turn! ctx0 {:kind :intro :subnet-id (:subnet-id opts)})
                   ctx0)]
        (try
          (if (= :in-memory (:transport tx))
            (let [final-ctx (drain-agent-events! ctx1 {:max-events (or (:max-turns opts) 10)})]
              (println "Simulation run complete. Chain tip:" (runtime/event-log-tip (:graph-inst final-ctx))))
            (loop [ctx ctx1
                   turns 0]
              (when (or (nil? (:max-turns opts)) (< turns (long (:max-turns opts))))
                (let [next-ctx (drain-agent-events! ctx {:max-events 1 :timeout-ms 60000})]
                  (recur next-ctx (inc turns))))))
          (finally
            (binder/stop-device! (:device-inst ctx1))
            (event-src/close-source! (:event-source tx))))))))
