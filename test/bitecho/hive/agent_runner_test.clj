(ns bitecho.hive.agent-runner-test
  "HIVE-BIP-001 H1/H2/H3/H6 generative & unit tests (bindings, ingress, sim/live transport, runner + CLI flag inventory)."
  (:require [bitecho.hive.agent-runner :as runner]
            [bitecho.hive.bindings :as bindings]
            [bitecho.hive.ingress :as ingress]
            [bitecho.hive.transport :as transport]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.graph.runtime :as runtime]
            [mealy.intelligence.port :as intel-port]))

(defspec ^{:doc "H6+M1+M4 generative invariant: identical Mealy agent definition running on :in-memory transport processes multi-turn DMs and broadcasts with unbroken SHA-256 hash chain and zero silent drops."}
  prop-in-memory-multi-agent-roundtrip-and-hash-chain
  40
  (prop/for-all [msgs (gen/vector
                       (gen/hash-map
                        :id (gen/elements ["m1" "m2" "m3" "m4" "m5"])
                        :kind (gen/elements [:dm :broadcast])
                        :text (gen/elements ["hello hive" "status check" "ping agent-a"]))
                       1 6)]
                (let [fabric (transport/make-in-memory-fabric
                              {:balances {"pk-a" 25000 "pk-b" 25000}})
                      tx-a (transport/build-agent-transport
                            {:transport :in-memory
                             :fabric fabric
                             :self-pubkey "pk-a"
                             :subnet-id "hive/poc"
                             :min-balance 2000})
                      mock-port (reify intel-port/IIntelligencePort
                                  (start-session! [this _opts] this)
                                  (generate! [_ req]
                                    {:text (str "ack:" (subs (:prompt req) 0 (min 20 (count (:prompt req)))))})
                                  (stop-session! [_] nil))
                      agent-ctx (runner/init-agent-runtime!
                                 {:agent-name "agent-a"
                                  :self-pubkey "pk-a"
                                  :subnet-id "hive/poc"
                                  :transport-bundle tx-a
                                  :intelligence-port mock-port
                                  :allowed-broadcast-senders #{"pk-b"}})]
                  ;; Seed events from pk-b into fabric for pk-a
                  (doseq [m msgs]
                    (if (= :dm (:kind m))
                      (transport/fabric-deliver-dm! fabric "pk-b" "pk-a" (:text m) (:id m))
                      (transport/fabric-deliver-broadcast! fabric "pk-b" "hive/poc" (:text m) (:id m))))
                  (let [result (runner/drain-agent-events! agent-ctx {:max-events 20 :base-ts 10000})
                        all-envs (:all-event-log result)
                        diag (get-in result [:middleware-state :diagnostics])
                        counted (+ (:accepted diag)
                                   (:self-filtered diag)
                                   (:duplicate-dropped diag)
                                   (:ping-pong-filtered diag)
                                   (:rate-limited diag))]
                    ;; In-memory broadcast replies echo back to the sender and are caught by M4 self-filter!
                    (and (= (count msgs) (- counted (:self-filtered diag)))
                         (runtime/verify-event-log-chain all-envs)
                         (= (count all-envs) (:tx (runtime/event-log-tip (:graph-inst result)))))))))

(deftest h2-concrete-bindings-test
  (testing "make-einsum-port adapts 2-arity [session prompt] into canonical M2 IIntelligencePort lifecycle"
    (let [closed? (atom false)
          port (bindings/make-einsum-port
                {:model "mock-gemma4"
                 :einsum-backend :uop-intel
                 :init-fn (fn [opts] {:mock-session true :opts opts})
                 :gen-fn (fn [sess prompt]
                           (str "gemma4(" (name (get-in sess [:opts :backend])) "):" prompt))
                 :close-fn (fn [_sess] (reset! closed? true))})
          started (intel-port/start-port! port {})
          resp (intel-port/invoke-port! started {:prompt "hi"})]
      (is (= "gemma4(uop-intel):hi" (:text resp)))
      (is (number? (:model-ms resp)))
      (intel-port/stop-port! started)
      (is (true? @closed?))))

  (testing "make-bitecho-ctl-handler maps canonical commands to CLI args and surfaces errors loudly"
    (let [seen (atom [])
          exec-fn (fn [cmd-vec]
                    (swap! seen conj cmd-vec)
                    {:exit 0 :out "{\"ok\":true,\"msg_id\":\"m123\"}" :err ""})
          handler (bindings/make-bitecho-ctl-handler
                   {:state-dir "/tmp/test-state"
                    :exec-fn exec-fn})
          res (handler {"cmd" "broadcast" "subnet_id" "hive/poc" "message" "hello" "min_balance" 2000})]
      (is (true? (:ok res)))
      (is (= "m123" (:msg_id res)))
      (is (some #(= "broadcast" %) (first @seen)))
      (is (some #(= "2000" %) (first @seen)))))

  (testing "make-bitecho-ctl-handler fails loudly on non-zero exit"
    (let [handler (bindings/make-bitecho-ctl-handler
                   {:state-dir "/tmp/test-state"
                    :exec-fn (fn [_] {:exit 1 :out "" :err "daemon offline"})})
          res (handler {"cmd" "status"})]
      (is (false? (:ok res)))
      (is (= "daemon offline" (:error res))))))

(deftest h3-ingress-cursor-normalization-and-offer-policy-test
  (testing "cursor persistence roundtrip"
    (let [tmp-file (io/file (System/getProperty "java.io.tmpdir")
                            (str "hive-ingress-test-" (System/nanoTime) ".edn"))]
      (try
        (is (= 0 (ingress/read-cursor tmp-file)))
        (ingress/write-cursor! tmp-file 42)
        (is (= 42 (ingress/read-cursor tmp-file)))
        (finally
          (.delete tmp-file)))))

  (testing "normalize-bitecho-event maps broadcast/dm into M4 canonical event shape"
    (let [bcast (ingress/normalize-bitecho-event
                 {:event "broadcast-received" :from "pk-1" :subnet_id "hive/poc" :text "hi" :msg_id "b1"})
          dm (ingress/normalize-bitecho-event
              {:event "message-received" :from "pk-2" :text "secret" :msg_id "d1"})]
      (is (= :broadcast (:kind bcast)))
      (is (= "b1" (:id bcast)))
      (is (= :dm (:kind dm)))
      (is (= "d1" (:id dm)))))

  (testing "evaluate-offer-policy respects :all, :roster-only, and :none with counted diagnostics"
    (let [offer {:event "p2p-offer-received" :session_id "s1" :from "pk-stranger"}]
      (is (= :answer (:action (ingress/evaluate-offer-policy :all #{} offer))))
      (is (= :ignore (:action (ingress/evaluate-offer-policy :roster-only #{"pk-friend"} offer))))
      (is (= :diagnose (get-in (ingress/evaluate-offer-policy :none #{} offer) [:diagnose :effect]))))))

(deftest h1-cli-flag-inventory-test
  (testing "Rule 6: every flag in cli-flag-inventory is accepted by parse-cli-args and documented in help-text"
    (let [help (runner/help-text)]
      (doseq [flag runner/cli-flag-inventory]
        (is (str/includes? help flag) (str "Missing flag in help-text: " flag))))
    (let [parsed (runner/parse-cli-args ["--name" "a1"
                                         "--transport" "sim"
                                         "--backend" "mock"
                                         "--model" "m1"
                                         "--einsum-backend" "uop-intel"
                                         "--state-dir" "/tmp/s1"
                                         "--subnet-id" "hive/poc"
                                         "--min-balance" "2500"
                                         "--max-turns" "3"
                                         "--no-intro"])]
      (is (= "a1" (:agent-name parsed)))
      (is (= :in-memory (:transport parsed)))
      (is (= :mock (:backend parsed)))
      (is (= 2500 (:min-balance parsed)))
      (is (= 3 (:max-turns parsed)))
      (is (false? (:intro? parsed))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown flag"
                          (runner/parse-cli-args ["--bogus-flag"])))))
