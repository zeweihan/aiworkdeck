package com.checkba.service.ai.subagent;

import cn.hutool.json.JSONUtil;
import com.checkba.dto.ai.TaskProgressEvent;
import com.checkba.service.ai.AllowedModels;
import com.checkba.service.ai.AuxModelResolver;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PlatformAiUserScope;
import com.checkba.service.ai.SseEmitterService;
import com.checkba.service.ai.TokenUsageService;
import com.checkba.service.ai.ToolRegistry;
import com.checkba.service.ai.XmlToolCallParser;
import com.checkba.service.ai.tools.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子 Agent 服务（Phase 3C 多智能体协作第一阶段）。
 *
 * 一个子 Agent = 独立消息栈（不进主会话历史）+ 受限工具集 + 独立模型
 * + 轮数上限 + token 预算。它是主循环（AgentOrchestrator.runLoop）的极简版：
 * 同样支持原生 function calling 与 XML &lt;tool_code&gt; 双协议，
 * 但**不复用**主循环的 SSE 推流与消息持久化——中间过程对主会话不可见，
 * 只把最终结构化结果（{@link SubAgentResult}）返回给调用方。
 *
 * 约束（见 docs/AI_ARCHITECTURE.md「子 Agent（Phase 3C）」）：
 * - 复用 {@link ToolRegistry} 分发工具、{@link ChatModelFactory} 建模型；
 * - 身份继承（不变式 3）：工具调用的 ToolContext 继承主会话的
 *   projectId/conversationId/userId，LLM 无法伪造；
 * - 防递归：子 Agent 工具集排除 dispatch_subtask，且分发前二次拦截；
 * - 并行度/轮数/预算/超时全部来自 {@link SubAgentProperties}（不变式 5）；
 * - 模型：system_setting {@code ai.subagentModel} → yml {@code ai.subagent.model} → 辅助模型
 *   （{@link AuxModelResolver}）。<b>默认便宜模型而不是继承父会话</b>——长程任务的子任务
 *   跟着主模型跑最烧钱；非白名单一律拒绝派发并给可读提示，不静默回落；
 * - 记账：每轮的 tokenUsage 都落 token_usage（归属主会话的 project/conversation/user）；
 * - 子任务开始/结束向主会话 SSE 发送 subtask_progress 事件（新增事件，向后兼容）。
 */
@Service
@Slf4j
public class SubAgentService {

    /** 委派工具名（防递归拦截用，与 SubAgentTools#dispatch_subtask 方法名一致） */
    public static final String DISPATCH_TOOL_NAME = "dispatch_subtask";

    /** 标记当前线程正在运行子 Agent 循环（防递归的第二道防线） */
    private static final ThreadLocal<Boolean> IN_SUB_AGENT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static boolean inSubAgent() {
        return Boolean.TRUE.equals(IN_SUB_AGENT.get());
    }

    private final ToolRegistry toolRegistry;
    private final ChatModelFactory chatModelFactory;
    private final XmlToolCallParser xmlToolCallParser;
    private final SseEmitterService sseEmitterService;
    private final SubAgentProperties props;
    private final AuxModelResolver auxModelResolver;
    private final TokenUsageService tokenUsageService;
    private final ExecutorService executor;

    /**
     * 正在跑的子任务登记簿：subtaskId → 句柄。只服务于「任务级取消」，
     * {@link #dispatch} 返回前一定移除（finally），不做过期清理。
     */
    private final Map<String, RunningSubtask> running = new ConcurrentHashMap<>();

    /**
     * 一个在跑的子任务。conversationId 一并记下来是为了鉴权：
     * 取消端点只允许停「自己会话里的」子任务，光凭一个 subtaskId 不足以授权。
     */
    private record RunningSubtask(String conversationId, Future<SubAgentResult> future) {}

