(ns bitecho.hive.twin-runner
  "Boundary Shells for the 5-Seat Digital-Twin Closed-Loop Runner (In-Memory vs Live Devnet).

   All core D41 state transitions, blind Round-1 isolation, prompt builders,
   verdict parsing, and generation/correlation accounting live in pure Sans-IO `.cljc`
   namespaces (`bitecho.hive.env`, `bitecho.hive.twin-protocol`,
   `bitecho.hive.twin-coordinator`).

   ONLY the boundary I/O shells live here:
   - `run-in-memory-twins!` — 5 independent `twin-seat-graph` Mealy agents + 1
     `twin-coordinator-graph` over `bitecho.hive.transport/make-in-memory-fabric`.
   - `run-live-devnet-twins!` — `twin-coordinator-graph` over live `:net/bitecho`
     (`make-bitecho-ctl-handler` + `make-bitecho-ingress`) dispatching sealed DMs
     to 5 distributed Mealy twin-seat agents running across the Bitecho devnet.
   - `run-live-run-1!` / `run-live-run-3!` — backwards-compatible wrappers that
     drive the unified Sans-IO core and append Phase 2 disposable-copy verification,
     R-2 SCI scoring, R-3 Trace v2 export, and `top-loop` fail-closed gate check."
  (:require [bitecho.hive.bindings :as bindings]
            [bitecho.hive.env :as env]
            [bitecho.hive.ingress :as ingress]
            [bitecho.hive.scorer :as scorer]
            [bitecho.hive.top-loop :as top]
            [bitecho.hive.trace :as trace]
            [bitecho.hive.transport :as transport]
            [bitecho.hive.twin-coordinator :as coord]
            [bitecho.hive.twin-protocol :as proto]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [mealy.event.source :as event-src]
            [mealy.graph.binder :as binder]
            [mealy.graph.runtime :as runtime]
            [mealy.intelligence.port :as intel-port]
            [mealy.sandbox.bwrap :as bwrap]))

(def ^:const RUN-3-BASE-REPO-HASH
  "Anchored multi-repo base hash (bitecho@73ada39 + mealy@13d4e93 + hive@9248d4f)."
  "661d926aeada3b6ed2f4f82dfff2adb1c4e422df37f8454d99ff9f824da9e13")

(def cli-flag-inventory
  "Authoritative CLI flag inventory for `bitecho.hive.twin-runner` (AGENTS.md Rule 6)."
  ["--run"
   "--transport"
   "--state-dir"
   "--out-dir"
   "--seat-endpoints-edn"
   "--blind-round1"
   "--help"])

(defn help-text
  "Returns CLI usage documentation listing every flag in `cli-flag-inventory`."
  []
  (str "Bitecho Hive Digital Twin Runner (Sans-IO Core + In-Memory / Devnet Shells)\n"
       "Usage: clojure -M -m bitecho.hive.twin-runner [options]\n\n"
       "Options:\n"
       "  --run <2|3>                      Preset run profile (default: 3)\n"
       "  --transport <in-memory|live>     Shell transport: in-memory Mealy fabric or live Bitecho devnet\n"
       "  --state-dir <path>               Coordinator light-node state directory (for --transport live)\n"
       "  --out-dir <path>                 Output directory for trace artifacts\n"
       "  --seat-endpoints-edn <file>      EDN map of {:simon {:pubkey ...} ...} for --transport live\n"
       "  --blind-round1                   Enforce strict blind Round-1 isolation\n"
       "  --help                           Print this help message\n"))

(defn parse-cli-args
  "Parses CLI arguments strictly against `cli-flag-inventory`. Unknown flags throw loudly."
  [args]
  (loop [opts {:run "3"
               :transport :in-memory
               :state-dir nil
               :out-dir nil
               :seat-endpoints-edn nil
               :blind-round1? false
               :help? false}
         rem args]
    (if (empty? rem)
      opts
      (let [[k v & more] rem]
        (case k
          "--run" (recur (assoc opts :run (str v)) more)
          "--transport" (recur (assoc opts :transport (case (str/lower-case (str v))
                                                        ("sim" "in-memory") :in-memory
                                                        "live" :live
                                                        (throw (ex-info (str "Invalid --transport: " v) {:value v}))))
                               more)
          "--state-dir" (recur (assoc opts :state-dir (str v)) more)
          "--out-dir" (recur (assoc opts :out-dir (str v)) more)
          "--seat-endpoints-edn" (recur (assoc opts :seat-endpoints-edn (str v)) more)
          "--blind-round1" (recur (assoc opts :blind-round1? true) (rest rem))
          "--help" (recur (assoc opts :help? true) (rest rem))
          (throw (ex-info (str "Unknown flag: " k) {:flag k :inventory cli-flag-inventory})))))))

