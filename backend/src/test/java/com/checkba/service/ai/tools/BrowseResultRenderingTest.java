package com.checkba.service.ai.tools;

import com.checkba.service.ai.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * browse_url 抓不到正文时不许伪装成成功。
 *
 * <p>病灶：无论抓到什么，旧实现都返回 {@code "Page Title: …\n\nContent:\n…"}。
 * 导航失败（域名解析不了、连接被拒、被 SSRF 路由拦掉）时 {@code page.content()}
 * 拿到的是 about:blank 或 Chromium 自己的错误页，照样拼成这个格式返回。
 *
 * <p>这个串**非空**，所以：
 * <ul>
 *   <li>编排器的空输出归一（BLANK_TOOL_OUTPUT）拦不住它；</li>
 *   <li>{@code ToolResult.success()} 判它成功（不以 Error/错误 开头）；</li>
 * </ul>
 * 模型于是收到一个「成功但什么都没有」的结果，转头就认定这个网页确实没内容。
 *
 * <p>判「有没有真读到东西」的那段抽成了静态纯函数，才测得动——browse_url 本体要
 * 拉起 Playwright + Chromium，单测里跑不动。
 */
class BrowseResultRenderingTest {

    private static boolean classifiedAsSuccess(String toolOutput) {
        return new ToolRegistry.ToolResult(toolOutput, null, true).success();
    }

    @Test
    @DisplayName("正文为空：必须报错，且要被判成工具失败")
    void emptyBodyIsAnError() {
        String out = WebTools.renderBrowseResult("https://example.com", "", "");

        assertTrue(out.startsWith("Error browsing URL"), "实际是：" + out);
        assertFalse(classifiedAsSuccess(out),
                "空正文必须被 ToolResult.success() 判成失败，否则过程卡打绿勾、失败回路不计数");
    }

    @Test
    @DisplayName("只有空白字符的正文同样算抓不到")
    void whitespaceOnlyBodyIsAnError() {
        String out = WebTools.renderBrowseResult("https://example.com", "标题", "   \n\t  ");

        assertTrue(out.startsWith("Error browsing URL"), "实际是：" + out);
        assertFalse(classifiedAsSuccess(out));
    }

    @Test
    @DisplayName("报错要带上可行动的原因，别让模型以为「这网页就是空的」")
    void errorMessageIsActionable() {
        String out = WebTools.renderBrowseResult("https://example.com/doc.pdf", "登录", "");

        assertTrue(out.contains("do not treat this as"), "要明确否掉「页面为空」这个结论，实际是：" + out);
        assertTrue(out.contains("登录"), "已知的标题要带上，便于模型判断是不是登录墙，实际是：" + out);
        assertTrue(out.contains("example.com/doc.pdf"), "要带上 URL，实际是：" + out);
    }

    @Test
    @DisplayName("正常抓到正文时格式与既有完全一致")
    void normalResultKeepsTheExistingShape() {
        String out = WebTools.renderBrowseResult("https://example.com", "民法典全文", "第五百七十七条 …");

        assertTrue(out.startsWith("Page Title: 民法典全文"), "实际是：" + out);
        assertTrue(out.contains("\n\nContent:\n第五百七十七条 …"), "实际是：" + out);
        assertTrue(classifiedAsSuccess(out), "正常结果必须仍判成功");
    }

    @Test
    @DisplayName("标题缺失但有正文：仍算成功，不因为没有标题就报错")
    void missingTitleWithBodyIsStillSuccess() {
        String out = WebTools.renderBrowseResult("https://example.com", null, "有正文");

        assertTrue(out.contains("有正文"), "实际是：" + out);
        assertTrue(classifiedAsSuccess(out));
    }
}
