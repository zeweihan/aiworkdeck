# 版本记录 v2：云端仓库与多人协作 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给项目级版本记录加云端 origin：团队服务器（现有后端 web 形态）内嵌 Git smart HTTP 端点，桌面端推拉主线实现多人协作，冲突复用 v1 三选一内核。

**Architecture:** 每人本地完整 JGit 仓库 + 云端 origin。服务端用 JGit 核心包的 `UploadPack`/`ReceivePack` 在 Spring Controller 里直写 smart HTTP（Boot 3.2.4 是 jakarta，JGit 6.9 GitServlet 是 javax，不能用）；认证走新增设备令牌（复用 `getUserIdFromSession` 静态入口）；跨机器身份靠清单 v2（节点 UUID + repo 相对路径）；推拉只涉及 master + 里程碑标签，冲突停 MERGING 窗口走三选一。

**Tech Stack:** Spring Boot 3.2.4 / JGit 6.9.0（零新依赖）/ JPA(H2 桌面、MySQL/PG 服务端) / uni-app Vue3 / app-e2e (Puppeteer)。

**Spec:** docs/superpowers/specs/2026-07-31-version-control-v2-cloud-collab-design.md

## Global Constraints

- 界面零 Git 术语（含中文直译，「主线」也不行）；术语表：push=「上传到云端」、pull=「从云端更新」、clone=「从云端接一个项目」、共享=「共享到云端」。
- 全局禁 emoji（代码/UI/文档/commit）。
- commit 尾注 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 本机 mvn 必须 JDK 21：`JAVA_HOME=$(/usr/libexec/java_home -v 21)`；前端 npm；worktree 内编辑构建同树。
- 版本记录失败绝不阻断主流程（v1 地雷 #5，延伸到一切网络路径：云端不可达只置状态，不抛给调用方）。
- 历史永不重写；`.awd/` 对用户不可见（一切新增文件列表接口都要过滤）。
- `hasReadPermission`/`isClient` 参数序是 `(projectId, userId)`（v1 地雷 #3）。
- 全上下文测试必须 `@ActiveProfiles("desktop")`（裸 @SpringBootTest 连本机 Postgres，CI 必挂）；H2 测试连接串约定 `MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1`；不在 `src/test/resources` 放 classpath 根的 schema.sql/application-test.yml。
- 冲突（MERGING/MERGING_RESOLVED）窗口内任何代码路径不得执行 `git add`（v1 地雷 #20）。
- 「中止」类操作必须 path-scoped，绝不全树 `reset --hard`（v1 地雷、第 3 期终审 Critical）。
- 改 `WorkSessionService`/`AgentOrchestrator` 构造器要同步 `EvalHarness.java` 手写 new（v1 地雷 #19，三次踩过）。
- 一行超过 2 个 `flex-shrink:0` 按钮的新 UI 必查窄容器溢出（v1 地雷 #24）。
- e2e 断言认组件真渲染的独有选择器，不用 body innerText 包含。

## 文件结构（新增/修改总览）

**服务端（backend，team-server 与桌面共用同一代码库，端点在桌面态也存在但无人调用）**
- 新增 `backend/src/main/java/com/checkba/version/cloud/GitHttpController.java`——smart HTTP 三端点（info/refs + upload-pack + receive-pack）+ gzip 处理 + PostReceiveHook 挂接。
- 新增 `backend/src/main/java/com/checkba/version/cloud/GitAccessService.java`——git 端点鉴权（令牌解析 + 成员矩阵）。
- 新增 `backend/src/main/java/com/checkba/version/cloud/PushIngestService.java`——push 落库（checkout+清单同步，含「待同步」延后）。
- 新增 `backend/src/main/java/com/checkba/model/entity/DeviceToken.java` + `backend/src/main/java/com/checkba/repository/DeviceTokenRepository.java` + `backend/src/main/java/com/checkba/service/DeviceTokenService.java`。
- 修改 `AuthController.java`——设备令牌发放/撤销端点 + `getUserIdFromSession` 识别 `awdt_` 前缀。
- 修改 `WorkSessionService.java`——服务端 endSession 冲突化（mergeKeepingConflicts 路径）。
- 修改 `VersionController.java`——/status 增加云端字段；冲突状态区分 cloud/draft 语境。

**桌面端（同一 backend 代码库的客户侧能力）**
- 修改 `ProjectRepoService.java`——addRemote/fetch/push（JGit 内建 HTTP transport）。
- 新增 `backend/src/main/java/com/checkba/model/entity/CloudConnection.java`、`ProjectRemote.java` + 对应 Repository。
- 新增 `backend/src/main/java/com/checkba/version/cloud/CloudSyncService.java`——上传/更新/共享/接入业务语义。
- 新增 `backend/src/main/java/com/checkba/controller/CloudController.java`——`/api/cloud/*` REST。
- 修改 `ProjectFile.java`——uid 列；修改 `ProjectTreeManifestService.java`/`TreeManifest.java`——清单 v2。

**前端**
- 修改 `frontend/src/services/api.js`——cloud 系列具名导出。
- 新增 `frontend/src/components/version/CloudSyncBar.vue`——云端状态区。
- 修改 `VersionPanel.vue`/`AdoptConflictDialog.vue`/`project-overview.vue`——云端状态、冲突语境标签、接项目/共享入口。
- 设置页云端协作分区（位置待核验后填入）。

**e2e**
- 修改 `frontend/tests/app-e2e/run.mjs`——J11 多人旅程（两桌面后端 + 一服务端）。

**领域文档**
- 修改 `.claude/agents/version-control.md`——v2 契约与新地雷。

## 任务清单（14 个）

1. Git smart HTTP 端点（协议层，暂无鉴权）+ JGit 客户端集成测试
2. DeviceToken：实体/发放/撤销 + `getUserIdFromSession` 令牌识别
3. Git 端点鉴权：成员矩阵过滤
4. 清单 v2：ProjectFile.uid + capture/apply v2 + v1 兼容
5. ProjectRepoService 远端原语：addRemote/fetch/push
6. 服务端 push 落库：PostReceiveHook + 待同步延后
7. 服务端 endSession 冲突化
8. CloudConnection/ProjectRemote + CloudSyncService 上传（含被拒自动合并重推）
9. CloudSyncService 更新（快进/分叉/冲突窗口）+ /status 云端冲突语境
10. 共享上云 + 接入克隆 + 旧清单升级提交
11. CloudController REST + endSession 自动上传钩子
12. 前端：设置页云端协作 + api.js
13. 前端：版本面板云端状态区 + 冲突语境标签 + 接项目/共享入口 + reload 链
14. e2e J11 + 领域文档更新

各任务详细步骤见下。

---

### Task 1: Git smart HTTP 端点（协议层，本任务暂无鉴权）

**Files:**
- Create: `backend/src/main/java/com/checkba/version/cloud/GitHttpController.java`
- Test: `backend/src/test/java/com/checkba/version/cloud/GitHttpProtocolTest.java`

**Interfaces:**
- Consumes: `ProjectRepoService.open(long)`（返回 `Repository`，调用方负责 close）、`isInitialized(long)`、`gitDir(long)`。
- Produces: HTTP 端点 `GET /git/{projectId}.git/info/refs?service=`、`POST /git/{projectId}.git/git-upload-pack`、`POST /git/{projectId}.git/git-receive-pack`。Task 3 会在这三个入口前加鉴权；Task 6 会往 `ReceivePack` 挂 `PostReceiveHook`（本任务先留 `configureReceivePack(ReceivePack, long)` 私有钩位，空实现）。

背景：后端 Spring Boot 3.2.4（jakarta.servlet），JGit 6.9 的 `org.eclipse.jgit.http.server`（GitServlet）是 javax.servlet 系不能挂载。JGit 核心包的 `UploadPack`/`ReceivePack` 是 servlet 无关的（吃 InputStream/OutputStream），直接在 Controller 里写 smart HTTP 协议，零新依赖。协议要点：info/refs 响应体 = pkt-line `# service=<name>\n` + flush(`0000`) + 引用广告；两个 POST 直接对接 upload/receive；git 客户端可能发 `Content-Encoding: gzip` 的请求体，要解。

- [ ] **Step 1: 写失败的集成测试**

`backend/src/test/java/com/checkba/version/cloud/GitHttpProtocolTest.java`：

```java
package com.checkba.version.cloud;

import com.checkba.version.ProjectRepoService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对内嵌 smart HTTP 端点做真实 JGit 客户端克隆/推送。
 * 必须 desktop profile：默认 profile 连本机 Postgres，CI 必挂（v1 已知地雷）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("desktop")
class GitHttpProtocolTest {

    static Path root;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("git-http-test");
        registry.add("storage.local.root-path", () -> root.toAbsolutePath().toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    ProjectRepoService repoService;

    private String remoteUrl(long projectId) {
        return "http://localhost:" + port + "/git/" + projectId + ".git";
    }

    private void seedProject(long projectId) throws Exception {
        Files.createDirectories(root.resolve("projects/" + projectId));
        Files.writeString(root.resolve("projects/" + projectId + "/合同.txt"), "初稿");
        repoService.init(projectId, "韩泽伟", "hzw@example.com");
    }

    @Test
    void cloneOverHttpGetsSeedContent(@TempDir Path clientDir) throws Exception {
        seedProject(7L);
        try (Git client = Git.cloneRepository()
                .setURI(remoteUrl(7L))
                .setDirectory(clientDir.toFile())
                .call()) {
            assertEquals("初稿", Files.readString(clientDir.resolve("合同.txt")));
        }
    }

    @Test
    void pushOverHttpAdvancesServerMaster(@TempDir Path clientDir) throws Exception {
        seedProject(8L);
        try (Git client = Git.cloneRepository()
                .setURI(remoteUrl(8L))
                .setDirectory(clientDir.toFile())
                .call()) {
            Files.writeString(clientDir.resolve("合同.txt"), "第二稿");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("修改").setAuthor("客户端", "c@example.com").call();
            client.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master"))
                    .call();
            ObjectId clientHead = client.getRepository().resolve("master");
            assertEquals(clientHead.getName(), repoService.resolveRef(8L, "master"));
        }
    }

    @Test
    void unknownProjectReturns404(@TempDir Path clientDir) {
        Exception e = assertThrows(Exception.class, () -> Git.cloneRepository()
                .setURI(remoteUrl(999L))
                .setDirectory(clientDir.toFile())
                .call());
        assertTrue(e.getMessage().contains("404") || e.getMessage().contains("not found")
                || e.getMessage().contains("无法"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=GitHttpProtocolTest
```
Expected: FAIL——clone 抛 `TransportException`（404，端点不存在）。三个用例全红。

- [ ] **Step 3: 实现 GitHttpController**

`backend/src/main/java/com/checkba/version/cloud/GitHttpController.java`：

```java
package com.checkba.version.cloud;

import com.checkba.version.ProjectRepoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Git smart HTTP 协议端点（团队服务器侧）。
 * 不用 org.eclipse.jgit.http.server 的 GitServlet：那是 javax.servlet 系，
 * 本项目是 Boot 3 / jakarta。UploadPack/ReceivePack 本身 servlet 无关，直接对接流。
 */
@RestController
@RequestMapping("/git")
public class GitHttpController {

    static final String UPLOAD_PACK = "git-upload-pack";
    static final String RECEIVE_PACK = "git-receive-pack";

    private final ProjectRepoService repoService;

    public GitHttpController(ProjectRepoService repoService) {
        this.repoService = repoService;
    }

    @GetMapping("/{projectId}.git/info/refs")
    public void infoRefs(@PathVariable long projectId,
                         @RequestParam(value = "service", required = false) String service,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        if (!UPLOAD_PACK.equals(service) && !RECEIVE_PACK.equals(service)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "smart protocol only");
            return;
        }
        if (!repoService.isInitialized(projectId)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType("application/x-" + service + "-advertisement");
        noCache(response);
        try (Repository repo = repoService.open(projectId)) {
            PacketLineOut out = new PacketLineOut(response.getOutputStream());
            out.writeString("# service=" + service + "\n");
            out.end();
            if (UPLOAD_PACK.equals(service)) {
                UploadPack up = new UploadPack(repo);
                up.setBiDirectionalPipe(false);
                up.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(out));
            } else {
                ReceivePack rp = new ReceivePack(repo);
                configureReceivePack(rp, projectId);
                rp.sendAdvertisedRefs(new RefAdvertiser.PacketLineOutRefAdvertiser(out));
            }
        }
    }

    @PostMapping("/{projectId}.git/git-upload-pack")
    public void uploadPack(@PathVariable long projectId,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        if (!repoService.isInitialized(projectId)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType("application/x-git-upload-pack-result");
        noCache(response);
        try (Repository repo = repoService.open(projectId)) {
            UploadPack up = new UploadPack(repo);
            up.setBiDirectionalPipe(false);
            up.upload(body(request), response.getOutputStream(), null);
        }
    }

    @PostMapping("/{projectId}.git/git-receive-pack")
    public void receivePack(@PathVariable long projectId,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        if (!repoService.isInitialized(projectId)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType("application/x-git-receive-pack-result");
        noCache(response);
        try (Repository repo = repoService.open(projectId)) {
            ReceivePack rp = new ReceivePack(repo);
            rp.setBiDirectionalPipe(false);
            configureReceivePack(rp, projectId);
            rp.receive(body(request), response.getOutputStream(), null);
        }
    }

    /** Task 6 在这里挂 PostReceiveHook（push 落库）。本任务空实现。 */
    private void configureReceivePack(ReceivePack rp, long projectId) {
    }

    private InputStream body(HttpServletRequest request) throws IOException {
        InputStream in = request.getInputStream();
        if ("gzip".equalsIgnoreCase(request.getHeader("Content-Encoding"))) {
            return new GZIPInputStream(in);
        }
        return in;
    }

    private void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=GitHttpProtocolTest
```
Expected: PASS（3/3）。若 clone 报 `invalid advertisement`，检查 pkt-line 头是否漏了 `# service=` 行或 flush。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/checkba/version/cloud/GitHttpController.java backend/src/test/java/com/checkba/version/cloud/GitHttpProtocolTest.java
git commit -m "feat(version): Git smart HTTP 端点——UploadPack/ReceivePack 直写协议

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 2: 设备令牌——实体、发放/撤销端点、静态鉴权入口识别

**Files:**
- Create: `backend/src/main/java/com/checkba/model/entity/DeviceToken.java`
- Create: `backend/src/main/java/com/checkba/repository/DeviceTokenRepository.java`
- Create: `backend/src/main/java/com/checkba/service/DeviceTokenService.java`
- Modify: `backend/src/main/java/com/checkba/controller/AuthController.java`（getUserIdFromSession 识别令牌 + 三个端点）
- Test: `backend/src/test/java/com/checkba/service/DeviceTokenServiceTest.java`

**Interfaces:**
- Consumes: `UserService.login(String username, String password)`（UserService.java:64，密码错抛异常）；`AuthController.getUserIdFromSession(String)` 静态入口（AuthController.java:206，全仓约 26 个调用方）。
- Produces: `DeviceTokenService.issue(Long userId, String name) -> IssuedToken(Long id, String plaintext)`、`resolveUserId(String token) -> Long|null`、`revoke(Long userId, Long tokenId)`、`listMine(Long userId) -> List<DeviceToken>`；常量 `DeviceTokenService.TOKEN_PREFIX = "awdt_"`。端点 `POST /api/auth/device-token`、`GET /api/auth/device-tokens`、`POST /api/auth/device-token/{id}/revoke`。Task 3 的 Basic 认证与 Task 8 的桌面端连接都吃这套。

设计要点：令牌明文 `awdt_` + 43 位 base64url（SecureRandom 32 字节），只在发放响应里出现一次；库里存 SHA-256 hex。识别改动做在 `getUserIdFromSession` 一处（顺手加 null 守卫——现状 `SESSION_STORE.get(null)` 会 NPE，核验实证）。静态互通用 `staticUserService` 同款模式（AuthController.java:27/34），但由 DeviceTokenService 构造器反向注册，避免动 AuthController 构造器签名。

- [ ] **Step 1: 写失败的单测**

`backend/src/test/java/com/checkba/service/DeviceTokenServiceTest.java`：

```java
package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.DeviceToken;
import com.checkba.repository.DeviceTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceTokenServiceTest {

    private DeviceTokenRepository repo;
    private DeviceTokenService svc;
    private final Map<String, DeviceToken> byHash = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        byHash.clear();
        repo = mock(DeviceTokenRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            DeviceToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(seq.getAndIncrement());
            byHash.put(t.getTokenHash(), t);
            return t;
        });
        when(repo.findByTokenHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byHash.get(inv.getArgument(0, String.class))));
        svc = new DeviceTokenService(repo);
    }

    @Test
    void issuedTokenResolvesBackToUser() {
        DeviceTokenService.IssuedToken issued = svc.issue(42L, "MacBook");
        assertTrue(issued.plaintext().startsWith(DeviceTokenService.TOKEN_PREFIX));
        assertEquals(42L, svc.resolveUserId(issued.plaintext()));
    }

    @Test
    void plaintextIsNeverStored() {
        DeviceTokenService.IssuedToken issued = svc.issue(42L, "MacBook");
        assertTrue(byHash.values().stream()
                .noneMatch(t -> issued.plaintext().equals(t.getTokenHash())));
        assertNull(svc.resolveUserId(DeviceTokenService.TOKEN_PREFIX + "wrong"));
    }

    @Test
    void staticAuthEntryRecognisesTokens() {
        // DeviceTokenService 构造器把自己注册进 AuthController 静态入口
        DeviceTokenService.IssuedToken issued = svc.issue(7L, "e2e");
        assertEquals(7L, AuthController.getUserIdFromSession(issued.plaintext()));
        assertNull(AuthController.getUserIdFromSession(null)); // null 守卫，不再 NPE
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=DeviceTokenServiceTest
```
Expected: 编译失败（DeviceToken/DeviceTokenService 不存在）。

- [ ] **Step 3: 实现实体 + Repository + Service + AuthController 改动**

`backend/src/main/java/com/checkba/model/entity/DeviceToken.java`（照 FileTag.java 的 Lombok 风格）：

```java
package com.checkba.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 云端协作的长期设备令牌。库里只存 SHA-256，明文只在发放时返回一次。 */
@Entity
@Table(name = "device_token",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tokenHash"}))
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(length = 128)
    private String name;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;
}
```

`backend/src/main/java/com/checkba/repository/DeviceTokenRepository.java`：

```java
package com.checkba.repository;

import com.checkba.model.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    Optional<DeviceToken> findByTokenHash(String tokenHash);
    List<DeviceToken> findByUserIdOrderByCreatedAtDesc(Long userId);
}
```

`backend/src/main/java/com/checkba/service/DeviceTokenService.java`：

```java
package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.DeviceToken;
import com.checkba.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
public class DeviceTokenService {

    public static final String TOKEN_PREFIX = "awdt_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceTokenRepository repository;

    public DeviceTokenService(DeviceTokenRepository repository) {
        this.repository = repository;
        AuthController.registerDeviceTokenService(this);
    }

    public record IssuedToken(Long id, String plaintext) {}

    public IssuedToken issue(Long userId, String name) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String plaintext = TOKEN_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        DeviceToken t = new DeviceToken();
        t.setUserId(userId);
        t.setTokenHash(sha256(plaintext));
        t.setName(name == null || name.isBlank() ? "未命名设备" : name.trim());
        t.setCreatedAt(LocalDateTime.now());
        t = repository.save(t);
        return new IssuedToken(t.getId(), plaintext);
    }

    /** 未命中返回 null——调用方（静态鉴权入口）把 null 当未登录处理。 */
    public Long resolveUserId(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(TOKEN_PREFIX)) return null;
        return repository.findByTokenHash(sha256(plaintext))
                .map(t -> {
                    t.setLastUsedAt(LocalDateTime.now());
                    repository.save(t);
                    return t.getUserId();
                })
                .orElse(null);
    }

    public void revoke(Long userId, Long tokenId) {
        repository.findById(tokenId)
                .filter(t -> t.getUserId().equals(userId))
                .ifPresent(repository::delete);
    }

    public List<DeviceToken> listMine(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
```

`AuthController.java` 改动（三处）：

1. 静态字段与注册方法（`staticUserService` 旁，AuthController.java:27 附近）：

```java
    private static com.checkba.service.DeviceTokenService staticDeviceTokenService;

    /** DeviceTokenService 构造时反向注册，静态鉴权入口由此识别设备令牌。 */
    public static void registerDeviceTokenService(com.checkba.service.DeviceTokenService svc) {
        staticDeviceTokenService = svc;
    }
```

