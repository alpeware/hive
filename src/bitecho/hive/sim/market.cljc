(ns bitecho.hive.sim.market
  "HIVE-SPEC-001 Workstream C (V3): Pure Sans-IO Endogenous Capability Market & Traffic Engine.

   Implements the full V3 12-item capability-market synthesis:
   - Expanded Capability Catalog & Explicit Calibration Anchors (`capability-calibration-anchors`):
     * Two distinct storage meters: `:storage-bytes-at-rest` (`:recurring-epoch-rent`, `$/GB/mo`
       durability/replication `r in {1,3,6}`) and `:bandwidth-bytes-in-motion` (`:per-task-escrow`,
       `$/GB moved`, ops/egress dominate)
     * `:web-search-extract` three-meter retrieval (`:query-ech` + `:extract-ech` + `:freshness-egress-ech`)
     * `:sandbox-compute` (Firecracker/vCPU-sec + H100 2-8x venue spread)
     * `:llm-inference` with input-token caching (`:cache-hit-rate 0.75`, output tokens scarce meter)
     * `:eval-verify` with judge cost separate from platform fee
     * `:formal-verify` explicitly tagged `:price-anchor-status :unanchored-calibration-param`
     * Full backward compatibility with `:artifact-storage`, `:tts-kokoro`, `:stt-moonshine`,
       `:inverse-game-eval`, `:tac-auction-agent`
   - Venue / SLA Tiers (`:economy`, `:standard`, `:premium`) capturing real multi-fold venue spreads
   - Non-manipulable clearing utility with Failover / Workflow-Insurance bonus:
     `U(offer, ref-price) = alpha*(sla/10000) - beta*(price/max(1,ref)) + gamma*failover_norm`
     100% immune to `:claimed-sla-bps` and buyer `max_wtp = 10^9` queue-jumping (Twin S3)
   - Deflationary compute-bound (`-20%/yr`) vs rising expertise-bound (`+6%/yr`) secular cost curves
     (`step-secular-cost-drift`)
   - Endogenous provider entry & exit (`step-provider-entry-exit`) spawning real Ed25519 Light Nodes
     funded with `min-node-stake-ech = 250,000 ECHO` from `vault:root/ecosystem-reserve` (42B conserved)
     and exiting unprofitable endogenous providers with counted `:diagnose` `:provider-exited`
   - Bounded per-provider-epoch batch one-shot HTLC escrow settlement (`[:escrows ...]`) through
     real `bitecho.state-machine/execute-finalized-blocks`, per-provider/per-capability GMV splits,
     and exact auditable LARG builder cashflow identity.

   Strictly adheres to AGENTS.md Rules 2 & 5 (Pure Sans-IO `.cljc`, zero silent failures)."
  (:require [bitecho.basalt.core :as basalt]
            [bitecho.circles.vault :as vault]
            [bitecho.core.circle :as circle]
            [bitecho.crypto :as crypto]
            [bitecho.economy.account :as account]
            [bitecho.fressian :as fressian]
            [bitecho.hive.sim.fabric :as fabric]
            [bitecho.light-node.core :as light-node]
            [bitecho.state-machine :as state-machine]
            [clojure.string :as str]))

;; =============================================================================
;; 1. V3 Capability Catalog, Calibration Anchors, Venue Tiers & Constants
;; =============================================================================

(def capability-catalog
  "V3 agent-economy capability catalog with explicit billing models, multi-meter pricing,
   cost-curve classification (`:compute-bound` vs `:expertise-bound`), and calibration anchors."
  {:llm-inference
   {:billing-model :per-task-escrow
    :cost-curve :compute-bound
    :ref-price-ech 200
    :marginal-cost-ech 80
    :cache-hit-rate 0.75
    :cache-discount 0.90
    :input-token-ech 40
    :output-token-ech 187
    :venue-spread-max 15.0
    :price-anchor-status :anchored
    :anchor-source "OpenRouter 790T tokens/mo + 15x venue spread; 75% input-token caching makes output tokens scarce meter"}

   :web-search-extract
   {:billing-model :per-task-escrow
    :cost-curve :compute-bound
    :query-ech 30
    :extract-ech 55
    :freshness-egress-ech 25
    :ref-price-ech 110
    :marginal-cost-ech 40
    :price-anchor-status :anchored
    :anchor-source "Tavily $5-8/1k queries + 3-meter query/extract/freshness-egress"}

   :sandbox-compute
   {:billing-model :per-task-escrow
    :cost-curve :compute-bound
    :ref-price-ech 140
    :marginal-cost-ech 50
    :venue-spread-range [2.0 8.0]
    :price-anchor-status :anchored
    :anchor-source "E2B Firecracker/vCPU-sec rate + H100 2-8x venue spread"}

   :eval-verify
   {:billing-model :per-task-escrow
    :cost-curve :compute-bound
    :judge-cost-ech 120
    :platform-fee-ech 40
    :ref-price-ech 160
    :marginal-cost-ech 60
    :price-anchor-status :anchored
    :anchor-source "Braintrust $249/mo platform fee + separate LLM-as-judge compute"}

   :formal-verify
   {:billing-model :per-task-escrow
    :cost-curve :expertise-bound
    :ref-price-ech 400
    :marginal-cost-ech 160
    :price-anchor-status :unanchored-calibration-param
    :anchor-source "$25K/engineer-week unanchored calibration parameter"}

   :storage-bytes-at-rest
   {:billing-model :recurring-epoch-rent
    :cost-curve :compute-bound
    :ref-price-ech 45
    :marginal-cost-ech 15
    :replication-factors #{1 3 6}
    :default-replication-factor 3
    :price-anchor-status :anchored
    :anchor-source "$/GB/mo durability-replicated at-rest storage (r in {1,3,6})"}

   :bandwidth-bytes-in-motion
   {:billing-model :per-task-escrow
    :cost-curve :compute-bound
    :ref-price-ech 95
    :marginal-cost-ech 35
    :ops-egress-share 0.80
    :price-anchor-status :anchored
    :anchor-source "$/GB moved (ops/egress dominate)"}

   ;; Existing V1/V2 capabilities retained for 100% backward compatibility
   :artifact-storage
   {:billing-model :per-task-escrow
    :cost-curve :compute-bound
    :ref-price-ech 60
    :marginal-cost-ech 20
    :alias-of :storage-bytes-at-rest
    :price-anchor-status :anchored
    :anchor-source "Compatibility alias for artifact/object storage"}

   :tts-kokoro
   {:billing-model :per-task-escrow
    :cost-curve :compute-bound
    :ref-price-ech 80
    :marginal-cost-ech 30
    :price-anchor-status :anchored
    :anchor-source "Open-weight Kokoro TTS inference benchmark"}

   :stt-moonshine
   {:billing-model :per-task-escrow
    :cost-curve :compute-bound
    :ref-price-ech 90
    :marginal-cost-ech 35
    :price-anchor-status :anchored
    :anchor-source "Open-weight Moonshine STT inference benchmark"}

   :inverse-game-eval
   {:billing-model :per-task-escrow
    :cost-curve :expertise-bound
    :ref-price-ech 320
    :marginal-cost-ech 120
    :price-anchor-status :unanchored-calibration-param
    :anchor-source "Specialized game-theoretic evaluation calibration parameter"}

   :tac-auction-agent
   {:billing-model :per-task-escrow
    :cost-curve :compute-bound
    :ref-price-ech 150
    :marginal-cost-ech 60
    :price-anchor-status :anchored
    :anchor-source "Autonomous TAC auction agent execution"}})

