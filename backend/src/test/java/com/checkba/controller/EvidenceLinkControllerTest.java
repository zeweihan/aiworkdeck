package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.service.evidence.EvidenceLinkViews.AnchorReport;
import com.checkba.service.evidence.EvidenceLinkViews.AnchorReportResult;
import com.checkba.service.evidence.EvidenceLinkViews.FileBrief;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetInput;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/projects/{pid}/evidence-links 的 HTTP 面：未登录 4010 信封、非成员（Service 抛 IAE）走 code=1、
 * 建链/反查/回写的参数整形与路由（/ref-counts、/anchors/report 不被 /{linkKey} 吃掉）。
 * Service 为 mock，鉴权语义在 EvidenceLinkServiceTest 里锁。
 */
@ExtendWith(MockitoExtension.class)
class EvidenceLinkControllerTest {

    @Mock private EvidenceLinkService svc;
    @Mock private ProjectMemberService projectMemberService;
    @InjectMocks private EvidenceLinkController controller;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static LinkView link(String key) {
        return new LinkView(100L, key, 10L, "根据《营业执照》", "h", "一/（一）", "主体资格", "active", "human",
                LocalDateTime.of(2026, 8, 21, 10, 0), LocalDateTime.of(2026, 8, 21, 10, 0),
                List.of(new TargetView(200L, 11L, new FileBrief(11L, "执照.pdf", "pdf", 3L, false),
                        null, "supports", "written_review", null, null)));
    }

