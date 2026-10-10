// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.Test;
import com.checkba.controller.ai.AiAgentController;
import com.checkba.service.trial.TrialTurnMeter;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

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

    @Test
    void queuedTurnCapturesOwnerAtLaunchAndPassesItToCompletionHook() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class, CALLS_REAL_METHODS);
        TrialTurnMeter meter = mock(TrialTurnMeter.class);
        when(meter.accountFingerprint()).thenReturn("account-a");
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        ReflectionTestUtils.setField(orchestrator, "trialTurnMeter", meter);
        ReflectionTestUtils.setField(orchestrator, "turnExecutor", (java.util.concurrent.Executor) queued::add);
        AgentOrchestrator.RunGuard guard = new AgentOrchestrator.RunGuard("conversation", "run-a", 1);
        ReflectionTestUtils.invokeMethod(orchestrator, "launchTurn", new AiAgentController.AgentChatRequest(), 7L, guard);
        assertEquals(1, queued.size());
        when(meter.accountFingerprint()).thenReturn("account-b");
        ReflectionTestUtils.setField(guard, "producedText", true);
        guard.platformUsed.set(true);
        ReflectionTestUtils.invokeMethod(orchestrator, "meterTrialTurn", guard, "{\"status\":\"finished\"}");
        verify(meter).onTurnEnded("run-a", 7L, "account-a", "finished", true, true);
        verify(meter, times(1)).accountFingerprint();
    }
}
