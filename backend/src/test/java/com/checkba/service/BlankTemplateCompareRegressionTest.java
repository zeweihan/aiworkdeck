// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.storage.LocalFileStorageService;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：资源管理器「新建文档 A（有内容）+ 新建空白文档 B → 选中比对」提示文件找不到。
 *
 * <p>病灶在 {@code LocalFileStorageService.createFromTemplate} 的模板缺失兜底：
 * 物化成 0 字节文件后，/api/files/compare 的 {@link DocumentTextService#extractText}
 * 对空流抛 {@code ZeroByteFileException}（Tika 拒绝 0 字节输入），比对 500。
 * 修复后兜底成有效的空白 DOCX，A/B 两条路径都要能被比对打开；
 * 而磁盘上真丢了的文件必须继续报错，不许被兜底掩盖。
 */
class BlankTemplateCompareRegressionTest {

    private LocalFileStorageService storageWithTemplate(Path root, Path template) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        props.getLocal().setTemplatePath(template.toAbsolutePath().toString());
        return new LocalFileStorageService(new ProjectStorageResolver(props, null));
    }

    private DocumentTextService textServiceOf(LocalFileStorageService storage) {
        StorageServiceFactory factory = new StorageServiceFactory() {
            @Override
            public com.checkba.storage.StorageService getStorageService() {
                return storage;
            }
        };
        return new DocumentTextService(factory);
    }

    private ProjectFile file(long id, String path) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setName(Path.of(path).getFileName().toString());
        f.setFileType("docx");
        f.setFilePath(path);
        return f;
    }

    @Test
    @DisplayName("模板缺失环境下新建空白文档 B：物化后可直接被比对抽取，不再是 0 字节")
    void blankDocFromMissingTemplateIsComparable(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectory(dir.resolve("root"));
        // 模拟 cloud 等环境：配置的模板路径不存在
        LocalFileStorageService storage = storageWithTemplate(root, dir.resolve("no-such-template.docx"));

        storage.createFromTemplate("projects/9/空白文档B.docx");
        Path made = root.resolve("projects/9/空白文档B.docx");
        assertTrue(Files.size(made) > 0, "兜底必须是有效的空白 DOCX，不能是 0 字节文件");

        String text = textServiceOf(storage).extractText(file(2L, "projects/9/空白文档B.docx"));
        assertTrue(text != null && text.isBlank(), "空白文档抽出空文本即可，但不能抛异常");
    }

    @Test
    @DisplayName("文件创建与抽取路径：新建 A 写入内容 + 新建空白 B，两份都能走 compare 的抽取")
    void newContentDocAndNewBlankDocBothExtractable(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectory(dir.resolve("root"));
        LocalFileStorageService storage = storageWithTemplate(root, dir.resolve("no-such-template.docx"));

        // A：新建后编辑器首次保存写入真实内容（upload 覆盖物化文件）
        storage.createFromTemplate("projects/9/合同A.docx");
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("甲方应当于2026年12月31日前完成出资");
            doc.write(out);
            bytes = out.toByteArray();
        }
        storage.save("projects/9/合同A.docx", new ByteArrayInputStream(bytes));
        // B：新建后从未输入内容、从未保存
        storage.createFromTemplate("projects/9/空白文档B.docx");

        DocumentTextService svc = textServiceOf(storage);
        assertEquals("甲方应当于2026年12月31日前完成出资",
                svc.extractText(file(1L, "projects/9/合同A.docx")).trim());
        assertTrue(svc.extractText(file(2L, "projects/9/空白文档B.docx")).isBlank());
    }

    @Test
    @DisplayName("负例：数据库有记录但磁盘文件真丢了，抽取必须显式报错，不许兜底成空文档")
    void genuinelyMissingFileStillFailsLoudly(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectory(dir.resolve("root"));
        LocalFileStorageService storage = storageWithTemplate(root, dir.resolve("no-such-template.docx"));

        // 只有 DB 行（filePath 指向不存在的物理文件），磁盘上什么都没有
        assertThrows(java.io.IOException.class,
                () -> textServiceOf(storage).extractText(file(3L, "projects/9/丢失的合同.docx")),
                "真丢的文件兜底成空白 = 掩盖数据丢失");
    }
}
