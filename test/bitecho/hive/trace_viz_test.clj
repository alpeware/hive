(ns bitecho.hive.trace-viz-test
  "Strict TDD tests for `bitecho.hive.trace-viz` pure trace parsing, petname voice mapping,
   and TTS text sanitization."
  (:require [bitecho.hive.trace-viz :as viz]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defspec ^{:doc "TTS text sanitization always strips markdown emphasis/backticks and bounds long hex CIDs cleanly."}
  prop-sanitize-speech-text-clean-and-bounded 50
  (prop/for-all [raw gen/string-ascii]
                (let [cleaned (viz/sanitize-speech-text raw)]
                  (and (string? cleaned)
                       (not (str/includes? cleaned "**"))
                       (not (str/includes? cleaned "`"))))))

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
