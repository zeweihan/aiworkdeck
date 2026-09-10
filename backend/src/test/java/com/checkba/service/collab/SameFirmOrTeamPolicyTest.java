// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资格判定层（spec 2026-09-10 §5）：加人双方**至少同一律所；没有律所的，同一团队**。
 *
 * <p>这一层是刻意可整体替换的——将来律师之间只按项目连接时把
 * {@code collab.eligibility.policy} 切到 {@code open} 即可，上下游一行不动。
 * 所以这里钉的是判定本身的六条分支，不是它被谁调用。
 */
class SameFirmOrTeamPolicyTest {

    private final CollaborationPolicy policy = new SameFirmOrTeamPolicy();

    @Test
    @DisplayName("自己还没加入团队：REQUESTER_NO_TEAM（先说自己的问题，别让人去催同事）")
    void requesterWithoutATeamIsToldAboutThemselvesFirst() {
        Verdict v = policy.check(OrgMembership.NONE, new OrgMembership("team-b", "firm-1"));

        assertFalse(v.allowed());
        assertEquals(Denial.REQUESTER_NO_TEAM, v.denial());
    }

    @Test
    @DisplayName("对方没有团队：NOT_IN_ORG")
    void candidateWithoutATeamIsOutOfOrg() {
        Verdict v = policy.check(new OrgMembership("team-a", "firm-1"), OrgMembership.NONE);

        assertFalse(v.allowed());
        assertEquals(Denial.NOT_IN_ORG, v.denial());
    }

    @Test
    @DisplayName("同一律所、不同团队：通过（律所是第一级，团队只是律所内的分组）")
    void sameFirmDifferentTeamPasses() {
        Verdict v = policy.check(new OrgMembership("team-a", "firm-1"),
                new OrgMembership("team-b", "firm-1"));

        assertTrue(v.allowed());
        assertNull(v.denial());
    }

    @Test
    @DisplayName("同一团队、两边都没有律所：通过（没挂靠律所的小团队照样能协作）")
    void sameTeamWithoutAnyFirmPasses() {
        Verdict v = policy.check(new OrgMembership("team-a", null),
                new OrgMembership("team-a", null));

        assertTrue(v.allowed());
        assertNull(v.denial());
    }

    @Test
    @DisplayName("不同律所：NOT_IN_ORG（团队号不同就更不用问了）")
    void differentFirmsAreOutOfOrg() {
        Verdict v = policy.check(new OrgMembership("team-a", "firm-1"),
                new OrgMembership("team-b", "firm-2"));

        assertFalse(v.allowed());
        assertEquals(Denial.NOT_IN_ORG, v.denial());
    }

    @Test
    @DisplayName("不同团队、两边都没有律所：NOT_IN_ORG")
    void differentTeamsWithoutFirmsAreOutOfOrg() {
        Verdict v = policy.check(new OrgMembership("team-a", null),
                new OrgMembership("team-b", null));

        assertFalse(v.allowed());
        assertEquals(Denial.NOT_IN_ORG, v.denial());
    }

    /** 同一律所但一边的 firmId 为空，不能靠"两个 null 相等"蒙混过关。 */
    @Test
    @DisplayName("一边有律所一边没有、团队又不同：NOT_IN_ORG")
    void oneSidedFirmDoesNotMatch() {
        Verdict v = policy.check(new OrgMembership("team-a", "firm-1"),
                new OrgMembership("team-b", null));

        assertFalse(v.allowed());
        assertEquals(Denial.NOT_IN_ORG, v.denial());
    }

    @Test
    @DisplayName("OpenPolicy：一律通过（将来只按项目连接、以及自建服务器用的就是它）")
    void openPolicyAlwaysAllows() {
        CollaborationPolicy open = new OpenPolicy();

        assertTrue(open.check(OrgMembership.NONE, OrgMembership.NONE).allowed());
        assertTrue(open.check(new OrgMembership("team-a", "firm-1"),
                new OrgMembership("team-b", "firm-2")).allowed());
        assertNull(open.check(OrgMembership.NONE, OrgMembership.NONE).denial());
    }
}
