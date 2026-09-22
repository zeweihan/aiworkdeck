// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiFailoverProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.account.AccountException;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.RunLoopCompactor;
import com.checkba.service.ai.memory.MemoryPipelineService;
import com.checkba.service.ai.skill.SkillRouter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 终止 catch 块的落库：用户消息已经落了，助手侧不补一条，刷新页面后这一轮就只剩用户
 * 自己的问题——看起来像 AI 完全没理他，而不是出过错。
 *
 * <p>守三条：AccountException（未分配 AI 额度这类，新用户最常撞）与兜底 Exception，
 * 各自都必须落一条 ASSISTANT，且内容与 SSE 推出去的是同一串；再加上流式传输出错这一条
 * ——它要连<b>本轮已经执行过的工具</b>一起落（与取消那条路同源的缺陷，见 D-01）。
 */
class AgentOrchestratorTerminalErrorPersistenceTest {

    private static final String MODEL = "anthropic/claude-sonnet-5";

    private ChatModelFactory chatModelFactory;
    private ProjectAiMessageService messageService;
    private AgentRunStateService runState;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;
    private SseEmitterService sse;
    private ContextAssemblerService assembler;

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        sseEvents = new CopyOnWriteArrayList<>();
        sseData = new CopyOnWriteArrayList<>();
        sse = mock(SseEmitterService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            sseEvents.add(inv.getArgument(1));
            sseData.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        messageService = mock(ProjectAiMessageService.class);
        // dev-board#729 ⑤：编排器改用 countByConversationId 判首轮；mock 默认回 0 会误判首轮、起异步标题线程
        // 与下一次 when(...) 打架（CI 上 Mockito WrongTypeOfReturnValue）。计数跟随 list 桩，保持各用例原语义。
        when(messageService.countByConversationId(any())).thenAnswer(inv -> (long) messageService.listByConversationId(inv.getArgument(0)).size());
        // 返回 2 条：跳过首轮标题生成的异步分支，本用例只关心终止路径
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("整理一下这份合同"))));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any())).thenReturn(List.of());
        rebuildOrchestratorWith(toolRegistry);
    }

    /** 只有工具注册表因用例而异，其余依赖与 setUp 完全一致。 */
    private void rebuildOrchestratorWith(ToolRegistry toolRegistry) {
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        AiFailoverProperties failoverProperties = new AiFailoverProperties();
        failoverProperties.setModels(List.of());
        AiContextProperties contextProperties = new AiContextProperties();
        RunLoopCompactor compactor =
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties));

        runState = new AgentRunStateService(mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class), runState, mock(com.checkba.version.WorkSessionService.class),
                failoverProperties, compactor,
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new com.checkba.service.ai.OfficePassStateStore());
    }

    private void run(String conversationId) {
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("整理一下这份合同");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);
    }

    /** 取本轮唯一那条 error 事件的载荷 */
    private String errorPayload() {
        int idx = sseEvents.indexOf("error");
        assertTrue(idx >= 0, "应给用户推了 error 事件：" + sseEvents);
        return sseData.get(idx);
    }

    /** 取落库的那条 ASSISTANT 正文 */
    private String persistedAssistantText(String conversationId) {
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(messageService, times(1)).upsertAssistantMessage(
                eq("1"), eq(7L), eq(conversationId), any(), content.capture());
        return content.getValue();
    }

    @Test
    @DisplayName("未分配 AI 额度：落一条 ASSISTANT，原文即推给用户的中文提示")
    void persistsAssistantMessageOnAccountException() {
        String notice = "请先在官网账户页分配 AI 额度";
        when(chatModelFactory.getStreamingChatModel(MODEL))
                .thenThrow(new AccountException(AccountException.Kind.CONFLICT, notice));

        run("conv-account");

        assertEquals(notice, errorPayload(), "账户类失败原样透出中文文案，不加前缀");
        assertEquals(notice, persistedAssistantText("conv-account"),
                "历史里必须留下这一轮的回应，且与 SSE 推出去的一致");
        assertEquals(AgentRunStateService.RunStatus.ERROR, runState.get("conv-account").status());
    }

    @Test
    @DisplayName("兜底异常：落一条 ASSISTANT，内容与 SSE 推出去的同一串")
    void persistsAssistantMessageOnGenericException() {
        when(chatModelFactory.getStreamingChatModel(MODEL))
                .thenThrow(new RuntimeException("boom"));

        run("conv-generic");

        assertEquals("Internal Error: boom", errorPayload());
        assertEquals(errorPayload(), persistedAssistantText("conv-generic"),
                "落库内容必须与 SSE 发出的错误文本一致");
        assertEquals(AgentRunStateService.RunStatus.ERROR, runState.get("conv-generic").status());
    }

    /** 落库的最后一条 ASSISTANT 正文（有工具的轮次中途还有一次增量保存，所以不能断言只写一次）。 */
    private String lastPersistedAssistantText(String conversationId) {
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(messageService, org.mockito.Mockito.atLeastOnce()).upsertAssistantMessage(
                eq("1"), eq(7L), eq(conversationId), any(), content.capture());
        return content.getValue();
    }

    /**
     * 流式传输出错的终态收尾必须带上执行日志（与取消那条路同源，见 D-01）。
     *
     * <p>病灶：{@code handleStreamErrorTerminal} 给 {@code finishWithError} 硬传了 null
     * executionLog，于是「模型先调了几个工具、然后这一轮流式出错」的收尾把工具过程卡整段丢掉，
     * 刷新后历史里只剩一句错误提示，看不出 AI 到底动过哪些文件。
     *
     * <p>用 400 触发 FATAL：既不退避重试、也不走故障转移（failover 清单本来就是空的），
     * 直接落到终态收尾，是这条路最短的走法。
     */
    @Test
    @DisplayName("流式传输出错：落库的 ASSISTANT 行要含本轮已执行工具的 tool_code 执行日志")
    void streamErrorPersistsTheExecutionLogOfToolsAlreadyRun() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any())).thenReturn(List.of(
                dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name("read_document").description("读文档").build()));
        when(toolRegistry.resolve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("合同正文若干", null, true));
        rebuildOrchestratorWith(toolRegistry);

        // 第一轮调工具，第二轮流式出错
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(
                new StreamingChatLanguageModel() {
                    int calls;
                    @Override
                    public void generate(List<ChatMessage> messages,
                                         dev.langchain4j.model.StreamingResponseHandler
                                                 <dev.langchain4j.data.message.AiMessage> handler) {
                        generate(messages, List.of(), handler);
                    }
                    @Override
                    public void generate(List<ChatMessage> messages,
                                         List<dev.langchain4j.agent.tool.ToolSpecification> tools,
                                         dev.langchain4j.model.StreamingResponseHandler
                                                 <dev.langchain4j.data.message.AiMessage> handler) {
                        if (calls++ == 0) {
                            handler.onComplete(dev.langchain4j.model.output.Response.from(
                                    dev.langchain4j.data.message.AiMessage.from(List.of(
                                            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                                    .id("1").name("read_document")
                                                    .arguments("{\"fileId\":1}").build()))));
                            return;
                        }
                        handler.onError(new dev.ai4j.openai4j.OpenAiHttpException(400, "bad request"));
                    }
                });

        run("conv-stream-error");

        // 两条断言都打在**同一行**上：整批工具跑完后有一次增量保存会先写下过程卡，
        // 所以分别看两处都「有」是假绿——必须是最终落下的那一行同时带着过程卡与出错标记。
        String saved = lastPersistedAssistantText("conv-stream-error");
        assertTrue(saved.contains("[生成出错，已中断]"),
                "出错轮根本没写最终那一行（partialContent 与 executionLog 都被当成空的）：" + saved);
        assertTrue(saved.contains("<tool_code>read_document"),
                "出错轮的落库丢掉了工具过程卡，历史里只剩一句错误提示，"
                        + "用户看不出 AI 动过哪些文件：" + saved);
        assertEquals(AgentRunStateService.RunStatus.ERROR, runState.get("conv-stream-error").status());
    }
}
