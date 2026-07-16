# ADR-0002: scheduled auto-publish — cadence tick 消費 outer loop と :auto-publish grant

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki
**Mirrors**: superproject `com-junkawasaki/root`
`90-docs/adr/2607162200-aozora-creator-scheduled-publishing-integration.md`
（4 層統合設計の正本。本 ADR はその minidrama 実装分）

## Context

ADR-0001 (R0) は publish 承認を run context の `:approvals #{:publish}`
（per-episode human sign-off、設計 ADR-2607071300 gate ④）とした。その後
superproject 側で 2026-07-10 恒久承認（公開コンテンツの発行・更新も agent
判断で可）が出され、ADR-2607162200 が公開承認ポリシーを
**governor auto-publish + escalate-on-flag** に改訂、あわせて
aozora PDS cron が cadence tick（`creatortick/<slug>/<date>/<slot>`）を
発行するようになった（Layer A、`app.aozora.creator.getTicks` で読める）。

## Decision

1. **`minidrama.phase/publish-allowed?` は phase 2 で `:publish`（human）に
   加え `:auto-publish`（スケジュール outer loop の standing grant）を認める。**
   どちらの grant で公開されたかは commit 台帳 fact の `:publish-grant` に
   監査記録される（human が同時に居れば `:publish` 優先で記録）。
2. **DramaGovernor は不変** — HARD gate（content-veto / likeness /
   unprovenanced-asset / budget-exceeded / rate-limited / 尺・shot 数）が
   escalation 境界。HOLD はどの phase・どの grant でも announce されない。
3. **outer loop = `minidrama.outer-loop`**（1 run = 1 tick 消費）:
   - tick は PDS の getTicks から読む。actor は tick db に書かない。
   - 消費は自 repo の record（collection
     `com.etzhayyim.apps.minidrama.tick`、rkey `<date>-<slot>`）で記録し、
     これが lease を兼ねる（並行インスタンスは record を見て skip）。
   - episode は `episodes/*.edn` カタログの未消費 design を順に採る
     （design-advisor 経由で DramaGovernor の検閲を通る）。
   - chain は既存 `scripts/produce-episode.bb`（produce → dougaka engine →
     announce）を呼ぶ。plan HOLD / chain 失敗は consumption record を
     `"held"` にして announce しない（escalate）。
   - `MINIDRAMA_PHASE`（default 2）が announce 可否を phase gate で決める。
4. **crash recovery R0**: `"started"` のまま残った record は
   `outer-loop status` で表示され、owner が retry する。lease TTL による
   自動 retry は R1 follow-up。

## Consequences

- 定期投稿は「PDS cron (5分) → tick → outer loop (スケジューラ: launchd /
  routine、1 run 1 episode) → governor → announce」の合成になり、各層が
  独立に検証・停止できる（cadence off は registry 1 行、loop 停止は
  スケジューラ側、公開停止は `MINIDRAMA_PHASE=0|1`）。
- 台帳の `:publish-grant` で human / auto の公開が事後監査可能。

## 一行まとめ

**phase 2 の承認に `:auto-publish` grant を追加（台帳に監査記録）、
governor HARD gate は不変の escalation 境界、tick 消費 outer loop が
1 run = 1 episode で catalog を回す。**
