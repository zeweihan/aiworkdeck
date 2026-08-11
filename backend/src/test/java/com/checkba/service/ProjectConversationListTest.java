package com.checkba.service;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.User;
import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.repository.ProjectAiMessageRepository;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 项目概览页的 AI 对话历史列表层。
 *
 * 钉死五件事：
 * 1. 标题/预览一律来自服务端既有的 cleanTitle/extractPreview/truncatePreview，前端不再清洗；
 * 2. 预览回退只在 extractPreview 返回空串时发生 —— 合法短回复（「已核对」）不许被替换掉；
 * 3. runStatus 读 agent_run_record 表，不读 AgentRunStateService 的内存 Map
 *    （内存态进程重启后全为 null，概览页铺开历史会整片显示无状态）；
 * 4. 分页游标是 (updatedAt, conversationId) 两维，nextBefore 与 nextBeforeId 成对给出；
 * 5. limit 服务端钳到 1..50。
 */
class ProjectConversationListTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 8, 10, 0, 12);

    private ProjectAiMessageRepository repository;
    private AgentRunRecordRepository runRecordRepository;
    private UserRepository userRepository;
    private ProjectAiMessageService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectAiMessageRepository.class);
        runRecordRepository = mock(AgentRunRecordRepository.class);
        userRepository = mock(UserRepository.class);
        // 字段声明顺序即构造器参数顺序（@RequiredArgsConstructor）
        service = new ProjectAiMessageService(
                repository,
                new com.checkba.service.ai.ConversationIssuanceService(false, false),
                runRecordRepository,
                userRepository);

        when(runRecordRepository.findByConversationIdIn(any())).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());
    }

    /** 行形状与 findProjectConversationSummaries 一致：6 列。 */
    private Object[] row(String conversationId, LocalDateTime updatedAt, String lastContent,
                         String title, String firstUserMessage, Long ownerUserId) {
        return new Object[]{conversationId, updatedAt, lastContent, title, firstUserMessage, ownerUserId};
    }

    private User user(Long id, String displayName, String username) {
        User u = new User();
        u.setId(id);
        u.setDisplayName(displayName);
        u.setUsername(username);
        return u;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> conversationsOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("conversations");
    }

    @Test
    void 有LLM标题时用它_预览走服务端清洗不带任何标签() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.<Object[]>of(
                row("c-a", BASE, "<thinking>先想一下</thinking><final>已核对通知与决议的届次</final>",
                        "股东会材料核查", "届次对不对", 7L)));

        Map<String, Object> result = service.listProjectConversations(1L, null, null, 20, 7L);
        Map<String, Object> item = conversationsOf(result).get(0);

        assertEquals("c-a", item.get("conversationId"));
        assertEquals("股东会材料核查", item.get("title"));
        assertEquals("已核对通知与决议的届次", item.get("lastMessage"),
                "必须是 extractPreview 的输出：只留 <final> 内容，thinking 与标签全剥掉");
        assertEquals("2026-08-08T10:00:12", item.get("updatedAt"), "updatedAt 是 ISO 串，不是数组也不是时间戳");
    }

    @Test
    void 无LLM标题时回退到cleanTitle_预览为空时回退到用户首条消息() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.<Object[]>of(
                // extractPreview 对以 const 开头的正文直接返回空串（ProjectAiMessageService:275 的
                // 「明显是代码」过滤），预览必须回退
                row("c-b", BASE, "const x = 1", null, "帮我起草一份股权转让协议", 9L)));

        Map<String, Object> item = conversationsOf(service.listProjectConversations(1L, null, null, 20, 9L)).get(0);

        assertEquals("const x = 1", item.get("title"), "无 conversationTitle 时标题走 cleanTitle");
        assertEquals("帮我起草一份股权转让协议", item.get("lastMessage"),
                "extractPreview 返回空串时回退到用户第一条消息");
    }

    @Test
    void 合法短回复不被回退掉_回退条件只判空串() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.<Object[]>of(
                row("c-short", BASE, "已核对", "标题", "这是用户问的很长的一句话", 7L)));

        Map<String, Object> item = conversationsOf(service.listProjectConversations(1L, null, null, 20, 7L)).get(0);

        assertEquals("已核对", item.get("lastMessage"),
                "回退条件只能是 isEmpty()；带上『长度不足 5』会把合法短回复替换成用户的提问");
    }

    @Test
    void 运行状态取自agent_run_record表_无记录为null() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                row("c-run", BASE.plusHours(1), "跑着呢", "标题", "问题", 7L),
                row("c-idle", BASE, "跑完了", "标题2", "问题2", 7L)));
        AgentRunRecord running = new AgentRunRecord();
        running.setConversationId("c-run");
        running.setStatus("RUNNING");
        when(runRecordRepository.findByConversationIdIn(any())).thenReturn(List.of(running));

        List<Map<String, Object>> items = conversationsOf(service.listProjectConversations(1L, null, null, 20, 7L));

        assertEquals("RUNNING", items.get(0).get("runStatus"));
        assertNull(items.get(1).get("runStatus"), "没有运行记录的会话 runStatus 为 null");
        verify(runRecordRepository, times(1)).findByConversationIdIn(any());
    }

    @Test
    void 发起人显示名优先displayName_为空回退username_查不到为null() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                row("c-1", BASE.plusHours(2), "内容1", "标题1", "问1", 7L),
                row("c-2", BASE.plusHours(1), "内容2", "标题2", "问2", 8L),
                row("c-3", BASE, "内容3", "标题3", "问3", 99L)));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(7L, "张三", "zhangsan"),
                user(8L, "  ", "lisi")));

        List<Map<String, Object>> items = conversationsOf(service.listProjectConversations(1L, null, null, 20, 7L));

        assertEquals("张三", items.get(0).get("ownerName"));
        assertEquals(Long.valueOf(7L), items.get(0).get("ownerUserId"));
        assertEquals("lisi", items.get(1).get("ownerName"), "displayName 空白时回退 username");
        assertNull(items.get(2).get("ownerName"), "用户查不到时为 null");
        verify(userRepository, times(1)).findAllById(any());
    }

    @Test
    void 有下一页时游标两个字段成对给出() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                row("c-1", BASE.plusHours(2), "a", "t1", "q1", 7L),
                row("c-2", BASE.plusHours(1), "b", "t2", "q2", 7L),
                row("c-3", BASE, "c", "t3", "q3", 7L)));

        Map<String, Object> result = service.listProjectConversations(1L, null, null, 2, 7L);

        assertEquals(2, conversationsOf(result).size(), "多取的第 3 条只用来判有没有下一页，不下发");
        assertEquals("2026-08-08T11:00:12", result.get("nextBefore"));
        assertEquals("c-2", result.get("nextBeforeId"),
                "游标是两维的，只给时间会在同时刻的两个会话上丢数据");
    }

    @Test
    void 没有下一页时两个游标字段都为null() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.<Object[]>of(
                row("c-1", BASE, "a", "t1", "q1", 7L)));

        Map<String, Object> result = service.listProjectConversations(1L, null, null, 20, 7L);

        assertEquals(1, conversationsOf(result).size());
        assertNull(result.get("nextBefore"));
        assertNull(result.get("nextBeforeId"));
    }

    @Test
    void 零会话时返回空数组而不是null() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of());

        Map<String, Object> result = service.listProjectConversations(1L, null, null, 20, 7L);

        assertNotNull(conversationsOf(result));
        assertTrue(conversationsOf(result).isEmpty());
        assertNull(result.get("nextBefore"));
        assertNull(result.get("nextBeforeId"));
        verify(runRecordRepository, never()).findByConversationIdIn(any());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void limit被钳到1到50之间() {
        List<Object[]> many = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(row("c-" + i, BASE.minusMinutes(i), "x", "t", "q", 7L));
        }
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(many);

        assertEquals(50, conversationsOf(service.listProjectConversations(1L, null, null, 999, 7L)).size(),
                "上钳到 50");
        assertEquals(1, conversationsOf(service.listProjectConversations(1L, null, null, 0, 7L)).size(),
                "下钳到 1");
        assertEquals(1, conversationsOf(service.listProjectConversations(1L, null, null, -5, 7L)).size(),
                "负数也钳到 1");
    }

    @Test
    void 两个游标参数原样透传给查询层() {
        LocalDateTime cursor = BASE.plusHours(3);
        when(repository.findProjectConversationSummaries(eq(1L), eq(cursor), eq("c-x"))).thenReturn(List.of());

        service.listProjectConversations(1L, cursor, "c-x", 20, 7L);

        verify(repository).findProjectConversationSummaries(1L, cursor, "c-x");
    }

    @Test
    void 会话项不含正文字段_列表层一行正文都不下发() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.<Object[]>of(
                row("c-a", BASE, "这是完整正文不该原样下发", "标题", "问题", 7L)));

        Map<String, Object> item = conversationsOf(service.listProjectConversations(1L, null, null, 20, 7L)).get(0);

        assertEquals(java.util.Set.of("conversationId", "title", "lastMessage", "updatedAt",
                        "runStatus", "ownerUserId", "ownerName"), item.keySet(),
                "键集合固定；正文层仍走 canUseConversation，列表层不许多出 content/messages 之类的键");
    }

    // ==================== 问题③：非本人会话不下发正文（spec §6.4） ====================

    @Test
    void 非本人会话_lastMessage置空_有storedTitle仍显示标题() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.<Object[]>of(
                row("c-other", BASE, "这是同事的对话正文，含文档原文与工具输出", "股东会材料核查", "问题", 9L)));

        // 调用者是 7L，会话发起人是 9L —— 不是同一个人
        Map<String, Object> item = conversationsOf(service.listProjectConversations(1L, null, null, 20, 7L)).get(0);

        assertNull(item.get("lastMessage"), "非本人发起的会话不下发正文预览");
        assertEquals("股东会材料核查", item.get("title"),
                "有 storedTitle 时仍然显示标题——标题/时间/发起人/状态属于列表层授权范围");
        assertEquals(9L, item.get("ownerUserId"));
    }

    @Test
    void 非本人会话_无storedTitle时给中性文案而不是从正文推标题() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.<Object[]>of(
                row("c-other", BASE, "这是同事的对话正文，不许被当成标题来源", null, "问题", 9L)));

        Map<String, Object> item = conversationsOf(service.listProjectConversations(1L, null, null, 20, 7L)).get(0);

        assertNull(item.get("lastMessage"));
        assertEquals("新对话", item.get("title"),
                "没有 storedTitle 时不许用 cleanTitle(正文) 从别人的对话正文推标题，只给中性文案");
    }

    @Test
    void 本人会话不受影响_lastMessage与title照旧从正文清洗() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.<Object[]>of(
                row("c-mine", BASE, "已核对通知与决议的届次", null, "问题", 7L)));

        Map<String, Object> item = conversationsOf(service.listProjectConversations(1L, null, null, 20, 7L)).get(0);

        assertEquals("已核对通知与决议的届次", item.get("lastMessage"), "自己发起的会话不受这条口径影响");
        assertEquals("已核对通知与决议的届次", item.get("title"),
                "自己的会话没有 storedTitle 时仍走 cleanTitle(正文)，这是既有行为，不受本次修复影响");
    }

    @Test
    void ownerUserId为null时按非本人处理_不下发正文() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.<Object[]>of(
                row("c-orphan", BASE, "归属不明的一行正文", "标题还在", "问题", null)));

        Map<String, Object> item = conversationsOf(service.listProjectConversations(1L, null, null, 20, 7L)).get(0);

        assertNull(item.get("lastMessage"), "ownerUserId 缺失时不能因为无法判定而放行正文，保守按非本人处理");
        assertEquals("标题还在", item.get("title"));
        assertNull(item.get("ownerUserId"));
    }
}
