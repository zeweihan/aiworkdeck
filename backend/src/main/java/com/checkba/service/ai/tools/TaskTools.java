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

    @Tool("为当前项目创建一条截止日/日程任务（如提交材料截止日、开庭日期、尽调节点）。" +
          "创建后会出现在项目日历与任务列表中供用户跟踪，并标记为 AI 建议。" +
          "不要用于规划 AI 自己本轮工作的步骤——那用 todo_write。")
    @ToolMeta(displayName = "创建日程任务", category = "task")
    public String task_create(
            @P("任务标题，简洁描述这是什么事项") String title,
            @P("截止日，ISO 格式 yyyy-MM-dd") String dueDate,
            @P(value = "具体时刻，HH:mm 24小时制（如开庭时间）；不填表示全天事项", required = false) String dueTime,
            @P(value = "关联的项目内文件ID（可选），须来自 doc_list_project_files 等工具返回的 fileId", required = false) Long fileId,
            Long projectId,
            Long userId
    ) {
        log.info("Tool: task_create title='{}' dueDate={} dueTime={} fileId={} project={}",
                title, dueDate, dueTime, fileId, projectId);

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

        try {
            ProjectTask task = projectTaskService.createAiTask(
                    projectId, fileId, title, parsedDueDate, parsedDueTime, userId);
            StringBuilder sb = new StringBuilder("已创建日程任务：");
            sb.append(task.getTitle()).append("（id=").append(task.getId())
                    .append("，截止日 ").append(task.getDueDate());
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

    @Tool("查询当前项目的日程任务列表（截止日、开庭日等），可选按截止日区间过滤。" +
          "用于回答“有哪些截止日”“下周有什么安排”“这个项目的任务清单”类问题。")
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
            line.add(String.valueOf(t.get("title")));
            Object dueDate = t.get("dueDate");
            Object dueTime = t.get("dueTime");
            line.add("截止 " + dueDate + (dueTime != null ? " " + dueTime : ""));
            line.add(String.valueOf(t.get("status")));
            Object fileName = t.get("fileName");
            if (fileName != null) {
                line.add("文件：" + fileName);
            }
            sb.append("- ").append(line).append('\n');
        }
        return sb.toString();
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
