package com.checkba.service.ai.tools;

import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.mcp.McpClientService;
import com.checkba.model.entity.ProjectFile;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Legal Tools Set for the Agent.
 * Includes:
 * 1. File Operations (Read Document) - using Tika or simple read
 * 2. PKULaw MCP Integration - using standard MCP SDK
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegalTools implements AgentToolComponent {

    private final ProjectFileService projectFileService;
    private final McpClientService mcpClientService;
    private final com.checkba.service.ai.context.FileContentExtractorService fileContentExtractorService;

    // --- File Operations ---

    @ToolMeta(displayName = "读取文档", category = "file")
    @Tool("Read document content. Use this to read files from the project. Provide fileId.")
    public String read_document(String fileId) {
        log.info("Tool: read_document called for fileId={}", fileId);
        java.nio.file.Path tempPath = null;
        try {
            Long fId = Long.parseLong(fileId);
            ProjectFile file = projectFileService.getFile(fId);
            if (file == null) return "Error: File not found.";

            byte[] bytes = projectFileService.getFileBytes(fId);
            if (bytes == null || bytes.length == 0) return "File is empty.";
            
            // Create temp file for extractor (needed for Tika/PDFBox/OCR)
            String ext = file.getFileType() != null ? "." + file.getFileType() : ".tmp";
            tempPath = java.nio.file.Files.createTempFile("checkba_legal_" + fId + "_", ext);
            java.nio.file.Files.write(tempPath, bytes);
            java.io.File tempFile = tempPath.toFile();
            
            String result;
            if (fileContentExtractorService.isOcrSupported(file.getName())) {
               result = fileContentExtractorService.extractTextWithOcr(tempFile);
            } else {
               result = fileContentExtractorService.extractText(tempFile);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("Failed to read document {}", fileId, e);
            return "Error reading document: " + e.getMessage();
        } finally {
            if (tempPath != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tempPath);
                } catch (Exception ignore) {}
            }
        }
    }


    // --- PKULaw MCP Integration (using standard MCP SDK) ---

    @ToolMeta(displayName = "语义搜索法规", category = "legal")
    @Tool("Search for laws and regulations using PKULaw MCP Semantic Search. Use this for general legal questions. Returns a list of relevant articles.")
    public String law_search(String query) {
        log.info("Tool: law_search (semantic) called for query='{}'", query);
        return mcpClientService.callTool("pkulaw-semantic", "search_article", Map.of("query", query));
    }

    @ToolMeta(displayName = "关键词搜索法规", category = "legal")
    @Tool("Search for laws by keywords in title or fulltext. Use this when you need specific laws by name.")
    public String law_search_keyword(String title, String fulltext) {
        log.info("Tool: law_search_keyword called for title='{}', fulltext='{}'", title, fulltext);
        Map<String, Object> args = new HashMap<>();
        if (StringUtils.hasText(title)) args.put("title", title);
        if (StringUtils.hasText(fulltext)) args.put("fulltext", fulltext);
        
        return mcpClientService.callTool("pkulaw-keyword", "get_law_list", args);
    }

    @ToolMeta(displayName = "法条识别与溯源", category = "legal")
    @Tool("Identify law names and articles from text and trace their source.")
    public String law_recognition(String text) {
        log.info("Tool: law_recognition called for text length={}", text.length());
        return mcpClientService.callTool("pkulaw-recognition", "law_recognition", Map.of("text", text));
    }

    @ToolMeta(displayName = "查询法条", category = "legal")
    @Tool("Get the full content of a specific law article by its title and article number. Use this when you have article info from law_search results.")
    public String get_law_article(String title, String number) {
        log.info("Tool: get_law_article called for title='{}', number='{}'", title, number);
        return mcpClientService.callTool("pkulaw-semantic", "get_article", Map.of("title", title, "number", number));
    }
}
