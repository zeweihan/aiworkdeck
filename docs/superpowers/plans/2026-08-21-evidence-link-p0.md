# EvidenceLink P0（内置底座）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「报告文字 ↔ 底稿文件」关联从一张 `linkKey→fileIdsJson` 表升级成有书签锚点、有目标位置、可双向查询、有状态机的内置事实表，并补齐规模底座前 6 条与大文档 e2e 基线。

**Architecture:** 后端新表 `evidence_link` / `evidence_link_target` + `EvidenceLinkService` 单一出口 + 启动迁移；worker 五个书签原语把锚点从字符属性升级为命名书签；前端拖拽建链改到编辑器区域、点击链接按 targetId 定位、审阅面板加「证据」页、改字后 worker 核对书签回写 stale；SDK 加三方法；稳定性改造与大文档基线组独立成单元。

**Tech Stack:** Spring Boot 3 / JPA（H2 desktop + PG cloud，ddl-auto update）/ JUnit5+Mockito；uni-app Vue3 + `node --test`；LibreOffice WASM worker（zetajs）+ puppeteer lowa-e2e；Ed25519 签名的 Web 插件 SDK（postMessage）。

**Spec:** `docs/superpowers/specs/2026-08-21-evidence-link-p0-design.md`（下称 SPEC）

## Global Constraints

- 全局禁 emoji（代码/UI/文档/commit）。
- worktree 内编辑与构建同树；`mvn` 用 JDK 21（`JAVA_HOME=$(/usr/libexec/java_home -v 21)`）；前端 npm。
- `docs/` 在 .gitignore，入库用 `git add -f`。
- 新增 worker action 后必须 `npm run build:zetaoffice` 再跑 lowa-e2e（测试 serve 的是 dist）。
- worker 失败返回同时写 `message` 与 `error`。
- 段落索引 0 基；locator 页码 1 基；坐标 0..1。
- 书签名 = linkKey，新建 `EVID_<ULID 26 位>`，只含 `[A-Za-z0-9_]`。
- 新增 @Component 工具 / 构造器参数要同步 `RealToolBeans` 与 EvalHarness（本期无 AI 工具，但改 `EditorBridgeService` 构造要查 EvalHarness）。
- `check:emits` 必须绿；新增 `$emit` 要在 `emits:` 声明。
- 商业化：本期全部内置、不门控。
- 同一棵 worktree 同时只跑一个改文件的 agent；单元 A-G 各一棵 worktree。
- 提交信息尾行 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。

## 单元与合并顺序

| 单元 | 看板 | 分支 | 依赖 | 模型档 |
|---|---|---|---|---|
| A 后端表/Service/REST/迁移 | #102 | `feat/evidence-link-backend` | 无 | 主模型（契约） |
| B worker 五原语 + e2e 证据锚点组 | #103 | `feat/evidence-link-worker` | 无 | 主模型（契约） |
| C 拖拽建链 + method 小条 + 定位 | #104 | `feat/evidence-link-drop-locate` | A、B 契约（可先按契约写，合并前 rebase） | 主模型 |
| D 审阅面板证据页 + stale 弹窗 | #105 | `feat/evidence-link-panel` | A、B 契约 | 主模型 |
| E SDK 三方法 + 宿主 + 官网 | #106 | `feat/evidence-link-sdk` | A 契约 | 主模型（契约）；官网搬运 Sonnet |
| F 稳定性 #1/#2/#6 | #107 | `fix/scale-import-create-tree` | 无 | Sonnet 低档写，校验主模型 |
| G 稳定性 #3/#4/#5 + 大文档组 | #108 | `perf/worker-batching-big-doc` | 无（与 B 同文件 office_thread.js，**后合者 rebase**） | 主模型 |

合并顺序 A → B → 其余任意。每单元一个 PR，auto-merge。

## File Structure

**后端（A）**
- Create `backend/src/main/java/com/checkba/model/entity/EvidenceLink.java` — 主表实体
- Create `backend/src/main/java/com/checkba/model/entity/EvidenceLinkTarget.java` — 子表实体
- Create `backend/src/main/java/com/checkba/repository/EvidenceLinkRepository.java` / `EvidenceLinkTargetRepository.java`
- Create `backend/src/main/java/com/checkba/service/evidence/AnchorHash.java` — 归一化 + sha256（纯函数）
- Create `backend/src/main/java/com/checkba/service/evidence/Ulid.java` — 26 位 Crockford ULID
- Create `backend/src/main/java/com/checkba/service/evidence/EvidenceLinkService.java` — 单一出口
- Create `backend/src/main/java/com/checkba/service/evidence/EvidenceLinkViews.java` — `LinkView` / `TargetView` / `TargetInput` record
- Create `backend/src/main/java/com/checkba/service/evidence/EvidenceLinkMigrationRunner.java` — doc_file_link → 新表
- Create `backend/src/main/java/com/checkba/controller/EvidenceLinkController.java` — `/api/projects/{pid}/evidence-links`
- Modify `backend/src/main/java/com/checkba/controller/DocFileLinkController.java` — 改只读代理
- Modify `backend/src/main/java/com/checkba/model/entity/ProjectFile.java` — 加 `metaJson`
- Modify `backend/src/main/java/com/checkba/service/ProjectFileService.java` — 彻底删除时级联
- Modify `backend/src/main/java/com/checkba/service/ai/tools/DocumentEditTools.java` `doc_set_hyperlink` 白名单 + `office_thread.js set_hyperlink_at_anchor` 白名单（B 顺手）
- Test `backend/src/test/java/com/checkba/service/evidence/{AnchorHashTest,UlidTest,EvidenceLinkServiceTest,EvidenceLinkMigrationRunnerTest}.java`
- Create `backend/src/test/resources/fixtures/anchor-hash-vectors.json`（前端同一份拷贝 `frontend/tests/evidence/anchor-hash-vectors.json`，`AnchorHashParityTest` 比对两份 sha256 相同）

**worker（B）**
- Modify `frontend/src/zetaoffice/public/office_thread.js` — 五原语 + `set_hyperlink_at_anchor` 白名单
- Modify `frontend/tests/lowa-e2e/run.mjs` — 新组「证据锚点」

**前端（C/D）**
- Create `frontend/src/utils/anchorHash.js`、`frontend/src/utils/ulid.js`、`frontend/src/utils/evidenceLocator.js`（locator 摘要文案）
- Create `frontend/src/pages/project-overview/evidenceLinkActions.js` — drop 四步、链接解包、打开定位（替代 project-overview.vue 里 `createWpsSelectionFileLink` / `openFileLinkTarget`）
- Create `frontend/src/components/EvidenceMethodBar.vue`、`EvidencePanel.vue`、`EvidenceStaleBar.vue`
- Modify `frontend/src/components/LibreOfficeEditor.vue` — drop 接收、`pendingLocator` 消费、stale 检测、证据 tab 数据流
- Modify `frontend/src/components/ReviewPanel.vue` — 第三 tab 壳
- Modify `frontend/src/components/FilePreview.vue` — pdf `#page=` / 图片框 / 媒体 seek
- Modify `frontend/src/pages/project-overview/fileOpenTabs.js` — `openFile(file, opts)`
- Modify `frontend/src/pages/project-overview/project-overview.vue` — 删 FileLinkDropZone、接新模块
- Delete `frontend/src/components/FileLinkDropZone.vue`
- Modify `frontend/src/services/api.js` — evidence-links 封装
- Modify `frontend/src/locales/zh-CN/*.json` 与 `en-US/*.json` — 新词条
- Test `frontend/tests/evidence/*.test.mjs`（`npm run test:evidence` 新脚本）

**SDK（E）**
- Modify `sdk/plugin-sdk/awd-plugin-sdk.js`、`examples/hello-web-plugin/awd-plugin-sdk.js`、`frontend/src/components/PluginPane.vue`、`docs/PLUGIN_SPEC.md`；官网仓 `lib/plugin-template.ts` + 模拟器（另一个仓，PR 单开）

**稳定性（F/G）**
- Modify `LocalProjectService.java`、`ProjectFileService.java`、`ProjectFileRepository.java`、`FileTree.vue`、`EditorBridgeService.java`、`office_thread.js`、`libreofficeExecutorClient.js`、`zetaOfficeRelay.js`
- Create `frontend/tests/lowa-e2e/fixtures/gen-big-doc.py`、`frontend/tests/lowa-e2e/big-doc.mjs`

---

# 单元 A：后端 EvidenceLink（#102）

### Task A1: AnchorHash 与 Ulid 纯函数

**Files:**
- Create: `backend/src/main/java/com/checkba/service/evidence/AnchorHash.java`
- Create: `backend/src/main/java/com/checkba/service/evidence/Ulid.java`
- Create: `backend/src/test/resources/fixtures/anchor-hash-vectors.json`
- Test: `backend/src/test/java/com/checkba/service/evidence/AnchorHashTest.java`、`UlidTest.java`

**Interfaces:**
- Produces: `AnchorHash.normalize(String) -> String`、`AnchorHash.of(String) -> String`（64 位小写 hex）；`Ulid.next() -> String`（26 位大写 Crockford）。前端 `utils/anchorHash.js` 必须对同一向量产出同一 hash。

- [ ] **Step 1: 写向量文件**

`backend/src/test/resources/fixtures/anchor-hash-vectors.json`：
```json
[
  { "in": "根据《营业执照》，收购人成立于 2020 年。", "norm": "根据《营业执照》,收购人成立于2020年." },
  { "in": "  根据《营业执照》，\n收购人成立于 2020 年。", "norm": "根据《营业执照》,收购人成立于2020年." },
  { "in": "ＡＢＣ（全角）", "norm": "ABC(全角)" },
  { "in": "", "norm": "" }
]
```
归一化规则（两端都按这个实现）：NFKC → 删除全部 Unicode 空白（`\s` 与 U+3000）→ 中文标点映射 `，→,` `。→.` `；→;` `：→:` `！→!` `？→?` `（→(` `）→)` `「」『』“”‘’→` 删除（引号一律删）→ 保留《》书名号。

- [ ] **Step 2: 写失败测试**

