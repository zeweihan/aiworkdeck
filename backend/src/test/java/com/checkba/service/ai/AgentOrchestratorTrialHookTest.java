// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 试用计量钩子从 bubble_end 载荷里读 status（载荷由 bubbleEndPayload 唯一生成）。 */
class AgentOrchestratorTrialHookTest {

    @Test
    void parsesStatusLiteral() {
        assertEquals("finished", AgentOrchestrator.bubbleEndStatus("{\"status\":\"finished\",\"documentEdited\":false}"));
        assertEquals("paused", AgentOrchestrator.bubbleEndStatus("{\"status\":\"paused\",\"reason\":\"max_tokens\",\"documentEdited\":true}"));
        assertNull(AgentOrchestrator.bubbleEndStatus("{\"reason\":\"x\"}"));
        assertNull(AgentOrchestrator.bubbleEndStatus(null));
    }
}
