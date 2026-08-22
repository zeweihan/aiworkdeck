package com.checkba.service.evidence;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.DocumentTextService;
import com.checkba.service.OcrService;
import com.checkba.service.ai.PlatformAiUserScope;
import com.checkba.service.ocr.OcrResult;
import com.checkba.storage.StorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * 底稿可读文本的唯一取法（勾稽核查专用，dev-board#116）：
 * 文档走 {@link DocumentTextService}（与 {@code extract_file_text} 同一条链路，
 * PDF 用 PDFBox 3、其余用 Tika），图片走既有 {@link OcrService}。
 *
 * <p><b>取不到就返回 null</b>——调用方据此判 {@code unverifiable}，不许猜、不许拿空串当"底稿里没有"。
 * 一切失败（文件没了、格式不支持、OCR 未配置、扫描件无文本层）都收敛成 null + 一条 WARN。
 *
 * <p>OCR 走平台网关按用户计费，所以要显式带 userId 进 {@link PlatformAiUserScope}：
 * 核查跑在超时保护的工作线程上，ThreadLocal 不会自己跟过去（PlatformAiUserScope 红线）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EvidenceTextExtractor {

    /** 与 PluginHostImpl.IMAGE_TYPES 同口径。 */
    static final Set<String> IMAGE_TYPES = Set.of("png", "jpg", "jpeg", "bmp", "gif", "webp", "tif", "tiff");
    /** 单份底稿最多取这么多字符：再长对四类要素的命中率没有帮助，只会把内存吃光。 */
    static final int MAX_CHARS = 200_000;
    /** 图片走 OCR 的体积上限（网关对超大图直接拒）。 */
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private final DocumentTextService documentTextService;
    private final OcrService ocrService;
    private final StorageServiceFactory storageServiceFactory;

    /** 取不到返回 null（文件夹、已删、无路径、格式不支持、抽取失败、抽出来是空白）。 */
    public String textOf(ProjectFile file, long userId) {
        if (file == null || Boolean.TRUE.equals(file.getIsFolder()) || Boolean.TRUE.equals(file.getIsDeleted())) {
            return null;
        }
        if (!StringUtils.hasText(file.getFilePath()) && !StringUtils.hasText(file.getWpsFileId())) {
            return null;
        }
        String type = StringUtils.hasText(file.getFileType())
                ? file.getFileType().toLowerCase(Locale.ROOT)
                : ext(file.getName());
        try {
            String text = IMAGE_TYPES.contains(type) ? ocr(file, userId) : documentTextService.extractText(file);
            if (!StringUtils.hasText(text)) return null;
            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        } catch (Exception e) {
            log.warn("勾稽核查取底稿文本失败 fileId={} name={}: {}", file.getId(), file.getName(), e.getMessage());
            return null;
        }
    }

    private String ocr(ProjectFile file, long userId) throws Exception {
        byte[] bytes;
        try (InputStream in = storageServiceFactory.getStorageService().load(file.getFilePath()).getInputStream()) {
            bytes = in.readAllBytes();
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) return null;
        String b64 = Base64.getEncoder().encodeToString(bytes);
        OcrResult r = PlatformAiUserScope.call(userId, () -> ocrService.recognizeGeneral(b64));
        return r == null ? null : r.getText();
    }

    private static String ext(String name) {
        if (!StringUtils.hasText(name)) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
