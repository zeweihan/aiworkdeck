package com.checkba.service.ai;

import com.checkba.model.entity.AgentTodoList;
import com.checkba.repository.AgentTodoListRepository;
import com.checkba.service.LangText;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
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
 *
 * <p><b>持久化（口径对齐 AgentRunStateService）</b>：内存 map 是快路径，同时写透
 * {@code agent_todo_list} 表；DB 写失败只记日志、绝不阻断（进度卡坏掉不该让对话中断）。
 * 之前清单是纯内存的，而 run 状态能跨重启回收成 INTERRUPTED 并给用户一个「继续」按钮——
 * 点下去清单已经没了，是个假承诺。现在读路径未命中时按 conversationId 惰性回填，
 * 「继续」能接着上次的清单干。
 */
@Service
@RequiredArgsConstructor
public class TodoListService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TodoListService.class);

    private static final List<String> VALID_STATUSES = List.of("pending", "in_progress", "completed", "failed");

    /**
     * 清单保留期：会话冷掉这么久之后，清单已无恢复价值（模型早就该重新 todo_write 了），
     * 留着只会让表和内存 map 只增不减。比登录会话的 7 天宽松——律师放一两周再回来接着改合同是常态。
     */
    static final Duration RETENTION = Duration.ofDays(30);

    public record TodoItem(String content, String activeForm, String status) {}

    /** 解析结果：error 非空表示整体拒绝（此时 todos 无意义）。 */
    private record ParsedTodos(List<TodoItem> todos, boolean demotedExtraInProgress, String error) {}

    private final SseEmitterService sseEmitterService;
    private final ObjectMapper objectMapper;
    private final AgentTodoListRepository repository;

    /**
     * conversationId -> 当前任务清单。
     * 空 list 是「已查过 DB、确实没有」的负缓存占位：reminder() 每次工具执行后都会被调，
     * 不占位的话没用过 todo_write 的会话会每次都打一次库。
     */
    private final Map<String, List<TodoItem>> lists = new ConcurrentHashMap<>();

    /**
     * 整表覆写任务清单。返回给模型的确认信息（含归一化说明）。
     */
    public String update(String conversationId, String todosJson) {
        ParsedTodos parsed = parse(todosJson);
        if (parsed.error() != null) {
            return parsed.error();
        }
        List<TodoItem> todos = parsed.todos();

        lists.put(conversationId, todos);
        persist(conversationId, todos);
        pushToFrontend(conversationId, todos);

        long done = todos.stream().filter(t -> "completed".equals(t.status())).count();
        String confirmation = String.format(
                LangText.of("任务清单已更新：共 %d 项，已完成 %d 项。%s", "Todo list updated: %d item(s) total, %d completed.%s"),
                todos.size(), done,
                parsed.demotedExtraInProgress()
                        ? LangText.of("（注意：同一时刻只允许一项 in_progress，多余的已降级为 pending）",
                                " (Note: only one item may be in_progress at a time; extras were demoted to pending.)")
                        : "");
        log.info("Todo list updated for {}: {}/{} completed", conversationId, done, todos.size());
        return confirmation;
    }

    /** 解析 + 归一化。写入路径与重启回填路径共用，回填的 JSON 因此也过一遍不变式。 */
    private ParsedTodos parse(String todosJson) {
        List<TodoItem> todos = new ArrayList<>();
        boolean demotedExtraInProgress = false;
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(todosJson);
            if (!root.isArray()) {
                return new ParsedTodos(List.of(), false,
                        LangText.of("Error: todos 必须是 JSON 数组，元素形如 {\"content\":\"...\",\"activeForm\":\"...\",\"status\":\"pending\"}",
                                "Error: todos must be a JSON array, with elements like {\"content\":\"...\",\"activeForm\":\"...\",\"status\":\"pending\"}"));
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
            return new ParsedTodos(List.of(), false,
                    LangText.of("Error: todos JSON 解析失败（", "Error: failed to parse todos JSON (") + e.getMessage() +
                            LangText.of("）。请传合法 JSON 数组。", "). Please pass a valid JSON array."));
        }
        if (todos.isEmpty()) {
            return new ParsedTodos(List.of(), false, LangText.of(
                    "Error: 任务清单为空。至少提供一项，或不要调用 todo_write。",
                    "Error: the todo list is empty. Provide at least one item, or don't call todo_write."));
        }
        return new ParsedTodos(todos, demotedExtraInProgress, null);
    }

    public boolean hasList(String conversationId) {
        return !currentList(conversationId).isEmpty();
    }

    /**
     * 供编排器在每次工具执行后注入的"防走神"摘要（Claude Code system-reminder 模式）。
     * 清单不存在或已无待办事项（完成或失败均算数）时返回 null（不注入）。
     *
     * <p><b>failed 是终态，要和 completed 一起算"done"</b>：此前只认 completed，
     * 一个 failed 项永远不会自己变成 completed，于是 {@code done < todos.size()} 恒成立，
     * 这条提醒永远关不掉，且摘要正文只列 in_progress/pending，完全不提 failed 项——
     * 模型收到的是一句问不出所以然的"还没做完"，看不出到底卡在哪一项（审计条目）。
     */
    public String reminder(String conversationId) {
        List<TodoItem> todos = currentList(conversationId);
        if (todos.isEmpty()) return null;
        long done = todos.stream().filter(t -> "completed".equals(t.status())).count();
        List<String> failed = todos.stream().filter(t -> "failed".equals(t.status()))
                .map(TodoItem::content).toList();
        if (done + failed.size() == todos.size()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[任务清单状态 %d/%d 完成]", done, todos.size()));
        if (!failed.isEmpty()) {
            sb.append(" 已失败：").append(String.join("、", failed)).append("。");
        }
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
     * 进程重启后这里也走 DB 回填，因此 /connect 一样能把进度卡重建出来。
     */
    public void resendToFrontend(String conversationId) {
        List<TodoItem> todos = currentList(conversationId);
        if (!todos.isEmpty()) {
            pushToFrontend(conversationId, todos);
        }
    }

    /**
     * 读路径统一入口：内存未命中时按 conversationId 从 DB 回填（进程重启后的唯一恢复通道）。
     * 永远返回非 null；查不到返回空 list。
     */
    private List<TodoItem> currentList(String conversationId) {
        if (conversationId == null) return List.of();
        List<TodoItem> cached = lists.get(conversationId);
        if (cached != null) return cached;
        try {
            List<TodoItem> restored = repository.findByConversationId(conversationId)
                    .map(r -> parse(r.getTodosJson()))
                    .filter(p -> p.error() == null)
                    .map(ParsedTodos::todos)
                    .orElse(List.of());
            // 查不到也占位（负缓存），否则每次工具执行后的 reminder() 都要打一次库
            lists.put(conversationId, restored);
            if (!restored.isEmpty()) {
                log.info("Restored todo list for {} from database: {} item(s)", conversationId, restored.size());
            }
            return restored;
        } catch (Exception e) {
            // 读失败只影响「重启后能不能接着干」，不缓存结果，下次还有机会恢复
            log.warn("Failed to load todo list for {}", conversationId, e);
            return List.of();
        }
    }

    /** 写透 DB。失败只记日志——进度卡坏掉不该让整轮对话中断。 */
    private void persist(String conversationId, List<TodoItem> todos) {
        if (conversationId == null) return;
        try {
            AgentTodoList record = repository.findByConversationId(conversationId)
                    .orElseGet(AgentTodoList::new);
            record.setConversationId(conversationId);
            record.setTodosJson(objectMapper.writeValueAsString(todos));
            record.setUpdatedAt(LocalDateTime.now());
            repository.save(record);
        } catch (Exception e) {
            log.warn("Failed to persist todo list: conv={}", conversationId, e);
        }
    }

    /**
     * 每日清理冷清单：删 DB 行，同时摘掉对应的内存条目，以及所有负缓存占位
     * （占位不含任何信息，丢了下次读会自己回填）。
     */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000, initialDelay = 15 * 60 * 1000)
    public void purgeStaleLists() {
        try {
            List<AgentTodoList> stale = repository.findByUpdatedAtBefore(LocalDateTime.now().minus(RETENTION));
            for (AgentTodoList record : stale) {
                lists.remove(record.getConversationId());
            }
            if (!stale.isEmpty()) {
                repository.deleteAll(stale);
                log.info("清理冷任务清单 {} 条", stale.size());
            }
            lists.entrySet().removeIf(e -> e.getValue().isEmpty());
        } catch (Exception e) {
            log.warn("Failed to purge stale todo lists", e);
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
