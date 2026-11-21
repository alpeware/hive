(ns bitecho.hive.top-loop
  "Pure Sans-IO `.cljc` Run-3 Top-Loop Reducer (`bitecho + mealy + hive`).

   Models the recursive whole-stack improvement loop:
     repo-hash_n -> proposal_n -> (twin consent sim + disposable-copy impl +
     in-memory whole-stack self-sim) -> (patch_n, telemetry_n) ->
     gated pointer promotion (real consent + mechanical/telemetry checks) ->
     self-regulating staged rollout (canary -> partial -> wider -> full,
     governed by circle consent & values doc; rollback-first on any fault) ->
     repo-hash_{n+1}."
  (:require [bitecho.hive.env :as env]))

(def ^:const RUN-3-PROPOSAL-TEXT
  "Simon's Run-3 Top-Loop proposal covering the whole stack (bitecho + mealy + hive)."
  "Extend the closed-loop Hive RL environment across the whole Sans-IO stack (bitecho + mealy + hive) so the system simulates itself in memory end-to-end: each Top-Loop iteration anchors to the exact current multi-repo hash (repo-hash_n), executes proposal_n -> twin consent sim -> disposable-copy implementation -> in-memory whole-stack proof & byte-level chaos simulation -> (patch_n, telemetry_n). Promotion to live devnet happens ONLY by updating the signed active devnet pointer (never manual bundle flips), strictly gated on real circle consent + green mechanical/telemetry checks. Rollout is staged and self-regulating (canary sample -> partial -> wider -> full, telemetry feedback per stage, rollback-first to .lkg on any degradation), with rollout policy governed by circle consent against the ratified Shared Values document; the promoted live state becomes repo-hash_{n+1}.")

(def default-rollout-stages
  "Circle-governed progressive staged rollout rings (canary-first)."
  [{:stage :canary  :node-count 1 :max-silent-drops 0}
   {:stage :partial :node-count 2 :max-silent-drops 0}
   {:stage :wider   :node-count 4 :max-silent-drops 0}
   {:stage :full    :node-count 6 :max-silent-drops 0}])

(defn init-top-loop
  "Initializes a pure Top-Loop state anchored to `repo-hash` and `values-cid`."
  [{:keys [repo-hash values-cid rollout-stages lkg-pointer-cid]
    :or {values-cid env/VALUES-DRAFT-CID
         rollout-stages default-rollout-stages}}]
  {:iteration 0
   :repo-hash (str repo-hash)
   :values-cid (str values-cid)
   :active-pointer-cid (or lkg-pointer-cid (str repo-hash))
   :lkg-pointer-cid (or lkg-pointer-cid (str repo-hash))
   :rollout-stages (vec rollout-stages)
   :phase :idle
   :pending-candidate nil
   :real-consent-receipt nil
   :rollout-idx 0
   :history []
   :diagnostics {:rejected-promotions 0 :rollbacks 0 :events []}})

(defn record-sim-iteration
  "Records a completed simulation iteration `(proposal -> patch + telemetry)` anchored
   to the current `:repo-hash`. Does NOT flip any live pointer."
  [st {:keys [proposal-cid sim-trace-cid patch-cid telemetry-cid telemetry-green?]}]
  (let [candidate {:iteration (inc (long (:iteration st)))
                   :base-repo-hash (:repo-hash st)
                   :proposal-cid (str proposal-cid)
                   :sim-trace-cid (str sim-trace-cid)
                   :patch-cid (str patch-cid)
                   :telemetry-cid (str telemetry-cid)
                   :telemetry-green? (boolean telemetry-green?)}]
    [(assoc st
            :phase :pending-promotion
            :pending-candidate candidate)
     [[:emit-candidate-ready candidate]]]))

