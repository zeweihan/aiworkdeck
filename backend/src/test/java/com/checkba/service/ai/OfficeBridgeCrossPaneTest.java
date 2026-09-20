// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.addin.PaneRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 跨窗格下发（dev-board#717）：A 窗格会话里的 AI 把命令发到同账号的 B 窗格。
 *
 * 三条要守住的东西：
 * - SSE 空档（每轮结束后端都主动断流，窗格退避 1~30 秒才重连）照常下发，靠补发缓冲兜住；
 * - 命令发到 B 当前的会话上，载荷带 origin，B 据此强制修订、记修订记录；
 * - 挂起表登记的是 B 的会话，回传端点的归属校验因此按 B 做。
 */
class OfficeBridgeCrossPaneTest {

    private final ObjectMapper om = new ObjectMapper();
    private SseEmitterService sse;
    private OfficeBridgeService bridge;
    private final List<String> sent = new CopyOnWriteArrayList<>();

    private final PaneRegistry.PaneInfo target =
            new PaneRegistry.PaneInfo("B", 7L, "word", "office", "B.docx", 11L, "conv-b", 0);
    private final OfficeBridgeService.CrossPaneOrigin origin =
            new OfficeBridgeService.CrossPaneOrigin("A", "A.docx", "conv-a");

    @BeforeEach
    void setUp() {
        sse = mock(SseEmitterService.class);
        bridge = new OfficeBridgeService(sse, om);
        sent.clear();
    }

    /**
     * 每轮回答结束后端都主动关掉 SSE 流，窗格要退避 1~30 秒才重连——一个开得好好的窗格
     * 在这段空档里没有 emitter。本窗格的 executeOfficeCommand 从来不因此拒发：client_action
     * 进补发缓冲，重连时按 Last-Event-ID 补回去（dev-board#287）。跨窗格不能比它脆，
     * 更不能对用户说「请在该文档里打开 AI WorkDeck 窗格」——窗格就在他眼前开着。
     */
    @Test
    @DisplayName("目标窗格在 SSE 空档里：照常下发（交给补发缓冲），不谎称窗格没打开")
    void sseGapStillDispatchesInsteadOfClaimingThePaneIsClosed() throws Exception {
        doAnswer(inv -> {
            String payload = String.valueOf((Object) inv.getArgument(2));
            sent.add(payload);
            // 空档里 send() 只入缓冲，窗格重连后才真正收到并回传
            bridge.completeOfficeAction(om.readTree(payload).get("requestId").asText(),
                    true, Map.of("text", "重连后补发执行"), null);
            return null;
        }).when(sse).send(eq("conv-b"), eq("client_action"), any());

        String out = bridge.executeOnPane(target, "get_text", Map.of(), origin);

        assertThat(om.readTree(out).get("text").asText()).isEqualTo("重连后补发执行");
        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("没有目标窗格：错误 JSON，不下发")
    void nullTargetFails() throws Exception {
        String out = bridge.executeOnPane(null, "get_text", Map.of(), origin);

        assertThat(om.readTree(out).has("error")).isTrue();
        assertThat(new ToolRegistry.ToolResult(out, null, true).success()).isFalse();
        verify(sse, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("目标窗格还没签发会话：没有可下发的通道，点名回话，不下发")
    void targetWithoutConversationFails() throws Exception {
        PaneRegistry.PaneInfo noConv =
                new PaneRegistry.PaneInfo("C", 7L, "word", "office", "C.docx", 11L, null, 0);

        String out = bridge.executeOnPane(noConv, "get_text", Map.of(), origin);

        assertThat(om.readTree(out).get("error").asText()).contains("C.docx");
        verify(sse, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("缺来源信息：拒绝下发——不带 origin 的写入到了 B 那边就不会被强制标修订")
    void missingOriginRejected() throws Exception {
        String out = bridge.executeOnPane(target, "replace_text", Map.of("searchText", "x"), null);

        assertThat(om.readTree(out).has("error")).isTrue();
        verify(sse, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("发往目标窗格当前会话，载荷带 origin；挂起期间归属登记为目标会话")
    void sendsToTargetConversationWithOrigin() throws Exception {
        List<String> pendingOwner = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            String payload = String.valueOf((Object) inv.getArgument(2));
            sent.add(payload);
            String requestId = om.readTree(payload).get("requestId").asText();
            pendingOwner.add(bridge.getPendingConversationId(requestId));
            bridge.completeOfficeAction(requestId, true, Map.of("text", "hi"), null);
            return null;
        }).when(sse).send(eq("conv-b"), eq("client_action"), any());

        String out = bridge.executeOnPane(target, "get_text", Map.of(), origin);

        assertThat(om.readTree(out).get("text").asText()).isEqualTo("hi");
        JsonNode payload = om.readTree(sent.get(0));
        assertThat(payload.get("tool").asText()).isEqualTo("office_command");
        assertThat(payload.get("command").asText()).isEqualTo("get_text");
        assertThat(payload.get("conversationId").asText()).isEqualTo("conv-b");
        JsonNode o = payload.get("origin");
        assertThat(o.get("paneId").asText()).isEqualTo("A");
        assertThat(o.get("docName").asText()).isEqualTo("A.docx");
        assertThat(o.get("conversationId").asText()).isEqualTo("conv-a");
        // OfficeResultController 按挂起表里的会话做归属校验：必须是 B 的会话
        assertThat(pendingOwner).containsExactly("conv-b");
        String requestId = payload.get("requestId").asText();
        assertThat(bridge.getPendingConversationId(requestId)).isNull();
    }

    @Test
    @DisplayName("跨窗格超时：沿用超时文案，挂起表清理")
    void crossPaneTimeoutClearsPending() throws Exception {
        bridge.setTimeoutSecondsForTest(1);
        doAnswer(inv -> {
            sent.add(String.valueOf((Object) inv.getArgument(2)));
            return null; // B 不回传
        }).when(sse).send(eq("conv-b"), eq("client_action"), any());

        String out = bridge.executeOnPane(target, "get_text", Map.of(), origin);

        assertThat(om.readTree(out).get("error").asText()).contains("超时");
        // OpenDocSource 按这个开头认出读取超时（dev-board#717）
        assertThat(om.readTree(out).get("error").asText()).startsWith(OfficeBridgeService.TIMEOUT_PREFIX);
        String requestId = om.readTree(sent.get(0)).get("requestId").asText();
        assertThat(bridge.getPendingConversationId(requestId)).isNull();
    }

    @Test
    @DisplayName("本窗格下发不带 origin 键：B 窗格靠有无 origin 区分跨文档写入")
    void ownPaneCommandCarriesNoOrigin() throws Exception {
        doAnswer(inv -> {
            String payload = String.valueOf((Object) inv.getArgument(2));
            sent.add(payload);
            bridge.completeOfficeAction(om.readTree(payload).get("requestId").asText(), true, Map.of(), null);
            return null;
        }).when(sse).send(any(), eq("client_action"), any());

        bridge.executeOfficeCommand("conv-a", "get_text", Map.of());

        assertThat(om.readTree(sent.get(0)).has("origin")).isFalse();
    }
}
