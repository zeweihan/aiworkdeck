package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectTask;
import com.checkba.service.task.ProjectTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * task_create / task_list（dev-board #53）单测。
 *
 * ProjectTaskService 是 mock：本类只验证 TaskTools 这一层的契约——参数解析、
 * 服务端上下文（projectId/userId）如何传给 service、异常如何转成可行动的错误文案、
 * 以及空列表绝不返回空字符串（工具空输出会掀翻整轮对话，见 ToolExecutionResultMessage
 * .ensureNotBlank 那条已知地雷）。source=ai/fileId 归属校验 IDOR 围栏本身的行为由
 * ProjectTaskServiceTest 覆盖，这里只验证 IllegalArgumentException 被正确回喂。
 */
class TaskToolsTest {

    private ProjectTaskService taskService;
    private TaskTools tools;

    @BeforeEach
    void setUp() {
        taskService = mock(ProjectTaskService.class);
        tools = new TaskTools(taskService);
    }

    private ProjectTask task(long id, String title, LocalDate dueDate, LocalTime dueTime) {
        ProjectTask t = new ProjectTask();
        t.setId(id);
        t.setTitle(title);
        t.setDueDate(dueDate);
        t.setDueTime(dueTime);
        return t;
    }

    @Test
    @DisplayName("task_create：创建成功，走 createAiTask（source=ai），返回摘要含 id/title/dueDate")
    void createSucceeds() {
        when(taskService.createAiTask(eq(1L), isNull(), eq("提交答辩状"),
                eq(LocalDate.of(2026, 9, 1)), eq(LocalTime.of(9, 30)), eq(10L)))
                .thenReturn(task(5L, "提交答辩状", LocalDate.of(2026, 9, 1), LocalTime.of(9, 30)));

        String out = tools.task_create("提交答辩状", "2026-09-01", "09:30", null, 1L, 10L);

        assertTrue(out.contains("提交答辩状"), out);
        assertTrue(out.contains("id=5"), out);
        assertTrue(out.contains("2026-09-01"), out);
        assertTrue(out.contains("09:30"), out);
        verify(taskService).createAiTask(1L, null, "提交答辩状",
                LocalDate.of(2026, 9, 1), LocalTime.of(9, 30), 10L);
    }

    @Test
    @DisplayName("task_create：dueDate 缺失/格式非法——不调用 service，返回可行动错误")
    void createRejectsBadDueDate() {
        String out = tools.task_create("任务", "not-a-date", null, null, 1L, 10L);

        assertTrue(out.startsWith("错误"), out);
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("task_create：fileId 越权——service 抛出的 IllegalArgumentException 转成可行动错误文案（非空白）")
    void createRejectsFileFromAnotherProject() {
        when(taskService.createAiTask(eq(1L), eq(99L), any(), any(), any(), eq(10L)))
                .thenThrow(new IllegalArgumentException("文件不属于该项目"));

        String out = tools.task_create("任务", "2026-09-01", null, 99L, 1L, 10L);

        assertFalse(out.isBlank());
        assertTrue(out.contains("文件不属于该项目"), out);
    }

    @Test
    @DisplayName("task_list：空列表返回明确文案，绝不是空字符串")
    void listEmptyReturnsExplicitText() {
        when(taskService.listByProject(1L, null, null)).thenReturn(List.of());

        String out = tools.task_list(null, null, 1L);

        assertFalse(out.isBlank(), "空工具输出会掀翻整轮对话（ensureNotBlank），必须给明确文案");
        assertTrue(out.contains("暂无"), out);
    }

    @Test
    @DisplayName("task_list：非空列表格式化为紧凑行，含 title/dueDate/dueTime/status/fileName")
    void listNonEmptyFormatsRows() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "提交答辩状");
        row.put("dueDate", LocalDate.of(2026, 9, 1));
        row.put("dueTime", LocalTime.of(9, 30));
        row.put("status", "OPEN");
        row.put("fileName", "答辩状.docx");
        when(taskService.listByProject(1L, null, null)).thenReturn(List.of(row));

        String out = tools.task_list(null, null, 1L);

        assertTrue(out.contains("提交答辩状"), out);
        assertTrue(out.contains("2026-09-01"), out);
        assertTrue(out.contains("09:30"), out);
        assertTrue(out.contains("OPEN"), out);
        assertTrue(out.contains("答辩状.docx"), out);
    }

    @Test
    @DisplayName("task_list：projectId 缺失时返回可行动错误，不调用 service")
    void listRejectsMissingProjectId() {
        String out = tools.task_list(null, null, null);

        assertTrue(out.startsWith("错误"), out);
        verifyNoInteractions(taskService);
    }
}
