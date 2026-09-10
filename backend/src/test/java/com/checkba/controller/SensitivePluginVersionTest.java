// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.controller.ai.SkillController;
import com.checkba.service.SensitiveService;
import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SensitivePluginVersionTest {
    @Test void installedPanelReportsActualEngineEvenWithOldBundledMetadata() {
        SkillDefinition skill = new SkillDefinition();
        skill.setId("desensitize"); skill.setVersion("1.0.0");
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getSkills()).thenReturn(List.of(skill));
        when(registry.activationMode("desensitize")).thenReturn(SkillRegistry.ActivationMode.DISABLED);
        SkillController controller = new SkillController(registry, null, null, null, null, null);
        var view = controller.listSkills().get(0);
        assertEquals(SensitiveService.VERSION, view.getVersion());
        assertEquals(SensitiveService.DESCRIPTION, view.getDescription());
        assertFalse(view.isEnabled());
        assertEquals("1.0.0", skill.getVersion());
    }
}