```java
package com.checkba.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

class AnchorHashTest {
    @Test
    void normalizeMatchesVectors() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/anchor-hash-vectors.json")) {
            for (JsonNode v : new ObjectMapper().readTree(in)) {
                assertEquals(v.get("norm").asText(), AnchorHash.normalize(v.get("in").asText()), v.get("in").asText());
            }
        }
    }
    @Test
    void hashIs64Hex() {
        assertTrue(AnchorHash.of("abc").matches("[0-9a-f]{64}"));
        assertEquals(AnchorHash.of("a b"), AnchorHash.of("ab"));
        assertNotEquals(AnchorHash.of("ab"), AnchorHash.of("ac"));
    }
}
```
```java
class UlidTest {
    @Test void shape() { String u = Ulid.next(); assertEquals(26, u.length()); assertTrue(u.matches("[0-9A-HJKMNP-TV-Z]{26}")); }
    @Test void monotonicWithinMillisIsNotRequiredButUnique() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (int i = 0; i < 10000; i++) assertTrue(s.add(Ulid.next()));
    }
}
```

- [ ] **Step 3: 跑测试确认红**

Run: `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest='AnchorHashTest,UlidTest'`
Expected: 编译失败（类不存在）

- [ ] **Step 4: 实现**

```java
package com.checkba.service.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Map;

/** 锚点文字归一化 + sha256。前端 utils/anchorHash.js 是同一算法，改一处必须同步另一处并更新向量。 */
public final class AnchorHash {
    private static final Map<Character, String> PUNCT = Map.ofEntries(
            Map.entry('，', ","), Map.entry('。', "."), Map.entry('；', ";"), Map.entry('：', ":"),
            Map.entry('！', "!"), Map.entry('？', "?"), Map.entry('（', "("), Map.entry('）', ")"),
            Map.entry('「', ""), Map.entry('」', ""), Map.entry('『', ""), Map.entry('』', ""),
            Map.entry('“', ""), Map.entry('”', ""), Map.entry('‘', ""), Map.entry('’', ""),
            Map.entry('"', ""), Map.entry('\'', ""));
    private AnchorHash() {}
    public static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isWhitespace(c) || c == '　') continue;
            String m = PUNCT.get(c);
            if (m != null) b.append(m); else b.append(c);
        }
        return b.toString();
    }
    public static String of(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(normalize(s).getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder(64);
            for (byte x : d) h.append(String.format("%02x", x));
            return h.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
```
注意：NFKC 会把全角逗号 `，`(U+FF0C) 归成 `,`，所以 PUNCT 里的 `，` 分支实际很少命中；`。`(U+3002) NFKC 不变，必须靠表。向量第 1 条就是为了钉这个。

```java
package com.checkba.service.evidence;

import java.security.SecureRandom;

/** 26 位 Crockford base32 ULID：前 10 位毫秒时间戳，后 16 位随机。只用于书签名，不追求单调。 */
public final class Ulid {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RND = new SecureRandom();
    private Ulid() {}
    public static String next() {
        char[] out = new char[26];
        long t = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) { out[i] = ALPHABET[(int) (t & 31)]; t >>>= 5; }
        byte[] r = new byte[10]; RND.nextBytes(r);
        long a = 0; for (int i = 0; i < 5; i++) a = (a << 8) | (r[i] & 0xff);
        long b = 0; for (int i = 5; i < 10; i++) b = (b << 8) | (r[i] & 0xff);
        for (int i = 17; i >= 10; i--) { out[i] = ALPHABET[(int) (a & 31)]; a >>>= 5; }
        for (int i = 25; i >= 18; i--) { out[i] = ALPHABET[(int) (b & 31)]; b >>>= 5; }
        return new String(out);
    }
}
```

- [ ] **Step 5: 跑测试确认绿** 同 Step 3 命令，Expected: PASS

- [ ] **Step 6: Commit**
```bash
git add backend/src/main/java/com/checkba/service/evidence backend/src/test
git commit -m "feat(evidence): AnchorHash 归一化与 Ulid 纯函数 + 前后端共用向量"
```

### Task A2: 实体与 Repository

**Files:**
- Create: `model/entity/EvidenceLink.java`、`model/entity/EvidenceLinkTarget.java`
- Create: `repository/EvidenceLinkRepository.java`、`repository/EvidenceLinkTargetRepository.java`
- Modify: `model/entity/ProjectFile.java`（加 `metaJson`）

**Interfaces:**
- Produces: 实体字段见 SPEC §1.1/§1.2；`EvidenceLinkRepository.findByProjectIdAndLinkKey`、`findByProjectIdAndDocFileIdOrderByIdAsc`、`findByProjectIdAndDocFileIdAndStatus`、`findByProjectIdAndDocFileIdAndSectionPathStartingWith`、`findByProjectIdAndIdIn`；`EvidenceLinkTargetRepository.findByLinkIdOrderBySortOrderAscIdAsc`、`findByLinkIdIn`、`findByFileId`、`findByFileIdIn`、`existsByLinkIdAndFileIdAndLocatorHash`、`countByFileIdIn`（返回 `List<Object[]>{fileId,count}` 的 @Query）。

- [ ] **Step 1: 写实体**

```java
@Entity
@Table(name = "evidence_link", indexes = {
        @Index(name = "idx_evl_project_key", columnList = "project_id,link_key", unique = true),
        @Index(name = "idx_evl_doc", columnList = "project_id,doc_file_id"),
        @Index(name = "idx_evl_section", columnList = "project_id,doc_file_id,section_path")
})
@Getter @Setter
public class EvidenceLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "doc_file_id", nullable = false) private Long docFileId;
    @Column(name = "link_key", length = 64, nullable = false) private String linkKey;
    @Column(name = "anchor_text", length = 1000) private String anchorText;
    @Column(name = "anchor_hash", length = 64) private String anchorHash;
    @Column(name = "section_path", length = 512) private String sectionPath;
    @Column(name = "section_title", length = 512) private String sectionTitle;
    @Column(length = 16, nullable = false) private String status = STATUS_ACTIVE;
    @Column(name = "created_by_kind", length = 8, nullable = false) private String createdByKind = KIND_HUMAN;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
    @Column(name = "checked_at") private LocalDateTime checkedAt;

    public static final String STATUS_ACTIVE = "active", STATUS_UNVERIFIED = "unverified",
            STATUS_STALE = "stale", STATUS_ORPHAN = "orphan";
    public static final String KIND_HUMAN = "human", KIND_AI = "ai", KIND_PLUGIN = "plugin";
}
```
`EvidenceLinkTarget`：`id, linkId, fileId, locatorJson(TEXT), locatorHash(64 not null), relation(16 not null, 默认 "supports"), method(16), confidence(Short), note(512), sortOrder(Integer 默认 0), createdByKind(8), createdBy, createdAt`；索引 `idx_evt_link(link_id)`、`idx_evt_file(file_id)`、唯一 `idx_evt_dedupe(link_id,file_id,locator_hash)`。常量 `RELATIONS = Set.of("supports","contradicts","partial")`、`METHODS = Set.of("written_review","written_statement","web_check","third_party","interview")`。

Lombok 在本仓已用（`@RequiredArgsConstructor`/`@Data` 见 DocFileLinkService），`@Getter @Setter` 可用。

`ProjectFile.java` 加：
```java
/** 来源元数据（JSON）：网核截图 {sourceUrl, capturedAt, provider}；P1 起 {docketNo}。无则 null。 */
@Column(name = "meta_json", columnDefinition = "TEXT")
private String metaJson;
```
加 getter/setter（该类是手写 getter/setter 风格，照旧）。

- [ ] **Step 2: 写 Repository**

```java
public interface EvidenceLinkRepository extends JpaRepository<EvidenceLink, Long> {
    Optional<EvidenceLink> findByProjectIdAndLinkKey(Long projectId, String linkKey);
    List<EvidenceLink> findByProjectIdAndDocFileIdOrderByIdAsc(Long projectId, Long docFileId);
    List<EvidenceLink> findByProjectIdAndDocFileIdAndStatusOrderByIdAsc(Long projectId, Long docFileId, String status);
    List<EvidenceLink> findByProjectIdAndDocFileIdAndSectionPathStartingWithOrderByIdAsc(Long projectId, Long docFileId, String prefix);
    List<EvidenceLink> findByProjectIdAndIdIn(Long projectId, Collection<Long> ids);
    long countByProjectId(Long projectId);
}
public interface EvidenceLinkTargetRepository extends JpaRepository<EvidenceLinkTarget, Long> {
    List<EvidenceLinkTarget> findByLinkIdOrderBySortOrderAscIdAsc(Long linkId);
    List<EvidenceLinkTarget> findByLinkIdInOrderBySortOrderAscIdAsc(Collection<Long> linkIds);
    List<EvidenceLinkTarget> findByFileId(Long fileId);
    List<EvidenceLinkTarget> findByFileIdIn(Collection<Long> fileIds);
    boolean existsByLinkIdAndFileIdAndLocatorHash(Long linkId, Long fileId, String locatorHash);
    void deleteByLinkId(Long linkId);
    @Query("select t.fileId, count(t) from EvidenceLinkTarget t where t.fileId in :fileIds group by t.fileId")
    List<Object[]> countByFileIds(@Param("fileIds") Collection<Long> fileIds);
}
```

- [ ] **Step 3: 编译** `mvn -q compile` Expected: 通过
- [ ] **Step 4: Commit** `git commit -m "feat(evidence): evidence_link/evidence_link_target 实体与 Repository；ProjectFile.metaJson"`

### Task A3: EvidenceLinkService（TDD）

**Files:**
- Create: `service/evidence/EvidenceLinkViews.java`、`service/evidence/EvidenceLinkService.java`
- Test: `service/evidence/EvidenceLinkServiceTest.java`（Mockito，仿 `TagServiceTest`）

**Interfaces:**
- Produces（供 Controller/SDK/P1 工具调用）:
```java
record TargetInput(Long fileId, String locatorJson, String relation, String method, Short confidence, String note) {}
record TargetView(Long id, Long fileId, FileBrief file, JsonNode locator, String relation, String method, Short confidence, String note) {}
record FileBrief(Long id, String name, String fileType, Long parentId, boolean isDeleted) {}
record LinkView(Long id, String linkKey, Long docFileId, String anchorText, String anchorHash, String sectionPath, String sectionTitle,
                String status, String createdByKind, LocalDateTime createdAt, LocalDateTime updatedAt, List<TargetView> targets) {}
record AnchorReport(String linkKey, boolean exists, String text) {}

LinkView create(Long userId, Long projectId, Long docFileId, String linkKey, String anchorText, String sectionPath, String sectionTitle, String createdByKind, List<TargetInput> targets)
LinkView addTargets(Long userId, Long projectId, String linkKey, List<TargetInput> targets)
TargetView updateTarget(Long userId, Long projectId, Long targetId, TargetInput patch)   // null 字段 = 不改
void removeTarget(Long userId, Long projectId, Long targetId)
void delete(Long userId, Long projectId, String linkKey)
LinkView getByKey(Long userId, Long projectId, String linkKey)
List<LinkView> listByDoc(Long userId, Long projectId, Long docFileId, String status, String sectionPathPrefix)
List<LinkView> listByFile(Long userId, Long projectId, Long fileId)
List<LinkView> listByParty(Long userId, Long projectId, Long docFileId, Long tagId)
List<String> reportAnchors(Long userId, Long projectId, Long docFileId, List<AnchorReport> reports)  // 返回状态变了的 linkKey
LinkView keepAnchor(Long userId, Long projectId, String linkKey)
LinkView rebind(Long userId, Long projectId, String linkKey, String newLinkKey, String anchorText, String sectionPath, String sectionTitle)
Map<Long, Long> refCounts(Long projectId, Collection<Long> fileIds)
void onFilePurged(Long projectId, Long fileId)   // ProjectFileService 彻底删除时调用
```
- Consumes: `ProjectMemberService.hasReadPermission/hasWritePermission`、`ProjectFileRepository.findById/findAllById`、`FileTagRepository.findByTagId`、`AnchorHash`、`Ulid`。

