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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回退（edit-and-resend）语义（dev-board#779 K1）。
 *
 * <p>改造前两侧语义相反：后端只删「严格晚于目标」的行、把目标留着（DTO 注释写的是
 * keep this one），前端却把目标一起从界面上抹掉并把正文回填输入框让用户改了重发。
 * 于是库里留下「原始提问 + 改过的提问」两条连着的 USER 行，刷新页面那条本以为撤销掉的
 * 提问会复活，而 ContextAssemblerService 的历史栈直接读库——模型会把旧要求也一起执行。
 *
 * <p>本测试把语义钉成前端那一侧：<b>目标连同其后一起删</b>，并且删之前先整条存档。
 */
@DisplayName("对话回退：edit-and-resend 语义 + 回退前自动存档")
class ProjectAiMessageRollbackTest {

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
        when(repository.findFirstByConversationId(anyString())).thenAnswer(inv -> rows.stream()
                .filter(m -> inv.getArgument(0).equals(m.getConversationId()))
                .min(java.util.Comparator.comparing(ProjectAiMessage::getCreatedAt)
                        .thenComparing(ProjectAiMessage::getId)));
        when(repository.findFirstByConversationIdAndClientRequestIdOrderByCreatedAtAscIdAsc(anyString(), anyString()))
                .thenAnswer(inv -> rows.stream()
                        .filter(m -> inv.getArgument(0).equals(m.getConversationId())
                                && inv.getArgument(1).equals(m.getClientRequestId()))
                        .min(java.util.Comparator.comparing(ProjectAiMessage::getCreatedAt)
                                .thenComparing(ProjectAiMessage::getId)));
        // 复合删除：目标及其后（显示顺序 = createdAt, id 的字典序）
        org.mockito.Mockito.doAnswer(inv -> {
            String conv = inv.getArgument(0);
            LocalDateTime ts = inv.getArgument(1);
            Long id = inv.getArgument(2);
            rows.removeIf(m -> conv.equals(m.getConversationId())
                    && (m.getCreatedAt().isAfter(ts) || (m.getCreatedAt().equals(ts) && m.getId() >= id)));
            return null;
        }).when(repository).deleteFromMessageOnwards(anyString(), any(LocalDateTime.class), anyLong());
    }

    /** 一条「用户问 → 助手答 → 用户再问 → 助手再答」的会话；第二轮用户消息与它的回复同刻落库。 */
    private void seedConversation() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 22, 10, 0, 0);
        row("conv-1", "USER", "第一问", "req-1", t);
        row("conv-1", "ASSISTANT", "第一答", null, t.plusSeconds(3));
        row("conv-1", "USER", "第二问", "req-2", t.plusSeconds(10));
        // 同刻：MySQL 秒级截断/同毫秒落库都会造出这种行，严格大于会让这条回复在回退后幸存
        row("conv-1", "ASSISTANT", "第二答", null, t.plusSeconds(10));
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

    private List<String> remaining(String conv) {
        return rows.stream().filter(m -> conv.equals(m.getConversationId()))
                .sorted(java.util.Comparator.comparing(ProjectAiMessage::getCreatedAt)
                        .thenComparing(ProjectAiMessage::getId))
                .map(ProjectAiMessage::getContent).toList();
    }

    private Long idOf(String content) {
        return rows.stream().filter(m -> content.equals(m.getContent()) && "conv-1".equals(m.getConversationId()))
                .findFirst().orElseThrow().getId();
    }

    @Test
    @DisplayName("回退后按会话读历史：目标与其后都不在（含与目标同刻的助手回复）")
    void truncateRemovesTargetAndEverythingAfterIt() {
        seedConversation();

        service.truncateHistory("conv-1", idOf("第二问"));

        assertEquals(List.of("第一问", "第一答"), remaining("conv-1"),
                "目标消息本身必须一起删——前端把正文回填输入框重发，留着它库里就有两条连着的 USER 行");
    }

    @Test
    @DisplayName("定位键：clientRequestId 能定位到本次会话内刚发出、前端拿不到主键的那条")
    void clientRequestIdLocatesTheRow() {
        seedConversation();

        Long resolved = service.resolveRollbackTarget("conv-1", null, "req-2");

        assertEquals("第二问", rows.stream().filter(m -> resolved.equals(m.getId()))
                .findFirst().orElseThrow().getContent());
    }

    @Test
    @DisplayName("定位键：两个都给不出可用值时抛可读中文，不许把 500 丢给用户")
    void unresolvableLocatorFailsReadably() {
        seedConversation();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.resolveRollbackTarget("conv-1", null, "req-never-sent"));
        assertTrue(e.getMessage().contains("定位") || e.getMessage().contains("locate"),
                "文案要说清是定位不到这条消息：" + e.getMessage());
    }

    @Test
    @DisplayName("定位键：别的会话的消息 id 不许用来删这条会话")
    void crossConversationTargetIsRejected() {
        seedConversation();
        ProjectAiMessage other = row("conv-other", "USER", "别人的问题", "req-x",
                LocalDateTime.of(2026, 9, 22, 11, 0, 0));

        assertThrows(IllegalArgumentException.class,
                () -> service.resolveRollbackTarget("conv-1", other.getId(), null));
    }

    @Test
    @DisplayName("回退前自动存档：先整条 fork 成「…· 回退前存档」，再截断；原路径一条不少")
    void rollbackArchivesBeforeTruncating() {
        seedConversation();
        rows.get(0).setConversationTitle("采购合同审查");

        String archived = service.rollbackWithArchive("conv-1", idOf("第二问"), null, 9L);

        assertNotNull(archived);
        assertNotEquals("conv-1", archived);
        assertEquals(List.of("第一问", "第一答", "第二问", "第二答"), remaining(archived),
                "存档必须是回退前的完整原路径——这正是回退要销毁的那一段");
        assertEquals(List.of("第一问", "第一答"), remaining("conv-1"));

        ProjectAiMessage head = rows.stream().filter(m -> archived.equals(m.getConversationId()))
                .min(java.util.Comparator.comparing(ProjectAiMessage::getId)).orElseThrow();
        assertTrue(head.getConversationTitle().contains("回退前存档"),
                "标题要让人一眼看出它是什么：" + head.getConversationTitle());
        assertTrue(head.getConversationTitle().startsWith("采购合同审查"));
    }

    @Test
    @DisplayName("还没起标题就回退：存档名取用户第一问，不是助手那整段回答")
    void archiveTitleFallsBackToTheFirstUserMessage() {
        // LLM 起标题是异步的，回退往往发生在它落库之前——这是最常见的那条路
        seedConversation();

        String archived = service.rollbackWithArchive("conv-1", idOf("第二问"), null, 9L);

        String title = rows.stream().filter(m -> archived.equals(m.getConversationId()))
                .min(java.util.Comparator.comparing(ProjectAiMessage::getId)).orElseThrow()
                .getConversationTitle();
        assertEquals("第一问 · 回退前存档", title,
                "取最后一条（助手整段回答）会在「近期对话」里被截断到连后缀都看不见");
    }

    @Test
    @DisplayName("fork 落库父子字段：parentConversationId + branchFromMessageId（K18 复用）")
    void forkRecordsLineage() {
        seedConversation();
        Long target = idOf("第二问");

        String archived = service.rollbackWithArchive("conv-1", target, null, 9L);

        ProjectAiMessage head = rows.stream().filter(m -> archived.equals(m.getConversationId()))
                .min(java.util.Comparator.comparing(ProjectAiMessage::getId)).orElseThrow();
        assertEquals("conv-1", head.getParentConversationId());
        assertEquals(target, head.getBranchFromMessageId());
    }

    @Test
    @DisplayName("存档失败就不截断：永不静默销毁——两步必须同生共死")
    void archiveFailureAbortsTheTruncation() {
        seedConversation();
        Long target = idOf("第二问");
        when(repository.save(any(ProjectAiMessage.class))).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.rollbackWithArchive("conv-1", target, null, 9L));
        assertEquals(List.of("第一问", "第一答", "第二问", "第二答"), remaining("conv-1"),
                "存档没成，原会话一条都不许少");
    }

    @Test
    @DisplayName("连续两次回退存出两条互不覆盖的存档（conv-毫秒 撞号会让后一份吞掉前一份）")
    void twoArchivesInTheSameMillisecondDoNotCollide() {
        seedConversation();
        String a1 = service.rollbackWithArchive("conv-1", idOf("第二问"), null, 9L);
        String a2 = service.rollbackWithArchive("conv-1", idOf("第一问"), null, 9L);

        assertNotEquals(a1, a2);
        assertEquals(4, remaining(a1).size());
        assertEquals(2, remaining(a2).size());
    }
}
