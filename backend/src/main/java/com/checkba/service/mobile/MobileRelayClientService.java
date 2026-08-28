package com.checkba.service.mobile;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.account.AccountService;
import com.checkba.service.LocalIdentityService;
import com.checkba.storage.StorageServiceFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 桌面侧手机同步客户端（spec：aiworkdeck_mobile docs/specs/2026-08-20-project-sync-relay.md）。
 *
 * <p>账户已连接（本机持有 awdk_）时自动工作，零配置：
 * <ul>
 *   <li>用 awdk_ 到云中转后端换 awdt_ 设备令牌（存 ~/.aiworkdeck/mobile-relay.json，0600）；</li>
 *   <li>周期把本机项目清单 {key, name} 推到云端目录（手机端「选择项目」的数据源）；</li>
 *   <li>轮询中转区取回现场影像，落到项目「现场影像/YYYY-MM-DD/」，落盘成功即 ACK
 *       （云端 ACK 后立即删 blob——落地即删）。</li>
 *   <li>跟在同一轮询节奏后响应跨设备文件传输命令（dev-board#251 B 侧）：LIST 回清单、
 *       PULL 把本机文件流式回传、PUSH 把对方投来的文件落到「跨设备文件/YYYY-MM-DD/」；
 *       命中传输往来时进入 5 秒一次的热窗口短轮询，见 {@link #pollTransferCommands()}。</li>
 * </ul>
 *
 * <p>只在 {@code security.local-mode=true}（单机桌面版）活动：云后端与团队服务器
 * 都没有 AccountService 凭据，天然不跑，这里再加一道显式闸。
 *
 * <p>换账号守卫：state 里记录账户指纹，与 {@link AccountService#accountFingerprintOrNull()}
 * 不一致时作废缓存令牌重新桥接——平台 AI key 曾因漏掉这一步把额度记到前一个账号头上
 * （PR#334 的根因），同一形状的坑不踩第二次。
 */
@Service
@Slf4j
public class MobileRelayClientService {

    private final boolean enabled;
    private final boolean localMode;
    private final String baseUrl;
    private final AccountService accountService;
    private final LocalIdentityService localIdentityService;
    private final ProjectRepository projectRepository;
    private final ProjectFileService projectFileService;
    private final StorageServiceFactory storageServiceFactory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path stateFile;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 上次成功推送的目录哈希：清单没变就不重复出站。 */
    private volatile String lastPushedHash;

    private final Object stateLock = new Object();
    private RelayState state;

    /** /transfer/commands 404（旧服务器没有这条路由）：进程内记住不再徒劳打它——服务器
     *  升级后要重启桌面端才恢复，这里权衡为可接受（spec 2.3）。 */
    private volatile boolean transferCommandsUnsupported = false;
    /** 热窗口截止时间戳（epoch millis）：命中传输命令或服务端 hot=true 时顺延。 */
    private volatile long transferHotUntil = 0L;
    /** 防止 pollInbox 的常规调用与热循环自身重入出两条并行循环。 */
    private final AtomicBoolean hotLoopRunning = new AtomicBoolean(false);
    /** 热窗口专用单线程 executor（daemon，惰性创建），不占用 @Scheduled 的调度线程。 */
    private volatile ExecutorService hotExecutor;
    /** 热窗口时长与轮询间隔：包可见非 final，测试用短值覆盖，避免真睡 120 秒。 */
    static long TRANSFER_HOT_WINDOW_MS = 120_000L;
    static long TRANSFER_HOT_POLL_INTERVAL_MS = 5_000L;

    /** 持久化结构：~/.aiworkdeck/mobile-relay.json */
    public static class RelayState {
        public String deviceId;
        public String token;
        public String accountFingerprint;
    }

    @Autowired
    public MobileRelayClientService(
            @Value("${mobile.relay.enabled:true}") boolean enabled,
            @Value("${security.local-mode:false}") boolean localMode,
            @Value("${mobile.relay.base-url:}") String configuredBaseUrl,
            @Value("${ai.account.base-url:https://www.aiworkdeck.com}") String accountBaseUrl,
            @Value("${security.license.dir:${user.home}/.aiworkdeck}") String stateDir,
            AccountService accountService,
            LocalIdentityService localIdentityService,
            ProjectRepository projectRepository,
            ProjectFileService projectFileService,
            StorageServiceFactory storageServiceFactory) {
        this.enabled = enabled;
        this.localMode = localMode;
        // 国际站账户连到国际中转，大陆站连大陆中转——跟着账户走，别让尽调影像跨境
        this.baseUrl = configuredBaseUrl != null && !configuredBaseUrl.isBlank()
                ? configuredBaseUrl.replaceAll("/+$", "")
                : (accountBaseUrl != null && accountBaseUrl.contains("workdeck.ai")
                        ? "https://addin.workdeck.ai" : "https://addin.aiworkdeck.com");
        this.accountService = accountService;
        this.localIdentityService = localIdentityService;
        this.projectRepository = projectRepository;
        this.projectFileService = projectFileService;
        this.storageServiceFactory = storageServiceFactory;
        this.stateFile = Path.of(stateDir, "mobile-relay.json");
    }

    // ==================== 周期任务 ====================

    /** 项目目录推送：启动后 45 秒首推，此后每 10 分钟（清单无变化则跳过出站）。 */
    @Scheduled(initialDelay = 45_000, fixedDelay = 600_000)
    public void pushDirectory() {
        if (!active()) return;
        try {
            Long userId = localIdentityService.localUserId();
            List<Project> projects = projectRepository.findByUserIdOrderByCreatedAtDesc(userId);
            ObjectNode body = mapper.createObjectNode();
            body.put("deviceId", deviceId());
            body.put("deviceName", deviceName());
            ArrayNode arr = body.putArray("projects");
            for (Project p : projects) {
                if (p.getId() == null || p.getName() == null) continue;
                ObjectNode e = arr.addObject();
                e.put("key", String.valueOf(p.getId()));
                e.put("name", p.getName());
            }
            String payload = mapper.writeValueAsString(body);
            String hash = sha256(payload);
            if (hash.equals(lastPushedHash)) return;

            HttpResponse<String> resp = authed("PUT", "/api/mobile/projects", payload);
            if (okEnvelope(resp)) {
                lastPushedHash = hash;
                // 尽调 P3#5：服务端超过 MAX_DIR_ENTRIES 时不再整批拒绝，改成截断 + 明确
                // 报告 truncated——这里必须读出来单独吼一句，不能让"HTTP 2xx = 全部同步
                // 成功"这个默认假设吞掉"其实只同步了前 1000 个"这件事。
                JsonNode respBody = mapper.readTree(resp.body());
                if (respBody.path("truncated").asBoolean(false)) {
                    // 数字全部取服务端的口径（totalCount/count），不与本地 arr.size() 混用——
                    // 服务端截断判断以它收到并落库的条数为准，才是"其实同步了多少"的真相。
                    int total = respBody.path("totalCount").asInt(arr.size());
                    int stored = respBody.path("count").asInt(0);
                    log.warn("手机同步：本机项目数 {} 超过云端目录上限，仅同步了前 {} 个，其余 {} 个未同步到手机端",
                            total, stored, Math.max(0, total - stored));
                } else {
                    log.info("手机同步：项目目录已推送（{} 项）", arr.size());
                }
            } else if (resp != null) {
                log.warn("手机同步：目录推送失败 status={} body={}", resp.statusCode(),
                        resp.body() != null && resp.body().length() > 200
                                ? resp.body().substring(0, 200) : resp.body());
            }
        } catch (Exception e) {
            log.warn("手机同步：目录推送异常（下轮重试）", e);
        }
    }

    /** 影像取件：每 60 秒轮询，逐件落盘 + ACK。任一件失败不影响其余。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void pollInbox() {
        if (!active()) return;
        try {
            HttpResponse<String> resp = authed("GET", "/api/mobile/inbox?deviceId=" + deviceId(), null);
            if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) return;
            JsonNode items = mapper.readTree(resp.body());
            if (!items.isArray() || items.isEmpty()) return;
            for (JsonNode item : items) {
                try {
                    landAndAck(item);
                } catch (Exception e) {
                    log.warn("手机同步：影像 {} 落盘失败（留在中转区下轮重试）",
                            item.path("id").asLong(), e);
                }
            }
        } catch (Exception e) {
            log.warn("手机同步：取件轮询异常（下轮重试）", e);
        } finally {
            // 跨设备文件传输（dev-board#251 B 侧）跟在同一轮询节奏后面，不单独占一个
            // @Scheduled——放 finally 而不是紧跟 try-catch 之后，是因为上面 try 块里
            // 有好几处 return（空收件箱/响应异常），那些 return 会直接跳出整个方法，
            // 不放 finally 的话大多数轮次（收件箱通常是空的）根本轮不到这一句。
            pollTransferCommands();
        }
    }

    // ==================== 落盘 ====================

    /** 归档根目录名，与 buildPhysicalPath 会拼出的物理路径同构，见 landAndAck 内注释。 */
    private static final String MEDIA_ROOT_FOLDER = "现场影像";
    /** 录音（mediaType=audio）落这个根目录——律师找录音不该去翻「现场影像」。 */
    private static final String AUDIO_ROOT_FOLDER = "现场录音";
    /** 跨设备投送（PUSH）落盘根目录名，与「现场影像/现场录音」并列。 */
    private static final String TRANSFER_ROOT_FOLDER = "跨设备文件";

    private void landAndAck(JsonNode item) throws IOException, InterruptedException {
        long itemId = item.path("id").asLong();
        long projectId;
        try {
            projectId = Long.parseLong(item.path("projectKey").asText());
        } catch (NumberFormatException e) {
            log.warn("手机同步：影像 {} 的项目标识无法解析: {}", itemId, item.path("projectKey").asText());
            return;
        }
        Long userId = localIdentityService.localUserId();
        Optional<Project> project = projectRepository.findById(projectId);
        if (project.isEmpty() || !userId.equals(project.get().getUserId())) {
            // 项目已删或不属于本机用户：不 ACK（云端 7 天 TTL 兜底），只告警
            log.warn("手机同步：影像 {} 指向的项目 {} 不存在或不属于本机用户，留置", itemId, projectId);
            return;
        }

        String dateStr = captureDate(item);
        // 根目录按类型分流：音频进「现场录音」，图片/视频照旧进「现场影像」。rootFolder 必须
        // 从 mediaType 稳定推导——它同时参与幂等判据（同名文件查找）与 storagePath 拼接，
        // 跨轮重试两处要落在同一棵目录下。
        String mediaType = item.path("mediaType").asText("image");
        String rootFolderName = "audio".equals(mediaType) ? AUDIO_ROOT_FOLDER : MEDIA_ROOT_FOLDER;
        ProjectFile root = ensureFolder(projectId, null, rootFolderName, userId);
        ProjectFile day = ensureFolder(projectId, root.getId(), dateStr, userId);

        String landedName = landedFileName(item.path("fileName").asText(),
                item.path("clientMediaId").asText());
        // 幂等判据：数据库里有没有同名的 project_file 行。这条判据成立的前提是
        // 「行存在 ⇒ 字节已经真的落好」——下面把 storage.save 挪到 createFile 之前正是为了
        // 保住这个前提，不然中途失败会把"行已落库、字节没写对"的空壳当成已完成，下一轮直接
        // 补 ACK，云端随之删除中转区原件，现场影像永久丢失却显示"已送达"。
        boolean already = projectFileService.getFilesByParent(projectId, day.getId()).stream()
                .anyMatch(f -> !Boolean.TRUE.equals(f.getIsFolder()) && landedName.equals(f.getName()));
        if (!already) {
            String token = currentToken();
            if (token == null) return;
            HttpRequest req = request("/api/mobile/inbox/" + itemId + "/content")
                    .header("X-Session-Id", token).GET().build();
            HttpResponse<InputStream> content = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            // 拒绝响应也是 HTTP 200（JSON 信封）——不验 Content-Type 就落盘，
            // 会把 {"code":4010} 当成照片字节写进项目
            String contentType = content.headers().firstValue("Content-Type").orElse("");
            if (content.statusCode() < 200 || content.statusCode() >= 300
                    || !contentType.startsWith("application/octet-stream")) {
                try (InputStream in = content.body()) { in.transferTo(OutputStream.nullOutputStream()); }
                log.warn("手机同步：影像 {} 内容下载失败 status={} contentType={}",
                        itemId, content.statusCode(), contentType);
                return;
            }

            // 字节先落盘、元数据后落库，顺序不能反：createFile 自带 @Transactional、本类没有
            // 事务包裹它，一旦返回即已提交，此后任何失败都救不回来（旧实现先 createFile 再
            // save，save 抛 IOException 时行已落库、ACK 没发，下一轮却只按"同名文件已在"的
            // 幂等判据误判成功直接补 ACK）。storagePath 与 buildPhysicalPath 会算出的路径同构
            // （两层目录名就是上面 ensureFolder 用的 rootFolderName 与 dateStr），显式传给
            // createFile 而不是留空自动推导，为的是下面这行 save 能在建库之前先拿到确定的
            // 存储 key；createFile 内部 createFromTemplate 见文件已存在会直接跳过，不会用
            // 模板覆盖已经落好的真内容。真落盘失败（磁盘满/网络中断）时异常在 createFile 之前
            // 抛出，本轮不落一条库记录，下一轮幂等判据自然判定"未落地"、重新下载，
            // 不需要额外的补偿删除。
            String storagePath = String.format("projects/%d/%s/%s/%s",
                    projectId, rootFolderName, dateStr, landedName);
            try (InputStream in = content.body()) {
                storageServiceFactory.getStorageService().save(storagePath, in);
            }
            ProjectFile record = projectFileService.createFile(
                    projectId, day.getId(), landedName,
                    mediaType,
                    item.path("fileSize").asLong(0),
                    storagePath, null, userId);
            // storagePath 是照着 ProjectFileService.buildPhysicalPath 的格式手拼的，
            // 两边一旦被改得对不上，字节就存在一个谁也不去读的位置、而行指向一个空路径——
            // 那是无声失败。这里对一次账，失配就吼出来（字节已经写完，救不回来，
            // 但至少不让它悄悄发生）。
            if (record != null && !storagePath.equals(record.getFilePath())) {
                log.error("手机同步：存储路径与文件行不一致，影像可能读不出来。saved={} row={}",
                        storagePath, record.getFilePath());
            }
            log.info("手机同步：影像 {} 已落盘 项目={} 文件={}/{}/{}",
                    itemId, projectId, rootFolderName, dateStr, landedName);
        }
        HttpResponse<String> ack = authed("POST", "/api/mobile/inbox/" + itemId + "/ack", "{}");
        if (!okEnvelope(ack)) {
            log.warn("手机同步：影像 {} ACK 失败（已落盘，下轮按同名幂等补发）", itemId);
        }
    }

    private ProjectFile ensureFolder(Long projectId, Long parentId, String name, Long userId) {
        Optional<ProjectFile> existing = projectFileService.getFilesByParent(projectId, parentId).stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsFolder()) && name.equals(f.getName()))
                .findFirst();
        return existing.orElseGet(() -> projectFileService.createFolder(projectId, parentId, name, userId));
    }

    /** 落盘文件名 = 原名 + clientMediaId 前 8 位：既可读，又是跨轮重试的幂等锚点。 */
    static String landedFileName(String fileName, String clientMediaId) {
        String n = fileName == null ? "" : fileName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        if (n.isEmpty()) n = "media";
        String marker = clientMediaId == null || clientMediaId.length() < 8
                ? "00000000" : clientMediaId.substring(0, 8).toLowerCase();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) + "-" + marker + n.substring(dot) : n + "-" + marker;
    }

    /** 归档日期：优先拍摄时间，缺失用中转区入库时间，再缺用今天（桌面机本地时区）。 */
    static String captureDate(JsonNode item) {
        for (String field : new String[]{"capturedAt", "createdAt"}) {
            String v = item.path(field).asText(null);
            if (v != null && !v.isBlank()) {
                try {
                    return LocalDateTime.parse(v).toLocalDate().toString();
                } catch (Exception ignored) {
                    // 归档日期是尽力而为的元数据，解析不了就退下一级
                }
            }
        }
        return LocalDate.now().toString();
    }

    // ==================== 跨设备文件传输（dev-board#251 B 侧） ====================

    /**
     * 传输命令轮询：跟在 pollInbox 每轮末尾；命中热窗口后由独立线程以短间隔连续追加调用
     * （见 {@link #enterHotWindow()}）。逐条处理，单条失败不影响其余。
     */
    void pollTransferCommands() {
        if (!active()) return;
        if (transferCommandsUnsupported) return;
        try {
            HttpResponse<String> resp = authed("GET",
                    "/api/mobile/transfer/commands?deviceId=" + deviceId(), null);
            if (resp == null) return;
            if (resp.statusCode() == 404) {
                transferCommandsUnsupported = true;
                log.info("手机同步：服务器未开通跨设备传输（/transfer/commands 404），"
                        + "本次运行不再轮询（服务器升级后重启桌面端恢复）");
                return;
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return;
            JsonNode body = mapper.readTree(resp.body());
            JsonNode commands = body.path("commands");
            boolean handledAny = false;
            if (commands.isArray()) {
                for (JsonNode cmd : commands) {
                    try {
                        handleTransferCommand(cmd);
                    } catch (Exception e) {
                        log.warn("手机同步：传输命令 {} 处理失败（不影响其余命令）",
                                cmd.path("id").asLong(), e);
                    }
                    handledAny = true;
                }
            }
            if (body.path("hot").asBoolean(false) || handledAny) {
                enterHotWindow();
            }
        } catch (Exception e) {
            log.warn("手机同步：传输命令轮询异常（下轮重试）", e);
        }
    }

    private void handleTransferCommand(JsonNode cmd) throws IOException, InterruptedException {
        long id = cmd.path("id").asLong();
        String kind = cmd.path("kind").asText("");
        switch (kind) {
            case "LIST":
                handleListCommand(id, cmd);
                break;
            case "PULL":
                handlePullCommand(id, cmd);
                break;
            case "PUSH":
                handlePushCommand(id, cmd);
                break;
            default:
                // 前向兼容：服务端以后加新 kind，旧桌面端不该炸，跳过就好
                log.debug("手机同步：未知传输命令类型 {}（id={}），跳过", kind, id);
        }
    }

    /** LIST：组本机项目的文件清单（不含文件夹）上报；项目不存在/不属本机用户则 /fail。 */
    private void handleListCommand(long id, JsonNode cmd) {
        Long userId = localIdentityService.localUserId();
        Long projectId = parseLongOrNull(cmd.path("projectKey").asText());
        Project project = projectId == null ? null : projectRepository.findById(projectId).orElse(null);
        if (project == null || !userId.equals(project.getUserId())) {
            failTransfer(id, "项目不存在或已删除");
            return;
        }
        List<ProjectFile> tree = projectFileService.getFileTree(projectId);
        Map<Long, ProjectFile> byId = new HashMap<>();
        for (ProjectFile f : tree) {
            if (f.getId() != null) byId.put(f.getId(), f);
        }
        ObjectNode body = mapper.createObjectNode();
        ArrayNode files = body.putArray("files");
        int count = 0;
        for (ProjectFile f : tree) {
            if (Boolean.TRUE.equals(f.getIsFolder())) continue;
            if (count >= 2000) break; // 服务端也会截断，这里提前止损，别白传超出部分
            ObjectNode e = files.addObject();
            e.put("id", String.valueOf(f.getId()));
            e.put("name", f.getName());
            e.put("path", listEntryPath(f, byId));
            e.put("size", f.getFileSize() == null ? 0L : f.getFileSize());
            count++;
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("手机同步：传输命令 {} 清单序列化失败", id, e);
            return;
        }
        HttpResponse<String> resp = authed("POST", "/api/mobile/transfer/" + id + "/files", payload);
        if (!okEnvelope(resp)) {
            log.warn("手机同步：传输命令 {} 清单上报失败", id);
        }
    }

    /** 清单条目的 path：按 parentId 逐级拼文件夹名以 "/" 连接，根下就是文件名。 */
    private static String listEntryPath(ProjectFile file, Map<Long, ProjectFile> byId) {
        Deque<String> segments = new ArrayDeque<>();
        segments.addFirst(file.getName());
        Long parentId = file.getParentId();
        int depth = 0;
        while (parentId != null && depth++ < 32) {
            ProjectFile parent = byId.get(parentId);
            if (parent == null) break;
            segments.addFirst(parent.getName());
            parentId = parent.getParentId();
        }
        return String.join("/", segments);
    }

    /**
     * PULL：把本机 remoteFileId 对应的文件流式回传。校验不过（文件/项目不存在或不属本机
     * 用户、文件与命令所在项目不一致）是确定性失败 → /fail；网络/IO 失败（含读盘失败）
     * 不报 fail，留待下轮重试——两者不能混为一谈，否则瞬态故障会被误判成永久失败退款。
     */
    private void handlePullCommand(long id, JsonNode cmd) {
        Long userId = localIdentityService.localUserId();
        Long projectId = parseLongOrNull(cmd.path("projectKey").asText());
        Long remoteFileId = parseLongOrNull(cmd.path("remoteFileId").asText());
        ProjectFile file = null;
        if (remoteFileId != null) {
            try {
                file = projectFileService.getFile(remoteFileId);
            } catch (Exception e) {
                file = null;
            }
        }
        Project project = projectId == null ? null : projectRepository.findById(projectId).orElse(null);
        boolean valid = file != null && project != null && userId.equals(project.getUserId())
                && projectId.equals(file.getProjectId()) && !Boolean.TRUE.equals(file.getIsFolder());
        if (!valid) {
            failTransfer(id, "文件不存在或已移动");
            return;
        }
        String fileName = cmd.path("fileName").asText(file.getName());
        try {
            var resource = storageServiceFactory.getStorageService().load(file.getFilePath());
            try (InputStream in = resource.getInputStream()) {
                uploadPulledFile(id, fileName, in);
            }
        } catch (Exception e) {
            log.warn("手机同步：传输命令 {} 拉取上传失败，留待下轮重试", id, e);
        }
    }

    /** PULL 上传：60 秒的默认超时不够传 200MB，单独给 10 分钟；multipart 真流式，不缓冲整份文件。 */
    private void uploadPulledFile(long id, String fileName, InputStream fileStream)
            throws IOException, InterruptedException {
        String token = currentToken();
        if (token == null) return;
        String boundary = MultipartBody.newBoundary();
        HttpRequest req = request("/api/mobile/transfer/" + id + "/upload", Duration.ofMinutes(10))
                .header("X-Session-Id", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(MultipartBody.filePublisher(boundary, "file", fileName, fileStream))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (authRejected(resp)) {
            // 文件流已经读过一次没法重放：作废令牌，交给下一轮用新令牌重新发起整条 PULL
            invalidateToken();
            log.warn("手机同步：传输命令 {} 上传鉴权被拒，令牌已作废，下轮重新拉取", id);
            return;
        }
        if (!okEnvelope(resp)) {
            log.warn("手机同步：传输命令 {} 上传响应异常 status={}", id, resp.statusCode());
        }
    }

    /**
     * PUSH：下载对方投来的内容，落到本机 projectKey 对应项目「跨设备文件/YYYY-MM-DD/」。
     * 与 landAndAck 的现场影像不同：PUSH 有退款通道，项目确定不存在要 /fail 触发云端退款，
     * 不能像 media 那样只告警留置。
     */
    private void handlePushCommand(long id, JsonNode cmd) throws IOException, InterruptedException {
        Long userId = localIdentityService.localUserId();
        Long projectId = parseLongOrNull(cmd.path("projectKey").asText());
        Project project = projectId == null ? null : projectRepository.findById(projectId).orElse(null);
        if (project == null || !userId.equals(project.getUserId())) {
            failTransfer(id, "项目不存在或已删除");
            return;
        }
        String token = currentToken();
        if (token == null) return;
        HttpResponse<InputStream> content;
        try {
            HttpRequest req = request("/api/mobile/transfer/" + id + "/content", Duration.ofMinutes(10))
                    .header("X-Session-Id", token).GET().build();
            content = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.warn("手机同步：传输命令 {} 内容下载异常，留待下轮重试", id, e);
            return;
        }
        // 拒绝响应也是 HTTP 200（JSON 信封）——不验 Content-Type 就落盘，同 landAndAck 的地雷
        String contentType = content.headers().firstValue("Content-Type").orElse("");
        if (content.statusCode() < 200 || content.statusCode() >= 300
                || !contentType.startsWith("application/octet-stream")) {
            try (InputStream in = content.body()) { in.transferTo(OutputStream.nullOutputStream()); }
            log.warn("手机同步：传输命令 {} 内容下载失败 status={} contentType={}",
                    id, content.statusCode(), contentType);
            return;
        }

        String fileName = landedPushFileName(cmd.path("fileName").asText("file"), id);
        String dateStr = LocalDate.now().toString();
        ProjectFile root = ensureFolder(projectId, null, TRANSFER_ROOT_FOLDER, userId);
        ProjectFile day = ensureFolder(projectId, root.getId(), dateStr, userId);

        // 幂等判据同 landAndAck：同 parent 下同名已在 = 上轮字节已经落好，只是 ACK 丢了
        boolean already = projectFileService.getFilesByParent(projectId, day.getId()).stream()
                .anyMatch(f -> !Boolean.TRUE.equals(f.getIsFolder()) && fileName.equals(f.getName()));
        if (!already) {
            // 字节先落盘、元数据后落库，顺序同 landAndAck：createFile 一旦提交救不回来，
            // 落盘失败必须在建库之前发生，下一轮才能按幂等判据正确判定"未落地"重新下载。
            String storagePath = String.format("projects/%d/%s/%s/%s",
                    projectId, TRANSFER_ROOT_FOLDER, dateStr, fileName);
            try (InputStream in = content.body()) {
                storageServiceFactory.getStorageService().save(storagePath, in);
            } catch (Exception e) {
                log.warn("手机同步：传输命令 {} 落盘失败，留待下轮重试", id, e);
                return;
            }
            String ext = "";
            int dot = fileName.lastIndexOf('.');
            if (dot > 0 && dot < fileName.length() - 1) ext = fileName.substring(dot + 1).toLowerCase();
            projectFileService.createFile(projectId, day.getId(), fileName, ext,
                    cmd.path("fileSize").asLong(0), storagePath, null, userId);
            log.info("手机同步：投送命令 {} 已落盘 项目={} 文件={}/{}/{}",
                    id, projectId, TRANSFER_ROOT_FOLDER, dateStr, fileName);
        } else {
            try (InputStream in = content.body()) { in.transferTo(OutputStream.nullOutputStream()); }
        }

        HttpResponse<String> ack = authed("POST", "/api/mobile/transfer/" + id + "/ack", "{}");
        if (!okEnvelope(ack)) {
            log.warn("手机同步：传输命令 {} ACK 失败（已落盘，下轮按同名幂等补 ACK）", id);
        }
    }

    /** PUSH 落盘文件名 = 原名 + t<命令 id>：命令没有 requestId，命令 id 本身就是稳定的跨轮幂等锚点。 */
    static String landedPushFileName(String fileName, long commandId) {
        String n = fileName == null ? "" : fileName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        if (n.isEmpty()) n = "file";
        String marker = "t" + commandId;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) + "-" + marker + n.substring(dot) : n + "-" + marker;
    }

    /** B 报确定性失败（文件/项目不存在）：触发云端 FAILED + 退款（如已扣）。 */
    private void failTransfer(long id, String message) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("message", message);
            HttpResponse<String> resp = authed("POST", "/api/mobile/transfer/" + id + "/fail",
                    mapper.writeValueAsString(body));
            if (!okEnvelope(resp)) {
                log.warn("手机同步：传输命令 {} 上报失败也没能成功（下轮重试）", id);
            }
        } catch (Exception e) {
            log.warn("手机同步：传输命令 {} 上报失败异常", id, e);
        }
    }

    private static Long parseLongOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 命中传输往来（服务端 hot=true 或本轮处理过任意命令）：顺延热窗口截止时间，
     * 并确保热循环线程在跑。AtomicBoolean 防重入——循环自身调用 pollTransferCommands 时
     * 再次命中热窗口只会顺延 transferHotUntil，不会再启动第二条循环。
     */
    private void enterHotWindow() {
        transferHotUntil = System.currentTimeMillis() + TRANSFER_HOT_WINDOW_MS;
        if (hotLoopRunning.compareAndSet(false, true)) {
            ensureHotExecutor().submit(this::runHotLoop);
        }
    }

    private synchronized ExecutorService ensureHotExecutor() {
        if (hotExecutor == null) {
            hotExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mobile-transfer-hot-poll");
                t.setDaemon(true);
                return t;
            });
        }
        return hotExecutor;
    }

    private void runHotLoop() {
        try {
            while (true) {
                try {
                    Thread.sleep(TRANSFER_HOT_POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!active() || System.currentTimeMillis() >= transferHotUntil) return;
                pollTransferCommands();
            }
        } finally {
            hotLoopRunning.set(false);
        }
    }

    // ==================== 凭据与传输 ====================

    private boolean active() {
        return enabled && localMode && accountService.currentKeyOrNull() != null;
    }

    /** 带令牌出站；鉴权失败时作废令牌重新桥接并重试一次。 */
    private HttpResponse<String> authed(String method, String path, String jsonBody) {
        String token = currentToken();
        if (token == null) return null;
        try {
            HttpResponse<String> resp = send(method, path, jsonBody, token);
            if (authRejected(resp)) {
                invalidateToken();
                token = currentToken();
                if (token == null) return resp;
                return send(method, path, jsonBody, token);
            }
            return resp;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("手机同步：出站失败 {} {}", method, path, e);
            return null;
        }
    }

    /**
     * 鉴权被拒的两种形态都要认：裸 401，以及全站惯例的「HTTP 200 + code 4010 信封」
     * （GlobalExceptionHandler 对 UnauthorizedException 统一返回 200）。只看 401 的话，
     * 令牌失效后目录推送会被当成功、永不重桥接——上线冒烟时抓到的真形态。
     */
    private boolean authRejected(HttpResponse<String> resp) {
        if (resp.statusCode() == 401) return true;
        String body = resp.body();
        if (body == null || !body.startsWith("{")) return false;
        try {
            return mapper.readTree(body).path("code").asInt(0)
                    == com.checkba.config.GlobalExceptionHandler.CODE_UNAUTHENTICATED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 业务成功 = HTTP 2xx 且（无信封 code 或 code==0）。4010 拒绝也是 200，光看状态码会误判。 */
    private boolean okEnvelope(HttpResponse<String> resp) {
        if (resp == null || resp.statusCode() < 200 || resp.statusCode() >= 300) return false;
        String body = resp.body();
        if (body == null || !body.startsWith("{")) return true;
        try {
            return mapper.readTree(body).path("code").asInt(0) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpResponse<String> send(String method, String path, String jsonBody, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = request(path);
        b.header("X-Session-Id", token);
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return request(path, Duration.ofSeconds(60));
    }

    /** 自定义超时的重载：PULL 上传 / PUSH 内容下载最大 200MB，60 秒默认超时不够用。 */
    private HttpRequest.Builder request(String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout);
    }

    /** 取当前令牌；没有或账户已换人则（重新）桥接。 */
    private String currentToken() {
        synchronized (stateLock) {
            RelayState s = loadState();
            String fingerprint = accountService.accountFingerprintOrNull();
            if (s.token != null && fingerprint != null && fingerprint.equals(s.accountFingerprint)) {
                return s.token;
            }
            return bridgeLocked(s, fingerprint);
        }
    }

    private void invalidateToken() {
        synchronized (stateLock) {
            RelayState s = loadState();
            s.token = null;
            s.accountFingerprint = null;
            saveState(s);
        }
    }

    private String bridgeLocked(RelayState s, String fingerprint) {
        String key = accountService.currentKeyOrNull();
        if (key == null) return null;
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("key", key);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/awdk-login"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300 && json.path("code").asInt(-1) == 0) {
                s.token = json.path("data").path("token").asText(null);
                s.accountFingerprint = fingerprint;
                saveState(s);
                log.info("手机同步：已桥接到 {}", baseUrl);
                return s.token;
            }
            log.warn("手机同步：桥接失败 status={} message={}", resp.statusCode(),
                    json.path("message").asText(""));
            return null;
        } catch (Exception e) {
            log.warn("手机同步：桥接异常", e);
            return null;
        }
    }

    // ==================== 状态文件 ====================

    String deviceId() {
        synchronized (stateLock) {
            return loadState().deviceId;
        }
    }

    private String deviceName() {
        String host = System.getenv().getOrDefault("HOSTNAME", "");
        if (host.isBlank()) {
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                host = "Desktop";
            }
        }
        return host.length() > 128 ? host.substring(0, 128) : host;
    }

    private RelayState loadState() {
        if (state != null) return state;
        RelayState s = null;
        try {
            if (Files.exists(stateFile)) {
                s = mapper.readValue(Files.readAllBytes(stateFile), RelayState.class);
            }
        } catch (Exception e) {
            log.warn("手机同步：状态文件损坏，重建 {}", stateFile, e);
        }
        if (s == null) s = new RelayState();
        if (s.deviceId == null || s.deviceId.isBlank()) {
            s.deviceId = UUID.randomUUID().toString();
            saveState(s);
        }
        state = s;
        return s;
    }

    private void saveState(RelayState s) {
        try {
            Files.createDirectories(stateFile.getParent());
            Files.write(stateFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(s));
            AccountService.restrictPermissions(stateFile);
            state = s;
        } catch (IOException e) {
            log.warn("手机同步：状态文件写入失败 {}", stateFile, e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
