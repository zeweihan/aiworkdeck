// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.tools.AgentToolComponent;
import com.checkba.service.ai.tools.ToolMeta;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ToolMeta} 声明层的契约（dev-board#799，审计 A9 / A15）。
 *
 * <p>两件事，各有一条自维护的绊线：
 * <ol>
 *   <li><b>requiresHost</b>：哪些工具声明了 LOWA 宿主依赖，逐名钉住；
 *       并扫源码找出「往桌面前端发了文档级 UI 指令」的组件，与声明对拍——
 *       新增工具开始发 open_file / reload_file 而没人声明宿主时，这里会红。</li>
 *   <li><b>offerToModel</b>：永久停用/调试类工具不下发规格，但仍然登记
 *       （只裁 spec、不裁 resolve/execute，是本仓已经确立的口径）。</li>
 * </ol>
 */
class ToolDeclarationContractTest {

    /**
     * 工具收尾走 EditorBridgeService 的四个<b>文档级 UI 指令</b>时就要声明
     * {@code requiresHost = LOWA}。这四条都指名一份文档、要求桌面前端把它打开或重新加载。
     *
     * <p>{@code sendRefreshFilesAction}（刷文件树）与 {@code sendComponentRequiredAction}
     * （引导下载组件）刻意不在此列：它们是环境通知不是交付物，按它们判会把
     * {@code write_docx} / {@code create_folder} 这类纯后端建文件的工具一并锁进 LOWA，
     * Office 会话里连新建文件都做不了。
     */
    private static final List<String> DOCUMENT_UI_ACTIONS = List.of(
            "sendOpenFileAction",
            "sendReloadFileAction",
            "sendTextReloadFileAction",
            "sendPptConfigAction");

    /**
     * 声明了 {@code requiresHost = LOWA} 的全部工具，逐名钉住（审计 A9）。
     *
     * <p>判据是上面那四个 send。逐条来历：
     * <ul>
     *   <li>{@code pptx_open_file} / {@code litigation_render} /
     *       {@code litigation_timeline_render} / {@code pdf_to_word} —— sendOpenFileAction，
     *       交付物是「在桌面编辑器里打开给用户看」，返回文案也这么写；</li>
     *   <li>{@code pptx_generate} —— sendPptConfigAction，发完就返回「等待用户操作...」，
     *       而那个配置界面在任务窗格里根本不存在，模型会停在那里等一个永远不会来的确认；</li>
     *   <li>{@code pptx_apply_format} —— sendReloadFileAction；PowerPoint 任务窗格还另有
     *       office_ppt_* 作用在真正打开的那份 deck 上，两套同时可见会索引打架（审计 B-09）；</li>
     *   <li>{@code pdf_highlight} / {@code pdf_annotate} / {@code pdf_redact} /
     *       {@code pdf_replace_text} —— PdfTools.finishModification 里的 sendReloadFileAction；</li>
     *   <li>{@code text_write_file} / {@code text_find_replace} ——
     *       TextFileEditTools.writeBack 里的 sendTextReloadFileAction。</li>
     * </ul>
     *
     * <p><b>要增删先读这一段</b>：后四组（pdf_* 与 text_*）的实际写入是纯服务端的，
     * 声明 LOWA 等于在 Office/none 会话里一并收走那份真能力。这是按审计口径做的取舍
     * （那些会话里用户既没有文件树也没有预览，拿不到结果，而工具还在承诺「编辑器会重载」），
     * 不是顺手扩大的——改口径要连同 {@code ToolMeta.requiresHost} 的 javadoc 一起改。
     */
    private static final Set<String> EXPECTED_LOWA_ONLY = new TreeSet<>(Set.of(
            "pptx_open_file",
            "pptx_generate",
            "pptx_apply_format",
            "litigation_render",
            "litigation_timeline_render",
            "pdf_to_word",
            "pdf_highlight",
            "pdf_annotate",
            "pdf_redact",
            "pdf_replace_text",
            "text_write_file",
            "text_find_replace"));