2. `getUserIdFromSession`（AuthController.java:206-208）替换为：

```java
    public static Long getUserIdFromSession(String sessionId) {
        if (sessionId == null) return null;
        if (sessionId.startsWith(com.checkba.service.DeviceTokenService.TOKEN_PREFIX)
                && staticDeviceTokenService != null) {
            return staticDeviceTokenService.resolveUserId(sessionId);
        }
        return SESSION_STORE.get(sessionId);
    }
```

3. 三个端点（类尾部，注入 `DeviceTokenService`——构造器加一参，同类只有 Spring 注入无手工 new，安全）：

```java
    /** 用账号密码换长期设备令牌（桌面端连接团队服务器用）。明文只在这里出现一次。 */
    @PostMapping("/device-token")
    public Map<String, Object> issueDeviceToken(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            User user = userService.login(body.get("username"), body.get("password"));
            var issued = deviceTokenService.issue(user.getId(), body.get("name"));
            result.put("code", 0);
            result.put("data", Map.of(
                    "tokenId", issued.id(),
                    "token", issued.plaintext(),
                    "userId", user.getId(),
                    "username", user.getUsername(),
                    "displayName", user.getDisplayName() == null ? user.getUsername() : user.getDisplayName()));
        } catch (Exception e) {
            result.put("code", 1);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/device-tokens")
    public Map<String, Object> listDeviceTokens(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) return Map.of("code", 1, "message", "未登录");
        var items = deviceTokenService.listMine(userId).stream()
                .map(t -> Map.of("id", t.getId(), "name", t.getName(),
                        "createdAt", String.valueOf(t.getCreatedAt()),
                        "lastUsedAt", String.valueOf(t.getLastUsedAt())))
                .toList();
        return Map.of("code", 0, "data", Map.of("tokens", items));
    }

    @PostMapping("/device-token/{id}/revoke")
    public Map<String, Object> revokeDeviceToken(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) return Map.of("code", 1, "message", "未登录");
        deviceTokenService.revoke(userId, id);
        return Map.of("code", 0, "message", "已撤销");
    }
```

- [ ] **Step 4: 跑测试确认通过 + 全量回归**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=DeviceTokenServiceTest
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
```
Expected: 新测试 3/3；全量 0 fail（AuthController 构造器变化只影响 Spring 注入，若有测试手工 new AuthController，按编译错误补第四参 mock）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/checkba/model/entity/DeviceToken.java backend/src/main/java/com/checkba/repository/DeviceTokenRepository.java backend/src/main/java/com/checkba/service/DeviceTokenService.java backend/src/main/java/com/checkba/controller/AuthController.java backend/src/test/java/com/checkba/service/DeviceTokenServiceTest.java
git commit -m "feat(auth): 设备令牌——发放/撤销/静态入口识别，云端协作认证基座

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 3: Git 端点鉴权——Basic 令牌 + 成员矩阵

**Files:**
- Create: `backend/src/main/java/com/checkba/version/cloud/GitAccessService.java`
- Modify: `backend/src/main/java/com/checkba/version/cloud/GitHttpController.java`
- Test: `backend/src/test/java/com/checkba/version/cloud/GitAccessServiceTest.java`
- Modify: `backend/src/test/java/com/checkba/version/cloud/GitHttpProtocolTest.java`（补认证）

**Interfaces:**
- Consumes: `DeviceTokenService.resolveUserId(String)`；`ProjectMemberService.hasReadPermission(Long projectId, Long userId)` / `hasWritePermission(Long, Long)` / `isClient(Long, Long)`（**参数序 projectId 在前**，v1 地雷 #3）。
- Produces: `GitAccessService.authorize(HttpServletRequest, long projectId, boolean write) -> Long userId`，未认证/无权抛 `GitAccessDeniedException(statusCode)`（401/403）。

规则：Basic 的 password 位放设备令牌（username 位仅展示用，不参与判定——令牌本身唯一标识用户）；读=hasReadPermission 且非 CLIENT，写=hasWritePermission 且非 CLIENT。401 必须带 `WWW-Authenticate: Basic realm="AIWorkdeck Git"`，JGit 客户端靠它触发凭据重试。

- [ ] **Step 1: 写失败的单测**

`backend/src/test/java/com/checkba/version/cloud/GitAccessServiceTest.java`：

```java
package com.checkba.version.cloud;

import com.checkba.service.DeviceTokenService;
import com.checkba.service.ProjectMemberService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitAccessServiceTest {

    private DeviceTokenService tokens;
    private ProjectMemberService members;
    private GitAccessService svc;

    @BeforeEach
    void setUp() {
        tokens = mock(DeviceTokenService.class);
        members = mock(ProjectMemberService.class);
        svc = new GitAccessService(tokens, members);
    }

    private HttpServletRequest reqWith(String user, String token) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        if (token != null) {
            String cred = Base64.getEncoder().encodeToString(
                    (user + ":" + token).getBytes(StandardCharsets.UTF_8));
            when(req.getHeader("Authorization")).thenReturn("Basic " + cred);
        }
        return req;
    }

    @Test
    void missingCredentialsIs401() {
        GitAccessDeniedException e = assertThrows(GitAccessDeniedException.class,
                () -> svc.authorize(reqWith(null, null), 7L, false));
        assertEquals(401, e.statusCode());
    }

    @Test
    void memberCanReadNonMemberCannot() {
        when(tokens.resolveUserId("awdt_x")).thenReturn(42L);
        when(members.hasReadPermission(7L, 42L)).thenReturn(true);
        when(members.isClient(7L, 42L)).thenReturn(false);
        assertEquals(42L, svc.authorize(reqWith("u", "awdt_x"), 7L, false));

        when(members.hasReadPermission(7L, 42L)).thenReturn(false);
        assertEquals(403, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorize(reqWith("u", "awdt_x"), 7L, false)).statusCode());
    }

    @Test
    void clientIsAlwaysDeniedAndReadOnlyCannotWrite() {
        when(tokens.resolveUserId("awdt_x")).thenReturn(42L);
        when(members.hasReadPermission(7L, 42L)).thenReturn(true);
        when(members.isClient(7L, 42L)).thenReturn(true);
        assertEquals(403, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorize(reqWith("u", "awdt_x"), 7L, false)).statusCode());

        when(members.isClient(7L, 42L)).thenReturn(false);
        when(members.hasWritePermission(7L, 42L)).thenReturn(false);
        assertEquals(403, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorize(reqWith("u", "awdt_x"), 7L, true)).statusCode());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=GitAccessServiceTest
```
Expected: 编译失败（GitAccessService 不存在）。

- [ ] **Step 3: 实现**

`backend/src/main/java/com/checkba/version/cloud/GitAccessService.java`（含同文件级异常类）：

```java
package com.checkba.version.cloud;

import com.checkba.service.DeviceTokenService;
import com.checkba.service.ProjectMemberService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Git 端点鉴权：Basic 的 password 位是设备令牌（username 位仅展示）。
 * 读=项目成员且非 CLIENT；写=hasWritePermission 且非 CLIENT——
 * 与 VersionController.requireMember 拒 CLIENT 同一口径。
 * 参数序 (projectId, userId)：两参同为 Long，写反能编译（v1 地雷 #3）。
 */
@Service
public class GitAccessService {

    private final DeviceTokenService deviceTokenService;
    private final ProjectMemberService memberService;

    public GitAccessService(DeviceTokenService deviceTokenService,
                            ProjectMemberService memberService) {
        this.deviceTokenService = deviceTokenService;
        this.memberService = memberService;
    }

    public Long authorize(HttpServletRequest request, long projectId, boolean write) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            throw new GitAccessDeniedException(401);
        }
        String token;
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            token = colon < 0 ? decoded : decoded.substring(colon + 1);
        } catch (IllegalArgumentException e) {
            throw new GitAccessDeniedException(401);
        }
        Long userId = deviceTokenService.resolveUserId(token);
        if (userId == null) throw new GitAccessDeniedException(401);
        if (memberService.isClient(projectId, userId)) throw new GitAccessDeniedException(403);
        boolean allowed = write
                ? memberService.hasWritePermission(projectId, userId)
                : memberService.hasReadPermission(projectId, userId);
        if (!allowed) throw new GitAccessDeniedException(403);
        return userId;
    }
}
```

`backend/src/main/java/com/checkba/version/cloud/GitAccessDeniedException.java`：

```java
package com.checkba.version.cloud;

public class GitAccessDeniedException extends RuntimeException {
    private final int statusCode;
    public GitAccessDeniedException(int statusCode) {
        super("git access denied: " + statusCode);
        this.statusCode = statusCode;
    }
    public int statusCode() { return statusCode; }
}
```

`GitHttpController.java` 改动：注入 `GitAccessService`；三个端点入口各加一行鉴权 + 统一 catch：

```java
    // infoRefs 开头（service 校验之后、isInitialized 之前）：
    if (!deny(response, () -> access.authorize(request, projectId, RECEIVE_PACK.equals(service)))) return;
    // uploadPack 开头：
    if (!deny(response, () -> access.authorize(request, projectId, false))) return;
    // receivePack 开头：
    if (!deny(response, () -> access.authorize(request, projectId, true))) return;

    /** 鉴权失败时写响应并返回 false。401 带 WWW-Authenticate，JGit 客户端靠它重试凭据。 */
    private boolean deny(HttpServletResponse response, java.util.function.Supplier<Long> auth)
            throws IOException {
        try {
            auth.get();
            return true;
        } catch (GitAccessDeniedException e) {
            if (e.statusCode() == 401) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"AIWorkdeck Git\"");
            }
            response.sendError(e.statusCode());
            return false;
        }
    }
```

- [ ] **Step 4: 更新 Task 1 的集成测试加真实凭据**

`GitHttpProtocolTest.java`：注入 `UserRepository`/`ProjectRepository`/`DeviceTokenService`（`@Autowired`）；`seedProject` 里建 User（`username="git_user_"+projectId`，密码任意 BCrypt）、建 Project（`userId=该用户`，owner 即有读写权）、发令牌存字段；三个用例的 clone/push 全部加 `.setCredentialsProvider(new UsernamePasswordCredentialsProvider("git_user", token))`；新增第 4 用例：

```java
    @Test
    void wrongTokenIsRejected(@TempDir Path clientDir) throws Exception {
        seedProject(9L);
        Exception e = assertThrows(Exception.class, () -> Git.cloneRepository()
                .setURI(remoteUrl(9L))
                .setDirectory(clientDir.toFile())
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("u", "awdt_bad"))
                .call());
        assertTrue(e.getMessage().contains("not authorized")
                || e.getMessage().contains("401") || e.getMessage().contains("403"));
    }
```

建 Project 行用 `new Project()` + setName/setProjectType("BLANK")/setUserId + `projectRepository.save`（Project 实体手写 setter，字段见 Project.java:26-79）。

- [ ] **Step 5: 跑测试确认通过**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest='GitAccessServiceTest,GitHttpProtocolTest'
```
Expected: 全绿（3 + 4 用例）。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/checkba/version/cloud/ backend/src/test/java/com/checkba/version/cloud/
git commit -m "feat(version): Git 端点鉴权——设备令牌 Basic + 成员矩阵，CLIENT 一律拒绝

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 4: 清单 v2——ProjectFile.uid、capture/apply 跨机器身份、v1 兼容

**Files:**
- Modify: `backend/src/main/java/com/checkba/model/entity/ProjectFile.java`（uid 列）
- Modify: `backend/src/main/java/com/checkba/version/TreeManifest.java`（v2 schema）
- Modify: `backend/src/main/java/com/checkba/version/ProjectTreeManifestService.java`（capture v2 + 归一化 + 构造器加依赖）
- Test: `backend/src/test/java/com/checkba/version/TreeManifestV2Test.java`
- Modify: 所有手工 `new ProjectTreeManifestService(...)` / `new TreeManifest.Node(...)` 的测试（编译错误定位，见 Step 4）

**Interfaces:**
- Consumes: `repoRelative(long, String)`（ProjectTreeManifestService.java:237，`projects/{id}/xxx → xxx`，前缀不符返回 null）；apply 内核（:127）的 id 匹配 + remap 机制原样复用。
- Produces: `TreeManifest.CURRENT_VERSION = 2`；`Node` record 新增 `uid/parentUid/relPath/author` 四字段（v2 写这四个 + 共享字段，`id/parentId/filePath/userId` 置 null）；`capture()` 产出 v2 并回填 `ProjectFile.uid`；`applyToDatabase`/`unionApply` 对 v2 清单按 uid 匹配、对 v1 清单行为一字不改。构造器变为 `(ProjectFileRepository, ProjectRepoService, ObjectMapper, UserRepository, ProjectRepository)`。

**策略（零重写内核）**：v2 清单在 apply 入口先「归一化」成 v1 形状——uid 命中本地行的节点得到该行的真实 id；未命中的节点分配**合成负 id**（互不相同）；parentUid 经同一张 uid→id 表换算成 parentId；relPath 加回本机前缀 `projects/{projectId}/`；author 用户名解析成本地 userId（用户名命中 → 命中行的 userId → 项目 owner 三级回退）。然后喂给现有 `apply` 内核：合成负 id 走「清单有、库无」路径，Spring Data save() 对未知 id 生成新 id + remap 修 parentId——这正是 v1 用户已裁决接受的机制（执行台账 T6）。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/checkba/version/TreeManifestV2Test.java`（style A：真实临时仓库 + HashMap 桩 repo，照 DraftAdoptTest.java:42-99 的形制）：

```java
package com.checkba.version;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TreeManifestV2Test {

    private Path root;
    private ProjectRepoService repoSvc;
    private ProjectTreeManifestService manifestSvc;
    private final Map<Long, ProjectFile> db = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.createDirectories(root.resolve("projects/9"));
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(props);

        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        // 模拟 IDENTITY 语义：id 为 null 或库里不存在（含归一化的合成负 id）都视为
        // 插入并重新分配 id——与生产里 Spring Data merge 的行为一致（v1 T6 裁决）。
        when(fileRepo.save(any())).thenAnswer(inv -> {
            ProjectFile f = inv.getArgument(0);
            if (f.getId() == null || !db.containsKey(f.getId())) {
                ProjectFile copy = new ProjectFile();
                copy.setId(ids.getAndIncrement());
                copy.setProjectId(f.getProjectId());
                copy.setParentId(f.getParentId());
                copy.setIsFolder(f.getIsFolder());
                copy.setName(f.getName());
                copy.setFileType(f.getFileType());
                copy.setSortOrder(f.getSortOrder());
                copy.setFilePath(f.getFilePath());
                copy.setUserId(f.getUserId());
                copy.setIsDeleted(f.getIsDeleted());
                copy.setUid(f.getUid());
                copy.setCreatedAt(f.getCreatedAt());
                db.put(copy.getId(), copy);
                return copy;
            }
            db.put(f.getId(), f);
            return f;
        });
        when(fileRepo.findByProjectId(anyLong())).thenAnswer(inv -> {
            Long pid = inv.getArgument(0);
            return db.values().stream().filter(f -> pid.equals(f.getProjectId())).toList();
        });

        UserRepository userRepo = mock(UserRepository.class);
        User zewei = new User();
        zewei.setId(1L);
        zewei.setUsername("hanzewei");
        when(userRepo.findByUsername(anyString())).thenAnswer(inv ->
                "hanzewei".equals(inv.getArgument(0)) ? Optional.of(zewei) : Optional.empty());
        when(userRepo.findById(anyLong())).thenAnswer(inv ->
                Long.valueOf(1L).equals(inv.getArgument(0)) ? Optional.of(zewei) : Optional.empty());

        ProjectRepository projectRepo = mock(ProjectRepository.class);
        Project p9 = new Project();
        p9.setId(9L);
        p9.setUserId(2L); // 项目 9 的 owner 是另一个人：author 回退到 owner 的用例吃它
        when(projectRepo.findById(anyLong())).thenAnswer(inv ->
                Long.valueOf(9L).equals(inv.getArgument(0)) ? Optional.of(p9) : Optional.empty());

        manifestSvc = new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper(),
                userRepo, projectRepo);
    }

    private ProjectFile seedRow(long projectId, Long parentId, String name,
                                boolean folder, String filePath) {
        ProjectFile f = new ProjectFile();
        f.setId(ids.getAndIncrement());
        f.setProjectId(projectId);
        f.setParentId(parentId);
        f.setIsFolder(folder);
        f.setName(name);
        f.setSortOrder(0);
        f.setFilePath(filePath);
        f.setUserId(1L);
        f.setIsDeleted(false);
        f.setCreatedAt(LocalDateTime.now());
        db.put(f.getId(), f);
        return f;
    }

    @Test
    void captureProducesV2AndBackfillsUids() {
        ProjectFile folder = seedRow(7L, null, "合同", true, null);
        seedRow(7L, folder.getId(), "股权协议.docx", false,
                "projects/7/合同/股权协议.docx");

        TreeManifest m = manifestSvc.capture(7L);

        assertEquals(2, m.version());
        assertEquals(2, m.nodes().size());
        assertTrue(m.nodes().stream().allMatch(n -> n.uid() != null && !n.uid().isBlank()));
        assertTrue(db.values().stream().allMatch(f -> f.getUid() != null)); // 回填进了库
        TreeManifest.Node child = m.nodes().stream()
                .filter(n -> !n.isFolder()).findFirst().orElseThrow();
        assertEquals("合同/股权协议.docx", child.relPath());   // 前缀已剥
        assertNull(child.id());                                 // 本地 id 不出仓
        assertNull(child.filePath());
        assertEquals("hanzewei", child.author());
        TreeManifest.Node parent = m.nodes().stream()
                .filter(TreeManifest.Node::isFolder).findFirst().orElseThrow();
        assertEquals(parent.uid(), child.parentUid());
    }

    @Test
    void v2AppliesToAnotherProjectWithFreshIdsAndTranslatedPaths() {
        ProjectFile folder = seedRow(7L, null, "合同", true, null);
        seedRow(7L, folder.getId(), "股权协议.docx", false,
                "projects/7/合同/股权协议.docx");
        TreeManifest m = manifestSvc.capture(7L);

        var report = manifestSvc.applyToDatabase(9L, m);

        assertEquals(2, report.created());
        List<ProjectFile> rows = db.values().stream()
                .filter(f -> f.getProjectId().equals(9L)).toList();
        assertEquals(2, rows.size());
        ProjectFile child9 = rows.stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsFolder())).findFirst().orElseThrow();
        assertEquals("projects/9/合同/股权协议.docx", child9.getFilePath()); // 本机前缀
        ProjectFile folder9 = rows.stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsFolder())).findFirst().orElseThrow();
        assertEquals(folder9.getId(), child9.getParentId());               // 父链经 remap 修好
        assertEquals(1L, child9.getUserId());                              // author 用户名命中
    }

    @Test
    void reapplyMatchesByUidWithoutDuplicating() {
        seedRow(7L, null, "备忘录.md", false, "projects/7/备忘录.md");
        TreeManifest m = manifestSvc.capture(7L);
        manifestSvc.applyToDatabase(9L, m);
        long countAfterFirst = db.values().stream().filter(f -> f.getProjectId().equals(9L)).count();

        var second = manifestSvcApplyRenamed(m);

        assertEquals(countAfterFirst,
                db.values().stream().filter(f -> f.getProjectId().equals(9L)).count()); // 没翻倍
        assertEquals(0, second.created());
        assertTrue(db.values().stream().anyMatch(f ->
                f.getProjectId().equals(9L) && "改名后.md".equals(f.getName())));
    }

    /** 同 uid、改了名的清单再 apply 一次：按 uid 命中做属性更新。 */
    private ProjectTreeManifestService.SyncReport manifestSvcApplyRenamed(TreeManifest m) {
        TreeManifest.Node n = m.nodes().get(0);
        TreeManifest renamed = new TreeManifest(2, List.of(new TreeManifest.Node(
                null, null, "改名后.md", n.isFolder(), n.fileType(), n.sortOrder(),
                null, n.isDeleted(), null,
                n.uid(), n.parentUid(), "改名后.md", n.author())));
        return manifestSvc.applyToDatabase(9L, renamed);
    }

    @Test
    void unknownAuthorFallsBackToProjectOwner() throws Exception {
        String v2Json = """
                {"version":2,"nodes":[{"name":"外来.md","isFolder":false,"sortOrder":0,
                "isDeleted":false,"uid":"u-1","relPath":"外来.md","author":"stranger"}]}
                """;
        TreeManifest m = new ObjectMapper().readValue(v2Json, TreeManifest.class);
        manifestSvc.applyToDatabase(9L, m);
        ProjectFile row = db.values().stream()
                .filter(f -> f.getProjectId().equals(9L)).findFirst().orElseThrow();
        assertEquals(2L, row.getUserId()); // 项目 9 的 owner
    }

    @Test
    void v1ManifestStillAppliesById() throws Exception {
        ProjectFile old = seedRow(9L, null, "旧文件.md", false, "projects/9/旧文件.md");
        String v1Json = """
                {"version":1,"nodes":[{"id":%d,"parentId":null,"name":"旧文件改名.md",
                "isFolder":false,"fileType":"md","sortOrder":0,
                "filePath":"projects/9/旧文件.md","isDeleted":false,"userId":1}]}
                """.formatted(old.getId());
        TreeManifest m = new ObjectMapper().readValue(v1Json, TreeManifest.class);
        var report = manifestSvc.applyToDatabase(9L, m);
        assertEquals(1, report.updated());
        assertEquals("旧文件改名.md", db.get(old.getId()).getName()); // 按 id 命中，行为与 v1 一致
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=TreeManifestV2Test
```
Expected: 编译失败（Node 无四个新字段、构造器参数不符、ProjectFile 无 uid）。

- [ ] **Step 3: 实现**

`ProjectFile.java`：`isDeleted` 字段旁新增（手写风格）：

```java
    /** 跨机器稳定身份（清单 v2）。本机新建行由 capture 回填，克隆行来自云端清单。 */
    @Column(length = 36)
    private String uid;

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }
```

`TreeManifest.java` 整体替换：

```java
package com.checkba.version;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * .awd/tree.json 的 Java 表示。
 * v1：节点带本机数据库 id/parentId/filePath/userId——只在单机内有意义。
 * v2：身份改稳定 uid（UUID），路径存 repo 相对 relPath，署名存 author 用户名——
 * 跨机器可用（云端协作前提）。v2 清单里 v1 那四个本机字段一律 null 不落盘。
 * 读取端两版都认：apply 入口按 version 分派（v2 先归一化，v1 原路径）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TreeManifest(int version, List<Node> nodes) {

    public static final int CURRENT_VERSION = 2;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(Long id, Long parentId, String name, boolean isFolder,
                       String fileType, Integer sortOrder, String filePath,
                       boolean isDeleted, Long userId,
                       String uid, String parentUid, String relPath, String author) {}
}
```

`ProjectTreeManifestService.java` 四处改动：

1. 构造器加 `UserRepository userRepository, ProjectRepository projectRepository` 两参（字段照存）。
2. `capture()` 整体替换（回填 uid + 产出 v2）：

```java
    /** 从数据库采集当前文件树（清单 v2）。软删除节点也收；存量行缺 uid 就地回填。 */
    @Transactional
    public TreeManifest capture(long projectId) {
        try {
            List<ProjectFile> files = projectFileRepository.findByProjectId(projectId)
                    .stream().sorted(Comparator.comparing(ProjectFile::getId)).toList();
            for (ProjectFile f : files) {
                if (f.getUid() == null || f.getUid().isBlank()) {
                    f.setUid(UUID.randomUUID().toString());
                    projectFileRepository.save(f);
                }
            }
            Map<Long, String> uidById = new HashMap<>();
            for (ProjectFile f : files) uidById.put(f.getId(), f.getUid());
            Map<Long, String> authorCache = new HashMap<>();
            List<TreeManifest.Node> nodes = files.stream()
                    .map(f -> new TreeManifest.Node(
                            null, null, f.getName(),
                            Boolean.TRUE.equals(f.getIsFolder()),
                            f.getFileType(), f.getSortOrder(), null,
                            Boolean.TRUE.equals(f.getIsDeleted()), null,
                            f.getUid(),
                            f.getParentId() == null ? null : uidById.get(f.getParentId()),
                            repoRelative(projectId, f.getFilePath()),
                            authorUsername(f.getUserId(), authorCache)))
                    .toList();
            return new TreeManifest(TreeManifest.CURRENT_VERSION, nodes);
        } catch (Exception e) {
            throw new VersionException("采集文件树清单失败: project=" + projectId, e);
        }
    }

    private String authorUsername(Long userId, Map<Long, String> cache) {
        if (userId == null) return null;
        return cache.computeIfAbsent(userId, id ->
                userRepository.findById(id).map(User::getUsername).orElse(null));
    }