    /**
     * runLoop 在子线程里边跑边写、dispatch() 在超时/中断/取消/异常分支读的跨线程进度快照
     * （审计条目：「Timeout/interrupted/cancelled failure results always report toolsUsed=[]
     * and rounds=0, discarding real partial progress the child made」）。
     *
     * <p>{@code future.get()} 超时/被中断/被取消时拿不到 runLoop 的返回值——被取消的 Future
     * 连正常返回值都读不到——此前 dispatch() 只能在这些分支里硬编 {@code toolsUsed=List.of()}、
     * {@code rounds=0}，哪怕子 Agent 已经真实执行过几轮、可能有副作用的工具调用。父级因此无法
     * 判断"什么都还没发生"和"已经做了不少工作后死掉"，这对是否安全重试同一个子任务
     * （幂等性）与如何跟用户解释失败都有影响。
     *
     * <p>{@link #toolsUsedList()} 与 runLoop 内部循环用的是同一个列表引用（{@code CopyOnWriteArrayList}，
     * 天然支持"一边被子线程 add、一边被 dispatch 线程读取/复制"），{@link #roundStarted} 在每轮
     * 循环开头调用，记录"这一刻已经完整跑完的轮数"，与 runLoop 自己失败分支里 {@code round - 1}
     * 的口径保持一致。
     */
    static final class Progress {
        private final List<String> toolsUsed = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger completedRounds = new AtomicInteger(0);

        List<String> toolsUsedList() {
            return toolsUsed;
        }

        /** 第 round 轮开始执行前调用：此刻已完整跑完的轮数是 round - 1（本轮才刚开始，还不算数）。 */
        void roundStarted(int round) {
            completedRounds.set(round - 1);
        }

        List<String> toolsUsedSnapshot() {
            return List.copyOf(toolsUsed);
        }

        int roundsCompleted() {
            return completedRounds.get();
        }
    }

