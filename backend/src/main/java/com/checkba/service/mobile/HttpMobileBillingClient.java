package com.checkba.service.mobile;

import com.checkba.service.LangText;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link MobileBillingClient} 的生产实现：POST {@code {base}/api/internal/account}，
 * 鉴权 header {@code X-Internal-Secret}，body {@code {action, ...}}
 * （dev-board#425，spec §3.2）。形状照抄 {@link HttpTransferBillingClient}。
 *
 * <p>base-url/secret 任一未配置（桌面/本地默认空）视为该服务器未开通统一账户充值，
 * 四个方法一律短路抛 DISABLED，<b>不发请求</b>——不静默放行、也不装作有余额。
 *
 * <p>网络失败（连不上/超时/中断，不含"连上了但业务报错"）只有 create-recharge 带同一
 * 幂等键重试一次；resolve/balance/query 是只读查询，失败直接报 UNAVAILABLE，不重试。
 *
 * <p>失败分类见 {@link #parse}：判据是<b>响应体里有没有 {@code error} 字段</b>，
 * 空体 404（官网 env 未配/secret 不符）与带 body 的 404（真查无此账户）必须分开，
 * 否则密钥配错会被全量用户看成「还没关联统一账户」（复审 C3）。
 */
@Component
@Slf4j
public class HttpMobileBillingClient implements MobileBillingClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String secret;
    private final ObjectMapper om;

    /** 固定 HTTP/1.1，理由与 HttpTransferBillingClient 逐字相同：明文地址走 h2c 升级会被 Next 开发服务器吞掉。 */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public HttpMobileBillingClient(
            @Value("${mobile.billing.base-url:}") String baseUrl,
            @Value("${mobile.billing.secret:}") String secret,
            ObjectMapper om) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.om = om;
    }

    @Override
    public String resolveAccountId(String phone, String email, boolean create) {
        boolean hasPhone = phone != null && !phone.isBlank();
        boolean hasEmail = email != null && !email.isBlank();
        if (hasPhone == hasEmail) {
            // 契约是"二选一恰好一个"。两个都给或都不给是调用方的编程错误，不是用户输入错误，
            // 所以直接 IllegalStateException 而不是走 MobileBillingException 那套用户可读文案。
            throw new IllegalStateException("resolve 必须且只能给 phone 与 email 中的一个");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "resolve");
        if (hasPhone) {
            body.put("phone", phone.trim());
        } else {
            body.put("email", email.trim());
        }
        // 显式传，不靠官网的默认值：官网 create 默认 false，但这里传死了才能在读日志/抓包时
        // 一眼看出"这次调用到底允不允许建号"。false 时官网只查不建，查无此人回带 body 的 404。
        body.put("create", create);
        return call(body).path("accountId").asText(null);
    }

    @Override
    public BalanceResult balance(String accountId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "balance");
        body.put("accountId", accountId);
        JsonNode json = call(body);
        return new BalanceResult(
                json.path("balanceCents").asLong(0),
                json.path("currency").asText("CNY"),
                json.hasNonNull("plan") ? json.path("plan").asText() : null);
    }

    @Override
    public RechargeOrder createRecharge(String accountId, long amountCents, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "create-recharge");
        body.put("accountId", accountId);
        body.put("amountCents", amountCents);
        body.put("idempotencyKey", idempotencyKey);
        JsonNode json = callWithRetry(body);
        return new RechargeOrder(
                json.path("present").asText(null),
                json.path("outTradeNo").asText(null),
                json.path("amountCents").asLong(0),
                textOrNull(json, "codeUrl"),
                textOrNull(json, "qrCode"),
                textOrNull(json, "redirectUrl"));
    }

    @Override
    public RechargeStatus queryRecharge(String accountId, String outTradeNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "query");
        body.put("accountId", accountId);
        body.put("outTradeNo", outTradeNo);
        JsonNode json = call(body);
        return new RechargeStatus(
                json.path("status").asText(null),
                json.path("paid").asBoolean(false),
                json.path("amountCents").asLong(0));
    }

    // ==================== 内部 ====================

    private static String textOrNull(JsonNode json, String field) {
        return json.hasNonNull(field) ? json.path(field).asText() : null;
    }

    private void requireConfigured() {
        if (baseUrl.isEmpty() || secret.isEmpty()) {
            throw new MobileBillingException(MobileBillingKind.DISABLED,
                    LangText.of("此服务器未开通统一账户充值",
                            "Unified account top-up is not enabled on this server"));
        }
    }

    /** 只读动作用：单次调用，网络失败不重试。 */
    private JsonNode call(Map<String, Object> body) {
        requireConfigured();
        try {
            return parse(send(writeJson(body)));
        } catch (NetworkFailure e) {
            log.warn("统一账户记账口请求失败（网络）: {}", e.getCause() == null ? e.toString() : e.getCause().toString());
            throw unavailable();
        }
    }

    /** create-recharge 用：网络失败带同一幂等键（body 原样）重试一次。 */
    private JsonNode callWithRetry(Map<String, Object> body) {
        requireConfigured();
        String json = writeJson(body);
        try {
            return parse(send(json));
        } catch (NetworkFailure first) {
            try {
                return parse(send(json));
            } catch (NetworkFailure second) {
                log.warn("统一账户记账口请求失败（网络，已重试一次）: {}",
                        second.getCause() == null ? second.toString() : second.getCause().toString());
                throw unavailable();
            }
        }
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return om.writeValueAsString(body);
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private HttpResponse<String> send(String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/internal/account"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new NetworkFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NetworkFailure(e);
        }
    }

    /**
     * 200 按成功解析；其余按<b>响应体里有没有 {@code error} 字段</b>再分：
     *
     * <ul>
     *   <li><b>空体 404</b> → {@link MobileBillingKind#UNAVAILABLE} + {@code log.warn}（复审 C3）。
     *       官网对「{@code AWD_MOBILE_BILLING_SECRET} 未配置 / header 不符」刻意回空体 404
     *       （对外部探测者与「这条路由不存在」不可区分，这一点不改），它与「accountId 查无此人」
     *       曾经是同一个响应——于是密钥配错一个字符，全量用户会被告知「还没关联统一账户」，
     *       而日志里一条痕迹都没有。这正是 mobile-sync.md 红线 7 禁止的「上游故障被吞成没有账户」。</li>
     *   <li><b>带 JSON body 的 404</b>（{@code {"error":"account_not_found"}} 等）→ 真业务
     *       {@link MobileBillingKind#NOT_FOUND}。持有正确 secret 的调用方只有我们自己的
     *       Java 后端，能区分不构成对外探测面。</li>
     *   <li><b>409</b> {@code order_already_paid} / {@code idempotency_conflict} →
     *       ALREADY_PAID / IDEMPOTENCY_CONFLICT，且<b>把官网一并回的 {@code outTradeNo} 带走</b>
     *       （复审 C4）。这是「App 被杀后没存下单号」的恢复路径，丢了它用户既拿不到货
     *       也查不到单。</li>
     *   <li>其余 4xx → REJECTED（error 串只进日志）；5xx 与解析失败 → UNAVAILABLE。</li>
     * </ul>
     */
    private JsonNode parse(HttpResponse<String> resp) {
        int status = resp.statusCode();
        if (status == 200) {
            try {
                return om.readTree(resp.body());
            } catch (Exception e) {
                log.warn("统一账户记账口响应解析失败: body={}", resp.body());
                throw unavailable();
            }
        }

        JsonNode body = tryReadTree(resp.body());
        // 官网的 fail() 恒带 error；没有 error 就说明这不是官网的业务响应
        String machineError = body == null ? null : textOrNull(body, "error");
        String outTradeNo = body == null ? null : textOrNull(body, "outTradeNo");

        if (status == 404) {
            if (machineError == null) {
                log.warn("统一账户记账口回空体 404：这不是「查无此账户」，而是鉴权/配置问题——"
                                + "核对本机 mobile.billing.secret 与官网 env AWD_MOBILE_BILLING_SECRET 是否一致、"
                                + "官网是否配了该 env（base-url={}）",
                        baseUrl);
                throw unavailable();
            }
            log.warn("统一账户记账口回 404: error={}", machineError);
            throw new MobileBillingException(MobileBillingKind.NOT_FOUND,
                    LangText.of("未找到对应的统一账户", "No matching unified account was found"),
                    machineError);
        }

        if (status == 409 && "order_already_paid".equals(machineError)) {
            log.info("统一账户充值单已支付，回放单号供客户端查单: outTradeNo={}", outTradeNo);
            throw new MobileBillingException(MobileBillingKind.ALREADY_PAID,
                    LangText.of("这笔充值已经支付成功，请查看订单状态",
                            "This top-up has already been paid; please check the order status"),
                    machineError, outTradeNo);
        }
        if (status == 409 && "idempotency_conflict".equals(machineError)) {
            log.warn("统一账户充值幂等键冲突: outTradeNo={}", outTradeNo);
            throw new MobileBillingException(MobileBillingKind.IDEMPOTENCY_CONFLICT,
                    LangText.of("该充值请求与已有订单不一致，请重新发起",
                            "This top-up request conflicts with an existing order; please start a new one"),
                    machineError, outTradeNo);
        }

        if (status >= 400 && status < 500) {
            log.warn("统一账户记账口被拒: status={}, error={}", status, machineError);
            throw new MobileBillingException(MobileBillingKind.REJECTED,
                    LangText.of("充值请求被拒绝，请稍后重试或联系客服",
                            "The top-up request was rejected. Please try again later or contact support."),
                    machineError);
        }
        log.warn("统一账户记账口调用失败: status={}, body={}", status, resp.body());
        throw unavailable();
    }

    /** 解析不出 JSON（空体、HTML 错误页）就回 null——判据本身要能区分"没有 body"与"body 里没有 error"。 */
    private JsonNode tryReadTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = om.readTree(body);
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    private MobileBillingException unavailable() {
        return new MobileBillingException(MobileBillingKind.UNAVAILABLE,
                LangText.of("账户服务暂不可用，请稍后再试",
                        "Account service is temporarily unavailable, please try again later"));
    }

    /** send() 内部标记网络层失败（连不上/超时/中断），与"连上了但业务报错"区分开，只有前者会重试。 */
    private static class NetworkFailure extends RuntimeException {
        NetworkFailure(Throwable cause) {
            super(cause);
        }
    }
}
