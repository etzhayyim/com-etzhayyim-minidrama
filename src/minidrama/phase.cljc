(ns minidrama.phase
  "Phase 0→2 staged rollout for minidrama (ADR-2607071300, publish policy
  revised by ADR-2607162200 Layer D). The phase gate can only ever withhold
  announcement, never force it, and a governor-held plan is never announced
  in any phase.

    Phase 0  draft      — governor-clean plans recorded to the ledger only
                          (no announcement). DEFAULT.
    Phase 1  unlisted   — governor-clean plans announce as unlisted previews
                          (自動可 per the ADR).
    Phase 2  public     — public announcement with an explicit approval in
                          the run context. Two grants exist:
                          :publish       per-episode human sign-off
                                         (ADR-2607071300 gate ④, unchanged)
                          :auto-publish  the scheduled outer loop's standing
                                         grant (ADR-2607162200: the 2026-07-10
                                         恒久承認 moved publication to agent
                                         judgment; the DramaGovernor's HARD
                                         gates — content-veto/likeness/
                                         provenance/budget/rate-cap — remain
                                         the escalation boundary: a HOLD is
                                         never announced, it is surfaced to
                                         the owner instead)."
  )

(def phases
  {0 {:label "draft"    :publish? false :needs-approval? false}
   1 {:label "unlisted" :publish? true  :needs-approval? false}
   2 {:label "public"   :publish? true  :needs-approval? true}})

(def default-phase 0)

(def publish-grants
  "Run-context approvals that satisfy phase 2's approval requirement."
  #{:publish :auto-publish})

(defn publish-allowed?
  "May a governor-clean episode be announced under `phase` with the run's
  `approvals` set? Phase 2 requires one of `publish-grants` (:publish =
  human sign-off, :auto-publish = scheduled-loop standing grant,
  ADR-2607162200)."
  [phase approvals]
  (let [{:keys [publish? needs-approval?]}
        (get phases phase (get phases default-phase))]
    (boolean (and publish?
                  (or (not needs-approval?)
                      (boolean (some publish-grants (set approvals))))))))
