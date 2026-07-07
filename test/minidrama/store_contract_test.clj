(ns minidrama.store-contract-test
  "MemStore ≡ DatomicStore — the same episode + ledger facts committed to both
  backends must read back identically (the Store is a swap, not a rewrite)."
  (:require [clojure.test :refer [deftest is testing]]
            [minidrama.store :as store]))

(deftest mem-and-datomic-stores-agree
  (doseq [s [(store/seed-db) (store/datomic-store)]]
    (testing (str (type s))
      (store/commit-episode! s "e1" {:episode-id "e1" :title "t1" :shots 6})
      (store/commit-episode! s "e2" {:episode-id "e2" :title "t2" :shots 4})
      (store/append-ledger! s {:t :committed :episode "e1" :seq-hint 0})
      (store/append-ledger! s {:t :governor-hold :episode "e2" :seq-hint 1})
      (is (= "t1" (:title (store/episode s "e1"))))
      (is (nil? (store/episode s "nope")))
      (is (= ["e1" "e2"] (mapv :episode-id (store/all-episodes s))))
      (is (= [:committed :governor-hold] (mapv :t (store/ledger s)))))))

(deftest ledger-is-append-only-ordered
  (doseq [s [(store/seed-db) (store/datomic-store)]]
    (dotimes [i 5] (store/append-ledger! s {:t :fact :i i}))
    (is (= [0 1 2 3 4] (mapv :i (store/ledger s))))))
