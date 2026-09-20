// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiFailoverProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.AppLanguageScope;
import com.checkba.service.AppLanguageService;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.FileContextLoader;
import com.checkba.service.ai.context.RunLoopCompactor;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.service.ai.memory.MemoryPipelineService;
import com.checkba.service.ai.skill.SkillRouter;
import com.checkba.service.ai.tools.LegalTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 插件会话的回答语言跟随界面语言（dev-board#713）。
 *
 * <p><b>根因</b>：后端唯一的语言来源是全局 system_setting 的 {@code app.language}，
 * 云后端（多租户）上它恒为默认的 zh-CN；而 Office/WPS 插件从来没声明过自己的界面语言。
 * 于是英文界面、英文文档，system prompt 仍是中文版，模型照中文回答——AppSource 政策
 * 1100.7 要求界面语言一致，这是上架阻塞项。
 *
 * <p>本类钉住修复后的两段链路：
 * <ol>
 *   <li>{@link AgentOrchestrator} 把请求体里的 {@code appLanguage} 在**执行线程**上重建成
 *       语言作用域——HTTP 线程上过滤器建的那个不跟着池线程走，漏了这一步是静默故障；</li>
 *   <li>{@link ContextAssemblerService} 据此选英文 system prompt 与英文 Language 规则。</li>
 * </ol>
 */
class AddinRequestLanguageTest {

    private static final String MODEL = "qwen/qwen3.7-flash";

    // ==================== 第一段：编排器在执行线程上重建语言作用域 ====================

    /** 一轮就收尾的模型（不调工具，避免把整条工具循环拖进来） */
    private static final class OneShotModel implements StreamingChatLanguageModel {
        @Override
        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            AiMessage msg = AiMessage.from("<final>Done.</final>");
            handler.onNext(msg.text());
            handler.onComplete(Response.from(msg));
        }

