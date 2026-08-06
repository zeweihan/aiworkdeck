package com.checkba.service.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云短信（dysmsapi）验证码发送。
 *
 * <p>刻意不引入阿里云 SDK：RPC 签名（HMAC-SHA1）几十行可覆盖，换依赖不值得
 * 破坏补丁通道资格（patch-gate 见 pom 变更即拒，增量更新契约）。签名算法与
 * 线上探测脚本对拍验证过（2026-08-06 真机发送成功受理）。
 *
 * <p>失败文案红线：不得含「登录」「未授权」「请先」子串（licensing 领域地雷 1，
 * 前端 api.js 会把命中的 code=1 message 当掉线清会话）。阿里云返回的原始 Message
 * 只进日志，不进给用户的文案。
 */
@Service
@Slf4j
public class SmsService {

    static final String ENDPOINT = "https://dysmsapi.aliyuncs.com/";
    static final String API_VERSION = "2017-05-25";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter RPC_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final SmsTransport transport;
    private final boolean enabled;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String signName;
    private final String templateCode;

    @Autowired
    public SmsService(SmsTransport transport,
                      @Value("${sms.enabled:false}") boolean enabled,
                      @Value("${sms.access-key-id:}") String accessKeyId,
                      @Value("${sms.access-key-secret:}") String accessKeySecret,
                      @Value("${sms.sign-name:}") String signName,
                      @Value("${sms.template-code:}") String templateCode) {
        this.transport = transport;
        this.enabled = enabled;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.signName = signName;
        this.templateCode = templateCode;
    }

    /** 配置齐全才算启用：开关 + AK/SK + 签名 + 模板缺一不可。 */
    public boolean enabled() {
        return enabled
                && StringUtils.hasText(accessKeyId)
                && StringUtils.hasText(accessKeySecret)
                && StringUtils.hasText(signName)
                && StringUtils.hasText(templateCode);
    }

    /** 发送验证码短信；网关或运营商拒绝时抛业务错误（文案见类注释红线）。 */
    public void sendVerificationCode(String phone, String code) {
        if (!enabled()) {
            throw new IllegalArgumentException("短信服务未配置");
        }
        String templateParam;
        try {
            templateParam = MAPPER.writeValueAsString(Map.of("code", code));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e); // Map.of 序列化不会失败
        }
        Map<String, String> params = new TreeMap<>();
        params.put("Action", "SendSms");
        params.put("Version", API_VERSION);
        params.put("Format", "JSON");
        params.put("RegionId", "cn-hangzhou");
        params.put("AccessKeyId", accessKeyId);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("Timestamp", RPC_TIMESTAMP.format(Instant.now()));
        params.put("PhoneNumbers", phone);
        params.put("SignName", signName);
        params.put("TemplateCode", templateCode);
        params.put("TemplateParam", templateParam);

        String body = signedForm(params, accessKeySecret);
        SmsTransport.Reply reply = transport.postForm(ENDPOINT, body);
        if (reply.status() != 200) {
            log.warn("短信网关异常: status={} body={}", reply.status(), abbreviate(reply.body()));
            throw new IllegalArgumentException("短信发送失败，请稍后重试");
        }
        String resultCode;
        try {
            JsonNode node = MAPPER.readTree(reply.body());
            resultCode = node.path("Code").asText("");
            if (!"OK".equals(resultCode)) {
                log.warn("短信发送被拒: code={} message={}", resultCode, node.path("Message").asText(""));
            }
        } catch (Exception e) {
            log.warn("短信响应解析失败: {}", abbreviate(reply.body()));
            throw new IllegalArgumentException("短信发送失败，请稍后重试");
        }
        if ("OK".equals(resultCode)) {
            return;
        }
        // 限流类给出可行动文案，其余一律通用文案（阿里云原始 Message 不外露）
        if ("isv.BUSINESS_LIMIT_CONTROL".equals(resultCode)) {
            throw new IllegalArgumentException("短信发送过于频繁，请稍后再试");
        }
        throw new IllegalArgumentException("短信发送失败，请稍后重试");
    }

    /**
     * 按阿里云 RPC 风格签名并编码为表单体（POST）。纯函数，供单测对拍。
     *
     * @return {@code Signature=...&K1=V1&...}（键按字典序，RFC3986 百分号编码）
     */
    static String signedForm(Map<String, String> sortedParams, String secret) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> e : sortedParams.entrySet()) {
            if (canonical.length() > 0) canonical.append('&');
            canonical.append(pctEncode(e.getKey())).append('=').append(pctEncode(e.getValue()));
        }
        String toSign = "POST&%2F&" + pctEncode(canonical.toString());
        String signature;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            signature = Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 不可用", e);
        }
        return "Signature=" + pctEncode(signature) + "&" + canonical;
    }

    /** RFC3986 百分号编码（URLEncoder 是表单编码，三处差异必须修正，与阿里云签名规范一致）。 */
    static String pctEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
