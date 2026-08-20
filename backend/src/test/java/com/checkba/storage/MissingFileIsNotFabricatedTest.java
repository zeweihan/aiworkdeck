package com.checkba.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读文件不许就地造一个出来。
 *
 * <p>病灶：{@code LocalFileStorageService.load(key)} 在文件不存在时会**从模板复制一份**
 * 并当成正常结果返回。于是一份正文丢失的合同（换存储位置、同步失败、只恢复了数据库、
 * localRoot 项目里被外部删掉）被读成一份空白模板：用户打开看到空文档、AI 读到的是模板内容，
 * 全程零报错；自动保存再把这份空白盖回去，原件就真的没了。
 *
 * <p>而且它与接口契约（「读取文件」）以及 {@code OssStorageService.load}（一直是抛）都不一致——
 * 同一份代码在桌面端伪造、在云端报错。
 *
 * <p>模板物化本身是新建文档需要的真实能力，所以不是删掉而是**拆开**：
 * 建走 {@code createFromTemplate}，读走 {@code load}。
 */
class MissingFileIsNotFabricatedTest {

    private LocalFileStorageService storageWithTemplate(Path root, Path template) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        props.getLocal().setTemplatePath(template.toAbsolutePath().toString());
        return new LocalFileStorageService(new ProjectStorageResolver(props, null));
    }

    @Test
    @DisplayName("读一个不存在的文件：必须抛，且不许在磁盘上留下任何东西")
    void loadOfMissingFileThrowsAndCreatesNothing(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("template.docx");
        Files.writeString(template, "TEMPLATE-BODY", StandardCharsets.UTF_8);
        Path root = Files.createDirectory(dir.resolve("root"));
        LocalFileStorageService storage = storageWithTemplate(root, template);

        assertThrows(StorageException.class, () -> storage.load("projects/1/合同.docx"),
                "读不到就伪造一份模板 = 用户以为文档被清空了，而且自动保存会把空白盖回去");

        assertFalse(Files.exists(root.resolve("projects/1/合同.docx")),
                "读操作不许有副作用：磁盘上不该凭空多出一个文件");
    }

    @Test
    @DisplayName("读到的必须是真正的原文，不是模板")
    void loadReturnsTheRealBytes(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("template.docx");
        Files.writeString(template, "TEMPLATE-BODY", StandardCharsets.UTF_8);
        Path root = Files.createDirectory(dir.resolve("root"));
        LocalFileStorageService storage = storageWithTemplate(root, template);

        storage.save("projects/1/合同.docx",
                new ByteArrayInputStream("REAL-CONTRACT".getBytes(StandardCharsets.UTF_8)));

        try (var in = storage.load("projects/1/合同.docx").getInputStream()) {
            assertEquals("REAL-CONTRACT", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("新建文档的模板物化仍然可用（能力没被删掉，只是换了入口）")
    void createFromTemplateStillMaterialisesTheFile(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("template.docx");
        Files.writeString(template, "TEMPLATE-BODY", StandardCharsets.UTF_8);
        Path root = Files.createDirectory(dir.resolve("root"));
        LocalFileStorageService storage = storageWithTemplate(root, template);

        storage.createFromTemplate("projects/1/新建文档.docx");

        Path made = root.resolve("projects/1/新建文档.docx");
        assertTrue(Files.exists(made), "新建文档必须真的落一个物理文件");
        assertEquals("TEMPLATE-BODY", Files.readString(made, StandardCharsets.UTF_8));

        try (var in = storage.load("projects/1/新建文档.docx").getInputStream()) {
            assertEquals("TEMPLATE-BODY", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("模板物化是幂等的，绝不覆盖已有正文")
    void createFromTemplateNeverOverwritesExistingContent(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("template.docx");
        Files.writeString(template, "TEMPLATE-BODY", StandardCharsets.UTF_8);
        Path root = Files.createDirectory(dir.resolve("root"));
        LocalFileStorageService storage = storageWithTemplate(root, template);

        storage.save("projects/1/合同.docx",
                new ByteArrayInputStream("REAL-CONTRACT".getBytes(StandardCharsets.UTF_8)));
        storage.createFromTemplate("projects/1/合同.docx");

        assertEquals("REAL-CONTRACT",
                Files.readString(root.resolve("projects/1/合同.docx"), StandardCharsets.UTF_8),
                "幂等：已经有正文了就别动它，否则新建路径会变成一把删除器");
    }

    @Test
    @DisplayName("缺模板文件时物化成空文件，仍然不许把这个行为漏进 load")
    void createFromTemplateFallsBackToAnEmptyFile(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("no-such-template.docx");
        Path root = Files.createDirectory(dir.resolve("root"));
        LocalFileStorageService storage = storageWithTemplate(root, template);

        storage.createFromTemplate("projects/1/新建文档.docx");
        assertTrue(Files.exists(root.resolve("projects/1/新建文档.docx")));

        assertThrows(StorageException.class, () -> storage.load("projects/1/另一个.docx"));
        assertFalse(Files.exists(root.resolve("projects/1/另一个.docx")));
    }
}