    /**
     * 源码里出现上述四个 send 的工具组件，连<b>调用点条数</b>一起钉住。
     *
     * <p>为什么连条数一起钉：只钉文件名的话，往 {@code PptxTools} 里再加一个发 open_file 的
     * 新工具不会让集合变化，绊线形同虚设。钉条数之后，任何一处新增/删除都会红，
     * 改的人必须回来决定「这个新工具要不要声明 requiresHost」。
     *
     * <p>{@code DocumentEditTools} 在列但<b>不需要声明</b>：它里面发 open_file 的是
     * {@code doc_open_file} 与 {@code sheet_create_file}，两个都带 doc_/sheet_ 前缀，
     * 前缀链本来就只放给 LOWA 会话，声明是多余的。
     */
    private static final Map<String, Integer> DOCUMENT_UI_ACTION_CALL_SITES = new TreeMap<>(Map.of(
            "DocumentEditTools.java", 2,      // doc_open_file、sheet_create_file（前缀链已管）
            "LitigationTimelineTools.java", 1, // litigation_timeline_render
            "LitigationVisualTools.java", 1,   // litigation_render
            "PdfTools.java", 4,                // pdf_to_word 三个分支 + finishModification
            "PptxTools.java", 3,               // pptx_open_file / pptx_generate / pptx_apply_format
            "TextFileEditTools.java", 1));     // writeBack（text_write_file 与 text_find_replace 共用）

    /** 只登记、不下发规格的工具（审计 A15）。 */
    private static final Set<String> EXPECTED_NOT_OFFERED = new TreeSet<>(Set.of(
            "delete_file",
            "doc_debug_revisions"));

    private static RecordingToolRegistry registry() {
        RecordingToolRegistry registry =
                new RecordingToolRegistry(RealToolBeans.instantiateAll(), new PluginService());
        registry.init();
        return registry;
    }

