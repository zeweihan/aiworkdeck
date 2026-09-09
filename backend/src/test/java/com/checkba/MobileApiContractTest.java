// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.checkba.model.entity.AccountBinding;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.service.ai.tools.WebTools;
import com.checkba.service.mobile.MobileBillingClient;
import com.checkba.service.mobile.MobileBillingKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 契约测试：手机端调用的各端点，真实响应必须合 src/main/resources/openapi/mobile-v1.yaml。
 * 只校验响应（请求级别 IGNORE，multipart 请求校验在该库里不稳）。
 * 环境配方同 MobileRelayEndpointIntegrationTest。移动仓 contract/api/ 钉的是这份 YAML 的副本。
 * <p>校验器（swagger-request-validator）默认 additionalProperties: false：mobile-v1.yaml 是响应字段的
 * 穷举白名单，服务端新增任何响应字段都必须先写进那份 YAML，否则本测试红。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mobile-api-contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false",
        "storage.local.root-path=${java.io.tmpdir}/mobile-api-contract-store",
        // 充值总开关生产默认是关（复审 N1，等 dev-board#434 才允许开）；这里显式打开，
        // 否则 /recharge 与 /recharge/status 的成功响应形状就没有任何地方对着 YAML 校验了。
        // 关着时的响应是 Envelope(code:1,kind:DISABLED)，形状与下面已经校过的 UNAVAILABLE 一模一样，
        // 开关本身的行为由 MobileBillingRechargeDisabledTest 守。
        "mobile.billing.recharge-enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("desktop")
class MobileApiContractTest {

    private static OpenApiInteractionValidator validator;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private AccountBindingRepository accountBindingRepository;
    @MockBean private WebTools webTools;
    // 统一账户余额/充值（dev-board#425）的上游是官网内部记账口，契约测试只关心响应形状，
    // 用桩给出三个成功形状即可，不打网络
    @MockBean private MobileBillingClient billing;

    @BeforeAll
    static void loadSpec() throws Exception {
        String spec = new String(
                MobileApiContractTest.class.getResourceAsStream("/openapi/mobile-v1.yaml").readAllBytes(),
                StandardCharsets.UTF_8);
        validator = OpenApiInteractionValidator.createForInlineApiSpecification(spec)
                .withLevelResolver(LevelResolver.create()
                        .withLevel("validation.request", ValidationReport.Level.IGNORE)
                        .build())
                .build();
    }

    private String register(String username) throws Exception {
        String body = om.writeValueAsString(Map.of(
                "username", username, "password", "pw123456", "displayName", username));
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = om.readTree(r.getResponse().getContentAsString());
        String sid = json.path("data").path("sessionId").asText();
        assertFalse(sid.isEmpty(), "注册应返回 sessionId：" + r.getResponse().getContentAsString());
        return sid;
    }

