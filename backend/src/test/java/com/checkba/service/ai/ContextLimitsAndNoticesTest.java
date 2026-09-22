// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.FileContextLoader;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.service.ai.skill.SkillRouter;
import com.checkba.service.ai.tools.LegalTools;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上下文层的上限与降级可见性（dev-board#793 K14 / #801 K21）。
 *
 * <p>守的是审查发现 E-4 / E-7 / E-10 / E-14 与 verify.missed 里那两条更严重的：
 * ① 有附件时活跃文档整个被挤掉（互斥）；② 多个文件夹各自拿满额度、绕开 10 份总闸；
 * ③ 视觉直送的图片不计配额；④ 每一次截断/丢弃/降级都必须有一条可见的 notice，
 * 而不是只在 system prompt 里留一句模型未必会转述的英文 System Note。
 */
class ContextLimitsAndNoticesTest {

    private LegalTools legalTools;
    private FileContextLoader fileContextLoader;
    private ChatModelFactory chatModelFactory;
    private com.checkba.service.ProjectFileService projectFileService;
    private AiContextProperties properties;
    private ContextAssemblerService assembler;

    /** 收集本轮全部 notice / attachment，断言直接查它。 */
    private static final class RecordingSink implements ContextTurnSink {
        final List<String> notices = new ArrayList<>();
        final List<String> attachments = new ArrayList<>();

        @Override
        public void attachment(String fileId, String name, String fileType, String kind, boolean visionUsed) {
            attachments.add(kind + ":" + fileId + ":" + name + ":" + visionUsed);
        }

        @Override
        public void notice(String kind, String fileId, String name, String detail) {
            notices.add(kind + ":" + fileId + ":" + name);
        }

        boolean hasNotice(String kind, String fileId) {
            return notices.stream().anyMatch(n -> n.startsWith(kind + ":" + fileId + ":"));
        }
    }

