package com.checkba.service.meeting;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.SystemSettingService;
import com.checkba.storage.ProjectStorageResolver;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 转写编排：音频转码 → OSS 中转 → 听悟建任务 → poll-on-read 收结果。
 *
 * <p>提交在单线程后台执行器里跑（转码+上传一场两小时的会要分钟级），
 * 状态推进靠前端轮询 GET 触发 {@link #refreshIfNeeded}——没有常驻调度器，
 * 面板关了任务就停在听悟侧，下次打开面板继续收，代价可接受、代码最少。
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

    /** poll-on-read 节流：距上次查听悟不足该秒数时直接返回现状 */
    private static final int POLL_THROTTLE_SECONDS = 10;
    /** 签名 URL 有效期。听悟要求 >= 3 小时，给 4 小时留余量 */
    private static final Duration URL_TTL = Duration.ofHours(4);

    /** 结果文件下载的接缝（测试桩用） */
    public interface UrlFetcher {
        String fetch(String url) throws Exception;
    }

    private final MeetingRecordingRepository meetingRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectStorageResolver storageResolver;
    private final SystemSettingService systemSettingService;
    private final MeetingAudioTranscoder transcoder;
    private final TingwuClient tingwuClient;
    private final MeetingOssClient ossClient;
    private final UrlFetcher urlFetcher;
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

    @org.springframework.beans.factory.annotation.Autowired
    public MeetingTranscriptionService(
            MeetingRecordingRepository meetingRepository,
            ProjectFileRepository projectFileRepository,
            ProjectStorageResolver storageResolver,
            SystemSettingService systemSettingService,
            MeetingAudioTranscoder transcoder,
            TingwuClient tingwuClient,
            MeetingOssClient ossClient,
            @Value("${meeting.asr.access-key-id:}") String defaultAccessKeyId,
            @Value("${meeting.asr.access-key-secret:}") String defaultAccessKeySecret,
            @Value("${meeting.asr.app-key:}") String defaultAppKey,
            @Value("${meeting.oss.bucket:}") String defaultOssBucket,
            @Value("${meeting.oss.endpoint:}") String defaultOssEndpoint) {
        this(meetingRepository, projectFileRepository, storageResolver, systemSettingService,
                transcoder, tingwuClient, ossClient, defaultUrlFetcher(),
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
            UrlFetcher urlFetcher,
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
        this.urlFetcher = urlFetcher;
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

    public MeetingAsrSettings loadSettings() {
        return new MeetingAsrSettings(
                systemSettingService.get(KEY_ACCESS_KEY_ID, defaultAccessKeyId),
                systemSettingService.get(KEY_ACCESS_KEY_SECRET, defaultAccessKeySecret),
                systemSettingService.get(KEY_APP_KEY, defaultAppKey),
                systemSettingService.get(KEY_OSS_BUCKET, defaultOssBucket),
                systemSettingService.get(KEY_OSS_ENDPOINT, defaultOssEndpoint));
    }

    public boolean isConfigured() {
        return loadSettings().configured();
    }

    /**
     * 提交转写。同步只做状态置位（TRANSCRIBING），耗时步骤进后台执行器。
     * RECORDED / FAILED 可提交；TRANSCRIBING/TRANSCRIBED 幂等返回。
     */
    public MeetingRecording startTranscription(Long meetingId) {
        MeetingRecording meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("会议不存在: " + meetingId));
        if (MeetingRecording.STATUS_TRANSCRIBING.equals(meeting.getStatus())
                || MeetingRecording.STATUS_TRANSCRIBED.equals(meeting.getStatus())) {
            return meeting;
        }
        // IllegalArgumentException：GlobalExceptionHandler 只对它透传 message，其余异常一律「服务器内部错误」
        if (MeetingRecording.STATUS_RECORDING.equals(meeting.getStatus())) {
            throw new IllegalArgumentException("录音尚未结束");
        }
        MeetingAsrSettings settings = loadSettings();
        if (!settings.configured()) {
            throw new IllegalArgumentException("未配置转写服务凭证，请到 设置-会议转写 填写阿里云凭证");
        }
        meeting.setStatus(MeetingRecording.STATUS_TRANSCRIBING);
        meeting.setError(null);
        meeting.setTingwuTaskId(null);
        MeetingRecording saved = meetingRepository.save(meeting);

        executor.submit(() -> submitToTingwu(meetingId, settings));
        return saved;
    }

    /** 后台：转码 → OSS → 听悟建任务。任何一步失败都落 FAILED + error。 */
    private void submitToTingwu(Long meetingId, MeetingAsrSettings settings) {
        Path workDir = null;
        try {
            MeetingRecording meeting = meetingRepository.findById(meetingId).orElse(null);
            if (meeting == null) return;
            ProjectFile audio = projectFileRepository.findById(meeting.getAudioFileId())
                    .orElseThrow(() -> new IllegalStateException("音频文件记录不存在"));
            Path audioPath = storageResolver.resolve(audio.getFilePath());
            if (!Files.exists(audioPath) || Files.size(audioPath) == 0) {
                throw new IllegalStateException("音频文件为空，无法转写");
            }

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
        }
    }

    /**
     * poll-on-read：GET 会议详情时调用。仅在 TRANSCRIBING 且已有 taskId 且距上次
     * 查询超过节流窗口时才真的去问听悟。返回可能已更新的实体。
     */
    public MeetingRecording refreshIfNeeded(MeetingRecording meeting) {
        if (!MeetingRecording.STATUS_TRANSCRIBING.equals(meeting.getStatus())
                || meeting.getTingwuTaskId() == null) {
            return meeting;
        }
        LocalDateTime last = meeting.getLastPolledAt();
        if (last != null && last.isAfter(LocalDateTime.now().minusSeconds(POLL_THROTTLE_SECONDS))) {
            return meeting;
        }
        meeting.setLastPolledAt(LocalDateTime.now());
        meetingRepository.save(meeting);

        MeetingAsrSettings settings = loadSettings();
        try {
            TingwuClient.TaskInfo info = tingwuClient.getTask(settings, meeting.getTingwuTaskId());
            if (info.completed()) {
                return completeMeeting(meeting, settings, info);
            }
            if (info.failed()) {
                meeting.setStatus(MeetingRecording.STATUS_FAILED);
                meeting.setError("听悟转写失败: "
                        + (info.errorMessage() == null ? "未知原因" : info.errorMessage()));
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

    private MeetingRecording completeMeeting(MeetingRecording meeting, MeetingAsrSettings settings,
                                             TingwuClient.TaskInfo info) {
        try {
            String transcriptionJson = info.transcriptionUrl() != null ? urlFetcher.fetch(info.transcriptionUrl()) : null;
            List<MeetingTranscriptParser.Segment> segments = MeetingTranscriptParser.parseSegments(transcriptionJson);
            if (segments.isEmpty()) {
                throw new IllegalStateException("听悟返回的转写结果为空");
            }
            String chapters = fetchQuietly(info.autoChaptersUrl());
            String summarization = fetchQuietly(info.summarizationUrl());
            String assistance = fetchQuietly(info.meetingAssistanceUrl());

            meeting.setTranscriptJson(MeetingTranscriptParser.segmentsToJson(segments));
            meeting.setSummaryJson(MeetingTranscriptParser.buildSummaryJson(chapters, summarization, assistance));
            meeting.setStatus(MeetingRecording.STATUS_TRANSCRIBED);
            meeting.setError(null);
            return meetingRepository.save(meeting);
        } catch (Exception e) {
            log.warn("转写结果落库失败: meetingId={}", meeting.getId(), e);
            meeting.setStatus(MeetingRecording.STATUS_FAILED);
            meeting.setError("转写结果处理失败: " + brief(e));
            return meetingRepository.save(meeting);
        } finally {
            cleanupOss(meeting, settings);
        }
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
