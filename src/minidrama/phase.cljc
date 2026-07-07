(ns minidrama.phase
  "Phase 0→2 staged rollout for minidrama (ADR-2607071300). Unlike the
  fully-autonomous publishers (tashikame, ADR-2606281500), minidrama's own ADR
  keeps PUBLIC publication behind an explicit human approval — the phase gate
  can only ever withhold announcement, never force it, and a governor-held
  plan is never announced in any phase.

    Phase 0  draft      — governor-clean plans recorded to the ledger only
                          (no announcement). DEFAULT.
    Phase 1  unlisted   — governor-clean plans announce as unlisted previews
                          (自動可 per the ADR).
    Phase 2  public     — public announcement, and ONLY with an explicit
                          :publish approval in the run context (per-episode
                          human sign-off, ADR-2607071300 governor gate ④)."
  )

(def phases
  {0 {:label "draft"    :publish? false :needs-approval? false}
   1 {:label "unlisted" :publish? true  :needs-approval? false}
   2 {:label "public"   :publish? true  :needs-approval? true}})

(def default-phase 0)

(defn publish-allowed?
  "May a governor-clean episode be announced under `phase` with the run's
  `approvals` set? Phase 2 requires the explicit :publish approval."
  [phase approvals]
  (let [{:keys [publish? needs-approval?]}
        (get phases phase (get phases default-phase))]
    (boolean (and publish?
                  (or (not needs-approval?)
                      (contains? (set approvals) :publish))))))
