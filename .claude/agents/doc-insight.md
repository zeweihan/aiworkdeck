---
name: doc-insight
description: 文档「解析」与「依据」窗格领域。任务涉及实体抽取（企业/法规/案例）、外部库检索（企查查 REST+MCP、北大法宝 MCP、判决书通道）、文档内部一致性校验（数量前后矛盾、统一社会信用代码硬错）、doc_insight_* 三张表与 /api/projects/{pid}/insight 时，先读本文档再动代码。
---

# 文档解析 / 依据窗格 领域地图

职责边界：用户在编辑器点「解析」之后的<b>后端全链路</b>——通读文档 → 抽三类实体 → 逐个打外部库 → 同时做文档内部一致性校验 → 落库供前端「依据」窗格轮询。
不含：编辑器侧的定位与一键替换（属 ai-doc-bridge），AI 对话里的 `qichacha_query` / `law_*` 工具（属 ai-chat，与本领域<b>共用下游、不共用代码</b>）。

dev-board#181（后端部分）+ #182。

## 关键文件

**服务层 `backend/src/main/java/com/checkba/service/insight/`**
- `DocInsightService.java` — 管线主体。`startParse` / `latest` / `entityDetail` / `refreshEntity` 四个公有入口，其余全是私有管线。
- `DocInsightExtraction.java` — 纯函数：切块、提示词、宽容 JSON 解析、确定性正则预抽取、按 normKey 合并去重。
- `DocInsightChecks.java` — 纯函数：文档内部一致性判定（数量矛盾 + 统一社会信用代码校验位）。**四类 finding 的 detailJson 形状全部定义在这里**（引用两类由服务层构造，形状注释仍只此一份）。
- `LawArticleNumbers.java` — 纯函数：中文条号 → 阿拉伯数字（`第二十条`→`20`、`第二十条之一`→`20.1`，转不动返回 null）、引文剥掉引用字样取「内容线索」。
- `DocInsightViews.java` — REST 视图 record。
- `InsightProperties.java` — `insight.*` 配置。

**实体与仓储**
- `model/entity/DocInsightRun|DocInsightEntity|DocInsightFinding.java`（表 `doc_insight_run` / `doc_insight_entity` / `doc_insight_finding`，索引写在 `@Table` 上，ddl-auto 自动建表）。
- `repository/DocInsight{Run,Entity,Finding}Repository.java`。

**控制器**
- `controller/DocInsightController.java` — `/api/projects/{projectId}/insight`。

**复用的既有件（不要另起一份）**
- `service/DocumentTextService.extractText(ProjectFile)` — 全文抽取唯一入口（PDF 走 PDFBox3、其余 Tika）。
- `service/ai/ChatModelFactory.getAuxChatModel()` + `service/ai/AuxModelResolver.auxModelId()` — 辅助模型与记账口径。
- `service/QichachaService.queryEciInfoJson(searchKey)` — 企查查 REST（平台代采/自备 Key 双档在它内部分）。
- `service/ai/mcp/McpClientService.callTool(server, tool, args)` — MCP 唯一入口。
- `service/evidence/EvidenceChecks` — `usccValid` / `usccPattern()` / `compact` / `stripOrgSuffix`。
  **这四个是本次为复用而放开的**（`usccPattern()` 与 `stripOrgSuffix` 是新增、`compact` 由包私有改公有），
  代码形状与后缀表全仓只此一份，别抄第二份出去。

## 数据模型

`doc_insight_run`：id / project_id / doc_file_id / status(RUNNING|DONE|FAILED) / phase（可读进度短语，前端直接显示）/ error / model / started_at / finished_at。
索引 `(project_id, doc_file_id, started_at)`。

`doc_insight_entity`：id / run_id / project_id / doc_file_id / kind(COMPANY|LAW|CASE) / name（展示名）/ norm_key（归一键，去重与缓存都按它）/ mentions_json / retrieval_status / retrieval_source / retrieval_json(TEXT) / retrieval_note / fetched_at。
索引 `(run_id)` 与 `(project_id, kind, norm_key, fetched_at)`（后者是 7 天缓存命中查询）。

`doc_insight_finding`：id / run_id / project_id / doc_file_id / kind(COUNT_MISMATCH|USCC_INVALID|CITATION_NOT_FOUND|CITATION_MISMATCH) / severity(warn|error) / title / detail_json(TEXT) / created_at。

