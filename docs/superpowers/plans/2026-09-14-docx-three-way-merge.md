# 律师版三方合并 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 文档类冲突从「整份三选一」变成「不重叠自动合并 + 同段冲突逐处裁决 + 合并比对稿 + 逐段溯源」。

**Architecture:** 后端（`com.checkba.version.merge`）用 POI 把 docx/xlsx/pptx 拆成单元、用 JGit `MergeAlgorithm` 做单元级三方比对并给出
重放计划；桌面端引擎（`office_thread.js`）对主线侧做原生比较、对另一侧按计划逐段重放修订；裁决结果经 `resolve-file` 落盘 + 待决记录，
三语境收尾时写 `X-AWD-Merges` / `X-AWD-Merge-Context` 尾注；溯源由后端沿历史逐版对齐单元并缓存在 gitdir。

**Tech Stack:** Java 21 / Spring / JGit / Apache POI 5.2.5；Vue3 uni-app；LibreOffice WASM（zetajs）；node --test；puppeteer。

**Spec:** `docs/superpowers/specs/2026-09-14-docx-three-way-merge-design.md`（每个任务都要先读它，规格是权威源；本计划只列任务书）。

## Global Constraints

- 界面零 Git 术语（稿/版/交稿/取回/裁决/合并比对稿/溯源/共同的上一版），浅色外壳，禁 emoji，任何界面不显示 username。
- 新建一方源文件带 SPDX 双行头（`SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors` / `SPDX-License-Identifier: AGPL-3.0-or-later`）。
- 后端 `mvn` 必须 JDK 21；前端 npm；**子代理禁止 commit / push / stash**；同一棵树里不并发跑两个 `mvn test`。
- 历史永不重写；引擎只在桌面端跑、文档字节不出本机；案件库侧（`GitHttpController` 及 cloud 包服务端）不改。
- i18n 中英同步（`frontend/src/locales/zh-CN/*.js` + `en-US/*.js`），文案走 `$t`。
- 已有测试形制：后端 `BareHub`（见 `backend/src/test/java/com/checkba/version/`）；前端 `node --test tests/**/*.test.mjs`；
  引擎 `frontend/tests/lowa-e2e/run.mjs`（线性脚本 + `check()`）。
- 每个任务的汇报格式：改了哪些文件；测试命令与**输出原文尾部**（不许只说「通过」）；未验证项明说「未验证」；任务书里哪条没做到、为什么。

---

## 文件结构

后端新增（`backend/src/main/java/com/checkba/version/merge/`）：
`Unit.java`、`DocxUnitReader.java`、`XlsxCellReader.java`、`Slide.java`、`PptxSlideReader.java`、
`MergeKind.java`、`MergeDecision.java`、`MergeReason.java`、`Overlap.java`、`Chunk.java`、`MergePlan.java`、`Analysis.java`、`ThreeWayAnalyzer.java`、
`Decision.java`、`MergeRecord.java`、`PendingMergeStore.java`、`MergeAnalysisService.java`、`XlsxMerger.java`、`PptxMerger.java`、
`ProvenanceService.java`、`ProvenanceUnit.java`。
后端修改：`ProjectRepoService`（尾注、提交钩子）、`VersionEntry`、`VersionController`（status 字段、merge 端点、provenance 端点）、
`WorkSessionService`（MERGED 裁决、complete* 传 merges）、`CloudSyncService`（cloudConflictStatus 字段、completeCloudMerge 传 merges）、`CloudController`。
前端新增：`frontend/src/services/mergeDraft.js`、`frontend/src/composables/useDocumentMerge.js`、`frontend/src/components/version/MergeReviewTab.vue`、
`frontend/src/utils/mergeRows.js`、`frontend/src/utils/provenanceAlign.js`、`frontend/src/utils/historyMerges.js`、
`frontend/tests/version-merge/*.test.mjs`、`frontend/tests/lowa-e2e/fixtures/merge/*`。
前端修改：`office_thread.js`（4 条命令）、`AdoptConflictDialog.vue`、`VersionPanel.vue`、`project-overview.vue`、`fileOpenTabs.js`、`fileKind`、
`ReviewPanel.vue`、`LibreOfficeEditor.vue`、`CommitHistoryTab.vue`、`services/api.js`、`locales/{zh-CN,en-US}/version.js`、`locales/glossary.md`。

## 后端共享类型（第一波锁死，所有任务照抄，不许改名）

