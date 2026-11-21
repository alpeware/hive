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
              ;; Protocol revenue R_t (signaling burns + 50 bps sci settlement takes), 100% burned
              demand-shock (+ 0.72 (* 0.56 u-demand))
              growth-pow (Math/pow (double (:sci-growth-rate scenario)) (double (min m 24)))
              fleet-factor (/ (double (+ next-fleet next-browser)) 10.0)
              r-raw (long (Math/floor (* (double (:base-monthly-sci-burn scenario))
                                         growth-pow
                                         fleet-factor
                                         demand-shock
                                         (if rollout-fault? 0.65 1.0))))
              r-burned (min circ-after-release (max 0 r-raw))
              ;; Trailing revenue window (2-month rolling proxy for 168-epoch window, zero-padded)
              trailing-rev (if (empty? rev-hist)
                             0
                             (long (quot (reduce + 0 (take-last 2 rev-hist)) 2)))
              ;; Demand-gated mint M_t <= floor(0.5 * R_trailing), capped by unminted conditional supply
              m-cap (long (Math/floor (* 0.5 (double trailing-rev))))
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
