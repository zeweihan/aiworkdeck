# 项目级版本记录 第 0–1 期 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让律师能在项目里「干活 → 结束本次工作 → 看时间线 → 退回去」，全程不出现 Git 术语。

**Architecture:** 后端用 JGit 在 `data/repos/project-{id}.git`（工作区指向 `data/projects/{id}`）维护每项目一个仓库。一次工作 = 一个分支，首次改动隐式开始、结束时合并回主线。数据库文件树每次提交前序列化进 `.awd/tree.json`，回退时反向同步回数据库。

**Tech Stack:** Java 17 / Spring Boot 3.2.4 / JGit / JPA(H2) / uni-app Vue 3 / puppeteer-core e2e

**Spec:** `docs/superpowers/specs/2026-07-28-project-git-version-control-design.md`

## Global Constraints

- **历史永不重写**（spec 5.5）：不做 squash / filter-branch / 过期删除。唯一例外是删除从未合并进主线的工作段分支。
- **界面零 Git 术语**：一律用 spec 第四节术语表的右列。代码内部可用 Git 词汇。
- **失败不阻断主流程**（spec 第七节）：版本记录的任何异常只记日志 + 面板提示，绝不弹窗、不阻断编辑或保存。
- **磁盘按无限处理**：不做配额、清理、体积治理。
- **全局禁用 emoji**：代码、UI 文案、注释、commit message 一律不用。
- **本机跑 mvn 必须 JDK 21**（系统默认 25 会 SIGBUS）：`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn ...`
- **前端包管理用 npm**，不是 pnpm。
- **worktree 有独立 src/**，编辑与构建必须在同一棵树内。
- `docs/` 在 .gitignore 里，向其中添加文件要 `git add -f`。

## 本计划的范围

只覆盖 spec 的第 0 期与第 1 期。第 2 期（修订对比）与第 3 期（另起一稿）等第 0 期探针有结论后再写——探针若失败，spec 5.7c 要重新设计，提前写的计划会作废。

**一处对 spec 的有意偏离：AI 署名推迟到第 2 期。** spec 5.3 承诺 AI 落的版本署名 `AI Workdeck`。要做到这点必须知道某次保存源自 AI，而 AI 的改动最终也是走编辑器自动保存落盘，从保存信号上分不出来。区分它需要挂钩 `AgentOrchestrator`，而改编排器构造器必须同步 `EvalHarness`（这个坑已经踩过两次）。第 1 期不碰编排器，所有版本一律署当前用户名；AI 署名随第 2 期一起做。

## 文件结构

**后端新增**

| 文件 | 职责 |
|---|---|
| `backend/src/main/java/com/checkba/version/ProjectRepoService.java` | JGit 薄封装。只管 Git 概念，不认识「工作段」 |
| `backend/src/main/java/com/checkba/version/VersionEntry.java` | 一条版本记录（record） |
| `backend/src/main/java/com/checkba/version/FileChange.java` | 一个文件变更（record） |
| `backend/src/main/java/com/checkba/version/MergeOutcome.java` | 合并结果（record） |
| `backend/src/main/java/com/checkba/version/TreeManifest.java` | 文件树清单数据结构（record） |
| `backend/src/main/java/com/checkba/version/ProjectTreeManifestService.java` | 清单的采集、落盘、读取、反向同步 |
| `backend/src/main/java/com/checkba/version/WorkSession.java` | 工作段实体 |
| `backend/src/main/java/com/checkba/version/WorkSessionRepository.java` | 工作段仓储 |
| `backend/src/main/java/com/checkba/version/WorkSessionService.java` | 工作段生命周期（本期核心） |
| `backend/src/main/java/com/checkba/version/VersionController.java` | REST 接口 + 权限 |
| `backend/src/main/java/com/checkba/version/RepoMaintenanceJob.java` | 每日 GC |

按职责分包到 `com.checkba.version`，而不是散进现有的 `service/` `controller/` —— 这些文件一起变，放一起。

**后端修改**

| 文件 | 改动 |
|---|---|
| `backend/pom.xml` | 加 JGit 依赖 |
| `backend/src/main/java/com/checkba/service/ProjectFileService.java` | 树变更后发变更信号 |
| `backend/src/main/java/com/checkba/controller/FileController.java` | 上传成功后发变更信号 |

**前端新增**

| 文件 | 职责 |
|---|---|
| `frontend/src/components/version/VersionPanel.vue` | 左栏面板容器 + 未开启引导 |
| `frontend/src/components/version/WorkSessionBar.vue` | 顶部工作状态条 + 结束工作 |
| `frontend/src/components/version/VersionTimeline.vue` | 三层时间线 |
| `frontend/src/components/version/VersionNodeDetail.vue` | 节点详情（改了哪些文件） |

**前端修改**

| 文件 | 改动 |
|---|---|
| `frontend/src/services/api.js` | 追加版本记录接口（项目规定：组件内禁止直接写 URL，一律走这里） |
| `frontend/src/config/leftSidebarPlugins.js` | 注册 rail 入口 |
| `frontend/src/pages/project-overview/project-overview.vue` | `sidebar-content` 加分支 |
| `frontend/tests/app-e2e/run.mjs` | 新增旅程 |
| `frontend/tests/lowa-e2e/run.mjs` | 第 0 期探针 |

**本期明确不做的两个 spec 条目**

- **「标为重要版本」（spec 5.3 的第三层）**：工作段本身可命名，「发客户第一稿」写成工作段标题即可，第 1 期用不着额外的里程碑标签。随第 2 期补。
- **FileTree 右键「这份文件的历史」（spec 5.8）**：单文件历史的价值主要在能看内容差异，与第 2 期的修订对比一起做才完整。第 1 期不动 `FileTree.vue`。

---

# 第 0 期：验证 `.uno:CompareDocuments`

**这一期不写业务代码。** 目的是回答一个是非题：LOWA 的 WASM 构建能不能用 LibreOffice 原生的比较功能产出修订标记。

**若失败**：停止，回报结论，spec 5.7c 重新设计（退回纯文本对比方案 b），第 2 期计划另写。第 1 期不受影响，可照常进行。

### Task 1: 在 lowa-e2e 中探测 CompareDocuments

**Files:**
- Modify: `frontend/tests/lowa-e2e/run.mjs`（`DEBUG_ACTIONS` 模板字符串内，以及测试主体末尾）

**Interfaces:**
- Consumes: 既有 worker action `load_document({bytes,name})`、`export_document()`、`debug_revisions()`、`replace_selection({text})`、`ui_command({name:'select_all'})`
- Produces: 结论——`.uno:CompareDocuments` 在 WASM 下是否可用，以及对比方向（旧版作参数时，插入/删除分别对应哪一版）

**背景（读代码得到，实施者不必重查）**

- MEMFS 写文件的既有可用写法在 `office_thread.js:976-988`：`SimpleFileAccess.create(context)` + `SequenceInputStream.createStreamFromSequence(context, bytes)` + `sfa.writeFile(url, stream)`。
- zetajs 编组硬规则：sequence 只收普通 Array，且 `sequence<byte>` 有符号，所以字节必须 `Array.from(new Int8Array(...))`（`office_thread.js:959` 有完整注释说明为什么）。
- 派发写法见 `office_thread.js:313`：`css.frame.DispatchHelper.create(context).executeDispatch(ctrl.getFrame(), url, '', 0, args)`。
- `mkProp(name, value)` 在 `office_thread.js:36`。
- `debug_revisions()` 在 `office_thread.js:1432`，返回 `{success, count, redlines:[{type,author,comment,text}]}`。
- 测试专用 action 由 run.mjs 在内存里注入，源码与 dist 保持干净——所以本任务**不修改 `office_thread.js`**。

- [ ] **Step 1: 加入探针 action**

在 `frontend/tests/lowa-e2e/run.mjs` 的 `DEBUG_ACTIONS` 模板字符串里追加（注意它是注入进 worker 的源码文本，写法要和相邻 action 一致）：

```js
  debug_compare_document(p) {
    // 把"旧版本"字节写进 MEMFS，再让当前文档与它比较。
    // 当前文档 = 新版本（由测试先 load_document 载入）。
    const raw = p && p.baseBytes;
    let u8 = null;
    if (raw instanceof ArrayBuffer) u8 = new Uint8Array(raw);
    else if (raw && raw.buffer instanceof ArrayBuffer) u8 = new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength);
    else if (Array.isArray(raw)) u8 = new Uint8Array(raw);
    if (!u8 || u8.length === 0) return { success: false, message: 'baseBytes empty' };
    const bytes = Array.from(new Int8Array(u8.buffer, u8.byteOffset, u8.byteLength));
    const url = 'file:///tmp/awd_base_cmp.docx';
    try {
      const sfa = css.ucb.SimpleFileAccess.create(context);
      try { if (sfa.exists(url)) sfa.kill(url); } catch (e) {}
      const stream = css.io.SequenceInputStream.createStreamFromSequence(context, bytes);
      sfa.writeFile(url, stream);
      try { stream.closeInput(); } catch (e) {}
    } catch (e) { return { success: false, stage: 'memfs', message: errStr(e) }; }
    try {
      css.frame.DispatchHelper.create(context).executeDispatch(
        ctrl.getFrame(), '.uno:CompareDocuments', '', 0, [mkProp('URL', url)]);
    } catch (e) { return { success: false, stage: 'dispatch', message: errStr(e) }; }
    return { success: true, url: url };
  },
```

- [ ] **Step 2: 加入探针测试组**

在 run.mjs 的测试主体末尾（最后一组 `check(...)` 之后、收尾统计之前）追加：

```js
// ---------- 组 13：CompareDocuments 探针（版本记录第 0 期）----------
console.log('\n[13] CompareDocuments 探针')
{
  const setText = async (t) => {
    await exec('debug_set_record_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: t })
  }

  await setText('甲方应于三十日内支付合同价款。')
  const oldBytes = (await exec('export_document')).bytes

  await setText('甲方应于六十日内支付合同价款。')
  const newBytes = (await exec('export_document')).bytes

  check('导出两版字节非空', !!oldBytes && !!newBytes && oldBytes.length > 0 && newBytes.length > 0)

  // 当前文档载入"新版本"，再与"旧版本"比较
  await exec('load_document', { bytes: newBytes, name: 'v2.docx', authorName: '测试用户' })
  const cmp = await exec('debug_compare_document', { baseBytes: oldBytes })
  check('CompareDocuments 派发成功', cmp && cmp.success === true,
    cmp && (cmp.stage + ':' + cmp.message))

  const rev = await exec('debug_revisions')
  check('比较后产生修订标记', rev && rev.success === true && rev.count > 0,
    'count=' + (rev && rev.count))

  // 记录方向：把修订内容打出来，供人工确认哪一版是"插入"哪一版是"删除"
  console.log('  [方向] redlines=' + JSON.stringify(rev && rev.redlines))
}
```

- [ ] **Step 3: 准备引擎并运行**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/frontend" && npm run build:zetaoffice && node ../desktop/scripts/fetch-lowa-assets.js
```

若 CDN 不可用，改从兄弟 worktree 复制引擎并用 `LOWA_ENGINE_DIR` 指过去。

- [ ] **Step 4: 跑探针**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/frontend" && npm run test:lowa-e2e
```

预期（成功路径）：第 13 组三条全 PASS，且 `[方向]` 那行能看到含「三十」的删除型修订与含「六十」的插入型修订。

**判定与分支：**

- 三条全 PASS → 第 0 期通过。把 `[方向]` 的实际结果记进 spec 5.7c（确认步骤 3/4 的新旧顺序是否要对调），然后继续第 1 期。
- `CompareDocuments 派发成功` FAIL 且 `stage=dispatch` → WASM 构建很可能未包含该功能。**停止，回报**。
- 派发成功但 `count=0` → 命令被静默忽略。**停止，回报**。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add frontend/tests/lowa-e2e/run.mjs && git commit -m "test(lowa-e2e): 探测 CompareDocuments 在 WASM 下的可用性"
```

---

# 第 1 期：工作段与时间线

### Task 2: JGit 依赖与仓库骨架

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/checkba/version/ProjectRepoService.java`
- Test: `backend/src/test/java/com/checkba/version/ProjectRepoServiceTest.java`

**Interfaces:**
- Consumes: `com.checkba.storage.StorageProperties`（拿 `local.rootPath`）
- Produces:
  - `Path ProjectRepoService.gitDir(long projectId)`
  - `Path ProjectRepoService.workTree(long projectId)`
  - `boolean ProjectRepoService.isInitialized(long projectId)`
  - `void ProjectRepoService.init(long projectId, String authorName, String authorEmail)`
  - `Repository ProjectRepoService.open(long projectId)`（调用方负责 close）

- [ ] **Step 1: 加依赖**

在 `backend/pom.xml` 的 `<dependencies>` 内加：

```xml
        <dependency>
            <groupId>org.eclipse.jgit</groupId>
            <artifactId>org.eclipse.jgit</artifactId>
            <version>6.9.0.202403050737-r</version>
        </dependency>
```

- [ ] **Step 2: 写失败测试**

创建 `backend/src/test/java/com/checkba/version/ProjectRepoServiceTest.java`：

```java
package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepoServiceTest {

    private ProjectRepoService svc(Path root) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        return new ProjectRepoService(props);
    }

    @Test
    void initCreatesRepoWithSeparateGitDirAndWorkTree(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        ProjectRepoService s = svc(root);
        assertFalse(s.isInitialized(7L));

        s.init(7L, "韩泽伟", "hzw@example.com");

        assertTrue(s.isInitialized(7L));
        assertTrue(Files.isDirectory(root.resolve("repos/project-7.git")));
        assertFalse(Files.exists(root.resolve("projects/7/.git")),
                "工作区目录下不得出现 .git");

        try (Repository repo = s.open(7L)) {
            assertEquals(root.resolve("projects/7").toRealPath(),
                    repo.getWorkTree().toPath().toRealPath());
            assertNotNull(repo.resolve("HEAD"), "初始版本应已提交");
        }
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ProjectRepoServiceTest
```

预期：编译失败，`ProjectRepoService` 不存在。

- [ ] **Step 4: 实现**

创建 `backend/src/main/java/com/checkba/version/ProjectRepoService.java`：

```java
package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 每项目一个 Git 仓库的薄封装。只认识 Git 概念，不认识「工作段」——
 * 业务语义在 WorkSessionService。
 *
 * 仓库目录与工作区分离：
 *   gitDir   = {root}/repos/project-{id}.git
 *   workTree = {root}/projects/{id}
 * 这样 .git 不会出现在 data/projects/ 下被 RAG 扫描、压缩包导出、搜索误伤。
 */
