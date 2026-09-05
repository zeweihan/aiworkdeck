# 项目级版本记录 第 2 期 实施计划（看得懂差异）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 律师能看懂两版之间改了什么——主路径是带 Word 修订标记的对比稿；同时补齐 AI 署名、重要版本、单文件历史，并修掉退回不通知编辑器重载的安全隐患。

**Architecture:** 后端在 `com.checkba.version` 上加三个只读端点（按版本取字节/取文本、按文件过滤时间线）与两个写端点（打里程碑标签、AI 轮次落版）。worker 层把第 0 期探针验证过的 `.uno:CompareDocuments` 变成生产 action。前端新增一个**独立的**只读对比 webview 宿主（绝不复用带自动保存的 `LibreOfficeEditor`），加上文本对比降级与三处入口接线。

**Tech Stack:** JGit / Spring Boot 3.2.4 / Tika（文本抽取，复用既有）/ LOWA zetajs / uni-app Vue3

**Spec:** `docs/superpowers/specs/2026-07-28-project-git-version-control-design.md`（5.7 差异呈现、5.3 署名与重要版本、5.8 单文件历史）
**领域文档（先读）:** `.claude/agents/version-control.md` —— 九条已知地雷全部适用于本期。

## Global Constraints

- **历史永不重写**；退回=新建版本。本期所有新端点都是只读或追加（tag 是追加引用，不改提交）。
- **界面零 Git 术语**（含中文直译「提交/分支/合并/主线」）。本期新文案：对比 / 和上一版对比 / 重要版本 / 这份文件的历史。
- **失败不阻断主流程**：AI 落版、编辑器重载通知的任何异常只记日志。
- **`.awd/` 对律师与 AI 不可见**：新增的 path 参数端点必须拒绝 `.awd/` 前缀；返回文件列表处照旧过滤。
- **权限**：所有新端点走 `requireMember`（`ProjectMemberService.hasReadPermission/isClient` 参数序是 **`(projectId, userId)`**——写反能编译能过 Mockito 单测，真 bean 下权限整体失效，领域文档地雷 3）。
- **对比宿主绝不接自动保存**：历史字节一旦被 autosave 写回真文件就是数据事故。`VersionCompareTab` 不得引入任何保存/上传路径。
- **改 `AgentOrchestrator` 构造依赖必须同步 `EvalHarness.java:162` 的手工构造**（已踩两次的雷）。
- **全上下文测试必须 `@ActiveProfiles("desktop")`**，不得依赖开发机 Postgres；不得新建 classpath 根的测试配置文件。
- 本机 mvn 必须 JDK 21：`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn ...`（默认 25 会 SIGBUS）。前端 npm。
- **全局禁 emoji**；commit message 末尾 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`；显式路径提交，不要 `git add -A`。
- 分支：继续在 `claude/project-git-version-control-edc4d1` 上叠（PR #214 未合并，第 2 期并入同一 PR）。

## 已验证的事实（写计划时对过真实代码，实施者不必重查）

- 第 0 期探针（lowa-e2e 组 13）已实证：`.uno:CompareDocuments` 在 WASM 下可用；**当前文档载入新版、旧版字节作 `URL` 参数** → 产出「旧→新」的修订（删旧字插新字），正文停在新版。方向不用对调。
- `office_thread.js` 有 `setRedlineAuthor(name)`（:64）与 `mkProp`（:36）；MEMFS 写文件的可用写法在 :976-988（`SimpleFileAccess` + `SequenceInputStream.createStreamFromSequence`）；字节编组必须 `Array.from(new Int8Array(...))`。
- `EDITOR_ACTIONS` 白名单在 `frontend/src/composables/libreofficeExecutorClient.js:15`。
- webview 宿主模式（`LibreOfficeEditor.vue:124-215`）：`window.checkbaDesktop.zetaoffice.getEditor()` → `{url, preload, partition}` → 建 `<webview partition preload webpreferences="contextIsolation=yes,nodeIntegration=no">` → `dom-ready` 后 `createWebviewEditorExecutor(webviewEl, opts)`（`useZetaOfficeWebview.js:54`）。`ipc-message` 通道 `lo-relay`，`{type:'modified'}` 是自动保存信号——对比宿主**忽略它**。
- `ProjectFile.filePath` 格式 `projects/{projectId}/{...}/{name}`（`ProjectFileService.buildPhysicalPath` :155-157），仓库相对路径 = 去掉 `projects/{projectId}/` 前缀。
- 文本抽取：`FileController.extractDocumentText`（:564）用 Tika；本期需要一个**基于字节**的变体。
- `VersionEntry` 生产构造点只有 `ProjectRepoService.toEntry`（:164）一处。
- `AgentOrchestrator` 是 `@RequiredArgsConstructor` + 18 个 final 依赖；轮次结束点 = `handleUserMessage` 里 `runLoop(...)` 正常返回之后（:304 附近）。`EvalHarness.java:162` 手工 `new AgentOrchestrator(15 个参数)`。
- `EditorBridgeService.sendReloadFileAction(ProjectFile)` 存在（`DocumentCheckpointService.restore` :83 在用），会让前端编辑器重载该文件。
- DocDiffViewer props：`sourceId/targetId/sourceName/targetName`，内部自己拉 `/api/files/compare`。
- diff 类标签页模式：`fileOpenTabs.js` 的 `openDiffTab`（tabType:'diff' 虚拟标签 + `isDiffTab` 分支渲染，project-overview :753/:820 两窗格各一处）。
- FileTree 右键菜单：`context-menu-item` view + `@tap="handler(contextMenu.targetItem); closeContextMenu()"` 模式（:258-300）。

## 文件结构

**后端**（全部在既有文件上加，不新建类，除测试）

| 文件 | 改动 |
|---|---|
| `ProjectRepoService.java` | +`logForPath`、`tagMilestone`、`listMilestones`；`toEntry` 带 milestone |
| `VersionEntry.java` | +`String milestone` 字段（末位） |
| `WorkSessionService.java` | +`commitAiRound`、`repoRelativePath` 静态助手、`safeRepoPath` 校验、revertTo 通知编辑器重载、endSession 空段提示 |
| `VersionController.java` | +4 个端点（file-bytes / file-text / milestone / timeline?fileId=） |
| `AgentOrchestrator.java` | +`WorkSessionService` 依赖 + 轮次结束钩子 |
| `EvalHarness.java` | 构造器同步 |

**worker 与前端**

| 文件 | 改动 |
|---|---|
| `frontend/src/zetaoffice/public/office_thread.js` | +`compare_document` 生产 action |
| `frontend/src/composables/libreofficeExecutorClient.js` | 白名单 +`compare_document` |
| `frontend/src/components/version/VersionCompareTab.vue` | 新建：只读对比 webview 宿主 |
| `frontend/src/components/DocDiffViewer.vue` | +versionSpec 模式（文本降级） |
| `frontend/src/components/version/VersionNodeDetail.vue` | 文件行 +「对比」按钮；+「标为重要版本」 |
| `frontend/src/components/version/VersionTimeline.vue` | 里程碑展示；fileFilter 支持 |
| `frontend/src/components/version/VersionPanel.vue` | fileFilter 透传与清除 |
| `frontend/src/components/FileTree.vue` | 右键 +「这份文件的历史」 |
| `frontend/src/pages/project-overview/fileOpenTabs.js` + `project-overview.vue` | +version-compare / version-text-diff 标签类型与事件接线 |
| `frontend/src/services/api.js` | +4 个具名导出 |
| `frontend/tests/lowa-e2e/run.mjs` | 组 13 改测真 action |
| `frontend/tests/app-e2e/run.mjs` | J9 扩展 |
| `.claude/agents/version-control.md` | 第 2 期契约与地雷 |

**明确不做（第 3 期或 follow-up）**：另起一稿与冲突三选一（第 3 期，届时另写计划）；「关闭项目」触发结束（需桌面壳）；crash-recovery 提示弹窗（WorkSessionBar 的常驻三按钮已覆盖三种选择，spec 的专门弹窗降级为不做，理由记入领域文档）。

---

### Task 1: 版本文件字节与文本端点

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`（静态助手）
- Modify: `backend/src/main/java/com/checkba/version/VersionController.java`
- Test: `backend/src/test/java/com/checkba/version/VersionFileAccessTest.java`（新建）

