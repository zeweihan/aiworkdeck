package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.Tag;
import com.checkba.model.dto.ProjectFileBatchRequest;
import com.checkba.exception.UnauthorizedException;
import com.checkba.service.FileTagService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 项目文件管理控制器
 */
@RestController
@RequestMapping("/api/projects/{projectId}/files")
@RequiredArgsConstructor
public class ProjectFileController {

    private final ProjectFileService projectFileService;
    private final ProjectMemberService projectMemberService;
    private final FileTagService fileTagService;
    private final com.checkba.service.quota.StageQuotaService stageQuotaService;

    /**
     * 文件缓存区用量（前端顶部用量条的数据源）。
     * GET /api/projects/{projectId}/files/stage/usage?folderId=xxx
     *
     * 上限常量只在后端定义一处，前端不复制——改额度时不会出现两边不一致。
     *
     * folderId 是全局 id，必须校验归属：只验路径上的 projectId 的话，
     * 项目 A 的成员能拿自己的 projectId 去问项目 B 任意目录的文件数与总字节
     * （数字主键可枚举）。与本文件其他按 fileId 操作的接口同一道闸。
     */
    @GetMapping("/stage/usage")
    public Map<String, Object> stageUsage(
            @PathVariable Long projectId,
            @RequestParam(value = "folderId", required = false) Long folderId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileTreeAccess(projectId, userId);
        if (folderId != null) {
            checkFileInProject(folderId, projectId);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", stageQuotaService.usage(folderId));
        return result;
    }

    /**
     * 获取文件列表（指定父文件夹）
     * GET /api/projects/{projectId}/files?parentId=xxx
     * GET /api/projects/{projectId}/files?tree=true 获取完整文件树
     */
    @GetMapping
    public List<ProjectFile> getFiles(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) Boolean tree,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        
        checkFileTreeAccess(projectId, userId);

        // 如果请求完整文件树
        List<ProjectFile> files;
        if (Boolean.TRUE.equals(tree)) {
            files = projectFileService.getFileTree(projectId);
        } else {
            // 否则返回指定父文件夹下的文件
            files = projectFileService.getFilesByParent(projectId, parentId);
        }
        
        // Populate tags
        if (!files.isEmpty()) {
            Map<Long, List<Tag>> tagsMap = fileTagService.getTagsByFileIds(files.stream().map(ProjectFile::getId).collect(Collectors.toList()));
            files.forEach(f -> f.setTags(tagsMap.getOrDefault(f.getId(), Collections.emptyList())));
        }
        
        return files;
    }
    
    private void checkFileTreeAccess(Long projectId, Long userId) {
         if (!projectMemberService.hasReadPermission(projectId, userId)) {
              throw new IllegalArgumentException(com.checkba.service.LangText.of("无权访问该项目", "You do not have access to this project"));
         }
         if (projectMemberService.isClient(projectId, userId)) {
             throw new IllegalArgumentException(com.checkba.service.LangText.of("客户无权访问资源管理器", "Clients do not have access to the Explorer"));
         }
    }

    /**
     * 写操作的权限闸。checkFileTreeAccess 只要求读权限，而 READ_ONLY 等非
     * ADMIN/PARTICIPANT 角色本就只该浏览：此前增删改（含彻底删除）全部只过读权限闸，
     * 只读成员可销毁整个项目的文档。语义与 extractArchive、CloudController.requireWriteMember 一致。
     */
    private void checkFileWriteAccess(Long projectId, Long userId) {
        checkFileTreeAccess(projectId, userId);
        if (!projectMemberService.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException(com.checkba.service.LangText.of("无权修改该项目的文件", "You do not have permission to modify files in this project"));
        }
    }

    /**
     * 校验目标文件确实属于当前 projectId，防止越权操作他人项目的文件（IDOR）。
     * checkFileTreeAccess 只校验用户是 URL 中 projectId 的成员；按全局 fileId 操作的接口
     * 若不校验归属，成员可传入他人项目的 fileId 进行读/写/删。
     */
    private void checkFileInProject(Long fileId, Long projectId) {
        ProjectFile file = projectFileService.getFile(fileId); // 文件不存在会抛异常
        if (!projectId.equals(file.getProjectId())) {
            throw new IllegalArgumentException(com.checkba.service.LangText.of("文件不属于该项目", "This file does not belong to this project"));
        }
    }

