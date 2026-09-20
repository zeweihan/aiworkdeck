// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 案件库内部口的出站客户端（dev-board#720，spec §7.1）。
 *
 * <p>桩服务用 JDK 自带的 {@link HttpServer} 起在本机、回真实状态码与响应体：判据全在 HTTP 层
 * （请求路径、{@code X-Internal-Secret} 头、请求体形状、非 2xx 与 code!=0 的分流），
 * 用 mock 绕过去等于没测（与 HttpAccountDirectoryClientTest 同款）。
 *
 * <p>本类真正护的两条：
 * <ol>
 *   <li>没配 base-url / secret 就<b>一次请求都不发</b>（国际站与自建服务器的默认态）；</li>
 *   <li>案件库说得出原因的失败（{@code code!=0} + message）与传输故障必须分得开——
 *       前者原样转述给律师，后者只该说「暂时无法访问」。</li>
 * </ol>
 */
class CaseRefClientTest {

    private final ObjectMapper om = new ObjectMapper();

    /** 端口 1 恒连不上，用来制造"连得上地址、连不上服务"的网络失败。 */
    private static final String DEAD_BASE = "http://127.0.0.1:1";

    private HttpServer server;
    private String base;
    private final AtomicInteger stubStatus = new AtomicInteger(200);
    private final AtomicReference<String> stubBody = new AtomicReference<>("{}");
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastSecret = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastSecret.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            hits.incrementAndGet();
            byte[] out = stubBody.get() == null ? new byte[0] : stubBody.get().getBytes(StandardCharsets.UTF_8);
            if (out.length == 0) {
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

    private CaseRefClient stubbed(int status, String body) {
        stubStatus.set(status);
        stubBody.set(body);
        return new CaseRefClient(base, "s3cret", om);
    }

    // ==================== 未配置 ====================

    @Test
    void notConfiguredWithoutBaseUrl() {
        assertThat(new CaseRefClient("", "s3cret", om).configured()).isFalse();
    }

    @Test
    void notConfiguredWithoutSecret() {
        assertThat(new CaseRefClient(base, "  ", om).configured()).isFalse();
    }

    @Test
    void notConfiguredSendsNoRequestAtAll() {
        CaseRefClient client = new CaseRefClient("", "", om);

        assertThatThrownBy(() -> client.list("acc-1", null)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> client.read("acc-1", 3L, "a.txt")).isInstanceOf(IOException.class);
        assertThat(hits.get()).isZero();
    }

    // ==================== 请求形状 ====================

    @Test
    void listPostsAccountAndKeywordWithSecretHeader() throws Exception {
        CaseRefClient client = stubbed(200, "{\"code\":0,\"entries\":[]}");

        client.list("acc-1", "说明");

        assertThat(lastPath.get()).isEqualTo("/api/internal/ref/list");
        assertThat(lastSecret.get()).isEqualTo("s3cret");
        assertThat(om.readTree(lastBody.get()).path("externalAccountId").asText()).isEqualTo("acc-1");
        assertThat(om.readTree(lastBody.get()).path("keyword").asText()).isEqualTo("说明");
    }

    @Test
    void listParsesEntries() throws Exception {
        CaseRefClient client = stubbed(200, "{\"code\":0,\"entries\":["
                + "{\"remoteProjectId\":3,\"projectName\":\"王某诉李某\",\"path\":\"资料/说明.txt\",\"name\":\"说明.txt\"}]}");

        List<CaseRefClient.Entry> entries = client.list("acc-1", null);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).remoteProjectId()).isEqualTo(3L);
        assertThat(entries.get(0).projectName()).isEqualTo("王某诉李某");
        assertThat(entries.get(0).path()).isEqualTo("资料/说明.txt");
        assertThat(entries.get(0).name()).isEqualTo("说明.txt");
    }

    @Test
    void readPostsProjectAndPathAndReturnsText() throws Exception {
        CaseRefClient client = stubbed(200, "{\"code\":0,\"text\":\"说明正文\"}");

        assertThat(client.read("acc-1", 3L, "资料/说明.txt")).isEqualTo("说明正文");
        assertThat(lastPath.get()).isEqualTo("/api/internal/ref/read");
        assertThat(om.readTree(lastBody.get()).path("remoteProjectId").asLong()).isEqualTo(3L);
        assertThat(om.readTree(lastBody.get()).path("path").asText()).isEqualTo("资料/说明.txt");
    }

    // ==================== 失败分流 ====================

    @Test
    void bareNotFoundIsPlainIoException() {
        CaseRefClient client = stubbed(404, null);

        // 案件库没配密钥 / 密钥不符 / nginx 兜底都是裸 404——这不是"没这个文件"，
        // 不能退化成一句会误导律师的业务话术
        assertThatThrownBy(() -> client.read("acc-1", 3L, "a.txt"))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(CaseRefClient.CaseRefException.class);
    }

    @Test
    void serverErrorIsPlainIoException() {
        CaseRefClient client = stubbed(500, "boom");

        assertThatThrownBy(() -> client.list("acc-1", null))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(CaseRefClient.CaseRefException.class);
    }

    @Test
    void businessFailureCarriesTheCaseSideMessage() {
        CaseRefClient client = stubbed(200, "{\"code\":1,\"message\":\"你没有这份案卷的读取权限。\"}");

        assertThatThrownBy(() -> client.read("acc-1", 3L, "a.txt"))
                .isInstanceOf(CaseRefClient.CaseRefException.class)
                .hasMessage("你没有这份案卷的读取权限。");
    }

    @Test
    void businessFailureWithoutMessageStillHasAReadableOne() {
        CaseRefClient client = stubbed(200, "{\"code\":1}");

        assertThatThrownBy(() -> client.read("acc-1", 3L, "a.txt"))
                .isInstanceOf(CaseRefClient.CaseRefException.class)
                .hasMessageContaining("案件库");
    }

    @Test
    void unparseableBodyIsPlainIoException() {
        CaseRefClient client = stubbed(200, "not json at all");

        assertThatThrownBy(() -> client.list("acc-1", null))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(CaseRefClient.CaseRefException.class);
    }

    @Test
    void networkFailureIsPlainIoException() {
        CaseRefClient client = new CaseRefClient(DEAD_BASE, "s3cret", om);

        assertThatThrownBy(() -> client.list("acc-1", null))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(CaseRefClient.CaseRefException.class);
    }
}
