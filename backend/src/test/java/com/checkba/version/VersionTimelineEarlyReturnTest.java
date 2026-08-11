package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 未开启版本记录不是错误：新建项目十有八九没开，概览页的动态块第一天就会撞上。
 * /timeline 必须早退返回空 versions，而不是掉进 VersionException 的通用错误信封
 * （「版本记录操作失败，请重试」），否则概览页会把「还没有版本记录」显示成「读取失败」。
 */
@ExtendWith(MockitoExtension.class)
class VersionTimelineEarlyReturnTest {

    @Mock private ProjectRepoService repoService;
    @Mock private WorkSessionService sessionService;
    @Mock private ProjectMemberService projectMemberService;
    @Mock private UserService userService;
    @Mock private ProjectFileService projectFileService;
    @Mock private ProjectTreeManifestService manifestService;
    @Mock private com.checkba.service.telemetry.TelemetryService telemetryService;

    @InjectMocks private VersionController controller;

    private void asMember(MockedStatic<AuthController> auth) {
        auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
        when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);
        when(projectMemberService.isClient(7L, 1L)).thenReturn(false);
    }

    @SuppressWarnings("unchecked")
    private void assertEmptyEnvelope(Map<String, Object> body) {
        assertNotNull(body);
        assertEquals(0, body.get("code"));
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertEquals(List.of(), data.get("versions"));
    }

    @Test
    void returnsEmptyVersionsWhenRepositoryNotInitialized() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            asMember(auth);
            when(repoService.isInitialized(7L)).thenReturn(false);

            assertEmptyEnvelope(controller.timeline(7L, 5, null, "sess").getBody());
            // 早退的证据：一次都不许去开仓/读日志
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void fileScopedTimelineAlsoEarlyReturnsWithoutTouchingTheFileTree() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            asMember(auth);
            when(repoService.isInitialized(7L)).thenReturn(false);

            assertEmptyEnvelope(controller.timeline(7L, 5, 50L, "sess").getBody());
            verify(projectFileService, never()).getFile(anyLong());
            verify(repoService, never()).logForPath(anyLong(), anyString(), anyString(), anyInt());
        }
    }
}
