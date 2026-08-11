package com.checkba.service;

import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.ProjectAiMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * AI 对话消息持久化的轮次隔离测试。
 *
 * 回归背景：旧实现在保存 ASSISTANT 消息时按"会话最后一条 ASSISTANT 距今 30 秒内则更新"
 * 判断轮次，用户两轮提问间隔小于 30 秒时，第二轮回复会原地覆盖第一轮回复，
 * 导致历史记录里上一轮的 AI 回复丢失。
 */
class ProjectAiMessageServiceTest {

    private ProjectAiMessageRepository repository;
    private ProjectAiMessageService service;
    /** 模拟 JPA：save 时给新实体分配自增 ID，并记录所有落库实体 */
    private final List<ProjectAiMessage> savedRows = new ArrayList<>();

    private com.checkba.service.ai.ConversationIssuanceService issuanceService;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectAiMessageRepository.class);
        // 默认配置：不强制签发、非 local-mode（与 application.yml 默认一致）
        issuanceService = new com.checkba.service.ai.ConversationIssuanceService(false, false);
        service = new ProjectAiMessageService(repository, issuanceService,
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));
        savedRows.clear();
        AtomicLong idGen = new AtomicLong(0);
        when(repository.save(any(ProjectAiMessage.class))).thenAnswer(inv -> {
            ProjectAiMessage msg = inv.getArgument(0);
            if (msg.getId() == null) {
                msg.setId(idGen.incrementAndGet());
                savedRows.add(msg);
            }
            return msg;
        });
        when(repository.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return savedRows.stream().filter(m -> id.equals(m.getId())).findFirst();
        });
    }

    @Test
    void 两轮回复各自落新行_即使间隔小于30秒() {
        // 第一轮：首次保存（existingMessageId = null）
        Long turn1Id = service.upsertAssistantMessage("1", 2L, "conv-1", null, "第一轮回复");
        // 第二轮紧接着开始（编排器每轮开始会清掉行 ID，再次传 null）
        Long turn2Id = service.upsertAssistantMessage("1", 2L, "conv-1", null, "第二轮回复");

        assertNotNull(turn1Id);
        assertNotNull(turn2Id);
        assertNotEquals(turn1Id, turn2Id, "两轮回复必须是两行，不允许覆盖");
        assertEquals(2, savedRows.size());
        assertEquals("第一轮回复", savedRows.get(0).getContent(), "第一轮回复内容不得被第二轮覆盖");
        assertEquals("第二轮回复", savedRows.get(1).getContent());
    }

    @Test
    void 同一轮内增量保存更新同一行不产生重复() {
        Long firstId = service.upsertAssistantMessage("1", 2L, "conv-1", null, "工具执行中的部分内容");
        Long secondId = service.upsertAssistantMessage("1", 2L, "conv-1", firstId, "完整的最终回复");

        assertEquals(firstId, secondId, "同一轮内的增量保存应更新同一行");
        assertEquals(1, savedRows.size(), "同一轮不应产生第二行");
        assertEquals("完整的最终回复", savedRows.get(0).getContent());
    }

    @Test
    void 传入的行ID不存在时退化为插入新行() {
        Long id = service.upsertAssistantMessage("1", 2L, "conv-1", 999L, "回复内容");

        assertNotNull(id);
        assertEquals(1, savedRows.size());
        assertEquals("回复内容", savedRows.get(0).getContent());
    }

    @Test
    void saveMessage总是插入新行_不再按时间窗口更新旧消息() {
        service.saveMessage("1", 2L, "conv-1", "ASSISTANT", "第一条");
        service.saveMessage("1", 2L, "conv-1", "ASSISTANT", "第二条");

        assertEquals(2, savedRows.size());
        assertEquals("第一条", savedRows.get(0).getContent());
        assertEquals("第二条", savedRows.get(1).getContent());
        // 不应再有"查最后一条同角色消息"之类的轮次猜测查询
        verify(repository, never()).findById(anyLong());
    }

    // ===== 契约 D：发送内容 ≠ 显示内容 =====

    @Test
    void 显示内容单独落列_模型看的content一字不动() {
        service.saveMessage("1", 2L, "conv-1", "USER",
                "已修订计划（共 3 处改动）：\n1. …\n2. …\n3. …", "已修订计划");

        assertEquals(1, savedRows.size());
        ProjectAiMessage row = savedRows.get(0);
        assertTrue(row.getContent().contains("共 3 处改动"), "模型看的那份必须带全细节");
        assertEquals("已修订计划", row.getDisplayContent(), "用户看的那份是一句人话");
    }

    /**
     * 「缺省 = 与今天行为完全一致」是这条通道的存量兼容前提：
     * 五参版本与空白显示内容都必须落 null，不许写空串占位——前端各端一律按
     * displayContent || content 回退，空串在不同客户端上的真假判断会分歧。
     */
    @Test
    void 未给显示内容时落null_空白也归一为null() {
        service.saveMessage("1", 2L, "conv-1", "USER", "普通消息");
        service.saveMessage("1", 2L, "conv-1", "USER", "普通消息", "   ");

        assertEquals(2, savedRows.size());
        assertNull(savedRows.get(0).getDisplayContent());
        assertNull(savedRows.get(1).getDisplayContent());
    }

    @Test
    void 项目ID非法时upsert返回null且不落库() {
        assertNull(service.upsertAssistantMessage(null, 2L, "conv-1", null, "内容"));
        assertNull(service.upsertAssistantMessage("not-a-number", 2L, "conv-1", null, "内容"));
        ArgumentCaptor<ProjectAiMessage> captor = ArgumentCaptor.forClass(ProjectAiMessage.class);
        verify(repository, never()).save(captor.capture());
    }

    /**
     * 回归：安全加固时把「会话可用」误用成「会话归属」，新会话（还没有任何消息）
     * 被判为无主，用户一进项目、AI 面板拉历史就 403，连带整个页面初始化中断
     * （app-e2e 表现为版本时间线不刷新，J10 整条链路失败）。
     * 两个语义必须分开：读写用可用性，破坏性操作用严格归属。
     */
    @Test
    void 新会话尚无消息时可用但不判定归属() {
        when(repository.findFirstByConversationId("conv-new")).thenReturn(Optional.empty());

        assertTrue(service.canUseConversation("conv-new", 7L), "新会话必须可用，否则每个新会话都被挡成 403");
        assertFalse(service.isConversationOwnedBy("conv-new", 7L), "无消息的会话没有归属人");
    }

    @Test
    void 已有消息的会话只有首条作者可用() {
        ProjectAiMessage first = new ProjectAiMessage();
        first.setUserId(7L);
        when(repository.findFirstByConversationId("conv-owned")).thenReturn(Optional.of(first));

        assertTrue(service.canUseConversation("conv-owned", 7L));
        assertFalse(service.canUseConversation("conv-owned", 8L), "别人的会话不可用");
        assertTrue(service.isConversationOwnedBy("conv-owned", 7L));
        assertFalse(service.isConversationOwnedBy("conv-owned", 8L));
    }

    @Test
    void 未登录一律不可用() {
        assertFalse(service.canUseConversation("conv-new", null));
        assertFalse(service.canUseConversation(null, 7L));
    }

    // ===== conversationId 服务端签发登记（2026-08 安全审计遗留：关掉空会话抢占窗口） =====

    @Test
    void 已签发的空会话只有签发对象可用_登记优先于空会话任何人可用() {
        String issued = issuanceService.issue(7L, 1L);
        when(repository.findFirstByConversationId(issued)).thenReturn(Optional.empty());

        assertTrue(service.canUseConversation(issued, 7L), "签发给谁就归谁");
        assertFalse(service.canUseConversation(issued, 8L),
                "首条消息落库前，其他登录用户不得再抢占已签发的会话");
    }

    @Test
    void 强制签发开启时_未登记的空会话被拒_已签发的可用() {
        com.checkba.service.ai.ConversationIssuanceService issuance =
                new com.checkba.service.ai.ConversationIssuanceService(true, false);
        ProjectAiMessageService enforcing = new ProjectAiMessageService(repository, issuance,
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));

        when(repository.findFirstByConversationId("conv-1754400000000")).thenReturn(Optional.empty());
        assertFalse(enforcing.canUseConversation("conv-1754400000000", 7L),
                "官方云配下客户端自造 ID 必须被拒，强制走签发端点");

        String issued = issuance.issue(7L, 1L);
        when(repository.findFirstByConversationId(issued)).thenReturn(Optional.empty());
        assertTrue(enforcing.canUseConversation(issued, 7L), "经签发的会话可用");
    }

    @Test
    void 强制签发开启时_已有消息的会话仍按DB归属判定_进程重启丢登记不影响历史() {
        ProjectAiMessageService enforcing = new ProjectAiMessageService(
                repository, new com.checkba.service.ai.ConversationIssuanceService(true, false),
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));
        ProjectAiMessage first = new ProjectAiMessage();
        first.setUserId(7L);
        when(repository.findFirstByConversationId("conv-old")).thenReturn(Optional.of(first));

        assertTrue(enforcing.canUseConversation("conv-old", 7L),
                "历史会话（登记已随进程重启丢失）必须仍可被归属者使用");
        assertFalse(enforcing.canUseConversation("conv-old", 8L));
    }

    @Test
    void localMode下恒不强制签发_桌面自造ID流程不变() {
        ProjectAiMessageService localMode = new ProjectAiMessageService(
                repository, new com.checkba.service.ai.ConversationIssuanceService(true, true),
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));
        when(repository.findFirstByConversationId("conv-1754400000000")).thenReturn(Optional.empty());

        assertTrue(localMode.canUseConversation("conv-1754400000000", 7L),
                "local-mode（单机免登、回环监听）不强制签发，前端自造 ID 照常可用");
    }
}
