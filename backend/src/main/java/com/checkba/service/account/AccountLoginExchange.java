package com.checkba.service.account;

import com.checkba.service.LangText;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 官网「账户登录」类请求的出站与错误分类（{@code /api/auth/sms-login/send-code}、
 * {@code /api/auth/exchange-key}）。
 *
 * <p>两个调用方形态完全不同却共用同一条契约，所以抽在这里而不是各写一份：
 * <ul>
 *   <li>{@link AccountService}——桌面端，站点可切、结果落 {@code ~/.aiworkdeck/account.json}；</li>
 *   <li>{@link AwdkLoginService}——插件云后端，站点钉死、结果换成本服务器的 awdt_ 设备令牌。</li>
 * </ul>
 * 真正要防的是下面那张 error code 表漂成两份：它是与官网仓约定的字面量，官网加一个码
 * 而这边只改了一处，另一处就会把新错误一律显示成「登录失败，请稍后重试」。
 *
 * <h3>与「带 Key 的业务请求」（{@code AccountService.handle}）的分工</h3>
 * 那条 401 一律解释成「Key 无效或已被吊销」。登录阶段还没有 Key，401 的真实含义是
 * 验证码错/口令错，套用那句文案会把用户引到完全错误的方向——所以这里**优先透传官网
 * 给出的 message**，官网没给才回落到本地兜底表。
 */
final class AccountLoginExchange {

    private AccountLoginExchange() {
    }

    /**
     * POST 一条登录类请求（不带 Authorization——此刻还没有 Key）。
     *
     * @param baseUrl 站点基址，已去掉尾部斜杠
     * @param path    以 / 开头的路径
     * @return 2xx 时的解析结果（空体按空 Map）
     * @throws AccountException NETWORK（不可达/5xx）、UNAUTHORIZED（验证码或口令不对）、
     *                          CONFLICT（{@code phone_binding_required} / {@code captcha_failed} /
     *                          {@code too_many_requests}）、MALFORMED（非 JSON）
     */
    static Map<String, Object> post(AccountTransport transport, ObjectMapper objectMapper,
                                    String baseUrl, String path, Map<String, Object> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e); // 入参都是 String，序列化不会失败
        }
        AccountTransport.Reply reply = transport.send("POST", baseUrl + path, null, json);
        if (reply.networkFailure()) {
            throw new AccountException(AccountException.Kind.NETWORK,
                    LangText.of("无法连接 AI WorkDeck 服务器，请检查网络后重试",
                            "Could not connect to the AI WorkDeck server, please check your network and retry"));
        }
        int status = reply.status();
        if (status >= 500) {
            throw new AccountException(AccountException.Kind.NETWORK,
                    LangText.of("AI WorkDeck 服务器暂时不可用，请稍后重试",
                            "The AI WorkDeck server is temporarily unavailable, please retry shortly"));
        }
        Map<String, Object> parsed = parse(objectMapper, reply.body());
        if (status >= 200 && status < 300) {
            return parsed;
        }
        String message = str(parsed.get("message"));
        String code = str(parsed.get("error"));
        // 官网的 message 优先——它常带本地判断不出来的具体信息（还要等几秒、地址被拒的原因）。
        // **但人机验证那两条例外**：官网只有英文文案，原样显示会让中文界面上冒出
        // 「Verification failed, please try again」（2026-08-18 真机踩到）。
        // 这两个 code 语义固定，本地双语文案的信息量不比英文原文少。
        if (message == null || message.isBlank() || LOCALIZED_OVER_UPSTREAM.contains(code)) {
            message = errorMessage(code);
        }
        // 403 phone_binding_required（过了补绑硬期限）/ captcha_failed（人机验证没过）、
        // 429 too_many_requests（发码太频繁）都是业务态，不是凭据失效——
        // 归到 UNAUTHORIZED 会让上层去清本地连接（桌面端）或计入失败锁定（云后端），
        // 而这两件对一个凭据本来就正确的用户都是错的。
        AccountException.Kind kind =
                ("phone_binding_required".equals(code) || "captcha_failed".equals(code)
                        || "too_many_requests".equals(code))
                        ? AccountException.Kind.CONFLICT
                        : AccountException.Kind.UNAUTHORIZED;
        throw new AccountException(kind, message);
    }

    /** 官网没给 message 时的兜底文案（按 error code 分，别一律「操作失败」）。 */
    /** 这几个 code 一律用本地双语文案，不用官网回的（那边是纯英文）。 */
    private static final java.util.Set<String> LOCALIZED_OVER_UPSTREAM =
            java.util.Set.of("captcha_failed", "too_many_requests");

    static String errorMessage(String code) {
        if (code == null) {
            return LangText.of("登录失败，请稍后重试", "Sign-in failed, please retry shortly");
        }
        return switch (code) {
            case "invalid_code" -> LangText.of("验证码错误或已过期", "Incorrect or expired verification code");
            case "invalid_credentials" -> LangText.of("账号或密码不正确", "Incorrect account or password");
            case "sms_not_supported_on_site" -> LangText.of("当前站点不支持手机号方式，请改用邮箱",
                    "This site does not support mobile numbers, please use email instead");
            case "sms_not_configured" -> LangText.of("短信服务暂不可用，请稍后重试",
                    "SMS is temporarily unavailable, please retry shortly");
            case "phone_binding_required" -> LangText.of(
                    "该账户尚未绑定手机号，且已超过绑定期限。请邮件联系 hi@aiworkdeck.com 处理",
                    "This account has no linked mobile number and the deadline has passed. Please email hi@aiworkdeck.com");
            case "captcha_failed" -> LangText.of("请先完成安全验证后再试",
                    "Please complete the security check and try again");
            case "too_many_requests" -> LangText.of("验证码请求过于频繁，请稍后再试",
                    "Too many verification code requests, please try again later");
            case "missing_fields" -> LangText.of("请填写完整", "Please fill in all fields");
            default -> LangText.of("登录失败，请稍后重试", "Sign-in failed, please retry shortly");
        };
    }

    static Map<String, Object> parse(ObjectMapper objectMapper, String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new AccountException(AccountException.Kind.MALFORMED,
                    LangText.of("官网返回的内容无法解析，请稍后重试",
                            "Could not parse the website's response, please retry shortly"));
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
