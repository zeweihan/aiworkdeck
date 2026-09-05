package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.service.insight.DocInsightService;
import com.checkba.service.insight.DocInsightViews.EntityView;
import com.checkba.service.insight.DocInsightViews.FindingView;
import com.checkba.service.insight.DocInsightViews.InsightView;
import com.checkba.service.insight.DocInsightViews.MentionView;
import com.checkba.service.insight.DocInsightViews.RunView;
import com.checkba.service.insight.DocInsightViews.StartResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/projects/{pid}/insight 的 HTTP 面：未登录 4010 信封、Service 拒绝走 code=1、
 * 参数透传与响应形状（前端照这个形状接）。鉴权语义本身在 DocInsightServiceTest 里锁。
 */
@ExtendWith(MockitoExtension.class)
class DocInsightControllerTest {

    @Mock
    private DocInsightService svc;
    @InjectMocks
    private DocInsightController controller;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static InsightView view() throws Exception {
        ObjectMapper om = new ObjectMapper();
        return new InsightView(
                new RunView(7L, 10L, "DONE", "完成：实体 3 个，检索成功 1 个，发现 1 处", null,
                        "qwen/qwen3.7-flash", LocalDateTime.of(2026, 8, 27, 10, 0),
                        LocalDateTime.of(2026, 8, 27, 10, 2)),
                List.of(new EntityView(31L, "COMPANY", "京微资易科技有限公司", "京微资易科技",
                        "OK", "qichacha+mcp", null, null, true, LocalDateTime.of(2026, 8, 27, 10, 1),
                        List.of(new MentionView("由京微资易科技持有", null)), null)),
                List.of(new FindingView(41L, "COUNT_MISMATCH", "warn", "标的公司 的「房产」前后不一致：58项 / 39项",
                        om.readTree("""
                                {"subject":"标的","metric":"房产","unit":"项","claims":[
                                  {"quote":"房产共 58 项","value":58,"unit":"项","numberText":"58","fixable":true},
                                  {"quote":"附表 39 项","value":39,"unit":"项","numberText":"39","fixable":true}]}
                                """))));
    }

    @Test
    void 未登录是4010信封() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(isNull())).thenReturn(null);
            mvc().perform(get("/api/projects/1/insight").param("docFileId", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
            mvc().perform(post("/api/projects/1/insight/parse")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"docFileId\":10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
            verify(svc, never()).startParse(any(), any(), any());
            verify(svc, never()).latest(any(), any(), any());
        }
    }

    @Test
    void 无权限走code1信封() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.startParse(9L, 1L, 10L)).thenThrow(new IllegalArgumentException("无权限修改该项目"));
            mvc().perform(post("/api/projects/1/insight/parse").header("X-Session-Id", "sess")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"docFileId\":10}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.message").value("无权限修改该项目"));
        }
    }

    @Test
    void POST解析透传docFileId() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.startParse(9L, 1L, 10L)).thenReturn(new StartResult(7L, 10L, "RUNNING"));
            mvc().perform(post("/api/projects/1/insight/parse").header("X-Session-Id", "sess")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("docFileId", 10))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.runId").value(7))
                    .andExpect(jsonPath("$.status").value("RUNNING"));
            verify(svc).startParse(9L, 1L, 10L);
        }
    }

    @Test
    void GET回run实体与发现的完整形状() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.latest(9L, 1L, 10L)).thenReturn(view());
            mvc().perform(get("/api/projects/1/insight").param("docFileId", "10").header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.run.status").value("DONE"))
                    .andExpect(jsonPath("$.run.phase").value("完成：实体 3 个，检索成功 1 个，发现 1 处"))
                    .andExpect(jsonPath("$.entities[0].kind").value("COMPANY"))
                    .andExpect(jsonPath("$.entities[0].retrievalStatus").value("OK"))
                    .andExpect(jsonPath("$.entities[0].hasDetail").value(true))
                    .andExpect(jsonPath("$.entities[0].detail").doesNotExist())
                    .andExpect(jsonPath("$.entities[0].mentions[0].quote").value("由京微资易科技持有"))
                    // findings 不瘦身：前端的一键修改直接吃 detail.claims[].numberText
                    .andExpect(jsonPath("$.findings[0].kind").value("COUNT_MISMATCH"))
                    .andExpect(jsonPath("$.findings[0].severity").value("warn"))
                    .andExpect(jsonPath("$.findings[0].detail.claims[0].numberText").value("58"))
                    .andExpect(jsonPath("$.findings[0].detail.claims[0].fixable").value(true))
                    .andExpect(jsonPath("$.findings[0].detail.claims[1].quote").value("附表 39 项"));
        }
    }

    @Test
    void 实体明细与重新检索路由分开() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            EntityView detail = new EntityView(31L, "LAW", "《中华人民共和国公司法》第二十条", "中华人民共和国公司法#第二十条",
                    "UNAVAILABLE", "pkulaw-semantic", "法规检索本次不可用：401 checking remaining points",
                    null, false, LocalDateTime.of(2026, 8, 27, 10, 1), List.of(), null);
            when(svc.entityDetail(9L, 1L, 31L)).thenReturn(detail);
            when(svc.refreshEntity(9L, 1L, 31L)).thenReturn(detail);

            mvc().perform(get("/api/projects/1/insight/entities/31").header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.retrievalStatus").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.retrievalNote").value("法规检索本次不可用：401 checking remaining points"));
            mvc().perform(post("/api/projects/1/insight/entities/31/refresh").header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(31));

            verify(svc).entityDetail(9L, 1L, 31L);
            verify(svc).refreshEntity(9L, 1L, 31L);
        }
    }

    @Test
    void 跨项目的id由Service拒绝并落code1() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.entityDetail(9L, 2L, 31L)).thenThrow(new IllegalArgumentException("条目不存在"));
            mvc().perform(get("/api/projects/2/insight/entities/31").header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.message").value("条目不存在"));
        }
    }
}
