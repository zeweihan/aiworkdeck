package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.version.WorkSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 纯文本直读直写原语（dev-board#37）：txt/md 不再进 LOWA，AI 改这类文件走
 * text_write_file / text_find_replace（后端 StorageService 读写 + 版本信号 + 前端刷新）。
 * 这组用例钉住四件事：命中替换真的落盘、未命中不动文件、非文本格式必须拒绝、
 * 写入成功后版本信号（onChangeSignal）与前端刷新（text_reload_file）必须触发。
 */
class TextFileEditToolsTest {

    private ProjectFileRepository repo;
    private StorageService storage;
    private WorkSessionService workSessions;
    private EditorBridgeService bridge;
    private TextFileEditTools tools;

    @BeforeEach
    void setUp() {
        ProjectContextHolder.setProjectId("7");
        repo = Mockito.mock(ProjectFileRepository.class);
        storage = Mockito.mock(StorageService.class);
        StorageServiceFactory factory = Mockito.mock(StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(storage);
        workSessions = Mockito.mock(WorkSessionService.class);
        bridge = Mockito.mock(EditorBridgeService.class);
        tools = new TextFileEditTools(repo, factory, workSessions, bridge, null);
    }

    @AfterEach
    void tearDown() {
        ProjectContextHolder.clear();
    }

    private ProjectFile textFile(long id, String name, String type) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(7L);
        f.setName(name);
        f.setFileType(type);
        f.setIsFolder(false);
        f.setFilePath("projects/7/" + name);
        return f;
    }

    private void stubContent(ProjectFile pf, String content) throws Exception {
        when(storage.load(pf.getFilePath()))
                .thenReturn(new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
    }

    private String savedContent(ProjectFile pf) throws Exception {
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(storage).save(eq(pf.getFilePath()), captor.capture());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        captor.getValue().transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("find_replace 命中：替换全部并落盘，触发版本信号与前端刷新")
    void findReplaceHitWritesBackAndSignals() throws Exception {
        ProjectFile pf = textFile(11L, "备忘.txt", "txt");
        when(repo.findById(11L)).thenReturn(Optional.of(pf));
        stubContent(pf, "甲方是A公司，甲方负责付款。");

        String out = tools.text_find_replace(11L, "甲方", "乙方", true);

        assertFalse(out.startsWith("Error"), out);
        assertTrue(out.contains("2"), "要报出命中次数：" + out);
        assertEquals("乙方是A公司，乙方负责付款。", savedContent(pf));
        verify(workSessions).onChangeSignal(eq(7L), any(), anyString());
        verify(bridge).sendTextReloadFileAction(pf);
    }

    @Test
    @DisplayName("find_replace replaceAll=false 只替换第一处")
    void findReplaceFirstOnly() throws Exception {
        ProjectFile pf = textFile(12L, "notes.md", "md");
        when(repo.findById(12L)).thenReturn(Optional.of(pf));
        stubContent(pf, "todo one, todo two");

        String out = tools.text_find_replace(12L, "todo", "done", false);

        assertFalse(out.startsWith("Error"), out);
        assertEquals("done one, todo two", savedContent(pf));
    }

    @Test
    @DisplayName("find_replace 未命中：不写盘、不发信号，说清楚没找到")
    void findReplaceMissTouchesNothing() throws Exception {
        ProjectFile pf = textFile(13L, "备忘.txt", "txt");
        when(repo.findById(13L)).thenReturn(Optional.of(pf));
        stubContent(pf, "没有目标词的内容");

        String out = tools.text_find_replace(13L, "丙方", "丁方", true);

        assertTrue(out.contains("未找到"), out);
        verify(storage, never()).save(anyString(), any());
        verify(workSessions, never()).onChangeSignal(anyLong(), any(), anyString());
        verify(bridge, never()).sendTextReloadFileAction(any());
    }

    @Test
    @DisplayName("非纯文本格式（docx）必须拒绝并指对路")
    void rejectsNonTextFile() {
        ProjectFile pf = textFile(14L, "合同.docx", "docx");
        when(repo.findById(14L)).thenReturn(Optional.of(pf));

        String w = tools.text_write_file(14L, "x");
        String r = tools.text_find_replace(14L, "a", "b", true);

        assertTrue(w.startsWith("Error"), w);
        assertTrue(w.contains("doc_"), "拒绝时要把 doc_* 的正路指出来：" + w);
        assertTrue(r.startsWith("Error"), r);
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("text_write_file 整篇覆盖：落盘 + 回写大小 + 信号 + 刷新")
    void writeFileOverwritesAndSignals() throws Exception {
        ProjectFile pf = textFile(15L, "README.md", "md");
        when(repo.findById(15L)).thenReturn(Optional.of(pf));

        String out = tools.text_write_file(15L, "# 新内容\n正文");

        assertFalse(out.startsWith("Error"), out);
        assertEquals("# 新内容\n正文", savedContent(pf));
        assertEquals("# 新内容\n正文".getBytes(StandardCharsets.UTF_8).length, pf.getFileSize());
        verify(repo).save(pf);
        verify(workSessions).onChangeSignal(eq(7L), any(), anyString());
        verify(bridge).sendTextReloadFileAction(pf);
    }

    @Test
    @DisplayName("isPlainText：代码文件（js/json/html）放行，docx 仍拒绝（dev-board#61 插件开发形态扩容）")
    void isPlainTextCoversCodeFilesButNotOffice() {
        assertTrue(TextFileEditTools.isPlainText(textFile(20L, "index.js", "js")));
        assertTrue(TextFileEditTools.isPlainText(textFile(21L, "manifest.json", "json")));
        assertTrue(TextFileEditTools.isPlainText(textFile(22L, "panel.html", "html")));
        assertFalse(TextFileEditTools.isPlainText(textFile(23L, "合同.docx", "docx")));
    }

    @Test
    @DisplayName("跨项目文件被项目边界挡住")
    void rejectsFileFromAnotherProject() {
        ProjectFile pf = textFile(16L, "别人的.txt", "txt");
        pf.setProjectId(999L);
        when(repo.findById(16L)).thenReturn(Optional.of(pf));

        String out = tools.text_write_file(16L, "x");

        assertTrue(out.startsWith("Error"), out);
        verifyNoInteractions(storage);
    }
}
