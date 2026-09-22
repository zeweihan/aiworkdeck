// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OfficeBridgeService#ACTION_TIMEOUT_SECONDS} 的覆盖护栏（审计 B-06，dev-board#806）。
 *
 * <p><b>为什么要一条扫源码的护栏，而不是把值写死在测试里</b>：这张表是
 * 「一次调用做 N 件事的原语必须进表」这条纪律的载体，而它的失效方式是**沉默的**——
 * 新加一个批量原语忘了登记，后端 30 秒就放弃等待，插件那边照样在写，模型拿到「失败」
 * 再发一次，用户看到同一段内容被写进文档两遍。表自己的类注释把这件事写得很清楚，
 * 可在 dev-board#806 之前那张表只有两条：insert_table / excel_set_values /
 * excel_set_formulas / excel_add_pivot_table / ppt_add_table 这些明显的批量原语
 * 一个都没进去，而对照的 LOWA 桥同款表有 35 条并有三处 parity 用例守着。
 *
 * <p>判据分两档：
 * <ul>
 *   <li><b>机械档</b>：工具方法带一个 {@code ...Json} 参数（editsJson / rowsJson /
 *       valuesJson / formulasJson / rowFieldsJson …）就是批量原语——那种参数一次能装
 *       几十上百条，跑得久是结构性的，不是偶然。这一档**自动发现**，新增即受管。</li>
 *   <li><b>判断档</b>：参数看不出批量、但实现要遍历整篇文档的（整篇套标准格式、
 *       从锚点起连套 N 段编号、整表逐格设边框、全篇每页每个形状找替换……）。
 *       这一档在下面逐条列出并写明理由，新增时得自己想清楚再加。</li>
 * </ul>
 *
 * <p><b>读取类刻意不在这张表里</b>：LOWA 桥的读取类是 dev-board#729 ③ 拿真机 telemetry
 * （86 次超时里 79 次是读命令）换来的，插件桥这边没有同等证据，而读取超时只是白等、
 * 不会造成双写。等有了真机数据再说，不跟着抄。
 */
class OfficeBridgeTimeoutCoverageTest {

    private static final Path OFFICE_EDIT_TOOLS =
            Path.of("src/main/java/com/checkba/service/ai/tools/OfficeEditTools.java");

    /** 分级超时的下限：与 LOWA 桥的批量档同值。 */
    private static final int LONG_BUDGET_SECONDS = 120;

    /**
     * 判断档：参数列表看不出批量、但实现是「一次调用做 N 件事」的命令。
     * 值是理由，出现在断言失败信息里——将来有人想删一条，先得读懂为什么加。
     */
    private static final Map<String, String> JUDGMENT_BULK = new LinkedHashMap<>();

