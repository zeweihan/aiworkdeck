// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.task;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectTask;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectTaskFileRepository;
import com.checkba.repository.ProjectTaskRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.ProjectMemberService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日历/任务系统的任务读写（dev-board #49，spec：
 * docs/superpowers/specs/2026-08-20-calendar-view-design.md）契约：
 * - 创建恒 source=user/status=OPEN，uid 现场生成；
 * - 部分更新用 containsKey 判「字段缺席」与「显式传 null」——只有 dueTime 允许显式清空；
 * - fileId 非空必须属于同一 projectId（IDOR 围栏）；
 * - fileId 悬空或指向已软删的 ProjectFile 时 fileName=null，任务本身照常返回；
 * - 跨项目聚合按 dueDate asc / dueTime asc nulls first 排序。
 * dev-board#895 追加：
 * - type/priority 枚举校验与默认值（旧行 null 在 DTO 里输出默认值）；
 * - 负责人必须是项目成员（ProjectMemberService.hasReadPermission，这里 mock）；
 * - fileIds 多文件关联：fileId 列 = 首个，PUT 出现即整体替换，删事项连带删关联；
 * - fileId 过滤命中旧列或关联表；summary 计数。
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
    @Autowired
    private ProjectTaskFileRepository taskFileRepository;
    @Autowired
    private UserRepository userRepository;

    private ProjectMemberService memberService;
    private ProjectTaskService service;

    @BeforeEach
    void setUp() {
        memberService = mock(ProjectMemberService.class);
        service = new ProjectTaskService(taskRepository, fileRepository, taskFileRepository, userRepository, memberService);
    }

    private User saveUser(String username, String displayName) {
        User u = new User();
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setPassword("x");
        return userRepository.save(u);
    }

    private ProjectTaskService.TaskDraft draft(String title, String type, String priority, String notes,
                                               Long assigneeId, Integer remindBefore, List<Long> fileIds) {
        return new ProjectTaskService.TaskDraft(title, LocalDate.of(2026, 10, 12), LocalTime.of(9, 30),
                type, priority, notes, assigneeId, remindBefore, fileIds);
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

    // ==================== dev-board#895 ====================

    @Test
    @DisplayName("#895 创建：新字段全量落库，DTO 输出 type/priority/notes/assignee/remindBefore/files/fileId=首个")
    void createWithAllNewFields() {
        User lawyer = saveUser("lawyer-a", "韩泽伟");
        when(memberService.hasReadPermission(1L, lawyer.getId())).thenReturn(true);
        ProjectFile a = saveFile(1L, "起诉状.docx", false);
        ProjectFile b = saveFile(1L, "证据清单.xlsx", false);

        ProjectTask t = service.createTask(1L, draft("开庭", "hearing", "HIGH", "带原件 @韩泽伟",
                lawyer.getId(), 1440, List.of(a.getId(), b.getId(), a.getId())), 10L);

        assertEquals("HEARING", t.getType(), "大小写不敏感，落库规整为大写");
        assertEquals(a.getId(), t.getFileId(), "fileId 列 = 关联集合首个");
        Map<String, Object> dto = service.toResponseMap(t);
        assertEquals("HEARING", dto.get("type"));
        assertEquals("HIGH", dto.get("priority"));
        assertEquals("带原件 @韩泽伟", dto.get("notes"));
        assertEquals(lawyer.getId(), dto.get("assigneeId"));
        assertEquals("韩泽伟", dto.get("assigneeName"));
        assertEquals(1440, dto.get("remindBefore"));
        assertEquals("起诉状.docx", dto.get("fileName"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) dto.get("files");
        assertEquals(2, files.size(), "重复 fileId 去重");
        assertEquals(a.getId(), files.get(0).get("fileId"));
        assertEquals("证据清单.xlsx", files.get(1).get("fileName"));
    }

    @Test
    @DisplayName("#895 默认值：未传 type/priority 落默认；旧行 type/priority 为 null 时 DTO 输出默认值而不是 null")
    void defaultsForMissingAndLegacyRows() {
        ProjectTask t = service.createTask(1L, null, "新建", LocalDate.of(2026, 9, 1), null, 10L);
        assertEquals("DEADLINE", t.getType());
        assertEquals("NORMAL", t.getPriority());

        ProjectTask legacy = new ProjectTask();
        legacy.setUid("legacy");
        legacy.setProjectId(1L);
        legacy.setTitle("旧行");
        legacy.setDueDate(LocalDate.of(2026, 9, 2));
        legacy.setStatus("OPEN");
        legacy.setSource("user");
        legacy.setUserId(10L);
        taskRepository.save(legacy);

        Map<String, Object> dto = service.listByProject(1L, null, null).stream()
                .filter(m -> "旧行".equals(m.get("title"))).findFirst().orElseThrow();
        assertEquals("DEADLINE", dto.get("type"));
        assertEquals("NORMAL", dto.get("priority"));
        assertEquals(List.of(), dto.get("files"));
        assertNull(dto.get("assigneeName"));
    }

    @Test
    @DisplayName("#895 旧行只有 file_id 列没有关联行：files[] 回落为单文件")
    void legacyFileIdColumnShowsAsSingleFile() {
        ProjectFile f = saveFile(1L, "旧文件.docx", false);
        ProjectTask legacy = new ProjectTask();
        legacy.setUid("legacy-f");
        legacy.setProjectId(1L);
        legacy.setFileId(f.getId());
        legacy.setTitle("旧行带文件");
        legacy.setDueDate(LocalDate.of(2026, 9, 2));
        legacy.setStatus("OPEN");
        legacy.setSource("user");
        legacy.setUserId(10L);
        taskRepository.save(legacy);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) service.toResponseMap(legacy).get("files");
        assertEquals(1, files.size());
        assertEquals("旧文件.docx", files.get(0).get("fileName"));
    }

    @Test
    @DisplayName("#895 校验：type/priority 不在枚举、负责人非成员、fileIds 含外项目文件、remindBefore 负数——一律拒绝")
    void createRejectsInvalidNewFields() {
        ProjectFile foreign = saveFile(2L, "外项目.docx", false);
        when(memberService.hasReadPermission(1L, 99L)).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(1L, draft("x", "PARTY", null, null, null, null, null), 10L));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(1L, draft("x", null, "URGENT", null, null, null, null), 10L));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(1L, draft("x", null, null, null, 99L, null, null), 10L));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(1L, draft("x", null, null, null, null, null, List.of(foreign.getId())), 10L));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(1L, draft("x", null, null, null, null, -5, null), 10L));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTask(1L, draft("x", null, null, "长".repeat(4001), null, null, null), 10L));
        assertTrue(taskRepository.findAll().isEmpty(), "校验失败不能落半条事项");
    }

    @Test
    @DisplayName("#895 更新：fileIds 出现即整体替换（含重新加入已有文件，不撞唯一索引），fileId 列跟着变；null 清空")
    void updateReplacesFileIds() {
        ProjectFile a = saveFile(1L, "a.docx", false);
        ProjectFile b = saveFile(1L, "b.docx", false);
        ProjectFile c = saveFile(1L, "c.docx", false);
        ProjectTask t = service.createTask(1L, draft("x", null, null, null, null, null, List.of(a.getId(), b.getId())), 10L);

        Map<String, Object> body = new HashMap<>();
        body.put("fileIds", List.of(c.getId(), b.getId()));
        ProjectTask after = service.updateTask(t.getId(), body);
        taskRepository.flush();
        assertEquals(c.getId(), after.getFileId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) service.toResponseMap(after).get("files");
        assertEquals(List.of(c.getId(), b.getId()), files.stream().map(f -> f.get("fileId")).toList());

        Map<String, Object> title = new HashMap<>();
        title.put("title", "只改标题");
        service.updateTask(t.getId(), title);
        assertEquals(2, taskFileRepository.findByTaskIdInOrderByIdAsc(List.of(t.getId())).size(),
                "fileIds 缺席时关联集合不动");

        Map<String, Object> clear = new HashMap<>();
        clear.put("fileIds", null);
        ProjectTask cleared = service.updateTask(t.getId(), clear);
        assertNull(cleared.getFileId());
        assertTrue(taskFileRepository.findByTaskIdInOrderByIdAsc(List.of(t.getId())).isEmpty());
    }

    @Test
    @DisplayName("#895 更新：显式 null 清空 notes/assigneeId/remindBefore，type 显式 null 回默认；非法值拒绝")
    void updateNewFieldsRespectPresence() {
        when(memberService.hasReadPermission(1L, 5L)).thenReturn(true);
        ProjectTask t = service.createTask(1L, draft("x", "MEETING", "HIGH", "备注", 5L, 60, null), 10L);

        Map<String, Object> clear = new HashMap<>();
        clear.put("notes", null);
        clear.put("assigneeId", null);
        clear.put("remindBefore", null);
        clear.put("type", null);
        ProjectTask after = service.updateTask(t.getId(), clear);
        assertNull(after.getNotes());
        assertNull(after.getAssigneeId());
        assertNull(after.getRemindBefore());
        assertEquals("DEADLINE", after.getType());
        assertEquals("HIGH", after.getPriority(), "未传的字段不动");

        Map<String, Object> badType = new HashMap<>();
        badType.put("type", "PARTY");
        assertThrows(IllegalArgumentException.class, () -> service.updateTask(t.getId(), badType));

        Map<String, Object> badAssignee = new HashMap<>();
        badAssignee.put("assigneeId", 77);
        assertThrows(IllegalArgumentException.class, () -> service.updateTask(t.getId(), badAssignee));
    }

    @Test
    @DisplayName("#895 删除：连带删关联行")
    void deleteRemovesLinks() {
        ProjectFile a = saveFile(1L, "a.docx", false);
        ProjectTask t = service.createTask(1L, draft("x", null, null, null, null, null, List.of(a.getId())), 10L);
        service.deleteTask(t.getId());
        assertTrue(taskFileRepository.findByTaskIdInOrderByIdAsc(List.of(t.getId())).isEmpty());
    }

    @Test
    @DisplayName("#895 fileId 过滤：旧列 file_id 命中或关联表命中（非首个文件）都返回，其余不返回")
    void listByProjectFiltersByFile() {
        ProjectFile a = saveFile(1L, "a.docx", false);
        ProjectFile b = saveFile(1L, "b.docx", false);
        service.createTask(1L, draft("首个是a", null, null, null, null, null, List.of(a.getId(), b.getId())), 10L);
        service.createTask(1L, draft("只关联b", null, null, null, null, null, List.of(b.getId())), 10L);
        service.createTask(1L, draft("无文件", null, null, null, null, null, null), 10L);

        List<Object> byB = service.listByProject(1L, null, null, b.getId()).stream().map(m -> m.get("title")).toList();
        assertEquals(2, byB.size());
        assertTrue(byB.containsAll(List.of("首个是a", "只关联b")));
        List<Object> byA = service.listByProject(1L, null, null, a.getId()).stream().map(m -> m.get("title")).toList();
        assertEquals(List.of("首个是a"), byA);
    }

    @Test
    @DisplayName("#895 summary：只算 OPEN；逾期/今天/本周（今天起 7 天含今天）计数；nextDue 为最近一条今天及以后的事项")
    void summarizeCounts() {
        LocalDate today = LocalDate.of(2026, 9, 25);
        service.createTask(1L, null, "逾期", today.minusDays(3), null, 10L);
        service.createTask(1L, null, "今天下午", today, LocalTime.of(15, 0), 10L);
        service.createTask(2L, null, "今天全天", today, null, 10L);
        service.createTask(1L, null, "第7天", today.plusDays(6), null, 10L);
        service.createTask(1L, null, "第8天", today.plusDays(7), null, 10L);
        ProjectTask done = service.createTask(1L, null, "已完成今天", today, null, 10L);
        Map<String, Object> markDone = new HashMap<>();
        markDone.put("status", "DONE");
        service.updateTask(done.getId(), markDone);
        service.createTask(3L, null, "不可见项目", today, null, 10L);

        Map<String, Object> sum = service.summarize(List.of(1L, 2L), today);
        assertEquals(1, sum.get("overdue"));
        assertEquals(2, sum.get("today"));
        assertEquals(3, sum.get("week"));
        @SuppressWarnings("unchecked")
        Map<String, Object> next = (Map<String, Object>) sum.get("nextDue");
        assertEquals("今天全天", next.get("title"), "同日全天排在有时刻的前面");

        Map<String, Object> empty = service.summarize(List.of(), today);
        assertEquals(0, empty.get("overdue"));
        assertNull(empty.get("nextDue"));
    }
}
