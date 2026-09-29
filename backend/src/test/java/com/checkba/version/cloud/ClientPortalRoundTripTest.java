// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.cloud;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.config.DdCloudProxyFilter;
import com.checkba.controller.AuthController;
import com.checkba.controller.CloudController;
import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.DdItem;
import com.checkba.model.entity.DdRequest;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.DdItemRepository;
import com.checkba.repository.DdRequestRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.DdCloudMigrationService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import com.checkba.storage.StorageProperties;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.version.CloudSyncService;
import com.checkba.version.ProjectRepoService;
import com.checkba.version.ProjectTreeManifestService;
import com.checkba.version.WorkSession;
import com.checkba.version.WorkSessionRepository;
import com.checkba.version.WorkSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 客户门户整条链（dev-board#1050）：Spring 上下文当案件库（local-mode=false，真 HTTP），
 * 手工 new 的桌面栈（CloudSyncService / CloudController / DdCloudProxyFilter / 迁移服务）当律师的
 * 桌面端后端，HTTP seam 不打桩。
 *
 * <p>律师放进案件库 → 本机旧清单首读时迁上去（含附件，走 multipart 原字节转发）→ 代理签码 →
 * 客户在案件库凭码登录 → 只看得到这一份 → 代理再建一条清单 → 客户读得到、上传、留言 →
 * 客户删清单 403 → 律师经代理取到客户传的文件 → 律师审核（驳回带理由 / 客户重传回待审核 / 通过 / 撤回）
 * → 撤销后码失效、会话看不到案卷。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"security.local-mode=false"})
@ActiveProfiles("desktop")
class ClientPortalRoundTripTest {

    static Path serverRoot;

