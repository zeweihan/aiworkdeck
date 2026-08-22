package com.checkba.service.evidence;

import com.checkba.model.entity.EvidenceLink;
import com.checkba.model.entity.EvidenceLinkTarget;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.Tag;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.EvidenceLinkTargetRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.TagRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.evidence.EvidenceVerifyViews.BatchQuery;
import com.checkba.service.evidence.EvidenceVerifyViews.BatchResult;
import com.checkba.service.evidence.EvidenceVerifyViews.LinkVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EvidenceVerifyService 契约（dev-board#116）：判定结果落到 target（relation/confidence/verify_json）、
 * 不动 EvidenceLink 状态机、缺文本 → unverifiable、批量的上限/续跑/取消、鉴权与 IDOR。
 * 全部 Repository 与 ProjectMemberService 为 mock，不起 Spring 上下文。
 */
class EvidenceVerifyServiceTest {

    static final long PID = 1L;
    static final long UID = 9L;
    static final long DOC = 10L;
    /** 真实在用的 18 位码，校验位由 GB 32100-2015 权重表独立算过。 */
    static final String USCC_A = "91330100799655058B";
    static final String USCC_B = "914403001922038216";

    EvidenceLinkRepository links = mock(EvidenceLinkRepository.class);
    EvidenceLinkTargetRepository targets = mock(EvidenceLinkTargetRepository.class);
    ProjectFileRepository files = mock(ProjectFileRepository.class);
    TagRepository tags = mock(TagRepository.class);
    ProjectMemberService members = mock(ProjectMemberService.class);
    EvidenceTextExtractor extractor = mock(EvidenceTextExtractor.class);
    EvidenceVerifyService svc;

