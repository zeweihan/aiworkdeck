package com.checkba.service;

import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.ProjectAiMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 插件对话镜像导入 + fork-from-here（dev-board#298）。
 *
 * 导入三不变式：幂等（sourceMessageId 撞上走更新不插行）、脏数据拒收（空白 content /
 * 坏 role——langchain4j 对空白消息抛异常，历史上能报废整条会话）、createdAt 严格递增
 * （历史回放只按 created_at ASC、无 id tiebreaker，同刻多条顺序未定义）。
 */
class ProjectAiMessageImportForkTest {

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
        when(repository.findByConversationIdAndSourceMessageId(anyString(), any())).thenAnswer(inv ->
                rows.stream().filter(m -> inv.getArgument(0).equals(m.getConversationId())
                        && inv.getArgument(1).equals(m.getSourceMessageId())).findFirst());
        when(repository.maxCreatedAtByConversationId(anyString())).thenAnswer(inv ->
                rows.stream().filter(m -> inv.getArgument(0).equals(m.getConversationId()))
                        .map(ProjectAiMessage::getCreatedAt)
                        .max(Comparator.naturalOrder()).orElse(null));
        when(repository.findByConversationIdOrderByCreatedAtAsc(anyString())).thenAnswer(inv ->
                rows.stream().filter(m -> inv.getArgument(0).equals(m.getConversationId()))
                        .sorted(Comparator.comparing(ProjectAiMessage::getCreatedAt))
                        .collect(java.util.stream.Collectors.toList()));
    }

    @Test
    @DisplayName("导入幂等：同一 sourceMessageId 重复投递走更新，不插重复行")
    void importIsIdempotentBySourceMessageId() {
        LocalDateTime ts = LocalDateTime.of(2026, 8, 30, 10, 0, 0);
        service.importExternalMessage(1L, 9L, "conv-x-abc", "USER", "第一版", null, "office-word", 11L, ts);
        service.importExternalMessage(1L, 9L, "conv-x-abc", "USER", "第二版", null, "office-word", 11L, ts);
        assertEquals(1, rows.size());
        assertEquals("第二版", rows.get(0).getContent());
        assertEquals("office-word", rows.get(0).getSourceChannel());
    }

    @Test
    @DisplayName("空白 content 与坏 role 拒收（返回 null、不落行）")
    void blankContentAndBadRoleAreRejected() {
        assertNull(service.importExternalMessage(1L, 9L, "conv-x", "USER", "   ", null, "office-word", 1L,
                LocalDateTime.now()));
        assertNull(service.importExternalMessage(1L, 9L, "conv-x", "SYSTEM", "内容", null, "office-word", 2L,
                LocalDateTime.now()));
        assertTrue(rows.isEmpty());
    }

    @Test
    @DisplayName("createdAt 严格递增：不晚于同会话现存最大时间戳的导入行被钳到 max+1ms")
    void createdAtIsClampedStrictlyIncreasing() {
        LocalDateTime ts = LocalDateTime.of(2026, 8, 30, 10, 0, 0);
        service.importExternalMessage(1L, 9L, "conv-x", "USER", "问", null, "office-word", 1L, ts);
        service.importExternalMessage(1L, 9L, "conv-x", "ASSISTANT", "答", null, "office-word", 2L, ts);
        assertEquals(2, rows.size());
        assertTrue(rows.get(1).getCreatedAt().isAfter(rows.get(0).getCreatedAt()),
                "同刻投递的第二条必须被钳到严格更晚，否则回放顺序未定义");
    }

    @Test
    @DisplayName("镜像判定：首条消息带 sourceChannel 即只读镜像；本地会话与分叉产物不是")
    void mirroredConversationIsDetectedByFirstMessage() {
        when(repository.findFirstByConversationId(anyString())).thenAnswer(inv ->
                rows.stream().filter(m -> inv.getArgument(0).equals(m.getConversationId()))
                        .min(Comparator.comparing(ProjectAiMessage::getCreatedAt)));
        service.importExternalMessage(1L, 9L, "conv-m", "USER", "问", null, "office-word", 1L,
                LocalDateTime.of(2026, 8, 30, 10, 0, 0));
        assertTrue(service.isMirroredConversation("conv-m"));
        String forked = service.forkConversation("conv-m", 9L);
        assertFalse(service.isMirroredConversation(forked), "分叉产物是普通本地会话，必须可写");
        assertFalse(service.isMirroredConversation("conv-nonexistent"));
    }

    @Test
    @DisplayName("fork：整条会话复制成新本地会话——标题带分支后缀、来源字段清空、原始时间保留")
    void forkCopiesConversationAsPlainLocal() {
        LocalDateTime ts = LocalDateTime.of(2026, 8, 30, 10, 0, 0);
        service.importExternalMessage(1L, 9L, "conv-x", "USER", "问", null, "wps-word", 1L, ts);
        service.importExternalMessage(1L, 9L, "conv-x", "ASSISTANT", "答", null, "wps-word", 2L, ts.plusSeconds(5));
        rows.get(0).setConversationTitle("合同审查");

        String forked = service.forkConversation("conv-x", 33L);

        assertTrue(forked.startsWith("conv-"));
        assertNotEquals("conv-x", forked);
        List<ProjectAiMessage> copies = rows.stream()
                .filter(m -> forked.equals(m.getConversationId())).toList();
        assertEquals(2, copies.size());
        assertEquals("合同审查（分支）", copies.get(0).getConversationTitle());
        assertNull(copies.get(0).getSourceChannel(), "分叉出来的是普通本地会话，不再是镜像");
        assertNull(copies.get(0).getSourceMessageId());
        assertEquals(33L, copies.get(0).getUserId(), "归属改成发起 fork 的用户，否则历史列表里看不见");
        assertEquals(ts, copies.get(0).getCreatedAt(), "原始时间保留，回放顺序不变");
    }
}
