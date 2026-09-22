// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.context;

import com.checkba.config.AiContextProperties;
import com.checkba.service.OcrService;
import com.checkba.service.file.PdfTextLayer;
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
     * 图片与 PDF 的正文抽取。
     *
     * <p>图片没有文字层，直接云端 OCR；<b>PDF 先抽文字层，抽不出（扫描件）才 OCR</b>
     *（dev-board#800，判据见 {@link PdfTextLayer}）。多模态模型优先直送原图，见
     * {@code ContextAssemblerService} 的视觉通道。
     *
     * @param file 图片或 PDF
     * @return 抽出的正文；失败以「[System: …]」形态返回（<b>非空、无 Error 前缀</b>，
     *         调用方必须自己判，直接透传会被当成正文喂给模型）
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
                // PDF：先 PDFBox 抽文字层，抽不出才逐页渲染 + OCR
                log.info("Extracting PDF text for file: {} (size={} bytes)", fileName, file.length());
                String result = extractTextFromPdfWithOcr(file);
                log.info("Completed PDF extraction for file: {}. Result length: {}", fileName, result.length());
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
     * PDF 抽取：<b>先看文字层，抽不出才逐页渲染 + 云端 OCR</b>（dev-board#800）。
     *
     * <p>改动前这里是无条件 OCR——绝大多数合同、裁判文书、招股书都带完整文字层，
     * 却被逐页渲染成 150DPI 的 PNG 送去识别：慢一到两个数量级、平台档按页扣 Credits、
     * 识别误差还专挑法律文书最怕的数字与主体名，而且只看前 20 页。
     * 「够不够用」的判据是 {@link PdfTextLayer#isUsable}，与 ProjectFileTextExtractor 同一份。
     *
     * <p>文字层与 OCR 共用同一次 {@code Loader.loadPDF}：扫描件上多跑一次 PDFTextStripper
     * 的代价是毫秒级（没有文字层可抽），换掉的是整条 OCR 链路。
     */
    private String extractTextFromPdfWithOcr(File pdfFile) throws Exception {
        StringBuilder allText = new StringBuilder();

        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfFile)) {
            String textLayer = readTextLayer(document, pdfFile.getName());
            if (PdfTextLayer.isUsable(textLayer)) {
                log.info("PDF {} has a usable text layer ({} chars), skipping OCR entirely",
                        pdfFile.getName(), textLayer.length());
                return textLayer;
            }

            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            int pageCount = document.getNumberOfPages();

            // 只有 OCR 路径有页数上限：按页花时间、按页花钱，几百页的扫描件能把一轮对话拖死
            int maxPages = Math.min(pageCount, Math.max(1, contextProperties.getOcrMaxPdfPages()));

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
                // 明写「识别」两个字：模型极易把「我看到的就是全部」当成事实，
                // 而这份正文既是转写（可能有识别误差）、又只是前 M 页
                allText.append("[System: 这是没有文字层的扫描件，以上正文由文字识别（OCR）得到，可能有识别误差；")
                       .append("该 PDF 共 ").append(pageCount).append(" 页，仅识别前 ").append(maxPages)
                       .append(" 页，第 ").append(maxPages + 1).append(" 页及其后的内容不在上文中，不要当作全文。]\n");
            } else if (allText.length() > 0) {
                allText.append("[System: 这是没有文字层的扫描件，以上正文由文字识别（OCR）得到，可能有识别误差。]\n");
            }
        }

        return allText.toString();
    }

    /** PDF 自带的文字层；抽不出（扫描件）或解析失败都返回空串，由调用方决定走不走 OCR。 */
    private String readTextLayer(org.apache.pdfbox.pdmodel.PDDocument document, String fileName) {
        try {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            return text == null ? "" : text;
        } catch (Exception e) {
            log.warn("Failed to read the text layer of {}, falling back to OCR: {}", fileName, e.toString());
            return "";
        }
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
