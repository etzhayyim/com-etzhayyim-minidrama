# com-etzhayyim-minidrama (ミニドラマ座)

縦型（720x1280）60〜90 秒ミニドラマの制作 actor。企画 → 脚本 → 絵コンテ
（shot list）までを DramaLLM が *proposal* として提案し、**DramaGovernor が
検閲**して可決分だけを append-only 台帳に commit する。可決済みプランは
genapp-clj 系 video エンジン（dougaka エンジン、follow-up）への work order
であり、公開は app-aozora `/videos`（`app.aozora.embed.video`、
ADR-2607071000/2607071100 経路）。

設計 正本: superproject
`90-docs/adr/2607071300-aozora-creator-actors-minidrama.md`（Part 2）。
actor identity: `minidrama.aozora.app`（projected、`aozora.appview.creator-actors`
registry — 鍵付き化は follow-up）。

## Overview

```
theme ──▶ :advise (DramaLLM, sealed) ──▶ :govern (DramaGovernor) ──▶ :decide
                                                        │
                :commit ◀── clean ──────────────────────┴── HARD ──▶ :hold
                  │  SSoT (episode plan) + ledger              ledger only
                  └─ phase/approval gate ──▶ Publisher (announce)
```

## StateGraph (one episode plan = one run)

`minidrama.operation/build` — intake → advise → govern → decide →
commit | hold。無限内部ループ無し。生成・合成はこの graph に含めない
（committed plan がエンジンへの発注書）。

## DramaGovernor gates (ADR-2607071300)

HARD → HOLD（台帳に記録、commit も announce もしない）:
`:no-actuation` `:over-duration`(>120s) `:too-many-shots`(>24)
`:overlong-shot`(>10s) `:content-veto`(Rider §2) `:likeness`
`:unprovenanced-asset` `:budget-exceeded` `:rate-limited`

SOFT → commit + タグ: `:low-confidence`

## Phase rollout

| phase | label | announce |
|---|---|---|
| 0 (default) | draft | しない（台帳のみ） |
| 1 | unlisted | 自動（unlisted preview、ADR の「unlisted までは自動可」） |
| 2 | public | **`:approvals #{:publish}` がある run だけ**（per-episode human sign-off） |

## Injected seams (each a swap, core unchanged)

- **Store** — `MemStore`（既定）‖ `DatomicStore`（langchain.db `:db-api`、
  kotoba-server pod へも同 record で接続可）
- **Advisor** — `mock-advisor`（既定、決定的）‖ `llm-advisor`
  （`langchain.model` ChatModel、Murakumo fleet 限定 `assert-murakumo!`）
- **Publisher** — `MockPublisher`（既定）‖ 実 app-aozora createRecord
  （follow-up、tashikame.aozora 同型）
- **Phase / approvals / budget / daily-cap** — run の `:context`

## Run

```bash
clojure -M:lint       # clj-kondo (errors fail)
clojure -M:dev:test   # cognitect test-runner
clojure -M:dev:run    # offline demo (mock advisor/publisher, MemStore)

# theme 一発でミニドラマを製造 (actor→dougaka engine→announce, ADR-2607071500):
bb scripts/produce-episode.bb --theme "屋上のラジオ体操" --duration 48            # preview (mp4 まで)
bb scripts/produce-episode.bb --theme "…" --announce   # 公開 = per-episode sign-off

# episodes/ の実写カタログ設計から製造 (手書き脚本も同じ DramaGovernor を通る):
bb scripts/produce-episode.bb --plan episodes/rooftop-3min.edn [--announce]
```

## episodes/ — 実写ミニドラマ設計カタログ (2026-07-07、実写前提)

11 本の手書き設計 (48-73s 縦型 / shot list + 台詞 + :speaker ヒント /
prompt は live-action cinematic)。全設計は `episode-designs-test` で
DramaGovernor + フォーマット不変条件を全数検証される — **governor を通らない
設計はカタログに置けない**。

| slug | title | genre | 尺 |
|---|---|---|---|
| okigasa | 置き傘の返し方 | 恋愛/日常 | 60s |
| receipt | 深夜のレシート | ミステリ | 70s |
| alarm-ai | AIに叱られる朝 | SFコメディ | 60s |
| rooftop-3min | 屋上、あと三分 | 青春 | 60s |
| wall-marks | 壁の背くらべ | 家族 | 65s |
| floor9 | 9階で止まる | ソフトホラー | 61s |
| gobaku | 誤爆 | お仕事コメディ | 48s |
| rain-books | 雨の日だけの古書店 | ファンタジー | 73s |
| recipe | レシピの最後の一行 | 家族/グルメ | 64s |
| teiki | 入れ替わった定期入れ | すれ違い恋愛 | 59s |
| starlight-bus | 終バスの天体観測 | ヒューマン | 60s |

実写前提のため、dougaka エンジン側は photoreal 系 checkpoint を
`DOUGAKA_DEFAULT_CKPT` で指定して製造する (エンジン既定は env 差し替え可)。

## ON-MESH surface (reside facet, ADR-2607071500)

`mesh/minidrama.app.edn` は kotoba WASM mesh 上の常駐面（kenchi-valuation 同型の
split of duties）。drama pipeline（LLM 提案・governor・生成・publish）は
OFF-MESH の JVM actor に残し、mesh guest は identity/liveness のみ扱う:

- `mesh/drama_profile.clj` — `on-http /minidrama/profile` で actor の
  identity record（handle / did / registry）を応答し、profile datom を assert
- `mesh/drama_heartbeat.clj` — `on-tick` 毎時、resident-liveness datom を
  append（fleet Datom log に as-of 履歴が残る）

deploy は murakumo 側から: `kotoba-lang/murakumo` の `murakumo.app.edn` に
fleet app として登録済み — `bb murakumo deploy mesh/minidrama.app.edn <node>`
（1回）または `bb reconcile murakumo.app.edn --apply`（宣言的収束）。

## Related files

- `src/minidrama/operation.cljc` — StateGraph
- `src/minidrama/governor.cljc` — DramaGovernor
- `src/minidrama/advisor.cljc` — DramaLLM (mock ‖ Murakumo LLM)
- `src/minidrama/store.cljc` — Store (MemStore ‖ DatomicStore)
- `src/minidrama/publisher.cljc` — Publisher (Mock ‖ aozora follow-up)
- `src/minidrama/phase.cljc` — phase 0 draft / 1 unlisted / 2 public+approval
- `docs/adr/0001-architecture.md` — repo-local design note
- `mesh/minidrama.app.edn` — ON-MESH surface manifest (reside facet)
- `mesh/drama_profile.clj` / `mesh/drama_heartbeat.clj` — kotoba-clj mesh guests
