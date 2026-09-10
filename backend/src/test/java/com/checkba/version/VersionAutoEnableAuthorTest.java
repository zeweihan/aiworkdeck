// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 自动开启落下的「初始版本」署名（spec 2026-09-10 §4）：与 VersionController 手动开启同一口径——
 * 展示名优先，空才回落用户名。以前取的是 username，时间线第一条就是 {@code admin}。
 */
class VersionAutoEnableAuthorTest {

    private static final long PROJECT = 5L;
    private static final long OWNER = 9L;

    private WorkSessionService sessionService;
    private UserRepository userRepository;
    private VersionLifecycleService lifecycle;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        sessionService = mock(WorkSessionService.class);
        doAnswer(i -> { ((Runnable) i.getArgument(1)).run(); return null; })
                .when(sessionService).runLocked(anyLong(), any(Runnable.class));
        ProjectRepoService repoService = mock(ProjectRepoService.class);
        when(repoService.isInitialized(PROJECT)).thenReturn(false);
        when(repoService.workTree(PROJECT)).thenReturn(tmp);
        ProjectRepository projects = mock(ProjectRepository.class);
        Project p = new Project();
        p.setId(PROJECT);
        p.setUserId(OWNER);
        when(projects.findById(PROJECT)).thenReturn(Optional.of(p));
        userRepository = mock(UserRepository.class);
        lifecycle = new VersionLifecycleService(sessionService, repoService, projects, userRepository,
                mock(ProjectRemoteRepository.class), Runnable::run);
    }

    private void owner(String username, String displayName) {
        User u = new User();
        u.setId(OWNER);
        u.setUsername(username);
        u.setDisplayName(displayName);
        when(userRepository.findById(OWNER)).thenReturn(Optional.of(u));
    }

    @Test
    void initialVersionIsSignedWithTheDisplayName() {
        owner("admin", "韩律师");
        lifecycle.autoEnableNow(PROJECT, null, null);
        verify(sessionService).enableVersionRecording(eq(PROJECT), eq("韩律师"), anyString());
    }

    @Test
    void blankDisplayNameFallsBackToTheUsername() {
        owner("admin", " ");
        lifecycle.autoEnableNow(PROJECT, null, null);
        verify(sessionService).enableVersionRecording(eq(PROJECT), eq("admin"), anyString());
    }
}
