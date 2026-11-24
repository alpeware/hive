(ns bitecho.hive.rolling-upgrade-sim
  "Pure Sans-IO (`.cljc`) Streamlet BFT Rolling-Upgrade SLA Simulator.

   Models pointer-driven, leader-slot-phase auto-updates built into full-nodes
   against Streamlet BFT consensus (`Q = ceil(2N/3)`, round-robin leaders
   `mod(epoch, N)`, 3-consecutive-epoch finalization rule) to quantify the
   release-latency vs finalization-stall trade-off across four upgrade policies:

     1. `:naive-sleep-5s`                  — blind sequential restarts every 5s
     2. `:post-rotation-unguarded`         — immediate roll after leader rotation
     3. `:post-rotation-prev-leader-guard` — roll only if `(k-1) mod N` online & `head - finalized <= 1`
     4. `:post-rotation-full-quorum-guard` — roll only if full quorum headroom healthy & finality advancing

   Zero IO, zero `System/currentTimeMillis`, zero host `rand` — 100% deterministic
   from `:seed` via a portable Park-Miller PRNG."
  (:require [clojure.string :as str]))

(def ^:const ALL-POLICIES
  "Canonical ordered vector of all four rolling-upgrade policies."
  [:naive-sleep-5s
   :post-rotation-unguarded
   :post-rotation-prev-leader-guard
   :post-rotation-full-quorum-guard])

;; ---------------------------------------------------------------------------
;; Pure Portable Deterministic PRNG (Park-Miller LCG; exact in 53-bit & 64-bit)
;; ---------------------------------------------------------------------------

(def ^:private ^:const LCG-MOD 2147483647)
(def ^:private ^:const LCG-MUL 48271)

(defn make-rng
  "Creates an initial pure PRNG state from integer `seed`."
  [seed]
  (let [n (long (if (neg? seed) (- seed) seed))
        s (mod n (dec LCG-MOD))]
    (if (zero? s) 1 s)))

(defn next-rng
  "Advances pure PRNG state `s`, returning `[s' u]` with `u` in `(0.0, 1.0)`."
  [s]
  (let [s' (mod (* (long s) LCG-MUL) LCG-MOD)]
    [s' (/ (double s') (double LCG-MOD))]))