    public SubAgentService(ToolRegistry toolRegistry,
                           ChatModelFactory chatModelFactory,
                           XmlToolCallParser xmlToolCallParser,
                           SseEmitterService sseEmitterService,
                           SubAgentProperties props,
                           AuxModelResolver auxModelResolver,
                           TokenUsageService tokenUsageService) {
        this.toolRegistry = toolRegistry;
        this.chatModelFactory = chatModelFactory;
        this.xmlToolCallParser = xmlToolCallParser;
        this.sseEmitterService = sseEmitterService;
        this.props = props;
        this.auxModelResolver = auxModelResolver;
        this.tokenUsageService = tokenUsageService;
        AtomicInteger seq = new AtomicInteger();
        // 专用线程池：与主循环的 taskExecutor 隔离，池大小 = 子任务并行度上限
        this.executor = new ThreadPoolExecutor(
                props.getMaxParallel(), props.getMaxParallel(),
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "sub-agent-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        ((ThreadPoolExecutor) this.executor).allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 委派一个子任务并阻塞等待其结构化结果（带超时保护，不挂死主循环）。
     *
     * @param taskDescription 子任务的完整独立描述
     * @param expectedOutput  期望产出的描述
     * @param toolScope       子 Agent 可用工具名（空 = 全部工具）；dispatch_subtask 恒被排除
     * @param parentCtx       主会话工具上下文——projectId/conversationId/userId 由此继承（不变式 3）
     */
    public SubAgentResult dispatch(String taskDescription, String expectedOutput,
                                   List<String> toolScope, ToolContext parentCtx) {
        String subtaskId = "subtask-" + UUID.randomUUID().toString().substring(0, 8);
        // started/结束事件必须成对发出（前端的子任务卡按 taskId 配对渲染），
        // 所以模型校验失败那条路径也走"先 started 再 failed"，不能只发一个 failed
        sendProgress(parentCtx, subtaskId, "started", 0,
                com.checkba.service.LangText.of("子任务开始：", "Subtask started: ") + brief(taskDescription));

        // 模型在提交线程解析，非白名单在起跑前就拒绝：ChatModelFactory 对非白名单的处置是
        // 静默回落默认模型——failover 链踩过的同一个坑（看着「配了便宜模型」，实际跑的是主模型，
        // 账单要到月底才看得出来）。这里给出可读提示，让模型与面板都能看到原因。
        String modelId = auxModelResolver.subAgentModelId(props.getModel());
        if (!AllowedModels.isAllowed(modelId)) {
            log.warn("子 Agent 模型 '{}' 不在可用模型清单内，子任务 {} 未派发", modelId, subtaskId);
            SubAgentResult rejected = SubAgentResult.failure(subtaskId,
                    com.checkba.service.LangText.of(
                            "子 Agent 模型「" + modelId + "」不在可用模型清单内，本次子任务没有执行。"
                                    + "到设置页的 AI 供应商里把子 Agent 模型换成清单内的模型，"
                                    + "或清空该项让它跟随辅助模型。",
                            "Sub-agent model \"" + modelId + "\" is not in the allowed model list, so this subtask was not executed. "
                                    + "In Settings > AI Provider, switch the sub-agent model to one from the list, "
                                    + "or clear the field to follow the auxiliary model."),
                    List.of(), 0);
            sendProgress(parentCtx, subtaskId, "failed", 100,
                    com.checkba.service.LangText.of("子任务失败：", "Subtask failed: ") + brief(rejected.error()));
            return rejected;
        }

        // 跨线程提交：子 Agent 的模型调用也按主会话身份计费（平台通道 per-user），显式重放。
        // 身份优先取 parentCtx.userId()（ToolRegistry 分发时已按它重建过作用域），
        // 不指望「提交线程恰好有作用域」——流式回调线程/子 Agent 线程都不继承请求线程的 ThreadLocal，
        // 缺身份在云多租户下会被 PlatformAiChannel 判成「未携带用户身份」直接抛 AccountException。
        Long scopedUser = parentCtx != null && parentCtx.userId() != null
                ? parentCtx.userId()
                : PlatformAiUserScope.current();
        // 跨线程进度快照：超时/中断/取消时子 Agent 可能已经真实跑过几轮工具调用，
        // 但 future.get() 抛异常时拿不到 runLoop 的返回值（被取消的 Future 连正常返回值都读不到）。
        // 没有这个快照的话，下面几条 catch 只能硬编 toolsUsed=空/rounds=0——即便子 Agent
        // 已经执行过真实的、可能有副作用的工具调用，父级也完全看不出来（审计条目）。
        Progress progress = new Progress();
        Callable<SubAgentResult> task = () -> PlatformAiUserScope.call(scopedUser, () -> {
            IN_SUB_AGENT.set(Boolean.TRUE);
            try {
                return runLoop(subtaskId, taskDescription, expectedOutput, toolScope, parentCtx, modelId, progress);
            } finally {
                IN_SUB_AGENT.remove();
            }
        });
        Future<SubAgentResult> future = executor.submit(task);
        // 登记后才可能被取消端点看见；先 submit 再 put 的窗口只会让「刚提交那一瞬的取消」失败，
        // 用户重点一次即可，比先 put 再 submit（句柄里是 null future）安全
        running.put(subtaskId, new RunningSubtask(
                parentCtx != null ? parentCtx.conversationId() : null, future));

        SubAgentResult result;
        boolean cancelledByUser = false;
        try {
            result = future.get(props.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            result = SubAgentResult.failure(subtaskId,
                    "sub-agent timed out after " + props.getTimeoutSeconds() + "s",
                    progress.toolsUsedSnapshot(), progress.roundsCompleted());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            result = SubAgentResult.failure(subtaskId, "sub-agent interrupted",
                    progress.toolsUsedSnapshot(), progress.roundsCompleted());
        } catch (CancellationException e) {
            // 用户点了「停止子任务」（见 cancel）。回喂给模型的文案必须点明「是用户停的、不要自动重派」：
            // 只说 failed 的话模型下一轮会立刻再 dispatch 一次，用户看到的是「点了停止反而又跑起来」。
            cancelledByUser = true;
            result = SubAgentResult.failure(subtaskId,
                    "sub-agent was stopped by the user. Do NOT dispatch this subtask again automatically; "
                            + "tell the user it stopped and ask how to proceed.",
                    progress.toolsUsedSnapshot(), progress.roundsCompleted());
        } catch (Exception e) {
            log.error("Sub-agent {} failed unexpectedly", subtaskId, e);
            result = SubAgentResult.failure(subtaskId,
                    "sub-agent failed: " + e.getMessage(),
                    progress.toolsUsedSnapshot(), progress.roundsCompleted());
        } finally {
            running.remove(subtaskId);
        }

        // 停止走的仍是 failed 这个 stage（前端按 stage=='started' 二分 loading/done，
        // 新造一个 stage 值只会多一处待同步的字面量），区别体现在给用户看的文案上
        sendProgress(parentCtx, subtaskId,
                result.success() ? "succeeded" : "failed", 100,
                result.success() ? com.checkba.service.LangText.of("子任务完成", "Subtask completed")
                        : cancelledByUser ? com.checkba.service.LangText.of("子任务已停止", "Subtask stopped")
                        : com.checkba.service.LangText.of("子任务失败：", "Subtask failed: ") + brief(result.error()));
        return result;
    }

    /**
     * 任务级取消：停掉一个正在跑的子任务（「长任务可控」的一半——另一半是后台任务取消）。
     *
     * <p><b>只承诺「正在停止」，不承诺「立即停止」</b>：{@code cancel(true)} 打不断已经发出去的
     * HTTP 读（OkHttp 的阻塞 read 不响应 interrupt），而 {@link #runLoop} 的中断检查在每轮开头，
     * 所以最坏情况是白烧一次在途 LLM 调用后才停下。文案上不要写「已停止」。
     *
     * @param subtaskId      dispatch 时生成、随 subtask_progress 事件下发给前端的子任务 ID
     * @param conversationId 调用方声明的会话；必须与该子任务登记的会话一致
     * @return true = 已请求停止；false = 没有这个正在跑的子任务，或它不属于该会话
     */
    public boolean cancel(String subtaskId, String conversationId) {
        if (subtaskId == null || conversationId == null) {
            return false;
        }
        RunningSubtask handle = running.get(subtaskId);
        // 控制器只验证了「调用方能用这个会话」，这里再验「这个子任务属于这个会话」：
        // 两道合起来才挡得住「拿自己的会话 ID + 猜到的 subtaskId 去停别人的子任务」
        if (handle == null || !conversationId.equals(handle.conversationId())) {
            return false;
        }
        handle.future().cancel(true);
        log.info("子任务 {} 收到停止请求（会话 {}）", subtaskId, conversationId);
        return true;
    }

    /**
     * 子 Agent 循环：主循环的极简版（Thought→Action→Observation，双协议），
     * 无 SSE 推流、无消息持久化、无 artifact/title 等主会话专属分支。
     *
     * @param modelId 已解析并校验过白名单的子 Agent 模型（见 {@link #dispatch}）
     * @param progress 跨线程进度快照（见 {@link Progress}），dispatch() 在超时/中断/取消分支读取
     */
    SubAgentResult runLoop(String subtaskId, String taskDescription, String expectedOutput,
                           List<String> toolScope, ToolContext parentCtx, String modelId, Progress progress) {
        // 子 Agent 继承主会话的客户端能力（不变式 3 同款思路）：
        // office 会话派生的子 Agent 同样不该看到 doc_*/sheet_* 死路径工具
        String parentConversationId = parentCtx != null ? parentCtx.conversationId() : null;
        Set<String> allowed = resolveScope(toolScope, parentConversationId);
        List<ToolSpecification> specs = toolRegistry.getAllSpecifications(parentConversationId).stream()
                .filter(s -> allowed.contains(s.name()))
                .toList();

        ChatLanguageModel model = chatModelFactory.getChatModel(modelId);
        // 子 Agent 的工具调用继承主会话身份（不变式 3），模型可独立
        ToolContext subCtx = parentCtx == null
                ? new ToolContext(null, null, null, modelId)
                : new ToolContext(parentCtx.projectId(), parentCtx.conversationId(), parentCtx.userId(), modelId);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(buildSystemPrompt(expectedOutput, allowed)));
        messages.add(UserMessage.from(taskDescription));

        // 与 progress 共用同一个列表引用：executeScoped 往这里 add，dispatch() 的超时/中断/
        // 取消分支能看到跑到一半时真实已经执行过的工具（见 Progress 类注释）。
        List<String> toolsUsed = progress.toolsUsedList();
        long charBudget = (long) (props.getTokenBudget() * props.getCharsPerToken());

        for (int round = 1; round <= props.getMaxRounds(); round++) {
            // 记「已完整跑完 round-1 轮」：本轮才刚开始，还不算数——与下面各分支自己返回的
            // round - 1 保持同一个口径。
            progress.roundStarted(round);
            if (Thread.currentThread().isInterrupted()) {
                return SubAgentResult.failure(subtaskId, "sub-agent interrupted", toolsUsed, round - 1);
            }
            if (estimateChars(messages) > charBudget) {
                return SubAgentResult.failure(subtaskId,
                        "sub-agent token budget exceeded (" + props.getTokenBudget() + " tokens)",
                        toolsUsed, round - 1);
            }

            Response<AiMessage> response;
            try {
                response = specs.isEmpty() ? model.generate(messages) : model.generate(messages, specs);
            } catch (Exception e) {
                log.warn("Sub-agent {} LLM call failed at round {}", subtaskId, round, e);
                return SubAgentResult.failure(subtaskId,
                        "sub-agent LLM call failed: " + e.getMessage(), toolsUsed, round - 1);
            }
            recordUsage(response, modelId, parentCtx);
            AiMessage aiMessage = response.content();
            messages.add(aiMessage);

            // 协议 1：原生 function calling
            if (aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    String output = executeScoped(req.name(), req.arguments(), allowed, subCtx, toolsUsed);
                    messages.add(ToolExecutionResultMessage.from(req, output));
                }
                continue;
            }

            String text = aiMessage.text() == null ? "" : aiMessage.text();

            // 协议 2：XML <tool_code> 兜底
            if (xmlToolCallParser.containsToolCall(text)) {
                boolean executed = false;
                for (XmlToolCallParser.ParsedCall call : xmlToolCallParser.parse(text)) {
                    String output = executeScoped(call.toolName(), call.argsJson(), allowed, subCtx, toolsUsed);
                    messages.add(UserMessage.from("[Tool Execution Result]\nTool: " + call.rawCode()
                            + "\nOutput: " + output));
                    executed = true;
                }
                if (executed) {
                    continue;
                }
            }

            // 无工具调用 = 最终答案
            return SubAgentResult.success(subtaskId, text.trim(), toolsUsed, round);
        }

        return SubAgentResult.failure(subtaskId,
                "sub-agent reached max rounds (" + props.getMaxRounds() + ") without a final answer",
                toolsUsed, props.getMaxRounds());
    }

    /**
     * 子 Agent 每一轮的 token 记账。
     *
     * <p>此前 {@code response.tokenUsage()} 从未被读过：跑满 6 轮的子任务在 token_usage 里一行都没有，
     * 这些花费被整块折进下一条主循环记录——总额对，但逐条归属与逐模型分布是错的
     * （子 Agent 现在默认走便宜的辅助模型，不记就看不出省了多少）。
     *
     * <p>归属取主会话上下文（不变式 3：projectId/conversationId/userId 一律继承，模型可独立），
     * 记账失败绝不影响子任务结果。
     */
    private void recordUsage(Response<AiMessage> response, String modelId, ToolContext parentCtx) {
        if (response == null || response.tokenUsage() == null) {
            return;
        }
        try {
            Long projectId = parentCtx != null ? parentCtx.projectId() : null;
            Long userId = parentCtx != null && parentCtx.userId() != null
                    ? parentCtx.userId()
                    : PlatformAiUserScope.current();
            String conversationId = parentCtx != null ? parentCtx.conversationId() : null;
            tokenUsageService.recordUsage(projectId, userId, modelId, response.tokenUsage(), conversationId);
        } catch (Exception e) {
            log.warn("子 Agent token 记账失败（不影响子任务结果）: {}", e.getMessage());
        }
    }

    /** 受限分发：先别名解析，再做防递归与工具域校验，最后经 ToolRegistry 执行 */
    private String executeScoped(String rawName, String argsJson, Set<String> allowed,
                                 ToolContext subCtx, List<String> toolsUsed) {
        String resolved = ToolRegistry.TOOL_NAME_ALIASES.getOrDefault(rawName, rawName);
        if (DISPATCH_TOOL_NAME.equals(resolved)) {
            return "Error: dispatch_subtask is not available inside a sub-agent (nested delegation refused).";
        }
        if (!allowed.contains(resolved)) {
            return "Error: tool '" + resolved + "' is outside this sub-agent's tool scope.";
        }
        toolsUsed.add(resolved);
        ToolRegistry.ToolResult result = toolRegistry.execute(rawName, argsJson, subCtx);
        String output = result.output();
        // 空输出归一（同主编排器）：ToolExecutionResultMessage.from 的 ensureNotBlank 会对空白
        // 抛异常，一个返回空串的工具就能把整个子任务打成 LLM call failed。两条协议分支共用这里。
        return output == null || output.isBlank()
                ? com.checkba.service.ai.AgentOrchestrator.BLANK_TOOL_OUTPUT : output;
    }

    /**
     * 解析 tool_scope 为别名解析后的可用工具名集合。
     * 空 scope = 全部已注册工具；dispatch_subtask 无条件排除（防递归第一道防线）。
     */
    private Set<String> resolveScope(List<String> toolScope, String conversationId) {
        Set<String> allowed = new LinkedHashSet<>();
        if (toolScope == null || toolScope.isEmpty()) {
            for (ToolSpecification spec : toolRegistry.getAllSpecifications(conversationId)) {
                allowed.add(spec.name());
            }
        } else {
            for (String name : toolScope) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                allowed.add(ToolRegistry.TOOL_NAME_ALIASES.getOrDefault(name.trim(), name.trim()));
            }
        }
        allowed.remove(DISPATCH_TOOL_NAME);
        return allowed;
    }

