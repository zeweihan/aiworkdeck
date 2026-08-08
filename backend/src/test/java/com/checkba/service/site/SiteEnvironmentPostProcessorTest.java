package com.checkba.service.site;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定站点在**属性层**的注入（双主站设计 §2.2）。
 *
 * 这一层是整个方案能「只改一处」的关键：registry 与 telemetry 的三个消费方
 * （{@code service/ai/} 下）一行都没改，靠的就是这里在容器起来之前把属性换掉。
 * 三条不变式各有一个用例：只在 local-mode 生效、site.json 缺失时行为零变化、
 * 优先级必须输给环境变量。
 */
class SiteEnvironmentPostProcessorTest {

    private static final String CN = "https://www.aiworkdeck.com";
    private static final String INTL = "https://www.workdeck.ai";

    @TempDir
    Path tempDir;

    /** 模拟 application.yml 已被 ConfigDataEnvironmentPostProcessor 加载后的环境。 */
    private StandardEnvironment env(boolean localMode, boolean intlEnabled) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> yml = new LinkedHashMap<>();
        yml.put("security.local-mode", String.valueOf(localMode));
        yml.put("security.license.dir", tempDir.toString());
        yml.put("ai.account.base-url", CN);
        yml.put("ai.account.default-site", "cn");
        yml.put("ai.account.sites.cn.enabled", "true");
        yml.put("ai.account.sites.cn.base-url", CN);
        yml.put("ai.account.sites.cn.registry-base-url", CN + "/api/registry");
        yml.put("ai.account.sites.cn.telemetry-ingest-url", CN + "/api/telemetry");
        yml.put("ai.account.sites.intl.enabled", String.valueOf(intlEnabled));
        yml.put("ai.account.sites.intl.base-url", INTL);
        yml.put("ai.account.sites.intl.registry-base-url", INTL + "/api/registry");
        yml.put("ai.account.sites.intl.telemetry-ingest-url", INTL + "/api/telemetry");
        yml.put("ai.plugins.registry-url", CN + "/api/registry/plugins");
        yml.put("ai.skills.registry-url", CN + "/api/registry/skills");
        yml.put("telemetry.ingest-url", CN + "/api/telemetry");
        // application.yml 在属性源里排最后，与真实运行一致
        environment.getPropertySources().addLast(new MapPropertySource("applicationConfig", yml));
        return environment;
    }

    private void run(StandardEnvironment environment) {
        new SiteEnvironmentPostProcessor().postProcessEnvironment(environment, null);
    }

    private void chooseSite(String siteId) throws Exception {
        SiteStateFile.write(tempDir, new SiteStateFile.State(siteId, "2026-08-08T00:00:00Z", "user"));
    }

    @Test
    @DisplayName("site.json 不存在 → 解析到默认站点，四个地址与 yml 里今天的值逐字相同")
    void absentStateFileKeepsTodaysValues() {
        StandardEnvironment environment = env(true, true);
        run(environment);
        assertEquals(CN, environment.getProperty("ai.account.base-url"));
        assertEquals(CN + "/api/registry/plugins", environment.getProperty("ai.plugins.registry-url"));
        assertEquals(CN + "/api/registry/skills", environment.getProperty("ai.skills.registry-url"));
        assertEquals(CN + "/api/telemetry", environment.getProperty("telemetry.ingest-url"));
        assertEquals("cn", environment.getProperty("ai.account.resolved-site"));
    }

    @Test
    @DisplayName("site.json 选中 intl → 四个地址一起改指向（registry 与 telemetry 也跟着走）")
    void chosenSiteRewritesAllFourAddresses() throws Exception {
        chooseSite("intl");
        StandardEnvironment environment = env(true, true);
        run(environment);
        assertEquals(INTL, environment.getProperty("ai.account.base-url"));
        assertEquals(INTL + "/api/registry/plugins", environment.getProperty("ai.plugins.registry-url"));
        assertEquals(INTL + "/api/registry/skills", environment.getProperty("ai.skills.registry-url"));
        assertEquals(INTL + "/api/telemetry", environment.getProperty("telemetry.ingest-url"));
        assertEquals("intl", environment.getProperty("ai.account.resolved-site"));
    }

    @Test
    @DisplayName("非 local-mode 一律不读 site.json：站点是部署决策")
    void serverModeIgnoresStateFile() throws Exception {
        chooseSite("intl");
        StandardEnvironment environment = env(false, true);
        run(environment);
        assertEquals(CN, environment.getProperty("ai.account.base-url"));
        assertNull(environment.getProperty("ai.account.resolved-site"));
    }

    @Test
    @DisplayName("选中的站点未启用 → 回落默认站点，不是报错也不是照用")
    void disabledSiteFallsBackToDefault() throws Exception {
        chooseSite("intl");
        StandardEnvironment environment = env(true, false);
        run(environment);
        assertEquals(CN, environment.getProperty("ai.account.base-url"));
        assertEquals("cn", environment.getProperty("ai.account.resolved-site"));
    }

    @Test
    @DisplayName("site.json 内容损坏 → 当作未选择，绝不让应用起不来")
    void corruptStateFileIsIgnored() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("site.json"), "{ not json at all");
        StandardEnvironment environment = env(true, true);
        assertDoesNotThrow(() -> run(environment));
        assertEquals(CN, environment.getProperty("ai.account.base-url"));
    }

    @Test
    @DisplayName("环境变量优先级更高：本地联调的 AI_ACCOUNT_BASE_URL 不会被站点解析覆盖")
    void systemEnvironmentStillWins() throws Exception {
        chooseSite("intl");
        StandardEnvironment environment = env(true, true);
        // 用一个与 systemEnvironment 同名的属性源模拟环境变量注入
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("ai.account.base-url", "http://localhost:3000")));
        run(environment);
        assertEquals("http://localhost:3000", environment.getProperty("ai.account.base-url"),
                "环境变量必须压过站点注入，否则改契约时没有安全的联调场地");
        // 但 registry / telemetry 仍随站点走（联调只覆盖账户基址这一条）
        assertEquals(INTL + "/api/registry/skills", environment.getProperty("ai.skills.registry-url"));
    }

    @Test
    @DisplayName("重复执行幂等：不会插出第二个同名属性源")
    void runningTwiceIsIdempotent() throws Exception {
        chooseSite("intl");
        StandardEnvironment environment = env(true, true);
        run(environment);
        run(environment);
        assertEquals(INTL, environment.getProperty("ai.account.base-url"));
        long count = environment.getPropertySources().stream()
                .filter(s -> "awdSiteProfile".equals(s.getName())).count();
        assertEquals(1, count);
    }
}
