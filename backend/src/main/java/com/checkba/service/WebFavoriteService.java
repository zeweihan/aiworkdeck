package com.checkba.service;

import com.checkba.model.entity.WebFavorite;
import com.checkba.repository.WebFavoriteRepository;
import com.checkba.storage.StorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebFavoriteService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebFavoriteService.class);

    private final WebFavoriteRepository repository;
    private final StorageServiceFactory storageServiceFactory;

    public List<WebFavorite> listMyFavorites(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<WebFavorite> listProjectFavorites(Long projectId, Long userId) {
        return repository.findByProjectIdAndUserIdOrderByCreatedAtDesc(projectId, userId);
    }

    public List<WebFavorite> searchProjectFavorites(Long projectId, Long userId, String q, Integer limit) {
        int lim = (limit == null ? 80 : Math.max(1, Math.min(200, limit)));
        Pageable pageable = PageRequest.of(0, lim);
        return repository.searchProjectFavorites(projectId, userId, q == null ? "" : q.trim(), pageable);
    }

    @Transactional
    public WebFavorite createFavorite(Long userId, Long projectId, String title, String sourceUrl, String content, String imageBase64, String meta) {
        if (userId == null) throw new IllegalArgumentException(LangText.of("userId 不能为空", "userId must not be empty"));
        if (!StringUtils.hasText(content) && !StringUtils.hasText(imageBase64)) {
            throw new IllegalArgumentException(LangText.of("content 或 imageBase64 至少提供一个", "Please provide at least one of content or imageBase64"));
        }

        WebFavorite fav = new WebFavorite();
        fav.setUserId(userId);
        fav.setProjectId(projectId);
        fav.setTitle(StringUtils.hasText(title) ? title.trim() : null);
        fav.setSourceUrl(StringUtils.hasText(sourceUrl) ? sourceUrl.trim() : null);
        fav.setContent(StringUtils.hasText(content) ? content : "");
        fav.setMeta(StringUtils.hasText(meta) ? meta.trim() : null);
        fav.setCreatedAt(LocalDateTime.now());

        // 可选：保存截图（便于回溯）
        if (StringUtils.hasText(imageBase64)) {
            try {
                String payload = imageBase64.trim();
                int comma = payload.indexOf(',');
                if (payload.startsWith("data:") && comma > 0) {
                    payload = payload.substring(comma + 1);
                }
                byte[] bytes = Base64.getDecoder().decode(payload);
                String fileName = "fav_" + UUID.randomUUID().toString().replace("-", "") + ".png";
                String path = "favorites/" + userId + "/" + fileName;
                storageServiceFactory.getStorageService().save(path, new ByteArrayInputStream(bytes));
                fav.setImagePath(path);
            } catch (Exception e) {
                log.warn("保存收藏截图失败，忽略继续: userId={}", userId, e);
            }
        }

        return repository.save(fav);
    }

    @Transactional
    public void delete(Long favoriteId, Long userId) {
        WebFavorite fav = repository.findById(favoriteId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("收藏不存在", "Favorite not found")));
        if (!fav.getUserId().equals(userId)) {
            throw new IllegalArgumentException(LangText.of("无权删除该收藏", "You do not have permission to delete this favorite"));
        }
        if (StringUtils.hasText(fav.getImagePath())) {
            try {
                storageServiceFactory.getStorageService().delete(fav.getImagePath());
            } catch (Exception e) {
                log.warn("删除收藏截图失败，忽略继续: path={}", fav.getImagePath(), e);
            }
        }
        repository.delete(fav);
    }

    public Resource loadImage(Long favoriteId, Long userId) {
        WebFavorite fav = repository.findById(favoriteId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("收藏不存在", "Favorite not found")));
        if (!fav.getUserId().equals(userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该收藏", "You do not have access to this favorite"));
        }
        if (!StringUtils.hasText(fav.getImagePath())) {
            throw new IllegalArgumentException(LangText.of("该收藏没有截图", "This favorite has no screenshot"));
        }
        return storageServiceFactory.getStorageService().load(fav.getImagePath());
    }
}


