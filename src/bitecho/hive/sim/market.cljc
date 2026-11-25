(ns bitecho.hive.sim.market
  "HIVE-SPEC-001 Workstream C: Pure Sans-IO Endogenous Capability Market & Traffic Engine.

   Implements:
   - Priced Capability Offers across 7 agent-economy capability types with utilization-adaptive
     pricing and strictly escrow-attested SLA EMA (`attested-sla-bps` derived solely from
     verified `:release-intent` vs `:refund-intent` outcomes — never self-reported)
   - Non-manipulable clearing utility `U(offer, ref-price) = alpha*(sla/10000) - beta*(price/max(1,ref))`
     with deterministic FIFO request ordering immune to buyer `max_wtp = 10^9` queue-jumping (Twin S3)
   - Bounded per-provider-epoch batch one-shot HTLC escrow settlement (`[:escrows ...]`) through
     real `bitecho.state-machine/execute-finalized-blocks` (`:lock-intent` / `:release-intent` /
     `:refund-intent`), reconciling 50 bps protocol burn and commercial `subnet_fee` (`<= 500 bps`)
     via real signed `:transfer` receipts
   - Closed-loop 42B buyer liquidity via Ecosystem Faucet DAC (`vault/spend-via-vault-dac`) and
     Edge-FX secondary-market cycling (zero off-cap minting)
   - Adversarial market scenario helpers (Sybil provider SLA decay, strategic whale buyer,
     commercial subnet halt traffic diversion).

   Strictly adheres to AGENTS.md Rules 2 & 5 (Pure Sans-IO `.cljc`, zero silent failures)."
  (:require [bitecho.basalt.core :as basalt]
            [bitecho.circles.vault :as vault]
            [bitecho.core.circle :as circle]
            [bitecho.crypto :as crypto]
            [bitecho.economy.account :as account]
            [bitecho.fressian :as fressian]
            [bitecho.hive.sim.fabric :as fabric]
            [bitecho.state-machine :as state-machine]
            [clojure.string :as str]))

;; =============================================================================
;; 1. Capability Catalog & Default Market Constants
;; =============================================================================

(def capability-catalog
  "Canonical 7 agent-economy capability types with baseline reference prices and marginal costs (ECHO)."
  {:llm-inference     {:ref-price-ech 200 :marginal-cost-ech 80}
   :tts-kokoro        {:ref-price-ech 80  :marginal-cost-ech 30}
   :stt-moonshine     {:ref-price-ech 90  :marginal-cost-ech 35}
   :formal-verify     {:ref-price-ech 400 :marginal-cost-ech 160}
   :inverse-game-eval {:ref-price-ech 320 :marginal-cost-ech 120}
   :tac-auction-agent {:ref-price-ech 150 :marginal-cost-ech 60}
   :artifact-storage  {:ref-price-ech 60  :marginal-cost-ech 20}})

(def capability-types
  "Ordered vector of all 7 supported capability types."
  [:llm-inference :tts-kokoro :stt-moonshine :formal-verify
   :inverse-game-eval :tac-auction-agent :artifact-storage])

(def default-utility-alpha
  "Default reliability weight in clearing utility (Twin S3 / HIVE-SPEC-001 L3)."
  0.65)

(def default-utility-beta
  "Default normalized price weight in clearing utility (Twin S3 / HIVE-SPEC-001 L3)."
  0.35)

(def max-commercial-subnet-fee-bps
  "Hard cap on commercial subnet take rate (<= 500 bps = 5.0%)."
  500)

(def default-sla-ema-alpha
  "Default exponential moving average smoothing factor for escrow-attested SLA."
  0.25)

;; =============================================================================
;; 2. Market State & Capability Offer Management
;; =============================================================================

