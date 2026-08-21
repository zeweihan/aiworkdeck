package com.checkba.service.ai;

import com.checkba.model.entity.Tag;
import com.checkba.service.DocumentTextService;
import com.checkba.service.FileTagService;
import com.checkba.service.TagService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoTaggingService {

    private final ChatModelFactory chatModelFactory;
    private final TagService tagService;
    private final FileTagService fileTagService;
    private final DocumentTextService documentTextService;
    // 自动打标签走辅助模型（便宜档）并落账：每次上传都会跑一次，此前用默认模型且一行账不记
    private final AuxModelResolver auxModelResolver;
    private final TokenUsageService tokenUsageService;

    /**
     * 按 fileId 序列化整个自动打标签流程（check-then-act 竞态修复，dev-board#74）。
     *
     * <p>hasAutoTags() 是一次裸 SELECT，没有锁也没有事务；它与第一次
     * {@code fileTagService.addTagToFile} 落库之间隔着一整趟 LLM 往返（几百毫秒到几秒）。
     * 同一个 fileId 的两次自动保存/上传离得够近时，都会在这段窗口里读到"还没打过标签"，
     * 各自跑一遍 LLM、各自落一遍标签——这正是类头注释里那次"单文件堆到 338 个标签"的
     * 生产事故的更小规模复现。</p>
     *
     * <p>这里不需要 ProjectVariableService/DdService 那种"进程锁 + REQUIRES_NEW 子事务"
     * 组合：本方法本身不带 @Transactional，从 hasAutoTags() 的检查到最后一次
     * fileTagService.addTagToFile() 落标签，中间调用的每一个 @Transactional 方法都各自
     * REQUIRED 独立成一次提交（没有外层事务参与进来），锁在整段流程结束时才释放，
     * 释放时前面每一次落库都已经真正提交——不会出现"锁放了但对方看不到"的假修陷阱。</p>
     */
    private final ConcurrentHashMap<Long, Object> autoTagLocks = new ConcurrentHashMap<>();

    /**
     * Automatically generate and attach tags to a file based on its content.
     */
    public void autoTagFile(Long projectId, Long fileId, String storagePath, Long userId) {
        // 平台通道按用户计费：这次 LLM 调用要落在上传者本人的额度上
        PlatformAiUserScope.run(userId, () -> autoTagFileInScope(projectId, fileId, storagePath, userId));
    }

    private void autoTagFileInScope(Long projectId, Long fileId, String storagePath, Long userId) {
        Object lock = autoTagLocks.computeIfAbsent(fileId, k -> new Object());
        synchronized (lock) {
            autoTagFileLocked(projectId, fileId, storagePath, userId);
        }
    }

    private void autoTagFileLocked(Long projectId, Long fileId, String storagePath, Long userId) {
        // 一个文件只自动打一次标签。上传端点同时是编辑器自动保存的落点
        // （FileController 的 legacy 分支），没有这道闸的话每存一次盘就再跑一次 LLM：
        // 每轮返回 5 个措辞不同的新词，getOrCreateSystemTag 又只按精确字符串去重，
        // 于是标签无上限累积（实测单个文件积到 338 个，搜索面板的标签墙就是这么来的），
        // 同时每一次自动保存都白烧一次辅助模型的钱。
        if (hasAutoTags(fileId)) {
            log.debug("fileId={} 已有自动标签，跳过重复打标签", fileId);
            return;
        }
        log.info("Starting auto-tagging for fileId={}, path={}", fileId, storagePath);
        try {
            // 1. Extract text
            String text = extractText(storagePath);
            if (!StringUtils.hasText(text) || text.length() < 50) {
                log.info("Text too short for auto-tagging, skipping. Length={}", text == null ? 0 : text.length());
                return;
            }
            
            // Truncate text to avoid token limits (e.g. first 3000 chars)
            String truncatedText = text.length() > 3000 ? text.substring(0, 3000) : text;
            
            // 2. Call LLM（辅助模型档：ai.auxModel → yml ai.aux-model）
            ChatLanguageModel model = chatModelFactory.getAuxChatModel();

            String prompt = "Analyze the following file content and suggest top 5 relevant tags. " +
                    "Tags should be concise (1-3 words), language should match the content. " +
                    "Return ONLY the tags separated by commas, no other text. " +
                    "Content:\n" + truncatedText;

            // 用 Response 版而不是 generate(String)：后者拿不到 tokenUsage，这笔账就记不上
            dev.langchain4j.model.output.Response<dev.langchain4j.data.message.AiMessage> llmResponse =
                    model.generate(dev.langchain4j.data.message.UserMessage.from(prompt));
            recordUsage(llmResponse, projectId, userId);
            String response = llmResponse.content().text();
            log.info("LLM Auto-tag response: {}", response);

            // 3. Parse and save tags
            if (StringUtils.hasText(response)) {
                // Remove potential markdown code blocks or extra text if LLM is chatty
                String cleanResponse = response.replaceAll("```", "").trim();
                String[] tags = cleanResponse.split("[,，、\\n]"); // Split by common separators
                
                Set<String> uniqueTags = Arrays.stream(tags)
                        .map(String::trim)
                        .filter(StringUtils::hasText)
                        // Filter out long nonsense
                        .filter(t -> t.length() < 20) 
                        .limit(5)
                        .collect(Collectors.toSet());
                
                for (String tagName : uniqueTags) {
                    try {
                        // Generate a color (random or fixed for system tags)
                        String color = "#" + Integer.toHexString((tagName.hashCode() & 0x00FFFFFF) | 0x1000000).substring(1).toUpperCase(); // Simple deterministic color
                        // Or use a specific system color
                        
                        Tag tag = tagService.getOrCreateSystemTag(projectId, tagName, "#3B82F6"); // Default blue for auto tags
                        fileTagService.addTagToFile(fileId, tag.getId(), userId);
                    } catch (Exception e) {
                        log.error("Failed to add tag '{}'", tagName, e);
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Auto-tagging failed for fileId={}", fileId, e);
        }
    }
    
    /**
     * 该文件是否已经被自动打过标签。判据是「挂着任一系统标签」——
     * 自动标签一律由 {@code getOrCreateSystemTag} 建成 {@code isSystem=true}，
     * 用户手工建的标签不算，所以手工打过标签的文件仍会正常走一次自动打标签。
     */
    private boolean hasAutoTags(Long fileId) {
        try {
            return fileTagService.getTagsByFileId(fileId).stream()
                    .anyMatch(t -> Boolean.TRUE.equals(t.getIsSystem()));
        } catch (Exception e) {
            // 查不动就当没打过：宁可多打一次，也不要因为一次查询失败让新文件永远没有标签
            log.warn("查询文件已有标签失败 fileId={}: {}", fileId, e.getMessage());
            return false;
        }
    }

    /** 打标签调用的 token 记账（会话无关，conversationId 传 null）。失败绝不影响打标签。 */
    private void recordUsage(dev.langchain4j.model.output.Response<dev.langchain4j.data.message.AiMessage> response,
                            Long projectId, Long userId) {
        if (response == null || response.tokenUsage() == null) {
            return;
        }
        try {
            tokenUsageService.recordUsage(projectId, userId,
                    auxModelResolver.auxModelId(), response.tokenUsage(), null);
        } catch (Exception e) {
            log.warn("自动打标签 token 记账失败（不影响打标签）: {}", e.getMessage());
        }
    }

    /**
     * 委托 {@link DocumentTextService}，PDF 走 PDFBox3 原生 API——同款不再自建
     * Tika 解析 PDF（Tika 2.9.1 调 PDFBox2 已删除的 API 会 NoSuchMethodError，
     * PDF 因此静默打标签失败）。静默失败语义不变：抽取失败照旧返回 null。
     */
    private String extractText(String storagePath) {
        try {
            com.checkba.model.entity.ProjectFile stub = new com.checkba.model.entity.ProjectFile();
            stub.setFilePath(storagePath);
            stub.setName(storagePath);
            return documentTextService.extractText(stub);
        } catch (Exception e) {
            log.warn("Failed to extract text from storagePath={}", storagePath, e);
            return null;
        }
    }
}
