package com.checkba.service.insight;

import com.checkba.service.insight.DocInsightChecks.Claim;
import com.checkba.service.insight.DocInsightChecks.Finding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档内部一致性校验的判定契约（dev-board#182）：数字归一、同主体判定的保守口径、
 * 统一社会信用代码硬错，以及<b>一键修改的三个前置条件</b>（numberText 逐字在 quote 里、
 * quote 逐字在原文里、组内单位一致）。
 */
class DocInsightChecksTest {

    private static Claim claim(String subject, String metric, String number, String unit, String quote) {
        return new Claim(subject, metric, new BigDecimal(number), unit, quote, number);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> claimsOf(Finding f) {
        return (List<Map<String, Object>>) f.detail().get("claims");
    }

    // ---------------------------------------------------------------- 数字归一

    @Test
    @DisplayName("1,000 万元 与 10,000,000 元 归一后相等，不报矛盾")
    void 千分位与万元换算后相等不报() {
        String doc = "注册资本 1,000 万元。附注：注册资本 10,000,000 元。";
        List<Claim> claims = List.of(
                new Claim("标的公司", "注册资本", new BigDecimal("1000"), "万元", "注册资本 1,000 万元", "1,000"),
                new Claim("标的公司", "注册资本", new BigDecimal("10000000"), "元", "注册资本 10,000,000 元", "10,000,000"));
        assertTrue(DocInsightChecks.countMismatches(claims, doc).isEmpty());
    }

    @Test
    @DisplayName("亿元换算：1 亿元 == 10000 万元")
    void 亿与万换算一致() {
        assertEquals(new BigDecimal("100000000"), DocInsightChecks.scaleOf("亿元"));
        assertEquals(new BigDecimal("10000"), DocInsightChecks.scaleOf("万"));
        assertEquals(BigDecimal.ONE, DocInsightChecks.scaleOf("项"));
        assertEquals("元", DocInsightChecks.baseUnit("亿元"));
        assertEquals("项", DocInsightChecks.baseUnit("项"));
        assertEquals("", DocInsightChecks.baseUnit(null));
    }

    @Test
    @DisplayName("parseNumber 吃千分位，吃不下中文数字（那由模型给 value）")
    void 数字解析() {
        assertEquals(new BigDecimal("1000"), DocInsightChecks.parseNumber("1,000"));
        assertEquals(new BigDecimal("58"), DocInsightChecks.parseNumber("58"));
        assertNull(DocInsightChecks.parseNumber("五十八"));
        assertNull(DocInsightChecks.parseNumber(null));
    }

    // ---------------------------------------------------------------- 命中与不误报

    @Test
    @DisplayName("同一主体同一指标出现 58 与 39 → 报矛盾，detail 带两侧 quote 与可替换的 numberText")
    void 同主体不同值命中() {
        String doc = "标的公司名下房产共 58 项。……附表二：房产明细共 39 项。";
        List<Claim> claims = List.of(
                claim("标的公司", "房产", "58", "项", "标的公司名下房产共 58 项"),
                claim("标的公司", "房产", "39", "项", "附表二：房产明细共 39 项"));

        List<Finding> out = DocInsightChecks.countMismatches(claims, doc);
        assertEquals(1, out.size());
        Finding f = out.get(0);
        assertEquals(DocInsightChecks.KIND_COUNT_MISMATCH, f.kind());
        assertEquals(DocInsightChecks.SEVERITY_WARN, f.severity());
        assertTrue(f.title().contains("58项") || f.title().contains("58 项"), f.title());

        // detail.subject 是归一键（剥掉了组织形式后缀「公司」），给前端分组用；
        // 给人看的是 title，那里保留原文写法
        assertEquals("标的", f.detail().get("subject"));
        assertTrue(f.title().startsWith("标的公司"), f.title());
        assertEquals("房产", f.detail().get("metric"));
        assertEquals("项", f.detail().get("unit"));
        List<Map<String, Object>> rows = claimsOf(f);
        assertEquals(2, rows.size());
        assertEquals(Boolean.TRUE, rows.get(0).get("fixable"));
        assertEquals("58", rows.get(0).get("numberText"));
        assertEquals(new BigDecimal("58"), rows.get(0).get("value"));
        assertEquals("标的公司名下房产共 58 项", rows.get(0).get("quote"));
        assertEquals("39", rows.get(1).get("numberText"));
    }

    @Test
    @DisplayName("不同主体的同名指标不合并（宁可漏报不误报）")
    void 不同主体不误报() {
        String doc = "甲公司房产 58 项；乙公司房产 39 项。";
        List<Claim> claims = List.of(
                claim("甲公司", "房产", "58", "项", "甲公司房产 58 项"),
                claim("乙公司", "房产", "39", "项", "乙公司房产 39 项"));
        assertTrue(DocInsightChecks.countMismatches(claims, doc).isEmpty());
    }

    @Test
    @DisplayName("剥掉组织形式后缀后全等算同一主体")
    void 后缀剥离等价() {
        String doc = "京微资易科技有限公司房产 58 项；京微资易科技房产 39 项。";
        List<Claim> claims = List.of(
                claim("京微资易科技有限公司", "房产", "58", "项", "京微资易科技有限公司房产 58 项"),
                claim("京微资易科技", "房产", "39", "项", "京微资易科技房产 39 项"));
        assertEquals(1, DocInsightChecks.countMismatches(claims, doc).size());
        assertEquals(DocInsightChecks.normalizeSubject("京微资易科技（集团）有限公司"),
                DocInsightChecks.normalizeSubject("京微资易科技"));
    }

    @Test
    @DisplayName("基准单位不同（项 / 人）根本不可比，不进同一组")
    void 单位不可比不报() {
        String doc = "员工 58 人；房产 58 项。";
        List<Claim> claims = List.of(
                claim("标的公司", "数量", "58", "人", "员工 58 人"),
                claim("标的公司", "数量", "39", "项", "房产 39 项"));
        assertTrue(DocInsightChecks.countMismatches(claims, doc).isEmpty());
    }

    @Test
    @DisplayName("同一个数说两遍不算矛盾")
    void 同值重复不报() {
        String doc = "房产 58 项。……仍为 58 项。";
        List<Claim> claims = List.of(
                claim("标的公司", "房产", "58", "项", "房产 58 项"),
                claim("标的公司", "房产", "58", "项", "仍为 58 项"));
        assertTrue(DocInsightChecks.countMismatches(claims, doc).isEmpty());
    }

    @Test
    void 空输入不炸() {
        assertTrue(DocInsightChecks.countMismatches(null, "x").isEmpty());
        assertTrue(DocInsightChecks.countMismatches(List.of(), "x").isEmpty());
        assertTrue(DocInsightChecks.usccIssues(null).isEmpty());
        assertTrue(DocInsightChecks.usccIssues("").isEmpty());
        assertTrue(DocInsightChecks.run(List.of(), null).isEmpty());
    }

    // ---------------------------------------------------------------- fixable 降级

    @Test
    @DisplayName("numberText 不是 quote 的逐字子串 → fixable:false，且不下发 numberText")
    void 数字原文不在引文里就降级() {
        String doc = "标的公司名下房产共 1,000 项。附表：房产 39 项。";
        List<Claim> claims = List.of(
                // 模型把「1,000」改写成了「1000」——前端 replace 会找不到，必须降级
                new Claim("标的公司", "房产", new BigDecimal("1000"), "项",
                        "标的公司名下房产共 1,000 项", "1000"),
                claim("标的公司", "房产", "39", "项", "附表：房产 39 项"));

        List<Map<String, Object>> rows = claimsOf(DocInsightChecks.countMismatches(claims, doc).get(0));
        assertEquals(Boolean.FALSE, rows.get(0).get("fixable"));
        assertNull(rows.get(0).get("numberText"), "不可修复时绝不下发对不上的 numberText");
        assertNotNull(rows.get(0).get("fixableReason"));
        // 同一条 finding 里另一条仍然可修复，降级是逐条的
        assertEquals(Boolean.TRUE, rows.get(1).get("fixable"));
        assertEquals("39", rows.get(1).get("numberText"));
    }

    @Test
    @DisplayName("quote 在文档原文里对不上（模型改写过）→ fixable:false，条目仍然展示")
    void 引文对不上原文就降级() {
        String doc = "标的公司名下房产共 58 项。附表：房产 39 项。";
        List<Claim> claims = List.of(
                // 逐字摘抄要求没做到：多了个「的」
                claim("标的公司", "房产", "58", "项", "标的公司名下的房产共 58 项"),
                claim("标的公司", "房产", "39", "项", "附表：房产 39 项"));

        List<Finding> out = DocInsightChecks.countMismatches(claims, doc);
        assertEquals(1, out.size(), "定位不了也要报这个矛盾，只是不给一键修改");
        List<Map<String, Object>> rows = claimsOf(out.get(0));
        assertEquals(Boolean.FALSE, rows.get(0).get("fixable"));
        assertNull(rows.get(0).get("numberText"));
        assertEquals(Boolean.TRUE, rows.get(1).get("fixable"));
    }

    @Test
    @DisplayName("组内单位字面量不一致（万元 / 元）→ 整条不给一键修改，量级会被换错")
    void 单位不一致整条不可修复() {
        String doc = "注册资本 58 万元。附注：注册资本 39 元。";
        List<Claim> claims = List.of(
                claim("标的公司", "注册资本", "58", "万元", "注册资本 58 万元"),
                claim("标的公司", "注册资本", "39", "元", "注册资本 39 元"));

        List<Finding> out = DocInsightChecks.countMismatches(claims, doc);
        assertEquals(1, out.size());
        for (Map<String, Object> row : claimsOf(out.get(0))) {
            assertEquals(Boolean.FALSE, row.get("fixable"));
            assertNull(row.get("numberText"));
        }
    }

    @Test
    @DisplayName("同一个值出现多处时，挑能定位能替换的那一处当代表")
    void 代表claim优先挑可修复的() {
        String doc = "标的公司名下房产共 58 项。前文亦称房产 58 项。附表：房产 39 项。";
        List<Claim> claims = List.of(
                // 同一个 58：第一处引文被模型改写过（原文没有「的」），第二处是逐字的
                claim("标的公司", "房产", "58", "项", "标的公司名下的房产共 58 项"),
                claim("标的公司", "房产", "58", "项", "前文亦称房产 58 项"),
                claim("标的公司", "房产", "39", "项", "附表：房产 39 项"));

        List<Map<String, Object>> rows = claimsOf(DocInsightChecks.countMismatches(claims, doc).get(0));
        assertEquals(2, rows.size(), "每个值只出一条代表");
        assertEquals("前文亦称房产 58 项", rows.get(0).get("quote"));
        assertEquals(Boolean.TRUE, rows.get(0).get("fixable"));
    }

    @Test
    @DisplayName("缺 numberText 也降级而不是丢条目")
    void 缺数字原文也降级() {
        String doc = "房产 58 项。附表：房产 39 项。";
        List<Claim> claims = List.of(
                new Claim("标的公司", "房产", new BigDecimal("58"), "项", "房产 58 项", null),
                claim("标的公司", "房产", "39", "项", "附表：房产 39 项"));
        List<Map<String, Object>> rows = claimsOf(DocInsightChecks.countMismatches(claims, doc).get(0));
        assertEquals(Boolean.FALSE, rows.get(0).get("fixable"));
    }

    // ---------------------------------------------------------------- 统一社会信用代码

    /** 真实在用的 18 位码，校验位由 GB 32100-2015 权重表独立算过（与 EvidenceChecksTest 同源）。 */
    static final String USCC_OK = "91330100799655058B";

    @Test
    @DisplayName("校验位不符 → USCC_INVALID（error 档），合法码不报")
    void 统一社会信用代码校验位() {
        String bad = "91330100799655058C";   // 只改末位校验位
        List<Finding> out = DocInsightChecks.usccIssues("受让方（统一社会信用代码 " + bad + "）与出让方 " + USCC_OK + " 签署。");
        assertEquals(1, out.size());
        Finding f = out.get(0);
        assertEquals(DocInsightChecks.KIND_USCC_INVALID, f.kind());
        assertEquals(DocInsightChecks.SEVERITY_ERROR, f.severity());
        assertEquals(bad, f.detail().get("code"));
        assertEquals(Boolean.FALSE, f.detail().get("fixable"), "没有『正确值』可建议，永远不给一键修改");
        assertTrue(String.valueOf(f.detail().get("quote")).contains(bad), "quote 要能定位回原文");
    }

    @Test
    @DisplayName("同一个坏码出现多次只报一条")
    void 坏码去重() {
        String bad = "91330100799655058C";
        assertEquals(1, DocInsightChecks.usccIssues(bad + " 前略，后又见 " + bad).size());
    }

    @Test
    @DisplayName("紧贴字母数字的串不当代码看（哈希/base64 片段）")
    void 相邻字母不误判() {
        assertTrue(DocInsightChecks.usccIssues("abc91330100799655058Cdef").isEmpty());
    }

    @Test
    @DisplayName("run 同时给出两类发现")
    void 两类一起跑() {
        String doc = "标的公司房产 58 项，附表 39 项；代码 91330100799655058C。";
        List<Claim> claims = List.of(
                claim("标的公司", "房产", "58", "项", "标的公司房产 58 项"),
                claim("标的公司", "房产", "39", "项", "附表 39 项"));
        List<Finding> out = DocInsightChecks.run(claims, doc);
        assertEquals(2, out.size());
        assertTrue(out.stream().anyMatch(f -> DocInsightChecks.KIND_COUNT_MISMATCH.equals(f.kind())));
        assertTrue(out.stream().anyMatch(f -> DocInsightChecks.KIND_USCC_INVALID.equals(f.kind())));
    }

    @Test
    void 括号内容与空白不影响主体归一() {
        assertEquals(DocInsightChecks.normalizeSubject("某某 科技（集团）有限公司"),
                DocInsightChecks.normalizeSubject("某某科技"));
        assertFalse(DocInsightChecks.normalizeSubject("甲公司").equals(DocInsightChecks.normalizeSubject("乙公司")));
    }
}
