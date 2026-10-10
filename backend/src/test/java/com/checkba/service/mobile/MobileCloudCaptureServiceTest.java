// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.mobile;
import com.checkba.model.entity.*;
import com.checkba.repository.*;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class MobileCloudCaptureServiceTest {
    MobileCloudReceiptRepository receipts=mock(MobileCloudReceiptRepository.class);
    ProjectRepository projects=mock(ProjectRepository.class);
    UserRepository users=mock(UserRepository.class);
    ProjectFileService files=mock(ProjectFileService.class);
    StorageServiceFactory storage=mock(StorageServiceFactory.class);
    StorageService bytes=mock(StorageService.class);
    CloudStorageQuotaService quota=mock(CloudStorageQuotaService.class);
    ProjectCatalogService catalog=mock(ProjectCatalogService.class);
    MobileCloudCaptureService service=new MobileCloudCaptureService(receipts,projects,users,files,storage,quota,catalog);
    String clientId="11111111-1111-4111-8111-111111111111";
    @BeforeEach void setup() {
        Project project=new Project();project.setId(8L);project.setUserId(1L);
        when(projects.findById(8L)).thenReturn(Optional.of(project));when(users.lockStorageOwner(1L)).thenReturn(Optional.of(new User()));
        when(storage.getStorageService()).thenReturn(bytes);
        ReflectionTestUtils.setField(service,"relayItems",mock(MobileMediaInboxRepository.class));
    }
    @Test void replayReturnsSameIdentityAfterSubscriptionExpiresWithoutNewBytes() {
        MobileCloudReceipt receipt=new MobileCloudReceipt();receipt.setId(2L);receipt.setProjectId(8L);receipt.setFileId(3L);
        receipt.setProjectUid(UUID.randomUUID().toString());receipt.setFileUid(UUID.randomUUID().toString());receipt.setClientMediaId(clientId);
        when(receipts.findByUserIdAndClientMediaId(1L,clientId)).thenReturn(Optional.of(receipt));
        var result=service.store(1L,"8",clientId,"x.m4a","audio",null,3,new ByteArrayInputStream(new byte[3]));
        assertEquals(receipt.getFileUid(),result.get("fileUid"));assertEquals(receipt.getProjectUid(),result.get("projectUid"));
        verifyNoInteractions(quota,bytes,files);
    }
    @Test void quotaRejectionCannotWriteAnyBytesOrMetadata() {
        doThrow(new IllegalArgumentException("full")).when(quota).requirePersistentCapacity(1L,3);
        assertThrows(IllegalArgumentException.class,()->service.store(1L,"8",clientId,"x.m4a","audio",null,3,new ByteArrayInputStream(new byte[3])));
        verifyNoInteractions(bytes,files);verify(receipts,never()).saveAndFlush(any());
    }
    @Test void truncatedUploadLeavesNoFileRowOrReceiptAndDeletesTemporaryBytes() throws Exception {
        when(bytes.save(anyString(),any(InputStream.class))).thenAnswer(inv->{inv.getArgument(1,InputStream.class).transferTo(OutputStream.nullOutputStream());return inv.getArgument(0);});
        assertThrows(IllegalStateException.class,()->service.store(1L,"8",clientId,"x.m4a","audio",null,30,new ByteArrayInputStream(new byte[3])));
        verify(bytes).delete(contains(".capture-"));verifyNoInteractions(files);verify(receipts,never()).saveAndFlush(any());
    }
    @Test void wrongOwnerFailsBeforeLockQuotaOrBytes() {
        assertThrows(IllegalArgumentException.class,()->service.store(2L,"8",clientId,"x.m4a","audio",null,3,new ByteArrayInputStream(new byte[3])));
        verifyNoInteractions(users,quota,bytes,files);
    }
}
