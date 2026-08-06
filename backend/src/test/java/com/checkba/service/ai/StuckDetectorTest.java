package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.checkba.service.ai.StuckDetector.Verdict.CIRCUIT_BREAK;
import static com.checkba.service.ai.StuckDetector.Verdict.INTERVENE;
import static com.checkba.service.ai.StuckDetector.Verdict.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原地打转检测：滑动窗口 + 先干预后熔断。
 *
 * <p>加固前是单槽 lastCallSignature，只认「A/A/A」；模型退化成「读 → 写 → 再读 → 再写」的
 * A/B/A/B 交替时守卫全程无感，一路空转到步数预算耗尽。
 */
class StuckDetectorTest {

    @Test
    @DisplayName("A/A/A：第 3 次同参重复先提醒，第 4 次才熔断")
    void identicalRepeatInterveneThenBreak() {
        StuckDetector d = new StuckDetector();

        assertEquals(OK, d.record("read_document", "{\"id\":1}"));
        assertEquals(OK, d.record("read_document", "{\"id\":1}"));
        assertEquals(INTERVENE, d.record("read_document", "{\"id\":1}"), "首次检出只提醒，工具照常执行");
        assertEquals(CIRCUIT_BREAK, d.record("read_document", "{\"id\":1}"), "没改就再来一次才拦");

        assertNotNull(d.lastPattern());
        assertTrue(d.lastPattern().contains("read_document"), "模式描述要点名工具");
    }

    @Test
    @DisplayName("A/B/A/B 交替：加固前完全无感，现在同样先提醒后熔断")
    void alternatingPatternIsDetected() {
        StuckDetector d = new StuckDetector();

        assertEquals(OK, d.record("doc_read", "{}"));
        assertEquals(OK, d.record("doc_write", "{\"t\":\"x\"}"));
        assertEquals(OK, d.record("doc_read", "{}"));
        assertEquals(INTERVENE, d.record("doc_write", "{\"t\":\"x\"}"), "第二个完整周期即检出");
        assertTrue(d.lastPattern().contains("doc_read") && d.lastPattern().contains("doc_write"));

        assertEquals(CIRCUIT_BREAK, d.record("doc_read", "{}"), "继续交替就熔断");
    }

    @Test
    @DisplayName("同一工具不同参数交替（分页反复横跳）也算打转")
    void alternatingSameToolDifferentArgs() {
        StuckDetector d = new StuckDetector();

        d.record("list_files", "{\"p\":1}");
        d.record("list_files", "{\"p\":2}");
        d.record("list_files", "{\"p\":1}");
        assertEquals(INTERVENE, d.record("list_files", "{\"p\":2}"));
        assertTrue(d.lastPattern().contains("两组参数"), d.lastPattern());
    }

    @Test
    @DisplayName("干预后模型换了思路：不再触发，也不因为窗口里的旧签名误伤")
    void compliantModelIsNotPunished() {
        StuckDetector d = new StuckDetector();

        d.record("read_document", "{\"id\":1}");
        d.record("read_document", "{\"id\":1}");
        assertEquals(INTERVENE, d.record("read_document", "{\"id\":1}"));

        assertEquals(OK, d.record("doc_replace_text", "{\"a\":\"b\"}"));
        assertEquals(OK, d.record("doc_save", "{}"));
        assertEquals(OK, d.record("doc_add_comment", "{}"));
    }

    @Test
    @DisplayName("正常的多样化调用序列全程不触发")
    void healthySequenceNeverTrips() {
        StuckDetector d = new StuckDetector();

        String[] tools = {"doc_list_project_files", "doc_open_file", "doc_read", "doc_insert_text",
                "doc_format_text", "doc_save", "todo_write", "doc_read"};
        for (String t : tools) {
            assertEquals(OK, d.record(t, "{}"), t);
        }
    }

    @Test
    @DisplayName("参数为 null 与空串同签名，不会因为空参把两次调用算成不同调用")
    void nullArgsAreNormalized() {
        StuckDetector d = new StuckDetector();

        d.record("scan_files", null);
        d.record("scan_files", "");
        assertEquals(INTERVENE, d.record("scan_files", null));
    }
}