`doc_insight_entity.retrieval_json` 里除检索结果外还可能有两个**并列**字段（法宝升级件补的，与 `result` 无关、检索没命中时可能是仅有的内容）：
LAW 的 `authoritative`（权威条文原文）、CASE 的 `recognition`（案号识别结果）。

**`retrieval_status` 四态的语义分工（最容易写错的一处）**
| 值 | 含义 | 典型来源 |
|---|---|---|
| PENDING | 还没轮到它 | 实体刚落库 |
| OK | 拿到结果 | 上游 200 |
| **UNAVAILABLE** | **通道不可用**——不是「查无此项」 | 法宝点数耗尽（401，2026-08-27 已续）、案例通道未配置、`GatewayException` |
| ERROR | 打了但失败 / 确实查无 | 企查查两条路都没查到 |

UNAVAILABLE 与 ERROR **必须**写 `retrieval_note`（可读中文）。窗格里显示「法宝检索本次不可用：账号点数耗尽」远好过一个空白格子——
这条正是本领域最常发生的状态（见「已知地雷」第 1 条）。

## 管线阶段

```
startParse（同步）：写权限 → 文件校验 → 单飞闸 → 落 RUNNING 行 → 返回 runId
  ↓ executor（专用 2 线程池，整段包在 PlatformAiUserScope.run(userId, …) 里）
① 读取文档   DocumentTextService.extractText，超 insight.max-chars 截断并在 phase 里写明
② 确定性预抽取  案号正则 + 书名号法规正则（正则那条腿保证下限，永不漏不编）
③ 逐块 LLM 抽取  chunk-chars=10000 / overlap=500，每块一次 getAuxChatModel().generate
                单块失败只跳过这一块；每块记一笔 token_usage
④ 合并去重   按 (kind, normKey)，出处累加（上限 max-mentions），展示名取最长的
⑤ 逐个检索   命中 7 天缓存则复制；否则打上游。**每个实体检索完立刻 save**
⑥ 法条引用校验 insight.citation-server 配了才跑（法宝 adjust_provisions）：逐个 LAW 实体
              （有条号 + 条号能转成阿拉伯数字，上限 30 个）→ 回填 authoritative +
              产出 CITATION_NOT_FOUND / CITATION_MISMATCH。**单条失败只跳过这一条**
⑦ 一致性校验  DocInsightChecks.run(claims, 全文) → 与⑥的发现一起落库
⑧ DONE + phase 摘要（N 实体 / M 检索成功 / K 处不一致）
任何顶层异常 → FAILED + 可读 error
```

**normKey 归一口径**
- COMPANY：去括号内容 → `EvidenceChecks.compact`（NFKC + 去空白 + 大写）→ 剥组织形式后缀。
- LAW：`compact(书名号内标题) + "#" + compact(条号)`。
- CASE：案号（全角括号转半角 + 去空白）；没案号时用标题。

**检索路由**
| kind | 通道 | 备注 |
|---|---|---|
| COMPANY | `QichachaService.queryEciInfoJson` → 查不到再 `qichacha-company` MCP 的 `get_company_by_query` 模糊搜索拿全称 → 用全称重打 REST | REST 只认工商全称，非全称回 Status 201 无结果；MCP 那把是**另一套凭证** |
| LAW | 有条号 → `pkulaw-semantic` / `get_article`；无条号 → `pkulaw-keyword` / `get_law_list` | |
| CASE | **先导步**：`insight.case-number-server` / `-tool`（yml 默认 `pkulaw-case-number` / `anhao_recognition`，参数 `text`）把案号标准化并给出法院/判决书标题/法宝链接 → 用**标题**去打 `insight.case-server` / `-tool` / `-arg`（yml 默认 `pkulaw-case-semantic` / `search_case` / `text`） | 识别只做加法：未配置 / 报错 / 空数组一律**静默走原路**（拿案号原文检索）。识别命中而全文检索失败 → 仍记 **OK**，`retrievalJson` 只有 `recognition`、note 写「仅返回案号识别结果」。换别家案例 MCP 只改配置；代码内缺省全为空。法宝另有关键词档 `pkulaw-case-keyword`（`get_case_list`）备用 |
| LAW 引用校验 | `insight.citation-server` / `-tool`（yml 默认 `pkulaw-citation-validator` / `adjust_provisions`） | 见「法条引用校验」一节。代码内缺省为空 = 整步跳过 |

