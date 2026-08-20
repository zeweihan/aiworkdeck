package com.checkba.service.mobile;

import com.checkba.model.entity.MobileMediaInbox;
import com.checkba.model.entity.MobileProjectDir;
import com.checkba.repository.MobileMediaInboxRepository;
import com.checkba.repository.MobileProjectDirRepository;
import com.checkba.service.LangText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 云中转区（server 侧）：手机端项目目录镜像 + 现场影像待取件。
 *
 * <p>spec：aiworkdeck_mobile docs/specs/2026-08-20-project-sync-relay.md。
 * 对应桌面侧客户端 {@link MobileRelayClientService}。三条不变式：
 * <ul>
 *   <li>目录按 (userId, deviceId) 整批替换——projectKey 是那台桌面机本地库的项目 id，
 *       跨机同号不同物；</li>
 *   <li>影像幂等键 (userId, clientMediaId)，重传返回既有记录；</li>
 *   <li>删除由桌面端 ACK 触发（删 blob 留行），7 天 TTL 只是兜底（行与残留 blob 一并删）。</li>
 * </ul>
 */
@Service
@Slf4j
public class MobileRelayStoreService {

    /** clientMediaId 只收 UUID 形态——它会拼进物理路径，这里是路径穿越的唯一围栏。 */
    private static final Pattern MEDIA_ID = Pattern.compile("^[A-Fa-f0-9-]{8,64}$");
    /** 单设备目录上限：正常律师机器几十个项目，超出多半是调用方出错，不让它撑爆表。 */
    private static final int MAX_DIR_ENTRIES = 1000;
    private static final Duration TTL = Duration.ofDays(7);

    private final MobileProjectDirRepository dirRepository;
    private final MobileMediaInboxRepository inboxRepository;
    private final Path relayRoot;

    @Autowired
    public MobileRelayStoreService(MobileProjectDirRepository dirRepository,
                                   MobileMediaInboxRepository inboxRepository,
                                   @Value("${storage.local.root-path:data}") String storageRoot) {
        this.dirRepository = dirRepository;
        this.inboxRepository = inboxRepository;
        this.relayRoot = Path.of(storageRoot, "mobile-relay").toAbsolutePath().normalize();
    }

    public record DirEntry(String key, String name) {}

    // ==================== 项目目录 ====================

    @Transactional
    public void replaceDirectory(Long userId, String deviceId, String deviceName, List<DirEntry> projects) {
        requireDeviceId(deviceId);
        if (projects == null) projects = List.of();
        if (projects.size() > MAX_DIR_ENTRIES) {
            throw new IllegalArgumentException(LangText.of("项目目录条数超限", "Too many directory entries"));
        }
        dirRepository.deleteByUserIdAndDeviceId(userId, deviceId);
        // Hibernate 的动作队列把 INSERT 排在实体级 DELETE 之前，同键重推会先撞唯一约束——
        // 删除必须先落库
        dirRepository.flush();
        LocalDateTime now = LocalDateTime.now();
        for (DirEntry entry : projects) {
            if (entry == null || entry.key() == null || entry.key().isBlank()
                    || entry.name() == null || entry.name().isBlank()) {
                continue;
            }
            MobileProjectDir row = new MobileProjectDir();
            row.setUserId(userId);
            row.setDeviceId(deviceId);
            row.setDeviceName(truncate(deviceName, 128));
            row.setProjectKey(truncate(entry.key().trim(), 64));
            row.setName(truncate(entry.name().trim(), 512));
            row.setUpdatedAt(now);
            dirRepository.save(row);
        }
    }

