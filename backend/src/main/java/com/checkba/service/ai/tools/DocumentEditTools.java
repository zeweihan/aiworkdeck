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
    private final com.checkba.storage.ProjectStorageResolver storageResolver;

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
            String denied = ToolFileGuard.rejectIfOutsideProject(file);
            if (denied != null) return denied;

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

                // 创建空白 docx 文件（localRoot 感知；旧实现无条件 getParent() 在打包态
                // cwd=~/.aiworkdeck 下会错误解析到 ~/data/projects/）
                java.nio.file.Path projectDataDir = storageResolver.projectRoot(projectId);
                if (!java.nio.file.Files.exists(projectDataDir)) {
                    java.nio.file.Files.createDirectories(projectDataDir);
                }
                // fileName 由 LLM 填写：不做归一化围栏的话，"../42/补充协议.docx"
                // 会把伪造文档直接落进别家项目的目录
                java.nio.file.Path targetPath = projectDataDir.resolve(fileName).normalize();
                if (!targetPath.startsWith(projectDataDir.normalize())) {
                    return "Error: 非法文件名，路径越出项目目录";
                }

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
                String denied = ToolFileGuard.rejectIfOutsideProject(file);
                if (denied != null) return denied;
            }

            // 2. 同步打开文件 (Wait for Ready)
            String resultJson = editorBridgeService.executeEditorCommand("doc_open_file_sync", java.util.Map.of(
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

            return "文档流式写入模式已激活，文件: " + file.getName() + "。请立即开始生成文档内容。" +
                    "**务必使用严格的 Markdown 格式输出**（主标题=#、小标题=##/###、表格用 | 语法、列表用 - 或 1.、加粗用 **）。" +
                    "Markdown 标记不会原样落入文档：编辑器会实时把它转换成律所标准格式" +
                    "（楷体_GB2312/Arial、主标题 16 号加粗居中、正文 12 号两端对齐、表格 Grid 边框等），" +
                    "所以不要为了排版手动加空行或符号装饰。";
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
          "有多个匹配时先根据上下文确认哪一个才是目标，再用 anchorId 直接 doc_replace_at_anchor（精准替换，会自动滚动定位并返回改后段落）。" +
          "多处独立修改：拿到各自 anchorId 后在同一轮连续输出多个替换调用。目标文本全文唯一时不必先找，直接 doc_find_replace。")
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
        if (findText == null || findText.isEmpty() || replaceText == null) {
            return "Error: 缺少必填参数 findText/replaceText。请用命名参数重新调用，例如 doc_find_replace(findText=\"原文\", replaceText=\"新文\", replaceAll=true)。";
        }
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

    @Tool("【改】把某个锚点（anchorId）处的文本替换为新文本，以修订模式进行。会自动把编辑器视图滚动到该处；返回改动后所在段落的实际文本，核对该返回值即完成验证——不需要先 doc_select_anchor，也不需要改后再读文档。" +
          "先 doc_find_text 拿到带上下文的匹配列表，选定目标的 anchorId 后用本工具替换；多处独立替换在同一轮连续输出多个调用。")
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

    @Tool("【格式】设置当前选区所在段落的段落格式：对齐、标题级别、行距、段前段后间距、缩进。只传需要改的参数。" +
          "headingLevel: 1-9 设为对应级别标题，0 恢复正文。alignment: left/right/center/justify。" +
          "行距 lineSpacingMode: single/1.5/double 直接传；proportional 配 lineSpacingValue=百分比（如 120）；" +
          "atLeast（最小值）/exactly（固定值）配 lineSpacingValue=磅数。" +
          "缩进：firstLineIndentChars 首行缩进 N 字符（中文文档惯例，如 2）；也可用 firstLineIndentPt/leftIndentPt/rightIndentPt 按磅设。")
    public String doc_set_paragraph_format(
            @P("对齐：left/right/center/justify，不改则不传") String alignment,
            @P("标题级别：1-9 为标题，0 恢复正文，不改则不传") Integer headingLevel,
            @P("行距模式：single/1.5/double/proportional/atLeast/exactly，不改则不传") String lineSpacingMode,
            @P("行距值：proportional 时为百分比，atLeast/exactly 时为磅数") Double lineSpacingValue,
            @P("段前间距（磅），不改则不传") Double spaceBeforePt,
            @P("段后间距（磅），不改则不传") Double spaceAfterPt,
            @P("首行缩进（字符数，如 2），不改则不传") Double firstLineIndentChars,
            @P("左缩进（磅），不改则不传") Double leftIndentPt,
            @P("右缩进（磅），不改则不传") Double rightIndentPt
    ) {
        log.info("Tool: doc_set_paragraph_format called alignment={}, headingLevel={}, lineSpacing={}/{}", alignment, headingLevel, lineSpacingMode, lineSpacingValue);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (alignment != null && !alignment.isEmpty()) params.put("alignment", alignment);
            if (headingLevel != null) params.put("headingLevel", headingLevel);
            if (lineSpacingMode != null && !lineSpacingMode.isEmpty()) params.put("lineSpacingMode", lineSpacingMode);
            if (lineSpacingValue != null) params.put("lineSpacingValue", lineSpacingValue);
            if (spaceBeforePt != null) params.put("spaceBeforePt", spaceBeforePt);
            if (spaceAfterPt != null) params.put("spaceAfterPt", spaceAfterPt);
            if (firstLineIndentChars != null) params.put("firstLineIndentChars", firstLineIndentChars);
            if (leftIndentPt != null) params.put("leftIndentPt", leftIndentPt);
            if (rightIndentPt != null) params.put("rightIndentPt", rightIndentPt);
            return editorBridgeService.executeEditorCommand("set_paragraph_format", params);
        } catch (Exception e) {
            log.error("Failed to set paragraph format", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "设置编号", category = "document", fileEffect = "MODIFIED")
    @Tool("【格式】给当前选区所在段落设置自动编号或项目符号（先选中段落，可跨多段）。" +
          "preset: bullet(•)/decimal(1. 2. 3.)/chinese(一、二、)/multilevel(多级编号 1. → 1.1 → 1.1.1)/none(去掉编号)。" +
          "level: 编号层级 1-9，默认 1；multilevel 配合不同 level 形成 1.1、1.1.1 结构。")
    public String doc_set_numbering(
            @P("编号类型：bullet/decimal/chinese/multilevel/none") String preset,
            @P("编号层级 1-9，默认 1") Integer level
    ) {
        log.info("Tool: doc_set_numbering called preset={}, level={}", preset, level);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (preset != null && !preset.isEmpty()) params.put("preset", preset);
            if (level != null) params.put("level", level);
            return editorBridgeService.executeEditorCommand("set_numbering", params);
        } catch (Exception e) {
            log.error("Failed to set numbering", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "设置表格格式", category = "document", fileEffect = "MODIFIED")
    @Tool("【格式】设置光标所在表格的格式（先把光标点进表格，或传 tableIndex 指定第 N 张表，0 开始）。" +
          "applyStandard=true 一键套标准表格式（Grid 实线 1.5 磅边框、10 号字、首行加粗居中、单元格垂直居中、数字居右）。" +
          "也可单独设：borderWidthPt 边框磅数、fontSizePt 表格字号、firstRowBold 首行加粗、" +
          "cellVerticalAlign(top/center/bottom) 单元格垂直对齐、columnWidthsPercent 列宽百分比（逗号分隔，如 '20,50,30'，个数=列数）、" +
          "rowHeightPt 行高磅数（rowHeightRule: min=最小值默认/exact=固定值）。")
    public String doc_format_table(
            @P("一键套标准表格式 true/false") Boolean applyStandard,
            @P("边框线宽（磅，如 1.5），不改则不传") Double borderWidthPt,
            @P("表格字号（磅），不改则不传") Double fontSizePt,
            @P("首行加粗 true/false，不改则不传") Boolean firstRowBold,
            @P("单元格垂直对齐：top/center/bottom，不改则不传") String cellVerticalAlign,
            @P("列宽百分比，逗号分隔如 '20,50,30'，不改则不传") String columnWidthsPercent,
            @P("行高（磅），不改则不传") Double rowHeightPt,
            @P("行高规则：min(最小值,默认)/exact(固定值)") String rowHeightRule,
            @P("表格序号（0 开始），不传则用光标所在表格") Integer tableIndex
    ) {
        log.info("Tool: doc_format_table called standard={}, border={}, tableIndex={}", applyStandard, borderWidthPt, tableIndex);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (applyStandard != null) params.put("applyStandard", applyStandard);
            if (borderWidthPt != null) params.put("borderWidthPt", borderWidthPt);
            if (fontSizePt != null) params.put("fontSizePt", fontSizePt);
            if (firstRowBold != null) params.put("firstRowBold", firstRowBold);
            if (cellVerticalAlign != null && !cellVerticalAlign.isEmpty()) params.put("cellVerticalAlign", cellVerticalAlign);
            if (columnWidthsPercent != null && !columnWidthsPercent.isEmpty()) params.put("columnWidthsPercent", columnWidthsPercent);
            if (rowHeightPt != null) params.put("rowHeightPt", rowHeightPt);
            if (rowHeightRule != null && !rowHeightRule.isEmpty()) params.put("rowHeightRule", rowHeightRule);
            if (tableIndex != null) params.put("tableIndex", tableIndex);
            return editorBridgeService.executeEditorCommand("format_table", params);
        } catch (Exception e) {
            log.error("Failed to format table", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "插入表格", category = "document", fileEffect = "MODIFIED")
    @Tool("【插入】在光标处插入一张表格，自动套标准表格式（Grid 1.5 磅边框、10 号字、首行加粗居中、数字居右）。" +
          "rowsJson 是 JSON 二维数组，如 [[\"项目\",\"金额\"],[\"咨询费\",\"10000\"]]，第一行默认为表头。")
    public String doc_insert_table(
            @P("表格内容，JSON 二维字符串数组，第一行为表头") String rowsJson,
            @P("第一行是否表头，默认 true") Boolean headerRow
    ) {
        log.info("Tool: doc_insert_table called, json length={}", rowsJson != null ? rowsJson.length() : 0);
        if (rowsJson == null || rowsJson.isBlank()) {
            return "Error: 缺少 rowsJson 参数（JSON 二维数组）";
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<java.util.List<String>> rows = mapper.readValue(
                    rowsJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.List<String>>>() {});
            if (rows.isEmpty() || rows.get(0).isEmpty()) {
                return "Error: rowsJson 不能为空表";
            }
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("rows", rows);
            params.put("headerRow", headerRow == null || headerRow);
            return editorBridgeService.executeEditorCommand("insert_table", params);
        } catch (com.fasterxml.jackson.core.JacksonException je) {
            return "Error: rowsJson 不是合法的 JSON 二维数组: " + je.getOriginalMessage();
        } catch (Exception e) {
            log.error("Failed to insert table", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "读取表格", category = "document")
    @Tool("【看/表格】把文档里的一张表读成二维数组（行列数 + 每格文本），改表格前必须先用它看清现状。" +
          "定位：tableIndex 第几张表（0 开始），或 tableName 表名；都不传则用光标所在表格。" +
          "返回的 cells 按行给出，行号从 1 开始、列号从 A 开始（左上角是 A1），" +
          "doc_table_set_cell 的 cell 参数就用这套坐标。表里有合并/拆分单元格时返回 note 说明。")
    public String doc_table_read(
            @P("表格序号（0 开始），不传则用光标所在表格") Integer tableIndex,
            @P("表名（如 Table1），一般不用传") String tableName,
            @P("最多读多少行，默认 200") Integer maxRows,
            @P("最多读多少列，默认 30") Integer maxCols
    ) {
        log.info("Tool: doc_table_read called tableIndex={}, tableName={}", tableIndex, tableName);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (tableIndex != null) params.put("tableIndex", tableIndex);
            if (tableName != null && !tableName.isEmpty()) params.put("tableName", tableName);
            if (maxRows != null) params.put("maxRows", maxRows);
            if (maxCols != null) params.put("maxCols", maxCols);
            return editorBridgeService.executeEditorCommand("table_read", params);
        } catch (Exception e) {
            log.error("Failed to read table", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "修改单元格", category = "document", fileEffect = "MODIFIED")
    @Tool("【改/表格】改表格里一个单元格的文本（整格替换）。先用 doc_table_read 看清表格坐标再改。" +
          "cell 用 '列字母+行号'，如 B2 = 第 2 列第 2 行；text 是这一格的新内容（不带公式、不含换行）。" +
          "修订模式下只有真正变动的字符会落成修订，不是整格删了重打。" +
          "定位：tableIndex 第几张表（0 开始），不传则用光标所在表格。")
    public String doc_table_set_cell(
            @P("单元格坐标，如 B2（列字母 + 行号，行号 1 开始）") String cell,
            @P("该单元格的新文本") String text,
            @P("表格序号（0 开始），不传则用光标所在表格") Integer tableIndex,
            @P("表名，一般不用传") String tableName
    ) {
        log.info("Tool: doc_table_set_cell called cell={}, tableIndex={}", cell, tableIndex);
        if (cell == null || cell.isBlank()) {
            return "Error: 缺少 cell 参数（单元格坐标，如 B2）";
        }
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("cell", cell);
            params.put("text", text == null ? "" : text);
            if (tableIndex != null) params.put("tableIndex", tableIndex);
            if (tableName != null && !tableName.isEmpty()) params.put("tableName", tableName);
            return editorBridgeService.executeEditorCommand("table_set_cell", params);
        } catch (Exception e) {
            log.error("Failed to set table cell", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "插入表格行", category = "document", fileEffect = "MODIFIED")
    @Tool("【改/表格】给表格插入空白行。position 是行号（1 开始），新行插在该行之前；" +
          "不传 position 则追加到表尾。count 一次插几行（默认 1）。插完用 doc_table_set_cell 逐格填内容。" +
          "定位：tableIndex 第几张表（0 开始），不传则用光标所在表格。")
    public String doc_table_add_row(
            @P("插入位置行号（1 开始，新行插在该行之前），不传则追加到表尾") Integer position,
            @P("插入几行，默认 1") Integer count,
            @P("表格序号（0 开始），不传则用光标所在表格") Integer tableIndex,
            @P("表名，一般不用传") String tableName
    ) {
        log.info("Tool: doc_table_add_row called position={}, count={}, tableIndex={}", position, count, tableIndex);
        return dispatchTableStructureCommand("table_add_row", position, count, tableIndex, tableName);
    }

    @ToolMeta(displayName = "删除表格行", category = "document", fileEffect = "MODIFIED")
    @Tool("【改/表格】删除表格的整行。position 是要删的行号（1 开始，必填），count 连删几行（默认 1）。" +
          "注意：删行是**直接删除、不留修订痕迹**（不像改文字那样能在修订里看到），删错只能靠撤销，" +
          "所以删之前务必先用 doc_table_read 看清要删的是哪一行。表格至少要留一行，删不掉全部行。" +
          "定位：tableIndex 第几张表（0 开始），不传则用光标所在表格。")
    public String doc_table_delete_row(
            @P("要删的行号（1 开始）") Integer position,
            @P("连删几行，默认 1") Integer count,
            @P("表格序号（0 开始），不传则用光标所在表格") Integer tableIndex,
            @P("表名，一般不用传") String tableName
    ) {
        log.info("Tool: doc_table_delete_row called position={}, count={}, tableIndex={}", position, count, tableIndex);
        if (position == null) {
            return "Error: 缺少 position 参数（要删的行号，1 开始）";
        }
        return dispatchTableStructureCommand("table_delete_row", position, count, tableIndex, tableName);
    }

    @ToolMeta(displayName = "插入表格列", category = "document", fileEffect = "MODIFIED")
    @Tool("【改/表格】给表格插入空白列。position 是列字母（如 B）或列号（1 开始），新列插在该列之前；" +
          "不传 position 则追加到最右。count 一次插几列（默认 1）。" +
          "合并过单元格的表格按列插入可能被引擎拒绝，失败会明确报出来。" +
          "定位：tableIndex 第几张表（0 开始），不传则用光标所在表格。")
    public String doc_table_add_col(
            @P("插入位置列字母（如 B）或列号（1 开始），新列插在该列之前；不传则追加到最右") String position,
            @P("插入几列，默认 1") Integer count,
            @P("表格序号（0 开始），不传则用光标所在表格") Integer tableIndex,
            @P("表名，一般不用传") String tableName
    ) {
        log.info("Tool: doc_table_add_col called position={}, count={}, tableIndex={}", position, count, tableIndex);
        return dispatchTableStructureCommand("table_add_col", position, count, tableIndex, tableName);
    }

    @ToolMeta(displayName = "删除表格列", category = "document", fileEffect = "MODIFIED")
    @Tool("【改/表格】删除表格的整列。position 是列字母（如 B）或列号（1 开始，必填），count 连删几列（默认 1）。" +
          "与删行一样是**直接删除、不留修订痕迹**，删前先用 doc_table_read 看清。表格至少要留一列。" +
          "定位：tableIndex 第几张表（0 开始），不传则用光标所在表格。")
    public String doc_table_delete_col(
            @P("要删的列字母（如 B）或列号（1 开始）") String position,
            @P("连删几列，默认 1") Integer count,
            @P("表格序号（0 开始），不传则用光标所在表格") Integer tableIndex,
            @P("表名，一般不用传") String tableName
    ) {
        log.info("Tool: doc_table_delete_col called position={}, count={}, tableIndex={}", position, count, tableIndex);
        if (position == null || position.isBlank()) {
            return "Error: 缺少 position 参数（要删的列字母如 B，或 1 开始的列号）";
        }
        return dispatchTableStructureCommand("table_delete_col", position, count, tableIndex, tableName);
    }

    /**
     * 表格行/列增删四个原语的共同下发路径（参数形状一致：position/count + 表格定位）。
     * position 对行是 Integer、对列是列字母或列号字符串，一律原样透传给 worker 解析。
     */
    private String dispatchTableStructureCommand(String action, Object position, Integer count,
                                                 Integer tableIndex, String tableName) {
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (position != null && !String.valueOf(position).isBlank()) params.put("position", position);
            if (count != null) params.put("count", count);
            if (tableIndex != null) params.put("tableIndex", tableIndex);
            if (tableName != null && !tableName.isEmpty()) params.put("tableName", tableName);
            return editorBridgeService.executeEditorCommand(action, params);
        } catch (Exception e) {
            log.error("Failed to execute table structure command: {}", action, e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【看/格式】读取当前光标或选区处的完整格式信息：字体（中西文）、字号、加粗/斜体/下划线/删除线、" +
          "颜色、高亮、段落样式、对齐、行距、段前段后、缩进、编号状态、所在表格（表名/行列数/单元格）。" +
          "改格式前先用本工具看清现状。")
    public String doc_get_formatting() {
        log.info("Tool: doc_get_formatting called");
        try {
            return editorBridgeService.executeEditorCommand("get_formatting", null);
        } catch (Exception e) {
            log.error("Failed to get formatting", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "应用标准格式", category = "document", fileEffect = "MODIFIED")
    @Tool("【格式】对整篇文档应用律所标准格式：正文楷体_GB2312/西文 Arial 12 号黑色、两端对齐、段前 0 段后 18 磅、" +
          "行距最小值 16 磅、首行缩进 2 字符；首段短文本视为主标题（16 号加粗居中）；标题段整段加粗；" +
          "表格套 Grid 1.5 磅边框、10 号字、首行加粗居中、数字居右；表格后首段段前 18 磅。" +
          "用户要求'规范格式/按标准排版'时用本工具，正文中既有的加粗强调不会被抹掉。")
    public String doc_apply_standard_format() {
        log.info("Tool: doc_apply_standard_format called");
        try {
            return editorBridgeService.executeEditorCommand("apply_house_style", null);
        } catch (Exception e) {
            log.error("Failed to apply standard format", e);
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

    @ToolMeta(displayName = "添加批注", category = "document", fileEffect = "MODIFIED")
    @Tool("【批注】在指定锚点处的文本上添加 Word 批注（comment）。修订文档时，解释、说明、修改理由等不属于正文的内容" +
          "**必须**用本工具以批注呈现，禁止写入正文。先 doc_find_text 拿到目标文本的 anchorId，再对它加批注；" +
          "批注署名 AI Workdeck，附着在目标文本上，保存为 docx 后可在 Word 中查看。")
    public String doc_add_comment(
            @P("doc_find_text 返回的 anchorId（批注附着的目标文本）") String anchorId,
            @P("批注内容（解释/说明/修改理由）") String comment
    ) {
        log.info("Tool: doc_add_comment called anchor={}, comment length={}", anchorId, comment != null ? comment.length() : 0);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("anchor", anchorId != null ? anchorId : "");
            params.put("comment", comment != null ? comment : "");
            return editorBridgeService.executeEditorCommand("add_comment", params);
        } catch (Exception e) {
            log.error("Failed to add comment", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 电子表格（Calc / xlsx）sheet_* 原语 ====================
    // 打开的 xlsx 由同一 LibreOffice 引擎的 Calc 模块承载；doc_* 的 Writer 原语
    // （getText 一族）在表格文档上必然失败，表格操作一律走本节 sheet_* 工具。
    // Calc 没有修订（redline）机制，写入即生效；纠错用 doc_undo，
    // 首次修改前的文档检查点（fileEffect=MODIFIED）仍是最后防线。

    @Tool("【表格·看】查看当前打开的电子表格（xlsx）的工作表结构：每张工作表的名称、序号、已用区域和行列数。" +
          "打开 xlsx 后先用本工具了解结构，再决定读哪个区域。Word 文档请用 doc_* 工具，本工具仅对表格文档有效。")
    public String sheet_get_overview() {
        log.info("Tool: sheet_get_overview called");
        try {
            return editorBridgeService.executeEditorCommand("sheet_get_overview", null);
        } catch (Exception e) {
            log.error("Failed to get sheet overview", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【表格·看】读取电子表格指定区域的单元格内容。返回二维数组 rows（文本为字符串、数值/公式结果为数字，日期是序列数）" +
          "和公式清单 formulas。range 不传则读整个已用区域；区域过大会截断并提示分块读取。")
    public String sheet_read_range(
            @P("区域，如 'A1:D20' 或单个单元格 'B3'；不传则读整个已用区域") String range,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_read_range called range={}, sheet={}", range, sheet);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (range != null && !range.isBlank()) params.put("range", range);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_read_range", params);
        } catch (Exception e) {
            log.error("Failed to read sheet range", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "写入单元格", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·写】从起始单元格开始按二维数组批量写入。rowsJson 是 JSON 二维数组，如 " +
          "[[\"项目\",\"金额\"],[\"咨询费\",10000],[\"合计\",\"=SUM(B2:B2)\"]]：数字与数字样式字符串写为数值，" +
          "'=' 开头写为公式，其余写为文本；null 跳过不动该格。写入即生效（无修订痕迹），返回实际写入区域和首行回读值供核对。" +
          "公式用英文函数名，按 Excel 习惯写即可（逗号分隔、跨表 Sheet!A1 会自动归一为引擎方言）；" +
          "SUM/AVERAGE/IF/COUNT/VLOOKUP/SUMIF/COUNTIF/INDEX+MATCH/IFERROR/TEXT/日期函数等均可用，" +
          "但引擎是 LibreOffice 24.2，**不支持 XLOOKUP 等新函数**（用 VLOOKUP 或 INDEX+MATCH 代替）。" +
          "任何公式出错都会在返回值 formulaErrors 里列出（单元格/公式/错误码），看到后必须修正并重写该格。")
    public String sheet_write_cells(
            @P("起始单元格，如 'A1'") String startCell,
            @P("写入内容，JSON 二维数组") String rowsJson,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_write_cells called startCell={}, json length={}", startCell, rowsJson != null ? rowsJson.length() : 0);
        if (startCell == null || startCell.isBlank()) {
            return "Error: 缺少 startCell 参数（如 'A1'）";
        }
        if (rowsJson == null || rowsJson.isBlank()) {
            return "Error: 缺少 rowsJson 参数（JSON 二维数组）";
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<java.util.List<Object>> rows = mapper.readValue(
                    rowsJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.List<Object>>>() {});
            if (rows.isEmpty()) {
                return "Error: rowsJson 不能为空";
            }
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("startCell", startCell);
            params.put("rows", rows);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_write_cells", params);
        } catch (com.fasterxml.jackson.core.JacksonException je) {
            return "Error: rowsJson 不是合法的 JSON 二维数组: " + je.getOriginalMessage();
        } catch (Exception e) {
            log.error("Failed to write sheet cells", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【表格·选】选中电子表格的一个区域（视图滚动到该处并高亮，用户能看到 AI 正在操作哪里）。" +
          "选中后可接 sheet_format_cells / sheet_set_borders 等格式操作。")
    public String sheet_select_range(
            @P("区域，如 'A1:D20' 或单个单元格 'B3'") String range,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_select_range called range={}, sheet={}", range, sheet);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("range", range != null ? range : "");
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_select_range", params);
        } catch (Exception e) {
            log.error("Failed to select sheet range", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "设置单元格格式", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·格式】设置电子表格区域的格式，只传需要改的参数：加粗/斜体/下划线、字号、字体、字色、底色、" +
          "水平对齐 hAlign(left/center/right/standard)、垂直对齐 vAlign(top/center/bottom/standard)、自动换行 wrap、" +
          "数字格式 numberFormat（LibreOffice 格式码，如 '#,##0.00'、'0.00%'、'yyyy-mm-dd'、'@'=文本）。")
    public String sheet_format_cells(
            @P("区域，如 'A1:D1'") String range,
            @P("加粗 true/false，不改则不传") Boolean bold,
            @P("斜体 true/false，不改则不传") Boolean italic,
            @P("下划线 true/false，不改则不传") Boolean underline,
            @P("字号（磅），不改则不传") Double fontSize,
            @P("字体名，不改则不传") String fontName,
            @P("文字颜色：#RRGGBB 或 auto，不改则不传") String color,
            @P("单元格底色：#RRGGBB 或 none，不改则不传") String background,
            @P("水平对齐：left/center/right/standard，不改则不传") String hAlign,
            @P("垂直对齐：top/center/bottom/standard，不改则不传") String vAlign,
            @P("自动换行 true/false，不改则不传") Boolean wrap,
            @P("数字格式码，如 '#,##0.00'、'0.00%'、'yyyy-mm-dd'，不改则不传") String numberFormat,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_format_cells called range={}", range);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("range", range != null ? range : "");
            if (bold != null) params.put("bold", bold);
            if (italic != null) params.put("italic", italic);
            if (underline != null) params.put("underline", underline);
            if (fontSize != null) params.put("fontSize", fontSize);
            if (fontName != null && !fontName.isBlank()) params.put("fontName", fontName);
            if (color != null && !color.isBlank()) params.put("color", color);
            if (background != null && !background.isBlank()) params.put("background", background);
            if (hAlign != null && !hAlign.isBlank()) params.put("hAlign", hAlign);
            if (vAlign != null && !vAlign.isBlank()) params.put("vAlign", vAlign);
            if (wrap != null) params.put("wrap", wrap);
            if (numberFormat != null && !numberFormat.isBlank()) params.put("numberFormat", numberFormat);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_format_cells", params);
        } catch (Exception e) {
            log.error("Failed to format sheet cells", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "设置单元格边框", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·格式】给电子表格区域设置边框。preset: all=内外全部框线 / outer=仅外框（内线清除）/ none=清除全部。" +
          "widthPt 线宽磅数（默认 0.75），color 边框颜色（默认黑色）。")
    public String sheet_set_borders(
            @P("区域，如 'A1:D20'") String range,
            @P("边框样式：all/outer/none，默认 all") String preset,
            @P("线宽（磅，如 0.75、1.5），默认 0.75") Double widthPt,
            @P("边框颜色 #RRGGBB，默认黑色") String color,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_set_borders called range={}, preset={}", range, preset);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("range", range != null ? range : "");
            if (preset != null && !preset.isBlank()) params.put("preset", preset);
            if (widthPt != null) params.put("widthPt", widthPt);
            if (color != null && !color.isBlank()) params.put("color", color);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_set_borders", params);
        } catch (Exception e) {
            log.error("Failed to set sheet borders", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "设置行高列宽", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·格式】设置电子表格的行高列宽，作用于 range 覆盖到的整行/整列。" +
          "rowHeightPt/colWidthPt 按磅设定值；autoFitRows/autoFitCols=true 按内容自动适应（优先于定值）。只传需要改的参数。")
    public String sheet_set_row_col(
            @P("区域，如 'A1:D1'（其覆盖的整行/整列生效）") String range,
            @P("行高（磅），不改则不传") Double rowHeightPt,
            @P("列宽（磅），不改则不传") Double colWidthPt,
            @P("行高自动适应内容 true，不改则不传") Boolean autoFitRows,
            @P("列宽自动适应内容 true，不改则不传") Boolean autoFitCols,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_set_row_col called range={}", range);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("range", range != null ? range : "");
            if (rowHeightPt != null) params.put("rowHeightPt", rowHeightPt);
            if (colWidthPt != null) params.put("colWidthPt", colWidthPt);
            if (autoFitRows != null) params.put("autoFitRows", autoFitRows);
            if (autoFitCols != null) params.put("autoFitCols", autoFitCols);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_set_row_col", params);
        } catch (Exception e) {
            log.error("Failed to set sheet row/col size", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "新建表格文件", category = "document", fileEffect = "ADDED")
    @Tool("【表格·建】在项目中新建一个空白 Excel 表格文件（.xlsx）并在编辑器中打开。" +
          "创建后用 sheet_write_cells 写入数据、sheet_format_cells 等做格式。重名会自动加序号。")
    public String sheet_create_file(
            @P("文件名，如 '费用明细表.xlsx'（.xlsx 后缀可省略）") String fileName,
            @P("项目ID") Long projectId
    ) {
        log.info("Tool: sheet_create_file called fileName={}, projectId={}", fileName, projectId);
        if (fileName == null || fileName.isBlank()) {
            return "Error: 缺少 fileName 参数";
        }
        if (projectId == null) {
            return "Error: 缺少 projectId 参数";
        }
        try {
            if (!fileName.toLowerCase().endsWith(".xlsx")) {
                fileName = fileName + ".xlsx";
            }
            java.nio.file.Path projectDataDir = storageResolver.projectRoot(projectId);
            if (!java.nio.file.Files.exists(projectDataDir)) {
                java.nio.file.Files.createDirectories(projectDataDir);
            }
            // 重名自动加序号（与 doc_start_stream 的新建 docx 同一策略）
            String baseName = fileName.substring(0, fileName.length() - 5);
            java.nio.file.Path targetPath = projectDataDir.resolve(fileName);
            int counter = 1;
            while (java.nio.file.Files.exists(targetPath)) {
                fileName = baseName + " (" + counter + ").xlsx";
                targetPath = projectDataDir.resolve(fileName);
                counter++;
            }

            // POI 生成最小空白工作簿（引擎按 .xlsx 扩展名自动选 Calc 过滤器加载）
            try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                 java.io.OutputStream os = java.nio.file.Files.newOutputStream(targetPath)) {
                wb.createSheet("Sheet1");
                wb.write(os);
            }

            String wpsId = "sheet_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
            String storageRelativePath = "projects/" + projectId + "/" + fileName;
            ProjectFile file = projectFileService.createOrUpdateFile(
                    projectId, null, fileName, "xlsx", targetPath.toFile().length(),
                    storageRelativePath, wpsId, AGENT_USER_ID
            );
            log.info("Created new xlsx file: id={}, name={}", file.getId(), file.getName());

            editorBridgeService.sendRefreshFilesAction();
            editorBridgeService.sendOpenFileAction(file);
            return String.format("已创建空白表格文件并发送打开指令。文件ID: %d, 文件名: %s。" +
                    "请等待编辑器加载完成后，用 sheet_write_cells 写入内容。", file.getId(), file.getName());
        } catch (Exception e) {
            log.error("Failed to create xlsx file", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "管理工作表", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·结构】管理工作表：op=add 新建（name+可选 position）、rename 重命名（name+newName）、" +
          "delete 删除（name，不能删最后一张）、move 移动（name+position，0 开始）。返回操作后的工作表清单。")
    public String sheet_manage_sheets(
            @P("操作：add/rename/delete/move") String op,
            @P("工作表名（add 时为新表名）") String name,
            @P("新名称，仅 rename 需要") String newName,
            @P("目标位置（0 开始），add/move 用；add 不传则加在最后") Integer position
    ) {
        log.info("Tool: sheet_manage_sheets called op={}, name={}", op, name);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("op", op != null ? op : "");
            if (name != null && !name.isBlank()) params.put("name", name);
            if (newName != null && !newName.isBlank()) params.put("newName", newName);
            if (position != null) params.put("position", position);
            return editorBridgeService.executeEditorCommand("sheet_manage_sheets", params);
        } catch (Exception e) {
            log.error("Failed to manage sheets", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "插入删除行列", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·结构】插入或删除整行/整列。op: insert_rows/delete_rows/insert_cols/delete_cols；" +
          "start 是 1 开始的行号（如 '3'）或列标（如 'B'）；count 默认 1。插入的新行/列占据 start 的位置（原内容后移）。")
    public String sheet_edit_rows_cols(
            @P("操作：insert_rows/delete_rows/insert_cols/delete_cols") String op,
            @P("起始位置：行号（1 开始，如 '3'）或列标（如 'B'）") String start,
            @P("行/列数，默认 1") Integer count,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_edit_rows_cols called op={}, start={}, count={}", op, start, count);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("op", op != null ? op : "");
            params.put("start", start != null ? start : "");
            if (count != null) params.put("count", count);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_edit_rows_cols", params);
        } catch (Exception e) {
            log.error("Failed to edit rows/cols", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "合并单元格", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·结构】合并或取消合并单元格区域。merge=true 合并（内容以左上格为准），false 取消合并。")
    public String sheet_merge_cells(
            @P("区域，如 'A1:C1'") String range,
            @P("true=合并（默认），false=取消合并") Boolean merge,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_merge_cells called range={}, merge={}", range, merge);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("range", range != null ? range : "");
            if (merge != null) params.put("merge", merge);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_merge_cells", params);
        } catch (Exception e) {
            log.error("Failed to merge cells", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "区域排序", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·结构】对区域按某列排序。byColumn 是列标（如 'B'，必须在区域内，不传用区域第一列）；" +
          "ascending 默认 true 升序；hasHeader 默认 true（首行是表头不参与排序）。返回排序后该列的前几个值供核对。" +
          "注意：值与公式随行整体移动，但单元格格式（底色等）不随行移动——先排序后做格式。")
    public String sheet_sort_range(
            @P("区域，如 'A1:D10'") String range,
            @P("排序依据列的列标（如 'B'），不传用区域第一列") String byColumn,
            @P("升序 true（默认）/降序 false") Boolean ascending,
            @P("首行是表头 true（默认）/false") Boolean hasHeader,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_sort_range called range={}, byColumn={}, asc={}", range, byColumn, ascending);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("range", range != null ? range : "");
            if (byColumn != null && !byColumn.isBlank()) params.put("byColumn", byColumn);
            if (ascending != null) params.put("ascending", ascending);
            if (hasHeader != null) params.put("hasHeader", hasHeader);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_sort_range", params);
        } catch (Exception e) {
            log.error("Failed to sort range", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "设置自动筛选", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·结构】给区域加/去自动筛选（表头出现筛选下拉按钮）。enabled 默认 true；range 不传用整个已用区域。")
    public String sheet_set_autofilter(
            @P("区域，如 'A1:D10'；不传用整个已用区域") String range,
            @P("true=开启（默认），false=关闭") Boolean enabled,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_set_autofilter called range={}, enabled={}", range, enabled);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (range != null && !range.isBlank()) params.put("range", range);
            if (enabled != null) params.put("enabled", enabled);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_set_autofilter", params);
        } catch (Exception e) {
            log.error("Failed to set autofilter", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "冻结窗格", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·结构】冻结窗格：冻结前 rows 行和前 cols 列（滚动时保持可见，常用 rows=1 冻结表头）；rows=0 且 cols=0 取消冻结。")
    public String sheet_freeze_panes(
            @P("冻结前几行（0=不冻结行）") Integer rows,
            @P("冻结前几列（0=不冻结列）") Integer cols,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_freeze_panes called rows={}, cols={}", rows, cols);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("rows", rows != null ? rows : 0);
            params.put("cols", cols != null ? cols : 0);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_freeze_panes", params);
        } catch (Exception e) {
            log.error("Failed to freeze panes", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "设置条件格式", category = "document", fileEffect = "MODIFIED")
    @Tool("【表格·格式】给区域设条件格式：满足条件的单元格自动套指定外观。" +
          "rule: greater/greaterEqual/less/lessEqual/equal/notEqual/between/notBetween/formula；" +
          "value1 是比较值或公式（between 再配 value2）；外观至少传一项：background 底色/color 字色/bold 加粗。" +
          "每次调用替换该区域已有的条件格式；clear=true 清除区域全部条件格式。")
    public String sheet_conditional_format(
            @P("区域，如 'B2:B10'") String range,
            @P("规则：greater/greaterEqual/less/lessEqual/equal/notEqual/between/notBetween/formula") String rule,
            @P("比较值 1（数值或公式，如 '5000'）") String value1,
            @P("比较值 2，仅 between/notBetween 需要") String value2,
            @P("命中时底色 #RRGGBB，不设则不传") String background,
            @P("命中时字色 #RRGGBB，不设则不传") String color,
            @P("命中时加粗 true，不设则不传") Boolean bold,
            @P("true=清除该区域全部条件格式（忽略其他参数）") Boolean clear,
            @P("工作表名称或序号（0 开始）；不传用当前活动工作表") String sheet
    ) {
        log.info("Tool: sheet_conditional_format called range={}, rule={}", range, rule);
        try {
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("range", range != null ? range : "");
            if (rule != null && !rule.isBlank()) params.put("rule", rule);
            if (value1 != null && !value1.isBlank()) params.put("value1", value1);
            if (value2 != null && !value2.isBlank()) params.put("value2", value2);
            if (background != null && !background.isBlank()) params.put("background", background);
            if (color != null && !color.isBlank()) params.put("color", color);
            if (bold != null) params.put("bold", bold);
            if (clear != null) params.put("clear", clear);
            if (sheet != null && !sheet.isBlank()) params.put("sheet", sheet);
            return editorBridgeService.executeEditorCommand("sheet_conditional_format", params);
        } catch (Exception e) {
            log.error("Failed to set conditional format", e);
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