```

3. `apply()` 入口第一行分派（`List<TreeManifest.Node> ordered = topoSort(manifest.nodes());` 改为）：

```java
            List<TreeManifest.Node> effective = manifest.version() >= 2
                    ? normalizeV2(projectId, manifest.nodes()) : manifest.nodes();
            List<TreeManifest.Node> ordered = topoSort(effective);
```

新增归一化方法（`repoRelative` 旁）：

```java
    /**
     * v2 → v1 形状：uid 命中本地行的节点换成该行真实 id；未命中的分配互不相同的
     * 合成负 id（走「清单有、库无」路径，save 时被 IDENTITY 重新分配 + remap 修
     * parentId——v1 既有机制）；relPath 加回本机前缀；author 三级回退解析 userId。
     */
    private List<TreeManifest.Node> normalizeV2(long projectId, List<TreeManifest.Node> nodes) {
        Map<String, ProjectFile> byUid = new HashMap<>();
        for (ProjectFile f : projectFileRepository.findByProjectId(projectId)) {
            if (f.getUid() != null) byUid.put(f.getUid(), f);
        }
        Long ownerId = projectRepository.findById(projectId)
                .map(Project::getUserId).orElse(null);
        Map<String, Long> idByUid = new HashMap<>();
        long synthetic = -1;
        for (TreeManifest.Node n : nodes) {
            ProjectFile match = n.uid() == null ? null : byUid.get(n.uid());
            idByUid.put(n.uid(), match != null ? match.getId() : synthetic--);
        }
        Map<String, Long> userIdCache = new HashMap<>();
        List<TreeManifest.Node> out = new ArrayList<>();
        for (TreeManifest.Node n : nodes) {
            ProjectFile match = n.uid() == null ? null : byUid.get(n.uid());
            Long userId = null;
            if (n.author() != null) {
                userId = userIdCache.computeIfAbsent(n.author(), name ->
                        userRepository.findByUsername(name).map(User::getId).orElse(null));
            }
            if (userId == null && match != null) userId = match.getUserId();
            if (userId == null) userId = ownerId;
            out.add(new TreeManifest.Node(
                    idByUid.get(n.uid()),
                    n.parentUid() == null ? null : idByUid.get(n.parentUid()),
                    n.name(), n.isFolder(), n.fileType(), n.sortOrder(),
                    n.relPath() == null ? null : "projects/" + projectId + "/" + n.relPath(),
                    n.isDeleted(), userId,
                    n.uid(), n.parentUid(), n.relPath(), n.author()));
        }
        return out;
    }
```

4. `applyAttributes()` 里补 uid 同步（`fileType` 行旁）：

```java
        if (n.uid() != null && !Objects.equals(f.getUid(), n.uid())) { f.setUid(n.uid()); changed = true; }
```

- [ ] **Step 4: 修编译连锁**

```bash
cd backend && grep -rn "new ProjectTreeManifestService\|new TreeManifest.Node" src/test src/main | grep -v ProjectTreeManifestService.java
```
逐个修：`new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper())` → 补 `mock(UserRepository.class), mock(ProjectRepository.class)` 两参（DraftAdoptTest/DraftLifecycleTest/DraftSessionGuardTest/WorkSessionServiceTest/TreeManifestSyncTest/TreeManifestCaptureTest 等）；测试里 9 参 `new TreeManifest.Node(...)` → 尾部补 `, null, null, null, null`。另外跑 `grep -rn "new AgentOrchestrator\|new WorkSessionService" backend/src/test` 确认 EvalHarness 是否受传染（本任务不动 WorkSessionService 构造器，理论无涉，防御性检查——v1 地雷 #19 三次踩过）。

- [ ] **Step 5: 跑测试 + 全量回归**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=TreeManifestV2Test
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
```
Expected: 新测试 5/5；全量 0 fail。特别关注 `TreeManifestSyncTest`/`TreeManifestCaptureTest`/`DraftAdoptTest`（capture 变 v2 后，这些测试若断言了 version==1 或 Node 里的本机字段要按 v2 事实修正——修断言不是修行为，行为变化就是本任务的交付物；但 v1 清单**读取**路径的断言绝不能动）。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/checkba/model/entity/ProjectFile.java backend/src/main/java/com/checkba/version/TreeManifest.java backend/src/main/java/com/checkba/version/ProjectTreeManifestService.java backend/src/test/
git commit -m "feat(version): 清单 v2——uid 稳定身份/相对路径/用户名署名，跨机器可用，v1 兼容

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 5: ProjectRepoService 远端原语——setRemote/fetch/push

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/ProjectRepoService.java`
- Test: `backend/src/test/java/com/checkba/version/ProjectRepoRemoteTest.java`

**Interfaces:**
- Consumes: JGit 核心 `RemoteSetUrlCommand`/`FetchCommand`/`PushCommand`/`UsernamePasswordCredentialsProvider`（全在既有依赖内）。
- Produces:
  - `public void setRemoteOrigin(long projectId, String url)`——建/改 origin。
  - `public String fetchFromOrigin(long projectId, String username, String token)`——抓 master + 里程碑标签，返回 `refs/remotes/origin/master` 的 sha（远端空仓返回 null）。
  - `public record PushOutcome(boolean pushed, boolean rejected, String message) {}`
  - `public PushOutcome pushMainlineToOrigin(long projectId, String username, String token)`——推 master（非强制）+ 里程碑标签（强制，重命名覆盖语义随行）；被拒（非快进等）时 `rejected=true` 不抛异常。
  - `public String originMasterSha(long projectId)`——本地已知的 origin/master（不联网）。
  - `public boolean isAncestor(long projectId, String ancestorRef, String descendantRef)`——RevWalk.isMergedInto；Task 7/9 判快进与「主线是否被推进」用。
  - `public String remoteOriginUrl(long projectId)`——读 origin URL，未配置返回 null。

异常纪律：网络失败抛技术档 `VersionException`（调用方 CloudSyncService 负责按「云端不可达只置状态」消化，v1 地雷 #5 的网络层延伸）；`pushMainlineToOrigin` 的「被拒」不是异常是正常业务结果，走返回值。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/checkba/version/ProjectRepoRemoteTest.java`（style B：`seeded(root)` 工厂 + `file://` 裸仓库当远端——file transport 不验凭据，凭据参数传占位）：

```java
package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepoRemoteTest {

    private ProjectRepoService seeded(Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService s = new ProjectRepoService(props);
        s.init(7L, "韩泽伟", "hzw@example.com");
        return s;
    }

    private String bareRemote(Path dir) throws Exception {
        Git.init().setBare(true).setDirectory(dir.toFile())
                .setInitialBranch("master").call().close();
        return dir.toUri().toString();
    }

    @Test
    void pushThenFetchRoundTrip(@TempDir Path root, @TempDir Path remote) throws Exception {
        ProjectRepoService s = seeded(root);
        s.setRemoteOrigin(7L, bareRemote(remote));

        ProjectRepoService.PushOutcome out = s.pushMainlineToOrigin(7L, "u", "t");
        assertTrue(out.pushed());
        assertFalse(out.rejected());

        String remoteSha = s.fetchFromOrigin(7L, "u", "t");
        assertEquals(s.resolveRef(7L, "master"), remoteSha);
        assertEquals(remoteSha, s.originMasterSha(7L));
    }

    @Test
    void divergedPushIsRejectedNotThrown(@TempDir Path root, @TempDir Path remote,
                                         @TempDir Path other) throws Exception {
        ProjectRepoService s = seeded(root);
        String url = bareRemote(remote);
        s.setRemoteOrigin(7L, url);
        assertTrue(s.pushMainlineToOrigin(7L, "u", "t").pushed());

        // 第二个"同事"仓库把远端 master 推进一步
        try (Git peer = Git.cloneRepository().setURI(url).setDirectory(other.toFile()).call()) {
            Files.writeString(other.resolve("合同.txt"), "同事的第二稿");
            peer.add().addFilepattern(".").call();
            peer.commit().setMessage("同事修改").setAuthor("同事", "p@example.com").call();
            peer.push().call();
        }

        // 本地也前进一步 → 推送分叉，应被拒而非抛异常
        Files.writeString(root.resolve("projects/7/合同.txt"), "我的第二稿");
        s.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        ProjectRepoService.PushOutcome out = s.pushMainlineToOrigin(7L, "u", "t");
        assertFalse(out.pushed());
        assertTrue(out.rejected());

        // fetch 后 isAncestor 能判「主线被别人推进」
        String remoteSha = s.fetchFromOrigin(7L, "u", "t");
        assertNotNull(remoteSha);
        assertFalse(s.isAncestor(7L, remoteSha, "master"));
        assertTrue(s.isAncestor(7L, "master^", "master"));
    }

    @Test
    void milestoneTagsTravelWithPush(@TempDir Path root, @TempDir Path remote) throws Exception {
        ProjectRepoService s = seeded(root);
        s.setRemoteOrigin(7L, bareRemote(remote));
        String sha = s.resolveRef(7L, "master");
        s.tagMilestone(7L, sha, "定稿");
        assertTrue(s.pushMainlineToOrigin(7L, "u", "t").pushed());

        try (Git remoteGit = Git.open(Path.of(java.net.URI.create(
                s.remoteOriginUrl(7L))).toFile())) {
            assertFalse(remoteGit.tagList().call().isEmpty());
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ProjectRepoRemoteTest
```
Expected: 编译失败（新方法不存在）。

- [ ] **Step 3: 实现（ProjectRepoService 类尾、gc 之前插入）**

```java
    // ==================== 远端（v2 云端协作） ====================

    private static final String ORIGIN = "origin";
    private static final String ORIGIN_MASTER = "refs/remotes/origin/master";
    private static final String MILESTONE_SPEC =
            "+refs/tags/awd/milestone/*:refs/tags/awd/milestone/*";

    /** 建/改 origin。幂等：已存在则改 URL。 */
    public void setRemoteOrigin(long projectId, String url) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            org.eclipse.jgit.transport.URIish uri = new org.eclipse.jgit.transport.URIish(url);
            if (repo.getConfig().getSubsections("remote").contains(ORIGIN)) {
                org.eclipse.jgit.api.RemoteSetUrlCommand cmd = git.remoteSetUrl();
                cmd.setRemoteName(ORIGIN);
                cmd.setRemoteUri(uri);
                cmd.call();
            } else {
                org.eclipse.jgit.api.RemoteAddCommand cmd = git.remoteAdd();
                cmd.setName(ORIGIN);
                cmd.setUri(uri);
                cmd.call();
            }
        } catch (Exception e) {
            throw new VersionException("配置云端地址失败: project=" + projectId, e);
        }
    }

    /** 读 origin URL；未配置返回 null。 */
    public String remoteOriginUrl(long projectId) {
        try (Repository repo = open(projectId)) {
            String url = repo.getConfig().getString("remote", ORIGIN, "url");
            return (url == null || url.isBlank()) ? null : url;
        } catch (Exception e) {
            throw new VersionException("读取云端地址失败: project=" + projectId, e);
        }
    }

    /** 抓 master + 里程碑标签；返回抓完后的 origin/master sha（远端空仓返回 null）。 */
    public String fetchFromOrigin(long projectId, String username, String token) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.fetch()
                    .setRemote(ORIGIN)
                    .setCredentialsProvider(
                            new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                                    username == null ? "" : username, token == null ? "" : token))
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                            "+refs/heads/master:" + ORIGIN_MASTER),
                            new org.eclipse.jgit.transport.RefSpec(MILESTONE_SPEC))
                    .setTimeout(60)
                    .call();
            return originMasterSha(projectId);
        } catch (Exception e) {
            throw new VersionException("从云端更新失败: project=" + projectId, e);
        }
    }

    /** 本地已知的 origin/master（不联网）；没有返回 null。 */
    public String originMasterSha(long projectId) {
        return resolveRef(projectId, ORIGIN_MASTER);
    }

    public record PushOutcome(boolean pushed, boolean rejected, String message) {}

    /**
     * 推 master（非强制——被拒即「主线被别人推进」，走返回值不走异常）
     * + 里程碑标签（强制——重命名即覆盖是 tagMilestone 的既有语义，随行到云端）。
     */
    public PushOutcome pushMainlineToOrigin(long projectId, String username, String token) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            Iterable<org.eclipse.jgit.transport.PushResult> results = git.push()
                    .setRemote(ORIGIN)
                    .setCredentialsProvider(
                            new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                                    username == null ? "" : username, token == null ? "" : token))
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                            "refs/heads/master:refs/heads/master"),
                            new org.eclipse.jgit.transport.RefSpec(MILESTONE_SPEC))
                    .setTimeout(60)
                    .call();
            boolean rejected = false;
            StringBuilder msg = new StringBuilder();
            for (org.eclipse.jgit.transport.PushResult r : results) {
                for (org.eclipse.jgit.transport.RemoteRefUpdate u : r.getRemoteUpdates()) {
                    switch (u.getStatus()) {
                        case OK, UP_TO_DATE -> { }
                        case REJECTED_NONFASTFORWARD, REJECTED_OTHER_REASON,
                             REJECTED_REMOTE_CHANGED -> rejected = true;
                        default -> {
                            rejected = true;
                            msg.append(u.getStatus()).append(' ');
                        }
                    }
                }
            }
            return new PushOutcome(!rejected, rejected, msg.toString().trim());
        } catch (Exception e) {
            throw new VersionException("上传到云端失败: project=" + projectId, e);
        }
    }

    /** ancestorRef 是否在 descendantRef 的历史里（快进判定/主线被推进判定）。 */
    public boolean isAncestor(long projectId, String ancestorRef, String descendantRef) {
        try (Repository repo = open(projectId);
             org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
            var a = repo.resolve(ancestorRef);
            var d = repo.resolve(descendantRef);
            if (a == null || d == null) return false;
            return walk.isMergedInto(walk.parseCommit(a), walk.parseCommit(d));
        } catch (Exception e) {
            throw new VersionException("比较版本先后失败: project=" + projectId, e);
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ProjectRepoRemoteTest
```
Expected: 3/3。若 `remoteSetUrl`/`remoteAdd` 的 API 形态与 6.9 不符（这两个命令在 6.x 间改过 setter 名），以编译器为准就地适配——语义不变。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/checkba/version/ProjectRepoService.java backend/src/test/java/com/checkba/version/ProjectRepoRemoteTest.java
git commit -m "feat(version): 远端原语——setRemote/fetch/push/isAncestor，兑现 v1 预留

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 6: 服务端 push 落库——锁内接收、脏区停靠、路径级物化、待同步延后

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`
- Modify: `backend/src/main/java/com/checkba/version/cloud/GitHttpController.java`
- Test: `backend/src/test/java/com/checkba/version/cloud/GitHttpIngestTest.java`

**Interfaces:**
- Consumes: `restoreWorkTreeFrom(long, String ref, List<FileChange>)`（WorkSessionService.java:1220，私有，同类内调用）；`syncManifestFromRef(long, String)`（:1238）；`diffNameStatus`；`repoLock(long)`（:69）。
- Produces: `WorkSessionService.runLocked(long projectId, Runnable body)`（public——GitHttpController 包 receive 用）；`ingestPushedMainline(long projectId, String oldSha, String newSha)`（public）；`dockDirtyMainlineForReceive(long projectId)`（public，pre-receive 停靠）；`retryPendingIngest(long projectId)`（public）。GitHttpController 的 `configureReceivePack` 挂 Pre/PostReceiveHook。

**核心风险（本任务存在的理由）**：receive-pack 只挪 master ref 不碰工作区。ref 挪完、工作区还是旧内容的窗口里，任何 `commitAll`（防抖自动存档！）都会把**旧内容**当成「相对新 master 的改动」提交——同事刚推上来的内容被静默回滚。堵法有三层：① 整个 receive 在 `repoLock` 内跑（`runLocked` 包住 `rp.receive(...)`），与一切本地提交路径互斥；② PreReceiveHook 先停靠脏区——HEAD 在 master、工作区脏且无工作段时（v1「脏但无段」的四条路径）先落一笔无主 auto 存档，master 因此前进、这次 push 的 old-sha 对不上被 git 原生拒绝（客户端按被拒→fetch→合并→重推的正常循环走）；③ PostReceiveHook 内同步物化（路径级 `restoreWorkTreeFrom`，不做全树 reset）+ 清单落库。守卫不满足（MERGING/有工作段/在稿上/HEAD 不在 master）时记 pending 延后，在 `pendingChangesLocked`、`endSession`、`discardSession` 收尾处补做。pending 是内存态，服务端重启丢失（下一次 push 或工作段收尾会自愈）——记入领域文档。

- [ ] **Step 1: 写失败的集成测试**

`backend/src/test/java/com/checkba/version/cloud/GitHttpIngestTest.java`（沿 GitHttpProtocolTest 的 SpringBootTest 骨架 + 凭据；额外 `@Autowired WorkSessionService sessionService; @Autowired ProjectFileRepository fileRepository; @Autowired UserRepository userRepository; @Autowired ProjectRepository projectRepository;`）：

```java
    /** seed：项目行 + 文件行 + enableVersionRecording（初始提交自带 v2 清单）。 */
    private String seedRecordedProject(long projectId) throws Exception {
        // 建 User/Project/令牌同 GitHttpProtocolTest.seedProject；再：
        Files.createDirectories(root.resolve("projects/" + projectId));
        Files.writeString(root.resolve("projects/" + projectId + "/合同.txt"), "初稿");
        ProjectFile f = new ProjectFile();
        f.setProjectId(projectId);
        f.setIsFolder(false);
        f.setName("合同.txt");
        f.setSortOrder(0);
        f.setFilePath("projects/" + projectId + "/合同.txt");
        f.setUserId(seededUserId);
        f.setIsDeleted(false);
        f.setCreatedAt(LocalDateTime.now());
        fileRepository.save(f);
        sessionService.enableVersionRecording(projectId, "韩泽伟", "hzw@example.com");
        return token;
    }

    @Test
    void pushMaterialisesWorkTreeAndDatabase(@TempDir Path clientDir) throws Exception {
        String token = seedRecordedProject(21L);
        try (Git client = clone(21L, clientDir, token)) {
            // 改既有文件 + 在清单里添一个新节点（模拟另一台桌面端的 capture 产物）
            Files.writeString(clientDir.resolve("合同.txt"), "第二稿");
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            var manifest = om.readValue(clientDir.resolve(".awd/tree.json").toFile(),
                    com.checkba.version.TreeManifest.class);
            var nodes = new java.util.ArrayList<>(manifest.nodes());
            nodes.add(new com.checkba.version.TreeManifest.Node(
                    null, null, "新文件.txt", false, "txt", 1, null, false, null,
                    "uid-new-1", null, "新文件.txt", "git_user_21"));
            Files.writeString(clientDir.resolve(".awd/tree.json"),
                    om.writerWithDefaultPrettyPrinter().writeValueAsString(
                            new com.checkba.version.TreeManifest(2, nodes)));
            Files.writeString(clientDir.resolve("新文件.txt"), "同事新增");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("同事修改\n\nX-AWD-Kind: session")
                    .setAuthor("同事", "p@example.com").call();
            push(client, token);
        }
        // 服务端工作区被物化 + 数据库出现新行
        assertEquals("第二稿", Files.readString(root.resolve("projects/21/合同.txt")));
        assertEquals("同事新增", Files.readString(root.resolve("projects/21/新文件.txt")));
        assertTrue(fileRepository.findByProjectId(21L).stream()
                .anyMatch(f -> "新文件.txt".equals(f.getName())));
    }

    @Test
    void ingestIsDeferredWhileSessionActiveAndCaughtUpOnDiscard(@TempDir Path clientDir)
            throws Exception {
        String token = seedRecordedProject(22L);
        try (Git client = clone(22L, clientDir, token)) {
            // 服务端开一段工作（HEAD 切去 work/*）
            sessionService.onChangeSignal(22L, seededUserId, "网页端用户");
            sessionService.commitNow(22L, seededUserId, "网页端用户", null);
            // 客户端推进 master
            Files.writeString(clientDir.resolve("合同.txt"), "第二稿");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("同事修改").setAuthor("同事", "p@example.com").call();
            push(client, token);
            // 延后：工作区不动（工作段的内容还端着）
            assertNotEquals("第二稿", Files.readString(root.resolve("projects/22/合同.txt")));
            // 丢弃工作段 → 补做同步
            sessionService.discardSession(22L, seededUserId);
            assertEquals("第二稿", Files.readString(root.resolve("projects/22/合同.txt")));
        }
    }

    @Test
    void dirtyMainlineIsDockedAndPushRejected(@TempDir Path clientDir) throws Exception {
        String token = seedRecordedProject(23L);
        try (Git client = clone(23L, clientDir, token)) {
            // 服务端主线脏且无段（「脏但无段」路径）：直接写盘不发信号
            Files.writeString(root.resolve("projects/23/合同.txt"), "网页端未存档的编辑");
            Files.writeString(clientDir.resolve("合同.txt"), "第二稿");
            client.add().addFilepattern(".").call();
            client.commit().setMessage("同事修改").setAuthor("同事", "p@example.com").call();
            var results = client.push().setCredentialsProvider(creds(token))
                    .setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master")).call();
            boolean rejected = false;
            for (var r : results)
                for (var u : r.getRemoteUpdates())
                    if (u.getStatus() != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK
                            && u.getStatus() != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE)
                        rejected = true;
            assertTrue(rejected, "脏区停靠后 master 已前进，这次 push 必须被拒");
            // 网页端的编辑没有丢：停靠提交已落在 master 上
            assertEquals("网页端未存档的编辑",
                    Files.readString(root.resolve("projects/23/合同.txt")));
        }
    }
