// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.checkba.service.ai.AskUserQuestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ask_user 在真实编排器里的四步（dev-board#868）：工具被分发 → 发出 SSE ask_user 事件 →
 * 本轮到此结束（不再调模型、不执行同批后续工具、bubble_end=awaiting_input）→
 * 问题以 {@code <question kind="ask_user">} 标记随本轮落库（历史回灌与下一轮模型读到的都是它）。
 *
 * <p>「回答回填」的另一半——下一条用户消息以 {@code <ask_user_answer>} 开头时末位提醒换成
 * 「按回答继续」——在 {@code ContextAssemblerServiceTest} 里（本 harness 的组装器是 mock）。
 */
@DisplayName("ask_user：编排器停机与事件（回放）")
class AskUserOrchestratorFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EvalCase nativeCase(String userInput, List<EvalCase.NativeCall> calls, List<EvalCase.Turn> more) {
        EvalCase c = new EvalCase();
        c.id = "ask-user-flow-" + Math.abs(userInput.hashCode());
        c.title = userInput;
        c.userInput = userInput;
        c.activeDocument = new EvalCase.ActiveDocument();
        c.activeDocument.name = "股份认购协议.docx";
        c.activeDocument.fileType = "docx";
        EvalCase.Turn first = new EvalCase.Turn();
        first.toolCalls = calls;
        c.turns = new ArrayList<>(List.of(first));
        c.turns.addAll(more);
        return c;
    }

    private static EvalCase.NativeCall call(String name, Map<String, Object> args) {
        EvalCase.NativeCall c = new EvalCase.NativeCall();
        c.name = name;
        c.arguments = args;
        return c;
    }

    private static String textOf(EvalHarness.SseEvent e) throws Exception {
        return MAPPER.readTree(e.data()).path("content").asText();
    }

    @Test
    void askUserEmitsTheEventEndsTheTurnAndPersistsTheQuestion() throws Exception {
        EvalCase c = nativeCase("你能帮我清理已经打开的这个文档么", List.of(
                call("ask_user", Map.of(
                        "question", "「清理」具体指哪一种？",
                        "header", "清理范围",
                        "options", List.of(
                                Map.of("label", "删除混入的审查报告", "description", "只删第 12-21 段"),
                                Map.of("label", "清理格式", "description", "统一字体、删多余空行"))
                )),
                // 同批里紧跟着的写入：正是还没被授权的那个动作，必须不执行
                call("doc_delete_text", Map.of("text", "审查报告"))), List.of());

        EvalHarness.RunResult r = EvalHarness.run(c);

        // ① 工具注册与分发：只分发了 ask_user，同批的写入没有执行
        assertEquals(List.of("ask_user"), r.dispatches().stream().map(RecordingToolRegistry.Dispatch::resolvedName).toList());
        assertTrue(r.toolNamesOfferedPerLlmCall().get(0).contains("ask_user"), "ask_user 必须在下发的工具集里");

        // ② 事件：一条 ask_user，载荷带版本、id、选项与说明
        List<EvalHarness.SseEvent> asks = r.events(AskUserQuestion.SSE_EVENT);
        assertEquals(1, asks.size(), "应发出且只发出一条 ask_user 事件");
        JsonNode payload = MAPPER.readTree(asks.get(0).data());
        assertEquals(1, payload.get("v").asInt());
        String id = payload.get("id").asText();
        assertTrue(id.startsWith("ask-"), id);
        assertEquals("「清理」具体指哪一种？", payload.get("question").asText());
        assertEquals("清理范围", payload.get("header").asText());
        assertEquals("删除混入的审查报告", payload.get("options").get(0).get("label").asText());
        assertEquals("只删第 12-21 段", payload.get("options").get(0).get("description").asText());
        assertFalse(payload.get("multiSelect").asBoolean());

        // ③ 回合终止：模型只被调了一次，脚本没有剩余，最后一个事件是 awaiting_input 的 bubble_end
        assertEquals(1, r.toolsOfferedPerLlmCall().size(), "ask_user 之后不许再调用模型");
        assertEquals(0, r.remainingScriptTurns());
        EvalHarness.SseEvent last = r.sseEvents().get(r.sseEvents().size() - 1);
        assertEquals("bubble_end", last.event());
        assertTrue(last.data().contains("\"awaiting_input\""), last.data());

        // 顺序：标记（text_delta）在前、结构化事件在后、bubble_end 最后——
        // 前端靠这个顺序让事件覆盖解析器从标记里拼出的那份
        int markupAt = -1, eventAt = -1, endAt = -1;
        for (int i = 0; i < r.sseEvents().size(); i++) {
            EvalHarness.SseEvent e = r.sseEvents().get(i);
            if ("text_delta".equals(e.event()) && textOf(e).contains("<question kind=\"ask_user\"")) markupAt = i;
            if (AskUserQuestion.SSE_EVENT.equals(e.event())) eventAt = i;
            if ("bubble_end".equals(e.event())) endAt = i;
        }
        assertTrue(markupAt >= 0 && markupAt < eventAt && eventAt < endAt,
                "顺序应为 标记 < 事件 < bubble_end，实际 " + markupAt + "/" + eventAt + "/" + endAt);

        // ④ 落库：本轮 ASSISTANT 消息带问题标记（与事件同一个 id），过程卡给的是人话而不是工具回执
        String saved = r.lastAssistantMessage().orElseThrow();
        assertTrue(saved.contains("<question kind=\"ask_user\" id=\"" + id + "\""), saved);
        assertTrue(saved.contains("<option description=\"只删第 12-21 段\">删除混入的审查报告</option>"), saved);
        assertTrue(saved.contains("已向你提问"), saved);
        assertFalse(saved.contains("OK (eval stub)"), "过程卡不该出现工具回执: " + saved);
    }

    @Test
    void invalidAskUserDoesNotStopTheTurn() {
        EvalCase.Turn fixed = new EvalCase.Turn();
        fixed.text = "<final>好的，我直接按格式清理处理。</final>";
        EvalCase c = nativeCase("帮我整理一下这份文件", List.of(
                call("ask_user", Map.of("question", "怎么整理？", "options", List.of("只有一个")))), List.of(fixed));

        EvalHarness.RunResult r = EvalHarness.run(c);

        assertTrue(r.events(AskUserQuestion.SSE_EVENT).isEmpty(), "参数不合格不许发事件");
        assertEquals(2, r.toolsOfferedPerLlmCall().size(), "参数不合格按普通工具回喂、继续下一轮");
        String lastEnd = r.events("bubble_end").get(r.events("bubble_end").size() - 1).data();
        assertTrue(lastEnd.contains("\"finished\""), lastEnd);
    }

    @Test
    void xmlFallbackAskUserAlsoStops() throws Exception {
        EvalCase c = new EvalCase();
        c.id = "ask-user-flow-xml";
        c.userInput = "帮我优化一下这份合同";
        EvalCase.Turn t = new EvalCase.Turn();
        t.text = "<process name=\"向用户提问\"><tool_code>ask_user({\"question\":\"从哪方面优化？\","
                + "\"options\":[\"对我方更有利\",\"只调整格式\"],\"multi_select\":true})</tool_code></process>";
        c.turns = new ArrayList<>(List.of(t));

        EvalHarness.RunResult r = EvalHarness.run(c);

        assertEquals(1, r.toolsOfferedPerLlmCall().size());
        JsonNode payload = MAPPER.readTree(r.events(AskUserQuestion.SSE_EVENT).get(0).data());
        assertTrue(payload.get("multiSelect").asBoolean());
        assertEquals(2, payload.get("options").size());
        assertTrue(r.lastAssistantMessage().orElseThrow().contains("multi=\"true\""));
    }
}
