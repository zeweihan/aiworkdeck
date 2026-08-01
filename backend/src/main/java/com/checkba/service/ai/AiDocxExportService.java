package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.storage.StorageException;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
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

    @Transactional
    public ProjectFile exportMarkdownToDocx(Long projectId,
                                            Long parentId,
                                            Long userId,
                                            String fileName,
                                            String markdownContent) {
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
        ProjectFile file = projectFileService.createFile(
                projectId,
                parentId,
                fileName,
                "docx",
                null,
                null,
                wpsFileId,
                userId
        );

        // 2. 使用 flexmark-docx-converter 根据 markdown 生成 docx
        String filePath = file.getFilePath();
        StorageService storageService = storageServiceFactory.getStorageService();

        try {
            // flexmark 配置：后续如需启用表格/任务列表等，可以在此处添加扩展
            MutableDataSet options = new MutableDataSet();
            Parser parser = Parser.builder(options).build();
            DocxRenderer renderer = DocxRenderer.builder(options).build();

            Document mdDocument = parser.parse(markdownContent);

            // 渲染为 docx 到内存中的 WordprocessingMLPackage
            WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.createPackage();
            // 添加缺失的样式（BodyText, Quotations），避免 docx4j PropertyResolver 报错
            com.checkba.util.DocxStyleHelper.addMissingStyles(wordMLPackage);
            renderer.render(mdDocument, wordMLPackage);
            // 律所标准格式：楷体_GB2312/Arial、段后 18 磅、首行缩进 2 字符、表格 Grid 1.5 磅等
            com.checkba.util.DocxStyleHelper.applyStandardFormat(wordMLPackage);

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


