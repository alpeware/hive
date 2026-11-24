(ns bitecho.hive.trace-viz-test
  "Strict TDD tests for `bitecho.hive.trace-viz` pure trace parsing, petname voice mapping,
   TTS text sanitization, SHA-384 SRI computation, HTML script validation, and zero-proxy
   BIP-017b production web app builder."
  (:require [bitecho.hive.trace-viz :as viz]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defspec ^{:doc "TTS text sanitization always strips markdown emphasis/backticks and bounds long hex CIDs cleanly."}
  prop-sanitize-speech-text-clean-and-bounded 50
  (prop/for-all [raw gen/string-ascii]
                (let [cleaned (viz/sanitize-speech-text raw)]
                  (and (string? cleaned)
                       (not (str/includes? cleaned "**"))
                       (not (str/includes? cleaned "`"))))))

(defspec ^{:doc "SHA-384 SRI helper is deterministic and always emits valid `sha384-<64-char-base64>`."}
  prop-sha384-sri-format-and-determinism 50
  (prop/for-all [bs gen/bytes]
                (let [s1 (viz/sha384-sri bs)
                      s2 (viz/sha384-sri bs)]
                  (and (= s1 s2)
                       (str/starts-with? s1 "sha384-")
                       (= 71 (count s1))
                       (boolean (re-matches #"^sha384-[A-Za-z0-9+/=]{64}$" s1))))))

(defspec ^{:doc "HTML script validator rejects any HTML containing an inline <script> tag."}
  prop-validate-html-scripts-rejects-inline-scripts 30
  (prop/for-all [body (gen/such-that #(not (str/includes? % "</script>")) gen/string-ascii)]
                (let [html (str "<!DOCTYPE html><html><head></head><body><script>" body "</script></body></html>")
                      res (viz/validate-html-scripts html {})]
                  (and (false? (:ok? res))
                       (boolean (some #(= :inline-script (:kind %)) (:errors res)))))))

(def ^:private gen-relay-entry
  (gen/hash-map
   :id (gen/elements ["ns1" "ns2" "ns3"])
   :url (gen/elements ["https://ns1.bitecho.org" "https://ns2.bitecho.org" "https://ns3.bitecho.org"])
   :healthy? gen/boolean
   :in-cooldown? gen/boolean
   :rtt-ms (gen/choose 5 2000)
   :finalized-epoch (gen/choose 80900 80950)))

(defspec ^{:doc "BIP-018 Track 3: Relay health pill classification and mechanical :relay-degraded vs :chain-stalled invariants."}
  prop-relay-health-pill-classification 50
  (prop/for-all [relays (gen/vector gen-relay-entry 1 5)
                 cluster-advancing? (gen/elements [nil true false])]
                (let [res (viz/classify-relay-health relays {:cluster-advancing? cluster-advancing?})
                      h (:healthy-count res)
                      t (:total-count res)]
                  (and (= t (count relays))
                       (<= 0 h t)
                       (= (:label res) (str "Relays: " h "/" t))
                       (cond
                         (= h t) (and (= :healthy (:status res))
                                      (= "pill ok" (:pill-class res)))
                         (pos? h) (and (= :degraded (:status res))
                                       (= "pill warn" (:pill-class res)))
                         :else (and (= :down (:status res))
                                    (= "pill live" (:pill-class res))))
                       (cond
                         (false? cluster-advancing?) (= :chain-stalled (:blast-radius res))
                         (= h t) (= :ok (:blast-radius res))
                         (pos? h) (= :relay-degraded (:blast-radius res))
                         :else (= :chain-stalled (:blast-radius res)))))))

(defspec ^{:doc "BIP-018 Track 3: Live BIP-017a chunk progress formatting invariants."}
  prop-chunk-progress-format-invariants 50
  (prop/for-all [label (gen/elements ["Moonshine" "ORT WASM" "Kokoro-82M" ""])
                 total (gen/choose 1 120)
                 completed (gen/choose -5 130)
                 relay (gen/elements ["https://ns1.bitecho.org"
                                      "https://ns2.bitecho.org"
                                      "https://ns3.bitecho.org"
                                      "ns2"])
                 failovers (gen/choose 0 10)]
                (let [s (viz/format-chunk-progress {:label label
                                                    :completed completed
                                                    :total total
                                                    :relay relay
                                                    :failovers failovers})
                      clamped (min total (max 0 completed))
                      expected-lbl (if (str/blank? label) "Artifact" label)]
                  (and (string? s)
                       (str/starts-with? s (str expected-lbl " " clamped "/" total " via "))
                       (not (str/includes? s "https://"))
                       (cond
                         (zero? failovers) (not (str/includes? s "failover"))
                         (= 1 failovers) (str/ends-with? s " · 1 failover")
                         :else (str/ends-with? s (str " · " failovers " failovers")))))))

(defspec ^{:doc "BIP-020 Stage 3: #api-badge honesty — NEVER returns :live (green) when disconnected or degraded."}
  prop-mesh-badge-honesty-never-green-when-disconnected 100
  (prop/for-all [mesh-connected? gen/boolean
                 degraded? gen/boolean
                 relay-fallback-active? gen/boolean]
                (let [res (viz/classify-mesh-badge {:mesh-connected? mesh-connected?
                                                    :degraded? degraded?
                                                    :relay-fallback-active? relay-fallback-active?})]
                  (and (contains? #{:live :degraded :disconnected} (:state res))
                       (string? (:label res))
                       (not (str/blank? (:label res)))
                       (string? (:css-class res))
                       ;; Core honesty invariant: NEVER :live or green pill when disconnected or degraded
                       (if (or (not mesh-connected?) degraded?)
                         (and (not= :live (:state res))
                              (not= "pill ok" (:css-class res)))
                         (and (= :live (:state res))
                              (= "pill ok" (:css-class res))))
                       ;; Disconnected when neither mesh nor relay fallback is active (and not degraded)
                       (if (and (not mesh-connected?) (not relay-fallback-active?) (not degraded?))
                         (and (= :disconnected (:state res))
                              (= "pill live" (:css-class res)))
                         true)
                       ;; Degraded when degraded? or relay fallback active while mesh disconnected
                       (if (or degraded? (and (not mesh-connected?) relay-fallback-active?))
                         (and (= :degraded (:state res))
                              (= "pill warn" (:css-class res)))
                         true)))))

(def ^:private gen-evicted-map
  (gen/map (gen/elements [:capacity-overflow :expired :retry-exhausted :peer-closed])
           (gen/choose -10 500)))

(def ^:private gen-diagnose-event
  (gen/hash-map
   :reason (gen/elements [:ice-disconnected :relay-failover :outbox-evicted :broadcast-gap-detected])
   :ts (gen/choose 1000 999999)))

(defspec ^{:doc "BIP-020 Stage 3: Delivery telemetry summary normalizes Pillar 1-4 counters and bounds :diagnose ring-buffer to <= 64 entries."}
  prop-delivery-telemetry-summary-bounded-and-honest 100
  (prop/for-all [sent (gen/choose -20 5000)
                 acked (gen/choose -20 5000)
                 evicted-by-reason gen-evicted-map
                 unacked-aged (gen/choose -10 200)
                 mesh-submitted (gen/choose -10 5000)
                 p2p-direct (gen/choose -10 5000)
                 received-not-emitted (gen/one-of [(gen/choose -5 100)
                                                   (gen/map (gen/elements [:duplicate :filtered :decode-error])
                                                            (gen/choose -5 50))])
                 diag-events (gen/vector gen-diagnose-event 0 120)
                 mesh-connected? gen/boolean
                 relay-fallback-active? gen/boolean]
                (let [res (viz/summarize-delivery-telemetry
                           {:sent sent
                            :acked acked
                            :evicted_by_reason evicted-by-reason
                            :unacked_aged unacked-aged
                            :mesh_submitted mesh-submitted
                            :p2p_direct p2p-direct
                            :received_but_not_emitted received-not-emitted
                            :diagnose diag-events
                            :mesh_connected mesh-connected?
                            :relay_fallback_active relay-fallback-active?})
                      expected-diag (vec (take-last 64 diag-events))]
                  (and (= (:sent res) (max 0 sent))
                       (= (:acked res) (max 0 acked))
                       (= (:unacked_aged res) (max 0 unacked-aged))
                       (= (:mesh_submitted res) (max 0 mesh-submitted))
                       (= (:p2p_direct res) (max 0 p2p-direct))
                       (map? (:evicted_by_reason res))
                       (every? #(>= % 0) (vals (:evicted_by_reason res)))
                       (vector? (:diagnose res))
                       (<= (count (:diagnose res)) 64)
                       (= (:diagnose res) expected-diag)
                       (= (:mesh_connected res) mesh-connected?)
                       (= (:relay_fallback_active res) relay-fallback-active?)
                       (if (map? received-not-emitted)
                         (and (map? (:received_but_not_emitted res))
                              (every? #(>= % 0) (vals (:received_but_not_emitted res))))
                         (= (:received_but_not_emitted res) (max 0 received-not-emitted)))))))

(defspec ^{:doc "BIP-020 Stage 3: Outbound delivery status NEVER returns :sent when :ok is false or error is non-blank."}
  prop-outbound-delivery-status-never-sent-on-failure 100
  (prop/for-all [ok (gen/elements [true false nil])
                 queued gen/boolean
                 evicted gen/boolean
                 err (gen/elements [nil "" "mesh-disconnected" "outbox-full" "timeout"])]
                (let [status (viz/classify-outbound-delivery-status {:ok ok
                                                                     :queued queued
                                                                     :evicted evicted
                                                                     :error err})
                      has-err? (not (str/blank? (str err)))]
                  (and (contains? #{:sent :queued :evicted :rejected} status)
                       ;; NEVER :sent when :ok is not true or when an error occurred
                       (if (or (not (true? ok)) has-err?)
                         (not= :sent status)
                         true)
                       ;; Eviction always surfaces as :evicted
                       (if evicted
                         (= :evicted status)
                         true)
                       ;; Queued on ok (without eviction/error) surfaces as :queued
                       (if (and (true? ok) (not evicted) (not has-err?) queued)
                         (= :queued status)
                         true)
                       ;; Clean ok without queued/evicted/error surfaces as :sent
                       (if (and (true? ok) (not evicted) (not has-err?) (not queued))
                         (= :sent status)
                         true)))))

(defspec ^{:doc "BIP-021: Honest liveness projection — NEVER returns :live when mesh-open-peers == 0 or transport-healthy? is false."}
  prop-bip021-mesh-badge-honesty-with-liveness-fields 100
  (prop/for-all [mesh-open-peers (gen/choose 0 5)
                 transport-healthy? (gen/elements [true false nil])
                 degraded? gen/boolean
                 relay-fallback-active? gen/boolean]
                (let [res (viz/classify-mesh-badge {:mesh-connected? (pos? mesh-open-peers)
                                                    :mesh-open-peers mesh-open-peers
                                                    :transport-healthy? transport-healthy?
                                                    :degraded? degraded?
                                                    :relay-fallback-active? relay-fallback-active?})]
                  (and (contains? #{:live :degraded :disconnected} (:state res))
                       (if (or (zero? mesh-open-peers) (false? transport-healthy?) degraded?)
                         (and (not= :live (:state res))
                              (not= "pill ok" (:css-class res)))
                         (and (= :live (:state res))
                              (= "pill ok" (:css-class res))))
                       (if (and (zero? mesh-open-peers) relay-fallback-active?)
                         (and (= :degraded (:state res))
                              (= "DEGRADED (relay)" (:label res))
                              (= "pill warn" (:css-class res)))
                         true)))))

(defspec ^{:doc "BIP-021: Delivery & integrity telemetry normalizes liveness fields and BIP-021 diagnostic counters."}
  prop-bip021-diagnostics-counters-normalized 100
  (prop/for-all [mesh-open-peers (gen/choose -5 10)
                 connected? gen/boolean
                 transport-healthy? gen/boolean
                 relay-fb? gen/boolean
                 sub-rec (gen/choose -5 50)
                 dac-miss (gen/choose -5 50)
                 dac-inv (gen/choose -5 50)
                 sig-inv (gen/choose -5 50)
                 rb-det (gen/choose -5 50)
                 rep-det (gen/choose -5 50)]
                (let [res (viz/summarize-delivery-telemetry
                           {:connected connected?
                            :transport_healthy transport-healthy?
                            :mesh_open_peers mesh-open-peers
                            :relay_fallback_active relay-fb?
                            :diagnostics {:subnet-id-reconciled sub-rec
                                          :broadcast-dac-missing dac-miss
                                          :broadcast-dac-invalid dac-inv
                                          :broadcast-sig-invalid sig-inv
                                          :session-tx-seq-rollback-detected rb-det
                                          :session-replay-detected rep-det}})]
                  (and (= (:connected res) connected?)
                       (= (:transport_healthy res) transport-healthy?)
                       (= (:mesh_open_peers res) (max 0 mesh-open-peers))
                       (= (:relay_fallback_active res) relay-fb?)
                       (= (:subnet_id_reconciled res) (max 0 sub-rec))
                       (= (:broadcast_dac_missing res) (max 0 dac-miss))
                       (= (:broadcast_dac_invalid res) (max 0 dac-inv))
                       (= (:broadcast_sig_invalid res) (max 0 sig-inv))
                       (= (:session_tx_seq_rollback_detected res) (max 0 rb-det))
                       (= (:session_replay_detected res) (max 0 rep-det))))))

(deftest unit-petname-voice-registry-and-trace-parsing
  (testing "All 5 circle members have distinct Kokoro voice IDs in the petname registry"
    (let [petnames (viz/default-petname-voices)
          voice-ids (map :voice_id petnames)]
      (is (= 5 (count petnames)))
      (is (= 5 (count (set voice-ids))))
      (is (= #{"simon" "axiom" "nexus" "vector" "lumen"}
             (set (map :id petnames))))))

  (testing "JSONL trace parsing extracts spoken turns, verdicts, and skips empty lines"
    (let [sample-jsonl
          (str "{\"turn_seq\":1,\"agent_id\":\"simon\",\"model_selector\":\"claude-opus-5-5-high\","
               "\"phase\":\"review\",\"round\":1,\"stage\":\"present\",\"action_type\":\"message\","
               "\"raw_output\":\"**Simon's twin:** Let's review `661d926aeada43b6ed2f4f82dfff2adb1c4e422df37f8454d99ff9f824da9e13`.\"}\n"
               "{\"turn_seq\":2,\"agent_id\":\"axiom\",\"model_selector\":\"gpt-oss-120b-medium\","
               "\"phase\":\"review\",\"round\":1,\"stage\":\"consent-round\",\"action_type\":\"protocol-act\","
               "\"action_structured\":{\"verb\":\"paramount-objection\"},"
               "\"raw_output\":\"PARAMOUNT-OBJECTION: Unratified values doc.\"}\n")
          turns (viz/parse-trace-jsonl sample-jsonl)]
      (is (= 2 (count turns)))
      (is (= "simon" (:agent_id (first turns))))
      (is (= "am_michael" (:voice_id (first turns))))
      (is (= "paramount-objection" (:verdict (second turns))))
      (is (not (str/includes? (:speech_text (first turns)) "**")))
      (is (not (str/includes? (:speech_text (first turns)) "`"))))))

(deftest unit-build-web-app-zero-inline-scripts-zero-cdn-and-exact-sri
  (let [tmp-dir (.toFile (Files/createTempDirectory "hive-web-app-test" (make-array FileAttribute 0)))]
    (try
      (let [res (viz/build-web-app! (.getAbsolutePath tmp-dir))
            index-file (io/file tmp-dir "index.html")
            chat-js-file (io/file tmp-dir "chat.js")
            viz-file (io/file tmp-dir "trace-viz.html")
            viz-js-file (io/file tmp-dir "trace-viz.js")
            manifest-file (io/file tmp-dir "release-assets.edn")
            index-html (slurp index-file)
            chat-js (slurp chat-js-file)
            viz-html (slurp viz-file)
            viz-js (slurp viz-js-file)
            chat-js-bytes (Files/readAllBytes (.toPath chat-js-file))
            viz-js-bytes (Files/readAllBytes (.toPath viz-js-file))
            chat-js-sri (viz/sha384-sri chat-js-bytes)
            viz-js-sri (viz/sha384-sri viz-js-bytes)
            assets-edn (edn/read-string (slurp manifest-file))]
        (testing "All 5 production web app files are emitted"
          (is (:ok? res))
          (is (.isFile index-file))
          (is (.isFile chat-js-file))
          (is (.isFile viz-file))
          (is (.isFile viz-js-file))
          (is (.isFile manifest-file)))

        (testing "Built index.html and trace-viz.html have ZERO inline scripts, ZERO CDN, ZERO /api/ proxy refs"
          (let [v-index (viz/validate-html-scripts
                         index-html
                         {"./bitecho-browser.js" viz/browser-bundle-sri
                          "./chat.js" chat-js-sri})
                v-viz (viz/validate-html-scripts
                       viz-html
                       {"./trace-viz.js" viz-js-sri})]
            (is (:ok? v-index) (pr-str (:errors v-index)))
            (is (:ok? v-viz) (pr-str (:errors v-viz)))
            (is (= 2 (count (:scripts v-index))))
            (is (= 1 (count (:scripts v-viz))))
            (is (not (str/includes? index-html "cdn.jsdelivr.net")))
            (is (not (str/includes? index-html "/api/bitecho-browser.js")))
            (is (not (str/includes? index-html "/api/")))
            (is (not (str/includes? viz-html "cdn.jsdelivr.net")))
            (is (str/includes? index-html "href=\"./trace-viz.html\""))
            (is (str/includes? viz-html "href=\"./index.html\""))))

        (testing "Externalized chat.js and trace-viz.js have ZERO cdn.jsdelivr.net and use v1.1.0 + self-hosted paths"
          (is (not (str/includes? chat-js "cdn.jsdelivr.net")))
          (is (not (str/includes? viz-js "cdn.jsdelivr.net")))
          (is (not (str/includes? chat-js "/api/kokoro-phonemize")))
          (is (str/includes? chat-js "textToInputIds"))
          (is (str/includes? chat-js "/app/vendor/kokoro-js.mjs"))
          (is (str/includes? chat-js "/app/vendor/transformers.mjs"))
          (is (str/includes? chat-js "/app/vendor/vad-web.mjs"))
          (is (str/includes? chat-js "/app/ort/"))
          (is (str/includes? chat-js "getChunkedArtifact"))
          (is (str/includes? chat-js "openChannel"))
          (is (str/includes? chat-js "listPendingOffers"))
          (is (str/includes? chat-js "registerYubiKey"))
          (is (str/includes? chat-js "recoverYubiKey"))
          (is (str/includes? chat-js "saveIdentity"))
          (is (str/includes? chat-js "SELF_HOSTED_STT_ASSETS"))
          (is (str/includes? chat-js "useCustomCache"))
          (is (str/includes? chat-js "wasmBinary"))
          (is (str/includes? chat-js "offer-received"))
          (is (str/includes? chat-js "session-updated")))

        (testing "release-assets.edn records exact SHA-256 CID, byte size, media-type, and SHA-384 SRI"
          (is (= :bitecho/hive-web-app-assets-v1 (:schema assets-edn)))
          (doseq [[k fname expected-ct] [[:index-html "index.html" "text/html; charset=utf-8"]
                                         [:chat-js "chat.js" "application/javascript; charset=utf-8"]
                                         [:trace-viz-html "trace-viz.html" "text/html; charset=utf-8"]
                                         [:trace-viz-js "trace-viz.js" "application/javascript; charset=utf-8"]]]
            (let [f (io/file tmp-dir fname)
                  bs (Files/readAllBytes (.toPath f))
                  entry (get-in assets-edn [:assets k])]
              (is (= (viz/sha256-bytes bs) (:cid entry)))
              (is (= (alength bs) (:size entry)))
              (is (= expected-ct (:media-type entry)))
              (is (= (viz/sha384-sri bs) (:sri entry))))))

        (testing "M5b: kokoro-webgpu-runner.js is emitted with zero CDN and honest Kokoro-82M Full Speech UI labels"
          (let [runner-file (io/file tmp-dir "kokoro-webgpu-runner.js")
                runner-js (slurp runner-file)
                runner-bs (Files/readAllBytes (.toPath runner-file))
                runner-entry (get-in assets-edn [:assets :kokoro-webgpu-runner-js])]
            (is (.isFile runner-file))
            (is (not (str/includes? runner-js "cdn.jsdelivr.net")))
            (is (str/includes? runner-js "KokoroWebGpuRunner"))
            (is (str/includes? runner-js "export function phonemize"))
            (is (str/includes? runner-js "export function textToInputIds"))
            (is (str/includes? index-html "clj-einsum (:uop-webgpu Kokoro-82M Full Speech)"))
            (is (str/includes? viz-html "clj-einsum (:uop-webgpu Kokoro-82M Full Speech)"))
            (is (= (viz/sha256-bytes runner-bs) (:cid runner-entry)))
            (is (= (viz/sha384-sri runner-bs) (:sri runner-entry)))))

        (testing "BIP-018 Track 3: Relay Health Pill, multi-relay pool, and live chunk progress UX"
          (is (str/includes? index-html "id=\"relay-health-pill\""))
          (is (str/includes? chat-js "https://ns1.bitecho.org"))
          (is (str/includes? chat-js "https://ns2.bitecho.org"))
          (is (str/includes? chat-js "https://ns3.bitecho.org"))
          (is (str/includes? chat-js "relay-health-pill"))
          (is (str/includes? chat-js "relay_pool"))
          (is (str/includes? chat-js "relay-failover"))
          (is (str/includes? chat-js "relay-stale-epoch"))
          (is (str/includes? chat-js "formatChunkProgress"))
          (is (str/includes? chat-js "updateRelayHealthPill"))
          (is (str/includes? chat-js "fetchChunkedWithFailover")))

        (testing "BIP-020 Stage 3: Honest #api-badge, delivery diagnostics drawer, multi-relay wiring & inline delivery status"
          (is (str/includes? index-html "id=\"delivery-diag-btn\""))
          (is (str/includes? index-html "id=\"delivery-diag-drawer\""))
          (is (str/includes? chat-js "classifyMeshBadge"))
          (is (str/includes? chat-js "DISCONNECTED"))
          (is (str/includes? chat-js "DEGRADED (relay)"))
          (is (str/includes? chat-js "ev.type === \"diagnose\""))
          (is (str/includes? chat-js "ev.type === \"delivery-outcome\""))
          (is (str/includes? chat-js "ev.type === \"status\" || ev.type === \"mesh-state\""))
          (is (str/includes? chat-js "node.onStateChange"))
          (is (str/includes? chat-js "relayUrls"))
          (is (str/includes? chat-js "evicted_by_reason"))
          (is (str/includes? chat-js "unacked_aged"))
          (is (str/includes? chat-js "mesh_submitted"))
          (is (str/includes? chat-js "p2p_direct"))
          (is (str/includes? chat-js "received_but_not_emitted"))
          (is (str/includes? chat-js "relayFallbackActive"))
          (is (str/includes? chat-js "classifyOutboundDeliveryStatus"))
          (is (str/includes? chat-js "delivery-pill"))
          (is (str/includes? chat-js "\"rejected\""))
          (is (str/includes? chat-js "\"evicted\""))
          (is (str/includes? chat-js "\"queued\""))
          (is (not (str/includes? chat-js "LIVE (${api.version"))))

        (testing "BIP-021: Stage 3 SRI pin, defaultSubnetId/explicitSubnetId, rekey banner, and honest liveness/diagnostics"
          (is (= "sha384-9jojLcv0pnxOBtEr7NFVJ431eruT+wdKbR0/UmFA6a+enHYP2tzMN8vzrzTxoLxd"
                 viz/browser-bundle-sri))
          (is (str/includes? index-html "sha384-9jojLcv0pnxOBtEr7NFVJ431eruT+wdKbR0/UmFA6a+enHYP2tzMN8vzrzTxoLxd"))
          (is (str/includes? index-html "id=\"rekey-banner\""))
          (is (str/includes? chat-js "defaultSubnetId: \"devnet.bitecho.org\""))
          (is (str/includes? chat-js "explicitSubnetId"))
          (is (str/includes? chat-js "session-seq-mismatch"))
          (is (str/includes? chat-js "rekey_required"))
          (is (str/includes? chat-js "initiateX25519Reoffer"))
          (is (not (str/includes? chat-js "p2p-offer")))
          (is (str/includes? chat-js "transport_healthy"))
          (is (str/includes? chat-js "mesh_open_peers"))
          (is (str/includes? chat-js "subnet_id_reconciled"))
          (is (str/includes? chat-js "broadcast_dac_missing"))
          (is (str/includes? chat-js "broadcast_dac_invalid"))
          (is (str/includes? chat-js "broadcast_sig_invalid"))
          (is (str/includes? chat-js "session_tx_seq_rollback_detected"))
          (is (str/includes? chat-js "session_replay_detected"))))
      (finally
        (doseq [f (reverse (file-seq tmp-dir))]
          (.delete f))))))

(deftest unit-bip018-relay-health-and-chunk-progress-examples
  (testing "Stale relay lagging > 6 finalized epochs is demoted to :relay-degraded"
    (let [res (viz/classify-relay-health
               [{:id "ns1" :url "https://ns1.bitecho.org" :healthy? true :in-cooldown? false :finalized-epoch 80900}
                {:id "ns2" :url "https://ns2.bitecho.org" :healthy? true :in-cooldown? false :finalized-epoch 80941}
                {:id "ns3" :url "https://ns3.bitecho.org" :healthy? true :in-cooldown? false :finalized-epoch 80941}])]
      (is (= :degraded (:status res)))
      (is (= "pill warn" (:pill-class res)))
      (is (= "Relays: 2/3" (:label res)))
      (is (= 1 (:stale-count res)))
      (is (= 80941 (:peer-max-finalized-epoch res)))
      (is (= :relay-degraded (:blast-radius res)))))

  (testing "0/3 healthy relays classifies as :down and :chain-stalled"
    (let [res (viz/classify-relay-health
               [{:id "ns1" :healthy? false}
                {:id "ns2" :healthy? false}
                {:id "ns3" :healthy? false}])]
      (is (= :down (:status res)))
      (is (= "pill live" (:pill-class res)))
      (is (= "Relays: 0/3" (:label res)))
      (is (= :chain-stalled (:blast-radius res)))))

  (testing "Chunk progress formatting matches BIP-018 examples"
    (is (= "ORT WASM 12/42 via ns1"
           (viz/format-chunk-progress {:label "ORT WASM"
                                       :completed 12
                                       :total 42
                                       :relay "https://ns1.bitecho.org"
                                       :failovers 0})))
    (is (= "Moonshine 18/42 via ns2 · 1 failover"
           (viz/format-chunk-progress {:label "Moonshine"
                                       :completed 18
                                       :total 42
                                       :relay "https://ns2.bitecho.org"
                                       :failovers 1})))
    (is (= "Moonshine 42/42 via ns3 · 2 failovers"
           (viz/format-chunk-progress {:label "Moonshine"
                                       :completed 42
                                       :total 42
                                       :relay "https://ns3.bitecho.org"
                                       :failovers 2})))))

(deftest unit-bip020-ui-parity-examples
  (testing "classify-mesh-badge covers all 3 states honestly"
    (is (= {:state :live :label "LIVE" :css-class "pill ok"}
           (viz/classify-mesh-badge {:mesh-connected? true :degraded? false :relay-fallback-active? false})))
    (is (= {:state :degraded :label "DEGRADED (relay)" :css-class "pill warn"}
           (viz/classify-mesh-badge {:mesh-connected? false :degraded? false :relay-fallback-active? true})))
    (is (= {:state :degraded :label "DEGRADED (relay)" :css-class "pill warn"}
           (viz/classify-mesh-badge {:mesh-connected? true :degraded? true :relay-fallback-active? false})))
    (is (= {:state :disconnected :label "DISCONNECTED" :css-class "pill live"}
           (viz/classify-mesh-badge {:mesh-connected? false :degraded? false :relay-fallback-active? false}))))

  (testing "summarize-delivery-telemetry degrades gracefully on pre-BIP-020 payloads and caps :diagnose at 64"
    (let [empty-res (viz/summarize-delivery-telemetry {})
          big-diag (vec (for [i (range 90)] {:reason :outbox-evicted :seq i}))
          capped (viz/summarize-delivery-telemetry {:sent 12
                                                    :acked 10
                                                    :evicted_by_reason {:capacity-overflow 2}
                                                    :unacked_aged 1
                                                    :mesh_submitted 12
                                                    :p2p_direct 8
                                                    :received_but_not_emitted {:duplicate 3}
                                                    :meshConnected true
                                                    :relayFallbackActive false
                                                    :diagnose big-diag})]
      (is (= 0 (:sent empty-res)))
      (is (= {} (:evicted_by_reason empty-res)))
      (is (= [] (:diagnose empty-res)))
      (is (= 64 (count (:diagnose capped))))
      (is (= 26 (:seq (first (:diagnose capped)))))
      (is (= 89 (:seq (last (:diagnose capped)))))
      (is (= 12 (:mesh_submitted capped)))
      (is (= 8 (:p2p_direct capped)))
      (is (= {:duplicate 3} (:received_but_not_emitted capped)))))

  (testing "classify-outbound-delivery-status maps all 4 outcomes honestly"
    (is (= :sent (viz/classify-outbound-delivery-status {:ok true :queued false :evicted false :error nil})))
    (is (= :queued (viz/classify-outbound-delivery-status {:ok true :queued true :evicted false :error nil})))
    (is (= :evicted (viz/classify-outbound-delivery-status {:ok false :queued false :evicted true :error "outbox-full"})))
    (is (= :rejected (viz/classify-outbound-delivery-status {:ok false :queued false :evicted false :error "mesh-disconnected"}))))

  (testing "chat.html source has delivery-diag-btn, delivery-diag-drawer, and zero inline <script> tags"
    (let [chat-html (slurp (io/file "resources/trace_viz/chat.html"))
          validation (viz/validate-html-scripts chat-html {})]
      (is (str/includes? chat-html "id=\"delivery-diag-btn\""))
      (is (str/includes? chat-html "id=\"delivery-diag-drawer\""))
      (is (not (some #(= :inline-script (:kind %)) (:errors validation)))))))
