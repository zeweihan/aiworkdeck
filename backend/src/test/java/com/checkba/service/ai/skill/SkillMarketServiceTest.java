package com.checkba.service.ai.skill;

import com.checkba.service.ai.PluginService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillMarketService 单测：注册表列表解析与安装标注、id/文件名安全校验、
 * 安装写盘 + rescan 生效、卸载（含拒绝插件携带 skill）。httpGet seam 打桩，不走真实网络。
 */
class SkillMarketServiceTest {

    private static final String REGISTRY_URL = "https://registry.example/api/registry/skills";

    @TempDir
    Path tempDir;

    /** httpGet seam 打桩：url -> 响应体；未配置的 url 模拟网络失败 */
    private static class StubMarketService extends SkillMarketService {
        final Map<String, String> responses = new HashMap<>();
        final List<String> requestedUrls = new ArrayList<>();

        StubMarketService(SkillProperties properties, SkillRegistry registry) {
            super(properties, registry);
        }

        @Override
        protected String httpGet(String url) {
            requestedUrls.add(url);
            String body = responses.get(url);
            if (body == null) {
                throw new IllegalStateException("注册表不可达: stub");
            }
            return body;
        }
    }

    private SkillProperties props;
    private SkillRegistry registry;

    private StubMarketService newService(Path skillsDir, PluginService pluginService) {
        props = new SkillProperties();
        props.setDir(skillsDir.toString());
        props.setRegistryUrl(REGISTRY_URL);
        registry = new SkillRegistry(props, null, pluginService);
        registry.init();
        return new StubMarketService(props, registry);
    }

