// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条 AI 消息随身带走的一个附件（dev-board#793 K14 ④）。
 *
 * <p><b>为什么要有这张表</b>：附件正文是以 {@code <file>} 注入<b>当轮</b> system prompt 的，
 * 而 {@link ProjectAiMessage} 只有单列 content、历史回放只重建文本。于是「我上一轮发过哪几份材料」
 * 在刷新之后一个字都没有：模型看不到原文，也无从知道那份文件的 fileId
 *（那个 id 只出现在上一轮的 system prompt 里，不在对话历史里）。
 *
 * <p>本表按长期原则 3「数据模型先于 UI」先落库：本批用它在历史回灌时重建气泡下的附件 chip，
 * 将来做「后端自动回放历史附件/图片」时它就是现成的数据源。
 *
 * <p><b>只记事实，不记正文</b>：正文体量大、会随文件被编辑而过期，且已经有版本记录这条路。
 * 这里只记「哪条消息带了哪个文件、它是怎么被处理的」。
 */
@Entity
@Table(name = "project_ai_message_attachment", indexes = {
        // 唯一的查询形态：按一批 messageId 取附件（历史回灌）。没有它就是全表扫描。
        @Index(name = "idx_ai_attachment_message", columnList = "message_id")
})
@Data
public class ProjectAiMessageAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属消息行（{@code project_ai_message.id}）。 */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /**
     * 附件标识。**故意是字符串**：{@code ContextItem.id} 对项目文件是 Long 主键的字符串形式，
     * 而 Office 插件那一侧还可能带非数字的客户端标识——存成 Long 会在那条路上直接抛。
     */
    @Column(name = "file_id", length = 128)
    private String fileId;

    /** 附件的显示名。文件之后可能被改名或删除，chip 上要显示的是「当时发的那份叫什么」。 */
    @Column(name = "name", length = 512)
    private String name;

    /** {@code file} / {@code image} / {@code folder}。 */
    @Column(name = "kind", length = 16)
    private String kind;

    /**
     * 附件的 fileType（客户端自填、原样透传）。
     *
     * <p>要存它是因为「重新生成」要按这份记录**原样重建**那一轮的 contextItems：
     * 后端判图是「fileType 优先、缺失退回文件名后缀」的双判据，丢了 fileType 就丢了一半——
     * 一个 fileType="image" 但文件名没有扩展名的条目，重建之后两边都不命中，
     * 既不走视觉直送也不走 OCR。
     */
    @Column(name = "file_type", length = 64)
    private String fileType;

    /**
     * 这一条是不是作为图像内容块直送给模型了。
     *
     * <p>false 不等于「没看到」：图片降级后走的是 OCR 转写文本，模型读到的是文字不是图像。
     * 两者的可信度不一样（OCR 会认错数字），所以这件事要记下来。
     */
    @Column(name = "vision_used")
    private Boolean visionUsed;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
