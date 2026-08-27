package com.checkba.service.evidence;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 勾稽核查的纯判定层（dev-board#116，P2）：拿「报告里的一句陈述」与「底稿全文」比对，
 * 只做四类<b>可机器校验</b>的要素——统一社会信用代码、日期、金额与比例、主体名。
 *
 * <p><b>刻意不做</b>：自然语言语义判定、不调 LLM。判不了的一律记 {@code ok=null}，
 * 由调用方合成 {@code unverifiable}。
 *
 * <h3>缺证据 ≠ 矛盾（evidence.retrieve.v1 既有不变式，别破）</h3>
 * 底稿里找不到某个要素只能记「查不到」（{@code ok=null}），不得判 {@code contradicts}。
 * 只有两种情形才是真矛盾：
 * <ol>
 *   <li>陈述里的统一社会信用代码<b>自身校验位不符</b>——这是陈述的硬错，与底稿无关；</li>
 *   <li>陈述与底稿里都有统一社会信用代码、但<b>值不一样</b>——18 位码自带身份，
 *       两边同时存在且不等就是挂错底稿/写错代码。</li>
 * </ol>
 * 日期、金额、主体名都<b>不</b>产生矛盾：同一份材料里日期和数字可以有很多个，
 * 「这份材料里没有这个数」与「这份材料说的是另一个数」在没有语义配对时区分不了，
 * 宁可报 unverifiable 让律师自己看，也不能栽一个假矛盾。
 */
public final class EvidenceChecks {

    public static final String KIND_USCC = "uscc";
    public static final String KIND_DATE = "date";
    public static final String KIND_AMOUNT = "amount";
    public static final String KIND_RATIO = "ratio";
    public static final String KIND_PARTY = "party";

    public static final String VERDICT_SUPPORTS = "supports";
    public static final String VERDICT_PARTIAL = "partial";
    public static final String VERDICT_CONTRADICTS = "contradicts";
    public static final String VERDICT_UNVERIFIABLE = "unverifiable";

    /** 一条要素核查结果。{@code ok=null} = 底稿里查不到（缺证据），不是矛盾。 */
    public record Check(String kind, String expected, String found, Boolean ok, String note) {}

    /** 主体（PARTY 标签）：全称 + 别名。 */
    public record Party(String name, List<String> aliases) {}

    private EvidenceChecks() {}

    // ---------------------------------------------------------------- 统一社会信用代码

    /** GB 32100-2015 的 31 位字符集：0-9 与除 I/O/S/V/Z 外的大写字母。 */
    private static final String USCC_CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    private static final int[] USCC_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
    /** 第 1-2 位登记管理部门+机构类别、3-8 行政区划码（纯数字）、9-17 主体标识码、18 校验位。 */
    private static final Pattern USCC = Pattern.compile(
            "(?<![0-9A-Z])[0-9A-HJ-NP-RTUWXY]{2}[0-9]{6}[0-9A-HJ-NP-RTUWXY]{10}(?![0-9A-Z])");

    /**
     * 统一社会信用代码的形状正则，供同类扫描复用（{@code service/insight/DocInsightChecks}
     * 要按<b>匹配位置</b>取上下文做定位，所以拿的是 Pattern 而不是结果集）。
     * 抄一份正则出去必然漂移——同一份代码形状全仓只此一处。
     */
    public static Pattern usccPattern() {
        return USCC;
    }