    @BeforeEach
    void setUp() {
        legalTools = mock(LegalTools.class);
        ProjectAiMessageService messageService = mock(ProjectAiMessageService.class);
        when(messageService.listByConversationId(anyString())).thenReturn(Collections.emptyList());
        fileContextLoader = mock(FileContextLoader.class);
        when(fileContextLoader.buildFolderContextCounted(anyString(), anyString(), anyInt()))
                .thenReturn(new FileContextLoader.FolderContext("### Directory Content:\n", 0, 0));
        SkillRouter skillRouter = mock(SkillRouter.class);
        when(skillRouter.match(anyString())).thenReturn(Optional.empty());
        MemoryManager memoryManager = mock(MemoryManager.class);
        when(memoryManager.getProjectMemory(anyLong())).thenReturn(Optional.empty());
        when(memoryManager.retrieveMemories(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(memoryManager.retrieveUserMemories(anyLong(), anyInt())).thenReturn(Collections.emptyList());
        ContextCompressor contextCompressor = mock(ContextCompressor.class);
        when(contextCompressor.needsCompression(any(), any())).thenReturn(false);
        com.checkba.service.AppLanguageService appLanguageService =
                mock(com.checkba.service.AppLanguageService.class);
        chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.effectiveModelSupportsVision(any())).thenReturn(false);
        projectFileService = mock(com.checkba.service.ProjectFileService.class);
        properties = new AiContextProperties();

        assembler = new ContextAssemblerService(
                legalTools, messageService, fileContextLoader, properties, skillRouter,
                new ClientCapabilityService(), new InlineContentCache(),
                memoryManager, contextCompressor, appLanguageService,
                chatModelFactory, projectFileService);
    }

    private static AiAgentController.ContextItem file(String id, String name) {
        AiAgentController.ContextItem item = new AiAgentController.ContextItem();
        item.setId(id);
        item.setName(name);
        item.setIsDir(false);
        return item;
    }

    private static AiAgentController.ContextItem folder(String id, String name) {
        AiAgentController.ContextItem item = new AiAgentController.ContextItem();
        item.setId(id);
        item.setName(name);
        item.setIsDir(true);
        return item;
    }

    private String systemTextOf(List<AiAgentController.ContextItem> items,
                                AiAgentController.ContextItem activeContext,
                                ContextTurnSink sink) {
        List<ChatMessage> messages = assembler.assemble(
                "conv-1", "run-1", "看看这些材料", items, activeContext,
                null, null, "88", AgentMode.AGENT, 1L, null, sink);
        return ((SystemMessage) messages.get(0)).text();
    }

    // ---------- E-4：附件与活跃文档不再二选一 ----------

    @Test
    @DisplayName("没有附件时活跃文档照旧注入正文")
    void activeDocumentKeepsItsBodyWhenNothingElseIsAttached() {
        when(legalTools.read_document("7")).thenReturn("第一条 合同标的：办公楼一栋。");
        String systemText = systemTextOf(null, file("7", "认购协议.docx"), ContextTurnSink.NOOP);

        assertTrue(systemText.contains("<active_document"), "活跃文档段必须在");
        assertTrue(systemText.contains("第一条 合同标的"), "无附件时必须注入正文");
    }

    @Test
    @DisplayName("有附件时活跃文档仍然带上，但只带壳（id/name + readHint），不注入正文")
    void activeDocumentIsStillAnnouncedWhenAttachmentsArePresentButOnlyAsAShell() {
        when(legalTools.read_document("7")).thenReturn("第一条 合同标的：办公楼一栋。");
        when(legalTools.read_document("9")).thenReturn("对方发来的补充协议正文。");

        String systemText = systemTextOf(List.of(file("9", "补充协议.docx")),
                file("7", "认购协议.docx"), ContextTurnSink.NOOP);

        assertTrue(systemText.contains("# Active Document"),
                "有附件时活跃文档也必须出现——原来这里整段消失（互斥），"
                        + "模型既不知道有活跃文档也不知道往哪儿写");
        assertTrue(systemText.contains("认购协议.docx"), "活跃文档的名字要给模型");
        assertFalse(systemText.contains("第一条 合同标的"),
                "有附件时活跃文档只带壳，正文交给 doc_get_document_text 自取（控 token）");
        assertTrue(systemText.contains("对方发来的补充协议正文。"), "附件正文照旧注入");
    }

    // ---------- E-10：活跃文档的截断上限与普通附件解耦 ----------

    @Test
    @DisplayName("活跃文档按自己的上限截断，不再被 max-chars-per-file 再砍一刀")
    void activeDocumentTruncatesOnItsOwnLimitAndSaysSo() {
        properties.getFiles().setMaxCharsPerFile(100);
        properties.getFiles().setMaxCharsActiveDocument(500);
        String body = "甲".repeat(300);
        when(legalTools.read_document("7")).thenReturn(body);

        RecordingSink sink = new RecordingSink();
        String systemText = systemTextOf(null, file("7", "长合同.docx"), sink);

        assertTrue(systemText.contains(body),
                "300 字在活跃文档上限（500）之内，不该被附件上限（100）截断");
        assertFalse(sink.hasNotice(ContextTurnSink.TRUNCATED, "7"), "没截断就不该报截断");
    }

    @Test
    @DisplayName("活跃文档真超上限时截断并发一条 truncated 通知")
    void activeDocumentOverItsOwnLimitIsTruncatedAndReported() {
        properties.getFiles().setMaxCharsActiveDocument(200);
        when(legalTools.read_document("7")).thenReturn("甲".repeat(1000));

        RecordingSink sink = new RecordingSink();
        String systemText = systemTextOf(null, file("7", "长合同.docx"), sink);

        assertTrue(systemText.contains("TRUNCATED"), "截断标记要留给模型");
        assertTrue(sink.hasNotice(ContextTurnSink.TRUNCATED, "7"),
                "截断必须对用户可见：「通篇审一下」只审了前三分之一是错误输出");
    }

    @Test
    @DisplayName("普通附件超 max-chars-per-file 同样发 truncated 通知")
    void attachmentTruncationIsAlsoReported() {
        properties.getFiles().setMaxCharsPerFile(50);
        when(legalTools.read_document("9")).thenReturn("乙".repeat(400));

        RecordingSink sink = new RecordingSink();
        systemTextOf(List.of(file("9", "对方合同.docx")), null, sink);

        assertTrue(sink.hasNotice(ContextTurnSink.TRUNCATED, "9"));
    }

    // ---------- E-14 + missed①：总闸真正生效 ----------

    @Test
    @DisplayName("超过 max-files-per-context 的附件被丢弃时逐条报出来，不再静默砍尾")
    void itemsBeyondTheFileLimitAreNamedNotSilentlyDropped() {
        properties.getFiles().setMaxFilesPerContext(2);
        when(legalTools.read_document(anyString())).thenReturn("正文");

        RecordingSink sink = new RecordingSink();
        systemTextOf(List.of(file("1", "a.docx"), file("2", "b.docx"),
                file("3", "c.docx"), file("4", "d.docx")), null, sink);

        assertFalse(sink.hasNotice(ContextTurnSink.DROPPED, "1"));
        assertFalse(sink.hasNotice(ContextTurnSink.DROPPED, "2"));
        assertTrue(sink.hasNotice(ContextTurnSink.DROPPED, "3"), "第 3 份被丢了要说");
        assertTrue(sink.hasNotice(ContextTurnSink.DROPPED, "4"), "第 4 份也要说，不能只报第一条");
    }

    @Test
    @DisplayName("文件夹读掉的份数要计进总闸，三个文件夹不能各拿满额")
    void foldersConsumeTheSharedQuota() {
        properties.getFiles().setMaxFilesPerContext(10);
        // 第一个文件夹读了 6 份，第二个只应拿到剩下的 4 份额度
        when(fileContextLoader.buildFolderContextCounted("100", "88", 0))
                .thenReturn(new FileContextLoader.FolderContext("folder-a", 6, 0));
        when(fileContextLoader.buildFolderContextCounted("200", "88", 6))
                .thenReturn(new FileContextLoader.FolderContext("folder-b", 4, 0));

        RecordingSink sink = new RecordingSink();
        String systemText = systemTextOf(
                List.of(folder("100", "证据一"), folder("200", "证据二"), file("5", "e.docx")),
                null, sink);

        assertTrue(systemText.contains("folder-a"));
        assertTrue(systemText.contains("folder-b"));
        // 两个文件夹合计已经用掉 10 份额度，后面那份单文件必须被挡下并报出来
        assertTrue(sink.hasNotice(ContextTurnSink.DROPPED, "5"),
                "额度用完后的条目要被挡下——原来 totalFileCount 从不递增，"
                        + "三个文件夹能一次注入 30 份正文");
    }

    @Test
    @DisplayName("文件夹里读不出正文的文件要报一条 unreadable")
    void unreadableFilesInsideAFolderAreReported() {
        when(fileContextLoader.buildFolderContextCounted("100", "88", 0))
                .thenReturn(new FileContextLoader.FolderContext("folder-a", 2, 3));

        RecordingSink sink = new RecordingSink();
        systemTextOf(List.of(folder("100", "现场照片")), null, sink);

        assertTrue(sink.hasNotice(ContextTurnSink.UNREADABLE, "100"));
    }

    @Test
    @DisplayName("读不出正文的单个附件也要报 unreadable，而不是只在 CDATA 里留一句英文")
    void unreadableAttachmentIsReported() {
        when(legalTools.read_document("9")).thenReturn("Error: File not found.");

        RecordingSink sink = new RecordingSink();
        systemTextOf(List.of(file("9", "坏文件.docx")), null, sink);

        assertTrue(sink.hasNotice(ContextTurnSink.UNREADABLE, "9"));
    }

    // ---------- 视觉：配额口径统一 + 三种降级各自可见 ----------

    private void stubImageBytes(long id, int size) throws Exception {
        com.checkba.model.entity.ProjectFile pf = new com.checkba.model.entity.ProjectFile();
        pf.setId(id);
        pf.setProjectId(88L);
        pf.setName("p" + id + ".png");
        when(projectFileService.getFile(id)).thenReturn(pf);
        when(projectFileService.getFileBytes(id)).thenReturn(new byte[size]);
    }

    @Test
    @DisplayName("直送成功的图片计入文件配额，并登记成 visionUsed 附件")
    void directlySentImagesConsumeTheFileQuotaToo() throws Exception {
        when(chatModelFactory.effectiveModelSupportsVision(any())).thenReturn(true);
        properties.getFiles().setMaxFilesPerContext(2);
        stubImageBytes(11L, 64);
        stubImageBytes(12L, 64);
        when(legalTools.read_document(anyString())).thenReturn("正文");

        RecordingSink sink = new RecordingSink();
        systemTextOf(List.of(file("11", "现场1.png"), file("12", "现场2.png"),
                file("13", "合同.docx")), null, sink);

        assertTrue(sink.attachments.contains("image:11:现场1.png:true"));
        assertTrue(sink.attachments.contains("image:12:现场2.png:true"));
        assertTrue(sink.hasNotice(ContextTurnSink.DROPPED, "13"),
                "两张图已经吃掉 2 份配额，第三份必须被挡下——"
                        + "原来直送分支不 totalFileCount++，实际可注入 4 张图 + 10 份文件");
    }

    @Test
    @DisplayName("超出单轮张数的图片降级 OCR 并报 image_limit")
    void imagesBeyondThePerTurnLimitAreReported() throws Exception {
        when(chatModelFactory.effectiveModelSupportsVision(any())).thenReturn(true);
        properties.getVision().setMaxImagesPerTurn(1);
        stubImageBytes(11L, 64);
        stubImageBytes(12L, 64);
        when(legalTools.read_document(anyString())).thenReturn("识别出来的文字");

        RecordingSink sink = new RecordingSink();
        systemTextOf(List.of(file("11", "现场1.png"), file("12", "现场2.png")), null, sink);

        assertFalse(sink.hasNotice(ContextTurnSink.IMAGE_LIMIT, "11"));
        assertTrue(sink.hasNotice(ContextTurnSink.IMAGE_LIMIT, "12"));
        assertTrue(sink.attachments.contains("image:12:现场2.png:false"),
                "降级的那张登记成 visionUsed=false");
        assertFalse(sink.hasNotice(ContextTurnSink.UNREADABLE, "12"),
                "OCR 读出字了就别再叠一条「读不到内容」——一个附件只说一件事");
    }

    @Test
    @DisplayName("单张超体积上限的图片降级 OCR 并报 image_too_large")
    void oversizedImagesAreReported() throws Exception {
        when(chatModelFactory.effectiveModelSupportsVision(any())).thenReturn(true);
        properties.getVision().setMaxImageBytes(100L);
        stubImageBytes(11L, 500);
        when(legalTools.read_document(anyString())).thenReturn("识别出来的文字");

        RecordingSink sink = new RecordingSink();
        systemTextOf(List.of(file("11", "大扫描件.png")), null, sink);

        assertTrue(sink.hasNotice(ContextTurnSink.IMAGE_TOO_LARGE, "11"));
    }

    @Test
    @DisplayName("模型读不了图时报 ocr_fallback（与张数/体积超限分开，文案不同）")
    void nonVisionModelDowngradeIsReportedAsOcrFallback() throws Exception {
        when(chatModelFactory.effectiveModelSupportsVision(any())).thenReturn(false);
        stubImageBytes(11L, 64);
        when(legalTools.read_document(anyString())).thenReturn("识别出来的文字");

        RecordingSink sink = new RecordingSink();
        systemTextOf(List.of(file("11", "现场1.png")), null, sink);

        assertTrue(sink.hasNotice(ContextTurnSink.OCR_FALLBACK, "11"));
        assertFalse(sink.hasNotice(ContextTurnSink.IMAGE_LIMIT, "11"));
        assertFalse(sink.hasNotice(ContextTurnSink.IMAGE_TOO_LARGE, "11"));
    }

    @Test
    @DisplayName("OCR 一个字都没读出来时不把系统提示当正文，且报 unreadable")
    void ocrSystemNoticeIsNeverTreatedAsBodyText() throws Exception {
        when(chatModelFactory.effectiveModelSupportsVision(any())).thenReturn(false);
        stubImageBytes(11L, 64);
        when(legalTools.read_document(anyString())).thenReturn("[System: 文件超过大小限制]");

        RecordingSink sink = new RecordingSink();
        String systemText = systemTextOf(List.of(file("11", "大图.png")), null, sink);

        assertFalse(systemText.contains("[System: 文件超过大小限制]"),
                "这句是系统提示不是 OCR 转写结果，顶着「以下正文由 OCR 转写而来」进 CDATA "
                        + "会让模型把它当成图里的字");
        assertTrue(sink.hasNotice(ContextTurnSink.UNREADABLE, "11"));
        assertFalse(sink.hasNotice(ContextTurnSink.OCR_FALLBACK, "11"),
                "OCR 一个字都没读出来时「读不到内容」盖过「怎么降级的」");
    }

    @Test
    @DisplayName("写进 prompt 的降级原因与 notice 的 kind 一一对应（张数 / 体积 / 读不了图各说各的）")
    void theReasonInThePromptMatchesTheNoticeKind() throws Exception {
        // 实测发现（dev-board#801）：原来三种降级共用一句「超出本轮张数或单张体积上限，或读取失败」，
        // 模型据此告诉用户「因超出单张体积限制」，而真实原因是这一轮的张数上限——
        // 用户会去压缩一张其实不大的图。
        when(chatModelFactory.effectiveModelSupportsVision(any())).thenReturn(true);
        properties.getVision().setMaxImagesPerTurn(1);
        stubImageBytes(11L, 64);
        stubImageBytes(12L, 64);
        when(legalTools.read_document(anyString())).thenReturn("识别出来的文字");

        String systemText = systemTextOf(List.of(file("11", "现场1.png"), file("12", "现场2.png")),
                null, ContextTurnSink.NOOP);
        assertTrue(systemText.contains("超出本轮可直送的图片张数上限"));
        assertFalse(systemText.contains("超出本轮张数或单张体积上限"), "那句含混的旧文案不许再出现");

        properties.getVision().setMaxImagesPerTurn(4);
        properties.getVision().setMaxImageBytes(10L);
        String tooLarge = systemTextOf(List.of(file("11", "大图.png")), null, ContextTurnSink.NOOP);
        assertTrue(tooLarge.contains("超过单张体积上限"));

        when(chatModelFactory.effectiveModelSupportsVision(any())).thenReturn(false);
        String noVision = systemTextOf(List.of(file("12", "现场2.png")), null, ContextTurnSink.NOOP);
        assertTrue(noVision.contains("当前模型不支持视觉输入"));
    }

    // ---------- 附件登记（K14 ④ 的数据来源） ----------

    @Test
    @DisplayName("每个附件都登记一条，kind 分 file/image/folder")
    void everyAttachmentIsLedgered() throws Exception {
        when(fileContextLoader.buildFolderContextCounted(anyString(), anyString(), anyInt()))
                .thenReturn(new FileContextLoader.FolderContext("folder", 1, 0));
        when(legalTools.read_document(anyString())).thenReturn("正文");
        stubImageBytes(11L, 64);

        RecordingSink sink = new RecordingSink();
        systemTextOf(List.of(file("9", "合同.docx"), file("11", "图.png"), folder("100", "证据")), null, sink);

        assertEquals(3, sink.attachments.size());
        assertTrue(sink.attachments.contains("file:9:合同.docx:false"));
        assertTrue(sink.attachments.contains("image:11:图.png:false"));
        assertTrue(sink.attachments.contains("folder:100:证据:false"));
    }

    @Test
    @DisplayName("不传 sink 的旧调用方一行不用改（NOOP 不抛）")
    void theLegacyOverloadStillWorks() {
        when(legalTools.read_document(anyString())).thenReturn("正文");
        List<ChatMessage> messages = assembler.assemble(
                "conv-1", "run-1", "问题", List.of(file("9", "a.docx")), null,
                null, null, "88", AgentMode.AGENT, 1L, null);
        assertFalse(messages.isEmpty());
    }
}
