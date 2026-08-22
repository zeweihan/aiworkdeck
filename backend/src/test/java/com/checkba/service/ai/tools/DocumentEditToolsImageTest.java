package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 尽调模块 P3 稳定性余项 #4（dev-board#100）：doc_insert_image 原来超过 2MB 直接
 * 报错。改成超限时先等比压缩（BufferedImage + ImageIO，JDK 自带，不引新库），压不下去
 * 才报错，错误信息要说清实际大小与上限。
 */
class DocumentEditToolsImageTest {

    private ProjectFileService projectFileService;
    private ProjectStorageResolver storageResolver;
    private EditorBridgeService bridge;
    private DocumentEditTools tools;

    @BeforeEach
    void setUp() {
        projectFileService = Mockito.mock(ProjectFileService.class);
        storageResolver = Mockito.mock(ProjectStorageResolver.class);
        bridge = Mockito.mock(EditorBridgeService.class);
        tools = new DocumentEditTools(projectFileService, null, bridge, storageResolver, null, null, null);
        when(bridge.executeEditorCommand(any(), any())).thenReturn("{\"success\":true}");
        ProjectContextHolder.setProjectId("7");
    }

    @AfterEach
    void tearDown() {
        ProjectContextHolder.clear();
    }

    private ProjectFile registerImageFile(Path path, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(1L);
        f.setName(name);
        f.setProjectId(7L);
        f.setFilePath("图片/" + name);
        when(projectFileService.getFile(1L)).thenReturn(f);
        when(storageResolver.resolve("图片/" + name)).thenReturn(path);
        return f;
    }

    /** 随机噪声图 PNG 压缩比很差，用来稳定制造出一份"体积确实超过 2MB"的合法图片文件。 */
    private byte[] noisyPng(int size) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(1);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) img.setRGB(x, y, r.nextInt());
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    @DisplayName("超过 2MB 的图片：先等比压缩再插入，不直接报错")
    void oversizedImageIsCompressedInsteadOfRejected(@TempDir Path dir) throws Exception {
        byte[] big = noisyPng(1200); // 随机噪声 1200x1200 PNG，稳定超过 2MB
        assertTrue(big.length > 2L * 1024 * 1024, "前置条件：源文件体积要真的超过 2MB，实际 " + big.length);
        Path path = dir.resolve("现场照片.png");
        Files.write(path, big);
        registerImageFile(path, "现场照片.png");

        String result = tools.doc_insert_image(1L);

        assertFalse(result.startsWith("Error"), result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq("insert_image"), cap.capture());
        String base64 = (String) cap.getValue().get("base64");
        byte[] sent = Base64.getDecoder().decode(base64);
        assertTrue(sent.length <= 2L * 1024 * 1024,
            "压缩后仍要遵守 2MB 上限，实际发送 " + sent.length + " 字节");
        assertTrue(sent.length < big.length, "压缩后应该比原图小");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(sent));
        assertNotNull(decoded, "压缩产物必须仍是一张能解出来的合法图片");
    }

    @Test
    @DisplayName("2MB 以内的图片：原样插入，不做任何压缩")
    void normalSizedImageIsInsertedAsIs(@TempDir Path dir) throws Exception {
        byte[] small = new byte[1024];
        new Random(2).nextBytes(small);
        // 不是合法图片也没关系——体积在阈值内的分支根本不解码，只是原样读字节
        Path path = dir.resolve("小图.png");
        Files.write(path, small);
        registerImageFile(path, "小图.png");

        String result = tools.doc_insert_image(1L);

        assertFalse(result.startsWith("Error"), result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(bridge).executeEditorCommand(eq("insert_image"), cap.capture());
        byte[] sent = Base64.getDecoder().decode((String) cap.getValue().get("base64"));
        assertArrayEquals(small, sent, "阈值内的图片必须原样发送，不能被意外压缩改动字节");
    }

    @Test
    @DisplayName("压缩上限调到不可能达到的地步：给出说清实际体积与上限的错误，不假装成功")
    void givesUpWithClearSizesWhenCompressionCannotReachLimit(@TempDir Path dir) throws Exception {
        byte[] big = noisyPng(1200);
        assertTrue(big.length > 2L * 1024 * 1024);
        Path path = dir.resolve("现场照片.png");
        Files.write(path, big);
        registerImageFile(path, "现场照片.png");
        // 任何真实图片压缩到 100 字节都不可能（JPEG 头部开销已经超过这个数），
        // 逼真实的压缩循环走到"压不下去"分支，而不是构造一份人为超大的噪声图。
        tools.setMaxImageBytesForTest(100);

        String result = tools.doc_insert_image(1L);

        assertTrue(result.startsWith("Error"), result);
        assertTrue(result.contains(String.valueOf(big.length)), "错误信息要说清实际体积: " + result);
        assertTrue(result.contains("100"), "错误信息要说清上限: " + result);
        verify(bridge, Mockito.never()).executeEditorCommand(eq("insert_image"), any());
    }

    @Test
    @DisplayName("超限但根本不是合法图片（无法解码）：给出明确错误而不是抛异常")
    void undecodableOversizedFileFailsCleanly(@TempDir Path dir) throws Exception {
        byte[] junk = new byte[3 * 1024 * 1024];
        new Random(3).nextBytes(junk); // 纯随机字节，不是任何图片格式
        Path path = dir.resolve("坏文件.png");
        Files.write(path, junk);
        registerImageFile(path, "坏文件.png");

        String result = tools.doc_insert_image(1L);

        assertTrue(result.startsWith("Error"), result);
        assertTrue(result.contains(String.valueOf(junk.length)), result);
        verify(bridge, Mockito.never()).executeEditorCommand(eq("insert_image"), any());
    }
}
