// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.AccountBinding;
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
import com.checkba.service.collab.Denial;
import com.checkba.service.collab.DirectoryAccount;
import com.checkba.service.collab.DirectoryReply;
import com.checkba.service.collab.DirectoryUnavailableException;
import com.checkba.service.collab.OrgMembership;
import com.checkba.service.collab.SameFirmOrTeamPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 先查人、看清头像姓名再确认加入（dev-board#444）。
 *
 * <p>「输了手机号就直接加」的问题是律师看不见自己加的是谁——号码打错一位就把
 * 一个陌生人加进了案卷，而案卷里是客户材料。所以加人前先回显一张人卡：
 * 展示名 + 头像 + 打码联系方式，确认了才加。
 *
 * <p>这张卡也是一个「这个手机号注册过没有」的探测口，所以它**绝不能**回原始
 * 手机号、用户名或 userId——那三样都是可以拿去做撞库与社工的原料。
 */
class ProjectMemberLookupTest {

    private static final long PROJECT = 7L;
    private static final long OWNER = 1L;
    private static final String ACCOUNT_BASE = "https://www.aiworkdeck.com";

    private ProjectMemberRepository memberRepository;
    private UserRepository userRepository;
    private ProjectRepository projectRepository;
    private AccountBindingRepository bindingRepository;
    private AwdkLoginService awdkLoginService;
    private ProjectMemberService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(ProjectMemberRepository.class);
        userRepository = mock(UserRepository.class);
        projectRepository = mock(ProjectRepository.class);
        bindingRepository = mock(AccountBindingRepository.class);
        awdkLoginService = mock(AwdkLoginService.class);
        service = new ProjectMemberService(memberRepository, userRepository, projectRepository,
                mock(ProjectInvitationRepository.class));
        service.setAccountLookupForTest(bindingRepository, ACCOUNT_BASE);

