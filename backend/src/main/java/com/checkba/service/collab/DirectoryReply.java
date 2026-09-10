// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

/**
 * 名录一次查询的答复。{@code found} 为假时 {@code account} 为 null、{@code candidate} 为
 * {@link OrgMembership#NONE}，但 {@code requester} 仍然有值——"你自己还没加入团队"这条
 * 拒绝理由要靠它算出来。
 */
public record DirectoryReply(boolean found, DirectoryAccount account,
                             OrgMembership requester, OrgMembership candidate) {}
