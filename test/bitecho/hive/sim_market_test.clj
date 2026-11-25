(ns bitecho.hive.sim-market-test
  "Strict-TDD generative (`clojure.test.check`) and unit tests for HIVE-SPEC-001 Workstream C
   (`bitecho.hive.sim.market`):
   - INV-6: One-shot HTLC escrow conservation (`buyer_debit == provider_credit + protocol_burn + subnet_fee`
     on release; 100% refund on timeout/failure)
   - Non-manipulable clearing utility (`max_wtp = 10^9` fails to jump queue)
   - Escrow-attested SLA EMA (dishonest Sybil provider claiming 9999 bps with 50% timeout loses clearing share)
   - Utilization-adaptive pricing (raise >85% util, lower toward marginal cost <40% util)
   - Commercial subnet halt traffic diversion & closed-loop 42B buyer liquidity."
  (:require [bitecho.circles.vault :as vault]
            [bitecho.hive.sim.fabric :as fabric]
            [bitecho.hive.sim.market :as market]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; =============================================================================
;; Clearing Utility & Non-Manipulability (Twin S3 / Axiom Catch)
;; =============================================================================

(defspec ^{:doc "Clearing utility is strictly independent of buyer max-wtp-ech and claimed-sla-bps; strictly monotonic in attested-sla-bps and negatively monotonic in unit-price-ech."}
  clearing-utility-non-manipulable-and-monotonic 30
  (prop/for-all [sla-lo (gen/choose 1000 7000)
                 sla-hi (gen/choose 7001 10000)
                 price-lo (gen/choose 10 500)
                 price-hi (gen/choose 501 2000)
                 ref-price (gen/choose 50 1000)
                 claimed-a (gen/choose 0 10000)
                 claimed-b (gen/choose 0 10000)]
                (let [offer-hi-sla {:attested-sla-bps sla-hi :claimed-sla-bps claimed-a :unit-price-ech price-lo}
                      offer-lo-sla {:attested-sla-bps sla-lo :claimed-sla-bps claimed-b :unit-price-ech price-lo}
                      offer-hi-price {:attested-sla-bps sla-hi :claimed-sla-bps claimed-a :unit-price-ech price-hi}
                      offer-same-diff-claim {:attested-sla-bps sla-hi :claimed-sla-bps claimed-b :unit-price-ech price-lo}]
                  (and (> (market/clearing-utility offer-hi-sla ref-price)
                          (market/clearing-utility offer-lo-sla ref-price))
                       (> (market/clearing-utility offer-hi-sla ref-price)
                          (market/clearing-utility offer-hi-price ref-price))
                       (= (market/clearing-utility offer-hi-sla ref-price)
                          (market/clearing-utility offer-same-diff-claim ref-price))))))

(deftest strategic-buyer-max-wtp-1e9-cannot-jump-queue-test
  (testing "Strategic buyer posting max-wtp-ech = 10^9 arriving after honest buyers does NOT jump queue on capacity saturation"
    (let [fab (fabric/init-fabric {:seed 42})
          p-pk (get-in fab [:light-nodes :builder-larg-1 :pubkey-hex])
          b1-pk (get-in fab [:light-nodes :builder-larg-2 :pubkey-hex])
          b2-pk (get-in fab [:light-nodes :builder-larg-3 :pubkey-hex])
          b3-pk (get-in fab [:light-nodes :founder-simon :pubkey-hex])
          m0 (-> (market/init-market-state {:seed 42})
                 (market/register-offer {:offer-id "offer-tac-1"
                                         :capability-type :tac-auction-agent
                                         :unit-price-ech 100
                                         :marginal-cost-ech 60
                                         :capacity-units-per-epoch 2
                                         :provider-id :builder-larg-1
                                         :provider-pubkey p-pk
                                         :subnet-id "root/markets/larg-tac"
                                         :attested-sla-bps 9800
                                         :true-delivery-prob 1.0}))
          ;; 2 honest buyers arrive first (seq 1, 2), then strategic whale arrives (seq 3) with max-wtp = 10^9
          reqs [{:request-id "req-honest-1" :request-seq 1 :epoch 1 :buyer-id :builder-larg-2 :buyer-pubkey b1-pk
                 :capability-type :tac-auction-agent :units 1 :max-wtp-ech 120 :min-sla-bps 9000 :budget-ech 1000}
                {:request-id "req-honest-2" :request-seq 2 :epoch 1 :buyer-id :builder-larg-3 :buyer-pubkey b2-pk
                 :capability-type :tac-auction-agent :units 1 :max-wtp-ech 110 :min-sla-bps 9000 :budget-ech 1000}
                (market/make-strategic-buyer-request
                 {:request-id "req-whale-3" :request-seq 3 :epoch 1 :buyer-id :founder-simon :buyer-pubkey b3-pk
                  :capability-type :tac-auction-agent :units 1 :min-sla-bps 9000 :budget-ech 1000000000})]
          cleared (market/clear-market-epoch m0 reqs {:epoch 1})
          cleared-ids (set (map :request-id (:cleared cleared)))
          uncleared-ids (set (map :request-id (:uncleared cleared)))]
      (is (= #{"req-honest-1" "req-honest-2"} cleared-ids))
      (is (= #{"req-whale-3"} uncleared-ids))
      (is (pos? (get-in cleared [:diagnostics :order-uncleared-capacity-exhausted] 0))))))

;; =============================================================================
;; Utilization-Adaptive Pricing
;; =============================================================================

(defspec ^{:doc "Adaptive pricing raises unit-price when utilization > 0.85, lowers toward marginal-cost when < 0.40, and never drops below marginal-cost-ech."}
  adaptive-pricing-invariants 30
  (prop/for-all [mc (gen/choose 20 500)
                 markup (gen/choose 0 500)
                 util-hi (gen/double* {:min 0.86 :max 1.0 :NaN? false :infinite? false})
                 util-lo (gen/double* {:min 0.0 :max 0.39 :NaN? false :infinite? false})
                 util-mid (gen/double* {:min 0.41 :max 0.84 :NaN? false :infinite? false})]
                (let [price (+ mc markup)
                      offer {:unit-price-ech price :marginal-cost-ech mc}
                      p-hi (:unit-price-ech (market/adapt-offer-price offer util-hi))
                      p-lo (:unit-price-ech (market/adapt-offer-price offer util-lo))
                      p-mid (:unit-price-ech (market/adapt-offer-price offer util-mid))]
                  (and (>= p-hi price)
                       (or (> p-hi price) (>= price (* 10 mc)))
                       (<= p-lo price)
                       (>= p-lo mc)
                       (= p-mid price)))))

;; =============================================================================
;; INV-6: Real One-Shot HTLC Escrow Settlement Conservation (Release + Timeout)
;; =============================================================================

(defspec ^{:doc "INV-6: Real one-shot HTLC batch escrow settlement conserves 42B ECHO on both delivery (with 50 bps burn + commercial subnet fee) and 100% timeout refund."}
  inv6-escrow-batch-settlement-conservation 12
  (prop/for-all [seed (gen/choose 1 100000)
                 unit-price (gen/choose 100 5000)
                 units (gen/choose 1 5)
                 subnet-fee-bps (gen/choose 0 500)
                 deliver? gen/boolean]
                (let [fab0 (fabric/init-fabric {:seed seed})
                      buyer-pk (get-in fab0 [:light-nodes :builder-larg-1 :pubkey-hex])
                      prov-pk (get-in fab0 [:light-nodes :builder-larg-2 :pubkey-hex])
                      sub-treasury-pk (get-in fab0 [:light-nodes :builder-larg-3 :pubkey-hex])
                      buyer-before (get-in fab0 [:state :ledger buyer-pk :balance])
                      prov-before (get-in fab0 [:state :ledger prov-pk :balance])
                      treas-before (get-in fab0 [:state :ledger sub-treasury-pk :balance])
                      burn-before (get-in fab0 [:state :total-burned])
                      m0 (-> (market/init-market-state {:seed seed
                                                        :subnet-fee-bps subnet-fee-bps
                                                        :subnet-treasuries {"root/markets/larg-tac" sub-treasury-pk}})
                             (market/register-offer {:offer-id "offer-1"
                                                     :capability-type :llm-inference
                                                     :unit-price-ech unit-price
                                                     :marginal-cost-ech 50
                                                     :capacity-units-per-epoch 10
                                                     :provider-id :builder-larg-2
                                                     :provider-pubkey prov-pk
                                                     :subnet-id "root/markets/larg-tac"
                                                     :attested-sla-bps 9500
                                                     :true-delivery-prob (if deliver? 1.0 0.0)}))
                      req {:request-id "req-1" :request-seq 1 :epoch 1
                           :buyer-id :builder-larg-1 :buyer-pubkey buyer-pk
                           :capability-type :llm-inference :units units
                           :max-wtp-ech (* 2 unit-price) :min-sla-bps 8000
                           :budget-ech (* unit-price units)}
                      clear-res (market/clear-market-epoch m0 [req] {:epoch 1})
                      settle-res (market/settle-cleared-batches
                                  fab0 (:market clear-res) (:cleared clear-res)
                                  {:epoch 1 :escrow-ttl-epochs 5 :subnet-fee-bps subnet-fee-bps})
                      fab1 (:fabric settle-res)
                      mkt1 (:market settle-res)
                      cons1 (fabric/verify-fabric-conservation fab1)
                      ;; For timeout path, settle matured pending refunds at real block-epoch = (+ 0 5 2) = 7
                      ref-res (when-not deliver?
                                (market/settle-pending-refunds fab1 mkt1 7))
                      fab-final (if deliver? fab1 (:fabric ref-res))
                      cons-final (fabric/verify-fabric-conservation fab-final)
                      buyer-after (get-in fab-final [:state :ledger buyer-pk :balance])
                      prov-after (get-in fab-final [:state :ledger prov-pk :balance])
                      treas-after (get-in fab-final [:state :ledger sub-treasury-pk :balance])
                      burn-after (get-in fab-final [:state :total-burned])
                      gross (* unit-price units)]
                  (and (:ok? cons1)
                       (:ok? cons-final)
                       (= 0 (get-in fab-final [:state :failed-receipts-count] 0))
                       (if deliver?
                         (let [buyer-debit (- buyer-before buyer-after)
                               prov-credit (- prov-after prov-before)
                               treas-credit (- treas-after treas-before)
                               proto-burn (- burn-after burn-before)]
                           (and (= gross buyer-debit)
                                (pos? proto-burn)
                                (= buyer-debit (+ prov-credit proto-burn treas-credit))))
                         (and (= 1 (:pending-timeouts settle-res))
                              (= 0 (:timeout-refunds settle-res))
                              (= 1 (:timeout-refunds ref-res))
                              (= buyer-before buyer-after)
                              (= prov-before prov-after)
                              (= treas-before treas-after)
                              (= burn-before burn-after)))))))

;; =============================================================================
;; Adversarial Market Scenarios: Sybil Provider SLA Decay & Subnet Halt Diversion
;; =============================================================================

(deftest sybil-provider-loses-clearing-share-under-attested-sla-ema-test
  (testing "Dishonest Sybil provider claiming 9999 bps SLA with 50% timeout rate decays in attested-sla-bps and loses clearing share to honest provider"
    (let [fab0 (fabric/init-fabric {:seed 20261124})
          honest-pk (get-in fab0 [:light-nodes :builder-larg-1 :pubkey-hex])
          sybil-pk (get-in fab0 [:light-nodes :builder-larg-2 :pubkey-hex])
          buyer-pk (get-in fab0 [:light-nodes :builder-larg-3 :pubkey-hex])
          m0 (-> (market/init-market-state {:seed 20261124})
                 (market/register-offer {:offer-id "offer-honest"
                                         :capability-type :formal-verify
                                         :unit-price-ech 200
                                         :marginal-cost-ech 100
                                         :capacity-units-per-epoch 5
                                         :provider-id :builder-larg-1
                                         :provider-pubkey honest-pk
                                         :subnet-id "root/markets/trustx-verify"
                                         :attested-sla-bps 9500
                                         :true-delivery-prob 0.99})
                 (market/inject-sybil-provider {:offer-id "offer-sybil"
                                                :capability-type :formal-verify
                                                :unit-price-ech 180
                                                :marginal-cost-ech 100
                                                :capacity-units-per-epoch 5
                                                :provider-id :builder-larg-2
                                                :provider-pubkey sybil-pk
                                                :subnet-id "root/markets/trustx-verify"
                                                :claimed-sla-bps 9999
                                                :attested-sla-bps 9500
                                                :true-delivery-prob 0.50}))
          final (loop [ep 1
                       fab fab0
                       mkt m0
                       late-honest 0
                       late-sybil 0]
                  (if (> ep 25)
                    {:fab fab :mkt mkt :late-honest late-honest :late-sybil late-sybil}
                    (let [reqs (mapv (fn [idx]
                                       {:request-id (str "r-" ep "-" idx)
                                        :request-seq idx
                                        :epoch ep
                                        :buyer-id :builder-larg-3
                                        :buyer-pubkey buyer-pk
                                        :capability-type :formal-verify
                                        :units 1
                                        :max-wtp-ech 500
                                        :min-sla-bps 8500
                                        :budget-ech 500})
                                     (range 1 5))
                          cl (market/clear-market-epoch mkt reqs {:epoch ep})
                          st (market/settle-cleared-batches fab (:market cl) (:cleared cl) {:epoch ep :escrow-ttl-epochs 3})
                          h-cnt (count (filter #(= "offer-honest" (:offer-id %)) (:cleared cl)))
                          s-cnt (count (filter #(= "offer-sybil" (:offer-id %)) (:cleared cl)))]
                      (recur (inc ep)
                             (:fabric st)
                             (:market st)
                             (if (> ep 15) (+ late-honest h-cnt) late-honest)
                             (if (> ep 15) (+ late-sybil s-cnt) late-sybil)))))
          sybil-sla (get-in final [:mkt :offers "offer-sybil" :attested-sla-bps])
          honest-sla (get-in final [:mkt :offers "offer-honest" :attested-sla-bps])]
      (is (< sybil-sla 8800) (str "Expected Sybil attested SLA to decay below 8800, got " sybil-sla))
      (is (>= honest-sla 9500) (str "Expected honest attested SLA >= 9500, got " honest-sla))
      (is (> (:late-honest final) (:late-sybil final)))
      (is (:ok? (fabric/verify-fabric-conservation (:fab final)))))))

(deftest commercial-subnet-halt-diverts-traffic-to-healthy-subnet-test
  (testing "Halting a commercial subnet diverts clearing to redundant healthy commercial subnet"
    (let [fab0 (fabric/init-fabric {:seed 77})
          p1-pk (get-in fab0 [:light-nodes :builder-larg-1 :pubkey-hex])
          p2-pk (get-in fab0 [:light-nodes :builder-larg-2 :pubkey-hex])
          b-pk (get-in fab0 [:light-nodes :builder-larg-3 :pubkey-hex])
          m0 (-> (market/init-market-state {:seed 77})
                 (market/register-offer {:offer-id "offer-larg"
                                         :capability-type :llm-inference
                                         :unit-price-ech 100
                                         :marginal-cost-ech 50
                                         :capacity-units-per-epoch 5
                                         :provider-id :builder-larg-1
                                         :provider-pubkey p1-pk
                                         :subnet-id "root/markets/larg-tac"
                                         :attested-sla-bps 9900
                                         :true-delivery-prob 1.0})
                 (market/register-offer {:offer-id "offer-midi"
                                         :capability-type :llm-inference
                                         :unit-price-ech 110
                                         :marginal-cost-ech 50
                                         :capacity-units-per-epoch 5
                                         :provider-id :builder-larg-2
                                         :provider-pubkey p2-pk
                                         :subnet-id "root/markets/midi-sim"
                                         :attested-sla-bps 9800
                                         :true-delivery-prob 1.0}))
          req {:request-id "req-divert" :request-seq 1 :epoch 1
               :buyer-id :builder-larg-3 :buyer-pubkey b-pk
               :capability-type :llm-inference :units 2
               :max-wtp-ech 200 :min-sla-bps 9000 :budget-ech 500}
          cl-normal (market/clear-market-epoch m0 [req] {:epoch 1 :halted-subnets #{}})
          cl-halted (market/clear-market-epoch m0 [req] {:epoch 1 :halted-subnets #{"root/markets/larg-tac"}})]
      (is (= "root/markets/larg-tac" (:subnet-id (first (:cleared cl-normal)))))
      (is (= "root/markets/midi-sim" (:subnet-id (first (:cleared cl-halted))))))))

(deftest closed-loop-buyer-liquidity-faucet-dac-conservation-test
  (testing "Underfunded buyer replenishes via real Ecosystem Faucet DAC (vault/spend-via-vault-dac) conserving 42B ECHO"
    (let [fab0 (fabric/init-fabric {:seed 88})
          buyer-pk (get-in fab0 [:light-nodes :angel-g4-1 :pubkey-hex])
          ;; Issue faucet DAC on vault:root/ecosystem-faucet
          faucet-holder (get-in fab0 [:light-nodes :founder-simon :pubkey-hex])
          dac-res (vault/issue-vault-spend-dac (:state fab0)
                                               {:dac-id "faucet-dac"
                                                :vault-id "vault:root/ecosystem-faucet"
                                                :holder-pubkey faucet-holder
                                                :max-per-epoch-bps 500
                                                :issued-epoch 0
                                                :expires-epoch 1000})
          fab1 (assoc fab0 :state (:state dac-res))
          rep (market/replenish-buyer-liquidity fab1 {:buyer-pubkey buyer-pk
                                                      :needed-ech 10000
                                                      :epoch 1
                                                      :faucet-dac-id "faucet-dac"})]
      (is (:ok? rep))
      (is (>= (get-in (:fabric rep) [:state :ledger buyer-pk :balance] 0) 9900))
      (is (:ok? (fabric/verify-fabric-conservation (:fabric rep)))))))

;; =============================================================================
;; V2 Falsifiers: Cross-Subnet CRN Isolation, Self-Trade Rejection & Bayesian SLA
;; =============================================================================

(deftest cross-subnet-crn-isolation-falsifier-test
  (testing "V3: Adding an earlier-sorting order on an unrelated subnet leaves target subnet delivery bit-identical"
    (let [fab0 (fabric/init-fabric {:seed 1337})
          p-target-pk (get-in fab0 [:light-nodes :builder-larg-1 :pubkey-hex])
          p-other-pk (get-in fab0 [:light-nodes :builder-larg-2 :pubkey-hex])
          b-target-pk (get-in fab0 [:light-nodes :builder-larg-3 :pubkey-hex])
          b-other-pk (get-in fab0 [:light-nodes :partner-larg-tac :pubkey-hex])
          ;; Ensure both buyers are funded
          fab-funded (assoc-in fab0 [:state :ledger b-other-pk :balance] 100000)
          m0 (-> (market/init-market-state {:seed 1337})
                 (market/register-offer {:offer-id "offer-target"
                                         :capability-type :llm-inference
                                         :unit-price-ech 200
                                         :marginal-cost-ech 80
                                         :capacity-units-per-epoch 10
                                         :provider-id :builder-larg-1
                                         :provider-pubkey p-target-pk
                                         :subnet-id "root/markets/zzz-target"
                                         :attested-sla-bps 9500
                                         :true-delivery-prob 0.50})
                 (market/register-offer {:offer-id "offer-earlier"
                                         :capability-type :tac-auction-agent
                                         :unit-price-ech 150
                                         :marginal-cost-ech 60
                                         :capacity-units-per-epoch 10
                                         :provider-id :builder-larg-2
                                         :provider-pubkey p-other-pk
                                         :subnet-id "root/markets/aaa-unrelated"
                                         :attested-sla-bps 9500
                                         :true-delivery-prob 0.50}))
          ;; Test across 20 epochs so 50% Bernoulli draws would certainly diverge if keyed by global batch index!
          outcomes-solo
          (mapv (fn [ep]
                  (let [req-target {:request-id (str "t-" ep) :request-seq 2 :epoch ep
                                    :buyer-id :builder-larg-3 :buyer-pubkey b-target-pk
                                    :capability-type :llm-inference :units 1
                                    :max-wtp-ech 300 :min-sla-bps 8000 :budget-ech 300}
                        cl (market/clear-market-epoch m0 [req-target] {:epoch ep})
                        st (market/settle-cleared-batches fab-funded (:market cl) (:cleared cl) {:epoch ep :escrow-ttl-epochs 2})]
                    (= 1 (:settled-batches st))))
                (range 1 21))
          outcomes-with-earlier
          (mapv (fn [ep]
                  (let [req-other {:request-id (str "a-" ep) :request-seq 1 :epoch ep
                                   :buyer-id :partner-larg-tac :buyer-pubkey b-other-pk
                                   :capability-type :tac-auction-agent :units 1
                                   :max-wtp-ech 300 :min-sla-bps 8000 :budget-ech 300}
                        req-target {:request-id (str "t-" ep) :request-seq 2 :epoch ep
                                    :buyer-id :builder-larg-3 :buyer-pubkey b-target-pk
                                    :capability-type :llm-inference :units 1
                                    :max-wtp-ech 300 :min-sla-bps 8000 :budget-ech 300}
                        cl (market/clear-market-epoch m0 [req-other req-target] {:epoch ep})
                        st (market/settle-cleared-batches fab-funded (:market cl) (:cleared cl) {:epoch ep :escrow-ttl-epochs 2})]
                    (pos? (get-in st [:market :per-subnet-released "root/markets/zzz-target"] 0))))
                (range 1 21))]
      (is (= outcomes-solo outcomes-with-earlier)
          "Adding an earlier-sorting unrelated subnet order must NEVER perturb target subnet delivery draws"))))

(deftest self-trade-forbidden-and-bayesian-sla-no-death-spiral-test
  (testing "clear-market-epoch forbids self-trades (buyer == provider) so lock-intent never fails"
    (let [fab (fabric/init-fabric {:seed 55})
          pk (get-in fab [:light-nodes :builder-larg-1 :pubkey-hex])
          m0 (-> (market/init-market-state {:seed 55})
                 (market/register-offer {:offer-id "offer-self"
                                         :capability-type :llm-inference
                                         :unit-price-ech 100
                                         :marginal-cost-ech 50
                                         :capacity-units-per-epoch 5
                                         :provider-id :builder-larg-1
                                         :provider-pubkey pk
                                         :subnet-id "root/markets/larg-tac"}))
          req {:request-id "req-self" :request-seq 1 :epoch 1
               :buyer-id :builder-larg-1 :buyer-pubkey pk
               :capability-type :llm-inference :units 1
               :max-wtp-ech 200 :min-sla-bps 8000 :budget-ech 200}
          cl (market/clear-market-epoch m0 [req] {:epoch 1})]
      (is (empty? (:cleared cl)))
      (is (= 1 (count (:uncleared cl))))))

  (testing "Bayesian/Beta SLA estimator: single timeout on honest provider stays > 8800 bps (9500 -> 9048), while 4 timeouts on Sybil drop < 8800 bps"
    (let [base-offer {:sla-prior-alpha 19 :sla-prior-beta 1 :attested-sla-bps 9500 :total-released 0 :total-refunded 0}
          after-1-timeout (market/update-attested-sla base-offer false)
          after-4-timeouts (nth (iterate #(market/update-attested-sla % false) base-offer) 4)]
      (is (= 9048 (:attested-sla-bps after-1-timeout)))
      (is (> (:attested-sla-bps after-1-timeout) 8800))
      (is (< (:attested-sla-bps after-4-timeouts) 8800)))))
