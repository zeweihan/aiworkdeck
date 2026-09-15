# AI、流连接与转写：超时和成本审计

日期：2026-09-15；开发看板：#650。代码基线：`origin/master da9330b2` 加本轮改动；旧工作树的结论已按最新主线重新核对。

## 结论

系统已有分层超时、有限重试、故障模型切换、上下文压缩、断线恢复和录音后台任务；不能把“有超时”解释为“整项工作在这个时间内完成”。最新主线允许主 Agent 持续工作，不再有旧版的 30 步硬上限。多轮调用、工具执行、等待队列和上游服务会叠加耗时。

本轮实际修复：

1. 模型请求同步抛异常现在走与异步回调相同的终态闸及故障切换，关闭原看门狗；迟到回调不能再污染完成的轮次。准备工具和本地压缩不计入首字等待。
2. SSE 初次建连 15 秒内没收到响应头即退出等待；发送中建连失败也会拒绝等待中的 Promise。响应头到达即清定时器，不用 15 秒截断正常长流。
3. 点停止立即结束本地等待，取消请求独立等待最多 10 秒。仅收到成功响应后显示“已发送停止指令”；失败明确告知后台停止尚未确认。
4. 已完成录音任务的结果下载超时保留原任务，下次只获取结果。合法空转写结果重复提交也不再新建任务；旧快照不覆盖数据库已完成结果。
5. 云录音原先可能 30 分钟就被本地判死；现至少覆盖官方三小时处理窗口，并先查一次上游。超过等待阈值只暂停自动查询，保留任务号；用户重试查询原任务，不再次提交收费转写。

## 限制与 fallback 矩阵

以下为仓库配置/代码，部署环境可覆盖的项不声称就是线上有效值。