## REST 契约（前端照这个接）

前缀 `/api/projects/{projectId}/insight`，鉴权走 `X-Session-Id`，未登录由 `GlobalExceptionHandler` 转 **HTTP 200 + `{"code":4010}`**，业务拒绝转 `{"code":1,"message":…}`。

**POST `/parse`** body `{"docFileId":10}` → `{"runId":7,"docFileId":10,"status":"RUNNING"}`（写权限）

**GET `/?docFileId=10`** →（读权限）
```json
{
  "run": {"id":7,"docFileId":10,"status":"DONE",
          "phase":"完成：实体 3 个，检索成功 1 个，发现 1 处",
          "error":null,"model":"qwen/qwen3.7-flash",
          "startedAt":"2026-08-27T10:00:00","finishedAt":"2026-08-27T10:02:00"},
  "entities": [
    {"id":31,"kind":"COMPANY","name":"京微资易科技有限公司","normKey":"京微资易科技",
     "retrievalStatus":"OK","retrievalSource":"qichacha+mcp","retrievalNote":null,
     "hasDetail":true,"fetchedAt":"2026-08-27T10:01:00",
     "mentions":[{"quote":"由京微资易科技持有","paragraph":null}],
     "detail":null}
  ],
  "findings": [
    {"id":41,"kind":"COUNT_MISMATCH","severity":"warn",
     "title":"标的公司 的「房产」前后不一致：58项 / 39项",
     "detail":{"subject":"标的","metric":"房产","unit":"项",
       "claims":[{"quote":"标的公司名下房产共 58 项","value":58,"unit":"项","numberText":"58","fixable":true},
                 {"quote":"附表二：房产明细共 39 项","value":39,"unit":"项","numberText":"39","fixable":true}]}}
  ]
}
```
`run` 为 **null** = 这份文档还没解析过（前端显示「点解析开始」，不是转圈也不是报错）。

**GET `/entities/{id}`** → 单个 `EntityView`，`detail` 为解析好的检索结果 JSON（读权限，跨项目 id 一律「条目不存在」）。
**POST `/entities/{id}/refresh`** → 同上，但绕过 7 天缓存重打上游（写权限）。

**列表瘦身、发现不瘦身**：`entities[].detail` 在列表里恒为 null（只给 `hasDetail`），一份文档几十个企业的工商全文有几百 KB，轮询接口不该每两秒搬一次；
`findings[].detail` **必须全量下发**——那里面是前端做定位与一键修改要用的数据，缺了整个 tab 就废了。

USCC_INVALID 的 detail 形状不同（没有 claims）：
```json
{"code":"91330100799655058C","quote":"…上下文…","reason":"统一社会信用代码校验位不符","fixable":false}
```

两类引用发现的 detail（都**没有** claims、**没有** numberText、`fixable` 恒 false）：
```json
{"kind":"CITATION_NOT_FOUND",
 "detail":{"lawTitle":"中华人民共和国民法典","citedArticle":"第九千九百九十九条","citedArabic":"9999",
           "quote":"…原文片段…","note":"可能条号有误或法规名不准，请人工核对","fixable":false}}
{"kind":"CITATION_MISMATCH",
 "detail":{"lawTitle":"中华人民共和国公司法","citedArticle":"第十五条",
           "citedText":"引用条目的权威原文（截 200 字，可能没有）","quote":"…原文片段…",
           "candidates":[{"title":"中华人民共和国公司法（2018 修正）","articleNumber":"16",
                          "snippet":"…（截 200 字）","url":"https://www.pkulaw.com/…"}],
           "note":"候选可能来自旧版法规（存在条文重编号），请人工核对现行版本","fixable":false}}
```

实体 `detail` 里的两个升级件字段（`GET /entities/{id}` 才下发）：
```json
{"source":"pkulaw-semantic","result":{…},
 "authoritative":{"title":"中华人民共和国公司法（2023 修订）","original_text":"…（截 500 字）",
                  "url":"https://…","implement_date":"2024-07-01"}}
{"source":"pkulaw-case-number",
 "recognition":{"text":"（2021）京01民终1234号","caseFlag":"（2021）京01民终1234号",
                "court":"北京市第一中级人民法院","title":"甲与乙合同纠纷二审民事判决书",
                "url":"https://www.pkulaw.com/pfnl/…","gid":"…"}}
```

## 一致性校验与「一键修改」契约

