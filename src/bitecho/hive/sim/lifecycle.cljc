(ns bitecho.hive.sim.lifecycle
  "HIVE-SPEC-001 Workstream B: Pure Sans-IO Dual-Track 36-Month Full-Network Lifecycle & CRN Runner.

   Runs Protocol Subnets (`bitecho.org/infra`, `devnet.bitecho.org`) and Commercial Subnets
   (`root/markets/larg-tac`, `midi-sim`, `clear-games`, `trustx-verify`, plus dynamic organic
   entrants) simultaneously over 36 compressed months with:
   - Proper time-compression scaling (`scale-full-lifecycle-config`, fixing P2/S4: scales all
     cliffs/vests/TTLs, uses exact compound drawdown `1 - (1 - lambda)^C`, enforces
     `validator-activation-lead-epochs >= 3`)
   - Real demand-gated conditional mint (`state-machine/apply-epoch-treasury-boundary`) and
     Year-3 Bootstrap Sunset Burn (`vault/apply-bootstrap-sunset`)
   - Real SLA-gated Genesis Partner vesting via `fabric/canonical-sla-vesting-puzzle-source` +
     `vault/spend-vault` (isolated per-partner sub-vaults, INV-5)
   - Endogenous Capability Market (`bitecho.hive.sim.market`) with closed-loop 42B buyer
     liquidity and real one-shot HTLC escrow settlement
   - `:shared-encrypted` G4 Angels circle onboarding (M4-9) & seed close (M10-18)
   - Clean commercial circle dissolution (`fabric/dissolve-circle-safe`, INV-8)
   - Isolated canonical string-only `crypto/fork-random` CRN streams (INV-3).

   Strictly adheres to AGENTS.md Rules 2 & 5 (Pure Sans-IO `.cljc`, zero silent failures)."
  (:require [bitecho.basalt.core :as basalt]
            [bitecho.circles.mc-harness :as mc-harness]
            [bitecho.circles.vault :as vault]
            [bitecho.core.circle :as circle]
            [bitecho.core.subnet :as subnet]
            [bitecho.crypto :as crypto]
            [bitecho.economy.account :as account]
            [bitecho.economy.puzzle :as puzzle]
            [bitecho.fressian :as fressian]
            [bitecho.hive.rolling-upgrade-sim :as rolling-upgrade]
            [bitecho.hive.sim.fabric :as fabric]
            [bitecho.hive.sim.market :as market]
            [bitecho.light-node.core :as light-node]
            [bitecho.state-machine :as state-machine]
            [clojure.string :as str]))

;; =============================================================================
;; 1. Normative Constants & Scenarios
;; =============================================================================

(def all-scenarios
  "Canonical 5 HIVE-SPEC-001 comparative Monte Carlo scenarios."
  [:baseline-g4-angel-heavy
   :high-velocity-commercial
   :subnet-halt-fault-isolation
   :sybil-provider-attack
   :bear-post-sunset-cliff])

(def ^:private partner-subnet-map
  {:partner-larg-tac      "root/markets/larg-tac"
   :partner-midi-sim      "root/markets/midi-sim"
   :partner-clear-games   "root/markets/clear-games"
   :partner-trustx-verify "root/markets/trustx-verify"
   :partner-grounded-lang "bitecho.org/infra"
   :partner-ml-theory     "bitecho.org/infra"
   :partner-neuroevo      "bitecho.org/infra"})

(def ^:private base-commercial-subnets
  ["root/markets/larg-tac"
   "root/markets/midi-sim"
   "root/markets/clear-games"
   "root/markets/trustx-verify"])

;; =============================================================================
;; 2. Proper Time-Compression Scaling (Fixes P2 / S4)
;; =============================================================================