| 层级 | 当前限制/行为 | 失败与用户反馈 | 证据 |
|---|---|---|---|
| SSE 建连 | 响应头 15 秒（本轮新增） | 失败退出本地等待；不自动重发 chat | `frontend/src/composables/useAgentStream.js` connectSSE |
| SSE 活连接 | 后端 15 秒心跳；前端 45 秒无活动，10 秒巡检 | 1/2/4…秒退避，最大 30 秒；界面显示重连 | `SseEmitterService`、`useAgentStream` |
| SSE 生命周期/回放 | 30 分钟连接；断开不等于后台轮次结束；回放最多 512 事件/128 KiB | 重连恢复状态与缓冲，历史仍是长期依据 | `SseEmitterService` |
| 对话停止 | 本地立即收尾；确认取消最多 10 秒 | 成功只称已发送；失败明确未确认，不承诺在途模型停止计费 | `useAgentStream.abort` |
| 模型流 | 首字 60 秒；内容/思考开始后静默 180 秒；5 秒巡检；上游保活会续期 | 终态幂等；仅没有正文 token 的失败可重放；已输出的保留部分结果 | `AgentStreamHandler`、`AgentOrchestrator` |
| OpenRouter/平台模型 | 配置 600 秒；新 `OpenRouterStreamingChatModel` 的 connect/read/write/call 均用此值 | 异步错误与同步抛错统一分类；保活不能突破 call 总时限 | `application.yml:311`、`OpenRouterStreamingChatModel` |
| 同步深入审校 | 本轮文档专项增加辅助调用剩余墙钟预算（105 秒总预算），不复用缓存模型、禁 SDK 自动重试 | 返回已完成片段并标记未完成；HTTP等待停止不保证上游停止计费 | `DocInsightService.extractDeep`、`ChatModelFactory.getAuxChatModel(Duration)` |
| 本机 Ollama | 配置 300 秒 | 模型超时分类处理；实际速度取决于模型和硬件 | `application.yml:302`、`ChatModelFactory` |
| 瞬时错误 | 最多重试 3 次，退避 8/16/32 秒 | 提示重试次数；预算耗尽才考虑备用模型 | `LlmErrorClassifier`、`AgentOrchestrator` |
| 限流/余额/鉴权 | 限流重试 2 次，30/60 秒；余额不足和鉴权错误不换模型重试 | 分别提示限流、额度或配置问题 | 同上 |
| 模型故障切换 | 仅无正文时；不重复尝试已失败模型；保留计费通道，按地域与视觉能力过滤 | 明示更换的模型；不偷偷切换平台/BYOK | `nextFailoverModel`、`switchToFailoverModel` |
| 上下文溢出 | 确认压缩能缩小后，仅重试一次 | 明示自动压缩；不能缩小则结束并引导缩小范围 | `forceCompactAfterOverflow` |
| 主 Agent 总任务 | 最新主线取消固定 30 步硬上限；运行轮次隔离、重复/失败守卫仍在 | 不能给出固定全任务完成时间或费用上限 | `AgentOrchestrator`、`StuckDetector` |
| 子 Agent | 3 并行，单任务 630 秒，60,000 估算 token；默认辅助模型 | 有界返回超时/预算错误；中断不能保证打断在途 HTTP | `application.yml:379`、`SubAgentService` |
| 流恢复/工具日志 | 恢复正文尾部 256 KiB、执行日志 512 KiB | 截断标记；与模型上下文预算不同 | `AgentOrchestrator` |
| 录音准备 | 转码 20 分钟；平台文件直传 30 分钟 | 异步任务失败、原录音保留；可重试 | `MeetingTranscriptionService` |
| 平台转写 HTTP | 凭证 15 秒、提交 30 秒、查询 15 秒；网络失败可同键再试一次 | 单个请求两次尝试可能叠加；提交持幂等键 | `MeetingTranscriptionService`、`PlatformGatewayClient` |
| 云转写任务 | 每次读详情至少间隔 10 秒查上游；等待阈值 max(3小时,音频3倍) | 先取结果，再暂停超时自动查询；重试只查原任务 | `refreshIfNeeded`、`failIfStuck` |
| 转写结果下载 | 连接 10 秒、请求 60 秒 | 正文下载失败保留任务继续轮询；附加摘要失败可省略 | `defaultUrlFetcher`、`completeMeeting` |
| 本机转写 | 就绪探测 2 秒；推理请求 4 小时；流式上传 | 本机失败绝不自动上传云端 | `LocalAsrClient` |
| BYOK 听悟/OSS | 客户端未显式配置 SDK connect/read timeout，依赖 SDK 默认 | 属于待补实测的边界，不能写成已验证与平台相同 | `TingwuClientImpl`、`MeetingOssClientImpl` |
| 通用 API/chat POST | 通用 uni.request 多数依赖框架默认；chat fetch 仍未单独设置响应上限 | 本轮未统一提高所有请求超时；避免未经幂等核对重发写请求 | `frontend/src/services/api.js`、`useAgentStream.sendMessage` |

## 长文上下文与最省 token 的执行方式

仓库当前默认总上下文预算 100,000 估算 token；系统/记忆/回复分别预留 8,000/5,000/8,000。运行中达到可用历史预算的 80% 后剪枝或折叠，保留近期 8 条及完整工具调用配对。运行中摘要是本地确定性处理，不额外调用模型；首次组装旧历史的压缩链则可能调用辅助摘要模型，二者不可混为一谈。

文件入口默认：单文件 10 MiB、单次最多 10 个文件、普通单文件最多 50,000 字符、目录单文件 20,000 字符。`AiContextProperties` 另含旧 chat 字段，不能只看字段存在就推定活跃入口实际使用。2–3 万字纯文本可以落入单文件 50,000 字符限制，但几百页包含图片/表格时可能先超过字节限制；上下文截断也不等于编辑器只处理这些字符。编辑器识别、格式修改与大文档回归见同批文档专项报告。

当前字符/token 比例是 2.0，属于估算，并非中文 tokenizer 的精确计数；图像另按每张 1,200 token 估算。模型特定预算配置为空时用默认预算。供应商仍可能返回窗口超限。

推荐执行顺序（复用现有能力，不新增模型调用）：

