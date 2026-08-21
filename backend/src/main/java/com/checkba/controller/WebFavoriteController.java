package com.checkba.controller;

import com.checkba.model.entity.User;
import com.checkba.model.entity.WebFavorite;
import com.checkba.repository.UserRepository;
import com.checkba.service.WebFavoriteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 收藏接口：
 * - 项目内收藏：/api/projects/{projectId}/favorites
 * - 我的收藏：/api/favorites/my
 */
@RestController
@RequiredArgsConstructor
public class WebFavoriteController {

    private final WebFavoriteService webFavoriteService;
    private final UserRepository userRepository;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 我的收藏返回条数上限：个人中心那一栏没有搜索框，比项目内收藏的 80 放宽一些 */
    private static final int MY_FAVORITES_DEFAULT_LIMIT = 200;
    private static final int MY_FAVORITES_MAX_LIMIT = 500;

    @GetMapping("/api/favorites/my")
    public ResponseEntity<?> myFavorites(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }
        // 性能关键：与项目内收藏同口径——限量 + 返回轻量列表（meta 可能包含 html 快照，体积巨大）
        int lim = (limit == null ? MY_FAVORITES_DEFAULT_LIMIT : Math.max(1, Math.min(MY_FAVORITES_MAX_LIMIT, limit)));
        List<WebFavorite> list = webFavoriteService.listMyFavorites(userId);
        return ResponseEntity.ok(list.stream().limit(lim).map(WebFavoriteListItem::from).toList());
    }

    @GetMapping("/api/projects/{projectId}/favorites")
    public ResponseEntity<?> listProjectFavorites(
            @PathVariable Long projectId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }
        // 性能关键：返回轻量列表（meta 可能包含 html 快照，体积巨大）
        List<WebFavorite> list = webFavoriteService.searchProjectFavorites(projectId, userId, q, limit);
        return ResponseEntity.ok(list.stream().map(WebFavoriteListItem::from).toList());
    }

    @PostMapping("/api/projects/{projectId}/favorites")
    public ResponseEntity<?> createProjectFavorite(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody CreateFavoriteRequest request) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }

        WebFavorite fav = webFavoriteService.createFavorite(
                userId,
                projectId,
                request.getTitle(),
                request.getSourceUrl(),
                request.getContent(),
                request.getImageBase64(),
                request.getMeta()
        );
        return ResponseEntity.ok(fav);
    }

    @DeleteMapping("/api/favorites/{favoriteId}")
    public ResponseEntity<?> delete(
            @PathVariable Long favoriteId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }
        webFavoriteService.delete(favoriteId, userId);
        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("message", com.checkba.service.LangText.of("删除成功", "Deleted successfully"));
        ok.put("data", new HashMap<>());
        return ResponseEntity.ok(ok);
    }

    @GetMapping("/api/favorites/{favoriteId}/image")
    public ResponseEntity<?> image(
            @PathVariable Long favoriteId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(value = "token", required = false) String token) {
        String finalSessionId = sessionId != null ? sessionId : token;
        Long userId = AuthController.getUserIdFromSession(finalSessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(com.checkba.service.LangText.of("请先登录", "Please sign in first")));
        }
        Resource res = webFavoriteService.loadImage(favoriteId, userId);
        return ResponseEntity.ok(res);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        result.put("data", new HashMap<>());
        return result;
    }

    public static class WebFavoriteListItem {
        private Long id;
        private String title;
        private String sourceUrl;
        private String content;
        private String imagePath;
        private Object createdAt;
        // 从 meta 中提取的轻量字段（前端展示用）
        private String sourceHost;
        private String docFileName;

        public static WebFavoriteListItem from(WebFavorite fav) {
            WebFavoriteListItem it = new WebFavoriteListItem();
            it.setId(fav.getId());
            it.setTitle(fav.getTitle());
            it.setSourceUrl(fav.getSourceUrl());
            it.setContent(fav.getContent());
            it.setImagePath(fav.getImagePath());
            it.setCreatedAt(fav.getCreatedAt());
            // 尝试解析 meta，提取必要字段，丢弃大字段（如 html）
            try {
                String meta = fav.getMeta();
                if (meta != null && !meta.isBlank()) {
                    var node = MAPPER.readTree(meta);
                    if (node != null && node.isObject()) {
                        ObjectNode obj = (ObjectNode) node;
                        if (obj.hasNonNull("sourceHost")) it.setSourceHost(obj.get("sourceHost").asText(""));
                        if (obj.hasNonNull("docFileName")) it.setDocFileName(obj.get("docFileName").asText(""));
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            return it;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getSourceUrl() { return sourceUrl; }
        public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getImagePath() { return imagePath; }
        public void setImagePath(String imagePath) { this.imagePath = imagePath; }
        public Object getCreatedAt() { return createdAt; }
        public void setCreatedAt(Object createdAt) { this.createdAt = createdAt; }
        public String getSourceHost() { return sourceHost; }
        public void setSourceHost(String sourceHost) { this.sourceHost = sourceHost; }
        public String getDocFileName() { return docFileName; }
        public void setDocFileName(String docFileName) { this.docFileName = docFileName; }
    }

    public static class CreateFavoriteRequest {
        private String title;
        private String sourceUrl;
        private String content;
        private String imageBase64;
        private String meta;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getSourceUrl() { return sourceUrl; }
        public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getImageBase64() { return imageBase64; }
        public void setImageBase64(String imageBase64) { this.imageBase64 = imageBase64; }
        public String getMeta() { return meta; }
        public void setMeta(String meta) { this.meta = meta; }
    }
}


