// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.service.account.AwdkLoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 加人前的准入（spec 2026-09-10 §5）：案件库本地查不到时回官网名录找账户，
 * 找到且过了资格门才预建桥接用户。
 *
 * <p>三条不能退的线：
 * <ul>
 *   <li>**被拒的人绝不建桥接用户**——建了就等于在案件库里凭空多出一行陌生人的账号；</li>
 *   <li>本地已有账号且有官网绑定时按 accountId 复核归属（改名/换号都不影响这条键）；</li>
 *   <li>名录没配（自建服务器、桌面单机）时整条准入短路成今天的本地行为，一次出站都不发。</li>
 * </ul>
 */
class CollaboratorAdmissionTest {

    private static final long REQUESTER = 1L;
    private static final long CANDIDATE = 4242L;

    /** 可编排的名录桩，记下调用形状。 */
    static class StubDirectory implements AccountDirectoryClient {
        boolean configured = true;
        DirectoryReply reply = new DirectoryReply(false, null, OrgMembership.NONE, OrgMembership.NONE);
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public DirectoryReply lookupByIdentifier(String requesterAccountId, String identifier) {
            calls.add("identifier:" + requesterAccountId + ":" + identifier);
            return reply;
        }

        @Override
        public DirectoryReply lookupByAccountId(String requesterAccountId, String candidateAccountId) {
            calls.add("accountId:" + requesterAccountId + ":" + candidateAccountId);
            return reply;
        }
    }

    private StubDirectory directory;
    private AccountBindingRepository bindingRepository;
    private AwdkLoginService awdkLoginService;
    private CollaboratorAdmission admission;

    @BeforeEach
    void setUp() {
        directory = new StubDirectory();
        bindingRepository = mock(AccountBindingRepository.class);
        when(bindingRepository.findByUserId(anyLong())).thenReturn(Optional.empty());
        awdkLoginService = mock(AwdkLoginService.class);
        admission = new CollaboratorAdmission(directory, new SameFirmOrTeamPolicy(),
                bindingRepository, awdkLoginService);
    }

    private static User user(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        return u;
    }

    private void bind(long userId, String accountId) {
        AccountBinding b = new AccountBinding();
        b.setUserId(userId);
        b.setExternalAccountId(accountId);
        when(bindingRepository.findByUserId(userId)).thenReturn(Optional.of(b));
    }

    private static DirectoryReply found(String teamRequester, String firmRequester,
                                        String teamCandidate, String firmCandidate) {
        return new DirectoryReply(true,
                new DirectoryAccount("acc-9f", "lisi", "李思", "13800138000"),
                new OrgMembership(teamRequester, firmRequester),
                new OrgMembership(teamCandidate, firmCandidate));
    }

    // ==================== 名录未配置 ====================

    @Test
    @DisplayName("名录未配置：本地有就用本地，本地没有就 NOT_REGISTERED，一次出站都不发")
    void unconfiguredDirectoryKeepsTodaysLocalBehaviour() {
        directory.configured = false;
        User local = user(CANDIDATE, "awd_lisi");

        assertSame(local, admission.admit(Optional.of(local), "13800138000", REQUESTER).user());
        CollaboratorAdmission.Admission miss =
                admission.admit(Optional.empty(), "13800138000", REQUESTER);
        assertNull(miss.user());
        assertEquals(Denial.NOT_REGISTERED, miss.denial());

        assertTrue(directory.calls.isEmpty(), "未配置不许发请求: " + directory.calls);
        assertFalse(admission.directoryConfigured());
        verifyNoInteractions(awdkLoginService);
    }

    // ==================== 本地已有账号 ====================

    @Test
    @DisplayName("本地有账号且有官网绑定：按 accountId 复核归属，过了就直接用本地那行，不重复建号")
    void localUserWithBindingIsRecheckedByAccountId() {
        bind(REQUESTER, "acc-me");
        bind(CANDIDATE, "acc-9f");
        directory.reply = found("team-a", "firm-1", "team-b", "firm-1");
        User local = user(CANDIDATE, "awd_lisi");

        CollaboratorAdmission.Admission a =
                admission.admit(Optional.of(local), "13800138000", REQUESTER);

        assertSame(local, a.user());
        assertNull(a.denial());
        assertEquals(List.of("accountId:acc-me:acc-9f"), directory.calls);
        verifyNoInteractions(awdkLoginService);
    }

    @Test
    @DisplayName("本地有账号但没有官网绑定（案件库里只有 admin 这样）：不出网，归属按 NONE 交给资格门")
    void localUserWithoutBindingNeverGoesOut() {
        bind(REQUESTER, "acc-me");
        User local = user(CANDIDATE, "admin");

        CollaboratorAdmission.Admission a =
                admission.admit(Optional.of(local), "admin", REQUESTER);

        assertTrue(directory.calls.isEmpty(), "不许出网: " + directory.calls);
        assertNull(a.user());
        assertEquals(Denial.NOT_IN_ORG, a.denial(),
                "没有官网身份的人不可能在任何团队里，理由要落在对方身上，不能让律师去查自己的团队");
    }

