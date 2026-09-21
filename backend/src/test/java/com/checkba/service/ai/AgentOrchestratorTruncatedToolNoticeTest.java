// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.AppLanguageService;
import com.checkba.service.LangText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两轮纠正后 tool_code 仍被截断时，那条收尾说明必须真的到得了用户眼前（dev-board#768）。
 *
 * <p>真机 2026-09-21（Windows Word / 英文界面 / addin.workdeck.ai）：一轮 55 秒的对话
 * 把内容以修订形式写进了文档，窗格里却是一个空白气泡。日志显示编排器走的正是这条
 * 「finishing with a visible note」分支，但那条说明当时是**裸接**在 content 后面的——
 * 而截断恰恰发生在 {@code <tool_code>} 里、标签没闭合，于是它落进了工具载荷作用域，
 * 被插件端与桌面端的解析器双双整块丢弃，用户一个字也没看到。
 *
 * <p>插件侧的对拍在 {@code office-addin/taskpane/lib/sse.test.js}
 * （「截断在未闭合的 {@code <tool_code>} 里」一例，裸接看不见、包 final 才看得见）。
 */
@DisplayName("截断收尾说明的可见性")
class AgentOrchestratorTruncatedToolNoticeTest {

    @AfterEach
    void resetLang() {
        LangText.reset();
    }

    @Test
    @DisplayName("说明包在 <final> 里：这是唯一能越过未闭合 tool_code 作用域落回正文的包裹")
    void noticeIsWrappedInFinal() {
        String delta = AgentOrchestrator.truncatedToolNoticeDelta();
        assertTrue(delta.startsWith("<final>"),
                "裸文本会被当成工具载荷丢掉，用户只剩一个空白气泡：" + delta);
        assertTrue(delta.endsWith("</final>"), delta);
        String body = delta.substring("<final>".length(), delta.length() - "</final>".length());
        assertFalse(body.isBlank(), "包裹在、正文空了，等于还是什么都没说");
        assertFalse(body.contains("<"), "说明正文里不该再夹标签，免得顶乱两端的标签栈：" + body);
    }

    @Test
    @DisplayName("说明随应用语言给中英两版：英文界面的用户读不懂中文提示等于没提示")
    void noticeFollowsAppLanguage() {
        LangText.reset(); // 未登记 = 中文，与既有默认态一致
        String zh = AgentOrchestrator.truncatedToolNoticeDelta();
        assertTrue(zh.contains("参数太长"), zh);

        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);
        String enText = AgentOrchestrator.truncatedToolNoticeDelta();
        assertTrue(enText.contains("too long"), enText);
        assertNotEquals(zh, enText);
    }
}
