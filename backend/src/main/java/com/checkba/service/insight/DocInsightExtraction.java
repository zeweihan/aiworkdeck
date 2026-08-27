package com.checkba.service.insight;

import com.checkba.model.entity.DocInsightEntity;
import com.checkba.service.evidence.EvidenceChecks;
import com.checkba.service.insight.DocInsightChecks.Claim;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实体抽取层：切块、提示词、宽容解析、确定性预抽取与合并去重（dev-board#182）。
 *
 * <p>与 {@link DocInsightChecks} 一样是纯函数，<b>不碰 Spring、不碰 IO</b>，
 * 便于单测直接钉住形状；模型调用本身在 {@link DocInsightService} 里。
 *
 * <h3>为什么 LLM 与正则两条腿一起走</h3>
 * 案号、书名号法规名有<b>确定形状</b>，正则永远不会漏也不会编；而「这段散文里提到的公司」
 * 只有模型认得出来。两边各抽一份，按 normKey 合并——正则那份保证下限，模型那份补覆盖。
 */
final class DocInsightExtraction {

    /** 一处出处。paragraph 目前恒 null（抽取的是纯文本，段号不可靠），字段留着给将来带结构的抽取用。 */
    record Mention(String quote, Integer paragraph) {
    }

    /** 抽出来的一个实体。{@code article} 只对 LAW 有意义（条号），其余为 null。 */
    record RawEntity(String kind, String name, String normKey, String article, List<Mention> mentions) {
    }

    /** 一块的抽取结果。 */
    record Parsed(List<RawEntity> entities, List<Claim> claims) {
    }

    /** 单条 quote 的长度上限（前端要拿它定位，太长反而定位不上）。 */
    static final int MAX_QUOTE = 120;

    private DocInsightExtraction() {
    }

    // ---------------------------------------------------------------- 切块