    // ==================== 本地没有账号 ====================

    @Test
    @DisplayName("本地没有：按 identifier 回官网找，找不到就是 NOT_REGISTERED，不建任何用户")
    void absentLocallyAndAbsentUpstreamIsNotRegistered() {
        bind(REQUESTER, "acc-me");
        directory.reply = new DirectoryReply(false, null, new OrgMembership("team-a", null), OrgMembership.NONE);

        CollaboratorAdmission.Admission a =
                admission.admit(Optional.empty(), "13800138000", REQUESTER);

        assertEquals(List.of("identifier:acc-me:13800138000"), directory.calls);
        assertNull(a.user());
        assertEquals(Denial.NOT_REGISTERED, a.denial());
        verifyNoInteractions(awdkLoginService);
    }

    @Test
    @DisplayName("本地没有、官网有、资格门通过：按官网四个字段预建桥接用户，用它加人")
    void allowedNewcomerGetsABridgedUser() {
        bind(REQUESTER, "acc-me");
        directory.reply = found("team-a", "firm-1", "team-b", "firm-1");
        User bridged = user(77L, "awd_lisi");
        when(awdkLoginService.ensureBridgedUser(anyString(), any(), any(), any())).thenReturn(bridged);

        CollaboratorAdmission.Admission a =
                admission.admit(Optional.empty(), "13800138000", REQUESTER);

        assertSame(bridged, a.user());
        assertNull(a.denial());
        verify(awdkLoginService).ensureBridgedUser("acc-9f", "lisi", "李思", "13800138000");
    }

    @Test
    @DisplayName("资格门拒绝：回 Denial，且**绝不**建桥接用户")
    void deniedNewcomerIsNeverBridged() {
        bind(REQUESTER, "acc-me");
        directory.reply = found("team-a", "firm-1", "team-b", "firm-2");

        CollaboratorAdmission.Admission a =
                admission.admit(Optional.empty(), "13800138000", REQUESTER);

        assertNull(a.user());
        assertEquals(Denial.NOT_IN_ORG, a.denial());
        verifyNoInteractions(awdkLoginService);
    }

    @Test
    @DisplayName("自己还没加入团队：REQUESTER_NO_TEAM，同样不建用户")
    void requesterWithoutATeamIsDeniedBeforeAnyBridging() {
        directory.reply = found(null, null, "team-b", "firm-1");

        CollaboratorAdmission.Admission a =
                admission.admit(Optional.empty(), "13800138000", REQUESTER);

        assertEquals(Denial.REQUESTER_NO_TEAM, a.denial());
        assertEquals(List.of("identifier:null:13800138000"), directory.calls,
                "案件库上没桥接过的操作账号也要拿到可解释的理由，requesterAccountId 为 null 照样发请求");
        verifyNoInteractions(awdkLoginService);
    }

    /** 名录不可用是故障，不是"没找到"：异常必须原样上抛，由上层译成"稍后再试"。 */
    @Test
    @DisplayName("名录不可用：异常上抛，不吞成 NOT_REGISTERED")
    void directoryFailureIsNotSwallowed() {
        AccountDirectoryClient broken = new AccountDirectoryClient() {
            @Override public boolean configured() { return true; }
            @Override public DirectoryReply lookupByIdentifier(String r, String i) {
                throw new DirectoryUnavailableException("上游炸了");
            }
            @Override public DirectoryReply lookupByAccountId(String r, String c) {
                throw new DirectoryUnavailableException("上游炸了");
            }
        };
        CollaboratorAdmission a = new CollaboratorAdmission(broken, new SameFirmOrTeamPolicy(),
                bindingRepository, awdkLoginService);

        assertThrows(DirectoryUnavailableException.class,
                () -> a.admit(Optional.empty(), "13800138000", REQUESTER));
    }

    /** OpenPolicy 下名录只用来把人找出来，归属一概不问。 */
    @Test
    @DisplayName("open 策略：官网找到就允许，照样预建桥接用户")
    void openPolicyAdmitsAnyoneTheDirectoryKnows() {
        CollaboratorAdmission open = new CollaboratorAdmission(directory, new OpenPolicy(),
                bindingRepository, awdkLoginService);
        directory.reply = found(null, null, null, null);
        User bridged = user(77L, "awd_lisi");
        when(awdkLoginService.ensureBridgedUser(anyString(), any(), any(), any())).thenReturn(bridged);

        assertSame(bridged, open.admit(Optional.empty(), "13800138000", REQUESTER).user());
    }
}
