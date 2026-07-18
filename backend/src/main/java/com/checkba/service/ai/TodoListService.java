package com.checkba.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 任务清单服务（Cursor todo_write / Claude Code TodoWrite 模式）。
 *
 * 职责：
 * 1. 维护每个会话的结构化任务清单（整表覆写，无部分更新，状态不会漂移）
 * 2. 归一化不变式：同一时刻最多一项 in_progress（多余的降级为 pending）
 * 3. 每次更新通过 SSE `plan_update` 推送完整清单，前端渲染为常驻进度卡
 * 4. 提供给编排器的"防走神"摘要：每次工具执行后注入当前清单状态
 *
 * 清单跨轮次保留（用户回复"继续"时模型能接着上次的清单干），
 * 由模型下一次 todo_write 整表覆写，或随会话切换自然失效。
 */
@Service
@RequiredArgsConstructor
public class TodoListService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TodoListService.class);

    private static final List<String> VALID_STATUSES = List.of("pending", "in_progress", "completed", "failed");

    public record TodoItem(String content, String activeForm, String status) {}

    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;

    // conversationId -> 当前任务清单
    private final Map<String, List<TodoItem>> lists = new ConcurrentHashMap<>();

    /**
     * 整表覆写任务清单。返回给模型的确认信息（含归一化说明）。
     */
    public String update(String conversationId, String todosJson) {
        List<TodoItem> todos = new ArrayList<>();
        boolean demotedExtraInProgress = false;
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(todosJson);
            if (!root.isArray()) {
                return "Error: todos 必须是 JSON 数组，元素形如 {\"content\":\"...\",\"activeForm\":\"...\",\"status\":\"pending\"}";
            }
            boolean seenInProgress = false;
            for (com.fasterxml.jackson.databind.JsonNode n : root) {
                String content = n.path("content").asText("").trim();
                if (content.isEmpty()) continue;
                String activeForm = n.path("activeForm").asText("").trim();
                if (activeForm.isEmpty()) activeForm = content;
                String status = n.path("status").asText("pending").trim().toLowerCase();
                if (!VALID_STATUSES.contains(status)) status = "pending";
                // 不变式：最多一项 in_progress，多余的降级为 pending
                if ("in_progress".equals(status)) {
                    if (seenInProgress) {
                        status = "pending";
                        demotedExtraInProgress = true;
                    } else {
                        seenInProgress = true;
                    }
                }
                todos.add(new TodoItem(content, activeForm, status));
            }
        } catch (Exception e) {
            return "Error: todos JSON 解析失败（" + e.getMessage() + "）。请传合法 JSON 数组。";
        }
        if (todos.isEmpty()) {
            return "Error: 任务清单为空。至少提供一项，或不要调用 todo_write。";
        }

        lists.put(conversationId, todos);
        pushToFrontend(conversationId, todos);

        long done = todos.stream().filter(t -> "completed".equals(t.status())).count();
        String confirmation = String.format("任务清单已更新：共 %d 项，已完成 %d 项。%s", todos.size(), done,
                demotedExtraInProgress ? "（注意：同一时刻只允许一项 in_progress，多余的已降级为 pending）" : "");
        log.info("Todo list updated for {}: {}/{} completed", conversationId, done, todos.size());
        return confirmation;
    }

    public boolean hasList(String conversationId) {
        List<TodoItem> todos = lists.get(conversationId);
        return todos != null && !todos.isEmpty();
    }

    /**
     * 供编排器在每次工具执行后注入的"防走神"摘要（Claude Code system-reminder 模式）。
     * 清单不存在或已全部完成时返回 null（不注入）。
     */
    public String reminder(String conversationId) {
        List<TodoItem> todos = lists.get(conversationId);
        if (todos == null || todos.isEmpty()) return null;
        long done = todos.stream().filter(t -> "completed".equals(t.status())).count();
        if (done == todos.size()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[任务清单状态 %d/%d 完成]", done, todos.size()));
        todos.stream().filter(t -> "in_progress".equals(t.status())).findFirst()
                .ifPresent(t -> sb.append(" 进行中：").append(t.content()).append("。"));
        List<String> pending = todos.stream().filter(t -> "pending".equals(t.status()))
                .map(TodoItem::content).toList();
        if (!pending.isEmpty()) {
            sb.append(" 待办：").append(String.join("、", pending.subList(0, Math.min(3, pending.size()))));
            if (pending.size() > 3) sb.append(" 等 ").append(pending.size()).append(" 项");
            sb.append("。");
        }
        sb.append("（有进展就用 todo_write 整表更新；同一轮完成多项可合并为一次更新；全部完成后输出 <final> 汇总。）");
        return sb.toString();
    }

    /**
     * 断线重连恢复：把当前清单重新推给前端。
     */
    public void resendToFrontend(String conversationId) {
        List<TodoItem> todos = lists.get(conversationId);
        if (todos != null && !todos.isEmpty()) {
            pushToFrontend(conversationId, todos);
        }
    }

    private void pushToFrontend(String conversationId, List<TodoItem> todos) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("todos", todos));
            sseEmitterService.send(conversationId, "plan_update", json);
        } catch (Exception e) {
            log.warn("Failed to push plan_update for {}", conversationId, e);
        }
    }
}