    /** 按字符切块并留重叠。最后一块不足一块也照发。 */
    static List<String> chunks(String text, int size, int overlap) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        int chunk = Math.max(500, size);
        int lap = Math.max(0, Math.min(overlap, chunk / 2));
        int from = 0;
        while (from < text.length()) {
            int to = Math.min(text.length(), from + chunk);
            out.add(text.substring(from, to));
            if (to >= text.length()) break;
            from = to - lap;
        }
        return out;
    }

    // ---------------------------------------------------------------- 提示词

    /**
     * 抽取提示词。<b>约束句一律排在末位</b>——本仓实证弱模型会无视中段约束
     * （见 .claude/agents/ai-chat.md「约束要挂消息末位」）。
     */
    static String prompt(String chunk) {
        // 刻意用拼接而不是 formatted()：提示词里有「51%」这样的百分号，
        // 走格式化会被当成转换符直接抛 UnknownFormatConversionException，
        // 而调用方对单块失败是「跳过」——整个抽取会静默变成只剩正则那条腿
        return PROMPT_HEAD + chunk + PROMPT_TAIL;
    }

    private static final String PROMPT_HEAD = """
                你是法律文书解析助手。下面是一份文档的一个片段，请从中抽取四类信息，只输出一个 JSON 对象。

                JSON 形状（字段缺失时给空数组，不要编造）：
                {
                  "companies": [{"name": "企业全称或文中写法", "quote": "出现该企业的原文片段"}],
                  "laws": [{"name": "《中华人民共和国公司法》", "article": "第二十条", "quote": "原文片段"}],
                  "cases": [{"caseNo": "（2021）京01民终1234号", "title": "案件标题", "quote": "原文片段"}],
                  "claims": [{"subject": "主体", "metric": "指标", "value": 58, "unit": "项",
                              "numberText": "58", "quote": "原文片段"}]
                }

                claims 是「带数量的事实陈述」：房产 58 项、注册资本 1000 万元、股权比例 51%、员工 240 人。
                同一份文档里同一个主体的同一指标可能在正文与附表里各写一次——两处都要抽出来，
                这正是后续查前后矛盾的依据。

                片段正文：
                ---
                """;

    private static final String PROMPT_TAIL = """

                ---

                硬性要求（逐条遵守，违反其中任何一条这次抽取就作废）：
                1. 只输出 JSON 对象本身，不要 markdown 代码块，不要任何解释文字。
                2. quote 必须是上面正文里的**逐字摘抄**，一个字都不能改写、补全或翻译，长度不超过 120 字。
                3. numberText 必须是 quote 里**逐字出现**的数字原文子串：原文写「1,000」就给「1,000」，
                   写「五十八」就给「五十八」，不要改写成阿拉伯数字、不要去掉千分位逗号。
                4. value 是该数字的阿拉伯数字值（中文数字也换算成数字），unit 是紧跟其后的单位原文。
                5. 文中没有出现的企业、法规、案例、数字一律不要写进来。
                """;

    // ---------------------------------------------------------------- 宽容解析

    private static final Pattern FENCE = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");

    /**
     * 解析模型输出。剥 markdown 围栏、截首个 {@code {} 到末个 }}，坏了返回空结果——
     * 单块解析失败只丢这一块，绝不炸整轮（一份 60 页的合同不该因为第 3 块跑偏就整个失败）。
     */
    static Parsed parse(String raw, ObjectMapper om) {
        List<RawEntity> entities = new ArrayList<>();
        List<Claim> claims = new ArrayList<>();
        JsonNode root = readJson(raw, om);
        if (root == null || !root.isObject()) return new Parsed(entities, claims);

        for (JsonNode n : array(root, "companies")) {
            String name = text(n, "name");
            if (name.isBlank()) continue;
            entities.add(company(name, text(n, "quote")));
        }
        for (JsonNode n : array(root, "laws")) {
            String name = text(n, "name");
            if (name.isBlank()) continue;
            entities.add(law(name, text(n, "article"), text(n, "quote")));
        }
        for (JsonNode n : array(root, "cases")) {
            String caseNo = text(n, "caseNo");
            String title = text(n, "title");
            if (caseNo.isBlank() && title.isBlank()) continue;
            entities.add(caseRef(caseNo, title, text(n, "quote")));
        }
        for (JsonNode n : array(root, "claims")) {
            BigDecimal value = number(n.get("value"));
            if (value == null) value = DocInsightChecks.parseNumber(text(n, "numberText"));
            if (value == null) continue;
            claims.add(new Claim(text(n, "subject"), text(n, "metric"), value,
                    text(n, "unit"), clip(text(n, "quote")), text(n, "numberText")));
        }
        return new Parsed(entities, claims);
    }

    private static JsonNode readJson(String raw, ObjectMapper om) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        Matcher fence = FENCE.matcher(s);
        if (fence.find()) s = fence.group(1).trim();
        int from = s.indexOf('{');
        int to = s.lastIndexOf('}');
        if (from < 0 || to <= from) return null;
        try {
            return om.readTree(s.substring(from, to + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<JsonNode> array(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isArray()) return List.of();
        List<JsonNode> out = new ArrayList<>();
        n.forEach(item -> {
            if (item != null && item.isObject()) out.add(item);
        });
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static BigDecimal number(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        return DocInsightChecks.parseNumber(v.asText(""));
    }

    // ---------------------------------------------------------------- 确定性预抽取

    /** 案号：（2021）京01民终1234号。全角半角括号都收。 */
    static final Pattern CASE_NO = Pattern.compile("[（(]\\s*(19|20)\\d{2}\\s*[)）][^，。；\\s]{1,30}号");

    /**
     * 书名号里的规范性文件名。尾字限定在法规体裁上——《甲方》《附件一》这类书名号
     * 在合同里满地都是，不限定就会把它们全当法规去打法宝。
     */
    static final Pattern LAW_TITLE = Pattern.compile(
            "《([^《》]{2,60}?(?:法|条例|办法|规定|规则|细则|解释|准则|通知|意见|决定|批复|公告|标准|指引))》"
                    + "(?:\\s*第([一二三四五六七八九十百千零〇\\d]{1,10})条)?");

    /** 正则能认的两类实体（案号、书名号法规）。企业名没有确定形状，只能靠模型。 */
    static List<RawEntity> scanDeterministic(String text) {
        List<RawEntity> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        Matcher m = LAW_TITLE.matcher(text);
        while (m.find()) {
            String article = m.group(2) == null ? null : "第" + m.group(2) + "条";
            out.add(law("《" + m.group(1) + "》", article, around(text, m.start(), m.end())));
        }
        m = CASE_NO.matcher(text);
        while (m.find()) {
            out.add(caseRef(m.group(), "", around(text, m.start(), m.end())));
        }
        return out;
    }

    /** 取匹配前后各 30 字符作为出处引文（逐字，前端要拿它定位）。 */
    private static String around(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 30);
        return text.substring(from, to);
    }

    // ---------------------------------------------------------------- 构造与归一

    static RawEntity company(String name, String quote) {
        return new RawEntity(DocInsightEntity.KIND_COMPANY, name.trim(),
                DocInsightChecks.normalizeSubject(name), null, mentions(quote));
    }

    static RawEntity law(String name, String article, String quote) {
        String title = stripBookMarks(name);
        String art = article == null ? "" : article.trim();
        String display = art.isEmpty() ? "《" + title + "》" : "《" + title + "》" + art;
        return new RawEntity(DocInsightEntity.KIND_LAW, display,
                EvidenceChecks.compact(title) + "#" + EvidenceChecks.compact(art),
                art.isEmpty() ? null : art, mentions(quote));
    }

    static RawEntity caseRef(String caseNo, String title, String quote) {
        String no = caseNo == null ? "" : caseNo.trim();
        String display = no.isEmpty() ? title.trim() : no;
        // 案号归一：全角括号转半角 + 去空白，「（2021）」与「(2021) 」是同一个案号
        String key = EvidenceChecks.compact(no.isEmpty() ? title : no)
                .replace('（', '(').replace('）', ')');
        return new RawEntity(DocInsightEntity.KIND_CASE, display, key, null, mentions(quote));
    }

    private static String stripBookMarks(String s) {
        return s == null ? "" : s.replace("《", "").replace("》", "").trim();
    }

    private static List<Mention> mentions(String quote) {
        String q = clip(quote);
        return q.isEmpty() ? List.of() : List.of(new Mention(q, null));
    }

    static String clip(String quote) {
        if (quote == null) return "";
        String q = quote.trim();
        return q.length() <= MAX_QUOTE ? q : q.substring(0, MAX_QUOTE);
    }

    // ---------------------------------------------------------------- 合并

    /**
     * 按 (kind, normKey) 合并：出处累加去重（上限 maxMentions），展示名取<b>最长的那个</b>
     * （「某某科技」与「某某科技有限公司」归一后同键，全称对用户更有用，检索也更可能命中）。
     */
    static List<RawEntity> merge(List<RawEntity> all, int maxMentions, int maxEntities) {
        Map<String, RawEntity> byKey = new LinkedHashMap<>();
        for (RawEntity e : all) {
            if (e == null || e.normKey() == null || e.normKey().isBlank()) continue;
            String key = e.kind() + " " + e.normKey();
            RawEntity prior = byKey.get(key);
            if (prior == null) {
                byKey.put(key, new RawEntity(e.kind(), e.name(), e.normKey(), e.article(),
                        new ArrayList<>(e.mentions())));
                continue;
            }
            for (Mention mention : e.mentions()) {
                if (prior.mentions().size() >= maxMentions) break;
                if (prior.mentions().stream().noneMatch(x -> x.quote().equals(mention.quote()))) {
                    prior.mentions().add(mention);
                }
            }
            if (e.name() != null && e.name().length() > prior.name().length()) {
                byKey.put(key, new RawEntity(e.kind(), e.name(), e.normKey(),
                        prior.article() != null ? prior.article() : e.article(), prior.mentions()));
            } else if (prior.article() == null && e.article() != null) {
                byKey.put(key, new RawEntity(prior.kind(), prior.name(), prior.normKey(),
                        e.article(), prior.mentions()));
            }
        }
        List<RawEntity> out = new ArrayList<>(byKey.values());
        return out.size() <= maxEntities ? out : out.subList(0, maxEntities);
    }
}
