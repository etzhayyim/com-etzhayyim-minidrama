;; drama_heartbeat.clj — KOTOBA Mesh component (Clojure / kotoba-clj).
;;
;; TICK-triggered. The manifest fires `on-tick` every 1h on a hosting node. It
;; asserts a resident-liveness datom so the fleet's Datom log carries an
;; append-only as-of history that the minidrama actor is resident on the mesh —
;; murakumo's dash/reconcile observe placement; this records it as graph state.
;;
;; host-imports used:  kqe-assert! / kqe-query  → kotoba:kais/kqe
(ns drama-heartbeat)

(defn run [_ctx]
  (kqe-assert! "g" "minidrama-heartbeat" "alive" "resident"))

;; TICK trigger: record the beat, and re-assert the identity anchor so a node
;; that newly won the placement auction also carries the profile facts.
(defn on-tick [_now]
  (kqe-assert! "g" "minidrama-heartbeat" "alive" "tick")
  (kqe-assert! "g" "minidrama" "handle" "minidrama.aozora.app"))
