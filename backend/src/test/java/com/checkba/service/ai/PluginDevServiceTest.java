package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PluginDevService 单测：安全红线（拒 JAR、拒覆盖广场插件、卸载认标记）、
 * manifest 校验错误的可读性、以及装机拷贝 + 启用的正路径。
 * 文件树用内存 map 模拟（repository 的三个查询方法都指到它），存储层按
 * filePath -> bytes 直查，plugins 目录用 @TempDir 真实文件系统。
 */
class PluginDevServiceTest {

    @TempDir
    Path pluginsDir;

    private ProjectFileRepository repository;
    private ProjectFileService projectFileService;
    private StorageService storage;
    private PluginService pluginService;
    private PluginDevService service;

    private final Map<Long, ProjectFile> nodes = new HashMap<>();
    private final Map<String, byte[]> blobs = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong(100);

    private static final Long PROJECT = 1L;
    private static final Long USER = 7L;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(ProjectFileRepository.class);
        projectFileService = mock(ProjectFileService.class);
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        storage = mock(StorageService.class);
        pluginService = mock(PluginService.class);
        when(factory.getStorageService()).thenReturn(storage);
        when(pluginService.getPlugins()).thenReturn(List.of());

        when(repository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(nodes.get(inv.<Long>getArgument(0))));
        when(repository.findByProjectIdAndParentIdOrderBySortOrderAsc(eq(PROJECT), any()))
                .thenAnswer(inv -> {
                    Long parent = inv.getArgument(1);
                    List<ProjectFile> out = new ArrayList<>();
                    for (ProjectFile f : nodes.values()) {
                        boolean sameParent = parent == null ? f.getParentId() == null : parent.equals(f.getParentId());
                        if (PROJECT.equals(f.getProjectId()) && sameParent) {
                            out.add(f);
                        }
                    }
                    return out;
                });
        when(repository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(eq(PROJECT), any(), anyString()))
                .thenAnswer(inv -> {
                    Long parent = inv.getArgument(1);
                    String name = inv.getArgument(2);
                    return nodes.values().stream()
                            .filter(f -> PROJECT.equals(f.getProjectId()))
                            .filter(f -> parent == null ? f.getParentId() == null : parent.equals(f.getParentId()))
                            .filter(f -> name.equals(f.getName()) && !Boolean.TRUE.equals(f.getIsDeleted()))
                            .findFirst();
                });
        when(storage.load(anyString())).thenAnswer(inv -> {
            byte[] bytes = blobs.get(inv.<String>getArgument(0));
            if (bytes == null) {
                throw new IllegalStateException("no blob: " + inv.getArgument(0));
            }
            return new ByteArrayResource(bytes);
        });
        when(storage.save(anyString(), any())).thenAnswer(inv -> {
            blobs.put(inv.getArgument(0), inv.<java.io.InputStream>getArgument(1).readAllBytes());
            return inv.getArgument(0);
        });
        when(projectFileService.createFolder(eq(PROJECT), any(), anyString(), eq(USER)))
                .thenAnswer(inv -> addFolder(inv.getArgument(1), inv.getArgument(2)));
        when(projectFileService.createFile(eq(PROJECT), any(), anyString(), any(), any(), any(), any(), eq(USER)))
                .thenAnswer(inv -> {
                    ProjectFile f = newNode(inv.getArgument(1), inv.getArgument(2), false);
                    f.setFilePath("projects/" + PROJECT + "/" + f.getId() + "-" + f.getName());
                    nodes.put(f.getId(), f);
                    return f;
                });