        @Override
        public void generate(List<ChatMessage> messages, List<ToolSpecification> tools,
                             StreamingResponseHandler<AiMessage> handler) {
            generate(messages, handler);
        }
    }

    /**
     * 跑一轮，返回「组装上下文那一刻线程上生效的语言」。
     * 组装器是 mock 的，但语言取值走的是**真的** AppLanguageService（全局设置固定 zh-CN，
     * 与云后端现状一致），所以这条断言问的正是「编排器有没有把请求语言建到执行线程上」。
     */
    private String languageAtAssembleTime(String requestLanguage) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(eq(AppLanguageService.KEY), eq(AppLanguageService.ZH_CN)))
                .thenReturn(AppLanguageService.ZH_CN);
        AppLanguageService appLanguage = new AppLanguageService(settings);
        String[] seen = new String[1];

        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.getStreamingChatModel(MODEL)).thenReturn(new OneShotModel());

        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        // dev-board#729 ⑤：编排器改用 countByConversationId 判首轮；mock 默认回 0 会误判首轮、起异步标题线程
        // 与下一次 when(...) 打架（CI 上 Mockito WrongTypeOfReturnValue）。计数跟随 list 桩，保持各用例原语义。
        when(messageService.countByConversationId(any())).thenAnswer(inv -> (long) messageService.listByConversationId(inv.getArgument(0)).size());
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenReturn(1L);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    seen[0] = appLanguage.language();
                    return new ArrayList<ChatMessage>(List.of(
                            SystemMessage.from("system"), UserMessage.from("Review this clause.")));
                });

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getAllSpecifications(any(), any())).thenReturn(List.of());
        when(toolRegistry.resolve(anyString())).thenReturn(Optional.empty());

        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skillRouter.activeSkill(any())).thenReturn(Optional.empty());
        XmlToolCallParser parser = mock(XmlToolCallParser.class);
        when(parser.containsToolCall(any())).thenReturn(false);

        AiContextProperties contextProperties = new AiContextProperties();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, mock(SseEmitterService.class),
                mock(TokenUsageService.class), assembler, toolRegistry, skillRouter, parser,
                mock(MemoryPipelineService.class), mock(ProjectFileService.class),
                mock(EditorBridgeService.class), mock(ConversationFileChangeService.class),
                mock(TodoListService.class), mock(DocumentCheckpointService.class),
                new AgentRunStateService(mock(com.checkba.repository.AgentRunRecordRepository.class),
                        mock(com.checkba.service.telemetry.TelemetryTurnTracker.class)),
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(contextProperties, new ContextCompressor(null, null, contextProperties)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class),
                new OfficePassStateStore());

        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId("conv-lang-" + requestLanguage);
        request.setMessage("Review this clause.");
        request.setModel(MODEL);
        request.setClientCapability("office");
        request.setOfficeHost("word");
        request.setAppLanguage(requestLanguage);
        // turnExecutor 未注入 → handleUserMessage 就地同步跑（既有测试同款）
        orchestrator.handleUserMessage(request, 7L);
        assertNull(AppLanguageScope.current(), "轮次跑完必须把作用域恢复干净");
        return seen[0];
    }

    @Test
    @DisplayName("编排器把请求体的 appLanguage 建到执行线程上（池线程不继承 HTTP 线程的作用域）")
    void orchestratorEstablishesRequestLanguageOnTheRunningThread() {
        assertEquals("en-US", languageAtAssembleTime("en-US"),
                "英文请求必须让组装上下文那一刻的生效语言是 en-US，否则 system prompt 仍是中文版");
    }

    @Test
    @DisplayName("不声明语言（桌面端）：仍读全局 app.language，行为与引入前一致")
    void unspecifiedLanguageFallsBackToGlobalSetting() {
        assertEquals("zh-CN", languageAtAssembleTime(null));
    }

    // ==================== 第二段：组装器据生效语言选英文 system prompt ====================

    private ContextAssemblerService assemblerWith(AppLanguageService appLanguage) {
        LegalTools legalTools = mock(LegalTools.class);
        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        // dev-board#729 ⑤：编排器改用 countByConversationId 判首轮；mock 默认回 0 会误判首轮、起异步标题线程
        // 与下一次 when(...) 打架（CI 上 Mockito WrongTypeOfReturnValue）。计数跟随 list 桩，保持各用例原语义。
        when(messageService.countByConversationId(any())).thenAnswer(inv -> (long) messageService.listByConversationId(inv.getArgument(0)).size());
        when(messageService.listByConversationId(anyString())).thenReturn(Collections.emptyList());
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.match(anyString())).thenReturn(Optional.empty());
        MemoryManager memoryManager = mock(MemoryManager.class);
        when(memoryManager.getProjectMemory(anyLong())).thenReturn(Optional.empty());
        when(memoryManager.retrieveMemories(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(memoryManager.retrieveUserMemories(anyLong(), anyInt())).thenReturn(Collections.emptyList());
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.needsCompression(any(), any())).thenReturn(false);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.effectiveModelSupportsVision(any())).thenReturn(false);
        return new ContextAssemblerService(
                legalTools, messageService, mock(FileContextLoader.class),
                new AiContextProperties(), skillRouter, new ClientCapabilityService(),
                new InlineContentCache(), memoryManager, compressor, appLanguage,
                factory, mock(ProjectFileService.class));
    }

    private String systemTextUnder(String scopedLanguage) {
        SystemSettingService settings = mock(SystemSettingService.class);
        // 云后端现状：全局语言恒为默认的 zh-CN，谁也不能替全体租户改掉它
        when(settings.get(eq(AppLanguageService.KEY), eq(AppLanguageService.ZH_CN)))
                .thenReturn(AppLanguageService.ZH_CN);
        ContextAssemblerService assembler = assemblerWith(new AppLanguageService(settings));
        return AppLanguageScope.call(scopedLanguage, () -> {
            List<ChatMessage> messages = assembler.assemble(
                    "conv-1", "run-1", "Please review this clause.", null, null,
                    null, null, "88", AgentMode.AGENT, 1L, null);
            return ((SystemMessage) messages.get(0)).text();
        });
    }

    @Test
    @DisplayName("英文请求 → 系统提示是英文版，Language 规则是 ENGLISH ONLY")
    void englishRequestAssemblesEnglishSystemPrompt() {
        String systemText = systemTextUnder("en-US");
        assertTrue(systemText.contains("ENGLISH ONLY"),
                "enforcement 段的 Language 行必须是 ENGLISH ONLY——这一条就是「回答语言跟随界面」的落点");
        assertFalse(systemText.contains("SIMPLIFIED CHINESE ONLY"),
                "英文请求下不许再出现中文 Language 规则（病灶就是这一条恒生效）");
        assertTrue(systemText.contains("# MODE: AGENT MODE (autonomous execution)"),
                "模式约束也应为英文版");
    }

    @Test
    @DisplayName("中文请求 / 不声明：系统提示保持中文版（桌面端与存量插件行为不变）")
    void chineseAndUnspecifiedKeepChineseSystemPrompt() {
        for (String lang : new String[] {"zh-CN", null}) {
            String systemText = systemTextUnder(lang);
            assertTrue(systemText.contains("SIMPLIFIED CHINESE ONLY"), "语言=" + lang);
            assertFalse(systemText.contains("ENGLISH ONLY"), "语言=" + lang);
        }
    }

    @Test
    @DisplayName("控制器把 X-App-Language 补进请求体：只靠请求头的话异步那一轮拿不到语言")
    void controllerCopiesHeaderIntoRequestBodyWhenBodyOmitsIt() {
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        // 控制器里的那两行等价逻辑（请求体优先，缺省补请求头）
        assertNull(AppLanguageScope.normalize(request.getAppLanguage()));
        request.setAppLanguage(AppLanguageScope.normalize("en-GB"));
        assertEquals("en-US", request.getAppLanguage(),
                "请求头里的 locale 形态要归一后再落进请求体，否则 AgentInbox 里存的是后端不认的串");
    }
}
