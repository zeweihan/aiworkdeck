// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 用户点停止时，正卡在编辑器桥上的那次等待必须<b>当场</b>结束（计划 K4 ③）。
 *
 * <p>病灶：{@code executeEditorCommand} 在 {@code future.get(timeout)} 上死等，
 * 而超时档位最长 180 秒（{@code ACTION_TIMEOUT_SECONDS} 里的 insert_under_heading 等）。
 * 取消标志只在「每个工具执行<b>之前</b>」被检查，于是用户在 doc_open_file_sync 之类的
 * 桥调用中间点停止，要眼睁睁等满整个超时档位才会有任何反应——实测 180 秒。
 *
 * <p>取消不是超时：回执要明说这是用户停的、命令<b>可能已经执行了</b>（worker 打不断，
 * 与 {@code TIMEOUT_RESULT_JSON} 同一个口径），别让模型下一轮把它当失败原样重发。
 */
class EditorBridgeCancelPendingTest {

    private static final long CANCEL_BUDGET_MS = 200;

    @Test
    @DisplayName("等待桥回包时被取消：200ms 内返回，回执是可识别的失败且标明结局未知")
    void cancellingAConversationReleasesItsPendingBridgeCallsImmediately() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        BlockingQueue<String> dispatched = new LinkedBlockingQueue<>();
        doAnswer(inv -> {
            dispatched.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        EditorBridgeService svc = new EditorBridgeService(sse,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> call = pool.submit(() -> {
                svc.setCurrentConversationId("conv-stop");
                // insert_under_heading 是 180 秒档：不取消的话这个 submit 要等三分钟
                return svc.executeEditorCommand("insert_under_heading",
                        Map.of("heading", "第一条", "content", "正文"));
            });
            assertNotNull(dispatched.poll(5, TimeUnit.SECONDS), "命令没能下发到编辑器");

            long t0 = System.nanoTime();
            int released = svc.cancelPendingActions("conv-stop");
            String result = call.get(CANCEL_BUDGET_MS * 10, TimeUnit.MILLISECONDS);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertTrue(released >= 1, "取消应当释放掉这次在途的桥调用，实际释放 " + released + " 个");
            assertTrue(elapsedMs < CANCEL_BUDGET_MS,
                    "取消后 " + elapsedMs + "ms 才从桥上退出来——用户点了停止却还在等超时");
            assertTrue(result.contains("\"error\""),
                    "回执要能被 ToolResult.success() 判成失败，否则面板给它打绿勾：" + result);
            assertTrue(result.contains("outcomeUnknown"),
                    "worker 打不断，命令可能已经执行了，回执必须标明结局未知：" + result);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("取消只释放这个会话的在途桥调用，别的会话不受影响")
    void cancellingOneConversationLeavesOtherConversationsAlone() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        BlockingQueue<String> dispatched = new LinkedBlockingQueue<>();
        doAnswer(inv -> {
            dispatched.add(String.valueOf((Object) inv.getArgument(2)));
            return null;
        }).when(sse).send(any(), any(), any());

        EditorBridgeService svc = new EditorBridgeService(sse,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> other = pool.submit(() -> {
                svc.setCurrentConversationId("conv-other");
                return svc.executeEditorCommand("insert_under_heading",
                        Map.of("heading", "第一条", "content", "正文"));
            });
            assertNotNull(dispatched.poll(5, TimeUnit.SECONDS), "命令没能下发到编辑器");

            assertFalse(svc.cancelPendingActions("conv-stop") > 0,
                    "停的是别的会话，不该动 conv-other 的在途调用");
            assertFalse(other.isDone(), "别的会话的桥调用被误伤了");
        } finally {
            pool.shutdownNow();
        }
    }
}
