package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 展示截断不许把外层 {@code <tool_output>} 的闭合吞掉。
 *
 * <p>截断先于中和发生（截断口径按原文字数），所以截断点可能落在载荷里某个标签形状的中间：
 * 剩下的半截 {@code <tool_output status="SUC} 没有 {@code >}，{@link AgentTagProtocol} 认不出它、
 * 原样放行；紧随其后的是截断后缀和外层自己的 {@code </tool_output>}，前端 tagRegex 的
 * {@code [^>]*} 属性段会一路吃到那个闭合标签的 {@code >}，把真正的闭合当成属性吞掉——
 * 于是本轮剩下的正文全被塞进工具输出折叠区，工具行一直转圈。
 */
@DisplayName("截断点落在标签形状中间")
class AgentOrchestratorTruncationTagSafetyTest {

    /** 与前端 {@code createProtocolTagRegex()} 同形 */
    private static final Pattern FRONTEND_TAG = Pattern.compile(
            "<(/?)(" + String.join("|", AgentTagProtocol.TAGS) + ")(\\s+[^>]*)?>");

    @Test
    @DisplayName("截断在属性中间：外层闭合仍是独立的一个标签")
    void truncationInsideAttributesKeepsWrapperClose() {
        String raw = "子任务日志：<tool_output status=\"SUCCESS\">已完成</tool_output>";
        int cut = raw.indexOf("SUCCESS") + 3; // 落在属性值中间，此处没有 '>'

        String delta = String.format("<tool_output status=\"SUCCESS\">%s</tool_output>",
                AgentTagProtocol.escape(AgentOrchestrator.truncate(raw, cut)));

        List<String> tags = scan(delta);
        assertEquals(2, tags.size(), "只该有外层的一开一闭：" + delta);
        assertEquals("<tool_output status=\"SUCCESS\">", tags.get(0));
        assertEquals("</tool_output>", tags.get(1),
                "外层闭合被载荷里的半截标签当成属性吞掉了：" + delta);
    }

    @Test
    @DisplayName("截断在标签名中间：同样不许留下半截标签")
    void truncationInsideTagNameKeepsWrapperClose() {
        String raw = "结果里提到 <final>，后面还有很多内容";
        String delta = String.format("<tool_output status=\"SUCCESS\">%s</tool_output>",
                AgentTagProtocol.escape(AgentOrchestrator.truncate(raw, raw.indexOf("<final") + 3)));

        assertEquals(List.of("<tool_output status=\"SUCCESS\">", "</tool_output>"), scan(delta), delta);
    }

    @Test
    @DisplayName("普通尖括号不因此被多切：截断点照旧按字数")
    void plainAngleBracketIsNotTagShape() {
        String raw = "报价 a < b 时按下限计，后面还有很长一段说明文字需要展示给用户";
        int cut = raw.indexOf('<') + 12;

        assertTrue(AgentOrchestrator.truncate(raw, cut).startsWith(raw.substring(0, cut)),
                "非标签形状的 < 不该触发回退，否则展示内容凭空少一截");
    }

    @Test
    @DisplayName("切口正好停在 '<' 或 '</' 上：不越界、也不留半截")
    void truncationAtAngleBracketBoundary() {
        String raw = "内容<//tool_output>结尾"; // 让切口分别停在 '<' 与 '</' 之后
        int lt = raw.indexOf('<');
        for (int cut : new int[]{lt, lt + 1, lt + 2, lt + 5}) {
            String delta = String.format("<tool_output status=\"SUCCESS\">%s</tool_output>",
                    AgentTagProtocol.escape(AgentOrchestrator.truncate(raw, cut)));
            assertEquals(List.of("<tool_output status=\"SUCCESS\">", "</tool_output>"), scan(delta),
                    "cut=" + cut + " 时：" + delta);
        }
    }

    private List<String> scan(String s) {
        Matcher m = FRONTEND_TAG.matcher(s);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group());
        return out;
    }
}
