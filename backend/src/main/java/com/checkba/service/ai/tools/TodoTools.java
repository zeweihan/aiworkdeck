// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.TodoListService;
import com.checkba.service.ai.context.ProjectContextHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 任务清单工具（Cursor todo_write / Claude Code TodoWrite 模式）。
 * 清单既是模型的自我规划工具，也是用户侧的常驻进度 UI（SSE plan_update 驱动）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TodoTools implements AgentToolComponent {

    private final TodoListService todoListService;

    @Tool("维护本轮工作的任务清单（整表覆写）。使用规则：" +
          "1) 多步任务（3 步以上的修改/审查/起草）范围明确后、细读分析前先发简短步骤，" +
          "不要先推演全部修改；需要澄清时仍先问；" +
          "2) 每项实际完成后立即更新为 completed，不得提前标完成；" +
          "下一步已确定且不依赖中间结果时，清单更新与该步工具调用放在同一次响应中按序执行，" +
          "避免只更新状态占一轮；依赖结果的操作仍等待结果，不得省略依据核验或最终回读；" +
          "3) 同一时刻只允许一项 in_progress；" +
          "4) 计划变化时（增删任务）也用本工具整表覆写；" +
          "5) 1-2 步的简单任务不要用。" +
          "清单会实时显示给用户作为进度面板，只管本轮执行进度——跨对话持续的截止日与里程碑用 task_create 等事项工具。")
    @ToolMeta(displayName = "更新任务清单", category = "planning")
    public String todo_write(
            @P("完整任务清单 JSON 数组（整表覆写，含已完成项）。元素形如 " +
               "{\"content\":\"修订违约条款\",\"activeForm\":\"正在修订违约条款\",\"status\":\"in_progress\"}，" +
               "status 取值 pending/in_progress/completed/failed") String todos
    ) {
        log.info("Tool: todo_write called");
        String conversationId = ProjectContextHolder.getConversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            return "Error: 无法获取当前会话ID，任务清单未更新。";
        }
        return todoListService.update(conversationId, todos);
    }
}
