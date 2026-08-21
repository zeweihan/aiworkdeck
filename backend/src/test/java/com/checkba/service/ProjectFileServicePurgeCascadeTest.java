package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 彻底删除 → EvidenceLinkService.onFilePurged 级联（spec §2.1）；软删不级联（面板灰显）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectFileServicePurgeCascadeTest {

    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private ProjectRagService projectRagService;
    @Mock private StorageServiceFactory storageServiceFactory;
    @Mock private StorageService storageService;
    @Mock private EvidenceLinkService evidenceLinkService;
    @Mock private com.checkba.version.WorkSessionService workSessionService;
    @Mock private com.checkba.service.telemetry.TelemetryService telemetryService;
    @Mock private UserService userService;

    @InjectMocks private ProjectFileService projectFileService;

    private ProjectFile file(long id, long pid) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(pid);
        f.setName("执照.pdf");
        f.setIsFolder(false);
        f.setIsDeleted(true);
        f.setFilePath("p/执照.pdf");
        return f;
    }

    @Test
    void permDeleteCascadesToEvidenceBeforeRowDelete() {
        when(projectFileRepository.findById(11L)).thenReturn(Optional.of(file(11L, 1L)));
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);

        projectFileService.permDelete(11L, 9L);

        InOrder order = inOrder(evidenceLinkService, projectFileRepository);
        order.verify(evidenceLinkService).onFilePurged(1L, 11L);
        order.verify(projectFileRepository).deleteById(11L);
    }

    @Test
    void softDeleteDoesNotTouchEvidence() {
        ProjectFile f = file(11L, 1L);
        f.setIsDeleted(false);
        when(projectFileRepository.findById(11L)).thenReturn(Optional.of(f));
        lenient().when(projectFileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        projectFileService.delete(11L, 9L);

        verify(evidenceLinkService, never()).onFilePurged(any(), any());
    }
}
