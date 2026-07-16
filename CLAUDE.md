# com-etzhayyim-minidrama

minidrama (ミニドラマ座) — 縦型ミニドラマ制作 actor。core contract は
`README.md`、pattern は full-repo `../../../CLAUDE.md` "Actors" 節
（containment + independent governor + append-only ledger）。
Superproject decision record:
`../../../90-docs/adr/2607071300-aozora-creator-actors-minidrama.md`（Part 2）。
Design 正本: `docs/adr/0001-architecture.md`。

## Invariant

minidrama は DramaGovernor が拒否したプランを NEVER commit / announce する。
over-duration(>120s) / too-many-shots(>24) / overlong-shot(>10s) /
content-veto(Rider §2) / likeness / unprovenanced-asset / budget-exceeded /
rate-limited は HELD — append-only 台帳に hold として記録され、SSoT には
書かれない。`:commit` だけが Store 書込 + announce を行い、全 commit/hold は
不変の台帳 fact。**public announcement (phase 2) は run context の
approval grant（`:publish` = per-episode human sign-off、または
`:auto-publish` = スケジュール outer loop の standing grant、
superproject ADR-2607162200 Layer D）が無い限り行わない**。
`:auto-publish` は 2026-07-10 恒久承認（公開コンテンツの発行も agent 判断で
可）を反映した改訂で、初版 ADR-2607071300 gate ④ の「常に human 承認」を
置き換える — ただし **DramaGovernor の HARD gate（content-veto / likeness /
provenance / budget / rate-cap）が escalation 境界として不変**: HOLD は
どの phase でも announce されず、owner へ surface される。どの grant で
公開されたかは台帳 fact の `:publish-grant` に監査記録される。
unlisted (phase 1) までは grant 無しで自動可。
low-confidence は block せず `:low-confidence` タグで commit（透明性）。
生成・合成はこの actor に実装しない — committed plan は genapp-clj 系
video エンジン（dougaka エンジン）への発注書。

## Conventions

- `.cljc` for anything portable (operation/governor/advisor/publisher/phase/
  store/sim) — `.clj` は JVM-only I/O（将来の cacao / aozora publisher）のみ。
- actor 自身の Ed25519 identity は `.minidrama/identity.edn`（gitignored）—
  NEVER commit a private key。
- `clojure -M:lint`（clj-kondo, errors fail）/ `clojure -M:dev:test`。
