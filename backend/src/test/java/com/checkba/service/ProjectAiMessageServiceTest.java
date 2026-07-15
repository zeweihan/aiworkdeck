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

    @BeforeEach
    void setUp() {
        repository = mock(ProjectAiMessageRepository.class);
        service = new ProjectAiMessageService(repository);
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

    @Test
    void 项目ID非法时upsert返回null且不落库() {
        assertNull(service.upsertAssistantMessage(null, 2L, "conv-1", null, "内容"));
        assertNull(service.upsertAssistantMessage("not-a-number", 2L, "conv-1", null, "内容"));
        ArgumentCaptor<ProjectAiMessage> captor = ArgumentCaptor.forClass(ProjectAiMessage.class);
        verify(repository, never()).save(captor.capture());
    }
}