    /**
     * 校验创建/移动时指定的父目录：必须是本项目内未删除的文件夹（parentId 为空表示根目录）。
     * 此前 parentId 完全不校验：前端对话框/拖拽目标缓存的目录 id 若在提交前被另一端软删除，
     * 新节点仍会以 isDeleted=false 挂到已删除的父目录下，接口返回 200 看着成功，
     * 但 getFileTree/getFilesByParent 只取 isDeleted=false 的行，永远拼不出它的路径，
     * 节点在所有树视图里凭空消失。跨项目 parentId 同理。
     */
    private void checkParentFolder(Long parentId, Long projectId) {
        if (parentId == null) {
            return;
        }
        ProjectFile parent = projectFileService.getFile(parentId); // 不存在会抛异常
        if (!projectId.equals(parent.getProjectId())
                || !Boolean.TRUE.equals(parent.getIsFolder())
                || Boolean.TRUE.equals(parent.getIsDeleted())) {
            throw new IllegalArgumentException(com.checkba.service.LangText.of(
                    "目标文件夹不存在或已被删除", "The target folder does not exist or has been deleted"));
        }
    }

    /**
     * 创建文件夹
     * POST /api/projects/{projectId}/files/folder
     */
    @PostMapping("/folder")
    public ProjectFile createFolder(
            @PathVariable Long projectId,
            @RequestBody CreateFolderRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        checkParentFolder(request.getParentId(), projectId);
        return projectFileService.createFolder(projectId, request.getParentId(), request.getName(), userId);
    }

