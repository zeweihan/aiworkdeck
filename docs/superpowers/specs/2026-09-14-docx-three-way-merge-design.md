# 律师版「三方合并」：合并比对稿、不重叠自动合并、逐处裁决、逐段溯源（设计稿）

日期：2026-09-14。看板卡：dev-board#630（合并比对稿与逐处裁决）、#631（不重叠自动合并）、#632（逐段溯源）。
产品线 p/aiworkdeck，模块「文件与项目」。上一步是 PR#836（协作历史，spec `2026-09-14-collab-history-git-parity-design.md`）。
用户裁决：**不分期、不阉割，一次交付最终成果**。

## 0. 病灶与已核实的现状

- **冲突判定是整份字节**：`ProjectRepoService.mergeCore` 不设 `ContentMergeStrategy`，JGit `ResolveMerger` 对二进制 blob 抛
  `BinaryBlobException` 把整个条目判 unmerged。docx/xlsx/pptx 只要两边都动过就整份进 `AdoptConflictDialog` 三选一，
  律师看不到对方具体改了哪里；`BOTH` 也不是合并，是两份并排。
- **三语境共用一套底层**：`RepositoryState.MERGING` + `MERGE_HEAD` 反查，`VersionController.status()` 按
  `sessionEndConflict → cloudConflict → adoptConflict` 优先级只暴露一个；裁决端点三个
  （`POST /version/draft/{id}/resolve`、`POST /version/session/resolve-end`、`POST /api/cloud/projects/{id}/resolve`），
  请求体都是 `{resolutions: {path: MAIN|DRAFT|BOTH}}`（session-end 外层多一个 `sessionId`），
  落盘统一走 `WorkSessionService.applyResolution`，收尾统一走 `ProjectRepoService.commitMergeResolution` 写
  `X-AWD-Resolutions` 尾注。MAIN/DRAFT 的物理侧在三语境里不同（方向表见 `version-control.md`「三语境冲突判定链」）。
- **合并基线能算**：`ProjectRepoService.mergeBase(projectId, refA, refB)` 已存在（三个 complete* 已在用）。
- **任意版本任意文件字节可取**：`GET /version/versions/{ref}/file-bytes?path=`，前端 `fetchVersionFileBytes`。
- **引擎原生比较已在产品里**：`compare_document` 把基准字节写进 MEMFS（`file:///tmp/awd_base_cmp.docx`）后派发
  `.uno:CompareDocuments`（`office_thread.js:5734`），署名走 `setRedlineAuthor()`（改 `UserProfile/Data/givenname`）。
  修订原语齐全：`list_revisions`（带 `paragraph`、`author`、`date`、`type`）、`resolve_revision`、`resolve_revisions`
  （r5 按原生 id）、`resolve_all_revisions`、`goto_revision`；审阅面板 `ReviewPanel.vue` 已按这些原语工作。
  现有 `VersionCompareTab` 结尾派发 `.uno:EditDoc` 切只读，注释说「toggle、每实例只能调一次」。
- **保存链**：`export_document` → `POST /api/files/{fileId}/upload` → `signalChange` → `onChangeSignal` → 防抖 `commitNow`。
  没有「另存到指定路径」。
- **段落索引语义**：引擎 `get_document_text`/`get_paragraph`/`select_paragraph` 的下标 = 正文顶层
  `com.sun.star.text.Paragraph` 枚举序（`buildParaIndex`），**表格里的段落不计入**。POI `XWPFDocument.getParagraphs()`
  同样只给正文段落，两边下标天然同构。
- **后端没有分段落读 docx 的工具**（`DocumentTextService` 走 Tika 整篇纯文本），但 `poi-ooxml 5.2.5` 在依赖里；
  没有 diff 库，但 JGit 的 `org.eclipse.jgit.diff.MyersDiff/HistogramDiff` 与 `org.eclipse.jgit.merge.MergeAlgorithm`
  对任意 `Sequence` 可用。
- `.awd/` 目录被文件树扫描忽略但**会被 `git add .` 收进历史**，`diffNameStatus` 不过滤，展示层各自过滤。
- 提交历史标签页已渲染 `resolutions`（`CommitHistoryTab.vue:431`，i18n `version.resolutionKeptMain/Draft/Both`），
  但文案「留了你这边」没带语境，在 `session-end` 语境下是反的（MAIN 是同事的）。

## 1. 两个技术验证（spike）的实测结论

实测环境：LOWA 24.2.8-zhcn-r5（`/Applications/AI WorkDeck.app` 里的 `dist/zetaoffice` 资产 + worktree 源码 `office_thread.js` 内存注入
throwaway 动作），无头 Chrome，夹具 420 段 / 32 页 / 2 张表 / 3 条批注，mine 与 theirs 各改 5 个不同段 + 同一段各改一处。
脚本与日志在会话 scratchpad `spike/` 下，全部 throwaway，不入库。

### 1.1 Spike A：引擎内「比较 + 合并 + 全部接受」链