@Service
public class ProjectRepoService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProjectRepoService.class);

    private final Path storageRoot;

    public ProjectRepoService(StorageProperties storageProperties) {
        String rootPath = storageProperties.getLocal().getRootPath();
        Path root = Paths.get(rootPath);
        if (!root.isAbsolute()) {
            String userDir = System.getProperty("user.dir");
            Path base = Paths.get(userDir);
            if (userDir.endsWith("backend")) base = base.getParent();
            root = base.resolve(rootPath);
        }
        this.storageRoot = root;
    }

    public Path gitDir(long projectId) {
        return storageRoot.resolve("repos").resolve("project-" + projectId + ".git");
    }

    public Path workTree(long projectId) {
        return storageRoot.resolve("projects").resolve(String.valueOf(projectId));
    }

    public boolean isInitialized(long projectId) {
        return Files.isDirectory(gitDir(projectId).resolve("objects"));
    }

    public Repository open(long projectId) {
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(gitDir(projectId).toFile())
                    .setWorkTree(workTree(projectId).toFile())
                    .setMustExist(true)
                    .build();
        } catch (IOException e) {
            throw new VersionException("打开版本记录失败: project=" + projectId, e);
        }
    }

    /** 建仓库并落一笔「初始版本」。已存在则直接返回。 */
    public void init(long projectId, String authorName, String authorEmail) {
        if (isInitialized(projectId)) return;
        try {
            Files.createDirectories(workTree(projectId));
            Files.createDirectories(gitDir(projectId).getParent());
            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(gitDir(projectId).toFile())
                    .setWorkTree(workTree(projectId).toFile())
                    .build()) {
                repo.create(true);
                try (Git git = new Git(repo)) {
                    git.add().addFilepattern(".").call();
                    git.commit()
                       .setMessage("初始版本\n\nX-AWD-Kind: session")
                       .setAuthor(authorName, authorEmail)
                       .setAllowEmpty(true)
                       .call();
                }
            }
            log.info("版本记录已开启: project={}", projectId);
        } catch (Exception e) {
            throw new VersionException("开启版本记录失败: project=" + projectId, e);
        }
    }
}
```

同时创建 `backend/src/main/java/com/checkba/version/VersionException.java`：

```java
package com.checkba.version;

/** 版本记录相关异常。调用方一律捕获后降级，不得阻断主流程。 */
public class VersionException extends RuntimeException {
    public VersionException(String message, Throwable cause) { super(message, cause); }
    public VersionException(String message) { super(message); }
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ProjectRepoServiceTest
```

预期：PASS。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/pom.xml backend/src/main/java/com/checkba/version backend/src/test/java/com/checkba/version && git commit -m "feat(version): JGit 仓库骨架，git 目录与工作区分离"
```

---

### Task 3: 提交、历史、变更清单、按版本取字节

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/ProjectRepoService.java`
- Create: `backend/src/main/java/com/checkba/version/VersionEntry.java`
- Create: `backend/src/main/java/com/checkba/version/FileChange.java`
- Test: `backend/src/test/java/com/checkba/version/ProjectRepoHistoryTest.java`

**Interfaces:**
- Consumes: Task 2 的 `open` / `init` / `workTree`
- Produces:
  - `String commitAll(long projectId, String message, String kind, String note, String authorName, String authorEmail)` — 返回 commit sha；无变更时返回 `null`
  - `List<VersionEntry> log(long projectId, String ref, int limit)`
  - `List<FileChange> diffNameStatus(long projectId, String fromRef, String toRef)`
  - `byte[] readBlobAtCommit(long projectId, String ref, String relPath)` — 不存在返回 `null`
  - `record VersionEntry(String sha, String message, String authorName, java.time.Instant when, String kind, String note, java.util.List<String> parents)`
  - `record FileChange(String path, FileChange.Type type)`，`enum Type { ADD, MODIFY, DELETE, RENAME }`

- [ ] **Step 1: 写 record**

`backend/src/main/java/com/checkba/version/VersionEntry.java`：

```java
package com.checkba.version;

import java.time.Instant;
import java.util.List;

/**
 * 一条版本记录。kind 取自提交消息尾注 X-AWD-Kind：
 *   auto    = 工作段内的自动存档
 *   session = 工作段本身（合并节点）
 */
public record VersionEntry(
        String sha,
        String message,
        String authorName,
        Instant when,
        String kind,
        String note,
        List<String> parents
) {}
```

`backend/src/main/java/com/checkba/version/FileChange.java`：

```java
package com.checkba.version;

public record FileChange(String path, Type type) {
    public enum Type { ADD, MODIFY, DELETE, RENAME }
}
```

- [ ] **Step 2: 写失败测试**

`backend/src/test/java/com/checkba/version/ProjectRepoHistoryTest.java`：

```java
package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepoHistoryTest {

    private ProjectRepoService svc(Path root) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        return new ProjectRepoService(props);
    }

    private ProjectRepoService seeded(Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");
        ProjectRepoService s = svc(root);
        s.init(7L, "韩泽伟", "hzw@example.com");
        return s;
    }

    @Test
    void commitAllReturnsNullWhenNothingChanged(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        assertNull(s.commitAll(7L, "无变更", "auto", null, "韩泽伟", "hzw@example.com"));
    }

    @Test
    void commitAllRecordsKindAndNote(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");

        String sha = s.commitAll(7L, "修改了《合同》", "session", "发客户第一稿",
                "韩泽伟", "hzw@example.com");
        assertNotNull(sha);

        List<VersionEntry> log = s.log(7L, "HEAD", 10);
        assertEquals(2, log.size());
        VersionEntry head = log.get(0);
        assertEquals("修改了《合同》", head.message());
        assertEquals("session", head.kind());
        assertEquals("发客户第一稿", head.note());
        assertEquals("韩泽伟", head.authorName());
    }

    @Test
    void diffNameStatusClassifiesChanges(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        Files.writeString(root.resolve("projects/7/新增.txt"), "新文件");
        String sha = s.commitAll(7L, "改了", "auto", null, "韩泽伟", "hzw@example.com");

        List<FileChange> changes = s.diffNameStatus(7L, sha + "^", sha);
        assertEquals(2, changes.size());
        assertTrue(changes.stream().anyMatch(
                c -> c.path().equals("合同.txt") && c.type() == FileChange.Type.MODIFY));
        assertTrue(changes.stream().anyMatch(
                c -> c.path().equals("新增.txt") && c.type() == FileChange.Type.ADD));
    }

    @Test
    void readBlobAtCommitReturnsHistoricBytes(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        String first = s.log(7L, "HEAD", 1).get(0).sha();

        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        s.commitAll(7L, "改了", "auto", null, "韩泽伟", "hzw@example.com");

        byte[] old = s.readBlobAtCommit(7L, first, "合同.txt");
        assertEquals("初稿", new String(old, StandardCharsets.UTF_8));
        assertNull(s.readBlobAtCommit(7L, first, "不存在.txt"));
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ProjectRepoHistoryTest
```

预期：编译失败，`commitAll` 等方法不存在。

- [ ] **Step 4: 实现**

在 `ProjectRepoService` 中追加 import 与方法：

```java
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
```

```java
    private static final String KIND_TRAILER = "X-AWD-Kind: ";
    private static final String NOTE_TRAILER = "X-AWD-Note: ";

    /**
     * 把工作区当前状态整体提交。无任何变更时返回 null（不产生空提交）。
     * kind 写入提交消息尾注，供时间线区分「自动存档」与「工作段」。
     */
    public String commitAll(long projectId, String message, String kind, String note,
                            String authorName, String authorEmail) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            Status status = git.status().call();
            if (status.isClean()) return null;

            StringBuilder msg = new StringBuilder(message).append("\n\n")
                    .append(KIND_TRAILER).append(kind);
            if (note != null && !note.isBlank()) {
                msg.append('\n').append(NOTE_TRAILER).append(note);
            }
            RevCommit c = git.commit()
                    .setMessage(msg.toString())
                    .setAuthor(authorName, authorEmail)
                    .call();
            return c.getName();
        } catch (Exception e) {
            throw new VersionException("提交失败: project=" + projectId, e);
        }
    }

    public List<VersionEntry> log(long projectId, String ref, int limit) {
        List<VersionEntry> out = new ArrayList<>();
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            ObjectId start = repo.resolve(ref);
            if (start == null) return out;
            for (RevCommit c : git.log().add(start).setMaxCount(limit).call()) {
                out.add(toEntry(c));
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取历史失败: project=" + projectId, e);
        }
    }

    private VersionEntry toEntry(RevCommit c) {
        String full = c.getFullMessage();
        String kind = extractTrailer(full, KIND_TRAILER);
        String note = extractTrailer(full, NOTE_TRAILER);
        List<String> parents = new ArrayList<>();
        for (RevCommit p : c.getParents()) parents.add(p.getName());
        return new VersionEntry(
                c.getName(),
                c.getShortMessage(),
                c.getAuthorIdent().getName(),
                Instant.ofEpochSecond(c.getCommitTime()),
                kind == null ? "auto" : kind,
                note,
                parents);
    }

    private String extractTrailer(String fullMessage, String prefix) {
        for (String line : fullMessage.split("\n")) {
            String t = line.trim();
            if (t.startsWith(prefix)) return t.substring(prefix.length()).trim();
        }
        return null;
    }

    public List<FileChange> diffNameStatus(long projectId, String fromRef, String toRef) {
        List<FileChange> out = new ArrayList<>();
        try (Repository repo = open(projectId); Git git = new Git(repo);
             RevWalk walk = new RevWalk(repo)) {
            ObjectId from = repo.resolve(fromRef);
            ObjectId to = repo.resolve(toRef);
            if (from == null || to == null) return out;

            CanonicalTreeParser fromTree = new CanonicalTreeParser();
            CanonicalTreeParser toTree = new CanonicalTreeParser();
            fromTree.reset(repo.newObjectReader(), walk.parseCommit(from).getTree());
            toTree.reset(repo.newObjectReader(), walk.parseCommit(to).getTree());

            for (DiffEntry d : git.diff().setOldTree(fromTree).setNewTree(toTree).call()) {
                out.add(new FileChange(
                        d.getChangeType() == DiffEntry.ChangeType.DELETE
                                ? d.getOldPath() : d.getNewPath(),
                        switch (d.getChangeType()) {
                            case ADD, COPY -> FileChange.Type.ADD;
                            case DELETE -> FileChange.Type.DELETE;
                            case RENAME -> FileChange.Type.RENAME;
                            default -> FileChange.Type.MODIFY;
                        }));
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取变更清单失败: project=" + projectId, e);
        }
    }

    /** 取某一版里某个相对路径的完整字节；该版中不存在该文件时返回 null。 */
    public byte[] readBlobAtCommit(long projectId, String ref, String relPath) {
        try (Repository repo = open(projectId); RevWalk walk = new RevWalk(repo)) {
            ObjectId commitId = repo.resolve(ref);
            if (commitId == null) return null;
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk tw = TreeWalk.forPath(repo, relPath, commit.getTree())) {
                if (tw == null) return null;
                ObjectLoader loader = repo.open(tw.getObjectId(0));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                loader.copyTo(bos);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            throw new VersionException("读取历史文件失败: project=" + projectId, e);
        }
    }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ProjectRepoHistoryTest
```

预期：4 个测试全 PASS。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/src && git commit -m "feat(version): 提交、历史、变更清单、按版本取文件字节"
```

---

### Task 4: 分支操作与合并

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/ProjectRepoService.java`
- Create: `backend/src/main/java/com/checkba/version/MergeOutcome.java`
- Test: `backend/src/test/java/com/checkba/version/ProjectRepoBranchTest.java`

**Interfaces:**
- Consumes: Task 3 的 `commitAll` / `log`
- Produces:
  - `void createBranch(long projectId, String name, String startPointRef)`
  - `void checkoutBranch(long projectId, String name)`
  - `String currentBranch(long projectId)`
  - `List<String> listBranches(long projectId)`
  - `void deleteBranch(long projectId, String name, boolean force)`
  - `MergeOutcome merge(long projectId, String branchName, String message, String authorName, String authorEmail)`
  - `String mainBranch()` — 常量 `"master"`（JGit 默认初始分支名）
  - `record MergeOutcome(boolean success, boolean fastForward, java.util.List<String> conflictingPaths, String mergeSha)`

- [ ] **Step 1: 写 record**

`backend/src/main/java/com/checkba/version/MergeOutcome.java`：

```java
package com.checkba.version;

import java.util.List;

/**
 * 合并结果。conflictingPaths 非空即冲突，此时 success=false 且仓库已回到合并前状态
 * （spec 第七节：合并失败要保证两份稿件都还在）。
 */
public record MergeOutcome(
        boolean success,
        boolean fastForward,
        List<String> conflictingPaths,
        String mergeSha
) {}
```

- [ ] **Step 2: 写失败测试**

`backend/src/test/java/com/checkba/version/ProjectRepoBranchTest.java`：

```java
package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepoBranchTest {

    private ProjectRepoService seeded(Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService s = new ProjectRepoService(props);
        s.init(7L, "韩泽伟", "hzw@example.com");
        return s;
    }

    @Test
    void createCheckoutAndListBranches(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        assertEquals(s.mainBranch(), s.currentBranch(7L));

        s.createBranch(7L, "work/1001", "HEAD");
        s.checkoutBranch(7L, "work/1001");

        assertEquals("work/1001", s.currentBranch(7L));
        assertTrue(s.listBranches(7L).contains("work/1001"));
    }

    @Test
    void mergeIsFastForwardWhenMainUntouched(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "work/1001", "HEAD");
        s.checkoutBranch(7L, "work/1001");

        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        s.commitAll(7L, "改了", "auto", null, "韩泽伟", "hzw@example.com");

        s.checkoutBranch(7L, s.mainBranch());
        MergeOutcome r = s.merge(7L, "work/1001", "7 月 28 日下午的工作",
                "韩泽伟", "hzw@example.com");

        assertTrue(r.success());
        assertTrue(r.fastForward());
        assertTrue(r.conflictingPaths().isEmpty());
        assertEquals("二稿", Files.readString(root.resolve("projects/7/合同.txt")));
    }

    @Test
    void mergeReportsConflictAndLeavesBothSidesIntact(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "work/1001", "HEAD");

        // 主线先动
        Files.writeString(root.resolve("projects/7/合同.txt"), "主线改动");
        s.commitAll(7L, "主线改了", "auto", null, "韩泽伟", "hzw@example.com");

        // 稿件在旧起点上改同一文件
        s.checkoutBranch(7L, "work/1001");
        Files.writeString(root.resolve("projects/7/合同.txt"), "稿件改动");
        s.commitAll(7L, "稿件改了", "auto", null, "韩泽伟", "hzw@example.com");

        s.checkoutBranch(7L, s.mainBranch());
        MergeOutcome r = s.merge(7L, "work/1001", "采纳", "韩泽伟", "hzw@example.com");

        assertFalse(r.success());
        assertTrue(r.conflictingPaths().contains("合同.txt"));
        // 合并失败后主线内容不得被破坏
        assertEquals("主线改动", Files.readString(root.resolve("projects/7/合同.txt")));
    }

    @Test
    void deleteBranchRemovesUnmergedWork(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        s.createBranch(7L, "work/1001", "HEAD");
        s.checkoutBranch(7L, "work/1001");
        Files.writeString(root.resolve("projects/7/合同.txt"), "废弃改动");
        s.commitAll(7L, "改了", "auto", null, "韩泽伟", "hzw@example.com");

        s.checkoutBranch(7L, s.mainBranch());
        s.deleteBranch(7L, "work/1001", true);

        assertFalse(s.listBranches(7L).contains("work/1001"));
        assertEquals("初稿", Files.readString(root.resolve("projects/7/合同.txt")));
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ProjectRepoBranchTest
```

预期：编译失败。

- [ ] **Step 4: 实现**

在 `ProjectRepoService` 追加 import：

```java
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.lib.Ref;
import java.util.Collections;
```

追加方法：

```java
    private static final String MAIN_BRANCH = "master";

    public String mainBranch() { return MAIN_BRANCH; }

    public void createBranch(long projectId, String name, String startPointRef) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.branchCreate()
               .setName(name)
               .setStartPoint(startPointRef)
               .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.NOTRACK)
               .call();
        } catch (Exception e) {
            throw new VersionException("创建分支失败: " + name, e);
        }
    }

    public void checkoutBranch(long projectId, String name) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.checkout().setName(name).call();
        } catch (Exception e) {
            throw new VersionException("切换分支失败: " + name, e);
        }
    }

    public String currentBranch(long projectId) {
        try (Repository repo = open(projectId)) {
            return repo.getBranch();
        } catch (Exception e) {
            throw new VersionException("读取当前分支失败: project=" + projectId, e);
        }
    }

    public List<String> listBranches(long projectId) {
        List<String> out = new ArrayList<>();
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            for (Ref r : git.branchList().call()) {
                out.add(Repository.shortenRefName(r.getName()));
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取分支列表失败: project=" + projectId, e);
        }
    }

    public void deleteBranch(long projectId, String name, boolean force) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.branchDelete().setBranchNames(name).setForce(force).call();
        } catch (Exception e) {
            throw new VersionException("删除分支失败: " + name, e);
        }
    }

    /**
     * 把 branchName 合并进当前分支。
     * 冲突时把工作区硬重置回合并前的 HEAD——spec 第七节要求合并失败后两份稿件都还在，
     * 稿件分支本身未被触碰，所以只需还原当前分支的工作区。
     */
    public MergeOutcome merge(long projectId, String branchName, String message,
                              String authorName, String authorEmail) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            ObjectId target = repo.resolve(branchName);
            if (target == null) throw new VersionException("分支不存在: " + branchName);

            MergeResult r = git.merge()
                    .include(target)
                    .setMessage(message + "\n\n" + KIND_TRAILER + "session")
                    .setCommit(true)
                    .call();

            MergeResult.MergeStatus st = r.getMergeStatus();
            if (st.isSuccessful()) {
                return new MergeOutcome(true,
                        st == MergeResult.MergeStatus.FAST_FORWARD,
                        Collections.emptyList(),
                        r.getNewHead() == null ? null : r.getNewHead().getName());
            }

            List<String> conflicts = r.getConflicts() == null
                    ? Collections.emptyList()
                    : new ArrayList<>(r.getConflicts().keySet());
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call();
            return new MergeOutcome(false, false, conflicts, null);
        } catch (VersionException e) {
            throw e;
        } catch (Exception e) {
            throw new VersionException("合并失败: " + branchName, e);
        }
    }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ProjectRepoBranchTest