```

（`clone`/`push`/`creds` 三个私有 helper 同 GitHttpProtocolTest 的写法带凭据；`seededUserId` 在 seed 里存字段。）

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=GitHttpIngestTest
```
Expected: 用例 1 红（工作区/DB 都没物化）、用例 2 红、用例 3 红（push 不被拒且脏编辑被后续动作冲掉）。

- [ ] **Step 3: 实现 WorkSessionService 四个方法**

（`gcLocked` 之后插入；`pendingIngestBase` 字段与其它 map 放一起：`private final Map<Long, String> pendingIngestBase = new ConcurrentHashMap<>();`）

```java
    /** 让 Git 接收端整个跑在本项目的可重入锁内，与一切本地提交路径互斥。 */
    public void runLocked(long projectId, Runnable body) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * pre-receive 停靠：HEAD 在主线、工作区脏、又没有工作段兜着（「脏但无段」）时，
     * 先落一笔无主 auto 存档。master 因此前进，这次 push 的 old-sha 对不上会被
     * git 原生拒绝——客户端走「被拒 → 从云端更新 → 重推」的正常循环，
     * 网页端未存档的编辑分毫不丢。失败吞掉（版本记录不阻断主流程）。
     */
    public void dockDirtyMainlineForReceive(long projectId) {
        try {
            if (awaitingAdoptResolution(projectId)) return;
            if (onDraftBranch(projectId)) return;
            if (activeSession(projectId).isPresent()) return;
            if (!repoService.mainBranch().equals(repoService.currentBranch(projectId))) return;
            if (repoService.pendingChanges(projectId).isEmpty()) return;
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
            repoService.commitAll(projectId, "自动存档", "auto", null,
                    "AI Workdeck", "system@aiworkdeck.local");
            log.info("push 前停靠了主线脏区: project={}", projectId);
        } catch (Exception e) {
            log.warn("push 前停靠失败（不阻断接收）: project={}", projectId, e);
        }
    }

    /**
     * push 使 master 前进后的落库：路径级物化工作区 + 清单同步数据库。
     * 守卫不满足时记 pending 延后（保留最早的基线 sha），由
     * {@link #retryPendingIngest} 补做。调用方已持锁（runLocked 内），锁可重入。
     */
    public void ingestPushedMainline(long projectId, String oldSha, String newSha) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            if (awaitingAdoptResolution(projectId)
                    || activeSession(projectId).isPresent()
                    || onDraftBranch(projectId)
                    || !repoService.mainBranch().equals(repoService.currentBranch(projectId))) {
                pendingIngestBase.putIfAbsent(projectId, oldSha);
                log.info("push 落库延后: project={}, base={}", projectId, oldSha);
                return;
            }
            // 口径同 revertTo：diffNameStatus(目标, 现状) + restoreWorkTreeFrom(目标)。
            // 现状 = 工作区还端着的 oldSha 内容，目标 = 新 master。
            List<FileChange> changes = repoService.diffNameStatus(projectId, newSha, oldSha);
            restoreWorkTreeFrom(projectId, newSha, changes);
            syncManifestFromRef(projectId, "HEAD");
            pendingIngestBase.remove(projectId);
            log.info("push 落库完成: project={}, {} 个文件", projectId, changes.size());
        } catch (Exception e) {
            pendingIngestBase.putIfAbsent(projectId, oldSha);
            log.warn("push 落库失败，转入待同步: project={}", projectId, e);
        } finally {
            lock.unlock();
        }
    }

    /** 有延后的落库且守卫已清空时补做。挂在 pendingChangesLocked 与工作段收尾处。 */
    public void retryPendingIngest(long projectId) {
        String base = pendingIngestBase.get(projectId);
        if (base == null) return;
        String head = repoService.resolveRef(projectId, repoService.mainBranch());
        if (head == null) return;
        pendingIngestBase.remove(projectId);
        ingestPushedMainline(projectId, base, head);
    }
```

挂接点（三处一行）：`pendingChangesLocked` 开头加 `retryPendingIngest(projectId);`（锁内首行）；`endSession` 成功返回前与 `discardSession` 成功返回前各加 `retryPendingIngest(projectId);`。

`GitHttpController.configureReceivePack` 实装 + receive 包锁：

```java
    private final WorkSessionService sessionService; // 构造器注入

    private void configureReceivePack(ReceivePack rp, long projectId) {
        rp.setPreReceiveHook((pack, commands) ->
                sessionService.dockDirtyMainlineForReceive(projectId));
        rp.setPostReceiveHook((pack, commands) -> {
            for (org.eclipse.jgit.transport.ReceiveCommand cmd : commands) {
                if ("refs/heads/master".equals(cmd.getRefName())
                        && cmd.getResult() == org.eclipse.jgit.transport.ReceiveCommand.Result.OK) {
                    sessionService.ingestPushedMainline(projectId,
                            cmd.getOldId().name(), cmd.getNewId().name());
                }
            }
        });
    }
```

`receivePack` 端点的 `rp.receive(...)` 调用改为包在锁里：

```java
            sessionService.runLocked(projectId, () -> {
                try {
                    rp.receive(body(request), response.getOutputStream(), null);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
```

（注意 GitHttpController 构造器加 `WorkSessionService`——纯 Spring 注入，无手工 new。PreReceiveHook 在 ref 校验前执行，停靠导致的 old-sha 不匹配由 JGit 的命令校验自然拒绝，无需自己写拒绝逻辑。）

- [ ] **Step 4: 跑测试 + 全量回归**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest='GitHttpIngestTest,GitHttpProtocolTest'
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
```
Expected: 新 3 + 旧 4 全绿；全量 0 fail。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/checkba/version/ backend/src/test/java/com/checkba/version/cloud/GitHttpIngestTest.java
git commit -m "feat(version): push 落库——锁内接收/脏区停靠/路径级物化/待同步延后

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 7: 服务端 endSession 冲突化——主线被推进时走三选一

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`
- Modify: `backend/src/main/java/com/checkba/version/VersionController.java`
- Test: `backend/src/test/java/com/checkba/version/SessionEndConflictTest.java`

**Interfaces:**
- Consumes: `mergeNoCommit`（干净也不提交，停 MERGED_NOT_COMMITTED）；`commitMergeResolution`（自带 `X-AWD-Kind: session` 尾注，ProjectRepoService.java:611 实证）；`applyResolution(projectId, path, Resolution, oursTip, theirsTip, sourceLabel)`（resolveAdopt L858 的既有私有方法，直接复用）；`userVisibleConflicts`；`isAncestor`（Task 5）；`armIdleTimer(long, Long)`（:236）。
- Produces:
  - `SessionEndResult` 改为 `record SessionEndResult(String sha, String notice, SessionEndConflict conflict)`；新增 `record SessionEndConflict(long sessionId, String title, List<String> conflictingPaths, String mainlineTip, String sessionTip)`。
  - `public SessionEndResult resolveSessionEnd(long projectId, long sessionId, Map<String, Resolution> resolutions, Long userId, String userName)`。
  - `public String abortSessionEnd(long projectId)`——返回 user-facing notice。
  - `VersionController`：`/session/end` 响应带 `conflict`；新端点 `POST /session/resolve-end`、`POST /session/abort-end`；`/status` 新字段 `sessionEndConflict`（优先于 `adoptConflict` 判定——两者都由 MERGE_HEAD 反查，工作段命中时 adoptConflict 必须为 null，防止前端弹错弹窗）。

**语义方向（务必钉死，v1 冲突弹窗装反过一次）**：结束工作时主线被同事推进 → 合并方向是「工作段并入 master」→ ours=master=**同事的**、theirs=工作段=**我这边的**。`Resolution.MAIN`=用同事的、`DRAFT`=用我这边的、`BOTH`=两份都留（副本来自工作段侧，名字「（来自：{工作段标题}）」）。前端标签在 Task 13 按此映射。桌面端不受影响：工作段期间「从云端更新」被前置挡住（Task 9），本地 master 只会被自己推进，`mainAdvanced` 恒 false 走 v1 原路径——零回归。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/checkba/version/SessionEndConflictTest.java`（style A，setUp 照 DraftAdoptTest.java:42-99；「同事推进主线」用 peer 克隆经 `file://` 直推 gitDir——工作段期间 HEAD 在 work/*，master 未检出，push 不会被 denyCurrentBranch 拒）：

```java
    /** 工作段开着的时候，用 peer 仓库把服务端 master 推进一步。 */
    private void advanceMasterFromPeer(Path peerDir, String content) throws Exception {
        String url = repoSvc.gitDir(7L).toUri().toString();
        try (Git peer = Git.cloneRepository().setURI(url).setDirectory(peerDir.toFile())
                .setBranch("master").call()) {
            Files.writeString(peerDir.resolve("合同.txt"), content);
            peer.add().addFilepattern(".").call();
            peer.commit().setMessage("同事的工作\n\nX-AWD-Kind: session")
                    .setAuthor("同事", "peer@example.com").call();
            peer.push().setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master")).call();
        }
    }

    @Test
    void endSessionOnAdvancedMainlineWithoutOverlapMergesCleanlyInOneCommit(@TempDir Path peer)
            throws Exception {
        enableAndStartSession();                       // helper：enable + onChangeSignal + commitNow
        writeAndCommit("我的文件.txt", "我的内容");      // 工作段里改不相干的文件
        advanceMasterFromPeer(peer, "同事的第二稿");
        WorkSessionService.SessionEndResult r =
                svc.endSession(7L, 1L, "韩泽伟", "两不相干");
        assertNull(r.conflict());
        assertNotNull(r.sha());
        // 双亲合并提交、两侧内容都在、清单同提交（地雷 #21 口径）
        List<VersionEntry> log = repoSvc.log(7L, "master", 10);
        assertEquals(2, log.get(0).parents().size());
        assertEquals("两不相干", log.get(0).message());
        assertEquals("同事的第二稿", Files.readString(root.resolve("projects/7/合同.txt")));
        assertEquals("我的内容", Files.readString(root.resolve("projects/7/我的文件.txt")));
        assertNotNull(repoSvc.readBlobAtCommit(7L, r.sha(), ".awd/tree.json"));
    }

    @Test
    void endSessionConflictStaysActiveAndResolvesWithBoth(@TempDir Path peer) throws Exception {
        enableAndStartSession();
        writeAndCommit("合同.txt", "我这边的第二稿");     // 与同事改同一文件 → 冲突
        advanceMasterFromPeer(peer, "同事的第二稿");
        WorkSessionService.SessionEndResult r =
                svc.endSession(7L, 1L, "韩泽伟", "撞车的工作");
        assertNull(r.sha());
        assertNotNull(r.conflict());
        assertTrue(r.conflict().conflictingPaths().contains("合同.txt"));
        assertEquals(WorkSession.Status.ACTIVE, sessionOf(r.conflict().sessionId()).getStatus());
        assertTrue(repoSvc.repositoryMerging(7L));

        WorkSessionService.SessionEndResult done = svc.resolveSessionEnd(7L,
                r.conflict().sessionId(),
                Map.of("合同.txt", WorkSessionService.Resolution.BOTH), 1L, "韩泽伟");
        assertNotNull(done.sha());
        assertFalse(repoSvc.repositoryMerging(7L));
        assertEquals(WorkSession.Status.MERGED, sessionOf(r.conflict().sessionId()).getStatus());
        assertEquals("同事的第二稿", Files.readString(root.resolve("projects/7/合同.txt")));
        // BOTH 的副本来自工作段侧
        assertTrue(Files.list(root.resolve("projects/7"))
                .map(p -> p.getFileName().toString())
                .anyMatch(n -> n.contains("来自") && n.contains("撞车的工作")));
    }

    @Test
    void abortSessionEndReturnsToBranchWithNothingLost(@TempDir Path peer) throws Exception {
        enableAndStartSession();
        writeAndCommit("合同.txt", "我这边的第二稿");
        advanceMasterFromPeer(peer, "同事的第二稿");
        WorkSessionService.SessionEndResult r = svc.endSession(7L, 1L, "韩泽伟", "撞车的工作");
        assertNotNull(r.conflict());

        String notice = svc.abortSessionEnd(7L);
        assertNotNull(notice);
        assertFalse(repoSvc.repositoryMerging(7L));
        // 回到工作段分支，内容原样
        WorkSession s = sessionOf(r.conflict().sessionId());
        assertEquals(WorkSession.Status.ACTIVE, s.getStatus());
        assertEquals(s.getBranchName(), repoSvc.currentBranch(7L));
        assertEquals("我这边的第二稿", Files.readString(root.resolve("projects/7/合同.txt")));
    }
```

（helper：`enableAndStartSession()` = `svc.enableVersionRecording(7L,...)` + `svc.onChangeSignal(7L, 1L, "韩泽伟")` + `svc.commitNow(...)`；`writeAndCommit` = 写盘 + `commitNow`；`sessionOf(id)` 从桩 sessionRepo 的 HashMap 里取。）

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=SessionEndConflictTest
```
Expected: 编译失败（SessionEndResult 是 2 参、无 resolveSessionEnd/abortSessionEnd）。

- [ ] **Step 3: 实现**

`endSession`（:427）的合并段（L457-465）替换为分叉逻辑：

```java
            repoService.checkoutBranch(projectId, repoService.mainBranch());
            String mainTipNow = repoService.resolveRef(projectId, repoService.mainBranch());
            boolean mainAdvanced = !repoService.isAncestor(
                    projectId, repoService.mainBranch(), s.getBranchName());

            if (!mainAdvanced) {
                // v1 原路径一字不改：单人场景主线没动过，merge() 的 NO_FF 语义与
                // 既有护栏（ProjectRepoBranchTest）全部照旧。
                MergeOutcome outcome = repoService.merge(
                        projectId, s.getBranchName(), finalTitle, userName, email(userName));
                if (!outcome.success()) {
                    repoService.checkoutBranch(projectId, s.getBranchName());
                    throw VersionException.userFacing("本次工作还没能收尾，你的改动都还在");
                }
                s.setStatus(WorkSession.Status.MERGED);
                s.setEndedAt(LocalDateTime.now());
                s.setTitle(finalTitle);
                sessionRepository.save(s);
                retryPendingIngest(projectId);
                log.info("结束一段工作: project={}, branch={}, title={}",
                        projectId, s.getBranchName(), finalTitle);
                return new SessionEndResult(outcome.mergeSha(), null, null);
            }

            // v2 路径：主线被同事推进（push），合并从快进降级为真合并。
            // 干净也不自动提交——清单要按数据库重算后与内容进同一个双亲提交（地雷 #21）。
            s.setTitle(finalTitle);
            sessionRepository.save(s);
            MergeOutcome outcome = repoService.mergeNoCommit(
                    projectId, s.getBranchName(), finalTitle, userName, email(userName));
            if (outcome.mergeSha() != null) {
                // ALREADY_UP_TO_DATE：理论不可达（空段已在上面筛掉），防御性收尾
                return closeMergedSession(projectId, s, outcome.mergeSha());
            }
            if (!outcome.success()) {
                // 冲突：仓库停在 MERGING，工作段保持 ACTIVE，HEAD 在主线。
                // 凡是「改了状态还要报信」的路径都用返回值，不用异常（v1 契约）。
                log.info("结束工作撞上云端新版本: project={}, session={}", projectId, s.getId());
                return new SessionEndResult(null, null, new SessionEndConflict(
                        s.getId(), finalTitle,
                        userVisibleConflicts(repoService.conflictingPaths(projectId)),
                        mainTipNow, branchTip));
            }
            return completeSessionMerge(projectId, s, mainTipNow);
