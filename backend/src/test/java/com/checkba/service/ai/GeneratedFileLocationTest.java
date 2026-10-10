// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.tools.*;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneratedFileLocationTest {
    private final ProjectFileService files = mock(ProjectFileService.class);
    private ProjectFile file(long id, long project, Long parent, boolean folder) {
        ProjectFile f = new ProjectFile();
        f.setId(id); f.setProjectId(project); f.setParentId(parent); f.setIsFolder(folder);
        f.setName("同名文件.docx"); f.setFilePath("projects/1/合同/同名文件.docx");
        when(files.findFile(id)).thenReturn(Optional.of(f));
        return f;
    }
    private ToolContext context(Long parent) {
        return new ToolContext(1L, "same-conversation", 7L, null, List.of(), null, "run", null, null, parent);
    }
    public static class CreationTools implements AgentToolComponent {
        @Tool("create text") public String write_file(Long projectId, Long parentFolderId) { return projectId + ":" + parentFolderId; }
        @Tool("create docx") public String write_docx(Long parentFolderId) { return "" + parentFolderId; }
        @Tool("stream") public String doc_start_stream(Long fileId, Long parentFolderId) { return fileId + ":" + parentFolderId; }
        @Tool("sheet") public String sheet_create_file(Long parentFolderId) { return "" + parentFolderId; }
        @Tool("pptx config") public String pptx_generate(Long parentId) { return "" + parentId; }
        @Tool("move") public String move_project_file(Long parentFolderId) { return "" + parentFolderId; }
        @Tool("pdf derivative") public String pdf_to_word(Long parentId) { return "" + parentId; }
    }
    private ToolRegistry registry() {
        ToolRegistry r = new ToolRegistry(List.of(new CreationTools()), new PluginService(), new ClientCapabilityService());
        ReflectionTestUtils.setField(r, "projectFileService", files); r.init(); return r;
    }
    @Test void defaultComesOnlyFromLiveSameProjectFileAndFolder() {
        file(7, 1, null, true); ProjectFile active = file(42, 1, 7L, false);
        assertEquals(7L, GeneratedFileLocation.initialParent(files, 1L, 42L));
        assertNull(GeneratedFileLocation.initialParent(files, 2L, 42L));
        active.setIsDeleted(true); assertNull(GeneratedFileLocation.initialParent(files, 1L, 42L));
        active.setIsDeleted(false); active.setParentId(99L); assertNull(GeneratedFileLocation.initialParent(files, 1L, 42L));
        assertNull(GeneratedFileLocation.initialParent(files, 1L, null));
    }
    @Test void hiddenStagingFoldersAndTheirDescendantsAreNotDefaultDestinations() {
        ProjectFile hidden = file(7, 1, null, true); hidden.setName(".stagezone");
        file(8, 1, 7L, true); ProjectFile active = file(42, 1, 8L, false);
        assertNull(GeneratedFileLocation.initialParent(files, 1L, 42L));
        active.setParentId(7L); hidden.setName("__staging_area__");
        assertNull(GeneratedFileLocation.initialParent(files, 1L, 42L));
        assertEquals(7L, GeneratedFileLocation.resolve(files, 1L, 7L, null), "explicit folder is separate from default inference");
        hidden.setName("正常目录"); assertEquals(7L, GeneratedFileLocation.initialParent(files, 1L, 42L));
        hidden.setParentId(7L); assertNull(GeneratedFileLocation.initialParent(files, 1L, 42L));
    }
    @Test void explicitFolderWinsRootIsZeroAndInvalidFolderNeverFallsBack() {
        file(7, 1, null, true); file(8, 1, null, true); file(9, 2, null, true); file(10, 1, null, false);
        var r = registry();
        assertEquals("1:7", r.execute("write_file", "{}", context(7L)).output());
        assertEquals("1:8", r.execute("write_file", "{\"parentFolderId\":8,\"projectId\":2}", context(7L)).output());
        assertEquals("1:null", r.execute("write_file", "{\"parentFolderId\":0}", context(7L)).output());
        assertEquals("1:null", r.execute("write_file", "{}", context(null)).output());
        for (int id : new int[]{-1, 9, 10, 404}) assertFalse(r.execute("write_file", "{\"parentFolderId\":" + id + "}", context(7L)).success());
        file(7, 1, null, true).setIsDeleted(true);
        assertFalse(r.execute("write_file", "{}", context(7L)).success(), "stale frozen destination must be reported, not silently relocated");
    }
    @Test void whiteListDoesNotRedirectExistingEditsMovesOrPdfDerivatives() {
        file(7, 1, null, true); var r = registry();
        for (String tool : List.of("write_docx", "sheet_create_file", "pptx_generate"))
            assertEquals("7", r.execute(tool, "{}", context(7L)).output(), tool);
        assertEquals("null:7", r.execute("doc_start_stream", "{}", context(7L)).output());
        assertEquals("42:null", r.execute("doc_start_stream", "{\"fileId\":42}", context(7L)).output());
        assertEquals("null", r.execute("move_project_file", "{}", context(7L)).output());
        assertEquals("null", r.execute("pdf_to_word", "{}", context(7L)).output());
    }
    @Test void sameConversationDifferentRunsKeepTheirOwnDefaultsAndRootAliases() {
        file(7, 1, null, true); file(8, 1, null, true); var r = registry();
        assertEquals("7", r.execute("write_docx", "{}", context(7L)).output());
        assertEquals("8", r.execute("write_docx", "{}", context(8L)).output());
        assertEquals("7", r.execute("write_docx", "{}", context(7L)).output());
        assertEquals("null", r.execute("write_docx", "{\"parentId\":0}", context(7L)).output());
    }
    @Test void resultIdentityRequiresStructuredSuccessAndProjectOwnership() {
        ProjectFile f = file(42, 1, 7L, false); f.setName("带\"引号.docx");
        String result = GeneratedFileLocation.createdResult(f, "已生成");
        assertEquals(f, GeneratedFileLocation.createdFile(files, 1L, "write_docx", result));
        assertTrue(result.contains("合同/同名文件.docx"));
        assertFalse(result.contains("projects/1/"));
        f.setFilePath("/Users/example/private.docx");
        assertFalse(GeneratedFileLocation.createdResult(f, null).contains("/Users/"));
        assertNull(GeneratedFileLocation.createdFile(files, 2L, "write_docx", result));
        assertNull(GeneratedFileLocation.createdFile(files, 1L, "move_project_file", result));
        for (String invalid : List.of("文件 ID: 42", "{\"status\":\"error\",\"db_id\":42}", "{\"status\":\"success\",\"db_id\":42.5}", "{\"status\":\"success\",\"db_id\":\"42\"}"))
            assertNull(GeneratedFileLocation.createdId(invalid));
        f.setIsDeleted(true); assertNull(GeneratedFileLocation.createdFile(files, 1L, "write_docx", result));
    }
}
