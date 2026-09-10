// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.service.account.AwdkLoginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 「把同事加进案卷」这一步的准入（spec 2026-09-10 §5）。
 *
 * <p>治的是这个病：官方案件库按手机号查人只查本库 {@code app_users}，而那张表只有在桌面端
 * 桥接过案件库的人——在官网注册过、也登录过桌面端的同事照样查不到，界面还引他"去邀请"，
 * 照着做完回来仍然查不到。现在本地查不到就**回官网名录找账户**，找到且过了资格门就按
 * {@code accountId} 预建桥接用户（与日后对方自己桥接落到同一行）。
 *
 * <p>不变式：
 * <ul>
 *   <li>名录没配（{@code collab.directory.*} 为空）→ 整条准入短路成今天的本地行为，不出网；</li>
 *   <li><b>资格门拒绝时绝不建桥接用户</b>——建了等于在案件库里凭空多出一行陌生人的账号；</li>
 *   <li>{@link DirectoryUnavailableException} 原样上抛，绝不吞成"没找到"。</li>
 * </ul>
 */
@Service
@Slf4j
public class CollaboratorAdmission {

    private final AccountDirectoryClient directory;
    private final CollaborationPolicy policy;
    private final AccountBindingRepository bindingRepository;
    private final AwdkLoginService awdkLoginService;

    public CollaboratorAdmission(AccountDirectoryClient directory,
                                 CollaborationPolicy policy,
                                 AccountBindingRepository bindingRepository,
                                 AwdkLoginService awdkLoginService) {
        this.directory = directory;
        this.policy = policy;
        this.bindingRepository = bindingRepository;
        this.awdkLoginService = awdkLoginService;
    }

    /** 准入结果：要么给出可以加进案卷的人，要么给出一个说得清下一步的拒绝理由。 */
    public record Admission(User user, Denial denial) {}

    /** 名录开通了没有。没开通时调用方应当维持今天的本地行为。 */
    public boolean directoryConfigured() {
        return directory.configured();
    }

    /**
     * @param local       本库按手机号/邮箱/用户名查到的人（查不到给 empty）
     * @param identifier  律师输入的那串东西，本地查不到时原样交给名录
     * @param requesterId 操作人（案卷管理员）
     */
    public Admission admit(Optional<User> local, String identifier, Long requesterId) {
        if (!directory.configured()) {
            return local.map(u -> new Admission(u, null))
                    .orElseGet(() -> new Admission(null, Denial.NOT_REGISTERED));
        }

        String requesterAccountId = externalAccountId(requesterId);

        if (local.isPresent()) {
            String candidateAccountId = externalAccountId(local.get().getId());
            if (candidateAccountId == null) {
                // 本地有这行、却没有官网绑定：案件库上这种只有 admin。不出网（没有可查的键），
                // 双方归属都按 NONE 交给资格门——open 放行，firm-or-team 拒绝。
                // 拒绝理由统一报 NOT_IN_ORG：没有官网身份的人不可能在任何团队里，这是对方的
                // 问题；资格门在这一支里对 requester 一无所知，它给的 REQUESTER_NO_TEAM
                // 会让律师去检查自己的团队，白跑一趟。
                Verdict verdict = policy.check(OrgMembership.NONE, OrgMembership.NONE);
                return verdict.allowed() ? new Admission(local.get(), null)
                        : new Admission(null, Denial.NOT_IN_ORG);
            }
            DirectoryReply reply = directory.lookupByAccountId(requesterAccountId, candidateAccountId);
            Verdict verdict = policy.check(reply.requester(), reply.candidate());
            return verdict.allowed() ? new Admission(local.get(), null)
                    : new Admission(null, verdict.denial());
        }

        DirectoryReply reply = directory.lookupByIdentifier(requesterAccountId, identifier);
        if (!reply.found()) {
            return new Admission(null, Denial.NOT_REGISTERED);
        }
        Verdict verdict = policy.check(reply.requester(), reply.candidate());
        if (!verdict.allowed()) {
            return new Admission(null, verdict.denial());
        }
        DirectoryAccount account = reply.account();
        User bridged = awdkLoginService.ensureBridgedUser(
                account.accountId(), account.username(), account.displayName(), account.phone());
        log.info("按官网名录预建协作用户: accountId={} -> userId={}", account.accountId(), bridged.getId());
        return new Admission(bridged, null);
    }

    private String externalAccountId(Long userId) {
        if (userId == null) {
            return null;
        }
        return bindingRepository.findByUserId(userId)
                .map(AccountBinding::getExternalAccountId)
                .orElse(null);
    }
}