```

新增三个方法（`completeAdopt` 附近）：

```java
    /** 干净或裁决后的真合并统一收尾：同事清单并集 → 按数据库重算清单 → 单一双亲提交。 */
    private SessionEndResult completeSessionMerge(long projectId, WorkSession s, String mainTipBefore) {
        TreeManifest theirs = manifestService.readAtRef(projectId, mainTipBefore);
        if (theirs != null) manifestService.unionApply(projectId, theirs);
        manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
        String sha = repoService.commitMergeResolution(projectId, s.getTitle(),
                userNameOfSession(s), email(userNameOfSession(s)));
        return closeMergedSession(projectId, s, sha);
    }

    private SessionEndResult closeMergedSession(long projectId, WorkSession s, String sha) {
        s.setStatus(WorkSession.Status.MERGED);
        s.setEndedAt(LocalDateTime.now());
        sessionRepository.save(s);
        repoService.deleteBranch(projectId, s.getBranchName(), true);
        retryPendingIngest(projectId);
        log.info("结束一段工作（真合并）: project={}, title={}", projectId, s.getTitle());
        return new SessionEndResult(sha, null, null);
    }

    private String userNameOfSession(WorkSession s) {
        // 与 endSession 调用方同一来源：调用时传入的 userName 已存进 title 流程，
        // 这里退化用「用户{id}」兜底——真实署名在 resolveSessionEnd/endSession 参数里，
        // 两个调用方都显式传 userName，本方法仅覆盖 title 已设的收尾复用。
        return "用户" + s.getUserId();
    }
```

（实现时把 `completeSessionMerge`/`closeMergedSession` 改成显式接 `userName` 参数、由 endSession/resolveSessionEnd 传入——上面 `userNameOfSession` 兜底仅防御，签名以实码为准，测试断言署名正确。）

`resolveSessionEnd` / `abortSessionEnd`（`abortAdopt` 附近，镜像 resolveAdopt 的守卫结构）：

```java
    public SessionEndResult resolveSessionEnd(long projectId, long sessionId,
                                              Map<String, Resolution> resolutions,
                                              Long userId, String userName) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            WorkSession s = sessionRepository.findById(sessionId)
                    .filter(x -> x.getProjectId().equals(projectId))
                    .filter(x -> x.getStatus() == WorkSession.Status.ACTIVE)
                    .orElseThrow(() -> VersionException.userFacing("这段工作不存在或已收尾"));
            if (!repoService.repositoryMerging(projectId)) {
                throw VersionException.userFacing("现在没有等着做选择的收尾");
            }
            String sessionTip = repoService.mergeHeadRef(projectId);
            if (sessionTip == null || !sessionTip.equals(
                    repoService.resolveRef(projectId, s.getBranchName()))) {
                throw VersionException.userFacing("正在处理的是另一件事，请先把它处理完");
            }
            String mainTip = repoService.resolveRef(projectId, "HEAD");
            List<String> rawConflicts = repoService.conflictingPaths(projectId);
            if (rawConflicts.isEmpty()) {
                throw new VersionException("冲突记录已丢失，无法安全收尾: project=" + projectId);
            }
            List<String> conflicts = userVisibleConflicts(rawConflicts);
            Map<String, Resolution> choices = resolutions == null ? Map.of() : resolutions;
            for (String path : conflicts) {
                if (choices.get(path) == null) {
                    throw VersionException.userFacing("还有文件没有做出选择");
                }
            }
            for (String path : conflicts) {
                applyResolution(projectId, path, choices.get(path),
                        mainTip, sessionTip, s.getTitle());
            }
            return completeSessionMerge(projectId, s, mainTip);
        } finally {
            lock.unlock();
        }
    }

    /** 中止收尾：合并窗口按路径还原，回到工作段分支继续工作，空闲定时器重新武装。 */
    public String abortSessionEnd(long projectId) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            WorkSession s = activeSession(projectId)
                    .orElseThrow(() -> VersionException.userFacing("当前没有进行中的工作"));
            repoService.abortMerge(projectId);
            if (!s.getBranchName().equals(repoService.currentBranch(projectId))) {
                repoService.checkoutBranch(projectId, s.getBranchName());
            }
            armIdleTimer(projectId, s.getId());
            log.info("中止一次工作收尾: project={}, session={}", projectId, s.getId());
            return "本次工作还没能收尾，你的改动都还在";
        } finally {
            lock.unlock();
        }
    }
```

`VersionController` 改动：

1. `/session/end`（既有端点）响应加 conflict：

```java
        WorkSessionService.SessionEndResult r = sessionService.endSession(...);
        Map<String, Object> data = new HashMap<>();
        data.put("sha", r.sha());
        if (r.conflict() != null) data.put("conflict", sessionEndConflictData(r.conflict()));
        return r.notice() != null ? okWithMessage(data, r.notice()) : ok(data);
```

2. 新端点 + 状态方法：

```java
    @PostMapping("/session/resolve-end")
    public ResponseEntity<Map<String, Object>> resolveSessionEnd(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        long targetSession = ((Number) body.get("sessionId")).longValue();
        @SuppressWarnings("unchecked")
        Map<String, String> raw = (Map<String, String>) body.get("resolutions");
        WorkSessionService.SessionEndResult r = sessionService.resolveSessionEnd(
                projectId, targetSession, parseResolutions(raw), userId, userName(userId));
        return ok(Map.of("sha", r.sha()));
    }

    @PostMapping("/session/abort-end")
    public ResponseEntity<Map<String, Object>> abortSessionEnd(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        String notice = sessionService.abortSessionEnd(projectId);
        return okWithMessage(Map.of(), notice);
    }

    /** MERGE_HEAD 指向活动工作段 tip 即「结束工作撞上新版本」窗口。优先于 adoptConflict。 */
    private Map<String, Object> sessionEndConflictStatus(long projectId) {
        try {
            if (!repoService.repositoryMerging(projectId)) return null;
            String mergeHead = repoService.mergeHeadRef(projectId);
            if (mergeHead == null) return null;
            var active = sessionService.activeSession(projectId);
            if (active.isEmpty() || !mergeHead.equals(
                    repoService.resolveRef(projectId, active.get().getBranchName()))) {
                return null;
            }
            return sessionEndConflictData(new WorkSessionService.SessionEndConflict(
                    active.get().getId(), active.get().getTitle(),
                    filterAwd(repoService.conflictingPaths(projectId)),
                    repoService.resolveRef(projectId, "HEAD"), mergeHead));
        } catch (Exception e) {
            return null;
        }
    }
```

3. `/status` 的 data 里：先算 `sessionEndConflict`，非 null 时 `adoptConflict` 强制 null（防止 MERGE_HEAD 反查稿列表失败后走 draftId=null 逃生门、前端弹错弹窗）。`sessionEndConflictData` 输出 `{sessionId, title, conflictingPaths, mainlineTip, sessionTip}`；`filterAwd` 沿用既有 `.awd/` 过滤谓词。

注意 `parseResolutions`（VersionController.java:354）签名是 `Map<String,Map<String,String>>` 还是 `Map<String,String>`——以实码为准适配（本端点 body 形状 `{sessionId, resolutions: {path: "MAIN"}}`）。

- [ ] **Step 4: 跑测试 + 全量回归 + 修编译连锁**

```bash
cd backend && grep -rn "SessionEndResult" src/main src/test --include="*.java" | grep -v WorkSessionService.java
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=SessionEndConflictTest
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
```
2 参构造 `new SessionEndResult(sha, notice)` 的既有调用点/断言补第三参 `null`。Expected: 新 3 用例绿；全量 0 fail（`WorkSessionServiceTest` 等对 endSession 的既有断言不许改行为——v1 路径分毫未动是本任务的验收项之一）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/checkba/version/ backend/src/test/java/com/checkba/version/SessionEndConflictTest.java backend/src/test/
git commit -m "feat(version): 服务端结束工作冲突化——主线被推进时走三选一，桌面路径零回归

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 8: CloudConnection/ProjectRemote 实体 + CloudSyncService 连接与上传 + 结束工作自动上传

**Files:**
- Create: `backend/src/main/java/com/checkba/model/entity/CloudConnection.java`、`ProjectRemote.java`
- Create: `backend/src/main/java/com/checkba/repository/CloudConnectionRepository.java`、`ProjectRemoteRepository.java`
- Create: `backend/src/main/java/com/checkba/version/CloudSyncService.java`（**注意在 com.checkba.version 包**，要用同包的 package-private 成员）
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`（repoLock/dockCurrentLine/resolveAffectedFileIds 可见性放宽 + MainlineMergedEvent 发布）
- Test: `backend/src/test/java/com/checkba/version/CloudSyncUploadTest.java`

**Interfaces:**
- Consumes: `pushMainlineToOrigin`/`fetchFromOrigin`/`setRemoteOrigin`/`originMasterSha`/`isAncestor`（Task 5）；Hutool `HttpRequest`（PluginMarketService.java:337 的 protected seam 范式）。
- Produces:
  - 实体 `CloudConnection{id, serverUrl, username, displayName, deviceToken, createdAt}`、`ProjectRemote{id, projectId(unique), connectionId, remoteProjectId, lastSyncSha, pendingUpload, createdAt}`（都照 DeviceToken 的 Lombok 风格）。
  - `CloudSyncService.connect(serverUrl, username, password, deviceName) -> CloudConnection`（打服务端 `/api/auth/device-token`）；`disconnect(connectionId)`（尽力撤远端令牌 + 删本地连接与关联）；`listConnections()`。
  - `public enum UploadStatus { UPLOADED, REMOTE_AHEAD, OFFLINE_PENDING, NOT_LINKED }`
  - `public record UploadResult(UploadStatus status, String message) {}`
  - `uploadToCloud(long projectId, boolean background) -> UploadResult`——推 master+里程碑；被拒置 pendingUpload 返回 REMOTE_AHEAD（Task 9 升级这个分支为自动合并）；网络异常置 pendingUpload，background=true 时吞掉只记日志（绝不阻断结束工作），false 时抛 userFacing。
  - `cloudStatus(long projectId) -> Map`（不联网）：`{linked, serverUrl, remoteProjectId, pendingUpload, remoteAhead}`，`remoteAhead = originMasterSha != null && !isAncestor(origin/master, master)`。
  - `WorkSessionService`：`repoLock`（:69）/`dockCurrentLine`（:1122）/`resolveAffectedFileIds`（:1169）从 private 放宽为 package-private（仅去掉 `private` 关键字，同包 CloudSyncService 复用，不动语义）；新增 `public record MainlineMergedEvent(long projectId) {}`，`endSession` v1 路径与 `closeMergedSession` 成功返回前发布之（构造器注入 `ApplicationEventPublisher`）；CloudSyncService 里 `@EventListener @Async("taskExecutor")` 消费并 `uploadToCloud(projectId, true)`。

**WorkSessionService 构造器加 `ApplicationEventPublisher` 后必须**：`grep -rn "new WorkSessionService\|new AgentOrchestrator" backend/src` 逐个补参——style A 测试传 `event -> {}`；EvalHarness 若受传染同步修（v1 地雷 #19，第四次了）。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/checkba/version/CloudSyncUploadTest.java`（style A 双仓：root 是桌面、`file://` 裸仓是云端；connect 的 HTTP 用匿名子类覆写 seam）：

```java
    // setUp 骨架同 DraftAdoptTest（repoSvc/manifestSvc/sessionRepo/fileRepo 全套 + 新增两个桩 repo）：
    // cloudConnRepo/projectRemoteRepo 用 HashMap 桩（save 分配 id、findByProjectId/findById 查表）。
    // svc = new WorkSessionService(..., publishedEvents::add)  // ApplicationEventPublisher 收集器
    // cloud = new CloudSyncService(repoSvc, svc, manifestSvc, fileRepo,
    //         cloudConnRepo, projectRemoteRepo) { 
    //     @Override protected String httpPost(String url, String body) { 
    //         lastHttpUrl = url; return cannedResponse; } };

    @Test
    void connectStoresTokenFromServerResponse() {
        cannedResponse = """
                {"code":0,"data":{"tokenId":1,"token":"awdt_abc","userId":5,
                "username":"hanzewei","displayName":"韩泽伟"}}
                """;
        CloudConnection conn = cloud.connect("http://server:9696", "hanzewei", "pw", "MacBook");
        assertEquals("awdt_abc", conn.getDeviceToken());
        assertTrue(lastHttpUrl.endsWith("/api/auth/device-token"));
    }

    @Test
    void uploadPushesMainlineAndClearsPending() throws Exception {
        linkToBareRemote(7L);                   // helper：建 CloudConnection+ProjectRemote 行 + setRemoteOrigin(file://)
        CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, false);
        assertEquals(CloudSyncService.UploadStatus.UPLOADED, r.status());
        assertFalse(remoteRowOf(7L).getPendingUpload());
        assertEquals(repoSvc.resolveRef(7L, "master"), remoteMasterShaOfBare());
    }

    @Test
    void rejectedUploadMarksPendingAndReportsRemoteAhead() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advanceBareRemoteFromPeer("同事的第二稿");   // helper 同 ProjectRepoRemoteTest
        Files.writeString(root.resolve("projects/7/合同.txt"), "我的第二稿");
        repoSvc.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, false);
        assertEquals(CloudSyncService.UploadStatus.REMOTE_AHEAD, r.status());
        assertTrue(remoteRowOf(7L).getPendingUpload());
    }

    @Test
    void offlineBackgroundUploadSwallowsAndMarksPending() {
        linkToUnreachableRemote(7L);            // setRemoteOrigin("http://127.0.0.1:1/x.git")
        assertDoesNotThrow(() -> {
            CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, true);
            assertEquals(CloudSyncService.UploadStatus.OFFLINE_PENDING, r.status());
        });
        assertTrue(remoteRowOf(7L).getPendingUpload());
    }

    @Test
    void endSessionPublishesMainlineMergedEvent() throws Exception {
        svc.enableVersionRecording(7L, "韩泽伟", "hzw@example.com");
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "改一笔");
        svc.commitNow(7L, 1L, "韩泽伟", null);
        svc.endSession(7L, 1L, "韩泽伟", "一段工作");
        assertTrue(publishedEvents.stream().anyMatch(e ->
                e instanceof WorkSessionService.MainlineMergedEvent m && m.projectId() == 7L));
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=CloudSyncUploadTest
```
Expected: 编译失败。

- [ ] **Step 3: 实现**

两实体 + 两 Repository 照 DeviceToken/DeviceTokenRepository 形制（字段见 Interfaces；`ProjectRemoteRepository` 带 `Optional<ProjectRemote> findByProjectId(Long)`、`List<ProjectRemote> findByConnectionId(Long)`；`CloudConnectionRepository` 裸 JpaRepository）。

`CloudSyncService.java` 核心（连接管理 + 上传 + 事件消费；Hutool 用法照 PluginMarketService.java:337-348，`cn.hutool.http.HttpRequest.post(url).body(json).setConnectionTimeout(5000).setReadTimeout(15000).execute()`）：

```java
package com.checkba.version;

// imports 略：Hutool HttpRequest/JSONUtil、Spring @Service/@EventListener/@Async、实体与 repo

/**
 * 云端同步业务语义（上传/更新/共享/接入）。只有这里认识 CloudConnection/ProjectRemote；
 * Git 细节在 ProjectRepoService，工作段语义在 WorkSessionService。
 * 与 WorkSessionService 同包：复用同一把 repoLock（包内可见），云端操作与本地
 * 提交路径互斥是硬要求。
 * 网络失败纪律：云端不可达只置状态（pendingUpload/黄灯），绝不阻断本地流程。
 */
@Service
public class CloudSyncService {

    private final ProjectRepoService repoService;
    private final WorkSessionService sessionService;
    private final ProjectTreeManifestService manifestService;
    private final ProjectFileRepository fileRepository;
    private final CloudConnectionRepository connectionRepository;
    private final ProjectRemoteRepository remoteRepository;

    // 构造器注入全部六个，略

    public enum UploadStatus { UPLOADED, REMOTE_AHEAD, OFFLINE_PENDING, NOT_LINKED }
    public record UploadResult(UploadStatus status, String message) {}

    public CloudConnection connect(String serverUrl, String username,
                                   String password, String deviceName) {
        String base = serverUrl.replaceAll("/+$", "");
        String body = cn.hutool.json.JSONUtil.toJsonStr(java.util.Map.of(
                "username", username, "password", password, "name", deviceName));
        cn.hutool.json.JSONObject resp =
                cn.hutool.json.JSONUtil.parseObj(httpPost(base + "/api/auth/device-token", body));
        if (resp.getInt("code", 1) != 0) {
            throw VersionException.userFacing("连接云端失败：" + resp.getStr("message", "账号或密码不对"));
        }
        cn.hutool.json.JSONObject data = resp.getJSONObject("data");
        CloudConnection conn = new CloudConnection();
        conn.setServerUrl(base);
        conn.setUsername(data.getStr("username"));
        conn.setDisplayName(data.getStr("displayName"));
        conn.setDeviceToken(data.getStr("token"));
        conn.setCreatedAt(java.time.LocalDateTime.now());
        return connectionRepository.save(conn);
    }

    public void disconnect(long connectionId) {
        connectionRepository.findById(connectionId).ifPresent(conn -> {
            try {
                // 尽力撤远端令牌，失败不阻断本地断开
                httpPost(conn.getServerUrl() + "/api/auth/device-token/0/revoke", "{}");
            } catch (Exception ignored) { }
            remoteRepository.findByConnectionId(connectionId)
                    .forEach(remoteRepository::delete);
            connectionRepository.delete(conn);
        });
    }

    public UploadResult uploadToCloud(long projectId, boolean background) {
        var lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            var remoteOpt = remoteRepository.findByProjectId(projectId);
            if (remoteOpt.isEmpty()) return new UploadResult(UploadStatus.NOT_LINKED, null);
            ProjectRemote remote = remoteOpt.get();
            CloudConnection conn = connectionRepository.findById(remote.getConnectionId())
                    .orElseThrow(() -> new VersionException("云端连接不存在: " + remote.getConnectionId()));
            if (repoService.repositoryMerging(projectId)) {
                return new UploadResult(UploadStatus.REMOTE_AHEAD, "请先处理正在进行的合并");
            }
            try {
                ProjectRepoService.PushOutcome out = repoService.pushMainlineToOrigin(
                        projectId, conn.getUsername(), conn.getDeviceToken());
                if (out.pushed()) {
                    remote.setPendingUpload(false);
                    remote.setLastSyncSha(repoService.resolveRef(projectId, repoService.mainBranch()));
                    remoteRepository.save(remote);
                    return new UploadResult(UploadStatus.UPLOADED, null);
                }
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
                return new UploadResult(UploadStatus.REMOTE_AHEAD, "云端有同事的新版本");
            } catch (VersionException e) {
                remote.setPendingUpload(true);
                remoteRepository.save(remote);
                if (background) {
                    org.slf4j.LoggerFactory.getLogger(getClass())
                            .warn("后台上传失败，转入待上传: project={}", projectId, e);
                    return new UploadResult(UploadStatus.OFFLINE_PENDING, null);
                }
                throw VersionException.userFacing("云端暂时连不上，改动已记为待上传");
            }
        } finally {
            lock.unlock();
        }
    }

    /** 不联网的云端状态快照（/status 与云端状态区吃它）。 */
    public java.util.Map<String, Object> cloudStatus(long projectId) {
        var remoteOpt = remoteRepository.findByProjectId(projectId);
        if (remoteOpt.isEmpty()) return java.util.Map.of("linked", false);
        ProjectRemote remote = remoteOpt.get();
        String serverUrl = connectionRepository.findById(remote.getConnectionId())
                .map(CloudConnection::getServerUrl).orElse(null);
        String origin = repoService.originMasterSha(projectId);
        boolean remoteAhead = origin != null
                && !repoService.isAncestor(projectId, origin, repoService.mainBranch());
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("linked", true);
        m.put("serverUrl", serverUrl);
        m.put("remoteProjectId", remote.getRemoteProjectId());
        m.put("pendingUpload", Boolean.TRUE.equals(remote.getPendingUpload()));
        m.put("remoteAhead", remoteAhead);
        return m;
    }

    /** 结束工作 → 后台自动上传（spec 决策 3）。 */
    @org.springframework.context.event.EventListener
    @org.springframework.scheduling.annotation.Async("taskExecutor")
    public void onMainlineMerged(WorkSessionService.MainlineMergedEvent event) {
        try {
            uploadToCloud(event.projectId(), true);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("自动上传异常（已吞）: project={}", event.projectId(), e);
        }
    }

    /** 单测覆写此 seam 打桩（PluginMarketService.httpGet 同款约定）。 */
    protected String httpPost(String url, String jsonBody) {
        try (cn.hutool.http.HttpResponse resp = cn.hutool.http.HttpRequest.post(url)
                .header("Content-Type", "application/json")
                .body(jsonBody)
                .setConnectionTimeout(5000)
                .setReadTimeout(15000)
                .execute()) {
            if (resp.getStatus() != 200) {
                throw new IllegalStateException("云端请求失败 (HTTP " + resp.getStatus() + ")");
            }
            return resp.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("云端不可达: " + e.getMessage(), e);
        }
    }

    protected String httpGet(String url, String token) {
        try (cn.hutool.http.HttpResponse resp = cn.hutool.http.HttpRequest.get(url)
                .header("X-Session-Id", token)
                .setConnectionTimeout(5000)
                .setReadTimeout(15000)
                .execute()) {
            if (resp.getStatus() != 200) {
                throw new IllegalStateException("云端请求失败 (HTTP " + resp.getStatus() + ")");
            }
            return resp.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("云端不可达: " + e.getMessage(), e);
        }
    }
}
```