```

预期：4 个测试全 PASS。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/src && git commit -m "feat(version): 分支创建/切换/删除与合并，冲突时还原工作区"
```

---

### Task 5: 文件树清单的采集与落盘

**Files:**
- Create: `backend/src/main/java/com/checkba/version/TreeManifest.java`
- Create: `backend/src/main/java/com/checkba/version/ProjectTreeManifestService.java`
- Test: `backend/src/test/java/com/checkba/version/TreeManifestCaptureTest.java`

**Interfaces:**
- Consumes: `ProjectFileRepository`（已有）、Task 2 的 `workTree`、Task 3 的 `readBlobAtCommit`、Jackson `ObjectMapper`（Spring 已提供）
- Produces:
  - `record TreeManifest(int version, java.util.List<TreeManifest.Node> nodes)`
  - `record TreeManifest.Node(Long id, Long parentId, String name, boolean isFolder, String fileType, Integer sortOrder, String filePath, boolean isDeleted)`
  - `TreeManifest ProjectTreeManifestService.capture(long projectId)`
  - `void ProjectTreeManifestService.writeToWorkTree(long projectId, TreeManifest m)`
  - `TreeManifest ProjectTreeManifestService.readAtRef(long projectId, String ref)` — 该版无清单时返回 `null`
  - `String ProjectTreeManifestService.MANIFEST_PATH` = `".awd/tree.json"`

标签（`tags`）本期不入清单：`ProjectFile.tags` 是 `@Transient`，需要额外查询，而第 1 期的时间线与退回都用不到它。第 3 期做另起一稿时再补。

- [ ] **Step 1: 写 record**

`backend/src/main/java/com/checkba/version/TreeManifest.java`：

```java
package com.checkba.version;

import java.util.List;

/**
 * 某一版的完整文件树快照。
 *
 * 存在的理由：数据库才是文件树的真源，磁盘只是投影（软删除不动磁盘文件，
 * 物理重命名失败时数据库仍会改名）。只跟踪磁盘文件不足以还原一个版本。
 */
public record TreeManifest(int version, List<Node> nodes) {

    public static final int CURRENT_VERSION = 1;

    public record Node(
            Long id,
            Long parentId,
            String name,
            boolean isFolder,
            String fileType,
            Integer sortOrder,
            String filePath,
            boolean isDeleted
    ) {}
}
```

- [ ] **Step 2: 写失败测试**

`backend/src/test/java/com/checkba/version/TreeManifestCaptureTest.java`：

```java
package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TreeManifestCaptureTest {

    private ProjectFile file(Long id, Long parentId, String name, boolean folder,
                             String path, boolean deleted) {
        ProjectFile f = new ProjectFile();
        f.setId(id); f.setProjectId(7L); f.setParentId(parentId); f.setName(name);
        f.setIsFolder(folder); f.setFileType(folder ? null : "docx");
        f.setSortOrder(0); f.setFilePath(path); f.setIsDeleted(deleted);
        return f;
    }

    @Test
    void captureIncludesDeletedNodesAndRoundTripsThroughDisk(@TempDir Path root) throws Exception {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        when(repo.findByProjectId(7L)).thenReturn(List.of(
                file(1L, null, "重要协议", true, null, false),
                file(2L, 1L, "股权转让协议.docx", false, "projects/7/重要协议/股权转让协议.docx", false),
                file(3L, null, "废弃.docx", false, "projects/7/废弃.docx", true)
        ));

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService repoSvc = new ProjectRepoService(props);
        ProjectTreeManifestService svc =
                new ProjectTreeManifestService(repo, repoSvc, new ObjectMapper());

        TreeManifest m = svc.capture(7L);
        assertEquals(TreeManifest.CURRENT_VERSION, m.version());
        assertEquals(3, m.nodes().size(), "软删除的节点也必须在清单里");
        assertTrue(m.nodes().stream().anyMatch(n -> n.id() == 3L && n.isDeleted()));

        Files.createDirectories(root.resolve("projects/7"));
        svc.writeToWorkTree(7L, m);

        Path onDisk = root.resolve("projects/7/.awd/tree.json");
        assertTrue(Files.exists(onDisk));

        TreeManifest back = new ObjectMapper().readValue(
                Files.readString(onDisk), TreeManifest.class);
        assertEquals(m, back, "序列化→反序列化必须恒等");
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=TreeManifestCaptureTest
```

预期：编译失败。若 `ProjectFileRepository` 没有 `findByProjectId(Long)`，本步会暴露出来——下一步补。

- [ ] **Step 4: 实现**

若 `backend/src/main/java/com/checkba/repository/ProjectFileRepository.java` 中没有 `findByProjectId`，加上：

```java
    List<ProjectFile> findByProjectId(Long projectId);
```

创建 `backend/src/main/java/com/checkba/version/ProjectTreeManifestService.java`：

```java
package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 文件树清单：把数据库里的文件树随版本一起存进仓库，
 * 使「退回到某一版」能同时还原目录结构、排序与回收站状态。
 */
@Service
public class ProjectTreeManifestService {

    public static final String MANIFEST_PATH = ".awd/tree.json";

    private final ProjectFileRepository projectFileRepository;
    private final ProjectRepoService repoService;
    private final ObjectMapper objectMapper;

    public ProjectTreeManifestService(ProjectFileRepository projectFileRepository,
                                      ProjectRepoService repoService,
                                      ObjectMapper objectMapper) {
        this.projectFileRepository = projectFileRepository;
        this.repoService = repoService;
        this.objectMapper = objectMapper;
    }

    /** 从数据库采集当前文件树。软删除的节点也要收进来，否则回退无法还原回收站状态。 */
    public TreeManifest capture(long projectId) {
        List<TreeManifest.Node> nodes = projectFileRepository.findByProjectId(projectId)
                .stream()
                .sorted(Comparator.comparing(ProjectFile::getId))
                .map(f -> new TreeManifest.Node(
                        f.getId(),
                        f.getParentId(),
                        f.getName(),
                        Boolean.TRUE.equals(f.getIsFolder()),
                        f.getFileType(),
                        f.getSortOrder(),
                        f.getFilePath(),
                        Boolean.TRUE.equals(f.getIsDeleted())))
                .toList();
        return new TreeManifest(TreeManifest.CURRENT_VERSION, nodes);
    }

    public void writeToWorkTree(long projectId, TreeManifest manifest) {
        try {
            Path target = repoService.workTree(projectId).resolve(MANIFEST_PATH);
            Files.createDirectories(target.getParent());
            Files.writeString(target,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new VersionException("写入文件树清单失败: project=" + projectId, e);
        }
    }

    /** 读取某一版的清单；该版没有清单（例如开启版本记录之前的历史）时返回 null。 */
    public TreeManifest readAtRef(long projectId, String ref) {
        byte[] bytes = repoService.readBlobAtCommit(projectId, ref, MANIFEST_PATH);
        if (bytes == null || bytes.length == 0) return null;
        try {
            return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8),
                    TreeManifest.class);
        } catch (Exception e) {
            throw new VersionException("解析文件树清单失败: project=" + projectId + " ref=" + ref, e);
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=TreeManifestCaptureTest
```

预期：PASS。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/src && git commit -m "feat(version): 文件树清单采集与落盘"
```

---

### Task 6: 清单反向同步回数据库

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/ProjectTreeManifestService.java`
- Test: `backend/src/test/java/com/checkba/version/TreeManifestSyncTest.java`

**Interfaces:**
- Consumes: Task 5 的 `TreeManifest`、`ProjectFileRepository`
- Produces:
  - `SyncReport ProjectTreeManifestService.applyToDatabase(long projectId, TreeManifest m)`
  - `record ProjectTreeManifestService.SyncReport(int created, int updated, int softDeleted)`

**算法（spec 5.4）**：差异同步，不是删表重建。

- 清单有、数据库无 → 重建记录，优先沿用原 id；id 已被占用则新建
- 数据库有、清单无 → 标记为已删除（进回收站，不物理删）
- 两边都有但属性不同 → 更新属性

