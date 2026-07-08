package com.checkba.controller;

import com.checkba.model.entity.FileVariable;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.FileVariableService;
import com.checkba.service.ProjectMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/file-variables")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FileVariableController {

    private final FileVariableService fileVariableService;
    private final ProjectMemberService projectMemberService;
    private final ProjectFileRepository projectFileRepository;

    // 越权校验：文件变量接口此前完全无鉴权。fileId → 所属 projectId → 成员资格。
    private void requireFileMember(String sessionId, Long fileId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        Long projectId = fileId == null ? null
                : projectFileRepository.findById(fileId).map(ProjectFile::getProjectId).orElse(null);
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException("无权访问该资源");
        }
    }

    @GetMapping
    public Map<String, Object> getVariables(
            @RequestParam Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireFileMember(sessionId, fileId);
        List<FileVariable> variables = fileVariableService.getVariables(fileId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", variables);
        return result;
    }

    @PostMapping
    public Map<String, Object> createOrUpdateVariable(
            @RequestBody FileVariable variable,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireFileMember(sessionId, variable.getFileId());
        FileVariable saved = fileVariableService.createOrUpdateVariable(variable);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", saved);
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteVariable(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireFileMember(sessionId, fileVariableService.getFileIdById(id));
        fileVariableService.deleteVariable(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "Deleted");
        return result;
    }
}
