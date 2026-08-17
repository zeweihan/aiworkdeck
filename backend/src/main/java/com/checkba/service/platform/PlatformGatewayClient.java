package com.checkba.service.platform;

import com.checkba.service.LangText;
import com.checkba.service.account.AccountService;
import com.checkba.service.site.SiteProfileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 平台服务网关的<b>唯一出站出口</b>。
 *
 * <p>桌面端带机器级 {@code awdk_} 调官网 {@code POST {site}/api/gateway/{service}/{op}}，
 * 官网持真实供应商凭证调上游、按量折算 Credits 从同一个余额扣、把结果原样回来。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-08-17-unified-credits-service-gateway-design.md}。
 *
 * <h3>为什么只有这一个出口</h3>
 * 记账、幂等、错误分类三样东西每接一家服务都要用一遍。散到每个 service 里去抄，
 * 抄到第三家必然漏掉一样——而漏掉的那样如果是幂等，代价就是用户重试一次扣两次钱。
 *
 * <h3>幂等键与重试</h3>
 * 每次 {@link #call} 生成一个幂等键，网络失败时<b>带同一个键</b>重试一次。
 * 这正是幂等键存在的理由：第一次可能已经在服务端扣了费，换个新键重试就是扣两次。
 * 服务端见到已完成的键会回放原响应；见到仍在途的键回 409，我们如实告诉用户稍后再试。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformGatewayClient {

    private final PlatformGatewayTransport transport;
    private final AccountService accountService;
    private final SiteProfileService siteProfileService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 一次网关调用的结果。{@code chargedCents} 是本次实际扣的 Credits，供 UI 显示。 */
    public record Result(JsonNode data, int chargedCents, double units, String unit) {}

    /**
     * 调一次网关。
     *
     * @param service        服务名（search / ocr / tts / qichacha / tushare / pkulaw / asr）
     * @param op             操作名
     * @param params         请求参数，会原样序列化成 JSON body
     * @param timeoutSeconds 响应超时。<b>按服务给</b>，不要沿用账户通道的 5 秒
     * @throws GatewayException 一律带明确的 {@link GatewayException.Kind}，调用方据此决定文案与下一步
     */
    public Result call(String service, String op, Map<String, Object> params, int timeoutSeconds) {
        JsonNode root = postJson("/api/gateway/" + service + "/" + op, params, true, timeoutSeconds);
        JsonNode billing = root.path("billing");
        return new Result(
                root.path("data"),
                billing.path("chargedCents").asInt(0),
                billing.path("units").asDouble(0),
                billing.path("unit").asText(""));
    }

    /**
     * 打一个网关端点并原样返回响应体。
     *
     * <p>给响应形态不是通用 {@code {ok,data,billing}} 的端点用——今天只有语音那条
     * 异步长任务链路（ticket / submit / task）。它必须拆成三个端点是因为几百 MB 的
     * 音频不能进 Next.js 进程，见设计 §6.1。
     *
     * @param idempotent 会扣费就传 true。true 时带幂等键，且网络失败**带同一个键**
     *                   重试一次——换新键等于放弃幂等保护：第一次若已在服务端扣过费，
     *                   重试就是扣两次。
     */
    public JsonNode postJson(String path, Map<String, Object> params, boolean idempotent, int timeoutSeconds) {
        String key = requireKey();
        String body;
        try {
            body = objectMapper.writeValueAsString(params == null ? Map.of() : params);
        } catch (Exception e) {
            throw new GatewayException(GatewayException.Kind.BAD_REQUEST,
                    LangText.of("请求参数无法序列化", "Request parameters could not be serialized"));
        }
        String idempotencyKey = idempotent ? UUID.randomUUID().toString().replace("-", "") : null;

        PlatformGatewayTransport.Reply reply =
                transport.send("POST", siteProfileService.baseUrl() + path, key, idempotencyKey, body, timeoutSeconds);
        if (reply.networkFailure()) {
            reply = transport.send("POST", siteProfileService.baseUrl() + path, key, idempotencyKey, body, timeoutSeconds);
        }
        return handle(reply);
    }

    /** 只读 GET。不扣费，所以不占幂等键。 */
    public JsonNode getJson(String path, int timeoutSeconds) {
        String key = requireKey();
        PlatformGatewayTransport.Reply reply =
                transport.send("GET", siteProfileService.baseUrl() + path, key, null, null, timeoutSeconds);
        if (reply.networkFailure()) {
            reply = transport.send("GET", siteProfileService.baseUrl() + path, key, null, null, timeoutSeconds);
        }
        return handle(reply);
    }

    /** 不扣费的只读调用（当前只有单价表）。不带幂等键。 */
    public JsonNode getPricing(int timeoutSeconds) {
        return getJson("/api/gateway/pricing", timeoutSeconds);
    }

    /**
     * 本月分服务消耗：{@code {month, services:[{service, cents, calls}], totalCents, balanceCents, pendingHoldCents}}。
     *
     * <p><b>与单价表分开两个端点是官网侧刻意的</b>：单价几乎不动、桌面端给它挂 10 分钟缓存，
     * 用量花一笔就要变。合并会逼两种保鲜要求互相迁就——要么缓存把用量冻住十分钟
     * （用户刚转写完看不到扣费），要么为了用量取消缓存（每次工具调用都来问一遍单价）。
     */
    public JsonNode getMonthlyUsage(int timeoutSeconds) {
        return getJson("/api/gateway/usage", timeoutSeconds);
    }

    /**
     * 本机是否已连账户。platform 档的「已配置」判据就是它——用不着那 5 个供应商凭证，
     * 但没有账户 Key 就连请求都发不出去。
     */
    public boolean connected() {
        return accountService.currentKeyOrNull() != null;
    }

    private String requireKey() {
        String key = accountService.currentKeyOrNull();
        if (key == null) {
            // 不发请求。发出去只会拿回 401，而 401 在桌面端会被判成凭据失效并清空权益缓存。
            throw new GatewayException(GatewayException.Kind.NOT_CONNECTED,
                    LangText.of("尚未连接 AI WorkDeck 账户，可在设置页「账户与用量」粘贴账户 Key",
                            "No AI WorkDeck account is connected. Paste your account key in Settings → Account & usage"));
        }
        return key;
    }

    private JsonNode handle(PlatformGatewayTransport.Reply reply) {
        if (reply.networkFailure()) {
            throw new GatewayException(GatewayException.Kind.GATEWAY_UNREACHABLE, unreachableMessage());
        }
        if (reply.status() != 200) {
            throw mapError(reply);
        }
        return parse(reply.body());
    }

    /**
     * 状态码 + error 码 → Kind。
     *
     * <p><b>401 是唯一的凭据问题</b>；其余全是业务错误，绝不能让上层把它们当成掉线。
     * 5xx 也分两种：502/504 是上游挂了（其余服务不受影响），503 是我们没配好。
     */
    private GatewayException mapError(PlatformGatewayTransport.Reply reply) {
        String code = "";
        String serverMessage = "";
        try {
            JsonNode body = objectMapper.readTree(reply.body() == null ? "{}" : reply.body());
            code = body.path("error").asText("");
            serverMessage = body.path("message").asText("");
        } catch (Exception ignored) {
            // 非 JSON 响应：落到下面按状态码分类
        }

        return switch (code) {
            case "no_credits" -> new GatewayException(GatewayException.Kind.NO_CREDITS,
                    fallback(serverMessage, LangText.of(
                            "Credits 余额不足，到官网充值后即可继续",
                            "Your Credits balance is insufficient. Top up on the website to continue")));
            case "service_disabled", "not_configured" -> new GatewayException(
                    GatewayException.Kind.SERVICE_DISABLED,
                    fallback(serverMessage, LangText.of(
                            "该服务暂未开放，可在系统管理里改用自己的 Key",
                            "This service is not available yet. You can switch to your own key in System settings")));
            // 用户自己设的单次任务上限被撞上。它与其余七档的性质不同：**任务没坏，钱也没白花**，
            // 用户确认一句就能继续（设计 §4.9）。不认这个码的话它会落到下面按状态码分类的
            // 409 分支，变成一句「平台服务返回了预期外的状态」——一个可恢复的确认被表达成故障。
            case "budget_exceeded" -> new GatewayException(GatewayException.Kind.BUDGET_EXCEEDED,
                    fallback(serverMessage, LangText.of(
                            "本次任务的花费已达到你设定的上限，确认后可继续",
                            "This task has reached the spending cap you set. Confirm to continue")));
            case "in_flight" -> new GatewayException(GatewayException.Kind.UPSTREAM_FAILED,
                    fallback(serverMessage, LangText.of(
                            "同一次操作仍在处理中，稍候重试",
                            "The same operation is still being processed, retry shortly")));
            case "upstream_failed" -> new GatewayException(GatewayException.Kind.UPSTREAM_FAILED,
                    fallback(serverMessage, LangText.of(
                            "该服务的上游暂时不可用，其余功能不受影响",
                            "The upstream provider for this service is temporarily unavailable; other features are unaffected")));
            case "bad_request" -> new GatewayException(GatewayException.Kind.BAD_REQUEST,
                    fallback(serverMessage, LangText.of("请求不合法", "The request was rejected as invalid")));
            default -> switch (reply.status()) {
                case 401, 403 -> new GatewayException(GatewayException.Kind.UNAUTHORIZED,
                        LangText.of("账户 Key 无效或已被撤销", "The account key is invalid or has been revoked"));
                case 502, 504 -> new GatewayException(GatewayException.Kind.UPSTREAM_FAILED,
                        LangText.of("该服务的上游暂时不可用，其余功能不受影响",
                                "The upstream provider for this service is temporarily unavailable; other features are unaffected"));
                case 503 -> new GatewayException(GatewayException.Kind.SERVICE_DISABLED,
                        LangText.of("该服务暂未开放，可在系统管理里改用自己的 Key",
                                "This service is not available yet. You can switch to your own key in System settings"));
                default -> new GatewayException(GatewayException.Kind.MALFORMED,
                        LangText.of("平台服务返回了预期外的状态（" + reply.status() + "）",
                                "The platform service returned an unexpected status (" + reply.status() + ")"));
            };
        };
    }

    /**
     * 网关不可达的文案<b>必须明说这不是用户的网络问题</b>。
     * 账户通道那句「无法连接 AI WorkDeck 服务器，请检查网络后重试」会让用户去重启路由器，
     * 而真实原因往往是我们正在发版。
     */
    private String unreachableMessage() {
        return LangText.of(
                "AI WorkDeck 平台服务暂时不可用（不是你的网络问题），稍后重试，或在系统管理里改用自己的 Key",
                "The AI WorkDeck platform service is temporarily unavailable (not a problem with your network). "
                        + "Retry shortly, or switch to your own key in System settings");
    }

    /** 服务端文案优先（它更贴近具体原因），缺失时用本地兜底。 */
    private String fallback(String serverMessage, String local) {
        return serverMessage == null || serverMessage.isBlank() ? local : serverMessage;
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new GatewayException(GatewayException.Kind.MALFORMED,
                    LangText.of("平台服务返回了无法解析的内容", "The platform service returned unparseable content"));
        }
    }
}
