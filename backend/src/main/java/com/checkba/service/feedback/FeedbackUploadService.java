package com.checkba.service.feedback;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.telemetry.InstallIdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把本机的反馈送到云端收件箱。
 *
 * <p><b>为什么是「先落本地再异步上传」而不是直接发云端：</b>反馈恰恰是在出问题的时候提交的——
 * 断网、后端刚崩、服务器正好在重启，都是它最该被记下来的时刻。先落本地保证一条都不丢，
 * 上传失败就留着下轮补传（节奏抄 {@link com.checkba.service.telemetry.TelemetryUploadService}）。
 *
 * <p>幂等靠 installId + 本机行 id：重传在云端不会变成两条。
 * 上传成功只置 uploaded 标记，**本地那条永远不删**——用户在自己的后台里还要能看见自己报过什么。
 */
@Slf4j
@Service
public class FeedbackUploadService {

    private static final long RETRY_INTERVAL_MS = 30 * 60 * 1000L;
    private static final int BATCH = 10;

    private final UserFeedbackRepository feedbackRepository;
    private final FeedbackService feedbackService;
    private final InstallIdentityService identity;
    private final String uploadUrl;
    private final boolean enabled;
    private final Transport transport;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 可注入的传输层（测试替身用）。 */
    public interface Transport {
        /** @return 状态码与响应体。**响应体必须带回来**：收件端对业务失败也回 HTTP 200 + {"code":1}，
         *  只看状态码就会把「云端拒收」读成「已收下」。 */
        Response post(String url, String contentType, byte[] body) throws Exception;

        record Response(int status, String body) {}
    }

    @Autowired
    public FeedbackUploadService(UserFeedbackRepository feedbackRepository,
                                 FeedbackService feedbackService,
                                 InstallIdentityService identity,
                                 @Value("${feedback.upload.enabled:true}") boolean enabled,
                                 @Value("${feedback.upload.url:}") String uploadUrl) {
        this(feedbackRepository, feedbackService, identity, enabled, uploadUrl, null);
    }

    FeedbackUploadService(UserFeedbackRepository feedbackRepository,
                          FeedbackService feedbackService,
                          InstallIdentityService identity,
                          boolean enabled,
                          String uploadUrl,
                          Transport transport) {
        this.feedbackRepository = feedbackRepository;
        this.feedbackService = feedbackService;
        this.identity = identity;
        this.enabled = enabled;
        this.uploadUrl = uploadUrl == null ? "" : uploadUrl.trim();
        this.transport = transport != null ? transport : defaultTransport();
    }

    private static Transport defaultTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (url, contentType, body) -> {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(60))
                            .header("Content-Type", contentType)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Transport.Response(resp.statusCode(), resp.body());
        };
    }

    public boolean isConfigured() {
        return enabled && !uploadUrl.isEmpty();
    }

    @PostConstruct
    public void onStartup() {
        if (!isConfigured()) return;
        Thread t = new Thread(this::flush, "feedback-upload-init");
        t.setDaemon(true);
        t.start();
    }

    /** 半小时补传一次；提交一条新反馈后也会立刻触发一次（见 FeedbackController）。 */
    @Scheduled(fixedDelay = RETRY_INTERVAL_MS, initialDelay = RETRY_INTERVAL_MS)
    public void scheduledFlush() {
        flush();
    }

    /** 异步触发（提交后立刻走一次，别让用户等网络）。 */
    public void flushAsync() {
        if (!isConfigured()) return;
        Thread t = new Thread(this::flush, "feedback-upload");
        t.setDaemon(true);
        t.start();
    }

    /** 全程静默失败：上传不成功只是留着下轮补传，绝不影响用户提交。 */
    public void flush() {
        if (!isConfigured()) return;
        try {
            List<UserFeedback> pending = feedbackRepository.findByUploadedFalseAndSourceOrderByIdAsc(
                    UserFeedback.SOURCE_LOCAL, PageRequest.of(0, BATCH));
            for (UserFeedback fb : pending) {
                try {
                    if (upload(fb)) feedbackService.markUploaded(fb);
                } catch (Exception e) {
                    log.debug("反馈 #{} 上传失败（下轮补传）: {}", fb.getId(), e.toString());
                }
            }
        } catch (Exception e) {
            log.debug("反馈上传轮次失败（静默）: {}", e.toString());
        }
    }

    private boolean upload(UserFeedback fb) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("installId", identity.installId());
        payload.put("clientRef", String.valueOf(fb.getId()));
        payload.put("kind", fb.getKind());
        payload.put("text", fb.getText());
        payload.put("voiceTranscript", fb.getVoiceTranscript());
        payload.put("page", fb.getPage());
        payload.put("appVersion", fb.getAppVersion());
        payload.put("platform", fb.getPlatform());
        payload.put("contextJson", fb.getContextJson());

        String boundary = "----awdup" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, boundary, "payload", mapper.writeValueAsString(payload));
        for (FeedbackAttachment a : feedbackService.attachmentsOf(fb.getId())) {
            Path p = feedbackService.attachmentPath(fb.getId(), a.getStoredName());
            if (!Files.isRegularFile(p)) {
                // 附件读不到就静默跳过，会让纯语音/纯截图那条反馈发出一个空 multipart，
                // 云端按「空反馈」拒收。留一条 WARN，否则这条反馈为什么一直传不上去
                // 在日志里查不到任何线索。
                log.warn("反馈 #{} 的附件 {} 不在磁盘上，本次上传将不含它", fb.getId(), a.getStoredName());
                continue;
            }
            writeFile(out, boundary, "files", a.getStoredName(),
                    a.getContentType() == null ? "application/octet-stream" : a.getContentType(),
                    Files.readAllBytes(p));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        Transport.Response resp = transport.post(uploadUrl, "multipart/form-data; boundary=" + boundary, out.toByteArray());
        int status = resp.status();
        if (status == 429) {
            // 配额挡下的：标成已上传只会让这条永远消失，宁可下轮再试
            log.debug("反馈 #{} 被云端限流，留待下轮", fb.getId());
            return false;
        }
        if (status < 200 || status >= 300) {
            return false;
        }
        // 收件端对业务失败也回 HTTP 200 + {"code":1}（controller 里多处
        // ResponseEntity.ok(error(...))，例如附件读不到导致正文与附件全空时的「空反馈」）。
        // 只看状态码就把「云端拒收」读成「已收下」，markUploaded 永久置位——
        // 这条反馈既不会重传也进不了优化者队列，而用户的「我的反馈」显示已送达。
        if (isRejectedEnvelope(resp.body())) {
            log.debug("反馈 #{} 被云端拒收（信封 code 非 0），留待下轮: {}", fb.getId(), abbreviate(resp.body()));
            return false;
        }
        return true;
    }

    /** 信封里 code 非 0 即视为未收下。解析不出来的按「收下了」处理，别让格式变化把正常上传卡死。 */
    private boolean isRejectedEnvelope(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode code = node.get("code");
            return code != null && code.isNumber() && code.asInt() != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name, String value)
            throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(ByteArrayOutputStream out, String boundary, String name,
                                  String filename, String contentType, byte[] bytes) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
