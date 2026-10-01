// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiFailoverProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.RunLoopCompactor;
import com.checkba.service.ai.memory.MemoryPipelineService;
import com.checkba.service.ai.skill.SkillRouter;
import com.checkba.service.ai.subagent.SubAgentResult;
import com.checkba.service.ai.subagent.SubAgentService;
import com.checkba.service.ai.tools.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 法律意见书实质修订的最小有界收尾检查（dev-board#1097）。
 *
 * <p>病灶：legal-opinion-review skill 第 4 步要求模型在收尾前做一次独立只读核验
 * （dispatch_subtask + 固定只读 scope），真机上 DeepSeek V4 Flash 改完文档直接 finished，
 * 核验整步被跳过——只靠提示词约束不住。服务端兜底：仅在命中该 skill 的 AGENT 轮次
 * 正常收尾路径上补一次（<b>仅一次</b>）固定只读核验，把发现接回主助手核对后续一轮。
 *
 * <p>这里守的是接线而不是辅助方法的布尔值：真实编排器 + 脚本模型跑完整轮次，
 * 断言「什么时候补、补几次、scope 是什么、接回后怎么走、取消/失败/已核验时怎么办」。
 */
class AgentOrchestratorOpinionCompletionCheckTest {

    private static final String MODEL = "qwen/qwen3.7-flash";

    private ChatModelFactory chatModelFactory;
    private SseEmitterService sse;
    private AgentRunStateService runState;
    private ProjectAiMessageService messageService;
    private ToolRegistry toolRegistry;
    private SkillRouter skillRouter;
    private SubAgentService subAgentService;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    /** 按脚本逐轮吐内容的模型；记录每轮收到的消息栈（断言接回注入用） */
    private static final class ScriptModel implements StreamingChatLanguageModel {
        private final List<AiMessage> script;
        final AtomicInteger calls = new AtomicInteger();
        Runnable beforeFirstCompletion;
        final List<List<ChatMessage>> seenMessages = new CopyOnWriteArrayList<>();

        ScriptModel(List<AiMessage> script) {
            this.script = script;
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            int idx = calls.getAndIncrement();
            seenMessages.add(new ArrayList<>(messages));
            AiMessage msg = script.get(Math.min(idx, script.size() - 1));
            if (msg.text() != null && !msg.text().isEmpty()) {
                handler.onNext(msg.text());
            }
            if (idx == 0 && beforeFirstCompletion != null) beforeFirstCompletion.run();
            handler.onComplete(Response.from(msg));
        }

