package com.checkba.service.ai;

import com.checkba.model.entity.Tag;
import com.checkba.service.FileTagService;
import com.checkba.service.TagService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoTaggingService {

    private final ChatModelFactory chatModelFactory;
    private final TagService tagService;
    private final FileTagService fileTagService;
    private final StorageServiceFactory storageServiceFactory;

    /**
     * Automatically generate and attach tags to a file based on its content.
     */
    public void autoTagFile(Long projectId, Long fileId, String storagePath, Long userId) {
        // 平台通道按用户计费：这次 LLM 调用要落在上传者本人的额度上
        PlatformAiUserScope.run(userId, () -> autoTagFileInScope(projectId, fileId, storagePath, userId));
    }

    private void autoTagFileInScope(Long projectId, Long fileId, String storagePath, Long userId) {
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
            
            // 2. Call LLM
            // Use a cheaper/faster model if possible, or default
            ChatLanguageModel model = chatModelFactory.getChatModel(null); // Default model
            
            String prompt = "Analyze the following file content and suggest top 5 relevant tags. " +
                    "Tags should be concise (1-3 words), language should match the content. " +
                    "Return ONLY the tags separated by commas, no other text. " +
                    "Content:\n" + truncatedText;
            
            String response = model.generate(prompt);
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
    
    private String extractText(String storagePath) {
        try {
            StorageService storageService = storageServiceFactory.getStorageService();
            Resource resource = storageService.load(storagePath);
            try (InputStream is = resource.getInputStream()) {
                Tika tika = new Tika();
                return tika.parseToString(is);
            }
        } catch (Exception e) {
            log.warn("Failed to extract text from storagePath={}", storagePath, e);
            return null;
        }
    }
}
