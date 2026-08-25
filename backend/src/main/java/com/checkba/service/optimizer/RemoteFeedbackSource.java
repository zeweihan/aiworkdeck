package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读云端收件箱（{@code /api/feedback/pending}），把结论回执给它（{@code /{id}/resolution}）。
 *
 * <p>取回来的 {@link UserFeedback} 是**游离对象**：id 是云端那条的 id，不进本地库，
 * 也不该被本地 repository 保存——本类的 {@link #save} 走 HTTP 回执。
 * 附件也一样，只带清单（类型/大小），字节留在云端，邮件里给的是可直接打开的 URL。
 */
@Slf4j
public class RemoteFeedbackSource implements OptimizerFeedbackSource {

    private final String baseUrl;
    private final String token;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public RemoteFeedbackSource(String baseUrl, String token) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/$", "");
        this.token = token == null ? "" : token.trim();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 附件清单随 pending 一起取回，按反馈 id 暂存，供本轮取用。 */
    private final Map<Long, List<FeedbackAttachment>> attachmentCache = new HashMap<>();

    @Override
    public List<UserFeedback> pending(int limit, int maxAttempts) {
        try {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(
                                    baseUrl + "/api/feedback/pending?limit=" + limit + "&maxAttempts=" + maxAttempts))
                            .timeout(Duration.ofSeconds(30))
                            .header("X-Optimizer-Token", token)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("取件失败 HTTP " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            if (root.path("code").asInt(1) != 0) {
                throw new IllegalStateException("取件被拒: " + root.path("message").asText(""));
            }
            List<UserFeedback> out = new ArrayList<>();
            attachmentCache.clear();
            for (JsonNode n : root.path("data").path("items")) {
                UserFeedback fb = new UserFeedback();
                fb.setId(n.path("id").asLong());
                fb.setKind(text(n, "kind"));
                fb.setText(text(n, "text"));
                fb.setVoiceTranscript(text(n, "voiceTranscript"));
                fb.setPage(text(n, "page"));
                fb.setAppVersion(text(n, "appVersion"));
                fb.setPlatform(text(n, "platform"));
                fb.setContextJson(text(n, "contextJson"));
                fb.setAttempts(n.path("attempts").asInt(0));
                fb.setStatus(UserFeedback.STATUS_NEW);
                fb.setSource(UserFeedback.SOURCE_CLOUD);
                String created = text(n, "createdAt");
                if (created != null && !created.isBlank()) {
                    try {
                        fb.setCreatedAt(LocalDateTime.parse(created));
                    } catch (Exception ignored) {
                        // 时间解析失败不影响处理，邮件里少一行而已
                    }
                }
                List<FeedbackAttachment> atts = new ArrayList<>();
                for (JsonNode an : n.path("attachments")) {
                    FeedbackAttachment a = new FeedbackAttachment();
                    a.setId(an.path("id").asLong());
                    a.setFeedbackId(fb.getId());
                    a.setType(text(an, "type"));
                    a.setStoredName(text(an, "name"));
                    a.setSizeBytes(an.path("sizeBytes").asLong(0));
                    atts.add(a);
                }
                attachmentCache.put(fb.getId(), atts);
                out.add(fb);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("读云端收件箱失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<FeedbackAttachment> attachmentsOf(UserFeedback fb) {
        return attachmentCache.getOrDefault(fb.getId(), List.of());
    }

    @Override
    public void save(UserFeedback fb) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", fb.getStatus());
        body.put("triageVerdict", fb.getTriageVerdict());
        body.put("triageJson", fb.getTriageJson());
        body.put("prUrl", fb.getPrUrl());
        body.put("lastError", fb.getLastError());
        body.put("attempts", fb.getAttempts());
        try {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/feedback/" + fb.getId() + "/resolution"))
                            .timeout(Duration.ofSeconds(30))
                            .header("X-Optimizer-Token", token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("回执失败 HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            // 回执写不回去是要命的：这条会被下一轮再捞出来重跑一遍（可能再开一个 PR）。
            // 所以这里不吞异常，让本条按失败计入战报，维护者能从状态接口看见。
            throw new IllegalStateException("回执云端失败 feedback#" + fb.getId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String attachmentRef(UserFeedback fb, FeedbackAttachment a) {
        return baseUrl + "/api/feedback/" + fb.getId() + "/attachment/" + a.getId();
    }

    @Override
    public String consoleRef(UserFeedback fb) {
        return baseUrl + "/feedback-console/?fb=" + fb.getId();
    }

    @Override
    public String describe() {
        return "云端收件箱 " + baseUrl;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
