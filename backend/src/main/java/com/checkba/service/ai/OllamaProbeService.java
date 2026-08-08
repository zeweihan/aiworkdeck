package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;
import com.checkba.service.SystemSettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 Ollama 只读探测（供应商三档改造的决策 2：Ollama 保留为「离线/实验」档）。
 *
 * <p><b>为什么需要它</b>：Ollama 档不需要任何密钥，所以向导没法像 OpenRouter 那样用
 * 「Key 填了没有」判断可用性——用户可能根本没装 Ollama，或者装了但没 pull 模型。
 * 改造前全仓零探测代码（11434 只出现在配置常量里），结果是用户在向导里选完本地档，
 * 一路到发第一条消息才收到一句 Connection refused，完全不知道下一步该做什么。
 *
 * <p><b>只读、短超时、失败当「服务没起」</b>：探测跑在向导的关键路径上，
 * 不能让它把界面卡住，也不能抛 500——对用户来说「连不上」「返回了看不懂的东西」
 * 「JSON 解析不了」都是同一件事：Ollama 现在用不了。所以除 200 + 可解析的
 * {@code /api/tags} 响应之外，一律归为 {@link Status#SERVICE_DOWN}。
 *
 * <p><b>不接受调用方传入的地址</b>：baseUrl 只取本机配置。让前端传任意 URL 进来
 * 等于把后端做成 SSRF 跳板（云后端与桌面后端共用这套代码）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OllamaProbeService {

    /**
     * 目标模型的 system_setting 覆盖键（空白视为未配置，回退 yml 的 ai.model.ollama.model-name）。
     *
     * <p>刻意引用 {@link ChatModelFactory} 的常量而不是自己写一份字面量：探测读的键
     * 必须和真实路由读的键是同一个，否则用户在设置页换了本机模型后会看到
     * 「探测说已就绪、对话却发给另一个模型」。地址键（{@link #SETTING_BASE_URL}）同理。
     */
    public static final String SETTING_MODEL = ChatModelFactory.SETTING_OLLAMA_MODEL;

    /** 服务地址的 system_setting 覆盖键，与真实路由同源 */
    public static final String SETTING_BASE_URL = ChatModelFactory.SETTING_OLLAMA_BASE_URL;

    /**
     * 探测超时（连接与响应各 2 秒）。打的是 localhost，2 秒足够；
     * 长超时只会让向导在「没装 Ollama」这个最常见的分支上干等。
     */
    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final AiModelProperties aiModelProperties;
    private final SystemSettingService systemSettingService;

    /**
     * 固定 HTTP/1.1：JDK HttpClient 默认 HTTP_2，对明文地址会先发 h2c 升级请求，
     * 某些本地服务收到后直接不回字节、报「header parser received no bytes」，
     * 在上层只表现为一句「连不上」，排查成本极高（HttpAccountTransport 踩过同一个坑）。
     */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /** 探测结论三态，前端据此渲染「下一步该做什么」 */
    public enum Status {
        /** 服务在跑且目标模型已 pull */
        READY,
        /** 服务在跑，但目标模型不在已下载列表里 */
        MODEL_MISSING,
        /** 连不上 / 响应异常（一律归到这一档，见类注释） */
        SERVICE_DOWN
    }

    /**
     * 探测结果。字段顺序即 JSON 键序（Jackson 对 record 按声明顺序序列化）。
     *
     * @param installedModels 已 pull 的模型名（SERVICE_DOWN 时为空列表），供向导直接渲染选择器
     * @param command         建议用户在终端执行的命令（就绪时为 null）；原样带出，前端不要自己拼
     */
    public record ProbeResult(
            Status status,
            String baseUrl,
            String targetModel,
            List<String> installedModels,
            String message,
            String nextStep,
            String command) {
    }

    /** 探测 yml/DB 里配置的目标模型 */
    public ProbeResult probe() {
        return probe(null);
    }

    /**
     * 探测指定模型。
     *
     * @param modelOverride 非空时探测这个模型（向导里用户还没保存设置就想先试的场景）；
     *                      空则取 system_setting 的 {@link #SETTING_MODEL}，再回退 yml
     */
    public ProbeResult probe(String modelOverride) {
        AiModelProperties.Ollama config = aiModelProperties.getOllama();
        String baseUrl = trimTrailingSlash(resolveSetting(SETTING_BASE_URL, config.getBaseUrl()));
        String targetModel = StringUtils.hasText(modelOverride)
                ? modelOverride.trim()
                : resolveSetting(SETTING_MODEL, config.getModelName());

        List<String> installed = fetchInstalledModels(baseUrl);
        if (installed == null) {
            return new ProbeResult(Status.SERVICE_DOWN, baseUrl, targetModel, List.of(),
                    "无法连接本地 Ollama 服务（" + baseUrl + "）。",
                    "确认本机已安装 Ollama 并让它保持运行，然后重新检测。",
                    "ollama serve");
        }
        if (installed.stream().anyMatch(name -> sameModel(name, targetModel))) {
            return new ProbeResult(Status.READY, baseUrl, targetModel, installed,
                    "本地 Ollama 已就绪，模型 " + targetModel + " 可用。",
                    // 决策 2：langchain4j 0.36 的 OllamaStreamingChatModel 没有带 tools 的三参 generate，
                    // AGENT/PLAN 在编排层必抛异常。这一句必须出现在「就绪」分支里，
                    // 否则用户会以为选了本地档就能用全部功能。
                    "本地档只支持 ASK（问答）模式；需要 Agent 自动调用工具或制定计划时，改用云端供应商。",
                    null);
        }
        return new ProbeResult(Status.MODEL_MISSING, baseUrl, targetModel, installed,
                "本地 Ollama 服务正常，但尚未下载模型 " + targetModel
                        + "（本机已有 " + installed.size() + " 个模型）。",
                "在终端执行下面的命令下载模型，完成后重新检测。",
                "ollama pull " + targetModel);
    }

    /** DB 优先于 yml（口径与 ChatModelFactory 的其他 ai.* 键一致：空白视为未配置） */
    private String resolveSetting(String key, String ymlFallback) {
        String value = systemSettingService.get(key, null);
        return StringUtils.hasText(value) ? value.trim() : ymlFallback;
    }

    /**
     * 拉取已 pull 的模型名。
     *
     * @return 模型名列表；连不上 / 非 200 / 响应解析不了时返回 null（调用方一律当服务没起）
     */
    private List<String> fetchInstalledModels(String baseUrl) {
        String url = baseUrl + "/api/tags";
        HttpReply reply = get(url);
        if (reply.status() != 200) {
            log.debug("Ollama 探测：{} 返回 {}", url, reply.status());
            return null;
        }
        return parseTags(reply.body());
    }

    /** 解析 /api/tags：{"models":[{"name":"qwen3-vl:8b",...}]}；结构不符返回 null */
    private List<String> parseTags(String body) {
        try {
            JsonNode models = mapper.readTree(body).path("models");
            if (!models.isArray()) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (JsonNode m : models) {
                String name = m.path("name").asText(null);
                if (StringUtils.hasText(name)) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            log.debug("Ollama /api/tags 响应解析失败: {}", e.toString());
            return null;
        }
    }

    /**
     * 模型名比对：Ollama 的 tags 恒带 tag 后缀（{@code llama3} 存下来是 {@code llama3:latest}），
     * 而用户与 yml 里常常只写仓库名。两边都补上默认 tag 再比，忽略大小写。
     */
    private boolean sameModel(String installed, String target) {
        return withDefaultTag(installed).equalsIgnoreCase(withDefaultTag(target));
    }

    private String withDefaultTag(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.contains(":") ? trimmed : trimmed + ":latest";
    }

    private String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 一次 HTTP 往返的结果（status=-1 表示根本没连上） */
    record HttpReply(int status, String body) {
        static final int NETWORK_FAILURE = -1;
    }

    /**
     * 测试用钩子：单测覆写这个方法替换 HTTP 往返。
     * 真发请求会让测试结果取决于本机装没装 Ollama，那样测试就不可信了。
     */
    HttpReply get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpReply(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HttpReply(HttpReply.NETWORK_FAILURE, null);
        } catch (Exception e) {
            // 「没装 Ollama」是最常见的分支，不该刷 WARN 日志；死因留在 debug 级别够排查了
            log.debug("Ollama 探测失败 {}: {}", url, e.toString());
            return new HttpReply(HttpReply.NETWORK_FAILURE, null);
        }
    }
}
