// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.pack;

import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackAutoInstallerTest {

    private static SkillDefinition skill(String id, String packId) {
        SkillDefinition s = new SkillDefinition();
        s.setId(id);
        s.setRequiresPack(packId);
        return s;
    }

    @Test
    @DisplayName("四个可选运行时 pack 绝不自动补下——用户明确要求「提示之后才下」")
    void neverAutoInstallsOptionalRuntimePacks() {
        PackProperties props = new PackProperties();
        NativePackService packs = mock(NativePackService.class);
        SkillRegistry skills = mock(SkillRegistry.class);
        when(packs.resourceReady(anyString())).thenReturn(false);
        when(skills.isEnabled(anyString())).thenReturn(true);
        when(skills.getSkills()).thenReturn(List.of(
                skill("text-to-speech", "kokoro-runtime"),
                skill("litigation-visual", "litigation-visual")));

        List<String> triggered = new PackAutoInstaller(props, packs, skills).checkAndInstall();

        assertEquals(List.of("litigation-visual"), triggered);
        verify(packs, never()).installAsync("kokoro-runtime");
        verify(packs).installAsync("litigation-visual");
    }
}
