package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 「AI 说写好了、文档一片空白、全程无报错」的后端半边（dev-board#465）。
 *
 * <p>{@link AgentStreamHandler} 的 HIDDEN_CONTENT_TAGS 会把
 * {@code <thinking>/<process>/<artifact>/<title>/<walkthrough>} 之间的文字整段吞掉——
 * 这对聊天气泡是对的（任务清单不该跑进用户的合同里），但模型一旦把<b>正文</b>也包进
 * 这些标签，编辑器流只剩标签之间漏出的空白：前端照样点亮「正在向文档流式写入内容…」，
 * 收尾照样报 finished，谁都不知道文档是空的。
 *
 * <p>所以过滤本身不动（放行 artifact 会把实施计划/任务清单写进用户文档，那是更坏的回归），
 * 改成如实报告：{@link EditorBridgeService#noteStreamContent} 记「送出过非空白正文」，
 * 编排器在 doc_stream_end 里带上 {@code wrote}，前端据此把占位符换成失败提示。
 */
class DocStreamEmptyBodyTest {

    private record Captured(AgentStreamHandler handler, List<String> editorText) {}

    private static Captured handler() {
        AgentStreamHandler h = new AgentStreamHandler(mock(SseEmitterService.class), "conv-465",
                mock(TokenUsageService.class), "1", 1L, "test/model", 0L);
        List<String> out = new ArrayList<>();
        h.setOnEditorStream(out::add);
        return new Captured(h, out);
    }

    private static String join(List<String> parts) {
        return String.join("", parts);
    }

    @Test
    @DisplayName("证据：正文被包进 <artifact> 时，编辑器流一个字都收不到（只剩空白）")
    void bodyWrappedInArtifactNeverReachesTheEditor() {
        Captured c = handler();
        for (String token : List.of(
                "<artifact type=\"task_list\">\n", "# 股权转让协议\n", "第一条 转让标的\n", "</artifact>\n")) {
            c.handler().onNext(token);
        }
        assertTrue(join(c.editorText()).isBlank(),
                "artifact 里的正文本就不该进文档，实际收到：" + join(c.editorText()));
    }

    @Test
    @DisplayName("裸 markdown 正文照常进编辑器流（过滤没有误伤）")
    void plainMarkdownStillReachesTheEditor() {
        Captured c = handler();
        c.handler().onNext("# 股权转让协议\n");
        c.handler().onNext("第一条 转让标的\n");
        assertEquals("# 股权转让协议\n第一条 转让标的\n", join(c.editorText()));
    }

    @Test
    @DisplayName("EditorBridgeService 记得住「这一轮到底送出过正文没有」，空白不算")
    void streamContentFlagIgnoresWhitespaceOnly() {
        EditorBridgeService bridge = new EditorBridgeService(mock(SseEmitterService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.checkba.service.telemetry.TelemetryService.class));
        String cid = "conv-465";

        bridge.setStreamingMode(cid, true);
        assertFalse(bridge.hasStreamedContent(cid), "刚开流还没写过");

        // 标签之间漏出来的换行/空格不能算「写过」——这正是本卡的假象来源
        bridge.noteStreamContent(cid, "\n");
        bridge.noteStreamContent(cid, "  ");
        bridge.noteStreamContent(cid, null);
        assertFalse(bridge.hasStreamedContent(cid), "只漏出空白时必须仍判定为「没写进去」");

        bridge.noteStreamContent(cid, "# 股权转让协议");
        assertTrue(bridge.hasStreamedContent(cid));

        // 下一轮流式写入重新计数，不能被上一轮的成功掩盖
        bridge.setStreamingMode(cid, false);
        bridge.setStreamingMode(cid, true);
        assertFalse(bridge.hasStreamedContent(cid), "新一轮必须从「没写过」起算");
    }
}