(defn- valid-real-consent-receipt?
  [receipt expected-patch-cid]
  (and (map? receipt)
       (false? (:simulation? receipt))
       (false? (:advisory-only receipt))
       (= expected-patch-cid (:patch-cid receipt))
       (string? (:receipt-cid receipt))
       (= 64 (count (:receipt-cid receipt)))
       (seq (:verdicts receipt))
       (every? #(contains? #{:consent :consent-with-addendum} %) (vals (:verdicts receipt)))))

(defn request-pointer-promotion
  "Hard gate between simulation output and live devnet pointer promotion.
   Fails closed with a counted `:diagnose` effect unless BOTH:
   (1) `:pending-candidate` passed telemetry/mechanical checks (`:telemetry-green? true`), AND
   (2) `real-receipt` is a verified `:real-consent-receipt` (`:simulation? false`)."
  [st real-receipt]
  (let [cand (:pending-candidate st)]
    (cond
      (or (nil? cand) (not= :pending-promotion (:phase st)))
      (let [diag {:reason :no-pending-candidate :phase (:phase st)}]
        [(-> st
             (update-in [:diagnostics :rejected-promotions] inc)
             (update-in [:diagnostics :events] conj diag))
         [[:diagnose diag]]])

      (not (:telemetry-green? cand))
      (let [diag {:reason :candidate-telemetry-not-green :patch-cid (:patch-cid cand)}]
        [(-> st
             (update-in [:diagnostics :rejected-promotions] inc)
             (update-in [:diagnostics :events] conj diag))
         [[:diagnose diag]]])

      (not (valid-real-consent-receipt? real-receipt (:patch-cid cand)))
      (let [diag {:reason :invalid-or-simulated-consent-receipt
                  :simulation? (:simulation? real-receipt)
                  :patch-cid (:patch-cid cand)}]
        [(-> st
             (update-in [:diagnostics :rejected-promotions] inc)
             (update-in [:diagnostics :events] conj diag))
         [[:diagnose diag]]])

      :else
      [(assoc st
              :phase :rolling-out
              :real-consent-receipt real-receipt
              :rollout-idx 0)
       [[:begin-staged-rollout {:stage (first (:rollout-stages st))
                                :patch-cid (:patch-cid cand)
                                :values-cid (:values-cid st)}]]])))

(defn advance-rollout-stage
  "Self-regulating staged rollout reducer.
   - If stage telemetry violates the circle-consented rollout policy (not green,
     finalized epoch stalled, or silent drops > threshold): immediately triggers
     `:rollback-to-lkg` + counted `:diagnose` effect and preserves `:repo-hash`.
   - If green and final stage (`:full`) completes: flips `:active-pointer-cid`,
     advances `:repo-hash` to `repo_{n+1}` hash-anchored to `repo_n + patch + receipt`,
     and appends the verified promotion record to `:history`."
  [st {:keys [stage-green? finalized-epoch-advancing? silent-drops]
       :or {silent-drops 0}}]
  (if (not= :rolling-out (:phase st))
    (let [diag {:reason :advance-rollout-while-not-rolling :phase (:phase st)}]
      [(update-in st [:diagnostics :events] conj diag) [[:diagnose diag]]])
    (let [stages (:rollout-stages st)
          idx (long (:rollout-idx st))
          cur-stage (nth stages idx)
          healthy? (and (boolean stage-green?)
                        (boolean finalized-epoch-advancing?)
                        (<= (long silent-drops) (long (:max-silent-drops cur-stage))))]
      (if-not healthy?
        (let [diag {:reason :staged-rollout-degraded
                    :stage (:stage cur-stage)
                    :stage-green? stage-green?
                    :finalized-epoch-advancing? finalized-epoch-advancing?
                    :silent-drops silent-drops}]
          [(-> st
               (assoc :phase :rolled-back
                      :pending-candidate nil
                      :real-consent-receipt nil
                      :rollout-idx 0)
               (update-in [:diagnostics :rollbacks] inc)
               (update-in [:diagnostics :events] conj diag))
           [[:rollback-to-lkg {:lkg-pointer-cid (:lkg-pointer-cid st)
                               :failed-stage (:stage cur-stage)}]
            [:diagnose diag]]])
        (let [next-idx (inc idx)]
          (if (< next-idx (count stages))
            [(assoc st :rollout-idx next-idx)
             [[:advance-rollout-pointer {:stage (nth stages next-idx)
                                         :patch-cid (get-in st [:pending-candidate :patch-cid])}]]]
            (let [cand (:pending-candidate st)
                  rcpt (:real-consent-receipt st)
                  new-repo-hash (env/sha256-str (pr-str {:prev-repo-hash (:repo-hash st)
                                                         :patch-cid (:patch-cid cand)
                                                         :real-consent-receipt-cid (:receipt-cid rcpt)}))
                  new-pointer-cid (env/sha256-str (pr-str {:pointer-target new-repo-hash
                                                           :iteration (:iteration cand)}))
                  record (assoc cand
                                :real-consent-receipt-cid (:receipt-cid rcpt)
                                :promoted-pointer-cid new-pointer-cid
                                :next-repo-hash new-repo-hash)]
              [(-> st
                   (assoc :iteration (:iteration cand)
                          :lkg-pointer-cid (:active-pointer-cid st)
                          :active-pointer-cid new-pointer-cid
                          :repo-hash new-repo-hash
                          :phase :idle
                          :pending-candidate nil
                          :real-consent-receipt nil
                          :rollout-idx 0)
                   (update :history conj record))
               [[:flip-active-devnet-pointer {:pointer-cid new-pointer-cid
                                              :repo-hash new-repo-hash
                                              :iteration (:iteration cand)}]]])))))))

(defn verify-top-loop-chain
  "Purely verifies that every promoted iteration in `:history` forms an unbroken
   hash chain anchored to the prior `:repo-hash` and ends at current `(:repo-hash st)`."
  [st]
  (let [hist (:history st)]
    (if (empty? hist)
      true
      (loop [idx 0
             expected-prev (:base-repo-hash (first hist))]
        (if (>= idx (count hist))
          (= expected-prev (:repo-hash st))
          (let [rec (nth hist idx)
                computed-next (env/sha256-str (pr-str {:prev-repo-hash (:base-repo-hash rec)
                                                       :patch-cid (:patch-cid rec)
                                                       :real-consent-receipt-cid (:real-consent-receipt-cid rec)}))]
            (if (and (= expected-prev (:base-repo-hash rec))
                     (= computed-next (:next-repo-hash rec)))
              (recur (inc idx) computed-next)
              false)))))))
