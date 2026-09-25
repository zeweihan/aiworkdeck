// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.version.WorkSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-59（v0.49.0 真机）：版本时间线上有一段工作署名「用户」，其余都是「韩泽伟（你）」。
 * 病根：诉讼可视化等 AI 路径落盘时带的是占位 userId 10001（AGENT_USER_ID，库里没有这一行），
 * 改动信号的署名解析查不到人就回落泛称「用户」；这一笔成了工作段的最后一个操作者，
 * 30 分钟空闲自动结束时整段就署成了「用户」。修法：查不到人时退到项目负责人（本机就是当前用户），
 * userId 一并换成负责人，邮箱/「是不是我」的判定才对得上。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFileServiceSignalActorTest {

    private static final long PROJECT_ID = 1L;
    private static final long FILE_ID = 100L;
    private static final long OWNER_ID = 3L;
    private static final long AGENT_USER_ID = 10001L;

    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private com.checkba.service.ai.ProjectRagService projectRagService;
    @Mock private com.checkba.storage.StorageServiceFactory storageServiceFactory;
    @Mock private WorkSessionService workSessionService;
    @Mock private UserService userService;
    @Mock private com.checkba.service.quota.StageQuotaService stageQuotaService;
    @Mock private com.checkba.service.telemetry.TelemetryService telemetryService;
    @Mock private com.checkba.service.evidence.EvidenceLinkService evidenceLinkService;
    @Mock private ProjectRepository projectRepository;

    private ProjectFileService service;

    @BeforeEach
    void setUp() {
        service = new ProjectFileService(projectFileRepository, projectRagService, storageServiceFactory,
                workSessionService, userService, stageQuotaService, telemetryService, evidenceLinkService);
        ReflectionTestUtils.setField(service, "projectRepository", projectRepository);

        ProjectFile f = new ProjectFile();
        f.setId(FILE_ID);
        f.setProjectId(PROJECT_ID);
        f.setParentId(5L);
        f.setIsFolder(false);
        f.setName("report.pdf");
        f.setFileType("pdf");
        when(projectFileRepository.findById(FILE_ID)).thenReturn(Optional.of(f));
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));

        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setUserId(OWNER_ID);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(p));

        User owner = new User();
        owner.setId(OWNER_ID);
        owner.setUsername("admin");
        owner.setDisplayName("韩泽伟");
        when(userService.getUserById(OWNER_ID)).thenReturn(owner);
        when(userService.getUserById(AGENT_USER_ID))
                .thenThrow(new IllegalArgumentException("用户不存在: " + AGENT_USER_ID));
    }

    @Test
    void agentPlaceholderUserIsSignedAsTheProjectOwnerNotGenericUser() {
        service.rename(FILE_ID, "summary", AGENT_USER_ID);

        verify(workSessionService).onChangeSignal(eq(PROJECT_ID), eq(OWNER_ID), eq("韩泽伟"));
    }

    @Test
    void realUserIsStillSignedAsThemselves() {
        User colleague = new User();
        colleague.setId(8L);
        colleague.setUsername("awd_x");
        colleague.setDisplayName("律师乙");
        when(userService.getUserById(8L)).thenReturn(colleague);

        service.rename(FILE_ID, "summary", 8L);

        verify(workSessionService).onChangeSignal(eq(PROJECT_ID), eq(8L), eq("律师乙"));
    }

    @Test
    void fallsBackToGenericNameOnlyWhenOwnerIsUnresolvableToo() {
        when(userService.getUserById(anyLong())).thenThrow(new RuntimeException("查询失败"));

        service.rename(FILE_ID, "summary", AGENT_USER_ID);

        verify(workSessionService).onChangeSignal(eq(PROJECT_ID), eq(AGENT_USER_ID), eq("用户"));
    }
}