前端一致性校验 tab 的两个动作：① 点条目 → 用 `quote` 在文档里做只读定位；② 点「修改建议」→ `quote.replace(numberText, 新数字)` 做机械替换。
因此 **`numberText` 必须是 `quote` 里的逐字子串，`quote` 必须是文档正文的逐字片段**。

`fixable` 的三个前置条件（后端校，缺一即 `fixable:false`）：
1. `numberText` 非空且是 `quote` 的逐字子串（模型把「1,000」改写成「1000」就会栽在这里）；
2. `quote` 能在抽取出的文档正文里逐字 `contains` 到（模型没做到「逐字摘抄」就会栽在这里）；
3. 同一条 finding 里各 claim 的**单位字面量一致**（「58 万元」与「39 元」机械替换会把量级换错）。

**不可修复时后端不下发 `numberText`**（JSON 里没有这个键），只给 `fixable:false` + `fixableReason`。
给一个对不上的串比不给更危险——前端 `replace` 会静默换错地方。条目本身**仍然展示**：用户要看到这个矛盾，只是不给按钮。

判定的保守口径（宁可漏报不误报）：
- 主体：`compact` 后全等、或再剥组织形式后缀全等，才算同一个主体。
- 单位：按**基准单位**分组（万元/亿元/元 → 元；项 → 项；缺省自成一组）。基准单位不同不进同一组。
- 同组里出现 ≥2 个**归一后不同**的数值才报。1,000 万元 与 10,000,000 元 归一后相等，不报。
- 统一社会信用代码只报**自身校验位不符**（陈述的硬错）。「文档里的码与企查查返回的不一致」不在这里报——那属于外部检索结果。

## 法条引用校验（法宝 `adjust_provisions`）

三种上游语义（2026-08-27 实测，**照此判定，别自行改判**）：
- `userlaw=[{title,article_number:"15"}]`（阿拉伯数字字符串）→ 返回该条权威条目 `[{title(含修订版), article_number, original_text, url, issue_date, implement_date}]`；
- 加 `answerlaw=[{title,text:<内容线索>}]` → 数组里除引用条目外还有**按内容定位到的候选**；
- 条号不存在（民法典第 9999 条）→ 返回**空数组**。

判定（保守，宁漏报不误报）：
| 上游返回 | 结果 |
|---|---|
| 空数组 | `CITATION_NOT_FOUND`（warn） |
| 有 `article_number` == 引用条号的条目 | 回填该实体 `retrievalJson.authoritative`，本身不算错 |
| 发了 answerlaw 且**条号不同**的候选非空 | `CITATION_MISMATCH`（warn），候选上限 3 条 |
| `"Error"` 前缀 / 抛错 / 形状不是数组 | **跳过该实体，不产生 finding**（校验不可用 ≠ 引用有错） |

- 条号先经 `LawArticleNumbers.toArabic` 转换，**转不动就跳过**（不猜条号去打上游）。
- 引文剥掉「《…》第…条」后剩余 ≥12 字才带 `answerlaw` + `prompt`。
- **候选条号绝不能拿来自动改写文档**：内容命中的可能是旧版法规的旧条号（2018 修正第十六条 = 2023 修订第十五条）。这就是两类引用发现恒 `fixable:false` 且不下发 `numberText` 的原因。
- 上限 30 条／轮。

## 配置

`application.yml`：
- `mcp.servers` 新增 `qichacha-company`（`https://agent.qcc.com/mcp/company/stream`，token `${QICHACHA_MCP_TOKEN:}`，在线覆盖键 `external.qichacha.mcpToken`，30s）。
- `insight.*`：`case-server`（yml 默认 `pkulaw-case-semantic`）/ `case-tool`（`search_case`）/ `case-arg`（`text`）/
  `case-number-server`（`pkulaw-case-number`）/ `case-number-tool`（`anhao_recognition`）/
  `citation-server`（`pkulaw-citation-validator`）/ `citation-tool`（`adjust_provisions`）/
  `chunk-chars` / `chunk-overlap` / `max-chars` / `cache-days` / `max-entities` / `max-mentions` / `stale-minutes`。
  后四项 server/tool 的**代码内缺省全为空**（= 那一步不做），真实缺省值只在 yml——照 `case-server` 的先例。

`application-prod.yml` 不重复定义 `mcp` 列表（抄一份必然漂移），只在 `external.qichacha` 下留了 `QICHACHA_MCP_TOKEN` 的部署清单注释。

