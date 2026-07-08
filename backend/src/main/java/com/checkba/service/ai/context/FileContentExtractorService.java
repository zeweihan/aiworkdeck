package com.checkba.service.ai.context;

import com.checkba.config.AiContextProperties;
import com.checkba.service.OcrService;
import com.checkba.service.ocr.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

@Service
public class FileContentExtractorService {

    private static final Logger log = LoggerFactory.getLogger(FileContentExtractorService.class);

    private static final Set<String> ALLOWED_TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "java", "kt", "scala", "groovy", // JVM
            "js", "jsx", "ts", "tsx", "vue", "svelte", // Frontend
            "html", "htm", "css", "scss", "less", // Web
            "xml", "yml", "yaml", "json", "properties", "toml", // Config
            "md", "txt", "csv", "sql", "sh", "bat", "dockerfile", ".gitignore", ".env" // Misc
    ));

    private final OcrService ocrService;
    private final AiContextProperties contextProperties;

    public FileContentExtractorService(OcrService ocrService, AiContextProperties contextProperties) {
        this.ocrService = ocrService;
        this.contextProperties = contextProperties;
    }

    private long maxFileSize() {
        return contextProperties.getFiles().getMaxFileSizeBytes();
    }

    /**
     * Extract text from a file (text files only, for backward compatibility).
     * For images/PDF, use extractTextWithOcr() instead.
     */
    public String extractText(File file) {
        if (file == null || !file.exists() || file.isDirectory()) {
            return "";
        }

        if (file.length() > maxFileSize()) {
            log.warn("File skipped due to size limit ({} > {}): {}", file.length(), maxFileSize(), file.getName());
            return "[System: File skipped - exceeds size limit]";
        }

        String fileName = file.getName();
        try {
            if (isTextFile(fileName)) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                try {
                    return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                } catch (java.nio.charset.CharacterCodingException ce) {
                    // 非 UTF-8（常见 GBK/GB18030 中文 txt/csv）回退，避免整篇内容丢失
                    return new String(bytes, java.nio.charset.Charset.forName("GBK"));
                }
            } else {
                // Non-text files: skip or hint to use OCR
                return "";
            }
        } catch (Exception e) {
            log.warn("Failed to extract text from file: {}", fileName, e);
            return "[System: Error reading file content]";
        }
    }

    /**
     * Extract text from image or PDF files using Aliyun OCR.
     * For multimodal-capable models, prefer sending raw file instead of OCR text.
     * 
     * @param file The image or PDF file
     * @return Extracted text via OCR, or error message
     */
    public String extractTextWithOcr(File file) {
        if (file == null || !file.exists() || file.isDirectory()) {
            return "";
        }

        String fileName = file.getName();
        String ext = getExtension(fileName);
        
        if (!isOcrSupported(fileName)) {
            log.warn("File type not supported for OCR: {}", fileName);
            return "[System: 该文件类型不支持 OCR]";
        }

        if (file.length() > maxFileSize()) {
            log.warn("File skipped due to size limit ({} > {}): {}", file.length(), maxFileSize(), file.getName());
            return "[System: 文件超过大小限制]";
        }

        try {
            if ("pdf".equals(ext)) {
                // PDF: 使用 PDFBox 渲染为图片后 OCR
                log.info("Starting PDF OCR for file: {} (size={} bytes)", fileName, file.length());
                String result = extractTextFromPdfWithOcr(file);
                log.info("Completed PDF OCR for file: {}. Result length: {}", fileName, result.length());
                return result;
            } else {
                // Image: 直接 OCR
                log.info("Starting Image OCR for file: {}", fileName);
                byte[] bytes = Files.readAllBytes(file.toPath());
                String base64 = Base64.getEncoder().encodeToString(bytes);
                OcrResult result = ocrService.recognizeGeneral(base64);
                log.info("Completed Image OCR for file: {}. Text length: {}", fileName, result.getText().length());
                return result.getText();
            }
        } catch (Exception e) {
            log.error("OCR extraction failed for: {}", fileName, e);
            return "[System: OCR 识别失败: " + e.getMessage() + "]";
        }
    }
    
    /**
     * Extract text from PDF by rendering pages to images and OCR each.
     * Uses Apache PDFBox for rendering.
     */
    private String extractTextFromPdfWithOcr(File pdfFile) throws Exception {
        StringBuilder allText = new StringBuilder();
        
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfFile)) {
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            
            // 限制最多处理 20 页，避免超大 PDF
            int maxPages = Math.min(pageCount, 20);
            
            for (int page = 0; page < maxPages; page++) {
                try {
                    // 渲染为 150 DPI 的图片（平衡质量和性能）
                    java.awt.image.BufferedImage image = renderer.renderImageWithDPI(page, 150);
                    
                    // 转为 PNG Base64
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(image, "png", baos);
                    String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                    
                    // OCR
                    OcrResult result = ocrService.recognizeGeneral(base64);
                    if (StringUtils.hasText(result.getText())) {
                        allText.append("--- 第 ").append(page + 1).append(" 页 ---\n");
                        allText.append(result.getText()).append("\n\n");
                    }
                } catch (Exception e) {
                    log.warn("Failed to OCR page {} of {}", page + 1, pdfFile.getName(), e);
                    allText.append("--- 第 ").append(page + 1).append(" 页 (OCR 失败) ---\n\n");
                }
            }
            
            if (pageCount > maxPages) {
                allText.append("[System: PDF 共 ").append(pageCount).append(" 页，仅处理前 ").append(maxPages).append(" 页]\n");
            }
        }
        
        return allText.toString();
    }

    public boolean isTextFile(String fileName) {
        if (!StringUtils.hasText(fileName)) return false;
        String ext = getExtension(fileName);
        return ALLOWED_TEXT_EXTENSIONS.contains(ext) || isConfigFile(fileName);
    }
    
    /**
     * Check if file is supported for OCR (images and PDF).
     */
    public boolean isOcrSupported(String fileName) {
        if (!StringUtils.hasText(fileName)) return false;
        String ext = getExtension(fileName);
        return contextProperties.getOcrExtensions().contains(ext);
    }

    /**
     * @deprecated Use isOcrSupported() for images/PDF. This method is kept for compatibility.
     */
    @Deprecated
    public boolean isSupportedBinary(String fileName) {
        return isOcrSupported(fileName);
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1).toLowerCase();
        }
        return "";
    }
    
    // Some files like Dockerfile makefile don't have extensions or strict conventions
    private boolean isConfigFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.equals("dockerfile") || lower.equals("makefile") || lower.equals("jenkinsfile");
    }
}
