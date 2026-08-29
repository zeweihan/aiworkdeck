package com.checkba.service.mobile;

import com.checkba.model.entity.MobileDeviceState;
import com.checkba.model.entity.MobileMediaInbox;
import com.checkba.model.entity.MobileProjectDir;
import com.checkba.repository.MobileDeviceStateRepository;
import com.checkba.repository.MobileMediaInboxRepository;
import com.checkba.repository.MobileProjectDirRepository;
import com.checkba.repository.MobileTransferRequestRepository;
import com.checkba.service.LangText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
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
 *   <li>删除由桌面端 ACK 触发（删 blob 留行），30 天 TTL 只是兜底（行与残留 blob 一并删）。</li>
 * </ul>
 *
 * <p>容量口径（dev-board#226）：每用户 3GB，只计<b>未投递的 blob</b>（storagePath 非空的行）——
 * ACK 即删 blob 就是释放配额，空间循环利用。配额检查在写盘之前按声明大小做，两笔并发上传
 * 可能同时通过检查而略超上限（最多超一件的量，nginx 单请求 200MB 封顶），接受这个软度，
 * 换取不引锁。
 */
@Service
@Slf4j
public class MobileRelayStoreService {

    /** clientMediaId 只收 UUID 形态——它会拼进物理路径，这里是路径穿越的唯一围栏。 */
    private static final Pattern MEDIA_ID = Pattern.compile("^[A-Fa-f0-9-]{8,64}$");
    /** 单设备目录上限：正常律师机器几十个项目，超出多半是调用方出错，不让它撑爆表。 */
    private static final int MAX_DIR_ENTRIES = 1000;
    /**
     * TTL 从 7 天延长到 30 天（dev-board#226）：7 天意味着桌面端一周不开机，用户拍的
     * 证据就被清掉——手机本地虽默认保留原件，但用户感知是「传上去的丢了」。
     */
    static final Duration TTL = Duration.ofDays(30);
    /** 每用户中转区配额：3GB，只计未投递的 blob（ACK 即删 = 释放配额）。 */
    static final long QUOTA_BYTES = 3L * 1024 * 1024 * 1024;
    /**
     * 在线判定窗口（dev-board#250）：桌面端 60 秒轮询一次 inbox，3 个周期没有心跳
     * 才判离线，容忍单次轮询抖动。isDeviceOnline（#251 跨设备传输复用）与 listDevices
     * 共用这一个常量。
     */
    static final Duration ONLINE_WINDOW = Duration.ofSeconds(180);

    private final MobileProjectDirRepository dirRepository;
    private final MobileMediaInboxRepository inboxRepository;
    private final MobileDeviceStateRepository deviceStateRepository;
    private final MobileRelayBlobStore blobStore;
    /**
     * 配额共池（dev-board#251）：3GB 配额现在是影像中转 + 跨设备传输两张表未投递 blob
     * 之和，不再是本表单独的字节数——跨设备传输占用会挤掉影像中转的可用额度，反之亦然。
     */
    private final MobileTransferRequestRepository transferRequestRepository;

    /**
     * 本 bean 的懒加载自身代理，只为让 storeMedia 撞约束后的重试真正经过 Spring 的事务代理
     * 开出一个新事务——写法与理由照抄 {@code ProjectProfileService.self}：同类方法互相调用
     * 不经代理，@Transactional 会被静默绕过；构造器没法自己注入自己，只能字段注入，@Lazy
     * 打破自引用的构造期死环。包可见（不加 private）是特意的，测试要手工把这个字段接到自己。
     */
    @Autowired
    @Lazy
    MobileRelayStoreService self;

    @Autowired
    public MobileRelayStoreService(MobileProjectDirRepository dirRepository,
                                   MobileMediaInboxRepository inboxRepository,
                                   MobileDeviceStateRepository deviceStateRepository,
                                   MobileRelayBlobStore blobStore,
                                   MobileTransferRequestRepository transferRequestRepository) {
        this.dirRepository = dirRepository;
        this.inboxRepository = inboxRepository;
        this.deviceStateRepository = deviceStateRepository;
        this.blobStore = blobStore;
        this.transferRequestRepository = transferRequestRepository;
    }

    public record DirEntry(String key, String name) {}

    /**
     * storedCount 是实际入库的条数（截断到 MAX_DIR_ENTRIES 时小于 totalCount）；
     * truncated 供调用方（控制器/客户端日志）明确告警，不许静默丢。
     */
    public record DirectoryReplaceResult(int storedCount, int totalCount, boolean truncated) {}

