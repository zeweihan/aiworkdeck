package com.checkba.controller.ai;

import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.skill.SkillProperties;
import com.checkba.service.ai.skill.SkillRegistry;
import com.checkba.service.pack.NativePackService;
import com.checkba.service.pack.PackProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /api/skills/list 的 packId / packReady 两个字段（规范 docs/NATIVE_PACK_DISTRIBUTION.md §7.1）。
 *
 * 前端靠它们区分「点安装就能用」与「要先下 45 MB」，判定必须包含
 * 「随包内置资源在场」这一支——老版本用户的资源还在，不该被显示成未安装。
 */
class SkillControllerPackViewTest {

    @TempDir
    Path tempDir;

    private void writeSkill(String id, String extra) throws IOException {
        Path dir = tempDir.resolve("skills").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill.yml"), """
                id: %s
                name: 测试技能
                description: 描述
                triggers:
                  - 触发词
                %s""".formatted(id, extra == null ? "" : extra));
        Files.writeString(dir.resolve("prompt.md"), "指引");
    }

    private SkillController controller(NativePackService packService) {
        SkillProperties props = new SkillProperties();
        props.setDir(tempDir.resolve("skills").toString());
        SkillRegistry registry = new SkillRegistry(props, null, new PluginService(), null);
        registry.init();
        return new SkillController(registry, null, null, null, null, packService);
    }

    private NativePackService packService() {
        PackProperties props = new PackProperties();
        props.setDir(tempDir.resolve("packs").toString());
        props.setBaseUrls(List.of("https://example.invalid/plugin-packs"));
        return new NativePackService(props, "", "0.21.0");
    }

    @Test
    @DisplayName("无 requires_pack 的 skill：packId 为空、packReady 恒 true")
    void skillWithoutPackIsAlwaysReady() throws IOException {
        writeSkill("plain-skill", null);
        List<SkillController.SkillView> views = controller(packService()).listSkills();

        assertEquals(1, views.size());
        assertNull(views.get(0).getPackId());
        assertTrue(views.get(0).isPackReady());
    }

    @Test
    @DisplayName("声明了 requires_pack：资源缺失 packReady=false，随包内置在场则 true")
    void packReadyFollowsResourceAvailability() throws IOException {
        writeSkill("packed-skill", "requires_pack: litigation-visual\n");
        NativePackService packs = packService();

        SkillController.SkillView missing = controller(packs).listSkills().get(0);
        assertEquals("litigation-visual", missing.getPackId());
        assertFalse(missing.isPackReady(), "pack 未装且随包资源不在场");

        // 随包内置资源在场（老版本用户）——不该被逼着重下一遍
        packs.registerBuiltinProbe("litigation-visual", () -> true);
        SkillController.SkillView bundled = controller(packs).listSkills().get(0);
        assertTrue(bundled.isPackReady());
    }
}
