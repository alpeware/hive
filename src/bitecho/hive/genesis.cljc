(ns bitecho.hive.genesis
  "Pure Sans-IO `.cljc` Genesis / Circle State Model & Run-4 Monte Carlo Future Fork Engine.

   Pinned to `docs/specs/legal-circles-map-excerpt-2026-11-20.md`
   (CID `8039db926a1d9ee5ab7b5a9c198512de20dd79def8e7687ed8e3c1b0e28d14d6`).

   Core invariants:
   - ECH conservation: unallocated genesis pool + all active circle pools + all member
     receipt balances == `pre-mint-total` across EVERY state transition.
   - Circle ownership: Pointer-style multisig (`>= threshold` distinct circle members).
   - Circle visibility: ONLY `:public` or `:shared-encrypted` (under the shared key of
     ALL circle members — no third mode, no single-member private silo).
   - Member root memory: private, encrypted, hash-chained receipt log per pubkey.
   - Run-4 Monte Carlo futures: fork the simulated system across candidate strategic
     directions, run seeded deterministic Monte Carlo rollouts with simulated human-anchor
     and digital-twin members, and select the data-driven future with highest path likelihood."
  (:require [bitecho.hive.env :as env]
            [clojure.set :as set]))

(def ^:const GENESIS-SPEC-CID
  "SHA-256 CID of `dist/hive/legal-circles-map-excerpt-2026-11-20.md` on `bitecho.org/dev`."
  "8039db926a1d9ee5ab7b5a9c198512de20dd79def8e7687ed8e3c1b0e28d14d6")