id 被占用而新建时，维护一张 旧id→新id 的临时映射，**仅用于修正同一次同步内其他节点的 parentId**，用完即弃。

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/checkba/version/TreeManifestSyncTest.java`：

```java
package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TreeManifestSyncTest {

    private Map<Long, ProjectFile> db;
    private ProjectFileRepository repo;
    private ProjectTreeManifestService svc;
    private long nextId;

    private ProjectFile f(Long id, Long parentId, String name, boolean folder,
                          String path, boolean deleted, int sortOrder) {
        ProjectFile p = new ProjectFile();
        p.setId(id); p.setProjectId(7L); p.setParentId(parentId); p.setName(name);
        p.setIsFolder(folder); p.setFileType(folder ? null : "docx");
        p.setSortOrder(sortOrder); p.setFilePath(path); p.setIsDeleted(deleted);
        p.setUserId(1L);
        return p;
    }

    private TreeManifest.Node n(Long id, Long parentId, String name, boolean folder,
                                String path, boolean deleted, int sortOrder) {
        return new TreeManifest.Node(id, parentId, name, folder,
                folder ? null : "docx", sortOrder, path, deleted);
    }

    @BeforeEach
    void setUp(@TempDir Path root) {
        db = new HashMap<>();
        nextId = 100L;
        repo = mock(ProjectFileRepository.class);
        when(repo.findByProjectId(7L)).thenAnswer(i -> new ArrayList<>(db.values()));
        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(db.get(i.getArgument(0))));
        when(repo.save(any(ProjectFile.class))).thenAnswer(i -> {
            ProjectFile p = i.getArgument(0);
            if (p.getId() == null) p.setId(nextId++);
            db.put(p.getId(), p);
            return p;
        });
        when(repo.existsById(any())).thenAnswer(i -> db.containsKey(i.getArgument(0)));

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        svc = new ProjectTreeManifestService(repo, new ProjectRepoService(props), new ObjectMapper());
    }

    @Test
    void createsMissingNode() {
        TreeManifest m = new TreeManifest(1, List.of(
                n(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0)));

        var r = svc.applyToDatabase(7L, m);

        assertEquals(1, r.created());
        assertEquals("合同.docx", db.get(1L).getName());
    }

    @Test
    void softDeletesNodeAbsentFromManifest() {
        db.put(1L, f(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0));
        db.put(2L, f(2L, null, "多余.docx", false, "projects/7/多余.docx", false, 1));

        var r = svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0))));

        assertEquals(1, r.softDeleted());
        assertTrue(db.get(2L).getIsDeleted(), "清单里没有的节点应进回收站");
        assertNotNull(db.get(2L), "不得物理删除");
    }

    @Test
    void updatesRenamedNode() {
        db.put(1L, f(1L, null, "旧名.docx", false, "projects/7/旧名.docx", false, 0));

        var r = svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(1L, null, "新名.docx", false, "projects/7/新名.docx", false, 0))));

        assertEquals(1, r.updated());
        assertEquals("新名.docx", db.get(1L).getName());
        assertEquals("projects/7/新名.docx", db.get(1L).getFilePath());
    }

    @Test
    void updatesMovedNode() {
        db.put(1L, f(1L, null, "文件夹", true, null, false, 0));
        db.put(2L, f(2L, null, "合同.docx", false, "projects/7/合同.docx", false, 1));

        svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(1L, null, "文件夹", true, null, false, 0),
                n(2L, 1L, "合同.docx", false, "projects/7/文件夹/合同.docx", false, 1))));

        assertEquals(1L, db.get(2L).getParentId());
    }

    @Test
    void restoresNodeFromRecycleBin() {
        db.put(1L, f(1L, null, "合同.docx", false, "projects/7/合同.docx", true, 0));

        svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(1L, null, "合同.docx", false, "projects/7/合同.docx", false, 0))));

        assertFalse(db.get(1L).getIsDeleted(), "清单里未删除的节点应从回收站恢复");
    }

    @Test
    void remapsParentIdWhenOriginalIdTaken() {
        // 数据库里 id=1 已被别的记录占用，清单里的 1 号节点必须新建并改写子节点 parentId
        db.put(1L, f(1L, null, "占位者.docx", false, "projects/7/占位者.docx", false, 0));

        svc.applyToDatabase(7L, new TreeManifest(1, List.of(
                n(1L, null, "文件夹", true, null, false, 0),
                n(2L, 1L, "合同.docx", false, "projects/7/文件夹/合同.docx", false, 1))));

        ProjectFile folder = db.values().stream()
                .filter(p -> "文件夹".equals(p.getName())).findFirst().orElseThrow();
        ProjectFile child = db.values().stream()
                .filter(p -> "合同.docx".equals(p.getName())).findFirst().orElseThrow();

        assertNotEquals(1L, folder.getId(), "被占用的 id 应改为新建");
        assertEquals(folder.getId(), child.getParentId(), "子节点 parentId 必须跟着重映射");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=TreeManifestSyncTest
```

预期：编译失败，`applyToDatabase` 不存在。

- [ ] **Step 3: 实现**

在 `ProjectTreeManifestService` 追加 import：

```java
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
```

追加：

```java
    public record SyncReport(int created, int updated, int softDeleted) {}

    /**
     * 把清单描述的文件树同步进数据库。差异同步，不是删表重建——
     * 保证回退不会丢掉当前状态里的任何东西，且回退本身可以再回退。
     */
    public SyncReport applyToDatabase(long projectId, TreeManifest manifest) {
        Map<Long, ProjectFile> current = new HashMap<>();
        for (ProjectFile f : projectFileRepository.findByProjectId(projectId)) {
            current.put(f.getId(), f);
        }

        // 清单节点按「父先于子」排序，保证建父节点时 parentId 已可解析
        List<TreeManifest.Node> ordered = topoSort(manifest.nodes());

        Map<Long, Long> remap = new LinkedHashMap<>();
        int created = 0, updated = 0;

        for (TreeManifest.Node node : ordered) {
            Long targetParentId = node.parentId() == null
                    ? null : remap.getOrDefault(node.parentId(), node.parentId());

            ProjectFile existing = current.get(node.id());
            boolean idTakenByOther = existing != null && !sameNode(existing, node);

            if (existing != null && !idTakenByOther) {
                if (applyAttributes(existing, node, targetParentId)) {
                    projectFileRepository.save(existing);
                    updated++;
                }
                current.remove(node.id());
                continue;
            }

            ProjectFile fresh = new ProjectFile();
            if (existing == null) fresh.setId(node.id());
            fresh.setProjectId(projectId);
            fresh.setUserId(existing != null ? existing.getUserId() : 1L);
            fresh.setCreatedAt(LocalDateTime.now());
            applyAttributes(fresh, node, targetParentId);
            ProjectFile saved = projectFileRepository.save(fresh);
            if (!Objects.equals(saved.getId(), node.id())) {
                remap.put(node.id(), saved.getId());
            }
            created++;
            current.remove(node.id());
        }

        int softDeleted = 0;
        for (ProjectFile leftover : current.values()) {
            if (Boolean.TRUE.equals(leftover.getIsDeleted())) continue;
            leftover.setIsDeleted(true);
            leftover.setDeletedAt(LocalDateTime.now());
            projectFileRepository.save(leftover);
            softDeleted++;
        }

        return new SyncReport(created, updated, softDeleted);
    }

    /** 同名同类型即认为是同一个节点；否则该 id 是被别的记录占用了。 */
    private boolean sameNode(ProjectFile f, TreeManifest.Node n) {
        return Objects.equals(f.getName(), n.name())
                && Boolean.TRUE.equals(f.getIsFolder()) == n.isFolder();
    }

    /** 返回 true 表示确实改动了字段。 */
    private boolean applyAttributes(ProjectFile f, TreeManifest.Node n, Long parentId) {
        boolean changed = false;
        if (!Objects.equals(f.getParentId(), parentId)) { f.setParentId(parentId); changed = true; }
        if (!Objects.equals(f.getName(), n.name())) { f.setName(n.name()); changed = true; }
        if (!Objects.equals(Boolean.TRUE.equals(f.getIsFolder()), n.isFolder())) {
            f.setIsFolder(n.isFolder()); changed = true;
        }
        if (!Objects.equals(f.getFileType(), n.fileType())) { f.setFileType(n.fileType()); changed = true; }
        if (!Objects.equals(f.getSortOrder(), n.sortOrder())) { f.setSortOrder(n.sortOrder()); changed = true; }
        if (!Objects.equals(f.getFilePath(), n.filePath())) { f.setFilePath(n.filePath()); changed = true; }
        if (Boolean.TRUE.equals(f.getIsDeleted()) != n.isDeleted()) {
            f.setIsDeleted(n.isDeleted());
            f.setDeletedAt(n.isDeleted() ? LocalDateTime.now() : null);
            changed = true;
        }
        if (changed) f.setUpdatedAt(LocalDateTime.now());
        return changed;
    }

    /** 父节点排在子节点之前；环或悬空父节点按原顺序兜底附加。 */
    private List<TreeManifest.Node> topoSort(List<TreeManifest.Node> nodes) {
        Map<Long, TreeManifest.Node> byId = new LinkedHashMap<>();
        for (TreeManifest.Node n : nodes) byId.put(n.id(), n);

        List<TreeManifest.Node> out = new java.util.ArrayList<>();
        java.util.Set<Long> placed = new java.util.HashSet<>();

        java.util.function.Consumer<TreeManifest.Node>[] visit = new java.util.function.Consumer[1];
        java.util.Set<Long> visiting = new java.util.HashSet<>();
        visit[0] = n -> {
            if (n == null || placed.contains(n.id()) || !visiting.add(n.id())) return;
            if (n.parentId() != null) visit[0].accept(byId.get(n.parentId()));
            visiting.remove(n.id());
            if (placed.add(n.id())) out.add(n);
        };
        for (TreeManifest.Node n : nodes) visit[0].accept(n);
        for (TreeManifest.Node n : nodes) if (!placed.contains(n.id())) out.add(n);
        return out;
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=TreeManifestSyncTest
```

预期：6 个测试全 PASS。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/src && git commit -m "feat(version): 文件树清单反向同步回数据库"
```

---

### Task 7: 工作段实体与仓储

**Files:**
- Create: `backend/src/main/java/com/checkba/version/WorkSession.java`
- Create: `backend/src/main/java/com/checkba/version/WorkSessionRepository.java`
- Test: `backend/src/test/java/com/checkba/version/WorkSessionRepositoryTest.java`

**Interfaces:**
- Produces:
  - 实体 `WorkSession`，字段：`id, projectId, branchName, startedAt, endedAt, status, title, userId`
  - `enum WorkSession.Status { ACTIVE, MERGED, DISCARDED }`
  - `Optional<WorkSession> WorkSessionRepository.findFirstByProjectIdAndStatus(Long projectId, WorkSession.Status status)`
  - `List<WorkSession> WorkSessionRepository.findByProjectIdOrderByStartedAtDesc(Long projectId)`

- [ ] **Step 1: 写实体**

`backend/src/main/java/com/checkba/version/WorkSession.java`：

```java
package com.checkba.version;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 一次工作。律师第一次改动任何东西时隐式创建，结束时合并回主线。
 * 分支名对用户不可见——界面上只有「本次工作」。
 */
@Entity
@Table(name = "work_session")
public class WorkSession {

    public enum Status { ACTIVE, MERGED, DISCARDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 128)
    private String branchName;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /** 结束时律师给这段工作起的名字；未命名则由服务端生成。 */
    @Column(length = 256)
    private String title;

    @Column(nullable = false)
    private Long userId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}
```

`backend/src/main/java/com/checkba/version/WorkSessionRepository.java`：

```java
package com.checkba.version;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkSessionRepository extends JpaRepository<WorkSession, Long> {

    Optional<WorkSession> findFirstByProjectIdAndStatus(Long projectId, WorkSession.Status status);

    List<WorkSession> findByProjectIdOrderByStartedAtDesc(Long projectId);
}
```

- [ ] **Step 2: 写测试**

`backend/src/test/java/com/checkba/version/WorkSessionRepositoryTest.java`：

```java
package com.checkba.version;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class WorkSessionRepositoryTest {

    @Autowired
    private WorkSessionRepository repository;

    private WorkSession session(Long projectId, WorkSession.Status status, String branch) {
        WorkSession s = new WorkSession();
        s.setProjectId(projectId);
        s.setBranchName(branch);
        s.setStartedAt(LocalDateTime.now());
        s.setStatus(status);
        s.setUserId(1L);
        return s;
    }

    @Test
    void findsOnlyTheActiveSessionForAProject() {
        repository.save(session(7L, WorkSession.Status.MERGED, "work/1"));
        repository.save(session(7L, WorkSession.Status.ACTIVE, "work/2"));
        repository.save(session(8L, WorkSession.Status.ACTIVE, "work/3"));

        var found = repository.findFirstByProjectIdAndStatus(7L, WorkSession.Status.ACTIVE);

        assertTrue(found.isPresent());
        assertEquals("work/2", found.get().getBranchName());
    }
}
```

- [ ] **Step 3: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=WorkSessionRepositoryTest
```

预期：PASS（实体与仓储同时新建，本任务无失败先行步骤——这是纯声明式代码，没有可先失败的行为）。

- [ ] **Step 4: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/src && git commit -m "feat(version): 工作段实体与仓储"
```

---

### Task 8: 工作段生命周期

**Files:**
- Create: `backend/src/main/java/com/checkba/version/WorkSessionService.java`
- Test: `backend/src/test/java/com/checkba/version/WorkSessionServiceTest.java`

**Interfaces:**
- Consumes: Task 2–4 的 `ProjectRepoService`、Task 5–6 的 `ProjectTreeManifestService`、Task 7 的 `WorkSessionRepository`
- Produces:
  - `void onChangeSignal(long projectId, Long userId, String userName)` — 隐式开始 + 排防抖提交
  - `Optional<WorkSession> activeSession(long projectId)`
  - `String commitNow(long projectId, Long userId, String userName, String message)` — 立即落一笔自动存档，返回 sha 或 null
  - `String endSession(long projectId, Long userId, String userName, String title)` — 返回合并后的 sha
  - `void discardSession(long projectId, Long userId)`
  - `String revertTo(long projectId, String ref, Long userId, String userName)` — 退回，返回新版本 sha

**关键语义（spec 5.2、5.6）**

- 首个变更信号且无 ACTIVE 工作段 → 建分支 `work/{epochMillis}` 并切过去
- 防抖 2 分钟；本任务把定时器抽成可注入的 `TaskScheduler`，测试里用同步实现
- 结束 = 收尾提交 → 切主线 → 合并 → 标 MERGED
- 丢弃 = 切主线 → 删分支（force）→ 同步清单回数据库 → 标 DISCARDED
- **退回不是历史重写**：把目标版本的文件与清单还原到工作区，再提交成一个新版本

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/checkba/version/WorkSessionServiceTest.java`：

```java
package com.checkba.version;

import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkSessionServiceTest {

    private Path root;
    private ProjectRepoService repoSvc;
    private WorkSessionService svc;
    private Map<Long, WorkSession> sessions;
    private long nextSessionId;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(props);
        repoSvc.init(7L, "韩泽伟", "hzw@example.com");

        ProjectFileRepository fileRepo = mock(ProjectFileRepository.class);
        when(fileRepo.findByProjectId(7L)).thenReturn(new ArrayList<>());
        ProjectTreeManifestService manifestSvc =
                new ProjectTreeManifestService(fileRepo, repoSvc, new ObjectMapper());

        sessions = new HashMap<>();
        nextSessionId = 1L;
        WorkSessionRepository sessionRepo = mock(WorkSessionRepository.class);
        when(sessionRepo.save(any(WorkSession.class))).thenAnswer(i -> {
            WorkSession s = i.getArgument(0);
            if (s.getId() == null) s.setId(nextSessionId++);
            sessions.put(s.getId(), s);
            return s;
        });
        when(sessionRepo.findFirstByProjectIdAndStatus(any(), any())).thenAnswer(i ->
                sessions.values().stream()
                        .filter(s -> s.getProjectId().equals(i.getArgument(0))
                                && s.getStatus() == i.getArgument(1))
                        .findFirst());

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();

        svc = new WorkSessionService(repoSvc, manifestSvc, sessionRepo, scheduler);
        svc.setDebounceMillis(60_000); // 测试里不让防抖自己触发，全部手动 commitNow
    }

    @Test
    void firstChangeSignalStartsSessionImplicitly() {
        assertTrue(svc.activeSession(7L).isEmpty());

        svc.onChangeSignal(7L, 1L, "韩泽伟");

        var s = svc.activeSession(7L);
        assertTrue(s.isPresent());
        assertTrue(s.get().getBranchName().startsWith("work/"));
        assertEquals(s.get().getBranchName(), repoSvc.currentBranch(7L),
                "开始工作后应已切到工作分支");
    }

    @Test
    void secondSignalReusesTheSameSession() {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        String branch = svc.activeSession(7L).orElseThrow().getBranchName();
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        assertEquals(branch, svc.activeSession(7L).orElseThrow().getBranchName());
    }

    @Test
    void endSessionMergesBackToMainAndClosesSession() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");

        String sha = svc.endSession(7L, 1L, "韩泽伟", "发客户第一稿");

        assertNotNull(sha);
        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L));
        assertEquals("二稿", Files.readString(root.resolve("projects/7/合同.txt")));
        assertTrue(svc.activeSession(7L).isEmpty());
        assertEquals(WorkSession.Status.MERGED,
                sessions.values().iterator().next().getStatus());
    }

    @Test
    void endSessionGeneratesTitleWhenNotProvided() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");

        svc.endSession(7L, 1L, "韩泽伟", null);

        String title = sessions.values().iterator().next().getTitle();
        assertNotNull(title);
        assertFalse(title.isBlank(), "未命名时服务端必须生成一个标题");
    }

    @Test
    void discardSessionThrowsAwayTheWholeBranch() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "不要的改动");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");

        svc.discardSession(7L, 1L);

        assertEquals(repoSvc.mainBranch(), repoSvc.currentBranch(7L));
        assertEquals("初稿", Files.readString(root.resolve("projects/7/合同.txt")),
                "丢弃后应回到主线内容");
        assertTrue(svc.activeSession(7L).isEmpty());
        assertEquals(WorkSession.Status.DISCARDED,
                sessions.values().iterator().next().getStatus());
    }

    @Test
    void revertCreatesNewVersionRatherThanRewritingHistory() throws Exception {
        String firstSha = repoSvc.log(7L, "HEAD", 1).get(0).sha();

        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        svc.commitNow(7L, 1L, "韩泽伟", "改了");
        svc.endSession(7L, 1L, "韩泽伟", "第一次工作");

        int before = repoSvc.log(7L, "HEAD", 100).size();
        String revertSha = svc.revertTo(7L, firstSha, 1L, "韩泽伟");
        int after = repoSvc.log(7L, "HEAD", 100).size();

        assertNotNull(revertSha);
        assertTrue(after > before, "退回必须新增版本，不得删除历史");
        assertEquals("初稿", Files.readString(root.resolve("projects/7/合同.txt")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=WorkSessionServiceTest
```

预期：编译失败。

- [ ] **Step 3: 实现**

创建 `backend/src/main/java/com/checkba/version/WorkSessionService.java`：

```java
package com.checkba.version;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 一次工作的生命周期（spec 5.2）。
 *
 * 律师第一次动了任何东西 → 隐式开始一段工作（建分支并切过去）；
 * 期间的自动存档攒在这个分支上；结束时整段合并回主线。
 *
 * 本服务的所有公开方法失败时抛 VersionException，调用方必须捕获后降级——
 * 版本记录是保险，不是主流程，绝不允许阻断编辑或保存。
 */
@Service
public class WorkSessionService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WorkSessionService.class);

    private static final DateTimeFormatter TITLE_FMT =
            DateTimeFormatter.ofPattern("M 月 d 日");

    private final ProjectRepoService repoService;
    private final ProjectTreeManifestService manifestService;
    private final WorkSessionRepository sessionRepository;
    private final TaskScheduler taskScheduler;

    /** 防抖静默期。测试里调短或调长以取得确定性。 */
    private long debounceMillis = 2 * 60 * 1000L;

    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<Long, PendingActor> actors = new ConcurrentHashMap<>();

    private record PendingActor(Long userId, String userName) {}

    public WorkSessionService(ProjectRepoService repoService,
                              ProjectTreeManifestService manifestService,
                              WorkSessionRepository sessionRepository,
                              TaskScheduler taskScheduler) {
        this.repoService = repoService;
        this.manifestService = manifestService;
        this.sessionRepository = sessionRepository;
        this.taskScheduler = taskScheduler;
    }

    public void setDebounceMillis(long millis) { this.debounceMillis = millis; }

    public Optional<WorkSession> activeSession(long projectId) {
        return sessionRepository.findFirstByProjectIdAndStatus(
                projectId, WorkSession.Status.ACTIVE);
    }

    /**
     * 收到一个变更信号：文件保存成功、文件树增删改移。
     * 没有进行中的工作段就隐式开一个，然后重排防抖提交。
     */
    public void onChangeSignal(long projectId, Long userId, String userName) {
        if (!repoService.isInitialized(projectId)) return;
        ensureSession(projectId, userId);
        actors.put(projectId, new PendingActor(userId, userName));

        ScheduledFuture<?> prev = pending.remove(projectId);
        if (prev != null) prev.cancel(false);

        ScheduledFuture<?> next = taskScheduler.schedule(
                () -> {
                    pending.remove(projectId);
                    PendingActor a = actors.get(projectId);
                    if (a == null) return;
                    try {
                        commitNow(projectId, a.userId(), a.userName(), null);
                    } catch (Exception e) {
                        log.warn("自动存档失败: project={}", projectId, e);
                    }
                },
                Instant.now().plusMillis(debounceMillis));
        pending.put(projectId, next);
    }

    private WorkSession ensureSession(long projectId, Long userId) {
        Optional<WorkSession> existing = activeSession(projectId);
        if (existing.isPresent()) return existing.get();

        String branch = "work/" + System.currentTimeMillis();
        repoService.createBranch(projectId, branch, "HEAD");
        repoService.checkoutBranch(projectId, branch);

        WorkSession s = new WorkSession();
        s.setProjectId(projectId);
        s.setBranchName(branch);
        s.setStartedAt(LocalDateTime.now());
        s.setStatus(WorkSession.Status.ACTIVE);
        s.setUserId(userId);
        log.info("开始一段工作: project={}, branch={}", projectId, branch);
        return sessionRepository.save(s);
    }

    /** 立即落一笔自动存档。无变更时返回 null。 */
    public String commitNow(long projectId, Long userId, String userName, String message) {
        if (!repoService.isInitialized(projectId)) return null;
        ensureSession(projectId, userId);
        manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
        String msg = message != null ? message : describePendingChanges(projectId);
        return repoService.commitAll(projectId, msg, "auto", null, userName, email(userName));
    }

    /**
     * 结束本次工作：收尾提交 → 切回主线 → 合并 → 关闭工作段。
     * 单人场景下主线在工作期间不会变，合并总是快进。
     */
    public String endSession(long projectId, Long userId, String userName, String title) {
        WorkSession s = activeSession(projectId)
                .orElseThrow(() -> new VersionException("当前没有进行中的工作"));

        cancelPending(projectId);
        commitNow(projectId, userId, userName, null);

        String finalTitle = (title == null || title.isBlank())
                ? defaultTitle(s.getStartedAt()) : title.trim();

        repoService.checkoutBranch(projectId, repoService.mainBranch());
        MergeOutcome outcome = repoService.merge(
                projectId, s.getBranchName(), finalTitle, userName, email(userName));

        if (!outcome.success()) {
            // 合并没成，把用户放回他的工作段，改动一个都不能丢
            repoService.checkoutBranch(projectId, s.getBranchName());
            throw new VersionException("本次工作还没能收尾，你的改动都还在");
        }

        s.setStatus(WorkSession.Status.MERGED);
        s.setEndedAt(LocalDateTime.now());
        s.setTitle(finalTitle);
        sessionRepository.save(s);
        log.info("结束一段工作: project={}, branch={}, title={}",
                projectId, s.getBranchName(), finalTitle);
        return outcome.mergeSha();
    }

    /** 丢弃整段工作：删分支，工作区回到主线状态，数据库文件树跟着回去。 */
    public void discardSession(long projectId, Long userId) {
        WorkSession s = activeSession(projectId)
                .orElseThrow(() -> new VersionException("当前没有进行中的工作"));

        cancelPending(projectId);
        repoService.checkoutBranch(projectId, repoService.mainBranch());
        repoService.deleteBranch(projectId, s.getBranchName(), true);
        syncManifestFromRef(projectId, "HEAD");

        s.setStatus(WorkSession.Status.DISCARDED);
        s.setEndedAt(LocalDateTime.now());
        sessionRepository.save(s);
        log.info("丢弃一段工作: project={}, branch={}", projectId, s.getBranchName());
    }

    /**
     * 退回到某一版。**不是历史重写**：把目标版本的内容还原到工作区，
     * 再作为一个新版本提交。时间线只会往前长。
     */
    public String revertTo(long projectId, String ref, Long userId, String userName) {
        // 先给当前状态留一笔，保证「退回」这个动作本身可撤销
        commitNow(projectId, userId, userName, null);

        restoreWorkTreeFrom(projectId, ref);
        syncManifestFromRef(projectId, ref);
        manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));

        String sha = repoService.commitAll(projectId,
                "退回到早先的版本", "session", null, userName, email(userName));
        log.info("退回: project={}, ref={}, newSha={}", projectId, ref, sha);
        return sha;
    }

    // ---- helpers ----------------------------------------------------------

    private void cancelPending(long projectId) {
        ScheduledFuture<?> f = pending.remove(projectId);
        if (f != null) f.cancel(false);
        actors.remove(projectId);
    }

    /** 把目标版本的所有文件覆盖回工作区；目标版本没有的文件删掉。 */
    private void restoreWorkTreeFrom(long projectId, String ref) {
        Path work = repoService.workTree(projectId);
        try {
            List<FileChange> changes = repoService.diffNameStatus(projectId, ref, "HEAD");
            for (FileChange c : changes) {
                Path target = work.resolve(c.path());
                byte[] bytes = repoService.readBlobAtCommit(projectId, ref, c.path());
                if (bytes == null) {
                    Files.deleteIfExists(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                }
            }
        } catch (Exception e) {
            throw new VersionException("还原文件失败: project=" + projectId + " ref=" + ref, e);
        }
    }

    private void syncManifestFromRef(long projectId, String ref) {
        TreeManifest m = manifestService.readAtRef(projectId, ref);
        if (m != null) manifestService.applyToDatabase(projectId, m);
    }

    private String describePendingChanges(long projectId) {
        return "修改了项目文件";
    }

    private String defaultTitle(LocalDateTime startedAt) {
        LocalDateTime t = startedAt != null ? startedAt : LocalDateTime.now();
        String half = t.getHour() < 12 ? "上午" : (t.getHour() < 18 ? "下午" : "晚上");
        return t.format(TITLE_FMT) + half + "的工作";
    }

    private String email(String userName) {
        return (userName == null ? "user" : userName) + "@aiworkdeck.local";
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=WorkSessionServiceTest
```

预期：6 个测试全 PASS。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/src && git commit -m "feat(version): 工作段生命周期——隐式开始、结束合并、丢弃、退回"
```

---

### Task 9: 提交消息与变更文件的中文描述

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`
- Test: `backend/src/test/java/com/checkba/version/ChangeDescriptionTest.java`

**Interfaces:**
- Consumes: Task 3 的 `ProjectRepoService`
- Produces:
  - `static String WorkSessionService.describeChanges(List<FileChange> changes)`（包级可见，供测试复用）
  - `List<FileChange> ProjectRepoService.pendingChanges(long projectId)` — 工作区相对 HEAD 的未提交变更（本任务新增，Task 11 的 `/status` 接口也用它）

Task 8 里的 `describePendingChanges` 是占位实现（固定返回「修改了项目文件」）。本任务把它换成 spec 5.3 要求的「修改了《XX》等 N 份文件」。

描述必须在**提交之前**算出来——提交之后再改写消息就是重写历史，违反全局约束。而 `diffNameStatus` 比较的是两个已提交版本，拿不到「尚未提交的变更」。所以本任务给 `ProjectRepoService` 补一个只读的 `pendingChanges`，它 add 到暂存区后读 `status`，不产生提交。

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/checkba/version/ChangeDescriptionTest.java`：

```java
package com.checkba.version;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChangeDescriptionTest {

    @Test
    void singleFileNamesIt() {
        String s = WorkSessionService.describeChanges(List.of(
                new FileChange("重要协议/股权转让协议.docx", FileChange.Type.MODIFY)));
        assertEquals("修改了《股权转让协议》", s);
    }

    @Test
    void multipleFilesNameFirstAndCount() {
        String s = WorkSessionService.describeChanges(List.of(
                new FileChange("重要协议/股权转让协议.docx", FileChange.Type.MODIFY),
                new FileChange("法律意见书.docx", FileChange.Type.MODIFY),
                new FileChange("附件.docx", FileChange.Type.ADD)));
        assertEquals("修改了《股权转让协议》等 3 份文件", s);
    }

    @Test
    void manifestIsNotUserVisible() {
        String s = WorkSessionService.describeChanges(List.of(
                new FileChange(".awd/tree.json", FileChange.Type.MODIFY),
                new FileChange("合同.docx", FileChange.Type.MODIFY)));
        assertEquals("修改了《合同》", s, "清单文件不得出现在律师看到的描述里");
    }

    @Test
    void onlyManifestChangedFallsBackToGenericWording() {
        String s = WorkSessionService.describeChanges(List.of(
                new FileChange(".awd/tree.json", FileChange.Type.MODIFY)));
        assertEquals("整理了文件结构", s);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ChangeDescriptionTest
```

预期：编译失败，`describeChanges` 不存在。

- [ ] **Step 3: 给 `ProjectRepoService` 补 `pendingChanges`**

```java
    /** 工作区相对 HEAD 的未提交变更。只读——add 到暂存区但不提交。 */
    public List<FileChange> pendingChanges(long projectId) {
        List<FileChange> out = new ArrayList<>();
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            Status st = git.status().call();
            for (String p : st.getAdded()) out.add(new FileChange(p, FileChange.Type.ADD));
            for (String p : st.getChanged()) out.add(new FileChange(p, FileChange.Type.MODIFY));
            for (String p : st.getRemoved()) out.add(new FileChange(p, FileChange.Type.DELETE));
            return out;
        } catch (Exception e) {
            throw new VersionException("读取未提交变更失败: project=" + projectId, e);
        }
    }
```

- [ ] **Step 4: 实现描述生成**

在 `WorkSessionService` 中，把 `describePendingChanges` 替换为：

```java
    /**
     * 生成律师在时间线上看到的那句话。清单文件是内部机制，不出现在描述里。
     */
    static String describeChanges(List<FileChange> changes) {
        List<String> names = changes.stream()
                .map(FileChange::path)
                .filter(p -> !p.startsWith(".awd/"))
                .map(WorkSessionService::displayName)
                .toList();
        if (names.isEmpty()) return "整理了文件结构";
        if (names.size() == 1) return "修改了《" + names.get(0) + "》";
        return "修改了《" + names.get(0) + "》等 " + names.size() + " 份文件";
    }

    /** 取文件名并去掉扩展名——律师习惯说《股权转让协议》，不说 .docx。 */
    private static String displayName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String describePendingChanges(long projectId) {
        try {
            return describeChanges(repoService.pendingChanges(projectId));
        } catch (Exception e) {
            log.warn("生成变更描述失败: project={}", projectId, e);
            return "修改了项目文件";
        }
    }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ChangeDescriptionTest,WorkSessionServiceTest
```

预期：两个测试类全 PASS。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/src && git commit -m "feat(version): 变更文件的中文描述"
```

---

### Task 10: 崩溃恢复与每日 GC

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`
- Create: `backend/src/main/java/com/checkba/version/RepoMaintenanceJob.java`
- Test: `backend/src/test/java/com/checkba/version/RepoMaintenanceTest.java`

**Interfaces:**
- Produces:
  - `Optional<WorkSession> WorkSessionService.pendingRecovery(long projectId)` — 等价于 `activeSession`，但语义是「上次没正常结束」
  - `void WorkSessionService.resumeSession(long projectId)` — 切回该工作段分支继续
  - `void ProjectRepoService.gc(long projectId)`
  - `void RepoMaintenanceJob.runDaily()`

**这一任务同时是「历史永不重写」的回归护栏**（spec 5.5）：GC 之后所有可达历史必须完好。

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/checkba/version/RepoMaintenanceTest.java`：

```java
package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepoMaintenanceTest {

    @Test
    void gcPreservesEveryReachableVersion(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectRepoService s = new ProjectRepoService(props);
        s.init(7L, "韩泽伟", "hzw@example.com");

        for (int i = 1; i <= 5; i++) {
            Files.writeString(root.resolve("projects/7/合同.txt"), "第 " + i + " 稿");
            s.commitAll(7L, "改了 " + i, "auto", null, "韩泽伟", "hzw@example.com");
        }

        List<VersionEntry> before = s.log(7L, "HEAD", 100);
        assertEquals(6, before.size());

        s.gc(7L);

        List<VersionEntry> after = s.log(7L, "HEAD", 100);
        assertEquals(before.size(), after.size(), "GC 不得删除任何可达版本");
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).sha(), after.get(i).sha(),
                    "GC 前后的版本序列必须逐条相同——历史永不重写");
        }
        // 历史内容仍可取回
        assertEquals("初稿", new String(
                s.readBlobAtCommit(7L, before.get(before.size() - 1).sha(), "合同.txt")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=RepoMaintenanceTest
```

预期：编译失败，`gc` 不存在。

- [ ] **Step 3: 实现**

在 `ProjectRepoService` 追加：

```java
    /**
     * 重打包并清理不可达对象。
     * 只动不可达对象（失败的合并、已丢弃工作段的悬空提交）——
     * 可达历史一个不动，这是「历史永不重写」的一部分。
     */
    public void gc(long projectId) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.gc().call();
        } catch (Exception e) {
            log.warn("仓库维护失败: project={}", projectId, e);
        }
    }
```

在 `WorkSessionService` 追加：

```java
    /** 上次没正常结束的工作段（崩溃或强杀留下的）。 */
    public Optional<WorkSession> pendingRecovery(long projectId) {
        return activeSession(projectId);
    }

    /** 继续上次没结束的工作：切回该分支。 */
    public void resumeSession(long projectId) {
        WorkSession s = activeSession(projectId)
                .orElseThrow(() -> new VersionException("当前没有未结束的工作"));
        repoService.checkoutBranch(projectId, s.getBranchName());
    }
```

创建 `backend/src/main/java/com/checkba/version/RepoMaintenanceJob.java`：

```java
package com.checkba.version;

import com.checkba.repository.ProjectRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日仓库维护。只做 GC（重打包 + 清理不可达对象），
 * 不做任何历史清理——spec 5.5。
 */
@Component
public class RepoMaintenanceJob {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RepoMaintenanceJob.class);

    private final ProjectRepository projectRepository;
    private final ProjectRepoService repoService;

    public RepoMaintenanceJob(ProjectRepository projectRepository,
                              ProjectRepoService repoService) {
        this.projectRepository = projectRepository;
        this.repoService = repoService;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void runDaily() {
        projectRepository.findAll().forEach(p -> {
            if (p.getId() == null || !repoService.isInitialized(p.getId())) return;
            try {
                repoService.gc(p.getId());
            } catch (Exception e) {
                log.warn("仓库维护失败: project={}", p.getId(), e);
            }
        });
    }
}
```

确认 `@EnableScheduling` 已在主应用类上；若无，加到 `CheckbaApplication`。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=RepoMaintenanceTest
```

预期：PASS。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/src && git commit -m "feat(version): 崩溃恢复与每日 GC，含历史不可重写护栏"
```

---

### Task 11: REST 接口与权限

**Files:**
- Create: `backend/src/main/java/com/checkba/version/VersionController.java`
- Test: `backend/src/test/java/com/checkba/version/VersionControllerAuthTest.java`

**Interfaces:**
- Consumes: Task 8–10 的 `WorkSessionService`、Task 2–4 的 `ProjectRepoService`
- Produces（全部挂在 `/api/projects/{projectId}/version`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/status` | `{enabled, working, sessionTitle, changedCount, pendingRecovery}` |
| POST | `/enable` | 开启版本记录 |
| GET | `/timeline?limit=50` | 版本列表 |
| GET | `/versions/{sha}/changes` | 该版改了哪些文件 |
| POST | `/session/end` | body `{title}`，结束本次工作 |
| POST | `/session/discard` | 丢弃本次工作 |
| POST | `/session/resume` | 继续未结束的工作 |
| POST | `/revert` | body `{ref}`，退回 |

权限：全部走既有的项目鉴权；**CLIENT 角色一律 403**（spec 5.10）。

**既有鉴权约定**（已从 `ProjectFileControllerIdorTest` 与 `ProjectFileController` 读出，照此实现，不要自创）：

- 会话取用户：`AuthController.getUserIdFromSession(sessionId)`（静态方法），入参来自请求头 `X-Session-Id`
- 读权限：`projectMemberService.hasReadPermission(userId, projectId)`
- CLIENT 判定：`projectMemberService.isClient(userId, projectId)`
- 控制器测试用纯 Mockito（`@ExtendWith(MockitoExtension.class)` + `@InjectMocks` + `MockedStatic<AuthController>`），**不是** `@SpringBootTest` + MockMvc

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/checkba/version/VersionControllerAuthTest.java`：

```java
package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 锁定版本记录接口的权限：CLIENT 角色（客户）不得看到版本历史——
 * 里面有律师的内部草稿。未登录同样拒绝。
 */
@ExtendWith(MockitoExtension.class)
class VersionControllerAuthTest {

    @Mock
    private ProjectRepoService repoService;
    @Mock
    private WorkSessionService sessionService;
    @Mock
    private ProjectMemberService projectMemberService;

    @InjectMocks
    private VersionController controller;

    @Test
    void clientRoleCannotSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(1L, 7L)).thenReturn(true);
            when(projectMemberService.isClient(1L, 7L)).thenReturn(true);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.timeline(7L, 50, "sess"));
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void nonMemberCannotSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(1L, 7L)).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.timeline(7L, 50, "sess"));
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void anonymousCannotSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.timeline(7L, 50, null));
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void memberCanSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(1L, 7L)).thenReturn(true);
            when(projectMemberService.isClient(1L, 7L)).thenReturn(false);
            when(repoService.log(7L, "HEAD", 50)).thenReturn(java.util.List.of());

            controller.timeline(7L, 50, "sess");

            verify(repoService).log(7L, "HEAD", 50);
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=VersionControllerAuthTest
```

预期：编译失败，`VersionController` 不存在。

- [ ] **Step 3: 实现控制器**

创建 `backend/src/main/java/com/checkba/version/VersionController.java`：

```java
package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本记录接口。术语对齐 spec 第四节——返回给前端的一切文案都不得出现 Git 词汇。
 *
 * 权限：项目成员可见；CLIENT（客户）一律拒绝——版本历史里有律师的内部草稿。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/version")