(defn scale-full-lifecycle-config
  "Consistently scales all 36-month lifecycle epoch constants by `compression-factor` C >= 1:
   - Scales cliffs, vesting windows, sunset epoch, treasury trailing window, escrow TTL,
     recovery timelock, descriptor TTL, and operational cadences
   - Uses exact compound bootstrap drawdown `1 - (1 - lambda)^C` (fixing linear error in S4)
   - Enforces `validator-activation-lead-epochs >= 3` for Streamlet 3-chain finality (fixing P2)."
  [theta compression-factor]
  (let [c (max 1 (long (or compression-factor 1)))
        base (merge {:bootstrap-sunset-epoch 26280
                     :core-team-cliff-epochs 8760
                     :core-team-vest-epochs 35040
                     :partner-cliff-epochs 8760
                     :partner-vest-epochs 26280
                     :treasury-trailing-epochs 168
                     :escrow-ttl-epochs 168
                     :recovery-timelock-epochs 168
                     :descriptor-ttl-epochs 26280
                     :validator-activation-lead-epochs 6
                     :bootstrap-drawdown-lambda 0.0001
                     :settlement-take-bps 50
                     :vest-claim-interval-epochs 720
                     :upgrade-interval-epochs 2160
                     :rotation-interval-epochs 4320
                     :g4-onboard-start-epoch 2920
                     :g4-onboard-end-epoch 6570
                     :g4-seed-close-epoch 10950}
                    theta)
        scale (fn [v] (mc-harness/scale-epoch-constant v c))
        lam (double (or (:bootstrap-drawdown-lambda base) 0.0001))
        compound-lam (- 1.0 (Math/pow (- 1.0 lam) (double c)))]
    (-> base
        (assoc :time-compression-factor c)
        (update :bootstrap-sunset-epoch scale)
        (update :core-team-cliff-epochs scale)
        (update :core-team-vest-epochs scale)
        (update :partner-cliff-epochs scale)
        (update :partner-vest-epochs scale)
        (update :treasury-trailing-epochs scale)
        (update :escrow-ttl-epochs scale)
        (update :recovery-timelock-epochs scale)
        (update :descriptor-ttl-epochs scale)
        (update :vest-claim-interval-epochs scale)
        (update :upgrade-interval-epochs scale)
        (update :rotation-interval-epochs scale)
        (update :g4-onboard-start-epoch scale)
        (update :g4-onboard-end-epoch scale)
        (update :g4-seed-close-epoch scale)
        (update :validator-activation-lead-epochs #(max 3 (scale %)))
        (assoc :bootstrap-drawdown-lambda compound-lam))))

;; =============================================================================
;; 3. Commercial Subnet Chartering & Clean Dissolution (INV-8)
;; =============================================================================

(defn charter-commercial-subnet
  "Atomically charters a commercial subnet via real `subnet/init-subnet-instance` (with
   `:subnet/parent-id \"bitecho.org/infra\"`) + verified `circle/create-entity-descriptor`
   (with dedicated `:vault-id`), populates it with a real Ed25519 Light Node provider +
   dedicated subnet treasury + capability offers."
  [fabric market {:keys [subnet-id epoch capability-types provider-node-id unit-mult]}]
  (let [master-rng (:master-rng fabric)
        net-uri (:network-uri fabric)
        gen-cid (:network-genesis-cid fabric)
        u-mult (max 1 (long (or unit-mult 1)))
        simon (get-in fabric [:light-nodes :founder-simon])
        axiom (get-in fabric [:light-nodes :agent-axiom])
        nexus (get-in fabric [:light-nodes :agent-nexus])
        founding-signers [{:pubkey (:pubkey-hex simon) :privkey (get-in simon [:keypair :private])}
                          {:pubkey (:pubkey-hex axiom) :privkey (get-in axiom [:keypair :private])}
                          {:pubkey (:pubkey-hex nexus) :privkey (get-in nexus [:keypair :private])}]
        founding-pks (mapv :pubkey founding-signers)
        ;; Derive a dedicated real Ed25519 Light Node for this commercial subnet if requested
        p-id (or provider-node-id (keyword (str "provider-" (str/replace subnet-id #"[^a-zA-Z0-9]+" "-"))))
        [fab1 prov-node]
        (if-let [existing (get-in fabric [:light-nodes p-id])]
          [fabric existing]
          (let [kp-info (fabric/derive-keypair master-rng (str "commercial/" subnet-id "/node/" (name p-id) "/keygen"))
                ls (light-node/init-light-state {:pubkey-hex (:pubkey-hex kp-info)
                                                 :fingerprint (:pubkey-cid kp-info)}
                                                {:epoch (long (or epoch 0)) :autonomous? true})
                node {:node-id p-id
                      :tier :light
                      :pubkey-hex (:pubkey-hex kp-info)
                      :pubkey-cid (:pubkey-cid kp-info)
                      :sovereign-uri (str net-uri "/id/" (:pubkey-cid kp-info))
                      :keypair (:keypair kp-info)
                      :light-state ls
                      :subscribed-subnets #{subnet-id}
                      :archetype {:archetype-id p-id :role "Organic Commercial Subnet Provider"
                                  :affiliation "Organic Entrant" :external-archetype? true
                                  :opt-in? false :endorsed? false :synthetic-archetype? true}}]
            [(-> fabric
                 (assoc-in [:light-nodes p-id] node)
                 (cond-> (not (contains? (get-in fabric [:state :ledger]) (:pubkey-hex kp-info)))
                   (assoc-in [:state :ledger (:pubkey-hex kp-info)]
                             (account/map->AccountState {:balance 0 :seq 0 :deps []}))))
             node]))
        ;; Build & verify entity descriptor with its own dedicated :vault-id (never sharing ecosystem-faucet)
        fab2 (if (contains? (get-in fab1 [:state :entity-descriptors]) subnet-id)
               fab1
               (let [desc (circle/create-entity-descriptor
                           {:network-uri net-uri
                            :network-genesis-cid gen-cid
                            :entity-kind :circle
                            :visibility :public
                            :path subnet-id
                            :parent-path "root"
                            :owner-multisig {:threshold 2 :pubkeys founding-pks}
                            :purpose-cid (fabric/deterministic-cid (str "purpose|" subnet-id))
                            :consent-round-cid (fabric/deterministic-cid (str "consent|" subnet-id "|" epoch))
                            :vault-id (str "vault:" subnet-id)
                            :custody-bucket :ecosystem
                            :allocation-ech 0
                            :retention-policy fabric/default-retention-policy
                            :storage-accounting {:bytes-at-rest 512
                                                 :cache-incentive :free-self-hosted
                                                 :payer-pubkey (:pubkey-hex simon)
                                                 :fee-ech 0}
                            :issued-epoch (long (or epoch 0))
                            :expires-epoch (+ (long (or epoch 0)) 26280)}
                           founding-signers)
                     v-res (circle/verify-entity-descriptor
                            desc
                            {:network-uri net-uri
                             :network-genesis-cid gen-cid
                             :advisory-twin-pubkeys (get-in fab1 [:state :advisory-twin-pubkeys])})]
                 (if (:ok? v-res)
                   (assoc-in fab1 [:state :entity-descriptors subnet-id] (assoc desc :cid (:cid v-res)))
                   (update-in fab1 [:diagnostics :commercial-subnet-descriptor-rejected] (fnil inc 0)))))
        ;; Instantiate real bitecho.core.subnet instance anchored to bitecho.org/infra
        anchors (set (conj (map :pubkey-hex (vals (:infra-nodes fab2))) (:pubkey-hex prov-node)))
        base-pol {:subnet/id subnet-id
                  :subnet/parent-id "bitecho.org/infra"
                  :subnet/gate {:gate/mode :permissioned
                                :gate/admission :allowlist
                                :gate/trust-anchors anchors}
                  :subnet/economy {:economy/mode :full-settlement
                                   :economy/settlement-take-bps 50}
                  :subnet/replication {:replication/pointers :streamlet-bft
                                       :replication/blobs :eager-push}}
        pol (assoc base-pol :subnet/genesis-policy-cid (subnet/policy-cid base-pol))
        sub-res (subnet/init-subnet-instance pol {:node-id (:pubkey-hex prov-node)
                                                  :all-nodes anchors})
        sub-inst (:instance sub-res)
        fab3 (cond-> (assoc-in fab2 [:commercial-subnets subnet-id]
                               {:subnet-id subnet-id
                                :provider-id p-id
                                :provider-pubkey (:pubkey-hex prov-node)
                                :subnet-instance sub-inst
                                :chartered-epoch (long (or epoch 0))
                                :status :active})
               (not (:ok? sub-res))
               (update-in [:diagnostics :commercial-subnet-init-failed] (fnil inc 0)))
        ;; Ensure this commercial subnet has a dedicated treasury pubkey (distinct from provider)
        mkt-treas (if (contains? (:subnet-treasuries market) subnet-id)
                    market
                    (let [t-kp (fabric/derive-keypair master-rng (str "commercial/" subnet-id "/treasury/keygen"))]
                      (assoc-in market [:subnet-treasuries subnet-id] (:pubkey-hex t-kp))))
        ;; Register capability offers on the market
        mkt' (reduce (fn [m ctype]
                       (let [cat (get market/capability-catalog ctype {:ref-price-ech 150 :marginal-cost-ech 60})]
                         (market/register-offer m
                                                {:offer-id (str "offer|" subnet-id "|" (name ctype) "|" (name p-id))
                                                 :capability-type ctype
                                                 :unit-price-ech (:ref-price-ech cat)
                                                 :marginal-cost-ech (:marginal-cost-ech cat)
                                                 :capacity-units-per-epoch (* u-mult 8)
                                                 :provider-id p-id
                                                 :provider-pubkey (:pubkey-hex prov-node)
                                                 :subnet-id subnet-id
                                                 :attested-sla-bps 9500
                                                 :true-delivery-prob 0.99})))
                     mkt-treas
                     (or capability-types [:llm-inference :tac-auction-agent]))]
    {:ok? (true? (:ok? sub-res))
     :fabric fab3
     :market mkt'
     :subnet-id subnet-id
     :provider-node prov-node}))

(defn dissolve-commercial-circle
  "Cleanly dissolves a commercial circle/subcircle via `fabric/dissolve-circle-safe`:
   - Guards EVERY path in `seat-lease-paths` with `circle/can-org-revoke-entity?` (rejecting
     revocation of sovereign `id/<pk-cid>` or unauthorized targets with counted `:diagnose`!)
   - Returns unspent circle/child vault principal to `parent-vault-id` at 0-take preserving `:custody-bucket`
   - Marks valid `:seat-lease` descriptors in `seat-lease-paths` as `:revoked`
   - Leaves LARG builders' earned sovereign market revenue in `[:ledger pk]` 100% untouched (INV-8)."
  [fabric {:keys [circle-path seat-lease-paths child-vault-id parent-vault-id]}]
  (let [st (:state fabric)
        org-desc (get-in st [:entity-descriptors circle-path])
        ;; Pre-check every requested revocation against circle/can-org-revoke-entity? (INV-C4)
        rev-check (some (fn [p]
                          (let [target-desc (get-in st [:entity-descriptors p])]
                            (when target-desc
                              (let [r (circle/can-org-revoke-entity? org-desc target-desc)]
                                (when-not (:ok? r)
                                  {:path p :result r})))))
                        (or seat-lease-paths []))]
    (if rev-check
      (let [err (or (get-in rev-check [:result :error]) :unauthorized-org-revocation)
            diag {:effect :diagnose
                  :reason err
                  :circle-path circle-path
                  :target-path (:path rev-check)}]
        {:ok? false
         :error err
         :fabric (update-in fabric [:diagnostics err] (fnil inc 0))
         :effects [diag]})
      (let [simon-pk (get-in fabric [:light-nodes :founder-simon :pubkey-hex])
            axiom-pk (get-in fabric [:light-nodes :agent-axiom :pubkey-hex])
            ;; Ensure the dissolving descriptor points to child-vault-id if explicitly provided
            st0 (if (and child-vault-id (get-in st [:entity-descriptors circle-path]))
                  (assoc-in st [:entity-descriptors circle-path :vault-id] child-vault-id)
                  st)
            round {:lead-link-pubkey simon-pk
                   :quorum-size 2
                   :ballots {simon-pk :consent
                             axiom-pk :consent}}
            ctx {:advisory-twin-pubkeys (get-in fabric [:state :advisory-twin-pubkeys])}
            res (fabric/dissolve-circle-safe st0
                                             {:circle-path circle-path
                                              :parent-vault-id parent-vault-id
                                              :consent-round round}
                                             ctx)]
        (if-not (:ok? res)
          {:ok? false
           :error (:error res)
           :fabric (assoc fabric :state (:state res))
           :effects (:effects res)}
          (let [st-revoked (reduce (fn [s p]
                                     (if (contains? (:entity-descriptors s) p)
                                       (assoc-in s [:entity-descriptors p :status] :revoked)
                                       s))
                                   (:state res)
                                   (or seat-lease-paths []))]
            {:ok? true
             :fabric (assoc fabric :state st-revoked)
             :effects (:effects res)}))))))

;; =============================================================================
;; 4. Fabric & Market Initialization for Dual-Track Lifecycle
;; =============================================================================

(defn- configure-fabric-for-lifecycle
  "Configures a freshly initialized `fabric` with scaled epoch constants, treasury boundary
   parameters, active Ecosystem Faucet DAC, and scaled partner SLA-vesting puzzles."
  [fab0 scaled-cfg]
  (let [st0 (:state fab0)
        oracle-node (get-in fab0 [:infra-nodes :infra-ns1])
        oracle-pk (:pubkey-hex oracle-node)
        oracle-priv (get-in oracle-node [:keypair :private])
        simon-pk (get-in fab0 [:light-nodes :founder-simon :pubkey-hex])
        validator-pks (mapv :pubkey-hex (vals (:infra-nodes fab0)))
        home-pks (mapv :pubkey-hex (vals (:home-nodes fab0)))
        sunset-ep (:bootstrap-sunset-epoch scaled-cfg)
        trailing-ep (:treasury-trailing-epochs scaled-cfg)
        lead-ep (:validator-activation-lead-epochs scaled-cfg)
        p-cliff (:partner-cliff-epochs scaled-cfg)
        p-vest (:partner-vest-epochs scaled-cfg)
        ;; 1. Issue Ecosystem Faucet DAC for closed-loop buyer liquidity
        dac-res (vault/issue-vault-spend-dac
                 (-> st0
                     (assoc :bootstrap-sunset-epoch sunset-ep
                            :treasury-pubkey oracle-pk
                            :treasury-privkey oracle-priv
                            :validators validator-pks
                            :mesh-nodes home-pks)
                     (assoc-in [:config :treasury-trailing-epochs] trailing-ep)
                     (assoc-in [:config :bootstrap-sunset-epoch] sunset-ep)
                     (assoc-in [:config :validator-activation-lead-epochs] lead-ep)
                     (assoc-in [:config :treasury-pubkey] oracle-pk)
                     (assoc-in [:config :treasury-privkey] oracle-priv)
                     (assoc-in [:config :validators] validator-pks)
                     (assoc-in [:config :mesh-nodes] home-pks))
                 {:dac-id "faucet-dac"
                  :vault-id "vault:root/ecosystem-faucet"
                  :holder-pubkey simon-pk
                  :max-per-epoch-bps 500
                  :issued-epoch 0
                  :expires-epoch (+ sunset-ep 20000)})
        st1 (:state dac-res)
        ;; 2. Rebuild each of the 7 Genesis Partner SLA sub-vault puzzles with scaled cliff & vest
        [st2 puzzles2]
        (reduce
         (fn [[s pmap] [pid _subnet]]
           (let [vid (str "vault:root/partners/" (name pid))
                 old-p (get pmap vid)
                 new-params (assoc (dissoc old-p :puzzle-source :puzzle-hash :kind)
                                   :cliff-epoch p-cliff
                                   :vest-duration-epochs p-vest)
                 new-src (fabric/canonical-sla-vesting-puzzle-source new-params)
                 new-ph (puzzle/puzzle-cid new-src)]
             [(assoc-in s [:vaults vid :puzzle-hash] new-ph)
              (assoc pmap vid (assoc new-params
                                     :puzzle-source new-src
                                     :puzzle-hash new-ph
                                     :kind :sla-vesting))]))
         [st1 (:vault-puzzles fab0)]
         partner-subnet-map)]
    (assoc fab0 :state st2 :vault-puzzles puzzles2)))

(defn- demand-unit-multiplier
  "Scales order batch size and offer capacity cleanly with time-compression factor `C`
   so compressed epochs represent realistic multi-hour GMV and protocol burns."
  [scaled-cfg]
  (max 1 (quot (long (or (:time-compression-factor scaled-cfg) 1)) 6)))

(defn- init-dual-track-market
  "Registers redundant capability offers across the 4 base commercial subnets + any extra
   commercial subnets so halting any one commercial subnet diverts traffic cleanly."
  [fab scaled-cfg opts]
  (let [seed (:seed fab)
        u-mult (demand-unit-multiplier scaled-cfg)
        sub-treasuries {"root/markets/larg-tac"      (get-in fab [:light-nodes :partner-larg-tac :pubkey-hex])
                        "root/markets/midi-sim"      (get-in fab [:light-nodes :partner-midi-sim :pubkey-hex])
                        "root/markets/clear-games"   (get-in fab [:light-nodes :partner-clear-games :pubkey-hex])
                        "root/markets/trustx-verify" (get-in fab [:light-nodes :partner-trustx-verify :pubkey-hex])}
        m0 (market/init-market-state {:seed seed
                                      :alpha (:utility-alpha opts)
                                      :beta (:utility-beta opts)
                                      :subnet-fee-bps (or (:subnet-fee-bps opts) 100)
                                      :subnet-treasuries sub-treasuries})
        base-offers
        [;; root/markets/larg-tac
         {:offer-id "offer-larg-tac-agent" :capability-type :tac-auction-agent :venue-tier :standard
          :unit-price-ech 150 :marginal-cost-ech 60 :capacity-units-per-epoch (* u-mult 7)
          :provider-id :builder-larg-1 :subnet-id "root/markets/larg-tac"}
         {:offer-id "offer-larg-llm" :capability-type :llm-inference :venue-tier :standard
          :unit-price-ech 190 :marginal-cost-ech 80 :capacity-units-per-epoch (* u-mult 7)
          :provider-id :builder-larg-1 :subnet-id "root/markets/larg-tac"}
         {:offer-id "offer-larg-search" :capability-type :web-search-extract :venue-tier :standard
          :unit-price-ech 110 :marginal-cost-ech 40 :capacity-units-per-epoch (* u-mult 7)
          :provider-id :builder-larg-1 :subnet-id "root/markets/larg-tac"}
         {:offer-id "offer-larg-sandbox" :capability-type :sandbox-compute :venue-tier :standard
          :unit-price-ech 140 :marginal-cost-ech 50 :capacity-units-per-epoch (* u-mult 6)
          :provider-id :builder-larg-1 :subnet-id "root/markets/larg-tac"}
         {:offer-id "offer-larg-storage-rest" :capability-type :storage-bytes-at-rest :venue-tier :standard
          :replication-factor 3 :unit-price-ech 45 :marginal-cost-ech 15 :capacity-units-per-epoch (* u-mult 8)
          :provider-id :partner-larg-tac :subnet-id "root/markets/larg-tac"}
         {:offer-id "offer-larg-storage" :capability-type :artifact-storage :venue-tier :standard
          :unit-price-ech 60 :marginal-cost-ech 20 :capacity-units-per-epoch (* u-mult 8)
          :provider-id :partner-larg-tac :subnet-id "root/markets/larg-tac"}
         ;; root/markets/midi-sim (redundant sibling commercial subnet for INV-5 diversion + economy/standard tiers)
         {:offer-id "offer-midi-llm" :capability-type :llm-inference :venue-tier :standard
          :unit-price-ech 200 :marginal-cost-ech 80 :capacity-units-per-epoch (* u-mult 8)
          :provider-id :builder-larg-2 :subnet-id "root/markets/midi-sim"}
         {:offer-id "offer-midi-llm-econ" :capability-type :llm-inference :venue-tier :economy
          :unit-price-ech 85 :marginal-cost-ech 40 :capacity-units-per-epoch (* u-mult 6)
          :attested-sla-bps 9100 :provider-id :partner-midi-sim :subnet-id "root/markets/midi-sim"}
         {:offer-id "offer-midi-search" :capability-type :web-search-extract :venue-tier :standard
          :unit-price-ech 115 :marginal-cost-ech 40 :capacity-units-per-epoch (* u-mult 7)
          :provider-id :builder-larg-2 :subnet-id "root/markets/midi-sim"}
         {:offer-id "offer-midi-sandbox" :capability-type :sandbox-compute :venue-tier :standard
          :unit-price-ech 145 :marginal-cost-ech 50 :capacity-units-per-epoch (* u-mult 6)
          :provider-id :builder-larg-2 :subnet-id "root/markets/midi-sim"}
         {:offer-id "offer-midi-bandwidth" :capability-type :bandwidth-bytes-in-motion :venue-tier :standard
          :unit-price-ech 95 :marginal-cost-ech 35 :capacity-units-per-epoch (* u-mult 8)
          :provider-id :builder-larg-2 :subnet-id "root/markets/midi-sim"}
         {:offer-id "offer-midi-tac" :capability-type :tac-auction-agent :venue-tier :standard
          :unit-price-ech 160 :marginal-cost-ech 60 :capacity-units-per-epoch (* u-mult 8)
          :provider-id :builder-larg-2 :subnet-id "root/markets/midi-sim"}
         {:offer-id "offer-midi-tts" :capability-type :tts-kokoro :venue-tier :standard
          :unit-price-ech 80 :marginal-cost-ech 30 :capacity-units-per-epoch (* u-mult 6)
          :provider-id :partner-midi-sim :subnet-id "root/markets/midi-sim"}
         {:offer-id "offer-midi-stt" :capability-type :stt-moonshine :venue-tier :standard
          :unit-price-ech 90 :marginal-cost-ech 35 :capacity-units-per-epoch (* u-mult 6)
          :provider-id :partner-midi-sim :subnet-id "root/markets/midi-sim"}
         ;; root/markets/clear-games (eval-verify, premium sandbox, bandwidth, game-eval, tac)
         {:offer-id "offer-clear-eval" :capability-type :eval-verify :venue-tier :standard
          :unit-price-ech 160 :marginal-cost-ech 60 :capacity-units-per-epoch (* u-mult 7)
          :provider-id :builder-larg-3 :subnet-id "root/markets/clear-games"}
         {:offer-id "offer-clear-sandbox-prem" :capability-type :sandbox-compute :venue-tier :premium
          :unit-price-ech 420 :marginal-cost-ech 50 :capacity-units-per-epoch (* u-mult 5)
          :attested-sla-bps 9950 :provider-id :builder-larg-3 :subnet-id "root/markets/clear-games"}
         {:offer-id "offer-clear-bandwidth" :capability-type :bandwidth-bytes-in-motion :venue-tier :standard
          :unit-price-ech 100 :marginal-cost-ech 35 :capacity-units-per-epoch (* u-mult 7)
          :provider-id :builder-larg-3 :subnet-id "root/markets/clear-games"}
         {:offer-id "offer-clear-game" :capability-type :inverse-game-eval :venue-tier :standard
          :unit-price-ech 320 :marginal-cost-ech 120 :capacity-units-per-epoch (* u-mult 6)
          :provider-id :builder-larg-3 :subnet-id "root/markets/clear-games"}
         {:offer-id "offer-clear-tac" :capability-type :tac-auction-agent :venue-tier :standard
          :unit-price-ech 165 :marginal-cost-ech 60 :capacity-units-per-epoch (* u-mult 7)
          :provider-id :builder-larg-3 :subnet-id "root/markets/clear-games"}
         ;; root/markets/trustx-verify (formal-verify, eval-verify, durable storage r=6, artifact-storage)
         {:offer-id "offer-trustx-verify" :capability-type :formal-verify :venue-tier :standard
          :unit-price-ech 400 :marginal-cost-ech 160 :capacity-units-per-epoch (* u-mult 6)
          :provider-id :partner-trustx-verify :subnet-id "root/markets/trustx-verify"}
         {:offer-id "offer-trustx-eval" :capability-type :eval-verify :venue-tier :standard
          :unit-price-ech 165 :marginal-cost-ech 60 :capacity-units-per-epoch (* u-mult 6)
          :provider-id :partner-trustx-verify :subnet-id "root/markets/trustx-verify"}
         {:offer-id "offer-trustx-storage-rest" :capability-type :storage-bytes-at-rest :venue-tier :standard
          :replication-factor 6 :unit-price-ech 90 :marginal-cost-ech 30 :capacity-units-per-epoch (* u-mult 8)
          :provider-id :builder-larg-1 :subnet-id "root/markets/trustx-verify"}
         {:offer-id "offer-trustx-storage" :capability-type :artifact-storage :venue-tier :standard
          :unit-price-ech 65 :marginal-cost-ech 20 :capacity-units-per-epoch (* u-mult 8)
          :provider-id :builder-larg-1 :subnet-id "root/markets/trustx-verify"}]
        m1 (reduce (fn [m o]
                     (let [pk (get-in fab [:light-nodes (:provider-id o) :pubkey-hex])]
                       (market/register-offer m (merge {:attested-sla-bps 9500 :true-delivery-prob 0.99}
                                                       o
                                                       {:provider-pubkey pk}))))
                   m0
                   base-offers)
        ;; Inject Sybil provider in :sybil-provider-attack scenario
        m2 (if (= :sybil-provider-attack (:scenario opts))
             (market/inject-sybil-provider
              m1
              {:offer-id "offer-sybil-verify"
               :capability-type :formal-verify
               :unit-price-ech 350
               :marginal-cost-ech 160
               :capacity-units-per-epoch (* u-mult 6)
               :provider-id :partner-neuroevo
               :provider-pubkey (get-in fab [:light-nodes :partner-neuroevo :pubkey-hex])
               :subnet-id "root/markets/trustx-verify"
               :claimed-sla-bps 9999
               :attested-sla-bps 9500
               :true-delivery-prob 0.50})
             m1)
        ;; Charter base commercial subnet instances
        fab1 (reduce (fn [f sid]
                       (:fabric (charter-commercial-subnet f m2 {:subnet-id sid :epoch 0 :capability-types [] :unit-mult u-mult})))
                     fab
                     base-commercial-subnets)
        ;; Charter any extra commercial subnets (testing cross-subnet PRNG isolation)
        [fab2 m3]
        (reduce (fn [[f m] sid]
                  (let [ch (charter-commercial-subnet f m {:subnet-id sid :epoch 0 :capability-types [:llm-inference :tac-auction-agent] :unit-mult u-mult})]
                    [(:fabric ch) (:market ch)]))
                [fab1 m2]
                (or (:extra-commercial-subnets opts) []))]
    {:fabric fab2 :market m3 :scaled-cfg scaled-cfg}))

;; =============================================================================
;; 5. Per-Epoch Protocol Infra, NetworkBus, Demand, Treasury & SLA Partner Vesting
;; =============================================================================

(defn- step-protocol-infra-epoch
  "Executes protocol subnet (`bitecho.org/infra`) epoch logic using an isolated PRNG stream
   that NEVER depends on commercial subnet count or activity (INV-3):
   - Runs real `rolling-upgrade-sim/run-trial` with `:committee-size 6` on upgrade epochs
   - Creates, verifies, and applies a real `:root/validator-set-descriptor` via `:rotate-validator-set`
     on rotation epochs."
  [fab ep scaled-cfg infra-stats]
  (let [infra-rng (crypto/fork-random (:master-rng fab) (str "protocol/bitecho.org/infra/ep/" ep))
        draw (crypto/next-int infra-rng 1000000)
        upgrade-int (max 1 (long (:upgrade-interval-epochs scaled-cfg)))
        rot-int (max 1 (long (:rotation-interval-epochs scaled-cfg)))
        do-upgrade? (zero? (mod ep upgrade-int))
        do-rotation? (zero? (mod ep rot-int))
        ;; Reuse rolling-upgrade-sim full-quorum-guard logic with real :committee-size 6
        up-res (when do-upgrade?
                 (rolling-upgrade/run-trial
                  {:policy :post-rotation-full-quorum-guard
                   :committee-size 6
                   :seed (+ 100000 draw)
                   :restart-mode :realistic}))
        stalled? (boolean (and up-res (not (:zero-stall? up-res))))
        ;; On rotation epochs, execute real create-validator-set-descriptor + verify + :rotate-validator-set
        [fab-rot rot-ok?]
        (if-not do-rotation?
          [fab false]
          (let [simon (get-in fab [:light-nodes :founder-simon])
                axiom (get-in fab [:light-nodes :agent-axiom])
                nexus (get-in fab [:light-nodes :agent-nexus])
                founding-signers [{:pubkey (:pubkey-hex simon) :privkey (get-in simon [:keypair :private])}
                                  {:pubkey (:pubkey-hex axiom) :privkey (get-in axiom [:keypair :private])}
                                  {:pubkey (:pubkey-hex nexus) :privkey (get-in nexus [:keypair :private])}]
                validator-pks (mapv :pubkey-hex (vals (:infra-nodes fab)))
                lead (long (:validator-activation-lead-epochs scaled-cfg))
                rot-ver (inc (long (:rotations-executed infra-stats 0)))
                v-desc (circle/create-validator-set-descriptor
                        {:network-uri (:network-uri fab)
                         :network-genesis-cid (:network-genesis-cid fab)
                         :version rot-ver
                         :epoch-finalized ep
                         :not-before-epoch ep
                         :epoch-effective (+ ep lead)
                         :quorum-threshold 5
                         :consent-round-cid (fabric/deterministic-cid (str "val-rot|" (:network-genesis-cid fab) "|" ep))
                         :committee (mapv (fn [pk] {:pubkey pk :role :core-validator}) validator-pks)
                         :policy-refs {:theta-version "v2.1" :validator-activation-lead-epochs lead}
                         :retention-policy fabric/default-retention-policy
                         :storage-accounting {:bytes-at-rest 512
                                              :cache-incentive :free-self-hosted
                                              :payer-pubkey (:pubkey-hex simon)
                                              :fee-ech 0}}
                        founding-signers)
                v-check (circle/verify-validator-set-descriptor
                         v-desc
                         {:root-owner-multisig (get-in fab [:state :root-owner-multisig])
                          :network-uri (:network-uri fab)
                          :network-genesis-cid (:network-genesis-cid fab)
                          :advisory-twin-pubkeys (get-in fab [:state :advisory-twin-pubkeys])
                          :lead-epochs lead})]
            (if (:ok? v-check)
              (let [rot-res (fabric/apply-finalized-receipts
                             fab
                             [{:receipt-id (str "rcpt-rot-ep" ep "-v" rot-ver)
                               :type :rotate-validator-set
                               :descriptor v-desc
                               :epoch (max 0 (dec ep))}]
                             ep)]
                [(:fabric rot-res) (true? (:ok? rot-res))])
              [(update-in fab [:diagnostics :validator-rotation-rejected] (fnil inc 0)) false])))]
    {:fabric fab-rot
     :infra-stats (-> infra-stats
                      (update :infra-draws conj draw)
                      (update :upgrades-executed + (if do-upgrade? 1 0))
                      (update :upgrade-stalls + (if stalled? 1 0))
                      (update :rotations-executed + (if do-rotation? 1 0))
                      (update :rotations-verified + (if rot-ok? 1 0)))}))

(defn- step-network-bus-epoch
  "Routes a real signed Light-Node gossip envelope through `fabric/bus-send` and
   `fabric/bus-deliver-epoch` each epoch using an isolated bus PRNG stream."
  [fab ep]
  (let [sender-node (get-in fab [:light-nodes :builder-larg-1])
        bus-rng (crypto/fork-random (:master-rng fab) (str "bus/ep/" ep))
        gossip-env (light-node/build-gossip-envelope
                    (:pubkey-hex sender-node)
                    (get-in sender-node [:keypair :private])
                    (fressian/utf8->bytes (str "epoch-heartbeat:" ep))
                    10
                    "root/markets/larg-tac")
        send-res (fabric/bus-send (:bus fab) bus-rng {:from :builder-larg-1
                                                      :to :infra-ns1
                                                      :subnet-id "root/markets/larg-tac"
                                                      :envelope gossip-env
                                                      :epoch ep})
        del-res (fabric/bus-deliver-epoch (:bus send-res) ep)]
    (assoc fab :bus (:bus del-res))))

(defn- generate-epoch-demand
  "Generates deterministic V3 capability market requests for epoch `ep` using isolated
   per-epoch demand PRNG streams, weighted by Axiom's Top-5 synthesis ranking, plus
   recurring `:storage-bytes-at-rest` epoch rent orders scaled with time-compression factor `C`."
  [fab scenario ep scaled-cfg]
  (let [sunset-ep (long (:bootstrap-sunset-epoch scaled-cfg))
        post-sunset? (>= ep sunset-ep)
        u-mult (demand-unit-multiplier scaled-cfg)
        base-orders (case scenario
                      :high-velocity-commercial 24
                      :bear-post-sunset-cliff (if post-sunset? 3 8)
                      :sybil-provider-attack 10
                      9)
        buyers [:builder-larg-1 :builder-larg-2 :builder-larg-3 :angel-g4-1 :founder-simon]
        rng (crypto/fork-random (:master-rng fab) (str "demand/global/ep/" ep))
        reqs (mapv (fn [idx]
                     (let [ctype (market/sample-capability-by-demand-weight rng)
                           buyer-id (nth buyers (crypto/next-int rng (count buyers)))
                           buyer-pk (get-in fab [:light-nodes buyer-id :pubkey-hex])
                           ref-p (get-in market/capability-catalog [ctype :ref-price-ech] 150)
                           units (* u-mult (inc (crypto/next-int rng 3)))
                           wtp (+ ref-p (quot (* ref-p (+ 25 (crypto/next-int rng 75))) 100))]
                       {:request-id (str "req-ep" ep "-" idx)
                        :request-seq idx
                        :epoch ep
                        :buyer-id buyer-id
                        :buyer-pubkey buyer-pk
                        :capability-type ctype
                        :units units
                        :max-wtp-ech wtp
                        :min-sla-bps 8800
                        :budget-ech (* wtp units)}))
                   (range 1 (inc base-orders)))
        ;; Recurring epoch rent order for :storage-bytes-at-rest (durability/replication r in {1,3,6})
        rent-buyer :founder-simon
        rent-buyer-pk (get-in fab [:light-nodes rent-buyer :pubkey-hex])
        rent-req {:request-id (str "req-rent-ep" ep)
                  :request-seq (inc base-orders)
                  :epoch ep
                  :buyer-id rent-buyer
                  :buyer-pubkey rent-buyer-pk
                  :capability-type :storage-bytes-at-rest
                  :billing-model :recurring-epoch-rent
                  :units (* u-mult 2)
                  :max-wtp-ech 150
                  :min-sla-bps 8800
                  :budget-ech (* 150 u-mult 2)}
        reqs-with-rent (conj reqs rent-req)]
    (if (= :sybil-provider-attack scenario)
      (conj reqs-with-rent
            (market/make-strategic-buyer-request
             {:request-id (str "req-whale-ep" ep)
              :request-seq (+ 2 base-orders)
              :epoch ep
              :buyer-id :angel-g4-1
              :buyer-pubkey (get-in fab [:light-nodes :angel-g4-1 :pubkey-hex])
              :capability-type :formal-verify
              :units (* u-mult 2)
              :min-sla-bps 8800
              :budget-ech 1000000000}))
      reqs-with-rent)))

(defn- compute-subnet-sla-bps
  [market subnet-id halted-subnets infra-stats]
  (cond
    (contains? halted-subnets subnet-id) 0
    (= subnet-id "bitecho.org/infra")
    (let [ups (long (or (:upgrades-executed infra-stats) 0))
          stalls (long (or (:upgrade-stalls infra-stats) 0))]
      (if (pos? ups)
        (max 0 (long (Math/round (* 10000.0 (/ (double (max 0 (- ups stalls))) (double ups))))))
        10000))
    :else
    (let [rel (long (get-in market [:per-subnet-released subnet-id] 0))
          ref (long (get-in market [:per-subnet-refunded subnet-id] 0))
          tot (+ rel ref)]
      (if (pos? tot)
        (long (Math/round (* 10000.0 (/ (double rel) (double tot)))))
        9700))))

(defn- step-treasury-vault-schedules
  "Executes gradual Bootstrap drawdown (`:bootstrap-drawdown-lambda`) before Year-3 sunset
   AND Core-Team linear vesting after cliff via real 2-of-5 multisig `vault/spend-vault`."
  [fab ep scaled-cfg treas-stats]
  (let [interval (max 1 (long (:vest-claim-interval-epochs scaled-cfg)))
        sunset-ep (long (:bootstrap-sunset-epoch scaled-cfg))
        core-cliff (long (:core-team-cliff-epochs scaled-cfg))
        core-vest (max 1 (long (:core-team-vest-epochs scaled-cfg)))
        signers [(get-in fab [:light-nodes :founder-simon])
                 (get-in fab [:light-nodes :agent-axiom])]]
    (if-not (zero? (mod ep interval))
      {:fabric fab :treas-stats treas-stats}
      (let [;; 1. Gradual Bootstrap drawdown prior to sunset
            [fab1 ts1]
            (if (< ep sunset-ep)
              (let [vid "vault:root/bootstrap"
                    v (get-in fab [:state :vaults vid])
                    bal (long (or (:balance v) 0))
                    lam (double (or (:bootstrap-drawdown-lambda scaled-cfg) 0.0))
                    interval-lam (- 1.0 (Math/pow (- 1.0 lam) (double interval)))
                    draw-amt (min bal (long (Math/floor (* (double bal) interval-lam))))]
                (if (pos? draw-amt)
                  (let [rec-pk (get-in fab [:home-nodes :home-austin-1 :pubkey-hex])
                        p-info (get-in fab [:vault-puzzles vid])
                        sol (fabric/build-multisig-vault-solution
                             {:fabric fab
                              :vault-id vid
                              :receiver-pubkey rec-pk
                              :release-amount draw-amt
                              :next-puzzle-hash (:puzzle-hash v)
                              :signers signers})
                        res (vault/spend-vault (:state fab)
                                               {:vault-id vid
                                                :vault-seq (:vault-seq v)
                                                :puzzle-source (:puzzle-source p-info)
                                                :solution sol
                                                :epoch ep})]
                    (if (:ok? res)
                      [(assoc fab :state (:state res))
                       (update treas-stats :bootstrap-drawn-ech (fnil + 0) draw-amt)]
                      [fab treas-stats]))
                  [fab treas-stats]))
              [fab treas-stats])]
        ;; 2. Core-Team linear vesting after cliff
        (if (>= ep core-cliff)
          (let [vid "vault:root/core-team/simon"
                v (get-in fab1 [:state :vaults vid])
                bal (long (or (:balance v) 0))
                total-grant 2520000000
                already-released (- total-grant bal)
                elapsed (min core-vest (max 0 (- ep core-cliff)))
                vested-total (quot (* total-grant elapsed) core-vest)
                interval-tranche (max 1 (quot (* total-grant interval) core-vest))
                claimable (min bal interval-tranche (max 0 (- vested-total already-released)))]
            (if (pos? claimable)
              (let [rec-pk (get-in fab1 [:light-nodes :founder-simon :pubkey-hex])
                    p-info (get-in fab1 [:vault-puzzles vid])
                    sol (fabric/build-multisig-vault-solution
                         {:fabric fab1
                          :vault-id vid
                          :receiver-pubkey rec-pk
                          :release-amount claimable
                          :next-puzzle-hash (:puzzle-hash v)
                          :signers signers})
                    res (vault/spend-vault (:state fab1)
                                           {:vault-id vid
                                            :vault-seq (:vault-seq v)
                                            :puzzle-source (:puzzle-source p-info)
                                            :solution sol
                                            :epoch ep})]
                (if (:ok? res)
                  {:fabric (assoc fab1 :state (:state res))
                   :treas-stats (update ts1 :core-team-vested-ech (fnil + 0) claimable)}
                  {:fabric fab1 :treas-stats ts1}))
              {:fabric fab1 :treas-stats ts1}))
          {:fabric fab1 :treas-stats ts1})))))

(defn- step-partner-sla-vesting
  "Executes real SCI `vault/spend-vault` SLA-gated vesting for each of the 7 Genesis Partners
   on vesting claim epochs. Caps each claim epoch to a single interval tranche so paused epochs
   defer/forgo that epoch's tranche with NO instant lump-sum catch-up on recovery.
   When a partner's sponsored commercial subnet is halted or below `min-sla-bps`, the real SCI
   puzzle rejects the spend and emits a counted `:diagnose` `:partner-vesting-sla-paused` (INV-5 / INV-7)."
  [fab market ep scaled-cfg halted-subnets infra-stats partner-stats]
  (let [cliff (long (:partner-cliff-epochs scaled-cfg))
        vest-dur (max 1 (long (:partner-vest-epochs scaled-cfg)))
        interval (max 1 (long (:vest-claim-interval-epochs scaled-cfg)))
        claim-epoch? (and (>= ep cliff) (zero? (mod (- ep cliff) interval)))]
    (if-not claim-epoch?
      {:fabric fab :partner-stats partner-stats}
      (let [oracle-node (get-in fab [:infra-nodes :infra-ns1])
            founder-node (get-in fab [:light-nodes :founder-simon])]
        (reduce
         (fn [{:keys [fabric partner-stats]} [pid subnet-id]]
           (let [vid (str "vault:root/partners/" (name pid))
                 v (get-in fabric [:state :vaults vid])
                 p-info (get-in fabric [:vault-puzzles vid])
                 bal (long (or (:balance v) 0))
                 total-grant (long (:total-grant-ech p-info))
                 already-released (- total-grant bal)
                 elapsed (min vest-dur (max 0 (- ep cliff)))
                 vested-total (quot (* total-grant elapsed) vest-dur)
                 interval-tranche (max 1 (quot (* total-grant interval) vest-dur))
                 claimable (min bal interval-tranche (max 0 (- vested-total already-released)))]
             (if (<= claimable 0)
               {:fabric fabric :partner-stats partner-stats}
               (let [sla-bps (compute-subnet-sla-bps market subnet-id halted-subnets infra-stats)
                     p-node (get-in fabric [:light-nodes pid])
                     sol (fabric/build-sla-vesting-solution
                          {:fabric fabric
                           :vault-id vid
                           :epoch ep
                           :sla-score sla-bps
                           :release-amount claimable
                           :next-puzzle-hash (:puzzle-hash p-info)
                           :signers [founder-node p-node]
                           :oracle-keypair (:keypair oracle-node)})
                     res (vault/spend-vault (:state fabric)
                                            {:vault-id vid
                                             :vault-seq (:vault-seq v)
                                             :puzzle-source (:puzzle-source p-info)
                                             :solution sol
                                             :epoch ep})]
                 (if (:ok? res)
                   {:fabric (assoc fabric :state (:state res))
                    :partner-stats (-> partner-stats
                                       (update-in [pid :vested-ech] (fnil + 0) claimable)
                                       (update-in [pid :vested-claims] (fnil inc 0)))}
                   {:fabric (update-in fabric [:diagnostics :partner-vesting-sla-paused] (fnil inc 0))
                    :partner-stats (update-in partner-stats [pid :paused-claims] (fnil inc 0))})))))
         {:fabric fab :partner-stats partner-stats}
         partner-subnet-map)))))

;; =============================================================================
;; 6. Full-Network 36-Month+ Lifecycle Trial & Multi-Scenario CRN Sweep
;; =============================================================================

(defn run-full-network-trial
  "Runs a deterministic pure Sans-IO dual-track full-network trial across Protocol Subnets
   and Commercial Subnets with real Ed25519 keys, real SCI vault puzzles, real one-shot HTLC
   escrows, real NetworkBus routing, real validator-set rotations, demand-gated conditional
   mint, gradual bootstrap drawdown + Year-3 sunset burn, core-team & SLA-gated partner vesting,
   and 7-bucket 42B conservation verification on EVERY epoch."
  [{:keys [scenario seed time-compression-factor epochs g4-anchor-threshold
           extra-commercial-subnets return-fabric? theta]
    :or {scenario :baseline-g4-angel-heavy
         seed 20261124
         time-compression-factor 72
         epochs 450
         g4-anchor-threshold 2
         extra-commercial-subnets []
         return-fabric? false}
    :as opts}]
  (let [c (max 1 (long time-compression-factor))
        n-epochs (max 1 (long epochs))
        scaled-cfg (scale-full-lifecycle-config theta c)
        u-mult (demand-unit-multiplier scaled-cfg)
        fab0 (-> (fabric/init-fabric {:seed seed})
                 (configure-fabric-for-lifecycle scaled-cfg))
        {:keys [fabric market]} (init-dual-track-market fab0 scaled-cfg
                                                        (assoc opts :scenario scenario
                                                               :extra-commercial-subnets extra-commercial-subnets))
        halt-start (mc-harness/scale-epoch-constant 10220 c) ;; ~M14
        halt-end (mc-harness/scale-epoch-constant 17520 c)   ;; ~M24
        organic-spawn-ep (mc-harness/scale-epoch-constant 4380 c) ;; ~M6
        g4-onboard-ep (long (:g4-onboard-end-epoch scaled-cfg))
        g4-close-ep (long (:g4-seed-close-epoch scaled-cfg))
        init-partner-stats (into {} (map (fn [[pid _]] [pid {:vested-ech 0 :vested-claims 0 :paused-claims 0}])
                                         partner-subnet-map))
        init-infra-stats {:upgrades-executed 0 :upgrade-stalls 0 :rotations-executed 0 :rotations-verified 0 :infra-draws []}
        init-treas-stats {:bootstrap-drawn-ech 0 :core-team-vested-ech 0}
        init-builder-bals {:builder-larg-1 fabric/larg-initial-stake-ech
                           :builder-larg-2 fabric/larg-initial-stake-ech
                           :builder-larg-3 fabric/larg-initial-stake-ech}
        init-monthly (into (sorted-map)
                           (map (fn [m]
                                  [m {:month m
                                      :cleared-gmv-ech 0
                                      :market-burned-ech 0
                                      :per-capability-gmv-ech {}
                                      :active-commercial-subnets (count base-commercial-subnets)
                                      :active-providers 0
                                      :sla-paused-partners 0}]))
                           (range 1 37))]
    (loop [ep 1
           fab fabric
           mkt market
           partner-stats init-partner-stats
           infra-stats init-infra-stats
           treas-stats init-treas-stats
           monthly-acc init-monthly
           all-conserved? true
           g4-state {:onboarded? false :seed-closed? false :anchor-threshold (long g4-anchor-threshold)}]
      (if (> ep n-epochs)
        (let [final-cons (fabric/verify-fabric-conservation fab)
              st (:state fab)
              all-subs (concat base-commercial-subnets extra-commercial-subnets
                               (when (= :high-velocity-commercial scenario) ["root/markets/organic-agent-x"]))
              per-sub-sla (into (sorted-map)
                                (map (fn [sid] [sid (compute-subnet-sla-bps mkt sid #{} infra-stats)]))
                                (conj (vec all-subs) "bitecho.org/infra"))
              partner-summary (into (sorted-map)
                                    (map (fn [[pid ps]]
                                           [pid (assoc ps :remaining-vault-ech
                                                       (get-in st [:vaults (str "vault:root/partners/" (name pid)) :balance] 0))]))
                                    partner-stats)
              larg-bal (into (sorted-map)
                             (map (fn [lid]
                                    [lid (fabric/project-light-node-balance fab lid)]))
                             [:builder-larg-1 :builder-larg-2 :builder-larg-3])
              larg-earned (into (sorted-map)
                                (map (fn [lid]
                                       [lid (long (get-in mkt [:per-provider-earned-revenue-ech lid] 0))]))
                                [:builder-larg-1 :builder-larg-2 :builder-larg-3])
              builder-cashflow (market/compute-builder-cashflow fab mkt
                                                                [:builder-larg-1 :builder-larg-2 :builder-larg-3]
                                                                init-builder-bals)
              infra-bals (into (sorted-map)
                               (map (fn [[nid n]]
                                      [nid (long (get-in st [:ledger (:pubkey-hex n) :balance] 0))]))
                               (:infra-nodes fab))
              total-burned (long (or (:total-burned st) 0))
              market-burned (long (or (:market-burned-ech fab) 0))
              vault-burned (max 0 (- total-burned market-burned))
              surge-count (count (filter (fn [[_ o]]
                                           (let [ref-p (get-in market/capability-catalog [(:capability-type o) :ref-price-ech] 150)]
                                             (> (long (:unit-price-ech o)) ref-p)))
                                         (:offers mkt)))
              infra-digest (str/lower-case (basalt/bytes->hex (crypto/sha256 (fressian/encode-canonical (:infra-draws infra-stats)))))
              merged-diags (merge-with +
                                       (or (:diagnostics fab) {})
                                       (or (:diagnostics mkt) {})
                                       (or (get-in fab [:bus :diagnostics]) {}))
              monthly-vec (mapv (fn [m]
                                  (update (get monthly-acc m) :per-capability-gmv-ech #(into (sorted-map) %)))
                                (range 1 37))
              out {:scenario scenario
                   :seed (long seed)
                   :time-compression-factor c
                   :epochs n-epochs
                   :scaled-config scaled-cfg
                   :all-epochs-conserved? (and all-conserved? (true? (:ok? final-cons)))
                   :final-conservation final-cons
                   :failed-receipts-count (long (or (:failed-receipts-count st) 0))
                   :cleared-gmv-ech (long (:total-cleared-gmv-ech mkt 0))
                   :cleared-orders (long (:total-cleared-orders mkt 0))
                   :uncleared-orders (long (:total-uncleared-orders mkt 0))
                   :timeout-refunds (long (:total-timeout-refunds mkt 0))
                   :total-burned-ech total-burned
                   :market-burned-ech market-burned
                   :vesting-vault-burned-ech vault-burned
                   :total-minted-ech (long (or (:total-minted st) 0))
                   :bootstrap-drawn-ech (long (:bootstrap-drawn-ech treas-stats 0))
                   :core-team-vested-ech (long (:core-team-vested-ech treas-stats 0))
                   :sunset-burned-ech (long (or (:sunset-custody-burned st) 0))
                   :subnet-fees-collected-ech (long (:total-subnet-fees-ech mkt 0))
                   :surge-priced-offers surge-count
                   :endogenous-providers-entered (long (or (:endogenous-providers-entered mkt) 0))
                   :endogenous-providers-exited (long (or (:endogenous-providers-exited mkt) 0))
                   :bus-seq (long (get-in fab [:bus :seq] 0))
                   :infra-validator-balances-ech infra-bals
                   :per-subnet-sla-bps per-sub-sla
                   :per-subnet-gmv-ech (into (sorted-map) (:per-subnet-gmv-ech mkt))
                   :per-subnet-cleared-orders (into (sorted-map) (:per-subnet-cleared-orders mkt))
                   :per-provider-gmv-ech (into (sorted-map) (:per-provider-gmv-ech mkt))
                   :per-capability-gmv-ech (into (sorted-map) (:per-capability-gmv-ech mkt))
                   :partner-vesting partner-summary
                   :larg-builder-sovereign-revenue-ech larg-bal
                   :larg-builder-earned-revenue-ech larg-earned
                   :builder-cashflow builder-cashflow
                   :calibration-anchors market/capability-calibration-anchors
                   :monthly-market-telemetry monthly-vec
                   :g4-angels g4-state
                   :protocol-infra (-> infra-stats
                                       (dissoc :infra-draws)
                                       (assoc :infra-rng-digest infra-digest))
                   :diagnostics merged-diags
                   :state-root-cid (fabric/fabric-state-root-cid fab)}]
          (if return-fabric?
            (assoc out :fabric fab :market mkt)
            out))

        ;; --- Epoch Step ---
        (let [;; 1. Route real signed envelope through NetworkBus (isolated PRNG stream)
              fab-bus (step-network-bus-epoch fab ep)
              ;; 2. Dynamic organic commercial subnet spawn in :high-velocity-commercial
              [fab-dyn mkt-dyn]
              (if (and (= :high-velocity-commercial scenario) (= ep organic-spawn-ep))
                (let [ch (charter-commercial-subnet fab-bus mkt {:subnet-id "root/markets/organic-agent-x"
                                                                 :epoch ep
                                                                 :unit-mult u-mult
                                                                 :capability-types [:llm-inference :web-search-extract :sandbox-compute :tac-auction-agent :storage-bytes-at-rest]})]
                  [(:fabric ch) (:market ch)])
                [fab-bus mkt])
              ;; 3. Commercial subnet halt injection (INV-5)
              halted-subs (if (and (= :subnet-halt-fault-isolation scenario)
                                   (>= ep halt-start)
                                   (<= ep halt-end))
                            #{"root/markets/larg-tac"}
                            #{})
              ;; 4. Endogenous capability market clearing + single-block batch HTLC settlement
              mkt-burn-before (long (or (:market-burned-ech fab-dyn) 0))
              cap-gmv-before (or (:per-capability-gmv-ech mkt-dyn) {})
              reqs (generate-epoch-demand fab-dyn scenario ep scaled-cfg)
              clear-res (market/clear-market-epoch mkt-dyn reqs {:epoch ep :halted-subnets halted-subs})
              settle-res (market/settle-cleared-batches
                          fab-dyn (:market clear-res) (:cleared clear-res)
                          {:epoch ep
                           :escrow-ttl-epochs (:escrow-ttl-epochs scaled-cfg)
                           :faucet-dac-id "faucet-dac"
                           :halted-subnets halted-subs})
              fab-mkt (:fabric settle-res)
              mkt-settled (:market settle-res)
              ;; 4b. V3 Secular cost drift (-20%/yr compute-bound, +6%/yr expertise-bound)
              ;;     & Endogenous provider entry/exit (42B conserved)
              mkt-drifted (market/step-secular-cost-drift mkt-settled {:time-compression-factor c})
              ee-res (market/step-provider-entry-exit
                      fab-mkt mkt-drifted
                      {:epoch ep
                       :entry-streak-epochs 3
                       :exit-streak-epochs 5
                       :max-endogenous-per-cap 3
                       :halted-subnets halted-subs
                       :unit-mult u-mult})
              fab-ee (:fabric ee-res)
              mkt-next (:market ee-res)
              ;; 5. Protocol subnet infra step (isolated PRNG stream + real :rotate-validator-set)
              infra-res (step-protocol-infra-epoch fab-ee ep scaled-cfg infra-stats)
              fab-infra (:fabric infra-res)
              infra-next (:infra-stats infra-res)
              ;; 6. Gradual Bootstrap drawdown (pre-sunset) + Core-Team linear vesting (post-cliff)
              treas-res (step-treasury-vault-schedules fab-infra ep scaled-cfg treas-stats)
              fab-treas (:fabric treas-res)
              tstats-next (:treas-stats treas-res)
              ;; 7. Real SLA-gated Genesis Partner vesting (isolated per-partner sub-vaults, no lump-sum catch-up)
              paused-before (long (get-in fab-treas [:diagnostics :partner-vesting-sla-paused] 0))
              vest-res (step-partner-sla-vesting fab-treas mkt-next ep scaled-cfg halted-subs infra-next partner-stats)
              fab-vest (:fabric vest-res)
              pstats-next (:partner-stats vest-res)
              paused-delta (- (long (get-in fab-vest [:diagnostics :partner-vesting-sla-paused] 0)) paused-before)
              ;; 8. Demand-gated conditional mint (idempotent if already ran on epoch ep's finalized block)
              ;;    + Year-3 Bootstrap Sunset Burn
              st-treas (state-machine/apply-epoch-treasury-boundary (:state fab-vest) ep)
              sunset-res (vault/apply-bootstrap-sunset st-treas ep)
              fab-next (assoc fab-vest :state (:state sunset-res))
              ;; 9. G4 Angels :shared-encrypted circle milestone tracking (M4-9 onboard, M10-18 close)
              g4-next (cond-> g4-state
                        (>= ep g4-onboard-ep) (assoc :onboarded? true)
                        (>= ep g4-close-ep) (assoc :seed-closed? true))
              ;; 9b. Accumulate monthly endogenous market telemetry bucket (M1..M36)
              m-idx (min 36 (max 1 (inc (quot (* (dec ep) 36) n-epochs))))
              ep-gmv (long (:cleared-gmv-ech settle-res 0))
              ep-burn (max 0 (- (long (or (:market-burned-ech fab-mkt) 0)) mkt-burn-before))
              cap-gmv-after (or (:per-capability-gmv-ech mkt-settled) {})
              ep-cap-deltas (reduce-kv (fn [acc k v]
                                         (let [d (- (long v) (long (get cap-gmv-before k 0)))]
                                           (if (pos? d) (assoc acc k d) acc)))
                                       {}
                                       cap-gmv-after)
              active-subs (count (remove halted-subs (keys (:commercial-subnets fab-next))))
              active-provs (count (distinct (map :provider-pubkey
                                                 (filter #(= :active (:status %)) (vals (:offers mkt-next))))))
              monthly-next (update monthly-acc m-idx
                                   (fn [mb]
                                     (-> mb
                                         (update :cleared-gmv-ech + ep-gmv)
                                         (update :market-burned-ech + ep-burn)
                                         (update :per-capability-gmv-ech #(merge-with + % ep-cap-deltas))
                                         (assoc :active-commercial-subnets active-subs
                                                :active-providers active-provs)
                                         (update :sla-paused-partners + paused-delta))))
              ;; 10. Verify exact 7-bucket 42B ECHO conservation on EVERY epoch (INV-2)
              ep-cons-ok? (true? (:ok? (fabric/verify-fabric-conservation fab-next)))]
          (recur (inc ep)
                 fab-next
                 mkt-next
                 pstats-next
                 infra-next
                 tstats-next
                 monthly-next
                 (and all-conserved? ep-cons-ok?)
                 g4-next))))))

(defn run-scenario-sweep
  "Runs a deterministic CRN multi-scenario sweep across `scenarios` and `seeds`."
  [{:keys [scenarios seeds time-compression-factor epochs g4-anchor-threshold]
    :or {scenarios all-scenarios
         seeds [20261124]
         time-compression-factor 72
         epochs 450
         g4-anchor-threshold 2}}]
  (let [results (vec (for [scen scenarios
                           s seeds]
                       (run-full-network-trial
                        {:scenario scen
                         :seed s
                         :time-compression-factor time-compression-factor
                         :epochs epochs
                         :g4-anchor-threshold g4-anchor-threshold})))]
    {:scenarios (vec scenarios)
     :seeds (vec seeds)
     :time-compression-factor (long time-compression-factor)
     :epochs (long epochs)
     :all-conserved? (every? :all-epochs-conserved? results)
     :results results}))
