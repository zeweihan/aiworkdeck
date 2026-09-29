// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ai.AutoTaggingService;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * 锁定 FileController 的鉴权：此前 download/text/compare/upload 完全无鉴权，
 * 按数字 id 可遍历下载任意项目文件。现要求 token(query) 或 X-Session-Id(header) → 项目成员。
 */
@ExtendWith(MockitoExtension.class)
class FileControllerAuthTest {

    @Mock
    private ProjectFileRepository projectFileRepository;
    @Mock
    private ProjectMemberService projectMemberService;
    @Mock
    private StorageServiceFactory storageServiceFactory;
    @Mock
    private ProjectRagService projectRagService;
    @Mock
    private AutoTaggingService autoTaggingService;

    @Mock
    private com.checkba.storage.ProjectStorageResolver storageResolver;

    @InjectMocks
    private FileController controller;

    @Test
    void downloadRejectsNonMemberWith403() {
        ProjectFile pf = new ProjectFile();
        pf.setId(5L);
        pf.setProjectId(42L);
        pf.setFilePath("projects/42/x.docx");
        when(projectFileRepository.findById(5L)).thenReturn(Optional.of(pf));

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(false);

            ResponseEntity<Resource> resp = controller.downloadFile("5", "sess", null);
            assertEquals(403, resp.getStatusCode().value());
        }
    }

    @Test
    void downloadRejectsUnknownFileWith404() {
        when(projectFileRepository.findById(9L)).thenReturn(Optional.empty());

        ResponseEntity<Resource> resp = controller.downloadFile("9", "sess", null);
        assertEquals(404, resp.getStatusCode().value());
    }

    /** dev-board#1035：非数字 fileId 不再按 wpsFileId 回退查找，下载与上传一律 404。 */
    @Test
    void nonNumericFileIdIsNotFoundWithoutWpsFileIdFallback() throws Exception {
        ResponseEntity<Resource> down = controller.downloadFile("project_4_doc_1_abc", "sess", null);
        assertEquals(404, down.getStatusCode().value());

        ResponseEntity<java.util.Map<String, Object>> up = controller.uploadFile(
                "project_4_doc_1_abc", null, null, "sess", null, new org.springframework.mock.web.MockHttpServletRequest());
        assertEquals(404, up.getStatusCode().value());
        org.mockito.Mockito.verifyNoInteractions(projectFileRepository);
    }
}
