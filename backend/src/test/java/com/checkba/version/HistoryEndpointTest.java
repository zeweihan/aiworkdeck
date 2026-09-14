// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * {@code GET /api/projects/{id}/version/history} —— 程序员那份 {@code git log --graph --all}
 * 换成律师的词（spec 2026-09-14 §2.4，dev-board#624）。
 *
 * <p>用真仓库跑：类型判定、引用标签、「还没取回的那几版」、自动存档折叠、四种筛选、
 * 游标翻页、变更计数、两边各领先几版。假仓库只能证明我把 map 拼对了，
 * 证明不了旗标传播与 TOPO 排序这些真正容易错的地方。
 */
class HistoryEndpointTest {

    private static final long PROJECT = 7L;
    private static final long ME = 1L;
    private static final String MY_EMAIL = "awd_hanzewei@collab.aiworkdeck.local";

    private Path root;
    private ProjectRepoService repoSvc;
    private VersionController controller;
    private WorkSessionService sessionService;
    private ProjectFileService projectFileService;
    private CloudSyncService cloudSyncService;
    private MockedStatic<AuthController> auth;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(new ProjectStorageResolver(props, null));
        repoSvc.init(PROJECT, "韩泽伟", MY_EMAIL);

        sessionService = mock(WorkSessionService.class);
        when(sessionService.listDrafts(PROJECT)).thenReturn(List.of());
        when(sessionService.activeDraftOnBranch(PROJECT)).thenReturn(Optional.empty());
        projectFileService = mock(ProjectFileService.class);
        cloudSyncService = mock(CloudSyncService.class);
        when(cloudSyncService.cloudStatus(anyLong(), any())).thenReturn(Map.of("linked", false));

        ProjectMemberService members = mock(ProjectMemberService.class);
        when(members.hasReadPermission(PROJECT, ME)).thenReturn(true);
        when(members.isClient(PROJECT, ME)).thenReturn(false);

        controller = new VersionController(repoSvc, sessionService, members,
                mock(UserService.class), projectFileService,
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(VersionLifecycleService.class));
        ReflectionTestUtils.setField(controller, "cloudSyncService", cloudSyncService);

