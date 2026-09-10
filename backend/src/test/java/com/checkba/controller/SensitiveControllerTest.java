package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.*;
import com.checkba.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SensitiveControllerTest {
    @TempDir Path dir;
    private final ProjectFileService files = mock(ProjectFileService.class);
    private final ProjectMemberService members = mock(ProjectMemberService.class);
    private final StorageServiceFactory storage = mock(StorageServiceFactory.class);
    private final StorageService local = mock(StorageService.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc() { return MockMvcBuilders.standaloneSetup(new SensitiveController(new SensitiveService(), storage, files, members)).build(); }
    private void file() throws Exception {
        Path path = dir.resolve("input.txt"); Files.writeString(path, "甲方：北京星河科技有限公司。电话13800001111");
        var source = new ProjectFile(); source.setId(1L); source.setProjectId(2L); source.setFilePath("projects/2/input.txt");
        when(files.getFile(1L)).thenReturn(source);
        when(storage.getStorageService()).thenReturn(local);
        when(local.load(source.getFilePath())).thenReturn(new FileSystemResource(path));
    }

    @Test void allOperationsRequireAuthenticationBeforeStorageAccess() throws Exception {
        try (var auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            for (String action : List.of("preview", "desensitize", "restore")) {
                mvc().perform(post("/api/sensitive/" + action).contentType("application/json").content("{\"fileId\":1}"))
                        .andExpect(status().isUnauthorized());
            }
            verifyNoInteractions(files, storage);
        }
    }

    @Test void viewerCanPreviewButCannotCreateRedactedOrRestoredFiles() throws Exception {
        file(); when(members.hasReadPermission(2L, 9L)).thenReturn(true);
        try (var auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(9L);
            String body = "{\"fileId\":1,\"strategies\":[\"PHONE\",\"COMPANY\"],\"mode\":\"TOKEN\"}";
            mvc().perform(post("/api/sensitive/preview").header("X-Session-Id", "s").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.counts.COMPANY").value(1)).andExpect(jsonPath("$.counts.PHONE").value(1));
            for (String action : List.of("desensitize", "restore")) {
                mvc().perform(post("/api/sensitive/" + action).header("X-Session-Id", "s").contentType("application/json").content(body))
                        .andExpect(status().isForbidden());
            }
            try (var list = Files.list(dir)) { assertEquals(1, list.count()); }
        }
    }

    @Test void responseContainsOnlyEncryptedKitAndRegisteredRedactedFile() throws Exception {
        file(); when(members.hasReadPermission(2L, 9L)).thenReturn(true); when(members.hasWritePermission(2L, 9L)).thenReturn(true);
        when(files.createFile(eq(2L), isNull(), anyString(), eq("txt"), anyLong(), anyString(), anyString(), eq(9L)))
                .thenAnswer(call -> { var f = new ProjectFile(); f.setId(3L); f.setName(call.getArgument(2)); return f; });
        try (var auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(9L);
            String body = json.writeValueAsString(Map.of("fileId", 1, "strategies", List.of("COMPANY", "PHONE"), "mode", "TOKEN", "password", "test-password-2026"));
            String output = mvc().perform(post("/api/sensitive/desensitize").header("X-Session-Id", "s").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.file.id").value(3)).andReturn().getResponse().getContentAsString();
            assertFalse(output.contains("13800001111")); assertFalse(output.contains("星河")); assertFalse(output.contains("test-password"));
            assertTrue(json.readTree(output).get("recoveryKit").asText().startsWith("AWD-RECOVERY-1:"));
        }
    }
}
