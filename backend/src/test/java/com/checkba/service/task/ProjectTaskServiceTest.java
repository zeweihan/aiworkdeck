package com.checkba.service.task;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectTask;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 日历/任务系统的任务读写（dev-board #49，spec：
 * docs/superpowers/specs/2026-08-20-calendar-view-design.md）契约：
 * - 创建恒 source=user/status=OPEN，uid 现场生成；
 * - 部分更新用 containsKey 判「字段缺席」与「显式传 null」——只有 dueTime 允许显式清空；
 * - fileId 非空必须属于同一 projectId（IDOR 围栏）；
 * - fileId 悬空或指向已软删的 ProjectFile 时 fileName=null，任务本身照常返回；
 * - 跨项目聚合按 dueDate asc / dueTime asc nulls first 排序。
 *
 * 内存 H2（MODE=PostgreSQL）约定同 MobileRelayStoreServiceTest。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-task-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectTaskServiceTest {

    @Autowired
    private ProjectTaskRepository taskRepository;
    @Autowired
    private ProjectFileRepository fileRepository;

    private ProjectTaskService service;

    @BeforeEach
    void setUp() {
        service = new ProjectTaskService(taskRepository, fileRepository);
    }

    private ProjectFile saveFile(Long projectId, String name, boolean deleted) {
        ProjectFile f = new ProjectFile();
        f.setProjectId(projectId);
        f.setIsFolder(false);
        f.setName(name);
        f.setSortOrder(0);
        f.setUserId(1L);
        f.setIsDeleted(deleted);
        return fileRepository.save(f);
    }

    @Test
    @DisplayName("创建：source=user/status=OPEN 恒定，uid 现场生成")
    void createSetsDefaults() {
        ProjectTask t = service.createTask(1L, null, "起诉状截止", LocalDate.of(2026, 9, 1), LocalTime.of(9, 30), 10L);

        assertNotNull(t.getId());
        assertNotNull(t.getUid());
        assertFalse(t.getUid().isBlank());
        assertEquals("user", t.getSource());
        assertEquals("OPEN", t.getStatus());
        assertEquals(1L, t.getProjectId());
        assertEquals(10L, t.getUserId());
    }

    @Test
    @DisplayName("创建：fileId 非空但不属于该项目——拒绝（IDOR 围栏）")
    void createRejectsFileFromAnotherProject() {
        ProjectFile foreignFile = saveFile(2L, "别的项目的文件.docx", false);

        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(1L, foreignFile.getId(), "任务", LocalDate.of(2026, 9, 1), null, 10L));
    }

    @Test
    @DisplayName("创建：fileId 属于该项目——通过并可被 join 出 fileName")
    void createAcceptsFileFromSameProject() {
        ProjectFile file = saveFile(1L, "起诉状.docx", false);
        ProjectTask t = service.createTask(1L, file.getId(), "任务", LocalDate.of(2026, 9, 1), null, 10L);

        List<Map<String, Object>> tasks = service.listByProject(1L, null, null);
        assertEquals(1, tasks.size());
        assertEquals("起诉状.docx", tasks.get(0).get("fileName"));
        assertEquals(t.getId(), tasks.get(0).get("id"));
    }

    @Test
    @DisplayName("部分更新：只传 title 不动 dueDate/status；dueTime 显式传 null 清空为全天")
    void partialUpdateRespectsFieldPresence() {
        ProjectTask t = service.createTask(1L, null, "旧标题", LocalDate.of(2026, 9, 1), LocalTime.of(9, 30), 10L);

        Map<String, Object> onlyTitle = new HashMap<>();
        onlyTitle.put("title", "新标题");
        ProjectTask afterTitle = service.updateTask(t.getId(), onlyTitle);
        assertEquals("新标题", afterTitle.getTitle());
        assertEquals(LocalDate.of(2026, 9, 1), afterTitle.getDueDate());
        assertEquals(LocalTime.of(9, 30), afterTitle.getDueTime());
        assertEquals("OPEN", afterTitle.getStatus());

        Map<String, Object> clearDueTime = new HashMap<>();
        clearDueTime.put("dueTime", null); // HashMap.put(k,null) still registers the key for containsKey
        ProjectTask afterClear = service.updateTask(t.getId(), clearDueTime);
        assertNull(afterClear.getDueTime());
        assertEquals("新标题", afterClear.getTitle(), "未传的字段不应被清掉");

        Map<String, Object> markDone = new HashMap<>();
        markDone.put("status", "done"); // 小写输入也应规整为大写
        ProjectTask afterDone = service.updateTask(t.getId(), markDone);
        assertEquals("DONE", afterDone.getStatus());
    }

    @Test
    @DisplayName("部分更新：dueDate 显式传 null 拒绝——not null 列不能被清空")
    void partialUpdateRejectsNullDueDate() {
        ProjectTask t = service.createTask(1L, null, "任务", LocalDate.of(2026, 9, 1), null, 10L);
        Map<String, Object> body = new HashMap<>();
        body.put("dueDate", null);
        assertThrows(IllegalArgumentException.class, () -> service.updateTask(t.getId(), body));
    }

    @Test
    @DisplayName("删除：删除后再查不到")
    void deleteRemovesTask() {
        ProjectTask t = service.createTask(1L, null, "任务", LocalDate.of(2026, 9, 1), null, 10L);
        service.deleteTask(t.getId());
        assertThrows(IllegalArgumentException.class, () -> service.getTask(t.getId()));
    }

    @Test
    @DisplayName("fileName 容错：fileId 悬空或指向已软删文件——fileName=null，任务本身照常返回")
    void listToleratesDanglingOrDeletedFile() {
        ProjectFile deletedFile = saveFile(1L, "已删除的文件.docx", true);
        service.createTask(1L, deletedFile.getId(), "锚定已删文件的任务", LocalDate.of(2026, 9, 1), null, 10L);

        List<Map<String, Object>> tasks = service.listByProject(1L, null, null);
        assertEquals(1, tasks.size());
        assertNotNull(tasks.get(0).get("fileId"));
        assertNull(tasks.get(0).get("fileName"));
    }

    @Test
    @DisplayName("跨项目聚合：按 dueDate asc / dueTime asc nulls first 排序")
    void listAcrossProjectsIsOrderedByDueDateThenDueTimeNullsFirst() {
        service.createTask(1L, null, "9-2 有具体时刻", LocalDate.of(2026, 9, 2), LocalTime.of(14, 0), 10L);
        service.createTask(2L, null, "9-1 全天", LocalDate.of(2026, 9, 1), null, 10L);
        service.createTask(1L, null, "9-1 上午", LocalDate.of(2026, 9, 1), LocalTime.of(9, 0), 10L);

        List<Map<String, Object>> tasks = service.listAcrossProjects(List.of(1L, 2L), null, null);
        assertEquals(3, tasks.size());
        assertEquals("9-1 全天", tasks.get(0).get("title"), "同日全天事项（dueTime=null）排在有具体时刻的前面");
        assertEquals("9-1 上午", tasks.get(1).get("title"));
        assertEquals("9-2 有具体时刻", tasks.get(2).get("title"));
    }

    @Test
    @DisplayName("跨项目聚合：区间过滤 from/to")
    void listAcrossProjectsFiltersByRange() {
        service.createTask(1L, null, "区间外", LocalDate.of(2026, 8, 1), null, 10L);
        service.createTask(1L, null, "区间内", LocalDate.of(2026, 9, 15), null, 10L);

        List<Map<String, Object>> tasks = service.listAcrossProjects(
                List.of(1L), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertEquals(1, tasks.size());
        assertEquals("区间内", tasks.get(0).get("title"));
    }

    @Test
    @DisplayName("跨项目聚合：projectIds 为空直接返回空列表，不查库")
    void listAcrossProjectsEmptyIdsShortCircuits() {
        assertTrue(service.listAcrossProjects(List.of(), null, null).isEmpty());
    }
}