1. 先读取目录、段落/表格数量和任务范围；明确“全文”还是“选区”。记录已检查范围，遇到截断不声称全量核对完成。
2. 统一字体、段距、表格边框等确定性工作用一次批处理规则；无需把整篇原文反复送模型重写。先快照，再执行，检查首/中/尾与表格样本，报告覆盖量。
3. 识别/补全/问答优先定位相关章节，按结构分段。分段保留上下文锚点及跨段定义；只有需要综合判断时才向主模型合并简短结论和原文定位。
4. 子任务只接收任务所需章节、输出短结果+位置+未验证项；默认辅助模型负责独立提取，主模型复核冲突。不要每个子任务重新读取全项目、重述全文。
5. 复用已抽取文本、已有任务号和稳定提示前缀。最新 `OpenRouterStreamingChatModel` 已为 Anthropic/Qwen 标记稳定系统提示缓存，并记录缓存使用量；命中及折扣以真实 usage/账单为准，不预估保证比例。
6. 只在明确允许的错误类型上有限重试。部分正文已经生成时保留结果并让用户接续；转写只是结果读取失败时查询原任务，避免重跑昂贵计算。

示例：30,000 字符按当前估算约 15,000 token。把同一全文传入 10 次，仅重复输入就约 150,000 估算 token，尚不含回复/历史/工具规格。一次定位后只发相关章节通常更省；此例是估算，不是账单或质量保证。

## 上游官方核实（2026-09-15）

- 听悟离线 API：单文件不超过 6 GB、时长不超过 6 小时；通常三小时内返回，半小时批量上传超过 500 小时时长等情况除外；签名 URL 至少三小时。创建/查询用户 QPS 分别为 20/100，文档建议可按一分钟或五分钟轮询。本项目四小时签名有效期符合最低要求，但本地等待超时不代表服务商已终止。[阿里云官方离线转写文档](https://help.aliyun.com/zh/tingwu/offline-transcribe-of-audio-and-video-files)
- OpenRouter 官方要求耗时推理发送 SSE 注释保活，否则可能触发 fetch timeout 并换供应商；该页没有承诺通用的固定超时秒数，因此不能把本系统 60/180/600 秒当成上游 SLA。[供应商性能要求](https://openrouter.ai/docs/guides/community/for-providers)
- OpenRouter 取消是否停止生成与计费取决于实际供应商；非流式和不支持取消的供应商可能仍处理并计费。HTTP 200 也可能包含中途 SSE error，不能只看状态码判成功。[流式错误与取消](https://openrouter.ai/docs/api_reference/streaming)

## 验证与剩余边界

前端实测 13/13：`agent-stream-abort.test.mjs`、`agent-stream-connect.test.mjs`、`agent-stream-unmount.test.mjs`。新增测试执行真实函数体，覆盖挂起取消、403拒绝、成功确认、初次建连503、未回响应头和长流不误截断。

后端本分工最终实测 41/41（JDK 21）：`AgentOrchestratorFailoverFlowTest` 7、`MeetingTranscriptionServiceTest` 17、`MeetingTranscriptionTimeoutTest` 17。

后端统一验证清单：`AgentOrchestratorFailoverFlowTest`、`AgentStreamWatchdogTest`、`MeetingTranscriptionServiceTest`、`MeetingTranscriptionLocalPathTest`、`MeetingTranscriptionPlatformPathTest`、`MeetingTranscriptionTimeoutTest`；全套最终结果由本次统一测试记录补充。关键回归覆盖同步异常切备用、迟到回调无效、结果超时仅重取、空结果幂等、旧快照不回写、三小时窗口、已完成结果优先及超时重试不再提交。

未声称验证：真实付费模型长文准确率、真实供应商排队和扣费/退款、部署代理超时覆盖值、SDK默认超时、多实例部署下数据库级幂等、停止后在途模型实际取消。网关提交的同键重试目前只覆盖一次方法调用；进程在“上游已受理但taskId未落库”窗口崩溃仍可能失去恢复凭据，需要网关持久化幂等/对账联测。主任务与子任务没有本轮新增硬费用上限；不能据此承诺固定最高成本。
