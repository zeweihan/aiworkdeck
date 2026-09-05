package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

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
        assertEquals(120, t.get("apply_style_profile"));
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

    @Test
    @DisplayName("整段插入类 action 也要 120s：默认 30s 让长报告插入超时，模型被告知失败后重发一次（dev-board#464）")
    void bulkInsertActionsGetLongerTimeouts() throws Exception {
        Map<String, Integer> t = table();
        assertEquals(120, t.get("insert_at_cursor"));
        assertEquals(120, t.get("insert_under_heading"));
        assertEquals(120, t.get("replace_selection"));
        assertEquals(120, t.get("modify_paragraph"));
    }

    @Test
    @DisplayName("超时文案不再说「失败」：命令可能仍在执行、内容可能已写入，先读回确认再说（dev-board#464）")
    void timeoutTellsTheModelTheOutcomeIsUnknown() {
        String payload = EditorBridgeService.TIMEOUT_RESULT_JSON;
        assertNotEquals("{\"error\": \"操作超时。请确保编辑器已打开并可用。\"}", payload,
                "旧文案把「后端不再等」说成「没执行」，模型据此重发造成双改");
        assertTrue(payload.contains("可能已写入"), "必须点明内容可能已经落进文档");
        assertTrue(payload.contains("不要重发"), "必须明确禁止重发同一命令");
    }

    // ==== 本轮重复整段插入的确定性去重闸（dev-board#464）====
    // 提示词层面拦不住：超时被当成失败后模型会原样重发，用户看到同一份长报告以修订插了两遍。

    private static EditorBridgeService bridge() {
        return new EditorBridgeService(null, null, null);
    }

    /** 长度过闸（>= 200 字符）的一段正文。 */
    private static String longText(String seed) {
        return seed.repeat(200 / seed.length() + 1);
    }

    @Test
    @DisplayName("同一轮内同一 action、同一文档、同一长文本的第二次插入被拒绝，不再下发")
    void secondIdenticalBulkInsertInSameRunIsRejected() {
        EditorBridgeService b = bridge();
        Map<String, Object> params = Map.of("text", longText("核查报告正文"));

        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", params), "第一次必须放行");

        String rejection = b.duplicateInsertRejection("conv-1", "insert_at_cursor", params);
        assertNotNull(rejection, "第二次必须拦下");
        assertTrue(rejection.startsWith("{\"error\""), "要按结构化错误回给模型");
        assertTrue(rejection.contains("本轮已插入过"), "要说清楚是本轮重复插入");
        assertTrue(rejection.contains("确认"), "要指路先读回确认，而不是再试一次");
    }

    @Test
    @DisplayName("四个插入类 action 都进闸")
    void allBulkInsertActionsAreGated() {
        String body = longText("同一段正文");
        for (Map<String, Object> shot : List.of(
                Map.<String, Object>of("action", "insert_at_cursor", "text", body),
                Map.<String, Object>of("action", "replace_selection", "text", body),
                Map.<String, Object>of("action", "insert_under_heading", "headingText", "第三章", "content", body),
                Map.<String, Object>of("action", "modify_paragraph", "index", 7, "newText", body))) {
            EditorBridgeService b = bridge();
            String action = (String) shot.get("action");
            Map<String, Object> params = new java.util.HashMap<>(shot);
            params.remove("action");
            assertNull(b.duplicateInsertRejection("conv-1", action, params), action + " 第一次应放行");
            assertNotNull(b.duplicateInsertRejection("conv-1", action, params), action + " 第二次应拦下");
        }
    }

    @Test
    @DisplayName("短文本不进闸：逐条补编号、逐格填表这类重复插入是正常操作")
    void shortInsertsAreNotDeduplicated() {
        EditorBridgeService b = bridge();
        Map<String, Object> params = Map.of("text", "（一）");
        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", params));
        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", params), "短插入不该被拦");
    }

    @Test
    @DisplayName("内容不同、定位不同、文档不同都不算重复")
    void onlyByteIdenticalRepeatsAreRejected() {
        EditorBridgeService b = bridge();
        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", Map.of("text", longText("甲段"))));
        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", Map.of("text", longText("乙段"))),
                "内容不同不该被拦");

        String body = longText("同一段正文");
        assertNull(b.duplicateInsertRejection("conv-1", "insert_under_heading",
                Map.of("headingText", "第一章", "content", body)));
        assertNull(b.duplicateInsertRejection("conv-1", "insert_under_heading",
                Map.of("headingText", "第二章", "content", body)), "换了标题就是另一处插入");

        assertNull(b.duplicateInsertRejection("conv-1", "modify_paragraph", Map.of("index", 3, "newText", body)));
        assertNull(b.duplicateInsertRejection("conv-1", "modify_paragraph", Map.of("index", 9, "newText", body)),
                "换了段落号就是另一处修改");
    }

    @Test
    @DisplayName("换了文档不算重复：同一段标准条款插进两份合同是正常批量操作")
    void switchingActiveDocumentResetsTheGate() {
        EditorBridgeService b = bridge();
        Map<String, Object> params = Map.of("text", longText("标准保密条款"));

        b.noteActiveDocument("conv-1", 101L);
        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", params));
        assertNotNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", params), "同一文档内仍要拦");

        b.noteActiveDocument("conv-1", 202L);
        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", params), "换了文档应放行");
    }

    @Test
    @DisplayName("会话之间互不干扰；新一轮（新用户消息）重置闸门")
    void gateIsScopedToOneConversationAndOneRun() {
        EditorBridgeService b = bridge();
        Map<String, Object> params = Map.of("text", longText("核查结论"));

        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", params));
        assertNull(b.duplicateInsertRejection("conv-2", "insert_at_cursor", params), "别的会话不受影响");
        assertNotNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", params));

        b.clearForNewRun("conv-1");
        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", params), "新一轮应放行");
    }

    @Test
    @DisplayName("表外 action 与缺参一律放行，闸门不许误伤")
    void gateIgnoresEverythingElse() {
        EditorBridgeService b = bridge();
        assertNull(b.duplicateInsertRejection("conv-1", "find_replace", Map.of("findText", "甲方", "replaceText", "乙方")));
        assertNull(b.duplicateInsertRejection("conv-1", "find_replace", Map.of("findText", "甲方", "replaceText", "乙方")));
        assertNull(b.duplicateInsertRejection(null, "insert_at_cursor", Map.of("text", longText("无会话"))));
        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", null));
        assertNull(b.duplicateInsertRejection("conv-1", "insert_at_cursor", Map.of("index", 1)));
    }

    @Test
    @DisplayName("端到端：闸在 executeEditorCommand 里短路下发；编辑器明确报错的那次不留登记")
    void gateShortCircuitsDispatchAndForgetsDefiniteFailures() {
        SseEmitterService sse = mock(SseEmitterService.class);
        EditorBridgeService b = new EditorBridgeService(
                sse, new ObjectMapper(), mock(com.checkba.service.telemetry.TelemetryService.class));
        b.setCurrentConversationId("conv-1");

        List<String> sent = new ArrayList<>();
        AtomicReference<Boolean> workerOk = new AtomicReference<>(false);
        // 每条 client_action 都立刻回一条回执，免得测试真等满超时
        doAnswer(inv -> {
            String payload = inv.getArgument(2);
            sent.add(payload);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new ObjectMapper().readValue(payload, Map.class);
            String reqId = (String) m.get("requestId");
            if (workerOk.get()) b.completeEditorAction(reqId, "conv-1", true, Map.of("done", true), null);
            else b.completeEditorAction(reqId, "conv-1", false, null, "编辑器未就绪");
            return null;
        }).when(sse).send(anyString(), anyString(), anyString());

        Map<String, Object> params = Map.of("text", longText("核查报告正文"));

        String first = b.executeEditorCommand("insert_at_cursor", params);
        assertTrue(first.contains("编辑器未就绪"), "第一次应把编辑器的错误原样带回");
        int afterFirst = sent.size();

        String retryAfterFailure = b.executeEditorCommand("insert_at_cursor", params);
        assertTrue(retryAfterFailure.contains("编辑器未就绪"), "明确失败过的那次重试不该被闸拦");
        assertTrue(sent.size() > afterFirst, "重试必须真的下发了");

        workerOk.set(true);
        String applied = b.executeEditorCommand("insert_at_cursor", params);
        assertTrue(applied.contains("done"), "写成功后应返回 worker 的数据");
        int afterApplied = sent.size();

        String duplicate = b.executeEditorCommand("insert_at_cursor", params);
        assertTrue(duplicate.contains("本轮已插入过"), "写成功之后的原样重发必须被拦");
        assertEquals(afterApplied, sent.size(), "被拦下时一个 worker 命令都不许发");
    }
}