**Interfaces:**
- Consumes: `ProjectRepoService.readBlobAtCommit(long, ref, relPath)`（不存在返回 null）
- Produces:
  - `static String WorkSessionService.safeRepoPath(String path)` —— 校验并返回规范化仓库相对路径；非法抛 `VersionException`
  - `GET /api/projects/{pid}/version/versions/{ref}/file-bytes?path=` → `application/octet-stream`
  - `GET /api/projects/{pid}/version/versions/{ref}/file-text?path=` → `{code, data:{text}}`

**path 校验规则（安全边界，测试必须逐条覆盖）**：空白、含 `\`、以 `/` 开头、任一段为 `..`、以 `.awd/` 开头或等于 `.awd` —— 一律拒绝（`VersionException`，技术档，不回显 path）。

- [ ] **Step 1: 写失败测试**

`VersionFileAccessTest.java`（纯 Mockito，照 `VersionControllerAuthTest` 的 `@ExtendWith(MockitoExtension.class)` + `MockedStatic<AuthController>` 模式；lenient 桩权限为成员非 CLIENT）：

```java
package com.checkba.version;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionFileAccessTest {

    @Test
    void safeRepoPathAcceptsNormalNestedPath() {
        assertEquals("重要协议/股权转让协议.docx",
                WorkSessionService.safeRepoPath("重要协议/股权转让协议.docx"));
    }

    @Test
    void safeRepoPathRejectsTraversalAwdAndAbsolute() {
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("../etc/passwd"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("a/../../b"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath(".awd/tree.json"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath(".awd"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("/abs"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("a\\b"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("  "));
    }
}
```

控制器行为测试（同文件或 `VersionControllerAuthTest` 追加）：file-bytes 对存在的 path 返回字节、对 `readBlobAtCommit` 返回 null 的 path 返回 404 语义（`code:1` + userFacing「这一版里没有这份文件」）；两个新端点进既有的 8 端点鉴权参数化矩阵（`Endpoint` 枚举各加一项，CLIENT/非成员/匿名 × 拒绝且服务层零调用）。

- [ ] **Step 2: 跑红** `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=VersionFileAccessTest`（backend/ 下）——编译失败，`safeRepoPath` 不存在。

- [ ] **Step 3: 实现**

`WorkSessionService` 加静态助手（放 `describeChanges` 附近）：

```java
    /** 校验外部传入的仓库相对路径。非法即抛（技术档，不回显内容）。 */
    static String safeRepoPath(String path) {
        if (path == null || path.isBlank() || path.contains("\\") || path.startsWith("/")) {
            throw new VersionException("非法路径");
        }
        String normalized = path.strip();
        for (String seg : normalized.split("/")) {
            if (seg.isEmpty() || seg.equals("..") || seg.equals(".")) {
                throw new VersionException("非法路径");
            }
        }
        if (normalized.equals(".awd") || normalized.startsWith(".awd/")) {
            throw new VersionException("非法路径");
        }
        return normalized;
    }
```

`VersionController` 加两个端点（鉴权、异常处理照本类既有模式）：

```java
    @GetMapping("/versions/{ref}/file-bytes")
    public ResponseEntity<byte[]> fileBytesAtRef(
            @PathVariable Long projectId, @PathVariable String ref,
            @RequestParam("path") String path,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        String rel = WorkSessionService.safeRepoPath(path);
        byte[] bytes = repoService.readBlobAtCommit(projectId, ref, rel);
        if (bytes == null) {
            throw VersionException.userFacing("这一版里没有这份文件");
        }
        return ResponseEntity.ok()
                .header("Content-Type", "application/octet-stream")
                .body(bytes);
    }

    @GetMapping("/versions/{ref}/file-text")
    public ResponseEntity<Map<String, Object>> fileTextAtRef(
            @PathVariable Long projectId, @PathVariable String ref,
            @RequestParam("path") String path,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        String rel = WorkSessionService.safeRepoPath(path);
        byte[] bytes = repoService.readBlobAtCommit(projectId, ref, rel);
        if (bytes == null) {
            throw VersionException.userFacing("这一版里没有这份文件");
        }
        try (java.io.InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            org.apache.tika.Tika tika = new org.apache.tika.Tika();
            String text = tika.parseToString(in);
            return ok(Map.of("text", text == null ? "" : text));
        } catch (Exception e) {
            log.warn("版本文本抽取失败: project={}, ref={}", projectId, ref, e);
            throw new VersionException("文本抽取失败", e);
        }
    }
```

注意：`fileBytesAtRef` 返回类型不是本类惯用的 `Map`，`@ExceptionHandler` 对它同样生效（Spring 按方法级处理器包 JSON）；确认既有 `onVersionError` 签名兼容（返回 `ResponseEntity<Map<...>>` 即可）。Tika 用法照 `FileController.extractDocumentText`（:564）的依赖坐标，不新加依赖。

- [ ] **Step 4: 跑绿** 同 Step 2 命令 + `VersionControllerAuthTest`。
- [ ] **Step 5: 提交** `git add backend/src/main/java/com/checkba/version backend/src/test/java/com/checkba/version && git commit -m "feat(version): 按版本取文件字节与文本，path 安全校验"`

---

### Task 2: worker 生产 action compare_document

**Files:**
- Modify: `frontend/src/zetaoffice/public/office_thread.js`
- Modify: `frontend/src/composables/libreofficeExecutorClient.js`
- Modify: `frontend/tests/lowa-e2e/run.mjs`（组 13 改测真 action，删注入的 `debug_compare_document`）

**Interfaces:**
- Produces: worker action `compare_document {baseBytes}` —— 当前文档与 baseBytes（旧版）比较，产出修订标记，随后文档切只读；返回 `{success, redlineCount}` 或 `{success:false, stage, message}`

- [ ] **Step 1: 实现 action**

在 `office_thread.js` 的 actions 表中加（与相邻 action 同风格；`errStr`/`mkProp`/`setRedlineAuthor` 均为本文件既有函数）：

```js
  // [第 2 期 版本对比] 当前文档（新版）与 baseBytes（旧版）比较，产出修订标记。
  // 探针（lowa-e2e 组 13）已实证方向：产出「旧到新」的删/插修订，正文停在新版。
  // 比较产生的修订署名统一为「版本对比」，与人工/AI 修订区分开。
  // 比较完成后把文档切只读（.uno:EditDoc 关编辑模式）——这是展示用文档，
  // 宿主（VersionCompareTab）没有任何保存路径，只读是第二道保险。
  compare_document(p) {
    const raw = p && p.baseBytes;
    let u8 = null;
    if (raw instanceof ArrayBuffer) u8 = new Uint8Array(raw);
    else if (raw && raw.buffer instanceof ArrayBuffer) u8 = new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength);
    else if (Array.isArray(raw)) u8 = new Uint8Array(raw);
    if (!u8 || u8.length === 0) return { success: false, stage: 'input', message: 'baseBytes empty' };
    const bytes = Array.from(new Int8Array(u8.buffer, u8.byteOffset, u8.byteLength));
    const url = 'file:///tmp/awd_base_cmp.docx';
    try {
      const sfa = css.ucb.SimpleFileAccess.create(context);
      try { if (sfa.exists(url)) sfa.kill(url); } catch (e) {}
      const stream = css.io.SequenceInputStream.createStreamFromSequence(context, bytes);
      sfa.writeFile(url, stream);
      try { stream.closeInput(); } catch (e) {}
    } catch (e) { return { success: false, stage: 'memfs', message: errStr(e) }; }
    try { setRedlineAuthor('版本对比'); } catch (e) {}
    try {
      css.frame.DispatchHelper.create(context).executeDispatch(
        ctrl.getFrame(), '.uno:CompareDocuments', '', 0, [mkProp('URL', url)]);
    } catch (e) { return { success: false, stage: 'dispatch', message: errStr(e) }; }
    let count = 0;
    try {
      const en = xModel.getRedlines().createEnumeration();
      while (en.hasMoreElements() && count < 10000) { en.nextElement(); count++; }
    } catch (e) {}
    try {
      css.frame.DispatchHelper.create(context).executeDispatch(
        ctrl.getFrame(), '.uno:EditDoc', '', 0, []);
    } catch (e) {}
    return { success: true, redlineCount: count };
  },
```

- [ ] **Step 2: 白名单** `libreofficeExecutorClient.js` 的 `EDITOR_ACTIONS` 数组加一行（带注释）：

```js
  // [第 2 期 版本对比] host-initiated：当前文档与旧版字节比较产出修订，随后切只读。
  'compare_document',
```

- [ ] **Step 3: 组 13 改测真 action** —— `run.mjs` 里删掉 `DEBUG_ACTIONS` 中的 `debug_compare_document`（保留 `debug_fresh_document`），并把注入补丁字符串里对应的白名单追加项去掉；测试主体把 `exec('debug_compare_document', ...)` 换成 `exec('compare_document', ...)`，断言从 `cmp.success` 扩为 `cmp.redlineCount > 0`。方向断言（redlines 含 Delete「三」）保留。

- [ ] **Step 4: 跑 lowa-e2e**

```bash
cd "/Users/zewei/Documents/2024-2044/5-Tech/1-2 checkba_cloud/.claude/worktrees/project-git-version-control-edc4d1/frontend" && npm run build:zetaoffice && node ../desktop/scripts/fetch-lowa-assets.js && npm run test:lowa-e2e
```

CDN 挂时 `LOWA_BASE_URL="https://www.aiworkdeck.com/lowa-engine/24.2.8-zhcn-r2/"` 前缀重跑 fetch。预期全绿（基线 42 项，组 13 断言小改）。

- [ ] **Step 5: 提交** `git add frontend/src/zetaoffice/public/office_thread.js frontend/src/composables/libreofficeExecutorClient.js frontend/tests/lowa-e2e/run.mjs && git commit -m "feat(version): compare_document 生产 action，修订署名版本对比并切只读"`

---

### Task 3: VersionCompareTab 只读对比宿主

**Files:**
- Create: `frontend/src/components/version/VersionCompareTab.vue`
- Modify: `frontend/src/pages/project-overview/fileOpenTabs.js`（+`openVersionCompareTab`、`isVersionCompareTab`）
- Modify: `frontend/src/pages/project-overview/project-overview.vue`（两窗格渲染分支 + 组件注册）
- Modify: `frontend/src/services/api.js`（+`getVersionFileBytesUrl` 辅助）

**Interfaces:**
- Consumes: Task 1 的 file-bytes 端点、Task 2 的 `compare_document`
- Produces: `openVersionCompareTab({projectId, path, name, newRef, oldRef})`；标签对象 `{tabType:'version-compare', compareSpec:{projectId, path, newRef, oldRef}}`

**硬边界（宿主与 `LibreOfficeEditor` 的区别，代码里必须体现）**：不监听 `modified`、没有任何 save/upload 代码路径、beforeUnmount 只销毁 webview 与 executor、不进保活池（不注册 `_libreRefs`/LRU）。

- [ ] **Step 1: api.js 辅助**（字节走带鉴权头的 fetch，照本文件既有 `getAuthHeaders` 用法）：

```js
// 版本对比：取某一版某文件的原始字节（octet-stream）。返回 Uint8Array。
export async function fetchVersionFileBytes(projectId, ref, path) {
  const baseUrl = getApiBaseUrl();
  const url = `${baseUrl.replace(/\/$/, '')}/api/projects/${projectId}/version/versions/${encodeURIComponent(ref)}/file-bytes?path=${encodeURIComponent(path)}`;
  const resp = await fetch(url, { headers: getAuthHeaders() });
  if (!resp.ok) throw new Error('读取版本文件失败');
  const ct = resp.headers.get('content-type') || '';
  if (ct.includes('application/json')) {
    const j = await resp.json();
    throw new Error((j && j.message) || '读取版本文件失败');
  }
  return new Uint8Array(await resp.arrayBuffer());
}
```

（`getAuthHeaders` 从 `@/utils/auth.js` 已在 api.js 顶部导入；若 fetch 在 uni-app H5 环境可用性有疑虑，照 `LibreOfficeEditor.prefetchBytes` 的实际取字节方式对齐——实施时读一眼它怎么下载文档字节，用同一机制。）

- [ ] **Step 2: 组件**

```vue
<template>
  <view class="vcmp-root">
    <view v-if="!ready" class="vcmp-status">
      <text>{{ statusText }}</text>
    </view>
    <view v-show="ready" :id="hostId" class="vcmp-host"></view>
    <view v-if="ready" class="vcmp-banner">
      <text>版本对比（只读）：左删右增的修订即两版差异，共 {{ redlineCount }} 处</text>
    </view>
  </view>
</template>

<script>
import { createWebviewEditorExecutor } from '@/composables/useZetaOfficeWebview.js'
import { fetchVersionFileBytes } from '@/services/api.js'

let seq = 0

export default {
  name: 'VersionCompareTab',
  props: {
    // {projectId, path, newRef, oldRef}
    compareSpec: { type: Object, required: true },
  },
  data() {
    return {
      hostId: 'vcmp-host-' + (++seq),
      ready: false,
      statusText: '正在准备对比…',
      redlineCount: 0,
      webviewEl: null,
      executor: null,
    }
  },
  async mounted() {
    try {
      const api = typeof window !== 'undefined' && window.checkbaDesktop && window.checkbaDesktop.zetaoffice
      if (!api || typeof api.getEditor !== 'function') {
        this.statusText = '版本对比仅桌面版可用'
        return
      }
      const spec = this.compareSpec
      // 字节下载与引擎启动并行
      const bytesPromise = Promise.all([
        fetchVersionFileBytes(spec.projectId, spec.newRef, spec.path),
        fetchVersionFileBytes(spec.projectId, spec.oldRef, spec.path),
      ])
      const info = await api.getEditor()
      await this.mountWebview(info)
      const [newBytes, oldBytes] = await bytesPromise
      this.statusText = '正在生成对比…'
      const name = spec.path.split('/').pop() || 'compare.docx'
      const loaded = await this.executor.executeCommand('load_document', {
        bytes: newBytes, name, authorName: '版本对比',
      })
      if (!loaded || loaded.success === false) throw new Error('加载新版失败')
      const cmp = await this.executor.executeCommand('compare_document', { baseBytes: oldBytes })
      if (!cmp || cmp.success !== true) throw new Error('对比生成失败')
      this.redlineCount = cmp.redlineCount || 0
      this.ready = true
    } catch (e) {
      console.warn('[VersionCompare] 失败', e)
      this.statusText = (e && e.message) || '对比生成失败，请稍后重试'
    }
  },
  beforeUnmount() {
    // 只销毁，绝无保存路径——这是与 LibreOfficeEditor 的本质区别。
    try { if (this.executor && typeof this.executor.dispose === 'function') this.executor.dispose() } catch (e) {}
    try { if (this.webviewEl && this.webviewEl.remove) this.webviewEl.remove() } catch (e) {}
    this.webviewEl = null
    this.executor = null
  },
  methods: {
    mountWebview(info) {
      return new Promise((resolve, reject) => {
        const host = document.getElementById(this.hostId)
        if (!host) { reject(new Error('host missing')); return }
        const wv = document.createElement('webview')
        wv.setAttribute('partition', info.partition)
        if (info.preload) wv.setAttribute('preload', info.preload)
        wv.setAttribute('webpreferences', 'contextIsolation=yes,nodeIntegration=no')
        wv.style.width = '100%'; wv.style.height = '100%'; wv.style.border = '0'
        wv.addEventListener('dom-ready', async () => {
          try {
            // 只读宿主：不监听 lo-relay 的 modified 信号（没有自动保存）。
            this.executor = createWebviewEditorExecutor(wv, {})
            if (this.executor && this.executor.whenReady) await this.executor.whenReady()
            resolve()
          } catch (e) { reject(e) }
        })
        wv.src = info.url
        host.appendChild(wv)
        this.webviewEl = wv
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.vcmp-root { display: flex; flex-direction: column; height: 100%; }
.vcmp-status { flex: 1; display: flex; align-items: center; justify-content: center; color: #888; font-size: 26rpx; }
.vcmp-host { flex: 1; min-height: 0; }
.vcmp-banner {
  padding: 10rpx 20rpx; background: #F7F5F0; border-top: 1px solid #eee;
  font-size: 23rpx; color: #666;
}
</style>
```

**实施注意**：`createWebviewEditorExecutor` 的握手 API（`whenReady` 或等 ready 事件）以 `useZetaOfficeWebview.js` 实际导出为准，实施时读 :54 起的真实签名并对齐——`LibreOfficeEditor.onDomReady` 是现成的调用范例。计划里这段以真实签名为准修正，其余结构不变。

- [ ] **Step 3: 标签接线**

`fileOpenTabs.js` 加（照 `openDiffTab` 模式）：

```js
    isVersionCompareTab(file) {
      return file && file.tabType === 'version-compare'
    },

    // 版本对比标签：{projectId, path, name, newRef, oldRef}
    openVersionCompareTab(spec) {
      const id = `vcmp-${spec.newRef.slice(0, 8)}-${Date.now()}`
      const tab = {
        id,
        name: `${spec.name} 版本对比`,
        tabType: 'version-compare',
        fileType: 'version-compare',
        compareSpec: {
          projectId: spec.projectId, path: spec.path,
          newRef: spec.newRef, oldRef: spec.oldRef,
        },
        createdAt: Date.now(),
      }
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const targetList = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const targetIdProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'
      targetList.push(tab)
      this[targetIdProp] = tab.id
    },
```

`project-overview.vue` 两窗格（:753 与 :820 的 `DocDiffViewer` 分支旁）各加：

```vue
                    <VersionCompareTab
                      v-else-if="isVersionCompareTab(activeFileLeft)"
                      :key="activeFileLeft.id"
                      :compare-spec="activeFileLeft.compareSpec"
                    />
```

（右窗格同式换 `activeFileRight`。）import + components 注册照 `DocDiffViewer` 的方式。

- [ ] **Step 4: 验证** `npm run check:emits && npm run build:h5 2>&1 | tail -3` 绿；桌面壳链路留给 Task 10 的 desktop-e2e/人工冒烟。
- [ ] **Step 5: 提交** `git add frontend/src/components/version/VersionCompareTab.vue frontend/src/pages/project-overview/fileOpenTabs.js frontend/src/pages/project-overview/project-overview.vue frontend/src/services/api.js && git commit -m "feat(version): 版本对比只读标签页"`

---

### Task 4: 文本对比降级与「对比」入口

**Files:**
- Modify: `frontend/src/components/DocDiffViewer.vue`（+versionSpec 模式）
- Modify: `frontend/src/services/api.js`（+`getVersionFileText`）
- Modify: `frontend/src/components/version/VersionNodeDetail.vue`（文件行 + 对比按钮）
- Modify: `frontend/src/components/version/VersionTimeline.vue` / `VersionPanel.vue`（事件冒泡）
- Modify: `frontend/src/pages/project-overview/fileOpenTabs.js` + `project-overview.vue`（version-text-diff 标签 + 监听）

**Interfaces:**
- Consumes: Task 1 的 file-text 端点；Task 3 的 `openVersionCompareTab`
- Produces:
  - `getVersionFileText(projectId, ref, path)`（api.js 具名导出，走 `request`）
  - DocDiffViewer 新 prop `versionSpec: {projectId, path, oldRef, newRef, oldLabel, newLabel}`（设了它就走版本文本源，忽略 sourceId/targetId；sourceId/targetId 相应改 `required: false`）
  - VersionNodeDetail emit `compare-file {path, sha}`，逐层冒泡到 project-overview

**入口规则**：仅 `type === 'MODIFY'` 的文件行显示「对比」（新增/删除没有两版可比）；根提交（`version.parents` 为空）不显示。点击后：`libreOfficePreferred && path 以 .docx/.doc 结尾` → `openVersionCompareTab`（oldRef = `sha + '^'`）；否则 → 打开 version-text-diff 标签（DocDiffViewer versionSpec 模式）。`libreOfficePreferred` 的取值方式照 `fileOpenTabs.js` 里 `useLibreEditor` 的现成写法。

- [ ] **Step 1: api.js**

```js
// 版本对比降级：取某一版某文件抽取出的纯文本。
export function getVersionFileText(projectId, ref, path) {
  return request({
    url: `/api/projects/${projectId}/version/versions/${encodeURIComponent(ref)}/file-text?path=${encodeURIComponent(path)}`,
    method: 'GET'
  });
}
```

- [ ] **Step 2: DocDiffViewer versionSpec 模式** —— 在其取数逻辑处分支：`versionSpec` 非空时并行调两次 `getVersionFileText`（oldRef/newRef），把结果塞进它现有的 `source.text/target.text` 渲染路径，标题用 `oldLabel/newLabel`（如「上一版」「这一版」）。不动它的 diff 渲染算法。sourceId/targetId 改 `required: false`（versionSpec 模式下不传）。

- [ ] **Step 3: VersionNodeDetail 对比按钮**（`detail-change` 行内，MODIFY 且非根提交时）：

```vue
        <view v-for="c in changes" :key="c.path" class="detail-change">
          <text class="change-type" :class="'type-' + c.type">{{ typeLabel(c.type) }}</text>
          <text class="change-path">{{ c.path }}</text>
          <view
            v-if="c.type === 'MODIFY' && version.parents && version.parents.length > 0"
            class="awd-btn awd-btn-secondary change-compare-btn"
            @tap="$emit('compare-file', { path: c.path, sha: version.sha })"
          >和上一版对比</view>
        </view>
```

emits 数组加 `'compare-file'`；样式 `.change-compare-btn { flex-shrink: 0; padding: 6rpx 14rpx; font-size: 22rpx; }`。

- [ ] **Step 4: 冒泡与打开** —— `VersionTimeline` 给 `<VersionNodeDetail @compare-file="$emit('compare-file', $event)">`，emits 注册；`VersionPanel` 同式再抛；`project-overview.vue` 的 `<VersionPanel>` 处监听：

```vue
          <VersionPanel v-else-if="leftPaneKey === 'version'" :project-id="projectId"
            @compare-file="onVersionCompareFile" />
```

`fileOpenTabs.js` 加：

```js
    onVersionCompareFile({ path, sha }) {
      const name = path.split('/').pop() || path
      const oldRef = sha + '^'
      const isDocx = /\.(docx?|DOCX?)$/.test(name)
      if (this.libreOfficePreferred && isDocx) {
        this.openVersionCompareTab({ projectId: this.projectId, path, name, newRef: sha, oldRef })
      } else {
        this.openVersionTextDiffTab({ projectId: this.projectId, path, name, newRef: sha, oldRef })
      }
    },

    isVersionTextDiffTab(file) {
      return file && file.tabType === 'version-text-diff'
    },

    openVersionTextDiffTab(spec) {
      const id = `vtd-${spec.newRef.slice(0, 8)}-${Date.now()}`
      const tab = {
        id, name: `${spec.name} 版本对比`, tabType: 'version-text-diff', fileType: 'version-text-diff',
        versionSpec: {
          projectId: spec.projectId, path: spec.path, oldRef: spec.oldRef, newRef: spec.newRef,
          oldLabel: '上一版', newLabel: '这一版',
        },
        createdAt: Date.now(),
      }
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const targetList = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const targetIdProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'
      targetList.push(tab)
      this[targetIdProp] = tab.id
    },
```

两窗格渲染分支（DocDiffViewer 复用，versionSpec 模式）：

```vue
                    <DocDiffViewer
                      v-else-if="isVersionTextDiffTab(activeFileLeft)"
                      :key="activeFileLeft.id"
                      :version-spec="activeFileLeft.versionSpec"
                    />
```

- [ ] **Step 5: 验证** `npm run check:emits && npm run build:h5 | tail -3` 绿。
- [ ] **Step 6: 提交** `git add frontend/src && git commit -m "feat(version): 和上一版对比入口，docx 走修订稿、其余走文本对比"`（此处 frontend/src 均为本任务文件，工作区无他人改动时可整体加；有疑虑就逐文件列）。

---

### Task 5: AI 轮次落版与署名

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`（+`commitAiRound`）
- Modify: `backend/src/main/java/com/checkba/service/ai/AgentOrchestrator.java`
- Modify: `backend/src/test/java/com/checkba/service/ai/eval/EvalHarness.java`（构造器同步！）
- Test: `backend/src/test/java/com/checkba/version/WorkSessionServiceTest.java`（追加）

**Interfaces:**
- Produces: `String WorkSessionService.commitAiRound(long projectId, Long userId)` —— AI 身份（`AI Workdeck <ai@aiworkdeck.local>`）落一笔 `kind=auto` 存档；无变更返回 null；内部走锁与 `ensureSession`（含空闲定时器武装）

- [ ] **Step 1: 失败测试**（`WorkSessionServiceTest` 追加；测试基建照本类现成的 seeded/mock 模式）：

```java
    @Test
    void commitAiRoundAttributesToAiWorkdeck() throws Exception {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        Files.writeString(root.resolve("projects/7/合同.txt"), "AI 改的");
        String sha = svc.commitAiRound(7L, 1L);

        assertNotNull(sha);
        VersionEntry head = repoSvc.log(7L, "HEAD", 1).get(0);
        assertEquals("AI Workdeck", head.authorName());
        assertEquals("auto", head.kind());
    }

    @Test
    void commitAiRoundWithNoChangesReturnsNull() {
        svc.onChangeSignal(7L, 1L, "韩泽伟");
        String first = svc.commitNow(7L, 1L, "韩泽伟", null);
        assertNull(svc.commitAiRound(7L, 1L));
    }
```

- [ ] **Step 2: 跑红** `mvn -q test -Dtest=WorkSessionServiceTest`（编译失败）。

- [ ] **Step 3: 实现**

`WorkSessionService`（紧挨 `commitNow`，结构照抄它，仅身份不同）：

```java
    /**
     * AI 轮次结束的落版：以 AI 身份（AI Workdeck <ai@aiworkdeck.local>）落一笔自动存档，
     * 让时间线能看出「哪些改动是 AI 做的」。无变更返回 null。
     * 已知局限（与文档检查点同源）：编辑器自动保存是异步的，轮次结束时未 flush 的
     * 改动不在本笔里，会随后续保存进入普通存档。
     */
    public String commitAiRound(long projectId, Long userId) {
        if (!repoService.isInitialized(projectId)) return null;
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            ensureSession(projectId, userId);
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
            String msg = describePendingChanges(projectId);
            return repoService.commitAll(projectId, msg, "auto", null,
                    "AI Workdeck", "ai@aiworkdeck.local");
        } finally {
            lock.unlock();
        }
    }
```

（若现 `commitNow` 内部已抽了公共私有方法，则对齐现状复用，身份参数化——以实际代码为准，不重复逻辑。）

`AgentOrchestrator`：加 `private final com.checkba.version.WorkSessionService workSessionService;`（lombok 自动进构造器），`runLoop(...)` 正常返回后（同一 try 块内、catch 之前）：

```java
            // 版本记录：AI 轮次结束落一笔 AI 署名的存档（失败绝不阻断，保险不是主流程）
            try {
                workSessionService.commitAiRound(projectId, userId);
            } catch (Exception e) {
                log.warn("AI 轮次版本落档失败: project={}", projectId, e);
            }
```

（`projectId`/`userId` 的局部变量名以该方法实际为准。）

**`EvalHarness.java:162` 同步**：构造器加第 16 个参数 `mock(com.checkba.version.WorkSessionService.class)`。这一步漏掉编译就断——把它当作本任务的验收项而不是顺手活。

- [ ] **Step 4: 跑绿** `mvn -q test -Dtest=WorkSessionServiceTest` + **全量**（EvalHarness 在 mvn test 里跑，必须全量确认）。
- [ ] **Step 5: 提交** `git add backend/src && git commit -m "feat(version): AI 轮次结束以 AI Workdeck 身份落版"`

---

### Task 6: 重要版本（后端）

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/ProjectRepoService.java`（+`tagMilestone`/`listMilestones`；`toEntry` 带 milestone）
- Modify: `backend/src/main/java/com/checkba/version/VersionEntry.java`（+`String milestone` 末位）
- Modify: `backend/src/main/java/com/checkba/version/VersionController.java`（+POST milestone）
- Test: `backend/src/test/java/com/checkba/version/MilestoneTest.java`（新建）

**Interfaces:**
- Produces:
  - `void ProjectRepoService.tagMilestone(long projectId, String sha, String name)` —— 附注标签 `refs/tags/awd/milestone/{sha 前 12 位}`，message = name；同 sha 重打则覆盖（`setForceUpdate(true)`）
  - `Map<String,String> ProjectRepoService.listMilestones(long projectId)` —— sha（完整）→ 里程碑名
  - `log()` 返回的 `VersionEntry.milestone` 非空即该版是重要版本
  - `POST /api/projects/{pid}/version/versions/{sha}/milestone` body `{name}`（name 必填、≤64 字，超限 userFacing 拒绝）

- [ ] **Step 1: 失败测试**

```java
package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MilestoneTest {

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
    void tagThenLogCarriesMilestoneName(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        String sha = s.log(7L, "HEAD", 1).get(0).sha();

        s.tagMilestone(7L, sha, "发客户第一稿");

        VersionEntry head = s.log(7L, "HEAD", 1).get(0);
        assertEquals("发客户第一稿", head.milestone());
        assertEquals("发客户第一稿", s.listMilestones(7L).get(sha));
    }

    @Test
    void retagSameShaOverwrites(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        String sha = s.log(7L, "HEAD", 1).get(0).sha();
        s.tagMilestone(7L, sha, "旧名");
        s.tagMilestone(7L, sha, "新名");
        assertEquals("新名", s.log(7L, "HEAD", 1).get(0).milestone());
    }

    @Test
    void untaggedVersionHasNullMilestone(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        assertNull(s.log(7L, "HEAD", 1).get(0).milestone());
    }
}
```

- [ ] **Step 2: 跑红**（编译失败：`milestone()` 不存在）。

- [ ] **Step 3: 实现**

`VersionEntry` 末位加 `String milestone`。`ProjectRepoService`：

```java
    private static final String MILESTONE_TAG_PREFIX = "refs/tags/awd/milestone/";

    /** 标记重要版本：附注标签，名字放 tag message。同一版本重打则覆盖旧名。 */
    public void tagMilestone(long projectId, String sha, String name) {
        try (Repository repo = open(projectId); Git git = new Git(repo);
             RevWalk walk = new RevWalk(repo)) {
            ObjectId id = repo.resolve(sha);
            if (id == null) throw new VersionException("版本不存在: " + sha);
            git.tag()
               .setObjectId(walk.parseCommit(id))
               .setName("awd/milestone/" + sha.substring(0, Math.min(12, sha.length())))
               .setMessage(name)
               .setAnnotated(true)
               .setForceUpdate(true)
               .call();
        } catch (VersionException e) { throw e; }
        catch (Exception e) { throw new VersionException("标记重要版本失败", e); }
    }

    /** 全部重要版本：完整 sha → 里程碑名。 */
    public Map<String, String> listMilestones(long projectId) {
        Map<String, String> out = new HashMap<>();
        try (Repository repo = open(projectId); RevWalk walk = new RevWalk(repo)) {
            for (org.eclipse.jgit.lib.Ref ref :
                    repo.getRefDatabase().getRefsByPrefix(MILESTONE_TAG_PREFIX)) {
                try {
                    org.eclipse.jgit.revwalk.RevObject obj = walk.parseAny(ref.getObjectId());
                    if (obj instanceof org.eclipse.jgit.revwalk.RevTag tag) {
                        out.put(walk.peel(tag).getName(), tag.getFullMessage().strip());
                    }
                } catch (Exception e) { log.warn("解析里程碑失败: {}", ref.getName(), e); }
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取重要版本失败: project=" + projectId, e);
        }
    }
```

`log()` 改为先取 `listMilestones`（同一 Repository 会话内直接内联同逻辑或复用方法，注意别双开 Repository——实施时以最小改动为准），`toEntry` 增参把 `milestones.get(c.getName())` 填进新字段。

`VersionController`：

```java
    @PostMapping("/versions/{sha}/milestone")
    public ResponseEntity<Map<String, Object>> markMilestone(
            @PathVariable Long projectId, @PathVariable String sha,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        String name = body == null ? null : body.get("name");
        if (name == null || name.isBlank()) {
            throw VersionException.userFacing("请给重要版本起个名字");
        }
        if (name.strip().length() > 64) {
            throw VersionException.userFacing("名字太长了，请控制在 64 字以内");
        }
        repoService.tagMilestone(projectId, sha, name.strip());
        return ok(Map.of("marked", true));
    }
```

鉴权参数化矩阵（`VersionControllerAuthTest` 的 `Endpoint` 枚举）加此端点。

- [ ] **Step 4: 跑绿** `mvn -q test -Dtest=MilestoneTest,VersionControllerAuthTest,ProjectRepoHistoryTest`。
- [ ] **Step 5: 提交** `git add backend/src && git commit -m "feat(version): 重要版本标签与时间线透出"`

---

### Task 7: 重要版本（前端）

**Files:**
- Modify: `frontend/src/services/api.js`（+`markVersionMilestone`）
- Modify: `frontend/src/components/version/VersionNodeDetail.vue`（+按钮与命名弹窗）
- Modify: `frontend/src/components/version/VersionTimeline.vue`（里程碑展示）

**Interfaces:**
- Consumes: Task 6 端点；timeline 返回的 `milestone` 字段
- Produces: `markVersionMilestone(projectId, sha, name)`；VersionNodeDetail emit `milestoned`（父级刷新时间线）

- [ ] **Step 1: api.js**

```js
// 标记重要版本（需命名，如「发客户第一稿」）。
export function markVersionMilestone(projectId, sha, name) {
  return request({
    url: `/api/projects/${projectId}/version/versions/${encodeURIComponent(sha)}/milestone`,
    method: 'POST',
    data: { name }
  });
}
```

- [ ] **Step 2: VersionNodeDetail** —— footer 加「标为重要版本」按钮（在「退回到这一版」旁），点击开命名弹窗（结构照 `WorkSessionBar` 的命名弹窗：`.awd-mask/.awd-dialog/.awd-input`，占位「例如：发客户第一稿」，必填）；确认后调 `markVersionMilestone`，成功 toast「已标为重要版本」+ emit `milestoned` + 关弹窗；失败 `(e && e.message) || '标记失败，请稍后重试'`。已是里程碑的版本按钮文案改「重新命名重要版本」。emits 注册 `'milestoned'`；`VersionTimeline` 监听后 `load()` 并继续上抛无必要（面板不需感知）——就地刷新即可。

- [ ] **Step 3: VersionTimeline 展示** —— 节点标题行：`v.milestone` 非空时前置一个标记 + 用里程碑名做主标题：

```vue
      <view class="node-main" @tap="select(group.head)">
        <view class="node-title" :class="{ 'has-milestone': group.head.milestone }">
          <text v-if="group.head.milestone" class="milestone-flag">重要版本</text>
          {{ group.head.milestone || titleOf(group.head) }}
        </view>
        <view class="node-meta">{{ group.head.authorName }} · {{ timeOf(group.head) }}</view>
      </view>
```

样式：`.milestone-flag { font-size: 20rpx; color: #C8A45D; border: 1px solid #C8A45D; border-radius: 4rpx; padding: 2rpx 8rpx; margin-right: 8rpx; }`、`.has-milestone { font-weight: 600; }`。展开的自动存档行同理（`a.milestone` 判断，复用同 class）。

- [ ] **Step 4: 验证** `npm run check:emits && npm run build:h5 | tail -3`。
- [ ] **Step 5: 提交** `git add frontend/src && git commit -m "feat(version): 重要版本标记与时间线展示"`

---

### Task 8: 单文件历史

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/ProjectRepoService.java`（+`logForPath`）
- Modify: `backend/src/main/java/com/checkba/version/VersionController.java`（timeline +`fileId` 参数）
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`（+`repoRelativePath` 静态助手）
- Modify: `frontend/src/components/FileTree.vue`、`version/VersionPanel.vue`、`version/VersionTimeline.vue`、`project-overview.vue`、`services/api.js`
- Test: `backend/src/test/java/com/checkba/version/ProjectRepoHistoryTest.java`（追加）

**Interfaces:**
- Produces:
  - `static String WorkSessionService.repoRelativePath(com.checkba.model.entity.ProjectFile f)` —— 由 `filePath`（`projects/{id}/...`）剥前缀得仓库相对路径；格式不符抛 `VersionException`
  - `List<VersionEntry> ProjectRepoService.logForPath(long projectId, String ref, String relPath, int limit)`（JGit `git.log().addPath(relPath)`）
  - `GET /timeline?fileId={id}` —— 有 fileId 时按该文件过滤（服务端做 fileId→path 映射并校验文件属于本项目，IDOR 口径照 `ProjectFileControllerIdorTest`）
  - `getVersionTimeline(projectId, limit, fileId)`（api.js 第三参可选）
  - VersionPanel prop 化的文件过滤：`project-overview` 持有 `versionFileFilter`（`{fileId, name}` 或 null），传给 VersionPanel → VersionTimeline；FileTree emit `file-history` 事件

- [ ] **Step 1: 失败测试**（`ProjectRepoHistoryTest` 追加）：

```java
    @Test
    void logForPathReturnsOnlyCommitsTouchingThatFile(@TempDir Path root) throws Exception {
        ProjectRepoService s = seeded(root);
        Files.writeString(root.resolve("projects/7/别的.txt"), "x");
        s.commitAll(7L, "改了别的", "auto", null, "韩泽伟", "hzw@example.com");
        Files.writeString(root.resolve("projects/7/合同.txt"), "二稿");
        s.commitAll(7L, "改了合同", "auto", null, "韩泽伟", "hzw@example.com");

        List<VersionEntry> all = s.log(7L, "HEAD", 100);
        List<VersionEntry> only = s.logForPath(7L, "HEAD", "合同.txt", 100);

        assertTrue(only.size() < all.size());
        assertTrue(only.stream().anyMatch(v -> v.message().contains("合同")));
        assertTrue(only.stream().noneMatch(v -> v.message().contains("别的")));
    }
```

`repoRelativePath` 单测（`VersionFileAccessTest` 追加）：`filePath="projects/7/重要协议/x.docx"` + projectId=7 → `"重要协议/x.docx"`；前缀不符（如 `projects/8/...` 或 null）抛 `VersionException`。

- [ ] **Step 2: 跑红**。

- [ ] **Step 3: 实现**

`WorkSessionService`：

```java
    /** ProjectFile.filePath（projects/{id}/...）→ 仓库相对路径。归属不符即拒。 */
    static String repoRelativePath(com.checkba.model.entity.ProjectFile f) {
        String fp = f == null ? null : f.getFilePath();
        if (f == null || fp == null) throw new VersionException("文件没有物理路径");
        String prefix = "projects/" + f.getProjectId() + "/";
        if (!fp.startsWith(prefix) || fp.length() <= prefix.length()) {
            throw new VersionException("文件路径不在本项目内: fileId=" + f.getId());
        }
        return safeRepoPath(fp.substring(prefix.length()));
    }
```

`ProjectRepoService.logForPath`：结构照 `log()`，`git.log().add(start).addPath(relPath).setMaxCount(limit)`；milestone 填充逻辑与 `log()` 一致（抽私有共用方法避免复制）。

`VersionController.timeline` 加 `@RequestParam(required=false) Long fileId`：非空时 `ProjectFile f = projectFileService.getFile(fileId)`（需注入 `ProjectFileService`——**注意**它依赖 `WorkSessionService`，而 `VersionController` 已依赖 `WorkSessionService`，无环；但 `ProjectFileService` 构造器大，Spring 注入即可）；校验 `f != null && projectId.equals(f.getProjectId())` 否则 `IllegalArgumentException`（沿用既有 IDOR 语义）；`logForPath(projectId, "HEAD", repoRelativePath(f), limit)`。

- [ ] **Step 4: 前端**

api.js：`getVersionTimeline` 加第三可选参 `fileId`，有值拼 `&fileId=`。

FileTree 右键菜单（`context-menu-item` 列表里、非文件夹项）：

```vue
        <view v-if="contextMenu.targetItem && !contextMenu.targetItem.isFolder" class="context-menu-item"
          @tap="$emit('file-history', contextMenu.targetItem); closeContextMenu()">
          <view class="context-menu-icon" style="display: flex; align-items: center; justify-content: center;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2" stroke-linecap="round"/>
            </svg>
          </view>
          <text>这份文件的历史</text>
        </view>
```

emits 注册 `'file-history'`。`project-overview.vue`：`<FileTree ... @file-history="onFileHistory" />`；data 加 `versionFileFilter: null`；

```js
    onFileHistory(file) {
      this.versionFileFilter = { fileId: file.id, name: file.name }
      if (this.leftPaneKey !== 'version') this.toggleLeftPane('version')
    },
```

`<VersionPanel ... :file-filter="versionFileFilter" @clear-file-filter="versionFileFilter = null" />`。VersionPanel 接 prop `fileFilter` 传给 VersionTimeline，并在 WorkSessionBar 下方显示过滤条：「只看《{name}》的历史 [显示全部]」（点触 emit `clear-file-filter`）。VersionTimeline 的 `load()` 把 `fileFilter?.fileId` 传给 `getVersionTimeline`，`watch: { fileFilter() { this.load() } }`。

- [ ] **Step 5: 验证与提交** 后端聚焦测试 + `check:emits` + `build:h5` 绿后：`git commit -m "feat(version): 单文件历史——右键入口与时间线过滤"`

---

### Task 9: 退回通知编辑器重载 + 空段结束提示（隐患修复）

**Files:**
- Modify: `backend/src/main/java/com/checkba/version/WorkSessionService.java`
- Modify: `frontend/src/components/version/WorkSessionBar.vue`（notice toast）
- Test: `backend/src/test/java/com/checkba/version/WorkSessionServiceTest.java`（追加）

**问题 A（数据安全）**：`revertTo` 改了磁盘文件但不通知前端，打开中的编辑器还端着退回前的内容，下一次 autosave 就把退回冲掉。`DocumentCheckpointService.restore`（:83）已有先例：`editorBridgeService.sendReloadFileAction(file)`。

**修法**：`WorkSessionService` 注入 `EditorBridgeService`（`com.checkba.service.ai`；`WorkSessionServiceTest` 构造点相应加 mock）。`revertTo` 在最终提交成功后：对 `diffNameStatus(ref, "HEAD")` 算出的每个变更 path（滤掉 `.awd/`），经 `ProjectFileRepository.findByProjectId(projectId)` 匹配 `filePath == "projects/{id}/" + path` 的记录，逐个 `editorBridgeService.sendReloadFileAction(file)`；整段 try/catch 包死只 warn（通知失败不影响退回本身）。注意 diff 要在覆盖工作区**之前**算好留存（现实现顺序如已先算后改，复用那份列表即可，以实际代码为准）。

**测试**：mock `EditorBridgeService`，revert 一个改过《合同.txt》的版本后 `verify(editorBridge).sendReloadFileAction(argThat(f -> "合同.txt".equals(f.getName())))`（`ProjectFileRepository` mock 里预置对应记录）。

**问题 B（体验）**：空工作段点「结束」静默成功但时间线无节点。`endSession` 在合并前比较 `repoService.resolve` 两端（分支 tip == master tip 时）：删分支、标 `DISCARDED`、抛 `VersionException.userFacing("本次工作没有任何改动，未生成版本")`——前端 toast 显示该消息（Task 已有 `(e && e.message) || fallback` 通道，无需新代码；确认 `end()` 的 catch 走的正是它）。测试：开段不改任何东西直接 endSession，断言抛 userFacing 异常、session 变 DISCARDED、master log 长度不变。

（比较两 tip：`ProjectRepoService` 若无现成方法，加 `String resolveRef(long projectId, String ref)` 返回 sha 或 null，10 行以内。）

- [ ] **Step 1: 写两组失败测试 → 跑红 → 实现 → 跑绿**（含全量，因为动了 `WorkSessionService` 构造器——`ChangeSignalWiringTest` 的 `@MockBean` 不受影响，直接构造的测试要补 mock 参数）。
- [ ] **Step 2: 提交** `git commit -m "fix(version): 退回后通知编辑器重载；空工作段结束给出明确提示"`

---

### Task 10: e2e 与领域文档

**Files:**
- Modify: `frontend/tests/app-e2e/run.mjs`（J9 追加步骤）
- Modify: `.claude/agents/version-control.md`

- [ ] **Step 1: J9 追加**（浏览器目标不驱动 LOWA，修订对比已由 lowa-e2e 组 13 盖住；这里补 UI 链路）：
  1. 在已有两个命名节点的时间线上：点开第二个节点详情 → 点「标为重要版本」→ 输入「e2e 里程碑」→ 确认 → 断言时间线出现「重要版本」标记与「e2e 里程碑」标题。
  2. 点开该节点详情，对 MODIFY 文件行断言存在「和上一版对比」按钮 → 点击 → 断言打开了名含「版本对比」的标签（浏览器目标走文本对比分支，断言 DocDiffViewer 渲染出「上一版/这一版」标签头）。
  3. 文件树右键测试文件 →「这份文件的历史」→ 断言版本面板出现「只看《qa-版本测试.txt》的历史」过滤条 → 点「显示全部」恢复。

  选择器与断言照 J9 既有风格（`mouseClickText`/`waitText`；先读组件真实文案再写断言——领域文档地雷 6 的教训）。

- [ ] **Step 2: 跑** `npm run test:app-e2e`（环境起法照 task-15 报告：隔离端口 frontend :5179 / backend :9697，避免撞其他 worktree 的进程——J9 上次就撞过）。

- [ ] **Step 3: 领域文档更新** —— 职责边界去掉「不做内容 diff（第 2 期）」；关键文件地图补 `VersionCompareTab.vue`/`compare_document`/新端点；核心契约补：对比方向（当前=新版、URL=旧版）、比较修订署名「版本对比」、里程碑标签 `refs/tags/awd/milestone/{sha12}`（附注、名字在 message、force 覆盖）、`safeRepoPath`/`repoRelativePath` 是所有 path 入口的必经校验；已知地雷补：**对比宿主绝不能接自动保存**、AI 落版的 flush 时序局限、`EvalHarness` 构造器同步（第三次踩就写进地雷）。CLAUDE.md 路由表行不变。

- [ ] **Step 4: 提交** `git commit -m "test+docs(version): 第 2 期 e2e 旅程与领域文档更新"`

---

## 收尾验证（全部任务后）

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
cd frontend && npm run check:emits && npm run build:h5 && npm run test:lowa-e2e && npm run test:app-e2e
```

后端基线 381/0/0/2（会涨）；lowa-e2e 基线 42；app-e2e J9 扩展后全绿。

## 第 2 期完成后的状态

律师能：对任意「修改」类文件点「和上一版对比」看到带修订标记的对比稿（桌面 docx）或红绿文本对比（其余情形）；把任意版本标为「重要版本」并命名；右键任意文件只看它的历史；时间线上分辨哪些改动是 AI 做的。退回不再有被打开中编辑器覆盖的隐患。

**尚不能**：另起一稿 / 采纳 / 放弃 / 冲突三选一（第 3 期，落地后另写计划）。
