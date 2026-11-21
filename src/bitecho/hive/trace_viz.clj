(ns bitecho.hive.trace-viz
  "Browser-Based Hive Trace Audio Visualizer (ASK 2 / M0 Demo).

   Serves an interactive 5-seat sociocratic circle visualizer where digital twins'
   responses from verified Hive Run-2 & Run-3 traces are read aloud in-browser
   via per-agent Kokoro-82M voices (`kokoro-js`, voice ID sourced from each
   member's petname entry).

   Pure core functions handle petname-voice registry, JSONL trace parsing, and
   TTS text sanitization; the thin JVM `HttpServer` shell serves the UI and
   SHA-256-verified traces (local `/tmp` cache or `ns1.bitecho.org` fallback)."
  (:require [bitecho.hive.env :as env]
            [bitecho.hive.twin-runner :as runner]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

;; ---------------------------------------------------------------------------
;; Pure Core: Petname Voice Registry, Sanitization & Trace Parsing
;; ---------------------------------------------------------------------------

(defn default-petname-voices
  "Petname entries for the 5-member Bitecho builders-stewards circle, including
   each member's default Kokoro-82M `voice_id` for browser TTS playback."
  []
  [{:id "simon"
    :petname "simon"
    :label "Simon"
    :role "Human Anchor Twin"
    :model "claude-opus-5-5-high"
    :voice_id "am_michael"
    :color "#f59e0b"
    :angle_deg -90}
   {:id "axiom"
    :petname "axiom"
    :label "Axiom"
    :role "Governance & Strategy"
    :model "gpt-oss-120b-medium"
    :voice_id "bm_george"
    :color "#38bdf8"
    :angle_deg -18}
   {:id "nexus"
    :petname "nexus"
    :label "Nexus"
    :role "Infra, DevOps & Impl"
    :model "claude-sonnet-5-5-high"
    :voice_id "am_adam"
    :color "#a78bfa"
    :angle_deg 54}
   {:id "vector"
    :petname "vector"
    :label "Vector"
    :role "Einsum, Hive & RL"
    :model "gemini-4-carbon-medium"
    :voice_id "bm_lewis"
    :color "#34d399"
    :angle_deg 126}
   {:id "lumen"
    :petname "lumen"
    :label "Lumen"
    :role "Protocol & BIP Research"
    :model "gemini-4-hydrogen-high"
    :voice_id "af_heart"
    :color "#fb7185"
    :angle_deg 198}])

(def ^:private voice-by-agent
  (into {} (map (juxt :id :voice_id) (default-petname-voices))))

(def trace-catalog
  "Published, SHA-256-verified Live 5-Seat Hive traces available for browser playback."
  [{:run_id "live-run-3"
    :title "Live Run-3: Whole-Stack Top Loop (bitecho + mealy + hive)"
    :subtitle "3 rounds · 64 turns · :fixed-point-2 · Reward 1.0 · Unused (epoch, seq+1) Rollback Revocation Catch"
    :jsonl_cid "379dc979165813af25a34764b512ccfae6780e70c695a26454cda6abdab4dfe0"
    :merkle_root "f2027bb27b96e8b814703c9c7d739ca70d67c2af0070176132b30289618e2d6f"
    :local_path "/tmp/hive-live-run3/live-run-3-trace.jsonl"}
   {:run_id "live-run-2"
    :title "Live Run-2: Contact Book, Circles, Human Anchor & Encrypted Memory Chain"
    :subtitle "3 rounds · 64 turns · :fixed-point-2 · Reward 1.0 · 3-BIP Memento/Circles Split + CAS Head Record"
    :jsonl_cid "9cacb04aa3d1008ff3ede2d5ab23e089d0bf862e999016478a5a61e8a03324f1"
    :merkle_root "9bbf36a70eeabdbe462a2b0ef55d0b6b60d686f3b829e6703fbaf66cbe2f7a80"
    :local_path "/tmp/hive-live-run2/live-run-2-trace.jsonl"}])

(defn sanitize-speech-text
  "Cleans markdown formatting, backticks, and long 64-char hex CIDs so Kokoro TTS
   reads twin-circle responses naturally."
  [raw]
  (-> (str raw)
      (str/replace #"\*\*" "")
      (str/replace #"`+" "")
      (str/replace #"[#]{1,6}\s*" "")
      ;; Shorten 64-char SHA-256 hex strings to spoken short prefix
      (str/replace #"\b([0-9a-fA-F]{8})[0-9a-fA-F]{56}\b" "$1")
      ;; Strip repetitive twin disclaimer boilerplate for smoother audio playback
      (str/replace #"(?i)\(advisory only[;,]?\s*(simulated consent is )?never real consent\.?\)" "")
      (str/replace #"(?i)\(advisory only[;,]?\s*not real consent\.?\)" "")
      (str/replace #"\s+" " ")
      str/trim))

(defn- extract-verdict
  [row]
  (let [verb (get-in row [:action_structured :verb])
        upper (str/upper-case (str (:raw_output row)))]
    (cond
      (string? verb) verb
      (re-find #"PARAMOUNT[-_\s]OBJECTION" upper) "paramount-objection"
      (or (re-find #"CONSENT[-_\s]WITH[-_\s]ADDENDUM" upper)
          (re-find #"CONSENT[-_\s]WITH[-_\s]FLAGS" upper)) "consent-with-addendum"
      (= "consent-round" (:stage row)) "consent"
      :else nil)))

(defn parse-trace-jsonl
  "Parses an R-3 v2 JSONL trace string into clean, UI-ready turn maps annotated
   with each agent's petname `voice_id`, `verdict`, and `speech_text`."
  [jsonl-text]
  (->> (str/split-lines (str jsonl-text))
       (remove str/blank?)
       (mapv (fn [line]
               (let [row (json/read-str line :key-fn keyword)
                     agent (str (:agent_id row))
                     raw (str (or (:raw_output row) ""))
                     action-type (str (or (:action_type row) "message"))
                     pass? (or (= "pass" action-type) (= "PASS" (str/trim raw)))]
                 {:turn_seq (:turn_seq row)
                  :agent_id agent
                  :voice_id (get voice-by-agent agent "af_heart")
                  :model_selector (:model_selector row)
                  :phase (:phase row)
                  :round (:round row)
                  :stage (:stage row)
                  :action_type action-type
                  :is_pass pass?
                  :verdict (extract-verdict row)
                  :latency_ms (:latency_ms row)
                  :merkle_root (:merkle_root row)
                  :raw_output raw
                  :speech_text (if pass? "" (sanitize-speech-text raw))})))))

;; ---------------------------------------------------------------------------
;; Boundary Shell: Verified Trace Loading & JDK HttpServer
;; ---------------------------------------------------------------------------

(defn- load-verified-trace-jsonl!
  [{:keys [local_path jsonl_cid]}]
  (let [f (when local_path (io/file local_path))]
    (if (and f (.isFile f))
      (let [txt (slurp f)]
        (if (= jsonl_cid (env/sha256-str txt))
          txt
          (:text (runner/fetch-verified-artifact! {:cid jsonl_cid}))))
      (:text (runner/fetch-verified-artifact! {:cid jsonl_cid})))))

(defn- send-response!
  [^HttpExchange ex status content-type ^bytes body]
  (let [hdrs (.getResponseHeaders ex)]
    (.set hdrs "Content-Type" content-type)
    (.set hdrs "Cache-Control" "no-cache")
    (.set hdrs "Access-Control-Allow-Origin" "*")
    (.sendResponseHeaders ex (int status) (long (alength body)))
    (with-open [os (.getResponseBody ex)]
      (.write os body))))

(defn- handle-request!
  [^HttpExchange ex]
  (let [path (.getPath (.getRequestURI ex))]
    (try
      (cond
        (or (= path "/") (= path "/index.html"))
        (let [res (io/resource "trace_viz/index.html")
              html (if res (slurp res) (slurp "resources/trace_viz/index.html"))]
          (send-response! ex 200 "text/html; charset=utf-8" (.getBytes html "UTF-8")))

        (= path "/api/petnames")
        (let [body (json/write-str {:petnames (default-petname-voices)})]
          (send-response! ex 200 "application/json; charset=utf-8" (.getBytes body "UTF-8")))

        (= path "/api/traces")
        (let [body (json/write-str {:traces (mapv #(dissoc % :local_path) trace-catalog)})]
          (send-response! ex 200 "application/json; charset=utf-8" (.getBytes body "UTF-8")))

        (str/starts-with? path "/api/trace/")
        (let [run-id (subs path (count "/api/trace/"))
              entry (first (filter #(= run-id (:run_id %)) trace-catalog))]
          (if-not entry
            (send-response! ex 404 "application/json; charset=utf-8"
                            (.getBytes (json/write-str {:error "trace-not-found" :run_id run-id}) "UTF-8"))
            (let [jsonl (load-verified-trace-jsonl! entry)
                  turns (parse-trace-jsonl jsonl)
                  body (json/write-str {:run_id run-id
                                        :title (:title entry)
                                        :subtitle (:subtitle entry)
                                        :jsonl_cid (:jsonl_cid entry)
                                        :merkle_root (:merkle_root entry)
                                        :advisory_only true
                                        :turns turns})]
              (send-response! ex 200 "application/json; charset=utf-8" (.getBytes body "UTF-8")))))

        :else
        (send-response! ex 404 "text/plain; charset=utf-8" (.getBytes "Not Found" "UTF-8")))
      (catch Throwable t
        (let [err (json/write-str {:error (.getMessage t)})]
          (send-response! ex 500 "application/json; charset=utf-8" (.getBytes err "UTF-8")))))))

(defn build-standalone-html!
  "Builds a completely self-contained static HTML file with the petname voice registry
   and all SHA-256-verified traces (Run-3 & Run-2) inlined into `window.__HIVE_STANDALONE__`,
   so it renders and plays anywhere with zero backend required."
  [out-path]
  (let [res (io/resource "trace_viz/index.html")
        template (if res (slurp res) (slurp "resources/trace_viz/index.html"))
        trace-data (into {}
                         (map (fn [entry]
                                (let [jsonl (load-verified-trace-jsonl! entry)
                                      turns (parse-trace-jsonl jsonl)]
                                  [(:run_id entry)
                                   {:run_id (:run_id entry)
                                    :title (:title entry)
                                    :subtitle (:subtitle entry)
                                    :jsonl_cid (:jsonl_cid entry)
                                    :merkle_root (:merkle_root entry)
                                    :advisory_only true
                                    :turns turns}]))
                              trace-catalog))
        payload-json (-> (json/write-str {:petnames (default-petname-voices)
                                          :traces (mapv #(dissoc % :local_path) trace-catalog)
                                          :traceData trace-data})
                         (str/replace "</" "<\\/"))
        injection (str "<script>window.__HIVE_STANDALONE__ = " payload-json ";</script>\n<script type=\"module\">")
        standalone (str/replace-first template "<script type=\"module\">" injection)]
    (spit out-path standalone)
    {:path out-path :bytes (count (.getBytes standalone "UTF-8")) :cid (env/sha256-str standalone)}))

(defn start-server!
  "Starts the Hive Trace Audio Visualizer HTTP server on `host:port` (default `0.0.0.0:8787`)."
  ([] (start-server! "0.0.0.0" 8787))
  ([port] (start-server! "0.0.0.0" port))
  ([host port]
   (let [srv (HttpServer/create (InetSocketAddress. (str host) (int port)) 0)]
     (.createContext srv "/" (reify HttpHandler
                               (handle [_ ex] (handle-request! ex))))
     (.setExecutor srv nil)
     (.start srv)
     (println (format "[trace-viz] Hive Trace Audio Visualizer running at http://%s:%d/" host port))
     srv)))

(defn -main
  "CLI entrypoint (`clojure -M:trace-viz [--host 0.0.0.0] [--port 8787] [--build-standalone path]`)."
  [& args]
  (let [arg-map (into {} (map vec (partition 2 1 args)))
        standalone-out (get arg-map "--build-standalone")]
    (if standalone-out
      (do
        (println "[trace-viz] Built standalone HTML:" (pr-str (build-standalone-html! standalone-out)))
        (shutdown-agents))
      (let [host (or (get arg-map "--host") "0.0.0.0")
            port (if-let [p (get arg-map "--port")] (Integer/parseInt p) 8787)]
        (start-server! host port)
        @(promise)))))
