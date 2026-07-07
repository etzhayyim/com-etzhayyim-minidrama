(ns minidrama.sim
  "Offline demo: drive two sample episode themes (one clean, one over-budget)
  through the minidrama actor on a MemStore + mock advisor + mock publisher
  (no network). `clojure -M:dev:run`."
  (:require [langgraph.graph :as g]
            [minidrama.operation :as op]
            [minidrama.store :as store]
            [minidrama.advisor :as advisor]
            [minidrama.publisher :as publisher])
  (:gen-class))

(defn -main [& _args]
  (let [s   (store/seed-db)
        pub (publisher/mock-publisher)
        a   (op/build s {:advisor (advisor/mock-advisor) :publisher pub})]
    (doseq [[ctx req] [[{:actor-id "minidrama" :phase 1}
                        {:op :episode/plan :episode-id "e1"
                         :theme "コンビニ夜勤の五分間" :duration-target 60}]
                       [{:actor-id "minidrama" :phase 1
                         :budget {:cost-per-shot 10 :episode-budget 24}}
                        {:op :episode/plan :episode-id "e2"
                         :theme "終電を逃した二人" :duration-target 60}]]]
      (let [r (g/run* a {:request req :context ctx}
                      {:thread-id (:episode-id req)})]
        (println (get-in r [:state :disposition]) "←" (:episode-id req)
                 "published?" (some? (get-in r [:state :published])))))
    (println "--- would-be announced records ---")
    (doseq [p @(:a pub)] (println (:episode-id p) "→" (:title p) "|" (:text p)))
    (println "--- ledger ---")
    (doseq [f (store/ledger s)] (prn f))))
