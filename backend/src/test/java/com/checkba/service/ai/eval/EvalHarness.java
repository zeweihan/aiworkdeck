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
        SkillRouter skillRouter = new SkillRouter(skillRegistry, skillProperties);

        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.getStreamingChatModel(any())).thenReturn(scripted);

        List<SavedMessage> savedMessages = new CopyOnWriteArrayList<>();
        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        doAnswer(inv -> {
            savedMessages.add(new SavedMessage(inv.getArgument(3), inv.getArgument(4)));
            return null;
        }).when(messageService).saveMessage(any(), any(), any(), any(), any());
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

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelFactory, messageService, sse, tokenUsage, assembler,
                registry, skillRouter, parser, memoryPipeline, projectFileService, editorBridge, fileChange,
                todoListService, checkpointService, new com.checkba.service.ai.AgentRunStateService());

        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(1L);
        request.setConversationId("eval-" + c.id);
        request.setMessage(c.userInput);
        request.setModel("anthropic/claude-3.5-sonnet");
        request.setMode(c.mode);

        orchestrator.handleUserMessage(request, 7L);

        return new RunResult(c, registry.dispatches(), List.copyOf(sseEvents), List.copyOf(savedMessages),
                List.copyOf(artifactSaves), List.copyOf(folderRenames),
                scripted.toolsOfferedPerCall(), scripted.toolNamesOfferedPerCall(), scripted.remainingTurns());
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
