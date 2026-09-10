// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

/** 放开：只要官网名录里有这个人就允许。自建服务器与"只按项目连接"的将来用它。 */
public class OpenPolicy implements CollaborationPolicy {

    @Override
    public Verdict check(OrgMembership requester, OrgMembership candidate) {
        return Verdict.ALLOWED;
    }
}
