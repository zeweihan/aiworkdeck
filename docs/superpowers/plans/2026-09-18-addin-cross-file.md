# Office/WPS 插件跨文件读写 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Office/WPS 插件里的 AI 读取其他打开的文档、桌面端项目文件、云端项目文件、官方案件库与 GitHub/Gitee 仓库里的文件，并能改其他打开着的文档（痕迹在目标文档自己的窗格里明示）。

**Architecture:** 云后端新增 `ReferenceSourceService`，按 ref 前缀分派到五个 `RefSource` 实现；打开文档经窗格登记簿 + 既有 `client_action` 往返下发到目标窗格；桌面端新增一条 SSE「门铃」流，收到 nudge 立刻取件处理 LIST/READ/OPEN；案件库走本机内部端点；GitHub/Gitee 走各自 contents API。插件端新增心跳、`read_for_reference` 命令、跨文档写入的强制修订与修订记录面板。

**Tech Stack:** Java 21 / Spring Boot 3 / JGit / java.net.http（后端与桌面端同一 jar）；Vue 3 + Office.js + WPS JSAPI（office-addin/taskpane）；node:test。

**Spec:** `docs/superpowers/specs/2026-09-18-addin-cross-file-design.md`

## Global Constraints

- 本机 `mvn` 必须 JDK 21：`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`。
- 新建一方源文件首两行 SPDX 头：`SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors` / `SPDX-License-Identifier: AGPL-3.0-or-later`（Java 用 `//`，Vue 用 `<!-- -->`，JS 用 `//`）。
- 工具输出永不为空串（`ToolExecutionResultMessage.ensureNotBlank`）。
- 参考材料文字：不计费、不落盘、日志只记 requestId/长度/耗时，不记正文。
- 文本上限 200,000 字符，截断标注 `...(截断)`；单文件字节上限 50MB。
- 只有 `open:` 来源可写；其他来源一律只读。
- 跨文档写入 Word 必须带修订；`trackingSupported()`（WordApi 1.4）为假时拒绝执行。
- 用户可见文案中英双语（插件 `lib/i18n.js` 的 ZH/EN；后端工具文案按 `ToolContextHolder`/AppLanguage 现有惯例，模型可见的指令性错误保持中文）。
- 插件模板里不得出现裸中文（`i18n.test.js` 扫描）。
- 不删、不改公开契约字面量（`checkba://`、`X-AWD-*`、`awd: 1` 等）。
- 子代理禁止 git commit / push；提交由主会话做。每个 Task 末尾的 Commit 步骤由主会话执行。

## File Map

后端（`backend/src/main/java/com/checkba/`）：
- `service/ai/SseEmitterService.java`（改：加 `isConnected`）
- `service/addin/PaneRegistry.java`（新）+ `controller/addin/AddinPaneController.java`（新）
- `service/ai/OfficeBridgeService.java`（改：加 `executeOnPane`）
- `service/ai/ref/RefSource.java`、`RefQuery.java`、`RefEntry.java`、`RefSourceException.java`、`ReferenceSourceService.java`（新）
- `service/ai/ref/OpenDocSource.java`、`CloudProjectSource.java`、`DesktopSource.java`、`CaseLibrarySource.java`、`GitProviderSource.java`（新）
- `service/ai/tools/ReferenceTools.java`（新）
- `service/ai/ClientCapabilityService.java`（改：`ref_` 仅 OFFICE 可见）
- `service/ai/ContextAssemblerService.java`（改：中英硬边界段）
- `service/file/ProjectFileTextExtractor.java`（新，从 FileTools 抽出）+ `service/ai/tools/FileTools.java`（改：委托）
- `service/ProjectFileService.java`（改：加 `findByRelativePath`、`listRelativePaths`）
- `service/mobile/DesktopStreamService.java`、`ReferenceRequestStore.java`（新）+ `controller/MobileRefController.java`（新）
- `service/mobile/MobileRelayClientService.java`（改：门铃流 + `pollReferenceRequests`）+ `service/mobile/DesktopRefHandler.java`（新）
- `controller/internal/InternalRefController.java`（新，case 实例）+ `service/ai/ref/CaseRefClient.java`（新）
- `model/entity/AddinGitRepoLink.java`、`repository/AddinGitRepoLinkRepository.java`、`service/addin/GitTokenCipher.java`、`service/addin/GitProviderClient.java`、`controller/addin/AddinGitLinkController.java`（新）

插件（`office-addin/taskpane/`）：
- `lib/paneHeartbeat.js`（新）、`lib/referenceRead.js`（新）、`lib/revisionLog.js`（新）、`lib/crossDocWrite.js`（新）、`lib/gitLink.js`（新）
- `lib/chatSession.js`、`lib/officeExecutor.js`、`lib/wpsWordHandlers.js`、`lib/wpsEtHandlers.js`、`lib/wpsWppHandlers.js`、`lib/api.js`、`lib/i18n.js`、`lib/i18n.test.js`（改）
- `components/RevisionLogPanel.vue`、`components/GitLinkPanel.vue`（新）、`App.vue`（改）

其他：`legal/PRIVACY.md`、`deploy/cloud/README.md`、`deploy/cloud/env.example`、`.claude/agents/{office-addin,ai-chat,mobile-sync,version-control}.md`。

---
### Task 1: 窗格登记簿（PaneRegistry）与心跳端点

**Files:**
- Modify: `backend/src/main/java/com/checkba/service/ai/SseEmitterService.java`（`emitters` 字段在 :24）
- Create: `backend/src/main/java/com/checkba/service/addin/PaneRegistry.java`
- Create: `backend/src/main/java/com/checkba/controller/addin/AddinPaneController.java`
- Test: `backend/src/test/java/com/checkba/service/addin/PaneRegistryTest.java`、`backend/src/test/java/com/checkba/service/ai/SseEmitterServiceConnectedTest.java`

**Interfaces:**
- Produces:
  - `SseEmitterService#isConnected(String connectionId): boolean`
  - `record PaneRegistry.PaneInfo(String paneId, Long userId, String host, String family, String docName, Long projectId, String conversationId, long lastSeenMs)`
  - `PaneRegistry#heartbeat(Long userId, PaneInfo info)`、`#bye(Long userId, String paneId)`、`#list(Long userId, String excludePaneId): List<PaneInfo>`、`#find(Long userId, String paneId): Optional<PaneInfo>`、`#paneOfConversation(Long userId, String conversationId): Optional<PaneInfo>`
  - `POST /api/addin/panes/heartbeat` body `{paneId, host, family, docName, projectId, conversationId}` → `{code:0}`；`POST /api/addin/panes/bye` body `{paneId}` → `{code:0}`（也接受 `text/plain` 的 JSON，sendBeacon 发的是 text/plain）

- [ ] **Step 1: Write the failing tests**

```java
// PaneRegistryTest.java
package com.checkba.service.addin;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class PaneRegistryTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final PaneRegistry registry = new PaneRegistry(now::get);

    private PaneRegistry.PaneInfo pane(String id, String conv) {
        return new PaneRegistry.PaneInfo(id, 7L, "word", "office", id + ".docx", 11L, conv, 0);
    }

    @Test void heartbeatThenListExcludesSelf() {
        registry.heartbeat(7L, pane("A", "conv-a"));
        registry.heartbeat(7L, pane("B", "conv-b"));
        assertThat(registry.list(7L, "A")).extracting(PaneRegistry.PaneInfo::paneId).containsExactly("B");
    }

    @Test void otherUsersPanesAreInvisible() {
        registry.heartbeat(7L, pane("A", "conv-a"));
        assertThat(registry.list(8L, null)).isEmpty();
        assertThat(registry.find(8L, "A")).isEmpty();
    }

    @Test void expiresAfter90Seconds() {
        registry.heartbeat(7L, pane("B", "conv-b"));
        now.addAndGet(90_001);
        assertThat(registry.list(7L, null)).isEmpty();
    }

    @Test void byeRemovesImmediately() {
        registry.heartbeat(7L, pane("B", "conv-b"));
        registry.bye(7L, "B");
        assertThat(registry.find(7L, "B")).isEmpty();
    }

    @Test void reHeartbeatUpdatesConversation() {
        registry.heartbeat(7L, pane("B", "conv-1"));
        registry.heartbeat(7L, pane("B", "conv-2"));
        assertThat(registry.find(7L, "B").orElseThrow().conversationId()).isEqualTo("conv-2");
        assertThat(registry.paneOfConversation(7L, "conv-2")).isPresent();
        assertThat(registry.paneOfConversation(7L, "conv-1")).isEmpty();
    }

    @Test void blankPaneIdIgnored() {
        registry.heartbeat(7L, pane("", "c"));
        assertThat(registry.list(7L, null)).isEmpty();
    }
}
```

```java
// SseEmitterServiceConnectedTest.java
package com.checkba.service.ai;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SseEmitterServiceConnectedTest {
    @Test void unknownConnectionIsNotConnected() {
        SseEmitterService svc = new SseEmitterService();
        assertThat(svc.isConnected("conv-x")).isFalse();
        assertThat(svc.isConnected(null)).isFalse();
    }
}
```
（若 `SseEmitterService` 构造器需要参数，按其现有构造器传 mock；先读文件确认。连接后为 true 的正向断言用现有 `createConnection` 的调用方式补一条。）

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=PaneRegistryTest,SseEmitterServiceConnectedTest`
Expected: 编译失败（PaneRegistry / isConnected 不存在）。

- [ ] **Step 3: Implement**

```java
// SseEmitterService.java 新增（放在 send 方法前）
/** 该连接当前是否有活着的 emitter。跨窗格下发前用它判离线，避免白等 30 秒超时。 */
public boolean isConnected(String connectionId) {
    return connectionId != null && emitters.containsKey(connectionId);
}
```

```java
// PaneRegistry.java
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.addin;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 同一账号下「当前开着的插件窗格」登记簿（dev-board#717）。进程内存，单区域单 JVM 前提。
 * 窗格每 30 秒心跳一次，90 秒无心跳视为已关。
 */
@Service
public class PaneRegistry {
    static final long EXPIRY_MS = 90_000;

    public record PaneInfo(String paneId, Long userId, String host, String family, String docName,
                           Long projectId, String conversationId, long lastSeenMs) {
        PaneInfo withSeen(Long uid, long ms) {
            return new PaneInfo(paneId, uid, host, family, docName, projectId, conversationId, ms);
        }
    }

    private final Map<Long, Map<String, PaneInfo>> byUser = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public PaneRegistry() { this(System::currentTimeMillis); }
    PaneRegistry(LongSupplier clock) { this.clock = clock; }

    public void heartbeat(Long userId, PaneInfo info) {
        if (userId == null || info == null || info.paneId() == null || info.paneId().isBlank()) return;
        byUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
              .put(info.paneId(), info.withSeen(userId, clock.getAsLong()));
    }

    public void bye(Long userId, String paneId) {
        if (userId == null || paneId == null) return;
        Map<String, PaneInfo> m = byUser.get(userId);
        if (m != null) m.remove(paneId);
    }

