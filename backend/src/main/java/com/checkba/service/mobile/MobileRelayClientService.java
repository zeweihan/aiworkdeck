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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 桌面侧手机同步客户端（spec：aiworkdeck_mobile docs/specs/2026-08-20-project-sync-relay.md）。
 *
 * <p>账户已连接（本机持有 awdk_）时自动工作，零配置：
 * <ul>
 *   <li>用 awdk_ 到云中转后端换 awdt_ 设备令牌（存 ~/.aiworkdeck/mobile-relay.json，0600）；</li>
 *   <li>周期把本机项目清单 {key, name} 推到云端目录（手机端「选择项目」的数据源）；</li>
 *   <li>轮询中转区取回现场影像，落到项目「现场影像/YYYY-MM-DD/」，落盘成功即 ACK
 *       （云端 ACK 后立即删 blob——落地即删）。</li>
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
            if (resp != null && resp.statusCode() >= 200 && resp.statusCode() < 300) {
                lastPushedHash = hash;
                log.info("手机同步：项目目录已推送（{} 项）", arr.size());
            } else if (resp != null) {
                log.warn("手机同步：目录推送失败 status={}", resp.statusCode());
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
        }
    }

    // ==================== 落盘 ====================

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
        ProjectFile root = ensureFolder(projectId, null, "现场影像", userId);
        ProjectFile day = ensureFolder(projectId, root.getId(), dateStr, userId);

        String landedName = landedFileName(item.path("fileName").asText(),
                item.path("clientMediaId").asText());
        // 幂等：同名已在（上轮落盘成功但 ACK 没发出去）→ 直接补 ACK
        boolean already = projectFileService.getFilesByParent(projectId, day.getId()).stream()
                .anyMatch(f -> !Boolean.TRUE.equals(f.getIsFolder()) && landedName.equals(f.getName()));
        if (!already) {
            String token = currentToken();
            if (token == null) return;
            HttpRequest req = request("/api/mobile/inbox/" + itemId + "/content")
                    .header("X-Session-Id", token).GET().build();
            HttpResponse<InputStream> content = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (content.statusCode() < 200 || content.statusCode() >= 300) {
                log.warn("手机同步：影像 {} 内容下载失败 status={}", itemId, content.statusCode());
                return;
            }
            ProjectFile record = projectFileService.createFile(
                    projectId, day.getId(), landedName,
                    item.path("mediaType").asText("image"),
                    item.path("fileSize").asLong(0),
                    null, null, userId);
            try (InputStream in = content.body()) {
                storageServiceFactory.getStorageService().save(record.getFilePath(), in);
            }
            log.info("手机同步：影像 {} 已落盘 项目={} 文件={}/{}/{}",
                    itemId, projectId, "现场影像", dateStr, landedName);
        }
        HttpResponse<String> ack = authed("POST", "/api/mobile/inbox/" + itemId + "/ack", "{}");
        if (ack == null || ack.statusCode() < 200 || ack.statusCode() >= 300) {
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

    // ==================== 凭据与传输 ====================

    private boolean active() {
        return enabled && localMode && accountService.currentKeyOrNull() != null;
    }

    /** 带令牌出站；401 时作废令牌重新桥接并重试一次。 */
    private HttpResponse<String> authed(String method, String path, String jsonBody) {
        String token = currentToken();
        if (token == null) return null;
        try {
            HttpResponse<String> resp = send(method, path, jsonBody, token);
            if (resp.statusCode() == 401) {
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
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60));
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
