// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 名录内部口的出站客户端（spec 2026-09-10 §5）：未配置绝不发请求、请求形状（头 + 恰好一个
 * 定位键）、两种响应形状的解析，以及**故障绝不退化成「没找到」**。
 *
 * <p>最后一条是本类真正护的东西：官网那侧未配 secret / nginx 兜底 / 反代抽风，回的都是
 * 404 或 5xx；把它读成 {@code found:false} 就会让律师看到「还没有人用这个手机号注册」——
 * 一句在故障时完全错误、还会引着他去催同事重新注册的话。
 *
 * <p>桩服务用 JDK 自带的 {@link HttpServer} 起在本机，回真实状态码与响应体：判据就在
 * HTTP 层，用 mock 绕过去等于没测（与 HttpMobileBillingClientTest 同款）。
 */
class HttpAccountDirectoryClientTest {

    private final ObjectMapper om = new ObjectMapper();

    /** 端口 1 恒连不上，用来制造"连得上地址、连不上服务"的网络失败。 */
    private static final String DEAD_BASE = "http://127.0.0.1:1";

    private static final String FOUND_BODY =
            "{\"found\":true,"
                    + "\"account\":{\"accountId\":\"acc-9f\",\"username\":\"lisi\",\"displayName\":\"李思\",\"phone\":\"13800138000\"},"
                    + "\"requester\":{\"teamId\":\"team-a\",\"firmId\":\"firm-1\"},"
                    + "\"candidate\":{\"teamId\":\"team-b\",\"firmId\":\"firm-1\"}}";

