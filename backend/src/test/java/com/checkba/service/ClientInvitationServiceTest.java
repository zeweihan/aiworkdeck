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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @DisplayName("local-mode 且案卷已放进案件库：本机也不签，要经案件库签发（dev-board#1050）")
    void localModeLinkedProjectMustIssueViaLibrary() {
        when(localIdentityService.isLocalMode()).thenReturn(true);
        when(remoteRepository.findByProjectId(1L)).thenReturn(Optional.of(new ProjectRemote()));
        ClientInvitationService.LibraryRequiredException e = assertThrows(
                ClientInvitationService.IssueViaLibraryException.class,
                () -> service.inviteClient(1L, 7L, "张三"));
        assertTrue(e.getMessage().contains("案件库签发") || e.getMessage().contains("issued through the library"),
                e.getMessage());
        verify(userRepository, never()).save(any());
        verify(invitationRepository, never()).save(any());
    }

    // ---- dev-board#1050：有效期与同码复用 ----

    @Test
    @DisplayName("签发即写有效期（30 天），回执带得出来")
    void issueSetsThirtyDayExpiry() {
        ClientInvitationService.Issued issued = service.issueClientCode(1L, 7L, "张三");
        assertNotNull(issued.expiresAt());
        long days = java.time.Duration.between(java.time.LocalDateTime.now(), issued.expiresAt()).toDays();
        assertTrue(days >= 29 && days <= 30, "有效期应为 30 天，实际 " + days);
        assertNotNull(issued.clientUserId());
    }

    @Test
    @DisplayName("过期的码登录被拒")
    void expiredCodeIsRejected() {
        ProjectInvitation inv = invitation(5L, 1L);
        inv.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(1));
        when(invitationRepository.findByAccessCode("CODE")).thenReturn(Optional.of(inv));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.validateCode("CODE"));
        assertTrue(e.getMessage().contains("过期") || e.getMessage().contains("expired"), e.getMessage());
    }

    @Test
    @DisplayName("没有 expiresAt 的老码按签发时间 + 30 天推算")
    void legacyCodeExpiresFromCreatedAt() {
        ProjectInvitation old = invitation(5L, 1L);
        old.setCreatedAt(java.time.LocalDateTime.now().minusDays(31));
        when(invitationRepository.findByAccessCode("OLD")).thenReturn(Optional.of(old));
        assertThrows(IllegalArgumentException.class, () -> service.validateCode("OLD"));
        ProjectInvitation fresh = invitation(6L, 1L);
        fresh.setCreatedAt(java.time.LocalDateTime.now().minusDays(3));
        when(invitationRepository.findByAccessCode("FRESH")).thenReturn(Optional.of(fresh));
        assertEquals(fresh, service.validateCode("FRESH"));
    }

    @Test
    @DisplayName("重新签发通用码即续期")
    void reissueGenericRefreshesExpiry() {
        ProjectInvitation generic = invitation(5L, 1L);
        generic.setType("CLIENT_GENERIC");
        generic.setAccessCode("abcdefghijklmnopqrst");
        generic.setRelatedUserId(55L);
        generic.setExpiresAt(java.time.LocalDateTime.now().minusDays(1));
        when(invitationRepository.findByProjectIdAndType(1L, "CLIENT_GENERIC")).thenReturn(List.of(generic));
        ClientInvitationService.Issued issued = service.issueClientCode(1L, 7L, null);
        assertEquals("abcdefghijklmnopqrst", issued.code());
        assertTrue(issued.expiresAt().isAfter(java.time.LocalDateTime.now().plusDays(29)));
        assertEquals(55L, issued.clientUserId());
    }

    @Test
    @DisplayName("同码同称呼重复登录复用同一个客户用户，不再每次新建")
    void sameNameReusesClientUser() {
        ProjectInvitation inv = invitation(5L, 1L);
        java.util.Map<String, User> byName = new java.util.HashMap<>();
        when(userRepository.findByUsername(any())).thenAnswer(i -> Optional.ofNullable(byName.get(i.getArgument(0))));
        org.mockito.Mockito.doAnswer(i -> {
            User u = i.getArgument(0);
            if (u.getId() == null) u.setId(userIds.incrementAndGet());
            byName.put(u.getUsername(), u);
            return u;
        }).when(userRepository).save(any());
        when(projectMemberRepository.findByProjectIdAndUserId(any(), any()))
                .thenReturn(Optional.of(new ProjectMember()));

        User first = service.createClientUser(inv, "李四");
        User second = service.createClientUser(inv, " 李四 ");
        assertEquals(first.getId(), second.getId());
        verify(userRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("被律师移出过的人再用同码登录被拒，不会把自己加回来")
    void removedPersonCannotRejoinByName() {
        ProjectInvitation inv = invitation(5L, 1L);
        User existing = new User();
        existing.setId(77L);
        when(userRepository.findByUsername(any())).thenReturn(Optional.of(existing));
        when(projectMemberRepository.findByProjectIdAndUserId(1L, 77L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.createClientUser(inv, "李四"));
        verify(projectMemberRepository, never()).save(any());
    }

    @Test
    @DisplayName("同一张码能建出的不同称呼用户有上限")
    void distinctNamesPerCodeAreCapped() {
        ProjectInvitation inv = invitation(5L, 1L);
        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(userRepository.countByUsernameStartingWith("client_inv5_"))
                .thenReturn((long) ClientInvitationService.MAX_USERS_PER_INVITATION);
        assertThrows(IllegalArgumentException.class, () -> service.createClientUser(inv, "王五"));
        verify(userRepository, never()).save(any());
    }

    private static ProjectInvitation invitation(Long id, Long projectId) {
        ProjectInvitation inv = new ProjectInvitation();
        inv.setId(id);
        inv.setProjectId(projectId);
        inv.setAccessCode("CODE");
        inv.setType("CLIENT_NAMED");
        inv.setRelatedUserId(99L);
        return inv;
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