    final Map<Long, EvidenceLink> linkStore = new HashMap<>();
    final Map<Long, List<EvidenceLinkTarget>> targetStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        svc = new EvidenceVerifyService(links, targets, files, tags, members, extractor, new ObjectMapper());
        when(members.hasReadPermission(PID, UID)).thenReturn(true);
        when(members.hasWritePermission(PID, UID)).thenReturn(true);
        when(tags.findByProjectId(PID)).thenReturn(List.of());
        when(links.findByProjectIdAndLinkKey(anyLong(), any())).thenAnswer(inv -> linkStore.values().stream()
                .filter(l -> l.getProjectId().equals(inv.getArgument(0)) && l.getLinkKey().equals(inv.getArgument(1)))
                .findFirst());
        when(links.findByProjectIdAndDocFileIdOrderByIdAsc(anyLong(), anyLong()))
                .thenAnswer(inv -> linkStore.values().stream()
                        .filter(l -> l.getProjectId().equals(inv.getArgument(0))
                                && l.getDocFileId().equals(inv.getArgument(1)))
                        .sorted((a, b) -> Long.compare(a.getId(), b.getId())).toList());
        when(links.findByProjectIdAndDocFileIdAndSectionPathStartingWithOrderByIdAsc(anyLong(), anyLong(), any()))
                .thenAnswer(inv -> linkStore.values().stream()
                        .filter(l -> l.getProjectId().equals(inv.getArgument(0))
                                && l.getDocFileId().equals(inv.getArgument(1))
                                && l.getSectionPath() != null
                                && l.getSectionPath().startsWith(inv.getArgument(2)))
                        .sorted((a, b) -> Long.compare(a.getId(), b.getId())).toList());
        when(links.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(targets.findByLinkIdOrderBySortOrderAscIdAsc(anyLong()))
                .thenAnswer(inv -> targetStore.getOrDefault(inv.<Long>getArgument(0), List.of()));
        when(targets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(files.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(fileStore.get(inv.<Long>getArgument(0))));
        when(files.findAllById(any())).thenAnswer(inv -> {
            List<ProjectFile> out = new ArrayList<>();
            for (Long id : inv.<Iterable<Long>>getArgument(0)) {
                ProjectFile f = fileStore.get(id);
                if (f != null) out.add(f);
            }
            return out;
        });
    }

    @AfterEach
    void tearDown() {
        svc.shutdown();
    }

    final Map<Long, ProjectFile> fileStore = new HashMap<>();

    // ---------------------------------------------------------------- 单条

    @Test
    @DisplayName("全部要素命中 → target 落 supports/100，link 状态不动")
    void supportsWritesRelationAndConfidence() {
        seed("EVID_A", "统一社会信用代码 " + USCC_A + "。", EvidenceLink.STATUS_UNVERIFIED, 11L);
        when(extractor.textOf(any(), anyLong())).thenReturn("营业执照 统一社会信用代码 " + USCC_A);

        LinkVerdict v = svc.verifyLink(UID, PID, "EVID_A");

        assertEquals(EvidenceChecks.VERDICT_SUPPORTS, v.verdict());
        EvidenceLinkTarget t = targetStore.get(100L).get(0);
        assertEquals("supports", t.getRelation());
        assertEquals((short) 100, t.getConfidence());
        // P0 状态机不动：核查不改 status（unverified→active 只能由 worker 核对与用户动作驱动）
        assertEquals(EvidenceLink.STATUS_UNVERIFIED, linkStore.get(100L).getStatus());
    }

    @Test
    @DisplayName("verify_json 形状：checkedAt + verdict + checks[{kind,expected,found,ok}]")
    void verifyJsonShape() throws Exception {
        seed("EVID_A", "统一社会信用代码 " + USCC_A + "。", EvidenceLink.STATUS_ACTIVE, 11L);
        when(extractor.textOf(any(), anyLong())).thenReturn("统一社会信用代码 " + USCC_A);

        svc.verifyLink(UID, PID, "EVID_A");

        JsonNode j = new ObjectMapper().readTree(targetStore.get(100L).get(0).getVerifyJson());
        assertNotNull(j.get("checkedAt").asText());
        assertEquals(EvidenceChecks.VERDICT_SUPPORTS, j.get("verdict").asText());
        JsonNode c = j.get("checks").get(0);
        assertEquals(EvidenceChecks.KIND_USCC, c.get("kind").asText());
        assertEquals(USCC_A, c.get("expected").asText());
        assertEquals(USCC_A, c.get("found").asText());
        assertTrue(c.get("ok").asBoolean());
    }

    @Test
    @DisplayName("底稿里是另一个代码 → contradicts/0；link 状态仍不动")
    void contradictsWritesRelation() {
        seed("EVID_A", "统一社会信用代码 " + USCC_A + "。", EvidenceLink.STATUS_ACTIVE, 11L);
        when(extractor.textOf(any(), anyLong())).thenReturn("统一社会信用代码 " + USCC_B);

        LinkVerdict v = svc.verifyLink(UID, PID, "EVID_A");

        assertEquals(EvidenceChecks.VERDICT_CONTRADICTS, v.verdict());
        assertEquals("contradicts", targetStore.get(100L).get(0).getRelation());
        assertEquals((short) 0, targetStore.get(100L).get(0).getConfidence());
        assertEquals(EvidenceLink.STATUS_ACTIVE, linkStore.get(100L).getStatus());
    }

    @Test
    @DisplayName("底稿取不到文本 → unverifiable：relation 保持原样、confidence 置空，绝不判 contradicts")
    void noTextIsUnverifiableNotContradiction() throws Exception {
        seed("EVID_A", "统一社会信用代码 " + USCC_A + "。", EvidenceLink.STATUS_ACTIVE, 11L);
        targetStore.get(100L).get(0).setRelation("partial");
        targetStore.get(100L).get(0).setConfidence((short) 77);
        when(extractor.textOf(any(), anyLong())).thenReturn(null);

        LinkVerdict v = svc.verifyLink(UID, PID, "EVID_A");

        assertEquals(EvidenceChecks.VERDICT_UNVERIFIABLE, v.verdict());
        EvidenceLinkTarget t = targetStore.get(100L).get(0);
        assertEquals("partial", t.getRelation(), "取不到文本不该改写 relation");
        assertNull(t.getConfidence(), "判不了就不打分");
        JsonNode j = new ObjectMapper().readTree(t.getVerifyJson());
        assertEquals(EvidenceChecks.VERDICT_UNVERIFIABLE, j.get("verdict").asText());
        assertTrue(j.get("checks").isEmpty());
        assertNotNull(j.get("note"), "要说明为什么判不了（底稿读不出文字）");
    }

    @Test
    @DisplayName("陈述里没有可机器校验的要素 → unverifiable，同样不改 relation")
    void nothingCheckableIsUnverifiable() {
        seed("EVID_A", "公司经营情况良好。", EvidenceLink.STATUS_ACTIVE, 11L);
        when(extractor.textOf(any(), anyLong())).thenReturn("底稿正文若干");

        assertEquals(EvidenceChecks.VERDICT_UNVERIFIABLE, svc.verifyLink(UID, PID, "EVID_A").verdict());
        assertEquals("supports", targetStore.get(100L).get(0).getRelation());
    }

    @Test
    @DisplayName("PARTY 标签参与主体核查，NORMAL/ISSUE 标签不参与")
    void partyTagsFeedTheCheck() {
        seed("EVID_A", "北京京微资易科技有限公司持股 51%。", EvidenceLink.STATUS_ACTIVE, 11L);
        when(tags.findByProjectId(PID)).thenReturn(List.of(
                tag(1L, "北京京微资易科技有限公司", "PARTY", "别名：京微资易"),
                tag(2L, "持股 51%", "ISSUE", null)));
        when(extractor.textOf(any(), anyLong())).thenReturn("京微资易 出具的股东决定，比例 51.00%");

        LinkVerdict v = svc.verifyLink(UID, PID, "EVID_A");
        assertEquals(EvidenceChecks.VERDICT_SUPPORTS, v.verdict());
        assertTrue(v.targets().get(0).checks().stream()
                .anyMatch(c -> EvidenceChecks.KIND_PARTY.equals(c.kind()) && Boolean.TRUE.equals(c.ok())));
    }

    @Test
    @DisplayName("幂等：连跑两次结论一致，verify_json 整条覆盖不追加")
    void idempotentRerun() throws Exception {
        seed("EVID_A", "统一社会信用代码 " + USCC_A + "。", EvidenceLink.STATUS_ACTIVE, 11L);
        when(extractor.textOf(any(), anyLong())).thenReturn("统一社会信用代码 " + USCC_A);

        svc.verifyLink(UID, PID, "EVID_A");
        String first = targetStore.get(100L).get(0).getVerifyJson();
        svc.verifyLink(UID, PID, "EVID_A");
        String second = targetStore.get(100L).get(0).getVerifyJson();

        ObjectMapper om = new ObjectMapper();
        assertEquals(om.readTree(first).get("checks"), om.readTree(second).get("checks"));
        assertEquals(om.readTree(first).get("verdict"), om.readTree(second).get("verdict"));
    }

    @Test
    @DisplayName("鉴权：只读成员不能核查（核查是写操作）")
    void readOnlyMemberRejected() {
        seed("EVID_A", "统一社会信用代码 " + USCC_A, EvidenceLink.STATUS_ACTIVE, 11L);
        when(members.hasWritePermission(PID, 8L)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> svc.verifyLink(8L, PID, "EVID_A"));
    }

    @Test
    @DisplayName("IDOR：别家项目的 linkKey 查不到")
    void crossProjectLinkNotFound() {
        seed("EVID_A", "统一社会信用代码 " + USCC_A, EvidenceLink.STATUS_ACTIVE, 11L);
        when(members.hasWritePermission(2L, UID)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> svc.verifyLink(UID, 2L, "EVID_A"));
    }

    @Test
    @DisplayName("target 指向的文件已被软删/不存在 → unverifiable，不炸整条")
    void deletedTargetFileIsUnverifiable() {
        seed("EVID_A", "统一社会信用代码 " + USCC_A, EvidenceLink.STATUS_ACTIVE, 11L);
        fileStore.remove(11L);
        assertEquals(EvidenceChecks.VERDICT_UNVERIFIABLE, svc.verifyLink(UID, PID, "EVID_A").verdict());
    }

    // ---------------------------------------------------------------- 批量

    @Test
    @DisplayName("批量：超过 limit 就停下并回 nextOffset，原样再调即续跑")
    void batchStopsAtLimitAndReportsCursor() {
        for (int i = 0; i < 5; i++) {
            seedAt(100L + i, "EVID_" + i, "统一社会信用代码 " + USCC_A, EvidenceLink.STATUS_ACTIVE, 11L, "一/（一）");
        }
        when(extractor.textOf(any(), anyLong())).thenReturn("统一社会信用代码 " + USCC_A);

        BatchResult r = svc.verifyBatch(UID, PID, new BatchQuery(DOC, null, null, 0, 2));
        assertEquals(5, r.total());
        assertEquals(2, r.processed());
        assertEquals(2, r.nextOffset());
        assertEquals(2, r.verdicts().get(EvidenceChecks.VERDICT_SUPPORTS));

        BatchResult r2 = svc.verifyBatch(UID, PID, new BatchQuery(DOC, null, null, r.nextOffset(), 10));
        assertEquals(3, r2.processed());
        assertNull(r2.nextOffset());
    }

    @Test
    @DisplayName("批量：limit 超过硬上限被夹到上限")
    void batchLimitCapped() {
        for (int i = 0; i < 3; i++) {
            seedAt(100L + i, "EVID_" + i, "无可核要素", EvidenceLink.STATUS_ACTIVE, 11L, "一");
        }
        when(extractor.textOf(any(), anyLong())).thenReturn("底稿");
        BatchResult r = svc.verifyBatch(UID, PID, new BatchQuery(DOC, null, null, 0, 100000));
        assertEquals(3, r.processed());
        assertTrue(EvidenceVerifyService.MAX_BATCH_LINKS <= 500, "上限得是个能在一次请求里跑完的数");
    }

    @Test
    @DisplayName("批量：sectionPath 前缀筛选")
    void batchFiltersBySection() {
        seedAt(100L, "EVID_0", "无", EvidenceLink.STATUS_ACTIVE, 11L, "一/（一）");
        seedAt(101L, "EVID_1", "无", EvidenceLink.STATUS_ACTIVE, 11L, "二/（一）");
        when(extractor.textOf(any(), anyLong())).thenReturn("底稿");
        BatchResult r = svc.verifyBatch(UID, PID, new BatchQuery(DOC, "一/", null, 0, 50));
        assertEquals(1, r.total());
        assertEquals("EVID_0", r.links().get(0).linkKey());
    }

    @Test
    @DisplayName("批量：取消后停在中途，cancelled=true 且回 nextOffset")
    void batchCancellable() throws Exception {
        // 每条挂不同底稿，否则批内文本缓存会让后五条秒过，取消根本来不及插进去
        for (int i = 0; i < 6; i++) {
            seedAt(100L + i, "EVID_" + i, "统一社会信用代码 " + USCC_A, EvidenceLink.STATUS_ACTIVE, 11L + i, "一");
        }
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(extractor.textOf(any(), anyLong())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) started.countDown();
            Thread.sleep(60);
            return "统一社会信用代码 " + USCC_A;
        });