```java
package com.checkba.version.merge;
public record Unit(String key, String text, String norm) {}                       // key: "p12" | "t1.2.3" | "Sheet1!B7" | "s3"
public record Slide(String sldId, int ordinal, String title, String text) {}       // ordinal 1 基
public enum MergeKind { DOCX, XLSX, PPTX, WHOLE }
public enum MergeDecision { AUTO, MANUAL, WHOLE }
public enum MergeReason { NO_BASE, BINARY, UNSUPPORTED, OVERLAP, CLEAN, TOO_LARGE, PARSE_FAILED }
public record Overlap(String key, String baseText, String mainText, String otherText) {}
public record Chunk(String type, int baseStart, int baseEnd, List<String> texts, List<String> ids, boolean conflict) {} // type MODIFY|INSERT|DELETE；texts=docx 新文本；ids=pptx sldId
public record MergePlan(List<Chunk> mainChunks, List<Chunk> otherChunks) {}
public record Analysis(MergeKind kind, MergeDecision decision, MergeReason reason, int mainChanges, int otherChanges,
                       List<Overlap> overlaps, List<String> mainOnly, List<String> otherOnly, MergePlan plan, List<Unit> baseUnits) {}
public record Decision(String key, String side, String action) {}                  // side M|T|""；action A|R|X|F
public record MergeRecord(String path, String mode, List<Decision> decisions, int mainCount, int otherCount) {} // mode auto|manual
```

`ProjectRepoService` 新签名（第一波 B3 落地，第二波 B4 调用）：
```java
public String commitMergeResolution(long projectId, String message, Map<String,String> resolutions,
                                    List<MergeRecord> merges, String mergeContext, String authorName, String authorEmail)
// 既有 5 参重载保留并转调（merges=null, mergeContext=null）；mergeContext ∈ adopt|cloud|session-end
```
`VersionEntry` 新字段：`String mergeContext`（可 null）、`List<MergeSummary> merges`，
`record MergeSummary(String path, String mode, List<Decision> decisions, int mainCount, int otherCount)`。

---

## 第一波（互不依赖，各自一棵 worktree 并行）

### Task B1: 读取器与三方分析核心（`DocxUnitReader` / `XlsxCellReader` / `PptxSlideReader` / `ThreeWayAnalyzer`）

**Files:** Create 上表 merge 包里除 `Decision/MergeRecord/PendingMergeStore/MergeAnalysisService/*Merger/Provenance*` 之外的全部；
Test: `backend/src/test/java/com/checkba/version/merge/{DocxUnitReaderTest,XlsxCellReaderTest,PptxSlideReaderTest,ThreeWayAnalyzerTest}.java`；
测试夹具用 POI 在测试里现生成（不要提交二进制 docx）。

**Interfaces（Produces）:**
```java
public final class DocxUnitReader { public static List<Unit> read(byte[] docx) throws IOException; public static String normalize(String s); }
public final class XlsxCellReader { public static Map<String,String> read(byte[] xlsx) throws IOException; }  // LinkedHashMap，公式为 "=" 开头
public final class PptxSlideReader { public static List<Slide> read(byte[] pptx) throws IOException; }
public final class ThreeWayAnalyzer {
  public static MergeKind kindOf(String path);                 // 按扩展名，大小写不敏感；docx/docm→DOCX，xlsx/xlsm→XLSX，pptx→PPTX，其余 WHOLE
  public static Analysis analyze(String path, byte[] base, byte[] main, byte[] other);  // base 为 null → WHOLE/NO_BASE；解析异常 → WHOLE/PARSE_FAILED；pdf/png 等 → WHOLE/BINARY
}
```
规则（spec §4.1–4.2）：docx 单元 = 正文顶层段落 `p{i}`（i 与 `XWPFDocument.getParagraphs()` 序一致，从 0 起）+ 表格单元 `t{ti}.{ri}.{ci}`
插在该表在 body 元素序里的位置；`norm` = trim + 连续空白折一 + NFC。三方比对：单元 `norm` 序列 → JGit `RawText`（每单元一行，行内容用 `norm` 的 SHA-256 十六进制，
避免换行干扰）→ `MergeAlgorithm(new SequenceComparator... RawTextComparator.DEFAULT).merge(base, main, other)`；`MergeChunk` 转 `Chunk`：
`conflict = chunk.getConflictState() != NO_CONFLICT`；`texts` 取该侧单元原文 `text`；type：`baseEnd==baseStart` → INSERT，该侧 0 行 → DELETE，其余 MODIFY。
`overlaps` = conflict 块覆盖的基线单元（`baseText`/`mainText`/`otherText` 为对应侧文本，删除为 ""）。`decision`：有 conflict → MANUAL/OVERLAP，否则 AUTO/CLEAN。
xlsx：键集比对，交集非空 → MANUAL，`overlaps` 每格一条；`mainOnly`/`otherOnly` 为各自改动键（含新增与删除，删除的值为 ""）。
pptx：按 `sldId` 对齐（两边都有的 sldId 视为同一页），单元 `norm` = 页文本归一，键 `s{ordinal}`（基线序，1 基）；两侧都改了页序 → MANUAL 并加 `Overlap("order", ...)`；
`Chunk.ids` 填该侧页的 sldId。任一侧字节超过 20 MB → WHOLE/TOO_LARGE。

