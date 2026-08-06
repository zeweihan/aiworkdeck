package com.checkba.service.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SmsServiceTest {

    private static SmsService service(SmsTransport transport) {
        return new SmsService(transport, true, "testAk", "testSecret", "京微资易", "SMS_483655011");
    }

    @Test
    @DisplayName("签名对拍：与线上实弹验证过的参考实现逐字节一致")
    void signatureMatchesReferenceVector() {
        Map<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", "testAk");
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("PhoneNumbers", "13800000000");
        params.put("RegionId", "cn-hangzhou");
        params.put("SignName", "京微资易");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", "fixed-nonce");
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", "SMS_483655011");
        params.put("TemplateParam", "{\"code\":\"123456\"}");
        params.put("Timestamp", "2026-08-06T08:00:00Z");
        params.put("Version", "2017-05-25");

        String form = SmsService.signedForm(params, "testSecret");
        // 参考签名由已在阿里云真机发送成功的探测脚本对同一输入计算得出
        assertTrue(form.startsWith("Signature=" + SmsService.pctEncode("W4Fg6bZtlyxPvAMi3Leco/yBEBQ=") + "&"),
                "签名不匹配: " + form.substring(0, Math.min(80, form.length())));
        assertTrue(form.contains("PhoneNumbers=13800000000"));
        assertTrue(form.contains("SignName=%E4%BA%AC%E5%BE%AE%E8%B5%84%E6%98%93"));
    }

    @Test
    @DisplayName("百分号编码：修正 URLEncoder 的表单编码三处差异")
    void pctEncodeIsRfc3986() {
        assertEquals("a%20b", SmsService.pctEncode("a b"));
        assertEquals("%2A", SmsService.pctEncode("*"));
        assertEquals("~", SmsService.pctEncode("~"));
        assertEquals("%3D%26", SmsService.pctEncode("=&"));
    }

    @Test
    @DisplayName("网关 OK 正常返回；请求体带齐手机号与模板参数")
    void sendsThroughTransport() {
        AtomicReference<String> captured = new AtomicReference<>();
        SmsService svc = service((url, body) -> {
            captured.set(body);
            assertEquals(SmsService.ENDPOINT, url);
            return new SmsTransport.Reply(200, "{\"Code\":\"OK\",\"BizId\":\"1\"}");
        });
        assertDoesNotThrow(() -> svc.sendVerificationCode("13800000000", "654321"));
        assertTrue(captured.get().contains("PhoneNumbers=13800000000"));
        assertTrue(captured.get().contains("Signature="));
        assertTrue(captured.get().contains(SmsService.pctEncode("{\"code\":\"654321\"}")));
    }

    @Test
    @DisplayName("运营商拒绝：文案通用且不外露阿里云原始 Message，也不含掉线三子串")
    void carrierRejectionUsesSafeMessage() {
        SmsService svc = service((url, body) ->
                new SmsTransport.Reply(200, "{\"Code\":\"isv.MOBILE_NUMBER_ILLEGAL\",\"Message\":\"raw aliyun text\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.sendVerificationCode("13800000000", "111111"));
        assertFalse(e.getMessage().contains("raw aliyun"));
        assertNoLogoutSubstring(e.getMessage());
    }

    @Test
    @DisplayName("限流码给出可行动文案")
    void throttleCodeGetsActionableMessage() {
        SmsService svc = service((url, body) ->
                new SmsTransport.Reply(200, "{\"Code\":\"isv.BUSINESS_LIMIT_CONTROL\"}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.sendVerificationCode("13800000000", "111111"));
        assertTrue(e.getMessage().contains("频繁"));
        assertNoLogoutSubstring(e.getMessage());
    }

    @Test
    @DisplayName("传输层失败（超时/断网）不炸栈，抛业务错误")
    void transportFailureBecomesBusinessError() {
        SmsService svc = service((url, body) -> new SmsTransport.Reply(-1, "timed out"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.sendVerificationCode("13800000000", "111111"));
        assertNoLogoutSubstring(e.getMessage());
    }

    @Test
    @DisplayName("配置不齐即未启用（缺 AK/SK/签名/模板任一）")
    void incompleteConfigMeansDisabled() {
        assertFalse(new SmsService((u, b) -> null, true, "", "s", "sign", "tpl").enabled());
        assertFalse(new SmsService((u, b) -> null, true, "ak", "", "sign", "tpl").enabled());
        assertFalse(new SmsService((u, b) -> null, true, "ak", "s", "", "tpl").enabled());
        assertFalse(new SmsService((u, b) -> null, true, "ak", "s", "sign", "").enabled());
        assertFalse(new SmsService((u, b) -> null, false, "ak", "s", "sign", "tpl").enabled());
        assertTrue(new SmsService((u, b) -> null, true, "ak", "s", "sign", "tpl").enabled());
    }

    /** licensing 领域地雷 1：这些子串会被前端当成掉线清会话。 */
    private static void assertNoLogoutSubstring(String message) {
        assertFalse(message.contains("登录"), message);
        assertFalse(message.contains("未授权"), message);
        assertFalse(message.contains("请先"), message);
    }
}