- [ ] **Step 1: 写失败测试（覆盖下列行为，每条一个 @Test）**

```java
class EvidenceLinkServiceTest {
    EvidenceLinkRepository links = mock(EvidenceLinkRepository.class);
    EvidenceLinkTargetRepository targets = mock(EvidenceLinkTargetRepository.class);
    ProjectFileRepository files = mock(ProjectFileRepository.class);
    FileTagRepository fileTags = mock(FileTagRepository.class);
    ProjectMemberService members = mock(ProjectMemberService.class);
    EvidenceLinkService svc;

    @BeforeEach void setUp() {
        svc = new EvidenceLinkService(links, targets, files, fileTags, members, new ObjectMapper());
        when(members.hasReadPermission(1L, 9L)).thenReturn(true);
        when(members.hasWritePermission(1L, 9L)).thenReturn(true);
        when(links.save(any())).thenAnswer(inv -> { EvidenceLink l = inv.getArgument(0); if (l.getId()==null) l.setId(100L); return l; });
        when(targets.save(any())).thenAnswer(inv -> { EvidenceLinkTarget t = inv.getArgument(0); if (t.getId()==null) t.setId(200L); return t; });
        ProjectFile doc = file(10L, 1L, "报告.docx"); ProjectFile pdf = file(11L, 1L, "执照.pdf");
        when(files.findById(10L)).thenReturn(Optional.of(doc));
        when(files.findById(11L)).thenReturn(Optional.of(pdf));
        when(files.findAllById(any())).thenAnswer(inv -> { List<ProjectFile> out = new ArrayList<>(); for (Object id : (Iterable<?>) inv.getArgument(0)) files.findById((Long) id).ifPresent(out::add); return out; });
    }
    static ProjectFile file(long id, long pid, String name) { ProjectFile f = new ProjectFile(); f.setId(id); f.setProjectId(pid); f.setName(name); f.setIsDeleted(false); return f; }

    @Test void createGeneratesEvidKeyAndActiveForHuman() {
        var v = svc.create(9L, 1L, 10L, null, "根据《营业执照》", "一/（一）", "主体资格", "human",
                List.of(new EvidenceLinkService.TargetInput(11L, "{\"type\":\"pdf\",\"page\":1}", null, "written_review", null, null)));
        assertTrue(v.linkKey().startsWith("EVID_")); assertEquals(31, v.linkKey().length());
        assertEquals("active", v.status()); assertEquals(AnchorHash.of("根据《营业执照》"), v.anchorHash());
        assertEquals(1, v.targets().size()); assertEquals("supports", v.targets().get(0).relation());
    }
    @Test void createByAiIsUnverified() { /* createdByKind="ai" → status unverified */ }
    @Test void createRejectsForeignFile() { /* files.findById(11L) 返回 projectId=2 → IllegalArgumentException，不 save */ }
    @Test void createRejectsBadRelationOrMethod() { /* relation="maybe" / method="guess" → IllegalArgumentException */ }
    @Test void createRejectsContradictsWithoutTarget() { /* targets 空且 relation contradicts 无意义：targets 为空列表 → IllegalArgumentException("至少一个底稿") */ }
    @Test void createRejectsNonMember() { /* hasWritePermission false → IllegalArgumentException("无权限") */ }
    @Test void addTargetsDedupesByLocatorHash() { /* existsByLinkIdAndFileIdAndLocatorHash true → 不 save，返回原 targets */ }
    @Test void reportAnchorsTransitions() {
        // active + text 变 → stale；unverified + text 变 → stale；stale + text 同 → 仍 stale；任何状态 exists=false → orphan；active + text 同 → 不变、checkedAt 更新
    }
    @Test void keepAnchorRefreshesHashAndActivates() { /* stale → active，anchorText/anchorHash 用 report 里最新 text（Service 在 reportAnchors 时把最新 text 暂存在 anchorText？不——keepAnchor 收 newText 参数由前端传 check_link_anchors 的 text */ }
    @Test void rebindReplacesKeyAndActivates() { /* orphan → active，linkKey 换成新值，唯一冲突 → IllegalArgumentException */ }
    @Test void onFilePurgedCascades() { /* 删 target；该 link 无 target 后 status=orphan */ }
    @Test void listByPartyFiltersByTag() { /* fileTags.findByTagId(5L) → fileId 11 → 只返回含该 target 的 link */ }
    @Test void refCountsMapsRows() { /* countByFileIds 返回 [[11L,3L]] → {11:3} */ }
}
```
`keepAnchor` 签名修正为 `keepAnchor(userId, projectId, linkKey, String currentText)`——前端把 `check_link_anchors` 返回的 `text` 一起传上来；Interfaces 块与 REST 随之同步（`POST /{linkKey}/keep` body `{text}`）。

- [ ] **Step 2: 跑确认红** `mvn -q test -Dtest=EvidenceLinkServiceTest` Expected: 编译失败

- [ ] **Step 3: 实现 Service（要点）**

```java
@Service @RequiredArgsConstructor
public class EvidenceLinkService {
    private final EvidenceLinkRepository links; private final EvidenceLinkTargetRepository targets;
    private final ProjectFileRepository files; private final FileTagRepository fileTags;
    private final ProjectMemberService members; private final ObjectMapper om;

    private void requireRead(Long pid, Long uid) { if (uid == null || !members.hasReadPermission(pid, uid)) throw new IllegalArgumentException(LangText.of("无权限访问该项目", "No access to this project")); }
    private void requireWrite(Long pid, Long uid) { if (uid == null || !members.hasWritePermission(pid, uid)) throw new IllegalArgumentException(LangText.of("无权限修改该项目", "No write access to this project")); }
    private ProjectFile requireProjectFile(Long pid, Long fid) { ProjectFile f = fid == null ? null : files.findById(fid).orElse(null); if (f == null || !pid.equals(f.getProjectId())) throw new IllegalArgumentException(LangText.of("文件不属于该项目: ", "File not in project: ") + fid); return f; }
    static String locatorHash(String json) { if (json == null || json.isBlank()) return "-"; return AnchorHash.of(canonical(json)); }
    // canonical：Jackson 解析后按键排序再序列化（ObjectMapper ORDER_MAP_ENTRIES_BY_KEYS + 递归 TreeMap）；解析失败 → IllegalArgumentException("locatorJson 不是合法 JSON")

    @Transactional public LinkView create(...) {
        requireWrite(projectId, userId); requireProjectFile(projectId, docFileId);
        if (targets == null || targets.isEmpty()) throw new IllegalArgumentException(LangText.of("至少关联一个底稿", "At least one target required"));
        String key = StringUtils.hasText(linkKey) ? linkKey.trim() : "EVID_" + Ulid.next();
        if (!key.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("linkKey 只能含字母数字下划线");
        if (links.findByProjectIdAndLinkKey(projectId, key).isPresent()) throw new IllegalArgumentException(LangText.of("链接已存在: ", "Link exists: ") + key);
        EvidenceLink l = new EvidenceLink(); ...; l.setStatus(KIND_AI.equals(kind) ? STATUS_UNVERIFIED : STATUS_ACTIVE); l.setAnchorHash(AnchorHash.of(anchorText)); ...
        l = links.save(l); int order = 0; for (TargetInput t : targets) saveTarget(projectId, l, t, order++, kind, userId);
        return view(l);
    }
    // reportAnchors：按 linkKey 批量取；exists=false → orphan；否则 hash(text) != anchorHash 且 status in (active,unverified,stale) → stale；checkedAt=now；返回变化的 key
    // keepAnchor：status→active，anchorText=currentText，anchorHash=of(currentText)
    // view()：targets 一次 findByLinkIdIn + files.findAllById 批量，不 N+1
}
```

- [ ] **Step 4: 跑确认绿**；补 `mvn -q test -Dtest='EvidenceLinkServiceTest,AnchorHashTest'`
- [ ] **Step 5: Commit** `git commit -m "feat(evidence): EvidenceLinkService 单一出口——建链/追加/反查/状态机/权限"`

### Task A4: 迁移 Runner + ProjectFileService 级联

**Files:**
- Create: `service/evidence/EvidenceLinkMigrationRunner.java`
- Modify: `service/ProjectFileService.java`（彻底删除路径调用 `evidenceLinkService.onFilePurged`；软删不动）
- Test: `service/evidence/EvidenceLinkMigrationRunnerTest.java`

