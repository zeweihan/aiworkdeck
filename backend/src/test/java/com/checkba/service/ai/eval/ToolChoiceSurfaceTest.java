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
    @DisplayName("记忆面一个心智模型：save/query =「记一条 / 找一条」，memory_* =「管理记忆文件本身」，两边互相点名（dev-board#1065 T-12/T-13）")
    void memoryToolsShareOneMentalModel() {
        RecordingToolRegistry registry = registry();
        List<String> offered = offeredNames(registry, "conv", null);

        String query = descriptionOf(registry, "query_memory");
        assertFalse(query.contains("唯一"),
                "memory_search 同时下发、检索的是同一份记忆，「唯一」这句话是错的：" + query);
        assertTrue(query.contains("save_memory") && query.contains("memory_"),
                "query_memory 要点名配对的 save_memory 与管理文件用的 memory_*：" + query);
        assertTrue(descriptionOf(registry, "save_memory").contains("memory_"),
                "save_memory 要说清什么时候改用 memory_* 写文件");
        for (String name : List.of("memory_list", "memory_read", "memory_search",
                "memory_write", "memory_edit", "memory_delete")) {
            String d = descriptionOf(registry, name);
            assertTrue(d.contains("save_memory") || d.contains("query_memory"),
                    name + " 要说清只想记一条 / 找一条时该用哪个，否则模型在两套写入、两套检索之间随机挑：" + d);
        }

        // 这三个取的东西每轮都已注入系统提示（ContextAssemblerService），下发只是白花一次往返
        for (String duplicate : List.of("get_user_profile", "get_project_context", "get_conversation_summary")) {
            assertFalse(offered.contains(duplicate), duplicate + " 与每轮注入的上下文重复，不该下发");
            descriptionOf(registry, duplicate); // 登记仍在：老会话回放与 XML 兜底照常执行
        }
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
        // pptx_list_files 不在这份名单里了：它是 doc_list_project_files 按 .pptx 过滤的子集，
        // dev-board#1065（T-27）起只登记不下发——文件 ID 统一从全类型清单拿
        for (String kept : List.of("pptx_generate", "pptx_generate_outline", "pptx_export_editable",
                "doc_list_project_files", "pptx_inspect_format")) {
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

        // 同一个病（dev-board#1065 T-02）：search_project_files 的实现同样逐条附 (fileId=N)，
        // 描述却写着「Returns paths only, NO database fileId」，而且它在核心集里
        String searchFiles = descriptionOf(registry, "search_project_files");
        assertTrue(searchFiles.contains("fileId"),
                "search_project_files 的结果带 (fileId=N)，描述必须说出来：" + searchFiles);
        assertFalse(searchFiles.contains("NO database fileId"), searchFiles);
        assertFalse(searchFiles.contains("Controller.java"),
                "示例参数要是律师会搜的文件名，不是开发者口吻：" + searchFiles);

        // 按类型的专用清单只登记不下发（T-27）：权威清单在每一类会话里都可见之后，它们只剩重复
        List<String> offered = offeredNames(registry, "conv", null);
        for (String subset : List.of("pdf_list_files", "pptx_list_files", "pptx_search_files")) {
            assertFalse(offered.contains(subset), subset + " 不该再下发：" + String.join(", ", offered));
        }
    }

    @Test
    @DisplayName("权威清单在三类会话里都可见：任务窗格与纯对话会话不再被指向一个拿不到的工具（dev-board#1065 T-01）")
    void theInventoryIsOfferedInEverySession() {
        RecordingToolRegistry registry = registry();
        registry.capabilities().record("conv-word", "office");
        registry.capabilities().record("conv-excel", "office", "excel");
        registry.capabilities().record("conv-none", "none");
        for (String conv : List.of("conv", "conv-word", "conv-excel", "conv-none")) {
            List<String> offered = offeredNames(registry, conv, null);
            assertTrue(offered.contains("doc_list_project_files"),
                    conv + " 里看不见权威清单——而十来个可见工具的描述都说 fileId 从它拿：" + offered);
            assertEquals("conv".equals(conv), offered.contains("doc_open_file"),
                    "例外只开给纯后端的清单，doc_open_file 仍然只属于 LOWA 会话：" + conv);
        }
    }

    @Test
    @DisplayName("按正文找文件有了一个真工具，三个「找」的分工写在描述里（dev-board#1065 T-03）")
    void contentSearchHasOneRealToolAndTheFindersPointAtEachOther() {
        RecordingToolRegistry registry = registry();
        List<String> offered = offeredNames(registry, "conv", ClientCapabilityService.DOC_KIND_WRITER);
        assertTrue(offered.contains("search_project_content"), String.join(", ", offered));
        assertFalse(offered.contains("doc_search_related_docs"),
                "doc_search_related_docs 自称搜内容、实际只比文件名，还拿「前 10 个文档」冒充结果——不许再下发");

        String content = descriptionOf(registry, "search_project_content");
        assertTrue(content.contains("search_project_files"), "要说清按文件名找用哪个：" + content);
        assertTrue(content.contains("doc_find_text"), "要说清在打开的文档里找用哪个：" + content);
        assertTrue(descriptionOf(registry, "search_project_files").contains("search_project_content"),
                "另一头也要点名，否则模型拿文件名搜索去找正文");
    }

    @Test
    @DisplayName("读、移、写三组同义入口各只剩一个下发（dev-board#1065 T-05/T-06/T-07）")
    void synonymEntryPointsAreCollapsed() {
        RecordingToolRegistry registry = registry();
        List<String> offered = offeredNames(registry, "conv", null);
        assertTrue(offered.contains("extract_file_text"));
        assertFalse(offered.contains("read_document"), "与 extract_file_text 同一个抽取器，只留一个入口");
        assertTrue(offered.contains("move_files_batch"));
        assertFalse(offered.contains("move_file"), "单条移动也走 move_files_batch");
        assertFalse(descriptionOf(registry, "move_files_batch").contains("keep using move_file"),
                "move_files_batch 不许再把单条移动指回一个已不下发的工具");
        assertTrue(offered.contains("write_file"));
        assertFalse(offered.contains("scan_files"), "write_file 有了 parentFolderId，scan_files 只剩维护用途");
        assertTrue(String.valueOf(registry.getAllSpecifications("conv", null).stream()
                        .filter(s -> s.name().equals("write_file")).findFirst().orElseThrow()
                        .parameters().properties()).contains("parentFolderId"),
                "write_file 必须能直接写进子文件夹");
        assertTrue(descriptionOf(registry, "write_docx").contains("doc_start_stream")
                        && descriptionOf(registry, "doc_start_stream").contains("write_docx"),
                "两个都能新建 docx，判据要写在描述里互相点名");
        assertTrue(offered.contains("copy_files"), "复制文件（T-25）要下发");
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
    @DisplayName("下发工具的描述与参数说明不得点名 offerToModel=false 的工具（dev-board#1065 T-09）")
    void offeredDescriptionsNeverPointAtRetiredTools() {
        RecordingToolRegistry registry = registry();

        // 判据只认 @ToolMeta(offerToModel = false)：isAvailable() 那类进程级闸（本机有没有 Docker）
        // 随机器而变，把它算进来会让同一条断言在不同机器上一红一绿。
        List<String> retired = registry.toolNamesLongestFirst().stream()
                .filter(name -> registry.resolve(name)
                        .map(t -> t.meta() != null && !t.meta().offerToModel())
                        .orElse(false))
                .sorted()
                .toList();
        assertTrue(retired.contains("pptx_apply_format") && retired.contains("delete_file"),
                "名单是从注解里现取的，取空了这条断言就成了空断言：" + retired);

        List<String> offenders = new java.util.ArrayList<>();
        for (ToolSpecification spec : registry.getAllSpecifications()) {
            String text = (spec.description() == null ? "" : spec.description())
                    + "\n" + (spec.parameters() == null ? "" : String.valueOf(spec.parameters().properties()));
            for (String name : retired) {
                if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(name)
                        + "(?![A-Za-z0-9_])").matcher(text).find()) {
                    offenders.add(spec.name() + " → " + name);
                }
            }
        }
        System.out.printf("[dev-board#1065] 只登记不下发的工具 %d 个：%s%n", retired.size(), retired);
        assertEquals(List.of(), offenders,
                "模型只读描述：描述里点名一个不下发的工具，它就会经 XML 兜底路径把那个工具调出来，"
                        + "恰好撞上当初下线它要防的那个坑（pptx_apply_format 的 reload 丢未保存修改、0 基错页）。"
                        + "改描述指向仍下发的替代工具，不要把名字加进例外");
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
