(ns bitecho.hive.bindings
  "HIVE-BIP-001 H2: Concrete Bindings for `:net/bitecho` (live ctl bridge),
   `:compute/einsum` (`clj-einsum` Gemma 4 E2B `IIntelligencePort`), and
   `:compute/agy` (headless streaming `agy` `IIntelligencePort`).

   All concrete Bitecho and clj-einsum glue lives here in Hive so Mealy remains
   a 0-dependency pure harness."
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [mealy.intelligence.adapters.agy :as agy]
            [mealy.intelligence.port :as intel-port]
            [mealy.runtime.jvm.process :as agy-proc]))

(defn resolve-node-bin
  "Resolves Node.js binary path from explicit override, `BITECHO_NODE_BIN` env,
   or `node` on PATH."
  ([] (resolve-node-bin nil))
  ([override]
   (or (when (and override (not (str/blank? (str override)))) (str override))
       (when-let [env-v (System/getenv "BITECHO_NODE_BIN")]
         (when-not (str/blank? env-v) env-v))
       "node")))

(defn resolve-light-node-js
  "Resolves `bitecho-light-node.cjs` path from explicit override, `BITECHO_LIGHT_NODE_JS`
   env, or relative `../bitecho/dist/bitecho-light-node.cjs`."
  ([] (resolve-light-node-js nil))
  ([override]
   (or (when (and override (not (str/blank? (str override)))) (str override))
       (when-let [env-v (System/getenv "BITECHO_LIGHT_NODE_JS")]
         (when-not (str/blank? env-v) env-v))
       "../bitecho/dist/bitecho-light-node.cjs")))

(defn resolve-einsum-model
  "Resolves default `clj-einsum` model directory from explicit override,
   `CLJ_EINSUM_MODEL_DIR` env, or relative `../clj-einsum/.models/gemma-4-E2B-it`."
  ([] (resolve-einsum-model nil))
  ([override]
   (or (when (and override (not (str/blank? (str override)))) (str override))
       (when-let [env-v (System/getenv "CLJ_EINSUM_MODEL_DIR")]
         (when-not (str/blank? env-v) env-v))
       "../clj-einsum/.models/gemma-4-E2B-it")))

(def ^:const DEFAULT-MIN-BALANCE
  "Default minimum ECH balance floor guard for outbound Bitecho commands."
  2000)

;; =============================================================================
;; 1. Live :net/bitecho CLI Bridge
;; =============================================================================

(defn default-exec-fn
  "Executes a command vector via `clojure.java.shell/sh`."
  [cmd-vec]
  (apply sh/sh (map str cmd-vec)))

(defn run-bitecho-ctl!
  "Invokes `bitecho-light-node.cjs ctl --state-dir <dir> ...` and parses JSON output.
   Never fails silently: non-zero exit returns `{:ok false :exit <n> :error <str>}`."
  [{:keys [node-bin light-node-js state-dir exec-fn]} & args]
  (let [cmd (into [(resolve-node-bin node-bin)
                   (resolve-light-node-js light-node-js)
                   "ctl" "--state-dir" (str state-dir)]
                  (map str args))
        {:keys [exit out err]} ((or exec-fn default-exec-fn) cmd)]
    (if (zero? (long (or exit -1)))
      (let [trimmed (str/trim (or out ""))]
        (if (str/blank? trimmed)
          {:ok true}
          (try
            (json/read-str trimmed :key-fn keyword)
            (catch Exception _ {:ok true :raw trimmed}))))
      {:ok false
       :exit exit
       :error (let [e (str/trim (or err ""))]
                (if (str/blank? e) (str/trim (or out "ctl-failed")) e))})))

(defn make-bitecho-ctl-handler
  "Constructs a live `:net/bitecho` `:handler-fn` translating canonical command maps
   into `bitecho-light-node.cjs ctl` invocations with `--min-balance` enforcement."
  [cfg]
  (fn [cmd-map]
    (let [verb (get cmd-map "cmd")]
      (case verb
        "status"
        (run-bitecho-ctl! cfg "status")

        "sync-account"
        (run-bitecho-ctl! cfg "sync-account")

        "subscribe"
        (run-bitecho-ctl! cfg "subscribe-subnets" "--subnets" (str (get cmd-map "subnet_id" "hive/poc")))

        "broadcast"
        (run-bitecho-ctl! cfg "broadcast"
                          "--subnet-id" (str (get cmd-map "subnet_id" "hive/poc"))
                          "--message" (str (get cmd-map "message" ""))
                          "--min-balance" (str (or (get cmd-map "min_balance") DEFAULT-MIN-BALANCE)))

        "send"
        (let [sid (get cmd-map "session_id")
              args (cond-> ["send"
                            "--peer" (str (get cmd-map "peer" ""))
                            "--message" (str (get cmd-map "message" ""))
                            "--min-balance" (str (or (get cmd-map "min_balance") DEFAULT-MIN-BALANCE))]
                     (and sid (not (str/blank? (str sid))))
                     (into ["--session-id" (str sid)]))]
          (apply run-bitecho-ctl! cfg args))

        "inbox"
        (run-bitecho-ctl! cfg "inbox" "--peek")

        "p2p-answer"
        (run-bitecho-ctl! cfg "p2p-answer" "--session-id" (str (get cmd-map "session_id" "")))

        {:ok false :error (str "unsupported-bitecho-verb:" verb)}))))

