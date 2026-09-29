// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectInvitation;
import com.checkba.model.entity.ProjectMember;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectInvitationRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.controller.AuthController;
import com.checkba.controller.ProjectMemberController;
import com.checkba.model.entity.ProjectRemote;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客户访问码的两条路：具名邀请与通用码。
 *
 * <p>不变式：拿到访问码的人登录后必须真的能读到这个项目。项目访问权全靠
 * project_member 行（{@code ProjectMemberService.hasReadPermission} 只认成员行或项目所有者），
 * 所以签发访问码的那条路必须把影子用户加成成员，否则「登录成功」与「什么都打不开」并存。
 */
class ClientInvitationServiceTest {

    private ProjectInvitationRepository invitationRepository;
    private ProjectMemberRepository projectMemberRepository;
    private UserRepository userRepository;
    private ProjectMemberService projectMemberService;
    private LocalIdentityService localIdentityService;
    private ProjectRemoteRepository remoteRepository;
    private ClientInvitationService service;

    private final AtomicLong userIds = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        invitationRepository = mock(ProjectInvitationRepository.class);
        projectMemberRepository = mock(ProjectMemberRepository.class);
        userRepository = mock(UserRepository.class);
        projectMemberService = mock(ProjectMemberService.class);
        when(projectMemberService.hasWritePermission(any(), any())).thenReturn(true);
        when(invitationRepository.findByAccessCode(any())).thenReturn(Optional.empty());
        when(invitationRepository.findByProjectIdAndType(any(), any())).thenReturn(List.of());
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(userIds.incrementAndGet());
            return u;
        });
        when(invitationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 既有用例按服务端形态（案件库，local-mode=false）跑
        localIdentityService = mock(LocalIdentityService.class);
        remoteRepository = mock(ProjectRemoteRepository.class);
        when(remoteRepository.findByProjectId(any())).thenReturn(Optional.empty());
        service = new ClientInvitationService(invitationRepository, projectMemberRepository,
                userRepository, projectMemberService, localIdentityService, remoteRepository);
    }

    private List<ProjectMember> savedMembers() {
        ArgumentCaptor<ProjectMember> captor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("具名邀请：影子用户当场加成 CLIENT 成员（既有行为，护栏）")
    void namedInviteAddsMember() {
        service.inviteClient(1L, 7L, "张三");
        List<ProjectMember> members = savedMembers();
        assertEquals(1, members.size());
        assertEquals("CLIENT", members.get(0).getRole());
        assertEquals(1L, members.get(0).getProjectId());
    }

    /**
     * 通用码这条路只建了影子用户和 invitation 行，从没建过成员行；
     * 而前端的客户登录恒传 displayName=null，走的正是「登录成 relatedUserId 那个影子用户」这一支，
     * 也不建成员行。于是律师把码发出去，客户登录提示成功、页面跳进工作台，
     * 之后每一个文件接口都回 403——「登录成功」与「什么都打不开」并存。
     */
    @Test
    @DisplayName("通用码：影子用户同样要加成成员，否则持码人登录后什么都读不到")
    void genericInviteAddsMemberToo() {
        service.inviteClient(1L, 7L, null);
        List<ProjectMember> members = savedMembers();
        assertTrue(members.stream().anyMatch(m -> "CLIENT".equals(m.getRole()) && m.getProjectId() == 1L),
                "通用码的影子用户没有成员行，持码人拿不到任何项目权限");
    }

    // ---- dev-board#1039：本机未上云的案卷不签访问码 ----

    @Test
    @DisplayName("local-mode 且案卷没有远端绑定：拒绝签发，且不落任何影子用户/邀请行")
    void localModeUnlinkedProjectIsRejected() {
        when(localIdentityService.isLocalMode()).thenReturn(true);
        assertThrows(ClientInvitationService.LibraryRequiredException.class,
                () -> service.inviteClient(1L, 7L, "张三"));
        assertThrows(ClientInvitationService.LibraryRequiredException.class,
                () -> service.inviteClient(1L, 7L, null));
        verify(userRepository, never()).save(any());
        verify(invitationRepository, never()).save(any());
        verify(projectMemberRepository, never()).save(any());
    }

    @Test
    @DisplayName("local-mode 但案卷已放进案件库：照常签发")
    void localModeLinkedProjectStillWorks() {
        when(localIdentityService.isLocalMode()).thenReturn(true);
        when(remoteRepository.findByProjectId(1L)).thenReturn(Optional.of(new ProjectRemote()));
        String code = service.inviteClient(1L, 7L, "张三");
        assertTrue(code != null && !code.isEmpty());
    }

    @Test
    @DisplayName("接口层：本机未上云时回 HTTP 400 + 明确文案")
    void controllerReturns400WithMessage() {
        when(localIdentityService.isLocalMode()).thenReturn(true);
        ProjectMemberController controller = new ProjectMemberController(
                projectMemberService, service, mock(AuthAbuseGuard.class));
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(7L);
            ResponseEntity<Map<String, Object>> res = controller.inviteClient(1L, Map.of("clientName", "张三"), null);
            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
            assertEquals(1, res.getBody().get("code"));
            String message = String.valueOf(res.getBody().get("message"));
            assertTrue(message.contains("案件库") || message.contains("Case Library"), message);
        }
    }
}
