package com.checkba.controller;

import com.checkba.model.entity.Project;
import com.checkba.service.ProjectService;
import com.checkba.service.task.ProjectTaskService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局日历页数据源（dev-board #49，spec：
 * docs/superpowers/specs/2026-08-20-calendar-view-design.md）。
 *
 * GET /api/calendar?from=&to= → 当前用户可见的全部项目的任务聚合，不新建表。
 * 「用户可见项目」复用 ProjectController /my 同一套判定
 * （ProjectService.getUserProjects：创建的项目 ∪ 成员项目），不自造过滤逻辑。
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final ProjectService projectService;
    private final ProjectTaskService taskService;

    public CalendarController(ProjectService projectService, ProjectTaskService taskService) {
        this.projectService = projectService;
        this.taskService = taskService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");

        List<Project> projects = projectService.getUserProjects(userId);
        Map<Long, String> projectNames = new HashMap<>();
        for (Project p : projects) {
            projectNames.put(p.getId(), p.getName());
        }

        List<Map<String, Object>> tasks = taskService.listAcrossProjects(
                List.copyOf(projectNames.keySet()), from, to);
        for (Map<String, Object> task : tasks) {
            task.put("projectName", projectNames.get(task.get("projectId")));
        }
        return ok(Map.of("tasks", tasks));
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }
}