    static {
        JUDGMENT_BULK.put("apply_standard_format",
                "整篇按律所标准格式逐段（按 run 合并后仍是逐段区间）落笔");
        JUDGMENT_BULK.put("set_numbering",
                "从锚点起给连续 N 段套编号；旧宿主不支持 List API 时退化成逐段手写编号前缀");
        JUDGMENT_BULK.put("format_table",
                "整张表逐格设边框/底纹，格数随表大小线性增长");
        JUDGMENT_BULK.put("insert_image",
                "单图上限 2MB，经 base64 过桥后体积再膨胀约三分之一，落笔前先要把整串搬进宿主");
        JUDGMENT_BULK.put("ppt_add_slide",
                "追加空白页 + moveTo 挪位置 + 标题/正文两个文本框，一条命令里四次 sync");
        JUDGMENT_BULK.put("ppt_replace_text",
                "遍历全篇每一页每一个形状（含组合与表格递归）找命中，再逐处从右到左替换");
        JUDGMENT_BULK.put("ppt_format_text",
                "与 ppt_replace_text 同一条遍历路径，只是把替换换成设字体");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> table() throws Exception {
        Field f = OfficeBridgeService.class.getDeclaredField("ACTION_TIMEOUT_SECONDS");
        f.setAccessible(true);
        return (Map<String, Integer>) f.get(null);
    }

    /**
     * 源码里的工具方法头 `public String office_xxx(`。
     *
     * <p>参数列表**不能**用 {@code [^)]*} 去截：每个 {@code @P("…")} 自己就带一个半角右括号，
     * 那样抓到的永远只是第一个 @P，于是这条护栏会对所有批量工具一路绿灯（第一版就是这么假绿的）。
     * 参数列表的真正终点是 {@code ") {"}，见 {@link #paramsOf}。
     */
    private static final Pattern TOOL_METHOD =
            Pattern.compile("public\\s+String\\s+(office_[a-zA-Z_0-9]+)\\s*\\(");

    /** 从方法头的左括号往后扫到 `) {`——那才是参数列表的终点。 */
    private static String paramsOf(String src, int openParenEnd) {
        for (int i = openParenEnd; i < src.length(); i++) {
            if (src.charAt(i) != ')') continue;
            int j = i + 1;
            while (j < src.length() && Character.isWhitespace(src.charAt(j))) j++;
            if (j < src.length() && src.charAt(j) == '{') return src.substring(openParenEnd, i);
        }
        return "";
    }

    /** 参数里的批量载荷：标识符以 Json 结尾（editsJson / rowsJson / valuesJson / …）。 */
    private static final Pattern JSON_PARAM = Pattern.compile("\\b[a-zA-Z]+Json\\b");

    /** 真正下发到插件的 command 名：`executeOfficeCommand(conversationId, "xxx"` 或表格结构那条同形的分发器。 */
    private static final Pattern DISPATCH =
            Pattern.compile("\\(\\s*conversationId\\s*,\\s*\"([a-z][a-z_0-9]*)\"");

    private static String source() throws IOException {
        return Files.readString(OFFICE_EDIT_TOOLS, StandardCharsets.UTF_8);
    }

    /** 方法名去掉 office_ 前缀就是 command——唯一的例外是 office_pass_step（它转调 replace_batch）。 */
    private static String commandOf(String methodName) {
        return methodName.substring("office_".length());
    }

    @Test
    @DisplayName("带 ...Json 批量参数的 office_* 原语全部进分级超时表（自动发现，新增即受管）")
    void everyBulkJsonToolIsRegistered() throws Exception {
        Map<String, Integer> t = table();
        String src = source();
        Set<String> missing = new TreeSet<>();
        int seen = 0;
        Matcher m = TOOL_METHOD.matcher(src);
        while (m.find()) {
            String method = m.group(1);
            if (!JSON_PARAM.matcher(paramsOf(src, m.end())).find()) continue;
            seen++;
            // office_pass_step 不是自己一条 command：它切块后转调 replace_batch 落笔，
            // 预算跟着 replace_batch 走，所以这里查的就是 replace_batch。
            String command = "office_pass_step".equals(method) ? "replace_batch" : commandOf(method);
            Integer seconds = t.get(command);
            if (seconds == null || seconds < LONG_BUDGET_SECONDS) {
                missing.add(command + "（来自 " + method + "）");
            }
        }
        // 空断言防线：抽不出批量工具时这条用例会「全绿」，而它守的恰恰是「有没有漏」。
        assertTrue(seen >= 6, "只认出了 " + seen + " 个带 ...Json 参数的工具，抽取正则该跟着源码改");
        assertTrue(missing.isEmpty(),
                "这些批量原语没进 OfficeBridgeService.ACTION_TIMEOUT_SECONDS（或预算 < "
                        + LONG_BUDGET_SECONDS + "s）："
                        + missing
                        + "。平超时不会报错，只会让后端先放弃、模型重发一次，把同一段内容写进文档两遍。");
    }

    @Test
    @DisplayName("遍历整篇文档的写入类原语（参数看不出批量）也在表里")
    void judgementBulkCommandsAreRegistered() throws Exception {
        Map<String, Integer> t = table();
        for (Map.Entry<String, String> e : JUDGMENT_BULK.entrySet()) {
            Integer seconds = t.get(e.getKey());
            assertTrue(seconds != null && seconds >= LONG_BUDGET_SECONDS,
                    "command " + e.getKey() + " 应有 >= " + LONG_BUDGET_SECONDS
                            + "s 预算：" + e.getValue() + "（当前=" + seconds + "）");
        }
    }

    @Test
    @DisplayName("表里没有拼错的键：每个键都是 OfficeEditTools 真的会下发的 command")
    void tableHasNoTypos() throws Exception {
        String src = source();
        Set<String> dispatched = new LinkedHashSet<>();
        Matcher m = DISPATCH.matcher(src);
        while (m.find()) dispatched.add(m.group(1));
        assertTrue(dispatched.size() > 50, "命令抽取失败（只找到 " + dispatched.size() + " 条），正则该跟着源码改");

        Set<String> unknown = new TreeSet<>(table().keySet());
        unknown.removeAll(dispatched);
        assertTrue(unknown.isEmpty(),
                "分级超时表里这些键对不上任何一条真实 command，拼错的键不会报错、只会静默失效：" + unknown);
    }

    @Test
    @DisplayName("表外命令仍是 30 秒默认值，null 键不抛 NPE")
    void defaultsUnchanged() {
        assertEquals(30, OfficeBridgeService.timeoutSecondsFor("get_text"));
        assertEquals(30, OfficeBridgeService.timeoutSecondsFor("replace_text"));
        assertEquals(30, OfficeBridgeService.timeoutSecondsFor(null));
    }
}
