package com.checkba.service;

import com.checkba.model.entity.ProjectInvitation;
import com.checkba.model.entity.ProjectMember;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectInvitationRepository;
import com.checkba.repository.ProjectMemberRepository;
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

    @Transactional
    public String inviteClient(Long projectId, Long requesterId, String clientName) {
        // 1. Check permissions (Allow Admin and Participant)
        if (!projectMemberService.hasWritePermission(projectId, requesterId)) {
             throw new IllegalArgumentException("权限不足：只有管理员或参与者可以邀请客户");
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
             invitationRepository.save(invitation);
             
             return code;
        }

        // 3. Standard Shared Code Logic (Generic)
        // Check for new "CLIENT_GENERIC" type
        Optional<ProjectInvitation> existingGeneric = earliest(projectId, "CLIENT_GENERIC");
        if (existingGeneric.isPresent()) {
            return ensureLongCode(existingGeneric.get());
        }
        
        // Check for legacy "CLIENT" type
        Optional<ProjectInvitation> existingLegacy = earliest(projectId, "CLIENT");
        if (existingLegacy.isPresent()) {
             return ensureLongCode(existingLegacy.get());
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
        invitationRepository.save(invitation);

        // 通用码此前只建影子用户与 invitation 行，从不建成员行；而客户登录恒走
        // 「登录成 relatedUserId 这个影子用户」那一支（前端传 displayName=null），
        // 同样不建成员行。项目访问权全靠 project_member（hasReadPermission 只认成员行
        // 或项目所有者），于是律师把码发出去，客户登录提示成功、页面跳进工作台，
        // 之后每个文件接口都回 403——「登录成功」与「什么都打不开」并存。
        ensureClientMember(projectId, templateUser.getId());

        return code;
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

    @Transactional
    public User createClientUser(Long projectId, String displayName, String accessCode) {
        // Create a unique user for this client login
        String username = "client_" + accessCode + "_" + UUID.randomUUID().toString().substring(0, 8);
        
        User user = new User();
        user.setUsername(username);
        user.setPassword("{noop}" + UUID.randomUUID().toString()); // No password
        user.setDisplayName(displayName);
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
        return invitation;
    }
}
