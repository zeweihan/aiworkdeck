package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.service.evidence.webverify.WebVerifyImportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/projects/{pid}/web-verify/import 的 HTTP 面：未登录 4010 信封、multipart 与参数透传、
 * sites 两种写法、Service 抛错走 code=1。落盘与挂链在 WebVerifyImportServiceTest 里锁。
 */
@ExtendWith(MockitoExtension.class)
class WebVerifyControllerTest {

    @Mock private WebVerifyImportService svc;
    @InjectMocks private WebVerifyController controller;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockMultipartFile zip() {
        return new MockMultipartFile("file", "网核.zip", "application/zip", new byte[]{1, 2, 3});
    }

    private static WebVerifyImportService.ImportResult result() {
        return new WebVerifyImportService.ImportResult("某某科技有限公司", "manual", 100L, 1,
                List.of(new WebVerifyImportService.LandedItem(300L, "_网核/某某科技有限公司/裁判文书-2026-08-21.png",
                        "judgment_docs", "裁判文书", "2026-08-21T00:00:00", null, null, List.of("EVID_A"))),
                List.of());
    }

    @Test
    @DisplayName("未登录是 4010 信封，Service 一次都不调")
    void unauthenticated() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(isNull())).thenReturn(null);
            mvc().perform(multipart("/api/projects/1/web-verify/import").file(zip()).param("partyName", "某某科技有限公司"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
            verify(svc, never()).importArchive(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("参数透传给 Service，createdByKind 固定 human")
    void passesParams() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.importArchive(eq(9L), eq(1L), eq("某某科技有限公司"), eq("91110000MA000000XA"),
                    any(), eq(100L), any(), eq("human"))).thenReturn(result());

            mvc().perform(multipart("/api/projects/1/web-verify/import").file(zip())
                            .param("partyName", "某某科技有限公司")
                            .param("unifiedSocialCreditCode", "91110000MA000000XA")
                            .param("docFileId", "100")
                            .header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.landed").value(1))
                    .andExpect(jsonPath("$.items[0].path").value("_网核/某某科技有限公司/裁判文书-2026-08-21.png"))
                    .andExpect(jsonPath("$.items[0].linkedKeys[0]").value("EVID_A"));
        }
    }

    @Test
    @DisplayName("sites 认逗号分隔与重复参数两种写法")
    void splitsSites() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.importArchive(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(result());

            mvc().perform(multipart("/api/projects/1/web-verify/import").file(zip())
                            .param("partyName", "某某科技有限公司")
                            .param("sites", "judgment_docs,dishonest_executee")
                            .header("X-Session-Id", "sess"))
                    .andExpect(status().isOk());

            ArgumentCaptor<List<String>> cap = ArgumentCaptor.forClass(List.class);
            verify(svc).importArchive(any(), any(), any(), any(), cap.capture(), any(), any(), any());
            assertEquals(List.of("judgment_docs", "dishonest_executee"), cap.getValue());
        }
    }

    @Test
    @DisplayName("Service 抛出的非法参数走 code=1 信封，原文回给前端")
    void serviceErrorEnvelope() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.importArchive(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("网核压缩包含非法路径（疑似路径穿越）: ../x"));

            mvc().perform(multipart("/api/projects/1/web-verify/import").file(zip())
                            .param("partyName", "某某科技有限公司").header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.message").value("网核压缩包含非法路径（疑似路径穿越）: ../x"));
        }
    }

    @Test
    @DisplayName("sites 两种写法的纯函数：逗号分隔拆开、空段丢掉")
    void splitSitesPureFunction() {
        assertEquals(List.of(), WebVerifyController.splitSites(null));
        assertEquals(List.of("a", "b", "c"), WebVerifyController.splitSites(List.of("a, b", "c")));
        assertEquals(List.of("a"), WebVerifyController.splitSites(List.of("a", " ", ",")));
    }
}