| 问题 | 结论 | 证据 |
|---|---|---|
| A1 `.uno:CompareDocuments` 对 MEMFS 里的另一份 docx | **可用** | `SimpleFileAccess` 写 `file:///tmp/spike_base.docx` 11 ms；只传 `URL` 一个属性，无头下无对话框；`redlinesAfter: 8`（6 Insert / 2 Delete）与夹具逐条对上；结果 = 当前文档 + 修订 |
| A2 修订作者 | **可控**，但**必须在同一条 worker 命令内设置** | `execCommand` 每条命令开头都会 `setRedlineAuthor(humanAuthor)`（`office_thread.js:7695`），跨命令设作者必失效（第一轮全签成「本地用户」）。同一命令内 `setRedlineAuthor('张律师')` 后比较 → `{"张律师/Insert":6,"张律师/Delete":2}` |
| A2 修订日期 | **只读** | `RedlineDateTime` setPropertyValue 不抛异常但读回不变；导出 docx 里 `w:date` 是比较时刻，同一次比较所有修订共享 |
| A3 `.uno:MergeDocuments` | **不能用于产品** | 「以我方比对稿为当前文档合并同事比对稿」能跑（8 → 13 条，作者各自保留、docx 往返不丢），**但同事侧 6 条 Insert 全部变成无修订标记的正文**（RejectAll 后同事插入的字仍在），律师无法拒绝同事新增的文字；同一段两边都动过时**不报冲突、静默叠加**成病句；合并进 base 或合并无标记副本更糟（插入全被吞 / 纹丝不动） |
| A4 修订枚举与逐条处置 | **完全可用** | 现成 `list_revisions` 给 `identifier`（原生 id）/`type`/`author`/`text`/`paraKey`/`start`/`end`/`inTable`；`resolve_revision` accept/reject 各 150–163 ms（`via: native-id`）；`.uno:AcceptAllTrackedChanges` 524 ms、RejectAll 可用；处置一条后后续 index 前移（批量走 `resolve_revisions`） |
| A5 同实例多次比较/合并 | **可用** | 一个实例内连做 load → compare → export → load → compare → export → load → merge → 逐条处置 → accept all → export 无退化，第二次比较 606 ms |
| A5 `.uno:EditDoc`「每实例只能调一次」 | **该命令在 r5 上根本不生效** | 连派三次后 `isReadonly()` 仍 false，探针每次都能插字。`VersionCompareTab` 的只读靠的是「没有保存路径」而不是 EditDoc；三方合并不必绕它 |
| A6 耗时（两次运行一致） | 整链 ≈ 5.5 s | load 2.3 s（实例内首次）/ compare 356 ms / export 215 ms / 第二份 load 250 ms / compare 606 ms / 逐条处置 150 ms / AcceptAll 524 ms / export 53 ms。未验证：重格式、含图片的真实 30 页合同的规模曲线 |
| A7 备选「逐条重放」 | **前提成立** | RecordChanges 开、作者在同一命令内设好时，程序化替换生成署该作者的修订：`{"李律师/Insert":1,"李律师/Delete":1}` |

**方案结论**：`CompareDocuments` 这一半照用；**放弃 `MergeDocuments`**。另一侧的改动按后端算好的段落计划逐段重放
（`applyMinimalRedline` 那套字符级最小修订 + 同命令内切作者），同一段两边都动过的不重放、交给律师三选一（§5.4）。

### 1.2 Spike B：docx 往返是否保留 `w14:paraId`

| 问题 | 结论 | 证据 |
|---|---|---|
| B1 `w14:paraId` | **整类丢弃**，新段也不分配 | 源 10 个 paraId（含表格内），`export_document`（filter `Office Open XML Text`）后 0 个；`<w:document>` 声明了 `xmlns:w14` 与 `mc:Ignorable` 却不写任何 `w14:` 属性；改字/拆段/新增段后仍 0 |
| B2 隐藏书签 `__awd_p_N` | **完整往返** | 原样往返 4/4；改段落文字后仍在；在书签之后拆段时书签落前半段；引擎自建书签也存活。未验证：在书签之前拆段、书签正好在拆分点、RecordChanges 开着时拆段 |
| B3 其它稳定属性 | **没有** | 导出件 `w:rsid*` 0 个、`settings.xml` 无 `rsidRoot` |

**方案结论**：溯源**不用 paraId、也不注入书签**。理由：溯源的语义是「最后改动这一段文字的是哪一版」——文字变了归本版、
没变就继承，这两条只需要**文本精确匹配 + LCS 位置对齐**就能判定；稳定 id 唯一多出来的能力是把「剪切后粘到别处」的段归原作者，
而「移动」本来就该算一次改动。注入书签则要在每次保存时改写每一份 docx（Word 里下划线开头是隐藏书签，虽不可见但会进用户产物），
为一个不需要的能力付产品级副作用，不值。降级口径见 §4.7。

## 2. 目标与用词

程序员的 git 三方合并，律师这里叫什么：

| git | 本产品界面 | 说明 |
|---|---|---|
| merge-base | 「共同的上一版」 | 两位律师分头修改前的那一版 |
| ours / theirs 的 diff | 「你改了 N 处」「律师乙改了 M 处」 | 修订稿里按作者分组 |
| 三方合并、hunk 不重叠自动合并 | 「自动合并了同事的改动」 | 静默，历史里一条版本 |
| 冲突 hunk | 「同一段两边都改了」 | 只在这时打扰律师 |
| 逐 hunk 解决 | 「合并比对稿」里逐处接受 / 拒绝，保存即裁决 | 代替整份三选一 |
| blame | 「溯源」：这一段是谁、哪一版、什么时候改的 | 悬停/侧栏，点击跳提交历史 |

