# AI 编排器回归评测（AI Eval）

编排器重构频繁，「给定用户输入 → 应当分发哪些工具 / 产出什么结构」过去只能真机手测。
本评测基建把这件事变成 `mvn test` 里的一等公民：**每次重构后，离线回放评测告诉你编排器行为有没有变。**

## 组成

| 部分 | 位置 | 说明 |
| --- | --- | --- |
| 评测用例集 | `backend/src/test/resources/ai-eval/cases/*.json` | 24 个法律场景用例（起草、修订、查法条、PPT、闲聊、记忆、artifact 等） |
| 回放测试 | `backend/src/test/java/com/checkba/service/ai/eval/OrchestratorReplayEvalTest.java` | **进默认 `mvn test` 套件**，不调真实 LLM，全程离线毫秒级 |
| 回放 harness | 同目录 `EvalHarness` / `ScriptedStreamingModel` / `RecordingToolRegistry` / `RealToolBeans` | 见下文原理 |
| 真实 LLM 冒烟 | 同目录 `RealLlmSmokeTest.java` | 默认跳过；设 `OPENROUTER_API_KEY` 后跑 3 个标注 `smoke` 的关键用例 |
| 冒烟系统提示词 | `backend/src/test/resources/ai-eval/smoke-system-prompt.txt` | 冒烟专用精简 prompt |

## 原理（谁是真的、谁是假的）

回放测试用**真实生产代码**跑主链路，只把「外界」换成桩：

- **真实**：`AgentOrchestrator`（完整 runLoop：XML 与原生两种工具协议、artifact、`<title>`、Ask 模式）、
  `XmlToolCallParser`（全部容错解析）、`ToolRegistry` 的注册与别名解析、**真实工具类的工具名/参数名**
  （`RealToolBeans` 以空依赖反射实例化 8 个生产工具组件——重构改了工具名或参数名，评测立刻红）。
- **回放**：`ScriptedStreamingModel` 实现 `StreamingChatLanguageModel`，按用例 `turns` 逐轮回放预录的模型输出，
  不访问网络。
- **记录**：`RecordingToolRegistry` 继承 `ToolRegistry`，`execute()` 只记录 `(toolName, argsJson)` 分发序列并返回
  桩输出，**从不执行工具方法**（所以空依赖实例化是安全的）。
- **打桩**：SSE、消息持久化、上下文组装、记忆管线等外围服务用 Mockito 打桩并记录调用。

断言的是编排器的**可观测行为**：分发给 ToolRegistry 的工具序列与参数、最终保存消息中的结构标签
（`<process>`/`<final>`/`<artifact>`）、artifact 落盘、`bubble_end` 收尾状态、ASK 模式是否携带工具规格、
`<title>` 触发的会话重命名。

## 怎么跑

```bash
cd backend
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # 本机默认 JDK 25 会 SIGBUS 崩溃，务必用 21

# 只跑回放评测（毫秒级）
mvn test -Dtest=OrchestratorReplayEvalTest -Dsurefire.failIfNoSpecifiedTests=false

# 全量测试（回放评测已包含在内）
mvn test

# 真实 LLM 冒烟（可选，产生真实 API 费用）
OPENROUTER_API_KEY=sk-or-... mvn test -Dtest=RealLlmSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
# 可选环境变量：OPENROUTER_BASE_URL（默认 openrouter.ai/api/v1）、AI_EVAL_SMOKE_MODEL（默认 google/gemini-2.5-flash）
```

## 怎么添加用例

在 `backend/src/test/resources/ai-eval/cases/` 里挑一个分类文件（或新建 `cases-xxx.json`），往数组里加一条。
加载器会自动发现所有 `*.json`，并校验 `id` 全局唯一。

