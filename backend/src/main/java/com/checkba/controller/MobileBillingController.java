// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.exception.UnauthorizedException;
import com.checkba.service.mobile.MobileBillingClient;
import com.checkba.service.mobile.MobileBillingService;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手机端统一账户余额与充值（dev-board#425，spec
 * {@code aiworkdeck_mobile/docs/specs/2026-09-04-mobile-recharge-design.md} §3.2）。
 *
 * <p>鉴权同 {@link MobileRelayController} 那一组：{@code X-Session-Id}，
 * 未登录走 {@link UnauthorizedException} → 200 + code 4010 信封。
 * 响应风格也随那一组：成功回<b>裸对象</b>（同 {@code /api/mobile/media/usage}），
 * 业务错误由全局处理器压成 200 + {@code {code:1,message}}。
 *
 * <p>第一期（dev-board#425）只有服务端通路；小程序虚拟支付（dev-board#427）在
 * {@link RechargeRequest} 上加了 channel/productId/wxCode 三个可选字段，
 * 缺省即第一期行为。iOS 内购 / 安卓微信支付仍是后面几期的事（dev-board#426/#428）。
 */
@RestController
@RequestMapping("/api/mobile/billing")
public class MobileBillingController {

    private final MobileBillingService service;

    public MobileBillingController(MobileBillingService service) {
        this.service = service;
    }

    /** GET /balance → {balanceCents, currency, plan}。 */
    @GetMapping("/balance")
    public Map<String, Object> balance(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        MobileBillingClient.BalanceResult r = service.balance(requireUser(sessionId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balanceCents", r.balanceCents());
        out.put("currency", r.currency());
        out.put("plan", r.plan());
        return out;
    }

    @Data
    public static class RechargeRequest {
        private Long amountCents;
        /**
         * 客户端生成并<b>落盘后</b>传入的幂等键。服务端刻意不代生成——代生成等于没有幂等键，
         * App 被杀/弱网重试会在官网留下一串悬挂 pending 单。缺失即 code:1 报错。
         */
        private String idempotencyKey;
        /**
         * 支付通道（dev-board#427/#426）。缺省 = 站点默认通道（第一期行为）；
         * {@code "wxvp"} = 小程序虚拟支付，此时 productId / wxCode 必填；
         * {@code "appstore"} = iOS 内购，此时只要 productId。
         */
        private String channel;
        /**
         * {@code channel=wxvp} / {@code appstore} 时必填：微信道具 id 或 App Store 商品 id
         * （后者带点号，如 credits.cny.50）。价格权威在官网，这里只做形态校验。
         */
        private String productId;
        /** {@code channel=wxvp} 时必填：{@code wx.login()} 的一次性 code，官网拿它换 openid。 */
        private String wxCode;
    }

    /**
     * POST /recharge → {present, outTradeNo, amountCents, codeUrl?, qrCode?, redirectUrl?,
     * signData?, paySig?, signature?}。
     */
    @PostMapping("/recharge")
    public Map<String, Object> recharge(
            @RequestBody(required = false) RechargeRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        MobileBillingClient.RechargeOrder order = service.createRecharge(userId,
                request == null ? null : request.getAmountCents(),
                request == null ? null : request.getIdempotencyKey(),
                request == null ? null : request.getChannel(),
                request == null ? null : request.getProductId(),
                request == null ? null : request.getWxCode());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", order.present());
        out.put("outTradeNo", order.outTradeNo());
        out.put("amountCents", order.amountCents());
        // 七个可选字段按 present 分组有值，为 null 时不出现在响应里（契约里也是非必填）：
        // qrcode → codeUrl/qrCode，redirect → redirectUrl，virtual → signData/paySig/signature，
        // native → appAccountToken
        putIfPresent(out, "codeUrl", order.codeUrl());
        putIfPresent(out, "qrCode", order.qrCode());
        putIfPresent(out, "redirectUrl", order.redirectUrl());
        putIfPresent(out, "signData", order.signData());
        putIfPresent(out, "paySig", order.paySig());
        putIfPresent(out, "signature", order.signature());
        putIfPresent(out, "appAccountToken", order.appAccountToken());
        return out;
    }

    @Data
    public static class RechargeConfirmRequest {
        /**
         * 可缺省：App 被杀后重放未完成交易时本地可能没存下单号，官网用交易里的
         * appAccountToken 反查 providerRef。
         */
        private String outTradeNo;
        /** 必填：StoreKit 2 的 {@code Transaction.jwsRepresentation} 原文。 */
        private String signedTransaction;
    }

    /**
     * POST /recharge/confirm → {status, paid, amountCents}（形状同 /recharge/status）。
     *
     * <p>iOS 内购（dev-board#426）：拿到 {@code .verified(tx)} 后调这里，服务端透传官网验签入账；
     * <b>客户端收到 paid 之前不许 {@code tx.finish()}</b>。
     */
    @PostMapping("/recharge/confirm")
    public Map<String, Object> rechargeConfirm(
            @RequestBody(required = false) RechargeConfirmRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        MobileBillingClient.RechargeStatus s = service.confirmAppstore(requireUser(sessionId),
                request == null ? null : request.getOutTradeNo(),
                request == null ? null : request.getSignedTransaction());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", s.status());
        out.put("paid", s.paid());
        out.put("amountCents", s.amountCents());
        return out;
    }

    /** GET /recharge/status?outTradeNo= → {status, paid, amountCents}。 */
    @GetMapping("/recharge/status")
    public Map<String, Object> rechargeStatus(
            @RequestParam(value = "outTradeNo", required = false) String outTradeNo,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        MobileBillingClient.RechargeStatus s = service.queryRecharge(requireUser(sessionId), outTradeNo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", s.status());
        out.put("paid", s.paid());
        out.put("amountCents", s.amountCents());
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isEmpty()) {
            out.put(key, value);
        }
    }

    private Long requireUser(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        return userId;
    }
}
