// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.exception.UnauthorizedException;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectFileReview;
import com.checkba.model.entity.ProjectFileReviewComment;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.review.FileReviewService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 计划审阅 REST（dev-board#1022，规格 §4.2 / §4.5）：
 * {@code /api/projects/{projectId}/files/{fileId}/review}。
 *
 * <p>鉴权与 {@link ProjectFileController} 同口径（那几个闸在那边是私有方法，这里照抄一份，语义不改）：
 * 读走 checkFileTreeAccess、写走 checkFileWriteAccess，一律再过 checkFileInProject。
 * 没有 open 记录时的写操作（服务层抛 {@link FileReviewService.NoOpenReviewException}）本控制器局部映射 409；
 * 其它 IllegalStateException 照全局处理器走。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/files/{fileId}/review")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class FileReviewController {

    private final FileReviewService fileReviewService;
    private final ProjectFileService projectFileService;
    private final ProjectMemberService projectMemberService;

    @PostMapping
    public Map<String, Object> open(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestBody(required = false) OpenRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        checkFileWriteAccess(projectId, userId);
        ProjectFile file = checkFileInProject(fileId, projectId);
        if (!isReviewableText(file)) {
            throw new IllegalArgumentException(LangText.of(
                    "只有文本文件（md / markdown / txt）可以审阅", "Only text files (md / markdown / txt) can be reviewed"));
        }
        OpenRequest req = request != null ? request : new OpenRequest();
        ProjectFileReview r = fileReviewService.open(projectId, fileId, req.getConversationId(),
                req.getArtifactId(), req.getBaselineText(), userId);
        return snapshot(r, fileReviewService.comments(r.getId()));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> current(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        checkFileTreeAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        Optional<ProjectFileReview> r = fileReviewService.current(fileId);
        if (r.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(snapshot(r.get(), fileReviewService.comments(r.get().getId())));
    }

    @PostMapping("/comments")
    public ResponseEntity<Map<String, Object>> addComment(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestBody(required = false) CommentRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        if (request == null || !StringUtils.hasText(request.getBody())) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", 1);
            err.put("message", LangText.of("批注内容不能为空", "Comment body must not be empty"));
            return ResponseEntity.badRequest().body(err);
        }
        ProjectFileReviewComment c = fileReviewService.addComment(fileId, request.getFromLine(),
                request.getToLine(), request.getQuotedText(), request.getBody());
        return ResponseEntity.ok(commentView(c));
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @PathVariable Long commentId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        fileReviewService.deleteComment(fileId, commentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/submit")
    public Map<String, Object> submit(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        ProjectFileReview r = fileReviewService.submit(fileId);
        return snapshot(r, fileReviewService.comments(r.getId()));
    }

    @PostMapping("/discard")
    public Map<String, Object> discard(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        ProjectFileReview r = fileReviewService.discard(projectId, fileId, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("review", reviewView(r));
        return result;
    }

    @ExceptionHandler(FileReviewService.NoOpenReviewException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(FileReviewService.NoOpenReviewException e) {
        log.info("计划审阅状态冲突: {}", e.getMessage());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", 1);
        err.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(err);
    }

    // ==================== 视图 ====================

    private static Map<String, Object> snapshot(ProjectFileReview r, List<ProjectFileReviewComment> comments) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("review", reviewView(r));
        result.put("comments", comments.stream().map(FileReviewController::commentView).toList());
        return result;
    }

    private static Map<String, Object> reviewView(ProjectFileReview r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("fileId", r.getFileId());
        m.put("conversationId", r.getConversationId());
        m.put("artifactId", r.getArtifactId());
        m.put("status", r.getStatus());
        m.put("baselineText", r.getBaselineText());
        m.put("createdAt", r.getCreatedAt());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    private static Map<String, Object> commentView(ProjectFileReviewComment c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("fromLine", c.getFromLine());
        m.put("toLine", c.getToLine());
        m.put("quotedText", c.getQuotedText());
        m.put("body", c.getBody());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    // ==================== 鉴权（照抄 ProjectFileController，语义不改） ====================

    private Long requireUser(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        return userId;
    }

    private void checkFileTreeAccess(Long projectId, Long userId) {
        if (!projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该项目", "You do not have access to this project"));
        }
        if (projectMemberService.isClient(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("客户无权访问资源管理器", "Clients do not have access to the Explorer"));
        }
    }

    private void checkFileWriteAccess(Long projectId, Long userId) {
        checkFileTreeAccess(projectId, userId);
        if (!projectMemberService.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权修改该项目的文件", "You do not have permission to modify files in this project"));
        }
    }

    private ProjectFile checkFileInProject(Long fileId, Long projectId) {
        ProjectFile file = projectFileService.getFile(fileId); // 文件不存在会抛异常
        if (!projectId.equals(file.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("文件不属于该项目", "This file does not belong to this project"));
        }
        return file;
    }

    /** 审阅按行比对纯文本，只接 md / markdown / txt（fileType 为空时看文件名后缀）。 */
    private static boolean isReviewableText(ProjectFile file) {
        String t = file.getFileType();
        if (!StringUtils.hasText(t)) {
            String name = file.getName() == null ? "" : file.getName();
            int dot = name.lastIndexOf('.');
            t = dot >= 0 ? name.substring(dot + 1) : "";
        }
        t = t.trim().toLowerCase(java.util.Locale.ROOT);
        return t.equals("md") || t.equals("markdown") || t.equals("txt");
    }

    @Data
    static class OpenRequest {
        private String conversationId;
        private String artifactId;
        private String baselineText;
    }

    @Data
    static class CommentRequest {
        private Integer fromLine;
        private Integer toLine;
        private String quotedText;
        private String body;
    }
}
