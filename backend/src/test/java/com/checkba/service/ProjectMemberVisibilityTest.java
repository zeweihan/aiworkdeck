package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectMember;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 被邀请方那一半的收尾（dev-board#444）：A 把 B 加成案件参与人之后，B 在
 * 「从团队案件库取一份案卷」里必须真的看得见这份案卷。
 *
 * <p>那个弹窗读的是案件库服务器上的 {@code GET /api/projects/my}，它落到
 * {@link ProjectService#getUserProjects}——所以「加人」这个动作能不能被 B 看见，
 * 全系于这个方法把 {@code project_member} 命中的项目也算进来。这条链此前没有任何
 * 用例钉住（{@code CalendarControllerTest} 只是把它整体 mock 掉了）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectMemberVisibilityTest {

    private static final Long A = 1L;
    private static final Long B = 2L;

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private com.checkba.repository.UserRepository userRepository;
    @Mock private com.checkba.repository.ProjectFileRepository projectFileRepository;
    @Mock private com.checkba.repository.ProjectProfileFieldRepository profileFieldRepository;
    @Mock private TushareService tushareService;
    @Mock private ProjectVariableService projectVariableService;
    @Mock private com.checkba.service.telemetry.TelemetryService telemetryService;
    @Mock private com.checkba.storage.ProjectStorageResolver storageResolver;
    @Mock private com.checkba.version.ProjectRepoService projectRepoService;

    @InjectMocks private ProjectService projectService;

    private Project project(long id, Long ownerId) {
        Project p = new Project();
        p.setId(id);
        p.setUserId(ownerId);
        p.setName("《张三诉李四》");
        p.setCreatedAt(LocalDateTime.now());
        return p;
    }

    @Test
    @DisplayName("被加成参与人之后，这份案卷出现在 B 的项目列表里")
    void aProjectSharedWithMeShowsUpInMyProjects() {
        Project shared = project(42L, A);
        ProjectMember membership = new ProjectMember();
        membership.setProjectId(42L);
        membership.setUserId(B);
        membership.setRole("PARTICIPANT");

        when(projectRepository.findByUserIdOrderByCreatedAtDesc(B)).thenReturn(List.of());
        when(projectMemberRepository.findByUserId(B)).thenReturn(List.of(membership));
        when(projectRepository.findAllById(List.of(42L))).thenReturn(List.of(shared));

        List<Project> visible = projectService.getUserProjects(B);

        assertEquals(1, visible.size());
        assertEquals(42L, visible.get(0).getId());
    }

    @Test
    @DisplayName("没被加进来的人看不到这份案卷")
    void aProjectNotSharedWithMeStaysInvisible() {
        when(projectRepository.findByUserIdOrderByCreatedAtDesc(B)).thenReturn(List.of());
        when(projectMemberRepository.findByUserId(B)).thenReturn(List.of());
        when(projectRepository.findAllById(List.of())).thenReturn(List.of());

        assertTrue(projectService.getUserProjects(B).isEmpty());
    }
}
