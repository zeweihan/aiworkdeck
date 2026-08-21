package com.checkba.service.plugin;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.Tag;
import com.checkba.plugin.api.ConflictPolicy;
import com.checkba.plugin.api.HostAware;
import com.checkba.plugin.api.HostQuotaException;
import com.checkba.plugin.api.LinkView;
import com.checkba.plugin.api.PluginHost;
import com.checkba.plugin.api.TargetInput;
import com.checkba.plugin.api.ToolCall;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.TagRepository;
import com.checkba.service.DocumentTextService;
import com.checkba.service.FileTagService;
import com.checkba.service.OcrService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.SystemSettingService;
import com.checkba.service.TagService;
import com.checkba.service.ai.AuxModelResolver;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.StyleProfileResolver;
import com.checkba.service.ai.TokenUsageService;
import com.checkba.service.ai.tools.ToolContext;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.service.evidence.EvidenceLinkViews;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.util.style.StyleProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PluginHostImpl 契约（dev-board#109 单元 H3）：非成员拒绝、配额第 61 次抛、Docs.exec 无会话抛、
 * Files.write 走 RENAME、Evidence 建链 createdByKind=plugin、Settings 键前缀、HostAware 注入、
 * 无调用上下文一律拒绝。全部宿主服务 mock。
 */
class PluginHostImplTest {

    ProjectFileService projectFileService = mock(ProjectFileService.class);
    ProjectFileRepository projectFileRepository = mock(ProjectFileRepository.class);
    StorageServiceFactory storageServiceFactory = mock(StorageServiceFactory.class);
    StorageService storage = mock(StorageService.class);
    ProjectMemberService members = mock(ProjectMemberService.class);
    DocumentTextService documentTextService = mock(DocumentTextService.class);
    OcrService ocrService = mock(OcrService.class);
    TagService tagService = mock(TagService.class);
    TagRepository tagRepository = mock(TagRepository.class);
    FileTagService fileTagService = mock(FileTagService.class);
    EvidenceLinkService evidenceLinkService = mock(EvidenceLinkService.class);
    PluginJobService pluginJobService = mock(PluginJobService.class);
    EditorBridgeService editorBridge = mock(EditorBridgeService.class);
    SystemSettingService settings = mock(SystemSettingService.class);
    StyleProfileResolver styleProfileResolver = mock(StyleProfileResolver.class);
    ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
    AuxModelResolver auxModelResolver = mock(AuxModelResolver.class);
    TokenUsageService tokenUsageService = mock(TokenUsageService.class);

    PluginHostFactory factory;
    PluginHost host;

    static final long PROJECT = 1L;
    static final long USER = 9L;

    @BeforeEach
    void setUp() {
        when(storageServiceFactory.getStorageService()).thenReturn(storage);
        when(members.hasReadPermission(PROJECT, USER)).thenReturn(true);
        when(members.hasWritePermission(PROJECT, USER)).thenReturn(true);
        factory = new PluginHostFactory(projectFileService, projectFileRepository, storageServiceFactory, members,
                documentTextService, ocrService, tagService, tagRepository, fileTagService, evidenceLinkService,
                pluginJobService, editorBridge, settings, styleProfileResolver, chatModelFactory, auxModelResolver,
                tokenUsageService, new ObjectMapper(), new PluginHostQuota());
        host = factory.forPlugin("dd");
        factory.bindCall(new ToolContext(PROJECT, "conv-1", USER, null));
    }

    @AfterEach
    void tearDown() {
        factory.clear();
    }

