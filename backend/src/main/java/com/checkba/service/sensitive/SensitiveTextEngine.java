// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.sensitive;

import com.checkba.model.SensitiveType;
import java.util.*;
import java.util.regex.*;

/** Request-scoped, offline recognizer. All spans refer to the original text, never to earlier replacements. */
public final class SensitiveTextEngine {
    public record Edit(int start, int end, String replacement) {}
    private record Hit(int start, int end, String code, String value) {}
    public static final Pattern TOKEN = Pattern.compile("\\[\\[[^\\[\\]\\r\\n]{1,80}_[0-9a-f]{12}\\]\\]");
    private static final Pattern ENGLISH_COMPANY = Pattern.compile("(?<![A-Za-z0-9])(?:[A-Z][A-Za-z0-9&'-]*[ \\t]+){1,7}(?:Co\\.,?\\h*Ltd\\.?|Ltd\\.?|Limited|Inc\\.?|Corporation|LLC)(?![A-Za-z0-9])");
    private final Set<String> strategies;
    private final Set<String> excluded;
    private final Map<String, String> known = new LinkedHashMap<>();
    private final Map<String, String> valueTokens = new LinkedHashMap<>();
    private final Map<String, String> recovery = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final boolean reversible;
    private String scope = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    public SensitiveTextEngine(List<String> strategies, boolean reversible, List<String> custom, List<String> excluded) {
        this.strategies = new HashSet<>(strategies);
        this.reversible = reversible;
        this.excluded = new HashSet<>(excluded);
        custom.forEach(value -> known.put(value, "CUSTOM"));
    }

    public void learn(String fullText) {
        while (fullText.contains("_" + scope + "]]")) {
            scope = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        for (Hit hit : detect(fullText)) {
            if (Set.of("CHINESE_NAME", "COMPANY").contains(hit.code) && !excluded.contains(hit.value)) {
                known.putIfAbsent(hit.value, hit.code);
            }
        }
    }

    private List<Hit> detect(String text) {
        List<Hit> hits = new ArrayList<>();
        for (SensitiveType type : SensitiveType.values()) {
            if (!type.isAutoDetect() || !strategies.contains(type.getCode())) continue;
            Matcher matcher = type.getPattern().matcher(text);
            while (matcher.find()) {
                int start = matcher.start(), end = matcher.end();
                if (Set.of("CHINESE_NAME", "PASSWORD", "ADDRESS").contains(type.getCode())) {
                    start = matcher.start("value"); end = matcher.end("value");
                }
                if (type == SensitiveType.COMPANY) {
                    // Strip syntactic introductions, not arbitrary surname-like Chinese fragments.
                    String candidate = text.substring(start, end);
                    Matcher prefix = Pattern.compile("^(?:(?:本合同|本协议|该合同)?由|(?:原告|被告|甲方|乙方|丙方|公司名称|企业名称)|与|及|向|委托|系)").matcher(candidate);
                    while (prefix.find()) {
                        start += prefix.end(); candidate = text.substring(start, end); prefix.reset(candidate);
                    }
                    if (candidate.length() < 6) continue;
                }
                String value = text.substring(start, end);
                if (type.isPlausible(value) && !excluded.contains(value)) {
                    hits.add(new Hit(start, end, type.getCode(), value));
                }
            }
        }
        if (strategies.contains("COMPANY")) {
            Matcher matcher = ENGLISH_COMPANY.matcher(text);
            while (matcher.find()) if (!excluded.contains(matcher.group())) {
                hits.add(new Hit(matcher.start(), matcher.end(), "COMPANY", matcher.group()));
            }
        }
        return hits;
    }

    public List<Edit> edits(String text) {
        List<Hit> hits = detect(text);
        known.forEach((value, code) -> {
            int from = 0, at;
            while ((at = text.indexOf(value, from)) >= 0) {
                if (!excluded.contains(value)) hits.add(new Hit(at, at + value.length(), code, value));
                from = at + value.length();
            }
        });
        // Custom overrides first, then longer entities (company before fragments / overlapping numeric rules).
        hits.sort(Comparator.<Hit>comparingInt(h -> h.code.equals("CUSTOM") ? 0 : 1)
                .thenComparing(Comparator.comparingInt((Hit h) -> h.end - h.start).reversed())
                .thenComparingInt(Hit::start).thenComparing(Hit::code));
        BitSet occupied = new BitSet(text.length());
        Matcher token = TOKEN.matcher(text);
        while (token.find()) occupied.set(token.start(), token.end());
        List<Hit> accepted = new ArrayList<>();
        for (Hit hit : hits) {
            int next = occupied.nextSetBit(hit.start);
            if (next >= 0 && next < hit.end) continue;
            occupied.set(hit.start, hit.end);
            accepted.add(hit);
        }
        accepted.sort(Comparator.comparingInt(Hit::start));
        List<Edit> edits = new ArrayList<>();
        for (Hit hit : accepted) {
            SensitiveType type = SensitiveType.fromCode(hit.code);
            String label = type == null ? "自定义" : type.getLabel();
            String replacement;
            if (reversible) {
                replacement = valueTokens.computeIfAbsent(hit.value, value -> {
                    String key = "[[" + label + (valueTokens.size() + 1) + "_" + scope + "]]";
                    recovery.put(key, value);
                    return key;
                });
            } else {
                replacement = type == null ? "*".repeat(hit.value.length()) : type.mask(hit.value);
            }
            edits.add(new Edit(hit.start, hit.end, replacement));
            counts.merge(hit.code, 1, Integer::sum);
        }
        return edits;
    }

    public String apply(String text) { return apply(text, edits(text)); }
    public Map<String, String> recovery() { return Map.copyOf(recovery); }
    public Map<String, Integer> counts() { return Map.copyOf(counts); }

    public static String apply(String text, List<Edit> edits) {
        StringBuilder result = new StringBuilder(text);
        for (int i = edits.size() - 1; i >= 0; i--) {
            Edit edit = edits.get(i);
            result.replace(edit.start, edit.end, edit.replacement);
        }
        return result.toString();
    }

    /** One pass prevents replacing an original value that happens to contain another token. */
    public static List<Edit> restoreEdits(String text, Map<String, String> recovery) {
        List<Edit> edits = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String original = recovery.get(matcher.group());
            if (original != null) edits.add(new Edit(matcher.start(), matcher.end(), original));
        }
        return edits;
    }
}