;; =============================================================================
;; 2. clj-einsum Gemma 4 E2B IIntelligencePort Binding (M2 Contract)
;; =============================================================================

(defn make-einsum-port
  "Creates an `IIntelligencePort` binding to `clj-einsum` Gemma 4 E2B-it.
   Defaults to `:uop-intel` GPU backend, `:int4` precision, `:thinking false` (no `<|think|>`).
   Adapts `clj-einsum`'s 2-arity `generate-new-text-string [session prompt]` to
   Mealy M2's canonical 1-arity `generate! [this req] -> {:text ... :model-ms ...}`.
   Accepts injectable `:init-fn`, `:gen-fn`, `:close-fn` for hermetic testing."
  [opts]
  (let [session-atom (atom nil)
        model (resolve-einsum-model (:model opts))
        backend (keyword (or (:einsum-backend opts) (:backend opts) :uop-intel))
        precision (keyword (or (:precision opts) :int4))
        max-new-tokens (long (or (:max-new-tokens opts) 96))
        temperature (double (or (:temperature opts) 0.4))
        top-k (long (or (:top-k opts) 20))
        rep-pen (double (or (:repetition-penalty opts) 1.15))]
    (reify intel-port/IIntelligencePort
      (start-session! [this _start-opts]
        (when-not @session-atom
          (let [init-fn (or (:init-fn opts)
                            (requiring-resolve 'einsum.models.gemma4.runtime/init-inference-session))
                sess (init-fn {:model model
                               :backend backend
                               :precision precision
                               :method :kv-cache
                               :max-new-tokens max-new-tokens
                               :temperature temperature
                               :top-k top-k
                               :repetition-penalty rep-pen
                               :thinking false
                               :quiet true
                               :profile false})]
            (reset! session-atom sess)))
        this)

      (generate! [_ req]
        (let [sess @session-atom
              _ (when-not sess
                  (throw (ex-info "Einsum intelligence port not started (call start-session! first)"
                                  {:model model :backend backend})))
              gen-fn (or (:gen-fn opts)
                         (requiring-resolve 'einsum.models.gemma4.runtime/generate-new-text-string))
              t0 (System/nanoTime)
              txt (gen-fn sess (:prompt req))
              ms (/ (- (System/nanoTime) t0) 1e6)]
          {:text (str (or txt ""))
           :model (str "clj-einsum/" (name backend) "/" (name precision))
           :model-ms ms}))

      (stop-session! [_]
        (when-let [sess @session-atom]
          (reset! session-atom nil)
          (when-let [close-fn (or (:close-fn opts)
                                  (try (requiring-resolve 'einsum.models.gemma4.runtime/close-agent-session!)
                                       (catch Exception _ nil)))]
            (try (close-fn sess) (catch Exception _ nil))))
        nil))))

;; =============================================================================
;; 3. Headless Agy IIntelligencePort Binding (M2 Contract)
;; =============================================================================

(defn make-agy-port
  "Creates an `IIntelligencePort` binding to headless streaming `agy` via
   `mealy.intelligence.adapters.agy` and `mealy.runtime.jvm.process/send-agy-turn!`."
  [opts]
  (let [model (str (or (:model opts) "claude-sonnet-5-5-medium"))
        working-dir (str (or (:working-dir opts) "."))
        timeout-ms (long (or (:timeout-ms opts) agy/default-timeout-ms))
        send-fn (or (:send-fn opts) agy-proc/send-agy-turn!)]
    (reify intel-port/IIntelligencePort
      (start-session! [this _start-opts] this)

      (generate! [_ req]
        (let [t0 (System/nanoTime)
              agy-req (agy/build-request (:prompt req) {:model (or (:model req) model)
                                                        :working-dir working-dir
                                                        :timeout-ms timeout-ms})
              raw (send-fn agy-req)
              parsed (agy/parse-response {:status 200 :body raw})
              ms (/ (- (System/nanoTime) t0) 1e6)]
          (if (:error parsed)
            {:error true :reason (:reason parsed) :model-ms ms}
            (let [in-toks (:input-tokens parsed)
                  out-toks (:output-tokens parsed)
                  real-split? (and (number? in-toks) (number? out-toks))]
              (cond-> {:text (str (or (:response parsed) ""))
                       :model (or (:model req) model)
                       :prompt-tokens (when real-split? (long in-toks))
                       :new-tokens (when real-split? (long out-toks))
                       :token-accounting (if real-split? :real :unavailable)
                       :tool-calls (long (or (:tool-calls parsed) 0))
                       :model-ms ms}
                (number? (:tokens parsed))
                (assoc :cumulative-tokens (long (:tokens parsed))))))))

      (stop-session! [_]
        (try (agy-proc/stop-agy-session! {:working-dir working-dir :model model})
             (catch Exception _ nil))
        nil))))
