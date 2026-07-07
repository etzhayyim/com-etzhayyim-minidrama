(ns minidrama.aozora
  "Real app-aozora Publisher for minidrama — creates a record in the
  com.etzhayyim.apps.minidrama.episode collection on an aozora PDS via the
  AT Protocol com.atproto.repo.createRecord XRPC, authenticated by a depth-1
  self-minted CACAO (the actor's own did:key). Ported from tashikame.aozora
  (keep in sync — same createSession(self-CACAO)→JWT→createRecord flow that
  app-aozora-pds enforces, ADR-2606251700 / DEPLOY-RUNBOOK).

  When produced media exists, the episode record carries the
  app.aozora.embed.video embed ({:src <getBlob URL>} for VOD,
  {:playlist … :live true} for a live premiere — ADR-2607071000/2607071100),
  so the announcement plays in aozora /videos.

  I/O is injected: an http-fn (default JDK java.net.http, no dependency) and a
  JSON pair passed by the caller, so this namespace stays dependency-free.
  ANNOUNCEMENT here still sits behind the DramaGovernor + phase/approval gate
  (minidrama.operation) — phase 2 public requires the per-episode :publish
  approval (ADR-2607071300 gate ④)."
  (:require [clojure.string :as str]
            [minidrama.cacao :as cacao]
            [minidrama.publisher :as publisher])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Instant]
           [java.util UUID]))

(def default-pds "https://pds.aozora.app")

(defn jvm-http-fn
  "host-caps :http-fn backed by the JDK HTTP client (no dependency)."
  [{:keys [url method headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers] (.header b k v))
    (let [req  (-> b (.method (str/upper-case (name (or method :post)))
                             (if body
                               (HttpRequest$BodyPublishers/ofString body)
                               (HttpRequest$BodyPublishers/noBody)))
                   (.build))
          resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(defn session-jwt!
  "app-aozora-pds auth (self-sovereign CACAO, ADR-2606251700): mint a CACAO
  for the actor's OWN did:key, exchange it at createSession for an HS256
  session JWT — the PDS enforces session DID == repo DID, and uploadBlob
  requires a valid session when PDS_REQUIRE_AUTH=1 (ADR-2607071000 follow-up)."
  [{:keys [pds identity json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (let [now   (str (Instant/now))
        graph (cacao/canonical-graph (:did identity) cacao/default-db-name)
        cacao (cacao/mint identity
                          {:cap :cap/transact :scope graph}
                          {:aud pds :nonce (str (UUID/randomUUID))
                           :issued-at now
                           :expiry (str (.plusSeconds (Instant/now) 3600))})
        sess  (http-fn {:url     (str pds "/xrpc/com.atproto.server.createSession")
                        :method  :post
                        :headers {"Content-Type" "application/json"}
                        :body    (json-write {:cacao cacao})})
        sbody (json-read (:body sess))
        jwt   (get sbody "accessJwt")]
    (when-not (and (= 200 (:status sess)) jwt)
      (throw (ex-info "aozora createSession failed"
                      {:status (:status sess) :body (:body sess)})))
    jwt))

(defn aozora-publisher
  "Returns a `minidrama.publisher/Publisher` that creates episode records on
  the aozora PDS. opts:
    :pds         PDS base URL (default default-pds)
    :identity    {:private-key :did …} from cacao/load-or-create-identity!
    :json-write  :json-read  injected JSON fns (e.g. clojure.data.json)
    :http-fn     optional override (default jvm-http-fn)"
  [{:keys [pds identity json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (assert (:did identity) ":identity with :did is required (cacao/load-or-create-identity!)")
  (assert json-write ":json-write fn is required (e.g. clojure.data.json/write-str)")
  (assert json-read  ":json-read fn is required (e.g. clojure.data.json/read-str)")
  (reify publisher/Publisher
    (publish! [_ record]
      (let [now (str (Instant/now))
            jwt (session-jwt! {:pds pds :identity identity
                               :json-write json-write :json-read json-read
                               :http-fn http-fn})
            coll  (or (:collection record) publisher/collection)
            rec   (-> (dissoc record :rkey :collection)
                        (assoc :createdAt now :actor (:did identity)))
            resp  (http-fn {:url     (str pds "/xrpc/com.atproto.repo.createRecord")
                            :method  :post
                            :headers {"Content-Type" "application/json"
                                      "Authorization" (str "Bearer " jwt)}
                            :body    (json-write {:repo       (:did identity)
                                                  :collection coll
                                                  :rkey       (or (:rkey record)
                                                                  (:episode-id record)
                                                                  "self")
                                                  :record     rec})})
            rbody (json-read (:body resp))]
          (when-not (= 200 (:status resp))
            (throw (ex-info "aozora createRecord failed"
                            {:status (:status resp) :body (:body resp)})))
          {:uri (get rbody "uri") :cid (get rbody "cid")}))))
