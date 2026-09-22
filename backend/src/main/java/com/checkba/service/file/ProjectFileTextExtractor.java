// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.file;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectFileTextCache;
import com.checkba.service.DocumentTextService;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.meeting.MeetingRecordingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 项目文件的纯文本抽取（dev-board#718，从 FileTools.extract_file_text 抽出）。
 *
 * <p>一条路由、四个使用方（dev-board#800 起 {@code read_document} 也走这里，于是附件正文、
 * 活跃文档正文与文件夹上下文都收敛到同一口径）：AI 工具 {@code extract_file_text} /
 * {@code read_document}、云端项目参考来源、桌面端参考读取、文件夹上下文。
 * 路由与 extract_file_text 一直以来的口径逐条相同（dev-board#396）：
 * 图片没有文字层，直接走云端 OCR；PDF 先抽文字层，抽不出（扫描件）才 OCR；其余格式走 Tika。
 * <b>音频没有任何「文字」可抽</b>（dev-board#814）：交给 Tika 得到的是空串或 ID3 标签里的
 * 艺术家/专辑，于是 {@code read_document} 回一句指向 OCR 与 extract_file_text 的 Warning——
 * 对音频三条建议没有一条成立，模型据此告诉用户「我看不到这个文件」。音频改走
 * {@link #audioText}：已转写的返回转写稿，没转写的抛 {@link AudioNotTranscribedException}，
 * 消息是一句可行动的下一步。
 * 「文字层够不够用」的判据在 {@link PdfTextLayer}，全仓只有那一份。
 * OCR 的失败以「[System: …]」形态返回（非空、无 Error 前缀），这里一律转成 {@link OcrFailedException}，
 * 绝不能被当成正文。
 *
 * <p>抽取结果经 {@link ProjectFileTextCacheService} 落库缓存（键 fileId，失效判据是物理文件的
 * mtime+size）：附件与活跃文档的正文是<b>每一轮都要重新注入</b>的，没有这层缓存时，
 * 同一份扫描件在一条会话里会被反复 OCR、反复按页扣 Credits。
 *
 * <p><b>只有工具入口 {@link #extractText(ProjectFile)} 走 OCR。</b>参考入口
 * （{@link #extract}、{@link #extractBytes}）走到 OCR 分支时改为报 {@link #NEEDS_OCR}：
 * 平台代采档的 OCR 按页扣 Credits，而 legal/PRIVACY.md 对参考材料写的是「不产生 Credits 扣费」。
 *
 * <p>红线：只返回文字、不落盘（OCR 的临时文件用完即删）、日志只记 id 与异常，不记正文。
 */
@Service
@Slf4j
public class ProjectFileTextExtractor {

    /** 参考材料的单文件字节上限。 */
    public static final long MAX_BYTES = 50L * 1024 * 1024;

    static final String TOO_LARGE = "文件超过 50MB，暂不支持作为参考材料读取";
    static final String IS_FOLDER = "这是一个文件夹，不是文件，不能作为参考材料读取；请用 ref_list 找到其中的文件再读。";
    /**
     * 参考读取撞上「只能靠 OCR 才有文字」的文件时的回话。
     *
     * <p>OCR 走平台代采档时是按页扣 Credits 的（OcrService.recognizeViaPlatform），
     * 而 legal/PRIVACY.md 对参考材料的承诺是「不产生 Credits 扣费」。承诺在先，
     * 这条路就不能默默扣钱——宁可说清楚，让用户自己决定要不要去工作台做识别。
     */
    static final String NEEDS_OCR = "这份文件没有可直接提取的文字（扫描件或图片）。"
            + "参考读取不做文字识别，请让用户在 AI WorkDeck 工作台里对它做一次文字识别后再引用。";

    /** 不在项目文件表里的字节（案件库、git）：这几种按 UTF-8 直接解码，不交给 Tika 猜字符集。 */
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "json");

    private final DocumentTextService documentTextService;
    private final FileContentExtractorService fileContentExtractorService;
    private final ProjectFileService projectFileService;
    /** 抽取结果的跨重启缓存；单测传 null 即退化成「每次重抽」的旧行为。 */
    private final ProjectFileTextCacheService textCache;
    /**
     * 音频的转写稿从哪儿来（dev-board#814）。传 null 即退化成「音频一律按未转写处理」——
     * 那仍然比改动前好：至少说的是「先转写」，而不是把模型指向对音频无用的 OCR。
     */
    private final MeetingRecordingService meetings;

    public ProjectFileTextExtractor(DocumentTextService documentTextService,
                                    FileContentExtractorService fileContentExtractorService,
                                    ProjectFileService projectFileService,
                                    ProjectFileTextCacheService textCache,
                                    MeetingRecordingService meetings) {
        this.documentTextService = documentTextService;
        this.fileContentExtractorService = fileContentExtractorService;
        this.projectFileService = projectFileService;
        this.textCache = textCache;
        this.meetings = meetings;
    }

    /**
     * 参考材料入口：文件夹与超过 50MB 的文件在读字节之前就拒绝，其余同 {@link #extractText}，
     * 但<b>不走 OCR</b>（见 {@link #NEEDS_OCR}）。
     *
     * @return 抽出的文字，可能为空串（文件没有可读的文字）
     * @throws IOException message 是可直接转述给用户的原因
     */
    public String extract(ProjectFile pf) throws IOException {
        if (isFolder(pf)) {
            throw new IOException(IS_FOLDER);
        }
        if (pf.getFileSize() != null && pf.getFileSize() > MAX_BYTES) {
            throw new IOException(TOO_LARGE);
        }
        return extractText(pf, false);
    }

    /**
     * 与 extract_file_text 完全相同的路由，不设大小闸（该工具一直没有 50MB 上限，维持原行为）。
     * 这条是<b>工具</b>入口，OCR 照走照扣——用户是显式让 AI 去识别这份文件的。
     *
     * @return 抽出的文字，可能为空串
     * @throws OcrFailedException OCR 分支失败（含底层原因）
     * @throws IOException        文字层抽取失败
     */
    public String extractText(ProjectFile pf) throws IOException {
        return extractText(pf, true);
    }

    /**
     * 抽取路由 + 缓存（dev-board#800）。
     *
     * <p>缓存对两个入口都生效，包括不走 OCR 的参考入口——<b>命中一条早先由 OCR 得到的正文
     * 不花一分钱</b>，而且这正是 {@link #NEEDS_OCR} 那句话许诺给用户的结果
     *（「在工作台里做一次文字识别后再引用」）。
     */
    private String extractText(ProjectFile pf, boolean allowOcr) throws IOException {
        String name = pf.getName();
        // 音频必须排在缓存之前：转写稿是会后才出现的，而音频字节一个都没变，
        // mtime+size 指纹也就一个字节都没变。把「请先转写」写进缓存，用户转写完成之后
        // 这份文件在本机永远读不到转写稿——而且不报错。见 audioText 的注释。
        if (MeetingRecordingService.isAudioFileName(name)) {
            return audioText(pf);
        }
        boolean ocrSupported = isOcrSupported(name);
        boolean pdf = isPdf(name, pf.getFileType());

        DocumentTextService.FileStamp stamp = stampOf(pf);
        String cached = textCache == null ? null : textCache.find(pf.getId(), stamp);
        if (cached != null) {
            return cached;
        }

        String text = ocrSupported && !pdf ? null : extractTextLayer(pf);
        // PDF 的「文字层够不够用」判据只此一份（PdfTextLayer）：扫描件常带几个残留字符，
        // 按 hasText 判会把那几个字符当全文返回。非 PDF 维持原判据，别一起收紧——
        // 一份只有两个字的 txt 是合法的短文件，不该被赶去 OCR。
        boolean usable = pdf ? PdfTextLayer.isUsable(text) : StringUtils.hasText(text);
        String source = ProjectFileTextCache.SOURCE_TEXT;
        if (!usable && ocrSupported) {
            if (!allowOcr) {
                throw new IOException(NEEDS_OCR);
            }
            text = ocrProjectFile(pf);
            source = ProjectFileTextCache.SOURCE_OCR;
        }
        String result = text == null ? "" : text;
        if (textCache != null) {
            textCache.store(pf.getId(), stamp, source, result);
        }
        return result;
    }

    private DocumentTextService.FileStamp stampOf(ProjectFile pf) {
        return textCache == null || pf.getId() == null ? null : documentTextService.stampOf(pf);
    }

    /**
     * 不在项目文件表里的字节（官方案件库、GitHub/Gitee）：txt/md/csv/json 按 UTF-8 解码；
     * PDF 走 PDFBox；其余走 Tika。只有这两个参考来源在用，所以与 {@link #extract} 同口径
     * <b>不走 OCR</b>（见 {@link #NEEDS_OCR}）。
     *
     * @param fileName 文件名或路径，只用于判断扩展名
     */
    public String extractBytes(String fileName, byte[] bytes) throws IOException {
        if (bytes == null) {
            return "";
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException(TOO_LARGE);
        }
        if (MeetingRecordingService.isAudioFileName(displayName(fileName))) {
            // 案件库/git 的裸字节查不到转写稿（那边没有 project_file），但同样不能交给
            // Tika——抽回来的是 ID3 标签里的艺术家与专辑，模型会把它当文件正文引用。
            throw new AudioNotTranscribedException(audioNotice(displayName(fileName), null));
        }
        String ext = extension(fileName);
        if (PLAIN_TEXT_EXTENSIONS.contains(ext)) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.startsWith("﻿") ? text.substring(1) : text;
        }
        boolean pdf = "pdf".equals(ext);
        boolean ocrSupported = isOcrSupported(fileName);
        String text = null;
        if (!ocrSupported || pdf) {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                text = pdf ? documentTextService.parsePdf(in) : documentTextService.parse(in);
            } catch (TikaException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        // 与 extractText 同一份判据（PdfTextLayer）：扫描件残留的几个字符不算正文
        boolean usable = pdf ? PdfTextLayer.isUsable(text) : StringUtils.hasText(text);
        if (!usable && ocrSupported) {
            throw new IOException(NEEDS_OCR);
        }
        return text == null ? "" : text;
    }

    public boolean isOcrSupported(String fileName) {
        return fileContentExtractorService.isOcrSupported(fileName);
    }

    private String extractTextLayer(ProjectFile pf) throws IOException {
        try {
            return documentTextService.extractText(pf);
        } catch (TikaException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private String ocrProjectFile(ProjectFile pf) throws OcrFailedException {
        byte[] bytes;
        try {
            bytes = projectFileService.getFileBytes(pf.getId());
        } catch (Exception e) {
            log.warn("OCR extraction failed for fileId={}", pf.getId(), e);
            throw new OcrFailedException("cloud OCR failed on '" + pf.getName() + "' — " + e.getMessage(), e);
        }
        if (bytes == null || bytes.length == 0) {
            throw new OcrFailedException("file '" + pf.getName() + "' is empty on disk, nothing to recognise.", null);
        }
        return ocr(pf.getName(), ocrTempSuffix(pf), bytes, "fileId=" + pf.getId());
    }

    /**
     * 走与 {@code read_file} 相同的 OCR 分支（extractTextWithOcr → OcrService）。
     * 临时文件必须保留原扩展名：extractTextWithOcr 按文件名判断走图片还是 PDF 分支。
     */
    private String ocr(String name, String suffix, byte[] bytes, String logKey) throws OcrFailedException {
        Path tempPath = null;
        try {
            tempPath = Files.createTempFile("checkba_ocr_", suffix);
            Files.write(tempPath, bytes);
            String result = fileContentExtractorService.extractTextWithOcr(tempPath.toFile());
            // extractTextWithOcr 的失败以 "[System: ...]" 形态返回（非空、无 Error 前缀），
            // 直接透传会被判成成功并当作正文喂给模型
            if (result != null && result.startsWith("[System:")) {
                throw new OcrFailedException("cloud OCR failed on '" + name + "' — "
                        + result.substring("[System:".length()).replace("]", "").trim(), null);
            }
            return result == null ? "" : result;
        } catch (OcrFailedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OCR extraction failed for {}", logKey, e);
            throw new OcrFailedException("cloud OCR failed on '" + name + "' — " + e.getMessage(), e);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (Exception ignore) {
                    // 临时目录会被系统回收，删不掉不影响结果
                }
            }
        }
    }

    private static boolean isFolder(ProjectFile pf) {
        return "folder".equalsIgnoreCase(pf.getFileType()) || Boolean.TRUE.equals(pf.getIsFolder());
    }

    /** PDF 可能带文字层，值得先抽一次；其余 OCR 格式（jpg/png/...）没有文字层，抽也是空。 */
    private static boolean isPdf(String name, String fileType) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return "pdf".equalsIgnoreCase(fileType) || lower.endsWith(".pdf");
    }

    private static String ocrTempSuffix(ProjectFile pf) {
        String name = pf.getName() == null ? "" : pf.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot);
        }
        return StringUtils.hasText(pf.getFileType()) ? "." + pf.getFileType() : ".tmp";
    }

    /** 取最后一段路径的扩展名（小写，无点）；没有扩展名返回空串。 */
    private static String extension(String fileName) {
        String name = displayName(fileName);
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String displayName(String fileName) {
        String name = fileName == null ? "" : fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    /**
     * 音频的「正文」就是它的转写稿（dev-board#814）。
     *
     * <p>关联用的是既有的 {@code meeting_recording.audio_file_id}——面板录音建档与资源管理器
     * 右键「转写音频」两条路径都写这一列，所以不需要在 project_file 上新开字段。
     *
     * <p>注入转写稿时顶一句横幅，口径同 OCR 降级那段（「降级必须明示」）：模型必须知道
     * 这是机器语音识别的产物、它听不到音频本身，否则会把识别误差当成庭审原话来引用。
     *
     * @return 带横幅的转写稿
     * @throws AudioNotTranscribedException 还没有转写稿，message 是一句可行动的下一步
     */
    private String audioText(ProjectFile pf) throws AudioNotTranscribedException {
        MeetingRecording meeting = meetings == null
                ? null
                : meetings.findByAudioFile(pf.getProjectId(), pf.getId()).orElse(null);
        if (meeting != null && MeetingRecording.STATUS_TRANSCRIBED.equals(meeting.getStatus())) {
            String transcript = meetings.renderTranscriptText(meeting);
            if (StringUtils.hasText(transcript)) {
                return transcriptBanner(pf.getName()) + transcript;
            }
        }
        throw new AudioNotTranscribedException(audioNotice(pf.getName(), meeting));
    }

    private static String transcriptBanner(String name) {
        return LangText.of(
                "[以下为音频「" + name + "」的转写稿，由机器语音识别生成，可能有识别误差；"
                        + "你听不到音频本身。引用时以转写稿原文为准，不要臆测听不清的部分。]\n\n",
                "[The following is the machine transcript of the audio \"" + name + "\". It was produced by "
                        + "speech recognition and may contain errors; you cannot hear the audio itself. "
                        + "Quote the transcript as written and do not guess at unclear passages.]\n\n");
    }

    /**
     * 「这是音频，先去转写」——一句话说清是什么、为什么读不了、下一步点哪里。
     *
     * <p>刻意不提 OCR、也不提 extract_file_text：改动前那句 Warning 把模型指向这两条路，
     * 对音频没有一条走得通，模型在它们之间空转几轮之后告诉用户文件读不了。
     */
    public static String audioNotice(String name, MeetingRecording meeting) {
        String state = LangText.of("尚未转写", "it has not been transcribed yet");
        if (meeting != null) {
            state = switch (meeting.getStatus()) {
                case MeetingRecording.STATUS_TRANSCRIBING ->
                        LangText.of("转写进行中，请等它完成", "transcription is still running; wait for it to finish");
                case MeetingRecording.STATUS_FAILED ->
                        LangText.of("上一次转写失败，可以让用户重试转写",
                                "the last transcription failed; the user can retry it");
                case MeetingRecording.STATUS_EMPTY ->
                        LangText.of("转写完成但没有识别到人声", "transcription finished but no speech was recognised");
                case MeetingRecording.STATUS_RECORDING ->
                        LangText.of("录音还没结束", "the recording has not finished yet");
                // 状态是「已转写」却走到这里 = 转写稿是空的（落库损坏/被清过）。
                // 说「尚未转写」会和面板上那个「已转写」徽标直接打架，用户只会以为 AI 在胡说。
                case MeetingRecording.STATUS_TRANSCRIBED ->
                        LangText.of("记录显示已转写，但转写稿是空的，需要重新转写",
                                "it is marked as transcribed but the transcript is empty; it needs transcribing again");
                default -> LangText.of("尚未转写", "it has not been transcribed yet");
            };
        }
        return LangText.of(
                "「" + name + "」是音频文件，需要先转写成文字才能读："
                        + state + "。请用户在文件树里右键该文件选「转写音频」（或在左栏「会议录音」面板里转写）；"
                        + "如果手头已经有转写稿，让用户把转写稿文件作为附件发过来。",
                "'" + name + "' is an audio file and must be transcribed before it can be read: "
                        + state + ". Ask the user to right-click the file in the file tree and choose "
                        + "\"Transcribe audio\" (or transcribe it in the Meeting Recording panel in the sidebar). "
                        + "If they already have a transcript, ask them to attach the transcript file instead.");
    }

    /**
     * 按<b>路径</b>读到音频时的说法（{@code read_file}）。
     *
     * <p>与 {@link #audioNotice} 分开，是因为这条路拿不到 fileId，也就查不到会议记录——
     * 套用那句「尚未转写」会在音频其实早就转写完的时候直接说反。这里只说事实
     *（这是音频、正文是转写稿），再把模型指向真正查得到转写稿的入口。
     */
    public static String audioNoticeByPath(String name) {
        return LangText.of(
                "「" + name + "」是音频文件，正文是它的转写稿，按路径读不到。"
                        + "如果它已经转写过，用 extract_file_text 配它的数据库 fileId 就能拿到转写稿；"
                        + "还没转写的话，请用户在文件树里右键该文件选「转写音频」。",
                "'" + name + "' is an audio file; its readable content is its transcript, which cannot be "
                        + "reached by path. If it has already been transcribed, call extract_file_text with its "
                        + "database fileId to get the transcript; if not, ask the user to right-click the file "
                        + "in the file tree and choose \"Transcribe audio\".");
    }

    /**
     * 音频还没有转写稿。<b>不是错误</b>——文件本身好好的，只是这一步还没做，
     * 所以调用方把它转成 {@code Warning:} 而不是 {@code Error:}（两者都会被
     * {@code ContextAssemblerService.isToolFailureText} 认出来、不进 &lt;file&gt; 的 CDATA）。
     */
    public static class AudioNotTranscribedException extends IOException {
        public AudioNotTranscribedException(String message) {
            super(message);
        }
    }

    /**
     * OCR 分支的失败：message 保持 extract_file_text 一直以来的英文措辞（该工具前面拼 "Error: " 即原样），
     * 底层原因（Credits 不足、OCR 未开放、上游报错）原样带出，模型才能转述真实原因。
     */
    public static class OcrFailedException extends IOException {
        public OcrFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
