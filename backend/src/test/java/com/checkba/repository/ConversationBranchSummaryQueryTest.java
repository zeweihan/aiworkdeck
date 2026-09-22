// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.ProjectAiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 面板会话列表查询的分叉出身列（dev-board#779 K18）。
 *
 * <p>两条查询在真库上跑一遍：{@code findConversationSummaries} 尾部新增的
 * parentConversationId（第七列），以及给「分支自 &lt;父标题&gt;」角标解析父标题的
 * {@code findConversationTitleCandidates}。上层那套 mock 仓储的服务测试证明不了
 * JPQL 本身能不能跑——标量子查询 + GROUP BY 的组合恰恰是最容易写错的地方。
 *
 * <p>环境：内存 H2（MODE=PostgreSQL）+ NON_KEYWORDS=VALUE，同 ProjectConversationSummaryQueryTest。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:conv-branch-summary-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ConversationBranchSummaryQueryTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 22, 10, 0, 0);

    @Autowired
    private ProjectAiMessageRepository repository;

    private void msg(String conversationId, String role, String content, String title,
                     String parentConversationId, Long branchFromMessageId, LocalDateTime createdAt) {
        ProjectAiMessage m = new ProjectAiMessage();
        m.setProjectId(1L);
        m.setUserId(7L);
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setConversationTitle(title);
        m.setParentConversationId(parentConversationId);
        m.setBranchFromMessageId(branchFromMessageId);
        m.setCreatedAt(createdAt);
        repository.save(m);
    }

    @BeforeEach
    void seed() {
        // 原会话：有 LLM 起的标题
        msg("c-origin", "USER", "请审查这份采购合同", "采购合同审查", null, null, BASE);
        msg("c-origin", "ASSISTANT", "已核对付款与违约条款", null, null, null, BASE.plusMinutes(1));
        // 从原会话第一条分叉出来的会话：出身写在首行（本仓没有 ai_conversation 表，
        // 会话级元数据一律挂首条消息）
        msg("c-branch", "USER", "请审查这份采购合同", "采购合同审查 · 分支", "c-origin", 1L, BASE.plusHours(1));
        // 父会话还没起标题的那条路（LLM 起标题是异步的）
        msg("c-untitled", "USER", "帮我看看这份股权转让协议", null, null, null, BASE.plusHours(2));
        msg("c-untitled-branch", "USER", "帮我看看这份股权转让协议", "帮我看看这份股权转让协议 · 分支",
                "c-untitled", 4L, BASE.plusHours(3));
    }

    @Test
    void 会话汇总尾部第八列是分叉出身_普通会话为空() {
        List<Object[]> rows = repository.findConversationSummaries(1L, 7L);

        // 列宽护栏：服务层按下标取值，加列只许追加在尾部。
        // 第七列是 pinned（dev-board#796），第八列才是分叉出身。
        assertEquals(8, rows.get(0).length);
        Object[] branch = rows.stream().filter(r -> "c-branch".equals(r[0])).findFirst().orElseThrow();
        Object[] origin = rows.stream().filter(r -> "c-origin".equals(r[0])).findFirst().orElseThrow();
        assertEquals("c-origin", branch[7], "分支会话的第八列是父会话 id");
        assertNull(origin[7], "普通会话恒为 null —— 不长「分支自」角标");
    }

    @Test
    void 批量解析父标题_一次查回而不是逐条() {
        List<Object[]> rows = repository.findConversationTitleCandidates(List.of("c-origin", "c-untitled"));

        assertEquals(2, rows.size());
        Object[] titled = rows.stream().filter(r -> "c-origin".equals(r[0])).findFirst().orElseThrow();
        assertEquals("采购合同审查", titled[1], "storedTitle 优先");
        assertEquals("请审查这份采购合同", titled[2], "用户第一问作为服务层的回退素材");

        Object[] untitled = rows.stream().filter(r -> "c-untitled".equals(r[0])).findFirst().orElseThrow();
        assertNull(untitled[1], "还没起标题");
        assertEquals("帮我看看这份股权转让协议", untitled[2]);
    }

    @Test
    void 查不到的父会话不进结果_调用方据此渲染成无标题而不是编一个() {
        List<Object[]> rows = repository.findConversationTitleCandidates(List.of("c-origin", "c-does-not-exist"));

        assertEquals(1, rows.size());
        assertEquals("c-origin", rows.get(0)[0]);
    }
}
