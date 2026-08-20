package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具失败判据：中文「错误：」前缀必须和英文 "Error" 同等对待。
 *
 * <p>病灶：{@code ToolResult.success()} 只认英文 {@code "Error"} 前缀与
 * {@code {"error"} } JSON 形态，而 MemoryTools / TagTools / TaskTools /
 * EvidenceTools / PptxTools 共 37 处失败返回用的是中文「错误：」。
 * 这些工具**自认为在报错**，判据却听不见，于是：
 * <ul>
 *   <li>过程卡给失败的调用打绿勾（用户看到「查询企业工商信息 ✓」而内容是查不到）；</li>
 *   <li>{@code appendFailureNudge} 把 {@code consecutiveFailures} 清零，
 *       连续失败纠正回路（CONSECUTIVE_FAILURE_NUDGE）对这些工具**永远不触发**，
 *       模型可以对着同一个错误一直重试到步数上限；</li>
 *   <li>埋点 {@code ai.tool success=true}，外部服务全线失败时指标仍是健康的。</li>
 * </ul>
 *
 * <p>判据只认<b>前缀</b>，不认「失败/不可用」这类词出现在正文任意位置——
 * 合同正文里出现「违约」「失败」是家常便饭，按包含匹配会把正常结果误判成失败，
 * 那比漏判更糟。
 */
class ToolFailureClassificationTest {

    private static boolean success(String output) {
        return new ToolRegistry.ToolResult(output, null, true).success();
    }

    @Test
    @DisplayName("中文「错误：」前缀 = 失败（MemoryTools/TagTools/TaskTools 等 37 处在用）")
    void chineseErrorPrefixIsAFailure() {
        assertFalse(success("错误：无法获取当前项目ID，请在项目上下文中使用此工具。"));
        assertFalse(success("错误：无法获取当前用户ID，无法保存用户级记忆。"));
        assertFalse(success("错误：打标签失败，标签不存在"));
        assertFalse(success("错误：PPTX 生成服务不可用。请先启动 Docker 服务"));
    }

    @Test
    @DisplayName("前导空白不影响判定（与英文分支同口径）")
    void leadingWhitespaceDoesNotHideTheMarker() {
        assertFalse(success("\n  错误：无法获取当前项目ID。"));
        assertFalse(success("\n  Error: file not found"));
    }

    @Test
    @DisplayName("既有的英文与 JSON 失败形态不变")
    void existingFailureShapesStillDetected() {
        assertFalse(success("Error: File not found."));
        assertFalse(success("{\"error\": \"操作超时\"}"));
        assertFalse(success("{ \"error\" : \"editor not open\" }"));
    }

    @Test
    @DisplayName("正文里出现「失败/错误」不算失败——判据只认前缀，绝不按包含匹配")
    void wordsInsideTheBodyMustNotFlipTheVerdict() {
        assertTrue(success("第三条 违约责任：一方未能履行的，视为违约，另一方有权解除合同。"),
                "合同正文里出现失败/违约字样是家常便饭，误判成失败比漏判更糟");
        assertTrue(success("检索到 3 条结果，其中 1 条记载该公司曾因申报错误被行政处罚。"));
        assertTrue(success("[文件 起诉状.docx]\n原告诉称：被告交付失败，构成根本违约。"));
    }

    @Test
    @DisplayName("正常结果与空白仍按既有规则")
    void normalResultsUnchanged() {
        assertTrue(success("合同正文……"));
        assertFalse(new ToolRegistry.ToolResult(null, null, true).success());
        assertFalse(new ToolRegistry.ToolResult("whatever", null, false).success());
    }
}
