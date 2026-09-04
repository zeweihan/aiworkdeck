package com.checkba.service.mobile;

/**
 * 手机端「统一账户」余额与充值口（dev-board#425，spec
 * {@code aiworkdeck_mobile/docs/specs/2026-09-04-mobile-recharge-design.md} §3.2）。
 *
 * <p>余额权威只在官网仓（credit_lots + wallet_ledger），云后端没有、也不应该有任何
 * 能直连官网用户态的凭据。本接口打的是官网新增的窄权限内部记账口
 * {@code POST /api/internal/account}，形状与 {@link TransferBillingClient} 同源
 * （同机直连 + {@code X-Internal-Secret}），权限只有「按 accountId 读余额 / 建充值单 /
 * 查充值单」与「按已验证手机号或邮箱解析 accountId」四件事。
 *
 * <p><b>已否决</b>手机端自持官网 {@code awdk_} Bearer 直连的方案：那是该账户的无范围
 * 凭据，且官网 api_keys 满槽（3 把）淘汰最旧，手机端每次登录都会挤掉桌面端在用的那把。
 *
 * <p>接口存在的理由同 {@link TransferBillingClient}：给测试用桩，生产实现见
 * {@link HttpMobileBillingClient}。
 */
public interface MobileBillingClient {

    /**
     * 余额。
     *
     * @param balanceCents 整数分
     * @param currency     站点币种（CNY / USD），由部署站点决定
     * @param plan         计费档位。权威侧 {@code lib/wallet.ts} 的 {@code getPlan()}
     *                     只返回 {@code paid} / {@code free}，<b>不是套餐名，也基本不会为 null</b>
     *                     （只有上游没给这个字段时才是 null）。各端不要当套餐名直接渲染。
     */
    record BalanceResult(long balanceCents, String currency, String plan) {}

    /**
     * 充值单。{@code present} = {@code "qrcode"}（codeUrl/qrCode 有值）或
     * {@code "redirect"}（redirectUrl 有值）；不适用的字段为 null。
     */
    record RechargeOrder(String present, String outTradeNo, long amountCents,
                         String codeUrl, String qrCode, String redirectUrl) {}

    /** 充值单状态：status ∈ {pending, paid, closed, expired}。 */
    record RechargeStatus(String status, boolean paid, long amountCents) {}

    /**
     * 按<b>已验证</b>的手机号或邮箱解析官网 accountId（action=resolve）。
     *
     * <p>契约要求 phone / email <b>恰好给一个</b>，另一个传 null。
     *
     * <p><b>红线</b>（spec §3.2）：入参只能取自服务端会话对应的 User 实体，
     * <b>绝不接受请求体传入</b>——否则等于对外开了一个手机号枚举/任意建号的口子。
     * 调用点收敛在 {@link MobileBillingService#requireAccountId}。
     *
     * @param create <b>是否允许官网按这个身份建号</b>（复审 C1）。
     *               {@code false}（读余额、查单）时官网只查不建，查无此人回带 body 的 404
     *               → {@link MobileBillingKind#NOT_FOUND}。
     *               {@code true} 只允许出现在<b>用户显式发起充值</b>这一个动作上：
     *               「打开设置页」这种纯读动作若能在官网静默建出一行含明文手机号的真账户，
     *               就撞上 App Store 5.1.1(v)（App 内能创建的账号必须能在 App 内删除）
     *               与个人信息保护法的删除权——而 App 的注销流程只删 Java 侧，删不掉官网那行。
     */
    String resolveAccountId(String phone, String email, boolean create);

    /** 读余额（action=balance）。只读，网络失败不重试。 */
    BalanceResult balance(String accountId);

    /**
     * 建充值单（action=create-recharge）。
     *
     * <p>{@code idempotencyKey} 由<b>客户端</b>生成并落盘后传入（官网 orders 表有
     * {@code UNIQUE(userId, idempotencyKey)} 兜底），服务端不代生成——代生成等于没有幂等键，
     * 弱网重试会在官网库里留下一串各自绑定独立二维码的悬挂 pending 单。
     */
    RechargeOrder createRecharge(String accountId, long amountCents, String idempotencyKey);

    /** 查充值单（action=query）。 */
    RechargeStatus queryRecharge(String accountId, String outTradeNo);

    /**
     * 计费失败分类，翻成用户可读文案的活交给 {@link MobileBillingService}——本类只负责把
     * 「在用户眼里长得一样、下一步却完全不同」的几种情况分开，理由同
     * {@link TransferBillingClient.TransferBillingException}。
     *
     * <p>判别位用共享枚举 {@link MobileBillingKind}：它同时是信封里 {@code kind} 字段的
     * 取值集合，四端按它分支（复审 C2）。本类能产出的子集是
     * DISABLED / UNAVAILABLE / NOT_FOUND / REJECTED / ALREADY_PAID / IDEMPOTENCY_CONFLICT；
     * NOT_CONNECTED 与 REVIEW_ACCOUNT 是服务层的判定，不由 HTTP 层产生。
     */
    class MobileBillingException extends RuntimeException {

        private final MobileBillingKind kind;
        /** 官网回的 {@code error} 字段（机器可读串），只进日志不回显给用户。 */
        private final String machineError;
        /** 仅 ALREADY_PAID / IDEMPOTENCY_CONFLICT 有值：官网连同 409 一起回的既有单号。 */
        private final String outTradeNo;

        public MobileBillingException(MobileBillingKind kind, String message) {
            this(kind, message, null, null);
        }

        public MobileBillingException(MobileBillingKind kind, String message, String machineError) {
            this(kind, message, machineError, null);
        }

        public MobileBillingException(MobileBillingKind kind, String message,
                                      String machineError, String outTradeNo) {
            super(message);
            this.kind = kind;
            this.machineError = machineError;
            this.outTradeNo = outTradeNo;
        }

        public MobileBillingKind getKind() {
            return kind;
        }

        public String getMachineError() {
            return machineError;
        }

        public String getOutTradeNo() {
            return outTradeNo;
        }
    }
}
