// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.writing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.checkba.service.writing.WritingTypes.*;

@Component
public class LitigationWritingProfile implements WritingProfile {
    private static final Pattern PARTY = Pattern.compile("^(原告|被告|第三人)[：:]\\s*([^，,。；;：:（）()\\d]{2,60})(?:[，,。；;（(].*)?$");
    private static final Pattern CLAIM = Pattern.compile("^(原告|被告|第三人)(?:主张|辩称|抗辩|认为)[：:]?\\s*(.{2,600})$");
    private static final Pattern AMOUNT = Pattern.compile("^(借款本金|尚欠本金|已还本金)[：:]\\s*((?:人民币)?[0-9][0-9,，]*(?:\\.[0-9]{1,2})?\\s*(?:万元|亿元|元))[。；;]?$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DATE = Pattern.compile("^(借款日期|还款期限|转账日期)[：:]\\s*(\\d{4}年\\d{1,2}月\\d{1,2}日)[。；;]?$");
    private static final Pattern FIELD = Pattern.compile("(?:^|[\\n。；;])\\s*(原告|被告|第三人|借款本金|尚欠本金|已还本金|借款日期|还款期限|转账日期)[：:]\\s*([^\\n，,。；;]*)$");
    private static final Set<String> INACTIVE = Set.of("STALE", "EXPIRED", "CONFLICT", "MISSING", "UNVERIFIED");
    // The profile has no court-decision verifier: it must not certify a finding or finality.
    private static final Pattern CERTAINTY = Pattern.compile("法院认定|法院已查明|本院(?:认定|查明|认为)|经审理查明|已经?查明|事实(?:已经|已)查明|双方(?:已)?(?:确认|认可|无争议)|已生效|足以认定|已经证明|毫无疑问|必然胜诉|应当支持全部|全部请求成立");
    public String id() { return "litigation"; }
    public String label() { return "起诉状与代理词"; }
    public int score(Context context, List<Source> sources) {
        String explicit = WritingTypes.clean(context.documentType());
        if (explicit.matches(".*(?:起诉状|代理词|答辩状|litigation).*")) return 100;
        if (WritingTypes.clean(context.title()).matches(".*(?:起诉状|代理词|答辩状).*")) return 90;
        return 0;
    }

    /** Recognize explicit labels only. A narrative mention is not a party register. */
    public List<Fact> facts(Context context, List<Source> sources) {
        List<Fact> out = new ArrayList<>();
        for (Source source : sources) {
            if (source == null || WritingTypes.clean(source.id()).isEmpty()) continue;
            int index = 0;
            for (String raw : WritingTypes.clean(source.text()).split("\\R")) {
                String line = raw.strip();
                if (line.isEmpty() || line.length() > 700) continue;
                Matcher m = PARTY.matcher(line);
                String kind, label, value, role = "", status = "DOCUMENT_STATES";
                if (m.matches()) {
                    kind = "PARTY"; label = m.group(1); role = label; value = m.group(2).strip();
                } else if ((m = CLAIM.matcher(line)).matches()) {
                    kind = "CLAIM"; role = m.group(1); label = role + "主张"; value = m.group(2).strip(); status = "CLAIM";
                } else if ((m = AMOUNT.matcher(line)).matches()) {
                    kind = "AMOUNT"; label = m.group(1); value = m.group(2).strip();
                } else if ((m = DATE.matcher(line)).matches()) {
                    kind = "EVENT"; label = m.group(1); value = m.group(2);
                } else continue;
                out.add(new Fact("litigation:" + source.id() + ":" + index++, kind, label, value, role, status, source.id(), line));
            }
        }
        return List.copyOf(out);
    }

    public List<Advice> deterministic(Context context, List<Fact> facts) {
        if (!WritingTypes.clean(context.after()).isEmpty()) return List.of();
        Matcher field = FIELD.matcher(WritingTypes.clean(context.before()));
        if (!field.find()) return List.of();
        String label = field.group(1), prefix = field.group(2);
        List<Fact> candidates = forLabel(facts, label);
        Map<String, Fact> unique = new LinkedHashMap<>();
        for (Fact fact : candidates) unique.putIfAbsent(fact.value(), fact);
        // A partial prefix must not conceal conflicting values in the same field.
        if (unique.size() != 1) return List.of();
        Fact fact = unique.values().iterator().next();
        if (!fact.value().startsWith(prefix) || fact.value().length() <= prefix.length()) return List.of();
        return List.of(new Advice(fact.value().substring(prefix.length()), "PARTY".equals(fact.kind()) ? "entity" : "fact",
                List.of(fact.id()), List.of(fact.sourceId()), "沿用材料中的“" + label + "”记载；材料记载不等于双方认可或法院认定。"));
    }

    private static List<Fact> forLabel(List<Fact> facts, String label) {
        List<Fact> matches = facts.stream().filter(LitigationWritingProfile::usable)
                .filter(f -> label.equals(f.label()) && !"CLAIM".equals(f.status())).toList();
        // Active document definitions take precedence over roles from historical pleadings.
        if (matches.stream().anyMatch(f -> "PARTY".equals(f.kind()) && "active-document".equals(f.sourceId()))) {
            return matches.stream().filter(f -> "active-document".equals(f.sourceId())).toList();
        }
        return matches;
    }

    private static boolean usable(Fact f) {
        return f != null && !INACTIVE.contains(WritingTypes.clean(f.status()))
                && !WritingTypes.clean(f.sourceId()).isEmpty() && !WritingTypes.clean(f.quote()).isEmpty()
                && !WritingTypes.clean(f.value()).isEmpty();
    }

    public String instructions(Context context, List<Fact> facts) {
        String stance = WritingTypes.clean(context.stance());
        return "起诉状与代理词：当前写作立场为" + (stance.isEmpty() ? "未指定；不得猜测代理对象" : stance)
                + "。章节：" + WritingTypes.clean(context.section())
                + "。仅建议光标处待插入后缀，不能重复前文、改写既有文字或新增诉讼请求。"
                + "材料中的记载、我方主张、对方主张、双方认可与法院认定是不同层次。CLAIM必须保留主张方，不能升级为已查明事实。"
                + "DOCUMENT_STATES只表示材料这样写，不能表述为已证实。当前文档中的主体定义优先于历史材料。"
                + "缺少来源、不同来源金额冲突或代理对象不明时不猜测；不得虚构法院、案号、证据编号、利率、利息起算日、保证或连带责任。"
                + "本金、利息、违约金分别处理，不根据两个金额自行决定应请求金额。只引用给出的事实ID与来源ID。"
                + "本通道未验证裁判效力，不得声称法院已认定、裁判已生效或作无条件胜诉结论。";
    }

    public List<String> validate(Context context, List<Fact> facts, Advice advice) {
        List<String> errors = new ArrayList<>();
        if (advice == null || WritingTypes.clean(advice.text()).isEmpty()) return List.of("EMPTY_SUGGESTION");
        String text = advice.text();
        String before = context.before() == null ? "" : context.before();
        String joined = before + text;
        int boundary = -1;
        for (char punctuation : new char[] {'。', '！', '？', '；', ';', '\n'}) boundary = Math.max(boundary, before.lastIndexOf(punctuation));
        String currentSentence = before.substring(boundary + 1) + text;
        if (CERTAINTY.matcher(currentSentence).find()) errors.add("UNVERIFIED_ADJUDICATION_OR_CERTAINTY");
        List<Fact> cited = new ArrayList<>();
        if (advice.factIds() == null || advice.factIds().isEmpty()) errors.add("MISSING_FACT_REFERENCE");
        else for (String id : advice.factIds()) {
            Fact f = facts.stream().filter(x -> id.equals(x.id())).findFirst().orElse(null);
            if (!usable(f)) errors.add("UNKNOWN_OR_STALE_FACT");
            else {
                cited.add(f);
                if (advice.sourceIds() == null || !advice.sourceIds().contains(f.sourceId())) errors.add("MISSING_SOURCE_REFERENCE");
                if ("CLAIM".equals(f.status()) && !attributedClauses(currentSentence, f.role())) {
                    errors.add("CLAIM_ATTRIBUTION_REQUIRED");
                }
            }
        }
        for (String role : List.of("原告", "被告", "第三人")) {
            List<Fact> expected = forLabel(facts, role);
            for (Fact f : facts) {
                if (!usable(f) || !"PARTY".equals(f.kind()) || role.equals(f.role())) continue;
                if (expected.stream().anyMatch(x -> x.value().equals(f.value()))) continue;
                if (Pattern.compile(Pattern.quote(role) + "[：:]?\\s*" + Pattern.quote(f.value())).matcher(joined).find()) errors.add("PARTY_ROLE_MISMATCH");
            }
        }
        for (Fact f : cited) {
            if (!"CLAIM".equals(f.status()) && forLabel(facts, f.label()).stream().map(Fact::value).distinct().count() > 1) errors.add("CONFLICTING_FACT_VALUES");
        }
        return errors.stream().distinct().toList();
    }

    private static boolean attributedClauses(String text, String role) {
        Pattern attribution = Pattern.compile(Pattern.quote(role) + "(?:主张|辩称|抗辩|认为|称|陈述|否认)");
        Pattern reservation = Pattern.compile("^(?:该|上述|相关)(?:主张|说法|陈述)(?:尚待|仍需|有待)(?:核实|核查|确认)[。！？，,]*$");
        for (String clause : text.split("[。！？；;\\n]|但是|然而|但")) {
            if (clause.isBlank() || reservation.matcher(clause.strip()).matches()) continue;
            Matcher attributed = attribution.matcher(clause);
            if (!attributed.find()) return false;
            // Attribution governs what follows it; a later claim cannot qualify an earlier assertion.
            String lead = clause.substring(0, attributed.start()).strip();
            lead = lead.replaceFirst("^(?:借款本金|尚欠本金|已还本金|借款日期|还款期限|转账日期)[：:]\\s*", "");
            if (!lead.matches("(?:根据|据|按照)?")) return false;
        }
        return true;
    }
}
