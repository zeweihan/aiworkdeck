# AI Harness 差距分析与加固记录（2026-08）

对标对象：Nous Research hermes-agent、OpenHands（原 OpenDevin）、Claude Code / Agent SDK、LangGraph（durable execution）、smolagents；2026-08-14 起新增 DeepSeek Harness（dsh，见第五节）。
触发背景：用户反馈 AI 功能不稳定——"经常断了，或者跑一半就不跑了"；多样化诉求（整理文件夹/重命名/移动/做表格）覆盖不稳。

## 一、"跑一半停了"的实证根因（按概率排序）

代码审计在本仓库找到 16 个失败模式（F-01~F-16），与开源 harness 对标后确认四大根因：

| # | 根因 | 证据 | 本轮修复 |
|---|---|---|---|
| 1 | **LLM 单轮 120s 硬超时**：langchain4j 0.36 把一个 timeout 灌进 OkHttp callTimeout（整通调用墙钟上限），长轮次即使 token 稳定在吐也会被第 120 秒掐断 | ChatModelFactory + application.yml `open-router.timeout` | 已修：timeout 600s + 流无活动看门狗 180s（AgentStreamHandler.armInactivityWatchdog） |
| 2 | **流式调用零重试**：0.36 的流式模型结构上不支持 maxRetries，一次 429/5xx/断连整轮报废 | AgentOrchestrator onError 直接终局 | 已修：零 token 轮次的瞬时错误按 8/16/32s 指数退避重放本轮（对标 OpenHands RetryMixin），4xx 不重试 |
| 3 | **编辑器桥失败被判成功**：`{"error":...}` 不匹配 "Error" 前缀启发式，编辑器超时/未打开时模型一路"绿勾"空转 30 步后 PAUSED | ToolRegistry.ToolResult.success() | 已修：success() 识别 JSON error 形态；scan_files 补 Error 前缀 |
| 4 | **断线无自愈**：心跳生产者是死代码、前端零自动重连、重连窗口期快照缺失导致 isStreaming 永久锁死 | SseEmitterService / useAgentStream | 已修：SSE 层 15s 心跳广播 + 前端心跳超时判活/指数退避重连/online+visibilitychange 钩子 + RUNNING 无条件发 state_recovery + 终态事件穿透气泡守卫 |

其余已修：截断的 `<tool_code>` 不再静默正常收尾（回喂纠正，最多 2 轮）；工具参数 JSON 解析失败不再静默空参硬跑；@Async 线程池显式配置（16/32/队列 200）+ 记忆管线隔离到独立池（此前默认池核心 8 + 无界队列，记忆管线的同步 LLM 调用可把交互路径整个堵死）；SSE 同 ID 重连不再悬空旧 emitter。

## 二、工具覆盖缺口（"整理文件夹/重命名/移动/做表格"）

行业结论（Claude Code Read/Write/Edit/Glob/Grep + OpenHands str_replace_editor+bash 殊途同归）：**十个以内的通用文件原语 + 兜底，胜过大量专用工具**。

本轮补齐：
- `create_folder` / `rename_project_file` / `move_project_file` —— DB 感知（直通 ProjectFileService，与文件树右键菜单同一条代码路径：同名校验/环检测/物理文件搬迁全部继承）。
- 原 `move_file` 已停用：它只做物理 Files.move、不更新 project_file 表，移动已注册文件后文件树脱节（净负资产）。

仍存在的缺口（后续排期）：
- **Word 表格只有整表原语**（doc_insert_table/doc_format_table），改一格只能重建整张表；需要单元格级原语（读表/增删行列/改单元格/合并）。
- 目录读取口径分散：list_files（物理）/doc_list_project_files（DB）/search_project_files（无 ID）/pdf_list_files 四五套，模型容易选错，宜合并或在描述里写清分工。

## 三、与开源 harness 的剩余差距（按投入/收益排序的路线图）

