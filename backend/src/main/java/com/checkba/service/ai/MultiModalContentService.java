package com.checkba.service.ai;

import com.checkba.controller.ai.AiChatController.AiChatContext;
import com.checkba.service.ProjectFileService;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 多模态内容处理服务。
 *
 * 职责：
 * - 文件类型 → 多模态能力的分类判断（isImage/isVideo/isPdf/canDirectSend）
 * - 将上下文中的图片/视频/PDF 转为 LangChain4j 多模态 Content（含视频关键帧抽取的临时文件管理）
 *
 * 历史背景：类型分类逻辑原在 AiChatController 的 chat() 与 buildPromptWithContext()
 * 各写了一遍，Phase 2 收拢至此单一实现。
 */
@Service
public class MultiModalContentService {

    private static final Logger log = LoggerFactory.getLogger(MultiModalContentService.class);

    private final ProjectFileService projectFileService;
    private final MediaProcessingService mediaProcessingService;

    public MultiModalContentService(ProjectFileService projectFileService,
                                    MediaProcessingService mediaProcessingService) {
        this.projectFileService = projectFileService;
        this.mediaProcessingService = mediaProcessingService;
    }

    // ==================== 类型分类（单一事实来源） ====================

    public boolean isGeminiModel(String modelKey) {
        String key = modelKey != null ? modelKey.toLowerCase() : "";
        return key.contains("gemini") || key.contains("google");
    }

    public boolean isMultiModalCapable(String modelKey) {
        String key = modelKey != null ? modelKey.toLowerCase() : "";
        return key.contains("gemini") || key.contains("gpt-4") || key.contains("claude-3");
    }

    public boolean isImageType(String fileType) {
        String fType = fileType != null ? fileType.toLowerCase() : "";
        return fType.matches("jpg|jpeg|png|gif|bmp|webp") || fType.equals("image");
    }

    public boolean isVideoType(String fileType) {
        String fType = fileType != null ? fileType.toLowerCase() : "";
        return fType.matches("mp4|mov|avi|mkv") || fType.equals("video");
    }

    public boolean isPdfType(String fileType) {
        String fType = fileType != null ? fileType.toLowerCase() : "";
        return fType.equals("pdf") || fType.endsWith("pdf");
    }

    /**
     * 该文件是否可以直接发送给模型（跳过文本提取，由多模态路径直传原文件）。
     * PDF 目前只有 Gemini 原生支持直传；图片则所有多模态模型都支持。
     */
    public boolean canDirectSend(String modelKey, String fileType) {
        if (isPdfType(fileType)) {
            return isGeminiModel(modelKey);
        }
        if (isImageType(fileType)) {
            return isMultiModalCapable(modelKey);
        }
        return false;
    }

    // ==================== 多模态内容构建 ====================

    /**
     * 将上下文列表中的媒体文件（PDF/图片/视频）转为多模态 Content 列表。
     * 行为沿用原 AiChatController.chat() 内联实现：
     * - Gemini + PDF：整文件 base64 直传
     * - 图片：base64 直传（jpg 归一化为 jpeg mime）
     * - 视频：抽取关键帧后按图片发送（临时文件即用即删）
     *
     * @return 媒体 Content 列表（不含文本部分）；无可用媒体时为空列表
     */
    public List<Content> buildMediaContents(List<AiChatContext> contexts, String modelKey) {
        List<Content> mediaContents = new ArrayList<>();
        if (contexts == null) {
            return mediaContents;
        }
        boolean isGemini = isGeminiModel(modelKey);

        for (AiChatContext ctx : contexts) {
            if (ctx == null) continue;
            String fType = ctx.getFileType() != null ? ctx.getFileType().toLowerCase() : "";

            // 1. Native PDF (Gemini)
            if (isGemini && isPdfType(fType)) {
                try {
                    Long fId = Long.parseLong(ctx.getFileId());
                    byte[] fileBytes = projectFileService.getFileBytes(fId);
                    if (fileBytes != null && fileBytes.length > 0) {
                        String base64 = Base64.getEncoder().encodeToString(fileBytes);
                        mediaContents.add(ImageContent.from(base64, "application/pdf"));
                    }
                } catch (Exception e) {
                    log.warn("Failed to attach PDF for Gemini", e);
                }
            }

            // 2. Images / Video
            boolean isImage = isImageType(fType);
            boolean isVideo = isVideoType(fType);

            if (isImage || isVideo) {
                try {
                    Long fileId = Long.parseLong(ctx.getFileId());
                    byte[] fileBytes = projectFileService.getFileBytes(fileId);

                    if (fileBytes != null && fileBytes.length > 0) {
                        List<String> images = new ArrayList<>();

                        if (isImage) {
                            images.add(Base64.getEncoder().encodeToString(fileBytes));
                        } else {
                            // For video keyframes, we need a physical temp file
                            String ext = !fType.isEmpty() ? "." + fType : ".mp4";
                            Path tempVid = Files.createTempFile("vid_ctx_" + fileId, ext);
                            Files.write(tempVid, fileBytes);
                            try {
                                images = mediaProcessingService.extractKeyframes(tempVid.toFile(), 5);
                            } finally {
                                Files.deleteIfExists(tempVid);
                            }
                        }

                        for (String base64 : images) {
                            String mimeType = isImage ? "image/" + fType.replace("jpg", "jpeg") : "image/jpeg";
                            if (mimeType.equals("image/image")) mimeType = "image/jpeg";
                            mediaContents.add(ImageContent.from(base64, mimeType));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to process media content", e);
                }
            }
        }
        return mediaContents;
    }
}
