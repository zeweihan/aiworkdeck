package com.checkba.service.ai.context;

import com.checkba.config.AiContextProperties;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件上下文加载器（接入层与组装层共用的"读文件"专职服务）。
 *
 * 职责：
 * - 单文件文本提取（含 OCR 路径与临时文件生命周期管理）
 * - 文件夹递归遍历与内容收集（带深度/数量/大小上限）
 *
 * 历史背景：这些逻辑原分散在 AiChatController（collectFolderContent/OCR 临时文件）
 * 和 ContextAssemblerService（buildFolderContext/collectFilesRecursive）两处，
 * Phase 2 收拢至此，使控制器只做 HTTP 出入口、ContextAssembler 只做消息组装。
 */
@Service
public class FileContextLoader {

    private static final Logger log = LoggerFactory.getLogger(FileContextLoader.class);

    /** 文件夹递归的深度保护（chat 场景，行为沿用原控制器实现） */
    private static final int CHAT_FOLDER_MAX_DEPTH = 20;
    /** 文件夹递归的深度保护（Agent 上下文组装场景，行为沿用原实现） */
    private static final int ASSEMBLER_FOLDER_MAX_DEPTH = 5;
    /** 「读不出正文」提示里最多点名几个文件（其余用省略号，防大文件夹刷屏） */
    private static final int UNREADABLE_NAMES_SHOWN = 10;

    private final ProjectFileService projectFileService;
    private final FileContentExtractorService fileContentExtractorService;
    private final AiContextProperties contextProperties;
    private final com.checkba.storage.ProjectStorageResolver storageResolver;
    /** Office/PDF 正文抽取（Tika + PDFBox），与 read_document / extract_file_text 同一套 */
    private final com.checkba.service.DocumentTextService documentTextService;

    public FileContextLoader(ProjectFileService projectFileService,
                             FileContentExtractorService fileContentExtractorService,
                             AiContextProperties contextProperties,
                             com.checkba.storage.ProjectStorageResolver storageResolver,
                             com.checkba.service.DocumentTextService documentTextService) {
        this.projectFileService = projectFileService;
        this.fileContentExtractorService = fileContentExtractorService;
        this.contextProperties = contextProperties;
        this.storageResolver = storageResolver;
        this.documentTextService = documentTextService;
    }

    /**
     * 文件夹上下文里单个文件的正文抽取。
     *
     * <p>纯文本类（java/js/md/txt/csv…）直读；<b>其余（docx/xlsx/pptx/doc/pdf）走
     * {@link com.checkba.service.DocumentTextService}</b>——与 {@code read_document} /
     * {@code extract_file_text} 同一套 Tika+PDFBox。
     *
     * <p>此前这里只有 {@code FileContentExtractorService.extractText} 一条路，而它的
     * 白名单不含 Office 格式，恒返回空串，于是「文件夹里的 Word/PDF」在上下文里
     * 一个字都没有——单文件路径在 17ca80d7 已修（走 read_document），文件夹路径漏了。
     * 图片仍不在此处做 OCR：文件夹扫描是批量路径，逐张走 OCR 的代价不在本次修复范围内。
     */
    private String extractForFolder(ProjectFile f, java.io.File physicalFile) {
        if (fileContentExtractorService.isTextFile(f.getName())) {
            return fileContentExtractorService.extractText(physicalFile);
        }
        try {
            return documentTextService.extractText(f);
        } catch (Exception e) {
            log.warn("Folder context: failed to extract text from {}: {}", f.getName(), e.getMessage());
            return "";
        }
    }

    /**
     * 读取单个项目文件并提取文本（自动选择 OCR 或标准提取）。
     * 内部通过临时文件桥接存储层（本地/OSS）与提取器，并保证临时文件被清理。
     *
     * @return 提取的文本；文件为空返回提示文本；失败返回带原因的提示文本
     */
    public String extractFileText(ProjectFile fileEntity) {
        if (fileEntity == null) {
            return "[文件内容为空或无法读取]";
        }
        Path tempPath = null;
        try {
            byte[] fileBytes = projectFileService.getFileBytes(fileEntity.getId());
            if (fileBytes == null || fileBytes.length == 0) {
                log.warn("File content is empty or not found via service: {}", fileEntity.getName());
                return "[文件内容为空或无法读取]";
            }
            String tempName = "ocr_ctx_" + fileEntity.getId() + "_" + System.currentTimeMillis();
            String ext = fileEntity.getFileType() != null ? "." + fileEntity.getFileType() : ".tmp";
            tempPath = Files.createTempFile(tempName, ext);
            Files.write(tempPath, fileBytes);
            java.io.File physicalFile = tempPath.toFile();

            if (fileContentExtractorService.isOcrSupported(fileEntity.getName())) {
                log.info("-> Using OCR extraction for: {}", fileEntity.getName());
                return fileContentExtractorService.extractTextWithOcr(physicalFile);
            } else {
                log.info("-> Using standard extraction for: {}", fileEntity.getName());
                return fileContentExtractorService.extractText(physicalFile);
            }
        } catch (Exception e) {
            log.warn("Failed to read context file content in backend", e);
            return "[读取文件失败: " + e.getMessage() + "]";
        } finally {
            deleteQuietly(tempPath);
        }
    }

    /**
     * 收集文件夹下的文件内容（chat 接口场景：仅正文拼接，最多 N 个文件）。
     * 行为沿用原 AiChatController.collectFolderContent。
     */
    public String collectFolderContent(Long projectId, Long folderId) {
        StringBuilder sb = new StringBuilder();
        int[] counter = {0};
        collectChatFilesRecursive(projectId, folderId, sb, 0, counter);
        return sb.toString();
    }

