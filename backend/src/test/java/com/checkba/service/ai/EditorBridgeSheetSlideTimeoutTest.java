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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sheet_*} / {@code slide_*} 在 {@link EditorBridgeService#ACTION_TIMEOUT_SECONDS}
 * 里的覆盖（审计 B-17，dev-board#806）。
 *
 * <p>这张表此前只登记了这两族的**读取类**八条（dev-board#729 ③ 那一批），
 * 全部写入类——{@code sheet_write_cells}（一次最多 2000 格）、
 * {@code sheet_add_pivot_table}、{@code slide_add_page}（插入前拍全篇标题快照、
 * 插入后逐页核对补回）等——都落在 30 秒默认值上。按这张表自己的立表理由：
 * <b>worker 不会因为后端放弃等待就停下</b>，超时后模型被告知失败、重发一次就是双写。
 * 而写入类比读取类危险得多：读取超时只是白等，写入超时会写两遍，
 * 且 {@code BULK_INSERT_TEXT_PARAM} 那道去重闸只覆盖四个 {@code doc_} 插入 action，
 * {@code sheet_} / {@code slide_} 完全不在闸内。
 *
 * <p>因此这里立的规矩是**整族登记**，而不是挑几个：后端会下发的每一条
 * {@code sheet_*} / {@code slide_*} 都要在表里，只有下面这份「瞬时导航类」白名单例外。
 * 挑着登记必然重演 B-17——漏一条不报错，只在真机上偶发双写。
 */
class EditorBridgeSheetSlideTimeoutTest {

    private static final Path TOOLS_DIR = Path.of("src/main/java/com/checkba/service/ai/tools");

    /**
     * 允许留在 30 秒默认值上的：只挪光标/选区、不碰内容，毫秒级就该回来。
     * 与表自身注释里「瞬时的交互类本来就该在毫秒级回来，没有理由进表」同一条口径。
     */
    private static final Set<String> INSTANT_NAVIGATION = Set.of(
            "sheet_select_range",
            "slide_goto");

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> table() throws Exception {
        Field f = EditorBridgeService.class.getDeclaredField("ACTION_TIMEOUT_SECONDS");
        f.setAccessible(true);
        return (Map<String, Integer>) f.get(null);
    }

    private static final Pattern DISPATCH = Pattern.compile(
            "executeEditorCommand\\(\\s*\"((?:sheet|slide)_[a-z_0-9]+)\"");

    /** 后端真的会下发的 sheet_/slide_ action（从工具层源码抽，名单不会腐烂）。 */
    private static Set<String> dispatchedActions() throws IOException {
        Set<String> actions = new TreeSet<>();
        try (var files = Files.list(TOOLS_DIR)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = DISPATCH.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) actions.add(m.group(1));
            }
        }
        return actions;
    }

    @Test
    @DisplayName("后端会下发的每一条 sheet_/slide_ action 都在分级超时表里（导航类除外）")
    void everySheetAndSlideActionIsBudgeted() throws Exception {
        Set<String> actions = dispatchedActions();
        // 空断言防线：抽不出 action 时这条用例会「全绿」，而它守的恰恰是「有没有漏」。
        assertTrue(actions.size() >= 40,
                "只认出了 " + actions.size() + " 条 sheet_/slide_ action，抽取正则该跟着源码改");

        Map<String, Integer> t = table();
        Set<String> missing = new TreeSet<>();
        for (String action : actions) {
            if (INSTANT_NAVIGATION.contains(action)) continue;
            Integer seconds = t.get(action);
            if (seconds == null || seconds < 120) missing.add(action + "=" + seconds);
        }
        assertTrue(missing.isEmpty(),
                "这些 sheet_/slide_ action 没进 EditorBridgeService.ACTION_TIMEOUT_SECONDS（或 < 120s）："
                        + missing
                        + "。worker 打不断：后端 30 秒放弃等待后它照样写完，模型却拿到「失败」并重发一次——"
                        + "写入类的代价是内容写两遍，而这两族完全不在 BULK_INSERT_TEXT_PARAM 去重闸内。");
    }

    @Test
    @DisplayName("导航类仍是 30 秒默认值：白名单不是摆设")
    void navigationStaysOnTheDefault() throws Exception {
        Map<String, Integer> t = table();
        for (String action : INSTANT_NAVIGATION) {
            assertEquals(null, t.get(action),
                    action + " 只挪光标不碰内容，没有理由占用长预算");
            assertEquals(30, EditorBridgeService.timeoutSecondsFor(action));
        }
    }

    @Test
    @DisplayName("表里没有拼错的 sheet_/slide_ 键：每个键都是真会下发的 action")
    void tableHasNoSheetSlideTypos() throws Exception {
        Set<String> actions = dispatchedActions();
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : table().keySet()) {
            if ((key.startsWith("sheet_") || key.startsWith("slide_")) && !actions.contains(key)) {
                unknown.add(key);
            }
        }
        assertEquals(List.of(), List.copyOf(unknown),
                "分级超时表里这些键对不上任何一条真实 action，拼错不会报错、只会静默失效");
    }
}
