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
import com.checkba.controller.AuthController;
import com.checkba.controller.ProjectMemberController;
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
 * 参与人列表的头像来源（spec 2026-09-10 §4）：桥接进来的同事本机表里根本没有头像，
 * 直接回 {@code user.avatarUrl} 就是一片空白首字母。改走
 * {@link ProjectMemberService#avatarUrlFor}——本机有就本机，否则按官网账户绑定
 * 拼出官网公开头像地址，与「加同事」确认卡同一个口径。
 *
 * <p>{@code username} 字段**保留一版**给老客户端，前端不再读它。
 */
class ProjectMemberAvatarSourceTest {

    private static final long PROJECT = 7L;
    private static final long OWNER = 1L;
    private static final long COLLEAGUE = 4242L;
    private static final String ACCOUNT_BASE = "https://www.aiworkdeck.com";

    private ProjectMemberRepository memberRepository;
    private UserRepository userRepository;
    private ProjectRepository projectRepository;
    private AccountBindingRepository bindingRepository;
    private ProjectMemberController controller;

    @BeforeEach
    void setUp() {
        memberRepository = mock(ProjectMemberRepository.class);
        userRepository = mock(UserRepository.class);
        projectRepository = mock(ProjectRepository.class);
        bindingRepository = mock(AccountBindingRepository.class);
        ProjectMemberService service = new ProjectMemberService(memberRepository, userRepository,
                projectRepository, mock(ProjectInvitationRepository.class));
        service.setAccountLookupForTest(bindingRepository, ACCOUNT_BASE);
        controller = new ProjectMemberController(service, mock(ClientInvitationService.class),
                mock(AuthAbuseGuard.class));

        Project project = new Project();
        project.setId(PROJECT);
        project.setUserId(OWNER);
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(project));
        when(userRepository.findById(OWNER)).thenReturn(Optional.of(owner()));
        when(bindingRepository.findByUserId(any())).thenReturn(Optional.empty());
    }

    private static User owner() {
        User u = new User();
        u.setId(OWNER);
        u.setUsername("hanzewei");
        u.setDisplayName("韩泽伟");
        return u;
    }

    /** 手机号注册、由「加同事」预建出来的桥接用户：本机没有头像，官网有。 */
    private static User colleague() {
        User u = new User();
        u.setId(COLLEAGUE);
        u.setUsername("awd_upoxwcdtg");
        u.setDisplayName("李思");
        return u;
    }

    private void hasMember(User user) {
        ProjectMember member = new ProjectMember();
        member.setId(11L);
        member.setProjectId(PROJECT);
        member.setUserId(user.getId());
        member.setRole("PARTICIPANT");
        when(memberRepository.findByProjectId(PROJECT)).thenReturn(List.of(member));
        when(userRepository.findAllById(any())).thenReturn(List.of(user));
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
    @DisplayName("桥接同事本机没头像：回官网 /api/avatar/{accountId}，不是 null")
    void bridgedColleagueGetsTheWebsiteAvatar() {
        User lisi = colleague();
        hasMember(lisi);
        AccountBinding binding = new AccountBinding();
        binding.setUserId(COLLEAGUE);
        binding.setExternalAccountId("acc-9f");
        when(bindingRepository.findByUserId(COLLEAGUE)).thenReturn(Optional.of(binding));

        Map<String, Object> row = row(members(), COLLEAGUE);

        assertEquals(ACCOUNT_BASE + "/api/avatar/acc-9f", row.get("avatarUrl"));
        assertEquals("李思", row.get("displayName"));
        assertEquals("awd_upoxwcdtg", row.get("username"), "username 保留一版给老客户端");
    }

    @Test
    @DisplayName("本机上传过头像（自建服务器的人工账号）：本机那份优先")
    void localAvatarWins() {
        User lisi = colleague();
        lisi.setAvatarUrl("http://127.0.0.1:5269/api/users/avatar/4242_1.png");
        hasMember(lisi);
        AccountBinding binding = new AccountBinding();
        binding.setUserId(COLLEAGUE);
        binding.setExternalAccountId("acc-9f");
        when(bindingRepository.findByUserId(COLLEAGUE)).thenReturn(Optional.of(binding));

        assertEquals("http://127.0.0.1:5269/api/users/avatar/4242_1.png",
                row(members(), COLLEAGUE).get("avatarUrl"));
    }

    @Test
    @DisplayName("负责人那一行同享这条：owner 也走官网头像，不是本机的 null")
    void ownerRowUsesTheSameSource() {
        hasMember(colleague());
        AccountBinding binding = new AccountBinding();
        binding.setUserId(OWNER);
        binding.setExternalAccountId("acc-me");
        when(bindingRepository.findByUserId(OWNER)).thenReturn(Optional.of(binding));

        Map<String, Object> row = row(members(), OWNER);

        assertEquals(ACCOUNT_BASE + "/api/avatar/acc-me", row.get("avatarUrl"));
        assertEquals("ADMIN", row.get("role"));
    }

    @Test
    @DisplayName("没有官网绑定也没有本机头像：null，不硬拼一个必然 404 的地址")
    void noBindingNoAvatar() {
        hasMember(colleague());
        assertNull(row(members(), COLLEAGUE).get("avatarUrl"));
    }
}
