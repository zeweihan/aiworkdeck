package com.checkba.service.ai;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.repository.ProjectAiMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * run 状态持久化 + 进程重启后中断任务续跑的契约测试：
 * mark 写透 DB、启动回收把 RUNNING 翻成 INTERRUPTED 并补中断标记、续跑时翻回 RUNNING。
 */
class AgentRunRecoveryServiceTest {

    private static final String CONV = "conv-interrupted-1";

    /** conversationId -> 记录，模拟唯一约束下的 upsert 语义 */
    private Map<String, AgentRunRecord> table;
    private AgentRunRecordRepository recordRepository;
    private ProjectAiMessageRepository messageRepository;
    private Map<String, List<ProjectAiMessage>> messages;

    private AgentRunStateService stateService;
    private AgentRunRecoveryService recoveryService;
    /** mark 的埋点旁路：验证停机状态确实把轮次交给 TelemetryTurnTracker 闭合 */
    private com.checkba.service.telemetry.TelemetryTurnTracker turnTracker;

    @BeforeEach
    void setUp() {
        table = new LinkedHashMap<>();
        messages = new LinkedHashMap<>();

        recordRepository = Mockito.mock(AgentRunRecordRepository.class);
        when(recordRepository.findByConversationId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(table.get(inv.<String>getArgument(0))));
        when(recordRepository.save(any(AgentRunRecord.class))).thenAnswer(inv -> {
            AgentRunRecord r = inv.getArgument(0);
            table.put(r.getConversationId(), r);
            return r;
        });
        when(recordRepository.findByStatus(anyString())).thenAnswer(inv -> {
            String status = inv.getArgument(0);
            List<AgentRunRecord> out = new ArrayList<>();
            for (AgentRunRecord r : table.values()) {
                if (status.equals(r.getStatus())) out.add(r);
            }
            return out;
        });

        messageRepository = Mockito.mock(ProjectAiMessageRepository.class);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(anyString()))
                .thenAnswer(inv -> messages.getOrDefault(inv.<String>getArgument(0), List.of()));
        when(messageRepository.save(any(ProjectAiMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        turnTracker = Mockito.mock(com.checkba.service.telemetry.TelemetryTurnTracker.class);
        stateService = new AgentRunStateService(recordRepository, turnTracker);
        recoveryService = new AgentRunRecoveryService(recordRepository, messageRepository, stateService);
    }

    @AfterEach
    void resetLangText() {
        // 静态语言桥必须清干净，否则 en 模式的测试会污染同 fork 里的其他测试类
        com.checkba.service.LangText.reset();
    }

    private void switchToEnglish() {
        com.checkba.service.AppLanguageService en =
                Mockito.mock(com.checkba.service.AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        com.checkba.service.LangText.register(en);
    }

    private ProjectAiMessage msg(String role, String content) {
        ProjectAiMessage m = new ProjectAiMessage();
        m.setRole(role);
        m.setContent(content);
        m.setConversationId(CONV);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    @Test
    void mark_writesThroughToDatabase() {
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);

        AgentRunRecord saved = table.get(CONV);
        assertNotNull(saved, "mark 必须把状态写透到 agent_run_record");
        assertEquals("RUNNING", saved.getStatus());
        assertEquals(42L, saved.getProjectId());
        assertEquals(7L, saved.getUserId());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void mark_withoutOwnership_keepsExistingProjectAndUser() {
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        // 终态打点拿不到 projectId/userId，不能把已有归属抹成 null
        stateService.mark(CONV, AgentRunStateService.RunStatus.FINISHED);

        AgentRunRecord saved = table.get(CONV);
        assertEquals("FINISHED", saved.getStatus());
        assertEquals(42L, saved.getProjectId());
        assertEquals(7L, saved.getUserId());
    }

    @Test
    void mark_dbFailureDoesNotBreakInMemoryState() {
        when(recordRepository.save(any(AgentRunRecord.class))).thenThrow(new RuntimeException("db down"));

        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);

        assertEquals("RUNNING", stateService.statusName(CONV), "DB 写失败只记日志，不能影响内存快路径");
    }

    @Test
    void recovery_turnsStaleRunningIntoInterruptedAndAppendsNotice() {
        // 上个进程：跑到一半被杀，DB 里留下 RUNNING 记录 + 半截 ASSISTANT 消息
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        ProjectAiMessage halfDone = msg("ASSISTANT", "<process>已修订第四条</process>");
        messages.put(CONV, List.of(msg("USER", "帮我改合同"), halfDone));

        // 新进程：内存登记簿清零
        AgentRunStateService fresh = new AgentRunStateService(recordRepository, org.mockito.Mockito.mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        AgentRunRecoveryService recovery =
                new AgentRunRecoveryService(recordRepository, messageRepository, fresh);

        assertEquals(1, recovery.recoverInterruptedRuns());

        assertEquals("INTERRUPTED", table.get(CONV).getStatus(), "DB 记录必须回收");
        assertEquals("INTERRUPTED", fresh.statusName(CONV),
                "内存也要塞回去——/connect 推 run_state 只看内存");
        assertTrue(halfDone.getContent().contains("[进程中断]"), "半截回复要补中断标记");
        assertTrue(halfDone.getContent().startsWith("<process>已修订第四条</process>"), "原内容不能被覆盖");
    }

    @Test
    void recovery_isIdempotent() {
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        ProjectAiMessage halfDone = msg("ASSISTANT", "半截内容");
        messages.put(CONV, List.of(halfDone));

        recoveryService.recoverInterruptedRuns();
        String afterFirst = halfDone.getContent();
        // 状态已不是 RUNNING，正常情况下第二次启动捞不到它
        assertEquals(0, recoveryService.recoverInterruptedRuns());
        // 极端情况（回收跑到一半又被杀，记录仍是 RUNNING）：标记不能叠加成一串
        table.get(CONV).setStatus(AgentRunStateService.RunStatus.RUNNING.name());
        assertEquals(1, recoveryService.recoverInterruptedRuns());
        assertEquals(afterFirst, halfDone.getContent());
        assertEquals(1, countOccurrences(halfDone.getContent(), "[进程中断]"));
    }

    @Test
    void recovery_secondRestartStillRestoresInterruptedIntoMemory() {
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        ProjectAiMessage halfDone = msg("ASSISTANT", "半截内容");
        messages.put(CONV, List.of(halfDone));
        recoveryService.recoverInterruptedRuns();

        // 用户一直没点「继续」，又重启了一次：记录已是 INTERRUPTED，捞不到 RUNNING
        AgentRunStateService fresh = new AgentRunStateService(recordRepository, org.mockito.Mockito.mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        AgentRunRecoveryService recovery =
                new AgentRunRecoveryService(recordRepository, messageRepository, fresh);

        assertEquals(0, recovery.recoverInterruptedRuns());
        assertEquals("INTERRUPTED", fresh.statusName(CONV), "「继续」入口不能因为再次重启而消失");
        assertEquals(1, countOccurrences(halfDone.getContent(), "[进程中断]"), "标记不能叠加");
    }

    @Test
    void recovery_noAssistantMessage_stillMarksInterrupted() {
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        messages.put(CONV, List.of(msg("USER", "帮我改合同")));

        assertEquals(1, recoveryService.recoverInterruptedRuns());
        assertEquals("INTERRUPTED", stateService.statusName(CONV));
    }

    @Test
    void resume_flipsInterruptedBackToRunning() {
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        messages.put(CONV, List.of(msg("ASSISTANT", "半截内容")));
        recoveryService.recoverInterruptedRuns();
        assertEquals("INTERRUPTED", stateService.statusName(CONV));

        // 用户点「继续」= 发一条普通消息，编排器起跑时照常翻成 RUNNING
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);

        assertEquals("RUNNING", stateService.statusName(CONV));
        assertEquals("RUNNING", table.get(CONV).getStatus());
    }

    // ---- 反问停机 AWAITING_INPUT（模型问用户，等下一轮回答）----

    @Test
    void awaitingInput_writesThroughAndIsTheRunStateWirePayload() {
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        stateService.mark(CONV, AgentRunStateService.RunStatus.AWAITING_INPUT);

        assertEquals("AWAITING_INPUT", table.get(CONV).getStatus(), "反问停机必须写透 DB");
        // statusName 是 /connect 推 run_state 与会话列表 runStatus 的唯一数据源
        // （AiAgentController 把它原样拼进 {"status":"…"}，AiChatController 拼进 conv.runStatus）
        assertEquals("AWAITING_INPUT", stateService.statusName(CONV));
        assertEquals(42L, table.get(CONV).getProjectId(), "终态打点不许抹掉归属");
        assertEquals(7L, table.get(CONV).getUserId());
    }

    @Test
    void awaitingInput_closesTelemetryTurn() {
        stateService.mark(CONV, AgentRunStateService.RunStatus.AWAITING_INPUT);
        // 不加进 TelemetryTurnTracker.TERMINAL 的后果是 ai.turn 永不闭合（静默少一条埋点）
        Mockito.verify(turnTracker).onStatus(CONV, "AWAITING_INPUT");
    }

    @Test
    void recovery_doesNotTouchAwaitingInput() {
        // 律师关掉 app 明天再来点选项：AWAITING_INPUT 跨重启必须保持原样，
        // 口径与 AWAITING_APPROVAL 一致（回收只认 RUNNING）
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        stateService.mark(CONV, AgentRunStateService.RunStatus.AWAITING_INPUT);
        ProjectAiMessage question = msg("ASSISTANT", "<question>按哪版章程核对？</question>");
        messages.put(CONV, List.of(msg("USER", "核对决议"), question));

        AgentRunStateService fresh = new AgentRunStateService(recordRepository,
                Mockito.mock(com.checkba.service.telemetry.TelemetryTurnTracker.class));
        AgentRunRecoveryService recovery =
                new AgentRunRecoveryService(recordRepository, messageRepository, fresh);

        assertEquals(0, recovery.recoverInterruptedRuns(), "等回答不是被杀，不该被回收");
        assertEquals("AWAITING_INPUT", table.get(CONV).getStatus(), "DB 状态不许被改写成 INTERRUPTED");
        assertFalse(question.getContent().contains("[进程中断]"), "问题气泡不许被补中断标记");
    }

    @Test
    void awaitingInput_answerFlipsBackToRunning() {
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        stateService.mark(CONV, AgentRunStateService.RunStatus.AWAITING_INPUT);

        // 用户回答 = 一条普通用户消息，编排器起跑照常翻回 RUNNING（不做阻塞式挂起）
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);

        assertEquals("RUNNING", stateService.statusName(CONV));
        assertEquals("RUNNING", table.get(CONV).getStatus());
    }

    // ---- 中断说明的双语与幂等（EN 版 PR4-A）----

    @Test
    void recovery_englishMode_appendsEnglishNotice() {
        switchToEnglish();
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        ProjectAiMessage halfDone = msg("ASSISTANT", "half-finished reply");
        messages.put(CONV, List.of(halfDone));

        assertEquals(1, recoveryService.recoverInterruptedRuns());
        assertTrue(halfDone.getContent().contains("[Process interrupted]"), "en 模式要补英文标记");
        assertFalse(halfDone.getContent().contains("[进程中断]"), "en 模式不该出现中文标记");
    }

    @Test
    void recovery_englishMode_skipsLegacyChineseMarker() {
        // 升级场景：存量中文半截消息已带 [进程中断]，切到 en 后重启不能被二次追加英文说明
        switchToEnglish();
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        ProjectAiMessage halfDone = msg("ASSISTANT",
                "半截内容" + AgentRunRecoveryService.INTERRUPT_NOTICE_ZH);
        messages.put(CONV, List.of(halfDone));

        assertEquals(1, recoveryService.recoverInterruptedRuns());
        assertEquals(0, countOccurrences(halfDone.getContent(), "[Process interrupted]"),
                "已带中文标记的消息不能再叠英文说明");
        assertEquals(1, countOccurrences(halfDone.getContent(), "[进程中断]"));
    }

    @Test
    void recovery_chineseMode_skipsEnglishMarker() {
        // 反向切换：en 时期落库的英文标记，切回 zh 后重启同样不能叠中文说明
        stateService.mark(CONV, AgentRunStateService.RunStatus.RUNNING, 42L, 7L);
        ProjectAiMessage halfDone = msg("ASSISTANT",
                "half-finished reply" + AgentRunRecoveryService.INTERRUPT_NOTICE_EN);
        messages.put(CONV, List.of(halfDone));

        assertEquals(1, recoveryService.recoverInterruptedRuns());
        assertEquals(0, countOccurrences(halfDone.getContent(), "[进程中断]"),
                "已带英文标记的消息不能再叠中文说明");
        assertEquals(1, countOccurrences(halfDone.getContent(), "[Process interrupted]"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = text.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = text.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
