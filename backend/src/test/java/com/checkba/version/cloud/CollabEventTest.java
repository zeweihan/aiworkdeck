// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.cloud;

import com.checkba.controller.ProjectController;
import com.checkba.model.dto.ProjectCreateRequest;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.DeviceTokenService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import com.checkba.version.WorkSessionService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 协作事件（spec 2026-09-14 §2.3）：案件库侧的「谁在什么时候动了这份案卷」。
 *
 * <p>骨架照抄 {@link GitHttpIngestTest}（真起 HTTP 端口 + 真 JGit 客户端 push/clone），
 * 因为要验的恰恰是「push 落地那一刻服务端知道是谁、用哪台机器推的」——这件事只有
 * 走完整条 git smart-HTTP 链路才成立，mock 掉鉴权就等于什么都没验。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"security.local-mode=false"})
@ActiveProfiles("desktop")
class CollabEventTest {

    static Path root;

    private static final String DB_URL = "jdbc:h2:mem:collab-event-test-" + System.nanoTime()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE";

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("collab-event-test");
        registry.add("storage.local.root-path", () -> root.toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @LocalServerPort
    int port;

    @Autowired WorkSessionService sessionService;
    @Autowired ProjectFileRepository fileRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DeviceTokenService deviceTokenService;
    @Autowired CollabEventRepository eventRepository;
    @Autowired CollabEventService eventService;
    @Autowired CollabEventController eventController;
    @Autowired ProjectController projectController;
    @Autowired ProjectMemberService memberService;
    @Autowired com.checkba.service.UserSessionService userSessionService;

    private long ownerId;

    // ==================== 脚手架 ====================

    private String remoteUrl(long projectId) {
        return "http://localhost:" + port + "/git/" + projectId + ".git";
    }

    private UsernamePasswordCredentialsProvider creds(String token) {
        return new UsernamePasswordCredentialsProvider("git_user", token);
    }

    private Git clone(long projectId, Path clientDir, String token) throws Exception {
        return Git.cloneRepository()
                .setURI(remoteUrl(projectId))
                .setDirectory(clientDir.toFile())
                .setCredentialsProvider(creds(token))
                .call();
    }

    private void push(Git client, String token) throws Exception {
        client.push().setRemote("origin")
                .setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master"))
                .setCredentialsProvider(creds(token))
                .call();
    }

    private User newUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setDisplayName(username + "-显示名");
        u.setPassword(UserService.encodePassword("x"));
        return userRepository.save(u);
    }

    /** 项目行 + 文件行 + 已开版本记录（可被 push 的接收目标）。 */
    private void seedRecordedProject(long projectId, long userId) throws Exception {
        jdbcTemplate.update(
                "insert into project (id, name, project_type, listed_company_name, target_company_name, user_id) "
                        + "values (?, ?, ?, ?, ?, ?)",
                projectId, "collab-event-test-" + projectId, "BLANK", "", "", userId);
        Files.createDirectories(root.resolve("projects/" + projectId));
        Files.writeString(root.resolve("projects/" + projectId + "/合同.txt"), "初稿");
        ProjectFile f = new ProjectFile();
        f.setProjectId(projectId);
        f.setIsFolder(false);
        f.setName("合同.txt");
        f.setSortOrder(0);
        f.setFilePath("projects/" + projectId + "/合同.txt");
        f.setUserId(userId);
        f.setIsDeleted(false);
        f.setCreatedAt(LocalDateTime.now());
        fileRepository.save(f);
        sessionService.enableVersionRecording(projectId, "韩泽伟", "hzw@example.com");
    }