    private String buildSystemPrompt(String expectedOutput, Set<String> allowed) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专注的子任务执行 Agent，只负责完成下面这一个子任务。\n");
        sb.append("规则：\n");
        sb.append("1. 需要外部信息时调用可用工具；不需要工具时直接给出答案。\n");
        sb.append("2. 完成后直接输出最终结果的纯文本，不要输出 <final> 等标签，不要闲聊。\n");
        sb.append("3. 你不能委派新的子任务（dispatch_subtask 不可用）。\n");
        if (expectedOutput != null && !expectedOutput.isBlank()) {
            sb.append("期望产出：").append(expectedOutput).append("\n");
        }
        if (!allowed.isEmpty()) {
            sb.append("可用工具：").append(String.join(", ", allowed)).append("\n");
        }
        return sb.toString();
    }

    private long estimateChars(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage m : messages) {
            try {
                String text = m.text();
                if (text != null) {
                    total += text.length();
                }
            } catch (Exception ignored) {
                // 多模态等无纯文本表示的消息忽略
            }
        }
        return total;
    }

    /** 子任务开始/结束向主会话推送 subtask_progress（新增 SSE 事件，结构对齐 TaskProgressEvent） */
    private void sendProgress(ToolContext ctx, String subtaskId, String stage, int progress, String message) {
        if (ctx == null || ctx.conversationId() == null) {
            return;
        }
        try {
            TaskProgressEvent event = TaskProgressEvent.builder()
                    .taskId(subtaskId)
                    .taskType("SUBTASK")
                    .source("SUB_AGENT")
                    .progress(progress)
                    .message(message)
                    .stage(stage)
                    .timestamp(System.currentTimeMillis())
                    .build();
            sseEmitterService.send(ctx.conversationId(), "subtask_progress", JSONUtil.toJsonStr(event));
        } catch (Exception e) {
            log.warn("Failed to send subtask_progress for {}", subtaskId, e);
        }
    }

    private String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }
}
