// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

/**
 * 加不进来的三种原因。分开是因为**下一步动作完全不同**：让对方去注册 / 去团队设置里
 * 邀请对方 / 自己先创建或加入团队。合成一句"用户不存在"等于把人晾在原地。
 */
public enum Denial {
    /** 官网上根本没有这个手机号/邮箱的账户。 */
    NOT_REGISTERED,
    /** 有账户，但不在操作人的律所或团队里。 */
    NOT_IN_ORG,
    /** 操作人自己还没加入任何团队。 */
    REQUESTER_NO_TEAM
}
