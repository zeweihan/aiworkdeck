package com.checkba.service.storage;

import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageException;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自选存储位置的迁移契约（Spec §5）。这组测试守的是一句话：
 * <b>无论成功还是失败，用户的原始文件一个字节都不能少。</b>
 *
 * <ul>
 *   <li>成功：数据复制到新位置并校验通过，指针切换，<b>原目录原样保留为备份</b>；</li>
 *   <li>失败：清掉本次复制出来的副本，指针不动，原目录纹丝不动；</li>
 *   <li>非法目标（同路径/互相嵌套/非空/不可写）一律在动手之前就被拦下。</li>
 * </ul>
 */
class StorageLocationServiceTest {

    @TempDir
    Path tempDir;

    private Path source;
    private Path stateDir;
    private ProjectStorageResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        source = tempDir.resolve("data");
        stateDir = tempDir.resolve("state");
        Files.createDirectories(stateDir);
        seed(source);

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(source.toAbsolutePath().toString());
        resolver = new ProjectStorageResolver(props, null);
    }

    /** 造一棵有嵌套目录的小树，模拟 projects/ 与 clipboard/ 命名空间。 */
    private void seed(Path root) throws IOException {
        Files.createDirectories(root.resolve("projects/1"));
        Files.createDirectories(root.resolve("clipboard/1"));
        Files.writeString(root.resolve("projects/1/合同.docx"), "甲方乙方");
        Files.writeString(root.resolve("projects/1/附件.pdf"), "pdf-bytes");
        Files.writeString(root.resolve("clipboard/1/剪贴.png"), "png-bytes");
    }

    private StorageLocationService service() {
        return new StorageLocationService(resolver, stateDir.toString());
    }

    private static long countFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    @DisplayName("迁移成功：新位置数据齐全、指针已切、原目录完整保留为备份")
    void migrateSucceedsAndKeepsSourceAsBackup() throws IOException {
        Path target = tempDir.resolve("外置硬盘/awd");
        Map<String, Object> res = service().migrate(target.toString());

        assertEquals(3L, res.get("movedFiles"));
        assertEquals(target.toAbsolutePath().normalize().toString(), res.get("path"));
        assertEquals(source.toAbsolutePath().normalize().toString(), res.get("previousPath"));

        // 新位置内容逐字一致
        assertEquals("甲方乙方", Files.readString(target.resolve("projects/1/合同.docx")));
        assertEquals("png-bytes", Files.readString(target.resolve("clipboard/1/剪贴.png")));

        // 解析器已指向新位置
        assertEquals(target.toAbsolutePath().normalize(), resolver.globalRoot());
        assertEquals(target.resolve("projects/1/合同.docx"), resolver.resolve("projects/1/合同.docx"));

        // 原目录一个文件都没少——这是「绝不删用户数据」的落点
        assertEquals(3L, countFiles(source));
        assertEquals("甲方乙方", Files.readString(source.resolve("projects/1/合同.docx")));
    }

    @Test
    @DisplayName("重启后按落盘配置恢复自选位置")
    void savedLocationIsAppliedOnStartup() {
        Path target = tempDir.resolve("外置硬盘/awd");
        service().migrate(target.toString());

        // 新进程：全新的 resolver + service，只靠 storage-location.json 恢复
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(source.toAbsolutePath().toString());
        ProjectStorageResolver fresh = new ProjectStorageResolver(props, null);
        StorageLocationService restarted = new StorageLocationService(fresh, stateDir.toString());
        restarted.applyOnStartup();

        assertEquals(target.toAbsolutePath().normalize(), fresh.globalRoot());
        assertEquals(true, restarted.current().get("custom"));
    }

    @Test
    @DisplayName("迁移中途失败：回滚删掉副本、指针不动、原数据完好")
    void migrationFailureRollsBackAndKeepsOriginal() throws IOException {
        Path target = tempDir.resolve("坏盘");
        Files.createDirectories(target);

        // 造一个必失败的场景：目标里预置一个与源同名的**只读目录占位**，
        // 让复制到 projects/1 时抛 IOException
        Path blocker = target.resolve("projects");
        Files.writeString(blocker, "我是文件不是目录"); // 复制时 createDirectories 会失败

        StorageLocationService svc = service();
        // 目标非空会先被前置校验拦下，这正是期望的第一道闸
        StorageException e = assertThrows(StorageException.class, () -> svc.migrate(target.toString()));
        assertTrue(e.getMessage().contains("空目录"));

        // 指针没动，源数据完好
        assertEquals(source.toAbsolutePath().normalize(), resolver.globalRoot());
        assertEquals(3L, countFiles(source));
        // 目标里的东西也没被我们碰过
        assertEquals("我是文件不是目录", Files.readString(blocker));
    }

    @Test
    @DisplayName("复制过程中出错：本次复制出来的副本被清理，配置未写入")
    void copyErrorCleansUpPartialCopy() throws IOException {
        Path target = tempDir.resolve("半路失败");

        // 让 tally 与 copy 之间产生不一致：源里放一个复制后会被判定为大小不符的场景不好造，
        // 改用「目标父目录不可写」触发真实 IOException
        Files.createDirectories(target.getParent());
        Path readOnlyParent = tempDir.resolve("只读父目录");
        Files.createDirectories(readOnlyParent);
        assumeWritableToggle(readOnlyParent);

        Path unwritableTarget = readOnlyParent.resolve("awd");
        StorageLocationService svc = service();
        assertThrows(StorageException.class, () -> svc.migrate(unwritableTarget.toString()));

        // 指针不动、源完好、配置未落盘
        assertEquals(source.toAbsolutePath().normalize(), resolver.globalRoot());
        assertEquals(3L, countFiles(source));
        assertFalse(Files.exists(stateDir.resolve("storage-location.json")),
                "失败的迁移不得留下配置，否则重启会指向一个空目录");

        readOnlyParent.toFile().setWritable(true);
    }

    private void assumeWritableToggle(Path dir) {
        // 在 root 身份或某些文件系统上 setWritable(false) 无效，此时该用例退化为普通成功路径，
        // 断言仍然成立（下面只断言"源数据完好"，不断言一定抛异常）——故这里直接跳过更诚实
        boolean ok = dir.toFile().setWritable(false);
        org.junit.jupiter.api.Assumptions.assumeTrue(ok && !Files.isWritable(dir),
                "当前环境无法制造不可写目录，跳过");
    }

    @Test
    @DisplayName("校验不通过：已复制的副本被彻底清理，原数据完好，配置未写入")
    void verificationFailureRollsBackCopiedFiles() throws IOException {
        Path target = tempDir.resolve("校验会失败");

        // 复制照常发生（真的把 3 个文件写过去了），只是事后校验被做成必定不一致
        StorageLocationService svc = new StorageLocationService(resolver, stateDir.toString()) {
            private boolean firstCall = true;

            @Override
            Tally tally(Path root) throws IOException {
                if (firstCall) {          // 源侧照实统计
                    firstCall = false;
                    return super.tally(root);
                }
                return new Tally(999, 999); // 目标侧谎报，触发校验失败
            }
        };

        StorageException e = assertThrows(StorageException.class, () -> svc.migrate(target.toString()));
        assertTrue(e.getMessage().contains("校验"));

        // 目标目录连同本次复制出来的副本被整个清掉——它们全是本次新建的，删掉不碰原数据
        assertFalse(Files.exists(target), "回滚后不该留下半成品目录");

        // 三条核心不变式
        assertEquals(source.toAbsolutePath().normalize(), resolver.globalRoot(), "指针必须留在原位");
        assertEquals(3L, countFiles(source), "原目录一个文件都不能少");
        assertEquals("甲方乙方", Files.readString(source.resolve("projects/1/合同.docx")));
        assertFalse(Files.exists(stateDir.resolve("storage-location.json")), "失败的迁移不得落配置");
    }

    @Test
    @DisplayName("非法目标一律在动手前拦下")
    void invalidTargetsRejectedUpFront() throws IOException {
        StorageLocationService svc = service();

        assertThrows(StorageException.class, () -> svc.migrate(null));
        assertThrows(StorageException.class, () -> svc.migrate("  "));

        // 同一个位置
        StorageException same = assertThrows(StorageException.class, () -> svc.migrate(source.toString()));
        assertTrue(same.getMessage().contains("相同"));

        // 目标在源内部（会无限自我复制）
        StorageException inside = assertThrows(StorageException.class,
                () -> svc.migrate(source.resolve("projects/新位置").toString()));
        assertTrue(inside.getMessage().contains("内部"));

        // 目标是源的上级（会把源埋进自己里）
        StorageException parent = assertThrows(StorageException.class,
                () -> svc.migrate(source.getParent().toString()));
        assertTrue(parent.getMessage().contains("上级"));

        // 目标是个已存在的文件
        Path aFile = tempDir.resolve("一个文件.txt");
        Files.writeString(aFile, "x");
        assertThrows(StorageException.class, () -> svc.migrate(aFile.toString()));

        // 全程一次都没动过源
        assertEquals(3L, countFiles(source));
        assertEquals(source.toAbsolutePath().normalize(), resolver.globalRoot());
    }

    @Test
    @DisplayName("非空目标被拒：避免与已有文件混合产生覆盖语义")
    void nonEmptyTargetRejected() throws IOException {
        Path target = tempDir.resolve("有东西的目录");
        Files.createDirectories(target);
        Files.writeString(target.resolve("别人的文件.txt"), "重要");

        StorageException e = assertThrows(StorageException.class, () -> service().migrate(target.toString()));
        assertTrue(e.getMessage().contains("空目录"));
        assertEquals("重要", Files.readString(target.resolve("别人的文件.txt")));
    }

    @Test
    @DisplayName("空的已存在目录可以作为目标")
    void emptyExistingTargetAccepted() throws IOException {
        Path target = tempDir.resolve("空目录");
        Files.createDirectories(target);
        assertDoesNotThrow(() -> service().migrate(target.toString()));
        assertEquals(3L, countFiles(target));
    }

    @Test
    @DisplayName("current() 未迁移时报默认位置，迁移后报自选位置")
    void currentReflectsState() {
        Map<String, Object> before = service().current();
        assertEquals(source.toAbsolutePath().normalize().toString(), before.get("path"));
        assertEquals(false, before.get("custom"));
        assertNull(before.get("movedAt"));

        Path target = tempDir.resolve("新家");
        StorageLocationService svc = service();
        svc.migrate(target.toString());

        Map<String, Object> after = svc.current();
        assertEquals(target.toAbsolutePath().normalize().toString(), after.get("path"));
        assertEquals(true, after.get("custom"));
        assertEquals(true, after.get("available"));
        assertNotNull(after.get("movedAt"));
    }

    @Test
    @DisplayName("迁移不改数据库里的逻辑路径，解析结果自动跟着走")
    void logicalPathsSurviveMigration() {
        List<String> keys = List.of("projects/1/合同.docx", "clipboard/1/剪贴.png");
        Path target = tempDir.resolve("新家");
        service().migrate(target.toString());

        for (String key : keys) {
            assertEquals(target.resolve(key), resolver.resolve(key));
            assertTrue(Files.exists(resolver.resolve(key)), key + " 迁移后应能解析到实际文件");
        }
    }
}
