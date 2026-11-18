(ns bitecho.hive.scenarios.hello
  "Phase 1 E2E Two-Agent Shared-VRAM Gemma 4 E2B 'Hello' Scenario (`bitecho.hive.scenarios.hello`).
   Proves out the full in-memory `clj-datachannel` / `bitecho` / `mealy` / `clj-einsum` stack:
   - Loads `clj-einsum/.models/gemma-4-E2B-it` (`bf16`) ONCE in VRAM (`weight-allocations = 1`)
   - Multiplexes TWO isolated KV caches (`kv-alpha` and `kv-beta`)
   - Connects Agent Alpha and Agent Beta over an in-memory E2EE P2P DataChannel / Bitecho leg
   - Executes 1 turn on Agent Alpha (says hello to Beta) and 1 turn on Agent Beta (receives greeting & replies hello to Alpha), then terminates
   - Emits and verifies a `BIP-005` `Blob` + `Pointer` + RFC 6962 Merkle episode trace."
  (:require [bitecho.hive.kv :as kv]
            [bitecho.hive.stack :as stack]
            [bitecho.hive.trace :as trace]
            [clojure.string :as str]))

(def ^:const DEFAULT-ALPHA-PROMPT
  "Default Turn 1 prompt for Agent Alpha."
  "Say hello to Beta in one short sentence.")

(def ^:const DEFAULT-BETA-REPLY-TEMPLATE
  "Default Turn 2 prompt template for Agent Beta incorporating Alpha's wire-delivered greeting."
  "Alpha said to you: \"%s\"\nSay hello back to Alpha in one short sentence.")