    // ==================== 项目目录 ====================

    /**
     * 尽调模块 P3 稳定性余项 #5（dev-board#100）：超过 MAX_DIR_ENTRIES 此前直接抛异常
     * 拒绝整批请求——桌面端 pushDirectory 每 10 分钟原样重推同一份超限清单，永远同样
     * 失败，律师一条项目都同步不到手机端，且失败只在桌面日志里留一句 warn（没人会看）。
     * 改成与 P0 修 MAX_IMPORT_ENTRIES 一致的口径：截断到上限而不是整批拒绝，
     * DirectoryReplaceResult 明确带回 truncated/totalCount/storedCount 供调用方
     * （控制器 API 响应 → 客户端日志）如实告警，不静默丢。
     */
    @Transactional
    public DirectoryReplaceResult replaceDirectory(Long userId, String deviceId, String deviceName, List<DirEntry> projects) {
        requireDeviceId(deviceId);
        if (projects == null) projects = List.of();

        // 空清单防顶掉守卫（dev-board#75 后续实测的系统性风险）：同一台机器上多个后端实例
        // （e2e/dev/优化者）会共享同一份 ~/.aiworkdeck/mobile-relay.json 的 relay 身份，测试
        // 实例本地库是空的，一次空清单 PUT 就把真桌面端推过的目录整批顶成 0 行。语义权衡
        // 已裁决：用户真删光全部项目时目录短暂陈旧可接受，被测试实例清空目录不可接受——
        // 空清单 + 现存目录行非空时跳过整批替换，保留现有行（心跳在控制器里已照常 touch）。
        if (projects.isEmpty() && !dirRepository.findByUserIdAndDeviceId(userId, deviceId).isEmpty()) {
            log.info("手机同步：收到空项目清单但该设备已有目录行，跳过整批替换（防多实例共享 relay 身份互相顶掉）: userId={}, deviceId={}",
                    userId, deviceId);
            return new DirectoryReplaceResult(0, 0, false);
        }

        int totalCount = projects.size();
        boolean truncated = totalCount > MAX_DIR_ENTRIES;
        List<DirEntry> toStore = truncated ? projects.subList(0, MAX_DIR_ENTRIES) : projects;

        dirRepository.deleteByUserIdAndDeviceId(userId, deviceId);
        // Hibernate 的动作队列把 INSERT 排在实体级 DELETE 之前，同键重推会先撞唯一约束——
        // 删除必须先落库
        dirRepository.flush();
        LocalDateTime now = LocalDateTime.now();
        int stored = 0;
        for (DirEntry entry : toStore) {
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
            stored++;
        }
        if (truncated) {
            log.warn("手机同步：项目目录条数超过上限，已截断: userId={}, deviceId={}, 总数={}, 上限={}, 已存={}",
                    userId, deviceId, totalCount, MAX_DIR_ENTRIES, stored);
        }
        return new DirectoryReplaceResult(stored, totalCount, truncated);
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

    // ==================== 设备心跳与在线判定 ====================

    /**
     * 心跳落点（dev-board#250）：GET /inbox（桌面端真实 60 秒轮询）与
     * PUT /projects（目录推送）各调一次。故意不要求调用方处理异常——心跳失败
     * 不能挡住这两个主请求，任何问题（含并发首建撞约束以外的意外错误）都吞掉只记日志。
     */
    public void touchDevice(Long userId, String deviceId) {
        touchDevice(userId, deviceId, null);
    }

    /** deviceName 非空白时顺带更新心跳行的设备名（PUT /projects 的 payload 里带着）。 */
    public void touchDevice(Long userId, String deviceId, String deviceName) {
        if (deviceId == null || deviceId.isBlank()) return;
        try {
            self.touchDeviceTx(userId, deviceId, deviceName);
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            // 并发首建撞唯一约束：对方那笔已经提交，重查更新一次（写法同 storeMedia）
            try {
                self.touchDeviceTx(userId, deviceId, deviceName);
            } catch (Exception e2) {
                log.warn("手机同步：设备心跳更新失败（不影响主请求）: userId={}, deviceId={}, err={}",
                        userId, deviceId, e2.toString());
            }
        } catch (Exception e) {
            log.warn("手机同步：设备心跳更新失败（不影响主请求）: userId={}, deviceId={}, err={}",
                    userId, deviceId, e.toString());
        }
    }

    /** {@link #touchDevice} 的事务体：find→update，无则 insert。必须经 {@link #self} 代理。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchDeviceTx(Long userId, String deviceId, String deviceName) {
        LocalDateTime now = LocalDateTime.now();
        String name = deviceName != null && !deviceName.isBlank() ? truncate(deviceName, 128) : null;
        Optional<MobileDeviceState> existing = deviceStateRepository.findByUserIdAndDeviceId(userId, deviceId);
        if (existing.isPresent()) {
            existing.get().setLastSeenAt(now);
            if (name != null) existing.get().setDeviceName(name);
            deviceStateRepository.save(existing.get());
            return;
        }
        MobileDeviceState row = new MobileDeviceState();
        row.setUserId(userId);
        row.setDeviceId(deviceId);
        row.setLastSeenAt(now);
        row.setDeviceName(name);
        deviceStateRepository.save(row);
    }

    /** 在线判定：ONLINE_WINDOW 窗口内有心跳。没心跳记录（从没上过线）一律离线。 */
    public boolean isDeviceOnline(Long userId, String deviceId) {
        return deviceStateRepository.findByUserIdAndDeviceId(userId, deviceId)
                .map(s -> Duration.between(s.getLastSeenAt(), LocalDateTime.now()).compareTo(ONLINE_WINDOW) <= 0)
                .orElse(false);
    }

    /**
     * 该账号全部设备清单：目录行按 deviceId 分组（deviceName 取该组第一个非空值），
     * join 心跳表拿 lastSeenAt/online。有心跳但没有目录行的设备也要出现（projects 为空
     * 数组，deviceName 取心跳行的、取不到给空串）——线上出现过真桌面端的目录被同机
     * 测试实例用空清单顶掉的形态，隐藏这种设备会让用户以为桌面端彻底掉线。排序 online
     * 优先，其次按 lastSeenAt（无心跳的设备退化用目录最近 updatedAt）倒序。
     */
    public List<Map<String, Object>> listDevices(Long userId) {
        List<MobileProjectDir> dirRows = dirRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        List<MobileDeviceState> states = deviceStateRepository.findByUserId(userId);
        if (dirRows.isEmpty() && states.isEmpty()) return List.of();

        // dirRows 已按 updatedAt 倒序，LinkedHashMap 分组后每组第一条就是该设备最新一次
        // 推送的行，可直接当作无心跳时的排序兜底时间戳。
        Map<String, List<MobileProjectDir>> byDevice = new LinkedHashMap<>();
        for (MobileProjectDir row : dirRows) {
            byDevice.computeIfAbsent(row.getDeviceId(), k -> new ArrayList<>()).add(row);
        }

        Map<String, MobileDeviceState> stateByDevice = new HashMap<>();
        for (MobileDeviceState s : states) {
            stateByDevice.put(s.getDeviceId(), s);
        }

        LocalDateTime now = LocalDateTime.now();
        List<DeviceRow> built = new ArrayList<>();
        for (Map.Entry<String, List<MobileProjectDir>> entry : byDevice.entrySet()) {
            String deviceId = entry.getKey();
            List<MobileProjectDir> rows = entry.getValue();
            String deviceName = rows.stream().map(MobileProjectDir::getDeviceName)
                    .filter(n -> n != null && !n.isBlank()).findFirst().orElse(null);
            MobileDeviceState state = stateByDevice.get(deviceId);
            LocalDateTime lastSeenAt = state != null ? state.getLastSeenAt() : null;
            boolean online = lastSeenAt != null
                    && Duration.between(lastSeenAt, now).compareTo(ONLINE_WINDOW) <= 0;
            LocalDateTime sortAt = lastSeenAt != null ? lastSeenAt : rows.get(0).getUpdatedAt();

            List<Map<String, Object>> projects = new ArrayList<>();
            for (MobileProjectDir row : rows) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("key", row.getProjectKey());
                p.put("name", row.getName());
                projects.add(p);
            }
            built.add(new DeviceRow(deviceId, deviceName, lastSeenAt, online, sortAt, projects));
        }

        // 有心跳但没有目录行的设备：projects 空数组照样露脸，deviceName 只有心跳行这一个
        // 来源（取不到给空串，插件端有 unknownDevice 兜底文案）。
        for (MobileDeviceState state : states) {
            if (byDevice.containsKey(state.getDeviceId())) continue;
            LocalDateTime lastSeenAt = state.getLastSeenAt();
            boolean online = lastSeenAt != null
                    && Duration.between(lastSeenAt, now).compareTo(ONLINE_WINDOW) <= 0;
            String deviceName = state.getDeviceName() != null && !state.getDeviceName().isBlank()
                    ? state.getDeviceName() : "";
            built.add(new DeviceRow(state.getDeviceId(), deviceName, lastSeenAt, online,
                    lastSeenAt, new ArrayList<>()));
        }

        built.sort((a, b) -> {
            if (a.online() != b.online()) return a.online() ? -1 : 1;
            return b.sortAt().compareTo(a.sortAt());
        });

        List<Map<String, Object>> out = new ArrayList<>();
        for (DeviceRow d : built) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", d.deviceId());
            m.put("deviceName", d.deviceName());
            m.put("lastSeenAt", d.lastSeenAt() != null ? d.lastSeenAt().toString() : null);
            m.put("online", d.online());
            m.put("projects", d.projects());
            out.add(m);
        }
        return out;
    }

    /** listDevices 内部排序用的中间结构，不对外暴露。 */
    private record DeviceRow(String deviceId, String deviceName, LocalDateTime lastSeenAt,
                              boolean online, LocalDateTime sortAt, List<Map<String, Object>> projects) {}

    // ==================== 影像中转 ====================

    /**
     * 先查后插之间没有锁：两次并发重传（弱网重试、手机端与桌面端同时轮到）各自查到
     * "不存在"，落败的一方在 {@code inboxRepository.save} 这一步撞
     * {@code (user_id, client_media_id)} 唯一约束，抛 DataIntegrityViolationException。
     * 这条异常在 mobile 包与 GlobalExceptionHandler 里都没有专项处理，落到通用处理器变成
     * {@code {"code":1,"message":"服务器内部错误"}}——与本方法原本承诺的"幂等：弱网重传…
     * 都不产生重复件"正相反（正常重传本该拿到既有记录，却收到一个错误）。
     *
     * <p>不带 @Transactional——重试必须落在新事务里，理由与
     * {@code ProjectProfileService.saveUserField} 完全一致：撞约束会被 Hibernate 标记
     * 当前事务 rollback-only，同一事务内 catch 之后继续操作能"正常跑完"，但事务在方法出口
     * 提交时仍会因 rollback-only 抛 UnexpectedRollbackException，等同于没有兜底。
     * 落败方重试时一定能查到既有记录：DB 唯一约束在 INSERT 时点检查，我们的 save 之所以
     * 撞上，就证明对方那笔已经提交（否则我们会拿到行锁等待而不是当场失败）。
     */
    public MobileMediaInbox storeMedia(Long userId, String deviceId, String projectKey,
                                       String clientMediaId, String fileName, String mediaType,
                                       LocalDateTime capturedAt, long declaredSize, InputStream content) {
        try {
            return self.storeMediaTx(userId, deviceId, projectKey, clientMediaId, fileName, mediaType, capturedAt, declaredSize, content);
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            return self.storeMediaTx(userId, deviceId, projectKey, clientMediaId, fileName, mediaType, capturedAt, declaredSize, content);
        }
    }

    /**
     * {@link #storeMedia} 的事务体。不要直接调用（包括同类内部调用）——必须经 {@link #self}
     * 代理才能拿到独立的新事务，直接调用会绕过 Spring 的事务拦截。propagation 显式钉死
     * REQUIRES_NEW，理由同 ProjectProfileService 对应方法：调用方今天确实没有外层事务，
     * 但 REQUIRES_NEW 的保证不依赖这个前提，且能防止撞约束打上的 rollback-only 标记
     * 随 join 污染调用方可能存在的外层事务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MobileMediaInbox storeMediaTx(Long userId, String deviceId, String projectKey,
                                       String clientMediaId, String fileName, String mediaType,
                                       LocalDateTime capturedAt, long declaredSize, InputStream content) {
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
        if (!"image".equals(mediaType) && !"video".equals(mediaType) && !"audio".equals(mediaType)) {
            throw new IllegalArgumentException(LangText.of("影像类型只能是 image、video 或 audio",
                    "Media type must be image, video or audio"));
        }

        Optional<MobileMediaInbox> existing = inboxRepository.findByUserIdAndClientMediaId(userId, clientMediaId);
        if (existing.isPresent()) {
            // 幂等：弱网重传、进程被杀重启都不产生重复件（spec 不变式 2）。
            // 幂等命中必须先于配额检查——重传不占新空间，配额满也不能把重传拒成失败。
            return existing.get();
        }

        // 配额检查在写盘之前、按声明大小做（controller 传 MultipartFile.getSize()，就是实际
        // 字节数）。只计未投递的 blob：桌面端收走（ACK）即释放，空间循环利用。
        // dev-board#251：配额是共池，跨设备传输（MobileTransferRequest）未投递的 blob 也占这份 3GB。
        long usedBytes = inboxRepository.sumPendingBytes(userId) + transferRequestRepository.sumPendingBytes(userId);
        if (usedBytes + Math.max(0, declaredSize) > QUOTA_BYTES) {
            throw new IllegalArgumentException(LangText.of(
                    "云端空间已满（3GB）：请在桌面端打开 AI WorkDeck 收取已上传的文件后重试",
                    "Cloud relay storage is full (3GB). Open AI WorkDeck on your desktop to collect pending items, then retry."));
        }

        MobileRelayBlobStore.StoredBlob stored;
        try {
            stored = blobStore.put(userId, clientMediaId, content, declaredSize);
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("影像暂存失败", "Failed to store media"), e);
        }

        MobileMediaInbox row = new MobileMediaInbox();
        row.setUserId(userId);
        row.setDeviceId(deviceId);
        row.setProjectKey(truncate(projectKey.trim(), 64));
        row.setClientMediaId(clientMediaId);
        row.setFileName(sanitizeFileName(fileName));
        row.setMediaType(mediaType);
        row.setFileSize(stored.size());
        row.setStoragePath(stored.locator());
        row.setCapturedAt(capturedAt);
        row.setCreatedAt(LocalDateTime.now());
        return inboxRepository.save(row);
    }

    public List<MobileMediaInbox> pendingForDevice(Long userId, String deviceId) {
        requireDeviceId(deviceId);
        return inboxRepository.findByUserIdAndDeviceIdAndDeliveredAtIsNullOrderByCreatedAtAsc(userId, deviceId);
    }

    /** openContent 的返回：内容流（调用方/Spring 负责关闭）+ 字节数（Content-Length 用）。 */
    public record ContentBlob(InputStream stream, long length) {}

    /** 取件内容：只有属主且尚未投递的能读。 */
    public ContentBlob openContent(Long userId, Long itemId) {
        MobileMediaInbox item = owned(userId, itemId);
        if (item.getDeliveredAt() != null || item.getStoragePath() == null) {
            throw new IllegalArgumentException(LangText.of("该影像已投递", "This media item has already been delivered"));
        }
        if (!blobStore.exists(item.getStoragePath())) {
            throw new IllegalArgumentException(LangText.of("影像内容不存在", "Media content not found"));
        }
        try {
            return new ContentBlob(blobStore.open(item.getStoragePath()), item.getFileSize());
        } catch (IOException e) {
            throw new IllegalArgumentException(LangText.of("影像内容不存在", "Media content not found"), e);
        }
    }

    @Transactional
    public void ack(Long userId, Long itemId) {
        MobileMediaInbox item = owned(userId, itemId);
        if (item.getStoragePath() != null) {
            // blob 删不掉不该挡住投递确认（deleteQuietly 只 warn）：行先标记，残留由 TTL/桶生命周期兜底
            blobStore.deleteQuietly(item.getStoragePath());
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
            // 未投递件带 TTL 到期时刻，手机端据此做「快到期」提醒（dev-board#226）
            if (item.getDeliveredAt() == null) {
                m.put("expiresAt", item.getCreatedAt().plus(TTL).toString());
            }
            out.add(m);
        }
        return out;
    }

    /** 中转区用量（只计未投递 blob）与配额，手机端设置页展示用。 */
    public Map<String, Object> usage(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("usedBytes", inboxRepository.sumPendingBytes(userId));
        m.put("quotaBytes", QUOTA_BYTES);
        return m;
    }

    // ==================== TTL 兜底 ====================

    /** 每天一次：超过 30 天的行（含未投递的）删行 + 删残留 blob。ACK 才是主删除机制。 */
    @Scheduled(initialDelay = 15 * 60 * 1000, fixedDelay = 24 * 60 * 60 * 1000)
    @Transactional
    public void cleanupExpired() {
        List<MobileMediaInbox> expired = inboxRepository.findByCreatedAtBefore(LocalDateTime.now().minus(TTL));
        for (MobileMediaInbox item : expired) {
            if (item.getStoragePath() != null) {
                blobStore.deleteQuietly(item.getStoragePath());
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
