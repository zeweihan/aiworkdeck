// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.model.entity.ClipboardItem;
import com.checkba.repository.ClipboardItemRepository;
import com.checkba.service.ClipboardService;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 剪贴板文件按本机路径存（dev-board B14）：桌面端复制了一个文件时，服务端自己把字节 copy 进
 * 剪贴板库（clipboard/{userId}/{uuid}），渲染进程不再把整个文件读进内存再 POST 回来。
 * 路径形态校验与 import-local 共用 ProjectFileService.resolveLocalSourcePath。
 */
class ClipboardLocalFileTest {

    private static final Long USER = 7L;

    private ClipboardItemRepository repository;
    private StorageService storage;
    private ClipboardService service;
    private ClipboardController controller;
    private final ByteArrayOutputStream stored = new ByteArrayOutputStream();
    private String storedKey;

    @BeforeEach
    void setUp() {
        repository = mock(ClipboardItemRepository.class);
        when(repository.save(any(ClipboardItem.class))).thenAnswer(inv -> inv.getArgument(0));
        storage = mock(StorageService.class);
        when(storage.save(anyString(), any(InputStream.class))).thenAnswer(inv -> {
            storedKey = inv.getArgument(0);
            ((InputStream) inv.getArgument(1)).transferTo(stored);
            return storedKey;
        });
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(storage);
        service = new ClipboardService(repository, factory, mock(EntitlementService.class), true);
        controller = new ClipboardController(service);
        ReflectionTestUtils.setField(controller, "localMode", true);
    }

    private ResponseEntity<?> post(String path) {
        ClipboardController.SaveLocalFileRequest req = new ClipboardController.SaveLocalFileRequest();
        req.setSourcePath(path);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER);
            return controller.saveLocalFile("sess", req);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> r) {
        return (Map<String, Object>) r.getBody();
    }

    @Test
    @DisplayName("服务端自己 copy：字节进 clipboard/{userId}/{uuid}，记一行 FILE，文件名取自路径")
    void copiesBytesIntoClipboardStore(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("证据清单.pdf");
        Files.writeString(src, "pdf-bytes");

        ResponseEntity<?> r = post(src.toString());

        assertEquals(200, r.getStatusCode().value(), String.valueOf(r.getBody()));
        assertEquals(0, body(r).get("code"));
        assertTrue(storedKey.startsWith("clipboard/" + USER + "/"), storedKey);
        assertEquals("pdf-bytes", stored.toString(java.nio.charset.StandardCharsets.UTF_8));
        ClipboardItem item = (ClipboardItem) body(r).get("data");
        assertEquals("FILE", item.getType());
        assertTrue(item.getMeta().contains("证据清单.pdf"), item.getMeta());
        assertTrue(Files.exists(src), "源文件只读不动");
    }

    @Test
    @DisplayName("非 local-mode 一律拒绝，一个字节都不读")
    void rejectedOutsideLocalMode(@TempDir Path dir) throws Exception {
        ReflectionTestUtils.setField(controller, "localMode", false);
        Path src = dir.resolve("a.txt");
        Files.writeString(src, "x");

        ResponseEntity<?> r = post(src.toString());

        assertEquals(403, r.getStatusCode().value());
        verify(storage, never()).save(anyString(), any(InputStream.class));
    }

    @Test
    @DisplayName("与 import-local 同一套路径校验：相对路径、符号链接、目录、不存在都拒绝")
    void sharedPathValidation(@TempDir Path dir) throws Exception {
        Path real = dir.resolve("real.txt");
        Files.writeString(real, "x");
        Path link = dir.resolve("link.txt");
        Files.createSymbolicLink(link, real);

        for (String bad : new String[]{"relative/a.txt", link.toString(), dir.toString(),
                dir.resolve("missing.txt").toString(), ""}) {
            ResponseEntity<?> r = post(bad);
            assertEquals(400, r.getStatusCode().value(), "应拒绝：" + bad + " -> " + r.getBody());
        }
        verify(storage, never()).save(anyString(), any(InputStream.class));
    }

    @Test
    @DisplayName("超过单文件上限拒绝（与 POST /file 的 multipart 上限同口径）")
    void rejectsOversize(@TempDir Path dir) throws Exception {
        ReflectionTestUtils.setField(controller, "maxFileSize", org.springframework.util.unit.DataSize.ofBytes(4));
        Path src = dir.resolve("big.bin");
        Files.writeString(src, "12345");

        ResponseEntity<?> r = post(src.toString());

        assertEquals(400, r.getStatusCode().value());
        verify(storage, never()).save(anyString(), any(InputStream.class));
    }
}