- [ ] **Step 1: 失败测试**
```java
@Test void migratesEachDocFileLinkRowOnce() {
    // doc_file_link 两行：A(wpsFileId "w1" → ProjectFile 10, fileIds [11,12]) B(wpsFileId 查不到)
    // 期望：links.save 1 次（key 原值 lk_x，status unverified，createdByKind human）、targets.save 2 次；B 跳过计数 1
    // 第二次 run：links.countByProjectId>0 → 什么都不做
}
```
- [ ] **Step 2: 实现**
```java
@Component @RequiredArgsConstructor @Slf4j
public class EvidenceLinkMigrationRunner implements ApplicationRunner {
    private final DocFileLinkRepository old; private final EvidenceLinkRepository links; private final EvidenceLinkTargetRepository targets;
    private final ProjectFileRepository files; private final ObjectMapper om;
    @Override @Transactional public void run(ApplicationArguments args) {
        if (links.count() > 0) return;
        List<DocFileLink> rows; try { rows = old.findAll(); } catch (Exception e) { log.info("doc_file_link 不存在，跳过迁移"); return; }
        int ok = 0, skipped = 0;
        for (DocFileLink r : rows) {
            ProjectFile doc = files.findByProjectIdAndWpsFileId(r.getProjectId(), r.getDocWpsFileId()).orElse(null); // 若 Repository 无此方法则加
            if (doc == null) { skipped++; continue; }
            EvidenceLink l = new EvidenceLink(); l.setProjectId(r.getProjectId()); l.setDocFileId(doc.getId()); l.setLinkKey(r.getLinkKey());
            l.setAnchorText(r.getAnchorText()); l.setAnchorHash(AnchorHash.of(r.getAnchorText())); l.setStatus(EvidenceLink.STATUS_UNVERIFIED);
            l.setCreatedByKind(EvidenceLink.KIND_HUMAN); l.setCreatedBy(r.getUserId()); l.setCreatedAt(r.getCreatedAt()); l.setUpdatedAt(LocalDateTime.now());
            l = links.save(l);
            List<Long> ids = parse(r.getFileIdsJson()); int order = 0;
            for (Long fid : ids) { EvidenceLinkTarget t = new EvidenceLinkTarget(); t.setLinkId(l.getId()); t.setFileId(fid); t.setLocatorHash("-"); t.setRelation("supports"); t.setSortOrder(order++); t.setCreatedByKind("human"); t.setCreatedBy(r.getUserId()); t.setCreatedAt(r.getCreatedAt()); targets.save(t); }
            ok++;
        }
        log.info("EvidenceLink 迁移完成: migrated={}, skipped={}", ok, skipped);
    }
}
```
`ProjectFileRepository` 若没有 `findByProjectIdAndWpsFileId` 就加一个 `Optional<ProjectFile>`（先 grep）。
- [ ] **Step 3: ProjectFileService 彻底删除处**：找到 `permanentlyDelete`/清空回收站的实现（grep `deleteById\|delete(` 在 ProjectFileService），在每个实体真删前调 `evidenceLinkService.onFilePurged(projectId, id)`。循环依赖：EvidenceLinkService 不注入 ProjectFileService，只注入 Repository，无环。
- [ ] **Step 4: 跑 `mvn -q test -Dtest='EvidenceLink*'`**，再 `mvn -q test`（全量，约 2300 用例）确认无连带红
- [ ] **Step 5: Commit** `git commit -m "feat(evidence): doc_file_link 启动迁移 + 彻底删除级联 orphan"`

### Task A5: Controller + 旧控制器只读代理 + doc_set_hyperlink 白名单

**Files:**
- Create: `controller/EvidenceLinkController.java`
- Modify: `controller/DocFileLinkController.java`（POST 删除或 410；GET 代理 `evidenceLinkService.getByKey` 并转成旧 `DocFileLinkResult` 形状——`files` = targets 的 file 去重）
- Modify: `service/ai/tools/DocumentEditTools.java` `doc_set_hyperlink` 的 url 校验放行 `https://checkba-internal.local/open?u=checkba://filelink` 前缀
- Test: `controller/EvidenceLinkControllerTest.java`（MockMvc standalone，仿既有 controller 测试；至少：未登录 401 信封、非成员拒绝、POST 建链 200、GET ?fileId 反查）

- [ ] **Step 1: Controller**
```java
@RestController @RequestMapping("/api/projects/{projectId}/evidence-links") @RequiredArgsConstructor
public class EvidenceLinkController {
    private final EvidenceLinkService svc;
    private Long uid(String sid) { Long u = AuthController.getUserIdFromSession(sid); if (u == null) throw new IllegalArgumentException("请先登录"); return u; }
    @PostMapping public LinkView create(@PathVariable Long projectId, @RequestBody CreateReq r, @RequestHeader(value="X-Session-Id", required=false) String sid) {
        return svc.create(uid(sid), projectId, r.docFileId, r.linkKey, r.anchorText, r.sectionPath, r.sectionTitle, r.createdByKind == null ? "human" : r.createdByKind, r.targets); }
    @GetMapping public List<LinkView> list(@PathVariable Long projectId, @RequestParam(required=false) Long docFileId, @RequestParam(required=false) Long fileId,
            @RequestParam(required=false) Long partyTagId, @RequestParam(required=false) String status, @RequestParam(required=false) String sectionPath, @RequestHeader(...) String sid) {
        Long u = uid(sid);
        if (fileId != null) return svc.listByFile(u, projectId, fileId);
        if (docFileId == null) throw new IllegalArgumentException("docFileId 或 fileId 必填");
        if (partyTagId != null) return svc.listByParty(u, projectId, docFileId, partyTagId);
        return svc.listByDoc(u, projectId, docFileId, status, sectionPath);
    }
    @GetMapping("/ref-counts") public Map<Long, Long> refCounts(@PathVariable Long projectId, @RequestParam List<Long> fileIds, ...) { uid(sid)+requireRead; return svc.refCounts(projectId, fileIds); }
    @GetMapping("/{linkKey}") ... getByKey
    @PostMapping("/{linkKey}/targets") ... addTargets(body List<TargetInput>)
    @PatchMapping("/targets/{targetId}") ... updateTarget
    @DeleteMapping("/targets/{targetId}") ... removeTarget
    @DeleteMapping("/{linkKey}") ... delete
    @PostMapping("/anchors/report") ... reportAnchors(body {docFileId, reports:[{linkKey, exists, text}]}) → {changed:[...]}
    @PostMapping("/{linkKey}/keep") ... keepAnchor(body {text})
    @PostMapping("/{linkKey}/rebind") ... rebind(body {newLinkKey, anchorText, sectionPath, sectionTitle})
}
```
`/ref-counts` 必须排在 `/{linkKey}` 之前注册没关系（Spring 按最具体匹配），但 linkKey 正则限制 `{linkKey:[A-Za-z0-9_]+}` 避免吃掉 `ref-counts`——`ref-counts` 含 `-`，不匹配，安全。
- [ ] **Step 2: MockMvc 测试 → 红 → 实现 → 绿**
- [ ] **Step 3: 更新 `.claude/agents/ai-doc-bridge.md`**：新增「EvidenceLink 契约」一节（表结构、状态机、locator schema、REST 表、worker 五原语名、AnchorHash 双端同步红线、书签名=linkKey、`filelink?k=&t=`）。直接把 SPEC §1/§2/§3/§5 的表压缩搬入。
- [ ] **Step 4: 全量 `mvn -q test`；Commit；push；`gh pr create` + `gh pr merge --auto --squash`；看板 #102 写落实记录 → 待复测**（纯后端改动用户无感？否——它改变了点击链接的行为，保持待复测）。

---

# 单元 B：worker 五原语 + lowa-e2e 证据锚点组（#103）

### Task B1: 五原语

**Files:**
- Modify: `frontend/src/zetaoffice/public/office_thread.js`（紧跟 `insert_link_with_bookmark` 之后，约 :3187）

**Interfaces:**
- Produces（action 名与返回形状是 C/D 的契约）：
  - `bookmark_selection {name}` → `{success, name, text}` | `{success:false, error, message}`
  - `get_bookmark_context {name}` → `{success, exists, text, sectionPath, sectionTitle, paragraphIndex}`
  - `check_link_anchors {names[]}` → `{success, items:[{name, exists, text}]}`
  - `adopt_legacy_links {}` → `{success, adopted:[name], skipped}`
  - `goto_bookmark {name}` → `{success}` | 失败 `{success:false, error, message}`

- [ ] **Step 1: 实现**
```js
  // ---- EvidenceLink 锚点原语（dev-board#103）：书签名 = linkKey ----------------
  function bmFail(msg) { return { success: false, error: msg, message: msg }; }
  function selectionRange() {
    const sel = ctrl.getSelection(); let range = null;
    try { if (sel && typeof sel.getByIndex === 'function' && sel.getCount() > 0) range = sel.getByIndex(0); } catch (e) {}
    return range;
  }
  // 从 range 所在段落向前找标题链：OutlineLevel 1..6，每级只取最近一个，直到遇到 1 级或文首
  function headingChainOf(range) {
    const chain = []; // [{level, text}]
    let seen = 99;
    try {
      const en = xModel.getText().createEnumeration();
      const cmp = xModel.getText(); // XTextRangeCompare
      const paras = [];
      while (en.hasMoreElements()) { const el = en.nextElement(); if (el.supportsService && el.supportsService('com.sun.star.text.Paragraph')) paras.push(el); }
      let idx = -1;
      for (let i = 0; i < paras.length; i++) { if (cmp.compareRegionStarts(paras[i].getStart(), range.getStart()) >= 0 && cmp.compareRegionEnds(paras[i].getEnd(), range.getStart()) <= 0) { idx = i; break; } }
      if (idx < 0) return { chain: [], paragraphIndex: -1 };
      for (let i = idx; i >= 0 && seen > 1; i--) {
        let lvl = 0; try { lvl = Number(paras[i].getPropertyValue('OutlineLevel')) || 0; } catch (e) {}
        if (lvl > 0 && lvl < seen) { chain.unshift({ level: lvl, text: (paras[i].getString() || '').trim().slice(0, 200) }); seen = lvl; }
      }
      return { chain, paragraphIndex: idx };
    } catch (e) { return { chain: [], paragraphIndex: -1 }; }
  }
```
注意：`compareRegionStarts`/`compareRegionEnds` 对跨 story（表格单元格）会抛，外层已 try。表格内的选区 `paragraphIndex=-1`、sectionPath 为空——P0 接受（契约里说「不可靠时为空」）。

