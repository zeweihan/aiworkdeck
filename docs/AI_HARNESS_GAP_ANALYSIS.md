# AI Harness 差距分析与加固记录（2026-08）

对标对象：Nous Research hermes-agent、OpenHands（原 OpenDevin）、Claude Code / Agent SDK、LangGraph（durable execution）、smolagents。
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