界面延续零 Git 术语纪律：稿 / 版 / 交稿 / 取回 / 裁决 / 合并比对稿 / 溯源。浅色外壳，禁 emoji，不显示 username。

## 3. 总体架构：后端判定、桌面端引擎执行、案件库不碰内容

```
                 冲突（MERGING）
                       │
   后端 MergeAnalysisService：按文件类型做结构化三方比对（POI + JGit MergeAlgorithm）
       docx 按正文段落 + 表格单元格 │ xlsx 按单元格 │ pptx 按页 │ 其余整份
                       │  每个路径给出 decision：AUTO / MANUAL / WHOLE
                       ▼
   /status 的冲突对象多一个 documentMerges[]，前端据此分流
       AUTO  ─ docx → 桌面端隐藏引擎实例：主线侧引擎比较 + 另一侧按段落计划重放修订 + 全部接受 → 上传合并结果（静默）
             ─ xlsx/pptx → 后端 POI 直接拼出合并文件（不需要引擎）
       MANUAL─ docx → 打开「合并比对稿」标签页，逐处接受/拒绝 → 保存即裁决
             ─ xlsx/pptx → 裁决总览里逐格 / 逐页选
       WHOLE ─ pdf/图片/其它二进制 → 原三选一，界面写明原因
                       │
   每个路径裁决完 → POST /version/merge/resolve-file（字节 + 决定清单）落到工作区 + 待决记录
   全部路径就绪 → 原三个 resolve 端点以 MERGED 收尾 → commitMergeResolution 写三条尾注
                       │
   溯源：ProvenanceService 沿历史逐版对齐段落 → 编辑器光标条 + 侧栏「溯源」标签 → 点击跳提交历史
```

硬约束：引擎只在桌面端跑、文档字节不出本机；案件库侧（`GitHttpController` 那一侧）**不新增任何内容处理**；
自动合并前后各有一版（合并前 = 本机改动已按既有流程落版：结束工作的工作段提交、取回前的 dock、采纳时的稿分支；
合并后 = 双亲合并提交），退回走既有「退回到这一版」，历史永不重写。

## 4. 后端：结构化三方比对与合并（`com.checkba.version.merge` 包）

### 4.1 读取器（POI）

- `DocxUnitReader.read(byte[]) -> List<Unit>`：正文顶层段落按枚举序给 `Unit{key:"p"+i, text}`（`i` 是正文段落序，与引擎
  `get_paragraph` 下标同构）；表格按 `Unit{key:"t"+ti+"."+ri+"."+ci, text}` 一格一单元，紧跟在它在正文里的位置之后。
  `text` 保留原文（重放要用），另给 `norm`：去首尾空白、连续空白折一个、NFC，比对与溯源都按 `norm`。不读 `w14:paraId`（§1.2）。
- `XlsxCellReader.read(byte[]) -> Map<"Sheet!A1", value>`：公式单元格取公式字符串（带 `=`），其余取显示值字符串；
  空单元格不进 map；工作表按名字。
- `PptxSlideReader.read(byte[]) -> List<Slide{sldId, ordinal, title, text}>`：`sldId` 取 `presentation.xml` 的 `<p:sldId id>`；
  `text` 是该页全部形状文本按形状序拼接。

### 4.2 `ThreeWayAnalyzer`

输入三份字节（base = `mergeBase(HEAD, MERGE_HEAD)`，main = HEAD 侧，other = MERGE_HEAD 侧）与文件类型；输出：

```
Analysis {
  kind: DOCX|XLSX|PPTX|WHOLE,
  decision: AUTO|MANUAL|WHOLE,
  reason: NO_BASE|BINARY|UNSUPPORTED|OVERLAP|CLEAN,
  mainChanges, otherChanges,              // 各自改了几处（单元数）
  overlaps: [{key, baseText, mainText, otherText}],   // 两边都动过的单元
  mainOnly: [key...], otherOnly: [key...], // 供 xlsx/pptx 后端拼合并文件
  plan: {                                  // docx 专用：引擎重放计划（基线坐标）
    mainChunks:  [{type: MODIFY|INSERT|DELETE, baseStart, baseEnd, texts: [...]}],
    otherChunks: [{type, baseStart, baseEnd, texts: [...], conflict: bool}]
  },
  baseUnits: [{key, norm}]                 // 引擎核对对齐用
}
```

`plan` 直接来自 `MergeAlgorithm` 的 `MergeChunk` 序列：每个块记它在**基线**里覆盖的单元区间与该侧的新文本；
`conflict=true` 的块两侧都动过。引擎只重放 `otherChunks` 里 `conflict=false` 的块（§5.1）。

- docx / pptx：把单元序列的 `text` 哈希当作「行」构造 JGit `RawText`，跑 `MergeAlgorithm.merge(RawTextComparator.DEFAULT, base, main, other)`；
  有 `MergeChunk.ConflictState != NO_CONFLICT` 的块即 overlap（JGit 与 git 同口径：**相邻块也算冲突**，宁可多问一次）。
  docx 里表格单元与段落混在同一序列里，所以「同事改了表格某一格、我改了表格上面那段」是相邻冲突，进 MANUAL——这是刻意的。
- xlsx：无序集合，按单元格键比：`mainChanged = {k | main[k] != base[k]}`，`otherChanged` 同理，交集非空即 MANUAL。
  新增/删除工作表按整表单元格展开。
