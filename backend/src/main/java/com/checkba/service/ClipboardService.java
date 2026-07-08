package com.checkba.service;

import com.checkba.model.entity.ClipboardItem;
import com.checkba.repository.ClipboardItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ClipboardService {

    private final ClipboardItemRepository repository;
    private final com.checkba.storage.StorageServiceFactory storageServiceFactory;

    private com.checkba.storage.StorageService getStorageService() {
        return storageServiceFactory.getStorageService();
    }

    public List<ClipboardItem> list(Long userId, String query, int limit) {
        int size = Math.max(1, Math.min(200, limit));
        if (StringUtils.hasText(query)) {
            return repository.search(userId, query.trim(), PageRequest.of(0, size));
        }
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, size));
    }

    @Transactional
    public ClipboardItem saveText(Long userId, String text) {
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");
        if (!StringUtils.hasText(text)) throw new IllegalArgumentException("text 不能为空");

        ClipboardItem item = new ClipboardItem();
        item.setUserId(userId);
        item.setType("TEXT");
        item.setText(text);
        item.setCreatedAt(LocalDateTime.now());
        return repository.save(item);
    }

    @Transactional
    public ClipboardItem saveFile(Long userId, org.springframework.web.multipart.MultipartFile file, String type) throws java.io.IOException {
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("file 不能为空");

        String uuid = java.util.UUID.randomUUID().toString();
        // 存储路径：clipboard/{userId}/{uuid}
        String storagePath = "clipboard/" + userId + "/" + uuid;
        
        // 保存文件到存储服务（try-with-resources 关闭上传源流，防句柄泄漏）
        try (java.io.InputStream in = file.getInputStream()) {
            getStorageService().save(storagePath, in);
        }

        // 构建元数据 JSON
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) originalFilename = "unknown";
        
        Map<String, Object> metaMap = new HashMap<>();
        metaMap.put("path", storagePath);
        metaMap.put("fileName", originalFilename);
        metaMap.put("size", file.getSize());
        
        String metaJson;
        try {
            metaJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metaMap);
        } catch (Exception e) {
            metaJson = "{}";
        }

        ClipboardItem item = new ClipboardItem();
        item.setUserId(userId);
        item.setType(type != null ? type : "FILE");
        item.setText(""); // 文件类型不存 text
        item.setMeta(metaJson);
        item.setCreatedAt(LocalDateTime.now());
        return repository.save(item);
    }
    
    public org.springframework.core.io.Resource getFile(Long id, Long userId) {
        ClipboardItem item = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("记录不存在"));
        if (!item.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该记录");
        }
        
        if ("TEXT".equals(item.getType())) {
            throw new IllegalArgumentException("该记录不是文件类型");
        }
        
        try {
            Map<String, Object> meta = new com.fasterxml.jackson.databind.ObjectMapper().readValue(item.getMeta(), Map.class);
            String path = (String) meta.get("path");
            if (!StringUtils.hasText(path)) {
                throw new IllegalArgumentException("文件路径丢失");
            }
            return getStorageService().load(path);
        } catch (Exception e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage());
        }
    }

    @Transactional
    public void delete(Long id, Long userId) {
        ClipboardItem item = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("记录不存在"));
        if (!item.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权删除该记录");
        }
        repository.delete(item);
    }
}


