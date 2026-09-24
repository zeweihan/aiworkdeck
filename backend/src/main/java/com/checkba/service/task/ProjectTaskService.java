// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.task;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectTask;
import com.checkba.model.entity.ProjectTaskFile;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectTaskFileRepository;
import com.checkba.repository.ProjectTaskRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.LangText;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.ProjectMemberService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 日历/任务系统的任务读写（dev-board #49，spec：
 * docs/superpowers/specs/2026-08-20-calendar-view-design.md）。
 *
 * 归属校验（任务所属项目是否为当前用户可访问项目、fileId 是否属于该项目）由调用方
 * （TaskController/CalendarController）在拿到 projectId 前完成或传入；本服务内部
 * 只做「fileId 若非空必须属于 projectId」这一条与业务数据强相关的校验，因为它需要
 * 联表查 ProjectFile，放在 controller 里会多一次重复查询。
 *
 * dev-board#895（spec：docs/superpowers/specs/2026-09-25-task-calendar-redesign.md 第一节）扩展：
 * type/priority/notes/assigneeId/remindBefore 五列 + project_task_file 多文件关联。
 * 这些字段的业务校验（枚举、负责人是项目成员、每个文件属于项目）同样落在本服务，
 * controller 与 AI 工具共用一份，不各写一遍。
 */