## 已知地雷

1. **法宝点数已于 2026-08-27 恢复**（新 token 在 `backend/.env` 的 `PKULAW_TOKEN`，法宝 MCP 控制台 mcp.pkulaw.com；**全部 10 个 server 当天逐一 `tools/call` 实测有真数据**，完整清单与工具面见 application.yml mcp.servers 注释与 EXTERNAL_SERVICES.md 法宝行）。管线当前用其中六个：semantic / keyword / case-semantic / qichacha，外加 **2026-08-27 接入的两个升级件**——`pkulaw-case-number` 的 `anhao_recognition(text)`（CASE 检索先导步，见检索路由表 CASE 行）与 `pkulaw-citation-validator` 的 `adjust_provisions`（法条引用校验，见专节）。仍未接的：`pkulaw-doc-link` 能为整段文本加法宝超链接；`pkulaw-fatiao` 的 `get_law_item_content` 是按数字条号取法条的另一条路。接入时按 InsightProperties 的配置化先例来。若再见 `tools/call` 401「checking remaining points」= 点数又耗尽，是账务问题不是回归：LAW/CASE 实体一律 UNAVAILABLE + note 带上游原话，先查点数不要改代码。
   同理 `QICHACHA_MCP_TOKEN` 缺省时企业模糊搜索静默降级为「只认工商全称」——不报错，只是简称查不到。
2. **`PlatformAiUserScope` 不跟随线程池**。管线跑在自己的池里，`startParse` 提交时必须
   `PlatformAiUserScope.run(userId, …)` 包住**整段**（不只是模型调用那一行）；`refreshEntity` 在控制器线程上跑，同样要包。
   漏了的表现是云多租户下抛「本次 AI 调用未携带用户身份」——一个与真实原因毫无关系的提示。
3. **提示词里有 `%`（「股权比例 51%」），绝不能用 `String.formatted()` 拼**：`%、` 会抛
   `UnknownFormatConversionException`，而调用方对单块失败是「跳过」——**整个 LLM 抽取会静默退化成只剩正则那条腿**，
   run 照样 DONE、只是企业实体一个都没有。本次开发实测踩过，现已改成字符串拼接并留了注释。
   同理任何新加的提示词模板都别用 `formatted`。
4. **工具/上游返回的失败判据是 `"Error"` 前缀**（`McpClientService.callTool` 找不到 server / 未启用 / 传输失败都返回该前缀的字符串而**不抛异常**）。
   新接通道时照 `callAndStore` 的判据走，别只判异常。
5. **`GatewayException` 不能被压成「查无此企业」**：它是平台代采网关的分类失败（余额不足 / 未开放 / 网关不可达），
   必须落 UNAVAILABLE 并把 `userHint()` 带上。`QichachaService` 刻意没把它裹进自己的 `catch(Exception)`，本领域也别裹。
6. **崩溃会留下僵尸 RUNNING**。`reapOrReject` 按 `insight.stale-minutes`（30 分钟）收尸，
   否则一次进程崩溃会让这份文档**永远**解析不了。改单飞逻辑时别把这段删了。
7. **测试断 RUNNING 中间态必须用 CountDownLatch 卡住后台 mock**（先落中间态再 submit 的服务，
   不卡住就是间歇红——#394 的教训）。`DocInsightServiceTest.中间态与单飞` 是现成的写法。
8. `Response.from(AiMessage.from(text))` **不带 tokenUsage**，记账断言会永远是空的。单测里用带 usage 的重载
   （`DocInsightServiceTest.modelReply`）。
9. 本轮**不加 AI 工具**：UI 直连 REST，`AgentOrchestrator` / `ToolRegistry` / `RealToolBeans` 一行没动。
   将来要给模型开一个 `doc_insight` 工具，记得同步 `RealToolBeans.instantiateAll()`（ai-chat 领域的既有地雷）。

## 前端（dev-board#182）

### 关键文件