`WorkSessionService` 三处可见性放宽（去 `private`）+ 事件：

```java
    public record MainlineMergedEvent(long projectId) {}
    // 构造器加 org.springframework.context.ApplicationEventPublisher eventPublisher 参数并存字段；
    // endSession v1 路径 return 前与 closeMergedSession return 前各加：
    try { eventPublisher.publishEvent(new MainlineMergedEvent(projectId)); }
    catch (Exception e) { log.warn("发布合并事件失败（不阻断）", e); }
```

- [ ] **Step 4: 修构造器连锁 + 跑测试 + 全量**

```bash
cd backend && grep -rn "new WorkSessionService\|new AgentOrchestrator" src --include="*.java"
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=CloudSyncUploadTest
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
```
Expected: 新 5 用例绿；全量 0 fail。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/checkba/model/entity/ backend/src/main/java/com/checkba/repository/ backend/src/main/java/com/checkba/version/ backend/src/test/
git commit -m "feat(version): 云端连接与上传——设备令牌换取/推主线/待上传/结束工作自动上传

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 9: 从云端更新——快进/真合并/云端冲突窗口 + 上传被拒自动合并重推

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/CloudSyncService.java`
- Modify: `backend/src/main/java/com/checkba/version/ProjectRepoService.java`（fastForwardMainline）
- Modify: `backend/src/main/java/com/checkba/version/VersionController.java`（cloudConflict 状态）
- Test: `backend/src/test/java/com/checkba/version/CloudSyncUpdateTest.java`

**Interfaces:**
- Consumes: `mergeNoCommit(projectId, "refs/remotes/origin/master", ...)`（mergeCore 用 `repo.resolve(branchName)`，远端追踪 ref 可解析）；`applyResolution`（同包）；`dockCurrentLine`/`resolveAffectedFileIds`（Task 8 放宽的包内成员）；`commitMergeResolution`。
- Produces:
  - `ProjectRepoService.fastForwardMainline(long projectId, String targetRef)`——`git.merge().include(repo.resolve(targetRef)).setFastForward(FastForwardMode.FF_ONLY).call()`，非 FF 或工作区冲突抛技术档异常（调用方已 dock 保证干净）。
  - `public enum UpdateStatus { UP_TO_DATE, UPDATED, CONFLICT, OFFLINE, NOT_LINKED }`
  - `public record UpdateResult(UpdateStatus status, List<Long> affectedFileIds, Map<String, Object> conflict) {}`
  - `updateFromCloud(long projectId, Long userId, String userName) -> UpdateResult`——前置守卫（userFacing）：无 MERGING、无 ACTIVE 工作段（「请先结束或丢弃当前工作，再从云端更新」）、不在稿上（「请先回到主线工作」）；流程：dockCurrentLine → fetch → 快进或真合并；真合并干净 = unionApply(云端清单) + capture + commitMergeResolution(「云端更新」) + 自动重推；冲突 = 停 MERGING 返回 conflict payload。
  - `resolveCloudMerge(long projectId, Map<String, Resolution>, Long userId, String userName) -> UpdateResult`、`abortCloudMerge(long projectId) -> String notice`。
  - `uploadToCloud` 的 REMOTE_AHEAD 分支升级：守卫允许时（无段/主线上/无 MERGING）自动走同一条 integrate 流程，干净则合并后重推返回 UPLOADED，冲突返回新增 `UploadStatus.CONFLICT`。
  - `VersionController.cloudConflictStatus(long)`：MERGING 且 `mergeHeadRef == originMasterSha` → `{conflictingPaths, mainlineTip, cloudTip}`；`/status` 判定链变为 **sessionEndConflict → cloudConflict → adoptConflict**（先到先得，后位强制 null）。

**方向钉死**：云端更新的合并是「origin/master 并入本地 master」→ ours=本地=**我这边的**、theirs=云端=**云端的**。`Resolution.MAIN`=用我这边的、`DRAFT`=用云端的、`BOTH`=两份都留（副本来自云端侧，`applyResolution` 的 label 传「云端」→ 副本名「（来自：云端）」）。与 Task 7 的方向**相反**（那边 MAIN=同事的）——前端标签按语境映射，Task 13 落实。

**语义护栏**：FF 路径清单用 `applyToDatabase` 全量同步（目标状态即真相，v1 切线/退回同口径）；真合并路径用 `unionApply` + capture（两条已分叉线的合并，adopt 同口径，地雷 #21 的清单同提交纪律照抄）。affectedFileIds 口径同 revertTo：变更在动作前算（`diffNameStatus(newHead, tipBefore)` 反向），走 `resolveAffectedFileIds`。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/checkba/version/CloudSyncUpdateTest.java`（骨架同 CloudSyncUploadTest；「同事」用 peer 克隆推 file:// 裸远端；peer 侧手工维护 v2 tree.json——JSON 手作法照 Task 6 测试）：

```java
    @Test
    void fastForwardUpdateMaterialisesFilesAndDatabase() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advancePeerWithNewFile("同事新增.txt", "内容");   // peer：加文件 + tree.json 添 v2 节点 + push
        CloudSyncService.UpdateResult r = cloud.updateFromCloud(7L, 1L, "韩泽伟");
        assertEquals(CloudSyncService.UpdateStatus.UPDATED, r.status());
        assertEquals("内容", Files.readString(root.resolve("projects/7/同事新增.txt")));
        assertTrue(dbRowsOf(7L).stream().anyMatch(f -> "同事新增.txt".equals(f.getName())));
        assertFalse(r.affectedFileIds().isEmpty());
    }

    @Test
    void divergedCleanUpdateMergesAndPushesBack() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advancePeerWithNewFile("同事的.txt", "同事内容");
        Files.writeString(root.resolve("projects/7/我的.txt"), "我的内容");
        repoSvc.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        CloudSyncService.UpdateResult r = cloud.updateFromCloud(7L, 1L, "韩泽伟");
        assertEquals(CloudSyncService.UpdateStatus.UPDATED, r.status());
        // 双亲合并 + 两侧都在 + 已自动重推（远端 tip == 本地 tip）
        assertEquals(2, repoSvc.log(7L, "master", 5).get(0).parents().size());
        assertEquals("同事内容", Files.readString(root.resolve("projects/7/同事的.txt")));
        assertEquals(repoSvc.resolveRef(7L, "master"), remoteMasterShaOfBare());
    }

    @Test
    void conflictingUpdateOpensWindowAndBothResolutionKeepsCloudCopy() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advancePeerRewriting("合同.txt", "云端的第二稿");
        Files.writeString(root.resolve("projects/7/合同.txt"), "我的第二稿");
        repoSvc.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        CloudSyncService.UpdateResult r = cloud.updateFromCloud(7L, 1L, "韩泽伟");
        assertEquals(CloudSyncService.UpdateStatus.CONFLICT, r.status());
        assertTrue(repoSvc.repositoryMerging(7L));

        CloudSyncService.UpdateResult done = cloud.resolveCloudMerge(7L,
                Map.of("合同.txt", WorkSessionService.Resolution.BOTH), 1L, "韩泽伟");
        assertEquals(CloudSyncService.UpdateStatus.UPDATED, done.status());
        assertFalse(repoSvc.repositoryMerging(7L));
        assertEquals("我的第二稿", Files.readString(root.resolve("projects/7/合同.txt"))); // MAIN=ours 缺省内容
        assertTrue(Files.list(root.resolve("projects/7")).map(p -> p.getFileName().toString())
                .anyMatch(n -> n.contains("来自：云端")));
        assertEquals(repoSvc.resolveRef(7L, "master"), remoteMasterShaOfBare()); // 裁决后重推
    }

    @Test
    void abortCloudMergeLeavesBothSidesIntact() throws Exception {
        // 同上制造冲突后：
        String before = Files.readString(root.resolve("projects/7/合同.txt"));
        String notice = cloud.abortCloudMerge(7L);
        assertNotNull(notice);
        assertFalse(repoSvc.repositoryMerging(7L));
        assertEquals(before.isEmpty() ? "我的第二稿" : "我的第二稿",
                Files.readString(root.resolve("projects/7/合同.txt")));
    }

    @Test
    void updateIsRefusedDuringActiveSession() throws Exception {
        linkToBareRemote(7L);
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        svc.commitNow(7L, 1L, "韩泽伟", null);
        VersionException e = assertThrows(VersionException.class,
                () -> cloud.updateFromCloud(7L, 1L, "韩泽伟"));
        assertTrue(e.isUserFacing());
    }

    @Test
    void rejectedUploadNowAutoIntegratesWhenClean() throws Exception {
        linkToBareRemote(7L);
        cloud.uploadToCloud(7L, false);
        advancePeerWithNewFile("同事的.txt", "同事内容");
        Files.writeString(root.resolve("projects/7/我的.txt"), "我的内容");
        repoSvc.commitAll(7L, "我的修改", "auto", null, "韩泽伟", "hzw@example.com");
        CloudSyncService.UploadResult r = cloud.uploadToCloud(7L, false);
        assertEquals(CloudSyncService.UploadStatus.UPLOADED, r.status()); // 合并后重推成功
        assertEquals(repoSvc.resolveRef(7L, "master"), remoteMasterShaOfBare());
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=CloudSyncUpdateTest
```
Expected: 编译失败（新方法不存在）。

- [ ] **Step 3: 实现**

`ProjectRepoService.fastForwardMainline`（远端原语区）：

```java
    /** 快进 master 到 targetRef（含工作区）。非快进/脏冲突抛技术档异常——调用方先 dock。 */
    public void fastForwardMainline(long projectId, String targetRef) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            var target = repo.resolve(targetRef);
            if (target == null) throw new VersionException("目标版本不存在: " + targetRef);
            var result = git.merge()
                    .include(target)
                    .setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.FF_ONLY)
                    .call();
            if (!result.getMergeStatus().isSuccessful()) {
                throw new VersionException("快进失败: " + result.getMergeStatus());
            }
        } catch (VersionException e) {
            throw e;
        } catch (Exception e) {
            throw new VersionException("快进主线失败: project=" + projectId, e);
        }
    }
```

`CloudSyncService` 新增（核心 integrate 私有方法 + 三个公有）：

```java
    public enum UpdateStatus { UP_TO_DATE, UPDATED, CONFLICT, OFFLINE, NOT_LINKED }
    public record UpdateResult(UpdateStatus status, java.util.List<Long> affectedFileIds,
                               java.util.Map<String, Object> conflict) {}

    private static final String ORIGIN_MASTER = "refs/remotes/origin/master";
    private static final String CLOUD_MERGE_TITLE = "云端更新";
    private static final String CLOUD_SIDE_LABEL = "云端";

    public UpdateResult updateFromCloud(long projectId, Long userId, String userName) {
        var lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            var remoteOpt = remoteRepository.findByProjectId(projectId);
            if (remoteOpt.isEmpty()) return new UpdateResult(UpdateStatus.NOT_LINKED, java.util.List.of(), null);
            requireCleanForCloudOps(projectId);
            CloudConnection conn = connectionOf(remoteOpt.get());
            sessionService.dockCurrentLine(projectId, userId, userName);
            String remoteSha;
            try {
                remoteSha = repoService.fetchFromOrigin(projectId, conn.getUsername(), conn.getDeviceToken());
            } catch (VersionException e) {
                return new UpdateResult(UpdateStatus.OFFLINE, java.util.List.of(), null);
            }
            if (remoteSha == null
                    || repoService.isAncestor(projectId, remoteSha, repoService.mainBranch())) {
                return new UpdateResult(UpdateStatus.UP_TO_DATE, java.util.List.of(), null);
            }
            String tipBefore = repoService.resolveRef(projectId, repoService.mainBranch());
            if (repoService.isAncestor(projectId, repoService.mainBranch(), ORIGIN_MASTER)) {
                // 快进：目标状态即真相 → applyToDatabase 全量同步（切线/退回同口径）
                repoService.fastForwardMainline(projectId, ORIGIN_MASTER);
                var manifest = manifestService.readAtRef(projectId, "HEAD");
                if (manifest != null) manifestService.applyToDatabase(projectId, manifest);
                return new UpdateResult(UpdateStatus.UPDATED,
                        affectedSince(projectId, tipBefore), null);
            }
            // 真合并
            MergeOutcome outcome = repoService.mergeNoCommit(projectId, ORIGIN_MASTER,
                    CLOUD_MERGE_TITLE, userName, "user-" + userId + "@aiworkdeck.local");
            if (outcome.mergeSha() != null) {
                return new UpdateResult(UpdateStatus.UP_TO_DATE, java.util.List.of(), null);
            }
            if (!outcome.success()) {
                return new UpdateResult(UpdateStatus.CONFLICT, java.util.List.of(),
                        cloudConflictPayload(projectId));
            }
            return completeCloudMerge(projectId, tipBefore, remoteSha, conn, userId, userName);
        } finally {
            lock.unlock();
        }
    }

    public UpdateResult resolveCloudMerge(long projectId,
                                          java.util.Map<String, WorkSessionService.Resolution> resolutions,
                                          Long userId, String userName) {
        var lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            if (!repoService.repositoryMerging(projectId)) {
                throw VersionException.userFacing("现在没有等着做选择的更新");
            }
            String cloudTip = repoService.mergeHeadRef(projectId);
            if (cloudTip == null || !cloudTip.equals(repoService.originMasterSha(projectId))) {
                throw VersionException.userFacing("正在处理的是另一件事，请先把它处理完");
            }
            var rawConflicts = repoService.conflictingPaths(projectId);
            if (rawConflicts.isEmpty()) {
                throw new VersionException("冲突记录已丢失，无法安全完成更新: project=" + projectId);
            }
            String mainTip = repoService.resolveRef(projectId, "HEAD");
            var conflicts = sessionService.userVisibleConflictsShared(rawConflicts);
            var choices = resolutions == null ? java.util.Map.<String, WorkSessionService.Resolution>of() : resolutions;
            for (String path : conflicts) {
                if (choices.get(path) == null) throw VersionException.userFacing("还有文件没有做出选择");
            }
            for (String path : conflicts) {
                sessionService.applyResolutionShared(projectId, path, choices.get(path),
                        mainTip, cloudTip, CLOUD_SIDE_LABEL);
            }
            var remote = remoteRepository.findByProjectId(projectId).orElseThrow();
            return completeCloudMerge(projectId, mainTip, cloudTip,
                    connectionOf(remote), userId, userName);
        } finally {
            lock.unlock();
        }
    }

    public String abortCloudMerge(long projectId) {
        var lock = sessionService.repoLock(projectId);
        lock.lock();
        try {
            repoService.abortMerge(projectId);
            return "这次更新没有完成，你的内容分毫未动";
        } finally {
            lock.unlock();
        }
    }

    /** 干净或裁决后的云端合并统一收尾（清单同提交，地雷 #21）+ 自动重推。 */
    private UpdateResult completeCloudMerge(long projectId, String tipBefore, String cloudTip,
                                            CloudConnection conn, Long userId, String userName) {
        var cloudManifest = manifestService.readAtRef(projectId, cloudTip);
        if (cloudManifest != null) manifestService.unionApply(projectId, cloudManifest);
        manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
        repoService.commitMergeResolution(projectId, CLOUD_MERGE_TITLE,
                userName, "user-" + userId + "@aiworkdeck.local");
        try {
            repoService.pushMainlineToOrigin(projectId, conn.getUsername(), conn.getDeviceToken());
            var remote = remoteRepository.findByProjectId(projectId).orElse(null);
            if (remote != null) {
                remote.setPendingUpload(false);
                remote.setLastSyncSha(repoService.resolveRef(projectId, repoService.mainBranch()));
                remoteRepository.save(remote);
            }
        } catch (Exception e) {
            // 合并已落地，只是回传没成——转入待上传，绝不回滚
            remoteRepository.findByProjectId(projectId).ifPresent(r -> {
                r.setPendingUpload(true);
                remoteRepository.save(r);
            });
        }
        return new UpdateResult(UpdateStatus.UPDATED, affectedSince(projectId, tipBefore), null);
    }

    private java.util.List<Long> affectedSince(long projectId, String tipBefore) {
        try {
            var changes = repoService.diffNameStatus(projectId, "HEAD", tipBefore);
            return sessionService.resolveAffectedFileIds(projectId, changes);
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private void requireCleanForCloudOps(long projectId) {
        if (repoService.repositoryMerging(projectId)) {
            throw VersionException.userFacing("请先处理正在进行的合并");
        }
        if (sessionService.activeSession(projectId).isPresent()) {
            throw VersionException.userFacing("请先结束或丢弃当前工作，再从云端更新");
        }
        if (sessionService.onDraftBranchShared(projectId)) {
            throw VersionException.userFacing("请先回到主线工作，再从云端更新");
        }
    }

    private java.util.Map<String, Object> cloudConflictPayload(long projectId) {
        return java.util.Map.of(
                "conflictingPaths", sessionService.userVisibleConflictsShared(
                        repoService.conflictingPaths(projectId)),
                "mainlineTip", repoService.resolveRef(projectId, "HEAD"),
                "cloudTip", repoService.mergeHeadRef(projectId));
    }

    private CloudConnection connectionOf(ProjectRemote remote) {
        return connectionRepository.findById(remote.getConnectionId())
                .orElseThrow(() -> new VersionException("云端连接不存在: " + remote.getConnectionId()));
    }
```

（`userVisibleConflictsShared`/`applyResolutionShared`/`onDraftBranchShared` = WorkSessionService 里对应私有方法去 `private`（包内共享），名字**不改**——上面后缀只是计划里的占位提醒，实码直接用原名 `userVisibleConflicts`/`applyResolution`/`onDraftBranch`。）

`uploadToCloud` 的 REMOTE_AHEAD 分支升级：被拒后若 `activeSession` 空、不在稿上、无 MERGING → 直接调 `updateFromCloud(projectId, userId?, userName?)` 的 integrate 内核（抽私有 `integrateAndPush` 或直接调 `updateFromCloud`——upload 调用方带不带 userId 视 CloudController 传参，background 事件路径用项目 owner 兜底）后按结果映射 UPLOADED/CONFLICT；仍被挡则维持 REMOTE_AHEAD + message「云端有同事的新版本，结束当前工作后再上传」。`UploadStatus` 加 `CONFLICT`。

`VersionController` `/status`：`sessionEndConflict` 之后插 `cloudConflict`（`repositoryMerging && mergeHeadRef.equals(repoService.originMasterSha(projectId))` → payload 同 `cloudConflictPayload` 形状），命中时 `adoptConflict` 强制 null；`adoptConflictStatus` 反查前先排除前两种。