1. **run 事件溯源持久化 + 崩溃恢复**（中投入）——OpenHands 的核心设计：一切皆事件、append-only 日志、崩溃后按序重放即恢复。我们的 AgentRunStateService 是纯内存态，进程重启后半截 ASSISTANT 消息无"已中断"标记也无"继续"按钮。落点：SSE 事件同步 append 到 H2 + 重启后按日志重建。硬约束：续跑会重放最后一步，有副作用的工具需幂等标记。
2. **provider 故障转移链**（低投入）——hermes-agent runtime_provider 模式：主通道连续失败自动切备选 (provider, model)；限流单列状态（OpenHands 把 RATE_LIMITED 与 ERROR 分开）。
3. **上下文超限触发 compaction**（中投入）——长任务后半段 400/质量塌方的隐性根因。ContextCompressor 已存在，需要接上"超阈值自动摘要中段轮次"的触发器（对标 hermes context_compressor / Claude SDK auto-compaction）。
4. **StuckDetector 先干预后熔断**——重复 action-observation 模式第一次注入提示打破循环，第二次才熔断；现有单槽 lastCallSignature 对 A/B 交替重复无感。
5. **工具分组暴露**——hermes 按 28 个 toolset 场景裁剪；我们 80+ 工具全量暴露，选错率与上下文占用都受影响（SkillRouter 白名单是雏形，可推广到场景预设）。
6. **长尾诉求进回放评测**——把"整理文件夹/批量重命名/做表格"失败案例固化进 ai-eval/cases，改工具描述跑评测（Anthropic 实证：微调描述即可显著提升）。
7. **XML 兜底协议对齐 Hermes function-calling 格式**（`<tool_call>` JSON 体）——提高开源模型兼容性。
8. **读 finishReason**——全链路目前不读 LENGTH/STOP，截断检测靠标签启发式；langchain4j 升级后应改为读结构化 finishReason。

## 四、桌面端端口整合（同批落地）

- 现状盘点：桌面端共 4 个受管服务（Java 后端、pptx、mineru、kokoro）+ 编辑器静态服务器。**前端只调后端一个端口**；三个 Python 服务打包态本就是动态回环端口、由后端内部转发，用户不可见——无需也不值得并端口（并入要新写 Spring 转发层 + SsrfGuard 豁免，零用户可见收益）。编辑器静态服务器因 COOP/COEP 跨源隔离（SharedArrayBuffer）必须独立源，不可合并。
- 落地：打包态后端端口链 **5269 → 5369 → 5169 → 随机**（5269 是 IANA 注册的 XMPP 服务器互联口，桌面软件几乎不占；5369/5169 未注册；三个端口本机实测空闲）。真实 bind 探测 + 复用前身份验证（探 `/api/admin/wizard` 特征响应，防"粘"到陌生进程——旧逻辑端口有人听就直接当自己人）。实际端口经 additionalArguments → preload → `window.checkbaDesktop.apiBaseUrl` 注入渲染层，api.js 最优先读它。
- dev 态维持 9696：restart-backend.sh / e2e / CI 工作流零改动；`CHECKBA_BACKEND_PORT` 环境变量仍可显式覆盖。

## 五、对标 DeepSeek Harness（dsh，2026-08-14）

2026-08-13 DeepSeek 开源 agent harness `dsh`（MIT，Cordis 微内核，「一切皆插件」，TS monorepo）。
全量研读了其 docs（architecture / defensive-patterns / persistence-catalog / postmortem 全 4 篇）与
核心包（core/session/compaction/spill/guard/llm/subagent/preset）。结论分三档。

### 5.1 我们已有、dsh 反而没有的（不自卑清单）

dsh **没有**：provider/model 故障转移链、熔断、整通请求超时、全局步数预算（他们只靠「模型不再要工具」终止）。
这些在 dsh 架构里被刻意留作 `agent/request` / `agent/request-error` 两个扩展点让部署自己写。
我们的故障转移链（含 REGION_BLOCKED 收窄）、MAX_LOOP_DEPTH=30、StuckDetector 先干预后熔断都保留不动。

### 5.2 本轮已落地（2026-08-14，抄 dsh 的机制细节）

1. **finishReason 结构化消费**（原路线图第 8 项收口）：
   - **LENGTH + 工具调用 → 一律不执行**（dsh：max-tokens 时丢弃 tool-call 块）。参数被砍半后
     「恰好仍可解析」比解析失败更危险——半篇正文的 write_file 直接覆盖用户文件。截断轮的
     AiMessage 不入栈（不执行又入栈 = tool_calls 无配对结果，OpenAI 兼容通道 400），
     复用 malformedToolRounds 纠正回路（≤2 轮），预算耗尽转「暂停 + 继续」（reason=max_tokens）。
   - **LENGTH + 纯文本 → 「暂停 + 继续」收尾**，不再装作正常完成（半句话戛然而止 + FINISHED）。
   - **空响应当瞬时错误重试**（dsh 的 EMPTY_RESPONSE 教训：正常终止 + 零内容会静默结束整轮，
     用户面前一片空白且无重试入口）。判定三条件：无工具、正文空白、零 token 流出；空 AiMessage 不入栈。
   - finishReason 为 null 的通道（Ollama、回放评测）行为与改造前完全一致。
