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
 * 裸 octet-stream 上传（分片上传/桌面端三个真实调用点全走这条路）空 body 的围栏。
 *
 * 客户端在 X-File-Total-Size 里自己声明了非零总大小，却送来空 body——这是客户端自相
 * 矛盾。此前 else 分支（非 multipart）没有任何空校验，直接把空输入流交给
 * storageService.save/append，REPLACE_EXISTING 语义会把已有的非空文件截成 0 字节，
 * 还照常回 code:0 骗前端"上传成功"。必须在写盘之前（storageService.save/append 调用
 * 之前）就拒绝，而不是事后补救。
 *
 * X-File-Total-Size 缺失或显式为 0 时不能拦——保存一个空 .txt 是合法场景。
 */
@ExtendWith(MockitoExtension.class)
class FileControllerEmptyBodyGuardTest {

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

    /**
     * 核心回归：已有非空文件 + 客户端声明了非零总大小的空 body → 必须在写盘前拒绝，
     * 不能截断已有文件，也不能回 code:0。
     */
    @Test
    void emptyOctetStreamBodyWithDeclaredTotalSizeIsRejectedBeforeWrite() throws Exception {
        when(projectFileRepository.findByWpsFileId(WPS_FILE_ID)).thenReturn(List.of(projectFile()));
        when(projectFileRepository.sumSizeByProjectId(4L)).thenReturn(0L);
        // 注意：不 stub getStorageService()——修复后的守卫必须在碰存储层之前就拒绝，
        // 一旦实现改成先取存储服务再判断，这里会因为 UnnecessaryStubbing 之外的原因
        // （NPE）失败，能防止守卫被误挪到写盘之后。

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/octet-stream");
        request.setContent(new byte[0]); // 空 body，getContentLengthLong() == 0
        request.addHeader("X-File-Total-Size", "1024"); // 客户端自己声明了非零总大小

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(4L, 7L)).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp =
                    controller.uploadFile(WPS_FILE_ID, null, null, "sess", null, request);

            assertEquals(400, resp.getStatusCode().value(), "自相矛盾的空 body 必须被拒绝，不能回 200");
            assertNotEquals(0, resp.getBody().get("code"), "响应体不能是 code:0（骗前端上传成功）");
        }

        verify(storageService, never()).save(any(), any());
        verify(storageService, never()).append(any(), any());
        verify(projectFileRepository, never()).save(any());
    }

    /** X-File-Total-Size 缺失时，空 body 是合法场景（保存一个空文件），不能被新校验误伤。 */
    @Test
    void emptyBodyWithoutTotalSizeHeaderIsAllowed() throws Exception {
        when(projectFileRepository.findByWpsFileId(WPS_FILE_ID)).thenReturn(List.of(projectFile()));
        when(projectFileRepository.sumSizeByProjectId(4L)).thenReturn(0L);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.save(any(), any())).thenReturn(FILE_PATH);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/octet-stream");
        request.setContent(new byte[0]);
        // 无 X-File-Total-Size 头

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(4L, 7L)).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp =
                    controller.uploadFile(WPS_FILE_ID, null, null, "sess", null, request);

            assertEquals(200, resp.getStatusCode().value());
            assertEquals(0, resp.getBody().get("code"));
        }

        verify(storageService).save(eq(FILE_PATH), any(InputStream.class));
    }

    /** X-File-Total-Size 显式为 0 同样是合法的空文件声明，不能被拦。 */
    @Test
    void emptyBodyWithZeroTotalSizeHeaderIsAllowed() throws Exception {
        when(projectFileRepository.findByWpsFileId(WPS_FILE_ID)).thenReturn(List.of(projectFile()));
        when(projectFileRepository.sumSizeByProjectId(4L)).thenReturn(0L);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.save(any(), any())).thenReturn(FILE_PATH);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/octet-stream");
        request.setContent(new byte[0]);
        request.addHeader("X-File-Total-Size", "0");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(4L, 7L)).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp =
                    controller.uploadFile(WPS_FILE_ID, null, null, "sess", null, request);

            assertEquals(200, resp.getStatusCode().value());
            assertEquals(0, resp.getBody().get("code"));
        }

        verify(storageService).save(eq(FILE_PATH), any(InputStream.class));
    }

    /** 非空 body 即便声明了总大小也必须正常放行，不能被新校验误伤真实分片。 */
    @Test
    void nonEmptyBodyWithDeclaredTotalSizeStillWorks() throws Exception {
        when(projectFileRepository.findByWpsFileId(WPS_FILE_ID)).thenReturn(List.of(projectFile()));
        when(projectFileRepository.sumSizeByProjectId(4L)).thenReturn(0L);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.save(any(), any())).thenReturn(FILE_PATH);
        when(storageService.getSize(FILE_PATH)).thenReturn(1024L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/octet-stream");
        request.setContent(new byte[]{1, 2, 3});
        request.addHeader("X-File-Total-Size", "1024");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(4L, 7L)).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp =
                    controller.uploadFile(WPS_FILE_ID, null, null, "sess", null, request);

            assertEquals(200, resp.getStatusCode().value());
            assertEquals(0, resp.getBody().get("code"));
        }

        verify(storageService).save(eq(FILE_PATH), any(InputStream.class));
    }
}
