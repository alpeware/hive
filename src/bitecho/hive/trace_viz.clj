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
            [clojure.string :as str]
            [einsum.core :as ein]
            [einsum.models.kokoro :as kokoro])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io ByteArrayOutputStream]
           [java.net HttpURLConnection InetSocketAddress URI]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Pure Core: Petname Voice Registry, Sanitization, SRI & HTML Validation
;; ---------------------------------------------------------------------------

(def browser-bundle-sri
  "Pinned SHA-384 SRI for `bitecho-browser.js` v0.23.19 (CID ecf56ca5...)."
  "sha384-xz3vl27CBqKMBOVMkuIM62Gq93H7zMhor9oXUMoi6PRWrUHQXDEE0rIE8zxF5/sL")

(defn sha256-bytes
  "Pure SHA-256 hex digest of a byte array."
  ^String [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")
        digest (.digest md bs)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn sha384-sri
  "Pure Subresource Integrity (`sha384-<base64>`) digest of a byte array."
  ^String [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-384")
        digest (.digest md bs)]
    (str "sha384-" (.encodeToString (Base64/getEncoder) digest))))

(defn validate-html-scripts
  "Pure BIP-017b HTML validator asserting:
   1. ZERO inline `<script>` tags (every `<script>` must have `src` and empty body).
   2. ZERO `cdn.jsdelivr.net` or `/api/bitecho-browser.js` references.
   3. Every `<script src=\"./...\">` carries an `integrity=\"sha384-...\"` matching
      `expected-sri-by-src` when supplied."
  [html expected-sri-by-src]
  (let [s (str html)
        script-re #"(?is)<script\b([^>]*)>(.*?)</script>"
        matches (re-seq script-re s)
        errors (atom [])
        scripts (atom [])]
    (when (str/includes? s "cdn.jsdelivr.net")
      (swap! errors conj {:kind :external-cdn :message "Forbidden cdn.jsdelivr.net reference"}))
    (when (str/includes? s "/api/bitecho-browser.js")
      (swap! errors conj {:kind :proxy-shim :message "Forbidden /api/bitecho-browser.js proxy reference"}))
    (doseq [[_ attrs body] matches]
      (let [src (second (re-find #"(?i)\bsrc\s*=\s*\"([^\"]+)\"" attrs))
            integrity (second (re-find #"(?i)\bintegrity\s*=\s*\"([^\"]+)\"" attrs))
            trimmed-body (str/trim (str body))]
        (swap! scripts conj {:src src :integrity integrity})
        (when (or (str/blank? src) (not (str/blank? trimmed-body)))
          (swap! errors conj {:kind :inline-script
                              :src src
                              :message "Inline <script> tag or non-empty script body is forbidden"}))
        (when (and src (str/includes? src "cdn.jsdelivr.net"))
          (swap! errors conj {:kind :external-cdn :src src}))
        (when (and src (contains? expected-sri-by-src src))
          (let [expected (get expected-sri-by-src src)]
            (when (not= expected integrity)
              (swap! errors conj {:kind :sri-mismatch
                                  :src src
                                  :expected expected
                                  :actual integrity}))))))
    {:ok? (empty? @errors)
     :errors @errors
     :scripts @scripts}))

(defn render-production-chat-html
  "Pure transform turning dev `chat.html` into zero-proxy production `index.html`
   for `bitecho.org/app/` with exact SHA-384 SRI pins and `./trace-viz.html` nav link."
  [chat-html chat-js-sri]
  (-> (str chat-html)
      (str/replace "src=\"/api/bitecho-browser.js\"" "src=\"./bitecho-browser.js\"")
      (str/replace "<script type=\"module\" src=\"/chat.js\"></script>"
                   (str "<script type=\"module\" src=\"./chat.js\" integrity=\""
                        chat-js-sri
                        "\" crossorigin=\"anonymous\"></script>"))
      (str/replace "href=\"/\"" "href=\"./trace-viz.html\"")))

(defn render-production-trace-viz-html
  "Pure transform turning dev `index.html` into zero-proxy production `trace-viz.html`
   for `bitecho.org/app/` with exact SHA-384 SRI pin and `./index.html` nav link."
  [trace-viz-html trace-viz-js-sri]
  (-> (str trace-viz-html)
      (str/replace "<script type=\"module\" src=\"/trace-viz.js\"></script>"
                   (str "<script type=\"module\" src=\"./trace-viz.js\" integrity=\""
                        trace-viz-js-sri
                        "\" crossorigin=\"anonymous\"></script>"))
      (str/replace "href=\"/chat\"" "href=\"./index.html\"")))

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

(defonce ^:private kokoro-webgpu-buckets-cache
  (delay (kokoro/compile-kokoro-webgpu-buckets)))

(defonce ^:private uop-cpu-ctx-cache
  (delay (ein/init-backend! :uop-cpu {:quiet? true})))

(def ^:private voice-base-f0
  {"am_michael" 118.0
   "bm_george"  132.0
   "am_adam"    126.0
   "bm_lewis"   140.0
   "af_heart"   215.0})

(defn synthesize-turn-uop-cpu!
  "Synthesizes an audible 24 kHz WAV vocoder preview directly through `clj-einsum` `:uop-cpu`
   (`kokoro/sine-gen` + chunked `1024`-frame `kokoro/run-custom-istft!` `:conv-transpose1d` +
   peak normalization to 0.75 + `pcm-f32->wav-bytes`).
   Returns `{:wav-bytes :latency-ms :bucket :samples :duration-ms :peak :rms}`."
  [text voice-id]
  (let [t0 (System/nanoTime)
        clean (let [s (sanitize-speech-text text)] (if (str/blank? s) "Hello from Bitecho Hive" s))
        ;; 1024 STFT frames @ hop=5 = 5,115 samples (~213ms/block); run 5..8 blocks (~1.1s..1.7s)
        n-blocks (max 5 (min 8 (quot (+ (count clean) 24) 20)))
        bucket 1024
        total-frames (* n-blocks bucket)
        n-fft 20
        hop 5
        n-bins (inc (quot n-fft 2))
        base-f0 (double (get voice-base-f0 voice-id 165.0))
        ;; Build speech-cadence F0 contour across the full multi-block duration
        f0-steps (quot total-frames 16)
        f0-arr (float-array f0-steps)
        _ (dotimes [i f0-steps]
            (let [ch (int (.charAt clean (mod i (count clean))))
                  voiced? (and (not= ch 32) (not= ch 46) (not= ch 44))
                  pitch (+ base-f0 (* 28.0 (Math/sin (* 0.18 (double i)))) (* 0.5 (double (mod ch 31))))]
              (aset f0-arr i (float (if voiced? pitch 0.0)))))
        sg (kokoro/sine-gen f0-arr {:sample-rate 24000 :upsample-scale 16 :harmonic-num 8 :voiced-threshold 10.0 :sine-amp 0.8})
        ^floats waves (:sine-waves sg)
        ;; Synthesize each 1024-frame bucket through compiled :uop-cpu CustomSTFT ConvTranspose1d
        block-pcms
        (mapv (fn [blk]
                (let [f-off (* blk bucket)
                      mag (float-array (* n-bins bucket))
                      phase (float-array (* n-bins bucket))]
                  (dotimes [k n-bins]
                    (let [formant (Math/exp (* -0.22 (double k)))]
                      (dotimes [f bucket]
                        (let [gf (+ f-off f)
                              w-val (double (aget waves (+ (* (mod k 9) total-frames) gf)))
                              env-amp (+ 0.15 (Math/abs w-val))
                              idx (+ (* k bucket) f)]
                          (aset mag idx (float (* formant env-amp)))
                          (aset phase idx (float (* 2.0 Math/PI (double (mod (* (+ k 1) gf) 37)) (/ 1.0 37.0))))))))
                  (kokoro/run-custom-istft! @uop-cpu-ctx-cache mag phase {:frames bucket :n-fft n-fft :hop-length hop})))
              (range n-blocks))
        total-samples (reduce + 0 (map #(alength ^floats %) block-pcms))
        raw-pcm (float-array total-samples)
        _ (loop [bs block-pcms off 0]
            (when (seq bs)
              (let [^floats b (first bs)
                    n (alength b)]
                (System/arraycopy b 0 raw-pcm off n)
                (recur (rest bs) (+ off n)))))
        ;; Peak-normalize to 0.75 (-2.5 dBFS) so playback is loud & clear
        raw-peak (loop [i 0 p 0.0]
                   (if (>= i total-samples)
                     p
                     (recur (inc i) (max p (Math/abs (double (aget raw-pcm i)))))))
        gain (if (> raw-peak 1e-6) (/ 0.75 raw-peak) 1.0)
        pcm (float-array total-samples)
        sum-sq (loop [i 0 acc 0.0]
                 (if (>= i total-samples)
                   acc
                   (let [v (* (double (aget raw-pcm i)) gain)]
                     (aset pcm i (float v))
                     (recur (inc i) (+ acc (* v v))))))
        rms (Math/sqrt (/ sum-sq (double (max 1 total-samples))))
        peak (min 1.0 (* raw-peak gain))
        wav (kokoro/pcm-f32->wav-bytes pcm 24000)
        lat-ms (long (/ (- (System/nanoTime) t0) 1e6))
        dur-ms (long (Math/round (* 1000.0 (/ (double total-samples) 24000.0))))]
    (println (format "[trace-viz] /api/tts/uop-cpu voice=%s blocks=%d dur=%dms peak=%.3f rms=%.3f lat=%dms"
                     voice-id n-blocks dur-ms peak rms lat-ms))
    {:wav-bytes wav
     :latency-ms lat-ms
     :bucket bucket
     :samples total-samples
     :duration-ms dur-ms
     :peak peak
     :rms rms}))

(def ^:private upstream-browser-bundle-url
  "https://bitecho.org/app/bitecho-browser.js")

(defn- fetch-browser-bundle-bytes!
  "Fetches the live `bitecho-browser.js` bundle bytes from `bitecho.org` on each
   request so same-origin `/chat` avoids CORS blocks while staying in lockstep
   with upstream pointer flips and preserving exact SRI verification."
  ^bytes []
  (let [conn (.openConnection (.toURL (URI. upstream-browser-bundle-url)))]
    (.setConnectTimeout conn 10000)
    (.setReadTimeout conn 15000)
    (with-open [is (.getInputStream conn)
                baos (ByteArrayOutputStream.)]
      (io/copy is baos)
      (.toByteArray baos))))

(defn- send-response!
  [^HttpExchange ex status content-type ^bytes body]
  (let [hdrs (.getResponseHeaders ex)]
    (.set hdrs "Content-Type" content-type)
    (.set hdrs "Cache-Control" "no-cache")
    (.set hdrs "Access-Control-Allow-Origin" "*")
    (.sendResponseHeaders ex (int status) (long (alength body)))
    (with-open [os (.getResponseBody ex)]
      (.write os body))))

(def ^:private upstream-relay-origins
  ["https://ns2.bitecho.org"
   "https://ns1.bitecho.org"
   "https://bitecho.org"])

(defn- read-stream-bytes
  ^bytes [is]
  (if (nil? is)
    (byte-array 0)
    (with-open [in is
                baos (ByteArrayOutputStream.)]
      (io/copy in baos)
      (.toByteArray baos))))

(defn- proxy-relay-request!
  "Fetch-through proxy for `/relay/v1/*` (GET/POST/OPTIONS) preserving query string,
   request body, Content-Type, and upstream HTTP status so the browser bundle on
   `http://<lan>:8787/chat` never hits 404 or cross-origin CORS blocks."
  [^HttpExchange ex]
  (let [method (.getRequestMethod ex)]
    (if (= method "OPTIONS")
      (let [hdrs (.getResponseHeaders ex)]
        (.set hdrs "Access-Control-Allow-Origin" "*")
        (.set hdrs "Access-Control-Allow-Methods" "GET, POST, OPTIONS")
        (.set hdrs "Access-Control-Allow-Headers" "Content-Type, Authorization")
        (.sendResponseHeaders ex 204 -1)
        (.close ex))
      (let [uri (.getRequestURI ex)
            path (.getRawPath uri)
            query (.getRawQuery uri)
            suffix (if (str/blank? query) path (str path "?" query))
            req-ct (.getFirst (.getRequestHeaders ex) "Content-Type")
            req-body (if (#{"POST" "PUT"} method)
                       (read-stream-bytes (.getRequestBody ex))
                       nil)
            res (loop [origins upstream-relay-origins
                       last-err nil]
                  (if-let [origin (first origins)]
                    (let [attempt (try
                                    (let [^HttpURLConnection conn
                                          (.openConnection (.toURL (URI. (str origin suffix))))]
                                      (.setRequestMethod conn method)
                                      (.setConnectTimeout conn 8000)
                                      (.setReadTimeout conn 15000)
                                      (when req-ct
                                        (.setRequestProperty conn "Content-Type" req-ct))
                                      (when (and req-body (pos? (alength req-body)))
                                        (.setDoOutput conn true)
                                        (with-open [os (.getOutputStream conn)]
                                          (.write os req-body)))
                                      (let [code (.getResponseCode conn)
                                            ct (or (.getContentType conn) "application/json; charset=utf-8")
                                            body (if (< code 400)
                                                   (read-stream-bytes (.getInputStream conn))
                                                   (read-stream-bytes (.getErrorStream conn)))]
                                        {:ok? true :status code :content-type ct :body body}))
                                    (catch Throwable t
                                      {:ok? false :error (.getMessage t)}))]
                      (if (:ok? attempt)
                        attempt
                        (recur (rest origins) (:error attempt))))
                    {:ok? false :error (or last-err "relay-proxy-unreachable")}))]
        (if (:ok? res)
          (send-response! ex (:status res) (:content-type res) (:body res))
          (send-response! ex 502 "application/json; charset=utf-8"
                          (.getBytes (json/write-str {:ok false :error (:error res)}) "UTF-8")))))))

(defn- handle-request!
  [^HttpExchange ex]
  (let [path (.getPath (.getRequestURI ex))]
    (try
      (cond
        (= path "/favicon.ico")
        (do
          (.sendResponseHeaders ex 204 -1)
          (.close ex))

        (str/starts-with? path "/relay/v1/")
        (proxy-relay-request! ex)

        (= path "/api/bitecho-browser.js")
        (send-response! ex 200 "application/javascript; charset=utf-8"
                        (fetch-browser-bundle-bytes!))

        (str/starts-with? path "/api/artifact/v1/blob/")
        (let [cid (subs path (count "/api/artifact/v1/blob/"))
              conn (.openConnection (.toURL (URI. (str "https://ns2.bitecho.org/artifact/v1/blob/" cid))))]
          (.setConnectTimeout conn 10000)
          (.setReadTimeout conn 15000)
          (with-open [is (.getInputStream conn)
                      baos (ByteArrayOutputStream.)]
            (io/copy is baos)
            (send-response! ex 200 "application/octet-stream" (.toByteArray baos))))

        (or (= path "/") (= path "/index.html"))
        (let [res (io/resource "trace_viz/index.html")
              html (if res (slurp res) (slurp "resources/trace_viz/index.html"))]
          (send-response! ex 200 "text/html; charset=utf-8" (.getBytes html "UTF-8")))

        (or (= path "/chat") (= path "/chat.html"))
        (let [res (io/resource "trace_viz/chat.html")
              html (if res (slurp res) (slurp "resources/trace_viz/chat.html"))]
          (send-response! ex 200 "text/html; charset=utf-8" (.getBytes html "UTF-8")))

        (= path "/chat.js")
        (let [res (io/resource "trace_viz/chat.js")
              js (if res (slurp res) (slurp "resources/trace_viz/chat.js"))]
          (send-response! ex 200 "application/javascript; charset=utf-8" (.getBytes js "UTF-8")))

        (= path "/trace-viz.js")
        (let [res (io/resource "trace_viz/trace-viz.js")
              js (if res (slurp res) (slurp "resources/trace_viz/trace-viz.js"))]
          (send-response! ex 200 "application/javascript; charset=utf-8" (.getBytes js "UTF-8")))

        (= path "/api/petnames")
        (let [body (json/write-str {:petnames (default-petname-voices)})]
          (send-response! ex 200 "application/json; charset=utf-8" (.getBytes body "UTF-8")))

        (= path "/api/traces")
        (let [body (json/write-str {:traces (mapv #(dissoc % :local_path) trace-catalog)})]
          (send-response! ex 200 "application/json; charset=utf-8" (.getBytes body "UTF-8")))

        (= path "/api/kokoro-webgpu-buckets")
        (let [bundles @kokoro-webgpu-buckets-cache
              body (json/write-str bundles)]
          (send-response! ex 200 "application/json; charset=utf-8" (.getBytes body "UTF-8")))

        (= path "/api/tts/uop-cpu")
        (let [req-body (slurp (.getRequestBody ex))
              parsed (if (str/blank? req-body) {} (json/read-str req-body :key-fn keyword))
              text (str (or (:text parsed) "Hello from Bitecho Hive"))
              voice (str (or (:voice parsed) "af_heart"))
              {:keys [wav-bytes latency-ms bucket samples]} (synthesize-turn-uop-cpu! text voice)
              hdrs (.getResponseHeaders ex)]
          (.set hdrs "X-Einsum-Backend" "clj-einsum:uop-cpu")
          (.set hdrs "X-Einsum-Latency-Ms" (str latency-ms))
          (.set hdrs "X-Einsum-Bucket" (str bucket))
          (.set hdrs "X-Einsum-Samples" (str samples))
          (.set hdrs "Access-Control-Expose-Headers" "X-Einsum-Backend, X-Einsum-Latency-Ms, X-Einsum-Bucket, X-Einsum-Samples")
          (send-response! ex 200 "audio/wav" wav-bytes))

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

(defn- read-trace-viz-resource
  ^String [rel-name]
  (let [res (io/resource (str "trace_viz/" rel-name))]
    (if res
      (slurp res)
      (slurp (str "resources/trace_viz/" rel-name)))))

(defn build-standalone-html!
  "Builds a completely self-contained static HTML file with the petname voice registry
   and all SHA-256-verified traces (Run-3 & Run-2) inlined into `window.__HIVE_STANDALONE__`,
   so it renders and plays anywhere with zero backend required."
  [out-path]
  (let [template (read-trace-viz-resource "index.html")
        viz-js (read-trace-viz-resource "trace-viz.js")
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
        injection (str "<script>window.__HIVE_STANDALONE__ = " payload-json ";</script>\n"
                       "<script type=\"module\">\n" viz-js "\n</script>")
        standalone (str/replace-first template "<script type=\"module\" src=\"/trace-viz.js\"></script>" injection)]
    (spit out-path standalone)
    {:path out-path :bytes (count (.getBytes standalone "UTF-8")) :cid (env/sha256-str standalone)}))

(defn build-web-app!
  "Builds the zero-proxy production `bitecho.org/app/` static web app bundle into `out-dir`:
   - `index.html` (Hive Live Chat UI with exact SHA-384 SRI pins, ZERO inline scripts, ZERO `/api/` proxy URLs)
   - `chat.js` (externalized zero-CDN module using `BitechoLightNode` v1.1.0 + OPFS chunked loader)
   - `trace-viz.html` (5-Seat Trace Visualizer with exact SHA-384 SRI pin, ZERO inline scripts)
   - `trace-viz.js` (externalized zero-CDN module)
   - `release-assets.edn` (exact SHA-256 CID, byte size, `media-type`, and `sha384-...` SRI for every emitted file)."
  [out-dir]
  (let [dir (io/file out-dir)
        _ (.mkdirs dir)
        chat-html-raw (read-trace-viz-resource "chat.html")
        chat-js-raw (read-trace-viz-resource "chat.js")
        viz-html-raw (read-trace-viz-resource "index.html")
        viz-js-raw (read-trace-viz-resource "trace-viz.js")
        chat-js-bytes (.getBytes chat-js-raw StandardCharsets/UTF_8)
        viz-js-bytes (.getBytes viz-js-raw StandardCharsets/UTF_8)
        chat-js-sri (sha384-sri chat-js-bytes)
        viz-js-sri (sha384-sri viz-js-bytes)
        index-html (render-production-chat-html chat-html-raw chat-js-sri)
        trace-viz-html (render-production-trace-viz-html viz-html-raw viz-js-sri)
        val-index (validate-html-scripts index-html {"./bitecho-browser.js" browser-bundle-sri
                                                     "./chat.js" chat-js-sri})
        val-viz (validate-html-scripts trace-viz-html {"./trace-viz.js" viz-js-sri})]
    (when-not (:ok? val-index)
      (throw (ex-info "Production index.html failed BIP-017b script/SRI validation" val-index)))
    (when-not (:ok? val-viz)
      (throw (ex-info "Production trace-viz.html failed BIP-017b script/SRI validation" val-viz)))
    (let [index-bytes (.getBytes index-html StandardCharsets/UTF_8)
          viz-bytes (.getBytes trace-viz-html StandardCharsets/UTF_8)
          files [[:index-html "index.html" "text/html; charset=utf-8" index-bytes]
                 [:chat-js "chat.js" "application/javascript; charset=utf-8" chat-js-bytes]
                 [:trace-viz-html "trace-viz.html" "text/html; charset=utf-8" viz-bytes]
                 [:trace-viz-js "trace-viz.js" "application/javascript; charset=utf-8" viz-js-bytes]]
          assets (reduce
                  (fn [acc [k fname media-type ^bytes bs]]
                    (let [f (io/file dir fname)]
                      (Files/write (.toPath f) bs (make-array java.nio.file.OpenOption 0))
                      (assoc acc k {:file fname
                                    :cid (sha256-bytes bs)
                                    :size (alength bs)
                                    :media-type media-type
                                    :sri (sha384-sri bs)})))
                  {}
                  files)
          release-assets {:schema :bitecho/hive-web-app-assets-v1
                          :browser-bundle-sri browser-bundle-sri
                          :assets assets}
          manifest-str (str (pr-str release-assets) "\n")
          manifest-bytes (.getBytes manifest-str StandardCharsets/UTF_8)
          manifest-file (io/file dir "release-assets.edn")]
      (Files/write (.toPath manifest-file) manifest-bytes (make-array java.nio.file.OpenOption 0))
      {:ok? true
       :out-dir (.getAbsolutePath dir)
       :manifest-path (.getAbsolutePath manifest-file)
       :manifest-cid (sha256-bytes manifest-bytes)
       :assets assets})))

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
  "CLI entrypoint (`clojure -M:trace-viz [--host 0.0.0.0] [--port 8787] [--build-standalone path] [--build-web-app out-dir]`)."
  [& args]
  (let [arg-map (into {} (map vec (partition 2 1 args)))
        standalone-out (get arg-map "--build-standalone")
        web-app-out (get arg-map "--build-web-app")]
    (cond
      web-app-out
      (do
        (println "[trace-viz] Built production web app:" (pr-str (build-web-app! web-app-out)))
        (shutdown-agents))

      standalone-out
      (do
        (println "[trace-viz] Built standalone HTML:" (pr-str (build-standalone-html! standalone-out)))
        (shutdown-agents))

      :else
      (let [host (or (get arg-map "--host") "0.0.0.0")
            port (if-let [p (get arg-map "--port")] (Integer/parseInt p) 8787)]
        (start-server! host port)
        @(promise)))))
