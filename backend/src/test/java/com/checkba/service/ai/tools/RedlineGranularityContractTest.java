// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 修订颗粒度的模型层契约（dev-board#365）。
 *
 * <p>字符级最小 diff 落在 worker（office_thread.js 的 minimalEdits / applyMinimalRedline），
 * 后端拿不到修订对象，形态断言在 frontend/tests/lowa-unit 与 lowa-e2e 组 11。后端能守的是
 * 模型层：替换类工具的描述必须把「未改动的文字逐字照抄」这条约束挂在<b>末尾</b>——
 * 引擎再细的 diff 也救不了模型顺手润色/改标点的整句重写，而弱模型对描述中段的约束视而不见。
 */
class RedlineGranularityContractTest {

    /** 会在修订模式下产生「删 X 插 Y」片段的替换类工具。 */
    private static final List<String> REPLACE_TOOLS = List.of(
            "doc_find_replace", "doc_replace_nth_match", "doc_replace_selection",
            "doc_modify_paragraph", "doc_replace_at_anchor");

    private static String toolDescription(String name) {
        for (Method m : DocumentEditTools.class.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Tool t = m.getAnnotation(Tool.class);
            assertNotNull(t, name + " 应当是 @Tool");
            return String.join("", t.value());
        }
        throw new AssertionError("DocumentEditTools 里没有工具 " + name);
    }

    @Test
    @DisplayName("每个替换类工具的描述都以「逐字照抄」颗粒度约束收尾")
    void everyReplaceToolEndsWithVerbatimCopyConstraint() {
        for (String name : REPLACE_TOOLS) {
            String d = toolDescription(name);
            assertTrue(d.contains("逐字照抄"), name + " 的描述缺少「未改动的文字逐字照抄」约束，实际是：" + d);
            assertTrue(d.endsWith(DocumentEditTools.REDLINE_GRANULARITY_NOTE),
                    name + " 的颗粒度约束必须放在描述末尾（弱模型只认末位约束），实际结尾是：" + d.substring(Math.max(0, d.length() - 80)));
        }
    }

    @Test
    @DisplayName("system prompt（中/英）第 7 节的修订颗粒度条目要求未改动文字逐字照抄")
    void systemPromptRequiresVerbatimCopyOfUnchangedText() throws Exception {
        // 第 7 节自 dev-board#809（K29）起在按客户端能力拼装的 LOWA 片段里，不在基底 prompt
        String zh = readResource("prompts/tools-lowa.md");
        String en = readResource("prompts/tools-lowa.en.md");
        assertTrue(zh.contains("逐字照抄"), "tools-lowa.md 修订颗粒度条目缺「逐字照抄」约束");
        assertTrue(en.contains("verbatim"), "tools-lowa.en.md revision-granularity note lacks the verbatim-copy constraint");
    }

    @Test
    @DisplayName("锚点范围是匹配文本，不能把段落上下文回填为替换文本（dev-board#1085）")
    void anchorToolDescriptionsSeparateMatchRangeFromParagraphContext() throws Exception {
        for (String tool : List.of("doc_find_text", "doc_replace_at_anchor")) {
            String description = toolDescription(tool);
            assertTrue(description.contains("matches[].text"), description);
            assertTrue(description.contains("paragraph") && description.contains("仅供定位"), description);
        }
        String replacement = toolDescription("doc_replace_at_anchor");
        assertTrue(replacement.contains("doc_modify_paragraph"), "整段重写必须指向范围匹配的工具");
        var method = DocumentEditTools.class.getMethod("doc_replace_at_anchor", String.class, String.class);
        String parameter = method.getParameters()[1].getAnnotation(P.class).value();
        assertTrue(parameter.contains("仅替换") && parameter.contains("matches[].text"), parameter);
        assertTrue(parameter.contains("周边文字"), parameter);
    }

    @Test
    void lowaPromptsDefineReplacementScopeInBothLanguages() throws Exception {
        String zh = readResource("prompts/tools-lowa.md");
        String en = readResource("prompts/tools-lowa.en.md");
        assertTrue(zh.contains("只覆盖 matches[].text") && zh.contains("仅供定位"));
        assertTrue(zh.contains("doc_modify_paragraph") && zh.contains("被替换范围内"));
        assertTrue(en.contains("covers only matches[].text") && en.contains("only for locating"));
        assertTrue(en.contains("doc_modify_paragraph") && en.contains("within the replacement range"));
    }

    @Test
    void truncatedAnchorReceiptsRequireAFullParagraphRead() throws Exception {
        String replacement = toolDescription("doc_replace_at_anchor");
        assertTrue(replacement.contains("paragraphAfterEditTruncated=true")
                && replacement.contains("doc_get_paragraph"));
        assertTrue(!replacement.contains("也不需要改后再读文档"));
        assertTrue(toolDescription("doc_find_text").contains("paragraphTruncated=true"));
        for (String resource : List.of("prompts/tools-lowa.md", "prompts/tools-lowa.en.md")) {
            String prompt = readResource(resource);
            assertTrue(prompt.contains("paragraphAfterEditTruncated=true") && prompt.contains("doc_get_paragraph"));
            assertTrue(prompt.contains("paragraphLength") && prompt.contains("paragraphTruncated=true"));
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = RedlineGranularityContractTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "classpath 上找不到 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
