package com.checkba.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /api/license/verify-key 的状态码分类红线：**只有 401/403 才算「官网明确拒绝这把 Key」**。
 *
 * <p>AccountService.handle 的注释白纸黑字写着「401/403 才是明确的鉴权失败——与 PR-A
 * LicenseService 的判定同源」，但 LicenseService 自己把整个 400-499 都收进了 INVALID。
 * 而 INVALID 在启动复验里是要清空本地授权的（saveState(new State())），于是官网一个 429
 * 限流（发版后大批桌面端同时复验就会撞上）、一次 400 版本不匹配、一次 404 路径变更，
 * 都会把一个**正在付费**的用户的本地授权直接抹掉，下次启动弹付费墙。
 *
 * <p>用回环 http 起一个桩服务器（AccountEndpoint.requireSecure 对回环地址放行 http）
 * 打真实链路，而不是只测分类函数——要证明的是「授权没被清掉」这个用户可见结果。
 */
class LicenseServiceVerifyKeyStatusTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private volatile int replyStatus = 200;
    private volatile String replyBody = "{\"valid\":true}";
    private CountDownLatch hit;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        hit = new CountDownLatch(1);
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/license/verify-key", exchange -> {
            hits.incrementAndGet();
            byte[] out = replyBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(replyStatus, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            hit.countDown();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private LicenseService service() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new LicenseService(true,
                com.checkba.service.site.SiteProfileService.pinnedTo(base),
                tempDir.toString(), true, "");
    }

    /** 落一张「正在付费」的 account 票据。 */
    private void writeAccountState() throws Exception {
        String now = Instant.now().toString();
        Files.writeString(tempDir.resolve("license.json"),
                "{\"mode\":\"account\",\"code\":\"awdk_paying_user\",\"activatedAt\":\"" + now
                        + "\",\"lastVerifiedAt\":\"" + now + "\"}",
                StandardCharsets.UTF_8);
    }

    private String modeOnDisk() throws Exception {
        Path f = tempDir.resolve("license.json");
        if (!Files.exists(f)) return "none";
        String json = Files.readString(f, StandardCharsets.UTF_8);
        return json.contains("\"mode\":\"account\"") ? "account" : "none";
    }

    /** 等复验后台线程把结果落盘；返回时要么 mode 变了，要么等到超时。 */
    private void awaitReverify() throws Exception {
        assertTrue(hit.await(5, TimeUnit.SECONDS), "桩服务器没被请求到，链路没打通");
        for (int i = 0; i < 100 && "account".equals(modeOnDisk()); i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void rateLimitedVerifyMustNotWipeAPayingUsersLicense() throws Exception {
        replyStatus = 429;
        replyBody = "{\"error\":\"too many requests\"}";
        writeAccountState();

        service().reverifyOnStartup();
        awaitReverify();

        assertEquals("account", modeOnDisk(),
                "官网只是限流（429），却把正在付费用户的本地授权清掉了");
    }

    @Test
    void unexpectedClientErrorsMustNotWipeALicenseEither() throws Exception {
        for (int status : new int[]{400, 404, 408}) {
            replyStatus = status;
            replyBody = "{\"error\":\"nope\"}";
            hit = new CountDownLatch(1);
            writeAccountState();

            service().reverifyOnStartup();
            awaitReverify();

            assertEquals("account", modeOnDisk(),
                    "HTTP " + status + " 不是鉴权结论，不该清除本地授权");
        }
    }

    /** 反向对照：真正的鉴权拒绝仍然必须清除授权，别把护栏修成谁都拦不住。 */
    @Test
    void genuineAuthRejectionStillClearsTheLicense() throws Exception {
        replyStatus = 401;
        replyBody = "{\"error\":\"unauthorized\"}";
        writeAccountState();

        service().reverifyOnStartup();
        awaitReverify();

        assertEquals("none", modeOnDisk(),
                "401 是官网明确拒绝，本地授权必须清掉");
    }

    /** 200 {valid:false} 同样是明确拒绝。 */
    @Test
    void explicitInvalidBodyStillClearsTheLicense() throws Exception {
        replyStatus = 200;
        replyBody = "{\"valid\":false}";
        writeAccountState();

        service().reverifyOnStartup();
        awaitReverify();

        assertEquals("none", modeOnDisk(),
                "200 {valid:false} 是官网明确拒绝，本地授权必须清掉");
    }

    /** 激活时撞上限流，文案要说「连不上」，不能指控用户的 Key 无效。 */
    @Test
    void activationDuringRateLimitReportsNetworkNotInvalidKey() {
        replyStatus = 429;
        replyBody = "{\"error\":\"too many requests\"}";

        Map<String, Object> result = service().activate("awdk_paying_user");

        assertEquals(false, result.get("unlocked"));
        String message = String.valueOf(result.get("message"));
        assertFalse(message.contains("无效") || message.contains("撤销"),
                "限流被说成 Key 无效或已被撤销，等于指控一把好 Key: " + message);
    }
}
