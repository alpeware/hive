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

;; =============================================================================
;; V3 Capability-Market 12-Item Synthesis Tests (Strict TDD)
;; =============================================================================

(deftest v3-capability-catalog-and-calibration-anchors-test
  (testing "V3 catalog splits storage into two distinct meters, includes 3-meter retrieval, output-token inference caching, separate eval judge/platform fee, and tags :formal-verify :unanchored-calibration-param"
    (let [cat market/capability-catalog
          anchors market/capability-calibration-anchors
          weights market/capability-demand-weights]
      ;; 1. Two storage meters
      (is (= :recurring-epoch-rent (get-in cat [:storage-bytes-at-rest :billing-model])))
      (is (= #{1 3 6} (get-in cat [:storage-bytes-at-rest :replication-factors])))
      (is (= :per-task-escrow (get-in cat [:bandwidth-bytes-in-motion :billing-model])))
      (is (> (double (get-in cat [:bandwidth-bytes-in-motion :ops-egress-share])) 0.5))
      ;; Storage rent scales monotonically with replication factor r in {1, 3, 6}
      (is (< (market/storage-rent-unit-price (:storage-bytes-at-rest cat) 1)
             (market/storage-rent-unit-price (:storage-bytes-at-rest cat) 3)))
      (is (< (market/storage-rent-unit-price (:storage-bytes-at-rest cat) 3)
             (market/storage-rent-unit-price (:storage-bytes-at-rest cat) 6)))
      ;; 2. Three-meter retrieval (:web-search-extract)
      (let [wse (:web-search-extract cat)]
        (is (= :per-task-escrow (:billing-model wse)))
        (is (pos? (:query-ech wse)))
        (is (pos? (:extract-ech wse)))
        (is (pos? (:freshness-egress-ech wse)))
        (is (= (:ref-price-ech wse) (market/web-search-extract-unit-price wse))))
      ;; 3. Sandbox compute
      (is (= :per-task-escrow (get-in cat [:sandbox-compute :billing-model])))
      (is (= :compute-bound (get-in cat [:sandbox-compute :cost-curve])))
      ;; 4. LLM inference with input-token caching (output tokens dominate scarce meter)
      (let [llm (:llm-inference cat)
            eff (market/effective-llm-unit-price llm)]
        (is (>= (double (:cache-hit-rate llm)) 0.70))
        (is (> (long (:output-token-ech llm)) (long (:effective-input-ech eff))))
        (is (> (/ (double (:output-token-ech llm)) (double (:total-ech eff))) 0.80)))
      ;; 5. Eval verify: judge cost separate from platform fee
      (let [ev (:eval-verify cat)]
        (is (pos? (:judge-cost-ech ev)))
        (is (pos? (:platform-fee-ech ev)))
        (is (= (:ref-price-ech ev) (market/eval-verify-unit-price ev))))
      ;; 6. Formal verify explicitly tagged :unanchored-calibration-param
      (is (= :unanchored-calibration-param (get-in cat [:formal-verify :price-anchor-status])))
      (is (= :unanchored-calibration-param (get-in anchors [:formal-verify :price-anchor-status])))
      (is (= :expertise-bound (get-in cat [:formal-verify :cost-curve])))
      ;; 7. Legacy compatibility preserved
      (doseq [k [:artifact-storage :tts-kokoro :stt-moonshine :inverse-game-eval :tac-auction-agent]]
        (is (contains? cat k)))
      ;; 8. Top-5 demand weights reflect synthesis ranking
      (is (= 100 (reduce + 0 (vals weights))))
      (is (> (get weights :llm-inference) (get weights :web-search-extract)))
      (is (> (get weights :web-search-extract) (get weights :sandbox-compute)))
      (is (> (get weights :sandbox-compute)
             (+ (get weights :storage-bytes-at-rest) (get weights :bandwidth-bytes-in-motion))))
      ;; Calibration anchors map covers every capability in catalog
      (is (= (set (keys cat)) (set (keys anchors)))))))

(defspec ^{:doc "V3 Venue/SLA tiers preserve strict ordering: economy < standard < premium in both price multiplier and default SLA, and register-offer applies tier defaults cleanly."}
  v3-venue-sla-tiers-ordering-prop 25
  (prop/for-all [ctype (gen/elements [:llm-inference :web-search-extract :sandbox-compute :eval-verify :formal-verify])]
                (let [fab (fabric/init-fabric {:seed 101})
                      pk (get-in fab [:light-nodes :builder-larg-1 :pubkey-hex])
                      m (-> (market/init-market-state {:seed 101})
                            (market/register-offer {:offer-id "o-econ" :capability-type ctype :venue-tier :economy
                                                    :provider-id :builder-larg-1 :provider-pubkey pk :subnet-id "root/markets/larg-tac"})
                            (market/register-offer {:offer-id "o-std" :capability-type ctype :venue-tier :standard
                                                    :provider-id :builder-larg-1 :provider-pubkey pk :subnet-id "root/markets/larg-tac"})
                            (market/register-offer {:offer-id "o-prem" :capability-type ctype :venue-tier :premium
                                                    :provider-id :builder-larg-1 :provider-pubkey pk :subnet-id "root/markets/larg-tac"}))
                      o-econ (get-in m [:offers "o-econ"])
                      o-std (get-in m [:offers "o-std"])
                      o-prem (get-in m [:offers "o-prem"])]
                  (and (= :economy (:venue-tier o-econ))
                       (= :standard (:venue-tier o-std))
                       (= :premium (:venue-tier o-prem))
                       (< (:attested-sla-bps o-econ) (:attested-sla-bps o-std))
                       (< (:attested-sla-bps o-std) (:attested-sla-bps o-prem))
                       (<= (:unit-price-ech o-econ) (:unit-price-ech o-std))
                       (< (:unit-price-ech o-std) (:unit-price-ech o-prem))))))

(defspec ^{:doc "V3 Failover/Workflow-Insurance bonus increases clearing utility when >=2 healthy redundant providers exist, while remaining 100% immune to max-wtp-ech and claimed-sla-bps."}
  v3-failover-workflow-insurance-utility-prop 30
  (prop/for-all [sla (gen/choose 8800 10000)
                 price (gen/choose 50 1000)
                 ref-price (gen/choose 50 1000)
                 claimed-a (gen/choose 0 10000)
                 claimed-b (gen/choose 0 10000)
                 redundant-n (gen/choose 2 6)]
                (let [offer-a {:attested-sla-bps sla :claimed-sla-bps claimed-a :unit-price-ech price}
                      offer-b {:attested-sla-bps sla :claimed-sla-bps claimed-b :unit-price-ech price}
                      u-solo (market/clearing-utility offer-a ref-price {:redundant-provider-count 1})
                      u-failover (market/clearing-utility offer-a ref-price {:redundant-provider-count redundant-n})
                      u-failover-b (market/clearing-utility offer-b ref-price {:redundant-provider-count redundant-n})]
                  (and (> u-failover u-solo)
                       (= u-failover u-failover-b)))))

(defspec ^{:doc "V3 step-secular-cost-drift compounds compute-bound capabilities at -20%/yr and expertise-bound capabilities at +6%/yr cleanly across compression factor C."}
  v3-secular-cost-drift-compute-vs-expertise-prop 20
  (prop/for-all [c (gen/elements [12 24 72 144])]
                (let [fab (fabric/init-fabric {:seed 202})
                      pk (get-in fab [:light-nodes :builder-larg-1 :pubkey-hex])
                      m0 (-> (market/init-market-state {:seed 202})
                             (market/register-offer {:offer-id "o-compute" :capability-type :llm-inference
                                                     :unit-price-ech 500 :marginal-cost-ech 200
                                                     :provider-id :builder-larg-1 :provider-pubkey pk
                                                     :subnet-id "root/markets/larg-tac"})
                             (market/register-offer {:offer-id "o-expert" :capability-type :formal-verify
                                                     :unit-price-ech 500 :marginal-cost-ech 200
                                                     :provider-id :builder-larg-1 :provider-pubkey pk
                                                     :subnet-id "root/markets/trustx-verify"}))
                      steps-per-year (long (Math/round (/ 8760.0 (double c))))
                      m1 (reduce (fn [m _] (market/step-secular-cost-drift m {:time-compression-factor c}))
                                 m0
                                 (range steps-per-year))
                      mc-comp (get-in m1 [:offers "o-compute" :marginal-cost-ech])
                      mc-exp (get-in m1 [:offers "o-expert" :marginal-cost-ech])]
                  ;; After 1 year: compute 200 -> ~160 (-20%), expertise 200 -> ~212 (+6%)
                  (and (<= 155 mc-comp 165)
                       (<= 208 mc-exp 216)
                       (< mc-comp 200)
                       (> mc-exp 200)))))

(deftest v3-endogenous-provider-entry-and-exit-conservation-test
  (testing "Sustained high util (>80% & price > 1.2*MC) spawns a real Ed25519 Light Node funded with 250k ECHO conserving 42B; sustained low util (<20% & price <= MC) marks endogenous provider :exited with counted :diagnose"
    (let [fab0 (fabric/init-fabric {:seed 303})
          pk (get-in fab0 [:light-nodes :builder-larg-1 :pubkey-hex])
          m0 (-> (market/init-market-state {:seed 303})
                 (market/register-offer {:offer-id "o-hot"
                                         :capability-type :sandbox-compute
                                         :unit-price-ech 140
                                         :marginal-cost-ech 50
                                         :capacity-units-per-epoch 10
                                         :provider-id :builder-larg-1
                                         :provider-pubkey pk
                                         :subnet-id "root/markets/larg-tac"}))
          ;; Simulate 3 consecutive hot epochs (util = 0.95, price = 140 > 1.2*50 = 60)
          m-hot (assoc-in m0 [:offers "o-hot" :last-utilization] 0.95)
          {:keys [fabric market]}
          (reduce (fn [{:keys [fabric market]} ep]
                    (let [res (market/step-provider-entry-exit
                               fabric
                               (assoc-in market [:offers "o-hot" :last-utilization] 0.95)
                               {:epoch ep :entry-streak-epochs 3 :exit-streak-epochs 3})]
                      {:fabric (:fabric res) :market (:market res)}))
                  {:fabric fab0 :market m-hot}
                  [1 2 3])
          entered-offers (filter (fn [[_ o]] (:endogenous? o)) (:offers market))
          [endo-oid endo-offer] (first entered-offers)
          endo-node (get-in fabric [:light-nodes (:provider-id endo-offer)])]
      (is (= 1 (:endogenous-providers-entered market)))
      (is (= 1 (count entered-offers)))
      (is (some? endo-node))
      (is (>= (get-in fabric [:state :ledger (:pubkey-hex endo-node) :balance] 0) market/min-node-stake-ech))
      (is (:ok? (fabric/verify-fabric-conservation fabric)))
      (is (pos? (get-in market [:diagnostics :provider-entered] 0)))
      ;; Now drive the endogenous provider unprofitable (util = 0.05, price == MC) for 3 epochs -> :exited
      (let [mc (:marginal-cost-ech endo-offer)
            m-cold (-> market
                       (assoc-in [:offers "o-hot" :last-utilization] 0.50)
                       (assoc-in [:offers endo-oid :unit-price-ech] mc)
                       (assoc-in [:offers endo-oid :last-utilization] 0.05))
            res-exit (reduce (fn [{:keys [fabric market]} ep]
                               (let [r (market/step-provider-entry-exit
                                        fabric
                                        (-> market
                                            (assoc-in [:offers endo-oid :unit-price-ech] mc)
                                            (assoc-in [:offers endo-oid :last-utilization] 0.05))
                                        {:epoch ep :entry-streak-epochs 3 :exit-streak-epochs 3})]
                                 {:fabric (:fabric r) :market (:market r)}))
                             {:fabric fabric :market m-cold}
                             [4 5 6])]
        (is (= :exited (get-in (:market res-exit) [:offers endo-oid :status])))
        (is (= 1 (:endogenous-providers-exited (:market res-exit))))
        (is (pos? (get-in (:market res-exit) [:diagnostics :provider-exited] 0)))
        (is (:ok? (fabric/verify-fabric-conservation (:fabric res-exit))))))))

(deftest v3-per-provider-gmv-split-and-builder-cashflow-identity-test
  (testing "Market tracks :per-provider-gmv-ech, :per-capability-gmv-ech, and exact builder cashflow identity (delta == 0)"
    (let [fab0 (fabric/init-fabric {:seed 404})
          b1-pk (get-in fab0 [:light-nodes :builder-larg-1 :pubkey-hex])
          b2-pk (get-in fab0 [:light-nodes :builder-larg-2 :pubkey-hex])
          treas-pk (get-in fab0 [:light-nodes :partner-larg-tac :pubkey-hex])
          m0 (-> (market/init-market-state {:seed 404
                                            :subnet-fee-bps 100
                                            :subnet-treasuries {"root/markets/larg-tac" treas-pk}})
                 (market/register-offer {:offer-id "offer-b1-search"
                                         :capability-type :web-search-extract
                                         :unit-price-ech 110
                                         :marginal-cost-ech 40
                                         :capacity-units-per-epoch 10
                                         :provider-id :builder-larg-1
                                         :provider-pubkey b1-pk
                                         :subnet-id "root/markets/larg-tac"
                                         :true-delivery-prob 1.0}))
          req {:request-id "req-cf-1" :request-seq 1 :epoch 1
               :buyer-id :builder-larg-2 :buyer-pubkey b2-pk
               :capability-type :web-search-extract :units 5
               :max-wtp-ech 200 :min-sla-bps 8000 :budget-ech 1000}
          cl (market/clear-market-epoch m0 [req] {:epoch 1})
          st (market/settle-cleared-batches fab0 (:market cl) (:cleared cl)
                                            {:epoch 1 :escrow-ttl-epochs 3 :subnet-fee-bps 100})
          mkt (:market st)
          fab (:fabric st)
          cf (market/compute-builder-cashflow fab mkt [:builder-larg-1 :builder-larg-2]
                                              {:builder-larg-1 fabric/larg-initial-stake-ech
                                               :builder-larg-2 fabric/larg-initial-stake-ech})]
      (is (= 550 (get-in mkt [:per-provider-gmv-ech "offer-b1-search"])))
      (is (= 550 (get-in mkt [:per-capability-gmv-ech :web-search-extract])))
      (is (= 0 (get-in cf [:builder-larg-1 :identity-delta-ech])))
      (is (= 0 (get-in cf [:builder-larg-2 :identity-delta-ech])))
      (is (= 550 (get-in cf [:builder-larg-1 :earned-ech])))
      (is (= 550 (get-in cf [:builder-larg-2 :buyer-spend-ech])))
      (is (pos? (get-in cf [:builder-larg-1 :subnet-fees-paid-ech])))
      (is (pos? (get-in cf [:builder-larg-1 :protocol-burns-paid-ech]))))))

;; =============================================================================
;; V4 Strict-TDD Tests: M3 Telemetry, Reseller Accounting & SaaS-Agent Traffic
;; =============================================================================

(defspec ^{:doc "V4 M3: split-web-search-meters partitions any non-negative gross-ech into query + extract + freshness-egress summing exactly to gross-ech."}
  v4-web-search-meter-breakdown-exact-sum-prop 40
  (prop/for-all [gross (gen/choose 0 1000000)]
                (let [{:keys [query-ech extract-ech freshness-egress-ech]} (market/split-web-search-meters gross)]
                  (and (>= query-ech 0)
                       (>= extract-ech 0)
                       (>= freshness-egress-ech 0)
                       (= (long gross) (+ query-ech extract-ech freshness-egress-ech))))))

(defspec ^{:doc "V4 Reseller accounting conserves 42B ECHO, produces 0 failed receipts, and satisfies reseller-gmv >= reseller-upstream-cost + reseller-net-margin."}
  v4-reseller-accounting-and-42b-conservation-prop 12
  (prop/for-all [seed (gen/choose 1 100000)
                 unit-price (gen/choose 100 2000)
                 units (gen/choose 1 5)
                 margin-bps (gen/elements [500 600])]
                (let [fab0 (fabric/init-fabric {:seed seed})
                      buyer-pk (get-in fab0 [:light-nodes :builder-larg-1 :pubkey-hex])
                      prov-pk (get-in fab0 [:light-nodes :builder-larg-2 :pubkey-hex])
                      treas-pk (get-in fab0 [:light-nodes :partner-larg-tac :pubkey-hex])
                      m0 (-> (market/init-market-state {:seed seed
                                                        :subnet-fee-bps 100
                                                        :subnet-treasuries {"root/markets/larg-tac" treas-pk}})
                             (market/register-offer {:offer-id "offer-reseller-or"
                                                     :capability-type :llm-inference
                                                     :provider-role :reseller
                                                     :upstream-vendor :openrouter
                                                     :reseller-margin-bps margin-bps
                                                     :unit-price-ech unit-price
                                                     :marginal-cost-ech 80
                                                     :capacity-units-per-epoch 10
                                                     :provider-id :builder-larg-2
                                                     :provider-pubkey prov-pk
                                                     :subnet-id "root/markets/larg-tac"
                                                     :attested-sla-bps 9600
                                                     :true-delivery-prob 1.0}))
                      req {:request-id "req-res-1" :request-seq 1 :epoch 1
                           :buyer-id :builder-larg-1 :buyer-pubkey buyer-pk
                           :capability-type :llm-inference :units units
                           :max-wtp-ech (* 2 unit-price) :min-sla-bps 8000
                           :budget-ech (* unit-price units)}
                      cl (market/clear-market-epoch m0 [req] {:epoch 1})
                      st (market/settle-cleared-batches fab0 (:market cl) (:cleared cl)
                                                        {:epoch 1 :escrow-ttl-epochs 3 :subnet-fee-bps 100})
                      mkt (:market st)
                      fab (:fabric st)
                      gross (* unit-price units)
                      r-gmv (long (:reseller-gmv-ech mkt 0))
                      r-up (long (:reseller-upstream-cost-ech mkt 0))
                      r-net (long (:reseller-net-margin-ech mkt 0))]
                  (and (:ok? (fabric/verify-fabric-conservation fab))
                       (= 0 (get-in fab [:state :failed-receipts-count] 0))
                       (= gross r-gmv)
                       (pos? r-up)
                       (>= r-net 0)
                       (>= r-gmv (+ r-up r-net))))))

(deftest v4-saas-api-call-and-intra-vs-cross-company-split-test
  (testing "V4 catalog includes :saas-api-call, and clear-market-epoch separates zero-burn :intra-company collaboration from commercial :cross-company HTLC orders"
    (is (contains? market/capability-catalog :saas-api-call))
    (is (contains? market/capability-calibration-anchors :saas-api-call))
    (is (= :per-task-escrow (get-in market/capability-catalog [:saas-api-call :billing-model])))
    (is (= 100 (reduce + 0 (vals market/capability-demand-weights))))
    (let [fab0 (fabric/init-fabric {:seed 405})
          b-pk (get-in fab0 [:light-nodes :builder-larg-1 :pubkey-hex])
          p-pk (get-in fab0 [:light-nodes :builder-larg-2 :pubkey-hex])
          treas-pk (get-in fab0 [:light-nodes :partner-larg-tac :pubkey-hex])
          m0 (-> (market/init-market-state {:seed 405
                                            :subnet-fee-bps 100
                                            :subnet-treasuries {"root/markets/larg-tac" treas-pk}})
                 (market/register-offer {:offer-id "offer-saas-1"
                                         :capability-type :saas-api-call
                                         :provider-role :saas-agent
                                         :unit-price-ech 130
                                         :marginal-cost-ech 30
                                         :capacity-units-per-epoch 1
                                         :provider-id :builder-larg-2
                                         :provider-pubkey p-pk
                                         :subnet-id "root/markets/larg-tac"
                                         :attested-sla-bps 9600
                                         :true-delivery-prob 1.0}))
          reqs [{:request-id "req-intra-1" :request-seq 1 :epoch 1
                 :traffic-scope :intra-company
                 :buyer-id :builder-larg-1 :buyer-pubkey b-pk
                 :capability-type :saas-api-call :units 1
                 :max-wtp-ech 200 :min-sla-bps 8000 :budget-ech 200}
                {:request-id "req-cross-1" :request-seq 2 :epoch 1
                 :traffic-scope :cross-company
                 :buyer-id :builder-larg-1 :buyer-pubkey b-pk
                 :capability-type :saas-api-call :units 1
                 :max-wtp-ech 200 :min-sla-bps 8000 :budget-ech 200}]
          cl (market/clear-market-epoch m0 reqs {:epoch 1})
          st (market/settle-cleared-batches fab0 (:market cl) (:cleared cl)
                                            {:epoch 1 :escrow-ttl-epochs 3 :subnet-fee-bps 100})
          mkt (:market st)]
      ;; Intra-company did NOT consume the 1 unit of commercial offer capacity or fail uncleared
      (is (= 1 (count (:cleared cl))))
      (is (= 0 (count (:uncleared cl))))
      (is (= 1 (:total-intra-company-events mkt)))
      (is (= 1 (:total-cross-company-cleared-orders mkt)))
      (is (= 130 (get-in mkt [:per-capability-gmv-ech :saas-api-call]))))))

(deftest v4-failover-insured-orders-tracking-test
  (testing "V4 M3: Orders matched to a capability with >= 2 healthy redundant providers increment :total-failover-insured-orders; solo provider does not"
    (let [fab0 (fabric/init-fabric {:seed 406})
          b-pk (get-in fab0 [:light-nodes :builder-larg-1 :pubkey-hex])
          p1-pk (get-in fab0 [:light-nodes :builder-larg-2 :pubkey-hex])
          p2-pk (get-in fab0 [:light-nodes :builder-larg-3 :pubkey-hex])
          req {:request-id "req-fo-1" :request-seq 1 :epoch 1
               :buyer-id :builder-larg-1 :buyer-pubkey b-pk
               :capability-type :web-search-extract :units 2
               :max-wtp-ech 200 :min-sla-bps 8800 :budget-ech 400}
          m-solo (-> (market/init-market-state {:seed 406})
                     (market/register-offer {:offer-id "o-solo" :capability-type :web-search-extract
                                             :unit-price-ech 110 :marginal-cost-ech 40
                                             :capacity-units-per-epoch 5 :provider-id :builder-larg-2
                                             :provider-pubkey p1-pk :subnet-id "root/markets/larg-tac"
                                             :attested-sla-bps 9600 :true-delivery-prob 1.0}))
          m-dual (market/register-offer m-solo {:offer-id "o-dual" :capability-type :web-search-extract
                                                :unit-price-ech 115 :marginal-cost-ech 40
                                                :capacity-units-per-epoch 5 :provider-id :builder-larg-3
                                                :provider-pubkey p2-pk :subnet-id "root/markets/midi-sim"
                                                :attested-sla-bps 9600 :true-delivery-prob 1.0})
          cl-solo (market/clear-market-epoch m-solo [req] {:epoch 1})
          st-solo (market/settle-cleared-batches fab0 (:market cl-solo) (:cleared cl-solo) {:epoch 1 :escrow-ttl-epochs 3})
          cl-dual (market/clear-market-epoch m-dual [req] {:epoch 1})
          st-dual (market/settle-cleared-batches fab0 (:market cl-dual) (:cleared cl-dual) {:epoch 1 :escrow-ttl-epochs 3})
          ws-brk (get-in st-dual [:market :web-search-meter-breakdown])]
      (is (= 0 (:total-failover-insured-orders (:market st-solo))))
      (is (= 1 (:total-failover-insured-orders (:market st-dual))))
      (is (= 220 (+ (:query-ech ws-brk) (:extract-ech ws-brk) (:freshness-egress-ech ws-brk)))))))
