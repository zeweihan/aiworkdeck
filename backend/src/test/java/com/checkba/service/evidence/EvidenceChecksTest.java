package com.checkba.service.evidence;

import com.checkba.service.evidence.EvidenceChecks.Check;
import com.checkba.service.evidence.EvidenceChecks.Party;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 勾稽核查的纯判定层（dev-board#116）：四类可机器校验的要素 + 判据合成。
 *
 * <p>红线：缺证据 ≠ 矛盾（evidence.retrieve.v1 既有不变式）——底稿里找不到某要素只记
 * {@code ok=null} 与 {@code unverifiable}，只有「同一标识在两边都在、值不一样」或
 * 「陈述里的代码自身校验位不符」才判 {@code contradicts}。
 */
class EvidenceChecksTest {

    // ------------------------------------------------------------ 统一社会信用代码

    /** 四条真实在用的 18 位码（校验位由 GB 32100-2015 权重表独立算过）。 */
    @Test
    @DisplayName("USCC 校验位：合法码通过")
    void usccValidCodes() {
        assertTrue(EvidenceChecks.usccValid("91330100799655058B"));
        assertTrue(EvidenceChecks.usccValid("914403001922038216"));
        assertTrue(EvidenceChecks.usccValid("91110108551385082Q"));
        assertTrue(EvidenceChecks.usccValid("91350100M000100Y43"));
    }

    @Test
    @DisplayName("USCC 校验位：末位改一个字符即不通过")
    void usccBadCheckDigit() {
        assertFalse(EvidenceChecks.usccValid("91440300192203821A"));
        assertFalse(EvidenceChecks.usccValid("91330100799655058C"));
    }

    @Test
    @DisplayName("USCC 校验位：长度不足、含被排除字母 I/O/S/V/Z 一律不通过")
    void usccMalformed() {
        assertFalse(EvidenceChecks.usccValid("9133010079965505"));
        assertFalse(EvidenceChecks.usccValid("91330100799655058BB"));
        assertFalse(EvidenceChecks.usccValid("9133010079965505IB"));
        assertFalse(EvidenceChecks.usccValid(null));
    }

    @Test
    @DisplayName("USCC：陈述与底稿一致 → ok=true / supports")
    void usccMatches() {
        List<Check> checks = EvidenceChecks.run(
                "目标公司统一社会信用代码为 91330100799655058B。",
                "营业执照\n统一社会信用代码 91330100799655058B\n名称 某某公司",
                List.of());
        Check c = only(checks, EvidenceChecks.KIND_USCC);
        assertEquals(Boolean.TRUE, c.ok());
        assertEquals("91330100799655058B", c.found());
        assertEquals(EvidenceChecks.VERDICT_SUPPORTS, EvidenceChecks.verdict(checks));
        assertEquals((short) 100, EvidenceChecks.confidence(checks));
    }

    @Test
    @DisplayName("USCC：底稿里被 OCR 拆成空格分组也算命中（归一化去空白）")
    void usccMatchesAcrossWhitespace() {
        List<Check> checks = EvidenceChecks.run(
                "统一社会信用代码 91330100799655058B",
                "统 一 社 会 信 用 代 码 ： 9133 0100 7996 5505 8B",
                List.of());
        assertEquals(Boolean.TRUE, only(checks, EvidenceChecks.KIND_USCC).ok());
    }

    @Test
    @DisplayName("USCC：底稿里是另一个合法码 → ok=false / contradicts")
    void usccConflict() {
        List<Check> checks = EvidenceChecks.run(
                "目标公司统一社会信用代码为 91330100799655058B。",
                "营业执照 统一社会信用代码 914403001922038216",
                List.of());
        Check c = only(checks, EvidenceChecks.KIND_USCC);
        assertEquals(Boolean.FALSE, c.ok());
        assertEquals("914403001922038216", c.found());
        assertEquals(EvidenceChecks.VERDICT_CONTRADICTS, EvidenceChecks.verdict(checks));
        assertEquals((short) 0, EvidenceChecks.confidence(checks));
    }

