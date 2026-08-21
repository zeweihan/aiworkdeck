package com.checkba.service.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PptxServiceClient 两个修复对着**真 HTTP 桩**跑（不 mock 传输层）：
 *
 * <p>1. waitForTask/waitForTaskWithProgress 的 FAILED 分支此前返回 null，把 pptx-service
 * 回传的 error_message 丢在日志里，调用方只能硬编码 "Description generation failed" 之类
 * 的通用文案——"api key 无效""配额用尽""模型 id 写错"在用户眼里全长一个样，无从自救。
 *
 * <p>2. downloadPptx 只校验 HTTP 200 就当成功，字节长度、content-type、PPTX 魔数一概不查，
 * 空 body 也会被无条件写盘并报告"生成成功"。
 */
class PptxServiceClientTest {

    private HttpServer server;
    private String baseUrl;
    private PptxServiceClient client;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() throws java.io.IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        client = new PptxServiceClient();
        client.setTimeoutSeconds(10);
    }

    private void startServer() {
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client.setBaseUrl(baseUrl);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String readBody(InputStream in) throws java.io.IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    private static void respondBytes(com.sun.net.httpserver.HttpExchange ex, int status, byte[] bytes) throws java.io.IOException {
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    // ==== 修复 5：FAILED 分支的 error_message 一路带到调用方 ====

    /** 走完 create/outline/descriptions 三步，让第 4 步（等待描述生成任务）返回 FAILED。 */
    private void stubUpToDescriptionsThenFail(String errorMessage) {
        server.createContext("/api/projects/proj-1/tasks/task-desc-1", ex -> {
            readBody(ex.getRequestBody());
            respond(ex, 200, "{\"data\":{\"status\":\"FAILED\",\"error_message\":\"" + errorMessage + "\"}}");
        });
        server.createContext("/api/projects/proj-1/generate/descriptions", ex -> {
            readBody(ex.getRequestBody());
            respond(ex, 202, "{\"data\":{\"task_id\":\"task-desc-1\"}}");
        });
        server.createContext("/api/projects/proj-1/generate/outline", ex -> {
            readBody(ex.getRequestBody());
            respond(ex, 200, "{\"data\":{\"pages\":[]}}");
        });
        server.createContext("/api/projects", ex -> {
            readBody(ex.getRequestBody());
            respond(ex, 201, "{\"data\":{\"project_id\":\"proj-1\"}}");
        });
    }

    @Test
    @DisplayName("修复：generatePptxSync 在描述生成任务 FAILED 时，最终结果的 error 里带着真实 error_message")
    void generatePptxSyncPropagatesRealErrorMessage() {
        stubUpToDescriptionsThenFail("invalid api key");
        startServer();

        PptxServiceClient.PptxGenerationResult result =
                client.generatePptxSync("尽调汇报", "zh", null, null, null, false);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("invalid api key"),
                "真实失败原因必须出现在最终结果里，而不是被一句通用文案盖掉：" + result.getError());
    }

    @Test
    @DisplayName("修复：generatePptxWithProgress（真实调用点）同样传递 error_message，不只是同步版本")
    void generatePptxWithProgressPropagatesRealErrorMessage() {
        stubUpToDescriptionsThenFail("配额已用尽");
        startServer();

        PptxServiceClient.PptxGenerationResult result = client.generatePptxWithProgress(
                "尽调汇报", "zh", null, null, null, false, (progress, stage, message) -> { });

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("配额已用尽"),
                "真实失败原因必须出现在最终结果里：" + result.getError());
    }

    // ==== 修复 6：downloadPptx 校验非空 + PPTX 魔数 ====

    @Test
    @DisplayName("修复：下载 URL 返回 200 + 空 body，downloadPptx 必须失败，不能写出 0 字节文件")
    void downloadPptxRejectsEmptyBody() throws Exception {
        server.createContext("/download/empty.pptx", ex -> respondBytes(ex, 200, new byte[0]));
        startServer();

        Path target = tmp.resolve("out.pptx");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.downloadPptx("/download/empty.pptx", target.toString()));
        assertTrue(ex.getMessage().contains("empty"), ex.getMessage());
        assertFalse(Files.exists(target), "校验失败不应留下 0 字节文件");
    }

    @Test
    @DisplayName("修复：下载 URL 返回 200 + 一段 JSON（不是 PPTX），downloadPptx 必须失败")
    void downloadPptxRejectsNonPptxBody() throws Exception {
        server.createContext("/download/error.pptx", ex ->
                respond(ex, 200, "{\"error\":\"upstream image model unavailable\"}"));
        startServer();

        Path target = tmp.resolve("out.pptx");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.downloadPptx("/download/error.pptx", target.toString()));
        assertTrue(ex.getMessage().contains("not a valid PPTX"), ex.getMessage());
        assertFalse(Files.exists(target), "校验失败不应把 JSON 错误信息当 PPTX 写盘");
    }

    @Test
    @DisplayName("对照组：真实 PPTX 字节（含魔数）应正常下载落盘，字节内容不失真")
    void downloadPptxAcceptsValidPptxBytes() throws Exception {
        byte[] fakePptx = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x01, 0x02, 0x03};
        server.createContext("/download/real.pptx", ex -> respondBytes(ex, 200, fakePptx));
        startServer();

        Path target = tmp.resolve("out.pptx");
        String saved = client.downloadPptx("/download/real.pptx", target.toString());

        assertEquals(target.toString(), saved);
        assertTrue(Files.exists(target));
        assertEquals(fakePptx.length, Files.size(target));
    }

    // ==== 修复：缺 download_url 被拼成误导性 404 ====

    @Test
    @DisplayName("修复：download_url 缺失时报「missing download_url」，不是拼出 baseUrl+\"null\" 再报 404")
    void downloadPptxRejectsMissingDownloadUrlBeforeRequesting() throws Exception {
        startServer(); // 不为任何路径注册 handler：一旦真的发出 HTTP 请求就会连接被拒/404

        Path target = tmp.resolve("out.pptx");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.downloadPptx(null, target.toString()));

        assertTrue(ex.getMessage().contains("download_url"),
                "错误信息应点名缺的是 download_url，而不是一句笼统的 HTTP 404: " + ex.getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    @DisplayName("修复：download_url 为空字符串同样在发请求前拒绝")
    void downloadPptxRejectsBlankDownloadUrl() {
        startServer();

        Path target = tmp.resolve("out.pptx");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.downloadPptx("   ", target.toString()));
        assertTrue(ex.getMessage().contains("download_url"), ex.getMessage());
    }

    // ==== 修复：10 分钟轮询里一次瞬时网络错误不该掀翻整条多阶段生成 ====

    @Test
    @DisplayName("修复：单次瞬时轮询失败（一次 503）不该让 waitForTask 直接放弃，应该重试并最终成功")
    void waitForTaskToleratesTransientPollFailure() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/projects/proj-1/tasks/task-1", ex -> {
            if (calls.getAndIncrement() == 0) {
                // 模拟一次瞬时故障：网关/连接抖动，底层任务在 pptx-service 那边其实还在正常跑
                respond(ex, 503, "upstream unavailable");
            } else {
                respond(ex, 200, "{\"data\":{\"status\":\"COMPLETED\",\"progress\":{\"completed\":1,\"total\":1}}}");
            }
        });
        startServer();

        cn.hutool.json.JSONObject result = client.waitForTask("proj-1", "task-1");

        assertNotNull(result, "重试后应该正常拿到最终状态，不能因为中间一次失败就整体放弃");
        assertEquals("COMPLETED", result.getStr("status"));
        assertTrue(calls.get() >= 2, "应该在第一次失败后重试过至少一次");
    }
}
