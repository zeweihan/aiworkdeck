package com.checkba.service.ai.subagent;

import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.SseEmitterService;
import com.checkba.service.ai.ToolRegistry;
import com.checkba.service.ai.XmlToolCallParser;
import com.checkba.service.ai.tools.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubAgentService 单测：循环上限 / token 预算 / 超时 / 防递归 / 工具域 / 身份继承（不变式 3）。
 * LLM 用 Mockito 打桩，ToolRegistry 用 mock 记录分发（不真执行工具）。
 */
@DisplayName("子 Agent 服务（Phase 3C）")
class SubAgentServiceTest {

    private static final ToolContext PARENT_CTX =
            new ToolContext(42L, "conv-main", 7L, "anthropic/claude-3.5-sonnet");

    private ToolRegistry registry;
    private ChatModelFactory modelFactory;
    private ChatLanguageModel model;
    private SseEmitterService sse;
    private SubAgentProperties props;

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistry.class);
        // 子 Agent 走会话能力感知的 getAllSpecifications(conversationId)（Phase C）
        when(registry.getAllSpecifications(any())).thenReturn(List.of(
                ToolSpecification.builder().name("search_web").description("web search").build(),
                ToolSpecification.builder().name("read_document").description("read file").build(),
                ToolSpecification.builder().name("dispatch_subtask").description("delegate").build()));
        when(registry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("tool output (test stub)", null, true));

        model = mock(ChatLanguageModel.class);
        modelFactory = mock(ChatModelFactory.class);
        when(modelFactory.getChatModel(any())).thenReturn(model);

        sse = mock(SseEmitterService.class);
        props = new SubAgentProperties();
    }

    private SubAgentService newService() {
        return new SubAgentService(registry, modelFactory, new XmlToolCallParser(registry), sse, props);
    }

    private static Response<AiMessage> toolCallTurn(String toolName, String argsJson) {
        return Response.from(AiMessage.from(ToolExecutionRequest.builder()
                .id("req-" + toolName).name(toolName).arguments(argsJson).build()));
    }

    private static Response<AiMessage> textTurn(String text) {
        return Response.from(AiMessage.from(text));
    }

    @Test
    @DisplayName("身份继承（不变式 3）：子 Agent 工具调用的 ToolContext 与主会话一致 + 进度事件成对发送")
    void identityInheritedFromParentContext() {
        when(model.generate(anyList(), anyList())).thenReturn(
                toolCallTurn("search_web", "{\"query\":\"对赌协议\"}"),
                textTurn("要点：1……"));

        SubAgentResult result = newService().dispatch("调研对赌协议裁判观点", "要点列表",
                List.of("search_web"), PARENT_CTX);

        assertTrue(result.success(), "应成功: " + result.toJson());
        assertEquals(List.of("search_web"), result.toolsUsed());
        assertEquals(2, result.rounds());
        assertTrue(result.result().contains("要点"));

        ArgumentCaptor<ToolContext> ctxCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(registry).execute(eq("search_web"), any(), ctxCaptor.capture());
        ToolContext used = ctxCaptor.getValue();
        assertNotNull(used);
        assertEquals(PARENT_CTX.projectId(), used.projectId(), "projectId 必须继承主会话");
        assertEquals(PARENT_CTX.conversationId(), used.conversationId(), "conversationId 必须继承主会话");
        assertEquals(PARENT_CTX.userId(), used.userId(), "userId 必须继承主会话");
        // 未配置独立模型时继承主会话模型
        assertEquals(PARENT_CTX.modelId(), used.modelId());

        // 子任务开始/结束各发一次 subtask_progress（发到主会话 conversationId）
        verify(sse, times(2)).send(eq("conv-main"), eq("subtask_progress"), any());
    }

    @Test
    @DisplayName("防递归：子 Agent 内调用 dispatch_subtask 被拒绝，不经 ToolRegistry 分发")
    void recursionRefused() {
        when(model.generate(anyList(), anyList())).thenReturn(
                toolCallTurn("dispatch_subtask", "{\"task_description\":\"再拆一层\"}"),
                textTurn("最终结果"));

        SubAgentResult result = newService().dispatch("任务", "结果", List.of(), PARENT_CTX);

        assertTrue(result.success());
        // dispatch_subtask 从未真正分发
        verify(registry, never()).execute(eq("dispatch_subtask"), any(), any());
        assertTrue(result.toolsUsed().isEmpty(), "拒绝的调用不计入 toolsUsed");

        // 拒绝错误作为工具结果回喂给了子模型
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(model, atLeastOnce()).generate(messagesCaptor.capture(), anyList());
        boolean refusalFedBack = messagesCaptor.getValue().stream()
                .anyMatch(m -> {
                    try {
                        return m.text() != null && m.text().contains("nested delegation refused");
                    } catch (Exception e) {
                        return false;
                    }
                });
        assertTrue(refusalFedBack, "子模型应收到明确的递归拒绝错误");
    }

    @Test
    @DisplayName("轮数上限：耗尽后返回明确失败结果")
    void maxRoundsExceeded() {
        props.setMaxRounds(2);
        when(model.generate(anyList(), anyList()))
                .thenReturn(toolCallTurn("search_web", "{\"query\":\"a\"}"))
                .thenReturn(toolCallTurn("search_web", "{\"query\":\"b\"}"));

        SubAgentResult result = newService().dispatch("查不完的任务", null, List.of("search_web"), PARENT_CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("max rounds"), "错误信息应说明轮数耗尽: " + result.error());
        assertEquals(2, result.rounds());
        assertEquals(List.of("search_web", "search_web"), result.toolsUsed());
    }

    @Test
    @DisplayName("token 预算：超出后立即中止，不再调用 LLM")
    void tokenBudgetExceeded() {
        props.setTokenBudget(1);
        props.setCharsPerToken(1.0);

        SubAgentResult result = newService().dispatch("这条任务描述本身就超过一个 token 的预算了",
                null, List.of("search_web"), PARENT_CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("token budget"), "错误信息应说明预算超限: " + result.error());
        verify(model, never()).generate(anyList(), anyList());
    }

    @Test
    @DisplayName("超时：返回明确错误结果，不挂死调用方")
    void timeoutReturnsExplicitError() {
        props.setTimeoutSeconds(1);
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return textTurn("太迟了");
        });

        SubAgentResult result = newService().dispatch("慢任务", null, List.of("search_web"), PARENT_CTX);

        assertFalse(result.success());
        assertTrue(result.error().contains("timed out"), "错误信息应说明超时: " + result.error());
    }

    @Test
    @DisplayName("工具域：scope 外的工具被拒绝，不经 ToolRegistry 分发")
    void toolScopeEnforced() {
        when(model.generate(anyList(), anyList())).thenReturn(
                toolCallTurn("read_document", "{\"fileId\":\"1\"}"),
                textTurn("好的，我不读文件了，直接给结论"));

        SubAgentResult result = newService().dispatch("任务", null, List.of("search_web"), PARENT_CTX);

        assertTrue(result.success());
        verify(registry, never()).execute(eq("read_document"), any(), any());
        assertTrue(result.toolsUsed().isEmpty());
    }

    @Test
    @DisplayName("tool_scope 容错解析：JSON 数组与逗号/顿号分隔均可")
    void parseToolScopeTolerant() {
        assertEquals(List.of("search_web", "browse_url"),
                com.checkba.service.ai.tools.SubAgentTools.parseToolScope("[\"search_web\", \"browse_url\"]"));
        assertEquals(List.of("search_web", "browse_url"),
                com.checkba.service.ai.tools.SubAgentTools.parseToolScope("search_web, browse_url"));
        assertEquals(List.of("search_web", "browse_url"),
                com.checkba.service.ai.tools.SubAgentTools.parseToolScope("search_web、browse_url"));
        assertTrue(com.checkba.service.ai.tools.SubAgentTools.parseToolScope("  ").isEmpty());
        assertTrue(com.checkba.service.ai.tools.SubAgentTools.parseToolScope(null).isEmpty());
    }
}
