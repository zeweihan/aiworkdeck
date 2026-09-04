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
 * <p><b>本期只有服务端通路，没有任何客户端支付界面</b>：iOS 内购 / 小程序虚拟支付 /
 * 安卓微信支付是后面几期的事（dev-board#426/#427/#428）。
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
    }

    /** POST /recharge → {present, outTradeNo, amountCents, codeUrl?, qrCode?, redirectUrl?}。 */
    @PostMapping("/recharge")
    public Map<String, Object> recharge(
            @RequestBody(required = false) RechargeRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        MobileBillingClient.RechargeOrder order = service.createRecharge(userId,
                request == null ? null : request.getAmountCents(),
                request == null ? null : request.getIdempotencyKey());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", order.present());
        out.put("outTradeNo", order.outTradeNo());
        out.put("amountCents", order.amountCents());
        // 三个可选字段按 present 二选一有值，为 null 时不出现在响应里（契约里也是非必填）
        putIfPresent(out, "codeUrl", order.codeUrl());
        putIfPresent(out, "qrCode", order.qrCode());
        putIfPresent(out, "redirectUrl", order.redirectUrl());
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