- 无 base（`mergeBase` 为 null，理论不可达）、任一侧解析失败、文件类型不在三种里 → `WHOLE`，`reason` 带原因；
  pdf/图片 `reason=BINARY`，前端据此显示「PDF 与图片没有可比对的段落，请整份选择」。
- 纯文本文件不经这里：JGit 已做行级合并，剩下的冲突照旧整份三选一（现状不变）。

### 4.3 `MergeAnalysisService`

- `analyzeConflicts(projectId) -> Map<path, Analysis>`：只在 `repositoryMerging()` 为真时工作；对 `userVisibleConflicts` 逐路径读三份字节
  （`readBlobAtCommit`）跑分析。缓存键 `(projectId, HEAD sha, MERGE_HEAD sha)`，仓库离开 MERGING 即失效。
  单文件超过 `maxTrackedFileSizeBytes` 或解析超过 5 秒 → 该路径 `WHOLE/UNSUPPORTED`，不拖垮 `/status`。
- `VersionController.status()` 的三个冲突对象各加两个字段：`mergeBase`（sha）与
  `documentMerges: [{path, kind, decision, reason, mainChanges, otherChanges, overlapCount}]`；
  `GET /version/merge/analysis?path=` 返回完整 `Analysis`（含 overlaps 文本），给裁决界面按需拉。
  `cloudConflictStatus` 走 `CloudSyncService`，同样拼这两个字段（用同一个服务，别复制）。

### 4.4 落盘与待决记录

- `POST /version/merge/resolve-file`（multipart：`path`、`ctx`、`mode=auto|manual`、`decisions`（JSON 数组）、`file`（合并后的字节，
  xlsx/pptx 走 4.5 时可省）；云端语境同样打这个端点，裁决语境由 `MERGE_HEAD` 反查而不是信客户端的 `ctx`，`ctx` 只用来校验一致）：
  1. `safeRepoPath` 校验路径确实在当前 `conflictingPaths` 里；
  2. 字节写到工作区该路径（覆盖冲突标记态的文件）；
  3. `PendingMergeStore` 在 `<gitdir>/awd-merge-pending.json` 记 `{path: {mode, decisions, writtenAt}}`——放 gitdir 不放工作区，
     `git add .` 收不到它，中止合并时整文件删除；
  4. 返回 `{path, state: "MERGED"}`。
- `decisions` 元素：`{key, side: "M"|"T"|"", action: "A"|"R"|"X"|"F"}`。`key` 是 4.1 的单元键（docx 用**合并结果里**的段落序 `p12`，
  表格单元 `t1.2.3`；xlsx `Sheet1!B7`；pptx `s3`）；`side`：M = MAIN 物理侧（合并时的主线），T = DRAFT 物理侧（另一边），
  `X`/`F` 时为空；`action`：A 接受该侧、R 拒绝该侧、X 律师自己改了这一处（同段冲突手改）、F 另一侧格式改动未合并（仅记录）。
  `mode=auto` 时 `decisions` 允许为空，服务端按分析结果记两个计数。
- 三个既有 resolve 端点的 `resolutions` 值域加 `MERGED`：服务端校验该路径在 `PendingMergeStore` 里有记录，否则 400；
  `applyResolution(MERGED)` 不写字节（已在磁盘），只把记录取出交给收尾。
- 三个 `complete*` 收尾把记录传给 `commitMergeResolution(projectId, message, resolutions, merges, ctx, author…)`，提交后清空待决记录；
  `abortMerge` 同时删待决文件。崩溃恢复：`/status` 的 `documentMerges[].state` 从待决记录里读（`PENDING|MERGED`），
  前端据此不重跑已合并的文件。

### 4.5 xlsx / pptx 的合并文件由后端拼

- `POST /version/merge/resolve-structured`（JSON：`path`、`decisions`）：后端按决定拼合并文件后走 4.4 同一条落盘路径。
- `XlsxMerger`：以 MAIN 侧文件为底（POI `XSSFWorkbook`），对 `otherOnly` 与决定为 `T` 的单元格把 other 侧的值/公式写进去
  （`setCellValue`/`setCellFormula`，样式沿用该格已有样式；other 新增的工作表整表复制值）；决定为 `M` 的格不动。
- `PptxMerger`：以 MAIN 侧文件为底（`XMLSlideShow`），按 `sldId` 对齐页；`otherOnly` 与决定为 `T` 的页用
  `XSLFSlide.importContent(otherSlide)` 换内容，other 新增的页 `createSlide()` 后 `importContent`，other 删掉的页删除；
  页序按 other 侧的顺序（若两边都调了顺序 → 该文件 MANUAL，overlap 里给出「页序」一条）。
  LibreOffice 导出的 pptx 若不保留 `sldId`（spike 未覆盖，实现时用引擎导出一份验一次），退回按「标题 + 文本相似度」对齐，
  相似度低于 0.6 的页当作删除 + 新增。
- 自动模式（`decision=AUTO`）由前端在拿到 `/status` 后直接调本端点、`decisions` 留空，后端把 `otherOnly` 全部合入。

### 4.6 尾注（`ProjectRepoService`）

在既有 `X-AWD-Kind`、`X-AWD-Resolutions` 之外新增两条：

