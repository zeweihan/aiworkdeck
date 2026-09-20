// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.addin;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.addin.PaneRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/addin/panes 的 HTTP 面（dev-board#717）：心跳登记、未登录不登记、
 * 会话 id 不信任请求体、告别既收 JSON 也收 sendBeacon 发来的 text/plain
 * （令牌在 body.token 里，因为 sendBeacon 带不了自定义请求头）。
 */
class AddinPaneControllerTest {

    private PaneRegistry registry;
    private ProjectAiMessageService messageService;
    private MockMvc mvc;
    private MockedStatic<AuthController> auth;

    @BeforeEach
    void setUp() {
        registry = new PaneRegistry();
        messageService = mock(ProjectAiMessageService.class);
        when(messageService.canUseConversation(anyString(), anyLong())).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(
                new AddinPaneController(registry, new ObjectMapper(), messageService)).build();
        auth = mockStatic(AuthController.class);
        // Mockito 对 Long 包装类型的默认返回值是 0L 而不是 null，未登录分支必须显式桩成 null
        auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(null);
        auth.when(() -> AuthController.getUserIdFromSession("awdt_good")).thenReturn(7L);
    }

    @AfterEach
    void tearDown() {
        auth.close();
    }

    @Test
    void heartbeatRegistersPaneForSessionUser() throws Exception {
        mvc.perform(post("/api/addin/panes/heartbeat")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paneId\":\"p1\",\"host\":\"word\",\"family\":\"office\","
                                + "\"docName\":\"A.docx\",\"projectId\":11,\"conversationId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        PaneRegistry.PaneInfo p = registry.find(7L, "p1").orElseThrow();
        assertThat(p.userId()).isEqualTo(7L);
        assertThat(p.host()).isEqualTo("word");
        assertThat(p.family()).isEqualTo("office");
        assertThat(p.docName()).isEqualTo("A.docx");
        assertThat(p.projectId()).isEqualTo(11L);
        assertThat(p.conversationId()).isEqualTo("c1");
    }

    @Test
    void heartbeatToleratesStringAndGarbageProjectId() throws Exception {
        mvc.perform(post("/api/addin/panes/heartbeat")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paneId\":\"p1\",\"projectId\":\"12\",\"conversationId\":\"c1\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(registry.find(7L, "p1").orElseThrow().projectId()).isEqualTo(12L);

        // 项目 id 坏了不该让心跳整条 500：窗格照样登记，只是不带项目
        mvc.perform(post("/api/addin/panes/heartbeat")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paneId\":\"p2\",\"projectId\":\"abc\",\"conversationId\":\"c2\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(registry.find(7L, "p2").orElseThrow().projectId()).isNull();
    }

    @Test
    void heartbeatWithoutSessionIsRejectedAndNotRegistered() throws Exception {
        mvc.perform(post("/api/addin/panes/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paneId\":\"p1\",\"conversationId\":\"c1\"}"))
                .andExpect(jsonPath("$.code").value(4010));
        assertThat(registry.list(7L, null)).isEmpty();
    }

    /**
     * 登记簿是跨窗格下发唯一一个「会话 id 来自不可信输入」的入口：
     * OfficeBridgeService.executeOnPane 直接往 target.conversationId() 那条连接推 client_action，
     * 而结果回传那一闸对受害者自己的窗格是合法投递者。
     * 所以任何人都不能把**别人的** conversationId 登记到自己名下——与 OfficeResultController
     * 的 canUseConversation 同一条线。
     */
    @Test
    void heartbeatWithSomeoneElsesConversationIsRejectedAndNotRegistered() throws Exception {
        when(messageService.canUseConversation("victim-conv", 7L)).thenReturn(false);

        mvc.perform(post("/api/addin/panes/heartbeat")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paneId\":\"p1\",\"host\":\"word\",\"family\":\"office\","
                                + "\"docName\":\"x.docx\",\"conversationId\":\"victim-conv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));

        assertThat(registry.find(7L, "p1")).isEmpty();
        assertThat(registry.paneOfConversation(7L, "victim-conv")).isEmpty();
    }

    @Test
    void heartbeatWithoutConversationStillRegisters() throws Exception {
        // 窗格刚载入、会话还没签发：照样登记（它只是暂时不能被下发命令），
        // 别让「没有会话」与「会话不是你的」共用一条拒绝路径
        mvc.perform(post("/api/addin/panes/heartbeat")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paneId\":\"p1\",\"docName\":\"x.docx\"}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(registry.find(7L, "p1").orElseThrow().conversationId()).isNull();
    }

    @Test
    void byeViaSendBeaconTextPlainUsesBodyToken() throws Exception {
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("p1", 7L, "word", "office", "A.docx", 11L, "c1", 0));

        mvc.perform(post("/api/addin/panes/bye")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"paneId\":\"p1\",\"token\":\"awdt_good\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(registry.find(7L, "p1")).isEmpty();
    }

    @Test
    void byeViaJsonWithHeader() throws Exception {
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("p1", 7L, "word", "office", "A.docx", 11L, "c1", 0));

        mvc.perform(post("/api/addin/panes/bye")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paneId\":\"p1\"}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(registry.find(7L, "p1")).isEmpty();
    }

    @Test
    void byeWithBadTokenLeavesPaneAlone() throws Exception {
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("p1", 7L, "word", "office", "A.docx", 11L, "c1", 0));

        mvc.perform(post("/api/addin/panes/bye")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"paneId\":\"p1\",\"token\":\"awdt_bad\"}"))
                .andExpect(jsonPath("$.code").value(4010));

        assertThat(registry.find(7L, "p1")).isPresent();
    }

    @Test
    void byeWithMalformedBodyIsAPlainErrorNotA500() throws Exception {
        mvc.perform(post("/api/addin/panes/bye")
                        .header("X-Session-Id", "awdt_good")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }
}
