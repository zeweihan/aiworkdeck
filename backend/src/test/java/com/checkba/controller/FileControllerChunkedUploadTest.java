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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    @Mock
    private WorkSessionService workSessionService;

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
            when(projectMemberService.hasWritePermission(4L, 7L)).thenReturn(true);

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

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(4L, 7L)).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp =
                    controller.getUploadStatus(WPS_FILE_ID, null, "sess");

            assertEquals(200, resp.getStatusCode().value());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            assertEquals(5242880L, data.get("uploadedSize"));
        }
        verify(storageService).getSize(FILE_PATH);
        verify(storageService, never()).getSize(WPS_FILE_ID);
    }

    /** 断点续传的进度查询同样是越权面：匿名调用可按 fileId 枚举文件是否存在及其大小。 */
    @Test
    void uploadStatusRejectsAnonymousCaller() {
        when(projectFileRepository.findByWpsFileId(WPS_FILE_ID)).thenReturn(List.of(projectFile()));

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            ResponseEntity<Map<String, Object>> resp =
                    controller.getUploadStatus(WPS_FILE_ID, null, null);

            assertEquals(403, resp.getStatusCode().value());
        }
        verify(storageService, never()).getSize(anyString());
    }

    /**
     * 版本变更信号必须收窄到"整个文件上传完成"才发一次，而不是每个分片都发。
     * 复用了 uploadFile 里已有的 currentSize >= totalSize 完成判定（原本只用于触发 RAG）。
     * 中间分片（currentSize < totalSize）：不应发信号。
     */
    @Test
    void intermediateChunkDoesNotEmitChangeSignal() throws Exception {
        when(projectFileRepository.findByWpsFileId(WPS_FILE_ID)).thenReturn(List.of(projectFile()));
        when(projectFileRepository.sumSizeByProjectId(4L)).thenReturn(0L);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.append(any(), any())).thenReturn(FILE_PATH);
        // 文件总大小 10MB，本次追加后落盘 5MB —— 还没传完
        when(storageService.getSize(FILE_PATH)).thenReturn(5242880L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/octet-stream");
        request.setContent(new byte[]{1, 2, 3});
        request.addHeader("X-File-Total-Size", "10485760");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(4L, 7L)).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp =
                controller.uploadFile(WPS_FILE_ID, null, 5242880L, "sess", null, request);

            assertEquals(200, resp.getStatusCode().value());
        }

        verify(workSessionService, never()).onChangeSignal(anyLong(), any(), any());
    }

    /**
     * 末块（currentSize >= totalSize，整文件传完）：应发一次信号。
     */
    @Test
    void finalChunkEmitsChangeSignalExactlyOnce() throws Exception {
        when(projectFileRepository.findByWpsFileId(WPS_FILE_ID)).thenReturn(List.of(projectFile()));
        when(projectFileRepository.sumSizeByProjectId(4L)).thenReturn(0L);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.append(any(), any())).thenReturn(FILE_PATH);
        // 落盘大小已达到声明的总大小 —— 整个文件传完
        when(storageService.getSize(FILE_PATH)).thenReturn(10485760L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/octet-stream");
        request.setContent(new byte[]{1, 2, 3});
        request.addHeader("X-File-Total-Size", "10485760");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(4L, 7L)).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp =
                controller.uploadFile(WPS_FILE_ID, null, 5242880L, "sess", null, request);

            assertEquals(200, resp.getStatusCode().value());
        }

        verify(workSessionService, times(1)).onChangeSignal(eq(4L), any(), any());
    }

    /**
     * resolveProjectFileForUpload 的数字 id 回退：wpsFileId 为 null 的行（克隆/退回/
     * 切线等场景新建的节点，manifest v2 只带 uid/relPath）必须能靠数字 id 命中数据库
     * 记录，字节写到该记录的 filePath，而不是把裸 id 字符串当孤儿路径存起来。
     */
    @Test
    void uploadFileResolvesNumericIdWhenWpsFileIdIsNull() throws Exception {
        ProjectFile pf = projectFile();
        pf.setWpsFileId(null);
        String numericId = String.valueOf(pf.getId());

        when(projectFileRepository.findById(pf.getId())).thenReturn(java.util.Optional.of(pf));
        when(projectFileRepository.sumSizeByProjectId(4L)).thenReturn(0L);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.save(any(), any())).thenReturn(FILE_PATH);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/octet-stream");
        request.setContent(new byte[]{1, 2, 3});

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(4L, 7L)).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp =
                controller.uploadFile(numericId, null, null, "sess", null, request);

            assertEquals(200, resp.getStatusCode().value());
            assertEquals(0, resp.getBody().get("code"));
        }

        // 字节必须落到该文件记录已有的 filePath，不能落到裸数字 id 拼出来的孤儿路径
        verify(storageService).save(eq(FILE_PATH), any(InputStream.class));
        verify(storageService, never()).save(eq(numericId), any(InputStream.class));
    }

    /**
     * 鉴权不能因为走了数字 id 回退就跳过：数字 id 指向的文件若属于调用者无权限的
     * 项目，必须照样拒绝（IDOR 面）——不能因为找到了记录就默认放行。
     */
    @Test
    void uploadFileNumericIdStillEnforcesProjectAuthorization() throws Exception {
        ProjectFile foreign = new ProjectFile();
        foreign.setId(99L);
        foreign.setProjectId(5L);
        foreign.setName("other.docx");
        foreign.setFileType("docx");
        foreign.setFilePath("projects/5/other.docx");
        foreign.setWpsFileId(null);

        when(projectFileRepository.findById(99L)).thenReturn(java.util.Optional.of(foreign));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/octet-stream");
        request.setContent(new byte[]{1, 2, 3});

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            // 已登录，但不是该文件所属项目（5）的成员
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(5L, 7L)).thenReturn(false);

            ResponseEntity<Map<String, Object>> resp =
                controller.uploadFile("99", null, null, "sess", null, request);

            assertEquals(403, resp.getStatusCode().value());
        }

        verifyNoInteractions(storageServiceFactory);
        verify(storageService, never()).save(any(), any());
        verify(storageService, never()).append(any(), any());
    }
}
