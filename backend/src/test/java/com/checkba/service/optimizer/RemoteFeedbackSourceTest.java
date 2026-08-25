package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.feedback.FeedbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 优化者跑在维护者机器上、收件箱在云上——这条 HTTP 缝的两端契约。
 * 用真的 HttpServer 起一个假收件箱，比 mock 更能挡住"字段名改了没人发现"。
 */
class RemoteFeedbackSourceTest {

    HttpServer server;
    String base;
    final List<String> requests = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();
    String pendingJson = "{\"code\":0,\"data\":{\"items\":[]}}";
    int pendingStatus = 200;
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/feedback/pending", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI()
                    + " token=" + ex.getRequestHeaders().getFirst("X-Optimizer-Token"));
            byte[] out = pendingJson.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(pendingStatus, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.createContext("/api/feedback/", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI()
                    + " token=" + ex.getRequestHeaders().getFirst("X-Optimizer-Token"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"code\":0,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void pendingIsMappedIntoDetachedFeedbackWithAttachments() {
        pendingJson = """
                {"code":0,"data":{"items":[
                  {"id":77,"kind":"BUG","text":"点保存没反应","voiceTranscript":null,
                   "page":"pages/project-overview/project-overview","appVersion":"0.13.0",
                   "platform":"Windows 11","contextJson":"{}","attempts":1,
                   "createdAt":"2026-08-08T10:00:00",
                   "attachments":[{"id":3,"type":"IMAGE","name":"img-1.png","sizeBytes":1234}]}
                ]}}""";
        RemoteFeedbackSource src = new RemoteFeedbackSource(base, "tok");

        List<UserFeedback> rows = src.pending(5, 3);

        assertEquals(1, rows.size());
        UserFeedback fb = rows.get(0);
        assertEquals(77L, fb.getId(), "id 必须是云端那条的 id，回执要靠它");
        assertEquals("点保存没反应", fb.getText());
        assertEquals("0.13.0", fb.getAppVersion());
        assertEquals(1, fb.getAttempts());
        assertEquals(UserFeedback.SOURCE_CLOUD, fb.getSource());
        List<FeedbackAttachment> atts = src.attachmentsOf(fb);
        assertEquals(1, atts.size());
        assertEquals(FeedbackAttachment.TYPE_IMAGE, atts.get(0).getType());
        assertTrue(requests.get(0).contains("token=tok"));
        assertTrue(requests.get(0).contains("limit=5"));
    }

    @Test
    void attachmentRefIsAnOpenableUrl() {
        RemoteFeedbackSource src = new RemoteFeedbackSource(base + "/", "tok");
        UserFeedback fb = new UserFeedback();
        fb.setId(12L);
        FeedbackAttachment a = new FeedbackAttachment();
        a.setId(3L);
        assertEquals(base + "/api/feedback/12/attachment/3", src.attachmentRef(fb, a));
    }

    /** 官网 admin 并入反馈看板后（dev-board#152），邮件直达地址可用模板改指官网。 */
    @Test
    void consoleRefUsesTemplateWhenConfigured() {
        UserFeedback fb = new UserFeedback();
        fb.setId(12L);
        // 默认：云端反馈控制台
        assertEquals(base + "/feedback-console/?fb=12",
                new RemoteFeedbackSource(base, "tok").consoleRef(fb));
        // 配了模板：{id} 被替换
        RemoteFeedbackSource custom = new RemoteFeedbackSource(base, "tok",
                "https://www.aiworkdeck.com/zh/admin?tab=feedback&fb={id}");
        assertEquals("https://www.aiworkdeck.com/zh/admin?tab=feedback&fb=12", custom.consoleRef(fb));
    }

    @Test
    void saveSendsResolutionBack() throws Exception {
        RemoteFeedbackSource src = new RemoteFeedbackSource(base, "tok");
        UserFeedback fb = new UserFeedback();
        fb.setId(77L);
        fb.setStatus(UserFeedback.STATUS_PR_OPENED);
        fb.setTriageVerdict("BUG");
        fb.setPrUrl("https://github.com/a/b/pull/9");
        fb.setAttempts(1);

        src.save(fb);

        assertTrue(requests.stream().anyMatch(r -> r.startsWith("POST /api/feedback/77/resolution")));
        Map<?, ?> body = mapper.readValue(bodies.get(0), Map.class);
        assertEquals("PR_OPENED", body.get("status"));
        assertEquals("https://github.com/a/b/pull/9", body.get("prUrl"));
        assertEquals(1, body.get("attempts"));
    }

    @Test
    void unreachableInboxSurfacesAsAnErrorNotAnEmptyBatch() {
        pendingStatus = 403;
        pendingJson = "{\"code\":1,\"message\":\"token 不对\"}";
        RemoteFeedbackSource src = new RemoteFeedbackSource(base, "wrong");
        // 静默返回空批次会让「优化者天天跑天天零条」看起来一切正常
        assertThrows(IllegalStateException.class, () -> src.pending(5, 3));
    }

    @Test
    void failedResolutionThrowsSoTheRoundReportsIt() {
        server.removeContext("/api/feedback/");
        server.createContext("/api/feedback/", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        RemoteFeedbackSource src = new RemoteFeedbackSource(base, "tok");
        UserFeedback fb = new UserFeedback();
        fb.setId(77L);
        fb.setStatus(UserFeedback.STATUS_EMAILED);
        // 回执丢了这条会被下一轮重跑（可能再开一个 PR），不能吞
        assertThrows(IllegalStateException.class, () -> src.save(fb));
    }

    @Test
    void remoteModeRefusesToStartWithoutUrlOrToken() {
        OptimizerProperties props = new OptimizerProperties();
        props.setSource("remote");
        OptimizerSourceConfig cfg = new OptimizerSourceConfig();
        UserFeedbackRepository repo = mock(UserFeedbackRepository.class);
        FeedbackService svc = mock(FeedbackService.class);

        assertThrows(IllegalStateException.class, () -> cfg.optimizerFeedbackSource(props, repo, svc));

        props.getRemote().setBaseUrl("http://cloud.example.com");
        props.getRemote().setToken("t");
        // 明文出公网不行：取件与回执上跑着 token 和用户反馈原文
        assertThrows(IllegalStateException.class, () -> cfg.optimizerFeedbackSource(props, repo, svc));

        props.getRemote().setBaseUrl("https://cloud.example.com");
        assertInstanceOf(RemoteFeedbackSource.class, cfg.optimizerFeedbackSource(props, repo, svc));
    }

    @Test
    void defaultModeIsLocal() {
        OptimizerProperties props = new OptimizerProperties();
        OptimizerFeedbackSource src = new OptimizerSourceConfig().optimizerFeedbackSource(
                props, mock(UserFeedbackRepository.class), mock(FeedbackService.class));
        assertInstanceOf(LocalFeedbackSource.class, src);
    }
}
