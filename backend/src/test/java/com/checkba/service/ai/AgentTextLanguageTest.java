package com.checkba.service.ai;

import com.checkba.service.AppLanguageService;
import com.checkba.service.LangText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 链路用户可见文案的应用语言抽样断言（EN 版 PR4-A）：
 * zh 默认（LangText 未登记）与今天逐字节一致，en 模式关键文案是英文。
 */
class AgentTextLanguageTest {

    @AfterEach
    void reset() {
        LangText.reset();
    }

    private void switchToEnglish() {
        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);
    }

    @Test
    void maxDepthNotice_chineseByDefault() {
        assertEquals(
                "\n\n> 本轮已达最大执行步数（30 步），先暂停。已完成的修改均已生效，点击下方「继续」按钮可接着执行剩余任务。",
                AgentOrchestrator.maxDepthNotice(),
                "zh 模式步数暂停 notice 必须与存量逐字节一致");
    }

    @Test
    void maxDepthNotice_englishInEnglishMode() {
        switchToEnglish();
        String notice = AgentOrchestrator.maxDepthNotice();
        assertTrue(notice.contains("maximum step budget"), "en 模式步数暂停 notice 要是英文: " + notice);
        assertTrue(notice.contains("30 steps"));
        assertFalse(notice.contains("步数"), "en 模式不该混入中文");
    }

    @Test
    void userFacingReason_followsAppLanguage() {
        assertEquals("触发了限流", LlmErrorClassifier.Kind.RATE_LIMITED.userFacingReason());
        assertEquals("账户额度不足", LlmErrorClassifier.Kind.QUOTA_EXHAUSTED.userFacingReason());

        switchToEnglish();
        assertEquals("hit a rate limit", LlmErrorClassifier.Kind.RATE_LIMITED.userFacingReason());
        assertEquals("ran out of account credit", LlmErrorClassifier.Kind.QUOTA_EXHAUSTED.userFacingReason());
    }

    @Test
    void interruptNotice_followsAppLanguage() {
        assertEquals(AgentRunRecoveryService.INTERRUPT_NOTICE_ZH, AgentRunRecoveryService.interruptNotice());
        switchToEnglish();
        assertEquals(AgentRunRecoveryService.INTERRUPT_NOTICE_EN, AgentRunRecoveryService.interruptNotice());
    }
}
