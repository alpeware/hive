(ns bitecho.hive.sim.fabric
  "HIVE-SPEC-001 Workstream A: Pure Sans-IO Zero-Scaffolding In-Memory Network Fabric.

   Wires real `alpeware/bitecho` (`f1eef45c`) pure Sans-IO `.cljc` primitives across all three
   node tiers (Infra Validator Nodes, Home Nodes, Light Nodes), exact 7-bucket 42B ECHO
   conservation (`vault/verify-7-bucket-conservation`), canonical SLA-gated vesting SCI puzzles
   (`puzzle/eval-puzzle` in `:vault-mode? true`), deterministic 3-arity `:shared-encrypted`
   descriptors, grow-only `:advisory-twin-pubkeys` isolation (`INV-TW1`/`INV-C5`), and a pure
   in-memory `NetworkBus` with deterministic fault injection.

   Strictly adheres to AGENTS.md Rules 2 & 5 (Pure Sans-IO `.cljc`, zero silent failures)."
  (:require [bitecho.basalt.core :as basalt]
            [bitecho.circles.vault :as vault]
            [bitecho.config :as config]
            [bitecho.core.circle :as circle]
            [bitecho.core.data :as data]
            [bitecho.core.subnet :as subnet]
            [bitecho.crypto :as crypto]
            [bitecho.economy.account :as account]
            [bitecho.economy.puzzle :as puzzle]
            [bitecho.fressian :as fressian]
            [bitecho.light-node.core :as light-node]
            [bitecho.state-machine :as state-machine]
            [clojure.string :as str]))

;; =============================================================================
;; 1. Normative Constants & Default §2.2 Allocation Table (42,000,000,000 ECHO)
;; =============================================================================

(def total-supply-cap-ech
  "Ratified Tokenomics v2.1 absolute 42B ECHO hard cap."
  42000000000)

(def genesis-custody-supply-ech
  "45% Genesis Custody Supply (18,900,000,000 ECHO)."
  18900000000)

(def unminted-conditional-supply-ech
  "55% Unminted Conditional Supply (23,100,000,000 ECHO)."
  23100000000)

(def larg-initial-stake-ech
  "Twin S2: Initial liquid validator stake & working-capital tranche for each LARG builder."
  250000)

(def default-cliff-epochs
  "1-year cliff in uncompressed epochs (8,760 epochs)."
  8760)

(def default-partner-vest-epochs
  "3-year linear partner vesting window after cliff (26,280 epochs)."
  26280)

(def default-min-sla-bps
  "Default minimum subnet/validator SLA score required for partner vesting (95.00% = 9500 bps)."
  9500)

(def default-retention-policy
  "BIP-016 compliant bounded pointer retention policy."
  {:max-entries 1024
   :max-bytes 262144
   :rotate-every-entries 256
   :ttl-epochs 26280})

(def supported-receipt-types
  "Closed set of receipt `:type` keywords dispatched by `bitecho.state-machine/execute-finalized-blocks` (`f1eef45c`)."
  #{:burn :transfer :bounty-create :bounty-settle :bounty-refund
    :lock-intent :release-intent :refund-intent
    :vault-create :vault-spend :vault-spend-dac-issue :vault-spend-dac-spend :vault-spend-dac-revoke
    :register-entity-descriptor :dissolve-circle :initiate-recovery :cancel-recovery
    :register-advisory-twin :rotate-validator-set :cold-root-recovery})

(def ^:private infra-validator-ids
  [:infra-ns1 :infra-ns2 :infra-ns3 :infra-node1 :infra-node2 :infra-node3])

(def ^:private home-node-ids
  [:home-austin-1 :home-austin-2])

(def ^:private advisory-twin-ids
  [:twin-founder :twin-advisor-1 :twin-research-1 :twin-builder-1])

(def ^:private partner-archetypes
  [{:id :partner-larg-tac :allocation-ech 420000000 :circle "root/austin/advisors" :market "root/markets/larg-tac"
    :affiliation "UT CS / LARG" :role "TAC Agent-Economy Archetype"}
   {:id :partner-midi-sim :allocation-ech 420000000 :circle "root/austin/advisors" :market "root/markets/midi-sim"
    :affiliation "UT ECE / MIDI Lab" :role "Multi-Agent Representation Archetype"}
   {:id :partner-clear-games :allocation-ech 420000000 :circle "root/austin/advisors" :market "root/markets/clear-games"
    :affiliation "UT Aerospace / CLeAR Lab" :role "Game-Theoretic Interaction Archetype"}
   {:id :partner-trustx-verify :allocation-ech 210000000 :circle "root/austin/research" :market "root/markets/trustx-verify"
    :affiliation "UT Aerospace / Oden" :role "Formal Verification Archetype"}
   {:id :partner-grounded-lang :allocation-ech 210000000 :circle "root/austin/research" :market nil
    :affiliation "UT CS" :role "Grounded Multi-Agent Language Archetype"}
   {:id :partner-ml-theory :allocation-ech 210000000 :circle "root/austin/research" :market nil
    :affiliation "UT / ML Theory" :role "Multi-Agent Learning Dynamics Archetype"}
   {:id :partner-neuroevo :allocation-ech 210000000 :circle "root/austin/research" :market nil
    :affiliation "UT CS" :role "Neuroevolution Archetype"}])

(def ^:private larg-builder-archetypes
  [{:id :builder-larg-1 :allocation-ech 105000000 :initial-stake-ech larg-initial-stake-ech
    :affiliation "UT CS / LARG" :role "Autonomous Agent Builder & Subnet Operator Archetype"}
   {:id :builder-larg-2 :allocation-ech 105000000 :initial-stake-ech larg-initial-stake-ech
    :affiliation "UT CS / LARG" :role "Multi-Agent Robotics/Control Builder Archetype"}
   {:id :builder-larg-3 :allocation-ech 105000000 :initial-stake-ech larg-initial-stake-ech
    :affiliation "UT CS / LARG" :role "Agent Systems & Market Operator Archetype"}])

(def ^:private founding-actors
  [{:id :founder-simon :role "Founder / Human Anchor" :external? false}
   {:id :agent-axiom :role "Core Governance & Strategy Agent" :external? false}
   {:id :agent-nexus :role "Core Protocol & Infra Agent" :external? false}
   {:id :agent-vector :role "Core Verification & Hive Sim Agent" :external? false}
   {:id :agent-lumen :role "Core Ethics & Sociocracy Agent" :external? false}
   {:id :angel-g4-1 :role "G4 Angel Circle Archetype" :external? true}])

