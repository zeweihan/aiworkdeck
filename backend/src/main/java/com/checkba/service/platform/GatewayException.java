package com.checkba.service.platform;

/**
 * 平台服务网关的失败分类。
 *
 * <p>刻意与 {@link com.checkba.service.account.AccountException} 分开，而不是往它的 Kind 里塞：
 * 那个类的 Kind 只有 NETWORK / UNAUTHORIZED / CONFLICT / NOT_CONNECTED / MALFORMED 五档，
 * 5xx 一律归 NETWORK、文案是「无法连接 AI WorkDeck 服务器，请检查网络后重试」——
 * <b>把我们自己的故障说成用户的网络问题</b>，用户会去重启路由器。
 *
 * <p>网关必须能区分三件在用户眼里长得一样、下一步却完全不同的事：
 * 「这项服务我们还没开放」「上游供应商挂了」「我们自己的服务器不可达」。
 *
 * <p>message 经 {@code LangText.of} 双语化，且<b>不许含「登录」「未授权」「请先」</b>——
 * {@code frontend/src/services/api.js} 拿这三个子串判掉线并清会话。
 */
public class GatewayException extends RuntimeException {

    public enum Kind {
        /** 本机尚未连接账户，请求根本没发出去。 */
        NOT_CONNECTED,
        /** 账户 Credits 不足。**不是凭据问题**，绝不能让前端当成掉线。 */
        NO_CREDITS,
        /**
         * 本次任务累计花费撞上用户自己设的上限（设计 §4.9）。
         *
         * <p>这一档<b>是可恢复的确认，不是失败</b>：任务没坏、余额也够，只是用户想在
         * 花到这个数的时候被问一句。上层要摆的是「已花费 N Credits，是否继续」，
         * 不是一句错误提示。
         */
        BUDGET_EXCEEDED,
        /** 该服务尚未开放（合同/账号未就绪，或平台侧未配置凭证）。 */
        SERVICE_DISABLED,
        /** 上游供应商返回错误或超时。其余服务不受影响。 */
        UPSTREAM_FAILED,
        /** 我们的网关不可达（官网挂了/正在部署/本机断网）。 */
        GATEWAY_UNREACHABLE,
        /** 账户 Key 无效或已被吊销。 */
        UNAUTHORIZED,
        /** 请求本身不合法，重试没用。 */
        BAD_REQUEST,
        /** 网关返回了预期外的内容。 */
        MALFORMED
    }

    private final Kind kind;

    public GatewayException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * 要不要在提示里主动摆出「改用自己的 Key」这个入口。
     *
     * <p>自备 Key 整个绕开网关，所以对任何一种网关失败它都是一条真出路。
     * 尤其是 {@link Kind#NOT_CONNECTED}：<b>用试用码解锁、根本不打算连账户的用户
     * （README 公开发布的永久试用码是主要获客入口），自备 Key 是他唯一的出路</b>——
     * 只提示他去连账户等于把他堵死。{@link Kind#NO_CREDITS} 同理，
     * 主 CTA 是充值，但不该把另一条合法出路藏起来。
     *
     * <p>只有 {@link Kind#UNAUTHORIZED} 不提：那里的结论已经很明确（Key 无效或被撤销），
     * 再塞第二个建议只会让用户不知道该修哪个。
     *
     * <p>{@link Kind#BUDGET_EXCEEDED} 同样不提，但理由相反：那不是一次故障，
     * 是用户自己设的闸拦了自己。他要的下一步是「继续」或「把上限调高」，
     * 这时摆一个「改用自己的 Key」是答非所问——照做还会让他绕开自己刚设的上限。
     */
    public boolean suggestsByok() {
        return kind != Kind.UNAUTHORIZED && kind != Kind.BAD_REQUEST && kind != Kind.MALFORMED
                && kind != Kind.BUDGET_EXCEEDED;
    }
}
