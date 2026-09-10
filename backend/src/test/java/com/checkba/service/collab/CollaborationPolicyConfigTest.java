// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资格门选型（{@code collab.eligibility.policy}）：写错值必须**启动即失败**。
 *
 * <p>不这么做的后果很具体：把值敲成 {@code firm_or_team} 会静默落回"放开"，
 * 一台本该按律所限制的案件库从此谁都能被加进案卷，而日志里一个字都没有。
 */
class CollaborationPolicyConfigTest {

    private final CollaborationPolicyConfig config = new CollaborationPolicyConfig();

    @Test
    @DisplayName("firm-or-team → SameFirmOrTeamPolicy；open → OpenPolicy")
    void knownValuesPickTheirImplementation() {
        assertInstanceOf(SameFirmOrTeamPolicy.class, config.collaborationPolicy("firm-or-team"));
        assertInstanceOf(SameFirmOrTeamPolicy.class, config.collaborationPolicy("  FIRM-OR-TEAM "));
        assertInstanceOf(OpenPolicy.class, config.collaborationPolicy("open"));
    }

    @Test
    @DisplayName("没配（空）：按 open，与 application.yml 的默认一致")
    void blankFallsBackToOpen() {
        assertInstanceOf(OpenPolicy.class, config.collaborationPolicy(""));
        assertInstanceOf(OpenPolicy.class, config.collaborationPolicy(null));
    }

    @Test
    @DisplayName("写错值：启动即失败，消息里说清取值范围，绝不静默落回 open")
    void unknownValueFailsStartupLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> config.collaborationPolicy("firm_or_team"));
        assertTrue(e.getMessage().contains("firm_or_team"), e.getMessage());
        assertTrue(e.getMessage().contains("firm-or-team"), e.getMessage());
        assertTrue(e.getMessage().contains("open"), e.getMessage());
    }
}
