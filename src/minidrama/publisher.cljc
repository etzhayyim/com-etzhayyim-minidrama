(ns minidrama.publisher
  "Publisher — the outbound surface for a minidrama episode announcement,
  injected so the network is a swap (MockPublisher default ‖ real app-aozora
  createRecord follow-up, mirroring tashikame.aozora). The graph never reaches
  the network directly; :commit calls `(publish! publisher record)` only after
  the DramaGovernor passed AND the phase/approval gate allows announcement
  (minidrama.phase, ADR-2607071300).

  record shape (what gets announced):
    {:episode-id :title :logline :text (social-post body)
     :visibility :unlisted|:public
     :collection \"com.etzhayyim.apps.minidrama.episode\"}

  Once produced media exists, the aozora announcement carries the
  app.aozora.embed.video embed ({:src <getBlob URL>} for VOD,
  {:playlist … :live true} for a live premiere — ADR-2607071000/2607071100).")

(def collection "com.etzhayyim.apps.minidrama.episode")

(defprotocol Publisher
  (publish! [p record] "announce one episode record → {:uri :cid}"))

(defrecord MockPublisher [a]
  Publisher
  (publish! [_ record]
    (swap! a conj record)
    {:uri (str "at://mock/minidrama/" (:episode-id record))
     :cid (str "mock:" (:episode-id record))}))

(defn mock-publisher
  "Deterministic in-memory publisher (default — records would-be posts).
  Optional atom arg lets a test read back what would have been announced."
  ([] (->MockPublisher (atom [])))
  ([a] (->MockPublisher a)))