- `X-AWD-Merge-Context: adopt|cloud|session-end`——**所有**裁决提交都写（含只有整份三选一的），
  提交历史标签页据此把 MAIN/DRAFT 翻成对的话（修 §0 最后一条的反向文案）。
- `X-AWD-Merges: <path>=<mode>:<list>; <path>=...`——只在有 MERGED 路径时写。
  `mode` ∈ `auto|manual`；`list`：`auto` 时是 `M<n>,T<m>`（两边各合入几处），`manual` 时是逐处 `<key><side><action>` 用 `,` 连接
  （`X`/`F` 没有 side，写成 `p9X`、`p20F`），例：`合同.docx=manual:p3MA,p7TA,p12MA,p12TR,p9X,p20F,t1.2.3MA`；
  `p12MA,p12TR` 读作「第 12 段留了主线这边的、拒绝了另一边的」，`p9X` 读作「第 9 段自己改的」，`p20F` 读作「第 20 段另一边的格式改动没合」。
  超过 500 条截断并追加 `+N`。路径编码与 `X-AWD-Resolutions` 同一函数（`%` 先换，再 `;` `=` `\r` `\n`），`:` 与 `,` 不在路径里编码，
  解析时 `=` 后第一个 `:` 之前是 mode。
- `X-AWD-Resolutions` 的 `kept` 值域加 `MERGED`。老客户端/老解析看到 `MERGED` 落到默认分支（「两边都留」）——可接受的降级。
- `VersionEntry` 加 `mergeContext`、`merges: [{path, mode, counts|decisions}]`；`parseMerges` 与 `resolutionsTrailerValue`
  同处；`CommitTrailerContractTest` 加往返用例（含中文路径、`;=%` 路径、截断）。

### 4.7 逐段溯源 `ProvenanceService`

- `GET /version/provenance?fileId=&ref=HEAD` → `{ref, kind, units: [{key, textHash, sha, shortId, authorName, self, when, title, type}], truncated}`。
  `authorName` 走 `VersionAuthorResolver.preferredAuthorName`（`allowFetch=false`），`self` 走 `isSelf`，`type` 走 `HistoryTypeClassifier`。
- 算法：从 `ref` 沿该路径的历史（`RevWalk` + `FollowFilter`，跟重命名）往回走；
  `prov(c)` = 对 c 里每个单元：在任一父提交里找到「同一单元」→ 继承那个父的记录（先第一父，再第二父：合并提交里来自同事那一侧的段落
  归同事那一版而不是合并提交）；找不到 → 记 c 本身。「同一单元」判定（docx，§1.2 的降级口径）：把两版的单元 `norm` 哈希序列
  跑 JGit `HistogramDiff`，处于未变块里的单元 → 同一（继承）；处于变更块里的 → 本版改了它（记 c）。推论：改了一个字的段落、
  被剪切后粘到别处的段落、被删后重打一遍相同文字的段落（若 LCS 没把它对上）都归本版；两段一模一样的文字（空段、「（略）」）
  按位置对齐，对错了也是同样的文字、同样的语义。这就是全部精度损失，规格不再假装有稳定 id。
  xlsx 按单元格键，值相等即继承；pptx 按 `sldId`（或标题+文本相似度）对齐，文本相等即继承。
- 增量缓存：`<gitdir>/awd-cache/provenance/<pathHash>/<sha>.json`，`prov(c)` 只依赖 `prov(parents)` 与 c 的单元，
  所以缓存命中即停；提交钩子：`commitAll`/`commitMergeResolution` 成功后对本次变更的 docx/xlsx/pptx **异步**预算 HEAD 的 prov。
  首次请求老文件最多回溯 500 版，再往前的单元统一记 `{sha: null, title: "更早的版本"}` 并 `truncated=true`。
  单次请求超 30 秒返回 `{computing: true}`，前端 3 秒后重试（后台继续算）。
- 段落序漂移：响应里带 `textHash`，前端不按 `key` 硬对，而是把引擎 `get_document_text` 全量段落与 units 做一次 LCS 对齐
  （`utils/provenanceAlign.js`，纯函数），未对上的段落显示「本机未保存的改动」。

## 5. 前端

### 5.1 引擎侧新命令（`office_thread.js`）

- `build_merge_draft({baseBytes, mainBytes, otherBytes, mainAuthor, otherAuthor, plan, baseUnits, name})`：**一条 worker 命令做完整条链**
  （作者必须在同一命令内设置，§1.1 A2），结果留在实例里可编辑（不派发 `.uno:EditDoc`）。步骤：
  1. `load_document(otherBytes)` → `setRedlineAuthor(otherAuthor)` → `.uno:CompareDocuments(base)` → `list_revisions`：只为收集
     **格式类修订**（`type` 为 `ParagraphFormat`/`Format`）所在的段落序 → `formatOnly[]`（文字重放带不过来格式，§5.4 要告诉律师）。
  2. `load_document(mainBytes)` → `setRedlineAuthor(mainAuthor)` → `.uno:CompareDocuments(base)`：主线侧改动成为带作者的字符级修订
     （含表格、批注、格式）。
  3. 建「基线段序 → 当前段序」映射：走 `plan.mainChunks`（INSERT 块在当前文档里多出的段、DELETE 块的段仍在流里作删除修订），
     并对未变段逐一核对 `norm` 等于 `baseUnits[i].norm`，核对失败 → 返回 `{success:false, stage:'align'}`（前端退回整份三选一）。
  4. `setRedlineAuthor(otherAuthor)`，RecordChanges 开着，按 `plan.otherChunks` 中 `conflict=false` 的块从后往前重放：
     `MODIFY` → 对每个段 `applyMinimalRedline(para, newText)`（字符级 Myers，与 AI 改文档同一函数）；表格单元走 `table_set_cell` 同一内核；
     `INSERT` → 在映射位置后插入新段并写入文字；`DELETE` → 删除整段。从后往前是为了前面的段序不漂。
  5. 恢复 `humanAuthor`。返回 `{success, revisions: list_revisions 同形, mainCount, otherCount, conflicts: plan 里 conflict 块的键, formatOnly, elapsedMs}`。
  同一段两边都动过的（`conflict=true`）**不重放**：文档里只有主线侧的修订，另一侧的文字交给 §5.4 的三选一面板。