;; =============================================================================
;; 2. Deterministic Crypto & Keygen Helpers (Zero SecureRandom, String-Only Fork)
;; =============================================================================

(defn deterministic-cid
  "Computes a deterministic 64-char lowercase SHA-256 hex CID from `label`."
  [label]
  (str/lower-case (basalt/bytes->hex (crypto/sha256 (fressian/utf8->bytes (str label))))))

(defn derive-keypair
  "Derives a real Ed25519 keypair deterministically from immutable `master-rng` via
   canonical string-only `crypto/fork-random` path (never mutating `master-rng`)."
  [master-rng path-str]
  (let [child-rng (crypto/fork-random master-rng (str path-str))
        kp (crypto/generate-keypair child-rng)
        pk-hex (circle/normalize-spki-pubkey (:public kp))]
    {:keypair kp
     :pubkey-hex pk-hex
     :pubkey-cid (circle/pubkey-cid pk-hex)}))

(defn- derive-random-bytes
  "Deterministically generates `n` bytes from immutable `master-rng` via string-only fork path."
  [master-rng path-str n]
  (let [child-rng (crypto/fork-random master-rng (str path-str))]
    (crypto/random-bytes child-rng n)))

(defn- signer-entry
  [node]
  {:pubkey (:pubkey-hex node)
   :privkey (get-in node [:keypair :private])})

;; =============================================================================
;; 3. Canonical SLA-Gated Vesting SCI Puzzle (Evaluated in `:vault-mode? true`)
;; =============================================================================

(defn sla-attestation-tuple
  "Canonical domain-separated tuple signed by the SLA oracle to attest `sla-score`
   bound strictly to `vault-id`, `vault-seq`, `epoch`, and `sha256(spend-digest)`."
  [vault-id vault-seq epoch sla-score spend-digest-bytes]
  ["bitecho-sla-attest-v1"
   (str vault-id)
   (long vault-seq)
   (long epoch)
   (long sla-score)
   (str/lower-case (basalt/bytes->hex (crypto/sha256 spend-digest-bytes)))])

