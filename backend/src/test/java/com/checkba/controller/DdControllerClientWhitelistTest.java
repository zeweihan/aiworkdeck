// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.service.DdService;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 尽调清单的客户白名单（dev-board#1050）：案件库托管客户门户之后，CLIENT 是公网上凭访问码
 * 进来的人。只许读清单、传文件、留言（外加回看自己传的文件）；其余写端点逐个 403，
 * 并且在碰到业务层之前就被挡住。
 */
class DdControllerClientWhitelistTest {

    private static final long PROJECT = 42L;
    private static final long REQUEST = 99L;
    private static final long ITEM = 7L;
    private static final long CLIENT = 3L;

    private DdService ddService;
    private ProjectMemberService pms;
    private DdController controller;
    private MockedStatic<AuthController> auth;

    @BeforeEach
    void setUp() {
        ddService = mock(DdService.class);
        pms = mock(ProjectMemberService.class);
        controller = new DdController(ddService, pms);
        when(ddService.getProjectIdByRequestId(REQUEST)).thenReturn(PROJECT);
        when(ddService.getProjectIdByItemId(ITEM)).thenReturn(PROJECT);
        when(pms.hasReadPermission(PROJECT, CLIENT)).thenReturn(true);
        when(pms.isClient(PROJECT, CLIENT)).thenReturn(true);
        auth = mockStatic(AuthController.class);
        auth.when(() -> AuthController.getUserIdFromSession("c")).thenReturn(CLIENT);
    }

    @AfterEach
    void tearDown() {
        auth.close();
    }

    private void assertForbidden(String name, Executable call) {
        assertThrows(DdController.ClientForbiddenException.class, call, name + " 应对客户 403");
    }

    @Test
    @DisplayName("十个写端点对客户逐个 403，且业务层一个都没被调到")
    void everyWriteEndpointRejectsClient() {
        DdController.CreateRequestDto create = new DdController.CreateRequestDto();
        create.setName("x");
        assertForbidden("createRequest", () -> controller.createRequest(PROJECT, create, "c"));
        assertForbidden("addItems", () -> controller.addItems(REQUEST, create, "c"));
        DdController.UpdateRequestDto rename = new DdController.UpdateRequestDto();
        rename.setName("y");
        assertForbidden("updateRequest", () -> controller.updateRequest(REQUEST, rename, "c"));
        assertForbidden("addItem", () -> controller.addItem(REQUEST, new DdController.AddItemDto(), "c"));
        assertForbidden("moveItem", () -> controller.moveItem(ITEM, new DdController.MoveItemDto(), "c"));
        DdController.UpdateStatusDto status = new DdController.UpdateStatusDto();
        status.setStatus("APPROVED");
        assertForbidden("updateStatus", () -> controller.updateStatus(ITEM, status, "c"));
        assertForbidden("updateInfo", () -> controller.updateInfo(ITEM, new DdController.UpdateInfoDto(), "c"));
        assertForbidden("deleteItem", () -> controller.deleteItem(ITEM, "c"));
        assertForbidden("deleteRequest", () -> controller.deleteRequest(REQUEST, "c"));
        assertForbidden("copyRequest", () -> controller.copyRequest(REQUEST, "c"));

        verify(ddService, never()).createRequest(any(), any(), any(), any());
        verify(ddService, never()).addItems(any(), any());
        verify(ddService, never()).updateRequest(any(), any());
        verify(ddService, never()).addItem(any(), any());
        verify(ddService, never()).moveItem(any(), any());
        verify(ddService, never()).updateItemStatus(any(), any());
        verify(ddService, never()).updateItemInfo(any(), any(), any());
        verify(ddService, never()).deleteItem(anyLong(), any());
        verify(ddService, never()).deleteRequest(anyLong(), any());
        verify(ddService, never()).copyRequest(any(), any());
    }

    @Test
    @DisplayName("客户能读清单、传文件、留言、看留言")
    void clientWhitelistStillWorks() throws Exception {
        when(ddService.getRequests(PROJECT)).thenReturn(List.of());
        when(ddService.getItems(REQUEST)).thenReturn(List.of());
        assertDoesNotThrow(() -> controller.getRequests(PROJECT, "c"));
        assertDoesNotThrow(() -> controller.getRequestDetails(REQUEST, "c"));
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1});
        assertDoesNotThrow(() -> controller.uploadFile(ITEM, file, "c"));
        verify(ddService).uploadFile(ITEM, file, CLIENT);
        DdController.CommentDto comment = new DdController.CommentDto();
        comment.setContent("已上传");
        assertDoesNotThrow(() -> controller.addComment(ITEM, comment, "c"));
        assertDoesNotThrow(() -> controller.getComments(ITEM, "c"));
    }

    @Test
    @DisplayName("律师（非客户）的写端点照常")
    void staffStillWrites() {
        when(pms.isClient(PROJECT, CLIENT)).thenReturn(false);
        assertDoesNotThrow(() -> controller.deleteRequest(REQUEST, "c"));
        verify(ddService).deleteRequest(REQUEST, CLIENT);
    }

    @Test
    @DisplayName("客户越权回真 403，带 code/message")
    void forbiddenMapsToHttp403() {
        var resp = controller.onClientForbidden(new DdController.ClientForbiddenException());
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertEquals(403, body.get("code"));
    }
}
