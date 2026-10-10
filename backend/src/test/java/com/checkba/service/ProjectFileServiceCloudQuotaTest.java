// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.dto.ProjectFileBatchRequest;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.mobile.CloudStorageQuotaService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectFileServiceCloudQuotaTest {
    private final ProjectFileRepository files = mock(ProjectFileRepository.class);
    private final StorageServiceFactory factory = mock(StorageServiceFactory.class);
    private final StorageService storage = mock(StorageService.class);
    private final CloudStorageQuotaService quota = mock(CloudStorageQuotaService.class);
    private final ProjectFileService service = new ProjectFileService(files,
            mock(com.checkba.service.ai.ProjectRagService.class), factory,
            mock(com.checkba.version.WorkSessionService.class), mock(UserService.class),
            mock(com.checkba.service.quota.StageQuotaService.class),
            mock(com.checkba.service.telemetry.TelemetryService.class),
            mock(com.checkba.service.evidence.EvidenceLinkService.class));
    private ProjectFile file;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "cloudStorageQuota", quota);
        when(quota.isEnabled()).thenReturn(true);
        when(factory.getStorageService()).thenReturn(storage);
        file = new ProjectFile(); file.setId(1L); file.setProjectId(1L); file.setName("a.txt");
        file.setIsFolder(false); file.setFileSize(1L); file.setFilePath("projects/1/a.txt");
        when(files.findById(1L)).thenReturn(Optional.of(file));
        when(files.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void copyingPhysicalFileCannotBypassQuotaWithUnderreportedMetadata() {
        when(quota.measuredBytes(file)).thenReturn(1000L);
        doThrow(new IllegalArgumentException("full")).when(quota).requireProjectCapacity(1L, 1000L);
        ProjectFileBatchRequest request = new ProjectFileBatchRequest();
        request.setFileIds(List.of(1L));
        assertThrows(IllegalArgumentException.class, () -> service.batchCopy(1L, request, 7L));
        verify(files, never()).save(any());
        verifyNoInteractions(storage);
    }

    @Test void textExpansionFailsBeforeWritingBytes() {
        doThrow(new IllegalArgumentException("full")).when(quota).requireFileCapacity(file, 5, false);
        assertThrows(IllegalArgumentException.class, () -> service.overwriteTextContent(1L, 1L, "hello", 7L));
        verify(storage, never()).save(anyString(), any());
    }

    @Test void metadataUpdateCannotHidePhysicalSizeOrChangeCloudPath() {
        when(files.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(1L, null, "a.txt")).thenReturn(Optional.of(file));
        when(quota.measuredBytes(file)).thenReturn(1000L);
        ProjectFile updated = service.createOrUpdateFile(1L, null, "a.txt", "txt", 0L, null, null, 7L);
        assertEquals(1000L, updated.getFileSize());
        assertThrows(IllegalArgumentException.class, () -> service.createOrUpdateFile(1L, null, "a.txt", "txt", 0L, "projects/1/other.txt", null, 7L));
    }

    @Test void failedPhysicalDeletionKeepsCloudStorageRecordEvenWhenClientSaysHandled() {
        doThrow(new StorageException("disk unavailable")).when(storage).delete(file.getFilePath());
        assertThrows(IllegalStateException.class, () -> service.permDelete(1L, 7L, true));
        verify(files, never()).deleteById(anyLong());
    }

    @Test void batchCopyRollbackRemovesAlreadyWrittenCloudCopies() throws Exception {
        when(quota.measuredBytes(file)).thenReturn(1000L);
        when(storage.load(file.getFilePath())).thenReturn(new org.springframework.core.io.ByteArrayResource(new byte[1000]));
        doNothing().doThrow(new IllegalArgumentException("full")).when(quota).requireProjectCapacity(1L, 1000L);
        var request = new ProjectFileBatchRequest(); request.setFileIds(List.of(1L, 1L));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(IllegalArgumentException.class, () -> service.batchCopy(1L, request, 7L));
            var callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertFalse(callbacks.isEmpty());
            callbacks.forEach(callback -> callback.afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(quota).removeRolledBackFile(eq(1L), argThat(path -> path.startsWith("projects/1/") && !path.equals(file.getFilePath())));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test void oversizedTemplateIsRemovedAndCreationFailsInsteadOfLeavingUncountedBytes() {
        when(files.maxSortOrder(1L, null)).thenReturn(0);
        when(storage.exists(file.getFilePath())).thenReturn(false, true);
        doThrow(new IllegalArgumentException("full")).when(quota).requireCurrentProjectUsage(1L);
        assertThrows(IllegalArgumentException.class, () -> service.createFile(1L, null, "a.txt", "txt", 0L, file.getFilePath(), null, 7L));
        verify(storage).delete(file.getFilePath());
    }
}
