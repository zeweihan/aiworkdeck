package com.checkba.service.ai;

import com.checkba.model.entity.AgentTodoList;
import com.checkba.repository.AgentTodoListRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TodoListService 测试：
 * ① 归一化不变式（整表覆写、单一 in_progress、非法输入拒绝、防走神摘要生成）；
 * ② 持久化契约（写透 DB、进程重启后按 conversationId 取回、DB 故障不阻断、冷清单清理）。
 */
class TodoListServiceTest {

    private static final String CONV = "conv-test-1";

    private SseEmitterService sse;
    private AgentTodoListRepository repository;
    /** conversationId -> 记录，模拟唯一约束下的 upsert 语义 */
    private Map<String, AgentTodoList> table;
    private TodoListService service;

    @BeforeEach
    void setUp() {
        sse = Mockito.mock(SseEmitterService.class);
        table = new LinkedHashMap<>();
        repository = fakeRepository();
        service = new TodoListService(sse, new ObjectMapper(), repository);
    }

    @SuppressWarnings("unchecked")
    private AgentTodoListRepository fakeRepository() {
        AgentTodoListRepository repo = Mockito.mock(AgentTodoListRepository.class);
        when(repo.findByConversationId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(table.get(inv.<String>getArgument(0))));
        when(repo.save(any(AgentTodoList.class))).thenAnswer(inv -> {
            AgentTodoList r = inv.getArgument(0);
            table.put(r.getConversationId(), r);
            return r;
        });
        when(repo.findByUpdatedAtBefore(any(LocalDateTime.class))).thenAnswer(inv -> {
            LocalDateTime cutoff = inv.getArgument(0);
            List<AgentTodoList> out = new ArrayList<>();
            for (AgentTodoList r : table.values()) {
                if (r.getUpdatedAt() != null && r.getUpdatedAt().isBefore(cutoff)) out.add(r);
            }
            return out;
        });
        doAnswer(inv -> {
            for (AgentTodoList r : (Iterable<AgentTodoList>) inv.getArgument(0)) {
                table.remove(r.getConversationId());
            }
            return null;
        }).when(repo).deleteAll(anyIterable());
        return repo;
    }

    // ---------- 归一化不变式 ----------

    @Test
    void update_validList_storesAndPushesPlanUpdate() {
        String json = "[{\"content\":\"修订第四条\",\"activeForm\":\"正在修订第四条\",\"status\":\"in_progress\"}," +
                "{\"content\":\"补充保密义务\",\"status\":\"pending\"}]";
        String result = service.update(CONV, json);

        assertTrue(result.contains("共 2 项"));
        assertTrue(service.hasList(CONV));
        verify(sse).send(eq(CONV), eq("plan_update"), anyString());
    }

    @Test
    void update_multipleInProgress_demotesExtras() {
        String json = "[{\"content\":\"A\",\"status\":\"in_progress\"}," +
                "{\"content\":\"B\",\"status\":\"in_progress\"}]";
        String result = service.update(CONV, json);

        assertTrue(result.contains("降级"));
        // 摘要里只出现一个进行中项（A）
        String reminder = service.reminder(CONV);
        assertNotNull(reminder);
        assertTrue(reminder.contains("进行中：A"));
        assertTrue(reminder.contains("待办：B"));
    }

    @Test
    void update_invalidJson_returnsError() {
        String result = service.update(CONV, "not json");
        assertTrue(result.startsWith("Error:"));
        assertFalse(service.hasList(CONV));
        assertTrue(table.isEmpty(), "被拒绝的输入不该落库");
    }