**Tests（每条一个用例，名字照写）:** `DocxUnitReaderTest.bodyParagraphsKeepEngineOrder`、`.tableCellsFollowTheirTable`、`.normalizeCollapsesWhitespaceAndNfc`；
`XlsxCellReaderTest.formulasKeepLeadingEquals`、`.emptyCellsAreAbsent`；`PptxSlideReaderTest.readsSldIdOrdinalTitleText`；
`ThreeWayAnalyzerTest.disjointParagraphEditsAreAuto`、`.sameParagraphIsManualWithOverlapTexts`、`.adjacentParagraphsAreManual`（第 3 段与第 4 段各改一边）、
`.tableCellNextToEditedParagraphIsManual`、`.otherInsertsParagraphBetweenUntouchedOnes`（INSERT 块 texts 正确、AUTO）、`.otherDeletesParagraph`、
`.xlsxDisjointCellsAuto`、`.xlsxSameCellManual`、`.pptxDisjointSlidesAuto`、`.pptxBothReorderIsManual`、`.pdfIsWholeBinary`、`.missingBaseIsWholeNoBase`、`.unparseableIsWholeParseFailed`。

**Verify:** `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest='com.checkba.version.merge.*Test'`（贴输出尾部）。

### Task B3: 尾注与历史出参（`X-AWD-Merges` / `X-AWD-Merge-Context`）

**Files:** Modify `ProjectRepoService.java`（`commitMergeResolution` 新重载、`mergesTrailerValue`、`parseMerges`、`extractTrailer` 调用处）、`VersionEntry.java`、
`VersionController.java`（`/history` 与 `/timeline` 出参透传新字段）；Create `merge/Decision.java`、`merge/MergeRecord.java`；
Test: `CommitTrailerContractTest`（追加）、`HistoryEndpointTest`（追加）。

**Interfaces（Produces）:** 上方「后端共享类型」里的 `Decision`、`MergeRecord`、`commitMergeResolution` 7 参、`VersionEntry.mergeContext/merges/MergeSummary`。
尾注格式（spec §4.6）：`X-AWD-Merge-Context: <ctx>` 所有带 resolutions 或 merges 的提交都写；
`X-AWD-Merges: <path>=<mode>:<list>; ...` 按路径排序；`auto` 的 list 是 `M<n>,T<m>`；`manual` 的 list 是 `<key><side><action>` 逗号连接，`X`/`F` 无 side；
超过 500 条截断加 `+N`；路径编码复用既有 `encodeResolutionPath`。`X-AWD-Resolutions` 的 kept 值域加 `MERGED`（只是允许写入与解析，不校验语义）。

**Tests:** `CommitTrailerContractTest.mergesTrailerRoundTripsManualDecisions`（含中文路径、`;=%` 路径、X/F 项）、`.mergesTrailerAutoCounts`、`.mergesTrailerTruncatesAt500`、
`.mergeContextTrailerWrittenWithWholeFileResolutions`；`HistoryEndpointTest.historyExposesMergesAndContext`。

**Verify:** `mvn -q test -Dtest='CommitTrailerContractTest,HistoryEndpointTest,ProjectRepoServiceTest'`。

### Task F1: 引擎命令 `build_merge_draft` / `merge_take_other` / `sheet_get_active_cell` / `slide_get_current` + lowa-e2e 组

