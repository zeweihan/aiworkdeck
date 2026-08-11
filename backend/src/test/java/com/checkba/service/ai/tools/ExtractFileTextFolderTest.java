package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ai.context.ProjectContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 传进来一个文件夹时 extract_file_text 的行为。
 *
 * <p>背景：以前撞上文件夹只返回 {@code Error: File is a folder}，模型无路可走——
 * 用户在诉讼可视化里把卷宗文件夹当材料范围交进来，表现就是「给它文件夹它不认识」。
 * 现在直接把文件夹内容答出来。这组用例钉住那条出路不许再退回死错误。
 */
class ExtractFileTextFolderTest {

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private static ProjectFile folder(long id, long projectId, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(projectId);
        f.setName(name);
        f.setFileType("folder");
        f.setIsFolder(true);
        return f;
    }

    private static ProjectFile file(long id, long projectId, String name, String type) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(projectId);
        f.setName(name);
        f.setFileType(type);
        f.setIsFolder(false);
        return f;
    }

    /** 只接线本用例需要的两个依赖，其余传 null——这条分支在碰到它们之前就返回了。 */
    private static FileTools toolsWith(ProjectFileRepository repo) {
        return new FileTools(null, repo, null, null, null, null, null);
    }

    @Test
    @DisplayName("文件夹返回子项清单，而不是死错误")
    void listsChildrenInsteadOfDeadEnd() {
        ProjectContextHolder.setProjectId("7");
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        ProjectFile dir = folder(100L, 7L, "一审卷宗");
        Mockito.when(repo.findById(100L)).thenReturn(Optional.of(dir));
        Mockito.when(repo.findByProjectIdAndParentIdAndIsDeletedFalseOrderBySortOrderAsc(7L, 100L))
                .thenReturn(List.of(
                        file(101L, 7L, "起诉状.docx", "docx"),
                        file(102L, 7L, "证据目录.pdf", "pdf"),
                        folder(103L, 7L, "补充证据")));

        String out = toolsWith(repo).extract_file_text(100L);

        assertFalse(out.startsWith("Error"), "文件夹不该再是错误出口：" + out);
        assertTrue(out.contains("起诉状.docx"), "要列出子文件");
        assertTrue(out.contains("证据目录.pdf"), "非 Office 文件同样要列（旧的指路方案会漏掉 PDF）");
        assertTrue(out.contains("id=101"), "必须给出 id，否则模型没法接着读");
        assertTrue(out.contains("[文件夹]"), "子文件夹要标出来，模型才知道可以再往下一层");
    }

    @Test
    @DisplayName("空文件夹说清楚是空的，不报错")
    void emptyFolderIsNotAnError() {
        ProjectContextHolder.setProjectId("7");
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        Mockito.when(repo.findById(200L)).thenReturn(Optional.of(folder(200L, 7L, "空目录")));
        Mockito.when(repo.findByProjectIdAndParentIdAndIsDeletedFalseOrderBySortOrderAsc(7L, 200L))
                .thenReturn(List.of());

        String out = toolsWith(repo).extract_file_text(200L);

        assertFalse(out.startsWith("Error"));
        assertTrue(out.contains("空"), "要说清楚里面没东西：" + out);
    }

    @Test
    @DisplayName("跨项目的文件夹仍被项目边界挡住")
    void stillRejectsFolderFromAnotherProject() {
        // 列目录是新出路，但不能顺手绕开项目边界——fileId 是 LLM 自由填的参数
        ProjectContextHolder.setProjectId("1");
        ProjectFileRepository repo = Mockito.mock(ProjectFileRepository.class);
        Mockito.when(repo.findById(300L)).thenReturn(Optional.of(folder(300L, 999L, "别人的卷宗")));

        String out = toolsWith(repo).extract_file_text(300L);

        assertTrue(out.startsWith("Error"), "跨项目必须拒绝：" + out);
        assertFalse(out.contains("别人的卷宗"), "拒绝时不该回显他人项目的文件夹名");
    }
}
