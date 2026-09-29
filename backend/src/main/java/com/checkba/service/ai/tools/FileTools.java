// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
    private final com.checkba.service.ai.StyleProfileResolver styleProfileResolver;
    /** extract_file_text 的抽取路由（OCR 判定、文字层、扫描件补 OCR），与参考材料读取共用（dev-board#718）。 */
    private final com.checkba.service.file.ProjectFileTextExtractor textExtractor;
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
    @Tool("Locate files by NAME PATTERN (glob, or a plain fragment of the name). Returns up to 50 project-relative "
            + "paths; every entry registered in the project database carries its id as '(fileId=N)', usable directly "
            + "with extract_file_text, rename_project_file, move_project_file and copy_files. "
            + "This searches file NAMES only - to find which file MENTIONS a phrase, use search_project_content; "
            + "for a complete inventory of every file with its id, use doc_list_project_files. "
            + "Can be limited to a sub-directory.")
    public String search_project_files(
            @P("File name pattern, e.g. '*起诉状*', '合同*' or '*.pdf'; a plain fragment such as '证据' also matches") String fileNamePattern,
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
    @Tool("Read a file's text BY PATH (absolute, or relative to the project root). "
            + "Use this only when you have a path and no database id — e.g. an entry list_files reported as "
            + "'unregistered', or a file you just wrote with write_file. "
            + "**When you have a fileId (from doc_list_project_files), use extract_file_text instead**: "
            + "same extraction and same truncation, but it also accepts a FOLDER id and lists its children. "
            + "Images and scanned PDFs are OCR'd automatically in the cloud (no local setup, no Docker, no script). "
            + "Max 10MB; very long text is truncated.")
    public String read_file(
            @P("文件路径：项目根目录下的相对路径（如 '卷宗/证据清单.txt'），或本项目目录内的绝对路径") String filePath) {
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
            // 音频（dev-board#814）：Tika 对 mp3 抽回来的是 ID3 标签里的标题/艺术家/专辑，
            // 非空，于是会被当成「文件正文」原样喂给模型。按路径查不到转写稿，所以这里
            // 只说事实并指向查得到的那个入口（extract_file_text + fileId）。
            if (com.checkba.service.meeting.MeetingRecordingService.isTranscribableMediaName(file.getName())) {
                return "Warning: " + com.checkba.service.file.ProjectFileTextExtractor
                        .audioNoticeByPath(file.getName());
            }
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
                return "Warning: no text extracted from '" + file.getName() + "' — the file may be empty, "
                        + "or an image whose OCR recognised nothing; try extract_file_text with its database file ID.";
            }
            // 同 extract_file_text 的上限（单一来源见 ToolFileGuard）：超长单条工具结果
            // 会把整轮顶进上下文超限，而且落在 compactor 尾区剪不掉
            return ToolFileGuard.capToolText(file.getName(), content);
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "列出文件", category = "file")
    @Tool("PHYSICAL DISK view of one directory under data/projects/{projectId}/: entries in on-disk layout, "
            + "one level at a time (pass subPath to descend). Registered entries DO carry their database id "
            + "— each line ends with (fileId=N) or (folderId=N), usable with doc_open_file, extract_file_text, "
            + "move_project_file, rename_project_file and create_folder; entries not yet in the database are "
            + "marked 'unregistered' (not in the file tree; readable by path with read_file). "
            + "Prefer doc_list_project_files for a whole-project inventory (it lists every file of every type "
            + "with its id in one call); use this one when the on-disk folder structure itself is what matters.")
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
                            ? " (unregistered: not in the file tree; read it by path with read_file)"
                            : (Files.isDirectory(path) ? " (folderId=" : " (fileId=") + pf.getId() + ")";
                    sb.append(type).append(" ").append(name).append(idNote).append("\n");
                });
            }

            sb.append("\nNote: fileId/folderId work with move_project_file, rename_project_file, copy_files and create_folder; move_files_batch accepts paths directly.");
            return sb.toString();

        } catch (IOException e) {
            log.error("Failed to list files", e);
            return "Error listing files: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "提取文档全文", category = "file")
    @Tool("Read the plain text of any project file by its database file ID: Word/Excel/PowerPoint, PDF, plain text "
            + "(UTF-8 or GBK), images, and audio/video that has been transcribed. "
            + "Images and scanned PDFs are OCR'd automatically in the cloud (no local setup, no Docker, no script); "
            + "when recognition fails the reply states the real reason (e.g. insufficient Credits, OCR not enabled) - "
            + "relay that reason to the user verbatim instead of inferring one. "
            + "Returns at most " + ToolFileGuard.MAX_TOOL_TEXT_CHARS + " characters per call: when the reply says "
            + "there is more ('还有 N 字符未读', nextStart=N), call again with offset=N to continue - "
            + "do not re-read from the start. To find where a phrase appears, use search_project_content first. "
            + "If the ID is a FOLDER, returns a listing of its direct children (id + name + type) instead of an error. "
            + "This is the fileId entry point; read_file is the same extraction addressed BY PATH, "
            + "for files that have no database id yet.")
    public String extract_file_text(
            @P("Project file database ID (from doc_list_project_files / material list). May also be a folder ID — you get its contents listed.") Long fileId,
            @P(value = "Optional: character offset to start from (0-based, default 0). "
                    + "To continue a long file, pass the nextStart of the previous reply.", required = false) Integer offset,
            @P(value = "Optional: max characters to return this call (default and upper limit "
                    + ToolFileGuard.MAX_TOOL_TEXT_CHARS + ")", required = false) Integer maxChars
    ) {
        log.info("Tool: extract_file_text called for fileId={}, offset={}, maxChars={}", fileId, offset, maxChars);
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
            String name = pf.getName();
            boolean ocrSupported = textExtractor.isOcrSupported(name);
            // 图片没有文字层可抽，直接 OCR；PDF 先抽文字层，抽不出（扫描件）才 OCR——路由在抽取器里。
            // OCR 失败一律 Error: 开头并把底层原因原样带出（Credits 不足、OCR 未开放、上游报错），
            // 模型才能转述真实原因，而不是转头去调 run_python 自己跑 OCR（dev-board#396）。
            String text;
            try {
                text = textExtractor.extractText(pf);
            } catch (com.checkba.service.file.ProjectFileTextExtractor
                    .AudioNotTranscribedException e) {
                // 音频要先转写（dev-board#814）：已转写的抽取器直接给转写稿，
                // 没转写的这里回一句可行动的下一步，而不是一句指向 OCR 的误导。
                return "Warning: " + e.getMessage();
            } catch (com.checkba.service.file.ProjectFileTextExtractor.OcrFailedException e) {
                return "Error: " + e.getMessage();
            }
            if (!StringUtils.hasText(text)) {
                return ocrSupported
                        ? "Warning: cloud OCR ran on '" + name + "' but recognised no text — the image may be "
                                + "blank, or too blurred to read. Tell the user what you tried."
                        : "Warning: no text extracted from '" + name + "'. The file may be empty, or its format "
                                + "carries no extractable text (OCR only covers images and PDF).";
            }
            // 分页与截断同一条口径（ToolFileGuard.pageToolText）：从头读且一次读得完时原样返回，
            // 保留原有的「[文件 X]」抬头（模型据此知道正文属于哪个文件）
            String paged = ToolFileGuard.pageToolText(pf.getName(), pf.getId(), text, offset, maxChars);
            return paged == text ? "[文件 " + pf.getName() + "]\n" + text : paged;
        } catch (Exception e) {
            log.warn("extract_file_text failed for fileId={}", fileId, e);
            return "Error extracting text: " + e.getMessage();
        }
    }

    /** 从头读的便捷重载（非工具入口；ToolRegistry 只登记带 {@code @Tool} 的那个）。 */
    public String extract_file_text(Long fileId) {
        return extract_file_text(fileId, null, null);
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
    @Tool("Write a plain-text file (txt / md / csv / json ...) into the project and register it in the file tree, "
            + "so it shows up there and can be opened in the editor. Returns the db_id. "
            + "It goes to the project root unless you pass parentFolderId (a folder id from list_project_folders "
            + "or create_folder) - same meaning as write_docx's parentFolderId. A file with the same name in that "
            + "folder is overwritten. fileName is a bare file name, never a path. For a Word document use write_docx.")
    public String write_file(
            @P("File name only, no folder path (e.g. 'notes.txt')") String fileName,
            @P("File content") String content,
            @P("Project ID (Required for DB registration)") Long projectId,
            @P(value = "Target folder ID (optional; omit for the project root). Folder IDs come from "
                    + "list_project_folders or create_folder.", required = false) Long parentFolderId
    ) {
        log.info("Tool: write_file called for {} (folder={})", fileName, parentFolderId);
        if (fileName == null || fileName.isBlank()) {
            return "Error: fileName is required.";
        }
        // 名字里带目录：以前是写到磁盘上、不登记，再请模型去调 scan_files 补登记——而 scan_files
        // 只扫项目根目录，那一步根本补不上（dev-board#1065，审计 T-07）。现在子文件夹一律走
        // parentFolderId，名字里的路径直接拒绝，不留一份文件树里看不见的孤儿文件。
        if (fileName.contains("/") || fileName.contains("\\")) {
            return "Error: fileName must be a bare file name, not a path. To write into a subfolder pass "
                    + "parentFolderId (folder IDs come from list_project_folders, or create_folder for a new one).";
        }
        if (parentFolderId != null) {
            return writeFileIntoFolder(fileName, content, projectId, parentFolderId);
        }
        try {
             Path path = resolvePath(fileName);
             if (!Files.exists(path.getParent())) {
                 Files.createDirectories(path.getParent());
             }

             Files.writeString(path, content == null ? "" : content,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

             // 落库。此前这里只有一段「Register in DB so Agent "owns" it」的注释，
             // 底下一行代码都没有——而工具描述与参数说明都白纸黑字写着会注册。
             // 后果：文件躺在项目目录里但没有 project_file 行，文件树看不见、编辑器打不开、
             // 后续工具也拿不到 fileId，模型却已经向用户报告「文件已创建」。
             // 注册方式与 write_docx 完全一致（createOrUpdateFile 幂等：同名已存在就更新）。
             if (projectId == null) {
                 return "File written to " + path.toAbsolutePath()
                         + " but NOT registered in the project (no projectId): it will not appear in the file tree.";
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
                 // 补救路径不再指 scan_files（它已不下发，dev-board#1065）：同名再写一次，
                 // createOrUpdateFile 幂等，会把这一行补登记上
                 return "File written to " + path.toAbsolutePath()
                         + " but DB registration failed (it will not appear in the file tree): "
                         + e.getMessage() + " - call write_file again with the same name to retry the registration.";
             }
        } catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    /**
     * write_file 的子文件夹分支（dev-board#1065，审计 T-07）：与 write_docx 的 parentFolderId 同口径——
     * 文件夹必须属于当前项目；行由 {@link ProjectFileService#createOrUpdateFile} 建（物理路径服务端
     * 按文件夹生成、同名即更新），字节由 {@link ProjectFileService#overwriteTextContent} 落盘
     *（回写大小、发版本信号）。
     */
    private String writeFileIntoFolder(String fileName, String content, Long projectId, Long parentFolderId) {
        if (projectId == null) {
            return "Error: no project context; cannot write into folder " + parentFolderId + ".";
        }
        ProjectFile folder = projectFileService.findFile(parentFolderId).orElse(null);
        if (folder == null || Boolean.TRUE.equals(folder.getIsDeleted())) {
            return "Error: target folder " + parentFolderId
                    + " does not exist (folder IDs come from list_project_folders).";
        }
        String denied = ToolFileGuard.rejectIfOutsideProject(folder);
        if (denied != null) return denied;
        if (!Boolean.TRUE.equals(folder.getIsFolder()) && !"folder".equalsIgnoreCase(folder.getFileType())) {
            return "Error: parentFolderId " + parentFolderId + " is a file ('" + folder.getName()
                    + "'), not a folder (folder IDs come from list_project_folders).";
        }
        String text = content == null ? "" : content;
        try {
            ProjectFile pf = projectFileService.createOrUpdateFile(projectId, parentFolderId, fileName.trim(),
                    getFileType(fileName), (long) text.getBytes(StandardCharsets.UTF_8).length,
                    null, null, AGENT_USER_ID);
            ProjectFile written = projectFileService.overwriteTextContent(projectId, pf.getId(), text, AGENT_USER_ID);
            editorBridgeService.sendRefreshFilesAction();
            String storedPath = written != null && written.getFilePath() != null
                    ? written.getFilePath() : pf.getFilePath();
            return String.format("{\"status\":\"success\", \"db_id\":%d, \"file_path\":\"%s\"}",
                    pf.getId(), String.valueOf(storedPath).replace("\\", "\\\\"));
        } catch (Exception e) {
            log.warn("write_file into folder {} failed for {}", parentFolderId, fileName, e);
            return "Error writing file into folder " + parentFolderId + ": " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "生成Word文档", category = "file", fileEffect = "ADDED", fileArg = "fileName", refreshFiles = true)
    @Tool("【STRICTLY NEW FILES ONLY】Create a NEW .docx from Markdown. FORBIDDEN for 'revise', 'update', or 'modify' tasks. If a similar file exists, you MUST use doc_open_file to edit it. DO NOT create 'Revised_Version.docx'. "
            + "Choosing between this and doc_start_stream: a long draft the user watches being written, in a desktop "
            + "editor session -> doc_start_stream; a one-shot save, or an Office add-in / plain chat session -> write_docx.")
    public String write_docx(
            @P("新文件名 (如 '报告.docx')") String fileName,
            @P("Markdown 内容") String markdownContent,
            @P("项目ID") Long projectId,
            @P(value = "目标文件夹ID（可选，不填则放项目根目录）", required = false) Long parentFolderId,
            @P(value = "样式画像 JSON（可选；docx_inspect_template 的输出或其子集。不填自动取项目 _模板/画像.json，"
                    + "没有则用系统默认 / 律所标准格式）", required = false) String styleProfileJson
    ) {
        // 同一轮对同一目标再生成一次：直接复用第一次那份（dev-board#1017）。编辑器没就绪时模型常转头
        // 用 write_docx 重来，而带文件夹的路径走 ConflictPolicy.RENAME——每试一次多一份「 (n)」同名文档。
        String runKey = (fileName == null || fileName.isBlank()) ? null
                : com.checkba.service.ai.EditorBridgeService.newDocxKey(parentFolderId, fileName);
        if (runKey != null) {
            Long existingId = editorBridgeService.generatedInRun(runKey);
            if (existingId != null) {
                ProjectFile existing = projectFileService.findFile(existingId).orElse(null);
                if (existing != null && !Boolean.TRUE.equals(existing.getIsDeleted())) {
                    return com.checkba.service.ai.EditorBridgeService.reusedGeneratedMessage(
                            existing.getName(), existing.getId());
                }
                editorBridgeService.forgetGenerated(runKey);
            }
        }
        String out = writeDocxDispatch(fileName, markdownContent, projectId, parentFolderId, styleProfileJson);
        Long createdId = successDbId(out);
        if (runKey != null && createdId != null) {
            editorBridgeService.noteGenerated(runKey, createdId);
        }
        return out;
    }

    private static final java.util.regex.Pattern SUCCESS_DB_ID =
            java.util.regex.Pattern.compile("^\\{\"status\":\"success\", \"db_id\":(\\d+)");

    /** write_docx 成功回执里的 db_id；不是成功回执返回 null。 */
    static Long successDbId(String out) {
        if (out == null) return null;
        java.util.regex.Matcher m = SUCCESS_DB_ID.matcher(out);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    private String writeDocxDispatch(String fileName, String markdownContent, Long projectId,
                                     Long parentFolderId, String styleProfileJson) {
        if (parentFolderId != null) {
            // 指定目标文件夹时走 AiDocxExportService（正确的路径构建 + StorageService 落盘 + RAG 刷新）
            log.info("Tool: write_docx (folder={}) called for {}", parentFolderId, fileName);
            if (fileName == null || fileName.isBlank()) return "Error: fileName is required.";
            if (!fileName.endsWith(".docx")) fileName += ".docx";
            if (fileName.matches(".*(revise|revision|update|modify|change|修改|修订|更新|变动).*")) {
                return "Error: Creation of files with 'revise/update/modify' in the name is FORBIDDEN. Use doc_open_file to edit the original instead.";
            }
            // 画像解析放在文件名校验之后：被拒的调用不该白读项目画像/系统配置
            com.checkba.util.style.StyleProfile profile = resolveProfile(projectId, styleProfileJson);
            try {
                ProjectFile pf = aiDocxExportService.exportMarkdownToDocx(
                        projectId, parentFolderId, AGENT_USER_ID, fileName, markdownContent, profile);
                editorBridgeService.sendRefreshFilesAction();
                return String.format("{\"status\":\"success\", \"db_id\":%d, \"file_path\":\"%s\"}",
                        pf.getId(), String.valueOf(pf.getFilePath()).replace("\\", "\\\\"));
            } catch (Exception e) {
                log.error("write_docx to folder failed", e);
                return "Error creating DOCX in folder " + parentFolderId + ": " + e.getMessage();
            }
        }
        return writeDocxAtRoot(fileName, markdownContent, projectId, styleProfileJson);
    }

    private com.checkba.util.style.StyleProfile resolveProfile(Long projectId, String styleProfileJson) {
        return styleProfileResolver == null
                ? com.checkba.util.style.StyleProfiles.houseDefault()
                : styleProfileResolver.resolve(projectId, styleProfileJson);
    }

    private String writeDocxAtRoot(String fileName, String markdownContent, Long projectId, String styleProfileJson) {
        log.info("Tool: write_docx called for {}", fileName);
        if (fileName == null || fileName.isBlank()) {
            return "Error: fileName is required.";
        }
        if (!fileName.endsWith(".docx")) fileName += ".docx";

        // Block suspicious filenames that suggest revision
        if (fileName.matches(".*(revise|revision|update|modify|change|修改|修订|更新|变动).*")) {
            return "Error: Creation of files with 'revise/update/modify' in the name is FORBIDDEN. You MUST use 'doc_open_file' to open the original file and use editing tools (doc_find_replace, doc_modify_paragraph, etc.) to apply changes. DO NOT create a new file.";
        }
        com.checkba.util.style.StyleProfile profile = resolveProfile(projectId, styleProfileJson);

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
            
            com.vladsch.flexmark.util.data.MutableDataSet options =
                    com.checkba.service.ai.AiDocxExportService.markdownOptions();
            Parser parser = Parser.builder(options).build();
            // XML 1.0 不允许的控制字符原样进 document.xml 会让整份 docx 打不开（dev-board#1018）
            com.vladsch.flexmark.util.ast.Node document = parser.parse(
                    com.checkba.service.ai.AiDocxExportService.stripXmlInvalidChars(
                            markdownContent == null ? "" : markdownContent));

            // Flexmark docx-converter usage pattern:
            File file = targetPath.toFile();
            DocxRenderer renderer = DocxRenderer.builder(options).build();

            // Create Package -> Add missing styles -> Render -> Save
            org.docx4j.openpackaging.packages.WordprocessingMLPackage wordDoc = org.docx4j.openpackaging.packages.WordprocessingMLPackage.createPackage();
            com.checkba.util.DocxStyleHelper.addMissingStyles(wordDoc);
            renderer.render(document, wordDoc);
            // 样式画像：显式 styleProfileJson / 项目画像 / 系统默认 / house-default（律所标准格式）
            com.checkba.util.DocxStyleHelper.applyProfile(wordDoc, profile);
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

    // dev-board#1065（审计 T-07）：它存在的唯一理由是 write_file 写不进子文件夹、要靠它补登记——
    // 而它只扫项目根目录，那一步其实补不上。write_file 有了 parentFolderId 之后它就只是个维护动作，
    // 只登记不下发（老会话回放与 XML 兜底照常执行）。
    @ToolMeta(displayName = "扫描项目文件", category = "file", offerToModel = false)
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

    // 永久停用的工具不下发规格（审计 A15）：本仓的口径一直是「只裁 spec、不裁
    // resolve/execute」，delete_file 正是这条口径的教科书案例——登记该留（模型经 XML 兜底
    // 路径调到时拿的是下面那句可行动的拒绝，好过 "Tool not found" 让它以为删文件这件事
    // 整个不存在），规格该裁（每轮白付一份 schema，而且用户说「把这个文件删掉」时
    // 模型会先调一次再转述拒绝，白烧一个往返）。
    @ToolMeta(displayName = "删除文件", category = "file", offerToModel = false)
    @Tool("Delete a file. DISABLED: AI Agent is not allowed to delete files.")
    public String delete_file(String filePath) {
        log.info("Tool: delete_file called for {} - DENIED (AI Agent cannot delete files)", filePath);
        // AI Agent 不允许永久删除；可恢复的删除走 move_to_trash（dev-board#1044）
        return "Error: Permission Denied. AI Agent is not allowed to permanently delete files. "
                + "Use move_to_trash to move them to the project recycle bin (the user can restore them).";
    }

    // dev-board#1065（审计 T-06）：路径式移动/改名的唯一入口是 move_files_batch（单条同样走它，
    // 两者共用 moveOnePath，拒绝理由一字不差）。本工具只登记不下发，老会话回放与 XML 兜底照常执行。
    @ToolMeta(displayName = "移动文件", category = "file", refreshFiles = true, offerToModel = false)
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
            MoveOutcome outcome = moveOnePath(projectId, dbPathIndex(projectId), src, dest);
            return "Successfully moved '" + src + "' to '" + outcome.path()
                    + "' (fileId=" + outcome.file().getId() + ").";
        } catch (IllegalArgumentException e) {
            // 可行动的拒绝（源未登记、目标那段是文件…）：原样把理由交给模型，文案与拆出共用实现前逐字一致
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.warn("move_file failed {} -> {}", sourcePath, destPath, e);
            return "Error moving file: " + e.getMessage();
        }
    }

    /** 一次 move_files_batch 最多移动多少份（与 office_replace_batch 的 MAX_BATCH_EDITS 同值同口径） */
    private static final int MAX_BATCH_MOVES = 50;

    /**
     * 批量清单的解析器。刻意用类级静态实例而不是构造注入：FileTools 已有 8 个协作者、
     * 被多处直接 new，为一处 readValue 再加一个构造参数会把改动扩散到一堆无关文件。
     * ObjectMapper 的读路径是线程安全的。
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper BATCH_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 一条已通过前置校验的移动。 */
    private record PlannedMove(String src, String dest) {}

    /** 一次成功移动的结果：DB 记录 + 移动后的项目内相对路径。 */
    private record MoveOutcome(ProjectFile file, String path) {}

    @ToolMeta(displayName = "批量移动文件", category = "file", refreshFiles = true)
    @Tool("Move MANY project files/folders in ONE call (file tree and storage stay in sync). " +
            "movesJson is a JSON array of {\"sourcePath\":\"a.docx\",\"destPath\":\"01 Pleadings/a.docx\"}, " +
            "at most " + MAX_BATCH_MOVES + " entries per batch; paths are relative to the project root. " +
            "[Organising a folder, archiving, sorting several files into categories MUST go through this tool in one call - " +
            "do NOT call move_project_file / create_folder once per file] - " +
            "one call per file runs out of the turn's step budget half way. " +
            "Missing destination folders are created automatically - you do NOT need create_folder first. " +
            "If destPath is an existing folder the file keeps its name; otherwise the last segment becomes the new name " +
            "(so a move can rename at the same time). " +
            "This is also THE tool for moving or renaming a single file by path - just pass a one-entry array. " +
            "The report gives 'moved: N' plus a per-item list; retry ONLY the entries under FAILED, never resend the whole " +
            "batch (the ones that succeeded would be moved twice).")
    public String move_files_batch(
            @P("Move list, JSON array: [{\"sourcePath\":\"...\",\"destPath\":\"...\"}, ...]") String movesJson
    ) {
        // dev-board#466：文件树的变更原语全是单项的，而步数预算按 LLM 轮数计
        // （AgentOrchestrator.MAX_LOOP_DEPTH=30）。弱模型一轮只发一个调用时，
        // 「14 份文件归进 8 个文件夹」光变更就要十几二十轮，撞上限暂停。
        // 修法照抄 #419 的 office_replace_batch：一个真正的批量原语 + 末位强制指引。
        log.info("Tool: move_files_batch called");
        Long projectId = com.checkba.service.ai.context.ProjectContextHolder.getProjectIdAsLong();
        if (projectId == null) {
            return "Error: no project context for this request.";
        }
        if (!StringUtils.hasText(movesJson)) {
            return "Error: movesJson 不能为空，示例：[{\"sourcePath\":\"会议记录.txt\",\"destPath\":\"归档/会议记录.txt\"}]";
        }
        List<java.util.Map<String, Object>> raw;
        try {
            raw = BATCH_MAPPER.readValue(movesJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {});
        } catch (Exception e) {
            return "Error: movesJson 不是合法的 JSON 数组，示例："
                    + "[{\"sourcePath\":\"会议记录.txt\",\"destPath\":\"归档/会议记录.txt\"}]";
        }
        if (raw == null || raw.isEmpty()) {
            return "Error: movesJson 至少要有一条移动";
        }
        if (raw.size() > MAX_BATCH_MOVES) {
            return "Error: 一批最多 " + MAX_BATCH_MOVES + " 份，本次给了 " + raw.size() + " 份，请拆成多批分次提交";
        }

        // 形状校验全部前置：任何一条不合法就整批不动手，不留半成品文件树。
        // （目标文件夹存不存在不在这里判——缺的会在执行时自动补建，且批内先建的对后面可见。）
        List<PlannedMove> plan = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            int index = i + 1;
            java.util.Map<String, Object> item = raw.get(i);
            Object s = item == null ? null : item.get("sourcePath");
            Object d = item == null ? null : item.get("destPath");
            String srcRaw = s == null ? "" : String.valueOf(s);
            String destRaw = d == null ? "" : String.valueOf(d);
            if (!StringUtils.hasText(srcRaw)) {
                return "Error: 第 " + index + " 条的 sourcePath 为空";
            }
            if (!StringUtils.hasText(destRaw)) {
                return "Error: 第 " + index + " 条的 destPath 为空";
            }
            String src = normalizeRelPath(srcRaw);
            String dest = normalizeRelPath(destRaw);
            if (src == null || dest == null) {
                return "Error: 第 " + index + " 条越界。Access denied: 路径必须留在项目目录内（不能含 '..'）："
                        + srcRaw + " -> " + destRaw;
            }
            if (!seen.add(src)) {
                return "Error: 第 " + index + " 条的 sourcePath 在本批里重复：" + src
                        + "（同一份文件一批只能移动一次；连着搬两次请分两批）";
            }
            plan.add(new PlannedMove(src, dest));
        }

        // 索引一次建、每成功一项后重建：批内新建的文件夹、改过的路径对后续条目可见，
        // 拿旧索引接着走会把后面的条目解析到过时的位置上（不报错、静默搬错）。
        java.util.Map<String, ProjectFile> index = dbPathIndex(projectId);
        List<String> ok = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (PlannedMove m : plan) {
            try {
                MoveOutcome outcome = moveOnePath(projectId, index, m.src(), m.dest());
                ok.add(m.src() + " -> " + outcome.path() + " (fileId=" + outcome.file().getId() + ")");
                index = dbPathIndex(projectId);
            } catch (Exception e) {
                // 单条失败不掀翻整批：物理文件已经搬走的那些回滚不了，
                // 逐条如实回报远好过让模型整批重发（成功的会被搬第二遍）
                log.warn("move_files_batch entry failed {} -> {}", m.src(), m.dest(), e);
                failed.add(m.src() + " -> " + m.dest() + " : " + e.getMessage());
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("moved: ").append(ok.size()).append("; failed: ").append(failed.size()).append('\n');
        for (String line : ok) {
            report.append("- ").append(line).append('\n');
        }
        if (!failed.isEmpty()) {
            report.append("FAILED (retry only these, do NOT resend the whole batch):\n");
            for (String line : failed) {
                report.append("- ").append(line).append('\n');
            }
        }
        return report.toString().trim();
    }

    /** 一次 move_to_trash 最多多少项（与 move_files_batch 同值同口径）。 */
    private static final int MAX_BATCH_TRASH = 50;

    /** 回执末尾那句给模型转述的恢复指引（中英同句，模型按会话语言转述）。 */
    static final String TRASH_RECOVERY_HINT =
            "已移入回收站，可在资源管理器的回收站中恢复。 / Moved to the recycle bin; restore it from the recycle bin in the file explorer.";

    @ToolMeta(displayName = "移入回收站", category = "file", refreshFiles = true)
    @Tool("Move project files/folders to the project RECYCLE BIN (recoverable - this is NOT a permanent delete). " +
            "Same action as the user pressing Delete in the file explorer: the entries disappear from the file tree " +
            "and can be restored from the recycle bin; a folder goes in together with everything inside it. " +
            "targetsJson is a JSON array whose items are either a path relative to the project root (string) " +
            "or a numeric fileId, e.g. [\"草稿/临时摘录.txt\", 1234]; at most " + MAX_BATCH_TRASH + " items per call. " +
            "Use it to clean up intermediate/temporary files you created - do NOT create a 'to delete' folder instead. " +
            "Only trash files the user asked to remove or that you produced yourself as scratch output. " +
            "The report gives 'trashed: N' plus a per-item list; retry ONLY the entries under FAILED.")
    public String move_to_trash(
            @P("Items to trash, JSON array of project-relative paths and/or numeric fileIds: [\"a.txt\", 123]") String targetsJson
    ) {
        // dev-board#1044（D25）：AI 此前没有任何删除途径（delete_file 永久停用），被要求「清掉中间产物」
        // 时只能建一个「待删除」文件夹把东西挪进去。这里给一个可恢复的原语：走 ProjectFileService.delete
        // ——与资源管理器右键「删除」同一条路（软删 isDeleted=true，不碰磁盘），用户随时能从回收站还原。
        // 永久删除（permDelete）刻意不给 AI。
        log.info("Tool: move_to_trash called");
        Long projectId = com.checkba.service.ai.context.ProjectContextHolder.getProjectIdAsLong();
        if (projectId == null) {
            return "Error: no project context for this request.";
        }
        if (!StringUtils.hasText(targetsJson)) {
            return "Error: targetsJson 不能为空，示例：[\"草稿/临时摘录.txt\", 1234]";
        }
        List<Object> raw;
        try {
            raw = BATCH_MAPPER.readValue(targetsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
        } catch (Exception e) {
            return "Error: targetsJson 不是合法的 JSON 数组，示例：[\"草稿/临时摘录.txt\", 1234]";
        }
        if (raw == null || raw.isEmpty()) {
            return "Error: targetsJson 至少要有一项";
        }
        if (raw.size() > MAX_BATCH_TRASH) {
            return "Error: 一次最多 " + MAX_BATCH_TRASH + " 项，本次给了 " + raw.size() + " 项，请拆成多批分次提交";
        }

        // 形状校验全部前置：任何一项不合法就整批不动手。
        List<Object> plan = new ArrayList<>();
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            int index = i + 1;
            Object item = raw.get(i);
            Object target;
            if (item instanceof Number n) {
                if (n.doubleValue() != n.longValue() || n.longValue() <= 0) {
                    return "Error: 第 " + index + " 项不是合法的 fileId：" + item;
                }
                target = n.longValue();
            } else if (item instanceof String s && StringUtils.hasText(s)) {
                String path = normalizeRelPath(s);
                if (path == null) {
                    return "Error: 第 " + index + " 项越界。Access denied: 路径必须留在项目目录内（不能含 '..'）：" + s;
                }
                target = path;
            } else {
                return "Error: 第 " + index + " 项要么是项目内相对路径（字符串），要么是数字 fileId";
            }
            if (!seen.add(target)) {
                return "Error: 第 " + index + " 项在本批里重复：" + target;
            }
            plan.add(target);
        }

        java.util.Map<String, ProjectFile> index = dbPathIndex(projectId);
        List<String> ok = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Object target : plan) {
            try {
                ProjectFile file;
                if (target instanceof Long id) {
                    file = projectFileRepository.findById(id).orElse(null);
                    // 跨项目的 id 与不存在的 id 同一句话：不给越权探测留出分辨余地
                    if (file == null || !java.util.Objects.equals(file.getProjectId(), projectId)) {
                        throw new IllegalArgumentException("fileId " + id + " is not a file of this project.");
                    }
                } else {
                    file = index.get((String) target);
                    if (file == null) {
                        throw new IllegalArgumentException("'" + target + "' is not in the project file tree "
                                + "(check the path with list_files or search_project_files).");
                    }
                }
                String label = target instanceof Long ? file.getName() + " (fileId=" + file.getId() + ")"
                        : target + " (fileId=" + file.getId() + ")";
                if (Boolean.TRUE.equals(file.getIsDeleted())) {
                    // 本批里先移入的父文件夹已经把它带进去了，或者它早就在回收站：目标已达成，不算失败
                    ok.add(label + " - already in the recycle bin");
                    continue;
                }
                if (ProjectFileService.isRootStagingFolder(file)) {
                    throw new IllegalArgumentException("the file staging area folder itself cannot be trashed; "
                            + "trash the files inside it instead.");
                }
                projectFileService.delete(file.getId(), toolUserId());
                ok.add(label + (Boolean.TRUE.equals(file.getIsFolder()) ? " - folder, with everything inside" : ""));
                index = dbPathIndex(projectId);
            } catch (Exception e) {
                log.warn("move_to_trash entry failed {}", target, e);
                failed.add(target + " : " + e.getMessage());
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("trashed: ").append(ok.size()).append("; failed: ").append(failed.size()).append('\n');
        for (String line : ok) {
            report.append("- ").append(line).append('\n');
        }
        if (!failed.isEmpty()) {
            report.append("FAILED (retry only these):\n");
            for (String line : failed) {
                report.append("- ").append(line).append('\n');
            }
        }
        if (!ok.isEmpty()) {
            report.append(TRASH_RECOVERY_HINT);
        }
        return report.toString().trim();
    }

    /**
     * 单条路径移动的实现，{@link #move_file} 与 {@link #move_files_batch} 共用——
     * 两个入口对同一件事的行为与拒绝理由不能有出入。
     *
     * @param index 调用方持有的路径索引（批量时每成功一项后重建）
     * @return 移动后的 DB 记录与项目内相对路径
     * @throws IllegalArgumentException 可行动的拒绝，message 直接给模型看
     */
    private MoveOutcome moveOnePath(Long projectId, java.util.Map<String, ProjectFile> index,
                                    String src, String dest) {
        ProjectFile source = index.get(src);
        if (source == null) {
            throw new IllegalArgumentException("'" + src + "' is not registered in the project file tree "
                    + "(check the path with list_files or search_project_files; a file that exists only on disk "
                    + "has to be added to the file tree by the user first).");
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
                // 逐段补建缺失的目标文件夹（某段已存在但是文件时 ensureFolderPath 抛 IllegalArgumentException）
                targetFolderId = projectFileService.ensureFolderPath(projectId, toolUserId(),
                        java.util.Arrays.asList(parentDir.split("/"))).getId();
            } else if (!Boolean.TRUE.equals(folder.getIsFolder())) {
                throw new IllegalArgumentException("'" + parentDir + "' exists but is a file, not a folder.");
            } else {
                targetFolderId = folder.getId();
            }
        }

        ProjectFile moved = projectFileService.move(source.getId(), targetFolderId, null, toolUserId());
        if (!newName.equals(moved.getName())) {
            moved = projectFileService.rename(moved.getId(), newName, toolUserId());
        }
        return new MoveOutcome(moved, parentDir == null ? moved.getName() : parentDir + "/" + moved.getName());
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

    /** 一次 copy_files 最多复制多少项（与 move_files_batch / move_to_trash 同值同口径）。 */
    private static final int MAX_BATCH_COPIES = 50;

    @ToolMeta(displayName = "复制文件", category = "file", refreshFiles = true)
    @Tool("Copy project files/folders (a folder is copied together with everything inside it) into a target folder, "
            + "the same action as Copy/Paste in the file explorer. The originals are left untouched. "
            + "fileIds is a JSON array of ids, e.g. [123, 456] (a comma-separated list also works), at most "
            + MAX_BATCH_COPIES + " per call; ids come from doc_list_project_files, search_project_files or list_files. "
            + "targetFolderId comes from list_project_folders or create_folder; omit it for the project root. "
            + "A copy placed in the same folder as its original is named '【副本】<name>'; a name clash in the "
            + "target folder gets a numbered suffix - nothing is ever overwritten. "
            + "Use it to keep a pristine version before a risky edit, or to assemble a bundle of materials in one folder.")
    public String copy_files(
            @P("Ids to copy, JSON array: [123, 456]") String fileIds,
            @P(value = "Target folder ID (optional; omit for the project root)", required = false) Long targetFolderId
    ) {
        // dev-board#1065（审计 T-25）：界面上早就能复制（ProjectFileService.batchCopy，资源管理器右键
        // 「复制 / 粘贴」），AI 却没有对位工具，「先留一份原稿再改」只能靠 write_docx 重写一遍。
        // 这里直通同一条服务路径：同名处理、文件夹递归、物理文件复制、版本信号全部继承。
        log.info("Tool: copy_files {} -> folder {}", fileIds, targetFolderId);
        Long projectId = com.checkba.service.ai.context.ProjectContextHolder.getProjectIdAsLong();
        if (projectId == null) {
            return "Error: no project context for this request.";
        }
        List<Long> ids;
        try {
            ids = parseIdList(fileIds);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (ids.isEmpty()) {
            return "Error: fileIds 至少要有一项，示例：[123, 456]";
        }
        if (ids.size() > MAX_BATCH_COPIES) {
            return "Error: 一次最多复制 " + MAX_BATCH_COPIES + " 项，本次给了 " + ids.size() + " 项，请拆成多批分次提交";
        }
        // 归属校验前置：batchCopy 自己也拒跨项目，但它的报错会带出别人项目的文件 id 是否存在——
        // 这里与 move_to_trash 同口径，「不存在」与「不属于本项目」回同一句话
        for (Long id : ids) {
            ProjectFile f = projectFileService.findFile(id).orElse(null);
            if (f == null || Boolean.TRUE.equals(f.getIsDeleted())
                    || !java.util.Objects.equals(f.getProjectId(), projectId)) {
                return "Error: fileId " + id + " is not a file of this project; nothing was copied.";
            }
        }
        com.checkba.model.dto.ProjectFileBatchRequest request = new com.checkba.model.dto.ProjectFileBatchRequest();
        request.setFileIds(ids);
        request.setTargetParentId(targetFolderId);
        try {
            List<ProjectFile> created = projectFileService.batchCopy(projectId, request, toolUserId());
            StringBuilder sb = new StringBuilder("copied: ").append(created.size()).append('\n');
            for (ProjectFile c : created) {
                boolean folder = Boolean.TRUE.equals(c.getIsFolder());
                sb.append("- ").append(folder ? "[文件夹] " : "").append(c.getName())
                        .append(folder ? " (folderId=" : " (fileId=").append(c.getId()).append(")\n");
            }
            sb.append("目标位置：").append(targetFolderId == null ? "项目根目录" : "文件夹 " + targetFolderId)
                    .append("。原文件保持不变。");
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.warn("copy_files failed ids={} target={}", ids, targetFolderId, e);
            return "Error copying files: " + e.getMessage();
        }
    }

    /** 「[1, 2]」或「1,2」都认；非正整数一律拒绝整批（形状校验全部前置，任何一项不合法就不动手）。 */
    static List<Long> parseIdList(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("fileIds 不能为空，示例：[123, 456]");
        }
        String body = raw.trim();
        if (body.startsWith("[")) {
            if (!body.endsWith("]")) {
                throw new IllegalArgumentException("fileIds 不是合法的 JSON 数组，示例：[123, 456]");
            }
            body = body.substring(1, body.length() - 1);
        }
        List<Long> ids = new ArrayList<>();
        for (String piece : body.split("[,，\\s]+")) {
            String t = piece.trim().replace("\"", "");
            if (t.isEmpty()) continue;
            long id;
            try {
                id = Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("fileIds 里的「" + t + "」不是数字 id，示例：[123, 456]");
            }
            if (id <= 0) {
                throw new IllegalArgumentException("fileIds 里的 " + id + " 不是合法的 id");
            }
            if (!ids.contains(id)) ids.add(id);
        }
        return ids;
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
        return dbPathIndex(projectFileRepository, projectId);
    }

    /** 同上，供同包其他工具（DocumentEditTools.doc_link_evidence 的 path → fileId）复用。 */
    static java.util.Map<String, ProjectFile> dbPathIndex(ProjectFileRepository projectFileRepository, Long projectId) {
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