**Files:** Modify `frontend/src/zetaoffice/public/office_thread.js`（新增 4 条命令，放在 `compare_document` 附近）；
Create `frontend/tests/lowa-e2e/fixtures/merge/gen.mjs`（用纯 OOXML zip 生成 base/main/other 三份 docx，含 2 张表、3 条批注、420 段，与 spike 夹具同形；生成脚本入库，产物不入库）；
Modify `frontend/tests/lowa-e2e/run.mjs`（追加「== N) 三方合并 ==」一组）。
无头前提与引擎资产借用见 `.claude/agents/doc-editor.md` 与 scratchpad `spike/boot.mjs`（本会话 spike 的可跑壳，路径见任务派发信息）。**只许无头。**

**Interfaces（Produces，spec §5.1）:**
```
build_merge_draft({baseBytes, mainBytes, otherBytes, mainAuthor, otherAuthor, plan, baseUnits, name})
  -> {success, revisions, mainCount, otherCount, conflicts: [key...], formatOnly: [{paraKey, preview}], elapsedMs: {loadOther, compareOther, loadMain, compareMain, replay, total}}
  -> 失败 {success:false, stage: 'load-other'|'compare-other'|'load-main'|'compare-main'|'align'|'replay', message}
merge_take_other({paraKey, text, author}) -> {success, revisionCount}
sheet_get_active_cell() -> {success, sheet, address}          // address 如 "B7"
slide_get_current() -> {success, slideNumber}                  // 1 基
```
实现要点：整条链在**一条命令内**完成，作者用 `setRedlineAuthor()` 在同命令内切换，结尾恢复 `humanAuthor`；比较用现有 `compare_document` 的 MEMFS 写入 + `.uno:CompareDocuments`
内核抽成 `compareWithBytes(bytes)`（`compare_document` 改调它，行为不变）；**不派发 `.uno:EditDoc`**；重放用现有 `applyMinimalRedline`（MODIFY）、
表格单元用 `table_set_cell` 内核；INSERT 用 `XText.insertControlCharacter(PARAGRAPH_BREAK)` 在映射段之后插段再 `setString`；DELETE 用段落光标 `gotoEndOfParagraph` +
`goRight(1,true)` 选到下一段起点后 `setString('')`；从后往前重放；对齐核对：`plan.mainChunks` 未覆盖的基线段，当前文档对应段 `norm` 必须等于 `baseUnits[i].norm`，否则 `stage:'align'`。
`formatOnly`：另一侧比较结果里 `type` 属于 `ParagraphFormat`/`Format`/`Table` 的修订所在段，且该段不在 `otherChunks` 的文本改动里。
`merge_take_other`：找该 `paraKey` 段内全部修订按原生 id `reject`，切作者，`applyMinimalRedline(para, text)`，再把该段新产生的修订 `accept`。

**Tests（lowa-e2e 新组，每条 `check()`）:** 两位作者修订数分别为夹具预期；`conflicts` 恰为那一段的键；冲突段文字 = 主线侧文字（未重放）；
`merge_take_other` 后该段文字 = 另一侧文字且该段无未处理修订；`resolve_all_revisions(accept)` 后 `get_document_text` 逐段等于预期合并文本（生成脚本同时输出 expected.json）；
另一侧只改格式（夹具里给一段加粗）的段出现在 `formatOnly`；表格单元改动被重放；同实例第二次 `build_merge_draft` 成功；篡改 `baseUnits` 的一个 norm 后返回 `stage:'align'`；
`sheet_get_active_cell`/`slide_get_current` 各一条；打印 `elapsedMs`。

**Verify:** `cd frontend && npm run test:lowa-e2e`（无头；贴组内 check 输出与总计）+ `npm run test:lowa-unit`。

### Task F2: 自动合并流程与裁决总览（`useDocumentMerge.js` / `mergeDraft.js` / `AdoptConflictDialog.vue` 改造 / api / i18n）

**Files:** Create `frontend/src/services/mergeDraft.js`、`frontend/src/composables/useDocumentMerge.js`、`frontend/src/utils/mergeRows.js`、
`frontend/tests/version-merge/{mergeRows,useDocumentMerge}.test.mjs`；Modify `services/api.js`、`AdoptConflictDialog.vue`、`VersionPanel.vue`、`project-overview.vue`（挂 composable、`.adopt-pending-bar` 文案）、
`locales/{zh-CN,en-US}/version.js`。

