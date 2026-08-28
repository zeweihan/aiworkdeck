package com.checkba.service.mobile;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.MobileTransferRequest;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.MobileMediaInboxRepository;
import com.checkba.repository.MobileTransferRequestRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.StorageServiceFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 跨设备文件传输（dev-board#251，spec 见
 * docs/superpowers/specs/2026-08-28-cross-device-transfer.md 二）。
 *
 * <p>与影像中转（{@link MobileRelayStoreService}）共用：blob 存储、配额（3GB，两表
 * 未投递 blob 之和）、幂等 requestId 围栏。B 在线判定复用
 * {@link MobileRelayStoreService#isDeviceOnline}（LIST/PULL 要求在线，PUSH 不要求）。
 *
 * <p>计费经 {@link TransferBillingClient}：quote 只读；charge/refund 幂等键
 * {@code xfer-<requestId>} / {@code xferrf-<requestId>}，失败一律翻成用户可读的
 * IllegalArgumentException（HTTP 200 + code:1 信封），绝不免费放行。
 */
@Service
@Slf4j
public class MobileTransferService {

    static final String KIND_LIST = "LIST";
    static final String KIND_PULL = "PULL";
    static final String KIND_PUSH = "PUSH";

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_STAGED = "STAGED";
    static final String STATUS_DONE = "DONE";
    static final String STATUS_DELIVERED = "DELIVERED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_EXPIRED = "EXPIRED";

    /** requestId 只收 UUID 形态——它会拼进 blob key，围栏理由同 MobileRelayStoreService.MEDIA_ID。 */
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Fa-f0-9-]{8,64}$");

    /** 单次传输文件上限：nginx 单请求上限同款。 */
    static final long MAX_TRANSFER_BYTES = 200L * 1024 * 1024;

    /** LIST 应答的 files 数组服务端截断上限。 */
    private static final int MAX_LIST_FILES = 2000;

    /** /commands 的 hot 窗口：5 分钟内有活跃行即让 B 进入短轮询档。 */
    private static final Duration HOT_WINDOW = Duration.ofMinutes(5);

    /** save-to-project 落盘根目录名。 */
    private static final String TRANSFER_ROOT_FOLDER = "跨设备文件";

    private final MobileTransferRequestRepository repository;
    private final MobileMediaInboxRepository mediaInboxRepository;
    private final AccountBindingRepository accountBindingRepository;
    private final ProjectRepository projectRepository;
    private final ProjectFileService projectFileService;
    private final StorageServiceFactory storageServiceFactory;
    private final MobileRelayStoreService relayStore;
    private final MobileRelayBlobStore blobStore;
    private final TransferBillingClient billing;
    private final ObjectMapper om;

    public MobileTransferService(MobileTransferRequestRepository repository,
                                  MobileMediaInboxRepository mediaInboxRepository,
                                  AccountBindingRepository accountBindingRepository,
                                  ProjectRepository projectRepository,
                                  ProjectFileService projectFileService,
                                  StorageServiceFactory storageServiceFactory,
                                  MobileRelayStoreService relayStore,
                                  MobileRelayBlobStore blobStore,
                                  TransferBillingClient billing,
                                  ObjectMapper om) {
        this.repository = repository;
        this.mediaInboxRepository = mediaInboxRepository;
        this.accountBindingRepository = accountBindingRepository;
        this.projectRepository = projectRepository;
        this.projectFileService = projectFileService;
        this.storageServiceFactory = storageServiceFactory;
        this.relayStore = relayStore;
        this.blobStore = blobStore;
        this.billing = billing;
        this.om = om;
    }

    // ==================== 发起端（A） ====================

    public TransferBillingClient.QuoteResult quote(Long userId, long bytes) {
        try {
            // 官网内部记账口拒收 bytes<=0（400），而文件行的 fileSize 可能是 0/null——
            // 出站一律钉下限 1 字节（计价向上取整=最低 1 Credit），别让空文件打出误导性的
            // 「计费服务暂不可用」。charge() 同款。
            return billing.quote(requireAccountId(userId), Math.max(1, bytes));
        } catch (TransferBillingClient.TransferBillingException e) {
            throw translate(e);
        }
    }

    /** 建 LIST 请求：在线检查，不扣费。requestId 撞既有行按幂等返回既有行。 */
    public MobileTransferRequest list(Long userId, String deviceId, String projectKey, String requestId) {
        requireRequestId(requestId);
        Optional<MobileTransferRequest> existing = repository.findByUserIdAndRequestId(userId, requestId);
        if (existing.isPresent()) return existing.get();

        requireDeviceId(deviceId);
        requireProjectKey(projectKey);
        requireOnline(userId, deviceId);

        MobileTransferRequest row = new MobileTransferRequest();
        row.setUserId(userId);
        row.setRequestId(requestId);
        row.setKind(KIND_LIST);
        row.setStatus(STATUS_PENDING);
        row.setDeviceId(deviceId);
        row.setProjectKey(truncate(projectKey.trim(), 64));
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return saveOrIdempotent(userId, requestId, row);
    }

    /** GET /{id}：属主可读，LIST DONE 带 files。 */
    public Map<String, Object> get(Long userId, Long id) {
        MobileTransferRequest row = owned(userId, id);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", row.getId());
        t.put("kind", row.getKind());
        t.put("status", row.getStatus());
        t.put("fileName", row.getFileName());
        t.put("fileSize", row.getFileSize());
        t.put("credits", row.getChargedCredits());
        t.put("error", row.getErrorMessage());
        if (KIND_LIST.equals(row.getKind()) && STATUS_DONE.equals(row.getStatus()) && row.getPayloadJson() != null) {
            t.put("files", parseFiles(row.getPayloadJson()));
        }
        t.put("createdAt", row.getCreatedAt() != null ? row.getCreatedAt().toString() : null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("transfer", t);
        return out;
    }

    /**
     * 建 PULL 请求：在线检查 → 大小上限 → 扣费（幂等键 xfer-requestId）→ 建行。
     * requestId 撞既有行 = 幂等返回既有行（不重复扣费——既有行早已带着当初扣费的结果）。
     */
    public MobileTransferRequest pull(Long userId, String deviceId, String projectKey, String remoteFileId,
                                       String fileName, long fileSize, String requestId) {
        requireRequestId(requestId);
        Optional<MobileTransferRequest> existing = repository.findByUserIdAndRequestId(userId, requestId);
        if (existing.isPresent()) return existing.get();

        requireDeviceId(deviceId);
        requireProjectKey(projectKey);
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException(LangText.of("缺少文件名", "Missing file name"));
        }
        requireOnline(userId, deviceId);
        long size = Math.max(0, fileSize);
        requireSizeLimit(size);

        TransferBillingClient.ChargeResult charge = charge(userId, size, "xfer-" + requestId, requestId);

        MobileTransferRequest row = new MobileTransferRequest();
        row.setUserId(userId);
        row.setRequestId(requestId);
        row.setKind(KIND_PULL);
        row.setStatus(STATUS_PENDING);
        row.setDeviceId(deviceId);
        row.setProjectKey(truncate(projectKey.trim(), 64));
        row.setRemoteFileId(remoteFileId);
        row.setFileName(sanitizeFileName(fileName));
        row.setFileSize(size);
        row.setChargedCredits(charge.credits());
        row.setChargeLedgerId(charge.ledgerId());
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return saveOrIdempotent(userId, requestId, row);
    }

    /**
     * PULL STAGED → 存进 A 自己的云项目：字节先落盘后 createFile（顺序同
     * MobileRelayClientService.landAndAck），落 跨设备文件/YYYY-MM-DD/原名+requestId前8位。
     * 幂等：已 DELIVERED 再调，按同名文件查到即返回；STAGED 内部重试按同名文件跳过重复落盘。
     */
    public Map<String, Object> saveToProject(Long userId, Long id, Long projectId) {
        MobileTransferRequest row = owned(userId, id);
        if (!KIND_PULL.equals(row.getKind())) {
            throw new IllegalArgumentException(LangText.of("该请求不是拉取类型", "This request is not a pull"));
        }
        if (projectId == null) {
            throw new IllegalArgumentException(LangText.of("缺少项目标识", "Missing project id"));
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("项目不存在", "Project not found")));
        if (!userId.equals(project.getUserId())) {
            throw new IllegalArgumentException(LangText.of("无权访问该项目", "You do not have access to this project"));
        }

        String dateStr = row.getCreatedAt().toLocalDate().toString();
        String landedName = landedFileName(row.getFileName(), row.getRequestId());

        if (STATUS_DELIVERED.equals(row.getStatus())) {
            ProjectFile existing = findLandedFile(projectId, dateStr, landedName);
            if (existing != null) {
                return Map.of("fileId", existing.getId(), "name", existing.getName());
            }
            throw new IllegalArgumentException(LangText.of(
                    "该文件已投递，但未能在项目中找到（可能已被移动或删除）",
                    "This file was already delivered but could not be found in the project (it may have been moved or deleted)"));
        }
        if (!STATUS_STAGED.equals(row.getStatus())) {
            throw new IllegalArgumentException(LangText.of(
                    "该拉取尚未就绪，无法保存到项目", "This pull is not ready to be saved to a project yet"));
        }

        ProjectFile root = ensureFolder(projectId, null, TRANSFER_ROOT_FOLDER, userId);
        ProjectFile day = ensureFolder(projectId, root.getId(), dateStr, userId);
        ProjectFile record = projectFileService.getFilesByParent(projectId, day.getId()).stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsFolder()) && landedName.equals(f.getName()))
                .findFirst().orElse(null);
        if (record == null) {
            if (row.getStoragePath() == null || !blobStore.exists(row.getStoragePath())) {
                throw new IllegalArgumentException(LangText.of(
                        "文件内容不存在，请重新发起拉取", "File content is missing, please start the pull again"));
            }
            String storagePath = String.format("projects/%d/%s/%s/%s",
                    projectId, TRANSFER_ROOT_FOLDER, dateStr, landedName);
            try (InputStream in = blobStore.open(row.getStoragePath())) {
                storageServiceFactory.getStorageService().save(storagePath, in);
            } catch (IOException e) {
                throw new IllegalStateException(LangText.of("文件保存失败", "Failed to save the file"), e);
            }
            record = projectFileService.createFile(projectId, day.getId(), landedName,
                    extractExtension(landedName), row.getFileSize(), storagePath, null, userId);
        }

        if (row.getStoragePath() != null) {
            blobStore.deleteQuietly(row.getStoragePath());
            row.setStoragePath(null);
        }
        row.setStatus(STATUS_DELIVERED);
        row.setUpdatedAt(LocalDateTime.now());
        repository.save(row);

        return Map.of("fileId", record.getId(), "name", record.getName());
    }

    /** LIST PENDING 或 PULL PENDING 可取消：FAILED(用户取消) + 退款（如已扣）。 */
    @Transactional
    public void cancel(Long userId, Long id) {
        MobileTransferRequest row = owned(userId, id);
        boolean cancellable = STATUS_PENDING.equals(row.getStatus())
                && (KIND_LIST.equals(row.getKind()) || KIND_PULL.equals(row.getKind()));
        if (!cancellable) {
            throw new IllegalArgumentException(LangText.of(
                    "该请求当前状态不可取消", "This request cannot be cancelled in its current state"));
        }
        row.setErrorMessage(LangText.of("用户取消", "Cancelled by user"));
        row.setStatus(STATUS_FAILED);
        row.setUpdatedAt(LocalDateTime.now());
        tryRefund(row);
        repository.save(row);
    }

    /**
     * 建 PUSH 请求：fileId 必须校验属于本用户项目 → 大小上限 → 扣费 → 配额检查（共池）→
     * 从项目存储复制字节入 blob → PUSH STAGED。requestId 幂等同 pull。
     */
    public MobileTransferRequest push(Long userId, String targetDeviceId, String projectKey,
                                       Long fileId, String requestId) {
        requireRequestId(requestId);
        Optional<MobileTransferRequest> existing = repository.findByUserIdAndRequestId(userId, requestId);
        if (existing.isPresent()) return existing.get();

        requireDeviceId(targetDeviceId);
        requireProjectKey(projectKey);
        if (fileId == null) {
            throw new IllegalArgumentException(LangText.of("缺少文件标识", "Missing file id"));
        }
        ProjectFile file = projectFileService.getFile(fileId);
        if (Boolean.TRUE.equals(file.getIsFolder())) {
            throw new IllegalArgumentException(LangText.of("不能投送文件夹", "Folders cannot be sent"));
        }
        Project project = projectRepository.findById(file.getProjectId())
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("项目不存在", "Project not found")));
        if (!userId.equals(project.getUserId())) {
            throw new IllegalArgumentException(LangText.of("无权访问该文件", "You do not have access to this file"));
        }
        long size = file.getFileSize() == null ? 0 : file.getFileSize();
        requireSizeLimit(size);

        TransferBillingClient.ChargeResult charge = charge(userId, size, "xfer-" + requestId, requestId);
        try {
            checkQuota(userId, size);
        } catch (RuntimeException quotaFailure) {
            // spec 顺序是"扣费→配额检查"，但扣了费又不建行、不退款会把 Credits 白扣掉——
            // 这里不改变检查顺序，只是在配额检查失败时把刚扣的这笔立刻退掉，不留给用户投诉。
            refundChargeQuietly(userId, charge.ledgerId(), requestId);
            throw quotaFailure;
        }

        byte[] bytes;
        try {
            bytes = projectFileService.getFileBytes(fileId);
        } catch (IOException e) {
            throw new IllegalStateException(LangText.of("读取文件失败", "Failed to read the file"), e);
        }
        MobileRelayBlobStore.StoredBlob stored;
        try {
            stored = blobStore.put(userId, requestId,
                    new java.io.ByteArrayInputStream(bytes == null ? new byte[0] : bytes),
                    bytes == null ? 0 : bytes.length);
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("文件暂存失败", "Failed to stage the file"), e);
        }

        MobileTransferRequest row = new MobileTransferRequest();
        row.setUserId(userId);
        row.setRequestId(requestId);
        row.setKind(KIND_PUSH);
        row.setStatus(STATUS_STAGED);
        row.setDeviceId(targetDeviceId);
        row.setProjectKey(truncate(projectKey.trim(), 64));
        row.setFileName(sanitizeFileName(file.getName()));
        row.setFileSize(stored.size());
        row.setStoragePath(stored.locator());
        row.setChargedCredits(charge.credits());
        row.setChargeLedgerId(charge.ledgerId());
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            return repository.save(row);
        } catch (DataIntegrityViolationException e) {
            // 并发撞约束：对方那笔已经提交，我们这份多余的 blob 不需要了（不占配额也不留垃圾）
            blobStore.deleteQuietly(stored.locator());
            return repository.findByUserIdAndRequestId(userId, requestId).orElseThrow(() -> e);
        }
    }

    // ==================== 响应端（B） ====================

    public record CommandsResult(List<Map<String, Object>> commands, boolean hot) {}

    /** GET /commands：该 (userId, deviceId) 的 LIST PENDING、PULL PENDING、PUSH STAGED 行。 */
    public CommandsResult commands(Long userId, String deviceId) {
        requireDeviceId(deviceId);
        List<MobileTransferRequest> candidates = repository.findByUserIdAndDeviceIdAndStatusIn(
                userId, deviceId, List.of(STATUS_PENDING, STATUS_STAGED));
        List<MobileTransferRequest> active = new ArrayList<>();
        for (MobileTransferRequest r : candidates) {
            boolean match = (KIND_LIST.equals(r.getKind()) && STATUS_PENDING.equals(r.getStatus()))
                    || (KIND_PULL.equals(r.getKind()) && STATUS_PENDING.equals(r.getStatus()))
                    || (KIND_PUSH.equals(r.getKind()) && STATUS_STAGED.equals(r.getStatus()));
            if (match) active.add(r);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean hot = active.stream().anyMatch(r -> {
            LocalDateTime t = r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getCreatedAt();
            return t != null && Duration.between(t, now).compareTo(HOT_WINDOW) <= 0;
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (MobileTransferRequest r : active) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("kind", r.getKind());
            m.put("projectKey", r.getProjectKey());
            m.put("remoteFileId", r.getRemoteFileId());
            m.put("fileName", r.getFileName());
            m.put("fileSize", r.getFileSize());
            out.add(m);
        }
        return new CommandsResult(out, hot);
    }

    /** POST /{id}/files：LIST 应答 → DONE，超 2000 条服务端截断。 */
    @Transactional
    public void submitFiles(Long userId, Long id, List<Map<String, Object>> files) {
        MobileTransferRequest row = owned(userId, id);
        if (!KIND_LIST.equals(row.getKind())) {
            throw new IllegalArgumentException(LangText.of("该请求不是清单类型", "This request is not a list"));
        }
        if (STATUS_DONE.equals(row.getStatus())) return; // 幂等：已应答过
        if (!STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalArgumentException(LangText.of("该请求当前状态不接受应答", "This request cannot accept a reply now"));
        }
        List<Map<String, Object>> src = files == null ? List.of() : files;
        if (src.size() > MAX_LIST_FILES) {
            src = src.subList(0, MAX_LIST_FILES);
        }
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Map<String, Object> f : src) {
            if (f == null) continue;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", truncate(str(f.get("id")), 128));
            s.put("name", truncate(str(f.get("name")), 512));
            s.put("path", truncate(str(f.get("path")), 1024));
            Object sizeObj = f.get("size");
            s.put("size", sizeObj instanceof Number ? ((Number) sizeObj).longValue() : 0L);
            sanitized.add(s);
        }
        try {
            row.setPayloadJson(om.writeValueAsString(sanitized));
        } catch (Exception e) {
            row.setPayloadJson("[]");
        }
        row.setStatus(STATUS_DONE);
        row.setUpdatedAt(LocalDateTime.now());
        repository.save(row);
    }

    /** POST /{id}/upload：PULL PENDING → 配额检查（共池）→ blob put → 实际大小覆盖 → STAGED。 */
    public void upload(Long userId, Long id, InputStream content, long declaredSize) {
        MobileTransferRequest row = owned(userId, id);
        if (!KIND_PULL.equals(row.getKind())) {
            throw new IllegalArgumentException(LangText.of("该请求不接受上传", "This request does not accept an upload"));
        }
        if (STATUS_STAGED.equals(row.getStatus())) return; // 幂等：已 STAGED 重传直接 ok
        if (!STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalArgumentException(LangText.of("该请求当前状态不接受上传", "This request does not accept an upload now"));
        }
        requireSizeLimit(declaredSize);
        checkQuota(userId, declaredSize);

        MobileRelayBlobStore.StoredBlob stored;
        try {
            stored = blobStore.put(userId, row.getRequestId(), content, declaredSize);
        } catch (Exception e) {
            throw new IllegalStateException(LangText.of("文件暂存失败", "Failed to stage the file"), e);
        }
        row.setStoragePath(stored.locator());
        row.setFileSize(stored.size());
        row.setStatus(STATUS_STAGED);
        row.setUpdatedAt(LocalDateTime.now());
        repository.save(row);
    }

    public record ContentBlob(InputStream stream, long length) {}

    /** GET /{id}/content：PUSH STAGED（或 PULL STAGED，留给未来桌面 A 直取）。契约同 /inbox/{id}/content。 */
    public ContentBlob content(Long userId, Long id) {
        MobileTransferRequest row = owned(userId, id);
        boolean available = STATUS_STAGED.equals(row.getStatus())
                && (KIND_PUSH.equals(row.getKind()) || KIND_PULL.equals(row.getKind()));
        if (!available) {
            throw new IllegalArgumentException(LangText.of("该内容当前不可读取", "This content is not available right now"));
        }
        if (row.getStoragePath() == null || !blobStore.exists(row.getStoragePath())) {
            throw new IllegalArgumentException(LangText.of("内容不存在", "Content not found"));
        }
        try {
            return new ContentBlob(blobStore.open(row.getStoragePath()),
                    row.getFileSize() == null ? 0 : row.getFileSize());
        } catch (IOException e) {
            throw new IllegalArgumentException(LangText.of("内容不存在", "Content not found"), e);
        }
    }

    /** POST /{id}/ack：PUSH STAGED → DELIVERED + 删 blob（PULL STAGED 同语义，备用）。 */
    @Transactional
    public void ack(Long userId, Long id) {
        MobileTransferRequest row = owned(userId, id);
        if (STATUS_DELIVERED.equals(row.getStatus())) return; // 幂等
        boolean eligible = STATUS_STAGED.equals(row.getStatus())
                && (KIND_PUSH.equals(row.getKind()) || KIND_PULL.equals(row.getKind()));
        if (!eligible) {
            throw new IllegalArgumentException(LangText.of("该请求当前状态不可确认", "This request cannot be acknowledged now"));
        }
        if (row.getStoragePath() != null) {
            blobStore.deleteQuietly(row.getStoragePath());
            row.setStoragePath(null);
        }
        row.setStatus(STATUS_DELIVERED);
        row.setUpdatedAt(LocalDateTime.now());
        repository.save(row);
    }

    /**
     * POST /{id}/fail：B 报确定性失败 → FAILED + 退款（如已扣）+ 删 blob（如有）。
     * 瞬态网络错误 B 不该调这个——那种情况留 PENDING 下轮重试，不进这条路径。
     */
    @Transactional
    public void fail(Long userId, Long id, String message) {
        MobileTransferRequest row = owned(userId, id);
        if (isTerminal(row.getStatus())) return; // 幂等：已经是终态
        row.setErrorMessage(truncate(message == null ? "" : message, 1024));
        if (row.getStoragePath() != null) {
            blobStore.deleteQuietly(row.getStoragePath());
            row.setStoragePath(null);
        }
        row.setStatus(STATUS_FAILED);
        row.setUpdatedAt(LocalDateTime.now());
        tryRefund(row);
        repository.save(row);
    }

    // ==================== TTL 兜底 ====================

    /**
     * 每小时一次：LIST PENDING>10 分钟、PULL PENDING>24 小时、PULL STAGED>7 天、
     * PUSH STAGED>30 天一律 EXPIRED（含删 blob）；随后统一重试一遍已扣未退的行
     * （含刚过期的、以及此前 fail/cancel 时退款失败留下的）。
     */
    @Scheduled(initialDelay = 10 * 60 * 1000, fixedDelay = 60 * 60 * 1000)
    public void cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        int expired = 0;
        expired += expireBatch(repository.findByKindAndStatusAndCreatedAtBefore(
                KIND_LIST, STATUS_PENDING, now.minusMinutes(10)));
        expired += expireBatch(repository.findByKindAndStatusAndCreatedAtBefore(
                KIND_PULL, STATUS_PENDING, now.minusHours(24)));
        expired += expireBatch(repository.findByKindAndStatusAndCreatedAtBefore(
                KIND_PULL, STATUS_STAGED, now.minusDays(7)));
        expired += expireBatch(repository.findByKindAndStatusAndCreatedAtBefore(
                KIND_PUSH, STATUS_STAGED, now.minusDays(30)));
        if (expired > 0) {
            log.info("跨设备传输 TTL 清理 {} 条", expired);
        }
        retryPendingRefunds();
    }

    /**
     * 不加 @Transactional：本类内 this.expireBatch(...) 是同类自调用，绕过 Spring AOP 代理，
     * 类级注解不会生效（与 MobileRelayStoreService 需要 self 代理重试是同一个坑，这里干脆
     * 不假装有事务）——每条 repository.save() 本身仍是 Spring Data 的独立原子操作，
     * 批内多条 save 之间没有跨行原子性也没关系：清扫是幂等的，下一轮重跑即可补齐。
     */
    private int expireBatch(List<MobileTransferRequest> rows) {
        for (MobileTransferRequest row : rows) {
            if (row.getStoragePath() != null) {
                blobStore.deleteQuietly(row.getStoragePath());
                row.setStoragePath(null);
            }
            row.setStatus(STATUS_EXPIRED);
            row.setUpdatedAt(LocalDateTime.now());
            repository.save(row);
        }
        return rows.size();
    }

    /** 已扣未退的行统一重试一次：覆盖 TTL 刚过期的行，也覆盖 fail/cancel 时退款失败留下的行。 */
    private void retryPendingRefunds() {
        List<MobileTransferRequest> pending = repository.findByStatusInAndChargedCreditsIsNotNullAndRefundedAtIsNull(
                List.of(STATUS_EXPIRED, STATUS_FAILED));
        for (MobileTransferRequest row : pending) {
            tryRefund(row);
            repository.save(row);
        }
    }

    // ==================== 内部 ====================

    private MobileTransferRequest saveOrIdempotent(Long userId, String requestId, MobileTransferRequest row) {
        try {
            return repository.save(row);
        } catch (DataIntegrityViolationException e) {
            return repository.findByUserIdAndRequestId(userId, requestId).orElseThrow(() -> e);
        }
    }

    private MobileTransferRequest owned(Long userId, Long id) {
        MobileTransferRequest row = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("传输请求不存在", "Transfer request not found")));
        if (!row.getUserId().equals(userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该传输请求", "You do not have access to this transfer request"));
        }
        return row;
    }

    private void requireOnline(Long userId, String deviceId) {
        if (!relayStore.isDeviceOnline(userId, deviceId)) {
            throw new IllegalArgumentException(LangText.of(
                    "对方设备不在线，待其开机联网后再试", "The other device is offline. Please try again once it is online."));
        }
    }

    private void requireSizeLimit(long size) {
        if (size > MAX_TRANSFER_BYTES) {
            throw new IllegalArgumentException(LangText.of(
                    "单次传输文件不能超过 200MB", "A single transfer cannot exceed 200MB"));
        }
    }

    /** 配额共池：媒体中转 + 跨设备传输两表的未投递 blob 之和，与 MobileRelayStoreService 同一份 3GB。 */
    private void checkQuota(Long userId, long declaredSize) {
        long used = mediaInboxRepository.sumPendingBytes(userId) + repository.sumPendingBytes(userId);
        if (used + Math.max(0, declaredSize) > MobileRelayStoreService.QUOTA_BYTES) {
            throw new IllegalArgumentException(LangText.of(
                    "云端空间已满（3GB）：请在桌面端打开 AI WorkDeck 收取已上传的文件后重试",
                    "Cloud relay storage is full (3GB). Open AI WorkDeck on your desktop to collect pending items, then retry."));
        }
    }

    private String requireAccountId(Long userId) {
        return accountBindingRepository.findByUserId(userId)
                .map(AccountBinding::getExternalAccountId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of(
                        "该账户未与官网账户关联，无法计费", "This account is not linked to a website account; billing is unavailable")));
    }

    private TransferBillingClient.ChargeResult charge(Long userId, long bytes, String idempotencyKey, String refId) {
        String accountId = requireAccountId(userId);
        try {
            // bytes 下限 1：理由见 quote()
            return billing.charge(accountId, Math.max(1, bytes), idempotencyKey, refId);
        } catch (TransferBillingClient.TransferBillingException e) {
            throw translate(e);
        }
    }

    /** 退款：已扣未退才退；失败只 log.error，refundedAt 留空，靠 TTL 清扫兜底重试。不抛异常。 */
    private void tryRefund(MobileTransferRequest row) {
        if (row.getChargedCredits() == null || row.getRefundedAt() != null) return;
        Optional<AccountBinding> binding = accountBindingRepository.findByUserId(row.getUserId());
        if (binding.isEmpty()) {
            log.error("跨设备传输退款失败：账户未绑定官网账户，requestId={}", row.getRequestId());
            return;
        }
        try {
            billing.refund(binding.get().getExternalAccountId(), row.getChargeLedgerId(), "xferrf-" + row.getRequestId());
            row.setRefundedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.error("跨设备传输退款失败（下轮清扫兜底重试）: requestId={}, err={}", row.getRequestId(), e.toString());
        }
    }

    /** push() 里配额检查失败后的即时补退——这笔从未落库成行，不走 tryRefund(row) 那条路径。 */
    private void refundChargeQuietly(Long userId, String ledgerId, String requestId) {
        if (ledgerId == null) return;
        Optional<AccountBinding> binding = accountBindingRepository.findByUserId(userId);
        if (binding.isEmpty()) {
            log.error("跨设备传输：配额检查失败后无法回退扣费（账户未绑定）: requestId={}", requestId);
            return;
        }
        try {
            billing.refund(binding.get().getExternalAccountId(), ledgerId, "xferrf-" + requestId);
        } catch (Exception e) {
            log.error("跨设备传输：配额检查失败后回退扣费也失败（需要人工介入）: requestId={}, err={}", requestId, e.toString());
        }
    }

    private IllegalArgumentException translate(TransferBillingClient.TransferBillingException e) {
        return switch (e.getKind()) {
            case DISABLED -> new IllegalArgumentException(e.getMessage());
            case UNAVAILABLE -> new IllegalArgumentException(e.getMessage());
            case NO_CREDITS -> new IllegalArgumentException(LangText.of(
                    "Credits 余额不足：本次传输预计需要 " + nz(e.getRequiredCredits())
                            + " Credits，账户当前余额约 " + centsToYuan(e.getAvailableCents()) + " 元，请前往官网充值后重试",
                    "Insufficient Credits: this transfer needs about " + nz(e.getRequiredCredits())
                            + " Credits, current balance is about " + centsToYuan(e.getAvailableCents())
                            + " CNY. Please top up on the website and try again."));
        };
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String centsToYuan(Long cents) {
        if (cents == null) return "?";
        return String.format("%.2f", cents / 100.0);
    }

    private static boolean isTerminal(String status) {
        return STATUS_DONE.equals(status) || STATUS_DELIVERED.equals(status)
                || STATUS_FAILED.equals(status) || STATUS_EXPIRED.equals(status);
    }

    private List<Map<String, Object>> parseFiles(String payloadJson) {
        try {
            return om.readValue(payloadJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("跨设备传输：LIST payloadJson 解析失败", e);
            return List.of();
        }
    }

    private ProjectFile ensureFolder(Long projectId, Long parentId, String name, Long userId) {
        Optional<ProjectFile> existing = projectFileService.getFilesByParent(projectId, parentId).stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsFolder()) && name.equals(f.getName()))
                .findFirst();
        return existing.orElseGet(() -> projectFileService.createFolder(projectId, parentId, name, userId));
    }

    /** 已 DELIVERED 的幂等回查：只读，不创建文件夹——找不到就是找不到。 */
    private ProjectFile findLandedFile(Long projectId, String dateStr, String landedName) {
        Optional<ProjectFile> root = projectFileService.getFilesByParent(projectId, null).stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsFolder()) && TRANSFER_ROOT_FOLDER.equals(f.getName()))
                .findFirst();
        if (root.isEmpty()) return null;
        Optional<ProjectFile> day = projectFileService.getFilesByParent(projectId, root.get().getId()).stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsFolder()) && dateStr.equals(f.getName()))
                .findFirst();
        if (day.isEmpty()) return null;
        return projectFileService.getFilesByParent(projectId, day.get().getId()).stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsFolder()) && landedName.equals(f.getName()))
                .findFirst().orElse(null);
    }

    /** 落盘文件名 = 原名 + requestId 前 8 位：既可读，又是幂等回查的锚点（写法照抄 MobileRelayClientService）。 */
    static String landedFileName(String fileName, String requestId) {
        String n = fileName == null ? "" : fileName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        if (n.isEmpty()) n = "file";
        String marker = requestId == null || requestId.length() < 8 ? "00000000" : requestId.substring(0, 8).toLowerCase();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) + "-" + marker + n.substring(dot) : n + "-" + marker;
    }

    private static String extractExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        String ext = name.substring(dot + 1).toLowerCase();
        return ext.length() > 32 ? ext.substring(0, 32) : ext;
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException(LangText.of("请求标识格式不正确", "Invalid request id format"));
        }
    }

    private static void requireDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 64) {
            throw new IllegalArgumentException(LangText.of("缺少设备标识", "Missing device id"));
        }
    }

    private static void requireProjectKey(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalArgumentException(LangText.of("缺少项目标识", "Missing project key"));
        }
    }

    /** 文件名只留最后一段并去掉路径字符，写法同 MobileRelayStoreService.sanitizeFileName。 */
    private static String sanitizeFileName(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        if (n.isEmpty() || ".".equals(n) || "..".equals(n)) {
            throw new IllegalArgumentException(LangText.of("文件名不合法", "Invalid file name"));
        }
        return truncate(n, 512);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
