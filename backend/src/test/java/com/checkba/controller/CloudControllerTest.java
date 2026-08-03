package com.checkba.controller;

import com.checkba.model.entity.CloudConnection;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import com.checkba.version.CloudSyncService;
import com.checkba.version.VersionException;
import com.checkba.version.WorkSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CloudController 是纯转发层——测试重点：鉴权（登录/成员/拒 CLIENT）、参数转发对不对、
 * 响应绝不带 deviceToken（防泄漏）、resolutions 坏值报 userFacing。
 * 形制照 VersionControllerAuthTest：MockitoExtension + MockedStatic<AuthController>。
 */
@ExtendWith(MockitoExtension.class)
class CloudControllerTest {

    @Mock
    private CloudSyncService cloudSyncService;
    @Mock
    private ProjectMemberService projectMemberService;
    @Mock
    private UserService userService;

    @InjectMocks
    private CloudController controller;

    private static final long PROJECT_ID = 7L;
    private static final long USER_ID = 1L;

    // ---- 登录态：连接级端点 --------------------------------------------------

    @Test
    void anonymousCannotListConnections() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> controller.connections(null));
            assertEquals("未登录", ex.getMessage());
            verify(cloudSyncService, never()).listConnections(anyLong());
        }
    }

    @Test
    void anonymousCannotConnect() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.connect(Map.of("serverUrl", "https://x"), null));
            verify(cloudSyncService, never()).connect(any(), any(), any(), any(), any());
        }
    }

    // ---- 鉴权：项目级端点 requireMemberNonClient 三连 ------------------------

    @Test
    void clientRoleCannotShare() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(true);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.share(PROJECT_ID, Map.of("connectionId", 3), "sess"));
            verify(cloudSyncService, never()).shareToCloud(anyLong(), anyLong(), any());
        }
    }

    @Test
    void nonMemberCannotSeeStatus() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.status(PROJECT_ID, "sess"));
            verify(cloudSyncService, never()).cloudStatus(anyLong());
        }
    }

    @Test
    void anonymousCannotUpload() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.upload(PROJECT_ID, null));
            verify(cloudSyncService, never()).uploadToCloud(anyLong(), anyBoolean());
        }
    }

    /**
     * v2 终审 I4：READ_ONLY 成员（有读权、无写权、非 CLIENT）对项目级写端点一律拒绝，
     * 服务方法一次都不被调到；读端点（status/check/members GET）照常放行。
     * 口径同 VersionControllerAuthTest.readOnlyMemberCannotWrite。
     */
    @Test
    void readOnlyMemberCannotWriteButCanRead() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
            when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(false);

            List<org.junit.jupiter.api.function.Executable> writes = List.of(
                    () -> controller.share(PROJECT_ID, Map.of("connectionId", 3), "sess"),
                    () -> controller.upload(PROJECT_ID, "sess"),
                    () -> controller.update(PROJECT_ID, "sess"),
                    () -> controller.resolve(PROJECT_ID,
                            Map.of("resolutions", Map.of("a.txt", "MAIN")), "sess"),
                    () -> controller.abort(PROJECT_ID, "sess"),
                    () -> controller.addMember(PROJECT_ID, Map.of("username", "赵六"), "sess"));
            for (var w : writes) {
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, w);
                assertEquals("无权修改该项目", ex.getMessage());
            }
            verify(cloudSyncService, never()).shareToCloud(anyLong(), anyLong(), any());
            verify(cloudSyncService, never()).uploadToCloud(anyLong(), anyBoolean());
            verify(cloudSyncService, never()).updateFromCloud(anyLong(), any(), any());
            verify(cloudSyncService, never()).resolveCloudMerge(anyLong(), any(), any(), any());
            verify(cloudSyncService, never()).abortCloudMerge(anyLong());
            verify(cloudSyncService, never()).proxyMembers(anyLong(), any(), any());

            when(cloudSyncService.cloudStatus(PROJECT_ID)).thenReturn(Map.of("linked", false));
            when(cloudSyncService.proxyMembers(PROJECT_ID)).thenReturn(List.of());
            assertEquals(0, controller.status(PROJECT_ID, "sess").getBody().get("code"));
            assertEquals(0, controller.members(PROJECT_ID, "sess").getBody().get("code"));
        }
    }

    // ---- connect 透传 ---------------------------------------------------------

    @Test
    void connectForwardsParamsAndReturnsFields() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            CloudConnection conn = new CloudConnection();
            conn.setId(5L);
            conn.setServerUrl("https://cloud.example.com");
            conn.setUsername("zhangsan");
            conn.setDisplayName("张三");
            conn.setDeviceToken("super-secret-token");
            conn.setCreatedAt(LocalDateTime.now());
            when(cloudSyncService.connect("https://cloud.example.com", "zhangsan", "pw123", "我的电脑", USER_ID))
                    .thenReturn(conn);

            var resp = controller.connect(Map.of(
                    "serverUrl", "https://cloud.example.com",
                    "username", "zhangsan",
                    "password", "pw123",
                    "deviceName", "我的电脑"), "sess");

            assertEquals(0, resp.getBody().get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            assertEquals(5L, data.get("connectionId"));
            assertEquals("zhangsan", data.get("username"));
            assertEquals("张三", data.get("displayName"));
            assertEquals("https://cloud.example.com", data.get("serverUrl"));
        }
    }

    // ---- connections 列表绝不带 deviceToken（防泄漏，序列化断言） --------------

    @Test
    void connectionsListNeverLeaksDeviceToken() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            CloudConnection conn = new CloudConnection();
            conn.setId(5L);
            conn.setServerUrl("https://cloud.example.com");
            conn.setUsername("zhangsan");
            conn.setDisplayName("张三");
            conn.setDeviceToken("super-secret-token-should-never-leak");
            conn.setCreatedAt(LocalDateTime.now());
            when(cloudSyncService.listConnections(USER_ID)).thenReturn(List.of(conn));

            var resp = controller.connections("sess");

            String json = new ObjectMapper().writeValueAsString(resp.getBody());
            assertFalse(json.contains("deviceToken"), "响应不得带 deviceToken 字段: " + json);
            assertFalse(json.contains("super-secret-token-should-never-leak"),
                    "响应不得带令牌值: " + json);
            assertTrue(json.contains("zhangsan"));
        }
    }

    // ---- upload/update 转发参数正确 --------------------------------------------

    @Test
    void uploadForwardsProjectIdAndBackgroundFalse() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
            when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(cloudSyncService.uploadToCloud(PROJECT_ID, false))
                    .thenReturn(new CloudSyncService.UploadResult(CloudSyncService.UploadStatus.UPLOADED, null));

            var resp = controller.upload(PROJECT_ID, "sess");

            verify(cloudSyncService).uploadToCloud(PROJECT_ID, false);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            assertEquals("UPLOADED", data.get("status"));
        }
    }

    @Test
    void updateForwardsProjectIdUserIdAndUserName() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
            when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);
            var user = new com.checkba.model.entity.User();
            user.setUsername("李四");
            when(userService.getUserById(USER_ID)).thenReturn(user);
            when(cloudSyncService.updateFromCloud(PROJECT_ID, USER_ID, "李四"))
                    .thenReturn(new CloudSyncService.UpdateResult(
                            CloudSyncService.UpdateStatus.UPDATED, List.of(11L, 12L), null));

            var resp = controller.update(PROJECT_ID, "sess");

            verify(cloudSyncService).updateFromCloud(PROJECT_ID, USER_ID, "李四");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            assertEquals("UPDATED", data.get("status"));
            assertEquals(List.of(11L, 12L), data.get("affectedFileIds"));
        }
    }

    // ---- resolutions 坏值 userFacing -------------------------------------------

    @Test
    void resolveWithInvalidResolutionValueThrowsUserFacing() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
            when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);

            VersionException ex = assertThrows(VersionException.class, () -> controller.resolve(
                    PROJECT_ID, Map.of("resolutions", Map.of("a.txt", "NOT_A_REAL_CHOICE")), "sess"));

            assertTrue(ex.isUserFacing());
            assertEquals("无效的选择", ex.getMessage());
            verify(cloudSyncService, never()).resolveCloudMerge(anyLong(), any(), any(), any());
        }
    }

    @Test
    void resolveParsesValidResolutionsAndForwards() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
            when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);
            var user = new com.checkba.model.entity.User();
            user.setUsername("王五");
            when(userService.getUserById(USER_ID)).thenReturn(user);
            when(cloudSyncService.resolveCloudMerge(eq(PROJECT_ID),
                    eq(Map.of("a.txt", WorkSessionService.Resolution.DRAFT)), eq(USER_ID), eq("王五")))
                    .thenReturn(new CloudSyncService.UpdateResult(
                            CloudSyncService.UpdateStatus.UPDATED, List.of(), null));

            controller.resolve(PROJECT_ID, Map.of("resolutions", Map.of("a.txt", "DRAFT")), "sess");

            verify(cloudSyncService).resolveCloudMerge(PROJECT_ID,
                    Map.of("a.txt", WorkSessionService.Resolution.DRAFT), USER_ID, "王五");
        }
    }

    // ---- 成员代理两端点 ---------------------------------------------------------

    @Test
    void membersGetForwardsProjectId() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
            when(cloudSyncService.proxyMembers(PROJECT_ID)).thenReturn(List.of(Map.of("username", "赵六")));

            var resp = controller.members(PROJECT_ID, "sess");

            verify(cloudSyncService).proxyMembers(PROJECT_ID);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            assertEquals(List.of(Map.of("username", "赵六")), data.get("members"));
        }
    }

    @Test
    void membersPostDefaultsRoleToParticipant() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
            when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);

            controller.addMember(PROJECT_ID, Map.of("username", "赵六"), "sess");

            verify(cloudSyncService).proxyMembers(PROJECT_ID, "赵六", "PARTICIPANT");
        }
    }

    @Test
    void membersPostForwardsExplicitRole() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
            when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);

            controller.addMember(PROJECT_ID, Map.of("username", "赵六", "role", "ADMIN"), "sess");

            verify(cloudSyncService).proxyMembers(PROJECT_ID, "赵六", "ADMIN");
        }
    }

    // ---- abort 携带 notice message ---------------------------------------------

    @Test
    void abortReturnsNoticeMessage() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
            when(projectMemberService.hasWritePermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(cloudSyncService.abortCloudMerge(PROJECT_ID)).thenReturn("这次更新没有完成，你的内容分毫未动");

            var resp = controller.abort(PROJECT_ID, "sess");

            assertEquals("这次更新没有完成，你的内容分毫未动", resp.getBody().get("message"));
        }
    }

    // ---- accept 转发 connectionId/remoteProjectId/userId -----------------------

    @Test
    void acceptForwardsIdsAndUserId() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(cloudSyncService.cloneFromCloud(3L, 9L, USER_ID)).thenReturn(Map.of("localProjectId", 42L));

            var resp = controller.accept(Map.of("connectionId", 3, "remoteProjectId", 9), "sess");

            verify(cloudSyncService).cloneFromCloud(3L, 9L, USER_ID);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            assertEquals(42L, data.get("localProjectId"));
        }
    }

    // ---- 异常处理器：技术性消息不回显，业务性消息原样回显 ------------------------

    @Test
    void technicalVersionExceptionIsMaskedWithGenericMessage() {
        var e = new VersionException("整合失败: work/1690000000", new RuntimeException("boom"));

        var response = controller.onVersionError(e);

        assertEquals(1, response.getBody().get("code"));
        assertEquals("云端协作操作失败，请重试", response.getBody().get("message"));
    }

    @Test
    void userFacingVersionExceptionIsShownAsIs() {
        var e = VersionException.userFacing("请先共享到云端");

        var response = controller.onVersionError(e);

        assertEquals(1, response.getBody().get("code"));
        assertEquals("请先共享到云端", response.getBody().get("message"));
    }
}
