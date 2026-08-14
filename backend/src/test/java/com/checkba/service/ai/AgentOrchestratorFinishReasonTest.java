package com.checkba.service.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * finishReason 结构化消费的两个判定（2026-08 对标 DeepSeek Harness）。
 *
 * <p>背景：此前全链路不读 finishReason——LENGTH 截断的原生工具调用会被照常执行
 * （参数恰好仍可解析时最危险：半篇正文的 write_file 直接覆盖用户文件）；
 * 「正常终止 + 零内容」的空响应会静默 FINISHED，用户面前一片空白且无任何重试入口。
 */
class AgentOrchestratorFinishReasonTest {

    private static AiMessage withToolCall() {
        return AiMessage.from(List.of(ToolExecutionRequest.builder()
                .id("c1").name("write_file").arguments("{\"path\":\"a.md\",\"content\":\"半截")
                .build()));
    }

    @Test
    @DisplayName("空响应判定：无工具、正文空白、零 token 流出，三个条件缺一不可")
    void emptyResponseNeedsAllThreeConditions() {
        assertTrue(AgentOrchestrator.isEmptyResponse(AiMessage.from(" "), false));
        assertTrue(AgentOrchestrator.isEmptyResponse(null, false), "内容缺失同样按空响应重试");

        assertFalse(AgentOrchestrator.isEmptyResponse(AiMessage.from("正文"), false), "有正文不算空");
        assertFalse(AgentOrchestrator.isEmptyResponse(withToolCall(), false), "有工具调用不算空");
        assertFalse(AgentOrchestrator.isEmptyResponse(AiMessage.from(" "), true),
                "只要有 token 给用户看过就绝不能悄悄重放——会看到重复内容");
    }

    @Test
    @DisplayName("LENGTH 截断轮判定：只有 LENGTH + 带工具调用才拦，其余一概放行（含 finishReason 为 null 的通道）")
    void truncatedToolCallRoundRequiresLengthAndToolCalls() {
        assertTrue(AgentOrchestrator.isTruncatedToolCallRound(FinishReason.LENGTH, withToolCall()));

        assertFalse(AgentOrchestrator.isTruncatedToolCallRound(FinishReason.LENGTH, AiMessage.from("纯文本被截断")),
                "纯文本截断走「暂停 + 继续」收尾，不走工具纠正回路");
        assertFalse(AgentOrchestrator.isTruncatedToolCallRound(FinishReason.STOP, withToolCall()),
                "正常终止的工具调用照常执行");
        assertFalse(AgentOrchestrator.isTruncatedToolCallRound(FinishReason.TOOL_EXECUTION, withToolCall()));
        assertFalse(AgentOrchestrator.isTruncatedToolCallRound(null, withToolCall()),
                "部分通道不回 finishReason（含回放评测），行为必须与改造前一致");
        assertFalse(AgentOrchestrator.isTruncatedToolCallRound(FinishReason.LENGTH, null));
    }
}
