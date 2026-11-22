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
            (is (str/includes? index-html "clj-einsum (:uop-webgpu Kokoro-82M Full Speech)"))
            (is (str/includes? viz-html "clj-einsum (:uop-webgpu Kokoro-82M Full Speech)"))
            (is (= (viz/sha256-bytes runner-bs) (:cid runner-entry)))
            (is (= (viz/sha384-sri runner-bs) (:sri runner-entry))))))
      (finally
        (doseq [f (reverse (file-seq tmp-dir))]
          (.delete f))))))
