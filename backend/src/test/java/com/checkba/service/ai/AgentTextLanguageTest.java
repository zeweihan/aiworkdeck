// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
