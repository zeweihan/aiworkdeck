// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

/**
 * 官方案件库当前的资格门：加人双方**至少同一律所；没有律所的，同一团队**。
 *
 * <p>判定顺序刻意先看操作人自己——他没有团队时说"对方不在你的团队里"会把人引去催同事，
 * 而该做的事在他自己这边（先建或加入一个团队）。
 */
public class SameFirmOrTeamPolicy implements CollaborationPolicy {

    @Override
    public Verdict check(OrgMembership requester, OrgMembership candidate) {
        OrgMembership me = requester == null ? OrgMembership.NONE : requester;
        OrgMembership other = candidate == null ? OrgMembership.NONE : candidate;

        if (isBlank(me.teamId())) {
            return Verdict.denied(Denial.REQUESTER_NO_TEAM);
        }
        if (isBlank(other.teamId())) {
            return Verdict.denied(Denial.NOT_IN_ORG);
        }
        // 律所是第一级：同律所不同团队照样是同事。两边 firmId 都非空才算数——
        // 都为 null 时 equals 成立，那会让所有没挂靠律所的人互相"同律所"。
        if (!isBlank(me.firmId()) && me.firmId().equals(other.firmId())) {
            return Verdict.ALLOWED;
        }
        if (me.teamId().equals(other.teamId())) {
            return Verdict.ALLOWED;
        }
        return Verdict.denied(Denial.NOT_IN_ORG);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
