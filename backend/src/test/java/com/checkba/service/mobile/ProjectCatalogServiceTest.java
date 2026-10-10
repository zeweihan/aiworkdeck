// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.mobile;
import com.checkba.model.entity.*;
import com.checkba.repository.*;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectCatalogServiceTest {
    ProjectRepository projects=mock(ProjectRepository.class);
    ProjectFileRepository files=mock(ProjectFileRepository.class);
    MobileProjectDirRepository dirs=mock(MobileProjectDirRepository.class);
    AddinProjectLinkRepository links=mock(AddinProjectLinkRepository.class);
    MobileProjectManifestRepository manifests=mock(MobileProjectManifestRepository.class);
    MobileRelayStoreService relay=mock(MobileRelayStoreService.class);
    StorageServiceFactory storage=mock(StorageServiceFactory.class);
    com.checkba.storage.StorageService bytes=mock(com.checkba.storage.StorageService.class);
    ProjectCatalogService service=new ProjectCatalogService(projects,files,dirs,links,manifests,relay,storage);
    @BeforeEach void storage(){when(storage.getStorageService()).thenReturn(bytes);}
    Project cloud(long id,String name) { Project p=new Project();p.setId(id);p.setUserId(1L);p.setName(name);p.setUid(UUID.randomUUID().toString());return p; }
    MobileProjectDir desktop(String device,String key,String name,String uid,Long cloudId) {
        MobileProjectDir d=new MobileProjectDir();d.setUserId(1L);d.setDeviceId(device);d.setProjectKey(key);d.setName(name);d.setProjectUid(uid);d.setCloudProjectId(cloudId);return d;
    }
    @Test void sameNamesNeverMergeButStableIdentitiesDo() {
        String uid=UUID.randomUUID().toString();
        when(dirs.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(desktop("a","1","same",uid,null),desktop("b","1","same",uid,null),desktop("c","1","same",null,null)));
        var catalog=service.catalog(1L);assertEquals(2,catalog.size());
        assertEquals(2,((List<?>)catalog.get(0).get("locations")).size());
        assertEquals(service.catalog(1L),catalog,"legacy identities remain stable on repeated reads");
    }
    @Test void foreignCloudMappingCannotMergeOrRevealOtherAccounts() {
        Project owned=cloud(8L,"owned");
        when(projects.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(owned));
        when(dirs.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(desktop("a","1","same",null,99L)));
        var catalog=service.catalog(1L);assertEquals(2,catalog.size());
        assertNotEquals(owned.getUid(),catalog.get(0).get("projectUid"));
        assertThrows(IllegalArgumentException.class,()->service.fileList(2L,owned.getUid()));
    }
    @Test void verifiedCloudMappingAndPluginShadowBecomeLocationsNotDuplicateProjects() {
        Project owned=cloud(8L,"case"),shadow=cloud(9L,"shadow");
        when(projects.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(owned,shadow));
        when(dirs.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(desktop("a","1","case",null,8L)));
        AddinProjectLink link=new AddinProjectLink();link.setCloudProjectId(9L);link.setDeviceId("a");link.setProjectKey("1");
        when(links.findByUserId(1L)).thenReturn(List.of(link));
        var catalog=service.catalog(1L);assertEquals(1,catalog.size());assertEquals(owned.getUid(),catalog.get(0).get("projectUid"));
        assertEquals(3,((List<?>)catalog.get(0).get("locations")).size());
    }
    @Test void cloudAndCachedDesktopFilesMergeOnlyByUidAndKeepRoutes() {
        Project owned=cloud(8L,"case");String fileUid=UUID.randomUUID().toString();
        when(projects.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(owned));
        when(dirs.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(desktop("a","1","case",null,8L)));
        ProjectFile file=new ProjectFile();file.setId(10L);file.setName("contract.docx");file.setUid(fileUid);file.setFilePath("projects/8/contract.docx");file.setFileSize(1L);
        when(bytes.exists(file.getFilePath())).thenReturn(true);when(bytes.getSize(file.getFilePath())).thenReturn(1024L);
        when(files.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(8L)).thenReturn(List.of(file));
        MobileProjectManifest manifest=new MobileProjectManifest();manifest.setPayloadJson("{\"files\":[{\"id\":\"2\",\"uid\":\""+fileUid+"\",\"name\":\"old.docx\"},{\"id\":\"10\",\"name\":\"another.docx\"}],\"truncated\":false}");
        when(manifests.findByUserIdAndDeviceIdAndProjectKey(1L,"a","1")).thenReturn(Optional.of(manifest));
        var result=service.fileList(1L,owned.getUid());var resultFiles=(List<Map<String,Object>>)result.get("files");
        assertEquals(2,resultFiles.size());assertEquals("cloud",resultFiles.get(0).get("source"));
        assertEquals(1024L,resultFiles.get(0).get("size"),"Downloads use real blob length despite stale metadata");
        assertEquals("a",resultFiles.get(1).get("deviceId"));assertEquals("1",resultFiles.get(1).get("projectKey"));
    }
}
