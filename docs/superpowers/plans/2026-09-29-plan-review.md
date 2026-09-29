# 计划审阅（可编辑计划 + 批注）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 计划模式的计划可以在工作台 md 标签里改（改动处有可见样式）并对选中文字写批注，改动与批注一起回喂模型直接执行。

**Architecture:** 后端给计划文件落盘补一条带 fileId 的 `artifact saved` 事件，并新增按文件的审阅记录（基线全文 + 批注）REST；前端 PlainTextEditor（CodeMirror 6）在有审阅记录时挂审阅扩展（行级 diff 装饰、选区批注、右栏），提交时把修订全文 + 改动摘要 + 批注拼成一条用户消息经既有 `sendMessage` 出口发出。计划卡只剩「打开修订 / 按此推进」与状态。

**Tech Stack:** Spring Boot + JPA（ddl-auto）、Vue 3（uni-app H5）、CodeMirror 6（`@codemirror/state|view`）、node:test、puppeteer-core 真渲染。

**Spec:** `docs/superpowers/specs/2026-09-29-plan-review-design.md`

## Global Constraints

- 新建一方源文件带 SPDX 双行头：`SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors` + `SPDX-License-Identifier: AGPL-3.0-or-later`（Java 用 `//`，Vue 用 `<!-- -->`，js/mjs 用 `//`）。
- 禁止 emoji（代码、文案、提交信息）。
- 前端包管理 npm；本树 `frontend/` 无 node_modules，跑测试前 `ln -s "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/frontend/node_modules" frontend/node_modules`，跑完 `rm frontend/node_modules`（先确认不存在再建）。
- 后端 mvn 必须 `JAVA_HOME=$(/usr/libexec/java_home -v 21)`。
- 界面文案 zh/en 成对（`frontend/src/locales/zh-CN/*.js` 与 `en-US/*.js` 同键），跑 `node scripts/check-locale-parity.mjs` exit 0。
- 颜色只用 `--awd-*` 令牌，新增令牌加进 `frontend/src/utils/appTheme.js` 的浅色与深色两套；外壳保持浅色。
- 子代理禁止 git commit / stash / checkout；每个任务的提交由主会话做。
- 契约字面量：SSE 事件名 `artifact`、`operation:"saved"`；REST 前缀 `/api/projects/{pid}/files/{fileId}/review`；审阅状态 `open | submitted | discarded`；CM6 class `cm-review-edited` / `cm-review-deleted` / `cm-review-commented`。

---

### Task 1: 后端——计划落盘后发 `artifact saved` 事件 + 按相对路径解析 fileId

**Files:**
- Modify: `backend/src/main/java/com/checkba/service/ai/AgentStreamHandler.java:515-530`（记录已发出的 artifact id 顺序）
- Modify: `backend/src/main/java/com/checkba/service/ai/AgentOrchestrator.java:2040-2100`（落盘后发 saved 事件）
- Modify: `backend/src/main/java/com/checkba/controller/ProjectFileController.java`（新增 `GET /api/projects/{projectId}/files/resolve?path=`）
- Modify: `backend/src/main/java/com/checkba/service/ProjectFileService.java`（新增 `resolveByRelativePath`）
- Test: `backend/src/test/java/com/checkba/service/ai/AgentOrchestratorArtifactSavedEventTest.java`（新建）
- Test: `backend/src/test/java/com/checkba/service/ProjectFileServiceResolvePathTest.java`（新建）

**Interfaces:**
- Produces: SSE `artifact` 事件 `{"operation":"saved","id":"<artifactId 或空串>","fileId":123,"filePath":"AI 助手文件/<会话文件夹>/<name>.md","type":"implementation_plan"}`。
- Produces: `GET /api/projects/{pid}/files/resolve?path=<相对路径>` → 200 `{"fileId":123,"name":"...","parentId":...}`；404 找不到。相对路径以「/」分隔、从项目根算、与 `artifactSavedNoticeDelta` 写进对话的那条一致（首段是文件夹显示名「AI 助手文件」或 `AI Assistant Files`，两者都接受）。
- Produces: `ProjectFileService.resolveByRelativePath(Long projectId, String relativePath): Optional<ProjectFile>`。

- [ ] **Step 1: 写 saved 事件的失败测试**

照 `AgentOrchestratorArtifactSavedNoticeTest.java` 的脚手架（它已经能驱动落盘那段并捕获 SSE），新建 `AgentOrchestratorArtifactSavedEventTest.java`：

```java
@Test
@DisplayName("计划落盘后紧接着发一条 artifact saved 事件，带 fileId 与相对路径")
void savedEventCarriesFileIdAndPath() {
    // 复用 SavedNoticeTest 的 arrange：projectFileService.saveArtifactFile 返回 id=77、parentId=5 的 ProjectFile，
    // findFile(5) 返回名为 conversationId 的文件夹
    List<String> events = runArtifactSave("implementation_plan", "示例计划", "# 计划\n- 第一步");
    String saved = events.stream().filter(e -> e.contains("\"operation\":\"saved\"")).findFirst().orElse(null);
    assertNotNull(saved, "落盘成功后必须发 saved 事件");
    assertTrue(saved.contains("\"fileId\":77"), saved);
    assertTrue(saved.contains("\"filePath\":\"AI 助手文件/"), saved);
    assertTrue(saved.contains("\"type\":\"implementation_plan\""), saved);
}

@Test
@DisplayName("流式层发过 create 的 artifact id 按顺序对齐到 saved 事件")
void savedEventReusesStreamedArtifactId() {
    // handler 先流过一个 <artifact> 块（AgentStreamHandler 记下 id），再落盘
    String streamedId = streamOneArtifactAndGetId("implementation_plan");
    List<String> events = runArtifactSave("implementation_plan", "示例计划", "# 计划");
    assertTrue(events.stream().anyMatch(e -> e.contains("\"operation\":\"saved\"") && e.contains("\"id\":\"" + streamedId + "\"")));
}

@Test
@DisplayName("对不上流式 id 时 saved 事件 id 为空串，仍带 fileId + filePath")
void savedEventWithoutStreamedIdStillCarriesFile() {
    List<String> events = runArtifactSave("task_list", null, "- 事项");
    String saved = events.stream().filter(e -> e.contains("\"operation\":\"saved\"")).findFirst().orElseThrow();
    assertTrue(saved.contains("\"id\":\"\""), saved);
    assertTrue(saved.contains("\"fileId\":77"), saved);
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=AgentOrchestratorArtifactSavedEventTest`
Expected: FAIL（没有 saved 事件 / 编译错误：辅助方法不存在）。