    @Test
    void handsetEndpointsMatchSpec() throws Exception {
        String sid = register("contract_" + System.nanoTime());
        String mediaId = "0a1b2c3d-2222-4333-8444-555566667777";

        // 未登录信封也在契约里
        mvc.perform(get("/api/mobile/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4010))
                .andExpect(openApi().isValid(validator));

        // 桌面推目录，让 GET /projects 有内容
        mvc.perform(put("/api/mobile/projects").header("X-Session-Id", sid)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"deviceId":"dev-c","deviceName":"Mac","projects":[{"key":"1","name":"契约项目"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/api/mobile/projects").header("X-Session-Id", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("1"))
                .andExpect(openApi().isValid(validator));

        MockMultipartFile file = new MockMultipartFile(
                "file", "现场影像-20260902-170000-0a1b.jpg", "application/octet-stream", "JPEG".getBytes());
        mvc.perform(multipart("/api/mobile/media").file(file)
                        .param("deviceId", "dev-c").param("projectKey", "1")
                        .param("clientMediaId", mediaId)
                        .param("fileName", "现场影像-20260902-170000-0a1b.jpg")
                        .param("mediaType", "image")
                        .param("capturedAt", "2026-09-02T17:00:00Z")
                        .header("X-Session-Id", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(openApi().isValid(validator));

        mvc.perform(get("/api/mobile/media/status").param("clientMediaIds", mediaId)
                        .header("X-Session-Id", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientMediaId").value(mediaId))
                .andExpect(openApi().isValid(validator));

        mvc.perform(get("/api/mobile/media/usage").header("X-Session-Id", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaBytes").isNumber())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void billingEndpointsMatchSpec() throws Exception {
        String sid = register("contract_billing_" + System.nanoTime());
        Long userId = om.readTree(mvc.perform(get("/api/auth/me").header("X-Session-Id", sid))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 未登录信封（这一组也走 4010）
        mvc.perform(get("/api/mobile/billing/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4010))
                .andExpect(openApi().isValid(validator));

        // 已桥接用户：直接放一行绑定，绕开 resolve（resolve 的红线在 MobileBillingServiceTest 里护）
        AccountBinding binding = new AccountBinding();
        binding.setUserId(userId);
        binding.setExternalAccountId("acct-contract-" + userId);
        binding.setCreatedAt(LocalDateTime.now());
        accountBindingRepository.save(binding);

        when(billing.balance(anyString()))
                .thenReturn(new MobileBillingClient.BalanceResult(12345L, "CNY", "paid"));
        when(billing.createRecharge(anyString(), anyLong(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(new MobileBillingClient.RechargeOrder(
                        "qrcode", "OT-contract-1", 5000L, "weixin://wxpay/bizpayurl?pr=x", null, null,
                        null, null, null));
        // 小程序虚拟支付（dev-board#427）：present=virtual 时多出 signData/paySig/signature 三个键，
        // 它们必须先写进 YAML——校验器默认 additionalProperties:false，漏一个这里就红
        when(billing.createRecharge(anyString(), anyLong(), anyString(),
                eq("wxvp"), anyString(), anyString()))
                .thenReturn(new MobileBillingClient.RechargeOrder(
                        "virtual", "OT-contract-vp", 1000L, null, null, null,
                        "{\"offerId\":\"1450637533\",\"buyQuantity\":1}", "a1b2c3", "d4e5f6"));
        when(billing.queryRecharge(anyString(), anyString()))
                .thenReturn(new MobileBillingClient.RechargeStatus("pending", false, 5000L));

        mvc.perform(get("/api/mobile/billing/balance").header("X-Session-Id", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceCents").value(12345))
                .andExpect(openApi().isValid(validator));

        mvc.perform(post("/api/mobile/billing/recharge").header("X-Session-Id", sid)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amountCents":5000,"idempotencyKey":"idem-contract-0001"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outTradeNo").value("OT-contract-1"))
                .andExpect(openApi().isValid(validator));

        mvc.perform(get("/api/mobile/billing/recharge/status")
                        .param("outTradeNo", "OT-contract-1").header("X-Session-Id", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(openApi().isValid(validator));

        // present=virtual 的成功形状（dev-board#427）：三个签名串出现在响应里，
        // 扫码那三兄弟一个都不出现（不适用的字段不进响应，与 present=qrcode 时正好相反）
        mvc.perform(post("/api/mobile/billing/recharge").header("X-Session-Id", sid)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amountCents":1000,"idempotencyKey":"idem-contract-vp01",\
                                "channel":"wxvp","productId":"credits_cny_10","wxCode":"0a1b2c3d4e"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value("virtual"))
                .andExpect(jsonPath("$.outTradeNo").value("OT-contract-vp"))
                .andExpect(jsonPath("$.signData").exists())
                .andExpect(jsonPath("$.paySig").value("a1b2c3"))
                .andExpect(jsonPath("$.signature").value("d4e5f6"))
                .andExpect(jsonPath("$.codeUrl").doesNotExist())
                .andExpect(openApi().isValid(validator));

        // 新请求字段的校验也走通用信封：不认识的通道 → code 1（无 kind，通用 handler）
        mvc.perform(post("/api/mobile/billing/recharge").header("X-Session-Id", sid)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amountCents":1000,"idempotencyKey":"idem-contract-vp02",\
                                "channel":"alipay","productId":"credits_cny_10","wxCode":"0a1b2c3d4e"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.kind").doesNotExist())
                .andExpect(openApi().isValid(validator));

        // channel=wxvp 但缺 productId / wxCode → 同样 code 1，绝不当成默认通道悄悄下单
        for (String bad : new String[]{
                "{\"amountCents\":1000,\"idempotencyKey\":\"idem-contract-vp03\",\"channel\":\"wxvp\"}",
                "{\"amountCents\":1000,\"idempotencyKey\":\"idem-contract-vp04\",\"channel\":\"wxvp\","
                        + "\"productId\":\"credits_cny_10\"}"}) {
            mvc.perform(post("/api/mobile/billing/recharge").header("X-Session-Id", sid)
                            .contentType(APPLICATION_JSON).content(bad))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andExpect(openApi().isValid(validator));
        }

        // 业务错误也在契约里：缺 idempotencyKey → 200 + Envelope(code 1)
        mvc.perform(post("/api/mobile/billing/recharge").header("X-Session-Id", sid)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amountCents":5000}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(openApi().isValid(validator));

        // 机器可读判别位（dev-board#425 复审 C2）：四端按 kind 分支，不许猜 message 措辞
        doThrow(new MobileBillingClient.MobileBillingException(
                MobileBillingKind.UNAVAILABLE, "账户服务暂不可用，请稍后再试"))
                .when(billing).createRecharge(anyString(), anyLong(), eq("idem-contract-down1"),
                        any(), any(), any());
        mvc.perform(post("/api/mobile/billing/recharge").header("X-Session-Id", sid)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amountCents":5000,"idempotencyKey":"idem-contract-down1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.kind").value("UNAVAILABLE"))
                .andExpect(openApi().isValid(validator));

        // 已付单的 409 要连 outTradeNo 一起进信封（复审 C4）：App 被杀后靠它转去查单
        doThrow(new MobileBillingClient.MobileBillingException(
                MobileBillingKind.ALREADY_PAID, "这笔充值已经支付成功，请查看订单状态",
                "order_already_paid", "RECHARGE20260904X"))
                .when(billing).createRecharge(anyString(), anyLong(), eq("idem-contract-paid1"),
                        any(), any(), any());
        mvc.perform(post("/api/mobile/billing/recharge").header("X-Session-Id", sid)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amountCents":5000,"idempotencyKey":"idem-contract-paid1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.kind").value("ALREADY_PAID"))
                .andExpect(jsonPath("$.outTradeNo").value("RECHARGE20260904X"))
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void wxPhoneLoginEnvelopesMatchSpec() throws Exception {
        // 成功：与 sms-login/verify 逐字同形（sessionId + isNewUser + user），data 受 LoginData 约束。
        // 号码用一个本测试专属的段，避免与别的用例抢同一行 users
        when(billing.wxPhone("wx-code-ok")).thenReturn("13800000123");
        mvc.perform(post("/api/auth/wx-phone-login").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"wx-code-ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.data.isNewUser").value(true))
                .andExpect(openApi().isValid(validator));

        // 未开通（本机 mobile.billing.* 没配 / 官网没配小程序 AppSecret）：code 1、无 kind，
        // message 指路短信登录。客户端不按 kind 分支，所以这里也不许多出一个 kind 字段。
        when(billing.wxPhone("wx-code-off")).thenThrow(new MobileBillingClient.MobileBillingException(
                MobileBillingKind.DISABLED, "本服务器未开通微信一键登录，请用短信验证码登录"));
        mvc.perform(post("/api/auth/wx-phone-login").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"wx-code-off\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.kind").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("本服务器未开通微信一键登录，请用短信验证码登录"))
                .andExpect(openApi().isValid(validator));

        // 官网 401 invalid_wx_code：同一个 code 1 信封，只是换一句话
        when(billing.wxPhone("wx-code-expired")).thenThrow(new MobileBillingClient.MobileBillingException(
                MobileBillingKind.REJECTED, "微信授权已过期，请重试", "invalid_wx_code"));
        mvc.perform(post("/api/auth/wx-phone-login").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"wx-code-expired\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.kind").doesNotExist())
                .andExpect(jsonPath("$.message").value("微信授权已过期，请重试"))
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void smsLoginEnvelopesMatchSpec() throws Exception {
        // 不真发短信：非法号码走 code 1 分支，信封形状仍受契约约束
        mvc.perform(post("/api/auth/sms-login/send-code").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"123\"}"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));

        mvc.perform(post("/api/auth/sms-login/verify").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000000\",\"code\":\"000000\"}"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));
    }
}
