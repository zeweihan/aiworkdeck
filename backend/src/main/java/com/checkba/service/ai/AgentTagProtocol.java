package com.checkba.service.ai;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面板伪 XML 协议的载荷中和。
 *
 * <p>工具的参数与输出会被原样拼进 {@code <tool_code>…</tool_code>} /
 * {@code <tool_output status="…">…</tool_output>}，既走 SSE 给面板，也进 executionLog 落库。
 * 载荷本身完全可能含协议标签——读一份讲协议的文档、模型复述自己的输出、子任务结果里带
 * {@code <final>}——不中和的话前端的标签解析会在载荷中间错位：折叠区内容被截断，
 * 剩下的半截漏进正文。以前工具输出压根不渲染，错位无人察觉；折叠区上线后就是可见缺陷。
 *
 * <p><b>中和范围刻意收窄到「已知标签形状」</b>：{@code <tag>} / {@code </tag>} /
 * {@code <tag attr="…">}，tag 取自 {@link #TAGS}。合同正文里的 {@code <甲方>}、
 * {@code <Party A>} 这类占位符不是协议标签形状，原样保留——把所有 {@code <} 都转义
 * 会让律师在折叠区看到 {@code &lt;甲方&gt;}。
 *
 * <p><b>这是两侧契约</b>：前端 {@code frontend/src/composables/agentTagProtocol.mjs} 按同一份
 * 标签清单反向解转义（流式与历史回灌两条路径都解），用户看到的仍是原文。
 * 改这里的清单必须同步改那边，{@code AgentTagProtocolTest} 会对拍两份清单。
 */
final class AgentTagProtocol {

    /**
     * 协议标签清单：必须与前端 {@code agentTagProtocol.mjs} 的 {@code PROTOCOL_TAGS} 逐字一致，
     * 且必须是 Office 插件 {@code office-addin/taskpane/lib/sse.js} 的 {@code KNOWN_TAGS} 的子集
     * （插件多认 tool / bubble_type 两个，它们不会让插件的标签栈错位，不必中和）。
     */
    static final List<String> TAGS = List.of(
            "thinking", "title", "process", "step", "tool_code", "tool_output",
            "walkthrough", "final", "question", "option", "artifact");

    /** 与前端 tagRegex 同形：{@code <(\/?)(tag…)(\s+[^>]*)?>}。大小写敏感，前端那条也是。 */
    private static final Pattern TAG = Pattern.compile(
            "</?(?:" + String.join("|", TAGS) + ")(?:\\s+[^>]*)?>");

    private AgentTagProtocol() {
    }

    /**
     * 把载荷里协议标签的起始 {@code <} 换成 {@code &lt;}，其余字符一律原样保留。
     *
     * <p>不含协议标签的载荷（绝大多数）原对象返回，零拷贝。
     */
    static String escape(String payload) {
        if (payload == null) return "";
        if (payload.indexOf('<') < 0) return payload;

        Matcher m = TAG.matcher(payload);
        if (!m.find()) return payload;

        StringBuilder out = new StringBuilder(payload.length() + 32);
        int last = 0;
        do {
            out.append(payload, last, m.start())
               .append("&lt;")
               .append(payload, m.start() + 1, m.end());
            last = m.end();
        } while (m.find());
        return out.append(payload, last, payload.length()).toString();
    }
}
