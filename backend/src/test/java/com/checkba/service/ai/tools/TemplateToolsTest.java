package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.StyleProfileResolver;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.util.style.StyleProfile;
import com.checkba.util.style.StyleProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docx_inspect_template：归属围栏、.doc 提示不报错、正常 docx 产出画像 JSON、多份合并。
 */
class TemplateToolsTest {

    @AfterEach
    void clear() {
        ProjectContextHolder.clear();
    }

    private static ProjectFile file(long id, long projectId, String name, String type) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(projectId);
        f.setName(name);
        f.setFileType(type);
        f.setFilePath("projects/" + projectId + "/" + name);
        f.setIsFolder(false);
        return f;
    }

    private static TemplateTools tools(ProjectFileRepository repo) {
        StorageService storage = Mockito.mock(StorageService.class);
        Mockito.when(storage.load(Mockito.anyString()))
                .thenReturn(new ClassPathResource("fixtures/template-sample.docx"));
        StorageServiceFactory factory = Mockito.mock(StorageServiceFactory.class);
        Mockito.when(factory.getStorageService()).thenReturn(storage);
        return new TemplateTools(repo, factory, null);
    }

    private static ProjectFile folder(long id, long projectId, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(projectId);
        f.setName(name);
        f.setIsFolder(true);
        f.setFileType("folder");
        return f;
    }

    @Test
    @DisplayName("学完自己落盘 _模板/画像.json：建夹 + 建文件 + 存字节，返回带 savedProfileFileId")
    void savesProfileIntoProject() {
        ProjectContextHolder.setProjectId("7");
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        Mockito.when(repo.findById(1L)).thenReturn(Optional.of(file(1L, 7L, "模板.docx", "docx")));
        Mockito.when(repo.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(7L, 90L, StyleProfileResolver.PROFILE_FILE))
                .thenReturn(Optional.empty());
        ProjectFileService pfs = Mockito.mock(ProjectFileService.class);
        Mockito.when(pfs.ensureFolderPath(7L, 10001L, List.of(StyleProfileResolver.TEMPLATE_FOLDER)))
                .thenReturn(folder(90L, 7L, StyleProfileResolver.TEMPLATE_FOLDER));
        Mockito.when(pfs.createFile(Mockito.eq(7L), Mockito.eq(90L), Mockito.eq(StyleProfileResolver.PROFILE_FILE),
                        Mockito.eq("json"), Mockito.anyLong(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(10001L)))
                .thenReturn(file(91L, 7L, StyleProfileResolver.PROFILE_FILE, "json"));

        StorageService storage = Mockito.mock(StorageService.class);
        Mockito.when(storage.load(Mockito.anyString())).thenReturn(new ClassPathResource("fixtures/template-sample.docx"));
        StorageServiceFactory factory = Mockito.mock(StorageServiceFactory.class);
        Mockito.when(factory.getStorageService()).thenReturn(storage);

        String out = new TemplateTools(repo, factory, pfs).docx_inspect_template("1", null);
        StyleProfile p = StyleProfiles.parse(out);
        assertEquals(91L, p.root().get("savedProfileFileId").asLong(), out);
        assertEquals("_模板/画像.json", p.root().get("savedProfilePath").asText());
        assertFalse(p.root().has("saveError"), out);

        org.mockito.ArgumentCaptor<InputStream> bytes = org.mockito.ArgumentCaptor.forClass(InputStream.class);
        Mockito.verify(storage).save(Mockito.eq("projects/7/画像.json"), bytes.capture());
        String written = new String(readAll(bytes.getValue()), StandardCharsets.UTF_8);
        // 存的是干净画像本身：解析得回来，且不含只给模型看的落盘回执字段
        StyleProfile reparsed = StyleProfiles.parse(written);
        assertEquals("楷体_GB2312", reparsed.body().font().eastAsia());
        assertFalse(written.contains("savedProfileFileId"), written.substring(0, Math.min(200, written.length())));
    }

    @Test
    @DisplayName("已有画像：就地覆盖同一个 fileId，不生成「画像 (1).json」")
    void overwritesExistingProfile() {
        ProjectContextHolder.setProjectId("7");
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        Mockito.when(repo.findById(1L)).thenReturn(Optional.of(file(1L, 7L, "模板.docx", "docx")));
        ProjectFile old = file(55L, 7L, StyleProfileResolver.PROFILE_FILE, "json");
        Mockito.when(repo.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(7L, 90L, StyleProfileResolver.PROFILE_FILE))
                .thenReturn(Optional.of(old));
        ProjectFileService pfs = Mockito.mock(ProjectFileService.class);
        Mockito.when(pfs.ensureFolderPath(7L, 10001L, List.of(StyleProfileResolver.TEMPLATE_FOLDER)))
                .thenReturn(folder(90L, 7L, StyleProfileResolver.TEMPLATE_FOLDER));

        StorageService storage = Mockito.mock(StorageService.class);
        Mockito.when(storage.load(Mockito.anyString())).thenReturn(new ClassPathResource("fixtures/template-sample.docx"));
        StorageServiceFactory factory = Mockito.mock(StorageServiceFactory.class);
        Mockito.when(factory.getStorageService()).thenReturn(storage);

        String out = new TemplateTools(repo, factory, pfs).docx_inspect_template("1", null);
        assertEquals(55L, StyleProfiles.parse(out).root().get("savedProfileFileId").asLong());
        Mockito.verify(pfs, Mockito.never()).createFile(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.anyLong());
        Mockito.verify(storage).save(Mockito.eq(old.getFilePath()), Mockito.any(InputStream.class));
    }

    @Test
    @DisplayName("落盘失败：画像照样返回，但带上让模型自己 write_file 的 saveError")
    void saveFailureIsVisible() {
        ProjectContextHolder.setProjectId("7");
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        Mockito.when(repo.findById(1L)).thenReturn(Optional.of(file(1L, 7L, "模板.docx", "docx")));
        ProjectFileService pfs = Mockito.mock(ProjectFileService.class);
        Mockito.when(pfs.ensureFolderPath(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList()))
                .thenThrow(new IllegalStateException("磁盘满"));

        StorageService storage = Mockito.mock(StorageService.class);
        Mockito.when(storage.load(Mockito.anyString())).thenReturn(new ClassPathResource("fixtures/template-sample.docx"));
        StorageServiceFactory factory = Mockito.mock(StorageServiceFactory.class);
        Mockito.when(factory.getStorageService()).thenReturn(storage);

        String out = new TemplateTools(repo, factory, pfs).docx_inspect_template("1", null);
        StyleProfile p = StyleProfiles.parse(out);
        assertTrue(p.root().get("saveError").asText().contains("磁盘满"), out);
        assertTrue(p.root().get("saveError").asText().contains("write_file"), out);
        assertEquals("楷体_GB2312", p.body().font().eastAsia());
    }

    private static byte[] readAll(InputStream in) {
        try (InputStream i = in) {
            return i.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("正常 docx：返回 styleProfile JSON，关键字段与 fixture 一致")
    void learnsProfile() {
        ProjectContextHolder.setProjectId("7");
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        Mockito.when(repo.findById(1L)).thenReturn(Optional.of(file(1L, 7L, "模板.docx", "docx")));

        String out = tools(repo).docx_inspect_template("1", "{\"name\":\"测试模板\"}");
        assertFalse(out.startsWith("Error"), out);
        StyleProfile p = StyleProfiles.parse(out);
        assertEquals("测试模板", p.name());
        assertEquals("楷体_GB2312", p.body().font().eastAsia());
        assertEquals("auto", p.heading(1).numbering().string("kind"));
        assertEquals("cell", p.table().sub("borders").string("source"));
        assertEquals(1, p.root().get("learnedFrom").size());
        assertEquals(1L, p.root().get("learnedFrom").get(0).get("fileId").asLong());
    }

    @Test
    @DisplayName(".doc 老格式：不报错，提示另存 docx 或编辑器学习")
    void docFormatGivesHint() {
        ProjectContextHolder.setProjectId("7");
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        Mockito.when(repo.findById(2L)).thenReturn(Optional.of(file(2L, 7L, "旧模板.doc", "doc")));

        String out = tools(repo).docx_inspect_template("2", null);
        assertFalse(out.startsWith("Error"), out);
        assertTrue(out.contains("另存为 .docx"), out);
        assertTrue(out.contains("编辑器打开后再学习"), out);
    }

    @Test
    @DisplayName("多份其中一份是 .doc：跳过并提示，其余照常学习")
    void mixedDocAndDocx() {
        ProjectContextHolder.setProjectId("7");
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        Mockito.when(repo.findById(1L)).thenReturn(Optional.of(file(1L, 7L, "模板.docx", "docx")));
        Mockito.when(repo.findById(2L)).thenReturn(Optional.of(file(2L, 7L, "旧模板.doc", "doc")));

        String out = tools(repo).docx_inspect_template("1, 2", null);
        assertTrue(out.startsWith("「旧模板.doc」"), out);
        String json = out.substring(out.indexOf('{'));
        StyleProfile p = StyleProfiles.parse(json);
        assertEquals("楷体_GB2312", p.body().font().eastAsia());
    }

    @Test
    @DisplayName("跨项目文件被围栏拒绝")
    void rejectsOtherProject() {
        ProjectContextHolder.setProjectId("7");
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        Mockito.when(repo.findById(9L)).thenReturn(Optional.of(file(9L, 8L, "别人的.docx", "docx")));

        String out = tools(repo).docx_inspect_template("9", null);
        assertTrue(out.startsWith("Error"), out);
    }

    @Test
    @DisplayName("fileIds 解析：逗号/中文逗号/空格/方括号")
    void parsesIds() {
        assertEquals(List.of(1L, 2L, 3L), TemplateTools.parseIds("1, 2，3"));
        assertEquals(List.of(12L, 34L), TemplateTools.parseIds("[12,34]"));
        assertTrue(TemplateTools.parseIds("").isEmpty());
        assertTrue(TemplateTools.parseIds(null).isEmpty());
    }
}
