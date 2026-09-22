// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 项目文件的正文抽取结果缓存（dev-board#800）。
 *
 * <p>为什么要落库而不是只放内存：{@link com.checkba.service.DocumentTextService} 已有一份
 * 32 条的进程内 LRU，它只吃得掉「同一轮里的重复抽取」。真正贵的是<b>扫描件的云端 OCR</b>——
 * 一份 20 页的扫描件要 20 次网络识别、平台档按页扣 Credits，进程一重启就全部作废，
 * 用户重新打开同一个会话又要再付一遍。落库之后这份钱一份文件只花一次。
 * 文字层/Tika 的结果一并缓存：它们不花钱，但一份大 docx 的 Tika 解析也是几百毫秒，
 * 而附件正文是<b>每一轮都要重新注入</b>的（contextItems 由前端每轮重送）。
 *
 * <p><b>失效判据是物理文件的 mtime + size，不是 project_file 表的 updatedAt</b>：
 * 编辑器保存、版本回退、插件写回都可能只动磁盘不动那一行，按 DB 时间戳判新旧会把
 * 改过的文档当成没改，喂给模型一份旧正文。这条与 DocumentTextService 的内存 LRU 同源。
 *
 * <p>一个文件只保留一行（fileId 唯一）：缓存的是「这个文件当前版本的正文」，
 * 留历史版本没有消费方，只会让表无限长。{@link #source} 只作观测用，不进键。
 */
@Entity
@Table(name = "project_file_text_cache")
@Getter
@Setter
public class ProjectFileTextCache {

    /** 文字层 / Tika 抽出来的（不花钱）。 */
    public static final String SOURCE_TEXT = "text";
    /** 云端 OCR 识别出来的（平台档按页扣过 Credits，最值得缓存的一档）。 */
    public static final String SOURCE_OCR = "ocr";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** project_file.id，一个文件一行。 */
    @Column(name = "file_id", nullable = false, unique = true)
    private Long fileId;

    /** 物理文件的 mtime（毫秒）。与 size 一起构成失效判据。 */
    @Column(name = "file_mtime", nullable = false)
    private Long fileMtime;

    /** 物理文件字节数。 */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** {@link #SOURCE_TEXT} 或 {@link #SOURCE_OCR}。 */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /**
     * 抽取出的正文。
     *
     * <p>长文本不用 {@code @Lob}：PG 方言下 @Lob String 走 large-object（oid）读写会炸，
     * LONGVARCHAR + TEXT 在 H2（MODE=PostgreSQL）与 PG 双方言通吃（与 MeetingRecording 同源）。
     * MySQL 档的 TEXT 只有 64KB，写超长正文会被拒——所以写入失败一律只 log
     *（缓存写不进去只是慢一点，绝不能让抽取本身失败），读出来还要过 {@link #textChars} 校验。
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    /**
     * 写入时的正文字符数。
     *
     * <p>读回来长度对不上就当没缓存、重抽一遍：某些库/字段类型会静默截断长文本
     *（MySQL 非严格模式的 TEXT 就是），没有这道校验的话，被截断的半截正文会被
     * 当成完整原文喂给模型——一份合同少了后半段，而没有任何地方会报错。
     */
    @Column(name = "text_chars", nullable = false)
    private Integer textChars;

    /** 写入时间。总量超限时按它淘汰最旧的行。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectFileTextCache that = (ProjectFileTextCache) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
