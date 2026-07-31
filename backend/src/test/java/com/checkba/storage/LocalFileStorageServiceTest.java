package com.checkba.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 LocalFileStorageService 的路径遍历围栏：
 * 任何 normalize 后逃出存储根的 fileId 都必须被拒绝，而非读写/删除根外文件。
 */
class LocalFileStorageServiceTest {

    private LocalFileStorageService storage;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(tempDir.toAbsolutePath().toString());
        props.getLocal().setTemplatePath(tempDir.resolve("no-template.docx").toAbsolutePath().toString());
        storage = new LocalFileStorageService(new ProjectStorageResolver(props, null));
    }

    private ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void savesAndReadsNormalKeyWithinRoot() {
        storage.save("projects/1/note.txt", bytes("hello"));
        assertTrue(storage.exists("projects/1/note.txt"));
    }

    @Test
    void rejectsRelativeTraversalOnSave() {
        assertThrows(StorageException.class,
                () -> storage.save("../escape.txt", bytes("x")));
    }

    @Test
    void rejectsDeepTraversalOnExists() {
        assertThrows(StorageException.class,
                () -> storage.exists("projects/1/../../../../etc/passwd"));
    }

    @Test
    void rejectsTraversalOnDelete() {
        assertThrows(StorageException.class,
                () -> storage.delete("../../secret"));
    }
}
