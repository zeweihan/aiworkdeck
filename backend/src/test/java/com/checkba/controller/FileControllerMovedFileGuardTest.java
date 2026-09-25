// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ai.AutoTaggingService;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.version.WorkSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BUG-14（v0.49.0 真机 C4-03）：文档在 Finder 里被改名 / 移走之后，编辑器的自动保存按
 * 旧行的旧路径上传，{@code save()} 的 REPLACE_EXISTING + createDirectories 语义把旧文件名
 * 在原地重新建了出来——磁盘上一新一旧两份，改名的那份停在旧内容。
 *
 * 编辑器保存时带 {@code mustExist=1}（它装载的就是磁盘上已有的那份文件）：
 * 目标路径已经不在、或这一行已进回收站，一律 409，绝不在旧路径重建。
 * 不带这个参数的上传（新建文件先建行再传字节、录音分片等）行为不变。
 */
@ExtendWith(MockitoExtension.class)
class FileControllerMovedFileGuardTest {

    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private ProjectMemberService projectMemberService;
    @Mock private StorageServiceFactory storageServiceFactory;
    @Mock private ProjectRagService projectRagService;
    @Mock private AutoTaggingService autoTaggingService;
    @Mock private StorageService storageService;
    @Mock private WorkSessionService workSessionService;

    @InjectMocks
    private FileController controller;

    private static final String FILE_PATH = "projects/245/C4-tmp/C4-long80.docx";

    private ProjectFile row(boolean deleted) {
        ProjectFile pf = new ProjectFile();
        pf.setId(16L);
        pf.setProjectId(245L);
        pf.setName("C4-long80.docx");
        pf.setFileType("docx");
        pf.setFilePath(FILE_PATH);
        pf.setFileSize(67603L);
        pf.setIsDeleted(deleted);
        return pf;
    }

    private MockHttpServletRequest multipart(boolean mustExist) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/octet-stream");
        request.setContent(new byte[] {1, 2, 3});
        if (mustExist) request.setParameter("mustExist", "1");
        return request;
    }

    private ResponseEntity<Map<String, Object>> upload(MockHttpServletRequest request) {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(245L, 7L)).thenReturn(true);
            return controller.uploadFile("16", null, null, "sess", null, request);
        }
    }

    @Test
    void editorSaveToAPathThatNoLongerExistsIsRejectedInsteadOfRecreatingTheOldName() throws Exception {
        when(projectFileRepository.findById(16L)).thenReturn(java.util.Optional.of(row(false)));
        when(projectFileRepository.sumSizeByProjectId(245L)).thenReturn(0L);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.exists(FILE_PATH)).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = upload(multipart(true));

        assertEquals(409, resp.getStatusCode().value());
        assertNotEquals(0, resp.getBody().get("code"));
        assertEquals("FILE_MOVED", resp.getBody().get("reason"));
        verify(storageService, never()).save(any(), any());
        verify(storageService, never()).append(any(), any());
    }

    @Test
    void editorSaveToARecycledRowIsRejected() throws Exception {
        when(projectFileRepository.findById(16L)).thenReturn(java.util.Optional.of(row(true)));
        when(projectFileRepository.sumSizeByProjectId(245L)).thenReturn(0L);

        ResponseEntity<Map<String, Object>> resp = upload(multipart(true));

        assertEquals(409, resp.getStatusCode().value());
        assertEquals("FILE_MOVED", resp.getBody().get("reason"));
        verify(storageService, never()).save(any(), any());
    }

    @Test
    void editorSaveToAnExistingFileStillWrites() throws Exception {
        when(projectFileRepository.findById(16L)).thenReturn(java.util.Optional.of(row(false)));
        when(projectFileRepository.sumSizeByProjectId(245L)).thenReturn(0L);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.exists(FILE_PATH)).thenReturn(true);
        when(storageService.save(any(), any())).thenReturn(FILE_PATH);

        ResponseEntity<Map<String, Object>> resp = upload(multipart(true));

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(0, resp.getBody().get("code"));
        verify(storageService).save(eq(FILE_PATH), any(InputStream.class));
    }

    /** 新建文件是「先建行、再传第一笔字节」，那时磁盘上本来就没有它——不带参数的上传不受影响。 */
    @Test
    void uploadsWithoutTheFlagStillCreateTheFirstBytes() throws Exception {
        when(projectFileRepository.findById(16L)).thenReturn(java.util.Optional.of(row(false)));
        when(projectFileRepository.sumSizeByProjectId(245L)).thenReturn(0L);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.save(any(), any())).thenReturn(FILE_PATH);

        ResponseEntity<Map<String, Object>> resp = upload(multipart(false));

        assertEquals(200, resp.getStatusCode().value());
        verify(storageService, never()).exists(any());
        verify(storageService).save(eq(FILE_PATH), any(InputStream.class));
    }
}
