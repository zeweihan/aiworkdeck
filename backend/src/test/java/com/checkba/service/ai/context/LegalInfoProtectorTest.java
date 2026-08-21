package com.checkba.service.ai.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 法律信息保护器测试
 */
class LegalInfoProtectorTest {

    private LegalInfoProtector protector;

    @BeforeEach
    void setUp() {
        protector = new LegalInfoProtector();
    }

    @Test
    @DisplayName("应该识别法律法规引用")
    void shouldExtractLegalReferences() {
        String content = """
            根据《公司法》第十六条和《证券法》第五十三条的规定，
            上市公司应当遵守《上市公司重大资产重组管理办法》第三十五条的要求。
            """;
        
        List<String> refs = protector.extractLegalReferences(content);
        
        assertEquals(3, refs.size());
        assertTrue(refs.contains("《公司法》第十六条"));
        assertTrue(refs.contains("《证券法》第五十三条"));
        assertTrue(refs.contains("《上市公司重大资产重组管理办法》第三十五条"));
    }

    @Test
    @DisplayName("应该识别金额信息")
    void shouldExtractAmounts() {
        String content = """
            本次交易金额为人民币10.5亿元，其中现金支付3亿元，
            股份支付7.5亿元。标的资产评估值为15亿元。
            """;
        
        List<String> amounts = protector.extractAmounts(content);
        
        assertTrue(amounts.size() >= 3);
        assertTrue(amounts.stream().anyMatch(a -> a.contains("10.5亿元")));
        assertTrue(amounts.stream().anyMatch(a -> a.contains("3亿元")));
    }

    @Test
    @DisplayName("应该识别日期信息")
    void shouldExtractDates() {
        String content = """
            本协议于2024年1月15日签署，自2024年3月1日起生效。
            资产交割日期为2024年6月30日。
            """;
        
        List<String> dates = protector.extractDates(content);
        
        assertEquals(3, dates.size());
        assertTrue(dates.contains("2024年1月15日"));
        assertTrue(dates.contains("2024年3月1日"));
        assertTrue(dates.contains("2024年6月30日"));
    }

    @Test
    @DisplayName("应该标记受保护的内容片段")
    void shouldMarkProtectedInfo() {
        String content = "根据《公司法》第十六条，交易金额为10亿元，签署日期为2024年1月15日。";
        
        List<LegalInfoProtector.ProtectedSegment> segments = protector.markProtectedInfo(content);
        
        assertFalse(segments.isEmpty());
        
        // 验证包含法律引用
        assertTrue(segments.stream().anyMatch(s -> 
                s.getContent().contains("《公司法》") && 
                s.getLevel() == LegalInfoProtector.ProtectionLevel.CRITICAL));
        
        // 验证包含日期
        assertTrue(segments.stream().anyMatch(s -> 
                s.getContent().contains("2024年1月15日")));
    }

    @Test
    @DisplayName("安全压缩应保留关键法律信息")
    void safeCompressShouldPreserveLegalInfo() {
        String content = """
            这是一段很长的法律文书内容，包含大量的背景描述和说明。
            
            根据《公司法》第十六条的规定，公司向其他企业投资或者为他人提供担保，
            依照公司章程的规定，由董事会或者股东会、股东大会决议。
            
            本次交易金额为人民币10.5亿元。
            
            这里是一些不太重要的背景说明，可以被压缩掉。
            包括一些冗余的描述性文字，这些内容在压缩时可以省略。
            
            协议签署日期为2024年1月15日。
            
            更多的背景信息和说明文字...
            """;
        
        // 目标长度设置为原文的一半
        int targetLength = content.length() / 2;
        
        LegalInfoProtector.CompressedResult result = protector.safeCompress(content, targetLength);
        
        // 验证压缩后仍包含关键信息
        assertTrue(result.getContent().contains("《公司法》") || 
                   result.getProtectedSegments().stream().anyMatch(s -> s.getContent().contains("《公司法》")));
    }

    /**
     * 重叠片段合并时 end 取了并集、content 却留着第一个片段的原文，于是
     * [content 长度, end) 那一截在 safeCompress 里被静默跳过。
     * 「人民币500万元」正好同时命中「人民币+数字」和「数字+万元」两条模式，
     * 合并后剩下「人民币500」——一份法律文书里的金额从五百万变成五百，
     * 全程没有任何报错。
     */
    @Test
    @DisplayName("重叠片段合并后必须覆盖整段原文，不能吃掉中间的字")
    void mergedSegmentsMustCoverTheWholeSpan() {
        String content = "本次交易对价为人民币500万元，由甲方于交割日一次性支付。";
        for (LegalInfoProtector.ProtectedSegment s : protector.markProtectedInfo(content)) {
            assertEquals(content.substring(s.getStart(), s.getEnd()), s.getContent(),
                    "片段 [" + s.getStart() + "," + s.getEnd() + ") 的正文与原文对不上");
        }
    }

