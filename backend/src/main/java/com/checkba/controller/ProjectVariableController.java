package com.checkba.controller;

import com.checkba.model.entity.ProjectVariable;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectVariableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/variables")
public class ProjectVariableController {

    @Autowired
    private ProjectVariableService service;

    @Autowired
    private ProjectMemberService projectMemberService;

    // 越权校验：此前 getVariables/deleteVariable 无鉴权、saveVariable 允许匿名。
    private Long requireMember(String sessionId, Long projectId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("请先登录");
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException("无权访问该资源");
        }
        return userId;
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<ProjectVariable>> getVariables(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, projectId);
        return ResponseEntity.ok(service.getVariablesByProject(projectId));
    }

    @PostMapping
    public ResponseEntity<?> saveVariable(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody ProjectVariable variable
    ) {
        Long userId = requireMember(sessionId, variable.getProjectId());
        variable.setCreatorId(userId);
        if (variable.getCreatorName() == null) {
            String username = AuthController.getUsernameFromSession(sessionId);
            variable.setCreatorName(username != null ? username : "Unknown");
        }
        return ResponseEntity.ok(service.createOrUpdateVariable(variable));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVariable(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, service.getProjectIdById(id));
        service.deleteVariable(id);
        return ResponseEntity.ok().build();
    }
}
