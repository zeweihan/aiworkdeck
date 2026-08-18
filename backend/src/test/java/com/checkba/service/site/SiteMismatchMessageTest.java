package com.checkba.service.site;

import com.checkba.service.LicenseService;
import com.checkba.service.account.AccountException;
import com.checkba.service.account.AccountService;
import com.checkba.service.account.AccountTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定站点错配时的错误文案（双主站设计 §2.6）。
 *
 * <p>国际站账户的 Key 粘到国内站，官网回 {@code 200 {valid:false}} / 账户端点回 401——
 * 桌面端会说「Key 无效或已被撤销」。**而这把 Key 是好的**：用户照着提示去官网重新生成一把，
 * 回来再撞一次同样的墙，且没有任何线索指向真正的原因。所以双站形态下文案必须点名站点。
 *
 * <p>同时守住那条更老的红线：文案不得含「登录」「未授权」「请先」三个子串，
 * 否则 {@code frontend/src/services/api.js} 会把业务错误当成掉线，清掉本地会话。
 */
class SiteMismatchMessageTest {

    /** api.js 用来判定「未登录」的三个子串。业务错误撞上任何一个都会把用户踢出去。 */
    private static final String[] LOGOUT_MARKERS = {"登录", "未授权", "请先"};

    private static final String CN = "https://www.aiworkdeck.com";
    private static final String INTL = "https://www.workdeck.ai";

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    // ==================== 站点装配 ====================

    private SiteProfileService sites(boolean intlEnabled, String cnBaseUrl) {
        SiteProperties p = new SiteProperties();
        p.setDefaultSite("cn");
        SiteProperties.Site cn = new SiteProperties.Site();
        cn.setEnabled(true);
        cn.setDisplayName("AI WorkDeck 国内站");
        cn.setBaseUrl(cnBaseUrl);
        SiteProperties.Site intl = new SiteProperties.Site();
        intl.setEnabled(intlEnabled);
        intl.setDisplayName("AI WorkDeck International");
        intl.setBaseUrl(INTL);
        p.getSites().put("cn", cn);
        p.getSites().put("intl", intl);
        return new SiteProfileService(true, cnBaseUrl, "cn", tempDir.toString(), p);
    }

    /** 起一个回环 HTTP 桩（AccountEndpoint 对回环 http 开了口子，正是为了这类联调）。 */
    private String startVerifyKeyStub(String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/license/verify-key", exchange -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ==================== 解锁门 ====================

    @Test
    @DisplayName("单站形态：文案保持原样，不提站点（说了只会让人困惑）")
    void singleSiteMessageStaysPlain() throws Exception {
        String base = startVerifyKeyStub("{\"valid\":false}");
        LicenseService license = new LicenseService(true, sites(false, base), tempDir.toString(), true, "");

        String message = String.valueOf(license.activate("awdk_whatever").get("message"));

        assertTrue(message.contains("Key 无效"), message);
        assertFalse(message.contains("站点"), message);
        assertNoLogoutMarkers(message);
    }

    @Test
    @DisplayName("多站形态：点名当前站点与另一站，把「Key 坏了」的指控换成可执行的下一步")
    void multiSiteMessageNamesBothSites() throws Exception {
        String base = startVerifyKeyStub("{\"valid\":false}");
        LicenseService license = new LicenseService(true, sites(true, base), tempDir.toString(), true, "");

        String message = String.valueOf(license.activate("awdk_whatever").get("message"));

        assertTrue(message.contains("AI WorkDeck 国内站"), message);
        assertTrue(message.contains("AI WorkDeck International"), message);
        assertTrue(message.contains("切换站点后重试"), message);
        assertNoLogoutMarkers(message);
    }

    @Test
    @DisplayName("试用码的错误文案不受站点影响：离线验签与站点无关")
    void trialCodeMessageIsUnaffected() {
        LicenseService license = new LicenseService(true, sites(true, CN), tempDir.toString(), true, "");

        String message = String.valueOf(license.activate("AWD-T-NOT-A-REAL-CODE").get("message"));

        assertFalse(message.contains("站点"), message);
        assertNoLogoutMarkers(message);
    }

    // ==================== 账户连接 ====================

    @Test
    @DisplayName("账户端点 401：多站形态下同样点名站点")
    void accountUnauthorizedNamesBothSites() {
        AccountService account = new AccountService(sites(true, CN), tempDir.toString(),
                (method, url, bearer, body) -> new AccountTransport.Reply(401, "{}"));

        String message = assertThrows(AccountException.class,
                () -> account.connect("awdk_" + "x".repeat(43))).getMessage();

        assertTrue(message.contains("AI WorkDeck 国内站"), message);
        assertTrue(message.contains("AI WorkDeck International"), message);
        assertNoLogoutMarkers(message);
    }

    @Test
    @DisplayName("账户端点 401：单站形态保持原样")
    void accountUnauthorizedSingleSite() {
        AccountService account = new AccountService(sites(false, CN), tempDir.toString(),
                (method, url, bearer, body) -> new AccountTransport.Reply(401, "{}"));

        String message = assertThrows(AccountException.class,
                () -> account.connect("awdk_" + "x".repeat(43))).getMessage();

        assertFalse(message.contains("站点"), message);
        assertNoLogoutMarkers(message);
    }

    @Test
    @DisplayName("切站后账户请求当场打向新站的地址")
    void switchingRetargetsOutboundRequests() {
        SiteProfileService profiles = sites(true, CN);
        StringBuilder seen = new StringBuilder();
        AccountService account = new AccountService(profiles, tempDir.toString(),
                (method, url, bearer, body) -> {
                    seen.setLength(0);
                    seen.append(url);
                    return new AccountTransport.Reply(401, "{}");
                });

        assertThrows(AccountException.class, () -> account.connect("awdk_" + "x".repeat(43)));
        assertTrue(seen.toString().startsWith(CN), seen.toString());

        profiles.persistSelection("intl");
        assertThrows(AccountException.class, () -> account.connect("awdk_" + "x".repeat(43)));
        assertTrue(seen.toString().startsWith(INTL), seen.toString());
    }

    // ==================== 切站只清 account 票据 ====================

    @Test
    @DisplayName("deactivateAccountMode：account 票据清掉，trial 票据原样保留")
    void deactivateAccountModeKeepsTrialTicket() throws Exception {
        LicenseService license = new LicenseService(true, sites(true, CN), tempDir.toString(), true, "");

        java.nio.file.Files.writeString(tempDir.resolve("license.json"),
                "{\"mode\":\"trial\",\"code\":\"AWD-T-x\",\"activatedAt\":\"2026-08-08T00:00:00Z\","
                        + "\"lastVerifiedAt\":\"2026-08-08T00:00:00Z\"}");
        assertFalse(license.deactivateAccountMode(), "trial 票据不该被清");
        Map<String, Object> status = license.status();
        assertEquals(true, status.get("unlocked"));
        assertEquals("trial", status.get("mode"));

        java.nio.file.Files.writeString(tempDir.resolve("license.json"),
                "{\"mode\":\"account\",\"code\":\"awdk_x\",\"activatedAt\":\"2026-08-08T00:00:00Z\","
                        + "\"lastVerifiedAt\":\"2026-08-08T00:00:00Z\"}");
        assertTrue(license.deactivateAccountMode(), "account 票据必须清掉");
        assertEquals(false, license.status().get("unlocked"));
    }

    private static void assertNoLogoutMarkers(String message) {
        for (String marker : LOGOUT_MARKERS) {
            assertFalse(message.contains(marker),
                    "文案含「" + marker + "」会被 api.js 当成掉线清会话: " + message);
        }
    }
}
