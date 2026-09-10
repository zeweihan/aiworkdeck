// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

/** 一个人在「律所 → 团队 → 律师」结构里的位置。两个键都可能为空（没加入任何团队）。 */
public record OrgMembership(String teamId, String firmId) {
    public static final OrgMembership NONE = new OrgMembership(null, null);
}
