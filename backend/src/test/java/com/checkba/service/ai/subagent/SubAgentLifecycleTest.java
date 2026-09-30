// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.ai.subagent;

import com.checkba.service.ai.*;
import com.checkba.service.ai.tools.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubAgentLifecycleTest {
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ChatLanguageModel model = mock(ChatLanguageModel.class);
    private final SseEmitterService sse = mock(SseEmitterService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EditorBridgeService bridge = new EditorBridgeService(sse, mapper,
            mock(com.checkba.service.telemetry.TelemetryService.class));
    private final SubAgentProperties props = new SubAgentProperties();
    private final TokenUsageService usage = mock(TokenUsageService.class);
    private final BlockingQueue<String> started = new LinkedBlockingQueue<>();
    private final List<String> stages = new CopyOnWriteArrayList<>();
    private SubAgentService service;

    @BeforeEach void setup() throws Exception {
        props.setMaxParallel(1);
        when(registry.getAllSpecifications(any())).thenReturn(List.of(
                ToolSpecification.builder().name("doc_get_document_text").description("read").build(),
                ToolSpecification.builder().name("search_web").description("search").build()));
        when(registry.execute(any(), any(), any())).thenReturn(new ToolRegistry.ToolResult("ok", null, true));
        when(model.generate(anyList(), anyList())).thenReturn(text("done"));
        var factory = mock(ChatModelFactory.class);
        when(factory.getChatModel(any())).thenReturn(model);
        var resolver = mock(AuxModelResolver.class);
        when(resolver.subAgentModelId(any())).thenReturn(AllowedModels.QWEN_3_7_FLASH.getModelId());
        service = new SubAgentService(registry, factory, new XmlToolCallParser(registry), sse, props,
                resolver, usage);
        ReflectionTestUtils.setField(service, "editorBridgeService", bridge);
        doAnswer(inv -> {
            if ("subtask_progress".equals(inv.getArgument(1))) {
                var event = mapper.readTree(inv.getArgument(2, String.class));
                stages.add(event.path("stage").asText());
                if ("started".equals(event.path("stage").asText())) started.add(event.path("taskId").asText());
            } else if ("client_action".equals(inv.getArgument(1))) {
                var event = mapper.readTree(inv.getArgument(2, String.class));
                bridge.completeEditorAction(event.path("requestId").asText(), inv.getArgument(0), true,
                        Map.of("text", "document:" + inv.getArgument(0)), null);
            }
            return null;
        }).when(sse).send(any(), any(), any());
    }

    @AfterEach void cleanup() { service.shutdown(); }

    private ToolContext ctx(String conversation, String run, AtomicBoolean cancelled) {
        return new ToolContext(42L, conversation, 7L, "parent", List.of(), null, run, null, cancelled::get);
    }
    private static Response<AiMessage> text(String value) { return Response.from(AiMessage.from(value)); }
    private static Response<AiMessage> tool(boolean xml) {
        return xml ? text("<tool_code>search_web(query=\"test\")</tool_code>")
                : Response.from(AiMessage.from(ToolExecutionRequest.builder().id("late").name("search_web")
                        .arguments("{\"query\":\"test\"}").build()));
    }
    // Deliberately clears interrupts like a non-interruptible provider, unlike Thread.sleep mocks.
    private static void awaitIgnoringInterrupt(CountDownLatch gate) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (gate.getCount() != 0 && System.nanoTime() < end) {
            try { gate.await(20, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        }
        assertEquals(0, gate.getCount(), "test gate timed out");
        Thread.interrupted();
    }
    private ExecutorService pool() { return (ExecutorService) ReflectionTestUtils.getField(service, "executor"); }
    private void drain() throws Exception { pool().submit(() -> {}).get(5, TimeUnit.SECONDS); }

    @Test void actualEditorBridgeUsesParentConversationAndCleansReusedThread() throws Exception {
        when(registry.execute(eq("doc_get_document_text"), any(), any())).thenAnswer(inv ->
                new ToolRegistry.ToolResult(bridge.executeEditorCommand("get_document_text", Map.of()), null, true));
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> {
            List<?> messages = inv.getArgument(0);
            if (messages.size() == 2) return Response.from(AiMessage.from(ToolExecutionRequest.builder()
                    .id("read").name("doc_get_document_text").arguments("{}").build()));
            return text(messages.get(messages.size() - 1).toString());
        });
        bridge.setCurrentConversationId("caller-thread");
        for (String cid : List.of("first", "second")) {
            var result = service.dispatch("read", null, List.of("doc_get_document_text"), ctx(cid, cid, new AtomicBoolean()));
            assertTrue(result.success());
            assertTrue(result.result().contains("document:" + cid), result.toJson());
            assertNull(pool().submit(bridge::getCurrentConversationId).get());
        }
        assertEquals("caller-thread", bridge.getCurrentConversationId());
        bridge.clearCurrentConversationId();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void taskCancelRejectsLateNativeAndXmlTools(boolean xml) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> {
            if (((List<?>) inv.getArgument(0)).size() > 2) return text("done");
            entered.countDown(); awaitIgnoringInterrupt(release);
            return Response.from(tool(xml).content(), new dev.langchain4j.model.output.TokenUsage(30, 10));
        });
        var caller = Executors.newSingleThreadExecutor();
        try {
            var result = caller.submit(() -> service.dispatch("late", null, List.of("search_web"),
                    ctx("c", "r", new AtomicBoolean())));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(service.cancel(started.poll(5, TimeUnit.SECONDS), "c"));
            assertFalse(result.get(5, TimeUnit.SECONDS).success());
            release.countDown(); drain();
            verify(registry, never()).execute(any(), any(), any());
            verify(usage).recordUsage(eq(42L), eq(7L), anyString(),
                    eq(new dev.langchain4j.model.output.TokenUsage(30, 10)), eq("c"));
            assertFalse(stages.contains("succeeded"));
        } finally { release.countDown(); caller.shutdownNow(); }
    }

    @Test void timeoutRejectsNonInterruptibleLateTool() throws Exception {
        props.setTimeoutSeconds(1);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> {
            if (((List<?>) inv.getArgument(0)).size() > 2) return text("done");
            entered.countDown(); awaitIgnoringInterrupt(release); return tool(false);
        });
        try {
            var result = service.dispatch("late", null, List.of("search_web"), ctx("c", "r", new AtomicBoolean()));
            assertTrue(result.error().contains("timed out"));
            release.countDown(); drain();
            verify(registry, never()).execute(any(), any(), any());
        } finally { release.countDown(); }
    }

    @Test void cancelledParentCannotSubmitOrAcceptFinalAnswer() throws Exception {
        var cancelled = new AtomicBoolean(true);
        assertFalse(service.dispatch("already stopped", null, List.of("search_web"), ctx("c", "r", cancelled)).success());
        verify(model, never()).generate(anyList(), anyList());
        cancelled.set(false);
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> { cancelled.set(true); return text("done"); });
        assertFalse(service.dispatch("stops in model", null, List.of("search_web"), ctx("c", "r", cancelled)).success());
        assertFalse(stages.contains("succeeded"));
    }

    @Test void cancellationBetweenToolsSkipsRestOfSameResponse() {
        var cancelled = new AtomicBoolean();
        var captured = new AtomicReference<ToolContext>();
        when(model.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("a").name("search_web").arguments("{}").build(),
                ToolExecutionRequest.builder().id("b").name("search_web").arguments("{}").build()))));
        when(registry.execute(any(), any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(2));
            cancelled.set(true); return new ToolRegistry.ToolResult("first done", null, true);
        });
        var result = service.dispatch("two tools", null, List.of("search_web"), ctx("c", "r", cancelled));
        assertFalse(result.success());
        verify(registry, times(1)).execute(any(), any(), any());
        assertEquals(List.of("search_web"), result.toolsUsed());
        assertNull(captured.get().runId(), "child tools must not see the parent's skill/disclosure run");
        assertTrue(captured.get().isCancelled());
    }

    @Test void failedWorkerRestoresPreviousBridgeContext() throws Exception {
        pool().submit(() -> bridge.setCurrentConversationId("previous")).get();
        when(model.generate(anyList(), anyList())).thenThrow(new IllegalStateException("model failed"));
        assertFalse(service.dispatch("fail", null, List.of("search_web"), ctx("failed", "r", new AtomicBoolean())).success());
        assertEquals("previous", pool().submit(bridge::getCurrentConversationId).get());
        pool().submit(bridge::clearCurrentConversationId).get();
        doReturn(text("next success")).when(model).generate(anyList(), anyList());
        assertTrue(service.dispatch("next", null, List.of("search_web"), ctx("next", "r2", new AtomicBoolean())).success());
        assertNull(pool().submit(bridge::getCurrentConversationId).get());
    }

    @Test void parentCancellationIsExactAndLateDispatchSeesCapturedChecker() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> {
            entered.countDown(); awaitIgnoringInterrupt(release); return text("done");
        });
        var callers = Executors.newFixedThreadPool(3);
        try {
            var first = callers.submit(() -> service.dispatch("first", null, List.of("search_web"), ctx("c", "r1", cancelled)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var sameConversation = callers.submit(() -> service.dispatch("new run", null, List.of("search_web"), ctx("c", "r2", new AtomicBoolean())));
            var otherConversation = callers.submit(() -> service.dispatch("other", null, List.of("search_web"), ctx("other", "r1", new AtomicBoolean())));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (((Map<?, ?>) ReflectionTestUtils.getField(service, "running")).size() < 3 && System.nanoTime() < until) Thread.sleep(5);
            assertEquals(3, ((Map<?, ?>) ReflectionTestUtils.getField(service, "running")).size());
            service.cancelParentRun("c", null);
            assertFalse(first.isDone());
            cancelled.set(true);
            service.cancelParentRun("c", "r1");
            assertFalse(first.get(5, TimeUnit.SECONDS).success());
            assertFalse(sameConversation.isDone());
            assertFalse(otherConversation.isDone());
            release.countDown();
            assertTrue(sameConversation.get(5, TimeUnit.SECONDS).success());
            assertTrue(otherConversation.get(5, TimeUnit.SECONDS).success());
            int calls = mockingDetails(model).getInvocations().size();
            assertFalse(service.dispatch("late", null, List.of("search_web"), ctx("c", "r1", cancelled)).success());
            assertEquals(calls, mockingDetails(model).getInvocations().size());
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test void interruptedCallerRejectsLateToolEvenWhenProviderClearsInterrupt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> {
            entered.countDown(); awaitIgnoringInterrupt(release); return tool(false);
        });
        var result = new AtomicReference<SubAgentResult>();
        Thread caller = new Thread(() -> result.set(service.dispatch("interrupt", null, List.of("search_web"), ctx("c", "r", new AtomicBoolean()))));
        try {
            caller.start(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            caller.interrupt(); caller.join(5000);
            assertFalse(caller.isAlive()); assertFalse(result.get().success());
            release.countDown(); drain();
            verify(registry, never()).execute(any(), any(), any());
        } finally { release.countDown(); caller.interrupt(); }
    }

    @Test void timeoutDuringToolWithoutParentContextStillBlocksNextTool() throws Exception {
        props.setTimeoutSeconds(1);
        var release = new CountDownLatch(1);
        when(model.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from(List.of(
                ToolExecutionRequest.builder().id("a").name("search_web").arguments("{}").build(),
                ToolExecutionRequest.builder().id("b").name("search_web").arguments("{}").build()))));
        when(registry.execute(any(), any(), any())).thenAnswer(inv -> {
            awaitIgnoringInterrupt(release);
            return new ToolRegistry.ToolResult("first done", null, true);
        });
        try {
            assertTrue(service.dispatch("two tools", null, List.of("search_web"), null).error().contains("timed out"));
            release.countDown(); drain();
            verify(registry, times(1)).execute(any(), any(), any());
        } finally { release.countDown(); }
    }

    @Test void realRegistryChildGetsIndependentSkillInstructionsWithoutChangingParent(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var skillProps = new com.checkba.service.ai.skill.SkillProperties();
        skillProps.setDir(dir.toString());
        skillProps.setBaseTools(List.of("use_skill", "list_tools"));
        for (String id : List.of("parent-skill", "child-skill")) {
            var skillDir = java.nio.file.Files.createDirectories(dir.resolve(id));
            java.nio.file.Files.writeString(skillDir.resolve("skill.yml"), "id: " + id + "\nname: " + id
                    + "\ntriggers:\n  - " + id + "\ntool_policy: restrict\nallowed_tools:\n  - " + id + "_tool\n");
            java.nio.file.Files.writeString(skillDir.resolve("prompt.md"), "Independent instructions for " + id);
        }
        var plugins = new PluginService();
        var skills = new com.checkba.service.ai.skill.SkillRegistry(skillProps, null, plugins, null);
        skills.init();
        var router = new com.checkba.service.ai.skill.SkillRouter(skills, skillProps,
                mock(com.checkba.service.telemetry.TelemetryService.class), null);
        router.activateMidRun("c", "parent-run", "parent-skill");
        assertTrue(router.isActiveInRun("parent-run", "parent-skill"));
        var discovery = new com.checkba.service.ai.tools.ToolDiscoveryTools(new ToolDisclosurePolicy(true));
        discovery.setSkillRouter(router);
        var realRegistry = new ToolRegistry(List.of(new com.checkba.service.ai.tools.SkillTools(router), discovery),
                plugins, new ClientCapabilityService());
        realRegistry.init();
        var candidates = List.of(
                ToolSpecification.builder().name("parent-skill_tool").description("parent").build(),
                ToolSpecification.builder().name("child-skill_tool").description("child").build());
        var beforeActive = router.activeSkills("parent-run");
        var beforeVisible = router.visibleTools("parent-run", candidates);
        var disclosed = new HashSet<>(Set.of("parent-only"));
        var factory = mock(ChatModelFactory.class);
        when(factory.getChatModel(any())).thenReturn(model);
        var resolver = mock(AuxModelResolver.class);
        when(resolver.subAgentModelId(any())).thenReturn(AllowedModels.QWEN_3_7_FLASH.getModelId());
        service.shutdown();
        service = new SubAgentService(realRegistry, factory, new XmlToolCallParser(realRegistry), sse, props, resolver, usage);
        var catalogOutput = new AtomicReference<String>();
        var skillOutputs = new AtomicReference<String>();
        when(model.generate(anyList(), anyList())).thenAnswer(inv -> {
            List<?> messages = inv.getArgument(0);
            if (messages.size() == 2) return Response.from(AiMessage.from(List.of(
                    ToolExecutionRequest.builder().id("catalog").name("list_tools").arguments("{}").build(),
                    ToolExecutionRequest.builder().id("parent").name("use_skill").arguments("{\"skillId\":\"parent-skill\"}").build(),
                    ToolExecutionRequest.builder().id("child").name("use_skill").arguments("{\"skillId\":\"child-skill\"}").build())));
            catalogOutput.set(((dev.langchain4j.data.message.ToolExecutionResultMessage) messages.get(3)).text());
            skillOutputs.set(messages.subList(4, messages.size()).toString());
            return text("done");
        });
        var result = service.dispatch("independent review", null, List.of(), new ToolContext(42L, "c", 7L, "parent",
                List.of(), null, "parent-run", disclosed, () -> false));
        assertTrue(result.success(), result.toJson());
        assertTrue(skillOutputs.get().contains("Independent instructions for parent-skill"), skillOutputs.get());
        assertTrue(skillOutputs.get().contains("Independent instructions for child-skill"), skillOutputs.get());
        assertFalse(skillOutputs.get().contains("already active for this turn"), skillOutputs.get());
        assertTrue(catalogOutput.get().contains("parent-skill"), catalogOutput.get());
        assertTrue(catalogOutput.get().contains("child-skill"), catalogOutput.get());
        assertEquals(beforeActive, router.activeSkills("parent-run"));
        assertEquals(beforeVisible, router.visibleTools("parent-run", candidates));
        assertFalse(router.isActiveInRun("parent-run", "child-skill"));
        assertEquals(Set.of("parent-only"), disclosed);
    }
}
