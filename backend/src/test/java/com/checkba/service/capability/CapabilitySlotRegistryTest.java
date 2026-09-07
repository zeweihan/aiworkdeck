package com.checkba.service.capability;

import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.pack.NativePackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 能力槽注册表单测：候选聚合、选择位、降级链、回滚、开发者模式。
 *
 * <p>刻意用真实文件系统（@TempDir）做实现目录：这一层要守的东西正是「目录里有没有 cli.py」
 * 这类环境判定，mock 掉文件系统就把要守的东西守没了。
 */
class CapabilitySlotRegistryTest {

    private static final String SLOT = CapabilitySlotRegistry.SLOT_LITIGATION_DIAGRAM;

    @TempDir
    Path tmp;

    private PluginService pluginService;
    private CapabilitySlotRegistry registry;
    private final Map<String, String> settings = new HashMap<>();
    private final List<PluginService.PluginMetadata> plugins = new ArrayList<>();
    private final Map<String, File> pluginDirs = new HashMap<>();
    private final Map<String, Boolean> enabled = new HashMap<>();
    private NativePackService packService;

    @BeforeEach
    void setUp() {
        pluginService = mock(PluginService.class);
        when(pluginService.getPlugins()).thenReturn(plugins);
        when(pluginService.isEnabled(anyString()))
                .thenAnswer(inv -> enabled.getOrDefault(inv.<String>getArgument(0), Boolean.TRUE));
        when(pluginService.getPluginDir(anyString()))
                .thenAnswer(inv -> pluginDirs.get(inv.<String>getArgument(0)));

        SystemSettingService systemSettingService = mock(SystemSettingService.class);
        when(systemSettingService.get(anyString(), any()))
                .thenAnswer(inv -> settings.getOrDefault(inv.<String>getArgument(0), inv.getArgument(1)));
        org.mockito.Mockito.doAnswer(inv -> {
            settings.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(systemSettingService).set(anyString(), any());

        packService = mock(NativePackService.class);
        when(packService.componentDir(anyString(), anyString())).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        ObjectProvider<NativePackService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(packService);

        registry = new CapabilitySlotRegistry(pluginService, systemSettingService, provider);
    }

    /** 造一个提供 litigation.diagram 实现的插件，实现目录里按需放 cli.py */
    private Path givenPlugin(String id, String capId, String protocol, boolean withCliPy) throws Exception {
        Path dir = tmp.resolve(id);
        Path engine = dir.resolve("engine");
        Files.createDirectories(engine);
        if (withCliPy) {
            Files.writeString(engine.resolve("cli.py"), "# fake\n");
        } else {
            Files.writeString(engine.resolve("readme.txt"), "no cli here\n");
        }
        PluginService.CapabilityDecl decl = new PluginService.CapabilityDecl();
        decl.setCapability(SLOT);
        decl.setId(capId);
        decl.setKind("process");
        decl.setEntry("engine/");
        decl.setProtocol(protocol);
        PluginService.Contributes contributes = new PluginService.Contributes();
        contributes.setCapabilities(List.of(decl));
        PluginService.PluginMetadata meta = new PluginService.PluginMetadata();
        meta.setId(id);
        meta.setName(id + " 引擎");
        meta.setContributes(contributes);
        plugins.add(meta);
        pluginDirs.put(id, dir.toFile());
        return engine;
    }

    @Test
    @DisplayName("没选时 resolve 为空——消费方接着走自己的内置链，降级逻辑不在这里抄第二遍")
    void resolvesEmptyWhenNothingSelected() {
        assertTrue(registry.resolve(SLOT).isEmpty());
        assertFalse(registry.isDegraded(SLOT));
        List<CapabilitySlotRegistry.Candidate> candidates = registry.candidates(SLOT);
        assertEquals(1, candidates.size(), "只有 builtin 一个候选");
        assertEquals(CapabilitySlotRegistry.REF_BUILTIN, candidates.get(0).ref());
    }

    @Test
    @DisplayName("选中插件实现后 resolve 返回它的 entry 目录")
    void resolvesSelectedPluginImplementation() throws Exception {
        Path engine = givenPlugin("acme-litviz", "engine", CapabilitySlotRegistry.PROTOCOL_LITVIZ_CLI_1, true);
        registry.select(SLOT, "plugin:acme-litviz:engine");
        assertEquals(engine.toAbsolutePath().normalize(), registry.resolve(SLOT).orElseThrow());
        assertFalse(registry.isDegraded(SLOT));
    }

    @Test
    @DisplayName("插件被禁用后选中的实现静默降级，标记为已降级")
    void degradesWhenPluginDisabled() throws Exception {
        givenPlugin("acme-litviz", "engine", CapabilitySlotRegistry.PROTOCOL_LITVIZ_CLI_1, true);
        registry.select(SLOT, "plugin:acme-litviz:engine");
        enabled.put("acme-litviz", false);

        assertTrue(registry.resolve(SLOT).isEmpty(), "禁用后应降级");
        assertTrue(registry.isDegraded(SLOT));
    }

    @Test
    @DisplayName("entry 目录里没有 cli.py 的实现不可用，也不许被选中")
    void rejectsImplementationWithoutEntryFile() throws Exception {
        givenPlugin("broken-litviz", "engine", CapabilitySlotRegistry.PROTOCOL_LITVIZ_CLI_1, false);
        CapabilitySlotRegistry.Candidate c = registry.candidates(SLOT).stream()
                .filter(x -> x.ref().equals("plugin:broken-litviz:engine")).findFirst().orElseThrow();
        assertFalse(c.available());
        assertTrue(c.reason().contains("cli.py"));
        assertThrows(IllegalArgumentException.class, () -> registry.select(SLOT, "plugin:broken-litviz:engine"));
    }

    @Test
    @DisplayName("协议不匹配的实现照样列出（否则用户看不见「装了没生效」），但不可用")
    void listsProtocolMismatchAsUnavailable() throws Exception {
        givenPlugin("old-litviz", "engine", "litviz-cli/0", true);
        CapabilitySlotRegistry.Candidate c = registry.candidates(SLOT).stream()
                .filter(x -> x.ref().equals("plugin:old-litviz:engine")).findFirst().orElseThrow();
        assertFalse(c.available());
        assertTrue(c.reason().contains("协议不匹配"), c.reason());
    }

    @Test
    @DisplayName("回滚回到上一次选择，再回滚一次又切回来")
    void rollbackTogglesBetweenLastTwoChoices() throws Exception {
        givenPlugin("acme-litviz", "engine", CapabilitySlotRegistry.PROTOCOL_LITVIZ_CLI_1, true);
        registry.select(SLOT, "plugin:acme-litviz:engine");
        assertEquals("plugin:acme-litviz:engine", registry.selectedRef(SLOT));

        registry.rollback(SLOT);
        assertEquals(CapabilitySlotRegistry.REF_BUILTIN, registry.selectedRef(SLOT), "回到初始的内置实现");

        registry.rollback(SLOT);
        assertEquals("plugin:acme-litviz:engine", registry.selectedRef(SLOT));
    }

    @Test
    @DisplayName("没有上一次选择时回滚报错，不是静默无操作")
    void rollbackWithoutHistoryFails() {
        assertThrows(IllegalArgumentException.class, () -> registry.rollback(SLOT));
    }

    @Test
    @DisplayName("includePack=false 跳过 pack 候选，但插件候选照常生效")
    void skipsPackCandidateWhenAsked() throws Exception {
        Path packDir = tmp.resolve("packcopy");
        Files.createDirectories(packDir);
        Files.writeString(packDir.resolve("cli.py"), "# pack\n");
        when(packService.componentDir("litigation-visual", "litviz")).thenReturn(Optional.of(packDir));

        registry.select(SLOT, "pack:litigation-visual");
        assertTrue(registry.resolve(SLOT, true).isPresent());
        assertTrue(registry.resolve(SLOT, false).isEmpty(), "不算 pack 时应当作没选");

        Path engine = givenPlugin("acme-litviz", "engine", CapabilitySlotRegistry.PROTOCOL_LITVIZ_CLI_1, true);
        registry.select(SLOT, "plugin:acme-litviz:engine");
        assertEquals(engine.toAbsolutePath().normalize(), registry.resolve(SLOT, false).orElseThrow(),
                "插件候选不是 pack，includePack=false 也要生效");
    }

    @Test
    @DisplayName("内置探针登记后 builtin 候选才是可用的")
    void builtinCandidateFollowsRegisteredProbe() throws Exception {
        Path builtin = tmp.resolve("builtin");
        Files.createDirectories(builtin);
        Files.writeString(builtin.resolve("cli.py"), "# builtin\n");
        assertFalse(registry.candidates(SLOT).get(0).available(), "没登记探针时内置候选不可用");

        registry.registerBuiltin(SLOT, () -> builtin);
        CapabilitySlotRegistry.Candidate c = registry.candidates(SLOT).get(0);
        assertTrue(c.available());
        assertEquals(builtin.toString(), c.dir());
    }

    @Test
    @DisplayName("切换与回滚都要触发变更回调——消费方靠它失效解析缓存，否则「切换即生效」是假的")
    void notifiesListenersOnSelectAndRollback() throws Exception {
        givenPlugin("acme-litviz", "engine", CapabilitySlotRegistry.PROTOCOL_LITVIZ_CLI_1, true);
        int[] fired = {0};
        registry.onSlotChanged(SLOT, () -> fired[0]++);

        registry.select(SLOT, "plugin:acme-litviz:engine");
        assertEquals(1, fired[0]);

        registry.select(SLOT, "plugin:acme-litviz:engine");
        assertEquals(1, fired[0], "选同一个不算变更，不该重复通知");

        registry.rollback(SLOT);
        assertEquals(2, fired[0]);
    }

    @Test
    @DisplayName("开发者模式默认开——维护者裁决（dev-board#497），不需要时可在设置页关闭")
    void devModeDefaultsOn() {
        assertTrue(registry.devMode());
        registry.setDevMode(false);
        assertFalse(registry.devMode());
        registry.setDevMode(true);
        assertTrue(registry.devMode());
    }
}
