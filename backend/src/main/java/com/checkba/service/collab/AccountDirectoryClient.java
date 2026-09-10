// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

/**
 * 官网账户名录的只读查询口（spec 2026-09-10 §4/§5）。
 *
 * <p>存在理由：案件库的 {@code app_users} 表只有**在桌面端桥接过案件库的人**，而律师要加的同事
 * 常常只在官网注册过。这条口回答两件事——"官网上有没有这个人"与"双方的团队/律所归属"，
 * 好让案件库预建桥接用户并判定资格。
 *
 * <p><b>它只回名录事实，永远不回任何凭据</b>：官网早已否决过"服务端凭 accountId 换任意
 * 用户 key"那类宽权限 S2S 主凭据（doc/desktop-contract.md「per-user 平台 AI key」），
 * 本口的形状照 {@code /api/internal/transfer} 与 {@code /api/internal/account}——
 * 同机回环直连、专用密钥、未配置一律裸 404。
 */
public interface AccountDirectoryClient {

    /** base-url 与 secret 都配齐了才算开通；没开通时上层维持"只查本库"的今天行为。 */
    boolean configured();

    /** 按手机号或邮箱找账户。{@code requesterAccountId} 可为 null（操作人还没桥接过）。 */
    DirectoryReply lookupByIdentifier(String requesterAccountId, String identifier);

    /** 按官网 accountId 找账户——本地已有这行、只需要复核归属时走它。 */
    DirectoryReply lookupByAccountId(String requesterAccountId, String candidateAccountId);
}
