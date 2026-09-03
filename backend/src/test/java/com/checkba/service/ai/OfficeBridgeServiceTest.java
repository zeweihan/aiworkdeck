package com.checkba.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * OfficeBridgeService（Office 插件桥）的请求-应答 / 超时 / 错误 JSON 行为测试。
 *
 * 桥与 EditorBridgeService 同构但完全独立：单名契约（tool=office_command）、
 * 独立超时常量、失败一律 {"error": ...}（ToolResult.success() 依赖该前缀判失败，
 * 防绿勾空转）。
 */
class OfficeBridgeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SseEmitterService sse;
    private OfficeBridgeService bridge;
    private final List<String> sentPayloads = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        sse = mock(SseEmitterService.class);
        bridge = new OfficeBridgeService(sse, objectMapper);
        sentPayloads.clear();
    }

    /** 让 SSE 下发即刻被"插件"处理：从 payload 里取 requestId 并回传结果 */
    private void autoRespond(boolean ok, Object data, String error) {
        doAnswer(inv -> {
            String payload = String.valueOf((Object) inv.getArgument(2));
            sentPayloads.add(payload);
            String requestId = objectMapper.readTree(payload).get("requestId").asText();
            bridge.completeOfficeAction(requestId, ok, data, error);
            return null;
        }).when(sse).send(any(), eq("client_action"), any());
    }

    @Test
    @DisplayName("请求-应答：下发 office_command 载荷，成功结果按数据 JSON 透传")
    void roundTripSuccess() throws Exception {
        autoRespond(true, Map.of("text", "第一条 合作范围"), null);

        String result = bridge.executeOfficeCommand("conv-1", "get_text", Map.of());

        assertEquals("第一条 合作范围", objectMapper.readTree(result).get("text").asText());
        // 载荷契约：tool 固定 office_command，携带 requestId/command/args/conversationId
        var payload = objectMapper.readTree(sentPayloads.get(0));
        assertEquals("office_command", payload.get("tool").asText());
        assertEquals("get_text", payload.get("command").asText());
        assertEquals("conv-1", payload.get("conversationId").asText());
        assertFalse(payload.get("requestId").asText().isBlank());
        assertTrue(payload.has("args"));
    }

    @Test
    @DisplayName("失败结果：返回 {\"error\": ...}，且被 ToolResult.success() 判为失败（防绿勾空转）")
    void failureBecomesErrorJson() throws Exception {
        autoRespond(false, null, "未找到目标文本 \"第三条\"");

        String result = bridge.executeOfficeCommand("conv-1", "replace_text",
                Map.of("searchText", "第三条", "replaceText", "第四条"));

        // 含引号的错误信息也必须是合法 JSON（Jackson 序列化而非手拼）
        assertEquals("未找到目标文本 \"第三条\"", objectMapper.readTree(result).get("error").asText());
        assertFalse(new ToolRegistry.ToolResult(result, null, true).success(),
                "错误 JSON 必须被 ToolResult 判为失败");
    }

    @Test
    @DisplayName("超时：插件不回传时返回超时错误 JSON，挂起表清理")
    void timeoutYieldsErrorJson() throws Exception {
        bridge.setTimeoutSecondsForTest(1);
        doAnswer(inv -> {
            sentPayloads.add(String.valueOf((Object) inv.getArgument(2)));
            return null; // 不回传
        }).when(sse).send(any(), eq("client_action"), any());

        String result = bridge.executeOfficeCommand("conv-1", "get_text", Map.of());

        assertTrue(objectMapper.readTree(result).get("error").asText().contains("超时"));
        String requestId = objectMapper.readTree(sentPayloads.get(0)).get("requestId").asText();
        assertNull(bridge.getPendingConversationId(requestId), "超时后挂起请求应被清理");
        assertFalse(new ToolRegistry.ToolResult(result, null, true).success());
    }

    @Test
    @DisplayName("分级超时：批量改写留够时间，其余命令仍是 30 秒（对齐 EditorBridgeService，dev-board#419）")
    void batchCommandGetsLongerTimeout() {
        // 平超时是「后端先放弃、模型重发一次造成双改」的成因（EditorBridgeService
        // 早在 dev-board#108 就按 action 分级）。批量原语必然跑得久，不分级等于自造重复写入。
        assertEquals(30, OfficeBridgeService.timeoutSecondsFor("replace_text"));
        assertEquals(30, OfficeBridgeService.timeoutSecondsFor("get_text"));
        assertEquals(30, OfficeBridgeService.timeoutSecondsFor(null), "Map.of 对 null 键抛 NPE，必须有兜底");
        assertTrue(OfficeBridgeService.timeoutSecondsFor("replace_batch") >= 120,
                "批量改写要留够时间");
        assertTrue(OfficeBridgeService.timeoutSecondsFor("apply_standard_format") >= 120,
                "整篇套用标准格式是逐段落笔，同样跑得久");
    }

    @Test
    @DisplayName("无会话上下文：直接返回错误 JSON，不下发任何指令")
    void missingConversationRejectedUpfront() throws Exception {
        String result = bridge.executeOfficeCommand(null, "get_text", Map.of());

        assertTrue(objectMapper.readTree(result).has("error"));
        org.mockito.Mockito.verifyNoInteractions(sse);
    }

    @Test
    @DisplayName("归属查询：挂起期间可查到 conversationId，未知 requestId 返回 null")
    void pendingConversationLookup() {
        doAnswer(inv -> {
            String payload = String.valueOf((Object) inv.getArgument(2));
            String requestId = objectMapper.readTree(payload).get("requestId").asText();
            assertEquals("conv-9", bridge.getPendingConversationId(requestId),
                    "挂起期间应能按 requestId 查到会话归属");
            bridge.completeOfficeAction(requestId, true, Map.of(), null);
            return null;
        }).when(sse).send(any(), eq("client_action"), any());

        bridge.executeOfficeCommand("conv-9", "get_selection", Map.of());

        assertNull(bridge.getPendingConversationId("no-such-request"));
    }

    @Test
    @DisplayName("失败但插件未给错误信息时，兜底文案仍是错误 JSON")
    void failureWithoutErrorMessageStillErrorJson() throws Exception {
        autoRespond(false, null, null);

        String result = bridge.executeOfficeCommand("conv-1", "search", Map.of("query", "试用期"));

        assertTrue(objectMapper.readTree(result).has("error"));
        assertFalse(new ToolRegistry.ToolResult(result, null, true).success());
    }
}