- `frontend/src/components/InsightPane.vue` + 同目录外置样式 `insight-pane.scss` —— 「依据」窗格本体，两个 tab（外部检索 / 一致性校验）。
- `frontend/src/utils/insightMatch.js` —— 纯函数：`cursorWindow` / `matchEntityAt`（光标邻域命中实体）、`buildFixedQuote` / `fixSuggestions` / `fixBlockReason` / `findingLocateQuote`（一键修改的替换串）。**不许 import Vue/uni/i18n**（`node --test` 直接导入，且客体页 `editor-main.js` 也 import 它取 `CURSOR_RADIUS`）。
- `frontend/src/utils/insightDetail.js` —— 纯函数：把 COMPANY/LAW/CASE 三种 `detail` 整形成可渲染的行，外加 `authoritative`（权威条文原文）/ `caseRecognition`（案号识别行）/ `citationDetail`（两类引用发现）。上游形状是别人家的，一律「认得的列出来、认不得的落原文兜底」。
- `frontend/src/config/panelRegistry.js` —— `insight` 一条（`defaultDock:'right'`、`allowedDocks:['left','right']`，**不给 bottom**：底栏放不下判决书全文）。
- `frontend/src/services/api.js` —— `parseDocInsight` / `getDocInsight` / `getDocInsightEntity` / `refreshDocInsightEntity`。
- 宿主接线在 `frontend/src/pages/project-overview/project-overview.vue`（右栏 `rightPaneKey==='insight'` / 左栏 `leftPaneKey==='insight'` 两条显式分支 + `isInsightDoc` / `getInsightExecutor` / `onOpenInsight` / `onInsightEntities` / `onEditorCursorContext`）。
- 编辑器侧：`EditorToolbar.vue` 的「解析」按钮（`toggle-insight`）→ `LibreOfficeEditor.vue` `onToggleInsight` → `open-insight` → 宿主。

### 事件流

```
工具栏「解析」 → open-insight{fileId} → 宿主 openPanelInItsDock('insight')（被拖去左栏就开左栏）
                                     → insightParseRequest={fileId,token} → 面板 consumeParseRequest → POST /parse
面板 GET / 轮询 2s（只在 RUNNING 时排下一发，beforeUnmount 必清）
面板 → @entities{docFileId,entities[]} → 宿主 _insightIndex（非响应式，同 _libreRefs 口径）

画布单击 / 光标移动 → 客体页 editor-main.js（**仅在订阅打开时**）get_cursor_context
   → lo-relay {type:'cursor-context', payload:{before,after,paragraph,meta:{metaKey,ctrlKey}}}
   → LibreOfficeEditor $emit('cursor-context') → 宿主 onEditorCursorContext（先用 _insightIndex 匹配一遍）
   → :cursor-context prop → 面板 matchEntityAt → Cmd/Ctrl 点击=选中并展开详情；普通点击/光标移动=被动高亮
```

订阅开关是宿主 → 客体页的下行消息 `{__lo:'lo-relay', type:'insight-sub', enabled}`，
由 `LibreOfficeEditor` 的 `insightSubscribed` prop 驱动（`ready` 时补发一次）。
**不订阅时客体页一次 `get_cursor_context` 都不打**——没开窗格的用户零开销。

### 定位与一键修改的口径（硬约束）

- **定位一律 `find_navigate`**，绝不用 `find_text_locations`：后者每个匹配插一个 `__ai_anchor_*` 书签、会随 docx 落盘（doc-editor.md 明令禁止）。空 quote 一条命令都不发（不拿标题去全文查找）。
- **一键修改必须恰好唯一命中**：先 `find_navigate` 数一遍（`total===1`），再 `find_replace {replaceAll:true}` 并复核 `replaced===1`。非唯一 / 未命中 / 引擎报 `replaced≠1` 一律**一个字都不改**，走面板内联提示（`.ip-notice`）——**不用 `uni.showToast`**，编辑器场景 toast 被 webview 遮挡 = 静默失败（dev-board#133 的定性）。
- 替换串由 `buildFixedQuote` 生成，`quote` 里 `numberText` 出现不止一次即拒绝（`ambiguous`）——换第一处还是第二处没有依据。
- 改动经 `find_replace` 落成一条修订（`nativeTrackedReplaceAll` 会掐掉公共前后缀，只对差异中段留 redline），随后编辑器的 modify 监听器自动保存。处置后前端把该条 finding 标「已修改」（灰显 + 「可用 Cmd+Z 撤销」），提示重新解析可刷新结论。

### 其它口径

