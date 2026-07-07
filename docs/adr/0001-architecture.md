# ADR 0001: minidrama actor architecture (R0)

**Status**: accepted — 2026-07-07。設計の正本は superproject
`90-docs/adr/2607071300-aozora-creator-actors-minidrama.md`（Part 2）。この
repo-local note は実装が固定した点だけを記す。

## Topology

containment + independent governor + append-only ledger
（tashikame / tsumugu / sng と同型）:

- **DramaLLM**（`minidrama.advisor`、封じ込め）: theme → 縦型ミニドラマの
  production plan proposal（title/logline/scenes/shots）。proposal のみ —
  生成 job も公開も決して自分では行わない。
- **DramaGovernor**（`minidrama.governor`、別系統）: HARD → HOLD（no
  override）、SOFT → タグ付き commit。gates は README 参照。
- **台帳**（`minidrama.store`）: episode plan は `:minidrama.episode/id`、
  全 decision は `:minidrama.ledger/seq` の append-only fact。backend は
  langchain.db `:db-api` map 越しのみ（MemStore ≡ DatomicStore、contract
  test 保証。kotoba-server pod へは同 record + `kotoba-api` で接続）。

## R0 で固定した判断

1. **HITL は approval-in-context、interrupt-before ではない**。superproject
   ADR は publish 承認に interrupt-before を挙げたが、この actor family に
   interrupt-before の実装前例が無いため、R0 は「1 run = 1 操作」を保ち
   phase 2 の announce を `:approvals #{:publish}`（run context）で gate する。
   同じ不変条件（承認なしの public announce はしない）を、テスト済みの
   conditional-edge パターンだけで実現する。checkpointer resume ベースの
   interrupt-before 化は langgraph-clj 側に前例ができたら follow-up。
2. **生成・合成は graph 外**。committed plan が genapp-clj 系 video エンジン
   （dougaka エンジン）への発注書。エンジン統合・実 media の uploadBlob →
   `app.aozora.embed.video` announce は follow-up（tashikame.aozora 同型の
   `.clj` publisher として入る）。
3. **phase 既定は 0 (draft)**。完全自律 publisher 群（ADR-2606281500）と
   意図的に differ — minidrama の ADR が public 公開に per-episode human
   sign-off を要求するため。unlisted (phase 1) は自動可。

## Follow-ups

- 実 app-aozora Publisher（`minidrama/aozora.clj`）+ CACAO 鍵
  （`.minidrama/identity.edn`、gitignored）
- genapp-clj video エンジン（dougaka エンジン）統合 — shot 生成 → assemble
  （ffmpeg concat + 字幕）→ uploadBlob → announce
- durable outer loop（`:agent.loop/*` 系 datom、kotoba code 型）
- RAD identity journal（signing seat が使える時に
  `etzhayyim/root 80-data/kotoba-rad/minidrama.identity.journal.edn` —
  sng / kyoninka と同じ defer 前例。did:web:
  `did:web:etzhayyim.github.io:com-etzhayyim-minidrama`）