**Interfaces（Consumes 后端，形状照 spec §4.3–4.5，先用 mock）:** 冲突对象新增 `mergeBase`、`documentMerges[{path, kind, decision, reason, mainChanges, otherChanges, overlapCount, state}]`、
`sides: {main: {sha, authorName, when, title}, other: {...}}`；`GET /version/merge/analysis?path=`；`POST /version/merge/resolve-file`（multipart `path, mode, decisions, file`）；
`POST /version/merge/resolve-structured`（JSON `{path, decisions}`）；三个既有 resolve 端点值域加 `MERGED`。
**Produces:**
```js
// services/api.js
getMergeAnalysis(projectId, path); postMergeResolveFile(projectId, {path, mode, decisions, bytes, name}); postMergeResolveStructured(projectId, {path, decisions})
// services/mergeDraft.js（F3 也用）
export async function fetchMergeInputs(projectId, path, {mergeBase, mainRef, otherRef}) -> {baseBytes, mainBytes, otherBytes, analysis}
export async function buildMergeDraft(run, inputs, {mainAuthor, otherAuthor, name}) -> build_merge_draft 结果   // run = executor.run(action, payload)
// composables/useDocumentMerge.js
export function useDocumentMerge({projectId, getExecutorForHiddenInstance, releaseHiddenInstance, t, toast}) -> { onConflictStatus(conflict, ctx), state }
// utils/mergeRows.js（纯函数，不 import）
export function mergeRowState(row, {isDesktop}) -> 'auto-running'|'merged'|'manual-docx'|'manual-xlsx'|'manual-pptx'|'whole'|'auto-failed'|'whole-nondesktop'
export function mergeRowText(t, row, sides) -> 字符串
```
行为照 spec §5.2–5.3；隐藏实例从 `librePool.js` 取（照备胎过继形制，file=null 的实例；不够就新起一个，用完 release）；幂等键 `(MERGE_HEAD, path)`；
全 AUTO 成功 → 自动调该语境 resolve 端点（全 `MERGED`）+ toast + `reload-files`；否则打开总览。总览每行按 `mergeRowState` 渲染（表格见 spec 5.3）；
xlsx 逐格表、pptx 逐页列表就在总览里做；「打开合并比对稿」调 `fileOpenTabs.openMergeReviewTab(spec)`（F3 提供，签名见 F3；F2 只调用）；
监听 `uni.$on('awd:merge-file-resolved', {path})` 刷新行态。非桌面端（无引擎）docx 行退回整份三选一并显示「在桌面端可逐处合并」。

**Tests:** `mergeRows.test.mjs`（8 种行态 + 文案）；`useDocumentMerge.test.mjs`（mock executor/api：全 AUTO 自动收尾且只调一次；含 MANUAL 不收尾；`formatOnly` 非空降 MANUAL；
`stage:'align'` 退整份；幂等不重跑；非桌面不跑）。

**Verify:** `cd frontend && node --test tests/version-merge/*.test.mjs && npm run build:h5 2>&1 | tail -5`（或仓库既有的构建命令，见 `eng-infra.md`）。

### Task F3: 合并比对稿标签页（`MergeReviewTab.vue` + `ReviewPanel` merge 模式 + 标签管道）

**Files:** Create `frontend/src/components/version/MergeReviewTab.vue`；Modify `ReviewPanel.vue`（`mode: 'merge'` prop 与三块）、`fileOpenTabs.js`（`openMergeReviewTab`、`isMergeReviewTab`）、
`fileKind`（`NON_FILE_TAB_TYPES` 加 `merge-review`）、两窗格 `v-else-if`（照 `version-compare` 的接法）、`locales/*/version.js`；
Test: `frontend/tests/tab-visibility/file-kind.test.mjs`（追加）、`frontend/tests/version-merge/mergeReviewDecisions.test.mjs`。

**Interfaces（Produces）:**
```js
fileOpenTabs.openMergeReviewTab({projectId, path, name, ctx, mergeBase, mainRef, otherRef, sides: {main:{authorName, when, title}, other:{...}}, readonly})
// tab: {id: `merge-review_${projectId}_${path}`, tabType: 'merge-review', fileType: 'merge-review', mergeSpec}
// 完成后 uni.$emit('awd:merge-file-resolved', {path})
// utils/mergeReviewDecisions.js（纯函数）：collectDecisions({conflictChoices, revisionOutcomes, formatOnly}) -> Decision[]
```
**Consumes:** F1 的 4 条命令形状（见 F1）；F2 的 `services/mergeDraft.js`（`fetchMergeInputs`/`buildMergeDraft`，并行开发时先按签名 stub 到本地文件，合并时换成真的）。
行为照 spec §5.4：可编辑 LOWA 实例（复用 `LibreOfficeEditor` 装载路径，`mergeSpec` 代替 `file`，自动保存关、`saveDocument` 不走 upload）；
面板三块；「完成裁决」：块 1 全处理完才可点，块 2 未处理 → 文案「接受其余 N 处并完成」→ `resolve_all_revisions(accept)` → `export_document` → `postMergeResolveFile(mode=manual, decisions)`；
成功后只读 + 提示；「先不处理」只关标签页。`readonly` 时加载工作区当前字节与 base 比较只读展示（复用 `compare_document`）。

