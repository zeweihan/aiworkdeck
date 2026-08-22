package com.checkba.service.ai.tools;

import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.evidence.EvidenceChecks;
import com.checkba.service.evidence.EvidenceChecks.Check;
import com.checkba.service.evidence.EvidenceVerifyService;
import com.checkba.service.evidence.EvidenceVerifyViews.BatchQuery;
import com.checkba.service.evidence.EvidenceVerifyViews.BatchResult;
import com.checkba.service.evidence.EvidenceVerifyViews.LinkVerdict;
import com.checkba.service.evidence.EvidenceVerifyViews.TargetVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * evidence_verify 工具面（dev-board#116）：参数整形、精简摘要形状、
 * 以及给模型的那句「unverifiable ≠ 矛盾」——这条是 evidence.retrieve.v1 的不变式，
 * 漏了模型就会把「底稿里没查到」写成「与底稿不符」。
 */
class EvidenceVerifyToolTest {

    EvidenceVerifyService verify = mock(EvidenceVerifyService.class);
    EvidenceTools tools = new EvidenceTools(null, verify);
    final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ProjectContextHolder.setProjectId("1");
        ProjectContextHolder.setUserId(9L);
    }

    @AfterEach
    void tearDown() {
        ProjectContextHolder.clear();
    }

    @Test
    @DisplayName("缺 docFileId 与 linkKey 直接报错，不发起核查")
    void requiresOneOfTheTwo() {
        assertTrue(tools.evidence_verify(null, null, null).startsWith("Error:"));
    }

    @Test
    @DisplayName("给 linkKey 走单条核查")
    void singleMode() throws Exception {
        when(verify.verifyLink(9L, 1L, "EVID_A")).thenReturn(
                new LinkVerdict("EVID_A", 10L, "一", "陈述", EvidenceChecks.VERDICT_SUPPORTS,
                        List.of(new TargetVerdict(200L, 11L, "执照.pdf", "supports", (short) 100,
                                EvidenceChecks.VERDICT_SUPPORTS, List.of()))));
        JsonNode j = om.readTree(tools.evidence_verify(null, "EVID_A", null));
        assertEquals("single", j.get("mode").asText());
        assertEquals(1, j.get("verdicts").get(EvidenceChecks.VERDICT_SUPPORTS).asInt());
    }

    @Test
    @DisplayName("给 docFileId 走批量，scope 变成章节前缀；没跑完时回 nextOffset 与续跑提示")
    void batchModeCarriesScopeAndCursor() throws Exception {
        when(verify.verifyBatch(eq(9L), eq(1L), any())).thenReturn(new BatchResult(120, 0, 50, 50, false,
                Map.of(EvidenceChecks.VERDICT_SUPPORTS, 50), List.of()));

        JsonNode j = om.readTree(tools.evidence_verify(10L, null, "一/（二）"));

        assertEquals("batch", j.get("mode").asText());
        assertEquals(120, j.get("total").asInt());
        assertEquals(50, j.get("nextOffset").asInt());
        assertTrue(j.hasNonNull("hint"));
        ArgumentCaptor<BatchQuery> cap = ArgumentCaptor.forClass(BatchQuery.class);
        verify(verify).verifyBatch(eq(9L), eq(1L), cap.capture());
        assertEquals(10L, cap.getValue().docFileId());
        assertEquals("一/（二）", cap.getValue().sectionPath());
    }

    @Test
    @DisplayName("摘要只点名真冲突，并且明说 unverifiable 不是矛盾")
    void summaryListsOnlyRealContradictions() throws Exception {
        LinkVerdict bad = new LinkVerdict("EVID_BAD", 10L, "一", "统一社会信用代码 91330100799655058B",
                EvidenceChecks.VERDICT_CONTRADICTS,
                List.of(new TargetVerdict(200L, 11L, "执照.pdf", "contradicts", (short) 0,
                        EvidenceChecks.VERDICT_CONTRADICTS,
                        List.of(new Check(EvidenceChecks.KIND_USCC, "91330100799655058B", "914403001922038216",
                                false, "底稿里的统一社会信用代码与陈述不一致")))));
        LinkVerdict unknown = new LinkVerdict("EVID_UNK", 10L, "一", "另一句",
                EvidenceChecks.VERDICT_UNVERIFIABLE,
                List.of(new TargetVerdict(201L, 12L, "章程.pdf", "supports", null,
                        EvidenceChecks.VERDICT_UNVERIFIABLE, List.of())));
        when(verify.verifyBatch(eq(9L), eq(1L), any())).thenReturn(new BatchResult(2, 0, 2, null, false,
                Map.of(EvidenceChecks.VERDICT_CONTRADICTS, 1, EvidenceChecks.VERDICT_UNVERIFIABLE, 1),
                List.of(bad, unknown)));

        String raw = tools.evidence_verify(10L, null, null);
        JsonNode j = om.readTree(raw);

        assertEquals(1, j.get("contradictions").size(), "只有真冲突进清单");
        assertEquals("EVID_BAD", j.get("contradictions").get(0).get("linkKey").asText());
        assertEquals("914403001922038216", j.get("contradictions").get(0).get("draft").asText());
        assertTrue(j.get("note").asText().contains("不是"), "要明说 unverifiable 不是矛盾：" + raw);
    }

    @Test
    @DisplayName("Service 抛错原样回给模型，不吞")
    void serviceErrorSurfaces() {
        when(verify.verifyLink(9L, 1L, "EVID_A")).thenThrow(new IllegalArgumentException("无权限修改该项目"));
        assertTrue(tools.evidence_verify(null, "EVID_A", null).contains("无权限修改该项目"));
    }
}
