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

import java.util.List;
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
        when(projectFileRepository.findByWpsFileId("9")).thenReturn(List.of());

        ResponseEntity<Resource> resp = controller.downloadFile("9", "sess", null);
        assertEquals(404, resp.getStatusCode().value());
    }
}