(defn init-market-state
  "Initializes a pure Sans-IO capability market state."
  ([] (init-market-state {}))
  ([opts]
   {:seed (long (or (:seed opts) 20261124))
    :alpha (double (or (:alpha opts) default-utility-alpha))
    :beta (double (or (:beta opts) default-utility-beta))
    :subnet-fee-bps (min max-commercial-subnet-fee-bps
                         (max 0 (long (or (:subnet-fee-bps opts) 100))))
    :subnet-treasuries (or (:subnet-treasuries opts) {})
    :offers {}
    :escrow-seq 0
    :total-cleared-gmv-ech 0
    :total-cleared-orders 0
    :total-uncleared-orders 0
    :total-timeout-refunds 0
    :total-subnet-fees-ech 0
    :per-subnet-gmv-ech {}
    :per-subnet-cleared-orders {}
    :per-subnet-released {}
    :per-subnet-refunded {}
    :diagnostics {}}))

(defn register-offer
  "Registers a priced capability offer on `market`. Validates canonical 88-char SPKI pubkey.
   Note: `:attested-sla-bps` is initialized independently of any self-reported `:claimed-sla-bps`."
  [market {:keys [offer-id capability-type unit-price-ech marginal-cost-ech
                  capacity-units-per-epoch provider-id provider-pubkey subnet-id
                  attested-sla-bps claimed-sla-bps true-delivery-prob ema-alpha]}]
  (let [norm-pk (circle/normalize-spki-pubkey provider-pubkey)
        cat (get capability-catalog capability-type {:ref-price-ech 150 :marginal-cost-ech 50})
        mc (max 1 (long (or marginal-cost-ech (:marginal-cost-ech cat))))
        price (max mc (long (or unit-price-ech (:ref-price-ech cat))))
        cap (max 1 (long (or capacity-units-per-epoch 10)))
        init-sla (min 10000 (max 0 (long (or attested-sla-bps 9500))))
        delivery-p (min 1.0 (max 0.0 (double (if (some? true-delivery-prob) true-delivery-prob 0.98))))]
    (if-not (and (string? offer-id) (some? norm-pk) (contains? capability-catalog capability-type))
      (update-in market [:diagnostics :invalid-offer-rejected] (fnil inc 0))
      (assoc-in market [:offers offer-id]
                {:offer-id offer-id
                 :capability-type capability-type
                 :unit-price-ech price
                 :marginal-cost-ech mc
                 :capacity-units-per-epoch cap
                 :provider-id provider-id
                 :provider-pubkey norm-pk
                 :subnet-id (str subnet-id)
                 :attested-sla-bps init-sla
                 :claimed-sla-bps (long (or claimed-sla-bps init-sla))
                 :true-delivery-prob delivery-p
                 :ema-alpha (double (or ema-alpha default-sla-ema-alpha))
                 :total-released 0
                 :total-refunded 0
                 :last-utilization 0.0}))))

(defn inject-sybil-provider
  "Adversarial helper: registers a dishonest/Sybil provider claiming high SLA (default 9999 bps)
   while actually failing/timing out ~50% of escrows (`true-delivery-prob = 0.50`)."
  [market offer-spec]
  (register-offer market
                  (merge {:claimed-sla-bps 9999
                          :attested-sla-bps 9500
                          :true-delivery-prob 0.50}
                         offer-spec)))

(defn make-strategic-buyer-request
  "Adversarial helper: builds a strategic buyer request posting `max-wtp-ech = 10^9`
   to verify immunity against queue-jumping."
  [req-spec]
  (merge {:max-wtp-ech 1000000000
          :units 1
          :min-sla-bps 8000}
         req-spec))

;; =============================================================================
;; 3. Non-Manipulable Clearing Utility & Utilization-Adaptive Pricing
;; =============================================================================

(defn clearing-utility
  "Computes non-manipulable clearing utility (Twin S3 / Axiom catch):
     U(offer, ref-price) = alpha * (attested_sla_bps / 10000.0) - beta * (unit_price_ech / max(1, ref_price_ech))
   Strictly ignores `:claimed-sla-bps` and buyer `:max-wtp-ech`."
  ([offer ref-price-ech]
   (clearing-utility offer ref-price-ech nil))
  ([offer ref-price-ech opts]
   (let [alpha (double (or (:alpha opts) default-utility-alpha))
         beta (double (or (:beta opts) default-utility-beta))
         sla (double (or (:attested-sla-bps offer) 0))
         price (double (or (:unit-price-ech offer) 1))
         ref-p (double (max 1 (long (or ref-price-ech 1))))]
     (- (* alpha (/ sla 10000.0))
        (* beta (/ price ref-p))))))