        service = new PluginDevService(repository, projectFileService, factory, pluginService,
                pluginsDir.toString());
    }

    // ==================== scaffold ====================

    @Test
    void scaffoldCreatesManifestIndexAndSdk() {
        Long folderId = service.scaffold(PROJECT, USER, "demo-plugin", "演示插件");

        assertNotNull(folderId);
        ProjectFile folder = nodes.get(folderId);
        assertEquals("demo-plugin", folder.getName());
        // manifest / index / sdk 三个文件的字节都真实落了存储
        List<String> names = nodes.values().stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsFolder()))
                .map(ProjectFile::getName).sorted().toList();
        assertEquals(List.of("awd-plugin-sdk.js", "index.html", "manifest.json"), names);
        String manifest = textBlobOf("manifest.json");
        assertTrue(manifest.contains("\"id\": \"demo-plugin\""));
        assertTrue(manifest.contains("\"frontendEntry\": \"web/index.html\""));
        assertTrue(textBlobOf("index.html").contains("演示插件"));
        assertTrue(textBlobOf("awd-plugin-sdk.js").contains("postMessage"));
    }

    @Test
    void scaffoldRejectsInvalidId() {
        assertThrows(IllegalArgumentException.class, () -> service.scaffold(PROJECT, USER, "Bad_ID", "x"));
        assertThrows(IllegalArgumentException.class, () -> service.scaffold(PROJECT, USER, "../evil", "x"));
    }

    // ==================== install ====================

    @Test
    void installCopiesWebOnlyPluginWritesMarkerAndEnables() {
        Long folderId = seedSourcePlugin("demo-plugin", validManifest("demo-plugin"));

        String id = service.install(PROJECT, folderId);

        assertEquals("demo-plugin", id);
        File target = pluginsDir.resolve("demo-plugin").toFile();
        assertTrue(new File(target, "manifest.json").isFile());
        assertTrue(new File(target, "web/index.html").isFile());
        assertTrue(new File(target, ".awd-dev").isFile(), "开发安装必须落 .awd-dev 来源标记");
        verify(pluginService).rescan();
        verify(pluginService).setEnabled("demo-plugin", true);
    }

    @Test
    void installRejectsBackendJarsToolsSkillsPacks() {
        String manifest = "{\"id\":\"demo-plugin\",\"name\":\"x\",\"frontendEntry\":\"web/index.html\","
                + "\"permissions\":[\"file_read\"],\"backendJars\":[\"a.jar\"],\"tools\":[],"
                + "\"skills\":[\"s\"],\"packs\":[\"p\"]}";
        Long folderId = seedSourcePlugin("demo-plugin", manifest);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.install(PROJECT, folderId));
        assertTrue(e.getMessage().contains("backendJars"));
        assertTrue(e.getMessage().contains("skills"));
        assertTrue(e.getMessage().contains("packs"));
        assertFalse(pluginsDir.resolve("demo-plugin").toFile().exists(), "拒装时不许留下任何目录");
        verify(pluginService, never()).rescan();
    }

    @Test
    void installCollectsReadableErrorsForBadManifest() {
        String manifest = "{\"id\":\"other-name\",\"name\":\"x\",\"frontendEntry\":\"index.html\","
                + "\"permissions\":[\"root_access\"]}";
        Long folderId = seedSourcePlugin("demo-plugin", manifest);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.install(PROJECT, folderId));
        // 三类错误逐条在场：id 不一致 / frontendEntry 不在 web/ / 未知权限
        assertTrue(e.getMessage().contains("other-name"));
        assertTrue(e.getMessage().contains("frontendEntry"));
        assertTrue(e.getMessage().contains("root_access"));
    }

    @Test
    void installRefusesToClobberMarketPluginOfSameId() throws Exception {
        File market = pluginsDir.resolve("demo-plugin").toFile();
        Files.createDirectories(market.toPath());
        Files.writeString(new File(market, "manifest.json").toPath(), "{}");
        Long folderId = seedSourcePlugin("demo-plugin", validManifest("demo-plugin"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.install(PROJECT, folderId));
        assertTrue(e.getMessage().contains("demo-plugin"));
        assertTrue(new File(market, "manifest.json").isFile(), "广场插件目录必须原样保留");
    }

    @Test
    void reinstallOverDevInstallSucceeds() {
        Long folderId = seedSourcePlugin("demo-plugin", validManifest("demo-plugin"));
        service.install(PROJECT, folderId);
        assertEquals("demo-plugin", service.install(PROJECT, folderId));
    }

    // ==================== uninstall ====================

    @Test
    void uninstallOnlyAcceptsDevMarkedDirs() throws Exception {
        File market = pluginsDir.resolve("market-plugin").toFile();
        Files.createDirectories(market.toPath());
        Files.writeString(new File(market, "manifest.json").toPath(), "{}");

        assertThrows(IllegalArgumentException.class, () -> service.uninstall("market-plugin"));
        assertTrue(market.isDirectory(), "无标记目录必须原样保留");

        Long folderId = seedSourcePlugin("demo-plugin", validManifest("demo-plugin"));
        service.install(PROJECT, folderId);
        service.uninstall("demo-plugin");
        assertFalse(pluginsDir.resolve("demo-plugin").toFile().exists());
    }

    // ==================== helpers ====================

    private String validManifest(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"x\",\"version\":\"0.1.0\","
                + "\"frontendEntry\":\"web/index.html\",\"permissions\":[\"file_read\"],"
                + "\"tools\":[],\"backendJars\":[]}";
    }

    /** 造一棵 插件开发/<id>/{manifest.json, web/index.html} 的源码树，返回 <id> 文件夹 ID */
    private Long seedSourcePlugin(String id, String manifestJson) {
        ProjectFile devRoot = addFolder(null, PluginDevService.DEV_ROOT_FOLDER);
        ProjectFile folder = addFolder(devRoot.getId(), id);
        ProjectFile web = addFolder(folder.getId(), "web");
        addFile(folder.getId(), "manifest.json", manifestJson.getBytes(StandardCharsets.UTF_8));
        addFile(web.getId(), "index.html", "<!DOCTYPE html><html></html>".getBytes(StandardCharsets.UTF_8));
        return folder.getId();
    }

    private ProjectFile addFolder(Long parentId, String name) {
        ProjectFile f = newNode(parentId, name, true);
        nodes.put(f.getId(), f);
        return f;
    }

    private void addFile(Long parentId, String name, byte[] bytes) {
        ProjectFile f = newNode(parentId, name, false);
        f.setFilePath("projects/" + PROJECT + "/" + f.getId() + "-" + name);
        nodes.put(f.getId(), f);
        blobs.put(f.getFilePath(), bytes);
    }

    private ProjectFile newNode(Long parentId, String name, boolean folder) {
        ProjectFile f = new ProjectFile();
        f.setId(idSeq.incrementAndGet());
        f.setProjectId(PROJECT);
        f.setParentId(parentId);
        f.setName(name);
        f.setIsFolder(folder);
        f.setIsDeleted(false);
        f.setSortOrder(0);
        return f;
    }

    private String textBlobOf(String fileName) {
        return nodes.values().stream()
                .filter(f -> fileName.equals(f.getName()) && !Boolean.TRUE.equals(f.getIsFolder()))
                .map(f -> new String(blobs.get(f.getFilePath()), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }
}
