package com.checkba.service.feedback;

import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.telemetry.InstallIdentityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把云端收件箱里的处理结果拉回本机这一行反馈。
 *
 * <p>为什么要有这个：{@link FeedbackUploadService} 只管把本机反馈送到云端，没有回读——
 * 优化者在维护者机器上处理完，结论只写进云端收件箱那一行，本机这一行的 status 永远停在
 * NEW，用户提交完在自己的浮窗里看不到任何进展。这个服务定时把
 * (status / triageVerdict / prUrl / handledAt) 拉回来，写在本机同一行上：
 * 不新增字段、不新增表，本机这行的字段本来就够表达这些状态。
 *
 * <p>节奏与 {@link FeedbackUploadService} 的补传对齐（30 分钟一轮 + 启动后跑一次）。
 * 地址复用 {@code feedback.upload.url}（同一个云端）拼上 {@code /status}，落在
 * {@link com.checkba.controller.FeedbackIngestController#ingestStatus} 这个新增端点上——
 * 不新增一个容易配歪的地址项。
 *
 * <p>红线：只改状态、绝不删行；拉不到（网络失败/云端还没处理这条）就静默保持原样，
 * 绝不影响用户提交这条主链路。
 */
@Slf4j
@Service
public class FeedbackResolutionSyncService {

    private static final long RETRY_INTERVAL_MS = 30 * 60 * 1000L;
    /** 一轮最多查这么多条待结果的行——与云端 /ingest/status 的 200 上限比是很宽裕的。 */
    private static final int BATCH = 50;

    private final UserFeedbackRepository feedbackRepository;
    private final InstallIdentityService identity;
    private final String statusUrl;
    private final boolean enabled;
    private final Transport transport;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 可注入的传输层（测试替身用），与 {@link FeedbackUploadService.Transport} 同一种写法。 */
    public interface Transport {
        Response post(String url, String jsonBody) throws Exception;
    }

    /** HTTP 状态码 + 响应体：查回执要读 JSON 内容，不像上传那样只看状态码就够。 */
    public record Response(int status, String body) {
    }

    @Autowired
    public FeedbackResolutionSyncService(UserFeedbackRepository feedbackRepository,
                                         InstallIdentityService identity,
                                         @Value("${feedback.upload.enabled:true}") boolean enabled,
                                         @Value("${feedback.upload.url:}") String uploadUrl) {
        this(feedbackRepository, identity, enabled, uploadUrl, null);
    }

    FeedbackResolutionSyncService(UserFeedbackRepository feedbackRepository,
                                  InstallIdentityService identity,
                                  boolean enabled,
                                  String uploadUrl,
                                  Transport transport) {
        this.feedbackRepository = feedbackRepository;
        this.identity = identity;
        this.enabled = enabled;
        this.statusUrl = deriveStatusUrl(uploadUrl);
        this.transport = transport != null ? transport : defaultTransport();
    }

    /** {@code .../api/feedback/ingest} → {@code .../api/feedback/ingest/status}：同一个云端。 */
    private static String deriveStatusUrl(String uploadUrl) {
        String trimmed = uploadUrl == null ? "" : uploadUrl.trim();
        return trimmed.isEmpty() ? "" : trimmed + "/status";
    }

    private static Transport defaultTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (url, jsonBody) -> {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(resp.statusCode(), resp.body());
        };
    }

    public boolean isConfigured() {
        return enabled && !statusUrl.isEmpty();
    }

    @PostConstruct
    public void onStartup() {
        if (!isConfigured()) return;
        Thread t = new Thread(this::sync, "feedback-resolution-sync-init");
        t.setDaemon(true);
        t.start();
    }

    /** 半小时一轮，节奏与 {@link FeedbackUploadService#scheduledFlush} 对齐。 */
    @Scheduled(fixedDelay = RETRY_INTERVAL_MS, initialDelay = RETRY_INTERVAL_MS)
    public void scheduledSync() {
        sync();
    }

    /** 全程静默失败：拉不到就留着原样，下一轮再试，绝不影响用户提交这条主链路。 */
    public void sync() {
        if (!isConfigured()) return;
        try {
            List<UserFeedback> pending = feedbackRepository.findByUploadedTrueAndSourceAndStatusInOrderByIdAsc(
                    UserFeedback.SOURCE_LOCAL,
                    List.of(UserFeedback.STATUS_NEW, UserFeedback.STATUS_FAILED),
                    PageRequest.of(0, BATCH));
            if (pending.isEmpty()) return;
            Map<String, JsonNode> byRef = fetch(pending);
            applyResolutions(pending, byRef);
        } catch (Exception e) {
            log.debug("反馈回执同步失败（静默，下轮再试）: {}", e.toString());
        }
    }

    private Map<String, JsonNode> fetch(List<UserFeedback> pending) throws Exception {
        List<String> clientRefs = new ArrayList<>();
        for (UserFeedback fb : pending) clientRefs.add(String.valueOf(fb.getId()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("installId", identity.installId());
        payload.put("clientRefs", clientRefs);

        Response resp = transport.post(statusUrl, mapper.writeValueAsString(payload));
        if (resp.status() != 200) {
            log.debug("反馈回执查询非 200（{}），留待下轮", resp.status());
            return Map.of();
        }
        JsonNode root = mapper.readTree(resp.body());
        if (root.path("code").asInt(1) != 0) return Map.of();

        Map<String, JsonNode> byRef = new LinkedHashMap<>();
        for (JsonNode item : root.path("data").path("items")) {
            String ref = text(item, "clientRef");
            if (ref != null && !ref.isBlank()) byRef.put(ref, item);
        }
        return byRef;
    }

    private void applyResolutions(List<UserFeedback> pending, Map<String, JsonNode> byRef) {
        for (UserFeedback fb : pending) {
            JsonNode item = byRef.get(String.valueOf(fb.getId()));
            if (item == null) continue; // 云端还没处理这条（或这轮没查到）：原样留着，下轮再拉
            String status = text(item, "status");
            if (status == null || status.isBlank()) continue; // 拿不到有效状态就不动本地这行

            fb.setStatus(status);
            fb.setTriageVerdict(text(item, "triageVerdict"));
            fb.setPrUrl(text(item, "prUrl"));
            String handledAt = text(item, "handledAt");
            if (handledAt != null && !handledAt.isBlank()) {
                try {
                    fb.setHandledAt(LocalDateTime.parse(handledAt));
                } catch (Exception ignored) {
                    // 时间解析失败不影响状态回写，缺一个时间戳而已
                }
            }
            feedbackRepository.save(fb);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