```js
  bookmark_selection(p) {
    if (!isWriterDoc()) return bmFail(NOT_TEXT_DOC_MSG);
    const name = String((p && p.name) || '');
    if (!/^[A-Za-z0-9_]{1,64}$/.test(name)) return bmFail('bookmark name must match [A-Za-z0-9_]{1,64}');
    const range = selectionRange();
    const text = range ? (range.getString() || '') : '';
    if (!range || !text.length) return bmFail('no selection to bookmark');
    const bms = xModel.getBookmarks();
    if (bms.hasByName(name)) return bmFail('bookmark exists: ' + name);
    const bm = xModel.createInstance('com.sun.star.text.Bookmark'); bm.setName(name);
    range.getText().insertTextContent(range, bm, true);
    return { success: true, name: name, text: text };
  },
  get_bookmark_context(p) {
    const name = String((p && p.name) || ''); const range = anchorRange(name);
    if (!range) return { success: true, exists: false, text: '', sectionPath: '', sectionTitle: '', paragraphIndex: -1 };
    const h = headingChainOf(range);
    return { success: true, exists: true, text: range.getString() || '', sectionPath: h.chain.map(function (c) { return c.text; }).join('/'),
             sectionTitle: h.chain.length ? h.chain[h.chain.length - 1].text : '', paragraphIndex: h.paragraphIndex };
  },
  check_link_anchors(p) {
    const names = Array.isArray(p && p.names) ? p.names.slice(0, 200) : [];
    const bms = xModel.getBookmarks(); const items = [];
    for (let i = 0; i < names.length; i++) {
      const n = String(names[i]); let exists = false, text = '';
      try { if (bms.hasByName(n)) { exists = true; text = bms.getByName(n).getAnchor().getString() || ''; } } catch (e) {}
      items.push({ name: n, exists: exists, text: text });
    }
    return { success: true, items: items };
  },
  adopt_legacy_links() {
    if (!isWriterDoc()) return bmFail(NOT_TEXT_DOC_MSG);
    const bms = xModel.getBookmarks(); const adopted = []; let skipped = 0;
    try {
      const en = xModel.getText().createEnumeration();
      while (en.hasMoreElements()) {
        const para = en.nextElement();
        if (!para.supportsService || !para.supportsService('com.sun.star.text.Paragraph')) continue;
        const pen = para.createEnumeration();
        while (pen.hasMoreElements()) {
          const portion = pen.nextElement(); let url = '';
          try { url = String(portion.getPropertyValue('HyperLinkURL') || ''); } catch (e) { continue; }
          const m = /filelink\?k=([A-Za-z0-9_%\-]+)/.exec(url); if (!m) continue;
          const key = decodeURIComponent(m[1]).replace(/[^A-Za-z0-9_]/g, '_');
          if (bms.hasByName(key)) { skipped++; continue; }
          const bm = xModel.createInstance('com.sun.star.text.Bookmark'); bm.setName(key);
          portion.getText().insertTextContent(portion, bm, true); adopted.push(key);
        }
      }
    } catch (e) { return bmFail('adopt_legacy_links failed: ' + errStr(e)); }
    return { success: true, adopted: adopted, skipped: skipped };
  },
  goto_bookmark(p) {
    const name = String((p && p.name) || ''); const range = anchorRange(name);
    if (!range) return bmFail('bookmark not found: ' + name);
    if (!selectVisibly(range)) return bmFail('could not select bookmark: ' + name);
    return { success: true };
  },
```
同一 run 被多个相邻 portion 拆开（格式变化）时，只有第一个 portion 会被套书签（后面的 `hasByName` 命中 skipped）——书签覆盖不全但锚点有效；lowa-e2e 断言只验 `exists` 与文字前缀。

- [ ] **Step 2: `set_hyperlink_at_anchor` 白名单**（:3150）：`if (!/^https?:\/\//i.test(url))` 保持，但这已放行 `https://checkba-internal.local/...`，无需改（后端 `doc_set_hyperlink` 的校验在 A5 放行）。核对即可。
- [ ] **Step 3: `node --check frontend/src/zetaoffice/public/office_thread.js`**；`npm run build:zetaoffice`；`diff src/zetaoffice/public/office_thread.js dist/zetaoffice/office_thread.js`
- [ ] **Step 4: Commit** `git commit -m "feat(lowa): EvidenceLink 书签锚点五原语 bookmark_selection/get_bookmark_context/check_link_anchors/adopt_legacy_links/goto_bookmark"`

### Task B2: lowa-e2e 新组「证据锚点」

**Files:**
- Modify: `frontend/tests/lowa-e2e/run.mjs`（新组放在现有最后一组之后；看 README 里组的写法与步数基线）

- [ ] **Step 1: 写组**（按 run.mjs 里既有 `group(...)`/`step(...)` 的辅助写；下面是逻辑）
```
1. load 空白 writer；type 三段："一、主体资格\n根据《营业执照》，收购人成立于2020年。\n（一）基本情况\n收购人注册资本1000万元。"
   第 1 段与第 3 段用 set_paragraph_format 设 headingLevel 1 / 2（既有原语）。
2. 选中「收购人成立于2020年」（find_text_locations → set_selection）；bookmark_selection {name:'EVID_TEST1'} → success；再调一次同名 → success:false 且 error 含 'exists'。
3. get_bookmark_context {name:'EVID_TEST1'} → exists:true, text 前缀 '收购人成立于', sectionPath === '一、主体资格', paragraphIndex === 1。
4. 选中第 4 段文字 → bookmark_selection 'EVID_TEST2' → get_bookmark_context.sectionPath === '一、主体资格/（一）基本情况'。
5. 光标移到 EVID_TEST1 内部（goto_bookmark 后按 Right），type '（有限合伙）'；check_link_anchors {names:['EVID_TEST1','EVID_TEST2']} → TEST1.text 含 '（有限合伙）'、TEST2.text 不变。
6. 选中 TEST2 整段文字删除（Backspace）；check_link_anchors → TEST2.exists === false（LO 会把空范围书签保留为点书签吗？实测：整段删除含书签范围时书签随之删除；若实测仍 exists 且 text==='' 则契约改为「exists && text==='' 视同 orphan」，把这条写进 ai-doc-bridge）。
7. 旧链接：选中 '注册资本' → set_selection_hyperlink {url:'https://checkba-internal.local/open?u=checkba%3A%2F%2Ffilelink%3Fk%3Dlk_old_1'} → adopt_legacy_links → adopted 含 'lk_old_1'；再调一次 → adopted 空、skipped 1；get_bookmark_context 'lk_old_1' → text === '注册资本'。
8. export_document → 重新 load 字节 → check_link_anchors ['EVID_TEST1','lk_old_1'] 都 exists（书签经 docx 往返）。
9. goto_bookmark 'EVID_TEST1' → success；get_ui_state 选区文字前缀 '收购人成立于'。goto_bookmark 'NOPE' → success:false, error 非空。
```
- [ ] **Step 2: 跑** `cd frontend && npm run test:lowa-e2e`（需要引擎：`LOWA_ENGINE_DIR` 指向兄弟树或 `node ../desktop/scripts/fetch-lowa-assets.js`）。Expected: 新组全绿，总步数基线 README 更新。
- [ ] **Step 3: 如第 6 步实测与预期不同，按实测改契约并记入 ai-doc-bridge.md。**
- [ ] **Step 4: Commit、PR、auto-merge；#103 落实记录。**

---

# 单元 C：拖拽建链 + method 小条 + 定位（#104）

### Task C1: 前端纯函数与 API 封装

**Files:**
- Create: `frontend/src/utils/anchorHash.js`、`frontend/src/utils/ulid.js`、`frontend/src/utils/evidenceLocator.js`
- Create: `frontend/tests/evidence/anchor-hash-vectors.json`（= 后端那份字节拷贝）、`frontend/tests/evidence/anchorHash.test.mjs`、`locator.test.mjs`
- Modify: `frontend/src/services/api.js`（在 `getDocFileLink` 旁）
- Modify: `frontend/package.json` scripts 加 `"test:evidence": "node --test tests/evidence/*.test.mjs"`

- [ ] **Step 1: 测试**
```js
// anchorHash.test.mjs
import test from 'node:test'; import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { normalizeAnchor, anchorHash } from '../../src/utils/anchorHash.js';
const vectors = JSON.parse(readFileSync(new URL('./anchor-hash-vectors.json', import.meta.url)));
test('normalize matches shared vectors', () => { for (const v of vectors) assert.equal(normalizeAnchor(v.in), v.norm); });
test('hash is 64 hex and whitespace-insensitive', async () => {
  assert.match(await anchorHash('abc'), /^[0-9a-f]{64}$/);
  assert.equal(await anchorHash('a b'), await anchorHash('ab'));
});
```
后端向量文件里**补一个 `hash` 字段**（A1 顺手：测试里由 Java 算出后写死），前端断言 `anchorHash(v.in) === v.hash`——这才是真正的双端对拍。A1 的 Step 1 向量文件改为含 `hash`，`AnchorHashTest` 也断言它。

```js
// locator.test.mjs
import { locatorSummary, parseFileLinkUrl } from '../../src/utils/evidenceLocator.js';
test('summary', () => {
  assert.equal(locatorSummary({ type: 'pdf', page: 3 }, (k, p) => k + JSON.stringify(p || {})), 'evidence.loc.page{"page":3}');
  assert.equal(locatorSummary(null, k => k), 'evidence.loc.wholeFile');
});
test('parse wrapped filelink with t', () => {
  const u = 'https://checkba-internal.local/open?u=' + encodeURIComponent('checkba://filelink?k=EVID_X&projectId=1&t=42');
  assert.deepEqual(parseFileLinkUrl(u), { linkKey: 'EVID_X', projectId: '1', targetId: 42 });
  assert.equal(parseFileLinkUrl('https://example.com'), null);
});
```
- [ ] **Step 2: 实现**
`anchorHash.js`：`normalizeAnchor` 同 Java 规则（`String.prototype.normalize('NFKC')`，`/\s|　/g` 删除，标点表同）；`anchorHash` 用 `crypto.subtle.digest('SHA-256')`（浏览器与 node 18+ 都有 `globalThis.crypto.subtle`），返回 Promise<string>。
`ulid.js`：同 Java 算法，`crypto.getRandomValues`。
`evidenceLocator.js`：
```js
export function locatorSummary(loc, t) {
  if (!loc || !loc.type) return t('evidence.loc.wholeFile');
  switch (loc.type) {
    case 'pdf': return loc.page ? t('evidence.loc.page', { page: loc.page }) : t('evidence.loc.wholeFile');
    case 'docx': return loc.quote ? t('evidence.loc.quote', { quote: String(loc.quote).slice(0, 20) }) : t('evidence.loc.wholeFile');
    case 'image': return loc.rect ? t('evidence.loc.region') : t('evidence.loc.wholeFile');
    case 'media': return t('evidence.loc.time', { time: fmtMs(loc.startMs) });
    case 'web': return loc.url ? t('evidence.loc.web', { host: hostOf(loc.url) }) : t('evidence.loc.wholeFile');
    case 'sheet': return t('evidence.loc.cell', { sheet: loc.sheet || '', cell: loc.cell || '' });
    default: return t('evidence.loc.wholeFile');
  }
}
export function parseFileLinkUrl(raw) { /* 解包 https://checkba-internal.local/open?u= → checkba://filelink?k=&projectId=&t= ；非法返回 null；t 解析为 Number 或 null */ }
export function buildFileLinkUrl(base, linkKey, projectId, targetId) { /* 反向 */ }
```
`api.js`：
```js
export const createEvidenceLink = (pid, body) => request({ url: `/api/projects/${pid}/evidence-links`, method: 'POST', data: body })
export const getEvidenceLink = (pid, key) => request({ url: `/api/projects/${pid}/evidence-links/${encodeURIComponent(key)}`, method: 'GET' })
export const listEvidenceLinks = (pid, params) => request({ url: `/api/projects/${pid}/evidence-links`, method: 'GET', data: params })
export const addEvidenceTargets = (pid, key, targets) => request({ url: `/api/projects/${pid}/evidence-links/${encodeURIComponent(key)}/targets`, method: 'POST', data: targets })
export const updateEvidenceTarget = (pid, id, patch) => request({ url: `/api/projects/${pid}/evidence-links/targets/${id}`, method: 'PATCH', data: patch })
export const removeEvidenceTarget = (pid, id) => request({ url: `/api/projects/${pid}/evidence-links/targets/${id}`, method: 'DELETE' })
export const deleteEvidenceLink = (pid, key) => request({ url: `/api/projects/${pid}/evidence-links/${encodeURIComponent(key)}`, method: 'DELETE' })
export const reportEvidenceAnchors = (pid, docFileId, reports) => request({ url: `/api/projects/${pid}/evidence-links/anchors/report`, method: 'POST', data: { docFileId, reports } })
export const keepEvidenceAnchor = (pid, key, text) => request({ url: `/api/projects/${pid}/evidence-links/${encodeURIComponent(key)}/keep`, method: 'POST', data: { text } })
export const rebindEvidenceLink = (pid, key, body) => request({ url: `/api/projects/${pid}/evidence-links/${encodeURIComponent(key)}/rebind`, method: 'POST', data: body })
export const evidenceRefCounts = (pid, fileIds) => request({ url: `/api/projects/${pid}/evidence-links/ref-counts`, method: 'GET', data: { fileIds: fileIds.join(',') } })
```
`request` 的名字以 api.js 里既有封装为准（看 `createDocFileLink` 怎么写，照抄）。
- [ ] **Step 3: `npm run test:evidence` 绿；Commit。**

