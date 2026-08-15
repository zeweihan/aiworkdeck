package com.checkba.service.feedback;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.FeedbackAttachmentRepository;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.LangText;
import com.checkba.storage.ProjectStorageResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户反馈的落库与取用。
 *
 * <p>提交是**一次 multipart 请求**（正文 + 0..N 张图 + 0..1 段语音）而不是先建后传：
 * 分步上传会在用户网络抖动时留下一堆没有正文的空反馈，而反馈本身就是「出问题时」提交的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    /** 单个附件上限 20MB；截图与几十秒语音都远小于此，超过多半是误传。 */
    public static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;
    public static final int MAX_IMAGES = 10;
    public static final int MAX_AUDIOS = 1;
    /** 正文上限：反馈不是文档，超长多半是粘贴了整份日志（日志有专门的上下文段） */
    public static final int MAX_TEXT_CHARS = 20000;

    private final UserFeedbackRepository feedbackRepository;
    private final FeedbackAttachmentRepository attachmentRepository;
    private final ProjectStorageResolver storageResolver;
    private final FeedbackContextCollector contextCollector;
    private final VoiceTranscriptionService transcriptionService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 提交一条反馈。返回已落库的行（含 id）。 */
    public UserFeedback submit(Long userId, SubmitRequest req, List<MultipartFile> files) throws IOException {
        String text = req.text() == null ? "" : req.text().trim();
        List<MultipartFile> safeFiles = files == null ? List.of() : files;
        if (text.isEmpty() && safeFiles.isEmpty()) {
            throw new IllegalArgumentException(LangText.of("请至少写一句话，或附一张截图/一段语音", "Please write at least a sentence, or attach a screenshot/voice note"));
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }

        UserFeedback fb = new UserFeedback();
        fb.setUserId(userId);
        fb.setProjectId(req.projectId());
        fb.setKind(UserFeedback.KIND_IDEA.equals(req.kind()) ? UserFeedback.KIND_IDEA : UserFeedback.KIND_BUG);
        fb.setText(text);
        fb.setPage(trim(req.page(), 256));
        fb.setAppVersion(contextCollector.appVersion());
        fb.setPlatform(trim(contextCollector.platform(), 128));
        fb.setStatus(UserFeedback.STATUS_NEW);
        fb.setAttempts(0);
        fb.setSource(UserFeedback.SOURCE_LOCAL);
        fb.setUploaded(false);
        fb.setCreatedAt(LocalDateTime.now());
        fb = feedbackRepository.save(fb);

        List<FeedbackAttachment> saved = storeAttachments(fb.getId(), safeFiles);

        // 语音转写：配了转写服务才有文本，否则留空由优化者转人工
        for (FeedbackAttachment a : saved) {
            if (!FeedbackAttachment.TYPE_AUDIO.equals(a.getType())) continue;
            byte[] bytes = Files.readAllBytes(attachmentPath(fb.getId(), a.getStoredName()));
            String transcript = transcriptionService.transcribe(bytes, a.getOriginalName(), a.getContentType());
            if (!transcript.isBlank()) fb.setVoiceTranscript(transcript.trim());
            break;
        }

        fb.setContextJson(buildContextJson(req.clientContext()));
        return feedbackRepository.save(fb);
    }

    private List<FeedbackAttachment> storeAttachments(Long feedbackId, List<MultipartFile> files) throws IOException {
        Path dir = feedbackDir(feedbackId);
        Files.createDirectories(dir);
        List<FeedbackAttachment> out = new ArrayList<>();
        int images = 0;
        int audios = 0;
        int seq = 0;
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            if (f.getSize() > MAX_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException(LangText.of("附件过大（上限 20MB）：", "Attachment is too large (20MB limit): ") + safeName(f.getOriginalFilename()));
            }
            String ct = f.getContentType() == null ? "" : f.getContentType().toLowerCase();
            String type;
            if (ct.startsWith("audio/")) {
                if (++audios > MAX_AUDIOS) throw new IllegalArgumentException(LangText.of("最多附 1 段语音", "At most 1 voice note may be attached"));
                type = FeedbackAttachment.TYPE_AUDIO;
            } else if (ct.startsWith("image/")) {
                if (++images > MAX_IMAGES) throw new IllegalArgumentException(LangText.of("最多附 " + MAX_IMAGES + " 张图片", "At most " + MAX_IMAGES + " images may be attached"));
                type = FeedbackAttachment.TYPE_IMAGE;
            } else {
                throw new IllegalArgumentException(LangText.of("只接受图片与语音附件：", "Only image and voice attachments are accepted: ") + safeName(f.getOriginalFilename()));
            }

            // 落盘文件名由服务端生成：客户端文件名一律只作展示，不参与路径拼接
            String ext = extensionFor(type, ct, f.getOriginalFilename());
            String stored = (FeedbackAttachment.TYPE_AUDIO.equals(type) ? "voice-" : "img-") + (++seq) + ext;
            Path target = dir.resolve(stored);
            f.transferTo(target);

            FeedbackAttachment a = new FeedbackAttachment();
            a.setFeedbackId(feedbackId);
            a.setType(type);
            a.setStoredName(stored);
            a.setOriginalName(trim(safeName(f.getOriginalFilename()), 256));
            a.setContentType(trim(ct, 128));
            a.setSizeBytes(f.getSize());
            a.setCreatedAt(LocalDateTime.now());
            out.add(attachmentRepository.save(a));
        }
        return out;
    }

    private String buildContextJson(Map<String, Object> clientContext) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("runtime", contextCollector.runtimeInfo());
        ctx.put("client", clientContext == null ? Map.of() : clientContext);
        String logTail = contextCollector.backendLogTail();
        if (!logTail.isEmpty()) ctx.put("backendLogTail", logTail);
        try {
            return mapper.writeValueAsString(ctx);
        } catch (Exception e) {
            log.warn("反馈上下文序列化失败: {}", e.toString());
            return "{}";
        }
    }

    /**
     * 云端收件箱：收下别的安装上传来的一条反馈。
     *
     * <p>与 {@link #submit} 的区别只有三处：没有登录用户、带上传方的 installId+clientRef
     * 做幂等、正文里的版本/平台**照抄上传方的**（云端自己的版本号对排障没有意义）。
     *
     * @return 已存在则原样返回旧行（幂等），不会重复建
     */
    public UserFeedback ingest(String installId, String clientRef, IngestRequest req,
                               List<MultipartFile> files) throws IOException {
        var existing = feedbackRepository.findByInstallIdAndClientRef(installId, clientRef);
        if (existing.isPresent()) return existing.get();

        String text = req.text() == null ? "" : req.text().trim();
        List<MultipartFile> safeFiles = files == null ? List.of() : files;
        if (text.isEmpty() && safeFiles.isEmpty() && (req.voiceTranscript() == null || req.voiceTranscript().isBlank())) {
            throw new IllegalArgumentException(LangText.of("空反馈", "Empty feedback"));
        }
        if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS);

        UserFeedback fb = new UserFeedback();
        fb.setUserId(null);
        fb.setSource(UserFeedback.SOURCE_CLOUD);
        fb.setInstallId(trim(installId, 64));
        fb.setClientRef(trim(clientRef, 64));
        fb.setKind(UserFeedback.KIND_IDEA.equals(req.kind()) ? UserFeedback.KIND_IDEA : UserFeedback.KIND_BUG);
        fb.setText(text);
        fb.setVoiceTranscript(req.voiceTranscript());
        fb.setPage(trim(req.page(), 256));
        fb.setAppVersion(trim(req.appVersion(), 64));
        fb.setPlatform(trim(req.platform(), 128));
        fb.setContextJson(req.contextJson());
        fb.setStatus(UserFeedback.STATUS_NEW);
        fb.setAttempts(0);
        fb.setUploaded(true);
        fb.setCreatedAt(LocalDateTime.now());
        fb = feedbackRepository.save(fb);

        storeAttachments(fb.getId(), safeFiles);
        return fb;
    }

    public void markUploaded(UserFeedback fb) {
        fb.setUploaded(true);
        fb.setUploadedAt(LocalDateTime.now());
        feedbackRepository.save(fb);
    }

    public List<FeedbackAttachment> attachmentsOf(Long feedbackId) {
        return attachmentRepository.findByFeedbackIdOrderByIdAsc(feedbackId);
    }

    public Resource readAttachment(Long feedbackId, Long attachmentId) {
        FeedbackAttachment a = attachmentRepository.findById(attachmentId)
                .filter(x -> x.getFeedbackId().equals(feedbackId))
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("附件不存在", "Attachment not found")));
        return new FileSystemResource(attachmentPath(feedbackId, a.getStoredName()));
    }

    public List<UserFeedback> list(String status, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        var page = PageRequest.of(0, safeLimit);
        if (status == null || status.isBlank()) {
            return feedbackRepository.findByOrderByIdDesc(page);
        }
        return feedbackRepository.findByStatusOrderByIdDesc(status.trim().toUpperCase(), page);
    }

    /** 用户自己提交过的反馈（浮窗「我的反馈」用）：不要求管理员，只看自己的行。 */
    public List<UserFeedback> listByUser(Long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        return feedbackRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, safeLimit));
    }

    public Path feedbackDir(Long feedbackId) {
        return storageResolver.globalRoot().resolve("feedback").resolve(String.valueOf(feedbackId));
    }

    public Path attachmentPath(Long feedbackId, String storedName) {
        return feedbackDir(feedbackId).resolve(storedName);
    }

    private static String extensionFor(String type, String contentType, String originalName) {
        String ct = contentType == null ? "" : contentType;
        if (ct.contains("png")) return ".png";
        if (ct.contains("jpeg") || ct.contains("jpg")) return ".jpg";
        if (ct.contains("gif")) return ".gif";
        if (ct.contains("webp") && FeedbackAttachment.TYPE_IMAGE.equals(type)) return ".webp";
        if (ct.contains("webm")) return ".webm";
        if (ct.contains("ogg")) return ".ogg";
        if (ct.contains("mp4") || ct.contains("m4a")) return ".m4a";
        if (ct.contains("mpeg")) return ".mp3";
        if (ct.contains("wav")) return ".wav";
        return FeedbackAttachment.TYPE_AUDIO.equals(type) ? ".bin" : ".png";
    }

    /** 客户端文件名只用于展示：去掉路径分隔符，避免任何形式的目录穿越。 */
    private static String safeName(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\\\/\\r\\n]", "_");
    }

    private static String trim(String v, int max) {
        if (v == null) return null;
        String s = v.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 提交请求（正文部分，与附件一起来自同一个 multipart 请求）。 */
    public record SubmitRequest(String kind, String text, Long projectId, String page,
                                Map<String, Object> clientContext) {
    }

    /** 云端收件箱的入站请求：上传方已经采好的现场原样带过来。 */
    public record IngestRequest(String kind, String text, String voiceTranscript, String page,
                                String appVersion, String platform, String contextJson) {
    }
}
