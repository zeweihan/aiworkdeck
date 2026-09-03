# Word 整篇过卷工具 `office_pass_step` 设计（2026-09-03）

关联：dev-board#419（整篇校对卡死的根因与批量原语）、dev-board#421（提示缓存）。本文是本卡的权威源。

## 1. 要解决什么

整篇校对 / 整篇润色 / 统一称谓 / 全文替换这类「一处一处改、处数与文档长度成正比」的任务，
在 Word 插件里只能走 agent 循环：模型读全文、决定改哪里、调工具落笔。#419 之前每处一轮，
撞 30 步上限；#419 之后有了 `office_replace_batch`，一批 50 处，但模型仍要在一轮里
把整篇几十上百处一次列全，长文档下要么漏、要么单次输出超长被截断整轮丢弃。

目标：让模型**按块推进**整篇任务，每块聚焦、每块落笔、进度可见、可停可查，
**判断全部由主模型做**，后端只做机械事（切块、维护游标、校验、落笔、汇总）。
不引入任何子模型调用。

## 2. 硬约束（维护者拍板）

1. **主模型全上下文**：内联正文（system prompt 里的 inlineContent）一字不动，每轮都在；
   块只是「本轮请你处理的工作面」。块与块之间的勾稽关系（A 块改了称谓，C 块要跟着改）
   由主模型自己把握，工具不做任何跨块推断。
2. **修改清单按全文落笔，不限于当前块**：edits 走 `replace_batch` 的全文查找语义，
   模型在处理 C 块时可以把 A 块的连带修改写进同一批。
3. **工具里没有模型**：切块、校验、落笔、汇总全是确定性代码；工具描述与 system prompt
   只告诉模型「何时用、怎么用」，不替模型做取舍。
4. **进度要在任务窗格里可见**：「校对 3/12 段」逐步更新，不是一个转圈的「正在操作文档」。
5. 仅 Word 面（Office Word + WPS 文字）。Excel / PPT 会话看不到这个工具。

## 3. 工具契约

### 3.1 `office_pass_step(conversationId, editsJson, stop)`

后端 `OfficeEditTools` 新增，`@ToolMeta(displayName="分段过卷", category="office", fileEffect="MODIFIED")`。

| 入参 | 类型 | 说明 |
|---|---|---|
| `conversationId` | String | 系统注入 |
| `editsJson` | String | 对**上一块**（或任意位置）的修改清单，JSON 数组 `[{searchText, replaceText}]`；首次调用传 `[]`；本块无需修改也传 `[]` |
| `stop` | boolean，可选 | `true` = 提前结束过卷，返回汇总并清状态；默认 false |

返回值（JSON 字符串，字段固定，模型据此推进）：

```json
{
  "pass": { "chunk": 3, "total": 12, "chunkChars": 2480, "done": false },
  "applied": { "replaced": 4, "requested": 5, "failed": [ { "index": 2, "searchText": "…", "error": "…" } ] },
  "paragraphs": [ { "no": 41, "text": "…" }, { "no": 42, "text": "…" } ],
  "hint": "这是第 3/12 块（第 41–52 段）。请只针对这一块判断是否需要修改；发现其它块需要连带修改也可一并写入，清单按全文查找落笔。改完把清单传给下一次 office_pass_step；本块不需要改就传 []。"
}
```

- 首次调用（会话无进行中的过卷）：忽略 edits 以外的语义，切块并建状态，返回第 1 块，`applied` 为 `null`。
- 中间调用：先对 edits 走 `office_replace_batch` **同一套校验与落笔**（校验失败整批拒绝、返回 `Error:` 字符串、**游标不前进**，模型修正后重试），再返回下一块。
- 末块：返回 `{"pass":{"chunk":12,"total":12,"done":true},"applied":{…},"summary":{"chunks":12,"replaced":37,"failed":[…全程累计…]},"paragraphs":[]}` 并清状态。
- `stop=true`：立即返回 summary 并清状态；edits 若非空仍先落笔。
- 过卷进行中调用 `office_replace_text` / `office_replace_batch` 不受限（模型可用它们补失败条目）。

`failed` 条目不阻塞推进；模型可在下一次调用的 edits 里换更长、更唯一的原文重试（全文查找语义使之可行）。

### 3.2 切块规则（`OfficePassChunker`，纯函数，单测覆盖）

- 输入：`InlineContentCache` 里该会话的内联正文（与模型看到的完全同一份字节；取不到 = 返回 `Error: 当前会话没有内联正文，请先确认文档已在 Word 中打开`）。
- 按段落切（`\n` 分隔，保留原段落序号 `no` 从 1 起，与内联正文段落一一对应；空段落计入序号但不计入块内容）。
- 目标块大小 `TARGET_CHARS = 2500`，不切断段落；超过 `2 × TARGET` 的单段独立成块（不拆段）。
- 块数上限 `MAX_CHUNKS = 60`：文档太长时按 `ceil(len / 60)` 抬高目标块大小，保证 total ≤ 60。
- 块边界在一次过卷内不变（状态里存的是段落序号区间，不是文本副本）。
- 表格文本在内联正文里已是行文本，不特殊处理。

