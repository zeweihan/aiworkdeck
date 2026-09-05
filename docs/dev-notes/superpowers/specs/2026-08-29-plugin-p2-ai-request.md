# 插件生态 P2：开放 AI 调用权——桥 ai.request 走平台 Credits（设计定稿）

> dev-board#282，延续 #275 生态路线（docs/PLUGIN_API_ROADMAP.md §3 P2）。
> 对位 `vscode.lm`：插件免带 Key 调用用户已授权的模型，计费/配额/审计全在宿主。
> JAR 插件已有这条路（SPI `Llm.complete`，v2.4）；本篇把它开放给 **Web 插件**，
> 与 P0/P1 同一个发版周期实施（规范 v2.7）。

## 1. 为什么现在做

- 平台方向是「只有官方版，全走平台 Credits」——插件生态里任何 AI 能力都不该
  要求作者自带 Key（那会立刻分裂出计费旁路与密钥泄露面）；
- Web 插件是推荐形态（sandbox 真隔离），但它今天没有任何 AI 通路：`chat.send` 只能
  把 prompt 塞进对话面板（用户可见、异步、拿不到返回值），做不了「面板内静默调一次
  模型做结构化处理」这类场景（例：尽调工作台对一段材料做要素抽取并回填表格）。

## 2. API 形状

### 2.1 桥方法（PluginPane / SDK / 模板 / 模拟器四处同步）

```
ai.request  { prompt, system?, purpose? }  ->  { text, modelId }
```

- `prompt` 必填；`prompt + system` 合计 ≤ 16000 字符（超限 `quota_exceeded`）——
  上限刻意收紧：这是「面板内的一次性辅助推理」，不是对话通道，长文本处理该走
  `chat.send` 进编排器（有检查点、有工具、有审计 UI）；
- `purpose` 是给审计日志的自述短语（如 `"extract-parties"`），≤64 字符，可缺省；
- 返回 `text`（模型输出全文）与 `modelId`（实际使用的模型，如实回声）；
- **v1 不开模型选择、不开流式**：统一走辅助模型（便宜档，`AuxModelResolver.auxModelId()`，
  与自动打标签同一条）。理由：成本上界可控、形状最小。流式与 modelId 选项留给
  实验通道先行验证（`x-ai.requestStream`，见 P0 实验 API 机制）。
- SDK 糖衣：`awd.ai.request(prompt, opts?)` 返回 `text` 字符串（原始 result 走 `awd.call`）。

### 2.2 manifest 权限

`permissions` 新增合法值 **`ai`**（§3 权限表第五项）：「经宿主平台通道调用 AI 模型，
消耗用户 Credits」。Web 插件缺声明时桥直接 `permission_denied`（宿主端与服务端各拦一道）；
JAR 插件的 `Llm` 照旧不受此值约束（SPI 信任模型不同，见规范 §3 的自述/边界之分）。
广场受理时 `ai` 权限是人工审查重点项（烧的是用户的钱）。

### 2.3 服务端落点

`POST /api/plugins/{id}/ai/complete`（PluginController，与 invokeTool 相邻），
请求体 `{ projectId, prompt, system?, purpose? }`，响应恒 200：`{ code: 0|1, text?, modelId?, error? }`。

安全闸自上而下（对齐 invokeTool 的顺序与口径）：

1. 登录会话有效（401）；
2. 插件存在、已启用、未被平台封禁（404，不泄露存在性）；
3. manifest `permissions` 含 `ai`（403）——服务端是权威，桥端校验只是快速失败；
4. 调用者对 projectId 有项目写权限（403，与 invokeTool 同档：花钱的操作按写权限把关）；
5. 长度校验（16000 字符，200+code:1 `quota_exceeded`）；
6. 频控：`PluginHostQuota` 新增第三窗口 `acquireAi(pluginId)`，**10 次/分钟/插件**
   （只加不改：新方法新窗口 key `<pluginId>#ai`，既有两窗口语义不动）；
7. 执行：`PlatformAiUserScope.call(userId, () -> chatModelFactory.getAuxChatModel().generate(...))`
   ——余额闸（PlatformCreditsGate）、平台/BYOK 分流、区域判定全部继承既有链路，零新分支；
8. 记账：`tokenUsageService.recordUsage(projectId, userId, auxModelId, usage, null)` +
   `log.info("plugin {} ai.request purpose={} tokens={}")`——与 SPI `Llm.complete`
   同口径（pluginId 目前只进日志不落库，与 SPI 现状一致；usage 表加 pluginId 维度
   是独立欠账，两条路一起补，不在本期）。

## 3. 与既有面的关系

| 通路 | 使用方 | 计费身份 | 审计 |
|---|---|---|---|
| SPI `Llm.complete`（v2.4） | JAR 插件 | 发起用户 Credits | 日志带 pluginId |
| 桥 `ai.request`（本期） | Web 插件 | 发起用户 Credits | 日志带 pluginId + purpose |
| 桥 `chat.send`（v2.5） | Web 插件 | 对话编排既有计费 | 对话面板全程可见 |

`chat.send` 与 `ai.request` 的分工写进规范与 plugin-dev prompt.md：**要工具、要落文档、
要让用户看见过程 → chat.send；面板内部的一次性静默推理 → ai.request**。

Skill/Chat Participant 双向联动（路线图 P2 第二条）**拆出独立后续**：对话产出 →
插件面板订阅，依赖 P1 事件通道先在真实插件上跑熟，避免一次引入两个新推送语义。

## 4. 兼容与降级

- 老宿主对 `ai.request` 回 `unknown_method`，SDK 不做特殊处理，插件按既有降级约定处理；
- manifest 写了 `ai` 的插件装进老宿主：`permissions` 未知值只记 WARN（§2 既有行为），
  加 `minHostVersion: "0.28.0"`（P0）才能拿到明确的版本提示——模板默认生成即带。

## 5. 验证方案

- 后端：`PluginControllerAiCompleteTest`（401/404/403 权限缺失/403 项目权限/超长/频控/
  正常路径断言走 `PlatformAiUserScope` 与记账）；`PluginHostQuotaTest` 补第三窗口；
- 前端：`sdk-parity` 补 `ai.request` 方法存在性断言；模拟器返回固定假文本
  （`[simulated ai response]`），插件开发不必连真模型；
- 真机：hello-web-plugin 加一个「AI 摘要当前文件」按钮走通全链（狗粮）。

## 6. finalization 三问

1. **真实插件**：尽调工作台的要素抽取回填（第一个用户）；hello-web-plugin 示例（狗粮）。
2. **能跑的示例**：§5 的 hello-web-plugin 按钮。
3. **过窄/过宽**：刻意窄——单模型、非流式、16k 上限、10/分钟。放宽的每一步
   （流式/选模型/更高频）都走 `x-` 实验通道先验证再进正式面。
