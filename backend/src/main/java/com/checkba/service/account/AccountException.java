package com.checkba.service.account;

/**
 * 与官网账户通信的失败分类。
 *
 * 分类的意义在调用侧：{@link Kind#NETWORK}（断网/超时/DNS）**不能**清除本地已存的连接，
 * 否则用户一进地铁就被断开；{@link Kind#UNAUTHORIZED}（官网明确拒绝）才是真的失效。
 * 这与 PR-A 的 {@code LicenseService} 4xx=INVALID / 5xx=UNREACHABLE 判定同源。
 *
 * message 一律中文且**不含 awdk_ Key 明文**——它会进日志、进前端 toast。
 */
public class AccountException extends RuntimeException {

    public enum Kind {
        /** 网络不可达：断网、超时、DNS 失败、5xx。保留本地连接。 */
        NETWORK,
        /** 鉴权失败：401/403，Key 无效或已被吊销。可清除本地连接。 */
        UNAUTHORIZED,
        /** 业务冲突：409（如未分配 AI 额度）。 */
        CONFLICT,
        /** 本地尚未连接账户，请求根本没发出去。 */
        NOT_CONNECTED,
        /** 官网返回了预期外的内容（字段缺失、非 JSON）。 */
        MALFORMED
    }

    private final Kind kind;

    public AccountException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