    @Test
    @DisplayName("USCC：底稿里根本没有代码 → ok=null / unverifiable，绝不判 contradicts")
    void usccMissingIsNotContradiction() {
        List<Check> checks = EvidenceChecks.run(
                "目标公司统一社会信用代码为 91330100799655058B。",
                "本页为公司章程正文，未记载任何登记代码。",
                List.of());
        Check c = only(checks, EvidenceChecks.KIND_USCC);
        assertNull(c.ok());
        assertNull(c.found());
        assertEquals(EvidenceChecks.VERDICT_UNVERIFIABLE, EvidenceChecks.verdict(checks));
        assertNull(EvidenceChecks.confidence(checks));
    }

    @Test
    @DisplayName("USCC：陈述自身校验位不符 → ok=false（这是陈述的硬错，不是缺证据）")
    void usccStatementSelfInvalid() {
        List<Check> checks = EvidenceChecks.run(
                "统一社会信用代码 91440300192203821A",
                "营业执照 统一社会信用代码 91440300192203821A",
                List.of());
        Check c = only(checks, EvidenceChecks.KIND_USCC);
        assertEquals(Boolean.FALSE, c.ok());
        assertNotNull(c.note());
        assertEquals(EvidenceChecks.VERDICT_CONTRADICTS, EvidenceChecks.verdict(checks));
    }

    // ------------------------------------------------------------ 日期

    @Test
    @DisplayName("日期：陈述 YYYY年M月D日 命中底稿的 YYYY-MM-DD")
    void dateChineseMatchesDash() {
        List<Check> checks = EvidenceChecks.run("公司于 2025年1月5日 成立。", "登记日期 2025-01-05", List.of());
        assertEquals(Boolean.TRUE, only(checks, EvidenceChecks.KIND_DATE).ok());
    }

    @Test
    @DisplayName("日期：陈述 YYYY-MM-DD 命中底稿的 YYYY年M月D日 / 斜杠 / 点分")
    void dateDashMatchesOtherFormats() {
        assertEquals(Boolean.TRUE, only(EvidenceChecks.run("成立日期 2025-01-05", "二〇二五年\n2025年1月5日核准", List.of()),
                EvidenceChecks.KIND_DATE).ok());
        assertEquals(Boolean.TRUE, only(EvidenceChecks.run("成立日期 2025-01-05", "核准日期 2025/1/5", List.of()),
                EvidenceChecks.KIND_DATE).ok());
        assertEquals(Boolean.TRUE, only(EvidenceChecks.run("成立日期 2025-01-05", "核准日期 2025.01.05", List.of()),
                EvidenceChecks.KIND_DATE).ok());
    }

    @Test
    @DisplayName("日期：底稿里没有这个日期 → ok=null，不判 contradicts（同一份材料里日期可以有很多个）")
    void dateMissingIsUnverifiable() {
        List<Check> checks = EvidenceChecks.run("公司于 2025年1月5日 成立。", "核准日期 2023-07-09", List.of());
        Check c = only(checks, EvidenceChecks.KIND_DATE);
        assertNull(c.ok());
        assertEquals(EvidenceChecks.VERDICT_UNVERIFIABLE, EvidenceChecks.verdict(checks));
    }

    @Test
    @DisplayName("日期：不存在的日历日不当成日期（2025年2月30日）")
    void dateInvalidCalendarDayIgnored() {
        assertTrue(EvidenceChecks.run("协议签于 2025年2月30日。", "任意底稿", List.of()).isEmpty());
    }

    // ------------------------------------------------------------ 金额与比例

    @Test
    @DisplayName("金额：1,234.56 万元 == 底稿里的 12345600")
    void amountWanConversion() {
        List<Check> checks = EvidenceChecks.run("注册资本 1,234.56万元。", "实收资本 12345600 元整", List.of());
        Check c = only(checks, EvidenceChecks.KIND_AMOUNT);
        assertEquals(Boolean.TRUE, c.ok());
        assertEquals("12345600", c.expected());
    }

    @Test
    @DisplayName("金额：1.2 亿元 == 底稿里的 120,000,000 元")
    void amountYiConversion() {
        assertEquals(Boolean.TRUE,
                only(EvidenceChecks.run("交易对价 1.2亿元", "对价合计 120,000,000 元", List.of()),
                        EvidenceChecks.KIND_AMOUNT).ok());
    }