**Tests:** `file-kind` 加 `merge-review` 非文件标签；`mergeReviewDecisions.test.mjs`（三块合成 decisions：`p12MA`、`p12TA`、`p9X`、`p20F`、块 2 每条 A/R；键映射表格 `t`）。

**Verify:** `cd frontend && node --test tests/tab-visibility/*.test.mjs tests/version-merge/*.test.mjs`。

### Task F4: 逐段溯源界面与提交历史增量（`provenanceAlign` / 光标条 / 侧栏「溯源」/ `focusSha` / merges 副标题）

**Files:** Create `frontend/src/utils/provenanceAlign.js`、`frontend/src/utils/historyMerges.js`、`frontend/tests/version-merge/{provenanceAlign,historyMerges}.test.mjs`；
Modify `LibreOfficeEditor.vue`（光标条 + 数据拉取）、`ReviewPanel.vue`（第三标签「溯源」）、`CommitHistoryTab.vue`（`focusSha` 定位高亮；merges 副标题与详情；`mergeContext` 翻译）、
`fileOpenTabs.js`（`openCommitHistoryTab` 透传 `focusSha`）、`services/api.js`（`getProvenance(projectId, fileId, ref)`）、`locales/*/version.js`。

**Interfaces（Consumes 后端 spec §4.7 / §4.6）:** `GET /version/provenance?fileId=&ref=` → `{ref, kind, units:[{key, textHash, sha, shortId, authorName, self, when, title, type}], truncated, computing}`；
`/history` entries 的 `mergeContext`、`merges[{path, mode, decisions, mainCount, otherCount}]`。引擎侧 `get_review_context().paragraphIndex`、
`get_document_text` 全量分页、`sheet_get_active_cell`、`slide_get_current`（F1）。
**Produces（纯函数，不 import）:**
```js
alignProvenance(units, engineParagraphs) -> Map<engineIndex, unit|null>       // units 用 textHash（sha256 hex of norm，前端同样归一后哈希）与引擎段落 norm 做 LCS
provenanceLabel(t, unit, {selfLabel}) -> '韩泽伟 · 9 月 13 日 · 核对注册资本' | '你 · …' | '自动存档 · …' | '本机未保存的改动' | '更早的版本'
historyMergeLines(t, entry, names) -> [{text, more}]   // 按 mergeContext 翻译 M/T：cloud→你/案件库那边；session-end→同事/你；adopt→主线/这一稿；缺席用旧文案
```
光标条：`sel_changed` + 既有聚焦轮询 → `get_review_context` → 映射 → 显示；点击 `openCommitHistoryTab({focusSha})`；保存落版后重拉。
「溯源」标签：列表 + 摘要行；点行 `select_paragraph`。`CommitHistoryTab` 的 `focusSha`：定位到含该 sha 的行（折叠的自动存档展开所属行），高亮 2 秒。
既有 `resolutionKeptMain/Draft` 改为按 `mergeContext` 翻译（缺席保持旧文案）。

**Tests:** `provenanceAlign.test.mjs`（一一对应、中间插段、删段、改一字、重复空段按位置）；`historyMerges.test.mjs`（三语境翻译、auto 计数句、manual 折叠「等 N 处」、无 mergeContext 旧文案）。

**Verify:** `cd frontend && node --test tests/version-merge/*.test.mjs tests/version-history/*.test.mjs`。

---

## 第二波（依赖第一波已合入分支）

### Task B4: `MergeAnalysisService` + status 字段 + merge 端点 + 待决记录 + 三语境 `MERGED` 收尾