(defn rng-int-range
  "Draws a deterministic integer in inclusive range `[lo, hi]`, returning `[s' v]`."
  [s lo hi]
  (if (>= lo hi)
    [s (long lo)]
    (let [[s' u] (next-rng s)
          span (inc (- (long hi) (long lo)))
          v (+ (long lo) (long (* u (double span))))]
      [s' (min (long hi) v)])))

;; ---------------------------------------------------------------------------
;; Streamlet Consensus Helpers
;; ---------------------------------------------------------------------------

(defn quorum-size
  "Streamlet BFT quorum threshold `Q = ceil(2N/3)` using pure integer arithmetic."
  [n]
  (quot (+ (* 2 (long n)) 2) 3))

(defn leader-for-epoch
  "Round-robin leader index `mod(epoch, N)`."
  [epoch n]
  (mod (long epoch) (long n)))

(defn- online-at?
  "True if `node` is online at instant `t`."
  [node t]
  (<= (long (:down-until node)) (long t)))

(defn- restarting-at?
  "True if `node` is actively restarting at instant `t`."
  [node t]
  (and (= :restarting (:down-reason node))
       (<= (long (:down-from node)) (long t))
       (> (long (:down-until node)) (long t))))

(defn- offline-in-epoch?
  "True if `node`'s downtime interval `[down-from, down-until)` overlaps `[t-start, t-end)`."
  [node t-start t-end]
  (let [df (long (:down-from node))
        du (long (:down-until node))]
    (and (>= df 0)
         (< df (long t-end))
         (> du (long t-start)))))

(defn- draw-restart-ms
  "Draws a restart duration in milliseconds according to `config`'s `:restart-mode`."
  [rng config]
  (case (:restart-mode config :realistic)
    :fixed
    [rng (long (:fixed-restart-ms config 5000))]

    :fast
    (rng-int-range rng
                   (long (:fast-min-ms config 3000))
                   (long (:fast-max-ms config 7000)))

    :realistic
    (let [[rng1 u] (next-rng rng)
          tail-prob (double (:tail-prob config 0.15))]
      (if (< u tail-prob)
        (rng-int-range rng1
                       (long (:slow-min-ms config 12000))
                       (long (:slow-max-ms config 22000)))
        (rng-int-range rng1
                       (long (:fast-min-ms config 4000))
                       (long (:fast-max-ms config 7000)))))

    ;; Default fallback: realistic
    (rng-int-range rng 4000 7000)))

;; ---------------------------------------------------------------------------
;; Initial State & Guard Evaluation
;; ---------------------------------------------------------------------------

(defn- init-state
  [config]
  (let [n (long (:committee-size config 6))
        q (quorum-size n)
        epoch-ms (long (:epoch-ms config 5000))
        policy (:policy config :post-rotation-full-quorum-guard)
        flip-ms (long (:pointer-flip-ms config 0))
        skew-max (long (:pointer-skew-max-ms config 1500))
        naive-interval (long (:naive-interval-ms config 5000))
        rng0 (make-rng (long (:seed config 1)))
        [rng1 nodes]
        (reduce (fn [[r acc] i]
                  (let [[r' skew] (if (pos? skew-max)
                                    (rng-int-range r 0 skew-max)
                                    [r 0])]
                    [r' (conj acc {:id i
                                   :version :v1
                                   :seen-at (+ flip-ms skew)
                                   :down-from -1
                                   :down-until -1
                                   :down-reason nil
                                   :upgraded-at nil
                                   :restart-from nil
                                   :restart-until nil
                                   :scheduled-roll-at (when (= policy :naive-sleep-5s)
                                                        (+ flip-ms (* i naive-interval)))})]))
                [rng0 []]
                (range n))]
    {:config (assoc config
                    :committee-size n
                    :quorum q
                    :epoch-ms epoch-ms
                    :policy policy
                    :pointer-flip-ms flip-ms
                    :pointer-skew-max-ms skew-max)
     :rng rng1
     :epoch 0
     :nodes nodes
     :notarized-tail [-3 -2 -1]
     :head -1
     :finalized -2
     :finalized-advanced? true
     :stall-ms 0
     :current-stall-ms 0
     :max-single-stall-ms 0
     :diagnostics {:roll-deferred-prev-leader-down 0
                   :roll-deferred-quorum-guard 0
                   :missed-leader-slot 0
                   :quorum-stall 0
                   :finalization-stall 0
                   :background-jitter-events 0}
     :trace []}))

(defn- evaluate-post-rotation-guard
  "Pure policy guard returning `[:roll nil]` or `[:defer diagnostic-key]`."
  [state node-idx t-start]
  (let [{:keys [committee-size quorum policy]} (:config state)
        nodes (:nodes state)
        prev-idx (mod (dec node-idx) committee-size)
        prev-online? (online-at? (nth nodes prev-idx) t-start)
        gap (- (long (:head state)) (long (:finalized state)))
        gap-ok? (<= gap 1)
        fin-adv? (boolean (:finalized-advanced? state))]
    (case policy
      :post-rotation-unguarded
      [:roll nil]

      :post-rotation-prev-leader-guard
      (cond
        (not prev-online?) [:defer :roll-deferred-prev-leader-down]
        (not gap-ok?) [:defer :roll-deferred-quorum-guard]
        :else [:roll nil])

      :post-rotation-full-quorum-guard
      (let [online-cnt (count (filter #(online-at? % t-start) nodes))
            restarting-cnt (count (filter #(restarting-at? % t-start) nodes))
            ;; For N < 7 (f=1): require all N peers online (0 down).
            ;; For N >= 7 (f>=2): at most 1 concurrent roll total (restarting-cnt == 0 before k rolls)
            ;; while >= Q + 1 peers remain healthy after k rolls.
            quorum-headroom-ok? (and (zero? restarting-cnt)
                                     (if (< committee-size 7)
                                       (= online-cnt committee-size)
                                       (>= (dec online-cnt) (inc quorum))))]
        (cond
          (and quorum-headroom-ok? gap-ok? fin-adv?) [:roll nil]
          (not prev-online?) [:defer :roll-deferred-prev-leader-down]
          :else [:defer :roll-deferred-quorum-guard]))

      [:defer :roll-deferred-quorum-guard])))

(defn- start-node-restart
  [node t-roll dur-ms]
  (let [t-done (+ (long t-roll) (long dur-ms))]
    (assoc node
           :version :v2
           :down-from (long t-roll)
           :down-until t-done
           :down-reason :restarting
           :upgraded-at t-done
           :restart-from (long t-roll)
           :restart-until t-done)))

(defn- apply-naive-rolls
  [state t-start t-end]
  (reduce (fn [st idx]
            (let [node (nth (:nodes st) idx)
                  sched (:scheduled-roll-at node)]
              (if (and (= :v1 (:version node))
                       sched
                       (>= (long sched) (long t-start))
                       (< (long sched) (long t-end)))
                (let [[rng' dur] (draw-restart-ms (:rng st) (:config st))]
                  (-> st
                      (assoc :rng rng')
                      (assoc-in [:nodes idx] (start-node-restart node sched dur))))
                st)))
          state
          (range (count (:nodes state)))))

(defn- apply-post-rotation-roll
  [state e t-start]
  (if (zero? (long e))
    state
    (let [n (long (get-in state [:config :committee-size]))
          prev-leader (leader-for-epoch (dec (long e)) n)
          node (nth (:nodes state) prev-leader)]
      (if (and (= :v1 (:version node))
               (>= (long t-start) (long (:seen-at node)))
               (online-at? node t-start))
        (let [[decision diag-key] (evaluate-post-rotation-guard state prev-leader t-start)]
          (if (= :roll decision)
            (let [[rng' dur] (draw-restart-ms (:rng state) (:config state))]
              (-> state
                  (assoc :rng rng')
                  (assoc-in [:nodes prev-leader] (start-node-restart node t-start dur))))
            (update-in state [:diagnostics diag-key] inc)))
        state))))

(defn- apply-background-jitter
  [state t-start]
  (let [p (double (get-in state [:config :jitter-prob-per-epoch] 0.0))]
    (if (<= p 0.0)
      state
      (let [[rng1 u] (next-rng (:rng state))]
        (if (>= u p)
          (assoc state :rng rng1)
          (let [online-ids (vec (keep-indexed (fn [idx nd]
                                                (when (online-at? nd t-start) idx))
                                              (:nodes state)))]
            (if (empty? online-ids)
              (assoc state :rng rng1)
              (let [[rng2 pick-idx] (rng-int-range rng1 0 (dec (count online-ids)))
                    target-id (nth online-ids pick-idx)
                    j-min (long (get-in state [:config :jitter-min-ms] 3000))
                    j-max (long (get-in state [:config :jitter-max-ms] 8000))
                    [rng3 j-dur] (rng-int-range rng2 j-min j-max)]
                (-> state
                    (assoc :rng rng3)
                    (update-in [:nodes target-id]
                               assoc
                               :down-from (long t-start)
                               :down-until (+ (long t-start) (long j-dur))
                               :down-reason :jitter)
                    (update-in [:diagnostics :background-jitter-events] inc))))))))))

(defn- clear-finished-downtimes
  [state t-start]
  (update state :nodes
          (fn [nodes]
            (mapv (fn [nd]
                    (if (and (:down-reason nd)
                             (<= (long (:down-until nd)) (long t-start)))
                      (assoc nd :down-reason nil)
                      nd))
                  nodes))))

(defn- step-epoch
  [state]
  (let [e (long (:epoch state))
        cfg (:config state)
        n (long (:committee-size cfg))
        q (long (:quorum cfg))
        epoch-ms (long (:epoch-ms cfg))
        t-start (* e epoch-ms)
        t-end (* (inc e) epoch-ms)
        ;; 1. Clear completed downtimes as of t-start
        st1 (clear-finished-downtimes state t-start)
        ;; 2. Apply rolling-upgrade policy
        st2 (if (= :naive-sleep-5s (:policy cfg))
              (apply-naive-rolls st1 t-start t-end)
              (apply-post-rotation-roll st1 e t-start))
        ;; 3. Optional background transient jitter
        st3 (apply-background-jitter st2 t-start)
        ;; 4. Streamlet consensus step for epoch e
        nodes (:nodes st3)
        leader-id (leader-for-epoch e n)
        leader-online? (not (offline-in-epoch? (nth nodes leader-id) t-start t-end))
        online-voters (count (remove #(offline-in-epoch? % t-start t-end) nodes))
        quorum-ok? (>= online-voters q)
        notarized? (and leader-online? quorum-ok?)
        ;; 5. Streamlet 3-chain finalization rule
        old-fin (long (:finalized st3))
        [new-tail new-head new-fin]
        (if notarized?
          (let [tail-raw (conj (:notarized-tail st3) e)
                tail (if (> (count tail-raw) 3)
                       (subvec tail-raw (- (count tail-raw) 3))
                       tail-raw)
                three-chain? (and (= 3 (count tail))
                                  (= (long (nth tail 1)) (inc (long (nth tail 0))))
                                  (= (long (nth tail 2)) (inc (long (nth tail 1)))))]
            [tail e (if three-chain? (long (nth tail 1)) old-fin)])
          [(:notarized-tail st3) (long (:head st3)) old-fin])
        fin-adv? (> new-fin old-fin)
        new-stall-ms (if fin-adv?
                       (long (:stall-ms st3))
                       (+ (long (:stall-ms st3)) epoch-ms))
        new-cur-stall (if fin-adv?
                        0
                        (+ (long (:current-stall-ms st3)) epoch-ms))
        new-max-stall (max (long (:max-single-stall-ms st3)) new-cur-stall)
        diag (-> (:diagnostics st3)
                 (cond-> (not leader-online?) (update :missed-leader-slot inc))
                 (cond-> (not quorum-ok?) (update :quorum-stall inc))
                 (cond-> (not fin-adv?) (update :finalization-stall inc)))
        st4 (assoc st3
                   :epoch (inc e)
                   :notarized-tail new-tail
                   :head new-head
                   :finalized new-fin
                   :finalized-advanced? fin-adv?
                   :stall-ms new-stall-ms
                   :current-stall-ms new-cur-stall
                   :max-single-stall-ms new-max-stall
                   :diagnostics diag)]
    (if (:record-trace? cfg)
      (update st4 :trace conj {:epoch e
                               :t-start t-start
                               :leader leader-id
                               :leader-online? leader-online?
                               :online-voters online-voters
                               :notarized? notarized?
                               :head new-head
                               :finalized new-fin
                               :finalized-advanced? fin-adv?})
      st4)))

(defn- max-concurrent-restarts
  "Exact peak number of simultaneously restarting nodes across all restart intervals."
  [nodes]
  (let [intervals (keep (fn [nd]
                          (when (and (:restart-from nd) (:restart-until nd))
                            [(long (:restart-from nd)) (long (:restart-until nd))]))
                        nodes)]
    (if (empty? intervals)
      0
      (apply max
             (map (fn [[s _]]
                    (count (filter (fn [[a b]] (and (<= a s) (> b s))) intervals)))
                  intervals)))))

(defn- converged-and-settled?
  [state]
  (let [e (long (:epoch state))
        epoch-ms (long (get-in state [:config :epoch-ms]))
        t-now (* e epoch-ms)
        nodes (:nodes state)
        all-v2-online? (every? (fn [nd]
                                 (and (= :v2 (:version nd))
                                      (online-at? nd t-now)))
                               nodes)
        finality-settled? (and (:finalized-advanced? state)
                               (<= (- (long (:head state)) (long (:finalized state))) 1))]
    (and all-v2-online? finality-settled?)))

;; ---------------------------------------------------------------------------
;; Public Simulation API
;; ---------------------------------------------------------------------------

(defn run-trial
  "Runs a single deterministic rolling-upgrade simulation trial and returns
   structured SLA metrics and Rule-5 diagnostics."
  [config]
  (let [max-epochs (long (:max-epochs config 400))
        final-st (loop [st (init-state config)]
                   (cond
                     (converged-and-settled? st) st
                     (>= (long (:epoch st)) max-epochs) st
                     :else (recur (step-epoch st))))
        nodes (:nodes final-st)
        flip-ms (long (get-in final-st [:config :pointer-flip-ms]))
        all-v2? (every? #(= :v2 (:version %)) nodes)
        converged? (and all-v2? (converged-and-settled? final-st))
        release-latency-ms (if all-v2?
                             (- (long (apply max (map :upgraded-at nodes))) flip-ms)
                             (* (long (:epoch final-st))
                                (long (get-in final-st [:config :epoch-ms]))))]
    {:config (:config final-st)
     :policy (get-in final-st [:config :policy])
     :converged? converged?
     :epochs-run (long (:epoch final-st))
     :release-latency-ms release-latency-ms
     :stall-ms (long (:stall-ms final-st))
     :max-single-stall-ms (long (:max-single-stall-ms final-st))
     :zero-stall? (zero? (long (:stall-ms final-st)))
     :max-concurrent-restarting (max-concurrent-restarts nodes)
     :diagnostics (:diagnostics final-st)
     :trace (:trace final-st)}))

;; ---------------------------------------------------------------------------
;; Sweep Aggregation & Markdown Reporting
;; ---------------------------------------------------------------------------

(defn- percentile
  [sorted-vals p]
  (let [cnt (count sorted-vals)]
    (if (zero? cnt)
      0
      (let [idx (min (dec cnt)
                     (max 0 (long (* (double p) (double (dec cnt))))))]
        (nth sorted-vals idx)))))

(defn- mean-num
  [xs]
  (if (empty? xs)
    0.0
    (/ (double (reduce + 0 xs)) (double (count xs)))))

(defn summarize-policy-trials
  "Aggregates a collection of `run-trial` maps for a single policy."
  [policy trials]
  (let [n (count trials)
        lats (vec (sort (map :release-latency-ms trials)))
        stalls (vec (sort (map :stall-ms trials)))
        max-stalls (vec (sort (map :max-single-stall-ms trials)))
        conv-cnt (count (filter :converged? trials))
        zero-cnt (count (filter :zero-stall? trials))
        q-stall-trials (count (filter #(pos? (long (get-in % [:diagnostics :quorum-stall] 0))) trials))
        q-stall-total (reduce + 0 (map #(long (get-in % [:diagnostics :quorum-stall] 0)) trials))
        missed-total (reduce + 0 (map #(long (get-in % [:diagnostics :missed-leader-slot] 0)) trials))
        def-prev-total (reduce + 0 (map #(long (get-in % [:diagnostics :roll-deferred-prev-leader-down] 0)) trials))
        def-quorum-total (reduce + 0 (map #(long (get-in % [:diagnostics :roll-deferred-quorum-guard] 0)) trials))
        max-conc (if (seq trials)
                   (apply max (map :max-concurrent-restarting trials))
                   0)]
    {:policy policy
     :trials n
     :converged-rate (if (pos? n) (* 100.0 (/ (double conv-cnt) (double n))) 0.0)
     :zero-stall-rate (if (pos? n) (* 100.0 (/ (double zero-cnt) (double n))) 0.0)
     :release-latency-mean-ms (mean-num lats)
     :release-latency-p50-ms (percentile lats 0.50)
     :release-latency-p95-ms (percentile lats 0.95)
     :release-latency-max-ms (if (seq lats) (peek lats) 0)
     :stall-mean-ms (mean-num stalls)
     :stall-p95-ms (percentile stalls 0.95)
     :stall-max-ms (if (seq stalls) (peek stalls) 0)
     :max-single-stall-p95-ms (percentile max-stalls 0.95)
     :max-single-stall-max-ms (if (seq max-stalls) (peek max-stalls) 0)
     :quorum-stall-trials q-stall-trials
     :quorum-stall-total q-stall-total
     :missed-leader-total missed-total
     :max-concurrent-restarting-max max-conc
     :deferred-prev-leader-total def-prev-total
     :deferred-quorum-guard-total def-quorum-total}))

(defn run-sweep
  "Runs `n-trials` seeded trials (seeds `1..n-trials`) for each policy in `policies`
   under `base-config`, returning a vector of summary maps."
  [base-config policies n-trials]
  (mapv (fn [policy]
          (let [trials (mapv (fn [seed]
                               (run-trial (assoc base-config
                                                 :policy policy
                                                 :seed seed
                                                 :record-trace? false)))
                             (range 1 (inc (long n-trials))))]
            (summarize-policy-trials policy trials)))
        policies))

(defn- fmt-sec
  [ms]
  (let [tenths (long (+ 0.5 (/ (double ms) 100.0)))
        whole (quot tenths 10)
        frac (mod tenths 10)]
    (str whole "." frac "s")))

(defn- fmt-pct
  [pct]
  (let [tenths (long (+ 0.5 (* (double pct) 10.0)))
        whole (quot tenths 10)
        frac (mod tenths 10)]
    (str whole "." frac "%")))

(defn format-sweep-markdown
  "Formats a sweep summary vector into a GitHub-flavored Markdown comparison table."
  [title summaries]
  (let [header (str "### " title "\n\n"
                    "| Policy | 0-Stall Rate | Release p50 / p95 / max | Stall mean / p95 / max | Max Single Stall (p95/max) | Quorum Stalls (trials/epochs) | Missed Leaders | Max Conc. Rolls |\n"
                    "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        rows (map (fn [s]
                    (str "| `" (name (:policy s)) "`"
                         " | " (fmt-pct (:zero-stall-rate s))
                         " | " (fmt-sec (:release-latency-p50-ms s)) " / " (fmt-sec (:release-latency-p95-ms s)) " / " (fmt-sec (:release-latency-max-ms s))
                         " | " (fmt-sec (:stall-mean-ms s)) " / " (fmt-sec (:stall-p95-ms s)) " / " (fmt-sec (:stall-max-ms s))
                         " | " (fmt-sec (:max-single-stall-p95-ms s)) " / " (fmt-sec (:max-single-stall-max-ms s))
                         " | " (:quorum-stall-trials s) " / " (:quorum-stall-total s)
                         " | " (:missed-leader-total s)
                         " | " (:max-concurrent-restarting-max s)
                         " |"))
                  summaries)]
    (str/join "\n" (cons header rows))))
