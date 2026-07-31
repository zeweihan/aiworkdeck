package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.storage.StorageException;
import com.checkba.storage.StorageServiceFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文档全文抽取服务：docx/xlsx 等走 Apache Tika；PDF 走 PDFBox 3 原生 API。
 * 注意：不能用 Tika 解析 PDF——项目 classpath 是 PDFBox 3.0.1，而 Tika 2.9.x 的
 * PDFParser 依赖 PDFBox 2.x 的 PDDocument.load（3.x 已删除），运行时会
 * NoSuchMethodError。由 FileController（/text、/compare）与 AI 工具
 * extract_file_text 共用。
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

        boolean isPdf = "pdf".equalsIgnoreCase(file.getFileType())
                || (file.getName() != null && file.getName().toLowerCase().endsWith(".pdf"));

        try {
            Resource resource = storageServiceFactory.getStorageService().load(filePath);
            try (InputStream is = resource.getInputStream()) {
                return isPdf ? parsePdf(is) : parse(is);
            }
        } catch (StorageException e) {
            throw new IOException("加载文件失败: " + filePath, e);
        }
    }

    /**
     * 非 PDF 格式的通用抽取（docx/xlsx/pptx/txt 等）。
     */
    public String parse(InputStream is) throws IOException, TikaException {
        Tika tika = new Tika();
        // 默认 100k chars 上限会截断长文档，放宽到 5M chars
        tika.setMaxStringLength(5 * 1024 * 1024);
        return tika.parseToString(is);
    }

    /**
     * PDF 文本抽取（PDFBox 3 原生 API）。扫描版 PDF 无文本层时返回空串。
     */
    public String parsePdf(InputStream is) throws IOException {
        try (PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }
}