(defn canonical-sla-vesting-puzzle-source
  "Returns a canonical SCI puzzle source evaluated by `bitecho.economy.puzzle/eval-puzzle`
   in `:vault-mode? true` enforcing:
   1. `epoch >= cliff-epoch`
   2. `sla-score >= min-sla-bps` with oracle-attested `verify-sig` over `(:spend-digest env)`
   3. `>= threshold` distinct non-twin signatures from `allowed-signers` over `(:spend-digest env)`
   4. Linear vesting cap blocking lump-sum drain (`release-amount <= claimable`)
   5. Strict relock binding (`(= next-puzzle-hash (:puzzle-hash env))`) blocking puzzle rotation
   6. Exact multi-output conservation (`(+ release relock) == (:amount env)`)."
  [{:keys [beneficiary-pubkey allowed-signers threshold advisory-twin-pubkeys
           sla-oracle-pubkey cliff-epoch vest-duration-epochs min-sla-bps total-grant-ech]}]
  (let [beneficiary (circle/normalize-spki-pubkey beneficiary-pubkey)
        allowed (set (circle/dedup-spki-pubkeys allowed-signers))
        twins (set (circle/dedup-spki-pubkeys (or advisory-twin-pubkeys #{})))
        oracle-pk (circle/normalize-spki-pubkey sla-oracle-pubkey)
        k (long threshold)
        cliff (long cliff-epoch)
        vest-dur (max 1 (long vest-duration-epochs))
        min-sla (long min-sla-bps)
        total-grant (long total-grant-ech)]
    (pr-str
     (list 'fn ['env 'solution]
           (list 'let ['allowed allowed
                       'twins twins
                       'oracle-pk oracle-pk
                       'beneficiary beneficiary
                       'ep (list 'or (list :epoch 'env) 0)
                       'amt (list 'or (list :amount 'env) 0)
                       'cur-ph (list :puzzle-hash 'env)
                       'digest (list :spend-digest 'env)
                       'sla-score (list 'or (list :sla-score 'solution) 0)
                       'sla-sig (list :sla-attest-sig 'solution)
                       'rel-amt (list 'or (list :release-amount 'solution) 0)
                       'req-next-ph (list 'or (list :next-puzzle-hash 'solution) 'cur-ph)
                       'sigs (list 'or (list :signatures 'solution) {})
                       'attest-msg ["bitecho-sla-attest-v1"
                                    (list :vault-id 'env)
                                    (list :vault-seq 'env)
                                    'ep
                                    'sla-score
                                    (list 'sha256 'digest)]
                       'oracle-ok? (list 'and
                                         (list 'string? 'sla-sig)
                                         (list 'verify-sig 'oracle-pk 'attest-msg 'sla-sig))
                       'valid-signers (list 'distinct
                                            (list 'filter
                                                  (list 'fn ['pk]
                                                        (list 'and
                                                              (list 'contains? 'allowed 'pk)
                                                              (list 'not (list 'contains? 'twins 'pk))
                                                              (list 'let ['s (list 'get 'sigs 'pk)]
                                                                    (list 'and
                                                                          (list 'string? 's)
                                                                          (list 'verify-sig 'pk 'digest 's)))))
                                                  (list 'keys 'sigs)))
                       'already-released (list '- total-grant 'amt)
                       'elapsed (list 'min vest-dur (list 'max 0 (list '- 'ep cliff)))
                       'vested-total (list 'quot (list '* total-grant 'elapsed) vest-dur)
                       'claimable (list 'max 0 (list '- 'vested-total 'already-released))
                       'relock-amt (list '- 'amt 'rel-amt)
                       'expected-outputs (list 'if (list 'pos? 'relock-amt)
                                               [{:type :release :receiver 'beneficiary :amount 'rel-amt}
                                                {:type :relock :amount 'relock-amt :next-puzzle-hash 'cur-ph}]
                                               [{:type :release :receiver 'beneficiary :amount 'rel-amt}])]
                 (list 'if (list 'and
                                 (list '>= 'ep cliff)
                                 (list '>= 'sla-score min-sla)
                                 'oracle-ok?
                                 (list '>= (list 'count 'valid-signers) k)
                                 (list 'integer? 'rel-amt)
                                 (list 'pos? 'rel-amt)
                                 (list '<= 'rel-amt 'claimable)
                                 (list '<= 'rel-amt 'amt)
                                 (list '= 'req-next-ph 'cur-ph)
                                 (list '= (list :outputs 'solution) 'expected-outputs))
                       'expected-outputs
                       false))))))

(defn build-sla-vesting-solution
  "Constructs a signed solution map for `canonical-sla-vesting-puzzle-source` against
   `vault-id` in `fabric`, signing both the anti-malleability `treasury-spend-digest`
   and the oracle `sla-attestation-tuple`."
  [{:keys [fabric vault-id epoch sla-score release-amount next-puzzle-hash signers oracle-keypair]}]
  (let [st (:state fabric)
        v (get-in st [:vaults vault-id])
        p-info (get-in fabric [:vault-puzzles vault-id])
        beneficiary (:beneficiary-pubkey p-info)
        bal (long (:balance v))
        rel-amt (long release-amount)
        relock-amt (- bal rel-amt)
        next-ph (or next-puzzle-hash (:puzzle-hash v))
        outputs (if (pos? relock-amt)
                  [{:type :release :receiver beneficiary :amount rel-amt}
                   {:type :relock :amount relock-amt :next-puzzle-hash next-ph}]
                  [{:type :release :receiver beneficiary :amount rel-amt}])
        digest (vault/treasury-spend-digest
                {:network-uri (:network-uri st)
                 :network-genesis-cid (:network-genesis-cid st)
                 :vault-id vault-id
                 :puzzle-hash (:puzzle-hash v)
                 :vault-seq (:vault-seq v)
                 :outputs outputs
                 :next-puzzle-hash next-ph})
        attest-tuple (sla-attestation-tuple vault-id (:vault-seq v) epoch sla-score digest)
        attest-sig (str/lower-case (basalt/bytes->hex (crypto/sign-canonical (:private oracle-keypair) attest-tuple)))
        sigs (into (sorted-map)
                   (map (fn [node]
                          (let [pk (:pubkey-hex node)
                                priv (get-in node [:keypair :private])]
                            [pk (str/lower-case (basalt/bytes->hex (crypto/sign priv digest)))])))
                   signers)]
    {:sla-score (long sla-score)
     :sla-attest-sig attest-sig
     :release-amount rel-amt
     :next-puzzle-hash next-ph
     :outputs outputs
     :signatures sigs}))

;; =============================================================================
;; 4. Pure In-Memory NetworkBus (Deterministic Fault Injection + Zero Silent Drops)
;; =============================================================================

(defn init-network-bus
  "Initializes a pure Sans-IO in-memory `NetworkBus` for routing signed envelopes
   between Light, Home, and Infra nodes with deterministic fault injection."
  ([] (init-network-bus {}))
  ([fault-policy]
   {:epoch 0
    :seq 0
    :in-flight []
    :delivered []
    :dropped []
    :fault-policy (merge {:drop-rate 0.0
                          :min-latency-epochs 0
                          :max-latency-epochs 0
                          :partitions #{}}
                         fault-policy)
    :diagnostics {}}))

(defn set-bus-fault-policy
  "Updates the fault-injection policy on `bus`."
  [bus fault-policy]
  (update bus :fault-policy merge fault-policy))

(defn- partitioned?
  [partitions from to]
  (boolean
   (some (fn [p]
           (cond
             (set? p) (and (contains? p from) (contains? p to))
             (map? p) (and (= (:from p) from) (= (:to p) to))
             :else false))
         partitions)))

(defn bus-send
  "Routes a signed envelope over the pure `NetworkBus`.
   Every partition or fault-injected drop emits a counted `:diagnose` effect (INV-7)."
  [bus rng {:keys [from to subnet-id envelope epoch]}]
  (let [fp (:fault-policy bus)
        ep (long (or epoch (:epoch bus) 0))
        next-seq (inc (long (:seq bus 0)))
        env-id (str "env-" next-seq)
        drop-rate (double (or (:drop-rate fp) 0.0))
        min-lat (max 0 (long (or (:min-latency-epochs fp) 0)))
        max-lat (max min-lat (long (or (:max-latency-epochs fp) min-lat)))]
    (cond
      (partitioned? (:partitions fp) from to)
      (let [diag {:effect :diagnose :reason :bus-partition-dropped :from from :to to :envelope-id env-id}]
        {:ok? false
         :error :bus-partition-dropped
         :bus (-> bus
                  (assoc :seq next-seq)
                  (update :dropped conj {:envelope-id env-id :from from :to to :epoch ep :reason :bus-partition-dropped})
                  (update-in [:diagnostics :bus-partition-dropped] (fnil inc 0)))
         :effects [diag]})

      (and (pos? drop-rate) (< (crypto/next-double rng) drop-rate))
      (let [diag {:effect :diagnose :reason :bus-fault-dropped :from from :to to :envelope-id env-id}]
        {:ok? false
         :error :bus-fault-dropped
         :bus (-> bus
                  (assoc :seq next-seq)
                  (update :dropped conj {:envelope-id env-id :from from :to to :epoch ep :reason :bus-fault-dropped})
                  (update-in [:diagnostics :bus-fault-dropped] (fnil inc 0)))
         :effects [diag]})

      :else
      (let [span (inc (- max-lat min-lat))
            lat (+ min-lat (if (> span 1) (crypto/next-int rng span) 0))
            item {:envelope-id env-id
                  :from from
                  :to to
                  :subnet-id subnet-id
                  :envelope envelope
                  :sent-epoch ep
                  :deliver-epoch (+ ep lat)}]
        {:ok? true
         :envelope-id env-id
         :bus (-> bus
                  (assoc :seq next-seq)
                  (update :in-flight conj item))
         :effects []}))))

(defn bus-deliver-epoch
  "Advances `bus` to `current-epoch` and pops all in-flight envelopes with `:deliver-epoch <= current-epoch`."
  [bus current-epoch]
  (let [ep (long current-epoch)
        ready (filterv #(<= (long (:deliver-epoch %)) ep) (:in-flight bus))
        remaining (filterv #(> (long (:deliver-epoch %)) ep) (:in-flight bus))]
    {:bus (-> bus
              (assoc :epoch ep :in-flight remaining)
              (update :delivered into ready))
     :deliveries ready
     :effects []}))

;; =============================================================================
;; 5. Custody-Bucket-Safe Circle Dissolution Wrapper (P5 Guard)
;; =============================================================================

(defn dissolve-circle-safe
  "Guards `bitecho.core.circle/dissolve-circle` against P5 `:custody-bucket` laundering:
   when the dissolving circle's vault holds positive principal, fails closed with counted
   `:diagnose` `:custody-bucket-mismatch` unless child and parent vaults share the exact
   same `:custody-bucket`."
  [state {:keys [circle-path parent-vault-id] :as params} ctx]
  (let [c-desc (get-in state [:entity-descriptors circle-path])
        c-vid (:vault-id c-desc)
        c-vault (when c-vid (get-in state [:vaults c-vid]))
        p-vault (when parent-vault-id (get-in state [:vaults parent-vault-id]))]
    (if (and c-vault
             p-vault
             (pos? (long (or (:balance c-vault) 0)))
             (not= (:custody-bucket c-vault) (:custody-bucket p-vault)))
      {:ok? false
       :error :custody-bucket-mismatch
       :state (update-in state [:diagnostics :custody-bucket-mismatch] (fnil inc 0))
       :effects [{:effect :diagnose
                  :reason :custody-bucket-mismatch
                  :circle-path circle-path
                  :child-bucket (:custody-bucket c-vault)
                  :parent-bucket (:custody-bucket p-vault)}]}
      (circle/dissolve-circle state params ctx))))

;; =============================================================================
;; 6. Node Tier Builders (Infra Validator, Home, Light)
;; =============================================================================

(defn- build-protocol-subnet-policy
  [subnet-id trust-anchors]
  (let [base {:subnet/id subnet-id
              :subnet/gate {:gate/mode :permissioned
                            :gate/admission :allowlist
                            :gate/trust-anchors (set trust-anchors)}
              :subnet/economy {:economy/mode :full-settlement
                               :economy/settlement-take-bps 50}
              :subnet/replication {:replication/pointers :streamlet-bft
                                   :replication/blobs :eager-push}}]
    (assoc base :subnet/genesis-policy-cid (subnet/policy-cid base))))

(defn- init-infra-nodes
  [master-rng network-uri network-genesis-cid]
  (let [kps (into {} (map (fn [nid] [nid (derive-keypair master-rng (str "node/" (name nid) "/keygen"))])
                          infra-validator-ids))
        anchors (set (map :pubkey-hex (vals kps)))
        policy (build-protocol-subnet-policy "global" anchors)
        peers-map (into {} (map (fn [[nid info]] [(:pubkey-hex info) {:node-id nid :pubkey (:pubkey-hex info)}]) kps))]
    (into {}
          (map (fn [[nid info]]
                 (let [sub-res (subnet/init-subnet-instance policy {:node-id (:pubkey-hex info)
                                                                    :all-nodes anchors})
                       sm-cfg (merge config/default-config
                                     {:network-uri network-uri
                                      :network-genesis-cid network-genesis-cid
                                      :subnet-id network-uri
                                      :peers-map peers-map
                                      :keypair (:keypair info)})
                       sm (state-machine/init-state (vec anchors) (:pubkey-hex info) sm-cfg)]
                   [nid {:node-id nid
                         :tier :infra-validator
                         :pubkey-hex (:pubkey-hex info)
                         :pubkey-cid (:pubkey-cid info)
                         :keypair (:keypair info)
                         :subnet-instance (:instance sub-res)
                         :state-machine sm}])))
          kps)))

(defn- init-home-nodes
  [master-rng network-uri]
  (into {}
        (map (fn [nid]
               (let [info (derive-keypair master-rng (str "node/" (name nid) "/keygen"))
                     hs-ptr (subnet/sign-home-set-pointer
                             (get-in info [:keypair :private])
                             (:pubkey-hex info)
                             [{:node-id (str (name nid) "." network-uri) :price 10}]
                             {:subnet network-uri :price 10 :epoch 0 :seq 1})]
                 [nid {:node-id nid
                       :tier :home
                       :pubkey-hex (:pubkey-hex info)
                       :pubkey-cid (:pubkey-cid info)
                       :keypair (:keypair info)
                       :home-set-pointer hs-ptr
                       :mailbox {}}])))
        home-node-ids))

(defn- init-light-nodes
  [master-rng network-uri]
  (let [all-specs (concat
                   (map (fn [a]
                          {:id (:id a)
                           :archetype {:archetype-id (:id a)
                                       :role (:role a)
                                       :affiliation "Bitecho Founding"
                                       :external-archetype? (:external? a)
                                       :opt-in? (not (:external? a))
                                       :endorsed? (not (:external? a))
                                       :synthetic-archetype? (:external? a)}
                           :subscribed-subnets #{"root/founding"}})
                        founding-actors)
                   (map (fn [p]
                          {:id (:id p)
                           :archetype {:archetype-id (:id p)
                                       :role (:role p)
                                       :affiliation (:affiliation p)
                                       :external-archetype? true
                                       :opt-in? false
                                       :endorsed? false
                                       :synthetic-archetype? true}
                           :subscribed-subnets (cond-> #{(:circle p)}
                                                 (:market p) (conj (:market p)))})
                        partner-archetypes)
                   (map (fn [b]
                          {:id (:id b)
                           :archetype {:archetype-id (:id b)
                                       :role (:role b)
                                       :affiliation (:affiliation b)
                                       :external-archetype? true
                                       :opt-in? false
                                       :endorsed? false
                                       :synthetic-archetype? true}
                           :subscribed-subnets #{"root/austin/larg-builders" "root/markets/larg-tac"}})
                        larg-builder-archetypes))]
    (into {}
          (map (fn [{:keys [id archetype subscribed-subnets]}]
                 (let [info (derive-keypair master-rng (str "node/" (name id) "/keygen"))
                       ls (light-node/init-light-state {:pubkey-hex (:pubkey-hex info)
                                                        :fingerprint (:pubkey-cid info)}
                                                       {:epoch 0 :autonomous? true})]
                   [id {:node-id id
                        :tier :light
                        :pubkey-hex (:pubkey-hex info)
                        :pubkey-cid (:pubkey-cid info)
                        :sovereign-uri (str network-uri "/id/" (:pubkey-cid info))
                        :keypair (:keypair info)
                        :light-state ls
                        :subscribed-subnets subscribed-subnets
                        :archetype archetype}])))
          all-specs)))

(defn- init-advisory-twins
  [master-rng]
  (let [twins (into {}
                    (map (fn [tid]
                           (let [info (derive-keypair master-rng (str "twin/" (name tid) "/keygen"))]
                             [tid {:twin-id tid
                                   :pubkey-hex (:pubkey-hex info)
                                   :pubkey-cid (:pubkey-cid info)
                                   :keypair (:keypair info)}])))
                    advisory-twin-ids)
        twin-set (reduce (fn [acc t] (circle/register-advisory-twin acc (:pubkey-hex t)))
                         #{}
                         (vals twins))]
    {:advisory-twins twins
     :advisory-twin-pubkeys twin-set}))

;; =============================================================================
;; 7. Genesis Directory Descriptors, Encrypted Circles & 42B Vault Construction
;; =============================================================================

(defn- put-descriptor-blob
  [store desc]
  (let [blob (data/make-blob (fressian/encode-canonical desc) "application/fressian")
        res (data/put-blob store blob 0)]
    (:store res)))

(defn- build-genesis-descriptors
  [master-rng network-uri network-genesis-cid light-nodes twin-set]
  (let [simon (get light-nodes :founder-simon)
        simon-pk (:pubkey-hex simon)
        founding-signers [(signer-entry simon)
                          (signer-entry (get light-nodes :agent-axiom))
                          (signer-entry (get light-nodes :agent-nexus))]
        founding-pks (mapv :pubkey founding-signers)
        consent-cid (deterministic-cid (str "genesis-consent-round|" network-genesis-cid))
        mk-acct (fn [payer-pk]
                  {:bytes-at-rest 512
                   :cache-incentive :free-self-hosted
                   :payer-pubkey payer-pk
                   :fee-ech 0})
        circle-specs [{:path "root" :parent nil :vis :public :vault-id "vault:root/bootstrap" :bucket :bootstrap :alloc 4200000000}
                      {:path "root/founding" :parent "root" :vis :public :vault-id "vault:root/core-team-reserve" :bucket :team :alloc 2520000000}
                      {:path "root/austin/advisors" :parent "root" :vis :shared-encrypted :vault-id "vault:root/partners-reserve" :bucket :partners :alloc 2100000000}
                      {:path "root/austin/research" :parent "root" :vis :shared-encrypted :vault-id "vault:root/partners-reserve" :bucket :partners :alloc 840000000}
                      {:path "root/austin/larg-builders" :parent "root" :vis :shared-encrypted :vault-id "vault:root/ecosystem-reserve" :bucket :ecosystem :alloc 315000000}
                      {:path "root/markets/larg-tac" :parent "root" :vis :public :vault-id "vault:root/ecosystem-faucet" :bucket :ecosystem :alloc 0}
                      {:path "root/markets/midi-sim" :parent "root" :vis :public :vault-id "vault:root/ecosystem-faucet" :bucket :ecosystem :alloc 0}
                      {:path "root/markets/clear-games" :parent "root" :vis :public :vault-id "vault:root/ecosystem-faucet" :bucket :ecosystem :alloc 0}
                      {:path "root/markets/trustx-verify" :parent "root" :vis :public :vault-id "vault:root/ecosystem-faucet" :bucket :ecosystem :alloc 0}
                      {:path "root/angels/g4" :parent "root" :vis :shared-encrypted :vault-id "vault:root/partners-reserve" :bucket :partners :alloc 0}]
        init-acc {:descriptors {} :encrypted {} :blob-store (data/init-blob-store)}
        ;; 1. Circle descriptors (+ deterministic 3-arity encryption for :shared-encrypted)
        acc1 (reduce
              (fn [acc {:keys [path parent vis vault-id bucket alloc]}]
                (let [desc (circle/create-entity-descriptor
                            {:network-uri network-uri
                             :network-genesis-cid network-genesis-cid
                             :entity-kind :circle
                             :visibility vis
                             :path path
                             :parent-path parent
                             :owner-multisig {:threshold 2 :pubkeys founding-pks}
                             :purpose-cid (deterministic-cid (str "purpose|" path))
                             :consent-round-cid consent-cid
                             :vault-id vault-id
                             :custody-bucket bucket
                             :allocation-ech alloc
                             :retention-policy default-retention-policy
                             :storage-accounting (mk-acct simon-pk)
                             :issued-epoch 0
                             :expires-epoch 26280}
                            founding-signers)
                      v-res (circle/verify-entity-descriptor
                             desc
                             {:network-uri network-uri
                              :network-genesis-cid network-genesis-cid
                              :advisory-twin-pubkeys twin-set})]
                  (when-not (:ok? v-res)
                    (throw (ex-info "Genesis circle descriptor failed verification"
                                    {:path path :error (:error v-res)})))
                  (let [desc' (assoc desc :cid (:cid v-res))
                        store' (put-descriptor-blob (:blob-store acc) desc')]
                    (if (= :shared-encrypted vis)
                      (let [gk (derive-random-bytes master-rng (str "enc/group-key/" path) 32)
                            nonce (derive-random-bytes master-rng (str "enc/nonce/" path) 24)
                            env (circle/encrypt-shared-descriptor desc' gk {:nonce-bytes nonce})
                            store'' (put-descriptor-blob store' env)]
                        (-> acc
                            (assoc-in [:descriptors path] desc')
                            (assoc-in [:encrypted path] {:envelope env :group-key-bytes gk})
                            (assoc :blob-store store'')))
                      (-> acc
                          (assoc-in [:descriptors path] desc')
                          (assoc :blob-store store'))))))
              init-acc
              circle-specs)
        ;; 2. Sovereign <net>/id/<pubkey-cid> descriptors for every Light Node
        acc2 (reduce
              (fn [acc [_ node]]
                (let [pk (:pubkey-hex node)
                      path (str "id/" (:pubkey-cid node))
                      desc (circle/create-entity-descriptor
                            {:network-uri network-uri
                             :network-genesis-cid network-genesis-cid
                             :entity-kind :sovereign-id
                             :visibility :public
                             :path path
                             :parent-path nil
                             :owner-multisig {:threshold 1 :pubkeys [pk]}
                             :purpose-cid (deterministic-cid (str "sovereign-id|" (:pubkey-cid node)))
                             :retention-policy default-retention-policy
                             :storage-accounting (mk-acct pk)
                             :issued-epoch 0
                             :expires-epoch 26280}
                            [(signer-entry node)])
                      v-res (circle/verify-entity-descriptor
                             desc
                             {:network-uri network-uri
                              :network-genesis-cid network-genesis-cid
                              :advisory-twin-pubkeys twin-set})]
                  (when-not (:ok? v-res)
                    (throw (ex-info "Sovereign ID descriptor failed verification"
                                    {:node-id (:node-id node) :error (:error v-res)})))
                  (let [desc' (assoc desc :cid (:cid v-res))]
                    (-> acc
                        (assoc-in [:descriptors path] desc')
                        (update :blob-store put-descriptor-blob desc')))))
              acc1
              light-nodes)]
    ;; 3. LARG Builder :seat-lease entries under root/austin/larg-builders/<id>
    (reduce
     (fn [acc b]
       (let [node (get light-nodes (:id b))
             path (str "root/austin/larg-builders/" (name (:id b)))
             desc (circle/create-entity-descriptor
                   {:network-uri network-uri
                    :network-genesis-cid network-genesis-cid
                    :entity-kind :seat-lease
                    :visibility :shared-encrypted
                    :path path
                    :parent-path "root/austin/larg-builders"
                    :lease-holder-uri (:sovereign-uri node)
                    :owner-multisig {:threshold 2 :pubkeys founding-pks}
                    :purpose-cid (deterministic-cid (str "seat-lease|" path))
                    :custody-bucket :ecosystem
                    :allocation-ech (:allocation-ech b)
                    :retention-policy default-retention-policy
                    :storage-accounting (mk-acct simon-pk)
                    :issued-epoch 0
                    :expires-epoch 26280}
                   founding-signers)
             v-res (circle/verify-entity-descriptor
                    desc
                    {:network-uri network-uri
                     :network-genesis-cid network-genesis-cid
                     :advisory-twin-pubkeys twin-set})]
         (when-not (:ok? v-res)
           (throw (ex-info "Seat-lease descriptor failed verification"
                           {:path path :error (:error v-res)})))
         (let [desc' (assoc desc :cid (:cid v-res))]
           (-> acc
               (assoc-in [:descriptors path] desc')
               (update :blob-store put-descriptor-blob desc')))))
     acc2
     larg-builder-archetypes)))

(defn- init-genesis-state-and-vaults
  [network-uri network-genesis-cid infra-nodes light-nodes twin-set descriptors]
  (let [simon (get light-nodes :founder-simon)
        axiom (get light-nodes :agent-axiom)
        nexus (get light-nodes :agent-nexus)
        vector-n (get light-nodes :agent-vector)
        lumen (get light-nodes :agent-lumen)
        oracle-pk (get-in infra-nodes [:infra-ns1 :pubkey-hex])
        founding-pks [(:pubkey-hex simon) (:pubkey-hex axiom) (:pubkey-hex nexus)
                      (:pubkey-hex vector-n) (:pubkey-hex lumen)]
        multisig-src (vault/canonical-multisig-treasury-puzzle-source 2 founding-pks)
        multisig-ph (puzzle/puzzle-cid multisig-src)
        ;; Initial liquid ledger: LARG builders receive 250,000 ECHO initial stake/working-capital tranche (S2)
        genesis-ledger (into {}
                             (map (fn [b]
                                    (let [pk (get-in light-nodes [(:id b) :pubkey-hex])]
                                      [pk (account/map->AccountState {:balance (:initial-stake-ech b) :seq 0 :deps []})])))
                             larg-builder-archetypes)
        chartered-ids (into #{"vault:root/bootstrap"
                              "vault:root/core-team/simon"
                              "vault:root/core-agent-ops"
                              "vault:root/core-team-reserve"
                              "vault:root/partners-reserve"
                              "vault:root/ecosystem-faucet"
                              "vault:root/ecosystem-reserve"}
                            (concat
                             (map #(str "vault:root/partners/" (name (:id %))) partner-archetypes)
                             (map #(str "vault:root/ecosystem/" (name (:id %))) larg-builder-archetypes)))
        sm-cfg (merge config/default-config
                      {:network-uri network-uri
                       :network-genesis-cid network-genesis-cid
                       :subnet-id network-uri
                       :genesis-ledger genesis-ledger
                       :root-owner-multisig {:threshold 2 :pubkeys founding-pks}})
        sm0 (-> (state-machine/init-state (mapv :pubkey-hex (vals infra-nodes)) oracle-pk sm-cfg)
                (merge (vault/init-vaults-state {:network-uri network-uri
                                                 :network-genesis-cid network-genesis-cid
                                                 :genesis-custody-supply genesis-custody-supply-ech
                                                 :chartered-vaults chartered-ids
                                                 :ledger genesis-ledger}))
                (assoc :advisory-twin-pubkeys twin-set
                       :entity-descriptors descriptors
                       :root-owner-multisig {:threshold 2 :pubkeys founding-pks}
                       :reconciled-epoch-burns 0
                       :reconciled-epoch-takes 0))
        ;; Build all persistent vaults summing to 18,899,250,000 ECHO (+ 750,000 liquid = 18,900,000,000)
        core-vault-specs [{:vault-id "vault:root/bootstrap" :entity-uri (str network-uri "/root")
                           :custody-bucket :bootstrap :balance 4200000000}
                          {:vault-id "vault:root/core-team/simon" :entity-uri (str network-uri "/root/founding")
                           :custody-bucket :team :balance 2520000000}
                          ;; Twin S1: Core AI Agents' 1.26B held in founding multisig DAC vault
                          {:vault-id "vault:root/core-agent-ops" :entity-uri (str network-uri "/root/founding")
                           :custody-bucket :team :balance 1260000000}
                          {:vault-id "vault:root/core-team-reserve" :entity-uri (str network-uri "/root/founding")
                           :custody-bucket :team :balance 2520000000}
                          {:vault-id "vault:root/partners-reserve" :entity-uri (str network-uri "/root/founding")
                           :custody-bucket :partners :balance 2100000000}
                          {:vault-id "vault:root/ecosystem-faucet" :entity-uri (str network-uri "/root/founding")
                           :custody-bucket :ecosystem :balance 420000000}
                          {:vault-id "vault:root/ecosystem-reserve" :entity-uri (str network-uri "/root/founding")
                           :custody-bucket :ecosystem :balance 3465000000}]
        acc1 (reduce (fn [{:keys [state puzzles]} spec]
                       (let [res (vault/create-vault state (assoc spec :puzzle-hash multisig-ph :created-epoch 0))]
                         (when-not (:ok? res)
                           (throw (ex-info "Failed creating core vault" {:spec spec :error (:error res)})))
                         {:state (:state res)
                          :puzzles (assoc puzzles (:vault-id spec) {:puzzle-source multisig-src
                                                                    :puzzle-hash multisig-ph
                                                                    :kind :multisig})}))
                     {:state sm0 :puzzles {}}
                     core-vault-specs)
        ;; Twin S2: Per-partner isolated SLA-gated vesting sub-vaults
        acc2 (reduce (fn [{:keys [state puzzles]} p]
                       (let [vid (str "vault:root/partners/" (name (:id p)))
                             p-node (get light-nodes (:id p))
                             p-pk (:pubkey-hex p-node)
                             p-params {:beneficiary-pubkey p-pk
                                       :allowed-signers [(:pubkey-hex simon) p-pk]
                                       :threshold 2
                                       :advisory-twin-pubkeys twin-set
                                       :sla-oracle-pubkey oracle-pk
                                       :cliff-epoch default-cliff-epochs
                                       :vest-duration-epochs default-partner-vest-epochs
                                       :min-sla-bps default-min-sla-bps
                                       :total-grant-ech (:allocation-ech p)}
                             p-src (canonical-sla-vesting-puzzle-source p-params)
                             p-ph (puzzle/puzzle-cid p-src)
                             res (vault/create-vault state {:vault-id vid
                                                            :entity-uri (str network-uri "/" (:circle p))
                                                            :custody-bucket :partners
                                                            :puzzle-hash p-ph
                                                            :balance (:allocation-ech p)
                                                            :created-epoch 0})]
                         (when-not (:ok? res)
                           (throw (ex-info "Failed creating partner SLA vault" {:vault-id vid :error (:error res)})))
                         {:state (:state res)
                          :puzzles (assoc puzzles vid (assoc p-params
                                                             :puzzle-source p-src
                                                             :puzzle-hash p-ph
                                                             :kind :sla-vesting))}))
                     acc1
                     partner-archetypes)]
    ;; LARG builder milestone DAC sub-vaults (105,000,000 - 250,000 initial stake = 104,750,000 each)
    (reduce (fn [{:keys [state puzzles]} b]
              (let [vid (str "vault:root/ecosystem/" (name (:id b)))
                    rem-bal (- (long (:allocation-ech b)) (long (:initial-stake-ech b)))
                    res (vault/create-vault state {:vault-id vid
                                                   :entity-uri (str network-uri "/root/austin/larg-builders")
                                                   :custody-bucket :ecosystem
                                                   :puzzle-hash multisig-ph
                                                   :balance rem-bal
                                                   :created-epoch 0})]
                (when-not (:ok? res)
                  (throw (ex-info "Failed creating LARG builder vault" {:vault-id vid :error (:error res)})))
                {:state (:state res)
                 :puzzles (assoc puzzles vid {:puzzle-source multisig-src
                                              :puzzle-hash multisig-ph
                                              :kind :multisig})}))
            acc2
            larg-builder-archetypes)))

;; =============================================================================
;; 8. Public Fabric API
;; =============================================================================

(defn init-fabric
  "Initializes the full-network pure Sans-IO HIVE-SPEC-001 Workstream A fabric with:
   - Real seeded Ed25519 keypairs across Infra Validator, Home, and Light nodes (INV-1)
   - Exact 7-bucket 42,000,000,000 ECHO conservation & §2.2 default allocations (INV-2)
   - Immutable parent-RNG + canonical string-only `fork-random` paths + deterministic 3-arity
     `encrypt-shared-descriptor` (INV-3)
   - Separate operational sim keys vs shadow `:advisory-twin-pubkeys` (INV-8 / INV-TW1 / INV-C5)
   - Pure in-memory `NetworkBus` with deterministic fault injection."
  ([] (init-fabric {}))
  ([opts]
   (let [seed (long (or (:seed opts) 20261124))
         network-uri (str (or (:network-uri opts) "sim.bitecho.org"))
         network-genesis-cid (deterministic-cid (str "hive-sim-genesis-v1|" network-uri "|" seed))
         master-rng (crypto/make-random seed)
         infra-nodes (init-infra-nodes master-rng network-uri network-genesis-cid)
         home-nodes (init-home-nodes master-rng network-uri)
         light-nodes (init-light-nodes master-rng network-uri)
         {:keys [advisory-twins advisory-twin-pubkeys]} (init-advisory-twins master-rng)
         {:keys [descriptors encrypted blob-store]} (build-genesis-descriptors
                                                     master-rng network-uri network-genesis-cid
                                                     light-nodes advisory-twin-pubkeys)
         {:keys [state puzzles]} (init-genesis-state-and-vaults
                                  network-uri network-genesis-cid infra-nodes light-nodes
                                  advisory-twin-pubkeys descriptors)]
     {:seed seed
      :network-uri network-uri
      :network-genesis-cid network-genesis-cid
      :master-rng master-rng
      :infra-nodes infra-nodes
      :home-nodes home-nodes
      :light-nodes light-nodes
      :advisory-twins advisory-twins
      :advisory-twin-pubkeys advisory-twin-pubkeys
      :state state
      :vault-puzzles puzzles
      :blob-store blob-store
      :encrypted-descriptors encrypted
      :bus (init-network-bus (:bus-fault-policy opts))
      :block-seq 0
      :diagnostics {}})))

(defn project-light-node-balance
  "Projects a Light Node's spendable balance directly from the unified `state-machine` state (G4)."
  [fabric node-id]
  (if-let [pk (get-in fabric [:light-nodes node-id :pubkey-hex])]
    (state-machine/spendable-balance (:state fabric) pk)
    0))

(defn verify-fabric-conservation
  "Verifies exact 7-bucket 42,000,000,000 ECHO conservation (INV-2) AND non-negativity across
   every ledger account, vault, escrow, bounty-escrow, burned, sunset-burned, and unminted bucket."
  [fabric]
  (let [st (:state fabric)
        v-res (vault/verify-7-bucket-conservation st)
        minted (long (or (:total-minted st) 0))
        gen-cust (long (or (:genesis-custody-supply st) 0))
        unminted (- total-supply-cap-ech (+ gen-cust minted))
        neg-ledger? (some #(neg? (long (or (:balance %) 0))) (vals (:ledger st)))
        neg-vault? (some #(neg? (long (or (:balance %) 0))) (vals (:vaults st)))
        neg-escrow? (some #(neg? (long (or (:amount %) 0))) (vals (:escrows st)))
        neg-bucket? (or neg-ledger? neg-vault? neg-escrow?
                        (neg? unminted)
                        (neg? (long (or (:total-burned st) 0)))
                        (neg? (long (or (:sunset-custody-burned st) 0))))]
    (cond
      neg-bucket?
      {:ok? false :error :negative-balance-detected :unminted-conditional unminted}

      (not (:ok? v-res))
      (assoc v-res :unminted-conditional unminted)

      (not= total-supply-cap-ech (+ gen-cust minted unminted))
      {:ok? false :error :total-cap-mismatch :total-cap-ech (+ gen-cust minted unminted)}

      :else
      {:ok? true
       :total-cap-ech total-supply-cap-ech
       :genesis-custody-supply gen-cust
       :total-minted minted
       :unminted-conditional unminted
       :vault-check v-res})))

(defn- reconcile-state-machine-burns
  "Reconciles `P1` split-brain between `state_machine`'s `:epoch-burns`/`:epoch-settlement-takes`
   and `vault.cljc`'s `:total-burned` without double-counting, immune to `prune-epoch-state`
   evicting epochs older than the retention window."
  [st-before st-after]
  (let [pos-delta (fn [m-before m-after]
                    (reduce-kv (fn [acc k v]
                                 (+ acc (max 0 (- (long v) (long (get m-before k 0))))))
                               0
                               (or m-after {})))
        delta (+ (pos-delta (:epoch-burns st-before) (:epoch-burns st-after))
                 (pos-delta (:epoch-settlement-takes st-before) (:epoch-settlement-takes st-after)))]
    (if (pos? delta)
      (update st-after :total-burned (fnil + 0) delta)
      st-after)))

(defn apply-finalized-receipts
  "Applies a batch of finalized receipts through real `bitecho.state-machine/execute-finalized-blocks`:
   - Rejects unknown receipt `:type`s loudly with counted `:diagnose` `:unknown-receipt-type` (INV-7 / P1)
   - Reconciles `P1` `:epoch-settlement-takes` / `:epoch-burns` into `:total-burned` so 7-bucket
     conservation holds atomically across persistent vaults AND one-shot task escrows."
  [fabric receipts block-epoch]
  (let [ep (long block-epoch)
        known (filterv #(contains? supported-receipt-types (:type %)) receipts)
        unknown (filterv #(not (contains? supported-receipt-types (:type %))) receipts)
        unk-effects (mapv (fn [r]
                            {:effect :diagnose
                             :reason :unknown-receipt-type
                             :receipt-type (:type r)
                             :receipt-id (:receipt-id r)})
                          unknown)
        fab-diag (reduce (fn [f _]
                           (update-in f [:diagnostics :unknown-receipt-type] (fnil inc 0)))
                         fabric
                         unknown)]
    (if (empty? known)
      {:ok? (empty? unknown)
       :fabric fab-diag
       :effects unk-effects}
      (let [st (:state fab-diag)
            next-seq (inc (long (:block-seq fab-diag 0)))
            parent-hash (or (:latest-finalized-block-hash st) "genesis")
            block-hash (deterministic-cid (str "sim-block|" (:network-genesis-cid fab-diag) "|" next-seq "|" ep "|" (count known)))
            block {:hash block-hash
                   :parent-hash parent-hash
                   :epoch ep
                   :payload known}
            st-raw (-> st
                       (assoc-in [:blocks block-hash] block)
                       (update :finalized-blocks conj block-hash)
                       (assoc :latest-finalized-block-hash block-hash)
                       (state-machine/execute-finalized-blocks))
            st-exec (reconcile-state-machine-burns st st-raw)]
        {:ok? (empty? unknown)
         :fabric (-> fab-diag
                     (assoc :state st-exec :block-seq next-seq))
         :effects unk-effects}))))

(defn fabric-state-root-cid
  "Computes a deterministic 64-char SHA-256 CID over the canonical fabric state
   (pubkeys, vaults, ledger balances, escrows, descriptors, encrypted envelopes, bus)."
  [fabric]
  (let [st (:state fabric)
        summary {:network-uri (:network-uri fabric)
                 :network-genesis-cid (:network-genesis-cid fabric)
                 :infra-pks (into (sorted-map) (map (fn [[k v]] [(name k) (:pubkey-hex v)]) (:infra-nodes fabric)))
                 :home-pks (into (sorted-map) (map (fn [[k v]] [(name k) (:pubkey-hex v)]) (:home-nodes fabric)))
                 :light-pks (into (sorted-map) (map (fn [[k v]] [(name k) (:pubkey-hex v)]) (:light-nodes fabric)))
                 :advisory-twins (vec (sort (:advisory-twin-pubkeys fabric)))
                 :ledger (into (sorted-map) (map (fn [[pk a]] [pk (long (or (:balance a) 0))]) (:ledger st)))
                 :vaults (into (sorted-map) (map (fn [[vid v]] [vid (select-keys v [:balance :vault-seq :puzzle-hash :status :custody-bucket])]) (:vaults st)))
                 :escrows (into (sorted-map) (map (fn [[eid e]] [eid (select-keys e [:amount :status :payer :receiver])]) (:escrows st)))
                 :total-burned (long (or (:total-burned st) 0))
                 :sunset-custody-burned (long (or (:sunset-custody-burned st) 0))
                 :encrypted-cids (into (sorted-map) (map (fn [[p e]] [p (select-keys (:envelope e) [:nonce-hex :ciphertext-hex :blinded-path])])
                                                         (:encrypted-descriptors fabric)))
                 :bus-seq (long (get-in fabric [:bus :seq] 0))}]
    (str/lower-case (basalt/bytes->hex (crypto/sha256 (fressian/encode-canonical summary))))))
