// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具失败判据：中文「错误：」前缀必须和英文 "Error" 同等对待。
 *
 * <p>病灶：{@code ToolResult.success()} 只认英文 {@code "Error"} 前缀与
 * {@code {"error"} } JSON 形态，而 MemoryTools / TagTools / TaskTools /
 * EvidenceTools / PptxTools 共 37 处失败返回用的是中文「错误：」。
 * 这些工具**自认为在报错**，判据却听不见，于是：
 * <ul>
 *   <li>过程卡给失败的调用打绿勾（用户看到「查询企业工商信息 ✓」而内容是查不到）；</li>
 *   <li>{@code appendFailureNudge} 把 {@code consecutiveFailures} 清零，
 *       连续失败纠正回路（CONSECUTIVE_FAILURE_NUDGE）对这些工具**永远不触发**，
 *       模型可以对着同一个错误一直重试到步数上限；</li>
 *   <li>埋点 {@code ai.tool success=true}，外部服务全线失败时指标仍是健康的。</li>
 * </ul>
 *
 * <p>判据只认<b>前缀</b>，不认「失败/不可用」这类词出现在正文任意位置——
 * 合同正文里出现「违约」「失败」是家常便饭，按包含匹配会把正常结果误判成失败，
 * 那比漏判更糟。
 */
class ToolFailureClassificationTest {

    private static boolean success(String output) {
        return new ToolRegistry.ToolResult(output, null, true).success();
    }

    @Test
    @DisplayName("中文「错误：」前缀 = 失败（MemoryTools/TagTools/TaskTools 等 37 处在用）")
    void chineseErrorPrefixIsAFailure() {
        assertFalse(success("错误：无法获取当前项目ID，请在项目上下文中使用此工具。"));
        assertFalse(success("错误：无法获取当前用户ID，无法保存用户级记忆。"));
        assertFalse(success("错误：打标签失败，标签不存在"));
        assertFalse(success("错误：PPTX 生成服务不可用。请先启动 Docker 服务"));
    }

    @Test
    @DisplayName("前导空白不影响判定（与英文分支同口径）")
    void leadingWhitespaceDoesNotHideTheMarker() {
        assertFalse(success("\n  错误：无法获取当前项目ID。"));
        assertFalse(success("\n  Error: file not found"));
    }

    @Test
    @DisplayName("既有的英文与 JSON 失败形态不变")
    void existingFailureShapesStillDetected() {
        assertFalse(success("Error: File not found."));
        assertFalse(success("{\"error\": \"操作超时\"}"));
        assertFalse(success("{ \"error\" : \"editor not open\" }"));
    }

    @Test
    @DisplayName("正文里出现「失败/错误」不算失败——判据只认前缀，绝不按包含匹配")
    void wordsInsideTheBodyMustNotFlipTheVerdict() {
        assertTrue(success("第三条 违约责任：一方未能履行的，视为违约，另一方有权解除合同。"),
                "合同正文里出现失败/违约字样是家常便饭，误判成失败比漏判更糟");
        assertTrue(success("检索到 3 条结果，其中 1 条记载该公司曾因申报错误被行政处罚。"));
        assertTrue(success("[文件 起诉状.docx]\n原告诉称：被告交付失败，构成根本违约。"));
    }

    @Test
    @DisplayName("正常结果与空白仍按既有规则")
    void normalResultsUnchanged() {
        assertTrue(success("合同正文……"));
        assertFalse(new ToolRegistry.ToolResult(null, null, true).success());
        assertFalse(new ToolRegistry.ToolResult("whatever", null, false).success());
    }

    // ==================== 源码扫描：失败文案必须带前缀（dev-board#1065 T-11） ====================

    private static final Path TOOLS_DIR = Path.of("src/main/java/com/checkba/service/ai/tools");

