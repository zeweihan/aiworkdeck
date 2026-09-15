// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.ai;

import com.checkba.service.DocumentTextService;
import com.checkba.service.FileTagService;
import com.checkba.service.TagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.clearInvocations;

@SpringBootTest(classes = AutoTaggingService.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("desktop")
class DesktopAutoTaggingPrivacyTest {
    @Autowired AutoTaggingService service;
    @MockBean ChatModelFactory chatModelFactory;
    @MockBean TagService tagService;
    @MockBean FileTagService fileTagService;
    @MockBean DocumentTextService documentTextService;
    @MockBean AuxModelResolver auxModelResolver;
    @MockBean TokenUsageService tokenUsageService;

    @Test
    void desktopImportsAndEditorSavesDoNotReadOrSendOriginalTextForAutomaticTags() {
        // Spring invokes the mocked factory's lifecycle callback during context setup.
        clearInvocations(documentTextService, chatModelFactory, auxModelResolver,
                tokenUsageService, fileTagService, tagService);
        // Exercise the shared entry used by uploads, local imports, and editor flushSave.
        service.autoTagFile(1L, 7L, "synthetic-unredacted.docx", 42L);
        service.autoTagFile(1L, 8L, "synthetic-unredacted.md", 42L);
        verifyNoInteractions(documentTextService, chatModelFactory, auxModelResolver,
                tokenUsageService, fileTagService, tagService);
    }
}
