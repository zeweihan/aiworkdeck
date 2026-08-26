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

    @org.springframework.beans.factory.annotation.Autowired
    private com.checkba.service.platform.ExternalProviderResolver externalProviderResolver;

    @org.springframework.beans.factory.annotation.Autowired
    private com.checkba.service.platform.PlatformGatewayClient platformGatewayClient;

    /** 搜索的上游超时。**不沿用账户通道的 5 秒**——博查返回 10 条带摘要的结果经常要十几秒。 */
    private static final int SEARCH_TIMEOUT_SECONDS = 30;

    @ToolMeta(displayName = "网络搜索", category = "web")
    @Tool("Search the web using Bocha AI. Useful for finding latest news, regulations, or legal cases. Returns a summary of search results.")
    public String search_web(String query) {
        log.info("Tool: search_web called for query='{}'", query);

        // 平台代采档：走网关，成本折算 Credits。失败**绝不静默回落 BYOK**——
        // 那会去花用户自己的 Key（同 licensing-billing 地雷 8）。
        if (externalProviderResolver.resolve(
                com.checkba.service.platform.ExternalServiceProvider.SEARCH)
                == com.checkba.service.platform.ExternalServiceProvider.PLATFORM) {
            return searchViaPlatform(query);
        }

        String apiKey = systemSettingService.get("external.bocha.apiKey", bochaApiKey);
        if (apiKey == null || apiKey.isBlank()) {
            return "错误：网络搜索未配置：当前部署未提供博查（Bocha AI）搜索的 API Key"
                    + "（环境变量 BOCHA_API_KEY，可在 bochaai.com 申请）。本次已跳过网络搜索，请基于已有信息继续完成任务。";
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

                return formatSearchResults(root, query);
            }

        } catch (Exception e) {
            log.error("Failed to search web via Bocha API", e);
            return "Error searching web: " + e.getMessage();
        }
    }

    /**
     * 平台代采档：把搜索交给网关，官网持凭证调博查并按次扣 Credits。
     *
     * <p>返回的是**给模型看的文本**，所以失败时不能抛异常打断整轮对话——
     * 与「未配置」那条既有分支同一口径：说清楚发生了什么、下一步是什么，
     * 然后让模型基于已有信息继续。抛异常会让一次搜索失败变成一次对话失败。
     */
    private String searchViaPlatform(String query) {
        try {
            com.checkba.service.platform.PlatformGatewayClient.Result result =
                    platformGatewayClient.call("search", "web",
                            java.util.Map.of("query", query, "count", 10),
                            SEARCH_TIMEOUT_SECONDS);
            return formatSearchResults(result.data(), query);
        } catch (com.checkba.service.platform.GatewayException e) {
            log.warn("平台搜索失败 kind={}: {}", e.getKind(), e.getMessage());
            return "网络搜索本次不可用：" + e.getMessage() + e.userHint()
                    + " 本次已跳过网络搜索，请基于已有信息继续完成任务。";
        }
    }

    /** 博查响应 → 给模型看的摘要文本。两条档位共用，避免格式漂移。 */
    private String formatSearchResults(JsonNode root, String query) {
        // Response structure: { "data": { "webPages": { "value": [...] } } }
        // 网关档下 root 已经是 data 那一层，所以两种形态都试一次。
        JsonNode webPages = root.path("data").path("webPages").path("value");
        if (webPages.isMissingNode() || !webPages.isArray() || webPages.size() == 0) {
            webPages = root.path("webPages").path("value");
        }

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

            // 导航失败与「等待加载状态超时」必须分开处理。旧代码把两者裹在同一个 catch 里
            // 一律「continue, content might be partially loaded」，于是域名解析失败、连接被拒、
            // 或被 SSRF 路由拦掉时，page.content() 拿到的是 about:blank 或 Chromium 自己的
            // 错误页，照样拼成 "Page Title: …\n\nContent:…" 返回——模型以为自己读到了网页。
            com.microsoft.playwright.Response navResponse;
            try {
                navResponse = page.navigate(url);
            } catch (Exception e) {
                log.warn("browse_url navigation failed for {}: {}", url, e.getMessage());
                return "Error browsing URL: navigation failed (" + e.getMessage()
                        + "). The page was NOT loaded — do not treat this as empty content.";
            }
            try {
                // 网络空闲等待超时是可以容忍的：SPA 常年有长连接，此时页面正文其实已经在了
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                        new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(20000));
            } catch (Exception e) {
                log.warn("Playwright wait-for-networkidle timed out for {} (continuing with loaded content): {}",
                        url, e.getMessage());
            }
            if (navResponse != null && navResponse.status() >= 400) {
                return "Error browsing URL: the site returned HTTP " + navResponse.status()
                        + ". The page was NOT loaded — do not treat this as empty content.";
            }

            // Get standard HTML
            String html = page.content();
            
            // Use Jsoup to parse and clean the rendered HTML
            Document doc = Jsoup.parse(html);
            String title = doc.title();

            // Basic extraction: remove script, style, nav, footer
            doc.select("script, style, nav, footer, header, aside, .ads, .advertisement, noscript, iframe").remove();

            String bodyText = doc.body() == null ? "" : doc.body().text();

            return renderBrowseResult(url, title, bodyText);

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

    /**
     * 把抓到的标题/正文拼成给模型的结果；**抓不到正文时绝不伪装成成功**。
     *
     * <p>抽成静态纯函数是为了能单测：browse_url 本身要拉起 Playwright + Chromium，
     * 单元测试里跑不动，而这里正是判「有没有真读到东西」的地方。
     *
     * <p>为什么空正文必须报错：旧实现无论如何都返回 "Page Title: …\n\nContent:\n…"，
     * 即使正文是空的。这个串**非空**，所以既不会被编排器的空输出归一拦住，
     * 也不会被 ToolResult.success() 判成失败——模型收到一个「成功但什么都没有」的结果，
     * 转头就当这个网页确实没内容。
     */
    static String renderBrowseResult(String url, String title, String bodyText) {
        String body = bodyText == null ? "" : bodyText.trim();
        if (body.isEmpty()) {
            String t = title == null ? "" : title.trim();
            return "Error browsing URL: loaded " + url + " but extracted no readable text"
                    + (t.isEmpty() ? "" : " (page title: " + t + ")")
                    + ". The page may be JS-gated, login-walled, or a PDF/binary — "
                    + "do not treat this as 'the page is empty'.";
        }
        return "Page Title: " + (title == null ? "" : title) + "\n\nContent:\n" + body;
    }
}
