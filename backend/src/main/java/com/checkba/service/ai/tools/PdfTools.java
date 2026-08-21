package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.AiDocxExportService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.PdfEditService;
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

    private static final Long AGENT_USER_ID = 10001L;
    private static final int INSPECT_MAX_CHARS_PER_PAGE = 3000;

    // ==================== 读取 ====================

    @ToolMeta(displayName = "列出PDF文件", category = "pdf")
    @Tool("PDF 专用清单，也是 PDF 文件 ID 的唯一来源：doc_list_project_files 不列 PDF、search_project_files 不返回 ID，"
            + "所有 pdf_* 工具的 fileId 只能从这里获取。列出项目中的所有 PDF 文件，返回文件 ID、名称和位置。")
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
            sb.append("\n后续 pdf_inspect / pdf_highlight / pdf_annotate / pdf_redact / pdf_replace_text / pdf_to_word 均使用上述 ID。");
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to list PDF files", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "读取PDF内容", category = "pdf")
    @Tool("读取 PDF 文件的逐页文本与基本信息（页数、每页是否有文本层）。页码从 0 开始。" +
          "对 PDF 做高亮/批注/脱敏/替换前必须先调用本工具核对原文的准确写法——后续操作全部靠引用原文文本定位。" +
          "如果页面 has_text_layer 为 false，说明是扫描件：无法做文本定位类操作（高亮/脱敏/替换），" +
          "但 pdf_to_word 可以直接对它做本地 MinerU OCR 转成可编辑 Word。")
    public String pdf_inspect(
            @P("文件 ID（从 pdf_list_files 获取）") Long fileId,
            @P("页码（从 0 开始，可选）。指定后只返回该页；传 null 返回全部页") Integer pageIndex
    ) {
        log.info("Tool: pdf_inspect called, fileId={}, pageIndex={}", fileId, pageIndex);
        try {
            Path localPath = resolvePdf(fileId);
            return pdfEditService.inspect(localPath, pageIndex, INSPECT_MAX_CHARS_PER_PAGE);
        } catch (Exception e) {
            return errorOf("读取 PDF 失败", e);
        }
    }

    // ==================== 高亮 / 批注 ====================

    @ToolMeta(displayName = "高亮PDF文本", category = "pdf", fileEffect = "MODIFIED")
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

    @ToolMeta(displayName = "添加PDF批注", category = "pdf", fileEffect = "MODIFIED")
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

    @ToolMeta(displayName = "PDF脱敏", category = "pdf", fileEffect = "MODIFIED")
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

    @ToolMeta(displayName = "替换PDF文本", category = "pdf", fileEffect = "MODIFIED")
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

    // ==================== 转 Word ====================

    @ToolMeta(displayName = "PDF转Word", category = "pdf", fileEffect = "ADDED", refreshFiles = true)
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
                            AGENT_USER_ID);
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

    private ProjectFile getPdfFile(Long fileId) {
        ProjectFile file = projectFileService.getFile(fileId);
        if (file == null) {
            throw new PdfEditService.PdfEditException("文件不存在，ID=" + fileId);
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

    private String errorOf(String prefix, Exception e) {
        if (e instanceof PdfEditService.PdfEditException) {
            return "Error: " + e.getMessage();
        }
        log.error(prefix, e);
        return prefix + ": " + e.getMessage();
    }

    private String brief(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