public class VersionController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(VersionController.class);

    private final ProjectRepoService repoService;
    private final WorkSessionService sessionService;
    private final ProjectMemberService projectMemberService;
    private final UserService userService;

    public VersionController(ProjectRepoService repoService,
                             WorkSessionService sessionService,
                             ProjectMemberService projectMemberService,
                             UserService userService) {
        this.repoService = repoService;
        this.sessionService = sessionService;
        this.projectMemberService = projectMemberService;
        this.userService = userService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        Map<String, Object> data = new HashMap<>();
        boolean enabled = repoService.isInitialized(projectId);
        data.put("enabled", enabled);
        if (enabled) {
            var active = sessionService.activeSession(projectId);
            data.put("working", active.isPresent());
            data.put("sessionTitle", active.map(WorkSession::getTitle).orElse(null));
            data.put("changedCount", repoService.pendingChanges(projectId).stream()
                    .filter(c -> !c.path().startsWith(".awd/")).count());
            data.put("pendingRecovery", sessionService.pendingRecovery(projectId).isPresent());
        } else {
            data.put("working", false);
            data.put("changedCount", 0);
            data.put("pendingRecovery", false);
        }
        return ok(data);
    }

    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> enable(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        repoService.init(projectId, userName(userId), email(userId));
        return ok(Map.of("enabled", true));
    }

    @GetMapping("/timeline")
    public ResponseEntity<Map<String, Object>> timeline(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        List<VersionEntry> entries = repoService.log(projectId, "HEAD", limit);
        return ok(Map.of("versions", entries));
    }

    @GetMapping("/versions/{sha}/changes")
    public ResponseEntity<Map<String, Object>> changes(
            @PathVariable Long projectId,
            @PathVariable String sha,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        List<FileChange> changes = repoService.diffNameStatus(projectId, sha + "^", sha)
                .stream().filter(c -> !c.path().startsWith(".awd/")).toList();
        return ok(Map.of("changes", changes));
    }

    @PostMapping("/session/end")
    public ResponseEntity<Map<String, Object>> endSession(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        String title = body == null ? null : body.get("title");
        String sha = sessionService.endSession(projectId, userId, userName(userId), title);
        return ok(Map.of("sha", sha == null ? "" : sha));
    }

    @PostMapping("/session/discard")
    public ResponseEntity<Map<String, Object>> discardSession(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        sessionService.discardSession(projectId, userId);
        return ok(Map.of("discarded", true));
    }

    @PostMapping("/session/resume")
    public ResponseEntity<Map<String, Object>> resumeSession(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        sessionService.resumeSession(projectId);
        return ok(Map.of("resumed", true));
    }

    @PostMapping("/revert")
    public ResponseEntity<Map<String, Object>> revert(
            @PathVariable Long projectId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        String sha = sessionService.revertTo(
                projectId, body.get("ref"), userId, userName(userId));
        return ok(Map.of("sha", sha == null ? "" : sha));
    }

    @ExceptionHandler(VersionException.class)
    public ResponseEntity<Map<String, Object>> onVersionError(VersionException e) {
        log.warn("版本记录操作失败", e);
        return ResponseEntity.status(500).body(Map.of("code", -1, "message", e.getMessage()));
    }

    /** 校验并返回当前用户 id。非成员或 CLIENT 一律拒绝。 */
    private Long requireMember(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (!projectMemberService.hasReadPermission(userId, projectId)) {
            throw new IllegalArgumentException("无权访问该项目");
        }
        if (projectMemberService.isClient(userId, projectId)) {
            throw new IllegalArgumentException("无权访问该项目");
        }
        return userId;
    }

    private String userName(Long userId) {
        try {
            var u = userService.getUserById(userId);
            if (u != null && u.getUsername() != null) return u.getUsername();
        } catch (Exception e) {
            log.warn("取用户名失败: userId={}", userId, e);
        }
        return "用户";
    }

    private String email(Long userId) {
        return "user-" + userId + "@aiworkdeck.local";
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }
}
```

若 `UserService` 没有 `getUserById(Long)`，改用它实际提供的按 id 取用户的方法；`ProjectFileController` 里有现成用法可参照。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=VersionControllerAuthTest
```