    @Test
    void 未登录是4010信封() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(isNull())).thenReturn(null);
            mvc().perform(get("/api/projects/1/evidence-links").param("docFileId", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
            verify(svc, never()).listByDoc(any(), any(), any(), any(), any());
        }
    }

    @Test
    void 非成员被Service拒绝走code1() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.listByDoc(9L, 1L, 10L, null, null)).thenThrow(new IllegalArgumentException("无权限访问该项目"));
            mvc().perform(get("/api/projects/1/evidence-links").param("docFileId", "10").header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andExpect(jsonPath("$.message").value("无权限访问该项目"));
        }
    }

    @Test
    void POST建链透传参数并回LinkView() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.create(eq(9L), eq(1L), eq(10L), isNull(), eq("根据《营业执照》"), eq("一/（一）"), eq("主体资格"),
                    eq("human"), anyList())).thenReturn(link("EVID_X"));

            String body = om.writeValueAsString(Map.of(
                    "docFileId", 10, "anchorText", "根据《营业执照》", "sectionPath", "一/（一）", "sectionTitle", "主体资格",
                    "targets", List.of(Map.of("fileId", 11, "locatorJson", "{\"type\":\"pdf\",\"page\":1}", "method", "written_review"))));
            mvc().perform(post("/api/projects/1/evidence-links").header("X-Session-Id", "sess")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.linkKey").value("EVID_X"))
                    .andExpect(jsonPath("$.status").value("active"))
                    .andExpect(jsonPath("$.targets[0].file.name").value("执照.pdf"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TargetInput>> cap = ArgumentCaptor.forClass(List.class);
            verify(svc).create(eq(9L), eq(1L), eq(10L), isNull(), anyString(), anyString(), anyString(), eq("human"), cap.capture());
            assertEquals(11L, cap.getValue().get(0).fileId());
            assertEquals("{\"type\":\"pdf\",\"page\":1}", cap.getValue().get(0).locatorJson());
        }
    }

    @Test
    void GET按fileId反查优先于docFileId() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.listByFile(9L, 1L, 11L)).thenReturn(List.of(link("EVID_A")));
            mvc().perform(get("/api/projects/1/evidence-links").param("fileId", "11").param("docFileId", "10")
                            .header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].linkKey").value("EVID_A"));
            verify(svc, never()).listByDoc(any(), any(), any(), any(), any());
        }
    }

    @Test
    void GET带partyTagId走主体视图_缺docFileId报错() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.listByParty(9L, 1L, 10L, 5L)).thenReturn(List.of());
            mvc().perform(get("/api/projects/1/evidence-links").param("docFileId", "10").param("partyTagId", "5")
                            .header("X-Session-Id", "sess"))
                    .andExpect(status().isOk());
            verify(svc).listByParty(9L, 1L, 10L, 5L);

            mvc().perform(get("/api/projects/1/evidence-links").header("X-Session-Id", "sess"))
                    .andExpect(jsonPath("$.code").value(1));
        }
    }

    @Test
    void refCounts路由不被linkKey吃掉_且校读权限() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(projectMemberService.hasReadPermission(1L, 9L)).thenReturn(true);
            when(svc.refCounts(eq(1L), anyList())).thenReturn(Map.of(11L, 3L));
            mvc().perform(get("/api/projects/1/evidence-links/ref-counts").param("fileIds", "11", "12")
                            .header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.11").value(3));
            verify(svc, never()).getByKey(any(), any(), any());

            when(projectMemberService.hasReadPermission(1L, 9L)).thenReturn(false);
            mvc().perform(get("/api/projects/1/evidence-links/ref-counts").param("fileIds", "11")
                            .header("X-Session-Id", "sess"))
                    .andExpect(jsonPath("$.code").value(1));
        }
    }

    @Test
    void GET单条与DELETE走linkKey() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.getByKey(9L, 1L, "EVID_A")).thenReturn(link("EVID_A"));
            mvc().perform(get("/api/projects/1/evidence-links/EVID_A").header("X-Session-Id", "sess"))
                    .andExpect(jsonPath("$.linkKey").value("EVID_A"));
            mvc().perform(delete("/api/projects/1/evidence-links/EVID_A").header("X-Session-Id", "sess"))
                    .andExpect(jsonPath("$.success").value(true));
            verify(svc).delete(9L, 1L, "EVID_A");
        }
    }

    @Test
    void anchorsReport回写返回changed() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.reportAnchors(eq(9L), eq(1L), eq(10L), anyList()))
                    .thenReturn(new AnchorReportResult(List.of("EVID_A"), 1));
            String body = om.writeValueAsString(Map.of("docFileId", 10, "reports", List.of(
                    Map.of("linkKey", "EVID_A", "exists", true, "text", "改了"),
                    Map.of("linkKey", "EVID_B", "exists", false),
                    Map.of("linkKey", "EVID_C", "text", "漏了 exists"))));
            mvc().perform(post("/api/projects/1/evidence-links/anchors/report").header("X-Session-Id", "sess")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changed[0]").value("EVID_A"))
                    .andExpect(jsonPath("$.ignored").value(1));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<AnchorReport>> cap = ArgumentCaptor.forClass(List.class);
            verify(svc).reportAnchors(eq(9L), eq(1L), eq(10L), cap.capture());
            assertEquals(3, cap.getValue().size());
            assertEquals(Boolean.FALSE, cap.getValue().get(1).exists());
            assertNull(cap.getValue().get(2).exists(), "漏字段反序列化成 null，不是 false");
            verify(svc, never()).create(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void keep与rebind与targets三条写路径() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(svc.keepAnchor(9L, 1L, "EVID_A", "现文")).thenReturn(link("EVID_A"));
            mvc().perform(post("/api/projects/1/evidence-links/EVID_A/keep").header("X-Session-Id", "sess")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"现文\"}"))
                    .andExpect(jsonPath("$.linkKey").value("EVID_A"));

            when(svc.rebind(9L, 1L, "EVID_A", "EVID_B", "新", "二", "财务")).thenReturn(link("EVID_B"));
            mvc().perform(post("/api/projects/1/evidence-links/EVID_A/rebind").header("X-Session-Id", "sess")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"newLinkKey\":\"EVID_B\",\"anchorText\":\"新\",\"sectionPath\":\"二\",\"sectionTitle\":\"财务\"}"))
                    .andExpect(jsonPath("$.linkKey").value("EVID_B"));

            when(svc.addTargets(eq(9L), eq(1L), eq("EVID_A"), anyList(), eq("plugin"))).thenReturn(link("EVID_A"));
            mvc().perform(post("/api/projects/1/evidence-links/EVID_A/targets").param("createdByKind", "plugin")
                            .header("X-Session-Id", "sess")
                            .contentType(MediaType.APPLICATION_JSON).content("[{\"fileId\":12,\"relation\":\"partial\"}]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.linkKey").value("EVID_A"));

            when(svc.updateTarget(eq(9L), eq(1L), eq(200L), any())).thenReturn(link("EVID_A").targets().get(0));
            mvc().perform(patch("/api/projects/1/evidence-links/targets/200").header("X-Session-Id", "sess")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"interview\"}"))
                    .andExpect(jsonPath("$.id").value(200));

            mvc().perform(delete("/api/projects/1/evidence-links/targets/200").header("X-Session-Id", "sess"))
                    .andExpect(jsonPath("$.success").value(true));
            verify(svc).removeTarget(9L, 1L, 200L);
        }
    }
}
