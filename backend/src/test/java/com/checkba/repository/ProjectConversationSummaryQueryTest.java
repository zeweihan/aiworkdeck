package com.checkba.repository;

import com.checkba.model.entity.AgentRunRecord;
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
 * 项目级会话汇总查询（概览页的 AI 对话历史列表层）。
 *
 * 与既有 findConversationSummaries 的唯一语义差别：去掉 userId 条件 —— 列表层按项目
 * 全员可见。正文层仍走 canUseConversation，本查询一行正文都不下发给外部。
 *
 * 游标是 (MAX(createdAt), conversationId) 两维：只用时间一维时，两个会话最后活跃时间
 * 相同就会在翻页时永久丢掉其中一条。「同一时刻落库的两个会话」那条用例专门钉这个。
 *
 * 环境：内存 H2（MODE=PostgreSQL）+ NON_KEYWORDS=VALUE。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-conv-summary-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectConversationSummaryQueryTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 8, 10, 0, 0);

    @Autowired
    private ProjectAiMessageRepository messageRepository;

    @Autowired
    private AgentRunRecordRepository runRecordRepository;

    private void msg(Long projectId, Long userId, String conversationId,
                     String role, String content, String title, LocalDateTime createdAt) {
        ProjectAiMessage m = new ProjectAiMessage();
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setConversationTitle(title);
        m.setCreatedAt(createdAt);
        messageRepository.save(m);
    }

    @BeforeEach
    void seed() {
        // 项目 1、会话 c-old：发起人 7 号，有 LLM 生成的标题
        msg(1L, 7L, "c-old", "USER", "股东会通知的届次对不对", "股东会材料核查", BASE);
        msg(1L, 7L, "c-old", "ASSISTANT", "已核对通知与决议的届次", null, BASE.plusMinutes(1));
        // 项目 1、会话 c-new：发起人 9 号（另一个人），无标题
        msg(1L, 9L, "c-new", "USER", "帮我起草一份股权转让协议", null, BASE.plusHours(1));
        // 项目 1、两个会话的最后活跃时间完全相同 —— 单字段游标会在这里丢数据
        msg(1L, 7L, "c-tie-a", "USER", "同一时刻落库的甲", null, BASE.plusHours(2));
        msg(1L, 7L, "c-tie-b", "USER", "同一时刻落库的乙", null, BASE.plusHours(2));
        // 项目 2：不能被项目 1 的查询捞到
        msg(2L, 7L, "c-other", "USER", "别的项目", null, BASE.plusHours(3));
    }

    @Test
    void 列出项目全部会话_不按发起人过滤_按最近活跃与会话id倒序() {
        List<Object[]> rows = messageRepository.findProjectConversationSummaries(1L, null, null);

        assertEquals(4, rows.size(), "项目 1 有四个会话，且不该按 userId 过滤掉别人发起的那些");
        assertEquals("c-tie-b", rows.get(0)[0], "同时刻的两个会话按 conversationId 倒序，b 在 a 前");
        assertEquals("c-tie-a", rows.get(1)[0]);
        assertEquals("c-new", rows.get(2)[0]);
        assertEquals("c-old", rows.get(3)[0]);
    }

    @Test
    void 行形状与类型固定为七列() {
        Object[] row = messageRepository.findProjectConversationSummaries(1L, null, null).get(3); // c-old

        // 第七列 sourceChannel 是 dev-board#298（插件对话镜像）加的：首条消息的来源通道，
        // 本地会话恒 null。列宽护栏跟着升——服务层按下标取值，加列只许追加在尾部。
        assertEquals(7, row.length);
        assertEquals("c-old", row[0]);
        assertInstanceOf(LocalDateTime.class, row[1], "updatedAt 必须是 LocalDateTime，服务层要直接强转");
        assertEquals(BASE.plusMinutes(1), row[1], "updatedAt = 该会话最后一条消息的时间");
        assertEquals("已核对通知与决议的届次", row[2], "lastContent = 最后一条消息正文");
        assertEquals("股东会材料核查", row[3], "conversationTitle = 最早那条非空标题");
        assertEquals("股东会通知的届次对不对", row[4], "firstUserMessage = 最早那条 USER 消息");
        assertInstanceOf(Long.class, row[5], "ownerUserId 必须是 Long，服务层要直接强转");
        assertEquals(7L, row[5]);
        assertNull(row[6], "本地会话的 sourceChannel 恒为 null（只有镜像导入的会话非空）");
    }

    @Test
    void 无标题会话的标题列为空_由服务层回退到清洗后的正文() {
        Object[] row = messageRepository.findProjectConversationSummaries(1L, null, null).get(2); // c-new
        assertNull(row[3], "c-new 没有 conversationTitle");
        assertEquals(9L, row[5], "发起人是 9 号，不是当前登录用户");
    }

    @Test
    void 复合游标_两个会话最后活跃时间完全相同时翻页一条都不丢() {
        List<Object[]> page1 = messageRepository.findProjectConversationSummaries(1L, null, null);
        LocalDateTime cursorAt = (LocalDateTime) page1.get(0)[1];
        String cursorId = (String) page1.get(0)[0];   // c-tie-b

        List<Object[]> page2 = messageRepository.findProjectConversationSummaries(1L, cursorAt, cursorId);

        assertEquals(3, page2.size(), "只应排除游标行本身");
        assertEquals("c-tie-a", page2.get(0)[0],
                "与游标同一时刻、conversationId 更小的那个会话必须还在 —— 单字段游标会把它永久丢掉");
        assertEquals("c-new", page2.get(1)[0]);
        assertEquals("c-old", page2.get(2)[0]);
    }

    @Test
    void 只传时间游标不传会话id时退化成严格小于_向后兼容() {
        List<Object[]> page = messageRepository.findProjectConversationSummaries(1L, BASE.plusHours(2), null);

        assertEquals(2, page.size(), "beforeId 缺失时第三个分支恒不成立，等于老的单字段行为");
        assertEquals("c-new", page.get(0)[0]);
        assertEquals("c-old", page.get(1)[0]);
    }

    @Test
    void 游标过滤_只回严格早于before的会话() {
        List<Object[]> page = messageRepository.findProjectConversationSummaries(1L, BASE.plusMinutes(30), null);

        assertEquals(1, page.size());
        assertEquals("c-old", page.get(0)[0], "c-new 与两个 tie 会话的最后活跃时间都晚于游标，应被排除");
    }

    @Test
    void 运行状态批量取_按会话id集合一次查完() {
        AgentRunRecord running = new AgentRunRecord();
        running.setConversationId("c-new");
        running.setStatus("RUNNING");
        running.setProjectId(1L);
        running.setUpdatedAt(BASE.plusHours(1));
        runRecordRepository.save(running);

        List<AgentRunRecord> found = runRecordRepository.findByConversationIdIn(List.of("c-new", "c-old"));

        assertEquals(1, found.size(), "c-old 没有运行记录，服务层据此给 null");
        assertEquals("c-new", found.get(0).getConversationId());
        assertEquals("RUNNING", found.get(0).getStatus());
    }

    @Test
    void 运行状态批量取_空集合不炸() {
        assertTrue(runRecordRepository.findByConversationIdIn(List.of()).isEmpty());
    }
}
