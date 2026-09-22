// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「同一个意图有几个工具可选」——工具选择面的量化护栏（dev-board#807/#808，审计 A11/A13/A14/B-09/B-10/B-11）。
 *
 * <p>回放评测钉的是「给定模型的选择，编排器做对了什么」；这条钉的是<b>模型面对的选项本身</b>：
 * 一个意图对应几个签名雷同、描述互不引用的工具。这个数字是「工具选择正确率」的上游——
 * 五个同义工具摆在那里，模型挑哪个就是随机变量，而选错的代价（一次公网搜索被当成法源、
 * 一次多花的辅助模型调用、一份被 reload 丢掉的未保存修改）没有任何返回值会戳穿。
 *
 * <p>失败时不要放宽断言：要么是真的多了一个同义工具（那就去声明 offerToModel = false 或合并），
 * 要么是某条描述里的互指被人删了（那就补回去）。
 */
class ToolChoiceSurfaceTest {

    private static RecordingToolRegistry registry() {
        RecordingToolRegistry registry =
                new RecordingToolRegistry(RealToolBeans.instantiateAll(), new PluginService());
        registry.init();
        return registry;
    }

    /** 某个工具的描述（未下发的工具也能取到——它的登记还在）。 */
    private static String descriptionOf(ToolRegistry registry, String name) {
        Optional<ToolRegistry.RegisteredTool> tool = registry.resolve(name);
        assertTrue(tool.isPresent(), name + " 必须仍然登记着（只裁 spec、不裁 execute）");
        String description = tool.get().spec().description();
        return description == null ? "" : description;
    }

    private static List<String> offeredNames(RecordingToolRegistry registry, String conversationId, String docKind) {
        return registry.getAllSpecifications(conversationId, docKind).stream()
                .map(ToolSpecification::name).toList();
    }

    @Test
    @DisplayName("「从项目记忆里找东西」只剩一个工具（此前三个签名雷同、描述不给判据）")
    void projectMemoryRetrievalOffersExactlyOneTool() {
        RecordingToolRegistry registry = registry();
        List<String> offered = offeredNames(registry, "conv", null);

        List<String> candidates = List.of("query_memory", "search_knowledge_base", "deep_search");
        List<String> stillOffered = candidates.stream().filter(offered::contains).toList();
        System.out.printf("[dev-board#807] 项目记忆检索：候选 %d 个，实际下发 %s%n",
                candidates.size(), stillOffered);

        assertEquals(List.of("query_memory"), stillOffered,
                "三个同义的记忆检索工具已合并成 query_memory(depth=quick|hybrid|deep)；"
                        + "再把旧名放回工具面，模型又会在它们之间随机挑，deep 那档还要多花一次辅助模型调用");

        // depth 必须真的是个参数，不然「合并」只是把两个工具藏起来而已
        ToolSpecification spec = registry.getAllSpecifications("conv", null).stream()
                .filter(s -> s.name().equals("query_memory")).findFirst().orElseThrow();
        String properties = String.valueOf(spec.parameters().properties());
        assertTrue(properties.contains("depth"),
                "query_memory 必须有 depth 参数，否则 hybrid / deep 两档就真的没了：" + properties);
        assertTrue(spec.description().contains("quick") && spec.description().contains("hybrid")
                        && spec.description().contains("deep"),
                "描述要给出可操作的档位判据，不能只留一个参数名：" + spec.description());
    }

    @Test
    @DisplayName("PPTX 只剩 slide_* 一套编辑面：0 基的 pptx_* 编辑工具不再与之并存")
    void pptxEditingHasOneAuthoritativeSurface() {
        RecordingToolRegistry registry = registry();

        for (String docKind : new String[]{null, ClientCapabilityService.DOC_KIND_SLIDE}) {
            List<String> offered = offeredNames(registry, "conv", docKind);
            for (String retired : List.of("pptx_apply_format", "pptx_edit_page", "pptx_open_file")) {
                assertFalse(offered.contains(retired),
                        retired + " 与 slide_* 大面积重合且索引基数相反（0 起 vs 1 起），"
                                + "两套同时可见时模型混用必然错页，而错页不报错。docKind=" + docKind);
            }
        }

        // 生成 / 大纲 / 导出 / 列表 / 只读检查这几样不可替代，必须留着
        List<String> slideSession = offeredNames(registry, "conv", ClientCapabilityService.DOC_KIND_SLIDE);
        for (String kept : List.of("pptx_generate", "pptx_generate_outline", "pptx_export_editable",
                "pptx_list_files", "pptx_inspect_format")) {
            assertTrue(slideSession.contains(kept),
                    kept + " 是不可替代的那一批，不该跟着撤下：" + String.join(", ", slideSession));
        }
        assertTrue(slideSession.contains("slide_set_shape_text"), String.join(", ", slideSession));

        assertTrue(descriptionOf(registry, "pptx_generate").contains("slide_"),
                "生成完之后往哪儿走必须写在描述里，否则模型会为了改几个字重新生成一遍整份文件");
    }

