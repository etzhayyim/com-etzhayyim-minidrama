(ns minidrama.produce
  "Plan-production entrypoint — run ONE episode theme through the actor
  (DramaLLM proposal → DramaGovernor → commit | hold) and, on :commit, emit
  the committed plan EDN to `.minidrama/episodes/<episode-id>.edn` — the work
  order the dougaka engine consumes (`dougaka.pipeline`, ADR-2607071500).
  scripts/produce-episode.bb chains produce → engine → announce.

  Governor doctrine is unchanged: a HOLD prints the violation basis and exits
  1 — no plan file is written for a rejected episode.

  Usage: clojure -M:dev -m minidrama.produce <theme> [episode-id] [duration]
         clojure -M:dev -m minidrama.produce --from episodes/<slug>.edn
           (hand-authored design — STILL censored by the DramaGovernor via a
            design-advisor, ADR-2607071300: 手書き脚本も同じ検閲を通る)
  Env:   MINIDRAMA_USE_LLM=1     use the Murakumo LLM advisor (deploy's wiring)
         MINIDRAMA_OLLAMA_URL / MINIDRAMA_OLLAMA_MODEL   as in minidrama.deploy"
  (:require [clojure.edn]
            [clojure.java.io :as io]
            [langgraph.graph :as g]
            [minidrama.advisor :as advisor]
            [minidrama.deploy :as deploy]
            [minidrama.operation :as op]
            [minidrama.publisher :as publisher]
            [minidrama.store :as store])
  (:gen-class))

(defn design-advisor
  "Advisor that proposes a fixed hand-authored design (episodes/*.edn) —
  the DramaGovernor censors it exactly like a DramaLLM proposal."
  [design]
  (reify advisor/Advisor
    (-plan [_ _ _]
      {:summary (str "hand-authored design: " (:title design))
       :rationale "episodes/ catalog design (operator-authored)"
       :episode (select-keys design [:title :logline :scenes])
       :effect :production
       :confidence 0.9})))

(defn produce-plan!
  "Run one theme through the actor. Returns
  {:disposition :commit|:hold :plan <record|nil> :basis [...]}."
  [{:keys [theme episode-id duration advisor phase]
    :or {phase 1}}]
  (let [s (store/seed-db)
        actor (op/build s (cond-> {:publisher (publisher/mock-publisher)}
                            advisor (assoc :advisor advisor)))
        r (g/run* actor {:request {:op :episode/plan :episode-id episode-id
                                   :theme theme :duration-target duration}
                         :context {:actor-id "minidrama" :phase phase}}
                  {:thread-id episode-id})
        disposition (get-in r [:state :disposition])]
    {:disposition disposition
     :plan (store/episode s episode-id)
     :basis (when (= :hold disposition)
              (-> (store/ledger s) last :basis))}))

(defn -main [& [theme episode-id duration]]
  (when-not (and theme (seq theme))
    (binding [*out* *err*]
      (println "usage: clojure -M:dev -m minidrama.produce <theme>|--from <edn> [episode-id] [duration]"))
    (System/exit 1))
  (let [design (when (= "--from" theme)
                 (clojure.edn/read-string (slurp episode-id)))
        episode-id (if design
                     (:episode-id design)
                     (or episode-id (str "ep-" (System/currentTimeMillis))))
        adv (cond
              design (design-advisor design)
              (= "1" (System/getenv "MINIDRAMA_USE_LLM"))
              (advisor/llm-advisor (deploy/ollama-chat-model) {:max-tokens 1024}))
        {:keys [disposition plan basis]}
        (produce-plan! {:theme (if design (:title design) theme)
                        :episode-id episode-id
                        :duration (some-> duration parse-long)
                        :advisor adv})]
    (if (= :commit disposition)
      (let [f (io/file ".minidrama/episodes" (str episode-id ".edn"))]
        (io/make-parents f)
        (spit f (pr-str plan))
        (println "disposition: commit")
        (println "plan       :" (str f))
        (println "title      :" (:title plan))
        (println "shots      :" (:shots plan) "duration:" (:duration plan) "s"))
      (do (println "disposition: hold")
          (println "basis      :" (pr-str basis))
          (System/exit 1)))))
