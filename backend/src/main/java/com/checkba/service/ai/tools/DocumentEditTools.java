package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.SseEmitterService;
import com.checkba.service.ai.EditorBridgeService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档编辑工具集（嵌入式 LibreOffice 编辑器）
 *
 * 提供 Agent 操作文档的能力：
 * 1. 列出项目文档
 * 2. 打开文档进行编辑
 * 3. 获取选区、查找替换、段落操作等
 * 4. 搜索相关文档
 *
 * 技术说明：
 * - doc_open_file 和 doc_list_project_files 可以直接在后端完成
 * - 其他操作需要通过 SSE client_action 发送到前端执行，然后等待结果返回
 * - 历史沿革：原名 WpsTools（WPS WebOffice 时代）；编辑器已全面迁移到 LibreOffice，
 *   工具名 doc_* 与 SSE 事件名暂保留旧名（前后端契约），见 docs/ai_agent_dev.md §2.2
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentEditTools implements AgentToolComponent {

    private final ProjectFileService projectFileService;
    private final ProjectFileRepository projectFileRepository;
    private final EditorBridgeService editorBridgeService;

    // ==================== 文件管理工具 ====================

    @Tool("列出项目中的所有可编辑文档文件（docx, doc, xlsx, xls, pptx, ppt）。返回文件ID、名称和类型的列表。")
    public String doc_list_project_files(
            @P("项目ID") Long projectId
    ) {
        log.info("Tool: doc_list_project_files called for projectId={}", projectId);
        try {
            List<ProjectFile> files = projectFileRepository.findByProjectIdOrderBySortOrderAsc(projectId);
            
            // 过滤出可编辑的文档文件
            List<ProjectFile> editableFiles = files.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsFolder()))
                    .filter(f -> isEditableDocument(f.getName()))
                    .collect(Collectors.toList());
            
            if (editableFiles.isEmpty()) {
                return "项目中没有可编辑的文档文件。";
            }
            
            StringBuilder sb = new StringBuilder("项目文档列表 (共 " + editableFiles.size() + " 个):\n");
            for (ProjectFile f : editableFiles) {
                sb.append(String.format("- ID: %d, 名称: %s, 类型: %s\n", 
                        f.getId(), f.getName(), f.getFileType()));
            }
            return sb.toString();
            
        } catch (Exception e) {
            log.error("Failed to list project files", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("打开指定文档进行编辑。文档会在编辑器中打开，之后可以使用其他文档编辑工具进行操作。")
    public String doc_open_file(
            @P("文件ID（从 doc_list_project_files 获取）") Long fileId
    ) {
        log.info("Tool: doc_open_file called for fileId={}", fileId);
        try {
            ProjectFile file = projectFileService.getFile(fileId);
            if (file == null) {
                return "Error: 文件不存在，ID=" + fileId;
            }
            
            if (!isEditableDocument(file.getName())) {
                return "Error: 该文件不是可编辑的文档格式: " + file.getName();
            }
            
            // 通过 SSE 发送打开文件指令到前端
            editorBridgeService.sendOpenFileAction(file);
            
            return String.format("已发送打开文件指令。文件名: %s, 类型: %s。请等待文档加载完成后再进行后续操作。", 
                    file.getName(), file.getFileType());
            
        } catch (Exception e) {
            log.error("Failed to open file", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 流式写入 ====================

    private static final Long AGENT_USER_ID = 10001L;

    @Tool("开始实时流式写入文档。使用此工具后，模型生成的后续内容将直接写入打开的文档中。" +
          "**重要：创建新文件时必须提供 fileName 和 projectId 参数。** " +
          "调用此工具后，你必须立即开始生成文档内容，并且必须使用严格的 Markdown 格式（Markdown Heading #, ##, ### 等）。" +
          "不要在调用此工具后输出任何非文档内容的闲聊，直接开始输出文档标题和正文。")
    public String doc_start_stream(
            @P("要打开的文件ID (如果是新建文件则传 null)") Long fileId,
            @P("新建文件名 (如 '法律意见书.docx')，仅当 fileId=null 时必填") String fileName,
            @P("项目ID，仅当 fileId=null 时必填") Long projectId
    ) {
        log.info("Tool: doc_start_stream called fileId={}, fileName={}, projectId={}", fileId, fileName, projectId);
        try {
            String conversationId = editorBridgeService.getCurrentConversationId();
            if (conversationId == null) {
                return "Error: 无法获取当前会话上下文";
            }

            ProjectFile file = null;

            // 1. 如果 fileId=null，创建新的空白 docx 文件
            if (fileId == null) {
                if (fileName == null || fileName.trim().isEmpty()) {
                    return "Error: 创建新文件时必须提供 fileName 参数";
                }
                if (projectId == null) {
                    return "Error: 创建新文件时必须提供 projectId 参数";
                }

                // 确保文件名以 .docx 结尾
                if (!fileName.toLowerCase().endsWith(".docx")) {
                    fileName = fileName + ".docx";
                }

                // 创建空白 docx 文件
                java.nio.file.Path projectDataDir = java.nio.file.Paths.get(System.getProperty("user.dir"))
                        .getParent().resolve("data/projects/" + projectId);
                if (!java.nio.file.Files.exists(projectDataDir)) {
                    java.nio.file.Files.createDirectories(projectDataDir);
                }
                java.nio.file.Path targetPath = projectDataDir.resolve(fileName);

                // 检查文件是否已存在，如果存在则自动重命名
                String originalFileName = fileName;
                String baseName = originalFileName;
                String extension = ".docx";
                if (originalFileName.toLowerCase().endsWith(".docx")) {
                    baseName = originalFileName.substring(0, originalFileName.length() - 5);
                }

                int counter = 1;
                while (java.nio.file.Files.exists(targetPath)) {
                    fileName = baseName + " (" + counter + ")" + extension;
                    targetPath = projectDataDir.resolve(fileName);
                    counter++;
                }
                
                if (counter > 1) {
                    log.info("File '{}' already exists. Renamed to '{}'", originalFileName, fileName);
                    // Update fileName argument effectively for the rest of the method? 
                    // No, 'fileName' variable is used below, so we are good.
                }

                // 创建空白 Word 文档
                org.docx4j.openpackaging.packages.WordprocessingMLPackage wordDoc = 
                        org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
                wordDoc.save(targetPath.toFile());

                // 注册到数据库
                String wpsId = "stream_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                String storageRelativePath = "projects/" + projectId + "/" + fileName;
                
                file = projectFileService.createOrUpdateFile(
                        projectId, null, fileName, "docx", targetPath.toFile().length(),
                        storageRelativePath, wpsId, AGENT_USER_ID
                );
                
                log.info("Created new docx file for streaming: id={}, name={}", file.getId(), file.getName());
                
                // 通知前端刷新文件列表
                editorBridgeService.sendRefreshFilesAction();
            } else {
                file = projectFileService.getFile(fileId);
                if (file == null) {
                    return "Error: 文件不存在，ID=" + fileId;
                }
            }

            // 2. 同步打开文件 (Wait for Ready)
            String resultJson = editorBridgeService.executeEditorCommand("wps_open_file_sync", java.util.Map.of(
                    "fileId", file.getId(),
                    "fileName", file.getName(),
                    "fileType", file.getFileType(),
                    "wpsFileId", file.getWpsFileId() != null ? file.getWpsFileId() : "",
                    "trackRevisions", false  // 流式写入不需要修订模式
            ));
            
            if (resultJson.contains("\"error\"")) {
                return "Error opening file: " + resultJson;
            }

            // 3. 开启流式模式
            editorBridgeService.setStreamingMode(conversationId, true);

            return "文档流式写入模式已激活，文件: " + file.getName() + "。请立即开始生成文档内容。**务必使用 Markdown 格式 (H1=#, H2=##) 输出内容。**";
        } catch (Exception e) {
            log.error("Failed to start doc stream", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 选区和光标操作 ====================

    @Tool("获取文档中当前选区的文本内容和位置信息。用于了解用户当前光标位置和选中的文本。")
    public String doc_get_selection() {
        log.info("Tool: doc_get_selection called");
        try {
            return editorBridgeService.executeEditorCommand("get_selection", null);
        } catch (Exception e) {
            log.error("Failed to get selection", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("移动文档的光标到指定位置。")
    public String doc_goto(
            @P("定位类型: paragraph(段落)/bookmark(书签)/start(文档开头)/end(文档结尾)/line(行号)") String type,
            @P("目标值: 段落号、书签名、行号等。对于 start/end 类型可以为空。") String target
    ) {
        log.info("Tool: doc_goto called type={}, target={}", type, target);
        try {
            return editorBridgeService.executeEditorCommand("goto", 
                    java.util.Map.of("type", type, "target", target != null ? target : ""));
        } catch (Exception e) {
            log.error("Failed to goto position", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("设置文档的选区范围（精确控制光标/选区）。Start 和 End 是字符索引位置。")
    public String doc_set_selection(
            @P("选区开始位置 (0-based 字符索引)") Integer start,
            @P("选区结束位置 (0-based 字符索引)") Integer end
    ) {
        log.info("Tool: doc_set_selection called start={}, end={}", start, end);
        try {
            return editorBridgeService.executeEditorCommand("set_selection", 
                    java.util.Map.of("start", start, "end", end));
        } catch (Exception e) {
            log.error("Failed to set selection", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 查找和替换 ====================

    @Tool("【找】在文档中查找文本。每个匹配返回：anchorId（稳定锚点，编辑后依然有效）、前后文 contextBefore/contextAfter、所在段落 paragraph。" +
          "有多个匹配时先根据上下文确认哪一个才是目标，再用 anchorId 配合 doc_select_anchor（选中查看）或 doc_replace_at_anchor（精准替换）操作。")
    public String doc_find_text(
            @P("要查找的文本") String keyword,
            @P("是否区分大小写，默认 false") Boolean matchCase
    ) {
        log.info("Tool: doc_find_text called keyword={}", keyword);
        try {
            // Updated to call 'find_text_locations' which returns detailed positions
            return editorBridgeService.executeEditorCommand("find_text_locations", 
                    java.util.Map.of("keyword", keyword, "matchCase", matchCase != null ? matchCase : false));
        } catch (Exception e) {
            log.error("Failed to find text", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "查找替换", category = "document", fileEffect = "MODIFIED")
    @Tool("在文档中查找并替换文本。所有修改将以修订模式进行，用户可以审阅后接受或拒绝。")
    public String doc_find_replace(
            @P("要查找的文本") String findText,
            @P("替换为的文本") String replaceText,
            @P("是否替换全部匹配项，默认 true") Boolean replaceAll
    ) {
        log.info("Tool: doc_find_replace called find={}, replace={}", findText, replaceText);
        try {
            return editorBridgeService.executeEditorCommand("find_replace", 
                    java.util.Map.of(
                            "findText", findText, 
                            "replaceText", replaceText, 
                            "replaceAll", replaceAll != null ? replaceAll : true
                    ));
        } catch (Exception e) {
            log.error("Failed to find and replace", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("将文档中第 N 个可见匹配项替换为新文本。" +
          "索引从 1 开始，只计算用户可见的匹配（排除修订模式下被删除的内容）。" +
          "如果要删除文本，将 replaceText 设置为空字符串即可。")
    public String doc_replace_nth_match(
            @P("要查找的文本") String findText,
            @P("替换为的文本") String replaceText,
            @P("第几个可见匹配（从 1 开始）") Integer matchIndex
    ) {
        log.info("Tool: doc_replace_nth_match called find={}, replace={}, index={}", findText, replaceText, matchIndex);
        try {
            if (matchIndex == null || matchIndex < 1) {
                return "Error: matchIndex 必须是从 1 开始的正整数";
            }
            return editorBridgeService.executeEditorCommand("replace_nth_match", 
                    java.util.Map.of(
                            "findText", findText, 
                            "replaceText", replaceText, 
                            "matchIndex", matchIndex
                    ));
        } catch (Exception e) {
            log.error("Failed to replace nth match", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("删除文档中第 N 个可见的匹配文本。专门用于删除操作，通过查找文本并执行删除。")
    public String doc_delete_match(
            @P("要删除的文本内容") String findText,
            @P("第几个可见匹配（从 1 开始）") Integer matchIndex
    ) {
        log.info("Tool: doc_delete_match called find={}, index={}", findText, matchIndex);
        try {
            if (matchIndex == null || matchIndex < 1) {
                return "Error: matchIndex 必须是从 1 开始的正整数";
            }
            return editorBridgeService.executeEditorCommand("delete_match", 
                    java.util.Map.of(
                            "findText", findText, 
                            "matchIndex", matchIndex
                    ));
        } catch (Exception e) {
            log.error("Failed to delete match", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("删除文档中的文本内容。可以删除所有匹配项，或只删除第一个匹配项。")
    public String doc_delete_text(
            @P("要删除的文本内容") String text,
            @P("是否删除所有匹配项，默认 true") Boolean deleteAll
    ) {
        log.info("Tool: doc_delete_text called text={}, all={}", text, deleteAll);
        try {
            return editorBridgeService.executeEditorCommand("delete_text", 
                    java.util.Map.of(
                            "text", text, 
                            "deleteAll", deleteAll != null ? deleteAll : true
                    ));
        } catch (Exception e) {
            log.error("Failed to delete text", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("替换当前选区（或光标位置）的文本内容。如果选区非空，则替换选区；如果只是光标，则插入文本。")
    public String doc_replace_selection(
            @P("用于替换的文本内容") String text
    ) {
        log.info("Tool: doc_replace_selection called text length={}", text.length());
        try {
            return editorBridgeService.executeEditorCommand("replace_selection", 
                    java.util.Map.of("text", text));
        } catch (Exception e) {
            log.error("Failed to replace selection", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 插入和修改 ====================

    @Tool("在文档的当前光标位置插入文本内容。修改将以修订模式进行。")
    public String doc_insert_at_cursor(
            @P("要插入的文本内容") String text
    ) {
        log.info("Tool: doc_insert_at_cursor called, text length={}", text.length());
        try {
            return editorBridgeService.executeEditorCommand("insert_at_cursor", 
                    java.util.Map.of("text", text));
        } catch (Exception e) {
            log.error("Failed to insert at cursor", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("获取文档中指定段落的文本内容。")
    public String doc_get_paragraph(
            @P("段落索引，从 1 开始") Integer paragraphIndex
    ) {
        log.info("Tool: doc_get_paragraph called index={}", paragraphIndex);
        try {
            return editorBridgeService.executeEditorCommand("get_paragraph", 
                    java.util.Map.of("index", paragraphIndex));
        } catch (Exception e) {
            log.error("Failed to get paragraph", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "修改段落", category = "document", fileEffect = "MODIFIED")
    @Tool("修改文档中指定段落的文本内容。修改将以修订模式进行，用户可以审阅后接受或拒绝。")
    public String doc_modify_paragraph(
            @P("段落索引，从 1 开始") Integer paragraphIndex,
            @P("新的段落文本") String newText
    ) {
        log.info("Tool: doc_modify_paragraph called index={}, new text length={}", paragraphIndex, newText.length());
        try {
            return editorBridgeService.executeEditorCommand("modify_paragraph", 
                    java.util.Map.of("index", paragraphIndex, "newText", newText));
        } catch (Exception e) {
            log.error("Failed to modify paragraph", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 文档结构 ====================

    @Tool("获取文档的大纲结构，包括各级标题及其位置。")
    public String doc_get_outline() {
        log.info("Tool: doc_get_outline called");
        try {
            return editorBridgeService.executeEditorCommand("get_outline", null);
        } catch (Exception e) {
            log.error("Failed to get outline", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("在文档的指定标题下方插入新内容。修改将以修订模式进行。")
    public String doc_insert_under_heading(
            @P("标题文本，用于定位插入位置") String headingText,
            @P("要插入的内容") String content
    ) {
        log.info("Tool: doc_insert_under_heading called heading={}", headingText);
        try {
            return editorBridgeService.executeEditorCommand("insert_under_heading", 
                    java.util.Map.of("headingText", headingText, "content", content));
        } catch (Exception e) {
            log.error("Failed to insert under heading", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 智能搜索 ====================

    @Tool("搜索项目中可能需要修改的相关文档。根据关键词在文件名和文档内容中搜索。")
    public String doc_search_related_docs(
            @P("搜索关键词，如'交易方案'、'股东决议'等") String keyword,
            @P("项目ID") Long projectId
    ) {
        log.info("Tool: doc_search_related_docs called keyword={}, projectId={}", keyword, projectId);
        try {
            List<ProjectFile> allFiles = projectFileRepository.findByProjectIdOrderBySortOrderAsc(projectId);
            
            // 1. 首先按文件名匹配
            List<ProjectFile> matchedByName = allFiles.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsFolder()))
                    .filter(f -> isEditableDocument(f.getName()))
                    .filter(f -> f.getName().contains(keyword))
                    .collect(Collectors.toList());
            
            // 2. 如果文件名匹配不够，可以考虑内容搜索（使用 RAG 服务）
            // TODO: 集成 ProjectRagService 进行内容搜索
            
            if (matchedByName.isEmpty()) {
                // 返回所有可编辑文档供参考
                List<ProjectFile> editableFiles = allFiles.stream()
                        .filter(f -> !Boolean.TRUE.equals(f.getIsFolder()))
                        .filter(f -> isEditableDocument(f.getName()))
                        .limit(10)
                        .collect(Collectors.toList());
                
                if (editableFiles.isEmpty()) {
                    return "未找到包含关键词'" + keyword + "'的文档，项目中也没有其他可编辑文档。";
                }
                
                StringBuilder sb = new StringBuilder("未找到包含关键词'" + keyword + "'的文档。以下是项目中的可编辑文档供参考:\n");
                for (ProjectFile f : editableFiles) {
                    sb.append(String.format("- ID: %d, 名称: %s\n", f.getId(), f.getName()));
                }
                return sb.toString();
            }
            
            StringBuilder sb = new StringBuilder("找到 " + matchedByName.size() + " 个可能相关的文档:\n");
            for (ProjectFile f : matchedByName) {
                sb.append(String.format("- ID: %d, 名称: %s, 类型: %s\n", 
                        f.getId(), f.getName(), f.getFileType()));
            }
            sb.append("\n建议：使用 doc_open_file 打开需要修改的文档，然后使用其他文档编辑工具进行编辑。");
            return sb.toString();
            
        } catch (Exception e) {
            log.error("Failed to search related docs", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 拟人式原语（嵌入式 LibreOffice 编辑器） ====================
    // 设计文档：docs/AI_EDITOR_PRIMITIVES.md。工作循环：看 → 找 → 选 → 改 → 验。
    // 定位一律使用 doc_find_text 返回的 anchorId（书签锚点，随文档编辑自动跟随），
    // 禁止使用整数字符偏移（跨富文本必然错位）。

    @Tool("【看】分段读取文档正文。返回带编号的段落列表（含标题级别），是了解文档内容的首选工具。" +
          "文档很长时结果会分页：返回 truncated=true 和 nextStartParagraph，用它继续读下一段。")
    public String doc_get_document_text(
            @P("起始段落号（0 开始，默认 0）") Integer startParagraph,
            @P("最多返回的段落数（默认 200）") Integer maxParagraphs
    ) {
        log.info("Tool: doc_get_document_text called start={}, max={}", startParagraph, maxParagraphs);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (startParagraph != null) params.put("startParagraph", startParagraph);
            if (maxParagraphs != null) params.put("maxParagraphs", maxParagraphs);
            return editorBridgeService.executeEditorCommand("get_document_text", params);
        } catch (Exception e) {
            log.error("Failed to get document text", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "识别合同条款", category = "document")
    @Tool("【看】识别合同/协议的条款结构。按「第X条 / 第X章 / 一、二、」等编号文本把段落归并成条款，" +
          "返回每条条款的编号、标题和段落范围（startParagraph~endParagraph）。" +
          "统计条款数、按条款定位或修订时必须先用本工具，禁止把段落数/行数当条款数。" +
          "拿到段落范围后可用 doc_get_document_text(startParagraph) 精读某条条款。")
    public String doc_get_clauses() {
        log.info("Tool: doc_get_clauses called");
        try {
            return editorBridgeService.executeEditorCommand("get_clauses", null);
        } catch (Exception e) {
            log.error("Failed to get clauses", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【看】查看当前光标/选区周围的文本（选中内容、前后文、所在段落）。在插入或格式化之前先确认光标位置。")
    public String doc_get_cursor_context() {
        log.info("Tool: doc_get_cursor_context called");
        try {
            return editorBridgeService.executeEditorCommand("get_cursor_context", null);
        } catch (Exception e) {
            log.error("Failed to get cursor context", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【选】选中 doc_find_text 返回的某个匹配（按 anchorId）。编辑器会滚动到该处并高亮选区，用户能看到 AI 正在操作哪里。" +
          "选中后可接 doc_replace_selection / doc_delete_selection / doc_format_selection / doc_collapse_cursor。")
    public String doc_select_anchor(
            @P("doc_find_text 返回的 anchorId") String anchorId
    ) {
        log.info("Tool: doc_select_anchor called anchor={}", anchorId);
        try {
            return editorBridgeService.executeEditorCommand("set_selection",
                    java.util.Map.of("anchor", anchorId != null ? anchorId : ""));
        } catch (Exception e) {
            log.error("Failed to select anchor", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【选】按段落号选中整个段落（0 开始，配合 doc_get_document_text 的编号）。编辑器会滚动到该段落并高亮。")
    public String doc_select_paragraph(
            @P("段落号（0 开始）") Integer index
    ) {
        log.info("Tool: doc_select_paragraph called index={}", index);
        try {
            return editorBridgeService.executeEditorCommand("select_paragraph",
                    java.util.Map.of("index", index != null ? index : 0));
        } catch (Exception e) {
            log.error("Failed to select paragraph", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【选】把光标落到当前选区的开头或结尾（取消选中）。要在某处'之前/之后'插入文本时：先选中目标，再 collapse 到 start/end，然后 doc_insert_at_cursor。")
    public String doc_collapse_cursor(
            @P("start=选区开头, end=选区结尾") String to
    ) {
        log.info("Tool: doc_collapse_cursor called to={}", to);
        try {
            return editorBridgeService.executeEditorCommand("collapse_selection",
                    java.util.Map.of("to", to != null ? to : "end"));
        } catch (Exception e) {
            log.error("Failed to collapse cursor", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【改】把某个锚点（anchorId）处的文本替换为新文本，以修订模式进行。返回改动后所在段落的实际文本，务必核对确认改对了。" +
          "这是最精准的替换方式：先 doc_find_text 拿到带上下文的匹配列表，选定目标的 anchorId 后用本工具替换。")
    public String doc_replace_at_anchor(
            @P("doc_find_text 返回的 anchorId") String anchorId,
            @P("新文本") String newText
    ) {
        log.info("Tool: doc_replace_at_anchor called anchor={}", anchorId);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("anchor", anchorId != null ? anchorId : "");
            params.put("newText", newText != null ? newText : "");
            return editorBridgeService.executeEditorCommand("replace_at_position", params);
        } catch (Exception e) {
            log.error("Failed to replace at anchor", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【改】删除当前选中的文本（以修订模式）。先用 doc_select_anchor / doc_select_paragraph 选中要删的内容。没有选区时会报错。")
    public String doc_delete_selection() {
        log.info("Tool: doc_delete_selection called");
        try {
            return editorBridgeService.executeEditorCommand("delete_selection", null);
        } catch (Exception e) {
            log.error("Failed to delete selection", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【格式】给当前选中的文本设置字符格式：加粗/斜体/下划线/删除线/高亮/字色/字号/字体。只传需要改的参数。" +
          "必须先选中文本（doc_select_anchor / doc_select_paragraph）。高亮支持 yellow/green/cyan/magenta/red/blue/gray/none 或 #RRGGBB；none 取消高亮。")
    public String doc_format_selection(
            @P("加粗 true/false，不改则不传") Boolean bold,
            @P("斜体 true/false，不改则不传") Boolean italic,
            @P("下划线 true/false，不改则不传") Boolean underline,
            @P("删除线 true/false，不改则不传") Boolean strikeout,
            @P("高亮颜色：yellow/green/cyan/magenta/red/blue/gray/none 或 #RRGGBB，不改则不传") String highlight,
            @P("文字颜色：#RRGGBB 或 auto，不改则不传") String color,
            @P("字号（磅），不改则不传") Double fontSize,
            @P("字体名，不改则不传") String fontName
    ) {
        log.info("Tool: doc_format_selection called");
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (bold != null) params.put("bold", bold);
            if (italic != null) params.put("italic", italic);
            if (underline != null) params.put("underline", underline);
            if (strikeout != null) params.put("strikeout", strikeout);
            if (highlight != null && !highlight.isEmpty()) params.put("highlight", highlight);
            if (color != null && !color.isEmpty()) params.put("color", color);
            if (fontSize != null) params.put("fontSize", fontSize);
            if (fontName != null && !fontName.isEmpty()) params.put("fontName", fontName);
            return editorBridgeService.executeEditorCommand("format_selection", params);
        } catch (Exception e) {
            log.error("Failed to format selection", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【格式】设置当前选区所在段落的段落格式：对齐方式和/或标题级别。" +
          "headingLevel: 1-9 设为对应级别标题，0 恢复正文。alignment: left/right/center/justify。")
    public String doc_set_paragraph_format(
            @P("对齐：left/right/center/justify，不改则不传") String alignment,
            @P("标题级别：1-9 为标题，0 恢复正文，不改则不传") Integer headingLevel
    ) {
        log.info("Tool: doc_set_paragraph_format called alignment={}, headingLevel={}", alignment, headingLevel);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (alignment != null && !alignment.isEmpty()) params.put("alignment", alignment);
            if (headingLevel != null) params.put("headingLevel", headingLevel);
            return editorBridgeService.executeEditorCommand("set_paragraph_format", params);
        } catch (Exception e) {
            log.error("Failed to set paragraph format", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【验/撤销】撤销最近的编辑操作。改错了（核对返回的段落文本发现不对）就用它退回，再重新操作。")
    public String doc_undo(
            @P("撤销步数，默认 1") Integer steps
    ) {
        log.info("Tool: doc_undo called steps={}", steps);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (steps != null) params.put("steps", steps);
            return editorBridgeService.executeEditorCommand("undo", params);
        } catch (Exception e) {
            log.error("Failed to undo", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【验/撤销】重做刚撤销的操作。")
    public String doc_redo(
            @P("重做步数，默认 1") Integer steps
    ) {
        log.info("Tool: doc_redo called steps={}", steps);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (steps != null) params.put("steps", steps);
            return editorBridgeService.executeEditorCommand("redo", params);
        } catch (Exception e) {
            log.error("Failed to redo", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 调试工具 ====================

    @Tool("调试工具：获取文档中所有修订记录的详细信息，包括修订类型、位置、内容等。用于分析和诊断修订模式下的文本操作问题。")
    public String doc_debug_revisions() {
        log.info("Tool: doc_debug_revisions called");
        try {
            return editorBridgeService.executeEditorCommand("debug_revisions", null);
        } catch (Exception e) {
            log.error("Failed to debug revisions", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 判断文件是否是可编辑的文档格式
     */
    private boolean isEditableDocument(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".docx") || lower.endsWith(".doc") 
                || lower.endsWith(".xlsx") || lower.endsWith(".xls")
                || lower.endsWith(".pptx") || lower.endsWith(".ppt");
    }
}

