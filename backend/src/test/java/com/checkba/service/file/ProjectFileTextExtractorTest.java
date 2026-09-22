// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.file;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.DocumentTextService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.meeting.MeetingRecordingService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 项目文件文字抽取器（dev-board#718）：extract_file_text、云端项目参考来源、桌面端参考读取共用同一条抽取路由。
 *
 * <p>要守住的东西：
 * <ol>
 *   <li>真实文件经存储层读出、原文返回（不是只测 mock 的连线）；</li>
 *   <li>参考材料入口挡住文件夹与超过 50MB 的文件，且挡在读字节之前；</li>
 *   <li>OCR 的「[System: …]」失败形态必须变成异常，不能被当正文；</li>
 *   <li>不在项目文件表里的字节（案件库、git）按扩展名路由：纯文本按 UTF-8，PDF 走 PDFBox，其余走 Tika；</li>
 *   <li>只有工具入口 extractText 走 OCR，参考入口（extract / extractBytes）一律拒绝——
 *       平台代采档的 OCR 按页扣 Credits，而 PRIVACY 对参考材料承诺的是不扣费。</li>
 * </ol>
 */
class ProjectFileTextExtractorTest {

    private final StorageServiceFactory storageFactory = mock(StorageServiceFactory.class);
    private final StorageService storage = mock(StorageService.class);
    private final FileContentExtractorService ocr = mock(FileContentExtractorService.class);
    private final ProjectFileService fileService = mock(ProjectFileService.class);
    private final DocumentTextService documentTextService = new DocumentTextService(storageFactory);
    private final MeetingRecordingService meetings = mock(MeetingRecordingService.class);
    private final ProjectFileTextExtractor extractor =
            new ProjectFileTextExtractor(documentTextService, ocr, fileService, null, meetings);

    private static ProjectFile file(long id, String name, String type, String path, Long size) {
        ProjectFile pf = new ProjectFile();
        pf.setId(id);
        pf.setProjectId(7L);
        pf.setName(name);
        pf.setFileType(type);
        pf.setFilePath(path);
        pf.setFileSize(size);
        pf.setIsFolder(false);
        return pf;
    }

    @Test
    void extractReadsProjectFileThroughStorage(@TempDir Path tmp) throws Exception {
        Path onDisk = tmp.resolve("说明.txt");
        Files.writeString(onDisk, "第一条 甲方应于十日内付款。", StandardCharsets.UTF_8);
        when(storageFactory.getStorageService()).thenReturn(storage);
        when(storage.load("projects/7/说明.txt")).thenReturn(new FileSystemResource(onDisk));

        String text = extractor.extract(file(1L, "说明.txt", "txt", "projects/7/说明.txt", Files.size(onDisk)));

        assertThat(text.trim()).isEqualTo("第一条 甲方应于十日内付款。");
    }

    @Test
    void extractRefusesFilesOver50MbBeforeReadingBytes() throws Exception {
        ProjectFile huge = file(2L, "证据.pdf", "pdf", "projects/7/证据.pdf", 60L * 1024 * 1024);

        assertThatThrownBy(() -> extractor.extract(huge))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("50MB");
        verifyNoInteractions(storageFactory);
        verify(fileService, never()).getFileBytes(any());
    }

