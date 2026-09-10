// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.User;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

/**
 * 版本时间线的署名（spec 2026-09-10 §4）：写进 Git 提交对象的作者名以前是 **username**，
 * 手机号注册的同事在时间线与文档修订里就是一串 {@code awd_upoxwcdtg}。
 * 改成展示名，展示名为空才回落用户名。已写入的历史 authorName 不回填。
 */
@ExtendWith(MockitoExtension.class)
class VersionAuthorNameTest {

    private static final long PROJECT = 7L;
    private static final long USER = 1L;

    @Mock private ProjectRepoService repoService;
    @Mock private WorkSessionService sessionService;
    @Mock private ProjectMemberService projectMemberService;
    @Mock private UserService userService;
    @Mock private ProjectFileService projectFileService;
    @Mock private com.checkba.service.telemetry.TelemetryService telemetryService;
    @Mock private VersionLifecycleService lifecycleService;

    @InjectMocks private VersionController controller;

    @BeforeEach
    void allowWrites() {
        when(projectMemberService.hasReadPermission(PROJECT, USER)).thenReturn(true);
        when(projectMemberService.hasWritePermission(PROJECT, USER)).thenReturn(true);
    }

    private static User user(String username, String displayName) {
        User u = new User();
        u.setId(USER);
        u.setUsername(username);
        u.setDisplayName(displayName);
        return u;
    }

    private void enableAs(User u) {
        when(userService.getUserById(USER)).thenReturn(u);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER);
            controller.enable(PROJECT, "sess");
        }
    }

    @Test
    @DisplayName("署名用展示名，不用桥接自动生成的用户名")
    void authorNameIsTheDisplayName() {
        enableAs(user("awd_upoxwcdtg", "李思"));
        verify(sessionService).enableVersionRecording(eq(PROJECT), eq("李思"), anyString());
    }

    @Test
    @DisplayName("展示名为空：回落用户名，不留一条没有作者的记录")
    void blankDisplayNameFallsBackToUsername() {
        enableAs(user("awd_upoxwcdtg", "  "));
        verify(sessionService).enableVersionRecording(eq(PROJECT), eq("awd_upoxwcdtg"), anyString());
    }
}
