// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.config;

import com.checkba.controller.AuthController;
import com.checkba.service.DdCloudMigrationService;
import com.checkba.service.ProjectMemberService;
import com.checkba.version.CloudSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 尽调清单代理的路由判据（dev-board#1050）。整条转发链的真 HTTP 往返在
 * ClientPortalRoundTripTest；这里钉住「什么时候不该转」——转错了就是律师的本机清单
 * 被发去了案件库，或者放进案件库的案卷还在读写本机库。
 */
class DdCloudProxyFilterTest {

    private final CloudSyncService cloud = mock(CloudSyncService.class);
    private final DdCloudMigrationService migration = mock(DdCloudMigrationService.class);
    private final ProjectMemberService pms = mock(ProjectMemberService.class);

    private MockFilterChain run(boolean localMode, String method, String path, String query,
                                MockHttpServletResponse resp) throws Exception {
        DdCloudProxyFilter f = new DdCloudProxyFilter(cloud, migration, pms, localMode);
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setQueryString(query);
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        return chain;
    }

    @Test
    @DisplayName("server 模式（案件库自己）一律不转")
    void serverModeNeverProxies() throws Exception {
        when(cloud.hasRemoteBinding(anyLong())).thenReturn(true);
        assertNotNull(run(false, "GET", "/api/dd/projects/7", null, new MockHttpServletResponse()).getRequest());
        verify(cloud, never()).proxyDd(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("定不出项目 id / 项目没放进案件库：落本机 DdController")
    void unlinkedOrUnknownProjectFallsThrough() throws Exception {
        when(cloud.hasRemoteBinding(7L)).thenReturn(false);
        assertNotNull(run(true, "GET", "/api/dd/projects/7", null, new MockHttpServletResponse()).getRequest());
        assertNotNull(run(true, "GET", "/api/dd/requests/5", null, new MockHttpServletResponse()).getRequest());
        assertNotNull(run(true, "GET", "/api/dd/requests/5", "projectId=7", new MockHttpServletResponse()).getRequest());
        assertNotNull(run(true, "GET", "/api/other", null, new MockHttpServletResponse()).getRequest());
        verify(cloud, never()).proxyDd(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("本机的客户角色不转发，回无权")
    void clientRoleRejected() throws Exception {
        when(cloud.hasRemoteBinding(7L)).thenReturn(true);
        when(pms.hasReadPermission(7L, 1L)).thenReturn(true);
        when(pms.isClient(7L, 1L)).thenReturn(true);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(1L);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            assertNull(run(true, "GET", "/api/dd/requests/5", "projectId=7", resp).getRequest());
            assertTrue(resp.getContentAsString(StandardCharsets.UTF_8).contains("\"code\":1"));
        }
        verify(cloud, never()).proxyDd(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("转发时剥掉本机的 projectId 与 token，其余查询串原样；列表读先触发迁移")
    void forwardsWithCleanQueryAndMigratesOnList() throws Exception {
        when(cloud.hasRemoteBinding(7L)).thenReturn(true);
        when(pms.hasReadPermission(7L, 1L)).thenReturn(true);
        when(cloud.proxyDd(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(new CloudSyncService.RawResponse(200, "application/json", null, "[]".getBytes()));
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(1L);
            run(true, "GET", "/api/dd/items/5/file", "projectId=7&token=abc&x=1", new MockHttpServletResponse());
            verify(cloud).proxyDd(7L, "GET", "/items/5/file", "x=1", new byte[0], null);
            verify(migration, never()).migratePending(anyLong());
            MockHttpServletResponse resp = new MockHttpServletResponse();
            run(true, "GET", "/api/dd/projects/7", null, resp);
            verify(migration).migratePending(7L);
            assertEquals("[]", resp.getContentAsString());
            assertEquals(SourceCodeNoticeFilter.SOURCE_URL, resp.getHeader(SourceCodeNoticeFilter.HEADER));
        }
    }
}
