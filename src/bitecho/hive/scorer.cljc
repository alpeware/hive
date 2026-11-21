(ns bitecho.hive.scorer
  "Deterministic SCI-sandboxed Clojure Scorer (`R-2`, `.cljc`) + Offline Eval Sidecar.

   Evaluates pure scoring expressions inside `org.babashka/sci` so any verifier
   (in-memory, JVM host, or browser CLJS) can independently re-derive and
   Merkle-verify per-agent and team rewards from the trace alone."
  (:require [sci.core :as sci]))

(def ^:const DEFAULT-SCORER-SOURCE
  "Canonical deterministic SCI Clojure scorer for closed-loop governance episodes."
  "(fn [summary]
     (let [status (:status summary)
           invalid-count (long (or (:invalid-actions summary) 0))
           chaos-green? (boolean (:chaos-all-green? summary))
           sim-ok? (boolean (:sim-consent-isolated? summary))
           seats (:seats summary)
           base-team (case status
                       :fixed-point-2 (if (and chaos-green? sim-ok?) 1.0 0.5)
                       :fixed-point-1 (if sim-ok? 0.6 0.0)
                       :unresolved 0.25
                       0.0)
           penalty (* 0.1 invalid-count)
           team-reward (max 0.0 (- base-team penalty))
           agent-rewards (into {}
                               (map (fn [seat]
                                      (let [seat-inv (long (get-in summary [:invalid-by-seat seat] 0))]
                                        [seat (max 0.0 (- team-reward (* 0.25 seat-inv)))]))
                                    seats))]
       {:team-reward team-reward
        :agent-rewards agent-rewards
        :metrics {:status status
                  :invalid-actions invalid-count
                  :chaos-all-green? chaos-green?
                  :sim-consent-isolated? sim-ok?
                  :turn-count (long (or (:turn-count summary) 0))}}))")

(defn env-state->score-summary
  "Extracts a pure, compact summary map from `env-state` suitable for SCI scoring."
  [env-state]
  (let [turns (:turns env-state)
        invalid-by-seat (reduce (fn [acc t]
                                  (if (= :invalid-action (get-in t [:action :action/type]))
                                    (update acc (:actor-id t) (fnil inc 0))
                                    acc))
                                {}
                                turns)
        receipt (:sim-consent-receipt env-state)]
    {:status (:status env-state)
     :seats (:turn-order env-state)
     :turn-count (count turns)
     :invalid-actions (long (get-in env-state [:diagnostics :invalid-actions] 0))
     :invalid-by-seat invalid-by-seat
     :chaos-all-green? (boolean (get-in env-state [:chaos-results :all-green?]))
     :sim-consent-isolated? (if receipt
                              (and (true? (:simulation? receipt))
                                   (true? (:advisory-only receipt)))
                              true)}))

(defn score-episode
  "Evaluates `scorer-source` (default `DEFAULT-SCORER-SOURCE`) inside a pure SCI sandbox."
  ([env-state]
   (score-episode env-state DEFAULT-SCORER-SOURCE))
  ([env-state scorer-source]
   (let [summary (env-state->score-summary env-state)
         sci-fn (sci/eval-string scorer-source {:deny ['eval 'load-string]})]
     (sci-fn summary))))

(defn build-offline-eval-sidecar
  "Constructs a post-hoc offline evaluation sidecar linking a twin trace CID to the
   eventual real BIP receipt CID (never used as an in-episode reward)."
  [{:keys [twin-trace-cid real-bip-receipt-cid twin-outcome real-outcome early-objections-surfaced per-seat-agreement]}]
  {:offline-eval/version 1
   :offline-eval/twin-trace-cid (str twin-trace-cid)
   :offline-eval/real-bip-receipt-cid (str real-bip-receipt-cid)
   :offline-eval/outcome-match? (= twin-outcome real-outcome)
   :offline-eval/early-objections-surfaced (vec (or early-objections-surfaced []))
   :offline-eval/per-seat-agreement (or per-seat-agreement {})})
