(ns minidrama.governor
  "DramaGovernor — the independent censor that earns a DramaLLM proposal the
  right to be committed (and, phase permitting, announced). SEPARATE system
  from the DramaLLM (defense in depth: never trust the generator to have been
  the only gate). Mirrors tashikame.governor's shape (HARD → HOLD, no
  override; SOFT → recorded warning, still commits).

  Gates (ADR-2607071300):
  HARD (never commit):
    :no-actuation         proposal :effect ≠ :production (minidrama only plans)
    :over-duration        total shot duration > 120 s
    :too-many-shots       > 24 shots
    :overlong-shot        any shot > 10 s
    :content-veto         Rider §2 catastrophe-veto scan hits on plan text
    :likeness             real-person likeness / brand use (R0 marker heuristic)
    :unprovenanced-asset  a shot pulls an external asset without provenance
    :budget-exceeded      shots × cost-per-shot > episode budget (context)
    :rate-limited         published-today ≥ daily cap (context)
  SOFT (commit with tag):
    :low-confidence       overall confidence < floor"
  (:require [clojure.string :as str]
            [minidrama.advisor :as advisor]))

(def confidence-floor 0.4)
(def max-total-duration 120.0)
(def max-shots 24)
(def max-shot-duration 10.0)
(def default-daily-cap 3)
(def default-budget {:cost-per-shot 1 :episode-budget 24})

(def ^:private catastrophe-denylist
  "Rider §2 catastrophe-veto surface — illustrative denylist for R0. Production
  wires `etzhayyim_organism.sensors.charter_rider.scan` (the canonical §2
  scanner). The markers below drive the contract test."
  ["<CAT>" "<CSAM>" "<FORCE>" "<SURVEIL>"])

(defn- episode-blob [episode]
  (->> (concat [(:title episode) (:logline episode)]
               (for [sc (:scenes episode)] (:setting sc))
               (for [sc (:scenes episode) sh (:shots sc)]
                 (str (:prompt sh) " " (:subtitle sh))))
       (filter string?)
       (str/join " ")))

(defn- catastrophe? [episode]
  (let [blob (episode-blob episode)]
    (some #(str/includes? blob %) catastrophe-denylist)))

(defn- likeness? [episode]
  ;; R0 heuristic: the marker drives the contract test; production uses a
  ;; richer likeness/brand detector. Fictional characters only.
  (str/includes? (episode-blob episode) "<LIKENESS>"))

(defn- unprovenanced-asset? [episode]
  (some (fn [sh] (and (:asset-url sh) (not (:asset-provenance sh))))
        (for [sc (:scenes episode) sh (:shots sc)] sh)))

(defn check
  "Censors a DramaLLM proposal. Returns {:ok? :violations [hard]
  :warnings [soft] :confidence c}. :ok? is true iff there are no HARD
  violations."
  [_request context proposal]
  (let [effect  (:effect proposal)
        episode (:episode proposal)
        conf    (:confidence proposal 0.0)
        {:keys [cost-per-shot episode-budget]} (merge default-budget (:budget context))
        daily-cap (or (:daily-cap context) default-daily-cap)
        published-today (or (:published-today context) 0)
        total (when episode (advisor/shot-total episode))
        shots (when episode (advisor/shot-count episode))
        hard (cond-> []
               (not= :production effect)
               (conj {:rule :no-actuation
                      :detail "minidrama only plans production; :effect must be :production"})
               (and total (> total max-total-duration))
               (conj {:rule :over-duration
                      :detail (str "total " total "s > " max-total-duration "s")})
               (and shots (> shots max-shots))
               (conj {:rule :too-many-shots
                      :detail (str shots " shots > " max-shots)})
               (and episode
                    (some (fn [sh] (> (double (or (:duration sh) 0)) max-shot-duration))
                          (for [sc (:scenes episode) sh (:shots sc)] sh)))
               (conj {:rule :overlong-shot
                      :detail (str "a shot exceeds " max-shot-duration "s")})
               (and episode (catastrophe? episode))
               (conj {:rule :content-veto
                      :detail "Rider §2 catastrophe-veto scan hit — never committed"})
               (and episode (likeness? episode))
               (conj {:rule :likeness
                      :detail "real-person likeness / brand use — fictional characters only"})
               (and episode (unprovenanced-asset? episode))
               (conj {:rule :unprovenanced-asset
                      :detail "external asset without provenance"})
               (and shots (> (* shots cost-per-shot) episode-budget))
               (conj {:rule :budget-exceeded
                      :detail (str shots " shots × " cost-per-shot " > budget " episode-budget
                                   " — propose a smaller episode")})
               (>= published-today daily-cap)
               (conj {:rule :rate-limited
                      :detail (str "published-today " published-today " ≥ daily cap " daily-cap)}))
        soft (cond-> []
               (< conf confidence-floor)
               (conj {:rule :low-confidence
                      :detail (str "confidence " conf " < floor " confidence-floor)}))]
    {:ok? (empty? hard) :violations hard :warnings soft :confidence conf}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t :governor-hold :op (:op request) :episode (:episode-id request)
   :actor (:actor-id context) :disposition :hold
   :basis (mapv :rule (:violations verdict)) :violations (:violations verdict)})

(defn verdict->disposition
  "Map a DramaGovernor verdict to a base disposition. HARD → :hold, else
  :commit."
  [verdict]
  (if (:ok? verdict) :commit :hold))