(def capability-calibration-anchors
  "Explicit calibration anchor inventory for every capability in `capability-catalog`."
  (into (sorted-map)
        (map (fn [[k v]]
               [k (select-keys v [:billing-model :cost-curve :ref-price-ech :marginal-cost-ech
                                  :price-anchor-status :anchor-source])]))
        capability-catalog))

(def capability-types
  "Ordered vector of all supported V3 capability types (top-5 synthesis ranking first)."
  [:llm-inference :web-search-extract :sandbox-compute :eval-verify :formal-verify
   :storage-bytes-at-rest :bandwidth-bytes-in-motion
   :tac-auction-agent :inverse-game-eval :tts-kokoro :stt-moonshine :artifact-storage])

(def capability-demand-weights
  "Demand share weights summing to 100 reflecting Axiom's V3 Top-5 synthesis ranking:
   (1) :llm-inference (28), (2) :web-search-extract (20), (3) :sandbox-compute (16),
   (4) :eval-verify (9) + :formal-verify (6) = 15,
   (5) :storage-bytes-at-rest (6) + :bandwidth-bytes-in-motion (6) = 12,
   plus legacy capabilities (9)."
  {:llm-inference 28
   :web-search-extract 20
   :sandbox-compute 16
   :eval-verify 9
   :formal-verify 6
   :storage-bytes-at-rest 6
   :bandwidth-bytes-in-motion 6
   :tac-auction-agent 4
   :inverse-game-eval 2
   :tts-kokoro 1
   :stt-moonshine 1
   :artifact-storage 1})

(def venue-tiers
  "Venue / SLA tiers capturing multi-fold real-world venue spreads."
  {:economy  {:price-mult 0.4 :default-sla-bps 9100}
   :standard {:price-mult 1.0 :default-sla-bps 9600}
   :premium  {:price-mult 3.5 :default-sla-bps 9950 :min-price-mult 3.0 :max-price-mult 8.0}})

(def default-utility-alpha
  "Default reliability weight in clearing utility (Twin S3 / HIVE-SPEC-001 L3)."
  0.65)

(def default-utility-beta
  "Default normalized price weight in clearing utility (Twin S3 / HIVE-SPEC-001 L3)."
  0.35)

(def default-utility-gamma
  "Default failover / workflow-insurance weight in clearing utility (V3 Item 3)."
  0.15)

(def compute-bound-annual-drift
  "Annual secular cost drift for compute-bound capabilities (-20%/yr)."
  -0.20)

(def expertise-bound-annual-drift
  "Annual secular cost drift for expertise-bound capabilities (+6%/yr)."
  0.06)

(def ^:const epochs-per-year
  "Uncompressed 1-hour epochs per year (365 * 24)."
  8760)

(def min-node-stake-ech
  "Minimum liquid stake required to spawn an endogenous Light Node provider (250,000 ECHO)."
  fabric/larg-initial-stake-ech)

(def max-commercial-subnet-fee-bps
  "Hard cap on commercial subnet take rate (<= 500 bps = 5.0%)."
  500)

(def default-sla-prior-alpha
  "Bayesian/Beta reliability prior alpha0 (19 / (19 + 1) = 9500 bps initial attested SLA).
   Prevents single-timeout death spirals for 99% honest providers (9500 -> 9048 bps > 8800)
   while penalizing 50% Sybil providers below 8800 bps within ~3-4 timeouts."
  19)

(def default-sla-prior-beta
  "Bayesian/Beta reliability prior beta0."
  1)

;; =============================================================================
;; 1b. Multi-Meter Pricing & Weighted Demand Helpers
;; =============================================================================

(defn effective-llm-unit-price
  "Computes effective LLM inference unit price accounting for input-token caching
   (e.g. 75% cache hit rate at 90% discount), leaving output tokens as the scarce meter."
  [{:keys [input-token-ech output-token-ech cache-hit-rate cache-discount]
    :or {input-token-ech 40 output-token-ech 187 cache-hit-rate 0.75 cache-discount 0.90}}]
  (let [hit (min 1.0 (max 0.0 (double cache-hit-rate)))
        disc (min 1.0 (max 0.0 (double cache-discount)))
        eff-in (long (Math/round (* (double input-token-ech) (+ (- 1.0 hit) (* hit (- 1.0 disc))))))
        out (long output-token-ech)]
    {:effective-input-ech eff-in
     :output-token-ech out
     :total-ech (+ eff-in out)}))

(defn web-search-extract-unit-price
  "Computes three-meter retrieval price: query + extract + freshness-egress."
  [{:keys [query-ech extract-ech freshness-egress-ech]
    :or {query-ech 30 extract-ech 55 freshness-egress-ech 25}}]
  (+ (long query-ech) (long extract-ech) (long freshness-egress-ech)))

(defn eval-verify-unit-price
  "Computes eval-verify price separating LLM-as-judge compute from platform fee."
  [{:keys [judge-cost-ech platform-fee-ech]
    :or {judge-cost-ech 120 platform-fee-ech 40}}]
  (+ (long judge-cost-ech) (long platform-fee-ech)))

