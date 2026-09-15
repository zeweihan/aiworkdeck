// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.controller.ProjectMemberController;
import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectMember;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.ProjectInvitationRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 参与人列表要带官网账户 id（spec 2026-09-14 §2.6，dev-board#625）。
 *
 * <p>病根：本机成员表里的 {@code hanzewei} 与案件库成员表里的 {@code awd_hanzewei}
 * 是同一个官网账户（头像 URL 里是同一个 accountId），前端按用户名字符串去重，
 * 于是同一个人在「案件参与人」里出现两次、显示成「2 人」。
 * 账户 id 是两边唯一对得上的键——两侧的 members 都带上它，去重才有依据。
 */
class ProjectMemberAccountIdTest {

    private static final long PROJECT = 7L;
    private static final long OWNER = 1L;
    private static final long COLLEAGUE = 4242L;

    private ProjectMemberRepository memberRepository;
    private UserRepository userRepository;
    private AccountBindingRepository bindingRepository;
    private AccountService accountService;
    private ProjectMemberController controller;

    @BeforeEach
    void setUp() {
        memberRepository = mock(ProjectMemberRepository.class);
        userRepository = mock(UserRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        bindingRepository = mock(AccountBindingRepository.class);
        accountService = mock(AccountService.class);

        ProjectMemberService service = new ProjectMemberService(memberRepository, userRepository,
                projectRepository, mock(ProjectInvitationRepository.class));
        service.setAccountLookupForTest(bindingRepository, "https://www.aiworkdeck.com");
        service.setAccountServiceForTest(accountService);
        controller = new ProjectMemberController(service, mock(ClientInvitationService.class),
                mock(AuthAbuseGuard.class));

        Project project = new Project();
        project.setId(PROJECT);
        project.setUserId(OWNER);
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project));
        when(userRepository.findById(OWNER)).thenReturn(Optional.of(user(OWNER, "hanzewei", "韩泽伟")));
        when(bindingRepository.findByUserId(any())).thenReturn(Optional.empty());
        when(accountService.currentAccountIdOrNull()).thenReturn(null);
    }

    private static User user(long id, String username, String displayName) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        return u;
    }

    private void hasMember(User u) {
        ProjectMember m = new ProjectMember();
        m.setId(11L);
        m.setProjectId(PROJECT);
        m.setUserId(u.getId());
        m.setRole("PARTICIPANT");
        when(memberRepository.findByProjectId(PROJECT)).thenReturn(List.of(m));
        when(userRepository.findAllById(any())).thenReturn(List.of(u));
    }

    private void bind(long userId, String accountId) {
        AccountBinding b = new AccountBinding();
        b.setUserId(userId);
        b.setExternalAccountId(accountId);
        when(bindingRepository.findByUserId(userId)).thenReturn(Optional.of(b));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> members() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(OWNER);
            Map<String, Object> res = controller.getMembers(PROJECT, "sess");
            assertEquals(0, res.get("code"));
            return (List<Map<String, Object>>) res.get("data");
        }
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, long userId) {
        return rows.stream().filter(r -> Long.valueOf(userId).equals(r.get("userId")))
                .findFirst().orElseThrow(() -> new AssertionError("名单里没有 userId=" + userId));
    }

    @Test
    @DisplayName("桥接同事：accountId 来自 account_binding")
    void colleagueAccountIdComesFromBinding() {
        hasMember(user(COLLEAGUE, "awd_upoxwcdtg", "李思"));
        bind(COLLEAGUE, "acc-9f");

        assertEquals("acc-9f", row(members(), COLLEAGUE).get("accountId"));
    }

    @Test
    @DisplayName("本机的自己（local-mode 下库里没有绑定行）：取本机连着的官网账户 id")
    void myOwnRowFallsBackToTheConnectedAccount() {
        hasMember(user(COLLEAGUE, "awd_upoxwcdtg", "李思"));
        when(accountService.currentAccountIdOrNull()).thenReturn("acc-me");

        // owner 那一行就是调用者自己（虚拟行同样要带上）
        assertEquals("acc-me", row(members(), OWNER).get("accountId"));
        assertEquals("ADMIN", row(members(), OWNER).get("role"));
    }

    @Test
    @DisplayName("别人那一行绝不拿本机账户顶替：查不到绑定就是 null")
    void otherPeopleNeverInheritMyAccountId() {
        hasMember(user(COLLEAGUE, "awd_upoxwcdtg", "李思"));
        when(accountService.currentAccountIdOrNull()).thenReturn("acc-me");

        assertNull(row(members(), COLLEAGUE).get("accountId"),
                "把本机账户 id 安到同事头上，去重会把两个人合成一个");
    }

    @Test
    @DisplayName("绑定优先于本机账户：两样都有时以 account_binding 为准")
    void bindingWinsOverTheConnectedAccount() {
        hasMember(user(COLLEAGUE, "awd_upoxwcdtg", "李思"));
        bind(OWNER, "acc-bound");
        when(accountService.currentAccountIdOrNull()).thenReturn("acc-me");

        assertEquals("acc-bound", row(members(), OWNER).get("accountId"));
    }

    @Test
    @DisplayName("既没绑定也没连账户（自建服务器的人工账号）：null，不硬编一个")
    void nothingKnownMeansNull() {
        hasMember(user(COLLEAGUE, "lisi", "李思"));

        List<Map<String, Object>> rows = members();
        assertNull(row(rows, COLLEAGUE).get("accountId"));
        assertNull(row(rows, OWNER).get("accountId"));
        assertTrue(row(rows, COLLEAGUE).containsKey("accountId"), "键要在，值才允许是 null");
    }

    @Test
    @DisplayName("读本机账户炸了也不许把整个参与人列表打挂")
    void accountLookupFailureDoesNotBreakTheList() {
        hasMember(user(COLLEAGUE, "awd_upoxwcdtg", "李思"));
        when(accountService.currentAccountIdOrNull()).thenThrow(new IllegalStateException("凭据文件坏了"));

        assertNull(row(members(), OWNER).get("accountId"));
    }
}
