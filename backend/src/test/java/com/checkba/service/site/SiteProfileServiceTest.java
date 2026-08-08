package com.checkba.service.site;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定站点解析的三级优先级与展示口径（双主站设计 §2.2 / §2.3）。
 *
 * 最要紧的一条是**存量兼容**：site.json 不存在时解析到默认站点，
 * 得到的地址与 application.yml 里今天写死的值逐字相同。
 */
class SiteProfileServiceTest {

    private static final String CN = "https://www.aiworkdeck.com";
    private static final String INTL = "https://www.workdeck.ai";

    @TempDir
    Path tempDir;

    private static SiteProperties props(boolean intlEnabled) {
        SiteProperties p = new SiteProperties();
        p.setDefaultSite("cn");
        SiteProperties.Site cn = new SiteProperties.Site();
        cn.setEnabled(true);
        cn.setDisplayName("AI Workdeck 国内站");
        cn.setBaseUrl(CN);
        cn.setRegistryBaseUrl(CN + "/api/registry");
        cn.setTelemetryIngestUrl(CN + "/api/telemetry");
        cn.setAccountPageUrl(CN + "/zh/account");
        SiteProperties.Site intl = new SiteProperties.Site();
        intl.setEnabled(intlEnabled);
        intl.setDisplayName("AI Workdeck International");
        intl.setBaseUrl(INTL);
        intl.setRegistryBaseUrl(INTL + "/api/registry");
        intl.setTelemetryIngestUrl(INTL + "/api/telemetry");
        intl.setAccountPageUrl(INTL + "/en/account");
        p.getSites().put("cn", cn);
        p.getSites().put("intl", intl);
        return p;
    }

    private SiteProfileService service(boolean localMode, String configuredBaseUrl,
                                       String resolvedSite, boolean intlEnabled) {
        return new SiteProfileService(localMode, configuredBaseUrl, resolvedSite,
                tempDir.toString(), props(intlEnabled));
    }

    @Test
    @DisplayName("site.json 不存在 → 默认站点，地址与今天逐字相同（存量兼容锚点）")
    void defaultsToCnWithIdenticalUrls() {
        SiteProfileService svc = service(true, CN, "", true);
        assertEquals("cn", svc.currentSite());
        assertEquals(CN, svc.baseUrl());
        assertEquals(CN + "/api/registry/plugins", svc.profile().pluginRegistryUrl());
        assertEquals(CN + "/api/registry/skills", svc.profile().skillRegistryUrl());
        assertEquals(CN + "/api/telemetry", svc.profile().telemetryIngestUrl());
        assertFalse(svc.isPinned());
    }

    @Test
    @DisplayName("属性层解析出 intl → baseUrl 当场指向国际站")
    void resolvedSiteDrivesBaseUrl() {
        SiteProfileService svc = service(true, INTL, "intl", true);
        assertEquals("intl", svc.currentSite());
        assertEquals(INTL, svc.baseUrl());
        assertEquals("AI Workdeck International", svc.displayName());
        assertFalse(svc.isPinned());
    }

    @Test
    @DisplayName("配置显式覆盖（本地联调 http://localhost）→ 钉住，baseUrl 恒用配置值且不可切站")
    void explicitOverridePinsTheSite() {
        // 环境变量 AI_ACCOUNT_BASE_URL 的优先级高于站点注入，此时有效基址与站点表对不上 = 被钉住
        SiteProfileService svc = service(true, "http://localhost:3000", "cn", true);
        assertTrue(svc.isPinned());
        assertEquals("http://localhost:3000", svc.baseUrl());
        assertFalse(svc.multiSite(), "钉住时不该暴露多站点");
        assertEquals(1, svc.availableSites().size());
        assertThrows(IllegalArgumentException.class, () -> svc.persistSelection("intl"));
    }

    @Test
    @DisplayName("非 local-mode（团队服务器/插件云后端）恒钉住：站点是部署决策，不是用户选择")
    void serverModeIsAlwaysPinned() {
        SiteProfileService svc = service(false, CN, "cn", true);
        assertTrue(svc.isPinned());
        assertThrows(IllegalArgumentException.class, () -> svc.persistSelection("intl"));
    }

    @Test
    @DisplayName("intl 未启用 → 单站形态，multiSite=false，切过去被拒")
    void disabledSiteIsNotSelectable() {
        SiteProfileService svc = service(true, CN, "cn", false);
        assertFalse(svc.multiSite());
        assertEquals(List.of("cn"), svc.availableSites().stream().map(SiteProfile::id).toList());
        assertTrue(svc.otherSites().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> svc.persistSelection("intl"));
    }

    @Test
    @DisplayName("intl 启用 → multiSite=true，otherSites 指向另一站（错配文案据此点名）")
    void multiSiteExposesTheOtherSite() {
        SiteProfileService svc = service(true, CN, "cn", true);
        assertTrue(svc.multiSite());
        assertEquals(List.of("intl"), svc.otherSites().stream().map(SiteProfile::id).toList());
        assertEquals("AI Workdeck International", svc.otherSites().get(0).displayName());
    }

    @Test
    @DisplayName("persistSelection 落盘 site.json 并当场改指向")
    void persistSelectionWritesAndSwitches() throws Exception {
        SiteProfileService svc = service(true, CN, "cn", true);
        svc.persistSelection("intl");
        assertEquals("intl", svc.currentSite());
        assertEquals(INTL, svc.baseUrl());
        String json = Files.readString(tempDir.resolve("site.json"));
        assertTrue(json.contains("\"site\": \"intl\""), json);
        assertTrue(json.contains("\"chosenBy\": \"user\""), json);
        // 重新构造（模拟重启）应当读到同一个站点
        assertEquals("intl", SiteStateFile.read(tempDir).site());
    }

    @Test
    @DisplayName("站点表里任一 enabled 站点的 base-url 非法 → 启动期就拒绝")
    void invalidSiteUrlIsRejectedAtStartup() {
        SiteProperties p = props(true);
        p.getSites().get("intl").setBaseUrl("http://www.workdeck.ai"); // 明文 http，非回环
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SiteProfileService(true, CN, "cn", tempDir.toString(), p));
        assertTrue(e.getMessage().contains("https"), e.getMessage());
    }

    @Test
    @DisplayName("站点表为空（老配置文件 / 直接 new 出来的实例）→ 用有效基址兜出一个站点，不返回 null")
    void emptySiteTableFallsBackToConfiguredBaseUrl() {
        SiteProfileService svc = new SiteProfileService(true, CN, "", tempDir.toString(),
                new SiteProperties());
        assertNotNull(svc.profile());
        assertEquals(CN, svc.baseUrl());
        assertFalse(svc.multiSite());
    }

    @Test
    @DisplayName("pinnedTo 工厂：钉住给定基址，协议红线照旧生效")
    void pinnedToFactory() {
        SiteProfileService svc = SiteProfileService.pinnedTo(CN + "/");
        assertEquals(CN, svc.baseUrl(), "尾斜杠应被规范化掉");
        assertTrue(svc.isPinned());
        assertFalse(svc.multiSite());
        assertThrows(IllegalArgumentException.class,
                () -> SiteProfileService.pinnedTo("http://www.aiworkdeck.com"));
    }
}
