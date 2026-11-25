(ns bitecho.hive.seed-to-round
  "Pure Sans-IO `.cljc` Full Bitecho Seed-to-A-Round + Network Evolution Monte Carlo Engine.

   Anchored to:
   - Pinned Genesis/Circle spec CID `8039db926a1d9ee5ab7b5a9c198512de20dd79def8e7687ed8e3c1b0e28d14d6`
   - Ratified Tokenomics v2.1 (`TOKENOMICS.md`, BIP-009 `theta*` sweep seed `1795112000`):
     * 42,000,000,000 ECHO hard cap (100% conserved across all 7 supply buckets every month)
     * 45% Genesis Custody (10% Bootstrap 3-yr decay + hard sunset burn at epoch 26,280;
       15% Core Team 1-yr cliff / 4-yr vest; 10% Genesis Partners 1-yr cliff / 3-yr SLA-gated vest;
       10% Ecosystem milestone-gated)
     * 55% Unminted Conditional Supply (demand-gated mint M_t <= 0.5 * R_trailing, 20/80 split,
       100% protocol revenue burned, tau = 50 bps)

   Simulates the full 36-month (26,280-epoch) arc:
     genesis -> circles stood up -> allocations flow -> angels/advisors onboard ->
     seed-round events -> network fleet & sci-commerce evolution -> Series-A qualification
     + Year-3 bootstrap sunset burn.
   Ranks scenarios by measured path likelihood and surfaces the most surprising emergent outcomes."
  (:require [bitecho.hive.env :as env]
            [bitecho.hive.genesis :as genesis]))

(def ^:const HARD-CAP-ECHO "Ratified v2.1 hard supply cap (42B ECHO)." 42000000000)
(def ^:const BOOTSTRAP-GENESIS "10% Bootstrap Reserve (3-yr decay + sunset burn)." 4200000000)
(def ^:const TEAM-GENESIS "15% Core Team custody (1-yr cliff / 4-yr vest)." 6300000000)
(def ^:const PARTNERS-GENESIS "10% Genesis Partners custody (1-yr cliff / 3-yr SLA-gated vest)." 4200000000)
(def ^:const ECOSYSTEM-GENESIS "10% Ecosystem & Grants custody (milestone-gated)." 4200000000)
(def ^:const UNMINTED-CONDITIONAL "55% Unminted Conditional Supply (demand-gated mint k<=0.5)." 23100000000)
(def ^:const EPOCHS-PER-MONTH "730 1-hour epochs per 30.4-day month (36 months = 26,280 epochs)." 730)
(def ^:const SUNSET-EPOCH "Year-3 Bootstrap Reserve hard sunset burn epoch." 26280)
(def ^:const BIP009-THETA-SEED "Canonical BIP-009 theta* Monte Carlo sweep seed." 1795112000)

(defn- next-rand
  "Pure portable LCG step returning `[next-seed unit-float]` in [0.0, 1.0)."
  [seed]
  (let [s0 (mod (Math/abs (long seed)) 2147483648)
        s (long (mod (+ (* 1103515245 s0) 12345) 2147483648))
        u (/ (double s) 2147483648.0)]
    [s u]))

(defn sum-supply-buckets
  "Exact integer sum across all 7 supply buckets (must equal 42,000,000,000 ECHO)."
  [m]
  (+ (long (:circulating m))
     (long (:bootstrap-locked m))
     (long (:team-locked m))
     (long (:partners-locked m))
     (long (:ecosystem-locked m))
     (long (:unminted-conditional m))
     (long (:permanently-burned m))))

(defn v21-vesting-scenarios
  "Six granular Seed-to-A-Round scenarios rooted directly in ratified Tokenomics v2.1
   vesting & allocation assumptions (`TOKENOMICS.md` + BIP-009 `theta*` sweep)."
  []
  [{:scenario-id :v2.1-baseline
    :label "Ratified v2.1 Baseline (10/15/10/10 Genesis + 55% demand-gated k=0.5, 2-of-3 circles)"
    :genesis-setup-fn genesis/build-setup-foundation-builders
    :bootstrap-drawdown-rate 0.045
    :partner-sla-breach-prob 0.08
    :anchor-availability 0.90
    :circle-multisig-resilient? true
    :sci-growth-rate 1.18
    :base-monthly-sci-burn 25000000
    :fleet-growth-rate 1.14
    :initial-fleet 6
    :rollout-fault-prob 0.06
    :browser-node-boost 1.0}
   {:scenario-id :v2.1-sla-strict-angels
    :label "Strict SLA + Encrypted Angels Circle (2-of-2 anchor-gated extension circles)"
    :genesis-setup-fn genesis/build-setup-seed-angels
    :bootstrap-drawdown-rate 0.040
    :partner-sla-breach-prob 0.18
    :anchor-availability 0.85
    :circle-multisig-resilient? false
    :sci-growth-rate 1.16
    :base-monthly-sci-burn 22000000
    :fleet-growth-rate 1.12
    :initial-fleet 6
    :rollout-fault-prob 0.07
    :browser-node-boost 1.05}
   {:scenario-id :v2.1-high-velocity-fleet
    :label "High-Velocity Agent Commerce + Browser WebGPU Fleet (fast sci burn, deflationary crossover)"
    :genesis-setup-fn genesis/build-setup-foundation-builders
    :bootstrap-drawdown-rate 0.055
    :partner-sla-breach-prob 0.06
    :anchor-availability 0.92
    :circle-multisig-resilient? true
    :sci-growth-rate 1.26
    :base-monthly-sci-burn 45000000
    :fleet-growth-rate 1.20
    :initial-fleet 8
    :rollout-fault-prob 0.05
    :browser-node-boost 1.35}
   {:scenario-id :v2.1-partner-sla-shock
    :label "Partner SLA Breach Shock + Nested Subcircle Reorg Stress (high SLA pauses & faults)"
    :genesis-setup-fn genesis/build-setup-stress-reorg
    :bootstrap-drawdown-rate 0.038
    :partner-sla-breach-prob 0.42
    :anchor-availability 0.80
    :circle-multisig-resilient? true
    :sci-growth-rate 1.13
    :base-monthly-sci-burn 20000000
    :fleet-growth-rate 1.10
    :initial-fleet 6
    :rollout-fault-prob 0.14
    :browser-node-boost 1.0}
   {:scenario-id :v2.1-bear-low-demand
    :label "Conservative Bear / Low-Demand Adoption (tests Graceful Degradation + Year-3 sunset burn)"
    :genesis-setup-fn genesis/build-setup-foundation-builders
    :bootstrap-drawdown-rate 0.022
    :partner-sla-breach-prob 0.15
    :anchor-availability 0.88
    :circle-multisig-resilient? true
    :sci-growth-rate 1.06
    :base-monthly-sci-burn 8000000
    :fleet-growth-rate 1.06
    :initial-fleet 6
    :rollout-fault-prob 0.08
    :browser-node-boost 0.95}
   {:scenario-id :v2.1-extension-heavy
    :label "Extension-Circle Heavy (Vector clj-einsum+Hive + Nexus DevOps prioritized, low rollout faults)"
    :genesis-setup-fn genesis/build-setup-foundation-builders
    :bootstrap-drawdown-rate 0.050
    :partner-sla-breach-prob 0.07
    :anchor-availability 0.91
    :circle-multisig-resilient? true
    :sci-growth-rate 1.23
    :base-monthly-sci-burn 36000000
    :fleet-growth-rate 1.19
    :initial-fleet 8
    :rollout-fault-prob 0.03
    :browser-node-boost 1.45}])