- `merge_take_other({paraKey, text, author})`：同段冲突「用律师乙的」——同一条命令内拒绝该段现有修订、切作者、`applyMinimalRedline` 写入对方文字并接受。
  **合并类 worker 契约只新增这两条**，其余复用 `list_revisions`/`resolve_revision(s)`/`resolve_all_revisions`/`export_document`。
  已知限制（写进 UI 与文档）：另一侧「只改格式不改文字」的段落与样式表/页面设置的改动不会自动带过来，只在面板里列出。
- `sheet_get_active_cell()` → `{sheet, address}`；`slide_get_current()` → `{slideNumber}`——溯源光标条用。
- 修订日期：引擎给的 `date` 是比较时刻，**界面不用它**；每处改动的时间 = 该侧版本的提交时间（M 侧 = 主线尖端那一版，T 侧 = 另一边尖端那一版），
  由前端按作者侧映射，界面显示「律师乙 · 9 月 13 日 21:58 · 结束工作：核对注册资本」。

### 5.2 自动合并流程 `composables/useDocumentMerge.js`（页面级，`project-overview.vue` 挂）

1. 任一 `/status` 带冲突对象（三语境同一个入口）→ 读 `documentMerges`；
2. 对 `decision=AUTO` 且 `state=PENDING` 的路径：docx → 向 `librePool` 申请一个**不绑定标签页**的实例（与备胎过继同形制，用完归还），
   取三份字节（`fetchVersionFileBytes` 三次：`mergeBase`、`mainlineTip`、另一侧 tip），`build_merge_draft` → `resolve_all_revisions(accept)`
   → `export_document` → `POST /version/merge/resolve-file(mode=auto)`；xlsx/pptx → 直接 `POST resolve-structured`。
   顶栏协作 chip 期间显示「正在合并同事的改动…」，不弹窗。`build_merge_draft` 回 `formatOnly` 非空或 `stage:'align'` 失败时
   **该文件不自动收尾**：前者按 MANUAL 打开合并比对稿（块 3 有条目），后者退回整份三选一并给出原因。
3. 全部路径都是 AUTO 且都成功 → 自动调用该语境的 resolve 端点（全 `MERGED`）收尾，toast「已自动合并同事对 N 份文件的改动」，
   `reload-files` 重载打开中的编辑器。任一路径 MANUAL/WHOLE 或自动合并失败 → 打开裁决总览（5.3）。
4. 幂等守卫：按 `(MERGE_HEAD, path)` 记「已在跑」，`/status` 120 秒轮询与面板刷新不会重复触发；引擎未就绪/非桌面端 → 不跑，
   docx 行退回整份三选一并显示「在桌面端可逐处合并」。
5. 三语境的触发点都是 UI 动作（结束工作、取回/立即上传、采纳一稿），所以引擎一定在；后台自动上传被拒只亮灯不整合（现状不变）。

### 5.3 裁决总览（改造 `AdoptConflictDialog.vue`）

标题按语境不变（「采纳时有几份文件要你做选择」等），每份文件一行，按 `kind`/`decision`/`state` 渲染：

| 行态 | 显示 | 操作 |
|---|---|---|
| docx AUTO 进行中 | 「正在合并同事的改动…」 | 无 |
| docx/xlsx/pptx 已 MERGED | 「已合并：你改的 3 处、律师乙改的 4 处」 | 「查看合并稿」（只读打开 5.4 标签页） |
| docx MANUAL | 「同一段两边都改了 · 2 处」 | 「打开合并比对稿」→ 5.4 |
| xlsx MANUAL | 展开表：单元格 / 共同的上一版 / 你的 / 律师乙的，每行选一边 | 全选完 → 本行「确定」→ resolve-structured |
| pptx MANUAL | 展开列表：第 N 页 缩略文本两栏，每页选一边；页序冲突单独一行 | 同上 |
| WHOLE（pdf/图片等） | 原三选一 + 原因句「PDF 与图片没有可比对的段落，只能整份选择」 | 原「对比」按钮保留 |
| docx 自动合并失败 | 「自动合并没成功（原因），请整份选择」+ 原三选一 | 「重试自动合并」 |

底部「确认选择」在所有行就绪时可点：MERGED 行提交 `MERGED`，其余提交所选。「先不采纳/先不结束/先不取回」照旧走 abort（待决记录一并清）。
面板外的 `.adopt-pending-bar` 文案改为「有 N 份文件等你裁决 / 去处理」。

