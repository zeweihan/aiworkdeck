// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.LitigationPngService;
import com.checkba.service.ai.LitigationVisualService;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LitigationVisualPanelSafetyTest {
    private final ProjectFileRepository repository = mock(ProjectFileRepository.class);
    private final ProjectFileService files = mock(ProjectFileService.class);
    private final ProjectStorageResolver storage = mock(ProjectStorageResolver.class);
    private final LitigationVisualService engine = mock(LitigationVisualService.class);
    private final LitigationPngService png = mock(LitigationPngService.class);
    private final LitigationVisualPanelService service =
            new LitigationVisualPanelService(repository, files, storage, engine, png);

    @TempDir Path directory;

    @Test
    void unconfirmedRestyleDoesNotReadOrChangeAnyFile() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.restyle(1L, 2L, "白描", false));
        assertTrue(error.getMessage().contains("覆盖当前手工修改"));
        verifyNoInteractions(repository, files, storage, engine, png);
    }

    @Test
    void confirmedRestyleReachesTheEngineAvailabilityCheck() {
        when(engine.unavailableReason()).thenReturn("engine unavailable");
        var error = assertThrows(IllegalStateException.class,
                () -> service.restyle(1L, 2L, "白描", true));
        assertEquals("engine unavailable", error.getMessage());
        verifyNoInteractions(repository, files, storage, png);
    }

    @Test
    void saveWritesManualContentAndUpdatesTheGalleryTimestamp() throws Exception {
        Path path = directory.resolve("diagram.drawio");
        Files.writeString(path, "original");
        var diagram = new ProjectFile();
        diagram.setId(10L);
        diagram.setProjectId(1L);
        diagram.setName("diagram.drawio");
        diagram.setFilePath("diagram.drawio");
        diagram.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(files.getFile(10L)).thenReturn(diagram);
        when(storage.resolve("diagram.drawio")).thenReturn(path);
        service.saveDrawio(1L, 10L, "manually edited", "");
        assertEquals("manually edited", Files.readString(path));
        assertTrue(diagram.getUpdatedAt().isAfter(LocalDateTime.of(2026, 1, 1, 0, 0)));
        verify(repository).save(diagram);
    }
}
