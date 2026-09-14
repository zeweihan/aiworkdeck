// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import com.checkba.version.MergeOutcome;
import com.checkba.version.ProjectRepoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 逐段溯源（dev-board#632，设计稿 §4.7）。
 *
 * <p>这些用例钉的是**归属**，不是接口形状：一段文字最后是哪一版改的。
 * 最容易写错、也最值钱的一条是合并提交——来自另一侧的段落必须归**对方那一版**，
 * 而不是归「我按下确认那一刻的合并提交」。少了第二父继承，一次取回就能把
 * 对方名下的几十段全部改签成我自己，律师再也看不出这份合同里哪几段是对方加的。
 */
class ProvenanceServiceTest {

    private static final long PID = 7L;
    private static final long UID = 1L;
    private static final String NAME = "韩泽伟";
    private static final String EMAIL = "hanzewei@local.aiworkdeck.local";

    // ------------------------------------------------------------------ 用例

    @Test
    void linearHistoryAttributesLastEditor(@TempDir Path root) throws Exception {
        ProjectRepoService repo = svc(root);
        init(root, repo);

        writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方", "第三条 丙方");
        String v0 = commit(repo, "第一稿", "session");

        writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方（已核对）", "第三条 丙方");
        String v1 = commit(repo, "核对第二条", "session");

        Map<String, ProvenanceUnit> units = unitsOf(service(repo).provenance(PID, UID, "合同.docx", "HEAD"));

        assertEquals(v0, units.get("p0").sha(), "没改过的段落应该留在它最后被改的那一版上");
        assertEquals(v1, units.get("p1").sha(), "改过的段落归本版");
        assertEquals(v0, units.get("p2").sha());
        assertEquals("核对第二条", units.get("p1").title());
        assertEquals(NAME, units.get("p1").authorName());
        assertEquals(v1.substring(0, 7), units.get("p1").shortId());
        assertNotNull(units.get("p1").when());
        assertNotNull(units.get("p1").textHash());
    }

    /**
     * 合并提交：{@code p0} 只有第一父改过、{@code p2} 只有第二父改过、{@code p1} 两边都没动。
     * 去掉「先第一父、再第二父」里的第二父那一步，{@code p2} 会归到合并提交自己头上 —— 转红。
     */
    @Test
    void mergeCommitInheritsFromSecondParent(@TempDir Path root) throws Exception {
        ProjectRepoService repo = svc(root);
        init(root, repo);

        writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方", "第三条 丙方");
        commit(repo, "第一稿", "session");

        // 主线这一侧改第一条
        writeDoc(root, "合同.docx", "第一条 甲方（我改的）", "第二条 乙方", "第三条 丙方");
        String mine = commit(repo, "我改第一条", "session");

        // 另一条线从第一稿分出去改第三条
        repo.createBranch(PID, "draft-1", mine + "^");
        repo.checkoutBranch(PID, "draft-1");
        writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方", "第三条 丙方（对方改的）");
        String theirs = commit(repo, "对方改第三条", "session");

        repo.checkoutBranch(PID, "master");
        MergeOutcome outcome = repo.mergeNoCommit(PID, "draft-1", "取回最新稿", NAME, EMAIL);
        assertFalse(outcome.conflictingPaths().isEmpty(), "两边都改过的 docx 在字节层面必然冲突");

        // 律师逐处裁决之后的最终内容：两边的改动都留下
        writeDoc(root, "合同.docx", "第一条 甲方（我改的）", "第二条 乙方", "第三条 丙方（对方改的）");
        String merged = repo.commitMergeResolution(PID, "取回最新稿", NAME, EMAIL);

        Map<String, ProvenanceUnit> units = unitsOf(service(repo).provenance(PID, UID, "合同.docx", "HEAD"));

        assertEquals(mine, units.get("p0").sha(), "只有主线改过的段落归主线那一版");
        assertEquals(theirs, units.get("p2").sha(),
                "来自另一侧的段落必须归对方那一版，不能归合并提交");
        assertNotEquals(merged, units.get("p2").sha());
        assertNotEquals(merged, units.get("p1").sha(), "两边都没动的段落不该被合并提交吃掉");
    }

