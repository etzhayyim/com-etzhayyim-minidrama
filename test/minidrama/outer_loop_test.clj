(ns minidrama.outer-loop-test
  "Pure-function coverage for the scheduled loop's dedup + lease semantics
  (repo ADR-0002 / superproject ADR-2607162200)."
  (:require [clojure.test :refer [deftest is testing]]
            [minidrama.outer-loop :as ol]))

(def ^:private tick {:id "creatortick/minidrama/2026-07-16/0"
                     :slug "minidrama" :date "2026-07-16" :slot "0"})

(deftest open-ticks-lease-semantics
  (let [now 10000000
        ttl 120]
    (testing "unconsumed tick is open"
      (is (= [tick] (vec (ol/open-ticks [tick] {} now ttl)))))
    (testing "done / held consumption closes the tick"
      (doseq [status ["done" "held"]]
        (is (empty? (ol/open-ticks [tick] {(:id tick) {:status status}} now ttl)))))
    (testing "fresh started lease closes the tick; expired lease reopens it"
      (let [at (fn [ms] (str (java.time.Instant/ofEpochMilli ms)))]
        (is (empty? (ol/open-ticks [tick]
                                   {(:id tick) {:status "started" :createdAt (at now)}}
                                   now ttl)))
        (is (= [tick] (vec (ol/open-ticks
                            [tick]
                            {(:id tick) {:status "started"
                                         :createdAt (at (- now (* (inc ttl) 60000)))}}
                            now ttl))))))
    (testing "unparseable createdAt stays leased (never crash-reopen on bad data)"
      (is (empty? (ol/open-ticks [tick]
                                 {(:id tick) {:status "started" :createdAt "garbage"}}
                                 now ttl))))))

(deftest next-design-dedups-consumption-and-published-posts
  (let [designs (ol/catalog-designs)]
    (is (seq designs) "episodes/ catalog present")
    (testing "consumption records exclude a design"
      (is (not= (first designs)
                (ol/next-design {"t" {:episode-id (first designs)}} #{}))))
    (testing "an out-of-band published post rkey excludes a design too
              (e.g. the manually announced rooftop-3min demo)"
      (is (not (contains? (set [(ol/next-design {} #{"rooftop-3min"})])
                          "rooftop-3min"))))
    (testing "everything used → nil (catalog exhausted)"
      (is (nil? (ol/next-design {} (set designs)))))))

(deftest merge-consumption-prefers-terminal-records
  ;; observed live 2026-07-16: same-rkey overwrites read back non-deterministically
  ;; while novelty is unfolded -- per-status rkeys + rank merge (done > held > started).
  (let [tid "creatortick/x/2026-07-16/0"
        started {:tick-id tid :status "started" :episode-id "a"}
        held    {:tick-id tid :status "held" :episode-id "a"}
        done    {:tick-id tid :status "done" :episode-id "a"}]
    (is (= {tid done} (ol/merge-consumption [started done])))
    (is (= {tid done} (ol/merge-consumption [done started])) "order-independent")
    (is (= {tid started} (ol/merge-consumption [started])))
    (is (= "done" (:status (get (ol/merge-consumption [started held done]) tid)))
        "done outranks held (a successful retry supersedes an earlier hold)")
    (is (= "held" (:status (get (ol/merge-consumption [held started]) tid))))))