    @Test
    void extractRefusesFolders() {
        ProjectFile dir = file(3L, "合同", null, null, null);
        dir.setIsFolder(true);

        assertThatThrownBy(() -> extractor.extract(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("这是一个文件夹");
        verifyNoInteractions(storageFactory);
    }

    @Test
    void ocrSystemFailureBecomesAnExceptionWithTheRealReason() throws Exception {
        ProjectFile img = file(4L, "扫描件.jpg", "jpg", "projects/7/扫描件.jpg", 3L);
        when(ocr.isOcrSupported("扫描件.jpg")).thenReturn(true);
        when(fileService.getFileBytes(4L)).thenReturn(new byte[]{1, 2, 3});
        when(ocr.extractTextWithOcr(any(File.class))).thenReturn("[System: OCR 识别失败: Credits 余额不足]");

        assertThatThrownBy(() -> extractor.extractText(img))
                .isInstanceOf(ProjectFileTextExtractor.OcrFailedException.class)
                .hasMessageContaining("Credits 余额不足");
        // 图片没有文字层，不许白抽一次 Tika
        verifyNoInteractions(storageFactory);
    }

    /**
     * 参考读取不许走 OCR：平台代采档的 OCR 按页扣 Credits，而 legal/PRIVACY.md 对参考材料
     * 白纸黑字写的是「不产生 Credits 扣费」。扣了钱用户既没被问过，也没法拒绝。
     */
    @Test
    void referenceReadOfAScanRefusesInsteadOfSpendingOcrCredits() throws Exception {
        ProjectFile img = file(4L, "扫描件.jpg", "jpg", "projects/7/扫描件.jpg", 3L);
        when(ocr.isOcrSupported("扫描件.jpg")).thenReturn(true);

        assertThatThrownBy(() -> extractor.extract(img))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("文字识别");
        verify(ocr, never()).extractTextWithOcr(any(File.class));
        verifyNoInteractions(fileService);
    }

    @Test
    void extractBytesDecodesPlainTextAsUtf8() throws Exception {
        assertThat(extractor.extractBytes("a.txt", "中文".getBytes(StandardCharsets.UTF_8))).isEqualTo("中文");
        assertThat(extractor.extractBytes("资料/说明.MD", "# 标题".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("# 标题");
    }

    @Test
    void extractBytesParsesOfficeFormatsWithTika() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("本合同由甲乙双方签订。");
            doc.write(out);
        }

        assertThat(extractor.extractBytes("合同/主合同.docx", out.toByteArray())).contains("本合同由甲乙双方签订。");
    }

    /** 案件库/git 的字节也是参考材料，同样不许替用户掏 OCR 的 Credits。 */
    @Test
    void extractBytesOfAScannedPdfRefusesInsteadOfSpendingOcrCredits() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage()); // 没有文字层的一页，等同扫描件
            doc.save(out);
        }
        when(ocr.isOcrSupported("卷宗/判决书.pdf")).thenReturn(true);

