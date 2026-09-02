package com.checkba.service.ai.review;

import com.checkba.service.ai.review.ContractStructureAudit.Finding;
import com.checkba.service.ai.review.ContractStructureAudit.Paragraph;
import com.checkba.service.ai.review.ContractStructureAudit.Report;
import com.checkba.service.ai.review.ContractStructureAudit.Revision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构审计的病灶用例全部取自 dev-board#375 那份台湾认购合约实审：
 * 序文 C 单位差 10 倍、条号从第 10 条起乱、2.1 被前一轮删残、简体混进繁體正文、
 * 「第 4.2(d) 条」这类交叉引用、空白价金。每条用例都先钉「有病灶就报」，
 * 再钉「没病灶不报」——误报会把模型引去改正常条款。
 */
class ContractStructureAuditTest {

    private static List<Paragraph> paras(String... lines) {
        List<Paragraph> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) out.add(new Paragraph(i, lines[i]));
        return out;
    }

    private static Report run(String... lines) {
        return ContractStructureAudit.run(paras(lines), List.of(), null);
    }

    private static boolean any(List<Finding> list, String needle) {
        return list.stream().anyMatch(f -> f.message().contains(needle));
    }

    // ---------------------------------------------------------------- 特征字表自检

    @Test
    @DisplayName("繁简特征字表成对且无重复（表烂了整套字形判定都失真）")
    void scriptPairTableIsWellFormed() {
        assertEquals(0, ContractStructureAudit.SCRIPT_PAIRS.length() % 2, "偶数位简体、奇数位繁體，长度必须是偶数");
        Map<Character, Character> pairs = ContractStructureAudit.pairs();
        Set<Character> trad = new HashSet<>();
        for (Map.Entry<Character, Character> e : pairs.entrySet()) {
            assertFalse(e.getKey().equals(e.getValue()), "同一个字不能既算简体又算繁體：" + e.getKey());
            assertTrue(trad.add(e.getValue()), "繁體侧重复：" + e.getValue());
            assertFalse(pairs.containsKey(e.getValue()), "繁體字不能同时出现在简体侧：" + e.getValue());
        }
        assertEquals(ContractStructureAudit.SCRIPT_PAIRS.length() / 2, pairs.size(), "简体侧有重复字");
    }

    // ---------------------------------------------------------------- 1. 字形

    @Test
    @DisplayName("繁體合约里混入的简体段落被点名，纯繁體段落不报")
    void simplifiedParagraphInsideTraditionalContractIsFlagged() {
        Report r = run(
                "第一條 認購標的：投資人同意依本合約條款認購公司發行之普通股。",
                "第二條 價金：總認購價金應於交割日一次付清，並依約定計算遲延利息。",
                "第三條 陳述與保證：公司於簽約日向投資人為下列陳述與保證。",
                "投资人应于交割日一次付清总认购价金，并明定迟延利息。",
                "第四條 違約責任：任一方違反本合約約定者，應負損害賠償責任。");
        assertEquals("traditional", r.dominantScript);
        assertEquals(1, r.scriptOutliers.size(), r.scriptOutliers.toString());
        assertEquals(3, r.scriptOutliers.get(0).paragraph());
        assertTrue(r.render().contains("简体字混入繁體正文"));
    }

    @Test
    @DisplayName("简体合同判为简体主体，且一两个异体字不算混入")
    void simplifiedContractWithOneStrayCharIsNotFlagged() {
        Report r = run(
                "第一条 合作内容：甲方委托乙方提供法律服务，双方应按约定履行义务。",
                "第二条 费用：乙方应于收到发票后三十日内支付服务费，逾期应承担违约责任。",
                "第三条 保密：双方对本協议内容负有保密义务。");
        assertEquals("simplified", r.dominantScript);
        assertTrue(r.scriptOutliers.isEmpty(), "单个「協」不该被当成整句混入：" + r.scriptOutliers);
    }

    // ---------------------------------------------------------------- 2. 编号

    @Test
    @DisplayName("第9条之后直接第11条：报缺号；连续编号不报")
    void clauseGapIsReported() {
        Report r = run("第一條 定義", "第二條 認購", "第三條 交割", "第五條 保證");
        assertTrue(any(r.numbering, "第3条之后直接是第5条"), r.numbering.toString());
        assertEquals(List.of("第1条", "第2条", "第3条", "第5条"), r.clauseSequence);

        Report ok = run("第一條 定義", "第二條 認購", "第三條 交割", "第四條 保證");
        assertTrue(ok.numbering.isEmpty(), ok.numbering.toString());
    }

    @Test
    @DisplayName("同一条里 (j) 之后直接 (l) 报跳号；下一条重新从 (a) 起不报")
    void latinSubItemsAreScopedPerClause() {
        Report r = run(
                "第八條 投資人之陳述",
                "(a) 投資人為依法設立之法人。",
                "(b) 投資人具有簽署本合約之權限。",
                "(d) 投資人資金來源合法。",
                "第九條 公司之陳述",
                "(a) 公司為依法設立之股份有限公司。",
                "(b) 公司章程所定股份總數足敷本次發行。");
        assertEquals(1, r.numbering.size(), r.numbering.toString());
        assertTrue(any(r.numbering, "从 (b) 跳到 (d)"), r.numbering.toString());
    }

    @Test
    @DisplayName("条内「1.」从 3 起、或 4.2 出现在第 5 条里，都点名")
    void arabicAndDottedNumberingAnomalies() {
        Report r = run(
                "第四條 價金",
                "4.1 總認購價金。",
                "4.2 付款方式。",
                "第五條 交割",
                "4.3 交割條件。",
                "第十條 誠信條款",
                "3. 修訂與棄權。");
        assertTrue(any(r.numbering, "「4.3」出现在第5条范围内"), r.numbering.toString());
        assertTrue(any(r.numbering, "「N.」编号在新条款里从 3 开始"), r.numbering.toString());
    }

    @Test
    @DisplayName("没有「第X条」的合同以 一、二、 为顶层，跳号照报")
    void enumTopLevelWhenNoTiao() {
        Report r = run("一、合作內容", "二、費用", "四、保密");
        assertTrue(any(r.numbering, "「一、」编号从 2 跳到 4"), r.numbering.toString());
    }

    // ---------------------------------------------------------------- 3. 交叉引用

    @Test
    @DisplayName("引用不存在的第 12 条与附表 D 报悬空；存在的第 4.2 条与附表 C 不报")
    void danglingReferences() {
        Report r = run(
                "第四條 價金",
                "4.2 投資人應於交割日一次付清總認購價金。",
                "第六條 遲延付款救濟：投資人違反第 4.2(d) 條者，公司得依第 12 條解除本合約。",
                "第九條 陳述與保證：除附表 C 揭露者外，公司向投資人為下列保證；股東名冊如附表 D。",
                "附表 C 揭露事項");
        assertTrue(any(r.danglingReferences, "没有第12条"), r.danglingReferences.toString());
        assertTrue(any(r.danglingReferences, "附表 D"), r.danglingReferences.toString());
        assertFalse(any(r.danglingReferences, "附表 C"), r.danglingReferences.toString());
        assertFalse(any(r.danglingReferences, "4.2"), "4.2 是定义过的款：" + r.danglingReferences);
        assertFalse(any(r.danglingReferences, "第9条"), "条款标题本身不是引用：" + r.danglingReferences);
    }

    // ---------------------------------------------------------------- 4. 空白

    @Test
    @DisplayName("下划线、空括号、暫定都算空白，并带上下文")
    void blanksAreListedWithContext() {
        Report r = run(
                "1.5 總認購價金：新台幣＿＿＿＿元。",
                "董事會決議發行新股 447,761 股（暫定）。",
                "附表 A 認購價金：新台幣（　）元整。");
        assertEquals(3, r.blanks.size(), r.blanks.toString());
        assertTrue(r.blanks.get(0).message().contains("總認購價金"));
        assertTrue(any(r.blanks, "暫定"));
    }

    // ---------------------------------------------------------------- 5. 金额与算术

    @Test
    @DisplayName("序文 C：14,039,850 股 × 每股 67 元 ≠ 940,670 萬元，报差 10 倍")
    void perShareTimesSharesMismatchByTenfold() {
        Report r = run("C. 公司擬發行普通股 14,039,850 股，每股認購價格新台幣 67 元，投前估值合計新台幣 940,670 萬元。");
        assertEquals(1, r.arithmetic.size(), r.arithmetic.toString());
        String msg = r.arithmetic.get(0).message();
        assertTrue(msg.contains("940,669,950 元"), msg);
        assertTrue(msg.contains("差 10 倍"), msg);
    }

    @Test
    @DisplayName("算式相符不报；台账仍列出金额供跨条款对照；多币种并存被点名")
    void consistentArithmeticIsSilentButLedgerAndCurrenciesAreReported() {
        Report r = run(
                "2.1 投資人認購普通股 447,761 股，每股新台幣 67 元，總認購價金新台幣 29,999,987 元。",
                "第十四條 估值：本次投前估值以美元 30,000,000 元計算。");
        assertTrue(r.arithmetic.isEmpty(), r.arithmetic.toString());
        assertEquals(2, r.amountLedger.size(), r.amountLedger.toString());
        assertTrue(r.amountLedger.get(0).message().contains("447,761 股"));
        assertEquals(Set.of("新台币", "美元"), r.currencies.keySet());
        assertTrue(r.render().contains("多币种并存"));
    }

    // ---------------------------------------------------------------- 7. 既有修订

    @Test
    @DisplayName("前一轮修订按作者/类型汇总，整句级（≥40 字）的删除单独点名")
    void revisionsAreSummarizedAndLargeDeletionsCalledOut() {
        String big = "投資人同意依本合約之條款及條件，以每股新台幣 67 元認購公司本次發行之普通股，認購股數為";
        List<Revision> revs = List.of(
                new Revision("Delete", "葛忠洋_創投", big, "2.1 共計 447,761 股"),
                new Revision("Insert", "AI WorkDeck", "為上限", "董事會決議發行新股 447,761 股為上限"),
                new Revision("Insert", "", "暫定", "董事會決議"));
        Report r = ContractStructureAudit.run(paras("第一條 定義"), revs, null);
        assertEquals(3, r.totalRevisions);
        assertEquals(Map.of("葛忠洋_創投", 1, "AI WorkDeck", 1, "（未知作者）", 1), r.revisionsByAuthor);
        assertEquals(Map.of("Delete", 1, "Insert", 2), r.revisionsByType);
        assertEquals(1, r.largeDeletions.size(), r.largeDeletions.toString());
        assertTrue(r.render().contains("大段删除"));
    }

    @Test
    @DisplayName("修订清单读不到时报告如实写明，不伪装成「没有修订」")
    void revisionNoteIsSurfaced() {
        Report r = ContractStructureAudit.run(paras("第一條 定義"), null, "（修订清单读取失败，已跳过：timeout）");
        assertTrue(r.render().contains("读取失败"));
        assertFalse(r.render().contains("没有未处理的修订"));
    }

    // ---------------------------------------------------------------- 中文数字

    @Test
    @DisplayName("条号里的中文/阿拉伯/全角数字都能解析")
    void chineseNumeralsParse() {
        assertEquals(1, ChineseNumerals.parse("一"));
        assertEquals(10, ChineseNumerals.parse("十"));
        assertEquals(12, ChineseNumerals.parse("十二"));
        assertEquals(21, ChineseNumerals.parse("二十一"));
        assertEquals(103, ChineseNumerals.parse("一百零三"));
        assertEquals(23, ChineseNumerals.parse("23"));
        assertEquals(8, ChineseNumerals.parse("８"));
        assertEquals(-1, ChineseNumerals.parse("甲"));
    }
}