预期：4 个测试全 PASS。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/src && git commit -m "feat(version): REST 接口与权限，CLIENT 角色不可见"
```

---

### Task 12: 挂接变更信号

**Files:**
- Modify: `backend/src/main/java/com/checkba/service/ProjectFileService.java`
- Modify: `backend/src/main/java/com/checkba/controller/FileController.java`
- Test: `backend/src/test/java/com/checkba/version/ChangeSignalWiringTest.java`

**Interfaces:**
- Consumes: Task 8 的 `WorkSessionService.onChangeSignal(long, Long, String)`

**注意**：`ProjectFileService` 已经很长（1150 行）。本任务只加一个可选依赖和若干一行调用，**不重构该文件**。

信号点：`createFile`、`createFolder`、`rename`、`delete`、`move`、`batchDelete`、`batchMove`、`batchCopy`、`restore`，以及 `FileController` 的上传成功分支。

为避免循环依赖（`WorkSessionService` → `ProjectTreeManifestService` → `ProjectFileRepository`，不经过 `ProjectFileService`，故无环），直接构造器注入即可。

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/checkba/version/ChangeSignalWiringTest.java`：

```java
package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class ChangeSignalWiringTest {

    @Autowired
    private ProjectFileService projectFileService;

    @MockBean
    private WorkSessionService workSessionService;

    @Test
    void creatingAFolderEmitsAChangeSignal() {
        ProjectFile folder = projectFileService.createFolder(7L, null, "新建文件夹", 1L);
        verify(workSessionService, atLeastOnce())
                .onChangeSignal(eq(7L), any(), any());
        projectFileService.permDelete(folder.getId(), 1L);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ChangeSignalWiringTest
```

