package com.checkba.service.ai.eval;

import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.AgentOrchestrator;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.ContextAssemblerService;
import com.checkba.service.ai.ConversationFileChangeService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.SseEmitterService;
import com.checkba.service.ai.TokenUsageService;
import com.checkba.service.ai.XmlToolCallParser;
import com.checkba.service.ai.memory.MemoryPipelineService;
import com.checkba.service.ai.skill.SkillProperties;
import com.checkba.service.ai.skill.SkillRegistry;
import com.checkba.service.ai.skill.SkillRouter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回放评测 harness：
 * 用「真实的 AgentOrchestrator + 真实的 ToolRegistry/XmlToolCallParser（分发被记录、不真执行）+
 * 回放式 LLM（ScriptedStreamingModel）+ Mockito 打桩的外围服务」跑一个用例，
 * 收集编排器产生的所有可观测行为供断言。
 *
 * 覆盖的真实生产代码路径：
 * AgentOrchestrator.handleUserMessage / runLoop（含 XML 与原生两种工具协议、artifact、
 * &lt;title&gt;、Ask 模式）、XmlToolCallParser 全部解析逻辑、ToolRegistry 的别名解析。
 */
public final class EvalHarness {

    private EvalHarness() {
    }

    public record SseEvent(String event, String data) {
    }

    public record SavedMessage(String role, String content) {
    }

    public record ArtifactSave(String filename, String content) {
    }

    public record RunResult(
            EvalCase evalCase,
            List<RecordingToolRegistry.Dispatch> dispatches,
            List<SseEvent> sseEvents,
            List<SavedMessage> savedMessages,
            List<ArtifactSave> artifactSaves,
            List<String> folderRenames,
            List<Boolean> toolsOfferedPerLlmCall,
            List<List<String>> toolNamesOfferedPerLlmCall,
            List<String> promptTexts,
            int remainingScriptTurns) {

        /** 最终保存的 ASSISTANT 消息（含 executionLog 前缀） */
        public Optional<String> lastAssistantMessage() {
            for (int i = savedMessages.size() - 1; i >= 0; i--) {
                if ("ASSISTANT".equals(savedMessages.get(i).role())) {
                    return Optional.of(savedMessages.get(i).content());
                }
            }
            return Optional.empty();
        }

        public List<SseEvent> events(String name) {
            return sseEvents.stream().filter(e -> name.equals(e.event())).toList();
        }

        public List<SseEvent> errorEvents() {
            return events("error");
        }
    }

    /** 同步跑一个用例（handleUserMessage 直接调用，不经 Spring 代理，@Async 不生效） */
    public static RunResult run(EvalCase c) {
        PluginService pluginService = new PluginService();
        RecordingToolRegistry registry =
                new RecordingToolRegistry(RealToolBeans.instantiateAll(), pluginService);
        registry.init();
        registry.setStubs(c.toolStubs);
        XmlToolCallParser parser = new XmlToolCallParser(registry);
        ScriptedStreamingModel scripted = new ScriptedStreamingModel(c.turns);

        // 真实的 Skill 体系（Phase 3B）：扫描仓库内置 skills/ 目录，
        // 让「skill 触发 → 工具可见性裁剪」路径在回放里被真实执行
        SkillProperties skillProperties = new SkillProperties();
        skillProperties.setDir(skillsDir());
        skillProperties.setBaseTools(List.of("read_document", "list_files", "query_memory"));
        SkillRegistry skillRegistry = new SkillRegistry(skillProperties, null, pluginService);
        skillRegistry.init();
        // litigation-visual 的 skill.yml 声明了 enabled_by_default:false（默认关闭，需用户手动
        // 打开），首次扫描会被 SkillRegistry 自动禁用。skill-litigation-visual-tools-visible 这条
        // 回放用例要验证的正是"命中该 skill 时工具可见性怎么裁剪"，禁用会让它连触发匹配都进不去，
        // 白名单裁剪路径就测不到——这里显式开回去，让回放继续跑真实的裁剪逻辑。
        skillRegistry.setEnabled("litigation-visual", true);
        // 埋点：评测里用 mock 仓储的真实 TelemetryService（白名单路径被真实执行、不落库）
        com.checkba.service.telemetry.TelemetryService telemetry =
                new com.checkba.service.telemetry.TelemetryService(
                        mock(com.checkba.repository.TelemetryEventRepository.class),
                        new com.checkba.service.telemetry.InstallIdentityService(
                                System.getProperty("java.io.tmpdir")),
                        "eval");
        com.checkba.service.telemetry.TelemetryTurnTracker turnTracker =
                new com.checkba.service.telemetry.TelemetryTurnTracker(telemetry);

        SkillRouter skillRouter = new SkillRouter(skillRegistry, skillProperties, telemetry);

        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.getStreamingChatModel(any())).thenReturn(scripted);

