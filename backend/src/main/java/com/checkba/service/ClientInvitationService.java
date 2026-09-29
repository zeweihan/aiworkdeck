// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectInvitation;
import com.checkba.model.entity.ProjectMember;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectInvitationRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientInvitationService {

    private final ProjectInvitationRepository invitationRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ProjectMemberService projectMemberService;
    private final LocalIdentityService localIdentityService;
    private final ProjectRemoteRepository remoteRepository;

    /**
     * 本机（local-mode）案卷还没放进案件库时拒绝签发访问码（dev-board#1039）。
     * 桌面端后端只听回环、所有请求都解析为本机用户，客户拿着一张落在本机库里的码
     * 没有任何入口够得着——签出来就是一张没人能用的码。前端已按同一判据收起「客户」页签，
     * 这里兜住绕过前端直调接口的情况。
     */
    public static class LibraryRequiredException extends IllegalStateException {
        public LibraryRequiredException() {
            this(LangText.of(
                    "这份案卷还没放进团队案件库，客户无法凭访问码查看。请先放进团队案件库再邀请客户。",
                    "This case file is not in the Team Case Library yet, so clients cannot view it with an access code. Add it to the Team Case Library first, then invite clients."));
        }

        protected LibraryRequiredException(String message) {
            super(message);
        }
    }

    /**
     * 本机（local-mode）案卷已经放进案件库时，访问码必须由**案件库**签发（dev-board#1050）：
     * 客户登录的是案件库的客户门户，本机 H2 里签出来的码与影子用户案件库上根本不存在。
     * 前端云端轨道走 {@code POST /api/cloud/projects/{id}/invite/client} 代理，不会打到这里；
     * 这里兜住绕过前端直调本机接口的情况。
     */
    public static class IssueViaLibraryException extends LibraryRequiredException {
        public IssueViaLibraryException() {
            super(LangText.of(
                    "这份案卷已放进团队案件库，客户访问码需要通过案件库签发，本机签的码客户无法使用。",
                    "This case file is in the Team Case Library, so client access codes must be issued through the library; a code issued on this computer would not work for the client."));
        }
    }

    /** 访问码默认有效天数（dev-board#1050）。重新签发即续期。 */
    public static final int VALID_DAYS = 30;

    /**
     * 同一张通用码最多能建出多少个具名客户用户（带 displayName 登录那一支）。
     * 同名复用之后，刷不同的名字仍能建人，这是兜底上限——一家客户公司里凭同一张码
     * 进来看材料的人不会有这么多。
     */
    static final int MAX_USERS_PER_INVITATION = 20;

    /** 签发结果：码、有效期截止、它登录成的那个客户用户（撤销时按这个 id 移出）。 */
    public record Issued(String code, LocalDateTime expiresAt, Long clientUserId) {}

    @Transactional
    public String inviteClient(Long projectId, Long requesterId, String clientName) {
        return issueClientCode(projectId, requesterId, clientName).code();
    }

    @Transactional
    public Issued issueClientCode(Long projectId, Long requesterId, String clientName) {
        // 1. Check permissions (Allow Admin and Participant)
        if (!projectMemberService.hasWritePermission(projectId, requesterId)) {
             throw new IllegalArgumentException("权限不足：只有管理员或参与者可以邀请客户");
        }
        // 判据与前端 InviteMemberDialog 的轨道同源：local-mode 且没有 project_remote 绑定 = 未放进案件库；
        // 有绑定则必须经案件库签发（本机签的码客户用不了）
        if (localIdentityService.isLocalMode()) {
            if (remoteRepository.findByProjectId(projectId).isEmpty()) {
                throw new LibraryRequiredException();
            }
            throw new IssueViaLibraryException();
        }

        // 2. If clientName is provided, generate a UNIQUE named invitation
        if (clientName != null && !clientName.trim().isEmpty()) {
             String code = generateUniqueCode();
             
             // Create specific user linked to this code
             User user = new User();
             user.setUsername("client_" + code);
             user.setPassword("{noop}" + UUID.randomUUID().toString());
             user.setDisplayName(clientName);
             user.setRole("CLIENT");
             user.setSubscriptionType("FREE");
             user.setCreatedAt(LocalDateTime.now());
             user.setUpdatedAt(LocalDateTime.now());
             user = userRepository.save(user);
             
             // Add to project immediately
             ProjectMember member = new ProjectMember();
             member.setProjectId(projectId);
             member.setUserId(user.getId());
             member.setRole("CLIENT");
             projectMemberRepository.save(member);
             
             // Save Invitation
             ProjectInvitation invitation = new ProjectInvitation();
             invitation.setProjectId(projectId);
             invitation.setAccessCode(code);
             invitation.setType("CLIENT_NAMED");
             invitation.setRelatedUserId(user.getId());
             invitation.setCreatedBy(requesterId);
             invitation.setExpiresAt(freshExpiry());
             invitationRepository.save(invitation);
             
             return new Issued(code, invitation.getExpiresAt(), user.getId());
        }

        // 3. Standard Shared Code Logic (Generic)
        // Check for new "CLIENT_GENERIC" type
        Optional<ProjectInvitation> existingGeneric = earliest(projectId, "CLIENT_GENERIC");
        if (existingGeneric.isPresent()) {
            return reissue(existingGeneric.get());
        }
        
        // Check for legacy "CLIENT" type
        Optional<ProjectInvitation> existingLegacy = earliest(projectId, "CLIENT");
        if (existingLegacy.isPresent()) {
             return reissue(existingLegacy.get());
        }

        // Create new Generic Invitation
        String code = generateUniqueCode();
        String username = "client_template_" + code;
        User templateUser = new User();
        templateUser.setUsername(username);
        templateUser.setPassword("{noop}" + UUID.randomUUID().toString());
        templateUser.setDisplayName("客户(通用)");
        templateUser.setRole("CLIENT");
        templateUser.setSubscriptionType("FREE");
        templateUser.setCreatedAt(LocalDateTime.now());
        templateUser.setUpdatedAt(LocalDateTime.now());
        templateUser = userRepository.save(templateUser);
        
        ProjectInvitation invitation = new ProjectInvitation();
        invitation.setProjectId(projectId);
        invitation.setAccessCode(code);
        invitation.setType("CLIENT_GENERIC");
        invitation.setRelatedUserId(templateUser.getId());
        invitation.setCreatedBy(requesterId);
        invitation.setExpiresAt(freshExpiry());
        invitationRepository.save(invitation);

        // 通用码此前只建影子用户与 invitation 行，从不建成员行；而客户登录恒走
        // 「登录成 relatedUserId 这个影子用户」那一支（前端传 displayName=null），
        // 同样不建成员行。项目访问权全靠 project_member（hasReadPermission 只认成员行
        // 或项目所有者），于是律师把码发出去，客户登录提示成功、页面跳进工作台，
        // 之后每个文件接口都回 403——「登录成功」与「什么都打不开」并存。
        ensureClientMember(projectId, templateUser.getId());

        return new Issued(code, invitation.getExpiresAt(), templateUser.getId());
    }

    /** 重新签发同一行通用码：续期并取（必要时升级成长码）的码。 */
    private Issued reissue(ProjectInvitation invitation) {
        invitation.setExpiresAt(freshExpiry());
        String code = ensureLongCode(invitation);
        invitationRepository.save(invitation);
        return new Issued(code, invitation.getExpiresAt(), invitation.getRelatedUserId());
    }

    private static LocalDateTime freshExpiry() {
        return LocalDateTime.now().plusDays(VALID_DAYS);
    }

    /** 老码（本列上线前签的）没有 expiresAt：按签发时间推算；两者都缺就不设限。 */
    static LocalDateTime effectiveExpiry(ProjectInvitation invitation) {
        if (invitation.getExpiresAt() != null) return invitation.getExpiresAt();
        return invitation.getCreatedAt() == null ? null : invitation.getCreatedAt().plusDays(VALID_DAYS);
    }

    /** 把影子用户补成 CLIENT 成员；已经是成员就不动（重发访问码会走到这里）。 */
    private void ensureClientMember(Long projectId, Long userId) {
        if (projectMemberRepository.findByProjectIdAndUserId(projectId, userId).isPresent()) {
            return;
        }
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole("CLIENT");
        projectMemberRepository.save(member);
    }

    /**
     * 同项目同类型的最早一行。并发签发能插出两行通用码，取「最早」保证之后每次都命中同一行，
     * 不会今天用 A 明天用 B。
     */
    private Optional<ProjectInvitation> earliest(Long projectId, String type) {
        return invitationRepository.findByProjectIdAndType(projectId, type).stream()
                .min(java.util.Comparator.comparing(ProjectInvitation::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
    }

    private String ensureLongCode(ProjectInvitation invitation) {
        // 复用的是同一行邀请：律师重新发起邀请就是明示要它再次生效，
        // 否则曾被作废过的项目再邀请客户只会拿到一个不能用的码。
        if (invitation.getRevokedAt() != null) {
            invitation.setRevokedAt(null);
            invitationRepository.save(invitation);
        }
        // 作废客户时成员行会被删掉，重新发码就得把它补回来，否则码能用但没有权限。
        ensureClientMember(invitation.getProjectId(), invitation.getRelatedUserId());
        String existingCode = invitation.getAccessCode();
        if (existingCode.length() < 10) {
            String newCode = generateUniqueCode();
            invitation.setAccessCode(newCode);
            invitationRepository.save(invitation);
            return newCode;
        }
        return existingCode;
    }

    /**
     * 带 displayName 的客户登录：同一张码、同一个称呼**复用同一个客户用户**（dev-board#1050）。
     *
     * <p>此前每登录一次就新建一个用户并加一行成员——客户门户上了公网之后，这是一个能被
     * 无限刷出用户行的口子。现在用户名按 {@code client_inv{invitationId}_{称呼摘要}} 确定性
     * 生成，同名即同人；不同称呼的人数设上限 {@link #MAX_USERS_PER_INVITATION}。
     *
     * <p>复用到的用户若已不是成员，说明律师把这个人移出过案卷：拒绝，而不是悄悄把他加回来
     * （与「移出客户同时作废访问码」同一条纪律，这里作用在单个人身上）。
     */
    @Transactional
    public User createClientUser(ProjectInvitation invitation, String displayName) {
        Long projectId = invitation.getProjectId();
        String name = displayName.trim();
        String prefix = "client_inv" + invitation.getId() + "_";
        String username = prefix + nameDigest(name);

        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            User user = existing.get();
            if (projectMemberRepository.findByProjectIdAndUserId(projectId, user.getId()).isEmpty()) {
                throw new IllegalArgumentException(LangText.of("访问码已失效", "This access code is no longer valid"));
            }
            return user;
        }
        if (userRepository.countByUsernameStartingWith(prefix) >= MAX_USERS_PER_INVITATION) {
            throw new IllegalArgumentException(LangText.of(
                    "这个访问码登录的人数已达上限，请联系律师",
                    "Too many people have signed in with this access code; please contact your lawyer"));
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword("{noop}" + UUID.randomUUID().toString()); // No password
        user.setDisplayName(name);
        user.setRole("CLIENT");
        user.setSubscriptionType("FREE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        user = userRepository.save(user);

        // Add to project
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(user.getId());
        member.setRole("CLIENT");
        projectMemberRepository.save(member);
        
        return user;
    }

    /** 称呼的短摘要：username 列有长度限制，且称呼里可能有任意字符。 */
    private static String nameDigest(String name) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String generateUniqueCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        // 用 SecureRandom：该码是客户访问项目的唯一凭证，可预测的 java.util.Random 可被枚举/预测
        java.security.SecureRandom random = new java.security.SecureRandom();
        String code;
        do {
            sb.setLength(0);
            for (int i = 0; i < 20; i++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            code = sb.toString();
        } while (invitationRepository.findByAccessCode(code).isPresent());
        return code;
    }

    public ProjectInvitation validateCode(String code) {
        ProjectInvitation invitation = invitationRepository.findByAccessCode(code)
                .orElseThrow(() -> new IllegalArgumentException("访问码无效"));
        // 已作废的码必须在这里挡住：过了这一关 createClientUser 会无条件把持码人
        // 重新加成 CLIENT 成员，被移出的客户就自己回到项目里了。
        if (invitation.getRevokedAt() != null) {
            throw new IllegalArgumentException("访问码已失效");
        }
        LocalDateTime expiry = effectiveExpiry(invitation);
        if (expiry != null && expiry.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException(LangText.of(
                    "访问码已过期，请联系律师重新发送", "This access code has expired; please ask your lawyer for a new one"));
        }
        return invitation;
    }
}
