(ns bitecho.hive.genesis-test
  "Strict TDD generative & unit tests for `bitecho.hive.genesis` — pinned to
   `docs/specs/legal-circles-map-excerpt-2026-11-20.md`
   (CID `8039db926a1d9ee5ab7b5a9c198512de20dd79def8e7687ed8e3c1b0e28d14d6`)."
  (:require [bitecho.hive.genesis :as genesis]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private sample-members
  [{:pubkey "pk-simon"  :role :human-anchor :label "Simon"}
   {:pubkey "pk-axiom"  :role :digital-twin :label "Axiom"}
   {:pubkey "pk-nexus"  :role :digital-twin :label "Nexus"}
   {:pubkey "pk-vector" :role :digital-twin :label "Vector"}
   {:pubkey "pk-lumen"  :role :digital-twin :label "Lumen"}])

(def ^:private gen-pubkey
  (gen/elements (mapv :pubkey sample-members)))

(def ^:private gen-visibility
  (gen/elements [:public :shared-encrypted :private-silo :unknown-mode]))

(def ^:private gen-op
  (gen/one-of
   [(gen/hash-map :type (gen/return :spawn-circle)
                  :circle-id (gen/elements [:c1 :c2 :c3 :c4 :sub1 :sub2])
                  :parent-id (gen/elements [:genesis :c1 :c2])
                  :allocation (gen/choose -500 40000)
                  :members (gen/not-empty (gen/set gen-pubkey {:min-elements 1 :max-elements 5}))
                  :threshold (gen/choose 1 4)
                  :visibility gen-visibility
                  :shared-key-holders (gen/set gen-pubkey {:min-elements 0 :max-elements 5})
                  :signers (gen/set gen-pubkey {:min-elements 0 :max-elements 5}))
    (gen/hash-map :type (gen/return :update-circle-state)
                  :circle-id (gen/elements [:c1 :c2 :c3 :sub1])
                  :payload-cid (gen/elements ["cid-a" "cid-b" "cid-c"])
                  :signers (gen/set gen-pubkey {:min-elements 0 :max-elements 5}))
    (gen/hash-map :type (gen/return :disburse-to-member)
                  :circle-id (gen/elements [:c1 :c2 :c3 :sub1])
                  :member-pubkey gen-pubkey
                  :amount (gen/choose -100 15000)
                  :memo (gen/elements ["bounty" "ops" "kernel-opt" "audit"])
                  :signers (gen/set gen-pubkey {:min-elements 0 :max-elements 5}))
    (gen/hash-map :type (gen/return :dissolve-circle)
                  :circle-id (gen/elements [:sub1 :sub2 :c3 :c4])
                  :signers (gen/set gen-pubkey {:min-elements 0 :max-elements 5}))]))

(defspec ^{:doc "ECH conservation holds across every valid and adversarial state transition."}
  prop-genesis-ech-conservation-every-transition 50
  (prop/for-all [pre-mint (gen/choose 10000 500000)
                 ops (gen/vector gen-op 1 35)]
                (let [st0 (genesis/init-genesis {:entity-id "bitecho-foundation-sim"
                                                 :pre-mint-total pre-mint
                                                 :members sample-members})]
                  (loop [st st0
                         remaining ops]
                    (if-not (and (genesis/conservation-holds? st)
                                 (= pre-mint (genesis/total-ech st)))
                      false
                      (if (empty? remaining)
                        true
                        (let [[st' effects] (genesis/transition st (first remaining))
                              has-diagnose? (some #(= :diagnose (first %)) effects)]
                          (and (genesis/conservation-holds? st')
                               (= pre-mint (genesis/total-ech st'))
                   ;; Every rejected transition (no state change besides diagnostics) MUST emit :diagnose
                               (if (= (dissoc st :diagnostics) (dissoc st' :diagnostics))
                                 (boolean has-diagnose?)
                                 true)
                               (recur st' (rest remaining))))))))))

(defspec ^{:doc "Pointer-style multisig and two-mode visibility (:public | :shared-encrypted) reject any third mode or insufficient quorum."}
  prop-circle-multisig-and-visibility-no-third-mode 40
  (prop/for-all [bad-mode (gen/elements [:private-silo :single-owner :unencrypted-private :custom])
                 pre-mint (gen/choose 10000 100000)]
                (let [st0 (genesis/init-genesis {:entity-id "multisig-test"
                                                 :pre-mint-total pre-mint
                                                 :members sample-members})
          ;; Third visibility mode MUST fail closed with :diagnose
                      [st-bad-mode eff1] (genesis/transition st0 {:type :spawn-circle
                                                                  :circle-id :bad
                                                                  :parent-id :genesis
                                                                  :description "Invalid third mode"
                                                                  :allocation 1000
                                                                  :members #{"pk-simon" "pk-vector"}
                                                                  :threshold 2
                                                                  :visibility bad-mode
                                                                  :signers #{"pk-simon"}})
          ;; Shared-encrypted missing a member from shared key MUST fail closed
                      [st-bad-key eff2] (genesis/transition st0 {:type :spawn-circle
                                                                 :circle-id :bad-key
                                                                 :parent-id :genesis
                                                                 :description "Incomplete shared key"
                                                                 :allocation 1000
                                                                 :members #{"pk-simon" "pk-vector"}
                                                                 :threshold 2
                                                                 :visibility :shared-encrypted
                                                                 :shared-key-holders #{"pk-simon"}
                                                                 :signers #{"pk-simon"}})
          ;; Valid shared-encrypted circle with all members in shared key succeeds
                      [st-ok _] (genesis/transition st0 {:type :spawn-circle
                                                         :circle-id :good
                                                         :parent-id :genesis
                                                         :description "Valid shared-encrypted circle"
                                                         :allocation 2000
                                                         :members #{"pk-simon" "pk-vector" "pk-nexus"}
                                                         :threshold 2
                                                         :visibility :shared-encrypted
                                                         :shared-key-holders #{"pk-simon" "pk-vector" "pk-nexus"}
                                                         :signers #{"pk-simon"}})
          ;; Insufficient multisig (1 of 2) MUST fail closed with :diagnose
                      [st-no-quorum eff3] (genesis/transition st-ok {:type :update-circle-state
                                                                     :circle-id :good
                                                                     :payload-cid "cid-v1"
                                                                     :signers #{"pk-simon"}})
          ;; Non-member signer MUST not count toward threshold
                      [st-non-member eff4] (genesis/transition st-ok {:type :update-circle-state
                                                                      :circle-id :good
                                                                      :payload-cid "cid-v1"
                                                                      :signers #{"pk-simon" "pk-axiom"}})
          ;; Valid 2-of-3 multisig succeeds and advances seq
                      [st-updated _] (genesis/transition st-ok {:type :update-circle-state
                                                                :circle-id :good
                                                                :payload-cid "cid-v1"
                                                                :signers #{"pk-simon" "pk-vector"}})]
                  (and (nil? (get-in st-bad-mode [:circles :bad]))
                       (= :diagnose (ffirst eff1))
                       (nil? (get-in st-bad-key [:circles :bad-key]))
                       (= :diagnose (ffirst eff2))
                       (= :shared-encrypted (get-in st-ok [:circles :good :visibility]))
                       (= 0 (get-in st-no-quorum [:circles :good :seq]))
                       (= :diagnose (ffirst eff3))
                       (= 0 (get-in st-non-member [:circles :good :seq]))
                       (= :diagnose (ffirst eff4))
                       (= 1 (get-in st-updated [:circles :good :seq]))
                       (= "cid-v1" (get-in st-updated [:circles :good :state-cid]))))))

(defspec ^{:doc "Member root memory stays private/encrypted with valid hash-chained receipts summing to exact balance."}
  prop-member-root-memory-private-encrypted-receipts 35
  (prop/for-all [amounts (gen/vector (gen/choose 1 2000) 1 10)]
                (let [pre-mint (+ 10000 (reduce + 0 amounts))
                      st0 (genesis/init-genesis {:entity-id "receipt-test"
                                                 :pre-mint-total pre-mint
                                                 :members sample-members})
                      [st1 _] (genesis/transition st0 {:type :spawn-circle
                                                       :circle-id :einsum-hive
                                                       :parent-id :genesis
                                                       :description "Vector clj-einsum + Hive circle"
                                                       :allocation pre-mint
                                                       :members #{"pk-simon" "pk-vector"}
                                                       :threshold 2
                                                       :visibility :public
                                                       :signers #{"pk-simon"}})
                      st-final (reduce (fn [s amt]
                                         (first (genesis/transition s {:type :disburse-to-member
                                                                       :circle-id :einsum-hive
                                                                       :member-pubkey "pk-vector"
                                                                       :amount amt
                                                                       :memo "kernel-milestone"
                                                                       :signers #{"pk-simon" "pk-vector"}})))
                                       st1
                                       amounts)
                      mem (get-in st-final [:member-memories "pk-vector"])]
                  (and (true? (:private-encrypted? mem))
                       (= (count amounts) (count (:receipts mem)))
                       (= (reduce + 0 amounts) (:balance mem))
                       (genesis/verify-member-memory-chain mem)
                       (genesis/conservation-holds? st-final)))))

(defspec ^{:doc "Monte Carlo future forks are deterministic, conserve ECH across all trials, and select the highest path-likelihood future."}
  prop-monte-carlo-fork-determinism-and-highest-path-likelihood 25
  (prop/for-all [seed (gen/choose 1 1000000)
                 trials (gen/choose 10 40)]
                (let [forks (genesis/default-run4-forks)
                      r1 (genesis/run-monte-carlo-futures {:seed seed :trials-per-fork trials :forks forks})
                      r2 (genesis/run-monte-carlo-futures {:seed seed :trials-per-fork trials :forks forks})
                      best (:selected-fork r1)
                      max-p (apply max (map :path-likelihood (:fork-results r1)))]
                  (and (= r1 r2)
                       (= genesis/GENESIS-SPEC-CID (:spec-cid r1))
                       (= (count forks) (count (:fork-results r1)))
                       (every? :all-trials-conserved? (:fork-results r1))
                       (= max-p (:path-likelihood best))))))

(deftest unit-three-genesis-setups-and-bitecho-circle-extensions
  (testing "Pinned spec CID matches Axiom's published artifact"
    (is (= "8039db926a1d9ee5ab7b5a9c198512de20dd79def8e7687ed8e3c1b0e28d14d6"
           genesis/GENESIS-SPEC-CID)))

  (testing "All three distinct genesis setups preserve ECH conservation and include circle extensions"
    (doseq [setup-fn [genesis/build-setup-foundation-builders
                      genesis/build-setup-seed-angels
                      genesis/build-setup-stress-reorg]]
      (let [st (setup-fn)]
        (is (genesis/conservation-holds? st))
        (is (string? (genesis/state-root-cid st)))
        (is (= 64 (count (genesis/state-root-cid st))))
        (is (contains? (:circles st) :nexus-devops))
        (is (contains? (:circles st) :vector-einsum-hive))
        (is (contains? (:circles st) :nexus-implementation)))))

  (testing "Digital-now vs prioritized human-anchor task map is populated and structured"
    (let [task-map (genesis/digital-vs-human-anchor-map)]
      (is (seq (:digital-now task-map)))
      (is (seq (:human-anchor-prioritized task-map)))
      (is (every? #(contains? #{:P0 :P1 :P2} (:priority %))
                  (:human-anchor-prioritized task-map)))))

  (testing "Run-4 Monte Carlo future selection ranks forks by measured path likelihood"
    (let [mc (genesis/run-monte-carlo-futures {:seed 20261121 :trials-per-fork 50})]
      (is (= 3 (count (:fork-results mc))))
      (is (every? :all-trials-conserved? (:fork-results mc)))
      (is (<= 0.0 (get-in mc [:selected-fork :path-likelihood]) 1.0))
      (is (string? (:forecast-root-cid mc))))))
