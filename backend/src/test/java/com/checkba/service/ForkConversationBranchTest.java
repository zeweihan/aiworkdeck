// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.ProjectAiMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「从此分叉」（dev-board#779 K18，审查 D-06 / F4）：非破坏地从某一条消息岔出一条新会话。
 *
 * <p>回退（K1）是 edit-and-resend——目标连同其后一起删，原路径只在自动存档里留着；
 * 分叉是它的非破坏形态：<b>原会话一个字都不动</b>，只把「到这条为止」复制成一条新会话。
 * 律师常要对同一份合同试两种改法再比较，这条路必须存在，而且不能逼人先破坏一次。
 *
 * <p>两者共用同一套定位键解析（{@link ProjectAiMessageService#resolveRollbackTarget}）与
 * 同一个 fork 实现，差别只有三处：复制到哪里为止、标题后缀、原会话截不截断。
 */
@DisplayName("从此分叉：按消息截断的 fork + 父子字段 + 列表来源角标")
class ForkConversationBranchTest {

    private ProjectAiMessageRepository repository;
    private ProjectAiMessageService service;
    private final List<ProjectAiMessage> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(ProjectAiMessageRepository.class);
        service = new ProjectAiMessageService(repository,
                new com.checkba.service.ai.ConversationIssuanceService(false, false),
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));
        rows.clear();
        AtomicLong idGen = new AtomicLong(0);
        when(repository.save(any(ProjectAiMessage.class))).thenAnswer(inv -> {
            ProjectAiMessage msg = inv.getArgument(0);
            if (msg.getId() == null) {
                msg.setId(idGen.incrementAndGet());
                rows.add(msg);
            }
            return msg;
        });
        when(repository.findById(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(m -> inv.getArgument(0).equals(m.getId())).findFirst());
        when(repository.findByConversationIdOrderByCreatedAtAsc(anyString())).thenAnswer(inv -> rows.stream()
                .filter(m -> inv.getArgument(0).equals(m.getConversationId()))
                .sorted(java.util.Comparator.comparing(ProjectAiMessage::getCreatedAt)
                        .thenComparing(ProjectAiMessage::getId))
                .toList());
        when(repository.findFirstByConversationIdAndClientRequestIdOrderByCreatedAtAscIdAsc(anyString(), anyString()))
                .thenAnswer(inv -> rows.stream()
                        .filter(m -> inv.getArgument(0).equals(m.getConversationId())
                                && inv.getArgument(1).equals(m.getClientRequestId()))
                        .min(java.util.Comparator.comparing(ProjectAiMessage::getCreatedAt)
                                .thenComparing(ProjectAiMessage::getId)));
    }

    /** 三轮问答；第二轮的提问与它的回复同刻落库（同毫秒 / MySQL 秒级截断都造得出）。 */
    private void seedThreeRounds() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 22, 10, 0, 0);
        row("conv-1", "USER", "第一问", "req-1", t);
        row("conv-1", "ASSISTANT", "第一答", null, t.plusSeconds(3));
        row("conv-1", "USER", "第二问", "req-2", t.plusSeconds(10));
        row("conv-1", "ASSISTANT", "第二答", null, t.plusSeconds(10));
        row("conv-1", "USER", "第三问", "req-3", t.plusSeconds(20));
        row("conv-1", "ASSISTANT", "第三答", null, t.plusSeconds(23));
    }

    private ProjectAiMessage row(String conv, String role, String content, String clientRequestId, LocalDateTime at) {
        ProjectAiMessage m = new ProjectAiMessage();
        m.setProjectId(1L);
        m.setUserId(9L);
        m.setRole(role);
        m.setContent(content);
        m.setConversationId(conv);
        m.setClientRequestId(clientRequestId);
        m.setCreatedAt(at);
        return repository.save(m);
    }

    private List<String> contentsOf(String conv) {
        return rows.stream().filter(m -> conv.equals(m.getConversationId()))
                .sorted(java.util.Comparator.comparing(ProjectAiMessage::getCreatedAt)
                        .thenComparing(ProjectAiMessage::getId))
                .map(ProjectAiMessage::getContent).toList();
    }

    private ProjectAiMessage head(String conv) {
        return rows.stream().filter(m -> conv.equals(m.getConversationId()))
                .min(java.util.Comparator.comparing(ProjectAiMessage::getId)).orElseThrow();
    }

    private Long idOf(String content) {
        return rows.stream().filter(m -> content.equals(m.getContent()) && "conv-1".equals(m.getConversationId()))
                .findFirst().orElseThrow().getId();
    }

    @Test
    @DisplayName("只复制到分叉点为止（含该条）——与目标同刻的那条助手回复不许跟过来")
    void branchCopiesOnlyThroughTheBranchPoint() {
        seedThreeRounds();

        String branch = service.forkFromMessage("conv-1", 9L, idOf("第二问"), null);

        assertEquals(List.of("第一问", "第一答", "第二问"), contentsOf(branch),
                "分叉点之后的内容跟过来，用户就是在新会话里重复一遍已经否掉的那条路");
    }

    @Test
    @DisplayName("原会话一个字不动——这正是分叉与回退的全部区别")
    void theOriginalConversationIsUntouched() {
        seedThreeRounds();
        List<String> before = contentsOf("conv-1");

        service.forkFromMessage("conv-1", 9L, idOf("第二问"), null);

        assertEquals(before, contentsOf("conv-1"));
        assertEquals(6, before.size());
    }

    @Test
    @DisplayName("父子字段落库：parentConversationId + branchFromMessageId 都写在首行")
    void branchRecordsLineage() {
        seedThreeRounds();
        Long target = idOf("第二问");

        String branch = service.forkFromMessage("conv-1", 9L, target, null);

        assertEquals("conv-1", head(branch).getParentConversationId());
        assertEquals(target, head(branch).getBranchFromMessageId());
    }

    @Test
    @DisplayName("标题是「<原标题> · 分支」——在近期对话里一眼看得出它从哪来")
    void branchTitleCarriesTheSuffix() {
        seedThreeRounds();
        head("conv-1").setConversationTitle("采购合同审查");

        String branch = service.forkFromMessage("conv-1", 9L, idOf("第二问"), null);

        assertEquals("采购合同审查 · 分支", head(branch).getConversationTitle());
    }

    @Test
    @DisplayName("定位键：本次会话内刚发出的气泡只有 clientRequestId，照样能分叉")
    void clientRequestIdLocatesTheBranchPoint() {
        seedThreeRounds();
        Long expected = idOf("第二问");

        String branch = service.forkFromMessage("conv-1", 9L, null, "req-2");

        assertEquals(List.of("第一问", "第一答", "第二问"), contentsOf(branch));
        assertEquals(expected, head(branch).getBranchFromMessageId());
    }

    @Test
    @DisplayName("定位不到就抛可读文案（控制器据此回 400，不是 500）")
    void unresolvableBranchPointFailsReadably() {
        seedThreeRounds();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.forkFromMessage("conv-1", 9L, null, "req-never-sent"));
        assertTrue(e.getMessage().contains("定位") || e.getMessage().contains("locate"), e.getMessage());
        assertEquals(6, contentsOf("conv-1").size(), "失败也不许动原会话");
    }

    @Test
    @DisplayName("连点两下分叉出两条互不覆盖的会话（conv-毫秒 撞号会让后一份吞掉前一份）")
    void twoBranchesInTheSameMillisecondDoNotCollide() {
        seedThreeRounds();

        String a = service.forkFromMessage("conv-1", 9L, idOf("第二问"), null);
        String b = service.forkFromMessage("conv-1", 9L, idOf("第二问"), null);

        assertNotEquals(a, b);
        assertEquals(3, contentsOf(a).size());
        assertEquals(3, contentsOf(b).size());
    }

    @Test
    @DisplayName("不给截断点时仍是整条复制——插件镜像会话「另起分支继续」那条路一行不改")
    void forkWithoutABranchPointStillCopiesEverything() {
        seedThreeRounds();

        String forked = service.forkConversation("conv-1", 9L);

        assertEquals(6, contentsOf(forked).size());
        assertNull(head(forked).getParentConversationId(), "整条复制没有分叉点，不记出身");
    }

    @Test
    @DisplayName("会话列表带上 parentConversationId 与父标题——分支在近期对话里要认得出来源")
    void conversationListCarriesTheBranchOrigin() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 22, 10, 0, 0);
        // [conversationId, updatedAt, lastContent, conversationTitle, firstUserMessage, sourceChannel,
        //  pinned, parentConversationId]
        when(repository.findConversationSummaries(1L, 9L)).thenReturn(List.<Object[]>of(
                new Object[]{"conv-branch", t, "分支里的回答", "采购合同审查 · 分支", "第二问", null, null, "conv-1"},
                new Object[]{"conv-1", t, "原对话的回答", "采购合同审查", "第一问", null, null, null}));
        when(repository.findConversationTitleCandidates(any())).thenReturn(List.<Object[]>of(
                new Object[]{"conv-1", "采购合同审查", "第一问"}));

        List<Map<String, Object>> list = service.listConversations(1L, 9L);

        Map<String, Object> branch = list.stream()
                .filter(m -> "conv-branch".equals(m.get("conversationId"))).findFirst().orElseThrow();
        assertEquals("conv-1", branch.get("parentConversationId"));
        assertEquals("采购合同审查", branch.get("parentTitle"));

        Map<String, Object> origin = list.stream()
                .filter(m -> "conv-1".equals(m.get("conversationId"))).findFirst().orElseThrow();
        assertNull(origin.get("parentConversationId"), "普通会话不长角标");
    }

    @Test
    @DisplayName("父会话还没起标题时角标回退到用户第一问，不把助手整段回答当标题")
    void missingParentTitleFallsBackToTheFirstUserMessage() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 22, 10, 0, 0);
        when(repository.findConversationSummaries(1L, 9L)).thenReturn(List.<Object[]>of(
                new Object[]{"conv-branch", t, "分支里的回答", "第一问 · 分支", "第二问", null, null, "conv-1"}));
        // 父会话的 LLM 标题还没落库（异步），只有用户第一条消息
        when(repository.findConversationTitleCandidates(any())).thenReturn(List.<Object[]>of(
                new Object[]{"conv-1", null, "第一问"}));

        Map<String, Object> branch = service.listConversations(1L, 9L).get(0);

        assertEquals("第一问", branch.get("parentTitle"),
                "与 fork 取标题同口径：没有 storedTitle 就用用户第一问");
    }
}