    private HttpServer server;
    private String base;
    private final AtomicInteger stubStatus = new AtomicInteger(200);
    private final AtomicReference<String> stubBody = new AtomicReference<>("{}");
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastSecret = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastSecret.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            hits.incrementAndGet();
            String body = stubBody.get();
            byte[] out = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (out.length == 0) {
                // -1 = 明确的"没有响应体"，即官网 env 未配 / secret 不符回的那种裸 404
                exchange.sendResponseHeaders(stubStatus.get(), -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(stubStatus.get(), out.length);
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private HttpAccountDirectoryClient stubbed(int status, String body) {
        stubStatus.set(status);
        stubBody.set(body);
        return new HttpAccountDirectoryClient(base, "s3cret", om);
    }

    // ==================== 未配置 ====================

    @Test
    @DisplayName("base-url / secret 任一为空：configured()=false，且一次请求都不发")
    void unconfiguredNeverGoesOut() {
        for (HttpAccountDirectoryClient c : new HttpAccountDirectoryClient[]{
                new HttpAccountDirectoryClient("", "", om),
                new HttpAccountDirectoryClient(base, "", om),
                new HttpAccountDirectoryClient(base, "   ", om),
                new HttpAccountDirectoryClient("", "s3cret", om)}) {
            assertFalse(c.configured());
            assertThrows(DirectoryUnavailableException.class,
                    () -> c.lookupByIdentifier(null, "13800138000"));
        }
        assertEquals(0, hits.get(), "未配置不许发请求");
    }

    @Test
    @DisplayName("两个键都给了才算配好")
    void bothKeysMakeItConfigured() {
        assertTrue(new HttpAccountDirectoryClient(base, "s3cret", om).configured());
    }

    // ==================== 请求形状 ====================

    @Test
    @DisplayName("按 identifier 查：POST {base}/api/internal/collab-directory + X-Internal-Secret，只带 identifier 一个定位键")
    void identifierLookupSendsExactlyOneLocator() {
        HttpAccountDirectoryClient c = stubbed(200, FOUND_BODY);

        c.lookupByIdentifier("acc-me", "13800138000");

        assertEquals("/api/internal/collab-directory", lastPath.get());
        assertEquals("s3cret", lastSecret.get());
        String sent = lastRequest.get();
        assertTrue(sent.contains("\"requesterAccountId\":\"acc-me\""), sent);
        assertTrue(sent.contains("\"identifier\":\"13800138000\""), sent);
        assertFalse(sent.contains("candidateAccountId"), sent);
    }

    @Test
    @DisplayName("按 candidateAccountId 查：只带 candidateAccountId，绝不同时带 identifier（同时给官网回 400）")
    void accountIdLookupSendsExactlyOneLocator() {
        HttpAccountDirectoryClient c = stubbed(200, FOUND_BODY);

        c.lookupByAccountId("acc-me", "acc-9f");

        String sent = lastRequest.get();
        assertTrue(sent.contains("\"candidateAccountId\":\"acc-9f\""), sent);
        assertFalse(sent.contains("\"identifier\""), sent);
    }

    /** 案件库上没桥接过的操作账号也要拿到可解释的拒绝理由，所以 requester 为空是合法请求。 */
    @Test
    @DisplayName("requesterAccountId 为空：显式上行 null，不是省掉这个键")
    void nullRequesterIsSentExplicitly() {
        HttpAccountDirectoryClient c = stubbed(200, "{\"found\":false,\"requester\":{\"teamId\":null,\"firmId\":null}}");

        c.lookupByIdentifier(null, "13800138000");

        assertTrue(lastRequest.get().contains("\"requesterAccountId\":null"), lastRequest.get());
    }

    // ==================== 响应解析 ====================

    @Test
    @DisplayName("found:true：账户四字段与双方归属都解回来")
    void parsesTheFoundShape() {
        DirectoryReply r = stubbed(200, FOUND_BODY).lookupByIdentifier("acc-me", "13800138000");

        assertTrue(r.found());
        assertEquals("acc-9f", r.account().accountId());
        assertEquals("lisi", r.account().username());
        assertEquals("李思", r.account().displayName());
        assertEquals("13800138000", r.account().phone());
        assertEquals(new OrgMembership("team-a", "firm-1"), r.requester());
        assertEquals(new OrgMembership("team-b", "firm-1"), r.candidate());
    }

    @Test
    @DisplayName("found:false：account 为 null，requester 仍然解回来（拒绝理由靠它算）")
    void parsesTheNotFoundShape() {
        DirectoryReply r = stubbed(200,
                "{\"found\":false,\"requester\":{\"teamId\":\"team-a\",\"firmId\":null}}")
                .lookupByIdentifier("acc-me", "13800138000");

        assertFalse(r.found());
        assertNull(r.account());
        assertEquals(new OrgMembership("team-a", null), r.requester());
        assertEquals(OrgMembership.NONE, r.candidate());
    }

    @Test
    @DisplayName("phone 可以为 null（官网只在有号时给），归属两个键也可以整块为 null")
    void nullsAreTolerated() {
        DirectoryReply r = stubbed(200,
                "{\"found\":true,\"account\":{\"accountId\":\"acc-9f\",\"username\":\"lisi\","
                        + "\"displayName\":\"李思\",\"phone\":null},"
                        + "\"requester\":null,\"candidate\":{\"teamId\":null,\"firmId\":null}}")
                .lookupByIdentifier("acc-me", "li@example.com");

        assertNull(r.account().phone());
        assertEquals(OrgMembership.NONE, r.requester());
        assertEquals(OrgMembership.NONE, r.candidate());
    }

    // ==================== 故障 ====================

    @Test
    @DisplayName("裸 404（官网未配 secret / nginx 兜底）：不可用，绝不是「没找到」")
    void bare404IsUnavailableNotNotFound() {
        assertThrows(DirectoryUnavailableException.class,
                () -> stubbed(404, null).lookupByIdentifier("acc-me", "13800138000"));
    }

    @Test
    @DisplayName("400 bad_request（两个定位键或体不合法）：不可用 + 落日志，不静默放行")
    void badRequestIsUnavailable() {
        assertThrows(DirectoryUnavailableException.class,
                () -> stubbed(400, "{\"error\":\"bad_request\"}").lookupByAccountId("acc-me", "acc-9f"));
    }

    @Test
    @DisplayName("5xx / 连不上 / 回一页 HTML：一律不可用")
    void serverErrorsAndNetworkFailuresAreUnavailable() {
        assertThrows(DirectoryUnavailableException.class,
                () -> stubbed(500, "{\"error\":\"internal\"}").lookupByIdentifier("acc-me", "13800138000"));
        assertThrows(DirectoryUnavailableException.class,
                () -> stubbed(200, "<html>404</html>").lookupByIdentifier("acc-me", "13800138000"));
        assertThrows(DirectoryUnavailableException.class,
                () -> new HttpAccountDirectoryClient(DEAD_BASE, "s3cret", om)
                        .lookupByIdentifier("acc-me", "13800138000"));
    }

    /** found:true 却没有 accountId 就没法建桥接用户，是上游坏了，不是"没这个人"。 */
    @Test
    @DisplayName("found:true 但缺 accountId：按上游故障处理")
    void foundWithoutAccountIdIsUnavailable() {
        assertThrows(DirectoryUnavailableException.class,
                () -> stubbed(200, "{\"found\":true,\"account\":{\"username\":\"lisi\"},"
                        + "\"requester\":{},\"candidate\":{}}").lookupByIdentifier("acc-me", "13800138000"));
    }
}