- [ ] **Step 4: 跑测试 + 全量**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest='CloudSyncUpdateTest,CloudSyncUploadTest,SessionEndConflictTest,DraftAdoptTest'
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
```
Expected: 全绿。DraftAdoptTest 必须原样绿（applyResolution 等只放宽可见性没改语义）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/checkba/version/ backend/src/test/java/com/checkba/version/CloudSyncUpdateTest.java
git commit -m "feat(version): 从云端更新——快进/真合并/冲突三选一/裁决重推，上传被拒自动合并

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 10: 共享上云 + 从云端接项目 + prepare-remote + 首推物化

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/CloudSyncService.java`（shareToCloud/cloneFromCloud/listRemoteProjects）
- Modify: `backend/src/main/java/com/checkba/version/ProjectRepoService.java`（initEmptyForReceive/cloneFromRemote/listPaths）
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`（ingest 支持首推 zeroId）
- Modify: `backend/src/main/java/com/checkba/version/VersionController.java`（POST /prepare-remote）
- Test: `backend/src/test/java/com/checkba/version/cloud/ShareCloneRoundTripTest.java`

**Interfaces:**
- Produces:
  - `ProjectRepoService.initEmptyForReceive(long projectId)`——只建仓（`repo.create()` + HEAD 指 master），**不落初始提交**：共享方的首推要带完整历史进来，服务端先落提交会造出两条无关历史（unrelated histories），永远合不上。幂等：已初始化则 no-op。
  - `ProjectRepoService.cloneFromRemote(long projectId, String url, String username, String token)`——`Git.cloneRepository().setURI(url).setGitDir(gitDir).setDirectory(workTree).setBranch("master")`，gitDir/workTree 分离布局与 init 一致。
  - `ProjectRepoService.listPaths(long projectId, String ref) -> List<String>`——TreeWalk 递归列出该版全部路径（首推物化用）。
  - `WorkSessionService.ingestPushedMainline`：`oldSha` 为全零（`ObjectId.zeroId().name()`，分支新建）时改用 `listPaths(newSha)` 构造全量 ADD 变更物化。
  - `CloudSyncService.shareToCloud(long projectId, long connectionId, Long userId) -> Map`——守卫：未关联、本地已开版本记录（否则 userFacing「请先开启版本记录，再共享到云端」）、无 MERGING；流程：POST 服务端 `/api/projects` 建项目（`{projectType:"BLANK", name:本地项目名}`）→ POST `/api/projects/{rid}/version/prepare-remote` → `setRemoteOrigin(serverUrl + "/git/{rid}.git")` → 首推 → 存 ProjectRemote。
  - `CloudSyncService.cloneFromCloud(long connectionId, long remoteProjectId, Long localUserId) -> Map{localProjectId}`——POST 服务端 prepare-remote（对旧项目顺带升级清单，见下）→ 本地建 Project 行（名字取服务端项目名，`projectType="BLANK"`）→ cloneFromRemote → 读 HEAD 清单：`version < 2` 抛 userFacing「云端项目还是旧版本格式，请在云端更新一次后再接入」→ `applyToDatabase` 落库 → 存 ProjectRemote。
  - `CloudSyncService.listRemoteProjects(long connectionId) -> List<Map>`——GET 服务端 `/api/projects/my`（X-Session-Id: 令牌）透传 `{id, name, projectType}`。
  - `VersionController` 新端点 `POST /prepare-remote`：requireMember + `hasWritePermission`；未初始化 → `initEmptyForReceive`；已初始化且 HEAD 清单 version<2 → `commitNow`（capture 已是 v2，任一提交即升级）落一笔「升级版本记录格式」。
- Consumes: `ProjectController POST /api/projects`（body ProjectCreateRequest，`projectType` 必填、`"BLANK"` 免公司名校验，返回**裸 Project 实体**——`JSONUtil.parseObj(body).getLong("id")` 取 id）；`GET /api/projects/my` 返回裸 `List<ProjectCardDTO>`。

- [ ] **Step 1: 写失败的集成测试**

`backend/src/test/java/com/checkba/version/cloud/ShareCloneRoundTripTest.java`——**Spring 上下文当云端服务器（root1），手工 new 的整套 style A 栈当桌面端（root2）**，Hutool seam 不打桩（真打嵌入式服务器的 HTTP）：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("desktop")
class ShareCloneRoundTripTest {
    // @DynamicPropertySource root1（服务器 storage）同 GitHttpProtocolTest
    // @LocalServerPort port; @Autowired 服务器侧 UserService/ProjectFileRepository/WorkSessionService 等
    // desktop：root2 + 手工 new ProjectRepoService/ProjectTreeManifestService/WorkSessionService/CloudSyncService
    //（桩 repo HashMap 形制照 CloudSyncUploadTest；CloudSyncService 不覆写 seam——走真 HTTP）

    @Test
    void shareThenCloneRoundTrip(@TempDir Path desktopB) throws Exception {
        // 服务器：注册 userA（真实 UserService.register）
        // 桌面：本地项目 7 已 enableVersionRecording + 一份文件
        CloudConnection conn = desktopCloud.connect(
                "http://localhost:" + port, "userA", "pw", "测试机");
        Map<String, Object> shared = desktopCloud.shareToCloud(7L, conn.getId(), 1L);
        long rid = ((Number) shared.get("remoteProjectId")).longValue();

        // 服务器物化了：工作区文件 + DB 行都在（首推 zeroId 路径）
        assertEquals("初稿", Files.readString(root1.resolve("projects/" + rid + "/合同.txt")));
        assertTrue(serverFileRepository.findByProjectId(rid).stream()
                .anyMatch(f -> "合同.txt".equals(f.getName())));

        // 第二台桌面（root3 手工栈）接入：文件与 DB 都长出来，且 uid 与共享方一致
        CloudSyncService cloudB = desktopStackOn(desktopB);
        Map<String, Object> accepted = cloudB.cloneFromCloud(connB.getId(), rid, 9L);
        long localId = ((Number) accepted.get("localProjectId")).longValue();
        assertEquals("初稿", Files.readString(desktopB.resolve(
                "projects/" + localId + "/合同.txt")));
        String uidOnA = uidOf(dbA, 7L, "合同.txt");
        String uidOnB = uidOf(dbB, localId, "合同.txt");
        assertEquals(uidOnA, uidOnB);
    }

    @Test
    void prepareRemoteUpgradesV1ManifestProject() throws Exception {
        // 服务器上造一个"v1 时代"项目：enable 后把 HEAD 清单硬改回 version:1 再提交
        //（写 .awd/tree.json 为 v1 JSON + commitAll），然后打 POST /prepare-remote，
        // 断言 HEAD 清单 version==2 且时间线多了一笔「升级版本记录格式」。
    }
```

（`desktopStackOn(Path)` helper 把 style A 全套在指定 root 上再造一份；`uidOf` 从各自桩 DB 查。测试重点是**跨机器 uid 一致**——清单 v2 的立身之本。）

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ShareCloneRoundTripTest
```
Expected: 编译失败。

- [ ] **Step 3: 实现**

`ProjectRepoService` 三方法（远端原语区）：

```java
    /** 只建仓不落提交：等着接收共享方的首推。已初始化 no-op（幂等）。 */
    public void initEmptyForReceive(long projectId) {
        if (isInitialized(projectId)) return;
        try {
            Files.createDirectories(workTree(projectId));
            Repository repo = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                    .setGitDir(gitDir(projectId).toFile())
                    .setWorkTree(workTree(projectId).toFile())
                    .build();
            repo.create();
            org.eclipse.jgit.lib.RefUpdate head = repo.updateRef("HEAD");
            head.link("refs/heads/master");
            repo.close();
        } catch (Exception e) {
            throw new VersionException("初始化云端仓库失败: project=" + projectId, e);
        }
    }

    /** 从云端整仓克隆（gitDir/workTree 分离布局与 init 一致）。 */
    public void cloneFromRemote(long projectId, String url, String username, String token) {
        try {
            Files.createDirectories(workTree(projectId));
            Git.cloneRepository()
                    .setURI(url)
                    .setGitDir(gitDir(projectId).toFile())
                    .setDirectory(workTree(projectId).toFile())
                    .setBranch("master")
                    .setCredentialsProvider(
                            new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                                    username == null ? "" : username, token == null ? "" : token))
                    .setTimeout(120)
                    .call()
                    .close();
        } catch (Exception e) {
            throw new VersionException("从云端接入项目失败: project=" + projectId, e);
        }
    }

    /** 该版全部文件路径（首推物化用）。 */
    public List<String> listPaths(long projectId, String ref) {
        try (Repository repo = open(projectId);
             org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
            var commit = walk.parseCommit(repo.resolve(ref));
            List<String> out = new ArrayList<>();
            try (org.eclipse.jgit.treewalk.TreeWalk tw = new org.eclipse.jgit.treewalk.TreeWalk(repo)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) out.add(tw.getPathString());
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取版本文件列表失败: project=" + projectId, e);
        }
    }
```

`ingestPushedMainline` 的 diff 段改为：

```java
            List<FileChange> changes;
            if (org.eclipse.jgit.lib.ObjectId.zeroId().name().equals(oldSha)) {
                changes = repoService.listPaths(projectId, newSha).stream()
                        .map(p -> new FileChange(p, FileChange.Type.ADD)).toList();
            } else {
                changes = repoService.diffNameStatus(projectId, newSha, oldSha);
            }
```

`CloudSyncService` 三方法（Hutool 走既有 `httpPost`/`httpGet` seam；`shareToCloud` 里建项目请求体 `{"projectType":"BLANK","name":...}`，`prepare-remote` 打 `POST {server}/api/projects/{rid}/version/prepare-remote`，git URL 拼 `conn.getServerUrl() + "/git/" + rid + ".git"`；`cloneFromCloud` 里本地 Project 行 `new Project()` + setName/setProjectType("BLANK")/setUserId(localUserId)/setCreatedAt → `projectRepository.save`——CloudSyncService 构造器补 `ProjectRepository`）。`cloneFromCloud` 清单校验：

```java
            TreeManifest manifest = manifestService.readAtRef(localProjectId, "HEAD");
            if (manifest == null || manifest.version() < 2) {
                throw VersionException.userFacing("云端项目还是旧版本格式，请在云端更新一次后再接入");
            }
            manifestService.applyToDatabase(localProjectId, manifest);
```

`VersionController` prepare-remote：

```java
    /** 让本项目可作为云端仓库：未初始化则建空仓等首推；旧清单（v1）则落一笔升级提交。 */
    @PostMapping("/prepare-remote")
    public ResponseEntity<Map<String, Object>> prepareRemote(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        if (!projectMemberService.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException("无权共享该项目");
        }
        if (!repoService.isInitialized(projectId)) {
            repoService.initEmptyForReceive(projectId);
            return ok(Map.of("prepared", true, "fresh", true));
        }
        TreeManifest head = manifestServiceReadHeadSafely(projectId);
        if (head != null && head.version() < 2) {
            sessionService.commitNow(projectId, userId, userName(userId), "升级版本记录格式");
        }
        return ok(Map.of("prepared", true, "fresh", false));
    }
```

（`manifestServiceReadHeadSafely` = try/catch 包 `manifestService.readAtRef(projectId, "HEAD")`，异常回 null；VersionController 构造器补 `ProjectTreeManifestService`。）

- [ ] **Step 4: 跑测试 + 全量**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest='ShareCloneRoundTripTest,GitHttpIngestTest'
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
```
Expected: 全绿；GitHttpIngestTest 不回归（ingest 改动向后兼容）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/checkba/ backend/src/test/
git commit -m "feat(version): 共享上云与接入克隆——空仓首推/整仓克隆/旧清单升级/跨机器 uid 一致

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 11: CloudController REST 面

**Files:**
- Create: `backend/src/main/java/com/checkba/controller/CloudController.java`
- Test: `backend/src/test/java/com/checkba/controller/CloudControllerTest.java`

**Interfaces:**
- Produces（全部 HTTP 200 + `{code, data|message}` 封装，异常处理照 VersionController.onVersionError 抄一份 `@ExceptionHandler(VersionException.class)`；鉴权：连接级端点只要登录，项目级端点 requireMember 同款三连——**含拒 CLIENT**）：
  - `POST /api/cloud/connect` `{serverUrl, username, password, deviceName}` → `{connectionId, username, displayName, serverUrl}`
  - `GET /api/cloud/connections` → `{connections: [{id, serverUrl, username, displayName}]}`——**绝不带 deviceToken**
  - `POST /api/cloud/connections/{id}/disconnect` → `{}`
  - `GET /api/cloud/connections/{id}/remote-projects` → `{projects: [...]}`
  - `POST /api/cloud/projects/{projectId}/share` `{connectionId}` → `{remoteProjectId}`
  - `POST /api/cloud/accept` `{connectionId, remoteProjectId}` → `{localProjectId}`
  - `GET /api/cloud/projects/{projectId}/status` → cloudStatus()（不联网）
  - `POST /api/cloud/projects/{projectId}/check` → fetch 后 cloudStatus()（失败回 `{linked:..., offline:true}` 不抛）
  - `POST /api/cloud/projects/{projectId}/upload` → UploadResult 平铺 `{status, message}`
  - `POST /api/cloud/projects/{projectId}/update` → UpdateResult 平铺 `{status, affectedFileIds, conflict}`
  - `POST /api/cloud/projects/{projectId}/resolve` `{resolutions: {path: MAIN|DRAFT|BOTH}}` → 同 update
  - `POST /api/cloud/projects/{projectId}/abort` → `{}` + notice message
  - `GET /api/cloud/projects/{projectId}/members` → 透传服务端 `GET /api/projects/{rid}/members`（spec 第六节「成员桌面代理」）
  - `POST /api/cloud/projects/{projectId}/members` `{username, role}` → 透传服务端加成员端点（role 缺省 `PARTICIPANT`）
  - 两个透传走 `CloudSyncService.proxyMembers` 新方法（httpGet/httpPost seam + 该项目 ProjectRemote 换算 rid 与令牌；未关联抛 userFacing「先共享到云端」）
- Consumes: CloudSyncService 全部公有方法；`AuthController.getUserIdFromSession`；`ProjectMemberService`。

- [ ] **Step 1: 写失败的控制器单测**（style C：`@ExtendWith(MockitoExtension.class)` + `@Mock` CloudSyncService/ProjectMemberService + `MockedStatic<AuthController>`，形制照 VersionControllerAuthTest.java:30-43）——用例：未登录 401 语义（code:1「未登录」）；CLIENT 打项目级端点被拒；connect 成功透传；connections 列表**不含 token 字段**（对响应 JSON 断言 `!contains("deviceToken")`——防泄漏是这条测试存在的意义）；upload/update 转发参数正确。

- [ ] **Step 2: 跑红** `mvn -q test -Dtest=CloudControllerTest` → 编译失败。

- [ ] **Step 3: 实现** `CloudController`——纯转发层，无业务逻辑；`requireMemberNonClient(projectId, sessionId)` 私有方法照 VersionController.requireMember:394 抄（注意参数序地雷）。`update`/`resolve` 需要 userName：照 VersionController.userName(userId) 的 UserService 查法（构造器注入 UserService）。

- [ ] **Step 4: 跑绿 + 全量** `mvn -q test -Dtest=CloudControllerTest` → 全绿；`mvn -q test` → 0 fail。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/checkba/controller/CloudController.java backend/src/test/java/com/checkba/controller/CloudControllerTest.java
git commit -m "feat(cloud): 云端协作 REST 面——连接/共享/接入/上传/更新/冲突裁决

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 12: 前端——api.js cloud 系列 + 设置页「云端协作」分区

**Files:**
- Modify: `frontend/src/services/api.js`（版本 API 区块 :1573 之后追加）
- Modify: `frontend/src/pages/admin/admin.vue`（navItems + 新分区）

**Interfaces:**
- Consumes: `request(options)`（api.js:143，uni.request 包装，`X-Session-Id` 自动带，`code===0` resolve 整个 `res.data`——调用方读 `res.data.xxx`）；admin.vue 三触点模式（navItems :580-586 / scroll-view 分区 / `.section-card` 结构，Bocha 例 :228-240）。
- Produces: 14 个具名导出（Task 13 消费）；设置页新分区 key=`cloud`，`desktopOnly: true`。

- [ ] **Step 1: api.js 追加**（照 `getVersionStatus` :1575 与 `resolveAdopt` :1710 模板；术语注释随行）：

```javascript
// ==================== 云端协作（v2）====================
// 术语：push=上传到云端 pull=从云端更新 clone=从云端接一个项目。界面零 Git 术语。

export function cloudConnect(serverUrl, username, password, deviceName) {
  return request({ url: '/api/cloud/connect', method: 'POST',
    data: { serverUrl, username, password, deviceName } })
}

export function listCloudConnections() {
  return request({ url: '/api/cloud/connections', method: 'GET' })
}

export function disconnectCloudConnection(connectionId) {
  return request({ url: `/api/cloud/connections/${connectionId}/disconnect`, method: 'POST' })
}

export function listRemoteProjects(connectionId) {
  return request({ url: `/api/cloud/connections/${connectionId}/remote-projects`, method: 'GET' })
}

export function shareProjectToCloud(projectId, connectionId) {
  return request({ url: `/api/cloud/projects/${projectId}/share`, method: 'POST',
    data: { connectionId } })
}

export function acceptCloudProject(connectionId, remoteProjectId) {
  return request({ url: '/api/cloud/accept', method: 'POST',
    data: { connectionId, remoteProjectId } })
}

export function getCloudStatus(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/status`, method: 'GET' })
}

export function checkCloud(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/check`, method: 'POST' })
}

export function uploadToCloud(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/upload`, method: 'POST' })
}