- [ ] **Step 3: 实现**

`AgentStreamHandler`：在 :515 生成 `artifactId` 处，把 id 追加进新字段 `private final List<String> streamedArtifactIds = new ArrayList<>();`，加 `public List<String> drainStreamedArtifactIds()`（返回副本并清空）。

`AgentOrchestrator`（:2040 那段循环里，每个 artifact 对应一个序号 `idx`）：在 `saved != null` 分支、`sendTextDelta(guard, savedNotice)` 之前：

```java
String streamedId = idx < streamedIds.size() ? streamedIds.get(idx) : "";
String relPath = folderDisplayName(folderName) + "/" + saved.getName(); // 与 artifactSavedNoticeDelta 同一段路径拼法，抽成同一个私有方法复用
sendRunEvent(guard, "artifact", String.format(
        "{\"operation\":\"saved\",\"id\":\"%s\",\"fileId\":%d,\"filePath\":\"%s\",\"type\":\"%s\"}",
        escapeJson(streamedId), saved.getId(), escapeJson(relPath), escapeJson(type)));
```

其中 `streamedIds = streamHandler.drainStreamedArtifactIds()` 在循环前取一次（`streamHandler` 是本轮的 `AgentStreamHandler` 实例，编排器已持有）。`artifactSavedNoticeDelta` 里拼路径的那段与这里必须调同一个方法，别复制。

`ProjectFileService`：

```java
/** 按「AI 助手文件/<夹>/<名>」这种相对路径解析文件：首段接受文件夹显示名的中英文两种写法。 */
public Optional<ProjectFile> resolveByRelativePath(Long projectId, String relativePath) {
    if (relativePath == null || relativePath.isBlank()) return Optional.empty();
    String[] segs = relativePath.replace('\\', '/').split("/");
    Long parentId = null;
    for (int i = 0; i < segs.length; i++) {
        String seg = segs[i].trim();
        if (seg.isEmpty()) continue;
        List<String> candidates = (i == 0 && ("AI 助手文件".equals(seg) || "AI Assistant Files".equals(seg)))
                ? List.of("AI Assistant Files", "AI 助手文件") : List.of(seg);
        Optional<ProjectFile> hit = Optional.empty();
        for (String c : candidates) {
            hit = projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(projectId, parentId, c);
            if (hit.isPresent()) break;
        }
        if (hit.isEmpty()) return Optional.empty();
        parentId = hit.get().getId();
        if (i == segs.length - 1) return hit;
    }
    return Optional.empty();
}
```

`ProjectFileController`：

```java
@GetMapping("/resolve")
public ResponseEntity<?> resolveByPath(@PathVariable Long projectId, @RequestParam("path") String path,
                                       @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
    Long userId = getUserIdFromSession(sessionId);
    if (userId == null) throw new UnauthorizedException("请先登录");
    checkFileTreeAccess(projectId, userId);
    return projectFileService.resolveByRelativePath(projectId, path)
            .<ResponseEntity<?>>map(f -> ResponseEntity.ok(Map.of("fileId", f.getId(), "name", f.getName(),
                    "parentId", f.getParentId() == null ? 0 : f.getParentId())))
            .orElseGet(() -> ResponseEntity.notFound().build());
}
```

`ProjectFileServiceResolvePathTest`（Mockito 桩仓储）：三条——中文首段命中、英文首段命中、中间段缺失返回 empty。

- [ ] **Step 4: 跑测试确认绿**

Run: `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest='AgentOrchestratorArtifactSavedEventTest,AgentOrchestratorArtifactSavedNoticeTest,ProjectFileServiceResolvePathTest'`
Expected: 全部 PASS。

- [ ] **Step 5: 汇报给主会话（主会话提交）**

---

### Task 2: 后端——审阅记录与批注（实体、仓储、服务、REST）

**Files:**
- Create: `backend/src/main/java/com/checkba/model/entity/ProjectFileReview.java`
- Create: `backend/src/main/java/com/checkba/model/entity/ProjectFileReviewComment.java`
- Create: `backend/src/main/java/com/checkba/repository/ProjectFileReviewRepository.java`
- Create: `backend/src/main/java/com/checkba/repository/ProjectFileReviewCommentRepository.java`
- Create: `backend/src/main/java/com/checkba/service/review/FileReviewService.java`
- Create: `backend/src/main/java/com/checkba/controller/FileReviewController.java`
- Test: `backend/src/test/java/com/checkba/service/review/FileReviewServiceTest.java`
- Test: `backend/src/test/java/com/checkba/controller/FileReviewControllerTest.java`

**Interfaces:**
- Produces REST（都要 `X-Session-Id`，读走 `checkFileTreeAccess`、写走 `checkFileWriteAccess`，fileId 必须属于 projectId）：
  - `POST /api/projects/{pid}/files/{fileId}/review` body `{"conversationId":"...","artifactId":"...","baselineText":"..."}` → 200 `{"review":{id,fileId,conversationId,artifactId,status,baselineText,createdAt,updatedAt},"comments":[]}`；已有 open 记录时原样返回它（不覆盖基线）。
  - `GET /api/projects/{pid}/files/{fileId}/review` → 200 同上；无 open 记录 204。
  - `POST .../review/comments` body `{"fromLine":3,"toLine":4,"quotedText":"...","body":"..."}` → 200 `{id,fromLine,toLine,quotedText,body,createdAt}`。
  - `DELETE .../review/comments/{cid}` → 204。
  - `POST .../review/submit` → 200 `{"review":{...status:"submitted"},"comments":[...]}`。
  - `POST .../review/discard` → 200 `{"review":{...status:"discarded"}}`，并把文件字节写回 `baselineText`（UTF-8），触发 `signalChange`。