    @Test
    void followsRename(@TempDir Path root) throws Exception {
        ProjectRepoService repo = svc(root);
        init(root, repo);

        writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方");
        String v0 = commit(repo, "第一稿", "session");

        // 改名，内容一字未动（同一份字节，重命名才认得出来）
        byte[] same = Files.readAllBytes(work(root).resolve("合同.docx"));
        Files.delete(work(root).resolve("合同.docx"));
        Files.write(work(root).resolve("合同-终稿.docx"), same);
        String renamed = commit(repo, "改个名字", "session");

        writeDoc(root, "合同-终稿.docx", "第一条 甲方", "第二条 乙方（已核对）");
        String v2 = commit(repo, "核对第二条", "session");

        Map<String, ProvenanceUnit> units =
                unitsOf(service(repo).provenance(PID, UID, "合同-终稿.docx", "HEAD"));

        assertEquals(v0, units.get("p0").sha(), "改名不是改内容，第一段仍归第一稿那一版");
        assertNotEquals(renamed, units.get("p0").sha());
        assertEquals(v2, units.get("p1").sha());
    }

    /**
     * 缓存命中就不再重算：把缓存文件里的归属改成一个假 sha，再问一次应当原样读回来。
     * （生产里没人会去篡改它；这是「有没有真的走缓存」唯一不靠计时的判据。）
     */
    @Test
    void cacheHitSkipsRecompute(@TempDir Path root) throws Exception {
        ProjectRepoService repo = svc(root);
        init(root, repo);

        writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方");
        String v0 = commit(repo, "第一稿", "session");
        writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方（已核对）");
        String v1 = commit(repo, "核对第二条", "session");

        assertEquals(v1, unitsOf(service(repo).provenance(PID, UID, "合同.docx", "HEAD")).get("p1").sha());

        // 把 HEAD 那一版的缓存里「第二段是 v1 改的」改写成「是 v0 改的」——真去重算一定是 v1，
        // 读回 v0 就说明这一趟根本没重算。（换成一个真的存在的版本，免得断言被
        // 「查不到这一版 → 显示更早的版本」那条兜底路径接管，验不到缓存本身。）
        Path cacheFile = cacheFileFor(repo.gitDir(PID).resolve("awd-cache/provenance"), v1);
        Files.writeString(cacheFile, Files.readString(cacheFile).replace("\"" + v1 + "\"", "\"" + v0 + "\""));

        Map<String, ProvenanceUnit> again = unitsOf(service(repo).provenance(PID, UID, "合同.docx", "HEAD"));
        assertEquals(v0, again.get("p1").sha(), "命中缓存就该原样用缓存里的归属，不重算");
    }

    /**
     * 回溯上限：更早的版本一律记成「更早的版本」（{@code sha=null}）并置 truncated。
     * 生产上限是 500 版（这里钉住那个常量），用例本身把上限调小，
     * 免得为了验一条规则去造 500 笔提交。
     */
    @Test
    void truncatesAt500(@TempDir Path root) throws Exception {
        assertEquals(500, ProvenanceService.MAX_HISTORY, "回溯上限是产品口径，改它要连同界面文案一起想清楚");

        ProjectRepoService repo = svc(root);
        init(root, repo);

        writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方");
        commit(repo, "第一稿", "session");
        for (int i = 1; i <= 4; i++) {
            writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方（第 " + i + " 次核对）");
            commit(repo, "第 " + i + " 次核对", "session");
        }

        ProvenanceService svc = service(repo);
        svc.setMaxHistoryForTest(2);
        Map<String, Object> res = svc.provenance(PID, UID, "合同.docx", "HEAD");
        Map<String, ProvenanceUnit> units = unitsOf(res);

        assertEquals(Boolean.TRUE, res.get("truncated"));
        assertNull(units.get("p0").sha(), "回溯到头的段落只说「更早的版本」，不许硬安给某一版");
        assertNotNull(units.get("p0").title());
        assertNotNull(units.get("p1").sha(), "窗口之内的改动照常归属");
    }

    @Test
    void autoCommitIsAttributedWithAutoType(@TempDir Path root) throws Exception {
        ProjectRepoService repo = svc(root);
        init(root, repo);

        writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方");
        commit(repo, "第一稿", "session");
        writeDoc(root, "合同.docx", "第一条 甲方", "第二条 乙方（随手改的）");
        String auto = commit(repo, "随手改了一处", "auto");

        Map<String, ProvenanceUnit> units = unitsOf(service(repo).provenance(PID, UID, "合同.docx", "HEAD"));

        assertEquals(auto, units.get("p1").sha());
        assertEquals("auto", units.get("p1").type(), "自动存档要认得出来，界面上显示「自动存档」而不是人名");
        assertEquals("session", units.get("p0").type());
    }

