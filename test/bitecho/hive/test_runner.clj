(ns bitecho.hive.test-runner
  "Test runner for Bitecho Hive (`alpeware/hive`)."
  (:require [bitecho.hive.arena-bounty-test]
            [bitecho.hive.core-test]
            [bitecho.hive.genesis-test]
            [bitecho.hive.rl-env-test]
            [bitecho.hive.rolling-upgrade-sim-test]
            [bitecho.hive.seed-to-round-test]
            [bitecho.hive.top-loop-test]
            [bitecho.hive.trace-viz-test]
            [bitecho.hive.twin-stage1-test]
            [clojure.test :as t]))

(def ^:private all-test-namespaces
  ['bitecho.hive.core-test
   'bitecho.hive.arena-bounty-test
   'bitecho.hive.rl-env-test
   'bitecho.hive.rolling-upgrade-sim-test
   'bitecho.hive.top-loop-test
   'bitecho.hive.genesis-test
   'bitecho.hive.seed-to-round-test
   'bitecho.hive.trace-viz-test
   'bitecho.hive.twin-stage1-test])

(defn- parse-selected-namespaces
  [args]
  (loop [xs (seq args)
         acc []]
    (if-not xs
      (if (seq acc) acc all-test-namespaces)
      (if (= "-n" (first xs))
        (recur (nnext xs) (if (second xs) (conj acc (symbol (second xs))) acc))
        (recur (next xs) acc)))))

(defn -main
  "Runs the Bitecho Hive test suite (or selected `-n <ns>` namespaces) and exits non-zero on failure."
  [& args]
  (let [nses (parse-selected-namespaces args)
        summary (apply t/run-tests nses)]
    (shutdown-agents)
    (when (pos? (+ (:fail summary 0) (:error summary 0)))
      (System/exit 1))))
