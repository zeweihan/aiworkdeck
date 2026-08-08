package com.checkba.service.ai;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具载荷中和的两侧契约。
 *
 * <p>守三件事：① 载荷里的协议标签不会顶掉外层 {@code <tool_output>}（否则前端折叠区内容缺失、
 * 剩下的半截串进正文）；② 合同占位符 {@code <甲方>} 这类非协议标签形状原样保留（全量转义
 * 会让律师看到 {@code &lt;甲方&gt;}）；③ 标签清单与前端 {@code agentTagProtocol.mjs} 逐字一致——
 * 一边加了标签另一边没加，就会出现「转义了没人还原」的裸转义符。
 *
 * <p>下面的 RAW/ESCAPED 两个常量与 {@code frontend/tests/tag-protocol/protocol.test.mjs}
 * 里的同名 fixture 是同一段文本：那边断言前端解析这段转义后的流不串位、折叠区拿到的是原文。
 * 改一边必须改另一边。
 */
@DisplayName("工具载荷的协议标签中和")
class AgentTagProtocolTest {

    /** 工具输出原文：含协议闭合标签、协议起始标签，以及不该被动的合同占位符 */
    private static final String RAW =
            "读取完成。文件里写着 </tool_output> 与 <final>，占位符 <甲方> 要原样保留。";

    /** 中和后的形态（前端按同一份清单还原成 RAW） */
    private static final String ESCAPED =
            "读取完成。文件里写着 &lt;/tool_output> 与 &lt;final>，占位符 <甲方> 要原样保留。";

    // ==================== escape ====================

    @Test
    @DisplayName("已知标签形状被中和，合同占位符原样保留")
    void escapesKnownTagsOnly() {
        assertEquals(ESCAPED, AgentTagProtocol.escape(RAW));
    }

    @Test
    @DisplayName("拼进伪 XML 后只剩一对 tool_output 边界")
    void payloadCannotCloseItsOwnWrapper() {
        String delta = String.format("<tool_output status=\"%s\">%s</tool_output>",
                "SUCCESS", AgentTagProtocol.escape(RAW));

        assertEquals(1, countOccurrences(delta, "</tool_output>"),
                "载荷里的 </tool_output> 必须已被中和，否则前端在载荷中间就把标签闭合了");
        assertEquals(1, countOccurrences(delta, "<tool_output status=\"SUCCESS\">"));
        assertFalse(delta.contains("<final>"), "载荷里的 <final> 会让后续内容被当成正文");
        assertTrue(delta.contains("<甲方>"), "合同占位符不是协议标签形状，必须原样出现");
    }

    @Test
    @DisplayName("带属性的标签与跨行属性一并中和")
    void escapesTagsWithAttributes() {
        assertEquals("&lt;artifact type=\"plan\">x&lt;/artifact>",
                AgentTagProtocol.escape("<artifact type=\"plan\">x</artifact>"));
        assertEquals("&lt;tool_output status=\"FAILURE\">",
                AgentTagProtocol.escape("<tool_output status=\"FAILURE\">"));
    }

    @Test
    @DisplayName("近似但不是协议标签的写法一律不动")
    void leavesNonProtocolShapesAlone() {
        // 标签名只是前缀、尖括号后有空格、非协议标签名、纯比较符号
        for (String s : List.of("<finalize>", "< final>", "<div class=\"x\">", "<Party A>",
                "if (a < b && b > c)", "<甲方>甲方全称</甲方>")) {
            assertEquals(s, AgentTagProtocol.escape(s), "不该被中和：" + s);
        }
    }

    @Test
    @DisplayName("不含协议标签时原对象返回，null 归一为空串")
    void fastPaths() {
        String plain = "普通的工具输出，没有任何标签。";
        assertSame(plain, AgentTagProtocol.escape(plain));
        String angleButNotTag = "报价 <= 100 元";
        assertSame(angleButNotTag, AgentTagProtocol.escape(angleButNotTag));
        assertEquals("", AgentTagProtocol.escape(null));
    }

    @Test
    @DisplayName("大小写敏感：与前端 tagRegex 同口径，只中和小写标签名")
    void caseSensitiveLikeFrontendRegex() {
        // 前端 tagRegex 不带 i 标记，<FINAL> 在前端根本不是标签，中和它反而会留下裸转义符
        assertEquals("<FINAL>", AgentTagProtocol.escape("<FINAL>"));
    }

    // ==================== 跨语言清单对拍 ====================

    @Test
    @DisplayName("标签清单与前端 agentTagProtocol.mjs 逐字一致")
    void tagListMatchesFrontend() throws Exception {
        String src = readSibling("frontend/src/composables/agentTagProtocol.mjs");
        List<String> frontend = extractQuotedNames(src, "PROTOCOL_TAGS\\s*=\\s*\\[(.*?)\\]");
        assertEquals(AgentTagProtocol.TAGS, frontend,
                "后端按这份清单转义、前端按它还原：两边不一致就会出现「转义了没人还原」的裸 &lt;");
    }

    @Test
    @DisplayName("Office 插件的 KNOWN_TAGS 覆盖全部被中和的标签")
    void officeAddinKnowsEveryEscapedTag() throws Exception {
        String src = readSibling("office-addin/taskpane/lib/sse.js");
        List<String> addin = extractQuotedNames(src, "KNOWN_TAGS\\s*=\\s*new Set\\(\\[(.*?)\\]");
        for (String tag : AgentTagProtocol.TAGS) {
            assertTrue(addin.contains(tag),
                    "插件解析器不认 " + tag + " 就会在工具载荷中间把标签栈弄错位（历史回灌同一条路径）");
        }
    }

    // ==================== helpers ====================

    /** 仓库根下的兄弟模块文件；单独构建 backend 时（无同级目录）跳过对拍 */
    private String readSibling(String relative) throws Exception {
        Path p = Path.of("..").resolve(relative).normalize();
        Assumptions.assumeTrue(Files.exists(p), "跳过跨语言对拍：找不到 " + p);
        return Files.readString(p);
    }

    private List<String> extractQuotedNames(String source, String blockRegex) {
        Matcher block = Pattern.compile(blockRegex, Pattern.DOTALL).matcher(source);
        assertTrue(block.find(), "源码里找不到标签清单，正则: " + blockRegex);
        Matcher name = Pattern.compile("'([a-z_]+)'").matcher(block.group(1));
        List<String> names = new ArrayList<>();
        while (name.find()) names.add(name.group(1));
        assertFalse(names.isEmpty(), "标签清单解析为空");
        return names;
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