        @Override
        public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                             StreamingResponseHandler<AiMessage> handler) {
            generate(messages, handler);
        }
    }

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        sseEvents = new CopyOnWriteArrayList<>();
        sseData = new CopyOnWriteArrayList<>();
        sse = mock(SseEmitterService.class);
        doAnswer(inv -> {
            sseEvents.add(inv.getArgument(1));
            sseData.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        messageService = mock(ProjectAiMessageService.class);
        when(messageService.countByConversationId(any())).thenAnswer(inv -> (long) messageService.listByConversationId(inv.getArgument(0)).size());
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("帮我修订一下这个法律意见书"))));

        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("ok", null, true));

        skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(java.util.Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        subAgentService = mock(SubAgentService.class);
        when(subAgentService.dispatch(any(), any(), any(), any()))
                .thenReturn(SubAgentResult.success("sub-check",
                        "逐项核对：未发现冲突（已核对 3 项，读过 2 份材料）",
                        List.of("doc_get_document_text", "ref_read"), 2));

        AiContextProperties contextProperties = new AiContextProperties();
        runState = new AgentRunStateService(
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class), runState,
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new com.checkba.service.ai.OfficePassStateStore());
        ReflectionTestUtils.setField(orchestrator, "subAgentService", subAgentService);
    }

    /** 命中 legal-opinion-review skill 的开关（mock 默认 false = 未命中） */
    private void skillActive() {
        when(skillRouter.isActiveInRun(any(), eq(OpinionCompletionCheck.SKILL_ID))).thenReturn(true);
    }

    private ScriptModel run(String conversationId, AiMessage... script) {
        return run(conversationId, null, "帮我修订一下这个法律意见书", script);
    }

    private ScriptModel run(String conversationId, String mode, AiMessage... script) {
        return run(conversationId, mode, "帮我修订一下这个法律意见书", script);
    }

    private ScriptModel run(String conversationId, String mode, String message, AiMessage... script) {
        ScriptModel model = new ScriptModel(List.of(script));
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage(message);
        request.setModel(MODEL);
        AiAgentController.ContextItem active = new AiAgentController.ContextItem();
        active.setId("5"); active.setName("法律意见书.docx"); active.setFileType("docx");
        request.setActiveContext(active);
        if (mode != null) {
            request.setMode(mode);
        }
        orchestrator.handleUserMessage(request, 7L);
        return model;
    }

    private ScriptModel runWithMessage(String conversationId, String message, AiMessage... script) {
        return run(conversationId, null, message, script);
    }

    private String bubbleEndData() {
        for (int i = 0; i < sseEvents.size(); i++) {
            if ("bubble_end".equals(sseEvents.get(i))) return sseData.get(i);
        }
        return null;
    }

    /** 最后一次落库的 ASSISTANT 正文（第 5 个参数） */
    private String lastPersistedAssistant() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(messageService, org.mockito.Mockito.atLeastOnce())
                .upsertAssistantMessage(any(), any(), any(), any(), captor.capture());
        List<String> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private static String lastMessageText(ScriptModel model, int round) {
        List<ChatMessage> messages = model.seenMessages.get(round);
        ChatMessage last = messages.get(messages.size() - 1);
        return ((UserMessage) last).singleText();
    }

    // ==================== 正例：模型跳过核验 → 服务端补一次 → 接回 → 完成 ====================

    @Test
    @DisplayName("命中 skill 且模型直接收尾：服务端恰好补检一次（固定只读 scope），发现接回后续一轮再 FINISHED")
    void serverRunsOneReadOnlyCheckWhenModelSkippedVerification() {
        skillActive();
        ScriptModel model = run("conv-check",
                AiMessage.from("已按您的要求完成修订。"),
                AiMessage.from("已核对收尾核验发现，修订无误，交付说明如下。"));

        // 恰好一次，scope 就是写死的只读清单，身份沿本轮 ToolContext（项目/会话/用户）
        ArgumentCaptor<List<String>> scopeCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ToolContext> ctxCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(subAgentService, times(1)).dispatch(any(), any(), scopeCaptor.capture(), ctxCaptor.capture());
        assertEquals(OpinionCompletionCheck.VERIFICATION_SCOPE, scopeCaptor.getValue(),
                "补检 scope 必须使用服务端固定只读清单，不能空（空=全工具）也不能含写工具");
        assertEquals(1L, ctxCaptor.getValue().projectId());
        assertEquals("conv-check", ctxCaptor.getValue().conversationId());
        assertEquals(7L, ctxCaptor.getValue().userId());

        // 续一轮：模型收到的末位消息是接回的核验发现（含子任务结论原文）
        assertEquals(2, model.calls.get(), "补检后必须接回主助手再跑一轮");
        String handoff = lastMessageText(model, 1);
        assertTrue(handoff.contains("收尾核验发现"), handoff);
        assertTrue(handoff.contains("未发现冲突"), "子任务发现原文要接回：" + handoff);
        assertTrue(handoff.contains("用户授权的编辑范围") || handoff.contains("授权"),
                "接回指令必须限定在用户授权编辑范围内：" + handoff);

        // 最终收尾：FINISHED；落库正文含收尾前正文 + 核验过程卡 + 核对后的最终说明（顺序一致）
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-check").status());
        assertEquals("{\"status\":\"finished\",\"documentEdited\":false}", bubbleEndData());
        String persisted = lastPersistedAssistant();
        int prematureIdx = persisted.indexOf("已按您的要求完成修订。");
        int cardIdx = persisted.indexOf("收尾核验");
        int finalIdx = persisted.indexOf("已核对收尾核验发现");
        assertTrue(prematureIdx >= 0 && cardIdx > prematureIdx && finalIdx > cardIdx,
                "历史顺序必须是 收尾前正文 → 核验过程卡 → 最终说明：" + persisted);
    }

    @Test
    @DisplayName("接回消息（生产注入原文，zh）：仅加批注不算修正、批注不替代正文限定、缺证不得反向否定")
    void handoffMessageDemandsBodyQualificationNotJustComments() {
        skillActive();
        when(subAgentService.dispatch(any(), any(), any(), any()))
                .thenReturn(SubAgentResult.success("sub-check",
                        "判定：待核实——“已获股东会批准”无决议材料支撑",
                        List.of("doc_get_document_text"), 1));
        ScriptModel model = run("conv-qualify",
                AiMessage.from("已按您的要求完成修订。"),
                AiMessage.from("已按发现限定正文表述，交付说明如下。"));

        String handoff = lastMessageText(model, 1);
        assertTrue(handoff.contains("收尾核验发现"), handoff);
        assertTrue(handoff.contains("仅新增批注而正文仍作确定结论不算修正"),
                "接回消息必须点破 dev-board#1107 的病灶——只加批注保留确定结论：" + handoff);
        assertTrue(handoff.contains("批注不替代正文限定"), handoff);
        assertTrue(handoff.contains("不得改写成否定结论"), "缺证不得凭空得否定结论：" + handoff);
        assertTrue(handoff.contains("限定正文表述本身"), handoff);
        assertTrue(handoff.contains("将整个命题标为待核实，或退回材料直接记载的最小事实"), handoff);
        assertTrue(handoff.contains("不能只删修饰词而保留无据关系"), handoff);
        assertTrue(handoff.contains("材料提及的主体不等于文书出具者或签署者"), handoff);
        assertTrue(handoff.contains("只有明确署名、签章或原句支持时才作该归属"), handoff);
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-qualify").status());
    }

    @Test
    @DisplayName("verificationTask（zh）：核验员须点名“正文确定断言 + 仅批注待核”形态")
    void verificationTaskFlagsCommentOnlyQualification() {
        String task = OpinionCompletionCheck.verificationTask(5L, "意见书",
                List.of("帮我修订这份法律意见书"));
        assertTrue(task.contains("仅以批注标注待核实"), task);
        assertTrue(task.contains("批注不替代正文限定"), task);
        assertTrue(task.contains("仅删修饰词后仍保留的无据关系"), task);
        assertTrue(task.contains("没有明确署名、签章或原句支持该归属"), task);
    }

    @Test
    @DisplayName("verificationTask（zh）：复合命题拆必要事实槽、逐槽直接证据、缺槽写「未找到」、当前稿不填证据槽")
    void verificationTaskRequiresPerSlotDirectEvidence() {
        String task = OpinionCompletionCheck.verificationTask(5L, "意见书",
                List.of("帮我修订这份法律意见书"));
        assertTrue(task.contains("必要事实槽"), task);
        assertTrue(task.contains("主体角色、法律关系、标的、条件、时点各自独立"), task);
        assertTrue(task.contains("来源文件 ID 或名称 + 定位 + 该来源的原文引文"), task);
        assertTrue(task.contains("未找到"), task);
        assertTrue(task.contains("不得把其他槽的有据合成为整句有据"), task);
        assertTrue(task.contains("任一必要槽未找到直接证据，该命题不得判「有依据」"), task);
        assertTrue(task.contains("当前稿和对话陈述只是待核命题，不能填入证据槽"), task);
        assertTrue(task.contains("直接有据的输入与可复算式，不能据此推断角色或标的"), task);
    }

    @Test
    @DisplayName("expectedOutput 走生产派发路径：逐槽引文 + 槽判定 + 缺槽不得判有依据")
    void expectedOutputSlotContractReachesTheSubtask() {
        skillActive();
        ArgumentCaptor<String> taskCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> expectedCaptor = ArgumentCaptor.forClass(String.class);
        run("conv-expected", AiMessage.from("已按您的要求完成修订。"),
                AiMessage.from("已核对收尾核验发现，交付说明如下。"));

        verify(subAgentService, times(1)).dispatch(taskCaptor.capture(), expectedCaptor.capture(), any(), any());
        assertTrue(taskCaptor.getValue().contains("必要事实槽"), taskCaptor.getValue());
        String expected = expectedCaptor.getValue();
        assertTrue(expected.contains("必要事实槽"), expected);
        assertTrue(expected.contains("该引文能证明的最小事实"), expected);
        assertTrue(expected.contains("槽判定（有依据/未找到/不适用）"), expected);
        assertTrue(expected.contains("任一必要槽未找到直接证据时整句不得判「有依据」"), expected);
        assertTrue(expected.contains("未发现冲突"), expected);
    }

    @Test
    @DisplayName("接回消息（zh）：待核命题逐条明确处置而非只在总结提及；缺逐槽引文的发现按未验证回读")
    void handoffMessageDemandsItemByItemHandlingOfUnverifiedPropositions() {
        skillActive();
        ScriptModel model = run("conv-slot-handoff",
                AiMessage.from("已按您的要求完成修订。"),
                AiMessage.from("已逐条处理待核实命题，交付说明如下。"));

        String handoff = lastMessageText(model, 1);
        assertTrue(handoff.contains("逐条处置每项发现"), handoff);
        assertTrue(handoff.contains("授权编辑范围内必须限定正文或改为明确待核实表述"), handoff);
        assertTrue(handoff.contains("不能只在总结里笼统带过"), handoff);
        assertTrue(handoff.contains("缺逐槽引文证据的"), handoff);
        assertTrue(handoff.contains("按未验证处理，自行回读原文核对"), handoff);
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-slot-handoff").status());
    }

    @Test
    @DisplayName("英文界面：核验任务与接回消息同样携带正文限定规则（多语边界）")
    void englishHandoffAndTaskCarryBodyQualificationRule() {
        com.checkba.service.AppLanguageService en = mock(com.checkba.service.AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        try {
            com.checkba.service.LangText.register(en);
            String task = OpinionCompletionCheck.verificationTask(5L, "Opinion", List.of("revise this opinion"));
            assertTrue(task.contains("a comment does not substitute for qualifying the body text"), task);
            assertTrue(task.contains("unsupported relationships left intact after merely deleting modifiers"), task);
            assertTrue(task.contains("without an explicit attribution, signature or seal"), task);
            String handoff = OpinionCompletionCheck.handoffMessage(
                    SubAgentResult.success("sub", "findings", List.of("doc_get_document_text"), 1));
            assertTrue(handoff.contains("a comment does not substitute for qualifying the body"), handoff);
            assertTrue(handoff.contains("not be turned into a negative conclusion"), handoff);
            assertTrue(handoff.contains("qualify the body text itself"), handoff);
            assertTrue(handoff.contains("proposition unverified or fall back to the minimal facts directly stated"), handoff);
            assertTrue(handoff.contains("must not leave the unsupported relationship intact"), handoff);
            assertTrue(handoff.contains("is not necessarily its issuer or signatory"), handoff);
            assertTrue(handoff.contains("explicit attribution, signature or seal supports it"), handoff);
            // 事实槽契约（dev-board#1107 收尾）：任务拆槽取证、产出按槽判定、接回逐条处置
            assertTrue(task.contains("required fact slots"), task);
            assertTrue(task.contains("directly evidenced inputs and a reproducible calculation"), task);
            assertTrue(task.contains("must not be composed into support for the whole sentence"), task);
            assertTrue(task.contains("can never fill an evidence slot"), task);
            String expected = OpinionCompletionCheck.expectedOutput();
            assertTrue(expected.contains("required fact slot"), expected);
            assertTrue(expected.contains("slot verdict (supported / not found / not applicable)"), expected);
            assertTrue(expected.contains("must not be judged \"supported\""), expected);
            assertTrue(handoff.contains("Handle each finding individually"), handoff);
            assertTrue(handoff.contains("not merely mentioned in a passing summary"), handoff);
            assertTrue(handoff.contains("without per-slot quote evidence counts as unverified"), handoff);
        } finally {
            com.checkba.service.LangText.reset();
        }
    }

    @Test
    @DisplayName("补检子任务失败：如实接回「未能完成」，绝不重复派发，仍 FINISHED")
    void failedCompletionCheckHandsBackUnverifiedAndNeverRepeats() {
        skillActive();
        when(subAgentService.dispatch(any(), any(), any(), any()))
                .thenReturn(SubAgentResult.failure("sub-check", "子 Agent 模型不可用", List.of(), 0));
        ScriptModel model = run("conv-check-fail",
                AiMessage.from("已按您的要求完成修订。"),
                AiMessage.from("我自行回读了当前稿并逐项核对，交付说明如下（核验子任务未能完成）。"));

        verify(subAgentService, times(1)).dispatch(any(), any(), any(), any());
        assertEquals(2, model.calls.get());
        String handoff = lastMessageText(model, 1);
        assertTrue(handoff.contains("未能完成"), handoff);
        assertTrue(handoff.contains("不会再自动重派"), "必须明说不会重派，且要求主助手自行核对或如实标注：" + handoff);
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-check-fail").status());
    }

    @Test
    @DisplayName("补检期间用户点停止：按取消收尾，发现不再接回，不再续轮")
    void cancelDuringCompletionCheckFinishesAsCancelled() {
        skillActive();
        when(subAgentService.dispatch(any(), any(), any(), any())).thenAnswer(inv -> {
            orchestrator.setCancelled("conv-check-cancel");
            return SubAgentResult.success("sub-check", "未发现冲突", List.of("doc_get_document_text"), 1);
        });
        ScriptModel model = run("conv-check-cancel", AiMessage.from("已按您的要求完成修订。"),
                AiMessage.from("不应该被用到：取消后不能续轮"));

        verify(subAgentService, times(1)).dispatch(any(), any(), any(), any());
        assertEquals(1, model.calls.get(), "取消后不能再发起新一轮 LLM 调用");
        assertEquals(AgentRunStateService.RunStatus.CANCELLED, runState.get("conv-check-cancel").status());
        assertTrue(sseEvents.contains("cancelled"), "要给前端明确的取消事件：" + sseEvents);
    }

    // ==================== 负例：不该补检的路径一律不补 ====================

    @Test
    @DisplayName("未命中 legal-opinion-review（普通对话/错字级窄改）：不补检，直接 FINISHED")
    void noCheckWhenSkillNotActive() {
        ScriptModel model = run("conv-no-skill", AiMessage.from("已改好那个错字。"));

        verify(subAgentService, never()).dispatch(any(), any(), any(), any());
        assertEquals(1, model.calls.get());
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-no-skill").status());
    }

    @Test
    @DisplayName("ASK 模式（只读问答）：即使命中 skill 也不补检、不续轮")
    void noCheckInAskMode() {
        skillActive();
        ScriptModel model = run("conv-ask", "ASK", AiMessage.from("这份意见书的结论部分依据如下……"));

        verify(subAgentService, never()).dispatch(any(), any(), any(), any());
        assertEquals(1, model.calls.get());
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-ask").status());
    }

    @Test
    @DisplayName("模型自己派过成功的只读子任务（摘录/翻译等）：不能冒充收尾核验，服务端仍补一次")
    void anySuccessfulReadOnlyDispatchDoesNotSkipServerCheck() {
        skillActive();
        AiMessage dispatch = AiMessage.from(List.of(ToolExecutionRequest.builder().id("1")
                .name("dispatch_subtask")
                .arguments("{\"task_description\":\"把第 3 章摘录成要点\",\"tool_scope\":\"doc_get_document_text\"}")
                .build()));
        when(toolRegistry.execute(eq("dispatch_subtask"), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult(
                        "{\"subtaskId\":\"sub-1\",\"success\":true,\"result\":\"要点如下\"}", null, true));

        ScriptModel model = run("conv-self-check", dispatch, AiMessage.from("摘录完成，交付说明如下。"));

        verify(subAgentService, times(1)).dispatch(any(), any(), any(), any());
        assertEquals(3, model.calls.get(), "工具轮 → 收尾补检 → 接回续轮，共三轮");
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-self-check").status());
    }

    // ==================== 判定函数与 scope 清单的边界 ====================

    @Test
    @DisplayName("VERIFICATION_SCOPE：非空、全只读、无再委派/反问")
    void verificationScopeIsReadOnly() {
        assertFalse(OpinionCompletionCheck.VERIFICATION_SCOPE.isEmpty());
        for (String tool : OpinionCompletionCheck.VERIFICATION_SCOPE) {
            assertFalse(ClientCapabilityService.isDocumentWritingTool(tool),
                    "核验 scope 不许含写工具：" + tool);
            assertFalse("dispatch_subtask".equals(tool), "核验 scope 不许含再委派");
            assertFalse("ask_user".equals(tool), "核验 scope 不许含反问（子任务无人可问）");
        }
    }

    @Test
    @DisplayName("手动选了 skill 但指令只限错字级窄改：复用 requestsOpinionReview 门控，不付费补检")
    void manualSkillWithNarrowEditInstructionDoesNotPayForCheck() {
        skillActive();
        ScriptModel model = runWithMessage("conv-narrow",
                "只把第三段中的“ seperate ”改成 separate",
                AiMessage.from("已按指示完成替换。"));

        verify(subAgentService, never()).dispatch(any(), any(), any(), any());
        assertEquals(1, model.calls.get());
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-narrow").status());
    }

    @Test
    @DisplayName("verificationTask：目标绑本轮最初文档，原指令与后续插话按顺序作范围数据")
    void verificationTaskBindsInitialTargetAndOrderedInstructions() {
        String task = OpinionCompletionCheck.verificationTask(5L, "法律意见书（终稿）",
                List.of("帮我全面修订这份法律意见书", "继续", "只改第 2 项"));
        assertTrue(task.contains("fileId=5"), task);
        assertTrue(task.contains("法律意见书（终稿）"), task);
        int original = task.indexOf("帮我全面修订这份法律意见书");
        int cont = task.indexOf("继续");
        int second = task.indexOf("只改第 2 项");
        assertTrue(original >= 0 && cont > original && second > cont,
                "原指令在前、插话按顺序：" + task);
        assertTrue(task.contains("后面的收窄不得被前面的放宽覆盖"), task);
        assertTrue(task.contains("确认编辑器当前打开的正是目标文档"), task);

        String noTarget = OpinionCompletionCheck.verificationTask(null, null, List.of("审查一下"));
        assertTrue(noTarget.contains("没有绑定目标文档"), noTarget);
    }

    @Test
    @DisplayName("本轮被新 run 取代后收尾：保守跳过补检，不接回、不自动编辑")
    void supersededRunSkipsPaidCheck() {
        skillActive();
        ScriptModel model = new ScriptModel(List.of(AiMessage.from("修订完成")));
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);
        model.beforeFirstCompletion = () -> startReplacementRun("conv-supersede");
        startOpinionRun("conv-supersede");
        assertEquals(2, model.calls.get(), "旧轮确实生成过，新轮在其收尾前完成");
        verify(subAgentService, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void supersededDuringCheckDoesNotContinueOldRun() {
        skillActive();
        when(subAgentService.dispatch(any(), any(), any(), any())).thenAnswer(inv -> {
            startReplacementRun("conv-during");
            return SubAgentResult.success("sub", "待核对发现", List.of("doc_get_document_text"), 1);
        });
        ScriptModel model = run("conv-during", AiMessage.from("修订完成"));
        verify(subAgentService, times(1)).dispatch(any(), any(), any(), any());
        assertEquals(2, model.calls.get(), "只有旧轮和新轮各一次，不接回旧轮自动续改");
    }

    @Test
    void changingToReferenceFileHandsBackUnverifiedWithoutReadingWrongTarget() {
        skillActive();
        AiMessage open = AiMessage.from(List.of(ToolExecutionRequest.builder().id("open")
                .name("doc_open_file").arguments("{\"fileId\":9}").build()));
        ScriptModel model = run("conv-other", open, AiMessage.from("完成"));
        verify(subAgentService, never()).dispatch(any(), any(), any(), any());
        assertEquals(3, model.calls.get());
        assertTrue(lastMessageText(model, 2).contains("无法确认当前编辑器"));
    }

    @Test
    void questionWaitsForUserWithoutCompletionCheck() {
        skillActive();
        ScriptModel model = run("conv-question", AiMessage.from("<question>请确认审查范围</question>"));
        verify(subAgentService, never()).dispatch(any(), any(), any(), any());
        assertEquals(1, model.calls.get());
        assertEquals(AgentRunStateService.RunStatus.AWAITING_INPUT, runState.get("conv-question").status());
    }

    @Test
    void longInstructionRetainsItsFinalScopeRestriction() {
        String instruction = "审查意见书。" + "资料说明".repeat(600) + "只改第七段，禁止修改其他正文";
        assertTrue(OpinionCompletionCheck.verificationTask(5L, "意见书", List.of(instruction))
                .contains("只改第七段，禁止修改其他正文"));
    }

    @Test
    void editsBeforeAndAfterCheckDoNotRepeatIt() {
        skillActive();
        AiMessage edit = AiMessage.from(List.of(ToolExecutionRequest.builder().id("edit")
                .name("doc_replace_text").arguments("{}").build()));
        AiMessage read = AiMessage.from(List.of(ToolExecutionRequest.builder().id("read")
                .name("doc_get_document_text").arguments("{}").build()));
        ScriptModel model = run("conv-edit-check", edit, AiMessage.from("初次修订完成"),
                edit, read, AiMessage.from("已根据发现修正并回读"));
        verify(subAgentService, times(1)).dispatch(any(), any(), any(), any());
        verify(toolRegistry, times(2)).execute(eq("doc_replace_text"), any(), any());
        verify(toolRegistry).execute(eq("doc_get_document_text"), any(), any());
        assertEquals(5, model.calls.get());
        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-edit-check").status());
    }

    private void startOpinionRun(String conversationId) {
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L); request.setConversationId(conversationId);
        request.setMessage("帮我修订一下这个法律意见书"); request.setModel(MODEL);
        AiAgentController.ContextItem active = new AiAgentController.ContextItem();
        active.setId("5"); active.setFileType("docx"); request.setActiveContext(active);
        orchestrator.handleUserMessage(request, 7L);
    }

    private void startReplacementRun(String conversationId) {
        AiAgentController.AgentChatRequest next = new AiAgentController.AgentChatRequest();
        next.setProjectId(1L); next.setConversationId(conversationId);
        next.setMessage("只改一个错字"); next.setModel(MODEL);
        orchestrator.handleUserMessage(next, 7L);
    }
}
