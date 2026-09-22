// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.model.entity.ProjectAiMessageAttachment;
import com.checkba.repository.ProjectAiMessageAttachmentRepository;
import com.checkba.repository.ProjectAiMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息 ↔ 附件的持久关联（dev-board#793 K14 ④，长期原则 3「数据模型先于 UI」）。
 *
 * <p>病灶（审查 E-2）：附件正文是以 {@code <file>} 注入<b>当轮</b> system prompt 的，
 * 而 {@code project_ai_message} 只有单列 content、历史回放只重建文本。于是刷新页面后
 * 「我上一轮发过哪几份材料」在界面上一个字都没有，模型也无从知道那份文件的 fileId
 *（那个 id 只出现在上一轮的 system prompt 里，不在对话历史里）。
 */
class ProjectAiMessageAttachmentTest {

    private ProjectAiMessageRepository repository;
    private ProjectAiMessageAttachmentRepository attachmentRepository;
    private ProjectAiMessageService service;
    private final List<ProjectAiMessageAttachment> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(ProjectAiMessageRepository.class);
        attachmentRepository = mock(ProjectAiMessageAttachmentRepository.class);
        AtomicLong seq = new AtomicLong(100);
        when(repository.save(any(ProjectAiMessage.class))).thenAnswer(inv -> {
            ProjectAiMessage m = inv.getArgument(0);
            if (m.getId() == null) m.setId(seq.incrementAndGet());
            return m;
        });
        when(attachmentRepository.saveAll(anyList())).thenAnswer(inv -> {
            stored.addAll(inv.getArgument(0));
            return inv.getArgument(0);
        });
        // 附件仓储走 field 注入 + 测试 setter：本服务是 @RequiredArgsConstructor，
        // 往构造器里加第 5 个参数要牵动五处手工 new 的既有测试（类注释里对镜像服务也是这么办的）
        service = new ProjectAiMessageService(repository,
                mock(com.checkba.service.ai.ConversationIssuanceService.class),
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));
        service.setAttachmentRepositoryForTest(attachmentRepository);
    }

    @Test
    @DisplayName("落库 USER 消息时能拿到行 id，附件按它挂上去")
    void savingAUserMessageReturnsItsRowIdSoAttachmentsCanBeLinked() {
        Long messageId = service.saveMessage("88", 1L, "conv-1", "USER", "看看这份合同", null);

        assertNotNull(messageId, "拿不到行 id 就没法把附件挂上去");

        service.recordAttachments(messageId, List.of(
                new ProjectAiMessageService.AttachmentRecord("9", "合同.docx", "docx", "file", false),
                new ProjectAiMessageService.AttachmentRecord("11", "现场.png", "image", "image", true)));

        assertEquals(2, stored.size());
        assertTrue(stored.stream().allMatch(a -> messageId.equals(a.getMessageId())));
        assertTrue(stored.stream().anyMatch(a -> "image".equals(a.getKind()) && Boolean.TRUE.equals(a.getVisionUsed())));
        assertTrue(stored.stream().anyMatch(a -> "file".equals(a.getKind()) && Boolean.FALSE.equals(a.getVisionUsed())));
        // fileType 必须原样存下来：「重新生成」要按这份记录重建那一轮的 contextItems，
        // 而后端判图是「fileType 优先、缺失退回文件名后缀」的双判据，丢了它就丢了一半
        assertTrue(stored.stream().anyMatch(a -> "9".equals(a.getFileId()) && "docx".equals(a.getFileType())));
        assertTrue(stored.stream().anyMatch(a -> "11".equals(a.getFileId()) && "image".equals(a.getFileType())));
    }

    @Test
    @DisplayName("没有附件就不打库")
    void noAttachmentsMeansNoWrite() {
        service.recordAttachments(1L, List.of());
        service.recordAttachments(null, List.of(
                new ProjectAiMessageService.AttachmentRecord("9", "a.docx", "docx", "file", false)));
        verify(attachmentRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("按会话读历史时附件清单一并回带（历史回灌据此重建气泡下的附件 chip）")
    void historyCarriesItsAttachments() {
        ProjectAiMessage user = new ProjectAiMessage();
        user.setId(7L);
        user.setRole("USER");
        user.setContent("看看这份合同");
        ProjectAiMessage assistant = new ProjectAiMessage();
        assistant.setId(8L);
        assistant.setRole("ASSISTANT");
        assistant.setContent("<final>好的</final>");
        when(repository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(user, assistant));

        ProjectAiMessageAttachment row = new ProjectAiMessageAttachment();
        row.setMessageId(7L);
        row.setFileId("9");
        row.setName("合同.docx");
        row.setKind("file");
        row.setVisionUsed(false);
        when(attachmentRepository.findByMessageIdInOrderByIdAsc(anyList())).thenReturn(List.of(row));

        List<ProjectAiMessage> history = service.listByConversationId("conv-1");

        assertEquals(1, history.get(0).getAttachments().size());
        assertEquals("合同.docx", history.get(0).getAttachments().get(0).getName());
        assertTrue(history.get(1).getAttachments() == null || history.get(1).getAttachments().isEmpty(),
                "没有附件的消息不该凭空长出附件");
    }

    @Test
    @DisplayName("附件表打不通只 log，绝不掀翻这一轮对话")
    void attachmentWriteFailureNeverBreaksTheTurn() {
        when(attachmentRepository.saveAll(anyList())).thenThrow(new RuntimeException("db down"));
        service.recordAttachments(1L, List.of(
                new ProjectAiMessageService.AttachmentRecord("9", "a.docx", "docx", "file", false)));
        // 不抛即通过
    }

    @Test
    @DisplayName("读附件失败时历史照常返回（只是没有附件 chip）")
    void historyStillLoadsWhenAttachmentLookupFails() {
        ProjectAiMessage user = new ProjectAiMessage();
        user.setId(7L);
        user.setRole("USER");
        user.setContent("x");
        when(repository.findByConversationIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of(user));
        when(attachmentRepository.findByMessageIdInOrderByIdAsc(anyList()))
                .thenThrow(new RuntimeException("db down"));

        assertEquals(1, service.listByConversationId("conv-1").size());
    }
}