- Produces: `FileReviewService.open(projectId, fileId, conversationId, artifactId, baselineText, userId)`、`current(fileId)`、`addComment(...)`、`deleteComment(...)`、`submit(fileId)`、`discard(projectId, fileId, userId)`。

- [ ] **Step 1: 写服务层失败测试**

```java
@ExtendWith(MockitoExtension.class)
class FileReviewServiceTest {
    @Mock ProjectFileReviewRepository reviews;
    @Mock ProjectFileReviewCommentRepository comments;
    @Mock ProjectFileService projectFileService;
    @InjectMocks FileReviewService svc;

    @Test @DisplayName("同一文件已有 open 记录时 open() 幂等返回它，不覆盖基线")
    void openIsIdempotent() {
        ProjectFileReview existing = new ProjectFileReview();
        existing.setId(9L); existing.setFileId(77L); existing.setStatus("open"); existing.setBaselineText("原文");
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.of(existing));
        ProjectFileReview r = svc.open(1L, 77L, "conv", "art", "新的基线", 5L);
        assertSame(existing, r);
        assertEquals("原文", r.getBaselineText());
        verify(reviews, never()).save(any());
    }

    @Test @DisplayName("submit 把 open 记录置 submitted")
    void submitMarksSubmitted() {
        ProjectFileReview r = new ProjectFileReview(); r.setId(9L); r.setFileId(77L); r.setStatus("open");
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.of(r));
        when(reviews.save(any())).thenAnswer(a -> a.getArgument(0));
        assertEquals("submitted", svc.submit(77L).getStatus());
    }

    @Test @DisplayName("discard 把文件字节写回基线并置 discarded")
    void discardRestoresBaseline() {
        ProjectFileReview r = new ProjectFileReview(); r.setId(9L); r.setFileId(77L); r.setStatus("open"); r.setBaselineText("基线正文");
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.of(r));
        when(reviews.save(any())).thenAnswer(a -> a.getArgument(0));
        svc.discard(1L, 77L, 5L);
        verify(projectFileService).overwriteTextContent(1L, 77L, "基线正文", 5L);
        assertEquals("discarded", r.getStatus());
    }

    @Test @DisplayName("没有 open 记录时 addComment 抛 IllegalStateException")
    void addCommentRequiresOpenReview() {
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> svc.addComment(77L, 1, 2, "引用", "评论"));
    }
}
```

`ProjectFileService.overwriteTextContent(projectId, fileId, text, userId)` 若不存在就在本任务加：走既有的字节写入路径（与 `POST /api/files/{id}/upload` 同一个服务方法）+ `signalChange`；先 grep 有没有现成的「用字符串覆盖文件内容」方法（text_* 工具用的那条），有就复用、别新写。

- [ ] **Step 2: 跑测试确认红**

Run: `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=FileReviewServiceTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现实体、仓储、服务**

实体照 `ProjectAiMessageAttachment.java` 的写法（`@Entity @Table @Data`，`@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`）：

```java
@Entity
@Table(name = "project_file_review", indexes = {
        @Index(name = "idx_file_review_file", columnList = "file_id"),
        @Index(name = "idx_file_review_status", columnList = "file_id,status") })
@Data
public class ProjectFileReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "file_id", nullable = false) private Long fileId;
    @Column(name = "conversation_id", length = 128) private String conversationId;
    @Column(name = "artifact_id", length = 128) private String artifactId;
    @Lob @Column(name = "baseline_text") private String baselineText;
    @Column(name = "status", length = 16, nullable = false) private String status; // open | submitted | discarded
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
}
```

```java
@Entity
@Table(name = "project_file_review_comment", indexes = @Index(name = "idx_file_review_comment_review", columnList = "review_id"))
@Data
public class ProjectFileReviewComment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "review_id", nullable = false) private Long reviewId;
    @Column(name = "from_line") private Integer fromLine;
    @Column(name = "to_line") private Integer toLine;
    @Lob @Column(name = "quoted_text") private String quotedText;
    @Lob @Column(name = "body") private String body;
    @Column(name = "created_at") private LocalDateTime createdAt;
}
```

仓储：`Optional<ProjectFileReview> findFirstByFileIdAndStatus(Long fileId, String status)`；`List<ProjectFileReviewComment> findByReviewIdOrderByIdAsc(Long reviewId)`；`void deleteByReviewId(Long reviewId)`。

服务（`@Service @RequiredArgsConstructor`，`@Transactional` 写方法）：`open` 先查 open 记录，有则返回；无则新建（status=open、时间戳）。`addComment` 无 open 记录抛 `IllegalStateException("no open review")`。`submit`/`discard` 找不到 open 记录抛同款异常。`discard` 先 `projectFileService.overwriteTextContent`，再置 discarded。

控制器照 `ProjectFileController` 的鉴权写法（`getUserIdFromSession` + `checkFileTreeAccess` / `checkFileWriteAccess` + `checkFileInProject`），`IllegalStateException` 映射 409。

- [ ] **Step 4: 写控制器测试并跑全部**

`FileReviewControllerTest` 用 `MockMvc` + `@MockBean FileReviewService`：`GET` 无记录 204；`POST` 返回 JSON 形状含 `review.status=open` 与 `comments=[]`；`POST comments` 缺 body 400；fileId 不属于项目 403/404（照 `checkFileInProject` 的既有行为）。

Run: `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest='FileReviewServiceTest,FileReviewControllerTest'`
Expected: PASS。

- [ ] **Step 5: 汇报给主会话（主会话提交）**

---

### Task 3: 前端纯函数——行级 diff、批注重锚定、回喂消息拼装

**Files:**
- Create: `frontend/src/utils/lineDiff.js`（从 `ArtifactCard.vue` 的 `lineDiffStats` 抽出并扩展为返回区间）
- Modify: `frontend/src/components/ArtifactCard.vue:60-110`（改为 import `lineDiffStats` from `@/utils/lineDiff.js`，删掉内联实现）
- Create: `frontend/src/utils/planReview.js`（重锚定 + 拼消息）
- Test: `frontend/tests/plan-review/line-diff.test.mjs`
- Test: `frontend/tests/plan-review/plan-review.test.mjs`

**Interfaces:**
- Produces `lineDiff(baseline: string, current: string): { hunks: number, added: number, removed: number, changedLines: number[], deletions: Array<{ beforeLine: number, count: number, text: string }> }`。`changedLines` 是当前文本里新增/改动的行号（1 起）；`deletions` 是被删段在当前文本里的插入位（`beforeLine`：删除发生在当前第几行之前，末尾删除用 `current 行数 + 1`）。
- Produces `lineDiffStats(a, b)` 保持 ArtifactCard 现在的返回形状 `{hunks, added, removed}`（就是 `lineDiff` 的前三项）。
- Produces `reanchorComment(comment: {fromLine,toLine,quotedText}, currentText: string): { fromLine, toLine, found: boolean }`：先在原区间 ±20 行内找 `quotedText`，再全文找；找不到 `found=false` 并保留原行号。
- Produces `buildPlanReviewPrompt({ lang: 'zh'|'en', currentText, diff: {hunks,added,removed}, comments: Array<{quotedText, body, found}> }): { message: string, displayText: string }`。0 改动 0 批注时 `message` 为既有确认语「已确认实施计划，请按此推进。」/ 英文 `Implementation plan confirmed, please proceed.`，displayText「按此推进」/`Proceed`。

- [ ] **Step 1: 写失败测试**

`tests/plan-review/line-diff.test.mjs`：

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { lineDiff, lineDiffStats } from '../../src/utils/lineDiff.js'

test('改一行：changedLines 只含那一行，hunks=1', () => {
  const d = lineDiff('a\nb\nc', 'a\nB\nc')
  assert.deepEqual(d.changedLines, [2])
  assert.equal(d.hunks, 1); assert.equal(d.added, 1); assert.equal(d.removed, 1)
})
test('中间删两行：deletions 记在当前第 2 行之前', () => {
  const d = lineDiff('a\nb\nc\nd', 'a\nd')
  assert.deepEqual(d.changedLines, [])
  assert.deepEqual(d.deletions, [{ beforeLine: 2, count: 2, text: 'b\nc' }])
})
test('末尾追加两行：changedLines 是新行号', () => {
  const d = lineDiff('a', 'a\nb\nc')
  assert.deepEqual(d.changedLines, [2, 3])
})
test('lineDiffStats 与旧 ArtifactCard 形状一致', () => {
  assert.deepEqual(lineDiffStats('a\nb', 'a\nb\nc'), { hunks: 1, added: 1, removed: 0 })
})
```