    private void writeLocalSkill(Path dir, String id) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill.yml"), """
                id: %s
                name: 本地技能
                triggers: [本地关键词]
                """.formatted(id));
        Files.writeString(dir.resolve("prompt.md"), "本地指引");
    }

    private static String bundleJson(String id) {
        return """
                { "id": "%s", "version": "1.0.0", "files": {
                    "skill.yml": "id: %s\\nname: 在线技能\\ntriggers: [在线关键词]\\n",
                    "prompt.md": "在线指引",
                    "../../evil.txt": "escape",
                    "hack.sh": "rm -rf /"
                } }""".formatted(id, id);
    }

    @Test
    @DisplayName("listMarket：解析注册表数组并标注 installed（本地已有同 id 的为 true）")
    void listMarketMarksInstalled() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        writeLocalSkill(skillsDir.resolve("contract-review"), "contract-review");
        StubMarketService service = newService(skillsDir, new PluginService());
        service.responses.put(REGISTRY_URL, """
                [{ "id": "contract-review", "name": "合同审查", "description": "d", "icon": "🧩",
                   "version": "1.0.0", "author": "u", "authorDisplayName": "显示名",
                   "triggers": ["合同审查"], "allowedTools": ["read_document"],
                   "downloads": 12, "updatedAt": "2026-07-14T00:00:00.000Z",
                   "homepage": "https://example.com" },
                 { "id": "other-skill", "name": "其他", "triggers": ["其他"] }]""");

        List<SkillMarketService.MarketSkillView> list = service.listMarket();

        assertEquals(2, list.size());
        SkillMarketService.MarketSkillView first = list.get(0);
        assertEquals("contract-review", first.getId());
        assertEquals("合同审查", first.getName());
        assertEquals("显示名", first.getAuthorDisplayName());
        assertEquals(List.of("合同审查"), first.getTriggers());
        assertEquals(12, first.getDownloads());
        assertTrue(first.isInstalled());
        assertFalse(list.get(1).isInstalled());
    }

    @Test
    @DisplayName("listMarket：注册表不可达/返回非数组时抛 IllegalStateException")
    void listMarketFailsCleanly() {
        StubMarketService service = newService(tempDir.resolve("skills"), new PluginService());
        assertThrows(IllegalStateException.class, service::listMarket, "网络失败");

        service.responses.put(REGISTRY_URL, "<html>error page</html>");
        assertThrows(IllegalStateException.class, service::listMarket, "解析失败");
    }

    @Test
    @DisplayName("install：只落盘 skill.yml + prompt.md（bundle 中其余条目忽略），rescan 后可用")
    void installWritesKnownFilesAndRescans() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        StubMarketService service = newService(skillsDir, new PluginService());
        service.responses.put(REGISTRY_URL + "/contract-review/bundle", bundleJson("contract-review"));

        assertEquals("contract-review", service.install("contract-review"));

        Path skillDir = skillsDir.resolve("contract-review");
        assertTrue(Files.isRegularFile(skillDir.resolve("skill.yml")));
        assertTrue(Files.isRegularFile(skillDir.resolve("prompt.md")));
        assertFalse(Files.exists(skillDir.resolve("hack.sh")), "白名单外文件不落盘");
        assertFalse(Files.exists(tempDir.resolve("evil.txt")), "路径穿越条目不落盘");
        try (var stream = Files.list(skillDir)) {
            assertEquals(2, stream.count(), "只写两个已知文件");
        }
        // rescan 已被 install 触发，skill 立即可用
        SkillDefinition skill = registry.getSkill("contract-review").orElseThrow();
        assertEquals("在线技能", skill.getName());

        // 重装即更新：同 id 再次安装直接覆盖，不报错
        assertEquals("contract-review", service.install("contract-review"));
    }

    @Test
    @DisplayName("install：非法 id 直接拒绝（路径穿越守卫），不发起任何请求")
    void installRejectsInvalidIds() {
        StubMarketService service = newService(tempDir.resolve("skills"), new PluginService());
        for (String bad : new String[]{null, "", "../evil", "a/b", "UPPER-Case", "a", "-lead", "a".repeat(51)}) {
            assertThrows(IllegalArgumentException.class, () -> service.install(bad), String.valueOf(bad));
        }
        assertTrue(service.requestedUrls.isEmpty(), "校验失败不应发起网络请求");
    }

    @Test
    @DisplayName("install：bundle 缺 skill.yml 或 prompt.md 时拒绝，不写盘")
    void installRequiresBothFiles() {
        Path skillsDir = tempDir.resolve("skills");
        StubMarketService service = newService(skillsDir, new PluginService());
        service.responses.put(REGISTRY_URL + "/half-skill/bundle",
                """
                { "id": "half-skill", "version": "1.0.0", "files": { "skill.yml": "id: half-skill" } }""");

        assertThrows(IllegalStateException.class, () -> service.install("half-skill"));
        assertFalse(Files.exists(skillsDir.resolve("half-skill")), "校验失败不写盘");
    }

    @Test
    @DisplayName("uninstall：删除 skills/ 下的目录并 rescan；未安装/非法 id 拒绝")
    void uninstallRemovesInstalledSkill() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        writeLocalSkill(skillsDir.resolve("contract-review"), "contract-review");
        StubMarketService service = newService(skillsDir, new PluginService());
        assertTrue(registry.getSkill("contract-review").isPresent());

        service.uninstall("contract-review");

        assertFalse(Files.exists(skillsDir.resolve("contract-review")));
        assertTrue(registry.getSkill("contract-review").isEmpty(), "rescan 后注册表同步移除");

        assertThrows(IllegalArgumentException.class, () -> service.uninstall("contract-review"), "重复卸载=未安装");
        assertThrows(IllegalArgumentException.class, () -> service.uninstall("../evil"));
    }

    @Test
    @DisplayName("uninstall：插件携带的 skill（sourcePluginId != null）拒绝卸载")
    void uninstallRefusesPluginCarriedSkill() throws IOException {
        Path pluginDir = tempDir.resolve("plugins").resolve("my-plugin");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("manifest.json"), """
                { "id": "my-plugin", "name": "带技能的插件", "version": "1.0.0", "skills": ["my-skill"] }""");
        writeLocalSkill(pluginDir.resolve("my-skill"), "plugin-skill");
        PluginService pluginService = new PluginService(null, tempDir.resolve("plugins").toString());
        pluginService.init();

        StubMarketService service = newService(tempDir.resolve("skills"), pluginService);
        assertTrue(registry.getSkill("plugin-skill").isPresent());

        assertThrows(IllegalStateException.class, () -> service.uninstall("plugin-skill"));
        assertTrue(registry.getSkill("plugin-skill").isPresent(), "插件 skill 原样保留");
    }
}
