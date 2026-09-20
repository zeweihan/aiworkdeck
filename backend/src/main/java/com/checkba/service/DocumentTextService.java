// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import java.util.Map;

/**
 * 文档全文抽取服务：docx/xlsx 等走 Apache Tika；PDF 走 PDFBox 3 原生 API。
 * 注意：不能用 Tika 解析 PDF——项目 classpath 是 PDFBox 3.0.1，而 Tika 2.9.x 的
 * PDFParser 依赖 PDFBox 2.x 的 PDDocument.load（3.x 已删除），运行时会
 * NoSuchMethodError。由 FileController（/text、/compare）与 AI 工具
 * extract_file_text 共用。
 */
@Service
public class DocumentTextService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentTextService.class);

    /**
     * 抽取结果的小容量 LRU（dev-board#729 ⑤）。
     *
     * <p>本机实测 {@code read_document} 中位 1128ms，而一轮对话里同一份文档常被读多次
     *（模型自己读一次、上下文组装注入一次、勾稽/审计工具再读一次），此前每次都重新跑
     * 一遍 Tika/PDFBox。条数刻意小：这不是持久缓存，只为了吃掉「同一轮里的重复抽取」。
     *
     * <p><b>不引 Caffeine</b>：全仓没有这个依赖，为 32 条缓存加一个新依赖不值当。
     */
    private static final int CACHE_MAX_ENTRIES = 32;

    /**
     * 超过这个长度的正文不进缓存：32 条 × 无上限 = 堆里随时可能躺着几百 MB 文本。
     * 200 万字符约 4MB（Java String 的 UTF-16），32 条封顶约 128MB 是上界，
     * 实际远达不到（单条到这个量级的文档极少）。
     */
    private static final int CACHE_MAX_TEXT_CHARS = 2_000_000;

    /**
     * 一条缓存。{@code lastModified}/{@code size} 取自 {@link Resource}（物理文件的 mtime 与长度），
     * <b>不是 project_file 表的 updatedAt</b>——编辑器保存、版本回退、插件写回都可能只动磁盘
     * 不动那一行，按 DB 时间戳判新旧会把改过的文档当成没改，喂给模型一份旧正文。
     */
    private record CachedText(long lastModified, long size, String text) {}

    private final Map<String, CachedText> textCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedText> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            });

    private final StorageServiceFactory storageServiceFactory;

    public DocumentTextService(StorageServiceFactory storageServiceFactory) {
        this.storageServiceFactory = storageServiceFactory;
    }

    /**
     * 抽取项目文件的纯文本内容。
     *
     * <p>按 (fileId 或路径, 物理文件 mtime, 长度) 命中小容量 LRU；mtime/长度取不到
     *（某些 Resource 实现不支持）时一律当缓存未命中处理，宁可重抽一次也不给出陈旧正文。
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
            String key = file.getId() != null ? "id:" + file.getId() : "path:" + filePath;
            long stampMtime = -1L;
            long stampSize = -1L;
            try {
                stampMtime = resource.lastModified();
                stampSize = resource.contentLength();
            } catch (Exception e) {
                // 拿不到指纹就不缓存（远端存储/特殊 Resource）：只是少一层加速
                log.debug("无法读取 {} 的 mtime/size，本次不走抽取缓存: {}", filePath, e.getMessage());
            }
            boolean cacheable = stampMtime > 0 && stampSize >= 0;
            if (cacheable) {
                CachedText hit = textCache.get(key);
                if (hit != null && hit.lastModified() == stampMtime && hit.size() == stampSize) {
                    return hit.text();
                }
            }
            String text;
            try (InputStream is = resource.getInputStream()) {
                text = isPdf ? parsePdf(is) : parse(is);
            }
            if (cacheable && text != null && text.length() <= CACHE_MAX_TEXT_CHARS) {
                textCache.put(key, new CachedText(stampMtime, stampSize, text));
            }
            return text;
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