    /**
     * 刻意不带前缀的软失败（文件名 → 文案开头），<b>只许有这两条</b>，都是维护者裁决过的：
     * <ul>
     *   <li>LegalTools「法规检索本次不可用」：跳过法规检索、继续把任务做完才是正确的下一步，
     *       判成失败会让连续失败纠正回路去催模型换思路；</li>
     *   <li>MeetingTools「该会议不属于当前项目」：只陈述 meetingId 越界这件事。</li>
     * </ul>
     * 两句今天都不含「失败」「出错」，扫描本身不会命中它们；列在这里是为了让下一个往里加
     * 「失败」二字的人知道这是刻意的，并由 {@link #softFailureAllowListStillPointsAtRealCode} 防止名单腐烂。
     */
    private static final Map<String, String> SOFT_FAILURE_ALLOW_LIST = Map.of(
            "LegalTools.java", "法规检索本次不可用",
            "MeetingTools.java", "该会议不属于当前项目");

    @Test
    @DisplayName("tools/*.java 里 return 的文案含「失败」「出错」时，必须以 Error: / 错误： 开头")
    void everyFailureLiteralReturnedByAToolCarriesTheMarker() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(TOOLS_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String name = file.getFileName().toString();
                for (String hit : unmarkedFailureReturns(Files.readString(file, StandardCharsets.UTF_8))) {
                    String allowed = SOFT_FAILURE_ALLOW_LIST.get(name);
                    if (allowed != null && hit.contains(allowed)) {
                        continue;
                    }
                    offenders.add(name + ": " + hit);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "这些失败返回不带 Error / 错误 前缀，ToolResult.success() 会把它们判成成功："
                        + "过程卡打绿勾、连续失败纠正回路被清零、埋点记成功。给它们加上「错误：」前缀；"
                        + "确属刻意的软失败，由维护者裁决后再进 SOFT_FAILURE_ALLOW_LIST");
    }

    @Test
    @DisplayName("扫描器本身不瞎：已知的坏形态必须被认出来，前缀齐全的与借助帮手函数的不误报")
    void theScannerRecognisesTheShapesItIsMeantToCatch() {
        String sample = String.join("\n",
                "class X {",
                "    String a() { return \"保存记忆时出错: \" + e.getMessage(); }",
                "    String b() { return ok ? \"已完成\" : \"出图失败：\" + why; }",
                "    String c() { return action + \"失败：\" + detail; }",
                "    String d() { return String.format(\"大纲生成失败: %s\", e); }",
                "    String e() { return \"错误：出图失败：\" + why; }",
                "    String f() { return \"Error: 读取失败\"; }",
                "    String g() { return errorOf(\"读取 PDF 失败\", e); }",
                "    String h() { return \"检索到 3 条结果\"; }",
                "    // return \"注释里的失败不算\";",
                "    String i() { return \"错误：\" + action + \"失败：\" + detail; }",
                "}");
        List<String> hits = unmarkedFailureReturns(sample);
        assertEquals(4, hits.size(), "应当恰好认出 a/b/c/d 四处：" + hits);
        assertTrue(hits.get(0).contains("保存记忆时出错"), hits.toString());
        assertTrue(hits.get(1).contains("出图失败"), hits.toString());
        assertTrue(hits.get(2).startsWith("action"), hits.toString());
        assertTrue(hits.get(3).contains("大纲生成失败"), hits.toString());
    }

    @Test
    @DisplayName("软失败白名单里的每一条都真实存在（名单不许指向已经不存在的代码）")
    void softFailureAllowListStillPointsAtRealCode() throws IOException {
        for (Map.Entry<String, String> e : SOFT_FAILURE_ALLOW_LIST.entrySet()) {
            String src = Files.readString(TOOLS_DIR.resolve(e.getKey()), StandardCharsets.UTF_8);
            assertTrue(src.contains("\"" + e.getValue()),
                    e.getKey() + " 里已经找不到「" + e.getValue() + "」，白名单该跟着删掉");
        }
    }

