package com.checkba.service;

import com.checkba.model.dto.ClipboardListResult;
import com.checkba.model.entity.ClipboardItem;
import com.checkba.repository.ClipboardItemRepository;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.service.entitlement.FeatureCatalog;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClipboardService {

    /** 免费版最多回溯的条数（Spec §5）。 */
    public static final int FREE_MAX_ITEMS = 20;
    /** 免费版保留天数（Spec §5）。与条数上限**同时**生效，取更严者。 */
    public static final int FREE_RETENTION_DAYS = 3;

    private final ClipboardItemRepository repository;
    private final com.checkba.storage.StorageServiceFactory storageServiceFactory;
    private final EntitlementService entitlementService;
    private final boolean localMode;

    public ClipboardService(ClipboardItemRepository repository,
                            com.checkba.storage.StorageServiceFactory storageServiceFactory,
                            EntitlementService entitlementService,
                            @org.springframework.beans.factory.annotation.Value("${security.local-mode:false}")
                            boolean localMode) {
        this.repository = repository;
        this.storageServiceFactory = storageServiceFactory;
        this.entitlementService = entitlementService;
        this.localMode = localMode;
    }

    private com.checkba.storage.StorageService getStorageService() {
        return storageServiceFactory.getStorageService();
    }

    /**
     * 剪贴板列表。
     *
     * <p><b>免费额度是查询侧过滤，绝不删除记录。</b>未拥有 {@code clipboard.unlimited} 时
     * 只返回「最近 20 条」且「3 天内」的记录（两条同时生效，取更严者），
     * 超出的记录留在库里，用户解锁后原样可见。</p>
     *
     * <p>{@code hiddenCount} 只统计**因额度**不可见的条数，不含仅被分页 {@code limit}
     * 挡住的：后者对付费用户同样存在，把它算进去会让提示文案变成谎话。
     * 算法：{@code hidden = 总数 − min(3天内的条数, 20)}。</p>
     *
     * <p>非单机模式（团队案件库服务器）不执行额度：{@link EntitlementService} 是按本机的
     * （无 userId 维度，来源是本机 {@code ~/.aiworkdeck} 状态），服务器上恒为空集，
     * 真照着执行会把每个接入成员的剪贴板都截到 20 条且永远无法解锁。
     * 这个 SKU 卖的是单机版的本地能力，与 {@code LicenseController}
     * 「非 local-mode 恒为已解锁正式版」同口径。</p>
     */
    public ClipboardListResult list(Long userId, String query, int limit) {
        int size = Math.max(1, Math.min(200, limit));
        String q = StringUtils.hasText(query) ? query.trim() : null;

        if (!localMode || entitlementService.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED)) {
            return ClipboardListResult.unlimited(fetch(userId, q, size));
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(FREE_RETENTION_DAYS);
        // 条数上限先在 SQL 层收紧，再按时间过滤——两者取交集即「更严者」
        List<ClipboardItem> items = fetch(userId, q, Math.min(size, FREE_MAX_ITEMS)).stream()
                .filter(it -> it.getCreatedAt() != null && it.getCreatedAt().isAfter(cutoff))
                .toList();

        long total = q == null ? repository.countByUserId(userId) : repository.countSearch(userId, q);
        long recent = q == null
                ? repository.countByUserIdAndCreatedAtAfter(userId, cutoff)
                : repository.countSearchAfter(userId, q, cutoff);
        long visibleUnderQuota = Math.min(recent, FREE_MAX_ITEMS);
        long hidden = Math.max(0L, total - visibleUnderQuota);

        return new ClipboardListResult(items, true, hidden, FREE_MAX_ITEMS, FREE_RETENTION_DAYS);
    }

    private List<ClipboardItem> fetch(Long userId, String q, int size) {
        if (q != null) {
            return repository.search(userId, q, PageRequest.of(0, size));
        }
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, size));
    }

    @Transactional
    public ClipboardItem saveText(Long userId, String text) {
        if (userId == null) throw new IllegalArgumentException(LangText.of("userId 不能为空", "userId must not be empty"));
        if (!StringUtils.hasText(text)) throw new IllegalArgumentException(LangText.of("text 不能为空", "text must not be empty"));

        ClipboardItem item = new ClipboardItem();
        item.setUserId(userId);
        item.setType("TEXT");
        item.setText(text);
        item.setCreatedAt(LocalDateTime.now());
        return repository.save(item);
    }

    @Transactional
    public ClipboardItem saveFile(Long userId, org.springframework.web.multipart.MultipartFile file, String type) throws java.io.IOException {
        if (userId == null) throw new IllegalArgumentException(LangText.of("userId 不能为空", "userId must not be empty"));
        if (file == null || file.isEmpty()) throw new IllegalArgumentException(LangText.of("file 不能为空", "file must not be empty"));

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
        ClipboardItem item = repository.findById(id).orElseThrow(() -> new IllegalArgumentException(LangText.of("记录不存在", "Record not found")));
        if (!item.getUserId().equals(userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该记录", "You do not have access to this record"));
        }

        if ("TEXT".equals(item.getType())) {
            throw new IllegalArgumentException(LangText.of("该记录不是文件类型", "This record is not a file"));
        }

        try {
            Map<String, Object> meta = new com.fasterxml.jackson.databind.ObjectMapper().readValue(item.getMeta(), Map.class);
            String path = (String) meta.get("path");
            if (!StringUtils.hasText(path)) {
                throw new IllegalArgumentException(LangText.of("文件路径丢失", "File path is missing"));
            }
            return getStorageService().load(path);
        } catch (Exception e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage());
        }
    }

    @Transactional
    public void delete(Long id, Long userId) {
        ClipboardItem item = repository.findById(id).orElseThrow(() -> new IllegalArgumentException(LangText.of("记录不存在", "Record not found")));
        if (!item.getUserId().equals(userId)) {
            throw new IllegalArgumentException(LangText.of("无权删除该记录", "You do not have permission to delete this record"));
        }
        repository.delete(item);
    }
}


