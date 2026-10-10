// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.*;
import com.checkba.service.mobile.CloudStorageQuotaService;
import com.checkba.storage.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectDeleteCloudQuotaTest {
    @Test void failedCloudDeletionPreservesProjectAndFileAccounting() {
        var projects = mock(ProjectRepository.class);
        var files = mock(ProjectFileRepository.class);
        var resolver = mock(ProjectStorageResolver.class);
        var entityManager = mock(EntityManager.class);
        var factory = mock(StorageServiceFactory.class);
        var storage = mock(StorageService.class);
        var quota = mock(CloudStorageQuotaService.class);
        var service = new ProjectService(projects, mock(ProjectMemberRepository.class), mock(UserRepository.class),
                files, mock(ProjectProfileFieldRepository.class), mock(TushareService.class), mock(ProjectVariableService.class),
                mock(com.checkba.service.telemetry.TelemetryService.class), resolver,
                mock(com.checkba.version.ProjectRepoService.class), mock(com.checkba.version.memory.MemoryRepoService.class),
                mock(MemoryRemoteRepository.class), mock(CompletionEntryRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "cloudStorageQuota", quota);
        ReflectionTestUtils.setField(service, "storageServiceFactory", factory);
        when(quota.isEnabled()).thenReturn(true);
        when(factory.getStorageService()).thenReturn(storage);
        when(entityManager.find(Project.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(new Project());
        var file = new ProjectFile(); file.setFilePath("projects/1/a.txt"); file.setIsFolder(false);
        when(files.findByProjectId(1L)).thenReturn(List.of(file));
        doThrow(new StorageException("disk unavailable")).when(storage).delete("projects/1/a.txt");

        assertThrows(StorageException.class, () -> service.deleteProject(1L));
        verify(quota).lockProjectForWrite(1L);
        verify(projects, never()).deleteById(anyLong());
        verify(entityManager, never()).createQuery(anyString());
        verify(resolver, never()).invalidate(anyLong());
    }
}