### 3.3 过卷状态（`OfficePassStateStore`）

- key = conversationId；值 = `{chunks: [[fromNo,toNo],…], cursor, startedAt, replacedTotal, failedAll[], contentHash}`。
- 内存 + TTL 30 分钟（与 `InlineContentCache` 同款寿命语义）；进程重启即丢，模型重新开始过卷即可。
- 会话取消（用户点停止）→ `AgentOrchestrator` 的取消路径顺手清掉该会话的过卷状态。
- `contentHash` 与内联正文 hash 不一致（文档中途被换）→ 返回 `Error: 文档内容已变化，过卷已终止，请重新开始`，清状态。

### 3.4 编排器步数规则

`AgentOrchestrator.MAX_LOOP_DEPTH = 30` 不动。新增：当前会话存在进行中的过卷时，本轮允许的深度 = `min(30 + total, 120)`。实现点在 runLoop 判超限处，只读 `OfficePassStateStore`，不引入其它耦合。达到 120 仍未完成 → 现有 `paused / max_depth` 语义，状态保留，用户可续。

### 3.5 何时调用（提示词与工具描述，两处同一口径）

- 工具描述：「用户要求对**整篇 / 全文 / 所有**做逐处修改（校对错别字与病句、整篇润色、统一称谓、全文替换某类表述）时，必须用本工具分块推进，不要试图一轮列全整篇的修改。单处、几处或选区内的修改仍用 office_replace_text / office_replace_batch。首次传 `[]` 拿第一块；每块判断后把清单传给下一次调用；本块不需要改传 `[]`；提前结束传 `stop=true`。清单按全文查找落笔，发现其它块要连带修改可一并写入。」
- `ContextAssemblerService` Word 分支末位（紧接 #419 那段之后，保持「约束挂末位」）：同一口径的一段，只对 `OfficeHost.WORD` 输出。
- 预置指令「校对错别字与病句」文案改为「请分段过卷校对全文的错别字与病句，以修订模式逐处修改」（zh/en 同改），让触发词与规则对齐。

### 3.6 进度展示（插件任务窗格）

- 后端每次 `office_pass_step` 返回后，向该会话 SSE 发一条 `pass_progress` 事件：`{chunk, total, replaced, done}`（若既有 SSE 契约里已有可承载工具结果摘要的事件，则复用它并在契约文档里写明字段；不新增第二套）。
- `ChatView.vue` 在「正在操作文档…」那个位置，过卷进行中改显示「校对 3/12 段 · 已改 7 处」；`done` 后恢复常规状态。文案走 i18n（zh/en）。
- 过程卡工具名登记 `frontend/src/utils/toolDisplayNames.js`（`ToolDisplayNameCoverageTest` 护栏）与 `office-addin/taskpane/lib/i18n.js`（`cmdPassStep`）。

## 4. 不做的事

- 不调用任何子模型；不做「先摘要再判断」；不做跨块自动一致性检查。
- 不新增插件端宿主命令：落笔复用 `replace_batch`（Office Word 与 WPS 文字两面已有）。
- 不改 `office_replace_batch` 的语义；抽出的共享校验只是把现有代码搬到可复用的位置。
- 不做 Excel / PPT 面。
- 不改计费与模型定价建模。

## 5. 测试面（先红后绿，还原病灶要转红）

后端：
- `OfficePassChunkerTest`：段落边界不切断；超长单段独立成块；空段落序号连续；`MAX_CHUNKS` 抬块；块边界稳定（同输入两次切块结果相等）。
- `OfficePassStateStoreTest`：首次建态；推进；末块清态；stop 清态；TTL 过期；contentHash 变化终止。
- `OfficeEditToolsTest` 新增：首次调用返回第 1 块；中间调用先落笔（桥收到 `replace_batch` 命令与原清单）再返回下一块；校验失败游标不前进；末块 `done=true` 且 summary 累计正确；Excel/PPT 会话不可见（`ToolRegistryCapabilityFilterTest` 同款断言）。
- `AgentOrchestratorTest`（或既有深度测试）：过卷进行中深度上限为 `min(30+total,120)`，无过卷时仍为 30。
- `ContextAssemblerServiceTest`：Word 分支出现过卷指引且在 #419 那段之后；Excel/PPT 不出现。
- `ToolDisplayNameCoverageTest` 登记。

插件端：
- `ChatView` 或 `chatSession` 的用例：收到 `pass_progress` 后状态文案为「校对 3/12 段」；`done` 后恢复。
- `npm test` 全绿、`npm run build` / `build:wps` 通过。

收尾走查（真机，维护者或会话在 Word 里做一次）：一份 2 到 3 万字合同点「校对错别字与病句」，看到进度逐块走完、修订落笔、过程卡显示「分段过卷」。

## 6. 发布面

后端 jar（云后端两台 + 桌面补丁）与插件静态（两站）必须成对发：新提示词会让模型调用 `office_pass_step`，旧后端没有该工具则模型调不到，旧插件端没有进度事件只是看不到进度、功能不受影响。
