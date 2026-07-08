package com.checkba.controller;

import com.checkba.service.SensitiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sensitive")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
public class SensitiveController {

    private final SensitiveService sensitiveService;
    private final com.checkba.storage.StorageServiceFactory storageServiceFactory;
    private final com.checkba.service.ProjectFileService projectFileService;
    private final com.checkba.service.ProjectMemberService projectMemberService;

    @GetMapping("/options")
    public ResponseEntity<List<Map<String, String>>> getSensitiveOptions() {
        java.util.List<Map<String, String>> options = new java.util.ArrayList<>();
        for (com.checkba.model.SensitiveType type : com.checkba.model.SensitiveType.values()) {
            options.add(Map.of(
                "value", type.getCode(),
                "label", type.getLabel() + " (" + type.getExample() + ")", // Combine for frontend display simplicity or keep separate
                "example", type.getExample(),
                "description", type.getDescription()
            ));
        }
        return ResponseEntity.ok(options);
    }

    @PostMapping("/desensitize")
    public ResponseEntity<?> desensitizeFile(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        try {
            Long userId = AuthController.getUserIdFromSession(sessionId);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
            }

            // Support both old 'filePath' (legacy) and new 'fileId' ways, or just enforce fileId.
            // Plan says: Accept fileId.
            Object fileIdObj = payload.get("fileId");
            if (fileIdObj == null) {
                 return ResponseEntity.badRequest().body(Map.of("error", "fileId is required"));
            }
            Long fileId = Long.valueOf(fileIdObj.toString());
            
            @SuppressWarnings("unchecked")
            List<String> strategies = (List<String>) payload.get("strategies");
            
            log.info("Requesting desensitization for fileId: {}, strategies: {}", fileId, strategies);

            if (strategies == null || strategies.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "strategies are required"));
            }

            // 1. Get Original File Info
            com.checkba.model.entity.ProjectFile originalFile = projectFileService.getFile(fileId);
            // 越权校验：此前仅校验登录，可对他人项目文件脱敏（读取+复制内容）
            if (!projectMemberService.hasReadPermission(originalFile.getProjectId(), userId)) {
                return ResponseEntity.status(403).body(Map.of("error", "无权访问该文件"));
            }
            String originalFilePath = originalFile.getFilePath();
            
            // 2. Resolve absolute path (for processing)
            org.springframework.core.io.Resource resource = storageServiceFactory.getStorageService().load(originalFilePath);
            java.io.File sourceFile = resource.getFile();
            String absoluteSrcPath = sourceFile.getAbsolutePath();

            // 3. Process desensitization
            // The service generates a new file on disk.
            String newAbsoluteFilePath = sensitiveService.processFile(absoluteSrcPath, strategies);
            java.io.File newFile = new java.io.File(newAbsoluteFilePath);
            
            // 4. Determine logical info for new file
            String newFileName = newFile.getName();
            Long newFileSize = newFile.length();
            String fileType = cn.hutool.core.io.FileUtil.extName(newFileName);
            
            // Calculate relative path for storage (assuming same parent dir as original)
            // originalFilePath: projects/14/doc/1.docx
            // newAbsoluteFilePath: /data/projects/14/doc/[desensitized]1.docx
            // We need: projects/14/doc/[desensitized]1.docx
            
            // Helper: We can try to relativize if we know the root, OR we can construct it manually based on original.
            java.nio.file.Path origPathObj = java.nio.file.Paths.get(originalFilePath);
            java.nio.file.Path parentPath = origPathObj.getParent();
            String newRelativePath;
            if (parentPath != null) {
                newRelativePath = parentPath.resolve(newFileName).toString().replace('\\', '/');
            } else {
                newRelativePath = newFileName;
            }
            
            // 5. Register in Database
            // Generate wpsFileId for online editing support
            String wpsFileId = String.format("project_%d_doc_%d_%s", 
                originalFile.getProjectId(), 
                System.currentTimeMillis(), 
                java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8));

            com.checkba.model.entity.ProjectFile savedFile = projectFileService.createFile(
                originalFile.getProjectId(),
                originalFile.getParentId(),
                newFileName,
                fileType,
                newFileSize,
                newRelativePath,
                wpsFileId,
                userId
            );

            log.info("Desensitized file registered: id={}, path={}", savedFile.getId(), newRelativePath);

            // Return the full file object so frontend can open it
            return ResponseEntity.ok(savedFile);
            
        } catch (Exception e) {
            log.error("Error desensitizing file", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
