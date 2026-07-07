(ns minidrama.governor-contract-test
  "The mini-drama production contract as executable tests. Invariant:
  minidrama NEVER commits a plan the DramaGovernor rejects (over-duration /
  too-many-shots / overlong-shot / content-veto / likeness / unprovenanced
  asset / budget / rate-limit are held — recorded in the ledger, never
  committed, never announced), and PUBLIC announcement (phase 2) additionally
  requires the explicit :publish approval (ADR-2607071300 gate ④)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [minidrama.store :as store]
            [minidrama.advisor :as advisor]
            [minidrama.publisher :as publisher]
            [minidrama.operation :as op]))

(defn- fresh []
  (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))]
    [s pub (op/build s {:publisher pub})]))

(defn- run
  ([actor id theme] (run actor id theme {:actor-id "minidrama" :phase 1}))
  ([actor id theme ctx]
   (g/run* actor
           {:request {:op :episode/plan :episode-id id
                      :theme theme :duration-target 60}
            :context ctx}
           {:thread-id id})))

(defn- basis [s] (-> (store/ledger s) last :basis))
(defn- published-count [pub] (count @(:a pub)))

(defn- planned-advisor
  "Advisor stub that returns a fixed episode plan (adversarial injection)."
  [episode & [{:keys [effect confidence] :or {effect :production confidence 0.8}}]]
  (reify advisor/Advisor
    (-plan [_ _ _]
      {:summary "stub" :rationale "stub"
       :episode episode :effect effect :confidence confidence})))

(defn- shots [n dur & [extra]]
  [{:seq 0 :setting "s"
    :shots (vec (for [i (range n)]
                  (merge {:seq i :prompt (str "shot " i) :duration dur
                          :subtitle (str "line " i)}
                         extra)))}])

(deftest clean-plan-commits-and-announces-unlisted
  (let [[s pub actor] (fresh)
        r (run actor "e1" "コンビニ夜勤の五分間")]
    (is (= :commit (get-in r [:state :disposition])))
    (is (= "コンビニ夜勤の五分間（ミニドラマ）" (:title (store/episode s "e1"))))
    (is (= :unlisted (:visibility (store/episode s "e1"))))
    (is (= 1 (published-count pub)) "phase 1 (unlisted) announces without approval")))

(deftest phase-0-records-but-never-announces
  (let [[s pub actor] (fresh)
        r (run actor "e1" "テーマ" {:actor-id "minidrama" :phase 0})]
    (is (= :commit (get-in r [:state :disposition])))
    (is (some? (store/episode s "e1")))
    (is (zero? (published-count pub)) "draft phase withholds announcement")))

(deftest phase-2-requires-explicit-publish-approval
  (testing "without approval — committed, NOT announced"
    (let [[s pub actor] (fresh)
          r (run actor "e1" "テーマ" {:actor-id "minidrama" :phase 2})]
      (is (= :commit (get-in r [:state :disposition])))
      (is (some? (store/episode s "e1")))
      (is (zero? (published-count pub)))
      (is (false? (-> (store/ledger s) last :published?)))))
  (testing "with the :publish approval — announced public"
    (let [[s pub actor] (fresh)
          r (run actor "e1" "テーマ" {:actor-id "minidrama" :phase 2
                                      :approvals #{:publish}})]
      (is (= :commit (get-in r [:state :disposition])))
      (is (= 1 (published-count pub)))
      (is (= :public (:visibility (store/episode s "e1")))))))

(deftest over-duration-held
  (let [[s pub actor0] (fresh)
        actor (op/build s {:publisher pub
                           :advisor (planned-advisor
                                     {:title "t" :logline "l"
                                      :scenes (shots 20 8)})})] ; 160 s
    (is (some? actor0))
    (let [r (run actor "e1" "テーマ")]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:over-duration} (basis s)))
      (is (nil? (store/episode s "e1")))
      (is (zero? (published-count pub))))))

(deftest too-many-shots-held
  (let [[s pub _] (fresh)
        actor (op/build s {:publisher pub
                           :advisor (planned-advisor
                                     {:title "t" :logline "l"
                                      :scenes (shots 25 2)})})]
    (run actor "e1" "テーマ")
    (is (some #{:too-many-shots} (basis s)))
    (is (zero? (published-count pub)))))

(deftest overlong-shot-held
  (let [[s pub _] (fresh)
        actor (op/build s {:publisher pub
                           :advisor (planned-advisor
                                     {:title "t" :logline "l"
                                      :scenes (shots 3 11)})})]
    (run actor "e1" "テーマ")
    (is (some #{:overlong-shot} (basis s)))
    (is (zero? (published-count pub)))))

(deftest content-veto-held
  (let [[s pub _] (fresh)
        actor (op/build s {:publisher pub
                           :advisor (planned-advisor
                                     {:title "t <CAT>" :logline "l"
                                      :scenes (shots 3 5)})})]
    (run actor "e1" "テーマ")
    (is (some #{:content-veto} (basis s)))
    (is (zero? (published-count pub)))))

(deftest likeness-held
  (let [[s pub _] (fresh)
        actor (op/build s {:publisher pub
                           :advisor (planned-advisor
                                     {:title "t" :logline "有名人 <LIKENESS> が登場"
                                      :scenes (shots 3 5)})})]
    (run actor "e1" "テーマ")
    (is (some #{:likeness} (basis s)))
    (is (zero? (published-count pub)))))

(deftest unprovenanced-asset-held
  (let [[s pub _] (fresh)
        actor (op/build s {:publisher pub
                           :advisor (planned-advisor
                                     {:title "t" :logline "l"
                                      :scenes (shots 3 5 {:asset-url "https://x/clip.mp4"})})})]
    (run actor "e1" "テーマ")
    (is (some #{:unprovenanced-asset} (basis s)))
    (is (zero? (published-count pub)))))

(deftest budget-exceeded-held
  (let [[s pub actor] (fresh)
        r (run actor "e1" "テーマ" {:actor-id "minidrama" :phase 1
                                    :budget {:cost-per-shot 10 :episode-budget 24}})]
    (is (= :hold (get-in r [:state :disposition])))
    (is (some #{:budget-exceeded} (basis s)))
    (is (zero? (published-count pub)))))

(deftest rate-limit-held
  (let [[s pub actor] (fresh)
        r (run actor "e1" "テーマ" {:actor-id "minidrama" :phase 1
                                    :published-today 3})]
    (is (= :hold (get-in r [:state :disposition])))
    (is (some #{:rate-limited} (basis s)))
    (is (zero? (published-count pub)))))
