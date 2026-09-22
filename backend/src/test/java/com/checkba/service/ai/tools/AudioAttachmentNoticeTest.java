// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.config.AiContextProperties;
import com.checkba.model.entity.MeetingRecording;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.DocumentTextService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.file.ProjectFileTextExtractor;
import com.checkba.service.meeting.MeetingRecordingService;
import com.checkba.service.OcrService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音频附件的三个工具入口（dev-board#814，审查 E-12）：read_document / extract_file_text（按 fileId）
 * 与 read_file（按路径）。
 *
 * <p>病灶：mp3/m4a/wav 既不在 ocr-extensions 也不在纯文本白名单，落进 Tika 抽不出正文，
 * {@code read_document} 于是回一句
 * 「the file may be empty, or an image whose OCR recognised nothing … try extract_file_text」。
 * 对音频这三条建议没有一条成立：它不是空文件、不是图片、extract_file_text 走的是同一条路。
 * 模型据此要么说「我看不到这个文件」，要么在无用的工具之间空转几轮——而这个产品自己
 * 就带着会议录音、听悟转写与文件树右键「转写音频」。
 *
 * <p>这里钉的是<b>工具返回值的形状</b>：Warning: 前缀（让 ContextAssembler 的失败回执守卫
 * 认出来、不当正文注进 CDATA）+ 一句可行动的下一步 + 有转写稿时直接给转写稿。
 */
class AudioAttachmentNoticeTest {

    private final ProjectFileService fileService = Mockito.mock(ProjectFileService.class);
    private final ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
    private final MeetingRecordingService meetings = Mockito.mock(MeetingRecordingService.class);
    private final DocumentTextService documentTextService =
            new DocumentTextService(Mockito.mock(StorageServiceFactory.class));
    private final FileContentExtractorService fileContent = new FileContentExtractorService(
            Mockito.mock(OcrService.class), new AiContextProperties());
    private final ProjectFileTextExtractor textExtractor =
            new ProjectFileTextExtractor(documentTextService, fileContent, fileService, null, meetings);

    private final LegalTools legalTools = new LegalTools(fileService, null, fileContent, textExtractor);
    private final FileTools fileTools = new FileTools(
            fileService, repo, null, fileContent, null, documentTextService, null, null, textExtractor);

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private ProjectFile audio(String name) {
        ProjectContextHolder.setProjectId("7");
        ProjectFile f = new ProjectFile();
        f.setId(42L);
        f.setProjectId(7L);
        f.setName(name);
        f.setFileType(name.substring(name.lastIndexOf('.') + 1));
        f.setFilePath("projects/7/" + name);
        f.setIsFolder(false);
        Mockito.when(fileService.getFile(42L)).thenReturn(f);
        Mockito.when(repo.findById(42L)).thenReturn(Optional.of(f));
        return f;
    }

    private MeetingRecording transcribed() {
        MeetingRecording m = new MeetingRecording();
        m.setId(9L);
        m.setProjectId(7L);
        m.setAudioFileId(42L);
        m.setStatus(MeetingRecording.STATUS_TRANSCRIBED);
        return m;
    }

    @Test
    @DisplayName("read_document 对未转写音频给可行动说明，不再指向 OCR / extract_file_text")
    void readDocumentTellsTheUserHowToTranscribe() {
        audio("开庭录音.mp3");
        Mockito.when(meetings.findByAudioFile(7L, 42L)).thenReturn(Optional.empty());

        String out = legalTools.read_document("42");

        assertTrue(out.startsWith("Warning: "),
                "前缀要让 ContextAssembler 的失败回执守卫认出来，实际是：" + out);
        assertTrue(out.contains("转写音频"), "要指到文件树右键那条真实入口，实际是：" + out);
        assertFalse(out.contains("OCR"), "OCR 对音频没有一点用，实际是：" + out);
        assertFalse(out.contains("extract_file_text"),
                "那是同一条抽取路由，指过去只会再空转一轮，实际是：" + out);
    }

    @Test
    @DisplayName("read_document 对已转写音频直接给转写稿，并声明这是机器转写")
    void readDocumentReturnsTheTranscript() {
        audio("开庭录音.mp3");
        MeetingRecording m = transcribed();
        Mockito.when(meetings.findByAudioFile(7L, 42L)).thenReturn(Optional.of(m));
        Mockito.when(meetings.renderTranscriptText(m))
                .thenReturn("[00:00] 审判长：现在开庭。\n[00:08] 原告代理人：收到。\n");

        String out = legalTools.read_document("42");

        assertFalse(out.startsWith("Warning"), "有转写稿就不该再说读不了，实际是：" + out);
        assertTrue(out.contains("现在开庭。"), "转写稿正文要在，实际是：" + out);
        assertTrue(out.contains("转写稿"), "要明示这是转写稿而不是音频本身，实际是：" + out);
        assertTrue(out.contains("识别误差"), "机器转写的误差必须挑明，实际是：" + out);
    }

    /**
     * 第三个入口：按路径读（{@code read_file}）。这条拿不到 fileId，也就查不到转写稿——
     * 但更要紧的是 Tika 对 mp3 抽回来的是 ID3 标签里的标题/艺术家/专辑，<b>非空</b>，
     * 会被当成「文件正文」原样喂给模型。所以它也要认音频，并且不许套用「尚未转写」
     * 那句话（音频其实早就转写完时会直接说反）。
     */
    @Test
    @DisplayName("read_file 按路径读到音频时不把 ID3 标签当正文，也不谎报转写状态")
    void readFileByPathRecognisesAudioWithoutAssertingItsState() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("awd_audio_");
        java.nio.file.Path mp3 = root.resolve("开庭录音.mp3");
        java.nio.file.Files.write(mp3, new byte[]{0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, 0});
        ProjectContextHolder.setProjectId("7");
        com.checkba.storage.ProjectStorageResolver resolver =
                Mockito.mock(com.checkba.storage.ProjectStorageResolver.class);
        Mockito.when(resolver.projectRoot(7L)).thenReturn(root);
        // 构造器第 5 位才是 storageResolver（第 3 位是 editorBridgeService）
        FileTools byPath = new FileTools(
                fileService, repo, null, fileContent, resolver, documentTextService, null, null, textExtractor);

        String out = byPath.read_file("开庭录音.mp3");

        assertTrue(out.startsWith("Warning: "), "实际是：" + out);
        assertTrue(out.contains("转写稿"), "要说清正文是转写稿，实际是：" + out);
        assertTrue(out.contains("extract_file_text"),
                "按路径查不到转写稿，必须指向查得到的那个入口，实际是：" + out);
        assertFalse(out.contains("尚未转写"),
                "这条路根本没查过会议记录，不许断言它没转写过，实际是：" + out);
    }

    @Test
    @DisplayName("extract_file_text 与 read_document 对音频口径一致")
    void extractFileTextMatchesReadDocument() {
        audio("会见录音.m4a");
        Mockito.when(meetings.findByAudioFile(7L, 42L)).thenReturn(Optional.empty());

        String out = fileTools.extract_file_text(42L);

        assertTrue(out.startsWith("Warning: "), "实际是：" + out);
        assertTrue(out.contains("转写"), "实际是：" + out);
        assertFalse(out.contains("OCR"), "实际是：" + out);
    }
}