    @Test
    void update_emptyList_returnsError() {
        String result = service.update(CONV, "[]");
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void reminder_allCompleted_returnsNull() {
        service.update(CONV, "[{\"content\":\"A\",\"status\":\"completed\"}]");
        assertNull(service.reminder(CONV));
    }

    @Test
    void reminder_noList_returnsNull() {
        assertNull(service.reminder("conv-unknown"));
    }

    // ==== failed 项的防走神提醒（审计条目）====
    // 背景：reminder() 此前只认 completed 算"done"，failed 项永远不会自己变成 completed，
    // 于是 done < todos.size() 恒成立，提醒永远关不掉；且正文只列 in_progress/pending，
    // 完全不提 failed 项，模型看不出到底卡在哪一项。

    @Test
    @DisplayName("修复：全部项要么完成要么失败时，提醒应该停止（不能因为有 failed 项就永远关不掉）")
    void reminder_completedAndFailed_returnsNull() {
        service.update(CONV, "[{\"content\":\"A\",\"status\":\"completed\"},"
                + "{\"content\":\"B\",\"status\":\"failed\"}]");

        assertNull(service.reminder(CONV), "全部项都已是终态（完成或失败），不该再继续催");
    }

    @Test
    @DisplayName("修复：还有未完成项时，提醒正文要点名失败的那一项，不能只字不提")
    void reminder_withFailedAndPending_mentionsFailedItem() {
        service.update(CONV, "[{\"content\":\"A\",\"status\":\"failed\"},"
                + "{\"content\":\"B\",\"status\":\"pending\"}]");

        String reminder = service.reminder(CONV);

        assertNotNull(reminder, "还有 pending 项没解决，提醒不该消失");
        assertTrue(reminder.contains("已失败：A"), "正文应点名失败项，不能只字不提: " + reminder);
        assertTrue(reminder.contains("待办：B"));
    }

    @Test
    void update_invalidStatus_fallsBackToPending() {
        service.update(CONV, "[{\"content\":\"A\",\"status\":\"doing\"}]");
        String reminder = service.reminder(CONV);
        assertNotNull(reminder);
        assertTrue(reminder.contains("待办：A"));
    }

    // ---------- 持久化 ----------

    @Test
    void update_writesThroughToDatabase() {
        service.update(CONV, "[{\"content\":\"A\",\"status\":\"in_progress\"}," +
                "{\"content\":\"B\",\"status\":\"pending\"}]");

        AgentTodoList saved = table.get(CONV);
        assertNotNull(saved, "update 必须把清单写透 agent_todo_list");
        assertNotNull(saved.getUpdatedAt());
        // 存的是归一化之后的整表 JSON
        assertTrue(saved.getTodosJson().contains("\"content\":\"A\""));
        assertTrue(saved.getTodosJson().contains("\"status\":\"in_progress\""));
    }

    @Test
    void update_overwritesSameRow() {
        service.update(CONV, "[{\"content\":\"A\",\"status\":\"pending\"}]");
        service.update(CONV, "[{\"content\":\"A\",\"status\":\"completed\"}," +
                "{\"content\":\"B\",\"status\":\"in_progress\"}]");

        assertEquals(1, table.size(), "一个会话只保留最新一次覆写，不该越写越多行");
        assertTrue(table.get(CONV).getTodosJson().contains("\"content\":\"B\""));
    }

    @Test
    void restart_restoresListFromDatabase() {
        service.update(CONV, "[{\"content\":\"A\",\"status\":\"completed\"}," +
                "{\"content\":\"B\",\"status\":\"in_progress\"}," +
                "{\"content\":\"C\",\"status\":\"pending\"}]");

        // 新进程：内存 map 清零，只剩 DB
        SseEmitterService freshSse = Mockito.mock(SseEmitterService.class);
        TodoListService fresh = new TodoListService(freshSse, new ObjectMapper(), repository);

        assertTrue(fresh.hasList(CONV), "「继续」按钮要能接着上次的清单干，不能是假承诺");
        String reminder = fresh.reminder(CONV);
        assertNotNull(reminder);
        assertTrue(reminder.contains("1/3 完成"));
        assertTrue(reminder.contains("进行中：B"));
        assertTrue(reminder.contains("待办：C"));

        // /connect 重连也要能把进度卡重建出来
        fresh.resendToFrontend(CONV);
        verify(freshSse).send(eq(CONV), eq("plan_update"), anyString());
    }

    @Test
    void restart_unknownConversationQueriesDatabaseOnce() {
        // 负缓存：没用过 todo_write 的会话，reminder() 每次工具执行后都会被调，不能每次都打库
        assertNull(service.reminder("conv-never-used"));
        assertNull(service.reminder("conv-never-used"));
        assertFalse(service.hasList("conv-never-used"));

        verify(repository, times(1)).findByConversationId("conv-never-used");
    }

    @Test
    void update_dbFailureDoesNotBlockConversation() {
        // 用 doThrow 改写已有 stub：when(mock.save(any())) 会真的把 null 喂给上面的 answer
        doThrow(new RuntimeException("db down")).when(repository).save(any(AgentTodoList.class));

        String result = service.update(CONV, "[{\"content\":\"A\",\"status\":\"in_progress\"}]");

        assertFalse(result.startsWith("Error:"), "进度卡落库失败不该让模型收到错误");
        assertTrue(service.hasList(CONV), "内存快路径照常可用");
        verify(sse).send(eq(CONV), eq("plan_update"), anyString());
    }

    @Test
    void read_dbFailureDoesNotBlockConversation() {
        doThrow(new RuntimeException("db down")).when(repository).findByConversationId(anyString());

        assertNull(service.reminder(CONV));
        assertFalse(service.hasList(CONV));
        // 读失败不写负缓存，下次还有机会恢复
        verify(repository, atLeast(2)).findByConversationId(CONV);
    }

    @Test
    void purge_dropsColdListsAndEvictsMemory() {
        service.update(CONV, "[{\"content\":\"A\",\"status\":\"pending\"}]");
        service.update("conv-warm", "[{\"content\":\"B\",\"status\":\"pending\"}]");
        // 把一条记录改成过了保留期
        table.get(CONV).setUpdatedAt(LocalDateTime.now().minus(TodoListService.RETENTION).minusDays(1));

        service.purgeStaleLists();

        assertFalse(table.containsKey(CONV), "冷清单要删行");
        assertTrue(table.containsKey("conv-warm"), "没冷的不许动");
        // 内存条目也摘掉了：再读一次只会从 DB 查（查不到），不能返回被删掉的旧清单
        assertFalse(service.hasList(CONV));
        assertTrue(service.hasList("conv-warm"));
    }

    @Test
    void purge_dbFailureDoesNotThrow() {
        doThrow(new RuntimeException("db down")).when(repository).findByUpdatedAtBefore(any(LocalDateTime.class));
        assertDoesNotThrow(() -> service.purgeStaleLists());
    }
}