        Thread canceller = new Thread(() -> {
            try {
                started.await(2, TimeUnit.SECONDS);
                Thread.sleep(80);
                svc.cancelBatch(UID, PID);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        });
        canceller.start();
        BatchResult r = svc.verifyBatch(UID, PID, new BatchQuery(DOC, null, null, 0, 50));
        canceller.join();

        assertTrue(r.cancelled(), "应当被取消");
        assertTrue(r.processed() < 6, "取消要真的截断，实际跑了 " + r.processed());
        assertNotNull(r.nextOffset());
    }

    @Test
    @DisplayName("取消：没有在跑的批次时返回 false，不报错")
    void cancelWithoutRunningBatch() {
        assertFalse(svc.cancelBatch(UID, PID));
    }

    @Test
    @DisplayName("批量：同一份底稿在一批里只抽一次文本")
    void draftTextCachedWithinBatch() {
        for (int i = 0; i < 4; i++) {
            seedAt(100L + i, "EVID_" + i, "统一社会信用代码 " + USCC_A, EvidenceLink.STATUS_ACTIVE, 11L, "一");
        }
        AtomicInteger calls = new AtomicInteger();
        when(extractor.textOf(any(), anyLong())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return "统一社会信用代码 " + USCC_A;
        });
        svc.verifyBatch(UID, PID, new BatchQuery(DOC, null, null, 0, 50));
        assertEquals(1, calls.get(), "四条 link 挂同一份底稿，只该抽一次");
    }

    @Test
    @DisplayName("批量：非成员直接拒")
    void batchRejectsNonMember() {
        when(members.hasWritePermission(PID, 7L)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,
                () -> svc.verifyBatch(7L, PID, new BatchQuery(DOC, null, null, 0, 10)));
    }

    @Test
    @DisplayName("批量：docFileId 必填")
    void batchRequiresDocFileId() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.verifyBatch(UID, PID, new BatchQuery(null, null, null, 0, 10)));
    }

    // ---------------------------------------------------------------- fixtures

    private void seed(String key, String anchor, String status, long targetFileId) {
        seedAt(100L, key, anchor, status, targetFileId, "一/（一）");
    }

    private void seedAt(long id, String key, String anchor, String status, long targetFileId, String sectionPath) {
        EvidenceLink l = new EvidenceLink();
        l.setId(id);
        l.setProjectId(PID);
        l.setDocFileId(DOC);
        l.setLinkKey(key);
        l.setAnchorText(anchor);
        l.setSectionPath(sectionPath);
        l.setStatus(status);
        linkStore.put(id, l);

        EvidenceLinkTarget t = new EvidenceLinkTarget();
        t.setId(200L + id);
        t.setLinkId(id);
        t.setFileId(targetFileId);
        t.setRelation("supports");
        targetStore.put(id, new ArrayList<>(List.of(t)));

        ProjectFile f = new ProjectFile();
        f.setId(targetFileId);
        f.setProjectId(PID);
        f.setName("执照.pdf");
        f.setFileType("pdf");
        f.setFilePath("p/执照.pdf");
        fileStore.put(targetFileId, f);
    }

    private static Tag tag(long id, String name, String type, String description) {
        Tag t = new Tag();
        t.setId(id);
        t.setProjectId(PID);
        t.setName(name);
        t.setType(type);
        t.setDescription(description);
        return t;
    }
}