    @Test
    @DisplayName("金额：底稿里没有等值数字 → ok=null，不判 contradicts")
    void amountMissingIsUnverifiable() {
        Check c = only(EvidenceChecks.run("注册资本 1,234.56万元。", "本页无金额记载", List.of()),
                EvidenceChecks.KIND_AMOUNT);
        assertNull(c.ok());
    }

    @Test
    @DisplayName("比例：51% 命中底稿的 51.00%")
    void ratioMatches() {
        Check c = only(EvidenceChecks.run("甲方持股 51%。", "股东出资比例 51.00%", List.of()), EvidenceChecks.KIND_RATIO);
        assertEquals(Boolean.TRUE, c.ok());
    }

    @Test
    @DisplayName("底稿里的小裸数字不当金额：不拿页码/条款号去确认「5 元」")
    void smallBareNumberInDraftIsNotAMatch() {
        Check c = only(EvidenceChecks.run("违约金 5元。", "第 5 条 违约责任", List.of()), EvidenceChecks.KIND_AMOUNT);
        assertNull(c.ok(), "小裸数字不该被当成命中");
        // 底稿写全了单位就算命中
        assertEquals(Boolean.TRUE,
                only(EvidenceChecks.run("违约金 5元。", "违约金为人民币 5 元", List.of()), EvidenceChecks.KIND_AMOUNT).ok());
    }

    @Test
    @DisplayName("底稿日期里的年份不当金额（2025-01-05 会被拆成三个裸数字）")
    void draftDateNumbersAreNotAmounts() {
        Check c = only(EvidenceChecks.run("对价 2025元。", "签署日期 2025年1月5日", List.of()), EvidenceChecks.KIND_AMOUNT);
        assertNull(c.ok());
    }

    @Test
    @DisplayName("比例只认底稿里带 % 的写法，裸着的 51 不算")
    void ratioNeedsExplicitPercentInDraft() {
        Check c = only(EvidenceChecks.run("甲方持股 51%。", "第 51 页 股东名册", List.of()), EvidenceChecks.KIND_RATIO);
        assertNull(c.ok());
    }

    @Test
    @DisplayName("日期在陈述里被空格拆开也能抽出来（2025 年 1 月 5 日）")
    void dateWithSpacesInStatement() {
        assertEquals(Boolean.TRUE,
                only(EvidenceChecks.run("公司于 2025 年 1 月 5 日 成立。", "登记日期 2025-01-05", List.of()),
                        EvidenceChecks.KIND_DATE).ok());
    }

    @Test
    @DisplayName("陈述里的裸数字不当金额（没有单位就不是可核对要素）")
    void bareNumberIsNotAnAmount() {
        assertTrue(EvidenceChecks.run("公司共有 3 名股东。", "股东名册合计 3 人", List.of()).isEmpty());
    }

    // ------------------------------------------------------------ 主体名与别名

    @Test
    @DisplayName("主体：底稿里只出现别名也算命中")
    void partyAliasHit() {
        Party p = new Party("北京京微资易科技有限公司", List.of("京微资易"));
        List<Check> checks = EvidenceChecks.run(
                "北京京微资易科技有限公司持有目标公司 51% 股权。", "京微资易 出具的股东决定", List.of(p));
        Check c = only(checks, EvidenceChecks.KIND_PARTY);
        assertEquals(Boolean.TRUE, c.ok());
        assertEquals("北京京微资易科技有限公司", c.expected());
        assertEquals("京微资易", c.found());
    }

    @Test
    @DisplayName("主体：陈述里用别名、底稿里用全称，同样命中")
    void partyStatementUsesAlias() {
        Party p = new Party("北京京微资易科技有限公司", List.of("京微资易"));
        Check c = only(EvidenceChecks.run("京微资易 为目标公司控股股东。",
                "北京京微资易科技有限公司 营业执照", List.of(p)), EvidenceChecks.KIND_PARTY);
        assertEquals(Boolean.TRUE, c.ok());
    }

    @Test
    @DisplayName("主体：底稿里没有该主体 → ok=null，不判 contradicts")
    void partyMissingIsUnverifiable() {
        Party p = new Party("北京京微资易科技有限公司", List.of("京微资易"));
        Check c = only(EvidenceChecks.run("北京京微资易科技有限公司持股 51%。",
                "另一家公司的章程", List.of(p)), EvidenceChecks.KIND_PARTY);
        assertNull(c.ok());
    }

