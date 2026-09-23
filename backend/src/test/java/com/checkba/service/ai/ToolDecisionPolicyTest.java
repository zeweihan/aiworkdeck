// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolDecisionPolicyTest {
    private final DecisionAssistService service = mock(DecisionAssistService.class);
    private final ToolDecisionPolicy policy = new ToolDecisionPolicy(service, new ToolDisclosurePolicy(false));

    private static DecisionAssistContext context(boolean enabled, AiModelProperties.Provider provider) {
        return new DecisionAssistContext(enabled, 1L, 2L, "synthetic", "selected-model", provider);
    }

    private static DecisionAssistContext enabled() {
        return context(true, AiModelProperties.Provider.AWD_CLOUD);
    }

    private static ToolSpecification tool(String name, int descriptionChars) {
        return ToolSpecification.builder().name(name).description("x".repeat(descriptionChars)).build();
    }

    private static List<ToolSpecification> candidates() {
        return List.of(tool("list_tools", 80), tool("read_document", 80),
                tool("read_file", 1800), tool("pdf_inspect", 1800), tool("doc_set_font", 1800),
                tool("memory_read", 80));
    }

    private void reply(String category, double confidence) {
        when(service.choose(any(), anyMap(), anyString(), anyMap()))
                .thenReturn(Optional.of(new DecisionAssistService.Decision(category, confidence)));
    }

    @Test void noConsentOrLocalOrCancelledNeverInvokesDecisionClient() {
        assertTrue(policy.select(null, "读取磁盘文件", candidates()).isEmpty());
        assertTrue(policy.select(context(false, AiModelProperties.Provider.AWD_CLOUD), "读取磁盘文件", candidates()).isEmpty());
        assertTrue(policy.select(context(true, AiModelProperties.Provider.OLLAMA), "读取磁盘文件", candidates()).isEmpty());
        assertTrue(policy.select(context(true, null), "读取磁盘文件", candidates()).isEmpty());
        DecisionAssistContext cancelled = enabled();
        cancelled.cancel();
        assertTrue(policy.select(cancelled, "读取磁盘文件", candidates()).isEmpty());
        verifyNoInteractions(service);
    }

    @Test void oversizedOrEmptyRequestIsNotTruncatedOrSent() {
        assertTrue(policy.select(enabled(), "合".repeat(4001), candidates()).isEmpty());
        assertTrue(policy.select(enabled(), "  ", candidates()).isEmpty());
        assertTrue(policy.select(enabled(), null, candidates()).isEmpty());
        verifyNoInteractions(service);
    }

    @Test void smallCandidatesOrMissingCatalogNeverPayForAdvice() {
        assertTrue(policy.select(enabled(), "读文件", List.of(tool("list_tools", 30), tool("read_file", 30))).isEmpty());
        assertTrue(policy.select(enabled(), "读文件", List.of(tool("read_file", 6000))).isEmpty());
        assertTrue(policy.select(enabled(), "读文件", List.of()).isEmpty());
        verifyNoInteractions(service);
    }

    @Test void entirelyCoreCandidatesCannotShrinkAndNeverCall() {
        assertTrue(policy.select(enabled(), "读取文档", List.of(tool("list_tools", 80), tool("read_document", 6000), tool("memory_read", 3000))).isEmpty());
        verifyNoInteractions(service);
    }

    @Test void forwardsCompleteUserTextAndOnlyActuallyAvailableToolNames() {
        reply("files", 0.8);
        String request = "用户原文".repeat(1000);
        assertEquals("files", policy.select(enabled(), request, candidates()).orElseThrow());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, ?>> state = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> criteria = ArgumentCaptor.forClass(Map.class);
        verify(service).choose(any(), state.capture(), eq(ToolDecisionPolicy.INSTRUCTIONS), criteria.capture());
        assertEquals(request, state.getValue().get("user_request"));
        assertEquals(java.util.Set.of("user_request", "available_tools"), state.getValue().keySet());
        @SuppressWarnings("unchecked") Map<String, List<String>> available =
                (Map<String, List<String>>) state.getValue().get("available_tools");
        assertEquals(List.of("list_tools", "read_document", "memory_read"), available.get("core"));
        assertEquals(List.of("read_file"), available.get("files"));
        assertFalse(available.containsKey("slides"));
        assertEquals(java.util.Set.of("core", "files", "pdf", "format", "uncertain"), criteria.getValue().keySet());
        assertFalse(state.getValue().toString().contains("xxx"), "Descriptions and documents are not sent to the classifier");
        assertTrue(criteria.getValue().get("files").contains("read_file"));
    }

    @Test void coreIsValidWhenItActuallyShrinksTheCandidateSet() {
        reply("core", 0.99);
        assertEquals("core", policy.select(enabled(), "只解释这个词", candidates()).orElseThrow());
    }

    @Test void uncertaintyLowConfidenceAndUnavailableOrMultipleCategoriesKeepOriginalSet() {
        for (String choice : List.of("uncertain", "python", "files,pdf", "files pdf", "")) {
            reply(choice, 0.99);
            assertTrue(policy.select(enabled(), "合成请求", candidates()).isEmpty(), choice);
        }
        for (double confidence : new double[]{0.799, Double.NaN, Double.POSITIVE_INFINITY, 1.01}) {
            reply("files", confidence);
            assertTrue(policy.select(enabled(), "合成请求", candidates()).isEmpty());
        }
    }

    @Test void decisionFailureDoesNotSelectADefaultCategory() {
        when(service.choose(any(), anyMap(), anyString(), anyMap())).thenReturn(Optional.empty());
        assertTrue(policy.select(enabled(), "合成请求", candidates()).isEmpty());
    }

    @Test void cancellationDuringDecisionDiscardsItsResult() {
        DecisionAssistContext ctx = enabled();
        when(service.choose(any(), anyMap(), anyString(), anyMap())).thenAnswer(invocation -> {
            ctx.cancel();
            return Optional.of(new DecisionAssistService.Decision("files", 1));
        });
        assertTrue(policy.select(ctx, "合成请求", candidates()).isEmpty());
    }

    @Test void dominantSelectedCategoryWithLessThan25PercentSavingIsRejected() {
        reply("files", 1);
        var almostAllFiles = List.of(tool("list_tools", 100), tool("read_file", 9000), tool("pdf_inspect", 200));
        assertTrue(policy.select(enabled(), "读文件", almostAllFiles).isEmpty());
        verify(service).choose(any(), anyMap(), anyString(), anyMap());
    }

    @Test void mandatoryMemoryToolsAreIncludedInSavingsNotPretendedRemoved() {
        var dominatedByMemory = List.of(tool("list_tools", 80), tool("memory_read", 9000), tool("read_file", 500));
        assertTrue(policy.select(enabled(), "查记忆", dominatedByMemory).isEmpty());
        verifyNoInteractions(service);
    }
}
