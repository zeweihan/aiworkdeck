## 一、期望的对话流程（目标行为）

你要的其实是一个明确的**状态机**：

1. **简单任务**：直接回答（不计划、不 ToDo、不工具，或至多一次轻量工具但对用户无感）。
2. **复杂任务**：

   * 先产出 `implementation_plan` → **等用户批准**（批准前不执行）。
   * 用户批准后：先产出 `task_list`（不需要用户批准）→ 然后执行（可多次工具调用）。
   * 执行完成后：给最终结果；必要时附 `walkthrough`（只是过程回顾，不是结果本身）。

这个流程要求系统在每一步都知道：**当前处在 PLAN 还是 EXECUTE 还是 DONE**。

---

## 二、当前 prompt 中最致命的逻辑冲突（会直接导致你描述的问题）

### 冲突 1：`artifact` 一出现就“必须停止，不执行” —— 直接杀死你的执行阶段

你在 enforcement 里写了：

> “Planning: If you output `<artifact>`, you MUST STOP immediately after `<walkthrough>`. Do NOT execute.”

但你又要求复杂任务在执行前要有 `task_list`（它也是 `<artifact>`）。
这意味着：**模型一旦生成 task_list，就会按最高优先级规则“停止并不执行”**。
你看到的“工具调用完了也不会继续”/“总把 walkthrough 当 final”，在很多场景本质上是这个规则把模型“钉死”了。

**修复方向**：把“artifact 即停止”改成“仅 implementation_plan 停止”。task_list 不能触发停止。

---

### 冲突 2：同一份系统提示同时要求“RAW XML”又要求“主答案必须是未标记纯文本”

sysprompt 开头写：

* “You must structure your response using these XML tags.”
* “CRITICAL: Output RAW XML.”

但 enforcement 又写：

* “Main Answer: UNTAGGED plain text.”

并且 chitchat 部分又写“Just text, NO XML tags”。

这会导致模型在“到底要不要用 XML”上摇摆，尤其在上下文很长、或者工具循环多轮后，模型会倾向选择一个“最稳的结束容器”，通常就是 `<walkthrough>`（因为它被描述为“叙述性总结”，且你又强调“STOP after walkthrough”）。

**修复方向**：必须做一个一致的输出契约——要么所有输出都包在一个固定根节点里（推荐），要么明确：只有复杂任务才输出 XML，简单任务只输出纯文本，且不要再写“RAW XML 适用于所有响应”。

---

### 冲突 3：你把“工具调用必须独立一轮、不要同轮输出答案”写进了模型规则，但你的系统未必实现了自动多轮循环

你在 CORE PROTOCOL 写：

* “Do NOT output Final Answer in the same turn as `<tool_code>`… output `<tool_code>` then STOP… Answer in NEXT turn.”

如果你的服务端没有做“自动 next turn”（即：模型→工具→再喂回工具结果→再调用模型），那用户端就会看到：**模型输出了工具指令后就停住了**。
你描述的“有时候工具调用完了也不会继续”，非常像是：**后端执行了工具，但没有再触发下一次 LLM 生成**，或者把工具结果塞回去的 message role/格式不对，导致模型没进入“观察→继续”的链条。

**修复方向**：工具调用必须由后端驱动一个 while-loop（或 LangChain4j 的工具自动迭代），直到模型显式返回“完成”。

---

## 三、ContextAssemblerService 这段代码会放大上述问题（尤其是长上下文时）

### 1）你把“系统提示 + enforcement + 状态 + 文件全文”塞进同一个 SystemMessage

这会导致两个实际后果：

* **指令稀释/截断风险**：文件全文一长，模型注意力被吞掉，越到后面越倾向用“最像结束语”的 `<walkthrough>` 来收束。
* **XML 污染风险**：你把文件内容原样塞入 `<file>` 标签内，但文件内容里只要出现类似 `</file>`、`<process>`、`<artifact>`、甚至大量尖括号，都会干扰你后续的 XML 解析和模型对协议的理解。

**修复方向**：文件内容建议用 `<![CDATA[ ... ]]>` 包裹，或做转义；同时把“系统规则”与“文件上下文”分成不同 message（至少两个 SystemMessage），并控制文件注入的长度（摘要/检索而不是全文）。

---

### 2）历史消息只区分 USER/ASSISTANT，没有 Tool role

你现在的 history 回放：

* USER → UserMessage
* ASSISTANT → AiMessage

如果你是靠“模型输出 `<tool_code>`，服务端解析并执行”这种自定义工具协议，那么工具结果回注入时很容易被你也存成 ASSISTANT 或 USER，导致模型无法稳定识别“这是 Observation”。

**修复方向**：

* 若使用 LangChain4j 原生 tools：必须用 `ToolExecutionResultMessage` 这种 tool message。
* 若继续自定义 `<tool_code>`：至少把工具结果用一个固定的 **system/tool 结果消息格式**回注入，并且不要把工具结果当成普通 assistant 内容混入（否则下一轮模型会把它当成自己说过的话）。

