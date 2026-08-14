package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 锁定项目概览页档案端点的鉴权口径：
 * 读走 hasReadPermission 且不拒 CLIENT（档案就是给客户看的那一页）；
 * 写走 hasWritePermission 且显式再判一次 !isClient。
 *
 * 参数序地雷：三个判权方法都是 (projectId, userId)，写反能编译通过、运行时静默 false。
 *
 * 关于 @InjectMocks：ProjectOverviewController 是四个后端组共用的文件，构造器参数会
 * 随兄弟组的落地逐个变长（stats / conversations 各加一个 service）。Mockito 选最大构造器、
 * 按类型匹配，匹配不到的参数传 null——本类两个端点从不触碰其他依赖，null 无害。
 * **不要在这里 @Mock 兄弟组的 service**：那会让本任务的测试在只执行本组时编译失败。
 * 既有 DdControllerAuthTest:22-30 也是这个写法。
 */
@ExtendWith(MockitoExtension.class)
class ProjectOverviewProfileAuthTest {

    @Mock
    private ProjectMemberService projectMemberService;
    @Mock
    private ProjectProfileService projectProfileService;

    @InjectMocks
    private ProjectOverviewController controller;

    /**
     * standaloneSetup 不加载 Spring 上下文，只把控制器与全局异常处理器串起来，
     * 用来验证「抛 IllegalArgumentException → HTTP 200 + code:1」这条全站口径。
     */
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * MockMvc 的 MockHttpServletResponse 默认字符集不是 UTF-8，直接
     * getContentAsString() 会把中文读成乱码，所以按字节自己解一次。
     */
    private static String utf8Body(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void 未登录时读档案被拒() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(null);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.getProfile(42L, "sess"));
            assertEquals("未登录", e.getMessage());
        }
    }

    @Test
    void 非项目成员读档案被拒() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(false);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.getProfile(42L, "sess"));
            assertEquals("无权访问该项目", e.getMessage());
        }
    }

    @Test
    void 成员读档案拿到信封与五条字段() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(true);
            when(projectProfileService.getProfile(42L)).thenReturn(List.of(
                    Map.of("fieldKey", "client"), Map.of("fieldKey", "matterType"),
                    Map.of("fieldKey", "openedAt"), Map.of("fieldKey", "nextStep"),
                    Map.of("fieldKey", "counterparty")));

            ResponseEntity<Map<String, Object>> res = controller.getProfile(42L, "sess");
            assertEquals(200, res.getStatusCode().value());
            Map<String, Object> body = res.getBody();
            assertNotNull(body);
            assertEquals(0, body.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            assertEquals(5, ((List<?>) data.get("fields")).size());
        }
    }

    @Test
    void CLIENT可以读档案() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(projectMemberService.hasReadPermission(42L, 9L)).thenReturn(true);
            when(projectProfileService.getProfile(42L)).thenReturn(List.of());

            assertNotNull(controller.getProfile(42L, "sess"));
            // 读端点不许调 isClient——档案就是给客户看的那一页
            verify(projectMemberService, never()).isClient(anyLong(), anyLong());
        }
    }

    @Test
    void 只读成员写档案被拒() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(42L, 7L)).thenReturn(false);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.saveProfileField(42L, "client", Map.of("value", "某某公司"), "sess"));
            assertEquals("无权修改该项目", e.getMessage());
            verify(projectProfileService, never()).saveUserField(anyLong(), anyString(), anyString());
        }
    }

    @Test
    void CLIENT即使有写权限也不能写档案() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(projectMemberService.hasWritePermission(42L, 9L)).thenReturn(true);
            when(projectMemberService.isClient(42L, 9L)).thenReturn(true);
            assertThrows(IllegalArgumentException.class,
                    () -> controller.saveProfileField(42L, "client", Map.of("value", "某某公司"), "sess"));
        }
    }

    @Test
    void 有写权限的成员写档案并拿回同形状单条() {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("fieldKey", "client");
        saved.put("label", "客户");
        saved.put("fieldValue", "北京某某科技有限公司");
        saved.put("source", "user");
        saved.put("confidence", null);
        saved.put("evidence", null);
        saved.put("updatedAt", "2026-08-08T10:11:12");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(42L, 7L)).thenReturn(true);
            when(projectMemberService.isClient(42L, 7L)).thenReturn(false);
            when(projectProfileService.saveUserField(42L, "client", "北京某某科技有限公司")).thenReturn(saved);

            ResponseEntity<Map<String, Object>> res = controller.saveProfileField(
                    42L, "client", Map.of("value", "北京某某科技有限公司"), "sess");
            Map<String, Object> body = res.getBody();
            assertNotNull(body);
            assertEquals(0, body.get("code"));
            assertSame(saved, body.get("data"));
        }
    }

    @Test
    void 请求体缺失时按清空处理() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(42L, 7L)).thenReturn(true);
            when(projectMemberService.isClient(42L, 7L)).thenReturn(false);
            when(projectProfileService.saveUserField(42L, "nextStep", null))
                    .thenReturn(new LinkedHashMap<>());

            assertNotNull(controller.saveProfileField(42L, "nextStep", null, "sess"));
            verify(projectProfileService).saveUserField(42L, "nextStep", null);
        }
    }

    @Test
    void 未登录读档案在HTTP层是200加code4010不是401() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(null);

            MvcResult result = mvc().perform(get("/api/projects/42/profile")
                            .header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(4010))
                    .andReturn();
            assertTrue(utf8Body(result).contains("未登录"), utf8Body(result));
        }
    }

    @Test
    void CLIENT写档案在HTTP层是200加code1不是403() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(projectMemberService.hasWritePermission(42L, 9L)).thenReturn(true);
            when(projectMemberService.isClient(42L, 9L)).thenReturn(true);

            MvcResult result = mvc().perform(put("/api/projects/42/profile/client")
                            .header("X-Session-Id", "sess")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"某某公司\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andReturn();
            assertTrue(utf8Body(result).contains("无权修改该项目"), utf8Body(result));
        }
    }
}
