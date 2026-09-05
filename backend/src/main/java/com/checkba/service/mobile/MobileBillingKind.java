package com.checkba.service.mobile;

/**
 * 统一账户余额/充值失败的<b>机器可读判别位</b>（dev-board#425 复审 C2）。
 *
 * <p>存在的理由是一条踩出来的教训：这一族失败在用户眼里长得都一样（都是一句红字），
 * 下一步却完全不同——「本服务器没开通」要收起入口，「官网挂了」要让用户稍后重试，
 * 「还没有统一账户」要连余额那一行都不渲染，「这单已经付过了」要带着单号转去查单。
 * 第一版只把 message 送到客户端，于是三端各自去猜：安卓逐字硬编码中文 message 做分支
 * （服务端 {@link com.checkba.service.LangText} 在英文部署下会把同一条消息变成英文，
 * 匹配全部落空），小程序判 {@code code === -1}（云后端业务失败一律 200 + code:1，
 * 那个分支永远进不去）。
 *
 * <p>所以：<b>枚举名是四端共享的契约</b>，写在
 * {@code openapi/mobile-v1.yaml} 的 {@code Envelope.kind} 里，
 * 各端<b>一律按它分支，禁止匹配 message 措辞</b>。message 只用于兜底展示。
 * 改名/新增值 = 契约变更，必须同步 yaml 与移动仓 {@code contract/fixtures/billing.json}。
 */
public enum MobileBillingKind {

    /** 本服务器未配 {@code mobile.billing.base-url/secret}，压根没开通。客户端应收起充值入口。 */
    DISABLED,

    /**
     * 上游不可用：网络失败、官网 5xx、响应解析失败，以及<b>官网回的空体 404</b>
     * （官网对「env 未配置 / secret 不符」刻意回空体 404 而不是 401/403，见 C3）。
     * <b>绝不能被吞成「没有账户」或「余额 0」</b>（mobile-sync.md 红线 7）。
     */
    UNAVAILABLE,

    /**
     * 这个登录账号还没有统一账户：没有已验证的手机号/邮箱，或官网按该身份查无账户
     * （读余额一律 {@code create=false}，不建号）。客户端<b>不渲染余额那一行</b>。
     */
    NOT_CONNECTED,

    /**
     * 官网明确回「查无此物」的带 body 404：accountId 已注销、或单号不属于该账户。
     * 与 {@link #UNAVAILABLE} 的空体 404 是两件事，判据是响应体里有没有 {@code error} 字段。
     */
    NOT_FOUND,

    /** 官网明确拒绝的业务错误（非 404/409 的 4xx），或本地判定的拒绝（如统一账户已绑给别人）。 */
    REJECTED,

    /** App 审核专用账号（{@link com.checkba.config.ReviewAccountGate}）：不桥接、不充值。 */
    REVIEW_ACCOUNT,

    /**
     * 同一把幂等键的单子已支付（官网 409 {@code order_already_paid}）。
     * 信封里带 {@code outTradeNo}：App 被杀后没存下单号时，靠它转去查单而不是重新下单。
     */
    ALREADY_PAID,

    /**
     * 同一把幂等键换了金额/类型（官网 409 {@code idempotency_conflict}）。
     * 同样带 {@code outTradeNo}。
     */
    IDEMPOTENCY_CONFLICT
}
