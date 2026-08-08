# 项目概览页 Plan 1：三级导航 + 项目列表页 + 项目概览页骨架

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「项目」从个人中心的一个 tab 变回一等公民——独立的项目列表页、独立的项目概览页，律师打开一个案卷就能看见它的档案、统计、动态、AI 对话历史和（B 期接入的）日程任务。

**Architecture:** 三级导航（项目列表页 → 项目概览页 → 工作台），`launch` 启动直达上次工作台的行为保留，只有主动切项目时才经过概览。后端新增一个 `ProjectOverviewController` 挂五个只读/轻写端点（统计、档案读、档案写、会话列表、任务空态）+ 一张新表 `project_profile_field`（字段级来源标记）。前端概览页是一页纸卷轴，拆成五个小组件纵向排布。

**Tech Stack:** Java 21 / Spring Boot 3 / JPA(ddl-auto=update) / JGit；uni-app + Vue 3（H5 构建）；测试用 JUnit 5 + Mockito + `@DataJpaTest`（后端）、`node --test` + 静态契约护栏脚本（前端）、puppeteer app-e2e（端到端）。

**设计依据：** `docs/superpowers/specs/2026-08-08-project-overview-design.md`。本 plan 是该 spec 的 A 期第一个切片。

**不在本切片范围：**
- 项目档案的 AI 抽取与 `.awd/profile.json` 跨机同步 → Plan 2
- 默认建仓与 13 条前置修复 → Plan 3（唯一例外：spec §9 第 4 条的 `/timeline` 未开仓早退修复在本切片，因为概览页第一天就会撞上）
- 任务系统的实体与 CRUD → B 期（本切片只落地 `GET /tasks` 端点契约并恒返回空数组，B 期接入时概览页一行不用改）

---

## Global Constraints

每个任务都隐含要求下面每一条，正文里不再重复。

- **JDK 21**：本机默认 JDK 25 会 SIGBUS。所有 maven 命令必须带前缀 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`，一次都不能省。
- **worktree 同树**：worktree 有独立的 `src/`，编辑与构建必须在同一棵树内，不要误用主仓库路径。
- **前端包管理用 npm**，不是 pnpm。
- **`docs/` 在 .gitignore 里**，往里加文件要 `git add -f`。
- **全局禁 emoji**：代码、UI、文档、commit message 一律不用。（既有 `docs/QA_JOURNEYS.md` 表格里的历史符号沿用，不扩散到新代码与 UI。）
- **外壳配色红线**：保持浅色（白 / `#F8F9FA` chrome + 森林绿 `#1A5336` + mint `#5BD197` 点缀），不做深色 chrome。
- **无数据库迁移体系**：四个 profile 全是 `ddl-auto: update`，无 flyway/liquibase。新表零成本，但**字段只增不减不改类型**；NOT NULL 的收回、列改名、类型变更都要手写 ALTER 并写进部署清单。
- **三种库并存**：prod 是 MySQL8，default/cloud 是 PostgreSQL，桌面打包态是 H2。**桌面壳开发态默认跑 prod(MySQL)、打包态才跑 desktop(H2)**——本机改 schema 的验证环境和线上不是同一种库，新表要在两种库上各验一次。
- **HTTP 口径**：全站 HTTP 200 + `{code, message}`，**不引 401/403**。失败一律抛 `IllegalArgumentException`，由 `GlobalExceptionHandler.java:69-77` 统一转换（注释原文「统一返回 HTTP 200，通过 code 字段表示失败」）。测试断 `status().isOk()` + `jsonPath("$.code").value(1)`。
- **鉴权参数序地雷**：`hasReadPermission` / `hasWritePermission` 的参数序是 `(projectId, userId)`，**两个都是 Long，写反了能编译通过**。`isClient` 是三个字面量的显式 or，不是 `startsWith("CLIENT")`。
- **权限口径**：读走 `hasReadPermission`（**含 CLIENT**，与尽调清单同一套心智）；写走 `hasWritePermission` 且 `!isClient`。
- **`pages.json` 每页必须显式写 `navigationStyle: custom`**（globalStyle 里没有这一项，漏写会得到系统导航栏）。
- **`awd-*` 弹窗/按钮样式没有集中定义**（在 `project-overview.vue`/`ChatInterface.vue`/`FileTree.vue` 各写一份 scoped 副本）。新页与新组件**不引入 `awd-*`**，档案编辑走行内 input、删除确认走 `uni.showModal`。
- **页面栈多实例守卫**：凡新增持有全局订阅的页面都要套活跃实例指针模式（概览页用自己的 `__checkbaProjectHomeVm`，**不要复用工作台的 `__checkbaActiveOverviewVm`**）。
- **导航总规则**：**凡是工作台参与的跳转一律 `reLaunch`；工作台之外的页面之间用 `navigateTo`。**

### 三个同名不同物的术语（第一段就要认清）

现有路由 `pages/project-overview/project-overview` **是工作台**，不是概览页；`pages.json` 里它的中文标题恰好也叫「项目概览」，`app-e2e/run.mjs` 用 `hash.includes('project-overview')` 判定「进了工作台」。本 plan 一律：

| 术语 | 路由 |
|---|---|
| **工作台** | `pages/project-overview/project-overview`（**不改名**） |
| **项目列表页** | `pages/project-list/project-list`（新增） |
| **项目概览页** | `pages/project-home/project-home`（新增） |

### e2e 类名锚点（九个，前端任务必须原样落上）

```
.page-project-list   .page-project-home
.btn-project-list    .btn-workbench
.overview-stats-bar  .profile-header
.activity-feed       .task-schedule      .conversation-list
```

---

## File Structure

**后端新增**
| 文件 | 职责 |
|---|---|
| `controller/ProjectOverviewController.java` | 五个端点的唯一挂载点。**第一个执行到的任务创建它，后续任务只追加方法与构造器参数**，不得重建 |
| `service/ProjectOverviewService.java` | 统计条的树骨架投影与系统目录剔除 |
| `service/ProjectProfileService.java` | 档案字段的读/手填/来源锁定不变式 |
| `model/entity/ProjectProfileField.java` + `repository/ProjectProfileFieldRepository.java` | 新表 `project_profile_field` |

**后端修改**
| 文件 | 改动 |
|---|---|
| `repository/ProjectFileRepository.java` | 加项目级文件树骨架查询 |
| `repository/AgentRunRecordRepository.java` | 加 `findByProjectIdOrderByUpdatedAtDesc` |
| `service/ProjectAiMessageService.java` | 加 `listProjectConversations`（就地复用它的私有清洗方法，不新起服务） |
| `model/entity/ProjectAiMessage.java` | 加两条 `@Index` |
| `version/VersionController.java` | `/timeline` 未开仓早退返回空数组 |

**前端新增**
| 文件 | 职责 |
|---|---|
| `pages/project-list/project-list.vue` | 项目列表页（从 userprofile 整块搬出） |
| `pages/project-home/project-home.vue` + `.scss` | 概览页容器：取数、五个区块编排、两个导航出口 |
| `components/project-home/{ProfileHeader,OverviewStatsBar,ActivityFeed,TaskSchedule,ConversationList}.vue` | 五个纵向区块，各自只管自己那一块 |
| `config/matterTypes.js` / `utils/projectHomeFormat.js` | 事项类型取值集合 / 纯展示格式化 |
| `scripts/check-navigation-contract.mjs` | 导航契约静态护栏（新增 `npm run check:nav`） |
| `tests/project-home/*.test.mjs` | 组件源码级契约测试（新增 `npm run test:project-home`） |

**前端修改**：`pages.json`、`services/api.js`、`pages/userprofile/userprofile.vue`（删项目 tab）、`pages/launch/launch.vue`、`pages/login/login.vue`、`pages/newproject/index.vue`、`pages/project-overview/project-overview.vue`（导航出口 + 消费 `conversationId`）、`components/collab/CollabDialog.vue`（邀请话术）、`App.vue`、`tests/app-e2e/run.mjs`

**文档**：`.claude/agents/sidebar-shell.md`、`.claude/agents/ai-chat.md`、`CLAUDE.md`、`docs/QA_JOURNEYS.md`

---

## 任务总览（39 个）

| 区间 | 组 | 交付物 |
|---|---|---|
| Task 1-5 | 后端·概览统计与动态 | 统计端点、后台 AI 任务查询、`/timeline` 早退修复 |
| Task 6-10 | 后端·项目档案 | 新表 + 读/写端点 + 来源锁定不变式（含 MySQL8 建表实测） |
| Task 11-15 | 后端·项目级会话列表 | 两条索引 + 复合游标分页 + 列表层可见性 |
| Task 16-23 | 前端·项目列表页与导航改造 | 搬迁、6 条入口重判、导航契约护栏、工作台消费 `conversationId` |
| Task 24-31 | 前端·项目概览页 | 五个区块组件 + 页面容器 |
| Task 32-39 | e2e 与领域文档 | J2/J3 旅程重写、四份文档、全量验证 |

**执行顺序建议**：按任务号顺序。唯一例外是 Task 23 保留了两条校验 Task 36 产出的 check 断言（跨任务互校），按号执行时那两条会暂红到 Task 36 完成——可以把 Task 36 提前到 Task 23 之前跑，或接受暂红。

---

### Task 1: ProjectFileRepository 新增项目级文件树骨架查询

**Files:**
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectFileRepositoryTreeSkeletonTest.java`
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/repository/ProjectFileRepository.java`（在 `:88` 的 `findByProjectIdAndIsDeletedTrueOrderByDeletedAtDesc` 之后、`:89` 的类闭合括号 `}` 之前追加。行号已实测核对）
- Test: 同 Create

**Interfaces:**
- Consumes: `com.checkba.model.entity.ProjectFile`（既有实体，字段一个不加）
- Produces: `List<Object[]> findTreeSkeletonByProjectId(Long projectId)`，行形状 `[id(Long), parentId(Long), isFolder(Boolean), name(String)]`，只含 `isDeleted=false` 的行

> 为什么不是契约初稿说的两个派生 count：派生 count 无法递归，做不到「剔除 `__staging_area__` 与 `AI Assistant Files` 整棵子树」这条同段落里的硬口径。标量投影一次取回 4 列（不 hydrate 实体、不传文件内容），服务层在内存里算，对外仍然只发两个整数。
>
> `ProjectFileRepository.java` 既有 4 个 `count` 方法（`:43` `countByUserIdAndIsDeletedFalse`、`:48` `countByParentIdAndIsDeletedFalse`、`:51` `countByParentId`、`:78` `sumSizeByProjectId`）没有一个是项目级文件/文件夹计数，所以必须新增。

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/checkba/repository/ProjectFileRepositoryTreeSkeletonTest.java`：

```java
package com.checkba.repository;

import com.checkba.model.entity.ProjectFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 内存 H2（MODE=PostgreSQL），头部配置照抄 version/WorkSessionRepositoryTest:19-28，
 * 只换 H2 库名（库名是本类 ApplicationContext 缓存键的一部分，重名会串数据）。
 * 钉死概览页统计条依赖的骨架查询：四列、只回存活行、只回本项目。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-file-skeleton-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectFileRepositoryTreeSkeletonTest {

    @Autowired
    private ProjectFileRepository repository;

    private ProjectFile row(Long projectId, Long parentId, boolean folder, String name, boolean deleted) {
        ProjectFile f = new ProjectFile();
        f.setProjectId(projectId);
        f.setParentId(parentId);
        f.setIsFolder(folder);
        f.setName(name);
        f.setSortOrder(0);
        f.setUserId(1L);
        f.setIsDeleted(deleted);
        f.setCreatedAt(LocalDateTime.now());
        return repository.save(f);
    }

    @Test
    void returnsFourColumnSkeletonOfLivingRowsOfOneProjectOnly() {
        ProjectFile folder = row(7L, null, true, "合同", false);
        row(7L, folder.getId(), false, "框架协议.docx", false);
        row(7L, null, false, "已删.docx", true);
        row(8L, null, false, "别的项目.docx", false);

        List<Object[]> rows = repository.findTreeSkeletonByProjectId(7L);

        assertEquals(2, rows.size());
        for (Object[] r : rows) {
            assertEquals(4, r.length);
        }
        Object[] child = rows.stream()
                .filter(r -> "框架协议.docx".equals(r[3]))
                .findFirst()
                .orElseThrow();
        assertEquals(folder.getId(), child[1]);
        assertEquals(Boolean.FALSE, child[2]);

        Object[] root = rows.stream()
                .filter(r -> "合同".equals(r[3]))
                .findFirst()
                .orElseThrow();
        assertEquals(null, root[1]);
        assertEquals(Boolean.TRUE, root[2]);
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectFileRepositoryTreeSkeletonTest -DfailIfNoTests=false
```

Expected: `BUILD FAILURE`，编译期错误 `cannot find symbol: method findTreeSkeletonByProjectId(long) location: interface com.checkba.repository.ProjectFileRepository`。

- [ ] **Step 3: 最小实现**

在 `backend/src/main/java/com/checkba/repository/ProjectFileRepository.java` 的 `:88`（`List<ProjectFile> findByProjectIdAndIsDeletedTrueOrderByDeletedAtDesc(Long projectId);` 那行）之后、`:89` 的 `}` 之前插入。该文件的既有风格是「注解写全限定名、不加 import」（见 `:71`、`:77`），照它写：

```java

    /**
     * 项目文件树骨架（排除软删除）：只取统计需要的四列，不 hydrate 实体、不传文件内容。
     * 行形状 [id(Long), parentId(Long), isFolder(Boolean), name(String)]。
     *
     * 概览页统计条要剔除 __staging_area__ 与 AI Assistant Files 的整棵子树，
     * 派生 count 方法做不到递归，因此一次取回骨架、由服务层在内存里走一遍，
     * 对外仍然只发两个整数。不要拿它当文件树接口用。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT pf.id, pf.parentId, pf.isFolder, pf.name FROM ProjectFile pf "
            + "WHERE pf.projectId = :projectId AND pf.isDeleted = false")
    List<Object[]> findTreeSkeletonByProjectId(
            @org.springframework.data.repository.query.Param("projectId") Long projectId);
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectFileRepositoryTreeSkeletonTest -DfailIfNoTests=false
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add backend/src/main/java/com/checkba/repository/ProjectFileRepository.java \
        backend/src/test/java/com/checkba/repository/ProjectFileRepositoryTreeSkeletonTest.java && \
git commit -m "$(cat <<'EOF'
feat(overview): ProjectFileRepository 新增项目级文件树骨架查询

概览页统计条要按项目数文件与文件夹，且要剔除 __staging_area__ 与
AI Assistant Files 的整棵子树。派生 count 做不到递归，改用四列标量
投影一次取回骨架，由服务层在内存里算，对外仍只发两个整数。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: AgentRunRecordRepository 新增项目级后台任务查询

**Files:**
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/AgentRunRecordRepositoryProjectScopeTest.java`
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/repository/AgentRunRecordRepository.java`（在 `:16` 的 `findByStatus` 之后、`:17` 的类闭合括号 `}` 之前追加。行号已实测核对）
- Test: 同 Create

**Interfaces:**
- Consumes: `com.checkba.model.entity.AgentRunRecord`（既有实体，字段一个不加；已有 `conversationId:27-28`、`status:31-32`、`projectId:34-35`、`updatedAt:40-41`，Lombok `@Getter/@Setter`）
- Produces: `List<AgentRunRecord> findByProjectIdOrderByUpdatedAtDesc(Long projectId)`

> **本任务只加这一个方法。** 契约里另一个 `findByConversationIdIn(Collection<String>)` 服务的是项目级会话列表端点的 runStatus 批量取，由「后端·项目级会话列表」组（Task 11 起）往同一个文件追加——两组不要抢同一行。
>
> 为什么读表不读内存：`AgentRunStateService`（`backend/src/main/java/com/checkba/service/agent/AgentRunStateService.java:49`）是进程内 `ConcurrentHashMap`，进程重启后历史会话状态整片变 null。统计条要显示「有 AI 任务在后台跑」，必须读 `agent_run_record` 表。

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/checkba/repository/AgentRunRecordRepositoryProjectScopeTest.java`：

```java
package com.checkba.repository;

import com.checkba.model.entity.AgentRunRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 概览页统计条的「后台 AI 任务」取自 agent_run_record 表（不是 AgentRunStateService
 * 的内存 Map——进程重启后内存态全是 null）。这里钉死按项目过滤 + 按 updatedAt 倒序。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-run-project-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AgentRunRecordRepositoryProjectScopeTest {

    @Autowired
    private AgentRunRecordRepository repository;

    private void record(String conversationId, Long projectId, String status, LocalDateTime updatedAt) {
        AgentRunRecord r = new AgentRunRecord();
        r.setConversationId(conversationId);
        r.setProjectId(projectId);
        r.setStatus(status);
        r.setUpdatedAt(updatedAt);
        repository.save(r);
    }

    @Test
    void returnsOnlyThisProjectsRunsNewestFirst() {
        LocalDateTime base = LocalDateTime.of(2026, 8, 8, 10, 0, 0);
        record("c-old", 7L, "DONE", base);
        record("c-new", 7L, "RUNNING", base.plusHours(2));
        record("c-other", 8L, "RUNNING", base.plusHours(3));

        List<AgentRunRecord> runs = repository.findByProjectIdOrderByUpdatedAtDesc(7L);

        assertEquals(2, runs.size());
        assertEquals("c-new", runs.get(0).getConversationId());
        assertEquals("RUNNING", runs.get(0).getStatus());
        assertEquals("c-old", runs.get(1).getConversationId());
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=AgentRunRecordRepositoryProjectScopeTest -DfailIfNoTests=false
```

Expected: `BUILD FAILURE`，编译期错误 `cannot find symbol: method findByProjectIdOrderByUpdatedAtDesc(long) location: interface com.checkba.repository.AgentRunRecordRepository`。

- [ ] **Step 3: 最小实现**

在 `backend/src/main/java/com/checkba/repository/AgentRunRecordRepository.java` 的 `:16`（`List<AgentRunRecord> findByStatus(String status);`）之后、`:17` 的 `}` 之前插入：

```java

    /**
     * 概览页统计条的「后台 AI 任务」：按项目取运行记录，最近更新的在前。
     * 刻意读表不读 AgentRunStateService 的内存 Map——那份状态进程重启即清零，
     * 概览页把历史铺开时会整片显示无状态。服务层再 limit，不在 SQL 里限条数。
     */
    List<AgentRunRecord> findByProjectIdOrderByUpdatedAtDesc(Long projectId);
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=AgentRunRecordRepositoryProjectScopeTest -DfailIfNoTests=false
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add backend/src/main/java/com/checkba/repository/AgentRunRecordRepository.java \
        backend/src/test/java/com/checkba/repository/AgentRunRecordRepositoryProjectScopeTest.java && \
git commit -m "$(cat <<'EOF'
feat(overview): AgentRunRecordRepository 新增项目级后台任务查询

统计条的「后台 AI 任务」读 agent_run_record 表而不是 AgentRunStateService
的内存 Map：后者进程重启即清零，概览页铺开历史会整片显示无状态。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: ProjectOverviewService 组装统计条数据

**Files:**
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/service/ProjectOverviewService.java`
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/service/ProjectOverviewServiceTest.java`
- Test: 同上第二个

**Interfaces:**
- Consumes: `ProjectFileRepository.findTreeSkeletonByProjectId(Long)`（Task 1 产出）、`AgentRunRecordRepository.findByProjectIdOrderByUpdatedAtDesc(Long)`（Task 2 产出）、`ProjectMemberRepository.findByProjectId(Long)`（既有 `:13`）、`ProjectRepository.findById(Long)`、`ProjectStorageResolver.hasLocalRoot(long)`（`storage/ProjectStorageResolver.java:108`，注意参数是原始 `long`）、常量 `StageQuotaService.STAGING_FOLDER_NAME`（`service/quota/StageQuotaService.java:40`，`public static final`）
- Produces: `Map<String,Object> stats(Long projectId)`，键固定为 `fileCount`(Long) / `folderCount`(Long) / `isLocalRoot`(Boolean) / `memberCount`(Integer) / `backgroundRuns`(`List<Map>`，元素键 `conversationId`、`status`、`updatedAt`，最多 5 条，空时为 `[]`)

三条口径必须钉死：
1. `__staging_area__` 与 `AI Assistant Files` 两个根级系统文件夹**连整棵子树一起剔除**，且这两个文件夹自身不计入 `folderCount`。识别口径是「项目根下（`parentId` 为 null）的同名文件夹」：`__staging_area__` 由 `frontend/src/pages/project-overview/stagingArea.js:39` 的 `createFolder(this.projectId, null, folderName)` 建在根下，`AI Assistant Files` 由 `backend/src/main/java/com/checkba/service/ProjectFileService.java:835` 的 `ensureFolder(projectId, null, "AI Assistant Files", userId)` 建在根下。
2. `memberCount` 要去重：`project_member` 返回裸行，owner 可能另有一行也可能没有（`ProjectMemberService.getMemberRole` 是先判 `project.userId` 再查表，说明两者并存），不去重会多算。
3. **不算「项目大小」与「最近修改」**——编辑器保存路径不更新 `ProjectFile.updatedAt/fileSize`，那两个数是假的。

> 软删除是递归的（`ProjectFileService.softDeleteRecursive:363` 把子孙一并置 `isDeleted=true`），所以「排除 `isDeleted=true`」不会留下不可达的存活子节点；统计走「全部存活行减去系统子树」而不是「从根 BFS」，脏数据里的孤儿行也能被计入。

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/checkba/service/ProjectOverviewServiceTest.java`：

```java
package com.checkba.service;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectMember;
import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 概览页统计条的三条口径：系统目录整棵子树剔除、成员去重、后台任务取表且封顶 5 条。
 */
@ExtendWith(MockitoExtension.class)
class ProjectOverviewServiceTest {

    private static final Long PROJECT = 7L;

    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AgentRunRecordRepository agentRunRecordRepository;
    @Mock private ProjectStorageResolver storageResolver;

    @InjectMocks private ProjectOverviewService service;

    private Object[] node(long id, Long parentId, boolean folder, String name) {
        return new Object[]{id, parentId, folder, name};
    }

    private ProjectMember member(Long userId) {
        ProjectMember m = new ProjectMember();
        m.setUserId(userId);
        return m;
    }

    private AgentRunRecord run(String conversationId, String status, LocalDateTime updatedAt) {
        AgentRunRecord r = new AgentRunRecord();
        r.setConversationId(conversationId);
        r.setStatus(status);
        r.setUpdatedAt(updatedAt);
        return r;
    }

    /** 五个依赖都要有桩，stats() 每次都会全部走到（严格桩下不会有 UnnecessaryStubbing）。 */
    private void stub(List<Object[]> tree, boolean localRoot,
                      List<ProjectMember> members, Long ownerUserId,
                      List<AgentRunRecord> runs) {
        when(projectFileRepository.findTreeSkeletonByProjectId(PROJECT)).thenReturn(tree);
        when(storageResolver.hasLocalRoot(PROJECT)).thenReturn(localRoot);
        when(projectMemberRepository.findByProjectId(PROJECT)).thenReturn(members);
        Project p = new Project();
        p.setId(PROJECT);
        p.setUserId(ownerUserId);
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(p));
        when(agentRunRecordRepository.findByProjectIdOrderByUpdatedAtDesc(PROJECT)).thenReturn(runs);
    }

    @Test
    void excludesSystemFolderSubtreesFromBothCounts() {
        stub(List.of(
                node(1L, null, true, "合同"),
                node(2L, 1L, false, "框架协议.docx"),
                node(3L, null, true, "__staging_area__"),
                node(4L, 3L, false, "临时.pdf"),
                node(5L, 3L, true, "拖进来的整个文件夹"),
                node(6L, 5L, false, "深层.pdf"),
                node(7L, null, true, "AI Assistant Files"),
                node(8L, 7L, false, "纪要.md")
        ), false, List.of(), 1L, List.of());

        Map<String, Object> data = service.stats(PROJECT);

        assertEquals(1L, data.get("fileCount"));
        assertEquals(1L, data.get("folderCount"));
        assertEquals(false, data.get("isLocalRoot"));
        assertEquals(List.of(), data.get("backgroundRuns"));
    }

    @Test
    void memberCountDeduplicatesOwnerThatAlsoHasAMemberRow() {
        stub(List.of(), false, List.of(member(1L), member(2L), member(2L)), 1L, List.of());

        assertEquals(2, service.stats(PROJECT).get("memberCount"));
    }

    @Test
    void memberCountCountsOwnerWithoutMemberRow() {
        stub(List.of(), false, List.of(), 1L, List.of());

        assertEquals(1, service.stats(PROJECT).get("memberCount"));
    }

    @Test
    void localRootProjectIsFlagged() {
        stub(List.of(), true, List.of(), 1L, List.of());

        assertEquals(true, service.stats(PROJECT).get("isLocalRoot"));
    }

    @Test
    void backgroundRunsAreCappedAtFiveAndCarryStatusAndIsoTime() {
        LocalDateTime base = LocalDateTime.of(2026, 8, 8, 10, 11, 12);
        List<AgentRunRecord> runs = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            runs.add(run("c-" + i, "RUNNING", base.minusMinutes(i)));
        }
        stub(List.of(), false, List.of(), 1L, runs);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out =
                (List<Map<String, Object>>) service.stats(PROJECT).get("backgroundRuns");

        assertEquals(5, out.size());
        assertEquals("c-0", out.get(0).get("conversationId"));
        assertEquals("RUNNING", out.get(0).get("status"));
        assertEquals("2026-08-08T10:11:12", out.get(0).get("updatedAt"));
    }

    @Test
    void backgroundRunWithoutTimestampKeepsNullInsteadOfBlowingUp() {
        stub(List.of(), false, List.of(), 1L, List.of(run("c-x", "INTERRUPTED", null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out =
                (List<Map<String, Object>>) service.stats(PROJECT).get("backgroundRuns");

        assertEquals(1, out.size());
        assertTrue(out.get(0).containsKey("updatedAt"));
        assertEquals(null, out.get(0).get("updatedAt"));
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectOverviewServiceTest -DfailIfNoTests=false
```

Expected: `BUILD FAILURE`，编译期错误 `cannot find symbol: class ProjectOverviewService location: package com.checkba.service`。

- [ ] **Step 3: 最小实现**

新建 `backend/src/main/java/com/checkba/service/ProjectOverviewService.java`：

```java
package com.checkba.service;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectMember;
import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.quota.StageQuotaService;
import com.checkba.storage.ProjectStorageResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目概览页统计条的数据组装。只读，不写任何表。
 *
 * <p>刻意不提供「项目大小」与「最近修改」：编辑器保存路径不更新
 * ProjectFile.updatedAt/fileSize，那两个数是假的。要「最近修改」请取
 * /version/timeline 最新一条的 when，不要走 /version/status（它会跑两次 git add）。</p>
 */
@Service
public class ProjectOverviewService {

    /** AI 生成物固定落在项目根下这个文件夹（见 ProjectFileService.java:835）。 */
    static final String AI_ARTIFACT_FOLDER_NAME = "AI Assistant Files";

    /** 统计条最多带回几条后台任务。 */
    private static final int MAX_BACKGROUND_RUNS = 5;

    private final ProjectFileRepository projectFileRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    private final AgentRunRecordRepository agentRunRecordRepository;
    private final ProjectStorageResolver storageResolver;

    public ProjectOverviewService(ProjectFileRepository projectFileRepository,
                                  ProjectMemberRepository projectMemberRepository,
                                  ProjectRepository projectRepository,
                                  AgentRunRecordRepository agentRunRecordRepository,
                                  ProjectStorageResolver storageResolver) {
        this.projectFileRepository = projectFileRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectRepository = projectRepository;
        this.agentRunRecordRepository = agentRunRecordRepository;
        this.storageResolver = storageResolver;
    }

    public Map<String, Object> stats(Long projectId) {
        List<Object[]> rows = projectFileRepository.findTreeSkeletonByProjectId(projectId);
        Set<Long> excluded = systemSubtreeIds(rows);

        long fileCount = 0L;
        long folderCount = 0L;
        for (Object[] r : rows) {
            if (excluded.contains((Long) r[0])) continue;
            if (Boolean.TRUE.equals(r[2])) folderCount++;
            else fileCount++;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("fileCount", fileCount);
        data.put("folderCount", folderCount);
        data.put("isLocalRoot", storageResolver.hasLocalRoot(projectId));
        data.put("memberCount", memberCount(projectId));
        data.put("backgroundRuns", backgroundRuns(projectId));
        return data;
    }

    /**
     * 两个系统文件夹（缓存区、AI 生成物）连整棵子树一起剔除。它们都是普通
     * ProjectFile 行、isDeleted=false，任何朴素计数都会把它们和子树数进去；
     * 而律师可以把整个文件夹拖进缓存区，所以剔除必须递归。
     * 识别口径是「项目根下（parentId 为 null）的同名文件夹」——两者都建在根下。
     */
    private Set<Long> systemSubtreeIds(List<Object[]> rows) {
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        for (Object[] r : rows) {
            Long id = (Long) r[0];
            Long parentId = (Long) r[1];
            childrenOf.computeIfAbsent(parentId, k -> new ArrayList<>()).add(id);
            if (parentId == null && Boolean.TRUE.equals(r[2]) && isSystemFolderName((String) r[3])) {
                queue.add(id);
            }
        }
        Set<Long> excluded = new HashSet<>();
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            // add 返回 false = 已经走过，脏数据里父子成环时不再下钻
            if (!excluded.add(id)) continue;
            queue.addAll(childrenOf.getOrDefault(id, List.of()));
        }
        return excluded;
    }

    private boolean isSystemFolderName(String name) {
        return StageQuotaService.STAGING_FOLDER_NAME.equals(name)
                || AI_ARTIFACT_FOLDER_NAME.equals(name);
    }

    /**
     * 成员数要去重：project_member 返回裸行，owner 可能另有一行也可能没有
     * （getMemberRole 是先判 project.userId 再查表，说明两者并存），不去重会多算。
     */
    private int memberCount(Long projectId) {
        Set<Long> userIds = new HashSet<>();
        for (ProjectMember m : projectMemberRepository.findByProjectId(projectId)) {
            if (m.getUserId() != null) userIds.add(m.getUserId());
        }
        projectRepository.findById(projectId)
                .map(Project::getUserId)
                .ifPresent(userIds::add);
        return userIds.size();
    }

    private List<Map<String, Object>> backgroundRuns(Long projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentRunRecord r : agentRunRecordRepository.findByProjectIdOrderByUpdatedAtDesc(projectId)) {
            if (out.size() >= MAX_BACKGROUND_RUNS) break;
            // HashMap 允许 null 值；Map.of 不允许，updatedAt 可空所以不能用 Map.of
            Map<String, Object> m = new HashMap<>();
            m.put("conversationId", r.getConversationId());
            m.put("status", r.getStatus());
            m.put("updatedAt", r.getUpdatedAt() == null ? null : r.getUpdatedAt().toString());
            out.add(m);
        }
        return out;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectOverviewServiceTest -DfailIfNoTests=false
```

Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add backend/src/main/java/com/checkba/service/ProjectOverviewService.java \
        backend/src/test/java/com/checkba/service/ProjectOverviewServiceTest.java && \
git commit -m "$(cat <<'EOF'
feat(overview): 新增 ProjectOverviewService 组装统计条数据

三条口径：__staging_area__ 与 AI Assistant Files 连整棵子树剔除、
成员按 userId 去重（owner 可能另有一行）、后台任务读表并封顶 5 条。
不出「项目大小」与「最近修改」——编辑器保存不更新 fileSize/updatedAt。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: ProjectOverviewController 骨架 + GET /overview/stats + GET /tasks

**Files:**
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/controller/ProjectOverviewController.java`
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/controller/ProjectOverviewStatsAuthTest.java`
- Test: 同上第二个

**Interfaces:**
- Consumes: `AuthController.getUserIdFromSession(String)`（`public static`，`backend/src/main/java/com/checkba/controller/AuthController.java:640`）、`ProjectMemberService.hasReadPermission(Long projectId, Long userId)`（`backend/src/main/java/com/checkba/service/ProjectMemberService.java:151`）、`ProjectOverviewService.stats(Long)`（Task 3 产出）
- Produces: `GET /api/projects/{projectId}/overview/stats` 与 `GET /api/projects/{projectId}/tasks`，均 HTTP 200 + `{"code":0,"data":{...}}`；私有助手 `requireRead(Long projectId, String sessionId)` 与 `ok(Map)` 供后续三组复用

**这个文件是三个后端组共用的唯一骨架，本任务是第一个到场的组，负责创建它。硬约束（不得各自发挥）：**
1. `requireRead` / `requireWrite` 的参数序恒为 **`(Long projectId, String sessionId)`**（projectId 在前，与端点方法参数顺序一致）。
2. 所有端点返回类型统一 `ResponseEntity<Map<String, Object>>`，统一走私有 `ok(Map<String,Object>)`。
3. 依赖注入统一**显式构造器**，不用 `@RequiredArgsConstructor`。
4. **后续组只追加方法与构造器参数，不要重写整个文件。** `requireWrite(Long projectId, String sessionId)` 由项目档案组（Task 6 起）随 `PUT /profile/{fieldKey}` 一起追加——本任务不预先写没有调用方、没有测试覆盖的私有方法。
5. 鉴权测试类**分三个不同文件名**，本组用 `ProjectOverviewStatsAuthTest`，档案组用 `ProjectOverviewProfileAuthTest`，会话列表组用 `ProjectOverviewConversationsAuthTest`，谁都不许同名覆盖。
6. **`hasReadPermission(projectId, userId)` 的参数序是地雷**：两个都是 `Long`，写反能编译通过、运行时静默返回 false（表现是「明明是我的项目却说无权访问」）。
7. **`X-Session-Id` 必须 `required = false`**：`getUserIdFromSession` 内部已处理设备令牌/local-mode 免登/普通 session 三种身份，写成 `required = true` 会让桌面端整条链 500。
8. **不拒 CLIENT**：统计条只是数量、任务列表是交付日期，客户看得见文件树就该看得见。这与 `VersionController.requireMember`（`backend/src/main/java/com/checkba/version/VersionController.java:556-566` 显式拒 CLIENT）口径不同，是有意的。
9. **状态码走全站惯例**：未登录/越权抛 `IllegalArgumentException`，由 `backend/src/main/java/com/checkba/config/GlobalExceptionHandler.java:69-77` 统一转成 HTTP 200 + `{"code":1,"message":...}`（那里的注释明写「统一返回 HTTP 200，通过 code 字段表示失败」）。**不引 401/403**，不新造异常类型。HTTP 层「200 + code 1」的集成断言由 e2e 与文档组（Task 32 起）负责，本任务是控制器单测，断 `assertThrows`。

> **`@InjectMocks` 说明**：后续两组会给构造器加 `ProjectProfileService` / `ProjectAiMessageService` 参数。Mockito 选最大构造器、未匹配的参数传 `null`，所以本测试类在那之后仍然编译且通过（stats/tasks 两个端点根本不碰那两个依赖）。**后续组新增依赖时，必须在自己的测试类里补齐自己那个 `@Mock` 字段**，不要指望别人的测试类替你注入。

- [ ] **Step 1: 幂等判定——确认控制器文件尚不存在**

Run:
```bash
ls "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/controller/ProjectOverviewController.java"
```

Expected: `No such file or directory` —— 说明本组是第一个到场的，按 Step 4 新建整个文件。

若它已存在（说明别的组先跑了），**不要覆盖**：只在字段区加 `private final ProjectOverviewService overviewService;`、在既有显式构造器上加一个参数，并在类末尾追加 `overviewStats` 与 `tasks` 两个方法，`requireRead` / `ok` 直接复用既有的。

- [ ] **Step 2: 写失败的测试**

新建 `backend/src/test/java/com/checkba/controller/ProjectOverviewStatsAuthTest.java`：

```java
package com.checkba.controller;

import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectOverviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 概览统计条与任务列表两个端点的鉴权口径：必须登录 + 必须是项目成员，但不拒 CLIENT
 * （统计条只是数量、任务是交付日期，客户看得见文件树就该看得见）。
 *
 * 未登录/越权抛 IllegalArgumentException，由 GlobalExceptionHandler:69-77 统一转成
 * HTTP 200 + {code:1,message}，全站同一口径，不引 401/403。
 *
 * 注意 hasReadPermission 的参数序是 (projectId, userId)，两参数同为 Long，写反能编译通过。
 *
 * 类名带 Stats 前缀是刻意的：档案组与会话列表组会各写一个鉴权测试类往同一个控制器上挂，
 * 三个文件名必须互不相同（ProjectOverviewProfileAuthTest / ProjectOverviewConversationsAuthTest）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectOverviewStatsAuthTest {

    @Mock private ProjectMemberService projectMemberService;
    @Mock private ProjectOverviewService overviewService;

    @InjectMocks private ProjectOverviewController controller;

    @Test
    void anonymousIsRejected() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.overviewStats(7L, null));
            assertEquals("未登录", e.getMessage());
            verify(overviewService, never()).stats(anyLong());
        }
    }

    @Test
    void nonMemberIsRejected() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(false);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.overviewStats(7L, "sess"));
            assertEquals("无权访问该项目", e.getMessage());
            verify(overviewService, never()).stats(anyLong());
        }
    }

    @Test
    void memberGetsEnvelopeAndClientIsNotBlocked() {
        Map<String, Object> stats = Map.of(
                "fileCount", 12L, "folderCount", 3L, "isLocalRoot", false,
                "memberCount", 4, "backgroundRuns", List.of());
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);
            when(overviewService.stats(7L)).thenReturn(stats);

            Map<String, Object> body = controller.overviewStats(7L, "sess").getBody();

            assertNotNull(body);
            assertEquals(0, body.get("code"));
            assertEquals(stats, body.get("data"));
            // 统计条不拒 CLIENT：isClient 一次都不该被问
            verify(projectMemberService, never()).isClient(anyLong(), anyLong());
        }
    }

    @Test
    void tasksRejectsNonMember() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(false);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.tasks(7L, "sess"));
            assertEquals("无权访问该项目", e.getMessage());
        }
    }

    /** A 期恒空数组（不是 null）。B 期接上任务系统只换实现，路径与响应形状一行不改。 */
    @Test
    void tasksReturnsEmptyListForMember() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);

            Map<String, Object> body = controller.tasks(7L, "sess").getBody();

            assertNotNull(body);
            assertEquals(0, body.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            assertEquals(List.of(), data.get("tasks"));
            verify(projectMemberService, never()).isClient(anyLong(), anyLong());
        }
    }
}
```

- [ ] **Step 3: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectOverviewStatsAuthTest -DfailIfNoTests=false
```

Expected: `BUILD FAILURE`，编译期错误 `cannot find symbol: class ProjectOverviewController location: package com.checkba.controller`。

- [ ] **Step 4: 最小实现**

新建 `backend/src/main/java/com/checkba/controller/ProjectOverviewController.java`：

```java
package com.checkba.controller;

import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectOverviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 项目概览页（pages/project-home，产品语言里的「项目概览页」，不是工作台
 * pages/project-overview）的只读端点。
 *
 * <p>类级路径与 ProjectController(/api/projects) 不相交：那边占 POST /、
 * GET|PUT|DELETE /{id}、/my、/{id}/local-path 等，这边全部多一段。</p>
 *
 * <p>响应一律信封 {code, data}，且一律返回自己组装的 Map，不下发实体
 * （GET /api/projects/{id} 现在返裸实体是遗留问题，别照抄）。</p>
 *
 * <p><b>本文件是三个后端切片共用的骨架，后来的组只追加方法与构造器参数，不要重写整文件。</b>
 * 约定：requireRead/requireWrite 的参数序恒为 (Long projectId, String sessionId)；
 * 端点返回类型恒为 ResponseEntity&lt;Map&lt;String,Object&gt;&gt;；依赖注入用显式构造器，
 * 不引 Lombok 的 @RequiredArgsConstructor。写端点用的
 * {@code private Long requireWrite(Long projectId, String sessionId)}
 * 随 PUT /profile/{fieldKey} 一起追加（没有调用方就不预先写）。</p>
 *
 * <p>AuthController.getUserIdFromSession 是 public static（AuthController.java:640），
 * 直接调，不把 AuthController 注入进来——注入一个 @RestController bean 只为调静态方法，
 * 既无必要又会给每个单测凭空加一个 @Mock。后续组也不要加这个字段。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectOverviewController {

    private final ProjectMemberService projectMemberService;
    private final ProjectOverviewService overviewService;

    public ProjectOverviewController(ProjectMemberService projectMemberService,
                                     ProjectOverviewService overviewService) {
        this.projectMemberService = projectMemberService;
        this.overviewService = overviewService;
    }

    /**
     * 读端点的统一入口：必须登录 + 必须是项目成员。返回 userId。
     *
     * <p>不拒 CLIENT——统计条/档案/会话列表/任务都是客户该看见的那一层。
     * 地雷：hasReadPermission 的参数序是 (projectId, userId)，两个都是 Long，
     * 写反了能编译通过、运行时静默返回 false。</p>
     *
     * <p>抛 IllegalArgumentException 而不是返 401/403：GlobalExceptionHandler:69-77
     * 把它统一转成 HTTP 200 + {code:1,message}，全站 90+ 端点同一口径。</p>
     */
    private Long requireRead(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException("无权访问该项目");
        }
        return userId;
    }

    /** 概览页统计条：一次请求喂满整块（文件/文件夹计数、成员数、localRoot、后台 AI 任务）。 */
    @GetMapping("/overview/stats")
    public ResponseEntity<Map<String, Object>> overviewStats(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireRead(projectId, sessionId);
        return ok(overviewService.stats(projectId));
    }

    /**
     * 日程与任务。A 期恒空数组，概览页据此渲染空态；B 期接上任务系统时只换实现，
     * 路径与响应形状一行不改，前端零改动。所以这里不建 service、不建实体。
     * B 期的任务 CRUD 另起 TaskController(/api/tasks)，但这条列表端点保持在
     * /api/projects/{projectId}/tasks 不迁移。
     */
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> tasks(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireRead(projectId, sessionId);
        return ok(Map.of("tasks", List.of()));
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectOverviewStatsAuthTest -DfailIfNoTests=false
```

Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add backend/src/main/java/com/checkba/controller/ProjectOverviewController.java \
        backend/src/test/java/com/checkba/controller/ProjectOverviewStatsAuthTest.java && \
git commit -m "$(cat <<'EOF'
feat(overview): 新增 ProjectOverviewController 与 stats/tasks 两个端点

信封返回自组装 Map，不下发实体。读权限走 hasReadPermission(projectId, userId)
且不拒 CLIENT——统计条只是数量、任务是交付日期，客户看得见文件树就该看得见。
X-Session-Id 一律 required=false，否则 local-mode 免登整条链 500。
未登录/越权抛 IllegalArgumentException，由全局处理器转 200 + code 1，
与全站 90+ 端点同一口径，不引 401/403。

tasks 端点 A 期恒返回空数组，B 期只换实现，路径与响应形状不改。
本文件是三个后端切片共用的骨架：后来的组只追加方法与构造器参数。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: /version/timeline 未开仓时早退返回空列表

**Files:**
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/version/VersionTimelineEarlyReturnTest.java`
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/version/VersionController.java`（在 `@GetMapping("/timeline")` 方法体首行 `requireMember(projectId, sessionId);`（当前 `:246`）之后插入早退。**用文本锚点定位，不要只按行号**）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/version/VersionControllerAuthTest.java`（`:90`、`:108`、`:125` 三个既有用例各补一行 `isInitialized` 桩。行号已实测核对）
- Test: 同 Create

**Interfaces:**
- Consumes: `ProjectRepoService.isInitialized(long projectId)`（`backend/src/main/java/com/checkba/version/ProjectRepoService.java:75-77`，判 `.git/objects` 目录是否存在）
- Produces: `GET /api/projects/{projectId}/version/timeline` 在仓库未初始化时改为 `{"code":0,"data":{"versions":[]}}`（此前是 `{"code":1,"message":"版本记录操作失败，请重试"}`）

**为什么这条属于本切片**：新建项目十有八九没开版本记录，概览页的动态块第一天就会撞上。不修的话 `ActivityFeed` 只能把「还没有版本记录」显示成读取失败，这是最差的第一印象。

**下游影响，前端两组要知道**：改完之后，非 CLIENT 用户在未开仓项目上拿到的是 `code:0` + 空 `versions`，动态块走的是「还没有动态」这条普通空态；`ActivityFeed` 的 `unavailable` 分支此后只剩 CLIENT 一条路径（`VersionController.requireMember:556-566` 显式拒 CLIENT），组件契约本身不变，仍然要保留 `unavailable` prop 与它的中性空态。

**早退位置**在 `requireMember` 之后、`fileId` 归属校验之前。副作用是「在未开仓项目上探测他人 fileId」从抛「无权访问该文件」变成返回空列表——响应与 fileId 是否存在无关，信息泄露只减不增。

- [ ] **Step 1: 写失败的测试**

新建 `backend/src/test/java/com/checkba/version/VersionTimelineEarlyReturnTest.java`。注意 `VersionController` 的构造器有 7 个参数（`VersionController.java:40-46`），7 个 `@Mock` 一个不少地列出来，避免 `@InjectMocks` 悄悄注入 null：

```java
package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 未开启版本记录不是错误：新建项目十有八九没开，概览页的动态块第一天就会撞上。
 * /timeline 必须早退返回空 versions，而不是掉进 VersionException 的通用错误信封
 * （「版本记录操作失败，请重试」），否则概览页会把「还没有版本记录」显示成「读取失败」。
 */
@ExtendWith(MockitoExtension.class)
class VersionTimelineEarlyReturnTest {

    @Mock private ProjectRepoService repoService;
    @Mock private WorkSessionService sessionService;
    @Mock private ProjectMemberService projectMemberService;
    @Mock private UserService userService;
    @Mock private ProjectFileService projectFileService;
    @Mock private ProjectTreeManifestService manifestService;
    @Mock private com.checkba.service.telemetry.TelemetryService telemetryService;

    @InjectMocks private VersionController controller;

    private void asMember(MockedStatic<AuthController> auth) {
        auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
        when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);
        when(projectMemberService.isClient(7L, 1L)).thenReturn(false);
    }

    @SuppressWarnings("unchecked")
    private void assertEmptyEnvelope(Map<String, Object> body) {
        assertNotNull(body);
        assertEquals(0, body.get("code"));
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertEquals(List.of(), data.get("versions"));
    }

    @Test
    void returnsEmptyVersionsWhenRepositoryNotInitialized() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            asMember(auth);
            when(repoService.isInitialized(7L)).thenReturn(false);

            assertEmptyEnvelope(controller.timeline(7L, 5, null, "sess").getBody());
            // 早退的证据：一次都不许去开仓/读日志
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void fileScopedTimelineAlsoEarlyReturnsWithoutTouchingTheFileTree() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            asMember(auth);
            when(repoService.isInitialized(7L)).thenReturn(false);

            assertEmptyEnvelope(controller.timeline(7L, 5, 50L, "sess").getBody());
            verify(projectFileService, never()).getFile(anyLong());
            verify(repoService, never()).logForPath(anyLong(), anyString(), anyString(), anyInt());
        }
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=VersionTimelineEarlyReturnTest -DfailIfNoTests=false
```

Expected: `BUILD FAILURE`，`Tests run: 2, Failures: 1, Errors: 1`，两条各自确定：

- `returnsEmptyVersionsWhenRepositoryNotInitialized` → Mockito 对返回 `List` 的未桩方法默认给**空集合**（不是 null），所以信封断言先通过，随后 `verify(never())` 报
  `org.mockito.exceptions.verification.NeverWantedButInvoked: repoService.log(7L, "HEAD", 5); Never wanted here ... But invoked here`。
- `fileScopedTimelineAlsoEarlyReturnsWithoutTouchingTheFileTree` → 走进了 fileId 分支，`projectFileService.getFile(50L)` 未桩返回 null，报
  `java.lang.NullPointerException: Cannot invoke "com.checkba.model.entity.ProjectFile.getProjectId()" because "f" is null`。

（两条用例都失败，所以 Mockito 的严格桩不会再额外报 `UnnecessaryStubbingException`——未使用的 `isInitialized` 桩只在用例通过时才被清算。）

- [ ] **Step 3: 最小实现**

打开 `backend/src/main/java/com/checkba/version/VersionController.java`，定位 `@GetMapping("/timeline")` 方法（当前 `:240-260`），在方法体首行 `requireMember(projectId, sessionId);`（当前 `:246`）与下一行 `List<VersionEntry> entries;` 之间插入 5 行。插入后整个方法形如：

```java
    @GetMapping("/timeline")
    public ResponseEntity<Map<String, Object>> timeline(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        // 未开启版本记录不是错误：新建项目十有八九没开，概览页的动态块第一天就会撞上。
        // 不早退的话这里会掉进 VersionException 的通用信封（「版本记录操作失败，请重试」），
        // 概览页只能把「还没有版本记录」显示成「读取失败」。
        if (!repoService.isInitialized(projectId)) {
            return ok(Map.of("versions", List.of()));
        }
        List<VersionEntry> entries;
        if (fileId != null) {
            ProjectFile f = projectFileService.getFile(fileId); // 文件不存在会抛异常
            if (!projectId.equals(f.getProjectId())) {
                // 拒绝消息不带 fileId：越权探测者不该从错误文案里拿到内部 id 的存在性回执。
                throw new IllegalArgumentException("无权访问该文件");
            }
            String relPath = WorkSessionService.repoRelativePath(f);
            entries = repoService.logForPath(projectId, "HEAD", relPath, limit);
        } else {
            entries = repoService.log(projectId, "HEAD", limit);
        }
        return ok(Map.of("versions", entries));
    }
```

（`ok(...)` 是同文件 `:596-598` 的既有私有助手，`List` 已在 `:12` import，无需新增 import。）

然后修 `backend/src/test/java/com/checkba/version/VersionControllerAuthTest.java` 里三个会走到早退之后的既有用例——各在 `isClient` 桩之后补一行 `when(repoService.isInitialized(7L)).thenReturn(true);`：

`:90` 之后（`memberCanSeeTimeline`）：
```java
            when(projectMemberService.isClient(7L, 1L)).thenReturn(false);
            when(repoService.isInitialized(7L)).thenReturn(true);
            when(repoService.log(7L, "HEAD", 50)).thenReturn(java.util.List.of());
```

`:108` 之后（`timelineRejectsFileFromAnotherProject`）：
```java
            when(projectMemberService.isClient(7L, 1L)).thenReturn(false);
            when(repoService.isInitialized(7L)).thenReturn(true);
            ProjectFile foreign = new ProjectFile();
```

`:125` 之后（`timelineFiltersByFileWithinSameProject`）：
```java
            when(projectMemberService.isClient(7L, 1L)).thenReturn(false);
            when(repoService.isInitialized(7L)).thenReturn(true);
            ProjectFile own = new ProjectFile();
```

`clientRoleCannotSeeTimeline` / `nonMemberCannotSeeTimeline` / `anonymousCannotSeeTimeline` 在 `requireMember` 就抛了，一行都不用改。参数化的三组 `Endpoint` 用例（`:149-153` 的枚举）里没有 TIMELINE，也不受影响。

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest='VersionTimelineEarlyReturnTest,VersionControllerAuthTest' -DfailIfNoTests=false
```

Expected: 两个类全绿（`VersionTimelineEarlyReturnTest` 2 个用例、`VersionControllerAuthTest` 既有用例全部），`BUILD SUCCESS`。

再跑一次后端全量回归（Task 1-5 的产出一起过一遍）：
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test
```

Expected: `BUILD SUCCESS`，`Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add backend/src/main/java/com/checkba/version/VersionController.java \
        backend/src/test/java/com/checkba/version/VersionControllerAuthTest.java \
        backend/src/test/java/com/checkba/version/VersionTimelineEarlyReturnTest.java && \
git commit -m "$(cat <<'EOF'
fix(version): /timeline 未开仓时早退返回空列表

此前未开启版本记录会掉进通用错误信封「版本记录操作失败，请重试」，
概览页的动态块只能把「还没有版本记录」显示成「读取失败」——而新建
项目十有八九没开，这是第一印象。改为早退返回 {versions: []}，响应
形状与已开仓路径一致，前端 res.data.versions 读法不变。

早退位于 requireMember 之后、fileId 归属校验之前：未开仓项目上探测
他人 fileId 从抛异常变成返回空列表，响应与 fileId 是否存在无关，信息
泄露只减不增。VersionControllerAuthTest 三个用例随之补 isInitialized 桩。

副作用：ActivityFeed 的 unavailable 分支此后只剩 CLIENT 一条路径
（requireMember 显式拒 CLIENT），组件契约与中性空态保持不变。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

## 本组共用前提（Task 6-10，执行前先读一遍）

- **仓库根**：`/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91`。worktree 有独立的 `src/`，编辑与构建必须在同一棵树内，不要误用主仓库路径。
- **JDK**：本机默认是 JDK 25，直接跑 `mvn` 会 SIGBUS。所有 maven 命令都必须带 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home` 前缀，一次都不能省。
- **HTTP 口径（全站惯例，不引 401/403）**：`backend/src/main/java/com/checkba/config/GlobalExceptionHandler.java:69-77` 对 `IllegalArgumentException` 一律 `ResponseEntity.ok()`，注释明写「统一返回 HTTP 200，通过 code 字段表示失败」，全站 90+ 端点同一口径，前端 `services/api.js` 的 request 包装器也是按 `code` 解的。本组两个端点未登录/越权时抛 `IllegalArgumentException`，HTTP 层表现为 **200 + `{"code":1,"message":"..."}`**。Task 10 里有两条 MockMvc 用例把这个 HTTP 层表现钉死。
- **`ProjectOverviewController.java` 是四个后端组共用的一个文件**（stats / profile / conversations / tasks 五个端点都挂在它上面）。约定：**第一个执行到的组创建文件，后续组只追加构造器参数与方法**。骨架形状在 Task 10 里写死，不要各自发挥。
- **鉴权参数序是地雷**：`hasReadPermission(Long projectId, Long userId)`（`backend/src/main/java/com/checkba/service/ProjectMemberService.java:151`）、`hasWritePermission(Long, Long)`（`:159`）、`isClient(Long, Long)`（`:171`），三个方法都是 **projectId 在前**，两个参数同为 `Long`，**写反能编译通过、运行时静默返回 false**（表现是「明明是我的项目却说无权访问」）。
- **数据库**：本仓无 flyway/liquibase，四个 profile 全是 `ddl-auto: update`。新表零成本，但**字段只增不减、不改类型**，VARCHAR 长度在 update 模式下也不会自动加宽。桌面壳开发态默认跑 prod profile（MySQL8），打包态才跑 desktop（H2 file, MODE=PostgreSQL）——**本机改 schema 的验证环境和线上不是同一种库**，所以 Task 6 里 H2 与 MySQL8 两侧各验一次建表。
- **全局禁 emoji**（代码 / UI / 文档 / commit 一律不用）。

---

### Task 6: 新建 ProjectProfileField 实体与 Repository，并在 H2 与 MySQL8 两侧各验一次建表

**Files:**
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/model/entity/ProjectProfileField.java`
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/repository/ProjectProfileFieldRepository.java`
- Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectProfileFieldRepositoryTest.java`
- Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectProfileFieldMysqlSchemaTest.java`
- 参考（只读不改）：`backend/src/main/java/com/checkba/model/entity/DdItem.java:14-15`（@Entity/@Table 形制）、`DdItem.java:211-223`（equals/hashCode 只比 id）、`backend/src/test/java/com/checkba/version/WorkSessionRepositoryTest.java:19-28`（H2 内存库配方）、`backend/src/main/resources/application-prod.yml:6-16`（线上 MySQL8 + ddl-auto: update 的实际配置）

**Interfaces:**
- Consumes: 无
- Produces: `com.checkba.model.entity.ProjectProfileField`；`com.checkba.repository.ProjectProfileFieldRepository extends JpaRepository<ProjectProfileField, Long>`，含 `List<ProjectProfileField> findByProjectId(Long projectId)` 与 `Optional<ProjectProfileField> findByProjectIdAndFieldKey(Long projectId, String fieldKey)`

背景约束（不要自作主张改）：

- 四个 `pending*` 列**本期就建出来**（A 期恒为 null，Plan 2 的 AI 建议直接写、不用改表）。`fieldValue` 定 2048、`evidence` 定 4000，不要图省事写小——`ddl-auto=update` 事后加宽要手写 ALTER 并进部署清单。
- `fieldValue` **用 VARCHAR 不用 TEXT/@Lob**：`@Lob String` 在 PostgreSQL 上映射成 OID 会炸，`columnDefinition="TEXT"` 要在 H2 / MySQL8 / PostgreSQL 三种库上各验一次；VARCHAR(2048) 三种库通吃。
- `@UniqueConstraint(columnNames=...)` 与 `@Index(columnList=...)` 里**必须写物理列名（snake_case）**——Spring Boot 默认物理命名策略把 `projectId` 转成 `project_id`，写成驼峰在建表时找不到列。
- 实体样板**手写 getter/setter + equals/hashCode 只比 id**，对齐 `DdItem`。不要用 Lombok `@Data`：它生成覆盖全字段的 equals/hashCode，在 JPA 游离态与懒加载下行为不可预期，而档案字段会以 List 形式在服务层被反复过滤。

---

- [ ] **Step 1: 写失败的测试（两个测试文件一起写）**

先确保测试包目录存在（该目录可能已存在但为空——git 不跟踪空目录）：

```bash
mkdir -p "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository"
```

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectProfileFieldRepositoryTest.java`：

```java
package com.checkba.repository;

import com.checkba.model.entity.ProjectProfileField;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 项目档案表的 schema 契约测试（H2 侧，MODE=PostgreSQL）。
 *
 * 本仓无 flyway/liquibase，四个 profile 全是 ddl-auto: update——新表零成本，
 * 但字段只增不减、不改类型、VARCHAR 不会自动加宽。因此这里把「唯一约束、
 * 长字段容量、pending 列建表即建」三条钉死，避免将来靠 ALTER 补救。
 *
 * H2 内存库配方照抄 WorkSessionRepositoryTest:19-28，只改库名——@TestPropertySource
 * 参与 ApplicationContext 缓存键，换个库名就不会与其他 @DataJpaTest 互相污染。
 *
 * MySQL8 侧的建表验证在 ProjectProfileFieldMysqlSchemaTest（需要 docker + 环境变量才跑）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:profile-field-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectProfileFieldRepositoryTest {

    @Autowired
    private ProjectProfileFieldRepository repository;

    private ProjectProfileField row(Long projectId, String fieldKey, String value, String source) {
        ProjectProfileField f = new ProjectProfileField();
        f.setProjectId(projectId);
        f.setFieldKey(fieldKey);
        f.setFieldValue(value);
        f.setSource(source);
        f.setUid(UUID.randomUUID().toString());
        return f;
    }

    @Test
    void 按项目与字段名取回单行() {
        repository.saveAndFlush(row(42L, "client", "北京某某科技有限公司", "user"));
        repository.saveAndFlush(row(42L, "counterparty", "上海某某贸易有限公司", "ai"));

        Optional<ProjectProfileField> found = repository.findByProjectIdAndFieldKey(42L, "client");
        assertTrue(found.isPresent());
        assertEquals("北京某某科技有限公司", found.get().getFieldValue());
        assertEquals("user", found.get().getSource());
        assertNotNull(found.get().getCreatedAt(), "@CreationTimestamp 应自动填充");
        assertNotNull(found.get().getUpdatedAt(), "@UpdateTimestamp 应自动填充");

        List<ProjectProfileField> all = repository.findByProjectId(42L);
        assertEquals(2, all.size());
        assertTrue(repository.findByProjectId(43L).isEmpty(), "不同项目之间不能串行");
    }

    @Test
    void 同一项目同一字段名只能有一行() {
        repository.saveAndFlush(row(42L, "client", "甲", "user"));
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(row(42L, "client", "乙", "ai")),
                "(projectId, fieldKey) 唯一约束必须生效——档案是一个字段一行，重复行会让 AI 建议另起一行");
    }

    @Test
    void 档案值可存2048字符且证据可存4000字符() {
        ProjectProfileField f = row(42L, "nextStep", "x".repeat(2048), "ai");
        f.setEvidence("y".repeat(4000));
        repository.saveAndFlush(f);

        ProjectProfileField loaded = repository.findByProjectIdAndFieldKey(42L, "nextStep").orElseThrow();
        assertEquals(2048, loaded.getFieldValue().length());
        assertEquals(4000, loaded.getEvidence().length());
    }

    @Test
    void pending四列建表即建_A期不写但可写() {
        ProjectProfileField f = row(42L, "matterType", "公司治理", "user");
        f.setPendingValue("并购交易");
        f.setPendingConfidence(0.82);
        f.setPendingEvidence("股权转让协议.docx 第 1 条");
        f.setPendingAt(LocalDateTime.of(2026, 8, 8, 10, 11, 12));
        repository.saveAndFlush(f);

        ProjectProfileField loaded = repository.findByProjectIdAndFieldKey(42L, "matterType").orElseThrow();
        assertEquals("并购交易", loaded.getPendingValue());
        assertEquals(0.82, loaded.getPendingConfidence(), 0.0001);
        assertEquals("股权转让协议.docx 第 1 条", loaded.getPendingEvidence());
        assertEquals(LocalDateTime.of(2026, 8, 8, 10, 11, 12), loaded.getPendingAt());
    }

    @Test
    void 相等性只看id() {
        ProjectProfileField a = new ProjectProfileField();
        a.setId(1L);
        a.setFieldValue("甲");
        ProjectProfileField b = new ProjectProfileField();
        b.setId(1L);
        b.setFieldValue("乙");
        ProjectProfileField c = new ProjectProfileField();
        c.setId(2L);

        assertEquals(a, b, "同 id 即同一实体——不能用全字段 equals");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
```

再新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectProfileFieldMysqlSchemaTest.java`：

```java
package com.checkba.repository;

import com.checkba.model.entity.ProjectProfileField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL8 侧的建表验证。
 *
 * 为什么必须单独验一次：桌面壳开发态默认跑 prod profile（MySQL8，
 * application-prod.yml:6-16），打包态才跑 desktop（H2 file, MODE=PostgreSQL）——
 * 本机 H2 上建表成功，不代表线上 MySQL8 上也成立。只在 MySQL 上才暴露的三件事：
 *   1. @UniqueConstraint / @Index 的物理列名解析是否真的落成了约束与索引；
 *   2. utf8mb4 下 VARCHAR 的字节膨胀（1 字符最多 4 字节），本表 varchar 合计
 *      (64 + 2048 + 8 + 4000 + 2048 + 4000 + 36) * 4 = 48816 字节，逼近 InnoDB
 *      单行 65535 字节的硬限，再往上加长字段就会建表失败；
 *   3. 4000 个中文的 evidence 能不能真写进去（H2 上按字符算，MySQL 上按字节算）。
 *
 * 默认不跑：类级 @EnabledIfEnvironmentVariable 在加载 Spring 上下文之前就判定，
 * 没有 AWD_MYSQL_SCHEMA_CHECK=1 时整个类被跳过，不会去连一个不存在的 MySQL 而挂住。
 * 跑法见本任务 Step 5。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13306/checkba_schema_check?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=checkba123",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQL8Dialect",
        "spring.jpa.hibernate.ddl-auto=update"
})
@EnabledIfEnvironmentVariable(named = "AWD_MYSQL_SCHEMA_CHECK", matches = "1")
class ProjectProfileFieldMysqlSchemaTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProjectProfileFieldRepository repository;

    private String showCreateTable() throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW CREATE TABLE project_profile_field")) {
            assertTrue(rs.next(), "project_profile_field 表没有被建出来");
            return rs.getString(2);
        }
    }

    @Test
    void 唯一约束与索引在MySQL8上真的建出来了() throws Exception {
        String ddl = showCreateTable();

        assertTrue(ddl.contains("uk_profile_field_project_key"),
                "唯一约束名没落上，实际 DDL:\n" + ddl);
        assertTrue(ddl.contains("UNIQUE KEY"),
                "uk_profile_field_project_key 不是唯一约束，实际 DDL:\n" + ddl);
        assertTrue(ddl.contains("`project_id`,`field_key`") || ddl.contains("`project_id`, `field_key`"),
                "唯一约束的列不是 (project_id, field_key)——@UniqueConstraint 里必须写 snake_case 物理列名，实际 DDL:\n" + ddl);
        assertTrue(ddl.contains("idx_profile_field_project"),
                "project_id 索引没落上，实际 DDL:\n" + ddl);
        assertTrue(ddl.contains("utf8mb4"),
                "表字符集不是 utf8mb4，字节口径与线上不一致，实际 DDL:\n" + ddl);
    }

    @Test
    void 长字段长度未被截短且行长在InnoDB限内() throws Exception {
        Map<String, Long> charLen = new HashMap<>();
        long octetTotal = 0;
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COLUMN_NAME, CHARACTER_MAXIMUM_LENGTH, CHARACTER_OCTET_LENGTH "
                             + "FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_SCHEMA = 'checkba_schema_check' "
                             + "AND TABLE_NAME = 'project_profile_field' "
                             + "AND CHARACTER_MAXIMUM_LENGTH IS NOT NULL")) {
            while (rs.next()) {
                charLen.put(rs.getString(1), rs.getLong(2));
                octetTotal += rs.getLong(3);
            }
        }

        assertEquals(2048L, charLen.get("field_value"), "field_value 必须是 VARCHAR(2048)");
        assertEquals(4000L, charLen.get("evidence"), "evidence 必须是 VARCHAR(4000)");
        assertEquals(2048L, charLen.get("pending_value"), "pending_value 必须是 VARCHAR(2048)");
        assertEquals(4000L, charLen.get("pending_evidence"), "pending_evidence 必须是 VARCHAR(4000)");
        assertEquals(64L, charLen.get("field_key"));
        assertEquals(8L, charLen.get("source"));
        assertEquals(36L, charLen.get("uid"));

        // InnoDB 单行硬限 65535 字节。当前合计 48816，余量约 16KB——
        // 谁要再往这张表加长 VARCHAR，这条会先红。
        assertTrue(octetTotal < 65535L,
                "varchar 字节合计 " + octetTotal + " 已超 InnoDB 单行 65535 字节限");
    }

    @Test
    void 四千个中文证据能真写进MySQL8() {
        ProjectProfileField f = new ProjectProfileField();
        f.setProjectId(42L);
        f.setFieldKey("nextStep");
        f.setFieldValue("一".repeat(2048));
        f.setEvidence("证".repeat(4000));
        f.setSource("ai");
        f.setUid(UUID.randomUUID().toString());
        repository.saveAndFlush(f);

        ProjectProfileField loaded = repository.findByProjectIdAndFieldKey(42L, "nextStep").orElseThrow();
        assertEquals(2048, loaded.getFieldValue().length());
        assertEquals(4000, loaded.getEvidence().length());
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest='ProjectProfileFieldRepositoryTest,ProjectProfileFieldMysqlSchemaTest'
```

Expected: `BUILD FAILURE`，`[ERROR] COMPILATION ERROR`。两个测试文件都报 `找不到符号  符号: 类 ProjectProfileField`（英文 locale 为 `cannot find symbol: class ProjectProfileField`），以及 `找不到符号  符号: 类 ProjectProfileFieldRepository`。

- [ ] **Step 3: 最小实现**

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/model/entity/ProjectProfileField.java`：

```java
package com.checkba.model.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 项目档案字段（一个字段一行——字段级来源标记要求行级粒度）。
 *
 * 固定五个 fieldKey：client / matterType / openedAt / nextStep / counterparty。
 *
 * source 库里只有两种取值：'ai'（AI 抽取）与 'user'（律师手填）。
 * 响应里可能出现的 'default' 是服务端为 openedAt 派生的（回落 Project.createdAt），
 * 永不落库。
 *
 * 核心不变式：source='user' 的字段锁定，AI 永不覆盖——AI 有新判断时写进同一行的
 * pending* 四列（唯一约束是 (projectId, fieldKey)，建议不能另起一行），律师采纳后才转正。
 *
 * 样板对齐 DdItem：手写 getter/setter，equals/hashCode 只比 id。
 * 不用 Lombok @Data——它生成覆盖全字段的 equals/hashCode，在 JPA 游离态与集合里行为不可预期。
 */
@Entity
@Table(
        name = "project_profile_field",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_profile_field_project_key",
                columnNames = {"project_id", "field_key"}),
        indexes = @Index(name = "idx_profile_field_project", columnList = "project_id")
)
public class ProjectProfileField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属项目 */
    @Column(nullable = false)
    private Long projectId;

    /** client / matterType / openedAt / nextStep / counterparty */
    @Column(length = 64, nullable = false)
    private String fieldKey;

    /**
     * 档案值。
     * 用 VARCHAR 不用 TEXT/@Lob：@Lob 在 PostgreSQL 上映射成 OID 会炸，
     * columnDefinition="TEXT" 要在 H2/MySQL8/PG 三种库上各验；VARCHAR(2048) 三种库通吃。
     * ddl-auto=update 不会自动加宽，所以一次给够。
     */
    @Column(length = 2048)
    private String fieldValue;

    /** 库里只存 'ai' | 'user' */
    @Column(length = 8, nullable = false)
    private String source;

    /** AI 填时的置信度，user 填时 null */
    @Column
    private Double confidence;

    /** AI 是从哪份文件哪句话得出的 */
    @Column(length = 4000)
    private String evidence;

    /** Plan 2 的 AI 建议值，挂在同一行 */
    @Column(length = 2048)
    private String pendingValue;

    @Column
    private Double pendingConfidence;

    @Column(length = 4000)
    private String pendingEvidence;

    @Column
    private LocalDateTime pendingAt;

    /** UUID，跨机器身份，.awd/profile.json 同步只认它 */
    @Column(length = 36, nullable = false)
    private String uid;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** 字段级 LWW 的裁决依据 */
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getFieldKey() {
        return fieldKey;
    }

    public void setFieldKey(String fieldKey) {
        this.fieldKey = fieldKey;
    }

    public String getFieldValue() {
        return fieldValue;
    }

    public void setFieldValue(String fieldValue) {
        this.fieldValue = fieldValue;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public String getPendingValue() {
        return pendingValue;
    }

    public void setPendingValue(String pendingValue) {
        this.pendingValue = pendingValue;
    }

    public Double getPendingConfidence() {
        return pendingConfidence;
    }

    public void setPendingConfidence(Double pendingConfidence) {
        this.pendingConfidence = pendingConfidence;
    }

    public String getPendingEvidence() {
        return pendingEvidence;
    }

    public void setPendingEvidence(String pendingEvidence) {
        this.pendingEvidence = pendingEvidence;
    }

    public LocalDateTime getPendingAt() {
        return pendingAt;
    }

    public void setPendingAt(LocalDateTime pendingAt) {
        this.pendingAt = pendingAt;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectProfileField that = (ProjectProfileField) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
```

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/repository/ProjectProfileFieldRepository.java`：

```java
package com.checkba.repository;

import com.checkba.model.entity.ProjectProfileField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 项目档案字段仓储。
 *
 * 只此两个方法：服务层按固定五键顺序在内存里组装响应，不靠 SQL 排序。
 * 「删项目连带清档案」的 deleteByProjectId 属 Plan 3，本期不预加。
 */
public interface ProjectProfileFieldRepository extends JpaRepository<ProjectProfileField, Long> {

    List<ProjectProfileField> findByProjectId(Long projectId);

    Optional<ProjectProfileField> findByProjectIdAndFieldKey(Long projectId, String fieldKey);
}
```

- [ ] **Step 4: 跑 H2 侧测试确认通过**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest='ProjectProfileFieldRepositoryTest,ProjectProfileFieldMysqlSchemaTest'
```

Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。MySQL 那个类因为没有 `AWD_MYSQL_SCHEMA_CHECK=1` 被整类跳过，不出现在 `Tests run` 里（surefire 会打印 `ProjectProfileFieldMysqlSchemaTest ... Tests run: 0`）。

- [ ] **Step 5: 起一个一次性 MySQL8 容器，验 MySQL 侧建表**

先起容器（镜像固定 `mysql:8.0`，与 `application-prod.yml` 的 `MySQL8Dialect` 和 pom 里的 `mysql-connector-java:8.0.33` 同代；本机是 arm64，`mysql:8.0` 有原生 arm64 镜像，首次 pull 约 1-2 分钟）：

```bash
docker run -d --name awd-mysql-schema-check \
  -e MYSQL_ROOT_PASSWORD=checkba123 \
  -e MYSQL_DATABASE=checkba_schema_check \
  -p 13306:3306 \
  mysql:8.0 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

等它就绪（首次初始化约 20-40 秒，就绪后立刻返回）：

```bash
until docker exec awd-mysql-schema-check \
  mysqladmin ping -h 127.0.0.1 -u root -pcheckba123 --silent >/dev/null 2>&1; do
  echo "waiting for mysql..."; sleep 3;
done; echo "mysql ready"
```

跑 MySQL 侧建表验证（`ddl-auto=update` 会在这个空库里把全部 40 个实体的表都建一遍，这正是「线上那种库上真建一次」的意思）：

```bash
AWD_MYSQL_SCHEMA_CHECK=1 JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" \
  test -Dtest=ProjectProfileFieldMysqlSchemaTest
```

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。

再人眼看一遍 DDL（验收标准：输出里必须同时出现 `UNIQUE KEY \`uk_profile_field_project_key\` (\`project_id\`,\`field_key\`)`、`KEY \`idx_profile_field_project\` (\`project_id\`)`、`\`field_value\` varchar(2048)`、`\`evidence\` varchar(4000)`、`DEFAULT CHARSET=utf8mb4`）：

```bash
docker exec awd-mysql-schema-check \
  mysql -u root -pcheckba123 -e "SHOW CREATE TABLE checkba_schema_check.project_profile_field\G"
```

- [ ] **Step 6: 拆掉容器**

```bash
docker rm -f awd-mysql-schema-check
```

Expected: 输出 `awd-mysql-schema-check`。这个库是一次性的，不要留着——它和本机 prod profile 用的 3306 不是同一个端口，留着只会占内存。

- [ ] **Step 7: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91"
git add backend/src/main/java/com/checkba/model/entity/ProjectProfileField.java \
        backend/src/main/java/com/checkba/repository/ProjectProfileFieldRepository.java \
        backend/src/test/java/com/checkba/repository/ProjectProfileFieldRepositoryTest.java \
        backend/src/test/java/com/checkba/repository/ProjectProfileFieldMysqlSchemaTest.java
git commit -m "feat(profile): 新增 project_profile_field 表与仓储

一个字段一行，唯一约束 (projectId, fieldKey)。pending 四列本期建表即建、
恒为 null，Plan 2 的 AI 建议直接写不用改表——ddl-auto=update 下字段只增不减。
fieldValue 用 VARCHAR(2048) 不用 TEXT/@Lob（@Lob 在 PostgreSQL 上映射成 OID）。

建表在 H2(MODE=PostgreSQL) 与 MySQL8 两侧各验一次：桌面壳开发态跑 prod(MySQL8)、
打包态才跑 desktop(H2)，本机验证环境和线上不是同一种库。MySQL 侧的测试用
AWD_MYSQL_SCHEMA_CHECK=1 环境变量开关，默认整类跳过。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: ProjectProfileService 读路径：恒返回五条固定顺序，openedAt 回落建档时间

**Files:**
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/service/ProjectProfileService.java`
- Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/service/ProjectProfileServiceTest.java`
- 参考（只读不改）：`backend/src/main/java/com/checkba/model/entity/Project.java:160-166`（getCreatedAt/setCreatedAt）、`backend/src/main/java/com/checkba/repository/ProjectRepository.java:8`（`ProjectRepository extends JpaRepository<Project, Long>`）、`backend/src/test/java/com/checkba/service/ProjectAiMessageServiceTest.java:35-55`（纯 Mockito 手工装配、不用 MockitoExtension 的既有风格）

**Interfaces:**
- Consumes: `ProjectProfileFieldRepository.findByProjectId(Long)`（Task 6）、`ProjectRepository.findById(Long)`（既有）、`Project.getCreatedAt()`（既有）
- Produces: `com.checkba.service.ProjectProfileService`；`public List<Map<String,Object>> getProfile(Long projectId)`；`public static final List<String> FIELD_KEYS`

硬契约（前端据此写死，不许自己补齐或排序）：

1. `getProfile` **恒返回 5 条**，顺序固定 `client → matterType → openedAt → nextStep → counterparty`。
2. `label` 由服务端给（中文文案单一来源，前端不再写一份）：客户 / 事项类型 / 立项时间 / 下一步 / 对方。
3. `source` 取值 `'ai' | 'user' | 'default'`，其中 **`'default'` 只出现在响应里、数据库永不存储**——`openedAt` 无行时用 `Project.getCreatedAt().toLocalDate().toString()` 填 `fieldValue` 并标 `source='default'`。UI 上要弱化成灰字（律师不能把「取自建档时间」当成有人填过）。
4. `updatedAt` 是 `LocalDateTime.toString()` 的 ISO 串（`2026-08-08T10:11:12`），无行时 null。

**实现地雷**：响应元素含 null 值，**必须用 `LinkedHashMap` 构造，不能用 `Map.of`**（`Map.of` 不接受 null value，会抛 NullPointerException）。

---

- [ ] **Step 1: 写失败的测试**

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/service/ProjectProfileServiceTest.java`：

```java
package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectProfileField;
import com.checkba.repository.ProjectProfileFieldRepository;
import com.checkba.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 项目档案服务。装配风格对齐 ProjectAiMessageServiceTest:35-55：手工 mock + 手工 new，
 * 不用 MockitoExtension（避免严格 stub 检查在多测试方法共用 setUp 时误报）。
 */
class ProjectProfileServiceTest {

    private ProjectProfileFieldRepository repository;
    private ProjectRepository projectRepository;
    private ProjectProfileService service;

    /** 项目 42 的建档时间 = 2026-08-01 09:30，openedAt 未填时应回落到 2026-08-01 */
    private static final LocalDateTime PROJECT_CREATED_AT = LocalDateTime.of(2026, 8, 1, 9, 30, 0);

    @BeforeEach
    void setUp() {
        repository = mock(ProjectProfileFieldRepository.class);
        projectRepository = mock(ProjectRepository.class);
        service = new ProjectProfileService(repository, projectRepository);

        Project project = new Project();
        project.setId(42L);
        project.setCreatedAt(PROJECT_CREATED_AT);
        when(projectRepository.findById(42L)).thenReturn(Optional.of(project));
        when(repository.findByProjectId(anyLong())).thenReturn(List.of());
    }

    private ProjectProfileField row(String fieldKey, String value, String source) {
        ProjectProfileField f = new ProjectProfileField();
        f.setId(1L);
        f.setProjectId(42L);
        f.setFieldKey(fieldKey);
        f.setFieldValue(value);
        f.setSource(source);
        f.setUid("uid-1");
        f.setUpdatedAt(LocalDateTime.of(2026, 8, 8, 10, 11, 12));
        return f;
    }

    @Test
    void 空档案也返回五条且顺序固定() {
        List<Map<String, Object>> fields = service.getProfile(42L);

        assertEquals(5, fields.size());
        assertEquals(List.of("client", "matterType", "openedAt", "nextStep", "counterparty"),
                fields.stream().map(f -> f.get("fieldKey")).toList());
        assertEquals(List.of("客户", "事项类型", "立项时间", "下一步", "对方"),
                fields.stream().map(f -> f.get("label")).toList());

        Map<String, Object> client = fields.get(0);
        assertTrue(client.containsKey("fieldValue"), "未填的字段也要出现在数组里，值为 null");
        assertNull(client.get("fieldValue"));
        assertNull(client.get("source"));
        assertNull(client.get("confidence"));
        assertNull(client.get("evidence"));
        assertNull(client.get("updatedAt"));
    }

    @Test
    void openedAt无行时回落建档时间并标default() {
        Map<String, Object> openedAt = service.getProfile(42L).get(2);

        assertEquals("openedAt", openedAt.get("fieldKey"));
        assertEquals("2026-08-01", openedAt.get("fieldValue"));
        assertEquals("default", openedAt.get("source"));
        assertNull(openedAt.get("updatedAt"), "派生值没有更新时间");
    }

    @Test
    void 已填字段原样返回并带ISO更新时间() {
        when(repository.findByProjectId(42L)).thenReturn(List.of(
                row("client", "北京某某科技有限公司", "user"),
                row("openedAt", "2026-07-15", "ai")));

        List<Map<String, Object>> fields = service.getProfile(42L);

        Map<String, Object> client = fields.get(0);
        assertEquals("北京某某科技有限公司", client.get("fieldValue"));
        assertEquals("user", client.get("source"));
        assertEquals("2026-08-08T10:11:12", client.get("updatedAt"));

        Map<String, Object> openedAt = fields.get(2);
        assertEquals("2026-07-15", openedAt.get("fieldValue"), "有行时不再回落建档时间");
        assertEquals("ai", openedAt.get("source"));
    }

    @Test
    void 项目不存在时openedAt不回落也不抛异常() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());
        when(repository.findByProjectId(99L)).thenReturn(List.of());

        Map<String, Object> openedAt = service.getProfile(99L).get(2);
        assertNull(openedAt.get("fieldValue"));
        assertNull(openedAt.get("source"));
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest=ProjectProfileServiceTest
```

Expected: `BUILD FAILURE`，`[ERROR] COMPILATION ERROR`，`ProjectProfileServiceTest.java` 报 `找不到符号  符号: 类 ProjectProfileService`（英文 locale 为 `cannot find symbol: class ProjectProfileService`）。

- [ ] **Step 3: 最小实现**

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/service/ProjectProfileService.java`：

```java
package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectProfileField;
import com.checkba.repository.ProjectProfileFieldRepository;
import com.checkba.repository.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目档案（客户 / 事项类型 / 立项时间 / 下一步 / 对方）。
 *
 * 对外契约：读接口恒返回 5 条、顺序固定、label 由服务端给——中文文案单一来源在这里，
 * 前端不许自己补齐缺项、不许自己排序、不许再写一份 label 表。
 *
 * source 库里只有 'ai' 与 'user' 两种取值；响应里可能出现的 'default' 是 openedAt
 * 无行时由 Project.createdAt 派生的，永不落库。
 */
@Service
public class ProjectProfileService {

    /** 固定五个字段，顺序即响应顺序 */
    public static final List<String> FIELD_KEYS =
            List.of("client", "matterType", "openedAt", "nextStep", "counterparty");

    private static final Map<String, String> LABELS = Map.of(
            "client", "客户",
            "matterType", "事项类型",
            "openedAt", "立项时间",
            "nextStep", "下一步",
            "counterparty", "对方");

    static final String SOURCE_USER = "user";
    static final String SOURCE_AI = "ai";
    static final String SOURCE_DEFAULT = "default";
    static final String KEY_OPENED_AT = "openedAt";

    private final ProjectProfileFieldRepository repository;
    private final ProjectRepository projectRepository;

    public ProjectProfileService(ProjectProfileFieldRepository repository,
                                 ProjectRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
    }

    /** 概览页档案头一次渲染完：五个字段全量返回，未填的也返回、值为 null。 */
    public List<Map<String, Object>> getProfile(Long projectId) {
        Map<String, ProjectProfileField> rows = new HashMap<>();
        for (ProjectProfileField row : repository.findByProjectId(projectId)) {
            rows.put(row.getFieldKey(), row);
        }
        Project project = projectRepository.findById(projectId).orElse(null);

        List<Map<String, Object>> fields = new ArrayList<>(FIELD_KEYS.size());
        for (String fieldKey : FIELD_KEYS) {
            fields.add(render(fieldKey, rows.get(fieldKey), project));
        }
        return fields;
    }

    /**
     * 组装单个字段的响应元素。
     *
     * 用 LinkedHashMap 不用 Map.of——Map.of 不接受 null value，而未填的字段五个值全是 null。
     */
    Map<String, Object> render(String fieldKey, ProjectProfileField row, Project project) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fieldKey", fieldKey);
        out.put("label", LABELS.get(fieldKey));

        if (row == null) {
            // openedAt 无行时回落建档时间。'default' 只出现在响应里，库里永远不会有这个值。
            if (KEY_OPENED_AT.equals(fieldKey) && project != null && project.getCreatedAt() != null) {
                out.put("fieldValue", project.getCreatedAt().toLocalDate().toString());
                out.put("source", SOURCE_DEFAULT);
            } else {
                out.put("fieldValue", null);
                out.put("source", null);
            }
            out.put("confidence", null);
            out.put("evidence", null);
            out.put("updatedAt", null);
            return out;
        }

        out.put("fieldValue", row.getFieldValue());
        out.put("source", row.getSource());
        out.put("confidence", row.getConfidence());
        out.put("evidence", row.getEvidence());
        out.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        return out;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest=ProjectProfileServiceTest
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91"
git add backend/src/main/java/com/checkba/service/ProjectProfileService.java \
        backend/src/test/java/com/checkba/service/ProjectProfileServiceTest.java
git commit -m "feat(profile): 档案读路径恒返回五条固定顺序

label 由服务端给，中文文案单一来源在服务层；前端不补齐、不排序。
openedAt 无行时回落 Project.createdAt 并标 source=default——这个取值只出现在
响应里，库里只有 ai 与 user 两种。响应元素含 null，用 LinkedHashMap 构造。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: ProjectProfileService 写路径：手填 upsert 与清空删除

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/service/ProjectProfileService.java`（Task 7 产出，在 `render` 方法之前追加三个方法，并补一个 import）
- Modify / Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/service/ProjectProfileServiceTest.java`（Task 7 产出，setUp 里补 save 桩、追加 5 个测试方法）

**Interfaces:**
- Consumes: `ProjectProfileFieldRepository.findByProjectIdAndFieldKey(Long, String)` / `.save(...)` / `.delete(...)`
- Produces: `public Map<String,Object> saveUserField(Long projectId, String fieldKey, String value)`

语义（A 期唯一的写入通道）：

- `value == null` 或 `trim()` 后为空串 → **删除该行**（回到未填态；`openedAt` 因此回落 `Project.createdAt` 默认值），返回的仍是同形状单条。
- 否则 upsert 一行：`fieldValue = value.trim()`、`source = 'user'`、`confidence = null`、`evidence = null`。**新建行生成 `uid = UUID.randomUUID().toString()`，既有行保持原 uid 不变**（uid 是跨机器身份，`.awd/profile.json` 同步只认它，换一个就等于换了一个字段）。
- `fieldKey` 不在白名单 → `IllegalArgumentException("未知的档案字段")`，经 `GlobalExceptionHandler`（`backend/src/main/java/com/checkba/config/GlobalExceptionHandler.java:69-77`）变成 HTTP 200 + `{"code":1,"message":"未知的档案字段"}`。

---

- [ ] **Step 1: 写失败的测试**

先在 `ProjectProfileServiceTest` 的 import 区补三行（放在既有 import 之后）：

```java
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
```

再把 `setUp()` 方法体末尾追加一段 save/find 桩（模拟 JPA：新行分配 id，并补 `@UpdateTimestamp` 的效果——单元测试里 Hibernate 不参与，时间戳不会自己填）：

```java
        // 模拟 JPA：save 时给新实体分配自增 ID 并补 updatedAt（@UpdateTimestamp 在单元测试里不会触发）
        when(repository.save(any(ProjectProfileField.class))).thenAnswer(inv -> {
            ProjectProfileField f = inv.getArgument(0);
            if (f.getId() == null) f.setId(100L);
            if (f.getUpdatedAt() == null) f.setUpdatedAt(LocalDateTime.of(2026, 8, 8, 10, 11, 12));
            return f;
        });
        when(repository.findByProjectIdAndFieldKey(anyLong(), anyString())).thenReturn(Optional.empty());
```

最后在类末尾追加 5 个测试方法：

```java
    @Test
    void 手填新字段落新行并锁成user() {
        Map<String, Object> saved = service.saveUserField(42L, "client", "  北京某某科技有限公司  ");

        ArgumentCaptor<ProjectProfileField> captor = ArgumentCaptor.forClass(ProjectProfileField.class);
        verify(repository).save(captor.capture());
        ProjectProfileField row = captor.getValue();
        assertEquals(42L, row.getProjectId());
        assertEquals("client", row.getFieldKey());
        assertEquals("北京某某科技有限公司", row.getFieldValue(), "两端空白要 trim");
        assertEquals("user", row.getSource());
        assertNull(row.getConfidence());
        assertNull(row.getEvidence());
        assertNotNull(row.getUid(), "新建行必须生成 uid——跨机器身份只认它");
        assertEquals(36, row.getUid().length());

        assertEquals("client", saved.get("fieldKey"));
        assertEquals("客户", saved.get("label"));
        assertEquals("北京某某科技有限公司", saved.get("fieldValue"));
        assertEquals("user", saved.get("source"));
        assertEquals("2026-08-08T10:11:12", saved.get("updatedAt"));
    }

    @Test
    void 改既有字段时uid不变() {
        ProjectProfileField existing = row("client", "旧客户", "ai");
        existing.setUid("uid-keep-me");
        existing.setConfidence(0.7);
        existing.setEvidence("从某份文件推断");
        when(repository.findByProjectIdAndFieldKey(42L, "client")).thenReturn(Optional.of(existing));

        service.saveUserField(42L, "client", "新客户");

        assertEquals("uid-keep-me", existing.getUid());
        assertEquals("新客户", existing.getFieldValue());
        assertEquals("user", existing.getSource(), "律师改过就锁成 user");
        assertNull(existing.getConfidence(), "改成手填后 AI 的置信度要清掉");
        assertNull(existing.getEvidence(), "改成手填后 AI 的证据要清掉");
    }

    @Test
    void 传空串删除该行() {
        ProjectProfileField existing = row("nextStep", "下周一交初稿", "user");
        when(repository.findByProjectIdAndFieldKey(42L, "nextStep")).thenReturn(Optional.of(existing));

        Map<String, Object> result = service.saveUserField(42L, "nextStep", "   ");

        verify(repository).delete(existing);
        verify(repository, never()).save(any(ProjectProfileField.class));
        assertNull(result.get("fieldValue"));
        assertNull(result.get("source"));
    }

    @Test
    void 删除openedAt后回落建档时间() {
        ProjectProfileField existing = row("openedAt", "2026-07-15", "user");
        when(repository.findByProjectIdAndFieldKey(42L, "openedAt")).thenReturn(Optional.of(existing));

        Map<String, Object> result = service.saveUserField(42L, "openedAt", null);

        verify(repository).delete(existing);
        assertEquals("2026-08-01", result.get("fieldValue"));
        assertEquals("default", result.get("source"));
    }

    @Test
    void 未知字段名被拒() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.saveUserField(42L, "secretColumn", "随便"));
        assertEquals("未知的档案字段", e.getMessage());
        verify(repository, never()).save(any(ProjectProfileField.class));
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest=ProjectProfileServiceTest
```

Expected: `BUILD FAILURE`，`[ERROR] COMPILATION ERROR`，`ProjectProfileServiceTest.java` 报 `找不到符号  符号: 方法 saveUserField(long,java.lang.String,java.lang.String)`（英文 locale 为 `cannot find symbol: method saveUserField(...)`），共 5 处（对应 5 个新测试方法里的调用点）。

- [ ] **Step 3: 最小实现**

在 `ProjectProfileService.java` 的 import 区补一行（`java.util.Optional` 本任务用不到——`ifPresent` / `orElseGet` 都是链式调用，不需要显式写出类型；它在 Task 9 才需要）：

```java
import java.util.UUID;
```

在 `render(...)` 方法之前追加三个方法：

```java
    /**
     * 手填单字段（A 期唯一的写入通道）。upsert 语义：
     * 写入即把该字段锁成 source='user'，Plan 2 的 AI 抽取永不覆盖它。
     *
     * value 为 null 或 trim 后为空串 → 删除该行（回到未填态；openedAt 因此回落建档时间）。
     */
    public Map<String, Object> saveUserField(Long projectId, String fieldKey, String value) {
        requireKnownKey(fieldKey);
        Project project = projectRepository.findById(projectId).orElse(null);

        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            repository.findByProjectIdAndFieldKey(projectId, fieldKey).ifPresent(repository::delete);
            return render(fieldKey, null, project);
        }

        ProjectProfileField row = repository.findByProjectIdAndFieldKey(projectId, fieldKey)
                .orElseGet(() -> newRow(projectId, fieldKey));
        row.setFieldValue(trimmed);
        row.setSource(SOURCE_USER);
        // 改成手填就把 AI 那次判断的痕迹清掉——留着会让 UI 把手填值标成「模型猜的」
        row.setConfidence(null);
        row.setEvidence(null);
        return render(fieldKey, repository.save(row), project);
    }

    /** 新行必须自带 uid：跨机器身份只认它，既有行的 uid 任何时候都不许换。 */
    private ProjectProfileField newRow(Long projectId, String fieldKey) {
        ProjectProfileField row = new ProjectProfileField();
        row.setProjectId(projectId);
        row.setFieldKey(fieldKey);
        row.setUid(UUID.randomUUID().toString());
        return row;
    }

    private void requireKnownKey(String fieldKey) {
        if (!FIELD_KEYS.contains(fieldKey)) {
            throw new IllegalArgumentException("未知的档案字段");
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest=ProjectProfileServiceTest
```

Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91"
git add backend/src/main/java/com/checkba/service/ProjectProfileService.java \
        backend/src/test/java/com/checkba/service/ProjectProfileServiceTest.java
git commit -m "feat(profile): 档案手填 upsert 与清空删除

写入即锁成 source=user。既有行的 uid 任何时候都不换（跨机器身份只认它）。
传空串等于删行，openedAt 删后回落建档时间。字段名走白名单，未知字段直接拒。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: 立住不变式：source=user 的字段 AI 永不覆盖，只挂 pending

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/service/ProjectProfileService.java`（Task 8 产出，在 `saveUserField` 之后追加一个方法，并补两个 import）
- Modify / Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/service/ProjectProfileServiceTest.java`（追加 3 个测试方法）

**Interfaces:**
- Consumes: `ProjectProfileFieldRepository.findByProjectIdAndFieldKey(Long, String)` / `.save(...)`；Task 8 的 `newRow` / `requireKnownKey`；Task 7 的 `render`
- Produces: `public Map<String,Object> applyAiSuggestion(Long projectId, String fieldKey, String value, Double confidence, String evidence)`

**为什么本期就写这个方法**：A 期没有任何 AI 写入方（抽取链路属 Plan 2）。但「`source='user'` 的字段锁定、AI 永不覆盖」是档案表设计的核心不变式——`pending*` 四列存在的全部理由。护栏必须先于第一个写入方存在，否则 Plan 2 接进来时只能靠人记得。**本方法 A 期零调用方，不要给它开 HTTP 端点。**

规则：

- 目标行 `source == 'user'` → 只写 `pendingValue` / `pendingConfidence` / `pendingEvidence` / `pendingAt`，**`fieldValue` 与 `source` 一个字节都不动**。律师采纳后才转正（转正动作属 Plan 2）。
- 目标行不存在，或 `source == 'ai'` → 直接写 `fieldValue`，标 `source='ai'`，带上 `confidence` / `evidence`。
- 抽取结果为空 → 什么都不写（模型这轮没抽出来，不代表要清空律师已有的值）。

---

- [ ] **Step 1: 写失败的测试**

在 `ProjectProfileServiceTest` 类末尾追加：

```java
    @Test
    void AI不覆盖律师填过的字段_只挂pending() {
        ProjectProfileField existing = row("client", "北京某某科技有限公司", "user");
        existing.setUid("uid-user");
        when(repository.findByProjectIdAndFieldKey(42L, "client")).thenReturn(Optional.of(existing));

        Map<String, Object> result =
                service.applyAiSuggestion(42L, "client", "上海某某贸易有限公司", 0.91, "股权转让协议.docx 第 1 条");

        // 这是本表的核心不变式：律师改过的字段，AI 一个字节都不许动
        assertEquals("北京某某科技有限公司", existing.getFieldValue());
        assertEquals("user", existing.getSource());
        assertNull(existing.getConfidence());
        assertNull(existing.getEvidence());

        assertEquals("上海某某贸易有限公司", existing.getPendingValue());
        assertEquals(0.91, existing.getPendingConfidence(), 0.0001);
        assertEquals("股权转让协议.docx 第 1 条", existing.getPendingEvidence());
        assertNotNull(existing.getPendingAt());
        assertEquals("uid-user", existing.getUid());

        assertEquals("北京某某科技有限公司", result.get("fieldValue"), "返回的仍是律师那份值");
        assertEquals("user", result.get("source"));
    }

    @Test
    void AI可以覆盖自己上次填的字段与未填的字段() {
        ProjectProfileField aiRow = row("matterType", "公司治理", "ai");
        aiRow.setUid("uid-ai");
        when(repository.findByProjectIdAndFieldKey(42L, "matterType")).thenReturn(Optional.of(aiRow));

        service.applyAiSuggestion(42L, "matterType", "并购交易", 0.77, "股权转让协议.docx");
        assertEquals("并购交易", aiRow.getFieldValue());
        assertEquals("ai", aiRow.getSource());
        assertEquals(0.77, aiRow.getConfidence(), 0.0001);
        assertNull(aiRow.getPendingValue(), "能直接写就不该挂 pending");

        // 未填过的字段（setUp 里 findByProjectIdAndFieldKey 默认返回 empty）
        Map<String, Object> created =
                service.applyAiSuggestion(42L, "counterparty", "某某集团", 0.5, "通知函.docx");
        assertEquals("某某集团", created.get("fieldValue"));
        assertEquals("ai", created.get("source"));
    }

    @Test
    void AI抽空时不清掉已有值() {
        ProjectProfileField existing = row("client", "北京某某科技有限公司", "user");
        when(repository.findByProjectIdAndFieldKey(42L, "client")).thenReturn(Optional.of(existing));

        Map<String, Object> result = service.applyAiSuggestion(42L, "client", "   ", 0.1, null);

        verify(repository, never()).save(any(ProjectProfileField.class));
        assertEquals("北京某某科技有限公司", result.get("fieldValue"));
        assertNull(existing.getPendingValue());
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest=ProjectProfileServiceTest
```

Expected: `BUILD FAILURE`，`[ERROR] COMPILATION ERROR`，报 `找不到符号  符号: 方法 applyAiSuggestion(long,java.lang.String,java.lang.String,double,java.lang.String)`（英文 locale 为 `cannot find symbol: method applyAiSuggestion(...)`），共 4 处调用点。

- [ ] **Step 3: 最小实现**

在 `ProjectProfileService.java` 的 import 区补两行：

```java
import java.time.LocalDateTime;
import java.util.Optional;
```

在 `saveUserField(...)` 之后追加：

```java
    /**
     * AI 抽取写入。**A 期没有调用方**（抽取链路属 Plan 2），本方法存在的意义是先把
     * 档案表的核心不变式立住并测掉：
     *
     *   source='user' 的字段锁定，AI 永不覆盖。
     *
     * AI 有新判断时挂到同一行的 pending* 四列（唯一约束是 (projectId, fieldKey)，
     * 建议不能另起一行），律师采纳后才转正。抽取结果为空时什么都不写——模型这轮没抽出来，
     * 不代表要清空律师已有的值。
     *
     * 不要给这个方法开 HTTP 端点：A 期没有任何触发 AI 抽取的入口，开了就是死端点。
     */
    public Map<String, Object> applyAiSuggestion(Long projectId, String fieldKey, String value,
                                                 Double confidence, String evidence) {
        requireKnownKey(fieldKey);
        Project project = projectRepository.findById(projectId).orElse(null);
        Optional<ProjectProfileField> found = repository.findByProjectIdAndFieldKey(projectId, fieldKey);

        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return render(fieldKey, found.orElse(null), project);
        }

        ProjectProfileField row = found.orElseGet(() -> newRow(projectId, fieldKey));
        if (SOURCE_USER.equals(row.getSource())) {
            row.setPendingValue(trimmed);
            row.setPendingConfidence(confidence);
            row.setPendingEvidence(evidence);
            row.setPendingAt(LocalDateTime.now());
        } else {
            row.setFieldValue(trimmed);
            row.setSource(SOURCE_AI);
            row.setConfidence(confidence);
            row.setEvidence(evidence);
        }
        return render(fieldKey, repository.save(row), project);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest=ProjectProfileServiceTest
```

Expected: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91"
git add backend/src/main/java/com/checkba/service/ProjectProfileService.java \
        backend/src/test/java/com/checkba/service/ProjectProfileServiceTest.java
git commit -m "feat(profile): 立住 source=user 锁定不变式，AI 只挂 pending

A 期没有 AI 写入方，但护栏必须先于第一个写入方存在：律师改过的字段，
AI 一个字节都不许动，新判断挂同一行的 pending 四列等采纳。抽空不清值。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: ProjectOverviewController：档案读写两个端点与鉴权

**Files:**
- Create **或** Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/controller/ProjectOverviewController.java`
  - 这个文件是本 plan 四个后端组共用的。**第一个执行到的组创建整文件，后续组只往构造器里加参数、往类末尾追加方法**。按既定落地顺序，Task 1-5（概览统计与动态组）先跑，所以执行本任务时该文件**大概率已存在**——Step 3 有明确的判定命令与两条分支。
- Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/controller/ProjectOverviewProfileAuthTest.java`
  - **文件名不许改**。三个后端组的鉴权测试各占一个不同文件名：`ProjectOverviewStatsAuthTest`（统计与动态组）/ `ProjectOverviewProfileAuthTest`（本组）/ `ProjectOverviewConversationsAuthTest`（会话列表组）。同名会互相覆盖，被覆盖那组的用例静默消失。
- 参考（只读不改）：`backend/src/main/java/com/checkba/controller/DdController.java:31-38`（requireXxx 助手的既有形状）、`backend/src/main/java/com/checkba/controller/AuthController.java:640`（`public static Long getUserIdFromSession(String)`）、`backend/src/main/java/com/checkba/service/ProjectMemberService.java:151/159/167-168/171/179`、`backend/src/main/java/com/checkba/config/GlobalExceptionHandler.java:69-77`、`backend/src/test/java/com/checkba/controller/DdControllerAuthTest.java:22-40`（`@InjectMocks` + `mockStatic(AuthController.class)` 的既有写法）

**Interfaces:**
- Consumes: `AuthController.getUserIdFromSession(String)`、`ProjectMemberService.hasReadPermission(Long projectId, Long userId)` / `hasWritePermission(Long, Long)` / `isClient(Long, Long)`、`ProjectProfileService.getProfile(Long)` / `saveUserField(Long, String, String)`
- Produces: `GET /api/projects/{projectId}/profile` → `{"code":0,"data":{"fields":[...]}}`；`PUT /api/projects/{projectId}/profile/{fieldKey}` 收 `{"value":"..."}` → `{"code":0,"data":{单条}}`；共用私有助手 `requireRead(Long projectId, String sessionId)` / `requireWrite(Long projectId, String sessionId)` / `ok(Map<String,Object> data)`

**共用骨架（三个后端组必须一致，不要各自发挥）**：

```java
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectOverviewController {

    private final ProjectMemberService projectMemberService;
    // ... 各组按需追加自己的 service 依赖到这个显式构造器

    public ProjectOverviewController(ProjectMemberService projectMemberService /* , ... */) {
        this.projectMemberService = projectMemberService;
    }

    /** 登录 + 读权限。不拒 CLIENT。返回 userId。 */
    private Long requireRead(Long projectId, String sessionId) { ... }

    /** 登录 + 写权限 + 拒 CLIENT。返回 userId。 */
    private Long requireWrite(Long projectId, String sessionId) { ... }
}
```

硬约束：

1. **`requireRead` / `requireWrite` 的参数序恒为 `(Long projectId, String sessionId)`（projectId 在前）**——与端点方法自己的参数顺序一致，最不容易记反。两个参数一个 `Long` 一个 `String`，写反是编译错误，但后到的组会以为「助手已存在直接复用」而调用点全错。
2. **所有端点返回类型统一 `ResponseEntity<Map<String, Object>>`**，信封统一走 `ok(...)` 助手。
3. **依赖注入统一显式构造器**，不用 `@RequiredArgsConstructor`（两种混用会出现「显式构造器 + Lombok 生成构造器」二义）。
4. **鉴权参数序是地雷**：`hasReadPermission` / `hasWritePermission` / `isClient` 三个方法都是 `(Long projectId, Long userId)`，两个参数同为 `Long`，**写反能编译通过、运行时静默返回 false**（表现是「明明是我的项目却说无权访问」）。逐个对照 `ProjectMemberService.java:151/159/171` 再写。
5. **读端点不拒 CLIENT**——档案就是给客户看的那一页。写端点 `hasWritePermission && !isClient` **两条都要显式写**：`hasWritePermission`（`ProjectMemberService.java:167-168`）虽已天然只放行 ADMIN/PARTICIPANT + owner，但 `isClient`（`:179`）是 `"CLIENT".equals(role) || "CLIENT_NAMED".equals(role) || "CLIENT_GENERIC".equals(role)` 三个字面量的显式 or、**不是 `startsWith("CLIENT")`**，将来新增 `CLIENT_*` 角色时会漏判，显式双判是第二道闸。
6. **`@RequestHeader` 一律 `required = false`**：`getUserIdFromSession` 内部已处理设备令牌前缀 / local-mode 免登 / 普通 session 三种身份，local-mode 下请求可以完全不带这个头；写成 `required = true` 会让桌面端整条链 500。
7. **cloud profile 是多租户共库**，每个端点都必须过一次归属校验，一处都不能省。
8. **HTTP 状态码不引 401/403**：未登录/越权一律抛 `IllegalArgumentException`，由 `GlobalExceptionHandler.java:69-77` 转成 HTTP 200 + `{"code":1,"message":"..."}`，与全站 90+ 端点同口径、与前端 `services/api.js` 按 `code` 解的包装器同口径。本任务的最后两条 MockMvc 用例把这个 HTTP 层表现钉死。

---

- [ ] **Step 1: 写失败的测试**

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/controller/ProjectOverviewProfileAuthTest.java`：

```java
package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 锁定项目概览页档案端点的鉴权口径：
 * 读走 hasReadPermission 且不拒 CLIENT（档案就是给客户看的那一页）；
 * 写走 hasWritePermission 且显式再判一次 !isClient。
 *
 * 参数序地雷：三个判权方法都是 (projectId, userId)，写反能编译通过、运行时静默 false。
 *
 * 关于 @InjectMocks：ProjectOverviewController 是四个后端组共用的文件，构造器参数会
 * 随兄弟组的落地逐个变长（stats / conversations 各加一个 service）。Mockito 选最大构造器、
 * 按类型匹配，匹配不到的参数传 null——本类两个端点从不触碰其他依赖，null 无害。
 * **不要在这里 @Mock 兄弟组的 service**：那会让本任务的测试在只执行本组时编译失败。
 * 既有 DdControllerAuthTest:22-30 也是这个写法。
 */
@ExtendWith(MockitoExtension.class)
class ProjectOverviewProfileAuthTest {

    @Mock
    private ProjectMemberService projectMemberService;
    @Mock
    private ProjectProfileService projectProfileService;

    @InjectMocks
    private ProjectOverviewController controller;

    /**
     * standaloneSetup 不加载 Spring 上下文，只把控制器与全局异常处理器串起来，
     * 用来验证「抛 IllegalArgumentException → HTTP 200 + code:1」这条全站口径。
     */
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * MockMvc 的 MockHttpServletResponse 默认字符集不是 UTF-8，直接
     * getContentAsString() 会把中文读成乱码，所以按字节自己解一次。
     */
    private static String utf8Body(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void 未登录时读档案被拒() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(null);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.getProfile(42L, "sess"));
            assertEquals("未登录", e.getMessage());
        }
    }

    @Test
    void 非项目成员读档案被拒() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(false);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.getProfile(42L, "sess"));
            assertEquals("无权访问该项目", e.getMessage());
        }
    }

    @Test
    void 成员读档案拿到信封与五条字段() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(true);
            when(projectProfileService.getProfile(42L)).thenReturn(List.of(
                    Map.of("fieldKey", "client"), Map.of("fieldKey", "matterType"),
                    Map.of("fieldKey", "openedAt"), Map.of("fieldKey", "nextStep"),
                    Map.of("fieldKey", "counterparty")));

            ResponseEntity<Map<String, Object>> res = controller.getProfile(42L, "sess");
            assertEquals(200, res.getStatusCode().value());
            Map<String, Object> body = res.getBody();
            assertNotNull(body);
            assertEquals(0, body.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            assertEquals(5, ((List<?>) data.get("fields")).size());
        }
    }

    @Test
    void CLIENT可以读档案() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(projectMemberService.hasReadPermission(42L, 9L)).thenReturn(true);
            when(projectProfileService.getProfile(42L)).thenReturn(List.of());

            assertNotNull(controller.getProfile(42L, "sess"));
            // 读端点不许调 isClient——档案就是给客户看的那一页
            verify(projectMemberService, never()).isClient(anyLong(), anyLong());
        }
    }

    @Test
    void 只读成员写档案被拒() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(42L, 7L)).thenReturn(false);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.saveProfileField(42L, "client", Map.of("value", "某某公司"), "sess"));
            assertEquals("无权修改该项目", e.getMessage());
            verify(projectProfileService, never()).saveUserField(anyLong(), anyString(), anyString());
        }
    }

    @Test
    void CLIENT即使有写权限也不能写档案() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(projectMemberService.hasWritePermission(42L, 9L)).thenReturn(true);
            when(projectMemberService.isClient(42L, 9L)).thenReturn(true);
            assertThrows(IllegalArgumentException.class,
                    () -> controller.saveProfileField(42L, "client", Map.of("value", "某某公司"), "sess"));
        }
    }

    @Test
    void 有写权限的成员写档案并拿回同形状单条() {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("fieldKey", "client");
        saved.put("label", "客户");
        saved.put("fieldValue", "北京某某科技有限公司");
        saved.put("source", "user");
        saved.put("confidence", null);
        saved.put("evidence", null);
        saved.put("updatedAt", "2026-08-08T10:11:12");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(42L, 7L)).thenReturn(true);
            when(projectMemberService.isClient(42L, 7L)).thenReturn(false);
            when(projectProfileService.saveUserField(42L, "client", "北京某某科技有限公司")).thenReturn(saved);

            ResponseEntity<Map<String, Object>> res = controller.saveProfileField(
                    42L, "client", Map.of("value", "北京某某科技有限公司"), "sess");
            Map<String, Object> body = res.getBody();
            assertNotNull(body);
            assertEquals(0, body.get("code"));
            assertSame(saved, body.get("data"));
        }
    }

    @Test
    void 请求体缺失时按清空处理() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasWritePermission(42L, 7L)).thenReturn(true);
            when(projectMemberService.isClient(42L, 7L)).thenReturn(false);
            when(projectProfileService.saveUserField(42L, "nextStep", null))
                    .thenReturn(new LinkedHashMap<>());

            assertNotNull(controller.saveProfileField(42L, "nextStep", null, "sess"));
            verify(projectProfileService).saveUserField(42L, "nextStep", null);
        }
    }

    @Test
    void 未登录读档案在HTTP层是200加code1不是401() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(null);

            MvcResult result = mvc().perform(get("/api/projects/42/profile")
                            .header("X-Session-Id", "sess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andReturn();
            assertTrue(utf8Body(result).contains("未登录"), utf8Body(result));
        }
    }

    @Test
    void CLIENT写档案在HTTP层是200加code1不是403() throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(projectMemberService.hasWritePermission(42L, 9L)).thenReturn(true);
            when(projectMemberService.isClient(42L, 9L)).thenReturn(true);

            MvcResult result = mvc().perform(put("/api/projects/42/profile/client")
                            .header("X-Session-Id", "sess")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"某某公司\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1))
                    .andReturn();
            assertTrue(utf8Body(result).contains("无权修改该项目"), utf8Body(result));
        }
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest=ProjectOverviewProfileAuthTest
```

Expected（两种之一，取决于兄弟组是否已建好文件）：
- 文件还不存在：`BUILD FAILURE`，`[ERROR] COMPILATION ERROR`，报 `找不到符号  符号: 类 ProjectOverviewController`（英文 locale 为 `cannot find symbol: class ProjectOverviewController`）。
- 文件已由统计与动态组建好：`BUILD FAILURE`，`[ERROR] COMPILATION ERROR`，报 `找不到符号  符号: 方法 getProfile(long,java.lang.String)` 与 `找不到符号  符号: 方法 saveProfileField(...)`。

- [ ] **Step 3: 判定 controller 文件是否已存在**

Run:
```bash
ls -l "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/controller/ProjectOverviewController.java"
```

- 输出 `No such file or directory` → 走 **Step 4 分支 A**（新建整文件）。
- 输出一行文件信息 → 走 **Step 4 分支 B**（只追加）。此时再确认一遍助手签名，参数序必须是 `(Long projectId, String sessionId)`：

```bash
grep -n "private Long requireRead\|private Long requireWrite\|private ResponseEntity<Map<String, Object>> ok\|public ProjectOverviewController" "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/controller/ProjectOverviewController.java"
```

若 grep 出来的是 `requireRead(String sessionId, Long projectId)` 这种反过来的签名，说明有人违反了共用骨架约定：先把助手签名与它现有的全部调用点一起改成 `(Long projectId, String sessionId)`，再继续。

另外：若 `grep -n "private Long requireWrite"` 零输出（统计与动态组的 Task 4 建的文件就是零输出——它只写了 `requireRead` / `ok`，并把 `requireWrite` 留给本组随 `PUT /profile/{fieldKey}` 一起补），**本组负责补上它**，具体见 Step 4 分支 B 第 3 条。

- [ ] **Step 4: 最小实现**

**分支 A（文件不存在，本组是第一个到场的）**——新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/controller/ProjectOverviewController.java`：

```java
package com.checkba.controller;

import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目概览页的后端出口（四个后端组共用一个文件：stats / profile / conversations / tasks）。
 *
 * 追加新端点的约定：只往构造器加参数、往类末尾加方法，
 * requireRead / requireWrite / ok 三个助手直接复用，不要各写一份。
 *
 * 路径与 ProjectController 不相交：那边占了 /api/projects（POST）、/{id}、/my、
 * /{id}/local-path、/ensure-addin-default、/open-local。
 *
 * 鉴权口径（两套并存且抄错不报错，写死在这里）：
 *   读 → hasReadPermission，不拒 CLIENT（档案与统计就是给客户看的那一页）
 *   写 → hasWritePermission 且显式再判一次 !isClient
 *
 * 地雷：三个判权方法都是 (Long projectId, Long userId)，两个参数同为 Long，
 * 写反能编译通过、运行时静默返回 false。见 ProjectMemberService.java:151/159/171。
 *
 * 状态码口径：未登录 / 越权一律抛 IllegalArgumentException，由
 * GlobalExceptionHandler.java:69-77 转成 HTTP 200 + {"code":1,"message":...}，
 * 与全站 90+ 端点一致。本类不引 401/403。
 *
 * 本类一律返回自己组装的 Map，绝不下发实体——GET /api/projects/{id} 现在返回裸实体
 * 把两个 companyInfoJson 下发给包括 CLIENT 在内的全部成员，那是待修项，不要照抄。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectOverviewController {

    private final ProjectMemberService projectMemberService;
    private final ProjectProfileService projectProfileService;

    public ProjectOverviewController(ProjectMemberService projectMemberService,
                                     ProjectProfileService projectProfileService) {
        this.projectMemberService = projectMemberService;
        this.projectProfileService = projectProfileService;
    }

    // ==================== 越权校验 ====================

    /** 登录 + 读权限。不拒 CLIENT。返回 userId。参数序恒为 (projectId, sessionId)。 */
    private Long requireRead(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException("无权访问该项目");
        }
        return userId;
    }

    /** 登录 + 写权限 + 拒 CLIENT。返回 userId。参数序恒为 (projectId, sessionId)。 */
    private Long requireWrite(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        // hasWritePermission 已天然只放行 ADMIN/PARTICIPANT + owner，但 isClient 是三个字面量的
        // 显式 or（不是 startsWith("CLIENT")），新增 CLIENT_* 角色时会漏判——显式双判是第二道闸。
        if (projectId == null
                || !projectMemberService.hasWritePermission(projectId, userId)
                || projectMemberService.isClient(projectId, userId)) {
            throw new IllegalArgumentException("无权修改该项目");
        }
        return userId;
    }

    // ==================== 项目档案 ====================

    /** 档案读：固定五个字段全量返回，未填的也返回、值为 null。不拒 CLIENT。 */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getProfile(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireRead(projectId, sessionId);
        List<Map<String, Object>> fields = projectProfileService.getProfile(projectId);
        return ok(Map.of("fields", fields));
    }

    /** 档案手填单字段（A 期唯一的写入通道）。value 为空即清空该字段。 */
    @PutMapping("/profile/{fieldKey}")
    public ResponseEntity<Map<String, Object>> saveProfileField(
            @PathVariable Long projectId,
            @PathVariable String fieldKey,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWrite(projectId, sessionId);
        String value = body == null ? null : body.get("value");
        return ok(projectProfileService.saveUserField(projectId, fieldKey, value));
    }

    /**
     * 统一信封。外层可以用 Map.of（data 恒非 null），
     * 但 data 内部的字段元素含 null 值，必须由服务层用 LinkedHashMap 构造。
     */
    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }
}
```

**分支 B（文件已存在）**——只做三处最小编辑，其余一行不动：

1. import 区补一行（若已有则跳过）：
```java
import com.checkba.service.ProjectProfileService;
```
2. 字段区在既有 `private final ...` 之后补一行，并给构造器加一个参数与一行赋值：
```java
    private final ProjectProfileService projectProfileService;
```
```java
    // 构造器签名变成（既有参数保持原样、原顺序，新参数追加在末尾）：
    // public ProjectOverviewController(ProjectMemberService projectMemberService,
    //                                  <既有其它 service>,
    //                                  ProjectProfileService projectProfileService) {
    //     ...
        this.projectProfileService = projectProfileService;
    // }
```
3. 在类末尾的 `ok(...)` 助手**之前**，依次粘贴分支 A 里的 `requireWrite` 与 `// ==================== 项目档案 ====================` 那一段（两个 `@GetMapping("/profile")` / `@PutMapping("/profile/{fieldKey}")` 方法及其注释）；`requireRead` / `ok` 已存在，**不要再写一份**。若 `List` 的 import 缺失则补 `import java.util.List;`。

要粘贴的 `requireWrite`（放在既有 `requireRead` 之后、项目档案那一段之前）：

```java
    /** 登录 + 写权限 + 拒 CLIENT。返回 userId。参数序恒为 (projectId, sessionId)。 */
    private Long requireWrite(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        // hasWritePermission 已天然只放行 ADMIN/PARTICIPANT + owner，但 isClient 是三个字面量的
        // 显式 or（不是 startsWith("CLIENT")），新增 CLIENT_* 角色时会漏判——显式双判是第二道闸。
        if (projectId == null
                || !projectMemberService.hasWritePermission(projectId, userId)
                || projectMemberService.isClient(projectId, userId)) {
            throw new IllegalArgumentException("无权修改该项目");
        }
        return userId;
    }
```

（若 Step 3 的 grep 显示 `private Long requireWrite` 已存在——说明另有组抢先补过——则跳过这段，只粘贴项目档案那一段。）

- [ ] **Step 5: 跑测试确认通过**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest=ProjectOverviewProfileAuthTest
```

Expected: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。

- [ ] **Step 6: 跑本组三个测试类，确认互不干扰**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -f "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/pom.xml" test -Dtest='ProjectProfileFieldRepositoryTest,ProjectProfileServiceTest,ProjectOverviewProfileAuthTest'
```

Expected: `Tests run: 27, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`（5 + 12 + 10）。

- [ ] **Step 7: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91"
git add backend/src/main/java/com/checkba/controller/ProjectOverviewController.java \
        backend/src/test/java/com/checkba/controller/ProjectOverviewProfileAuthTest.java
git commit -m "feat(profile): 概览页档案读写端点与鉴权

GET /api/projects/{id}/profile 与 PUT /api/projects/{id}/profile/{fieldKey}，
一律信封 {code,data}、一律返回自己组装的 Map 不下发实体。
读不拒 CLIENT（档案就是给客户看的那一页），写 hasWritePermission 且显式再判 !isClient。
未登录/越权走 IllegalArgumentException，HTTP 层是 200 + code:1，与全站同口径，不引 401/403。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

### Task 11: 给 project_ai_message 加两条索引，并在 H2 与 MySQL8 上各验一次

`project_ai_message` 实体上**零个 `@Index`**，而四个 profile 全是 `ddl-auto: update`、无 flyway/liquibase、无 schema.sql——线上这张表只有主键索引。Task 12 要加的项目级汇总查询是 `WHERE project_id = ? GROUP BY conversation_id` + 四个按 `conversation_id` 的标量子查询，不加索引就是全表扫描套全表扫描。

D3 已裁定这条索引**留在本切片**：只加索引，不动任何列的名字/类型/可空性，与「字段只增不减不改类型」不冲突。

D9 要求 schema 变更不能只在 H2 上验——桌面壳开发态默认跑 prod profile（MySQL8），打包态才跑 desktop（H2 file, MODE=PostgreSQL），本机验证环境和线上不是同一种库。所以本任务把同一条断言在两种库上各跑一次。

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/model/entity/ProjectAiMessage.java:12`（`@Table(name = "project_ai_message")` 这一行）
- Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectAiMessageIndexTest.java`（新建）
- Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectAiMessageIndexMysqlTest.java`（新建，默认跳过，只在设了环境变量 `AWD_MYSQL_SCHEMA_CHECK=1` 时运行）

**Interfaces:**
- Consumes: 无（纯 schema 变更）
- Produces: 两条数据库索引 `idx_ai_message_project_created (project_id, created_at)` 与 `idx_ai_message_conversation_created (conversation_id, created_at)`，由 Hibernate `ddl-auto: update` 在四个 profile 上自动创建。被 Task 12 的 `findProjectConversationSummaries` 与既有 `findByConversationIdOrderByCreatedAtAsc` 使用。

---

- [ ] **Step 1: 写两个失败的测试（H2 一个、MySQL8 一个）**

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectAiMessageIndexTest.java`：

```java
package com.checkba.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死 project_ai_message 的索引定义。
 *
 * 背景：这张表长期零 @Index，而四个 profile 全是 ddl-auto: update、无 flyway/liquibase、
 * 无 schema.sql —— 线上只有主键索引，概览页按 projectId 铺全项目会话是全表扫描。
 * 索引被谁顺手删掉不会有任何报错，只会悄悄变慢，所以用测试钉住。
 *
 * 环境：内存 H2（MODE=PostgreSQL）+ NON_KEYWORDS=VALUE，表结构交给 Hibernate ddl-auto 建。
 * H2 里未加引号的标识符一律存成大写，故断言用大写字面量。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-message-index-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectAiMessageIndexTest {

    @Autowired
    private TestEntityManager em;

    @SuppressWarnings("unchecked")
    private List<String> indexNames() {
        List<Object> rows = em.getEntityManager().createNativeQuery(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME) = 'PROJECT_AI_MESSAGE'")
                .getResultList();
        return rows.stream().map(String::valueOf)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    @Test
    void 按项目铺会话的复合索引存在() {
        List<String> names = indexNames();
        assertTrue(names.contains("IDX_AI_MESSAGE_PROJECT_CREATED"),
                "缺 (project_id, created_at) 索引，项目级会话汇总会退化成全表扫描；实际索引=" + names);
    }

    @Test
    void 按会话取正文与标量子查询的复合索引存在() {
        List<String> names = indexNames();
        assertTrue(names.contains("IDX_AI_MESSAGE_CONVERSATION_CREATED"),
                "缺 (conversation_id, created_at) 索引，四个标量子查询与历史回放都会全表扫描；实际索引=" + names);
    }
}
```

同时新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectAiMessageIndexMysqlTest.java`（同一条断言，换到 MySQL8 上跑；没有环境变量时整类跳过，所以不会拖慢 `mvn test` 也不会在 CI 上红）：

```java
package com.checkba.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 与 ProjectAiMessageIndexTest 同一条断言，换到 MySQL8 上再验一次。
 *
 * 为什么必须验两次：桌面壳开发态默认跑 prod profile（MySQL8），打包态才跑 desktop
 * （H2 file, MODE=PostgreSQL）—— 本机改 schema 的验证环境和线上不是同一种库。
 * 且四个 profile 全是 ddl-auto: update、无迁移体系，索引能不能被 update 模式补出来
 * 只有在真 MySQL 上跑一遍才知道。
 *
 * 默认跳过。跑法见本任务 Step 5（起一个一次性 docker MySQL8，再带
 * AWD_MYSQL_SCHEMA_CHECK=1 跑本类）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "AWD_MYSQL_SCHEMA_CHECK", matches = "1")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13306/checkba_schema_check?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root",
        "spring.datasource.password=checkba123",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        // 与线上 prod profile 完全一致：update，不是 create-drop
        "spring.jpa.hibernate.ddl-auto=update"
})
class ProjectAiMessageIndexMysqlTest {

    @Autowired
    private TestEntityManager em;

    @Test
    @SuppressWarnings("unchecked")
    void ddl_auto_update_在MySQL8上也把两条索引建出来() {
        List<Object> rows = em.getEntityManager().createNativeQuery(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_ai_message'")
                .getResultList();
        List<String> names = rows.stream().map(String::valueOf)
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toList());

        assertTrue(names.contains("IDX_AI_MESSAGE_PROJECT_CREATED"),
                "MySQL8 上 ddl-auto=update 没建出 (project_id, created_at) 索引；实际索引=" + names);
        assertTrue(names.contains("IDX_AI_MESSAGE_CONVERSATION_CREATED"),
                "MySQL8 上 ddl-auto=update 没建出 (conversation_id, created_at) 索引；实际索引=" + names);
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest='ProjectAiMessageIndexTest,ProjectAiMessageIndexMysqlTest' -DfailIfNoTests=false
```

Expected：`ProjectAiMessageIndexTest` 报 `Tests run: 2, Failures: 2`，两条都是 `AssertionFailedError`，消息就是

```
缺 (project_id, created_at) 索引，项目级会话汇总会退化成全表扫描；实际索引=[PRIMARY_KEY_2C]
```

（`PRIMARY_KEY_2C` 是 H2 给主键起的名字，这一行同时证明「这张表现在真的只有主键索引」。）
`ProjectAiMessageIndexMysqlTest` 显示 `Tests run: 0, Skipped: 1`——没有 `AWD_MYSQL_SCHEMA_CHECK` 就整类跳过，符合预期。

> 本机默认 JDK 是 25，直接 `mvn` 会 SIGBUS。`JAVA_HOME=` 前缀一次都不能省。

- [ ] **Step 3: 最小实现**

编辑 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/model/entity/ProjectAiMessage.java`，把第 12 行

```java
@Table(name = "project_ai_message")
```

整行替换为：

```java
@Table(name = "project_ai_message", indexes = {
        // 概览页按 projectId 铺全项目会话（GROUP BY conversationId + ORDER BY MAX(createdAt)）。
        // 加索引前线上只有主键索引，这条查询是全表扫描。
        @Index(name = "idx_ai_message_project_created", columnList = "project_id, created_at"),
        // 会话正文回放与四个按 conversationId 的标量子查询。
        @Index(name = "idx_ai_message_conversation_created", columnList = "conversation_id, created_at")
})
```

无需改 import：文件头第 3 行已是 `import jakarta.persistence.*;`，`@Index` 已覆盖。`columnList` 必须写**物理列名**（snake_case）——Spring Boot 默认物理命名策略把 `projectId` 转成 `project_id`，写驼峰会建不出索引且不报错。

- [ ] **Step 4: 跑测试确认通过（H2）**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectAiMessageIndexTest -DfailIfNoTests=false
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: 在 MySQL8 上再验一次（D9）**

先确认 Docker daemon 在跑（本机 CLI 装了但 daemon 可能没起，报 `failed to connect to the docker API` 就是没起，去 Launchpad 打开 Docker Desktop 等它变绿）：

```bash
docker version --format '{{.Server.Version}}'
```

起一个一次性 MySQL8 并等它就绪：

```bash
docker run -d --name awd-mysql-schema-check \
  -e MYSQL_ROOT_PASSWORD=checkba123 \
  -e MYSQL_DATABASE=checkba_schema_check \
  -p 13306:3306 mysql:8.0 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
until docker exec awd-mysql-schema-check mysqladmin ping -h127.0.0.1 -uroot -pcheckba123 --silent >/dev/null 2>&1; do sleep 2; done; echo MYSQL_READY
```

带环境变量跑 MySQL 版断言（Hibernate 会以 `ddl-auto=update` 在这个空库上建全套表，与 prod 启动时做的事完全一样）：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && AWD_MYSQL_SCHEMA_CHECK=1 JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectAiMessageIndexMysqlTest -DfailIfNoTests=false
```

再用 mysql CLI 肉眼复核一遍列顺序：

```bash
docker exec awd-mysql-schema-check mysql -uroot -pcheckba123 checkba_schema_check \
  -e "SHOW INDEX FROM project_ai_message;"
```

**验收标准（三条都要满足）：**
1. mvn 输出 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（不是 `Skipped: 1`——那说明环境变量没传进去）。
2. `SHOW INDEX` 的结果里能看到 `idx_ai_message_project_created` 两行，`Seq_in_index=1` 的 `Column_name` 是 `project_id`、`=2` 的是 `created_at`；`idx_ai_message_conversation_created` 同理是 `conversation_id` / `created_at`。列顺序反了等于索引白加。
3. 全过程没有 `Row size too large` 一类错误。

> 若 `ddl-auto=update` 在 MySQL8 上因为**别的实体**建表失败（本次没碰的表），那是既有的 prod schema 问题，不是本任务引入的：把报错原文记下来单独开 issue，然后只按第 2 条用 `SHOW INDEX` 判定本任务的验收，不要为了让它过去改任何别的实体。

用完立刻销毁容器（它占着 13306）：

```bash
docker rm -f awd-mysql-schema-check
```

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add backend/src/main/java/com/checkba/model/entity/ProjectAiMessage.java backend/src/test/java/com/checkba/repository/ProjectAiMessageIndexTest.java backend/src/test/java/com/checkba/repository/ProjectAiMessageIndexMysqlTest.java && git commit -m "perf(ai): project_ai_message 补 (project_id,created_at) 与 (conversation_id,created_at) 索引

这张表此前零 @Index，四个 profile 全是 ddl-auto: update 且无迁移体系，
线上只有主键索引；概览页按 projectId 铺全项目会话是全表扫描。
新增测试用 INFORMATION_SCHEMA 钉住两条索引，防止后续被顺手删掉；
同一条断言在 H2 与 MySQL8 上各跑一次（MySQL 那份默认跳过，
需 AWD_MYSQL_SCHEMA_CHECK=1 + 本地 MySQL8 才启用）。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 12: 新增项目级会话汇总查询（复合游标）与运行状态批量查询

既有 `ProjectAiMessageRepository.findConversationSummaries`（`:20-27`）同时按 `projectId` **和 `userId`** 过滤，语义是「我在这个项目里的会话」。概览页要的是「这个项目的全部会话」，所以**另起一条**、去掉 userId 条件、多回一列发起人 id、加游标 HAVING。**既有那条一行都不改**——它服务 `/api/ai/conversations`，动它会影响整个 AI 面板。

**游标必须是复合的（D2）**：`(MAX(createdAt), conversationId)` 两维。只用 `MAX(createdAt)` 单维时，两个会话的最后活跃时间相同（同批导入、同毫秒落库、MySQL 秒级截断）就会在翻页时**永久丢掉其中一条**——第一页返回其中一条并把它的时间当游标，第二页的 `MAX < :before` 把另一条也一起排除了。所以：

- `ORDER BY MAX(m.createdAt) DESC, m.conversationId DESC`
- `HAVING (:before IS NULL OR MAX(m.createdAt) < :before OR (MAX(m.createdAt) = :before AND m.conversationId < :beforeId))`

`beforeId` 传 null 时第三个分支恒为 unknown，整条 HAVING 退化成严格小于——即老行为，向后兼容（前端只回传 `before` 不回传 `beforeId` 时不会报错，只是同秒会话仍可能丢，见 Task 14 的说明）。

`AgentRunRecordRepository` 同时补一个 `findByConversationIdIn`，供服务层批量取运行状态（防 N+1）。

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/repository/ProjectAiMessageRepository.java:29`（在 `void deleteByConversationIdAndCreatedAtAfter(...)` 这一行**之前**插入）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/repository/AgentRunRecordRepository.java:16`（在 `List<AgentRunRecord> findByStatus(String status);` **之后**追加）
- Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectConversationSummaryQueryTest.java`（新建）

**Interfaces:**
- Consumes: 既有实体 `com.checkba.model.entity.ProjectAiMessage`（projectId / conversationId / conversationTitle / role / content / userId / createdAt）与 `com.checkba.model.entity.AgentRunRecord`（conversationId / status / projectId / updatedAt），**两个都不加字段**
- Produces:
  - `List<Object[]> ProjectAiMessageRepository.findProjectConversationSummaries(Long projectId, java.time.LocalDateTime before, String beforeId)`，行形状 `[conversationId(String), updatedAt(java.time.LocalDateTime), lastContent(String), conversationTitle(String), firstUserMessage(String), ownerUserId(java.lang.Long)]`，按 `MAX(createdAt) DESC, conversationId DESC` 排序；`before` 为 null 表示第一页
  - `List<AgentRunRecord> AgentRunRecordRepository.findByConversationIdIn(java.util.Collection<String> conversationIds)`

---

- [ ] **Step 1: 写失败的测试**

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/repository/ProjectConversationSummaryQueryTest.java`：

```java
package com.checkba.repository;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.ProjectAiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 项目级会话汇总查询（概览页的 AI 对话历史列表层）。
 *
 * 与既有 findConversationSummaries 的唯一语义差别：去掉 userId 条件 —— 列表层按项目
 * 全员可见。正文层仍走 canUseConversation，本查询一行正文都不下发给外部。
 *
 * 游标是 (MAX(createdAt), conversationId) 两维：只用时间一维时，两个会话最后活跃时间
 * 相同就会在翻页时永久丢掉其中一条。「同一时刻落库的两个会话」那条用例专门钉这个。
 *
 * 环境：内存 H2（MODE=PostgreSQL）+ NON_KEYWORDS=VALUE。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-conv-summary-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectConversationSummaryQueryTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 8, 10, 0, 0);

    @Autowired
    private ProjectAiMessageRepository messageRepository;

    @Autowired
    private AgentRunRecordRepository runRecordRepository;

    private void msg(Long projectId, Long userId, String conversationId,
                     String role, String content, String title, LocalDateTime createdAt) {
        ProjectAiMessage m = new ProjectAiMessage();
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setConversationTitle(title);
        m.setCreatedAt(createdAt);
        messageRepository.save(m);
    }

    @BeforeEach
    void seed() {
        // 项目 1、会话 c-old：发起人 7 号，有 LLM 生成的标题
        msg(1L, 7L, "c-old", "USER", "股东会通知的届次对不对", "股东会材料核查", BASE);
        msg(1L, 7L, "c-old", "ASSISTANT", "已核对通知与决议的届次", null, BASE.plusMinutes(1));
        // 项目 1、会话 c-new：发起人 9 号（另一个人），无标题
        msg(1L, 9L, "c-new", "USER", "帮我起草一份股权转让协议", null, BASE.plusHours(1));
        // 项目 1、两个会话的最后活跃时间完全相同 —— 单字段游标会在这里丢数据
        msg(1L, 7L, "c-tie-a", "USER", "同一时刻落库的甲", null, BASE.plusHours(2));
        msg(1L, 7L, "c-tie-b", "USER", "同一时刻落库的乙", null, BASE.plusHours(2));
        // 项目 2：不能被项目 1 的查询捞到
        msg(2L, 7L, "c-other", "USER", "别的项目", null, BASE.plusHours(3));
    }

    @Test
    void 列出项目全部会话_不按发起人过滤_按最近活跃与会话id倒序() {
        List<Object[]> rows = messageRepository.findProjectConversationSummaries(1L, null, null);

        assertEquals(4, rows.size(), "项目 1 有四个会话，且不该按 userId 过滤掉别人发起的那些");
        assertEquals("c-tie-b", rows.get(0)[0], "同时刻的两个会话按 conversationId 倒序，b 在 a 前");
        assertEquals("c-tie-a", rows.get(1)[0]);
        assertEquals("c-new", rows.get(2)[0]);
        assertEquals("c-old", rows.get(3)[0]);
    }

    @Test
    void 行形状与类型固定为六列() {
        Object[] row = messageRepository.findProjectConversationSummaries(1L, null, null).get(3); // c-old

        assertEquals(6, row.length);
        assertEquals("c-old", row[0]);
        assertInstanceOf(LocalDateTime.class, row[1], "updatedAt 必须是 LocalDateTime，服务层要直接强转");
        assertEquals(BASE.plusMinutes(1), row[1], "updatedAt = 该会话最后一条消息的时间");
        assertEquals("已核对通知与决议的届次", row[2], "lastContent = 最后一条消息正文");
        assertEquals("股东会材料核查", row[3], "conversationTitle = 最早那条非空标题");
        assertEquals("股东会通知的届次对不对", row[4], "firstUserMessage = 最早那条 USER 消息");
        assertInstanceOf(Long.class, row[5], "ownerUserId 必须是 Long，服务层要直接强转");
        assertEquals(7L, row[5]);
    }

    @Test
    void 无标题会话的标题列为空_由服务层回退到清洗后的正文() {
        Object[] row = messageRepository.findProjectConversationSummaries(1L, null, null).get(2); // c-new
        assertNull(row[3], "c-new 没有 conversationTitle");
        assertEquals(9L, row[5], "发起人是 9 号，不是当前登录用户");
    }

    @Test
    void 复合游标_两个会话最后活跃时间完全相同时翻页一条都不丢() {
        List<Object[]> page1 = messageRepository.findProjectConversationSummaries(1L, null, null);
        LocalDateTime cursorAt = (LocalDateTime) page1.get(0)[1];
        String cursorId = (String) page1.get(0)[0];   // c-tie-b

        List<Object[]> page2 = messageRepository.findProjectConversationSummaries(1L, cursorAt, cursorId);

        assertEquals(3, page2.size(), "只应排除游标行本身");
        assertEquals("c-tie-a", page2.get(0)[0],
                "与游标同一时刻、conversationId 更小的那个会话必须还在 —— 单字段游标会把它永久丢掉");
        assertEquals("c-new", page2.get(1)[0]);
        assertEquals("c-old", page2.get(2)[0]);
    }

    @Test
    void 只传时间游标不传会话id时退化成严格小于_向后兼容() {
        List<Object[]> page = messageRepository.findProjectConversationSummaries(1L, BASE.plusHours(2), null);

        assertEquals(2, page.size(), "beforeId 缺失时第三个分支恒不成立，等于老的单字段行为");
        assertEquals("c-new", page.get(0)[0]);
        assertEquals("c-old", page.get(1)[0]);
    }

    @Test
    void 游标过滤_只回严格早于before的会话() {
        List<Object[]> page = messageRepository.findProjectConversationSummaries(1L, BASE.plusMinutes(30), null);

        assertEquals(1, page.size());
        assertEquals("c-old", page.get(0)[0], "c-new 与两个 tie 会话的最后活跃时间都晚于游标，应被排除");
    }

    @Test
    void 运行状态批量取_按会话id集合一次查完() {
        AgentRunRecord running = new AgentRunRecord();
        running.setConversationId("c-new");
        running.setStatus("RUNNING");
        running.setProjectId(1L);
        running.setUpdatedAt(BASE.plusHours(1));
        runRecordRepository.save(running);

        List<AgentRunRecord> found = runRecordRepository.findByConversationIdIn(List.of("c-new", "c-old"));

        assertEquals(1, found.size(), "c-old 没有运行记录，服务层据此给 null");
        assertEquals("c-new", found.get(0).getConversationId());
        assertEquals("RUNNING", found.get(0).getStatus());
    }

    @Test
    void 运行状态批量取_空集合不炸() {
        assertTrue(runRecordRepository.findByConversationIdIn(List.of()).isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectConversationSummaryQueryTest -DfailIfNoTests=false
```

Expected: `BUILD FAILURE` + `COMPILATION ERROR`，报两处 `cannot find symbol`：
```
symbol:   method findProjectConversationSummaries(long,java.time.LocalDateTime,java.lang.String)
location: variable messageRepository of type com.checkba.repository.ProjectAiMessageRepository
symbol:   method findByConversationIdIn(java.util.List<java.lang.String>)
location: variable runRecordRepository of type com.checkba.repository.AgentRunRecordRepository
```

- [ ] **Step 3: 最小实现**

**3a.** 编辑 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/repository/ProjectAiMessageRepository.java`，在第 29 行 `void deleteByConversationIdAndCreatedAtAfter(...)` **之前**插入：

```java
    /**
     * 项目级会话汇总（概览页用）：与上面的 findConversationSummaries 唯一的差别是
     * 去掉 userId 条件，改成「这个项目的全部会话」，并多回一列发起人 id。
     * 上面那条服务 /api/ai/conversations，一行都不改。
     *
     * 分页是游标不是 offset，且游标是 (MAX(createdAt), conversationId) 两维：
     * 只用时间一维时，两个会话最后活跃时间相同（同批导入 / 同毫秒落库 / MySQL 秒级截断）
     * 会在翻下一页时永久丢掉其中一条。beforeId 传 null 则第三个分支恒不成立，
     * 整条 HAVING 退化成严格小于（老行为，向后兼容）。
     *
     * limit 只能在 Java 层做 —— 这条 JPQL 有 4 个标量子查询 + GROUP BY + HAVING，
     * 套 Pageable 会逼出手写 countQuery 或改两段式。
     *
     * Returns: [conversationId, updatedAt, lastContent, conversationTitle, firstUserMessage, ownerUserId]
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT m.conversationId, MAX(m.createdAt), " +
        "(SELECT m2.content FROM ProjectAiMessage m2 WHERE m2.conversationId = m.conversationId ORDER BY m2.createdAt DESC LIMIT 1), " +
        "(SELECT m3.conversationTitle FROM ProjectAiMessage m3 WHERE m3.conversationId = m.conversationId AND m3.conversationTitle IS NOT NULL ORDER BY m3.createdAt ASC LIMIT 1), " +
        "(SELECT m4.content FROM ProjectAiMessage m4 WHERE m4.conversationId = m.conversationId AND m4.role = 'USER' ORDER BY m4.createdAt ASC LIMIT 1), " +
        "(SELECT m5.userId FROM ProjectAiMessage m5 WHERE m5.conversationId = m.conversationId ORDER BY m5.createdAt ASC LIMIT 1) " +
        "FROM ProjectAiMessage m WHERE m.projectId = :projectId " +
        "GROUP BY m.conversationId " +
        "HAVING (:before IS NULL OR MAX(m.createdAt) < :before " +
        "        OR (MAX(m.createdAt) = :before AND m.conversationId < :beforeId)) " +
        "ORDER BY MAX(m.createdAt) DESC, m.conversationId DESC")
    List<Object[]> findProjectConversationSummaries(
            @org.springframework.data.repository.query.Param("projectId") Long projectId,
            @org.springframework.data.repository.query.Param("before") java.time.LocalDateTime before,
            @org.springframework.data.repository.query.Param("beforeId") String beforeId);

```

**3b.** 编辑 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/repository/AgentRunRecordRepository.java`，在第 16 行 `List<AgentRunRecord> findByStatus(String status);` **之后**插入：

```java

    /** 概览页会话列表批量取运行状态：一次查完，避免每个会话一次 findByConversationId 的 N+1。 */
    List<AgentRunRecord> findByConversationIdIn(java.util.Collection<String> conversationIds);
```

> 若「后端·概览统计与动态」组（Task 1 起）已经先在这个文件里加过 `findByProjectIdOrderByUpdatedAtDesc`，**保留它**，只追加上面这一个方法。

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectConversationSummaryQueryTest -DfailIfNoTests=false
```

Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add backend/src/main/java/com/checkba/repository/ProjectAiMessageRepository.java backend/src/main/java/com/checkba/repository/AgentRunRecordRepository.java backend/src/test/java/com/checkba/repository/ProjectConversationSummaryQueryTest.java && git commit -m "feat(ai): 新增项目级会话汇总查询（复合游标）与运行状态批量查询

findProjectConversationSummaries 去掉 userId 条件，语义从「我在这个项目里的会话」
变成「这个项目的全部会话」，多回一列发起人 id。
游标是 (MAX(createdAt), conversationId) 两维：只用时间一维时，两个会话最后活跃
时间相同就会在翻页时永久丢掉其中一条，测试专门钉住这条。
既有 findConversationSummaries 服务 /api/ai/conversations，一行未改。
AgentRunRecordRepository 补 findByConversationIdIn，供列表批量取运行状态防 N+1。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 13: ProjectAiMessageService 新增 listProjectConversations

放在这个既有服务里、而不是新起一个服务，是为了**就地复用它的三个私有清洗方法**——`cleanTitle`（`ProjectAiMessageService.java:210`）、`extractPreview`（`:238`）、`truncatePreview`（`:285`）。仓里已经有两套并行漂移的清洗正则（服务端一套、前端 `fetchChatHistory` 一套），**不许出第三套**。

既有的 `listConversations`（`:182-205`）**一行都不改**——它服务 AI 面板。新方法直接调同样的三个私有助手，重复的只有 8 行组装代码，代价远小于动 AI 面板主链路的风险。

**预览回退的条件只判空串**：既有 `listConversations:197` 写的是 `preview.isEmpty() || preview.length() < 5`，那个 `< 5` 会把「已核对」「好的」这类合法短回复也替换成用户的提问，且与前端 `hasConversationPreview`（只判 trim 后空串）口径不一致。新方法只判 `preview.isEmpty()`；既有那条不动（改它会改 AI 面板的既有显示）。

**一个必须同批处理的连带影响**：给本类加两个 `final` 字段会让 `@RequiredArgsConstructor` 生成的构造器从 2 参变 4 参，当场打断 `ProjectAiMessageServiceTest` 里 4 个直接 `new` 的调用点。Step 3c 列出这 4 处的具体修补（行号已实测：`:40`、`:187`、`:200-201`、`:213-214`）。其余 6 个引用方（`AiAgentControllerTest:53`、`OfficeResultControllerTest:32`、`AgentOrchestratorFailoverFlowTest:102/250`、`AgentOrchestratorQuestionStopTest:103`、`ContextAssemblerServiceTest:48/387`、`EvalHarness:121`）用的都是 `mock(ProjectAiMessageService.class)`，不受构造器变更影响。

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/service/ProjectAiMessageService.java`（字段区 `:16` 追加两个 final 依赖；新方法插在文件末尾 `truncateHistory` 方法之后、类的最后一个 `}` 之前——**用文本锚点定位，不要按行号**，该文件当前 351 行）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/service/ProjectAiMessageServiceTest.java:40, :187, :200-201, :213-214`
- Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/service/ProjectConversationListTest.java`（新建）

**Interfaces:**
- Consumes: Task 12 的 `ProjectAiMessageRepository.findProjectConversationSummaries(Long, LocalDateTime, String)` 与 `AgentRunRecordRepository.findByConversationIdIn(Collection<String>)`；`UserRepository.findAllById(Iterable<Long>)`（JpaRepository 自带）；本类既有私有方法 `cleanTitle:210` / `extractPreview:238` / `truncatePreview:285`
- Produces: `public java.util.Map<String, Object> com.checkba.service.ProjectAiMessageService.listProjectConversations(Long projectId, java.time.LocalDateTime before, String beforeId, int limit)`（包路径是 `com.checkba.service`，**没有 `.ai` 子包**），返回 `{"conversations": List<Map<String,Object>>, "nextBefore": String|null, "nextBeforeId": String|null}`；每个会话项的键固定为 `conversationId, title, lastMessage, updatedAt, runStatus, ownerUserId, ownerName`

---

- [ ] **Step 1: 写失败的测试**

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/service/ProjectConversationListTest.java`：

```java
package com.checkba.service;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.User;
import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.repository.ProjectAiMessageRepository;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 项目概览页的 AI 对话历史列表层。
 *
 * 钉死五件事：
 * 1. 标题/预览一律来自服务端既有的 cleanTitle/extractPreview/truncatePreview，前端不再清洗；
 * 2. 预览回退只在 extractPreview 返回空串时发生 —— 合法短回复（「已核对」）不许被替换掉；
 * 3. runStatus 读 agent_run_record 表，不读 AgentRunStateService 的内存 Map
 *    （内存态进程重启后全为 null，概览页铺开历史会整片显示无状态）；
 * 4. 分页游标是 (updatedAt, conversationId) 两维，nextBefore 与 nextBeforeId 成对给出；
 * 5. limit 服务端钳到 1..50。
 */
class ProjectConversationListTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 8, 10, 0, 12);

    private ProjectAiMessageRepository repository;
    private AgentRunRecordRepository runRecordRepository;
    private UserRepository userRepository;
    private ProjectAiMessageService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectAiMessageRepository.class);
        runRecordRepository = mock(AgentRunRecordRepository.class);
        userRepository = mock(UserRepository.class);
        // 字段声明顺序即构造器参数顺序（@RequiredArgsConstructor）
        service = new ProjectAiMessageService(
                repository,
                new com.checkba.service.ai.ConversationIssuanceService(false, false),
                runRecordRepository,
                userRepository);

        when(runRecordRepository.findByConversationIdIn(any())).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());
    }

    /** 行形状与 findProjectConversationSummaries 一致：6 列。 */
    private Object[] row(String conversationId, LocalDateTime updatedAt, String lastContent,
                         String title, String firstUserMessage, Long ownerUserId) {
        return new Object[]{conversationId, updatedAt, lastContent, title, firstUserMessage, ownerUserId};
    }

    private User user(Long id, String displayName, String username) {
        User u = new User();
        u.setId(id);
        u.setDisplayName(displayName);
        u.setUsername(username);
        return u;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> conversationsOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("conversations");
    }

    @Test
    void 有LLM标题时用它_预览走服务端清洗不带任何标签() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                row("c-a", BASE, "<thinking>先想一下</thinking><final>已核对通知与决议的届次</final>",
                        "股东会材料核查", "届次对不对", 7L)));

        Map<String, Object> result = service.listProjectConversations(1L, null, null, 20);
        Map<String, Object> item = conversationsOf(result).get(0);

        assertEquals("c-a", item.get("conversationId"));
        assertEquals("股东会材料核查", item.get("title"));
        assertEquals("已核对通知与决议的届次", item.get("lastMessage"),
                "必须是 extractPreview 的输出：只留 <final> 内容，thinking 与标签全剥掉");
        assertEquals("2026-08-08T10:00:12", item.get("updatedAt"), "updatedAt 是 ISO 串，不是数组也不是时间戳");
    }

    @Test
    void 无LLM标题时回退到cleanTitle_预览为空时回退到用户首条消息() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                // extractPreview 对以 const 开头的正文直接返回空串（ProjectAiMessageService:275 的
                // 「明显是代码」过滤），预览必须回退
                row("c-b", BASE, "const x = 1", null, "帮我起草一份股权转让协议", 9L)));

        Map<String, Object> item = conversationsOf(service.listProjectConversations(1L, null, null, 20)).get(0);

        assertEquals("const x = 1", item.get("title"), "无 conversationTitle 时标题走 cleanTitle");
        assertEquals("帮我起草一份股权转让协议", item.get("lastMessage"),
                "extractPreview 返回空串时回退到用户第一条消息");
    }

    @Test
    void 合法短回复不被回退掉_回退条件只判空串() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                row("c-short", BASE, "已核对", "标题", "这是用户问的很长的一句话", 7L)));

        Map<String, Object> item = conversationsOf(service.listProjectConversations(1L, null, null, 20)).get(0);

        assertEquals("已核对", item.get("lastMessage"),
                "回退条件只能是 isEmpty()；带上『长度不足 5』会把合法短回复替换成用户的提问");
    }

    @Test
    void 运行状态取自agent_run_record表_无记录为null() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                row("c-run", BASE.plusHours(1), "跑着呢", "标题", "问题", 7L),
                row("c-idle", BASE, "跑完了", "标题2", "问题2", 7L)));
        AgentRunRecord running = new AgentRunRecord();
        running.setConversationId("c-run");
        running.setStatus("RUNNING");
        when(runRecordRepository.findByConversationIdIn(any())).thenReturn(List.of(running));

        List<Map<String, Object>> items = conversationsOf(service.listProjectConversations(1L, null, null, 20));

        assertEquals("RUNNING", items.get(0).get("runStatus"));
        assertNull(items.get(1).get("runStatus"), "没有运行记录的会话 runStatus 为 null");
        verify(runRecordRepository, times(1)).findByConversationIdIn(any());
    }

    @Test
    void 发起人显示名优先displayName_为空回退username_查不到为null() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                row("c-1", BASE.plusHours(2), "内容1", "标题1", "问1", 7L),
                row("c-2", BASE.plusHours(1), "内容2", "标题2", "问2", 8L),
                row("c-3", BASE, "内容3", "标题3", "问3", 99L)));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(7L, "张三", "zhangsan"),
                user(8L, "  ", "lisi")));

        List<Map<String, Object>> items = conversationsOf(service.listProjectConversations(1L, null, null, 20));

        assertEquals("张三", items.get(0).get("ownerName"));
        assertEquals(Long.valueOf(7L), items.get(0).get("ownerUserId"));
        assertEquals("lisi", items.get(1).get("ownerName"), "displayName 空白时回退 username");
        assertNull(items.get(2).get("ownerName"), "用户查不到时为 null");
        verify(userRepository, times(1)).findAllById(any());
    }

    @Test
    void 有下一页时游标两个字段成对给出() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                row("c-1", BASE.plusHours(2), "a", "t1", "q1", 7L),
                row("c-2", BASE.plusHours(1), "b", "t2", "q2", 7L),
                row("c-3", BASE, "c", "t3", "q3", 7L)));

        Map<String, Object> result = service.listProjectConversations(1L, null, null, 2);

        assertEquals(2, conversationsOf(result).size(), "多取的第 3 条只用来判有没有下一页，不下发");
        assertEquals("2026-08-08T11:00:12", result.get("nextBefore"));
        assertEquals("c-2", result.get("nextBeforeId"),
                "游标是两维的，只给时间会在同时刻的两个会话上丢数据");
    }

    @Test
    void 没有下一页时两个游标字段都为null() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                row("c-1", BASE, "a", "t1", "q1", 7L)));

        Map<String, Object> result = service.listProjectConversations(1L, null, null, 20);

        assertEquals(1, conversationsOf(result).size());
        assertNull(result.get("nextBefore"));
        assertNull(result.get("nextBeforeId"));
    }

    @Test
    void 零会话时返回空数组而不是null() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of());

        Map<String, Object> result = service.listProjectConversations(1L, null, null, 20);

        assertNotNull(conversationsOf(result));
        assertTrue(conversationsOf(result).isEmpty());
        assertNull(result.get("nextBefore"));
        assertNull(result.get("nextBeforeId"));
        verify(runRecordRepository, never()).findByConversationIdIn(any());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void limit被钳到1到50之间() {
        List<Object[]> many = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(row("c-" + i, BASE.minusMinutes(i), "x", "t", "q", 7L));
        }
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(many);

        assertEquals(50, conversationsOf(service.listProjectConversations(1L, null, null, 999)).size(),
                "上钳到 50");
        assertEquals(1, conversationsOf(service.listProjectConversations(1L, null, null, 0)).size(),
                "下钳到 1");
        assertEquals(1, conversationsOf(service.listProjectConversations(1L, null, null, -5)).size(),
                "负数也钳到 1");
    }

    @Test
    void 两个游标参数原样透传给查询层() {
        LocalDateTime cursor = BASE.plusHours(3);
        when(repository.findProjectConversationSummaries(eq(1L), eq(cursor), eq("c-x"))).thenReturn(List.of());

        service.listProjectConversations(1L, cursor, "c-x", 20);

        verify(repository).findProjectConversationSummaries(1L, cursor, "c-x");
    }

    @Test
    void 会话项不含正文字段_列表层一行正文都不下发() {
        when(repository.findProjectConversationSummaries(eq(1L), eq(null), eq(null))).thenReturn(List.of(
                row("c-a", BASE, "这是完整正文不该原样下发", "标题", "问题", 7L)));

        Map<String, Object> item = conversationsOf(service.listProjectConversations(1L, null, null, 20)).get(0);

        assertEquals(java.util.Set.of("conversationId", "title", "lastMessage", "updatedAt",
                        "runStatus", "ownerUserId", "ownerName"), item.keySet(),
                "键集合固定；正文层仍走 canUseConversation，列表层不许多出 content/messages 之类的键");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectConversationListTest -DfailIfNoTests=false
```

Expected: `BUILD FAILURE` + `COMPILATION ERROR`，主要两条：
```
constructor ProjectAiMessageService in class com.checkba.service.ProjectAiMessageService cannot be applied to given types;
  required: ProjectAiMessageRepository,ConversationIssuanceService
  found:    ProjectAiMessageRepository,ConversationIssuanceService,AgentRunRecordRepository,UserRepository
cannot find symbol: method listProjectConversations(long,java.time.LocalDateTime,java.lang.String,int)
```

- [ ] **Step 3: 最小实现**

**3a.** 编辑 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/service/ProjectAiMessageService.java`。把第 16 行

```java
    private final com.checkba.service.ai.ConversationIssuanceService conversationIssuanceService;
```

替换为（**顺序不能调**，`@RequiredArgsConstructor` 按字段声明顺序生成构造器，Step 1 的测试与 3c 的修补都按这个顺序传参）：

```java
    private final com.checkba.service.ai.ConversationIssuanceService conversationIssuanceService;
    /** 概览页会话列表的运行状态来源：读表不读 AgentRunStateService 的内存 Map。 */
    private final com.checkba.repository.AgentRunRecordRepository agentRunRecordRepository;
    /** 概览页会话列表的发起人显示名。 */
    private final com.checkba.repository.UserRepository userRepository;
```

**3b.** 同一文件，在 `truncateHistory` 方法的收尾行

```java
        repository.deleteByConversationIdAndCreatedAtAfter(conversationId, message.getCreatedAt());
    }
```

**之后**、类的最后一个 `}` 之前插入下面整块（用这个文本锚点定位，不要数行号）。全部用全限定名，是为了不动文件头的 import 区（该文件只 import 了 `java.time.LocalDateTime` 与 `java.util.List`，既有的 `listConversations` 也是这个写法）：

```java

    /**
     * 项目级会话列表（概览页用）：不按 userId 过滤，这个项目的成员都看得到全部会话。
     *
     * 与 {@link #listConversations} 只有三点不同 —— 可见性口径（项目全员 vs 我自己）、
     * runStatus 来源（agent_run_record 表 vs 内存 Map）、预览回退条件（只判空串 vs
     * 还判长度不足 5，后者会把「已核对」这类合法短回复也替换掉）。
     * 标题与预览的清洗一律复用 cleanTitle / extractPreview / truncatePreview 三个私有方法：
     * 仓里已经有两套并行漂移的清洗正则（服务端一套、前端 fetchChatHistory 一套），不许出第三套。
     * 既有的 listConversations 服务 AI 面板，一行都不改。
     *
     * 只有列表层。正文一行都不下发 —— 正文层仍走 canUseConversation 判权。
     *
     * @param before   游标的时间维；null 表示第一页
     * @param beforeId 游标的会话维（上一页最后一条的 conversationId）。与 before 成对使用：
     *                 只给 before 时同一时刻的另一个会话会被永久跳过
     * @param limit    期望条数，服务端钳到 1..50
     * @return {"conversations": [...], "nextBefore": ISO 串或 null, "nextBeforeId": 会话 id 或 null}
     */
    public java.util.Map<String, Object> listProjectConversations(Long projectId, LocalDateTime before,
                                                                 String beforeId, int limit) {
        int pageSize = Math.max(1, Math.min(50, limit));

        // limit 只能在 Java 层做：那条 JPQL 有 4 个标量子查询 + GROUP BY + HAVING，
        // 套 Pageable 会逼出手写 countQuery 或两段式。多取一条用来判有没有下一页。
        List<Object[]> rows = repository.findProjectConversationSummaries(projectId, before, beforeId).stream()
                .filter(row -> row[0] != null)
                .limit(pageSize + 1L)
                .collect(java.util.stream.Collectors.toList());
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = new java.util.ArrayList<>(rows.subList(0, pageSize));
        }

        // 运行状态批量取，防 N+1。读 agent_run_record 表而不是 AgentRunStateService 的
        // 内存 Map：内存态进程重启后全为 null，概览页把历史铺开会整片显示无状态。
        java.util.Map<String, String> statusByConversation = new java.util.HashMap<>();
        if (!rows.isEmpty()) {
            java.util.List<String> conversationIds = rows.stream()
                    .map(row -> (String) row[0])
                    .collect(java.util.stream.Collectors.toList());
            for (com.checkba.model.entity.AgentRunRecord record
                    : agentRunRecordRepository.findByConversationIdIn(conversationIds)) {
                if (record.getConversationId() != null) {
                    statusByConversation.put(record.getConversationId(), record.getStatus());
                }
            }
        }

        // 发起人显示名批量取，同样防 N+1。
        java.util.Map<Long, String> nameByUserId = new java.util.HashMap<>();
        java.util.Set<Long> ownerIds = rows.stream()
                .map(row -> (Long) row[5])
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (!ownerIds.isEmpty()) {
            for (com.checkba.model.entity.User user : userRepository.findAllById(ownerIds)) {
                String name = user.getDisplayName();
                if (name == null || name.isBlank()) {
                    name = user.getUsername();
                }
                if (name != null) {
                    nameByUserId.put(user.getId(), name);
                }
            }
        }

        java.util.List<java.util.Map<String, Object>> conversations = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            String conversationId = (String) row[0];
            LocalDateTime updatedAt = (LocalDateTime) row[1];
            String lastContent = row[2] != null ? row[2].toString() : "";
            String storedTitle = row[3] != null ? row[3].toString() : null;
            String firstUserMessage = row[4] != null ? row[4].toString() : "";
            Long ownerUserId = (Long) row[5];

            String preview = extractPreview(lastContent);
            if (preview.isEmpty()) {
                // extractPreview 对以 import/def/function/class/const/let/var/public/private
                // 开头的正文直接返回空串（本类 :275），回退到用户第一条消息。
                // 只判空串：加「长度不足 N」会把「已核对」「好的」这类合法短回复也顶掉。
                preview = truncatePreview(firstUserMessage.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim());
            }

            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("conversationId", conversationId);
            item.put("title", storedTitle != null && !storedTitle.isBlank() ? storedTitle : cleanTitle(lastContent));
            item.put("lastMessage", preview);
            // ISO 串而不是原始 LocalDateTime：前端直接显示，且能原样当成下一页的 before 传回来
            // （保留纳秒精度，避免截到秒后漏掉同一秒内的另一个会话）。
            item.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
            item.put("runStatus", statusByConversation.get(conversationId));
            item.put("ownerUserId", ownerUserId);
            item.put("ownerName", ownerUserId == null ? null : nameByUserId.get(ownerUserId));
            conversations.add(item);
        }

        java.util.Map<String, Object> last = conversations.isEmpty()
                ? null : conversations.get(conversations.size() - 1);
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("conversations", conversations);
        // 游标两维成对下发：少给 nextBeforeId 会让同一时刻的两个会话在翻页时丢一条。
        result.put("nextBefore", hasMore && last != null ? last.get("updatedAt") : null);
        result.put("nextBeforeId", hasMore && last != null ? last.get("conversationId") : null);
        return result;
    }
```

**3c.** 修补 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/service/ProjectAiMessageServiceTest.java` 的 4 个直接 `new` 调用点（构造器已变 4 参）。先列一遍确认只有这四处：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && grep -rn "new ProjectAiMessageService(" src/
```
Expected: 恰好四行，分别在 `:40`、`:187`、`:200`、`:213`。

用全限定名传新增的两个 mock，避免动 import 区。

第 40 行，把
```java
        service = new ProjectAiMessageService(repository, issuanceService);
```
改为
```java
        service = new ProjectAiMessageService(repository, issuanceService,
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));
```

第 187 行，把
```java
        ProjectAiMessageService enforcing = new ProjectAiMessageService(repository, issuance);
```
改为
```java
        ProjectAiMessageService enforcing = new ProjectAiMessageService(repository, issuance,
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));
```

第 200-201 行，把
```java
        ProjectAiMessageService enforcing = new ProjectAiMessageService(
                repository, new com.checkba.service.ai.ConversationIssuanceService(true, false));
```
改为
```java
        ProjectAiMessageService enforcing = new ProjectAiMessageService(
                repository, new com.checkba.service.ai.ConversationIssuanceService(true, false),
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));
```

第 213-214 行，把
```java
        ProjectAiMessageService localMode = new ProjectAiMessageService(
                repository, new com.checkba.service.ai.ConversationIssuanceService(true, true));
```
改为
```java
        ProjectAiMessageService localMode = new ProjectAiMessageService(
                repository, new com.checkba.service.ai.ConversationIssuanceService(true, true),
                mock(com.checkba.repository.AgentRunRecordRepository.class),
                mock(com.checkba.repository.UserRepository.class));
```

- [ ] **Step 4: 跑测试确认通过**

Run（新测试 + 被连带修改的老测试一起跑）：
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest='ProjectConversationListTest,ProjectAiMessageServiceTest' -DfailIfNoTests=false
```

Expected: `BUILD SUCCESS`；`ProjectConversationListTest` 报 `Tests run: 11, Failures: 0, Errors: 0`，`ProjectAiMessageServiceTest` 全绿（构造器修补后行为不变）。

再确认没有别的地方被构造器变更打断：
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test-compile
```
Expected: `BUILD SUCCESS`（另外 6 个引用方用的是 `mock(ProjectAiMessageService.class)`，不受构造器变更影响）。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add backend/src/main/java/com/checkba/service/ProjectAiMessageService.java backend/src/test/java/com/checkba/service/ProjectAiMessageServiceTest.java backend/src/test/java/com/checkba/service/ProjectConversationListTest.java && git commit -m "feat(ai): ProjectAiMessageService 新增项目级会话列表 listProjectConversations

就地复用既有的 cleanTitle/extractPreview/truncatePreview 三个私有清洗方法
（仓里已有两套并行漂移的清洗正则，不再出第三套）；游标两维、多取一条判下一页，
nextBefore 与 nextBeforeId 成对下发；运行状态与发起人显示名各一次批量查询防 N+1。
runStatus 读 agent_run_record 表而不是内存 Map —— 内存态进程重启后全为 null。
预览回退条件只判空串，不再带「长度不足 5」（那会顶掉「已核对」这类合法短回复）。
构造器由 2 参变 4 参，同步修 ProjectAiMessageServiceTest 的四个直接 new 调用点。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 14: ProjectOverviewController 落地 GET /api/projects/{projectId}/conversations

`ProjectOverviewController.java` 由三个后端组共用（Task 1 起的统计与动态组、Task 6 起的档案组、本组）。**骨架只有一份，照下面写，不要各自发挥**：

- `requireRead` / `requireWrite` 的参数序恒为 `(Long projectId, String sessionId)`——projectId 在前，与端点方法的参数顺序一致，最不容易记反
- 所有端点返回类型统一 `ResponseEntity<Map<String, Object>>`
- 依赖注入统一**显式构造器**，不用 `@RequiredArgsConstructor`
- **第一个执行到的组创建文件，后续组只往字段区、构造器参数表和类末尾追加**，绝不重建整文件

**两个必须写对的点：**
- `hasReadPermission(Long projectId, Long userId)` 的**参数序是 projectId 在前**（`ProjectMemberService.java:151`），两个都是 Long，写反能编译通过、运行时静默返回 false，表现成「明明是我的项目却说无权访问」。
- **不拒 CLIENT**。列表层按项目全员可见是分层决策，`hasReadPermission` 本身就放行 CLIENT，这里不要再加 `isClient` 判断。

未登录/越权一律抛 `IllegalArgumentException`，由 `GlobalExceptionHandler.java:69-77` 转成 **HTTP 200 + `{code:1, message}`**——这是全站 90+ 端点的统一口径（那段代码的注释明写「统一返回 HTTP 200，通过 code 字段表示失败」），**不要为这几个新端点单开 401/403**。要修的缺陷是 `/api/ai/conversations` 无 session 时**静默返回空数组**（让人以为「没有对话」而不是「你没登录」），`{code:1,"未登录"}` 一样修掉它，而且与全站一致。

**Files:**
- Create（若不存在）: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/controller/ProjectOverviewController.java`
- Modify（若已由另外两个后端组之一创建）: 同上文件，只追加字段、构造器参数与 `listConversations` 方法
- Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/controller/ProjectOverviewConversationsAuthTest.java`（新建，**文件名必须是这个**，三个后端组的鉴权测试各一个文件名，同名会互相覆盖）

**Interfaces:**
- Consumes: `AuthController.getUserIdFromSession(String)`（`AuthController.java:640`，`public static`，直接调不用注入）、`ProjectMemberService.hasReadPermission(Long projectId, Long userId)`（`ProjectMemberService.java:151`）、Task 13 的 `ProjectAiMessageService.listProjectConversations(Long, LocalDateTime, String, int)`
- Produces: HTTP `GET /api/projects/{projectId}/conversations?limit=20&before=2026-08-08T10:11:12&beforeId=c-abc`，请求头 `X-Session-Id`（`required=false`），返回信封 `{"code":0,"data":{"conversations":[...],"nextBefore":...,"nextBeforeId":...}}`；未登录抛 `IllegalArgumentException("未登录")`、非成员抛 `IllegalArgumentException("无权访问该项目")`

> **给前端的接缝说明（写进代码注释，见下）**：`beforeId` 是可选的。前端 `getProjectConversations` 若只回传 `before` 不回传 `beforeId`，服务端退化成严格小于——不报错，但同一时刻落库的两个会话仍会丢一条。翻页时请把上一页的 `nextBefore` 与 `nextBeforeId` **一起**带回来。

---

- [ ] **Step 1: 写失败的测试**

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/controller/ProjectOverviewConversationsAuthTest.java`：

```java
package com.checkba.controller;

import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 项目概览页「会话列表」端点的鉴权与信封。
 *
 * 关键差异（别照抄 /api/ai/conversations）：
 * - 无 session 必须抛「未登录」，不许静默返回空数组；
 * - 非成员必须被 hasReadPermission 挡掉；
 * - 参数序是 (projectId, userId)，写反能编译通过、运行时静默 false；
 * - 返回 ResponseEntity 包的信封 {code:0,data:...}，不是裸数组。
 *
 * 用 @InjectMocks 而不是手工 new：ProjectOverviewController 的构造器参数表会随另外
 * 两个后端组追加自己的 service 而变长，手工 new 在合并后会编译失败；@InjectMocks
 * 取最大构造器、对不上的参数注 null，而本测试从不触碰那些依赖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectOverviewConversationsAuthTest {

    @Mock
    private ProjectAiMessageService projectAiMessageService;
    @Mock
    private ProjectMemberService projectMemberService;
    // 不要加 @Mock private AuthController authController —— 控制器构造器里没有这个参数，
    // 注不进去是个死字段。会话解析走 mockStatic(AuthController.class)（下面每个用例里）。

    @InjectMocks
    private ProjectOverviewController controller;

    @Test
    void 无会话时抛未登录_不返回空数组() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> controller.listConversations(42L, 20, null, null, null));
            assertEquals("未登录", ex.getMessage());
            verify(projectAiMessageService, never())
                    .listProjectConversations(any(), any(), any(), anyInt());
        }
    }

    @Test
    void 非项目成员被拒() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            // 参数序：projectId 在前，userId 在后
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(false);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> controller.listConversations(42L, 20, null, null, "sess"));
            assertEquals("无权访问该项目", ex.getMessage());
            verify(projectAiMessageService, never())
                    .listProjectConversations(any(), any(), any(), anyInt());
        }
    }

    @Test
    void 成员放行并返回信封_两个游标参数与条数原样透传() {
        LocalDateTime cursor = LocalDateTime.of(2026, 8, 8, 10, 0, 12);
        Map<String, Object> payload = Map.of(
                "conversations", List.of(Map.of("conversationId", "c-a")),
                "nextBefore", "2026-08-08T09:00:00",
                "nextBeforeId", "c-a");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(true);
            when(projectAiMessageService.listProjectConversations(42L, cursor, "c-b", 5))
                    .thenReturn(payload);

            ResponseEntity<Map<String, Object>> res =
                    controller.listConversations(42L, 5, cursor, "c-b", "sess");

            assertNotNull(res.getBody());
            assertEquals(Integer.valueOf(0), res.getBody().get("code"), "必须是信封，不是裸数组");
            assertSame(payload, res.getBody().get("data"));
            verify(projectAiMessageService).listProjectConversations(42L, cursor, "c-b", 5);
        }
    }

    @Test
    void 读权限不拒CLIENT_列表层按项目全员可见() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(true);
            when(projectAiMessageService.listProjectConversations(any(), any(), any(), anyInt()))
                    .thenReturn(Map.of("conversations", List.of()));

            assertNotNull(controller.listConversations(42L, 20, null, null, "sess"));
            // 读端点不该去问 isClient —— 问了就说明写成写端点的口径了
            verify(projectMemberService, never()).isClient(anyLong(), anyLong());
        }
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectOverviewConversationsAuthTest -DfailIfNoTests=false
```

Expected: `BUILD FAILURE` + `COMPILATION ERROR`。若 `ProjectOverviewController.java` 还不存在，报 `cannot find symbol: class ProjectOverviewController`；若已由另外两个后端组之一建好，报 `cannot find symbol: method listConversations(long,int,java.time.LocalDateTime,java.lang.String,java.lang.String)`。

- [ ] **Step 3: 最小实现**

先判断文件在不在：
```bash
ls "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/main/java/com/checkba/controller/ProjectOverviewController.java"
```

**3a. 报 `No such file or directory` —— 本组是第一个到场的，新建整文件**，完整内容：

```java
package com.checkba.controller;

import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectMemberService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 项目概览页的聚合端点（三个后端切片共用同一个控制器）。
 *
 * 共用约定，改之前先看清楚：
 * - requireRead / requireWrite 的参数序恒为 (Long projectId, String sessionId)，projectId 在前；
 * - 所有端点返回类型统一 ResponseEntity<Map<String, Object>>；
 * - 依赖注入统一显式构造器，不用 @RequiredArgsConstructor；后到的切片只往字段区、
 *   构造器参数表和类末尾追加，不重建整文件。
 *
 * 鉴权模式与 DdController:31-38 / ShareholderMeetingController 一致：
 * X-Session-Id -> userId -> 项目成员校验。X-Session-Id 一律 required=false ——
 * local-mode（桌面单机免登）下请求可以完全不带这个头，写成 required=true 会让整条链 500。
 *
 * 地雷：hasReadPermission / hasWritePermission / isClient 的参数序都是 (projectId, userId)
 * （ProjectMemberService.java:151 / :159 / :171），两个都是 Long，写反能编译通过、
 * 运行时静默返回 false，表现成「明明是我的项目却说无权访问」。
 *
 * 失败一律抛 IllegalArgumentException，由 GlobalExceptionHandler:69-77 转成
 * HTTP 200 + {code:1, message} —— 全站 90+ 端点同一口径，不为新端点单开 401/403。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectOverviewController {

    private final ProjectMemberService projectMemberService;
    private final ProjectAiMessageService projectAiMessageService;

    // AuthController.getUserIdFromSession 是 public static（AuthController.java:640），
    // 直接调，不注入 —— 不要加 AuthController 字段与构造器参数。

    public ProjectOverviewController(ProjectMemberService projectMemberService,
                                     ProjectAiMessageService projectAiMessageService) {
        this.projectMemberService = projectMemberService;
        this.projectAiMessageService = projectAiMessageService;
    }

    /** 登录 + 读权限。**不拒 CLIENT** —— 概览页就是给客户看的那一页。返回 userId。 */
    private Long requireRead(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException("无权访问该项目");
        }
        return userId;
    }

    /**
     * 登录 + 写权限 + 显式拒 CLIENT。返回 userId。
     *
     * hasWritePermission 已经天然排除 CLIENT（ProjectMemberService.java:167-168 只放行
     * ADMIN/PARTICIPANT 加 owner），仍显式再判一次 isClient 是第二道闸：isClient 的实现
     * （:179）是三个字面量的显式 or，不是 startsWith("CLIENT")，将来新增 CLIENT_* 角色时会漏判。
     *
     * 本切片的会话列表是读端点，用不到这个助手；它属于共用骨架的一部分，由档案切片的
     * PUT 端点使用。后到的切片直接复用，不要再写一份。
     */
    private Long requireWrite(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (projectId == null
                || !projectMemberService.hasWritePermission(projectId, userId)
                || projectMemberService.isClient(projectId, userId)) {
            throw new IllegalArgumentException("无权修改该项目");
        }
        return userId;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }

    /**
     * 项目级 AI 对话历史（列表层，项目全员可见）。
     *
     * 与 user-scoped 的 GET /api/ai/conversations 是两条独立通道，后者一行不改。
     * 那条在无 session 时静默返回空数组（会被误诊成前端 bug）；这条必须抛「未登录」。
     *
     * 分页是游标不是 offset，且游标是两维的：before（上一页最后一条的 updatedAt）
     * 与 beforeId（它的 conversationId）。beforeId 可以不传，服务端会退化成严格小于——
     * 不报错，但同一时刻落库的两个会话会丢一条。前端翻页时请把响应里的
     * nextBefore 与 nextBeforeId 一起带回来。
     */
    @GetMapping("/conversations")
    public ResponseEntity<Map<String, Object>> listConversations(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
            @RequestParam(required = false) String beforeId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireRead(projectId, sessionId);
        return ok(projectAiMessageService.listProjectConversations(projectId, before, beforeId, limit));
    }
}
```

**3b. 文件已存在**（另外两个后端组之一先落了）—— **不要重建**，只做三处追加：

1. 字段区末尾补一行：
```java
    private final ProjectAiMessageService projectAiMessageService;
```
2. 显式构造器的参数表末尾补一个参数并在方法体里赋值：
```java
                                     ProjectAiMessageService projectAiMessageService) {
        ...
        this.projectAiMessageService = projectAiMessageService;
```
3. 在类的最后一个 `}` 之前，粘贴 3a 里 `listConversations` 方法的**完整代码块**（连同它上面的 Javadoc）。

同时确认文件头有这四个 import，缺哪个补哪个（**不要重复添加**）：
```java
import com.checkba.service.ProjectAiMessageService;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;
import java.util.Map;
```

已存在的 `requireRead` / `ok` 直接复用，不要再写一份。若已存在的 `requireRead` 拒绝文案不是「无权访问该项目」，以本文案为准统一（Task 15 的集成测试断言这个字符串）。

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectOverviewConversationsAuthTest -DfailIfNoTests=false
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add backend/src/main/java/com/checkba/controller/ProjectOverviewController.java backend/src/test/java/com/checkba/controller/ProjectOverviewConversationsAuthTest.java && git commit -m "feat(api): 新增 GET /api/projects/{projectId}/conversations（项目级会话列表层）

鉴权模板照 DdController：未登录抛「未登录」而不是静默返回空数组
（/api/ai/conversations 的老毛病，会被误诊成前端 bug）；读权限走
hasReadPermission(projectId, userId)，不拒 CLIENT —— 列表层按项目全员可见。
失败经 GlobalExceptionHandler 转成 HTTP 200 + {code:1}，与全站 90+ 端点同口径。
游标两个参数：before 走 ISO DATE_TIME 绑定，beforeId 可选、缺失时退化成严格小于。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 15: [护栏任务·非 TDD] HTTP 端到端回归护栏与全量回归

**本任务不写生产代码、不做红绿循环**：Task 11-14 已经把实现落全，这里只补一层走真 HTTP 的回归护栏，再跑一次后端全量测试确认没碰坏 AI 面板既有链路。

为什么这层护栏必须有：Task 13/14 的单元测试都在 mock 之上，**三件事只有走完真 HTTP 才验得到**——
1. `before` 查询参数的字符串 → `LocalDateTime` 绑定（`@DateTimeFormat` 漏写时单元测试全绿、线上第二页 500）；
2. 「本页返回的 `nextBefore` + `nextBeforeId` 能不能原样当下一页的入参」这条闭环；
3. 「列表层不再按 userId 过滤」这条语义变更——它是本组唯一的可见性变更，将来最容易被无意改回去。

顺带把复合游标在**同一时刻落库的两个会话**上不丢数据这件事，从 Repository 层一路验到 HTTP 层。

环境照抄 `IdorAuthIntegrationTest`（同目录）：内存 H2（MODE=PostgreSQL）+ `desktop` profile + `security.local-mode=false`——`desktop` profile 自带的 `local-mode=true` 会把所有请求解析成同一个本机用户，「跨用户」前提就不成立了。

**Files:**
- Test: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/ProjectConversationsEndpointIntegrationTest.java`（新建）
- 无生产代码改动

**Interfaces:**
- Consumes: 真实 HTTP 链路 `POST /api/auth/register` → `POST /api/projects` → `GET /api/projects/{projectId}/conversations`；直接注入 `ProjectAiMessageRepository` / `AgentRunRecordRepository` / `UserRepository` 造数据
- Produces: 回归护栏，钉死六件事——未登录返 HTTP 200 + `{code:1,"未登录"}`、非成员返 `{code:1,"无权访问该项目"}`、别人发起的会话对项目成员可见、两个游标参数能被绑定并完成翻页、同一时刻的两个会话翻页时一条都不丢、`runStatus` 来自 `agent_run_record` 表

---

- [ ] **Step 1: 写护栏测试**

新建 `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/src/test/java/com/checkba/ProjectConversationsEndpointIntegrationTest.java`：

```java
package com.checkba;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.repository.ProjectAiMessageRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.ai.tools.WebTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/projects/{projectId}/conversations 的端到端护栏。
 *
 * 单元测试全在 mock 之上，三件事只有走完真 HTTP 才验得到：before 参数的字符串 →
 * LocalDateTime 绑定、「本页游标能否原样当下一页入参」的闭环、以及列表层不再按 userId
 * 过滤这条可见性语义变更。
 *
 * 失败一律是 HTTP 200 + {code:1,message}（GlobalExceptionHandler:69-77 的全站口径），
 * 所以每个请求都断言 status().isOk()，成败看 code 字段。
 *
 * 环境同 IdorAuthIntegrationTest：内存 H2（MODE=PostgreSQL）。desktop profile 自带的
 * security.local-mode=true 会把所有请求解析成同一个本机用户，「跨用户」前提不复存在，故显式关闭。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-conversations-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("desktop")
class ProjectConversationsEndpointIntegrationTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 8, 10, 0, 12);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private ProjectAiMessageRepository messageRepository;
    @Autowired
    private AgentRunRecordRepository runRecordRepository;
    @Autowired
    private UserRepository userRepository;

    /** 真 WebTools 的 @PostConstruct 会起线程预热 Playwright，测试里挡掉 */
    @MockBean
    private WebTools webTools;

    private String register(String username) throws Exception {
        String body = om.writeValueAsString(Map.of(
                "username", username, "password", "pw123456", "displayName", username));
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("sessionId").asText();
    }

    private long createProject(String sessionId, String name) throws Exception {
        String body = om.writeValueAsString(Map.of("projectType", "BLANK", "name", name));
        MvcResult r = mvc.perform(post("/api/projects").header("X-Session-Id", sessionId)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("id").asLong();
    }

    private void msg(Long projectId, Long userId, String conversationId,
                     String role, String content, String title, LocalDateTime createdAt) {
        ProjectAiMessage m = new ProjectAiMessage();
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setConversationTitle(title);
        m.setCreatedAt(createdAt);
        messageRepository.save(m);
    }

    private JsonNode getConversations(long projectId, String sessionId, String query) throws Exception {
        MockHttpServletRequestBuilder request =
                get("/api/projects/" + projectId + "/conversations" + query);
        if (sessionId != null) {
            request = request.header("X-Session-Id", sessionId);
        }
        // 失败也走 HTTP 200 + {code:1}，所以这里恒断言 isOk()
        MvcResult r = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString());
    }

    /** 把一页的两个游标字段拼成下一页的 query。 */
    private String nextQuery(JsonNode page) {
        return "?limit=1&before=" + page.path("data").path("nextBefore").asText()
                + "&beforeId=" + page.path("data").path("nextBeforeId").asText();
    }

    @Test
    void 列表层跨用户可见_复合游标翻页不丢条_未登录与非成员被拒() throws Exception {
        String aliceName = "alice_" + System.nanoTime();
        String aliceSid = register(aliceName);
        String bobName = "bob_" + System.nanoTime();
        String bobSid = register(bobName);
        long projectId = createProject(aliceSid, "股东会核查案卷");
        long aliceId = userRepository.findByUsername(aliceName).orElseThrow().getId();
        long bobId = userRepository.findByUsername(bobName).orElseThrow().getId();

        // alice 的项目里四个会话：c-new 由 bob 发起（bob 不是成员，但消息在库里），
        // c-tie-a / c-tie-b 最后活跃时间完全相同 —— 单字段游标会在这里丢一条。
        msg(projectId, aliceId, "c-old", "USER", "股东会通知的届次对不对", "股东会材料核查", BASE);
        msg(projectId, aliceId, "c-old", "ASSISTANT", "已核对通知与决议的届次", null, BASE.plusMinutes(1));
        msg(projectId, bobId, "c-new", "USER", "帮我起草一份股权转让协议", null, BASE.plusHours(1));
        msg(projectId, aliceId, "c-tie-a", "USER", "同一时刻落库的甲", null, BASE.plusHours(2));
        msg(projectId, aliceId, "c-tie-b", "USER", "同一时刻落库的乙", null, BASE.plusHours(2));

        AgentRunRecord running = new AgentRunRecord();
        running.setConversationId("c-new");
        running.setStatus("RUNNING");
        running.setProjectId(projectId);
        running.setUpdatedAt(BASE.plusHours(1));
        runRecordRepository.save(running);

        // 1) 不带 session：必须是「未登录」，不许静默返回空数组
        JsonNode anon = getConversations(projectId, null, "");
        assertEquals(1, anon.path("code").asInt(), "无 session 必须走失败信封：" + anon);
        assertEquals("未登录", anon.path("message").asText());

        // 2) bob 不是这个项目的成员：被 hasReadPermission 挡掉
        JsonNode outsider = getConversations(projectId, bobSid, "");
        assertEquals(1, outsider.path("code").asInt(), "非成员必须被拒：" + outsider);
        assertEquals("无权访问该项目", outsider.path("message").asText());

        // 3) 第一页：最近活跃且 conversationId 更大的 c-tie-b
        JsonNode page1 = getConversations(projectId, aliceSid, "?limit=1");
        assertEquals(0, page1.path("code").asInt(), "成员必须放行：" + page1);
        assertEquals("c-tie-b", page1.path("data").path("conversations").get(0)
                .path("conversationId").asText());
        assertEquals("2026-08-08T12:00:12", page1.path("data").path("nextBefore").asText());
        assertEquals("c-tie-b", page1.path("data").path("nextBeforeId").asText());

        // 4) 第二页：把两个游标字段原样传回去。这一步同时验证 @DateTimeFormat 绑定，
        //    以及「与游标同一时刻的另一个会话不能被丢掉」。
        JsonNode page2 = getConversations(projectId, aliceSid, nextQuery(page1));
        assertEquals(0, page2.path("code").asInt(), "游标参数应能被绑定：" + page2);
        assertEquals("c-tie-a", page2.path("data").path("conversations").get(0)
                        .path("conversationId").asText(),
                "同一时刻落库的另一个会话必须还在 —— 单字段游标会把它永久丢掉");

        // 5) 第三页：bob 发起的会话，项目成员也看得到；运行状态来自表
        JsonNode page3 = getConversations(projectId, aliceSid, nextQuery(page2));
        JsonNode third = page3.path("data").path("conversations").get(0);
        assertEquals("c-new", third.path("conversationId").asText());
        assertEquals(bobId, third.path("ownerUserId").asLong(),
                "列表层不按 userId 过滤：别人发起的会话，项目成员也看得到");
        assertEquals(bobName, third.path("ownerName").asText(), "发起人显示名取 displayName");
        assertEquals("RUNNING", third.path("runStatus").asText(),
                "runStatus 来自 agent_run_record 表，不是 AgentRunStateService 的内存 Map");
        assertTrue(third.path("content").isMissingNode(), "列表层一行正文都不下发");

        // 6) 第四页：最后一条，两个游标字段都归 null
        JsonNode page4 = getConversations(projectId, aliceSid, nextQuery(page3));
        JsonNode fourth = page4.path("data").path("conversations").get(0);
        assertEquals("c-old", fourth.path("conversationId").asText());
        assertEquals("股东会材料核查", fourth.path("title").asText());
        assertEquals("已核对通知与决议的届次", fourth.path("lastMessage").asText(),
                "预览走服务端 extractPreview，前端不再清洗");
        assertTrue(page4.path("data").path("nextBefore").isNull(), "没有下一页时 nextBefore 为 null");
        assertTrue(page4.path("data").path("nextBeforeId").isNull(), "nextBeforeId 同样归 null");
    }
}
```

- [ ] **Step 2: 跑它，确认直接绿**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest=ProjectConversationsEndpointIntegrationTest -DfailIfNoTests=false
```

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`。

它是护栏不是驱动实现的红：Task 11-14 已经把实现落全，所以这里应当一次绿。**若不绿，改的是实现不是断言**，按失败点回到对应任务：

| 失败现象 | 病灶 |
|---|---|
| `nextBefore` 不等于 `2026-08-08T12:00:12` | Task 13 的 `updatedAt` 没用 `LocalDateTime.toString()` |
| 第二页 `code=1` 且 message 含 `Failed to convert` | Task 14 的 `before` 参数漏了 `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)` |
| 第二页拿到的是 `c-new` 而不是 `c-tie-a` | Task 12 的 HAVING 少了 `(MAX = :before AND conversationId < :beforeId)` 这一支，或 ORDER BY 少了第二维 |
| `nextBeforeId` 是空串 | Task 13 的结果 Map 没放 `nextBeforeId` |
| `ownerUserId` 取不到 bob | Task 12 的 JPQL 漏了第 6 列子查询 |
| `runStatus` 为空 | Task 13 用了内存态而不是 `agentRunRecordRepository` |
| 非成员那条拿到 `code=0` | Task 14 的 `hasReadPermission` 参数序写反了（必须 `(projectId, userId)`） |

- [ ] **Step 3: 跑后端全量回归**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test
```

Expected: `BUILD SUCCESS`，`Failures: 0, Errors: 0`。重点确认这几个既有测试仍绿：`ProjectAiMessageServiceTest`（构造器被本组改过）、`IdorAuthIntegrationTest`、`DesktopContextSmokeTest`、`ContextAssemblerServiceTest`、`AgentOrchestratorFailoverFlowTest`、`AgentOrchestratorQuestionStopTest`。把 `Tests run:` 的总数记下来当基线。

`ProjectAiMessageIndexMysqlTest` 在这里显示 `Skipped: 1` 是对的（没有 `AWD_MYSQL_SCHEMA_CHECK` 环境变量），MySQL 那侧已在 Task 11 Step 5 单独验过。

- [ ] **Step 4: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add backend/src/test/java/com/checkba/ProjectConversationsEndpointIntegrationTest.java && git commit -m "test(api): 项目级会话列表端点的 HTTP 端到端护栏

钉死六件事：未登录返 HTTP 200 + {code:1,未登录} 而不是空数组、非成员被
hasReadPermission 挡掉、别人发起的会话对项目成员可见（列表层不按 userId 过滤）、
两个游标字段能原样当下一页入参（验 @DateTimeFormat 绑定）、同一时刻落库的
两个会话翻页时一条都不丢、runStatus 来自 agent_run_record 表。
这几条只有走完真 HTTP 才验得到，单元测试的 mock 覆盖不到。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

### Task 16: 建立导航契约静态护栏，注册项目列表页路由并落两个占位文件

**Files:**
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/scripts/check-navigation-contract.mjs`
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/project-list/project-list.vue`
- Create: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/project-list/project-list.scss`
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages.json`（在 `:44` 的 `project-overview` 项之后插入一项）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/package.json:5`（scripts 里加一行 `check:nav`）
- Test: `frontend/scripts/check-navigation-contract.mjs`

**Interfaces:**
- Consumes: `frontend/src/pages.json` 的 `pages` 数组。**该文件第 2 行是 `"pages": [ //pages数组中第一项表示应用启动页…`，带 `//` 行注释，直接 `JSON.parse` 会抛错**，脚本里必须先剥注释。
- Produces: `npm run check:nav` → `node scripts/check-navigation-contract.mjs`，全通过打印「导航契约检查通过」，任一条不过打印清单并 `process.exit(1)`。路由常量 `pages/project-list/project-list`。

> **本任务只建 `project-list` 的文件，不建 `project-home` 的任何文件**：`pages/project-home/project-home.vue` 与 `.scss` 的唯一 owner 是项目概览页组（Task 24 起），本组一行都不碰。
> **`pages.json` 里 `pages/project-home/project-home` 那一项留到 Task 23 再注册**：uni-app 会把 `pages.json` 的每一项解析成一个入口模块，注册了路由而页面文件不存在，`npm run build:h5` 会以 `Failed to resolve import` 整段失败。放在 Task 23（本组最后一个任务）注册，破窗期只有 Task 23 → Task 24 这一个任务的间隔。

- [ ] **Step 0: 装依赖（worktree 有独立的 src/，必须在本树装）**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm install
```

Expected: `node_modules/` 出现，`ls node_modules/.bin/sass` 有输出（Task 17 要用它单独编 SCSS）。主仓 `1-2 checkba_cloud/frontend/node_modules` 是另一棵树的，不要软链过来。

- [ ] **Step 1: 写失败的测试**

新建 `frontend/scripts/check-navigation-contract.mjs`：

```js
#!/usr/bin/env node
/**
 * 三级导航契约静态护栏（项目列表页 → 项目概览页 → 工作台）。
 *
 * 存在理由：这套导航散在 launch / login / newproject / project-overview / 两个新页面
 * 共十来处硬编码 URL 上，改错一处不会编译报错，只会在真人走到那一步时落到空白页
 * 或者多跳一次。规则写死在这里，CI 每次跑。
 *
 * 术语（同名不同物，别看串）：
 *   工作台       = pages/project-overview/project-overview（四列干活界面，不改名）
 *   项目概览页   = pages/project-home/project-home（一页纸卷轴）
 *   项目列表页   = pages/project-list/project-list（原个人中心的「我的项目」tab）
 *
 * 用法：cd frontend && npm run check:nav
 */
import { readFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND = resolve(dirname(fileURLToPath(import.meta.url)), '..')

const readFrontend = (rel) => readFileSync(resolve(FRONTEND, rel), 'utf8')
const hasFile = (rel) => existsSync(resolve(FRONTEND, rel))

// pages.json 带 // 行注释，JSON.parse 之前要剥掉；先吃掉字符串字面量避免误伤 URL 里的 //
const stripJsonComments = (s) =>
  s.replace(/"(?:\\.|[^"\\])*"|\/\/[^\n]*/g, (m) => (m.startsWith('"') ? m : ''))

// .vue 源码里做「禁字」断言前先剥注释：注释要能写清楚为什么不做某事
const stripVueComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

const failures = []
const check = (name, fn) => {
  let msg
  try {
    msg = fn()
  } catch (e) {
    msg = '检查本身抛异常: ' + (e && e.message)
  }
  if (msg) failures.push(name + ' — ' + msg)
}

const LIST_ROUTE = 'pages/project-list/project-list'
const WORKBENCH_ROUTE = 'pages/project-overview/project-overview'

// ==================== 路由注册 ====================

const pages = JSON.parse(stripJsonComments(readFrontend('src/pages.json'))).pages
const pageByPath = new Map(pages.map((p) => [p.path, p]))

check('pages.json 注册 ' + LIST_ROUTE, () => {
  const p = pageByPath.get(LIST_ROUTE)
  if (!p) return '未注册'
  if (!p.style || p.style.navigationStyle !== 'custom') {
    return 'style.navigationStyle 必须显式写 custom（globalStyle 里没有这一项，漏写会得到系统导航栏）'
  }
  return null
})

check('工作台路由不许改名', () =>
  pageByPath.has(WORKBENCH_ROUTE) ? null : WORKBENCH_ROUTE + ' 不在 pages.json 里'
)

check('项目列表页的两个文件都存在', () => {
  const missing = [
    'src/pages/project-list/project-list.vue',
    'src/pages/project-list/project-list.scss',
  ].filter((f) => !hasFile(f))
  return missing.length ? '缺文件: ' + missing.join(', ') : null
})

// ---- 追加位：后续任务把新的 check(...) 加在这一行之前 ----

if (failures.length) {
  console.error('导航契约检查未通过：')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}
console.log('导航契约检查通过')
```

在 `frontend/package.json` 的 scripts 里，紧跟第 5 行 `"check:emits": "node scripts/check-emit-bindings.mjs",` 之后加一行：

```json
    "check:nav": "node scripts/check-navigation-contract.mjs",
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav
```

Expected: 退出码 1，输出恰好这两条（「工作台路由不许改名」这条现在就是绿的）：
```
导航契约检查未通过：
  - pages.json 注册 pages/project-list/project-list — 未注册
  - 项目列表页的两个文件都存在 — 缺文件: src/pages/project-list/project-list.vue, src/pages/project-list/project-list.scss
```

- [ ] **Step 3: 最小实现**

建目录：

```bash
mkdir -p "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/project-list"
```

`frontend/src/pages/project-list/project-list.vue`（占位，Task 18 整文件写满）：

```vue
<template>
  <view class="page-project-list">
    <view class="project-list-container"></view>
  </view>
</template>

<script>
export default {
  name: 'ProjectList',
}
</script>

<style lang="scss" scoped src="./project-list.scss"></style>
```

`frontend/src/pages/project-list/project-list.scss`（占位，Task 17 整文件写满）：

```scss
/* 项目列表页样式。内容在 Task 17 从 userprofile.vue 抽行搬入。 */
.page-project-list {
  min-height: 100vh;
}
```

`frontend/src/pages.json`：在第 44 行（`pages/project-overview/project-overview` 那一项的结尾 `},`）之后插入一项。改完第 38-51 行长这样（缩进用 Tab，与文件其余部分一致）：

```json
		{
			"path": "pages/project-overview/project-overview",
			"style": {
				"navigationBarTitleText": "项目概览",
				"navigationStyle": "custom"
			}
		},
		{
			"path": "pages/project-list/project-list",
			"style": {
				"navigationBarTitleText": "我的项目",
				"navigationStyle": "custom"
			}
		},
		{
			"path": "pages/variable-library/variable-library",
```

`pages/project-overview/project-overview` 的 `navigationBarTitleText` 保持「项目概览」不动——改标题会动 e2e 与埋点 path 维度，spec §0 已裁定不碰工作台。

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav && npm run check:emits && npm run build:h5
```

Expected: PASS —— 依次打印 `导航契约检查通过`、`✓ emit/绑定契约检查通过（扫描 N 个 .vue）`、`build:h5` 编译成功（新页已注册且文件存在，构建能解析到它）。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/scripts/check-navigation-contract.mjs frontend/package.json frontend/src/pages.json frontend/src/pages/project-list && \
git commit -m "feat(frontend): 注册项目列表页路由并加导航契约静态护栏"
```

---

### Task 17: 项目列表页样式外置：从 userprofile.vue 抽行生成 project-list.scss

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/project-list/project-list.scss`（整文件重写）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/scripts/check-navigation-contract.mjs`（「追加位」注释之前加一组断言）
- Test: `frontend/scripts/check-navigation-contract.mjs`

**Interfaces:**
- Consumes: `frontend/src/pages/userprofile/userprofile.vue` 的 `<style lang="scss" scoped>`（起于 `:1298`）里的七段行区间，已逐段核对过真实内容：
  - `1299-1310` 品牌配色变量（10 个 `$` 变量，抽出来的样式里用到的恰好就是这 10 个）
  - `1430-1434` `.role-text`（**卡片角色徽章与个人信息卡「标准用户」标签共用，是复制不是搬走，userprofile 那份必须留着**）
  - `1517-1593` `.content-header` / `.header-title` / `.header-actions` / `.btn-primary-small` / `.btn-secondary-small` / `.cloud-accept-entry*`
  - `1595-1932` 统计条 + 项目卡片全套（到 `.project-item-card:hover .enter-btn-arrow` 收尾）
  - `2031-2065` 虚线空态（`/* Dashed Empty State */` 起）
  - `2590-2617` 角色徽章（`/* Project Role Badge */` 起）
  - `2619-2728` 负责人头像 + 成员分组布局 + `.act-glyph` / `.badge-glyph`
- Produces: `frontend/src/pages/project-list/project-list.scss`（693 行，大括号 96/96）。新增外壳三类 `.page-project-list` / `.project-list-container` / `.main-content`，补齐原页面全文无定义的 `.panel-projects` / `.loading-state` / `.loading-text`，新增 CLIENT 空态用的 `.empty-state-dashed.client-empty`，以及只保留两条与本页有关规则的响应式段。
- **不搬**：`userprofile.vue:2381-2451`（`/* Members */`）与 `:2453-2588`（`/* Modal */`）在模板里已无 class 命中（成员用 `-new` 变体、弹窗已组件化）；`:2730-2735` 的 `.empty-icon` 服务收藏/待办空态，必须留在 userprofile。
- **另有五个 class 在原页面同样无定义**（`.card-style-default` / `.badge-text-new` / `.arrow-char` / `.rename-box` / `.rename-input`），本次是纯搬迁，**保持无定义**，不在搬迁 PR 里夹带视觉改动。

- [ ] **Step 1: 写失败的测试**

在 `frontend/scripts/check-navigation-contract.mjs` 的 `// ---- 追加位` 那一行**之前**插入：

```js
// ==================== 项目列表页样式 ====================

// 说明：SCSS 里选择器后面必然跟空格、换行或逗号，用这三种收尾判存在，避免
// 「.stat-card」被「.stat-card-x」这类前缀关系误判成已存在。
const hasSelector = (css, sel) =>
  css.includes(sel + ' ') || css.includes(sel + '\n') || css.includes(sel + ',')

check('project-list.scss 搬齐了必需的样式块', () => {
  const css = readFrontend('src/pages/project-list/project-list.scss')
  const need = [
    '.page-project-list', '.project-list-container', '.main-content',
    '.content-header', '.header-actions', '.btn-primary-small', '.btn-secondary-small',
    '.cloud-accept-entry', '.projects-stats-row', '.stat-card',
    '.project-grid', '.project-item-card', '.card-deco-header', '.action-btn-icon',
    '.project-title-new', '.card-footer-new', '.member-avatar-new', '.add-member-btn-new',
    '.enter-btn-arrow', '.empty-state-dashed', '.dashed-icon',
    '.project-role-badge', '.role-owner', '.role-text',
    '.manager-avatar-wrapper', '.members-split-container', '.clients-group',
    '.act-glyph', '.badge-glyph',
  ].filter((sel) => !hasSelector(css, sel))
  return need.length ? '缺样式块: ' + need.join(', ') : null
})

check('project-list.scss 补齐了原页面无定义的三个 class', () => {
  const css = readFrontend('src/pages/project-list/project-list.scss')
  const miss = ['.panel-projects', '.loading-state', '.loading-text'].filter((s) => !css.includes(s))
  return miss.length ? '未补: ' + miss.join(', ') : null
})

check('project-list.scss 不许把两块死样式搬过来', () => {
  const css = readFrontend('src/pages/project-list/project-list.scss')
  const dead = ['.modal-mask', '.project-members', '.member-list'].filter((s) => css.includes(s))
  return dead.length ? '搬进了模板里已无命中的死样式: ' + dead.join(', ') : null
})

check('project-list.scss 守浅色外壳红线', () => {
  const css = readFrontend('src/pages/project-list/project-list.scss')
  if (!css.includes('#1A5336')) return '缺森林绿 #1A5336'
  if (!css.includes('#F8F9FA')) return '缺浅底 #F8F9FA'
  if (/background:\s*#(21262|1[0-9a-f]{5})\b/i.test(css)) return '外壳不做深色 chrome'
  return null
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav
```

Expected: 退出码 1，输出恰好这三条（占位文件里已经有 `.page-project-list`，所以它不在缺失清单里；「不许把死样式搬过来」这条现在是绿的）：
```
导航契约检查未通过：
  - project-list.scss 搬齐了必需的样式块 — 缺样式块: .project-list-container, .main-content, .content-header, .header-actions, .btn-primary-small, .btn-secondary-small, .cloud-accept-entry, .projects-stats-row, .stat-card, .project-grid, .project-item-card, .card-deco-header, .action-btn-icon, .project-title-new, .card-footer-new, .member-avatar-new, .add-member-btn-new, .enter-btn-arrow, .empty-state-dashed, .dashed-icon, .project-role-badge, .role-owner, .role-text, .manager-avatar-wrapper, .members-split-container, .clients-group, .act-glyph, .badge-glyph
  - project-list.scss 补齐了原页面无定义的三个 class — 未补: .panel-projects, .loading-state, .loading-text
  - project-list.scss 守浅色外壳红线 — 缺森林绿 #1A5336
```

- [ ] **Step 3: 最小实现**

整个 `project-list.scss` 用一条命令拼装（抽行 + 手写外壳 + 补齐三 class），不要手工转录 600 行 SCSS。直接粘贴执行：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
SRC="frontend/src/pages/userprofile/userprofile.vue" && \
OUT="frontend/src/pages/project-list/project-list.scss" && \
{
  cat <<'HEADER'
/* 项目列表页样式。
 *
 * 来源：2026-08-08 从 pages/userprofile/userprofile.vue 的「我的项目」tab 整块搬出。
 * 抽行区间：1299-1310 品牌配色变量 / 1430-1434 .role-text（与个人信息卡共用，是复制不是搬走）/
 * 1517-1593 页头与两个按钮 / 1595-1932 统计条与项目卡片 / 2031-2065 虚线空态 /
 * 2590-2617 角色徽章 / 2619-2728 负责人头像与成员分组。
 *
 * 没搬的两块：userprofile.vue 的 2381-2451 与 2453-2588（模板里已无 class 命中的死样式，
 * 成员改用 -new 变体、弹窗已组件化）；2730-2735 的 .empty-icon 服务收藏/待办空态，留在 userprofile。
 *
 * 原页面就没有定义的 class 这次补了三个（.panel-projects / .loading-state / .loading-text）；
 * 另外 .card-style-default / .badge-text-new / .arrow-char / .rename-box / .rename-input
 * 在原页面同样无定义，本次是纯搬迁不夹带视觉改动，保持无定义。
 *
 * 配色守浅色红线：白 / #F8F9FA 底 + 森林绿 #1A5336 + mint #5BD197 点缀，不做深色 chrome。
 */
HEADER
  sed -n '1299,1310p' "$SRC"
  echo
  cat <<'SHELL'
/* 页面外壳。原 .page-userprofile（:1311-1327）与 .main-content（:1509-1515）改写：
   本页没有左侧个人信息栏，容器由两列改成单列，因此 .workbench-container 不搬。 */
.page-project-list {
  min-height: 100vh;
  background: linear-gradient(135deg, #F8F9FA 0%, #E8F3ED 100%);
  padding: 40px 24px;
  box-sizing: border-box;
  color: $text-main;
}

.project-list-container {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 24px;
}

.main-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
SHELL
  echo
  echo '/* .role-text：卡片角色徽章与原页面的个人信息卡共用，这里是复制，userprofile 那份别删 */'
  sed -n '1430,1434p' "$SRC"
  echo
  sed -n '1517,1593p' "$SRC"
  echo
  sed -n '1595,1932p' "$SRC"
  echo
  sed -n '2031,2065p' "$SRC"
  echo
  sed -n '2590,2617p' "$SRC"
  echo
  sed -n '2619,2728p' "$SRC"
  echo
  cat <<'TAIL'
/* 补齐：这三个 class 在原页面模板里用了但全文无样式定义，加载态一直是裸文本 */
.panel-projects {
  width: 100%;
}

.loading-state {
  padding: 48px 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.loading-text {
  font-size: 14px;
  color: $text-secondary;
}

/* CLIENT 的空态：没有「新建项目」可点，虚线框只作说明用，不做 hover 抬升 */
.empty-state-dashed.client-empty {
  cursor: default;

  &:hover {
    border-color: $border-color;
    background: transparent;
  }
}

/* 响应式：源 userprofile.vue:2363-2379，只取与本页有关的两条
   （另两条 .workbench-container / .user-sidebar 是个人中心两列布局的，留在原页） */
@media screen and (max-width: 768px) {
  .projects-stats-row {
    flex-wrap: wrap;
  }

  .stat-card {
    min-width: 45%;
  }
}
TAIL
} > "$OUT" && echo "生成完毕：$(wc -l < "$OUT") 行"
```

Expected: 打印 `生成完毕：693 行`。

- [ ] **Step 4: 跑测试确认通过**

先用 sass 单独把它编一遍（这一步比大括号计数强得多：能抓出未定义变量、嵌套写坏、非法属性；`sass` 是 `frontend/package.json` 的 devDependency，Step 0 已装）：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && \
./node_modules/.bin/sass --no-source-map src/pages/project-list/project-list.scss /tmp/project-list-check.css && \
echo "sass 编译通过" && \
python3 -c "
import re
s = open('src/pages/project-list/project-list.scss').read()
b = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
assert b.count('{') == b.count('}'), '大括号不平衡: %d/%d，抽行区间搞错了' % (b.count('{'), b.count('}'))
print('大括号平衡:', b.count('{'), '/', b.count('}'))
"
```

Expected: 打印 `sass 编译通过` 与 `大括号平衡: 96 / 96`，sass 无 warning 无 error。

再跑护栏与构建：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav && npm run build:h5
```

Expected: PASS —— `导航契约检查通过`，`build:h5` 编译成功。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
rm -f /tmp/project-list-check.css && \
git add frontend/src/pages/project-list/project-list.scss frontend/scripts/check-navigation-contract.mjs && \
git commit -m "feat(frontend): 项目列表页样式外置，从个人中心抽行搬入并补齐三个无定义 class"
```

---

### Task 18: 项目列表页模板与脚本搬迁（角色文案收敛、CLIENT 隐藏、卡片跳概览页）

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/project-list/project-list.vue`（整文件重写）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/scripts/check-navigation-contract.mjs`（追加位之前加断言）
- Test: `frontend/scripts/check-navigation-contract.mjs`

**Interfaces:**
- Consumes:
  - `services/api.js`：`getMyProjects()` **返回裸数组**（后端 `ProjectController.java:193-200` 直接返 `List<ProjectCardDTO>`，无信封）——直接用返回值，**写 `res.data` 会恒空**（`admin.vue:2044-2049` 现在就是那么写的、list 恒空，别照抄）；`getProjectMembers(id)` 是信封，取 `res.data`；`deleteProject(id)` / `renameProject(id, name)` / `removeProjectMember(projectId, userId)` / `getCurrentUser()`（`/api/auth/me`，信封）。
  - `config/memberRoles.js`：`roleLabel` / `ROLE_LABELS`（角色文案**唯一来源**；源页面 `userprofile.vue:1135-1144` 自己硬编码了一份 `{OWNER:'负责人', ADMIN:'管理员', PARTICIPANT:'成员'…}`，与唯一来源里的「案件管理员」「协作人」不一致，搬迁时收敛掉）。
  - `config/projectTypes.js` 的 `getProjectTypeLabel`（旧数据回显用）、`config/icons.js` 的 `ICONS`（`trash:23` / `crown:32`）、`utils/auth.js` 的 `getCurrentUser`/`getSessionId`、`services/host.js` 的 `isDesktopHost`。
  - `components/InviteMemberDialog.vue`（emits `update:visible` / `close` / `success`）、`components/CloudAcceptDialog.vue`（`emits: ['accepted', 'update:visible']`，`accepted` 的 payload 是 `localProjectId`）。
- Produces: uni-app 页面 `pages/project-list/project-list`，无 props / 无 emits / 无 query 参数。根节点类名 `.page-project-list`（e2e 锚点，不许改）。`onLoad` 做登录闸 + 取用户信息，`onShow` 每次刷 `loadProjects()`。`goToProject(projectId)` → `uni.navigateTo({ url: '/pages/project-home/project-home?id=' + projectId })`。

> 搬迁口径四条硬要求：
> ① 删掉写死字面量 `0` 的「进行中」「已完成」两张统计卡（`userprofile.vue:80-87`，`Project` 实体没有状态字段，spec §4.3）；
> ② `CloudAcceptDialog` 与它的**两个**入口（`:63` 有项目时的顶部按钮 + `:102-105` 空项目态入口）一起搬，缺一个「从团队案件库取一份案卷」这条协作唯一入口就整个消失，而 `CollabDialog.vue:271` 的邀请话术还在指着它；
> ③ CLIENT 隐藏「+ 新建项目」「从团队案件库取一份案卷」与卡片上的删除/重命名/邀请；
> ④ 每个项目一次 `getProjectMembers` 的 N+1 **原样搬，不要顺手优化**——它是 spec §9 第 11 条的前置修复，属 Plan 3（改 `ProjectCardDTO` 会牵动 `ProjectService` 的 `BeanUtils.copyProperties` 那条静默失败链）。
>
> 本页**不做** `host.browser.setViewsVisible(false)`：源页面 `userprofile.vue:557-564` 有这一段，但 spec §319 已裁定非工作台页面不必自己隐藏 BrowserView（admin / plugin-market / variable-library 三个非工作台页都没做也没问题，兜底在工作台的 `onHide`/`onUnload`）。

- [ ] **Step 1: 写失败的测试**

在 `check-navigation-contract.mjs` 的 `// ---- 追加位` 之前插入：

```js
// ==================== 项目列表页脚本 ====================

check('项目列表页根节点带 e2e 锚点类名', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  return src.includes('class="page-project-list"') ? null : '根节点必须是 .page-project-list（e2e 锚点）'
})

check('项目列表页角色文案收敛到 config/memberRoles.js', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  if (!/from\s+'@\/config\/memberRoles\.js'/.test(src)) return "没有从 '@/config/memberRoles.js' 引入"
  if (/'PARTICIPANT'\s*:/.test(src)) return '页面里还残留自己硬编码的角色映射表'
  return null
})

check('项目列表页点卡片进项目概览页（navigateTo）', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  if (!src.includes('/pages/project-home/project-home?id=')) return 'goToProject 没有指向项目概览页'
  if (src.includes('/pages/project-overview/project-overview')) {
    return '不许从列表页直连工作台，必须先经概览页'
  }
  const i = src.indexOf('goToProject(projectId)')
  if (i < 0) return '找不到 goToProject(projectId)'
  if (!src.slice(i, i + 300).includes('navigateTo')) {
    return '列表页→概览页两端都不是工作台，必须用 navigateTo 不是 reLaunch'
  }
  return null
})

check('项目列表页删掉了写死 0 的两张统计卡', () => {
  // 禁字断言只看实际代码：注释里要写清楚「原先的进行中/已完成是写死的 0」，
  // 那段说明性文字不该把断言判红。
  const src = stripVueComments(readFrontend('src/pages/project-list/project-list.vue'))
  if (src.includes('进行中') || src.includes('已完成')) {
    return 'Project 实体没有状态字段，这两张卡的数字是写死的字面量 0，不许搬过来'
  }
  const cards = (src.match(/class="stat-card"/g) || []).length
  return cards === 1 ? null : '统计条应当只剩「全部项目」一张卡，实际 ' + cards + ' 张'
})

check('项目列表页带全 CloudAcceptDialog 的两个入口', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  if (!src.includes('<CloudAcceptDialog')) return '弹窗组件没搬过来'
  // 1 处 method 定义 + 2 处入口绑定（有项目态顶部按钮 / 空项目态入口）
  const entries = (src.match(/openCloudAccept/g) || []).length
  return entries >= 3 ? null : 'openCloudAccept 只出现 ' + entries + ' 次，两个入口缺一个'
})

check('项目列表页对 CLIENT 收起写操作入口', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  if (!/isClientUser\s*\(\)/.test(src)) return '缺 isClientUser computed'
  if (!src.includes('!isClientUser && projects.length > 0')) return '顶部两个动作按钮没有对 CLIENT 隐藏'
  if (!src.includes('canManageMembers')) return '成员增删没有对 CLIENT 收起'
  return null
})

check('项目列表页别把裸数组当信封解', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  if (/getMyProjects\(\)[\s\S]{0,80}\.data/.test(src)) {
    return 'getMyProjects 返回裸数组（ProjectController.java:193-200），取 .data 会恒空'
  }
  return null
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav
```

Expected: 退出码 1，输出这五条（占位文件里已有 `class="page-project-list"`，第一条与最后一条是绿的）：
```
导航契约检查未通过：
  - 项目列表页角色文案收敛到 config/memberRoles.js — 没有从 '@/config/memberRoles.js' 引入
  - 项目列表页点卡片进项目概览页（navigateTo） — goToProject 没有指向项目概览页
  - 项目列表页删掉了写死 0 的两张统计卡 — 统计条应当只剩「全部项目」一张卡，实际 0 张
  - 项目列表页带全 CloudAcceptDialog 的两个入口 — 弹窗组件没搬过来
  - 项目列表页对 CLIENT 收起写操作入口 — 缺 isClientUser computed
```

- [ ] **Step 3: 最小实现**

用下面的内容**整体覆盖** `frontend/src/pages/project-list/project-list.vue`：

```vue
<template>
  <view class="page-project-list">
    <view class="project-list-container">
      <view class="main-content">
        <view class="content-header">
          <text class="header-title">我的项目</text>
          <view v-if="!isClientUser && projects.length > 0" class="header-actions">
            <button class="btn-secondary-small" @tap="openCloudAccept">从团队案件库取一份案卷</button>
            <button class="btn-primary-small" @tap="goToNewProject">+ 新建项目</button>
          </view>
        </view>

        <view class="panel-projects">
          <!-- 只留「全部项目」一张卡：原先的「进行中」「已完成」是写死的字面量 0，
               Project 实体根本没有状态字段，搬迁时按 spec §4.3 删掉，不把假数字带过来 -->
          <view class="projects-stats-row">
            <view class="stat-card">
              <text class="stat-value">{{ projects.length }}</text>
              <text class="stat-label">全部项目</text>
            </view>
          </view>

          <view v-if="projectsLoading" class="loading-state">
            <text class="loading-text">加载中...</text>
          </view>

          <view v-else-if="projects.length === 0">
            <!-- CLIENT 没有建项目/取案卷的入口，空态只作说明 -->
            <template v-if="isClientUser">
              <view class="empty-state-dashed client-empty">
                <view class="dashed-content">
                  <text class="dashed-text">律师把案卷分享给你之后，会出现在这里</text>
                </view>
              </view>
            </template>
            <template v-else>
              <view class="empty-state-dashed" @tap="goToNewProject">
                <view class="dashed-content">
                  <text class="dashed-icon">+</text>
                  <text class="dashed-text">新建项目</text>
                </view>
              </view>
              <!-- 协作的唯一入口。CollabDialog 的邀请话术写死指向这里，别删 -->
              <view class="cloud-accept-entry" @tap="openCloudAccept">
                <text class="cloud-accept-entry-text">从团队案件库取一份案卷</text>
              </view>
            </template>
          </view>

          <view v-else class="project-grid">
            <view
              v-for="project in projects"
              :key="project.id"
              class="project-item-card"
              :class="getProjectCardClass(project.projectType)"
              @tap="goToProject(project.id)"
            >
              <view class="card-deco-header"></view>

              <view class="card-top-row">
                <view class="project-type-badge-new">
                  <text class="badge-text-new">{{ getProjectTypeLabel(project.projectType) }}</text>
                </view>
                <view v-if="!isClientUser" class="card-actions">
                  <view class="action-btn-icon danger" @tap.stop="handleDeleteProject(project.id)" title="删除"><svg class="act-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                </view>
              </view>

              <view class="card-main-content">
                <view class="project-title-area">
                  <view v-if="renamingProjectId === project.id" class="rename-box" @tap.stop>
                    <input
                      class="rename-input"
                      v-model="renameValue"
                      :focus="true"
                      @confirm="confirmRename"
                      @blur="cancelRename"
                    />
                  </view>
                  <view v-else class="title-row-flex">
                    <text class="project-title-new" @tap.stop="startRename(project)">{{ project.name }}</text>
                    <view class="project-role-badge" :class="getRoleClass(project.myRole)">
                      <text class="role-text">{{ getRoleLabel(project.myRole) }}</text>
                    </view>
                  </view>
                </view>

                <view class="company-info-area" v-if="project.projectType !== 'BLANK'">
                  <view class="info-row-new" v-if="shouldShowListedCompany(project.projectType)">
                    <text class="info-label-new">上市公司</text>
                    <text class="info-val-new highlight">{{ project.listedCompanyName || '-' }}</text>
                  </view>
                  <view class="info-row-new" v-if="shouldShowTargetCompany(project.projectType)">
                    <text class="info-label-new">标的公司</text>
                    <text class="info-val-new">{{ project.targetCompanyName || '-' }}</text>
                  </view>
                </view>
                <view v-else class="blank-placeholder">
                  <text class="placeholder-text">通用项目工作区</text>
                </view>
              </view>

              <view class="card-footer-new">
                <view class="members-area-new">
                  <view class="manager-avatar-wrapper" v-if="project.managerId" :title="'项目负责人: ' + (project.managerName || '未知')">
                    <image v-if="project.managerAvatarUrl" :src="project.managerAvatarUrl" class="manager-avatar-img" />
                    <view v-else class="manager-avatar-placeholder">{{ project.managerName?.charAt(0) || 'M' }}</view>
                    <view class="manager-badge-icon"><svg class="badge-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.crown" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                  </view>
                  <view class="members-divider" v-if="project.managerId && getInternalMembers(project).length > 0"></view>

                  <view class="members-split-container">
                    <view class="members-group">
                      <view v-for="member in getInternalMembers(project)" :key="member.id" class="member-avatar-new" :title="member.displayName">
                        <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img-new" />
                        <view v-else class="avatar-placeholder-new">{{ member.displayName?.charAt(0) || 'U' }}</view>
                        <view v-if="canManageMembers(project) && member.userId !== userInfo.id" class="member-remove-overlay" @tap.stop="removeMember(project.id, member.userId)">×</view>
                      </view>
                      <view v-if="canManageMembers(project)" class="add-member-btn-new" @tap.stop="openInviteModal(project.id)">+</view>
                    </view>

                    <view class="members-vertical-divider" v-if="getClientMembers(project).length > 0"></view>

                    <view class="members-group clients-group" v-if="getClientMembers(project).length > 0">
                      <text class="client-group-label">客户</text>
                      <view v-for="member in getClientMembers(project)" :key="member.id" class="member-avatar-new client-avatar" :title="member.displayName + ' (客户)'">
                        <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img-new" />
                        <view v-else class="avatar-placeholder-new client-placeholder">{{ member.displayName?.charAt(0) || '客' }}</view>
                        <view v-if="canManageMembers(project)" class="member-remove-overlay" @tap.stop="removeMember(project.id, member.userId)">×</view>
                      </view>
                    </view>
                  </view>
                </view>
                <view class="footer-meta">
                  <text class="time-text-new">{{ formatTime(project.createdAt) }}</text>
                  <view class="enter-btn-arrow">
                    <text class="arrow-char">→</text>
                  </view>
                </view>
              </view>
            </view>
          </view>
        </view>
      </view>
    </view>

    <InviteMemberDialog
      v-model:visible="showInviteModal"
      :project-id="currentInviteProjectId"
      @success="loadProjects"
      @close="closeInviteModal"
    />

    <CloudAcceptDialog
      v-model:visible="showCloudAccept"
      @accepted="onCloudAccepted"
    />
  </view>
</template>

<script>
/**
 * 项目列表页（三级导航第一级）。
 *
 * 2026-08-08 从 pages/userprofile/userprofile.vue 的「我的项目」tab 整块搬出：
 * 项目寄居在个人中心的一个 tab 里，是前端把项目概念弱化掉的历史遗留（后端 Project
 * 一直是一等公民）。搬出来之后个人中心只管人，这里只管案卷。
 *
 * 点卡片主体进的是**项目概览页**（pages/project-home），不是工作台——工作台是第三级。
 *
 * 本页不做 host.browser.setViewsVisible(false)：非工作台页面不必自己隐藏 BrowserView，
 * 兜底在工作台的 onHide/onUnload（admin / plugin-market / variable-library 都没做）。
 */
import { getMyProjects, deleteProject, renameProject, getProjectMembers, removeProjectMember, getCurrentUser as getCurrentUserApi } from '@/services/api.js'
import { getProjectTypeLabel } from '@/config/projectTypes.js'
import { roleLabel, ROLE_LABELS } from '@/config/memberRoles.js'
import { getCurrentUser, getSessionId } from '@/utils/auth.js'
import { isDesktopHost } from '@/services/host.js'
import { ICONS } from '@/config/icons.js'
import InviteMemberDialog from '@/components/InviteMemberDialog.vue'
import CloudAcceptDialog from '@/components/CloudAcceptDialog.vue'

export default {
  name: 'ProjectList',
  components: {
    InviteMemberDialog,
    CloudAcceptDialog,
  },
  computed: {
    ICONS() {
      return ICONS
    },
    isDesktop() {
      return isDesktopHost()
    },
    // CLIENT 看得见别人分享给他的案卷（ProjectService.getUserProjects 把成员身份的项目
    // 也算进去），但建项目/取案卷/删除/重命名/邀请全部对他隐藏。
    // 角色在一次会话里不会变，computed 无响应式依赖只算一次正合适。
    isClientUser() {
      const u = getCurrentUser()
      return !!u && u.role === 'CLIENT'
    },
  },
  data() {
    return {
      userInfo: {
        id: null,
        username: '',
        displayName: '用户',
        avatarUrl: null,
      },
      projects: [],
      projectsLoading: false,
      deletingProjectId: null,
      renamingProjectId: null,
      renameValue: '',

      showInviteModal: false,
      currentInviteProjectId: null,
      showCloudAccept: false,
    }
  },
  onLoad() {
    if (!this.ensureLoggedIn()) return
    this.loadUserInfo()
  },
  onShow() {
    // 从概览页 navigateBack、从新建项目页回来都要看到最新结果（改名/删除都在这一页做）
    if (!this.ensureLoggedIn()) return
    this.loadProjects()
  },
  methods: {
    // 浏览器端未登录直接回登录页；桌面 local-mode 免登，跳过该检查（同 userprofile.vue:565-578）
    ensureLoggedIn() {
      if (isDesktopHost()) return true
      if (getSessionId() && getCurrentUser()) return true
      uni.reLaunch({ url: '/pages/login/login' })
      return false
    },
    async loadUserInfo() {
      const user = getCurrentUser()
      if (user) this.userInfo = user
      // isProjectAdmin 要靠 userInfo.id，缓存里可能没有，拉一次 /api/auth/me 补齐
      try {
        const res = await getCurrentUserApi()
        if (res && res.code === 0 && res.data) {
          this.userInfo = { ...this.userInfo, ...res.data }
        }
      } catch (e) {
        // 拿不到就用缓存那份，不拦路
        console.error('获取用户信息失败:', e)
      }
    },
    async loadProjects() {
      this.projectsLoading = true
      try {
        // getMyProjects 返回的是裸数组（ProjectController 直接返 List<ProjectCardDTO>，
        // 无信封）。这里写 res.data 会恒空——admin.vue 现在就踩着这个坑。
        const projects = await getMyProjects()
        // 每个项目一次成员查询（N+1）。这是既有行为，spec §9 第 11 条已记为前置修复，
        // 属 Plan 3；本次原样搬，不要顺手优化（改 ProjectCardDTO 会牵动
        // ProjectService 的 BeanUtils.copyProperties 那条静默失败链）。
        const projectsWithMembers = await Promise.all(projects.map(async (p) => {
          try {
            const res = await getProjectMembers(p.id)
            let members = res.data || []
            // getProjectMembers 返回 project_member 裸行，owner 可能另有一行，去重
            const seen = new Set()
            members = members.filter((m) => {
              if (seen.has(m.userId)) return false
              seen.add(m.userId)
              return true
            })
            return { ...p, members }
          } catch (e) {
            console.error(`Failed to load members for project ${p.id}`, e)
            return { ...p, members: [] }
          }
        }))
        this.projects = projectsWithMembers
      } catch (error) {
        console.error('加载项目列表失败:', error)
        // 桌面端免登：绝不跳 login（launch 分流已保证桌面不进登录页，这里若跳就是死胡同），
        // 只提示错误。浏览器端保留原「登录失效回登录页」兜底。
        if (!this.isDesktop && error.message && error.message.includes('登录')) {
          uni.reLaunch({ url: '/pages/login/login' })
        } else {
          uni.showToast({
            title: error.message || '加载失败，请稍后重试',
            icon: 'none',
            duration: 2000,
          })
        }
      } finally {
        this.projectsLoading = false
      }
    },

    // ---- 成员 ----
    openInviteModal(projectId) {
      this.currentInviteProjectId = projectId
      this.showInviteModal = true
    },
    closeInviteModal() {
      this.showInviteModal = false
      this.currentInviteProjectId = null
    },
    async removeMember(projectId, userId) {
      uni.showModal({
        title: '确认移除',
        content: '确定要移除该成员吗？',
        cancelText: '取消',
        confirmText: '确认',
        success: async (res) => {
          if (res.confirm) {
            try {
              await removeProjectMember(projectId, userId)
              uni.showToast({ title: '移除成功', icon: 'success' })
              this.loadProjects()
            } catch (e) {
              uni.showToast({ title: e.message || '移除失败', icon: 'none' })
            }
          }
        },
      })
    },
    isProjectAdmin(project) {
      if (!this.userInfo || !project) return false
      if (project.userId === this.userInfo.id) return true
      const member = project.members?.find((m) => m.userId === this.userInfo.id)
      return member && member.role === 'ADMIN'
    },
    canManageMembers(project) {
      return !this.isClientUser && this.isProjectAdmin(project)
    },
    // 角色文案唯一来源是 config/memberRoles.js（源页面自己硬编码了一份「管理员/成员」，
    // 与唯一来源里的「案件管理员/协作人」不一致，搬迁时收敛）
    getRoleLabel(role) {
      return roleLabel(role) || ROLE_LABELS.PARTICIPANT
    },
    getRoleClass(role) {
      if (role === 'OWNER') return 'role-owner'
      if (role === 'ADMIN') return 'role-admin'
      if (role === 'CLIENT') return 'role-client'
      return 'role-member'
    },
    getInternalMembers(project) {
      if (!project.members) return []
      return project.members.filter((m) => {
        const isClient = ['CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC'].includes(m.role)
        const isManager = project.managerId && m.userId === project.managerId
        return !isClient && !isManager
      })
    },
    getClientMembers(project) {
      if (!project.members) return []
      return project.members.filter((m) => ['CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC'].includes(m.role))
    },

    // ---- 卡片展示 ----
    // Project.projectType 是重大资产重组时代的遗留列，只用于旧数据回显；
    // 概览页的「事项类型」以 project_profile_field.matterType 为准，两者冲突时不提示
    getProjectTypeLabel(projectType) {
      return getProjectTypeLabel(projectType) || projectType
    },
    getProjectCardClass(type) {
      if (['MAJOR_ASSET_RESTRUCTURING', 'ACQUISITION'].includes(type)) {
        return 'card-style-restructuring'
      } else if (['PRIVATE_PLACEMENT', 'PUBLIC_PLACEMENT'].includes(type)) {
        return 'card-style-refinancing'
      } else if (type === 'BLANK') {
        return 'card-style-blank'
      }
      return 'card-style-default'
    },
    shouldShowListedCompany(type) {
      return type !== 'BLANK'
    },
    shouldShowTargetCompany(type) {
      return ['MAJOR_ASSET_RESTRUCTURING', 'ACQUISITION'].includes(type)
    },
    formatTime(timeStr) {
      if (!timeStr) return ''
      try {
        const date = new Date(timeStr)
        const year = date.getFullYear()
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        return `${year}-${month}-${day}`
      } catch (e) {
        return timeStr
      }
    },

    // ---- 导航与写操作 ----
    // 列表页 → 概览页：两端都不是工作台，用 navigateTo（工作台参与的跳转才 reLaunch）
    goToProject(projectId) {
      uni.navigateTo({
        url: `/pages/project-home/project-home?id=${projectId}`,
      })
    },
    openCloudAccept() {
      this.showCloudAccept = true
    },
    onCloudAccepted(localProjectId) {
      this.loadProjects()
      if (localProjectId) this.goToProject(localProjectId)
    },
    async handleDeleteProject(projectId) {
      uni.showModal({
        title: '确认删除',
        content: '确定要删除这个项目吗？删除后无法恢复。',
        cancelText: '取消',
        confirmText: '确认',
        success: async (res) => {
          if (res.confirm) {
            this.deletingProjectId = projectId
            try {
              await deleteProject(projectId)
              uni.showToast({ title: '删除成功', icon: 'success', duration: 2000 })
              await this.loadProjects()
            } catch (error) {
              console.error('删除项目失败:', error)
              uni.showToast({
                title: error.message || '删除失败，请稍后重试',
                icon: 'none',
                duration: 2000,
              })
            } finally {
              this.deletingProjectId = null
            }
          }
        },
      })
    },
    goToNewProject() {
      uni.navigateTo({ url: '/pages/newproject/index' })
    },
    startRename(project) {
      if (this.isClientUser) return
      this.renamingProjectId = project.id
      this.renameValue = project.name
    },
    async confirmRename() {
      if (!this.renameValue || !this.renameValue.trim()) {
        uni.showToast({ title: '项目名称不能为空', icon: 'none' })
        return
      }
      try {
        await renameProject(this.renamingProjectId, this.renameValue.trim())
        const project = this.projects.find((p) => p.id === this.renamingProjectId)
        if (project) {
          project.name = this.renameValue.trim()
        }
        this.renamingProjectId = null
        this.renameValue = ''
        uni.showToast({ title: '重命名成功', icon: 'success' })
      } catch (e) {
        console.error('重命名失败', e)
        uni.showToast({ title: '重命名失败', icon: 'none' })
      }
    },
    cancelRename() {
      this.renamingProjectId = null
      this.renameValue = ''
    },
  },
}
</script>

<style lang="scss" scoped src="./project-list.scss"></style>
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav && npm run check:emits && npm run build:h5
```

Expected: PASS —— `导航契约检查通过`；`check:emits` 打印 `✓ emit/绑定契约检查通过（扫描 N 个 .vue）`（`InviteMemberDialog` 的 `@success`/`@close`、`CloudAcceptDialog` 的 `@accepted` 都是两个组件已声明/已 `$emit` 的事件，不应报死绑定）；`build:h5` 编译成功。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/pages/project-list/project-list.vue frontend/scripts/check-navigation-contract.mjs && \
git commit -m "feat(frontend): 项目列表页搬迁完成，角色文案收敛到 memberRoles，删掉写死的两张统计卡"
```

---

### Task 19: 个人中心瘦身：删掉搬走的项目 tab，默认落工作记录

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/userprofile/userprofile.vue`
  - 模板：`:62-65`（header-actions 整块）、`:71-214`（`<!-- 我的项目 Tab -->` 注释 + panel-projects 整块）、`:217`（`v-else-if` 改 `v-if`）、`:449-461`（两个弹窗含注释行）
  - 导入与组件：`:467`（`getProjectTypeLabel` 整行删）、`:470-471`（两个弹窗组件的 import）、`:486-489`（`components` 选项整块）、`:466`（具名导入定点重写）
  - data 与生命周期：`:492`（activeTab 默认值）、`:494`（tabs 删 projects 项）、`:506-510`、`:514-516`、`:585`（`loadProjects()` → `loadActivityLogs()`）
  - methods：`:1058-1184`、`:1212-1260`、`:1266-1293`
  - 样式：`:1535-1932`、`:2031-2065`、`:2372-2378`、`:2590-2617`、`:2619-2728`
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/scripts/check-navigation-contract.mjs`
- Test: `frontend/scripts/check-navigation-contract.mjs`

**Interfaces:**
- Consumes: 无新增。
- Produces: `userprofile.vue` 只剩「工作记录 / 我的收藏 / 我的代办 / 设置」四个 tab（管理员多一个「系统设置」，由 `checkAdminTab` 动态插）。`activeTab` 默认 `'work_log'`。文件从 2736 行变成 1778 行。

> **三条漏了会静默出错的**：① `activeTab` 默认值不改就得到一个默认打开空白 tab 的个人中心；② tabs 数组里的 projects 项不删，点它得到一个什么都不渲染的面板；③ **`:585` 的 `this.loadProjects()` 必须换成 `this.loadActivityLogs()`，不是删除**——工作记录是懒加载的（只在 `switchTab:723-735` 的 `key === 'work_log'` 分支里触发，实现在 `:737`），删了就得到一个默认打开却永远空白的 tab。
> **不要删**（已逐个 grep 核过还有别的调用方）：`:1185-1196` 的 `formatTime` 与 `:1197-1211` 的 `formatDateTime`（收藏 `:279`、授权 `:413`、令牌 `:431`、工作记录 `:750/:773/:775/:778` 都在用）、`:1261-1265` 的 `goToAdmin`（预先存在的死方法，不属本次清理范围）、`:1430-1434` 的 `.role-text`（个人信息卡 `:31` 的「标准用户」标签在用）、`:2730-2735` 的 `.empty-icon`（收藏/待办空态 `:258/:289` 在用）、`:1298-1310` 的配色变量、`:1517-1534` 的 `.content-header`/`.header-title`（页头还在）、`:476-484` 的 `ICONS` 与 `isDesktop` 两个 computed（`:258/:289` 与 `:314/:403/:419/:439` 还在用）、`:466` 里的 `addProjectMember` 与 `inviteClient`（**搬迁之前就已经是死导入**，全文只出现在 import 行，属预先存在的死代码）。

- [ ] **Step 1: 写失败的测试**

在 `check-navigation-contract.mjs` 的 `// ---- 追加位` 之前插入：

```js
// ==================== 个人中心瘦身 ====================

check('个人中心默认 tab 不再是被搬走的 projects', () => {
  const src = readFrontend('src/pages/userprofile/userprofile.vue')
  if (!/activeTab:\s*'work_log'/.test(src)) return "activeTab 默认值必须是 'work_log'"
  if (/key:\s*'projects'/.test(src)) return 'tabs 数组里还留着 projects 项'
  return null
})

check('个人中心默认 tab 有人给它加载数据', () => {
  const src = readFrontend('src/pages/userprofile/userprofile.vue')
  // 工作记录是懒加载的（只在 switchTab 里触发），默认落它就必须在 onLoad 里补一次
  const onLoad = src.slice(src.indexOf('onLoad()'), src.indexOf('methods:'))
  return onLoad.includes('this.loadActivityLogs()')
    ? null
    : 'onLoad 里没有 loadActivityLogs()，默认 tab 会永远空白'
})

check('个人中心已清空项目相关的模板与方法', () => {
  const src = readFrontend('src/pages/userprofile/userprofile.vue')
  const left = [
    'project-item-card', 'panel-projects', 'projects-stats-row',
    'loadProjects', 'goToProject', 'handleDeleteProject', 'confirmRename',
    'CloudAcceptDialog', 'InviteMemberDialog', 'getRoleLabel',
  ].filter((s) => src.includes(s))
  return left.length ? '还残留: ' + left.join(', ') : null
})

check('个人中心已摘掉被搬迁搞成孤儿的导入', () => {
  const src = readFrontend('src/pages/userprofile/userprofile.vue')
  const orphan = ['getMyProjects', 'deleteProject', 'renameProject', 'getProjectMembers', 'removeProjectMember', 'getProjectTypeLabel']
    .filter((s) => src.includes(s))
  return orphan.length ? '孤儿导入: ' + orphan.join(', ') : null
})

check('个人中心保住了不该删的东西', () => {
  const src = readFrontend('src/pages/userprofile/userprofile.vue')
  const gone = ['formatTime(', 'formatDateTime(', '.role-text', '.empty-icon', 'loadActivityLogs', 'loadFavorites', 'addProjectMember', 'inviteClient']
    .filter((s) => !src.includes(s))
  return gone.length ? '误删: ' + gone.join(', ') : null
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav
```

Expected: 退出码 1，输出这四条（最后一条「保住了不该删的东西」现在是绿的）：
```
导航契约检查未通过：
  - 个人中心默认 tab 不再是被搬走的 projects — activeTab 默认值必须是 'work_log'
  - 个人中心默认 tab 有人给它加载数据 — onLoad 里没有 loadActivityLogs()，默认 tab 会永远空白
  - 个人中心已清空项目相关的模板与方法 — 还残留: project-item-card, panel-projects, projects-stats-row, loadProjects, goToProject, handleDeleteProject, confirmRename, CloudAcceptDialog, InviteMemberDialog, getRoleLabel
  - 个人中心已摘掉被搬迁搞成孤儿的导入 — 孤儿导入: getMyProjects, deleteProject, renameProject, getProjectMembers, removeProjectMember, getProjectTypeLabel
```

- [ ] **Step 3a: 纯删除（必须从大行号往小行号删，否则行号会漂）**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
F="frontend/src/pages/userprofile/userprofile.vue" && \
cp "$F" "$F.bak" && \
for r in 2619,2728 2590,2617 2372,2378 2031,2065 1535,1932 1266,1293 1212,1260 1058,1184 514,516 506,510 494,494 486,489 470,471 467,467 449,461 71,214 62,65; do \
  sed -i '' "${r}d" "$F"; \
done && \
echo "删完剩 $(wc -l < "$F") 行（原 2736 行，共删 959 行，应剩 1777 行）"
```

Expected: 打印 `删完剩 1777 行（原 2736 行，共删 959 行，应剩 1777 行）`。

删除区间逐条对照（删之前可以用 `sed -n 'Np' frontend/src/pages/userprofile/userprofile.vue.bak` 抽查）：

| 区间 | 内容 |
|---|---|
| `2619,2728` | `/* Manager Avatar */` 到 `.badge-glyph`（负责人头像 + 成员分组布局 + 两个 glyph） |
| `2590,2617` | `/* Project Role Badge */` 到 `.role-member` |
| `2372,2378` | 响应式段里 `.projects-stats-row` 与 `.stat-card` 两条（`.workbench-container`/`.user-sidebar` 两条留下） |
| `2031,2065` | `/* Dashed Empty State */` 到 `.dashed-text` |
| `1535,1932` | `.header-actions` 到 `.project-item-card:hover .enter-btn-arrow`（含两个按钮、cloud-accept-entry、统计条、项目卡片全套；`.content-header`/`.header-title` 在 1517-1534，页头还在用，不删） |
| `1266,1293` | `startRename` / `confirmRename` / `cancelRename` |
| `1212,1260` | `goToProject` / `openCloudAccept` / `onCloudAccepted` / `handleDeleteProject` / `goToNewProject`（`goToAdmin:1261-1265` 在这之后，保留） |
| `1058,1184` | `loadProjects` 到 `shouldShowTargetCompany`（含 `// Member Management` 注释块） |
| `514,516` | `showInviteModal` / `currentInviteProjectId` / `showCloudAccept` |
| `506,510` | `projects` / `projectsLoading` / `deletingProjectId` / `renamingProjectId` / `renameValue`（`:511 favoritesLoading` 与 `:512 favorites` 属收藏 tab，不删） |
| `494,494` | tabs 里的 `{ key: 'projects', label: '我的项目' },` |
| `486,489` | `components: { InviteMemberDialog, CloudAcceptDialog },` 整块（删空后 components 选项为空，整块拿掉） |
| `470,471` | 两个弹窗组件的 import |
| `467,467` | `import { getProjectTypeLabel } from '@/config/projectTypes.js'` |
| `449,461` | 模板尾部两个弹窗（含各自的注释行） |
| `71,214` | `<!-- 我的项目 Tab -->` 注释 + `panel-projects` 整块 |
| `62,65` | `header-actions` 整块（顶部两个按钮） |

- [ ] **Step 3b: 四处文本编辑（行号已漂，按文本匹配改，不要按行号）**

① 删掉 `header-actions` 与 projects 面板后，「工作记录 Tab」那个 `v-else-if` 变成了没有 `v-if` 前驱的孤儿：

```
-          <view v-else-if="activeTab === 'work_log'" class="panel-work-log">
+          <view v-if="activeTab === 'work_log'" class="panel-work-log">
```

② 默认 tab：

```
-      activeTab: 'projects',
+      activeTab: 'work_log',
```

③ `onLoad` 的 `$nextTick` 里换掉那一句（原 `:584-585`）：

```
-      // 加载项目列表
-      this.loadProjects()
+      // 默认 tab 是「工作记录」，它和收藏一样是懒加载的（只在 switchTab 里触发），
+      // 默认落它就必须在这里补一次，否则一进来是一张永远空白的表
+      this.loadActivityLogs()
```

④ 摘掉被搬迁搞成孤儿的五个具名导入。把那条 `from '@/services/api.js'` 的 import 行**整行替换**成（`addProjectMember` 与 `inviteClient` 搬迁之前就已经是死导入，属预先存在的死代码，留着）：

```js
import { getCurrentUser as getCurrentUserApi, getMyFavorites, deleteFavorite, getFavoriteImageUrl, addProjectMember, getUserActivityHistory, inviteClient, uploadAvatar, getLicenseStatus, deactivateLicense, sendSmsCode, bindPhone, sendMailCode, bindEmail, totpSetup, totpActivate, totpDisable, issueLocalDeviceToken, listDeviceTokens, revokeDeviceToken } from '@/services/api.js'
```

- [ ] **Step 3c: 删掉备份并做一次结构自查**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
rm -f frontend/src/pages/userprofile/userprofile.vue.bak && \
python3 -c "
import re
p = 'frontend/src/pages/userprofile/userprofile.vue'
s = open(p).read()
lines = s.count(chr(10)) + 1
css = s[s.index('<style'):]
b = re.sub(r'/\*.*?\*/', '', css, flags=re.S)
assert b.count('{') == b.count('}'), '样式段大括号不平衡: %d/%d' % (b.count('{'), b.count('}'))
assert 'components:' not in s, 'components 选项没删干净'
# 5 = 1 处 activeTab === tab.key（左侧导航）+ 4 个 tab 面板分支
assert s.count('activeTab ===') == 5, 'activeTab === 应当恰好 5 处，实际 %d' % s.count('activeTab ===')
assert s.count(chr(60) + 'view v-if=\"activeTab === ' + chr(39) + 'work_log' + chr(39)) == 1, 'work_log 分支没改成 v-if'
print('OK，%d 行' % lines)
"
```

Expected: 打印 `OK，1778 行`。

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav && npm run check:emits && npm run build:h5
```

Expected: PASS —— `导航契约检查通过`；`check:emits` 无死绑定；`build:h5` 编译成功（这一步是模板 `v-if/v-else-if` 链与样式段删除的真正校验，CI 跑的也是它）。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/pages/userprofile/userprofile.vue frontend/scripts/check-navigation-contract.mjs && \
git commit -m "refactor(frontend): 个人中心去掉项目 tab，默认落工作记录并补上它的加载"
```

---

### Task 20: 六条「我的项目」入口改指项目列表页，五条直达工作台的出口守住不动

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/launch/launch.vue:99`
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/login/login.vue:282`（注释）、`:290`、`:299`、`:392`、`:472`
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/newproject/index.vue:33`、`:96`、`:175-177`
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/project-overview/project-overview.vue:2689-2692`（`goAllProjects`）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/scripts/check-navigation-contract.mjs`
- Test: `frontend/scripts/check-navigation-contract.mjs`

**Interfaces:**
- Consumes: 无新增。
- Produces: 总规则落地 —— **凡是工作台参与的跳转一律 `reLaunch`；工作台之外的页面之间用 `navigateTo`**。

**改（6 条通往「我的项目」的入口，行号已实测，与 spec §5.3 写的有偏移，以这里为准）**

| # | 位置 | 现状 | 改成 |
|---|---|---|---|
| 1 | `launch.vue:99` 启动无最近项目兜底 | `reLaunch` → userprofile | `reLaunch` → project-list |
| 2 | `login.vue:290` tryAutoResume 的 CLIENT 分支 | `reLaunch` → userprofile | `reLaunch` → project-list |
| 3 | `login.vue:299` 会话恢复但无最近项目 | `reLaunch` → userprofile | `reLaunch` → project-list |
| 4 | `login.vue:392` finishLogin 登录成功 | `reLaunch` → userprofile | `reLaunch` → project-list |
| 5 | `login.vue:472` 注册成功 | `reLaunch` → userprofile | `reLaunch` → project-list |
| 6 | `newproject/index.vue:176` 返回 | `navigateTo` → userprofile | `navigateTo` → project-list |
| 7 | `project-overview.vue:2691` goAllProjects | `navigateTo` → userprofile | **`reLaunch`** → project-list（工作台参与，必须 reLaunch） |

CLIENT 落点重判的判据：`ProjectService.getUserProjects:134-146` 把「成员身份的项目」也算进去，CLIENT 有成员行就能在列表页看到自己那份案卷；项目 tab 搬走之后落个人中心会得到一个空白页。

**不改（5 条直达工作台的出口，本任务一行都不许碰）**：`launch.vue:97`（启动直达）、`login.vue:297`（会话恢复直达）、`App.vue:45`（应用菜单「最近打开」）、`utils/ideOpen.js:22`（打开本地文件夹/文件）、`project-overview.vue:2687`（顶栏切换器 `switchToProject`）。

**也不改**：`project-overview.vue:3740-3742` 的 `goToUserProfile`（rail 头像 → 个人中心，仍 `navigateTo`——它依赖页面栈保留实例以便 `onShow` 回流刷新）、`admin.vue:1819`/`:1823`（返回个人中心）、`admin.vue:2017` 切换本机工作区时的 `removeStorageSync('checkba_last_project_id')`（改 `launch.vue:99` 之后这条链变成「切身份 → 落项目列表页」，语义正确，**别把那行删了**）。

- [ ] **Step 1: 写失败的测试**

在 `check-navigation-contract.mjs` 的 `// ---- 追加位` 之前插入：

```js
// ==================== 导航入口与出口 ====================

const USERPROFILE_ROUTE = '/pages/userprofile/userprofile'
const countOf = (s, sub) => s.split(sub).length - 1

check('launch 无最近项目兜底落项目列表页', () => {
  const src = readFrontend('src/pages/launch/launch.vue')
  if (src.includes(USERPROFILE_ROUTE)) return '还指着个人中心'
  if (!src.includes("reLaunch({ url: '/pages/project-list/project-list' })")) return '没有指向项目列表页'
  if (!src.includes('/pages/project-overview/project-overview?id=')) return '启动直达工作台那条被改坏了'
  return null
})

check('login 四处落点全改项目列表页', () => {
  const src = readFrontend('src/pages/login/login.vue')
  if (src.includes(USERPROFILE_ROUTE)) return '还有指着个人中心的落点'
  const n = countOf(src, '/pages/project-list/project-list')
  if (n !== 4) return '应当恰好四处（CLIENT 分支 / 无最近项目兜底 / 登录成功 / 注册成功），实际 ' + n
  if (!src.includes('/pages/project-overview/project-overview?id=')) return '会话恢复直达工作台那条被改坏了'
  return null
})

check('newproject 返回项目列表页且仍用 navigateTo', () => {
  const src = readFrontend('src/pages/newproject/index.vue')
  if (src.includes(USERPROFILE_ROUTE)) return '还指着个人中心'
  if (!src.includes("navigateTo({ url: '/pages/project-list/project-list' })")) {
    return '两端都不是工作台，应当 navigateTo 到项目列表页'
  }
  if (src.includes('goToUserProfile')) return '方法名还叫 goToUserProfile，与它现在的去向不符'
  if (countOf(src, 'goToProjectList') !== 3) {
    return 'goToProjectList 应当恰好 3 处（1 处定义 + 模板两处绑定），实际 ' + countOf(src, 'goToProjectList')
  }
  return null
})

check('工作台「全部项目」用 reLaunch 去项目列表页', () => {
  const src = readFrontend('src/pages/project-overview/project-overview.vue')
  const i = src.indexOf('goAllProjects()')
  if (i < 0) return '找不到 goAllProjects'
  const body = src.slice(i, i + 400)
  if (!body.includes('/pages/project-list/project-list')) return 'goAllProjects 没有指向项目列表页'
  if (!body.includes('reLaunch')) return '工作台参与的跳转一律 reLaunch，不能用 navigateTo'
  return null
})

check('工作台 rail 头像仍 navigateTo 个人中心（不许顺手改）', () => {
  const src = readFrontend('src/pages/project-overview/project-overview.vue')
  const i = src.indexOf('goToUserProfile()')
  if (i < 0) return '找不到 goToUserProfile'
  const body = src.slice(i, i + 200)
  if (!body.includes(USERPROFILE_ROUTE) || !body.includes('navigateTo')) {
    return '它依赖页面栈保留实例以便 onShow 回流刷新，本次不改'
  }
  return null
})

check('五条直达工作台的出口一条都没动', () => {
  const bad = []
  if (!readFrontend('src/App.vue').includes('/pages/project-overview/project-overview?id=')) bad.push('App.vue 应用菜单「最近打开」')
  if (!readFrontend('src/utils/ideOpen.js').includes('/pages/project-overview/project-overview?')) bad.push('ideOpen.js 打开本地文件夹/文件')
  const ov = readFrontend('src/pages/project-overview/project-overview.vue')
  const i = ov.indexOf('switchToProject(p)')
  if (i < 0 || !ov.slice(i, i + 400).includes('/pages/project-overview/project-overview?id=')) bad.push('顶栏切换器 switchToProject')
  return bad.length ? '被改坏: ' + bad.join(', ') : null
})

check('admin 切换本机工作区仍清最近项目', () => {
  const src = readFrontend('src/pages/admin/admin.vue')
  return src.includes("removeStorageSync('checkba_last_project_id')")
    ? null
    : '删了这行会让切身份之后仍直达上一个身份的项目'
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav
```

Expected: 退出码 1，输出这四条（后四条断言现在是绿的）：
```
导航契约检查未通过：
  - launch 无最近项目兜底落项目列表页 — 还指着个人中心
  - login 四处落点全改项目列表页 — 还有指着个人中心的落点
  - newproject 返回项目列表页且仍用 navigateTo — 还指着个人中心
  - 工作台「全部项目」用 reLaunch 去项目列表页 — goAllProjects 没有指向项目列表页
```

- [ ] **Step 3a: 改 launch 与 login 五处 URL 与一处注释**

① `frontend/src/pages/launch/launch.vue` 第 96-100 行：

```
         if (lastId && list.some((p) => Number(p.id) === lastId)) {
           uni.reLaunch({ url: `/pages/project-overview/project-overview?id=${lastId}` })
         } else {
-          uni.reLaunch({ url: '/pages/userprofile/userprofile' })
+          uni.reLaunch({ url: '/pages/project-list/project-list' })
         }
```

② `frontend/src/pages/login/login.vue` 第 281-282 行注释 + 第 289-292 行（CLIENT 落点重判）：

```
     // IDE 化启动直达：会话有效 → 直进上次项目（像 VS Code 打开即回到上个工作区）；
-    // 会话失效/网络异常 → 静默停留登录页。CLIENT 账号视图受限，只回个人中心。
+    // 会话失效/网络异常 → 静默停留登录页。CLIENT 账号视图受限，落项目列表页——
+    // getUserProjects 把成员身份的项目也算进去，客户在那里看得见律师分享给他的案卷；
+    // 项目 tab 搬出个人中心之后，落个人中心会得到一张空白页。
     async tryAutoResume() {
```

```
         if (user.role === 'CLIENT') {
-          uni.reLaunch({ url: '/pages/userprofile/userprofile' })
+          uni.reLaunch({ url: '/pages/project-list/project-list' })
           return
         }
```

③ 同文件第 296-300 行（会话恢复但无最近项目）：

```
         if (lastId && list.some((p) => Number(p.id) === lastId)) {
           uni.reLaunch({ url: `/pages/project-overview/project-overview?id=${lastId}` })
         } else {
-          uni.reLaunch({ url: '/pages/userprofile/userprofile' })
+          uni.reLaunch({ url: '/pages/project-list/project-list' })
         }
```

④ 同文件第 391-393 行（finishLogin）：

```
       setTimeout(() => {
-        uni.reLaunch({ url: '/pages/userprofile/userprofile' });
+        uni.reLaunch({ url: '/pages/project-list/project-list' });
       }, 300);
```

⑤ 同文件第 471-473 行（注册成功；新账号零项目，落在有「+ 新建项目」空态的页上正好）：

```
           setTimeout(() => {
-            uni.reLaunch({ url: '/pages/userprofile/userprofile' });
+            uni.reLaunch({ url: '/pages/project-list/project-list' });
           }, 500);
```

- [ ] **Step 3b: 改 newproject（定点替换，不做全文件 sed）**

先把全部命中列出来，逐个判断语义再改（**不要 `sed -i '' 's/goToUserProfile/goToProjectList/g'` 这种机械重命名**，它会连带改掉该文件里其他可能合法的用途）：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && grep -n "goToUserProfile" src/pages/newproject/index.vue
```

Expected: 恰好 3 行 —— `33`、`96`、`175`。逐个判语义：

| 行 | 上下文 | 语义 | 处置 |
|---|---|---|---|
| `:33` | `<view class="action-item" @tap="goToUserProfile">` | 顶部「返回」动作项 | 改（它要回的是「我的项目」，不是个人资料） |
| `:96` | `<button class="btn btn-cancel" @tap="goToUserProfile">取消</button>` | 表单取消 | 改（同上） |
| `:175` | 方法定义 | 唯一定义 | 改 |

三处都是「回到我的项目」，无一处真去个人资料，因此三处全改。逐处编辑：

`:33`：
```
-            <view class="action-item" @tap="goToUserProfile">
+            <view class="action-item" @tap="goToProjectList">
```

`:96`：
```
-               <button class="btn btn-cancel" @tap="goToUserProfile">取消</button>
+               <button class="btn btn-cancel" @tap="goToProjectList">取消</button>
```

`:175-177`：
```
-    goToUserProfile() {
-      uni.navigateTo({ url: '/pages/userprofile/userprofile' })
-    },
+    // 两端都不是工作台，用 navigateTo（工作台参与的跳转才 reLaunch）
+    goToProjectList() {
+      uni.navigateTo({ url: '/pages/project-list/project-list' })
+    },
```

改完复核：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && grep -n "goToProjectList\|goToUserProfile" src/pages/newproject/index.vue
```

Expected: 恰好 3 行，全是 `goToProjectList`（`:33`、`:96`、方法定义），`goToUserProfile` 零命中。

- [ ] **Step 3c: 改工作台的 goAllProjects**

`frontend/src/pages/project-overview/project-overview.vue` 第 2689-2692 行：

```
     goAllProjects() {
       this.projectSwitcherOpen = false
-      uni.navigateTo({ url: '/pages/userprofile/userprofile' })
+      // 工作台参与的跳转一律 reLaunch：navigateTo 会把工作台留在页面栈里，
+      // 从列表页再进另一个项目就出现两个存活的工作台实例（全局监听多实例地雷）
+      uni.reLaunch({ url: '/pages/project-list/project-list' })
     },
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav && npm run build:h5
```

Expected: PASS —— `导航契约检查通过`，`build:h5` 编译成功。

再手工确认全仓只剩四处指向个人中心：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && grep -rn "pages/userprofile/userprofile" src
```

Expected: 恰好 4 行 —— `src/pages.json:53`（路由注册）、`src/pages/admin/admin.vue:1819`、`src/pages/admin/admin.vue:1823`、`src/pages/project-overview/project-overview.vue` 的 `goToUserProfile` 那一行。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/pages/launch/launch.vue frontend/src/pages/login/login.vue frontend/src/pages/newproject/index.vue frontend/src/pages/project-overview/project-overview.vue frontend/scripts/check-navigation-contract.mjs && \
git commit -m "feat(frontend): 六条我的项目入口改指项目列表页，CLIENT 落点一并重判"
```

---

### Task 21: 工作台顶栏切换器新增「项目概览」入口

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/project-overview/project-overview.vue:37-40`（模板，在 `.switcher-all` 之前插入一项）、`:2688` 附近（`goAllProjects` 之前新增 `goProjectHome`）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/project-overview/project-overview.scss:284`（在 `.switcher-all` 规则之前插入 `.switcher-home`）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/scripts/check-navigation-contract.mjs`
- Test: `frontend/scripts/check-navigation-contract.mjs`

**Interfaces:**
- Consumes: `project-overview.vue` 的 `this.projectId`、`this.projectSwitcherOpen`；`project-overview.scss:2` 的 `$color-primary`。
- Produces: 方法 `goProjectHome()` → `uni.reLaunch({ url: '/pages/project-home/project-home?id=' + this.projectId })`。模板项 `.switcher-home`「项目概览」，位置在 `.switcher-all`「全部项目…」之前。**这是工作台通往概览页的唯一入口。**

> 目标页 `pages/project-home/project-home` 由项目概览页组（Task 24 起）创建、由 Task 23 注册进 `pages.json`。本任务只落入口，此刻点它会因为路由未注册而失败，属预期的中间态。

- [ ] **Step 1: 写失败的测试**

在 `check-navigation-contract.mjs` 的 `// ---- 追加位` 之前插入：

```js
// ==================== 工作台通往概览页的入口 ====================

check('工作台切换器有通往项目概览页的入口', () => {
  const src = readFrontend('src/pages/project-overview/project-overview.vue')
  if (!src.includes('switcher-home')) return '模板里缺 .switcher-home 一项'
  const i = src.indexOf('goProjectHome()', src.indexOf('methods:'))
  if (i < 0) return 'goProjectHome 不在 methods 里'
  const body = src.slice(i, i + 320)
  if (!body.includes('/pages/project-home/project-home?id=')) return 'goProjectHome 没有指向项目概览页'
  if (!body.includes('reLaunch')) return '工作台参与的跳转一律 reLaunch'
  // 「项目概览」必须排在「全部项目…」之前（两者的首次出现都在模板里）
  if (src.indexOf('switcher-home') > src.indexOf('switcher-all')) {
    return '「项目概览」应当排在「全部项目…」之前'
  }
  return null
})

check('switcher-home 有对应样式', () => {
  const css = readFrontend('src/pages/project-overview/project-overview.scss')
  return css.includes('.switcher-home') ? null : 'project-overview.scss 里没有 .switcher-home'
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav
```

Expected: 退出码 1，输出：
```
导航契约检查未通过：
  - 工作台切换器有通往项目概览页的入口 — 模板里缺 .switcher-home 一项
  - switcher-home 有对应样式 — project-overview.scss 里没有 .switcher-home
```

- [ ] **Step 3: 最小实现**

① `project-overview.vue` 模板：在第 38 行 `<view class="switcher-item switcher-all" @tap="goAllProjects">` **之前**插入一项。改完第 35-41 行长这样：

```html
              <view v-if="!switcherProjects.length" class="switcher-item switcher-empty">
                <text>没有其他最近项目</text>
              </view>
              <view class="switcher-item switcher-home" @tap="goProjectHome">
                <text>项目概览</text>
              </view>
              <view class="switcher-item switcher-all" @tap="goAllProjects">
                <text>全部项目…</text>
              </view>
            </view>
```

② `project-overview.vue` methods：在 `goAllProjects()` **之前**插入（即 `switchToProject` 收尾的 `},` 之后）：

```js
    // 工作台通往项目概览页的唯一入口。工作台参与的跳转一律 reLaunch。
    goProjectHome() {
      this.projectSwitcherOpen = false
      if (!this.projectId) return
      uni.reLaunch({ url: `/pages/project-home/project-home?id=${this.projectId}` })
    },
```

③ `project-overview.scss`：在第 284 行 `.switcher-all {` **之前**插入：

```scss
.switcher-home {
  border-top: 1px solid #f1f3f5;
  margin-top: 4px;
  padding-top: 10px;

  text {
    color: $color-primary;
    font-size: 13px;
  }
}

/* 「项目概览」在上、「全部项目…」在下时只保留一条分隔线 */
.switcher-home + .switcher-all {
  border-top: none;
  margin-top: 0;
  padding-top: 8px;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav && npm run build:h5
```

Expected: PASS —— `导航契约检查通过`，`build:h5` 编译成功。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/pages/project-overview/project-overview.vue frontend/src/pages/project-overview/project-overview.scss frontend/scripts/check-navigation-contract.mjs && \
git commit -m "feat(frontend): 工作台顶栏切换器新增项目概览入口"
```

---

### Task 22: 工作台消费概览页带来的 conversationId，把那条历史对话真打开

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages/project-overview/project-overview.vue:2185`（`onLoad` 里 `openFileId` 那段之后插入）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/scripts/check-navigation-contract.mjs`
- Test: `frontend/scripts/check-navigation-contract.mjs`

**Interfaces:**
- Consumes: `project-overview.vue` 既有的 `toggleAiPanel()`（`:3809-3819`，翻 `showAiPanel` 并在 `$nextTick` 里 `refreshAiContextPreview()` + `fetchChatHistory()`）与 `loadHistoryChat(chat)`（`:4729-4766`，参数是一个带 `conversationId` 字段的对象，内部调 `getAiHistory` 再经 `this.$refs.chatInterface.loadMessages` 灌进面板）。
- Produces: 工作台 `onLoad` 新增一条分支：`query.conversationId` 存在时打开右侧 AI 面板并把那条会话装进去。概览页 `ConversationList` 点击 → 概览页 `onOpenConversation` → `reLaunch(工作台?id=..&conversationId=..)` 这条链因此闭环。

> 为什么必须做：概览页只把参数带过去、工作台不消费的话，用户点一条历史对话进工作台后**停在当前会话，历史对话没打开**，是明显的功能落空，而 e2e 与静态测试都发现不了。
> 为什么用 `setTimeout` 而不是直接调：`showAiPanel` 默认 `false`（`:1583`），`<ChatInterface ref="chatInterface">` 挂在 `v-if="showAiPanel"` 里（`:1022`、`:1035-1036`），`loadHistoryChat` 依赖这个 `$refs`。同一个 `onLoad` 里 `openFileId` 用的就是 `setTimeout(..., 600)` 等挂载（`:2182-2185`），照抄同一手法，不另造一套时序。

- [ ] **Step 1: 写失败的测试**

在 `check-navigation-contract.mjs` 的 `// ---- 追加位` 之前插入：

```js
check('工作台消费概览页带来的 conversationId', () => {
  const src = readFrontend('src/pages/project-overview/project-overview.vue')
  const i = src.indexOf('onLoad(query)')
  if (i < 0) return '找不到 onLoad(query)'
  const body = src.slice(i, i + 3000)
  if (!body.includes('query.conversationId')) {
    return 'onLoad 没有读 conversationId——概览页点历史对话进来会停在当前会话'
  }
  if (!body.includes('loadHistoryChat(')) return '读了 conversationId 却没有打开那条会话'
  if (!body.includes('showAiPanel')) return '右侧 AI 面板默认收起，不打开它 $refs.chatInterface 不存在'
  return null
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav
```

Expected: 退出码 1，输出：
```
导航契约检查未通过：
  - 工作台消费概览页带来的 conversationId — onLoad 没有读 conversationId——概览页点历史对话进来会停在当前会话
```

- [ ] **Step 3: 最小实现**

`frontend/src/pages/project-overview/project-overview.vue`：在 `onLoad` 里 `openFileId` 那段（`:2181-2185`）之后、`ensureStagingFolder` 那段之前插入：

```js
      // 概览页的 AI 对话列表点进来时带着 conversationId：把那条历史会话真打开。
      // 右侧 AI 面板默认收起（showAiPanel: false）且 ChatInterface 挂在 v-if 里，
      // 所以先开面板、再等它挂载完调 loadHistoryChat（它要 $refs.chatInterface）——
      // 与上面 openFileId 同一手法，不另造一套时序。
      if (query.conversationId) {
        const pendingConversationId = String(query.conversationId)
        if (!this.showAiPanel) this.toggleAiPanel()
        setTimeout(() => this.loadHistoryChat({ conversationId: pendingConversationId }), 600)
      }
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav && npm run build:h5
```

Expected: PASS —— `导航契约检查通过`，`build:h5` 编译成功。

真机验证放在项目概览页组落地之后（本任务此刻还没有会话列表可点）：概览页组 Task 24-31 完成后，在 dev Electron 里从概览页 AI 对话块点一条历史会话 → 落工作台 → 右侧面板自动展开且显示的是那条会话的历史消息（不是空的新会话）。这一条已由 e2e + 文档组的全量走查覆盖。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/pages/project-overview/project-overview.vue frontend/scripts/check-navigation-contract.mjs && \
git commit -m "feat(frontend): 工作台消费 conversationId，从概览页点历史对话直接打开那条会话"
```

---

### Task 23: 注册概览页路由，护栏加全量层并挂上 CI，复核邀请话术

**Files:**
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/pages.json`（在 `project-list` 那一项之后插入 `project-home` 一项）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/src/App.vue:14`（路由埋点注释里的页面数）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/package.json`（加 `check:nav:full`）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend/scripts/check-navigation-contract.mjs`（加两层机制 + 11 条全量层断言）
- Modify: `/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/.github/workflows/ci.yml:38`（`check:emits` 那一步之后加一步）
- Test: `frontend/scripts/check-navigation-contract.mjs`

**Interfaces:**
- Consumes: 无新增。
- Produces: `pages.json` 里 `pages/project-home/project-home` 的注册（项目概览页组 Task 24 起的「pages.json 幂等判定」步骤会看到已注册、跳过）；`npm run check:nav:full`（`CHECK_NAV_FULL=1`）；CI 的 frontend job 新增一步跑全量层。

> **本任务之后到 Task 24 之前，`npm run build:h5` 会报 `Failed to resolve import .../pages/project-home/project-home.vue`**——路由注册了而页面文件还没落地。这是本组与概览页组的交接缝，**Step 4 因此不跑 build:h5**；第一次 build:h5 由 Task 24（概览页组建 `project-home.vue`）跑。这也是本组把这条注册留到最后一个任务的原因。
> **全量层校验的是别的组的产出**（概览页容器与五个子组件、领域文档、app-e2e 旅程），落地之前必红，属预期。跨任务互校是有意为之：`CLAUDE.md` / `.claude/agents/sidebar-shell.md` / `docs/QA_JOURNEYS.md` / `run.mjs` 的**编辑权全部在 e2e + 文档组**，本组一个字都不改，只校验。

- [ ] **Step 1: 写失败的测试**

① 在 `check-navigation-contract.mjs` 顶部，把常量与助手补齐——`const hasFile = ...` 那一行之后加三行：

```js
const REPO = resolve(FRONTEND, '..')
const readRepo = (rel) => readFileSync(resolve(REPO, rel), 'utf8')
const readFrontendOrNull = (rel) =>
  existsSync(resolve(FRONTEND, rel)) ? readFileSync(resolve(FRONTEND, rel), 'utf8') : null
```

`const WORKBENCH_ROUTE = ...` 那一行之后加一行：

```js
const HOME_ROUTE = 'pages/project-home/project-home'
```

`const check = (name, fn) => {...}` 那一段之后加两段（全量层机制）：

```js
// 全量层：只在 CHECK_NAV_FULL=1 时执行（npm run check:nav:full，CI 用它）。
// 它校验的是同一个 PR 里别的批次的产出——概览页容器与它的五个子组件、领域文档、
// app-e2e 旅程。那些还没落地时全量层必红，属预期，所以日常 npm run check:nav 不跑它。
const FULL = process.env.CHECK_NAV_FULL === '1'
let skipped = 0
const checkFull = (name, fn) => {
  if (!FULL) { skipped++; return }
  check(name, fn)
}

const NOT_YET = '文件尚未落地（由项目概览页组 / e2e + 文档组产出）'
```

② 在 `// ---- 追加位` 之前插入基础层的两条与全量层的十一条：

```js
// ==================== 概览页路由与埋点注释 ====================

check('pages.json 注册 ' + HOME_ROUTE, () => {
  const p = pageByPath.get(HOME_ROUTE)
  if (!p) return '未注册'
  if (!p.style || p.style.navigationStyle !== 'custom') {
    return 'style.navigationStyle 必须显式写 custom（globalStyle 里没有这一项，漏写会得到系统导航栏）'
  }
  return null
})

check('App.vue 的路由埋点注释与 pages.json 对得上', () => {
  const src = readFrontend('src/App.vue')
  const n = pages.length
  return src.includes(`pages.json 里的 ${n} 个页面`)
    ? null
    : `注释里的页面数与 pages.json 实际的 ${n} 个对不上`
})

check('CI 跑导航护栏', () => {
  const yml = readRepo('.github/workflows/ci.yml')
  return yml.includes('npm run check:nav:full') ? null : 'ci.yml 里没有 check:nav:full 这一步'
})

check('邀请话术仍指向真看得见的入口', () => {
  const src = readFrontend('src/components/collab/CollabDialog.vue')
  if (!src.includes('打开项目列表')) return '话术被改坏了'
  const list = readFrontend('src/pages/project-list/project-list.vue')
  return list.includes('从团队案件库取一份案卷') ? null : '话术指的入口在项目列表页上不存在'
})

// ==================== 全量层：别的批次的产出 ====================

const HOME_VUE = 'src/pages/project-home/project-home.vue'

checkFull('概览页容器带全三个 e2e 锚点类名', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  const miss = ['page-project-home', 'btn-project-list', 'btn-workbench'].filter((c) => !src.includes(c))
  return miss.length ? '缺 e2e 锚点: ' + miss.join(', ') : null
})

checkFull('概览页挂了五个内容区块', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  const miss = ['<ProfileHeader', '<OverviewStatsBar', '<ActivityFeed', '<TaskSchedule', '<ConversationList']
    .filter((t) => !src.includes(t))
  return miss.length ? '缺子组件: ' + miss.join(', ') : null
})

checkFull('概览页登记最近项目', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  if (!/from\s+'@\/utils\/recentProjects\.js'/.test(src)) return "没有从 '@/utils/recentProjects.js' 引入"
  if (!src.includes('recordProjectVisit(')) return '没有调 recordProjectVisit'
  return null
})

checkFull('概览页用自己的活跃实例指针', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  // 禁字断言只看实际代码：概览页的注释里要解释「为什么不复用 __checkbaActiveOverviewVm」，
  // 那段说明性文字不该把断言判红。「必须出现」那条仍然看整份源码。
  const code = stripVueComments(src)
  if (!src.includes('__checkbaProjectHomeVm')) return '缺活跃实例指针守卫'
  if (code.includes('__checkbaActiveOverviewVm')) {
    return '复用了工作台的指针，会让工作台的全局事件被概览页拦掉'
  }
  return null
})

checkFull('概览页 → 工作台用 reLaunch 并透传 openFileId', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  const i = src.indexOf('goWorkbench()')
  if (i < 0) return '缺 goWorkbench()'
  const body = src.slice(i, i + 500)
  if (!body.includes('reLaunch')) return '进入工作台必须用 reLaunch（工作台参与的跳转一律 reLaunch）'
  if (!body.includes('/pages/project-overview/project-overview')) return '目标不是工作台'
  if (!body.includes('openFileId')) return '没有透传 openFileId'
  return null
})

checkFull('概览页 → 项目列表页按页面栈分流', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  const i = src.indexOf('goProjectList()')
  if (i < 0) return '缺 goProjectList()'
  const body = src.slice(i, i + 600)
  if (!body.includes('getCurrentPages')) return '没有判页面栈，无脑 navigateTo/redirectTo 会堆出多个列表页实例'
  if (!body.includes('navigateBack') || !body.includes('redirectTo')) {
    return '必须两条分支：栈里上一页是列表页就 navigateBack，否则 redirectTo'
  }
  return null
})

checkFull('概览页轮询纪律', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  // 禁字断言只看实际代码：概览页的注释里要写明「绝不调 /version/status」的理由，
  // 那段说明性文字不该把断言判红。
  const code = stripVueComments(src)
  if (code.includes('getVersionStatus') || code.includes('/version/status')) {
    return '不许调 /version/status：它在 enabled 时会跑两次 git add，并与工作台争 per-project 锁'
  }
  if (code.includes('setInterval')) return 'A 期只在 onLoad 与 onShow 各刷一次，不起轮询'
  return null
})

checkFull('五个子组件的根节点类名是 e2e 锚点', () => {
  const map = {
    'src/components/project-home/ProfileHeader.vue': 'profile-header',
    'src/components/project-home/OverviewStatsBar.vue': 'overview-stats-bar',
    'src/components/project-home/ActivityFeed.vue': 'activity-feed',
    'src/components/project-home/TaskSchedule.vue': 'task-schedule',
    'src/components/project-home/ConversationList.vue': 'conversation-list',
  }
  const bad = []
  for (const [file, cls] of Object.entries(map)) {
    const src = readFrontendOrNull(file)
    if (src === null) bad.push(file + '(未落地)')
    else if (!src.includes(`class="${cls}`)) bad.push(file + ' 缺 .' + cls)
  }
  return bad.length ? bad.join(', ') : null
})

checkFull('CLAUDE.md 写下了三个同名不同物的术语', () => {
  const md = readRepo('CLAUDE.md')
  const miss = [HOME_ROUTE, LIST_ROUTE, '工作台'].filter((s) => !md.includes(s))
  return miss.length ? '缺: ' + miss.join(', ') : null
})

checkFull('sidebar-shell.md 的页面路由一节收录了两个新页', () => {
  const md = readRepo('.claude/agents/sidebar-shell.md')
  const miss = ['project-list', 'project-home'].filter((s) => !md.includes(s))
  return miss.length ? '缺: ' + miss.join(', ') : null
})

checkFull('app-e2e 走三级跳而不是把个人中心当必经之路', () => {
  const src = readFrontend('tests/app-e2e/run.mjs')
  if (!src.includes(LIST_ROUTE)) return 'J3 没有从项目列表页出发'
  if (!src.includes(HOME_ROUTE)) return 'J3 没有经过项目概览页'
  if (src.includes("mouseClickText('我的项目')")) return '个人中心已经没有「我的项目」tab 了'
  const i = src.indexOf('解锁成功')
  if (i < 0 || !src.slice(i, i + 500).includes(LIST_ROUTE)) {
    return '解锁后的落点断言还没放行项目列表页'
  }
  return null
})
```

③ 把脚本末尾的通过输出改成带跳过计数的版本：

```
-console.log('导航契约检查通过')
+console.log(
+  '导航契约检查通过' +
+    (FULL ? '（含全量层）' : `（跳过 ${skipped} 条全量层断言，用 npm run check:nav:full 跑全量）`)
+)
```

④ `frontend/package.json` 的 scripts 里，紧跟 `"check:nav"` 那一行之后加一行：

```json
    "check:nav:full": "CHECK_NAV_FULL=1 node scripts/check-navigation-contract.mjs",
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav
```

Expected: 退出码 1，基础层红三条（`App.vue` 注释那条也红：pages.json 此刻是 12 项，注释写的是 11）：
```
导航契约检查未通过：
  - pages.json 注册 pages/project-home/project-home — 未注册
  - App.vue 的路由埋点注释与 pages.json 对得上 — 注释里的页面数与 pages.json 实际的 12 个对不上
  - CI 跑导航护栏 — ci.yml 里没有 check:nav:full 这一步
```
（「邀请话术仍指向真看得见的入口」这条是绿的：`CollabDialog.vue:271` 的话术未动，Task 18 的列表页上有「从团队案件库取一份案卷」。）

- [ ] **Step 3a: 注册概览页路由并对齐埋点注释**

`frontend/src/pages.json`：在 Task 16 加的 `pages/project-list/project-list` 那一项之后插入一项：

```json
		{
			"path": "pages/project-list/project-list",
			"style": {
				"navigationBarTitleText": "我的项目",
				"navigationStyle": "custom"
			}
		},
		{
			"path": "pages/project-home/project-home",
			"style": {
				"navigationBarTitleText": "项目概览页",
				"navigationStyle": "custom"
			}
		},
```

`frontend/src/App.vue` 第 13-14 行（spec §5.1 点名的「顺手改的注释」）：

```
     // 埋点：页面路由唯一收口（全仓 50 处 navigateTo/reLaunch 直调，拦截器一处全覆盖）；
-    // 只记页面路径枚举（pages.json 里的 11 个页面），query 参数不采集
+    // 只记页面路径枚举（pages.json 里的 13 个页面，2026-08-08 三级导航加了
+    // project-list 与 project-home 两页），query 参数不采集
```

- [ ] **Step 3b: CI 挂上导航护栏**

`.github/workflows/ci.yml`：在第 36-38 行那一步（`Check emit/binding contracts`）之后插入：

```yaml
      - name: Check navigation contracts
        working-directory: frontend
        run: npm run check:nav:full
```

- [ ] **Step 4: 跑测试确认通过**

Run（**不跑 build:h5**，理由见任务开头）：
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav && npm run check:emits
```

Expected: PASS —— 打印 `导航契约检查通过（跳过 11 条全量层断言，用 npm run check:nav:full 跑全量）`，以及 `check:emits` 的通过输出。

再跑一次全量层，把「等谁落地」这件事记成账（这一步允许红，只核对红的是不是恰好这 11 条）：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav:full; echo "exit=$?"
```

Expected: `exit=1`，红的恰好 11 条，逐条对应下表；**没有第 12 条**（出现别的就是基础层被改坏了）：

| 红的断言 | 由谁转绿 |
|---|---|
| 概览页容器带全三个 e2e 锚点类名 | 项目概览页组（Task 24 起） |
| 概览页挂了五个内容区块 | 同上 |
| 概览页登记最近项目 | 同上 |
| 概览页用自己的活跃实例指针 | 同上 |
| 概览页 → 工作台用 reLaunch 并透传 openFileId | 同上 |
| 概览页 → 项目列表页按页面栈分流 | 同上 |
| 概览页轮询纪律 | 同上 |
| 五个子组件的根节点类名是 e2e 锚点 | 同上 |
| CLAUDE.md 写下了三个同名不同物的术语 | e2e + 文档组（Task 32 起） |
| sidebar-shell.md 的页面路由一节收录了两个新页 | 同上 |
| app-e2e 走三级跳而不是把个人中心当必经之路 | 同上 |

CI 的 `check:nav:full` 与 `build:h5` 两步在这些任务落地之前都是红的，**本 PR 在 Task 38 全量验证通过之前不要推分支**。

- [ ] **Step 5: 邀请话术复核（不改文案，只确认路径成立）**

`CollabDialog.vue:271` 写死「1. 打开项目列表，点『从团队案件库取一份案卷』」，收件人是手上还没有案卷的人。搬完之后这句话仍必须指向他真看得见的位置。等项目概览页组落地后（或直接在本任务用 Task 18 的列表页验），在 dev 里点一遍：

1. 工作台顶栏协作 chip → CollabDialog → 复制邀请话术；
2. 按话术走：打开项目列表页 → 找「从团队案件库取一份案卷」→ 弹窗里的「去连一个」；
3. **两种状态都要看**：项目列表页在**有项目**（顶部 `header-actions` 里那个按钮）与**零项目**（空态下的 `.cloud-accept-entry`）时都能看到这个入口。

Expected: 两种状态都能看到该入口，话术每一步都落得下去，文案不改。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/pages.json frontend/src/App.vue frontend/package.json frontend/scripts/check-navigation-contract.mjs .github/workflows/ci.yml && \
git commit -m "feat(frontend): 注册项目概览页路由，导航护栏加全量层并进 CI"
```

### Task 24: 纯展示逻辑落地：matterTypes.js + projectHomeFormat.js（含 node --test 基建）

**Files:**
- Create: `frontend/src/config/matterTypes.js`
- Create: `frontend/src/utils/projectHomeFormat.js`
- Create: `frontend/tests/project-home/format.test.mjs`
- Modify: `frontend/package.json:42`（`"test:feedback-e2e"` 那一行，给它补逗号并在下一行加新脚本）
- Test: `frontend/tests/project-home/format.test.mjs`

**Interfaces:**
- Consumes: `backend/src/main/java/com/checkba/service/telemetry/MatterClassifierService.java:31`（`PROMPT_PREFIX` 里「可选类别：」那一行，只读来对拍，不改）；`backend/src/main/java/com/checkba/service/ai/AgentRunStateService.java:30-57`（`RunStatus` 枚举，8 个值）
- Produces: `MATTER_TYPES`（11 条中文串）、`formatDateTime`、`versionTitle`、`fileCountLabel`、`runStatusLabel`、`runStatusDotClass`、`isProfileEmpty`、`profileFieldHint`、`hasConversationPreview`、`canEditProfile`。后面 Task 26-31 全部依赖本模块。

背景：本仓前端没有单测运行器（`frontend/package.json` 只有 `check:emits` 静态检查 + 四套 puppeteer e2e），Node 22 内置 `node --test` 零依赖可用。所以把概览页所有会出错的口径（localRoot 措辞、时间线 6 种文案形状、`source=default` 弱化、空预览兜底、编辑权限）抽成纯函数，用真断言守住。

**本机实测过的两个坑，照抄不要自己改写：**
1. `node --test <目录>` 在 Node v22.22.3 上**会把目录当模块加载并报 `Cannot find module`**（实测 `# pass 0 / # fail 1`）。必须写成带引号的 glob `node --test "tests/project-home/*.test.mjs"`（实测通过，失败时退出码 1）。
2. `RunStatus` 有 **8** 个值，比工作台的会话状态映射少写一个 `AWAITING_INPUT` 就会在概览页丢一种状态。取值与文案以 `project-overview.vue:4769-4790` 的 `convDotClass` / `convStatusLabel` 为准（那是仓里已经在跑的口径）。

- [ ] **Step 1: 加脚本入口 + 写失败的测试**

先改 `frontend/package.json`，把 `:42` 这一行：

```json
    "test:feedback-e2e": "node tests/feedback-e2e/run.mjs"
```

换成两行（注意上一行结尾补逗号）：

```json
    "test:feedback-e2e": "node tests/feedback-e2e/run.mjs",
    "test:project-home": "node --test \"tests/project-home/*.test.mjs\""
```

然后新建 `frontend/tests/project-home/format.test.mjs`：

```js
// 项目概览页纯展示逻辑的单测。零依赖：只用 node 内置 test runner，
// node_modules 未安装也能跑（本仓前端没有 vitest/jest）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { MATTER_TYPES } from '../../src/config/matterTypes.js'
import {
  formatDateTime, versionTitle, fileCountLabel, runStatusLabel, runStatusDotClass,
  isProfileEmpty, profileFieldHint, hasConversationPreview, canEditProfile,
} from '../../src/utils/projectHomeFormat.js'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')

test('MATTER_TYPES 与后端分类表逐字一致', () => {
  assert.equal(MATTER_TYPES.length, 11)
  assert.equal(MATTER_TYPES[0], '公司治理')
  const java = readFileSync(
    resolve(REPO, 'backend/src/main/java/com/checkba/service/telemetry/MatterClassifierService.java'),
    'utf8')
  const line = java.split('\n').find((l) => l.includes('可选类别：'))
  assert.ok(line, '后端 prompt 里找不到「可选类别：」')
  const backend = line.split('可选类别：')[1].replace(/。\s*$/, '').split('、')
  assert.deepEqual(backend, MATTER_TYPES)
})

test('formatDateTime 吃 LocalDateTime 串，坏值返回空串', () => {
  assert.equal(formatDateTime('2026-08-08T10:11:12'), '8 月 8 日 10:11')
  assert.equal(formatDateTime('2026-08-08T09:05:00'), '8 月 8 日 09:05')
  assert.equal(formatDateTime(''), '')
  assert.equal(formatDateTime(null), '')
  assert.equal(formatDateTime('not-a-date'), '')
  // VersionEntry.when 是 Instant，Spring Boot 默认序列化成带 Z 的 ISO 串。
  // 不断言具体值（会跟着 CI 机器时区飘），只断言能解析出东西。
  assert.notEqual(formatDateTime('2026-08-08T02:11:12Z'), '')
})

test('versionTitle 容纳 6 种文案形状且不动空白', () => {
  // 未命名工作的默认名带空格（WorkSessionService 的 TITLE_FMT "M 月 d 日"），压掉就成错别字
  assert.equal(versionTitle({ message: '8 月 8 日下午的工作' }), '8 月 8 日下午的工作')
  assert.equal(versionTitle({ message: '采纳：老王的稿' }), '采纳：老王的稿')
  assert.equal(versionTitle({ message: '退回到早先的版本' }), '退回到早先的版本')
  assert.equal(versionTitle({ message: '初始版本' }), '初始版本')
  assert.equal(versionTitle({ message: '取回最新稿' }), '取回最新稿')
  // note 优先于 message（与 VersionTimeline.vue:111-113 的 titleOf 同口径）
  assert.equal(versionTitle({ message: '8 月 8 日下午的工作', note: '尽调清单第一轮' }), '尽调清单第一轮')
  assert.equal(versionTitle(null), '')
})

test('fileCountLabel 对 localRoot 项目改口径', () => {
  assert.equal(fileCountLabel({ fileCount: 12, isLocalRoot: false }), '12 个文件')
  assert.equal(fileCountLabel({ fileCount: 12, isLocalRoot: true }), '已登记 12 项')
  assert.equal(fileCountLabel({}), '0 个文件')
  assert.equal(fileCountLabel(null), '0 个文件')
})

test('runStatusLabel / runStatusDotClass 覆盖 RunStatus 全部 8 个枚举值', () => {
  assert.equal(runStatusLabel('RUNNING'), '运行中')
  assert.equal(runStatusLabel('PAUSED'), '待继续')
  assert.equal(runStatusLabel('INTERRUPTED'), '已中断')
  assert.equal(runStatusLabel('AWAITING_APPROVAL'), '待审批')
  // AWAITING_INPUT 必须与 AWAITING_APPROVAL 分开：这是后端新增它的全部目的
  assert.equal(runStatusLabel('AWAITING_INPUT'), '待回答')
  assert.equal(runStatusLabel('ERROR'), '出错')
  assert.equal(runStatusLabel('FINISHED'), '')
  assert.equal(runStatusLabel('CANCELLED'), '')
  assert.equal(runStatusLabel(null), '')
  assert.equal(runStatusDotClass('RUNNING'), 'dot-running')
  assert.equal(runStatusDotClass('AWAITING_APPROVAL'), 'dot-attention')
  assert.equal(runStatusDotClass('AWAITING_INPUT'), 'dot-attention')
  assert.equal(runStatusDotClass('ERROR'), 'dot-error')
  assert.equal(runStatusDotClass('FINISHED'), '')
})

test('isProfileEmpty：openedAt 的 default 值不算有人填过', () => {
  const blank = [
    { fieldKey: 'client', fieldValue: null, source: null },
    { fieldKey: 'openedAt', fieldValue: '2026-08-01', source: 'default' },
    { fieldKey: 'nextStep', fieldValue: null, source: null },
  ]
  assert.equal(isProfileEmpty(blank), true)
  assert.equal(isProfileEmpty([...blank, { fieldKey: 'client', fieldValue: '某公司', source: 'user' }]), false)
  assert.equal(isProfileEmpty([{ fieldKey: 'client', fieldValue: '某公司', source: 'ai' }]), false)
  assert.equal(isProfileEmpty([]), true)
  assert.equal(isProfileEmpty(null), true)
})

test('profileFieldHint 弱化 default 与 ai', () => {
  assert.equal(profileFieldHint({ source: 'default' }), '取自建档时间')
  assert.equal(profileFieldHint({ source: 'ai' }), 'AI 读文件得出，请核对')
  assert.equal(profileFieldHint({ source: 'user' }), '')
  assert.equal(profileFieldHint(null), '')
})

test('hasConversationPreview 对空预览兜底（extractPreview 会返回空串）', () => {
  assert.equal(hasConversationPreview({ lastMessage: '已核对通知与决议的届次' }), true)
  assert.equal(hasConversationPreview({ lastMessage: '' }), false)
  assert.equal(hasConversationPreview({ lastMessage: '   ' }), false)
  assert.equal(hasConversationPreview({}), false)
  assert.equal(hasConversationPreview(null), false)
})

test('canEditProfile 与后端 hasWritePermission 放行集合一致', () => {
  for (const r of ['OWNER', 'MANAGER', 'ADMIN', 'PARTICIPANT']) assert.equal(canEditProfile(r), true)
  for (const r of ['READ_ONLY', 'CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC', null, undefined, ''])
    assert.equal(canEditProfile(r), false)
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: 失败，退出码 1。输出里出现
`Error [ERR_MODULE_NOT_FOUND]: Cannot find module '/Users/zewei/.../frontend/src/config/matterTypes.js' imported from /Users/zewei/.../frontend/tests/project-home/format.test.mjs`，末尾 `# pass 0`、`# fail 1`（模块加载就炸，整个测试文件算一条失败）。

- [ ] **Step 3: 最小实现**

新建 `frontend/src/config/matterTypes.js`：

```js
/**
 * 法律事项分类——项目档案「事项类型」字段的候选值。
 *
 * 这 11 个值必须与后端 MatterClassifierService.PROMPT_PREFIX
 * （backend/src/main/java/com/checkba/service/telemetry/MatterClassifierService.java:31）
 * 的类别表逐字一致：Plan 2 的档案 AI 抽取会复用同一份 prompt，两边漂了就会出现
 * 「AI 填进来的值不在下拉里」。Plan 2 上线时以后端为准。
 */
export const MATTER_TYPES = [
  '公司治理',
  '资本市场证券',
  '并购交易',
  '争议解决',
  '合同审查起草',
  '合规监管',
  '知识产权',
  '劳动人事',
  '破产重整',
  '其他法律事务',
  '非法律事务',
]
```

新建 `frontend/src/utils/projectHomeFormat.js`：

```js
/**
 * 项目概览页（pages/project-home）的纯展示逻辑。
 *
 * 抽出来的理由：这几条口径全部是硬约束（localRoot 措辞、时间线 6 种文案形状、
 * source=default 弱化、空预览兜底），而 .vue 模板在本仓没有单测手段。
 * 本文件不 import 任何东西、不碰 uni.*，才能被 node --test 直接跑。
 *
 * 与工作台 project-overview.vue:4769-4790 的 convStatusLabel/convDotClass 形状相同
 * 但不共用：概览页是新页面，重构工作台不在本次范围内。两边的取值表必须一起改。
 */

function pad2(n) {
  return String(n).padStart(2, '0')
}

/** ISO 串（Instant 带 Z 或 LocalDateTime 不带 Z）→「8 月 8 日 10:11」；坏值返回空串。 */
export function formatDateTime(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日 ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/**
 * 时间线条目标题。note 优先于 message（同 VersionTimeline.vue:111-113 的 titleOf）。
 * 原样返回，不做任何空白归一——未命名工作的默认名是「8 月 8 日下午的工作」，
 * 空格来自 WorkSessionService 的 TITLE_FMT "M 月 d 日"，压掉就是错别字。
 */
export function versionTitle(entry) {
  if (!entry) return ''
  return entry.note || entry.message || ''
}

/**
 * 统计条的文件计数措辞。localRoot 项目说「已登记 N 项」而不是「共 N 个文件」：
 * 3000 条上限 + 20 层深度 + 隐藏项跳过，外置盘拔出时数字还会冻结在最后一次
 * 成功对账的快照上。
 */
export function fileCountLabel(stats) {
  const n = Number((stats && stats.fileCount) || 0)
  return stats && stats.isLocalRoot ? `已登记 ${n} 项` : `${n} 个文件`
}

/**
 * 后台 AI 任务状态文案。取值是 AgentRunStateService.RunStatus 的 8 个枚举值
 * （service/ai/AgentRunStateService.java:30-57）。
 * 「待回答」(AWAITING_INPUT) 必须与「待审批」(AWAITING_APPROVAL) 分开：
 * 前者是模型缺信息在问你，后者是有草案等你点头。
 */
export function runStatusLabel(status) {
  if (status === 'RUNNING') return '运行中'
  if (status === 'PAUSED') return '待继续'
  if (status === 'INTERRUPTED') return '已中断'
  if (status === 'AWAITING_APPROVAL') return '待审批'
  if (status === 'AWAITING_INPUT') return '待回答'
  if (status === 'ERROR') return '出错'
  return ''
}

/** 状态点样式类。跑完/取消不打点。 */
export function runStatusDotClass(status) {
  if (status === 'RUNNING') return 'dot-running'
  if (status === 'PAUSED' || status === 'AWAITING_APPROVAL'
    || status === 'AWAITING_INPUT' || status === 'INTERRUPTED') return 'dot-attention'
  if (status === 'ERROR') return 'dot-error'
  return ''
}

/**
 * 档案是否全空。source==='default' 的 openedAt 是服务端用建档时间派生的，
 * 不算有人填过——否则新建项目永远进不了引导态。
 */
export function isProfileEmpty(fields) {
  if (!Array.isArray(fields)) return true
  return !fields.some((f) => f && f.fieldValue && f.source !== 'default')
}

/** 字段值下方的弱化说明。律师不能把模型猜的立项日期当事实。 */
export function profileFieldHint(field) {
  if (!field) return ''
  if (field.source === 'default') return '取自建档时间'
  if (field.source === 'ai') return 'AI 读文件得出，请核对'
  return ''
}

/**
 * 会话预览是否有内容。extractPreview 对以 import/def/function/class/const/let/var/
 * public/private 开头的正文直接返回空串，此时不要留一个空行。
 */
export function hasConversationPreview(conversation) {
  return !!(conversation && typeof conversation.lastMessage === 'string' && conversation.lastMessage.trim())
}

/**
 * 当前用户在这个项目里能不能改档案。myRole 取自 ProjectCardDTO（model/dto/ProjectCardDTO.java:19）。
 *
 * 集合对应后端 hasWritePermission（ProjectMemberService.java:159-169，放行 owner + ADMIN + PARTICIPANT）。
 * MANAGER 是**前端侧的历史键**：全仓 grep 后端无一处产生这个值（ProjectService.java:175 给
 * owner 写死 "OWNER"，:179 从 project_member 行取），memberRoles.js:18-19 里它与 OWNER 同为
 * 「负责人」，project-overview.vue:1905 也按管理员待遇。这里放行它是与既有前端口径一致；
 * 万一将来真出现一个非 owner 的 MANAGER，写入会被后端拒，概览页 onProfileSave 的 catch 会 toast。
 */
export function canEditProfile(myRole) {
  return ['OWNER', 'MANAGER', 'ADMIN', 'PARTICIPANT'].indexOf(myRole) !== -1
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: `# tests 9`、`# pass 9`、`# fail 0`，退出码 0。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/config/matterTypes.js frontend/src/utils/projectHomeFormat.js frontend/tests/project-home/format.test.mjs frontend/package.json && \
git commit -m "feat(project-home): 事项类型常量与概览页展示逻辑纯函数"
```

---

### Task 25: services/api.js 新增五个概览页端点（具名导出 + 默认导出同步）

**Files:**
- Modify: `frontend/src/services/api.js:2192-2200`（默认导出对象结尾，最后一个键是 `searchProjectContent`，`:2200` 是对象的 `}`）
- Modify: `frontend/src/services/api.js:2202`（`// ==================== 版本记录 ====================` 之前插入新段）
- Create: `frontend/tests/project-home/api-contract.test.mjs`
- Test: `frontend/tests/project-home/api-contract.test.mjs`

**Interfaces:**
- Consumes: 既有 `request(options)`（`services/api.js:157`）。GET 的 query 必须走 `options.params`——`:193` 那一行才把 params 合并进 `uni.request` 的 `data`；拼进 url 也能跑，但与全仓写法不一致。
- Produces: `getProjectOverviewStats` / `getProjectProfile` / `saveProjectProfileField` / `getProjectConversations` / `getProjectTasks`。五个端点一律信封：`request()` 见到 `{code:0,...}` 时 `resolve(res.data)`（`:231`）里的 `res.data` 是**整个响应体**，所以调用方写 `const res = await getProjectProfile(id); res.data.fields`。
- 被 Task 31 的页面容器消费。

注意：`services/api.js` 同时有具名导出和一个 `export default {...}` 大对象（`:2069-2200`）。只加具名会让走 default 的调用方拿不到，两处都要加。`export function` 是函数声明、会被提升，所以对象里的键写在函数定义之前是合法的（既有 `getVersionStatus:2205` 就定义在对象之后）。

- [ ] **Step 1: 写失败的测试**

新建 `frontend/tests/project-home/api-contract.test.mjs`：

```js
// services/api.js 引用了 uni.* 与 @/ 别名，node 无法 import，只能做源码级契约断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/services/api.js'), 'utf8')

const NAMES = ['getProjectOverviewStats', 'getProjectProfile', 'saveProjectProfileField',
  'getProjectConversations', 'getProjectTasks']

test('五个函数都有具名导出', () => {
  for (const n of NAMES) assert.match(SRC, new RegExp('export function ' + n + '\\('), '缺具名导出: ' + n)
})

test('五个函数都进了默认导出对象', () => {
  const start = SRC.indexOf('export default {')
  assert.ok(start > 0, '找不到 export default 对象')
  const end = SRC.indexOf('\n}', start)
  const block = SRC.slice(start, end)
  for (const n of NAMES) assert.ok(block.includes(n), '默认导出对象里缺: ' + n)
})

test('URL 与后端契约逐字一致', () => {
  assert.ok(SRC.includes('`/api/projects/${projectId}/overview/stats`'))
  assert.ok(SRC.includes('`/api/projects/${projectId}/profile`'))
  assert.ok(SRC.includes('`/api/projects/${projectId}/profile/${encodeURIComponent(fieldKey)}`'))
  assert.ok(SRC.includes('`/api/projects/${projectId}/conversations`'))
  assert.ok(SRC.includes('`/api/projects/${projectId}/tasks`'))
})

test('saveProjectProfileField 是 PUT + JSON body {value}', () => {
  const i = SRC.indexOf('export function saveProjectProfileField(')
  const body = SRC.slice(i, i + 420)
  assert.match(body, /method:\s*'PUT'/)
  assert.match(body, /data:\s*\{\s*value\s*\}/)
  assert.match(body, /'Content-Type':\s*'application\/json'/)
})

test('getProjectConversations 走 params 而不是拼字符串', () => {
  const i = SRC.indexOf('export function getProjectConversations(')
  const body = SRC.slice(i, i + 420)
  assert.match(body, /params:\s*\{/)
  assert.match(body, /limit:\s*options\.limit\s*\|\|\s*20/)
  assert.match(body, /options\.before\s*\?\s*\{\s*before:\s*options\.before\s*\}/)
  assert.match(body, /options\.beforeId\s*\?\s*\{\s*beforeId:\s*options\.beforeId\s*\}/)
  assert.ok(!/conversations\?limit=/.test(body), 'query 不许拼进 url')
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: 退出码 1，`# fail 5`（本文件 5 条全红），首条报 `缺具名导出: getProjectOverviewStats`；Task 24 的 9 条仍 pass。

- [ ] **Step 3: 最小实现**

改动 A —— 把 `frontend/src/services/api.js:2192-2200` 这段：

```js
  // Tag Management
  getProjectTags,
  createTag,
  updateTag,
  deleteTag,
  addTagToFile,
  removeTagFromFile,
  searchProjectContent
}
```

换成：

```js
  // Tag Management
  getProjectTags,
  createTag,
  updateTag,
  deleteTag,
  addTagToFile,
  removeTagFromFile,
  searchProjectContent,
  // 项目概览页（pages/project-home）
  getProjectOverviewStats,
  getProjectProfile,
  saveProjectProfileField,
  getProjectConversations,
  getProjectTasks
}
```

改动 B —— 在 `frontend/src/services/api.js:2202` 的 `// ==================== 版本记录 ====================` **之前**插入下面这一整块：

```js
// ==================== 项目概览页（pages/project-home） ====================
// 五个端点一律返回信封：request() 见到 {code:0,...} 时 resolve 的是整个响应体，
// 所以调用方一律写 `const res = await getProjectProfile(id); res.data.fields`。
// 反例：getMyProjects 走的是裸数组（ProjectController 直接返 List），别照抄这里加 .data。

export function getProjectOverviewStats(projectId) {
  return request({
    url: `/api/projects/${projectId}/overview/stats`,
    method: 'GET'
  });
}

export function getProjectProfile(projectId) {
  return request({
    url: `/api/projects/${projectId}/profile`,
    method: 'GET'
  });
}

/** value 传空串 = 清空该字段（服务端删行；openedAt 因此回落建档时间默认值）。 */
export function saveProjectProfileField(projectId, fieldKey, value) {
  return request({
    url: `/api/projects/${projectId}/profile/${encodeURIComponent(fieldKey)}`,
    method: 'PUT',
    data: { value },
    header: { 'Content-Type': 'application/json' }
  });
}

/** options.before / options.beforeId 是上一页最后一条的 (updatedAt, conversationId) 复合游标，成对传。 */
export function getProjectConversations(projectId, options = {}) {
  return request({
    url: `/api/projects/${projectId}/conversations`,
    method: 'GET',
    params: {
      limit: options.limit || 20,
      ...(options.before ? { before: options.before } : {}),
      ...(options.beforeId ? { beforeId: options.beforeId } : {})
    }
  });
}

/** A 期恒返回 {code:0,data:{tasks:[]}}；B 期接任务系统时本函数一行不改。 */
export function getProjectTasks(projectId) {
  return request({
    url: `/api/projects/${projectId}/tasks`,
    method: 'GET'
  });
}

```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: `# fail 0`（累计 14 条通过）。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/services/api.js frontend/tests/project-home/api-contract.test.mjs && \
git commit -m "feat(project-home): api.js 新增概览页五个端点"
```

---

### Task 26: OverviewStatsBar.vue 统计条组件

**Files:**
- Create: `frontend/src/components/project-home/OverviewStatsBar.vue`
- Create: `frontend/tests/project-home/stats-bar.test.mjs`
- Test: `frontend/tests/project-home/stats-bar.test.mjs`

**Interfaces:**
- Consumes: props `stats`（`GET /api/projects/{id}/overview/stats` 的 `data` 原样）、`loading`；`fileCountLabel` / `runStatusLabel` from `@/utils/projectHomeFormat.js`（Task 24）
- Produces: 无 emits。根节点类名 **`.overview-stats-bar`**，是 e2e 的九个稳定锚点之一（e2e 组会 `waitForSelector('.overview-stats-bar')`），**不许改名**。

两条口径红线：
1. **不展示「项目大小」与「最近修改」**——编辑器保存路径不更新 `ProjectFile.fileSize` 与 `updatedAt`，那两个数是假的。
2. 计数措辞一律走 `fileCountLabel`，组件里不自己拼 localRoot 文案。

- [ ] **Step 1: 写失败的测试**

新建 `frontend/tests/project-home/stats-bar.test.mjs`：

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/OverviewStatsBar.vue'),
  'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

test('e2e 锚点：根节点类名是 overview-stats-bar', () => {
  assert.ok(SRC.includes('class="overview-stats-bar"'), 'e2e 靠这个类名等统计条渲染，不许改名')
})

test('props 契约：stats 对象 + loading 布尔，都有默认值', () => {
  assert.match(SRC, /stats:\s*\{\s*type:\s*Object,\s*default:\s*\(\)\s*=>\s*\(\{\}\)\s*\}/)
  assert.match(SRC, /loading:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('计数措辞走 fileCountLabel，不在组件里自己拼', () => {
  assert.match(SRC, /import\s*\{[^}]*fileCountLabel[^}]*\}\s*from\s*'@\/utils\/projectHomeFormat\.js'/)
  assert.ok(!/已登记/.test(SRC.split('<script>')[0]), '模板里不许自己写 localRoot 措辞')
})

test('不展示项目大小与最近修改（那两个数是假的）', () => {
  for (const bad of ['项目大小', '最近修改', 'totalBytes', 'fileSize'])
    assert.ok(!CODE.includes(bad), '不该出现: ' + bad)
})

test('四个统计格都在：文件 / 文件夹 / 参与人 / 后台任务', () => {
  assert.ok(SRC.includes('个文件夹'))
  assert.ok(SRC.includes('位参与人'))
  assert.ok(SRC.includes('个后台任务'))
  assert.match(SRC, /class="stat-tile"/)
})

test('浅色红线 + 禁 emoji', () => {
  assert.ok(!SRC.includes('#212629'), '外壳不做深色 chrome')
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC), '禁 emoji')
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: 退出码 1，输出里出现 `error: "ENOENT: no such file or directory, open '/Users/zewei/.../frontend/src/components/project-home/OverviewStatsBar.vue'"`，本文件整体 fail（top-level 读文件抛错），`# fail 1`。

- [ ] **Step 3: 最小实现**

新建目录并写 `frontend/src/components/project-home/OverviewStatsBar.vue`：

```vue
<template>
  <view class="overview-stats-bar">
    <view v-if="loading" class="stats-loading">正在读取项目情况…</view>
    <view v-else class="stats-tiles">
      <view class="stat-tile">
        <text class="stat-value">{{ fileLabel }}</text>
        <text class="stat-caption">{{ fileCaption }}</text>
      </view>
      <view class="stat-tile">
        <text class="stat-value">{{ folderCount }} 个文件夹</text>
        <text class="stat-caption">不含系统目录</text>
      </view>
      <view class="stat-tile">
        <text class="stat-value">{{ memberCount }} 位参与人</text>
        <text class="stat-caption">含负责人</text>
      </view>
      <view class="stat-tile">
        <text class="stat-value">{{ runCount }} 个后台任务</text>
        <text class="stat-caption">{{ runCaption }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// 概览页统计条。数据来自 GET /api/projects/{id}/overview/stats，原样透传。
// 根类名 overview-stats-bar 是 e2e 锚点，不许改名。
//
// 刻意不展示「项目大小」与「最近修改」：编辑器保存路径不更新 ProjectFile 的
// fileSize / updatedAt，那两个数是假的。
import { fileCountLabel, runStatusLabel } from '@/utils/projectHomeFormat.js'

export default {
  name: 'OverviewStatsBar',
  props: {
    stats: { type: Object, default: () => ({}) },
    loading: { type: Boolean, default: false },
  },
  computed: {
    fileLabel() {
      return fileCountLabel(this.stats)
    },
    fileCaption() {
      return this.stats.isLocalRoot ? '本机文件夹，取自最近一次对账' : '不含缓存区与 AI 生成目录'
    },
    folderCount() {
      return Number(this.stats.folderCount || 0)
    },
    memberCount() {
      return Number(this.stats.memberCount || 0)
    },
    runs() {
      return Array.isArray(this.stats.backgroundRuns) ? this.stats.backgroundRuns : []
    },
    runCount() {
      return this.runs.length
    },
    runCaption() {
      if (!this.runs.length) return '当前没有在跑的任务'
      const label = runStatusLabel(this.runs[0].status)
      return label ? '最近一个：' + label : '最近一个：已结束'
    },
  },
}
</script>

<style scoped>
.overview-stats-bar {
  background: #FFFFFF;
  border: 1px solid #E9ECEF;
  border-radius: 6px;
  padding: 14px 18px;
}

.stats-loading {
  font-size: 13px;
  color: #6C757D;
}

.stats-tiles {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.stat-tile {
  flex: 1 1 160px;
  min-width: 140px;
  padding: 10px 12px;
  background: #F8F9FA;
  border-left: 3px solid #5BD197;
  border-radius: 4px;
}

.stat-value {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: #1A5336;
  line-height: 22px;
}

.stat-caption {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: #6C757D;
  line-height: 16px;
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: `# fail 0`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/components/project-home/OverviewStatsBar.vue frontend/tests/project-home/stats-bar.test.mjs && \
git commit -m "feat(project-home): 统计条组件"
```

---

### Task 27: ActivityFeed.vue 动态组件（含拿不到版本记录时的引导态）

**Files:**
- Create: `frontend/src/components/project-home/ActivityFeed.vue`
- Create: `frontend/tests/project-home/activity-feed.test.mjs`
- Test: `frontend/tests/project-home/activity-feed.test.mjs`

**Interfaces:**
- Consumes: props `versions`（`/version/timeline` 的 `data.versions` 原样，条目形状 `{sha, message, authorName, when, kind, note, parents, milestone}`，见 `backend/src/main/java/com/checkba/version/VersionEntry.java:14-23`）、`backgroundRuns`、`loading`、`unavailable`
- Produces: 无 emits。根节点类名 **`.activity-feed`**（e2e 锚点）。

两条硬约束：
1. **不标注 AI/人**：`authorName` 的 `"AI Workdeck"` 有两个语义相反的来源，`VersionEntry` 也不带 email，拿它区分只会误导。
2. `unavailable=true` 时渲染**中性引导态**，不是错误态。已核实 `VersionController.requireMember`（`backend/src/main/java/com/checkba/version/VersionController.java:556-566`）在 `hasReadPermission` 通过后还有一句 `isClient → throw`，所以 CLIENT 身份进概览页一定拿到 `{code:1}`；此外若后端未做「未开仓早退回空数组」那条修复，未开启版本记录的项目也会走到这里。两种情况都不能报错——新建项目十有八九没开版本记录，一进概览页就弹错是最差的第一印象。

- [ ] **Step 1: 写失败的测试**

新建 `frontend/tests/project-home/activity-feed.test.mjs`：

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/ActivityFeed.vue'),
  'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

test('e2e 锚点：根节点类名是 activity-feed', () => {
  assert.ok(SRC.includes('class="activity-feed"'))
})

test('四个 props 齐全且都有默认值', () => {
  assert.match(SRC, /versions:\s*\{\s*type:\s*Array,\s*default:\s*\(\)\s*=>\s*\[\]\s*\}/)
  assert.match(SRC, /backgroundRuns:\s*\{\s*type:\s*Array,\s*default:\s*\(\)\s*=>\s*\[\]\s*\}/)
  assert.match(SRC, /loading:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
  assert.match(SRC, /unavailable:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('unavailable 走中性引导态而不是错误态', () => {
  assert.match(SRC, /v-else-if="unavailable"/)
  assert.ok(SRC.includes('这份案卷还没有版本记录'))
  for (const bad of ['读取失败', '加载失败', '出错了', '请重试'])
    assert.ok(!CODE.includes(bad), 'unavailable 不许是错误文案: ' + bad)
})

test('空列表另有一条空态文案，与 unavailable 分开', () => {
  assert.ok(SRC.includes('还没有动态'))
})

test('不标注 AI/人', () => {
  assert.ok(!CODE.includes('authorName'), '不许读 authorName')
})

test('标题与时间走公共纯函数，不在组件里再实现一遍', () => {
  assert.match(SRC, /import\s*\{[^}]*versionTitle[^}]*\}\s*from\s*'@\/utils\/projectHomeFormat\.js'/)
  assert.ok(SRC.includes('formatDateTime'))
  assert.ok(!/月.*日.*getHours/s.test(SRC), '不许自己格式化时间')
})

test('后台 AI 任务与版本条目合成同一条 feed', () => {
  assert.ok(SRC.includes('runStatusDotClass'))
  assert.match(SRC, /rows\s*\(\)/)
})

test('禁 emoji + 浅色', () => {
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  assert.ok(!SRC.includes('#212629'))
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: 退出码 1，`error: "ENOENT: no such file or directory, open '.../components/project-home/ActivityFeed.vue'"`，`# fail 1`。

- [ ] **Step 3: 最小实现**

新建 `frontend/src/components/project-home/ActivityFeed.vue`：

```vue
<template>
  <view class="activity-feed">
    <view v-if="loading" class="activity-hint">正在读取动态…</view>

    <view v-else-if="unavailable" class="activity-guide">
      <text class="activity-guide-title">这份案卷还没有版本记录</text>
      <text class="activity-guide-desc">开启之后，每次改动都会自动留底，这里会按时间列出做过什么。开启的入口在工作台右侧的「版本」面板里。</text>
    </view>

    <view v-else-if="!rows.length" class="activity-guide">
      <text class="activity-guide-title">还没有动态</text>
      <text class="activity-guide-desc">在工作台里改过文件、或者让 AI 跑过一次任务之后，这里就会有记录。</text>
    </view>

    <view v-else class="activity-rows">
      <view v-for="row in rows" :key="row.key" class="activity-row">
        <view class="activity-dot" :class="row.dotClass"></view>
        <view class="activity-body">
          <text class="activity-title">{{ row.title }}</text>
          <text class="activity-time">{{ row.time }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
// 概览页动态块。主源是版本时间线，副源是后台 AI 任务。
//
// 两条硬约束：
// 1) 不标注 AI/人：authorName 的 "AI Workdeck" 有两个语义相反的来源，
//    VersionEntry 也不带 email，拿它区分只会误导。
// 2) unavailable=true 不是防御性编程：VersionController.requireMember:562-564 在
//    hasReadPermission 通过后还显式拒 CLIENT，客户身份进概览页一定拿到 {code:1}；
//    另外若后端仍未做「未开仓早退回空 versions」那条修复，未开启版本记录的项目
//    也会走这里。两种情况都必须是中性引导态而不是报错。
//
// 标题原样渲染，能容纳时间线的 6 种文案形状（含带空格的「8 月 8 日下午的工作」）。
import { versionTitle, formatDateTime, runStatusLabel, runStatusDotClass } from '@/utils/projectHomeFormat.js'

export default {
  name: 'ActivityFeed',
  props: {
    versions: { type: Array, default: () => [] },
    backgroundRuns: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    unavailable: { type: Boolean, default: false },
  },
  computed: {
    rows() {
      const runs = (this.backgroundRuns || []).map((r) => ({
        key: 'run-' + r.conversationId,
        title: 'AI 任务 ' + (runStatusLabel(r.status) || '已结束'),
        time: formatDateTime(r.updatedAt),
        dotClass: runStatusDotClass(r.status),
      }))
      const vers = (this.versions || []).map((v) => ({
        key: 'ver-' + v.sha,
        title: versionTitle(v),
        time: formatDateTime(v.when),
        dotClass: '',
      }))
      return runs.concat(vers)
    },
  },
}
</script>

<style scoped>
.activity-hint {
  font-size: 13px;
  color: #6C757D;
}

.activity-guide-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: #2C3338;
}

.activity-guide-desc {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 19px;
  color: #6C757D;
}

.activity-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid #F1F3F5;
}

.activity-row:last-child {
  border-bottom: none;
}

.activity-dot {
  width: 7px;
  height: 7px;
  margin-top: 6px;
  border-radius: 50%;
  background: #CED4DA;
  flex: none;
}

.activity-dot.dot-running {
  background: #5BD197;
}

.activity-dot.dot-attention {
  background: #F5B60D;
}

.activity-dot.dot-error {
  background: #E74C3C;
}

.activity-body {
  flex: 1;
  min-width: 0;
}

/* 标题一律不压缩空白：未命名工作的默认名带空格，压掉就成错别字 */
.activity-title {
  display: block;
  font-size: 13px;
  line-height: 20px;
  color: #2C3338;
  white-space: pre-wrap;
  word-break: break-word;
}

.activity-time {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: #6C757D;
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: `# fail 0`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/components/project-home/ActivityFeed.vue frontend/tests/project-home/activity-feed.test.mjs && \
git commit -m "feat(project-home): 动态组件与拿不到版本记录时的引导态"
```

---

### Task 28: TaskSchedule.vue 日程与任务组件（A 期空态，B 期只换渲染分支）

**Files:**
- Create: `frontend/src/components/project-home/TaskSchedule.vue`
- Create: `frontend/tests/project-home/task-schedule.test.mjs`
- Test: `frontend/tests/project-home/task-schedule.test.mjs`

**Interfaces:**
- Consumes: props `tasks`（`GET /api/projects/{id}/tasks` 的 `data.tasks` 原样；A 期后端恒返回 `[]`）、`loading`
- Produces: 无 emits。根节点类名 **`.task-schedule`**（e2e 锚点）。

本期渲染的是空态，列表分支同样落地——B 期接上任务系统时父页面与端点一行不改。

**字段断言只钉两条**：A 期 `tasks` 恒为 `[]`，本切片**没有任何任务定义 `project_task` 表**，所以模板里的 `t.uid` / `t.status` / `t.dueDate` 是 B 期的预期形状，不是已落地的契约。静态测试只断言 `v-for="t in tasks"` 与 `t.title` 存在，其余字段写成注释说明——不要把一个还没落地的表结构断言成硬契约。

- [ ] **Step 1: 写失败的测试**

新建 `frontend/tests/project-home/task-schedule.test.mjs`：

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/TaskSchedule.vue'),
  'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

test('e2e 锚点：根节点类名是 task-schedule', () => {
  assert.ok(SRC.includes('class="task-schedule"'))
})

test('props 契约', () => {
  assert.match(SRC, /tasks:\s*\{\s*type:\s*Array,\s*default:\s*\(\)\s*=>\s*\[\]\s*\}/)
  assert.match(SRC, /loading:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('空态文案存在（A 期唯一会渲染的分支）', () => {
  assert.ok(SRC.includes('还没有排任务'))
})

test('列表分支已落地（B 期只换渲染分支，父页面与端点不改）', () => {
  assert.match(SRC, /v-for="t in tasks"/)
  assert.ok(SRC.includes('t.title'))
  // 其余字段（uid / status / dueDate）是 B 期 project_task 的预期形状：
  // A 期后端恒返回 []，本切片没有任何任务定义该表，故不断言，避免把
  // 未落地的表结构钉成硬契约。
})

test('不混用 AI 步骤条的词', () => {
  assert.ok(!CODE.includes('进度条'), '「进度」是 todo_write 的词，项目级里程碑一律叫「任务」')
})

test('禁 emoji + 浅色', () => {
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  assert.ok(!SRC.includes('#212629'))
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: 退出码 1，`error: "ENOENT: no such file or directory, open '.../components/project-home/TaskSchedule.vue'"`，`# fail 1`。

- [ ] **Step 3: 最小实现**

新建 `frontend/src/components/project-home/TaskSchedule.vue`：

```vue
<template>
  <view class="task-schedule">
    <view v-if="loading" class="task-hint">正在读取任务…</view>

    <view v-else-if="!tasks.length" class="task-guide">
      <text class="task-guide-title">还没有排任务</text>
      <text class="task-guide-desc">交付日期、待办和提醒以后会排在这里。</text>
    </view>

    <view v-else class="task-rows">
      <view v-for="t in tasks" :key="t.uid || t.id" class="task-row">
        <text class="task-status" :class="'task-status-' + statusKey(t.status)">{{ statusLabel(t.status) }}</text>
        <text class="task-title">{{ t.title }}</text>
        <text class="task-due">{{ t.dueDate || '' }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// 概览页「日程与任务」块。A 期后端恒返回空数组，实际渲染的只有空态；
// 列表分支同样落地，B 期接上任务系统时父页面与端点一行不改。
//
// 注意：uid / status / dueDate 是 B 期 project_task 的**预期**字段形状，
// 本切片没有任何任务定义这张表，别把它当成已生效的契约往别处引。
//
// 用词边界：项目级里程碑叫「任务」，AI 单次工作的步骤条叫「进度」
// （那是 todo_write 的东西），两个词不能混。
export default {
  name: 'TaskSchedule',
  props: {
    tasks: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
  },
  methods: {
    statusKey(status) {
      return String(status || 'OPEN').toLowerCase()
    },
    statusLabel(status) {
      if (status === 'DOING') return '进行中'
      if (status === 'DONE') return '已完成'
      return '待办'
    },
  },
}
</script>

<style scoped>
.task-hint {
  font-size: 13px;
  color: #6C757D;
}

.task-guide-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: #2C3338;
}

.task-guide-desc {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 19px;
  color: #6C757D;
}

.task-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid #F1F3F5;
}

.task-row:last-child {
  border-bottom: none;
}

.task-status {
  flex: none;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  background: #F1F3F5;
  color: #6C757D;
}

.task-status.task-status-doing {
  background: #E6F9F0;
  color: #1A5336;
}

.task-status.task-status-done {
  background: #F8F9FA;
  color: #ADB5BD;
}

.task-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: #2C3338;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-due {
  flex: none;
  font-size: 11px;
  color: #6C757D;
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: `# fail 0`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/components/project-home/TaskSchedule.vue frontend/tests/project-home/task-schedule.test.mjs && \
git commit -m "feat(project-home): 日程与任务块（A 期空态 + B 期列表分支）"
```

---

### Task 29: ConversationList.vue AI 对话历史列表组件

**Files:**
- Create: `frontend/src/components/project-home/ConversationList.vue`
- Create: `frontend/tests/project-home/conversation-list.test.mjs`
- Test: `frontend/tests/project-home/conversation-list.test.mjs`

**Interfaces:**
- Consumes: props `conversations`（`GET /api/projects/{id}/conversations` 的 `data.conversations` 原样：`conversationId` / `title` / `lastMessage` / `updatedAt` / `runStatus` / `ownerUserId` / `ownerName`）、`loading`、`hasMore`
- Produces: emits `open`（payload 是 `conversationId` 字符串）、`load-more`（无 payload）。根节点类名 **`.conversation-list`**（e2e 锚点）。`emits` 必须写成数组字面量——`scripts/check-emit-bindings.mjs` 靠静态匹配 `emits: [...]` 与 `$emit('字面量')`，写成变量会被判为「动态 emit」而整组跳过检查。

硬约束：**前端不许再剥标签、不许再截字数**。服务端 `ProjectAiMessageService` 的 `cleanTitle` / `extractPreview` / `truncatePreview` 已清洗过，仓里已有两套并行漂移的正则，不许出第三套。两个展示坑要兜底：`lastMessage` 可能是空串（`extractPreview` 对以 import/def/function/class/const/let/var/public/private 开头的正文直接返回空串），`title` 可能是字面量「新对话」（清洗兜底与 LLM 生成失败同文案，前端无法区分，照常显示不特判）。

- [ ] **Step 1: 写失败的测试**

新建 `frontend/tests/project-home/conversation-list.test.mjs`：

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/ConversationList.vue'),
  'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

test('e2e 锚点：根节点类名是 conversation-list', () => {
  assert.ok(SRC.includes('class="conversation-list"'))
})

test('props 契约', () => {
  assert.match(SRC, /conversations:\s*\{\s*type:\s*Array,\s*default:\s*\(\)\s*=>\s*\[\]\s*\}/)
  assert.match(SRC, /loading:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
  assert.match(SRC, /hasMore:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('emits 已声明（check:emits 依赖这个），且 open 带 conversationId', () => {
  assert.match(SRC, /emits:\s*\['open',\s*'load-more'\]/)
  assert.match(SRC, /\$emit\('open',\s*c\.conversationId\)/)
  assert.match(SRC, /\$emit\('load-more'\)/)
})

test('不做第三套清洗：不剥标签、不截字数', () => {
  assert.ok(!CODE.includes('.replace('), '不许再剥一次标签')
  assert.ok(!CODE.includes('substr'), '不许再截一次字数')
  assert.ok(!/\.slice\(0,\s*\d+\)/.test(SRC), '不许再截一次字数')
  assert.ok(!CODE.includes('thinking'), '服务端已剥过 thinking 标签')
})

test('空预览兜底走 hasConversationPreview，不留空行', () => {
  assert.match(SRC, /import\s*\{[^}]*hasConversationPreview[^}]*\}\s*from\s*'@\/utils\/projectHomeFormat\.js'/)
  assert.match(SRC, /v-if="hasPreview\(c\)"/)
})

test('不内嵌 ChatInterface（loadHistoryChat 会抢占当前会话）', () => {
  assert.ok(!CODE.includes('ChatInterface'))
  assert.ok(!CODE.includes('loadHistoryChat'))
})

test('禁 emoji + 浅色', () => {
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  assert.ok(!SRC.includes('#212629'))
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: 退出码 1，`error: "ENOENT: no such file or directory, open '.../components/project-home/ConversationList.vue'"`，`# fail 1`。

- [ ] **Step 3: 最小实现**

新建 `frontend/src/components/project-home/ConversationList.vue`：

```vue
<template>
  <view class="conversation-list">
    <view v-if="loading && !conversations.length" class="conv-hint">正在读取对话历史…</view>

    <view v-else-if="!conversations.length" class="conv-guide">
      <text class="conv-guide-title">这份案卷还没有 AI 对话</text>
      <text class="conv-guide-desc">进工作台打开 AI 面板问第一个问题，之后每次对话都会记在这里。</text>
    </view>

    <template v-else>
      <view
        v-for="c in conversations"
        :key="c.conversationId"
        class="conv-card"
        @tap="$emit('open', c.conversationId)"
      >
        <view class="conv-card-head">
          <text class="conv-title">{{ c.title }}</text>
          <text v-if="statusLabel(c.runStatus)" class="conv-status" :class="dotClass(c.runStatus)">
            {{ statusLabel(c.runStatus) }}
          </text>
        </view>
        <text v-if="hasPreview(c)" class="conv-preview">{{ c.lastMessage }}</text>
        <text class="conv-meta">{{ metaOf(c) }}</text>
      </view>
      <view v-if="hasMore" class="conv-more" @tap="$emit('load-more')">看更早的对话</view>
    </template>
  </view>
</template>

<script>
// 概览页 AI 对话历史「列表层」。全项目成员可见（分层决策：只放开标题/时间/
// 发起人/状态，正文层一行都不放开）。点击不内嵌 ChatInterface —— loadHistoryChat
// 是完整切换会话，会在用户还没进工作台时就抢占当前会话，所以只 emit 出去由父页面跳工作台。
//
// 硬约束：title / lastMessage 是服务端 cleanTitle/extractPreview/truncatePreview
// 的输出，本组件一律原样渲染。仓里已有两套并行漂移的清洗正则，不许出第三套。
// 两个已知展示坑的兜底：lastMessage 可能是空串（不留空行）；title 可能是字面量
// 「新对话」（清洗兜底与 LLM 生成失败同文案，无法区分，照常显示不特判）。
import { formatDateTime, runStatusLabel, runStatusDotClass, hasConversationPreview } from '@/utils/projectHomeFormat.js'

export default {
  name: 'ConversationList',
  props: {
    conversations: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    hasMore: { type: Boolean, default: false },
  },
  emits: ['open', 'load-more'],
  methods: {
    hasPreview(c) {
      return hasConversationPreview(c)
    },
    statusLabel(status) {
      return runStatusLabel(status)
    },
    dotClass(status) {
      return runStatusDotClass(status)
    },
    metaOf(c) {
      return [c.ownerName, formatDateTime(c.updatedAt)].filter(Boolean).join(' · ')
    },
  },
}
</script>

<style scoped>
.conv-hint {
  font-size: 13px;
  color: #6C757D;
}

.conv-guide-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: #2C3338;
}

.conv-guide-desc {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 19px;
  color: #6C757D;
}

.conv-card {
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #F8F9FA;
  border: 1px solid #E9ECEF;
  border-radius: 4px;
  cursor: pointer;
}

.conv-card:hover {
  border-color: #5BD197;
}

.conv-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conv-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: #2C3338;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-status {
  flex: none;
  font-size: 11px;
  color: #6C757D;
}

.conv-status.dot-running {
  color: #1A5336;
}

.conv-status.dot-attention {
  color: #8A6D1D;
}

.conv-status.dot-error {
  color: #E74C3C;
}

.conv-preview {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: #6C757D;
}

.conv-meta {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: #ADB5BD;
}

.conv-more {
  padding: 8px 0;
  text-align: center;
  font-size: 12px;
  color: #1A5336;
  cursor: pointer;
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: `# fail 0`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/components/project-home/ConversationList.vue frontend/tests/project-home/conversation-list.test.mjs && \
git commit -m "feat(project-home): AI 对话历史列表组件"
```

---

### Task 30: ProfileHeader.vue 档案头（只读展示 + 行内手填 + 空态引导）

**Files:**
- Create: `frontend/src/components/project-home/ProfileHeader.vue`
- Create: `frontend/tests/project-home/profile-header.test.mjs`
- Test: `frontend/tests/project-home/profile-header.test.mjs`

**Interfaces:**
- Consumes: props `projectId`（Number, required）、`projectName`、`fields`（`GET /profile` 的 `data.fields` 原样，服务端保证恒 5 条且顺序固定 client → matterType → openedAt → nextStep → counterparty，**组件不许自己补齐或排序**）、`canEdit`；`MATTER_TYPES` from `@/config/matterTypes.js`；`isProfileEmpty` / `profileFieldHint` from `@/utils/projectHomeFormat.js`
- Produces: emits `save`，payload `{ fieldKey, value }`；`value` 为空串表示清空该字段。根节点类名 **`.profile-header`**（e2e 锚点），输入框类名 **`.profile-field-input`**（e2e 组的「手填档案落库为 source=user」那一步会 `page.type('.profile-field-input .uni-input-input', ...)`，**不许改名**）。

三条硬约束：
1. **编辑走行内 input，不开弹窗**——`awd-*` 弹窗样式在仓里没有集中定义（`project-overview.vue` / `ChatInterface.vue` / `FileTree.vue` 各自带一份 scoped 副本），走行内就不需要复制第四份，否则渲染成无样式裸框。
2. **A 期不渲染「重新分析」按钮**——AI 抽取链路在 Plan 2，先出按钮就是个点了没反应的死按钮。同时空态文案用的是手填引导（「先把客户和事项类型填上」）而不是 AI 引导，这是切片裁剪的有意偏离，必须在源码里留一行注释标注，否则下一个人会当成已完成。
3. `source='ai'` 与 `source='default'` 都要弱化标记：律师不能把模型猜的立项日期、或建档时间当成有人填过的事实。

- [ ] **Step 1: 写失败的测试**

新建 `frontend/tests/project-home/profile-header.test.mjs`：

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/ProfileHeader.vue'),
  'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

test('e2e 锚点：根类名 profile-header + 输入框类名 profile-field-input', () => {
  assert.ok(SRC.includes('class="profile-header"'))
  assert.ok(SRC.includes('class="profile-field-input"'), 'e2e 靠这个类名找输入框，不许改名')
})

test('props 契约', () => {
  assert.match(SRC, /projectId:\s*\{\s*type:\s*Number,\s*required:\s*true\s*\}/)
  assert.match(SRC, /projectName:\s*\{\s*type:\s*String,\s*default:\s*''\s*\}/)
  assert.match(SRC, /fields:\s*\{\s*type:\s*Array,\s*default:\s*\(\)\s*=>\s*\[\]\s*\}/)
  assert.match(SRC, /canEdit:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('emits save 已声明且 payload 是 {fieldKey, value}', () => {
  assert.match(SRC, /emits:\s*\['save'\]/)
  assert.match(SRC, /\$emit\('save',\s*\{\s*fieldKey:[^}]*value:[^}]*\}\s*\)/)
})

test('label 用服务端下发的，不在前端写第二份中文文案表', () => {
  assert.ok(SRC.includes('f.label'))
  for (const bad of ["'客户'", "'事项类型'", "'立项时间'", "'下一步'", "'对方'"])
    assert.ok(!SRC.includes(bad), '中文标签的单一来源是服务端: ' + bad)
})

test('不自己补齐或排序 fields（服务端保证恒 5 条顺序固定）', () => {
  assert.match(SRC, /v-for="f in fields"/)
  assert.ok(!SRC.includes('.sort('), '不许排序')
  assert.ok(!SRC.includes('FIELD_ORDER'), '不许在前端复制一份字段顺序表')
})

test('行内编辑而不是弹窗（awd-* 样式没有集中定义）', () => {
  assert.ok(!CODE.includes('awd-'), '不引入 awd-* 类名就不用自带 scoped 副本')
  assert.ok(!SRC.includes('uni.showModal'))
})

test('值没变就不发请求（blur 与 confirm 会各触发一次）', () => {
  assert.ok(SRC.includes('if (value === beforeValue) return'))
})

test('A 期不渲染「重新分析」死按钮，且标注了 Plan 2 要改回 AI 引导', () => {
  assert.ok(!CODE.includes('重新分析'))
  assert.ok(!CODE.includes('analyze'))
  assert.ok(SRC.includes('Plan 2 上线 AI 抽取后'), '空态文案是切片裁剪的有意偏离，必须留标注')
})

test('ai / default 都弱化标记，走 profileFieldHint', () => {
  assert.match(SRC, /import\s*\{[^}]*profileFieldHint[^}]*\}\s*from\s*'@\/utils\/projectHomeFormat\.js'/)
  assert.match(SRC, /profile-field-weak/)
})

test('空态引导 + 事项类型下拉用 MATTER_TYPES', () => {
  assert.match(SRC, /import\s*\{\s*MATTER_TYPES\s*\}\s*from\s*'@\/config\/matterTypes\.js'/)
  assert.match(SRC, /isProfileEmpty/)
  assert.ok(SRC.includes('这份案卷的档案还是空的'))
})

test('禁 emoji + 浅色', () => {
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  assert.ok(!SRC.includes('#212629'))
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: 退出码 1，`error: "ENOENT: no such file or directory, open '.../components/project-home/ProfileHeader.vue'"`，`# fail 1`。

- [ ] **Step 3: 最小实现**

新建 `frontend/src/components/project-home/ProfileHeader.vue`：

```vue
<template>
  <view class="profile-header">
    <text class="profile-project-name">{{ projectName }}</text>

    <view v-if="showGuide" class="profile-guide">
      <text class="profile-guide-desc">这份案卷的档案还是空的。先把客户和事项类型填上，同事和客户点进来一眼就知道这是什么案子。</text>
      <view v-if="canEdit" class="profile-guide-btn" @tap="startEdit('client')">开始填写</view>
    </view>

    <view class="profile-fields">
      <view v-for="f in fields" :key="f.fieldKey" class="profile-field">
        <text class="profile-field-label">{{ f.label }}</text>

        <picker
          v-if="editingKey === f.fieldKey && f.fieldKey === 'matterType'"
          mode="selector"
          :range="matterTypes"
          @change="onPickMatterType"
          @cancel="cancelEdit"
        >
          <view class="profile-field-picker">{{ draft || '选择事项类型' }}</view>
        </picker>

        <input
          v-else-if="editingKey === f.fieldKey"
          class="profile-field-input"
          :value="draft"
          :placeholder="placeholderOf(f.fieldKey)"
          :focus="true"
          @input="draft = $event.detail.value"
          @confirm="commitEdit"
          @blur="commitEdit"
        />

        <text
          v-else
          class="profile-field-value"
          :class="{ 'profile-field-empty': !f.fieldValue, 'profile-field-weak': isWeak(f) }"
          @tap="startEdit(f.fieldKey)"
        >{{ f.fieldValue || '未填写' }}</text>

        <text v-if="hintOf(f)" class="profile-field-hint">{{ hintOf(f) }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// 项目档案头。fields 是 GET /api/projects/{id}/profile 的 data.fields 原样：
// 服务端保证恒 5 条、顺序固定、label 由服务端给，本组件不补齐、不排序、不写第二份文案表。
//
// 编辑走行内 input 不开弹窗：awd-* 弹窗/按钮样式在仓里没有集中定义（project-overview /
// ChatInterface / FileTree 各自带一份 scoped 副本），走行内就不用复制第四份。
//
// A 期不渲染「重新分析」按钮：AI 抽取链路在 Plan 2，先出按钮就是点了没反应的死按钮。
// 空态引导因此写成手填口径而不是「让 AI 读一遍项目里的文件」——
// Plan 2 上线 AI 抽取后，把空态引导改回 AI 文案，并把按钮随抽取链路一起放出来。
import { MATTER_TYPES } from '@/config/matterTypes.js'
import { isProfileEmpty, profileFieldHint } from '@/utils/projectHomeFormat.js'

const PLACEHOLDERS = {
  client: '例如：北京某某科技有限公司',
  matterType: '选择事项类型',
  openedAt: '例如：2026-08-01',
  nextStep: '例如：8 月 15 日前出尽调报告初稿',
  counterparty: '例如：上海某某贸易有限公司',
}

export default {
  name: 'ProfileHeader',
  props: {
    projectId: { type: Number, required: true },
    projectName: { type: String, default: '' },
    fields: { type: Array, default: () => [] },
    canEdit: { type: Boolean, default: false },
  },
  emits: ['save'],
  data() {
    return { editingKey: '', draft: '', matterTypes: MATTER_TYPES }
  },
  computed: {
    showGuide() {
      return isProfileEmpty(this.fields) && !this.editingKey
    },
  },
  watch: {
    // 换了项目就把半截的编辑态丢掉，避免把 A 项目的输入提交到 B 项目
    projectId() {
      this.cancelEdit()
    },
  },
  methods: {
    isWeak(f) {
      return f.source === 'default' || f.source === 'ai'
    },
    hintOf(f) {
      return profileFieldHint(f)
    },
    placeholderOf(fieldKey) {
      return PLACEHOLDERS[fieldKey] || ''
    },
    startEdit(fieldKey) {
      if (!this.canEdit) return
      const f = this.fields.find((x) => x.fieldKey === fieldKey)
      this.editingKey = fieldKey
      this.draft = (f && f.fieldValue) || ''
    },
    cancelEdit() {
      this.editingKey = ''
      this.draft = ''
    },
    commitEdit() {
      // confirm 与 blur 会先后各来一次，第一次已清空 editingKey，第二次直接返回
      if (!this.editingKey) return
      const fieldKey = this.editingKey
      const value = this.draft
      const before = this.fields.find((x) => x.fieldKey === fieldKey)
      // source==='default' 的值是服务端派生的，不算"原值"：照原样再提交一次
      // 正是把它锁成 source='user' 的正常操作
      const beforeValue = (before && before.source !== 'default' && before.fieldValue) || ''
      this.cancelEdit()
      if (value === beforeValue) return
      this.$emit('save', { fieldKey: fieldKey, value: value })
    },
    onPickMatterType(e) {
      const idx = Number(e.detail.value)
      this.draft = this.matterTypes[idx] || ''
      this.commitEdit()
    },
  },
}
</script>

<style scoped>
.profile-header {
  background: #FFFFFF;
  border: 1px solid #E9ECEF;
  border-radius: 6px;
  padding: 18px 20px;
}

.profile-project-name {
  display: block;
  font-size: 20px;
  font-weight: 600;
  color: #1A5336;
  line-height: 28px;
}

.profile-guide {
  margin-top: 12px;
  padding: 12px 14px;
  background: #F8F9FA;
  border-left: 3px solid #5BD197;
  border-radius: 4px;
}

.profile-guide-desc {
  display: block;
  font-size: 12px;
  line-height: 19px;
  color: #6C757D;
}

.profile-guide-btn {
  display: inline-block;
  margin-top: 10px;
  padding: 5px 14px;
  background: #1A5336;
  color: #FFFFFF;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}

.profile-guide-btn:hover {
  background: #2D7A52;
}

.profile-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 24px;
  margin-top: 16px;
}

.profile-field {
  flex: 1 1 200px;
  min-width: 180px;
}

.profile-field-label {
  display: block;
  font-size: 11px;
  color: #ADB5BD;
  line-height: 16px;
}

.profile-field-value {
  display: block;
  margin-top: 2px;
  font-size: 14px;
  line-height: 22px;
  color: #2C3338;
  cursor: pointer;
  word-break: break-word;
}

.profile-field-value.profile-field-empty {
  color: #CED4DA;
}

/* AI 猜的与建档时间派生的都弱化：律师不能把它们当成有人填过的事实 */
.profile-field-value.profile-field-weak {
  color: #868E96;
}

.profile-field-input,
.profile-field-picker {
  margin-top: 2px;
  padding: 3px 6px;
  font-size: 14px;
  line-height: 22px;
  color: #2C3338;
  background: #FFFFFF;
  border: 1px solid #1A5336;
  border-radius: 3px;
  box-sizing: border-box;
  width: 100%;
}

.profile-field-hint {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: #ADB5BD;
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: `# fail 0`。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/components/project-home/ProfileHeader.vue frontend/tests/project-home/profile-header.test.mjs && \
git commit -m "feat(project-home): 档案头组件与手填通道"
```

---

### Task 31: project-home 页面容器（取数、卷轴布局、导航、多实例守卫）

**Files:**
- Create: `frontend/src/pages/project-home/project-home.vue`
- Create: `frontend/src/pages/project-home/project-home.scss`
- Modify: `frontend/src/App.vue:14`（路由埋点那条注释的页面数已过时，顺手改）
- Create: `frontend/tests/project-home/page.test.mjs`
- Verify only（**不改**）: `frontend/src/pages.json` —— 两个新页的注册归 Task 16 那一组，本任务只做幂等判定与断言
- Test: `frontend/tests/project-home/page.test.mjs`

**Interfaces:**
- Consumes: `@/services/api.js` 的 `getMyProjects`（**裸数组**，`ProjectController` 直接返 `List<ProjectCardDTO>`）/ `getProjectOverviewStats` / `getProjectProfile` / `saveProjectProfileField` / `getProjectConversations` / `getProjectTasks`（Task 25）/ `getVersionTimeline`（既有，信封，`res.data.versions`，签名 `(projectId, limit = 50, fileId)`，见 `services/api.js:2219`）；`recordProjectVisit`（`utils/recentProjects.js:10`）；`canEditProfile`（Task 24）；五个子组件（Task 26-30）
- Produces: 路由 `/pages/project-home/project-home?id={projectId}[&openFileId=]`。**本文件是概览页的唯一 owner**——列表页/导航组不创建也不重写它；它们的 `check-navigation-contract.mjs` 断言的是这里的五个子组件标签（`<ProfileHeader`、`<OverviewStatsBar`、`<ActivityFeed`、`<TaskSchedule`、`<ConversationList`）与 `goWorkbench` 的实现形状，不是任何私有 class 名。
- e2e 锚点（**九个之三，不许改名**）：根节点 `.page-project-home`、返回按钮 `.btn-project-list`、进工作台按钮 `.btn-workbench`；另外顶栏标题固定文案「项目概览」且带 `.home-topbar-title` 类，e2e 组用它做 blur 触发点。

四条硬约束：
1. **轮询纪律**：只在 onLoad 与 onShow 各刷一次，A 期不起任何定时器。
2. **绝不调 `getVersionStatus` / `/version/status`**：它在 enabled 时会一路跑两次 `git add "."`，工作台已有 7 处触发点在喂同一份状态。
3. `/version/timeline` 失败必须落成 `unavailable` 引导态，**不能弹错误 toast**。
4. 多实例守卫用**自己的指针名** `window.__checkbaProjectHomeVm`，复用工作台的 `__checkbaActiveOverviewVm`（`project-overview.vue:2049-2052 / :2231 / :2288 / :3354-3360`）会让工作台的全局事件被概览页拦掉。

关于 `conversationId`：本页只负责把它带到工作台 URL 上；**工作台侧的消费（`onLoad` 读 query → 调既有 `loadHistoryChat`）由导航组在 Task 16-23 区间落地**，本任务不实现、也不要在注释里写「Plan 1 不做」。

- [ ] **Step 1: 写失败的测试**

新建 `frontend/tests/project-home/page.test.mjs`：

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../src')
const SRC = readFileSync(resolve(ROOT, 'pages/project-home/project-home.vue'), 'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

// pages.json 不是纯 JSON（:2 行尾有 // 注释，注释里还带 https:// ），
// 用逐字符扫描剥注释，别用 /\/\/.*$/ ——那会把字符串里的 URL 也砍掉。
function stripJsonComments(text) {
  let out = ''
  let inStr = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (inStr) {
      out += ch
      if (ch === '\\') { out += text[++i]; continue }
      if (ch === '"') inStr = false
      continue
    }
    if (ch === '"') { inStr = true; out += ch; continue }
    if (ch === '/' && text[i + 1] === '/') { while (i < text.length && text[i] !== '\n') i++; out += '\n'; continue }
    if (ch === '/' && text[i + 1] === '*') { i += 2; while (i < text.length && !(text[i] === '*' && text[i + 1] === '/')) i++; i++; continue }
    out += ch
  }
  return out
}
const PAGES = JSON.parse(stripJsonComments(readFileSync(resolve(ROOT, 'pages.json'), 'utf8')))

test('pages.json 注册了两个新页且都显式写 navigationStyle: custom', () => {
  const home = PAGES.pages.find((x) => x.path === 'pages/project-home/project-home')
  assert.ok(home, 'pages.json 里没有 project-home（归 Task 16 组注册）')
  assert.equal(home.style.navigationStyle, 'custom', 'globalStyle 里没有这一项，漏写会得到系统导航栏')
  assert.equal(home.style.navigationBarTitleText, '项目概览页')
  const list = PAGES.pages.find((x) => x.path === 'pages/project-list/project-list')
  assert.ok(list, 'pages.json 里没有 project-list')
  assert.equal(list.style.navigationStyle, 'custom')
  // 工作台那一项一行都不许动（改名要动 9 处硬编码 URL + 11 个模块文件）
  assert.ok(PAGES.pages.some((x) => x.path === 'pages/project-overview/project-overview'))
})

test('App.vue 的路由埋点注释与 pages.json 的页面数一致', () => {
  const app = readFileSync(resolve(ROOT, 'App.vue'), 'utf8')
  const m = app.match(/pages\.json 里的 (\d+) 个页面/)
  assert.ok(m, 'App.vue 里找不到路由埋点注释')
  assert.equal(Number(m[1]), PAGES.pages.length, '加了新页就要同步这条注释')
})

test('e2e 锚点类名齐全', () => {
  for (const c of ['page-project-home', 'btn-project-list', 'btn-workbench', 'home-topbar-title'])
    assert.ok(SRC.includes(c), '缺 e2e 锚点: ' + c)
  assert.ok(SRC.includes('>项目概览<'), 'e2e 用顶栏标题文案做 blur 触发点')
})

test('轮询纪律：不起定时器，不调 /version/status', () => {
  assert.ok(!CODE.includes('setInterval'), 'A 期不许起轮询')
  assert.ok(!CODE.includes('getVersionStatus'), '/version/status 会跑两次 git add')
  assert.ok(!CODE.includes('version/status'))
})

test('timeline 失败落 unavailable 引导态而不是 toast', () => {
  const i = SRC.indexOf('async loadActivity(')
  assert.ok(i > 0)
  const body = SRC.slice(i, SRC.indexOf('async loadTasks('))
  assert.match(body, /this\.activityUnavailable\s*=\s*true/)
  assert.ok(!body.includes('showToast'), 'timeline 失败不许弹 toast')
})

test('多实例守卫用自己的指针名', () => {
  assert.ok(SRC.includes('window.__checkbaProjectHomeVm'))
  assert.ok(!CODE.includes('__checkbaActiveOverviewVm'), '不许复用工作台的指针')
  assert.match(SRC, /window\.__checkbaProjectHomeVm === this/)
})

test('onLoad 记最近项目', () => {
  assert.match(SRC, /import\s*\{\s*recordProjectVisit\s*\}\s*from\s*'@\/utils\/recentProjects\.js'/)
  assert.match(SRC, /recordProjectVisit\(/)
})

test('getMyProjects 按裸数组解（不许照抄 admin.vue 的 res.data）', () => {
  const i = SRC.indexOf('async loadProjectCard(')
  const body = SRC.slice(i, SRC.indexOf('async loadProfile('))
  assert.match(body, /Array\.isArray\(res\)\s*\?\s*res\s*:\s*\[\]/)
})

test('信封端点一律再取一层 data', () => {
  assert.ok(SRC.includes('res.data.fields'))
  assert.ok(SRC.includes('res.data.versions'))
  assert.ok(SRC.includes('res.data.tasks'))
})

test('导航出口：工作台 reLaunch、列表按页面栈分流', () => {
  assert.match(SRC, /uni\.reLaunch\(\{\s*url\s*\}\)/)
  assert.ok(SRC.includes('conversationId=${encodeURIComponent(conversationId)}'))
  assert.ok(SRC.includes("prev.route === 'pages/project-list/project-list'"))
  assert.ok(SRC.includes('uni.navigateBack'))
  assert.ok(SRC.includes("uni.redirectTo({ url: '/pages/project-list/project-list' })"))
  assert.ok(!SRC.includes("uni.navigateTo({ url: '/pages/project-list"), '双向 navigateTo 会堆出多个列表实例')
})

test('翻页带回复合游标的第二维', () => {
  assert.ok(SRC.includes('nextBeforeId'), '复合游标第二维不能在前端丢掉')
})

test('五个区块按卷轴顺序排列', () => {
  const order = ['ProfileHeader', 'OverviewStatsBar', 'ActivityFeed', 'TaskSchedule', 'ConversationList']
  const tpl = SRC.slice(0, SRC.indexOf('</template>'))
  let at = -1
  for (const c of order) {
    const i = tpl.indexOf('<' + c)
    assert.ok(i > at, '卷轴顺序不对: ' + c)
    at = i
  }
})

test('概览页不内嵌 ChatInterface；样式外置；禁 emoji；浅色', () => {
  assert.ok(!CODE.includes('ChatInterface'))
  assert.match(SRC, /<style lang="scss" scoped src="\.\/project-home\.scss"><\/style>/)
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  const scss = readFileSync(resolve(ROOT, 'pages/project-home/project-home.scss'), 'utf8')
  assert.ok(!scss.includes('#212629'), '外壳保持浅色')
  assert.ok(scss.includes('#5BD197') && scss.includes('#1A5336'))
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home
```
Expected: 退出码 1，`error: "ENOENT: no such file or directory, open '.../src/pages/project-home/project-home.vue'"`，`# fail 1`。

- [ ] **Step 3a: 确认 pages.json 已由 Task 16 组注册（幂等判定，本任务不改这个文件）**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && grep -cE 'pages/project-(list/project-list|home/project-home)' src/pages.json
```
Expected: 输出 `2`，说明列表页组（Task 16 那一组）已把两个新页都注册进 `pages.json`，**跳过本步，什么都不用改**。

输出 `0` 或 `1` 时不要自己补——`pages.json` 的注册单一 owner 是列表页组，两处各插一次会产生重复项或合并冲突。先回去把 Task 16 组那一步（在 `pages.json:39` 的 project-overview 项之后插入两项，每项显式写 `"navigationStyle": "custom"`）执行完，再回到本任务。

- [ ] **Step 3b: 页面样式 project-home.scss**

新建 `frontend/src/pages/project-home/project-home.scss`：

```scss
/* 项目概览页容器样式。子组件各自的样式在各自 scoped 块里，这里只管容器。
   外壳浅色红线：白 / #F8F9FA 底 + 森林绿 #1A5336 + mint #5BD197 点缀，不做深色 chrome。 */
$color-primary: #1A5336;
$color-primary-light: #2D7A52;
$color-accent: #5BD197;
$color-text-main: #2C3338;
$color-text-light: #6C757D;
$color-border: #E9ECEF;
$bg-pale: #F8F9FA;
$bg-white: #FFFFFF;

.page-project-home {
  min-height: 100vh;
  box-sizing: border-box;
  background: $bg-pale;
  color: $color-text-main;
}

.home-topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 16px;
  height: 52px;
  padding: 0 24px;
  background: $bg-white;
  border-bottom: 1px solid $color-border;
}

/* btn-project-list / btn-workbench 是 e2e 锚点类名，样式挂在 home-back / home-enter 上 */
.home-back {
  font-size: 13px;
  color: $color-text-light;
  cursor: pointer;
}

.home-back:hover {
  color: $color-primary;
}

.home-topbar-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: $color-text-main;
}

.home-enter {
  flex: none;
  padding: 6px 16px;
  border-radius: 4px;
  background: $color-primary;
  color: $bg-white;
  font-size: 13px;
  cursor: pointer;
}

.home-enter:hover {
  background: $color-primary-light;
}

.home-scroll {
  padding: 24px;
}

/* 一页纸卷轴：单列纵向 */
.home-column {
  max-width: 880px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.home-section {
  background: $bg-white;
  border: 1px solid $color-border;
  border-radius: 6px;
  padding: 16px 18px;
}

.home-section-title {
  display: block;
  margin-bottom: 12px;
  padding: 0 0 8px 8px;
  border-left: 3px solid $color-accent;
  border-bottom: 1px solid $color-border;
  font-size: 13px;
  font-weight: 600;
  color: $color-primary;
}

@media (max-width: 720px) {
  .home-topbar {
    padding: 0 12px;
    gap: 10px;
  }

  .home-scroll {
    padding: 16px 12px;
  }
}
```

- [ ] **Step 3c: 页面 project-home.vue**

新建 `frontend/src/pages/project-home/project-home.vue`：

```vue
<template>
  <view class="page-project-home">
    <view class="home-topbar">
      <text class="home-back btn-project-list" @tap="goProjectList">返回项目列表</text>
      <text class="home-topbar-title">项目概览</text>
      <view class="home-enter btn-workbench" @tap="goWorkbench">进入工作台</view>
    </view>

    <view class="home-scroll">
      <view class="home-column">
        <ProfileHeader
          :project-id="projectId"
          :project-name="projectName"
          :fields="profileFields"
          :can-edit="canEdit"
          @save="onProfileSave"
        />

        <OverviewStatsBar :stats="stats" :loading="statsLoading" />

        <view class="home-section">
          <text class="home-section-title">动态</text>
          <ActivityFeed
            :versions="versions"
            :background-runs="backgroundRuns"
            :loading="activityLoading"
            :unavailable="activityUnavailable"
          />
        </view>

        <view class="home-section">
          <text class="home-section-title">日程与任务</text>
          <TaskSchedule :tasks="tasks" :loading="tasksLoading" />
        </view>

        <view class="home-section">
          <text class="home-section-title">AI 对话</text>
          <ConversationList
            :conversations="conversations"
            :loading="conversationsLoading"
            :has-more="!!nextBefore"
            @open="onOpenConversation"
            @load-more="onLoadMoreConversations"
          />
        </view>
      </view>
    </view>
  </view>
</template>

<script>
// 项目概览页（产品语言里的「项目概览页」）。注意与 pages/project-overview 区分：
// 后者在代码里是**工作台**，同名不同物。
//
// 本页只做三件事：取数、按卷轴顺序排五个子组件、把子组件事件转成导航或写入。
//
// 轮询纪律：只在 onLoad 与 onShow 各刷一次，A 期不起任何定时器；
// 并且绝不调 /version/status —— 它在 enabled 时会一路跑两次 git add，
// 工作台已有 7 处触发点在喂同一份状态。
import ProfileHeader from '@/components/project-home/ProfileHeader.vue'
import OverviewStatsBar from '@/components/project-home/OverviewStatsBar.vue'
import ActivityFeed from '@/components/project-home/ActivityFeed.vue'
import TaskSchedule from '@/components/project-home/TaskSchedule.vue'
import ConversationList from '@/components/project-home/ConversationList.vue'
import {
  getMyProjects,
  getProjectOverviewStats,
  getProjectProfile,
  saveProjectProfileField,
  getProjectConversations,
  getProjectTasks,
  getVersionTimeline,
} from '@/services/api.js'
import { recordProjectVisit } from '@/utils/recentProjects.js'
import { canEditProfile } from '@/utils/projectHomeFormat.js'

export default {
  name: 'ProjectHome',
  components: { ProfileHeader, OverviewStatsBar, ActivityFeed, TaskSchedule, ConversationList },
  data() {
    return {
      projectId: 0,
      openFileId: '',
      projectName: '',
      canEdit: false,
      profileFields: [],
      stats: {},
      statsLoading: true,
      versions: [],
      activityLoading: true,
      activityUnavailable: false,
      tasks: [],
      tasksLoading: true,
      conversations: [],
      conversationsLoading: true,
      nextBefore: null,
      nextBeforeId: null,
      firstShowDone: false,
    }
  },
  computed: {
    backgroundRuns() {
      return Array.isArray(this.stats.backgroundRuns) ? this.stats.backgroundRuns : []
    },
  },
  onLoad(query) {
    const id = Number((query && query.id) || 0)
    if (!id) {
      uni.showToast({ title: '缺少项目参数', icon: 'none' })
      uni.redirectTo({ url: '/pages/project-list/project-list' })
      return
    }
    this.projectId = id
    // openFileId 本页自己不消费，原样透传给工作台
    this.openFileId = query && query.openFileId ? String(query.openFileId) : ''
    recordProjectVisit(id)
    this.loadAll()
  },
  onShow() {
    if (typeof window !== 'undefined') window.__checkbaProjectHomeVm = this
    // onLoad 已经拉过一轮，第一次 onShow 跳过，之后每次切回刷一次
    if (!this.firstShowDone) {
      this.firstShowDone = true
      return
    }
    if (this.projectId) this.loadAll()
  },
  mounted() {
    if (typeof window !== 'undefined') window.__checkbaProjectHomeVm = this
  },
  beforeUnmount() {
    // 多实例守卫：只清指向自己的指针。用本页自己的指针名，复用工作台的
    // __checkbaActiveOverviewVm（project-overview.vue:2049-2052/:2231/:2288）
    // 会让工作台的全局事件被本页拦掉。
    if (typeof window !== 'undefined' && window.__checkbaProjectHomeVm === this) {
      window.__checkbaProjectHomeVm = null
    }
  },
  methods: {
    isActiveInstance() {
      if (typeof window === 'undefined') return true
      return !window.__checkbaProjectHomeVm || window.__checkbaProjectHomeVm === this
    },
    loadAll() {
      this.loadProjectCard()
      this.loadProfile()
      this.loadStats()
      this.loadActivity()
      this.loadTasks()
      this.loadConversations({ reset: true })
    },
    async loadProjectCard() {
      try {
        // GET /api/projects/my 返回**裸数组**（ProjectController 直接返 List<ProjectCardDTO>），
        // 不是信封。写 res.data 会恒空 —— admin.vue 就是这么坏掉的，别照抄。
        const res = await getMyProjects()
        const list = Array.isArray(res) ? res : []
        const card = list.find((p) => Number(p.id) === this.projectId)
        if (!this.isActiveInstance()) return
        this.projectName = (card && card.name) || ''
        this.canEdit = canEditProfile(card && card.myRole)
      } catch (e) {
        console.warn('[ProjectHome] 读取项目卡片失败', e)
      }
    },
    async loadProfile() {
      try {
        const res = await getProjectProfile(this.projectId)
        if (!this.isActiveInstance()) return
        this.profileFields = (res && res.data && res.data.fields) || []
      } catch (e) {
        console.warn('[ProjectHome] 读取项目档案失败', e)
      }
    },
    async loadStats() {
      this.statsLoading = true
      try {
        const res = await getProjectOverviewStats(this.projectId)
        if (!this.isActiveInstance()) return
        this.stats = (res && res.data) || {}
      } catch (e) {
        console.warn('[ProjectHome] 读取统计失败', e)
        this.stats = {}
      } finally {
        this.statsLoading = false
      }
    },
    async loadActivity() {
      this.activityLoading = true
      this.activityUnavailable = false
      try {
        const res = await getVersionTimeline(this.projectId, 5)
        if (!this.isActiveInstance()) return
        this.versions = (res && res.data && res.data.versions) || []
      } catch (e) {
        // VersionController.requireMember:562-564 显式拒 CLIENT，客户身份一定走到这里；
        // 后端若还没做「未开仓早退回空 versions」那条修复，未开启版本记录的项目也走这里。
        // 新建项目十有八九没开版本记录，一进概览页就弹错是最差的第一印象：
        // 落成引导态，不弹 toast。
        this.versions = []
        this.activityUnavailable = true
      } finally {
        this.activityLoading = false
      }
    },
    async loadTasks() {
      this.tasksLoading = true
      try {
        const res = await getProjectTasks(this.projectId)
        if (!this.isActiveInstance()) return
        this.tasks = (res && res.data && res.data.tasks) || []
      } catch (e) {
        console.warn('[ProjectHome] 读取任务失败', e)
        this.tasks = []
      } finally {
        this.tasksLoading = false
      }
    },
    async loadConversations(options) {
      const reset = !!(options && options.reset)
      this.conversationsLoading = true
      try {
        // 复合游标成对传：只带 before 会让服务端退化成严格小于，
        // 同一时刻落库的两个会话仍然会丢一条。
        const before = reset ? null : this.nextBefore
        const beforeId = reset ? null : this.nextBeforeId
        const res = await getProjectConversations(this.projectId, { limit: 20, before, beforeId })
        if (!this.isActiveInstance()) return
        const data = (res && res.data) || {}
        const page = data.conversations || []
        this.conversations = reset ? page : this.conversations.concat(page)
        this.nextBefore = data.nextBefore || null
        this.nextBeforeId = data.nextBeforeId || null
      } catch (e) {
        console.warn('[ProjectHome] 读取对话历史失败', e)
        if (reset) this.conversations = []
        this.nextBefore = null
        this.nextBeforeId = null
      } finally {
        this.conversationsLoading = false
      }
    },
    onLoadMoreConversations() {
      if (!this.nextBefore) {
        // 没有下一页了，第二维游标也不许留在手里
        this.nextBeforeId = null
        return
      }
      this.loadConversations()
    },
    async onProfileSave(payload) {
      try {
        const res = await saveProjectProfileField(this.projectId, payload.fieldKey, payload.value)
        const row = (res && res.data) || null
        if (!row) return
        this.profileFields = this.profileFields.map((f) => (f.fieldKey === row.fieldKey ? row : f))
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '保存失败', icon: 'none' })
      }
    },
    goWorkbench() {
      let url = `/pages/project-overview/project-overview?id=${this.projectId}`
      if (this.openFileId) url += `&openFileId=${encodeURIComponent(this.openFileId)}`
      uni.reLaunch({ url })
    },
    onOpenConversation(conversationId) {
      if (!conversationId) return
      // 把会话 id 带到工作台；工作台侧读 query 走既有 loadHistoryChat 的那一改
      // 由导航组落地。概览页绝不内嵌 ChatInterface —— loadHistoryChat 是完整切换
      // 会话，会在用户还没进工作台时就抢占当前会话。
      const url = `/pages/project-overview/project-overview?id=${this.projectId}`
        + `&conversationId=${encodeURIComponent(conversationId)}`
      uni.reLaunch({ url })
    },
    goProjectList() {
      // 列表与概览会被反复来回点：上一页就是列表时回退，否则 redirectTo 换掉本页。
      // 双向 navigateTo 会在页面栈里堆出多个列表实例（页面栈多实例地雷）。
      // getCurrentPages 的存在性判定照抄 components/FeedbackWidget.vue:414。
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
      const prev = pages.length >= 2 ? pages[pages.length - 2] : null
      if (prev && prev.route === 'pages/project-list/project-list') {
        uni.navigateBack({ delta: 1 })
      } else {
        uni.redirectTo({ url: '/pages/project-list/project-list' })
      }
    },
  },
}
</script>

<!-- 样式单一来源：./project-home.scss（与 project-overview.vue:4841 同形制） -->
<style lang="scss" scoped src="./project-home.scss"></style>
```

- [ ] **Step 3d: 顺手改 App.vue:14 那条已过时的埋点注释**

先确认还没被人改过：
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && grep -n 'pages.json 里的' src/App.vue
```
输出 `14:    // 只记页面路径枚举（pages.json 里的 11 个页面），query 参数不采集` 才需要改（若已是 13 就跳过本步）。把 `frontend/src/App.vue:13-14` 这两行：

```js
    // 埋点：页面路由唯一收口（全仓 50 处 navigateTo/reLaunch 直调，拦截器一处全覆盖）；
    // 只记页面路径枚举（pages.json 里的 11 个页面），query 参数不采集
```

换成：

```js
    // 埋点：页面路由唯一收口（全仓 50 处 navigateTo/reLaunch 直调，拦截器一处全覆盖）；
    // 只记页面路径枚举（pages.json 里的 13 个页面，含项目列表页 pages/project-list
    // 与项目概览页 pages/project-home；pages/project-overview 是工作台），query 参数不采集
```

（这条注释不改不会报错，但它是「pages.json 有几个页面」的唯一人读索引，上面的 `page.test.mjs` 有一条断言把它和 `pages.json` 的实际条数钉在一起。）

- [ ] **Step 4: 跑测试确认通过 + emit 契约静态检查**

Run:
```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:project-home && npm run check:emits
```
Expected: `# fail 0`；`check:emits` 输出 `✓ emit/绑定契约检查通过（扫描 74 个 .vue）`（改动前基线是 69 个，本组新增 5 个组件；关键是仍然是 `✓` 而不是死绑定报错——`@save` / `@open` / `@load-more` 三个绑定都能在子组件的 `emits` 数组里找到）。

- [ ] **Step 5: 真 UI 走查（prop 名写错是 check:emits 拦不住的盲区，必须真点一遍）**

后端五个端点已合并、桌面后端跑在 9696 的前提下，起 dev:h5：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npx uni --port 5174
```

浏览器打开（把 `1` 换成一个真实项目 id）：

```
http://127.0.0.1:5174/#/pages/project-home/project-home?id=1
```

逐条确认，任何一条不成立都回去改代码再跑一遍 Step 4：
1. 五个区块都渲染出内容，没有无样式裸框、没有 `undefined` / `NaN` / `[object Object]`。
2. 统计条出现「N 个文件 / N 个文件夹 / N 位参与人 / N 个后台任务」，不出现「项目大小」「最近修改」。
3. 未开版本记录的新项目，动态块是「这份案卷还没有版本记录」而不是报错文案。
4. 点档案头「客户」那一格 → 变成行内输入框 → 输入后点顶栏标题「项目概览」让它 blur → 值留在页面上；刷新页面后仍在（说明落库了）。
5. 点「进入工作台」落到工作台且左侧资源管理器出得来；再从列表页点进概览页、点「返回项目列表」，确认没有在页面栈里堆出第二个列表页（连点五次列表↔概览，浏览器后退一次应回到概览之前的页面）。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && \
git add frontend/src/pages/project-home frontend/src/App.vue frontend/tests/project-home/page.test.mjs && \
git commit -m "feat(project-home): 概览页容器与卷轴布局"
```

> **本组（e2e 与领域文档）的公共前提，只写一次**
>
> - **执行顺序**：本组三个 e2e 任务（Task 32/33/34）**必须在前端两组全部完成之后**跑。它们依赖：两个新页已注册进 `frontend/src/pages.json`（列表页组负责）、`launch.vue:99` 与 `login.vue:290/:299/:392/:472` 与 `newproject/index.vue:176` 已改指项目列表页、`userprofile.vue` 的「我的项目」tab 已删。前置没做完时跑，红的原因会是「页面不存在」而不是断言本身，两者在输出里分不出来。
> - **本组是 `frontend/tests/app-e2e/run.mjs` 与四份文档（`docs/QA_JOURNEYS.md`、`.claude/agents/sidebar-shell.md`、`.claude/agents/ai-chat.md`、`CLAUDE.md`）的唯一 owner**。别的组不改这五个文件；本组也**不碰 `frontend/src/pages.json`**（归列表页组）。
> - **e2e 锚点契约（九个类名，前端两组已按此落地，本组断言只用这九个）**：
>
>   ```
>   .page-project-list      项目列表页根节点
>   .page-project-home      项目概览页根节点
>   .btn-project-list       概览页顶栏「返回项目列表」按钮
>   .btn-workbench          概览页顶栏「进入工作台」按钮
>   .overview-stats-bar     统计条组件根节点
>   .profile-header         档案头组件根节点
>   .activity-feed          动态组件根节点
>   .task-schedule          日程与任务组件根节点
>   .conversation-list      AI 对话组件根节点
>   ```
>
>   `.stats-bar` / `.home-enter` / `.home-back` / `.btn-enter-workbench` / `.topbar-back` 全部作废，本组一个都不用。
> - `.project-item-card` 随模板整块从 `userprofile.vue:111` 搬到 `project-list.vue`，类名不变——既有 e2e 断言可以复用，只换页面路由。
> - J9（版本记录）两段旅程本次一行不动：它 `page.goto` 直接进工作台，不经列表/概览。
> - `AI_E2E=0` 用于迭代期跳过付费 AI 那一腿；Task 39 的最后一次全量跑**不带**这个开关。
> - **本组只改测试与文档**，唯一的产品代码改动是 Task 38 的五处注释文字（零逻辑变化）。

---

### Task 32: e2e J1 解锁落点断言改指项目列表页，并把三个 project-* 路由收紧成全路径判定

**Files:**
- Modify: `frontend/tests/app-e2e/run.mjs:298-304`（J1「真试用码解锁」的落点断言）
- Modify: `frontend/tests/app-e2e/run.mjs:318-325`（J1「已解锁重启 → 直达上次项目」的 hash 断言）
- Test: `frontend/tests/app-e2e/run.mjs`（套件自身即测试，跑法见步骤）

**Interfaces:**
- Consumes: `frontend/src/pages/launch/launch.vue:97`（命中最近项目 → `uni.reLaunch('/pages/project-overview/project-overview?id=...')`，本计划**不改**）；`frontend/src/pages/launch/launch.vue:99`（无最近项目 → 已由列表页组改成 `'/pages/project-list/project-list'`）
- Produces: J1 两处 `page.waitForFunction` 断言接受新路由集合 `{wizard, project-list, project-overview}`；直达断言由裸子串收紧成全路径

- [ ] **Step 0: 起前置环境（两个终端，跑完 Task 32/33/34 都别关）**

终端 A —— worktree 内起 dev server（端口 5174，5173 常被别的 worktree 占）：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npx uni --port 5174
```

终端 B —— 确认桌面后端在 9696（本机打包版常驻即可），返回 JSON 即算通：

```bash
curl -s http://127.0.0.1:9696/api/auth/me | head -c 200; echo
```

注意：J1 会先 `POST /api/license/deactivate` 把后端打回未解锁起点，跑完停在试用版。若你的常驻后端原本是账户模式，套件会在报告里给一条 `warn`，需要事后手工重连账户。

- [ ] **Step 1: 跑套件，确认 J1 现在是红的**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && AI_E2E=0 npm run test:app-e2e
```

Expected（**必须先看到这条红**，看不到说明前端页面任务还没做完，回去先做）：

```
== J1 首启解锁门 ==
  ✓ launch 未解锁分流到 unlock 页
  ✓ 坏码走后端 400 内联报错
  [step-fail] 真试用码解锁（含粘贴态换行空格去除）: waiting for function failed: timeout 30000ms exceeded
```

红的机理：puppeteer 每次跑用全新临时 profile，localStorage 里没有 `checkba_last_project_id`，解锁后 `launch.vue` 走 `:99` 的兜底分支落到 `pages/project-list/project-list`，而 `run.mjs:302` 的断言集合里只有 `userprofile`。

- [ ] **Step 2: 改 J1 解锁落点断言（run.mjs:298-304）**

把这一整段：

```js
    // 解锁成功 → toast → reLaunch 回 launch 分流：向导未初始化去 wizard，
    // 已初始化直接进应用（长驻后端场景）
    await page.waitForFunction(() => {
      const h = location.hash
      return h.includes('pages/wizard/wizard') || h.includes('pages/userprofile/userprofile')
        || h.includes('pages/project-overview/project-overview')
    }, { timeout: 30000 })
```

换成：

```js
    // 解锁成功 → toast → reLaunch 回 launch 分流，三个合法落点：
    //  - 向导未初始化 → wizard
    //  - 已初始化 + 有最近项目 → 工作台（launch.vue:97，直达语义保留不变）
    //  - 已初始化 + 无最近项目 → 项目列表页（launch.vue:99，三级导航改造把这里
    //    由 userprofile 改成了 project-list；全新 puppeteer profile 走的正是这条）
    await page.waitForFunction(() => {
      const h = location.hash
      return h.includes('pages/wizard/wizard') || h.includes('pages/project-list/project-list')
        || h.includes('pages/project-overview/project-overview')
    }, { timeout: 30000 })
```

- [ ] **Step 3: 收紧「直达上次项目」的 hash 判定（run.mjs:318-325）**

把这一整段：

```js
  await step('已解锁重启 → 直达上次项目', async () => {
    // uni h5 getStorageSync 兼容裸字符串
    await page.evaluate((id) => localStorage.setItem('checkba_last_project_id', String(id)), QA.projectId)
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForFunction(() => location.hash.includes('project-overview'), { timeout: 30000 })
    await waitText('资源管理器', 20000)
  })
```

换成：

```js
  await step('已解锁重启 → 直达上次项目（工作台，不经概览页）', async () => {
    // uni h5 getStorageSync 兼容裸字符串
    await page.evaluate((id) => localStorage.setItem('checkba_last_project_id', String(id)), QA.projectId)
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 30000 })
    // 三级导航后 project-list / project-home / project-overview 三个路由并存，
    // 一律写全路径判定：裸 'project-overview' 目前确实不是另外两个的子串，但
    // 'project-' 前缀家族已经三个成员了，模糊匹配迟早撞上。
    // 断言落工作台而不是概览页 = 钉死「启动直达永远进工作台」这条产品决策
    // （spec §5.3：概览页不做启动落点，recentProjects 存储格式不扩）。
    await page.waitForFunction(
      () => location.hash.includes('pages/project-overview/project-overview'), { timeout: 30000 })
    await waitText('资源管理器', 20000)
  })
```

- [ ] **Step 4: 跑套件确认 J1 全绿**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && AI_E2E=0 npm run test:app-e2e
```

Expected：J1 六步全 `✓`，随后在 J2 处**硬崩**（不是 step-fail，是未捕获异常直接终止进程）：

```
  ✓ launch 未解锁分流到 unlock 页
  ✓ 坏码走后端 400 内联报错
  ✓ 真试用码解锁（含粘贴态换行空格去除）
  ✓ 向导页无 admin/123 口令提示（未初始化时）
  ✓ 已解锁重启 → 直达上次项目（工作台，不经概览页）
  ✓ project-overview 常驻试用版标识
== J2 个人中心 ==
node:internal/process/promises ... TimeoutError: Waiting for selector `.project-item-card` failed
```

J2 那个崩溃是**预期的**——`run.mjs:333-334` 是裸 `await`、不在 `step()` 包装里，抛出后没人接，整个进程带着非 0 退出码死掉。Task 33 修它。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add frontend/tests/app-e2e/run.mjs && git commit -m "$(cat <<'EOF'
test(app-e2e): J1 解锁落点接受项目列表页，路由判定写全路径

launch.vue 无最近项目时的兜底落点由 userprofile 改成 project-list，
J1 的落点断言集合跟着换；直达断言由裸子串 'project-overview' 收紧成
全路径，避免 project-list/project-home/project-overview 三个同前缀
路由并存后的误判。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 33: e2e J2 重写：项目列表页独立断言 + 个人中心四 tab（默认 tab 改成工作记录）

**Files:**
- Modify: `frontend/tests/app-e2e/run.mjs:331-343`（J2 整段，13 行换成一段新的）
- Test: `frontend/tests/app-e2e/run.mjs`

**Interfaces:**
- Consumes: `pages/project-list/project-list`（新页，根类名 `.page-project-list`；卡片 `.project-item-card` 由 `userprofile.vue:111` 原样搬来，类名不变）；`userprofile.vue:492` 的 `activeTab` 默认值 `'projects'` → `'work_log'`；`userprofile.vue:494` 的 `{ key: 'projects', label: '我的项目' }` 整行删除；tab 标签 DOM 是 `userprofile.vue:37-46` 的 `.nav-menu > .nav-item > .nav-text`
- Produces: J2 由「个人中心四 tab」扩成「项目列表页 + 个人中心四 tab」两段独立断言；不再靠点 tab 回到项目列表

> 沿用 Task 32 Step 0 起的那两个终端，不用重起。

- [ ] **Step 1: 跑套件，确认 J2 现在是硬崩（不是 step-fail）**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && AI_E2E=0 npm run test:app-e2e
```

Expected：J1 全绿后进程直接死在 J2，控制台最后几行形如

```
== J2 个人中心 ==
node:internal/process/promises:xxx
        triggerUncaughtException(err, true /* fromPromise */);
        ^
TimeoutError: Waiting for selector `.project-item-card` failed: Waiting failed: 20000ms exceeded
```

机理：`run.mjs:333` goto 到 userprofile，`:334` 等 `.project-item-card`——项目列表整块已搬走，这个选择器在 userprofile 上永远不出现；而这两行是**裸 await、没被 `step()` 包住**，异常无人接管，`finally` 里关掉浏览器后进程带非 0 退出码终止，J3 及之后全部不跑。所以这不是「一个步骤红了」，是「套件当场断」。

- [ ] **Step 2: 用新的 J2 整段替换 run.mjs:331-343**

把这一整段（从 `// ============ J2 个人中心四 tab ============` 到 `await mouseClickText('我的项目')`，共 13 行）：

```js
  // ============ J2 个人中心四 tab ============
  console.log('== J2 个人中心 ==')
  await page.goto(BASE + '/#/pages/userprofile/userprofile', { waitUntil: 'networkidle2' })
  await page.waitForSelector('.project-item-card', { timeout: 20000 })
  for (const tab of ['工作记录', '我的收藏', '我的代办', '设置']) {
    await step('tab ' + tab, async () => {
      await mouseClickText(tab)
      const t = await textOf()
      const m = t.match(/.{0,40}(undefined|NaN|\[object).{0,40}/)
      if (m) throw new Error('页面文本可疑: ' + m[0])
    })
  }
  await mouseClickText('我的项目')
```

换成：

```js
  // ============ J2 项目列表页 + 个人中心四 tab ============
  // 三级导航改造后「我的项目」不再是个人中心的一个 tab，而是独立页面
  // pages/project-list/project-list；个人中心的默认 tab 随之变成「工作记录」，
  // tabs 数组里已无 projects 项。这里拆成两段独立断言：
  //  ① 项目列表页自己能加载出卡片（J3 的起点，必须先立住）
  //  ② 个人中心剩下的四个 tab 仍能切、且默认 tab 不是空白页
  // 不要再用「点『我的项目』tab 回到列表」这条老路径——那个 tab 已经不存在，
  // mouseClickText 会抛「找不到文本」。
  console.log('== J2 项目列表页 + 个人中心 ==')

  await step('项目列表页加载出项目卡片', async () => {
    await page.goto(BASE + '/#/pages/project-list/project-list', { waitUntil: 'networkidle2' })
    await page.waitForSelector('.page-project-list', { timeout: 20000 })
    await page.waitForSelector('.project-item-card', { timeout: 20000 })
    await waitText(QA.project.slice(0, 8))
  })

  await step('项目列表页文案巡检', async () => {
    const t = await textOf()
    const m = t.match(/.{0,40}(undefined|NaN|\[object|服务器内部错误).{0,40}/)
    if (m) throw new Error('页面文本可疑: ' + m[0])
    // 空项目态与有项目态都必须渲染「从团队案件库取一份案卷」——它是协作的唯一
    // 入口，且 CollabDialog.vue:271 的邀请话术第 1 步就指着它。搬迁时漏掉
    // CloudAcceptDialog 的两个入口，这条断言会红。
    if (!t.includes('从团队案件库取一份案卷')) {
      throw new Error('项目列表页缺「从团队案件库取一份案卷」入口（CloudAcceptDialog 没搬全）')
    }
  })

  await shot('j2-project-list')

  await step('个人中心不再有「我的项目」tab', async () => {
    await page.goto(BASE + '/#/pages/userprofile/userprofile', { waitUntil: 'networkidle2' })
    await waitText('工作记录', 20000)
    // 只看 tab 栏本身（.nav-menu .nav-text），不看整页 innerText——
    // 页面别处出现「我的项目」四个字不该让这条断言误红。
    const labels = await page.evaluate(
      () => [...document.querySelectorAll('.nav-menu .nav-text')].map((e) => e.innerText.trim()))
    if (labels.includes('我的项目')) {
      throw new Error('个人中心仍有「我的项目」tab（userprofile.vue:494 那行没删）: ' + JSON.stringify(labels))
    }
    if (labels[0] !== '工作记录') {
      throw new Error('个人中心首个 tab 不是「工作记录」（userprofile.vue:492 默认值没改）: ' + JSON.stringify(labels))
    }
  })

  for (const tab of ['工作记录', '我的收藏', '我的代办', '设置']) {
    await step('tab ' + tab, async () => {
      await mouseClickText(tab)
      const t = await textOf()
      const m = t.match(/.{0,40}(undefined|NaN|\[object).{0,40}/)
      if (m) throw new Error('页面文本可疑: ' + m[0])
    })
  }
```

注意两条 uni @tap 驱动纪律（顶部注释 `run.mjs:25-31` 已列，这里照办）：tab 切换必须走 `mouseClickText`（真实鼠标坐标点击），`el.click()` 不触发 uni 的 @tap；页面异步渲染，`goto` 之后一律 `waitForSelector` / `waitText`，不用固定 `sleep`。

- [ ] **Step 3: 跑套件确认 J2 全绿**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && AI_E2E=0 npm run test:app-e2e
```

Expected：

```
== J2 项目列表页 + 个人中心 ==
  ✓ 项目列表页加载出项目卡片
  ✓ 项目列表页文案巡检
  ✓ 个人中心不再有「我的项目」tab
  ✓ tab 工作记录
  ✓ tab 我的收藏
  ✓ tab 我的代办
  ✓ tab 设置
== J3 进入项目 ==
  [step-fail] 点项目卡片进入: Waiting for selector `.project-item-card` failed
```

J3 那条红是**预期的**——J2 结束时停在个人中心，而 J3 还在原地等项目卡片。Task 34 修它。这次是 `step-fail` 不是硬崩（J3 的断言都在 `step()` 里），套件会继续往下跑完并以退出码 1 结束。

- [ ] **Step 4: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add frontend/tests/app-e2e/run.mjs && git commit -m "$(cat <<'EOF'
test(app-e2e): J2 拆成项目列表页与个人中心两段断言

项目列表从 userprofile 的 tab 搬成独立页后，旧 J2 的裸 await
waitForSelector('.project-item-card') 会让整个套件硬崩。改为：
先断言 project-list 页能加载卡片与协作入口，再断言个人中心
默认 tab 是工作记录、tab 栏里已无「我的项目」。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 34: e2e J3 重写：列表 → 概览页 → 工作台三级跳，含档案手填落库与不堆页面栈两条护栏

**Files:**
- Modify: `frontend/tests/app-e2e/run.mjs`（J3 整段：原 `// ============ J3 进入项目 ============` 到 `await shot('j3-project')`；Task 33 之后行号已右移，**用字符串锚点匹配，不要按行号定位**）
- Modify: `frontend/tests/app-e2e/run.mjs:5`（文件头旅程清单一行）
- Test: `frontend/tests/app-e2e/run.mjs`

**Interfaces:**
- Consumes: `pages/project-home/project-home?id={projectId}`（根 `.page-project-home`；五个子组件根 `.profile-header` / `.overview-stats-bar` / `.activity-feed` / `.task-schedule` / `.conversation-list`；顶部两按钮 `.btn-workbench` / `.btn-project-list`）；`project-list.vue` 的 `goToProject` → `uni.navigateTo('/pages/project-home/project-home?id=...')`；`project-home.vue` 的 `goProjectList()` 条件分流（上一页是 project-list 则 `navigateBack`，否则 `redirectTo`）；`ProfileHeader.vue` 的两个内部约定（未填态字面量「未填写」、行内输入框类名 `.profile-field-input`）
- Produces: J3 由「点卡片直接进工作台」改为七步三级跳，新增三条护栏：概览页未开版本记录时是中性空态而非报错、列表↔概览来回点不堆页面栈实例、手填档案落库为 `source='user'`

> 沿用 Task 32 Step 0 起的那两个终端。

- [ ] **Step 1: 跑套件，确认 J3 现在是红的**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && AI_E2E=0 npm run test:app-e2e
```

Expected：

```
== J3 进入项目 ==
  [step-fail] 点项目卡片进入: Waiting for selector `.project-item-card` failed: Waiting failed: 15000ms exceeded
```

（J2 结束时停在个人中心，J3 却在原地等项目卡片；即便手工先跳回列表页，`mouseClickSel('.project-item-card')` 之后落的也是 `project-home` 而不是 `project-overview`，`:353` 的断言照样超时。）

- [ ] **Step 2: 用新的 J3 整段替换旧 J3**

把这一整段（从 `// ============ J3 进入项目 ============` 到 `await shot('j3-project')`）：

```js
  // ============ J3 进入项目 ============
  console.log('== J3 进入项目 ==')
  await step('点项目卡片进入', async () => {
    await page.waitForSelector('.project-item-card', { timeout: 15000 })
    await waitText(QA.project.slice(0, 8))
    // 注意：卡片标题绑定 @tap.stop=startRename（点名字=重命名），进入项目要点
    // 卡片主体 —— UX 疑点已记录于 docs/QA_JOURNEYS.md
    await mouseClickSel('.project-item-card')
    await page.waitForFunction(() => location.hash.includes('project-overview'), { timeout: 15000 })
    await waitText('资源管理器', 20000)
  })
  await shot('j3-project')
```

换成：

```js
  // ============ J3 三级导航：项目列表页 → 项目概览页 → 工作台 ============
  // 术语（代码里同名不同物，别看错）：
  //   pages/project-overview/project-overview = 工作台（四列干活界面，路由不改名）
  //   pages/project-home/project-home         = 产品语言里的「项目概览页」（新增）
  //   pages/project-list/project-list         = 项目列表页（从 userprofile 搬出，新增）
  // 三个路由互不是子串，但同属 'project-' 前缀家族——一律写全路径判定。
  console.log('== J3 三级导航 ==')

  await step('列表页点卡片 → 项目概览页', async () => {
    await page.goto(BASE + '/#/pages/project-list/project-list', { waitUntil: 'networkidle2' })
    await page.waitForSelector('.project-item-card', { timeout: 15000 })
    await waitText(QA.project.slice(0, 8))
    // 卡片标题绑定 @tap.stop=startRename（点名字=重命名），进入要点卡片主体
    // —— UX 疑点已记录于 docs/QA_JOURNEYS.md
    await mouseClickSel('.project-item-card')
    await page.waitForFunction(
      () => location.hash.includes('pages/project-home/project-home'), { timeout: 15000 })
    await page.waitForSelector('.page-project-home', { timeout: 15000 })
  })

  await step('概览页五个区块齐全', async () => {
    // 组件根类名即 e2e 稳定锚点（仓里既有惯例：.cloud-bar / .adopt-dialog / .clip-panel）。
    // 只等 .page-project-home 是不够的——子组件挂载失败时容器照样在。
    for (const sel of ['.profile-header', '.overview-stats-bar', '.activity-feed',
      '.task-schedule', '.conversation-list']) {
      await page.waitForSelector(sel, { timeout: 15000 })
    }
    const t = await textOf()
    const m = t.match(/.{0,40}(undefined|NaN|\[object|服务器内部错误).{0,40}/)
    if (m) throw new Error('概览页文本可疑: ' + m[0])
  })

  await step('动态块不暴露版本记录的错误信封', async () => {
    // Task 5 已让 /version/timeline 在未开仓时早退返回 {code:0,data:{versions:[]}}，
    // 所以 QA 这个刚建的 BLANK 项目走的是 ActivityFeed 的「还没有动态」普通空态，
    // 而不是 unavailable 引导态（unavailable 此后只剩 CLIENT 一条路径）。
    // 这一条守的是「任何情况下都不许把版本记录的错误信封当通用错误暴露给用户」。
    const t = await textOf()
    if (t.includes('版本记录操作失败')) {
      throw new Error('概览页把 /version/timeline 的错误信封当通用错误暴露了')
    }
  })

  await shot('j3-project-home')

  await step('概览页「返回项目列表」不堆页面栈', async () => {
    await mouseClickSel('.btn-project-list')
    await page.waitForFunction(
      () => location.hash.includes('pages/project-list/project-list'), { timeout: 15000 })
    // uni h5 的页面栈在 DOM 里是并存的（navigateTo 压栈时旧页留在文档里只是隐藏），
    // 所以根节点计数就是页面栈实例数的直接证据。goProjectList() 的规则是
    // 「上一页是 project-list 就 navigateBack、否则 redirectTo」——两条路走对了
    // 这里恒为 1；写成无脑 navigateTo 会变成 2，且随来回次数线性增长。
    const n = await page.evaluate(() => document.querySelectorAll('.page-project-list').length)
    if (n !== 1) throw new Error('项目列表页实例数 = ' + n + '（页面栈堆叠，goProjectList 的分流规则没实现对）')
  })

  await step('再次进入概览页（来回点不堆栈）', async () => {
    await mouseClickSel('.project-item-card')
    await page.waitForFunction(
      () => location.hash.includes('pages/project-home/project-home'), { timeout: 15000 })
    const n = await page.evaluate(() => document.querySelectorAll('.page-project-home').length)
    if (n !== 1) throw new Error('概览页实例数 = ' + n + '（页面栈堆叠）')
  })

  await step('概览页手填档案「客户」→ 落库 source=user', async () => {
    // 档案头五个字段顺序固定 client → matterType → openedAt → nextStep → counterparty，
    // 未填态渲染字面量「未填写」（ProfileHeader.vue）。openedAt 有建档时间兜底，
    // 所以 nth=0 的「未填写」一定是 client（走行内 input，不是 matterType 那个 picker）。
    // 这两个串是与概览页组之间的缝隙：断言红了先
    //   grep -n "未填写\|profile-field-input" frontend/src/components/project-home/ProfileHeader.vue
    await mouseClickText('未填写')
    // uni-app h5 的 <input> 真实输入元素是内层 .uni-input-input
    await page.waitForSelector('.profile-field-input .uni-input-input', { timeout: 10000 })
    // uni useValueSync 的 triggerInput 是 100ms throttle：连打只有首字符进 v-model。
    // 停一拍再补一个空格，让最后一次 input 以完整值触发（后端 PUT 会 value.trim()，
    // 这个尾随空格不会落库）。
    await page.type('.profile-field-input .uni-input-input', 'QA客户公司')
    await sleep(250)
    await page.type('.profile-field-input .uni-input-input', ' ')
    await sleep(250)
    // 点统计条让输入框失焦 → @blur 提交。刻意点 .overview-stats-bar 这个
    // e2e 锚点而不是顶栏标题文案：类名是九个锚点契约的一部分，文案不是。
    await mouseClickSel('.overview-stats-bar')
    await waitText('QA客户公司', 15000)
    const res = await api('/api/projects/' + QA.projectId + '/profile')
    const fields = (res && res.data && res.data.fields) || []
    if (fields.length !== 5) throw new Error('档案字段不是恒 5 条: ' + fields.length)
    const client = fields.find((f) => f.fieldKey === 'client')
    if (!client || client.fieldValue !== 'QA客户公司' || client.source !== 'user') {
      throw new Error('档案未按 source=user 落库: ' + JSON.stringify(client))
    }
  })

  await step('概览页「进入工作台」→ 工作台', async () => {
    await mouseClickSel('.btn-workbench')
    await page.waitForFunction(
      () => location.hash.includes('pages/project-overview/project-overview'), { timeout: 15000 })
    await waitText('资源管理器', 20000)
  })
  await shot('j3-project')
```

四条驱动纪律，改这段时别退回去：两个新按钮都用 `mouseClickSel`（uni @tap 只认真实鼠标坐标）；每次跳转后先等路由 hash 再等选择器（页面异步渲染，固定 `sleep` 不可靠）；`.btn-workbench` 走的是 `reLaunch`，之后页面栈被清空，所以「返回列表」与「手填档案」两组断言必须排在它**之前**；手填那一步只截 `j3-project-home` 一张图（这张图在它之前已经截过），不新增截图名。

- [ ] **Step 3: 更新文件头的旅程清单（run.mjs:5）**

把：

```js
// 走完核心用户旅程：个人中心四 tab、进入项目、上传文件（含 >5MB 分片路径回归）、
```

换成：

```js
// 走完核心用户旅程：项目列表页、个人中心四 tab、三级导航（列表→概览页→工作台，
// 含概览页档案手填落库）、上传文件（含 >5MB 分片路径回归）、
```

- [ ] **Step 4: 跑套件确认全绿**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && AI_E2E=0 npm run test:app-e2e ; echo "exit=$?"
```

Expected：

```
== J3 三级导航 ==
  ✓ 列表页点卡片 → 项目概览页
  ✓ 概览页五个区块齐全
  ✓ 动态块不暴露版本记录的错误信封
  ✓ 概览页「返回项目列表」不堆页面栈
  ✓ 再次进入概览页（来回点不堆栈）
  ✓ 概览页手填档案「客户」→ 落库 source=user
  ✓ 概览页「进入工作台」→ 工作台
```

且末尾 `===== 结果 =====` 一行里 `0 失败`、`exit=0`。J9 版本记录那两段不受影响——它 `page.goto` 直接进工作台，不经列表/概览。

- [ ] **Step 5: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add frontend/tests/app-e2e/run.mjs && git commit -m "$(cat <<'EOF'
test(app-e2e): J3 重写为列表→概览页→工作台三级跳

新增三条护栏：① 未开版本记录时概览页动态块必须是中性空态，
不许把 /version/timeline 的错误信封当通用错误暴露；② 列表与
概览来回点时根节点计数恒为 1，钉死 goProjectList 的
navigateBack/redirectTo 分流规则，防页面栈多实例；③ 行内手填
档案「客户」后回读 GET /profile，断言恒 5 条且该字段 source=user。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 35: docs/QA_JOURNEYS.md 同步三级导航旅程，顺手清掉两条已失效的登记

**Files:**
- Modify: `docs/QA_JOURNEYS.md:12-21`（「账号与导航」整节，标题 :12 + 表 :14-21）
- Modify: `docs/QA_JOURNEYS.md:94`（运行环境备忘里已失效的 qa_bot 一行）
- Test: `grep` 核对（命令见步骤）

**Interfaces:**
- Consumes: Task 32/33/34 落地后 `frontend/tests/app-e2e/run.mjs` 的实际旅程结构
- Produces: 「账号与导航」表反映三级导航与本 PR 的真实覆盖面。**注意 `docs/` 在 .gitignore 的 `:56`，提交必须 `git add -f`。**

- [ ] **Step 1: 跑核对命令，确认文档现在是旧的**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && grep -n "项目列表页\|项目概览页\|qa_bot\|登录页真实打字登录" docs/QA_JOURNEYS.md
```

Expected（两条命中的都是**该被改掉**的旧登记，两条该有的新词一条都没有）：

```
16:| 登录页真实打字登录 → 跳转 | A ✅ | |
94:- 后端：本机打包版常驻 9696 即可；套件自注册 `qa_bot_<ts>` 账号并在结束时删除项目
```

两条为什么是错的：`run.mjs:243-245` 白纸黑字写着「旧 J1『登录页真实打字登录』已随桌面去登录整体移除」；`run.mjs:113-118` 写着「PR-A 去登录后 desktop profile 开 security.local-mode……注册/登录接口已不在桌面链路上，QA.sid 恒为 null」，套件早已不注册任何 `qa_bot_*` 账号。

- [ ] **Step 2: 替换「账号与导航」整节（:12-21）**

把：

```markdown
## 账号与导航

| 旅程 | 覆盖 | 备注 |
|---|---|---|
| 登录页真实打字登录 → 跳转 | A ✅ | |
| 注册新账号（UI） | ❌ | API 注册已覆盖；UI 表单未走 |
| 个人中心四 tab（工作记录/收藏/代办/设置） | A ✅ | 文案 undefined/NaN 巡检 |
| 项目卡片进入项目 | A ✅ | ⚠️ UX 疑点：点**项目名**=重命名（@tap.stop），点卡片主体才进入；新用户易困惑 |
| 新建项目向导（各项目类型/必填校验） | A 仅页面加载 | 表单提交未走 |
| 项目删除/重命名 | ❌ | |
```

换成：

```markdown
## 账号与导航

> **术语**（代码里同名不同物）：`pages/project-overview/project-overview` = **工作台**；
> `pages/project-home/project-home` = 产品语言里的**项目概览页**（新增）；
> `pages/project-list/project-list` = **项目列表页**（2026-08 从个人中心搬出，新增）。

| 旅程 | 覆盖 | 备注 |
|---|---|---|
| 首启解锁门（试用码真实打字 → 分流） | A ✅ | 三个合法落点：wizard / 项目列表页 / 工作台 |
| 已解锁重启 → 直达上次项目 | A ✅ | 直达**永远进工作台**，不经概览页（spec §5.3 决策） |
| 登录页真实打字登录 → 跳转 | ❌ | 桌面已去登录，旧 J1 随之移除；login.vue 只剩浏览器访问团队服务器的场景，不在本套件覆盖面 |
| 注册新账号（UI） | ❌ | 桌面 local-mode 免登，注册接口已不在桌面链路上 |
| 项目列表页加载（卡片 + 协作入口） | A ✅ | 断言 `.page-project-list` / `.project-item-card` / 「从团队案件库取一份案卷」文案 |
| 个人中心四 tab（工作记录/收藏/代办/设置） | A ✅ | 文案 undefined/NaN 巡检；另断言 tab 栏已无「我的项目」且默认 tab 是「工作记录」 |
| 三级导航：列表 → 概览页 → 工作台 | A ✅ | J3 七步；另断言「返回项目列表」与来回点不堆页面栈实例 |
| 项目卡片进入项目 | A ✅ | ⚠️ UX 疑点：点**项目名**=重命名（@tap.stop），点卡片主体才进入；新用户易困惑。**目的地已改为项目概览页**，不再直进工作台 |
| 概览页五区块渲染（档案头/统计条/动态/日程/对话） | A ✅ 挂载 | 只验五个组件根挂上、无 undefined/NaN；各区块内容正确性未走 |
| 概览页动态块在未开版本记录时的空态 | A ✅ | 新建项目必经路径：未开仓已由后端早退回空数组，动态块走普通空态，页面不许暴露版本记录的错误信封 |
| 概览页档案手填（PUT /profile/{fieldKey}） | A ✅ | J3 第 6 步；行内 input，点统计条 blur 提交；回读 GET /profile 断言恒 5 条且该字段 source=user |
| 概览页 AI 对话历史点开 → 工作台并打开该会话 | ❌ | 链路已完整（概览页带 conversationId + 工作台 onLoad 走既有 loadHistoryChat），但 QA 项目零 AI 历史，本套件未走 |
| 新建项目向导（各项目类型/必填校验） | A 仅页面加载 | 表单提交未走 |
| 项目删除/重命名 | ❌ | 搬到项目列表页后仍未覆盖 |
```

- [ ] **Step 3: 修运行环境备忘里那条已失效的 qa_bot 说明（:94）**

把：

```markdown
- 后端：本机打包版常驻 9696 即可；套件自注册 `qa_bot_<ts>` 账号并在结束时删除项目
```

换成：

```markdown
- 后端：本机打包版常驻 9696 即可；桌面 local-mode 免登（任何请求都解析为本机用户），
  套件**不再注册任何账号**（旧的 `qa_bot_<ts>` 已随去登录消亡），只自建一个 BLANK 项目
  并在结束时删掉
```

- [ ] **Step 4: 跑核对命令确认改到位**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && grep -n "三级导航：列表 → 概览页 → 工作台\|概览页档案手填\|不再注册任何账号\|pages/project-home/project-home" docs/QA_JOURNEYS.md
```

Expected：四条各命中一行（行号随编辑漂移，不用对号）：

```
14:> `pages/project-home/project-home` = 产品语言里的**项目概览页**（新增）；
26:| 三级导航：列表 → 概览页 → 工作台 | A ✅ | J3 七步；另断言「返回项目列表」与来回点不堆页面栈实例 |
30:| 概览页档案手填（PUT /profile/{fieldKey}） | A ✅ | J3 第 6 步；行内 input，点统计条 blur 提交；回读 GET /profile 断言恒 5 条且该字段 source=user |
99:  套件**不再注册任何账号**（旧的 `qa_bot_<ts>` 已随去登录消亡），只自建一个 BLANK 项目
```

再确认 `qa_bot` 只剩新写的那一处（旧登记已清）：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && grep -c "qa_bot" docs/QA_JOURNEYS.md
```

Expected：`1`。

- [ ] **Step 5: 提交（docs/ 在 .gitignore:56 里，必须 -f）**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add -f docs/QA_JOURNEYS.md && git commit -m "$(cat <<'EOF'
docs(qa): QA_JOURNEYS 同步三级导航旅程

账号与导航整节按 A 期实际覆盖面重写，加术语提示；概览页五区块、
未开版本记录空态、档案手填三条登记为 A 已覆盖，AI 对话点开登记为
链路已完整但本套件未走。顺手清掉两条已失效登记（登录页打字登录
随桌面去登录移除、套件早已不注册 qa_bot 账号）。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 36: sidebar-shell.md 补两个新页 + 导航流总规则 + 术语表，并修 :10/:12/:78-79 的行号腐烂；CLAUDE.md 加术语

**Files:**
- Modify: `.claude/agents/sidebar-shell.md:10`（内部地图标题行数）
- Modify: `.claude/agents/sidebar-shell.md:12`（三大块行号区间）
- Modify: `.claude/agents/sidebar-shell.md:47-51`（页面路由与导航流）
- Modify: `.claude/agents/sidebar-shell.md:65`（页面栈地雷，加新页指针名）
- Modify: `.claude/agents/sidebar-shell.md:78-79`（FileTree 与各页面行数）
- Modify: `CLAUDE.md`（「## 全局约定」列表末尾追加一条）
- Test: `grep` 核对（命令见步骤）

**Interfaces:**
- Consumes: 已核实的真实行号（下面逐条给出，**不要从 spec 或契约里抄旧数字，那两份自己也漂了**）
- Produces: sidebar-shell.md 反映三级导航与两个新页；CLAUDE.md 带术语；三处行号腐烂修正

> **跨任务依赖，先读**：列表页组 Task 23 的 `npm run check:nav` 里保留了两条校验本任务产出的断言——「CLAUDE.md 写下了三个同名不同物的术语」（要求 CLAUDE.md 同时含 `pages/project-home/project-home`、`pages/project-list/project-list`、`工作台` 三个串）与「sidebar-shell.md 的页面路由一节收录了两个新页」（要求含 `project-list` 与 `project-home`）。按编号顺序执行时这两条会在 Task 23 处暂红，直到本任务完成才转绿；也可以把本任务提前到 Task 23 之前跑。下面的文案已保证这五个串都在。

- [ ] **Step 1: 跑核对命令，亲眼看到腐烂的数字**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && sed -n '10p;12p;78p;79p' .claude/agents/sidebar-shell.md && echo '--- 真实值（2026-08-08 实测）---' && wc -l frontend/src/pages/project-overview/project-overview.vue frontend/src/pages/project-overview/project-overview.scss frontend/src/components/FileTree.vue frontend/src/pages/login/login.vue frontend/src/pages/newproject/index.vue frontend/src/pages/wizard/wizard.vue frontend/src/pages/variable-library/variable-library.vue frontend/src/pages/admin/admin.vue frontend/src/pages/plugin-market/plugin-market.vue && grep -n '^</template>\|^<script>\|^</script>\|^<style' frontend/src/pages/project-overview/project-overview.vue
```

Expected：文档说 `project-overview.vue（10638 行）`、`script :1215-6729`、`FileTree.vue（5195 行）`、`userprofile.vue(2158)`、`admin.vue(1648+)`、`plugin-market.vue(766)`；而实测是

```
    4841 frontend/src/pages/project-overview/project-overview.vue
    4298 frontend/src/pages/project-overview/project-overview.scss
    5225 frontend/src/components/FileTree.vue
     929 frontend/src/pages/login/login.vue
     679 frontend/src/pages/newproject/index.vue
    1007 frontend/src/pages/wizard/wizard.vue
     543 frontend/src/pages/variable-library/variable-library.vue
    4077 frontend/src/pages/admin/admin.vue
      22 frontend/src/pages/plugin-market/plugin-market.vue
1404:</template>
1406:<script>
4838:</script>
4841:<style lang="scss" scoped src="./project-overview.scss"></style>
```

（`project-overview.vue` 从 10638 掉到 4841 是因为样式已外置到 `.scss`、九个逻辑模块已拆成同目录 `.js`；`plugin-market.vue` 从 766 掉到 22 是因为它已变成薄壳页——文档 `:38` 自己都写了「薄壳页 + `<MarketPane :standalone="true">`」，只有 `:79` 的行数没跟上。）

- [ ] **Step 2: 修 :10 的内部地图头，并给下面那批陈旧行号挂警告**

把 `:10`：

```markdown
## project-overview.vue 内部地图（10638 行，主战场）
```

换成：

```markdown
## project-overview.vue 内部地图（4841 行，主战场）

> **下面这份 :xxx 行号地图早于「project-overview 分阶段拆分」，多数已漂（2026-08-08 实测：
> project-header 由 :3 → :4、left-rail :172 → :207、sidebar-left :354 → :421、
> workbench :595 → :650、data() :1314 → :1535、computed :1557 → :1784、
> methods :2261 → :2611）。结构描述仍然准确，**引用任何具体行号前自己 grep 一遍**。
> 已实测的锚点：`switchToProject` :2683（内部 reLaunch 在 :2687）、`goAllProjects` :2689、
> `goToUserProfile` :3740、`goToSystemSettings` :3743、`isActiveOverviewInstance` :3354、
> `beforeUnmount` :2049、`onLoad` :2161、`onShow` :2227、`mounted` :2288、
> 顶栏切换器的 `.switcher-all`「全部项目…」:38。`toggleLeftPane` **已不在本文件里**，
> 拆到同目录 `panelSwitching.js:7`。
```

- [ ] **Step 3: 修 :12 的三大块区间**

把（原 `:12`，Step 2 之后会往下挪几行，**用字符串定位**）：

```markdown
三大块：template :1-1213 / script :1215-6729 / style(scss scoped) :6731-10638。
```

换成：

```markdown
三大块：template :1-1404 / script :1406-4838 / style **已外置**——`:4841` 只有一行
`<style lang="scss" scoped src="./project-overview.scss">`，样式实体在同目录
`project-overview.scss`（4298 行）。逻辑也已拆出九个同目录 .js 模块：
`agentClientActions` / `clipboardBridge` / `fileOpenTabs` / `librePool` / `ocrActions` /
`ocrCapture` / `panelSwitching` / `stagingArea` / `tabDragSplit`（都以 mixin 形式并进页面）。
```

- [ ] **Step 4: 重写「页面路由」一节的 :47-51**

把这四行（`## 页面路由...` 标题行 + 下面三行正文，中间空一行）：

```markdown
## 页面路由（frontend/src/pages.json，全部 navigationStyle: custom）

launch（**启动页**）/ unlock / identity / login / newproject / project-overview / variable-library / userprofile / admin / plugin-market / wizard。
导航流：launch reLaunch→login（非桌面）|unlock（未解锁）|identity（本机工作区待选定）|wizard（未初始化）|project-overview|userprofile；unlock/identity 完成后一律 reLaunch 回 launch 重跑分流，不自己跳工作区；overview navigateTo userprofile/admin；admin 内「插件广场」是页内切换（plugin-market 独立页仅直链保留）；newproject reLaunch→overview；退出 reLaunch login。
**启动链只用 reLaunch，不用 navigateTo**——分流页不该留在页面栈里。
```

换成：

```markdown
## 页面路由（frontend/src/pages.json，全部 navigationStyle: custom）

launch（**启动页**）/ unlock / identity / login / newproject / **project-list** / **project-home** / project-overview / variable-library / userprofile / admin / plugin-market / wizard。

**术语表（同名不同物，全篇按此读）**：

| 术语 | 指代 | 路由 |
|---|---|---|
| **工作台** | 现有四列布局的干活界面 | `pages/project-overview/project-overview`（**刻意不改名**，改名要动 9 处硬编码 URL + 九个模块文件 + e2e + 埋点 path 维度） |
| **项目列表页** | 2026-08 从个人中心 projects tab 搬出的独立页 | `pages/project-list/project-list` |
| **项目概览页** | 一页纸卷轴（档案头/统计条/动态/日程/AI 对话） | `pages/project-home/project-home` |

代价是「project-overview」在代码里指工作台、在产品语言里指项目概览页。写代码时以路由为准，写文案时以术语表为准。

**三级导航（2026-08）**：项目列表页 → 项目概览页 → 工作台。总规则两条——
① **五条「直达工作台」的出口一条都不改**（启动直达 `launch.vue:97`、浏览器会话恢复 `login.vue:297`、应用菜单最近打开 `App.vue:45`、打开本地文件夹/文件 `ideOpen.js:22`、顶栏最近项目切换器 `project-overview.vue:2687`）——这些语境的用户意图是「立刻干活」，强插概览页只是多一跳；
② **所有「去我的项目」的落点统一到项目列表页**（`launch.vue:99` 兜底、`login.vue:290` CLIENT 分支 / `:299` 会话恢复兜底 / `:392` 普通登录 / `:472` 注册成功、`newproject/index.vue:176` 返回、工作台 `goAllProjects`:2689）。

导航流：launch reLaunch→login（非桌面）|unlock（未解锁）|identity（本机工作区待选定）|wizard（未初始化）|project-overview（有最近项目）|**project-list**（无最近项目）；unlock/identity 完成后一律 reLaunch 回 launch 重跑分流，不自己跳工作区；**project-list navigateTo→project-home**（`goToProject`，`onCloudAccepted` 复用同一方法）；**project-home reLaunch→project-overview**（顶部「进入工作台」，`openFileId` 原样透传；点 AI 对话历史时另带 `conversationId`，**工作台侧已消费**——`onLoad` 读到 `conversationId` 后调既有 `loadHistoryChat({ conversationId })`（`project-overview.vue:4729`），它内部要 `$refs.chatInterface`，所以只能在 mounted 且 AI 面板已渲染之后调）；**project-home →project-list 条件分流**——上一页 route 是 `pages/project-list/project-list` 就 `navigateBack({delta:1})`，否则 `redirectTo`（**不能无脑 navigateTo**：列表↔概览会被反复来回点，双向 navigateTo 堆实例，而纯 redirectTo 在「列表→概览→列表」链上同样造第二个列表实例）；**project-overview reLaunch→project-list**（顶栏切换器里的「全部项目…」`.switcher-all`:38，工作台参与的跳转一律 reLaunch）；**project-overview reLaunch→project-home**（在 `.switcher-all` **之前**插的 `.switcher-home`「项目概览」，是工作台通往概览页的唯一入口）；overview navigateTo userprofile/admin（**这两条保持 navigateTo 不动**——它们依赖页面栈保留实例以便 onShow 回流刷新，见 `project-overview.vue:2227` 的 onShow 重新接管全局处理器）；admin 内「插件广场」是页内切换（plugin-market 独立页仅直链保留）；newproject reLaunch→overview；退出 reLaunch login。
**启动链只用 reLaunch，不用 navigateTo**——分流页不该留在页面栈里。
**新页 pages.json 注册必须逐条显式写 `navigationStyle: custom`**：globalStyle 里没有这一项（只有 navigationBarTextStyle / TitleText / BackgroundColor / backgroundColor），漏写会得到一个系统导航栏，与全应用自绘顶栏形制冲突。
**个人中心配套三改，漏一条就静默出错**：`userprofile.vue:492` 的 `activeTab` 默认值改 `'work_log'`、`:494` 的 `{ key: 'projects', label: '我的项目' }` 整行删、**`:585` 的 `this.loadProjects()` 换成 `this.loadActivityLogs()`（不是删除）**——工作记录 tab 是懒加载的，唯一触发点是 `switchTab:734`（`loadActivityLogs` 定义在 `:737`），直接删掉就得到一个默认打开却永远空白的 tab。
```

- [ ] **Step 5: 给 :65 的页面栈地雷补上新页的指针名**

在 `:65` 那段末尾（`**外壳里新增任何全局订阅必须套用此模式**（PR#148/#151）。` 之后）追加：

```markdown
**新页同样成立**：`project-home.vue` 套同一套守卫，但**必须用自己的指针名** `window.__checkbaProjectHomeVm`——复用工作台的 `__checkbaActiveOverviewVm`（:2051/:2231/:2292 登记与清理，:3354 判活跃）会让工作台的全局事件被概览页拦掉。`project-home` 的轮询纪律：只在 onLoad 与 onShow 各刷一次，不起定时器；**绝不调 `getVersionStatus` / `/version/status`**（enabled 时会一路走到 `ProjectRepoService` 跑两次 `git add "."`，工作台已有 ≥7 处触发点在喂同一份状态，概览页再打第三次是纯浪费且会与工作台争 per-project 锁）。要「最近修改」时间取 `/version/timeline` 最新一条的 when。
```

- [ ] **Step 6: 修 :78-79 的行数腐烂并登记两个新页**

把：

```markdown
- `frontend/src/components/FileTree.vue`（5195 行）— 左栏文件树。
- 各页面：login.vue(777)、newproject/index.vue(660)、wizard.vue(593，重跑语义见 PR#134)、userprofile.vue(2158)、variable-library.vue(543)、admin.vue(1648+，含插件广场入口与「记忆同步」面板——nav key `memory`、desktopOnly，配置记忆 Git 远端，见 version-control.md)、plugin-market.vue(766)。
```

换成：

```markdown
- `frontend/src/components/FileTree.vue`（5225 行）— 左栏文件树。
- 各页面（行数 2026-08-08 实测）：login.vue(929)、newproject/index.vue(679)、wizard.vue(1007，重跑语义见 PR#134)、userprofile.vue（项目 tab 已搬出，只剩工作记录/收藏/代办/设置四 tab，行数随之变动、不再登记具体数字）、variable-library.vue(543)、admin.vue(4077，含插件广场入口与「记忆同步」面板——nav key `memory`、desktopOnly，配置记忆 Git 远端，见 version-control.md)、plugin-market.vue(22，**已是薄壳页**，实体在 `MarketPane`)。
- **项目列表页** `frontend/src/pages/project-list/project-list.vue` + 同目录 `project-list.scss`（样式 `@import` 引入，照 project-overview.vue + .scss 的既有形制）。整块搬自 `userprofile.vue` 的 projects tab，卡片类名 `.project-item-card` 保持不变（e2e 锚点）；页面根 `.page-project-list`。承载 `InviteMemberDialog` 与 `CloudAcceptDialog`（**这两个必须一起搬**，`CloudAcceptDialog` 的两个入口是协作唯一入口，`CollabDialog.vue:271` 的邀请话术还指着它）。CLIENT 隐藏「+ 新建项目」「从团队案件库取一份案卷」与卡片上的删除/重命名/邀请。角色文案唯一来源是 `config/memberRoles.js`（搬迁时把原来硬编码的 `getRoleLabel` 映射表换掉）。**不要搬**「进行中/已完成」那两张统计卡——它们是写死的字面量 0，Project 实体根本没有状态字段。
- **项目概览页** `frontend/src/pages/project-home/project-home.vue` + `project-home.scss`；五个子组件在 `frontend/src/components/project-home/`：`ProfileHeader` / `OverviewStatsBar` / `ActivityFeed` / `TaskSchedule` / `ConversationList`。**九个 e2e 稳定锚点类名**：页面根 `.page-project-home`、项目列表页根 `.page-project-list`、顶部两按钮 `.btn-workbench` / `.btn-project-list`、五个组件根 `.overview-stats-bar` / `.profile-header` / `.activity-feed` / `.task-schedule` / `.conversation-list`——**改这九个名字要同步改 `frontend/tests/app-e2e/run.mjs` 的 J2/J3 段**。档案编辑刻意走行内 input、删除确认走 `uni.showModal`，因此两个新页与五个新组件**都不需要自带 awd-\* 样式副本**（awd-\* 没有集中定义，改成弹窗就必须自带一份 scoped 副本，否则渲染成无样式裸框）。
```

- [ ] **Step 7: CLAUDE.md 的「## 全局约定」列表末尾追加一条**

在 `CLAUDE.md` 的 `- 版本号单一来源是 \`desktop/package.json\`。` 之后追加：

```markdown
- **三个 project-\* 路由同名不同物**：`pages/project-overview/project-overview` 在代码里指**工作台**（四列干活界面，刻意不改名）；产品语言里的「项目概览页」是 `pages/project-home/project-home`；「项目列表页」是 `pages/project-list/project-list`。写代码以路由为准，写文案以本条为准。导航总规则：凡是工作台参与的跳转一律 `reLaunch`，工作台之外的页面之间用 `navigateTo`。详见 `.claude/agents/sidebar-shell.md` 的术语表。
```

- [ ] **Step 8: 跑核对命令确认改到位**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && echo '--- 两个新页进了 sidebar-shell ---' && grep -n "pages/project-home/project-home\|pages/project-list/project-list" .claude/agents/sidebar-shell.md | head -4 ; echo '--- CLAUDE.md 三个术语串 ---' && grep -n "pages/project-home/project-home" CLAUDE.md && grep -n "pages/project-list/project-list" CLAUDE.md && grep -c "工作台" CLAUDE.md ; echo '--- 腐烂数字应全清（下一条无输出即通过）---' && grep -n "10638\|5195\|(2158)\|(1648+)\|(766)\|(777)\|(660)\|(593，" .claude/agents/sidebar-shell.md ; echo "grep exit=$? （1 = 无命中 = 通过）"
```

Expected：前三组各有命中（`grep -c "工作台" CLAUDE.md` ≥ 1）；最后一条 `grep -n` **零输出**且 `grep exit=1`。

顺带跑一次列表页组建的导航护栏，确认那两条跨组断言现在绿了：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:nav
```

Expected：退出码 0，输出 `导航契约检查通过`（`npm run check:nav` 这个 script 由列表页组 Task 16 引入）。

- [ ] **Step 9: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add .claude/agents/sidebar-shell.md CLAUDE.md && git commit -m "$(cat <<'EOF'
docs(agents): sidebar-shell 补三级导航与两个新页，修行号腐烂

新增术语表（三个 project-* 路由同名不同物）、三级导航总规则两条、
两个新页的文件地图与九个 e2e 锚点契约、project-home 的多实例守卫
指针名与「不调 /version/status」纪律；工作台侧消费 conversationId
走既有 loadHistoryChat 也一并写死。顺手修 :10/:12 的内部地图头
（10638→4841，样式已外置、九模块已拆出）与 :78-79 的各页面行数
（全部 2026-08-08 实测重取）。CLAUDE.md 全局约定同步术语一条。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 37: ai-chat.md 登记项目级会话列表端点、可见性口径，并补 project_memory 表结构与「不复用它做档案」的三条理由

**Files:**
- Modify: `.claude/agents/ai-chat.md:17`（`AiChatController.java` 那条 bullet 之后、`:18` 的 `AiAssistantService` 之前，插入一条新 bullet）
- Modify: `.claude/agents/ai-chat.md:34`（`context/ 子包` 那条 bullet 之后插入 `project_memory` 一整块）
- Modify: `.claude/agents/ai-chat.md:143`（「## 已知地雷」最后一条 bullet 之后追加两条）
- Test: `grep` 核对（命令见步骤）

**Interfaces:**
- Consumes: 新端点 `GET /api/projects/{projectId}/conversations`（控制器 `controller/ProjectOverviewController.java`，业务落既有 `service/ProjectAiMessageService.listProjectConversations(Long, LocalDateTime, String, int)`）；新仓储方法 `ProjectAiMessageRepository.findProjectConversationSummaries`、`AgentRunRecordRepository.findByConversationIdIn`；新增索引 `idx_ai_message_project_created` / `idx_ai_message_conversation_created`
- Produces: ai-chat.md 登记与既有 `/api/ai/conversations` 并行的第二条通道 + 复合游标口径 + 200/code 鉴权口径 + `project_memory` 表结构与三条不复用理由

- [ ] **Step 1: 跑核对命令，确认文档现在没有这些内容**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && grep -n "projects/{projectId}/conversations\|listProjectConversations\|findProjectConversationSummaries\|project_memory" .claude/agents/ai-chat.md ; echo "exit=$? （1 = 无命中）" && echo '--- 插入位锚点 ---' && sed -n '17p;18p;34p;143p;144p;145p' .claude/agents/ai-chat.md | cut -c1-70
```

Expected：第一条 `grep` 无任何输出、`exit=1`（文档现在只有 `:17` 提到 user-scoped 的 `GET /conversations`，全文一次都没提过 `project_memory`——spec §10 点名的正是这个缺口）。第二条 `sed` 输出形如：

```
- `controller/ai/AiChatController.java` — 已不含任何对话端点，只剩会
- `service/ai/AiAssistantService.java` — 只剩 `loadAssistants()`（读
- context/ 子包：ContextCompressor、ConversationSummarizer、FileCont
  （缓存键含模型名，但不含 baseUrl，只改地址时靠 clearCache 生效）。
（空行）
## 辅助模型、子 Agent 与身份作用域（2026-08 供应商三档改造）
```

- [ ] **Step 2: 在 :17 那条 bullet 之后插入项目级会话列表端点**

在 `- \`controller/ai/AiChatController.java\` — 已不含任何对话端点…` 这**整条 bullet 之后**（也就是 `- \`service/ai/AiAssistantService.java\`` 那一行之前）插入：

```markdown
- **项目级会话列表（2026-08 项目概览页 A 期）**：`GET /api/projects/{projectId}/conversations`，控制器在 `controller/ProjectOverviewController.java`（**不在 ai 包下**——它是概览页那一组端点之一），业务落既有 `service/ProjectAiMessageService.listProjectConversations(...)`（**`com.checkba.service`，没有 `.ai` 子包**；放这里是为了就地复用它的 private `cleanTitle` / `extractPreview` / `truncatePreview`，不新起服务）。仓储是新增的 `ProjectAiMessageRepository.findProjectConversationSummaries(projectId, before, beforeId)`，**与既有 `findConversationSummaries` 并存、后者一行不改**（那条服务 `/api/ai/conversations`，动了会牵动整个 AI 面板）。
  - **与 `/api/ai/conversations` 是两条独立通道，别合并**：既有那条是 user-scoped（同时按 projectId 与 userId 过滤，「我在这个项目里的会话」）且返回**裸数组**；新这条去掉 userId 条件变成「这个项目的全部会话」且返回**信封** `{code:0,data:{conversations:[...],nextBefore,nextBeforeId}}`。
  - **可见性是分层的，这是唯一的语义变更**：**只放开列表层**（title / lastMessage / updatedAt / runStatus / ownerUserId / ownerName），**正文层一行都不放开**——正文仍按 `ProjectAiMessageService.canUseConversation` 判权。放开正文正是 2026-08 安全审计修过的那类问题，不要顺手做进去。
  - **鉴权口径是全站的 200 + code，不是 401/403**：`AuthController.getUserIdFromSession(sessionId)` 为 null 时抛 `IllegalArgumentException("未登录")`，由 `config/GlobalExceptionHandler.java:69-77` 统一转成 **HTTP 200 + `{"code":1,"message":"未登录"}`**（那段注释明写「统一返回 HTTP 200，通过 code 字段表示失败」，全站 90+ 端点同一口径，前端 `services/api.js` 的 request 包装器也是按 code 解的）。**关键是不许像 `/api/ai/conversations` 那样静默返回空数组**——那让人以为「没有对话」而不是「你没登录」。再过 `hasReadPermission(projectId, userId)`（注意参数序 projectId 在前），失败抛「无权访问该项目」。**不拒 CLIENT**（列表层按项目全员可见是产品决策）。
  - **runStatus 读表不读内存**：批量走 `AgentRunRecordRepository.findByConversationIdIn`（防 N+1），**不读 `AgentRunStateService` 的内存 Map**。既有 `/api/ai/conversations`（`AiChatController.java:99-102`）用的是内存态，进程重启后全变 null；概览页要把全部历史铺开，用内存态会整片显示无状态。两个端点因此可能对同一个会话给出不同的 runStatus，**这是有意的、不需要对齐**。
  - **分页是复合游标，不是单字段**：`ORDER BY MAX(m.createdAt) DESC, m.conversationId DESC`，`HAVING (:before IS NULL OR MAX(m.createdAt) < :before OR (MAX(m.createdAt) = :before AND m.conversationId < :beforeId))`，响应同时回 `nextBefore` 与 `nextBeforeId`，下一页两个都要带。**只用 `MAX(createdAt)` 一维会永久丢条**：同批导入 / 同毫秒落库 / MySQL 秒级截断都会让两个会话的 `MAX(createdAt)` 完全相等，翻页时其中一条再也看不到。
  - **limit 只能在 Java 层做**：这条 JPQL 用了 4 个标量子查询 + GROUP BY + HAVING，套 `Pageable` 会逼出手写 countQuery 或两段式。服务层取全部汇总行后 `stream().limit(limit + 1)`，第 limit+1 条存在即 hasMore，游标取第 limit 条的 `(updatedAt, conversationId)`。
  - **前端不许再清洗一次**：title / lastMessage 已由服务端过 `cleanTitle` / `extractPreview` / `truncatePreview`，`ConversationList.vue` 不许再剥标签、不许再截字数（仓里已有两套并行漂移的正则，不许出第三套）。两个已知展示形态要有兜底：`lastMessage` 可能是**空串**（`extractPreview` 对以 import/def/function/class/const/let/var/public/private 开头的正文直接返回空串，服务端此时回退到用户第一条消息，**回退条件只判空串、不判长度**——「已核对」「好的」是合法短回复），`title` 可能是字面量**「新对话」**（清洗兜底与 LLM 起标题失败写库同文案，前端无法区分）。
  - **点开一条历史 → 进工作台并打开它**：概览页 `reLaunch` 到工作台时带 `conversationId` query，工作台 `onLoad` 读到后调既有 `loadHistoryChat({ conversationId })`（`pages/project-overview/project-overview.vue:4729`）。那个方法内部要 `$refs.chatInterface.loadMessages(...)`，**必须在 mounted 且 AI 面板已渲染之后调**；它同时会清掉该会话的未读蓝点、并带竞态防护（快速切换时丢弃已不是当前会话的旧响应）。概览页本身**绝不内嵌 ChatInterface**——`loadHistoryChat` 是完整切换会话，会在用户还没进工作台时就抢占当前会话。
```

- [ ] **Step 3: 在 `context/ 子包` 那条 bullet（:34）之后插入 project_memory 一整块**

在 `- context/ 子包：ContextCompressor、ConversationSummarizer、FileContextLoader、LegalInfoProtector、ProjectContextHolder。` 之后插入：

```markdown
**`project_memory`（项目级长期记忆，喂模型用；不是项目档案）**

- 实体 `model/entity/ProjectMemory.java`，表 `project_memory`，**15 列**：`id`、`project_id`（`nullable=false, unique=true`，一个项目一行）、`project_name(200)`、`project_type(100)`、`listed_company(200)`、`target_company(200)`、`transaction_structure(TEXT)`、`transaction_amount(NUMERIC(20,2))`、四个 JSON 列 `key_dates(Map)` / `parties(List<Map>)` / `key_variables(Map)` / `legal_refs(List<String>)` / `check_conclusions(List<Map>)`、`created_at`、`updated_at`。`toCoreContext()` 把它拼成注入 system prompt 的那段文本。仓储 `ProjectMemoryRepository`：`findByProjectId` / `existsByProjectId` / `deleteByProjectId`。
- 写入方两条：`service/ai/memory/ProjectMemoryExtractor.extractAndUpdateProjectMemory(:49)`（每轮异步跑的**纯正则**抽取，`LEGAL_REF:29 / AMOUNT:32 / DATE:38 / COMPANY:41 / PARTY:44`）与 `MemoryTools.update_project_info(:232)` → `MemoryManager.updateProjectField(:649)`（模型自觉调用，只写五个字段）。读取方三处：`ContextAssemblerService:401` 与 `:458`、`ContextCompressor:328`（经 `toCoreContext()`）、`MemoryTools:206`。
- **`project_memory` 不是项目档案的落点**（2026-08 项目概览页 A 期的决策，新建了 `project_profile_field` 表；这条写在这里是因为「为什么不用 project_memory」会被反复问）：
  1. **消费者不同**。`project_memory` 服务的是 AI 上下文装配——它是喂给模型的记忆。档案是给律师看、律师能改的字段。记忆错了模型会绕过去，档案错了律师会当真。
  2. **写入是整行覆盖且无乐观锁**。`MemoryManager.saveProjectMemory(:629)` 只从 existing 抄回 `id` 和 `createdAt`，然后 `save` 整个游离实体；**全仓 39 个实体上 `@Version` 零命中**。正则抽取器（每轮异步）与 `update_project_info`（模型随时调）写同一行，后到的整行覆盖会抹掉对方刚写的字段——律师手填的值放进去必被覆盖。
  3. **字段对不齐**。15 列里没有「客户」（只有 listedCompany / targetCompany）、没有「立项时间」、没有「下一步」。
- 补充事实，两个方向都别说错：那五个字段（projectName/projectType/listedCompany/targetCompany/transactionStructure）**有写入通道但完全靠模型自觉**（`update_project_info`），本机实测 68 行里这些列非空计数均为 0。既不能说「没有通道」（会导致重复造轮子），也不能说「有数据可用」。
- `ProjectMemoryExtractor` 与 `project_memory` **保持现状不动**，概览页只是不读它。
```

- [ ] **Step 4: 在「## 已知地雷」最后一条 bullet 之后追加两条**

找到「## 已知地雷」一节的最后一条 bullet（现在是「本地 Ollama 的地址与模型名有 DB 覆盖键……只改地址时靠 clearCache 生效」，末行 `:143`），在其后追加：

```markdown
- **`project_ai_message` 的索引是 2026-08 随项目概览页 A 期才补上的**：`idx_ai_message_project_created (project_id, created_at)` 与 `idx_ai_message_conversation_created (conversation_id, created_at)`，定义在实体的 `@Table(indexes = {...})` 上，由 `ProjectAiMessageIndexTest` 读 INFORMATION_SCHEMA 钉住。在此之前这张表零 `@Index`、线上只有主键索引，项目级会话汇总是全表扫描套全表扫描。四个 profile 全是 `ddl-auto: update`、无 flyway/liquibase、无 schema.sql，**索引被谁顺手删掉不会报错、只会悄悄变慢**——所以才用测试守着。（配套的「删项目清 AI 数据」级联清理仍属后续批次。）
- **会话列表有两条通道，改一条前先确认改的是哪条**：user-scoped 的 `/api/ai/conversations`（裸数组、内存态 runStatus、AI 面板历史下拉在用）与 project-scoped 的 `/api/projects/{id}/conversations`（信封、表态 runStatus、复合游标、项目概览页在用）。两者的 SQL、鉴权口径、返回形状全都不同，**共用的只有 `ProjectAiMessageService` 那三个 private 清洗方法**。改清洗逻辑会同时影响两条，改 SQL/鉴权只影响一条。
```

- [ ] **Step 5: 跑核对命令确认改到位**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && for k in "listProjectConversations" "findProjectConversationSummaries" "只放开列表层" "nextBeforeId" "GlobalExceptionHandler.java:69-77" "project_memory" "idx_ai_message_project_created" "loadHistoryChat"; do printf '%-40s %s\n' "$k" "$(grep -c "$k" .claude/agents/ai-chat.md)"; done && echo '--- 不许再出现 service/ai/ProjectAiMessageService ---' && grep -n "service/ai/ProjectAiMessageService" .claude/agents/ai-chat.md ; echo "exit=$? （1 = 无命中 = 通过）"
```

Expected：八个 key 的计数依次是 `1 1 1 1 1`、`project_memory` ≥ 4、`idx_ai_message_project_created` 1、`loadHistoryChat` 1；最后那条 `grep -n` 零输出、`exit=1`（包路径是 `com.checkba.service`，**没有 `.ai` 子包**，全文不许再写成 `service/ai/ProjectAiMessageService`）。

- [ ] **Step 6: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add .claude/agents/ai-chat.md && git commit -m "$(cat <<'EOF'
docs(agents): ai-chat 登记项目级会话列表端点与 project_memory 契约

新端点 GET /api/projects/{id}/conversations 与既有 user-scoped 的
/api/ai/conversations 并存：信封 vs 裸数组、表态 vs 内存态 runStatus、
复合游标 (MAX(createdAt), conversationId)、鉴权走全站 200+code 口径，
共用的只有三个 private 清洗方法。补 project_memory 的 15 列表结构、
两条写入通道、三处读取点，以及「为什么不复用它做项目档案」的三条
理由（消费者不同 / 整行覆盖无乐观锁 / 字段对不齐）。地雷两条：索引
已补齐且靠测试守着、两条会话通道别改错。

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 38: 修 spec §10 点名的文档腐烂：version-control.md 两处行号 + 三处代码注释

**Files:**
- Modify: `.claude/agents/version-control.md:27`（describeChanges 行号）
- Modify: `.claude/agents/version-control.md:157`（describeChanges 行号，第二处）
- Modify: `backend/src/main/java/com/checkba/version/ProjectRepoService.java:170`（注释里不存在的「limit 上限 100」）
- Modify: `frontend/src/utils/recentProjects.js:2`（注释自称存了访问时间，代码没有）
- Modify: `backend/src/main/java/com/checkba/model/entity/UserActivityLog.java:57-60`（duration 单位注释写「秒」，实际毫秒）
- Test: `grep` 核对 + `mvn test -Dtest=ChangeDescriptionTest` 冒烟（只改注释，不该有行为变化）

**Interfaces:**
- Consumes: 实测事实（下面每条都给了取证命令）
- Produces: 五处注释与文档说的话与代码一致。**本任务只改注释文字，不动任何一行逻辑。**

> `App.vue:14` 的路由埋点注释（页面数 11 → 13）**不在本任务范围内**：它由列表页组 Task 23 Step 3a 随 `pages.json` 一起改完，本任务再改一次会扑空。

- [ ] **Step 1: 取证——四条一次跑完，亲眼看到腐烂**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && echo '=== 1 describeChanges 真实位置 ===' && grep -n "static String describeChanges\|private static String displayName" backend/src/main/java/com/checkba/version/WorkSessionService.java && echo '--- 文档说的位置 ---' && grep -n ":299" .claude/agents/version-control.md && echo '=== 2 timeline 的 limit 有没有钳到 100 ===' && sed -n '240,246p' backend/src/main/java/com/checkba/version/VersionController.java && grep -n "limit" backend/src/main/java/com/checkba/version/ProjectRepoService.java && echo '=== 3 recentProjects 存没存时间戳 ===' && sed -n '1,20p' frontend/src/utils/recentProjects.js && echo '=== 4 duration 传的是什么单位 ===' && sed -n '205,224p' frontend/src/utils/activityTracker.js
```

Expected（四条结论，逐条对照）：

1. `describeChanges` 在 `WorkSessionService.java:1563`（配套 `displayName` 在 `:1575`）；文档 `:27` 与 `:157` 都写「`:299`」「`:299-304`」——**差 1264 行**。
2. `VersionController.java:243` 是 `@RequestParam(defaultValue = "50") int limit`，一路直传 `logForPath`（`:255`）/`log`（`:257`），`ProjectRepoService` 里 limit 只出现在 `:147` / `:153` / `:172` / `:180` 的 `setMaxCount(limit)`，**全链没有任何 `Math.min` 或 100 的钳制**；而 `ProjectRepoService.java:170` 的注释写「单项目仓库很小、limit 上限 100，开销可接受」——那个上限不存在。
3. `recentProjects.js` 里 `RECENT_KEY` 存的是 `list.slice(0, MAX_RECENT)`，一个**纯 id 数组**，没有任何时间戳；`:2` 却写「只存 id 与访问时间」。
4. `activityTracker.js:207` 的 `effectiveDuration = totalRawDuration` 是 `Date.now()` 差值（**毫秒**；同段代码 `:203` 在做 `segDur / 60000` 转分钟、`:210` 做 `/ 15000` 取 15 秒档只是为了拼 metaInfo 文案），`:223` 把它**原样**传给 `logActivity`；而 `UserActivityLog.java:58` 的注释写「持续时间（秒）」。

- [ ] **Step 2: 修 version-control.md 两处行号**

`:27` 把：

```markdown
  - `describeChanges()`（:299 附近）：生成时间线文案，过滤 `.awd/` 前缀、去扩展名——律师看到的是「修改了《股权转让协议》」。
```

换成：

```markdown
  - `describeChanges()`（:1563，static；配套的 `displayName()` 在 :1575）：生成时间线文案，过滤 `.awd/` 前缀、去扩展名——律师看到的是「修改了《股权转让协议》」。三种形态：空列表回「整理了文件结构」，单文件回「修改了《X》」，多文件回「修改了《X》等 N 份文件」。
```

`:157` 把这个子串（该行较长，只替换这一段，不要替换整行）：

```markdown
`WorkSessionService.describeChanges()`（:299-304）同样过滤。
```

换成：

```markdown
`WorkSessionService.describeChanges()`（:1563-1572）同样过滤。
```

- [ ] **Step 3: 修 ProjectRepoService.java:170 那句不存在的上限**

把：

```java
     * 净变化，也正是律师想看的那一条。单项目仓库很小、limit 上限 100，开销可接受。
```

换成：

```java
     * 净变化，也正是律师想看的那一条。单项目仓库很小，全量走历史的开销可接受。
     * 注意 limit **没有服务端上限**：VersionController.timeline（:243）的 @RequestParam
     * 默认 50、原样直传到这里的 setMaxCount，调用方传多大就走多大。（旧注释曾自称
     * 「上限 100」，全链核对过，那个钳制从来不存在。）
```

- [ ] **Step 4: 修 recentProjects.js:2 的注释**

把：

```js
// 只存 id 与访问时间，名称一律从 getMyProjects 实时解析，避免改名后显示陈旧。
```

换成：

```js
// 只存 id（LAST_KEY 一个数字 + RECENT_KEY 一个 id 数组，**不存时间戳**，顺序即最近度）；
// 名称一律从 getMyProjects 实时解析，避免改名后显示陈旧。
// 格式刻意不扩：项目概览页也调 recordProjectVisit，但启动直达永远进工作台，
// 不为「上次落在哪个页面」加字段（spec §5.3 决策）。
```

- [ ] **Step 5: 修 UserActivityLog.java:57-60 的单位**

把：

```java
    /**
     * 持续时间（秒）
     * 仅对 CLOSE_FILE / PAGE_VIEW 等结束事件有效，表示该次会话的持续时长
     */
```

换成：

```java
    /**
     * 持续时间（**毫秒**）
     * 仅对 CLOSE_FILE / PAGE_VIEW 等结束事件有效，表示该次会话的持续时长。
     * 唯一写入方是前端 utils/activityTracker.js（:207 effectiveDuration 是
     * Date.now() 差值，:223 原样经 logActivity 传上来），后端不做任何单位换算。
     * 旧注释写「秒」，与实际差 1000 倍——读这个字段做统计前先看清楚。
     */
```

- [ ] **Step 6: 跑核对 + 后端冒烟（只改注释，行为必须零变化）**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && grep -n ":299" .claude/agents/version-control.md ; grep -n "limit 上限 100" backend/src/main/java/com/checkba/version/ProjectRepoService.java ; grep -n "只存 id 与访问时间" frontend/src/utils/recentProjects.js ; grep -n "持续时间（秒）" backend/src/main/java/com/checkba/model/entity/UserActivityLog.java ; echo "以上四条应全部无输出"
```

Expected：四条 `grep` 全部无输出，最后打印那句提示。

后端冒烟（`describeChanges` 的护栏测试是 `ChangeDescriptionTest`，**不是** `WorkSessionServiceTest`——后者在仓里不存在，写错会得到「No tests were executed」而不是失败；**JAVA_HOME 必须显式传 JDK 21，系统默认 25 会 SIGBUS**）：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -q test -Dtest=ChangeDescriptionTest
```

Expected：`BUILD SUCCESS`，`Tests run: N, Failures: 0, Errors: 0`。

- [ ] **Step 7: 提交**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git add .claude/agents/version-control.md backend/src/main/java/com/checkba/version/ProjectRepoService.java frontend/src/utils/recentProjects.js backend/src/main/java/com/checkba/model/entity/UserActivityLog.java && git commit -m "$(cat <<'EOF'
docs: 修五处腐烂注释（只改文字，零行为变化）

- version-control.md :27/:157 的 describeChanges 行号 299 → 1563（差 1264 行）
- ProjectRepoService.java:170 自称的「limit 上限 100」全链不存在，改成说明
  VersionController.timeline:243 默认 50 且无服务端上限
- recentProjects.js:2 自称「存 id 与访问时间」，代码里只有 id 数组无时间戳
- UserActivityLog.java:57 duration 单位注释写「秒」，写入方 activityTracker.js
  :207/:223 传的是 Date.now() 差值毫秒，差 1000 倍

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 39: [验证任务·非 TDD] 全量验证：后端 mvn clean test + MySQL8 建表复验 + 前端三套静态护栏 + app-e2e，并记基线

**Files:**
- Test: `backend/`（`mvn clean test` 全量 + MySQL8 建表复验）
- Test: `frontend/scripts/check-emit-bindings.mjs`（`npm run check:emits`）
- Test: `frontend/scripts/check-navigation-contract.mjs`（`npm run check:nav`，由列表页组 Task 16 引入）
- Test: `frontend/tests/project-home/`（`npm run test:project-home`，由概览页组 Task 24 引入）
- Test: `frontend/tests/app-e2e/run.mjs`（`npm run test:app-e2e` 全量，不带 `AI_E2E=0`）

**Interfaces:**
- Consumes: 本 PR 全部改动（后端新表/新端点/新索引、前端两个新页五个新组件、e2e 与四份文档）
- Produces: 五套验证的通过记录与新基线数字（写进 PR 描述）

> **本任务不写代码、不做红绿循环**，只跑已有验证并记录基线数字。它是本 PR 合并前的最后一道关，五套必须**全部**跑，不许只跑改动相关的那套。

- [ ] **Step 1: 后端全量测试（H2 那一侧的建表验证在这里顺带完成）**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn clean test 2>&1 | tail -30
```

Expected：`BUILD SUCCESS`，且 `Tests run: N, Failures: 0, Errors: 0`。

三条纪律：
- **`JAVA_HOME` 必须显式传 JDK 21**——本机系统默认是 25，会 SIGBUS 崩在 mmap 上，表现是莫名其妙的 JVM crash 而不是测试失败。
- **必须 `clean test` 不是 `test`**：跨类 `public static final` 常量在编译期内联，增量编译会留下「源码一致、字节码不一致」的假失败。
- **有 5 个类被 env 门控跳过属常态**：`AllowedModelsLiveContractTest`（`RUN_LIVE_MODEL_CHECK=1`）、`RealLlmSmokeTest`（`OPENROUTER_API_KEY`）、`CrossLanguageSignatureTest`（python）、`ProjectProfileFieldMysqlSchemaTest` 与 `ProjectAiMessageIndexMysqlTest`（`AWD_MYSQL_SCHEMA_CHECK=1`，MySQL 侧在 Step 2 单独跑），不是回归。

这一步顺带在 **H2（MODE=PostgreSQL）** 上验了 `project_profile_field` 的建表与 `project_ai_message` 的两条索引。**MySQL8 那一侧必须另外验一次**——见 Step 2。

- [ ] **Step 2: MySQL8 建表复验（新表零成本但字段只增不减，这条不能省）**

为什么必须做：桌面壳开发态默认跑 **prod profile（MySQL8）**，打包态才跑 desktop（H2 file, MODE=PostgreSQL），见 `desktop/main/services/backend-service.js:123`。也就是说本机跑测试的库和线上不是同一种库。四个 profile 全是 `ddl-auto: update`、无 flyway/liquibase，`db/migration/init_user.sql` 是零引用死文件。推论：**新表零成本，但字段只增不减不改类型**，VARCHAR 长度在 Hibernate update 模式下也不会自动加宽。而 `@UniqueConstraint` / `@Index` 的物理列名解析、`VARCHAR(4000)` 在 utf8mb4 下的行长（4000×4 = 16000 字节）**只在 MySQL 上才暴露**。

**2a. 起一次性 MySQL 8**（容器名 / 端口 / 库名 / 开关四项与 Task 6、Task 11 完全同一套配方：`awd-mysql-schema-check` / `13306` / `checkba_schema_check` / `AWD_MYSQL_SCHEMA_CHECK=1`；端口 13306 避开本机可能已占的 3306，`--rm` 保证退出即删）：

```bash
docker run -d --rm --name awd-mysql-schema-check \
  -e MYSQL_ROOT_PASSWORD=checkba123 -e MYSQL_DATABASE=checkba_schema_check \
  -e MYSQL_USER=checkba -e MYSQL_PASSWORD=checkba123 \
  -p 13306:3306 mysql:8.0 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

等它就绪：

```bash
until docker exec awd-mysql-schema-check mysqladmin ping -uroot -pcheckba123 --silent 2>/dev/null; do sleep 2; done; echo "MySQL8 ready"
```

Expected：最终打印 `MySQL8 ready`（首次拉镜像可能要一两分钟）。

**2b. 跑两个 env 门控的 MySQL 建表测试类**（就是 Step 1 里被跳过的那两个；`@TestPropertySource` 里的 `spring.datasource.url` 已指向 `127.0.0.1:13306/checkba_schema_check`）：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && AWD_MYSQL_SCHEMA_CHECK=1 JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn test -Dtest='ProjectProfileFieldMysqlSchemaTest,ProjectAiMessageIndexMysqlTest'
```

Expected：`BUILD SUCCESS`，`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`（Task 6 的 3 个用例 + Task 11 的 1 个）。**`Skipped` 必须是 0**——若仍是 4，说明 `AWD_MYSQL_SCHEMA_CHECK=1` 没传进去，这一步等于没跑，别当绿看。日志里也不该出现 `Row size too large` / `Specified key was too long` / `Error executing DDL`。

**2c. 人眼复核建表结果（另开一个终端）**：

```bash
docker exec -i awd-mysql-schema-check mysql -ucheckba -pcheckba123 checkba_schema_check -e "SHOW CREATE TABLE project_profile_field\G SHOW INDEX FROM project_ai_message\G SELECT SUM(CHARACTER_OCTET_LENGTH) AS char_bytes FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='checkba_schema_check' AND TABLE_NAME='project_profile_field';"
```

**验收标准，四条全中才算过**：
1. `SHOW CREATE TABLE project_profile_field` 输出里有 ``UNIQUE KEY `uk_profile_field_project_key` (`project_id`,`field_key`)``——证明 `@UniqueConstraint` 的 snake_case 物理列名在 MySQL 上解析对了；
2. 同一输出里有 ``KEY `idx_profile_field_project` (`project_id`)``；
3. 同一输出里 `field_value` 是 `varchar(2048)`、`evidence` 是 `varchar(4000)`、`pending_value` `varchar(2048)`、`pending_evidence` `varchar(4000)`、`uid` `varchar(36)`、末尾 `DEFAULT CHARSET=utf8mb4`——且 `CREATE TABLE` 本身没报 `Row size too large`（InnoDB 单行 65535 字节限）；
4. `SHOW INDEX FROM project_ai_message` 里能看到 `idx_ai_message_project_created` 与 `idx_ai_message_conversation_created`；`char_bytes` 一列约 **48816**（= 256+8192+32+16000+8192+16000+144），**必须 < 65535**。

**2d. 清场**：

```bash
docker rm -f awd-mysql-schema-check ; echo cleaned
```

- [ ] **Step 3: 前端三套静态护栏**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run check:emits && npm run check:nav && npm run test:project-home
```

Expected：三条都退出码 0；`check:nav` 打印 `导航契约检查通过`；`test:project-home` 打印 `pass N / fail 0`。

- `check:emits` 已进 CI，拦的是「组件 `$emit` 了一个没在 `emits` 数组里声明的事件名」。本 PR 新增的 `ProfileHeader`（emits `['save']`）与 `ConversationList`（emits `['open', 'load-more']`）必须显式声明，`OverviewStatsBar` / `ActivityFeed` / `TaskSchedule` 无 emit。
- `check:nav` 守的是导航契约与五条直达工作台的出口。
- **这三套都拦不住 prop 名写错**——prop 契约是本仓已知的盲区。所以 Step 5 的真机走查不能省。
- 前端包管理是 **npm 不是 pnpm**。

- [ ] **Step 4: app-e2e 全量（这次不带 AI_E2E=0）**

前置同 Task 32 Step 0（dev server 在 5174、后端在 9696）。

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && npm run test:app-e2e ; echo "exit=$?"
```

Expected：`exit=0`，末尾结果行形如 `步骤: NNN 通过, 0 失败; 异常信号 M 条`。

**基线判定纪律**：`异常信号` 那一栏不为 0 不等于回归——要逐条对照上一版基线看是不是既有信号。把这次的 `NNN / 0 / M` 三个数字与截图目录路径记进 PR 描述，作为下一次的基线。

跑 J11（多后端协作那段）需要额外提供 `APP_E2E_JAR`；缺它时那段会显式 `note('skip', ...)`，不静默假绿。先打一个包：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend" && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -q -DskipTests package && ls -l target/backend-0.0.1-SNAPSHOT.jar
```

再带着它跑：

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/frontend" && APP_E2E_JAR="/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91/backend/target/backend-0.0.1-SNAPSHOT.jar" npm run test:app-e2e ; echo "exit=$?"
```

- [ ] **Step 5: 真机走查概览页五个区块（前面四步全部拦不住 prop 契约）**

在 dev Electron 里手工点一遍，逐条打勾：

1. 从项目列表页点卡片进概览页 —— 档案头显示项目名 + 5 个字段（未填的显示「未填写」，`openedAt` 是灰字并注明取自建档时间）
2. 统计条四个数字都不是 `undefined` / `NaN`；`isLocalRoot` 项目上文案是「已登记 N 项」而不是「共 N 个文件」
3. 动态块在未开版本记录的项目上是中性空态，**不是**「版本记录操作失败，请重试」
4. 日程与任务块是「还没有排任务」空态
5. AI 对话列表：有历史的项目能看到标题/预览/时间/发起人；点一条会跳工作台，**且工作台里那条历史对话真的被打开了**（这是本切片纳入的工作台侧 `conversationId` 消费，走 `loadHistoryChat`）
6. 档案手填：改一个字段 → 该字段变成已填、`source` 为 `user`；清空 → 回到未填态（`openedAt` 回落建档时间）
7. 顶栏「返回项目列表」与「进入工作台」各点一次，来回三轮，界面不出现重影/双份顶栏（页面栈多实例的肉眼症状）

任何一项拿到 `undefined` 或空白，八成是 prop 名拼错——`check:emits` 不会告诉你。

- [ ] **Step 6: 把五套结果写进 PR 描述**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-overview-planning-aeea91" && git status --short
```

Step 1-5 不该产生任何文件改动，`git status --short` 应为空，本任务无需提交。把下面这段填好数字后贴进 PR 描述：

```
验证：
- backend mvn clean test（JDK 21）：BUILD SUCCESS，Tests run N / Failures 0 / Errors 0（5 个类 env 门控 skip 属常态）
- MySQL8 建表复验（docker mysql:8.0 utf8mb4 + AWD_MYSQL_SCHEMA_CHECK=1 跑
  ProjectProfileFieldMysqlSchemaTest / ProjectAiMessageIndexMysqlTest）：project_profile_field 的
  uk_profile_field_project_key 与 idx_profile_field_project 均已建，field_value/pending_value
  varchar(2048)、evidence/pending_evidence varchar(4000)，字符列总字节 48816 < 65535；
  project_ai_message 两条索引均已建
- frontend npm run check:emits / check:nav / test:project-home：pass
- frontend npm run test:app-e2e（含 APP_E2E_JAR）：NNN 通过 / 0 失败 / 异常信号 M 条
  （均为既有信号，逐条对照过上一版基线）
- dev Electron 真机走查概览页五区块 + 档案手填 + 对话点开进工作台：pass
```