(defn adapt-offer-price
  "Utilization-adaptive pricing:
   - utilization > 0.85: raises `:unit-price-ech` (~+10%, at least +1, capped at 10x marginal cost)
   - utilization < 0.40: lowers `:unit-price-ech` toward `:marginal-cost-ech` (~-8%, never below MC)
   - otherwise: holds steady."
  [offer utilization]
  (let [u (double (or utilization 0.0))
        mc (max 1 (long (or (:marginal-cost-ech offer) 1)))
        price (max mc (long (or (:unit-price-ech offer) mc)))
        cap-price (* 10 mc)
        new-price (cond
                    (> u 0.85)
                    (max price (min cap-price (max (inc price) (long (Math/ceil (* (double price) 1.10))))))

                    (< u 0.40)
                    (max mc (if (> price mc)
                              (min (dec price) (long (Math/floor (* (double price) 0.92))))
                              mc))

                    :else
                    price)]
    (assoc offer :unit-price-ech new-price :last-utilization u)))

(defn update-attested-sla
  "Updates an offer's escrow-attested SLA EMA strictly from verified on-chain
   `:release-intent` (delivered? = true -> 10000 bps) or `:refund-intent` (delivered? = false -> 0 bps)."
  [offer delivered?]
  (let [prev (double (or (:attested-sla-bps offer) 9500))
        alpha (double (or (:ema-alpha offer) default-sla-ema-alpha))
        obs (if delivered? 10000.0 0.0)
        next-sla (min 10000 (max 0 (long (Math/round (+ (* (- 1.0 alpha) prev) (* alpha obs))))))]
    (-> offer
        (assoc :attested-sla-bps next-sla)
        (update (if delivered? :total-released :total-refunded) (fnil inc 0)))))

;; =============================================================================
;; 4. Per-Epoch Clearing Engine (FIFO Request Order, Halt-Aware Diversion)
;; =============================================================================