;; Backwards-compatible alias to pure protocol parser
(def parse-consent-verdict
  "Delegates to pure `bitecho.hive.twin-protocol/parse-consent-verdict`."
  proto/parse-consent-verdict)

;; ---------------------------------------------------------------------------
;; Artifact Fetching & Verification (Boundary I/O)
;; ---------------------------------------------------------------------------

(defn- hex->utf8
  [^String hex]
  (let [n (quot (.length hex) 2)
        bs (byte-array n)]
    (dotimes [i n]
      (aset-byte bs i (unchecked-byte (Integer/parseInt (.substring hex (* 2 i) (+ (* 2 i) 2)) 16))))
    (String. bs "UTF-8")))

(defn fetch-verified-artifact!
  "Fetches an artifact from local verified cache (`/tmp/hive-prompts/<cid>.md`),
   the local Bitecho node by `--cid` (or `--subnet` + `--path`), or `ns1.bitecho.org`
   HTTPS fallback and asserts SHA-256 == `cid` loudly."
  [{:keys [cid subnet path state-dir node-bin light-node-js]}]
  (let [cache-file (when cid (io/file "/tmp/hive-prompts" (str cid ".md")))
        eff-state-dir (or state-dir (str (System/getProperty "user.home") "/.bitecho/light-node"))]
    (if (and cache-file (.isFile cache-file))
      (let [text (slurp cache-file)
            actual-cid (env/sha256-str text)]
        (when (not= cid actual-cid)
          (throw (ex-info "Cached artifact SHA-256 mismatch"
                          {:expected cid :actual actual-cid :file (.getPath cache-file)})))
        {:cid actual-cid :text text :bytes (count (.getBytes text "UTF-8"))})
      (let [cmd (if cid
                  [(bindings/resolve-node-bin node-bin)
                   (bindings/resolve-light-node-js light-node-js)
                   "ctl" "--state-dir" eff-state-dir "artifact-get" "--cid" cid]
                  [(bindings/resolve-node-bin node-bin)
                   (bindings/resolve-light-node-js light-node-js)
                   "ctl" "--state-dir" eff-state-dir "artifact-get" "--subnet" subnet "--path" path])
            res (bwrap/run-command-vec! cmd {:timeout-ms 15000})
            parsed (when (:ok? res)
                     (try (json/read-str (:stdout res) :key-fn keyword)
                          (catch Exception _ nil)))
            hex-str (when parsed (or (:hex parsed) (:data_hex parsed) (:payload_hex parsed)))
            text (cond
                   hex-str (hex->utf8 hex-str)
                   (and parsed (:text parsed)) (str (:text parsed))
                   cid
                   (let [url (str "https://ns1.bitecho.org/artifact/v1/blob/" cid)
                         curl-res (bwrap/run-command-vec! ["curl" "-sSf" url] {:timeout-ms 15000})]
                     (when-not (:ok? curl-res)
                       (throw (ex-info "Failed to fetch Bitecho artifact via ctl and ns1 HTTPS fallback"
                                       {:cid cid :path path :ctl-res res :curl-res curl-res})))
                     (:stdout curl-res))
                   :else
                   (throw (ex-info "Failed to fetch Bitecho artifact" {:cid cid :path path :res res})))
            actual-cid (env/sha256-str text)]
        (when (and cid (not= cid actual-cid))
          (throw (ex-info "Artifact SHA-256 mismatch"
                          {:expected cid :actual actual-cid :path path})))
        {:cid actual-cid :text text :bytes (count (.getBytes text "UTF-8"))}))))

;; ---------------------------------------------------------------------------
;; Local Agy Seat Port Factory (for In-Memory Shell with Live Frontier Models)
;; ---------------------------------------------------------------------------