### Task C2: 拖到编辑器建链 + method 小条

**Files:**
- Create: `frontend/src/pages/project-overview/evidenceLinkActions.js`（mixin 形态，与 `stagingArea.js` 同款导出 `{ data(), methods }`）
- Create: `frontend/src/components/EvidenceMethodBar.vue`
- Modify: `frontend/src/components/LibreOfficeEditor.vue`：最外层 `<view class="libre-root">` 加 `@dragover.prevent="onEvidenceDragOver" @dragleave="onEvidenceDragLeave" @drop.prevent="onEvidenceDrop"`，拖拽中加类 `evidence-drop-armed`（描边）；`emits` 加 `'evidence-drop'`，drop 时 `this.$emit('evidence-drop', { file })`（文件 JSON 来自 `e.dataTransfer.getData('application/x-checkba-file')`，文件夹忽略）。
- Modify: `project-overview.vue`：删除 `FileLinkDropZone` 的 import/注册/模板/`onFileLinkZoneDrop`/`createWpsSelectionFileLink`；两处 `<LibreOfficeEditor>` 加 `@evidence-drop="onEvidenceDrop($event, 'left'|'right')"`；`openFileLinkTarget` 与两处 `getDocFileLink` 解包改调新模块。
- Delete: `frontend/src/components/FileLinkDropZone.vue`
- Modify: locales：`workbench.evidence.*`（selectFirst「先选中要关联的文字」、linked「已关联《{name}》」、method.written_review 等五个、dropHint「松开即关联到选中文字」）
- Test: `frontend/tests/evidence/evidenceLinkActions.test.mjs`（把 `createEvidenceLinkForDrop` 抽成可注入 `exec/api` 的纯函数测试）

- [ ] **Step 1: 纯函数 + 测试**
```js
// evidenceLinkActions.js（节选，可测部分）
export async function createEvidenceLinkForDrop({ exec, api, projectId, docFileId, file, wrap, internalBase, t }) {
  const cur = await exec('get_selection_hyperlink', {});
  const selText = cur && cur.success ? String(cur.text || '').trim() : '';
  if (!selText) return { ok: false, reason: 'no_selection' };
  let linkKey = ''; const parsed = parseFileLinkUrl(cur.url || '');
  if (parsed && parsed.linkKey) linkKey = parsed.linkKey;
  let created = false;
  if (!linkKey) {
    linkKey = 'EVID_' + ulid();
    const bm = await exec('bookmark_selection', { name: linkKey });
    if (!bm || !bm.success) return { ok: false, reason: 'bookmark_failed', message: bm && bm.error };
    const url = wrap(`${internalBase}?k=${encodeURIComponent(linkKey)}&projectId=${encodeURIComponent(String(projectId))}`);
    const r = await exec('set_selection_hyperlink', { url });
    if (!r || !r.success) return { ok: false, reason: 'hyperlink_failed', message: r && r.message };
    created = true;
  }
  const ctx = await exec('get_bookmark_context', { name: linkKey });
  const target = { fileId: Number(file.id), relation: 'supports', method: 'written_review' };
  const view = created
    ? await api.createEvidenceLink(projectId, { docFileId, linkKey, anchorText: selText, sectionPath: ctx && ctx.sectionPath || '', sectionTitle: ctx && ctx.sectionTitle || '', createdByKind: 'human', targets: [target] })
    : await api.addEvidenceTargets(projectId, linkKey, [target]);
  const tgt = (view.targets || []).find(x => Number(x.fileId) === Number(file.id)) || null;
  return { ok: true, linkKey, view, targetId: tgt && tgt.id };
}
```
测试：mock `exec` 按 action 返回；断言无选区 → `no_selection`；新建走 `bookmark_selection`+`set_selection_hyperlink`+`createEvidenceLink`；选区已带 `filelink?k=` → 只 `addEvidenceTargets`；bookmark 失败不调 api。
- [ ] **Step 2: `EvidenceMethodBar.vue`**：props `{ visible, fileName, method, targetId }`，emits `['change','close']`；五个 chip，`@tap` emit `change({targetId, method})`；内部 3s 定时器 `close`（每次 props 变化重置）。样式：浅色、编辑器底部悬浮、`z-index` 高于画布，不遮审阅面板。
- [ ] **Step 3: 接线**：`onEvidenceDrop({file}, side)` → 调纯函数（exec = 该侧 `libreOfficeExecutor`；docFileId = 该侧 activeFile.id）→ toast 或显示 method bar；`change` → `updateEvidenceTarget` + `uni.$emit('awd:evidence-changed', {docFileId})`。
- [ ] **Step 4: `npm run check:emits`、`npm run test:evidence`；dev H5 真渲染走查（`ui-live-walkthrough-recipe` 记忆配方）：拖文件到编辑器 → 无选区 toast → 选中后拖 → 小条出现 → 点「访谈」→ 网络面板看到 PATCH。**
- [ ] **Step 5: Commit** `git commit -m "feat(evidence): 拖到编辑器即建书签+链接+入库，method 浮动小条；下线 FileLinkDropZone"`

### Task C3: 点击链接定位 + openFile(locator)

**Files:**
- Modify: `fileOpenTabs.js` `openFile(file, opts = {})`：`opts.locator` 存进 tab 对象 `pendingLocator`（`targetList.push({ ...file, pendingLocator: opts.locator || null })`；已打开时 `existing.pendingLocator = opts.locator || null`）。
- Modify: `project-overview.vue` 两处链接解包：`parseFileLinkUrl` → `getEvidenceLink` → 选 target（`t` 命中 / 单 target / 弹窗列 targets 显示 `file.name + locatorSummary`）→ `openFileLinkTarget(target)`：`openFile(file, { locator: target.locator })`。
- Modify: `LibreOfficeEditor.vue`：`ready` 后与 `file.pendingLocator` 变化时 `consumeLocator()`：`bookmark` → `goto_bookmark`；否则 `quote` → `find_text_locations {query: quote, limit: 1}` → `set_selection {anchor}`；消费后 `this.$emit('locator-consumed', file.id)` 由宿主清空。
- Modify: `FilePreview.vue`：props 加 `locator`；pdf 分支 `:src="blobUrl + (locator && locator.type==='pdf' && locator.page ? '#page=' + locator.page : '')"`；image 分支在图片容器里 `<view v-if="rectStyle" class="evidence-rect" :style="rectStyle">`（`rectStyle` 由 `locator.rect` × 当前渲染尺寸算，随缩放 watch）；audio/video：`loadedmetadata` 后若 `locator.startMs` → `currentTime = startMs/1000`。
- Modify: `agentClientActions.js` `handleEditorOpenFile`：透传 `action.locator`（后端 A5 已允许字段，P1 才真发）。

- [ ] **Step 1: 实现以上；`check:emits`。**
- [ ] **Step 2: dev 真渲染走查**：造一条 pdf target `{type:'pdf',page:2}` → 点击文档链接 → 右侧 pdf 打开在第 2 页；图片 rect 画框可见；音频从 startMs 起播。
- [ ] **Step 3: Commit、rebase 到含 A/B 的 master、PR、auto-merge；#104 落实记录。**

---

# 单元 D：审阅面板「证据」页 + stale 弹窗（#105）

### Task D1: EvidencePanel 数据与分组（纯函数先行）

**Files:**
- Create: `frontend/src/utils/evidenceGrouping.js`、`frontend/tests/evidence/grouping.test.mjs`
- Create: `frontend/src/components/EvidencePanel.vue`
- Modify: `ReviewPanel.vue`：tab 三枚（`editor.review.evidenceTab` 「证据 {count}」），`tab==='evd'` 时渲染 `<EvidencePanel :executor :project-id :doc-file-id @locate="$emit('locate', $event)" />`；emits 加 `'locate'`。
- Modify: `LibreOfficeEditor.vue`：`<ReviewPanel>` 传 `:project-id :doc-file-id="file.id"`，`@locate="onEvidenceLocate"`（`this.$emit('open-evidence-target', payload)` 给宿主去 openFile）。
- Modify: `project-overview.vue`：`@open-evidence-target` → `openFileLinkTarget(target)`。