    private List<CollabEvent> eventsOf(long projectId, CollabEvent.Kind kind) {
        return eventRepository.findAll().stream()
                .filter(e -> e.getProjectId() == projectId && kind.name().equals(e.getKind()))
                .toList();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("同事交稿：记一条 PUSH，带推送者、他那台设备、以及推进了几版")
    void pushRecordsPusherDeviceAndCommitCount(@TempDir Path clientDir) throws Exception {
        long projectId = 501L;
        User owner = newUser("collab_owner_501");
        ownerId = owner.getId();
        seedRecordedProject(projectId, ownerId);
        User peer = newUser("collab_peer_501");
        jdbcTemplate.update("insert into project_member (project_id, user_id, role) values (?, ?, ?)",
                projectId, peer.getId(), "PARTICIPANT");
        DeviceTokenService.IssuedToken peerToken =
                deviceTokenService.issue(peer.getId(), "同事的 MacBook");

        try (Git client = clone(projectId, clientDir, peerToken.plaintext())) {
            Files.writeString(clientDir.resolve("合同.txt"), "第二稿");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("同事修改一").setAuthor("同事", "p@example.com").call();
            Files.writeString(clientDir.resolve("合同.txt"), "第三稿");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("同事修改二").setAuthor("同事", "p@example.com").call();
            push(client, peerToken.plaintext());
        }

        List<CollabEvent> pushes = eventsOf(projectId, CollabEvent.Kind.PUSH);
        assertEquals(1, pushes.size(), "一次 push 记一条");
        CollabEvent e = pushes.get(0);
        assertEquals(peer.getId(), e.getActorUserId(), "推的人必须是拿令牌的那个人");
        assertEquals(peerToken.id(), e.getTokenId(), "设备维度：分不出机器就说不出「你在另一台电脑」");
        assertEquals(2, e.getCommitCount(), "这次推进了两版");
        assertNotNull(e.getFromSha());
        assertNotNull(e.getToSha());
        assertNotEquals(e.getFromSha(), e.getToSha());
    }

    @Test
    @DisplayName("签出：同一台设备只记一条，换一台设备再记一条")
    void checkoutIsRecordedOncePerDevice(@TempDir Path dirs) throws Exception {
        long projectId = 502L;
        User owner = newUser("collab_owner_502");
        seedRecordedProject(projectId, owner.getId());
        DeviceTokenService.IssuedToken laptop = deviceTokenService.issue(owner.getId(), "笔记本");
        DeviceTokenService.IssuedToken desktop = deviceTokenService.issue(owner.getId(), "台式机");

        clone(projectId, dirs.resolve("a"), laptop.plaintext()).close();
        assertEquals(1, eventsOf(projectId, CollabEvent.Kind.CHECKOUT).size());

        // 同一台设备再取一次（日常同步就是这样）：不许刷成第二条
        clone(projectId, dirs.resolve("b"), laptop.plaintext()).close();
        assertEquals(1, eventsOf(projectId, CollabEvent.Kind.CHECKOUT).size(),
                "同一设备重复取内容不得重复记，否则事件表会被日常同步刷成洪流");

        clone(projectId, dirs.resolve("c"), desktop.plaintext()).close();
        List<CollabEvent> checkouts = eventsOf(projectId, CollabEvent.Kind.CHECKOUT);
        assertEquals(2, checkouts.size(), "换一台机器是一件新事");
        assertEquals(2, checkouts.stream().map(CollabEvent::getTokenId).distinct().count());
    }

    @Test
    @DisplayName("放进案件库：带设备令牌建项目记一条 SHARED；普通会话建项目不记")
    void sharedIsRecordedOnlyForDesktopShare() {
        User lawyer = newUser("collab_sharer");
        String token = deviceTokenService.issue(lawyer.getId(), "律师的电脑").plaintext();

        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setProjectType("BLANK");
        req.setName("股权转让案卷");
        Project shared = projectController.createProject(req, token);

        List<CollabEvent> rows = eventsOf(shared.getId(), CollabEvent.Kind.SHARED);
        assertEquals(1, rows.size());
        assertEquals(lawyer.getId(), rows.get(0).getActorUserId());
        assertEquals("股权转让案卷",
                CollabEventService.parseDetail(rows.get(0).getDetail()).get("name"));

        // 不带设备令牌（网页端自己建项目）：这不是「放进案件库」，不该留痕
        ProjectCreateRequest plain = new ProjectCreateRequest();
        plain.setProjectType("BLANK");
        plain.setName("本机建的案卷");
        Project local = projectController.createProject(plain, userSessionService.issue(lawyer.getId()));
        assertTrue(eventsOf(local.getId(), CollabEvent.Kind.SHARED).isEmpty(),
                "普通建项目不是共享，不能记 SHARED");
    }

    @Test
    @DisplayName("加人/移出各记一条，带被操作的人与角色")
    void memberChangesAreRecorded() {
        long projectId = 503L;
        User owner = newUser("collab_owner_503");
        jdbcTemplate.update(
                "insert into project (id, name, project_type, listed_company_name, target_company_name, user_id) "
                        + "values (?, ?, ?, ?, ?, ?)",
                projectId, "collab-event-test-503", "BLANK", "", "", owner.getId());
        User peer = newUser("collab_peer_503");

        memberService.addMember(projectId, peer.getUsername(), "PARTICIPANT", owner.getId());
        List<CollabEvent> added = eventsOf(projectId, CollabEvent.Kind.MEMBER_ADDED);
        assertEquals(1, added.size());
        assertEquals(owner.getId(), added.get(0).getActorUserId());
        assertEquals(peer.getId(), added.get(0).getTargetUserId());
        assertEquals("PARTICIPANT",
                CollabEventService.parseDetail(added.get(0).getDetail()).get("role"));

        memberService.removeMember(projectId, peer.getId(), owner.getId());
        List<CollabEvent> removed = eventsOf(projectId, CollabEvent.Kind.MEMBER_REMOVED);
        assertEquals(1, removed.size());
        assertEquals(peer.getId(), removed.get(0).getTargetUserId());
    }

    @Test
    @DisplayName("上报端点只收 PULLED，其余一律 400（别人的历史不许伪造）")
    void onlyPulledMayBeReportedByClients() {
        long projectId = 504L;
        User owner = newUser("collab_owner_504");
        jdbcTemplate.update(
                "insert into project (id, name, project_type, listed_company_name, target_company_name, user_id) "
                        + "values (?, ?, ?, ?, ?, ?)",
                projectId, "collab-event-test-504", "BLANK", "", "", owner.getId());
        String token = deviceTokenService.issue(owner.getId(), "本机").plaintext();

        ResponseEntity<Map<String, Object>> ok = eventController.report(projectId,
                Map.of("kind", "PULLED", "toSha", "abc123"), token);
        assertEquals(200, ok.getStatusCode().value());
        List<CollabEvent> pulled = eventsOf(projectId, CollabEvent.Kind.PULLED);
        assertEquals(1, pulled.size());
        assertEquals("abc123", pulled.get(0).getToSha());

        for (String forged : List.of("PUSH", "SHARED", "MEMBER_ADDED", "CHECKOUT")) {
            ResponseEntity<Map<String, Object>> bad =
                    eventController.report(projectId, Map.of("kind", forged), token);
            assertEquals(400, bad.getStatusCode().value(), forged + " 不许由客户端上报");
        }
        assertEquals(1, eventRepository.findAll().stream()
                .filter(e -> e.getProjectId() == projectId).count(), "被拒的上报一条都不许落库");
    }

    @Test
    @DisplayName("读端点：倒序、limit、before 游标翻页，且只有成员读得到")
    void listIsOrderedPagedAndMemberOnly() {
        long projectId = 505L;
        User owner = newUser("collab_owner_505");
        jdbcTemplate.update(
                "insert into project (id, name, project_type, listed_company_name, target_company_name, user_id) "
                        + "values (?, ?, ?, ?, ?, ?)",
                projectId, "collab-event-test-505", "BLANK", "", "", owner.getId());
        String token = deviceTokenService.issue(owner.getId(), "本机 505").plaintext();

        for (int i = 0; i < 5; i++) {
            eventService.record(CollabEvent.Kind.PULLED, projectId, owner.getId(), null,
                    null, "sha-" + i, null, null, null);
        }

        List<Map<String, Object>> first = events(eventController.list(projectId, 3, null, token));
        assertEquals(3, first.size());
        assertEquals("sha-4", first.get(0).get("toSha"), "最新的排最前");
        assertEquals("sha-2", first.get(2).get("toSha"));

        Long cursor = ((Number) first.get(2).get("id")).longValue();
        List<Map<String, Object>> second = events(eventController.list(projectId, 3, cursor, token));
        assertEquals(2, second.size());
        assertEquals("sha-1", second.get(0).get("toSha"));
        assertEquals("sha-0", second.get(1).get("toSha"));

        // 事件行里的人有展示名（界面永远不显示用户名）
        @SuppressWarnings("unchecked")
        Map<String, Object> actor = (Map<String, Object>) first.get(0).get("actor");
        assertEquals(owner.getId(), actor.get("userId"));
        assertEquals(owner.getDisplayName(), actor.get("displayName"));

        User outsider = newUser("collab_outsider_505");
        String outsiderToken = deviceTokenService.issue(outsider.getId(), "外人的机器").plaintext();
        assertThrows(IllegalArgumentException.class,
                () -> eventController.list(projectId, 10, null, outsiderToken),
                "不是成员就读不到这份案卷的协作记录");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(Map<String, Object> response) {
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return (List<Map<String, Object>>) data.get("events");
    }

    @Test
    @DisplayName("记事件失败绝不外抛——它只是旁白，不能把 push 或加人弄失败")
    void recordNeverThrows() {
        CollabEventRepository broken = mock(CollabEventRepository.class);
        when(broken.save(any(CollabEvent.class))).thenThrow(new RuntimeException("库挂了"));
        when(broken.existsByProjectIdAndKindAndTokenId(any(), any(), any()))
                .thenThrow(new RuntimeException("库挂了"));
        CollabEventService fragile = new CollabEventService(broken);

        assertDoesNotThrow(() -> fragile.record(CollabEvent.Kind.PUSH, 1L, 2L, 3L,
                "a", "b", 1, null, Map.of("k", "v")));
        assertDoesNotThrow(() -> fragile.recordCheckoutOnce(1L, 2L, 3L));
    }
}
