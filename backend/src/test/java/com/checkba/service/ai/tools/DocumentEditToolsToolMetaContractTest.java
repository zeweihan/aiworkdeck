// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.ClientCapabilityService;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentEditTools} 的 {@link ToolMeta} 契约（dev-board 审计 B-02）。
 *
 * <p>病灶：编排器只对 {@code @ToolMeta(fileEffect="MODIFIED")} 的工具在执行前建
 * 本轮检查点（{@code AgentOrchestrator.dispatchTool}），并且
 * {@code applyToolSideEffects} 在 {@code meta() == null} 时**直接提前返回**——
 * 连 {@code refresh_files} 与 {@code file_change} 通知也一并不发。
 *
 * <p>而这个文件里最常用的十个写入原语（「选中这句改成 X」「在光标处插入这段条款」）
 * 曾经只有 {@code @Tool}、完全没有 {@code @ToolMeta}：一轮对话只用它们的话，
 * {@code doc_restore_checkpoint} 因为没有快照而无法恢复，前端也不知道文档被 AI 改过。
 * 「恢复本轮快照」这条产品承诺在最常用的路径上是空的。
 *
 * <p>所以这里钉两条：<b>每个 @Tool 都要有 @ToolMeta</b>（漏一个就是一条静默失效的支路），
 * 以及<b>只读工具不许声明 fileEffect</b>（虚假的 file_change 会让用户去找一个不存在的改动）。
 */
class DocumentEditToolsToolMetaContractTest {

    /**
     * 必须声明 {@code fileEffect="MODIFIED"} 的写入原语。
     *
     * <p>逐个点名而不是靠词头推断：推断规则（{@link ClientCapabilityService#isDocumentWritingTool}）
     * 把 {@code doc_collapse_cursor} 这类纯光标操作也算成写入，拿它做正向断言会造成误报。
     */
    private static final Set<String> MUST_BE_MODIFIED = Set.of(
            "doc_replace_nth_match",
            "doc_delete_match",
            "doc_delete_text",
            "doc_replace_selection",
            "doc_insert_at_cursor",
            "doc_insert_under_heading",
            "doc_replace_at_anchor",
            "doc_delete_selection",
            "doc_format_selection",
            "doc_set_paragraph_format",
            "doc_undo",
            "doc_redo");

    private static List<Method> toolMethods() {
        List<Method> tools = Arrays.stream(DocumentEditTools.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(Tool.class) != null)
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .toList();
        assertFalse(tools.isEmpty(), "用例前提：应当能反射到 @Tool 方法");
        return tools;
    }

    @Test
    @DisplayName("每个 @Tool 都带 @ToolMeta：漏一个就是一条静默失效的副作用支路")
    void everyToolDeclaresMeta() {
        List<String> missing = new ArrayList<>();
        for (Method m : toolMethods()) {
            if (m.getAnnotation(ToolMeta.class) == null) {
                missing.add(m.getName());
            }
        }
        assertTrue(missing.isEmpty(),
                "这些工具没有 @ToolMeta：编排器的 applyToolSideEffects 在 meta==null 时直接 return，"
                        + "它们既不建检查点、也不刷文件树、也不发 file_change。缺注解的工具：" + missing);
    }

    @Test
    @DisplayName("@ToolMeta 不能是空壳：displayName 与 category 都要填")
    void metaCarriesDisplayNameAndCategory() {
        List<String> blank = new ArrayList<>();
        for (Method m : toolMethods()) {
            ToolMeta meta = m.getAnnotation(ToolMeta.class);
            if (meta == null) continue;   // 由上一条用例报告
            if (meta.displayName().isEmpty() || meta.category().isEmpty()) {
                blank.add(m.getName());
            }
        }
        assertTrue(blank.isEmpty(), "displayName/category 为空的工具：" + blank);
    }

    @Test
    @DisplayName("十二个写入原语必须是 MODIFIED，否则本轮检查点不会建、doc_restore_checkpoint 无从恢复")
    void writePrimitivesDeclareModified() {
        List<String> wrong = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Method m : toolMethods()) {
            if (!MUST_BE_MODIFIED.contains(m.getName())) continue;
            seen.add(m.getName());
            ToolMeta meta = m.getAnnotation(ToolMeta.class);
            if (meta == null || !"MODIFIED".equals(meta.fileEffect())) {
                wrong.add(m.getName() + " -> " + (meta == null ? "<无 @ToolMeta>" : meta.fileEffect()));
            }
        }
        assertEquals(MUST_BE_MODIFIED.size(), seen.size(),
                "点名清单与实际方法名对不上（改名了？）。实际命中：" + seen);
        assertTrue(wrong.isEmpty(), "这些写入原语没声明 fileEffect=MODIFIED：" + wrong);
    }

    @Test
    @DisplayName("只读工具不许声明 fileEffect：假的 file_change 会让用户去找一个不存在的改动")
    void readOnlyToolsDeclareNoFileEffect() {
        List<String> wrong = new ArrayList<>();
        for (Method m : toolMethods()) {
            // 单向校验：只用「判定为只读」这一侧。反方向（写入 ⇒ MODIFIED）会把
            // doc_collapse_cursor 这类纯光标操作误判成漏标。
            if (ClientCapabilityService.isDocumentWritingTool(m.getName())) continue;
            ToolMeta meta = m.getAnnotation(ToolMeta.class);
            if (meta != null && !meta.fileEffect().isEmpty()) {
                wrong.add(m.getName() + " -> " + meta.fileEffect());
            }
        }
        assertTrue(wrong.isEmpty(), "只读工具声明了 fileEffect：" + wrong);
    }
}
