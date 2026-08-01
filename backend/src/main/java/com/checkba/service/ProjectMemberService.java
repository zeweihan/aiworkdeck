package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectMember;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectInvitationRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectMemberService {

    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectInvitationRepository invitationRepository;

    public List<ProjectMember> getProjectMembers(Long projectId) {
        return projectMemberRepository.findByProjectId(projectId);
    }

    /**
     * Get member details (with User info populated if possible, but here we return entities)
     * Usually we need a DTO to return User info.
     */
    public List<ProjectMember> getMembers(Long projectId) {
        return projectMemberRepository.findByProjectId(projectId);
    }
    
    // Helper to get User objects for members
    public List<User> getMemberUsers(Long projectId) {
        List<Long> userIds = projectMemberRepository.findByProjectId(projectId).stream()
                .map(ProjectMember::getUserId)
                .collect(Collectors.toList());
        return userRepository.findAllById(userIds);
    }

    public User getProjectOwner(Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project != null) {
            return userRepository.findById(project.getUserId()).orElse(null);
        }
        return null;
    }

    @Transactional
    public void addMember(Long projectId, String username, String role, Long requesterId) {
        checkAdminPermission(projectId, requesterId);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));

        if (projectMemberRepository.findByProjectIdAndUserId(projectId, user.getId()).isPresent()) {
            throw new IllegalArgumentException("用户已在项目中");
        }

        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(user.getId());
        member.setRole(role);
        projectMemberRepository.save(member);
    }

    @Transactional
    public void removeMember(Long projectId, Long userIdToRemove, Long requesterId) {
        if (userIdToRemove.equals(requesterId)) {
             throw new IllegalArgumentException("无法将自己移出项目");
        }

        String requesterRole = getMemberRole(projectId, requesterId);
        String targetRole = getMemberRole(projectId, userIdToRemove);

        if (requesterRole == null) {
            throw new IllegalArgumentException("无权操作");
        }

        boolean allowed = false;
        if ("OWNER".equals(requesterRole)) {
            allowed = true;
        } else if ("ADMIN".equals(requesterRole)) {
            if (!"OWNER".equals(targetRole)) {
                allowed = true;
            }
        } else if ("PARTICIPANT".equals(requesterRole)) {
            if ("READ_ONLY".equals(targetRole) || "CLIENT".equals(targetRole) || "CLIENT_NAMED".equals(targetRole) || "CLIENT_GENERIC".equals(targetRole)) {
                allowed = true;
            }
        }

        if (!allowed) {
            throw new IllegalArgumentException("权限不足：您无法移除该角色的成员");
        }

        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userIdToRemove)
                .orElseThrow(() -> new IllegalArgumentException("成员不存在"));

        projectMemberRepository.delete(member);

        // 移出客户只删成员行不算收回权限：访问码没有有效期，持码人再登一次
        // 就会被重新加成 CLIENT 成员，所以同时把他名下的访问码作废掉。
        if (targetRole != null && targetRole.startsWith("CLIENT")) {
            invitationRepository.findByProjectIdAndRelatedUserId(projectId, userIdToRemove)
                    .ifPresent(invitation -> {
                        invitation.setRevokedAt(LocalDateTime.now());
                        invitationRepository.save(invitation);
                    });
        }
    }

    private String getMemberRole(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        
        if (project.getUserId().equals(userId)) {
            return "OWNER";
        }
        
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(ProjectMember::getRole)
                .orElse(null);
    }

    public void checkAdminPermission(Long projectId, Long userId) {
        // Check if user is creator
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        
        if (project.getUserId().equals(userId)) {
            return; 
        }

        // Check if user is ADMIN member
        Optional<ProjectMember> memberOpt = projectMemberRepository.findByProjectIdAndUserId(projectId, userId);
        if (memberOpt.isPresent() && "ADMIN".equals(memberOpt.get().getRole())) {
            return;
        }

        throw new IllegalArgumentException("权限不足：只有管理员可以执行此操作");
    }

    public boolean hasReadPermission(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) return false;
        if (project.getUserId().equals(userId)) return true;
        
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId).isPresent();
    }
    
    public boolean hasWritePermission(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) return false;
        if (project.getUserId().equals(userId)) return true;

        Optional<ProjectMember> memberOpt = projectMemberRepository.findByProjectIdAndUserId(projectId, userId);
        if (memberOpt.isEmpty()) return false;
        
        String role = memberOpt.get().getRole();
        return "ADMIN".equals(role) || "PARTICIPANT".equals(role);
    }

    public boolean isClient(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project != null && project.getUserId().equals(userId)) return false; // Owner is not client

        Optional<ProjectMember> memberOpt = projectMemberRepository.findByProjectIdAndUserId(projectId, userId);
        if (memberOpt.isEmpty()) return false;
        
        String role = memberOpt.get().getRole();
        return "CLIENT".equals(role) || "CLIENT_NAMED".equals(role) || "CLIENT_GENERIC".equals(role);
    }
}