**Files:** Create `merge/MergeAnalysisService.java`、`merge/PendingMergeStore.java`；Modify `VersionController.java`（三个 *ConflictStatus 加 `mergeBase/documentMerges/sides`；
`GET /merge/analysis`、`POST /merge/resolve-file`、`POST /merge/resolve-structured`）、`WorkSessionService.java`（`Resolution.MERGED`、`applyResolution` MERGED 分支、`resolveAdopt`/`resolveSessionEnd` 校验、
`completeAdopt`/`completeSessionMerge` 传 merges+ctx、`abortAdopt`/`abortSessionEnd` 清待决）、`CloudSyncService.java`（`cloudConflictStatus` 同款字段、`resolveCloudMerge` 校验、`completeCloudMerge` 传 merges+ctx、`abortCloudMerge` 清待决）；
Test: `merge/MergeResolveFileTest.java`、`merge/MergeAnalysisServiceTest.java`、既有 `DraftAdoptTest`/`CloudSyncTest`（追加 MERGED 用例）。

**Interfaces（Produces）:**
```java
public class MergeAnalysisService {
  public Map<String, Analysis> analyzeConflicts(long projectId);            // 只在 MERGING；缓存键 (projectId, HEAD, MERGE_HEAD)；单文件 >5s → WHOLE/TOO_LARGE
  public List<Map<String,Object>> documentMerges(long projectId);          // /status 用的精简形（含 state PENDING|MERGED）
}
public class PendingMergeStore {                                           // <gitdir>/awd-merge-pending.json
  public void put(long projectId, MergeRecord r); public Optional<MergeRecord> get(long projectId, String path);
  public List<MergeRecord> all(long projectId); public void clear(long projectId);
}
// VersionController
GET  /merge/analysis?path=            -> Analysis
POST /merge/resolve-file              multipart path, mode, decisions(JSON), file -> {path, state:"MERGED", mainCount, otherCount}
POST /merge/resolve-structured        JSON {path, decisions} -> 同上（调 B5 的 XlsxMerger/PptxMerger；B5 未合入前先按接口写、用 mock 测）
```
`sides.main/other` 用 `ProjectRepoService.toEntry` 取两侧 tip 的作者/时间/标题（作者名走 `VersionAuthorResolver.preferredAuthorName(entry, remoteNames)`，`allowFetch=false`）。
`resolve-file` 校验：路径在 `conflictingPaths` 里且 `safeRepoPath` 通过；写字节到工作区；`PendingMergeStore.put`。
三个 resolve 端点：值 `MERGED` 必须有待决记录否则 400（消息走 `userFacing`）；收尾把 `all()` 转成 `merges` 传给 7 参 `commitMergeResolution`，`ctx` 按语境常量；成功后 `clear`。

**Tests:** `MergeResolveFileTest.writesBytesAndRecordsPending`、`.rejectsPathOutsideConflicts`、`.mergedWithoutPendingIs400`、`.adoptCompletesWithMergesTrailer`、`.sessionEndCompletesWithMergesTrailer`、
`.cloudCompletesWithMergesTrailer`、`.abortClearsPending`、`.statusExposesDocumentMergesAndSidesAfterRestart`（新建 service 实例读同一 gitdir）；
`MergeAnalysisServiceTest.cachesByHeadAndMergeHead`、`.leavesMergingClearsCache`。

**Verify:** `mvn -q test -Dtest='com.checkba.version.merge.*Test,DraftAdoptTest,WorkSessionServiceTest,CloudSyncTest,HistoryEndpointTest'` 后再跑一次全量 `mvn -q test`（贴尾部）。

### Task B5: `XlsxMerger` / `PptxMerger`

**Files:** Create `merge/XlsxMerger.java`、`merge/PptxMerger.java`；Test `merge/{XlsxMergerTest,PptxMergerTest}.java`；Modify `VersionController` 的 `resolve-structured` 接线（若 B4 已留接口则只填实现）。

**Interfaces:**
```java
public final class XlsxMerger { public static byte[] merge(byte[] main, byte[] other, Analysis a, List<Decision> decisions); }
public final class PptxMerger { public static byte[] merge(byte[] main, byte[] other, Analysis a, List<Decision> decisions); }
// decisions 为空 = AUTO：合入 a.otherOnly 全部；非空 = 逐格/逐页，side T 取 other，M 取 main
```
规则见 spec §4.5（样式沿用、公式 `setCellFormula`、新增表整表复制值；pptx `importContent`、新增/删除页、页序取 other 侧；sldId 不稳时标题+文本相似度 0.6 对齐）。