(defn run-hello-scenario
  "Runs the 2-agent, 1-turn-each shared-VRAM Gemma 4 E2B 'Hello' scenario over the full in-memory
   `clj-datachannel` / `bitecho` / `mealy` / `clj-einsum` stack and returns the verified result map."
  ([] (run-hello-scenario {}))
  ([opts]
   (let [seed (long (or (:seed opts) 20261118))
         alpha-prompt (str (or (:alpha-prompt opts) DEFAULT-ALPHA-PROMPT))
         reply-template (str (or (:reply-prompt-template opts) DEFAULT-BETA-REPLY-TEMPLATE))
         stack0 (stack/init-in-memory-hive-stack (assoc opts :seed seed))]
     (try
       (let [;; Turn 1: Agent Alpha generates greeting using kv-alpha & sends over Bitecho P2P
             [stack1 turn1] (stack/step-agent-turn!
                             stack0 :alpha
                             {:peer "beta"
                              :poll-inbox? false
                              :prompt alpha-prompt})
             ;; Turn 2: Agent Beta drains Bitecho inbox, generates reply using kv-beta (shared VRAM weights), & sends to Alpha
             [stack2 turn2] (stack/step-agent-turn!
                             stack1 :beta
                             {:peer "alpha"
                              :poll-inbox? true
                              :prompt "Say hello back to Alpha in one short sentence."
                              :reply-prompt-template reply-template})
             ;; Drain Alpha's Bitecho inbox to confirm receipt of Beta's reply over the wire
             [_ alpha-inbox-resp _] (stack/execute-light-node-command
                                     @(:net-atom stack2) :alpha
                                     {"cmd" "inbox" "peek" false})
             alpha-inbox-msgs (or (get alpha-inbox-resp "messages") (:messages alpha-inbox-resp) [])
             alpha-received-text (or (:text (first alpha-inbox-msgs))
                                     (get (first alpha-inbox-msgs) "text")
                                     "")
             kv-mux @(:kv-mux-atom stack2)
             net-st @(:net-atom stack2)
             session-id (:session-id net-st)
             alpha-pub (get-in net-st [:agents :alpha :pubkey])
             beta-pub (get-in net-st [:agents :beta :pubkey])
             turn1-wire-cids (vec (or (get-in turn1 [:send-receipt "wire_envelope_cids"])
                                      (get-in turn1 [:send-receipt :wire_envelope_cids])
                                      []))
             turn2-wire-cids (vec (or (get-in turn2 [:send-receipt "wire_envelope_cids"])
                                      (get-in turn2 [:send-receipt :wire_envelope_cids])
                                      []))
             episode-trace (trace/build-episode-trace
                            {:scenario-id "hello-gemma4-e2b"
                             :run-id (str "seed-" seed)
                             :seed seed
                             :authority-keypair (get-in net-st [:agents :alpha :keypair])
                             :kv-blob-store (:blob-store kv-mux)
                             :kv-logs (:kv-logs kv-mux)
                             :turns [{:seq 1
                                      :actor-id :alpha
                                      :actor-pubkey alpha-pub
                                      :kv-log-cid (:kv-log-cid turn1)
                                      :prompt-tokens (:prompt-ids turn1)
                                      :output-tokens (:new-token-ids turn1)
                                      :output-text (:output-text turn1)
                                      :mealy-event-root (:mealy-event-root turn1)
                                      :bitecho-session-id session-id
                                      :wire-envelope-cids turn1-wire-cids}
                                     {:seq 2
                                      :actor-id :beta
                                      :actor-pubkey beta-pub
                                      :kv-log-cid (:kv-log-cid turn2)
                                      :prompt-tokens (:prompt-ids turn2)
                                      :output-tokens (:new-token-ids turn2)
                                      :output-text (:output-text turn2)
                                      :mealy-event-root (:mealy-event-root turn2)
                                      :bitecho-session-id session-id
                                      :wire-envelope-cids turn2-wire-cids}]})
             trace-verification (trace/verify-episode-trace episode-trace)
             vram-stats (kv/multiplexer-stats kv-mux)
             kv-check-alpha (kv/verify-agent-kv-chain kv-mux :alpha)
             kv-check-beta (kv/verify-agent-kv-chain kv-mux :beta)
             replay-alpha (stack/verify-mealy-agent-replay stack2 :alpha)
             replay-beta (stack/verify-mealy-agent-replay stack2 :beta)
             alpha-greeting (:output-text turn1)
             beta-received (:received-text turn2)
             beta-reply (:output-text turn2)
             all-ok? (and (:handshake-ok? net-st)
                          (not (str/blank? alpha-greeting))
                          (not (str/blank? beta-reply))
                          (= alpha-greeting beta-received)
                          (= beta-reply alpha-received-text)
                          (= 1 (:weight-allocations vram-stats))
                          (= 2 (:kv-cache-count vram-stats))
                          (:shared-device-weights? vram-stats)
                          (:isolated-kv-atoms? vram-stats)
                          (:ok? kv-check-alpha)
                          (:ok? kv-check-beta)
                          (:ok? replay-alpha)
                          (:ok? replay-beta)
                          (:ok? trace-verification))]
         {:ok? all-ok?
          :scenario :hello-gemma4-e2b
          :mode (:mode kv-mux)
          :backend (:backend kv-mux)
          :uop-target (:uop-target kv-mux)
          :model-dir (:model-dir kv-mux)
          :precision (:precision kv-mux)
          :seed seed
          :bitecho-session-id session-id
          :alpha-pubkey alpha-pub
          :beta-pubkey beta-pub
          :alpha-greeting alpha-greeting
          :beta-reply beta-reply
          :wire-receipts {:beta-received-from-alpha beta-received
                          :alpha-received-from-beta alpha-received-text
                          :total-wire-packets (count (:wire-log net-st))
                          :turn1-wire-cids turn1-wire-cids
                          :turn2-wire-cids turn2-wire-cids}
          :vram-proof vram-stats
          :kv-chain-checks {:alpha kv-check-alpha
                            :beta kv-check-beta}
          :mealy-replay-checks {:alpha replay-alpha
                                :beta replay-beta}
          :trace-verification trace-verification
          :episode-trace episode-trace})
       (finally
         (stack/close-hive-stack! stack0))))))
