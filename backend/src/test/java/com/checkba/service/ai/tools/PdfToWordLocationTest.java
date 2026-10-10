// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.context.ProjectContextHolder;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PdfToWordLocationTest {
    private ProjectFile file(long id, Long parent, boolean folder) {
        var f = new ProjectFile(); f.setId(id); f.setProjectId(42L); f.setParentId(parent);
        f.setIsFolder(folder); f.setName(folder ? "资料" : "source.pdf"); return f;
    }
    @Test void defaultsToSourceFolderAndResolvesBeforeRunReuse() {
        for (Long requested : new Long[]{null, 0L, 9L}) {
            var files = mock(ProjectFileService.class); var bridge = mock(EditorBridgeService.class);
            Long expectedParent = requested;
            if (requested == null) expectedParent = 7L;
            else if (requested == 0) expectedParent = null;
            var source = file(1, 7L, false); var generated = file(2, expectedParent, false);
            when(files.getFile(1L)).thenReturn(source); when(files.findFile(1L)).thenReturn(Optional.of(source));
            when(files.findFile(2L)).thenReturn(Optional.of(generated));
            when(files.findFile(7L)).thenReturn(Optional.of(file(7, null, true)));
            when(files.findFile(9L)).thenReturn(Optional.of(file(9, null, true)));
            String key = EditorBridgeService.pdfToWordKey(1L, generated.getParentId());
            when(bridge.generatedInRun(key)).thenReturn(2L);
            var tools = new PdfTools(null, files, null, bridge, null, null, null, null);
            ProjectContextHolder.setProjectId("42");
            try { assertFalse(tools.pdf_to_word(1L, requested, false).startsWith("错误")); verify(bridge).generatedInRun(key); }
            finally { ProjectContextHolder.clear(); }
        }
    }
    @Test void rejectsForeignExplicitFolderBeforeConversion() {
        var files = mock(ProjectFileService.class); var bridge = mock(EditorBridgeService.class);
        when(files.getFile(1L)).thenReturn(file(1, 7L, false));
        var foreign = file(9, null, true); foreign.setProjectId(99L);
        when(files.findFile(9L)).thenReturn(Optional.of(foreign));
        var tools = new PdfTools(null, files, null, bridge, null, null, null, null);
        ProjectContextHolder.setProjectId("42");
        try { assertTrue(tools.pdf_to_word(1L, 9L, false).contains("不属于本项目")); verifyNoInteractions(bridge); }
        finally { ProjectContextHolder.clear(); }
    }
}
