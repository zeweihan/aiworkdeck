// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import com.checkba.version.VersionEntry;
import com.checkba.version.VersionException;
import com.checkba.version.WorkSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 逐处合并结果的落盘、待决记录，以及三语境按 {@code MERGED} 收尾（spec 2026-09-14 §4.4）。
 *
 * <p>三条不变式，每一条都对应一次真实的数据事故：
 * <ol>
 *   <li><b>落盘的路径必须在本次冲突清单里</b>——否则 {@code resolve-file} 就成了一个
 *       "往项目里任意写文件"的端点，而它随后还会被 {@code git add .} 收进律师的历史。</li>
 *   <li><b>{@code MERGED} 必须有待决记录才认</b>——没有记录就意味着工作区里躺着的还是
 *       带冲突标记的半成品（或者干脆是合并前的旧内容），认下去等于把半成品提交进主线，
 *       历史永不重写，不可逆。</li>
 *   <li><b>待决记录必须跨进程活着</b>——裁决窗口是数据安全窗口，律师裁完一份文件之后
 *       关掉桌面端，重开时 {@code /status} 要说"这份已经合好了"，否则他会被要求把
 *       同一份文件再裁一遍，而那时工作区里已经是合并结果、不是冲突态。</li>
 * </ol>
 *
 * <p>HTTP 档位：本控制器的异常处理器（{@code VersionController.onVersionError}）把
 * 业务性拒绝统一落成 HTTP 200 + {@code code=1}，所以"400"在这里的判据是
 * <b>抛出 userFacing 的 {@link VersionException}</b>，而不是 HTTP 状态码。
 */
class MergeResolveFileTest {

    private static final String DOC = "合同.docx";

    /** 逐处裁决清单：第 2 段留了主线这边的、拒绝了另一边的。 */
    private static final String DECISIONS_JSON =
            "[{\"key\":\"p1\",\"side\":\"M\",\"action\":\"A\"},"
                    + "{\"key\":\"p1\",\"side\":\"T\",\"action\":\"R\"},"
                    + "{\"key\":\"p4\",\"side\":\"T\",\"action\":\"A\"}]";

    private static final List<Decision> DECISIONS = List.of(
            new Decision("p1", "M", "A"),
            new Decision("p1", "T", "R"),
            new Decision("p4", "T", "A"));

    /** 律师在合并比对稿里裁完之后导出的那份字节。 */
    private static byte[] mergedBytes() {
        return MergeFixtures.docx("第一条 甲方", "第二条 乙方（我改的）", "第三条 标的",
                "第四条 价款", "第五条 交付（律师乙改的）");
    }

