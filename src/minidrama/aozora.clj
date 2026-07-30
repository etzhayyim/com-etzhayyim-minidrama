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
            [minidrama.repo-signer :as repo-signer]
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

(defn register-handle!
  "Bind the actor's aozora handle to its OWN did:key on the PDS
  (com.atproto.identity.updateHandle — self-scoped: the authenticated session
  DID claims its own handle, so only the key holder can bind it). This is the
  keyed-flip step (ADR-2607070400 系列): after it, resolveHandle returns the
  did:key instead of falling open to did:web:<handle>, and the appview
  attributes the actor's real records to the friendly handle.
  Returns {:handle :did}."
  [{:keys [pds identity handle json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (assert (:did identity) ":identity with :did is required")
  (assert handle ":handle is required")
  (let [jwt  (session-jwt! {:pds pds :identity identity
                            :json-write json-write :json-read json-read
                            :http-fn http-fn})
        resp (http-fn {:url     (str pds "/xrpc/com.atproto.identity.updateHandle")
                       :method  :post
                       :headers {"Content-Type" "application/json"
                                 "Authorization" (str "Bearer " jwt)}
                       :body    (json-write {:handle handle})})
        body (json-read (:body resp))]
    (when-not (= 200 (:status resp))
      (throw (ex-info "aozora updateHandle failed"
                      {:status (:status resp) :body (:body resp)})))
    {:handle (get body "handle") :did (get body "did")}))

(defn create-account!
  "com.atproto.server.createAccount — prove the actor's did:key via a fresh
  self-sovereign CACAO and persist the `:atproto.account/*` (+ handle) datom
  on the PDS. This is the account-store 整合 step of the keyed flip
  (ADR-2607071700 follow-up; ADR-2607070400 系列の createAccount 昇格):
  createSession/updateHandle already work keyless-store because the CACAO is
  the proof, but a first-class account record makes getAccount answer for the
  actor. Idempotent (re-transacting the account entity is a no-op upsert).
  Returns {:did :handle}."
  [{:keys [pds identity handle json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (assert (:did identity) ":identity with :did is required")
  (assert handle ":handle is required")
  (let [now   (str (Instant/now))
        graph (cacao/canonical-graph (:did identity) cacao/default-db-name)
        cacao (cacao/mint identity
                          {:cap :cap/transact :scope graph}
                          {:aud pds :nonce (str (UUID/randomUUID))
                           :issued-at now
                           :expiry (str (.plusSeconds (Instant/now) 3600))})
        resp  (http-fn {:url     (str pds "/xrpc/com.atproto.server.createAccount")
                        :method  :post
                        :headers {"Content-Type" "application/json"}
                        :body    (json-write {:handle handle :cacao cacao})})
        body  (json-read (:body resp))]
    (when-not (= 200 (:status resp))
      (throw (ex-info "aozora createAccount failed"
                      {:status (:status resp) :body (:body resp)})))
    {:did (get body "did") :handle (get body "handle")}))

;; ── federation: did:web repo identity + signed commits ───────────────────────
;;
;; Why any of this exists: records written through com.atproto.repo.createRecord
;; persist and read back fine, but the repo never acquires a signed commit head,
;; so com.atproto.sync.listRepos filters it out (aozora.pds.repo/list-repos only
;; advertises a did whose head AND rev are present) and no Relay ever learns the
;; repo exists. Verified on the live PDS: getLatestCommit answered RepoNotFound
;; for this actor while its episodes were readable the whole time.
;;
;; Our did:key identity cannot carry a commit key: a commit signature must be
;; secp256k1, our did:key is Ed25519 (a CACAO issuer has to be), and did:key has
;; no DID document to publish a second key in. So the repo becomes a did:web
;; whose CONTROLLER is the Ed25519 key we already hold — the wallet key is kept,
;; not rotated; only a signing key is added (minidrama.repo-signer).

(defn repo-did
  "did:web:pds.aozora.app:<slug> — the identity shape createAccount accepts for
  a federatable repo (its valid-repo-did? is
  did:web:pds\\.aozora\\.app:[a-z0-9][a-z0-9-]{0,62})."
  [slug]
  (str "did:web:pds.aozora.app:" slug))

(defn claim-repo!
  "com.atproto.server.createAccount for a did:web repo controlled by our
  Ed25519 identity, with `signer`'s public half as the repo signing key.

  The CACAO must additionally carry kotoba://can/aozora:repo:<repo-did> or the
  PDS refuses with AuthFailed — that resource is what authorises minting THIS
  repo DID rather than any other. Re-running with the SAME identity is allowed
  (idempotent, and how the signing key is rotated); a different key is refused,
  which is what stops anyone else claiming our slug.
  Returns {:did :handle :controllerDid :accessJwt}."
  [{:keys [pds identity signer slug handle json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (assert (:did identity) ":identity is required")
  (assert (:multikey signer) ":signer from repo-signer/load-or-create-key! is required")
  (assert slug ":slug is required")
  (let [rdid  (repo-did slug)
        graph (cacao/canonical-graph (:did identity) cacao/default-db-name)
        cacao (cacao/mint identity
                          {:cap :cap/transact :scope graph
                           :extra [(str "kotoba://can/aozora:repo:" rdid)]}
                          {:aud pds :nonce (str (UUID/randomUUID))
                           :issued-at (str (Instant/now))
                           :expiry (str (.plusSeconds (Instant/now) 3600))})
        resp  (http-fn {:url     (str pds "/xrpc/com.atproto.server.createAccount")
                        :method  :post
                        :headers {"Content-Type" "application/json"}
                        :body    (json-write {:handle (or handle (str slug ".aozora.app"))
                                              :cacao cacao
                                              :repoDid rdid
                                              :signingKey (:multikey signer)})})
        body  (json-read (:body resp))]
    (when-not (and (= 200 (:status resp)) (nil? (get body "error")))
      (throw (ex-info "aozora claim-repo! failed"
                      {:status (:status resp) :body (:body resp)})))
    {:did (get body "did") :handle (get body "handle")
     :controllerDid (get body "controllerDid") :accessJwt (get body "accessJwt")}))

(defn publish-signing-key!
  "app.aozora.repo.publishKey — register the signing key's PUBLIC half so the
  PDS serves it at /<slug>/did.json. Without it didkey/resolve-pub-bytes has
  nothing to verify our commits against and commitSigned fails closed."
  [{:keys [pds jwt signer repo json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (let [resp (http-fn {:url     (str pds "/xrpc/app.aozora.repo.publishKey")
                       :method  :post
                       :headers {"Content-Type" "application/json"
                                 "Authorization" (str "Bearer " jwt)}
                       :body    (json-write {:repo repo :publicKeyHex (:pub-hex signer)})})
        body (json-read (:body resp))]
    (when-not (and (= 200 (:status resp)) (nil? (get body "error")))
      (throw (ex-info "aozora publishKey failed"
                      {:status (:status resp) :body (:body resp)})))
    {:did (get body "did") :publicKeyMultibase (get body "publicKeyMultibase")}))

(defn signed-write!
  "Two-phase no-server-key write: prepareWrite → sign locally → commitSigned.

  The record map is built ONCE and sent to both phases byte-identically; the
  server re-derives the commit from it in phase 2, so any difference (a
  regenerated timestamp is the easy mistake) changes the bytes under the
  signature and the write is rejected as InvalidSignature.
  Returns {:uri :cid :commit :rev}."
  [{:keys [pds jwt signer repo collection rkey record json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (let [post (fn [nsid body]
               (let [r (http-fn {:url (str pds "/xrpc/" nsid) :method :post
                                 :headers {"Content-Type" "application/json"
                                           "Authorization" (str "Bearer " jwt)}
                                 :body (json-write body)})
                     b (json-read (:body r))]
                 (when-not (and (= 200 (:status r)) (nil? (get b "error")))
                   (throw (ex-info (str "aozora " nsid " failed")
                                   {:status (:status r) :body (:body r)})))
                 b))
        prep (post "app.aozora.repo.prepareWrite"
                   (cond-> {:repo repo :collection collection :action "create" :record record}
                     rkey (assoc :rkey rkey)))
        unsigned (.decode (java.util.Base64/getDecoder) ^String (get prep "unsigned"))
        sig  (repo-signer/sign-compact signer unsigned)
        done (post "app.aozora.repo.commitSigned"
                   {:repo repo :collection collection :rkey (get prep "rkey")
                    :rev (get prep "rev") :sig sig :action "create" :record record})]
    {:uri (get done "uri") :cid (get done "cid")
     :commit (get done "commit") :rev (get done "rev")}))