    private void collectChatFilesRecursive(Long projectId, Long parentId, StringBuilder sb, int depth, int[] counter) {
        int maxFiles = contextProperties.getFiles().getMaxFilesPerContext();
        if (depth > CHAT_FOLDER_MAX_DEPTH) return;
        if (counter[0] >= maxFiles) return;

        List<ProjectFile> children = projectFileService.getFilesByParent(projectId, parentId);

        for (ProjectFile file : children) {
            if (counter[0] >= maxFiles) break;

            if (Boolean.TRUE.equals(file.getIsFolder())) {
                collectChatFilesRecursive(projectId, file.getId(), sb, depth + 1, counter);
            } else {
                Path tempP = null;
                try {
                    byte[] fileBytes = projectFileService.getFileBytes(file.getId());
                    if (fileBytes != null && fileBytes.length > 0) {
                        // 保留真实扩展名：extractText 依据扩展名判断是否文本文件，
                        // 用死的 ".tmp" 会让文件夹内所有 .txt/.md/.docx 正文被判为非文本而丢弃。
                        String nm = file.getName();
                        String ext = (nm != null && nm.contains(".")) ? nm.substring(nm.lastIndexOf('.')) : ".txt";
                        tempP = Files.createTempFile("folder_scan_" + file.getId(), ext);
                        Files.write(tempP, fileBytes);
                        String extracted = fileContentExtractorService.extractText(tempP.toFile());

                        if (StringUtils.hasText(extracted)) {
                            sb.append("\n--- File: ").append(file.getName()).append(" ---\n");
                            sb.append(extracted).append("\n");
                            counter[0]++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to read folder file content: {}", file.getName(), e);
                } finally {
                    deleteQuietly(tempP);
                }
            }
        }
    }

    /**
     * 构建文件夹上下文（Agent 组装场景：目录结构 + 前 N 个文件的内容）。
     * 行为沿用原 ContextAssemblerService.buildFolderContext。
     *
     * @param currentTotalCount 已消耗的文件配额（跨多个 context item 共享上限）
     */
    public String buildFolderContext(String folderIdStr, String projectIdStr, int currentTotalCount) {
        StringBuilder sb = new StringBuilder();
        try {
            Long folderId = Long.parseLong(folderIdStr);
            Long projectId = Long.parseLong(projectIdStr);

            List<ProjectFile> allFiles = new ArrayList<>();
            listFilesRecursive(projectId, folderId, allFiles, 0);

            // 1. Directory Structure
            sb.append("### Directory Content:\n");
            for (ProjectFile f : allFiles) {
                String type = Boolean.TRUE.equals(f.getIsFolder()) ? "[DIR]" : "[FILE]";
                sb.append("- ").append(type).append(" ").append(f.getName())
                  .append(" (ID: ").append(f.getId()).append(")\n");
            }
            sb.append("\n");

            // 2. File Contents (Limit total)
            int reads = 0;
            int maxReads = contextProperties.getFiles().getMaxFilesPerContext() - currentTotalCount;
            if (maxReads <= 0) return sb.toString();

            sb.append("### Folder Document Contents (First ").append(maxReads).append(" files):\n");

            long maxFileSize = contextProperties.getFiles().getMaxFileSizeBytes();
            int maxChars = contextProperties.getFiles().getFolderFileMaxChars();
            // 读不出正文的文件要在上下文里留痕：静默跳过时模型看到的是
            // 「Folder Document Contents」标题下空空如也，只能当这些文件不存在或去猜内容。
            List<String> unreadable = new ArrayList<>();
            for (ProjectFile f : allFiles) {
                if (reads >= maxReads) break;
                if (Boolean.TRUE.equals(f.getIsFolder())) continue;

                try {
                    // 旧实现 new File(相对filePath) 按 CWD 解析，几乎必然 exists()==false 静默跳过；
                    // 改走 resolver 才真正读到文件（localRoot 感知）
                    java.io.File physicalFile = storageResolver.resolve(f.getFilePath()).toFile();
                    if (physicalFile.exists() && physicalFile.length() < maxFileSize) {
                        String text = extractForFolder(f, physicalFile);
                        if (text != null && !text.isBlank()) {
                            if (text.length() > maxChars) text = text.substring(0, maxChars) + "...[Truncated]";

                            sb.append("\n#### File: ").append(f.getName()).append("\n");
                            sb.append("```\n").append(text).append("\n```\n");
                            reads++;
                        } else {
                            unreadable.add(f.getName());
                        }
                    } else {
                        unreadable.add(f.getName());
                    }
                } catch (Exception e) {
                    unreadable.add(f.getName());
                }
            }
            if (!unreadable.isEmpty()) {
                int shown = Math.min(unreadable.size(), UNREADABLE_NAMES_SHOWN);
                sb.append("\n[System Note: ").append(unreadable.size())
                  .append(" file(s) in this folder have no extractable text (scanned image, unsupported type, or too large): ")
                  .append(String.join(", ", unreadable.subList(0, shown)));
                if (unreadable.size() > shown) sb.append(", ...");
                sb.append(". Use extract_file_text or read_file with OCR if you need their content.]\n");
            }

        } catch (Exception e) {
            sb.append("\n[Error reading folder: ").append(e.getMessage()).append("]\n");
        }
        return sb.toString();
    }

    private void listFilesRecursive(Long projectId, Long parentId, List<ProjectFile> collector, int depth) {
        if (depth > ASSEMBLER_FOLDER_MAX_DEPTH) return;
        List<ProjectFile> children = projectFileService.getFilesByParent(projectId, parentId);
        for (ProjectFile child : children) {
            collector.add(child);
            if (Boolean.TRUE.equals(child.getIsFolder())) {
                listFilesRecursive(projectId, child.getId(), collector, depth + 1);
            }
        }
    }

    private void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignore) {
            }
        }
    }
}