        List<SavedMessage> savedMessages = new CopyOnWriteArrayList<>();
        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        // USER 消息落库走六参重载（契约 D：末位是 displayContent，缺省 null）。
        // 编排器只调六参那一个，五参重载在这里不桩也无妨；但**两个都桩着**是刻意的——
        // 哪天有人把调用点改回五参，评测不会因为「一条 USER 消息都没记录」而给出误导性的失败。
        doAnswer(inv -> {
            savedMessages.add(new SavedMessage(inv.getArgument(3), inv.getArgument(4)));
            return null;
        }).when(messageService).saveMessage(any(), any(), any(), any(), any());
        doAnswer(inv -> {
            savedMessages.add(new SavedMessage(inv.getArgument(3), inv.getArgument(4)));
            return null;
        }).when(messageService).saveMessage(any(), any(), any(), any(), any(), any());
        // ASSISTANT 消息走轮次级 upsert（首次插入返回行 ID，本轮内后续保存更新同一行）
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            savedMessages.add(new SavedMessage("ASSISTANT", inv.getArgument(4)));
            return 1L;
        });
        // 返回 2 条历史消息，跳过「首次对话异步生成标题」分支（评测不关心该路径）
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));

        List<SseEvent> sseEvents = new CopyOnWriteArrayList<>();
        SseEmitterService sse = mock(SseEmitterService.class);
        doAnswer(inv -> {
            sseEvents.add(new SseEvent(inv.getArgument(1), String.valueOf((Object) inv.getArgument(2))));
            return null;
        }).when(sse).send(any(), any(), any());

        TokenUsageService tokenUsage = mock(TokenUsageService.class);

        ContextAssemblerService assembler = mock(ContextAssemblerService.class);
        when(assembler.assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new ArrayList<>(List.of(
                        SystemMessage.from("[eval] system prompt placeholder"),
                        UserMessage.from(c.userInput))));

        MemoryPipelineService memoryPipeline = mock(MemoryPipelineService.class);

        List<ArtifactSave> artifactSaves = new CopyOnWriteArrayList<>();
        List<String> folderRenames = new CopyOnWriteArrayList<>();
        ProjectFileService projectFileService = mock(ProjectFileService.class);
        when(projectFileService.saveArtifactFile(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    artifactSaves.add(new ArtifactSave(inv.getArgument(2), inv.getArgument(3)));
                    return null;
                });
        doAnswer(inv -> {
            folderRenames.add(inv.getArgument(1));
            return null;
        }).when(projectFileService).renameConversationFolder(any(), any(), any());

        EditorBridgeService editorBridge = mock(EditorBridgeService.class);
        when(editorBridge.isStreamingMode(any())).thenReturn(false);

        ConversationFileChangeService fileChange = mock(ConversationFileChangeService.class);

        com.checkba.service.ai.TodoListService todoListService = mock(com.checkba.service.ai.TodoListService.class);
        com.checkba.service.ai.DocumentCheckpointService checkpointService = mock(com.checkba.service.ai.DocumentCheckpointService.class);
        com.checkba.version.WorkSessionService workSessionService = mock(com.checkba.version.WorkSessionService.class);

        // 故障转移与自动 compaction 用真实配置默认值：备选链默认为空（不会在回放里切模型），
        // compaction 阈值按 10 万 token 预算算，评测用例的几百字符碰不到
        com.checkba.config.AiFailoverProperties failoverProperties = new com.checkba.config.AiFailoverProperties();
        com.checkba.config.AiContextProperties contextProperties = new com.checkba.config.AiContextProperties();
        com.checkba.service.ai.context.ContextCompressor contextCompressor =
                new com.checkba.service.ai.context.ContextCompressor(null, null, contextProperties);
        com.checkba.service.ai.context.RunLoopCompactor runLoopCompactor =
                new com.checkba.service.ai.context.RunLoopCompactor(contextProperties, contextCompressor);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, tokenUsage, assembler,
                registry, skillRouter, parser, memoryPipeline, projectFileService, editorBridge, fileChange,
                todoListService, checkpointService,
                new com.checkba.service.ai.AgentRunStateService(
                        mock(com.checkba.repository.AgentRunRecordRepository.class), turnTracker),
                workSessionService, failoverProperties, runLoopCompactor,
                telemetry, turnTracker,
                mock(com.checkba.service.telemetry.MatterClassifierService.class));

        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId("eval-" + c.id);
        request.setMessage(c.userInput);
        request.setModel("anthropic/claude-3.5-sonnet");
        request.setMode(c.mode);

        orchestrator.handleUserMessage(request, 7L);

        return new RunResult(c, registry.dispatches(), List.copyOf(sseEvents), List.copyOf(savedMessages),
                List.copyOf(artifactSaves), List.copyOf(folderRenames),
                scripted.toolsOfferedPerCall(), scripted.toolNamesOfferedPerCall(),
                scripted.promptTextPerCall(), scripted.remainingTurns());
    }

    /** 内置 skills 目录（与 EvalCase.casesDir 同思路：兼容从 backend/ 或仓库根目录跑测试） */
    private static String skillsDir() {
        for (String candidate : List.of("skills", "backend/skills")) {
            if (java.nio.file.Files.isDirectory(java.nio.file.Path.of(candidate))) {
                return candidate;
            }
        }
        return "skills";
    }
}