export function updateFromCloud(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/update`, method: 'POST' })
}

// resolutions: { [path]: 'MAIN' | 'DRAFT' | 'BOTH' }（语境映射见 AdoptConflictDialog）
export function resolveCloudMerge(projectId, resolutions) {
  return request({ url: `/api/cloud/projects/${projectId}/resolve`, method: 'POST',
    data: { resolutions } })
}

export function abortCloudMerge(projectId) {
  return request({ url: `/api/cloud/projects/${projectId}/abort`, method: 'POST' })
}

export function resolveSessionEnd(projectId, sessionId, resolutions) {
  return request({ url: `/api/projects/${projectId}/version/session/resolve-end`, method: 'POST',
    data: { sessionId, resolutions } })
}

export function abortSessionEnd(projectId) {
  return request({ url: `/api/projects/${projectId}/version/session/abort-end`, method: 'POST' })
}
```

- [ ] **Step 2: admin.vue 三触点**——`navItems`（:580-586）插 `{ key: 'cloud', label: '云端协作', desktopOnly: true }`；`.admin-main` 加分区（照 `components` 分区形制）：

```html
<scroll-view v-else-if="activeNav === 'cloud'" scroll-y class="admin-scroll">
  <view class="section-card">
    <view class="section-header">
      <text class="section-title">云端协作</text>
      <text class="section-subtitle">连接团队服务器后，项目可以共享给同事、多人同步修改</text>
    </view>
    <view class="section-body">
      <view v-for="conn in cloudConnections" :key="conn.id" class="provider-card">
        <view class="provider-header">
          <text class="provider-name">{{ conn.serverUrl }}</text>
          <text class="cloud-conn-user">{{ conn.displayName || conn.username }}</text>
          <button class="awd-btn awd-btn-danger cloud-conn-btn"
                  @tap="onDisconnectCloud(conn)">断开连接</button>
        </view>
      </view>
      <view class="provider-card">
        <view class="provider-header"><text class="provider-name">连接新的团队服务器</text></view>
        <view class="form-row">
          <text class="form-label">服务器地址</text>
          <input class="form-input" v-model="cloudForm.serverUrl"
                 placeholder="https://team.example.com" />
        </view>
        <view class="form-row">
          <text class="form-label">账号</text>
          <input class="form-input" v-model="cloudForm.username" />
        </view>
        <view class="form-row">
          <text class="form-label">密码</text>
          <input class="form-input" password v-model="cloudForm.password" />
        </view>
        <button class="awd-btn awd-btn-primary" :disabled="cloudBusy"
                @tap="onConnectCloud">连接</button>
      </view>
    </view>
  </view>
</scroll-view>
```

data 加 `cloudConnections: []`, `cloudForm: { serverUrl: '', username: '', password: '' }`, `cloudBusy: false`；methods：

```javascript
async loadCloudConnections() {
  try {
    const res = await listCloudConnections()
    this.cloudConnections = (res.data && res.data.connections) || []
  } catch (e) { this.cloudConnections = [] }
},
async onConnectCloud() {
  if (!this.cloudForm.serverUrl || !this.cloudForm.username) return
  this.cloudBusy = true
  try {
    await cloudConnect(this.cloudForm.serverUrl.trim(), this.cloudForm.username.trim(),
      this.cloudForm.password, '桌面端')
    this.cloudForm = { serverUrl: '', username: '', password: '' }
    await this.loadCloudConnections()
    uni.showToast({ title: '已连接', icon: 'none' })
  } catch (e) {
    uni.showToast({ title: e.message || '连接失败', icon: 'none' })
  } finally { this.cloudBusy = false }
},
async onDisconnectCloud(conn) {
  const ok = await new Promise(r => uni.showModal({
    title: '断开云端连接',
    content: '断开后本机不再与该服务器同步，已关联项目要重新连接后才能继续上传。',
    success: res => r(res.confirm) }))
  if (!ok) return
  try {
    await disconnectCloudConnection(conn.id)
    await this.loadCloudConnections()
  } catch (e) { uni.showToast({ title: e.message || '操作失败', icon: 'none' }) }
},
```

`onNavTap` 进 `cloud` 时（或 mounted 后首次）调 `loadCloudConnections()`；import 三个 api。密码框用 `password` 属性——**绝不回显、绝不存前端**（只透传一次换令牌）。服务器地址为 `http://` 开头时表单下方渲染一行 `.cloud-http-warn`「未加密地址仅建议在律所内网使用」（spec 第二节传输安全项）。

- [ ] **Step 3: 验证 + Commit**

```bash
cd frontend && npm run check:emits && npm run build:h5
```
Expected: 双绿。

```bash
git add frontend/src/services/api.js frontend/src/pages/admin/admin.vue
git commit -m "feat(cloud): 前端——cloud API 系列与设置页云端协作分区

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 13: 前端——云端状态区、三语境冲突弹窗、接项目/共享入口、重载链

**Files:**
- Create: `frontend/src/components/version/CloudSyncBar.vue`
- Create: `frontend/src/components/CloudAcceptDialog.vue`
- Modify: `frontend/src/components/version/VersionPanel.vue`、`AdoptConflictDialog.vue`
- Modify: `frontend/src/pages/userprofile/userprofile.vue`（接项目入口）
- Modify: `frontend/src/pages/project-overview/project-overview.vue`（固定条覆盖三语境）

**Interfaces:**
- Consumes: Task 12 的 api；`VersionPanel.onReload` 漏斗（:141-144，一切带 affectedFileIds 的子事件汇入）；`AdoptConflictDialog` 现有 props/emits（:61-70）与选择常量（:76-80）。
- Produces: `CloudSyncBar` emits `['reload-files', 'shared']`；`AdoptConflictDialog` 新增可选 props `mode ('adopt'|'cloud'|'session-end')`、`sessionId`，按语境切标签与裁决 API；`CloudAcceptDialog` emits `['accepted']`。

**三语境标签映射（方向已在 Task 7/9 钉死，装反即数据事故级 UX 错误）**：

| 语境 | MAIN 标签 | DRAFT 标签 | BOTH 标签 | 对比 oldRef/oldLabel | 对比 newRef/newLabel | 裁决 API |
|---|---|---|---|---|---|---|
| adopt（现状） | 用主线的 | 用这一稿的 | 两份都留 | mainlineTip/主线上的 | draftTip/这一稿的 | resolveAdopt |
| cloud（更新冲突） | 用我这边的 | 用云端的 | 两份都留 | mainlineTip/我这边的 | draftTip(=cloudTip)/云端的 | resolveCloudMerge |
| session-end（结束工作撞车） | 用同事的 | 用我这边的 | 两份都留 | mainlineTip/同事的 | draftTip(=sessionTip)/我这边的 | resolveSessionEnd |

- [ ] **Step 1: AdoptConflictDialog 语境化**——props 加 `mode: { type: String, default: 'adopt' }`、`sessionId: { type: [String, Number], default: null }`；`choiceOptions`（:76-80）改 computed 按上表返回；`compare(row)`（:102-112）的 `newLabel/oldLabel` 按上表；确认方法按 mode 分派三个 API（cloud/session-end 不需要 draftId）；「先不采纳」按钮文案按 mode（cloud=「先不更新」→ abortCloudMerge；session-end=「先不收尾」→ abortSessionEnd）；弹窗标题按 mode（「采纳这一稿」/「云端有不同的修改」/「结束工作时发现同事的新版本」）。**根类名 `.adopt-dialog` 与行选择器 `.adopt-row-name` 不改**（e2e 既有断言依赖）。`awd-*` 样式 scoped 复制体不动。

- [ ] **Step 2: CloudSyncBar.vue**（挂在 VersionPanel 的 WorkSessionBar 下方，enabled 时渲染）：

```html
<template>
  <view class="cloud-bar">
    <template v-if="!cloud || !cloud.linked">
      <text class="cloud-text cloud-unlinked">这个项目还没有共享到云端</text>
      <button v-if="hasConnection" class="awd-btn awd-btn-secondary cloud-btn"
              :disabled="busy" @tap="onShare">共享到云端</button>
      <text v-else class="cloud-hint">先在设置里连接团队服务器</text>
    </template>
    <template v-else>
      <text class="cloud-dot" :class="stateClass"></text>
      <text class="cloud-text">{{ stateText }}</text>
      <button class="awd-btn awd-btn-secondary cloud-btn" :disabled="busy"
              @tap="onUpload">立即上传</button>
      <button class="awd-btn awd-btn-secondary cloud-btn" :disabled="busy"
              @tap="onUpdate">从云端更新</button>
    </template>
  </view>
</template>
```

script 要点：props `cloud`（VersionPanel 下发的 cloudStatus 对象）、`hasConnection`；`inject: ['projectId']`；computed `stateText`：`offline→'云端暂时连不上'`、`pendingUpload→'有改动待上传'`、`remoteAhead→'云端有新版本'`、否则 `'已与云端同步'`；`stateClass` 对应黄/蓝/绿点。方法：

```javascript
async onShare() {
  this.busy = true
  try {
    const conns = await listCloudConnections()
    const list = (conns.data && conns.data.connections) || []
    if (!list.length) { uni.showToast({ title: '请先在设置里连接团队服务器', icon: 'none' }); return }
    await shareProjectToCloud(this.projectId, list[0].id)
    uni.showToast({ title: '已共享到云端', icon: 'none' })
    this.$emit('shared')
  } catch (e) { uni.showToast({ title: e.message || '共享失败', icon: 'none' }) }
  finally { this.busy = false }
},
async onUpload() {
  this.busy = true
  try {
    const res = await uploadToCloud(this.projectId)
    const st = res.data && res.data.status
    if (st === 'UPLOADED') uni.showToast({ title: '已上传到云端', icon: 'none' })
    else if (st === 'CONFLICT') this.$emit('shared') // 让面板 refresh 弹冲突弹窗
    else uni.showToast({ title: (res.data && res.data.message) || '暂时没能上传', icon: 'none' })
    this.$emit('shared')
  } catch (e) { uni.showToast({ title: e.message || '上传失败', icon: 'none' }) }
  finally { this.busy = false }
},
async onUpdate() {
  this.busy = true
  try {
    const res = await updateFromCloud(this.projectId)
    const d = res.data || {}
    if (d.status === 'UPDATED') {
      uni.showToast({ title: '已从云端更新', icon: 'none' })
      this.$emit('reload-files', d.affectedFileIds || [])
    } else if (d.status === 'CONFLICT') {
      this.$emit('shared') // refresh → /status 带 cloudConflict → 弹窗
    } else if (d.status === 'OFFLINE') {
      uni.showToast({ title: '云端暂时连不上', icon: 'none' })
    } else {
      uni.showToast({ title: '已经是最新内容', icon: 'none' })
    }
  } catch (e) { uni.showToast({ title: e.message || '更新失败', icon: 'none' }) }
  finally { this.busy = false }
},
```

样式：`.cloud-bar { display: flex; flex-wrap: wrap; ... }` + `.cloud-btn { flex-shrink: 0 }`——**两个按钮 + 文字必须 `flex-wrap: wrap` + `.cloud-text { min-width: 200rpx }`**（v1 地雷 #24 一字不差照抄 WorkSessionBar :159-171 的注释与写法）。`.cloud-dot`/`.cloud-unlinked` 是 e2e 独有选择器。已关联态另给一行「成员」次级链接（`.cloud-members-link`）：点开简易弹窗（`.awd-dialog` 形制）列成员 + 按用户名加成员（走 Task 11 的两个代理端点），完整管理仍指网页端。

- [ ] **Step 3: VersionPanel 接线**——data 加 `cloud: null, hasConnection: false, cloudConflict: null, sessionEndConflict: null`；`refresh()` 里从 `res.data` 取 `cloudConflict`/`sessionEndConflict`，另拉 `getCloudStatus`（enabled 时）与 `listCloudConnections`（错误吞掉置默认）；`mounted` 加静默 `checkCloud(this.projectId).then(res => this.cloud = res.data).catch(() => {})`；模板 WorkSessionBar 下插 `<CloudSyncBar :cloud="cloud" :has-connection="hasConnection" @shared="refresh" @reload-files="onReload" />`；`AdoptConflictDialog` 挂载改三语境（互斥优先级：sessionEndConflict > cloudConflict > adoptConflict——与后端 /status 判定链同序）：

```html
<AdoptConflictDialog v-if="sessionEndConflict" mode="session-end"
  :project-id="projectId" :session-id="sessionEndConflict.sessionId"
  :draft-name="sessionEndConflict.title"
  :conflicting-paths="sessionEndConflict.conflictingPaths"
  :mainline-tip="sessionEndConflict.mainlineTip" :draft-tip="sessionEndConflict.sessionTip"
  @resolved="onReload" @aborted="refresh" @compare-file="$emit('compare-file', $event)" />
<AdoptConflictDialog v-else-if="cloudConflict" mode="cloud"
  :project-id="projectId"
  :conflicting-paths="cloudConflict.conflictingPaths"
  :mainline-tip="cloudConflict.mainlineTip" :draft-tip="cloudConflict.cloudTip"
  @resolved="onReload" @aborted="refresh" @compare-file="$emit('compare-file', $event)" />
<AdoptConflictDialog v-else-if="adoptConflict" ... 现状原样 ... />
```

`adopt-conflict` 事件（→ project-overview 固定条）改发 `!!(adoptConflict || cloudConflict || sessionEndConflict)`；project-overview 的 `checkAdoptConflict()`（:2400-2409）同样改三者取或。固定条文案「有一次采纳等待处理」改为通用「有一次合并等待处理」（`.adopt-pending-bar` 选择器不动）。

- [ ] **Step 4: CloudAcceptDialog.vue + userprofile 入口**——`.awd-mask > .awd-dialog` 模板（照 WorkSessionBar:22-38 形制，含 scoped `awd-*` 样式复制体）：打开时 `listCloudConnections` → 无连接给「去设置连接」提示；有则 `listRemoteProjects(首个连接)` 列表（`.cloud-project-row` 每行名字 + 「接到本地」按钮）→ `acceptCloudProject` → emit `accepted(localProjectId)`。userprofile.vue：`+ 新建项目` 按钮（:62）旁加 `从云端接一个项目` secondary 按钮（空态 :91-98 同加）→ 开弹窗；`@accepted` → `loadProjects()` + `goToProject(localProjectId)`。**不给 `.project-item-card` 卡体加第三个 tap 目标**（核验注记：卡体 tap=进入、标题 tap.stop=改名，已满）。

- [ ] **Step 5: 验证 + Commit**

```bash
cd frontend && npm run check:emits && npm run build:h5
```
Expected: 双绿（新 emits 全部在 emits 数组里声明——check:emits 就是查这个）。

```bash
git add frontend/src/components/ frontend/src/pages/
git commit -m "feat(cloud): 前端——云端状态区/三语境冲突弹窗/接项目与共享入口/重载链

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 14: e2e J11 多人协作旅程 + 领域文档更新

**Files:**
- Modify: `frontend/tests/app-e2e/run.mjs`（J11 + 多后端 spawn 基建）
- Modify: `frontend/tests/app-e2e/../../docs` 若有 QA_JOURNEYS.md 则同步一段
- Modify: `.claude/agents/version-control.md`

**Interfaces:**
- Consumes: run.mjs 既有 helpers（`api`/`step`/`mouseClickText`/`mouseClickSel`/`waitText`/`restOverwrite`，:41-147）；`.awd-dialog .uni-input-input` + `sleep(300)` 输入纪律（:373-383）；面板重挂载靠 rail 来回切（:456-462）。**现状实证：run.mjs 不 spawn 任何进程**，假设 9696 桌面后端 + 5174 前端已在跑——J11 的服务器与桌面 B 由本任务新增的 spawn helper 起。
- Produces: J11（预计 22-26 步）；`spawnBackend(tag, port)` helper。

- [ ] **Step 1: spawn 基建**（run.mjs 顶部 helpers 区）：

```javascript
// J11 需要两个额外后端：团队服务器 S 与"同事的桌面" B。三个隔离旋钮（核验实证）：
// SERVER_PORT（Spring relaxed binding）、SPRING_DATASOURCE_URL（desktop profile 的
// H2 带 AUTO_SERVER=TRUE，同路径会附着而非隔离——必须各给一个文件）、cwd（storage
// root-path 默认相对 'data'，随 cwd 隔离文件与 git 仓库）。
import { spawn } from 'node:child_process'
const J11_JAR = process.env.APP_E2E_JAR   // 由跑法提供：ls backend/target/*.jar
const spawned = []
async function spawnBackend(tag, port) {
  const home = path.join(OUT, 'j11-' + tag)
  fs.mkdirSync(path.join(home, 'cwd'), { recursive: true })
  const child = spawn(process.env.JAVA_HOME + '/bin/java', ['-jar', J11_JAR], {
    cwd: path.join(home, 'cwd'),
    env: { ...process.env,
      SPRING_PROFILES_ACTIVE: 'desktop',
      SERVER_PORT: String(port),
      SPRING_DATASOURCE_URL: `jdbc:h2:file:${home}/db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE` },
    stdio: ['ignore', 'pipe', 'pipe'] })
  spawned.push(child)
  const base = `http://127.0.0.1:${port}`
  for (let i = 0; i < 120; i++) {                       // 最多等 120s
    try { const r = await fetch(base + '/api/auth/me'); if (r.status === 200) return base }
    catch (e) { /* 未就绪 */ }
    await sleep(1000)
  }
  throw new Error(`backend ${tag}:${port} 未在 120s 内就绪`)
}
// finally 里：spawned.forEach(c => { try { c.kill('SIGTERM') } catch (e) {} })
```

`J11_JAR` 缺失时整段 J11 跳过并 `note('skip', 'J11 需要 APP_E2E_JAR')`——不许静默假绿，报告里必须可见跳过。

- [ ] **Step 2: J11 旅程**（J10 之后；骨架，断言全用组件独有选择器）：

```javascript
// ============ J11 云端协作：共享/接入/双向同步/冲突三选一 ============
// 拓扑：A = 既有 9696 桌面后端（UI 驱动），S = 团队服务器，B = 同事桌面（纯 REST 驱动）。
// 前端只有一个（连 A）——B 的所有动作走裸 REST，这是拓扑决定的，不是偷懒。
const S = await spawnBackend('server', 9701)
const B = await spawnBackend('desktopB', 9702)
const sApi = mkApi(S), bApi = mkApi(B)   // mkApi = 复刻 :41 的 api()，base 可变、sid 独立

await step('J11-服务器注册两个账号', async () => {
  await sApi('/api/auth/register', { method: 'POST', body: { username: 'lawyer_a', password: 'pw_a', displayName: '律师甲' } })
  await sApi('/api/auth/register', { method: 'POST', body: { username: 'lawyer_b', password: 'pw_b', displayName: '律师乙' } })
})
// A：UI 建项目 + 传文件 + 开版本记录 + 结束一段命名工作（复用 J9 的 runWorkSession 原语）
// A：设置页连接 S（admin 页 cloud 分区表单，.uni-input-input + sleep(300) 纪律）
// A：版本面板 CloudSyncBar「共享到云端」→ 断言 .cloud-dot 出现（已关联态）
// 服务器侧断言（sApi）：项目出现、version/status enabled、文件行在
// 服务器：lawyer_a 会话把 lawyer_b 加成 PARTICIPANT（POST /members）
// B（bApi）：connect → accept → 断言 B 本地 /api/projects/my 有了项目、文件内容一致
// B：restOverwrite 改文件 + POST /session/end（自动上传随之发生）→ 轮询 S 的 master 前进
// A：版本面板「从云端更新」→ 断言编辑器/文件树看到 B 的内容 + 时间线出现 B 的工作段节点
// 冲突段：B 再改同一文件并 end（推上去）；A 本地 UI 改同一文件、结束工作（标题「甲的撞车工作」）
//   → A 上传被拒自动合并 → 撞冲突 → 断言 .adopt-dialog 出现且 mode 标签是「用我这边的/用云端的」
//   →（先点「对比」验证收起条 .adopt-collapsed-bar）→ 选「两份都留」确认
//   → 断言文件树同时有原名与「（来自：云端）」副本、S 的 master 与 A 一致（裁决已重推）
// 收尾：A UI 删项目（既有 finally 会删 QA 项目）；S/B 进程 finally kill
```

实现时把上面每行注释落成真实 `step(...)`（预计 22-26 步）；J9/J10 的既有原语（`runWorkSession`/`uploadOne`/`restOverwrite`/rail 切换重挂载）全部复用；**弹窗输入一律 `.awd-dialog .uni-input-input` + `sleep(300)`**；`uni.showModal` 确认按「确定/OK 双试」范式（:387-390）。

- [ ] **Step 3: 实跑**

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q package -DskipTests && ls target/*.jar
cd frontend && APP_E2E_JAR=$(ls ../backend/target/*.jar | head -1) npm run test:app-e2e
```
Expected: J1-J11 全绿（基线 73 步 + J11 新增）。J1 若见登录抖动是既有 issue #200 口径，勿当回归查（app-e2e-j1-login-flake 记忆）。

- [ ] **Step 4: 领域文档更新**——`.claude/agents/version-control.md`：职责边界补 v2 一段；关键文件地图加 cloud 五类（GitHttpController/GitAccessService/CloudSyncService/CloudController/DeviceToken 与两实体）；核心契约补：清单 v2 归一化策略、三语境冲突判定链（sessionEnd→cloud→adopt）与方向表、receive 锁内 + 脏区停靠、endSession 双路径（mainAdvanced 分叉）、prepare-remote 空仓语义；已知地雷至少新增：**(a)** receive-pack 后工作区陈旧窗口——一切服务端本地提交路径必须与 receive 同锁，新入口先想 pendingIngest；**(b)** 三语境 MAIN/DRAFT 方向相反表（装反=静默数据覆盖）；**(c)** 清单 v2 的本机字段必须为 null，往 v2 节点里写本机 id 会在别人机器上造幽灵匹配；**(d)** pendingIngest 是内存态，服务端重启后靠下次 push/收尾自愈；**(e)** J11 需要 APP_E2E_JAR，缺了是显式 skip 不是绿。验证一节补 J11 与双后端跑法。

- [ ] **Step 5: Commit**

```bash
git add frontend/tests/app-e2e/run.mjs .claude/agents/version-control.md
git commit -m "test(e2e): J11 多人协作旅程——共享/接入/双向同步/冲突三选一；领域文档 v2 契约

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 终局验收（全部任务完成后，合并前必过）

1. `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test`——0 fail 0 error（预期 570+ 用例）。
2. `cd frontend && npm run check:emits && npm run build:h5`——双绿。
3. `cd frontend && APP_E2E_JAR=... npm run test:app-e2e`——J1-J11 全绿（J11 不许 skip）。
4. lowa-e2e 免跑声明成立性检查：本计划未触碰编辑器三件套（LibreOfficeEditor/librePool/office_thread）——`git diff --stat master` 确认后免跑；若实施中碰了，跑 `npm run test:lowa-e2e` 基线 44。
5. v1 全部护栏原样绿（历史不可重写/NO_FF/path-scoped abort/冲突窗口零 add/清单同提交）。
6. 整支终审（whole-branch review，新鲜上下文）后 commit → PR → 用户 Bypass 合并；**push 后复查 PR state**（#214 事故纪律）。
