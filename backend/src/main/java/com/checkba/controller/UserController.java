package com.checkba.controller;

import com.checkba.model.entity.User;
import com.checkba.service.UserService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserService userService;

    @Autowired
    private StorageServiceFactory storageServiceFactory;

    private StorageService getStorageService() {
        return storageServiceFactory.getStorageService();
    }

    /**
     * 上传用户头像
     */
    @PostMapping("/avatar")
    public ResponseEntity<Map<String, Object>> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        if (sessionId == null) {
            return ResponseEntity.status(401).body(Map.of("code", 1, "message", "未登录"));
        }

        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("code", 1, "message", "无效的会话"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "文件不能为空"));
        }

        try {
            // Check file type
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "只能上传图片"));
            }

            // Generate filename: avatars/{userId}.{ext}
            String originalFilename = file.getOriginalFilename();
            String ext = "png"; // Default
            if (originalFilename != null && originalFilename.contains(".")) {
                ext = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
            }
            
            // Allow only common image extensions
            if (!ext.matches("^(png|jpg|jpeg|gif|webp)$")) {
                ext = "png";
            }

            // Use timestamp to prevent caching issues on frontend
            String filename = userId + "_" + System.currentTimeMillis() + "." + ext;
            String storagePath = "avatars/" + filename;

            // Save file
            getStorageService().save(storagePath, file.getInputStream());

            // Build API URL
            // String avatarUrl = "/api/users/avatar/" + filename;
            // Full URL is better for frontend usage if we want to be explicit, but relative path is fine too.
            // Let's use absolute path relative to server root
             String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
             String avatarUrl = baseUrl + "/api/users/avatar/" + filename;

            // Update user profile
            userService.updateAvatar(userId, avatarUrl);

            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("message", "上传成功");
            result.put("data", Map.of("avatarUrl", avatarUrl));
            
            return ResponseEntity.ok(result);

        } catch (IOException e) {
            log.error("Avatar upload failed", e);
            return ResponseEntity.status(500).body(Map.of("code", 1, "message", "上传失败: " + e.getMessage()));
        }
    }

    /**
     * 获取用户头像
     */
    @GetMapping("/avatar/{filename:.+}")
    public ResponseEntity<Resource> getAvatar(@PathVariable String filename) {
        try {
            String storagePath = "avatars/" + filename;
            Resource resource = getStorageService().load(storagePath);

            String contentType = "image/png";
            if (filename.toLowerCase().endsWith(".jpg") || filename.toLowerCase().endsWith(".jpeg")) {
                contentType = "image/jpeg";
            } else if (filename.toLowerCase().endsWith(".gif")) {
                contentType = "image/gif";
            } else if (filename.toLowerCase().endsWith(".webp")) {
                contentType = "image/webp";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=3600") // Cache for 1 hour
                    .body(resource);
        } catch (Exception e) {
            log.warn("Avatar not found: {}", filename);
            return ResponseEntity.notFound().build();
        }
    }
}
