package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ai.context.ProjectContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 AI 工具的项目边界：fileId 是 LLM 自由填写的参数，
 * 不能凭它直接跨项目取文件（提示注入可驱使模型点名他人项目的文件）。
 */
class ToolFileGuardTest {

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private static ProjectFile fileInProject(Long projectId) {
        ProjectFile f = new ProjectFile();
        f.setId(50L);
        f.setProjectId(projectId);
        return f;
    }

    @Test
    void rejectsFileFromAnotherProject() {
        ProjectContextHolder.setProjectId("1");
        assertNotNull(ToolFileGuard.rejectIfOutsideProject(fileInProject(999L)));
    }

    @Test
    void allowsFileInCurrentProject() {
        ProjectContextHolder.setProjectId("1");
        assertNull(ToolFileGuard.rejectIfOutsideProject(fileInProject(1L)));
    }

    @Test
    void failsClosedWithoutProjectContext() {
        // 没有项目上下文就无从判断归属，必须拒绝而不是放行
        assertNotNull(ToolFileGuard.rejectIfOutsideProject(fileInProject(1L)));
    }

    @Test
    void leavesMissingFileToCaller() {
        // 「文件不存在」由调用方自己的分支给文案，这里不抢
        ProjectContextHolder.setProjectId("1");
        assertNull(ToolFileGuard.rejectIfOutsideProject(null));
    }
}
