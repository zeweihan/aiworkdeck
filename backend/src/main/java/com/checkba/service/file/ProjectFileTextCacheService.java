// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.file;

import com.checkba.config.AiContextProperties;
import com.checkba.model.entity.ProjectFileTextCache;
import com.checkba.repository.ProjectFileTextCacheRepository;
import com.checkba.service.DocumentTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 正文抽取结果的跨重启缓存（dev-board#800）。
 *
 * <p>读侧唯一入口 {@link #find}、写侧唯一入口 {@link #store}。使用方：
 * {@code read_document}（含活跃文档注入与附件注入）、{@code extract_file_text}、
 * 参考材料读取、文件夹上下文——它们在此之前各自重抽，同一份 PDF 在一轮对话里被抽几遍。
 *
 * <p><b>全部失败都只 log，绝不抛</b>：缓存是加速层，它坏掉的正确表现是「慢一点」，
 * 而不是「读不出文件」。库里没这张表、字段被截断、并发写撞唯一键，一律按未命中处理。
 */
@Service
@Slf4j
public class ProjectFileTextCacheService {

    private final ProjectFileTextCacheRepository repository;
    private final AiContextProperties contextProperties;

    public ProjectFileTextCacheService(ProjectFileTextCacheRepository repository,
                                       AiContextProperties contextProperties) {
        this.repository = repository;
        this.contextProperties = contextProperties;
    }

    /**
     * 取缓存正文。
     *
     * @param stamp 物理文件的 mtime/size；为 null（远端存储等拿不到指纹的场景）一律未命中
     * @return 命中的正文，未命中返回 null
     */
    public String find(Long fileId, DocumentTextService.FileStamp stamp) {
        AiContextProperties.TextCache cfg = contextProperties.getTextCache();
        if (!cfg.isEnabled() || fileId == null || stamp == null) {
            return null;
        }
        try {
            ProjectFileTextCache row = repository.findByFileId(fileId).orElse(null);
            if (row == null) {
                return null;
            }
            if (!equalsLong(row.getFileMtime(), stamp.lastModified())
                    || !equalsLong(row.getFileSize(), stamp.size())) {
                // 文件改过了：这一行已经没用，就地删掉，省得它一直占着唯一键等 upsert
                repository.delete(row);
                return null;
            }
            String text = row.getTextContent();
            if (text == null) {
                return null;
            }
            if (row.getTextChars() != null && row.getTextChars() != text.length()) {
                // 写进去 N 个字符、读回来不是 N：库把长文本截断了（MySQL 的 TEXT 只有 64KB）。
                // 半截正文当完整原文喂给模型是最坏的一种坏法，宁可重抽。
                log.warn("Text cache row for fileId={} came back truncated ({} != {}), re-extracting",
                        fileId, text.length(), row.getTextChars());
                repository.delete(row);
                return null;
            }
            return text;
        } catch (Exception e) {
            log.debug("Text cache lookup failed for fileId={}: {}", fileId, e.toString());
            return null;
        }
    }

    /**
     * 落库。空正文不缓存（「这次没抽出来」不是结论，下次换条路可能就抽出来了），
     * 超过单条上限的也不缓存（理由见 {@link AiContextProperties.TextCache#getMaxTextChars()}）。
     *
     * @param source {@link ProjectFileTextCache#SOURCE_TEXT} / {@link ProjectFileTextCache#SOURCE_OCR}
     */
    public void store(Long fileId, DocumentTextService.FileStamp stamp, String source, String text) {
        AiContextProperties.TextCache cfg = contextProperties.getTextCache();
        if (!cfg.isEnabled() || fileId == null || stamp == null || text == null || text.isEmpty()) {
            return;
        }
        if (text.length() > cfg.getMaxTextChars()) {
            log.debug("Text cache skipped for fileId={}: {} chars over the {} limit",
                    fileId, text.length(), cfg.getMaxTextChars());
            return;
        }
        try {
            ProjectFileTextCache row = repository.findByFileId(fileId).orElseGet(ProjectFileTextCache::new);
            row.setFileId(fileId);
            row.setFileMtime(stamp.lastModified());
            row.setFileSize(stamp.size());
            row.setSource(source);
            row.setTextContent(text);
            row.setTextChars(text.length());
            row.setCreatedAt(LocalDateTime.now());
            repository.save(row);
            pruneIfOverCapacity(cfg.getMaxEntries());
        } catch (Exception e) {
            // 唯一键并发冲突、字段超长、表不存在……缓存写不进去只是下次再抽一遍
            log.debug("Text cache store failed for fileId={}: {}", fileId, e.toString());
        }
    }

    /** 文件被删除/覆盖时主动摘掉（调用方可选；不调也会被 mtime 判据自然失效）。 */
    public void evict(Long fileId) {
        if (fileId == null) {
            return;
        }
        try {
            repository.deleteByFileId(fileId);
        } catch (Exception e) {
            log.debug("Text cache evict failed for fileId={}: {}", fileId, e.toString());
        }
    }

    private void pruneIfOverCapacity(int maxEntries) {
        long count = repository.count();
        if (count <= maxEntries) {
            return;
        }
        int over = (int) Math.min(count - maxEntries, 500);
        List<ProjectFileTextCache> oldest = repository.findOldest(PageRequest.of(0, over));
        if (!oldest.isEmpty()) {
            repository.deleteAll(oldest);
            log.info("Text cache pruned {} oldest entries (was {}, cap {})", oldest.size(), count, maxEntries);
        }
    }

    private static boolean equalsLong(Long a, long b) {
        return a != null && a == b;
    }
}