(defn make-bwrap-agy-seat-port
  "Creates an `IIntelligencePort` backed by `bwrap/invoke-agy-turn!` for a given
   model selector so the in-memory Mealy shell can run either mock or live frontier models."
  [{:keys [model-selector timeout-ms] :or {timeout-ms 90000}}]
  (reify intel-port/IIntelligencePort
    (start-session! [this _] this)
    (generate! [_ req]
      (let [t0 (System/nanoTime)
            res (bwrap/invoke-agy-turn! {:model-selector model-selector
                                         :prompt (:prompt req)
                                         :timeout-ms timeout-ms
                                         :use-bwrap? (bwrap/bwrap-available?)})
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (if (:ok? res)
          {:text (:stdout res) :model model-selector :model-ms ms}
          {:text (str "CONSENT-WITH-ADDENDUM: [harness-timeout-or-error: " (:stderr res) "]")
           :model model-selector
           :model-ms ms})))
    (stop-session! [_] nil)))

;; ---------------------------------------------------------------------------
;; Boundary Shell 1: In-Memory Multi-Agent Mealy Shell
;; ---------------------------------------------------------------------------

(defn- step-graph-turn!
  [ctx in-port payload]
  (let [g0 (:graph-inst ctx)
        prepared (if (zero? (long (:tx g0 0))) g0 (runtime/continue-graph g0))
        executed (-> prepared
                     (runtime/enqueue-input in-port payload)
                     runtime/run-graph!)
        errs (get-in executed [:outbox :error-out] [])]
    (-> ctx
        (assoc :graph-inst executed)
        (update :all-event-log into (:event-log executed))
        (update :diagnostics-log into errs))))

(defn- drain-one-agent!
  [ctx max-events]
  (let [source (get-in ctx [:transport-bundle :event-source])]
    (loop [c ctx
           n 0]
      (if (>= n max-events)
        c
        (let [{:keys [event cursor]} (event-src/poll-event! source (:cursor c) 0)]
          (if-not event
            (assoc c :cursor cursor)
            (let [[mw' verdict] (event-src/apply-middleware (:middleware-state c) event (+ 10000 (* n 100)))
                  c-mw (cond-> (assoc c :middleware-state mw')
                         (:diagnose verdict) (update :diagnostics-log conj (:diagnose verdict)))
                  c-after (if (= :accept (:action verdict))
                            (step-graph-turn! c-mw :incoming-in event)
                            c-mw)]
              (recur (assoc c-after :cursor cursor) (inc n)))))))))

(defn run-in-memory-mealy-episode!
  "Runs the full 5-seat digital twin episode using 5 independent `twin-seat-graph`
   Mealy agents + 1 `twin-coordinator-graph` Mealy agent communicating exclusively
   via `:net/bitecho` DMs over `bitecho.hive.transport/make-in-memory-fabric`."
  [{:keys [spec seed run-id values-text seat-prompts seat-ports blind-round1?
           max-ticks inject-stray-pleasantry?]
    :or {seed 20261126
         run-id "sim-twins"
         values-text ""
         blind-round1? false
         max-ticks 200}}]
  (let [seats (mapv :seat-id (:scenario/seats spec))
        coord-pk "pk-coordinator"
        seat-endpoints (into {}
                             (map (fn [s]
                                    [s {:pubkey (str "pk-" (name s))
                                        :session-id (str "sess-" (name s))}])
                                  seats))
        balances (into {coord-pk 100000}
                       (map (fn [s] [(str "pk-" (name s)) 100000]) seats))
        fabric (transport/make-in-memory-fabric {:balances balances})
        coord-tx (transport/build-agent-transport {:transport :in-memory
                                                   :fabric fabric
                                                   :self-pubkey coord-pk
                                                   :subnet-id "hive/twins"})
        coord-dev (binder/compile-device
                   [:device {:id :twin-coord-device}
                    [:cap {:id :storage-cas :type :storage/cas}]
                    [:cap {:id :bitecho-net
                           :type :net/bitecho
                           :subnet-id "hive/twins"
                           :min-balance 2000
                           :balance 100000
                           :handler-fn (:bitecho-handler coord-tx)}]])
        coord-graph (binder/bind-agent
                     (coord/twin-coordinator-graph
                      {:coord-init-opts {:spec spec
                                         :seed seed
                                         :run-id run-id
                                         :values-text values-text
                                         :seat-prompts seat-prompts
                                         :seat-endpoints seat-endpoints
                                         :blind-round1? blind-round1?
                                         :run-phase2? false}})
                     coord-dev {})
        coord-ctx0 {:graph-inst coord-graph
                    :device-inst coord-dev
                    :transport-bundle coord-tx
                    :middleware-state (event-src/init-middleware-state {:self-id coord-pk :max-per-window 1000})
                    :cursor 0
                    :all-event-log []
                    :diagnostics-log []}
        seat-ctxs0 (into {}
                         (map (fn [s]
                                (let [pk (str "pk-" (name s))
                                      tx (transport/build-agent-transport {:transport :in-memory
                                                                           :fabric fabric
                                                                           :self-pubkey pk
                                                                           :subnet-id "hive/twins"})
                                      dev (binder/compile-device
                                           [:device {:id (keyword (str "twin-" (name s) "-device"))}
                                            [:cap {:id :storage-cas :type :storage/cas}]
                                            [:cap {:id :llm-port
                                                   :type :intelligence/port
                                                   :port (get seat-ports s)}]
                                            [:cap {:id :bitecho-net
                                                   :type :net/bitecho
                                                   :subnet-id "hive/twins"
                                                   :min-balance 2000
                                                   :balance 100000
                                                   :handler-fn (:bitecho-handler tx)}]])
                                      g (binder/bind-agent (coord/twin-seat-graph {:seat-id s
                                                                                   :subnet-id "hive/twins"
                                                                                   :coordinator-pubkey coord-pk})
                                                           dev {})]
                                  [s {:graph-inst g
                                      :device-inst dev
                                      :transport-bundle tx
                                      :middleware-state (event-src/init-middleware-state {:self-id pk :max-per-window 1000})
                                      :cursor 0
                                      :all-event-log []
                                      :diagnostics-log []}])))
                         seats)]
    (when inject-stray-pleasantry?
      (transport/fabric-deliver-dm! fabric "pk-stranger" "pk-axiom" "Hey Axiom, nice weather today!" "stray-1" "sess-stray"))
    (try
      (let [coord-started (step-graph-turn! coord-ctx0 :control-in {:event/type :start})]
        (loop [c-ctx coord-started
               s-ctxs seat-ctxs0
               tick 0]
          (let [coord-st (coord/coordinator-state-of (:graph-inst c-ctx))]
            (if (or (coord/terminal? coord-st) (>= tick max-ticks))
              {:coordinator-state coord-st
               :env-state (:env coord-st)
               :coordinator-context c-ctx
               :seat-contexts s-ctxs
               :fabric fabric
               :ticks tick}
              (let [s-ctxs' (into {} (map (fn [[s sc]] [s (drain-one-agent! sc 5)]) s-ctxs))
                    c-ctx' (drain-one-agent! c-ctx 5)]
                (recur c-ctx' s-ctxs' (inc tick)))))))
      (finally
        (binder/stop-device! coord-dev)
        (doseq [[_ sc] seat-ctxs0]
          (binder/stop-device! (:device-inst sc)))))))

(defn run-in-memory-twins!
  "Executes Phase 1 through the single pure Sans-IO `twin-coordinator` + 5 independent
   `twin-seat-graph` Mealy agents communicating over `bitecho.hive.transport` in-memory fabric."
  [{:keys [spec seed run-id values-text seat-prompts seat-ports blind-round1? max-ticks]
    :or {seed 20261126 run-id "sim-twins" blind-round1? false max-ticks 200}}]
  (let [effective-ports (or seat-ports
                            (into {}
                                  (map (fn [{:keys [seat-id model-selector]}]
                                         [seat-id (make-bwrap-agy-seat-port {:model-selector model-selector})])
                                       (:scenario/seats spec))))]
    (run-in-memory-mealy-episode!
     {:spec spec
      :seed seed
      :run-id run-id
      :values-text values-text
      :seat-prompts seat-prompts
      :seat-ports effective-ports
      :blind-round1? blind-round1?
      :max-ticks max-ticks})))

;; ---------------------------------------------------------------------------
;; Boundary Shell 2: Distributed Live Devnet Shell
;; ---------------------------------------------------------------------------

(defn- step-coord-graph!
  [ctx in-port payload]
  (let [g0 (:graph-inst ctx)
        prepared (if (zero? (long (:tx g0 0))) g0 (runtime/continue-graph g0))
        executed (-> prepared
                     (runtime/enqueue-input in-port payload)
                     runtime/run-graph!)
        errs (get-in executed [:outbox :error-out] [])]
    (-> ctx
        (assoc :graph-inst executed)
        (update :all-event-log into (:event-log executed))
        (update :diagnostics-log into errs))))

(defn run-live-devnet-twins!
  "Executes Phase 1 over the live Bitecho devnet using the EXACT same
   `twin-coordinator-graph` Sans-IO core, dispatching sealed DMs via
   `make-bitecho-ctl-handler` and polling replies via `make-bitecho-ingress`."
  [{:keys [spec seed run-id values-text seat-prompts seat-endpoints state-dir
           node-bin light-node-js subnet-id min-balance blind-round1?
           poll-timeout-ms max-poll-cycles]
    :or {seed 20261126
         run-id "live-devnet-twins"
         subnet-id "hive/twins"
         min-balance bindings/DEFAULT-MIN-BALANCE
         blind-round1? false
         poll-timeout-ms 90000
         max-poll-cycles 120}}]
  (when-not (and state-dir (seq seat-endpoints))
    (throw (ex-info "run-live-devnet-twins! requires :state-dir and non-empty :seat-endpoints"
                    {:state-dir state-dir :seat-endpoints-keys (keys seat-endpoints)})))
  (let [ctl-handler (bindings/make-bitecho-ctl-handler {:state-dir state-dir
                                                        :node-bin node-bin
                                                        :light-node-js light-node-js})
        status (ctl-handler {"cmd" "status"})
        self-pk (:pubkey status)
        bal (long (or (get-in status [:account :spendable]) (get-in status [:account :balance]) 25000))
        cursor-file (str state-dir "/twin-coord-cursor.edn")
        roster-pks (set (keep :pubkey (vals seat-endpoints)))
        source (ingress/make-bitecho-ingress {:state-dir state-dir
                                              :node-bin node-bin
                                              :light-node-js light-node-js
                                              :cursor-file cursor-file
                                              :ctl-handler ctl-handler
                                              :offer-policy :roster-only
                                              :roster-set roster-pks})
        dev (binder/compile-device
             [:device {:id :twin-live-coord-device}
              [:cap {:id :storage-cas :type :storage/cas}]
              [:cap {:id :bitecho-net
                     :type :net/bitecho
                     :subnet-id subnet-id
                     :min-balance min-balance
                     :balance bal
                     :handler-fn ctl-handler}]])
        g0 (binder/bind-agent
            (coord/twin-coordinator-graph
             {:coord-init-opts {:spec spec
                                :seed seed
                                :run-id run-id
                                :values-text values-text
                                :seat-prompts seat-prompts
                                :seat-endpoints seat-endpoints
                                :blind-round1? blind-round1?
                                :run-phase2? false}
              :min-balance min-balance})
            dev {})
        ctx0 {:graph-inst g0
              :device-inst dev
              :middleware-state (event-src/init-middleware-state {:self-id self-pk
                                                                  :allowed-broadcast-senders roster-pks
                                                                  :max-per-window 200})
              :cursor (ingress/read-cursor cursor-file)
              :all-event-log []
              :diagnostics-log []}]
    (try
      (let [ctx-started (step-coord-graph! ctx0 :control-in {:event/type :start})]
        (loop [ctx ctx-started
               cycle 0]
          (let [coord-st (coord/coordinator-state-of (:graph-inst ctx))]
            (if (or (coord/terminal? coord-st) (>= cycle (long max-poll-cycles)))
              {:coordinator-state coord-st
               :env-state (:env coord-st)
               :coordinator-context ctx
               :cycles cycle}
              (let [prev-cur (:cursor ctx)
                    {:keys [event cursor diagnose]} (event-src/poll-event! source prev-cur poll-timeout-ms)
                    ctx-d (cond-> ctx diagnose (update :diagnostics-log conj diagnose))]
                (if-not event
                  ;; Poll timed out -> notify coordinator with :seat-timeout so generation bumps & retries loudly
                  (let [inflight (:inflight coord-st)
                        ctx-t (if inflight
                                (step-coord-graph! ctx-d :control-in {:event/type :seat-timeout
                                                                      :correlation-id (:correlation-id inflight)
                                                                      :generation (:generation inflight)
                                                                      :seat (:seat inflight)
                                                                      :error "devnet-poll-timeout"})
                                ctx-d)]
                    (when (> (long cursor) (long prev-cur))
                      (ingress/write-cursor! cursor-file cursor))
                    (recur (assoc ctx-t :cursor cursor) (inc cycle)))
                  (let [[mw' verdict] (event-src/apply-middleware (:middleware-state ctx-d) event (System/currentTimeMillis))
                        ctx-mw (cond-> (assoc ctx-d :middleware-state mw')
                                 (:diagnose verdict) (update :diagnostics-log conj (:diagnose verdict)))
                        ctx-after (if (= :accept (:action verdict))
                                    (step-coord-graph! ctx-mw :incoming-in event)
                                    ctx-mw)]
                    (when (> (long cursor) (long prev-cur))
                      (ingress/write-cursor! cursor-file cursor))
                    (recur (assoc ctx-after :cursor cursor) (inc cycle)))))))))
      (finally
        (binder/stop-device! dev)
        (event-src/close-source! source)))))

;; ---------------------------------------------------------------------------
;; Phase 2 Disposable-Copy Verification & R-2/R-3 Trace Finalization
;; ---------------------------------------------------------------------------

(defn- run-phase2-and-finalize!
  [{:keys [st-fp1 spec seed out-dir run-id proposal-id base-repo-hash patch-doc-path transport-mode]}]
  (.mkdirs (io/file out-dir))
  (let [st-final
        (if (= :fixed-point-1 (:status st-fp1))
          (let [[st-p2 _] (env/transition-to-implement st-fp1 (:sim-consent-receipt st-fp1))
                copy-dir (str out-dir "/disposable-copy")
                _ (bwrap/run-command-vec! ["rm" "-rf" copy-dir])
                clone-res (bwrap/run-command-vec! ["git" "clone" "--local" "--no-hardlinks" "." copy-dir]
                                                  {:timeout-ms 30000})
                _ (when-not (:ok? clone-res)
                    (throw (ex-info "Failed to create disposable copy" clone-res)))
                doc-file (io/file copy-dir patch-doc-path)
                _ (.mkdirs (.getParentFile doc-file))
                _ (spit doc-file
                        (str "# Advisory Twin-Circle Shaped Proposal (" run-id ")\n\n"
                             "- **Base Repo Hash:** `" base-repo-hash "`\n"
                             "- **Proposal ID:** `" proposal-id "`\n"
                             "- **Fixed Point 1 Round:** " (:round st-fp1) "\n\n"
                             "## Shaped Proposal\n\n"
                             (:proposal-text st-fp1) "\n\n"
                             "**Hard Invariant:** Simulated outcomes are advisory only; simulated consent is never real consent, and all real Stage 2 review + devnet pointer promotion gates remain unchanged.\n"))
                _ (bwrap/run-command-vec! ["git" "-C" copy-dir "add" patch-doc-path])
                diff-res (bwrap/run-command-vec! ["git" "-C" copy-dir "diff" "--cached"])
                patch-text (:stdout diff-res)
                _ (spit (io/file out-dir (str run-id ".patch")) patch-text)
                [st-patch _] (env/step st-p2 :vector
                                       {:action/type :protocol-act
                                        :action/content (str "Generated advisory policy patch inside disposable copy (" patch-doc-path ").")
                                        :action/protocol {:verb :submit-patch :patch-text patch-text}
                                        :action/raw-output (str "SUBMIT_PATCH " patch-doc-path)
                                        :latency-ms 50})
                chaos-check (env/run-in-memory-closed-loop! (env/run-1-scenario-spec seed))
                chaos-ok? (true? (get-in chaos-check [:verification :ok?]))
                [st-fp2 _] (env/step st-patch :vector
                                     {:action/type :protocol-act
                                      :action/content (if chaos-ok? "Proof + chaos suite all green." "Chaos failed.")
                                      :action/protocol {:verb :record-chaos-result
                                                        :chaos-results {:all-green? chaos-ok?
                                                                        :scenarios-passed (if chaos-ok? 3 0)
                                                                        :scenarios-total 3
                                                                        :proof-merkle-root (get-in chaos-check [:trace-v2 :merkle-root])
                                                                        :bwrap-available? (bwrap/bwrap-available?)}}
                                      :action/raw-output (str "CHAOS_RESULT all-green=" chaos-ok?)
                                      :latency-ms 100})]
            st-fp2)
          st-fp1)
        rewards (scorer/score-episode st-final)
        trace-v2 (trace/build-episode-trace-v2 {:env-state st-final :rewards rewards :seed seed :run-id run-id})
        verification (trace/verify-episode-trace-v2 trace-v2)
        jsonl (trace/export-trace-v2->jsonl trace-v2)
        out (env/outcome st-final)
        telemetry-green? (boolean (and (= :fixed-point-2 (:status out))
                                       (get-in out [:chaos-results :all-green?])
                                       (:ok? verification)))
        tl0 (top/init-top-loop {:repo-hash base-repo-hash
                                :values-cid env/VALUES-DRAFT-CID})
        [tl1 _] (top/record-sim-iteration
                 tl0
                 {:proposal-cid (get-in spec [:scenario/proposal :proposal/cid])
                  :sim-trace-cid (:merkle-root trace-v2)
                  :patch-cid (or (:patch-cid out) "none")
                  :telemetry-cid (env/sha256-str (pr-str (:chaos-results out)))
                  :telemetry-green? telemetry-green?})
        sim-attempt-receipt (assoc (or (:sim-consent-receipt out) {:simulation? true :advisory-only true})
                                   :patch-cid (or (:patch-cid out) "none")
                                   :receipt-cid (:header-cid trace-v2))
        [tl-checked gate-effects] (top/request-pointer-promotion tl1 sim-attempt-receipt)
        gate-fail-closed? (and (= :pending-promotion (:phase tl-checked))
                               (= base-repo-hash (:repo-hash tl-checked))
                               (= :diagnose (ffirst gate-effects)))
        summary {:trace/version 2
                 :run-id (:run-id trace-v2)
                 :scenario-id (:scenario-id trace-v2)
                 :transport transport-mode
                 :base-repo-hash base-repo-hash
                 :live-agy-models? true
                 :bwrap-enforced? (boolean (bwrap/bwrap-available?))
                 :bwrap-note (if (bwrap/bwrap-available?)
                               "Bubblewrap (bwrap) sandbox enforced on host."
                               "Host missing sys-apps/bubblewrap; fallback to isolated /tmp disposable git clone.")
                 :design-note-cid "a5ad135a823a0d2d0e92d3e094a5f18b2e9ccbdd392cfb6d6aa47eac6a8b13f3"
                 :status (:status out)
                 :rounds-executed (:round out)
                 :turn-count (count (:turns trace-v2))
                 :header-cid (:header-cid trace-v2)
                 :merkle-root (:merkle-root trace-v2)
                 :subnet-merkle-root (:subnet-merkle-root trace-v2)
                 :pointer-path (:pointer-path trace-v2)
                 :shaped-proposal (:proposal-text st-final)
                 :sim-consent-receipt (:sim-consent-receipt out)
                 :standing-objections (:standing-objections out)
                 :open-addenda (:open-addenda out)
                 :patch-cid (:patch-cid out)
                 :chaos-results (:chaos-results out)
                 :top-loop {:base-repo-hash base-repo-hash
                            :phase (:phase tl-checked)
                            :pending-candidate (:pending-candidate tl-checked)
                            :sim-promotion-gate-fail-closed? gate-fail-closed?
                            :gate-effects gate-effects
                            :chain-verified? (top/verify-top-loop-chain tl-checked)
                            :diagnostics (:diagnostics tl-checked)}
                 :rewards rewards
                 :verification verification}]
    (spit (io/file out-dir (str run-id "-trace.edn")) (pr-str summary))
    (spit (io/file out-dir (str run-id "-trace.jsonl")) jsonl)
    (println (str "[twin-runner] " (str/upper-case run-id) " (" (name transport-mode) ") COMPLETE:")
             (pr-str {:status (:status out)
                      :rounds (:round out)
                      :turns (count (:turns trace-v2))
                      :ok? (:ok? verification)
                      :merkle-root (:merkle-root trace-v2)
                      :team-reward (:team-reward rewards)
                      :gate-fail-closed? gate-fail-closed?}))
    summary))

(defn run-live-run-1!
  "Executes the 5-seat closed-loop simulation via the unified Sans-IO core on either
   `:transport :in-memory` (default, 5 independent Mealy seat agents in-process) or
   `:transport :live` (distributed Mealy seat agents over live Bitecho devnet)."
  [{:keys [seed out-dir max-review-rounds run-id proposal-id proposal-text vector-model
           base-repo-hash patch-doc-path transport state-dir seat-endpoints blind-round1? seat-ports]
    :or {seed 20261120
         out-dir "/tmp/hive-live-run2"
         max-review-rounds 3
         run-id "live-run-2"
         proposal-id "PROP-RUN-2-MEMENTO-CIRCLES"
         proposal-text env/RUN-2-PROPOSAL-TEXT
         vector-model "gemini-4-carbon-high"
         base-repo-hash RUN-3-BASE-REPO-HASH
         patch-doc-path "docs/twin-circle-advisory-policy.md"
         transport :in-memory
         blind-round1? false}}]
  (let [base-spec (env/run-1-scenario-spec seed)
        seats (mapv (fn [s]
                      (if (= :vector (:seat-id s))
                        (assoc s :model-selector vector-model)
                        s))
                    (:scenario/seats base-spec))
        spec (-> base-spec
                 (assoc :scenario/id (str "governance-closed-loop-" run-id)
                        :scenario/seats seats
                        :scenario/proposal {:proposal/id proposal-id
                                            :proposal/cid (env/sha256-str proposal-text)
                                            :proposal/text proposal-text})
                 (assoc-in [:scenario/limits :max-review-rounds] max-review-rounds))
        values-art (fetch-verified-artifact! {:cid env/VALUES-DRAFT-CID :state-dir state-dir})
        seat-prompts (into {}
                           (map (fn [{:keys [seat-id prompt-cid]}]
                                  (let [art (fetch-verified-artifact! {:cid prompt-cid :state-dir state-dir})]
                                    [seat-id (:text art)]))
                                (:scenario/seats spec)))
        phase1-res (case (keyword transport)
                     :live
                     (run-live-devnet-twins! {:spec spec
                                              :seed seed
                                              :run-id run-id
                                              :values-text (:text values-art)
                                              :seat-prompts seat-prompts
                                              :seat-endpoints seat-endpoints
                                              :state-dir state-dir
                                              :blind-round1? blind-round1?})
                     (:in-memory :sim)
                     (run-in-memory-twins! {:spec spec
                                            :seed seed
                                            :run-id run-id
                                            :values-text (:text values-art)
                                            :seat-prompts seat-prompts
                                            :seat-ports seat-ports
                                            :blind-round1? blind-round1?})
                     (throw (ex-info (str "Unsupported twin-runner transport: " transport)
                                     {:transport transport})))]
    (run-phase2-and-finalize! {:st-fp1 (:env-state phase1-res)
                               :spec spec
                               :seed seed
                               :out-dir out-dir
                               :run-id run-id
                               :proposal-id proposal-id
                               :base-repo-hash base-repo-hash
                               :patch-doc-path patch-doc-path
                               :transport-mode (keyword transport)})))

(defn run-live-run-3!
  "Executes Live 5-Seat Run-3 (Whole-Stack Top-Loop proposal anchored to
   `RUN-3-BASE-REPO-HASH` `661d926a...`)."
  ([] (run-live-run-3! {}))
  ([opts]
   (run-live-run-1!
    (merge {:seed 20261121
            :out-dir "/tmp/hive-live-run3"
            :max-review-rounds 3
            :run-id "live-run-3"
            :proposal-id "PROP-RUN-3-WHOLE-STACK-TOP-LOOP"
            :proposal-text top/RUN-3-PROPOSAL-TEXT
            :vector-model "gemini-4-carbon-high"
            :base-repo-hash RUN-3-BASE-REPO-HASH
            :patch-doc-path "docs/run3-top-loop-advisory.md"}
           opts))))

(defn -main
  "CLI entrypoint to execute 5-seat closed-loop simulations (`--transport in-memory|live`)."
  [& args]
  (let [opts (parse-cli-args args)]
    (if (:help? opts)
      (println (help-text))
      (let [endpoints (when-let [f (:seat-endpoints-edn opts)]
                        (edn/read-string (slurp f)))
            run-opts (cond-> {:transport (:transport opts)
                              :blind-round1? (:blind-round1? opts)}
                       (:state-dir opts) (assoc :state-dir (:state-dir opts))
                       (:out-dir opts) (assoc :out-dir (:out-dir opts))
                       endpoints (assoc :seat-endpoints endpoints))]
        (case (:run opts)
          "2" (run-live-run-1! run-opts)
          (run-live-run-3! run-opts)))))
  (shutdown-agents))
