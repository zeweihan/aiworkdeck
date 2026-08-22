package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.service.DdExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/projects/{pid}/dd-exports/{kind} 的 HTTP 面：未登录 4010 信封、参数透传、Service 抛错走 code=1。
 * 分组/落盘/画像等实质逻辑在 DdExportServiceTest 里锁，这里只锁路由与鉴权整形。
 */
@ExtendWith(MockitoExtension.class)
class DdExportControllerTest {

    @Mock private DdExportService svc;
    @InjectMocks private DdExportController controller;

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 未登录是4010信封() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(isNull())).thenReturn(null);
            mvc().perform(get("/api/projects/1/dd-exports/docket").param("docFileId", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
            verify(svc, never()).export(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    void 参数透传给Service_format缺省时不强填() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.export(9L, 1L, 10L, "docket", null))
                    .thenReturn(new DdExportService.ExportResult(300L, "_交付件/底稿目录.docx", 5));
            mvc().perform(get("/api/projects/1/dd-exports/docket").param("docFileId", "10").header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fileId").value(300))
                    .andExpect(jsonPath("$.path").value("_交付件/底稿目录.docx"))
                    .andExpect(jsonPath("$.rows").value(5));
        }
    }

    @Test
    void kind与format按路径与查询参数原样传递() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.export(eq(9L), eq(2L), eq(20L), eq("gaps"), eq("xlsx")))
                    .thenReturn(new DdExportService.ExportResult(301L, "_交付件/缺口清单.xlsx", 0));
            mvc().perform(get("/api/projects/2/dd-exports/gaps")
                            .param("docFileId", "20").param("format", "xlsx").header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fileId").value(301));
        }
    }

    @Test
    void Service抛出的非法参数走code1信封() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.export(9L, 1L, 10L, "docket", null))
                    .thenThrow(new IllegalArgumentException("文件不属于该项目: 10"));
            mvc().perform(get("/api/projects/1/dd-exports/docket").param("docFileId", "10").header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.message").value("文件不属于该项目: 10"));
        }
    }
}
