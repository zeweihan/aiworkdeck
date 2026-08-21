package com.checkba.controller;

import com.checkba.model.entity.ProjectTask;
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.task.ProjectTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * 任务 CRUD（dev-board #49，spec：docs/superpowers/specs/2026-08-20-calendar-view-design.md）。
 *
 * 列表端点不在这里：单项目列表留在 ProjectOverviewController 的
 * GET /api/projects/{projectId}/tasks（B 期换真实现，路径不迁移），跨项目聚合在
 * CalendarController 的 GET /api/calendar。这里只管创建/更新/删除。
 *
 * 鉴权照 ProjectOverviewController 惯例：X-Session-Id + AuthController.getUserIdFromSession，
 * 抛 IllegalArgumentException 让 GlobalExceptionHandler 统一转 HTTP 200 + {code,message}。
 * 写操作用写权限闸（hasWritePermission 且非 CLIENT），与 ProjectOverviewController.requireWrite
 * 同语义——task 是项目内的写操作，不是只读展示。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final ProjectTaskService taskService;
    private final ProjectMemberService projectMemberService;

    public TaskController(ProjectTaskService taskService, ProjectMemberService projectMemberService) {
        this.taskService = taskService;
        this.projectMemberService = projectMemberService;
    }

    /** 登录 + 写权限 + 拒 CLIENT，参数序恒为 (projectId, sessionId)，照 ProjectOverviewController.requireWrite。 */
    private Long requireWrite(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException(LangText.of("未登录", "Not signed in"));
        if (projectId == null
                || !projectMemberService.hasWritePermission(projectId, userId)
                || projectMemberService.isClient(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权修改该项目", "You don't have permission to modify this project"));
        }
        return userId;
    }

    /** body: {projectId, fileId?, title, dueDate, dueTime?} */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long projectId = toLong(body == null ? null : body.get("projectId"));
        Long userId = requireWrite(projectId, sessionId);

        Long fileId = toLong(body.get("fileId"));
        String title = toText(body.get("title"));
        LocalDate dueDate = toLocalDate(body.get("dueDate"));
        LocalTime dueTime = toLocalTime(body.get("dueTime"));

        ProjectTask task = taskService.createTask(projectId, fileId, title, dueDate, dueTime, userId);
        return ok(taskService.toResponseMap(task));
    }

    /** body: {title?, dueDate?, dueTime?, status?}——任意子集，缺席字段不动。 */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        // 归属校验：任务所在 projectId 须属于当前用户可写的项目，先查任务再判权限，防越权改他人项目任务
        ProjectTask existing = taskService.getTask(id);
        requireWrite(existing.getProjectId(), sessionId);

        ProjectTask updated = taskService.updateTask(id, body == null ? Map.of() : body);
        return ok(taskService.toResponseMap(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        ProjectTask existing = taskService.getTask(id);
        requireWrite(existing.getProjectId(), sessionId);

        taskService.deleteTask(id);
        return ok(Map.of("deleted", true));
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }

    /**
     * 裸强转 {@code (String)} 传进来一个数字就抛 ClassCastException——那不是
     * IllegalArgumentException，绕过 GlobalExceptionHandler 那条能给出人话的分支，
     * 落到兜底处理器变成「服务器内部错误」，还给一条普通的参数校验失败打了 ERROR 堆栈。
     * 调用方看不出真正的原因只是 title 类型不对。
     */
    private String toText(Object v) {
        if (v == null) return null;
        if (v instanceof String str) return str;
        throw new IllegalArgumentException(LangText.of("标题必须是文本", "The title must be text"));
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        return Long.parseLong(s);
    }

    private LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        try {
            return LocalDate.parse(String.valueOf(v));
        } catch (Exception e) {
            throw new IllegalArgumentException(LangText.of("dueDate 格式不合法", "dueDate has an invalid format"));
        }
    }

    private LocalTime toLocalTime(Object v) {
        if (v == null) return null;
        try {
            return LocalTime.parse(String.valueOf(v));
        } catch (Exception e) {
            throw new IllegalArgumentException(LangText.of("dueTime 格式不合法", "dueTime has an invalid format"));
        }
    }
}