2. **配额耗尽单列 QUOTA_EXHAUSTED**（dsh：QUOTA 判定先于 429）：402、或 4xx + 配额语义
   （insufficient credits/quota/balance、quota exceeded、余额不足…）。终局——不退避、不换模型
   （同一账户换哪个模型都没钱），SSE 载荷带 `AI_QUOTA_EXHAUSTED` 标记，前端换中文引导。
   此前它被归进 RATE_LIMITED/FATAL：用户盯着「限流等待中」白等两轮 + 换模型白探一次。
3. **上下文溢出的被动恢复通道**（dsh context-overflow 通道）：主动 compaction 靠 chars/token=2
   估算，中文语料系统性低估，估漏时服务商 400 兜底证实。新分类 CONTEXT_OVERFLOW（400 + 上下文语义）
   → 强制压缩（跳过阈值）→ **确实缩小了才同 depth 重放一次**（重试凭证 = compact 返回新实例，
   对标 dsh「generation 前进才允许 retry」）→ 压不动/再撞就终态（`AI_CONTEXT_OVERFLOW` 标记 + 中文引导）。
   预算 1 次/轮、成功轮清零（长任务「涨→压→涨→压」是合法路径）。
4. **工具结果无模型剪枝**（dsh compaction-tool-result-pruner，压缩第一道防线）：折叠摘要前，
   先把中段超过 8192 字符的工具结果剪成首 4096 + 尾 1024 + 省略标记；剪完重估、够了就完全不折叠
   （保留的原文越多幻觉越少）。只改正文不动 id/toolName，工具配对不受影响；keepRecent 尾部刻意不剪。
   同时补了 dsh 的「必须变小」硬规则：折叠后估算不降反升就放弃折叠（小中段的摘要头开销会得不偿失）。

### 5.3 排期项（吸收 dsh 设计后的路线图更新）

1. **run 事件溯源持久化**（原第 1 项，设计已由 dsh 验证并具体化，实施时照抄这些细节）：
   - 数据模型抄 dsh SQLite 后端：`events(session_id, seq, type, time, data JSON, PRIMARY KEY(session_id, seq))`，
     seq 严格连续；**surface / log-only 事件二分**（只有 user/assistant/tool-result 三类进模型历史，
     其余 40 种全是审计旁路）——审计完整性与上下文体积彻底解耦。
   - **两层写入**：有界批 write-behind（200ms 合并窗口不重置、失败保留不重试、flush barrier）+
     语义检查点 fail-closed（模型请求前 / 工具正文前 / pre-step 必须先落盘）。
   - **崩溃修复算法**（dsh repair.ts 可整段移植）：保留完整尾部轮次，为未配对工具调用合成
     TOOL_OUTCOME_UNKNOWN（「只读/幂等才重试；有副作用先验证外部状态」）/ TOOL_NOT_STARTED
     （「仍需要就重试」）两种结果——**副作用不自动重放，把重试决策连同语义指引交给模型**。
   - 撕裂尾部判定：最后一个可解析 turn/end 为已提交区边界，边界前有洞 = 损坏拒绝，边界后 = 容忍截断。
2. **工具分组暴露**（原第 5 项）：dsh 的四层机制里，`ToolRestriction`（allow/deny 交集 + 自身注册豁免）
   与 Skill 渐进式披露（目录只有名字 + 500 字描述，命中才加载正文）最可搬。SkillRouter 白名单是雏形。
3. **spill 大输出溢出**：工具结果超 50KB 落文件，模型拿到 head/tail 预览 + 「用 read_file 分段读」提示。
   我们已有 read_file，缺的是落盘 + 替换策略。注意 dsh 的坑：read 类工具要豁免（防 read→spill→read 死循环）。
4. **Retry-After 采纳**：dsh 规则「provider 值 ≤ maxDelay 就采纳，超了直接放弃重试」。
   langchain4j 0.36 的 OpenAiHttpException 拿不到响应 header，**升级 langchain4j 后再做**。
5. **XML 兜底协议对齐**（原第 7 项，未动）；**长尾诉求进回放评测**（原第 6 项，持续）。

### 5.4 明确不抄的（去粗）

- **Code Mode / PTC**（工具以 TS SDK 呈现、模型写程序一次编排多工具）：需要受限脚本运行时，无对等物；
  其「宣告表面 = 可调用表面」闭环思想已体现在插件启停过滤里。
- **创造模式**（模型改写自己运行其上的运行时）：dsh 自己都注明「把这类会话当 shell 访问对待」，法律行业不宜。
- **workflow 引擎**：其 README 自列已知缺陷（无 journaling、无 resume、无 token 预算），不成熟。
- **事故复盘制度**（dsh postmortem 三门槛 + 「护栏必须能因原 bug 变红」）——制度本身不进代码库，
  但其精神已在实践（.claude/agents/ 领域文档的「已知地雷」节 + 回放评测钉根因）。
