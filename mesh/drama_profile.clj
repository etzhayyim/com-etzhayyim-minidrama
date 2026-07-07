;; drama_profile.clj — KOTOBA Mesh component (Clojure / kotoba-clj).
;;
;; HTTP-triggered. The manifest binds this to route "/minidrama/profile"; an
;; HTTP request to `/mesh/http/minidrama/profile` on a hosting node invokes the
;; `on-http` export with the request bytes, and its output becomes the response.
;;
;; It PUBLISHES the actor's identity (the same projected identity registered in
;; aozora.appview.creator-actors, ADR-2607071300) as content-addressed datoms
;; and serves it over HTTP. It never proposes, generates, or publishes drama —
;; those are governor-gated and stay on the off-mesh JVM actor.
;;
;; host-imports used:  kqe-assert! / kqe-query  → kotoba:kais/kqe
(ns drama-profile)

;; generic invoke / placement probe — assert the identity facts so the hosting
;; node's Datom log carries the profile as content-addressed, queryable state.
(defn run [_ctx]
  (kqe-assert! "g" "minidrama" "handle" "minidrama.aozora.app")
  (kqe-assert! "g" "minidrama" "did" "did:web:etzhayyim.github.io:com-etzhayyim-minidrama")
  (kqe-assert! "g" "minidrama" "registry" "aozora.appview.creator-actors")
  (kqe-query "profile(?k,?v) :- minidrama(?k,?v)."))

;; HTTP trigger: request bytes → the actor's profile record (EDN). Audit the
;; read as a datom, then answer with the static identity record.
(defn on-http [_req]
  (kqe-assert! "g" "minidrama-profile" "served" "profile")
  "{:actor \"minidrama\" :handle \"minidrama.aozora.app\" :did \"did:web:etzhayyim.github.io:com-etzhayyim-minidrama\" :registry \"aozora.appview.creator-actors\" :role \"vertical mini-drama production (DramaLLM governed by DramaGovernor)\" :publishes \"app.aozora.embed.video -> aozora /videos\"}")
