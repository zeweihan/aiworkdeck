package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.storage.StorageException;
import com.checkba.storage.StorageServiceFactory;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文档全文抽取服务：基于 Apache Tika，支持 pdf/docx/xlsx 等常见格式。
 * 由 FileController（/text、/compare）与 AI 工具 extract_file_text 共用。
 */
@Service
public class DocumentTextService {

    private final StorageServiceFactory storageServiceFactory;

    public DocumentTextService(StorageServiceFactory storageServiceFactory) {
        this.storageServiceFactory = storageServiceFactory;
    }

    /**
     * 抽取项目文件的纯文本内容。
     */
    public String extractText(ProjectFile file) throws IOException, TikaException {
        String filePath = file.getFilePath();
        if (!StringUtils.hasText(filePath)) {
            // 尝试使用 wpsFileId 作为路径
            filePath = file.getWpsFileId();
        }

        if (!StringUtils.hasText(filePath)) {
            throw new IOException("文件路径为空: " + file.getId());
        }

        try {
            Resource resource = storageServiceFactory.getStorageService().load(filePath);
            try (InputStream is = resource.getInputStream()) {
                return parse(is);
            }
        } catch (StorageException e) {
            throw new IOException("加载文件失败: " + filePath, e);
        }
    }

    /**
     * 抽取任意输入流的纯文本内容（供测试与非 ProjectFile 场景使用）。
     */
    public String parse(InputStream is) throws IOException, TikaException {
        Tika tika = new Tika();
        // 默认 100k chars 上限会截断长公告，放宽到 5M chars（Tika 内部限制字符数）
        tika.setMaxStringLength(5 * 1024 * 1024);
        return tika.parseToString(is);
    }
}
