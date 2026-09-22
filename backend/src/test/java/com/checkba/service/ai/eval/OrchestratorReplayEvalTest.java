// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 编排器回归评测（离线回放，不调真实 LLM，进默认 mvn test 套件）。
 *
 * 对 src/test/resources/ai-eval/cases/*.json 中的每个用例：
 * 回放预录的模型输出（XML tool_code 与原生 function calling 两种协议），
 * 断言编排器分发给 ToolRegistry 的工具序列、输出结构标签、artifact 与
 * 会话收尾行为与期望一致。用法与失败解读见 docs/AI_EVAL.md。
 */
@DisplayName("AI 编排器回归评测（回放）")
class OrchestratorReplayEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> replayCases() {
        List<EvalCase> cases = EvalCase.loadAll();
        assertTrue(cases.size() >= 15, "评测集应至少包含 15 个用例，当前 " + cases.size());
        return cases.stream().map(c ->
                DynamicTest.dynamicTest("[" + c.category + "] " + c.id + " — " + c.title,
                        () -> runAndAssert(c)));
    }

    private void runAndAssert(EvalCase c) {
        EvalHarness.RunResult r = EvalHarness.run(c);

        // 0. 回放脚本应恰好消费完；不应出现 SSE error 事件
        assertEquals(0, r.remainingScriptTurns(),
                "编排器请求的 LLM 轮次少于用例预录轮次（脚本剩余 " + r.remainingScriptTurns() + " 轮）");
        assertTrue(r.errorEvents().isEmpty(), "不应有 SSE error 事件: " + r.errorEvents());

        // 1. 工具分发序列
        if (c.expect.toolCalls != null) {
            List<RecordingToolRegistry.Dispatch> actual = r.dispatches();
            assertEquals(c.expect.toolCalls.size(), actual.size(),
                    "工具调用数量不符。实际分发序列: " + describe(actual));
            for (int i = 0; i < actual.size(); i++) {
                EvalCase.ExpectedToolCall expected = c.expect.toolCalls.get(i);
                RecordingToolRegistry.Dispatch d = actual.get(i);
                assertEquals(expected.name, d.resolvedName(),
                        "第 " + (i + 1) + " 个工具调用名不符（raw=" + d.rawName() + "）。实际序列: " + describe(actual));
                assertArgsContain(expected, d, i);
            }
        }

        // 2. 输出结构标签：最终保存的 ASSISTANT 消息
        if (!c.expect.structureContains.isEmpty()) {
            String last = r.lastAssistantMessage().orElse(null);
            assertTrue(last != null, "期望保存 ASSISTANT 消息但一条都没有");
            for (String marker : c.expect.structureContains) {
                assertTrue(last.contains(marker),
                        "最终 ASSISTANT 消息缺少结构标签 [" + marker + "]。实际内容:\n" + last);
            }
        }

        // 3. artifact 落盘
        if (c.expect.artifact != null) {
            assertFalse(r.artifactSaves().isEmpty(), "期望保存 artifact 但 saveArtifactFile 未被调用");
            if (c.expect.artifact.filenameContains != null) {
                assertTrue(r.artifactSaves().stream()
                                .anyMatch(a -> a.filename().contains(c.expect.artifact.filenameContains)),
                        "artifact 文件名应包含 [" + c.expect.artifact.filenameContains + "]，实际: "
                                + r.artifactSaves().stream().map(EvalHarness.ArtifactSave::filename).toList());
            }
        }

        // 4. 会话收尾：最后一个 bubble_end 的 status
        List<EvalHarness.SseEvent> ends = r.events("bubble_end");
        assertFalse(ends.isEmpty(), "未收到 bubble_end 事件，会话没有正常收尾");
        String lastEnd = ends.get(ends.size() - 1).data();
        assertTrue(lastEnd.contains(c.expect.bubbleEndStatus),
                "bubble_end status 应为 [" + c.expect.bubbleEndStatus + "]，实际: " + lastEnd);

        // 5. 是否向 LLM 提供工具规格（ASK 模式只提供只读记忆工具）
        if (c.expect.toolsOffered != null) {
            for (int i = 0; i < r.toolsOfferedPerLlmCall().size(); i++) {
                assertEquals(c.expect.toolsOffered, r.toolsOfferedPerLlmCall().get(i),
                        "第 " + (i + 1) + " 次 LLM 调用的 toolsOffered 不符");
            }
        }

        // 5.1 Skill 工具可见性裁剪：携带工具的 LLM 调用中可见工具的包含/排除断言
        if (!c.expect.offeredToolsInclude.isEmpty() || !c.expect.offeredToolsExclude.isEmpty()) {
            for (int i = 0; i < r.toolNamesOfferedPerLlmCall().size(); i++) {
                List<String> offered = r.toolNamesOfferedPerLlmCall().get(i);
                if (offered.isEmpty()) {
                    continue; // 未携带工具的调用不参与该断言
                }
                for (String name : c.expect.offeredToolsInclude) {
                    assertTrue(offered.contains(name),
                            "第 " + (i + 1) + " 次 LLM 调用可见工具应包含 [" + name + "]，实际: " + offered);
                }
                for (String name : c.expect.offeredToolsExclude) {
                    assertFalse(offered.contains(name),
                            "第 " + (i + 1) + " 次 LLM 调用可见工具不应包含 [" + name + "]（Skill 裁剪失效），实际: " + offered);
                }
            }
        }

        // 5.1.1 逐轮变化的可见性（dev-board#729 ①）：起跑时按活跃文档类型裁掉，
        // 中途新建/切换到别的类型的文档后放回全集。首尾两次调用分别断言。
        if (!c.expect.offeredToolsExcludeFirstCall.isEmpty()
                || !c.expect.offeredToolsIncludeLastCall.isEmpty()) {
            List<List<String>> withTools = r.toolNamesOfferedPerLlmCall().stream()
                    .filter(o -> !o.isEmpty()).toList();
            assertTrue(withTools.size() >= 2,
                    "逐轮可见性断言至少需要两次携带工具的调用，实际 " + withTools.size() + " 次");
            for (String name : c.expect.offeredToolsExcludeFirstCall) {
                assertFalse(withTools.get(0).contains(name),
                        "首次 LLM 调用不应可见 [" + name + "]（起跑裁剪失效），实际: " + withTools.get(0));
            }
            List<String> last = withTools.get(withTools.size() - 1);
            for (String name : c.expect.offeredToolsIncludeLastCall) {
                assertTrue(last.contains(name),
                        "末次 LLM 调用应可见 [" + name + "]（切换文档后没把工具集放回全集，"
                                + "模型第一步做成了、第二步没有工具可用），实际: " + last);
            }
        }

        // 5.2 回喂断言：编排器主动追加的系统提醒应出现在某次 LLM 上下文里
        for (String marker : c.expect.promptContains) {
            assertTrue(r.promptTexts().stream().anyMatch(p -> p.contains(marker)),
                    "期望某次 LLM 调用的上下文中包含 [" + marker + "]，但 " + r.promptTexts().size()
                            + " 次调用里都没有出现");
        }

        // 5.2.1 一次 Tool not found = 白烧一整轮模型往返（dev-board#809 实测「发现 A」的形态）。
        // 默认不允许，只有专门在验这件事本身的用例才放行。
        //
        // 「系统提示里有没有教用不了的工具」不在这里断言：回放 harness 的
        // ContextAssemblerService 是 mock 的，system 消息只是一句占位符，
        // 在这里写 promptExcludes 只会得到一条永远通过的空断言。
        // 那一面由 ContextAssemblerServiceTest（真实 CAS）与
        // SystemPromptToolVisibilityContractTest（逐名对拍片段）守。
        if (!c.expect.allowUnresolvedTools) {
            List<String> unresolved = r.dispatches().stream()
                    .filter(d -> !d.found()).map(RecordingToolRegistry.Dispatch::resolvedName).toList();
            assertTrue(unresolved.isEmpty(),
                    "本轮调到了本会话里不存在或不可见的工具 " + unresolved
                            + "（clientCapability="
                            + (c.clientCapability == null ? "lowa(默认)" : c.clientCapability)
                            + "）。模型拿回的是 \"Tool not found or arguments invalid.\"，"
                            + "这一轮模型往返白烧了——通常意味着系统提示在教一个本会话用不了的工具。");
        }

        // 5.3 本轮检查点（dev-board 审计 B-02）：写入类工具执行前必须为活跃文档留下快照，
        // 否则 doc_restore_checkpoint 无从恢复。判据是 @ToolMeta(fileEffect="MODIFIED")，
        // 所以漏标注解的写入原语会在这里现形。
        if (c.expect.checkpointForFileId != null) {
            assertTrue(r.checkpointFileIds().contains(Long.valueOf(c.expect.checkpointForFileId)),
                    "本轮应为 fileId=" + c.expect.checkpointForFileId + " 建检查点，实际建过的是: "
                            + r.checkpointFileIds()
                            + "（空清单通常意味着这一轮用到的写入原语漏了 @ToolMeta(fileEffect=\"MODIFIED\")）");
        }

        // 6. <title> 协议：会话文件夹重命名
        if (c.expect.renamedTitleContains != null) {
            assertTrue(r.folderRenames().stream().anyMatch(t -> t.contains(c.expect.renamedTitleContains)),
                    "期望会话重命名包含 [" + c.expect.renamedTitleContains + "]，实际: " + r.folderRenames());
        }
    }

    private void assertArgsContain(EvalCase.ExpectedToolCall expected,
                                   RecordingToolRegistry.Dispatch d, int index) {
        if (expected.argsContain.isEmpty()) {
            return;
        }
        Map<String, Object> args;
        try {
            args = MAPPER.readValue(
                    (d.argsJson() == null || d.argsJson().isBlank()) ? "{}" : d.argsJson(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new AssertionError("第 " + (index + 1) + " 个工具调用参数不是合法 JSON: " + d.argsJson(), e);
        }
        for (Map.Entry<String, String> entry : expected.argsContain.entrySet()) {
            Object actual = args.get(entry.getKey());
            assertTrue(actual != null && String.valueOf(actual).contains(entry.getValue()),
                    "第 " + (index + 1) + " 个工具调用 [" + d.resolvedName() + "] 参数 "
                            + entry.getKey() + " 应包含 [" + entry.getValue() + "]，实际参数: " + d.argsJson());
        }
    }

    private String describe(List<RecordingToolRegistry.Dispatch> dispatches) {
        if (dispatches.isEmpty()) {
            return "(无工具调用)";
        }
        StringBuilder sb = new StringBuilder();
        for (RecordingToolRegistry.Dispatch d : dispatches) {
            sb.append("\n  - ").append(d.resolvedName()).append(" args=").append(d.argsJson());
        }
        return sb.toString();
    }
}