- [ ] **Step 1: 分组纯函数 + 测试**
```js
export function groupBySection(links) { /* Map<sectionPath||'__none__', links[]>，按首次出现顺序；返回 [{key, title, items}] */ }
export function groupByParty(links, fileTagsById /* Map<fileId, tags[]> */) { /* 每个 link 按其 targets 的 PARTY 标签落组（可多组）；无 PARTY → '__none__' */ }
export function filterByStatus(links, status /* 'all'|'unverified'|'stale'|'orphan' */) {}
```
测试：三条 link，两条同 sectionPath；一条 target 文件挂两个 PARTY → 出现在两组；status 过滤。
- [ ] **Step 2: EvidencePanel.vue**：顶部 segmented「按章节 / 按主体」+ 状态下拉；列表按 D1 分组；卡片见 SPEC §4.3；动作：
  - 点卡片 → `executor.executeCommand('goto_bookmark', {name: link.linkKey})`
  - 点 target → emit `locate({ fileId, locator })`
  - 「保留关联」（stale）→ `check_link_anchors([key])` 取 text → `keepEvidenceAnchor`
  - 「重新指定」（orphan）→ 进入 rebinding 态：提示「选中新文字后点确认」→ 确认时 `bookmark_selection(EVID_new)` + `set_selection_hyperlink` + `rebindEvidenceLink`
  - 「删除」→ `deleteEvidenceLink`；target 行方法 chip → `updateEvidenceTarget`；target 删 → `removeEvidenceTarget`
  - 数据：`mounted` 与 `uni.$on('awd:evidence-changed')` 时 `listEvidenceLinks({docFileId})`；主体视图需要 `tags`：用 `getProjectFiles(pid, null, true)` 已带 `tags` 的树一次取 Map（文件树已有缓存则复用 props 传入）。`beforeUnmount` `$off`。
  - PARTY 判定用 `normalizeTagType(tag.type) === 'PARTY'`（`utils/tagTypes.js`）。
- [ ] **Step 3: locales**：`editor.review.evidenceTab`、`evidence.view.bySection/byParty`、`evidence.status.active/unverified/stale/orphan`、`evidence.action.keep/rebind/delete/confirmRebind`、`evidence.group.none`、`evidence.loc.*`。
- [ ] **Step 4: `check:emits`、`test:evidence`；dev 走查：建两条 link → 打开面板 → 切视图 → 点 target 定位。Commit。**

### Task D2: stale 检测 + 弹窗

**Files:**
- Create: `frontend/src/components/EvidenceStaleBar.vue`
- Create: `frontend/src/utils/evidenceStaleQueue.js` + `tests/evidence/staleQueue.test.mjs`（合并规则纯函数）
- Modify: `LibreOfficeEditor.vue`

- [ ] **Step 1: 合并规则纯函数 + 测试**
```js
export class StaleQueue {
  constructor({ now = () => Date.now(), windowMs = 3000 } = {}) { this.now = now; this.windowMs = windowMs; this.ignored = new Set(); this.lastShown = new Map(); this.pending = new Map(); }
  offer(linkKey, text) { if (this.ignored.has(linkKey)) return false; const t = this.now(); const last = this.lastShown.get(linkKey) || 0; if (t - last < this.windowMs) return false; this.pending.set(linkKey, text); return true; }
  flush() { const items = [...this.pending.entries()].map(([linkKey, text]) => ({ linkKey, text })); const t = this.now(); for (const i of items) this.lastShown.set(i.linkKey, t); this.pending.clear(); return items; }
  ignore(linkKey) { this.ignored.add(linkKey); }
}
```
测试：同 key 3s 内 offer 两次只入队一次；ignore 后不入队；flush 合并多条。
- [ ] **Step 2: LibreOfficeEditor 接线**：
  - `onDocModified` 里除 autosave 外起 `_staleTimer = setTimeout(runAnchorCheck, 3000)`（每次 modified 重置）；`flushSave` 前也 `runAnchorCheck()`。
  - `runAnchorCheck`：若 `_cmdBusy > 0` 则 1s 后重试；取 `this._evidenceCache`（`listEvidenceLinks({docFileId})` 在 ready 后 + `awd:evidence-changed` 时刷新）；按 200 分批 `check_link_anchors`；对每条：`!exists` → report orphan；`await anchorHash(text) !== link.anchorHash && link.status !== 'stale'` → report stale + `queue.offer(key, text)`；一次 `reportEvidenceAnchors(pid, docFileId, reports)`；有变化 → 更新缓存 + `uni.$emit('awd:evidence-changed')` + `staleItems = queue.flush()` 显示 bar。
  - ready 后首轮：先 `adopt_legacy_links`（只对 writer 文档），再 `runAnchorCheck()`。
- [ ] **Step 3: EvidenceStaleBar.vue**：props `{ items }`，单条显示「这段文字有 N 份底稿，文字已改动」（N = link.targets.length），多条「N 段文字已改动」可展开；按钮 [保留关联]（→ `keepEvidenceAnchor` 全部 / 单条）[查看底稿]（→ emit `locate` 首个 target）[忽略]（→ `queue.ignore`）。非阻塞：`position:absolute; top:0` 叠在编辑器顶部，不抢焦点。
- [ ] **Step 4: lowa-e2e 不测此层（它在宿主）；app-e2e 不新增旅程，走 dev 真渲染走查：建 link → 改字 → 3s 内弹条 → 保留 → 面板变绿；删段 → 面板红。Commit、PR、auto-merge；#105 落实记录。**

---

# 单元 E：SDK 三方法（#106）

### Task E1: SDK 与宿主

**Files:**
- Modify: `sdk/plugin-sdk/awd-plugin-sdk.js`（方法表注释 + `awd.evidence = { link, list, locate }` 三个 `call` 包装）
- Modify: `examples/hello-web-plugin/awd-plugin-sdk.js`（字节拷贝）
- Modify: `frontend/src/components/PluginPane.vue` `handleCall` 三个 case
- Modify: `docs/PLUGIN_SPEC.md` §8 方法表
- Test: 既有 SDK parity 测试（grep `sha256` in frontend/tests 或 scripts，找到比对三份副本的那条）照常；`frontend/tests/evidence/pluginEvidence.test.mjs` 测宿主端纯函数 `resolveAnchor`

- [ ] **Step 1: 宿主端纯函数 + 测试**
```js
// frontend/src/utils/pluginEvidence.js
export async function resolveAnchor(exec, anchor) {
  if (anchor && anchor.selection === true) {
    const cur = await exec('get_selection_hyperlink', {}); const text = cur && cur.success ? String(cur.text || '').trim() : '';
    if (!text) return { error: { code: 'no_selection', message: '编辑器当前没有选区' } };
    return { mode: 'selection', text, existingUrl: cur.url || '' };
  }
  if (anchor && typeof anchor.quote === 'string' && anchor.quote.trim()) {
    const r = await exec('find_text_locations', { query: anchor.quote.trim(), limit: 2 });
    const n = r && Array.isArray(r.matches) ? r.matches.length : 0;
    if (n !== 1) return { error: { code: 'anchor_ambiguous', message: n === 0 ? '引文未命中' : '引文命中多处' } };
    return { mode: 'quote', anchorId: r.matches[0].anchor, text: r.matches[0].text || anchor.quote.trim() };
  }
  return { error: { code: 'anchor_ambiguous', message: 'anchor 需为 {selection:true} 或 {quote}' } };
}
```
（`find_text_locations` 返回字段名以 office_thread.js :1665-1700 为准，先读再写。）
- [ ] **Step 2: 宿主 case**
```js
case 'evidence.list': {
  if (!this.hasPermission('file_read')) return this.denied('file_read')
  const docFileId = params.docPath ? await this.fileIdByPath(params.docPath) : this.activeDocFileId()
  const q = params.path ? { fileId: await this.fileIdByPath(params.path) } : { docFileId, status: params.status, sectionPath: params.sectionPath }
  if (!q.fileId && !q.docFileId) return { ok: false, error: { code: 'no_active_document', message: '没有打开的文档' } }
  const links = await listEvidenceLinks(this.projectId, q)
  return { ok: true, result: { links: links.map(l => this.toPluginLink(l)) } }   // path 由 fileId 反查（复用 listProjectFiles 的 path 表）
}
case 'evidence.link': {
  if (!this.hasPermission('editor')) return this.denied('editor')
  const exec = this.activeExecutor(); if (!exec) return { ok:false, error:{ code:'no_active_document', message:'没有打开的文档' } }
  const a = await resolveAnchor(exec, params.anchor); if (a.error) return { ok:false, error:a.error }
  if (a.mode === 'quote') await exec('set_selection', { anchor: a.anchorId })   // 让后续 bookmark_selection 有选区
  // 复用 C2 的 createEvidenceLinkForDrop 的核心，但 targets 多个且带 locator/relation/method/note；createdByKind:'plugin'
  ...
  return { ok:true, result:{ linkKey, targetIds } }
}
case 'evidence.locate': {
  if (!this.hasPermission('editor')) return this.denied('editor')
  const link = await getEvidenceLink(this.projectId, String(params.linkKey)).catch(() => null)
  if (!link) return { ok:false, error:{ code:'not_found', message:'链接不存在' } }
  const tgt = params.targetId ? link.targets.find(t => t.id === Number(params.targetId)) : null
  if (tgt) uni.$emit('awd:open-evidence-target', { fileId: tgt.fileId, locator: tgt.locator }); else await this.activeExecutor()('goto_bookmark', { name: link.linkKey })
  return { ok:true, result:{} }
}
```
`activeExecutor()`/`activeDocFileId()`：PluginPane 今天拿不到编辑器——需要宿主（project-overview）通过 props 注入 `getActiveEditor()`（返回 `{executor, fileId}`），与 `VariablePanel` 拿 `getEditor` 适配器的既有做法一致（grep `getEditor` 在 project-overview.vue）。`awd:open-evidence-target` 由 project-overview 监听 → `openFileLinkTarget`。
- [ ] **Step 3: SDK 文件**：注释方法表加三行；`awd.evidence = { link: p => call('evidence.link', p), list: p => call('evidence.list', p), locate: p => call('evidence.locate', p) }`；错误码注释加 `anchor_ambiguous / no_selection / not_found / no_active_document`。拷贝到 examples。
- [ ] **Step 4: `docs/PLUGIN_SPEC.md` §8 表加三行；跑既有 SDK 副本一致性测试 + `test:evidence`；Commit、PR。**

### Task E2: 官网仓同步（Sonnet 可做，主模型验收）

在 `aiworkdeckweb` 仓：`lib/plugin-template.ts` 内联 SDK 更新为同字节；模板的宿主模拟器（`?type=web` 五件套里的 mock host）给三方法假实现（`evidence.link` 返回 `{linkKey:'EVID_MOCK', targetIds:[1]}`、`list` 返回空数组、`locate` 返回 `{}`）；开 PR。验收：桌面仓 SDK 一致性测试对官网文件 sha256 一致（测试怎么拿官网文件看既有实现）。

---

# 单元 F：稳定性 #1 #2 #6（#107）

### Task F1: 导入硬顶

**Files:** `LocalProjectService.java:40,195-197,351,397`；前端对账结果消费处（grep `truncated` in frontend/src）；Test `LocalProjectServiceImportCapTest.java`

