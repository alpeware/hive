(ns bitecho.hive.sim-fabric-test
  "Strict-TDD generative (`clojure.test.check`) and unit tests for HIVE-SPEC-001 Workstream A
   (`bitecho.hive.sim.fabric`): INV-1, INV-2, INV-3, INV-6 (MUT-10), INV-7, INV-8 (INV-TW1/INV-C5),
   Canonical SLA-Gated Vesting SCI Puzzle, Pure In-Memory NetworkBus, and Live Protocol-Gap
   Falsifiers (P1-P5) against `alpeware/bitecho @ f1eef45c`."
  (:require [bitecho.basalt.core :as basalt]
            [bitecho.circles.mc-harness :as mc-harness]
            [bitecho.circles.vault :as vault]
            [bitecho.core.circle :as circle]
            [bitecho.core.subnet :as subnet]
            [bitecho.crypto :as crypto]
            [bitecho.economy.puzzle :as puzzle]
            [bitecho.fressian :as fressian]
            [bitecho.hive.sim.fabric :as fabric]
            [bitecho.light-node.core :as light-node]
            [bitecho.state-machine :as state-machine]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- flip-first-bit
  ^bytes [^bytes bs]
  (let [out (byte-array (alength bs))]
    (System/arraycopy bs 0 out 0 (alength bs))
    (aset-byte out 0 (unchecked-byte (bit-xor (aget out 0) 0x01)))
    out))

(def ^:private forbidden-real-names
  ["Peter Stone" "Amy Zhang" "David Fridovich-Keil" "Ufuk Topcu"
   "Elias Stengel-Eskin" "Noah Golowich" "Risto Miikkulainen"
   "Jiaxun Cui" "Jiaheng Hu" "Cameron Angliss"])

;; =============================================================================
;; INV-1: Zero-Shortcut Real Ed25519 Authenticity (All 3 Node Tiers)
;; =============================================================================

(defspec ^{:doc "INV-1: Every Infra, Home, and Light node holds a distinct real seeded Ed25519 SPKI keypair verified via crypto/sign + crypto/verify with 1-bit-flip negative rejection."}
  inv1-real-ed25519-keypairs-and-1bit-flip-rejection 10
  (prop/for-all [seed (gen/choose 1 1000000)
                 msg-str gen/string-alphanumeric]
                (let [fab (fabric/init-fabric {:seed seed})
                      all-nodes (concat (vals (:infra-nodes fab))
                                        (vals (:home-nodes fab))
                                        (vals (:light-nodes fab)))
                      payload (fressian/utf8->bytes (str "inv1-msg:" msg-str))]
                  (and (pos? (count (:infra-nodes fab)))
                       (pos? (count (:home-nodes fab)))
                       (pos? (count (:light-nodes fab)))
           ;; All operational pubkeys are distinct canonical 88-char SPKI DER hex
                       (= (count all-nodes) (count (distinct (map :pubkey-hex all-nodes))))
                       (every? (fn [node]
                                 (let [pk (:pubkey-hex node)
                                       priv (get-in node [:keypair :private])
                                       sig (crypto/sign priv payload)
                                       flipped-sig (flip-first-bit sig)
                                       flipped-msg (flip-first-bit (if (pos? (alength payload))
                                                                     payload
                                                                     (fressian/utf8->bytes "x")))]
                                   (and (circle/valid-spki-pubkey? pk)
                                        (true? (crypto/verify pk payload sig))
                                        (false? (crypto/verify pk payload flipped-sig))
                                        (or (zero? (alength payload))
                                            (false? (crypto/verify pk flipped-msg sig))))))
                               all-nodes)))))

;; =============================================================================
;; INV-2: Exact 7-Bucket 42,000,000,000 ECHO Conservation & §2.2 Default Table
;; =============================================================================

(deftest inv2-exact-42b-conservation-and-seat-allocations-test
  (testing "Genesis fabric conserves exactly 42,000,000,000 ECHO across all 7 buckets and matches §2.2 + Twin S1/S2"
    (let [fab (fabric/init-fabric {:seed 20261124})
          st (:state fab)
          cons-res (fabric/verify-fabric-conservation fab)
          vaults (:vaults st)
          ledger (:ledger st)]
      (is (:ok? cons-res) (str "Conservation failed: " cons-res))
      (is (= 42000000000 (:total-cap-ech cons-res)))
      (is (= 18900000000 (:genesis-custody-supply st)))
      (is (= 23100000000 (:unminted-conditional cons-res)))
      ;; Bootstrap (10% = 4.2B)
      (is (= 4200000000 (get-in vaults ["vault:root/bootstrap" :balance])))
      (is (= :bootstrap (get-in vaults ["vault:root/bootstrap" :custody-bucket])))
      ;; Core Team (15% = 6.3B) — Core AI Agents' 1.26B held in founding multisig DAC per Twin S1
      (is (= 2520000000 (get-in vaults ["vault:root/core-team/simon" :balance])))
      (is (= 1260000000 (get-in vaults ["vault:root/core-agent-ops" :balance])))
      (is (= 2520000000 (get-in vaults ["vault:root/core-team-reserve" :balance])))
      (is (= 6300000000 (+ (get-in vaults ["vault:root/core-team/simon" :balance])
                           (get-in vaults ["vault:root/core-agent-ops" :balance])
                           (get-in vaults ["vault:root/core-team-reserve" :balance]))))
      ;; Genesis Partners (10% = 4.2B) — explicit per-partner isolated SLA sub-vaults per Twin S2
      (let [partner-vault-ids ["vault:root/partners/partner-larg-tac"
                               "vault:root/partners/partner-midi-sim"
                               "vault:root/partners/partner-clear-games"
                               "vault:root/partners/partner-trustx-verify"
                               "vault:root/partners/partner-grounded-lang"
                               "vault:root/partners/partner-ml-theory"
                               "vault:root/partners/partner-neuroevo"
                               "vault:root/partners-reserve"]]
        (is (every? #(contains? vaults %) partner-vault-ids))
        (is (= 420000000 (get-in vaults ["vault:root/partners/partner-larg-tac" :balance])))
        (is (= 210000000 (get-in vaults ["vault:root/partners/partner-trustx-verify" :balance])))
        (is (= 2100000000 (get-in vaults ["vault:root/partners-reserve" :balance])))
        (is (= 4200000000 (reduce + 0 (map #(get-in vaults [% :balance]) partner-vault-ids)))))
      ;; Ecosystem (10% = 4.2B) — LARG builders get 250,000 ECHO initial stake/working-capital tranche (S2)
      (let [larg-ids [:builder-larg-1 :builder-larg-2 :builder-larg-3]
            larg-liquid (reduce + 0 (map (fn [lid]
                                           (let [pk (get-in fab [:light-nodes lid :pubkey-hex])]
                                             (get-in ledger [pk :balance] 0)))
                                         larg-ids))
            larg-vaults (reduce + 0 (map #(get-in vaults [(str "vault:root/ecosystem/" (name %)) :balance] 0)
                                         larg-ids))
            eco-total (+ larg-liquid
                         larg-vaults
                         (get-in vaults ["vault:root/ecosystem-faucet" :balance])
                         (get-in vaults ["vault:root/ecosystem-reserve" :balance]))]
        (is (= 750000 larg-liquid))
        (doseq [lid larg-ids]
          (let [pk (get-in fab [:light-nodes lid :pubkey-hex])]
            (is (= 250000 (get-in ledger [pk :balance])))
            (is (= 250000 (fabric/project-light-node-balance fab lid)))))
        (is (= 4200000000 eco-total))))))

(defspec ^{:doc "INV-2: Across arbitrary seeds, genesis fabric conserves exactly 42,000,000,000 ECHO across all 7 buckets with zero negative balances."}
  inv2-conservation-across-arbitrary-seeds 10
  (prop/for-all [seed (gen/choose 1 1000000)]
                (let [fab (fabric/init-fabric {:seed seed})
                      res (fabric/verify-fabric-conservation fab)]
                  (and (:ok? res)
                       (= 42000000000 (:total-cap-ech res))
                       (every? #(not (neg? (long (or (:balance %) 0)))) (vals (get-in fab [:state :ledger])))
                       (every? #(not (neg? (long (or (:balance %) 0)))) (vals (get-in fab [:state :vaults])))))))

;; =============================================================================
;; INV-3: CRN Bit-Identical Determinism (Zero SecureRandom, String-Only Fork Paths)
;; =============================================================================

(defspec ^{:doc "INV-3: Across arbitrary seeds, identical fabric configs produce bit-identical state-root CIDs, pubkeys, and 3-arity deterministic encrypted descriptor nonces/ciphertexts."}
  inv3-crn-bit-identical-determinism 10
  (prop/for-all [seed (gen/choose 1 1000000)]
                (let [fab-a (fabric/init-fabric {:seed seed})
                      fab-b (fabric/init-fabric {:seed seed})
                      root-a (fabric/fabric-state-root-cid fab-a)
                      root-b (fabric/fabric-state-root-cid fab-b)
                      enc-a (into {} (map (fn [[k v]] [k (select-keys (:envelope v) [:nonce-hex :ciphertext-hex :blinded-path])])
                                          (:encrypted-descriptors fab-a)))
                      enc-b (into {} (map (fn [[k v]] [k (select-keys (:envelope v) [:nonce-hex :ciphertext-hex :blinded-path])])
                                          (:encrypted-descriptors fab-b)))]
                  (and (string? root-a)
                       (= 64 (count root-a))
                       (= root-a root-b)
                       (pos? (count enc-a))
                       (= enc-a enc-b)
                       (= (mapv :pubkey-hex (vals (:light-nodes fab-a)))
                          (mapv :pubkey-hex (vals (:light-nodes fab-b))))))))

(deftest inv3-shared-encrypted-roundtrip-deterministic-test
  (testing "3-arity encrypt-shared-descriptor uses deterministic :nonce-bytes and round-trips cleanly"
    (let [fab (fabric/init-fabric {:seed 4242})
          enc-entry (get-in fab [:encrypted-descriptors "root/austin/advisors"])
          dec-res (circle/decrypt-shared-descriptor (:envelope enc-entry) (:group-key-bytes enc-entry))]
      (is (some? enc-entry))
      (is (:ok? dec-res))
      (is (= :shared-encrypted (get-in dec-res [:descriptor :visibility])))
      (is (= 48 (count (get-in enc-entry [:envelope :nonce-hex])))))))

;; =============================================================================
;; INV-6 (MUT-10): Strict Persistent Vault vs One-Shot Task Escrow Separation
;; =============================================================================

(deftest inv6-mut10-persistent-vault-vs-oneshot-escrow-test
  (testing "Persistent Circle Vaults reject :refund-remainder and :refund-intent (MUT-10)"
    (let [fab (fabric/init-fabric {:seed 20261124})
          st (:state fab)
          vid "vault:root/ecosystem-faucet"
          v (get-in st [:vaults vid])
          ;; 1. Direct vault/reject-vault-refund
          rej (vault/reject-vault-refund st vid)
          ;; 2. Puzzle returning :refund-remainder in :vault-mode? true
          refund-puzzle "(fn [env _sol] [{:type :refund-remainder :receiver (:vault-id env) :amount (:amount env)}])"
          eval-res (puzzle/eval-puzzle refund-puzzle {} {:vault-mode? true
                                                         :amount (:balance v)
                                                         :vault-id vid
                                                         :puzzle-hash (puzzle/puzzle-cid refund-puzzle)})
          ;; 3. Finalized :refund-intent receipt targeting a persistent vault via state-machine
          sm-res (fabric/apply-finalized-receipts
                  fab
                  [{:receipt-id "rcpt-vault-refund-attack"
                    :type :refund-intent
                    :escrow-id vid
                    :vault-id vid
                    :epoch 1}]
                  1)]
      (is (false? (:ok? rej)))
      (is (= :vault-refund-forbidden (:error rej)))
      (is (false? (:valid? eval-res)))
      (is (= :vault-refund-forbidden (:error eval-res)))
      (is (pos? (get-in (:fabric sm-res) [:state :diagnostics :vault-refund-forbidden] 0)))
      (is (= (:balance v) (get-in (:fabric sm-res) [:state :vaults vid :balance])))
      (is (:ok? (fabric/verify-fabric-conservation (:fabric sm-res))))))

  (testing "One-shot Task Escrows settle via :lock-intent / :release-intent and refund 100% on timeout via :refund-intent"
    (let [fab (fabric/init-fabric {:seed 20261124})
          buyer-pk (get-in fab [:light-nodes :builder-larg-1 :pubkey-hex])
          provider-pk (get-in fab [:light-nodes :builder-larg-2 :pubkey-hex])
          preimage "deliverable-secret-001"
          d-hash (str/lower-case (basalt/bytes->hex (crypto/sha256 (fressian/utf8->bytes preimage))))
          ;; Lock + release escrow-1 (10,000 ECHO)
          r1 (fabric/apply-finalized-receipts
              fab
              [{:receipt-id "rcpt-lock-1"
                :type :lock-intent
                :escrow-id "escrow-task-1"
                :payer buyer-pk
                :receiver provider-pk
                :amount 10000
                :deliverable-hash d-hash
                :created-epoch 0
                :expires-epoch 10
                :epoch 0}]
              1)
          fab1 (:fabric r1)
          r2 (fabric/apply-finalized-receipts
              fab1
              [{:receipt-id "rcpt-rel-1"
                :type :release-intent
                :escrow-id "escrow-task-1"
                :preimage preimage
                :sender provider-pk
                :epoch 1}]
              2)
          fab2 (:fabric r2)
          ;; Lock + timeout refund escrow-2 (5,000 ECHO)
          r3 (fabric/apply-finalized-receipts
              fab2
              [{:receipt-id "rcpt-lock-2"
                :type :lock-intent
                :escrow-id "escrow-task-2"
                :payer buyer-pk
                :receiver provider-pk
                :amount 5000
                :deliverable-hash d-hash
                :created-epoch 1
                :expires-epoch 5
                :epoch 1}]
              3)
          fab3 (:fabric r3)
          r4 (fabric/apply-finalized-receipts
              fab3
              [{:receipt-id "rcpt-ref-2"
                :type :refund-intent
                :escrow-id "escrow-task-2"
                :sender buyer-pk
                :epoch 6}]
              7)
          fab4 (:fabric r4)]
      (is (:ok? (fabric/verify-fabric-conservation fab1)))
      (is (= :locked (get-in fab1 [:state :escrows "escrow-task-1" :status])))
      (is (= :released (get-in fab2 [:state :escrows "escrow-task-1" :status])))
      ;; 50 bps of 10,000 = 50 ECHO burned, 9,950 credited to provider
      (is (= 50 (get-in fab2 [:state :total-burned])))
      (is (= (+ 250000 9950) (get-in fab2 [:state :ledger provider-pk :balance])))
      (is (:ok? (fabric/verify-fabric-conservation fab2)))
      ;; Timeout refund returns 100% of 5,000 to buyer
      (is (= :refunded (get-in fab4 [:state :escrows "escrow-task-2" :status])))
      (is (= (- 250000 10000) (get-in fab4 [:state :ledger buyer-pk :balance])))
      (is (:ok? (fabric/verify-fabric-conservation fab4))))))

;; =============================================================================
;; INV-7: Zero Silent Failures (Counted :diagnose Events)
;; =============================================================================

(deftest inv7-zero-silent-failures-test
  (testing "Unknown receipt types emit counted :diagnose instead of silently falling through"
    (let [fab (fabric/init-fabric {:seed 777})
          res (fabric/apply-finalized-receipts
               fab
               [{:receipt-id "rcpt-bogus-1"
                 :type :create-vault ;; Wrong name (P1 trap)!
                 :vault-id "vault:bogus"
                 :epoch 0}]
               1)
          fab' (:fabric res)]
      (is (pos? (get-in fab' [:diagnostics :unknown-receipt-type] 0)))
      (is (some #(= :unknown-receipt-type (:reason %)) (:effects res))))))

;; =============================================================================
;; INV-8 (INV-TW1 + INV-C5): Operational Sim Keys vs Shadow Advisory Twins & Privacy
;; =============================================================================

(deftest inv8-advisory-twin-isolation-and-privacy-hygiene-test
  (let [fab (fabric/init-fabric {:seed 20261124})
        op-pks (set (concat (map :pubkey-hex (vals (:infra-nodes fab)))
                            (map :pubkey-hex (vals (:home-nodes fab)))
                            (map :pubkey-hex (vals (:light-nodes fab)))))
        twin-pks (:advisory-twin-pubkeys fab)
        sample-twin (first (vals (:advisory-twins fab)))
        sample-op (get-in fab [:light-nodes :founder-simon])
        net-uri (:network-uri fab)
        gen-cid (:network-genesis-cid fab)]
    (testing "Operational sim keys are strictly disjoint from shadow :advisory-twin-pubkeys"
      (is (pos? (count twin-pks)))
      (is (empty? (set/intersection op-pks twin-pks)))
      (is (= twin-pks (get-in fab [:state :advisory-twin-pubkeys]))))

    (testing "Grow-only advisory-twin registry rejects shrinking transitions (INV-C5)"
      (let [shrunk (disj twin-pks (first twin-pks))
            tr (circle/validate-advisory-twin-registry-transition twin-pks shrunk)
            grown (circle/register-advisory-twin twin-pks (:pubkey-hex sample-op))
            tr-ok (circle/validate-advisory-twin-registry-transition twin-pks grown)]
        (is (false? (:ok? tr)))
        (is (= :advisory-twin-registry-shrunk (:error tr)))
        (is (true? (:ok? tr-ok)))))

    (testing "Shadow advisory-twin key is rejected by circle/verify-entity-descriptor (:advisory-twin-cannot-own-entity)"
      (let [twin-pk (:pubkey-hex sample-twin)
            twin-desc (circle/create-entity-descriptor
                       {:network-uri net-uri
                        :network-genesis-cid gen-cid
                        :entity-kind :sovereign-id
                        :visibility :public
                        :path (str "id/" (circle/pubkey-cid twin-pk))
                        :parent-path nil
                        :owner-multisig {:threshold 1 :pubkeys [twin-pk]}
                        :purpose-cid (fabric/deterministic-cid "twin-attempt")
                        :retention-policy fabric/default-retention-policy
                        :storage-accounting {:bytes-at-rest 256
                                             :cache-incentive :free-self-hosted
                                             :payer-pubkey twin-pk
                                             :fee-ech 0}}
                       [{:pubkey twin-pk :privkey (get-in sample-twin [:keypair :private])}])
            v-res (circle/verify-entity-descriptor
                   twin-desc
                   {:network-uri net-uri
                    :network-genesis-cid gen-cid
                    :advisory-twin-pubkeys twin-pks})]
        (is (false? (:ok? v-res)))
        (is (= :advisory-twin-cannot-own-entity (:error v-res)))))

    (testing "Shadow advisory-twin key is rejected by circle/verify-consent-round (:advisory-twin-cannot-consent)"
      (let [twin-pk (:pubkey-hex sample-twin)
            op-pk (:pubkey-hex sample-op)
            round {:lead-link-pubkey op-pk
                   :quorum-size 2
                   :ballots {op-pk :consent
                             twin-pk :consent}}
            c-res (circle/verify-consent-round round {:advisory-twin-pubkeys twin-pks})]
        (is (false? (:ok? c-res)))
        (is (= :advisory-twin-cannot-consent (:error c-res)))))

    (testing "Public-repo privacy hygiene & root-contained paths (Twin S1/S2)"
      (let [descs (get-in fab [:state :entity-descriptors])
            descs-str (pr-str descs)]
        ;; Zero real faculty/builder names in signed wire descriptors
        (doseq [real-name forbidden-real-names]
          (is (not (str/includes? descs-str real-name))
              (str "Real name leaked into signed wire descriptors: " real-name)))
        ;; Every external archetype carries explicit synthetic attribution metadata off-wire
        (doseq [[_ node] (:light-nodes fab)]
          (when (:external-archetype? (:archetype node))
            (is (false? (get-in node [:archetype :opt-in?])))
            (is (false? (get-in node [:archetype :endorsed?])))
            (is (true? (get-in node [:archetype :synthetic-archetype?])))))
        ;; Root-contained org paths exist and verify
        (doseq [p ["root" "root/founding" "root/austin/advisors"
                   "root/austin/research" "root/austin/larg-builders"
                   "root/markets/larg-tac" "root/markets/midi-sim"
                   "root/markets/clear-games" "root/markets/trustx-verify"
                   "root/angels/g4"]]
          (is (contains? descs p) (str "Missing root-contained circle descriptor: " p)))))))

;; =============================================================================
;; Real Canonical SLA-Gated Vesting SCI Puzzle (Positive + All Attack Vectors)
;; =============================================================================

(deftest canonical-sla-vesting-sci-puzzle-test
  (let [fab (fabric/init-fabric {:seed 20261124})
        vid "vault:root/partners/partner-larg-tac"
        p-info (get-in fab [:vault-puzzles vid])
        oracle-node (get-in fab [:infra-nodes :infra-ns1])
        beneficiary-node (get-in fab [:light-nodes :partner-larg-tac])
        founder-node (get-in fab [:light-nodes :founder-simon])
        twin-node (first (vals (:advisory-twins fab)))
        cliff (:cliff-epoch p-info)
        vest-dur (:vest-duration-epochs p-info)
        min-sla (:min-sla-bps p-info)
        total-grant (:total-grant-ech p-info)
        ;; At epoch = cliff + vest-dur/4 => 25% vested = 105,000,000 ECHO
        ep-q1 (+ cliff (quot vest-dur 4))
        claim-q1 (quot total-grant 4)
        build-sol (fn [{:keys [epoch sla-score release-amt next-ph signers oracle-kp]}]
                    (fabric/build-sla-vesting-solution
                     {:fabric fab
                      :vault-id vid
                      :epoch epoch
                      :sla-score sla-score
                      :release-amount release-amt
                      :next-puzzle-hash next-ph
                      :signers signers
                      :oracle-keypair oracle-kp}))]

    (testing "Valid SLA-attested vesting spend succeeds, enforces relock to same puzzle-hash, and conserves 42B"
      (let [sol (build-sol {:epoch ep-q1
                            :sla-score min-sla
                            :release-amt claim-q1
                            :next-ph (:puzzle-hash p-info)
                            :signers [founder-node beneficiary-node]
                            :oracle-kp (:keypair oracle-node)})
            res (vault/spend-vault (:state fab)
                                   {:vault-id vid
                                    :vault-seq 1
                                    :puzzle-source (:puzzle-source p-info)
                                    :solution sol
                                    :epoch ep-q1})]
        (is (:ok? res) (str "Valid SLA vest rejected: " res))
        (is (= (- total-grant claim-q1) (get-in res [:state :vaults vid :balance])))
        (is (= (:puzzle-hash p-info) (get-in res [:state :vaults vid :puzzle-hash])))
        (is (= 2 (get-in res [:state :vaults vid :vault-seq])))
        (is (:ok? (vault/verify-7-bucket-conservation (:state res))))))

    (testing "Pre-cliff spend is rejected"
      (let [sol (build-sol {:epoch (dec cliff)
                            :sla-score 9900
                            :release-amt 1000
                            :next-ph (:puzzle-hash p-info)
                            :signers [founder-node beneficiary-node]
                            :oracle-kp (:keypair oracle-node)})
            res (vault/spend-vault (:state fab)
                                   {:vault-id vid :vault-seq 1 :puzzle-source (:puzzle-source p-info)
                                    :solution sol :epoch (dec cliff)})]
        (is (false? (:ok? res)))))

    (testing "SLA breach (sla-score < min-sla-bps) pauses vesting"
      (let [sol (build-sol {:epoch ep-q1
                            :sla-score (dec min-sla)
                            :release-amt claim-q1
                            :next-ph (:puzzle-hash p-info)
                            :signers [founder-node beneficiary-node]
                            :oracle-kp (:keypair oracle-node)})
            res (vault/spend-vault (:state fab)
                                   {:vault-id vid :vault-seq 1 :puzzle-source (:puzzle-source p-info)
                                    :solution sol :epoch ep-q1})]
        (is (false? (:ok? res)))))

    (testing "Unattested / spoofed SLA oracle signature is rejected"
      (let [sol (build-sol {:epoch ep-q1
                            :sla-score 9999
                            :release-amt claim-q1
                            :next-ph (:puzzle-hash p-info)
                            :signers [founder-node beneficiary-node]
                            ;; Signed by beneficiary instead of oracle!
                            :oracle-kp (:keypair beneficiary-node)})
            res (vault/spend-vault (:state fab)
                                   {:vault-id vid :vault-seq 1 :puzzle-source (:puzzle-source p-info)
                                    :solution sol :epoch ep-q1})]
        (is (false? (:ok? res)))))

    (testing "Lump-sum drain exceeding linear vested schedule is blocked"
      (let [sol (build-sol {:epoch ep-q1
                            :sla-score 9999
                            :release-amt (inc claim-q1)
                            :next-ph (:puzzle-hash p-info)
                            :signers [founder-node beneficiary-node]
                            :oracle-kp (:keypair oracle-node)})
            res (vault/spend-vault (:state fab)
                                   {:vault-id vid :vault-seq 1 :puzzle-source (:puzzle-source p-info)
                                    :solution sol :epoch ep-q1})]
        (is (false? (:ok? res)))))

    (testing "Puzzle-rotation bypass (next-puzzle-hash != current puzzle-hash) is blocked"
      (let [evil-ph (fabric/deterministic-cid "evil-puzzle-rotation")
            sol (build-sol {:epoch ep-q1
                            :sla-score 9999
                            :release-amt claim-q1
                            :next-ph evil-ph
                            :signers [founder-node beneficiary-node]
                            :oracle-kp (:keypair oracle-node)})
            res (vault/spend-vault (:state fab)
                                   {:vault-id vid :vault-seq 1 :puzzle-source (:puzzle-source p-info)
                                    :solution sol :epoch ep-q1})]
        (is (false? (:ok? res)))))

    (testing "Shadow advisory-twin key cannot satisfy SLA vesting signer quorum"
      (let [sol (build-sol {:epoch ep-q1
                            :sla-score 9999
                            :release-amt claim-q1
                            :next-ph (:puzzle-hash p-info)
                            :signers [founder-node twin-node]
                            :oracle-kp (:keypair oracle-node)})
            res (vault/spend-vault (:state fab)
                                   {:vault-id vid :vault-seq 1 :puzzle-source (:puzzle-source p-info)
                                    :solution sol :epoch ep-q1})]
        (is (false? (:ok? res)))))))

;; =============================================================================
;; Pure In-Memory NetworkBus (Signed Envelopes + Deterministic Fault Injection)
;; =============================================================================

(deftest pure-in-memory-network-bus-test
  (let [fab (fabric/init-fabric {:seed 9001})
        light-n (get-in fab [:light-nodes :builder-larg-1])
        home-n (get-in fab [:home-nodes :home-austin-1])
        infra-n (get-in fab [:infra-nodes :infra-ns1])
        gossip-env (light-node/build-gossip-envelope
                    (:pubkey-hex light-n)
                    (get-in light-n [:keypair :private])
                    (fressian/utf8->bytes "hello-capability-market")
                    100
                    "root/markets/larg-tac")
        hs-env (light-node/build-home-set-envelope
                (:pubkey-hex light-n)
                (get-in light-n [:keypair :private])
                [(:pubkey-hex home-n)]
                10
                1)]

    (testing "Routes signed envelopes with deterministic latency"
      (let [bus0 (fabric/init-network-bus {:min-latency-epochs 1 :max-latency-epochs 2})
            rng (crypto/fork-random (:master-rng fab) "bus/test/latency")
            s1 (fabric/bus-send bus0 rng {:from :builder-larg-1 :to :infra-ns1
                                          :subnet-id "root/markets/larg-tac"
                                          :envelope gossip-env :epoch 1})
            s2 (fabric/bus-send (:bus s1) rng {:from :builder-larg-1 :to :home-austin-1
                                               :subnet-id "root/founding"
                                               :envelope hs-env :epoch 1})
            d1 (fabric/bus-deliver-epoch (:bus s2) 1)
            d3 (fabric/bus-deliver-epoch (:bus d1) 3)]
        (is (:ok? s1))
        (is (:ok? s2))
        (is (empty? (:deliveries d1)))
        (is (= 2 (count (:deliveries d3))))
        (is (some? (:home-set-pointer home-n)))
        (is (:ok? (subnet/verify-home-set-pointer
                   (get-in home-n [:home-set-pointer :pointer])
                   (get-in home-n [:home-set-pointer :blob]))))
        (is (true? (get-in infra-n [:subnet-instance :bitecho.subnet/unified?])))))

    (testing "Partition and drop-rate fault injection emit counted :diagnose events (INV-7)"
      (let [bus-part (fabric/init-network-bus {:partitions #{#{:builder-larg-1 :infra-ns1}}})
            rng (crypto/fork-random (:master-rng fab) "bus/test/partition")
            r-part (fabric/bus-send bus-part rng {:from :builder-larg-1 :to :infra-ns1
                                                  :subnet-id "root/markets/larg-tac"
                                                  :envelope gossip-env :epoch 1})
            bus-drop (fabric/init-network-bus {:drop-rate 1.0})
            r-drop (fabric/bus-send bus-drop rng {:from :builder-larg-1 :to :home-austin-1
                                                  :subnet-id "root/founding"
                                                  :envelope hs-env :epoch 1})]
        (is (false? (:ok? r-part)))
        (is (= :bus-partition-dropped (:error r-part)))
        (is (= 1 (get-in r-part [:bus :diagnostics :bus-partition-dropped])))
        (is (false? (:ok? r-drop)))
        (is (= :bus-fault-dropped (:error r-drop)))
        (is (= 1 (get-in r-drop [:bus :diagnostics :bus-fault-dropped])))))))

;; =============================================================================
;; Live Protocol-Gap Falsifier Tests (P1-P5 on alpeware/bitecho @ f1eef45c)
;; =============================================================================

(deftest protocol-gap-falsifiers-p1-to-p5-test
  (testing "P1: Raw f1eef45c state-machine dispatches :vault-create/:vault-spend, silently drops unknown receipt types, and splits :epoch-settlement-takes vs :total-burned"
    (let [fab (fabric/init-fabric {:seed 101})
          st (:state fab)
          buyer-pk (get-in fab [:light-nodes :builder-larg-1 :pubkey-hex])
          prov-pk (get-in fab [:light-nodes :builder-larg-2 :pubkey-hex])
          preimage "p1-secret"
          d-hash (str/lower-case (basalt/bytes->hex (crypto/sha256 (fressian/utf8->bytes preimage))))
          ;; Raw unknown receipt (:create-vault instead of :vault-create) passes silently without incrementing :failed-receipts-count
          b1 {:hash "p1-block-1" :parent-hash "genesis" :epoch 1
              :payload [{:receipt-id "p1-unknown" :type :create-vault :vault-id "v-ignored" :epoch 0}]}
          st1 (state-machine/execute-finalized-blocks
               (-> st
                   (assoc-in [:blocks "p1-block-1"] b1)
                   (update :finalized-blocks conj "p1-block-1")
                   (assoc :latest-finalized-block-hash "p1-block-1")))
          ;; Raw :lock-intent + :release-intent updates :epoch-settlement-takes WITHOUT updating :total-burned
          b2 {:hash "p1-block-2" :parent-hash "p1-block-1" :epoch 2
              :payload [{:receipt-id "p1-lock" :type :lock-intent :escrow-id "p1-esc"
                         :payer buyer-pk :receiver prov-pk :amount 10000
                         :deliverable-hash d-hash :created-epoch 1 :expires-epoch 10 :epoch 1}
                        {:receipt-id "p1-rel" :type :release-intent :escrow-id "p1-esc"
                         :preimage preimage :sender prov-pk :epoch 1}]}
          st2 (state-machine/execute-finalized-blocks
               (-> st1
                   (assoc-in [:blocks "p1-block-2"] b2)
                   (update :finalized-blocks conj "p1-block-2")
                   (assoc :latest-finalized-block-hash "p1-block-2")))]
      (is (nil? (get-in st1 [:vaults "v-ignored"])))
      (is (= 0 (:failed-receipts-count st1)))
      ;; P1 split-brain falsifier: raw :release-intent incremented :epoch-settlement-takes (50) but :total-burned stayed 0!
      (is (= 50 (get-in st2 [:epoch-settlement-takes 1])))
      (is (= 0 (:total-burned st2)))
      (is (false? (:ok? (vault/verify-7-bucket-conservation st2))))))

  (testing "P2: Raw f1eef45c mc-harness/scale-lifecycle-config clamps :validator-activation-lead-epochs to 1 for C >= 12"
    (let [scaled (mc-harness/scale-lifecycle-config {} 36)]
      (is (= 1 (:validator-activation-lead-epochs scaled)))))

  (testing "P3: Raw f1eef45c puzzle/eval-puzzle rejects :allocate when :vault-mode? false"
    (let [p-src "(fn [_env _sol] [{:type :allocate :target-vault-id \"vault:root/ecosystem\" :amount 100}])"
          res (puzzle/eval-puzzle p-src {} {:vault-mode? false :amount 100})]
      (is (false? (:valid? res)))
      (is (= :invalid-puzzle-conditions (:error res)))))

  (testing "P4: Raw f1eef45c puzzle/eval-puzzle rejects boolean true in :vault-mode? true"
    (let [p-src "(fn [_env _sol] true)"
          res (puzzle/eval-puzzle p-src {} {:vault-mode? true :amount 100})]
      (is (false? (:valid? res)))
      (is (= :vault-requires-explicit-conditions (:error res)))))

  (testing "P5: Raw f1eef45c circle/dissolve-circle permits :custody-bucket mismatch whereas fabric/dissolve-circle-safe blocks it; encrypted envelope rejected by verify-entity-descriptor"
    (let [fab (fabric/init-fabric {:seed 505})
          st (:state fab)
          founder-pk (get-in fab [:light-nodes :founder-simon :pubkey-hex])
          ;; Create a synthetic child circle with :bootstrap vault pointing to :ecosystem parent vault
          c-vid "vault:root/temp-boot-child"
          st-with-child (-> st
                            (assoc-in [:vaults c-vid] {:vault-id c-vid :entity-uri "sim.bitecho.org/root/temp-boot-child"
                                                       :custody-bucket :bootstrap :puzzle-hash (fabric/deterministic-cid "p")
                                                       :balance 5000 :vault-seq 1 :created-epoch 0 :status :active})
                            (assoc-in [:entity-descriptors "root/temp-boot-child"]
                                      {:entity-kind :circle :path "root/temp-boot-child" :vault-id c-vid}))
          round {:lead-link-pubkey founder-pk :quorum-size 1 :ballots {founder-pk :consent}}
          ctx {:advisory-twin-pubkeys (:advisory-twin-pubkeys fab)}
          ;; Raw f1eef45c circle/dissolve-circle succeeds and launders :bootstrap principal into :ecosystem vault!
          raw-res (circle/dissolve-circle st-with-child
                                          {:circle-path "root/temp-boot-child"
                                           :parent-vault-id "vault:root/ecosystem-reserve"
                                           :consent-round round}
                                          ctx)
          ;; Our fabric/dissolve-circle-safe fails closed with :custody-bucket-mismatch
          safe-res (fabric/dissolve-circle-safe st-with-child
                                                {:circle-path "root/temp-boot-child"
                                                 :parent-vault-id "vault:root/ecosystem-reserve"
                                                 :consent-round round}
                                                ctx)
          ;; Encrypted envelope is rejected by circle/verify-entity-descriptor
          enc-env (get-in fab [:encrypted-descriptors "root/austin/advisors" :envelope])
          enc-verify (circle/verify-entity-descriptor enc-env {})]
      (is (true? (:ok? raw-res)))
      (is (= :ecosystem (get-in raw-res [:state :vaults "vault:root/ecosystem-reserve" :custody-bucket])))
      (is (false? (:ok? safe-res)))
      (is (= :custody-bucket-mismatch (:error safe-res)))
      (is (false? (:ok? enc-verify)))
      (is (= :invalid-descriptor-type (:error enc-verify))))))
