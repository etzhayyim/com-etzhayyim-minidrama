#!/usr/bin/env bb
;; produce-episode — theme 一発でミニドラマを製造する orchestrator
;; (ADR-2607071300/2607071500 の「pipeline 発注の自動化」の完成形)。
;;
;;   minidrama.produce   企画→脚本→DramaGovernor→commit (hold なら exit 1)
;;   dougaka.pipeline    committed plan → keyframes → ffmpeg → 縦 mp4 + SRT
;;   minidrama.announce  uploadBlob(認証) → app.aozora.embed.video post → /videos
;;
;; usage:
;;   bb scripts/produce-episode.bb --theme "終電を逃した二人" [--id ep-x]
;;      [--duration 60] [--announce] [--title "…"]
;;
;; --announce が「このエピソードを公開してよい」という operator の per-episode
;; sign-off (ADR-2607071300 gate ④)。無しなら mp4 製造まで (preview)。
;; dougaka repo は west 配置の sibling (../../gftdcojp/ai-gftd-dougaka) 既定、
;; DOUGAKA_DIR で上書き可。
(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn- opt [args flag]
  (when-let [i (some->> args (map-indexed vector)
                        (filter #(= flag (second %))) first first)]
    (get (vec args) (inc i))))

(defn- flag? [args flag] (boolean (some #{flag} args)))

(defn- run! [dir cmd]
  (let [{:keys [exit]} @(p/process cmd {:dir dir :inherit true})]
    (when-not (zero? exit)
      (binding [*out* *err*] (println "step failed (exit" exit "):" (str/join " " cmd)))
      (System/exit exit))))

(let [args *command-line-args*
      theme (or (opt args "--theme")
                (do (binding [*out* *err*]
                      (println "usage: bb scripts/produce-episode.bb --theme \"…\" [--id …] [--duration 60] [--announce] [--title …]"))
                    (System/exit 1)))
      id (or (opt args "--id") (str "ep-" (System/currentTimeMillis)))
      duration (opt args "--duration")
      title (or (opt args "--title") theme)
      announce? (flag? args "--announce")
      here (str (fs/parent (fs/parent *file*)))          ; repo root
      dougaka (or (System/getenv "DOUGAKA_DIR")
                  (str (fs/normalize (fs/path here "../../gftdcojp/ai-gftd-dougaka/clj"))))
      plan-file (str here "/.minidrama/episodes/" id ".edn")
      out-dir (str here "/.minidrama/episodes/" id)]
  (println "=== 1/3 plan (minidrama actor: DramaLLM ⊣ DramaGovernor) ===")
  (run! here (cond-> ["clojure" "-M:dev" "-m" "minidrama.produce" theme id]
               duration (conj duration)))
  (println "=== 2/3 produce (dougaka engine: keyframes → ffmpeg) ===")
  (run! dougaka ["clojure" "-M:dev" "-m" "dougaka.pipeline" plan-file out-dir])
  (let [mp4 (str out-dir "/" id ".mp4")]
    (if announce?
      (do (println "=== 3/3 announce (uploadBlob → app.aozora.embed.video → /videos) ===")
          (run! here ["clojure" "-M:dev" "-m" "minidrama.announce" mp4 id title])
          (println "done:" id "→ https://aozora.app/videos"))
      (do (println "=== preview (no --announce) ===")
          (println "video:" mp4)
          (let [plan (edn/read-string (slurp plan-file))]
            (println "title:" (:title plan) "| shots:" (:shots plan)
                     "| duration:" (:duration plan) "s"))
          (println "announce するには --announce を付けて再実行 (per-episode sign-off)")))))
