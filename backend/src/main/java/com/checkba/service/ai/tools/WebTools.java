package com.checkba.service.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Web Tools for the Agent.
 * Includes:
 * 1. Web Search (Bocha AI API)
 * 2. Browse URL (Content Extraction via Playwright)
 */
@Component
@Slf4j
public class WebTools implements AgentToolComponent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebTools.class);

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String BOCHA_API_URL = "https://api.bochaai.com/v1/web-search";
    
    @Value("${bocha.api.key:}")
    private String bochaApiKey;

    // 桌面打包版不注入 BOCHA_API_KEY，环境变量默认为空；管理员可在设置页
    // （external.bocha.apiKey）填 key，DB 值优先于环境变量/配置文件。
    @org.springframework.beans.factory.annotation.Autowired
    private com.checkba.service.SystemSettingService systemSettingService;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ToolMeta(displayName = "网络搜索", category = "web")
    @Tool("Search the web using Bocha AI. Useful for finding latest news, regulations, or legal cases. Returns a summary of search results.")
    public String search_web(String query) {
        log.info("Tool: search_web called for query='{}'", query);
        String apiKey = systemSettingService.get("external.bocha.apiKey", bochaApiKey);
        if (apiKey == null || apiKey.isBlank()) {
            return "网络搜索未配置：缺少博查（Bocha AI）搜索的 API Key。请管理员在「设置 → 外部服务」中填写博查 API Key"
                    + "（可在 bochaai.com 申请），保存后重试。本次已跳过网络搜索，请基于已有信息继续完成任务。";
        }
        try {
            // Build request body
            String requestBody = objectMapper.writeValueAsString(
                java.util.Map.of(
                    "query", query,
                    "summary", true,
                    "count", 10,
                    "freshness", "noLimit"
                )
            );

            Request request = new Request.Builder()
                    .url(BOCHA_API_URL)
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Bocha API returned error: {} - {}", response.code(), response.message());
                    return "Error searching web: Bocha API returned " + response.code() + " " + response.message();
                }

                String responseBody = response.body().string();
                log.debug("Bocha API response: {}", responseBody);

                JsonNode root = objectMapper.readTree(responseBody);
                
                // Check for error in response
                if (root.has("error")) {
                    String errorMsg = root.get("error").asText();
                    log.error("Bocha API error: {}", errorMsg);
                    return "Error searching web: " + errorMsg;
                }

                // Parse web pages from response
                // Response structure: { "data": { "webPages": { "value": [...] } } }
                JsonNode webPages = root.path("data").path("webPages").path("value");
                
                if (webPages.isMissingNode() || !webPages.isArray() || webPages.size() == 0) {
                    return "No search results found for: " + query;
                }

                StringBuilder summary = new StringBuilder("Search Results for '" + query + "':\n\n");
                int count = 0;
                for (JsonNode page : webPages) {
                    if (count >= 5) break;

                    String title = page.path("name").asText("No Title");
                    String url = page.path("url").asText("");
                    String snippet = page.path("snippet").asText("");
                    String siteName = page.path("siteName").asText("");
                    String datePublished = page.path("datePublished").asText("");

                    // Limit snippet length
                    if (snippet.length() > 300) {
                        snippet = snippet.substring(0, 300) + "...";
                    }

                    summary.append(count + 1).append(". ").append(title);
                    if (!siteName.isEmpty()) {
                        summary.append(" [").append(siteName).append("]");
                    }
                    summary.append("\n");
                    summary.append("   Link: ").append(url).append("\n");
                    if (!datePublished.isEmpty()) {
                        summary.append("   Published: ").append(datePublished).append("\n");
                    }
                    summary.append("   Snippet: ").append(snippet).append("\n\n");
                    count++;
                }

                return summary.toString();
            }

        } catch (Exception e) {
            log.error("Failed to search web via Bocha API", e);
            return "Error searching web: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "浏览网页", category = "web")
    @Tool("Browse a specific URL and extract its main content.")
    public String browse_url(String url) {
        log.info("Tool: browse_url called for url='{}'", url);
        if (url == null || url.isBlank()) {
            return "Error: url is required.";
        }
        // Ensure URL has protocol
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        // URL 由 LLM 填写，属不可信输入：先挡掉内网/回环/云元数据目标
        String blocked = com.checkba.util.SsrfGuard.rejectIfBlocked(url);
        if (blocked != null) {
            log.warn("browse_url blocked by SSRF guard: url='{}'", url);
            return blocked;
        }

        try (com.microsoft.playwright.Playwright playwright = com.microsoft.playwright.Playwright.create()) {
            com.microsoft.playwright.Browser browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(java.util.Arrays.asList("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage"))
            );
            com.microsoft.playwright.Page page = browser.newPage();
            
            // Set User Agent
            page.setExtraHTTPHeaders(java.util.Collections.singletonMap("User-Agent", USER_AGENT));

            // 每个请求都复校一次：首次检查挡不住 302 跳内网，也挡不住页面自己发的子请求
            page.route("**/*", route -> {
                if (com.checkba.util.SsrfGuard.rejectIfBlocked(route.request().url()) != null) {
                    log.warn("browse_url aborted a request to a blocked target: {}", route.request().url());
                    route.abort();
                } else {
                    route.resume();
                }
            });

            try {
                page.navigate(url);
                // Wait for network to be idle or dom content loaded. 'networkidle' is good for SPAs.
                // Using a reasonable timeout
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(20000));
            } catch (Exception e) {
                log.warn("Playwright navigation/wait warning: {}", e.getMessage());
                // Continue, as content might be partially loaded
                // Fallback to DOMCONTENTLOADED if network idle times out
                // page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);
            }

            // Get standard HTML
            String html = page.content();
            
            // Use Jsoup to parse and clean the rendered HTML
            Document doc = Jsoup.parse(html);
            String title = doc.title();

            // Basic extraction: remove script, style, nav, footer
            doc.select("script, style, nav, footer, header, aside, .ads, .advertisement, noscript, iframe").remove();

            String bodyText = doc.body().text();

            return "Page Title: " + title + "\n\nContent:\n" + bodyText;

        } catch (Exception e) {
             log.error("Failed to browse url {} with Playwright", url, e);
             return "Error browsing URL with Playwright: " + e.getMessage();
        }
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("WebTools initializing: Checking and downloading Playwright browsers if needed...");
        // Define specific env vars to force download if needed, though default behavior is usually sufficient.
        // We launch a browser dry-run to trigger the download mechanism.
        new Thread(() -> {
            try (com.microsoft.playwright.Playwright playwright = com.microsoft.playwright.Playwright.create()) {
                log.info("Playwright created. Launching dry-run browser to ensure binaries are ready...");
                com.microsoft.playwright.Browser browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(java.util.Arrays.asList("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage"))
                );
                browser.close();
                log.info("WebTools initialization: Playwright browser is ready.");
            } catch (Exception e) {
                log.error("WebTools initialization warning: Failed to pre-warm Playwright. It will try again on first request.", e);
            }
        }).start();
    }
}
