package com.checkba.service.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 境外号码的短信通道：Twilio Messages API（不引 SDK，同 {@link SmsService} 的立场）。
 *
 * <p>只负责非大陆号码。发送内容由本服务拼装（验证码仍由
 * {@link com.checkba.service.auth.VerificationCodeStore} 统一签发与核销），
 * 这样国内外只有一套验证码生命周期，不必为 Twilio Verify 再维护一条远端校验路径。
 *
 * <p>合规（各国 Sender ID / 美国 10DLC / 印度 DLT）在 Twilio 控制台的 Messaging Service
 * 里配置，代码侧只认一个 {@code messaging-service-sid}——**这正是选 Messaging Service 而不是
 * 裸 from 号码的原因**：换国家、加 Sender ID 都不需要改代码发版。
 */
@Service
@Slf4j
public class TwilioSmsGateway implements SmsGateway {

    static final String API_BASE = "https://api.twilio.com/2010-04-01";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SmsTransport transport;
    private final boolean enabled;
    private final String accountSid;
    private final String apiKeySid;
    private final String authToken;
    private final String messagingServiceSid;
    private final String template;

    @Autowired
    public TwilioSmsGateway(SmsTransport transport,
                            @Value("${sms.intl.enabled:false}") boolean enabled,
                            @Value("${sms.intl.account-sid:}") String accountSid,
                            @Value("${sms.intl.api-key-sid:}") String apiKeySid,
                            @Value("${sms.intl.auth-token:}") String authToken,
                            @Value("${sms.intl.messaging-service-sid:}") String messagingServiceSid,
                            @Value("${sms.intl.template:Your AI WorkDeck verification code is {code}. It expires in 5 minutes.}")
                            String template) {
        this.transport = transport;
        this.enabled = enabled;
        this.accountSid = accountSid;
        this.apiKeySid = apiKeySid;
        this.authToken = authToken;
        this.messagingServiceSid = messagingServiceSid;
        this.template = template;
    }

    /**
     * Basic 认证的用户名：配了 API Key（SK 开头）就用它，否则回落账号自身的 Auth Token 认证。
     * **推荐配 API Key**——可单独吊销，泄露时不必换掉整个账号的 Auth Token。
     * 无论哪种，URL 路径里的账号标识始终是 Account SID（AC 开头）。
     */
    private String basicUser() {
        return StringUtils.hasText(apiKeySid) ? apiKeySid : accountSid;
    }

    @Override
    public boolean enabled() {
        return enabled
                && StringUtils.hasText(accountSid)
                && StringUtils.hasText(authToken)
                && StringUtils.hasText(messagingServiceSid);
    }

    /** 境外号：规范化后带 + 前缀且不是 +86。大陆号一律留给阿里云通道。 */
    @Override
    public boolean supports(String phone) {
        return phone != null && phone.startsWith("+") && !phone.startsWith("+86");
    }

    @Override
    public void sendVerificationCode(String phone, String code) {
        if (!enabled()) {
            throw new IllegalArgumentException("国际短信通道未配置");
        }
        String body = "To=" + enc(phone)
                + "&MessagingServiceSid=" + enc(messagingServiceSid)
                + "&Body=" + enc(template.replace("{code}", code));
        String url = API_BASE + "/Accounts/" + enc(accountSid) + "/Messages.json";
        String basic = Base64.getEncoder().encodeToString(
                (basicUser() + ":" + authToken).getBytes(StandardCharsets.UTF_8));

        SmsTransport.Reply reply = transport.postForm(url, body, "Basic " + basic);
        // Twilio 成功回 201；失败体形如 {"code":21211,"message":"Invalid 'To' Phone Number"}
        if (reply.status() == 200 || reply.status() == 201) {
            return;
        }
        String twilioCode = "";
        try {
            JsonNode node = MAPPER.readTree(reply.body());
            twilioCode = node.path("code").asText("");
            log.warn("Twilio 短信发送失败: http={} code={} message={}",
                    reply.status(), twilioCode, node.path("message").asText(""));
        } catch (Exception e) {
            log.warn("Twilio 响应解析失败: http={} body={}", reply.status(), abbreviate(reply.body()));
        }
        // 21611 = 目的地被限流/超出配额；其余一律通用文案（Twilio 原始文案不外露）
        if ("21611".equals(twilioCode)) {
            throw new IllegalArgumentException("短信发送过于频繁，请稍后再试");
        }
        throw new IllegalArgumentException("短信发送失败，请稍后重试");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