    /** 该账号全部设备的目录并集，按更新时间倒序（最近在线的桌面机排前面）。 */
    public List<Map<String, Object>> listDirectory(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MobileProjectDir row : dirRepository.findByUserIdOrderByUpdatedAtDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", row.getDeviceId());
            m.put("deviceName", row.getDeviceName());
            m.put("key", row.getProjectKey());
            m.put("name", row.getName());
            out.add(m);
        }
        return out;
    }

    // ==================== 影像中转 ====================

    @Transactional
    public MobileMediaInbox storeMedia(Long userId, String deviceId, String projectKey,
                                       String clientMediaId, String fileName, String mediaType,
                                       LocalDateTime capturedAt, InputStream content) {
        requireDeviceId(deviceId);
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalArgumentException(LangText.of("缺少项目标识", "Missing project key"));
        }
        if (clientMediaId == null || !MEDIA_ID.matcher(clientMediaId).matches()) {
            throw new IllegalArgumentException(LangText.of("影像标识格式不正确", "Invalid media id format"));
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException(LangText.of("缺少文件名", "Missing file name"));
        }
        if (!"image".equals(mediaType) && !"video".equals(mediaType)) {
            throw new IllegalArgumentException(LangText.of("影像类型只能是 image 或 video", "Media type must be image or video"));
        }

        Optional<MobileMediaInbox> existing = inboxRepository.findByUserIdAndClientMediaId(userId, clientMediaId);
        if (existing.isPresent()) {
            // 幂等：弱网重传、进程被杀重启都不产生重复件（spec 不变式 2）
            return existing.get();
        }

        Path blob = relayRoot.resolve(String.valueOf(userId)).resolve(clientMediaId);
        long size;
        try {
            Files.createDirectories(blob.getParent());
            size = Files.copy(content, blob, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(LangText.of("影像暂存失败", "Failed to store media"), e);
        }

        MobileMediaInbox row = new MobileMediaInbox();
        row.setUserId(userId);
        row.setDeviceId(deviceId);
        row.setProjectKey(truncate(projectKey.trim(), 64));
        row.setClientMediaId(clientMediaId);
        row.setFileName(sanitizeFileName(fileName));
        row.setMediaType(mediaType);
        row.setFileSize(size);
        row.setStoragePath(blob.toString());
        row.setCapturedAt(capturedAt);
        row.setCreatedAt(LocalDateTime.now());
        return inboxRepository.save(row);
    }

    public List<MobileMediaInbox> pendingForDevice(Long userId, String deviceId) {
        requireDeviceId(deviceId);
        return inboxRepository.findByUserIdAndDeviceIdAndDeliveredAtIsNullOrderByCreatedAtAsc(userId, deviceId);
    }

    /** 取件内容：只有属主且尚未投递的能读。 */
    public Path contentPath(Long userId, Long itemId) {
        MobileMediaInbox item = owned(userId, itemId);
        if (item.getDeliveredAt() != null || item.getStoragePath() == null) {
            throw new IllegalArgumentException(LangText.of("该影像已投递", "This media item has already been delivered"));
        }
        Path p = Path.of(item.getStoragePath());
        if (!Files.exists(p)) {
            throw new IllegalArgumentException(LangText.of("影像内容不存在", "Media content not found"));
        }
        return p;
    }

    @Transactional
    public void ack(Long userId, Long itemId) {
        MobileMediaInbox item = owned(userId, itemId);
        if (item.getStoragePath() != null) {
            try {
                Files.deleteIfExists(Path.of(item.getStoragePath()));
            } catch (IOException e) {
                // blob 删不掉不该挡住投递确认：行先标记，残留 blob 由 TTL 清理兜底
                log.warn("ACK 删除 blob 失败，留给 TTL 清理: item={}, path={}", itemId, item.getStoragePath(), e);
            }
            item.setStoragePath(null);
        }
        if (item.getDeliveredAt() == null) {
            item.setDeliveredAt(LocalDateTime.now());
        }
        inboxRepository.save(item);
    }

    public List<Map<String, Object>> status(Long userId, List<String> clientMediaIds) {
        if (clientMediaIds == null || clientMediaIds.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (MobileMediaInbox item : inboxRepository.findByUserIdAndClientMediaIdIn(userId, clientMediaIds)) {
            Map<String, Object> m = new HashMap<>();
            m.put("clientMediaId", item.getClientMediaId());
            m.put("delivered", item.getDeliveredAt() != null);
            m.put("waitingSeconds", item.getDeliveredAt() != null ? 0L
                    : Math.max(0L, Duration.between(item.getCreatedAt(), now).getSeconds()));
            out.add(m);
        }
        return out;
    }

    // ==================== TTL 兜底 ====================

    /** 每天一次：超过 7 天的行（含未投递的）删行 + 删残留 blob。ACK 才是主删除机制。 */
    @Scheduled(initialDelay = 15 * 60 * 1000, fixedDelay = 24 * 60 * 60 * 1000)
    @Transactional
    public void cleanupExpired() {
        List<MobileMediaInbox> expired = inboxRepository.findByCreatedAtBefore(LocalDateTime.now().minus(TTL));
        for (MobileMediaInbox item : expired) {
            if (item.getStoragePath() != null) {
                try {
                    Files.deleteIfExists(Path.of(item.getStoragePath()));
                } catch (IOException e) {
                    log.warn("TTL 清理 blob 失败: item={}, path={}", item.getId(), item.getStoragePath(), e);
                }
            }
            inboxRepository.delete(item);
        }
        if (!expired.isEmpty()) {
            log.info("影像中转区 TTL 清理 {} 件", expired.size());
        }
    }

    // ==================== 内部 ====================

    private MobileMediaInbox owned(Long userId, Long itemId) {
        MobileMediaInbox item = inboxRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("影像不存在", "Media item not found")));
        if (!item.getUserId().equals(userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该影像", "You do not have access to this media item"));
        }
        return item;
    }

    private static void requireDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 64) {
            throw new IllegalArgumentException(LangText.of("缺少设备标识", "Missing device id"));
        }
    }

    /** 文件名只留最后一段并去掉路径字符——它日后会成为桌面端项目里的文件名。 */
    private static String sanitizeFileName(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        if (n.isEmpty() || ".".equals(n) || "..".equals(n)) {
            throw new IllegalArgumentException(LangText.of("文件名不合法", "Invalid file name"));
        }
        return truncate(n, 512);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
