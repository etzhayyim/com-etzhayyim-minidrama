(ns minidrama.run-tests
  "Test runner for com-etzhayyim-minidrama (new actors ship run_tests.clj, not
  .sh — per etzhayyim/root CLAUDE.md). Canonical path: `clojure -M:dev:test`
  (cognitect test-runner). This runner: `clojure -M -m minidrama.run-tests`."
  (:require [clojure.test :refer [run-tests]]
            [minidrama.governor-contract-test]
            [minidrama.store-contract-test]
            [minidrama.operation-test])
  (:gen-class))

(defn -main [& _args]
  (let [res (run-tests
             'minidrama.governor-contract-test
             'minidrama.store-contract-test
             'minidrama.operation-test)]
    (when (pos? (+ (:fail res 0) (:error res 0)))
      (System/exit 1))))