### 5.4 合并比对稿标签页 `MergeReviewTab.vue`（tabType `merge-review`，单例 id `merge-review_{projectId}_{path}`）

- 顶部说明条：「合并比对稿 · 合同.docx · 你改了 5 处 · 律师乙改了 4 处 · 同一段两边都改了 1 处」+ 两侧版本信息
  （作者 · 时间 · 标题）+ 按钮「完成裁决」「先不处理」。
- 正文是可编辑的 LOWA 实例（复用 `LibreOfficeEditor` 的引擎装载，`mergeSpec` 代替 `file`，自动保存关闭，导出不走 upload），
  载入时跑 `build_merge_draft`。修订视图默认「全部」（`set_revision_view all`）。
- 右侧 `ReviewPanel` 加 `merge` 模式，三块：
  1. **「同一段两边都改了」**（置顶，来自 `conflicts`）：每处显示三栏文字「共同的上一版 / 你的 / 律师乙的」
     （文字来自后端 `analysis.overlaps`），三个按钮「用你的」「用律师乙的」「自己改」。「用你的」= 接受该段主线侧修订；
     「用律师乙的」= 拒绝该段主线侧修订后在同一条 worker 命令里以对方作者 `applyMinimalRedline` 写入对方文字再接受
     （新增 worker 命令 `merge_take_other({paraKey, text, author})`）；「自己改」= 光标定位到该段，律师手改，该处记 `X`。
     每处处理完打勾，未处理的挡住「完成裁决」。
  2. **修订按作者分两组**（「你」/ 对方展示名），每条带段落序与那一侧版本的时间；接受/拒绝走既有 `resolve_revision(s)`，
     「全部接受这一边」「全部拒绝这一边」两组各一对按钮（`resolve_revisions` 批量，按原生 id）。律师也可直接在正文里改字。
  3. **「律师乙改了格式、未自动带过来」**（来自 `formatOnly`）：列段落序与首 40 字，点行定位，旁边「查看律师乙那一版」
     打开既有 `VersionCompareTab`（base → 另一侧）只读对照，律师手动套格式。没有条目时整块不显示。
- 「完成裁决」：块 1 全部处理完才可点；若块 2 仍有未处理修订，按钮文案变「接受其余 N 处并完成」（未拒绝即保留，Word 的默认语义）；
  执行 `resolve_all_revisions(accept)` → `export_document` → `POST resolve-file(mode=manual, decisions)`；
  `decisions` 由面板逐条记录：块 1 每处一条（`p12MA`/`p12TA`/`p12X`），块 2 每条修订一条（接受 A / 拒绝 R，键取 `list_revisions`
  的 `paraKey`，表格用 `inTable+paraKey` 映射到 `t` 键），块 3 每条 `p<n>F`（格式未合并，仅记录）。
  成功后标签页变只读并提示「已裁决，回到裁决总览确认」，总览行态刷新为 MERGED。
- 「先不处理」只关标签页，不动待决记录；总览行仍是 MANUAL。
- `spec.readonly=true` 时（查看已合并稿）载入合并后的工作区字节与 base 比较，只读展示（复用 `compare_document` 现有链）。
- 非 docx 不走这个标签页。

### 5.5 逐段溯源界面

- `LibreOfficeEditor` 顶部工具栏右侧一条**溯源光标条**：光标所在段落对应的
  「韩泽伟 · 9 月 13 日 · 核对注册资本」（本人显示「你」，自动存档显示「自动存档」，未对上显示「本机未保存的改动」），
  点击 → `openCommitHistoryTab({focusSha})`。数据：`sel_changed` 与既有聚焦轮询触发 `get_review_context()` 取 `paragraphIndex`，
  查 `provenanceAlign` 的映射；文档保存落版后（`reload-files`/自动保存成功）重新拉 provenance。
  xlsx 用 `sheet_get_active_cell`，pptx 用 `slide_get_current`，同一条。
- `ReviewPanel` 加第三个标签「溯源」：按段落序列出（段落首 40 字 · 作者 · 日期 · 版本标题），点行 `select_paragraph`，
  点作者/版本跳提交历史；顶部按作者/版本聚合的一行摘要「本稿 62 段：你 40 段 · 律师乙 20 段 · 更早 2 段」。
  「悬停」在画布式引擎里没有逐段 DOM，因此产品口径是**光标条 + 侧栏**，spec 明确这一点。
- `CommitHistoryTab` 接 `spec.focusSha`：定位到含该 sha 的行（折叠进工作段的自动存档定位到所属行并展开），高亮 2 秒。

### 5.6 提交历史标签页增量

- 版本行副标题：`merges` 有 `auto` 项 → 「自动合并了{另一侧}对 合同.docx 的改动（你 3 处 · 律师乙 4 处）」；
  `manual` 项 → 「合同.docx 逐处裁决：第 3 段留了你的、第 7 段留了律师乙的、第 12 段两边都留、第 15 段拒绝了律师乙的」，
  超过 6 条折叠「等 N 处」，详情区全量列出。
- 「另一侧」与「你/律师乙」按 `mergeContext` 翻译：`cloud` → M=你这边、T=案件库那边（作者名取那一侧尖端提交的作者）；
  `session-end` → M=同事、T=你；`adopt` → M=主线、T=这一稿。既有 `resolutionKeptMain/Draft` 文案改为同一套翻译，
  `mergeContext` 缺席（老提交）保持旧文案。
