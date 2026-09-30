// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
 * <p>UI uses structured error codes to distinguish sign-in, top-up and provider failures.
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

    /** Official desktop gateways require an account; BYOK is not a supported recovery path. */
    public boolean suggestsByok() {
        return false;
    }

    /**
     * 给用户/模型看的「下一步」指引（官方版口径，2026-08-26 dev-board#172）。
     *
     * <p>旧提示「在系统管理 → 平台服务改为自备 Key」自 #533 起是死路——官方版界面
     * 已无任何 BYOK 入口。真正需要用户动手的只有两种失败：余额不足去充值、
     * 未连接账户去连接；其余（服务未开放/上游故障/网关不可达）都在平台侧，
     * 提示稍后重试即可，不指一条不存在的路。
     */
    public String userHint() {
        return switch (kind) {
            case NO_CREDITS -> "账户 Credits 余额不足，充值后重试。";
            case NOT_CONNECTED -> "请登录 AI WorkDeck 账户后重试。";
            case SERVICE_DISABLED, UPSTREAM_FAILED, GATEWAY_UNREACHABLE -> "请稍后重试；若持续失败请联系 hi@aiworkdeck.com。";
            default -> "";
        };
    }
}
