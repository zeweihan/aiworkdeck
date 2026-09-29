// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ai.AutoTaggingService;
import com.checkba.service.ai.ProjectRagService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 锁定 GET /api/files/{id}/download 的 Range 语义（dev-board#1025）：
 * 媒体元素按需拖动依赖 206 + Content-Range，无 Range 的 200 也必须声明 Accept-Ranges: bytes，
 * 否则 Chromium 不会发 Range。鉴权先于 Range 处理，带 Range 也不能绕过 403。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileControllerRangeTest {

    @Mock
    private ProjectFileRepository projectFileRepository;
    @Mock
    private ProjectMemberService projectMemberService;
    @Mock
    private StorageServiceFactory storageServiceFactory;
    @Mock
    private StorageService storageService;
    @Mock
    private ProjectRagService projectRagService;
    @Mock
    private AutoTaggingService autoTaggingService;

    @InjectMocks
    private FileController controller;

    @TempDir
    Path tmp;

    private byte[] content;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        content = new byte[1000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        Path file = tmp.resolve("clip.bin");
        Files.write(file, content);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
        when(storageService.load(anyString())).thenReturn(new FileSystemResource(file));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void givenFile(String name, String type) {
        ProjectFile pf = new ProjectFile();
        pf.setId(5L);
        pf.setProjectId(42L);
        pf.setName(name);
        pf.setFileType(type);
        pf.setFilePath("projects/42/" + name);
        when(projectFileRepository.findById(5L)).thenReturn(Optional.of(pf));
    }

    private MvcResult download(String range, boolean member) throws Exception {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(member);
            var req = get("/api/files/5/download").param("token", "sess");
            if (range != null) {
                req = req.header("Range", range);
            }
            return mvc.perform(req).andReturn();
        }
    }

    @Test
    void range_0_99_returns_206_with_slice() throws Exception {
        givenFile("clip.mp4", "mp4");
        MvcResult r = download("bytes=0-99", true);
        assertEquals(206, r.getResponse().getStatus());
        assertEquals("bytes 0-99/1000", r.getResponse().getHeader("Content-Range"));
        assertEquals("bytes", r.getResponse().getHeader("Accept-Ranges"));
        byte[] body = r.getResponse().getContentAsByteArray();
        assertEquals(100, body.length);
        assertArrayEquals(Arrays.copyOfRange(content, 0, 100), body);
        assertEquals("100", r.getResponse().getHeader("Content-Length"));
    }

    @Test
    void no_range_returns_200_with_accept_ranges() throws Exception {
        givenFile("clip.mp4", "mp4");
        MvcResult r = download(null, true);
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("bytes", r.getResponse().getHeader("Accept-Ranges"));
        assertEquals(1000, r.getResponse().getContentAsByteArray().length);
        assertTrue(r.getResponse().getHeader("Content-Disposition").startsWith("attachment;"));
    }

    @Test
    void range_beyond_length_returns_416() throws Exception {
        givenFile("clip.mp4", "mp4");
        MvcResult r = download("bytes=2000-2100", true);
        assertEquals(416, r.getResponse().getStatus());
        assertEquals("bytes */1000", r.getResponse().getHeader("Content-Range"));
    }

    @Test
    void range_without_permission_still_403() throws Exception {
        givenFile("clip.mp4", "mp4");
        MvcResult r = download("bytes=0-99", false);
        assertEquals(403, r.getResponse().getStatus());
        assertEquals(0, r.getResponse().getContentAsByteArray().length);
    }

    @Test
    void mov_maps_to_video_quicktime() throws Exception {
        givenFile("clip.mov", "mov");
        assertEquals("video/quicktime", download(null, true).getResponse().getContentType());
    }

    @Test
    void m4a_maps_to_audio_mp4() throws Exception {
        givenFile("voice.m4a", "m4a");
        assertEquals("audio/mp4", download(null, true).getResponse().getContentType());
    }

    @Test
    void range_with_unknown_length_falls_back_to_full_200() throws Exception {
        givenFile("clip.mp4", "mp4");
        // 模拟 OSS 之类取不到长度的 Resource：contentLength 抛 IOException
        Resource noLength = new AbstractResource() {
            @Override
            public String getDescription() {
                return "no-length resource";
            }

            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(content);
            }

            @Override
            public long contentLength() throws IOException {
                throw new IOException("length unknown");
            }
        };
        when(storageService.load(anyString())).thenReturn(noLength);
        MvcResult r = download("bytes=0-99", true);
        assertEquals(200, r.getResponse().getStatus());
        assertEquals(1000, r.getResponse().getContentAsByteArray().length);
    }
}
