// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.AskUserQuestion;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 向用户提问（dev-board#868，对标 Claude Code 的 AskUserQuestion）。
 *
 * <p>本方法<b>只做参数校验</b>：校验不过回 {@code Error:}，模型改了重发；校验通过后真正的动作
 * （发 SSE {@code ask_user} 事件、落库问题卡、以 AWAITING_INPUT 停机）由编排器做——
 * 这是一个会结束本轮的工具，工具方法本身拿不到「结束本轮」的权力，也不该拿。
 * 编排器按<b>参数</b>重建问题（{@link AskUserQuestion#fromArgsJson}），不读本方法的返回值。
 *
 * <p>刻意不做「工具里阻塞等人类回答」：理由与 {@code <question>} 反问停机相同——工具分发跑在
 * 流式回调线程上，撞 600s callTimeout 与 180s 看门狗，而律师完全可能明天才回来答。
 * 答案是下一轮普通用户消息（以 {@code <ask_user_answer>} 开头）。
 *
 * <p>子 Agent 里不可用（SubAgentService 排除）：子任务没有用户可问，也停不了父轮次。
 */
@Component
public class AskUserTools implements AgentToolComponent {

    @Tool("Ask the user ONE clarifying question and END this turn. The UI shows the question with "
            + "clickable options plus an 'Other' free-text box; the user's answer arrives as the NEXT user "
            + "message starting with <ask_user_answer id=...>. Nothing else runs after this call in the same turn, "
            + "so do not batch other tools after it.\n"
            + "CALL IT BEFORE ACTING when: (1) the request is a yes/no-style question about whether you can do "
            + "something and the action it implies is unclear (e.g. '你能帮我清理一下这个文档么'); "
            + "(2) the key verb has no stated standard or scope ('清理', '整理', '优化一下', '规范一下', 'clean up', "
            + "'tidy up', 'improve') and several reasonable readings would produce different edits; "
            + "(3) the action would delete or rewrite a large part of a document (many paragraphs, whole sections) "
            + "and the user has not explicitly authorised that scope.\n"
            + "DO NOT CALL IT when the instruction already names the target and the action (e.g. '把第12到21段删掉', "
            + "'把甲方全部改成乙方', 'delete paragraphs 12-21'): just do it. Do not ask about things you can read from "
            + "the open document, project files, history or memory - look them up instead. Do not ask twice about "
            + "the same point; once the user has answered, proceed.\n"
            + "Give 2-4 options, each a DISTINCT concrete interpretation (short label + one-line description of what "
            + "you would do). Do NOT add an 'Other' option yourself. Put the option you recommend first.")
    @ToolMeta(displayName = "向用户提问", category = "planning")
    public String ask_user(
            @P("The single question to ask, in the user's language. One sentence; state what is ambiguous.")
            String question,
            @P(value = "Optional JSON array of 2-4 choices: [{\"label\":\"short choice (<=15 chars)\","
                    + "\"description\":\"what you would do if chosen\"}, ...]. Omit for a free-text question.",
                    required = false)
            String options,
            @P(value = "Optional. true when the user may pick several options at once (default false).",
                    required = false)
            Boolean multi_select,
            @P(value = "Optional very short topic tag shown above the question (<=8 chars), e.g. '清理范围'.",
                    required = false)
            String header) {
        try {
            AskUserQuestion q = AskUserQuestion.of(question, options, multi_select, header, null);
            return "OK: the question has been shown to the user with " + q.options().size()
                    + " option(s). This turn ends here; wait for the user's answer in the next message.";
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }
}
