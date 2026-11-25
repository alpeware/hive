(ns bitecho.hive.sim-lifecycle-test
  "Strict-TDD generative (`clojure.test.check`) and unit tests for HIVE-SPEC-001 Workstream B
   (`bitecho.hive.sim.lifecycle`):
   - INV-2: Exact 7-bucket 42B ECHO conservation EVERY epoch across 36 compressed months + Year-3 sunset burn
   - INV-3: CRN bit-identical determinism + cross-subnet PRNG stream isolation
   - INV-4: Cross-C scaling consistency, exact compound drawdown `1 - (1 - lambda)^C`, and `validator-activation-lead-epochs >= 3`
   - INV-5: Paired CRN commercial-subnet halt fault isolation + traffic diversion + isolated partner SLA pause
   - INV-7: Zero silent failures (counted `:diagnose` events)
   - INV-8: Clean dissolution (0-take preserving `:custody-bucket`, revoking `:seat-lease`, LARG builder sovereign earned revenue untouched)."
  (:require [bitecho.hive.sim.fabric :as fabric]
            [bitecho.hive.sim.lifecycle :as lifecycle]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; =============================================================================
;; INV-4: Proper Time-Compression Scaling (Fixes P2 / S4)
;; =============================================================================

(defspec ^{:doc "INV-4: scale-full-lifecycle-config scales all cliffs/vests/TTLs consistently, enforces validator-activation-lead-epochs >= 3, and computes exact compound drawdown 1 - (1 - lambda)^C."}
  inv4-scale-full-lifecycle-config-invariants 30
  (prop/for-all [c (gen/choose 1 144)
                 lambda (gen/double* {:min 0.00001 :max 0.01 :NaN? false :infinite? false})]
                (let [scaled (lifecycle/scale-full-lifecycle-config {:bootstrap-drawdown-lambda lambda} c)
                      expected-drawdown (- 1.0 (Math/pow (- 1.0 lambda) (double c)))
                      err (Math/abs (- (double (:bootstrap-drawdown-lambda scaled)) expected-drawdown))]
                  (and (= c (:time-compression-factor scaled))
                       (>= (:validator-activation-lead-epochs scaled) 3)
                       (>= (:bootstrap-sunset-epoch scaled) 1)
                       (>= (:core-team-cliff-epochs scaled) 1)
                       (>= (:core-team-vest-epochs scaled) (:core-team-cliff-epochs scaled))
                       (>= (:partner-cliff-epochs scaled) 1)
                       (>= (:partner-vest-epochs scaled) (:partner-cliff-epochs scaled))
                       (>= (:treasury-trailing-epochs scaled) 1)
                       (>= (:escrow-ttl-epochs scaled) 1)
                       (< err 1e-12)))))

;; =============================================================================
;; INV-2: Exact 7-Bucket 42B ECHO Conservation Every Epoch Across 36 Months
;; =============================================================================

(deftest inv2-full-36-month-conservation-and-sunset-burn-test
  (testing "Full 36-month+ compressed run (C=72, 450 epochs) conserves 42B ECHO on EVERY epoch, zero failed receipts, all 6 validators minted, real NetworkBus & validator rotations, and Year-3 Bootstrap Sunset Burn"
    (let [res (lifecycle/run-full-network-trial
               {:scenario :baseline-g4-angel-heavy
                :seed 20261124
                :time-compression-factor 72
                :epochs 450
                :return-fabric? true})]
      (is (true? (:all-epochs-conserved? res)))
      (is (true? (get-in res [:final-conservation :ok?])))
      (is (= 42000000000 (get-in res [:final-conservation :total-cap-ech])))
      (is (= 0 (get-in res [:fabric :state :failed-receipts-count])) "Zero failed receipts across entire lifecycle")
      (is (= 0 (:failed-receipts-count res)))
      (is (every? #(some? (:subnet-instance %)) (vals (get-in res [:fabric :commercial-subnets])))
          "Every commercial subnet must have a valid :subnet-instance anchored to bitecho.org/infra")
      (is (pos? (get-in res [:fabric :bus :seq])) "NetworkBus must route real signed envelopes")
      (is (pos? (get-in res [:protocol-infra :rotations-verified])) "Validator set rotations must verify & apply")
      (is (pos? (:bootstrap-drawn-ech res)) "Bootstrap vault must draw down gradually before sunset")
      (is (pos? (:core-team-vested-ech res)) "Core-Team vault must vest linearly after cliff")
      (is (pos? (:sunset-burned-ech res)) "Year-3 bootstrap sunset burn must fire at scaled epoch 26,280")
      (is (pos? (:cleared-gmv-ech res)) "Endogenous capability market must clear non-zero GMV")
      (is (pos? (:market-burned-ech res)) "Market settlement take must burn ECHO")
      (is (pos? (:vesting-vault-burned-ech res)) "Vault spends must burn ECHO")
      (is (= (:total-burned-ech res) (+ (:market-burned-ech res) (:vesting-vault-burned-ech res))))
      (is (= 6 (count (:infra-validator-balances-ech res))))
      (is (every? pos? (vals (:infra-validator-balances-ech res)))
          (str "ALL 6 Infra Validators must receive non-zero ECHO mints, got " (:infra-validator-balances-ech res)))
      (is (true? (get-in res [:g4-angels :onboarded?])))
      (is (true? (get-in res [:g4-angels :seed-closed?]))))))

;; =============================================================================
;; INV-3: CRN Bit-Identical Determinism & Cross-Subnet PRNG Stream Isolation
;; =============================================================================

(defspec ^{:doc "INV-3: Identical trial configs produce bit-identical state-root CID, cleared GMV, burned/minted totals, and diagnostics."}
  inv3-crn-bit-identical-trial-determinism 5
  (prop/for-all [seed (gen/choose 1 100000)]
                (let [cfg {:scenario :baseline-g4-angel-heavy
                           :seed seed
                           :time-compression-factor 144
                           :epochs 60}
                      r1 (lifecycle/run-full-network-trial cfg)
                      r2 (lifecycle/run-full-network-trial cfg)]
                  (and (= (:state-root-cid r1) (:state-root-cid r2))
                       (= (:cleared-gmv-ech r1) (:cleared-gmv-ech r2))
                       (= (:total-burned-ech r1) (:total-burned-ech r2))
                       (= (:total-minted-ech r1) (:total-minted-ech r2))
                       (= (:diagnostics r1) (:diagnostics r2))))))

(deftest inv3-cross-subnet-prng-stream-isolation-test
  (testing "Adding an extra commercial subnet NEVER perturbs bitecho.org/infra PRNG stream digest"
    (let [r-base (lifecycle/run-full-network-trial
                  {:scenario :baseline-g4-angel-heavy
                   :seed 555
                   :time-compression-factor 144
                   :epochs 40
                   :extra-commercial-subnets []})
          r-extra (lifecycle/run-full-network-trial
                   {:scenario :baseline-g4-angel-heavy
                    :seed 555
                    :time-compression-factor 144
                    :epochs 40
                    :extra-commercial-subnets ["root/markets/organic-alpha"]})]
      (is (= (get-in r-base [:protocol-infra :infra-rng-digest])
             (get-in r-extra [:protocol-infra :infra-rng-digest]))
          "Protocol infra PRNG stream must be bit-identical when a commercial subnet is added"))))

;; =============================================================================
;; INV-5: Paired CRN Commercial-Subnet Halt Isolation & Traffic Diversion
;; =============================================================================

(deftest inv5-paired-crn-subnet-halt-isolation-and-diversion-test
  (testing "Halting root/markets/larg-tac pauses ONLY :partner-larg-tac vesting, diverts traffic to sibling subnets, and never stalls bitecho.org/infra"
    (let [r-base (lifecycle/run-full-network-trial
                  {:scenario :baseline-g4-angel-heavy
                   :seed 20261124
                   :time-compression-factor 72
                   :epochs 250})
          r-halt (lifecycle/run-full-network-trial
                  {:scenario :subnet-halt-fault-isolation
                   :seed 20261124
                   :time-compression-factor 72
                   :epochs 250})
          base-larg-paused (get-in r-base [:partner-vesting :partner-larg-tac :paused-claims] 0)
          halt-larg-paused (get-in r-halt [:partner-vesting :partner-larg-tac :paused-claims] 0)
          base-larg-vested (get-in r-base [:partner-vesting :partner-larg-tac :vested-ech] 0)
          halt-larg-vested (get-in r-halt [:partner-vesting :partner-larg-tac :vested-ech] 0)
          base-midi-vested (get-in r-base [:partner-vesting :partner-midi-sim :vested-ech] 0)
          halt-midi-paused (get-in r-halt [:partner-vesting :partner-midi-sim :paused-claims] 0)
          halt-midi-vested (get-in r-halt [:partner-vesting :partner-midi-sim :vested-ech] 0)
          base-midi-gmv (get-in r-base [:per-subnet-gmv-ech "root/markets/midi-sim"] 0)
          halt-midi-gmv (get-in r-halt [:per-subnet-gmv-ech "root/markets/midi-sim"] 0)]
      (is (true? (:all-epochs-conserved? r-halt)))
      (is (= 0 (:failed-receipts-count r-halt)))
      ;; Only :partner-larg-tac experiences SLA-paused vesting claims AND measurably lower vested ECHO (no lump-sum catch-up)
      (is (> halt-larg-paused base-larg-paused))
      (is (< halt-larg-vested base-larg-vested)
          (str "Paused epochs must reduce M36 vested ECHO for :partner-larg-tac without lump-sum catch-up: halt=" halt-larg-vested " base=" base-larg-vested))
      (is (= 0 halt-midi-paused))
      (is (= base-midi-vested halt-midi-vested) "Healthy sibling :partner-midi-sim vesting must be untouched")
      ;; Traffic strictly diverts from halted larg-tac to healthy sibling commercial subnet(s)
      (is (> halt-midi-gmv base-midi-gmv)
          (str "Expected diverted GMV strictly > baseline on midi-sim: halt=" halt-midi-gmv " base=" base-midi-gmv))
      ;; Protocol infra never stalls due to commercial subnet halt
      (is (= (get-in r-base [:protocol-infra :upgrade-stalls])
             (get-in r-halt [:protocol-infra :upgrade-stalls])))
      ;; INV-7: SLA pause is counted in diagnostics
      (is (pos? (get-in r-halt [:diagnostics :partner-vesting-sla-paused] 0))))))

;; =============================================================================
;; INV-8: Clean Dissolution (0-Take Custody-Preserving + Builder Revenue Safe)
;; =============================================================================

(deftest inv8-dissolution-preserves-custody-and-sovereign-builder-revenue-test
  (testing "Dissolving a commercial circle/subnet returns principal at 0-take preserving :custody-bucket, revokes :seat-lease, and leaves LARG builder earned revenue 100% untouched"
    (let [trial (lifecycle/run-full-network-trial
                 {:scenario :baseline-g4-angel-heavy
                  :seed 9001
                  :time-compression-factor 144
                  :epochs 40
                  :return-fabric? true})
          fab-before (:fabric trial)
          builder-pk (get-in fab-before [:light-nodes :builder-larg-1 :pubkey-hex])
          builder-bal-before (get-in fab-before [:state :ledger builder-pk :balance])
          parent-bal-before (get-in fab-before [:state :vaults "vault:root/ecosystem-reserve" :balance])
          child-bal-before (get-in fab-before [:state :vaults "vault:root/ecosystem/builder-larg-1" :balance])
          diss-res (lifecycle/dissolve-commercial-circle
                    fab-before
                    {:circle-path "root/austin/larg-builders"
                     :seat-lease-paths ["root/austin/larg-builders/builder-larg-1"]
                     :child-vault-id "vault:root/ecosystem/builder-larg-1"
                     :parent-vault-id "vault:root/ecosystem-reserve"})
          fab-after (:fabric diss-res)
          builder-bal-after (get-in fab-after [:state :ledger builder-pk :balance])
          parent-bal-after (get-in fab-after [:state :vaults "vault:root/ecosystem-reserve" :balance])]
      (is (:ok? diss-res) (str "Dissolution failed: " diss-res))
      (is (:ok? (fabric/verify-fabric-conservation fab-after)))
      ;; 0-take return of unspent child vault principal to parent vault preserving :ecosystem bucket
      (is (= (+ parent-bal-before child-bal-before) parent-bal-after))
      (is (= :ecosystem (get-in fab-after [:state :vaults "vault:root/ecosystem-reserve" :custody-bucket])))
      ;; Seat-lease revoked
      (is (= :revoked (get-in fab-after [:state :entity-descriptors "root/austin/larg-builders/builder-larg-1" :status])))
      ;; LARG builder's earned sovereign market balance is 100% untouched!
      (is (= builder-bal-before builder-bal-after))
      (is (>= builder-bal-after fabric/larg-initial-stake-ech))
      ;; Attempting to revoke a sovereign id/<pk-cid> during dissolution MUST be rejected with :sovereign-root-non-revocable
      (let [sov-path (str "id/" (get-in fab-before [:light-nodes :builder-larg-1 :pubkey-cid]))
            bad-diss (lifecycle/dissolve-commercial-circle
                      fab-before
                      {:circle-path "root/austin/larg-builders"
                       :seat-lease-paths [sov-path]
                       :child-vault-id "vault:root/ecosystem/builder-larg-1"
                       :parent-vault-id "vault:root/ecosystem-reserve"})]
        (is (false? (:ok? bad-diss)))
        (is (= :sovereign-root-non-revocable (:error bad-diss)))
        (is (pos? (get-in (:fabric bad-diss) [:diagnostics :sovereign-root-non-revocable] 0)))
        (is (not= :revoked (get-in (:fabric bad-diss) [:state :entity-descriptors sov-path :status])))))))

;; =============================================================================
;; Multi-Scenario CRN Sweep (All 5 Scenarios + G4 Anchor Threshold Sweep)
;; =============================================================================

(deftest multi-scenario-crn-sweep-test
  (testing "All 6 canonical scenarios execute cleanly with exact 42B conservation, zero failed receipts, and expected comparative dynamics"
    (let [sweep (lifecycle/run-scenario-sweep
                 {:scenarios lifecycle/all-scenarios
                  :seeds [20261124]
                  :time-compression-factor 144
                  :epochs 230})]
      (is (= 6 (count (:results sweep))))
      (doseq [r (:results sweep)]
        (is (true? (:all-epochs-conserved? r)) (str "Conservation failed in " (:scenario r)))
        (is (= 0 (:failed-receipts-count r)) (str "Unexpected failed receipts in " (:scenario r)))
        (is (string? (:state-root-cid r)))
        (is (= 64 (count (:state-root-cid r)))))
      ;; High-velocity commercial > baseline > bear-post-sunset-cliff (post-sunset divergence!)
      (let [by-scen (into {} (map (juxt :scenario identity) (:results sweep)))]
        (is (> (:cleared-gmv-ech (get by-scen :high-velocity-commercial))
               (:cleared-gmv-ech (get by-scen :baseline-g4-angel-heavy))))
        (is (> (:cleared-gmv-ech (get by-scen :baseline-g4-angel-heavy))
               (:cleared-gmv-ech (get by-scen :bear-post-sunset-cliff))))))))

;; =============================================================================
;; V3 Capability-Market Lifecycle Integration Tests (Strict TDD)
;; =============================================================================

(deftest v3-lifecycle-telemetry-and-builder-cashflow-identity-test
  (testing "V3 trial surfaces per-provider GMV, per-capability GMV (including V3 capabilities & recurring storage rent), exact builder cashflow identity (delta==0), endogenous entry/exit counters, calibration anchors, and 36-month endogenous market telemetry"
    (let [res (lifecycle/run-full-network-trial
               {:scenario :high-velocity-commercial
                :seed 20261124
                :time-compression-factor 72
                :epochs 450})]
      (is (true? (:all-epochs-conserved? res)))
      (is (= 0 (:failed-receipts-count res)))
      (is (map? (:per-provider-gmv-ech res)))
      (is (pos? (count (:per-provider-gmv-ech res))))
      (is (map? (:per-capability-gmv-ech res)))
      ;; V3 capabilities cleared
      (doseq [c [:llm-inference :web-search-extract :sandbox-compute :storage-bytes-at-rest :bandwidth-bytes-in-motion :eval-verify]]
        (is (pos? (get-in res [:per-capability-gmv-ech c] 0))
            (str "Expected positive V3 GMV for capability " c)))
      ;; Exact builder cashflow identity for all 3 LARG builders
      (is (map? (:builder-cashflow res)))
      (doseq [b [:builder-larg-1 :builder-larg-2 :builder-larg-3]]
        (is (= 0 (get-in res [:builder-cashflow b :identity-delta-ech]))
            (str "Expected exact 0 cashflow identity delta for " b ", got " (get-in res [:builder-cashflow b]))))
      ;; Endogenous provider counters & calibration anchors
      (is (integer? (:endogenous-providers-entered res)))
      (is (integer? (:endogenous-providers-exited res)))
      (is (pos? (:endogenous-providers-entered res))
          "High-velocity commercial scenario should trigger endogenous provider entry")
      (is (map? (:calibration-anchors res)))
      (is (= :unanchored-calibration-param (get-in res [:calibration-anchors :formal-verify :price-anchor-status])))
      ;; 36-month endogenous monthly telemetry for seed_to_round piping
      (is (= 36 (count (:monthly-market-telemetry res)))))))

;; =============================================================================
;; V4 Strict-TDD Tests: M2/M3 Telemetry, Resellers, SaaS Agents & Reach Growth
;; =============================================================================

(deftest v4-m2-m3-telemetry-and-reseller-saas-reach-growth-test
  (testing "V4 :reseller-saas-reach-growth surfaces M2/M3 telemetry, reseller accounting, :saas-api-call + intra/cross split, Reach growth, 0 failed receipts, [0 0 0] builder cashflow deltas, and exact 42B conservation"
    (let [res (lifecycle/run-full-network-trial
               {:scenario :reseller-saas-reach-growth
                :seed 20261124
                :time-compression-factor 72
                :epochs 450})
          cleared-gmv (long (:cleared-gmv-ech res))
          fv-gmv (long (get-in res [:per-capability-gmv-ech :formal-verify] 0))
          ws-gmv (long (get-in res [:per-capability-gmv-ech :web-search-extract] 0))
          ws-brk (:web-search-meter-breakdown res)]
      ;; Core conservation & zero failed receipts
      (is (true? (:all-epochs-conserved? res)))
      (is (= 0 (:failed-receipts-count res)))
      (is (= [0 0 0] (mapv #(get-in res [:builder-cashflow % :identity-delta-ech])
                           [:builder-larg-1 :builder-larg-2 :builder-larg-3])))
      ;; M2: ex-formal-verify GMV & formal-verify share pct
      (is (= (- cleared-gmv fv-gmv) (:ex-formal-verify-gmv-ech res)))
      (is (<= 0.0 (double (:formal-verify-share-pct res)) 100.0))
      ;; M3: failover utility gamma, failover-insured orders & web-search meter breakdown
      (is (= 0.15 (:failover-utility-gamma res)))
      (is (pos? (:failover-insured-orders res)))
      (is (<= (:failover-insured-orders res) (:cleared-orders res)))
      (is (map? ws-brk))
      (is (= ws-gmv (+ (long (:query-ech ws-brk 0))
                       (long (:extract-ech ws-brk 0))
                       (long (:freshness-egress-ech ws-brk 0)))))
      ;; Reseller accounting
      (is (pos? (:reseller-gmv-ech res)))
      (is (pos? (:reseller-upstream-cost-ech res)))
      (is (>= (:reseller-gmv-ech res)
              (+ (:reseller-upstream-cost-ech res) (:reseller-net-margin-ech res))))
      ;; SaaS-agent & intra-company vs cross-company split
      (is (pos? (get-in res [:per-capability-gmv-ech :saas-api-call] 0)))
      (is (pos? (:intra-company-events res)))
      (is (pos? (:cross-company-cleared-orders res)))
      ;; Reach growth & endogenous provider entry
      (is (pos? (:endogenous-providers-entered res)))
      (is (pos? (get-in res [:reach-growth :mealy-native-agents] 0)))
      (is (pos? (get-in res [:reach-growth :oss-harness-agents] 0))))))