```jsonc
{
  "id": "my-new-case-xml",              // 唯一 ID（测试名）
  "title": "一句话描述",
  "category": "drafting",               // 分类，只影响测试展示名
  "protocol": "xml",                    // xml / native / mixed，仅文档标注
  "mode": "AGENT",                      // AGENT（默认）/ PLAN / ASK
  "userInput": "用户会说的话",
  "smoke": { "anyOfFirstTools": ["write_docx"] },  // 可选：加入真实 LLM 冒烟集；[] 表示期望不调工具
  "turns": [                            // 预录的模型输出，一项 = 一轮 LLM 回复
    { "text": "<process name=\"...\"><tool_code>write_docx(fileName=\"x\", markdownContent=\"y\")</tool_code></process>" },
    { "toolCalls": [ { "name": "write_docx", "arguments": { "fileName": "x" } } ] },  // 原生 function calling 轮
    { "text": "<final>收尾话术</final>" }   // 最后一轮必须不含工具调用，否则循环不会结束
  ],
  "toolStubs": { "write_docx": "{\"status\":\"success\"}" },  // 回放给模型的工具输出，缺省 "OK (eval stub)"
  "expect": {
    "toolCalls": [                       // 按顺序断言分发序列；null=不断言；[]=断言零调用
      { "name": "write_docx",            // 别名解析后的工具名（search_laws 写 search_web；wps_* 旧名写 doc_*）
        "argsContain": { "fileName": "x" } }   // 参数值子串断言
    ],
    "structureContains": ["<process", "<final>"],  // 最终保存的 ASSISTANT 消息应含的标签
    "artifact": { "type": "task_list", "filenameContains": "清单" },  // 可选
    "bubbleEndStatus": "finished",       // finished / awaiting_approval（PLAN 模式实施计划）
    "toolsOffered": true,                // 可选：ASK 模式写 false
    "renamedTitleContains": "..."        // 可选：<title> 协议断言
  }
}
```

预录 `turns` 的取材方式：真机跑一次目标场景，从后端日志里抄 `Response completed ... Full content:` 的原文
（XML 协议），或 `Detected Native Tool Requests` 的工具名与参数（原生协议）。

经验规则：

- **每个 XML/native 工具轮之后必须还有下一轮**（编排器执行工具后会再问一次模型）；最后一轮必须是纯文本。
- `turns` 数与编排器实际请求的 LLM 轮次必须精确相等——多了少了都会失败（这本身就是回归信号）。
- artifact 轮（`task_list`）与 `<title>` 轮不触发递归，可以直接作为最后一轮；
  PLAN 模式的 `implementation_plan` 轮会停循环等审批（`bubbleEndStatus: "awaiting_approval"`）。

## 怎么解读失败

| 失败信息 | 含义 | 常见原因 |
| --- | --- | --- |
| `工具调用数量不符` / `第 N 个工具调用名不符` | 分发序列变了 | 编排器分发逻辑被改动；工具改名；`XmlToolCallParser` 解析行为变化；别名表变动 |
| `参数 X 应包含 [...]` | 参数提取/绑定变了 | 解析器参数提取回归；工具方法参数改名（解析器按参数名提取，改名会直接丢参数） |
| `回放脚本已耗尽`（IllegalStateException） | 编排器比预期多问了一轮 LLM | 循环控制变化（如工具执行后新增了一轮确认）；工具结果反馈格式变化导致再次触发工具 |
| `编排器请求的 LLM 轮次少于用例预录轮次` | 编排器提前结束了循环 | 提前 return / 新增终止条件；XML 工具调用未被识别（containsToolCall 判断变化） |
| `最终 ASSISTANT 消息缺少结构标签 [...]` | 持久化内容结构变了 | executionLog 拼装格式变化；`<final>`/`<process>` 清洗逻辑变化 |
| `期望保存 artifact 但未被调用` / 文件名断言失败 | artifact 协议处理变了 | artifact 检测正则、name 提取、文件名清洗逻辑变动 |
| `bubble_end status 应为 [...]` | 会话收尾协议变了 | PLAN 审批停循环逻辑、bubble_end 事件负载变化 |
| `toolsOffered 不符` | ASK 模式约束被破坏 | ASK 模式下把工具规格传给了 LLM（或反之） |
| `不应有 SSE error 事件` | 回放中编排器抛了异常 | 看断言消息里带出的 error 事件内容定位堆栈 |

**失败 ≠ 一定是 bug**：如果这次重构就是有意改变行为（例如调整反馈消息格式、增加一轮循环），
确认新行为正确后，更新对应用例的 `turns`/`expect` 即可——评测的职责是让行为变化**显式化**，而不是禁止变化。

## 冒烟测试（真实 LLM）怎么解读

冒烟只断言「模型面对真实工具规格时的**首个工具选择**落在期望集合内」（闲聊用例断言不调工具），
它验证的是 prompt/工具规格与真实模型的兼容性，不验证多轮循环。失败时优先检查：
模型是否变更（`AI_EVAL_SMOKE_MODEL`）、OpenRouter 是否限流、工具描述是否被改坏。
冒烟失败不阻塞 CI（默认跳过），但连续失败说明真机体验大概率已受影响。

## 与架构不变式的关系

本评测守护 AI 编排器重构（PR#83/#84）确立的边界：编排器不感知具体工具、统一走 `ToolRegistry` 分发、
XML `<tool_code>` 兜底协议与原生 function calling 双协议等价。新增工具**不需要**改编排器，
但建议为新工具补一个评测用例（一个 XML 轮 + 一个 final 轮即可）。