    @Test
    @DisplayName("压缩不得把金额的单位吃掉（人民币500万元 -> 人民币500）")
    void compressionKeepsTheAmountUnit() {
        String filler = "以下为与本条无关的背景说明文字，用于把正文撑到需要压缩的长度。".repeat(20);
        String content = "本次交易对价为人民币500万元，由甲方于交割日一次性支付。\n" + filler;
        LegalInfoProtector.CompressedResult result =
                protector.safeCompress(content, content.length() / 2);
        assertTrue(result.getContent().contains("人民币500万元"),
                "压缩后金额被截断：" + result.getContent());
    }

    /**
     * 分段循环结束后就直接返回，最后一个受保护片段之后的正文被无条件丢掉——
     * 合同/裁判文书里引用与金额往往集中在前半段，后面几百字的约定、送达条款、
     * 落款就这么没了，而且不像「无受保护片段」那条分支还会补个省略号，
     * 这里连截断标记都没有：模型与用户都不知道后面还有东西。
     */
    @Test
    @DisplayName("最后一个受保护片段之后的正文不能被无声丢掉")
    void tailAfterLastProtectedSegmentIsNotSilentlyDropped() {
        String tail = "本协议自双方签署之日起生效，一式两份，甲乙双方各执一份，具有同等法律效力。".repeat(6);
        String content = "协议签署日期为2024年1月15日。" + tail;
        LegalInfoProtector.CompressedResult result =
                protector.safeCompress(content, content.length() - 20);
        assertTrue(result.getContent().length() > "协议签署日期为2024年1月15日。".length() + 20,
                "尾部正文被整段丢掉了：" + result.getContent());
        assertTrue(result.getContent().contains("2024年1月15日"), "受保护片段仍要在");
    }

    @Test
    @DisplayName("验证压缩结果应检测丢失的关键信息")
    void validateShouldDetectMissingCriticalInfo() {
        String original = "根据《公司法》第十六条，交易金额为10亿元，日期为2024年1月15日。";
        String compressedGood = "《公司法》第十六条，10亿元，2024年1月15日";
        String compressedBad = "某法律条款，交易金额，某日期";
        
        // 好的压缩结果应该通过验证
        LegalInfoProtector.ValidationResult resultGood = protector.validate(original, compressedGood);
        assertTrue(resultGood.isPassed());
        
        // 丢失信息的压缩结果应该失败
        LegalInfoProtector.ValidationResult resultBad = protector.validate(original, compressedBad);
        assertFalse(resultBad.isPassed());
        assertFalse(resultBad.getMissingItems().isEmpty());
    }

    @Test
    @DisplayName("应该识别当事人信息")
    void shouldIdentifyPartyInfo() {
        String content = """
            甲方：北京某某科技有限公司
            乙方：上海某某投资有限公司
            标的公司：深圳某某集团有限公司
            """;
        
        List<LegalInfoProtector.ProtectedSegment> segments = protector.markProtectedInfo(content);
        
        // 应该识别出当事人信息
        assertTrue(segments.stream().anyMatch(s -> 
                s.getType().equals("当事人") && 
                s.getLevel() == LegalInfoProtector.ProtectionLevel.CRITICAL));
    }

    @Test
    @DisplayName("应该识别合同编号")
    void shouldIdentifyContractNumber() {
        String content = "本协议编号：HT-2024-001-A";
        
        List<LegalInfoProtector.ProtectedSegment> segments = protector.markProtectedInfo(content);
        
        assertTrue(segments.stream().anyMatch(s -> 
                s.getType().equals("合同编号")));
    }

    @Test
    @DisplayName("应该识别股权比例")
    void shouldIdentifyPercentages() {
        String content = "本次交易完成后，甲方持股比例将从51%增加至67.5%。";
        
        List<LegalInfoProtector.ProtectedSegment> segments = protector.markProtectedInfo(content);
        
        assertTrue(segments.stream().anyMatch(s -> 
                s.getType().equals("比例") && s.getContent().contains("%")));
    }
}

