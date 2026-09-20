// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.checkba.config.GlobalExceptionHandler;
import com.checkba.service.mobile.DesktopStreamService;
import com.checkba.service.mobile.MobileRelayStoreService;
import com.checkba.service.mobile.ReferenceRequestStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 桌面端门铃流与参考读取取件/回传的 HTTP 面（dev-board#718 #719）。
 *
 * <p>门铃流的线上形态（Spring 写的是 {@code event:ready}，冒号后没有空格）由这里钉住——
 * 桌面端的逐行解析按这个形态写。参考材料正文只在内存里过一遍：回传日志只许有 id、长度，
 * 不许有正文（最后一个用例还原病灶即转红：日志级别拉到 TRACE 并断言确实记了一行）。
 */
class MobileRefControllerTest {

    private DesktopStreamService stream;
    private ReferenceRequestStore store;
    private MobileRelayStoreService relayStore;
    private MockMvc mvc;
    private MockedStatic<AuthController> auth;

    @BeforeEach
    void setUp() {
        stream = new DesktopStreamService();
        store = new ReferenceRequestStore();
        relayStore = mock(MobileRelayStoreService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MobileRefController(stream, store, relayStore))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        auth = mockStatic(AuthController.class);
        // Mockito 对 Long 包装类型的默认返回值是 0L 而不是 null，未登录分支必须显式桩成 null
        auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(null);
        auth.when(() -> AuthController.getUserIdFromSession("awdt_seven")).thenReturn(7L);
        auth.when(() -> AuthController.getUserIdFromSession("awdt_eight")).thenReturn(8L);
    }

    @AfterEach
    void tearDown() {
        auth.close();
    }

    // ==================== 门铃流 ====================

    @Test
    void streamWithoutSessionIsBare401AndNotOnline() throws Exception {
        mvc.perform(get("/api/mobile/desktop/stream").param("deviceId", "dev1"))
                .andExpect(status().isUnauthorized());
        assertThat(stream.isOnline(7L, "dev1")).isFalse();
        verify(relayStore, never()).touchDevice(any(), any());
    }

    @Test
    void streamWithoutDeviceIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/mobile/desktop/stream").header("X-Session-Id", "awdt_seven"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void streamSendsReadyAndMarksDeviceOnline() throws Exception {
        MvcResult r = connect("awdt_seven", "dev1");

