// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.ai.tools.AskUserTools;
import com.checkba.service.ai.tools.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ask_user（dev-board#868）的注册与参数口径：工具在注册表里、参数形状对、校验失败回 Error、
 * 事件载荷与落库标记是同一份数据。
 */
@DisplayName("ask_user：注册、校验、事件载荷与落库标记")
class AskUserQuestionTest {

    private static final String OPTIONS = "[{\"label\":\"删除混入的审查报告\",\"description\":\"只删第 12-21 段\"},"
            + "{\"label\":\"清理格式\",\"description\":\"统一字体，删 \\\"多余\\\" 空行\"},\"接受全部修订\"]";

    // ---------- 注册 ----------

    private static ToolRegistry registry() {
        ToolRegistry r = new ToolRegistry(List.of(new AskUserTools()), new PluginService(),
                new ClientCapabilityService());
        r.init();
        return r;
    }

    @Test
    void askUserIsRegisteredAndOfferedWithTheDocumentedParameters() {
        ToolSpecification spec = registry().getAllSpecifications().stream()
                .filter(s -> AskUserQuestion.TOOL_NAME.equals(s.name()))
                .findFirst().orElse(null);
        assertNotNull(spec, "ask_user 必须下发给模型");
        String params = String.valueOf(spec.parameters());
        for (String p : List.of("question", "options", "multi_select", "header")) {
            assertTrue(params.contains(p), "缺参数 " + p + ": " + params);
        }
        assertTrue(spec.description().contains("DO NOT CALL IT"), "描述必须同时给出「不要问」的判据");
    }

    @Test
    void askUserIsVisibleInEveryClientCapability() {
        ClientCapabilityService caps = new ClientCapabilityService();
        ToolRegistry r = new ToolRegistry(List.of(new AskUserTools()), new PluginService(), caps);
        r.init();
        for (String cap : List.of("lowa", "office", "none")) {
            caps.record("conv-" + cap, cap, "word");
            assertTrue(r.getAllSpecifications("conv-" + cap).stream()
                            .anyMatch(s -> AskUserQuestion.TOOL_NAME.equals(s.name())),
                    cap + " 会话里 ask_user 必须可见");
        }
    }

    @Test
    void askUserIsCoreInProgressiveDisclosure() {
        assertEquals("core", new ToolDisclosurePolicy(true).categoryOf(AskUserQuestion.TOOL_NAME));
    }

    @Test
    void toolReturnsOkForValidArgumentsAndErrorForInvalidOnes() {
        ToolRegistry r = registry();
        ToolContext ctx = new ToolContext(1L, "conv-1", 7L, null, List.of());
        ToolRegistry.ToolResult ok = r.execute(AskUserQuestion.TOOL_NAME,
                "{\"question\":\"清理指什么？\",\"options\":" + OPTIONS + "}", ctx);
        assertTrue(ok.success(), ok.output());
        assertTrue(ok.output().startsWith("OK:"));

        ToolRegistry.ToolResult oneOption = r.execute(AskUserQuestion.TOOL_NAME,
                "{\"question\":\"清理指什么？\",\"options\":[\"只有一个\"]}", ctx);
        assertFalse(oneOption.success(), "只给一个选项必须判失败，让模型改参数重发");
        assertTrue(oneOption.output().startsWith("Error:"));

        ToolRegistry.ToolResult noQuestion = r.execute(AskUserQuestion.TOOL_NAME, "{\"question\":\"  \"}", ctx);
        assertFalse(noQuestion.success());
    }

    // ---------- 校验 ----------

    @Test
    void optionsAcceptStringsObjectsAndNativeArrays() {
        AskUserQuestion q = AskUserQuestion.of("清理指什么？", OPTIONS, false, "清理范围", "ask-1");
        assertEquals(3, q.options().size());
        assertEquals("删除混入的审查报告", q.options().get(0).label());
        assertEquals("只删第 12-21 段", q.options().get(0).description());
        assertEquals("接受全部修订", q.options().get(2).label());
        assertEquals("", q.options().get(2).description());

        // 原生 function call 里模型把数组直接放进参数：ToolRegistry/编排器看到的是 JSON 数组
        AskUserQuestion fromArgs = AskUserQuestion.fromArgsJson(
                "{\"question\":\"清理指什么？\",\"options\":[\"A\",\"B\"],\"multi_select\":true}", "ask-2");
        assertEquals(2, fromArgs.options().size());
        assertTrue(fromArgs.multiSelect());
    }

