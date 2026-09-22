// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.AiDocxExportService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.PdfEditService;
import com.checkba.service.pack.NativePackService;
import com.checkba.service.pack.OptionalComponents;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PDF 工具集
 *
 * 面向文本型、未加密 PDF：高亮、便签批注、脱敏（真删文字层）、短文本原位替换、转 Word。
 * 定位一律引用原文文本（先 pdf_inspect 核对原文，再执行操作），不暴露坐标。
 *
 * 技术说明：
 * - 实现在 PdfEditService（PDFBox 3.x）；高亮/批注写标准 PDF annotation，
 *   前端预览是 Chromium 原生 PDF 引擎，注释直接可见；
 * - 修改类工具收尾统一三步：更新 wpsFileId（预览据此重新拉取字节）→ 存库 → sendReloadFileAction；
 * - 大范围内容修改不做原位编辑（PDF 无回流），引导 pdf_to_word 转 Word 后用 doc_* 工具编辑。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PdfTools implements AgentToolComponent {

    private final PdfEditService pdfEditService;
    private final ProjectFileService projectFileService;
    private final ProjectFileRepository projectFileRepository;
    private final EditorBridgeService editorBridgeService;
    private final AiDocxExportService aiDocxExportService;
    private final com.checkba.service.ai.PptxServiceClient pptxServiceClient;
    private final com.checkba.storage.ProjectStorageResolver storageResolver;
    // mineru-service / pptx-service 从 0.38.0 起是按需下载的可选组件（设计 §3.2）
    private final NativePackService packService;

    private static final Long AGENT_USER_ID = 10001L;
    private static final int INSPECT_MAX_CHARS_PER_PAGE = 3000;

    // ==================== 读取 ====================

    @ToolMeta(displayName = "列出PDF文件", category = "pdf")
    @Tool("PDF 专用清单：等价于 doc_list_project_files 只保留 .pdf 的那一份结果，返回文件 ID、名称和位置。"
            + "**要看项目里有哪些文件（含 Word / Excel / PPT / 文本 / 图片）请直接用 doc_list_project_files，一次列全**，"
            + "它给出的 fileId 同样可以直接喂给 pdf_* 工具；只有在结果太多、确实只想看 PDF 时才用本工具。")
    public String pdf_list_files(
            @P("项目 ID") Long projectId
    ) {
        log.info("Tool: pdf_list_files called for projectId={}", projectId);
        try {
            List<ProjectFile> allFiles = projectFileRepository.findByProjectIdOrderBySortOrderAsc(projectId);
            List<ProjectFile> pdfs = allFiles.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsFolder()))
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDeleted()))
                    .filter(f -> f.getName() != null && f.getName().toLowerCase().endsWith(".pdf"))
                    .toList();
            if (pdfs.isEmpty()) {
                return "项目中没有 PDF 文件。";
            }
            StringBuilder sb = new StringBuilder("项目中的 PDF 文件 (共 " + pdfs.size() + " 个):\n");
            for (ProjectFile f : pdfs) {
                sb.append(String.format("- ID: %d, 名称: %s\n", f.getId(), f.getName()));
            }
            sb.append("\n后续全部 pdf_* 工具都使用上述 ID：\n")
                    .append("- 读与标注：pdf_inspect（逐页读，支持 offset 续读）/ pdf_highlight / pdf_annotate / pdf_redact / pdf_replace_text\n")
                    .append("- 页级组织（都产出新文件、原件不动）：pdf_merge 合并成册、pdf_split 按范围拆分、")
                    .append("pdf_extract_pages 提取指定页、pdf_delete_pages 删页、pdf_rotate_pages 旋转页、")
                    .append("pdf_add_page_numbers 编页码或贝茨编号\n")
                    .append("- 大范围改内容：pdf_to_word 转成 Word 后用 doc_* 编辑");
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to list PDF files", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "读取PDF内容", category = "pdf")
    @Tool("读取 PDF 文件的逐页文本与基本信息（页数、每页字符数、是否有文本层、当前旋转角度）。" +
          "本工具的 pageIndex 从 0 开始（注意：页操作类工具的 ranges 用的是 1 基页码）。" +
          "对 PDF 做高亮/批注/脱敏/替换前必须先调用本工具核对原文的准确写法——后续操作全部靠引用原文文本定位。" +
          "每页单次最多返回 " + INSPECT_MAX_CHARS_PER_PAGE + " 字符；正文更长时返回 truncated=true 与 next_offset，" +
          "把 next_offset 原样传回本工具的 offset 参数即可接着读同一页的后半段（char_count 始终是整页字数）。" +
          "如果页面 has_text_layer 为 false，说明是扫描件：无法做文本定位类操作（高亮/脱敏/替换），" +
          "但 pdf_to_word 可以直接对它做本地 MinerU OCR 转成可编辑 Word。")
    public String pdf_inspect(
            @P("文件 ID（从 pdf_list_files 获取）") Long fileId,
            @P("页码（从 0 开始，可选）。指定后只返回该页；传 null 返回全部页") Integer pageIndex,
            @P(value = "续读位点（可选）：上一次返回的 next_offset。给了它就必须同时指定 pageIndex",
                    required = false) Integer offset
    ) {
        log.info("Tool: pdf_inspect called, fileId={}, pageIndex={}, offset={}", fileId, pageIndex, offset);
        try {
            Path localPath = resolvePdf(fileId);
            return pdfEditService.inspect(localPath, pageIndex, INSPECT_MAX_CHARS_PER_PAGE,
                    offset == null ? 0 : offset);
        } catch (Exception e) {
            return errorOf("读取 PDF 失败", e);
        }
    }

    // ==================== 高亮 / 批注 ====================

    // 这四个修改类工具的收尾都走 finishModification 里的 sendReloadFileAction（文档级 UI 指令），
    // 任务窗格会话里那一步不会发生，而工具描述还在承诺「预览中直接可见」——审计 A9 的同一族。
    // 注意：它们改的是磁盘上那份 PDF，写入本身是纯服务端的；收窄的代价见 ToolMeta.requiresHost 的
    // 「代价要想清楚再标」一段。只读面（pdf_list_files / pdf_inspect）没有声明，照常可见。
    @ToolMeta(displayName = "高亮PDF文本", category = "pdf", fileEffect = "MODIFIED",
            requiresHost = ToolMeta.Host.LOWA)
    @Tool("在 PDF 中高亮指定文本（所有匹配处）。写入标准 PDF 高亮注释，预览中直接可见。" +
          "text 必须与原文逐字一致（先用 pdf_inspect 核对）；原文跨行也能匹配。" +
          "仅适用于有文本层的页面（非扫描件）、未加密的 PDF。")
    public String pdf_highlight(
            @P("文件 ID") Long fileId,
            @P("要高亮的原文文本（逐字一致）") String text,
            @P("页码（从 0 开始，可选）。指定后只在该页查找；传 null 全文查找") Integer pageIndex,
            @P("高亮颜色，十六进制如 '#FFFF00'（可选，默认黄色）") String color,
            @P("附着在高亮上的说明文字（可选，悬停/点开可见）") String note
    ) {
        log.info("Tool: pdf_highlight called, fileId={}, text={}, pageIndex={}", fileId, brief(text), pageIndex);
        try {
            ProjectFile file = getPdfFile(fileId);
            Path localPath = resolveExisting(file);
            int count = pdfEditService.highlight(localPath, text, pageIndex, color, note);
            finishModification(file, localPath);
            return String.format("已高亮 %d 处『%s』。预览将自动刷新显示高亮。", count, brief(text));
        } catch (Exception e) {
            return errorOf("高亮失败", e);
        }
    }

    @ToolMeta(displayName = "添加PDF批注", category = "pdf", fileEffect = "MODIFIED",
            requiresHost = ToolMeta.Host.LOWA)
    @Tool("在 PDF 的指定文本旁添加便签批注（锚定第一处匹配）。批注以标准 PDF 注释写入，" +
          "预览中显示为可点开的便签图标，署名 AI WorkDeck。anchorText 必须与原文逐字一致。")
    public String pdf_annotate(
            @P("文件 ID") Long fileId,
            @P("锚点原文文本（批注加在它旁边，需与原文逐字一致）") String anchorText,
            @P("批注内容") String comment,
            @P("页码（从 0 开始，可选）；传 null 全文查找第一处") Integer pageIndex
    ) {
        log.info("Tool: pdf_annotate called, fileId={}, anchor={}, pageIndex={}", fileId, brief(anchorText), pageIndex);
        try {
            if (!StringUtils.hasText(comment)) {
                return "Error: 批注内容不能为空";
            }
            ProjectFile file = getPdfFile(fileId);
            Path localPath = resolveExisting(file);
            int page = pdfEditService.addNote(localPath, anchorText, comment, pageIndex);
            finishModification(file, localPath);
            return String.format("已在第 %d 页『%s』旁添加批注。预览将自动刷新。", page, brief(anchorText));
        } catch (Exception e) {
            return errorOf("添加批注失败", e);
        }
    }

    // ==================== 脱敏 ====================

    @ToolMeta(displayName = "PDF脱敏", category = "pdf", fileEffect = "MODIFIED",
            requiresHost = ToolMeta.Host.LOWA)
    @Tool("对 PDF 做真脱敏：黑框覆盖指定文本，并把涉及的页面转为图片页、彻底移除该页文字层" +
          "（黑框下的内容无法再复制或提取——单纯画黑框是伪脱敏）。代价是被处理的页面文字不可再选中，" +
          "其余页面保持原样。此操作不可逆，务必确认目标文本无误后执行。" +
          "textsJson 为 JSON 字符串数组，可一次脱敏多个目标（如姓名、身份证号、金额）。")
    public String pdf_redact(
            @P("文件 ID") Long fileId,
            @P("要脱敏的文本列表，JSON 字符串数组，如 [\"张三\",\"110101199001011234\"]，每项需与原文逐字一致") String textsJson,
            @P("页码（从 0 开始，可选）；传 null 全文查找") Integer pageIndex
    ) {
        log.info("Tool: pdf_redact called, fileId={}, pageIndex={}", fileId, pageIndex);
        try {
            List<String> texts;
            try {
                texts = cn.hutool.json.JSONUtil.parseArray(textsJson).toList(String.class);
            } catch (Exception e) {
                return "Error: textsJson 不是合法的 JSON 字符串数组: " + e.getMessage();
            }
            if (texts == null || texts.isEmpty()) {
                return "Error: textsJson 不能为空数组";
            }
            ProjectFile file = getPdfFile(fileId);
            Path localPath = resolveExisting(file);
            PdfEditService.RedactResult result = pdfEditService.redact(localPath, texts, pageIndex);
            finishModification(file, localPath);
            // 部分命中不算失败：已经真实生效的脱敏必须走 finishModification（否则磁盘已改、
            // DB 与预览还停在旧版本），缺失目标如实报告，别把已经生效的操作说成失败。
            String missingNote = result.missing.isEmpty() ? ""
                    : String.format("；以下文本未找到，未被脱敏（请核对原文是否逐字一致）: %s", result.missing);
            return String.format("脱敏完成：共 %d 处，涉及页面 %s（这些页已转为图片页，文字层已彻底移除，其余页不受影响）。预览将自动刷新。%s",
                    result.matchCount, result.rasterizedPages, missingNote);
        } catch (Exception e) {
            return errorOf("脱敏失败", e);
        }
    }

    // ==================== 短文本替换 ====================

    @ToolMeta(displayName = "替换PDF文本", category = "pdf", fileEffect = "MODIFIED",
            requiresHost = ToolMeta.Host.LOWA)
    @Tool("PDF 短文本原位替换（白底覆盖+按原字号覆写）。适合改日期、金额、人名等不跨行的短文本。" +
          "限制必须如实告知用户：1) PDF 没有排版回流，新文本过长会超出原区域；2) 匹配文本不能跨行；" +
          "3) 只覆盖显示层，底层旧文字仍可被提取，需要彻底清除请用 pdf_redact。" +
          "大范围内容修改不要用本工具，应改用 pdf_to_word 转成 Word 后编辑。")
    public String pdf_replace_text(
            @P("文件 ID") Long fileId,
            @P("要替换的原文文本（逐字一致、不跨行）") String find,
            @P("新文本") String replace,
            @P("页码（从 0 开始，可选）；传 null 全文替换所有匹配") Integer pageIndex
    ) {
        log.info("Tool: pdf_replace_text called, fileId={}, find={}, pageIndex={}", fileId, brief(find), pageIndex);
        try {
            if (replace == null) {
                return "Error: 新文本不能为 null";
            }
            ProjectFile file = getPdfFile(fileId);
            Path localPath = resolveExisting(file);
            int count = pdfEditService.replaceText(localPath, find, replace, pageIndex);
            finishModification(file, localPath);
            return String.format("已替换 %d 处『%s』→『%s』。预览将自动刷新。" +
                    "提醒：替换只覆盖显示层，底层旧文字仍可提取；若替换涉及敏感信息请追加 pdf_redact。",
                    count, brief(find), brief(replace));
        } catch (Exception e) {
            return errorOf("替换失败", e);
        }
    }

    // ==================== 页级组织：合并 / 拆分 / 提页 / 删页 / 旋转 / 编页码 ====================

    /*
     * dev-board#805（审计 A17 / B-12）。诉讼业务里 PDF 是证据的主要载体，而这六个动作
     * （合卷、拆分、提页、删页、转正、编页码/贝茨号）此前一个都做不到，模型只能建议手工处理。
     *
     * 六个工具共用一条契约，与上面的标注类工具正好相反：
     *   **产出项目内的新 PDF，原件一个字节都不动。**
     * 理由是 PDF 没有修订痕迹、项目里也没有针对 PDF 的检查点——页级操作一旦做成原位修改，
     * 用户丢的是证据原件，没有任何东西能把它捞回来。
     *
     * 这不与「PDF 大范围修改不做原位编辑、统一引导 pdf_to_word」那条刻意设计冲突：
     * 那条说的是改<b>内容</b>，这里做的是组织<b>页面</b>。
     *
     * requiresHost 刻意留 NONE：收尾只是建文件 + 刷文件树（refreshFiles=true），
     * 不发 open_file / reload_file 这类文档级 UI 指令，纯服务端就能把事情做完（K19 判据）。
     * 返回文案因此也不许承诺「预览会自动刷新」「已在编辑器中打开」。
     */

    /** 页操作产出物的临时文件前缀，与 pdf_to_word 的 .pdf2docx- 同一路数。 */
    private static final String TEMP_PREFIX = ".pdfpageop-";

    /** 默认页码模板：含中文，需要本机有可嵌入的 CJK 字体，否则按提示改用纯英数模板。 */
    private static final String DEFAULT_PAGE_NUMBER_FORMAT = "第 {n} 页 / 共 {total} 页";

    private static final String RANGES_DOC =
            "ranges 语法（1 基页码，与你在阅读器里看到的页码一致，注意 pdf_inspect 的 pageIndex 是 0 基）："
            + "\"1-3,5,8-\" 表示第 1 到 3 页、第 5 页、第 8 页到最后一页；逗号分隔，N- 表示到末页。"
            + "越界、倒序（如 5-3）、0 页一律报错，不会就近取整。";

    @ToolMeta(displayName = "合并PDF", category = "pdf", fileEffect = "ADDED", refreshFiles = true)
    @Tool("把多份 PDF 按给定顺序合并成一册（证据合卷）。" +
          "fileIdsJson 是文件 ID 的 JSON 数字数组，如 [12,15,13]——合并顺序就是数组顺序，" +
          "所以想按证据序号排就按那个序号排好再传。至少 2 份，最多 50 份。" +
          "产出项目内的新 PDF，参与合并的原件全部保持不变。" +
          "输出文件落在第一份原件所在的文件夹；不传 outputName 时命名为『合并.pdf』，同名自动加 (n)。" +
          "加密的 PDF 会被拒绝。合并后如需连续页码，接着调 pdf_add_page_numbers。")
    public String pdf_merge(
            @P("要合并的 PDF 文件 ID，JSON 数字数组，如 [12,15,13]；顺序即合并顺序") String fileIdsJson,
            @P(value = "输出文件名（可选，默认『合并.pdf』）", required = false) String outputName
    ) {
        log.info("Tool: pdf_merge called, fileIdsJson={}", brief(fileIdsJson));
        try {
            List<Long> ids;
            try {
                ids = cn.hutool.json.JSONUtil.parseArray(fileIdsJson).toList(Long.class);
            } catch (Exception e) {
                return "Error: fileIdsJson 不是合法的 JSON 数字数组（如 [12,15,13]）: " + e.getMessage();
            }
            if (ids == null || ids.size() < 2) {
                return "Error: 合并至少需要 2 份 PDF，fileIdsJson 里只有 "
                        + (ids == null ? 0 : ids.size()) + " 个 ID。";
            }
            List<ProjectFile> files = new java.util.ArrayList<>();
            List<Path> paths = new java.util.ArrayList<>();
            for (Long id : ids) {
                ProjectFile f = getPdfFile(id);
                files.add(f);
                paths.add(resolveExisting(f));
            }
            ProjectFile first = files.get(0);
            return produce(first, defaultName(outputName, "合并"), target -> {
                int pages = pdfEditService.merge(paths, target);
                return String.format("已把 %d 份 PDF 合并为一册，共 %d 页。", files.size(), pages);
            });
        } catch (Exception e) {
            return errorOf("合并 PDF 失败", e);
        }
    }

    @ToolMeta(displayName = "拆分PDF", category = "pdf", fileEffect = "ADDED", refreshFiles = true)
    @Tool("按页码范围把一份 PDF 拆成多份：每个逗号分段产出一份新文件。" +
          "例如 ranges=\"1-3,5,8-\" 会产出三份（第 1-3 页 / 第 5 页 / 第 8 页到末页）。" + RANGES_DOC +
          "原件保持不变；产出文件与原件同目录，命名为『<原名>_第N-M页.pdf』，同名自动加 (n)。" +
          "只要某几页的话用 pdf_extract_pages（只产出一份）。")
    public String pdf_split(
            @P("文件 ID") Long fileId,
            @P("页码范围，如 \"1-3,5,8-\"（1 基页码）。每个逗号分段产出一份文件") String ranges
    ) {
        log.info("Tool: pdf_split called, fileId={}, ranges={}", fileId, ranges);
        try {
            ProjectFile file = getPdfFile(fileId);
            Path localPath = resolveExisting(file);
            int total = pdfEditService.pageCount(localPath);
            List<PdfEditService.PageRange> segments =
                    PdfEditService.parsePageRangeSegments(ranges, total);
            if (segments.size() > MAX_SPLIT_PARTS) {
                return "Error: 一次最多拆成 " + MAX_SPLIT_PARTS + " 份，本次给了 " + segments.size() + " 段。";
            }

            String base = baseName(file.getName());
            StringBuilder report = new StringBuilder();
            int done = 0;
            // 逐段分别登记：某一段失败不回滚已经产出的那几份（文件已经在文件树里了，
            // 说成失败会让用户去找一个其实存在的文件），如实逐条报告成功与失败。
            for (PdfEditService.PageRange seg : segments) {
                String name = base + "_" + pageLabel(seg.pages()) + ".pdf";
                String line = produce(file, name, target -> {
                    int pages = pdfEditService.extractPages(localPath, target, seg.pages());
                    return String.format("%s（%d 页）", pageLabel(seg.pages()), pages);
                });
                if (!line.startsWith("Error:")) done++;
                report.append(line).append('\n');
            }
            String failures = done == segments.size() ? ""
                    : String.format("（%d 段失败，只重试失败的那几段，不要整份重拆）", segments.size() - done);
            return String.format("已把『%s』（共 %d 页）拆分为 %d / %d 份%s，原件保持不变：\n%s",
                    file.getName(), total, done, segments.size(), failures, report.toString().strip());
        } catch (Exception e) {
            return errorOf("拆分 PDF 失败", e);
        }
    }

    @ToolMeta(displayName = "提取PDF页", category = "pdf", fileEffect = "ADDED", refreshFiles = true)
    @Tool("从一份 PDF 里提取指定页，产出一份新 PDF（从大卷宗里只要第 10-20 页时用它）。" +
          RANGES_DOC +
          "产物保持原文档页序（提页不改变页序，重复页只取一次）。原件保持不变；" +
          "产出文件与原件同目录，不传 outputName 时命名为『<原名>_摘取.pdf』，同名自动加 (n)。" +
          "要一次拆成多份用 pdf_split。")
    public String pdf_extract_pages(
            @P("文件 ID") Long fileId,
            @P("要保留的页码范围，如 \"10-20\" 或 \"1,5,9-12\"（1 基页码）") String ranges,
            @P(value = "输出文件名（可选，默认『<原名>_摘取.pdf』）", required = false) String outputName
    ) {
        log.info("Tool: pdf_extract_pages called, fileId={}, ranges={}", fileId, ranges);
        try {
            ProjectFile file = getPdfFile(fileId);
            Path localPath = resolveExisting(file);
            List<Integer> pages = PdfEditService.parsePageRanges(
                    ranges, pdfEditService.pageCount(localPath));
            String name = outputName != null && !outputName.isBlank()
                    ? outputName : baseName(file.getName()) + "_摘取.pdf";
            return produce(file, name, target -> {
                int kept = pdfEditService.extractPages(localPath, target, pages);
                return String.format("已从『%s』提取 %s，共 %d 页。原件保持不变。",
                        file.getName(), pageLabel(pages), kept);
            });
        } catch (Exception e) {
            return errorOf("提取 PDF 页失败", e);
        }
    }

    @ToolMeta(displayName = "删除PDF页", category = "pdf", fileEffect = "ADDED", refreshFiles = true)
    @Tool("删掉一份 PDF 里的指定页，产出一份新 PDF（去掉多余的封面/空白页/重复件）。" +
          RANGES_DOC +
          "不能删光全部页。原件保持不变；产出文件与原件同目录，" +
          "不传 outputName 时命名为『<原名>_删页.pdf』，同名自动加 (n)。")
    public String pdf_delete_pages(
            @P("文件 ID") Long fileId,
            @P("要删除的页码范围，如 \"2\" 或 \"1,7-9\"（1 基页码）") String ranges,
            @P(value = "输出文件名（可选，默认『<原名>_删页.pdf』）", required = false) String outputName
    ) {
        log.info("Tool: pdf_delete_pages called, fileId={}, ranges={}", fileId, ranges);
        try {
            ProjectFile file = getPdfFile(fileId);
            Path localPath = resolveExisting(file);
            List<Integer> pages = PdfEditService.parsePageRanges(
                    ranges, pdfEditService.pageCount(localPath));
            String name = outputName != null && !outputName.isBlank()
                    ? outputName : baseName(file.getName()) + "_删页.pdf";
            return produce(file, name, target -> {
                int left = pdfEditService.deletePages(localPath, target, pages);
                return String.format("已从『%s』删除 %s（共 %d 页），产物剩 %d 页。原件保持不变。",
                        file.getName(), pageLabel(pages), pages.size(), left);
            });
        } catch (Exception e) {
            return errorOf("删除 PDF 页失败", e);
        }
    }

    @ToolMeta(displayName = "旋转PDF页", category = "pdf", fileEffect = "ADDED", refreshFiles = true)
    @Tool("把 PDF 的指定页旋转（扫描歪了的证据页转正），产出一份新 PDF。" +
          RANGES_DOC +
          "degrees 只接受 90 / 180 / 270（顺时针），并且是在该页**当前角度基础上叠加**——" +
          "各页当前角度可先用 pdf_inspect 读 rotation 字段。" +
          "原件保持不变；产出文件与原件同目录，命名为『<原名>_旋转.pdf』，同名自动加 (n)。")
    public String pdf_rotate_pages(
            @P("文件 ID") Long fileId,
            @P("要旋转的页码范围，如 \"3\" 或 \"1-5\"（1 基页码）") String ranges,
            @P("顺时针旋转角度，只能是 90、180 或 270；在该页当前角度上叠加") Integer degrees
    ) {
        log.info("Tool: pdf_rotate_pages called, fileId={}, ranges={}, degrees={}", fileId, ranges, degrees);
        try {
            if (degrees == null) {
                return "Error: degrees 不能为空，只支持 90 / 180 / 270（顺时针）。";
            }
            ProjectFile file = getPdfFile(fileId);
            Path localPath = resolveExisting(file);
            List<Integer> pages = PdfEditService.parsePageRanges(
                    ranges, pdfEditService.pageCount(localPath));
            String name = baseName(file.getName()) + "_旋转.pdf";
            return produce(file, name, target -> {
                int rotated = pdfEditService.rotatePages(localPath, target, pages, degrees);
                return String.format("已把『%s』的 %s（共 %d 页）顺时针旋转 %d 度。原件保持不变。",
                        file.getName(), pageLabel(pages), rotated, degrees);
            });
        } catch (Exception e) {
            return errorOf("旋转 PDF 页失败", e);
        }
    }

    @ToolMeta(displayName = "PDF编页码", category = "pdf", fileEffect = "ADDED", refreshFiles = true)
    @Tool("给整册 PDF 逐页写入页码或贝茨编号（Bates number），产出一份新 PDF。" +
          "format 是模板：{n} 是本页号、{total} 是总页数、{n:6} 是零填充到 6 位。" +
          "例如 \"第 {n} 页 / 共 {total} 页\"；贝茨编号写成前缀加零填充号，如 \"AWD{n:6}\" 得到 AWD000001。" +
          "模板里必须有 {n}，否则整册会印上同一个数。" +
          "position 只支持 bottom-center（默认）与 bottom-right。startAt 是第一页印的号（默认 1），" +
          "多册连号时把上一册的末号加一传进来。" +
          "页码跟着页面的显示方向走，所以可以先 pdf_rotate_pages 转正再编号。" +
          "中文模板需要本机有可嵌入的中文字体，没有时会明确报错并建议改用纯英数模板。" +
          "原件保持不变；产出文件与原件同目录，命名为『<原名>_页码.pdf』，同名自动加 (n)。")
    public String pdf_add_page_numbers(
            @P("文件 ID") Long fileId,
            @P(value = "位置：bottom-center（默认）或 bottom-right", required = false) String position,
            @P(value = "第一页印的号（可选，默认 1）", required = false) Integer startAt,
            @P(value = "页码模板（可选，默认『第 {n} 页 / 共 {total} 页』）；{n} 本页号、{total} 总页数、{n:6} 零填充 6 位",
                    required = false) String format
    ) {
        log.info("Tool: pdf_add_page_numbers called, fileId={}, position={}, startAt={}", fileId, position, startAt);
        try {
            ProjectFile file = getPdfFile(fileId);
            Path localPath = resolveExisting(file);
            String template = format != null && !format.isBlank() ? format : DEFAULT_PAGE_NUMBER_FORMAT;
            int from = startAt == null ? 1 : startAt;
            String pos = position != null && !position.isBlank() ? position : "bottom-center";
            String name = baseName(file.getName()) + "_页码.pdf";
            return produce(file, name, target -> {
                int stamped = pdfEditService.addPageNumbers(localPath, target, pos, from, template);
                return String.format("已给『%s』的 %d 页写入页码（模板『%s』，起始号 %d，位置 %s）。原件保持不变。",
                        file.getName(), stamped, template, from, pos);
            });
        } catch (Exception e) {
            return errorOf("写入 PDF 页码失败", e);
        }
    }

    // ==================== 转 Word ====================

    // 三条分支的收尾都是 sendOpenFileAction，返回文案三处都写着「已在编辑器中打开」，
    // 还接着让模型「用 doc_* 编辑该 docx」——而 doc_* 在任务窗格会话里恰恰是隐藏的。
    @ToolMeta(displayName = "PDF转Word", category = "pdf", fileEffect = "ADDED", refreshFiles = true,
            requiresHost = ToolMeta.Host.LOWA)
    @Tool("把 PDF 转换为可编辑的 Word 文档，自动选择最佳路径：" +
          "1) 文本型 PDF 优先版式级转换（pdf2docx：段落/表格/图片/分栏尽量保留原排版），转换服务不可用时" +
          "自动回退为结构级转换（保留文字与段落，不保版式）；" +
          "2) 扫描件（无文本层）自动走本地 MinerU OCR 识别出内容再转（内容级，文档不出本机）。" +
          "这是对 PDF 做大范围修改的正确路径：转出 docx 后用 doc_* 工具编辑（带修订痕迹）。" +
          "转换是机械流程，不消耗模型步数重新生成内容；完成后新 docx 自动在编辑器中打开，" +
          "返回信息会注明实际使用的转换路径，向用户如实转述。")
    public String pdf_to_word(
            @P("PDF 文件 ID") Long fileId,
            @P("目标文件夹 ID（可选，传 null 放项目根目录）") Long parentId
    ) {
        log.info("Tool: pdf_to_word called, fileId={}, parentId={}", fileId, parentId);
        try {
            ProjectFile file = getPdfFile(fileId);
            Path localPath = resolveExisting(file);
            String docxName = file.getName().replaceAll("(?i)\\.pdf$", "") + ".docx";

            PdfEditService.ExtractedText extracted = pdfEditService.extractMarkdown(localPath);
            String markdown = extracted.markdown();

            if (extracted.looksScanned()) {
                // 扫描件：本地 MinerU OCR（pptx-service 路由本地优先/云端兜底）
                String ocrMarkdown;
                try {
                    ocrMarkdown = pptxServiceClient.ocrPdfToMarkdown(localPath.toString());
                } catch (Exception e) {
                    log.warn("MinerU OCR failed for scanned PDF", e);
                    if (markdown != null && !markdown.isBlank()) {
                        // 手里还有已经提取到的文本层（页眉页码那种稀薄内容，也可能是一份
                        // 本来就很短的文本件）。OCR 失败就回退用它，别把一份能转的文档
                        // 变成一句「请去装 MinerU」——判据偏向 OCR 的前提就是有这条兜底。
                        log.warn("OCR 不可用，回退到已提取的稀薄文本层: {}", file.getName());
                    } else {
                        OptionalComponents.Entry me = OptionalComponents.byService("mineru-service");
                        if (!packService.isReady(me.packId())) {
                            long sizeMb = packService.knownSizes(me.packId()).downloadBytes() / (1024 * 1024);
                            editorBridgeService.sendComponentRequiredAction(
                                    me.packId(), me.service(), me.modelId(), sizeMb, me.featureKeys(), "pdf_to_word");
                            // 同 PptxTools：文案里不出现「稍后重试」，模型会原样转述
                            return "该 PDF 是扫描件（无文本层），本机的「扫描件 OCR 引擎」组件还没安装，"
                                    + "已请用户确认下载（界面上已经弹出提示，含 3GB 模型）。"
                                    + "用户确认后会自动装好并重新执行这一步，不要让用户等一会儿再试一次。";
                        }
                        return "Error: 该 PDF 是扫描件（无文本层），已尝试本地 MinerU OCR 但失败：" + e.getMessage() +
                                "\n请确认桌面端 MinerU 组件已下载并启动（设置-组件管理），或稍后重试。";
                    }
                    ocrMarkdown = null;
                }
                if (ocrMarkdown == null) {
                    // 走下面的文本型分支，用已提取的文本层继续
                } else {
                ProjectFile docx = aiDocxExportService.exportMarkdownToDocx(
                        file.getProjectId(), parentId, AGENT_USER_ID, docxName, ocrMarkdown);
                editorBridgeService.sendRefreshFilesAction();
                editorBridgeService.sendOpenFileAction(docx);
                return String.format("已将扫描件『%s』经本地 MinerU OCR 识别并转换为 Word 文档『%s』（文件 ID: %d），已在编辑器中打开。\n" +
                        "说明：这是 OCR 内容级转换（识别文字并保留段落结构，不保留原版式；识别结果建议人工核对）。\n" +
                        "接下来可用 doc_* 编辑工具修改该 docx（修改带修订痕迹）。",
                        file.getName(), docx.getName(), docx.getId());
                }
            }

            // 文本型：版式级优先（pdf2docx），失败回退结构级
            try {
                Path projectRoot = storageResolver.projectRoot(file.getProjectId());
                Files.createDirectories(projectRoot);
                Path tempOut = projectRoot.resolve(".pdf2docx-" + System.currentTimeMillis() + ".docx");
                try {
                    pptxServiceClient.convertPdfToDocx(localPath.toString(), tempOut.toString());

                    ProjectFile docx = projectFileService.createFile(
                            file.getProjectId(), parentId, docxName, "docx",
                            Files.size(tempOut), null,
                            "project_" + file.getProjectId() + "_ai_" + System.currentTimeMillis(),
                            AGENT_USER_ID, ProjectFileService.ConflictPolicy.RENAME);
                    Path target = storageResolver.resolve(docx.getFilePath());
                    Files.createDirectories(target.getParent());
                    Files.move(tempOut, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    docx.setFileSize(Files.size(target));
                    projectFileRepository.save(docx);

                    editorBridgeService.sendRefreshFilesAction();
                    editorBridgeService.sendOpenFileAction(docx);
                    return String.format("已将『%s』版式级转换为 Word 文档『%s』（文件 ID: %d），已在编辑器中打开。\n" +
                            "说明：版式级转换（pdf2docx），段落/表格/图片/分栏尽量保留原排版。\n" +
                            "接下来可用 doc_* 编辑工具修改该 docx（修改带修订痕迹，用户可逐条接受/拒绝）。",
                            file.getName(), docx.getName(), docx.getId());
                } finally {
                    Files.deleteIfExists(tempOut);
                }
            } catch (Exception e) {
                log.warn("Layout-level pdf2docx conversion failed, falling back to structural extraction", e);
                // 组件没装才提示下载；提示只是提示——下面的结构级降级照常做完，
                // 不能因为发提示把一份本来能转出来的 docx 弄丢（设计 §4.2）。
                promptPptxComponentIfMissing("pdf_to_word");
            }

            ProjectFile docx = aiDocxExportService.exportMarkdownToDocx(
                    file.getProjectId(), parentId, AGENT_USER_ID, docxName, markdown);
            editorBridgeService.sendRefreshFilesAction();
            editorBridgeService.sendOpenFileAction(docx);
            return String.format("已将『%s』转换为 Word 文档『%s』（文件 ID: %d），已在编辑器中打开。\n" +
                    "说明：版式级转换服务当前不可用，本次为结构级转换（保留文字与段落、不保留原版式）。\n" +
                    "接下来可用 doc_* 编辑工具修改该 docx（修改带修订痕迹）。",
                    file.getName(), docx.getName(), docx.getId());
        } catch (Exception e) {
            return errorOf("PDF 转 Word 失败", e);
        }
    }

    // ==================== 辅助 ====================

    /**
     * 版式级转换（pdf2docx，跑在 pptx-service 里）打不通且 pptx-runtime 没装时，
     * 发一次 component_required 引导下载。只发提示、不改变返回值：
     * 结构级降级转换仍然照常完成。
     */
    private void promptPptxComponentIfMissing(String trigger) {
        try {
            OptionalComponents.Entry pe = OptionalComponents.byService("pptx-service");
            if (packService.isReady(pe.packId())) return;
            long sizeMb = packService.knownSizes(pe.packId()).downloadBytes() / (1024 * 1024);
            editorBridgeService.sendComponentRequiredAction(
                    pe.packId(), pe.service(), pe.modelId(), sizeMb, pe.featureKeys(), trigger);
        } catch (Exception ignored) {
            // 提示失败不该影响降级转换
        }
    }

    private ProjectFile getPdfFile(Long fileId) {
        ProjectFile file = projectFileService.getFile(fileId);
        if (file == null) {
            throw new PdfEditService.PdfEditException("文件不存在，ID=" + fileId);
        }
        // fileId 是模型自由填写的普通参数（而模型的输入里混着文档正文、网页、他人上传的文件名），
        // 所以按 ID 取到的每一份 ProjectFile 都要和当前会话的真实项目比一次——这是全仓统一的
        // 围栏，PdfTools 此前漏了。pdf_merge 尤其经不起漏：合并会把另一个项目那份 PDF 的
        // 全部内容复制进本项目，一次调用就是一次完整的跨项目搬运。
        String denied = ToolFileGuard.rejectIfOutsideProject(file);
        if (denied != null) {
            throw new PdfEditService.PdfEditException(denied.replaceFirst("^Error: ", ""));
        }
        String name = file.getName() != null ? file.getName().toLowerCase() : "";
        if (!name.endsWith(".pdf")) {
            throw new PdfEditService.PdfEditException("该文件不是 PDF 格式: " + file.getName());
        }
        return file;
    }

    private Path resolveExisting(ProjectFile file) {
        Path localPath = storageResolver.resolve(file.getFilePath());
        if (!Files.exists(localPath)) {
            throw new PdfEditService.PdfEditException("文件不存在于本地磁盘: " + localPath);
        }
        return localPath;
    }

    private Path resolvePdf(Long fileId) {
        return resolveExisting(getPdfFile(fileId));
    }

    /**
     * 修改类工具收尾三步：新 wpsFileId（前端预览 watch 它重新拉字节）→ 存库 → 通知重载。
     * 与 PptxTools 的编辑器重载口径一致。
     */
    private void finishModification(ProjectFile file, Path localPath) {
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        file.setWpsFileId(String.format("project_%d_doc_%d_%s", file.getProjectId(), System.currentTimeMillis(), rand));
        file.setUpdatedAt(LocalDateTime.now());
        try {
            file.setFileSize(Files.size(localPath));
        } catch (Exception e) {
            log.warn("Failed to update file size: {}", e.getMessage());
        }
        projectFileRepository.save(file);
        editorBridgeService.sendReloadFileAction(file);
    }

    /** 一次拆分最多产出多少份，防一条模型指令在文件树里刷出上百个文件。 */
    private static final int MAX_SPLIT_PARTS = 50;

    /** 产出一份新 PDF 的那一步：拿到临时目标路径，写完返回给用户看的那句话。 */
    @FunctionalInterface
    private interface PdfProducer {
        String writeTo(Path target) throws Exception;
    }

    /**
     * 页级操作的统一收尾：<b>先写临时文件，再登记进文件树，最后搬进位</b>。
     *
     * <p>落点是 {@code source} 所在的那个文件夹——证据卷宗都放在文件夹里，
     * 把产物扔到项目根目录等于让用户自己再搬一次。同名冲突走 RENAME 自动加 "(n)"，
     * 绝不覆盖：覆盖掉的可能正是上一次的合卷成果。
     *
     * <p>登记失败时临时文件会被删掉——留一个不在文件树里的孤儿文件，用户既看不见也删不掉。
     */
    private String produce(ProjectFile source, String outputName, PdfProducer producer) {
        String name = sanitizeName(outputName);
        Path temp = null;
        try {
            Path projectRoot = storageResolver.projectRoot(source.getProjectId());
            Files.createDirectories(projectRoot);
            temp = projectRoot.resolve(TEMP_PREFIX + System.nanoTime() + ".pdf");

            String summary = producer.writeTo(temp);

            ProjectFile created = projectFileService.createFile(
                    source.getProjectId(), source.getParentId(), name, "pdf",
                    Files.size(temp), null,
                    "project_" + source.getProjectId() + "_ai_" + System.currentTimeMillis(),
                    AGENT_USER_ID, ProjectFileService.ConflictPolicy.RENAME);
            Path target = storageResolver.resolve(created.getFilePath());
            Files.createDirectories(target.getParent());
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            created.setFileSize(Files.size(target));
            projectFileRepository.save(created);

            return String.format("%s 新文件『%s』（文件 ID: %d）已保存在原件所在目录。",
                    summary, created.getName(), created.getId());
        } catch (PdfEditService.PdfEditException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.error("PDF 页操作失败: {}", name, e);
            // message 可能是 null（NPE 之类），直接拼会得到一句 "Error: null" 让模型无从下手
            return "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignore) {
                    log.warn("清理临时文件失败: {}", temp);
                }
            }
        }
    }

    /**
     * 产出文件名归一：补 .pdf 后缀，并抹掉路径分隔符。
     *
     * <p>outputName 是模型自由填写的参数，写成 "证据/合卷.pdf" 会在文件树里造出一个
     * 名字带斜杠的条目、物理路径也跟着嵌一层——用户在界面上既找不到也删不掉。
     * （真正的目录穿越由 {@code ProjectFileService.requireProjectScopedPath} 兜底，
     * 这里管的是「名字就该是个名字」。）
     */
    private static String sanitizeName(String outputName) {
        String name = outputName.replace('/', '_').replace('\\', '_').trim();
        if (name.isEmpty()) name = "输出";
        return name.toLowerCase().endsWith(".pdf") ? name : name + ".pdf";
    }

    /** 去掉 .pdf 扩展名；用于派生产出文件名。 */
    private static String baseName(String fileName) {
        return fileName == null ? "文档" : fileName.replaceAll("(?i)\\.pdf$", "");
    }

    private static String defaultName(String given, String fallback) {
        return given != null && !given.isBlank() ? given : fallback + ".pdf";
    }

    /**
     * 0 基页下标 → 面向用户的 1 基描述，如「第3页」「第1-5页」「第1,4,9页」。
     *
     * <p>这个串既进返回文案也进拆分产物的文件名，所以零散页超过 10 个就折成
     * 「第A-B页中的N页」——否则删 200 个零散页会得到一个四千字的工具输出和一个没法用的文件名。
     */
    private static String pageLabel(List<Integer> zeroBased) {
        if (zeroBased.isEmpty()) return "第0页";
        int first = zeroBased.get(0) + 1;
        int last = zeroBased.get(zeroBased.size() - 1) + 1;
        if (zeroBased.size() == 1) return "第" + first + "页";
        if (last - first + 1 == zeroBased.size()) return "第" + first + "-" + last + "页";
        if (zeroBased.size() > 10) return "第" + first + "-" + last + "页中的" + zeroBased.size() + "页";
        StringBuilder sb = new StringBuilder("第");
        for (int i = 0; i < zeroBased.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(zeroBased.get(i) + 1);
        }
        return sb.append('页').toString();
    }

    /**
     * 统一的失败返回。
     *
     * <p><b>必须以 "Error:" 开头</b>：{@code ToolRegistry.ToolResult.success()} 只认
     * {@code Error} / 「错误」前缀与 {@code {"error"} } JSON 形态，此前这里的兜底分支返回的是
     * 「读取 PDF 失败: …」——判据看不见，于是失败被判成 SUCCESS：过程卡给失败调用打绿勾、
     * 连续失败纠正回路被清零（模型能对着同一个错误重试到步数上限）、埋点也记 success=true。
     */
    private String errorOf(String prefix, Exception e) {
        if (e instanceof PdfEditService.PdfEditException) {
            return "Error: " + e.getMessage();
        }
        log.error(prefix, e);
        return "Error: " + prefix + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    private String brief(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
