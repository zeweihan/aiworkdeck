package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.vladsch.flexmark.docx.converter.DocxRenderer;
import com.vladsch.flexmark.parser.Parser;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * File Tools for the Agent.
 * Includes:
 * 1. Search Files (Global or Scoped)
 * 2. Read Files
 * 3. Write Files (Text) - Registers to DB for the editor
 * 4. Write Docx (MD -> DOCX) - Registers to DB for the editor
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FileTools implements AgentToolComponent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileTools.class);

    private final ProjectFileService projectFileService;
    private final ProjectFileRepository projectFileRepository;
    private final com.checkba.service.ai.EditorBridgeService editorBridgeService;
    private final com.checkba.service.ai.context.FileContentExtractorService fileContentExtractorService;
    private final com.checkba.storage.ProjectStorageResolver storageResolver;
    private final com.checkba.service.DocumentTextService documentTextService;
    private final com.checkba.service.ai.AiDocxExportService aiDocxExportService;
    private static final Long AGENT_USER_ID = 10001L;

    /**
     * 路径类工具的唯一围栏基准：当前会话所属项目的物理目录。
     *
     * 不能用服务端安装根（user.dir）：所有租户的 data/projects/{id} 与 skills/、plugins/
     * 扫描目录都并排在它下面——前者意味着跨租户读写他人卷宗，后者写进去的文本会在下次
     * 扫描后进入所有用户的 SYSTEM 提示词，是持久化的跨租户污染。
     * projectId 取自 ToolRegistry 强制注入的服务端上下文，LLM 伪造不了；
     * 没有项目上下文时一律拒绝（fail closed），口径与 ToolFileGuard 一致。
     */
    private Path currentProjectRoot() {
        Long projectId = com.checkba.service.ai.context.ProjectContextHolder.getProjectIdAsLong();
        if (projectId == null) {
            throw new SecurityException("Access denied: no project context for this request.");
        }
        return storageResolver.projectRoot(projectId).normalize();
    }

    @ToolMeta(displayName = "搜索项目文件", category = "file")
    @Tool("Locate a file by NAME PATTERN. Returns paths only, NO database fileId — once you know the name, "
            + "get the fileId from doc_list_project_files (documents), pdf_list_files (PDF) or pptx_list_files (PPTX) "
            + "before any open/edit/rename/move. Can specify a sub-directory.")
    public String search_project_files(
            @P("Filename pattern (e.g. '*Controller.java' or 'User*.java')") String fileNamePattern,
            @P("Optional: Sub-directory to search in, relative to the project folder. Default is the project root.") String dirPath
    ) {
        log.info("Tool: search_project_files called pattern='{}', dir='{}'", fileNamePattern, dirPath);
        if (fileNamePattern == null || fileNamePattern.isBlank()) {
            return "Error: fileNamePattern is required.";
        }
        List<String> matches = new ArrayList<>();
        
        Path root = currentProjectRoot();
        Path startDir = root;
        if (StringUtils.hasText(dirPath)) {
            startDir = root.resolve(dirPath).normalize();
            if (!startDir.startsWith(root)) {
                return "Error: Access denied. Path escapes project directory.";
            }
            if (!Files.exists(startDir)) return "Error: Directory not found: " + dirPath;
        }

        final String glob = "glob:**" + (fileNamePattern.startsWith("*") ? "" : "/") + fileNamePattern;
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher(glob);

        try {
            Files.walkFileTree(startDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String pathStr = file.toAbsolutePath().toString();
                    if (pathStr.contains("/.git/") || pathStr.contains("/target/") || pathStr.contains("/node_modules/")) {
                        return FileVisitResult.CONTINUE;
                    }

                    if (matcher.matches(file) || file.getFileName().toString().contains(fileNamePattern.replace("*", ""))) {
                         // Return relative path for readability
                         matches.add(root.relativize(file).toString());
                    }
                    if (matches.size() >= 50) return FileVisitResult.TERMINATE; 
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".") || name.equals("target") || name.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                // 单个条目读不了就跳过，不许掀翻整次搜索。SimpleFileVisitor 的默认实现是
                // **把异常重新抛出**，于是一个没权限的目录、一条断掉的符号链接、或者遍历途中
                // 被删掉的文件，就能让整次搜索抛 IOException——已经找到的匹配全部丢弃，
                // 模型只拿到一句 "Error searching files"，然后认定这些文件不存在。
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("search_project_files: 跳过读不了的条目 {}: {}", file, exc.toString());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        log.debug("search_project_files: 目录 {} 未能完整遍历: {}", dir, exc.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            
            if (matches.isEmpty()) return "No files found matching '" + fileNamePattern + "' in " + (dirPath != null ? dirPath : "root");
            // 附上 DB fileId：让搜索结果可直接喂给 move_project_file / rename_project_file
            var index = dbPathIndex(com.checkba.service.ai.context.ProjectContextHolder.getProjectIdAsLong());
            return matches.stream()
                    .map(m -> {
                        ProjectFile pf = index.get(m.replace('\\', '/'));
                        return pf != null ? m + " (fileId=" + pf.getId() + ")" : m;
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));
            
        } catch (IOException e) {
            log.error("Error searching files", e);
            return "Error searching files: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "读取文件", category = "file")
    @Tool("Read the content of a file. Provide path (absolute or relative to project root).")
    public String read_file(String filePath) {
        log.info("Tool: read_file called for {}", filePath);
        try {
            Path path = resolvePath(filePath);
            if (!Files.exists(path)) return "Error: File does not exist.";
            if (Files.isDirectory(path)) return "Error: Path is a directory.";
            if (Files.size(path) > 10 * 1024 * 1024) return "Error: File too large (>10MB).";
            
            // Use unified extractor：图片/PDF 走 OCR，纯文本直读，其余（docx/xlsx/pptx 等
            // Office 格式）走 Tika——第三条以前不存在，Office 文件恒返回空串，
            // 而空串会被 ToolExecutionResultMessage 的 ensureNotBlank 抛出来掀翻整轮
            File file = path.toFile();
            String content;
            if (fileContentExtractorService.isOcrSupported(file.getName())) {
                content = fileContentExtractorService.extractTextWithOcr(file);
            } else if (fileContentExtractorService.isTextFile(file.getName())) {
                content = fileContentExtractorService.extractText(file);
            } else {
                try (java.io.InputStream is = Files.newInputStream(path)) {
                    content = documentTextService.parse(is);
                }
            }
            if (!StringUtils.hasText(content)) {
                return "Warning: no text extracted from '" + file.getName() + "' — the file may be a scanned "
                        + "image or empty; try extract_file_text with its database file ID.";
            }
            // 同 extract_file_text 的上限（单一来源见 ToolFileGuard）：超长单条工具结果
            // 会把整轮顶进上下文超限，而且落在 compactor 尾区剪不掉
            return ToolFileGuard.capToolText(file.getName(), content);
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "列出文件", category = "file")
    @Tool("PHYSICAL DISK view of the project folder: returns paths only, NO database fileId, so nothing here can be "
            + "fed to open/edit/rename/move tools. Use doc_list_project_files (documents), pdf_list_files (PDF) or "
            + "pptx_list_files (PPTX) whenever a later step needs a fileId. "
            + "Lists files and directories under data/projects/{projectId}/.")
    public String list_files(
            @P("Project ID - files will be listed from data/projects/{projectId}/") Long projectId,
            @P("Optional: Subdirectory path within the project folder. Use '.' or empty for project root.") String subPath
    ) {
        log.info("Tool: list_files called for projectId={}, subPath={}", projectId, subPath);
        try {
            // 限制在项目数据目录内（localRoot 感知）
            Path projectDataDir = storageResolver.projectRoot(projectId);
            if (!Files.exists(projectDataDir)) {
                return "Error: Project data directory not found: " + projectDataDir;
            }
            
            Path dir = projectDataDir;
            if (StringUtils.hasText(subPath) && !".".equals(subPath)) {
                dir = projectDataDir.resolve(subPath);
                // 安全检查：确保解析后的路径仍在项目目录内
                if (!dir.normalize().startsWith(projectDataDir.normalize())) {
                    return "Error: Access denied. Path escapes project directory.";
                }
            }
            
            if (!Files.exists(dir)) return "Error: Directory not found: " + subPath;
            if (!Files.isDirectory(dir)) return "Error: Path is not a directory: " + subPath;

            String displayPath = subPath == null || subPath.isEmpty() || ".".equals(subPath) ?
                    "project " + projectId + " root" : subPath;
            StringBuilder sb = new StringBuilder("Contents of " + displayPath + ":\n");

            // 物理条目 join DB 记录：所有文件类型（含 txt 等非文档）都直接拿到 fileId，
            // 供 move_project_file / rename_project_file 使用，不必再绕 doc/pdf 专用列表
            var index = dbPathIndex(projectId);
            String prefix = projectDataDir.relativize(dir.normalize()).toString().replace('\\', '/');

            // Stream and sort: Directories first, then files
            try (var stream = Files.list(dir)) {
                stream.filter(p -> !p.getFileName().toString().startsWith(".")) // ignore hidden, incl. .awd/
                        .sorted((p1, p2) -> {
                    boolean d1 = Files.isDirectory(p1);
                    boolean d2 = Files.isDirectory(p2);
                    if (d1 && !d2) return -1;
                    if (!d1 && d2) return 1;
                    return p1.getFileName().compareTo(p2.getFileName());
                }).forEach(path -> {
                    String type = Files.isDirectory(path) ? "[DIR] " : "[FILE]";
                    String name = path.getFileName().toString();
                    ProjectFile pf = index.get(prefix.isEmpty() ? name : prefix + "/" + name);
                    String idNote = pf == null
                            ? " (unregistered: run scan_files before moving/renaming)"
                            : (Files.isDirectory(path) ? " (folderId=" : " (fileId=") + pf.getId() + ")";
                    sb.append(type).append(" ").append(name).append(idNote).append("\n");
                });
            }

            sb.append("\nNote: fileId/folderId work with move_project_file, rename_project_file and create_folder. move_file accepts paths directly.");
            return sb.toString();

        } catch (IOException e) {
            log.error("Failed to list files", e);
            return "Error listing files: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "提取文档全文", category = "file")
    @Tool("Extract the full plain text of a project file (pdf/docx/xlsx/doc etc.) by its database file ID. Use this to read Word/Excel/PDF documents from the project file tree. Returns extracted text (may be truncated for very large files). If the ID is a FOLDER, returns a listing of its direct children (id + name + type) instead of an error, so you can then read each file in turn.")
    public String extract_file_text(
            @P("Project file database ID (from doc_list_project_files / material list). May also be a folder ID — you get its contents listed.") Long fileId
    ) {
        log.info("Tool: extract_file_text called for fileId={}", fileId);
        if (fileId == null) {
            return "Error: fileId is required.";
        }
        Optional<ProjectFile> fileOpt = projectFileRepository.findById(fileId);
        if (fileOpt.isEmpty()) {
            return "Error: File not found in database: " + fileId;
        }
        ProjectFile pf = fileOpt.get();
        String denied = ToolFileGuard.rejectIfOutsideProject(pf);
        if (denied != null) return denied;
        if ("folder".equalsIgnoreCase(pf.getFileType()) || Boolean.TRUE.equals(pf.getIsFolder())) {
            // 直接把文件夹内容答出来，而不是只说一句"这是个文件夹"。
            //
            // 原先返回的死错误让模型无路可走：用户在诉讼可视化里把一个卷宗文件夹当
            // 材料范围交进来，模型调到这里就卡住了，表现就是"给它文件夹它不认识"。
            // 指向别的工具也不成立——doc_list_project_files 只收 projectId、且只列
            // 「可编辑文档」，PDF 和图片全漏，答不了"这个文件夹里有什么"。
            // 模型问的是"这东西的内容"，回"这是文件夹，里面是这些"才是真答案，
            // 还省掉一轮往返。
            return describeFolder(pf);
        }
        try {
            String text = documentTextService.extractText(pf);
            if (text == null || text.isBlank()) {
                return "Warning: No text extracted from '" + pf.getName() + "'. The file may be a scanned image; try read_file with OCR for image PDFs.";
            }
            String capped = ToolFileGuard.capToolText(pf.getName(), text);
            // 未截断时保留原有的「[文件 X]」抬头（模型据此知道正文属于哪个文件）
            return capped.length() == text.length() ? "[文件 " + pf.getName() + "]\n" + text : capped;
        } catch (Exception e) {
            log.warn("extract_file_text failed for fileId={}", fileId, e);
            return "Error extracting text: " + e.getMessage();
        }
    }

    /**
     * 文件夹的「内容」= 它下面有什么。列直接子项，子文件夹标出来，让模型能自己往下走。
     *
     * <p>只列一层：卷宗嵌套通常不深，而递归展开一个大文件夹会把上下文吃光。
     * 子文件夹带着 id 返回，模型想深入就再调一次。
     */
    private String describeFolder(ProjectFile folder) {
        List<ProjectFile> children = projectFileRepository
                .findByProjectIdAndParentIdAndIsDeletedFalseOrderBySortOrderAsc(
                        folder.getProjectId(), folder.getId());
        if (children.isEmpty()) {
            return "[文件夹 " + folder.getName() + "（id=" + folder.getId() + "）] 是空的，里面没有文件。";
        }
        final int maxItems = 200;
        StringBuilder sb = new StringBuilder();
        sb.append("[文件夹 ").append(folder.getName()).append("（id=").append(folder.getId())
                .append("）] 这是一个文件夹，不是文件。它直接包含 ").append(children.size()).append(" 项")
                .append("；对其中的每个文件调用 extract_file_text 读正文，子文件夹可再次对其 id 调用本工具。\n");
        int shown = 0;
        for (ProjectFile c : children) {
            if (shown >= maxItems) {
                sb.append("- …（还有 ").append(children.size() - shown).append(" 项未列出）\n");
                break;
            }
            boolean isDir = "folder".equalsIgnoreCase(c.getFileType()) || Boolean.TRUE.equals(c.getIsFolder());
            sb.append("- ").append(isDir ? "[文件夹] " : "").append("id=").append(c.getId())
                    .append("，名称：").append(c.getName());
            if (!isDir && c.getFileType() != null) sb.append("，类型：").append(c.getFileType());
            sb.append('\n');
            shown++;
        }
        return sb.toString();
    }

    @ToolMeta(displayName = "写入文件", category = "file", fileEffect = "ADDED", fileArg = "fileName", refreshFiles = true)
    @Tool("Write content to a text file at the project root and register it in the project database so it "
            + "appears in the file tree and can be opened in the editor. Returns the db_id. "
            + "For a file inside a subfolder, write it and then call scan_files to register it.")
    public String write_file(
            @P("Target filename at the project root (e.g. 'notes.txt')") String fileName, 
            @P("File content") String content,
            @P("Project ID (Required for DB registration)") Long projectId
    ) {
        log.info("Tool: write_file called for {}", fileName);
        if (fileName == null || fileName.isBlank()) {
            return "Error: fileName is required.";
        }
        try {
             Path path = resolvePath(fileName);
             if (!Files.exists(path.getParent())) {
                 Files.createDirectories(path.getParent());
             }
             
             Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

             // 落库。此前这里只有一段「Register in DB so Agent "owns" it」的注释，
             // 底下一行代码都没有——而工具描述与参数说明都白纸黑字写着会注册。
             // 后果：文件躺在项目目录里但没有 project_file 行，文件树看不见、编辑器打不开、
             // 后续工具也拿不到 fileId，模型却已经向用户报告「文件已创建」。
             // 注册方式与 write_docx 完全一致（createOrUpdateFile 幂等：同名已存在就更新）。
             if (projectId == null) {
                 return "File written to " + path.toAbsolutePath()
                         + " but NOT registered in the project (no projectId): it will not appear in the file "
                         + "tree. Call scan_files to register it.";
             }
             // 带子目录的名字不在这里登记：parentId 只能填 null，会让文件树把它显示在根目录，
             // 与它实际所在的子文件夹对不上。如实告知并指向 scan_files（那条路会按目录结构登记）。
             String normalizedName = fileName.replace('\\', '/');
             if (normalizedName.contains("/")) {
                 editorBridgeService.sendRefreshFilesAction();
                 return "File written to " + path.toAbsolutePath()
                         + ". It is in a subfolder, so it was NOT registered here — call scan_files("
                         + projectId + ") to register it into the file tree.";
             }
             try {
                 ProjectFile pf = projectFileService.createOrUpdateFile(
                         projectId, null, fileName, getFileType(fileName), Files.size(path),
                         "projects/" + projectId + "/" + fileName, null, AGENT_USER_ID);
                 editorBridgeService.sendRefreshFilesAction();
                 return String.format("{\"status\":\"success\", \"db_id\":%d, \"file_path\":\"%s\"}",
                         pf.getId(), path.toAbsolutePath().toString().replace("\\", "\\\\"));
             } catch (Exception e) {
                 log.warn("write_file DB register failed for {}", fileName, e);
                 return "File written to " + path.toAbsolutePath()
                         + " but DB registration failed (it will not appear in the file tree): "
                         + e.getMessage() + " — call scan_files to retry registration.";
             }
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "生成Word文档", category = "file", fileEffect = "ADDED", fileArg = "fileName", refreshFiles = true)
    @Tool("【STRICTLY NEW FILES ONLY】Create a NEW .docx from Markdown. FORBIDDEN for 'revise', 'update', or 'modify' tasks. If a similar file exists, you MUST use doc_open_file to edit it. DO NOT create 'Revised_Version.docx'.")
    public String write_docx(
            @P("新文件名 (如 '报告.docx')") String fileName,
            @P("Markdown 内容") String markdownContent,
            @P("项目ID") Long projectId,
            @P(value = "目标文件夹ID（可选，不填则放项目根目录）", required = false) Long parentFolderId
    ) {
        if (parentFolderId != null) {
            // 指定目标文件夹时走 AiDocxExportService（正确的路径构建 + StorageService 落盘 + RAG 刷新）
            log.info("Tool: write_docx (folder={}) called for {}", parentFolderId, fileName);
            if (fileName == null || fileName.isBlank()) return "Error: fileName is required.";
            if (!fileName.endsWith(".docx")) fileName += ".docx";
            if (fileName.matches(".*(revise|revision|update|modify|change|修改|修订|更新|变动).*")) {
                return "Error: Creation of files with 'revise/update/modify' in the name is FORBIDDEN. Use doc_open_file to edit the original instead.";
            }
            try {
                ProjectFile pf = aiDocxExportService.exportMarkdownToDocx(
                        projectId, parentFolderId, AGENT_USER_ID, fileName, markdownContent);
                editorBridgeService.sendRefreshFilesAction();
                return String.format("{\"status\":\"success\", \"db_id\":%d, \"file_path\":\"%s\"}",
                        pf.getId(), String.valueOf(pf.getFilePath()).replace("\\", "\\\\"));
            } catch (Exception e) {
                log.error("write_docx to folder failed", e);
                return "Error creating DOCX in folder " + parentFolderId + ": " + e.getMessage();
            }
        }
        return writeDocxAtRoot(fileName, markdownContent, projectId);
    }

    private String writeDocxAtRoot(String fileName, String markdownContent, Long projectId) {
        log.info("Tool: write_docx called for {}", fileName);
        if (fileName == null || fileName.isBlank()) {
            return "Error: fileName is required.";
        }
        if (!fileName.endsWith(".docx")) fileName += ".docx";
        
        // Block suspicious filenames that suggest revision
        if (fileName.matches(".*(revise|revision|update|modify|change|修改|修订|更新|变动).*")) {
            return "Error: Creation of files with 'revise/update/modify' in the name is FORBIDDEN. You MUST use 'doc_open_file' to open the original file and use editing tools (doc_find_replace, doc_modify_paragraph, etc.) to apply changes. DO NOT create a new file.";
        }
        
        try {
            Path projectDataDir = storageResolver.projectRoot(projectId).normalize();
            if (!Files.exists(projectDataDir)) Files.createDirectories(projectDataDir);
            Path targetPath = projectDataDir.resolve(fileName).normalize();
            // fileName 由 LLM 自由填写，"../42/协议.docx" 会把伪造文书落进别的租户目录
            if (!targetPath.startsWith(projectDataDir)) {
                return "Error: Access denied. Path escapes project directory.";
            }

            if (Files.exists(targetPath)) {
                return "Error: File '" + fileName + "' already exists. Please use 'doc_open_file' and editing tools to modify the existing document instead of overwriting it.";
            }
            
            Parser parser = Parser.builder().build();
            com.vladsch.flexmark.util.ast.Node document = parser.parse(markdownContent);
            
            // Flexmark docx-converter usage pattern:
            File file = targetPath.toFile();
            DocxRenderer renderer = DocxRenderer.builder().build();
            
            // Create Package -> Add missing styles -> Render -> Save
            org.docx4j.openpackaging.packages.WordprocessingMLPackage wordDoc = org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
            com.checkba.util.DocxStyleHelper.addMissingStyles(wordDoc);
            renderer.render(document, wordDoc);
            // 律所标准格式：楷体_GB2312/Arial、段后 18 磅、首行缩进 2 字符、表格 Grid 1.5 磅等
            com.checkba.util.DocxStyleHelper.applyStandardFormat(wordDoc);
            wordDoc.save(file);
            
            // Register with AGENT_USER_ID
            String wpsId = "gen_" + System.currentTimeMillis();
            String storageRelativePath = "projects/" + projectId + "/" + fileName;
            
            try {
                ProjectFile pf = projectFileService.createOrUpdateFile(
                        projectId, null, fileName, "docx", file.length(), 
                        storageRelativePath, wpsId, AGENT_USER_ID
                );
                
                // 通知前端刷新文件列表
                editorBridgeService.sendRefreshFilesAction();
                
                return String.format("{\"status\":\"success\", \"db_id\":%d, \"wps_file_id\":\"%s\", \"file_path\":\"%s\"}", pf.getId(), wpsId, targetPath.toAbsolutePath().toString().replace("\\", "\\\\"));
            } catch (Exception e) {
                return "File created at " + targetPath + " but DB register failed (Ownership lost): " + e.getMessage();
            }

        } catch (Exception e) {
            log.error("Failed to write docx", e);
            return "Error creating DOCX: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "扫描项目文件", category = "file")
    @Tool("Actively scan the project directory and register any missing files to the database. Repair DB inconsistency.")
    public String scan_files(
        @P("Project ID") Long projectId
    ) {
        try {
            Path projectRoot = storageResolver.projectRoot(projectId);
            if (!Files.exists(projectRoot)) return "Project directory not found: " + projectRoot;

            StringBuilder report = new StringBuilder("Scan Report:\n");
            
            // Scan depth 1 for now (Root of project)
            try (java.util.stream.Stream<Path> stream = Files.list(projectRoot)) {
                stream.forEach(entry -> {
                    if (Files.isRegularFile(entry)) {
                        String name = entry.getFileName().toString();
                        if (name.startsWith(".")) return; // ignore hidden
                        if (!name.contains(".")) return; // ignore no extension?
                        
                        String ext = name.substring(name.lastIndexOf(".") + 1);
                        long size = 0;
                        try { size = Files.size(entry); } catch(IOException ignore){}
                        
                        String wpsId = "scan_" + System.currentTimeMillis() + "_" + name.hashCode();
                        String storageRelPath = "projects/" + projectId + "/" + name;
                        
                        try {
                           // Use AGENT_USER_ID (1) or System?
                           projectFileService.createOrUpdateFile(projectId, null, name, ext, size, storageRelPath, null, AGENT_USER_ID);
                           report.append("- Synced: ").append(name).append("\n");
                        } catch(Exception e) {
                           report.append("- Failed: ").append(name).append(" (").append(e.getMessage()).append(")\n");
                        }
                    }
                });
            }
            
            // 通知前端刷新文件列表
            editorBridgeService.sendRefreshFilesAction();
            
            return report.toString();
        } catch (Exception e) {
             // "Error" 前缀是 ToolResult.success() 的失败判据，不能丢
             return "Error: Scan failed: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "删除文件", category = "file")
    @Tool("Delete a file. DISABLED: AI Agent is not allowed to delete files.")
    public String delete_file(String filePath) {
        log.info("Tool: delete_file called for {} - DENIED (AI Agent cannot delete files)", filePath);
        // AI Agent 不允许删除文件，只能新建、移动、重命名
        return "Error: Permission Denied. AI Agent is not allowed to delete files. You can only create, move, or rename files.";
    }

    @ToolMeta(displayName = "移动文件", category = "file", refreshFiles = true)
    @Tool("Move or rename a project file/folder by path (file tree and storage stay in sync). " +
            "Paths are relative to the project root, e.g. move_file('会议记录.txt', '归档/会议记录.txt'). " +
            "If destPath is an existing folder, the file is moved into it keeping its name. " +
            "Missing destination folders are created automatically. Path-based equivalent of move_project_file.")
    public String move_file(
            @P("Source path (relative to project root)") String sourcePath,
            @P("Destination path: target folder, or full path with new name") String destPath
    ) {
        // 2026-08 由「停用回错误」复活为 DB 感知版：真机日志实证（conv-1785993773100），
        // 非文档/非 PDF 文件（如 txt）在任何列表工具里都拿不到 fileId，模型对着停用
        // 提示只能绕道 read_file+write_file 整篇重写——既移不动文件又撑爆输出。
        // 本实现按路径解析 project_file 记录后走与 move_project_file 完全相同的服务路径。
        log.info("Tool: move_file called {} -> {}", sourcePath, destPath);
        if (!StringUtils.hasText(sourcePath) || !StringUtils.hasText(destPath)) {
            return "Error: sourcePath and destPath are required.";
        }
        Long projectId = com.checkba.service.ai.context.ProjectContextHolder.getProjectIdAsLong();
        if (projectId == null) {
            return "Error: no project context for this request.";
        }
        String src = normalizeRelPath(sourcePath);
        String dest = normalizeRelPath(destPath);
        if (src == null || dest == null) {
            return "Error: Access denied. Paths must stay inside the project directory (no '..').";
        }
        try {
            var index = dbPathIndex(projectId);
            ProjectFile source = index.get(src);
            if (source == null) {
                return "Error: '" + src + "' is not registered in the project file tree. "
                        + "Run scan_files first to register it, then retry.";
            }

            // destPath 指向已有文件夹 → 移入该文件夹并保留原名
            String parentDir;
            String newName;
            ProjectFile destEntry = index.get(dest);
            if (destEntry != null && Boolean.TRUE.equals(destEntry.getIsFolder())) {
                parentDir = dest;
                newName = source.getName();
            } else {
                int slash = dest.lastIndexOf('/');
                parentDir = slash < 0 ? null : dest.substring(0, slash);
                newName = slash < 0 ? dest : dest.substring(slash + 1);
            }

            Long targetFolderId = null;
            if (parentDir != null) {
                ProjectFile folder = index.get(parentDir);
                if (folder == null) {
                    // 逐段补建缺失的目标文件夹（某段已存在但是文件时抛 IllegalArgumentException，下面统一转成 Error 返回）
                    targetFolderId = projectFileService.ensureFolderPath(projectId, toolUserId(),
                            java.util.Arrays.asList(parentDir.split("/"))).getId();
                } else if (!Boolean.TRUE.equals(folder.getIsFolder())) {
                    return "Error: '" + parentDir + "' exists but is a file, not a folder.";
                } else {
                    targetFolderId = folder.getId();
                }
            }

            ProjectFile moved = projectFileService.move(source.getId(), targetFolderId, null, toolUserId());
            if (!newName.equals(moved.getName())) {
                moved = projectFileService.rename(moved.getId(), newName, toolUserId());
            }
            return "Successfully moved '" + src + "' to '" + (parentDir == null ? moved.getName() : parentDir + "/" + moved.getName())
                    + "' (fileId=" + moved.getId() + ").";
        } catch (Exception e) {
            log.warn("move_file failed {} -> {}", sourcePath, destPath, e);
            return "Error moving file: " + e.getMessage();
        }
    }

    // ==================== 文件树管理原语（DB 感知：文件树/物理文件同步更新） ====================
    // 治理"整理文件夹/重命名/移动"类诉求：此前没有任何 DB 感知的目录管理工具，
    // 模型只能用物理 move_file 把文件树搞脱节。三个原语直通 ProjectFileService，
    // 与前端文件树右键菜单同一条代码路径（同名校验/环检测/物理文件搬迁全部继承）。

    /** 工具执行线程的真实用户；拿不到时退回 Agent 专户（与 write_docx 的口径一致）。 */
    private Long toolUserId() {
        Long uid = com.checkba.service.ai.context.ProjectContextHolder.getUserId();
        return uid != null ? uid : AGENT_USER_ID;
    }

    @ToolMeta(displayName = "新建文件夹", category = "file", refreshFiles = true)
    @Tool("Create a new folder in the project file tree. Returns the new folderId. " +
            "Use parentFolderId to nest inside an existing folder (IDs from doc_list_project_files); omit for project root.")
    public String create_folder(
            @P("Folder name") String folderName,
            @P("Project ID") Long projectId,
            @P(value = "Parent folder ID (optional; omit for project root)", required = false) Long parentFolderId
    ) {
        log.info("Tool: create_folder '{}' parent={} project={}", folderName, parentFolderId, projectId);
        try {
            ProjectFile folder = projectFileService.createFolder(projectId, parentFolderId, folderName, toolUserId());
            return "Successfully created folder '" + folder.getName() + "' (folderId=" + folder.getId() + ").";
        } catch (Exception e) {
            return "Error creating folder: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "重命名文件", category = "file", refreshFiles = true)
    @Tool("Rename a project file or folder (file tree and storage stay in sync). " +
            "Get the fileId from doc_list_project_files. For files, the original extension is preserved automatically.")
    public String rename_project_file(
            @P("File or folder ID") Long fileId,
            @P("New name") String newName
    ) {
        log.info("Tool: rename_project_file {} -> '{}'", fileId, newName);
        try {
            ProjectFile renamed = projectFileService.rename(fileId, newName, toolUserId());
            return "Successfully renamed to '" + renamed.getName() + "' (fileId=" + renamed.getId() + ").";
        } catch (Exception e) {
            return "Error renaming: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "移动文件", category = "file", refreshFiles = true)
    @Tool("Move a project file or folder into another folder (file tree and storage stay in sync). " +
            "targetFolderId comes from doc_list_project_files or create_folder; omit to move to project root. " +
            "Moving a folder moves all its contents.")
    public String move_project_file(
            @P("File or folder ID to move") Long fileId,
            @P(value = "Target folder ID (optional; omit for project root)", required = false) Long targetFolderId
    ) {
        log.info("Tool: move_project_file {} -> folder {}", fileId, targetFolderId);
        try {
            ProjectFile moved = projectFileService.move(fileId, targetFolderId, null, toolUserId());
            return "Successfully moved '" + moved.getName() + "' to "
                    + (targetFolderId == null ? "project root" : "folder " + targetFolderId) + ".";
        } catch (Exception e) {
            return "Error moving: " + e.getMessage();
        }
    }

    // --- Helpers ---

    /**
     * 归一化项目内相对路径："./a/b" -> "a/b"；含 ".." 或越界返回 null（fail closed）。
     */
    private String normalizeRelPath(String path) {
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty() || p.equals(".")) return null;
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals("..")) return null;
        }
        return p;
    }

    /**
     * 项目文件树的相对路径索引："a/b/c.txt" -> ProjectFile（不含已删除，含文件夹）。
     * 供路径类工具把物理路径映射回 DB 记录，让所有文件类型都能拿到 fileId。
     */
    private java.util.Map<String, ProjectFile> dbPathIndex(Long projectId) {
        List<ProjectFile> all = projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(projectId);
        java.util.Map<Long, ProjectFile> byId = new java.util.HashMap<>();
        for (ProjectFile f : all) byId.put(f.getId(), f);
        java.util.Map<String, ProjectFile> index = new java.util.HashMap<>();
        for (ProjectFile f : all) {
            StringBuilder p = new StringBuilder(f.getName());
            ProjectFile cur = f;
            int guard = 0;
            while (cur.getParentId() != null && guard++ < 64) {
                ProjectFile parent = byId.get(cur.getParentId());
                if (parent == null) break;
                p.insert(0, parent.getName() + "/");
                cur = parent;
            }
            index.put(p.toString(), f);
        }
        return index;
    }

    private Path resolvePath(String fileName) {
        Path root = currentProjectRoot();
        Path resolved = Paths.get(fileName).isAbsolute()
                ? Paths.get(fileName).normalize()
                : root.resolve(fileName).normalize();
        // 安全围栏：AI 完全可控该路径，normalize 后必须仍在本项目目录内，
        // 否则 read/write/move_file 可用绝对路径或 "../" 读写别的租户的卷宗、
        // 或写进 skills/、plugins/ 扫描目录污染所有人的系统提示词。
        // 与同类 list_files 已有的 startsWith 校验保持一致。
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Access denied: path escapes project directory: " + fileName);
        }
        return resolved;
    }
    
    private String getFileType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0) ? fileName.substring(dot + 1) : "txt";
    }
}