(defn storage-rent-unit-price
  "Computes recurring epoch rent for `:storage-bytes-at-rest` scaled by replication factor `r in {1,3,6}`."
  [cat-spec replication-factor]
  (let [base (long (or (:ref-price-ech cat-spec) 45))
        r (if (contains? #{1 3 6} replication-factor) (long replication-factor) 3)]
    (max 1 (long (Math/round (* (double base) (/ (double r) 3.0)))))))

(defn sample-capability-by-demand-weight
  "Deterministically samples a capability type from `capability-demand-weights` using `rng`."
  [rng]
  (let [roll (crypto/next-int rng 100)]
    (loop [caps capability-types
           acc 0]
      (let [c (first caps)
            w (long (get capability-demand-weights c 0))
            next-acc (+ acc w)]
        (if (or (< roll next-acc) (empty? (rest caps)))
          c
          (recur (rest caps) next-acc))))))

;; =============================================================================
;; 2. Market State & Capability Offer Management
;; =============================================================================

(defn init-market-state
  "Initializes a pure Sans-IO V3 capability market state."
  ([] (init-market-state {}))
  ([opts]
   {:seed (long (or (:seed opts) 20261124))
    :alpha (double (or (:alpha opts) default-utility-alpha))
    :beta (double (or (:beta opts) default-utility-beta))
    :gamma (double (or (:gamma opts) default-utility-gamma))
    :subnet-fee-bps (min max-commercial-subnet-fee-bps
                         (max 0 (long (or (:subnet-fee-bps opts) 100))))
    :subnet-treasuries (or (:subnet-treasuries opts) {})
    :offers {}
    :pending-refunds []
    :escrow-seq 0
    :endogenous-provider-seq 0
    :endogenous-providers-entered 0
    :endogenous-providers-exited 0
    :total-cleared-gmv-ech 0
    :total-cleared-orders 0
    :total-uncleared-orders 0
    :total-timeout-refunds 0
    :total-subnet-fees-ech 0
    :per-subnet-gmv-ech {}
    :per-subnet-cleared-orders {}
    :per-subnet-released {}
    :per-subnet-refunded {}
    :per-provider-gmv-ech {}
    :per-capability-gmv-ech {}
    :per-provider-earned-revenue-ech {}
    :per-provider-subnet-fees-ech {}
    :per-provider-protocol-burns-ech {}
    :per-buyer-spend-ech {}
    :per-buyer-faucet-replenished-ech {}
    :diagnostics {}}))

(defn register-offer
  "Registers a priced capability offer on `market`. Validates canonical 88-char SPKI pubkey.
   Supports V3 `:venue-tier` (`:economy`, `:standard`, `:premium`) and `:replication-factor`.
   Note: `:attested-sla-bps` is initialized independently of any self-reported `:claimed-sla-bps`."
  [market {:keys [offer-id capability-type unit-price-ech marginal-cost-ech
                  capacity-units-per-epoch provider-id provider-pubkey subnet-id
                  attested-sla-bps claimed-sla-bps true-delivery-prob
                  sla-prior-alpha sla-prior-beta venue-tier replication-factor
                  endogenous? status]}]
  (let [norm-pk (circle/normalize-spki-pubkey provider-pubkey)
        cat (get capability-catalog capability-type {:ref-price-ech 150 :marginal-cost-ech 50 :billing-model :per-task-escrow :cost-curve :compute-bound})
        tier-key (if (contains? venue-tiers venue-tier) venue-tier :standard)
        tier-spec (get venue-tiers tier-key)
        base-mc (max 1 (long (or marginal-cost-ech (:marginal-cost-ech cat))))
        mc (if (and (= tier-key :economy) (nil? marginal-cost-ech))
             (max 1 (long (Math/round (* (double base-mc) 0.5))))
             base-mc)
        tier-ref-price (max mc (long (Math/round (* (double (:ref-price-ech cat))
                                                    (double (:price-mult tier-spec))))))
        price (max mc (long (or unit-price-ech
                                (if (some? venue-tier) tier-ref-price (:ref-price-ech cat)))))
        cap (max 1 (long (or capacity-units-per-epoch 10)))
        a0 (max 1 (long (or sla-prior-alpha default-sla-prior-alpha)))
        b0 (max 1 (long (or sla-prior-beta default-sla-prior-beta)))
        prior-sla (if (some? venue-tier)
                    (long (:default-sla-bps tier-spec))
                    (long (Math/round (* 10000.0 (/ (double a0) (double (+ a0 b0)))))))
        init-sla (min 10000 (max 0 (long (or attested-sla-bps prior-sla))))
        delivery-p (min 1.0 (max 0.0 (double (if (some? true-delivery-prob) true-delivery-prob 0.98))))]
    (if-not (and (string? offer-id) (some? norm-pk) (contains? capability-catalog capability-type))
      (update-in market [:diagnostics :invalid-offer-rejected] (fnil inc 0))
      (assoc-in market [:offers offer-id]
                {:offer-id offer-id
                 :capability-type capability-type
                 :billing-model (or (:billing-model cat) :per-task-escrow)
                 :cost-curve (or (:cost-curve cat) :compute-bound)
                 :venue-tier tier-key
                 :replication-factor (or replication-factor (:default-replication-factor cat))
                 :unit-price-ech price
                 :marginal-cost-ech mc
                 :marginal-cost-float (double mc)
                 :capacity-units-per-epoch cap
                 :provider-id provider-id
                 :provider-pubkey norm-pk
                 :subnet-id (str subnet-id)
                 :attested-sla-bps init-sla
                 :claimed-sla-bps (long (or claimed-sla-bps init-sla))
                 :true-delivery-prob delivery-p
                 :sla-prior-alpha a0
                 :sla-prior-beta b0
                 :total-released 0
                 :total-refunded 0
                 :last-utilization 0.0
                 :high-util-streak 0
                 :low-util-streak 0
                 :endogenous? (boolean endogenous?)
                 :status (or status :active)}))))

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
;; 3. Non-Manipulable Clearing Utility (with Failover Insurance), Adaptive Pricing & Cost Drift
;; =============================================================================

(defn failover-redundancy-norm
  "Computes normalized failover/workflow-insurance redundancy in [0.0, 1.0]:
   - `< 2` healthy redundant providers -> `0.0`
   - `>= 2` healthy redundant providers -> `min(1.0, (count - 1) / 4.0 + cross-subnet-bonus)`."
  ([redundant-provider-count]
   (failover-redundancy-norm redundant-provider-count 0))
  ([redundant-provider-count cross-subnet-peers]
   (let [n (long (or redundant-provider-count 0))
         cs (long (or cross-subnet-peers 0))]
     (if (< n 2)
       0.0
       (min 1.0 (+ (/ (double (dec n)) 4.0)
                   (if (pos? cs) 0.25 0.0)))))))

(defn clearing-utility
  "Computes non-manipulable V3 clearing utility (Twin S3 + Failover Workflow Insurance):
     U(offer, ref-price) = alpha * (attested_sla_bps / 10000.0)
                         - beta  * (unit_price_ech / max(1, ref_price_ech))
                         + gamma * failover_redundancy_norm
   Strictly ignores `:claimed-sla-bps` and buyer `:max-wtp-ech`."
  ([offer ref-price-ech]
   (clearing-utility offer ref-price-ech nil))
  ([offer ref-price-ech opts]
   (let [alpha (double (or (:alpha opts) default-utility-alpha))
         beta (double (or (:beta opts) default-utility-beta))
         gamma (double (or (:gamma opts) default-utility-gamma))
         sla (double (or (:attested-sla-bps offer) 0))
         price (double (or (:unit-price-ech offer) 1))
         ref-p (double (max 1 (long (or ref-price-ech 1))))
         failover-norm (cond
                         (some? (:failover-redundancy-norm opts))
                         (min 1.0 (max 0.0 (double (:failover-redundancy-norm opts))))

                         (some? (:redundant-provider-count opts))
                         (failover-redundancy-norm (:redundant-provider-count opts)
                                                   (or (:cross-subnet-peers opts) 0))

                         (some? (:redundant-provider-count offer))
                         (failover-redundancy-norm (:redundant-provider-count offer) 0)

                         :else 0.0)]
     (+ (- (* alpha (/ sla 10000.0))
           (* beta (/ price ref-p)))
        (* gamma failover-norm)))))

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

(defn step-secular-cost-drift
  "Applies V3 secular marginal-cost drift compounded cleanly by time-compression factor `C`:
   - `:compute-bound` capabilities deflate at `-20%/yr` (`0.80^(C/8760)` per epoch)
   - `:expertise-bound` capabilities rise at `+6%/yr` (`1.06^(C/8760)` per epoch)
   Tracks exact `:marginal-cost-float` to prevent integer rounding stall and keeps
   `:unit-price-ech >= :marginal-cost-ech`."
  [market {:keys [time-compression-factor] :or {time-compression-factor 1}}]
  (let [c (max 1 (long time-compression-factor))
        yr-frac (/ (double c) (double epochs-per-year))
        comp-mult (Math/pow (+ 1.0 compute-bound-annual-drift) yr-frac)
        exp-mult (Math/pow (+ 1.0 expertise-bound-annual-drift) yr-frac)
        updated-offers
        (into {}
              (map (fn [[oid o]]
                     (if (= :exited (:status o))
                       [oid o]
                       (let [curve (or (:cost-curve o)
                                       (get-in capability-catalog [(:capability-type o) :cost-curve])
                                       :compute-bound)
                             mult (if (= :expertise-bound curve) exp-mult comp-mult)
                             prev-f (double (or (:marginal-cost-float o) (:marginal-cost-ech o) 1))
                             next-f (max 1.0 (* prev-f mult))
                             next-mc (max 1 (long (Math/round next-f)))
                             next-price (max next-mc (long (:unit-price-ech o)))]
                         [oid (assoc o
                                     :marginal-cost-float next-f
                                     :marginal-cost-ech next-mc
                                     :unit-price-ech next-price)]))))
              (:offers market))]
    (assoc market :offers updated-offers)))

(defn update-attested-sla
  "Updates an offer's escrow-attested SLA using a Bayesian/Beta reliability estimator
   `round(10000 * (alpha0 + delivered) / (alpha0 + beta0 + delivered + refunded))`
   strictly from verified on-chain `:release-intent` or `:refund-intent` outcomes."
  [offer delivered?]
  (let [offer' (update offer (if delivered? :total-released :total-refunded) (fnil inc 0))
        a0 (max 1 (long (or (:sla-prior-alpha offer') default-sla-prior-alpha)))
        b0 (max 1 (long (or (:sla-prior-beta offer') default-sla-prior-beta)))
        rel (long (or (:total-released offer') 0))
        ref (long (or (:total-refunded offer') 0))
        next-sla (min 10000 (max 0 (long (Math/round (* 10000.0 (/ (double (+ a0 rel))
                                                                   (double (+ a0 b0 rel ref))))))))]
    (assoc offer' :attested-sla-bps next-sla)))

;; =============================================================================
;; 4. Per-Epoch Clearing Engine (FIFO Request Order, Failover Utility, Halt Diversion)
;; =============================================================================

(defn clear-market-epoch
  "Clears capability requests for an epoch:
   - Processes requests in canonical deterministic arrival order (`[:epoch :request-seq :request-id]`),
     preventing strategic `max-wtp-ech = 10^9` buyers from jumping the queue
   - Filters out halted/partitioned subnets and `:exited` offers, automatically diverting traffic
   - Ranks eligible offers solely by non-manipulable `clearing-utility` (including failover bonus
     when `>= 2` healthy redundant providers exist)
   - Adapts offer prices at end of epoch based on realized utilization
   - Emits counted `:diagnose` reasons for every uncleared order (INV-7)."
  [market requests opts]
  (let [halted (set (or (:halted-subnets opts) #{}))
        u-opts {:alpha (or (:alpha opts) (:alpha market) default-utility-alpha)
                :beta (or (:beta opts) (:beta market) default-utility-beta)
                :gamma (or (:gamma opts) (:gamma market) default-utility-gamma)}
        ordered-reqs (sort-by (juxt #(long (or (:epoch %) 0))
                                    #(long (or (:request-seq %) 0))
                                    #(str (:request-id %)))
                              requests)
        init-rem (into {} (map (fn [[oid o]] [oid (if (= :exited (:status o))
                                                    0
                                                    (long (:capacity-units-per-epoch o)))])
                               (:offers market)))
        {:keys [rem cleared uncleared diags effects]}
        (reduce
         (fn [acc req]
           (let [ctype (:capability-type req)
                 units (max 1 (long (or (:units req) 1)))
                 max-wtp (long (or (:max-wtp-ech req) 0))
                 min-sla (long (or (:min-sla-bps req) 0))
                 budget (long (or (:budget-ech req) (* max-wtp units)))
                 ref-p (get-in capability-catalog [ctype :ref-price-ech] 150)
                 ;; All active non-exited offers for this capability on non-halted subnets (forbidding self-trades)
                 active-offers (filter (fn [o]
                                         (and (= ctype (:capability-type o))
                                              (not= :exited (:status o))
                                              (not= (:buyer-pubkey req) (:provider-pubkey o))
                                              (not (contains? halted (:subnet-id o)))))
                                       (vals (:offers market)))
                 ;; Healthy redundant peers for failover/workflow-insurance bonus
                 healthy-peers (filter #(>= (long (:attested-sla-bps %)) 8800) active-offers)
                 healthy-prov-count (count (distinct (map :provider-pubkey healthy-peers)))
                 healthy-subnets (set (map :subnet-id healthy-peers))
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
                                             (let [cross-subs (count (disj healthy-subnets (:subnet-id o)))
                                                   o-u-opts (assoc u-opts
                                                                   :redundant-provider-count healthy-prov-count
                                                                   :cross-subnet-peers cross-subs)]
                                               [(- (double (clearing-utility o ref-p o-u-opts)))
                                                (:offer-id o)]))
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
                                    (if (or (= :exited (:status o)) (contains? halted (:subnet-id o)))
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
   `fabric/apply-finalized-receipts` (zero off-cap minting, zero extra take, zero silent failures)."
  [fabric {:keys [sender-pubkey sender-privkey receiver-pubkey amount epoch receipt-id]}]
  (let [st (:state fabric)
        sender-acct (get-in st [:ledger sender-pubkey])
        cur-bal (long (or (:balance sender-acct) 0))
        cur-seq (long (or (:seq sender-acct) 0))
        cur-deps (vec (or (:deps sender-acct) []))
        amt (long amount)
        failed-before (long (or (:failed-receipts-count st) 0))]
    (if (and (pos? amt) (>= cur-bal amt) sender-privkey (not= sender-pubkey receiver-pubkey))
      (let [tx (account/create-transfer sender-privkey
                                        {:sender sender-pubkey
                                         :receiver receiver-pubkey
                                         :amount amt
                                         :seq (inc cur-seq)
                                         :deps cur-deps
                                         :network-uri (:network-uri fabric)})
            pb (fressian/encode-canonical {:transfer (into {} tx)})
            res (fabric/apply-finalized-receipts
                 fabric
                 [{:receipt-id receipt-id
                   :type :transfer
                   :payload-bytes pb
                   :epoch (max 0 (dec (long epoch)))}]
                 (long epoch))
            failed-after (long (or (get-in res [:fabric :state :failed-receipts-count]) 0))]
        (if (and (:ok? res) (= failed-before failed-after))
          {:ok? true :fabric (:fabric res) :amount amt :effects (:effects res)}
          (let [diag {:effect :diagnose
                      :reason :transfer-receipt-failed
                      :receipt-id receipt-id
                      :sender sender-pubkey
                      :receiver receiver-pubkey
                      :amount amt}]
            {:ok? false
             :fabric (update-in (:fabric res) [:diagnostics :transfer-receipt-failed] (fnil inc 0))
             :amount 0
             :effects (conj (vec (:effects res)) diag)})))
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
;; 5b. Endogenous Provider Entry & Exit (V3 Item 5, 42B Conserved)
;; =============================================================================

(defn- fund-endogenous-provider-stake
  "Funds a newly spawned endogenous Light Node with `min-node-stake-ech` (250,000 ECHO)
   from `vault:root/ecosystem-reserve` via real 2-of-5 multisig `vault/spend-vault` (grossing up
   50 bps so net credited >= 250,000 ECHO) OR fallback `edge-fx-pool`, strictly conserving 42B."
  [fabric provider-pubkey epoch edge-fx-pool]
  (let [vid "vault:root/ecosystem-reserve"
        v (get-in fabric [:state :vaults vid])
        p-info (get-in fabric [:vault-puzzles vid])
        ;; Gross up 50 bps so provider receives >= min-node-stake-ech (250,000 ECHO)
        gross-amt (+ min-node-stake-ech (quot (* min-node-stake-ech 55) 10000) 10)
        simon (get-in fabric [:light-nodes :founder-simon])
        axiom (get-in fabric [:light-nodes :agent-axiom])]
    (cond
      (and v p-info simon axiom (>= (long (:balance v)) gross-amt))
      (let [fab-acct (if (contains? (get-in fabric [:state :ledger]) provider-pubkey)
                       fabric
                       (assoc-in fabric [:state :ledger provider-pubkey]
                                 (account/map->AccountState {:balance 0 :seq 0 :deps []})))
            sol (fabric/build-multisig-vault-solution
                 {:fabric fab-acct
                  :vault-id vid
                  :receiver-pubkey provider-pubkey
                  :release-amount gross-amt
                  :next-puzzle-hash (:puzzle-hash v)
                  :signers [simon axiom]})
            res (vault/spend-vault (:state fab-acct)
                                   {:vault-id vid
                                    :vault-seq (:vault-seq v)
                                    :puzzle-source (:puzzle-source p-info)
                                    :solution sol
                                    :epoch (long epoch)})]
        (if (:ok? res)
          {:ok? true :fabric (assoc fab-acct :state (:state res)) :source :ecosystem-reserve}
          {:ok? false :fabric fabric :source :ecosystem-reserve-failed}))

      (and edge-fx-pool (:pubkey edge-fx-pool) (:privkey edge-fx-pool))
      (let [tx-res (apply-signed-transfer fabric
                                          {:sender-pubkey (:pubkey edge-fx-pool)
                                           :sender-privkey (:privkey edge-fx-pool)
                                           :receiver-pubkey provider-pubkey
                                           :amount min-node-stake-ech
                                           :epoch (long epoch)
                                           :receipt-id (str "rcpt-endo-stake-" epoch "-" (subs provider-pubkey 0 12))})]
        {:ok? (:ok? tx-res) :fabric (:fabric tx-res) :source :edge-fx-pool})

      :else
      {:ok? false :fabric fabric :source :no-funding-source})))

(defn step-provider-entry-exit
  "V3 Endogenous Provider Entry & Exit:
   - Updates consecutive `:high-util-streak` (`util > 0.80` & `price > 1.2 * MC`) and
     `:low-util-streak` (`util < 0.20` & `price <= MC`) per active offer.
   - Entry: when a capability has sustained excess demand (`high-util-streak >= entry-streak-epochs`),
     spawns a new deterministic Ed25519 Light Node provider funded with `min-node-stake-ech = 250,000 ECHO`
     from `vault:root/ecosystem-reserve` (42B conserved) and registers its offer with counted
     `:diagnose` `:provider-entered`.
   - Exit: when an endogenous provider stays unprofitable (`low-util-streak >= exit-streak-epochs`),
     marks it `:status :exited` and emits counted `:diagnose` `:provider-exited`."
  [fabric market {:keys [epoch entry-streak-epochs exit-streak-epochs max-endogenous-per-cap
                         halted-subnets edge-fx-pool unit-mult]
                  :or {epoch 1
                       entry-streak-epochs 3
                       exit-streak-epochs 5
                       max-endogenous-per-cap 3
                       halted-subnets #{}
                       unit-mult 1}}]
  (let [halted (set halted-subnets)
        entry-k (max 1 (long entry-streak-epochs))
        exit-k (max 1 (long exit-streak-epochs))
        max-per-cap (max 1 (long max-endogenous-per-cap))
        u-mult (max 1 (long unit-mult))
        ;; 1. Update high/low utilization streaks on active non-halted offers
        streaked-offers
        (into {}
              (map (fn [[oid o]]
                     (if (or (= :exited (:status o)) (contains? halted (:subnet-id o)))
                       [oid o]
                       (let [u (double (or (:last-utilization o) 0.0))
                             price (double (or (:unit-price-ech o) 0))
                             mc (double (max 1 (long (or (:marginal-cost-ech o) 1))))
                             hot? (and (> u 0.80) (> price (* 1.2 mc)))
                             cold? (and (< u 0.20) (<= price mc))]
                         [oid (assoc o
                                     :high-util-streak (if hot? (inc (long (or (:high-util-streak o) 0))) 0)
                                     :low-util-streak (if cold? (inc (long (or (:low-util-streak o) 0))) 0))]))))
              (:offers market))
        mkt1 (assoc market :offers streaked-offers)
        ;; 2. Exit unprofitable endogenous providers
        {:keys [market exit-effects]}
        (reduce
         (fn [{:keys [market exit-effects]} [oid o]]
           (if (and (:endogenous? o)
                    (= :active (:status o))
                    (>= (long (or (:low-util-streak o) 0)) exit-k))
             (let [diag {:effect :diagnose
                         :reason :provider-exited
                         :offer-id oid
                         :provider-id (:provider-id o)
                         :capability-type (:capability-type o)
                         :epoch (long epoch)}]
               {:market (-> market
                            (assoc-in [:offers oid :status] :exited)
                            (update :endogenous-providers-exited (fnil inc 0))
                            (update-in [:diagnostics :provider-exited] (fnil inc 0)))
                :exit-effects (conj exit-effects diag)})
             {:market market :exit-effects exit-effects}))
         {:market mkt1 :exit-effects []}
         (sort-by key (:offers mkt1)))
        ;; 3. Entry: spawn new endogenous Light Node provider for hot capabilities
        hot-caps (->> (vals (:offers market))
                      (filter #(and (= :active (:status %))
                                    (not (contains? halted (:subnet-id %)))
                                    (>= (long (or (:high-util-streak %) 0)) entry-k)))
                      (map :capability-type)
                      distinct
                      sort)
        healthy-subnets (vec (sort (remove halted (keys (:subnet-treasuries market)))))
        fallback-subnet (or (first healthy-subnets) "root/markets/larg-tac")
        {:keys [fabric market entry-effects]}
        (reduce
         (fn [{:keys [fabric market entry-effects]} ctype]
           (let [existing-endo (count (filter #(and (:endogenous? %)
                                                    (= :active (:status %))
                                                    (= ctype (:capability-type %)))
                                              (vals (:offers market))))]
             (if (>= existing-endo max-per-cap)
               {:fabric fabric :market market :entry-effects entry-effects}
               (let [next-seq (inc (long (or (:endogenous-provider-seq market) 0)))
                     p-id (keyword (str "endo-prov-" (name ctype) "-" next-seq))
                     kp-info (fabric/derive-keypair (:master-rng fabric)
                                                    (str "market/endogenous-provider/" (name ctype) "/ep/" epoch "/seq/" next-seq))
                     fund-res (fund-endogenous-provider-stake fabric (:pubkey-hex kp-info) epoch edge-fx-pool)]
                 (if-not (:ok? fund-res)
                   {:fabric fabric
                    :market (update-in market [:diagnostics :provider-entry-funding-failed] (fnil inc 0))
                    :entry-effects (conj entry-effects {:effect :diagnose
                                                        :reason :provider-entry-funding-failed
                                                        :capability-type ctype
                                                        :epoch (long epoch)})}
                   (let [fab-funded (:fabric fund-res)
                         target-sub (if (seq healthy-subnets)
                                      (nth healthy-subnets (mod next-seq (count healthy-subnets)))
                                      fallback-subnet)
                         ls (light-node/init-light-state {:pubkey-hex (:pubkey-hex kp-info)
                                                          :fingerprint (:pubkey-cid kp-info)}
                                                         {:epoch (long epoch) :autonomous? true})
                         node {:node-id p-id
                               :tier :light
                               :pubkey-hex (:pubkey-hex kp-info)
                               :pubkey-cid (:pubkey-cid kp-info)
                               :sovereign-uri (str (:network-uri fab-funded) "/id/" (:pubkey-cid kp-info))
                               :keypair (:keypair kp-info)
                               :light-state ls
                               :subscribed-subnets #{target-sub}
                               :endogenous? true
                               :archetype {:archetype-id p-id :role "Endogenous Market Entrant Provider"
                                           :affiliation "Organic Market Entry" :external-archetype? true
                                           :opt-in? false :endorsed? false :synthetic-archetype? true}}
                         fab' (assoc-in fab-funded [:light-nodes p-id] node)
                         cat (get capability-catalog ctype {:ref-price-ech 150 :marginal-cost-ech 60})
                         oid (str "offer-endo-" (name ctype) "-" next-seq)
                         ;; Reset high-util-streak on existing offers for this cap so we don't double-spawn next epoch
                         mkt-reset (reduce (fn [m [k o]]
                                             (if (= ctype (:capability-type o))
                                               (assoc-in m [:offers k :high-util-streak] 0)
                                               m))
                                           market
                                           (:offers market))
                         mkt' (-> mkt-reset
                                  (assoc :endogenous-provider-seq next-seq)
                                  (update :endogenous-providers-entered (fnil inc 0))
                                  (update-in [:diagnostics :provider-entered] (fnil inc 0))
                                  (register-offer {:offer-id oid
                                                   :capability-type ctype
                                                   :unit-price-ech (:ref-price-ech cat)
                                                   :marginal-cost-ech (:marginal-cost-ech cat)
                                                   :capacity-units-per-epoch (* u-mult 6)
                                                   :provider-id p-id
                                                   :provider-pubkey (:pubkey-hex kp-info)
                                                   :subnet-id target-sub
                                                   :venue-tier :standard
                                                   :attested-sla-bps 9500
                                                   :true-delivery-prob 0.98
                                                   :endogenous? true
                                                   :status :active}))
                         diag {:effect :diagnose
                               :reason :provider-entered
                               :offer-id oid
                               :provider-id p-id
                               :capability-type ctype
                               :subnet-id target-sub
                               :epoch (long epoch)}]
                     {:fabric fab'
                      :market mkt'
                      :entry-effects (conj entry-effects diag)}))))))
         {:fabric fabric :market market :entry-effects []}
         hot-caps)]
    {:fabric fabric
     :market market
     :effects (into (vec exit-effects) entry-effects)}))

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

(defn- refund-matured?
  "Returns true when a pending timed-out escrow can be refunded at real `block-epoch = current-epoch`
   (`state-machine` sets `closing-ep = (dec block-epoch)` and requires `(> closing-ep expires-epoch)`)."
  [pending current-epoch]
  (> (max 0 (dec (long current-epoch))) (long (:expires-epoch pending))))

(defn- apply-matured-refund-stats
  "Updates market SLA, timeout counts, and counted `:diagnose` effects for matured `:refund-intent`s.
   Increments `:escrow-timeout-refunded` in ONE place only (on `market`, never duplicated on `fabric`)."
  [market matured]
  (reduce
   (fn [{:keys [market effects]} {:keys [escrow-id subnet-id provider-pubkey amount offer-ids]}]
     (let [mkt' (reduce (fn [m oid]
                          (if (contains? (:offers m) oid)
                            (update-in m [:offers oid] update-attested-sla false)
                            m))
                        (-> market
                            (update :total-timeout-refunds (fnil inc 0))
                            (update-in [:per-subnet-refunded subnet-id] (fnil inc 0))
                            (update-in [:diagnostics :escrow-timeout-refunded] (fnil inc 0)))
                        offer-ids)
           diag {:effect :diagnose
                 :reason :escrow-timeout-refunded
                 :escrow-id escrow-id
                 :subnet-id subnet-id
                 :provider-pubkey provider-pubkey
                 :amount amount}]
       {:market mkt'
        :effects (conj effects diag)}))
   {:market market :effects []}
   matured))

(defn settle-pending-refunds
  "Settles all matured timed-out escrows in `(:pending-refunds market)` via real `:refund-intent`
   receipts at the real current `block-epoch = current-epoch` (never time-traveling to a future epoch)."
  [fabric market current-epoch]
  (let [ep (long current-epoch)
        closing-ep (max 0 (dec ep))
        pending (vec (or (:pending-refunds market) []))
        matured (filterv #(refund-matured? % ep) pending)
        still-pending (filterv #(not (refund-matured? % ep)) pending)]
    (if (empty? matured)
      {:fabric fabric
       :market market
       :timeout-refunds 0
       :effects []}
      (let [ref-receipts (mapv (fn [{:keys [escrow-id buyer-pubkey]}]
                                 {:receipt-id (str "rcpt-ref-" escrow-id)
                                  :type :refund-intent
                                  :escrow-id escrow-id
                                  :sender buyer-pubkey
                                  :epoch closing-ep})
                               matured)
            ref-res (fabric/apply-finalized-receipts fabric ref-receipts ep)
            {:keys [market effects]} (apply-matured-refund-stats
                                      (assoc market :pending-refunds still-pending)
                                      matured)]
        {:fabric (:fabric ref-res)
         :market market
         :timeout-refunds (count matured)
         :effects (into (vec (:effects ref-res)) effects)}))))

(defn settle-cleared-batches
  "Settles matured pending refunds and cleared capability orders in bounded per-(buyer, provider, subnet)
   batches through real `bitecho.state-machine` one-shot HTLC escrows at the REAL `block-epoch = ep`:
   - Settles matured `:pending-refunds` via `:refund-intent` at real `block-epoch = ep` (zero time-travel)
   - Forks delivery RNG by canonical content key `(subnet-id, ep, buyer-pk, prov-pk)` (never global batch index)
   - Executes all matured `:refund-intent`, `:lock-intent`, and `:release-intent` receipts for epoch `ep`
     in a SINGLE finalized block at `block-epoch = ep` so `apply-epoch-treasury-boundary` sees 100% of
     epoch `ep`'s settlement takes
   - Queues timed-out escrows in `[:pending-refunds]` until matured at real `block-epoch`
   - Transfers commercial `subnet_fee` (`<= 500 bps`) to subnet treasury via real signed `:transfer`
   - Tracks `:per-provider-gmv-ech`, `:per-capability-gmv-ech`, and exact buyer/provider cashflow legs
   - Updates offer `:attested-sla-bps` via Bayesian/Beta estimator strictly from verified outcomes."
  [fabric market cleared-orders opts]
  (let [ep (long (or (:epoch opts) 1))
        closing-ep (max 0 (dec ep))
        ttl (max 1 (long (or (:escrow-ttl-epochs opts) 3)))
        subnet-fee-bps (min max-commercial-subnet-fee-bps
                            (max 0 (long (or (:subnet-fee-bps opts) (:subnet-fee-bps market) 0))))
        faucet-dac-id (:faucet-dac-id opts)
        edge-fx-pool (:edge-fx-pool opts)
        halted (set (or (:halted-subnets opts) #{}))
        ;; 1. Partition matured vs still-pending timeout refunds
        pending-all (vec (or (:pending-refunds market) []))
        matured (filterv #(refund-matured? % ep) pending-all)
        still-pending (filterv #(not (refund-matured? % ep)) pending-all)
        ref-receipts (mapv (fn [{:keys [escrow-id buyer-pubkey]}]
                             {:receipt-id (str "rcpt-ref-" escrow-id)
                              :type :refund-intent
                              :escrow-id escrow-id
                              :sender buyer-pubkey
                              :epoch closing-ep})
                           matured)
        ;; 2. Group cleared orders into deterministic sorted per-(buyer, provider, subnet) batches
        grouped (group-by (juxt :buyer-pubkey :provider-pubkey :subnet-id) cleared-orders)
        batch-keys (sort (keys grouped))
        buyer-pk->id (into {} (map (juxt :buyer-pubkey :buyer-id) cleared-orders))
        ;; 3. Pre-replenish each distinct buyer for their total gross spend this epoch
        buyer-totals (reduce (fn [acc bk]
                               (let [[buyer-pk _ _] bk
                                     gross (reduce + 0 (map #(long (:gross-ech %)) (get grouped bk)))]
                                 (update acc buyer-pk (fnil + 0) gross)))
                             (sorted-map)
                             batch-keys)
        {:keys [fab-rep rep-effects funded-buyers buyer-replenished]}
        (reduce-kv (fn [acc buyer-pk needed]
                     (let [rep (replenish-buyer-liquidity (:fab-rep acc)
                                                          {:buyer-pubkey buyer-pk
                                                           :needed-ech needed
                                                           :epoch ep
                                                           :faucet-dac-id faucet-dac-id
                                                           :edge-fx-pool edge-fx-pool})
                           bid (get buyer-pk->id buyer-pk)
                           gained (long (or (:replenished-ech rep) 0))]
                       (-> acc
                           (assoc :fab-rep (:fabric rep))
                           (update :rep-effects into (:effects rep))
                           (cond-> (:ok? rep) (update :funded-buyers conj buyer-pk))
                           (cond-> (and (some? bid) (pos? gained))
                             (update-in [:buyer-replenished bid] (fnil + 0) gained)))))
                   {:fab-rep fabric :rep-effects [] :funded-buyers #{} :buyer-replenished {}}
                   buyer-totals)
        ;; 4. Build lock/release receipts and new pending refunds for all funded batches
        {:keys [escrow-seq batch-receipts delivered-batches new-pending]}
        (reduce
         (fn [acc [buyer-pk prov-pk subnet-id]]
           (if-not (contains? funded-buyers buyer-pk)
             acc
             (let [orders (get grouped [buyer-pk prov-pk subnet-id])
                   gross (reduce + 0 (map #(long (:gross-ech %)) orders))
                   offer-ids (vec (distinct (map :offer-id orders)))
                   primary-offer (get-in market [:offers (first offer-ids)])
                   next-es (inc (long (:escrow-seq acc)))
                   escrow-id (str "escrow-ep" ep "-b" next-es)
                   preimage (str "preimage|" (:network-genesis-cid fab-rep) "|" escrow-id)
                   d-hash (str/lower-case (basalt/bytes->hex (crypto/sha256 (fressian/utf8->bytes preimage))))
                   expires-ep (+ closing-ep ttl)
                   lock-rcpt {:receipt-id (str "rcpt-lock-" escrow-id)
                              :type :lock-intent
                              :escrow-id escrow-id
                              :payer buyer-pk
                              :receiver prov-pk
                              :amount gross
                              :deliverable-hash d-hash
                              :created-epoch closing-ep
                              :expires-epoch expires-ep
                              :epoch closing-ep}
                   ;; Isolated canonical content-keyed PRNG fork (never global batch index!)
                   settle-rng (crypto/fork-random (:master-rng fab-rep)
                                                  (str "market/subnet/" subnet-id
                                                       "/ep/" ep
                                                       "/buyer/" (subs buyer-pk 0 16)
                                                       "/prov/" (subs prov-pk 0 16)))
                   delivery-prob (if (contains? halted subnet-id)
                                   0.0
                                   (double (or (:true-delivery-prob primary-offer) 0.98)))
                   delivered? (< (crypto/next-double settle-rng) delivery-prob)]
               (if delivered?
                 (let [rel-rcpt {:receipt-id (str "rcpt-rel-" escrow-id)
                                 :type :release-intent
                                 :escrow-id escrow-id
                                 :preimage preimage
                                 :sender prov-pk
                                 :epoch closing-ep}]
                   (-> acc
                       (assoc :escrow-seq next-es)
                       (update :batch-receipts into [lock-rcpt rel-rcpt])
                       (update :delivered-batches conj {:escrow-id escrow-id
                                                        :buyer-id (:buyer-id (first orders))
                                                        :buyer-pubkey buyer-pk
                                                        :provider-pubkey prov-pk
                                                        :provider-id (:provider-id (first orders))
                                                        :subnet-id subnet-id
                                                        :gross gross
                                                        :orders orders
                                                        :offer-ids offer-ids})))
                 (-> acc
                     (assoc :escrow-seq next-es)
                     (update :batch-receipts conj lock-rcpt)
                     (update :new-pending conj {:escrow-id escrow-id
                                                :buyer-id (:buyer-id (first orders))
                                                :buyer-pubkey buyer-pk
                                                :provider-pubkey prov-pk
                                                :subnet-id subnet-id
                                                :amount gross
                                                :expires-epoch expires-ep
                                                :offer-ids offer-ids}))))))
         {:escrow-seq (long (:escrow-seq market 0))
          :batch-receipts []
          :delivered-batches []
          :new-pending []}
         batch-keys)
        ;; 5. Execute all matured refunds + locks + releases in ONE finalized block at real block-epoch = ep
        all-block-receipts (into ref-receipts batch-receipts)
        block-res (if (seq all-block-receipts)
                    (fabric/apply-finalized-receipts fab-rep all-block-receipts ep)
                    {:ok? true :fabric fab-rep :effects []})
        fab-settled (:fabric block-res)
        ;; 6. Update market stats for matured refunds & buyer replenishments
        mkt-with-pending (-> market
                             (assoc :escrow-seq escrow-seq
                                    :pending-refunds (into still-pending new-pending))
                             (update :per-buyer-faucet-replenished-ech
                                     (fn [m] (merge-with + (or m {}) buyer-replenished))))
        {:keys [market effects]} (apply-matured-refund-stats mkt-with-pending matured)
        ;; 7. Apply delivered batch stats + optional commercial subnet_fee signed transfers
        final-acc
        (reduce
         (fn [acc {:keys [escrow-id buyer-id provider-pubkey provider-id subnet-id gross orders offer-ids]}]
           (let [fab-cur (:fabric acc)
                 treas-pk (get-in (:market acc) [:subnet-treasuries subnet-id])
                 prov-priv (when (and (pos? subnet-fee-bps) treas-pk (not= treas-pk provider-pubkey))
                             (find-node-privkey fab-cur provider-pubkey))
                 ;; Exact 50 bps protocol burn executed by state-machine on :release-intent
                 proto-burn (if (pos? gross) (max 1 (quot (* gross 50) 10000)) 0)
                 est-net (max 0 (- gross proto-burn))
                 raw-sub-fee (if (and prov-priv (pos? subnet-fee-bps))
                               (min est-net (quot (* gross subnet-fee-bps) 10000))
                               0)
                 fee-res (if (pos? raw-sub-fee)
                           (apply-signed-transfer fab-cur
                                                  {:sender-pubkey provider-pubkey
                                                   :sender-privkey prov-priv
                                                   :receiver-pubkey treas-pk
                                                   :amount raw-sub-fee
                                                   :epoch ep
                                                   :receipt-id (str "rcpt-subfee-" escrow-id)})
                           {:ok? true :fabric fab-cur :amount 0 :effects []})
                 sub-fee-paid (long (:amount fee-res))
                 mkt-orders (reduce (fn [m ord]
                                      (let [og (long (:gross-ech ord))]
                                        (-> m
                                            (update-in [:per-provider-gmv-ech (:offer-id ord)] (fnil + 0) og)
                                            (update-in [:per-capability-gmv-ech (:capability-type ord)] (fnil + 0) og))))
                                    (:market acc)
                                    orders)
                 mkt' (reduce (fn [m oid]
                                (if (contains? (:offers m) oid)
                                  (update-in m [:offers oid] update-attested-sla true)
                                  m))
                              (-> mkt-orders
                                  (update :total-cleared-gmv-ech (fnil + 0) gross)
                                  (update :total-cleared-orders (fnil + 0) (count orders))
                                  (update :total-subnet-fees-ech (fnil + 0) sub-fee-paid)
                                  (update-in [:per-subnet-gmv-ech subnet-id] (fnil + 0) gross)
                                  (update-in [:per-subnet-cleared-orders subnet-id] (fnil + 0) (count orders))
                                  (update-in [:per-subnet-released subnet-id] (fnil inc 0))
                                  (cond-> (some? buyer-id)
                                    (update-in [:per-buyer-spend-ech buyer-id] (fnil + 0) gross))
                                  (cond-> (some? provider-id)
                                    (-> (update-in [:per-provider-earned-revenue-ech provider-id] (fnil + 0) gross)
                                        (update-in [:per-provider-subnet-fees-ech provider-id] (fnil + 0) sub-fee-paid)
                                        (update-in [:per-provider-protocol-burns-ech provider-id] (fnil + 0) proto-burn))))
                              offer-ids)]
             (-> acc
                 (assoc :fabric (:fabric fee-res) :market mkt')
                 (update :settled-batches inc)
                 (update :cleared-gmv-ech + gross)
                 (update :subnet-fees-ech + sub-fee-paid)
                 (update :effects into (:effects fee-res)))))
         {:fabric fab-settled
          :market market
          :settled-batches 0
          :timeout-refunds (count matured)
          :pending-timeouts (count new-pending)
          :cleared-gmv-ech 0
          :subnet-fees-ech 0
          :effects (vec (concat rep-effects (:effects block-res) effects))}
         delivered-batches)]
    final-acc))

;; =============================================================================
;; 7. Auditable LARG Builder Cashflow Identity (V3 Item 6)
;; =============================================================================

(defn compute-builder-cashflow
  "Computes the exact auditable cashflow identity for each builder in `builder-ids`:
     ending-ledger-ech == initial-ech + earned-ech + replenished-ech
                        - buyer-spend-ech - subnet-fees-paid-ech
                        - protocol-burns-paid-ech - pending-escrow-locked-ech
   Returns a sorted-map of `builder-id -> cashflow-map` with `:identity-delta-ech`."
  [fabric market builder-ids initial-balances]
  (into (sorted-map)
        (map (fn [bid]
               (let [pk (get-in fabric [:light-nodes bid :pubkey-hex])
                     initial (long (get initial-balances bid fabric/larg-initial-stake-ech))
                     earned (long (get-in market [:per-provider-earned-revenue-ech bid] 0))
                     replenished (long (get-in market [:per-buyer-faucet-replenished-ech bid] 0))
                     buyer-spend (long (get-in market [:per-buyer-spend-ech bid] 0))
                     sub-fees (long (get-in market [:per-provider-subnet-fees-ech bid] 0))
                     proto-burns (long (get-in market [:per-provider-protocol-burns-ech bid] 0))
                     pending-locked (reduce + 0 (map #(long (:amount %))
                                                     (filter #(= pk (:buyer-pubkey %))
                                                             (:pending-refunds market))))
                     ending (long (get-in fabric [:state :ledger pk :balance] 0))
                     expected (+ initial earned replenished
                                 (- buyer-spend) (- sub-fees) (- proto-burns) (- pending-locked))]
                 [bid {:initial-ech initial
                       :earned-ech earned
                       :replenished-ech replenished
                       :buyer-spend-ech buyer-spend
                       :subnet-fees-paid-ech sub-fees
                       :protocol-burns-paid-ech proto-burns
                       :pending-escrow-locked-ech pending-locked
                       :ending-ledger-ech ending
                       :identity-delta-ech (- ending expected)}])))
        builder-ids))
