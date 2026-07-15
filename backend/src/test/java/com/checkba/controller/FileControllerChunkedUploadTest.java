package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ai.AutoTaggingService;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 锁定分片上传的路径一致性：首块(save)、追加块(append)、断点查询(getSize)
 * 必须解析到 DB 记录的同一 filePath。此前追加块直接用裸 wpsFileId 作路径，
 * >5MB 文件的第 2+ 块被写进存储根的孤儿文件，正式路径只剩首块 —— 下载得到
 * 截断的 zip，编辑器"文档加载失败"。
 */
@ExtendWith(MockitoExtension.class)
class FileControllerChunkedUploadTest {

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
    private StorageService storageService;

    @InjectMocks
    private FileController controller;

    private static final String WPS_FILE_ID = "project_4_doc_1784101301299_a5d5o6u";
    private static final String FILE_PATH = "projects/4/big.docx";

    private ProjectFile projectFile() {
        ProjectFile pf = new ProjectFile();
        pf.setId(16L);
        pf.setProjectId(4L);
        pf.setName("big.docx");
        pf.setFileType("docx");
        pf.setFilePath(FILE_PATH);
        pf.setWpsFileId(WPS_FILE_ID);
        return pf;
    }

    @Test
    void appendChunkGoesToResolvedFilePathNotRawFileId() throws Exception {
        when(projectFileRepository.findByWpsFileId(WPS_FILE_ID)).thenReturn(List.of(projectFile()));
        when(projectFileRepository.sumSizeByProjectId(4L)).thenReturn(0L);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.append(any(), any())).thenReturn(FILE_PATH);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/octet-stream");
        request.setContent(new byte[]{1, 2, 3});

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(4L, 7L)).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp =
                controller.uploadFile(WPS_FILE_ID, null, 5242880L, "sess", null, request);

            assertEquals(200, resp.getStatusCode().value());
        }

        verify(storageService).append(eq(FILE_PATH), any(InputStream.class));
        verify(storageService, never()).append(eq(WPS_FILE_ID), any(InputStream.class));
    }

    @Test
    void uploadStatusReadsSizeFromResolvedFilePath() {
        when(projectFileRepository.findByWpsFileId(WPS_FILE_ID)).thenReturn(List.of(projectFile()));
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.getSize(FILE_PATH)).thenReturn(5242880L);

        ResponseEntity<Map<String, Object>> resp = controller.getUploadStatus(WPS_FILE_ID);

        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertEquals(5242880L, data.get("uploadedSize"));
        verify(storageService).getSize(FILE_PATH);
        verify(storageService, never()).getSize(WPS_FILE_ID);
    }
}
