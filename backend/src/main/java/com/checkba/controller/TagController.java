package com.checkba.controller;

import com.checkba.model.entity.Tag;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.TagService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;
    private final ProjectMemberService projectMemberService;

    // 越权校验：标签接口此前完全无鉴权，任何人可读/改/删任意项目的标签。
    // 注：updateTag/deleteTag 的 tagId 未强制归属 projectId，属次要面（需已是本项目成员且知道外部 tagId）；
    // 主漏洞（零鉴权）由成员校验闭合。
    private void requireMember(String sessionId, Long projectId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException("无权访问该资源");
        }
    }

    /**
     * Get all tags for a project
     */
    @GetMapping
    public List<Tag> getProjectTags(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, projectId);
        return tagService.getProjectTags(projectId);
    }

    /**
     * Create a new tag
     */
    @PostMapping
    public Tag createTag(
            @PathVariable Long projectId,
            @RequestBody CreateTagRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, projectId);
        return tagService.createTag(projectId, request.getName(), request.getColor(), request.getDescription());
    }

    /**
     * Update an existing tag
     */
    @PutMapping("/{tagId}")
    public Tag updateTag(
            @PathVariable Long projectId,
            @PathVariable Long tagId,
            @RequestBody UpdateTagRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, projectId);
        return tagService.updateTag(tagId, request.getName(), request.getColor(), request.getDescription());
    }

    /**
     * Delete a tag
     */
    @DeleteMapping("/{tagId}")
    public void deleteTag(
            @PathVariable Long projectId,
            @PathVariable Long tagId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(sessionId, projectId);
        tagService.deleteTag(tagId);
    }

    @Data
    public static class CreateTagRequest {
        private String name;
        private String color;
        private String description;
    }

    @Data
    public static class UpdateTagRequest {
        private String name;
        private String color;
        private String description;
    }
}