(defn clear-market-epoch
  "Clears capability requests for an epoch:
   - Processes requests in canonical deterministic arrival order (`[:epoch :request-seq :request-id]`),
     preventing strategic `max-wtp-ech = 10^9` buyers from jumping the queue
   - Filters out halted/partitioned subnets, automatically diverting traffic to healthy sibling subnets
   - Ranks eligible offers solely by non-manipulable `clearing-utility`
   - Adapts offer prices at end of epoch based on realized utilization
   - Emits counted `:diagnose` reasons for every uncleared order (INV-7)."
  [market requests opts]
  (let [halted (set (or (:halted-subnets opts) #{}))
        u-opts {:alpha (or (:alpha opts) (:alpha market) default-utility-alpha)
                :beta (or (:beta opts) (:beta market) default-utility-beta)}
        ordered-reqs (sort-by (juxt #(long (or (:epoch %) 0))
                                    #(long (or (:request-seq %) 0))
                                    #(str (:request-id %)))
                              requests)
        init-rem (into {} (map (fn [[oid o]] [oid (long (:capacity-units-per-epoch o))]) (:offers market)))
        {:keys [rem cleared uncleared diags effects]}
        (reduce
         (fn [acc req]
           (let [ctype (:capability-type req)
                 units (max 1 (long (or (:units req) 1)))
                 max-wtp (long (or (:max-wtp-ech req) 0))
                 min-sla (long (or (:min-sla-bps req) 0))
                 budget (long (or (:budget-ech req) (* max-wtp units)))
                 ref-p (get-in capability-catalog [ctype :ref-price-ech] 150)
                 ;; All active offers for this capability on non-halted subnets
                 active-offers (filter (fn [o]
                                         (and (= ctype (:capability-type o))
                                              (not (contains? halted (:subnet-id o)))))
                                       (vals (:offers market)))
                 ;; Offers matching price & SLA terms
                 term-offers (filter (fn [o]
                                       (and (<= (long (:unit-price-ech o)) max-wtp)
                                            (>= (long (:attested-sla-bps o)) min-sla)))
                                     active-offers)
                 ;; Offers affordable within buyer's budget
                 budget-offers (filter (fn [o]
                                         (<= (* (long (:unit-price-ech o)) units) budget))
                                       term-offers)
                 ;; Offers with remaining capacity
                 avail-offers (filter (fn [o]
                                        (>= (long (get (:rem acc) (:offer-id o) 0)) units))
                                      budget-offers)]
             (if-let [best (first (sort-by (fn [o]
                                             [(- (double (clearing-utility o ref-p u-opts)))
                                              (:offer-id o)])
                                           avail-offers))]
               (let [oid (:offer-id best)
                     gross (* (long (:unit-price-ech best)) units)]
                 (-> acc
                     (update-in [:rem oid] - units)
                     (update :cleared conj {:request-id (:request-id req)
                                            :buyer-id (:buyer-id req)
                                            :buyer-pubkey (:buyer-pubkey req)
                                            :offer-id oid
                                            :provider-id (:provider-id best)
                                            :provider-pubkey (:provider-pubkey best)
                                            :subnet-id (:subnet-id best)
                                            :capability-type ctype
                                            :units units
                                            :unit-price-ech (long (:unit-price-ech best))
                                            :gross-ech gross})))
               (let [reason (cond
                              (empty? term-offers) :order-uncleared-no-supply
                              (empty? budget-offers) :order-uncleared-insufficient-budget
                              :else :order-uncleared-capacity-exhausted)]
                 (-> acc
                     (update :uncleared conj (assoc req :reason reason))
                     (update-in [:diags reason] (fnil inc 0))
                     (update :effects conj {:effect :diagnose
                                            :reason reason
                                            :request-id (:request-id req)
                                            :capability-type ctype}))))))
         {:rem init-rem :cleared [] :uncleared [] :diags {} :effects []}
         ordered-reqs)
        ;; Adapt offer prices based on realized utilization
        updated-offers (into {}
                             (map (fn [[oid o]]
                                    (if (contains? halted (:subnet-id o))
                                      [oid o]
                                      (let [cap (max 1 (long (:capacity-units-per-epoch o)))
                                            used (- cap (long (get rem oid cap)))
                                            util (/ (double used) (double cap))]
                                        [oid (adapt-offer-price o util)]))))
                             (:offers market))
        mkt' (-> market
                 (assoc :offers updated-offers)
                 (update :total-uncleared-orders (fnil + 0) (count uncleared))
                 (update :diagnostics (fn [d] (merge-with + (or d {}) diags))))]
    {:market mkt'
     :cleared cleared
     :uncleared uncleared
     :diagnostics diags
     :effects effects}))

;; =============================================================================
;; 5. Closed-Loop 42B Buyer Liquidity & Real Signed Transfer Helper
;; =============================================================================

(defn- apply-signed-transfer
  "Executes a real signed `:transfer` receipt via `account/create-transfer` and
   `fabric/apply-finalized-receipts` (zero off-cap minting, zero extra take)."
  [fabric {:keys [sender-pubkey sender-privkey receiver-pubkey amount epoch receipt-id]}]
  (let [st (:state fabric)
        sender-acct (get-in st [:ledger sender-pubkey])
        cur-bal (long (or (:balance sender-acct) 0))
        cur-seq (long (or (:seq sender-acct) 0))
        amt (long amount)]
    (if (and (pos? amt) (>= cur-bal amt) sender-privkey)
      (let [tx (account/create-transfer sender-privkey
                                        {:sender sender-pubkey
                                         :receiver receiver-pubkey
                                         :amount amt
                                         :seq (inc cur-seq)
                                         :deps []
                                         :network-uri (:network-uri fabric)})
            pb (fressian/encode-canonical {:transfer (into {} tx)})
            res (fabric/apply-finalized-receipts
                 fabric
                 [{:receipt-id receipt-id
                   :type :transfer
                   :payload-bytes pb
                   :epoch (long epoch)}]
                 (long epoch))]
        {:ok? true :fabric (:fabric res) :amount amt :effects (:effects res)})
      {:ok? false :fabric fabric :amount 0 :effects []})))

(defn replenish-buyer-liquidity
  "Replenishes an underfunded buyer strictly within 42B ECHO conservation:
   1. Ecosystem Faucet DAC drip via real `vault/spend-via-vault-dac` (if `:faucet-dac-id` active)
   2. Edge-FX secondary-market liquidity cycling via real signed `:transfer` from circulating pool
   Zero off-cap minting!"
  [fabric {:keys [buyer-pubkey needed-ech epoch faucet-dac-id edge-fx-pool]}]
  (let [ep (long (or epoch 1))
        cur-bal (state-machine/spendable-balance (:state fabric) buyer-pubkey)
        shortfall (max 0 (- (long needed-ech) cur-bal))]
    (if (zero? shortfall)
      {:ok? true :fabric fabric :replenished-ech 0 :source :already-funded :effects []}
      (let [;; 1. Try Ecosystem Faucet DAC (gross up ~1% to cover 50 bps take)
            fab1 (if (and faucet-dac-id (contains? (get-in fabric [:state :vault-spend-dacs]) faucet-dac-id))
                   (let [gross-req (max 200 (+ shortfall (quot shortfall 50) 100))
                         dac-res (vault/spend-via-vault-dac (:state fabric)
                                                            {:dac-id faucet-dac-id
                                                             :receiver buyer-pubkey
                                                             :amount gross-req
                                                             :epoch ep})]
                     (if (:ok? dac-res)
                       (assoc fabric :state (:state dac-res))
                       fabric))
                   fabric)
            bal1 (state-machine/spendable-balance (:state fab1) buyer-pubkey)
            rem-short (max 0 (- (long needed-ech) bal1))
            ;; 2. Try Edge-FX secondary-market cycling (signed :transfer from circulating pool)
            fx-res (if (and (pos? rem-short) edge-fx-pool (:pubkey edge-fx-pool) (:privkey edge-fx-pool))
                     (apply-signed-transfer fab1
                                            {:sender-pubkey (:pubkey edge-fx-pool)
                                             :sender-privkey (:privkey edge-fx-pool)
                                             :receiver-pubkey buyer-pubkey
                                             :amount rem-short
                                             :epoch ep
                                             :receipt-id (str "rcpt-edge-fx-" ep "-" (subs buyer-pubkey 0 12))})
                     {:ok? false :fabric fab1 :amount 0 :effects []})
            fab2 (:fabric fx-res)
            bal2 (state-machine/spendable-balance (:state fab2) buyer-pubkey)
            gained (- bal2 cur-bal)]
        (if (>= bal2 (long needed-ech))
          {:ok? true
           :fabric fab2
           :replenished-ech gained
           :source (if (:ok? fx-res) :edge-fx-pool :faucet-dac)
           :effects (:effects fx-res)}
          {:ok? false
           :fabric (update-in fab2 [:diagnostics :buyer-liquidity-insufficient] (fnil inc 0))
           :replenished-ech gained
           :source :insufficient
           :effects (conj (vec (:effects fx-res))
                          {:effect :diagnose
                           :reason :buyer-liquidity-insufficient
                           :buyer-pubkey buyer-pubkey
                           :needed-ech (long needed-ech)
                           :available-ech bal2})})))))

;; =============================================================================
;; 6. Bounded Per-Provider-Epoch Batch One-Shot HTLC Escrow Settlement (INV-6)
;; =============================================================================

(defn- find-node-privkey
  [fabric pubkey-hex]
  (or (some (fn [[_ n]] (when (= pubkey-hex (:pubkey-hex n)) (get-in n [:keypair :private])))
            (:light-nodes fabric))
      (some (fn [[_ n]] (when (= pubkey-hex (:pubkey-hex n)) (get-in n [:keypair :private])))
            (:home-nodes fabric))
      (some (fn [[_ n]] (when (= pubkey-hex (:pubkey-hex n)) (get-in n [:keypair :private])))
            (:infra-nodes fabric))))

(defn settle-cleared-batches
  "Settles cleared capability orders in bounded per-(buyer, provider, subnet) batches through
   real `bitecho.state-machine` one-shot HTLC escrows (`:lock-intent` -> `:release-intent` on
   delivery or `:refund-intent` on timeout/failure):
   - Credits provider's canonical hex pubkey in `[:ledger pk]`
   - Burns `tau = 50 bps` (`min 1 ECHO`) reconciled into `:total-burned`
   - Transfers commercial `subnet_fee` (`<= 500 bps`) to subnet treasury via real signed `:transfer`
   - Updates offer `:attested-sla-bps` EMA strictly from verified release/refund outcomes."
  [fabric market cleared-orders opts]
  (let [ep (long (or (:epoch opts) 1))
        ttl (max 1 (long (or (:escrow-ttl-epochs opts) 3)))
        subnet-fee-bps (min max-commercial-subnet-fee-bps
                            (max 0 (long (or (:subnet-fee-bps opts) (:subnet-fee-bps market) 0))))
        faucet-dac-id (:faucet-dac-id opts)
        edge-fx-pool (:edge-fx-pool opts)
        halted (set (or (:halted-subnets opts) #{}))
        ;; Group into bounded per-(buyer, provider, subnet) batches in deterministic sorted order
        grouped (group-by (juxt :buyer-pubkey :provider-pubkey :subnet-id) cleared-orders)
        batch-keys (sort (keys grouped))]
    (reduce
     (fn [acc [b-idx [buyer-pk prov-pk subnet-id]]]
       (let [orders (get grouped [buyer-pk prov-pk subnet-id])
             gross (reduce + 0 (map #(long (:gross-ech %)) orders))
             offer-ids (vec (distinct (map :offer-id orders)))
             primary-offer (get-in (:market acc) [:offers (first offer-ids)])
             fab0 (:fabric acc)
             ;; Ensure closed-loop buyer liquidity
             rep (replenish-buyer-liquidity fab0 {:buyer-pubkey buyer-pk
                                                  :needed-ech gross
                                                  :epoch ep
                                                  :faucet-dac-id faucet-dac-id
                                                  :edge-fx-pool edge-fx-pool})
             fab1 (:fabric rep)]
         (if-not (:ok? rep)
           (-> acc
               (assoc :fabric fab1)
               (update :effects into (:effects rep)))
           (let [next-es (inc (long (get-in acc [:market :escrow-seq] 0)))
                 escrow-id (str "escrow-ep" ep "-b" next-es)
                 preimage (str "preimage|" (:network-genesis-cid fab1) "|" escrow-id)
                 d-hash (str/lower-case (basalt/bytes->hex (crypto/sha256 (fressian/utf8->bytes preimage))))
                 expires-ep (+ ep ttl)
                 ;; 1. Lock one-shot HTLC escrow
                 lock-res (fabric/apply-finalized-receipts
                           fab1
                           [{:receipt-id (str "rcpt-lock-" escrow-id)
                             :type :lock-intent
                             :escrow-id escrow-id
                             :payer buyer-pk
                             :receiver prov-pk
                             :amount gross
                             :deliverable-hash d-hash
                             :created-epoch ep
                             :expires-epoch expires-ep
                             :epoch ep}]
                           ep)
                 fab-locked (:fabric lock-res)
                 ;; 2. Deterministic delivery draw from isolated subnet-epoch PRNG path
                 settle-rng (crypto/fork-random (:master-rng fab-locked)
                                                (str "market/subnet/" subnet-id "/ep/" ep "/batch/" b-idx))
                 delivery-prob (if (contains? halted subnet-id)
                                 0.0
                                 (double (or (:true-delivery-prob primary-offer) 0.98)))
                 delivered? (< (crypto/next-double settle-rng) delivery-prob)]
             (if delivered?
               ;; 3a. Delivery succeeded -> :release-intent + optional commercial subnet_fee transfer
               (let [prov-bal-before (get-in fab-locked [:state :ledger prov-pk :balance] 0)
                     rel-res (fabric/apply-finalized-receipts
                              fab-locked
                              [{:receipt-id (str "rcpt-rel-" escrow-id)
                                :type :release-intent
                                :escrow-id escrow-id
                                :preimage preimage
                                :sender prov-pk
                                :epoch ep}]
                              ep)
                     fab-rel (:fabric rel-res)
                     prov-bal-after (get-in fab-rel [:state :ledger prov-pk :balance] 0)
                     prov-net (max 0 (- prov-bal-after prov-bal-before))
                     treas-pk (get-in (:market acc) [:subnet-treasuries subnet-id])
                     prov-priv (when (and (pos? subnet-fee-bps) treas-pk (not= treas-pk prov-pk))
                                 (find-node-privkey fab-rel prov-pk))
                     raw-sub-fee (if (and prov-priv (pos? subnet-fee-bps))
                                   (min prov-net (quot (* gross subnet-fee-bps) 10000))
                                   0)
                     fee-res (if (pos? raw-sub-fee)
                               (apply-signed-transfer fab-rel
                                                      {:sender-pubkey prov-pk
                                                       :sender-privkey prov-priv
                                                       :receiver-pubkey treas-pk
                                                       :amount raw-sub-fee
                                                       :epoch ep
                                                       :receipt-id (str "rcpt-subfee-" escrow-id)})
                               {:ok? true :fabric fab-rel :amount 0 :effects []})
                     fab-final (:fabric fee-res)
                     sub-fee-paid (long (:amount fee-res))
                     mkt' (reduce (fn [m oid]
                                    (if (contains? (:offers m) oid)
                                      (update-in m [:offers oid] update-attested-sla true)
                                      m))
                                  (-> (:market acc)
                                      (assoc :escrow-seq next-es)
                                      (update :total-cleared-gmv-ech (fnil + 0) gross)
                                      (update :total-cleared-orders (fnil + 0) (count orders))
                                      (update :total-subnet-fees-ech (fnil + 0) sub-fee-paid)
                                      (update-in [:per-subnet-gmv-ech subnet-id] (fnil + 0) gross)
                                      (update-in [:per-subnet-cleared-orders subnet-id] (fnil + 0) (count orders))
                                      (update-in [:per-subnet-released subnet-id] (fnil inc 0)))
                                  offer-ids)]
                 (-> acc
                     (assoc :fabric fab-final :market mkt')
                     (update :settled-batches inc)
                     (update :cleared-gmv-ech + gross)
                     (update :subnet-fees-ech + sub-fee-paid)
                     (update :effects into (concat (:effects lock-res) (:effects rel-res) (:effects fee-res)))))

               ;; 3b. Delivery failed / timed out -> 100% :refund-intent + counted :diagnose
               ;; Note: `state-machine` sets `closing-ep = (dec block-epoch)` and requires
               ;; `(> closing-ep expires-epoch)`, so `block-epoch = (+ expires-ep 2)`.
               (let [refund-ep (inc expires-ep)
                     refund-block-ep (+ expires-ep 2)
                     ref-res (fabric/apply-finalized-receipts
                              fab-locked
                              [{:receipt-id (str "rcpt-ref-" escrow-id)
                                :type :refund-intent
                                :escrow-id escrow-id
                                :sender buyer-pk
                                :epoch refund-ep}]
                              refund-block-ep)
                     fab-ref (update-in (:fabric ref-res) [:diagnostics :escrow-timeout-refunded] (fnil inc 0))
                     diag {:effect :diagnose
                           :reason :escrow-timeout-refunded
                           :escrow-id escrow-id
                           :subnet-id subnet-id
                           :provider-pubkey prov-pk
                           :amount gross}
                     mkt' (reduce (fn [m oid]
                                    (if (contains? (:offers m) oid)
                                      (update-in m [:offers oid] update-attested-sla false)
                                      m))
                                  (-> (:market acc)
                                      (assoc :escrow-seq next-es)
                                      (update :total-timeout-refunds (fnil inc 0))
                                      (update-in [:per-subnet-refunded subnet-id] (fnil inc 0))
                                      (update-in [:diagnostics :escrow-timeout-refunded] (fnil inc 0)))
                                  offer-ids)]
                 (-> acc
                     (assoc :fabric fab-ref :market mkt')
                     (update :timeout-refunds inc)
                     (update :effects into (conj (vec (concat (:effects lock-res) (:effects ref-res))) diag)))))))))
     {:fabric fabric
      :market market
      :settled-batches 0
      :timeout-refunds 0
      :cleared-gmv-ech 0
      :subnet-fees-ech 0
      :effects []}
     (map-indexed vector batch-keys))))