    /**
     * 压缩包条目列表（预览）
     * GET /api/projects/{projectId}/files/{fileId}/archive/entries
     */
    @GetMapping("/{fileId}/archive/entries")
    public Map<String, Object> listArchiveEntries(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileTreeAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        List<Map<String, Object>> entries = projectFileService.listArchiveEntries(fileId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("entries", entries);
        resp.put("total", entries.size());
        return resp;
    }

    /**
     * 解压压缩包到其所在目录下的新文件夹
     * POST /api/projects/{projectId}/files/{fileId}/archive/extract
     */
    @PostMapping("/{fileId}/archive/extract")
    public ProjectFile extractArchive(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileTreeAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        // 解压会写入项目资源，要求写权限（读权限成员只能浏览条目）
        if (!projectMemberService.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException(com.checkba.service.LangText.of("无权在该项目中解压文件", "You do not have permission to extract files in this project"));
        }
        return projectFileService.extractArchive(projectId, fileId, userId);
    }

    /**
     * 创建文件
     * POST /api/projects/{projectId}/files/file
     */
    @PostMapping("/file")
    public ProjectFile createFile(
            @PathVariable Long projectId,
            @RequestBody CreateFileRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        checkParentFolder(request.getParentId(), projectId);
        // 存储键一律由服务端按 projectId + 目录结构生成：请求体里的 filePath 曾被原样落库，
        // 可指向他人项目的文件，再借这条记录下载/覆盖对方的文档
        return projectFileService.createFile(
                projectId,
                request.getParentId(),
                request.getName(),
                request.getFileType(),
                request.getFileSize(),
                null,
                request.getWpsFileId(),
                userId
        );
    }

    /**
     * 重命名文件或文件夹
     * PUT /api/projects/{projectId}/files/{fileId}/rename
     */
    @PutMapping("/{fileId}/rename")
    public ProjectFile rename(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestBody RenameRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        return projectFileService.rename(fileId, request.getName(), userId);
    }

    /**
     * 删除文件或文件夹
     * DELETE /api/projects/{projectId}/files/{fileId}
     */
    @DeleteMapping("/{fileId}")
    public Map<String, Object> delete(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        projectFileService.delete(fileId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", com.checkba.service.LangText.of("删除成功", "Deleted successfully"));
        return result;
    }

    /**
     * 批量删除文件或文件夹（支持文件夹递归删除）
     * POST /api/projects/{projectId}/files/batch/delete
     */
    @PostMapping("/batch/delete")
    public Map<String, Object> batchDelete(
            @PathVariable Long projectId,
            @RequestBody ProjectFileBatchRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        projectFileService.batchDelete(projectId, request, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", com.checkba.service.LangText.of("删除成功", "Deleted successfully"));
        result.put("data", new HashMap<>());
        return result;
    }

    /**
     * 批量移动文件或文件夹（支持文件夹递归移动：同步更新子文件 filePath 并移动物理文件）
     * POST /api/projects/{projectId}/files/batch/move
     */
    @PostMapping("/batch/move")
    public Map<String, Object> batchMove(
            @PathVariable Long projectId,
            @RequestBody ProjectFileBatchRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        List<ProjectFile> moved = projectFileService.batchMove(projectId, request, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", com.checkba.service.LangText.of("移动成功", "Moved successfully"));
        Map<String, Object> data = new HashMap<>();
        data.put("files", moved);
        result.put("data", data);
        return result;
    }

    /**
     * 批量复制文件或文件夹（支持文件夹递归复制：同步复制物理文件）
     * POST /api/projects/{projectId}/files/batch/copy
     */
    @PostMapping("/batch/copy")
    public Map<String, Object> batchCopy(
            @PathVariable Long projectId,
            @RequestBody ProjectFileBatchRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        List<ProjectFile> created = projectFileService.batchCopy(projectId, request, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", com.checkba.service.LangText.of("复制成功", "Copied successfully"));
        Map<String, Object> data = new HashMap<>();
        data.put("files", created);
        result.put("data", data);
        return result;
    }

    /**
     * 移动文件或文件夹（拖拽排序）
     * PUT /api/projects/{projectId}/files/{fileId}/move
     */
    @PutMapping("/{fileId}/move")
    public ProjectFile move(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestBody MoveRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        checkParentFolder(request.getParentId(), projectId);
        return projectFileService.move(fileId, request.getParentId(), request.getSortOrder(), userId);
    }

    /**
     * 获取文件详情
     * GET /api/projects/{projectId}/files/{fileId}
     */
    @GetMapping("/{fileId}")
    public ProjectFile getFile(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileTreeAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        return projectFileService.getFile(fileId);
    }

    /**
     * 获取回收站文件列表
     * GET /api/projects/{projectId}/files/recycle-bin
     */
    @GetMapping("/recycle-bin")
    public List<ProjectFile> getRecycleBinFiles(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileTreeAccess(projectId, userId);
        return projectFileService.getRecycleBinFiles(projectId);
    }
    
    /**
     * 还原文件或文件夹
     * POST /api/projects/{projectId}/files/{fileId}/restore
     */
    @PostMapping("/{fileId}/restore")
    public Map<String, Object> restoreFile(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        projectFileService.restore(fileId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", com.checkba.service.LangText.of("还原成功", "Restored successfully"));
        return result;
    }
    
    /**
     * 彻底删除文件或文件夹
     * DELETE /api/projects/{projectId}/files/{fileId}/permanent
     */
    @DeleteMapping("/{fileId}/permanent")
    public Map<String, Object> permDelete(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        projectFileService.permDelete(fileId, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", com.checkba.service.LangText.of("彻底删除成功", "Permanently deleted successfully"));
        return result;
    }

    private Long getUserIdFromSession(String sessionId) {
        return AuthController.getUserIdFromSession(sessionId);
    }

    static class CreateFolderRequest {
        private Long parentId;
        private String name;

        public Long getParentId() { return parentId; }
        public void setParentId(Long parentId) { this.parentId = parentId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    static class CreateFileRequest {
        private Long parentId;
        private String name;
        private String fileType;
        private Long fileSize;
        private String wpsFileId;

        public Long getParentId() { return parentId; }
        public void setParentId(Long parentId) { this.parentId = parentId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
        public Long getFileSize() { return fileSize; }
        public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
        public String getWpsFileId() { return wpsFileId; }
        public void setWpsFileId(String wpsFileId) { this.wpsFileId = wpsFileId; }
    }

    static class RenameRequest {
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    static class MoveRequest {
        private Long parentId;
        private Integer sortOrder;

        public Long getParentId() { return parentId; }
        public void setParentId(Long parentId) { this.parentId = parentId; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    }
    
    @Data
    static class AddTagRequest {
        private Long tagId;
    }

    /**
     * 给文件打标签
     * POST /api/projects/{projectId}/files/{fileId}/tags
     */
    @PostMapping("/{fileId}/tags")
    public void addTagToFile(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @RequestBody AddTagRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        fileTagService.addTagToFile(fileId, request.getTagId(), userId);
    }

    /**
     * 移除文件标签
     * DELETE /api/projects/{projectId}/files/{fileId}/tags/{tagId}
     */
    @DeleteMapping("/{fileId}/tags/{tagId}")
    public void removeTagFromFile(
            @PathVariable Long projectId,
            @PathVariable Long fileId,
            @PathVariable Long tagId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        checkFileWriteAccess(projectId, userId);
        checkFileInProject(fileId, projectId);
        fileTagService.removeTagFromFile(fileId, tagId);
    }
}

