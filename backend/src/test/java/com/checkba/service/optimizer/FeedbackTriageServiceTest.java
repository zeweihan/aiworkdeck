package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 分诊的两条硬规则（先于模型判定）与解析容错。
 * 这里刻意不接真模型：要守的是「什么情况下不许让模型说了算」。
 */
class FeedbackTriageServiceTest {

    private FeedbackTriageService withModelReply(String reply) {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString())).thenReturn(reply);
        Function<String, ChatLanguageModel> supplier = id -> model;
        return new FeedbackTriageService(supplier);
    }

    private static UserFeedback feedback(String text, String transcript) {
        UserFeedback fb = new UserFeedback();
        fb.setId(1L);
        fb.setKind(UserFeedback.KIND_BUG);
        fb.setText(text);
        fb.setVoiceTranscript(transcript);
        return fb;
    }

    private static FeedbackAttachment att(String type) {
        FeedbackAttachment a = new FeedbackAttachment();
        a.setId(1L);
        a.setType(type);
        a.setStoredName("x");
        return a;
    }

    @Test
    void audioWithoutTranscriptGoesToHumanWithoutCallingModel() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        FeedbackTriageService svc = new FeedbackTriageService((Function<String, ChatLanguageModel>) id -> model);

        var r = svc.triage(feedback("", null), List.of(att(FeedbackAttachment.TYPE_AUDIO)), "");

        assertEquals(FeedbackTriageService.VERDICT_UNCLEAR, r.verdict());
        assertEquals(0.0, r.confidence());
        verifyNoInteractions(model);
    }

    @Test
    void screenshotOnlyGoesToHumanWithoutCallingModel() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        FeedbackTriageService svc = new FeedbackTriageService((Function<String, ChatLanguageModel>) id -> model);

        var r = svc.triage(feedback("  ", null), List.of(att(FeedbackAttachment.TYPE_IMAGE)), "");

        assertEquals(FeedbackTriageService.VERDICT_UNCLEAR, r.verdict());
        verifyNoInteractions(model);
    }

    @Test
    void transcriptAloneIsEnoughToAskTheModel() {
        FeedbackTriageService svc = withModelReply(
                "{\"verdict\":\"BUG\",\"confidence\":0.9,\"title\":\"保存无反应\",\"summary\":\"s\",\"severity\":\"high\",\"reason\":\"r\"}");

        var r = svc.triage(feedback("", "点保存没反应"), List.of(att(FeedbackAttachment.TYPE_AUDIO)), "");

        assertTrue(r.isBug());
        assertEquals(0.9, r.confidence(), 1e-9);
        assertEquals("保存无反应", r.title());
    }

    @Test
    void jsonInsideCodeFenceAndPreambleIsParsed() {
        FeedbackTriageService svc = withModelReply("""
                好的，我的判断如下：
                ```json
                {"verdict":"suggestion","confidence":0.4,"title":"希望支持批量导出","summary":"s","severity":"low","reason":"r"}
                ```
                """);

        var r = svc.triage(feedback("希望能批量导出", null), List.of(), "");

        assertEquals(FeedbackTriageService.VERDICT_SUGGESTION, r.verdict());
        assertEquals(0.4, r.confidence(), 1e-9);
    }

    @Test
    void unknownVerdictDegradesToUnclearNotNoise() {
        FeedbackTriageService svc = withModelReply("{\"verdict\":\"WHATEVER\",\"confidence\":0.99}");

        var r = svc.triage(feedback("有点怪", null), List.of(), "");

        assertEquals(FeedbackTriageService.VERDICT_UNCLEAR, r.verdict());
    }

    @Test
    void unparsableOutputDegradesToUnclearNotNoise() {
        FeedbackTriageService svc = withModelReply("我觉得这不是问题。");

        var r = svc.triage(feedback("有点怪", null), List.of(), "");

        // 解析不了 ≠ 没问题：绝不能落成 NOISE 被静默跳过
        assertEquals(FeedbackTriageService.VERDICT_UNCLEAR, r.verdict());
        assertNotEquals(FeedbackTriageService.VERDICT_NOISE, r.verdict());
    }

    @Test
    void confidenceIsClampedToUnitInterval() {
        FeedbackTriageService svc = withModelReply("{\"verdict\":\"BUG\",\"confidence\":7}");
        assertEquals(1.0, svc.triage(feedback("x", null), List.of(), "").confidence(), 1e-9);

        FeedbackTriageService neg = withModelReply("{\"verdict\":\"BUG\",\"confidence\":-3}");
        assertEquals(0.0, neg.triage(feedback("x", null), List.of(), "").confidence(), 1e-9);
    }

    @Test
    void promptCarriesUserTextVersionAndLogTail() {
        FeedbackTriageService svc = withModelReply("{}");
        UserFeedback fb = feedback("点保存没反应", null);
        fb.setAppVersion("0.13.0");
        fb.setPage("pages/project-overview/project-overview");
        fb.setContextJson("{\"backendLogTail\":\"java.lang.NullPointerException at Foo\"}");

        String prompt = svc.buildPrompt(fb, List.of(att(FeedbackAttachment.TYPE_IMAGE)));

        assertTrue(prompt.contains("点保存没反应"));
        assertTrue(prompt.contains("0.13.0"));
        assertTrue(prompt.contains("NullPointerException"));
        assertTrue(prompt.contains("1 张图片"));
    }

    @Test
    void logTailFedToModelIsCapped() {
        FeedbackTriageService svc = withModelReply("{}");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("noise ");
        String tail = svc.extractLogTail("{\"backendLogTail\":\"" + big + "\"}");
        assertTrue(tail.length() <= 3000);
    }
}