- [ ] `MAX_IMPORT_ENTRIES = 30000`；超出时把 `truncated=true, truncatedCount=N` 放进对账返回 DTO（看 `reconcileProject` 返回类型，加两个字段）；前端拿到 `truncated` 时 `uni.showModal`「本次导入已截断，N 项未纳入：目录超过 30000 项，请拆分项目」一次（按 projectId 记 sessionStorage 防重复）。
- [ ] 测试：临时目录造 30001 个空文件（`Files.createFile` 循环，1-2s）→ reconcile → 入库 30000 + truncated；造 3001 个 → 全部入库、truncated=false。
- [ ] Commit。

### Task F2: 新建文件 O(N²) + 同名自动编号

**Files:** `ProjectFileService.java:91-96, 224-229`；`ProjectFileRepository.java`（加 `@Query("select max(f.sortOrder) from ProjectFile f where f.projectId=:pid and ((:parent is null and f.parentId is null) or f.parentId=:parent)") Integer maxSortOrder(...)`）；Test `ProjectFileServiceCreateConflictTest.java`

- [ ] `createFile(..., ConflictPolicy policy)`：新增重载，旧签名委托 `FAIL`（行为不变）；`RENAME` 时循环 `name (n)`（扩展名前插入）直到 `existsByProjectIdAndParentIdAndNameAndIdNotAndIsDeletedFalse` 为 false，上限 1000；`createOrUpdateFile` 不动。`createFolder` 同样改 `maxSortOrder` 单查。
- [ ] 把后端内部的「截图/导出落盘」调用点（`MeetingRecordingService.exportTranscript` 的 `uniqueName`、`AiDocxExportService`、`PdfTools` pdf_to_word）改成传 `RENAME`，各自手写的 uniqueName 删掉。
- [ ] 测试：mock repo，`existsBy...` 对 `a.pdf`、`a (1).pdf` 返回 true → 得 `a (2).pdf`；`maxSortOrder` 被调用且 `findByProjectIdAndParentIdOrderBySortOrderAsc` **never**。
- [ ] Commit。

### Task F3: 文件树懒加载 + 窗口化

**Files:** `FileTree.vue:1292, 422, 561, 1746-1830`；后端 `ProjectFileController.java:67-93`（`?parentId=` 已支持单层？先看）；Test：`frontend/tests/evidence/treeBuild.test.mjs`（把 `buildTreeView` 的分组逻辑抽到 `utils/fileTreeBuild.js` 测）

- [ ] `buildTreeView`：先 `const byParent = new Map()` 一次分组，递归只取 `byParent.get(id)`；排序比较器不变。测试：1000 节点构建 < 50ms（断言调用 `filter` 次数为 0 更直接：用抽出的纯函数计数）。
- [ ] 懒加载：`getProjectFiles(pid, null, true)` 仍整棵拉（后端一次下发可接受，盘点结论），但**渲染**改为：每个展开文件夹的子项超过 100 时只渲染前 100 + 「展开更多（还有 N 项）」行，点一次加 200。这是窗口化的最小形态，不引入第三方虚拟列表（uni 下稳定性优先）。
- [ ] 「被引用 N 次」角标：树刷新后对当前已渲染的文件 id 分批 `evidenceRefCounts`，结果存 `refCounts` Map，文件行右侧小灰字 `引用 N`（N>0 才显示）。
- [ ] app-e2e 现有旅程跑一遍不红（J 系列涉及文件树的）；Commit、PR；#107 落实记录。

---

# 单元 G：稳定性 #3 #4 #5 + 大文档基线组（#108）

### Task G1: 大文档夹具与基线组（先建尺子）

**Files:** `frontend/tests/lowa-e2e/fixtures/gen-big-doc.py`、`frontend/tests/lowa-e2e/big-doc.mjs`、`package.json` 加 `"test:lowa-big": "LOWA_E2E_BIG=1 node tests/lowa-e2e/big-doc.mjs"`、README

- [ ] `gen-big-doc.py`：按稳定性盘点附录 A（150 页 / 每页 1 个二级标题 + 4 段约 220 字 + 分页符 / 随机 30 页 12x5 表 / 随机 20 页 900x600 噪声 JPEG q75）；固定 `random.seed(20260821)`；输出到 `$TMPDIR/awd-big-doc/big.docx`（不入库）。依赖 `python3 -m pip install --user python-docx pillow`，脚本开头检查并给出安装命令。
- [ ] `big-doc.mjs`：复用 run.mjs 的 server/puppeteer 启动（把那段抽成 `tests/lowa-e2e/_boot.mjs` 共用），加载 big.docx，逐项计时（`performance.now()` 在 `page.evaluate` 内，结果裁掉大字段再跨界），**每项跑 3 次取中位数**，输出表格，并与阈值比较：
  | 项 | 硬阈 |
  |---|---|
  | load_document | < 15s |
  | get_document_text 第 2 次（同参数） | < 300ms（G3 后） |
  | find_replace 修订 150 命中 | < 8s（G2 后） |
  | apply_house_style 921 段 | 不超时（< 120s）且 `truncated === false`（G2 后） |
  | export_document | < 10s |
  | 连续 30s 无 modified（导出后） | 0 次 |
  未达阈值 → 进程退出码 1 并打印实测；**先跑一次记录改造前基线**写进 README。
- [ ] Commit `test(lowa): 大文档基线组（150 页/30 表/20 图，三次中位数）`。

### Task G2: 批量命令分批 + 进度 + 分级超时

**Files:** `office_thread.js` `find_replace`(:1632-1645)、`apply_house_style`(:2946-2984)、`resolve_all_revisions`(:3492)；`libreofficeExecutorClient.js:189-191`、`zetaOfficeRelay.js:108-112`（超时表）；`EditorBridgeService.java:66,286`（按 action 分级）；`DocumentEditTools.java` 对应工具描述补「大量命中会分批，返回 progress」

- [ ] worker：`find_replace` 去掉每命中 `selectVisibly`，结束只滚到首处；命中 > 50 时按 30 一批处理，每批后 `postMessage({type:'progress', reqId, done, total})`（relay 已有 `type` 分发，加一个 `progress` 分支转发给宿主 `onProgress(reqId)` 回调；宿主在 AI 过程卡上显示「第 x/y 处」）；`p.cancelToken` 检查：宿主可 `executeCommand('cancel', {reqId})` 置位 `cancelled[reqId]`，批间检查即停，返回 `{success:true, cancelled:true, done}`。
- [ ] `apply_house_style`：按 500 元素一批，批间 `await` 一个宏任务让 export/progress 有机会；`truncated` 字段永远返回（false/true）；去掉 5000 硬顶（改为分批到底）。
- [ ] `resolve_all_revisions`：前后只各数一次 redline（原本就只 dispatch 一次，主要是 `countRedlines` 两次 O(N) —— 保留，但 `list_revisions` 加 `since` 游标不在本期）。
- [ ] 超时：前端两处超时表加 `find_replace/apply_house_style/resolve_all_revisions/insert_table: 120000`；后端 `EditorBridgeService` 加 `static final Map<String,Integer> ACTION_TIMEOUT_SECONDS = Map.of("doc_open_file_sync",180,"find_replace",120,"apply_house_style",120,"resolve_all_revisions",120,"export_document",180)`，`future.get` 用它，默认 30。**构造器不变**（EvalHarness 免改）。
- [ ] 测试：`EditorBridgeServiceTest` 加「find_replace 超时 120s」的反射断言；lowa-e2e 既有组 + 大文档组：150 命中 < 8s。
- [ ] Commit。

### Task G3: 段落索引缓存

**Files:** `office_thread.js` `eachParagraph`(:169-178)、`get_document_text`(:2451-2477)、`get_paragraph/modify_paragraph`(:1744-1775)、modified 监听器(:1449)

- [ ] 新增 `paraIndex = { ranges: null, total: 0 }`；`buildParaIndex()` 一次枚举把每段 `XTextRange`（段落对象本身）推进数组；`invalidateParaIndex()` 在 `modified` 监听器、`load_document`、`undo/redo`、所有写原语末尾调用（写原语统一走 `verifySnapshot()` 的话在那里挂一次即可——先 grep 确认写原语都调它）。
- [ ] `get_document_text`：`startParagraph` 直接从 `paraIndex.ranges[start]` 起读；`total = paraIndex.total`。`get_paragraph/modify_paragraph/select_paragraph` 同样走索引。
- [ ] 风险：段落对象在文档被编辑后可能失效（UNO 对象引用被销毁抛异常）——任何一处读到异常就 `invalidateParaIndex()` 重建一次再读，仍失败才返回错误。
- [ ] lowa-e2e 既有组全绿（段落号 0 基回归）；大文档组 `get_document_text` 二次 < 300ms。
- [ ] Commit；**rebase 到含 B 的 master**（同文件）；PR、auto-merge；#108 落实记录。大文档组改造前后两组数字写进 README 与交付报告。

---

# 收尾（P0 定稿，自己做不外包）

- [ ] 七个 PR 全合并后在 master 上：`mvn -q test` 全量、`npm run check:emits`、`npm run test:evidence`、`npm run test:lowa-e2e`、`npm run test:lowa-big`、`npm run test:app-e2e`（与基线失败集对照，见 `app-e2e-j1-login-flake` 记忆）。
- [ ] `/code-review` 级对抗复核：对 A（状态机/权限/迁移）、B（书签往返）、D（stale 误报）、G（索引失效）各起一轮「找→反驳→确认」。
- [ ] 「还原病灶即转红」抽查：注释掉 `reportAnchors` 的 orphan 分支跑 `EvidenceLinkServiceTest`；把 `anchor-hash-vectors.json` 的一条 hash 改一位跑 `AnchorHashParityTest` 与 `anchorHash.test.mjs`；把 `MAX_IMPORT_ENTRIES` 改回 3000 跑 `LocalProjectServiceImportCapTest`。三处都必须转红。
- [ ] `.claude/agents/ai-doc-bridge.md` 契约段、`doc-editor.md`（新原语 + 段落索引缓存地雷）、`plugin-system.md`（SDK 三方法）、`utility-tools.md`（FilePreview locator）、`sidebar-shell.md`（FileLinkDropZone 下线、编辑器 drop）已随各 PR 更新；核对一遍。
- [ ] #102-#108 全部「待复测」并带复测提示；母卡 #100 评论 P0 小结（PR 列表 + 大文档改造前后数字 + 真机走查清单）。
- [ ] 记忆：写 `evidence-link-p0-shipped.md`（契约地雷 + 实测数字 + 走查清单位置）。
