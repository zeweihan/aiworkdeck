package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户反馈（右下角浮窗提交的一条）。
 *
 * <p>这张表是「反馈驱动自我迭代」闭环的唯一入口：桌面端浮窗写入，优化者
 * （{@code service/optimizer}）按天/周读取 status=NEW 的行做分诊，再分流到
 * 「开 PR」或「发邮件」两条出口。
 *
 * <p><b>红线：行只改状态、绝不删除。</b>用户报过的问题永远留痕，
 * 分诊结论（triageJson）与出口（prUrl / 邮件时间）都回写到同一行，
 * 便于事后复盘「优化者当时凭什么这么判」。
 */
@Entity
@Table(name = "user_feedback")
@Getter
@Setter
public class UserFeedback {

    /** 用户自选类别：报障 / 建议。只是提交人的第一直觉，最终以优化者分诊为准。 */
    public static final String KIND_BUG = "BUG";
    public static final String KIND_IDEA = "IDEA";

    /** 待分诊 */
    public static final String STATUS_NEW = "NEW";
    /** 分诊判为 bug 且已开出 PR */
    public static final String STATUS_PR_OPENED = "PR_OPENED";
    /** 分诊判为建议/拿不准，已邮件给维护者 */
    public static final String STATUS_EMAILED = "EMAILED";
    /** 分诊判为无需处理（重复/无信息量） */
    public static final String STATUS_SKIPPED = "SKIPPED";
    /** 本轮处理出错，等下一轮重试 */
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 本机提交的原始反馈 */
    public static final String SOURCE_LOCAL = "LOCAL";
    /** 别的安装上传上来的（只出现在云端收件箱） */
    public static final String SOURCE_CLOUD = "CLOUD";

    /** 提交人（local-mode 下就是本机用户；云端收上来的没有用户，为 null） */
    @Column(name = "user_id")
    private Long userId;

    /** LOCAL / CLOUD */
    @Column(name = "source", length = 16)
    private String source;

    /** 上传方的匿名安装标识（source=CLOUD 时有值，与埋点用的是同一个 installId） */
    @Column(name = "install_id", length = 64)
    private String installId;

    /**
     * 上传方那条反馈在它自己库里的 id。与 installId 一起做幂等键——
     * 网络抖动导致的重传绝不能在云端变成两条。
     */
    @Column(name = "client_ref", length = 64)
    private String clientRef;

    /** 本机这条是否已上传到云端收件箱（source=LOCAL 才有意义） */
    @Column(name = "uploaded", nullable = false)
    private boolean uploaded;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    /** 提交时所在项目（可为空：解锁页/向导页也能反馈） */
    @Column(name = "project_id")
    private Long projectId;

    /** BUG / IDEA */
    @Column(name = "kind", length = 16, nullable = false)
    private String kind;

    /** 用户打的字 */
    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    /** 语音转写文本（未配置转写服务时为空，此时语音只作为附件留存） */
    @Column(name = "voice_transcript", columnDefinition = "TEXT")
    private String voiceTranscript;

    /** 提交时所在页面路由（如 pages/project-overview/project-overview） */
    @Column(name = "page", length = 256)
    private String page;

    /** 应用版本（服务端从 telemetry.app-version 填，客户端说了不算） */
    @Column(name = "app_version", length = 64)
    private String appVersion;

    /** 操作系统标识 */
    @Column(name = "platform", length = 128)
    private String platform;

    /** 采集到的上下文快照（JSON：客户端环境 + 最近前端报错 + 后端日志尾巴） */
    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    /** NEW / PR_OPENED / EMAILED / SKIPPED / FAILED */
    @Column(name = "status", length = 32, nullable = false)
    private String status;

    /** 优化者分诊结论原文（JSON），含 verdict/confidence/reason */
    @Column(name = "triage_json", columnDefinition = "TEXT")
    private String triageJson;

    /** 分诊判定：BUG / SUGGESTION / UNCLEAR / NOISE */
    @Column(name = "triage_verdict", length = 32)
    private String triageVerdict;

    /** 开出的 PR 地址（走「开 PR」出口时回填） */
    @Column(name = "pr_url", length = 512)
    private String prUrl;

    /** 本轮处理的失败原因（status=FAILED 时有值） */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** 优化者已处理的次数（防止一条坏反馈无限重试） */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;
}