- 窗格绑「当前活跃的 **writer** 文档」（`isInsightDoc`：`useLibreEditor` 且扩展名在 Writer 子集里；表格/演示/PDF 不进）。分屏时跟 `focusedPane`，点哪一侧的工具栏就把焦点挪到哪一侧。
- **工具栏「解析」对已经解析过的文档不重跑**（一次解析 = 一次 LLM + 一串外部库调用），只把面板开出来；`status==='FAILED'` 那次不算结论，照样重跑。面板里的「重新解析」是用户明示的，force 重跑。
- `parseRequest` **必须带 fileId**：面板是 v-if 挂载的，点完解析才挂出来（watch 看不到变化，所以 mounted 也认一次）；不带 fileId 会让「在 A 文档点过解析、随后切到 B 开面板」把 B 也解析掉。
- 列表瘦身的另一半在前端：`detail` 展开时才 `GET /entities/{id}` 并缓存在组件里；`hasDetail:false` 的一次都不打。
- UNAVAILABLE / ERROR 的 `retrievalNote` **必须显示**（`.ip-note`），旁边给「重试」（`POST /entities/{id}/refresh`，要写权限）。
- 两类引用发现（CITATION_*）在一致性 tab 里只列不改：`fixSuggestions` 看的是 `detail.claims`，引用 detail 没有这一段，天然落不到「修改建议」那一支；点条目仍按 `detail.quote` 定位。CITATION_MISMATCH 的「引用条文」是折叠段（`citedOpen`）。
- 法宝外链（权威条文 / 案号识别 / 引用候选）走 `@open-url` → 宿主 `openBrowserTab`（与 MarketDetailPane 同一条既有事件），**面板自己不 `window.open`**：桌面端要走内置浏览器面板。
- 视觉：浅色 + `--awd-panel-*` 密度令牌；**面板不自画标题**（左栏由外壳 `.sidebar-header` 出、右栏由 dock tab 出）。
- **已知视觉变化**：`insight` 默认停右栏，于是 AI 面板顶部从此恒有一条 dock tab 条（「AI 助手 | 依据」）。此前 `rightDockPanels` 常为空、那条 tab 条不渲染。

### 前端验证

```
cd frontend && npm run test:insight        # 83 条（纯函数 58 + 组件级 25）
cd frontend && npm run test:panel-dock     # 注册表自洽 + 停靠回落
cd frontend && npm run check:emits && npm run check:nav && npm run check:locales
cd frontend && npm run build:h5 && npm run build:zetaoffice   # 改 editor-main.js 后必须重建 glue
```
`tests/insight/insightPane.test.mjs` 把 `InsightPane.vue` 的 `<script>` 抽出来跑（同
`tests/evidence/panelFilters.test.mjs` 的路子），锁的是三条真会花钱/改文档的不变式：
已解析不重跑、唯一命中才替换、卸载清定时器。给 `InsightPane.vue` **新加一个 import 时，
必须同步往那份测试的 `deps` 里加一条**——harness 是把 import 行剥掉、依赖当形参喂进去的，漏了就是 ReferenceError。
**改这三处前先确认对应用例会转红**
（2026-08-27 逐条做过还原病灶的对拍）。

## 验证

```
cd backend && mvn test -Dtest='DocInsight*,LawArticle*'   # 58 条
cd backend && mvn clean test                      # 全量（跨类常量内联，验证阶段一律 clean）
```
- `DocInsightChecksTest`（19）：数字归一（千分位/万/亿）、同主体命中、不同主体与不同基准单位不误报、
  后缀剥离等价、USCC 校验位与去重、**fixable 三条降级分支**、空输入。
- `LawArticleNumbersTest`（7）：十/百/千组合与「零」、`之N`、已是阿拉伯数字（含全角）、
  **转不动一律 null**、引文剥引用字样取内容线索。
- `DocInsightServiceTest`（26）：RUNNING 中间态（CountDownLatch）、单飞、企查查降级链、网关失败落 UNAVAILABLE、
  法宝不可用不连坐、案例通道从「未配置」到「配上就接入」、**案号识别命中改用标题检索 / 识别失败静默走原路 /
  识别命中但全文失败仍 OK**、**引用校验三种判定 + 通道未配置整步跳过 + 通道报错不报发现 + 上限 30**、
  7 天缓存与 refresh 绕过、列表瘦身/发现不瘦身、
  读不出文字与辅助模型未配置 → FAILED、单块坏输出不炸整轮、鉴权与跨项目 IDOR。
- `DocInsightControllerTest`（6）：4010 信封、code=1、参数透传、响应形状、路由不互相吃。