    /** 直接从真实工具类的注解里读声明——不经注册表，避免"注册表没推就全绿"的假绿。 */
    private static Set<String> declaredHosts(ToolMeta.Host host) {
        Set<String> names = new TreeSet<>();
        for (AgentToolComponent bean : RealToolBeans.instantiateAll()) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                ToolMeta meta = m.getAnnotation(ToolMeta.class);
                if (m.isAnnotationPresent(Tool.class) && meta != null && meta.requiresHost() == host) {
                    names.add(m.getName());
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("声明了 requiresHost = LOWA 的工具逐名钉住（新增/删除都要来改这份清单）")
    void theLowaOnlyDeclarationSetIsPinned() {
        assertEquals(EXPECTED_LOWA_ONLY, declaredHosts(ToolMeta.Host.LOWA));
        // 今天没有任何工具声明 OFFICE：office_* 由前缀链管，ref_* 由 isToolVisible 里
        // 那一行单独管（dev-board#717），两条都不需要声明。
        assertEquals(Set.of(), declaredHosts(ToolMeta.Host.OFFICE));
    }

    @Test
    @DisplayName("绊线：源码里发文档级 UI 指令的调用点连条数一起钉住——新增一处就会红")
    void everyComponentSendingADocumentUiActionIsAccountedFor() throws IOException {
        Path toolsDir = Path.of("src/main/java/com/checkba/service/ai/tools");
        assertTrue(Files.isDirectory(toolsDir), "找不到工具源码目录：" + toolsDir.toAbsolutePath());

        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.list(toolsDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                int hits = 0;
                for (String action : DOCUMENT_UI_ACTIONS) {
                    // 只数真实调用（含 "("），不数注释里提到的名字——注释里写的是判据说明，
                    // 本文件与 ToolMeta 的 javadoc 里都会反复出现这些名字。
                    int from = 0;
                    String needle = action + "(";
                    while ((from = src.indexOf(needle, from)) >= 0) {
                        if (!isInsideComment(src, from)) {
                            hits++;
                        }
                        from += needle.length();
                    }
                }
                if (hits > 0) {
                    found.put(f.getFileName().toString(), hits);
                }
            }
        }
        assertEquals(DOCUMENT_UI_ACTION_CALL_SITES, found,
                "有组件新增（或删掉）了往桌面前端发文档级 UI 指令的调用点。新增的那个要么给对应的"
                        + " @Tool 方法声明 requiresHost = LOWA，要么说明为什么不用（例如工具名本来就带"
                        + " doc_/sheet_/slide_ 前缀，前缀链已经把它锁在 LOWA 会话里），然后来改这份清单。");
    }

    /** 这一行是不是 {@code //} 注释（本仓里这四个名字在注释里出现得很多）。 */
    private static boolean isInsideComment(String src, int index) {
        int lineStart = src.lastIndexOf('\n', index) + 1;
        String before = src.substring(lineStart, index);
        return before.stripLeading().startsWith("//") || before.stripLeading().startsWith("*");
    }

    @Test
    @DisplayName("声明 LOWA 的工具在 Office / none 会话里既不下发规格、也分发不到")
    void lowaOnlyToolsAreGoneFromNonLowaSessions() {
        RecordingToolRegistry registry = registry();
        ClientCapabilityService caps = registry.capabilities();
        caps.record("conv-lowa", "lowa");
        caps.record("conv-word", "office");
        caps.record("conv-excel", "office", "excel");
        caps.record("conv-ppt", "office", "powerpoint");
        caps.record("conv-none", "none");

        for (String conv : new String[]{"conv-word", "conv-excel", "conv-ppt", "conv-none"}) {
            List<String> names = registry.getAllSpecifications(conv, null).stream()
                    .map(ToolSpecification::name).toList();
            for (String tool : EXPECTED_LOWA_ONLY) {
                assertFalse(names.contains(tool), tool + " 不该下发给 " + conv);
                assertTrue(registry.resolve(tool, conv).isEmpty(),
                        tool + " 在 " + conv + " 里应当分发不到（能力闸两头都管）");
            }
        }

        List<String> lowaNames = registry.getAllSpecifications("conv-lowa", null).stream()
                .map(ToolSpecification::name).toList();
        for (String tool : EXPECTED_LOWA_ONLY) {
            assertTrue(lowaNames.contains(tool), tool + " 必须仍然下发给 LOWA 会话");
        }
    }

    @Test
    @DisplayName("PDF 的读取面不受影响：Office 会话仍看得见 pdf_list_files / pdf_inspect")
    void readOnlyPdfToolsSurviveInOfficeSessions() {
        RecordingToolRegistry registry = registry();
        registry.capabilities().record("conv-word", "office");
        List<String> names = registry.getAllSpecifications("conv-word", null).stream()
                .map(ToolSpecification::name).toList();
        // 收窄的只有"改"，不是"读"——任务窗格里照样能列 PDF、读 PDF 正文。
        assertTrue(names.contains("pdf_list_files"), names.toString());
        assertTrue(names.contains("pdf_inspect"), names.toString());
        assertTrue(names.contains("pptx_inspect_format"), names.toString());
        assertTrue(names.contains("pptx_list_files"), names.toString());
    }

    // ==================== offerToModel（审计 A15） ====================

    @Test
    @DisplayName("offerToModel = false 的工具不进规格清单，但登记还在（只裁 spec、不裁 execute）")
    void permanentlyHiddenToolsAreStillRegistered() {
        RecordingToolRegistry registry = registry();
        List<String> names = registry.getAllSpecifications().stream()
                .map(ToolSpecification::name).toList();

        for (String tool : EXPECTED_NOT_OFFERED) {
            assertFalse(names.contains(tool),
                    tool + " 每轮白付一份 schema，而且用户说「把这个文件删掉」时模型会先调一次"
                            + "再转述拒绝，白烧一个往返：" + names.size() + " specs");
            assertTrue(registry.resolve(tool).isPresent(),
                    tool + " 的登记要保留：XML 兜底路径调到时，工具自己那句可行动的错误"
                            + "远好过一句 'Tool not found'（后者会让模型以为这个能力整个不存在）");
        }
    }

    @Test
    @DisplayName("声明 offerToModel = false 的工具逐名钉住（别顺手再藏别的）")
    void theHiddenSetIsPinned() {
        Set<String> declared = new TreeSet<>();
        for (AgentToolComponent bean : RealToolBeans.instantiateAll()) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                ToolMeta meta = m.getAnnotation(ToolMeta.class);
                if (m.isAnnotationPresent(Tool.class) && meta != null && !meta.offerToModel()) {
                    declared.add(m.getName());
                }
            }
        }
        assertEquals(EXPECTED_NOT_OFFERED, declared);
    }

    @Test
    @DisplayName("其余工具一个都没少：改动前后注册的工具总数不变，只是少下发了两份规格")
    void nothingElseDisappeared() {
        RecordingToolRegistry registry = registry();
        List<String> offered = registry.getAllSpecifications().stream()
                .map(ToolSpecification::name).toList();
        List<String> missing = new ArrayList<>();
        for (String name : new String[]{"write_docx", "create_folder", "move_files_batch",
                "extract_file_text", "read_file", "search_web", "todo_write", "dispatch_subtask",
                "doc_find_replace", "doc_undo", "doc_list_revisions", "sheet_write_cells"}) {
            if (!offered.contains(name)) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "这些工具不该受本次改动影响，却不见了：" + missing);
    }
}
