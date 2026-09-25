// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v0.49.0 真机 BUG-03：回收站「彻底删除」对「物理文件已经不在」或「记录已经被级联带走」的条目
 * 必须幂等成功——目标就是让这条记录消失，它已经消失了就是成功，不能永久卡在回收站里。
 */
@ExtendWith(MockitoExtension.class)
class ProjectFileServicePermDeleteIdempotentTest {

    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private ProjectRagService projectRagService;
    @Mock private StorageServiceFactory storageServiceFactory;
    @Mock private StorageService storageService;
    @Mock private EvidenceLinkService evidenceLinkService;
    @Mock private com.checkba.version.WorkSessionService workSessionService;
    @Mock private com.checkba.service.telemetry.TelemetryService telemetryService;
    @Mock private UserService userService;

    @InjectMocks private ProjectFileService projectFileService;

    @Test
    void permDeleteOfAlreadyGoneRecordIsANoOpSuccess() {
        // 同一批里先彻底删了父文件夹，级联把子行带走；紧接着对子行的那次调用
        when(projectFileRepository.findById(42L)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> projectFileService.permDelete(42L, 9L));
        verify(projectFileRepository, never()).deleteById(anyLong());
    }

    @Test
    void permDeleteStillRemovesRowWhenPhysicalFileIsMissing() {
        ProjectFile f = new ProjectFile();
        f.setId(11L);
        f.setProjectId(1L);
        f.setName("qa-perf-1.txt");
        f.setIsFolder(false);
        f.setIsDeleted(true);
        f.setFilePath("projects/1/perf100/qa-perf-1.txt");
        when(projectFileRepository.findById(11L)).thenReturn(Optional.of(f));
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        doThrow(new UncheckedIOException(new NoSuchFileException("projects/1/perf100/qa-perf-1.txt")))
                .when(storageService).delete(anyString());

        assertDoesNotThrow(() -> projectFileService.permDelete(11L, 9L));
        verify(projectFileRepository).deleteById(11L);
    }
}
