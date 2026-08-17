package com.checkba.service.meeting;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.LangText;
import com.checkba.service.SystemSettingService;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
import com.checkba.service.platform.GatewayException;
import com.checkba.service.platform.PlatformGatewayClient;
import com.checkba.storage.ProjectStorageResolver;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 转写编排。三档，<b>分档发生在这一层</b>：
 *
 * <ul>
 *   <li><b>byok</b>：音频转码 → 自己的 OSS 中转 → 自己的听悟凭证建任务 → poll-on-read 收结果。
 *   <li><b>platform</b>：音频转码 → 向网关换直传凭证 → 普通 PUT 直传 → 网关建任务并预扣 Credits
 *       → poll-on-read 让网关按听悟返回的真实时长结算。
 *   <li><b>local</b>：音频转码 → 本机 asr-service 直接转写。<b>一个字节都不出本机</b>，
 *       也没有 taskId——整段转写就在后台执行器里跑完（见 {@link #inFlight}）。
 * </ul>
 *
 * <p>platform 档<b>完全不碰 {@link MeetingOssClient} 与 {@link TingwuClient}</b>，
 * 那两个接口的实现一字未动、只服务 byok 档。不在接口内部分档是因为三条路的失败语义
 * 完全不同：byok 失败是「你的凭证/网络有问题」，platform 失败还要区分「余额不够」
 * 「服务未开放」「我们的服务器挂了」，local 失败是「组件没装好」，
 * 塞进同一个实现里，错误分类就再也拆不开。
 *
 * <p><b>本地档失败绝不回落云端。</b>用户打开「录音不出本机」的唯一理由就是这段谈话
 * 绝对不能出网，悄悄传上云正好背叛他打开开关的目的。失败只落一条说清「什么没就绪、
 * 下一步做什么」的 error。
 *
 * <p>提交在单线程后台执行器里跑（转码+上传一场两小时的会要分钟级），
 * 状态推进靠前端轮询 GET 触发 {@link #refreshIfNeeded}——没有常驻调度器，
 * 面板关了任务就停在上游，下次打开面板继续收，代价可接受、代码最少。
 */
@Slf4j
@Service
public class MeetingTranscriptionService {

    /** 凭证键（system_setting 表，管理页可改；env/yml 只作默认值） */
    public static final String KEY_ACCESS_KEY_ID = "meeting.asr.access-key-id";
    public static final String KEY_ACCESS_KEY_SECRET = "meeting.asr.access-key-secret";
    public static final String KEY_APP_KEY = "meeting.asr.app-key";
    public static final String KEY_OSS_BUCKET = "meeting.oss.bucket";
    public static final String KEY_OSS_ENDPOINT = "meeting.oss.endpoint";

    /** poll-on-read 节流：距上次查上游不足该秒数时直接返回现状 */
    private static final int POLL_THROTTLE_SECONDS = 10;
    /** 签名 URL 有效期。听悟要求 >= 3 小时，给 4 小时留余量 */
    private static final Duration URL_TTL = Duration.ofHours(4);

    /**
     * 网关调用的超时，<b>按端点给</b>。不沿用账户通道那 5 秒：
     * 建听悟任务本身要跟阿里云打一个来回，5 秒内返回不了是常态。
     */
    private static final int TICKET_TIMEOUT_SECONDS = 15;
    private static final int SUBMIT_TIMEOUT_SECONDS = 30;
    private static final int TASK_TIMEOUT_SECONDS = 15;

    /** 结果文件下载的接缝（测试桩用） */
    public interface UrlFetcher {
        String fetch(String url) throws Exception;
    }

    /**
     * 直传的接缝（测试桩用）。platform 档只做一次普通 HTTP PUT，
     * <b>不把 OSS SDK 引到这条路上</b>——签名是官网签好的，客户端这边只是发字节。
     */
    public interface BinaryUploader {
        void put(String url, File file, String contentType) throws Exception;
    }

    private final MeetingRecordingRepository meetingRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectStorageResolver storageResolver;
    private final SystemSettingService systemSettingService;
    private final MeetingAudioTranscoder transcoder;
    private final TingwuClient tingwuClient;
    private final MeetingOssClient ossClient;
    private final ExternalProviderResolver externalProviderResolver;
    private final PlatformGatewayClient platformGatewayClient;
    private final LocalAsrClient localAsrClient;
    private final UrlFetcher urlFetcher;
    private final BinaryUploader uploader;
    private final String defaultAccessKeyId;
    private final String defaultAccessKeySecret;
    private final String defaultAppKey;
    private final String defaultOssBucket;
    private final String defaultOssEndpoint;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "meeting-transcribe");
        t.setDaemon(true);
        return t;
    });

    /**
     * 本 JVM 里还在后台跑的转写（转码/上传/本地推理都算）。
     *
     * <p>用来把「正跑着」与「上次运行被关机打断了」区分开。本地档必须有它：
     * 那一档从头到尾没有 taskId，整段推理都落在这个窗口里；没有它，
     * 一次关机就会让会议<b>永远</b>停在「转写中」——而 {@link #startTranscription}
     * 对 TRANSCRIBING 是幂等返回，用户连「重试转写」都点不动。
     * 云端两档的转码上传阶段同样在这个窗口内（那是本改造之前就有的同一个缺口）。
     */
    private final java.util.Set<Long> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired
    public MeetingTranscriptionService(
            MeetingRecordingRepository meetingRepository,
            ProjectFileRepository projectFileRepository,
            ProjectStorageResolver storageResolver,
            SystemSettingService systemSettingService,
            MeetingAudioTranscoder transcoder,
            TingwuClient tingwuClient,
            MeetingOssClient ossClient,
            ExternalProviderResolver externalProviderResolver,
            PlatformGatewayClient platformGatewayClient,
            LocalAsrClient localAsrClient,
            @Value("${meeting.asr.access-key-id:}") String defaultAccessKeyId,
            @Value("${meeting.asr.access-key-secret:}") String defaultAccessKeySecret,
            @Value("${meeting.asr.app-key:}") String defaultAppKey,
            @Value("${meeting.oss.bucket:}") String defaultOssBucket,
            @Value("${meeting.oss.endpoint:}") String defaultOssEndpoint) {
        this(meetingRepository, projectFileRepository, storageResolver, systemSettingService,
                transcoder, tingwuClient, ossClient, externalProviderResolver, platformGatewayClient,
                localAsrClient, defaultUrlFetcher(), defaultUploader(),
                defaultAccessKeyId, defaultAccessKeySecret, defaultAppKey, defaultOssBucket, defaultOssEndpoint);
    }

    MeetingTranscriptionService(
            MeetingRecordingRepository meetingRepository,
            ProjectFileRepository projectFileRepository,
            ProjectStorageResolver storageResolver,
            SystemSettingService systemSettingService,
            MeetingAudioTranscoder transcoder,
            TingwuClient tingwuClient,
            MeetingOssClient ossClient,
            ExternalProviderResolver externalProviderResolver,
            PlatformGatewayClient platformGatewayClient,
            LocalAsrClient localAsrClient,
            UrlFetcher urlFetcher,
            BinaryUploader uploader,
            String defaultAccessKeyId,
            String defaultAccessKeySecret,
            String defaultAppKey,
            String defaultOssBucket,
            String defaultOssEndpoint) {
        this.meetingRepository = meetingRepository;
        this.projectFileRepository = projectFileRepository;
        this.storageResolver = storageResolver;
        this.systemSettingService = systemSettingService;
        this.transcoder = transcoder;
        this.tingwuClient = tingwuClient;
        this.ossClient = ossClient;
        this.externalProviderResolver = externalProviderResolver;
        this.platformGatewayClient = platformGatewayClient;
        this.localAsrClient = localAsrClient;
        this.urlFetcher = urlFetcher;
        this.uploader = uploader;
        this.defaultAccessKeyId = defaultAccessKeyId;
        this.defaultAccessKeySecret = defaultAccessKeySecret;
        this.defaultAppKey = defaultAppKey;
        this.defaultOssBucket = defaultOssBucket;
        this.defaultOssEndpoint = defaultOssEndpoint;
    }

    private static UrlFetcher defaultUrlFetcher() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return url -> {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("结果下载失败 HTTP " + resp.statusCode());
            }
            return resp.body();
        };
    }

    private static BinaryUploader defaultUploader() {
        HttpClient client = HttpClient.newBuilder()
                // 固定 HTTP/1.1：与 HttpAccountTransport / HttpPlatformGatewayTransport 同一理由
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return (url, file, contentType) -> {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            // 直传的是几十到几百 MB，超时按整段上传给，不能按一次 API 往返给
                            .timeout(Duration.ofMinutes(30))
                            // Content-Type 进了 OSS 的签名，必须逐字用官网下发的那个值
                            .header("Content-Type", contentType)
                            .PUT(HttpRequest.BodyPublishers.ofFile(file.toPath()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("音频上传失败 HTTP " + resp.statusCode());
            }
        };
    }

    public MeetingAsrSettings loadSettings() {
        return new MeetingAsrSettings(
                systemSettingService.get(KEY_ACCESS_KEY_ID, defaultAccessKeyId),
                systemSettingService.get(KEY_ACCESS_KEY_SECRET, defaultAccessKeySecret),
                systemSettingService.get(KEY_APP_KEY, defaultAppKey),
                systemSettingService.get(KEY_OSS_BUCKET, defaultOssBucket),
                systemSettingService.get(KEY_OSS_ENDPOINT, defaultOssEndpoint));
    }

    /** 本服务当前走哪一档。非 local-mode 恒 BYOK，闸在 resolver 一处（设计决策 D5）。 */
    private ExternalServiceProvider tier() {
        return externalProviderResolver.resolve(ExternalServiceProvider.ASR);
    }

    /**
     * 「转写能不能用」。<b>判据按档位分</b>：
     * platform 档只要连了账户就算配好（那 5 个阿里云凭证是我们出的）；
     * byok 档仍然要求用户自己那 5 项齐全；
     * local 档要求 asr-service 起着<b>且</b>模型已下载——只起了服务不算，
     * 那样用户会在按下录音键之后才发现转不了。
     */
    public boolean isConfigured() {
        return switch (tier()) {
            case LOCAL -> localAsrClient.probe().ready();
            case PLATFORM -> platformGatewayClient.connected();
            case BYOK -> loadSettings().configured();
        };
    }

    /**
     * platform 档下这台机器还欠一次录音处理方式的告知。
     *
     * <h3>为什么这道闸在服务端，而且只在 platform 档</h3>
     * 真正把录音交出去的那一刻不是用户点「开始转写」，而是 {@code POST /meetings/{id}/finish}
     * <b>自动</b>提交的那一下——常见路径里用户在上传时没有任何动作。界面上的那块告知
     * 只挡得住走界面的人；<b>任何绕过面板的调用方（崩溃恢复补刀、未来的命令/插件）
     * 都能不经告知就把一场会议传上来</b>，而被录的是第三方，他不是我们的用户，
     * 没同意过任何条款。所以提交这条缝上也要查一次。
     *
     * <h3>为什么它不是「每次硬闸」</h3>
     * 确认过一次就永远为 false（除非告知改版），用户一辈子只会撞到它一次；
     * 而正常路径下他在录音开始之前就确认过了，根本撞不到。
     * 会被拦住的只有「从没看过告知、却有东西替他上传」这一种情况——那正是要拦的。
     * byok 档走用户自己的 OSS 与听悟账号、local 档不出本机，两者都没有要告知的事。
     */
    public boolean recordingNoticePending() {
        return tier() == ExternalServiceProvider.PLATFORM
                && !MeetingRecordingNotice.acknowledged(systemSettingService);
    }

    /** 不含「登录」「未授权」「请先」——api.js 拿这三个子串判掉线并清会话。 */
    private String noticePendingMessage() {
        return LangText.of(
                "这段录音需要确认一次录音处理方式：到会议录音面板看一下「录音转写告知」，"
                        + "确认后即可转写；不想让音频出本机的话，打开「录音不出本机」改用本机转写。",
                "This recording needs a one-time acknowledgement of how recordings are handled: "
                        + "open the meeting panel, read the transcription notice and confirm it. "
                        + "If the audio must not leave this machine, turn on \"Keep recordings on this machine\" instead.");
    }

    /**
     * 提交转写。同步只做状态置位（TRANSCRIBING），耗时步骤进后台执行器。
     * RECORDED / FAILED 可提交；TRANSCRIBING/TRANSCRIBED 幂等返回。
     */
    public MeetingRecording startTranscription(Long meetingId) {
        MeetingRecording meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        LangText.of("会议不存在: ", "Meeting not found: ") + meetingId));
        if (MeetingRecording.STATUS_TRANSCRIBING.equals(meeting.getStatus())
                || MeetingRecording.STATUS_TRANSCRIBED.equals(meeting.getStatus())) {
            return meeting;
        }
        // IllegalArgumentException：GlobalExceptionHandler 只对它透传 message，其余异常一律「服务器内部错误」
        if (MeetingRecording.STATUS_RECORDING.equals(meeting.getStatus())) {
            throw new IllegalArgumentException(LangText.of(
                    "录音尚未结束", "The recording has not finished yet"));
        }

        ExternalServiceProvider tier = tier();
        MeetingAsrSettings settings = tier == ExternalServiceProvider.BYOK ? loadSettings() : null;
        switch (tier) {
            case LOCAL -> {
                LocalAsrClient.ProbeResult probe = localAsrClient.probe();
                if (!probe.ready()) {
                    // 就地说清「什么没就绪、下一步做什么」。**不回落云端**：
                    // 悄悄把音频传上去正好背叛用户打开「录音不出本机」的目的。
                    throw new IllegalArgumentException(probe.message() + " " + probe.nextStep());
                }
            }
            case PLATFORM -> {
                if (!platformGatewayClient.connected()) {
                    // 文案不含「登录」「未授权」「请先」——api.js 拿这三个子串判掉线并清会话。
                    // 也一并给出自备 Key 这条出路：用试用码解锁、不打算连账户的用户只有它。
                    throw new IllegalArgumentException(LangText.of(
                            "平台转写需要连接 AI WorkDeck 账户；也可到 系统管理-会议转写 改用自己的阿里云凭证",
                            "Platform transcription needs a connected AI WorkDeck account; "
                                    + "or switch to your own Aliyun credentials in System settings"));
                }
                if (recordingNoticePending()) {
                    throw new IllegalArgumentException(noticePendingMessage());
                }
            }
            case BYOK -> {
                if (!settings.configured()) {
                    throw new IllegalArgumentException(LangText.of(
                            "未配置转写服务凭证，请到 设置-会议转写 填写阿里云凭证",
                            "No transcription credentials configured. Enter your Aliyun credentials "
                                    + "under System settings - Platform Services."));
                }
            }
        }

        meeting.setStatus(MeetingRecording.STATUS_TRANSCRIBING);
        meeting.setError(null);
        meeting.setTingwuTaskId(null);
        meeting.setGatewayTaskId(null);
        // 先登记再落库：中间那一瞬前端刚好来轮询的话，没有这一步会被判成「上次被打断」
        inFlight.add(meetingId);
        MeetingRecording saved = meetingRepository.save(meeting);

        switch (tier) {
            case LOCAL -> executor.submit(() -> transcribeLocally(meetingId));
            case PLATFORM -> executor.submit(() -> submitViaPlatform(meetingId));
            case BYOK -> executor.submit(() -> submitToTingwu(meetingId, settings));
        }
        return saved;
    }

    /** 音频本体的定位与非空校验，两档共用。 */
    private Path resolveAudioPath(MeetingRecording meeting) throws Exception {
        ProjectFile audio = projectFileRepository.findById(meeting.getAudioFileId())
                .orElseThrow(() -> new IllegalStateException("音频文件记录不存在"));
        Path audioPath = storageResolver.resolve(audio.getFilePath());
        if (!Files.exists(audioPath) || Files.size(audioPath) == 0) {
            throw new IllegalStateException("音频文件为空，无法转写");
        }
        return audioPath;
    }

    /**
     * 后台（platform 档）：转码 → 换直传凭证 → 普通 PUT 直传 → 网关建任务并预扣。
     *
     * <p><b>失败绝不回落 BYOK</b>：回落等于拿用户自己的 Key 去花钱，而他可能根本没配过，
     * 表现就成了「一个看不懂的凭证错误」。这里只把可读的原因落到 error 上，
     * 界面据此给「改用自己的 Key」的入口。
     */
    private void submitViaPlatform(Long meetingId) {
        Path workDir = null;
        try {
            MeetingRecording meeting = meetingRepository.findById(meetingId).orElse(null);
            if (meeting == null) return;
            Path audioPath = resolveAudioPath(meeting);

            workDir = Files.createTempDirectory("awd-meeting-");
            File prepared = transcoder.toMp3(audioPath.toFile(), workDir);
            String format = prepared.getName().endsWith(".mp3") ? "mp3" : "webm";
            long durationSec = estimateDurationSec(meeting, prepared);

            // ① 凭证：余额闸在这一步就生效，用户不会白传两小时录音才被拒
            JsonNode ticket = platformGatewayClient.postJson("/api/gateway/asr/ticket",
                    orderedMap("durationSec", durationSec, "format", format),
                    false, TICKET_TIMEOUT_SECONDS);
            String objectKey = textOrNull(ticket, "objectKey");
            String uploadUrl = textOrNull(ticket, "uploadUrl");
            String contentType = textOrNull(ticket, "contentType");
            if (objectKey == null || uploadUrl == null) {
                throw new IllegalStateException("平台未返回可用的上传凭证");
            }

            // ② 数据面：几百 MB 直传对象存储，不经过官网进程
            uploader.put(uploadUrl, prepared, contentType == null ? "application/octet-stream" : contentType);

            // ③ 预扣 + 建任务。会扣费，所以 postJson 带幂等键
            JsonNode submitted = platformGatewayClient.postJson("/api/gateway/asr/submit",
                    orderedMap("objectKey", objectKey, "durationSec", durationSec),
                    true, SUBMIT_TIMEOUT_SECONDS);
            String taskId = textOrNull(submitted, "taskId");
            if (taskId == null) {
                throw new IllegalStateException("平台未返回转写任务号");
            }

            MeetingRecording fresh = meetingRepository.findById(meetingId).orElse(null);
            if (fresh == null) return;
            // taskId 必须落库：预扣的钱挂在网关那边，重启后没有它就没人去把结算跑完
            fresh.setGatewayTaskId(taskId);
            fresh.setLastPolledAt(LocalDateTime.now());
            meetingRepository.save(fresh);
            log.info("平台转写任务已提交: meetingId={}, taskId={}, 预扣={}分",
                    meetingId, taskId, submitted.path("heldCents").asInt(0));
        } catch (GatewayException e) {
            log.warn("平台转写提交失败: meetingId={}, kind={}", meetingId, e.getKind());
            failMeeting(meetingId, e.getMessage());
        } catch (Exception e) {
            log.warn("平台转写提交失败: meetingId={}", meetingId, e);
            failMeeting(meetingId, "转写提交失败: " + brief(e));
        } finally {
            cleanupDir(workDir);
            inFlight.remove(meetingId);
        }
    }

    /**
     * 后台（local 档）：转码 → 本机 asr-service 转写 → 直接落库。<b>没有 taskId、没有轮询</b>——
     * 整段推理就在这个方法里跑完，一场两小时的会常态要几十分钟。
     *
     * <p><b>失败绝不回落云端。</b>用户开这一档的唯一理由是这段谈话不能出网，
     * 悄悄传上去正好是他要规避的事。这里只把可读的原因落到 error 上。
     */
    private void transcribeLocally(Long meetingId) {
        Path workDir = null;
        try {
            MeetingRecording meeting = meetingRepository.findById(meetingId).orElse(null);
            if (meeting == null) return;
            Path audioPath = resolveAudioPath(meeting);

            workDir = Files.createTempDirectory("awd-meeting-");
            File prepared = transcoder.toMp3(audioPath.toFile(), workDir);
            String raw = localAsrClient.transcribe(prepared);

            MeetingRecording fresh = meetingRepository.findById(meetingId).orElse(null);
            if (fresh == null) return;
            // summaryJson 为 null：章节/摘要/待办是听悟的增值结果，本地档没有。
            // 用户仍可用 AI 面板基于转写稿生成纪要（getMeetingMinutesPrompt 那条路两档共用）。
            storeSegments(fresh, MeetingTranscriptParser.parseLocalSegments(raw), null);
            log.info("本机转写完成: meetingId={}", meetingId);
        } catch (Exception e) {
            log.warn("本机转写失败: meetingId={}", meetingId, e);
            failMeeting(meetingId, LangText.of("本机转写失败: ", "On-device transcription failed: ") + brief(e));
        } finally {
            cleanupDir(workDir);
            inFlight.remove(meetingId);
        }
    }

    /**
     * 申报给网关的时长。<b>只影响预扣估算与余额闸</b>，真实计费以听悟返回的时长为准，
     * 所以这里不值得为了精确再开一次 ffmpeg。
     *
     * <p>durationMs 可能为空（崩溃恢复路径：前端没来得及回报）。此时按转码产物的码率反推
     * ——那个 mp3 是我们自己按 {@link MeetingAudioTranscoder#BITRATE} 定码率写出来的，
     * 误差在个位数百分比；转码失败回退原始录音时会低估，而低估只会让预扣少一点。
     */
    private static long estimateDurationSec(MeetingRecording meeting, File prepared) {
        Long ms = meeting.getDurationMs();
        if (ms != null && ms > 0) {
            return Math.max(1, (ms + 999) / 1000);
        }
        return Math.max(1, prepared.length() * 8 / MeetingAudioTranscoder.BITRATE);
    }

    /** JSON 字段取文本：缺失与 JSON null 都回 Java null（asText 对 NullNode 会给出字符串 "null"）。 */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /** Map.of 不接受 null 值也不保序，请求体用有序 map 拼，日志里好看也好对。 */
    private static Map<String, Object> orderedMap(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    /** 后台（byok 档）：转码 → 自己的 OSS → 自己的听悟凭证建任务。任何一步失败都落 FAILED + error。 */
    private void submitToTingwu(Long meetingId, MeetingAsrSettings settings) {
        Path workDir = null;
        try {
            MeetingRecording meeting = meetingRepository.findById(meetingId).orElse(null);
            if (meeting == null) return;
            Path audioPath = resolveAudioPath(meeting);

            workDir = Files.createTempDirectory("awd-meeting-");
            File prepared = transcoder.toMp3(audioPath.toFile(), workDir);
            String ext = prepared.getName().endsWith(".mp3") ? "mp3" : "webm";
            String objectKey = ossObjectKey(meeting, ext);

            String signedUrl = ossClient.uploadAndSign(settings, objectKey, prepared, URL_TTL);
            String taskId = tingwuClient.submitTask(settings, signedUrl);

            MeetingRecording fresh = meetingRepository.findById(meetingId).orElse(null);
            if (fresh == null) {
                ossClient.deleteQuietly(settings, objectKey);
                return;
            }
            fresh.setTingwuTaskId(taskId);
            fresh.setLastPolledAt(LocalDateTime.now());
            meetingRepository.save(fresh);
            log.info("听悟任务已提交: meetingId={}, taskId={}", meetingId, taskId);
        } catch (Exception e) {
            log.warn("转写提交失败: meetingId={}", meetingId, e);
            failMeeting(meetingId, "转写提交失败: " + brief(e));
        } finally {
            cleanupDir(workDir);
            inFlight.remove(meetingId);
        }
    }

    /**
     * poll-on-read：GET 会议详情时调用。仅在 TRANSCRIBING 且已有 taskId 且距上次
     * 查询超过节流窗口时才真的去问上游。返回可能已更新的实体。
     *
     * <p><b>走哪条路由由落库的 taskId 决定，不由当前档位设置决定。</b>
     * 用户在转写途中切了档，按设置分派就会拿着网关的 taskId 去问听悟（或反过来），
     * 结果是一个永远查不到的任务 + 一笔永远结不了的预扣。任务归属在提交那一刻就定死了。
     */
    public MeetingRecording refreshIfNeeded(MeetingRecording meeting) {
        if (!MeetingRecording.STATUS_TRANSCRIBING.equals(meeting.getStatus())) {
            return meeting;
        }
        boolean viaPlatform = meeting.getGatewayTaskId() != null;
        if (!viaPlatform && meeting.getTingwuTaskId() == null) {
            return interruptedOrPending(meeting);
        }
        LocalDateTime last = meeting.getLastPolledAt();
        if (last != null && last.isAfter(LocalDateTime.now().minusSeconds(POLL_THROTTLE_SECONDS))) {
            return meeting;
        }
        meeting.setLastPolledAt(LocalDateTime.now());
        meetingRepository.save(meeting);

        return viaPlatform ? refreshViaPlatform(meeting) : refreshViaTingwu(meeting);
    }

    /**
     * 两个 taskId 都为空的「转写中」：要么后台任务正跑着（local 档的整段推理、
     * 云端两档的转码上传阶段都落在这个窗口里），要么上次运行被关机/崩溃打断了。
     * 靠 {@link #inFlight} 区分——不区分的话被打断的会议会<b>永远</b>停在「转写中」，
     * 而 {@link #startTranscription} 对该状态是幂等返回，用户连「重试转写」都点不动。
     */
    private MeetingRecording interruptedOrPending(MeetingRecording meeting) {
        if (inFlight.contains(meeting.getId())) return meeting;
        meeting.setStatus(MeetingRecording.STATUS_FAILED);
        meeting.setError(LangText.of(
                "转写在上次运行中被中断，重新提交即可（录音本身完好）",
                "Transcription was interrupted by a previous shutdown; submit it again (the recording itself is intact)"));
        return meetingRepository.save(meeting);
    }

    /**
     * platform 档轮询。完成时网关已经在同一次请求里按听悟返回的真实时长结算并删了中转对象，
     * 桌面端这边只负责把结果落库。
     */
    private MeetingRecording refreshViaPlatform(MeetingRecording meeting) {
        try {
            JsonNode res = platformGatewayClient.getJson(
                    "/api/gateway/asr/task/" + meeting.getGatewayTaskId(), TASK_TIMEOUT_SECONDS);
            String status = res.path("status").asText("");
            if ("completed".equals(status)) {
                return completeFromGateway(meeting, res);
            }
            if ("failed".equals(status)) {
                meeting.setStatus(MeetingRecording.STATUS_FAILED);
                String message = textOrNull(res, "message");
                meeting.setError("转写失败: " + (message == null ? "未知原因" : message));
                return meetingRepository.save(meeting);
            }
            return meeting; // processing，下次再看
        } catch (GatewayException e) {
            if (e.getKind() == GatewayException.Kind.BAD_REQUEST) {
                // 网关说这个任务它不认识——再问一万次也是同一个答案，别把用户挂在转写中
                meeting.setStatus(MeetingRecording.STATUS_FAILED);
                meeting.setError("转写失败: " + e.getMessage());
                return meetingRepository.save(meeting);
            }
            // 其余（网络抖动、我们在发版、上游临时不可用）不落 FAILED：
            // 任务还在平台侧跑着，钱也还被预扣占着，改成失败反而丢结果
            log.warn("平台转写任务查询失败: meetingId={}, kind={}", meeting.getId(), e.getKind());
            return meeting;
        }
    }

    /** byok 档轮询：直接问听悟。 */
    private MeetingRecording refreshViaTingwu(MeetingRecording meeting) {
        MeetingAsrSettings settings = loadSettings();
        try {
            TingwuClient.TaskInfo info = tingwuClient.getTask(settings, meeting.getTingwuTaskId());
            if (info.completed()) {
                return completeMeeting(meeting, settings, info);
            }
            if (info.failed()) {
                meeting.setStatus(MeetingRecording.STATUS_FAILED);
                meeting.setError(tingwuFailureText(info));
                cleanupOss(meeting, settings);
                return meetingRepository.save(meeting);
            }
            return meeting; // ONGOING，下次再看
        } catch (Exception e) {
            // 查询失败不落 FAILED：网络抖动不该终结一个还在跑的听悟任务
            log.warn("听悟任务查询失败: meetingId={}, {}", meeting.getId(), e.toString());
            return meeting;
        }
    }

    /**
     * 终态失败的文案。INVALID 与 FAILED 分开说：前者是听悟没受理（音频取不到、格式不支持、参数非法），
     * 用户该去换音频或查凭证；后者是任务跑起来了但失败。都写成「转写失败」的话，
     * 用户只会以为是我们这边卡住了，然后反复重试同一份必然被拒的音频。
     */
    private static String tingwuFailureText(TingwuClient.TaskInfo info) {
        String reason = info.errorMessage() == null || info.errorMessage().isBlank()
                ? "未知原因" : info.errorMessage();
        return info.invalid()
                ? "听悟未受理该任务（INVALID）: " + reason
                : "听悟转写失败: " + reason;
    }

    private MeetingRecording completeMeeting(MeetingRecording meeting, MeetingAsrSettings settings,
                                             TingwuClient.TaskInfo info) {
        try {
            String transcriptionJson = info.transcriptionUrl() != null ? urlFetcher.fetch(info.transcriptionUrl()) : null;
            return storeResults(meeting, transcriptionJson,
                    fetchQuietly(info.autoChaptersUrl()),
                    fetchQuietly(info.summarizationUrl()),
                    fetchQuietly(info.meetingAssistanceUrl()));
        } catch (Exception e) {
            return failFromResults(meeting, e);
        } finally {
            // 中转对象是 byok 档自己传上去的，也由它自己删。
            // platform 档下这几个对象在官网的 bucket 里，由网关在结算的同一次请求里删。
            cleanupOss(meeting, settings);
        }
    }

    /** platform 档：四份结果由网关代下载后随响应内联回来，形态与听悟原始结果文件逐字一致。 */
    private MeetingRecording completeFromGateway(MeetingRecording meeting, JsonNode res) {
        try {
            return storeResults(meeting,
                    textOrNull(res, "transcription"),
                    textOrNull(res, "autoChapters"),
                    textOrNull(res, "summarization"),
                    textOrNull(res, "meetingAssistance"));
        } catch (Exception e) {
            return failFromResults(meeting, e);
        }
    }

    /** 解析 + 落库，云端两档共用。 */
    private MeetingRecording storeResults(MeetingRecording meeting, String transcriptionJson,
                                          String chapters, String summarization, String assistance) {
        return storeSegments(meeting,
                MeetingTranscriptParser.parseSegments(transcriptionJson),
                MeetingTranscriptParser.buildSummaryJson(chapters, summarization, assistance));
    }

    /** 落库，三档共用。转写正文为空一律当失败——不能让空稿冒充成功。 */
    private MeetingRecording storeSegments(MeetingRecording meeting,
                                           List<MeetingTranscriptParser.Segment> segments,
                                           String summaryJson) {
        if (segments.isEmpty()) {
            throw new IllegalStateException("转写结果为空");
        }
        meeting.setTranscriptJson(MeetingTranscriptParser.segmentsToJson(segments));
        meeting.setSummaryJson(summaryJson);
        meeting.setStatus(MeetingRecording.STATUS_TRANSCRIBED);
        meeting.setError(null);
        return meetingRepository.save(meeting);
    }

    private MeetingRecording failFromResults(MeetingRecording meeting, Exception e) {
        log.warn("转写结果落库失败: meetingId={}", meeting.getId(), e);
        meeting.setStatus(MeetingRecording.STATUS_FAILED);
        meeting.setError("转写结果处理失败: " + brief(e));
        return meetingRepository.save(meeting);
    }

    /** 增值结果（章节/摘要/待办）下载失败不影响主流程。 */
    private String fetchQuietly(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return urlFetcher.fetch(url);
        } catch (Exception e) {
            log.warn("听悟增值结果下载失败（跳过）: {}", e.toString());
            return null;
        }
    }

    private void failMeeting(Long meetingId, String error) {
        meetingRepository.findById(meetingId).ifPresent(m -> {
            m.setStatus(MeetingRecording.STATUS_FAILED);
            m.setError(error);
            meetingRepository.save(m);
        });
    }

    /** objectKey 由 meeting 派生（确定性），完成/失败后可无状态清理。 */
    private String ossObjectKey(MeetingRecording meeting, String ext) {
        return "awd-meetings/" + meeting.getProjectId() + "/" + meeting.getId() + "." + ext;
    }

    private void cleanupOss(MeetingRecording meeting, MeetingAsrSettings settings) {
        ossClient.deleteQuietly(settings, ossObjectKey(meeting, "mp3"));
        ossClient.deleteQuietly(settings, ossObjectKey(meeting, "webm"));
    }

    private void cleanupDir(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static String brief(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