预期：FAIL，`onChangeSignal` 从未被调用。

- [ ] **Step 3: 实现**

在 `ProjectFileService` 的构造器参数末尾追加 `WorkSessionService workSessionService` 并存字段。然后在上列每个变更方法的 `return` 之前插入一行：

```java
        signalChange(projectId, userId);
```

（`rename`/`delete`/`move` 等只有 `fileId` 的方法，用 `file.getProjectId()`。）

并在类末尾加：

```java
    /**
     * 通知版本记录：项目文件发生了变更。
     * 版本记录是保险不是主流程——任何异常只记日志，绝不阻断文件操作。
     */
    private void signalChange(Long projectId, Long userId) {
        if (projectId == null) return;
        try {
            workSessionService.onChangeSignal(projectId, userId, resolveUserName(userId));
        } catch (Exception e) {
            log.warn("发送版本变更信号失败: project={}", projectId, e);
        }
    }

    private String resolveUserName(Long userId) {
        return userId == null ? "用户" : ("user-" + userId);
    }
```

`resolveUserName` 暂用占位实现；接入真实用户名放在 Task 13 与前端一起做（控制器层已有会话，能拿到真名）。

在 `FileController` 的上传成功分支（保存字节成功、返回 200 之前）同样加一行调用，用户名从会话取真实值。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ChangeSignalWiringTest
```

预期：PASS。

- [ ] **Step 5: 跑全量后端测试，确认没打破既有用例**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
```

预期：全绿。`ProjectFileService` 构造器变了，若有测试直接 new 它，本步会暴露，逐个补参数。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add backend/src && git commit -m "feat(version): 文件与文件树变更挂接版本记录信号"
```

---

### Task 13: 前端接口封装与 rail 入口

**Files:**
- Modify: `frontend/src/services/api.js`
- Modify: `frontend/src/config/leftSidebarPlugins.js`
- Create: `frontend/src/components/version/VersionPanel.vue`
- Modify: `frontend/src/pages/project-overview/project-overview.vue`

**Interfaces:**
- Consumes: Task 11 的 REST 接口
- Produces（`frontend/src/services/api.js` 具名导出）：`getVersionStatus`、`enableVersionControl`、`getVersionTimeline`、`getVersionChanges`、`endWorkSession`、`discardWorkSession`、`resumeWorkSession`、`revertToVersion`

**既有约定**：项目里所有网络请求走 `frontend/src/services/api.js` 的具名导出函数，内部统一用 `request({url, method, data})`（`api.js:143`），鉴权头由该封装统一注入。文件顶部明文规定「组件内禁止直接写 URL」——**不要新建 `src/api/` 目录**。

- [ ] **Step 1: 追加接口函数**

在 `frontend/src/services/api.js` 末尾追加（写法与相邻的 `getPlugins` / `setPluginEnabled` 一致）：

```js
// ==================== 版本记录 ====================
// 术语对齐 spec 第四节：对外只说「版本 / 本次工作」，不说 commit / branch。

export function getVersionStatus(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/status`,
    method: 'GET'
  });
}

export function enableVersionControl(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/enable`,
    method: 'POST'
  });
}

export function getVersionTimeline(projectId, limit = 50) {
  return request({
    url: `/api/projects/${projectId}/version/timeline?limit=${limit}`,
    method: 'GET'
  });
}

export function getVersionChanges(projectId, sha) {
  return request({
    url: `/api/projects/${projectId}/version/versions/${encodeURIComponent(sha)}/changes`,
    method: 'GET'
  });
}

export function endWorkSession(projectId, title) {
  return request({
    url: `/api/projects/${projectId}/version/session/end`,
    method: 'POST',
    data: { title: title || '' }
  });
}

