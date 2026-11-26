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
            [mealy.event.source :as event-src]
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
                                         "--node-bin" "/usr/bin/node"
                                         "--light-node-js" "/opt/bitecho/light.cjs"
                                         "--offer-policy" "none"
                                         "--state-dir" "/tmp/s1"
                                         "--subnet-id" "hive/poc"
                                         "--min-balance" "2500"
                                         "--max-turns" "3"
                                         "--no-intro"])]
      (is (= "a1" (:agent-name parsed)))
      (is (= :in-memory (:transport parsed)))
      (is (= :mock (:backend parsed)))
      (is (= "/usr/bin/node" (:node-bin parsed)))
      (is (= "/opt/bitecho/light.cjs" (:light-node-js parsed)))
      (is (= :none (:offer-policy parsed)))
      (is (= 2500 (:min-balance parsed)))
      (is (= 3 (:max-turns parsed)))
      (is (false? (:intro? parsed))))
    ;; F2: default offer-policy must be fail-closed :roster-only, not :all
    (is (= :roster-only (:offer-policy (runner/parse-cli-args []))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown flag"
                          (runner/parse-cli-args ["--bogus-flag"])))))

(deftest axiom-review-flags-regression-test
  (testing "F1+F3+F8: cursor is seeded from file on boot, committed ONLY after turn succeeds, and diagnostics are retained"
    (let [tmp-cur (io/file (System/getProperty "java.io.tmpdir")
                           (str "hive-f1-f3-" (System/nanoTime) ".edn"))
          _ (ingress/write-cursor! tmp-cur 100)
          boom? (atom true)
          src (reify event-src/IEventSource
                (poll-event! [_ cur _timeout-ms]
                  (if (= cur 100)
                    {:event {:id "ev-101" :kind :dm :from "pk-b" :text "crash first"}
                     :cursor 101}
                    {:event nil :cursor cur}))
                (close-source! [_] nil))
          tx-bundle {:transport :live
                     :event-source src
                     :bitecho-handler (fn [_] {:ok true :msg_id "m1"})
                     :read-cursor-fn #(ingress/read-cursor tmp-cur)
                     :commit-cursor-fn #(ingress/write-cursor! tmp-cur %)
                     :balance-fn (constantly 25000)}
          flaky-port (reify intel-port/IIntelligencePort
                       (start-session! [this _] this)
                       (generate! [_ _req]
                         (if @boom?
                           (throw (ex-info "mid-turn crash" {}))
                           {:text "recovered"}))
                       (stop-session! [_] nil))
          ctx (runner/init-agent-runtime! {:agent-name "a1"
                                           :self-pubkey "pk-a"
                                           :transport-bundle tx-bundle
                                           :intelligence-port flaky-port})]
      (try
        ;; F1: seeded from persisted cursor 100, NOT 0
        (is (= 100 (:cursor ctx)))
        ;; F3: crash mid-turn must NOT advance persisted cursor
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mid-turn crash"
                              (runner/drain-agent-events! ctx {:max-events 1})))
        (is (= 100 (ingress/read-cursor tmp-cur)))
        ;; On retry, event 101 succeeds and cursor commits to 101
        (reset! boom? false)
        (let [ctx2 (runner/drain-agent-events! ctx {:max-events 1})]
          (is (= 101 (:cursor ctx2)))
          (is (= 101 (ingress/read-cursor tmp-cur))))
        (finally
          (.delete tmp-cur)))))

  (testing "F4: resolver helpers avoid hardcoded /home/simonpure paths"
    (is (not (str/includes? (bindings/resolve-node-bin nil) "/home/simonpure")))
    (is (not (str/includes? (bindings/resolve-light-node-js nil) "/home/simonpure")))
    (is (not (str/includes? (bindings/resolve-einsum-model nil) "/home/simonpure")))
    (is (= "/custom/node" (bindings/resolve-node-bin "/custom/node"))))

  (testing "F6+F8: wait-event non-zero exit surfaces loud :diagnose and is collected in runner :diagnostics-log"
    (let [src (ingress/make-bitecho-ingress
               {:state-dir "/tmp/dead"
                :exec-fn (fn [_] {:exit 1 :out "" :err "ECONNREFUSED"})})
          res (event-src/poll-event! src 50 1000)]
      (is (nil? (:event res)))
      (is (= 50 (:cursor res)))
      (is (= :wait-event-failed (get-in res [:diagnose :reason]))))
    (let [src (ingress/make-bitecho-ingress
               {:state-dir "/tmp/dead"
                :exec-fn (fn [_] {:exit 1 :out "" :err "daemon died"})})
          ctx (runner/init-agent-runtime!
               {:agent-name "a1"
                :self-pubkey "pk-a"
                :transport-bundle {:transport :live
                                   :event-source src
                                   :bitecho-handler (fn [_] {:ok true})
                                   :balance-fn (constantly 25000)}
                :intelligence-port (reify intel-port/IIntelligencePort
                                     (start-session! [this _] this)
                                     (generate! [_ _] {:text "ok"})
                                     (stop-session! [_] nil))})
          after (runner/drain-agent-events! ctx {:max-events 1})]
      (is (= 1 (count (:diagnostics-log after))))
      (is (= :wait-event-failed (:reason (first (:diagnostics-log after)))))))

  (testing "F7: ctl {:ok false} routes to :error-out, not :reply-out"
    (let [ctx (runner/init-agent-runtime!
               {:agent-name "a1"
                :self-pubkey "pk-a"
                :transport-bundle {:transport :live
                                   :event-source (reify event-src/IEventSource
                                                   (poll-event! [_ c _] {:event nil :cursor c})
                                                   (close-source! [_] nil))
                                   :bitecho-handler (fn [_] {:ok false :error "relay-offline"})
                                   :balance-fn (constantly 25000)}
                :intelligence-port (reify intel-port/IIntelligencePort
                                     (start-session! [this _] this)
                                     (generate! [_ _] {:text "hello"})
                                     (stop-session! [_] nil))})
          after (runner/step-agent-turn! ctx {:kind :dm :from "pk-b" :text "ping"})]
      (is (empty? (get-in after [:graph-inst :outbox :reply-out])))
      (is (= :net-command-failed (:reason (first (get-in after [:graph-inst :outbox :error-out]))))))))