@Service
public class ProjectTaskService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_DONE = "DONE";
    private static final String SOURCE_USER = "user";
    private static final String SOURCE_AI = "ai";

    /** 事项类型枚举（dev-board#895），第一个是默认值。 */
    public static final List<String> TYPES = List.of("DEADLINE", "HEARING", "MEETING", "TODO", "OTHER");
    public static final String DEFAULT_TYPE = "DEADLINE";
    /** 优先级枚举，第一个是默认值。 */
    public static final List<String> PRIORITIES = List.of("NORMAL", "HIGH");
    public static final String DEFAULT_PRIORITY = "NORMAL";
    private static final int NOTES_MAX = 4000;

    private final ProjectTaskRepository taskRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectTaskFileRepository taskFileRepository;
    private final UserRepository userRepository;
    private final ProjectMemberService projectMemberService;

    public ProjectTaskService(ProjectTaskRepository taskRepository,
                              ProjectFileRepository projectFileRepository,
                              ProjectTaskFileRepository taskFileRepository,
                              UserRepository userRepository,
                              ProjectMemberService projectMemberService) {
        this.taskRepository = taskRepository;
        this.projectFileRepository = projectFileRepository;
        this.taskFileRepository = taskFileRepository;
        this.userRepository = userRepository;
        this.projectMemberService = projectMemberService;
    }

    /**
     * 创建事项的完整入参（dev-board#895）。title/dueDate 必填，其余可空：
     * type/priority 为 null 取默认；fileIds 为 null 或空表示不关联文件（顺序即关联顺序，重复自动去重）。
     */
    public record TaskDraft(String title, LocalDate dueDate, LocalTime dueTime,
                            String type, String priority, String notes,
                            Long assigneeId, Integer remindBefore, List<Long> fileIds) {

        /** 旧口径（只有单个 fileId）到新入参的桥。 */
        public static TaskDraft basic(String title, LocalDate dueDate, LocalTime dueTime, Long fileId) {
            return new TaskDraft(title, dueDate, dueTime, null, null, null, null, null,
                    fileId == null ? null : List.of(fileId));
        }
    }

    /** 创建任务。source 恒为 user，status 恒为 OPEN，uid 现场生成。 */
    @Transactional
    public ProjectTask createTask(Long projectId, Long fileId, String title, LocalDate dueDate, LocalTime dueTime, Long userId) {
        return createTask(projectId, TaskDraft.basic(title, dueDate, dueTime, fileId), userId);
    }

    /** 用户创建事项（完整字段，dev-board#895）。 */
    @Transactional
    public ProjectTask createTask(Long projectId, TaskDraft draft, Long userId) {
        return doCreate(projectId, draft, userId, SOURCE_USER);
    }

    /**
     * AI 创建任务：source 恒为 ai（界面据此与用户手动创建区分），其余规则与 {@link #createTask}
     * 完全一致。供 AI 编排器的 task_create 工具调用（dev-board #53）。
     */
    @Transactional
    public ProjectTask createAiTask(Long projectId, Long fileId, String title, LocalDate dueDate, LocalTime dueTime, Long userId) {
        return createAiTask(projectId, TaskDraft.basic(title, dueDate, dueTime, fileId), userId);
    }

    /** AI 创建事项（完整字段，dev-board#895）。 */
    @Transactional
    public ProjectTask createAiTask(Long projectId, TaskDraft draft, Long userId) {
        return doCreate(projectId, draft, userId, SOURCE_AI);
    }

    private ProjectTask doCreate(Long projectId, TaskDraft draft, Long userId, String source) {
        String title = draft.title();
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(LangText.of("标题不能为空", "Title must not be empty"));
        }
        if (draft.dueDate() == null) {
            throw new IllegalArgumentException(LangText.of("截止日不能为空", "Due date must not be empty"));
        }
        String type = normalizeType(draft.type());
        String priority = normalizePriority(draft.priority());
        String notes = normalizeNotes(draft.notes());
        validateAssignee(draft.assigneeId(), projectId);
        validateRemindBefore(draft.remindBefore());
        List<Long> fileIds = normalizeFileIds(draft.fileIds(), projectId);

        ProjectTask task = new ProjectTask();
        task.setUid(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setFileId(fileIds.isEmpty() ? null : fileIds.get(0));
        task.setTitle(title);
        task.setDueDate(draft.dueDate());
        task.setDueTime(draft.dueTime());
        task.setStatus(STATUS_OPEN);
        task.setSource(source);
        task.setUserId(userId);
        task.setType(type);
        task.setPriority(priority);
        task.setNotes(notes);
        task.setAssigneeId(draft.assigneeId());
        task.setRemindBefore(draft.remindBefore());
        ProjectTask saved = taskRepository.save(task);
        for (Long fid : fileIds) {
            taskFileRepository.save(new ProjectTaskFile(saved.getId(), fid));
        }
        return saved;
    }

    /**
     * 部分更新：title/dueDate/status 缺席时不动，显式传 null 视为非法（notNull 列）；
     * dueTime 缺席时不动，显式传 null 视为清空（回到全天事项）。
     * updates 用 containsKey 判「字段缺席」与「传 null」的区别。
     *
     * dev-board#895 新字段同一口径：type/priority 显式 null 回到默认值；notes/assigneeId/remindBefore
     * 显式 null 清空；fileIds 出现即整体替换关联集合（null 或 [] 清空），fileId 列随之改成新集合首个。
     */
    @Transactional
    public ProjectTask updateTask(Long taskId, Map<String, Object> updates) {
        ProjectTask task = getTask(taskId);

        if (updates.containsKey("title")) {
            Object v = updates.get("title");
            if (!(v instanceof String) || ((String) v).isBlank()) {
                throw new IllegalArgumentException(LangText.of("标题不能为空", "Title must not be empty"));
            }
            task.setTitle((String) v);
        }
        if (updates.containsKey("dueDate")) {
            Object v = updates.get("dueDate");
            if (v == null) {
                throw new IllegalArgumentException(LangText.of("截止日不能为空", "Due date must not be empty"));
            }
            task.setDueDate(parseLocalDate(v));
        }
        if (updates.containsKey("dueTime")) {
            Object v = updates.get("dueTime");
            task.setDueTime(v == null ? null : parseLocalTime(v));
        }
        if (updates.containsKey("status")) {
            Object v = updates.get("status");
            String status = v == null ? null : String.valueOf(v).toUpperCase();
            if (!STATUS_OPEN.equals(status) && !STATUS_DONE.equals(status)) {
                throw new IllegalArgumentException(LangText.of("status 只能是 OPEN 或 DONE", "status must be OPEN or DONE"));
            }
            task.setStatus(status);
        }
        if (updates.containsKey("type")) {
            task.setType(normalizeType(asText(updates.get("type"), "type")));
        }
        if (updates.containsKey("priority")) {
            task.setPriority(normalizePriority(asText(updates.get("priority"), "priority")));
        }
        if (updates.containsKey("notes")) {
            task.setNotes(normalizeNotes(asText(updates.get("notes"), "notes")));
        }
        if (updates.containsKey("assigneeId")) {
            Long assigneeId = asLong(updates.get("assigneeId"), "assigneeId");
            validateAssignee(assigneeId, task.getProjectId());
            task.setAssigneeId(assigneeId);
        }
        if (updates.containsKey("remindBefore")) {
            Integer remindBefore = asInteger(updates.get("remindBefore"), "remindBefore");
            validateRemindBefore(remindBefore);
            task.setRemindBefore(remindBefore);
        }
        if (updates.containsKey("fileIds")) {
            List<Long> fileIds = normalizeFileIds(asLongList(updates.get("fileIds")), task.getProjectId());
            taskFileRepository.deleteAllByTaskId(task.getId());
            for (Long fid : fileIds) {
                taskFileRepository.save(new ProjectTaskFile(task.getId(), fid));
            }
            task.setFileId(fileIds.isEmpty() ? null : fileIds.get(0));
        }
        return taskRepository.save(task);
    }

    /** 删事项时显式删关联行（不靠 JPA cascade，关联是裸 Long）。 */
    @Transactional
    public void deleteTask(Long taskId) {
        ProjectTask task = getTask(taskId);
        taskFileRepository.deleteAllByTaskId(task.getId());
        taskRepository.delete(task);
    }

    /** 归属校验用：拿到任务实体（含 projectId），不存在则抛错。 */
    public ProjectTask getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("任务不存在", "Task not found")));
    }

    /** 单项目任务列表（fileName 已 join，悬空/已删为 null），供 ProjectOverviewController /tasks 用。 */
    public List<Map<String, Object>> listByProject(Long projectId, LocalDate from, LocalDate to) {
        return listByProject(projectId, from, to, null);
    }

    /**
     * 同上，另可按文件过滤（dev-board#895）：fileId 非空时只返回旧列 file_id 或关联表命中该文件的事项。
     */
    public List<Map<String, Object>> listByProject(Long projectId, LocalDate from, LocalDate to, Long fileId) {
        List<ProjectTask> tasks = fileId == null
                ? taskRepository.findByProjectIdAndDueDateRange(projectId, from, to)
                : taskRepository.findByProjectIdAndFileIdAndDueDateRange(projectId, fileId, from, to);
        return toMaps(tasks);
    }

    /** 跨项目聚合（不含 projectName——由调用方按自己已有的 projectId→name 映射补上）。 */
    public List<Map<String, Object>> listAcrossProjects(List<Long> projectIds, LocalDate from, LocalDate to) {
        if (projectIds == null || projectIds.isEmpty()) {
            return List.of();
        }
        List<ProjectTask> tasks = taskRepository.findByProjectIdInAndDueDateRange(projectIds, from, to);
        return toMaps(tasks);
    }

    /** 单任务响应体（含 fileName）。create/update 端点用，字段集与列表端点完全一致。 */
    public Map<String, Object> toResponseMap(ProjectTask t) {
        return toMaps(List.of(t)).get(0);
    }

    /** 事项概览计数（dev-board#895），today 取服务器本地日期。 */
    public Map<String, Object> summarize(List<Long> projectIds) {
        return summarize(projectIds, LocalDate.now());
    }

    /**
     * 事项概览计数：只算 OPEN。
     * overdue = dueDate 早于今天；today = dueDate 是今天；week = 今天起 7 天内（含今天，即 today..today+6）；
     * nextDue = 按 dueDate/dueTime 排序后第一条 dueDate 不早于今天的事项 DTO，没有则 null。
     */
    public Map<String, Object> summarize(List<Long> projectIds, LocalDate today) {
        List<ProjectTask> open = (projectIds == null || projectIds.isEmpty())
                ? List.of()
                : taskRepository.findByProjectIdInAndStatus(projectIds, STATUS_OPEN);
        LocalDate weekEnd = today.plusDays(6);
        int overdue = 0;
        int todayCount = 0;
        int week = 0;
        ProjectTask next = null;
        for (ProjectTask t : open) {
            LocalDate d = t.getDueDate();
            if (d.isBefore(today)) {
                overdue++;
                continue;
            }
            if (d.isEqual(today)) {
                todayCount++;
            }
            if (!d.isAfter(weekEnd)) {
                week++;
            }
            if (next == null) {
                next = t;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overdue", overdue);
        m.put("today", todayCount);
        m.put("week", week);
        m.put("nextDue", next == null ? null : toResponseMap(next));
        return m;
    }

    private void validateFileInProject(Long fileId, Long projectId) {
        ProjectFile file = projectFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("文件不存在", "File not found")));
        if (!projectId.equals(file.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("文件不属于该项目", "This file does not belong to this project"));
        }
    }

    /**
     * 批量组 DTO：一次性取关联行、文件名、负责人名，避免逐条查询。
     * 文件名：悬空 id 或已软删的文件一律 fileName=null（沿用 #49 口径）。
     */
    private List<Map<String, Object>> toMaps(List<ProjectTask> tasks) {
        if (tasks.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, List<Long>> links = loadLinks(tasks);
        Set<Long> allFileIds = new LinkedHashSet<>();
        for (ProjectTask t : tasks) {
            allFileIds.addAll(fileIdsOf(t, links));
        }
        Map<Long, String> fileNames = loadFileNames(allFileIds);
        Map<Long, String> assigneeNames = loadUserNames(tasks.stream()
                .map(ProjectTask::getAssigneeId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<Map<String, Object>> result = new ArrayList<>(tasks.size());
        for (ProjectTask t : tasks) {
            result.add(toMap(t, fileIdsOf(t, links), fileNames, assigneeNames));
        }
        return result;
    }

    /** 事项的关联文件：关联表有行就用关联表；没有但旧列 fileId 非空（#895 之前建的事项）就当单文件。 */
    private List<Long> fileIdsOf(ProjectTask t, Map<Long, List<Long>> links) {
        List<Long> linked = links.get(t.getId());
        if (linked != null && !linked.isEmpty()) {
            return linked;
        }
        return t.getFileId() == null ? List.of() : List.of(t.getFileId());
    }

    private Map<Long, List<Long>> loadLinks(List<ProjectTask> tasks) {
        List<Long> taskIds = tasks.stream().map(ProjectTask::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> result = new HashMap<>();
        for (ProjectTaskFile link : taskFileRepository.findByTaskIdInOrderByIdAsc(taskIds)) {
            result.computeIfAbsent(link.getTaskId(), k -> new ArrayList<>()).add(link.getFileId());
        }
        return result;
    }

    /** 批量取 fileId→fileName，悬空 id 或已软删的文件一律不进 map（调用方据此落 null）。 */
    private Map<Long, String> loadFileNames(Collection<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        for (ProjectFile f : projectFileRepository.findAllById(fileIds)) {
            if (!Boolean.TRUE.equals(f.getIsDeleted())) {
                result.put(f.getId(), f.getName());
            }
        }
        return result;
    }

    /** 批量取 userId→展示名，口径同成员列表（LocalIdentityService.displayNameOf 本地化本机用户哨兵值）。 */
    private Map<Long, String> loadUserNames(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        for (User u : userRepository.findAllById(userIds)) {
            result.put(u.getId(), LocalIdentityService.displayNameOf(u.getDisplayName()));
        }
        return result;
    }

    private Map<String, Object> toMap(ProjectTask t, List<Long> fileIds,
                                      Map<Long, String> fileNames, Map<Long, String> assigneeNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("uid", t.getUid());
        m.put("projectId", t.getProjectId());
        m.put("title", t.getTitle());
        m.put("type", t.getType() == null ? DEFAULT_TYPE : t.getType());
        m.put("priority", t.getPriority() == null ? DEFAULT_PRIORITY : t.getPriority());
        m.put("notes", t.getNotes());
        m.put("dueDate", t.getDueDate());
        m.put("dueTime", t.getDueTime());
        m.put("status", t.getStatus());
        m.put("source", t.getSource());
        m.put("assigneeId", t.getAssigneeId());
        m.put("assigneeName", t.getAssigneeId() == null ? null : assigneeNames.get(t.getAssigneeId()));
        m.put("remindBefore", t.getRemindBefore());
        m.put("fileId", t.getFileId());
        m.put("fileName", t.getFileId() == null ? null : fileNames.get(t.getFileId()));
        List<Map<String, Object>> files = new ArrayList<>(fileIds.size());
        for (Long fid : fileIds) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("fileId", fid);
            f.put("fileName", fileNames.get(fid));
            files.add(f);
        }
        m.put("files", files);
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        return m;
    }

    // ==================== 新字段校验（dev-board#895） ====================

    /** null/空白 → 默认 DEADLINE；大小写不敏感；不在枚举内拒绝。 */
    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return DEFAULT_TYPE;
        }
        String up = type.trim().toUpperCase();
        if (!TYPES.contains(up)) {
            throw new IllegalArgumentException(LangText.of(
                    "type 只能是 " + String.join("/", TYPES), "type must be one of " + String.join("/", TYPES)));
        }
        return up;
    }

    /** null/空白 → 默认 NORMAL；大小写不敏感；不在枚举内拒绝。 */
    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return DEFAULT_PRIORITY;
        }
        String up = priority.trim().toUpperCase();
        if (!PRIORITIES.contains(up)) {
            throw new IllegalArgumentException(LangText.of(
                    "priority 只能是 NORMAL 或 HIGH", "priority must be NORMAL or HIGH"));
        }
        return up;
    }

    /** 空白备注存 null；超过列宽拒绝（否则落库时才炸成 500）。 */
    private String normalizeNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        if (notes.length() > NOTES_MAX) {
            throw new IllegalArgumentException(LangText.of(
                    "备注不能超过 " + NOTES_MAX + " 字", "Notes must not exceed " + NOTES_MAX + " characters"));
        }
        return notes;
    }

    /** 负责人必须是项目成员或 owner——直接复用 hasReadPermission 的判定（owner ∪ 成员表），不另造。 */
    private void validateAssignee(Long assigneeId, Long projectId) {
        if (assigneeId == null) {
            return;
        }
        if (!projectMemberService.hasReadPermission(projectId, assigneeId)) {
            throw new IllegalArgumentException(LangText.of(
                    "负责人不是该项目成员", "The assignee is not a member of this project"));
        }
    }

    private void validateRemindBefore(Integer remindBefore) {
        if (remindBefore != null && remindBefore < 0) {
            throw new IllegalArgumentException(LangText.of(
                    "remindBefore 不能为负数", "remindBefore must not be negative"));
        }
    }

    /** 去重保序，逐个校验属于项目；null 视为空集合。 */
    private List<Long> normalizeFileIds(List<Long> fileIds, Long projectId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(new LinkedHashSet<>(
                fileIds.stream().filter(Objects::nonNull).collect(Collectors.toList())));
        for (Long fid : result) {
            validateFileInProject(fid, projectId);
        }
        return result;
    }

    private String asText(Object v, String field) {
        if (v == null) return null;
        if (v instanceof String str) return str;
        throw new IllegalArgumentException(LangText.of(field + " 必须是文本", field + " must be text"));
    }

    private Long asLong(Object v, String field) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(LangText.of(field + " 必须是数字", field + " must be a number"));
        }
    }

    private Integer asInteger(Object v, String field) {
        Long l = asLong(v, field);
        if (l == null) return null;
        if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(LangText.of(field + " 超出范围", field + " is out of range"));
        }
        return l.intValue();
    }

    private List<Long> asLongList(Object v) {
        if (v == null) return List.of();
        if (!(v instanceof Collection<?> c)) {
            throw new IllegalArgumentException(LangText.of("fileIds 必须是数组", "fileIds must be an array"));
        }
        List<Long> result = new ArrayList<>(c.size());
        for (Object o : c) {
            Long l = asLong(o, "fileIds");
            if (l != null) result.add(l);
        }
        return result;
    }

    private LocalDate parseLocalDate(Object v) {
        try {
            return LocalDate.parse(String.valueOf(v));
        } catch (Exception e) {
            throw new IllegalArgumentException(LangText.of("dueDate 格式不合法", "dueDate has an invalid format"));
        }
    }

    private LocalTime parseLocalTime(Object v) {
        try {
            return LocalTime.parse(String.valueOf(v));
        } catch (Exception e) {
            throw new IllegalArgumentException(LangText.of("dueTime 格式不合法", "dueTime has an invalid format"));
        }
    }
}
