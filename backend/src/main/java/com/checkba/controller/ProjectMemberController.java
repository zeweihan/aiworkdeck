package com.checkba.controller;

import com.checkba.model.entity.ProjectMember;
import com.checkba.model.entity.User;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ClientInvitationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;
    private final ClientInvitationService clientInvitationService;

    @GetMapping("/{projectId}/members")
    public Map<String, Object> getMembers(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        
        // 越权校验：此前无成员校验，可枚举任意项目的成员名单/用户名
        Long callerId = AuthController.getUserIdFromSession(sessionId);
        if (callerId == null || !projectMemberService.hasReadPermission(projectId, callerId)) {
            throw new IllegalArgumentException("无权访问该项目成员");
        }

        // Fetch project owner (implicitly an ADMIN member)
        com.checkba.model.entity.User owner = projectMemberService.getProjectOwner(projectId);
        
        List<ProjectMember> members = projectMemberService.getMembers(projectId);
        List<User> users = projectMemberService.getMemberUsers(projectId);
        
        // Combine data
        List<Map<String, Object>> resultList = members.stream().map(member -> {
            User user = users.stream().filter(u -> u.getId().equals(member.getUserId())).findFirst().orElse(null);
            Map<String, Object> map = new HashMap<>();
            map.put("id", member.getId());
            map.put("userId", member.getUserId());
            map.put("role", member.getRole());
            map.put("joinedAt", member.getJoinedAt());
            if (user != null) {
                map.put("username", user.getUsername());
                map.put("displayName", user.getDisplayName());
                map.put("avatarUrl", user.getAvatarUrl());
            }
            return map;
        }).collect(Collectors.toList());

        // Add owner if not present
        if (owner != null) {
            boolean ownerExists = resultList.stream().anyMatch(m -> m.get("userId").equals(owner.getId()));
            if (!ownerExists) {
                Map<String, Object> ownerMap = new HashMap<>();
                ownerMap.put("id", 0L); // Virtual ID
                ownerMap.put("userId", owner.getId());
                ownerMap.put("role", "ADMIN");
                ownerMap.put("joinedAt", null);
                ownerMap.put("username", owner.getUsername());
                ownerMap.put("displayName", owner.getDisplayName());
                ownerMap.put("avatarUrl", owner.getAvatarUrl());
                resultList.add(0, ownerMap); // Add to top
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", resultList);
        return result;
    }

    @PostMapping("/{projectId}/members")
    public Map<String, Object> addMember(
            @PathVariable Long projectId,
            @RequestBody AddMemberRequest request,
            @RequestHeader(value = "X-Session-Id") String sessionId) {
        
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }

        try {
            projectMemberService.addMember(projectId, request.getUsername(), request.getRole(), userId);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("message", "添加成功");
            return result;
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
    }

    @PostMapping("/{projectId}/invite/client")
    public Map<String, Object> inviteClient(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id") String sessionId) {
        
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }

        String clientName = (body != null) ? body.get("clientName") : null;

        try {
            String code = clientInvitationService.inviteClient(projectId, userId, clientName);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("message", "邀请码生成成功");
            result.put("data", Map.of("accessCode", code));
            return result;
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
    }

    @DeleteMapping("/{projectId}/members/{userIdToRemove}")
    public Map<String, Object> removeMember(
            @PathVariable Long projectId,
            @PathVariable Long userIdToRemove,
            @RequestHeader(value = "X-Session-Id") String sessionId) {
        
        Long requesterId = AuthController.getUserIdFromSession(sessionId);
        if (requesterId == null) {
            throw new IllegalArgumentException("未登录");
        }

        try {
            projectMemberService.removeMember(projectId, userIdToRemove, requesterId);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("message", "移除成功");
            return result;
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 1);
            result.put("message", e.getMessage());
            return result;
        }
    }

    static class AddMemberRequest {
        private String username;
        private String role; // ADMIN, PARTICIPANT, READ_ONLY

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }
}
