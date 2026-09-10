// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectMember;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.ProjectInvitationRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.account.AwdkLoginService;
import com.checkba.service.collab.AccountDirectoryClient;
import com.checkba.service.collab.CollaboratorAdmission;
import com.checkba.service.collab.DirectoryAccount;
import com.checkba.service.collab.DirectoryReply;
import com.checkba.service.collab.OrgMembership;
import com.checkba.service.collab.SameFirmOrTeamPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 按手机号/邮箱把同事加进案卷（dev-board#439 第 4 环）。
 *
 * 背景：官方案件库里的账号是 awdk 桥接自动建的 awd_ 前缀用户名，律师根本不知道
 * 同事叫什么——他知道的是手机号。按用户名解析保留为兜底（自建服务器场景）。
 */
class ProjectMemberAddByContactTest {

    private static final long PROJECT = 7L;
    private static final long OWNER = 1L;

    private ProjectMemberRepository memberRepository;
    private UserRepository userRepository;
    private ProjectRepository projectRepository;
    private ProjectMemberService service;
    private List<ProjectMember> saved;

    @BeforeEach
    void setUp() {
        memberRepository = mock(ProjectMemberRepository.class);
        userRepository = mock(UserRepository.class);
        projectRepository = mock(ProjectRepository.class);
        service = new ProjectMemberService(memberRepository, userRepository, projectRepository,
                mock(ProjectInvitationRepository.class));

        Project project = new Project();
        project.setId(PROJECT);
        project.setUserId(OWNER);
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project));
        when(memberRepository.findByProjectIdAndUserId(any(), any())).thenReturn(Optional.empty());
        saved = new ArrayList<>();
        when(memberRepository.save(any(ProjectMember.class))).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(userRepository.findByPhone(any())).thenReturn(Optional.empty());
        when(userRepository.findByVerifiedEmail(any())).thenReturn(Optional.empty());
    }

    private User user(long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    @Test
    void aColleagueCanBeAddedByPhoneNumber() {
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(user(9L, "awd_lisi")));

        service.addMember(PROJECT, "13800138000", "PARTICIPANT", OWNER);

        assertEquals(1, saved.size());
        assertEquals(9L, saved.get(0).getUserId());
    }

    /** 存储形态是 11 位裸号（SmsAuthService.normalizePhone 的口径），律师带 +86 或空格照样能查到。 */
    @Test
    void phoneLookupUsesTheStoredFormSoPlusEightySixAndSpacesStillMatch() {
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(user(9L, "awd_lisi")));

        service.addMember(PROJECT, " +86 138-0013-8000 ", "PARTICIPANT", OWNER);

        assertEquals(1, saved.size());
        assertEquals(9L, saved.get(0).getUserId());
    }

    @Test
    void aColleagueCanBeAddedByVerifiedEmail() {
        when(userRepository.findByVerifiedEmail("li@example.com")).thenReturn(Optional.of(user(11L, "awd_li")));

        service.addMember(PROJECT, "  Li@Example.com ", "PARTICIPANT", OWNER);

        assertEquals(1, saved.size());
        assertEquals(11L, saved.get(0).getUserId());
    }

    /** 自建服务器上律师知道对方的账号名——按用户名解析必须保留。 */
    @Test
    void addingByUsernameStillWorksAsTheFallback() {
        when(userRepository.findByUsername("lisi")).thenReturn(Optional.of(user(12L, "lisi")));

        service.addMember(PROJECT, "lisi", "PARTICIPANT", OWNER);

        assertEquals(1, saved.size());
        assertEquals(12L, saved.get(0).getUserId());
    }

    /** 号码没人用过时的文案要说下一步该做什么，不能只回一句"用户不存在"。 */
    @Test
    void anUnknownPhoneNumberExplainsWhatToDoNext() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addMember(PROJECT, "13800138000", "PARTICIPANT", OWNER));

        assertTrue(ex.getMessage().contains("手机号"), ex.getMessage());
        assertFalse(ex.getMessage().startsWith("用户不存在"), ex.getMessage());
        assertTrue(saved.isEmpty());
    }

    @Test
    void anUnknownEmailExplainsWhatToDoNext() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addMember(PROJECT, "li@example.com", "PARTICIPANT", OWNER));

        assertTrue(ex.getMessage().contains("邮箱"), ex.getMessage());
        assertTrue(saved.isEmpty());
    }

    /** 手机号查不到时还要试一次用户名——有人可能真的把账号名起成一串数字。 */
    @Test
    void aDigitsOnlyUsernameIsStillFoundWhenNoOneOwnsThatPhoneNumber() {
        when(userRepository.findByUsername("13800138000")).thenReturn(Optional.of(user(13L, "13800138000")));

        service.addMember(PROJECT, "13800138000", "PARTICIPANT", OWNER);

        assertEquals(13L, saved.get(0).getUserId());
    }

    // ==================== 回官网找账户 + 资格门（spec 2026-09-10） ====================

    private AwdkLoginService injectAdmission(DirectoryReply reply) {
        AccountDirectoryClient directory = new AccountDirectoryClient() {
            @Override public boolean configured() { return true; }
            @Override public DirectoryReply lookupByIdentifier(String r, String i) { return reply; }
            @Override public DirectoryReply lookupByAccountId(String r, String c) { return reply; }
        };
        AccountBindingRepository bindings = mock(AccountBindingRepository.class);
        when(bindings.findByUserId(any())).thenReturn(Optional.empty());
        AwdkLoginService awdk = mock(AwdkLoginService.class);
        service.setCollaboratorAdmissionForTest(new CollaboratorAdmission(
                directory, new SameFirmOrTeamPolicy(), bindings, awdk, mock(UserService.class)));
        return awdk;
    }

    /** 加人与查人同一道门：查人那边拒了，这边照样得拒，而且是同一句话。 */
    @Test
    void addingSomeoneOutsideYourFirmOrTeamIsRejectedWithTheSameMessage() {
        AwdkLoginService awdk = injectAdmission(new DirectoryReply(true,
                new DirectoryAccount("acc-9f", "lisi", "李思", "13800138000"),
                new OrgMembership("team-a", "firm-1"),
                new OrgMembership("team-b", "firm-2")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addMember(PROJECT, "13800138000", "PARTICIPANT", OWNER));

        assertTrue(ex.getMessage().contains("团队"), ex.getMessage());
        assertTrue(saved.isEmpty(), "被拒的人绝不能落成员行");
        verify(awdk, never()).ensureBridgedUser(any(), any(), any(), any());
    }

    /** 治本的那一半：官网注册过、案件库里还没有这行的同事，现在加得进来了。 */
    @Test
    void aColleagueKnownOnlyToTheWebsiteIsBridgedAndAdded() {
        AwdkLoginService awdk = injectAdmission(new DirectoryReply(true,
                new DirectoryAccount("acc-9f", "lisi", "李思", "13800138000"),
                new OrgMembership("team-a", "firm-1"),
                new OrgMembership("team-b", "firm-1")));
        when(awdk.ensureBridgedUser("acc-9f", "lisi", "李思", "13800138000"))
                .thenReturn(user(99L, "awd_lisi"));

        service.addMember(PROJECT, "13800138000", "PARTICIPANT", OWNER);

        assertEquals(1, saved.size());
        assertEquals(99L, saved.get(0).getUserId());
    }
}
