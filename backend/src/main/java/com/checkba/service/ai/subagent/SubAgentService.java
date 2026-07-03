package com.checkba.service.ai.subagent;

import cn.hutool.json.JSONUtil;
import com.checkba.dto.ai.TaskProgressEvent;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.SseEmitterService;
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
import java.util.Set;
import java.util.UUID;
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
    private final ExecutorService executor;

    public SubAgentService(ToolRegistry toolRegistry,
                           ChatModelFactory chatModelFactory,
                           XmlToolCallParser xmlToolCallParser,
                           SseEmitterService sseEmitterService,
                           SubAgentProperties props) {
        this.toolRegistry = toolRegistry;
        this.chatModelFactory = chatModelFactory;
        this.xmlToolCallParser = xmlToolCallParser;
        this.sseEmitterService = sseEmitterService;
        this.props = props;
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
        sendProgress(parentCtx, subtaskId, "started", 0, "子任务开始：" + brief(taskDescription));

        Future<SubAgentResult> future = executor.submit(() -> {
            IN_SUB_AGENT.set(Boolean.TRUE);
            try {
                return runLoop(subtaskId, taskDescription, expectedOutput, toolScope, parentCtx);
            } finally {
                IN_SUB_AGENT.remove();
            }
        });

        SubAgentResult result;
        try {
            result = future.get(props.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            result = SubAgentResult.failure(subtaskId,
                    "sub-agent timed out after " + props.getTimeoutSeconds() + "s", List.of(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            result = SubAgentResult.failure(subtaskId, "sub-agent interrupted", List.of(), 0);
        } catch (Exception e) {
            log.error("Sub-agent {} failed unexpectedly", subtaskId, e);
            result = SubAgentResult.failure(subtaskId,
                    "sub-agent failed: " + e.getMessage(), List.of(), 0);
        }

        sendProgress(parentCtx, subtaskId,
                result.success() ? "succeeded" : "failed", 100,
                result.success() ? "子任务完成" : "子任务失败：" + brief(result.error()));
        return result;
    }

    /**
     * 子 Agent 循环：主循环的极简版（Thought→Action→Observation，双协议），
     * 无 SSE 推流、无消息持久化、无 artifact/title 等主会话专属分支。
     */
    SubAgentResult runLoop(String subtaskId, String taskDescription, String expectedOutput,
                           List<String> toolScope, ToolContext parentCtx) {
        Set<String> allowed = resolveScope(toolScope);
        List<ToolSpecification> specs = toolRegistry.getAllSpecifications().stream()
                .filter(s -> allowed.contains(s.name()))
                .toList();

        String modelId = (props.getModel() != null && !props.getModel().isBlank())
                ? props.getModel()
                : (parentCtx != null ? parentCtx.modelId() : null);
        ChatLanguageModel model = chatModelFactory.getChatModel(modelId);
        // 子 Agent 的工具调用继承主会话身份（不变式 3），模型可独立
        ToolContext subCtx = parentCtx == null
                ? new ToolContext(null, null, null, modelId)
                : new ToolContext(parentCtx.projectId(), parentCtx.conversationId(), parentCtx.userId(), modelId);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(buildSystemPrompt(expectedOutput, allowed)));
        messages.add(UserMessage.from(taskDescription));

        List<String> toolsUsed = new ArrayList<>();
        long charBudget = (long) (props.getTokenBudget() * props.getCharsPerToken());

        for (int round = 1; round <= props.getMaxRounds(); round++) {
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
        return result.output();
    }

    /**
     * 解析 tool_scope 为别名解析后的可用工具名集合。
     * 空 scope = 全部已注册工具；dispatch_subtask 无条件排除（防递归第一道防线）。
     */
    private Set<String> resolveScope(List<String> toolScope) {
        Set<String> allowed = new LinkedHashSet<>();
        if (toolScope == null || toolScope.isEmpty()) {
            for (ToolSpecification spec : toolRegistry.getAllSpecifications()) {
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