    private static MultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", DOC, "application/octet-stream", bytes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(org.springframework.http.ResponseEntity<Map<String, Object>> res) {
        return (Map<String, Object>) res.getBody().get("data");
    }

    // ---- 1. 落盘与待决记录 --------------------------------------------------

    @Test
    @DisplayName("逐处裁决的字节写回工作区，同时记下待决记录")
    void writesBytesAndRecordsPending(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            s.stageAdoptConflict(DOC);
            byte[] merged = mergedBytes();

            Map<String, Object> data = dataOf(s.controller.mergeResolveFile(
                    MergeScene.PROJECT_ID, DOC, "manual", DECISIONS_JSON, "adopt",
                    upload(merged), MergeScene.SESSION));

            assertEquals(DOC, data.get("path"));
            assertEquals("MERGED", data.get("state"));
            assertArrayEquals(merged, s.read(DOC), "合并结果必须真的落在工作区那个路径上");

            MergeRecord rec = s.pendingStore.get(MergeScene.PROJECT_ID, DOC).orElseThrow();
            assertEquals("manual", rec.mode());
            assertEquals(DECISIONS, rec.decisions());
        }
    }

    @Test
    @DisplayName("自动合并（mode=auto）不带逐处清单，按分析结果记两个计数")
    void autoModeRecordsCountsFromAnalysis(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            s.stageAdoptConflict(DOC);

            s.controller.mergeResolveFile(MergeScene.PROJECT_ID, DOC, "auto", "[]", null,
                    upload(mergedBytes()), MergeScene.SESSION);

            MergeRecord rec = s.pendingStore.get(MergeScene.PROJECT_ID, DOC).orElseThrow();
            assertEquals("auto", rec.mode());
            assertTrue(rec.decisions().isEmpty());
            assertEquals(1, rec.mainCount(), "主线这边改了第 2 段");
            assertEquals(1, rec.otherCount(), "另一边改了第 5 段");
        }
    }

    @Test
    @DisplayName("不在本次冲突清单里的路径一律拒绝")
    void rejectsPathOutsideConflicts(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            s.stageAdoptConflict(DOC);

            VersionException e = assertThrows(VersionException.class, () ->
                    s.controller.mergeResolveFile(MergeScene.PROJECT_ID, "别的文件.docx", "manual",
                            "[]", null, upload(mergedBytes()), MergeScene.SESSION));
            assertTrue(e.isUserFacing());
            assertFalse(java.nio.file.Files.exists(
                            s.root.resolve("projects/" + MergeScene.PROJECT_ID + "/别的文件.docx")),
                    "被拒的路径一个字节都不许落盘");
        }
    }

    @Test
    @DisplayName("语境对不上（客户端拿着过期的冲突对象）也拒绝")
    void rejectsMismatchedContext(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            s.stageAdoptConflict(DOC);

            VersionException e = assertThrows(VersionException.class, () ->
                    s.controller.mergeResolveFile(MergeScene.PROJECT_ID, DOC, "manual",
                            "[]", "cloud", upload(mergedBytes()), MergeScene.SESSION));
            assertTrue(e.isUserFacing());
        }
    }

    // ---- 2. MERGED 的校验与三语境收尾 ---------------------------------------

    @Test
    @DisplayName("没有待决记录就报 MERGED：拒绝（否则会把半成品提交进主线）")
    void mergedWithoutPendingIs400(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            long draftId = s.stageAdoptConflict(DOC);

            VersionException e = assertThrows(VersionException.class, () ->
                    s.sessionSvc.resolveAdopt(MergeScene.PROJECT_ID, draftId,
                            Map.of(DOC, WorkSessionService.Resolution.MERGED),
                            MergeScene.USER_ID, "韩泽伟"));
            assertTrue(e.isUserFacing());
            assertTrue(s.repoSvc.repositoryMerging(MergeScene.PROJECT_ID),
                    "被拒之后仓库必须仍停在待裁决状态，两边分毫无损");
        }
    }

    @Test
    @DisplayName("采纳语境：MERGED 收尾写下语境与逐处合并尾注")
    void adoptCompletesWithMergesTrailer(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            long draftId = s.stageAdoptConflict(DOC);
            byte[] merged = mergedBytes();
            s.controller.mergeResolveFile(MergeScene.PROJECT_ID, DOC, "manual", DECISIONS_JSON,
                    "adopt", upload(merged), MergeScene.SESSION);

            WorkSessionService.AdoptOutcome r = s.sessionSvc.resolveAdopt(MergeScene.PROJECT_ID, draftId,
                    Map.of(DOC, WorkSessionService.Resolution.MERGED), MergeScene.USER_ID, "韩泽伟");

            assertTrue(r.success());
            VersionEntry head = s.repoSvc.log(MergeScene.PROJECT_ID, "HEAD", 1).get(0);
            assertEquals("adopt", head.mergeContext());
            assertEquals(List.of(new VersionEntry.Resolution(DOC, "MERGED")), head.resolutions());
            assertEquals(List.of(new VersionEntry.MergeSummary(DOC, "manual", DECISIONS, 0, 0)),
                    head.merges());
            assertArrayEquals(merged, s.read(DOC), "提交的就是律师裁决后的那份字节");
            assertTrue(s.pendingStore.all(MergeScene.PROJECT_ID).isEmpty(), "收尾后待决记录清空");
        }
    }

    @Test
    @DisplayName("结束工作撞车语境：同一条 MERGED 链路，语境写 session-end")
    void sessionEndCompletesWithMergesTrailer(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            long sessionId = s.stageSessionEndConflict(DOC);
            byte[] merged = mergedBytes();
            s.controller.mergeResolveFile(MergeScene.PROJECT_ID, DOC, "manual", DECISIONS_JSON,
                    "session-end", upload(merged), MergeScene.SESSION);

            s.sessionSvc.resolveSessionEnd(MergeScene.PROJECT_ID, sessionId,
                    Map.of(DOC, WorkSessionService.Resolution.MERGED), MergeScene.USER_ID, "韩泽伟");

            VersionEntry head = s.repoSvc.log(MergeScene.PROJECT_ID, "HEAD", 1).get(0);
            assertEquals("session-end", head.mergeContext());
            assertEquals(List.of(new VersionEntry.MergeSummary(DOC, "manual", DECISIONS, 0, 0)),
                    head.merges());
            assertArrayEquals(merged, s.read(DOC));
            assertTrue(s.pendingStore.all(MergeScene.PROJECT_ID).isEmpty());
        }
    }

    @Test
    @DisplayName("中止合并连待决记录一起清掉（下一次窗口不能读到上一次的记录）")
    void abortClearsPending(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            s.stageAdoptConflict(DOC);
            s.controller.mergeResolveFile(MergeScene.PROJECT_ID, DOC, "manual", DECISIONS_JSON,
                    "adopt", upload(mergedBytes()), MergeScene.SESSION);
            assertFalse(s.pendingStore.all(MergeScene.PROJECT_ID).isEmpty(), "前置：记录确实写下了");

            s.sessionSvc.abortAdopt(MergeScene.PROJECT_ID);

            assertTrue(s.pendingStore.all(MergeScene.PROJECT_ID).isEmpty());
            assertFalse(s.repoSvc.repositoryMerging(MergeScene.PROJECT_ID));
        }
    }

    // ---- 3. /status 的新字段与崩溃恢复 --------------------------------------

    @Test
    @DisplayName("关掉桌面端再开：/status 仍认得已经合好的文件，两侧信息也在")
    @SuppressWarnings("unchecked")
    void statusExposesDocumentMergesAndSidesAfterRestart(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            s.stageAdoptConflict(DOC);
            s.controller.mergeResolveFile(MergeScene.PROJECT_ID, DOC, "manual", DECISIONS_JSON,
                    "adopt", upload(mergedBytes()), MergeScene.SESSION);

            // 「重启」：换一套全新的服务实例读同一个 gitdir，内存缓存一点都不继承
            PendingMergeStore freshStore = new PendingMergeStore(s.repoSvc);
            MergeAnalysisService freshAnalysis = new MergeAnalysisService(s.repoSvc, freshStore);
            s.controller.setMergeAnalysisServiceForTest(freshAnalysis);
            s.controller.setPendingMergeStoreForTest(freshStore);

            Map<String, Object> data = (Map<String, Object>) s.controller
                    .status(MergeScene.PROJECT_ID, MergeScene.SESSION).getBody().get("data");
            Map<String, Object> conflict = (Map<String, Object>) data.get("adoptConflict");
            assertNotNull(conflict, "前置：仍停在采纳裁决窗口");

            assertNotNull(conflict.get("mergeBase"), "共同的上一版要给出来，前端三份字节全靠它");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) conflict.get("documentMerges");
            Map<String, Object> row = rows.stream()
                    .filter(r -> DOC.equals(r.get("path"))).findFirst().orElseThrow();
            assertEquals("DOCX", row.get("kind"));
            assertEquals("MERGED", row.get("state"), "重启后这一份不能被当成还没合过再跑一遍");

            Map<String, Object> sides = (Map<String, Object>) conflict.get("sides");
            Map<String, Object> main = (Map<String, Object>) sides.get("main");
            Map<String, Object> other = (Map<String, Object>) sides.get("other");
            assertEquals(s.repoSvc.resolveRef(MergeScene.PROJECT_ID, "HEAD"), main.get("sha"));
            assertEquals(s.repoSvc.mergeHeadRef(MergeScene.PROJECT_ID), other.get("sha"));
            assertEquals("主线的工作", main.get("title"));
            assertNotNull(other.get("authorName"));
            assertNotNull(other.get("when"));
        }
    }

    @Test
    @DisplayName("逐处分析端点：同一份文件给出冲突段的三栏文字")
    @SuppressWarnings("unchecked")
    void analysisEndpointReturnsOverlapsForThePath(@TempDir Path tmp) throws Exception {
        try (MergeScene s = new MergeScene(tmp)) {
            s.stageAdoptConflict(DOC);

            Map<String, Object> body = s.controller
                    .mergeAnalysis(MergeScene.PROJECT_ID, DOC, MergeScene.SESSION).getBody();
            Analysis a = (Analysis) body.get("data");

            assertEquals(MergeKind.DOCX, a.kind());
            assertEquals(MergeDecision.AUTO, a.decision(), "两边改的是互不相邻的段落");
            assertEquals(MergeScene.BASE_PARAGRAPHS.size(), a.baseUnits().size());
            assertFalse(a.plan().otherChunks().isEmpty());
        }
    }
}