    @Test
    void xlsxCellsAndPptxSlides(@TempDir Path root) throws Exception {
        ProjectRepoService repo = svc(root);
        init(root, repo);

        Files.write(work(root).resolve("台账.xlsx"),
                MergeFixtures.xlsx("Sheet1", MergeFixtures.cells("A1", "注册资本", "B1", "100 万")));
        Files.write(work(root).resolve("汇报.pptx"),
                MergeFixtures.pptx(List.of("第一页", "第二页"), List.of("甲方情况", "乙方情况")));
        String v0 = commit(repo, "第一稿", "session");

        Files.write(work(root).resolve("台账.xlsx"),
                MergeFixtures.xlsx("Sheet1", MergeFixtures.cells("A1", "注册资本", "B1", "200 万")));
        Files.write(work(root).resolve("汇报.pptx"),
                MergeFixtures.pptx(List.of("第一页", "第二页"), List.of("甲方情况", "乙方情况（已核对）")));
        String v1 = commit(repo, "核对注册资本", "session");

        ProvenanceService svc = service(repo);

        Map<String, Object> xlsx = svc.provenance(PID, UID, "台账.xlsx", "HEAD");
        assertEquals("xlsx", xlsx.get("kind"));
        Map<String, ProvenanceUnit> cells = unitsOf(xlsx);
        assertEquals(v0, cells.get("Sheet1!A1").sha());
        assertEquals(v1, cells.get("Sheet1!B1").sha());

        Map<String, Object> pptx = svc.provenance(PID, UID, "汇报.pptx", "HEAD");
        assertEquals("pptx", pptx.get("kind"));
        Map<String, ProvenanceUnit> slides = unitsOf(pptx);
        assertEquals(v0, slides.get("s1").sha());
        assertEquals(v1, slides.get("s2").sha());
    }

    /**
     * {@code textHash} 是跨端契约：前端 {@code utils/provenanceAlign.js} 自带一份同口径的
     * 归一 + SHA-256（后端只回哈希不回原文），两边算出同一个值，画布上的段落才对得上后端的
     * units。任何一端动了归一口径或编码，这里当场转红。
     *
     * <p>下面三个期望值是 2026-09-14 用 node 跑
     * {@code hashUnitText()} 现取的（全角空格 / 制表符 / 换行 / 首尾空白各一例）。
     */
    @Test
    void textHashMatchesFrontendNormalizeContract() {
        assertEquals("第一条 甲方应当于 2026 年 9 月 14 日前 交付全部材料。",
                DocxUnitReader.normalize("第一条　甲方应当于  2026 年\t9 月 14 日前\n交付全部材料。"));

        assertEquals("67fbc19460aafb186d22cefbc831d933a2b5de862f1fbff6b54951f2c435d938",
                textHash("第一条　甲方应当于  2026 年\t9 月 14 日前\n交付全部材料。"));
        assertEquals("1a0886235c2da5c98cdd85f560ea2947892276970aa7f10269d53dcaf3e791ca",
                textHash("  第二条 乙方（已核对）  "));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                textHash(""));
    }

    private static String textHash(String text) {
        return ThreeWayAnalyzer.sha256Hex(DocxUnitReader.normalize(text));
    }

    // ------------------------------------------------------------------ 夹具

    private ProjectRepoService svc(Path root) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        return new ProjectRepoService(new ProjectStorageResolver(props, null));
    }

    private ProvenanceService service(ProjectRepoService repo) {
        return new ProvenanceService(repo);
    }

    private Path work(Path root) {
        return root.resolve("projects/" + PID);
    }

    private void init(Path root, ProjectRepoService repo) throws Exception {
        Files.createDirectories(work(root));
        Files.writeString(work(root).resolve("说明.txt"), "占位");
        repo.init(PID, NAME, EMAIL);
    }

    private void writeDoc(Path root, String name, String... paragraphs) throws Exception {
        Files.write(work(root).resolve(name), MergeFixtures.docx(paragraphs));
    }

    private String commit(ProjectRepoService repo, String message, String kind) {
        String sha = repo.commitAll(PID, message, kind, null, NAME, EMAIL);
        assertNotNull(sha, "这一笔应该真的落版了: " + message);
        return sha;
    }

    @SuppressWarnings("unchecked")
    private Map<String, ProvenanceUnit> unitsOf(Map<String, Object> response) {
        assertNotEquals(Boolean.TRUE, response.get("computing"), "用例规模下不该超时");
        Map<String, ProvenanceUnit> byKey = new LinkedHashMap<>();
        for (ProvenanceUnit u : (List<ProvenanceUnit>) response.get("units")) {
            byKey.put(u.key(), u);
        }
        return byKey;
    }

    private Path cacheFileFor(Path dir, String sha) throws Exception {
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().equals(sha + ".json")).findFirst()
                    .orElseThrow(() -> new AssertionError("没有写出 " + sha + " 的缓存文件"));
        }
    }
}