    @Test
    @DisplayName("三个「读文件正文」工具互相点名，说清各自的定位方式")
    void theThreeReadersCrossReferenceEachOther() {
        RecordingToolRegistry registry = registry();

        String readDocument = descriptionOf(registry, "read_document");
        assertTrue(readDocument.contains("extract_file_text"),
                "read_document 是 base-tools 的兜底工具，没 skill 时模型大概率挑它——"
                        + "它必须指出「id 可能是文件夹时用 extract_file_text」，"
                        + "否则「把一个卷宗文件夹当材料范围交进来」这条能力永远不会被发现：" + readDocument);
        assertTrue(readDocument.contains("OCR"), "要说清会自动 OCR，模型才不会转头去调 run_python：" + readDocument);
        assertTrue(readDocument.length() > 200,
                "改动前它只有一句 79 字的英文，信息量远低于同功能的 extract_file_text：" + readDocument);

        String readFile = descriptionOf(registry, "read_file");
        assertTrue(readFile.contains("extract_file_text"), "read_file 要说清「有 fileId 时用哪个」：" + readFile);

        String extract = descriptionOf(registry, "extract_file_text");
        assertTrue(extract.contains("read_file"), "extract_file_text 要说清「只有路径时用哪个」：" + extract);
    }

    @Test
    @DisplayName("文件发现：一个清单答完全貌，专用清单自报是它的子集")
    void fileDiscoveryHasOneAuthoritativeInventory() {
        RecordingToolRegistry registry = registry();

        String inventory = descriptionOf(registry, "doc_list_project_files");
        for (String kind : List.of("PDF", "图片", "纯文本")) {
            assertTrue(inventory.contains(kind),
                    "权威清单要自报覆盖到 " + kind + "——改动前 txt / md / 图片没有任何工具能给出它们的 fileId："
                            + inventory);
        }

        for (Map.Entry<String, String> e : Map.of(
                "pdf_list_files", "PDF",
                "pptx_list_files", "PPTX").entrySet()) {
            String d = descriptionOf(registry, e.getKey());
            assertTrue(d.contains("doc_list_project_files"),
                    e.getKey() + " 要自报是权威清单按类型过滤后的子集，否则模型会为了凑齐全貌挨个调一遍：" + d);
            assertFalse(d.contains("唯一来源"),
                    e.getKey() + " 不再是「唯一来源」——那句话现在是错的：" + d);
        }

        String listFiles = descriptionOf(registry, "list_files");
        assertTrue(listFiles.contains("fileId"),
                "list_files 的实现逐条附 (fileId=N)，描述却曾写着 NO database fileId——"
                        + "模型只读描述，于是一个能用的能力被自己的文案藏起来了：" + listFiles);
        assertFalse(listFiles.contains("NO database fileId"), listFiles);
    }

    @Test
    @DisplayName("法规检索族完整，且工具面里没有 search_laws 这个名字")
    void theLawFamilyHasNoDecoy() {
        RecordingToolRegistry registry = registry();
        List<String> offered = offeredNames(registry, "conv", null);

        for (String law : List.of("law_search", "law_search_keyword", "get_law_article", "law_recognition")) {
            assertTrue(offered.contains(law), law + " 必须下发：" + String.join(", ", offered));
        }
        assertFalse(offered.contains("search_laws"), "search_laws 从来不是真工具，别把它注册出来");
        assertTrue(ToolRegistry.TOOL_NAME_ALIASES.isEmpty(),
                "别名 = 静默改道。要容错写错的工具名请改 not-found 的指路文案：" + ToolRegistry.TOOL_NAME_ALIASES);
        assertTrue(ToolRegistry.unknownToolMessage("search_laws").contains("law_search"),
                "模型写错名字时必须收到一句能照着做的指路");
    }

    @Test
    @DisplayName("插入位置类参数带上基准与方向，不再只叫 position")
    void insertionParametersSpellOutTheirBaseAndDirection() {
        RecordingToolRegistry registry = registry();

        // slide_add_page（插在第 N 页「之后」）与 office_ppt_add_slide（插在第 N 页「之前」）
        // 方向相反，而错页不报错、要用户自己翻页才发现。
        ToolSpecification addPage = registry.resolve("slide_add_page").orElseThrow().spec();
        assertTrue(String.valueOf(addPage.parameters().properties()).contains("insertAfterPage"),
                "slide_add_page 要有语义明确的 insertAfterPage：" + addPage.parameters().properties());
        assertTrue(addPage.description().contains("office_ppt_add_slide"),
                "两族方向相反这件事要写在描述里互相点名：" + addPage.description());
        assertTrue(descriptionOf(registry, "office_ppt_add_slide").contains("slide_add_page"),
                "另一头也要点名，只写一边的话从 Office 那侧过来的模型照样差一页");

        // 表格行号：doc_* 1 基、office_* 0 基
        ToolSpecification deleteRow = registry.resolve("doc_table_delete_row").orElseThrow().spec();
        assertTrue(String.valueOf(deleteRow.parameters().properties()).contains("rowNumber1Based"),
                "doc_table_delete_row 要有 rowNumber1Based：" + deleteRow.parameters().properties());
        assertTrue(descriptionOf(registry, "office_table_delete_row").contains("0 开始"),
                "office_ 那一族要明写 0 基——删行不留修订痕迹，删错只能靠撤销");
    }
}