---

## 四、为什么模型会把 walkthrough 当 final output（机制解释）

结合你的规则，模型会形成一个“最安全的完成策略”：

* 你强调“artifact 出现必须 STOP after walkthrough”；
* 你又禁止把内容放在 artifact（artifact 只能是 plan/task_list）；
* 你又要求主答案是 untagged plain text，但同时又要求 RAW XML；
* walkthrough 被定义为“叙述性总结”，天然适合塞“最终结论”，而且不会违反 artifact 限制；
* 再加上长上下文/多轮工具后，模型更倾向在一个明确的“结束标签”里收口。

所以它把 walkthrough 当作“最终输出容器”是一个**被你规则诱导出来的稳定解**。

---

## 五、最小改动的可落地修复方案（推荐按优先级做）

### A. 先修 prompt 的“停止条件”（你现在的最大阻断点）

把 enforcement 这一条：

> If you output `<artifact>`, you MUST STOP… Do NOT execute.

改成：

* **仅当 `<artifact type="implementation_plan">` 出现时停止**；
* `<artifact type="task_list">` 不触发停止；
* `<walkthrough>` 永远不得包含结论/最终答案，只能 3–5 句过程回顾。

这一步做完，你的“task_list → 执行”链条才可能成立。

---

### B. 引入显式 phase（强烈建议由后端注入）

你现在注入了 projectId/taskListId/planId，但没有注入“当前阶段”。建议在 system injection 增加：

* `- Current Phase: PLAN | AWAIT_APPROVAL | EXECUTE | FINAL`

并在 sysprompt 写死规则：

* Phase=PLAN：只允许输出 implementation_plan + 一句请求批准；禁止工具调用；禁止 walkthrough。
* Phase=EXECUTE：先输出 task_list（可选），允许工具调用；最终必须输出 untagged main answer；walkthrough 可选但限长。
* Phase=FINAL：只允许输出最终答案（必要时 walkthrough）。

这能把“复杂任务先计划再执行”从“模型自己猜”变成“系统明确告知”。

---

### C. 工具调用必须由后端实现自动循环（否则你永远会遇到“工具后不继续”）

你需要一个明确的执行循环（无论你用 LangChain4j 原生 tools 还是自定义 `<tool_code>`）：

* LLM 产出工具请求
* 后端执行工具
* 把工具结果作为 **tool/observation 消息**喂回
* 再调用 LLM
* 直到 LLM 返回“完成且无工具请求”

如果你继续用 `<tool_code>` 文本协议，建议你在回注入工具结果时用一个固定结构，例如：

* role=SYSTEM（或 TOOL）
* content 以 `TOOL_RESULT` 开头，并带上原 tool_name、输入、输出摘要

同时，在 sysprompt 加一句非常硬的规则：

* “当收到 TOOL_RESULT 消息时，必须继续执行下一步，不得向用户提问‘是否继续’。”

---

### D. 统一输出契约：建议把“最终答案”也放进固定标签里（否则 walkthrough/纯文本永远争夺“谁是最终输出”）

这是结构性问题：你一边要求 RAW XML，一边要求 untagged plain text。只要你继续这样混用，模型迟早会漂移。

最稳的方案是：增加一个 `<final>` 标签承载最终答案（你的 UI 解析也会简单很多），例如：

* `<final>…最终答案…</final>`
* `<walkthrough>…过程回顾…</walkthrough>`

如果你坚持“最终答案必须 untagged”，那也至少需要一个不会与 XML 冲突的强分隔符，例如：

* `===FINAL===` 开始
* `===END===` 结束

否则你只能靠猜测“最后一段纯文本”来判定 final，非常不稳。

---

### E. 文件注入要做 CDATA/转义 + 控长

把：

```java
systemText.append("<file id=\"").append(fileId).append("\">\n");
systemText.append(content);
systemText.append("\n</file>\n");
```

改成至少：

* 用 `<![CDATA[` `]]>` 包住 content（避免 XML 污染）
* 对超长文件截断或摘要（否则系统规则在长对话里会被“注意力淹没”）

---

## 六、你可以用一个“二阶段 LLM 调用”把简单/复杂任务分流（效果会明显稳定）

这一步能显著降低主模型在“要不要计划”上的摇摆：

1. **Classifier call（小调用）**：只让模型输出 JSON：`{"complex": true/false, "needs_tools": ..., "reason": ...}`（不展示给用户）。
2. **Main call**：根据 classifier 结果决定 phase=CHAT 或 phase=PLAN，并选择不同 system prompt 模板。

这样你就不再依赖主 prompt 里那堆“Intent Classification & Actions”的自我约束（那部分目前冲突很多，是不稳定来源）。

---

