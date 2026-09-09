// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
 * 每个方法都短路抛 DISABLED，<b>不发请求</b>——不静默放行、也不装作有余额。
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
    public RechargeOrder createRecharge(String accountId, long amountCents, String idempotencyKey,
                                        String channel, String productId, String wxCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "create-recharge");
        body.put("accountId", accountId);
        body.put("amountCents", amountCents);
        body.put("idempotencyKey", idempotencyKey);
        // 通道三兄弟只在指定了 channel 时上行：不传 = 官网走站点默认通道（第一期行为），
        // 传一串 null 上去只会让官网多几个要判空的字段
        if (channel != null && !channel.isBlank()) {
            body.put("channel", channel);
            body.put("productId", productId);
            body.put("wxCode", wxCode);
        }
        // wxvp 的 wxCode 是一次性的：第一发若已到达官网、只是响应丢了，带同一 body 重试会让官网
        // 再换一次 code 而失败。所以 wxvp 只发一次，网络失败回 UNAVAILABLE，由小程序重新
        // wx.login 拿新 code、带同一 idempotencyKey 再来（官网对命中幂等键的 pending 单用新 code 重签）。
        boolean wxvp = "wxvp".equals(channel);
        JsonNode json = wxvp ? call(body) : callWithRetry(body);
        return new RechargeOrder(
                json.path("present").asText(null),
                json.path("outTradeNo").asText(null),
                json.path("amountCents").asLong(0),
                textOrNull(json, "codeUrl"),
                textOrNull(json, "qrCode"),
                textOrNull(json, "redirectUrl"),
                textOrNull(json, "signData"),
                textOrNull(json, "paySig"),
                textOrNull(json, "signature"));
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

    /**
     * 微信 code 换手机号（action=wx-phone，dev-board#534）。走不重试的 {@link #call}——
     * code 一次性，带同一 body 重试只会让官网再换一次 code 而必败。
     *
     * <p>失败要翻成<b>登录场景</b>的话：这条路上用户看到的是登录页，共用计费那套
     * 「充值请求被拒绝，请联系客服」只会让人不知所措。三类可预期的失败各给一句，
     * 判据是官网回的 {@code error} 串（{@link #parse} 已把它带进异常）。
     *
     * <p>官网回的手机号形状不对（空、非大陆号）按上游故障处理：宁可让用户改用短信登录，
     * 也不能拿一个来路不明的串去 {@code findOrCreateByPhone} 建号。
     */
    @Override
    public String wxPhone(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "wx-phone");
        body.put("code", code);
        JsonNode json;
        try {
            json = call(body);
        } catch (MobileBillingException e) {
            throw remapWxPhoneFailure(e);
        }
        String phone = textOrNull(json, "phone");
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            log.warn("微信一键登录：官网回的手机号形状不对，按上游故障处理");
            throw unavailable();
        }
        return phone;
    }

    /** wx-phone 的失败翻译，见 {@link #wxPhone}。认不出的失败原样抛回。 */
    private MobileBillingException remapWxPhoneFailure(MobileBillingException e) {
        String err = e.getMachineError();
        // 本机没配 base-url/secret（DISABLED），与官网没配小程序 AppSecret（503 wx_not_configured）
        // 对用户是同一件事：这台服务器上没有这条登录路，去用短信。
        if (e.getKind() == MobileBillingKind.DISABLED || "wx_not_configured".equals(err)) {
            return new MobileBillingException(MobileBillingKind.DISABLED,
                    LangText.of("本服务器未开通微信一键登录，请用短信验证码登录",
                            "WeChat one-tap sign-in is not enabled on this server; please sign in with an SMS code"),
                    err);
        }
        if (e.getKind() == MobileBillingKind.REJECTED) {
            if ("unsupported_region".equals(err)) {
                return new MobileBillingException(MobileBillingKind.REJECTED,
                        LangText.of("目前仅支持中国大陆手机号",
                                "Only Chinese mainland phone numbers are supported"),
                        err);
            }
            // 401 invalid_wx_code，以及官网其余 4xx：对用户都是「这张 code 没换成」，
            // 下一步都是重新授权或改用短信，不该分叉出第三种说法。
            return new MobileBillingException(MobileBillingKind.REJECTED,
                    LangText.of("微信授权已过期，请重试",
                            "The WeChat authorization has expired, please try again"),
                    err);
        }
        return e;
    }

    /**
     * 删账号（action=delete-account，dev-board#434）。只读语义的反面：<b>失败必须响亮</b>，
     * 所以走不重试的 {@link #call}——重试一次删除请求换不来什么，反而会把「官网到底删没删」
     * 搅得更不清楚；调用方拿到异常就中止本地删除，用户稍后再试即可。
     *
     * <p>官网回带 body 的 404（{@code account_not_found}）不是失败：那边本来就没有这个账户，
     * 与「刚刚删掉了」对本地是同一个结论，回 {@code deleted=true}。
     * <b>空体 404 仍是配置/鉴权问题</b>（{@link #parse}），照样抛 UNAVAILABLE——
     * 把它当成「已经删了」等于密钥配错时全量用户的官网账户被静默留下。
     */
    @Override
    public DeleteAccountResult deleteAccount(String accountId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "delete-account");
        body.put("accountId", accountId);
        JsonNode json;
        try {
            json = call(body);
        } catch (MobileBillingException e) {
            if (e.getKind() == MobileBillingKind.NOT_FOUND) {
                log.info("官网侧统一账户已不存在，注销传导视为完成: error={}", e.getMachineError());
                return new DeleteAccountResult(true, null, null);
            }
            throw e;
        }
        boolean deleted = json.path("deleted").asBoolean(false);
        if (!deleted) {
            log.warn("官网拒绝删除统一账户: blocker={}", textOrNull(json, "blocker"));
        }
        return new DeleteAccountResult(deleted, textOrNull(json, "blocker"), textOrNull(json, "message"));
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
     *   <li><b>400</b> {@code product_mismatch}（dev-board#427）→ REJECTED，但给「请更新小程序」
     *       那句专用文案：价格权威在官网，这条错只会在小程序拿着过期档位表下单时出现，
     *       用户能自救，不该被引去联系客服。</li>
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

        // 档位与金额不符（dev-board#427）：价格权威在官网，云后端只做形态校验。走到这里
        // 说明小程序拿的是过期的档位表，用户能自救的动作是更新小程序，所以单给一句文案，
        // 而不是与「官网拒绝了这笔充值」共用那句「请联系客服」。
        if (status == 400 && "product_mismatch".equals(machineError)) {
            log.warn("充值档位与金额不符: status={}, error={}", status, machineError);
            throw new MobileBillingException(MobileBillingKind.REJECTED,
                    LangText.of("充值档位与金额不符，请更新小程序后重试",
                            "Top-up tier and amount do not match; please update the mini program and try again"),
                    machineError);
        }

        if (status >= 400 && status < 500) {
            log.warn("统一账户记账口被拒: status={}, error={}", status, machineError);
            throw new MobileBillingException(MobileBillingKind.REJECTED,
                    LangText.of("充值请求被拒绝，请稍后重试或联系客服",
                            "The top-up request was rejected. Please try again later or contact support."),
                    machineError);
        }
        log.warn("统一账户记账口调用失败: status={}, body={}", status, resp.body());
        // machineError 带上：5xx 也可能是官网明确的业务状态（如 wx-phone 的 503
        // wx_not_configured），调用方要据它翻文案；对其余 5xx 它本来就是 null。
        throw unavailable(machineError);
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
        return unavailable(null);
    }

    private MobileBillingException unavailable(String machineError) {
        return new MobileBillingException(MobileBillingKind.UNAVAILABLE,
                LangText.of("账户服务暂不可用，请稍后再试",
                        "Account service is temporarily unavailable, please try again later"),
                machineError);
    }

    /** send() 内部标记网络层失败（连不上/超时/中断），与"连上了但业务报错"区分开，只有前者会重试。 */
    private static class NetworkFailure extends RuntimeException {
        NetworkFailure(Throwable cause) {
            super(cause);
        }
    }
}
