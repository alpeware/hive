(ns bitecho.hive.test-runner
  "Test runner for Bitecho Hive (`alpeware/hive`)."
  (:require [bitecho.hive.core-test]
            [clojure.test :as t]))

(defn -main
  "Runs the Bitecho Hive test suite and exits non-zero on failure."
  [& _args]
  (let [summary (t/run-tests 'bitecho.hive.core-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail summary 0) (:error summary 0)))
      (System/exit 1))))
