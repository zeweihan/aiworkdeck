package com.checkba.service.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Map;

/**
 * 锚点文字归一化 + sha256。
 *
 * 规则（前后端共用，改一处必须同步另一处并更新向量 fixtures/anchor-hash-vectors.json）：
 * NFKC → 删除全部 Unicode 空白（含 U+3000）→ 中文标点映射（，。；：！？（））→ 引号一律删 → 保留《》。
 * 前端实现：frontend/src/utils/anchorHash.js。
 *
 * 注意 NFKC 已把全角逗号 U+FF0C 归成 ','，所以 PUNCT 里的 '，' 分支基本不命中；
 * 句号 U+3002 NFKC 不变，必须靠表——向量第 1 条就是为了钉这个。
 */
public final class AnchorHash {

    private static final Map<Character, String> PUNCT = Map.ofEntries(
            Map.entry('，', ","), Map.entry('。', "."), Map.entry('；', ";"), Map.entry('：', ":"),
            Map.entry('！', "!"), Map.entry('？', "?"), Map.entry('（', "("), Map.entry('）', ")"),
            Map.entry('「', ""), Map.entry('」', ""), Map.entry('『', ""), Map.entry('』', ""),
            Map.entry('“', ""), Map.entry('”', ""), Map.entry('‘', ""), Map.entry('’', ""),
            Map.entry('"', ""), Map.entry('\'', ""));

    private AnchorHash() {}

    public static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isWhitespace(c) || c == '　') continue;
            String m = PUNCT.get(c);
            if (m != null) b.append(m); else b.append(c);
        }
        return b.toString();
    }

    /** 64 位小写 hex。 */
    public static String of(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(normalize(s).getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder(64);
            for (byte x : d) h.append(String.format("%02x", x));
            return h.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
