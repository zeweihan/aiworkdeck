// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * SSE 双轨摘旧名（dev-board#816 / 计划 K36）：每条编辑器指令只发<b>一次</b> client_action。
 *
 * <p>病灶（审计 B-15）：{@code executeEditorCommand} 对同一条命令按「新名 editor_command 在前、
 * 旧名 wps_command 在后」各发一份完整载荷，前端靠「先见新名」的 latch 丢掉后一份。
 * 于是<b>每一个字节都在 SSE 上走了两遍</b>——一条 5000 字的 doc_insert_at_cursor
 * 要推两万多字节，其中一半注定被前端扔掉。打开/重载文件（doc_open_file / wps_open_file、
 * doc_reload_file / wps_reload_file）与流式写入（doc_stream_data / wps_stream_data）同理。
 *
 * <p>摘掉的判据：旧名的唯一消费者是桌面端自己（agentClientActions.handleClientAction），
 * 它与后端同一个安装包一起发版；Office/WPS 任务窗格在 chatSession.handleClientAction
 * 第一行就 {@code action.tool !== 'office_command'} 直接 return，从来不读 editor_command，
 * 更不读 wps_command。没有会掉队的已发布客户端。
 */
class EditorBridgeSingleDispatchTest {

    /** 捕获所有 client_action 载荷（按下发顺序）。 */
    private static List<String> captureClientActions(SseEmitterService sse, List<String> eventNames) {
        List<String> payloads = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            String event = inv.getArgument(1);
            eventNames.add(event);
            if ("client_action".equals(event)) payloads.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());
        return payloads;
    }

    private static EditorBridgeService newService(SseEmitterService sse) {
        return new EditorBridgeService(sse, new ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));
    }

    @Test
    @DisplayName("一条编辑器命令只发一次 client_action，tool 恒为 editor_command")
    void everyEditorCommandIsDispatchedExactlyOnce() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        List<String> events = new CopyOnWriteArrayList<>();
        List<String> payloads = captureClientActions(sse, events);
        EditorBridgeService svc = newService(sse);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            BlockingQueue<String> done = new LinkedBlockingQueue<>();
            pool.submit(() -> {
                svc.setCurrentConversationId("conv-single");
                done.add(svc.executeEditorCommand("find_replace",
                        Map.of("findText", "甲方", "replaceText", "买方", "replaceAll", true)));
            });
            // 下发是同步的，给它一点时间落到 mock 上再断言
            for (int i = 0; i < 100 && payloads.isEmpty(); i++) Thread.sleep(10);
            assertFalse(payloads.isEmpty(), "命令没能下发到编辑器");
            Thread.sleep(100); // 若还存在第二份旧名载荷，这段时间足够它落进来

            assertEquals(1, payloads.size(),
                    "同一条命令发了 " + payloads.size() + " 份 client_action——双轨旧名 wps_command 还在："
                            + payloads);
            assertTrue(payloads.get(0).contains("\"tool\":\"editor_command\""),
                    "唯一那份必须是新名：" + payloads.get(0));
            assertFalse(payloads.get(0).contains("wps_command"), "载荷里不该再出现旧名");

            svc.cancelPendingActions("conv-single");
            assertNotNull(done.poll(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("打开 / 重载文件也只发一次，不再有 wps_open_file / wps_reload_file")
    void openAndReloadAreDispatchedExactlyOnce() {
        SseEmitterService sse = mock(SseEmitterService.class);
        List<String> events = new CopyOnWriteArrayList<>();
        List<String> payloads = captureClientActions(sse, events);
        EditorBridgeService svc = newService(sse);
        svc.setCurrentConversationId("conv-open");

        ProjectFile file = new ProjectFile();
        file.setId(42L);
        file.setName("合同.docx");
        file.setFileType("docx");
        file.setWpsFileId("w-42");

        svc.sendOpenFileAction(file);
        assertEquals(1, payloads.size(), "doc_open_file 发了 " + payloads.size() + " 份：" + payloads);
        assertTrue(payloads.get(0).contains("\"action\":\"doc_open_file\""), payloads.get(0));
        assertFalse(payloads.get(0).contains("wps_open_file"), payloads.get(0));

        payloads.clear();
        svc.sendReloadFileAction(file);
        assertEquals(1, payloads.size(), "doc_reload_file 发了 " + payloads.size() + " 份：" + payloads);
        assertTrue(payloads.get(0).contains("\"action\":\"doc_reload_file\""), payloads.get(0));
        assertFalse(payloads.get(0).contains("wps_reload_file"), payloads.get(0));
    }

    @Test
    @DisplayName("5000 字正文插入：client_action 只推一份载荷（旧行为是两份，字节数正好一半）")
    void bulkInsertPushesHalfTheBytes() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        List<String> events = new CopyOnWriteArrayList<>();
        List<String> payloads = captureClientActions(sse, events);
        EditorBridgeService svc = newService(sse);

        String body = "第一条　本协议由甲乙双方于签署日订立。".repeat(265); // 约 5000 个汉字
        assertTrue(body.length() >= 5000, "正文得够长才量得出差别：" + body.length());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> {
                svc.setCurrentConversationId("conv-bulk");
                svc.executeEditorCommand("insert_at_cursor", Map.of("text", body));
            });
            for (int i = 0; i < 200 && payloads.isEmpty(); i++) Thread.sleep(10);
            assertFalse(payloads.isEmpty(), "命令没能下发");
            Thread.sleep(150);

            int bytes = 0;
            for (String p : payloads) bytes += p.getBytes(StandardCharsets.UTF_8).length;
            System.out.println("[K36] 5000 字 insert_at_cursor：client_action 载荷 " + payloads.size()
                    + " 份，合计 " + bytes + " 字节（同一份载荷双发时为 " + (bytes * 2) + " 字节）");
            assertEquals(1, payloads.size(), "双轨还在：" + payloads.size() + " 份载荷");
            svc.cancelPendingActions("conv-bulk");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("后端源码里不再有 wps_* 的 SSE 旧名（含 wps_stream_data / wps_open_file_sync）")
    void noLegacySseNameSurvivesInBackendSources() throws Exception {
        Path src = repoRoot().resolve("backend/src/main/java/com/checkba");
        List<String> hits = new ArrayList<>();
        try (var files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String legacy : List.of("\"wps_command\"", "\"wps_open_file\"", "\"wps_reload_file\"",
                        "\"wps_stream_data\"", "\"wps_open_file_sync\"")) {
                    if (text.contains(legacy)) hits.add(f.getFileName() + " → " + legacy);
                }
            }
        }
        assertTrue(hits.isEmpty(), "双轨旧名还在后端源码里：" + hits);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve(".claude/agents/ai-doc-bridge.md"))) return dir;
            dir = dir.getParent();
        }
        return fail("找不到仓库根");
    }
}