export function discardWorkSession(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/session/discard`,
    method: 'POST'
  });
}

export function resumeWorkSession(projectId) {
  return request({
    url: `/api/projects/${projectId}/version/session/resume`,
    method: 'POST'
  });
}

export function revertToVersion(projectId, ref) {
  return request({
    url: `/api/projects/${projectId}/version/revert`,
    method: 'POST',
    data: { ref }
  });
}
```

- [ ] **Step 2: 注册 rail 入口**

在 `frontend/src/config/leftSidebarPlugins.js` 的固定入口数组里追加一项（字段名照抄相邻项）：

```js
  {
    key: 'version',
    name: '版本',
    icon: 'clock',
  },
```

`getPluginsForUser(role)` 里让 CLIENT 角色看不到 `version`——照抄它对尽调文件的既有过滤写法。

- [ ] **Step 3: 写面板容器**

创建 `frontend/src/components/version/VersionPanel.vue`：

```vue
<template>
  <view class="version-panel">
    <view v-if="loading" class="version-empty">正在读取版本记录…</view>

    <view v-else-if="!enabled" class="version-intro">
      <view class="version-intro-title">本项目还没有开启版本记录</view>
      <view class="version-intro-desc">
        开启后，你每次改动都会自动留底，随时可以看到项目改了什么、退回到以前的样子。
      </view>
      <view class="awd-btn awd-btn-primary" @tap="enable">开启版本记录</view>
    </view>

    <template v-else>
      <WorkSessionBar
        :working="working"
        :changed-count="changedCount"
        @ended="refresh"
        @discarded="refresh"
      />
      <VersionTimeline :project-id="projectId" :key="timelineKey" />
    </template>
  </view>
</template>

<script>
import { getVersionStatus, enableVersionControl } from '@/services/api.js'
import WorkSessionBar from './WorkSessionBar.vue'
import VersionTimeline from './VersionTimeline.vue'

export default {
  name: 'VersionPanel',
  components: { WorkSessionBar, VersionTimeline },
  props: {
    projectId: { type: [String, Number], required: true },
  },
  data() {
    return {
      loading: true,
      enabled: false,
      working: false,
      changedCount: 0,
      timelineKey: 0,
    }
  },
  mounted() {
    this.refresh()
  },
  methods: {
    async refresh() {
      this.loading = true
      try {
        const res = await getVersionStatus(this.projectId)
        const d = (res && res.data) || {}
        this.enabled = !!d.enabled
        this.working = !!d.working
        this.changedCount = d.changedCount || 0
        this.timelineKey += 1
      } catch (e) {
        console.warn('[Version] 读取状态失败', e)
      } finally {
        this.loading = false
      }
    },
    async enable() {
      try {
        await enableVersionControl(this.projectId)
        await this.refresh()
      } catch (e) {
        uni.showToast({ title: '开启失败，请稍后重试', icon: 'none' })
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.version-panel { display: flex; flex-direction: column; height: 100%; }
.version-empty { padding: 24rpx; color: #888; font-size: 26rpx; }
.version-intro { padding: 32rpx 24rpx; }
.version-intro-title { font-size: 30rpx; font-weight: 600; margin-bottom: 12rpx; }
.version-intro-desc { font-size: 26rpx; color: #666; line-height: 1.6; margin-bottom: 24rpx; }

/* awd-* 没有集中定义，各组件 scoped 内各自定义 */
.awd-btn {
  display: inline-block; padding: 14rpx 28rpx; border-radius: 8rpx;
  font-size: 26rpx; text-align: center;
}
.awd-btn-primary { background: #12344D; color: #fff; }
</style>
```

- [ ] **Step 4: 接进左栏**

在 `frontend/src/pages/project-overview/project-overview.vue`：

1. `components` 里注册 `VersionPanel`
2. `sidebar-content`（约 :515-556，按 `leftPaneKey` 分支处）追加一支：

```vue
          <VersionPanel v-else-if="leftPaneKey === 'version'" :project-id="projectId" />
```

3. `leftPaneTitle` 的映射里补 `version: '版本'`

- [ ] **Step 5: 跑死绑定护栏**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/frontend" && npm run check:emits
```

预期：通过。此时 `WorkSessionBar` 与 `VersionTimeline` 尚不存在，Vue 编译会报错——**这是预期的**，下一任务补齐后再整体验证。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add frontend/src && git commit -m "feat(version): 前端接口封装、rail 入口与面板容器"
```

---

### Task 14: 工作状态条与时间线

**Files:**
- Create: `frontend/src/components/version/WorkSessionBar.vue`
- Create: `frontend/src/components/version/VersionTimeline.vue`
- Create: `frontend/src/components/version/VersionNodeDetail.vue`

**Interfaces:**
- Consumes: Task 13 在 `frontend/src/services/api.js` 追加的具名导出
- Produces:
  - `WorkSessionBar` emits `ended`、`discarded`
  - `VersionTimeline` props `projectId`；emits `reverted`

- [ ] **Step 1: 工作状态条**

创建 `frontend/src/components/version/WorkSessionBar.vue`：

```vue
<template>
  <view class="session-bar">
    <template v-if="working">
      <view class="session-dot" />
      <text class="session-text">工作中{{ changedCount ? `（已改 ${changedCount} 份文件）` : '' }}</text>
      <view class="awd-btn awd-btn-primary session-btn" @tap="openNaming">结束本次工作</view>
      <view class="awd-btn awd-btn-danger session-btn" @tap="confirmDiscard">丢弃</view>
    </template>
    <template v-else>
      <text class="session-text session-idle">主线</text>
    </template>

    <view v-if="naming" class="awd-mask" @tap.self="naming = false">
      <view class="awd-dialog">
        <view class="awd-header"><text class="awd-title">给这次工作起个名字</text></view>
        <view class="awd-body">
          <input
            v-model="title"
            class="awd-input"
            placeholder="例如：发客户第一稿（不填也可以）"
          />
        </view>
        <view class="awd-footer">
          <view class="awd-btn awd-btn-secondary" @tap="naming = false">取消</view>
          <view class="awd-btn awd-btn-primary" @tap="end">完成</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { endWorkSession, discardWorkSession } from '@/services/api.js'

export default {
  name: 'WorkSessionBar',
  props: {
    working: { type: Boolean, default: false },
    changedCount: { type: Number, default: 0 },
  },
  emits: ['ended', 'discarded'],
  data() {
    return { naming: false, title: '', busy: false }
  },
  inject: ['projectId'],
  methods: {
    openNaming() {
      this.title = ''
      this.naming = true
    },
    async end() {
      if (this.busy) return
      this.busy = true
      try {
        await endWorkSession(this.projectId, this.title)
        this.naming = false
        this.$emit('ended')
      } catch (e) {
        uni.showToast({ title: '本次工作还没能收尾，你的改动都还在', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    confirmDiscard() {
      uni.showModal({
        title: '丢弃本次工作',
        content: '本次工作的所有改动都会被撤销，回到开始工作之前的样子。确定吗？',
        success: async (r) => {
          if (!r.confirm) return
          try {
            await discardWorkSession(this.projectId)
            this.$emit('discarded')
          } catch (e) {
            uni.showToast({ title: '丢弃失败，请稍后重试', icon: 'none' })
          }
        },
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.session-bar {
  display: flex; align-items: center; gap: 12rpx;
  padding: 16rpx 20rpx; border-bottom: 1px solid #eee;
}
.session-dot {
  width: 14rpx; height: 14rpx; border-radius: 50%; background: #C8A45D;
}
.session-text { font-size: 26rpx; color: #333; flex: 1; }
.session-idle { color: #999; }
.session-btn { flex-shrink: 0; }

.awd-btn { padding: 10rpx 20rpx; border-radius: 6rpx; font-size: 24rpx; }
.awd-btn-primary { background: #12344D; color: #fff; }
.awd-btn-secondary { background: #f0f0f0; color: #333; }
.awd-btn-danger { background: #fff; color: #b23; border: 1px solid #e0c0c0; }

.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.awd-dialog { width: 600rpx; background: #fff; border-radius: 12rpx; overflow: hidden; }
.awd-header { padding: 24rpx; border-bottom: 1px solid #eee; }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; }
.awd-input {
  width: 100%; padding: 16rpx; border: 1px solid #ddd;
  border-radius: 8rpx; font-size: 26rpx;
}
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid #eee;
}
</style>
```

`inject: ['projectId']` 要求父级 `provide`。在 `VersionPanel.vue` 的 `<script>` 里加：

```js
  provide() {
    return { projectId: this.projectId }
  },
```

- [ ] **Step 2: 时间线**

创建 `frontend/src/components/version/VersionTimeline.vue`：

```vue
<template>
  <scroll-view class="timeline" scroll-y>
    <view v-if="!versions.length" class="timeline-empty">还没有任何版本记录</view>

    <view
      v-for="group in grouped"
      :key="group.head.sha"
      class="timeline-node"
      :class="{ 'is-session': group.head.kind === 'session' }"
    >
      <view class="node-line" />
      <view class="node-main" @tap="select(group.head)">
        <view class="node-title">{{ titleOf(group.head) }}</view>
        <view class="node-meta">{{ group.head.authorName }} · {{ timeOf(group.head) }}</view>
      </view>

      <view
        v-if="group.autos.length"
        class="node-autos-toggle"
        @tap="toggle(group.head.sha)"
      >
        {{ expanded[group.head.sha] ? '收起' : `这段工作里还有 ${group.autos.length} 次自动存档` }}
      </view>
      <view v-if="expanded[group.head.sha]" class="node-autos">
        <view
          v-for="a in group.autos"
          :key="a.sha"
          class="node-auto"
          @tap="select(a)"
        >
          <text class="auto-time">{{ timeOf(a) }}</text>
          <text class="auto-msg">{{ a.message }}</text>
        </view>
      </view>
    </view>

    <VersionNodeDetail
      v-if="selected"
      :project-id="projectId"
      :version="selected"
      @close="selected = null"
      @reverted="onReverted"
    />
  </scroll-view>
</template>

<script>
import { getVersionTimeline } from '@/services/api.js'
import VersionNodeDetail from './VersionNodeDetail.vue'

export default {
  name: 'VersionTimeline',
  components: { VersionNodeDetail },
  props: {
    projectId: { type: [String, Number], required: true },
  },
  emits: ['reverted'],
  data() {
    return { versions: [], expanded: {}, selected: null }
  },
  computed: {
    // 工作段是主线节点，自动存档折进它下面。
    grouped() {
      const out = []
      let current = null
      for (const v of this.versions) {
        if (v.kind === 'session' || !current) {
          current = { head: v, autos: [] }
          out.push(current)
        } else {
          current.autos.push(v)
        }
      }
      return out
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    async load() {
      try {
        const res = await getVersionTimeline(this.projectId)
        this.versions = ((res && res.data && res.data.versions) || [])
      } catch (e) {
        console.warn('[Version] 读取时间线失败', e)
      }
    },
    titleOf(v) {
      return v.note || v.message
    },
    timeOf(v) {
      const d = new Date(v.when)
      const pad = (n) => String(n).padStart(2, '0')
      return `${d.getMonth() + 1} 月 ${d.getDate()} 日 ${pad(d.getHours())}:${pad(d.getMinutes())}`
    },
    toggle(sha) {
      this.expanded = { ...this.expanded, [sha]: !this.expanded[sha] }
    },
    select(v) {
      this.selected = v
    },
    onReverted() {
      this.selected = null
      this.load()
      this.$emit('reverted')
    },
  },
}
</script>

<style lang="scss" scoped>
.timeline { flex: 1; padding: 12rpx 0; }
.timeline-empty { padding: 24rpx; color: #999; font-size: 26rpx; }
.timeline-node { position: relative; padding: 16rpx 20rpx 16rpx 40rpx; }
.node-line {
  position: absolute; left: 20rpx; top: 0; bottom: 0; width: 2rpx; background: #e4e4e4;
}
.timeline-node.is-session .node-title { font-weight: 600; }
.node-title { font-size: 27rpx; color: #222; }
.node-meta { font-size: 23rpx; color: #999; margin-top: 6rpx; }
.node-autos-toggle { font-size: 23rpx; color: #12344D; margin-top: 10rpx; }
.node-autos { margin-top: 10rpx; padding-left: 12rpx; border-left: 2rpx dashed #ddd; }
.node-auto { display: flex; gap: 12rpx; padding: 8rpx 0; }
.auto-time { font-size: 23rpx; color: #aaa; flex-shrink: 0; }
.auto-msg { font-size: 23rpx; color: #666; }
</style>
```

- [ ] **Step 3: 节点详情**

创建 `frontend/src/components/version/VersionNodeDetail.vue`：

```vue
<template>
  <view class="awd-mask" @tap.self="$emit('close')">
    <view class="awd-dialog">
      <view class="awd-header">
        <text class="awd-title">{{ version.note || version.message }}</text>
      </view>
      <view class="awd-body">
        <view class="detail-meta">{{ version.authorName }} · {{ when }}</view>
        <view v-if="!changes.length" class="detail-empty">这一版没有文件改动</view>
        <view v-for="c in changes" :key="c.path" class="detail-change">
          <text class="change-type" :class="'type-' + c.type">{{ typeLabel(c.type) }}</text>
          <text class="change-path">{{ c.path }}</text>
        </view>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="$emit('close')">关闭</view>
        <view class="awd-btn awd-btn-primary" @tap="confirmRevert">退回到这一版</view>
      </view>
    </view>
  </view>
</template>

<script>
import { getVersionChanges, revertToVersion } from '@/services/api.js'

export default {
  name: 'VersionNodeDetail',
  props: {
    projectId: { type: [String, Number], required: true },
    version: { type: Object, required: true },
  },
  emits: ['close', 'reverted'],
  data() {
    return { changes: [] }
  },
  computed: {
    when() {
      const d = new Date(this.version.when)
      const pad = (n) => String(n).padStart(2, '0')
      return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 ${pad(d.getHours())}:${pad(d.getMinutes())}`
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    async load() {
      try {
        const res = await getVersionChanges(this.projectId, this.version.sha)
        this.changes = ((res && res.data && res.data.changes) || [])
      } catch (e) {
        console.warn('[Version] 读取变更失败', e)
      }
    },
    typeLabel(t) {
      return { ADD: '新增', MODIFY: '修改', DELETE: '删除', RENAME: '改名' }[t] || t
    },
    confirmRevert() {
      uni.showModal({
        title: '退回到这一版',
        content: '项目会回到这一版的样子。这次退回本身也会记进时间线，随时可以再退回来。',
        success: async (r) => {
          if (!r.confirm) return
          try {
            await revertToVersion(this.projectId, this.version.sha)
            this.$emit('reverted')
          } catch (e) {
            uni.showToast({ title: '退回失败，请稍后重试', icon: 'none' })
          }
        },
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.awd-dialog { width: 640rpx; max-height: 70vh; background: #fff; border-radius: 12rpx; display: flex; flex-direction: column; }
.awd-header { padding: 24rpx; border-bottom: 1px solid #eee; }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; overflow-y: auto; flex: 1; }
.detail-meta { font-size: 24rpx; color: #999; margin-bottom: 16rpx; }
.detail-empty { font-size: 26rpx; color: #999; }
.detail-change { display: flex; gap: 12rpx; padding: 8rpx 0; }
.change-type { font-size: 23rpx; flex-shrink: 0; }
.type-ADD { color: #2a7; }
.type-MODIFY { color: #C8A45D; }
.type-DELETE { color: #b23; }
.type-RENAME { color: #666; }
.change-path { font-size: 25rpx; color: #333; word-break: break-all; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid #eee;
}
.awd-btn { padding: 12rpx 24rpx; border-radius: 6rpx; font-size: 25rpx; }
.awd-btn-primary { background: #12344D; color: #fff; }
.awd-btn-secondary { background: #f0f0f0; color: #333; }
</style>
```

- [ ] **Step 4: 跑死绑定护栏与构建**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/frontend" && npm run check:emits
```

预期：通过。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add frontend/src && git commit -m "feat(version): 工作状态条、时间线与节点详情"
```

---

### Task 15: 端到端旅程

**Files:**
- Modify: `frontend/tests/app-e2e/run.mjs`

**Interfaces:**
- Consumes: 前面所有任务

**既有 harness 结构**（已读出，照此写）：`frontend/tests/app-e2e/run.mjs` 不是声明式的 journey 数组，而是一串 `// ============ J<N> 名称 ============` 内联块。可用助手：

- `step(name, fn)` — 包一步，自动计数与失败截图
- `mouseClickText(label, { contains, nth })` — 按可见文本点（uni 的 `@tap` 必须真实鼠标坐标，`el.click()` 不触发）
- `mouseClickSel(sel)`、`waitText(t, ms)`、`textOf()`、`note(sev, what)`、`sleep(ms)`
- 输入框选择器是 `.uni-input-input`；图标按钮无文字，用 `[title="..."]` 定位
- 项目 id 在 `QA.projectId`，会话在 `QA.sid`，`api(ep, opts)` 可直接调后端

**重要**：本 harness 跑在浏览器目标下，**不驱动 LOWA 编辑器**（文件顶部注释：浏览器目标只验容器不验引擎）。所以工作段的触发不能靠「改文档」，改用上传文件——上传成功同样发变更信号（Task 12）。

- [ ] **Step 1: 追加旅程块**

在 `run.mjs` 最后一个 J 块之后、收尾统计之前插入：

```js
  // ============ J9 版本记录 ============
  console.log('== J9 版本记录 ==')
  await page.goto(BASE + '/#/pages/project-overview/project-overview?id=' + QA.projectId,
    { waitUntil: 'networkidle2', timeout: 30000 })
  await sleep(1500)

  await step('打开版本面板并看到未开启引导', async () => {
    await mouseClickSel('[title="版本"]')
    await waitText('本项目还没有开启版本记录')
  })

  await step('开启版本记录后显示主线', async () => {
    await mouseClickText('开启版本记录')
    await waitText('主线')
  })

  await step('上传文件后进入工作中', async () => {
    // 直接走后端上传，触发变更信号；比在 UI 里点文件树稳定
    const form = new FormData()
    form.append('file', new Blob([fs.readFileSync(smallFile)]), 'qa-版本测试.txt')
    await fetch(BACKEND + '/api/projects/' + QA.projectId + '/files/file', {
      method: 'POST', headers: { 'X-Session-Id': QA.sid }, body: form,
    })
    await page.reload({ waitUntil: 'networkidle2' })
    await mouseClickSel('[title="版本"]')
    await waitText('工作中')
  })

  await step('结束本次工作并命名', async () => {
    await mouseClickText('结束本次工作')
    await page.waitForSelector('.awd-input', { timeout: 10000 })
    const input = await page.$('.awd-input')
    await input.click(); await input.type('端到端测试稿', { delay: 15 })
    await mouseClickText('完成')
    await waitText('主线')
  })

  await step('时间线出现该工作段', async () => {
    await waitText('端到端测试稿')
  })

  let beforeCount = 0
  await step('节点详情列出被改文件', async () => {
    beforeCount = await page.evaluate(() =>
      document.querySelectorAll('.timeline-node').length)
    await mouseClickText('端到端测试稿')
    await waitText('qa-版本测试')
  })

  await step('退回后历史只增不减', async () => {
    await mouseClickText('退回到这一版')
    // uni.showModal 的确认按钮
    await mouseClickText('确定')
    await sleep(2000)
    const afterCount = await page.evaluate(() =>
      document.querySelectorAll('.timeline-node').length)
    if (afterCount <= beforeCount) {
      throw new Error('退回后版本数没有增加：' + beforeCount + ' -> ' + afterCount)
    }
  })
```

若 `[title="版本"]` 定位不到，检查 Task 13 在 `leftSidebarPlugins.js` 里注册的 rail 项是否把 `name` 渲染进了 `title` 属性——照相邻入口补上。

- [ ] **Step 2: 跑旅程**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/frontend" && npm run test:app-e2e
```

预期：J9 全绿。J1 登录若抖动，那是既有 issue #200，不是本次回归。

- [ ] **Step 3: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add frontend/tests && git commit -m "test(app-e2e): 版本记录端到端旅程"
```

---

### Task 16: 更新领域文档

**Files:**
- Modify: `CLAUDE.md`
- Create: `.claude/agents/version-control.md`
- Modify: `.claude/agents/sidebar-shell.md`

项目约定：改变了领域布局或新增契约的 PR，同一个 PR 里更新对应领域文档。本期新增了一个领域。

- [ ] **Step 1: 写领域文档**

创建 `.claude/agents/version-control.md`，照搬 `.claude/agents/sidebar-shell.md` 的结构（frontmatter + 关键文件 + 核心契约 + 已知地雷 + 验证），内容涵盖：

- 关键文件地图（`com.checkba.version` 各类职责、前端 `components/version/`）
- 核心契约：工作段生命周期状态机（ACTIVE→MERGED/DISCARDED）、提交消息尾注 `X-AWD-Kind`、清单 `.awd/tree.json` 格式
- 已知地雷：**历史永不重写**；版本记录失败不得阻断主流程；gitDir 与 workTree 必须分离；`.awd/` 不能出现在律师可见的文件树里
- 验证命令：本期新增的各测试类 + `npm run test:app-e2e`

- [ ] **Step 2: 加进路由表**

在 `CLAUDE.md` 的领域文档路由表里追加一行：

```markdown
| 版本记录、工作段、时间线、退回、Git 仓库 | `.claude/agents/version-control.md` |
```

- [ ] **Step 3: 更新侧边栏文档**

在 `.claude/agents/sidebar-shell.md` 的「左栏入口」一节，把 `version` 加进固定入口列表。

- [ ] **Step 4: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1" && git add CLAUDE.md .claude/agents && git commit -m "docs(version): 新增版本记录领域文档并入路由表"
```

---

## 收尾验证

全部任务完成后，按顺序跑：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/backend" && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
```

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/frontend" && npm run check:emits && npm run test:app-e2e
```

## 第 1 期完成后的状态

律师可以：开启版本记录、正常干活（工作段隐式开始）、结束本次工作并命名、看时间线（工作段为主、自动存档可展开）、看某一版改了哪些文件、退回到任意一版、丢弃整段工作。

**尚不能**：看两版之间的内容差异（第 2 期）、另起一稿（第 3 期）。AI 改动的署名仍是当前用户（第 2 期一并处理）。
