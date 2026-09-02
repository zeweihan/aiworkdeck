package com.checkba.service.ai.tools;

import com.checkba.service.DocumentTextService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.ai.mcp.McpClientService;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.PlatformGatewayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 审计条目：「get_law_article/law_search/law_recognition dereference LLM-supplied args
 * before validating, producing an uninformative 'Error executing tool: null' instead of
 * an actionable message」。
 *
 * 三个工具此前直接把可能为 null 的参数塞进 {@code Map.of(...)}（law_recognition 甚至在那之前
 * 就先 {@code text.length()}），模型漏传一个参数就是裸 NPE，外层只能看到
 * "Error executing tool: null"——不知道到底缺了哪个。修法是先用 StringUtils.hasText 校验，
 * 和 law_search_keyword / EnterpriseDataTools 的既有口径对齐。
 */
@DisplayName("LegalTools：先解引用后校验")
class LegalToolsValidationTest {

    private McpClientService mcpClientService;
    private LegalTools tools;

    @BeforeEach
    void setUp() {
        ProjectFileService projectFileService = mock(ProjectFileService.class);
        mcpClientService = mock(McpClientService.class);
        FileContentExtractorService fileContentExtractorService = mock(FileContentExtractorService.class);
        ExternalProviderResolver externalProviderResolver = mock(ExternalProviderResolver.class);
        PlatformGatewayClient platformGatewayClient = mock(PlatformGatewayClient.class);
        DocumentTextService documentTextService = mock(DocumentTextService.class);
        tools = new LegalTools(projectFileService,
                new com.checkba.service.legal.PkulawChannel(
                        mcpClientService, externalProviderResolver, platformGatewayClient),
                fileContentExtractorService, documentTextService);
    }

    @Test
    @DisplayName("修复：get_law_article 漏传 number 时返回可行动的错误，不是裸 NPE")
    void getLawArticleMissingNumberReturnsActionableError() {
        String result = tools.get_law_article("民法典", null);

        assertTrue(result.startsWith("Error"), result);
        assertTrue(result.contains("number"), "错误信息应点名缺的是 number: " + result);
        verifyNoInteractions(mcpClientService);
    }

    @Test
    @DisplayName("修复：get_law_article 漏传 title 时返回可行动的错误")
    void getLawArticleMissingTitleReturnsActionableError() {
        String result = tools.get_law_article(null, "第五百七十七条");

        assertTrue(result.startsWith("Error"), result);
        assertTrue(result.contains("title"), "错误信息应点名缺的是 title: " + result);
        verifyNoInteractions(mcpClientService);
    }

    @Test
    @DisplayName("修复：law_search 漏传 query 时返回可行动的错误，不是裸 NPE")
    void lawSearchMissingQueryReturnsActionableError() {
        String result = tools.law_search(null);

        assertTrue(result.startsWith("Error"), result);
        verifyNoInteractions(mcpClientService);
    }

    @Test
    @DisplayName("修复：law_recognition 漏传 text 时返回可行动的错误，不是裸 NPE（此前连 log 都到不了）")
    void lawRecognitionMissingTextReturnsActionableError() {
        String result = tools.law_recognition(null);

        assertTrue(result.startsWith("Error"), result);
        verifyNoInteractions(mcpClientService);
    }
}
