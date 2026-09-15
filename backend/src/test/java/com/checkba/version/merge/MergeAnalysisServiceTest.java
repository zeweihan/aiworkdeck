// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化比对的缓存口径（spec 2026-09-14 §4.3）。
 *
 * <p>缓存键必须是 {@code (projectId, HEAD, MERGE_HEAD)} 而不是光一个 projectId：
 * {@code /status} 每 120 秒轮一次、裁决面板还会再刷几次，每轮把几 MB 的 docx 重解一遍
 * 是白烧 CPU；但键里少了那两个 sha，上一次合并窗口的判定就会被端给下一次窗口，
 * 律师看到的是别的文件的裁决清单。
 */
class MergeAnalysisServiceTest {

    private static final String DOC = "合同.docx";

    @Test
    @DisplayName("同一个合并窗口里重复取分析走缓存，不重解一遍")
    void cachesByHeadAndMergeHead(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            s.stageAdoptConflict(DOC);

            Map<String, Analysis> first = s.analysisSvc.analyzeConflicts(MergeScene.PROJECT_ID);
            Map<String, Analysis> second = s.analysisSvc.analyzeConflicts(MergeScene.PROJECT_ID);

            assertFalse(first.isEmpty(), "前置：窗口里确实有待裁决的文件");
            assertSame(first, second, "HEAD 与 MERGE_HEAD 都没动，必须命中缓存");
            assertSame(first.get(DOC), second.get(DOC));
        }
    }

    @Test
    @DisplayName("离开合并态：缓存当场作废，下一次窗口不会读到上一次的判定")
    void leavesMergingClearsCache(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            s.stageAdoptConflict(DOC);
            Map<String, Analysis> inWindow = s.analysisSvc.analyzeConflicts(MergeScene.PROJECT_ID);
            assertTrue(inWindow.containsKey(DOC));

            s.sessionSvc.abortAdopt(MergeScene.PROJECT_ID);

            assertTrue(s.analysisSvc.analyzeConflicts(MergeScene.PROJECT_ID).isEmpty(),
                    "不在合并中就没有待裁决文件，旧判定一条都不许留下");
            assertTrue(s.analysisSvc.documentMerges(MergeScene.PROJECT_ID).isEmpty());
        }
    }

    @Test
    @DisplayName("pdf 这类没有可比对单元的文件：整份选择，理由说得出来")
    void binaryFilesAreWholeWithReason(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            s.stageAdoptConflictOn("附件.pdf", "PDF-A".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "PDF-B".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "PDF-C".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Analysis a = s.analysisSvc.analysisFor(MergeScene.PROJECT_ID, "附件.pdf");

            assertEquals(MergeKind.WHOLE, a.kind());
            assertEquals(MergeDecision.WHOLE, a.decision());
            assertEquals(MergeReason.BINARY, a.reason());
        }
    }
}