    @Test
    @DisplayName("主体：陈述里没提到的主体不产生 check")
    void partyNotMentionedProducesNoCheck() {
        Party p = new Party("北京京微资易科技有限公司", List.of("京微资易"));
        assertTrue(EvidenceChecks.run("本章无主体陈述。", "京微资易 的材料", List.of(p)).isEmpty());
    }

    @Test
    @DisplayName("别名来源：description 带「别名/简称」标签才解析，普通说明文字不当别名")
    void aliasParsing() {
        List<String> a = EvidenceChecks.aliasesOf("北京京微资易科技有限公司", "别名：京微资易、京微；简称 京微科技");
        assertTrue(a.contains("京微资易"));
        assertTrue(a.contains("京微"));
        assertTrue(a.contains("京微科技"));
        // 不带标签的自由说明不拆成别名，避免拿一句话去底稿里瞎命中
        List<String> b = EvidenceChecks.aliasesOf("北京京微资易科技有限公司", "本次交易的目标公司，尽调重点");
        assertFalse(b.contains("本次交易的目标公司"));
    }

    @Test
    @DisplayName("别名来源：全称去掉组织形式后缀得到一个派生简称")
    void aliasDerivedShortForm() {
        assertTrue(EvidenceChecks.aliasesOf("北京京微资易科技有限公司", null).contains("北京京微资易科技"));
        assertTrue(EvidenceChecks.aliasesOf("某某集团股份有限公司", null).contains("某某集团"));
        // 去完后太短的不要（两三个字的串在底稿里满地都是）
        assertTrue(EvidenceChecks.aliasesOf("甲公司", null).isEmpty());
    }

    // ------------------------------------------------------------ 判据合成

    @Test
    @DisplayName("陈述里没有任何可机器校验的要素 → 不产生 check，判 unverifiable")
    void nothingCheckable() {
        List<Check> checks = EvidenceChecks.run("公司经营情况良好，管理规范。", "任意底稿正文", List.of());
        assertTrue(checks.isEmpty());
        assertEquals(EvidenceChecks.VERDICT_UNVERIFIABLE, EvidenceChecks.verdict(checks));
        assertNull(EvidenceChecks.confidence(checks));
    }

    @Test
    @DisplayName("一部分命中、一部分缺证据 → partial，confidence 按命中比例")
    void partialVerdict() {
        List<Check> checks = EvidenceChecks.run(
                "公司统一社会信用代码 91330100799655058B，成立于 2025年1月5日。",
                "营业执照 统一社会信用代码 91330100799655058B（未记载成立日期）",
                List.of());
        assertEquals(2, checks.size());
        assertEquals(EvidenceChecks.VERDICT_PARTIAL, EvidenceChecks.verdict(checks));
        assertEquals((short) 50, EvidenceChecks.confidence(checks));
    }

    @Test
    @DisplayName("有任一矛盾即 contradicts，压过其余命中")
    void contradictionWins() {
        List<Check> checks = EvidenceChecks.run(
                "公司统一社会信用代码 91330100799655058B，成立于 2025年1月5日。",
                "统一社会信用代码 914403001922038216，登记日期 2025-01-05",
                List.of());
        assertEquals(EvidenceChecks.VERDICT_CONTRADICTS, EvidenceChecks.verdict(checks));
        assertEquals((short) 0, EvidenceChecks.confidence(checks));
    }

    @Test
    @DisplayName("同一要素在陈述里出现两次只核一次（幂等、不虚增分母）")
    void duplicateElementsCollapse() {
        List<Check> checks = EvidenceChecks.run(
                "代码 91330100799655058B，再说一次 91330100799655058B。",
                "统一社会信用代码 91330100799655058B", List.of());
        assertEquals(1, checks.size());
    }

    private static Check only(List<Check> checks, String kind) {
        List<Check> hit = checks.stream().filter(c -> kind.equals(c.kind())).toList();
        assertEquals(1, hit.size(), "期望恰好一条 " + kind + " check，实际 " + checks);
        return hit.get(0);
    }
}