        Project project = new Project();
        project.setId(PROJECT);
        project.setUserId(OWNER);
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project));
        when(memberRepository.findByProjectIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(userRepository.findByPhone(any())).thenReturn(Optional.empty());
        when(userRepository.findByVerifiedEmail(any())).thenReturn(Optional.empty());
        when(bindingRepository.findByUserId(any())).thenReturn(Optional.empty());
    }

    private User colleague() {
        User u = new User();
        u.setId(4242L);
        u.setUsername("awd_7f3c1a");   // 桥接自动生成，律师从没见过
        u.setDisplayName("李思");
        u.setPhone("13800138000");
        return u;
    }

    @Test
    @DisplayName("按手机号查到人：回展示名与打码号，绝不回原始号/用户名/userId")
    void lookupByPhoneReturnsMaskedIdentityOnly() {
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(colleague()));

        ProjectMemberService.MemberLookup r =
                service.lookupMember(PROJECT, " +86 138-0013-8000 ", OWNER);

        assertTrue(r.found());
        assertEquals("李思", r.displayName());
        assertEquals("138****8000", r.maskedContact());
        assertFalse(r.alreadyMember());
        assertNull(r.currentRole());

        String dump = String.valueOf(r);
        assertFalse(dump.contains("13800138000"), "回包里不得出现原始手机号: " + dump);
        assertFalse(dump.contains("awd_7f3c1a"), "回包里不得出现用户名: " + dump);
        assertFalse(dump.contains("4242"), "回包里不得出现 userId: " + dump);
    }

    @Test
    @DisplayName("按邮箱查到人：打码只露首字符与域名")
    void lookupByEmailMasksTheLocalPart() {
        User u = colleague();
        u.setPhone(null);
        u.setVerifiedEmail("lisi@example.com");
        when(userRepository.findByVerifiedEmail("lisi@example.com")).thenReturn(Optional.of(u));

        ProjectMemberService.MemberLookup r =
                service.lookupMember(PROJECT, "  Lisi@Example.com ", OWNER);

        assertTrue(r.found());
        assertEquals("l***@example.com", r.maskedContact());
    }

    @Test
    @DisplayName("查不到：found=false + 说下一步该做什么的话，不抛异常")
    void lookupMissesWithAnActionableMessage() {
        ProjectMemberService.MemberLookup r =
                service.lookupMember(PROJECT, "13800138000", OWNER);

        assertFalse(r.found());
        assertNotNull(r.message());
        assertTrue(r.message().contains("手机号"), r.message());
        assertFalse(r.message().startsWith("用户不存在"), r.message());
        assertNull(r.displayName());
        assertNull(r.maskedContact());
        assertNull(r.reason(), "没接名录时不给 reason，老服务端与今天的呈现逐字一致");
    }

    @Test
    @DisplayName("已经在案卷里：alreadyMember=true 并带上现在的角色（界面据此禁用按钮）")
    void lookupReportsAnExistingMemberAndTheirRole() {
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(colleague()));
        ProjectMember existing = new ProjectMember();
        existing.setProjectId(PROJECT);
        existing.setUserId(4242L);
        existing.setRole("READ_ONLY");
        when(memberRepository.findByProjectIdAndUserId(PROJECT, 4242L)).thenReturn(Optional.of(existing));

        ProjectMemberService.MemberLookup r = service.lookupMember(PROJECT, "13800138000", OWNER);

        assertTrue(r.found());
        assertTrue(r.alreadyMember());
        assertEquals("READ_ONLY", r.currentRole());
    }

    @Test
    @DisplayName("项目负责人本人：也算已在案卷里，角色是 OWNER")
    void lookupReportsTheProjectOwnerAsAMember() {
        User owner = colleague();
        owner.setId(OWNER);
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(owner));

        ProjectMemberService.MemberLookup r = service.lookupMember(PROJECT, "13800138000", OWNER);

        assertTrue(r.alreadyMember());
        assertEquals("OWNER", r.currentRole());
    }

    @Test
    @DisplayName("有官网账户绑定：头像走官网公开头像地址")
    void avatarComesFromTheWebsiteAccountBinding() {
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(colleague()));
        AccountBinding b = new AccountBinding();
        b.setUserId(4242L);
        b.setExternalAccountId("acc_42");
        when(bindingRepository.findByUserId(4242L)).thenReturn(Optional.of(b));

        ProjectMemberService.MemberLookup r = service.lookupMember(PROJECT, "13800138000", OWNER);

        assertEquals(ACCOUNT_BASE + "/api/avatar/acc_42", r.avatarUrl());
    }

    /** 自建服务器上人工建的本地账号没有官网绑定：头像给 null，界面降级成首字母方块。 */
    @Test
    @DisplayName("没有官网账户绑定：avatarUrl 为 null，不硬拼一个必然 404 的地址")
    void avatarIsNullWithoutAnAccountBinding() {
        when(userRepository.findByPhone("13800138000")).thenReturn(Optional.of(colleague()));

        ProjectMemberService.MemberLookup r = service.lookupMember(PROJECT, "13800138000", OWNER);

        assertNull(r.avatarUrl());
    }

    @Test
    @DisplayName("不是项目管理员：查人这一步就被拒（与加人同一道权限）")
    void lookupRequiresTheSameAdminPermissionAsAdding() {
        when(memberRepository.findByProjectIdAndUserId(PROJECT, 99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.lookupMember(PROJECT, "13800138000", 99L));
        verify(userRepository, never()).findByPhone(any());
    }

    // ==================== 回官网找账户 + 资格门（spec 2026-09-10） ====================

    /** 名录桩：configured() 恒 true，回预先编排的一份答复（或抛不可用）。 */
    private void injectAdmission(DirectoryReply reply, RuntimeException failure) {
        AccountDirectoryClient directory = new AccountDirectoryClient() {
            @Override public boolean configured() { return true; }
            @Override public DirectoryReply lookupByIdentifier(String r, String i) { return answer(); }
            @Override public DirectoryReply lookupByAccountId(String r, String c) { return answer(); }
            private DirectoryReply answer() {
                if (failure != null) throw failure;
                return reply;
            }
        };
        service.setCollaboratorAdmissionForTest(new CollaboratorAdmission(
                directory, new SameFirmOrTeamPolicy(), bindingRepository, awdkLoginService,
                mock(UserService.class)));
    }

    @Test
    @DisplayName("对方有账户但不在你的律所/团队：found=false + reason，且**一个身份字段都不回**")
    void deniedLookupSaysNothingAboutWhoTheyAre() {
        injectAdmission(new DirectoryReply(true,
                new DirectoryAccount("acc-9f", "lisi", "李思", "13800138000"),
                new OrgMembership("team-a", "firm-1"),
                new OrgMembership("team-b", "firm-2")), null);

        ProjectMemberService.MemberLookup r = service.lookupMember(PROJECT, "13800138000", OWNER);

        assertFalse(r.found());
        assertEquals(Denial.NOT_IN_ORG.name(), r.reason());
        assertNull(r.displayName(), "被拒时不得回展示名——存在与否可以说，是谁不能说");
        assertNull(r.avatarUrl());
        assertNull(r.maskedContact());
        assertTrue(r.message().contains("团队"), r.message());
        verify(awdkLoginService, never()).ensureBridgedUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("自己还没加入团队：reason=REQUESTER_NO_TEAM，文案落到「你还没有加入团队」")
    void requesterWithoutATeamGetsTheirOwnReason() {
        injectAdmission(new DirectoryReply(true,
                new DirectoryAccount("acc-9f", "lisi", "李思", "13800138000"),
                OrgMembership.NONE,
                new OrgMembership("team-b", "firm-1")), null);

        ProjectMemberService.MemberLookup r = service.lookupMember(PROJECT, "13800138000", OWNER);

        assertFalse(r.found());
        assertEquals(Denial.REQUESTER_NO_TEAM.name(), r.reason());
        assertTrue(r.message().contains("团队"), r.message());
    }

    @Test
    @DisplayName("官网也没有这个号：reason=NOT_REGISTERED，文案说的是「注册」不是「登录过」")
    void unknownUpstreamAccountIsNotRegistered() {
        injectAdmission(new DirectoryReply(false, null,
                new OrgMembership("team-a", "firm-1"), OrgMembership.NONE), null);

        ProjectMemberService.MemberLookup r = service.lookupMember(PROJECT, "13800138000", OWNER);

        assertFalse(r.found());
        assertEquals(Denial.NOT_REGISTERED.name(), r.reason());
        assertTrue(r.message().contains("手机号"), r.message());
        assertTrue(r.message().contains("注册"), r.message());
    }

    @Test
    @DisplayName("官网有、资格门通过：预建桥接用户后照常回人卡")
    void allowedNewcomerIsBridgedAndShownAsACard() {
        User bridged = colleague();
        when(awdkLoginService.ensureBridgedUser("acc-9f", "lisi", "李思", "13800138000"))
                .thenReturn(bridged);
        injectAdmission(new DirectoryReply(true,
                new DirectoryAccount("acc-9f", "lisi", "李思", "13800138000"),
                new OrgMembership("team-a", "firm-1"),
                new OrgMembership("team-b", "firm-1")), null);

        ProjectMemberService.MemberLookup r = service.lookupMember(PROJECT, "13800138000", OWNER);

        assertTrue(r.found());
        assertNull(r.reason());
        assertEquals("李思", r.displayName());
        assertEquals("138****8000", r.maskedContact());
    }

    /** 名录连不上是故障，不是"没找到"：必须走异常，让界面显示红字而不是「还没有人注册」。 */
    @Test
    @DisplayName("名录不可用：抛业务异常（前端红字），绝不退化成 found=false")
    void directoryOutageIsAnErrorNotAMiss() {
        injectAdmission(null, new DirectoryUnavailableException("上游炸了"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.lookupMember(PROJECT, "13800138000", OWNER));

        assertTrue(e.getMessage().contains("稍后再试"), e.getMessage());
        assertFalse(e.getMessage().contains("注册"), e.getMessage());
    }

    @Test
    @DisplayName("识别串为空：维持今天的提示，不出网、不给 reason")
    void blankIdentifierKeepsTodaysPrompt() {
        injectAdmission(new DirectoryReply(true,
                new DirectoryAccount("acc-9f", "lisi", "李思", "13800138000"),
                new OrgMembership("team-a", "firm-1"),
                new OrgMembership("team-b", "firm-1")), null);

        ProjectMemberService.MemberLookup r = service.lookupMember(PROJECT, "  ", OWNER);

        assertFalse(r.found());
        assertNull(r.reason());
        assertTrue(r.message().contains("请填写"), r.message());
    }
}
