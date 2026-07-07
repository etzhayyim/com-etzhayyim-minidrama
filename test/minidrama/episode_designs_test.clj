(ns minidrama.episode-designs-test
  "episodes/ の実写ミニドラマ設計カタログを、DramaLLM 提案と同一の検閲
  (DramaGovernor) + フォーマット不変条件で全数検証する。設計が governor を
  通らないなら、それは出荷できない設計である。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [minidrama.advisor :as advisor]
            [minidrama.governor :as governor]
            [minidrama.produce :as produce]))

(defn- designs []
  (->> (.listFiles (io/file "episodes"))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (map #(edn/read-string (slurp %)))
       (sort-by :episode-id)))

(deftest catalog-has-about-ten-designs
  (is (<= 10 (count (designs)) 12)))

(deftest every-design-passes-the-drama-governor
  (doseq [{:keys [episode-id] :as d} (designs)]
    (testing episode-id
      (let [{:keys [disposition basis]}
            (produce/produce-plan! {:theme (:title d)
                                    :episode-id episode-id
                                    :advisor (produce/design-advisor d)})]
        (is (= :commit disposition) (pr-str basis))))))

(deftest every-design-meets-format-invariants
  (doseq [{:keys [episode-id duration-target premise scenes] :as d} (designs)]
    (testing episode-id
      (let [ep (select-keys d [:title :logline :scenes])
            total (advisor/shot-total ep)
            shots (for [sc scenes sh (:shots sc)] sh)]
        (is (= :live-action premise) "実写前提 (owner 指示 2026-07-07)")
        (is (= (double duration-target) total)
            "shot durations は duration-target にぴったり一致")
        (is (<= 45 total 90) "縦型ショートの尺帯")
        (is (<= (count shots) governor/max-shots))
        (is (every? #(<= (double (:duration %)) governor/max-shot-duration) shots))
        (is (every? #(str/includes? (:prompt %) "live-action") shots)
            "全 shot prompt が実写指定")
        (is (every? #(seq (str/trim (or (:subtitle %) ""))) shots)
            "全 shot に台詞/字幕 (voice レグが喋る)")
        (is (every? #(keyword? (:speaker %)) shots)
            "話者ヒント (将来の VOICEVOX 話者演じ分け)")))))