        assertThatThrownBy(() -> extractor.extractBytes("卷宗/判决书.pdf", out.toByteArray()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("文字识别");
        verify(ocr, never()).extractTextWithOcr(any(File.class));
    }

    @Test
    void extractBytesRefusesOver50Mb() {
        byte[] big = new byte[(int) ProjectFileTextExtractor.MAX_BYTES + 1];

        assertThatThrownBy(() -> extractor.extractBytes("大文件.docx", big))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("50MB");
    }

    // --- 音频（dev-board#814）---------------------------------------------------------
    //
    // 改动前：mp3/m4a/wav 既不在 ocr-extensions 也不在纯文本白名单，于是落进 Tika，
    // 抽出来的要么是空串、要么是 ID3 标签里的「艺术家/专辑」，read_document 随后回一句
    // 「the file may be empty, or an image whose OCR recognised nothing … try extract_file_text」。
    // 对音频而言那三条建议没有一条成立，模型据此告诉用户「我看不到这个文件」，
    // 而这个产品自己就带着会议录音与听悟转写。

    private static ProjectFile audio(long id, String name) {
        return file(id, name, name.substring(name.lastIndexOf('.') + 1), "projects/7/" + name, 1024L);
    }

    private static MeetingRecording meeting(String status, Long audioFileId) {
        MeetingRecording m = new MeetingRecording();
        m.setId(99L);
        m.setProjectId(7L);
        m.setAudioFileId(audioFileId);
        m.setStatus(status);
        return m;
    }

    @Test
    void audioWithoutAnyTranscriptSaysHowToGetOneInsteadOfPointingAtOcr() {
        ProjectFile mp3 = audio(11L, "开庭录音.mp3");
        when(meetings.findByAudioFile(7L, 11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> extractor.extractText(mp3))
                .isInstanceOf(ProjectFileTextExtractor.AudioNotTranscribedException.class)
                .hasMessageContaining("音频")
                .hasMessageContaining("转写");
        // 抽不出来不是因为「没试」：音频根本不该交给 Tika 或 OCR 走一趟
        verifyNoInteractions(storageFactory);
        verify(ocr, never()).extractTextWithOcr(any(File.class));
        verifyNoInteractions(fileService);
    }

    @Test
    void audioNoticeNamesTheStatusWhenTranscriptionIsStillRunning() {
        ProjectFile mp3 = audio(12L, "会见录音.m4a");
        when(meetings.findByAudioFile(7L, 12L))
                .thenReturn(Optional.of(meeting(MeetingRecording.STATUS_TRANSCRIBING, 12L)));

        assertThatThrownBy(() -> extractor.extractText(mp3))
                .isInstanceOf(ProjectFileTextExtractor.AudioNotTranscribedException.class)
                .hasMessageContaining("转写进行中");
    }

    @Test
    void audioNoticeSaysSoWhenTranscriptionFailed() {
        ProjectFile mp3 = audio(13L, "谈话.wav");
        when(meetings.findByAudioFile(7L, 13L))
                .thenReturn(Optional.of(meeting(MeetingRecording.STATUS_FAILED, 13L)));

        assertThatThrownBy(() -> extractor.extractText(mp3))
                .isInstanceOf(ProjectFileTextExtractor.AudioNotTranscribedException.class)
                .hasMessageContaining("转写失败");
    }

    /** 已转写的音频：正文换成转写稿，且顶着一句「这是机器转写、你听不到音频本身」的横幅。 */
    @Test
    void transcribedAudioIsInjectedAsItsTranscriptWithAnExplicitBanner() throws Exception {
        ProjectFile mp3 = audio(14L, "股东会.mp3");
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBED, 14L);
        when(meetings.findByAudioFile(7L, 14L)).thenReturn(Optional.of(m));
        when(meetings.renderTranscriptText(m)).thenReturn("[00:00] 说话人1：现在开会。\n");

        String text = extractor.extractText(mp3);

        assertThat(text).contains("转写稿").contains("股东会.mp3").contains("现在开会。");
        assertThat(text).doesNotStartWith("[00:00]");
    }

    /**
     * 转写稿是<b>会后才出现</b>的：音频字节一个都没变，mtime+size 指纹也就一个字节都没变。
     * 一旦把「这是音频，请先转写」写进 project_file_text_cache，用户转写完成之后这条会话
     * 永远读不到转写稿——而且不报错。所以音频这条分支必须完全绕开缓存。
     */
    @Test
    void audioNeverTouchesTheTextCacheInEitherDirection() throws Exception {
        ProjectFileTextCacheService cache = mock(ProjectFileTextCacheService.class);
        ProjectFileTextExtractor cached =
                new ProjectFileTextExtractor(documentTextService, ocr, fileService, cache, meetings);
        ProjectFile mp3 = audio(15L, "庭审.mp3");
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBED, 15L);
        when(meetings.findByAudioFile(7L, 15L)).thenReturn(Optional.of(m));
        when(meetings.renderTranscriptText(m)).thenReturn("[00:00] 说话人1：开庭。\n");

        assertThat(cached.extractText(mp3)).contains("开庭。");

        verifyNoInteractions(cache);
    }

    /**
     * 状态是「已转写」但转写稿是空的（落库损坏 / 被清过）：说法不能退回「尚未转写」——
     * 那和面板上那个「已转写」徽标直接打架，用户只会以为 AI 在胡说。
     */
    @Test
    void transcribedButEmptyTranscriptSaysSoInsteadOfContradictingThePanel() {
        ProjectFile mp3 = audio(16L, "谈判.mp3");
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBED, 16L);
        when(meetings.findByAudioFile(7L, 16L)).thenReturn(Optional.of(m));
        when(meetings.renderTranscriptText(m)).thenReturn("");

        assertThatThrownBy(() -> extractor.extractText(mp3))
                .isInstanceOf(ProjectFileTextExtractor.AudioNotTranscribedException.class)
                .hasMessageContaining("转写稿是空的");
    }

    /** 参考读取（案件库/git 的裸字节）拿不到 project_file，也就查不到转写稿——照样不许交给 Tika。 */
    @Test
    void extractBytesOfAudioAlsoAsksForATranscript() {
        assertThatThrownBy(() -> extractor.extractBytes("卷宗/询问笔录.amr", new byte[]{1, 2, 3}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("转写");
    }
}