**Tests:** `XlsxMergerTest.autoAppliesOtherOnlyCellsKeepingStyles`、`.formulaPreserved`、`.manualPerCellChoice`、`.newSheetCopied`；
`PptxMergerTest.autoReplacesOtherChangedSlides`、`.insertsAndDeletesSlides`、`.manualPerSlideChoice`、`.fallsBackToSimilarityWhenSldIdMissing`。

**Verify:** `mvn -q test -Dtest='XlsxMergerTest,PptxMergerTest'`。

### Task B6: `ProvenanceService` + 端点 + 缓存 + 提交钩子

**Files:** Create `merge/ProvenanceService.java`、`merge/ProvenanceUnit.java`；Modify `VersionController.java`（`GET /provenance`）、`ProjectRepoService.java`（`commitAll`/`commitMergeResolution` 成功后异步预算）；
Test `merge/ProvenanceServiceTest.java`。

**Interfaces:**
```java
public record ProvenanceUnit(String key, String textHash, String sha, String shortId, String authorName, boolean self, Instant when, String title, String type) {}
public class ProvenanceService {
  public Map<String,Object> provenance(long projectId, long userId, String relPath, String ref);  // {ref, kind, units, truncated, computing}
  public void precomputeAsync(long projectId, String sha, List<String> relPaths);
}
GET /version/provenance?fileId=&ref=HEAD
```
算法与降级口径照 spec §4.7：`RevWalk` + `FollowFilter`；每版单元 `norm` 哈希序列对父提交跑 `HistogramDiff`，未变块继承（先第一父再第二父），变更块记本版；
缓存 `<gitdir>/awd-cache/provenance/<sha256(path) 前 16 位>/<sha>.json`；上限 500 版，超出记 `{sha:null, title:"更早的版本"}` + `truncated`；单次超 30 s 回 `computing:true` 并后台继续。
`textHash` = SHA-256(norm) 十六进制。xlsx/pptx 单元键与读取器一致。

**Tests:** `ProvenanceServiceTest.linearHistoryAttributesLastEditor`、`.mergeCommitInheritsFromSecondParent`、`.followsRename`、`.cacheHitSkipsRecompute`（篡改缓存文件内容后读到篡改值）、
`.truncatesAt500`、`.autoCommitIsAttributedWithAutoType`、`.xlsxCellsAndPptxSlides`。

**Verify:** `mvn -q test -Dtest='ProvenanceServiceTest,ProjectRepoServiceTest'`。

---

## 第三波（全部合入后）

### Task E1: app-e2e J12（真实段落级冲突全链路）

**Files:** Modify `frontend/tests/app-e2e/run.mjs`（J12 段，复用 J11 的 `spawnBackend`/`restOverwriteAt`）；Create `frontend/tests/app-e2e/fixtures/merge/gen.mjs`（与 lowa-e2e 同一生成器，可直接 import）。
场景照 spec §7 的 ①②③④，每步 `check()`，断言走接口（`/history` 的 `merges`、`/status` 的 `documentMerges`）+ 界面几何可见；引擎 UI 步骤（打开合并比对稿、面板点拒绝、完成裁决）走既有 `mouseClickSel`。

**Verify:** `cd frontend && npm run test:app-e2e`（J1–J12 全绿，贴总计与 J12 段输出）。

### Task D1: 领域文档

**Files:** Modify `.claude/agents/version-control.md`（新节「三方合并与溯源」：分析/计划/待决/尾注/MERGED/provenance 缓存/地雷）、`.claude/agents/doc-editor.md`（4 条命令契约与「作者必须同命令内设置」「EditDoc 不生效」两条地雷）、
`.claude/agents/sidebar-shell.md`（`merge-review` 标签）、`frontend/src/locales/glossary.md`。以最终代码为准，不照抄规格。

---

## 主会话（Fable）自留的三件事

1. 每个任务合入前读 diff、在该 worktree 亲自跑任务书里的验证命令。
2. 「还原病灶即转红」：注掉相邻冲突判定 → `ThreeWayAnalyzerTest.adjacentParagraphsAreManual` 红；去掉第二父继承 → `ProvenanceServiceTest.mergeCommitInheritsFromSecondParent` 红；
   去掉 `MERGED` 待决校验 → `MergeResolveFileTest.mergedWithoutPendingIs400` 红；去掉「作者同命令内设置」→ lowa-e2e 作者计数红。每条先 assert 锚点唯一再改。
3. 有头真机走查截图（维护者空闲 > 180 s 才跑）。
