(ns bitecho.hive.test-runner
  "Test runner for Bitecho Hive (`alpeware/hive`)."
  (:require [bitecho.hive.arena-bounty-test]
            [bitecho.hive.core-test]
            [bitecho.hive.genesis-test]
            [bitecho.hive.rl-env-test]
            [bitecho.hive.seed-to-round-test]
            [bitecho.hive.top-loop-test]
            [clojure.test :as t]))

(defn -main
  "Runs the Bitecho Hive test suite and exits non-zero on failure."
  [& _args]
  (let [summary (t/run-tests 'bitecho.hive.core-test
                             'bitecho.hive.arena-bounty-test
                             'bitecho.hive.rl-env-test
                             'bitecho.hive.top-loop-test
                             'bitecho.hive.genesis-test
                             'bitecho.hive.seed-to-round-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail summary 0) (:error summary 0)))
      (System/exit 1))))
