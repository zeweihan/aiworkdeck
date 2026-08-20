package com.checkba.service.task;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectTask;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectTaskRepository;
import com.checkba.service.LangText;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
@Service
public class ProjectTaskService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_DONE = "DONE";
    private static final String SOURCE_USER = "user";
    private static final String SOURCE_AI = "ai";

    private final ProjectTaskRepository taskRepository;
    private final ProjectFileRepository projectFileRepository;

    public ProjectTaskService(ProjectTaskRepository taskRepository, ProjectFileRepository projectFileRepository) {
        this.taskRepository = taskRepository;
        this.projectFileRepository = projectFileRepository;
    }

    /** 创建任务。source 恒为 user，status 恒为 OPEN，uid 现场生成。 */
    public ProjectTask createTask(Long projectId, Long fileId, String title, LocalDate dueDate, LocalTime dueTime, Long userId) {
        return createTask(projectId, fileId, title, dueDate, dueTime, userId, SOURCE_USER);
    }

    /**
     * AI 创建任务：source 恒为 ai（界面据此与用户手动创建区分），其余规则与 {@link #createTask}
     * 完全一致。供 AI 编排器的 task_create 工具调用（dev-board #53）。
     */
    public ProjectTask createAiTask(Long projectId, Long fileId, String title, LocalDate dueDate, LocalTime dueTime, Long userId) {
        return createTask(projectId, fileId, title, dueDate, dueTime, userId, SOURCE_AI);
    }

    private ProjectTask createTask(Long projectId, Long fileId, String title, LocalDate dueDate, LocalTime dueTime, Long userId, String source) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(LangText.of("标题不能为空", "Title must not be empty"));
        }
        if (dueDate == null) {
            throw new IllegalArgumentException(LangText.of("截止日不能为空", "Due date must not be empty"));
        }
        if (fileId != null) {
            validateFileInProject(fileId, projectId);
        }

        ProjectTask task = new ProjectTask();
        task.setUid(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setFileId(fileId);
        task.setTitle(title);
        task.setDueDate(dueDate);
        task.setDueTime(dueTime);
        task.setStatus(STATUS_OPEN);
        task.setSource(source);
        task.setUserId(userId);
        return taskRepository.save(task);
    }

    /**
     * 部分更新：title/dueDate/status 缺席时不动，显式传 null 视为非法（notNull 列）；
     * dueTime 缺席时不动，显式传 null 视为清空（回到全天事项）。
     * updates 用 containsKey 判「字段缺席」与「传 null」的区别。
     */
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
        return taskRepository.save(task);
    }

    public void deleteTask(Long taskId) {
        ProjectTask task = getTask(taskId);
        taskRepository.delete(task);
    }

    /** 归属校验用：拿到任务实体（含 projectId），不存在则抛错。 */
    public ProjectTask getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("任务不存在", "Task not found")));
    }

    /** 单项目任务列表（fileName 已 join，悬空/已删为 null），供 ProjectOverviewController /tasks 用。 */
    public List<Map<String, Object>> listByProject(Long projectId, LocalDate from, LocalDate to) {
        List<ProjectTask> tasks = taskRepository.findByProjectIdAndDueDateRange(projectId, from, to);
        Map<Long, String> fileNames = loadFileNames(tasks);
        return tasks.stream().map(t -> toMap(t, fileNames)).collect(Collectors.toList());
    }

    /** 跨项目聚合（不含 projectName——由调用方按自己已有的 projectId→name 映射补上）。 */
    public List<Map<String, Object>> listAcrossProjects(List<Long> projectIds, LocalDate from, LocalDate to) {
        if (projectIds == null || projectIds.isEmpty()) {
            return List.of();
        }
        List<ProjectTask> tasks = taskRepository.findByProjectIdInAndDueDateRange(projectIds, from, to);
        Map<Long, String> fileNames = loadFileNames(tasks);
        return tasks.stream().map(t -> toMap(t, fileNames)).collect(Collectors.toList());
    }

    /** 单任务响应体（含 fileName）。create/update 端点用，字段集与列表端点完全一致。 */
    public Map<String, Object> toResponseMap(ProjectTask t) {
        return toMap(t, loadFileNames(List.of(t)));
    }

    private void validateFileInProject(Long fileId, Long projectId) {
        ProjectFile file = projectFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("文件不存在", "File not found")));
        if (!projectId.equals(file.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("文件不属于该项目", "This file does not belong to this project"));
        }
    }

    /** 批量取 fileId→fileName，悬空 id 或已软删的文件一律不进 map（调用方据此落 null）。 */
    private Map<Long, String> loadFileNames(List<ProjectTask> tasks) {
        List<Long> fileIds = tasks.stream()
                .map(ProjectTask::getFileId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
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

    private Map<String, Object> toMap(ProjectTask t, Map<Long, String> fileNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("uid", t.getUid());
        m.put("projectId", t.getProjectId());
        m.put("fileId", t.getFileId());
        m.put("fileName", t.getFileId() == null ? null : fileNames.get(t.getFileId()));
        m.put("title", t.getTitle());
        m.put("dueDate", t.getDueDate());
        m.put("dueTime", t.getDueTime());
        m.put("status", t.getStatus());
        m.put("source", t.getSource());
        return m;
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
