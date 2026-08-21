package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.storage.StorageException;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.util.style.StyleProfile;
import com.vladsch.flexmark.docx.converter.DocxRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;

/**
 * AI 导出 Word 文档服务：
 * - 接收 markdown 文本
 * - 在后端生成 docx 并通过 StorageService 落盘
 * - 创建 ProjectFile 记录，供前端和 WPS 使用
 */
@Service
@RequiredArgsConstructor
public class AiDocxExportService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiDocxExportService.class);

    private final ProjectFileService projectFileService;
    private final StorageServiceFactory storageServiceFactory;
    private final ProjectRagService projectRagService;
    private final StyleProfileResolver styleProfileResolver;

    /** write_docx 两条路径共用的 flexmark 配置：表格扩展（GFM 管道表）必须开。 */
    public static MutableDataSet markdownOptions() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, java.util.List.of(com.vladsch.flexmark.ext.tables.TablesExtension.create()));
        return options;
    }

    @Transactional
    public ProjectFile exportMarkdownToDocx(Long projectId,
                                            Long parentId,
                                            Long userId,
                                            String fileName,
                                            String markdownContent) {
        return exportMarkdownToDocx(projectId, parentId, userId, fileName, markdownContent, null);
    }

    /**
     * @param profile 样式画像；null 时按 {@link StyleProfileResolver} 的顺序解析
     *                （项目 _模板/画像.json → SystemSetting → house-default）
     */
    @Transactional
    public ProjectFile exportMarkdownToDocx(Long projectId,
                                            Long parentId,
                                            Long userId,
                                            String fileName,
                                            String markdownContent,
                                            StyleProfile profile) {
        if (projectId == null) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (!StringUtils.hasText(markdownContent)) {
            markdownContent = "";
        }

        // 生成 WPS 文件 ID，供在线编辑与回调使用
        String wpsFileId = "project_" + projectId + "_ai_" + System.currentTimeMillis();

        // 1. 先通过项目文件服务创建 ProjectFile 记录（会自动生成 filePath）
        // 同名时自动编号而非报错：AI 导出/OCR 转换是后端内部落盘调用点，不应该因为
        // 用户已有一份同名文档就整个失败。
        ProjectFile file = projectFileService.createFile(
                projectId,
                parentId,
                fileName,
                "docx",
                null,
                null,
                wpsFileId,
                userId,
                ProjectFileService.ConflictPolicy.RENAME
        );

        // 2. 使用 flexmark-docx-converter 根据 markdown 生成 docx
        String filePath = file.getFilePath();
        StorageService storageService = storageServiceFactory.getStorageService();

        try {
            // flexmark 配置：表格扩展必须开，否则 markdown 表格会退化成竖线文本，
            // 画像里的表格格式（边框/列宽/表头）根本没有落点
            MutableDataSet options = markdownOptions();
            Parser parser = Parser.builder(options).build();
            DocxRenderer renderer = DocxRenderer.builder(options).build();

            Document mdDocument = parser.parse(markdownContent);

            // 渲染为 docx 到内存中的 WordprocessingMLPackage
            WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.createPackage();
            // 添加缺失的样式（BodyText, Quotations），避免 docx4j PropertyResolver 报错
            com.checkba.util.DocxStyleHelper.addMissingStyles(wordMLPackage);
            renderer.render(mdDocument, wordMLPackage);
            // 样式画像：缺省 = 项目画像 / 系统默认 / house-default（律所标准格式）
            StyleProfile effective = profile != null ? profile : styleProfileResolver.resolve(projectId, null);
            com.checkba.util.DocxStyleHelper.applyProfile(wordMLPackage, effective);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wordMLPackage.save(bos);

            ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
            String savedPath = storageService.save(filePath, bis);

            log.info("AI 导出 Word 成功: projectId={}, fileId={}, path={}", projectId, file.getId(), savedPath);

            // 更新文件大小和路径信息
            file.setFilePath(savedPath);
            file.setFileSize((long) bos.size());
            file.setUpdatedAt(LocalDateTime.now());

            // 刷新项目知识库（增量）
            try {
                projectRagService.refreshProjectKnowledgeIncremental(
                        String.valueOf(projectId),
                        savedPath
                );
            } catch (Exception ragEx) {
                log.warn("刷新项目知识库失败: projectId={}, path={}", projectId, savedPath, ragEx);
            }

            return file;
        } catch (StorageException se) {
            log.error("AI 导出 Word 保存失败, filePath={}", filePath, se);
            throw new RuntimeException("保存 Word 文件失败: " + se.getMessage(), se);
        } catch (Exception e) {
            log.error("AI 导出 Word 生成失败", e);
            throw new RuntimeException("生成 Word 文件失败: " + e.getMessage(), e);
        }
    }
}


