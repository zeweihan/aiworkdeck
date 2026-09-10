// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiFailoverProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.AgentInboxItem;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.AgentInboxItemRepository;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.RunLoopCompactor;
import com.checkba.service.ai.memory.MemoryPipelineService;
import com.checkba.service.ai.skill.SkillRouter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentOrchestratorInboxTest {
    private static final String CONV = "conv-inbox";
    private static final String MODEL = "qwen/qwen3.7-flash";

    private final Map<String, AgentInboxItem> rows = new ConcurrentHashMap<>();
    private final List<List<ChatMessage>> modelRequests = new CopyOnWriteArrayList<>();
    private final List<String> sseEvents = new CopyOnWriteArrayList<>();
    private AgentInboxService inbox;
    private AgentOrchestrator orchestrator;
    private ChatModelFactory modelFactory;
    private ToolRegistry tools;
    private ContextAssemblerService assembler;
    private XmlToolCallParser xml;

    @BeforeEach
    void setUp() {
        rows.clear();
        modelRequests.clear();
        sseEvents.clear();
        AgentInboxItemRepository repo = inMemoryRepository();
        SseEmitterService sse = mock(SseEmitterService.class);
        doAnswer(inv -> {
            Object payload = inv.getArgument(2);
            sseEvents.add(inv.getArgument(1, String.class) + ":" + String.valueOf(payload));
            return null;
        })
                .when(sse).send(any(), any(), any());
        AgentRunStateService runState = mock(AgentRunStateService.class);
        inbox = new AgentInboxService(repo, sse, runState);

        modelFactory = mock(ChatModelFactory.class);
        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(any()))
                .thenReturn(List.of(mock(ProjectAiMessage.class), mock(ProjectAiMessage.class)));
        AtomicInteger assistantIds = new AtomicInteger();
        when(messageService.upsertAssistantMessage(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(3) != null ? inv.getArgument(3) : (long) assistantIds.incrementAndGet());

        assembler = mock(ContextAssemblerService.class);
        doAnswer(inv -> {
            String prompt = inv.getArgument(2);
            return new ArrayList<ChatMessage>(List.of(SystemMessage.from("system"),
                    UserMessage.from(prompt == null ? "original" : prompt)));
        }).when(assembler).assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        tools = mock(ToolRegistry.class);
        when(tools.getAllSpecifications(any())).thenReturn(List.of());
        when(tools.resolve(anyString())).thenReturn(Optional.empty());
        SkillRouter skills = mock(SkillRouter.class);
        when(skills.visibleTools(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(skills.activeSkill(any())).thenReturn(Optional.empty());
        xml = mock(XmlToolCallParser.class);
        when(xml.containsToolCall(any())).thenReturn(false);
        AiContextProperties props = new AiContextProperties();

        orchestrator = new AgentOrchestrator(
                modelFactory, messageService, sse, mock(TokenUsageService.class), assembler,
                tools, skills, xml, mock(MemoryPipelineService.class), mock(ProjectFileService.class),
                mock(EditorBridgeService.class), mock(ConversationFileChangeService.class),
                mock(TodoListService.class), mock(DocumentCheckpointService.class), runState,
                mock(com.checkba.version.WorkSessionService.class), new AiFailoverProperties(),
                new RunLoopCompactor(props, new ContextCompressor(null, null, props)),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.telemetry.TelemetryTurnTracker.class),
                mock(com.checkba.service.telemetry.MatterClassifierService.class), new OfficePassStateStore());
        orchestrator.setInboxService(inbox);
    }

    @Test
    void steerDuringFirstToolIsAppliedOnceAndSkipsPendingWriteWithNativePairing() throws Exception {
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch releaseTool = new CountDownLatch(1);
        when(tools.execute(eq("read"), any(), any())).thenAnswer(inv -> {
            toolEntered.countDown();
            assertTrue(releaseTool.await(5, TimeUnit.SECONDS));
            return new ToolRegistry.ToolResult("read-result", null, true);
        });
        when(tools.execute(eq("write"), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("written", null, true));

        StreamingChatLanguageModel model = scripted(
                AiMessage.from(List.of(tool("read", "r1"), tool("write", "w1"))),
                AiMessage.from("done after steer"));
        when(modelFactory.getStreamingChatModel(MODEL)).thenReturn(model);

        AgentInboxItem initial = inbox.submit(request("original", "initial", "steer"), 7L);
        Thread run = new Thread(() -> orchestrator.acceptInboxSubmission(initial.getId(), false));
        run.start();
        assertTrue(toolEntered.await(5, TimeUnit.SECONDS));

        AgentInboxItem steer = inbox.submit(request("change direction", "steer-key", "steer"), 7L);
        AgentInboxItem duplicate = inbox.submit(request("duplicate text", "steer-key", "steer"), 7L);
        assertEquals(steer.getId(), duplicate.getId());
        assertEquals(orchestrator.activeRunId(CONV), orchestrator.acceptInboxSubmission(duplicate.getId()));

        releaseTool.countDown();
        run.join(5000);
        assertFalse(run.isAlive());

        verify(tools, never()).execute(eq("write"), any(), any());
        assertEquals(2, rows.size());
        assertEquals(AgentInboxService.APPLIED, rows.get(steer.getId()).getState());
        assertEquals(rows.get(initial.getId()).getRunId(), rows.get(steer.getId()).getRunId());
        assertEquals(2, modelRequests.size());
        List<ChatMessage> next = modelRequests.get(1);
        assertEquals(1, next.stream().filter(m -> text(m).contains("change direction")).count());
        assertTrue(next.stream().filter(m -> m instanceof ToolExecutionResultMessage)
                .anyMatch(m -> text(m).contains("superseded")), "skipped native write must have a paired result");
    }

    @Test
    void steeringAttachmentIsAugmentedIntoTheNextModelRequest() throws Exception {
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch releaseTool = new CountDownLatch(1);
        when(tools.execute(eq("read"), any(), any())).thenAnswer(inv -> {
            toolEntered.countDown();
            releaseTool.await(5, TimeUnit.SECONDS);
            return new ToolRegistry.ToolResult("ok", null, true);
        });
        doAnswer(inv -> {
                    List<?> contexts = inv.getArgument(3);
                    String text = contexts != null && !contexts.isEmpty()
                            ? "AUGMENTED_ATTACHMENT:88" : inv.getArgument(2, String.class);
                    return new ArrayList<ChatMessage>(List.of(SystemMessage.from("system"),
                            UserMessage.from(text == null ? "original" : text)));
                }).when(assembler).assemble(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(modelFactory.getStreamingChatModel(MODEL)).thenReturn(scripted(
                AiMessage.from(List.of(tool("read", "r1"))), AiMessage.from("done")));

        AgentInboxItem first = inbox.submit(request("original", "i", "steer"), 7L);
        Thread run = new Thread(() -> orchestrator.acceptInboxSubmission(first.getId(), false));
        run.start();
        assertTrue(toolEntered.await(5, TimeUnit.SECONDS));
        AiAgentController.AgentChatRequest steerRequest = request("use attached contract", "attachment", "steer");
        AiAgentController.ContextItem context = new AiAgentController.ContextItem();
        context.setId("88");
        context.setName("contract.docx");
        steerRequest.setContextItems(List.of(context));
        inbox.submit(steerRequest, 7L);
        releaseTool.countDown();
        run.join(5000);

        assertTrue(modelRequests.get(1).stream().anyMatch(m -> text(m).contains("AUGMENTED_ATTACHMENT:88")));
    }

    @Test
    void steerBetweenXmlToolsCancelsTheRemainingCallAndPreservesFeedback() throws Exception {
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch releaseTool = new CountDownLatch(1);
        when(tools.execute(eq("read"), any(), any())).thenAnswer(inv -> {
            toolEntered.countDown();
            assertTrue(releaseTool.await(5, TimeUnit.SECONDS));
            return new ToolRegistry.ToolResult("read-result", null, true);
        });
        when(tools.execute(eq("write"), any(), any()))
                .thenReturn(new ToolRegistry.ToolResult("written", null, true));
        when(xml.containsToolCall(anyString()))
                .thenAnswer(inv -> inv.getArgument(0, String.class).contains("<tool_code>"));
        when(xml.extractProcessName(anyString())).thenReturn(Optional.empty());
        when(xml.parse(anyString())).thenReturn(List.of(
                new XmlToolCallParser.ParsedCall("read", "{}", "read()"),
                new XmlToolCallParser.ParsedCall("write", "{}", "write()")));
        when(modelFactory.getStreamingChatModel(MODEL)).thenReturn(scripted(
                AiMessage.from("<tool_code>read()</tool_code><tool_code>write()</tool_code>"),
                AiMessage.from("done after XML steer")));

        AgentInboxItem first = inbox.submit(request("original", "xml-first", "steer"), 7L);
        Thread run = new Thread(() -> orchestrator.acceptInboxSubmission(first.getId(), false));
        run.start();
        assertTrue(toolEntered.await(5, TimeUnit.SECONDS));
        AgentInboxItem steer = inbox.submit(request("change XML direction", "xml-steer", "steer"), 7L);
        releaseTool.countDown();
        run.join(5000);

        assertFalse(run.isAlive());
        verify(tools, never()).execute(eq("write"), any(), any());
        assertEquals(AgentInboxService.APPLIED, rows.get(steer.getId()).getState());
        List<ChatMessage> next = modelRequests.get(1);
        assertTrue(next.stream().anyMatch(m -> text(m).contains("change XML direction")));
        assertTrue(next.stream().anyMatch(m -> text(m).contains("Status: CANCELLED")),
                "skipped XML tool must leave cancellation feedback in the model stack");
    }

    @Test
    void queuedInputRacingWithFinalizationStartsExactlyOnce() throws Exception {
        CountDownLatch finalReady = new CountDownLatch(1);
        CountDownLatch releaseFinal = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StreamingChatLanguageModel model = new StreamingChatLanguageModel() {
            @Override public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
                complete(messages, handler);
            }
            @Override public void generate(List<ChatMessage> messages, List<ToolSpecification> specifications,
                                           StreamingResponseHandler<AiMessage> handler) {
                complete(messages, handler);
            }
            private void complete(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
                modelRequests.add(new ArrayList<>(messages));
                if (calls.incrementAndGet() == 1) {
                    finalReady.countDown();
                    await(releaseFinal);
                    handler.onNext("first done");
                    handler.onComplete(Response.from(AiMessage.from("first done")));
                } else {
                    handler.onNext("queued done");
                    handler.onComplete(Response.from(AiMessage.from("queued done")));
                }
            }
        };
        when(modelFactory.getStreamingChatModel(MODEL)).thenReturn(model);

        AgentInboxItem first = inbox.submit(request("first", "first", "steer"), 7L);
        Thread run = new Thread(() -> orchestrator.acceptInboxSubmission(first.getId(), false));
        run.start();
        assertTrue(finalReady.await(5, TimeUnit.SECONDS));
        AgentInboxItem queued = inbox.submit(request("follow-up", "q", "queue"), 7L);
        assertEquals(AgentInboxService.PENDING, rows.get(queued.getId()).getState());
        releaseFinal.countDown();
        run.join(5000);

        assertEquals(2, calls.get());
        assertEquals(AgentInboxService.APPLIED, rows.get(queued.getId()).getState());
        assertNotEquals(rows.get(first.getId()).getRunId(), rows.get(queued.getId()).getRunId());
        assertEquals(1, modelRequests.stream()
                .filter(stack -> stack.stream().anyMatch(m -> text(m).contains("follow-up"))).count());
        assertNull(orchestrator.activeRunId(CONV));
        assertTrue(sseEvents.stream().noneMatch(e -> e.startsWith("input_applied:") && e.contains(first.getId())),
                "originating POST must not emit input_applied");
        int snapshotIndex = indexOfEvent("inbox_updated", rows.get(queued.getId()).getRunId());
        int appliedIndex = indexOfEvent("input_applied", queued.getId());
        assertTrue(snapshotIndex >= 0 && appliedIndex > snapshotIndex,
                "new-run snapshot must precede applied event: " + sseEvents);
    }

    @Test
    void cancellationPausesQueuedFollowUpInsteadOfAutoDrainingIt() throws Exception {
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch releaseTool = new CountDownLatch(1);
        when(tools.execute(eq("read"), any(), any())).thenAnswer(inv -> {
            toolEntered.countDown();
            assertTrue(releaseTool.await(5, TimeUnit.SECONDS));
            return new ToolRegistry.ToolResult("read-result", null, true);
        });
        AtomicInteger calls = new AtomicInteger();
        StreamingChatLanguageModel delegate = scripted(
                AiMessage.from(List.of(tool("read", "r1"))), AiMessage.from("should not be reached"));
        StreamingChatLanguageModel model = new StreamingChatLanguageModel() {
            @Override public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
                calls.incrementAndGet();
                delegate.generate(messages, handler);
            }
            @Override public void generate(List<ChatMessage> messages, List<ToolSpecification> specifications,
                                           StreamingResponseHandler<AiMessage> handler) {
                calls.incrementAndGet();
                delegate.generate(messages, specifications, handler);
            }
        };
        when(modelFactory.getStreamingChatModel(MODEL)).thenReturn(model);

        AgentInboxItem first = inbox.submit(request("first", "cancel-first", "steer"), 7L);
        Thread run = new Thread(() -> orchestrator.acceptInboxSubmission(first.getId(), false));
        run.start();
        assertTrue(toolEntered.await(5, TimeUnit.SECONDS));
        AgentInboxItem queued = inbox.submit(request("later", "cancel-queued", "queue"), 7L);
        orchestrator.setCancelled(CONV);
        releaseTool.countDown();
        run.join(5000);

        assertFalse(run.isAlive());
        assertEquals(1, calls.get());
        assertEquals(AgentInboxService.PENDING, rows.get(queued.getId()).getState());
        assertNull(orchestrator.activeRunId(CONV));
    }

    private StreamingChatLanguageModel scripted(AiMessage... script) {
        AtomicInteger call = new AtomicInteger();
        return new StreamingChatLanguageModel() {
            @Override public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
                complete(messages, handler);
            }
            @Override public void generate(List<ChatMessage> messages, List<ToolSpecification> specifications,
                                           StreamingResponseHandler<AiMessage> handler) {
                complete(messages, handler);
            }
            private void complete(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
                modelRequests.add(new ArrayList<>(messages));
                AiMessage reply = script[Math.min(call.getAndIncrement(), script.length - 1)];
                if (reply.text() != null && !reply.text().isEmpty()) handler.onNext(reply.text());
                handler.onComplete(Response.from(reply));
            }
        };
    }

    private AgentInboxItemRepository inMemoryRepository() {
        AgentInboxItemRepository repo = mock(AgentInboxItemRepository.class);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> save(inv.getArgument(0)));
        when(repo.save(any())).thenAnswer(inv -> save(inv.getArgument(0)));
        when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        when(repo.findByConversationIdOrderByPositionAscCreatedAtAsc(anyString()))
                .thenAnswer(inv -> list(inv.getArgument(0), null));
        when(repo.findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(anyString(), anyString()))
                .thenAnswer(inv -> list(inv.getArgument(0), inv.getArgument(1)));
        when(repo.findByConversationIdAndUserIdAndClientRequestId(anyString(), anyLong(), anyString()))
                .thenAnswer(inv -> rows.values().stream().filter(i ->
                        Objects.equals(i.getConversationId(), inv.getArgument(0))
                                && Objects.equals(i.getUserId(), inv.getArgument(1))
                                && Objects.equals(i.getClientRequestId(), inv.getArgument(2))).findFirst());
        when(repo.findByRunIdAndState(anyString(), anyString())).thenReturn(List.of());
        return repo;
    }

    private AgentInboxItem save(AgentInboxItem item) { rows.put(item.getId(), item); return item; }
    private List<AgentInboxItem> list(String conv, String state) {
        return rows.values().stream().filter(i -> Objects.equals(conv, i.getConversationId()))
                .filter(i -> state == null || Objects.equals(state, i.getState()))
                .sorted(Comparator.comparing(AgentInboxItem::getPosition)).toList();
    }
    private static ToolExecutionRequest tool(String name, String id) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }
    private static AiAgentController.AgentChatRequest request(String message, String key, String mode) {
        AiAgentController.AgentChatRequest r = new AiAgentController.AgentChatRequest();
        r.setProjectId(42L); r.setConversationId(CONV); r.setMessage(message); r.setModel(MODEL);
        r.setClientRequestId(key); r.setSubmissionMode(mode); return r;
    }
    private static String text(ChatMessage message) {
        try { return message.text() == null ? "" : message.text(); }
        catch (Exception e) { return ""; }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private int indexOfEvent(String name, String contains) {
        for (int i = 0; i < sseEvents.size(); i++) {
            if (sseEvents.get(i).startsWith(name + ":") && sseEvents.get(i).contains(contains)) return i;
        }
        return -1;
    }
}
