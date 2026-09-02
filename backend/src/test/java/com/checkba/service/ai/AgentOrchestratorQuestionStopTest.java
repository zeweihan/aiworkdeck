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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 反问停机（{@code <question>} → AWAITING_INPUT）与慢工具执行期取消。
 *
 * <p>守的是三件容易静默退化的事：
 * ① 模型问了问题就必须停，不能递归下一轮自己接着猜（那是 {@code <question>} 存在的全部理由）；
 * ② 停机必须打 AWAITING_INPUT 状态点 + 发 {@code bubble_end {"status":"awaiting_input"}}
 *    ——漏 mark 则 ai.turn 轮次永不闭合、会话列表看不到「待回答」（PR#173 状态机契约）；
 * ③ 「停止」按钮在慢工具（dispatch_subtask 630 秒 / AI PPT 十几分钟）中间要真的生效。
 */
class AgentOrchestratorQuestionStopTest {

    private static final String MODEL = "qwen/qwen3.7-flash";

    private ChatModelFactory chatModelFactory;
    private SseEmitterService sse;
    private AgentRunStateService runState;
    private ProjectAiMessageService messageService;
    private ToolRegistry toolRegistry;
    private List<String> sseEvents;
    private List<String> sseData;
    private AgentOrchestrator orchestrator;

    /** 按脚本逐轮吐内容的模型，同时数被调了几轮（递归发生就 >1） */
    private static final class ScriptModel implements StreamingChatLanguageModel {
        private final List<AiMessage> script;
        final AtomicInteger calls = new AtomicInteger();

        ScriptModel(List<AiMessage> script) {
            this.script = script;
        }

        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
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
        sse = mock(SseEmitterService.class);
        doAnswer(inv -> {
            sseEvents.add(inv.getArgument(1));
            sseData.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<ChatMessage>(List.of(
                        SystemMessage.from("system"), UserMessage.from("帮我起草一份股权转让协议"))));

        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(java.util.Optional.empty());
        when(toolRegistry.execute(any(), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("ok", null, true));

        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(java.util.Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

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
                mock(com.checkba.service.telemetry.MatterClassifierService.class));
    }

    private ScriptModel run(String conversationId, AiMessage... script) {
        ScriptModel model = new ScriptModel(List.of(script));
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(model);
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId(conversationId);
        request.setMessage("帮我起草一份股权转让协议");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);
        return model;
    }

    private String bubbleEndData() {
        for (int i = 0; i < sseEvents.size(); i++) {
            if ("bubble_end".equals(sseEvents.get(i))) return sseData.get(i);
        }
        return null;
    }

    @Test
    @DisplayName("带选项的反问：停机等回答，不递归；状态 AWAITING_INPUT，bubble_end=awaiting_input")
    void stopsAndMarksAwaitingInputOnQuestion() {
        ScriptModel model = run("conv-q",
                AiMessage.from("<question>受让方是自然人还是公司？"
                        + "<option>自然人</option><option>公司</option></question>"),
                AiMessage.from("不应该被用到：反问之后必须停机"));

        assertEquals(1, model.calls.get(), "反问必须停机，不能递归下一轮让模型自己接着猜");
        assertEquals(AgentRunStateService.RunStatus.AWAITING_INPUT, runState.get("conv-q").status());
        assertEquals("{\"status\":\"awaiting_input\"}", bubbleEndData(),
                "status 字面量是跨端契约（前端两处解析 + Office 插件 stillRunning）");
        assertFalse(sseEvents.contains("error"), "反问不是错误");
        // 问题正文必须落库：用户关掉 app 明天回来还要看得见问题与选项
        verify(messageService).upsertAssistantMessage(any(), any(), any(), any(), contains("<question>"));
    }

    @Test
    @DisplayName("反问缺闭合标签也停机：问句已经流给用户看了，当正常收尾会留下没有下文的问题")
    void stopsOnUnclosedQuestionTag() {
        ScriptModel model = run("conv-q-open", AiMessage.from("<question>案号是多少？"));

        assertEquals(1, model.calls.get());
        assertEquals(AgentRunStateService.RunStatus.AWAITING_INPUT, runState.get("conv-q-open").status());
    }

    @Test
    @DisplayName("同一轮既调工具又反问：工具照常跑完，但不递归——问题优先于继续执行")
    void stopsAfterToolsWhenSameTurnAlsoAsks() {
        AiMessage withTool = AiMessage.from(
                "<question>这份协议的受让方是自然人还是公司？</question>",
                List.of(ToolExecutionRequest.builder().id("1").name("list_files").arguments("{}").build()));

        ScriptModel model = run("conv-q-tool", withTool, AiMessage.from("不应该被用到"));

        verify(toolRegistry).execute(any(), any(), any());
        assertEquals(1, model.calls.get(), "工具结果不该带着未决问题递归下一轮");
        assertEquals(AgentRunStateService.RunStatus.AWAITING_INPUT, runState.get("conv-q-tool").status());
        assertEquals("{\"status\":\"awaiting_input\"}", bubbleEndData());
    }

