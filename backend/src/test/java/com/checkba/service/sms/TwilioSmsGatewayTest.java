package com.checkba.service.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TwilioSmsGatewayTest {

    private static TwilioSmsGateway gateway(SmsTransport transport) {
        return new TwilioSmsGateway(transport, true, "ACtest", "", "token123", "MGtest",
                "Your AI WorkDeck verification code is {code}. It expires in 5 minutes.");
    }

    @Test
    @DisplayName("只接境外号：+86 与裸号留给阿里云通道")
    void supportsOnlyInternational() {
        TwilioSmsGateway gw = gateway((u, b, a) -> null);
        assertTrue(gw.supports("+14155552671"));
        assertTrue(gw.supports("+442071838750"));
        assertFalse(gw.supports("+8613800000000"));
        assertFalse(gw.supports("13800000000"));
        assertFalse(gw.supports(null));
    }

    @Test
    @DisplayName("配置不齐即未启用（缺 SID/Token/MessagingServiceSid 任一）")
    void incompleteConfigMeansDisabled() {
        assertFalse(new TwilioSmsGateway((u, b, a) -> null, true, "", "", "t", "MG", "x").enabled());
        assertFalse(new TwilioSmsGateway((u, b, a) -> null, true, "AC", "", "", "MG", "x").enabled());
        assertFalse(new TwilioSmsGateway((u, b, a) -> null, true, "AC", "", "t", "", "x").enabled());
        assertFalse(new TwilioSmsGateway((u, b, a) -> null, false, "AC", "", "t", "MG", "x").enabled());
        assertTrue(new TwilioSmsGateway((u, b, a) -> null, true, "AC", "", "t", "MG", "x").enabled());
    }

    @Test
    @DisplayName("请求形态：Basic 认证、Messages.json 路径、模板占位替换")
    void sendsWithBasicAuthAndTemplate() {
        AtomicReference<String> url = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        TwilioSmsGateway gw = gateway((u, b, a) -> {
            url.set(u);
            body.set(b);
            auth.set(a);
            return new SmsTransport.Reply(201, "{\"sid\":\"SM1\",\"status\":\"queued\"}");
        });

        assertDoesNotThrow(() -> gw.sendVerificationCode("+14155552671", "246810"));

        assertEquals(TwilioSmsGateway.API_BASE + "/Accounts/ACtest/Messages.json", url.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                "ACtest:token123".getBytes(StandardCharsets.UTF_8)), auth.get());
        assertTrue(body.get().contains("To=%2B14155552671"), body.get());
        assertTrue(body.get().contains("MessagingServiceSid=MGtest"));
        assertTrue(body.get().contains("246810"), "模板占位应被替换为真码");
        assertFalse(body.get().contains("%7Bcode%7D"), "模板占位不得原样发出");
    }

    @Test
    @DisplayName("配了 API Key 时用 SK 做 Basic 用户名，URL 路径仍是 Account SID")
    void apiKeyAuthPrefersKeySid() {
        AtomicReference<String> url = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        TwilioSmsGateway gw = new TwilioSmsGateway((u, b, a) -> {
            url.set(u);
            auth.set(a);
            return new SmsTransport.Reply(201, "{\"sid\":\"SM1\"}");
        }, true, "ACtest", "SKtest", "keysecret", "MGtest", "code {code}");

        gw.sendVerificationCode("+14155552671", "123456");

        assertTrue(url.get().contains("/Accounts/ACtest/"), url.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                "SKtest:keysecret".getBytes(StandardCharsets.UTF_8)), auth.get());
    }

    @Test
    @DisplayName("Twilio 拒绝：文案通用不外露原始 message，也不含掉线三子串")
    void rejectionUsesSafeMessage() {
        TwilioSmsGateway gw = gateway((u, b, a) ->
                new SmsTransport.Reply(400, "{\"code\":21211,\"message\":\"Invalid 'To' Phone Number\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> gw.sendVerificationCode("+1999", "111111"));
        assertFalse(e.getMessage().contains("Invalid"));
        assertNoLogoutSubstring(e.getMessage());
    }

    @Test
    @DisplayName("目的地限流码给出可行动文案")
    void throttleCodeGetsActionableMessage() {
        TwilioSmsGateway gw = gateway((u, b, a) ->
                new SmsTransport.Reply(429, "{\"code\":21611,\"message\":\"queue full\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> gw.sendVerificationCode("+14155552671", "111111"));
        assertTrue(e.getMessage().contains("频繁"));
    }

    @Test
    @DisplayName("传输失败与未配置都抛业务错误，不炸栈")
    void failuresBecomeBusinessErrors() {
        TwilioSmsGateway down = gateway((u, b, a) -> new SmsTransport.Reply(-1, "timed out"));
        assertThrows(IllegalArgumentException.class, () -> down.sendVerificationCode("+14155552671", "1"));
        TwilioSmsGateway off = new TwilioSmsGateway((u, b, a) -> null, false, "", "", "", "", "x");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> off.sendVerificationCode("+14155552671", "1"));
        assertNoLogoutSubstring(e.getMessage());
    }

    private static void assertNoLogoutSubstring(String message) {
        assertFalse(message.contains("登录"), message);
        assertFalse(message.contains("未授权"), message);
        assertFalse(message.contains("请先"), message);
    }
}