    public List<PaneInfo> list(Long userId, String excludePaneId) {
        Map<String, PaneInfo> m = byUser.get(userId);
        if (m == null) return List.of();
        long cutoff = clock.getAsLong() - EXPIRY_MS;
        m.values().removeIf(p -> p.lastSeenMs() < cutoff);
        List<PaneInfo> out = new ArrayList<>();
        for (PaneInfo p : m.values()) {
            if (!p.paneId().equals(excludePaneId)) out.add(p);
        }
        out.sort(Comparator.comparing(PaneInfo::docName, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    public Optional<PaneInfo> find(Long userId, String paneId) {
        return list(userId, null).stream().filter(p -> p.paneId().equals(paneId)).findFirst();
    }

    public Optional<PaneInfo> paneOfConversation(Long userId, String conversationId) {
        if (conversationId == null) return Optional.empty();
        return list(userId, null).stream().filter(p -> conversationId.equals(p.conversationId())).findFirst();
    }
}
```

```java
// AddinPaneController.java
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.controller.addin;

import com.checkba.controller.AuthController;
import com.checkba.exception.GlobalExceptionHandler;
import com.checkba.service.addin.PaneRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/addin/panes")
@RequiredArgsConstructor
public class AddinPaneController {
    private final PaneRegistry registry;
    private final ObjectMapper mapper;

    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) return Map.of("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED, "message", "未登录");
        Object pid = body.get("projectId");
        Long projectId = pid == null || String.valueOf(pid).isBlank() ? null : Long.valueOf(String.valueOf(pid));
        registry.heartbeat(userId, new PaneRegistry.PaneInfo(
                str(body.get("paneId")), userId, str(body.get("host")), str(body.get("family")),
                str(body.get("docName")), projectId, str(body.get("conversationId")), 0));
        return Map.of("code", 0);
    }

    /** sendBeacon 只能发 text/plain，且带不了自定义头：会话令牌放在 body.token 里。 */
    @PostMapping(value = "/bye", consumes = {"application/json", "text/plain"})
    public Map<String, Object> bye(@RequestBody String raw,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) throws Exception {
        Map<?, ?> body = mapper.readValue(raw, Map.class);
        String token = sessionId != null ? sessionId : str(body.get("token"));
        Long userId = AuthController.getUserIdFromSession(token);
        if (userId == null) return Map.of("code", GlobalExceptionHandler.CODE_UNAUTHENTICATED, "message", "未登录");
        registry.bye(userId, str(body.get("paneId")));
        return Map.of("code", 0);
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
```
（`GlobalExceptionHandler` 的包名以现有 AuthController import 为准。）

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=PaneRegistryTest,SseEmitterServiceConnectedTest`
Expected: PASS。

- [ ] **Step 5: Commit**（主会话）

```bash
git add backend/src/main/java/com/checkba/service/ai/SseEmitterService.java backend/src/main/java/com/checkba/service/addin/PaneRegistry.java backend/src/main/java/com/checkba/controller/addin/AddinPaneController.java backend/src/test/java/com/checkba/service/addin/PaneRegistryTest.java backend/src/test/java/com/checkba/service/ai/SseEmitterServiceConnectedTest.java
git commit -m "feat(addin): 插件窗格登记簿与心跳端点（dev-board#717）"
```

---

### Task 2: 跨窗格下发（OfficeBridgeService.executeOnPane）

**Files:**
- Modify: `backend/src/main/java/com/checkba/service/ai/OfficeBridgeService.java`（`executeOfficeCommand` 在 :86-132）
- Test: `backend/src/test/java/com/checkba/service/ai/OfficeBridgeCrossPaneTest.java`（仿 `OfficeBridgeServiceTest` 手工 mock 风格）

**Interfaces:**
- Consumes: `SseEmitterService#isConnected`、`PaneRegistry.PaneInfo`（Task 1）
- Produces:
  - `record OfficeBridgeService.CrossPaneOrigin(String paneId, String docName, String conversationId)`
  - `OfficeBridgeService#executeOnPane(PaneRegistry.PaneInfo target, String command, Map<String,Object> args, CrossPaneOrigin origin): String`（返回值格式与 `executeOfficeCommand` 相同：成功为 data 的 JSON，失败为 `errorJson(...)`）
  - SSE `client_action` 载荷在跨窗格时多一个键 `origin: {paneId, docName, conversationId}`

- [ ] **Step 1: Write the failing test**

```java
package com.checkba.service.ai;

import com.checkba.service.addin.PaneRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OfficeBridgeCrossPaneTest {
    private final SseEmitterService sse = mock(SseEmitterService.class);
    private final ObjectMapper om = new ObjectMapper();
    private final OfficeBridgeService bridge = new OfficeBridgeService(sse, om);
    private final PaneRegistry.PaneInfo target =
            new PaneRegistry.PaneInfo("B", 7L, "word", "office", "B.docx", 11L, "conv-b", 0);
    private final OfficeBridgeService.CrossPaneOrigin origin =
            new OfficeBridgeService.CrossPaneOrigin("A", "A.docx", "conv-a");

    @Test void offlineTargetFailsImmediatelyWithoutSending() {
        when(sse.isConnected("conv-b")).thenReturn(false);
        String out = bridge.executeOnPane(target, "get_text", Map.of(), origin);
        assertThat(out).contains("B.docx").contains("没有连着");
        verify(sse, never()).send(anyString(), anyString(), any());
    }

    @Test void sendsToTargetConversationWithOrigin() throws Exception {
        when(sse.isConnected("conv-b")).thenReturn(true);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<String> f = ex.submit(() -> bridge.executeOnPane(target, "get_text", Map.of(), origin));
        verify(sse, timeout(2000)).send(eq("conv-b"), eq("client_action"), payload.capture());
        Map<?, ?> sent = om.readValue((String) payload.getValue(), Map.class);
        assertThat(((Map<?, ?>) sent.get("origin")).get("docName")).isEqualTo("A.docx");
        bridge.completeOfficeAction((String) sent.get("requestId"), true, Map.of("text", "hi"), null);
        assertThat(f.get(2, TimeUnit.SECONDS)).contains("hi");
        assertThat(bridge.getPendingConversationId((String) sent.get("requestId"))).isNull();
        ex.shutdown();
    }
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=OfficeBridgeCrossPaneTest`
Expected: 编译失败（executeOnPane / CrossPaneOrigin 不存在）。

- [ ] **Step 3: Implement** —— 把 `executeOfficeCommand` 的主体抽成私有 `dispatch(String conversationId, String command, Map<String,Object> args, Map<String,Object> extra)`，原方法改为 `return dispatch(conversationId, command, args, null);`，`extra` 非空时 `payload.putAll(extra)`。新增：

```java
public record CrossPaneOrigin(String paneId, String docName, String conversationId) { }

/**
 * 把命令下发到同一账号的另一个窗格（dev-board#717）。目标没连着就立刻失败，不白等超时。
 * 归属校验仍由 OfficeResultController 按目标会话做。
 */
public String executeOnPane(com.checkba.service.addin.PaneRegistry.PaneInfo target, String command,
                            Map<String, Object> args, CrossPaneOrigin origin) {
    if (target == null || !sseEmitterService.isConnected(target.conversationId())) {
        String name = target == null ? "目标文档" : "《" + target.docName() + "》";
        return errorJson(name + "的 AI WorkDeck 窗格当前没有连着，请在该文档里打开 AI WorkDeck 窗格后重试。");
    }
    Map<String, Object> o = new java.util.HashMap<>();
    o.put("paneId", origin.paneId());
    o.put("docName", origin.docName());
    o.put("conversationId", origin.conversationId());
    return dispatch(target.conversationId(), command, args, Map.of("origin", o));
}
```

- [ ] **Step 4: Run** `mvn -q test -Dtest=OfficeBridgeCrossPaneTest,OfficeBridgeServiceTest` → PASS（既有用例不回归）。

- [ ] **Step 5: Commit**（主会话）

```bash
git add backend/src/main/java/com/checkba/service/ai/OfficeBridgeService.java backend/src/test/java/com/checkba/service/ai/OfficeBridgeCrossPaneTest.java
git commit -m "feat(addin): 命令可下发到同账号另一窗格并携带来源（dev-board#717）"
```

---
### Task 3: 参考来源核心、ref_* 工具、可见性与末位规则

**Files:**
- Create: `backend/src/main/java/com/checkba/service/ai/ref/{RefSource,RefQuery,RefEntry,RefSourceException,ReferenceSourceService}.java`
- Create: `backend/src/main/java/com/checkba/service/ai/tools/ReferenceTools.java`
- Modify: `backend/src/main/java/com/checkba/service/ai/ClientCapabilityService.java`（`isToolVisible` :141）
- Modify: `backend/src/main/java/com/checkba/service/ai/ContextAssemblerService.java`（中文 :478-483，英文 :1509-1516）
- Test: `backend/src/test/java/com/checkba/service/ai/ref/ReferenceSourceServiceTest.java`；在 `ClientCapabilityServiceTest`（若无则新建）加 ref_ 可见性用例；在 `ContextAssemblerServiceTest` 加中英末位规则用例

**Interfaces:**
- Produces（后续 Task 4-9 都实现 `RefSource`）：

```java
public interface RefSource {
    /** ref 前缀：open / desk / cloud / case / git */
    String scheme();
    /** 该来源在本上下文是否可用（例如 SG 没有案件库）；不可用时 list 跳过、read 报「来源不可用」。 */
    default boolean available(RefQuery q) { return true; }
    List<RefEntry> list(RefQuery q);
    /** body = ref 去掉 "scheme:" 之后的部分；返回纯文本（未截断，由 service 统一截断）。 */
    String read(RefQuery q, String body, String locator);
    /** 只有 open 来源实现；其他来源默认拒绝。 */
    default String edit(RefQuery q, String body, String command, Map<String, Object> args) {
        throw new RefSourceException("该文件没有打开，不能直接修改。请用户先打开它，并在该文档里打开 AI WorkDeck 窗格。");
    }
    /** 只有 desk 来源实现。 */
    default String open(RefQuery q, String body) {
        throw new RefSourceException("只有桌面端项目里的文件可以代为打开。");
    }
}
public record RefQuery(Long userId, Long projectId, String conversationId, String query) { }
public record RefEntry(String ref, String source, String name, String path, String host, String updatedAt, Boolean openable) { }
/** 面向模型的、可直接转述给用户的失败原因。 */
public class RefSourceException extends RuntimeException { public RefSourceException(String m) { super(m); } }
```

- `ReferenceSourceService(List<RefSource> sources)`：
  - `String list(RefQuery q, String sourceFilter)`：按固定顺序 `open, desk, cloud, case, git` 逐个 `available→list`，合并成文本清单（每行 `ref | source | path | 附加`），总数 ≤100；某来源抛异常则追加一行 `[desk] 不可用：<message>`；全空时返回「没有找到匹配的文件。可以请用户手动上传，或换个关键词。」
  - `String read(RefQuery q, String ref, String locator)`：解析 `scheme:body`，未知前缀 → 「无法识别的引用：请先用 ref_list 获取 ref」；结果经 `cap(String)` 截断到 200,000 并附 `\n...(截断)`；空文本 → 「该文件没有可读取的文字。」
  - `String edit(RefQuery q, String ref, String command, Map<String,Object> args)`、`String open(RefQuery q, String ref)`：同样分派，`RefSourceException` 转为 `错误：<message>`。
  - `static String cap(String text)` 包可见供测试。
- `ReferenceTools implements AgentToolComponent`（`@Component`），四个 `@Tool`：`ref_list(String query, String source, Long userId, Long projectId, String conversationId)`、`ref_read(String ref, String locator, ...)`、`ref_edit(String ref, String command, String argsJson, ...)`、`ref_open(String ref, ...)`，后三个参数由 `SERVER_CONTEXT_PARAMS` 注入。`argsJson` 用 ObjectMapper 解析成 Map，非法 JSON 回「错误：args 必须是 JSON 对象」。

- [ ] **Step 1: Write failing tests**

```java
package com.checkba.service.ai.ref;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReferenceSourceServiceTest {
    static RefSource fake(String scheme, List<RefEntry> entries, String text, RuntimeException listErr) {
        return new RefSource() {
            public String scheme() { return scheme; }
            public List<RefEntry> list(RefQuery q) { if (listErr != null) throw listErr; return entries; }
            public String read(RefQuery q, String body, String locator) { return text; }
        };
    }
    final RefQuery q = new RefQuery(7L, 11L, "conv-a", null);

    @Test void listMergesInFixedOrderAndReportsFailures() {
        var svc = new ReferenceSourceService(List.of(
            fake("cloud", List.of(new RefEntry("cloud:5", "cloud", "C.docx", "C.docx", null, null, null)), "", null),
            fake("desk", List.of(), "", new RefSourceException("设备离线")),
            fake("open", List.of(new RefEntry("open:B", "open", "B.docx", "B.docx", "word", null, null)), "", null)));
        String out = svc.list(q, null);
        assertThat(out.indexOf("open:B")).isLessThan(out.indexOf("cloud:5"));
        assertThat(out).contains("[desk] 不可用：设备离线");
    }

    @Test void sourceFilterLimitsToOne() {
        var svc = new ReferenceSourceService(List.of(
            fake("open", List.of(new RefEntry("open:B", "open", "B", "B", null, null, null)), "", null),
            fake("cloud", List.of(new RefEntry("cloud:5", "cloud", "C", "C", null, null, null)), "", null)));
        assertThat(svc.list(q, "cloud")).contains("cloud:5").doesNotContain("open:B");
    }

    @Test void emptyListIsNeverBlank() {
        var svc = new ReferenceSourceService(List.of(fake("open", List.of(), "", null)));
        assertThat(svc.list(q, null)).isNotBlank();
    }

    @Test void readDispatchesAndCaps() {
        String big = "x".repeat(200_010);
        var svc = new ReferenceSourceService(List.of(fake("cloud", List.of(), big, null)));
        String out = svc.read(q, "cloud:5", null);
        assertThat(out).endsWith("...(截断)");
        assertThat(out.length()).isLessThan(200_100);
    }

    @Test void unknownSchemeAndBlankTextHaveMessages() {
        var svc = new ReferenceSourceService(List.of(fake("cloud", List.of(), "  ", null)));
        assertThat(svc.read(q, "zzz:1", null)).contains("ref_list");
        assertThat(svc.read(q, "cloud:5", null)).contains("没有可读取的文字");
    }

    @Test void editOnReadOnlySourceIsRefused() {
        var svc = new ReferenceSourceService(List.of(fake("cloud", List.of(), "t", null)));
        assertThat(svc.edit(q, "cloud:5", "replace_text", Map.of())).startsWith("错误：").contains("没有打开");
    }
}
```

可见性用例（加到 `ClientCapabilityServiceTest`）：注册一个 OFFICE 会话与一个 LOWA 会话（按该测试文件现有的登记方式），断言 `isToolVisible("ref_read", officeConv)` 为 true、`isToolVisible("ref_read", lowaConv)` 为 false、`isToolVisible("read_file", lowaConv)` 仍为 true。

末位规则用例（加到 `ContextAssemblerServiceTest`，沿用其 OFFICE 会话装配夹具）：中文装配结果包含「ref_edit」与「未打开的文件一律不能修改」，且该段仍位于 Office 分支文本的最后；英文装配包含「ref_edit」与「Files that are not open must never be edited」。

- [ ] **Step 2: Run** `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test -Dtest=ReferenceSourceServiceTest,ClientCapabilityServiceTest,ContextAssemblerServiceTest` → 失败。

- [ ] **Step 3: Implement**

`ReferenceSourceService` 核心：

```java
@Service
public class ReferenceSourceService {
    static final int MAX_CHARS = 200_000;
    static final List<String> ORDER = List.of("open", "desk", "cloud", "case", "git");
    static final int MAX_ENTRIES = 100;
    private final Map<String, RefSource> byScheme = new LinkedHashMap<>();

    public ReferenceSourceService(List<RefSource> sources) {
        for (String s : ORDER) for (RefSource src : sources) if (src.scheme().equals(s)) byScheme.put(s, src);
    }

    public String list(RefQuery q, String sourceFilter) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (RefSource src : byScheme.values()) {
            if (sourceFilter != null && !sourceFilter.isBlank() && !src.scheme().equals(sourceFilter.trim())) continue;
            if (!src.available(q)) continue;
            try {
                for (RefEntry e : src.list(q)) {
                    if (n >= MAX_ENTRIES) break;
                    sb.append(e.ref()).append(" | ").append(e.source()).append(" | ").append(e.path());
                    if (e.host() != null) sb.append(" | ").append(e.host());
                    if (Boolean.TRUE.equals(e.openable())) sb.append(" | openable");
                    sb.append('\n');
                    n++;
                }
            } catch (RuntimeException ex) {
                sb.append('[').append(src.scheme()).append("] 不可用：").append(ex.getMessage()).append('\n');
            }
        }
        if (n == 0 && sb.isEmpty()) return "没有找到匹配的文件。可以请用户手动上传，或换个关键词。";
        return sb.toString().trim();
    }

    public String read(RefQuery q, String ref, String locator) {
        return route(ref, (src, body) -> {
            String text = src.read(q, body, locator);
            return text == null || text.isBlank() ? "该文件没有可读取的文字。" : cap(text);
        });
    }
    public String edit(RefQuery q, String ref, String command, Map<String, Object> args) {
        return route(ref, (src, body) -> src.edit(q, body, command, args));
    }
    public String open(RefQuery q, String ref) {
        return route(ref, (src, body) -> src.open(q, body));
    }

    private String route(String ref, java.util.function.BiFunction<RefSource, String, String> fn) {
        int i = ref == null ? -1 : ref.indexOf(':');
        RefSource src = i <= 0 ? null : byScheme.get(ref.substring(0, i));
        if (src == null) return "错误：无法识别的引用，请先用 ref_list 获取 ref。";
        try { return fn.apply(src, ref.substring(i + 1)); }
        catch (RefSourceException e) { return "错误：" + e.getMessage(); }
    }

    static String cap(String text) {
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + "\n...(截断)";
    }
}
```

`ClientCapabilityService.isToolVisible` 在方法开头 `toolName == null` 判定之后插入：

```java
if (toolName.startsWith("ref_")) {
    return capabilityOf(conversationId) == Capability.OFFICE;
}
```

`ReferenceTools` 的 `@Tool` 描述（模型可见，中文）：
- `ref_list`：「列出可作为参考材料的文件：其他打开着的 Office/WPS 文档、桌面端项目文件、云端项目文件、官方案件库、已关联的 git 仓库。query 为文件名关键字，可空；source 可选 open/desk/cloud/case/git。返回每行一个 ref，读取或修改时原样使用 ref。」
- `ref_read`：「读取参考文件的文字。ref 来自 ref_list。locator 可选：page:N（Word 页）、slide:N（演示稿页）、sheet:名称 或 sheet:名称!A1:D20（表格）、heading:标题文字。按页定位只对打开着的文档有效。」
- `ref_edit`：「修改另一个打开着的文档（只允许 open: 开头的 ref）。command 与 args 使用该文档所在软件对应的 office 命令名与参数（与 office_* 工具下发的命令相同，例如 replace_text、excel_set_values、ppt_replace_text），argsJson 为 JSON 对象。修改会以修订/修订记录的形式显示在那个文档自己的窗格里。」
- `ref_open`：「请桌面端用系统默认程序打开项目里的文件（只允许 ref_list 标了 openable 的 desk: ref），用于用户要求修改一个尚未打开的文件时。打开后请用户在该文档里打开 AI WorkDeck 窗格。」

`ContextAssemblerService` 中文 :478-483 六行整体替换为：

```java
systemText.append("**本会话直接编辑的是上面这一份打开的文档。** ");
systemText.append("用户提到其他文件时：需要参考内容，用 ref_list 找到它再用 ref_read 读取，不要凭上一轮的印象作答；");
systemText.append("需要修改另一个**打开着**的文档，用 ref_edit，并且只改用户要求改的那个文档，");
systemText.append("绝不能把本该写进那个文件的内容改写进当前这份文档。");
systemText.append("**未打开的文件一律不能修改**：说明它没有打开，桌面端项目里的文件可用 ref_open 代为打开，");
systemText.append("然后请用户在那个文档里打开 AI WorkDeck 窗格，停下来等用户。\n\n");
```

英文 :1509-1516 对应替换为：

```java
sb.append("**This session edits the one open document described above.** ")
  .append("When the user refers to another file: to use its content, find it with ref_list and read it with ref_read - ")
  .append("never answer for it from an earlier impression. To change another document that is **open**, use ref_edit, ")
  .append("change only the document the user asked for, and never write content meant for that file into the current one. ")
  .append("**Files that are not open must never be edited**: say it is not open; a desktop-project file can be opened ")
  .append("with ref_open; then ask the user to open the AI WorkDeck task pane in that document, and stop and wait.\n\n");
```

- [ ] **Step 4: Run** 同 Step 2 → PASS；再跑 `mvn -q test -Dtest='ContextAssembler*,ClientCapability*,ToolRegistry*'` 确认无回归。

- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(ai): 参考来源分派与 ref_* 工具，插件会话末位规则改为可读可改打开文档（dev-board#717）"`

---

### Task 4: OpenDocSource（其他打开文档的读与写）

**Files:**
- Create: `backend/src/main/java/com/checkba/service/ai/ref/OpenDocSource.java`
- Test: `backend/src/test/java/com/checkba/service/ai/ref/OpenDocSourceTest.java`

**Interfaces:**
- Consumes: `PaneRegistry`（Task 1）、`OfficeBridgeService#executeOnPane`、`CrossPaneOrigin`（Task 2）、`RefSource`（Task 3）
- Produces: `OpenDocSource implements RefSource`，scheme `open`；读取下发窗格命令 `read_for_reference`，args `{locator}`（插件端 Task 11 实现），返回 JSON `{text}`，本类取出 `text`；写入把 `command/args` 原样下发。

- [ ] **Step 1: Write failing test**

```java
package com.checkba.service.ai.ref;

import com.checkba.service.addin.PaneRegistry;
import com.checkba.service.ai.OfficeBridgeService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenDocSourceTest {
    final PaneRegistry registry = new PaneRegistry();
    final OfficeBridgeService bridge = mock(OfficeBridgeService.class);
    final OpenDocSource src = new OpenDocSource(registry, bridge, new com.fasterxml.jackson.databind.ObjectMapper());
    final RefQuery q = new RefQuery(7L, 11L, "conv-a", null);

    OpenDocSourceTest() {
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("A", 7L, "word", "office", "A.docx", 11L, "conv-a", 0));
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("B", 7L, "powerpoint", "office", "B.pptx", 11L, "conv-b", 0));
    }

    @Test void listExcludesCallerPaneAndFiltersByQuery() {
        assertThat(src.list(q)).extracting(RefEntry::ref).containsExactly("open:B");
        assertThat(src.list(new RefQuery(7L, 11L, "conv-a", "zzz"))).isEmpty();
    }

    @Test void readSendsReadForReferenceWithOrigin() {
        when(bridge.executeOnPane(any(), eq("read_for_reference"), any(), any())).thenReturn("{\"text\":\"第3页内容\"}");
        assertThat(src.read(q, "B", "slide:3")).isEqualTo("第3页内容");
        verify(bridge).executeOnPane(argThat(p -> p.paneId().equals("B")), eq("read_for_reference"),
                eq(Map.of("locator", "slide:3")),
                argThat(o -> o.docName().equals("A.docx") && o.paneId().equals("A")));
    }

    @Test void readErrorBecomesRefSourceException() {
        when(bridge.executeOnPane(any(), any(), any(), any())).thenReturn("{\"error\":\"本机 Word 不支持按页读取\"}");
        assertThatThrownBy(() -> src.read(q, "B", "page:3")).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("按页读取");
    }

    @Test void unknownPaneIsReportedAsClosed() {
        assertThatThrownBy(() -> src.read(q, "Z", null)).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("已经关闭");
    }

    @Test void editPassesCommandThrough() {
        when(bridge.executeOnPane(any(), eq("replace_text"), any(), any())).thenReturn("{\"replaced\":2}");
        assertThat(src.edit(q, "B", "replace_text", Map.of("find", "甲", "replace", "乙"))).contains("replaced");
    }

    @Test void cannotEditSelf() {
        assertThatThrownBy(() -> src.edit(q, "A", "replace_text", Map.of())).isInstanceOf(RefSourceException.class);
    }
}
```
（`errorJson` 的实际格式以 `OfficeBridgeService.errorJson` 为准；若不是 `{"error":...}`，按实际格式调整本测试与实现的判定。）

- [ ] **Step 2: Run** `mvn -q test -Dtest=OpenDocSourceTest` → 失败。

- [ ] **Step 3: Implement**

```java
@Component
@RequiredArgsConstructor
public class OpenDocSource implements RefSource {
    private final PaneRegistry registry;
    private final OfficeBridgeService bridge;
    private final ObjectMapper mapper;

    public String scheme() { return "open"; }

    public List<RefEntry> list(RefQuery q) {
        String self = registry.paneOfConversation(q.userId(), q.conversationId()).map(PaneRegistry.PaneInfo::paneId).orElse(null);
        String kw = q.query() == null ? "" : q.query().trim().toLowerCase(Locale.ROOT);
        List<RefEntry> out = new ArrayList<>();
        for (PaneRegistry.PaneInfo p : registry.list(q.userId(), self)) {
            String name = p.docName() == null ? "(未命名)" : p.docName();
            if (!kw.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(kw)) continue;
            out.add(new RefEntry("open:" + p.paneId(), "open", name, name, p.family() + "/" + p.host(), null, null));
        }
        return out;
    }

    public String read(RefQuery q, String body, String locator) {
        Map<String, Object> args = new HashMap<>();
        if (locator != null && !locator.isBlank()) args.put("locator", locator.trim());
        String json = bridge.executeOnPane(target(q, body), "read_for_reference", args, origin(q));
        Map<?, ?> m = parse(json);
        Object text = m.get("text");
        return text == null ? "" : String.valueOf(text);
    }

    public String edit(RefQuery q, String body, String command, Map<String, Object> args) {
        PaneRegistry.PaneInfo t = target(q, body);
        if (q.conversationId() != null && q.conversationId().equals(t.conversationId()))
            throw new RefSourceException("这是当前文档本身，请直接用 office_* 工具修改。");
        String json = bridge.executeOnPane(t, command, args == null ? Map.of() : args, origin(q));
        parse(json); // 失败时抛出
        return json;
    }

    private PaneRegistry.PaneInfo target(RefQuery q, String paneId) {
        return registry.find(q.userId(), paneId).orElseThrow(() ->
                new RefSourceException("这个文档的窗格已经关闭或超过 90 秒没有响应，请在该文档里重新打开 AI WorkDeck 窗格。"));
    }

    private OfficeBridgeService.CrossPaneOrigin origin(RefQuery q) {
        var self = registry.paneOfConversation(q.userId(), q.conversationId());
        return new OfficeBridgeService.CrossPaneOrigin(
                self.map(PaneRegistry.PaneInfo::paneId).orElse(null),
                self.map(PaneRegistry.PaneInfo::docName).orElse("另一个文档"),
                q.conversationId());
    }

    private Map<?, ?> parse(String json) {
        try {
            Object v = mapper.readValue(json, Object.class);
            if (v instanceof Map<?, ?> m) {
                if (m.containsKey("error")) throw new RefSourceException(String.valueOf(m.get("error")));
                return m;
            }
            return Map.of("text", String.valueOf(v));
        } catch (RefSourceException e) { throw e; }
        catch (Exception e) { return Map.of("text", json); }
    }
}
```

- [ ] **Step 4: Run** `mvn -q test -Dtest=OpenDocSourceTest,ReferenceSourceServiceTest` → PASS。

- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(addin): 经窗格读写其他打开文档（dev-board#717）"`

---
### Task 5: 文字抽取器抽出、按路径找文件、CloudProjectSource

**Files:**
- Create: `backend/src/main/java/com/checkba/service/file/ProjectFileTextExtractor.java`
- Modify: `backend/src/main/java/com/checkba/service/ai/tools/FileTools.java`（`extract_file_text` :264-318 与 `extractWithOcr` :335 的抽取部分改为委托）
- Modify: `backend/src/main/java/com/checkba/service/ProjectFileService.java`（新增两个方法）
- Create: `backend/src/main/java/com/checkba/service/ai/ref/CloudProjectSource.java`
- Test: `ProjectFileTextExtractorTest.java`、`ProjectFileServicePathTest.java`（`@SpringBootTest` + H2，仿 `DesktopContextSmokeTest` 的属性写法）、`CloudProjectSourceTest.java`

**Interfaces:**
- Produces:
  - `ProjectFileTextExtractor#extract(ProjectFile pf): String` —— 与 `extract_file_text` 完全相同的路由（OCR 优先判定、Tika/PDFBox、空文本回落 OCR、`[System:` 前缀视为失败抛 `IOException`）；超过 50MB 抛 `IOException("文件超过 50MB，暂不支持作为参考材料读取")`；文件夹抛 `IOException("这是一个文件夹")`。
  - `ProjectFileTextExtractor#extractBytes(String fileName, byte[] bytes): String` —— 给不在项目文件表里的字节用（案件库、git）：txt/md/csv/json 按 UTF-8 解码；pdf 走 `DocumentTextService.parsePdf`；其余走 `DocumentTextService.parse`；超过 50MB 抛同样的 IOException。测试补一条：`extractBytes("a.txt", "中文".getBytes(UTF_8))` 返回「中文」。
  - `ProjectFileService#findByRelativePath(Long projectId, String relPath): Optional<ProjectFile>` —— 以 `/` 分段、从根逐层按名称匹配未删除条目；拒绝空段、`.`、`..`。
  - `ProjectFileService#listRelativePaths(Long projectId, String keyword, int limit): List<Map.Entry<String, ProjectFile>>` —— 列出项目内全部未删除的非文件夹条目及其相对路径，按关键字（文件名包含，忽略大小写）过滤，按路径排序，截到 limit。
  - `CloudProjectSource implements RefSource`，scheme `cloud`，ref body = fileId；read 校验 `pf.projectId` 属于用户可读项目（`ProjectMemberService#hasReadPermission`）。

- [ ] **Step 1: 先读现状**：读 `FileTools.java:250-360` 与 `ProjectFileService` 里获取子条目的方法（`getFilesByParent` 等）与 `ProjectFileRepository` 的按项目查询方法，记下真实方法名。

- [ ] **Step 2: Write failing tests**

```java
// ProjectFileServicePathTest（节选；夹具按现有 ProjectFileService 建目录/文件的 API 建 "合同/主合同.docx" 与 "附件/清单.xlsx"）
@Test void findsNestedFileByPath() {
    assertThat(service.findByRelativePath(projectId, "合同/主合同.docx")).get()
        .extracting(ProjectFile::getName).isEqualTo("主合同.docx");
}
@Test void rejectsTraversalAndMissing() {
    assertThat(service.findByRelativePath(projectId, "../x")).isEmpty();
    assertThat(service.findByRelativePath(projectId, "合同/不存在.docx")).isEmpty();
    assertThat(service.findByRelativePath(projectId, "合同//主合同.docx")).isEmpty();
}
@Test void listsPathsWithKeyword() {
    assertThat(service.listRelativePaths(projectId, "清单", 100)).extracting(Map.Entry::getKey)
        .containsExactly("附件/清单.xlsx");
    assertThat(service.listRelativePaths(projectId, null, 1)).hasSize(1);
}
```

```java
// CloudProjectSourceTest（Mockito）
@Test void refusesFileFromUnreadableProject() {
    ProjectFile pf = new ProjectFile(); pf.setId(5L); pf.setProjectId(99L); pf.setName("C.docx");
    when(fileService.getFile(5L)).thenReturn(pf);
    when(members.hasReadPermission(99L, 7L)).thenReturn(false);
    assertThatThrownBy(() -> src.read(q, "5", null)).isInstanceOf(RefSourceException.class);
}
@Test void readsViaExtractorAndNotesLocatorIgnored() throws Exception {
    ProjectFile pf = new ProjectFile(); pf.setId(5L); pf.setProjectId(11L); pf.setName("C.docx");
    when(fileService.getFile(5L)).thenReturn(pf);
    when(members.hasReadPermission(11L, 7L)).thenReturn(true);
    when(extractor.extract(pf)).thenReturn("正文");
    assertThat(src.read(q, "5", "page:2")).startsWith("未打开的文件无法按页定位，以下为全文").endsWith("正文");
    assertThat(src.read(q, "5", null)).isEqualTo("正文");
}
@Test void listsCurrentProjectFiles() {
    ProjectFile pf = new ProjectFile(); pf.setId(5L); pf.setName("C.docx");
    when(fileService.listRelativePaths(11L, null, 100)).thenReturn(List.of(Map.entry("资料/C.docx", pf)));
    assertThat(src.list(q)).extracting(RefEntry::ref).containsExactly("cloud:5");
}
```
（`getFile` 的真实名称与返回类型以 Step 1 读到的为准；返回 Optional 就相应调整。）

`ProjectFileTextExtractorTest`：用一个临时 `.txt` 项目文件（经 StorageService 写入）断言 `extract` 返回原文；mock 一个 60MB 大小的 `ProjectFile.fileSize` 断言抛出含「50MB」的 IOException。

- [ ] **Step 3: Run** `mvn -q test -Dtest=ProjectFileServicePathTest,CloudProjectSourceTest,ProjectFileTextExtractorTest` → 失败。

- [ ] **Step 4: Implement**
  - `ProjectFileTextExtractor`：`@Service`，构造器注入 `DocumentTextService`、`FileContentExtractorService`、`StorageServiceFactory`（与 FileTools 现有依赖同名同类），把 FileTools 里 `hasTextLayerCandidate`、OCR 路由、`extractWithOcr` 临时文件逻辑整体搬进来；FileTools 的 `extract_file_text` 保留文件夹描述分支与返回文案，抽取调用改为 `extractor.extract(pf)`，异常文案保持原样（跑 `FileToolsTest`/`ReadDocumentOfficeFormatTest` 保证不回归）。
  - `CloudProjectSource`：

```java
public String read(RefQuery q, String body, String locator) {
    ProjectFile pf = /* fileService.getFile(Long.valueOf(body)) */;
    if (pf == null || !members.hasReadPermission(pf.getProjectId(), q.userId()))
        throw new RefSourceException("找不到这个文件，或你没有这个项目的读取权限。");
    String text;
    try { text = extractor.extract(pf); }
    catch (IOException e) { throw new RefSourceException(e.getMessage()); }
    return withLocatorNote(locator, text);
}
/** 未打开的来源没有页概念：带 locator 时明说返回全文（四个只读来源共用，放在 RefSource 的 static 方法里）。 */
static String withLocatorNote(String locator, String text) {
    return locator == null || locator.isBlank() ? text : "未打开的文件无法按页定位，以下为全文。\n\n" + text;
}
```
  把 `withLocatorNote` 定义为 `RefSource` 接口的 `static` 方法（签名 `static String withLocatorNote(String locator, String text)`），Task 6/8/9 复用。

- [ ] **Step 5: Run** Step 3 的测试 + `mvn -q test -Dtest='FileTools*,ReadDocumentOfficeFormatTest,FolderContextOfficeFormatTest'` → PASS。

- [ ] **Step 6: Commit**（主会话）`git commit -m "feat(ai): 抽出项目文件文字抽取器，云端项目文件作为参考来源（dev-board#718）"`

---

### Task 6: 桌面端门铃流、参考请求登记簿与云端端点、DesktopSource

**Files:**
- Create: `backend/src/main/java/com/checkba/service/mobile/DesktopStreamService.java`
- Create: `backend/src/main/java/com/checkba/service/mobile/ReferenceRequestStore.java`
- Create: `backend/src/main/java/com/checkba/controller/MobileRefController.java`
- Create: `backend/src/main/java/com/checkba/service/ai/ref/DesktopSource.java`
- Test: `DesktopStreamServiceTest.java`、`ReferenceRequestStoreTest.java`、`MobileRefControllerTest.java`（MockMvc standalone）、`DesktopSourceTest.java`

**Interfaces:**
- Produces（Task 7 桌面端按此契约实现）：
  - `GET /api/mobile/desktop/stream?deviceId=` （`X-Session-Id: awdt_…`，`text/event-stream`）：连上立刻发 `event: ready`；每 15 秒 `event: ping`；有待办时 `event: nudge` data `{"kind":"ref"}` 或 `{"kind":"transfer"}`。同一 (userId, deviceId) 后连顶掉先连（先连收到 `event: superseded` 后关闭）。
  - `GET /api/mobile/ref/requests?deviceId=` → `{code:0, requests:[{id, kind:"LIST"|"READ"|"OPEN", projectKey, path?, keyword?}]}`（取出即标记为 DISPATCHED，不重复下发）
  - `POST /api/mobile/ref/{id}/result` body `{ok:true, entries:[{path,name,updatedAt,openable}]}`（LIST）或 `{ok:true, text:"..."}`（READ）或 `{ok:true, opened:true}`（OPEN）或 `{ok:false, error:"..."}` → `{code:0}`；请求不属于该用户 → 403；已过期/已完成 → `{code:0, stale:true}`
  - `DesktopStreamService#connect(Long userId, String deviceId): SseEmitter`、`#nudge(Long userId, String deviceId, String kind): boolean`（返回是否在线送达）、`#isOnline(Long userId, String deviceId): boolean`、`#lastSeenMs(Long userId, String deviceId): OptionalLong`
  - `ReferenceRequestStore#submit(Long userId, String deviceId, String kind, String projectKey, String path, String keyword): Pending`（`record Pending(String id, CompletableFuture<Map<String,Object>> future)`）、`#take(Long userId, String deviceId): List<Map<String,Object>>`、`#complete(Long userId, String id, Map<String,Object> result): Outcome`（`enum Outcome { OK, STALE, FORBIDDEN }`）；TTL 60 秒由 `@Scheduled(fixedDelay = 10_000)` 清扫并以 `ok:false,error:"超时"` 完成 future。
  - `DesktopSource implements RefSource`，scheme `desk`，ref body = `deviceId:projectKey:path`（path 可含 `:`，只按前两个 `:` 切）。

- [ ] **Step 1: Write failing tests**

```java
// ReferenceRequestStoreTest
@Test void submitTakeCompleteRoundTrip() throws Exception {
    var store = new ReferenceRequestStore(now::get);
    var p = store.submit(7L, "dev1", "READ", "42", "合同/A.docx", null);
    var taken = store.take(7L, "dev1");
    assertThat(taken).hasSize(1).first().satisfies(m -> assertThat(m.get("path")).isEqualTo("合同/A.docx"));
    assertThat(store.take(7L, "dev1")).isEmpty();              // 不重复下发
    assertThat(store.complete(8L, p.id(), Map.of("ok", true))).isEqualTo(ReferenceRequestStore.Outcome.FORBIDDEN);
    assertThat(store.complete(7L, p.id(), Map.of("ok", true, "text", "t"))).isEqualTo(ReferenceRequestStore.Outcome.OK);
    assertThat(p.future().get(1, TimeUnit.SECONDS)).containsEntry("text", "t");
    assertThat(store.complete(7L, p.id(), Map.of("ok", true))).isEqualTo(ReferenceRequestStore.Outcome.STALE);
}
@Test void expiredRequestsCompleteWithTimeout() throws Exception {
    var store = new ReferenceRequestStore(now::get);
    var p = store.submit(7L, "dev1", "LIST", "42", null, null);
    now.addAndGet(60_001);
    store.sweep();
    assertThat(p.future().get(1, TimeUnit.SECONDS)).containsEntry("ok", false);
}
```

```java
// DesktopStreamServiceTest
@Test void nudgeReturnsFalseWhenOffline() {
    var svc = new DesktopStreamService();
    assertThat(svc.nudge(7L, "dev1", "ref")).isFalse();
    assertThat(svc.isOnline(7L, "dev1")).isFalse();
}
@Test void secondConnectSupersedesFirst() {
    var svc = new DesktopStreamService();
    SseEmitter a = svc.connect(7L, "dev1");
    SseEmitter b = svc.connect(7L, "dev1");
    assertThat(svc.isOnline(7L, "dev1")).isTrue();
    assertThat(a).isNotSameAs(b);
}
```

```java
// DesktopSourceTest（Mockito：DesktopStreamService、ReferenceRequestStore、AddinProjectLinkRepository、MobileProjectDirRepository）
@Test void readOfflineDeviceFailsFastWithLastSeen() {
    when(stream.isOnline(7L, "dev1")).thenReturn(false);
    assertThatThrownBy(() -> src.read(q, "dev1:42:合同/A.docx", null))
        .isInstanceOf(RefSourceException.class).hasMessageContaining("离线");
    verifyNoInteractions(store);
}
@Test void readWaitsForDesktopText() {
    when(stream.isOnline(7L, "dev1")).thenReturn(true);
    var future = CompletableFuture.completedFuture(Map.<String, Object>of("ok", true, "text", "正文"));
    when(store.submit(7L, "dev1", "READ", "42", "合同/A.docx", null)).thenReturn(new ReferenceRequestStore.Pending("r1", future));
    assertThat(src.read(q, "dev1:42:合同/A.docx", null)).isEqualTo("正文");
    verify(stream).nudge(7L, "dev1", "ref");
}
@Test void listUsesBoundProject() {
    // 当前会话项目 11 绑定到 (dev1, 42)
    when(links.findByCloudProjectId(11L)).thenReturn(List.of(link("dev1", "42")));
    when(stream.isOnline(7L, "dev1")).thenReturn(true);
    var future = CompletableFuture.completedFuture(Map.<String, Object>of("ok", true,
        "entries", List.of(Map.of("path", "合同/A.docx", "name", "A.docx", "openable", true))));
    when(store.submit(eq(7L), eq("dev1"), eq("LIST"), eq("42"), isNull(), any())).thenReturn(new ReferenceRequestStore.Pending("r2", future));
    assertThat(src.list(q)).extracting(RefEntry::ref).containsExactly("desk:dev1:42:合同/A.docx");
}
@Test void unboundProjectListsOnlineDevicesProjects() {
    when(links.findByCloudProjectId(11L)).thenReturn(List.of());
    when(dirs.findByUserId(7L)).thenReturn(List.of(dir("dev1", "42", "某某案")));
    when(stream.isOnline(7L, "dev1")).thenReturn(true);
    // 未绑定时不下发 LIST，只返回项目条目，提示模型 ref_list(query=项目名) 再选
    assertThat(src.list(q)).extracting(RefEntry::name).contains("某某案");
}
```
（`AddinProjectLinkRepository.findByCloudProjectId` 返回类型、`MobileProjectDir` 仓库查询方法以现有代码为准，按需在仓库接口补 `findByUserId`。）

- [ ] **Step 2: Run** → 失败。

- [ ] **Step 3: Implement**
  - `DesktopStreamService`：`Map<String, SseEmitter>`（键 `userId + "|" + deviceId`）+ `Map<String, Long> lastSeen`；`connect` 建 `new SseEmitter(0L)`（不超时，靠 ping 探活），旧的先 `send(event superseded)` 再 `complete()`；`onCompletion/onTimeout/onError` 只在 map 里仍是自己时移除；`@Scheduled(fixedDelay = 15_000)` 对所有连接发 `ping`，发送失败即移除；`nudge` 发送失败返回 false。
  - `MobileRefController` 挂 `/api/mobile`：`GET /desktop/stream`（鉴权同 `MobileTransferController.requireUser`；返回 emitter 前 `store.touchDevice(userId, deviceId)`——即 `MobileRelayStoreService#touchDevice`，保持设备心跳）、`GET /ref/requests`、`POST /ref/{id}/result`（FORBIDDEN → `ResponseEntity.status(403)`）。日志只打 id、kind、`text` 长度。
  - `MobileTransferService` 建 LIST/PULL/PUSH 命令行后追加一次 `desktopStreamService.nudge(userId, deviceId, "transfer")`（送达失败无害，桌面端 60 秒轮询兜底）。
  - `DesktopSource`：

```java
static final long WAIT_SECONDS = 60;
public String read(RefQuery q, String body, String locator) {
    String[] p = split(body); // [deviceId, projectKey, path]
    requireOnline(q.userId(), p[0]);
    Map<String, Object> r = await(store.submit(q.userId(), p[0], "READ", p[1], p[2], null), p[0], q.userId());
    return RefSource.withLocatorNote(locator, String.valueOf(r.getOrDefault("text", "")));
}
public String open(RefQuery q, String body) {
    String[] p = split(body);
    requireOnline(q.userId(), p[0]);
    await(store.submit(q.userId(), p[0], "OPEN", p[1], p[2], null), p[0], q.userId());
    return "已在设备《" + deviceName(q.userId(), p[0]) + "》上用默认程序打开该文件。请用户在该文档里打开 AI WorkDeck 窗格后继续。";
}
private void requireOnline(Long uid, String deviceId) {
    if (!stream.isOnline(uid, deviceId)) {
        String last = stream.lastSeenMs(uid, deviceId).stream()
            .mapToObj(ms -> "，最后在线 " + java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDateTime().withNano(0).toString().replace('T', ' ')).findFirst().orElse("");
        throw new RefSourceException("设备《" + deviceName(uid, deviceId) + "》离线" + last + "。请打开桌面端，或手动上传文件。");
    }
}
private Map<String, Object> await(ReferenceRequestStore.Pending pending, String deviceId, Long uid) {
    stream.nudge(uid, deviceId, "ref");
    try {
        Map<String, Object> r = pending.future().get(WAIT_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(r.get("ok"))) throw new RefSourceException(String.valueOf(r.getOrDefault("error", "桌面端处理失败")));
        return r;
    } catch (TimeoutException e) {
        throw new RefSourceException("桌面端 60 秒内未响应，可稍后重试。");
    } catch (InterruptedException | ExecutionException e) {
        throw new RefSourceException("桌面端处理失败：" + e.getMessage());
    }
}
static String[] split(String body) {
    int a = body.indexOf(':'), b = a < 0 ? -1 : body.indexOf(':', a + 1);
    if (a <= 0 || b <= a + 1 || b == body.length() - 1) throw new RefSourceException("无法识别的引用，请先用 ref_list 获取 ref。");
    return new String[] { body.substring(0, a), body.substring(a + 1, b), body.substring(b + 1) };
}
```
  `deviceName` 取 `MobileDeviceState.deviceName`（无则 deviceId 前 8 位）。`list` 三种情形：
  1. 当前会话项目有绑定（`findByCloudProjectId`）：对每个绑定设备，在线则发 `LIST`（projectKey=绑定的 key，keyword=q.query()），entries 映射为 `desk:<dev>:<key>:<path>`，`openable` 透传；离线则抛 `RefSourceException`（service 记成一行不可用）。
  2. 无绑定且 `q.query()` 非空：对用户每台在线设备发一次 `LIST`，`projectKey="*"`（桌面端按文件名跨全部项目搜索，Task 7），每个 entry 自带 `projectKey`，ref 用它拼。
  3. 无绑定且 query 为空：不发请求，返回在线设备的项目清单（`MobileProjectDir`），ref 为 `desk:<dev>:<key>:`（path 为空），name 为项目名；对这种 ref 调 `read` 抛「这是一个项目，请用 ref_list 并带上文件名关键字查找其中的文件」。
  测试 `unboundProjectListsOnlineDevicesProjects` 对应情形 3；为情形 2 补一条用例：query=「清单」时对 dev1 发出 `projectKey="*"` 的 LIST。

- [ ] **Step 4: Run** Step 1 测试 + `mvn -q test -Dtest='MobileTransfer*'` → PASS。

- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(mobile): 桌面端门铃流与参考读取请求中转，桌面项目文件作为参考来源（dev-board#718 #719）"`

---
### Task 7: 桌面端——门铃流客户端与 LIST/READ/OPEN 处理

**Files:**
- Modify: `backend/src/main/java/com/checkba/service/mobile/MobileRelayClientService.java`（字段 :75-108，构造器 :117-144，`pollInbox` :204-231，`pollTransferCommands` :549-583，热窗口 :820-858，HTTP 帮手 `authed`/`send`/`request` :867-935）
- Create: `backend/src/main/java/com/checkba/service/mobile/DesktopRefHandler.java`
- Test: `DesktopRefHandlerTest.java`（`@SpringBootTest` + H2 + `@ActiveProfiles("desktop")`，夹具建真项目与文件）、`MobileRelayClientRefTest.java`（仿 `MobileRelayClientHttpTest` 的桩 HTTP 服务器写法）

**Interfaces:**
- Consumes: Task 6 的三个端点与 SSE 事件名；Task 5 的 `ProjectFileService#findByRelativePath`、`#listRelativePaths`、`ProjectFileTextExtractor#extract`
- Produces:
  - `DesktopRefHandler#handle(Map<String,Object> request): Map<String,Object>` —— 输入为 `/ref/requests` 的单条请求，输出为 `/ref/{id}/result` 的 body
  - `MobileRelayClientService#pollReferenceRequests()`（包可见）与门铃线程

- [ ] **Step 1: Write failing tests**

```java
// DesktopRefHandlerTest（节选）
@Test void listReturnsPathsOfProjectWithKeyword() {
    var out = handler.handle(Map.of("id", "r1", "kind", "LIST", "projectKey", String.valueOf(projectId), "keyword", "清单"));
    assertThat(out).containsEntry("ok", true);
    assertThat((List<Map<String, Object>>) out.get("entries")).extracting(m -> m.get("path")).containsExactly("附件/清单.xlsx");
    assertThat((List<Map<String, Object>>) out.get("entries")).allSatisfy(m -> assertThat(m.get("openable")).isEqualTo(true));
}
@Test void listStarSearchesAllProjectsAndCarriesProjectKey() {
    var out = handler.handle(Map.of("id", "r2", "kind", "LIST", "projectKey", "*", "keyword", "主合同"));
    assertThat((List<Map<String, Object>>) out.get("entries")).extracting(m -> m.get("projectKey")).containsOnly(String.valueOf(projectId));
}
@Test void readExtractsText() {
    var out = handler.handle(Map.of("id", "r3", "kind", "READ", "projectKey", String.valueOf(projectId), "path", "说明.txt"));
    assertThat(out).containsEntry("ok", true).containsEntry("text", "说明正文");
}
@Test void traversalAndUnknownProjectAreRejected() {
    assertThat(handler.handle(Map.of("id", "r4", "kind", "READ", "projectKey", String.valueOf(projectId), "path", "../etc/passwd")))
        .containsEntry("ok", false);
    assertThat(handler.handle(Map.of("id", "r5", "kind", "READ", "projectKey", "999999", "path", "a.txt")))
        .containsEntry("ok", false);
}
@Test void openUsesInjectedOpener() {
    // handler 构造时注入 Consumer<Path> opener；测试里记录被打开的路径，不真的调系统程序
    handler.handle(Map.of("id", "r6", "kind", "OPEN", "projectKey", String.valueOf(projectId), "path", "说明.txt"));
    assertThat(opened).singleElement().satisfies(p -> assertThat(p.toString()).endsWith("说明.txt"));
}
```

```java
// MobileRelayClientRefTest（桩服务器）
@Test void nudgeTriggersImmediateFetchAndResultPost() { /* 桩 /api/mobile/desktop/stream 发 ready 后发 nudge{kind:ref}；
   桩 /api/mobile/ref/requests 返回一条 READ；断言 2 秒内收到 POST /api/mobile/ref/r1/result 且 body.ok=true */ }
@Test void stream404PinsAndKeepsPolling() { /* 桩对 stream 回 404；断言 streamUnsupported=true 且不再请求 stream，
   pollInbox 仍照常请求 /api/mobile/inbox */ }
@Test void refRequests404PinsSilently() { /* 桩对 /ref/requests 回 404；断言 refUnsupported=true，后续 nudge 不再请求 */ }
```
（三个用例按 `MobileRelayClientHttpTest` 现有的桩服务器、令牌与 `active()` 前提构造方式写全；门铃线程的重连退避常量做成包可见，测试里缩短到 50ms。）

- [ ] **Step 2: Run** `mvn -q test -Dtest=DesktopRefHandlerTest,MobileRelayClientRefTest` → 失败。

- [ ] **Step 3: Implement**
  - `DesktopRefHandler`（`@Service`，依赖 `ProjectRepository`、`ProjectFileService`、`ProjectFileTextExtractor`、`ProjectStorageResolver`，外加包可见构造器参数 `Consumer<Path> opener`，默认实现：macOS `new ProcessBuilder("open", path.toString()).start()`，Windows `new ProcessBuilder("cmd", "/c", "start", "\"\"", path.toString()).start()`，其他系统 `xdg-open`）：
    - 所有分支 try/catch，失败返回 `{ok:false, error:<中文原因>}`，从不抛出。
    - `projectKey` 解析为 `Long`，`projectRepository.existsById` 为假 → 「桌面端没有这个项目」。
    - LIST：`listRelativePaths(projectId, keyword, 200)` → `entries:[{path, name, updatedAt, openable:true, projectKey}]`；`projectKey="*"` 时遍历 `projectRepository.findAll()`（跳过已删除），每项目 `listRelativePaths(pid, keyword, 50)`，合计截 200。
    - READ：`findByRelativePath` 为空 → 「项目里没有这个文件，请用 ref_list 重新查找」；`extractor.extract(pf)`；返回 `{ok:true, text}`。
    - OPEN：解析出物理路径 = `ProjectStorageResolver.resolve(pf 的 storageKey)`，`toRealPath()` 后必须 `startsWith(projectRoot(projectId).toRealPath())`，否则拒绝（符号链接逃逸）；`opener.accept(path)`；返回 `{ok:true, opened:true}`。
  - `MobileRelayClientService`：
    - 新字段：`volatile boolean streamUnsupported`、`volatile boolean refUnsupported`、`ExecutorService streamExecutor`（单 daemon 线程 `mobile-relay-doorbell`）、包可见 `long doorbellMinBackoffMs = 1_000`、`long doorbellMaxBackoffMs = 60_000`。
    - `@PostConstruct` 之后（或首次 `pollInbox` 时）若 `active()` 则启动 `runDoorbell()`：循环 `GET {baseUrl}/api/mobile/desktop/stream?deviceId=` 用 `HttpResponse.BodyHandlers.ofLines()` 逐行读；`event: nudge` 的下一行 `data:` 含 `"ref"` → `pollReferenceRequests()`，含 `"transfer"` → `pollTransferCommands()`；`superseded` → 退出本轮并按最大退避等待；404 → `streamUnsupported=true` 退出线程；其他断开 → 指数退避（1s 起翻倍，封顶 60s，成功收到 `ready` 后重置）；`!active()` 时退出。令牌失效（401）走 `invalidateToken()` 后重试一次，同 `authed` 惯例。
    - `pollReferenceRequests()`：`refUnsupported` 为真直接返回；`GET /api/mobile/ref/requests?deviceId=`，404 → 钉死；逐条 `handler.handle(req)` 后 `POST /api/mobile/ref/{id}/result`。日志只记 id、kind、耗时与 text 长度。
    - `pollInbox()` 的 finally 链末尾追加 `pollReferenceRequests()`（门铃断线时 60 秒轮询兜底）。

- [ ] **Step 4: Run** Step 2 测试 + `mvn -q test -Dtest='MobileRelayClient*'` → PASS（既有 hot 窗口「至少 N 次」断言不应受影响；若受影响按「改下限断言」惯例处理）。

- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(desktop): 与云端常连的门铃流，按需回传项目文件文字并可代为打开文件（dev-board#718 #719）"`

---

### Task 8: 官方案件库来源（case 实例内部端点 + addin 侧客户端）

**Files:**
- Create: `backend/src/main/java/com/checkba/controller/internal/InternalRefController.java`
- Create: `backend/src/main/java/com/checkba/service/ai/ref/CaseRefClient.java`、`CaseLibrarySource.java`
- Modify: `backend/src/main/resources/application.yml`（新增 `ref.internal.secret: ${AWD_REF_INTERNAL_SECRET:}`、`ref.case.base-url: ${AWD_REF_CASE_BASE_URL:}`）
- Test: `InternalRefControllerTest.java`（MockMvc standalone + 临时 JGit 仓库，仿 `ShareCloneRoundTripTest` 建仓方式）、`CaseLibrarySourceTest.java`

**Interfaces:**
- Produces:
  - case 侧：`POST /api/internal/ref/list` body `{externalAccountId, keyword}` → `{code:0, entries:[{remoteProjectId, projectName, path, name}]}`；`POST /api/internal/ref/read` body `{externalAccountId, remoteProjectId, path}` → `{code:0, text}` 或 `{code:<非0>, message}`。
  - 两端点：`ref.internal.secret` 为空 → 404；请求头 `X-Internal-Secret` 不等（常量时间比较 `MessageDigest.isEqual`）→ 404；`request.getRemoteAddr()` 不是回环地址 → 404。
  - addin 侧：`CaseRefClient#list(String externalAccountId, String keyword)`、`#read(String externalAccountId, long remoteProjectId, String path)`；`CaseLibrarySource implements RefSource`，scheme `case`，ref body = `remoteProjectId:path`；`available(q)` = `ref.case.base-url` 与 `ref.internal.secret` 都非空且用户有 `AccountBinding`。

- [ ] **Step 1: Write failing tests**

```java
// InternalRefControllerTest（节选）
@Test void missingSecretConfigIs404() { /* ref.internal.secret="" → 404 */ }
@Test void wrongSecretIs404() { /* 头不等 → 404 */ }
@Test void nonLoopbackIs404() { /* MockMvc with(request -> { request.setRemoteAddr("10.0.0.5"); return request; }) → 404 */ }
@Test void unknownAccountReturnsEmptyList() { /* 无 AccountBinding → entries=[] */ }
@Test void clientRoleCannotRead() { /* ProjectMemberService.isClient=true → code!=0，message 含「没有读取权限」 */ }
@Test void readsHeadBlobAsText() {
    /* 夹具：用户 U 绑定 externalAccountId="acc-1"，项目 P 是 U 可读的非客户角色，
       仓库 HEAD 有 "资料/说明.txt"="说明正文" → POST read 返回 text="说明正文" */
}
@Test void listFiltersByKeywordAcrossReadableProjects() { /* keyword="说明" → 只返回该文件 */ }
```

```java
// CaseLibrarySourceTest（mock CaseRefClient、AccountBindingRepository）
@Test void unavailableWithoutBinding() { when(bindings.findByUserId(7L)).thenReturn(Optional.empty()); assertThat(src.available(q)).isFalse(); }
@Test void readUsesExternalAccountIdAndLocatorNote() {
    when(bindings.findByUserId(7L)).thenReturn(Optional.of(binding("acc-1")));
    when(client.read("acc-1", 3L, "资料/说明.txt")).thenReturn("说明正文");
    assertThat(src.read(q, "3:资料/说明.txt", "page:1")).startsWith("未打开的文件无法按页定位");
}
@Test void clientErrorsBecomeRefSourceException() { /* client 抛 IOException → RefSourceException「案件库暂时无法访问」 */ }
```
（`AccountBindingRepository.findByUserId` 的返回类型以现有代码为准。）

- [ ] **Step 2: Run** → 失败。

- [ ] **Step 3: Implement**
  - `InternalRefController`：list = 按 `externalAccountId` 找本地用户 → 该用户可读的项目（沿用 `ProjectMemberService` 现有「我参与的项目」查询；排除 `isClient`）→ 每个项目 `repoService.resolveRef(pid, "HEAD")` 非空时 `listPaths(pid, "HEAD")`，过滤 `.awd/` 前缀与关键字，截 200；read = 权限判定 → `readBlobAtCommit(pid, "HEAD", path)`（50MB 闸已内置，`VersionException` 透传其 userFacing 文案）→ `ProjectFileTextExtractor#extractBytes(path, bytes)`（Task 5）。
  - `CaseRefClient`：`java.net.http.HttpClient`，超时 20 秒，POST JSON 带 `X-Internal-Secret`；非 2xx 或 `code!=0` 抛 `IOException(message)`。
  - `CaseLibrarySource`：list 映射为 `case:<remoteProjectId>:<path>`，`path` 显示为「案件库/<项目名>/<path>」。

- [ ] **Step 4: Run** → PASS。

- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(case): 案件库经本机内部端点作为只读参考来源（dev-board#720）"`

---
### Task 9: GitHub / Gitee 仓库关联与只读来源

**Files:**
- Create: `backend/src/main/java/com/checkba/model/entity/AddinGitRepoLink.java`、`repository/AddinGitRepoLinkRepository.java`
- Create: `backend/src/main/java/com/checkba/service/addin/GitTokenCipher.java`、`GitProviderClient.java`
- Create: `backend/src/main/java/com/checkba/controller/addin/AddinGitLinkController.java`
- Create: `backend/src/main/java/com/checkba/service/ai/ref/GitProviderSource.java`
- Modify: `application.yml`（`addin.git.token-secret: ${AWD_GIT_TOKEN_SECRET:}`、`addin.git.github-api: https://api.github.com`、`addin.git.gitee-api: https://gitee.com/api/v5`）
- Test: `GitTokenCipherTest.java`、`GitProviderClientTest.java`（`com.sun.net.httpserver.HttpServer` 桩）、`AddinGitLinkControllerTest.java`、`GitProviderSourceTest.java`

**Interfaces:**
- Produces:
  - 实体字段：`id, userId, cloudProjectId, provider("github"|"gitee"), owner, repo, branch, tokenEnc, tokenLast4, createdAt, lastOkAt, lastError`；唯一 `(user_id, cloud_project_id, provider, owner, repo)`。
  - `GitTokenCipher(@Value("${addin.git.token-secret:}") String secret)`：`boolean enabled()`、`String encrypt(String)`、`String decrypt(String)`——实现照抄 `PlatformAiKeyCipher` 的 AES-256-GCM（SHA-256 派生密钥、随机 12 字节 IV、Base64(iv‖ct)），但密钥来源独立。
  - `GitProviderClient`：`record RepoRef(String provider, String owner, String repo, String branch)`；`static RepoRef parseUrl(String url, String branch)`（接受 `https://github.com/o/r(.git)`、`https://gitee.com/o/r(.git)`、`git@github.com:o/r.git`，其他主机抛 `IllegalArgumentException("只支持 GitHub 与 Gitee 仓库")`）；`void verify(RepoRef, String token)`；`List<String> listPaths(RepoRef, String token)`（GitHub `GET /repos/{o}/{r}/git/trees/{branch}?recursive=1` 取 `type=blob`；Gitee `GET /repos/{o}/{r}/git/trees/{branch}?recursive=1&access_token=`）；`byte[] readFile(RepoRef, String token, String path)`（GitHub `GET /repos/{o}/{r}/contents/{path}?ref={branch}` 带 `Accept: application/vnd.github.raw`；Gitee `GET /repos/{o}/{r}/raw/{path}?ref=&access_token=`），超过 50MB 抛；401/403 抛 `GitAuthException`，404 抛 `FileNotFoundException`。GitHub 用 `Authorization: Bearer`。路径段逐段 URL 编码。
  - `POST /api/addin/git-links` body `{projectId, url, branch, token}` → `{code:0, link:{id, provider, owner, repo, branch, tokenLast4}}`（先 `verify`，失败回 `{code:400, message}`；cipher 未启用回 `{code:503, message:"服务器未配置 git 令牌密钥"}`）；`GET /api/addin/git-links?projectId=` → `{code:0, links:[…]}`（不含令牌）；`DELETE /api/addin/git-links/{id}` → `{code:0}`（仅本人）。项目须 `hasReadPermission`。
  - `GitProviderSource`：scheme `git`，ref body = `linkId:path`；list 对当前项目的全部关联逐个 `listPaths`（关键字过滤，每仓库截 100）；read 解密令牌 → `readFile` → `ProjectFileTextExtractor#extractBytes(path, bytes)`（Task 5）→ `RefSource.withLocatorNote`；`GitAuthException` → 更新 `lastError` 并抛「git 仓库授权失效，请在设置里重新填写令牌」；成功更新 `lastOkAt`。

- [ ] **Step 1: Write failing tests**

```java
// GitTokenCipherTest
@Test void roundTripAndDistinctCiphertexts() {
    var c = new GitTokenCipher("s3cret");
    String a = c.encrypt("ghp_abc"), b = c.encrypt("ghp_abc");
    assertThat(a).isNotEqualTo(b);
    assertThat(c.decrypt(a)).isEqualTo("ghp_abc");
}
@Test void disabledWithoutSecret() { assertThat(new GitTokenCipher("").enabled()).isFalse(); }
```

```java
// GitProviderClientTest（节选）
@Test void parsesSupportedUrls() {
    assertThat(GitProviderClient.parseUrl("https://github.com/acme/docs.git", "main"))
        .isEqualTo(new GitProviderClient.RepoRef("github", "acme", "docs", "main"));
    assertThat(GitProviderClient.parseUrl("git@gitee.com:acme/docs.git", null).branch()).isEqualTo("master");
    assertThatThrownBy(() -> GitProviderClient.parseUrl("https://gitlab.com/a/b", "main")).hasMessageContaining("GitHub 与 Gitee");
}
@Test void githubTreeAndRawRead() { /* 桩 /repos/acme/docs/git/trees/main 返回两个 blob 一个 tree；
   断言 listPaths 只含两个 blob；桩 contents 返回字节，断言请求头 Authorization=Bearer t 与 Accept=raw */ }
@Test void unauthorizedBecomesGitAuthException() { /* 桩回 401 */ }
@Test void tokenNeverAppearsInExceptionMessages() { /* 桩回 500，断言异常消息不含令牌 */ }
```

```java
// GitProviderSourceTest（mock client、repo、cipher、extractor）
@Test void authFailureRecordsLastErrorAndExplains() { /* client.readFile 抛 GitAuthException → save(link.lastError!=null)，
   RefSourceException 消息含「重新填写令牌」 */ }
@Test void listPrefixesRefsWithLinkId() { /* link id=4，paths ["a/x.docx"] → ref "git:4:a/x.docx" */ }
```

`AddinGitLinkControllerTest`：未登录 401 语义（按现有 code 约定）；他人项目 403；cipher 未启用 503；成功后 GET 不含 `tokenEnc`；DELETE 他人链接不生效。

- [ ] **Step 2: Run** → 失败。
- [ ] **Step 3: Implement**（按上述契约；日志只记 provider/owner/repo/状态码，绝不记令牌与正文）。
- [ ] **Step 4: Run** → PASS。
- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(addin): 关联 GitHub/Gitee 仓库作为只读参考来源，令牌加密存储（dev-board#720）"`

---

### Task 10: 插件——窗格心跳与告别

**Files:**
- Create: `office-addin/taskpane/lib/paneHeartbeat.js`、`paneHeartbeat.test.js`
- Modify: `office-addin/taskpane/lib/api.js`（新增 `postPaneHeartbeat`、`paneByeUrl`）、`lib/chatSession.js`（`activateSession` :225-273 与会话 id 变化处调用）、`App.vue`（onMounted 启动、`pagehide` 发告别）

**Interfaces:**
- Consumes: Task 1 两个端点；chatSession 现有 `paneId`（建 SSE 时传的 `clientId`）
- Produces: `startHeartbeat({ getState, post, intervalMs = 30000, setIntervalFn, clearIntervalFn })` 返回 `{ beatNow(), stop() }`；`getState()` 返回 `{ paneId, host, family, docName, projectId, conversationId }` 或 `null`（未登录时不发）。`chatSession` 导出 `paneIdentity()` 返回 `{ paneId, conversationId, projectId }`，并在 `conversationId` 变化时调用注册进来的 `onIdentityChange` 回调。

- [ ] **Step 1: Write failing test**

```js
// paneHeartbeat.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { startHeartbeat } from './paneHeartbeat.js'

test('beats immediately, on interval, and skips when state is null', async () => {
  const sent = []
  let tick = null
  let state = { paneId: 'p1', host: 'word', family: 'office', docName: 'A.docx', projectId: 11, conversationId: 'c1' }
  const hb = startHeartbeat({
    getState: () => state,
    post: async (body) => { sent.push(body) },
    setIntervalFn: (fn) => { tick = fn; return 1 },
    clearIntervalFn: () => { tick = null }
  })
  await Promise.resolve()
  assert.equal(sent.length, 1)
  state = null
  await tick()
  assert.equal(sent.length, 1)
  state = { ...state, paneId: 'p1', conversationId: 'c2' }
  hb.beatNow()
  await Promise.resolve()
  assert.equal(sent.at(-1).conversationId, 'c2')
  hb.stop()
  assert.equal(tick, null)
})

test('post failures are swallowed', async () => {
  const hb = startHeartbeat({
    getState: () => ({ paneId: 'p1' }),
    post: async () => { throw new Error('offline') },
    setIntervalFn: () => 1, clearIntervalFn: () => {}
  })
  await hb.beatNow()
})
```

- [ ] **Step 2: Run** `cd office-addin && node --test taskpane/lib/paneHeartbeat.test.js` → 失败。

- [ ] **Step 3: Implement**

```js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 窗格心跳（dev-board#717）：让云端知道「这个账号现在开着哪些文档的窗格」，供别的窗格跨文档读写。
export function startHeartbeat({ getState, post, intervalMs = 30000,
  setIntervalFn = setInterval, clearIntervalFn = clearInterval }) {
  async function beatNow() {
    const s = getState()
    if (!s || !s.paneId) return
    try { await post(s) } catch (e) { /* 心跳失败无害，下一轮再发；云端 90 秒过期兜底 */ }
  }
  const timer = setIntervalFn(beatNow, intervalMs)
  beatNow()
  return { beatNow, stop: () => clearIntervalFn(timer) }
}
```

  `api.js`：

```js
export async function postPaneHeartbeat({ serverUrl, token }, body) {
  const base = normalizeBaseUrl(serverUrl)
  const resp = await fetch(`${base}/api/addin/panes/heartbeat`, { method: 'POST', headers: headers(token), body: JSON.stringify(body) })
  if (!resp.ok) throw new Error('heartbeat ' + resp.status)
}
export function sendPaneBye({ serverUrl, token }, paneId) {
  const base = normalizeBaseUrl(serverUrl)
  const payload = JSON.stringify({ paneId, token })
  try {
    if (navigator.sendBeacon && navigator.sendBeacon(`${base}/api/addin/panes/bye`, new Blob([payload], { type: 'text/plain' }))) return
  } catch (e) { /* 落到 fetch keepalive */ }
  fetch(`${base}/api/addin/panes/bye`, { method: 'POST', headers: headers(token), body: payload, keepalive: true }).catch(() => {})
}
```

  `App.vue` onMounted：`const hb = startHeartbeat({ getState: () => settings.token ? { ...paneIdentity(), host: detectHost(), family: hostFamily(), docName: currentDocName.value } : null, post: (b) => postPaneHeartbeat(settings, b) })`；`onIdentityChange(() => hb.beatNow())`；`window.addEventListener('pagehide', () => sendPaneBye(settings, paneIdentity().paneId))`。`currentDocName` 用现有 `readDocumentMeta()`/`documentDisplayName` 取（Office：`Office.context.document.url` 末段；WPS：`ActiveDocument/ActiveWorkbook/ActivePresentation.Name`）。

- [ ] **Step 4: Run** `cd office-addin && npm test` → PASS。
- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(addin): 窗格心跳与关闭告别（dev-board#717）"`

---
### Task 11: 插件——`read_for_reference` 命令（按 locator 读取本文档）

**Files:**
- Create: `office-addin/taskpane/lib/referenceRead.js`、`referenceRead.test.js`
- Modify: `lib/officeExecutor.js`（`HANDLERS` :727 加 `read_for_reference`；`COMMAND_HOSTS` :3524 不登记——三宿主都可用）、`lib/wpsExecutor.js`（`requiredHostOf` 对 `read_for_reference` 返回当前宿主）、`lib/wpsWordHandlers.js`/`wpsEtHandlers.js`/`wpsWppHandlers.js`（各加 `read_for_reference`）、`lib/docSnapshot.js`（`READ_ONLY_COMMANDS` :26-31 加 `read_for_reference`）

**Interfaces:**
- Produces: `parseLocator(str) → { kind: 'page'|'slide'|'sheet'|'heading'|'none', n?, sheet?, range?, text? }`（非法格式抛 `Error('无法识别的定位：…')`）；`sliceByHeading(paragraphs, headingText) → string`（paragraphs 为 `[{text, isHeading, level}]`，返回从匹配标题到下一个同级或更高级标题之前的文字；未找到抛「没有找到标题：…」）。各宿主 handler 返回 `{ text }`。

- [ ] **Step 1: Write failing test**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseLocator, sliceByHeading } from './referenceRead.js'

test('parseLocator', () => {
  assert.deepEqual(parseLocator(''), { kind: 'none' })
  assert.deepEqual(parseLocator('page:3'), { kind: 'page', n: 3 })
  assert.deepEqual(parseLocator('slide:2'), { kind: 'slide', n: 2 })
  assert.deepEqual(parseLocator('sheet:报价!A1:D20'), { kind: 'sheet', sheet: '报价', range: 'A1:D20' })
  assert.deepEqual(parseLocator('sheet:报价'), { kind: 'sheet', sheet: '报价', range: '' })
  assert.deepEqual(parseLocator('heading:第三条 违约责任'), { kind: 'heading', text: '第三条 违约责任' })
  assert.throws(() => parseLocator('page:0'))
  assert.throws(() => parseLocator('foo:1'))
})

test('sliceByHeading stops at same-or-higher level', () => {
  const paras = [
    { text: '第一条', isHeading: true, level: 1 }, { text: 'a', isHeading: false },
    { text: '1.1', isHeading: true, level: 2 }, { text: 'b', isHeading: false },
    { text: '第二条', isHeading: true, level: 1 }, { text: 'c', isHeading: false }
  ]
  assert.equal(sliceByHeading(paras, '第一条'), '第一条\na\n1.1\nb')
  assert.equal(sliceByHeading(paras, '1.1'), '1.1\nb')
  assert.throws(() => sliceByHeading(paras, '第九条'), /没有找到标题/)
})
```

- [ ] **Step 2: Run** `cd office-addin && node --test taskpane/lib/referenceRead.test.js` → 失败。

- [ ] **Step 3: Implement**
  - `referenceRead.js`：上面两个纯函数（`heading` 匹配为去空白后包含）。
  - Office `read_for_reference(args)`（在 `officeExecutor.js`，按 `detectHost()` 分三支，均经 `truncate()`）：
    - Word：`none` → 与 `get_text` 同；`heading` → `Word.run` 加载 `body.paragraphs` 的 `text, styleBuiltIn, outlineLevel`，`isHeading = outlineLevel` 在 1-9，交给 `sliceByHeading`；`page` → `Office.context.requirements.isSetSupported('WordApiDesktop', '1.2')` 为假抛「本机 Word 不支持按页读取，可改用标题或关键词」，为真则 `context.document.activeWindow.activePane.pages` 取第 n 页 `getRange()` 的 `text`（n 越界抛「文档只有 N 页」）；`slide/sheet` → 抛「Word 文档不支持该定位」。
    - Excel：`none` → 与现有 `readExcelSheet()` 同（活动表 used range TSV）；`sheet` → `workbook.worksheets.getItemOrNullObject(sheet)`，不存在抛「没有名为 X 的工作表」，`range` 空则 used range，否则 `getRange(range)`，`values` 转 TSV（最多 2000 行，沿用 `MAX_EXCEL_ROWS`）。其他 kind 抛「表格不支持该定位」。
    - PowerPoint：`none` → 现有 `readPptSlides()`；`slide` → 只取第 n 张（与 `ppt_get_slide_details` 同一形状文本拼法）；其他 kind 抛「演示稿不支持该定位」。
  - WPS 三个 handler 同构：文字 `page` 用 `doc.GoTo(1 /*wdGoToPage*/, 1 /*wdGoToAbsolute*/, n)` 取页首，再 `GoTo` 第 n+1 页首（末页取到文末）组 Range 读 `Text`；表格 `sheet` 用 `Worksheets.Item(name).Range(range || UsedRange.Address)`；演示 `slide` 用 `Slides.Item(n)` 形状 `TextFrame.TextRange.Text`。**WPS 按页读取未经真机验证**，异常统一转「WPS 按页读取失败：<原因>」。

- [ ] **Step 4: Run** `cd office-addin && npm test` → PASS；`npm run build` 通过。
- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(addin): 窗格按页/幻灯片/工作表/标题读取本文档供其他窗格引用（dev-board#717）"`

---

### Task 12: 插件——跨文档写入的痕迹：强制修订、改前值、修订记录存储与撤销

**Files:**
- Create: `office-addin/taskpane/lib/revisionLog.js`、`revisionLog.test.js`、`lib/crossDocWrite.js`、`crossDocWrite.test.js`
- Modify: `lib/chatSession.js`（`handleClientAction` :1077-1116）、`lib/officeExecutor.js`（导出 `trackingSupported`）、`lib/wpsWordHandlers.js`（导出 `withTracking` 探测）

**Interfaces:**
- Consumes: SSE 载荷新增的 `origin`（Task 2）；`isReadOnlyCommand`（docSnapshot.js）
- Produces:
  - `revisionLog.js`（模块级 store，Vue `reactive`）：`entries`（当前文档的条目数组，新在前）、`unread`（ref 数字）、`bindDocument(docKey, storage = localStorage)`（加载 `awd_addin_revlog_{docKey}`）、`record({ originDocName, originConversationId, command, summary, before, after, undoable })` → 条目 `{ id, time, … }`（上限 200，超出丢最旧，持久化，`unread++`）、`markAllRead()`、`remove(id)`、`clear()`。
  - `crossDocWrite.js`：`async function runCrossDocWrite({ command, args, origin, host, family, exec, capture, trackingOk })` → `{ result, entry }`：
    - Word（office/wps）：`trackingOk()` 为假 → 返回 `{ result: { ok:false, error:'本机 Word 版本无法标记修订，已拒绝跨文档修改' } }`，不执行；为真 → `exec(command, {...args, __forceTracking: true})`，条目 `before/after` 为空、`undoable:false`（撤销走 Word 自己的拒绝修订）。
    - Excel / PPT：先 `capture(command, args)` 取改前值（返回 `{ target, before }` 或 `null`=无法取得），再 `exec`；成功且 `capture` 非空 → 条目 `undoable:true, before, after:args`；`capture` 为空 → `undoable:false`，summary 追加「（无法记录改前值）」。
    - 只读命令（`isReadOnlyCommand`）不产生条目。
  - `summarize(command, args)`：人话摘要（如 `replace_text` → 「将“甲”改为“乙”」，`excel_set_values` → 「写入 报价!B2:C3」，`ppt_replace_text` → 「第 2 页替换文字」），未知命令回退为命令显示名（`commandDisplayName`）。
  - `undoEntry(entry, { readCurrent, writeBack })`：`readCurrent(entry.before.target)` 与 `entry.after` 不一致 → 返回 `{ ok:false, conflict:true }`；一致 → `writeBack(entry.before)`。

- [ ] **Step 1: Write failing tests**

```js
// revisionLog.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'
const mem = new Map()
const storage = { getItem: (k) => mem.get(k) ?? null, setItem: (k, v) => mem.set(k, v), removeItem: (k) => mem.delete(k) }
const log = await import('./revisionLog.js')

test('records newest first, persists per document, caps at 200', () => {
  log.bindDocument('docA', storage)
  for (let i = 0; i < 205; i++) log.record({ originDocName: 'A.docx', command: 'replace_text', summary: 's' + i })
  assert.equal(log.entries.length, 200)
  assert.equal(log.entries[0].summary, 's204')
  assert.equal(log.unread.value, 205)
  log.bindDocument('docB', storage)
  assert.equal(log.entries.length, 0)
  log.bindDocument('docA', storage)
  assert.equal(log.entries.length, 200)
  log.markAllRead()
  assert.equal(log.unread.value, 0)
})
```

```js
// crossDocWrite.test.js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { runCrossDocWrite, undoEntry } from './crossDocWrite.js'
const origin = { docName: 'A.docx', conversationId: 'c-a', paneId: 'A' }

test('word refuses without tracking support', async () => {
  let ran = false
  const { result } = await runCrossDocWrite({ command: 'replace_text', args: {}, origin, host: 'word', family: 'office',
    exec: async () => { ran = true; return { ok: true } }, capture: async () => null, trackingOk: () => false })
  assert.equal(result.ok, false); assert.equal(ran, false)
})

test('word forces tracking and logs non-undoable entry', async () => {
  let seen
  const { result, entry } = await runCrossDocWrite({ command: 'replace_text', args: { find: '甲', replace: '乙' }, origin,
    host: 'word', family: 'office', exec: async (c, a) => { seen = a; return { ok: true, data: {} } },
    capture: async () => null, trackingOk: () => true })
  assert.equal(result.ok, true); assert.equal(seen.__forceTracking, true)
  assert.equal(entry.undoable, false); assert.equal(entry.originDocName, 'A.docx')
})

test('excel captures before value and is undoable', async () => {
  const { entry } = await runCrossDocWrite({ command: 'excel_set_values', args: { range: 'B2', values: [[2]] }, origin,
    host: 'excel', family: 'office', exec: async () => ({ ok: true, data: {} }),
    capture: async () => ({ target: { range: 'B2' }, before: { range: 'B2', values: [[1]] } }), trackingOk: () => true })
  assert.equal(entry.undoable, true); assert.deepEqual(entry.before.values, [[1]])
})

test('read-only commands produce no entry', async () => {
  const { entry } = await runCrossDocWrite({ command: 'get_text', args: {}, origin, host: 'word', family: 'office',
    exec: async () => ({ ok: true, data: {} }), capture: async () => null, trackingOk: () => true })
  assert.equal(entry, null)
})

test('undo detects conflict', async () => {
  const entry = { before: { target: { range: 'B2' }, values: [[1]] }, after: { range: 'B2', values: [[2]] } }
  const r = await undoEntry(entry, { readCurrent: async () => ({ range: 'B2', values: [[9]] }), writeBack: async () => {} })
  assert.equal(r.conflict, true)
})
```

- [ ] **Step 2: Run** `cd office-addin && node --test taskpane/lib/revisionLog.test.js taskpane/lib/crossDocWrite.test.js` → 失败。

- [ ] **Step 3: Implement**
  - `crossDocWrite.js` 按上面契约；`capture` 的真实实现放在同文件的 `captureBefore(command, args)`：Excel 写入类命令（`excel_set_values`、`excel_set_formula`、`excel_clear_range` 等——以 `officeExecutor.js` 里 `COMMAND_HOSTS` 为 `excel` 且不在 `READ_ONLY_COMMANDS` 的命令为准，逐一列出其目标区域参数名）读 `values`+`formulas`；PPT 写入类命令（`ppt_replace_text`、`ppt_add_text_box` 等）读目标幻灯片全部形状文本；结构性命令（加/删工作表、加/删幻灯片）返回 `null`（不可撤销，如实标注）。
  - `officeExecutor.js`：Word 写入 handler 已经走 `withTracking`；`__forceTracking` 为真且 `trackingSupported()` 为假时 `withTracking` 直接抛「本机 Word 版本无法标记修订」（双保险），并从 args 里删掉 `__forceTracking` 再交给 handler。WPS 文字同理（`TrackRevisions` 设置失败即抛）。
  - `chatSession.handleClientAction`：`action.origin` 存在时改走 `runCrossDocWrite`（只读命令直接 `executeCommand`）；本窗格**不**往当前会话气泡里挂工具 chip（这是别的会话的动作），改为：写入成功 → `revisionLog.record(entry)` + `crossDocBanner.value = { originDocName, count }`（同一来源 10 秒内合并计数）；无论成败都照常 `postOfficeResult`。

- [ ] **Step 4: Run** `cd office-addin && npm test` → PASS。
- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(addin): 跨文档写入强制修订并在目标窗格记录改前值与来源（dev-board#717）"`

---
### Task 13: 插件——修订记录面板、横幅与头部入口

**Files:**
- Create: `office-addin/taskpane/components/RevisionLogPanel.vue`
- Modify: `App.vue`（头部 :7-73 加按钮与角标；挂面板；横幅）、`lib/i18n.js`（ZH/EN 新键）、`lib/i18n.test.js`（`SCAN_FILES` :145-150 加 `'../components/RevisionLogPanel.vue'`）、`lib/crossDocWrite.js`（导出 `locateEntry(entry)`）

**Interfaces:**
- Consumes: `revisionLog`（Task 12）、`crossDocBanner`（chatSession 导出的 ref）、`undoEntry`、`locateInDocument`（hostBridge）
- Produces: i18n 键（ZH/EN 各一份）：`revLogTitle` 修订记录 / Change log；`revLogEmpty` 还没有来自其他文档的修改 / No changes from other documents yet；`revLogFrom` 来自《{name}》 / From "{name}"；`revLogLocate` 定位 / Locate；`revLogUndo` 撤销 / Undo；`revLogUndone` 已撤销 / Undone；`revLogConflict` 这里已被再次修改，未覆盖 / Changed again since, not overwritten；`revLogNotUndoable` 请在 Word 的修订中拒绝此项 / Reject it from Word's tracked changes；`revLogNoBefore` 无法记录改前值 / Previous value not recorded；`revLogClear` 清空 / Clear；`crossDocBanner` 来自《{name}》会话的 AI 刚修改了本文档 {count} 处，修订已标记 / The AI in "{name}" just changed {count} place(s) in this document; changes are marked；`crossDocBannerOpen` 查看 / View。

- [ ] **Step 1: Write failing test** —— `i18n.test.js` 加入新文件后先跑：`cd office-addin && node --test taskpane/lib/i18n.test.js` 应因文件不存在失败。
- [ ] **Step 2: Implement**
  - `RevisionLogPanel.vue`：overlay（沿用 TransferPanel 的 overlay 结构与 `z-index`，不给 composer 加 z-index）；列表每行：时间（`HH:mm`）、`t('revLogFrom', {name})`、summary、按钮「定位」「撤销」（`undoable` 为假时撤销按钮换成说明文字：Word 显示 `revLogNotUndoable`，无改前值显示 `revLogNoBefore`）；撤销结果就地显示 `revLogUndone`/`revLogConflict`；打开面板时 `markAllRead()`；底部「清空」。
  - `locateEntry(entry)`：Word → `locateInDocument(args.replace || args.text)`；Excel → `worksheet.getRange(target).select()`；PPT → `setSelectedSlides([slideId])`；WPS 对应 `Range.Select()` / `Slides.Item(n).Select()`；失败静默。
  - `App.vue`：头部语言按钮左侧加「修订记录」按钮（SVG 图标 + `unread>0` 时数字角标，`aria-label=t('revLogTitle')`）；`crossDocBanner` 非空时在 composer 上方显示横幅，点「查看」打开面板并清横幅；`onMounted` 用当前文档标识 `bindDocument(docKey)`——`docKey` = Office `Office.context.document.url` 或 WPS `FullName`，取不到时用 `host + ':' + docName`。
  - 样式走 `styles.css` 现有令牌（森林绿体系、浅色外壳），不引入新配色。
- [ ] **Step 3: Run** `cd office-addin && npm test && npm run build` → PASS。
- [ ] **Step 4: 真渲染走查**（本地）：`npm run build` 后 `python3 -m http.server` 托管 `dist/`，浏览器打开（无 Office 全局时 `main.js` 直接挂载），在控制台对 `revisionLog.record(...)` 注入三条（Word/Excel/PPT 各一），截图面板与横幅，确认中英两种语言都无裸键名。截图交主会话。
- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(addin): 修订记录面板与跨文档修改横幅（dev-board#717）"`

---

### Task 14: 插件——关联 git 仓库入口

**Files:**
- Create: `office-addin/taskpane/components/GitLinkPanel.vue`、`lib/gitLink.js`、`lib/gitLink.test.js`
- Modify: `lib/api.js`（`listGitLinks`、`createGitLink`、`deleteGitLink`）、`App.vue`（头像菜单加「关联 git 仓库」项，打开面板）、`lib/i18n.js`、`lib/i18n.test.js`（SCAN_FILES 加 `GitLinkPanel.vue`）

**Interfaces:**
- Consumes: Task 9 三个端点
- Produces: `gitLink.js`：`validateRepoUrl(url) → { ok, provider?, error? }`（与后端 `parseUrl` 同规则，前端先挡一遍）；模块级 store `links`、`loadLinks(settings, projectId)`、`addLink(settings, {projectId, url, branch, token})`、`removeLink(settings, id)`。i18n 键：`gitLinkTitle` 关联 git 仓库 / Linked git repositories；`gitLinkUrl` 仓库地址 / Repository URL；`gitLinkBranch` 分支（留空为默认） / Branch (blank = default)；`gitLinkToken` 访问令牌（只读权限即可） / Access token (read-only is enough)；`gitLinkSave` 验证并保存 / Verify and save；`gitLinkRemove` 解除关联 / Unlink；`gitLinkOnlyHosts` 只支持 GitHub 与 Gitee / Only GitHub and Gitee are supported；`gitLinkHint` AI 只会读取这个仓库里的文件，不会提交或推送；令牌加密保存，可随时解除 / The AI only reads files in this repository and never commits or pushes. The token is stored encrypted and can be removed at any time；`gitLinkNoProject` 请先在顶部选择项目 / Choose a project at the top first。

- [ ] **Step 1: Write failing test**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { validateRepoUrl } from './gitLink.js'
test('validateRepoUrl', () => {
  assert.equal(validateRepoUrl('https://github.com/acme/docs').provider, 'github')
  assert.equal(validateRepoUrl('git@gitee.com:acme/docs.git').provider, 'gitee')
  assert.equal(validateRepoUrl('https://gitlab.com/a/b').ok, false)
  assert.equal(validateRepoUrl('').ok, false)
})
```
- [ ] **Step 2: Run** `cd office-addin && node --test taskpane/lib/gitLink.test.js` → 失败。
- [ ] **Step 3: Implement**：面板列出已关联仓库（`provider/owner/repo@branch`、`…tokenLast4`、`lastError` 以警示色显示）与新增表单；令牌输入框 `type=password`，保存后立即清空输入；后端 503/400 的 message 原样显示。
- [ ] **Step 4: Run** `cd office-addin && npm test && npm run build` → PASS；按 Task 13 Step 4 的方式截图面板（桩后端实现三个端点）。
- [ ] **Step 5: Commit**（主会话）`git commit -m "feat(addin): 在插件里关联 GitHub/Gitee 仓库（dev-board#720）"`

---

### Task 15: 隐私声明、部署文档与领域文档

**Files:**
- Modify: `legal/PRIVACY.md`（中文「二、平台代采档下会经过我们服务器的内容」:26 节末尾追加；英文 Part 1 :150 节对应追加）
- Modify: `deploy/cloud/env.example`（`AWD_REF_INTERNAL_SECRET`、`AWD_REF_CASE_BASE_URL`、`AWD_GIT_TOKEN_SECRET` 三行与注释）、`deploy/cloud/README.md`（case 实例 nginx 增加 `location ^~ /api/internal/ { return 404; }`；两实例共享 `AWD_REF_INTERNAL_SECRET`；北京 addin 的 `AWD_REF_CASE_BASE_URL=http://127.0.0.1:9797`；SG 不配）
- Modify: `.claude/agents/office-addin.md`（窗格心跳、跨文档写入痕迹、修订记录、git 关联、`read_for_reference`）、`ai-chat.md`（ref_* 工具与可见性、末位规则改写、ReferenceSourceService 顺序）、`mobile-sync.md`（门铃流、ref 请求三端点、不计费红线、`projectKey="*"`）、`version-control.md`（案件库内部只读端点与身份键）

- [ ] **Step 1: 写 PRIVACY.md 增补**（中文原文，英文对照同义）：

```markdown
**跨文件参考与修改（Office/WPS 插件）**
- 你在一个文档的插件窗格里要求 AI 参考或修改另一个打开着的文档时，文档内容经 AI WorkDeck 云后端在你同一账号的两个窗格之间转发，只在内存中停留，不存储。
- AI 需要参考桌面端项目里的文件时，桌面端在本机抽出该文件的文字，经云后端转发给 AI，只在内存中停留，不存储，不收费。桌面端为此与同一台插件云后端保持一条连接，不新增其他服务器。
- 你在插件里关联 GitHub 或 Gitee 仓库后，云后端会使用你提供的访问令牌读取该仓库的文件列表与你要求参考的文件（访问 api.github.com 或 gitee.com），只读、不提交、不推送；令牌加密保存，可随时在插件里解除关联。
```
- [ ] **Step 2: 更新 env.example / README / 四份领域文档**（按上面列出的要点，每份文档加一个带 dev-board#717-720 标注的小节，含地雷：跨窗格写入必须带修订、参考文字不计费不落盘、ref_ 只对 OFFICE 可见、门铃 404 钉死、案件库端点三重闸）。
- [ ] **Step 3: 校验** `node scripts/check-spdx.mjs`（CI 同款，只查新增文件）通过。
- [ ] **Step 4: Commit**（主会话）`git commit -m "docs: 跨文件读写的隐私声明、部署配置与领域文档（dev-board#717-720）"`

---

### Task 16: 全量回归与真机走查

- [ ] **Step 1: 后端全量** `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test`，输出原文贴回主会话。
- [ ] **Step 2: 插件全量** `cd office-addin && npm test && npm run build && npm run build:wps`。
- [ ] **Step 3: 前端未受影响确认** `cd frontend && npm run build`（本改动不应触碰前端；构建通过即可）。
- [ ] **Step 4: 本地端到端**：本机起桌面端后端（local-mode）与一个 cloud profile 后端，桌面端 `mobile.relay.base-url` 指向本地 cloud；插件 dist 指向本地 cloud；用两个浏览器标签模拟两个窗格（无 Office 全局，`hostBridge` 走不到宿主时只验心跳、登记、`ref_list(open)` 与离线报错）；验证 `ref_list(desk)`/`ref_read(desk)` 冷热两次的耗时并记录。
- [ ] **Step 5: 真机走查**（主会话亲自或派有头走查，先确认维护者空闲时间 ≥180 秒与屏幕未锁）：spec §10 的五项，逐项截图入卡；未能完成的项在卡上与汇报里标「未验证」。
- [ ] **Step 6: 发布面清单**（主会话）：两台 addin 云后端 jar + env、case 实例 jar + env + nginx、插件静态包两台、桌面端随下次发版。按 `eng-infra.md` 4.6 与记忆「发版只发有更新的端」执行。
