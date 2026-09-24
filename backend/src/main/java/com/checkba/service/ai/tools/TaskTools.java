// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectTask;
import com.checkba.service.task.ProjectTaskService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 项目级日程任务工具（dev-board #53，日历/任务系统 AI 接线，
 * spec：docs/superpowers/specs/2026-08-20-calendar-view-design.md）。
 *
 * 术语边界：本工具管理的是项目级「任务/日程」——截止日、开庭日这类跨对话持续存在、
 * 用户在日历页也看得到的里程碑；不要与 todo_write 管理的 AI 单次工作「进度」步骤条
 * 混淆——那是本轮工作结束即失效的临时清单，不落 project_task 表。
 *
 * projectId/userId 由 ToolRegistry 按服务端上下文强制注入（SERVER_CONTEXT_PARAMS），
 * LLM 传入的同名值会被忽略，防止跨项目越权。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskTools implements AgentToolComponent {

    private final ProjectTaskService projectTaskService;

    @Tool("为当前项目创建一条事项（截止日、开庭、会议、待办等），创建后出现在项目日程与事项列表中供用户跟踪，并标记为 AI 建议。" +
          "type 取值：DEADLINE=截止日（默认，如举证期限、上诉期、提交材料截止）、HEARING=开庭、MEETING=会议/约谈、TODO=待办、OTHER=其他。" +
          "不要用于规划 AI 自己本轮工作的步骤——那用 todo_write，两者不要混用。")
    @ToolMeta(displayName = "创建日程任务", category = "task")
    public String task_create(
            @P("事项标题，简洁描述这是什么事项") String title,
            @P("日期，ISO 格式 yyyy-MM-dd（截止日/开庭日/会议日）") String dueDate,
            @P(value = "具体时刻，HH:mm 24小时制（如开庭 09:30）；不填表示全天事项", required = false) String dueTime,
            @P(value = "关联的单个项目内文件ID（旧参数，可选）；多个文件请用 fileIds", required = false) Long fileId,
            @P(value = "事项类型：DEADLINE 截止日（默认）/ HEARING 开庭 / MEETING 会议 / TODO 待办 / OTHER 其他", required = false) String type,
            @P(value = "备注纯文本（可选），如案号、法庭、需准备的材料", required = false) String notes,
            @P(value = "优先级：NORMAL 普通（默认）/ HIGH 重要", required = false) String priority,
            @P(value = "关联的项目内文件ID，多个用英文逗号分隔（如 12,15），须来自 doc_list_project_files 等工具返回的 fileId", required = false) String fileIds,
            @P(value = "负责人用户ID（可选），必须是本项目成员", required = false) Long assigneeId,
            @P(value = "提前多少分钟提醒（可选）：0=准时，60=提前1小时，1440=提前1天；不填表示不提醒", required = false) Integer remindBefore,
            Long projectId,
            Long userId
    ) {
        log.info("Tool: task_create title='{}' dueDate={} dueTime={} type={} fileId={} fileIds={} project={}",
                title, dueDate, dueTime, type, fileId, fileIds, projectId);

        if (projectId == null) {
            return "错误：无法获取当前项目ID，请在项目上下文中使用此工具。";
        }
        if (userId == null) {
            return "错误：无法获取当前用户身份，任务未创建。";
        }
        LocalDate parsedDueDate = parseDate(dueDate);
        if (parsedDueDate == null) {
            return "错误：dueDate 缺失或格式不合法，须为 ISO 日期 yyyy-MM-dd。";
        }
        LocalTime parsedDueTime = null;
        if (dueTime != null && !dueTime.isBlank()) {
            parsedDueTime = parseTime(dueTime);
            if (parsedDueTime == null) {
                return "错误：dueTime 格式不合法，须为 HH:mm（24小时制）。";
            }
        }
        List<Long> parsedFileIds = new ArrayList<>();
        if (fileId != null) {
            parsedFileIds.add(fileId);
        }
        if (fileIds != null && !fileIds.isBlank()) {
            for (String part : fileIds.split("[,，\\s]+")) {
                if (part.isBlank()) continue;
                try {
                    parsedFileIds.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException e) {
                    return "错误：fileIds 格式不合法，须为逗号分隔的数字文件ID，如 12,15。";
                }
            }
        }

        try {
            ProjectTaskService.TaskDraft draft = new ProjectTaskService.TaskDraft(
                    title, parsedDueDate, parsedDueTime, type, priority, notes, assigneeId, remindBefore,
                    parsedFileIds.isEmpty() ? null : parsedFileIds);
            ProjectTask task = projectTaskService.createAiTask(projectId, draft, userId);
            StringBuilder sb = new StringBuilder("已创建").append(typeLabel(task.getType())).append("事项：");
            sb.append(task.getTitle()).append("（id=").append(task.getId())
                    .append("，日期 ").append(task.getDueDate());
            if (task.getDueTime() != null) {
                sb.append(' ').append(task.getDueTime());
            }
            sb.append("）。");
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("task_create failed", e);
            return "错误：创建任务失败，" + e.getMessage();
        }
    }

    @Tool("修改当前项目里已有的一条事项：改日期/时刻（如“把开庭改到下周三”）、改标题、改类型/备注/优先级，或标记完成（status=DONE）/重新打开（status=OPEN）。" +
          "taskId 从 task_list 的输出里取（每行的 id=）。只传要改的字段，没传的保持不变。" +
          "这是项目日程里的事项，不是 todo_write 的本轮进度步骤，两者不要混用。")
    @ToolMeta(displayName = "修改日程任务", category = "task")
    public String task_update(
            @P("要修改的事项ID，来自 task_list 输出的 id=") Long taskId,
            @P(value = "新标题（可选）", required = false) String title,
            @P(value = "新日期，ISO 格式 yyyy-MM-dd（可选）", required = false) String dueDate,
            @P(value = "新时刻，HH:mm 24小时制（可选）", required = false) String dueTime,
            @P(value = "状态：OPEN 未完成 / DONE 已完成（可选）", required = false) String status,
            @P(value = "类型：DEADLINE 截止日 / HEARING 开庭 / MEETING 会议 / TODO 待办 / OTHER 其他（可选）", required = false) String type,
            @P(value = "新备注（可选，整体替换原备注）", required = false) String notes,
            @P(value = "优先级：NORMAL 普通 / HIGH 重要（可选）", required = false) String priority,
            Long projectId
    ) {
        log.info("Tool: task_update taskId={} project={}", taskId, projectId);

        if (projectId == null) {
            return "错误：无法获取当前项目ID，请在项目上下文中使用此工具。";
        }
        if (taskId == null) {
            return "错误：缺少 taskId，请先用 task_list 查到事项的 id。";
        }
        Map<String, Object> updates = new HashMap<>();
        if (title != null && !title.isBlank()) updates.put("title", title);
        if (dueDate != null && !dueDate.isBlank()) {
            if (parseDate(dueDate) == null) {
                return "错误：dueDate 格式不合法，须为 ISO 日期 yyyy-MM-dd。";
            }
            updates.put("dueDate", dueDate.trim());
        }
        if (dueTime != null && !dueTime.isBlank()) {
            if (parseTime(dueTime) == null) {
                return "错误：dueTime 格式不合法，须为 HH:mm（24小时制）。";
            }
            updates.put("dueTime", dueTime.trim());
        }
        if (status != null && !status.isBlank()) updates.put("status", status.trim());
        if (type != null && !type.isBlank()) updates.put("type", type.trim());
        if (notes != null && !notes.isBlank()) updates.put("notes", notes);
        if (priority != null && !priority.isBlank()) updates.put("priority", priority.trim());
        if (updates.isEmpty()) {
            return "错误：没有要修改的字段，请至少传一个要改的值。";
        }

        try {
            // 归属校验：事项必须属于当前项目（projectId 由服务端上下文注入），防止跨项目改别人的事项
            ProjectTask existing = projectTaskService.getTask(taskId);
            if (!projectId.equals(existing.getProjectId())) {
                return "错误：该事项不属于当前项目，无法修改。";
            }
            ProjectTask t = projectTaskService.updateTask(taskId, updates);
            StringBuilder sb = new StringBuilder("已更新事项：");
            sb.append(t.getTitle()).append("（id=").append(t.getId())
                    .append("，").append(typeLabel(t.getType()))
                    .append("，日期 ").append(t.getDueDate());
            if (t.getDueTime() != null) {
                sb.append(' ').append(t.getDueTime());
            }
            sb.append("，").append("DONE".equals(t.getStatus()) ? "已完成" : "未完成").append("）。");
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("task_update failed", e);
            return "错误：修改事项失败，" + e.getMessage();
        }
    }

    @Tool("查询当前项目的事项列表（截止日、开庭、会议、待办等），可选按日期区间过滤。" +
          "每行含事项 id、类型、标题、时间、状态、负责人与关联文件。" +
          "用于回答“有哪些截止日”“下周有什么安排”“这个项目的任务清单”类问题，也用于给 task_update 找 taskId。")
    @ToolMeta(displayName = "查询日程任务", category = "task")
    public String task_list(
            @P(value = "起始日期（含），ISO 格式 yyyy-MM-dd；不填表示不限起始", required = false) String from,
            @P(value = "结束日期（含），ISO 格式 yyyy-MM-dd；不填表示不限结束", required = false) String to,
            Long projectId
    ) {
        log.info("Tool: task_list project={} from={} to={}", projectId, from, to);

        if (projectId == null) {
            return "错误：无法获取当前项目ID，请在项目上下文中使用此工具。";
        }
        LocalDate fromDate = null;
        if (from != null && !from.isBlank()) {
            fromDate = parseDate(from);
            if (fromDate == null) {
                return "错误：from 格式不合法，须为 ISO 日期 yyyy-MM-dd。";
            }
        }
        LocalDate toDate = null;
        if (to != null && !to.isBlank()) {
            toDate = parseDate(to);
            if (toDate == null) {
                return "错误：to 格式不合法，须为 ISO 日期 yyyy-MM-dd。";
            }
        }

        List<Map<String, Object>> tasks = projectTaskService.listByProject(projectId, fromDate, toDate);
        if (tasks.isEmpty()) {
            // 空列表必须给明确文案，不能返回空字符串——空白工具输出会掀翻整轮对话
            // （ToolExecutionResultMessage.ensureNotBlank，见 .claude/agents/ai-chat.md 已知地雷）。
            return "当前项目在该区间内暂无日程任务。";
        }

        StringBuilder sb = new StringBuilder("共 ").append(tasks.size()).append(" 项日程任务：\n");
        for (Map<String, Object> t : tasks) {
            StringJoiner line = new StringJoiner(" | ");
            line.add("id=" + t.get("id"));
            line.add(typeLabel(t.get("type") == null ? null : String.valueOf(t.get("type"))));
            String title = String.valueOf(t.get("title"));
            line.add("HIGH".equals(t.get("priority")) ? title + "（重要）" : title);
            Object dueDate = t.get("dueDate");
            Object dueTime = t.get("dueTime");
            line.add("时间 " + dueDate + (dueTime != null ? " " + dueTime : "（全天）"));
            line.add(String.valueOf(t.get("status")));
            Object assigneeName = t.get("assigneeName");
            if (assigneeName != null) {
                line.add("负责人：" + assigneeName);
            }
            List<String> fileNames = fileNamesOf(t);
            if (!fileNames.isEmpty()) {
                line.add("文件：" + String.join("、", fileNames));
            }
            sb.append("- ").append(line).append('\n');
        }
        return sb.toString();
    }

    /** 类型中文名；null/未知按默认截止日。 */
    static String typeLabel(String type) {
        if (type == null) {
            return "截止日";
        }
        return switch (type) {
            case "HEARING" -> "开庭";
            case "MEETING" -> "会议";
            case "TODO" -> "待办";
            case "OTHER" -> "其他";
            default -> "截止日";
        };
    }

    /** 关联文件名：优先 files[]（多文件），没有再退回旧 fileName；悬空文件（名字为 null）不列。 */
    private List<String> fileNamesOf(Map<String, Object> t) {
        List<String> names = new ArrayList<>();
        Object files = t.get("files");
        if (files instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> f && f.get("fileName") != null) {
                    names.add(String.valueOf(f.get("fileName")));
                }
            }
        }
        if (names.isEmpty() && t.get("fileName") != null) {
            names.add(String.valueOf(t.get("fileName")));
        }
        return names;
    }

    private LocalDate parseDate(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalTime parseTime(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(v.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
