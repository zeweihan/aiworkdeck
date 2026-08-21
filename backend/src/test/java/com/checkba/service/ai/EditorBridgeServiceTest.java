package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EditorBridgeService 的分级超时表（dev-board#108）。
 *
 * <p>全文批量改稿（find_replace 150 命中实测 20s+、apply_house_style 30s+）与整文档
 * 装载/导出必须拿到比默认 30s 更长的预算，否则后端放弃等待、模型被告知失败后可能
 * 重发一次，而 worker 仍在继续改——「双改」。这里用反射锁住表里的值，构造器不动
 * （EvalHarness 免改）。
 */
class EditorBridgeServiceTest {

    @SuppressWarnings("unchecked")
    private Map<String, Integer> table() throws Exception {
        Field f = EditorBridgeService.class.getDeclaredField("ACTION_TIMEOUT_SECONDS");
        f.setAccessible(true);
        return (Map<String, Integer>) f.get(null);
    }

    @Test
    @DisplayName("批量改稿类 action 超时 120s，整文档装载/导出 180s")
    void batchActionsGetLongerTimeouts() throws Exception {
        Map<String, Integer> t = table();
        assertEquals(120, t.get("find_replace"));
        assertEquals(120, t.get("insert_table"));
        assertEquals(120, t.get("apply_house_style"));
        assertEquals(120, t.get("resolve_all_revisions"));
        assertEquals(180, t.get("doc_open_file_sync"));
        assertEquals(180, t.get("export_document"));
    }

    @Test
    @DisplayName("表外 action 仍是 30s 默认值")
    void defaultStaysThirtySeconds() throws Exception {
        assertEquals(30, EditorBridgeService.timeoutSecondsFor("get_selection"));
        assertEquals(120, EditorBridgeService.timeoutSecondsFor("find_replace"));
        assertEquals(30, EditorBridgeService.timeoutSecondsFor(null));
    }
}