    private ProjectFile file(long id, String name, boolean folder) {
        ProjectFile p = new ProjectFile();
        p.setId(id);
        p.setProjectId(PROJECT);
        p.setName(name);
        p.setIsFolder(folder);
        p.setFileType(folder ? null : "txt");
        p.setFilePath("projects/1/" + name);
        p.setFileSize(3L);
        p.setIsDeleted(false);
        when(projectFileRepository.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("forPlugin 按 id 缓存同一实例；call() 透传 ToolRegistry 绑定的上下文")
    void factoryCachesAndExposesCall() {
        assertSame(host, factory.forPlugin("dd"));
        assertEquals("dd", host.pluginId());
        ToolCall c = host.call();
        assertEquals(PROJECT, c.projectId());
        assertEquals(USER, c.userId());
        assertEquals("conv-1", c.conversationId());
        factory.clear();
        assertNull(host.call());
    }

    @Test
    @DisplayName("非项目成员：读写都拒绝（IllegalArgumentException），不碰仓库")
    void nonMemberRejected() {
        factory.bindCall(new ToolContext(PROJECT, null, 77L, null));
        assertThrows(IllegalArgumentException.class, () -> host.files().list(PROJECT, null, false));
        assertThrows(IllegalArgumentException.class, () -> host.files().createFolderPath(PROJECT, List.of("a")));
        assertThrows(IllegalArgumentException.class, () -> host.tags().getOrCreate(PROJECT, "x", "PARTY"));
        verify(projectFileRepository, never()).findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(anyLong());
        verify(projectFileService, never()).ensureFolderPath(anyLong(), anyLong(), anyList());
    }

    @Test
    @DisplayName("没有调用上下文的线程（既非工具分发也非任务体）一律拒绝")
    void noCallContextRejected() {
        factory.clear();
        assertThrows(IllegalStateException.class, () -> host.files().list(PROJECT, null, false));
        assertThrows(IllegalStateException.class, () -> host.settings().get("k"));
    }

    @Test
    @DisplayName("配额：每插件每分钟 60 次，第 61 次抛 HostQuotaException；别的插件不受影响")
    void quota61stCallThrows() {
        for (int i = 0; i < 60; i++) {
            host.settings().get("k");
        }
        assertThrows(HostQuotaException.class, () -> host.settings().get("k"));
        PluginHost other = factory.forPlugin("other");
        other.settings().get("k");
    }

    @Test
    @DisplayName("配额：后台任务线程（bindJob）走 1200 次/分钟的大窗口，与工具线程的 60 次分开计数")
    void quotaJobThreadHasLargerWindow() {
        factory.bindJob(new ToolCall(PROJECT, null, USER, null));
        for (int i = 0; i < 1200; i++) {
            host.settings().get("k");
        }
        assertThrows(HostQuotaException.class, () -> host.settings().get("k"));
        // 回到工具线程：自己的 60 次窗口还是空的
        factory.clear();
        factory.bindCall(new ToolContext(PROJECT, "conv-1", USER, null));
        for (int i = 0; i < 60; i++) {
            host.settings().get("k");
        }
        assertThrows(HostQuotaException.class, () -> host.settings().get("k"));
    }

    @Test
    @DisplayName("Jobs.start 的任务体包装以 job 模式绑定（大窗口），结束后清掉")
    void jobsBodyRunsInJobMode() throws Exception {
        ArgumentCaptor<com.checkba.plugin.api.JobBody> body = ArgumentCaptor.forClass(com.checkba.plugin.api.JobBody.class);
        when(pluginJobService.start(eq("dd"), eq("ingest"), eq("t"), any(), body.capture()))
                .thenReturn(new com.checkba.plugin.api.JobHandle("J2"));
        host.jobs().start("ingest", "t", ctx -> {
            for (int i = 0; i < 100; i++) host.settings().get("k"); // 超过 60，job 窗口放行
        });
        Thread th = new Thread(() -> {
            try { body.getValue().run(null); } catch (Exception e) { throw new RuntimeException(e); }
            assertTrue(!factory.inJob() && host.call() == null);
        });
        th.start();
        th.join(5000);
        assertTrue(!th.isAlive());
    }

    @Test
    @DisplayName("FileInfo.updatedAt 填 ProjectFile.updatedAt（epoch millis），为空则 null")
    void fileInfoCarriesUpdatedAt() {
        ProjectFile p = file(80L, "u.txt", false);
        p.setUpdatedAt(java.time.LocalDateTime.of(2026, 8, 22, 1, 0));
        ProjectFile q = file(81L, "v.txt", false);
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT)).thenReturn(List.of(p, q));
        long expected = p.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, host.files().get(PROJECT, 80L).updatedAt());
        assertNull(host.files().get(PROJECT, 81L).updatedAt());
    }

    @Test
    @DisplayName("Docs.exec：无 conversationId 抛 IllegalStateException(no active conversation)；非白名单 action 拒绝")
    void docsExecRequiresConversationAndWhitelist() {
        factory.bindCall(new ToolContext(PROJECT, null, USER, null));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> host.docs().exec("find_replace", Map.of()));
        assertEquals("no active conversation", ex.getMessage());

        factory.bindCall(new ToolContext(PROJECT, "conv-1", USER, null));
        assertThrows(IllegalArgumentException.class, () -> host.docs().exec("load_document", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> host.docs().exec("export_document", Map.of()));

        when(editorBridge.executeEditorCommand(eq("find_replace"), any())).thenReturn("{\"ok\":1}");
        assertEquals("{\"ok\":1}", host.docs().exec("find_replace", Map.of("find", "a")));
        // 按调用上下文临时绑定会话再还原（后台任务线程上 bridge 自己的 ThreadLocal 是空的）
        verify(editorBridge).setCurrentConversationId("conv-1");
        verify(editorBridge).clearCurrentConversationId();
    }

    @Test
    @DisplayName("Files.write 走 ProjectFileService.createFile 的 RENAME 策略并把字节写进存储")
    void filesWriteUsesRenamePolicy() throws Exception {
        ProjectFile created = file(50L, "r.txt", false);
        when(projectFileService.createFile(eq(PROJECT), isNull(), eq("r.txt"), eq("txt"), eq(3L), isNull(), isNull(),
                eq(USER), eq(ProjectFileService.ConflictPolicy.RENAME))).thenReturn(created);
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT)).thenReturn(List.of(created));

        var info = host.files().write(PROJECT, null, "r.txt",
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), ConflictPolicy.RENAME);
        assertEquals(50L, info.id());
        assertEquals("r.txt", info.path());
        ArgumentCaptor<java.io.InputStream> bytes = ArgumentCaptor.forClass(java.io.InputStream.class);
        verify(storage).save(eq("projects/1/r.txt"), bytes.capture());
        assertEquals("abc", new String(bytes.getValue().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Files.write FAIL 策略原样透传；createFolderPath 经 ensureFolderPath")
    void filesFailPolicyAndFolderPath() {
        ProjectFile created = file(51L, "f.txt", false);
        when(projectFileService.createFile(anyLong(), any(), anyString(), anyString(), anyLong(), any(), any(), anyLong(),
                eq(ProjectFileService.ConflictPolicy.FAIL))).thenReturn(created);
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT)).thenReturn(List.of(created));
        host.files().write(PROJECT, null, "f.txt", new ByteArrayInputStream(new byte[0]), ConflictPolicy.FAIL);
        verify(projectFileService).createFile(anyLong(), any(), anyString(), anyString(), anyLong(), any(), any(), anyLong(),
                eq(ProjectFileService.ConflictPolicy.FAIL));

        ProjectFile folder = file(60L, "b", true);
        when(projectFileService.ensureFolderPath(PROJECT, USER, List.of("a", "b"))).thenReturn(folder);
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT)).thenReturn(List.of(folder));
        assertEquals(60L, host.files().createFolderPath(PROJECT, List.of("a", "b")).id());
    }

    @Test
    @DisplayName("Files.list 递归带路径；get 拒绝别的项目的文件（IDOR）")
    void filesListPathsAndIdor() {
        ProjectFile root = file(1L, "卷宗", true);
        ProjectFile sub = file(2L, "合同", true);
        sub.setParentId(1L);
        ProjectFile doc = file(3L, "a.docx", false);
        doc.setParentId(2L);
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT)).thenReturn(List.of(root, sub, doc));

        var all = host.files().list(PROJECT, null, true);
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(f -> f.path().equals("卷宗/合同/a.docx") && !f.folder()));
        var underRoot = host.files().list(PROJECT, 1L, false);
        assertEquals(1, underRoot.size());
        assertEquals("卷宗/合同", underRoot.get(0).path());

        ProjectFile foreign = file(99L, "x.txt", false);
        foreign.setProjectId(2L);
        assertThrows(IllegalArgumentException.class, () -> host.files().get(PROJECT, 99L));
    }

    @Test
    @DisplayName("Files.sha256 算一次后缓存到 metaJson（sha256 + sha256At），同一 updatedAt 不再读存储")
    void sha256CachedInMeta() throws Exception {
        ProjectFile p = file(7L, "h.txt", false);
        p.setUpdatedAt(java.time.LocalDateTime.of(2026, 8, 22, 1, 0));
        when(storage.load("projects/1/h.txt")).thenReturn(new ByteArrayResource("abc".getBytes(StandardCharsets.UTF_8)));
        when(projectFileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String h1 = host.files().sha256(PROJECT, 7L);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h1);
        assertTrue(p.getMetaJson().contains("\"sha256\""));
        assertTrue(p.getMetaJson().contains("sha256At"));

        String h2 = host.files().sha256(PROJECT, 7L);
        assertEquals(h1, h2);
        verify(storage, org.mockito.Mockito.times(1)).load("projects/1/h.txt");
    }

    @Test
    @DisplayName("Evidence.create 映射到 EvidenceLinkService.create(createdByKind=plugin)")
    void evidenceCreatePassesPluginKind() {
        EvidenceLinkViews.LinkView view = new EvidenceLinkViews.LinkView(5L, "EVID_X", 3L, "anchor", "hash", "1.2", "title",
                "active", "plugin", null, null, List.of(
                new EvidenceLinkViews.TargetView(8L, 4L, new EvidenceLinkViews.FileBrief(4L, "b.pdf", "pdf", null, false),
                        null, "supports", "plugin", null, null)));
        when(evidenceLinkService.create(eq(USER), eq(PROJECT), eq(3L), eq("EVID_X"), eq("anchor"), eq("1.2"), eq("title"),
                eq("plugin"), anyList())).thenReturn(view);

        LinkView out = host.evidence().create(PROJECT, 3L, "EVID_X", "anchor", "1.2", "title",
                List.of(new TargetInput(4L, null, "supports", "plugin", null, null)));
        assertEquals("EVID_X", out.linkKey());
        assertEquals(1, out.targets().size());
        assertEquals("b.pdf", out.targets().get(0).fileName());
        assertEquals(8L, out.targets().get(0).id());
    }

    @Test
    @DisplayName("Tags：getOrCreate 透传 type，tagFile 拒绝别的项目的标签")
    void tags() {
        Tag t = new Tag();
        t.setId(11L);
        t.setProjectId(PROJECT);
        t.setName("甲方");
        t.setType("PARTY");
        t.setColor("#fff");
        when(tagService.getOrCreateTag(PROJECT, "甲方", "PARTY", null)).thenReturn(t);
        assertEquals("PARTY", host.tags().getOrCreate(PROJECT, "甲方", "PARTY").type());

        file(3L, "a.docx", false);
        when(tagRepository.findById(11L)).thenReturn(Optional.of(t));
        host.tags().tagFile(PROJECT, 3L, 11L);
        verify(fileTagService).addTagToFile(3L, 11L, USER);

        Tag foreign = new Tag();
        foreign.setId(12L);
        foreign.setProjectId(2L);
        when(tagRepository.findById(12L)).thenReturn(Optional.of(foreign));
        assertThrows(IllegalArgumentException.class, () -> host.tags().tagFile(PROJECT, 3L, 12L));
    }

    @Test
    @DisplayName("Settings：键自动加前缀 plugin.<id>.；styleProfile 委托 StyleProfileResolver 并返回合并后的完整画像")
    void settingsPrefixAndStyleProfile() throws Exception {
        host.settings().set("k", "v");
        verify(settings).set("plugin.dd.k", "v");
        when(settings.get("plugin.dd.k", null)).thenReturn("v");
        assertEquals("v", host.settings().get("k"));

        when(styleProfileResolver.resolve(PROJECT, null)).thenReturn(StyleProfiles.houseDefault().merge(
                StyleProfiles.parse("{\"schemaVersion\":1,\"body\":{\"firstLineIndent\":{\"value\":0,\"unit\":\"pt\"}}}")));
        com.fasterxml.jackson.databind.JsonNode n = new ObjectMapper().readTree(host.settings().projectStyleProfileJson(PROJECT));
        assertEquals(0, n.path("body").path("firstLineIndent").path("value").asInt());
        assertEquals("楷体_GB2312", n.path("body").path("font").path("eastAsia").asText(), "缺省叶子由 house-default 补齐");
        verify(styleProfileResolver).resolve(PROJECT, null);
        // 非成员拒绝（requireRead）
        when(members.hasReadPermission(PROJECT, USER)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> host.settings().projectStyleProfileJson(PROJECT));
    }

    @Test
    @DisplayName("Docs.openFile：配额只计一次（P1 复核 F7）；locator 非空时追发 plugin_open_locator {fileId, locator}")
    void docsOpenFileCountsQuotaOnce() {
        file(70L, "a.docx", false);
        for (int i = 0; i < 59; i++) {
            host.settings().get("k");
        }
        // 第 60 次：openFile 若双计会在这里抛 HostQuotaException
        host.docs().openFile(70L, Map.of("type", "pdf", "page", 7));
        assertThrows(HostQuotaException.class, () -> host.settings().get("k"));
        verify(editorBridge).sendOpenFileAction(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(editorBridge).sendClientAction(eq("plugin_open_locator"), eq("conv-1"), fields.capture());
        assertEquals(70L, fields.getValue().get("fileId"));
        assertEquals(7, ((Map<?, ?>) fields.getValue().get("locator")).get("page"));
    }

    @Test
    @DisplayName("DOC_ACTIONS 白名单 ⊇ DocumentEditTools 实际下发的编辑器动作（宿主自用与诊断原语除外），防再漂移")
    void docActionsCoverDocumentEditToolsDispatch() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of("src/main/java/com/checkba/service/ai/tools/DocumentEditTools.java");
        assertTrue(java.nio.file.Files.exists(src), "测试工作目录须为 backend/: " + src.toAbsolutePath());
        String code = java.nio.file.Files.readString(src);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:executeEditorCommand|dispatchTableStructureCommand|dispatchInsertNote)\\(\"([a-z_]+)\"")
                .matcher(code);
        java.util.Set<String> dispatched = new java.util.TreeSet<>();
        while (m.find()) dispatched.add(m.group(1));
        assertTrue(dispatched.contains("set_style_profile") && dispatched.contains("insert_toc"), "扫描没抓到已知下发名: " + dispatched);
        // 宿主自用 / 诊断原语，按 SPEC §11 不开放给插件
        dispatched.removeAll(java.util.Set.of("doc_open_file_sync", "debug_revisions"));
        dispatched.removeAll(PluginHostImpl.DOC_ACTIONS);
        assertTrue(dispatched.isEmpty(), "DocumentEditTools 下发但 DOC_ACTIONS 未放行（同步 docs/PLUGIN_SPEC.md §11）: " + dispatched);
    }

    @Test
    @DisplayName("Jobs.start 把调用快照交给 PluginJobService，并在任务体内重新绑定上下文")
    void jobsStartBindsSnapshotInsideBody() throws Exception {
        ArgumentCaptor<com.checkba.plugin.api.JobBody> body = ArgumentCaptor.forClass(com.checkba.plugin.api.JobBody.class);
        when(pluginJobService.start(eq("dd"), eq("ingest"), eq("t"), any(), body.capture()))
                .thenReturn(new com.checkba.plugin.api.JobHandle("J1"));
        final ToolCall[] seen = new ToolCall[1];
        assertEquals("J1", host.jobs().start("ingest", "t", ctx -> seen[0] = host.call()).jobId());

        // 模拟任务线程：没有上下文，任务体包装会自己绑快照、结束后清掉
        Thread th = new Thread(() -> {
            try {
                body.getValue().run(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        th.start();
        th.join(5000);
        assertNotNull(seen[0]);
        assertEquals(USER, seen[0].userId());
        assertEquals(PROJECT, seen[0].projectId());
    }

    @Test
    @DisplayName("Jobs.cancel/status：他人项目的 job 不能取消（写权限）、不能看（读权限）；别的插件的 job 当不存在")
    void jobsCancelRequiresProjectPermission() {
        com.checkba.model.entity.PluginJob foreign = new com.checkba.model.entity.PluginJob();
        foreign.setId("J-FOREIGN");
        foreign.setPluginId("dd");
        foreign.setProjectId(2L);
        when(pluginJobService.get("J-FOREIGN")).thenReturn(foreign);
        when(members.hasWritePermission(2L, USER)).thenReturn(false);
        when(members.hasReadPermission(2L, USER)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> host.jobs().cancel("J-FOREIGN"));
        assertThrows(IllegalArgumentException.class, () -> host.jobs().status("J-FOREIGN"));
        verify(pluginJobService, never()).cancel(anyString());

        // 本项目的 job：读权限能看，写权限能取消
        com.checkba.model.entity.PluginJob own = new com.checkba.model.entity.PluginJob();
        own.setId("J-OWN");
        own.setPluginId("dd");
        own.setProjectId(PROJECT);
        when(pluginJobService.get("J-OWN")).thenReturn(own);
        when(pluginJobService.status("J-OWN")).thenReturn(new com.checkba.plugin.api.JobStatus("J-OWN", "k", "t", "running", 0, 0, null, null, null));
        assertEquals("running", host.jobs().status("J-OWN").status());
        host.jobs().cancel("J-OWN");
        verify(pluginJobService).cancel("J-OWN");

        // 别的插件的 job：不存在
        com.checkba.model.entity.PluginJob other = new com.checkba.model.entity.PluginJob();
        other.setId("J-OTHER");
        other.setPluginId("someone-else");
        other.setProjectId(PROJECT);
        when(pluginJobService.get("J-OTHER")).thenReturn(other);
        assertNull(host.jobs().status("J-OTHER"));
        host.jobs().cancel("J-OTHER");
        verify(pluginJobService, never()).cancel("J-OTHER");
    }

    @Test
    @DisplayName("Files.write：parentId 必须是本项目的文件夹——文件、别的项目的节点都拒绝，不落盘")
    void filesWriteValidatesParent() {
        ProjectFile notFolder = file(70L, "a.txt", false);
        assertThrows(IllegalArgumentException.class, () -> host.files().write(PROJECT, 70L, "x.txt",
                new ByteArrayInputStream(new byte[0]), ConflictPolicy.RENAME));
        ProjectFile foreignFolder = file(71L, "dir", true);
        foreignFolder.setProjectId(2L);
        assertThrows(IllegalArgumentException.class, () -> host.files().write(PROJECT, 71L, "x.txt",
                new ByteArrayInputStream(new byte[0]), ConflictPolicy.RENAME));
        verify(projectFileService, never()).createFile(anyLong(), any(), anyString(), anyString(), anyLong(), any(), any(), anyLong(),
                any(ProjectFileService.ConflictPolicy.class));

        ProjectFile okFolder = file(72L, "ok", true);
        ProjectFile created = file(73L, "x.txt", false);
        created.setParentId(72L);
        when(projectFileService.createFile(eq(PROJECT), eq(72L), eq("x.txt"), eq("txt"), eq(0L), isNull(), isNull(),
                eq(USER), eq(ProjectFileService.ConflictPolicy.RENAME))).thenReturn(created);
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT)).thenReturn(List.of(okFolder, created));
        assertEquals("ok/x.txt", host.files().write(PROJECT, 72L, "x.txt",
                new ByteArrayInputStream(new byte[0]), ConflictPolicy.RENAME).path());
    }

    @Test
    @DisplayName("PluginService.injectHostIfAware：HostAware 工具拿到绑定本插件 id 的 host，普通工具不受影响")
    void pluginServiceInjectsHost() {
        PluginService ps = new PluginService();
        final PluginHost[] got = new PluginHost[1];
        HostAware tool = h -> got[0] = h;
        // 没有工厂：只记 WARN，不注入
        invokeInject(ps, tool, "dd");
        assertNull(got[0]);
        // 有工厂：注入绑定同 id 的实例
        invokeInjectWithFactory(ps, tool, "dd");
        assertNotNull(got[0]);
        assertEquals("dd", got[0].pluginId());
        assertSame(host, got[0]);
        invokeInjectWithFactory(ps, new Object(), "dd");
    }

    private void invokeInject(PluginService ps, Object tool, String pluginId) {
        try {
            var m = PluginService.class.getDeclaredMethod("injectHostIfAware", Object.class, String.class);
            m.setAccessible(true);
            m.invoke(ps, tool, pluginId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void invokeInjectWithFactory(PluginService ps, Object tool, String pluginId) {
        try {
            var set = PluginService.class.getDeclaredMethod("setPluginHostFactory", PluginHostFactory.class);
            set.setAccessible(true);
            set.invoke(ps, factory);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        invokeInject(ps, tool, pluginId);
    }
}