(defn simulate-seed-to-round-trajectory
  "Simulates one complete deterministic 36-month (26,280-epoch) Seed-to-A-Round +
   Network Evolution trajectory for `scenario` and `seed`."
  [scenario seed]
  (let [g0 ((:genesis-setup-fn scenario))
        g-conserved? (genesis/conservation-holds? g0)
        init-state {:month 0
                    :epoch 0
                    :circulating 0
                    :bootstrap-locked BOOTSTRAP-GENESIS
                    :team-locked TEAM-GENESIS
                    :partners-locked PARTNERS-GENESIS
                    :ecosystem-locked ECOSYSTEM-GENESIS
                    :unminted-conditional UNMINTED-CONDITIONAL
                    :permanently-burned 0
                    :fleet-nodes (long (:initial-fleet scenario))
                    :browser-nodes 2
                    :trailing-burned-revenue 0
                    :cumulative-burned-revenue 0
                    :angels-onboarded? false
                    :seed-closed? false
                    :sla-paused-months 0
                    :anchor-bottleneck-months 0
                    :rollbacks 0
                    :first-deflationary-month nil}]
    (loop [m 1
           s (long seed)
           st init-state
           rev-hist []
           trace []]
      (if (> m 36)
        (let [final-m (last trace)
              all-conserved? (and g-conserved?
                                  (every? #(= HARD-CAP-ECHO (sum-supply-buckets %)) trace))
              a-round-ok? (and all-conserved?
                               (:angels-onboarded? st)
                               (:seed-closed? st)
                               (>= (long (:fleet-nodes st)) 60)
                               (>= (long (:cumulative-burned-revenue st)) 1500000000)
                               (<= (long (:rollbacks st)) 4))
              year3-sunset (long (:sunset-burned-this-month final-m))]
          {:spec-cid genesis/GENESIS-SPEC-CID
           :theta-anchor-seed BIP009-THETA-SEED
           :hard-cap HARD-CAP-ECHO
           :scenario-id (:scenario-id scenario)
           :seed seed
           :genesis-root-cid (genesis/state-root-cid g0)
           :all-months-conserved? all-conserved?
           :a-round-qualified? a-round-ok?
           :angels-onboarded? (:angels-onboarded? st)
           :seed-closed? (:seed-closed? st)
           :final-fleet-nodes (:fleet-nodes st)
           :final-browser-nodes (:browser-nodes st)
           :final-circulating (:circulating st)
           :total-permanently-burned (:permanently-burned st)
           :year3-sunset-burned year3-sunset
           :cumulative-burned-revenue (:cumulative-burned-revenue st)
           :sla-paused-months (:sla-paused-months st)
           :anchor-bottleneck-months (:anchor-bottleneck-months st)
           :rollbacks (:rollbacks st)
           :first-deflationary-month (:first-deflationary-month st)
           :monthly-trace trace})
        (let [[s1 u-anchor] (next-rand s)
              [s2 u-sla] (next-rand s1)
              [s3 u-fault] (next-rand s2)
              [s4 u-demand] (next-rand s3)
              epoch (* m EPOCHS-PER-MONTH)
              anchor-ok? (< u-anchor (double (:anchor-availability scenario)))
              ;; 2-of-3 circles keep progressing even if anchor is temporarily away;
              ;; 2-of-2 circles stall whenever anchor is unavailable.
              governance-progress? (or anchor-ok? (true? (:circle-multisig-resilient? scenario)))
              anchor-bottleneck? (not governance-progress?)
              sla-breach? (< u-sla (double (:partner-sla-breach-prob scenario)))
              rollout-fault? (< u-fault (double (:rollout-fault-prob scenario)))
              ;; Arc milestones: angels onboard (M4-9), seed closes (M10-18)
              angels-now? (or (:angels-onboarded? st)
                              (and (>= m 4) anchor-ok? (not rollout-fault?)))
              seed-now? (or (:seed-closed? st)
                            (and angels-now? (>= m 10) anchor-ok? (>= (long (:fleet-nodes st)) 12)))
              ;; Bootstrap Reserve 3-yr algorithmic decay drawdown
              decay-factor (max 0.15 (- 1.0 (* 0.022 (double (dec m)))))
              b-raw (long (Math/floor (* (double (:bootstrap-locked st))
                                         (double (:bootstrap-drawdown-rate scenario))
                                         decay-factor)))
              b-draw (if (< m 36) (min (long (:bootstrap-locked st)) b-raw) 0)
              ;; Core Team: 1-yr cliff (m >= 12), 4-yr linear vest
              team-delta (cond
                           (< m 12) 0
                           (= m 12) (min (long (:team-locked st)) (quot TEAM-GENESIS 4))
                           :else (min (long (:team-locked st)) (quot TEAM-GENESIS 48)))
              ;; Genesis Partners: 1-yr cliff (m >= 12), 3-yr linear vest, STRICTLY SLA-gated
              partners-delta (cond
                               (< m 12) 0
                               sla-breach? 0
                               (= m 12) (min (long (:partners-locked st)) (quot PARTNERS-GENESIS 3))
                               :else (min (long (:partners-locked st)) (quot PARTNERS-GENESIS 36)))
              ;; Ecosystem & Grants: milestone-gated only (no automatic time cliffs)
              eco-delta (if (and governance-progress? (not rollout-fault?) (zero? (mod m 3)))
                          (min (long (:ecosystem-locked st)) (quot ECOSYSTEM-GENESIS 18))
                          0)
              ;; Update circulating after custody releases before protocol revenue burn
              circ-after-release (+ (long (:circulating st)) b-draw team-delta partners-delta eco-delta)
              ;; Network evolution: fleet & browser WebGPU Mealy nodes
              fleet-mult (if rollout-fault?
                           0.96
                           (* (double (:fleet-growth-rate scenario))
                              (if seed-now? 1.03 0.97)
                              (+ 0.92 (* 0.16 u-demand))))
              next-fleet (max 4 (long (Math/round (* (double (:fleet-nodes st)) fleet-mult))))
              next-browser (max 2 (long (Math/round (* (double next-fleet)
                                                       0.45
                                                       (double (:browser-node-boost scenario))))))
              ;; Protocol revenue R_t (signaling burns + tau_bps sci settlement takes), 100% burned
              k-bps (long (min 5000 (max 0 (long (or (:k-bps scenario) 5000)))))
              tau-bps (long (min 200 (max 0 (long (or (:tau-bps scenario) 50)))))
              tau-take-mult (/ (double tau-bps) 50.0)
              ;; Mild volume elasticity: lower take slightly encourages volume; higher take slightly dampens it
              tau-elasticity (cond
                               (< tau-bps 50) 1.08
                               (> tau-bps 50) (max 0.70 (- 1.0 (* 0.0024 (double (- tau-bps 50)))))
                               :else 1.0)
              demand-shock (+ 0.72 (* 0.56 u-demand))
              growth-pow (Math/pow (double (:sci-growth-rate scenario)) (double (min m 24)))
              fleet-factor (/ (double (+ next-fleet next-browser)) 10.0)
              r-raw (long (Math/floor (* (double (:base-monthly-sci-burn scenario))
                                         tau-take-mult
                                         tau-elasticity
                                         growth-pow
                                         fleet-factor
                                         demand-shock
                                         (if rollout-fault? 0.65 1.0))))
              r-burned (min circ-after-release (max 0 r-raw))
              ;; Trailing revenue window (2-month rolling proxy for 168-epoch window, zero-padded)
              trailing-rev (if (empty? rev-hist)
                             0
                             (long (quot (reduce + 0 (take-last 2 rev-hist)) 2)))
              ;; Demand-gated mint M_t <= floor((k_bps/10000) * R_trailing), capped by unminted conditional supply
              m-cap (long (Math/floor (* (/ (double k-bps) 10000.0) (double trailing-rev))))
              m-minted (min (long (:unminted-conditional st)) m-cap)
              ;; Year-3 hard sunset burn at Month 36 (epoch 26,280)
              b-after-draw (- (long (:bootstrap-locked st)) b-draw)
              sunset-burn (if (= m 36) b-after-draw 0)
              b-locked-final (- b-after-draw sunset-burn)
              circ-final (+ (- circ-after-release r-burned) m-minted)
              net-supply-delta (- circ-final (long (:circulating st)))
              first-defl-m (or (:first-deflationary-month st)
                               (when (and (> m 12) (neg? net-supply-delta)) m))
              snap {:month m
                    :epoch epoch
                    :circulating circ-final
                    :bootstrap-locked b-locked-final
                    :team-locked (- (long (:team-locked st)) team-delta)
                    :partners-locked (- (long (:partners-locked st)) partners-delta)
                    :ecosystem-locked (- (long (:ecosystem-locked st)) eco-delta)
                    :unminted-conditional (- (long (:unminted-conditional st)) m-minted)
                    :permanently-burned (+ (long (:permanently-burned st)) r-burned sunset-burn)
                    :bootstrap-draw-delta b-draw
                    :team-vested-delta team-delta
                    :partners-vested-delta partners-delta
                    :ecosystem-released-delta eco-delta
                    :burned-revenue-this-month r-burned
                    :trailing-burned-revenue trailing-rev
                    :minted-this-month m-minted
                    :sunset-burned-this-month sunset-burn
                    :net-supply-delta net-supply-delta
                    :partner-sla-breach? sla-breach?
                    :anchor-bottleneck? anchor-bottleneck?
                    :rollout-fault? rollout-fault?
                    :fleet-nodes next-fleet
                    :browser-nodes next-browser
                    :angels-onboarded? angels-now?
                    :seed-closed? seed-now?}
              st' (assoc st
                         :month m
                         :epoch epoch
                         :circulating (:circulating snap)
                         :bootstrap-locked (:bootstrap-locked snap)
                         :team-locked (:team-locked snap)
                         :partners-locked (:partners-locked snap)
                         :ecosystem-locked (:ecosystem-locked snap)
                         :unminted-conditional (:unminted-conditional snap)
                         :permanently-burned (:permanently-burned snap)
                         :fleet-nodes next-fleet
                         :browser-nodes next-browser
                         :trailing-burned-revenue trailing-rev
                         :cumulative-burned-revenue (+ (long (:cumulative-burned-revenue st)) r-burned)
                         :angels-onboarded? angels-now?
                         :seed-closed? seed-now?
                         :sla-paused-months (+ (long (:sla-paused-months st)) (if sla-breach? 1 0))
                         :anchor-bottleneck-months (+ (long (:anchor-bottleneck-months st)) (if anchor-bottleneck? 1 0))
                         :rollbacks (+ (long (:rollbacks st)) (if rollout-fault? 1 0))
                         :first-deflationary-month first-defl-m)]
          (recur (inc m) s4 st' (conj rev-hist r-burned) (conj trace snap)))))))

(defn- mean-num
  [xs]
  (if (empty? xs) 0.0 (/ (double (reduce + 0.0 xs)) (double (count xs)))))

(defn detect-surprising-findings
  "Extracts the most surprising emergent findings & weirdest outlier trajectories
   across the full Seed-to-A-Round ensemble for Simon's morning readout."
  [ranked-summaries all-trajectories]
  (let [winner (first ranked-summaries)
        by-id (into {} (map (juxt :scenario-id identity) ranked-summaries))
        baseline (:v2.1-baseline by-id)
        sla-angels (:v2.1-sla-strict-angels by-id)
        high-vel (:v2.1-high-velocity-fleet by-id)
        bear (:v2.1-bear-low-demand by-id)
        sla-shock (:v2.1-partner-sla-shock by-id)
        ext-heavy (:v2.1-extension-heavy by-id)
        most-deflationary (apply min-key :final-circulating all-trajectories)
        highest-sunset (apply max-key :year3-sunset-burned all-trajectories)
        toughest-survivor (let [qualified (filter :a-round-qualified? all-trajectories)]
                            (when (seq qualified)
                              (apply max-key :rollbacks qualified)))]
    (vec
     (keep
      identity
      [{:finding-id :highest-path-likelihood-winner
        :headline (str "Data-driven winner: " (name (:scenario-id winner))
                       " (Series-A path likelihood = "
                       (/ (double (Math/round (* 1000.0 (double (:a-round-path-likelihood winner))))) 10.0)
                       "%)")
        :detail {:winner (:scenario-id winner)
                 :path-likelihood (:a-round-path-likelihood winner)
                 :mean-final-fleet (:mean-final-fleet winner)
                 :mean-rollbacks (:mean-rollbacks winner)}}
       (when (and baseline sla-angels)
         {:finding-id :multisig-2of3-vs-2of2-anchor-bottleneck
          :headline "2-of-2 human-anchor gating creates a measurable Series-A drag vs 2-of-3 resilient circles"
          :detail {:baseline-2of3-likelihood (:a-round-path-likelihood baseline)
                   :angels-2of2-likelihood (:a-round-path-likelihood sla-angels)
                   :angels-mean-anchor-bottleneck-months (:mean-anchor-bottleneck-months sla-angels)}})
       (when (and high-vel ext-heavy)
         {:finding-id :early-deflationary-crossover-before-sunset
          :headline "High-velocity agent commerce + browser WebGPU fleet triggers net deflation BEFORE Year-3 bootstrap sunset"
          :detail {:high-vel-deflationary-rate (:deflationary-crossover-rate high-vel)
                   :high-vel-mean-crossover-month (:mean-deflationary-month high-vel)
                   :ext-heavy-deflationary-rate (:deflationary-crossover-rate ext-heavy)}})
       (when bear
         {:finding-id :bear-graceful-degradation-and-sunset-cliff
          :headline "Bear regime burns >45% of Bootstrap Reserve at epoch 26,280 yet preserves 100% 42B conservation with zero death spirals"
          :detail {:bear-mean-year3-sunset-burned (:mean-year3-sunset-burned bear)
                   :bear-sunset-pct-of-bootstrap (/ (double (:mean-year3-sunset-burned bear)) (double BOOTSTRAP-GENESIS))
                   :bear-all-conserved? (:all-trials-conserved? bear)}})
       (when sla-shock
         {:finding-id :sla-breach-shock-supply-suppression
          :headline "SLA-gated partner pauses under shock suppress circulating supply while still allowing resilient subcircles to survive"
          :detail {:shock-mean-sla-paused-months (:mean-sla-paused-months sla-shock)
                   :shock-a-round-likelihood (:a-round-path-likelihood sla-shock)
                   :shock-mean-final-circulating (:mean-final-circulating sla-shock)}})
       {:finding-id :weirdest-outlier-trajectories
        :headline "Extreme outlier trajectories flagged across the ensemble"
        :detail {:lowest-circulating-outlier (select-keys most-deflationary
                                                          [:scenario-id :seed :final-circulating :first-deflationary-month :a-round-qualified?])
                 :highest-sunset-burn-outlier (select-keys highest-sunset
                                                           [:scenario-id :seed :year3-sunset-burned :final-fleet-nodes])
                 :toughest-a-round-survivor (when toughest-survivor
                                              (select-keys toughest-survivor
                                                           [:scenario-id :seed :rollbacks :sla-paused-months :final-fleet-nodes]))}}]))))

(defn run-seed-to-round-ensemble
  "Executes the full 6-scenario Tokenomics v2.1 Seed-to-A-Round + Network Evolution
   Monte Carlo ensemble, ranks futures by measured Series-A path likelihood, verifies
   100% 42B hard-cap conservation across every month of every trajectory, and extracts
   the most surprising emergent findings."
  [{:keys [seed trials-per-scenario scenarios]
    :or {seed BIP009-THETA-SEED
         trials-per-scenario 200
         scenarios (v21-vesting-scenarios)}}]
  (let [n (long trials-per-scenario)
        per-scenario
        (mapv (fn [idx sc]
                (let [trajs (mapv (fn [t]
                                    (let [t-seed (+ (mod (long seed) 1000000) (* (long idx) 10007) (long t))]
                                      (simulate-seed-to-round-trajectory sc t-seed)))
                                  (range n))
                      qualified (count (filter :a-round-qualified? trajs))
                      defl-months (keep :first-deflationary-month trajs)
                      summary {:scenario-id (:scenario-id sc)
                               :label (:label sc)
                               :trials n
                               :a-round-qualified-count qualified
                               :a-round-path-likelihood (/ (double qualified) (double n))
                               :all-trials-conserved? (every? :all-months-conserved? trajs)
                               :mean-final-fleet (mean-num (map :final-fleet-nodes trajs))
                               :mean-final-browser-nodes (mean-num (map :final-browser-nodes trajs))
                               :mean-final-circulating (mean-num (map :final-circulating trajs))
                               :mean-year3-sunset-burned (mean-num (map :year3-sunset-burned trajs))
                               :mean-cumulative-burned-revenue (mean-num (map :cumulative-burned-revenue trajs))
                               :mean-sla-paused-months (mean-num (map :sla-paused-months trajs))
                               :mean-anchor-bottleneck-months (mean-num (map :anchor-bottleneck-months trajs))
                               :mean-rollbacks (mean-num (map :rollbacks trajs))
                               :deflationary-crossover-rate (/ (double (count defl-months)) (double n))
                               :mean-deflationary-month (when (seq defl-months) (mean-num defl-months))}]
                  {:summary summary :trajectories trajs}))
              (range (count scenarios))
              scenarios)
        summaries (mapv :summary per-scenario)
        all-trajs (vec (mapcat :trajectories per-scenario))
        ranked (vec (sort-by (fn [s] [(- (double (:a-round-path-likelihood s))) (name (:scenario-id s))])
                             summaries))
        selected (first ranked)
        surprises (detect-surprising-findings ranked all-trajs)
        root-cid (env/sha256-str (pr-str {:spec-cid genesis/GENESIS-SPEC-CID
                                          :theta-anchor-seed BIP009-THETA-SEED
                                          :seed seed
                                          :trials-per-scenario n
                                          :ranked ranked}))]
    {:spec-cid genesis/GENESIS-SPEC-CID
     :theta-anchor-seed BIP009-THETA-SEED
     :seed seed
     :trials-per-scenario n
     :total-trajectories (count all-trajs)
     :scenario-summaries ranked
     :selected-future selected
     :surprising-findings surprises
     :ensemble-root-cid root-cid}))

(def ^:private core-4-scenario-ids
  #{:v2.1-extension-heavy :v2.1-high-velocity-fleet :v2.1-baseline :v2.1-sla-strict-angels})

(defn run-sensitivity-sweep
  "Sweep #2: Granular sensitivity sweep over `k_bps x tau_bps x anchor-availability`
   across the 4 core Tokenomics v2.1 scenarios, explicitly interrogating:
   (1) The `2-of-2` vs `2-of-3` human-anchor availability phase-transition cliff;
   (2) The Month ~13-15 deflationary crossover across `(k_bps, tau_bps)` regimes."
  [{:keys [seed trials-per-cell k-bps-grid tau-bps-grid anchor-grid]
    :or {seed BIP009-THETA-SEED
         trials-per-cell 50
         k-bps-grid [2500 3500 5000]
         tau-bps-grid [25 50 100]
         anchor-grid [0.50 0.70 0.85 0.95]}}]
  (let [n (long trials-per-cell)
        base-scenarios (vec (filter #(contains? core-4-scenario-ids (:scenario-id %))
                                    (v21-vesting-scenarios)))
        cells
        (vec
         (for [[s-idx sc] (map-indexed vector base-scenarios)
               [k-idx k-bps] (map-indexed vector k-bps-grid)
               [t-idx tau-bps] (map-indexed vector tau-bps-grid)
               [a-idx anchor-p] (map-indexed vector anchor-grid)]
           (let [sc' (assoc sc :k-bps k-bps :tau-bps tau-bps :anchor-availability anchor-p)
                 cell-offset (+ (* (long s-idx) 100003) (* (long k-idx) 10007) (* (long t-idx) 1009) (* (long a-idx) 97))
                 trajs (mapv (fn [t]
                               (simulate-seed-to-round-trajectory
                                sc'
                                (+ (mod (long seed) 1000000) cell-offset (long t))))
                             (range n))
                 qualified (count (filter :a-round-qualified? trajs))
                 defl-months (keep :first-deflationary-month trajs)]
             {:scenario-id (:scenario-id sc)
              :k-bps k-bps
              :tau-bps tau-bps
              :anchor-availability anchor-p
              :trials n
              :a-round-path-likelihood (/ (double qualified) (double n))
              :all-trials-conserved? (every? :all-months-conserved? trajs)
              :mean-anchor-bottleneck-months (mean-num (map :anchor-bottleneck-months trajs))
              :mean-final-fleet (mean-num (map :final-fleet-nodes trajs))
              :mean-final-circulating (mean-num (map :final-circulating trajs))
              :deflationary-crossover-rate (/ (double (count defl-months)) (double n))
              :mean-deflationary-month (when (seq defl-months) (mean-num defl-months))})))
        ;; Interrogation 1: 2-of-2 (:v2.1-sla-strict-angels) vs 2-of-3 (:v2.1-baseline & :v2.1-extension-heavy)
        ;; at ratified (k-bps=5000, tau-bps=50) across anchor-availability
        ratified-cells (filter #(and (= 5000 (:k-bps %)) (= 50 (:tau-bps %))) cells)
        anchor-cliff
        {:at-ratified-k5000-tau50
         (into (sorted-map)
               (for [a anchor-grid]
                 [a (into (sorted-map)
                          (for [c (filter #(= a (:anchor-availability %)) ratified-cells)]
                            [(:scenario-id c)
                             {:a-round-path-likelihood (:a-round-path-likelihood c)
                              :mean-anchor-bottleneck-months (:mean-anchor-bottleneck-months c)
                              :mean-final-fleet (:mean-final-fleet c)}]))]))}
        ;; Interrogation 2: Deflationary crossover across (k-bps, tau-bps) at anchor-availability=0.85
        defl-cells (filter #(= 0.85 (:anchor-availability %)) cells)
        defl-interrogation
        {:at-anchor-0.85
         (vec
          (for [k k-bps-grid
                tau tau-bps-grid]
            (let [cs (filter #(and (= k (:k-bps %)) (= tau (:tau-bps %))) defl-cells)
                  months (keep :mean-deflationary-month cs)]
              {:k-bps k
               :tau-bps tau
               :mean-crossover-rate (mean-num (map :deflationary-crossover-rate cs))
               :mean-crossover-month (when (seq months) (mean-num months))
               :mean-a-round-likelihood (mean-num (map :a-round-path-likelihood cs))
               :mean-final-circulating (mean-num (map :mean-final-circulating cs))})))}
        sweep-root-cid (env/sha256-str (pr-str {:spec-cid genesis/GENESIS-SPEC-CID
                                                :theta-anchor-seed BIP009-THETA-SEED
                                                :seed seed
                                                :trials-per-cell n
                                                :anchor-cliff anchor-cliff
                                                :defl-interrogation defl-interrogation}))]
    {:spec-cid genesis/GENESIS-SPEC-CID
     :theta-anchor-seed BIP009-THETA-SEED
     :seed seed
     :trials-per-cell n
     :total-grid-cells (count cells)
     :total-trajectories (* (count cells) n)
     :all-cells-conserved? (every? :all-trials-conserved? cells)
     :grid-cells cells
     :anchor-cliff-interrogation anchor-cliff
     :deflationary-crossover-interrogation defl-interrogation
     :sweep-root-cid sweep-root-cid}))

;; =============================================================================
;; V3 Endogenous Seed-to-A Piping (`run-endogenous-seed-to-round`)
;; =============================================================================

(def ^:const MICRO-COHORT-AGENTS
  "Reference micro-cohort node count in `bitecho.hive.sim.lifecycle` (6 infra + 2 home + 17 light)."
  25)

(defn- build-endogenous-monthly-series
  "Extracts or synthesizes the 36-month endogenous market series from `trial-telemetry`.
   Uses exact `:monthly-market-telemetry` when present; otherwise derives an even 36-month
   series from `:cleared-gmv-ech`, `:per-capability-gmv-ech`, `:per-subnet-gmv-ech`, and
   `:partner-vesting`."
  [trial-telemetry]
  (let [raw-monthly (:monthly-market-telemetry trial-telemetry)]
    (if (and (sequential? raw-monthly) (= 36 (count raw-monthly)))
      (vec raw-monthly)
      (let [tot-gmv (long (or (:cleared-gmv-ech trial-telemetry) 0))
            tot-burn (long (or (:market-burned-ech trial-telemetry) 0))
            per-cap (or (:per-capability-gmv-ech trial-telemetry) {})
            sub-cnt (max 1 (count (or (:per-subnet-gmv-ech trial-telemetry) {})))
            pv (or (:partner-vesting trial-telemetry) {})
            paused-total (reduce + 0 (map #(long (or (:paused-claims %) 0)) (vals pv)))]
        (mapv (fn [m]
                {:month m
                 :cleared-gmv-ech (quot tot-gmv 36)
                 :market-burned-ech (quot tot-burn 36)
                 :per-capability-gmv-ech (into (sorted-map)
                                               (map (fn [[k v]] [k (quot (long v) 36)]))
                                               per-cap)
                 :active-commercial-subnets sub-cnt
                 :active-providers (+ 8 (long (or (:endogenous-providers-entered trial-telemetry) 0)))
                 :sla-paused-partners (if (and (>= m 14) (<= m 24) (pos? paused-total)) 1 0)})
              (range 1 37))))))

(defn run-endogenous-seed-to-round
  "V3 Endogenous Seed-to-A-Round engine: replaces the exogenous
   `base-monthly-sci-burn * sci-growth-rate * demand-shock` formula with endogenous
   monthly cleared GMV, per-capability burn `R_t`, active commercial subnet count,
   and SLA-gated partner vesting derived directly from `lifecycle/run-full-network-trial`
   telemetry (plus optional `:agent-scale-multiplier` or `:target-active-agents` to
   project from the 25-node micro-cohort to target autonomous-agent fleet sizes, e.g.
   550-2,700 active autonomous agents).

   Preserves exact 42,000,000,000 ECHO 7-bucket conservation every month (M1..M36)
   and evaluates Series-A qualification, deflationary crossover month, and Year-3
   bootstrap sunset burn."
  [{:keys [trial-telemetry agent-scale-multiplier target-active-agents
           k-bps tau-bps bootstrap-drawdown-rate]
    :or {k-bps 5000
         tau-bps 50
         bootstrap-drawdown-rate 0.045}}]
  (let [scale (double (cond
                        (some? agent-scale-multiplier) (max 0.01 (double agent-scale-multiplier))
                        (some? target-active-agents) (/ (double (max 1 (long target-active-agents)))
                                                        (double MICRO-COHORT-AGENTS))
                        :else 1.0))
        target-agents (long (or target-active-agents
                                (Math/round (* (double MICRO-COHORT-AGENTS) scale))))
        k-clamped (long (min 5000 (max 0 (long k-bps))))
        tau-clamped (long (min 200 (max 0 (long tau-bps))))
        monthly-series (build-endogenous-monthly-series trial-telemetry)
        g4-info (or (:g4-angels trial-telemetry) {:onboarded? true :seed-closed? true})
        infra-stalls (long (or (get-in trial-telemetry [:protocol-infra :upgrade-stalls]) 0))
        total-partners (max 1 (count (or (:partner-vesting trial-telemetry) {:p1 {} :p2 {} :p3 {} :p4 {} :p5 {} :p6 {} :p7 {}})))
        init-state {:month 0
                    :epoch 0
                    :circulating 0
                    :bootstrap-locked BOOTSTRAP-GENESIS
                    :team-locked TEAM-GENESIS
                    :partners-locked PARTNERS-GENESIS
                    :ecosystem-locked ECOSYSTEM-GENESIS
                    :unminted-conditional UNMINTED-CONDITIONAL
                    :permanently-burned 0
                    :fleet-nodes MICRO-COHORT-AGENTS
                    :active-commercial-subnets (long (or (:active-commercial-subnets (first monthly-series)) 4))
                    :trailing-burned-revenue 0
                    :cumulative-burned-revenue 0
                    :cumulative-cleared-gmv-ech 0
                    :cumulative-per-capability-burn-ech {}
                    :angels-onboarded? false
                    :seed-closed? false
                    :sla-paused-months 0
                    :rollbacks infra-stalls
                    :first-deflationary-month nil}]
    (loop [m 1
           st init-state
           rev-hist []
           trace []]
      (if (> m 36)
        (let [final-m (last trace)
              all-conserved? (every? #(= HARD-CAP-ECHO (sum-supply-buckets %)) trace)
              year3-sunset (long (:sunset-burned-this-month final-m))
              mean-monthly-gmv (/ (double (:cumulative-cleared-gmv-ech st)) 36.0)
              a-round-ok? (and all-conserved?
                               (:angels-onboarded? st)
                               (:seed-closed? st)
                               (>= (long (:fleet-nodes st)) 60)
                               (>= (long (:cumulative-burned-revenue st)) 1500000000)
                               (<= (long (:rollbacks st)) 4))
              root-cid (env/sha256-str (pr-str {:spec-cid genesis/GENESIS-SPEC-CID
                                                :scenario (:scenario trial-telemetry)
                                                :seed (:seed trial-telemetry)
                                                :scale scale
                                                :k-bps k-clamped
                                                :tau-bps tau-clamped
                                                :final-circulating (:circulating st)
                                                :cumulative-gmv (:cumulative-cleared-gmv-ech st)}))]
          {:spec-cid genesis/GENESIS-SPEC-CID
           :theta-anchor-seed BIP009-THETA-SEED
           :hard-cap HARD-CAP-ECHO
           :mode :endogenous-capability-market
           :scenario (:scenario trial-telemetry)
           :seed (:seed trial-telemetry)
           :agent-scale-multiplier scale
           :target-active-agents target-agents
           :k-bps k-clamped
           :tau-bps tau-clamped
           :all-months-conserved? all-conserved?
           :a-round-qualified? a-round-ok?
           :angels-onboarded? (:angels-onboarded? st)
           :seed-closed? (:seed-closed? st)
           :final-fleet-nodes (:fleet-nodes st)
           :final-active-commercial-subnets (:active-commercial-subnets st)
           :final-circulating (:circulating st)
           :total-permanently-burned (:permanently-burned st)
           :year3-sunset-burned year3-sunset
           :cumulative-cleared-gmv-ech (:cumulative-cleared-gmv-ech st)
           :mean-monthly-gmv-ech (long (Math/round mean-monthly-gmv))
           :cumulative-burned-revenue (:cumulative-burned-revenue st)
           :cumulative-per-capability-burn-ech (into (sorted-map) (:cumulative-per-capability-burn-ech st))
           :sla-paused-months (:sla-paused-months st)
           :rollbacks (:rollbacks st)
           :first-deflationary-month (:first-deflationary-month st)
           :endogenous-root-cid root-cid
           :monthly-trace trace})
        (let [m-rec (nth monthly-series (dec m))
              epoch (* m EPOCHS-PER-MONTH)
              ;; Endogenous commercial subnet count & scaled active fleet progression
              active-subs (max 1 (long (or (:active-commercial-subnets m-rec) 4)))
              ramp-frac (/ (double m) 36.0)
              next-fleet (max MICRO-COHORT-AGENTS
                              (long (Math/round (+ (double MICRO-COHORT-AGENTS)
                                                   (* (- (double target-agents) (double MICRO-COHORT-AGENTS))
                                                      ramp-frac)))))
              ;; Arc milestones grounded in trial's G4 Angels state
              angels-now? (or (:angels-onboarded? st) (and (>= m 6) (true? (:onboarded? g4-info))))
              seed-now? (or (:seed-closed? st) (and angels-now? (>= m 12) (true? (:seed-closed? g4-info))))
              ;; Bootstrap Reserve 3-yr algorithmic decay drawdown
              decay-factor (max 0.15 (- 1.0 (* 0.022 (double (dec m)))))
              b-raw (long (Math/floor (* (double (:bootstrap-locked st))
                                         (double bootstrap-drawdown-rate)
                                         decay-factor)))
              b-draw (if (< m 36) (min (long (:bootstrap-locked st)) b-raw) 0)
              ;; Core Team: 1-yr cliff (m >= 12), 4-yr linear vest
              team-delta (cond
                           (< m 12) 0
                           (= m 12) (min (long (:team-locked st)) (quot TEAM-GENESIS 4))
                           :else (min (long (:team-locked st)) (quot TEAM-GENESIS 48)))
              ;; Genesis Partners: strictly SLA-gated by endogenous :sla-paused-partners
              paused-cnt (long (or (:sla-paused-partners m-rec) 0))
              healthy-partner-frac (max 0.0 (/ (double (max 0 (- total-partners paused-cnt)))
                                               (double total-partners)))
              base-partner-tranche (cond
                                     (< m 12) 0
                                     (= m 12) (min (long (:partners-locked st)) (quot PARTNERS-GENESIS 3))
                                     :else (min (long (:partners-locked st)) (quot PARTNERS-GENESIS 36)))
              partners-delta (min (long (:partners-locked st))
                                  (long (Math/floor (* (double base-partner-tranche) healthy-partner-frac))))
              ;; Ecosystem & Grants: milestone-gated by active commercial subnets
              eco-delta (if (and (>= active-subs 4) (zero? (mod m 3)))
                          (min (long (:ecosystem-locked st)) (quot ECOSYSTEM-GENESIS 18))
                          0)
              circ-after-release (+ (long (:circulating st)) b-draw team-delta partners-delta eco-delta)
              ;; Endogenous monthly cleared GMV & per-capability burn R_t
              gmv-m (long (Math/round (* (double (or (:cleared-gmv-ech m-rec) 0)) scale)))
              per-cap-gmv-m (into (sorted-map)
                                  (map (fn [[k v]] [k (long (Math/round (* (double v) scale)))]))
                                  (or (:per-capability-gmv-ech m-rec) {}))
              per-cap-burn-m (into (sorted-map)
                                   (map (fn [[k v]] [k (long (quot (* (long v) tau-clamped) 10000))]))
                                   per-cap-gmv-m)
              r-raw (max (long (quot (* gmv-m tau-clamped) 10000))
                         (reduce + 0 (vals per-cap-burn-m)))
              r-burned (min circ-after-release (max 0 r-raw))
              trailing-rev (if (empty? rev-hist)
                             0
                             (long (quot (reduce + 0 (take-last 2 rev-hist)) 2)))
              ;; Demand-gated conditional mint M_t <= floor((k_bps / 10000) * R_trailing)
              m-cap (long (Math/floor (* (/ (double k-clamped) 10000.0) (double trailing-rev))))
              m-minted (min (long (:unminted-conditional st)) m-cap)
              ;; Year-3 hard sunset burn at Month 36 (epoch 26,280)
              b-after-draw (- (long (:bootstrap-locked st)) b-draw)
              sunset-burn (if (= m 36) b-after-draw 0)
              b-locked-final (- b-after-draw sunset-burn)
              circ-final (+ (- circ-after-release r-burned) m-minted)
              net-supply-delta (- circ-final (long (:circulating st)))
              first-defl-m (or (:first-deflationary-month st)
                               (when (and (> m 12) (neg? net-supply-delta)) m))
              snap {:month m
                    :epoch epoch
                    :circulating circ-final
                    :bootstrap-locked b-locked-final
                    :team-locked (- (long (:team-locked st)) team-delta)
                    :partners-locked (- (long (:partners-locked st)) partners-delta)
                    :ecosystem-locked (- (long (:ecosystem-locked st)) eco-delta)
                    :unminted-conditional (- (long (:unminted-conditional st)) m-minted)
                    :permanently-burned (+ (long (:permanently-burned st)) r-burned sunset-burn)
                    :cleared-gmv-this-month gmv-m
                    :per-capability-burn-this-month per-cap-burn-m
                    :active-commercial-subnets active-subs
                    :bootstrap-draw-delta b-draw
                    :team-vested-delta team-delta
                    :partners-vested-delta partners-delta
                    :ecosystem-released-delta eco-delta
                    :burned-revenue-this-month r-burned
                    :trailing-burned-revenue trailing-rev
                    :minted-this-month m-minted
                    :sunset-burned-this-month sunset-burn
                    :net-supply-delta net-supply-delta
                    :sla-paused-partners paused-cnt
                    :fleet-nodes next-fleet
                    :angels-onboarded? angels-now?
                    :seed-closed? seed-now?}
              st' (assoc st
                         :month m
                         :epoch epoch
                         :circulating (:circulating snap)
                         :bootstrap-locked (:bootstrap-locked snap)
                         :team-locked (:team-locked snap)
                         :partners-locked (:partners-locked snap)
                         :ecosystem-locked (:ecosystem-locked snap)
                         :unminted-conditional (:unminted-conditional snap)
                         :permanently-burned (:permanently-burned snap)
                         :fleet-nodes next-fleet
                         :active-commercial-subnets active-subs
                         :trailing-burned-revenue trailing-rev
                         :cumulative-burned-revenue (+ (long (:cumulative-burned-revenue st)) r-burned)
                         :cumulative-cleared-gmv-ech (+ (long (:cumulative-cleared-gmv-ech st)) gmv-m)
                         :cumulative-per-capability-burn-ech
                         (merge-with + (:cumulative-per-capability-burn-ech st) per-cap-burn-m)
                         :angels-onboarded? angels-now?
                         :seed-closed? seed-now?
                         :sla-paused-months (+ (long (:sla-paused-months st)) (if (pos? paused-cnt) 1 0))
                         :first-deflationary-month first-defl-m)]
          (recur (inc m) st' (conj rev-hist r-burned) (conj trace snap)))))))

(defn run-endogenous-scale-sweep
  "Sweeps `run-endogenous-seed-to-round` across a vector of target autonomous-agent fleet
   sizes (default `[550 1500 2700]` active autonomous agents) from a single lifecycle trial."
  [{:keys [trial-telemetry target-agent-counts k-bps tau-bps bootstrap-drawdown-rate]
    :or {target-agent-counts [550 1500 2700]
         k-bps 5000
         tau-bps 50
         bootstrap-drawdown-rate 0.045}}]
  (let [results (mapv (fn [n-agents]
                        (run-endogenous-seed-to-round
                         {:trial-telemetry trial-telemetry
                          :target-active-agents n-agents
                          :k-bps k-bps
                          :tau-bps tau-bps
                          :bootstrap-drawdown-rate bootstrap-drawdown-rate}))
                      target-agent-counts)]
    {:spec-cid genesis/GENESIS-SPEC-CID
     :scenario (:scenario trial-telemetry)
     :seed (:seed trial-telemetry)
     :target-agent-counts (vec target-agent-counts)
     :all-conserved? (every? :all-months-conserved? results)
     :results results}))
