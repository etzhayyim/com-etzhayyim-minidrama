(ns minidrama.deploy
  "Deploy entrypoint — wires a REAL Murakumo-fleet LLM (langchain.model
  OpenAI-compatible against the local Ollama) into the minidrama advisor and
  runs ONE episode plan end-to-end.

  Publication is MockPublisher by default: a real aozora announcement needs
  (a) the actor's did registered on the PDS, (b) phase ≥1 (unlisted) or, for
  phase 2 public, the per-episode :publish approval in the run context
  (ADR-2607071300 gate ④), and (c) the real Publisher wired via
  `minidrama.aozora`. This entrypoint proves the real-LLM → governor →
  (mock) announce path against the live Murakumo model.

  Usage: clojure -M:dev -m minidrama.deploy \"<theme>\" [duration-seconds]
         clojure -M:dev -m minidrama.deploy identify-live
         clojure -M:dev -m minidrama.deploy register-handle
  Env:   MINIDRAMA_OLLAMA_URL (default http://127.0.0.1:11434)
         MINIDRAMA_OLLAMA_MODEL (default gemma-4-E4B qat)"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [langchain.model :as model]
            [langgraph.graph :as g]
            [minidrama.advisor :as advisor]
            [minidrama.aozora :as aozora]
            [minidrama.cacao :as cacao]
            [minidrama.publisher :as publisher]
            [minidrama.store :as store]
            [minidrama.operation :as op])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers])
  (:gen-class))

(def ^:private default-ollama-url
  (or (System/getenv "MINIDRAMA_OLLAMA_URL") "http://127.0.0.1:11434"))

(def ^:private default-ollama-model
  (or (System/getenv "MINIDRAMA_OLLAMA_MODEL")
      "hf.co/unsloth/gemma-4-E4B-it-qat-GGUF:UD-Q4_K_XL"))

(defn jvm-http-fn
  "langchain.model :http-fn backed by the JDK HTTP client (no dependency)."
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

(defn ollama-chat-model
  "Build a langchain.model/openai-model against a Murakumo-fleet Ollama.
  Refuses non-Murakumo hosts (Rider §2(i))."
  ([]
   (ollama-chat-model default-ollama-url default-ollama-model))
  ([ollama-url ollama-model]
   (advisor/assert-murakumo! ollama-url)
   (model/openai-model
    {:url        (str ollama-url "/v1/chat/completions")
     :model      ollama-model
     :api-key    nil
     :http-fn    jvm-http-fn
     :json-write json/write-str
     :json-read  #(json/read-str % :key-fn keyword)})))

(defn identify-live
  "Live identify test: generate the actor's self-sovereign did:key, then
  createSession(self-CACAO)→JWT→createRecord a profile record to
  pds.aozora.app. Proves the app-aozora-pds auth flow for minidrama.
  clojure -M:dev -m minidrama.deploy identify-live"
  []
  (let [id  (cacao/load-or-create-identity! ".minidrama/identity.edn")
        pub (aozora/aozora-publisher {:pds        "https://pds.aozora.app"
                                      :identity   id
                                      :json-write json/write-str
                                      :json-read  json/read-str})
        profile {:$type       "com.etzhayyim.apps.minidrama.profile"
                 :collection  "com.etzhayyim.apps.minidrama.profile"
                 :rkey        "self"
                 :displayName "ミニドラマ座 — Vertical Mini-Drama Production Actor"
                 :description "minidrama (ミニドラマ座) live identify via createSession→createRecord (self-sovereign did:key). Registry handle: minidrama.aozora.app (ADR-2607071300)."
                 :lexicons    ["com.etzhayyim.apps.minidrama.episode"]}]
    (println "actor did:key :" (:did id))
    (println "createSession→createRecord profile @ pds.aozora.app, repo=" (:did id))
    (try
      (let [r (publisher/publish! pub profile)] (println "PUBLISHED:" r))
      (catch Exception e
        (println "FAILED:" (ex-message e) (pr-str (ex-data e)))))))

(defn register-handle
  "Keyed flip (ADR-2607070400 系列): bind minidrama.aozora.app to the actor's
  own did:key on the PDS via com.atproto.identity.updateHandle. After this,
  resolveHandle returns the did:key (not the did:web fallback) and the appview
  attributes the actor's real records to the friendly handle.
  clojure -M:dev -m minidrama.deploy register-handle"
  []
  (let [id (cacao/load-or-create-identity! ".minidrama/identity.edn")]
    (println "actor did:key :" (:did id))
    (println "updateHandle minidrama.aozora.app → " (:did id) "@ pds.aozora.app")
    (try
      (let [r (aozora/register-handle! {:pds        "https://pds.aozora.app"
                                        :identity   id
                                        :handle     "minidrama.aozora.app"
                                        :json-write json/write-str
                                        :json-read  json/read-str})]
        (println "REGISTERED:" r))
      (catch Exception e
        (println "FAILED:" (ex-message e) (pr-str (ex-data e)))))))

(defn -main
  [& args]
  (when (= (first args) "identify-live") (identify-live) (System/exit 0))
  (when (= (first args) "register-handle") (register-handle) (System/exit 0))
  (let [[theme dur] (if (seq args) args ["終電を逃した二人の五分間" nil])
        chat    (ollama-chat-model)
        adv     (advisor/llm-advisor chat {:max-tokens 1024})
        s       (store/seed-db)
        pub     (publisher/mock-publisher)
        actor   (op/build s {:advisor adv :publisher pub})
        eid     "deploy-1"
        req     {:op :episode/plan :episode-id eid :theme theme
                 :duration-target (when dur (parse-long dur))}
        r       (g/run* actor {:request req :context {:actor-id "minidrama" :phase 1}}
                         {:thread-id eid})]
    (println "=== minidrama deploy (real LLM @ Murakumo) ===")
    (println "theme      :" theme)
    (println "disposition:" (get-in r [:state :disposition]))
    (println "title      :" (:title (store/episode s eid)))
    (println "shots      :" (:shots (store/episode s eid))
             "duration:" (:duration (store/episode s eid)) "s")
    (println "announced? :" (boolean (get-in r [:state :published])) "(mock publisher)")
    (println "ledger tail:" (pr-str (last (store/ledger s))))))
