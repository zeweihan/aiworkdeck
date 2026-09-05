package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 压缩包预览/解压（zip/7z/rar）：条目列表、中文名、macOS 噪音过滤、
 * zip-slip 拒绝、7z 往返、坏包友好报错、解压建目录树与写字节。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFileServiceArchiveTest {

    @Mock
    private ProjectFileRepository projectFileRepository;
    @Mock
    private com.checkba.service.ai.ProjectRagService projectRagService;
    @Mock
    private StorageServiceFactory storageServiceFactory;
    @Mock
    private StorageService storageService;
    @Mock
    private com.checkba.service.telemetry.TelemetryService telemetryService;

    @InjectMocks
    private ProjectFileService projectFileService;

    private static final long ARCHIVE_ID = 10L;
    private static final long PROJECT_ID = 1L;

    /**
     * 假的「库里现有的行」。解压建出来的文件夹要能被 findById 查到——
     * 真库里 save 完当然查得到，父节点校验（ProjectFileService.resolveParentId，
     * dev-board#457）与 buildPhysicalPath 都要走这一步。
     */
    private final Map<Long, ProjectFile> rows = new HashMap<>();

    // ---- fixtures -----------------------------------------------------------

    private static byte[] makeZip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("docs/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("docs/合同范本.txt"));
            zos.write("hello 合同".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("readme.md"));
            zos.write("root file".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            // macOS 打包噪音，应被过滤
            zos.putNextEntry(new ZipEntry("__MACOSX/docs/._合同范本.txt"));
            zos.write(new byte[]{0});
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("docs/.DS_Store"));
            zos.write(new byte[]{0});
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] makeZipSlip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("../evil.txt"));
            zos.write("pwn".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] make7z() throws Exception {
        SeekableInMemoryByteChannel ch = new SeekableInMemoryByteChannel();
        try (SevenZOutputFile out = new SevenZOutputFile(ch)) {
            SevenZArchiveEntry dir = new SevenZArchiveEntry();
            dir.setName("附件");
            dir.setDirectory(true);
            out.putArchiveEntry(dir);
            out.closeArchiveEntry();
            SevenZArchiveEntry f = new SevenZArchiveEntry();
            f.setName("附件/证据清单.txt");
            f.setDirectory(false);
            // 手工构造的 entry 必须 setHasStream(true)，否则 closeArchiveEntry
            // 直接丢弃已写入的内容（e2e 实测踩坑：解压出 0 字节）
            f.setHasStream(true);
            byte[] data = "7z 内容".getBytes(StandardCharsets.UTF_8);
            f.setSize(data.length);
            out.putArchiveEntry(f);
            out.write(data);
            out.closeArchiveEntry();
        }
        byte[] all = ch.array();
        return java.util.Arrays.copyOf(all, (int) ch.size());
    }

    private void stubArchive(String name, String type, byte[] bytes) {
        ProjectFile pf = new ProjectFile();
        pf.setId(ARCHIVE_ID);
        pf.setProjectId(PROJECT_ID);
        pf.setParentId(null);
        pf.setIsFolder(false);
        pf.setName(name);
        pf.setFileType(type);
        pf.setFileSize((long) bytes.length);
        pf.setFilePath("projects/1/" + name);
        rows.put(ARCHIVE_ID, pf);
        when(projectFileRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<Long>getArgument(0))));
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        try {
            when(storageService.load("projects/1/" + name)).thenReturn(new ByteArrayResource(bytes));
        } catch (Exception ignored) { }
    }

    // ---- 条目列表 ------------------------------------------------------------

    @Test
    void listZipEntriesFiltersNoiseAndKeepsChineseNames() throws Exception {
        stubArchive("样本.zip", "zip", makeZip());
        List<Map<String, Object>> entries = projectFileService.listArchiveEntries(ARCHIVE_ID);
        List<String> paths = entries.stream().map(e -> (String) e.get("path")).toList();
        assertTrue(paths.contains("docs/合同范本.txt"), "中文名应保留: " + paths);
        assertTrue(paths.contains("readme.md"));
        assertTrue(paths.stream().noneMatch(p -> p.startsWith("__MACOSX")), "__MACOSX 应被过滤");
        assertTrue(paths.stream().noneMatch(p -> p.endsWith(".DS_Store")), ".DS_Store 应被过滤");
    }

    @Test
    void listZipDecodesGbkNamesWithoutEfsFlag() throws Exception {
        // 旧版 Windows 压缩包：GBK 编码条目名、无 UTF-8(EFS) 标志位 → UTF-8 打开
        // 失败后应回退 GBK 成功解码
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, java.nio.charset.Charset.forName("GBK"))) {
            zos.putNextEntry(new ZipEntry("目录/中文文档.txt"));
            zos.write("gbk".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        stubArchive("gbk.zip", "zip", bos.toByteArray());
        List<Map<String, Object>> entries = projectFileService.listArchiveEntries(ARCHIVE_ID);
        List<String> paths = entries.stream().map(e -> (String) e.get("path")).toList();
        assertTrue(paths.contains("目录/中文文档.txt"), String.valueOf(paths));
    }

    @Test
    void listZipRejectsZipSlipPaths() throws Exception {
        stubArchive("evil.zip", "zip", makeZipSlip());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> projectFileService.listArchiveEntries(ARCHIVE_ID));
        assertTrue(e.getMessage().contains("非法路径"), e.getMessage());
    }

    @Test
    void list7zEntriesRoundTrip() throws Exception {
        stubArchive("样本.7z", "7z", make7z());
        List<Map<String, Object>> entries = projectFileService.listArchiveEntries(ARCHIVE_ID);
        Map<String, Object> file = entries.stream()
                .filter(e -> "附件/证据清单.txt".equals(e.get("path"))).findFirst().orElseThrow();
        // size 必须非 0——否则说明内容流被丢（见 make7z 的 setHasStream 坑）
        assertEquals((long) "7z 内容".getBytes(StandardCharsets.UTF_8).length, file.get("size"));
    }

    @Test
    void corruptRarYieldsFriendlyError() {
        stubArchive("broken.rar", "rar", "definitely not a rar".getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> projectFileService.listArchiveEntries(ARCHIVE_ID));
        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().isBlank());
    }

    @Test
    void unsupportedTypeRejected() {
        stubArchive("a.tar", "tar", new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> projectFileService.listArchiveEntries(ARCHIVE_ID));
    }

    // ---- 解压 ---------------------------------------------------------------

    @Test
    void extractZipCreatesTreeAndWritesBytes() throws Exception {
        stubArchive("样本.zip", "zip", makeZip());

        AtomicLong ids = new AtomicLong(100);
        List<ProjectFile> saved = new ArrayList<>();
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(anyLong(), any(), any(), anyLong()))
                .thenReturn(false);
        when(projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(anyLong(), any()))
                .thenReturn(List.of());
        when(projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> {
            ProjectFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(ids.incrementAndGet());
            saved.add(f);
            rows.put(f.getId(), f);
            return f;
        });
        // createFile 里的 load（模板物化）与解压时的 save 都打到 mock 上
        lenient().when(storageService.save(any(), any(java.io.InputStream.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectFile root = projectFileService.extractArchive(PROJECT_ID, ARCHIVE_ID, 42L);

        assertNotNull(root.getId());
        assertEquals("样本", root.getName());
        assertTrue(Boolean.TRUE.equals(root.getIsFolder()));

        List<String> names = saved.stream().map(ProjectFile::getName).toList();
        assertTrue(names.contains("docs"), String.valueOf(names));
        assertTrue(names.contains("合同范本.txt"), String.valueOf(names));
        assertTrue(names.contains("readme.md"), String.valueOf(names));
        // 噪音不落库
        assertTrue(names.stream().noneMatch(n -> n.contains("DS_Store") || n.contains("__MACOSX")));

        ProjectFile contract = saved.stream().filter(f -> "合同范本.txt".equals(f.getName())).findFirst().orElseThrow();
        assertEquals("txt", contract.getFileType());
        assertEquals("hello 合同".getBytes(StandardCharsets.UTF_8).length, contract.getFileSize());
        assertNotNull(contract.getWpsFileId());
    }

    @Test
    void extractZipWithImplodedEntryDecodesContent() throws Exception {
        // 老 PKZIP 的 Implode(方法 6) 条目：解码走 commons-compress 的 BinaryTree，
        // 其内部调用 commons-lang3 的 ArrayFill(3.14 才有)。Spring Boot BOM 若把
        // lang3 钉回 3.13 会 NoClassDefFoundError（Error 不进 catch(Exception)，
        // 直接裸奔成 500）。样本取自 commons-compress 官方测试资源。
        byte[] zip;
        try (java.io.InputStream in = getClass().getResourceAsStream("/imploding-8Kdict-3trees.zip")) {
            zip = in.readAllBytes();
        }
        stubArchive("legacy.zip", "zip", zip);

        AtomicLong ids = new AtomicLong(100);
        List<ProjectFile> saved = new ArrayList<>();
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(anyLong(), any(), any(), anyLong()))
                .thenReturn(false);
        when(projectFileRepository.findByProjectIdAndParentIdOrderBySortOrderAsc(anyLong(), any()))
                .thenReturn(List.of());
        when(projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(projectFileRepository.save(any(ProjectFile.class))).thenAnswer(inv -> {
            ProjectFile f = inv.getArgument(0);
            if (f.getId() == null) f.setId(ids.incrementAndGet());
            saved.add(f);
            rows.put(f.getId(), f);
            return f;
        });
        lenient().when(storageService.save(any(), any(java.io.InputStream.class))).thenAnswer(inv -> inv.getArgument(0));

        projectFileService.extractArchive(PROJECT_ID, ARCHIVE_ID, 42L);

        ProjectFile license = saved.stream().filter(f -> "LICENSE.TXT".equals(f.getName())).findFirst().orElseThrow();
        assertEquals(11560L, license.getFileSize(), "imploded 条目应完整解码");
    }
}
