(ns minidrama.outer-loop
  "Durable outer loop (ADR-2607162200 Layer B): consume ONE production tick
  per run — 1 run = 1 operation, no unbounded inner loops — driving the
  produce → dougaka engine → announce chain that scripts/produce-episode.bb
  already orchestrates.

  Tick source (Layer A): the aozora PDS cron emits `creatortick/<slug>/<date>/
  <slot>` datoms; this loop reads them via app.aozora.creator.getTicks. The
  actor NEVER writes the tick db — consumption is recorded as records in the
  actor's OWN repo (collection com.etzhayyim.apps.minidrama.tick, rkey
  <date>-<slot>), which doubles as the lease: a parallel loop instance sees
  the record and skips, so consuming a tick is idempotent. A record stuck in
  \"started\" (crash mid-chain) is surfaced by `status` for owner retry
  (lease-TTL auto-retry is an R1 follow-up).

  Publish policy (Layer D, ADR-2607162200): the run carries the
  :auto-publish grant — minidrama.phase/publish-allowed? admits it for
  phase 2 alongside the per-episode human :publish. The DramaGovernor stays
  the escalation boundary: a HOLD (content-veto / likeness / provenance /
  budget / rate-cap) exits the plan step non-zero, the tick is marked
  \"held\" and nothing is announced.

  Episode selection: the next episodes/*.edn catalog design not yet consumed
  (hand-authored designs still pass the DramaGovernor via the design-advisor,
  ADR-2607071300). MINIDRAMA_USE_LLM=1 is the deploy-wired LLM path instead.

  Usage: clojure -M:dev -m minidrama.outer-loop            run once
         clojure -M:dev -m minidrama.outer-loop status     ticks + consumption
  Env:   MINIDRAMA_PHASE   0 draft / 1 unlisted / 2 public (default 2 —
                           ADR-2607162200 scheduled operation)
         DOUGAKA_DIR       dougaka engine checkout (produce-episode.bb 既定)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [minidrama.aozora :as aozora]
            [minidrama.cacao :as cacao]
            [minidrama.phase :as phase]
            [minidrama.publisher :as publisher])
  (:gen-class))

(def tick-collection "com.etzhayyim.apps.minidrama.tick")
(def actor-slug "minidrama")

(defn- getx [url]
  (let [{:keys [status body]} (aozora/jvm-http-fn {:url url :method :get})]
    (when (= 200 status) (json/read-str body :key-fn keyword))))

(defn ticks
  "Ticks the PDS cadence cron has emitted for this actor (optionally one date)."
  ([pds] (ticks pds nil))
  ([pds date]
   (:ticks (getx (str pds "/xrpc/app.aozora.creator.getTicks?actor=" actor-slug
                      (when date (str "&date=" date)))))))

(defn consumption
  "Tick-consumption records from the actor's OWN repo → {tick-id record-value}."
  [pds did]
  (let [rs (:records (getx (str pds "/xrpc/com.atproto.repo.listRecords?repo=" did
                                "&collection=" tick-collection "&limit=100")))]
    (into {} (keep (fn [{:keys [value]}]
                     (when-let [tid (:tick-id value)] [tid value]))
                   rs))))

(defn- record-consumption! [pub {:keys [tick episode-id status extra]}]
  (publisher/publish!
   pub (merge {:collection tick-collection
               :rkey (str (:date tick) "-" (:slot tick))
               :$type tick-collection
               :tick-id (:id tick)
               :episode-id episode-id
               :status status}
              extra)))

(defn catalog-designs
  "episodes/*.edn catalog slugs (sorted) — the scheduled loop drains these."
  []
  (->> (.listFiles (io/file "episodes"))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
       (map #(str/replace (.getName ^java.io.File %) #"\.edn$" ""))
       sort vec))

(defn published-posts
  "rkeys of the actor's existing app.bsky.feed.post records + how many were
  created today — dedup source (a design already announced outside the tick
  system, e.g. the rooftop-3min manual demo, must never be re-produced) and
  the deterministic rate-cap input for the governor's :rate-limited gate."
  [pds did today]
  (let [rs (:records (getx (str pds "/xrpc/com.atproto.repo.listRecords?repo=" did
                                "&collection=app.bsky.feed.post&limit=100")))]
    {:rkeys (set (keep (fn [{:keys [uri]}] (last (str/split (str uri) #"/"))) rs))
     :today (count (filter #(str/starts-with? (str (get-in % [:value :createdAt])) today)
                           rs))}))

(defn next-design
  "First catalog design neither a consumption record nor an existing feed
  post (dedup vs out-of-band announces) has used yet."
  [consumed published-rkeys]
  (let [used (into (set (keep :episode-id (vals consumed))) published-rkeys)]
    (first (remove used (catalog-designs)))))

(def lease-ttl-minutes
  "A consumption record stuck in \"started\" longer than this is treated as a
  dead lease (crashed chain) and the tick becomes consumable again — the
  re-consume overwrites the same rkey, so recovery is idempotent.
  Override: MINIDRAMA_LEASE_TTL_MIN."
  120)

(defn- lease-expired? [record now-ms ttl-min]
  (and (= "started" (:status record))
       (when-let [ts (:createdAt record)]
         (try (> (- now-ms (.toEpochMilli (java.time.Instant/parse ts)))
                 (* ttl-min 60000))
              (catch Exception _ false)))))

(defn open-ticks
  "Due ticks that are unconsumed OR whose \"started\" lease has expired."
  [due consumed now-ms ttl-min]
  (remove (fn [t]
            (when-let [r (consumed (:id t))]
              (not (lease-expired? r now-ms ttl-min))))
          due))

(defn- notify!
  "Best-effort owner escalation on HOLD (macOS user notification). Never
  fails the run — the consumption record is the durable escalation fact."
  [msg]
  (try (.waitFor (.start (ProcessBuilder.
                          ^java.util.List
                          ["osascript" "-e"
                           (str "display notification \"" msg
                                "\" with title \"minidrama outer-loop\"")])))
       (catch Exception _ nil)))

(defn- run-chain!
  "produce → engine → announce via the existing orchestrator.
  mode: :public | :unlisted | :none. args: extra CLI args (e.g. --theme).
  published-today feeds the governor's deterministic rate cap. Returns exit."
  [{:keys [plan-args mode published-today]}]
  (let [cmd (cond-> (into ["bb" "scripts/produce-episode.bb"] plan-args)
              (= :public mode)   (conj "--announce")
              (= :unlisted mode) (conj "--announce-unlisted"))
        pb (doto (ProcessBuilder. ^java.util.List cmd) (.inheritIO))]
    (when published-today
      (.put (.environment pb) "MINIDRAMA_PUBLISHED_TODAY" (str published-today)))
    (.waitFor (.start pb))))

(defn run-once!
  "Consume at most one unconsumed tick for today (UTC). Returns a result map."
  []
  (let [pds aozora/default-pds
        id (cacao/load-or-create-identity! ".minidrama/identity.edn")
        pub (aozora/aozora-publisher {:pds pds :identity id
                                      :json-write json/write-str
                                      :json-read json/read-str})
        now-ms (System/currentTimeMillis)
        today (subs (str (java.time.Instant/now)) 0 10)
        ttl (or (some-> (System/getenv "MINIDRAMA_LEASE_TTL_MIN") parse-long)
                lease-ttl-minutes)
        due (vec (ticks pds today))
        consumed (consumption pds (:did id))
        open (first (open-ticks due consumed now-ms ttl))
        ph (or (some-> (System/getenv "MINIDRAMA_PHASE") parse-long) 2)
        ;; publish mode: phase 2 + :auto-publish grant → public feed post;
        ;; phase 1 → unlisted preview collection; phase 0 → produce only.
        mode (cond
               (phase/publish-allowed? ph #{:auto-publish}) :public
               (phase/publish-allowed? ph #{}) :unlisted
               :else :none)
        pubs (published-posts pds (:did id) today)
        hold! (fn [tick extra reason]
                (record-consumption! pub (merge {:tick tick :status "held"} extra))
                (notify! (str "HOLD " (:id tick) " — " reason))
                (merge {:status :held :tick (:id tick)} (:extra extra)))]
    (cond
      (nil? open)
      {:status :idle :due (count due) :consumed (count consumed)}

      :else
      (let [design (next-design consumed (:rkeys pubs))
            llm? (= "1" (System/getenv "MINIDRAMA_USE_LLM"))
            plan-args (cond
                        design ["--plan" (str "episodes/" design ".edn")]
                        ;; catalog dry + LLM enabled: themed plan, still
                        ;; censored by the DramaGovernor like any proposal.
                        llm? (let [auto-id (str "auto-" (:date open) "-" (:slot open))]
                               ["--theme" (str "縦型ミニドラマ・" (:date open))
                                "--id" auto-id]))
            episode-id (or design (when llm? (str "auto-" (:date open) "-" (:slot open))))]
        (if-not plan-args
          (hold! open {:extra {:reason "catalog-exhausted"}} "catalog exhausted (LLM off)")
          (do (record-consumption! pub {:tick open :episode-id episode-id :status "started"})
              (let [exit (run-chain! {:plan-args plan-args :mode mode
                                      :published-today (:today pubs)})]
                (if (zero? exit)
                  (do (record-consumption! pub {:tick open :episode-id episode-id :status "done"
                                                :extra {:phase ph :grant "auto-publish"
                                                        :mode (name mode)}})
                      {:status :done :tick (:id open) :episode episode-id :mode mode})
                  (hold! open {:episode-id episode-id :extra {:exit exit}}
                         (str "chain exit " exit))))))))))

(defn -main [& [cmd]]
  (if (= cmd "status")
    (let [pds aozora/default-pds
          id (cacao/load-or-create-identity! ".minidrama/identity.edn")]
      (println "ticks      :" (pr-str (ticks pds)))
      (println "consumption:" (pr-str (consumption pds (:did id)))))
    (println "run-once!  :" (pr-str (run-once!))))
  (System/exit 0))
