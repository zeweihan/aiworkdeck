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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * XML 兜底路径（{@code <tool_code>}）回喂给模型的「工具执行日志」不许谎报成功。
 *
 * <p>病灶：那条 feedback 文案里 “The tool executed successfully.” 是**无条件**拼进去的，
 * 与同一条消息里的 {@code Status: FAILURE} 直接打架，紧跟着还有一句
 * “output {@code <final>} IMMEDIATELY”。XML 兜底是弱模型的主路径，而本仓的既有实证
 * （PR#209）是「末位/最强指令赢」——于是工具失败时模型被引导去宣布任务完成，
 * 用户看到的就是「AI 说做完了，其实什么都没发生」。
 *
 * <p>原生 function-calling 分支没有这个问题：它回喂的是纯 ToolExecutionResultMessage，
 * 不附带任何成功断言。两条路径的语义必须一致。
 */
class AgentOrchestratorXmlToolFeedbackTest {

    private static final String MODEL = "qwen/qwen3.7-flash";

    private ChatModelFactory chatModelFactory;
    private ToolRegistry toolRegistry;
    private XmlToolCallParser parser;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    /** 按脚本逐轮吐内容的模型，同时留下最后一轮收到的完整消息栈 */
    private static final class ScriptModel implements StreamingChatLanguageModel {
        private final List<AiMessage> script;
        final AtomicInteger calls = new AtomicInteger();
        volatile List<ChatMessage> lastMessages = List.of();

        ScriptModel(List<AiMessage> script) {
            this.script = script;
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            lastMessages = new ArrayList<>(messages);
            int idx = calls.getAndIncrement();
            AiMessage msg = script.get(Math.min(idx, script.size() - 1));
            if (msg.text() != null && !msg.text().isEmpty()) {
                handler.onNext(msg.text());
            }
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
                        SystemMessage.from("system"), UserMessage.from("读一下这份合同"))));

        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());

        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(java.util.Optional.empty());

        // XML 兜底路径：解析器交出一次 read_document 调用
        parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(anyString()))
                .thenAnswer(inv -> String.valueOf((Object) inv.getArgument(0)).contains("<tool_code>"));
        when(parser.extractProcessName(anyString())).thenReturn(java.util.Optional.of("读取文档"));
        when(parser.parse(anyString())).thenAnswer(inv ->
                String.valueOf((Object) inv.getArgument(0)).contains("<tool_code>")
                        ? List.of(new XmlToolCallParser.ParsedCall(
                                "read_document", "{\"fileId\":\"12\"}", "read_document(fileId=\"12\")"))
                        : List.of());

        TodoListService todoListService = mock(TodoListService.class);
        AiContextProperties contextProperties = new AiContextProperties();
        AgentRunStateService runState = new AgentRunStateService(
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                toolRegistry, skillRouter, parser, mock(MemoryPipelineService.class),
                mock(ProjectFileService.class), mock(EditorBridgeService.class),
                mock(ConversationFileChangeService.class), todoListService,
                mock(DocumentCheckpointService.class), runState,
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new com.checkba.service.ai.OfficePassStateStore());
    }

    private ScriptModel run(String conversationId, AiMessage... script) {
        ScriptModel model = new ScriptModel(List.of(script));
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("读一下这份合同");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);
        return model;
    }

    /** 取回喂给模型的那条「工具执行日志」用户消息 */
    private String toolFeedback(ScriptModel model) {
        for (ChatMessage m : model.lastMessages) {
            if (m instanceof UserMessage um) {
                String text = um.singleText();
                if (text != null && text.contains("[System Tool Execution Log]")) {
                    return text;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("XML 兜底：工具失败时回喂文案不许断言成功、不许催 <final>")
    void failedXmlToolMustNotBeReportedAsSuccess() {
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("Error: 文件不存在（fileId=12）", null, true));

        ScriptModel model = run("conv-xml-fail",
                AiMessage.from("<process name=\"读取文档\"><tool_code>read_document(fileId=\"12\")</tool_code></process>"),
                AiMessage.from("<final>没找到这份文件。</final>"));

        String feedback = toolFeedback(model);
        assertNotNull(feedback, "工具反馈必须回喂模型，实际消息栈：" + model.lastMessages);
        assertTrue(feedback.contains("Status: FAILURE"),
                "失败的工具必须标 FAILURE，实际是：" + feedback);
        assertFalse(feedback.contains("The tool executed successfully"),
                "工具失败时不许告诉模型它成功了——这正是「AI 说做完了其实没做」的成因，实际是：" + feedback);
        assertFalse(feedback.contains("output `<final>` IMMEDIATELY"),
                "工具失败时不许催模型立刻收尾，应让它纠错或如实告知用户，实际是：" + feedback);
    }

    @Test
    @DisplayName("XML 兜底：工具成功时保留原有的收敛指令，不改变既有行为")
    void successfulXmlToolKeepsTheConvergenceInstruction() {
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("合同正文……", null, true));

        ScriptModel model = run("conv-xml-ok",
                AiMessage.from("<process name=\"读取文档\"><tool_code>read_document(fileId=\"12\")</tool_code></process>"),
                AiMessage.from("<final>读好了。</final>"));

        String feedback = toolFeedback(model);
        assertNotNull(feedback, "工具反馈必须回喂模型，实际消息栈：" + model.lastMessages);
        assertTrue(feedback.contains("Status: SUCCESS"), "成功的工具标 SUCCESS，实际是：" + feedback);
        assertTrue(feedback.contains("The tool executed successfully"),
                "成功路径的既有文案保持不变（防过度改动），实际是：" + feedback);
    }

    @Test
    @DisplayName("XML 兜底：工具返回空白按失败处理，与原生分支同口径")
    void blankXmlToolOutputIsTreatedAsFailure() {
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("   ", null, true));

        ScriptModel model = run("conv-xml-blank",
                AiMessage.from("<process name=\"读取文档\"><tool_code>read_document(fileId=\"12\")</tool_code></process>"),
                AiMessage.from("<final>读不出来。</final>"));

        String feedback = toolFeedback(model);
        assertNotNull(feedback, "工具反馈必须回喂模型，实际消息栈：" + model.lastMessages);
        assertTrue(feedback.contains("Status: FAILURE"),
                "空白输出与原生分支同口径按 FAILURE 处理，实际是：" + feedback);
        assertFalse(feedback.contains("The tool executed successfully"),
                "空白输出不许被断言为成功，实际是：" + feedback);
    }
}
