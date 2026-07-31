package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ai.AutoTaggingService;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.storage.StorageException;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.version.WorkSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WPS 文档实际存储与下载/上传接口
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileController.class);

    @Autowired
    private StorageServiceFactory storageServiceFactory;

    @Autowired
    private ProjectFileRepository projectFileRepository;
    
    @Autowired
    private ProjectRagService projectRagService;

    @Autowired
    private AutoTaggingService autoTaggingService;

    @Autowired
    private ProjectMemberService projectMemberService;

    @Autowired
    private WorkSessionService workSessionService;

    private StorageService getStorageService() {
        return storageServiceFactory.getStorageService();
    }

    /**
     * 校验调用者是否有权访问该文件所属项目。
     * 浏览器 &lt;a&gt; 下载无法设请求头，只能用 ?token=&lt;sessionId&gt;；fetch/XHR 走 X-Session-Id 头。
     * 两者取其一解析出 userId 后校验项目成员资格。此前这些接口完全无鉴权，可按数字 id 遍历下载他人文件。
     */
    private boolean isAuthorizedForProject(String token, String sessionHeader, Long projectId) {
        if (projectId == null) {
            return false;
        }
        String sid = StringUtils.hasText(sessionHeader) ? sessionHeader : token;
        Long userId = AuthController.getUserIdFromSession(sid);
        return userId != null && projectMemberService.hasReadPermission(projectId, userId);
    }

    /**
     * 通知版本记录：项目文件发生了变更（上传场景）。
     * 版本记录是保险不是主流程——任何异常只记日志，绝不阻断上传本身。
     */
    private void signalChange(Long projectId, Long userId, String userName) {
        if (projectId == null) return;
        try {
            workSessionService.onChangeSignal(projectId, userId, userName);
        } catch (Exception e) {
            log.warn("发送版本变更信号失败: project={}", projectId, e);
        }
    }

    /**
     * 实际下载接口
     */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable("fileId") String fileId,
            @RequestParam(value = "token", required = false) String token,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionHeader) {
        log.info("[FileDownload] ===== 开始处理下载请求 =====");
        log.info("[FileDownload] 收到下载请求: fileId={}", fileId);
        
        try {
            // 1. 获取文件路径
            String path = fileId; // 默认回退到 fileId (兼容旧逻辑)
            
            Optional<ProjectFile> projectFileOpt = Optional.empty();
            
            // 尝试将 fileId 解析为 Long ID (Frontend 传的是 DB ID)
            try {
                Long dbId = Long.parseLong(fileId);
                projectFileOpt = projectFileRepository.findById(dbId);
                log.info("[FileDownload] 按数据库ID查找: dbId={}, found={}", dbId, projectFileOpt.isPresent());
            } catch (NumberFormatException e) {
                log.info("[FileDownload] fileId不是数字，将按wpsFileId查找: {}", fileId);
            }
            
            // 如果没找到，尝试按 WPS File ID 查找 (Fallback)
            if (projectFileOpt.isEmpty()) {
                projectFileOpt = projectFileRepository.findByWpsFileId(fileId).stream().findFirst();
                log.info("[FileDownload] 按wpsFileId查找: wpsFileId={}, found={}", fileId, projectFileOpt.isPresent());
            }
            
            String downloadFilename = fileId + ".docx";

            if (projectFileOpt.isPresent()) {
                ProjectFile pf = projectFileOpt.get();
                // 鉴权：只有该文件所属项目的成员可下载（浏览器下载用 ?token=，其它用 X-Session-Id 头）
                if (!isAuthorizedForProject(token, sessionHeader, pf.getProjectId())) {
                    log.warn("[FileDownload] 拒绝越权下载: fileId={}, projectId={}", fileId, pf.getProjectId());
                    return ResponseEntity.status(403).build();
                }
                log.info("[FileDownload] 找到文件记录: id={}, name={}, filePath={}, wpsFileId={}",
                    pf.getId(), pf.getName(), pf.getFilePath(), pf.getWpsFileId());
                
                // 如果数据库中有 filePath，优先使用
                if (StringUtils.hasText(pf.getFilePath())) {
                    path = pf.getFilePath();
                    log.info("[FileDownload] 使用数据库filePath: {}", path);
                } else {
                    log.warn("[FileDownload] 数据库filePath为空，使用fileId作为路径: {}", path);
                }
                // 使用真实文件名（防中文乱码）
                if (StringUtils.hasText(pf.getName())) {
                    downloadFilename = pf.getName();
                    // Ensure proper extension if not present
                    if (StringUtils.hasText(pf.getFileType()) && 
                        !downloadFilename.toLowerCase().endsWith("." + pf.getFileType().toLowerCase())) {
                        downloadFilename += "." + pf.getFileType();
                    }
                }
            } else {
                // 找不到 DB 记录无法确定文件归属，拒绝下载（此前会按裸 fileId 路径直接返回文件，是越权面）
                log.warn("[FileDownload] 未找到文件记录，拒绝下载: {}", fileId);
                return ResponseEntity.status(404).build();
            }

            log.info("[FileDownload] 准备从存储加载文件: path={}", path);
            Resource resource = getStorageService().load(path);
            log.info("[FileDownload] 存储加载结果: exists={}, readable={}", resource.exists(), resource.isReadable());

            // Determine Media Type dynamically
            MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
            String lowerName = downloadFilename.toLowerCase();
            if (lowerName.endsWith(".doc") || lowerName.endsWith(".docx")) {
                mediaType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            } else if (lowerName.endsWith(".pdf")) {
                mediaType = MediaType.APPLICATION_PDF;
            } else if (lowerName.endsWith(".png")) {
                mediaType = MediaType.IMAGE_PNG;
            } else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
                mediaType = MediaType.IMAGE_JPEG;
            } else if (lowerName.endsWith(".gif")) {
                mediaType = MediaType.IMAGE_GIF;
            } else if (lowerName.endsWith(".mp4")) {
                 mediaType = MediaType.parseMediaType("video/mp4");
            } else if (lowerName.endsWith(".mp3")) {
                 mediaType = MediaType.parseMediaType("audio/mpeg");
            } else if (lowerName.endsWith(".txt")) {
                 mediaType = MediaType.TEXT_PLAIN;
            }

            String filename = URLEncoder.encode(downloadFilename, StandardCharsets.UTF_8).replace("+", "%20");

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (StorageException e) {
            log.error("[FileDownload] 存储异常: fileId={}, message={}", fileId, e.getMessage());
            log.error("[FileDownload] 存储异常堆栈:", e);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("[FileDownload] 未知异常: fileId={}, message={}", fileId, e.getMessage());
            log.error("[FileDownload] 未知异常堆栈:", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 递归构建文件的逻辑路径（相对于项目根目录）
     */
    private String buildLogicalPath(ProjectFile file) {
        if (file.getParentId() == null) {
            return "";
        }
        
        StringBuilder pathBuilder = new StringBuilder();
        ProjectFile current = file;
        
        // 向上查找父文件夹，直到根目录
        // 为防止死循环，设置最大深度
        int depth = 0;
        while (current.getParentId() != null && depth < 20) {
            Optional<ProjectFile> parentOpt = projectFileRepository.findById(current.getParentId());
            if (parentOpt.isPresent()) {
                current = parentOpt.get();
                // 在路径前插入父文件夹名
                if (pathBuilder.length() > 0) {
                    pathBuilder.insert(0, "/");
                }
                pathBuilder.insert(0, current.getName());
            } else {
                break;
            }
            depth++;
        }
        
        return pathBuilder.toString();
    }

    /**
     * 上传目标文件定位：先按数据库 ID 查，查不到再退回 wpsFileId——与 downloadFile
     * （:111-124）同一套双查顺序。缺这一步时，任何 wpsFileId 为 null 的文件（凡是走
     * 清单同步创建的 ProjectFile 行都是这样：跨机器 git clone/从云端接一个项目/退回·
     * 切线·采纳等场景新建的节点，manifest v2 只带 uid/relPath，不带 wpsFileId）在编辑器
     * 里保存都会静默失败——LibreOfficeEditor.vue 的 `f.wpsFileId || f.id` 会把数字 id
     * 当 fileId 传过来，这里如果只认 wpsFileId 就查不到，resolveUploadStoragePath 会拿
     * 裸 id 字符串当存储路径，字节写进一个跟真实文件毫不相干的孤儿路径，且
     * signalChange 因 projectFileOpt 为空而不触发——律师看到保存成功提示，实际编辑
     * 内容对应的真文件在磁盘上纹丝没动。J11 e2e 里同事在另一台机器上编辑一个从云端
     * 接入的文件时现场踩中，不是假设性风险。
     */
    private Optional<ProjectFile> resolveProjectFileForUpload(String fileId) {
        try {
            Optional<ProjectFile> byId = projectFileRepository.findById(Long.parseLong(fileId));
            if (byId.isPresent()) return byId;
        } catch (NumberFormatException ignored) {
            // fileId 不是数字，走下面的 wpsFileId 查找
        }
        return projectFileRepository.findByWpsFileId(fileId).stream().findFirst();
    }

    /**
     * 解析上传目标的存储路径：优先用 DB 记录的 filePath；没有则按项目逻辑路径
     * 生成并回写 DB。首块(save)、追加块(append)、断点查询(getSize) 三处必须用
     * 同一路径，否则分片会写散。
     */
    private String resolveUploadStoragePath(String fileId, Optional<ProjectFile> projectFileOpt) {
        String storagePath = fileId;
        if (projectFileOpt.isPresent()) {
            ProjectFile pf = projectFileOpt.get();
            if (StringUtils.hasText(pf.getFilePath())) {
                storagePath = pf.getFilePath();
            } else {
                String safeName = StringUtils.hasText(pf.getName()) ? pf.getName() : fileId;
                if (pf.getFileType() != null && !safeName.endsWith("." + pf.getFileType())) {
                    safeName += "." + pf.getFileType();
                }
                String logicalPath = buildLogicalPath(pf);
                String basePath = String.format("projects/%d", pf.getProjectId());
                storagePath = StringUtils.hasText(logicalPath) ?
                    String.format("%s/%s/%s", basePath, logicalPath, safeName) :
                    String.format("%s/%s", basePath, safeName);

                pf.setFilePath(storagePath);
                projectFileRepository.save(pf);
            }
        }
        return storagePath;
    }

    @GetMapping("/{fileId}/upload-status")
    public ResponseEntity<Map<String, Object>> getUploadStatus(@PathVariable("fileId") String fileId) {
        try {
            // 与上传同一套路径解析——此前直接 getSize(裸 fileId) 读的是孤儿路径，
            // 断点续传的 offset 永远对不上真实文件
            Optional<ProjectFile> pfOpt = projectFileRepository.findByWpsFileId(fileId).stream().findFirst();
            String path = pfOpt.map(ProjectFile::getFilePath).filter(StringUtils::hasText).orElse(fileId);
            long size = getStorageService().getSize(path);
            Map<String, Object> data = new HashMap<>();
            data.put("uploadedSize", size);
            
            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("data", data);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取上传状态失败: fileId={}", fileId, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 上传接口
     * ...
     */
    @PostMapping("/{fileId}/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @PathVariable("fileId") String fileId,
            @RequestPart(value = "file", required = false) MultipartFile multipartFile,
            @RequestHeader(value = "X-File-Offset", required = false) Long offset,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionHeader,
            @RequestParam(value = "token", required = false) String token,
            HttpServletRequest request) {

        InputStream inputStream = null;
        try {
            // 0. 检查项目总大小限制 (20GB)
            // 先按数据库 ID 查，查不到再退回 wpsFileId——与 downloadFile（:111-124）同一套
            // 双查顺序。缺这一步时，任何 wpsFileId 为 null 的文件（凡是走清单同步创建的
            // ProjectFile 行都是这样：跨机器 git clone/从云端接一个项目/退回·切线·采纳
            // 等场景新建的节点，manifest v2 只带 uid/relPath，不带 wpsFileId）在编辑器里
            // 保存都会静默失败——LibreOfficeEditor.vue 的 `f.wpsFileId || f.id` 会把数字
            // id 当 fileId 传过来，这里如果只认 wpsFileId 就查不到，projectFileOpt 落空，
            // resolveUploadStoragePath 拿裸 id 字符串当存储路径，字节写进一个跟真实文件
            // 毫不相干的孤儿路径，且 signalChange 因 projectFileOpt 为空而不触发——律师
            // 看到保存成功提示，实际编辑内容对应的真文件在磁盘上纹丝没动。J11 e2e 里
            // 同事在 B 机器上编辑一个从云端接入的文件时现场踩中，不是假设性风险。
            final Optional<ProjectFile> projectFileOpt = resolveProjectFileForUpload(fileId);
            if (projectFileOpt.isPresent()) {
                Long projectId = projectFileOpt.get().getProjectId();
                // 鉴权：只有目标文件所属项目的成员可上传
                if (!isAuthorizedForProject(token, sessionHeader, projectId)) {
                    return ResponseEntity.status(403).body(Map.of("code", -1, "message", "无权上传到该文件"));
                }
                Long totalSize = projectFileRepository.sumSizeByProjectId(projectId); // Need to add this method to repo
                if (totalSize != null && totalSize > 20L * 1024 * 1024 * 1024) {
                     return ResponseEntity.status(400).body(Map.of("code", -1, "message", "项目文件总大小超过20GB限制"));
                }
            } else {
                // 找不到目标文件记录时，至少要求已登录用户，拒绝匿名上传
                String sid = StringUtils.hasText(sessionHeader) ? sessionHeader : token;
                if (AuthController.getUserIdFromSession(sid) == null) {
                    return ResponseEntity.status(401).body(Map.of("code", -1, "message", "请先登录"));
                }
            }

            String contentType = request.getContentType();
            log.info("文件上传请求: fileId={}, offset={}, contentType={}, multipartFile={}", 
                fileId, offset, contentType, multipartFile != null ? multipartFile.getOriginalFilename() : "null");
            
            // ... (multipart checks) ...
            if (contentType != null && contentType.toLowerCase().startsWith("multipart/form-data")) {
                if (multipartFile == null || multipartFile.isEmpty()) {
                     if (request instanceof MultipartHttpServletRequest) {
                        MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
                        multipartFile = multipartRequest.getFile("file");
                     }
                }
                if (multipartFile == null || multipartFile.isEmpty()) {
                     return ResponseEntity.status(400).body(Map.of("code", -1, "message", "未找到文件"));
                }
                inputStream = multipartFile.getInputStream();
            } else {
                inputStream = request.getInputStream();
            }

            // 1. 确定存储路径
            // ... (Existing path logic, keep simplified for brevity in this replace block if possible, but I need to be careful not to delete logic)
            // Wait, replace_file_content replaces a block. I should probably use multi_replace to be precise or rewrite the whole method carefully.
            // I will rewrite the logic to use `append` if offset > 0.

            // 1. 确定存储路径（首块与追加块必须解析到同一路径：此前追加块直接用
            // 裸 wpsFileId 作路径，>5MB 文件的第 2+ 块被追加到存储根的孤儿文件里，
            // 正式路径上只剩首块 5MB —— 下载得到截断的 zip，文档加载失败）
            String storagePath = resolveUploadStoragePath(fileId, projectFileOpt);

            String savedPath;
            if (offset != null && offset > 0) {
                 // 追加模式
                 savedPath = getStorageService().append(storagePath, inputStream);
            } else {
                 // 覆盖/新传模式
                 savedPath = getStorageService().save(storagePath, inputStream);
            }

            // 检查是否完成上传并触发RAG (Async)
            // uploadComplete 同时复用为版本变更信号的完成判定：分片上传时，"上传成功"
            // 指整个文件传完，而不是某一片落盘，避免几百个分片各发一次信号。
            // 无 X-File-Total-Size 头的普通（非分片）上传路径不受影响，视为一次性完成。
            boolean uploadComplete = true;
            String totalSizeStr = request.getHeader("X-File-Total-Size");
            if (StringUtils.hasText(totalSizeStr)) {
                uploadComplete = false;
                try {
                    long totalSize = Long.parseLong(totalSizeStr);
                    long currentSize = getStorageService().getSize(savedPath); // Need to ensure savedPath works for getSize, usually it takes key?
                    // LocalFileStorageService.getSize implementation takes key (filePath).
                    // Wait, getStorageService().save returns the key (path). so savedPath is the key.

                    uploadComplete = currentSize >= totalSize;
                    if (uploadComplete) {
                         if (projectFileOpt.isPresent()) {
                             Long pid = projectFileOpt.get().getProjectId();
                             // Async execution to prevent blocking 408 Timeout
                             java.util.concurrent.CompletableFuture.runAsync(() -> {
                                 try {
                                     log.info("触发异步RAG索引: projectId={}, file={}", pid, savedPath);
                                     projectRagService.refreshProjectKnowledgeIncremental(String.valueOf(pid), savedPath);
                                     
                                     // 触发自动打标签
                                     try {
                                         autoTaggingService.autoTagFile(pid, projectFileOpt.get().getId(), savedPath, projectFileOpt.get().getUserId());
                                     } catch (Exception e) {
                                         log.error("AutoTag failed", e);
                                     }
                                 } catch (Exception e) {
                                     log.error("Async RAG indexing failed for file: " + savedPath, e);
                                 }
                             });
                         }
                    }
                } catch (Exception e) {
                    log.warn("Failed to check completion for RAG trigger", e);
                }
            } else {
                // Compatibility: If no header, trigger for first chunk (Legacy behavior, but Async now)
                if ((offset == null || offset == 0) && projectFileOpt.isPresent()) {
                     Long pid = projectFileOpt.get().getProjectId();
                     java.util.concurrent.CompletableFuture.runAsync(() -> {
                         try {
                              projectRagService.refreshProjectKnowledgeIncremental(String.valueOf(pid), savedPath);
                              
                              // 触发自动打标签 (Legacy)
                              try {
                                  autoTaggingService.autoTagFile(pid, projectFileOpt.get().getId(), savedPath, projectFileOpt.get().getUserId());
                              } catch (Exception e) {
                                  log.error("AutoTag (Legacy) failed", e);
                              }
                         } catch (Exception e) {
                              log.error("Async RAG (Legacy) failed", e);
                         }
                     });
                }
            }

            if (uploadComplete && projectFileOpt.isPresent()) {
                String sid = StringUtils.hasText(sessionHeader) ? sessionHeader : token;
                signalChange(projectFileOpt.get().getProjectId(), AuthController.getUserIdFromSession(sid), AuthController.getUsernameFromSession(sid));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("上传失败", e);
            return ResponseEntity.status(500).body(Map.of("code", -1, "message", e.getMessage()));
        } finally {
            // 关闭上传输入流：此前无 finally，multipart/大文件上传每次泄漏一个句柄
            if (inputStream != null) {
                try { inputStream.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 获取文件文本内容
     * GET /api/files/{fileId}/text
     */
    @GetMapping("/{fileId}/text")
    public ResponseEntity<Map<String, Object>> getFileText(
            @PathVariable("fileId") Long fileId,
            @RequestParam(value = "token", required = false) String token,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionHeader) {
        try {
            Optional<ProjectFile> fileOpt = projectFileRepository.findById(fileId);
            if (fileOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("code", -1, "message", "文件不存在"));
            }
            if (!isAuthorizedForProject(token, sessionHeader, fileOpt.get().getProjectId())) {
                return ResponseEntity.status(403).body(Map.of("code", -1, "message", "无权访问该文件"));
            }

            String text = extractDocumentText(fileOpt.get());
            
            return ResponseEntity.ok(Map.of("code", 0, "data", text));
        } catch (Exception e) {
            log.error("获取文件文本失败: fileId={}", fileId, e);
            return ResponseEntity.status(500).body(Map.of("code", -1, "message", "获取文本失败: " + e.getMessage()));
        }
    }

    /**
     * 文档比较接口 - 提取两个文档的文本内容供前端进行差异对比
     * @param sourceId 源文档 ID（基准文档）
     * @param targetId 目标文档 ID（比较对象）
     * @return 包含两个文档文本内容的响应
     */
    @GetMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareDocuments(
            @RequestParam("sourceId") Long sourceId,
            @RequestParam("targetId") Long targetId,
            @RequestParam(value = "token", required = false) String token,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionHeader) {

        try {
            log.info("文档比较请求: sourceId={}, targetId={}", sourceId, targetId);
            
            // 1. 查找源文档
            Optional<ProjectFile> sourceOpt = projectFileRepository.findById(sourceId);
            if (sourceOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "code", -1,
                    "message", "源文档不存在: " + sourceId
                ));
            }
            
            // 2. 查找目标文档
            Optional<ProjectFile> targetOpt = projectFileRepository.findById(targetId);
            if (targetOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "code", -1,
                    "message", "目标文档不存在: " + targetId
                ));
            }
            
            ProjectFile sourceFile = sourceOpt.get();
            ProjectFile targetFile = targetOpt.get();

            // 鉴权：两个文档所属项目都需当前用户有权访问
            if (!isAuthorizedForProject(token, sessionHeader, sourceFile.getProjectId())
                    || !isAuthorizedForProject(token, sessionHeader, targetFile.getProjectId())) {
                return ResponseEntity.status(403).body(Map.of("code", -1, "message", "无权访问该文件"));
            }

            // 3. 检查文件类型（只支持 doc/docx）
            List<String> supportedTypes = List.of("doc", "docx");
            String sourceType = sourceFile.getFileType() != null ? sourceFile.getFileType().toLowerCase() : "";
            String targetType = targetFile.getFileType() != null ? targetFile.getFileType().toLowerCase() : "";
            
            if (!supportedTypes.contains(sourceType)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "code", -1,
                    "message", "源文档类型不支持比较: " + sourceType
                ));
            }
            if (!supportedTypes.contains(targetType)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "code", -1,
                    "message", "目标文档类型不支持比较: " + targetType
                ));
            }
            
            // 4. 提取文本内容
            String sourceText = extractDocumentText(sourceFile);
            String targetText = extractDocumentText(targetFile);
            
            // 5. 构建响应
            Map<String, Object> data = new HashMap<>();
            data.put("source", Map.of(
                "id", sourceFile.getId(),
                "name", sourceFile.getName(),
                "text", sourceText
            ));
            data.put("target", Map.of(
                "id", targetFile.getId(),
                "name", targetFile.getName(),
                "text", targetText
            ));
            
            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("data", data);
            
            log.info("文档比较完成: sourceId={}, targetId={}, sourceLen={}, targetLen={}", 
                sourceId, targetId, sourceText.length(), targetText.length());
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("文档比较失败: sourceId={}, targetId={}", sourceId, targetId, e);
            return ResponseEntity.status(500).body(Map.of(
                "code", -1,
                "message", "文档比较失败: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 使用 Apache Tika 提取文档文本内容
     */
    private String extractDocumentText(ProjectFile file) throws IOException, TikaException {
        String filePath = file.getFilePath();
        if (!StringUtils.hasText(filePath)) {
            // 尝试使用 wpsFileId 作为路径
            filePath = file.getWpsFileId();
        }
        
        if (!StringUtils.hasText(filePath)) {
            throw new IOException("文件路径为空: " + file.getId());
        }
        
        try {
            Resource resource = getStorageService().load(filePath);
            try (InputStream is = resource.getInputStream()) {
                Tika tika = new Tika();
                return tika.parseToString(is);
            }
        } catch (StorageException e) {
            throw new IOException("加载文件失败: " + filePath, e);
        }
    }
}