    /** 18 位、字符集合法、校验位相符。 */
    public static boolean usccValid(String code) {
        if (code == null || code.length() != 18) return false;
        String s = code.toUpperCase(Locale.ROOT);
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int v = USCC_CHARS.indexOf(s.charAt(i));
            if (v < 0) return false;
            sum += v * USCC_WEIGHTS[i];
        }
        int expect = 31 - (sum % 31);
        if (expect == 31) expect = 0;
        int last = USCC_CHARS.indexOf(s.charAt(17));
        return last >= 0 && last == expect;
    }

    // ---------------------------------------------------------------- 别名

    /** description 里被认作别名清单的标签前缀；不带标签的自由说明不拆（拿一句话去底稿里瞎命中比没有更糟）。 */
    private static final Pattern ALIAS_LABEL = Pattern.compile("^\\s*(别名|简称|又名|曾用名|alias(es)?|aka)\\s*[:：]?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIAS_SPLIT = Pattern.compile("[、,，;；/／|｜\\r\\n]+");
    /** 组织形式后缀：去掉后得到派生简称（如「北京京微资易科技有限公司」→「北京京微资易科技」）。 */
    private static final Pattern ORG_SUFFIX = Pattern.compile(
            "(股份有限公司|有限责任公司|有限合伙企业|普通合伙企业|有限公司|集团有限公司|合伙企业|股份公司|分公司|公司|集团|事务所|中心|研究院)$");
    /** 显式声明的别名下限：律师自己写的「简称 京微」是有意为之，照收。 */
    private static final int ALIAS_EXPLICIT_MIN_LEN = 2;
    /** 派生简称下限：机器猜的短串在底稿里满地都是，四字起步。 */
    private static final int ALIAS_DERIVED_MIN_LEN = 4;
    private static final int ALIAS_MAX_LEN = 64;
    private static final int ALIAS_MAX_COUNT = 8;

    /**
     * 主体别名 = description 里显式列出的（须带「别名/简称/又名/曾用名/alias/aka」标签）
     * ∪ 全称去掉组织形式后缀的派生简称。
     */
    public static List<String> aliasesOf(String name, String description) {
        Set<String> out = new LinkedHashSet<>();
        if (description != null) {
            Matcher m = ALIAS_LABEL.matcher(description);
            if (m.find()) {
                for (String piece : ALIAS_SPLIT.split(description.substring(m.end()))) {
                    String a = stripAliasLabel(piece);
                    if (a.length() >= ALIAS_EXPLICIT_MIN_LEN && a.length() <= ALIAS_MAX_LEN && !a.equals(name)) out.add(a);
                    if (out.size() >= ALIAS_MAX_COUNT) break;
                }
            }
        }
        if (name != null && out.size() < ALIAS_MAX_COUNT) {
            String derived = stripOrgSuffix(name);
            if (derived.length() >= ALIAS_DERIVED_MIN_LEN && !derived.equals(name.trim())) out.add(derived);
        }
        return List.copyOf(out);
    }

    /**
     * 剥掉组织形式后缀（「北京京微资易科技有限公司」→「北京京微资易科技」）。
     * 主体归一的判据全仓共用这一份后缀表：{@link #aliasesOf} 派生简称与
     * {@code service/insight/DocInsightChecks} 判「是不是同一个主体」都调它。
     */
    public static String stripOrgSuffix(String name) {
        if (name == null) return "";
        return ORG_SUFFIX.matcher(name.trim()).replaceFirst("");
    }

    /** 段内可能还挂着一个标签（「简称 京微科技」），一并剥掉。 */
    private static String stripAliasLabel(String piece) {
        String s = piece == null ? "" : piece.trim();
        Matcher m = ALIAS_LABEL.matcher(s);
        return m.find() && m.start() == 0 ? s.substring(m.end()).trim() : s;
    }

    // ---------------------------------------------------------------- 日期 / 数字

    private static final Pattern DATE_CN = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern DATE_DASH = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");

    /** 底稿里的裸数字要达到这个量级才当金额看（下面 checkNumbers 里说明理由）。 */
    private static final BigDecimal BARE_NUMBER_FLOOR = new BigDecimal("1000");

    /** 数字 + 可选单位；千分位与小数点都收。陈述侧要求带单位，底稿侧单位可选。 */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\d.,])(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)\\s*(亿元|万元|亿|万|元|%)?");

    // ---------------------------------------------------------------- 主流程

    /**
     * 对一条陈述与一份底稿全文跑四类核查。陈述里抽不出任何要素时返回空列表（调用方判 unverifiable）。
     * 同一要素在陈述里重复出现只核一次。
     */
    public static List<Check> run(String statement, String draft, List<Party> parties) {
        List<Check> out = new ArrayList<>();
        if (statement == null || statement.isBlank()) return out;
        String stmtCompact = compact(statement);
        String stmtSpaced = spaced(statement);
        String draftCompact = draft == null ? "" : compact(draft);
        String draftSpaced = draft == null ? "" : spaced(draft);

        checkUscc(stmtCompact, draftCompact, out);
        checkDates(stmtCompact, draftCompact, out);
        checkNumbers(stmtSpaced, draftSpaced, out);
        checkParties(stmtCompact, draftCompact, parties, out);
        return out;
    }

    private static void checkUscc(String stmt, String draft, List<Check> out) {
        Set<String> inStatement = findUscc(stmt, false);
        if (inStatement.isEmpty()) return;
        Set<String> inDraft = findUscc(draft, true);
        for (String code : inStatement) {
            if (!usccValid(code)) {
                out.add(new Check(KIND_USCC, code, null, false, "统一社会信用代码校验位不符"));
                continue;
            }
            if (draft.contains(code)) {
                out.add(new Check(KIND_USCC, code, code, true, null));
            } else if (!inDraft.isEmpty()) {
                out.add(new Check(KIND_USCC, code, inDraft.iterator().next(), false, "底稿里的统一社会信用代码与陈述不一致"));
            } else {
                out.add(new Check(KIND_USCC, code, null, null, "底稿里未见统一社会信用代码"));
            }
        }
    }

    /** onlyValid=true 时只收校验位合法的（底稿侧：避免拿一串随机数字当成"另一个代码"去判矛盾）。 */
    private static Set<String> findUscc(String text, boolean onlyValid) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = USCC.matcher(text);
        while (m.find()) {
            String c = m.group();
            if (!onlyValid || usccValid(c)) out.add(c);
        }
        return out;
    }

    private static void checkDates(String stmt, String draftCompact, List<Check> out) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        Matcher m = DATE_CN.matcher(stmt);
        while (m.find()) addDate(dates, m.group(1), m.group(2), m.group(3));
        m = DATE_DASH.matcher(stmt);
        while (m.find()) addDate(dates, m.group(1), m.group(2), m.group(3));
        for (LocalDate d : dates) {
            String hit = null;
            for (String candidate : renderDate(d)) {
                if (draftCompact.contains(candidate)) { hit = candidate; break; }
            }
            String iso = d.toString();
            out.add(hit != null
                    ? new Check(KIND_DATE, iso, hit, true, null)
                    : new Check(KIND_DATE, iso, null, null, "底稿里未见该日期"));
        }
    }

    private static void addDate(Set<LocalDate> into, String y, String mo, String d) {
        try {
            into.add(LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d)));
        } catch (DateTimeException | NumberFormatException ignore) {
            // 2025年2月30日 这种不存在的日历日不当日期，静默丢弃
        }
    }

    /** 底稿里可能的八种写法（年月日 / - / / / . 各两种补零形态）。 */
    private static List<String> renderDate(LocalDate d) {
        int y = d.getYear();
        int mo = d.getMonthValue();
        int da = d.getDayOfMonth();
        String pm = String.format("%02d", mo);
        String pd = String.format("%02d", da);
        return List.of(
                y + "年" + mo + "月" + da + "日", y + "年" + pm + "月" + pd + "日",
                y + "-" + mo + "-" + da, y + "-" + pm + "-" + pd,
                y + "/" + mo + "/" + da, y + "/" + pm + "/" + pd,
                y + "." + mo + "." + da, y + "." + pm + "." + pd);
    }

    private static void checkNumbers(String stmt, String draft, List<Check> out) {
        Map<String, String> amounts = new LinkedHashMap<>();   // 归一值 → 陈述原文
        Map<String, String> ratios = new LinkedHashMap<>();
        Matcher m = NUMBER.matcher(stmt);
        while (m.find()) {
            String unit = m.group(2);
            if (unit == null) continue;   // 陈述侧裸数字不算可核对要素（「3 名股东」不是金额）
            BigDecimal v = parseNumber(m.group(1));
            if (v == null) continue;
            if ("%".equals(unit)) {
                ratios.putIfAbsent(key(v), m.group().trim());
            } else {
                amounts.putIfAbsent(key(v.multiply(scaleOf(unit))), m.group().trim());
            }
        }
        if (amounts.isEmpty() && ratios.isEmpty()) return;
        Set<String> draftAmounts = new LinkedHashSet<>();
        Set<String> draftRatios = new LinkedHashSet<>();
        m = NUMBER.matcher(draft);
        while (m.find()) {
            BigDecimal v = parseNumber(m.group(1));
            if (v == null) continue;
            String unit = m.group(2);
            if ("%".equals(unit)) {
                draftRatios.add(key(v));
                continue;
            }
            if (unit != null) {
                draftAmounts.add(key(v.multiply(scaleOf(unit))));
                continue;
            }
            // 底稿里的裸数字：只有"够大"才当金额。小数字满地都是（页码、条款号、人数），
            // 拿它去确认「注册资本 5 元」只会栽一个假的 supports——比报 unverifiable 坏得多。
            if (v.abs().compareTo(BARE_NUMBER_FLOOR) < 0) continue;
            // 年月日里的数字不是金额：2025-01-05 会被拆成 2025/1/5 三个裸数字
            int end = m.end(1);
            if (end < draft.length() && "年月日".indexOf(draft.charAt(end)) >= 0) continue;
            draftAmounts.add(key(v));
        }
        amounts.forEach((k, raw) -> out.add(draftAmounts.contains(k)
                ? new Check(KIND_AMOUNT, k, k, true, "陈述原文 " + raw)
                : new Check(KIND_AMOUNT, k, null, null, "底稿里未见等值金额（陈述原文 " + raw + "）")));
        // 比例只认底稿里带 % 的写法：表格里裸着的 51 更可能是页码或条款号
        ratios.forEach((k, raw) -> out.add(draftRatios.contains(k)
                ? new Check(KIND_RATIO, k + "%", k + "%", true, null)
                : new Check(KIND_RATIO, k + "%", null, null, "底稿里未见该比例")));
    }

    private static BigDecimal parseNumber(String raw) {
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal scaleOf(String unit) {
        return switch (unit) {
            case "亿元", "亿" -> new BigDecimal("100000000");
            case "万元", "万" -> new BigDecimal("10000");
            default -> BigDecimal.ONE;
        };
    }

    /** 1 / 1.0 / 1.00 归成同一个键，比较才不会被序列化习惯坑到。 */
    private static String key(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static void checkParties(String stmt, String draft, List<Party> parties, List<Check> out) {
        if (parties == null || parties.isEmpty()) return;
        Set<String> seen = new LinkedHashSet<>();
        for (Party p : parties) {
            if (p == null || p.name() == null || p.name().isBlank()) continue;
            String name = compact(p.name());
            if (name.isEmpty() || !seen.add(name)) continue;
            List<String> forms = new ArrayList<>();
            forms.add(name);
            if (p.aliases() != null) {
                for (String a : p.aliases()) {
                    String c = compact(a);
                    if (!c.isEmpty() && !forms.contains(c)) forms.add(c);
                }
            }
            boolean mentioned = forms.stream().anyMatch(stmt::contains);
            if (!mentioned) continue;
            String hit = forms.stream().filter(draft::contains).findFirst().orElse(null);
            out.add(hit != null
                    ? new Check(KIND_PARTY, p.name(), hit, true, null)
                    : new Check(KIND_PARTY, p.name(), null, null, "底稿里未见该主体名或别名"));
        }
    }

    // ---------------------------------------------------------------- 合成

    /**
     * 有任一 {@code ok=false} → contradicts；全部命中 → supports；有命中也有查不到 → partial；
     * 一条 check 都没有、或一条都没命中 → unverifiable。
     */
    public static String verdict(List<Check> checks) {
        if (checks == null || checks.isEmpty()) return VERDICT_UNVERIFIABLE;
        long bad = checks.stream().filter(c -> Boolean.FALSE.equals(c.ok())).count();
        if (bad > 0) return VERDICT_CONTRADICTS;
        long ok = checks.stream().filter(c -> Boolean.TRUE.equals(c.ok())).count();
        if (ok == 0) return VERDICT_UNVERIFIABLE;
        return ok == checks.size() ? VERDICT_SUPPORTS : VERDICT_PARTIAL;
    }

    /** contradicts=0；supports=100；partial 按命中比例四舍五入；unverifiable = null（不打分，不许猜）。 */
    public static Short confidence(List<Check> checks) {
        String v = verdict(checks);
        if (VERDICT_UNVERIFIABLE.equals(v)) return null;
        if (VERDICT_CONTRADICTS.equals(v)) return 0;
        long ok = checks.stream().filter(c -> Boolean.TRUE.equals(c.ok())).count();
        return (short) Math.round(100.0 * ok / checks.size());
    }

    // ---------------------------------------------------------------- 归一化

    /** NFKC + 删除全部空白：给「包含判断」用（PDF/OCR 抽出来的文字里空格与换行位置不可靠）。 */
    public static String compact(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isWhitespace(c) || c == '　') continue;
            b.append(Character.toUpperCase(c));
        }
        return b.toString();
    }

    /**
     * NFKC + 空白折成单个空格：给「数字扫描」用。
     * 不能用 compact——删空白会把跨行的两个数字粘成一个（"1,234" + 换行 + "56" → "1,23456"）。
     */
    static String spaced(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("\\s+", " ");
    }
}
