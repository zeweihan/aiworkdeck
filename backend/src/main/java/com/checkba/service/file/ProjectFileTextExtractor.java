// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.file;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.DocumentTextService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.FileContentExtractorService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 项目文件的纯文本抽取（dev-board#718，从 FileTools.extract_file_text 抽出）。
 *
 * <p>一条路由、三个使用方：AI 工具 {@code extract_file_text}、云端项目参考来源、桌面端参考读取。
 * 路由与 extract_file_text 一直以来的口径逐条相同（dev-board#396）：
 * 图片没有文字层，直接走云端 OCR；PDF 先抽文字层，抽不出（扫描件）才 OCR；其余格式走 Tika。
 * OCR 的失败以「[System: …]」形态返回（非空、无 Error 前缀），这里一律转成 {@link OcrFailedException}，
 * 绝不能被当成正文。
 *
 * <p><b>只有工具入口 {@link #extractText(ProjectFile)} 走 OCR。</b>参考入口
 * （{@link #extract}、{@link #extractBytes}）走到 OCR 分支时改为报 {@link #NEEDS_OCR}：
 * 平台代采档的 OCR 按页扣 Credits，而 legal/PRIVACY.md 对参考材料写的是「不产生 Credits 扣费」。
 *
 * <p>红线：只返回文字、不落盘（OCR 的临时文件用完即删）、日志只记 id 与异常，不记正文。
 */
@Service
@Slf4j
public class ProjectFileTextExtractor {

    /** 参考材料的单文件字节上限。 */
    public static final long MAX_BYTES = 50L * 1024 * 1024;

    static final String TOO_LARGE = "文件超过 50MB，暂不支持作为参考材料读取";
    static final String IS_FOLDER = "这是一个文件夹，不是文件，不能作为参考材料读取；请用 ref_list 找到其中的文件再读。";
    /**
     * 参考读取撞上「只能靠 OCR 才有文字」的文件时的回话。
     *
     * <p>OCR 走平台代采档时是按页扣 Credits 的（OcrService.recognizeViaPlatform），
     * 而 legal/PRIVACY.md 对参考材料的承诺是「不产生 Credits 扣费」。承诺在先，
     * 这条路就不能默默扣钱——宁可说清楚，让用户自己决定要不要去工作台做识别。
     */
    static final String NEEDS_OCR = "这份文件没有可直接提取的文字（扫描件或图片）。"
            + "参考读取不做文字识别，请让用户在 AI WorkDeck 工作台里对它做一次文字识别后再引用。";

    /** 不在项目文件表里的字节（案件库、git）：这几种按 UTF-8 直接解码，不交给 Tika 猜字符集。 */
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "json");

    private final DocumentTextService documentTextService;
    private final FileContentExtractorService fileContentExtractorService;
    private final ProjectFileService projectFileService;

    public ProjectFileTextExtractor(DocumentTextService documentTextService,
                                    FileContentExtractorService fileContentExtractorService,
                                    ProjectFileService projectFileService) {
        this.documentTextService = documentTextService;
        this.fileContentExtractorService = fileContentExtractorService;
        this.projectFileService = projectFileService;
    }

    /**
     * 参考材料入口：文件夹与超过 50MB 的文件在读字节之前就拒绝，其余同 {@link #extractText}，
     * 但<b>不走 OCR</b>（见 {@link #NEEDS_OCR}）。
     *
     * @return 抽出的文字，可能为空串（文件没有可读的文字）
     * @throws IOException message 是可直接转述给用户的原因
     */
    public String extract(ProjectFile pf) throws IOException {
        if (isFolder(pf)) {
            throw new IOException(IS_FOLDER);
        }
        if (pf.getFileSize() != null && pf.getFileSize() > MAX_BYTES) {
            throw new IOException(TOO_LARGE);
        }
        return extractText(pf, false);
    }

    /**
     * 与 extract_file_text 完全相同的路由，不设大小闸（该工具一直没有 50MB 上限，维持原行为）。
     * 这条是<b>工具</b>入口，OCR 照走照扣——用户是显式让 AI 去识别这份文件的。
     *
     * @return 抽出的文字，可能为空串
     * @throws OcrFailedException OCR 分支失败（含底层原因）
     * @throws IOException        文字层抽取失败
     */
    public String extractText(ProjectFile pf) throws IOException {
        return extractText(pf, true);
    }

    private String extractText(ProjectFile pf, boolean allowOcr) throws IOException {
        String name = pf.getName();
        boolean ocrSupported = isOcrSupported(name);
        String text = ocrSupported && !isPdf(name, pf.getFileType())
                ? null
                : extractTextLayer(pf);
        if (!StringUtils.hasText(text) && ocrSupported) {
            if (!allowOcr) {
                throw new IOException(NEEDS_OCR);
            }
            text = ocrProjectFile(pf);
        }
        return text == null ? "" : text;
    }

    /**
     * 不在项目文件表里的字节（官方案件库、GitHub/Gitee）：txt/md/csv/json 按 UTF-8 解码；
     * PDF 走 PDFBox；其余走 Tika。只有这两个参考来源在用，所以与 {@link #extract} 同口径
     * <b>不走 OCR</b>（见 {@link #NEEDS_OCR}）。
     *
     * @param fileName 文件名或路径，只用于判断扩展名
     */
    public String extractBytes(String fileName, byte[] bytes) throws IOException {
        if (bytes == null) {
            return "";
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException(TOO_LARGE);
        }
        String ext = extension(fileName);
        if (PLAIN_TEXT_EXTENSIONS.contains(ext)) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.startsWith("﻿") ? text.substring(1) : text;
        }
        boolean pdf = "pdf".equals(ext);
        boolean ocrSupported = isOcrSupported(fileName);
        String text = null;
        if (!ocrSupported || pdf) {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                text = pdf ? documentTextService.parsePdf(in) : documentTextService.parse(in);
            } catch (TikaException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        if (!StringUtils.hasText(text) && ocrSupported) {
            throw new IOException(NEEDS_OCR);
        }
        return text == null ? "" : text;
    }

    public boolean isOcrSupported(String fileName) {
        return fileContentExtractorService.isOcrSupported(fileName);
    }

    private String extractTextLayer(ProjectFile pf) throws IOException {
        try {
            return documentTextService.extractText(pf);
        } catch (TikaException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private String ocrProjectFile(ProjectFile pf) throws OcrFailedException {
        byte[] bytes;
        try {
            bytes = projectFileService.getFileBytes(pf.getId());
        } catch (Exception e) {
            log.warn("OCR extraction failed for fileId={}", pf.getId(), e);
            throw new OcrFailedException("cloud OCR failed on '" + pf.getName() + "' — " + e.getMessage(), e);
        }
        if (bytes == null || bytes.length == 0) {
            throw new OcrFailedException("file '" + pf.getName() + "' is empty on disk, nothing to recognise.", null);
        }
        return ocr(pf.getName(), ocrTempSuffix(pf), bytes, "fileId=" + pf.getId());
    }

    /**
     * 走与 {@code read_file} 相同的 OCR 分支（extractTextWithOcr → OcrService）。
     * 临时文件必须保留原扩展名：extractTextWithOcr 按文件名判断走图片还是 PDF 分支。
     */
    private String ocr(String name, String suffix, byte[] bytes, String logKey) throws OcrFailedException {
        Path tempPath = null;
        try {
            tempPath = Files.createTempFile("checkba_ocr_", suffix);
            Files.write(tempPath, bytes);
            String result = fileContentExtractorService.extractTextWithOcr(tempPath.toFile());
            // extractTextWithOcr 的失败以 "[System: ...]" 形态返回（非空、无 Error 前缀），
            // 直接透传会被判成成功并当作正文喂给模型
            if (result != null && result.startsWith("[System:")) {
                throw new OcrFailedException("cloud OCR failed on '" + name + "' — "
                        + result.substring("[System:".length()).replace("]", "").trim(), null);
            }
            return result == null ? "" : result;
        } catch (OcrFailedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OCR extraction failed for {}", logKey, e);
            throw new OcrFailedException("cloud OCR failed on '" + name + "' — " + e.getMessage(), e);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (Exception ignore) {
                    // 临时目录会被系统回收，删不掉不影响结果
                }
            }
        }
    }

    private static boolean isFolder(ProjectFile pf) {
        return "folder".equalsIgnoreCase(pf.getFileType()) || Boolean.TRUE.equals(pf.getIsFolder());
    }

    /** PDF 可能带文字层，值得先抽一次；其余 OCR 格式（jpg/png/...）没有文字层，抽也是空。 */
    private static boolean isPdf(String name, String fileType) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return "pdf".equalsIgnoreCase(fileType) || lower.endsWith(".pdf");
    }

    private static String ocrTempSuffix(ProjectFile pf) {
        String name = pf.getName() == null ? "" : pf.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot);
        }
        return StringUtils.hasText(pf.getFileType()) ? "." + pf.getFileType() : ".tmp";
    }

    /** 取最后一段路径的扩展名（小写，无点）；没有扩展名返回空串。 */
    private static String extension(String fileName) {
        String name = displayName(fileName);
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String displayName(String fileName) {
        String name = fileName == null ? "" : fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    /**
     * OCR 分支的失败：message 保持 extract_file_text 一直以来的英文措辞（该工具前面拼 "Error: " 即原样），
     * 底层原因（Credits 不足、OCR 未开放、上游报错）原样带出，模型才能转述真实原因。
     */
    public static class OcrFailedException extends IOException {
        public OcrFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