    @Test
    @DisplayName("没有反问时行为不变：正常收尾 FINISHED")
    void normalTurnStillFinishes() {
        run("conv-plain", AiMessage.from("<final>好的，已经改好了。</final>"));

        assertEquals(AgentRunStateService.RunStatus.FINISHED, runState.get("conv-plain").status());
        assertEquals("{\"status\":\"finished\"}", bubbleEndData());
    }

    @Test
    @DisplayName("慢工具执行期点停止：本轮剩下的工具全部不再执行")
    void cancelDuringToolLoopSkipsRemainingTools() {
        // 第一个工具执行途中用户点了「停止」（真实场景是它跑了 630 秒）
        when(toolRegistry.execute(any(), any(), any())).thenAnswer(inv -> {
            orchestrator.setCancelled("conv-cancel");
            return new ToolRegistry.ToolResult("subtask done", null, true);
        });

        AiMessage twoTools = AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("1").name("dispatch_subtask").arguments("{}").build(),
                ToolExecutionRequest.builder().id("2").name("write_docx").arguments("{}").build()));

        run("conv-cancel", twoTools, AiMessage.from("不应该被用到"));

        verify(toolRegistry, times(1)).execute(any(), any(), any());
        assertTrue(sseEvents.contains("cancelled"), "要给前端明确的取消事件：" + sseEvents);
        assertEquals(AgentRunStateService.RunStatus.CANCELLED, runState.get("conv-cancel").status());
    }

    @Test
    @DisplayName("连着两轮都以反问收尾不被守卫误伤：答了一个又被问下一个是合理的")
    void consecutiveQuestionTurnsAreNotTreatedAsLooping() {
        // 每次 handleUserMessage 新建 RunGuard（StuckDetector 窗口/步数预算都在里面），
        // 用户的回答是新一轮消息即新的 run。这条测试守的是「别把 RunGuard 改成跨轮复用」。
        run("conv-q-twice", AiMessage.from("<question>您代表哪一方？</question>"));
        assertEquals(AgentRunStateService.RunStatus.AWAITING_INPUT, runState.get("conv-q-twice").status());

        sseEvents.clear();
        sseData.clear();
        run("conv-q-twice", AiMessage.from("<question>那违约金上限按合同额的多少算？</question>"));

        assertEquals(AgentRunStateService.RunStatus.AWAITING_INPUT, runState.get("conv-q-twice").status(),
                "第二次反问同样应停机等回答，不该被当成打转熔断或报错");
        assertTrue(sseEvents.contains("bubble_end"), "第二轮也要正常收尾：" + sseEvents);
        assertFalse(sseEvents.contains("error"));
    }

    @Test
    @DisplayName("displayText 落 displayContent：模型拿全文细节，用户气泡只看到那句人话")
    void persistsDisplayTextAlongsideModelContent() {
        when(chatModelFactory.getStreamingChatModel(MODEL))
                .thenReturn(new ScriptModel(List.of(AiMessage.from("<final>好的。</final>"))));
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId("conv-display");
        request.setMessage("我已修订计划（共 3 处改动）：\n[修订版全文]");
        request.setDisplayText("已修订计划");
        request.setModel(MODEL);
        orchestrator.handleUserMessage(request, 7L);

        // content 给模型（含细节），displayContent 给用户；缺省时第 6 个参数为 null（存量行为）
        verify(messageService).saveMessage(any(), any(), any(), any(),
                contains("修订版全文"), contains("已修订计划"));
    }

    @Test
    @DisplayName("containsQuestion：认属性与跨行写法，不把 <questionnaire> 之类的词误判成反问")
    void questionDetectionShape() {
        assertTrue(AgentOrchestrator.containsQuestion("<question>案号？</question>"));
        assertTrue(AgentOrchestrator.containsQuestion("<question type=\"choice\">A 还是 B？</question>"));
        assertTrue(AgentOrchestrator.containsQuestion("前言\n<question>\n案号？\n</question>"));
        assertFalse(AgentOrchestrator.containsQuestion("<questionnaire>问卷</questionnaire>"),
                "前缀相同的其它标签不能触发停机");
        assertFalse(AgentOrchestrator.containsQuestion("我有一个 question 想问你"));
        assertFalse(AgentOrchestrator.containsQuestion(null));
    }

    @Test
    @DisplayName("结果型工具的面板展示上限放宽到 16000，其余仍是 4000")
    void resultHeavyToolsGetLargerDisplayBudget() {
        assertEquals(16000, AgentOrchestrator.toolOutputDisplayLimit("dispatch_subtask"),
                "子任务结果是 JSON，截断后前端结构化卡片直接解析失败");
        assertEquals(16000, AgentOrchestrator.toolOutputDisplayLimit("extract_file_text"));
        assertEquals(16000, AgentOrchestrator.toolOutputDisplayLimit("pdf_inspect"));
        assertEquals(16000, AgentOrchestrator.toolOutputDisplayLimit("doc_audit_structure"),
                "结构审计报告是给用户核对的成果，面板不能只显示前 4000 字");
        assertEquals(4000, AgentOrchestrator.toolOutputDisplayLimit("doc_replace_text"));
        assertEquals(4000, AgentOrchestrator.toolOutputDisplayLimit(null));
    }
}
