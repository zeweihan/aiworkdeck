// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.capability;

import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.PluginDevService;
import com.checkba.service.ai.PluginService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 能力包安装的三档分流单测：web / data 可自动装，process 默认拒装、开发者模式下才放行。
 *
 * <p>拉取器注入本地构造的 tar.gz（不上网），落盘走真实文件系统（@TempDir 当 plugins/ 目录）——
 * 要守的正是「什么档位的代码被允许落到本机」，落盘 mock 掉就守不住了。
 */
class CapabilityInstallServiceTest {

    @TempDir
    Path pluginsDir;

    private CapabilitySourceFetchService fetchService;
    private CapabilitySlotRegistry slotRegistry;
    private CapabilityInstallService installService;
    private final Map<String, String> settings = new HashMap<>();

    @BeforeEach
    void setUp() {
        fetchService = new CapabilitySourceFetchService();

        PluginService pluginService = mock(PluginService.class);
        when(pluginService.getPlugins()).thenReturn(List.of());
        when(pluginService.isEnabled(anyString())).thenReturn(true);

        SystemSettingService systemSettingService = mock(SystemSettingService.class);
        when(systemSettingService.get(anyString(), any()))
                .thenAnswer(inv -> settings.getOrDefault(inv.<String>getArgument(0), inv.getArgument(1)));
        org.mockito.Mockito.doAnswer(inv -> {
            settings.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(systemSettingService).set(anyString(), any());

        @SuppressWarnings("unchecked")
        ObjectProvider<com.checkba.service.pack.NativePackService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        slotRegistry = new CapabilitySlotRegistry(pluginService, systemSettingService, provider);

        PluginDevService devService = new PluginDevService(null, null, null, pluginService, pluginsDir.toString());
        installService = new CapabilityInstallService(fetchService, devService, slotRegistry);
    }

    private void givenRepo(Map<String, String> files) throws Exception {
        byte[] archive = tarGz("pkg-main", files);
        fetchService.setDownloader(url -> archive);
    }

    private static byte[] tarGz(String topDir, Map<String, String> files) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tout = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
            tout.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, String> e : new LinkedHashMap<>(files).entrySet()) {
                byte[] body = e.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(topDir + "/" + e.getKey());
                entry.setSize(body.length);
                tout.putArchiveEntry(entry);
                tout.write(body);
                tout.closeArchiveEntry();
            }
        }
        return bos.toByteArray();
    }

    private static final String URL = "https://github.com/acme/pkg";

    @Test
    @DisplayName("web 档（沙箱 iframe 前端）可自动安装")
    void webPackageInstallsAutomatically() throws Exception {
        givenRepo(Map.of(
                "manifest.json", "{\"id\":\"demo-web\",\"name\":\"演示\",\"version\":\"0.1.0\","
                        + "\"frontendEntry\":\"web/index.html\",\"permissions\":[\"file_read\"]}",
                "web/index.html", "<p>hi</p>"));
        CapabilityInstallService.Plan plan = installService.plan(URL);
        assertEquals("web", plan.kind());
        assertTrue(plan.canAutoInstall(), String.join("; ", plan.reasons()));

        assertEquals("demo-web", installService.apply(plan.planId()));
        assertTrue(Files.isRegularFile(pluginsDir.resolve("demo-web/web/index.html")));
        assertTrue(Files.isRegularFile(pluginsDir.resolve("demo-web/" + PluginDevService.DEV_MARKER)));
    }

    @Test
    @DisplayName("data 档（纯声明式包，无 frontendEntry）可自动安装——这一档零执行面")
    void declarativePackageInstallsWithoutFrontendEntry() throws Exception {
        givenRepo(Map.of(
                "manifest.json", "{\"id\":\"demo-data\",\"name\":\"模板包\",\"version\":\"1.0.0\","
                        + "\"contributes\":{\"templates\":[{\"id\":\"memo\",\"name\":\"备忘\",\"file\":\"t/memo.md\"}]}}",
                "t/memo.md", "# memo"));
        CapabilityInstallService.Plan plan = installService.plan(URL);
        assertEquals("data", plan.kind());
        assertTrue(plan.canAutoInstall(), String.join("; ", plan.reasons()));
        assertEquals("demo-data", installService.apply(plan.planId()));
    }

    private static Map<String, String> processRepo() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("manifest.json", "{\"id\":\"demo-engine\",\"name\":\"出图引擎\",\"version\":\"2.0.0\","
                + "\"contributes\":{\"capabilities\":[{\"capability\":\"litigation.diagram\",\"id\":\"engine\","
                + "\"kind\":\"process\",\"entry\":\"engine/\",\"protocol\":\"litviz-cli/1\","
                + "\"runtime\":\"python>=3.11\"}]}}");
        files.put("engine/cli.py", "# engine\n");
        return files;
    }

    @Test
    @DisplayName("process 档在开发者模式关闭时拒装，理由指明怎么办")
    void processPackageRefusedWhenDevModeOff() throws Exception {
        slotRegistry.setDevMode(false);
        givenRepo(processRepo());
        CapabilityInstallService.Plan plan = installService.plan(URL);
        assertEquals("process", plan.kind());
        assertEquals(List.of("litigation.diagram"), plan.slots());
        assertFalse(plan.canAutoInstall());
        assertTrue(String.join("\n", plan.reasons()).contains("开发者模式"), String.join("; ", plan.reasons()));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> installService.apply(plan.planId()));
        assertTrue(e.getMessage().contains("开发者模式"));
        assertFalse(Files.exists(pluginsDir.resolve("demo-engine")), "拒装就不该有任何东西落盘");
    }

    @Test
    @DisplayName("开发者模式打开后 process 档才装得上，且带 .awd-dev 标记（UI 上永远显示未签名）")
    void processPackageInstallsUnderDevMode() throws Exception {
        slotRegistry.setDevMode(true);
        givenRepo(processRepo());
        CapabilityInstallService.Plan plan = installService.plan(URL);
        assertTrue(plan.canAutoInstall(), String.join("; ", plan.reasons()));
        assertEquals("demo-engine", installService.apply(plan.planId()));
        assertTrue(Files.isRegularFile(pluginsDir.resolve("demo-engine/engine/cli.py")));
        assertTrue(Files.isRegularFile(pluginsDir.resolve("demo-engine/" + PluginDevService.DEV_MARKER)));
    }

    @Test
    @DisplayName("默认（未设置开发者模式）时 process 档允许以 .awd-dev 装，且 unsigned=true")
    void processPackageInstallsByDefaultWhenDevModeUnset() throws Exception {
        givenRepo(processRepo());
        CapabilityInstallService.Plan plan = installService.plan(URL);
        assertTrue(plan.canAutoInstall(), String.join("; ", plan.reasons()));
        assertEquals("demo-engine", installService.apply(plan.planId()));
        assertTrue(Files.isRegularFile(pluginsDir.resolve("demo-engine/engine/cli.py")));
        assertTrue(Files.isRegularFile(pluginsDir.resolve("demo-engine/" + PluginDevService.DEV_MARKER)),
                "默认未设置时也应视为开发者模式开，落盘 .awd-dev 标记");
    }

    @Test
    @DisplayName("协议不匹配在 plan 阶段就报，不等到装完才发现不生效")
    void protocolMismatchReportedInPlan() throws Exception {
        Map<String, String> files = new LinkedHashMap<>(processRepo());
        files.put("manifest.json", files.get("manifest.json").replace("litviz-cli/1", "litviz-cli/9"));
        givenRepo(files);
        CapabilityInstallService.Plan plan = installService.plan(URL);
        assertFalse(plan.canAutoInstall());
        assertTrue(String.join("\n", plan.reasons()).contains("协议不匹配"), String.join("; ", plan.reasons()));
    }

    @Test
    @DisplayName("撞广场已装的 id 在 plan 阶段就报，不等到落盘才失败")
    void idCollisionReportedInPlan() throws Exception {
        Files.createDirectories(pluginsDir.resolve("demo-web"));
        Files.writeString(pluginsDir.resolve("demo-web/manifest.json"), "{}");
        givenRepo(Map.of(
                "manifest.json", "{\"id\":\"demo-web\",\"name\":\"演示\",\"frontendEntry\":\"web/index.html\"}",
                "web/index.html", "<p>hi</p>"));
        CapabilityInstallService.Plan plan = installService.plan(URL);
        assertFalse(plan.canAutoInstall());
        assertTrue(String.join("\n", plan.reasons()).contains("广场插件"), String.join("; ", plan.reasons()));
    }

    @Test
    @DisplayName("带 JAR / 工具 / skill / 资源包的包一律拒——免签路径不许绕过签名闸")
    void jarBearingPackageRefused() throws Exception {
        givenRepo(Map.of(
                "manifest.json", "{\"id\":\"demo-jar\",\"name\":\"坏包\",\"frontendEntry\":\"web/index.html\","
                        + "\"backendJars\":[\"lib.jar\"]}",
                "web/index.html", "<p>hi</p>"));
        CapabilityInstallService.Plan plan = installService.plan(URL);
        assertFalse(plan.canAutoInstall());
        assertTrue(String.join("\n", plan.reasons()).contains("backendJars"), String.join("; ", plan.reasons()));
    }

    @Test
    @DisplayName("过期/不存在的 planId 明确报错，不静默无操作")
    void unknownPlanIdFails() {
        assertThrows(IllegalArgumentException.class, () -> installService.apply("no-such-plan"));
    }
}