    private static final String DB_URL = "jdbc:h2:mem:client-portal-test-" + System.nanoTime()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        serverRoot = Files.createTempDirectory("client-portal-test");
        registry.add("storage.local.root-path", () -> serverRoot.toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> DB_URL);
    }

    @LocalServerPort
    int port;

    @Autowired
    UserService userService;

    private String serverUrl() {
        return "http://localhost:" + port;
    }

    // ---- 桌面侧 ------------------------------------------------------------

    private static final long LOCAL_PROJECT = 7L;
    private static final long LOCAL_USER = 1L;

    private final Map<Long, DdRequest> localRequests = new HashMap<>();
    private final Map<Long, DdItem> localItems = new HashMap<>();

    private CloudSyncService desktopCloud(Path root) {
        Map<Long, ProjectFile> fileDb = new HashMap<>();
        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        when(fileRepo.findByProjectId(any())).thenAnswer(i -> {
            List<ProjectFile> out = new ArrayList<>();
            for (ProjectFile f : fileDb.values()) if (f.getProjectId().equals(i.getArgument(0))) out.add(f);
            return out;
        });
        long[] nextFileId = {100L};
        when(fileRepo.save(any(ProjectFile.class))).thenAnswer(i -> {
            ProjectFile p = i.getArgument(0);
            if (p.getId() == null) p.setId(nextFileId[0]++);
            fileDb.put(p.getId(), p);
            return p;
        });
        ProjectFile file = new ProjectFile();
        file.setId(100L);
        file.setProjectId(LOCAL_PROJECT);
        file.setIsFolder(false);
        file.setName("委托合同.txt");
        file.setSortOrder(0);
        file.setFilePath("projects/7/委托合同.txt");
        file.setUserId(LOCAL_USER);
        file.setIsDeleted(false);
        file.setCreatedAt(LocalDateTime.now());
        fileDb.put(100L, file);

        Map<Long, Project> projectDb = new HashMap<>();
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        when(projectRepo.save(any(Project.class))).thenAnswer(i -> {
            Project p = i.getArgument(0);
            projectDb.put(p.getId(), p);
            return p;
        });
        when(projectRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(projectDb.get(i.getArgument(0))));
        Project project = new Project();
        project.setId(LOCAL_PROJECT);
        project.setName("某公司股权收购尽调");
        project.setUserId(LOCAL_USER);
        projectRepo.save(project);

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService repoSvc = new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, projectRepo));
        ProjectTreeManifestService manifestSvc = new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper(),
                mock(UserRepository.class), projectRepo);

        Map<Long, WorkSession> sessions = new HashMap<>();
        long[] nextSessionId = {1L};
        WorkSessionRepository sessionRepo = mock(WorkSessionRepository.class);
        when(sessionRepo.save(any(WorkSession.class))).thenAnswer(i -> {
            WorkSession se = i.getArgument(0);
            if (se.getId() == null) se.setId(nextSessionId[0]++);
            sessions.put(se.getId(), se);
            return se;
        });
        when(sessionRepo.findFirstByProjectIdAndStatusAndSessionType(any(), any(), any())).thenAnswer(i ->
                sessions.values().stream()
                        .filter(se -> se.getProjectId().equals(i.getArgument(0))
                                && se.getStatus() == i.getArgument(1)
                                && se.getSessionType() == i.getArgument(2))
                        .findFirst());
        when(sessionRepo.findByProjectIdAndStatusAndSessionTypeOrderByStartedAtDesc(any(), any(), any()))
                .thenAnswer(i -> List.of());
        when(sessionRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(sessions.get(i.getArgument(0))));
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        WorkSessionService sessionSvc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler, fileRepo,
                event -> { });
        sessionSvc.setDebounceMillis(60_000);

        Map<Long, CloudConnection> conns = new HashMap<>();
        long[] nextConnId = {1L};
        CloudConnectionRepository connRepo = mock(CloudConnectionRepository.class);
        when(connRepo.save(any(CloudConnection.class))).thenAnswer(i -> {
            CloudConnection c = i.getArgument(0);
            if (c.getId() == null) c.setId(nextConnId[0]++);
            conns.put(c.getId(), c);
            return c;
        });
        when(connRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(conns.get(i.getArgument(0))));

        Map<Long, ProjectRemote> remotes = new HashMap<>();
        long[] nextRemoteId = {1L};
        ProjectRemoteRepository remoteRepo = mock(ProjectRemoteRepository.class);
        when(remoteRepo.save(any(ProjectRemote.class))).thenAnswer(i -> {
            ProjectRemote r = i.getArgument(0);
            if (r.getId() == null) r.setId(nextRemoteId[0]++);
            remotes.put(r.getId(), r);
            return r;
        });
        when(remoteRepo.findByProjectId(any())).thenAnswer(i -> remotes.values().stream()
                .filter(r -> r.getProjectId().equals(i.getArgument(0))).findFirst());
        when(remoteRepo.findByConnectionId(any())).thenAnswer(i -> remotes.values().stream()
                .filter(r -> r.getConnectionId().equals(i.getArgument(0))).toList());

        try {
            Files.createDirectories(root.resolve("projects/7"));
            Files.writeString(root.resolve("projects/7/委托合同.txt"), "初稿");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        sessionSvc.enableVersionRecording(LOCAL_PROJECT, "律师甲", "lawyer@example.com");
        return new CloudSyncService(repoSvc, sessionSvc, manifestSvc, fileRepo, connRepo, remoteRepo, projectRepo);
    }

    /** 本机早先建过的一份清单（放进案件库之前）：两级、第二项带一份已上传的附件。 */
    private DdCloudMigrationService migrationWithLocalChecklist(CloudSyncService cloud, byte[] attachment) {
        DdRequest req = new DdRequest();
        req.setId(501L);
        req.setProjectId(LOCAL_PROJECT);
        req.setName("本机旧清单");
        req.setCreatedBy(LOCAL_USER);
        localRequests.put(req.getId(), req);
        DdItem parent = item(601L, null, "主体资格", 1, "PENDING", null);
        DdItem child = item(602L, 601L, "营业执照", 2, "APPROVED", 900L);
        localItems.put(parent.getId(), parent);
        localItems.put(child.getId(), child);

        DdRequestRepository reqRepo = mock(DdRequestRepository.class);
        when(reqRepo.findByProjectIdOrderByCreatedAtDesc(any())).thenAnswer(i -> localRequests.values().stream()
                .filter(r -> r.getProjectId().equals(i.getArgument(0))).toList());
        when(reqRepo.save(any(DdRequest.class))).thenAnswer(i -> i.getArgument(0));
        DdItemRepository itemRepo = mock(DdItemRepository.class);
        when(itemRepo.findByDdRequestIdOrderBySortOrderAsc(any())).thenAnswer(i -> localItems.values().stream()
                .filter(it -> it.getDdRequestId().equals(i.getArgument(0)))
                .sorted(java.util.Comparator.comparing(DdItem::getSortOrder)).toList());
        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        ProjectFile uploaded = new ProjectFile();
        uploaded.setId(900L);
        uploaded.setProjectId(LOCAL_PROJECT);
        uploaded.setName("营业执照副本.pdf");
        uploaded.setFilePath("projects/7/client_uploads/1_license.pdf");
        when(fileRepo.findById(900L)).thenReturn(Optional.of(uploaded));
        StorageService storage = mock(StorageService.class);
        when(storage.load("projects/7/client_uploads/1_license.pdf")).thenReturn(new ByteArrayResource(attachment));
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(storage);
        return new DdCloudMigrationService(cloud, reqRepo, itemRepo, fileRepo, factory);
    }

    private static DdItem item(long id, Long parentId, String title, int order, String status, Long fileId) {
        DdItem i = new DdItem();
        i.setId(id);
        i.setDdRequestId(501L);
        i.setParentId(parentId);
        i.setTitle(title);
        i.setSortOrder(order);
        i.setStatus(status);
        i.setUploadedFileId(fileId);
        return i;
    }

    /** 过一遍桌面端的 DdCloudProxyFilter（就是律师前端打本机 /api/dd/* 的那条路）。 */
    private MockHttpServletResponse viaDesktop(DdCloudProxyFilter filter, String method, String path,
                                               String query, byte[] body, String contentType) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setQueryString(query);
        if (body != null) req.setContent(body);
        if (contentType != null) req.setContentType(contentType);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        assertNull(chain.getRequest(), "放进案件库的案卷不该落到本机 DdController: " + path);
        return resp;
    }

    /** 律师在桌面端点「通过 / 驳回 / 撤回」：PUT /api/dd/items/{id}/status?projectId=7 经代理到案件库。 */
    private MockHttpServletResponse review(DdCloudProxyFilter filter, long itemId, String status, String reason)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        if (reason != null) body.put("reason", reason);
        return viaDesktop(filter, "PUT", "/api/dd/items/" + itemId + "/status", "projectId=7",
                JSONUtil.toJsonStr(body).getBytes(StandardCharsets.UTF_8), "application/json");
    }

    // ---- 案件库侧（客户直连） -------------------------------------------------

    private HttpResponse clientCall(cn.hutool.http.Method method, String path, String session, String json) {
        HttpRequest req = HttpRequest.of(serverUrl() + path).method(method).header("X-Session-Id", session);
        if (json != null) req.header("Content-Type", "application/json").body(json);
        return req.execute();
    }

    private JSONObject clientLogin(String code) {
        return JSONUtil.parseObj(HttpRequest.post(serverUrl() + "/api/auth/client-login")
                .header("Content-Type", "application/json")
                .body(JSONUtil.toJsonStr(Map.of("accessCode", code)))
                .execute().body());
    }

    // ---- 测试 ---------------------------------------------------------------

    @Test
    void lawyerSharesInvitesClientChecklistAndRevokeRoundTrip(@TempDir Path desktopRoot) throws Exception {
        userService.register("lawyerA", "pw123456", "律师甲");
        CloudSyncService cloud = desktopCloud(desktopRoot);
        CloudConnection conn = cloud.connect(serverUrl(), "lawyerA", "pw123456", "测试机", LOCAL_USER);
        long rid = ((Number) cloud.shareToCloud(LOCAL_PROJECT, conn.getId(), LOCAL_USER).get("remoteProjectId")).longValue();

        byte[] oldAttachment = "%PDF-1.4 本机旧附件".getBytes(StandardCharsets.UTF_8);
        DdCloudMigrationService migration = migrationWithLocalChecklist(cloud, oldAttachment);
        ProjectMemberService pms = mock(ProjectMemberService.class);
        when(pms.hasReadPermission(LOCAL_PROJECT, LOCAL_USER)).thenReturn(true);
        when(pms.hasWritePermission(LOCAL_PROJECT, LOCAL_USER)).thenReturn(true);
        when(pms.isClient(LOCAL_PROJECT, LOCAL_USER)).thenReturn(false);
        DdCloudProxyFilter filter = new DdCloudProxyFilter(cloud, migration, pms, true);
        CloudController cloudController = new CloudController(cloud, null, pms, null, null);

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            // 桌面 local-mode：本机请求一律解析为本机用户（只影响本线程；案件库在 Tomcat 线程上不受影响）
            auth.when(() -> AuthController.getUserIdFromSession(any())).thenReturn(LOCAL_USER);

            // ① 首读清单列表：本机旧清单被推上去（两级 + 附件 + 审核状态），本机行记下案件库 id
            MockHttpServletResponse list = viaDesktop(filter, "GET", "/api/dd/projects/7", null, null, null);
            assertEquals(200, list.getStatus());
            JSONArray lists = JSONUtil.parseArray(list.getContentAsString(StandardCharsets.UTF_8));
            assertEquals(1, lists.size(), list.getContentAsString(StandardCharsets.UTF_8));
            long migratedId = lists.getJSONObject(0).getLong("id");
            assertEquals("本机旧清单", lists.getJSONObject(0).getStr("name"));
            assertEquals(migratedId, localRequests.get(501L).getCloudRequestId());
            // 再读一次不会重复迁移
            viaDesktop(filter, "GET", "/api/dd/projects/7", null, null, null);
            JSONObject migratedDetail = JSONUtil.parseObj(viaDesktop(filter, "GET", "/api/dd/requests/" + migratedId,
                    "projectId=7", null, null).getContentAsString(StandardCharsets.UTF_8));
            JSONArray migratedItems = migratedDetail.getJSONArray("items");
            assertEquals(2, migratedItems.size());
            JSONObject license = migratedItems.stream().map(o -> (JSONObject) o)
                    .filter(o -> "营业执照".equals(o.getStr("title"))).findFirst().orElseThrow();
            JSONObject subject = migratedItems.stream().map(o -> (JSONObject) o)
                    .filter(o -> "主体资格".equals(o.getStr("title"))).findFirst().orElseThrow();
            assertEquals(subject.getLong("id"), license.getLong("parentId"), "层级要保留");
            assertEquals("APPROVED", license.getStr("status"), "审核状态要保留");
            assertNotNull(license.getLong("uploadedFileId"), "附件要经 multipart 原字节转发传上去");
            MockHttpServletResponse migratedFile = viaDesktop(filter, "GET",
                    "/api/dd/items/" + license.getLong("id") + "/file", "projectId=7&token=x", null, null);
            assertArrayEquals(oldAttachment, migratedFile.getContentAsByteArray());

            // ② 代理签码：码落在案件库，回执带门户地址与有效期
            @SuppressWarnings("unchecked")
            Map<String, Object> invite = (Map<String, Object>) cloudController
                    .inviteClient(LOCAL_PROJECT, Map.of("clientName", "某公司 张总"), "s").getBody().get("data");
            String code = (String) invite.get("accessCode");
            assertNotNull(code);
            assertEquals(serverUrl() + "/client/", invite.get("clientUrl"));
            assertTrue(String.valueOf(invite.get("expiresAt")).startsWith(
                    LocalDateTime.now().plusDays(30).toLocalDate().toString()), String.valueOf(invite.get("expiresAt")));
            long clientUserId = ((Number) invite.get("clientUserId")).longValue();

            // ③ 客户在案件库凭码登录，只看得到这一份
            JSONObject login = clientLogin(code);
            assertEquals(0, login.getInt("code"), login.toString());
            String clientSession = login.getJSONObject("data").getStr("sessionId");
            assertEquals(rid, login.getJSONObject("data").getLong("projectId"));
            assertEquals("CLIENT", login.getJSONObject("data").getJSONObject("user").getStr("role"));
            JSONArray myProjects = JSONUtil.parseArray(clientCall(cn.hutool.http.Method.GET, "/api/projects/my",
                    clientSession, null).body());
            assertEquals(1, myProjects.size());
            assertEquals(rid, myProjects.getJSONObject(0).getLong("id"));

            // ④ 律师经代理再建一条清单（带条目），客户读得到
            byte[] createBody = JSONUtil.toJsonStr(Map.of("name", "第一轮尽调清单", "content", "公司章程\n股东名册"))
                    .getBytes(StandardCharsets.UTF_8);
            MockHttpServletResponse created = viaDesktop(filter, "POST", "/api/dd/projects/7", null, createBody,
                    "application/json");
            long requestId = JSONUtil.parseObj(created.getContentAsString(StandardCharsets.UTF_8)).getLong("id");
            JSONArray clientLists = JSONUtil.parseArray(clientCall(cn.hutool.http.Method.GET,
                    "/api/dd/projects/" + rid, clientSession, null).body());
            assertEquals(2, clientLists.size());
            JSONObject detail = JSONUtil.parseObj(clientCall(cn.hutool.http.Method.GET,
                    "/api/dd/requests/" + requestId, clientSession, null).body());
            long itemId = detail.getJSONArray("items").getJSONObject(0).getLong("id");

            // ⑤ 客户能传文件、能留言
            byte[] clientFile = "客户上传的章程".getBytes(StandardCharsets.UTF_8);
            Path tmp = Files.createTempFile(desktopRoot, "charter", ".txt");
            Files.write(tmp, clientFile);
            HttpResponse up = HttpRequest.post(serverUrl() + "/api/dd/items/" + itemId + "/upload")
                    .header("X-Session-Id", clientSession).form("file", tmp.toFile()).execute();
            assertEquals(200, up.getStatus());
            assertEquals("UPLOADED", JSONUtil.parseObj(up.body()).getStr("status"), up.body());
            HttpResponse comment = clientCall(cn.hutool.http.Method.POST, "/api/dd/items/" + itemId + "/comments",
                    clientSession, JSONUtil.toJsonStr(Map.of("content", "已上传")));
            assertEquals(200, comment.getStatus());
            assertFalse(JSONUtil.parseObj(comment.body()).containsKey("code"), comment.body());

            // ⑥ 客户删清单 / 改条目 → 403（公网上的真实越权面）
            assertEquals(403, clientCall(cn.hutool.http.Method.DELETE, "/api/dd/requests/" + requestId,
                    clientSession, null).getStatus());
            assertEquals(403, clientCall(cn.hutool.http.Method.PUT, "/api/dd/items/" + itemId + "/status",
                    clientSession, JSONUtil.toJsonStr(Map.of("status", "APPROVED"))).getStatus());
            // 律师这一侧照常：清单还在
            JSONArray afterDelete = JSONUtil.parseArray(viaDesktop(filter, "GET", "/api/dd/projects/7", null, null, null)
                    .getContentAsString(StandardCharsets.UTF_8));
            assertEquals(2, afterDelete.size());

            // ⑦ 律师经代理取到客户传的文件（本机 /api/files 的 id 空间与案件库不同，必须走清单项取件口）
            MockHttpServletResponse got = viaDesktop(filter, "GET", "/api/dd/items/" + itemId + "/file",
                    "projectId=7", null, null);
            assertEquals(200, got.getStatus());
            assertArrayEquals(clientFile, got.getContentAsByteArray());

            // ⑦½ 律师审核（dev-board#1057）：经代理驳回（带理由）→ 客户读到状态与理由 → 客户改状态 403
            //     → 客户重传回到待审核 → 律师通过 → 客户不能再传 → 律师撤回通过；没上传的条目不能通过（400）
            long untouchedItem = detail.getJSONArray("items").getJSONObject(1).getLong("id");
            MockHttpServletResponse early = review(filter, untouchedItem, "APPROVED", null);
            assertEquals(400, early.getStatus(), early.getContentAsString(StandardCharsets.UTF_8));
            assertEquals(400, review(filter, itemId, "REJECTED", "  ").getStatus(), "驳回必须带理由");
            MockHttpServletResponse rejected = review(filter, itemId, "REJECTED", "章程缺最后一页");
            assertEquals(200, rejected.getStatus(), rejected.getContentAsString(StandardCharsets.UTF_8));
            assertEquals("REJECTED", JSONUtil.parseObj(rejected.getContentAsString(StandardCharsets.UTF_8)).getStr("status"));

            JSONObject clientSees = JSONUtil.parseObj(clientCall(cn.hutool.http.Method.GET,
                    "/api/dd/requests/" + requestId, clientSession, null).body());
            JSONObject seenItem = clientSees.getJSONArray("items").stream().map(o -> (JSONObject) o)
                    .filter(o -> o.getLong("id") == itemId).findFirst().orElseThrow();
            assertEquals("REJECTED", seenItem.getStr("status"));
            assertEquals("章程缺最后一页", clientSees.getJSONObject("rejectReasons").getStr(String.valueOf(itemId)),
                    clientSees.toString());
            JSONArray seenComments = JSONUtil.parseArray(clientCall(cn.hutool.http.Method.GET,
                    "/api/dd/items/" + itemId + "/comments", clientSession, null).body());
            assertTrue(seenComments.stream().map(o -> ((JSONObject) o).getStr("content"))
                    .anyMatch("驳回：章程缺最后一页"::equals), seenComments.toString());

            assertEquals(403, clientCall(cn.hutool.http.Method.PUT, "/api/dd/items/" + itemId + "/status",
                    clientSession, JSONUtil.toJsonStr(Map.of("status", "UPLOADED"))).getStatus());

            HttpResponse reUp = HttpRequest.post(serverUrl() + "/api/dd/items/" + itemId + "/upload")
                    .header("X-Session-Id", clientSession).form("file", tmp.toFile()).execute();
            assertEquals(200, reUp.getStatus(), reUp.body());
            assertEquals("UPLOADED", JSONUtil.parseObj(reUp.body()).getStr("status"), "驳回后重传回到待审核");

            assertEquals(200, review(filter, itemId, "APPROVED", null).getStatus());
            HttpResponse afterApprove = HttpRequest.post(serverUrl() + "/api/dd/items/" + itemId + "/upload")
                    .header("X-Session-Id", clientSession).form("file", tmp.toFile()).execute();
            assertEquals(400, afterApprove.getStatus(), "已通过的条目不再收材料: " + afterApprove.body());
            MockHttpServletResponse withdrawn = review(filter, itemId, "UPLOADED", null);
            assertEquals(200, withdrawn.getStatus());
            assertEquals("UPLOADED", JSONUtil.parseObj(withdrawn.getContentAsString(StandardCharsets.UTF_8)).getStr("status"));

            // ⑧ 撤销：案件库上移出客户 → 码失效、旧会话看不到案卷
            ResponseEntity<Map<String, Object>> removed = cloudController.removeMember(LOCAL_PROJECT, clientUserId, "s");
            assertEquals(0, removed.getBody().get("code"));
            JSONObject again = clientLogin(code);
            assertEquals(1, again.getInt("code"));
            assertTrue(again.getStr("message").contains("失效") || again.getStr("message").contains("no longer"),
                    again.toString());
            JSONArray afterRevoke = JSONUtil.parseArray(clientCall(cn.hutool.http.Method.GET, "/api/projects/my",
                    clientSession, null).body());
            assertEquals(0, afterRevoke.size());
        }
    }
}
