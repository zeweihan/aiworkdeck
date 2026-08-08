package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 反馈附件：截图/上传图片（IMAGE）与语音（AUDIO）。
 *
 * <p>字节落在 {globalRoot}/feedback/{feedbackId}/ 下，库里只存相对文件名——
 * 与剪贴板附件同一套做法，换存储位置（StorageLocationService 迁移）时不用改库。
 */
@Entity
@Table(name = "feedback_attachment")
@Getter
@Setter
public class FeedbackAttachment {

    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_AUDIO = "AUDIO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "feedback_id", nullable = false)
    private Long feedbackId;

    /** IMAGE / AUDIO */
    @Column(name = "type", length = 16, nullable = false)
    private String type;

    /** 落盘文件名（不含目录，目录由 feedbackId 决定） */
    @Column(name = "stored_name", length = 256, nullable = false)
    private String storedName;

    @Column(name = "original_name", length = 256)
    private String originalName;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private long sizeBytes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