`tests/plan-review/plan-review.test.mjs`：

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { reanchorComment, buildPlanReviewPrompt } from '../../src/utils/planReview.js'

test('文字上移后按引用原文重锚定', () => {
  const r = reanchorComment({ fromLine: 5, toLine: 5, quotedText: '第二步' }, '新加\n第二步\n第三步')
  assert.deepEqual(r, { fromLine: 2, toLine: 2, found: true })
})
test('引用原文被删：found=false 且保留原行号', () => {
  const r = reanchorComment({ fromLine: 2, toLine: 2, quotedText: '第二步' }, '第一步\n第三步')
  assert.deepEqual(r, { fromLine: 2, toLine: 2, found: false })
})
test('有改动有批注：message 含修订全文、改动摘要、逐条批注；displayText 是人话', () => {
  const { message, displayText } = buildPlanReviewPrompt({
    lang: 'zh', currentText: '# 计划\n- 改后的第一步',
    diff: { hunks: 1, added: 1, removed: 1 },
    comments: [{ quotedText: '改后的第一步', body: '要引用合同第 3 条', found: true }]
  })
  assert.match(message, /共 1 处（\+1 行 \/ -1 行）/)
  assert.match(message, /针对「改后的第一步」：要引用合同第 3 条/)
  assert.match(message, /修订版计划全文：\n# 计划\n- 改后的第一步$/)
  assert.equal(displayText, '已按修订版推进（1 处改动、1 条批注）')
})
test('0 改动 0 批注退化为既有确认语', () => {
  const { message, displayText } = buildPlanReviewPrompt({ lang: 'zh', currentText: 'x', diff: { hunks: 0, added: 0, removed: 0 }, comments: [] })
  assert.equal(message, '已确认实施计划，请按此推进。')
  assert.equal(displayText, '按此推进')
})
```

- [ ] **Step 2: 跑测试确认红**

Run: `cd frontend && node --test tests/plan-review/*.test.mjs`
Expected: 模块不存在。

- [ ] **Step 3: 实现**

`lineDiff.js`：把 ArtifactCard 里的 LCS DP 搬过来，回溯时记录三种操作（等 / 删 / 增）；`changedLines` 收「增」对应的当前行号；连续的「删」合并成一个 deletion，`beforeLine` 取紧接其后第一个当前行号（没有则 `currentLines.length + 1`）；`hunks` 按连续非「等」操作段计数（与旧实现一致）。`n*m > 400000` 时退化为旧实现的整体一处（`changedLines` 取全部当前行，`deletions` 空）。

`planReview.js`：

```js
export function reanchorComment(comment, currentText) {
  const lines = String(currentText || '').split('\n')
  const q = String(comment.quotedText || '')
  const span = Math.max(1, (comment.toLine || comment.fromLine) - comment.fromLine + 1)
  const matchAt = (i) => q && lines.slice(i, i + span).join('\n').includes(q)
  const tryRange = (from, to) => { for (let i = Math.max(0, from); i <= Math.min(lines.length - 1, to); i++) if (matchAt(i)) return i }
  let hit = tryRange(comment.fromLine - 1 - 20, comment.fromLine - 1 + 20)
  if (hit === undefined) hit = tryRange(0, lines.length - 1)
  if (hit === undefined) return { fromLine: comment.fromLine, toLine: comment.toLine, found: false }
  return { fromLine: hit + 1, toLine: hit + span, found: true }
}

export function buildPlanReviewPrompt({ lang = 'zh', currentText, diff, comments }) {
  const n = diff ? diff.hunks : 0
  const m = Array.isArray(comments) ? comments.length : 0
  const en = lang === 'en'
  if (!n && !m) {
    return en ? { message: 'Implementation plan confirmed, please proceed.', displayText: 'Proceed' }
              : { message: '已确认实施计划，请按此推进。', displayText: '按此推进' }
  }
  const list = (comments || []).map((c, i) => en
    ? `${i + 1}. On "${c.quotedText}"${c.found === false ? ' (original text has since changed)' : ''}: ${c.body}`
    : `${i + 1}. 针对「${c.quotedText}」${c.found === false ? '（原文已改动）' : ''}：${c.body}`).join('\n')
  const message = en
    ? `I have revised the plan file and added comments. Execute the revised plan below directly; the comments are additional requirements for the corresponding sections and must be followed when you reach them.\nChange summary: ${n} hunk(s) (+${diff.added} / -${diff.removed} lines).\nComments (${m}):\n${list || '(none)'}\nFull revised plan:\n${currentText}`
    : `我已在计划文件中修订并加了批注，请以下方修订版计划为准直接执行；批注是对相应段落的补充要求，执行到该段时必须照办。\n改动摘要：共 ${n} 处（+${diff.added} 行 / -${diff.removed} 行）。\n批注（${m} 条）：\n${list || '（无）'}\n修订版计划全文：\n${currentText}`
  const displayText = en ? `Proceed with revised plan (${n} change(s), ${m} comment(s))` : `已按修订版推进（${n} 处改动、${m} 条批注）`
  return { message, displayText }
}
```

ArtifactCard 改为 `import { lineDiffStats } from '@/utils/lineDiff.js'`，删掉内联函数；其余行为不变。

- [ ] **Step 4: 跑测试确认绿**

Run: `cd frontend && node --test tests/plan-review/*.test.mjs`
Expected: PASS。再跑 `node --test tests/project-home/attention-locator.test.mjs`（间接读 ArtifactCard 的既有测试）确认仍绿。

- [ ] **Step 5: 汇报给主会话（主会话提交）**

---

### Task 4: 前端——CodeMirror 审阅扩展（改动装饰、批注高亮、选区「+」按钮）

**Files:**
- Create: `frontend/src/utils/planReviewSpecs.js`（纯函数：`buildDecorationSpecs`、`selectionSnapshot`；不 import 任何 `@codemirror/*`）
- Create: `frontend/src/utils/planReviewExtensions.js`（CodeMirror 扩展，import 前者）
- Modify: `frontend/src/utils/appTheme.js`（新增令牌 `--awd-review-edited-bg`、`--awd-review-edited-bar`、`--awd-review-deleted-fg`、`--awd-review-comment-bg`，浅色/深色各一套）
- Test: `frontend/tests/plan-review/review-extensions.test.mjs`

**Interfaces:**
- Consumes: Task 3 的 `lineDiff`、`reanchorComment`。
- Produces `createReviewExtensions({ getBaseline: () => string, getComments: () => Array<{id,fromLine,toLine,quotedText,body}>, onAddComment: (sel: {fromLine,toLine,quotedText}) => void }): { extensions: Extension[], refresh: (view: EditorView) => void }`。`refresh` 在基线/批注变化后调用，重算装饰。
- Produces 纯函数 `buildDecorationSpecs({ baseline, current, comments }): { editedLines: number[], deletions: Array<{beforeLine,count,text}>, commentRanges: Array<{id,fromLine,toLine,found}> }`（不依赖 CodeMirror，供单测）。
- Produces `selectionSnapshot(state: EditorState): { fromLine, toLine, quotedText } | null`（选区为空返回 null）。

- [ ] **Step 1: 写失败测试（纯函数层）**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildDecorationSpecs } from '../../src/utils/planReviewSpecs.js'

test('改动行 + 删除段 + 批注区间一次算齐', () => {
  const specs = buildDecorationSpecs({
    baseline: '一\n二\n三\n四', current: '一\n二改\n四',
    comments: [{ id: 1, fromLine: 4, toLine: 4, quotedText: '四' }]
  })
  assert.deepEqual(specs.editedLines, [2])
  assert.deepEqual(specs.deletions, [{ beforeLine: 3, count: 1, text: '三' }])
  assert.deepEqual(specs.commentRanges, [{ id: 1, fromLine: 3, toLine: 3, found: true }])
})
test('批注原文被删时 found=false 但仍返回', () => {
  const specs = buildDecorationSpecs({ baseline: 'a\nb', current: 'a', comments: [{ id: 2, fromLine: 2, toLine: 2, quotedText: 'b' }] })
  assert.equal(specs.commentRanges[0].found, false)
})
```

- [ ] **Step 2: 跑测试确认红**

Run: `cd frontend && node --test tests/plan-review/review-extensions.test.mjs`
Expected: 模块不存在。

- [ ] **Step 3: 实现**

`planReviewSpecs.js`：纯函数 `buildDecorationSpecs`（调 `lineDiff` + `reanchorComment`）与 `selectionSnapshot(state)`（`state.selection.main`，`state.doc.lineAt(from).number` / `lineAt(to).number`，`quotedText = state.sliceDoc(from, to)`；它只读传入对象的方法，不 import CodeMirror）。**这个文件不许 import 任何 `@codemirror/*`**：node:test 加载模块会执行全部顶层 import，CodeMirror 包在 node 里不可直接加载。

`planReviewExtensions.js`：CodeMirror 扩展，import 上面的纯函数。

`createReviewExtensions` 用 `StateField<DecorationSet>` + `StateEffect` 承载：

- 改动行：`Decoration.line({ class: 'cm-review-edited' })`。
- 删除段：`Decoration.widget({ widget: new DeletedWidget(count, text), block: true, side: -1 })` 放在 `beforeLine` 行首（末尾时 `doc.length`）；`DeletedWidget.toDOM` 生成 `<div class="cm-review-deleted" title="<原文>">已删除 k 行</div>`（文案由调用方传入 zh/en 两句，扩展不自己查 locale）。
- 批注：`Decoration.mark({ class: 'cm-review-commented', attributes: { 'data-comment-id': id } })`；`found=false` 的不画。
- 「+」按钮：`showTooltip` 的 `StateField`，选区非空时在 `selection.main.head` 位置放一个 `<button class="cm-review-add">+</button>`，点击调 `onAddComment(selectionSnapshot(view.state))`。
- `EditorView.baseTheme` 用令牌：`.cm-review-edited { backgroundColor: 'var(--awd-review-edited-bg)', boxShadow: 'inset 3px 0 0 var(--awd-review-edited-bar)' }`，`.cm-review-deleted { color: 'var(--awd-review-deleted-fg)', fontSize: '12px', padding: '0 8px' }`，`.cm-review-commented { backgroundColor: 'var(--awd-review-comment-bg)' }`。
- `refresh(view)` 派发一个 effect 让 StateField 重算；`EditorView.updateListener` 里 `docChanged` 也重算（防抖 150ms）。

令牌值（`appTheme.js` 浅色）：`--awd-review-edited-bg: #FFF6E5`、`--awd-review-edited-bar: #E0A526`、`--awd-review-deleted-fg: #8A8F98`、`--awd-review-comment-bg: #E8F3ED`；深色套按同文件其它令牌的路数给对应值。

- [ ] **Step 4: 跑测试确认绿**

Run: `cd frontend && node --test tests/plan-review/*.test.mjs`
Expected: PASS。

- [ ] **Step 5: 汇报给主会话（主会话提交）**

---

### Task 5: 前端——PlainTextEditor 审阅态（审阅条、右栏、提交/放弃）

**Files:**
- Modify: `frontend/src/components/PlainTextEditor.vue`（props 加 `review`，装配扩展，审阅条与右栏，`GET review` 自恢复）
- Create: `frontend/src/components/PlanReviewBar.vue`
- Create: `frontend/src/components/PlanReviewComments.vue`
- Create: `frontend/src/services/fileReview.js`（REST 封装：`openReview / getReview / addComment / deleteComment / submitReview / discardReview`，走 `services/api.js` 的 request 封装）
- Modify: `frontend/src/locales/zh-CN/editor.js`、`en-US/editor.js`（`planReview.*` 文案）
- Test: `frontend/tests/plan-review/plain-text-editor-review.test.mjs`（读源码断言接线 + 服务层纯函数）

**Interfaces:**
- Consumes: Task 2 REST；Task 3 `lineDiff` / `buildPlanReviewPrompt`；Task 4 `createReviewExtensions`。
- Consumes: 既有 `PlainTextEditor` 的 `getText()`、`reloadFromBackend()`、自动保存（`onUserEdit`）、`file` / `projectId` props。
- Produces: PlainTextEditor 新 prop `review: { conversationId, artifactId, baselineText } | null`（打开时由宿主传；为 null 时组件自己 `getReview(projectId, file.id)`，有 open 记录也进审阅态）。
- Produces: PlainTextEditor emit `review-submit` `{ fileId, message, displayText, conversationId }`（宿主转成 sendMessage）与 `review-state` `{ fileId, hunks, comments, status }`（宿主回传给计划卡）。
- Produces: `fileReview.js` 六个函数，签名 `(projectId, fileId, payload?)`，返回解析后的 JSON；`getReview` 在 204 时返回 `null`。

- [ ] **Step 1: 写失败测试**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const SRC = readFileSync(new URL('../../src/components/PlainTextEditor.vue', import.meta.url), 'utf8')

test('PlainTextEditor 有 review prop、装配审阅扩展、发 review-submit / review-state', () => {
  assert.match(SRC, /review:\s*\{\s*type:\s*Object/)
  assert.match(SRC, /createReviewExtensions\(/)
  assert.match(SRC, /\$emit\('review-submit'/)
  assert.match(SRC, /\$emit\('review-state'/)
  assert.match(SRC, /getReview\(/)
})
test('审阅条与右栏只在审阅态渲染', () => {
  assert.match(SRC, /<PlanReviewBar[\s\S]*v-if="reviewActive"/)
  assert.match(SRC, /<PlanReviewComments[\s\S]*v-if="reviewActive/)
})
```

- [ ] **Step 2: 跑测试确认红**

Run: `cd frontend && node --test tests/plan-review/plain-text-editor-review.test.mjs`
Expected: FAIL。

- [ ] **Step 3: 实现**

PlainTextEditor：

- data 加 `reviewRecord: null, reviewComments: [], reviewStats: { hunks: 0, added: 0, removed: 0 }, reviewSubmitting: false`；computed `reviewActive = !!reviewRecord && reviewRecord.status === 'open'`。
- `boot()` 拿到正文后：若 `props.review` 有值 → `openReview(projectId, file.id, props.review)`；否则 `getReview(...)`；结果存 `reviewRecord/reviewComments`。
- 装配 CodeMirror 时，`reviewActive` 就 `extensions.push(...createReviewExtensions({ getBaseline: () => this.reviewRecord.baselineText, getComments: () => this.reviewComments, onAddComment: this.promptComment, deletedLabel: (k) => this.$t('editor.planReview.deletedLines', { count: k }) }).extensions)`；审阅记录是 boot 后才有的，所以扩展装配放在拿到记录之后（boot 顺序：下载正文 → 查审阅 → 建 view）。
- `promptComment(sel)`：打开一个内联小输入框（`PlanReviewComments` 暴露 `startDraft(sel)`），保存时 `addComment` → 追加到 `reviewComments` → `this._review.refresh(this._view)`。
- `updateListener` 的 `docChanged` 里除自动保存外，重算 `reviewStats = lineDiff(baseline, getText())`，并 `$emit('review-state', ...)`。
- `submitReview()`：先 `await this.save()`（既有保存方法）确保落盘；`submitReview(projectId, file.id)` → 用 `buildPlanReviewPrompt({ lang: this.$i18n.locale.startsWith('en') ? 'en' : 'zh', currentText: getText(), diff: reviewStats, comments: reviewComments.map(c => ({ ...c, ...reanchorComment(c, getText()) })) })` → `$emit('review-submit', { fileId, message, displayText, conversationId: reviewRecord.conversationId })` → `reviewRecord.status='submitted'`，`view.dispatch` 移除扩展（用 `Compartment` 装审阅扩展，`reconfigure([])`）。
- `discardReview()`：`uni.showModal` 确认 → `discardReview(projectId, file.id)` → `reloadFromBackend()` → 退出审阅态（同上 reconfigure）。

`PlanReviewBar.vue`：props `hunks, added, removed, commentCount, submitting`；两个按钮 emit `submit` / `discard`；文案 `editor.planReview.title`（「计划审阅」）、`editor.planReview.summary`（「{hunks} 处改动 · {comments} 条批注」）、`editor.planReview.submit`（「按修订版推进」）、`editor.planReview.discard`（「放弃修改」）。

`PlanReviewComments.vue`：props `comments`（含 found）；列表项显示引用原文（截 80 字）+ 评论 + 删除按钮；`found=false` 的加一行小字 `editor.planReview.anchorLost`（「原文已改动」）；`startDraft(sel)` 显示草稿框（textarea + 保存/取消），emit `add` `{ ...sel, body }` / `remove` `{ id }`。样式：宽 260px 右侧栏，浅色令牌。

`fileReview.js`：

```js
import { request } from '@/services/api.js'  // 以 api.js 实际导出的底层 request 为准
const base = (pid, fid) => `/api/projects/${pid}/files/${fid}/review`
export const openReview = (pid, fid, body) => request(base(pid, fid), { method: 'POST', body })
export const getReview = async (pid, fid) => { const r = await request(base(pid, fid), { method: 'GET', allow204: true }); return r || null }
export const addComment = (pid, fid, body) => request(base(pid, fid) + '/comments', { method: 'POST', body })
export const deleteComment = (pid, fid, cid) => request(base(pid, fid) + '/comments/' + cid, { method: 'DELETE' })
export const submitReview = (pid, fid) => request(base(pid, fid) + '/submit', { method: 'POST' })
export const discardReview = (pid, fid) => request(base(pid, fid) + '/discard', { method: 'POST' })
```

（`request` 的真实名字与 204 处理照 `services/api.js` 里其它 GET 的写法；没有 204 支持就在这里 try/catch 状态码。）

- [ ] **Step 4: 跑测试确认绿**

Run: `cd frontend && node --test tests/plan-review/*.test.mjs && node scripts/check-locale-parity.mjs`
Expected: PASS，parity exit 0。

- [ ] **Step 5: 汇报给主会话（主会话提交）**

---

### Task 6: 前端——计划卡 / 气泡 / 流 / 工作台接线

**Files:**
- Modify: `frontend/src/components/ArtifactCard.vue`（审批栏改「打开修订 / 按此推进」，状态 chip，兜底 textarea）
- Modify: `frontend/src/components/AgentMessage/RootBubble.vue:39-49`（传 `:saved-path`、`:file-id`、`@open-review`）
- Modify: `frontend/src/composables/useAgentStream.js:1812-1826`（处理 `operation==='saved'`）
- Modify: `frontend/src/components/ChatInterface.vue`（`handleArtifactOpenReview`、`handleReviewSubmit`、卡片状态回传）
- Modify: `frontend/src/pages/project-overview/project-overview.vue` 与 `fileOpenTabs.js`（`openFile(file, { review })` 透传到 PlainTextEditor 的 `review` prop；PlainTextEditor 的 `review-submit` / `review-state` 事件回到 ChatInterface）
- Modify: `frontend/src/locales/zh-CN/chat.js`、`en-US/chat.js`（`openRevisionBtn`「打开修订」、`reviewInProgress`「修订中 · {hunks} 处改动 · {comments} 条批注」、`approveDisplayRevised` 复用）
- Test: `frontend/tests/plan-review/artifact-card-review.test.mjs`（读源码断言 + `resolveSavedPath` 纯函数）

**Interfaces:**
- Consumes: Task 1 saved 事件与 resolve 接口；Task 5 的 `review` prop 与两个事件。
- Produces `utils/planReview.js` 新增 `resolveSavedPath(bubbleContent: string): string | null`：从「> 已保存到项目文件：<路径>」或英文 `> Saved to project file: <path>` 那行取路径（取最后一次出现）。
- Produces ArtifactCard 新 props `fileId`（Number|null）、`savedPath`（String）、`reviewState`（`{hunks, comments, status}|null`）；新 emit `open-review` `{ id, type, fileId, savedPath, content }`。

- [ ] **Step 1: 写失败测试**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolveSavedPath } from '../../src/utils/planReview.js'
const CARD = readFileSync(new URL('../../src/components/ArtifactCard.vue', import.meta.url), 'utf8')
const STREAM = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

test('从气泡正文取「已保存到项目文件」的路径（中英都认，取最后一次）', () => {
  assert.equal(resolveSavedPath('正文\n> 已保存到项目文件：AI 助手文件/conv-1/Plan.md\n'), 'AI 助手文件/conv-1/Plan.md')
  assert.equal(resolveSavedPath('> Saved to project file: AI Assistant Files/conv-1/Plan.md'), 'AI Assistant Files/conv-1/Plan.md')
  assert.equal(resolveSavedPath('没有'), null)
})
test('计划卡有「打开修订」按钮并发 open-review；useAgentStream 处理 saved', () => {
  assert.match(CARD, /chat\.openRevisionBtn/)
  assert.match(CARD, /\$emit\('open-review'/)
  assert.match(STREAM, /evt\.operation === 'saved'/)
})
```

- [ ] **Step 2: 跑测试确认红**

Run: `cd frontend && node --test tests/plan-review/artifact-card-review.test.mjs`
Expected: FAIL。

- [ ] **Step 3: 实现**

`useAgentStream.handleArtifactEvent`：

```js
if (evt.operation === 'saved') {
    const arts = currentAssistantBubble.value.artifacts
    const target = (evt.id && arts.find(a => a.id === evt.id)) || arts.find(a => !a.fileId && a.type === evt.type) || null
    if (target) { target.fileId = evt.fileId; target.filePath = evt.filePath; captureChatTimeline(currentAssistantBubble.value) }
    return
}
```

ArtifactCard 审批栏（`!editing` 分支）：「打开修订」`@click.stop="openReview"` + 「按此推进」；`openReview()`：`fileId || savedPath` 任一有值就 `$emit('open-review', { id, type, fileId, savedPath, content: planContent })`，都没有则退回 `startEditing()`（既有 textarea）。`reviewState` 有值且 `status==='open'` 时在标题旁显示 `chat.reviewInProgress` chip；`status==='submitted'` 时 `localResolved=true`。

RootBubble：`:file-id="entry.data.fileId || null"`、`:saved-path="resolveSavedPath(bubble.content)"`（`resolveSavedPath` 从 `@/utils/planReview.js` import）、`:review-state="reviewStates[entry.data.fileId]"`（`reviewStates` 是 ChatInterface 传下来的 prop，按 fileId 索引）、`@open-review="$emit('open-review', $event)"`。

ChatInterface：

```js
const reviewStates = ref({})
const handleArtifactOpenReview = async (art) => {
  let fileId = art.fileId
  if (!fileId && art.savedPath) {
    try { const r = await resolveProjectFileByPath(props.projectId, art.savedPath); fileId = r && r.fileId } catch (e) { fileId = null }
  }
  if (!fileId) { uni.showToast({ title: t('chat.reviewFileMissing'), icon: 'none' }); return }
  emit('open-review-tab', { fileId, review: { conversationId: conversationId.value, artifactId: art.id, baselineText: art.content } })
}
const handleReviewSubmit = async ({ fileId, message, displayText }) => {
  reviewStates.value[fileId] = { ...(reviewStates.value[fileId] || {}), status: 'submitted' }
  await sendMessage({ prompt: message, displayText, fileList: [], projectId: props.projectId, modelId: currentModelId.value, mode: 'AGENT', skillIds: currentSkillIds() })
  scrollToBottom()
}
const handleReviewState = (s) => { reviewStates.value[s.fileId] = s }
```

`resolveProjectFileByPath` 加进 `services/api.js`（`GET /api/projects/${pid}/files/resolve?path=` encodeURIComponent）。

project-overview：`open-review-tab` 事件 → `openFile({ id: fileId, name, fileType: 'md' }, { review })`（文件对象按 fileId 从文件树/后端取名，照 `doc_open_file` 走的那条 `openFile` 路径）；`openFile` 的 `opts.review` 存到 tab 对象 `tab.review`，PlainTextEditor 渲染处 `:review="tab.review || null"`，并把 `@review-submit="onReviewSubmit"` / `@review-state="onReviewState"` 转给 ChatInterface（ChatInterface 暴露 `handleReviewSubmit` / `handleReviewState`，project-overview 通过 ref 调用，照 `flushActiveDocumentForChat` 那种 prop/ref 的既有路数）。

- [ ] **Step 4: 跑测试确认绿**

Run: `cd frontend && node --test tests/plan-review/*.test.mjs tests/project-home/attention-locator.test.mjs tests/project-home/chat-turn-presentation.test.mjs && node scripts/check-locale-parity.mjs`
Expected: PASS。

- [ ] **Step 5: 汇报给主会话（主会话提交）**

---

### Task 7: 真渲染走查 + 领域文档

**Files:**
- Create: `frontend/tests/plan-review/walk-plan-review.mjs`（puppeteer-core 走查脚本，照 `tests/project-home/` 里既有真渲染脚本与 dev-board#1014 那次的做法：H5 dev 接本机 5269 或隔离后端，`checkbaDesktop` 桩）
- Modify: `.claude/agents/ai-chat.md`（「计划审批卡」一节重写）
- Modify: `.claude/agents/doc-editor.md`（PlainTextEditor 审阅态、CM6 扩展位置、令牌）

**Interfaces:** 消费全部前序任务。

- [ ] **Step 1: 起环境**

后端：本树 `mvn -DskipTests package` 出 jar，按 `frontend/tests/app-e2e/run.mjs` 头注释起隔离后端（9797，播 trial 票据，`OPENROUTER_API_KEY` 可不配——走查不打真模型）。前端：`cd frontend && npx uni --port 5176`。

- [ ] **Step 2: 写走查脚本**

流程：建项目 → `POST files/file` + `upload` 放一份 `Plan.md`（内容 6 行）→ `POST .../review` 建 open 记录（基线=同内容）→ 打开工作台、点开该文件 → 断言审阅条可见、文案「0 处改动 · 0 条批注」→ 用 `page.keyboard` 在第 2 行末尾输入「（改）」→ 等 300ms → 断言 `.cm-review-edited` 有 1 个、审阅条「1 处改动」→ 选中第 3 行文字（`page.mouse` 拖选）→ 断言 `.cm-review-add` 出现 → 点它 → 输入「补充引用」→ 保存 → 断言 `.cm-review-commented` 1 个、右栏 1 条 → 截图 `plan-review-1-marks.png` → 拦截 `POST /api/agent/chat` 请求体 → 点「按修订版推进」→ 断言请求体 `message` 含「修订版计划全文」与「针对「」且 `displayText` 为「已按修订版推进（1 处改动、1 条批注）」→ 断言审阅条消失。第二段：再建 open 记录 → 改一行 → 「放弃修改」→ 确认 → 断言 `GET /api/files/{id}/download` 内容等于基线。最后 `DELETE /api/projects/{id}`。

- [ ] **Step 3: 跑走查两遍，截图存到 `/tmp/dev-board-1022-plan-review/`**

Expected: 两遍全 PASS，截图里能看到琥珀底色行、灰色删除标记（若走查里删一行）、绿色批注高亮与右栏。

- [ ] **Step 4: 改领域文档**

ai-chat.md「计划审批卡」一节改为：入口（打开修订 / 按此推进）、saved 事件与 resolve 兜底、审阅记录 REST、回喂消息格式（引用 `buildPlanReviewPrompt`）、状态流转（open → submitted / discarded）、既有 textarea 只作兜底。doc-editor.md 加 PlainTextEditor `review` prop、`planReviewSpecs.js` / `planReviewExtensions.js`、四个令牌、「纯函数与 CodeMirror 扩展分两个文件是为了 node:test 能 import」。

- [ ] **Step 5: 汇报给主会话（主会话提交、发 PR）**