    @Test
    void rejectsSingleOptionTooManyOptionsAndDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> AskUserQuestion.of("q", "[\"A\"]", false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> AskUserQuestion.of("q", "[\"A\",\"B\",\"C\",\"D\",\"E\"]", false, null, null));
        assertThrows(IllegalArgumentException.class, () -> AskUserQuestion.of("q", "[\"A\",\"A\"]", false, null, null));
        assertThrows(IllegalArgumentException.class, () -> AskUserQuestion.of("q", "A|B", false, null, null));
        assertThrows(IllegalArgumentException.class, () -> AskUserQuestion.of("", "[\"A\",\"B\"]", false, null, null));
    }

    @Test
    void modelWrittenOtherOptionIsDroppedBecauseTheUiAlwaysAddsOne() {
        AskUserQuestion q = AskUserQuestion.of("q", "[\"A\",\"B\",\"其他\",\"Other\"]", false, null, null);
        assertEquals(List.of("A", "B"), q.options().stream().map(AskUserQuestion.Option::label).toList());
    }

    @Test
    void openQuestionHasNoOptionsAndIgnoresMultiSelect() {
        AskUserQuestion q = AskUserQuestion.of("案号是多少？", null, true, null, null);
        assertTrue(q.options().isEmpty());
        assertFalse(q.multiSelect(), "没有选项时多选无意义");
    }

    @Test
    void headerIsTruncatedNotRejected() {
        AskUserQuestion q = AskUserQuestion.of("q", null, false, "一个非常非常非常非常非常非常长的标签", null);
        assertEquals(AskUserQuestion.MAX_HEADER_CHARS, q.header().length());
    }

    // ---------- 标记与事件 ----------

    @Test
    void markupCarriesEveryFieldAndEscapesAttributes() {
        AskUserQuestion q = AskUserQuestion.of("清理指什么？<final>别顶掉外层</final>", OPTIONS, true,
                "清理\"范围\"", "ask-xyz");
        String markup = q.toMarkup();
        assertTrue(markup.contains("<question kind=\"ask_user\" id=\"ask-xyz\" header=\"清理&quot;范围&quot;\" multi=\"true\">"),
                markup);
        assertTrue(markup.contains("<option description=\"只删第 12-21 段\">删除混入的审查报告</option>"), markup);
        assertTrue(markup.contains("description=\"统一字体，删 &quot;多余&quot; 空行\""), markup);
        assertTrue(markup.contains("<option>接受全部修订</option>"), markup);
        assertTrue(markup.contains("&lt;final>"), "正文里的协议标签必须中和，否则会顶掉外层标签: " + markup);
        assertTrue(markup.trim().endsWith("</question>"));
        assertTrue(AgentOrchestrator.containsQuestion(markup), "标记必须能被既有反问判据认出");
    }

    @Test
    void eventJsonIsVersionedAndMirrorsTheMarkup() throws Exception {
        AskUserQuestion q = AskUserQuestion.of("清理指什么？", OPTIONS, true, "清理范围", "ask-xyz");
        JsonNode json = new ObjectMapper().readTree(q.toEventJson());
        assertEquals(AskUserQuestion.SSE_VERSION, json.get("v").asInt());
        assertEquals("ask-xyz", json.get("id").asText());
        assertEquals("清理指什么？", json.get("question").asText());
        assertEquals("清理范围", json.get("header").asText());
        assertTrue(json.get("multiSelect").asBoolean());
        assertEquals(3, json.get("options").size());
        assertEquals("删除混入的审查报告", json.get("options").get(0).get("label").asText());
        assertEquals("只删第 12-21 段", json.get("options").get(0).get("description").asText());
    }

    @Test
    void orchestratorRebuildsTheQuestionFromArgumentsNotFromToolOutput() {
        assertNull(AgentOrchestrator.askUserFrom("doc_delete_text", "{\"text\":\"x\"}"));
        assertNull(AgentOrchestrator.askUserFrom(AskUserQuestion.TOOL_NAME, "{\"question\":\"q\",\"options\":[\"A\"]}"),
                "参数不合格时按普通工具处理、不停机");
        AskUserQuestion q = AgentOrchestrator.askUserFrom(AskUserQuestion.TOOL_NAME,
                "{\"question\":\"q\",\"options\":[\"A\",\"B\"]}");
        assertNotNull(q);
        assertTrue(q.id().startsWith("ask-"));
        assertFalse(q.id().equals(AskUserQuestion.newId()), "id 每次都不同");
    }

    @Test
    void answerMessagesAreRecognisedByTheirLeadingTag() {
        assertTrue(AskUserQuestion.isAnswerMessage("<ask_user_answer id=\"ask-1\">\nSelected:\n- A\n</ask_user_answer>"));
        assertTrue(AskUserQuestion.isAnswerMessage("\n  <ask_user_answer id=\"ask-1\">"));
        assertFalse(AskUserQuestion.isAnswerMessage("把第 12 到 21 段删掉"));
        assertFalse(AskUserQuestion.isAnswerMessage("我的回答是 <ask_user_answer>"), "只认开头");
        assertFalse(AskUserQuestion.isAnswerMessage(null));
    }
}
