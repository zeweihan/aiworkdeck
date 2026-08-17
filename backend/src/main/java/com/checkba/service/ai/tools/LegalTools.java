package com.checkba.service.ai.tools;

import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.mcp.McpClientService;
import com.checkba.service.ai.mcp.McpResponseParser;
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
 * 2. PKULaw MCP Integration - 只做法律语义封装，协议细节在 service/ai/mcp 包
 *    （服务器列表见配置 mcp.servers，传输实现见 McpProvider）
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegalTools implements AgentToolComponent {

    /** 法宝的三个 MCP server 都配了 60 秒超时，网关这条要留出同样的余量。 */
    private static final int PKULAW_TIMEOUT_SECONDS = 60;

    private final ProjectFileService projectFileService;
    private final McpClientService mcpClientService;
    private final com.checkba.service.ai.context.FileContentExtractorService fileContentExtractorService;
    private final com.checkba.service.platform.ExternalProviderResolver externalProviderResolver;
    private final com.checkba.service.platform.PlatformGatewayClient platformGatewayClient;

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
            String denied = ToolFileGuard.rejectIfOutsideProject(file);
            if (denied != null) return denied;

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


    // --- PKULaw MCP Integration（服务器名对应配置 mcp.servers[].name）---

    @ToolMeta(displayName = "语义搜索法规", category = "legal")
    @Tool("Search for laws and regulations using PKULaw MCP Semantic Search. Use this for general legal questions. Returns a list of relevant articles.")
    public String law_search(String query) {
        log.info("Tool: law_search (semantic) called for query='{}'", query);
        return callPkulaw("pkulaw-semantic", "search_article", Map.of("query", query));
    }

    @ToolMeta(displayName = "关键词搜索法规", category = "legal")
    @Tool("Search for laws by keywords in title or fulltext. Use this when you need specific laws by name.")
    public String law_search_keyword(String title, String fulltext) {
        log.info("Tool: law_search_keyword called for title='{}', fulltext='{}'", title, fulltext);
        Map<String, Object> args = new HashMap<>();
        if (StringUtils.hasText(title)) args.put("title", title);
        if (StringUtils.hasText(fulltext)) args.put("fulltext", fulltext);

        return callPkulaw("pkulaw-keyword", "get_law_list", args);
    }

    @ToolMeta(displayName = "法条识别与溯源", category = "legal")
    @Tool("Identify law names and articles from text and trace their source.")
    public String law_recognition(String text) {
        log.info("Tool: law_recognition called for text length={}", text.length());
        return callPkulaw("pkulaw-recognition", "law_recognition", Map.of("text", text));
    }

    @ToolMeta(displayName = "查询法条", category = "legal")
    @Tool("Get the full content of a specific law article by its title and article number. Use this when you have article info from law_search results.")
    public String get_law_article(String title, String number) {
        log.info("Tool: get_law_article called for title='{}', number='{}'", title, number);
        return callPkulaw("pkulaw-semantic", "get_article", Map.of("title", title, "number", number));
    }

    /**
     * 法宝检索的双档分发。
     *
     * <p>平台档下 MCP 客户端拿不到 token（token 是订阅主体的，我们不会下发给每台机器），
     * 所以走网关：官网持 token 打同一个 MCP 端点，把 JSON-RPC 响应正文原样带回来。
     * <b>解析仍用 {@link McpResponseParser}</b>——两档共用一个解析器，
     * 法宝改格式只会改一处；在网关侧解析等于把这份格式知识抄成两份。
     *
     * <p>网关失败<b>不抛异常打断整轮对话</b>：这是给模型看的文本，
     * 说清楚发生了什么、下一步是什么，让它基于已有信息继续——
     * 与「未配置」那条既有分支同一口径（licensing-billing 地雷 27）。
     */
    private String callPkulaw(String server, String tool, Map<String, Object> args) {
        if (externalProviderResolver.resolve(
                com.checkba.service.platform.ExternalServiceProvider.PKULAW)
                != com.checkba.service.platform.ExternalServiceProvider.PLATFORM) {
            return mcpClientService.callTool(server, tool, args);
        }
        try {
            // 网关的 op 就是法宝的工具名；MCP 端点写死在服务端，客户端点不了别处
            com.checkba.service.platform.PlatformGatewayClient.Result result =
                    platformGatewayClient.call("pkulaw", tool, Map.of("args", args),
                            PKULAW_TIMEOUT_SECONDS);
            return McpResponseParser.parse(result.data().path("raw").asText(""));
        } catch (com.checkba.service.platform.GatewayException e) {
            log.warn("平台法规检索失败 kind={}: {}", e.getKind(), e.getMessage());
            String hint = e.suggestsByok()
                    ? "如需继续使用，可在「系统管理 → 平台服务」把法规检索改为自备 Key。"
                    : "";
            return "法规检索本次不可用：" + e.getMessage() + hint
                    + " 本次已跳过法规检索，请基于已有信息继续完成任务。";
        }
    }
}
