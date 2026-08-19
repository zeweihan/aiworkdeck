package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiFailoverProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.RunLoopCompactor;
import com.checkba.service.ai.memory.MemoryPipelineService;
import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRouter;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSE {@code skill_update}：本轮生效的 skill 清单必须推给前端，用户才看得见
 * "这轮加载了哪些技能"（自动命中的那枚在面板里会闪一下）。
 *
 * <p>守三件事：
 * ① 每轮必发、空也发——漏发一次上一轮的 chip 就会一直挂着，用户以为它还生效着；
 * ② 请求里的 skillIds 原样传给 SkillRouter（手动选择是无状态的，后端不持久化）；
 * ③ ASK 模式下下发空列表且不带手动选择——该模式不传工具也不注入 skill 指引，
 *    面板亮着而实际没生效正是本次要消灭的"显示与实际不一致"。
 */
class AgentSkillUpdateEventTest {

    private static final String MODEL = "qwen/qwen3.7-flash";

    private ChatModelFactory chatModelFactory;
    private SkillRouter skillRouter;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    /** 一轮就收尾的假模型 */
    private static final class OneShotModel implements StreamingChatLanguageModel {
        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            AiMessage msg = AiMessage.from("<final>好的。</final>");
            handler.onNext(msg.text());
            handler.onComplete(Response.from(msg));
        }

        @Override
        public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                             StreamingResponseHandler<AiMessage> handler) {
            generate(messages, handler);
        }
    }

    private static SkillDefinition skill(String id, String name) {
        SkillDefinition def = new SkillDefinition();
        def.setId(id);
        def.setName(name);
        return def;
    }

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(new OneShotModel());

        sseEvents = new CopyOnWriteArrayList<>();
        sseData = new CopyOnWriteArrayList<>();
        SseEmitterService sse = mock(SseEmitterService.class);
        doAnswer(inv -> {
            sseEvents.add(inv.getArgument(1));
            sseData.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("帮我出一张诉讼时间轴"))));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());

        skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(java.util.Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        AiContextProperties contextProperties = new AiContextProperties();
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), mock(TodoListService.class),
                mock(DocumentCheckpointService.class),
                new AgentRunStateService(
                        mock(com.checkba.repository.AgentRunRecordRepository.class),
                        mock(com.checkba.service.telemetry.TelemetryTurnTracker.class)),
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class));
    }

    private void run(String conversationId, AgentMode mode, List<String> skillIds) {
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("帮我出一张诉讼时间轴");
        request.setModel(MODEL);
        request.setMode(mode.name());
        request.setSkillIds(skillIds);
        orchestrator.handleUserMessage(request, 7L);
    }

    private String skillUpdateData() {
        for (int i = 0; i < sseEvents.size(); i++) {
            if ("skill_update".equals(sseEvents.get(i))) return sseData.get(i);
        }
        return null;
    }

    @Test
    @DisplayName("生效的 skill 随 skill_update 下发：id / name / source 三个字段是跨端契约")
    void emitsActiveSkillsWithSource() {
        when(skillRouter.activeSkills("conv-skill")).thenReturn(List.of(
                new SkillRouter.ActiveSkill(skill("litigation-visual", "诉讼可视化"),
                        "诉讼可视化", SkillRouter.SOURCE_MANUAL),
                new SkillRouter.ActiveSkill(skill("listing-pathway", "上市路径选择"),
                        "上市路径选择", SkillRouter.SOURCE_AUTO)));

        run("conv-skill", AgentMode.AGENT, List.of("litigation-visual"));

        String data = skillUpdateData();
        assertNotNull(data, "每轮都必须发 skill_update：" + sseEvents);
        assertTrue(data.contains("\"id\":\"litigation-visual\""), data);
        assertTrue(data.contains("\"source\":\"manual\""), data);
        assertTrue(data.contains("\"name\":\"上市路径选择\""), data);
        assertTrue(data.contains("\"source\":\"auto\""), data);
    }

    @Test
    @DisplayName("skillIds 原样传给 SkillRouter（手动选择无状态，每次请求携带）")
    void forwardsSkillIdsToRouter() {
        run("conv-fwd", AgentMode.AGENT, List.of("a", "b"));

        verify(skillRouter).activateForTurn(eq("conv-fwd"), eq("帮我出一张诉讼时间轴"),
                isNull(), eq(List.of("a", "b")));
    }

    @Test
    @DisplayName("一个都不生效时也发空列表——不发的话上一轮的 chip 会一直挂着")
    void emitsEmptyListWhenNothingActive() {
        run("conv-none", AgentMode.AGENT, null);

        assertEquals("{\"skills\":[]}", skillUpdateData());
    }

    @Test
    @DisplayName("ASK 模式：手动选择不参与，skill_update 下发空列表")
    void askModeDropsManualSelection() {
        when(skillRouter.activeSkills("conv-ask")).thenReturn(List.of(
                new SkillRouter.ActiveSkill(skill("litigation-visual", "诉讼可视化"),
                        "诉讼可视化", SkillRouter.SOURCE_MANUAL)));

        run("conv-ask", AgentMode.ASK, List.of("litigation-visual"));

        verify(skillRouter).activateForTurn(eq("conv-ask"), eq("帮我出一张诉讼时间轴"), isNull(), isNull());
        assertEquals("{\"skills\":[]}", skillUpdateData(),
                "ASK 下 skill 本来就不生效，面板不该亮着 chip");
    }
}
