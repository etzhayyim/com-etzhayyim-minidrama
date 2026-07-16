(ns minidrama.operation-test
  "Doctrine tests: empty theme → :noop proposal → :no-actuation hold;
  low-confidence plans still commit, tagged :low-confidence (transparency,
  not a block)."
  (:require [clojure.test :refer [deftest is]]
            [langgraph.graph :as g]
            [minidrama.store :as store]
            [minidrama.advisor :as advisor]
            [minidrama.publisher :as publisher]
            [minidrama.operation :as op]))

(defn- run [actor id theme & [ctx]]
  (g/run* actor
          {:request {:op :episode/plan :episode-id id :theme theme}
           :context (merge {:actor-id "minidrama" :phase 1} ctx)}
          {:thread-id id}))

(deftest empty-theme-is-held-as-no-actuation
  (let [s (store/seed-db)
        pub (publisher/mock-publisher (atom []))
        actor (op/build s {:publisher pub})
        r (run actor "e0" "")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (some #{:no-actuation} (-> (store/ledger s) last :basis)))
    (is (zero? (count @(:a pub))))))

(deftest low-confidence-commits-with-warning-tag
  (let [s (store/seed-db)
        pub (publisher/mock-publisher (atom []))
        low (reify advisor/Advisor
              (-plan [_ _ _]
                {:summary "low" :rationale "low"
                 :episode {:title "t" :logline "l"
                           :scenes [{:seq 0 :setting "s"
                                     :shots [{:seq 0 :prompt "p" :duration 5
                                              :subtitle "x"}]}]}
                 :effect :production :confidence 0.1}))
        actor (op/build s {:publisher pub :advisor low})
        r (run actor "e1" "テーマ")]
    (is (= :commit (get-in r [:state :disposition])))
    (is (= [:low-confidence]
           (mapv :rule (-> (store/ledger s) last :warnings)))
        "low confidence is a transparency tag, not a block")
    (is (= 1 (count @(:a pub))))))

(deftest phase2-publishes-with-auto-publish-grant
  ;; ADR-2607162200 Layer D: the scheduled loop's :auto-publish grant
  ;; satisfies phase 2 exactly like the per-episode human :publish; with NO
  ;; grant the phase gate withholds announcement (commit still lands).
  (let [plan (reify advisor/Advisor
               (-plan [_ _ _]
                 {:summary "s" :rationale "r"
                  :episode {:title "t" :logline "l"
                            :scenes [{:seq 0 :setting "s"
                                      :shots [{:seq 0 :prompt "p" :duration 5
                                               :subtitle "x"}]}]}
                  :effect :production :confidence 0.9}))
        run2 (fn [ctx id]
               (let [s (store/seed-db)
                     pub (publisher/mock-publisher (atom []))
                     actor (op/build s {:publisher pub :advisor plan})]
                 (run actor id "テーマ" ctx)
                 {:ledger (last (store/ledger s)) :posts (count @(:a pub))}))]
    (let [{:keys [ledger posts]} (run2 {:phase 2 :approvals #{:auto-publish}} "e2")]
      (is (true? (:published? ledger)))
      (is (= :auto-publish (:publish-grant ledger)) "grant is audited")
      (is (= 1 posts)))
    (let [{:keys [ledger posts]} (run2 {:phase 2 :approvals #{:publish}} "e3")]
      (is (true? (:published? ledger)))
      (is (= :publish (:publish-grant ledger)))
      (is (= 1 posts)))
    (let [{:keys [ledger posts]} (run2 {:phase 2} "e4")]
      (is (false? (:published? ledger)) "no grant → committed but NOT announced")
      (is (nil? (:publish-grant ledger)))
      (is (zero? posts)))))
