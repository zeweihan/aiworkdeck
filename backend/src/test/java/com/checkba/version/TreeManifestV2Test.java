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

    /** 手改 .awd/tree.json 丢了 uid：宁可显式失败，不能让节点静默不落库。见 PR 审查修复。 */
    @Test
    void v2NodeWithoutUidIsRejectedLoudly() throws Exception {
        String v2Json = """
                {"version":2,"nodes":[
                {"name":"外来1.md","isFolder":false,"sortOrder":0,
                "isDeleted":false,"uid":null,"relPath":"外来1.md","author":"stranger"},
                {"name":"外来2.md","isFolder":false,"sortOrder":1,
                "isDeleted":false,"uid":null,"relPath":"外来2.md","author":"stranger"}
                ]}
                """;
        TreeManifest m = new ObjectMapper().readValue(v2Json, TreeManifest.class);

        VersionException ex = assertThrows(VersionException.class,
                () -> manifestSvc.applyToDatabase(9L, m));

        assertTrue(ex.getMessage().contains("uid"));
        assertTrue(db.values().stream().noneMatch(f -> f.getProjectId().equals(9L))); // 一行都没落库
    }

    /**
     * v2 终审 C2（跨项目 IDOR）：.awd/tree.json 随 push 跨机器传播、外部可编辑，恶意成员
     * 手改 relPath 写 "../{别的项目}/x.docx"，落库的 filePath 会指向别人项目的文件，
     * 下载端点按行上的 projectId 放行即打穿隔离。normalizeV2 必须显式拒绝、零落库。
     */
    @Test
    void v2NodeWithTraversalRelPathIsRejectedWithNothingPersisted() throws Exception {
        String v2Json = """
                {"version":2,"nodes":[{"name":"evil.docx","isFolder":false,"sortOrder":0,
                "isDeleted":false,"uid":"u-evil","relPath":"../9/evil.docx","author":"stranger"}]}
                """;
        TreeManifest m = new ObjectMapper().readValue(v2Json, TreeManifest.class);

        VersionException ex = assertThrows(VersionException.class,
                () -> manifestSvc.applyToDatabase(7L, m));

        assertTrue(ex.getMessage().contains("路径不合法"));
        assertTrue(db.values().stream().noneMatch(f -> f.getProjectId().equals(7L)),
                "不合法清单必须整体拒绝，一行都不落库");
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
