package com.checkba.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

/**
 * TodoListService 归一化不变式测试：
 * 整表覆写、单一 in_progress、非法输入拒绝、防走神摘要生成。
 */
class TodoListServiceTest {

    private SseEmitterService sse;
    private TodoListService service;

    private static final String CONV = "conv-test-1";

    @BeforeEach
    void setUp() {
        sse = Mockito.mock(SseEmitterService.class);
        service = new TodoListService(sse, new ObjectMapper());
    }

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

    @Test
    void update_invalidStatus_fallsBackToPending() {
        service.update(CONV, "[{\"content\":\"A\",\"status\":\"doing\"}]");
        String reminder = service.reminder(CONV);
        assertNotNull(reminder);
        assertTrue(reminder.contains("待办：A"));
    }
}
