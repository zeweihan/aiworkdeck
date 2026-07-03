package com.checkba.service.ai;

import com.checkba.controller.ai.AiChatController.AiChatContext;
import com.checkba.service.ProjectFileService;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MultiModalContentService 测试：类型分类与多模态内容构建
 * （原 AiChatController 双份分类逻辑去重后的单一实现）
 */
class MultiModalContentServiceTest {

    private ProjectFileService projectFileService;
    private MediaProcessingService mediaProcessingService;
    private MultiModalContentService service;

    @BeforeEach
    void setUp() {
        projectFileService = mock(ProjectFileService.class);
        mediaProcessingService = mock(MediaProcessingService.class);
        service = new MultiModalContentService(projectFileService, mediaProcessingService);
    }

    private static AiChatContext ctx(String fileId, String fileType) {
        AiChatContext c = new AiChatContext();
        c.setFileId(fileId);
        c.setFileType(fileType);
        return c;
    }

    @Test
    @DisplayName("类型分类：PDF 只有 Gemini 可直传，图片所有多模态模型可直传")
    void canDirectSendClassification() {
        assertTrue(service.canDirectSend("google/gemini-2.0-flash", "pdf"));
        assertFalse(service.canDirectSend("gpt-4o", "pdf"), "PDF 直传目前仅限 Gemini");
        assertTrue(service.canDirectSend("gpt-4o", "png"));
        assertTrue(service.canDirectSend("claude-3-sonnet", "jpg"));
        assertFalse(service.canDirectSend("qwen3-vl:8b", "png"), "未知模型不视为多模态");
        assertFalse(service.canDirectSend("gemini", "docx"), "非媒体文件不直传");
    }

    @Test
    @DisplayName("Gemini + PDF：整文件 base64 直传")
    void buildsPdfContentForGemini() throws Exception {
        when(projectFileService.getFileBytes(1L)).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));

        List<Content> contents = service.buildMediaContents(List.of(ctx("1", "pdf")), "google/gemini-2.0-flash");

        assertEquals(1, contents.size());
        ImageContent pdf = (ImageContent) contents.get(0);
        assertEquals("application/pdf", pdf.image().mimeType());
    }

    @Test
    @DisplayName("非 Gemini 模型：PDF 不构建多模态内容")
    void skipsPdfForNonGemini() {
        List<Content> contents = service.buildMediaContents(List.of(ctx("1", "pdf")), "gpt-4o");
        assertTrue(contents.isEmpty());
    }

    @Test
    @DisplayName("图片：jpg 归一化为 image/jpeg")
    void normalizesJpgMimeType() throws Exception {
        when(projectFileService.getFileBytes(2L)).thenReturn("img".getBytes(StandardCharsets.UTF_8));

        List<Content> contents = service.buildMediaContents(List.of(ctx("2", "jpg")), "gemini");

        assertEquals(1, contents.size());
        assertEquals("image/jpeg", ((ImageContent) contents.get(0)).image().mimeType());
    }

    @Test
    @DisplayName("视频：抽取关键帧后按 image/jpeg 发送")
    void extractsVideoKeyframes() throws Exception {
        when(projectFileService.getFileBytes(3L)).thenReturn("video".getBytes(StandardCharsets.UTF_8));
        when(mediaProcessingService.extractKeyframes(any(), eq(5)))
                .thenReturn(List.of("frame1", "frame2"));

        List<Content> contents = service.buildMediaContents(List.of(ctx("3", "mp4")), "gemini");

        assertEquals(2, contents.size());
        assertEquals("image/jpeg", ((ImageContent) contents.get(0)).image().mimeType());
    }

    @Test
    @DisplayName("单个文件读取失败不影响其他上下文")
    void toleratesSingleFileFailure() throws Exception {
        when(projectFileService.getFileBytes(4L)).thenThrow(new java.io.IOException("gone"));
        when(projectFileService.getFileBytes(5L)).thenReturn("img".getBytes(StandardCharsets.UTF_8));

        List<Content> contents = service.buildMediaContents(
                List.of(ctx("4", "png"), ctx("5", "png")), "gemini");

        assertEquals(1, contents.size(), "失败文件跳过，正常文件仍应构建");
    }
}