        assertThat(body(r)).contains("event:ready");
        assertThat(r.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(r.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(stream.isOnline(7L, "dev1")).isTrue();
        verify(relayStore).touchDevice(7L, "dev1");
    }

    @Test
    void nudgeIsWrittenToTheOpenStream() throws Exception {
        MvcResult r = connect("awdt_seven", "dev1");

        assertThat(stream.nudge(7L, "dev1", "ref")).isTrue();

        assertThat(body(r)).contains("event:nudge").contains("{\"kind\":\"ref\"}");
    }

    @Test
    void secondStreamSupersedesFirst() throws Exception {
        MvcResult first = connect("awdt_seven", "dev1");
        MvcResult second = connect("awdt_seven", "dev1");

        assertThat(body(first)).contains("event:superseded");
        assertThat(body(second)).doesNotContain("superseded");
        assertThat(stream.isOnline(7L, "dev1")).isTrue();
        assertThat(stream.nudge(7L, "dev1", "transfer")).isTrue();
        assertThat(body(second)).contains("{\"kind\":\"transfer\"}");
        assertThat(body(first)).doesNotContain("transfer");
    }

    // ==================== 取件 ====================

    @Test
    void requestsWithoutSessionIs4010() throws Exception {
        mvc.perform(get("/api/mobile/ref/requests").param("deviceId", "dev1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
    }

    @Test
    void requestsAreHandedOutOnceAndResultCompletesTheWaiter() throws Exception {
        var pending = store.submit(7L, "dev1", "READ", "42", "合同/A.docx", null);

        mvc.perform(get("/api/mobile/ref/requests").param("deviceId", "dev1")
                        .header("X-Session-Id", "awdt_seven"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].id").value(pending.id()))
                .andExpect(jsonPath("$.requests[0].kind").value("READ"))
                .andExpect(jsonPath("$.requests[0].projectKey").value("42"))
                .andExpect(jsonPath("$.requests[0].path").value("合同/A.docx"));

        mvc.perform(get("/api/mobile/ref/requests").param("deviceId", "dev1")
                        .header("X-Session-Id", "awdt_seven"))
                .andExpect(jsonPath("$.requests.length()").value(0));

        mvc.perform(post("/api/mobile/ref/" + pending.id() + "/result")
                        .header("X-Session-Id", "awdt_seven")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ok\":true,\"text\":\"正文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.stale").doesNotExist());

        assertThat(pending.future().get(1, TimeUnit.SECONDS)).containsEntry("ok", true).containsEntry("text", "正文");
    }

    @Test
    void anotherUsersRequestsAreInvisible() throws Exception {
        store.submit(7L, "dev1", "LIST", "42", null, null);
        mvc.perform(get("/api/mobile/ref/requests").param("deviceId", "dev1")
                        .header("X-Session-Id", "awdt_eight"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.requests.length()").value(0));
    }

    @Test
    void requestsWithoutDeviceIdIsPlainError() throws Exception {
        mvc.perform(get("/api/mobile/ref/requests").header("X-Session-Id", "awdt_seven"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    // ==================== 回传 ====================

    @Test
    void resultForAnotherUsersRequestIs403AndLeavesItPending() throws Exception {
        var pending = store.submit(7L, "dev1", "READ", "42", "a.txt", null);

        mvc.perform(post("/api/mobile/ref/" + pending.id() + "/result")
                        .header("X-Session-Id", "awdt_eight")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ok\":true,\"text\":\"冒充\"}"))
                .andExpect(status().isForbidden());

        assertThat(pending.future()).isNotDone();
    }

    @Test
    void staleResultIsAcknowledgedAsStale() throws Exception {
        mvc.perform(post("/api/mobile/ref/no-such-request/result")
                        .header("X-Session-Id", "awdt_seven")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ok\":true,\"text\":\"t\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.stale").value(true));
    }

    @Test
    void resultWithoutSessionIs4010() throws Exception {
        var pending = store.submit(7L, "dev1", "READ", "42", "a.txt", null);
        mvc.perform(post("/api/mobile/ref/" + pending.id() + "/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ok\":true,\"text\":\"t\"}"))
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
        assertThat(pending.future()).isNotDone();
    }

    @Test
    void resultTextNeverReachesTheLogs() throws Exception {
        String secret = "机密正文-不许进日志-7f3a";
        var pending = store.submit(7L, "dev1", "READ", "42", "a.txt", null);

        List<ILoggingEvent> events = captureLogs(() ->
                mvc.perform(post("/api/mobile/ref/" + pending.id() + "/result")
                                .header("X-Session-Id", "awdt_seven")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ok\":true,\"text\":\"" + secret + "\"}"))
                        .andExpect(jsonPath("$.code").value(0)));

        List<String> messages = events.stream().map(ILoggingEvent::getFormattedMessage).toList();
        // 断言不能是空的：回传确实记了一行（带 id 与长度），只是那一行里没有正文
        assertThat(messages).anySatisfy(m -> assertThat(m).contains(pending.id()));
        assertThat(messages).noneSatisfy(m -> assertThat(m).contains(secret));
        assertThat(pending.future().get(1, TimeUnit.SECONDS)).containsEntry("text", secret);
    }

    // ==================== helpers ====================

    private MvcResult connect(String session, String deviceId) throws Exception {
        return mvc.perform(get("/api/mobile/desktop/stream").param("deviceId", deviceId)
                        .header("X-Session-Id", session))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static List<ILoggingEvent> captureLogs(ThrowingRunnable action) throws Exception {
        List<Logger> loggers = List.of(
                (Logger) LoggerFactory.getLogger(MobileRefController.class),
                (Logger) LoggerFactory.getLogger(ReferenceRequestStore.class));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        List<Level> previous = new ArrayList<>();
        for (Logger l : loggers) {
            previous.add(l.getLevel());
            l.setLevel(Level.TRACE);
            l.addAppender(appender);
        }
        try {
            action.run();
        } finally {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).detachAppender(appender);
                loggers.get(i).setLevel(previous.get(i));
            }
        }
        return new ArrayList<>(appender.list);
    }
}
