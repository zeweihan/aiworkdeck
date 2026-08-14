package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 会议录音：一次「点录音 → 转写（说话人分离）→ 生成纪要」的完整生命周期。
 * 音频本体是项目文件树里的 ProjectFile（边录边分片追加上传），本表只存元数据与转写结果。
 */
@Data
@Entity
@Table(name = "meeting_recording")
public class MeetingRecording {

    /** 状态流转（只前进不回退，FAILED 可经 transcribe 重回 TRANSCRIBING） */
    public static final String STATUS_RECORDING = "RECORDING";
    public static final String STATUS_RECORDED = "RECORDED";
    public static final String STATUS_TRANSCRIBING = "TRANSCRIBING";
    public static final String STATUS_TRANSCRIBED = "TRANSCRIBED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    /** 标题，默认「会议 MM-DD HH:mm」，事后可改 */
    @Column(length = 256, nullable = false)
    private String title;

    @Column(length = 32, nullable = false)
    private String status = STATUS_RECORDING;

    /** 录音音频在项目文件树里的 ProjectFile ID */
    private Long audioFileId;

    /** 录音时长（毫秒），结束录音时由前端回报 */
    private Long durationMs;

    /** 听悟异步任务 ID（TRANSCRIBING 期间轮询用） */
    @Column(length = 128)
    private String tingwuTaskId;

    /** 上一次向听悟查询任务状态的时间（poll-on-read 的节流锚点） */
    private LocalDateTime lastPolledAt;

    /**
     * 转写结果（压缩后的段落 JSON 数组）：
     * [{"speaker":"1","start":毫秒,"end":毫秒,"text":"..."}]
     * speaker 是听悟的说话人编号字符串，展示名经 speakerNames 映射。
     */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String transcriptJson;

    /** 说话人改名映射 JSON：{"1":"张律师","2":"对方代理人"}，未改名的用默认「说话人N」 */
    @Column(length = 2048)
    private String speakerNames;

    /**
     * 听悟增值结果 JSON：{"chapters":[...],"summary":"...","todos":[...],"keywords":[...]}
     * 作为纪要生成的素材，缺失不影响主流程。
     */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String summaryJson;

    /** 最近一次失败原因（FAILED 态展示与排障用） */
    @Column(length = 1024)
    private String error;

    @Column(nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
