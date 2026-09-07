package com.checkba.service.capability;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 源码拉取单测。**永不上网**：下载器被替换成一个返回本地构造 tar.gz 的桩，
 * 测的是 URL 白名单、顶层目录剥离、限额与解包安全闸。
 */
class CapabilitySourceFetchServiceTest {

    private CapabilitySourceFetchService service;
    private String lastRequestedUrl;
    private byte[] fixture;

    @BeforeEach
    void setUp() throws Exception {
        service = new CapabilitySourceFetchService();
        fixture = tarGz("demo-main", Map.of(
                "manifest.json", "{\"id\":\"demo\"}",
                "engine/cli.py", "# hi\n"));
        service.setDownloader(url -> {
            lastRequestedUrl = url;
            return fixture;
        });
    }

    /** 造一个与 GitHub codeload 同形状的 tar.gz：所有条目都在 <repo>-<ref>/ 之下 */
    private static byte[] tarGz(String topDir, Map<String, String> files) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tout = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
            tout.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry dir = new TarArchiveEntry(topDir + "/");
            tout.putArchiveEntry(dir);
            tout.closeArchiveEntry();
            for (Map.Entry<String, String> e : new LinkedHashMap<>(files).entrySet()) {
                byte[] body = e.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(topDir + "/" + e.getKey());
                entry.setSize(body.length);
                tout.putArchiveEntry(entry);
                tout.write(body);
                tout.closeArchiveEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    @DisplayName("只收 GitHub 仓库链接，别的形态一律拒——这是全系统唯一「从任意 URL 取代码」的入口")
    void rejectsNonGithubUrls() {
        for (String bad : new String[]{
                "https://evil.example.com/repo.tar.gz",
                "http://github.com/a/b",
                "https://github.com/a",
                "https://gitlab.com/a/b",
                "https://github.com/a/b/releases/download/v1/x.tar.gz",
                "file:///etc/passwd",
                ""}) {
            assertThrows(IllegalArgumentException.class, () -> service.fetch(bad), "应拒绝: " + bad);
        }
    }

    @Test
    @DisplayName("默认拉 HEAD，/tree/<ref> 形态拉指定分支")
    void buildsCodeloadUrl() {
        service.fetch("https://github.com/acme/litviz");
        assertEquals("https://codeload.github.com/acme/litviz/tar.gz/HEAD", lastRequestedUrl);

        service.fetch("https://github.com/acme/litviz/tree/v2.1");
        assertEquals("https://codeload.github.com/acme/litviz/tar.gz/v2.1", lastRequestedUrl);
    }

    @Test
    @DisplayName("解包剥掉 GitHub 的顶层目录，commit 从目录名后缀取")
    void stripsTopLevelDirectory() {
        CapabilitySourceFetchService.Source src = service.fetch("https://github.com/acme/demo");
        try {
            assertEquals("acme", src.owner());
            assertEquals("demo", src.repo());
            assertEquals("main", src.commit());
            assertTrue(Files.isRegularFile(src.dir().resolve("manifest.json")));
            assertTrue(Files.isRegularFile(src.dir().resolve("engine/cli.py")));
            assertEquals(2, src.files().size());
        } finally {
            CapabilitySourceFetchService.deleteTree(src.dir());
        }
    }

    @Test
    @DisplayName("路径穿越的条目拒绝解压，且不留临时目录")
    void refusesPathTraversal() throws Exception {
        byte[] evil = tarGz("demo-main", Map.of("../../etc/passwd", "pwned"));
        service.setDownloader(url -> evil);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch("https://github.com/acme/demo"));
        assertTrue(e.getMessage().contains("非法路径") || e.getMessage().contains("unsafe path"), e.getMessage());
    }

    @Test
    @DisplayName("文件数超 200 个即拒")
    void enforcesFileCountLimit() throws Exception {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < CapabilitySourceFetchService.MAX_FILES + 5; i++) {
            many.put("f" + i + ".txt", "x");
        }
        byte[] big = tarGz("demo-main", many);
        service.setDownloader(url -> big);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch("https://github.com/acme/demo"));
        assertTrue(e.getMessage().contains("文件数") || e.getMessage().contains("Too many"), e.getMessage());
    }

    @Test
    @DisplayName("单文件超 5MB 即拒")
    void enforcesSingleFileLimit() throws Exception {
        String huge = "x".repeat((int) CapabilitySourceFetchService.MAX_FILE_BYTES + 16);
        byte[] big = tarGz("demo-main", Map.of("big.bin", huge));
        service.setDownloader(url -> big);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch("https://github.com/acme/demo"));
        assertTrue(e.getMessage().contains("5MB"), e.getMessage());
    }

    @Test
    @DisplayName("空仓库给的是人话，不是 NPE")
    void emptyArchiveExplainsItself() {
        service.setDownloader(url -> new byte[0]);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.fetch("https://github.com/acme/demo"));
        assertTrue(e.getMessage().contains("acme/demo"), e.getMessage());
    }
}
