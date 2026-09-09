<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# 本目录第三方来源说明 / Third-party provenance

仓库根 `LICENSE` 是 AGPL-3.0。本目录里**不是**全部按 AGPL 发布，这份文件逐个交代。

## `zeta.js` — allotropia/zetajs 上游原文（MIT）

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/allotropia/zetajs |
| 上游路径 | `source/zeta.js` |
| 许可 | MIT（文件头 `SPDX-License-Identifier: MIT` 即上游原样，**不要改**） |
| 引入时间 | 2026-06-22，PR #44（commit `ff7bfabd`，LibreOffice 迁移 Phase 0） |
| 对应上游版本 | **版本待补**。引入时未记录上游 tag/commit，文件头也没有版本标记。 |

对应引擎世代的线索（引擎与 zeta.js 必须同代，见 `desktop/lowa-build/RECIPE.md`）：

- LibreOffice core 分支 `distro/allotropia/zeta-24-2`（rev `dced3bc71`）
- emscripten fork `allotropia/emscripten` 分支 `fixed-3.1.65`
- qt5 `allotropia/qt5` 分支 `5.15.2+wasm`

也就是说这份 `zeta.js` 属于 zetaoffice / LibreOffice 24.2 那一代。日后如需精确回溯，
按上述 core 分支的时间点去 `allotropia/zetajs` 找同期提交。

### 我们对 `zeta.js` 的本地改动

上游原文之外只有一处改动，仍在 MIT 之下：

- 2026-08-01，PR #230（commit `c7402cb4`）：`translateTypeDescriptionAndDelete` 补
  `TypeClass.TYPEDEF` 分支，解析 typedef（如 `com.sun.star.util.Color = long`）到被引用类型，
  对齐更新的上游实现。不打这个补丁，任何带 typedef 成员的 struct
  （`BorderLine2.Color` -> `TableBorder2`）双向编组都会报 "bad type description"。
  共 11 行，见该 commit 的 diff。

**不要给 `zeta.js` 加我们自己的版权头，也不要把它挪出本目录**：它是上游原文（加一处对齐上游的修复），
且引导器按相对路径取它——`frontend/src/composables/zetaOfficeBoot.js` 的默认值
`zetaJsUrl = './zeta.js'`，`frontend/src/zetaoffice/editor-main.js` 也只在 URL 参数缺席时回落到它。
本目录是 publicDir，文件原样进产物，路径就是运行期路径，挪走等于加载失败。

## `office_thread.js` — 我们自研（AGPL-3.0-or-later AND MIT）

早期骨架（worker 装载、消息分发、少量示例式片段）来自 allotropia/zetajs 的
`simple-examples` + `web-office` 示例（MIT）；此后长到 6800 余行，主体是 AI WorkDeck 自研的
UNO 原语实现，以 AGPL-3.0-or-later 发布。双段声明见该文件头。

## `house-default.js` / `house-default.json` — 我们自研

律所标准格式（HOUSE profile）。唯一出处是
`backend/src/main/resources/style-profiles/house-default.json`，由
`frontend/scripts/sync-house-profile.mjs` 复制过来（勿手改），随仓库按 AGPL-3.0 发布。

## 构建产物

本目录是 `vite.zetaoffice.config.js` 的 publicDir（root = `frontend/src/zetaoffice`），
目录内文件会原样拷进 `dist/zetaoffice/`，本文件也随之发布，这正是我们想要的：
分发出去的编辑器产物旁边就带着上游溯源说明。