        auth = mockStatic(AuthController.class);
        auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(ME);
    }

    @AfterEach
    void tearDown() {
        if (auth != null) auth.close();
    }

    // ---------- fixture 小工具 ----------

    /** 在工作区改一份文件再落一笔提交，返回 sha。 */
    private String commit(String file, String content, String message, String kind) throws Exception {
        Files.writeString(root.resolve("projects/7").resolve(file), content);
        String sha = repoSvc.commitAll(PROJECT, message, kind, null, "韩泽伟", MY_EMAIL);
        assertNotNull(sha, "这一笔应该真的产生了版本：" + message);
        return sha;
    }

    private String commit(String content, String message, String kind) throws Exception {
        return commit("合同.txt", content, message, kind);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map<String, Object>> res) {
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertEquals(0, body.get("code"));
        return (Map<String, Object>) body.get("data");
    }

    private Map<String, Object> history() {
        return history(100, null, null, null, null, null, null, false);
    }

    private Map<String, Object> history(int limit, String cursor, String author, Long fileId,
                                        String q, String from, String to, boolean includeAuto) {
        return data(controller.history(PROJECT, limit, cursor, author, fileId, q, from, to,
                includeAuto, "sess"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entries(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("entries");
    }

    private Map<String, Object> row(Map<String, Object> data, String sha) {
        return entries(data).stream().filter(e -> sha.equals(e.get("sha"))).findFirst()
                .orElseThrow(() -> new AssertionError("历史里没有这一版: " + sha
                        + "，实际有 " + entries(data).stream().map(e -> e.get("title")).toList()));
    }

    /** 案件库：裸仓 + 把本机现有历史先推上去，两边这时是齐的。 */
    private void linkCaseLibrary() throws Exception {
        Path bare = Files.createTempDirectory("case-library");
        repoSvc.setRemoteOrigin(PROJECT, BareHub.init(bare));
        assertTrue(repoSvc.pushMainlineToOrigin(PROJECT, "awd_hanzewei", "awdt_test").pushed());
        repoSvc.fetchFromOrigin(PROJECT, "awd_hanzewei", "awdt_test");
    }

    /** 同事在案件库上交了一版，本机随后 fetch 一次（本机主线并没有前进）。 */
    private String colleagueSubmits(String content) throws Exception {
        Path peerDir = Files.createTempDirectory("peer");
        String sha;
        try (Git peer = Git.cloneRepository()
                .setURI(repoSvc.remoteOriginUrl(PROJECT))
                .setDirectory(peerDir.toFile()).setBranch("master").call()) {
            Files.writeString(peerDir.resolve("意见书.txt"), content);
            peer.add().addFilepattern(".").call();
            sha = peer.commit().setMessage(content + "\n\nX-AWD-Kind: session")
                    .setAuthor("律师乙", "awd_lawyer_b@collab.aiworkdeck.local").call().getName();
            peer.push().setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master")).call();
        }
        repoSvc.fetchFromOrigin(PROJECT, "awd_hanzewei", "awdt_test");
        return sha;
    }

    private void haveOneDraft(String branch, String name) {
        WorkSession d = new WorkSession();
        d.setId(3L);
        d.setProjectId(PROJECT);
        d.setTitle(name);
        d.setBranchName(branch);
        d.setSessionType(WorkSession.SessionType.DRAFT);
        d.setStatus(WorkSession.Status.ACTIVE);
        when(sessionService.listDrafts(PROJECT)).thenReturn(List.of(d));
    }

    // ---------- 类型判定 ----------

    @Test
    @DisplayName("七类动作各自认得出来，且判定只看消息模板不看 kind")
    void classifiesAllSevenTypes() throws Exception {
        String initial = repoSvc.log(PROJECT, "HEAD", 1).get(0).sha();
        String auto = commit("改了一句", "修改了《合同》", "auto");
        String session = commit("又改一句", "核对注册资本", "session");
        String revert = commit("回到初稿", "退回到早先的版本", "session");
        String adopt = commit("并进来", "采纳：试验稿", "session");
        String pull = commit("同步下来", "取回最新稿", "session");
        String upgrade = commit("升一版", "升级版本记录格式", "session");

        Map<String, Object> data = history(100, null, null, null, null, null, null, true);
        assertEquals("initial", row(data, initial).get("type"));
        assertEquals("auto", row(data, auto).get("type"));
        assertEquals("session", row(data, session).get("type"));
        assertEquals("revert", row(data, revert).get("type"));
        assertEquals("adopt", row(data, adopt).get("type"));
        assertEquals("pull", row(data, pull).get("type"));
        assertEquals("upgrade", row(data, upgrade).get("type"));
        // 「初始版本」「退回」这些身上的 kind 都是 session——先看 kind 就会全归成「结束工作」
        assertEquals("session", row(data, revert).get("kind"));
    }

    // ---------- 引用标签 ----------

    @Test
    @DisplayName("主线 / 稿 / 案件库 / 本机四种标签只打在各自那条线的尖端上")
    void tagsRefsOnBranchTips() throws Exception {
        linkCaseLibrary();
        String oldMain = repoSvc.log(PROJECT, "HEAD", 1).get(0).sha();
        repoSvc.createBranch(PROJECT, "draft/1000", "master");
        repoSvc.checkoutBranch(PROJECT, "draft/1000");
        String draftTip = commit("稿上的改动", "试验方案", "session");
        repoSvc.checkoutBranch(PROJECT, repoSvc.mainBranch());
        haveOneDraft("draft/1000", "试验稿");
        String mainTip = commit("主线继续", "补充意见书节选", "session");

        Map<String, Object> data = history();

        assertEquals(Set.of("mainline", "local"), refTypes(row(data, mainTip)));
        assertEquals(Set.of("draft"), refTypes(row(data, draftTip)));
        assertEquals("试验稿", refName(row(data, draftTip), "draft"));
        assertEquals("主线", refName(row(data, mainTip), "mainline"));
        // 案件库还停在两边齐平那一版上
        assertEquals(Set.of("remote"), refTypes(row(data, oldMain)));
        assertEquals("案件库", refName(row(data, oldMain), "remote"));
    }

    @SuppressWarnings("unchecked")
    private Set<String> refTypes(Map<String, Object> entry) {
        List<Map<String, Object>> refs = (List<Map<String, Object>>) entry.get("refs");
        Set<String> out = new HashSet<>();
        for (Map<String, Object> r : refs) out.add((String) r.get("type"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private String refName(Map<String, Object> entry, String type) {
        List<Map<String, Object>> refs = (List<Map<String, Object>>) entry.get("refs");
        return refs.stream().filter(r -> type.equals(r.get("type")))
                .map(r -> (String) r.get("name")).findFirst().orElse(null);
    }

    // ---------- 还没取回的那几版 ----------

    @Test
    @DisplayName("只有案件库走得到的版本标 remote，本机自己的每一版都不标")
    void marksCommitsThatOnlyTheCaseLibraryCanReach() throws Exception {
        linkCaseLibrary();
        String mine = commit("我这边改的", "核对注册资本", "session");
        String theirs = colleagueSubmits("同事的意见书");

        Map<String, Object> data = history();

        assertEquals(Boolean.TRUE, row(data, theirs).get("remote"), "同事那一版本机还走不到");
        assertEquals(Boolean.FALSE, row(data, mine).get("remote"), "我自己刚落的版不是 remote");
        assertEquals("律师乙", row(data, theirs).get("authorName"));
        assertEquals(Boolean.FALSE, row(data, theirs).get("self"));
    }

    @Test
    @DisplayName("取回之后同一版不再是 remote——旗标看的是可达性不是「谁先推的」")
    void aPulledCommitStopsBeingRemote() throws Exception {
        linkCaseLibrary();
        String theirs = colleagueSubmits("同事的意见书");
        assertEquals(Boolean.TRUE, row(history(), theirs).get("remote"));

        repoSvc.fastForwardMainline(PROJECT, repoSvc.originMasterRef());

        assertEquals(Boolean.FALSE, row(history(), theirs).get("remote"));
    }

    // ---------- 自动存档折叠 ----------

    @Test
    @DisplayName("自动存档默认不出行，折成上面那一行的计数；includeAuto=true 时逐条出行")
    void foldsAutosavesIntoTheRowAbove() throws Exception {
        commit("a", "修改了《合同》", "auto");
        commit("b", "修改了《合同》", "auto");
        String session = commit("c", "核对注册资本", "session");
        commit("d", "修改了《合同》", "auto");

        Map<String, Object> folded = history();
        // 折叠后：那一笔更晚的自动存档没有更新的行可附，附给随后出现的第一行；
        // 会话行下面那两笔也折进同一行 → 3
        assertEquals(3, ((Number) row(folded, session).get("autoCount")).intValue());
        assertEquals(2, entries(folded).size(), "只剩会话行与初始版本行");

        Map<String, Object> all = history(100, null, null, null, null, null, null, true);
        assertEquals(5, entries(all).size());
        assertEquals(0, ((Number) row(all, session).get("autoCount")).intValue());
    }

    // ---------- 筛选 ----------

    @Test
    @DisplayName("按参与人筛：邮箱与展示名都能匹配，别人的版不出现")
    void filtersByAuthor() throws Exception {
        String mine = commit("我的", "核对注册资本", "session");
        Files.writeString(root.resolve("projects/7/意见书.txt"), "同事的");
        String hers = repoSvc.commitAll(PROJECT, "补充意见书节选", "session", null,
                "律师乙", "awd_lawyer_b@collab.aiworkdeck.local");

        List<Map<String, Object>> byEmail = entries(
                history(100, null, MY_EMAIL, null, null, null, null, false));
        assertTrue(byEmail.stream().anyMatch(e -> mine.equals(e.get("sha"))));
        assertTrue(byEmail.stream().noneMatch(e -> hers.equals(e.get("sha"))));

        List<Map<String, Object>> byName = entries(
                history(100, null, "律师乙", null, null, null, null, false));
        assertEquals(List.of(hers), byName.stream().map(e -> e.get("sha")).toList());
    }

    @Test
    @DisplayName("按关键词筛：不区分大小写，只留标题里带这个词的版本")
    void filtersByKeyword() throws Exception {
        String hit = commit("x", "核对 Registered Capital", "session");
        commit("y", "补充意见书节选", "session");

        List<Map<String, Object>> got = entries(
                history(100, null, null, null, "registered", null, null, false));
        assertEquals(List.of(hit), got.stream().map(e -> e.get("sha")).toList());
    }

    @Test
    @DisplayName("按文件筛：只留动过这份文件的版本，口径与单文件历史一致")
    void filtersByFile() throws Exception {
        String touched = commit("合同.txt", "改了合同", "核对注册资本", "session");
        String other = commit("意见书.txt", "改了意见书", "补充意见书节选", "session");

        ProjectFile f = new ProjectFile();
        f.setId(11L);
        f.setProjectId(PROJECT);
        f.setFilePath("projects/7/合同.txt");
        when(projectFileService.getFile(11L)).thenReturn(f);

        List<String> shas = entries(history(100, null, null, 11L, null, null, null, false))
                .stream().map(e -> (String) e.get("sha")).toList();
        assertTrue(shas.contains(touched));
        assertFalse(shas.contains(other));
    }

    @Test
    @DisplayName("按日期筛：含端，未来那一天筛不出任何东西")
    void filtersByDate() throws Exception {
        String today = commit("x", "核对注册资本", "session");
        java.time.LocalDate d = java.time.LocalDate.now();

        List<String> inRange = entries(history(100, null, null, null, null,
                d.toString(), d.toString(), false)).stream()
                .map(e -> (String) e.get("sha")).toList();
        assertTrue(inRange.contains(today), "今天这一版应落在 [今天, 今天] 区间里");

        List<Map<String, Object>> future = entries(history(100, null, null, null, null,
                d.plusDays(1).toString(), null, false));
        assertTrue(future.isEmpty());
    }

    // ---------- 翻页 ----------

    @Test
    @DisplayName("游标翻页：两页接得上、不重不漏，走到底 nextCursor 为 null")
    void pagesWithCursorWithoutGapsOrDuplicates() throws Exception {
        for (int i = 0; i < 7; i++) commit("v" + i, "第 " + i + " 次核对", "session");

        Map<String, Object> p1 = history(3, null, null, null, null, null, null, false);
        assertEquals(3, entries(p1).size());
        String cursor = (String) p1.get("nextCursor");
        assertNotNull(cursor);
        assertEquals(entries(p1).get(2).get("sha"), cursor);

        List<String> collected = new ArrayList<>(
                entries(p1).stream().map(e -> (String) e.get("sha")).toList());
        int guard = 0;
        while (cursor != null && guard++ < 10) {
            Map<String, Object> page = history(3, cursor, null, null, null, null, null, false);
            collected.addAll(entries(page).stream().map(e -> (String) e.get("sha")).toList());
            cursor = (String) page.get("nextCursor");
        }
        assertNull(cursor, "走到底应该没有下一页了");
        assertEquals(8, collected.size(), "7 次核对 + 初始版本");
        assertEquals(8, new HashSet<>(collected).size(), "翻页不该出现重复行");
        assertEquals(entries(history(100, null, null, null, null, null, null, false))
                .stream().map(e -> e.get("sha")).toList(), collected, "翻页拼起来应与一次拉完一致");
    }

    @Test
    @DisplayName("翻页不会把同一批自动存档在两页里各数一次")
    void autosavesAreNotCountedTwiceAcrossPages() throws Exception {
        String newer = commit("x", "第二次核对", "session");
        commit("a", "修改了《合同》", "auto");
        commit("b", "修改了《合同》", "auto");
        String older = commit("y", "第一次核对", "session");
        // 注意提交顺序是从旧到新写下来的，这里换成时间倒序读：newer 在上、older 在下

        Map<String, Object> p1 = history(1, null, null, null, null, null, null, false);
        assertEquals(1, entries(p1).size());
        String first = (String) entries(p1).get(0).get("sha");
        int firstAutos = ((Number) entries(p1).get(0).get("autoCount")).intValue();

        Map<String, Object> p2 = history(10, (String) p1.get("nextCursor"),
                null, null, null, null, null, false);
        int laterAutos = entries(p2).stream()
                .mapToInt(e -> ((Number) e.get("autoCount")).intValue()).sum();

        assertEquals(2, firstAutos + laterAutos, "两笔自动存档一共只该被数两次");
        assertTrue(List.of(newer, older).contains(first));
    }

    // ---------- 变更计数 ----------

    @Test
    @DisplayName("每一行带增删改名计数，且不把 .awd/ 的清单算进去")
    void countsChangesPerRow() throws Exception {
        Files.writeString(root.resolve("projects/7/意见书.txt"), "新文件");
        Files.writeString(root.resolve("projects/7/合同.txt"), "改过的合同");
        String sha = repoSvc.commitAll(PROJECT, "核对注册资本", "session", null, "韩泽伟", MY_EMAIL);

        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) row(history(), sha).get("changes");
        assertEquals(1, ((Number) counts.get("added")).intValue(), "只有意见书是新增");
        assertEquals(1, ((Number) counts.get("modified")).intValue(), "合同被改；清单不算");
        assertEquals(0, ((Number) counts.get("deleted")).intValue());
        assertEquals(0, ((Number) counts.get("renamed")).intValue());
    }

    // ---------- 领先几版 ----------

    @Test
    @DisplayName("本机领先几版自己数；案件库领先几版取云端状态那一份，两个数字同源")
    void reportsAheadAndBehind() throws Exception {
        linkCaseLibrary();
        commit("一改", "第一次核对", "session");
        commit("二改", "第二次核对", "session");
        when(cloudSyncService.cloudStatus(anyLong(), any())).thenReturn(Map.of(
                "linked", true, "remoteAhead", true, "remoteAheadCount", 2,
                "remoteAheadAuthors", List.of("律师乙"), "remoteAheadBySelf", false));

        Map<String, Object> data = history();

        assertEquals(2, ((Number) data.get("ahead")).intValue());
        assertEquals(2, ((Number) data.get("behind")).intValue());
        assertEquals(List.of("律师乙"), data.get("remoteAheadAuthors"));
        assertEquals(false, data.get("remoteAheadBySelf"));
    }

    @Test
    @DisplayName("没放进案件库时两个数字都是 0，且不带案件库那三个键")
    void unlinkedProjectHasNoRemoteCounters() throws Exception {
        commit("一改", "第一次核对", "session");

        Map<String, Object> data = history();

        assertEquals(0, ((Number) data.get("ahead")).intValue());
        assertEquals(0, ((Number) data.get("behind")).intValue());
        assertFalse(data.containsKey("remoteAheadCount"));
        assertFalse(data.containsKey("remoteAheadAuthors"));
        assertFalse(data.containsKey("remoteAheadBySelf"));
    }

    // ---------- 其余契约 ----------

    @Test
    @DisplayName("没开版本记录回 enabled:false + 空列表，不是错误")
    void notEnabledIsNotAnError() {
        ProjectRepoService fresh = mock(ProjectRepoService.class);
        when(fresh.isInitialized(99L)).thenReturn(false);
        ProjectMemberService members = mock(ProjectMemberService.class);
        when(members.hasReadPermission(99L, ME)).thenReturn(true);
        when(members.isClient(99L, ME)).thenReturn(false);
        VersionController c = new VersionController(fresh, sessionService, members,
                mock(UserService.class), projectFileService,
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(VersionLifecycleService.class));

        Map<String, Object> data = data(c.history(99L, 100, null, null, null, null, null, null,
                false, "sess"));

        assertEquals(false, data.get("enabled"));
        assertEquals(List.of(), data.get("entries"));
        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("head 说清楚现在站在主线还是某一稿上")
    void reportsWhichLineWeAreOn() throws Exception {
        assertEquals("mainline", ((Map<?, ?>) history().get("head")).get("branch"));

        WorkSession d = new WorkSession();
        d.setId(3L);
        d.setTitle("试验稿");
        d.setBranchName("draft/1000");
        when(sessionService.activeDraftOnBranch(PROJECT)).thenReturn(Optional.of(d));

        Map<?, ?> head = (Map<?, ?>) history().get("head");
        assertEquals("draft", head.get("branch"));
        assertEquals("试验稿", head.get("draftName"));
    }

    @Test
    @DisplayName("非成员一律拒绝，一次仓库都不碰")
    void rejectsNonMember() {
        ProjectMemberService members = mock(ProjectMemberService.class);
        when(members.hasReadPermission(PROJECT, ME)).thenReturn(false);
        ProjectRepoService spy = mock(ProjectRepoService.class);
        VersionController c = new VersionController(spy, sessionService, members,
                mock(UserService.class), projectFileService,
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(VersionLifecycleService.class));

        assertThrows(IllegalArgumentException.class,
                () -> c.history(PROJECT, 100, null, null, null, null, null, null, false, "sess"));
        verifyNoInteractions(spy);
    }

    @Test
    @DisplayName("limit 超过上限被钳到 500，不让一次请求把整部历史拖出来")
    void limitIsCapped() throws Exception {
        for (int i = 0; i < 3; i++) commit("v" + i, "第 " + i + " 次核对", "session");
        // 钳制本身看不见，用「不抛且照常返回」证明没有把 9999 原样透下去导致异常
        assertEquals(4, entries(history(9999, null, null, null, null, null, null, false)).size());
    }

    // ---------- 对比任意两版 ----------

    @Test
    @DisplayName("对比任意两版给出文件清单，清单文件不出现在里面")
    void comparesTwoArbitraryVersions() throws Exception {
        String base = repoSvc.log(PROJECT, "HEAD", 1).get(0).sha();
        commit("意见书.txt", "新文件", "补充意见书节选", "session");
        String tip = commit("合同.txt", "改过的合同", "核对注册资本", "session");

        @SuppressWarnings("unchecked")
        List<FileChange> changes = (List<FileChange>) data(
                controller.compare(PROJECT, base, tip, "sess")).get("changes");

        assertEquals(2, changes.size(), "实际: " + changes);
        assertTrue(changes.stream().noneMatch(c -> c.path().startsWith(".awd/")));
        assertTrue(changes.stream().anyMatch(
                c -> c.path().endsWith("意见书.txt") && c.type() == FileChange.Type.ADD));
        assertTrue(changes.stream().anyMatch(
                c -> c.path().endsWith("合同.txt") && c.type() == FileChange.Type.MODIFY));
    }

    @Test
    @DisplayName("对比里给了一个不存在的版本：说人话，不走通用「操作失败」信封")
    void compareRejectsUnknownRef() {
        VersionException e = assertThrows(VersionException.class,
                () -> controller.compare(PROJECT, "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                        "HEAD", "sess"));
        assertTrue(e.isUserFacing());
    }
}