- 事件行不新增（合并是本机动作，案件库侧无事件）。

### 5.7 i18n 与文案

`zh-CN/version.js` + `en-US/version.js` 同步新增；`glossary.md` 补「合并比对稿 / 裁决 / 溯源 / 共同的上一版」。

## 6. 表格与演示文稿、PDF 的范围口径（用户要求逐项写清）

| 类型 | 三方比对 | 自动合并 | 逐处裁决 | 溯源 |
|---|---|---|---|---|
| docx | 正文段落 + 表格单元格（同一序列，相邻算冲突） | 引擎比较 + 逐段重放 + 全部接受（另一侧只改格式的段不自动合） | 合并比对稿：同段冲突三选一 + 其余逐处接受/拒绝 | 段落级，光标条 + 侧栏 |
| xlsx | 单元格（值/公式） | 后端 POI 写入 other 侧改动格 | 总览里逐格选一边 | 单元格级，活动单元格 + 侧栏 |
| pptx | 页（sldId 对齐，文本比对） | 后端 POI `importContent` 换页 | 总览里逐页选一边 | 页级，当前页 + 侧栏 |
| pdf / 图片 / 其它 | 整份 | 无 | 原三选一，界面说明原因 | 无（侧栏显示「整份文件的最后修改」= 该文件最近一版） |
| 纯文本 | JGit 行级（现状） | 现状 | 现状整份 | 无变化 |

## 7. 测试与验证

- 后端单测（`BareHub` 形制）：`DocxUnitReaderTest`（段落/表格/paraId/归一）、`XlsxCellReaderTest`、`PptxSlideReaderTest`、
  `ThreeWayAnalyzerTest`（不重叠→AUTO、同段→MANUAL、相邻→MANUAL、表格格 vs 上一段相邻、xlsx 交集、pptx 页序冲突、无 base→WHOLE、pdf→WHOLE/BINARY）、
  `MergeResolveFileTest`（路径校验、字节落盘、待决记录、`MERGED` 校验 400、三语境收尾尾注、abort 清记录、崩溃后 `/status` 的 state）、
  `XlsxMergerTest`/`PptxMergerTest`（值/公式/样式保留、新增删除页）、`CommitTrailerContractTest`（两条新尾注往返、截断、编码）、
  `ProvenanceServiceTest`（线性历史归属、合并提交继承第二父、重命名跟随、缓存命中不重算、500 版截断、自动存档归属）、
  `HistoryEndpointTest`（`merges`/`mergeContext` 出参）。
- 前端单测：`tests/version-merge/{provenanceAlign,mergeRows,historyMerges}.test.mjs`；`file-kind` 加 `merge-review`。
- `lowa-e2e` 新增一组（沿用 spike 夹具形制：base/main/other 三份 docx，含表格与批注，5 个不同段 + 1 个同段）：
  `build_merge_draft` 后两位作者各自的修订数、`conflicts` 恰为那一段、同段冲突不被重放（该段文字 = 主线侧）、
  `merge_take_other` 后该段文字 = 另一侧、`resolve_all_revisions(accept)` 后 `get_document_text` 等于预期合并文本、
  另一侧只改格式的夹具落进 `formatOnly`、同实例第二次 `build_merge_draft` 仍成功、对齐核对失败返回 `stage:'align'`、耗时打印。
- `app-e2e` 新增一段旅程（实施时编号落在 J14，J12/J13 已被既有旅程占用；复用 J11 的 A/S/B 拓扑与 `restOverwriteAt`，上传真实 docx 字节）：
  ① A、B 从同一版各改不同段落 → A 取回 → 不弹窗、历史多一行带「自动合并了律师乙对 …docx 的改动」、正文含两边改动、退回到合并前那一版可用；
  ② A、B 改同一段 → 总览出现「同一段两边都改了 · 1 处」→ 打开合并比对稿 → 面板里拒绝律师乙那一处、接受其余 → 完成裁决 → 确认 →
  历史行副标题含「第 N 段拒绝了律师乙的」，`X-AWD-Merges` 尾注经 `/history` 出参断言；
  ③ 溯源：A 打开该 docx，光标放到 B 改的段落，光标条显示律师乙的名字与那一版标题，点击后提交历史标签页高亮那一行；
  ④ xlsx 两边改不同格自动合并、改同一格逐格裁决；pptx 同理逐页；pdf 行显示整份原因句。
- 「还原病灶即转红」由 Fable 亲自做：去掉相邻冲突判定 → `ThreeWayAnalyzerTest` 转红；去掉第二父继承 → `ProvenanceServiceTest` 转红；
  去掉 `MERGED` 校验 → `MergeResolveFileTest` 转红。
- 有头真机走查（Fable，需维护者空闲 > 180 秒）：本机桌面端对真实案件库走一遍 ①②③ 截图。

## 8. 部署与交付

- 案件库侧无变更（不改 jar 的必要：本次全部改动在桌面端后端与前端；`GitHttpController` 不动）。
- 桌面端随下一版发版；交付 = PR 合入 master + 三张卡落实记录 + `.claude/agents/version-control.md` 加「三方合并与溯源」一节、
  `doc-editor.md` 加 `build_merge_draft` 契约、`sidebar-shell.md` 加 `merge-review` 标签。
