// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PdfTools} 的 {@link ToolMeta} 契约（dev-board#805，沿用 K10 的写法）。
 *
 * <p>三件事：
 * <ol>
 *   <li>每个 {@code @Tool} 都要有 {@code @ToolMeta}——编排器的 {@code applyToolSideEffects}
 *       在 {@code meta() == null} 时直接提前返回，连 refresh_files 与 file_change 都不发，
 *       表现就是「新文件生成了，文件树里看不见」；</li>
 *   <li>六个页操作工具产出的是<b>新文件</b>：{@code fileEffect="ADDED"} +
 *       {@code refreshFiles=true}，而且<b>不许</b>声明 {@code requiresHost=LOWA}——
 *       它们的收尾不经 EditorBridgeService 的四个文档级 UI 指令（K19 判据），
 *       声明了等于在 Office / none 会话里白白收走一份纯服务端就能做完的能力；</li>
 *   <li>只读工具不许声明 fileEffect：假的 file_change 会让用户去找一个不存在的改动。</li>
 * </ol>
 */
class PdfToolsToolMetaContractTest {

    /** 产出新文件的六个页操作工具（dev-board#805）。 */
    private static final Set<String> PAGE_OPS = new TreeSet<>(Set.of(
            "pdf_merge",
            "pdf_split",
            "pdf_extract_pages",
            "pdf_delete_pages",
            "pdf_rotate_pages",
            "pdf_add_page_numbers"));

    /** 纯读取、不碰磁盘的工具。 */
    private static final Set<String> READ_ONLY = new TreeSet<>(Set.of(
            "pdf_list_files",
            "pdf_inspect"));

    private static List<Method> toolMethods() {
        List<Method> tools = Arrays.stream(PdfTools.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(Tool.class) != null)
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .toList();
        assertFalse(tools.isEmpty(), "用例前提：应当能反射到 @Tool 方法");
        return tools;
    }

    @Test
    @DisplayName("每个 @Tool 都带 @ToolMeta，且 displayName / category 不留空")
    void everyToolDeclaresAUsefulMeta() {
        List<String> bad = new ArrayList<>();
        for (Method m : toolMethods()) {
            ToolMeta meta = m.getAnnotation(ToolMeta.class);
            if (meta == null) {
                bad.add(m.getName() + " -> <无 @ToolMeta>");
            } else if (meta.displayName().isEmpty() || meta.category().isEmpty()) {
                bad.add(m.getName() + " -> displayName/category 为空");
            }
        }
        assertTrue(bad.isEmpty(), "这些工具的 @ToolMeta 不合格：" + bad);
    }

    @Test
    @DisplayName("六个页操作工具都在，声明 ADDED + refreshFiles，且不声明 LOWA 宿主")
    void pageOperationsAddFilesAndNeedNoDesktopHost() {
        Set<String> seen = new TreeSet<>();
        List<String> wrong = new ArrayList<>();
        for (Method m : toolMethods()) {
            if (!PAGE_OPS.contains(m.getName())) continue;
            seen.add(m.getName());
            ToolMeta meta = m.getAnnotation(ToolMeta.class);
            if (meta == null) {
                wrong.add(m.getName() + " -> <无 @ToolMeta>");
                continue;
            }
            if (!"ADDED".equals(meta.fileEffect())) {
                wrong.add(m.getName() + " -> fileEffect=" + meta.fileEffect());
            }
            if (!meta.refreshFiles()) {
                wrong.add(m.getName() + " -> refreshFiles=false（新文件不会出现在文件树里）");
            }
            if (meta.requiresHost() != ToolMeta.Host.NONE) {
                wrong.add(m.getName() + " -> requiresHost=" + meta.requiresHost()
                        + "（它只产出新文件并登记文件树，不发文档级 UI 指令）");
            }
        }
        assertEquals(PAGE_OPS, seen, "点名清单与实际方法名对不上（改名了？）");
        assertTrue(wrong.isEmpty(), wrong.toString());
    }

    @Test
    @DisplayName("只读工具不许声明 fileEffect")
    void readOnlyToolsDeclareNoFileEffect() {
        List<String> wrong = new ArrayList<>();
        for (Method m : toolMethods()) {
            if (!READ_ONLY.contains(m.getName())) continue;
            ToolMeta meta = m.getAnnotation(ToolMeta.class);
            if (meta != null && !meta.fileEffect().isEmpty()) {
                wrong.add(m.getName() + " -> " + meta.fileEffect());
            }
        }
        assertTrue(wrong.isEmpty(), "只读工具声明了 fileEffect：" + wrong);
    }
}