(def ^:private valid-visibilities #{:public :shared-encrypted})

(defn- reject
  [st reason detail]
  (let [diag (merge {:reason reason} detail)]
    [(-> st
         (update-in [:diagnostics :rejected-ops] inc)
         (update-in [:diagnostics :events] conj diag))
     [[:diagnose diag]]]))

(defn init-genesis
  "Initializes a pure Genesis State root anchored to `GENESIS-SPEC-CID` with fixed
   `pre-mint-total` and initial member root memories (private + encrypted)."
  [{:keys [entity-id pre-mint-total members spec-cid]
    :or {spec-cid GENESIS-SPEC-CID}}]
  (let [pre-mint (long pre-mint-total)
        memories (into {}
                       (map (fn [{:keys [pubkey role label]}]
                              (let [pk (str pubkey)
                                    init-head (env/sha256-str (pr-str {:genesis-member pk :entity entity-id}))]
                                [pk {:pubkey pk
                                     :role (or role :digital-twin)
                                     :label (or label pk)
                                     :private-encrypted? true
                                     :balance 0
                                     :head-cid init-head
                                     :receipts []}]))
                            members))]
    {:spec-cid (str spec-cid)
     :entity-id (str entity-id)
     :pre-mint-total pre-mint
     :unallocated-ech pre-mint
     :circles {}
     :member-memories memories
     :transition-count 0
     :diagnostics {:rejected-ops 0 :events []}}))

(defn total-ech
  "Sums all ECH across every branch: genesis unallocated pool + all active circle
   allocations + all member root-memory receipt balances."
  [st]
  (let [circle-sum (reduce + 0 (map :allocation (vals (:circles st))))
        member-sum (reduce + 0 (map :balance (vals (:member-memories st))))]
    (+ (long (:unallocated-ech st)) (long circle-sum) (long member-sum))))

(defn conservation-holds?
  "True iff all pools and balances are non-negative and sum to `:pre-mint-total`."
  [st]
  (and (>= (long (:unallocated-ech st)) 0)
       (every? #(>= (long (:allocation %)) 0) (vals (:circles st)))
       (every? #(>= (long (:balance %)) 0) (vals (:member-memories st)))
       (= (long (:pre-mint-total st)) (total-ech st))))

(defn state-root-cid
  "Deterministic SHA-256 state root of the legal entity's digital genesis state."
  [st]
  (env/sha256-str
   (pr-str {:spec-cid (:spec-cid st)
            :entity-id (:entity-id st)
            :pre-mint-total (:pre-mint-total st)
            :unallocated-ech (:unallocated-ech st)
            :circles (into (sorted-map)
                           (map (fn [[k v]]
                                  [k (select-keys v [:circle-id :parent-id :allocation
                                                     :members :threshold :visibility
                                                     :seq :state-cid])])
                                (:circles st)))
            :member-heads (into (sorted-map)
                                (map (fn [[pk m]] [pk {:balance (:balance m) :head-cid (:head-cid m)}])
                                     (:member-memories st)))})))

(defn verify-member-memory-chain
  "Purely verifies a member's private encrypted root-memory receipt hash chain and balance."
  [mem]
  (let [receipts (:receipts mem)]
    (and (true? (:private-encrypted? mem))
         (= (long (:balance mem)) (reduce + 0 (map :amount receipts)))
         (if (empty? receipts)
           (string? (:head-cid mem))
           (loop [idx 0
                  prev-cid (:prev-cid (first receipts))]
             (if (>= idx (count receipts))
               (= prev-cid (:head-cid mem))
               (let [r (nth receipts idx)
                     expected (env/sha256-str (pr-str {:prev-cid (:prev-cid r)
                                                       :circle-id (:circle-id r)
                                                       :member-pubkey (:member-pubkey r)
                                                       :amount (:amount r)
                                                       :memo (:memo r)
                                                       :seq (:seq r)}))]
                 (if (and (= prev-cid (:prev-cid r))
                          (= expected (:receipt-cid r)))
                   (recur (inc idx) expected)
                   false))))))))

(defn- multisig-valid?
  [member-set threshold signers]
  (let [valid-signers (set/intersection (set member-set) (set signers))]
    (>= (count valid-signers) (long threshold))))

(defn- active-children
  [st circle-id]
  (filter #(= circle-id (:parent-id %)) (vals (:circles st))))

(defn- handle-spawn-circle
  [st {:keys [circle-id parent-id description allocation members threshold
              visibility shared-key-holders signers]
       :or {parent-id :genesis description ""}}]
  (let [member-set (set members)
        known-members (set (keys (:member-memories st)))
        alloc (long (or allocation -1))
        thresh (long (or threshold 0))
        parent-circle (when (not= :genesis parent-id) (get-in st [:circles parent-id]))]
    (cond
      (nil? circle-id)
      (reject st :missing-circle-id {})

      (contains? (:circles st) circle-id)
      (reject st :circle-already-exists {:circle-id circle-id})

      (and (not= :genesis parent-id) (nil? parent-circle))
      (reject st :unknown-parent-circle {:parent-id parent-id})

      (< alloc 0)
      (reject st :negative-allocation {:circle-id circle-id :allocation alloc})

      (or (empty? member-set) (not (set/subset? member-set known-members)))
      (reject st :invalid-circle-members {:circle-id circle-id :members member-set})

      (or (< thresh 1) (> thresh (count member-set)))
      (reject st :invalid-multisig-threshold {:circle-id circle-id :threshold thresh :members (count member-set)})

      (not (contains? valid-visibilities visibility))
      (reject st :invalid-visibility-mode {:circle-id circle-id :visibility visibility})

      (and (= :shared-encrypted visibility)
           (not= member-set (set shared-key-holders)))
      (reject st :shared-key-mismatch {:circle-id circle-id
                                       :members member-set
                                       :shared-key-holders (set shared-key-holders)})

      (and (= :genesis parent-id)
           (not (multisig-valid? known-members 1 signers)))
      (reject st :unauthorized-genesis-spawn {:circle-id circle-id :signers (set signers)})

      (and (not= :genesis parent-id)
           (not (multisig-valid? (:members parent-circle) (:threshold parent-circle) signers)))
      (reject st :insufficient-parent-multisig {:circle-id circle-id :parent-id parent-id})

      (and (= :genesis parent-id) (> alloc (long (:unallocated-ech st))))
      (reject st :insufficient-genesis-ech {:circle-id circle-id :requested alloc :available (:unallocated-ech st)})

      (and (not= :genesis parent-id) (> alloc (long (:allocation parent-circle))))
      (reject st :insufficient-parent-ech {:circle-id circle-id :parent-id parent-id
                                           :requested alloc :available (:allocation parent-circle)})

      :else
      (let [circle {:circle-id circle-id
                    :parent-id parent-id
                    :description (str description)
                    :members member-set
                    :threshold thresh
                    :visibility visibility
                    :shared-key-holders (when (= :shared-encrypted visibility) member-set)
                    :allocation alloc
                    :seq 0
                    :state-cid (env/sha256-str (pr-str {:circle-id circle-id :parent-id parent-id
                                                        :visibility visibility :members (sort member-set)}))}
            st' (if (= :genesis parent-id)
                  (-> st
                      (update :unallocated-ech - alloc)
                      (assoc-in [:circles circle-id] circle)
                      (update :transition-count inc))
                  (-> st
                      (update-in [:circles parent-id :allocation] - alloc)
                      (assoc-in [:circles circle-id] circle)
                      (update :transition-count inc)))]
        [st' [[:emit-circle-spawned {:circle-id circle-id :parent-id parent-id :allocation alloc}]]]))))

(defn- handle-update-circle-state
  [st {:keys [circle-id payload-cid signers]}]
  (let [circle (get-in st [:circles circle-id])]
    (cond
      (nil? circle)
      (reject st :unknown-circle {:circle-id circle-id})

      (not (string? payload-cid))
      (reject st :invalid-payload-cid {:circle-id circle-id})

      (not (multisig-valid? (:members circle) (:threshold circle) signers))
      (reject st :insufficient-circle-multisig {:circle-id circle-id
                                                :required (:threshold circle)
                                                :signers (set signers)})

      :else
      (let [next-seq (inc (long (:seq circle)))
            st' (-> st
                    (assoc-in [:circles circle-id :seq] next-seq)
                    (assoc-in [:circles circle-id :state-cid] payload-cid)
                    (update :transition-count inc))]
        [st' [[:emit-circle-state-updated {:circle-id circle-id :seq next-seq :state-cid payload-cid}]]]))))

(defn- handle-disburse-to-member
  [st {:keys [circle-id member-pubkey amount memo signers]}]
  (let [circle (get-in st [:circles circle-id])
        mem (get-in st [:member-memories member-pubkey])
        amt (long (or amount -1))]
    (cond
      (nil? circle)
      (reject st :unknown-circle {:circle-id circle-id})

      (nil? mem)
      (reject st :unknown-member {:member-pubkey member-pubkey})

      (<= amt 0)
      (reject st :non-positive-disbursement {:circle-id circle-id :amount amt})

      (not (multisig-valid? (:members circle) (:threshold circle) signers))
      (reject st :insufficient-circle-multisig {:circle-id circle-id :required (:threshold circle)})

      (> amt (long (:allocation circle)))
      (reject st :insufficient-circle-ech {:circle-id circle-id :requested amt :available (:allocation circle)})

      :else
      (let [next-seq (inc (count (:receipts mem)))
            prev-cid (:head-cid mem)
            rcpt-cid (env/sha256-str (pr-str {:prev-cid prev-cid
                                              :circle-id circle-id
                                              :member-pubkey member-pubkey
                                              :amount amt
                                              :memo (str memo)
                                              :seq next-seq}))
            receipt {:seq next-seq
                     :prev-cid prev-cid
                     :receipt-cid rcpt-cid
                     :circle-id circle-id
                     :member-pubkey member-pubkey
                     :amount amt
                     :memo (str memo)}
            st' (-> st
                    (update-in [:circles circle-id :allocation] - amt)
                    (update-in [:member-memories member-pubkey :balance] + amt)
                    (assoc-in [:member-memories member-pubkey :head-cid] rcpt-cid)
                    (update-in [:member-memories member-pubkey :receipts] conj receipt)
                    (update :transition-count inc))]
        [st' [[:emit-member-receipt {:member-pubkey member-pubkey :receipt-cid rcpt-cid :amount amt}]]]))))

(defn- handle-dissolve-circle
  [st {:keys [circle-id signers]}]
  (let [circle (get-in st [:circles circle-id])]
    (cond
      (nil? circle)
      (reject st :unknown-circle {:circle-id circle-id})

      (seq (active-children st circle-id))
      (reject st :circle-has-active-subcircles {:circle-id circle-id})

      (not (multisig-valid? (:members circle) (:threshold circle) signers))
      (reject st :insufficient-circle-multisig {:circle-id circle-id :required (:threshold circle)})

      :else
      (let [returned (long (:allocation circle))
            parent-id (:parent-id circle)
            st' (if (= :genesis parent-id)
                  (-> st
                      (update :unallocated-ech + returned)
                      (update :circles dissoc circle-id)
                      (update :transition-count inc))
                  (-> st
                      (update-in [:circles parent-id :allocation] + returned)
                      (update :circles dissoc circle-id)
                      (update :transition-count inc)))]
        [st' [[:emit-circle-dissolved {:circle-id circle-id :parent-id parent-id :returned-ech returned}]]]))))

(defn transition
  "Single-authority pure reducer `(transition st event) -> [new-st effects]`.
   Enforces ECH conservation, Pointer-style multisig, and two-mode visibility on EVERY step;
   all rejected transitions emit a counted `:diagnose` effect (zero silent failures)."
  [st event]
  (case (:type event)
    :spawn-circle (handle-spawn-circle st event)
    :update-circle-state (handle-update-circle-state st event)
    :disburse-to-member (handle-disburse-to-member st event)
    :dissolve-circle (handle-dissolve-circle st event)
    (reject st :unknown-event-type {:type (:type event)})))

;; ---------------------------------------------------------------------------
;; Work Order (1): Digital-Now vs Prioritized Human-Anchor Task Map
;; ---------------------------------------------------------------------------

(defn digital-vs-human-anchor-map
  "Maps Bitecho's structure against the Genesis/Circle model into:
   - `:digital-now`: everything buildable immediately on existing Bitecho/Hive primitives;
   - `:human-anchor-prioritized`: P0-P2 prioritized list of tasks requiring a human anchor
     member or legacy-system interaction (filings, counsel, banking, registries)."
  []
  {:spec-cid GENESIS-SPEC-CID
   :digital-now
   [{:id :D1 :name "Genesis State Root & Pre-Mint Allocation Tree"
     :primitive "bitecho.hive.genesis / Pointer-style multisig"}
    {:id :D2 :name "Circle Descriptors & Two-Mode Visibility (:public | :shared-encrypted)"
     :primitive "Bitecho artifact pointers + shared group key"}
    {:id :D3 :name "Private Encrypted Member Root Memory & Hash-Chained Receipts"
     :primitive "Bitecho per-pubkey encrypted append-only chain"}
    {:id :D4 :name "Standing Extension Circles (:nexus-devops, :vector-einsum-hive, :nexus-implementation)"
     :primitive "D41 consent + Top-Loop staged rollout"}
    {:id :D5 :name "Multi-Genesis & Run-4 Monte Carlo Future Fork Simulator"
     :primitive "bitecho.hive.genesis/run-monte-carlo-futures"}]
   :human-anchor-prioritized
   [{:id :H1 :priority :P0 :area :legal-entity
     :task "Adopt legal-entity charter / bylaws mirroring the Genesis State Root CID and circle delegation authority."
     :requires :human-anchor-and-counsel}
    {:id :H2 :priority :P0 :area :shared-values
     :task "Real circle members ratify Shared Values document in their own words (blocks real rollout policy binding)."
     :requires :human-anchor-and-members}
    {:id :H3 :priority :P1 :area :registry-filing
     :task "Execute state/jurisdiction corporate or foundation registry filings and appoint human anchor officer(s)."
     :requires :human-anchor-and-legacy-registry}
    {:id :H4 :priority :P1 :area :banking-custody
     :task "Open fiat banking / tax-ID (EIN) accounts and sign human-custodied signing-key recovery policy."
     :requires :human-anchor-and-banking}
    {:id :H5 :priority :P2 :area :angel-instruments
     :task "Execute real-world angel/seed legal agreements mirrored to encrypted :angels-circle state CID."
     :requires :human-anchor-and-counsel}]})

;; ---------------------------------------------------------------------------
;; Work Orders (2)-(4): Three Distinct Genesis Setups + Circle Extensions
;; ---------------------------------------------------------------------------

(def default-circle-members
  "Simulated human-anchor (Simon) + 4 digital-twin members (Axiom, Nexus, Vector, Lumen)."
  [{:pubkey "pk-simon"  :role :human-anchor :label "Simon (Human Anchor)"}
   {:pubkey "pk-axiom"  :role :digital-twin :label "Axiom (Governance/Strategy Twin)"}
   {:pubkey "pk-nexus"  :role :digital-twin :label "Nexus (Infra/DevOps/Impl Twin)"}
   {:pubkey "pk-vector" :role :digital-twin :label "Vector (Einsum/Hive/RL Twin)"}
   {:pubkey "pk-lumen"  :role :digital-twin :label "Lumen (Research/BIP Twin)"}])

(defn- apply-events
  [st events]
  (reduce (fn [s ev] (first (transition s ev))) st events))

(defn build-setup-foundation-builders
  "Genesis Setup A: Foundation/Builders (100M ECH) with Bitecho Circles 1-6 mapped
   plus the 3 standing extension circles (:nexus-devops, :vector-einsum-hive, :nexus-implementation)."
  []
  (let [all-pks #{"pk-simon" "pk-axiom" "pk-nexus" "pk-vector" "pk-lumen"}
        st0 (init-genesis {:entity-id "setup-a-foundation-builders"
                           :pre-mint-total 100000000
                           :members default-circle-members})]
    (apply-events
     st0
     [{:type :spawn-circle :circle-id :stewards-council :parent-id :genesis
       :description "Bitecho Circle 1: Stewards & Governance Council"
       :allocation 20000000 :members all-pks :threshold 3 :visibility :public :signers #{"pk-simon"}}
      {:type :spawn-circle :circle-id :protocol-research :parent-id :genesis
       :description "Bitecho Circle 2: Protocol & BIP Research (Lumen + Axiom)"
       :allocation 15000000 :members #{"pk-simon" "pk-lumen" "pk-axiom"} :threshold 2
       :visibility :public :signers #{"pk-simon"}}
      {:type :spawn-circle :circle-id :nexus-devops :parent-id :genesis
       :description "Extension Circle: Nexus DevOps (Gentoo fleet current + staged rollouts)"
       :allocation 15000000 :members #{"pk-simon" "pk-nexus" "pk-vector"} :threshold 2
       :visibility :public :signers #{"pk-simon"}}
      {:type :spawn-circle :circle-id :vector-einsum-hive :parent-id :genesis
       :description "Extension Circle: Vector clj-einsum + Hive (model support + faster kernels)"
       :allocation 18000000 :members #{"pk-simon" "pk-vector" "pk-nexus"} :threshold 2
       :visibility :public :signers #{"pk-simon"}}
      {:type :spawn-circle :circle-id :nexus-implementation :parent-id :genesis
       :description "Extension Circle: Nexus Implementation Circle (standing twin-pattern impl)"
       :allocation 17000000 :members #{"pk-simon" "pk-nexus" "pk-lumen"} :threshold 2
       :visibility :public :signers #{"pk-simon"}}
      {:type :spawn-circle :circle-id :treasury-reserve :parent-id :genesis
       :description "Bitecho Circle 6: Shared-Encrypted Treasury & Legal Anchor"
       :allocation 10000000 :members #{"pk-simon" "pk-axiom" "pk-nexus"} :threshold 2
       :visibility :shared-encrypted :shared-key-holders #{"pk-simon" "pk-axiom" "pk-nexus"}
       :signers #{"pk-simon"}}])))

(defn build-setup-seed-angels
  "Genesis Setup B: Lean Seed/Angels (50M ECH) with encrypted :angels-circle and
   the 3 standing extension circles."
  []
  (let [st0 (init-genesis {:entity-id "setup-b-seed-angels"
                           :pre-mint-total 50000000
                           :members default-circle-members})]
    (apply-events
     st0
     [{:type :spawn-circle :circle-id :angels-circle :parent-id :genesis
       :description "Encrypted Seed/Angels Circle (shared-key among all circle members)"
       :allocation 12000000 :members #{"pk-simon" "pk-axiom"} :threshold 2
       :visibility :shared-encrypted :shared-key-holders #{"pk-simon" "pk-axiom"}
       :signers #{"pk-simon"}}
      {:type :spawn-circle :circle-id :nexus-devops :parent-id :genesis
       :description "Extension Circle: Nexus DevOps (Gentoo fleet + rollouts)"
       :allocation 10000000 :members #{"pk-simon" "pk-nexus"} :threshold 2
       :visibility :public :signers #{"pk-simon"}}
      {:type :spawn-circle :circle-id :vector-einsum-hive :parent-id :genesis
       :description "Extension Circle: Vector clj-einsum + Hive (model support + kernels)"
       :allocation 14000000 :members #{"pk-simon" "pk-vector"} :threshold 2
       :visibility :public :signers #{"pk-simon"}}
      {:type :spawn-circle :circle-id :nexus-implementation :parent-id :genesis
       :description "Extension Circle: Nexus Implementation Circle"
       :allocation 10000000 :members #{"pk-simon" "pk-nexus" "pk-lumen"} :threshold 2
       :visibility :public :signers #{"pk-simon"}}])))

(defn build-setup-stress-reorg
  "Genesis Setup C: High-Resilience Reorg/Subcircle Setup (25M ECH) with nested
   subcircles and 3-of-5 multisig quorum across extension circles."
  []
  (let [core-pks #{"pk-simon" "pk-axiom" "pk-nexus" "pk-vector" "pk-lumen"}
        st0 (init-genesis {:entity-id "setup-c-stress-reorg"
                           :pre-mint-total 25000000
                           :members default-circle-members})]
    (apply-events
     st0
     [{:type :spawn-circle :circle-id :root-ops :parent-id :genesis
       :description "Root Operations Umbrella"
       :allocation 15000000 :members core-pks :threshold 3 :visibility :public :signers #{"pk-simon"}}
      {:type :spawn-circle :circle-id :nexus-devops :parent-id :root-ops
       :description "Subcircle: Nexus DevOps"
       :allocation 5000000 :members #{"pk-simon" "pk-nexus" "pk-vector"} :threshold 2
       :visibility :public :signers #{"pk-simon" "pk-axiom" "pk-nexus"}}
      {:type :spawn-circle :circle-id :vector-einsum-hive :parent-id :root-ops
       :description "Subcircle: Vector clj-einsum + Hive"
       :allocation 5000000 :members #{"pk-simon" "pk-vector" "pk-lumen"} :threshold 2
       :visibility :public :signers #{"pk-simon" "pk-axiom" "pk-vector"}}
      {:type :spawn-circle :circle-id :nexus-implementation :parent-id :root-ops
       :description "Subcircle: Nexus Implementation"
       :allocation 5000000 :members #{"pk-simon" "pk-nexus" "pk-lumen"} :threshold 2
       :visibility :public :signers #{"pk-simon" "pk-axiom" "pk-lumen"}}])))

;; ---------------------------------------------------------------------------
;; Run-4: Fork Simulated System + Monte Carlo Future Selection
;; ---------------------------------------------------------------------------

(defn- next-rand
  "Pure portable LCG step returning `[next-seed unit-float]` in [0.0, 1.0)."
  [seed]
  (let [s0 (mod (Math/abs (long seed)) 2147483648)
        s (long (mod (+ (* 1103515245 s0) 12345) 2147483648))
        u (/ (double s) 2147483648.0)]
    [s u]))

(defn default-run4-forks
  "Three candidate strategic futures forked from distinct genesis setups with
   realistic simulated human-anchor availability and digital-twin fault profiles."
  []
  [{:fork-id :fork-a-foundation-balanced
    :label "Foundation/Builders (100M ECH, 2-of-3 extension circles, 5M unallocated buffer)"
    :setup-fn build-setup-foundation-builders
    :anchor-availability 0.92
    :twin-fault-rate 0.08
    :horizon-steps 12}
   {:fork-id :fork-b-seed-angels-lean
    :label "Lean Seed/Angels (50M ECH, 2-of-2 human-anchor-gated extension circles)"
    :setup-fn build-setup-seed-angels
    :anchor-availability 0.85
    :twin-fault-rate 0.12
    :horizon-steps 12}
   {:fork-id :fork-c-stress-reorg-hardened
    :label "Hardened Nested Subcircles (25M ECH, 3-of-5 root + 2-of-3 subcircles, 10M reserve)"
    :setup-fn build-setup-stress-reorg
    :anchor-availability 0.90
    :twin-fault-rate 0.10
    :horizon-steps 12}])

(defn- simulate-single-future-trial
  [fork trial-seed]
  (let [st0 ((:setup-fn fork))
        anchor-p (double (:anchor-availability fork))
        fault-p (double (:twin-fault-rate fork))
        steps (long (:horizon-steps fork))
        target-circles [:nexus-devops :vector-einsum-hive :nexus-implementation]]
    (loop [step-idx 0
           s trial-seed
           st st0
           conserved? (conservation-holds? st0)
           milestones-hit 0
           faults-injected 0]
      (if (>= step-idx steps)
        (let [success? (and conserved?
                            (>= milestones-hit (long (Math/ceil (* 0.75 (double steps))))))]
          {:success? success?
           :conserved? conserved?
           :milestones-hit milestones-hit
           :faults-injected faults-injected
           :rejected-ops (get-in st [:diagnostics :rejected-ops])
           :final-root-cid (state-root-cid st)})
        (let [[s1 u-anchor] (next-rand s)
              [s2 u-fault] (next-rand s1)
              cid (nth target-circles (mod step-idx (count target-circles)))
              circle (get-in st [:circles cid])
              anchor-online? (< u-anchor anchor-p)
              fault? (< u-fault fault-p)
              ;; Build signers based on simulated human-anchor + digital-twin availability
              twin-members (vec (disj (:members circle) "pk-simon"))
              signers (cond
                        fault? #{} ;; injected twin/network fault -> insufficient multisig
                        anchor-online? (set (cons "pk-simon" (take 1 twin-members)))
                        :else (set (take 2 twin-members)))
              recipient (or (first twin-members) "pk-simon")
              [st' effects] (transition st {:type :disburse-to-member
                                            :circle-id cid
                                            :member-pubkey recipient
                                            :amount 100000
                                            :memo (str "mc-step-" step-idx)
                                            :signers signers})
              step-ok? (some #(= :emit-member-receipt (first %)) effects)
              still-conserved? (and conserved? (conservation-holds? st'))]
          (recur (inc step-idx)
                 s2
                 st'
                 still-conserved?
                 (if step-ok? (inc milestones-hit) milestones-hit)
                 (if fault? (inc faults-injected) faults-injected)))))))

(defn run-monte-carlo-futures
  "Run-4 engine: forks the simulated genesis system across `forks`, runs `trials-per-fork`
   deterministic seeded Monte Carlo rollouts per fork, verifies ECH conservation across
   every trial, computes empirical path likelihood `P(target-success | fork)`, and
   selects the data-driven future with the highest measured path likelihood."
  [{:keys [seed trials-per-fork forks]
    :or {seed 20261121
         trials-per-fork 100
         forks (default-run4-forks)}}]
  (let [n (long trials-per-fork)
        fork-results
        (mapv (fn [idx fork]
                (let [trials (mapv (fn [t]
                                     (let [t-seed (+ (* (long seed) 1009) (* (long idx) 10007) (long t))]
                                       (simulate-single-future-trial fork t-seed)))
                                   (range n))
                      successes (count (filter :success? trials))
                      all-conserved? (every? :conserved? trials)
                      path-p (/ (double successes) (double n))
                      mean-milestones (/ (double (reduce + 0 (map :milestones-hit trials))) (double n))
                      total-rejected (reduce + 0 (map :rejected-ops trials))]
                  {:fork-id (:fork-id fork)
                   :label (:label fork)
                   :trials n
                   :successes successes
                   :path-likelihood path-p
                   :mean-milestones mean-milestones
                   :total-rejected-ops total-rejected
                   :all-trials-conserved? all-conserved?}))
              (range (count forks))
              forks)
        ranked (vec (sort-by (fn [r] [(- (double (:path-likelihood r))) (name (:fork-id r))])
                             fork-results))
        selected (first ranked)
        forecast-root-cid (env/sha256-str (pr-str {:spec-cid GENESIS-SPEC-CID
                                                   :seed seed
                                                   :trials-per-fork n
                                                   :ranked ranked}))]
    {:spec-cid GENESIS-SPEC-CID
     :seed seed
     :trials-per-fork n
     :fork-results ranked
     :selected-fork selected
     :forecast-root-cid forecast-root-cid}))
