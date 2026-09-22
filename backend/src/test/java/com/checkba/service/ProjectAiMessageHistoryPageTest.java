// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.ProjectAiMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史分页与上下文组装投影（dev-board#811 K31，审查 C-12）。
 *
 * <p>病灶：{@code GET /api/ai/history} 无分页，一条跑了几十轮、带大量工具过程的会话
 * 整条正文可以是几 MB，前端打开历史要一次性下载并正则解析全部；{@code assemble} 又每一轮
 * 把同一条会话全量读成实体（连 displayContent、会话标题、附件一起），而它只用 role 与
 * content 两列——这一段就卡在用户等待首 token 的关键路径上。
 */
class ProjectAiMessageHistoryPageTest {

    private ProjectAiMessageRepository repository;
    private ProjectAiMessageService service;
    private final List<ProjectAiMessage> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(ProjectAiMessageRepository.class);
        for (long i = 1; i <= 10; i++) {
            ProjectAiMessage m = new ProjectAiMessage();
            m.setId(i);
            m.setConversationId("conv-1");
            m.setRole(i % 2 == 1 ? "USER" : "ASSISTANT");
            m.setContent("第 " + i + " 条");
            rows.add(m);
        }
        // 真仓储的语义：conversationId 命中、id < before、按 id 倒序、取 pageable 的页大小
        when(repository.findPageBefore(anyString(), nullable(Long.class), any(Pageable.class)))
                .thenAnswer(inv -> {
                    String cid = inv.getArgument(0);
                    Long before = inv.getArgument(1);
                    Pageable pageable = inv.getArgument(2);
                    return rows.stream()
                            .filter(m -> m.getConversationId().equals(cid))
                            .filter(m -> before == null || m.getId() < before)
                            .sorted(Comparator.comparing(ProjectAiMessage::getId).reversed())
                            .limit(pageable.getPageSize())
                            .toList();
                });
        service = new ProjectAiMessageService(repository,
                mock(com.checkba.service.ai.ConversationIssuanceService.class),
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));
    }

    @Test
    @DisplayName("首页取最近 N 条，且按时间正序返回（与全量读同口径）")
    void theFirstPageIsTheMostRecentMessagesInReadingOrder() {
        List<ProjectAiMessage> page = service.listByConversationIdPage("conv-1", null, 4);

        assertEquals(4, page.size());
        assertEquals(List.of(7L, 8L, 9L, 10L), page.stream().map(ProjectAiMessage::getId).toList(),
                "页内必须是正序：前端直接按这个顺序往对话流里铺，倒过来会让整段历史反着读");
    }

    @Test
    @DisplayName("向上翻：before 传上一页里最早那条的 id，取到的是它之前的一页")
    void theNextPageGoesBackwardsFromTheEarliestRowOfThePreviousPage() {
        List<ProjectAiMessage> first = service.listByConversationIdPage("conv-1", null, 4);
        List<ProjectAiMessage> older = service.listByConversationIdPage("conv-1", first.get(0).getId(), 4);

        assertEquals(List.of(3L, 4L, 5L, 6L), older.stream().map(ProjectAiMessage::getId).toList());
        assertTrue(service.hasMoreBefore("conv-1", older.get(0).getId()), "前面还有 1、2 两条");

        List<ProjectAiMessage> oldest = service.listByConversationIdPage("conv-1", older.get(0).getId(), 4);
        assertEquals(List.of(1L, 2L), oldest.stream().map(ProjectAiMessage::getId).toList());
        assertFalse(service.hasMoreBefore("conv-1", oldest.get(0).getId()), "翻到头了就该说没有了");
    }

    @Test
    @DisplayName("一页装得下整条会话时 hasMore 为假，不会让前端一直往上拉空气")
    void aConversationThatFitsInOnePageReportsNoMore() {
        List<ProjectAiMessage> page = service.listByConversationIdPage("conv-1", null, 60);

        assertEquals(10, page.size());
        assertFalse(service.hasMoreBefore("conv-1", page.get(0).getId()));
    }

    @Test
    @DisplayName("limit 有上下限：0 当 1，超大值封顶，不让调用方一把拉穿整张表")
    void theLimitIsClamped() {
        assertEquals(1, service.listByConversationIdPage("conv-1", null, 0).size());
        assertEquals(10, service.listByConversationIdPage("conv-1", null, 100000).size());
    }

    @Test
    @DisplayName("上下文组装走两列投影，不查附件、不读整实体")
    void assemblyReadsTheProjectionAndNeverTouchesAttachments() {
        when(repository.findHistoryForAssembly("conv-1")).thenReturn(List.of(
                new ProjectAiMessageRepository.HistoryLine("USER", "看看这份合同"),
                new ProjectAiMessageRepository.HistoryLine("ASSISTANT", "已核对")));

        List<ProjectAiMessageRepository.HistoryLine> history = service.listHistoryForAssembly("conv-1");

        assertEquals(2, history.size());
        assertEquals("USER", history.get(0).role());
        assertEquals("看看这份合同", history.get(0).content());
        // 全量实体那条路（会顺手补一次附件查询）一次都不该被走到
        verify(repository, never()).findByConversationIdOrderByCreatedAtAsc(anyString());
    }
}
