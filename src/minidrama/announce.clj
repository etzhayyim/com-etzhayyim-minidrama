(ns minidrama.announce
  "Announce leg (ADR-2607071300 follow-up「pipeline 発注の自動化」の後半):
  a produced episode mp4 → PDS uploadBlob (R2-backed, ADR-2607071000) →
  app.bsky.feed.post with the app.aozora.embed.video direct-URL embed —
  exactly the shape aozora /videos plays (ADR-2607062100) and the appview
  getVideoFeed filter matches server-side.

  Doctrine: this module only ASSEMBLES and SENDS the announcement; whether an
  episode may be announced at all is the DramaGovernor + phase/approval gate's
  decision upstream (minidrama.operation / minidrama.phase). The -main here is
  the operator verification entrypoint (owner-driven), mirroring
  deploy/identify-live.

  Usage: clojure -M:dev -m minidrama.announce <episode.mp4> [episode-id] [title]"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [minidrama.aozora :as aozora]
            [minidrama.cacao :as cacao]
            [minidrama.publisher :as publisher])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers])
  (:gen-class))

(def default-pds aozora/default-pds)

(defn upload-blob!
  "POST the mp4 bytes to com.atproto.repo.uploadBlob → {:blob ref}. Raw-body
  JDK http (the JSON http-fn can't carry binary). `jwt` (session JWT) is
  required by the PDS when PDS_REQUIRE_AUTH=1 (ADR-2607071000 follow-up)."
  [{:keys [pds path mime jwt] :or {pds default-pds mime "video/mp4"}}]
  (let [bytes (with-open [in (io/input-stream path)
                          out (java.io.ByteArrayOutputStream.)]
                (io/copy in out)
                (.toByteArray out))
        req (-> (HttpRequest/newBuilder (URI/create (str pds "/xrpc/com.atproto.repo.uploadBlob")))
                (.header "Content-Type" mime)
                (cond-> jwt (.header "Authorization" (str "Bearer " jwt)))
                (.method "POST" (HttpRequest$BodyPublishers/ofByteArray bytes))
                (.build))
        resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode resp))
      (throw (ex-info "uploadBlob failed" {:status (.statusCode resp) :body (.body resp)})))
    (json/read-str (.body resp) :key-fn keyword)))

(defn blob-url [pds cid]
  (str pds "/xrpc/com.atproto.sync.getBlob?cid=" cid))

(def preview-collection
  "Unlisted previews (phase 1, ADR-2607162200 Layer D): same record shape but
  a non-feed collection — the appview video/author feeds only scan
  app.bsky.feed.post, so a preview never surfaces publicly while still being
  fetchable by URI for review."
  "com.etzhayyim.apps.minidrama.preview")

(defn announce-record
  "app.bsky.feed.post record announcing one produced episode — the embed is
  app.aozora.embed.video {:src <getBlob URL>} (direct URL, /videos-playable).
  :visibility :unlisted swaps the collection to `preview-collection`."
  [{:keys [pds episode-id title text blob visibility]
    :or {pds default-pds}}]
  (let [cid (get-in blob [:ref :$link])]
    {:$type "app.bsky.feed.post"
     :collection (if (= :unlisted visibility) preview-collection "app.bsky.feed.post")
     :rkey episode-id
     :text (or text (str "【ミニドラマ座】『" title "』"))
     :embed {:$type "app.aozora.embed.video"
             :src (blob-url pds cid)
             :mimeType (:mimeType blob)
             :video blob
             :aspectRatio {:width 720 :height 1280}}}))

(defn announce!
  "Upload the mp4 + create the announce post as the actor's own did:key.
  Returns {:blob-cid :post {:uri :cid}}."
  [{:keys [pds identity path episode-id title text visibility]
    :or {pds default-pds}}]
  (let [jwt (aozora/session-jwt! {:pds pds :identity identity
                                  :json-write json/write-str
                                  :json-read json/read-str})
        {:keys [blob]} (upload-blob! {:pds pds :path path :jwt jwt})
        pub (aozora/aozora-publisher {:pds pds :identity identity
                                      :json-write json/write-str
                                      :json-read json/read-str})
        rec (announce-record {:pds pds :episode-id episode-id
                              :title title :text text :blob blob
                              :visibility visibility})]
    {:blob-cid (get-in blob [:ref :$link])
     :src (blob-url pds (get-in blob [:ref :$link]))
     :post (publisher/publish! pub rec)}))

(defn -main [& [path episode-id title visibility]]
  (when-not (and path (.exists (io/file path)))
    (binding [*out* *err*] (println "usage: clojure -M:dev -m minidrama.announce <episode.mp4> [episode-id] [title] [public|unlisted]"))
    (System/exit 1))
  (let [id (cacao/load-or-create-identity! ".minidrama/identity.edn")
        episode-id (or episode-id "demo-1")
        title (or title "デモ")
        vis (if (= "unlisted" visibility) :unlisted :public)
        r (announce! {:identity id :path path :episode-id episode-id
                      :title title :visibility vis
                      :text (str "【ミニドラマ座】『" title "』")})]
    (println "actor      :" (:did id))
    (println "visibility :" (name vis))
    (println "blob cid   :" (:blob-cid r))
    (println "video src  :" (:src r))
    (println "post       :" (:post r))))