    /**
     * 找出所有「直接产出文案」的 return 分支里，含「失败」「出错」却不以前缀开头的那些。
     *
     * <p>口径：每个 return 表达式按顶层三元运算拆成分支；分支以字符串字面量、
     * {@code String.format(}、{@code LangText.of(} 或「变量 + 字符串」开头时算直接产出，
     * 其余（如 {@code errorOf(...)} 这类帮手函数）交给帮手自己的 return 去接受同一条检查。
     * 分支里任一字面量含「失败」「出错」时，第一个字面量必须以 {@code Error} 或 {@code 错误} 开头——
     * 与 {@code ToolResult.success()} 的判据逐字对应。
     */
    static List<String> unmarkedFailureReturns(String source) {
        String code = stripComments(source);
        List<String> hits = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = indexOfKeyword(code, "return", from);
            if (at < 0) {
                break;
            }
            int end = endOfStatement(code, at + "return".length());
            String expr = code.substring(at + "return".length(), end);
            for (String branch : topLevelBranches(expr)) {
                String b = branch.strip();
                if (!producesTextDirectly(b)) {
                    continue;
                }
                List<String> literals = literalsIn(b);
                if (literals.isEmpty()) {
                    continue;
                }
                boolean mentionsFailure = literals.stream()
                        .anyMatch(l -> l.contains("失败") || l.contains("出错"));
                String first = literals.get(0);
                if (mentionsFailure && !first.startsWith("Error") && !first.startsWith("错误")) {
                    hits.add(b.replaceAll("\\s+", " "));
                }
            }
            from = end;
        }
        return hits;
    }

    private static boolean producesTextDirectly(String branch) {
        return branch.startsWith("\"")
                || branch.startsWith("String.format(")
                || branch.startsWith("LangText.of(")
                || branch.matches("(?s)^[A-Za-z_][\\w.]*(\\(\\))?\\s*\\+.*");
    }

    /** 去掉行注释与块注释，字符串与字符字面量原样保留。 */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int j = skipLiteral(s, i);
                out.append(s, i, j);
                i = j;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int close = s.indexOf("*/", i + 2);
                i = close < 0 ? s.length() : close + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 从 start 处的引号起跳过一个字面量（含文本块），返回其后的位置。 */
    private static int skipLiteral(String s, int start) {
        char q = s.charAt(start);
        if (q == '"' && s.startsWith("\"\"\"", start)) {
            int close = s.indexOf("\"\"\"", start + 3);
            return close < 0 ? s.length() : close + 3;
        }
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == q || c == '\n') {
                return i + 1;
            } else {
                i++;
            }
        }
        return s.length();
    }

    private static int indexOfKeyword(String code, String kw, int from) {
        int i = from;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(code, i);
                continue;
            }
            if (code.startsWith(kw, i)
                    && (i == 0 || !Character.isJavaIdentifierPart(code.charAt(i - 1)))
                    && (i + kw.length() >= code.length()
                        || !Character.isJavaIdentifierPart(code.charAt(i + kw.length())))) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static int endOfStatement(String code, int from) {
        int depth = 0;
        int i = from;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(code, i);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ';' && depth <= 0) {
                return i;
            }
            i++;
        }
        return code.length();
    }

    private static List<String> topLevelBranches(String expr) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(expr, i);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 0 && c == ':' && i + 1 < expr.length() && expr.charAt(i + 1) == ':') {
                i += 2;
                continue;
            } else if (depth == 0 && (c == '?' || c == ':')) {
                parts.add(expr.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        parts.add(expr.substring(start));
        return parts;
    }

    private static List<String> literalsIn(String branch) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < branch.length()) {
            char c = branch.charAt(i);
            if (c == '"') {
                int j = skipLiteral(branch, i);
                if (branch.startsWith("\"\"\"", i)) {
                    out.add(branch.substring(i + 3, Math.max(i + 3, j - 3)).strip());
                } else {
                    out.add(branch.substring(i + 1, Math.max(i + 1, j - 1)));
                }
                i = j;
            } else if (c == '\'') {
                i = skipLiteral(branch, i);
            } else {
                i++;
            }
        }
        return out;
    }
}
