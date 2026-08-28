package com.checkba.service.mobile;

/**
 * 跨设备传输计费口（dev-board#251，spec 2.4）。
 *
 * <p>云后端刻意不落 awdk_ 明文，对已桥接用户没有任何可打 {@code /api/gateway/*} 的
 * Bearer——本接口打的是官网新增的窄权限内部记账口（同机服务器间，权限只有「按
 * accountId 对 transfer 计价并扣/退 service_spend」一件事），与平台网关是两条不同的路。
 *
 * <p>接口存在的理由同 {@link MobileRelayBlobStore}：给测试用桩，生产实现见
 * {@link HttpTransferBillingClient}。
 */
public interface TransferBillingClient {

    /** 报价：quote 只读不扣费。balanceCents 可能为 null（官网侧未回）。 */
    record QuoteResult(int credits, Long balanceCents) {}

    /** 扣费结果：credits 是实际扣的数额，ledgerId 是官网流水 id，退款要用。 */
    record ChargeResult(int credits, String ledgerId) {}

    QuoteResult quote(String accountId, long bytes);

    /**
     * 扣费。idempotencyKey 约定 {@code xfer-<requestId>}——同一 requestId 重复调用
     * 必须只扣一次（官网侧幂等兜底），调用方因此可以放心地在撞约束重试时原样再调一次。
     */
    ChargeResult charge(String accountId, long bytes, String idempotencyKey, String refId);

    /**
     * 退款。idempotencyKey 约定 {@code xferrf-<requestId>}。已退过的 ledgerId 幂等返回
     * 成功，不重复退。调用方对失败的处理是 log.error 不回滚业务状态，由 TTL 清扫兜底重试。
     */
    void refund(String accountId, String ledgerId, String idempotencyKey);

    /**
     * 计费失败分类，全部翻成用户可读文案的活交给 {@link MobileTransferService}——本类只
     * 负责把三种「在用户眼里长得一样、下一步却完全不同」的情况分开，理由与
     * {@code com.checkba.service.platform.GatewayException} 完全一致。
     */
    class TransferBillingException extends RuntimeException {

        public enum Kind {
            /** 网络/上游 5xx 等不可预期故障，绝不能当成余额不足免费放行。 */
            UNAVAILABLE,
            /** 账户 Credits 不足。 */
            NO_CREDITS,
            /** base-url 未配置——本服务器根本没开通跨设备传输计费。 */
            DISABLED
        }

        private final Kind kind;
        /** 仅 NO_CREDITS 有意义：本次传输所需的 Credits。 */
        private final Integer requiredCredits;
        /** 仅 NO_CREDITS 有意义：账户当前余额（分）。 */
        private final Long availableCents;

        public TransferBillingException(Kind kind, String message) {
            this(kind, message, null, null);
        }

        public TransferBillingException(Kind kind, String message, Integer requiredCredits, Long availableCents) {
            super(message);
            this.kind = kind;
            this.requiredCredits = requiredCredits;
            this.availableCents = availableCents;
        }

        public Kind getKind() {
            return kind;
        }

        public Integer getRequiredCredits() {
            return requiredCredits;
        }

        public Long getAvailableCents() {
            return availableCents;
        }
    }
}
