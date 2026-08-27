package com.checkba.service.insight;

import com.checkba.service.evidence.EvidenceChecks;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 条号与引用字样的纯函数（dev-board#181 升级件二）。
 *
 * <p>法宝 {@code adjust_provisions} 的 {@code article_number} 收<b>阿拉伯数字字符串</b>
 * （"15"、"20.1"），而文档里写的是「第二十条」「第二十条之一」。这里只做形式转换，
 * 不做任何法律判断；转不动一律返回 {@code null}（调用方据此跳过该实体，
 * 宁可不校验也不拿一个猜出来的条号去打上游）。
 *
 * <p>与 {@link DocInsightChecks} 同样是纯函数、不碰 Spring 与 IO，便于单测钉住形状。
 */
public final class LawArticleNumbers {

    /** 「第二十条之一」的「之一」尾巴（法条重编号时新增的条常带这个后缀）。 */
    private static final Pattern SUB = Pattern.compile(
            "^(.*?)之([一二三四五六七八九十百千零〇两0-9]+)$");

    /** 已经是阿拉伯数字形态（含「20.1」这种带之号的）。 */
    private static final Pattern ARABIC = Pattern.compile("^\\d{1,6}(?:\\.\\d{1,3})?$");

    /** 书名号连同里面的法规名。 */
    private static final Pattern BOOK_TITLE = Pattern.compile("《[^《》]{0,60}》");

    /** 「第二十条」「第20条之一」这类条号引用字样。 */
    private static final Pattern ARTICLE_REF = Pattern.compile(
            "第[一二三四五六七八九十百千零〇两0-9]{1,10}条(?:之[一二三四五六七八九十0-9]{1,4})?"
                    + "(?:第[一二三四五六七八九十百千零〇两0-9]{1,10}[款项目])*");

    /** 引用字样剥掉之后，句子两端剩下的标点与虚词（剥了它们才是「内容线索」）。 */
    private static final Pattern EDGE_NOISE = Pattern.compile(
            "^[\\s，,。.、；;：:（(）)]*(?:依据|根据|按照|参照|依照|违反了|违反|适用|的规定|规定)?[\\s，,。.、；;：:]*"
                    + "|[\\s，,。.、；;：:（(）)]*$");

    private LawArticleNumbers() {
    }

    /**
     * 中文/阿拉伯条号 → 阿拉伯数字字符串。
     * <pre>
     * 第二十条     → "20"      第二十条之一 → "20.1"
     * 二十一       → "21"      第9999条     → "9999"
     * 第十条       → "10"      一百零八     → "108"
     * </pre>
     *
     * @return 转不动（空串、认不出的字符、值为 0）返回 {@code null}
     */
    public static String toArabic(String article) {
        if (article == null) return null;
        String s = EvidenceChecks.compact(article);
        if (s.isEmpty()) return null;
        if (ARABIC.matcher(s).matches()) return s;

        String sub = null;
        Matcher m = SUB.matcher(s);
        if (m.matches()) {
            s = m.group(1);
            sub = m.group(2);
        }
        Long main = parse(strip(s));
        if (main == null || main <= 0) return null;
        if (sub == null) return String.valueOf(main);
        Long subNum = parse(strip(sub));
        if (subNum == null || subNum <= 0) return null;
        return main + "." + subNum;
    }

    /** 去掉「第…条」的壳，只留数词本身。 */
    private static String strip(String s) {
        String out = s;
        if (out.startsWith("第")) out = out.substring(1);
        if (out.endsWith("条")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /**
     * 中文数词 → 数值。十/百/千组合与阿拉伯数字混写都吃，遇到不认得的字符即返回 null
     * （「若干」「本」这类词不是条号，交给调用方跳过）。
     */
    private static Long parse(String s) {
        if (s == null || s.isEmpty()) return null;
        long total = 0;
        long num = 0;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int digit = digitOf(c);
            if (digit >= 0) {
                num = num * 10 + digit;
                any = true;
                continue;
            }
            int unit = unitOf(c);
            if (unit < 0) return null;
            if (num == 0) num = 1;     // 「十」= 10、「一百十」的隐含 1
            total += num * unit;
            num = 0;
            any = true;
        }
        return any ? total + num : null;
    }

    private static int digitOf(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        return switch (c) {
            case '零', '〇' -> 0;
            case '一', '壹' -> 1;
            case '二', '两', '贰' -> 2;
            case '三', '叁' -> 3;
            case '四', '肆' -> 4;
            case '五', '伍' -> 5;
            case '六', '陆' -> 6;
            case '七', '柒' -> 7;
            case '八', '捌' -> 8;
            case '九', '玖' -> 9;
            default -> -1;
        };
    }

    private static int unitOf(char c) {
        return switch (c) {
            case '十', '拾' -> 10;
            case '百', '佰' -> 100;
            case '千', '仟' -> 1000;
            default -> -1;
        };
    }

    /**
     * 从一句引文里剥掉「《…》第…条」这类引用字样，留下<b>内容线索</b>
     * （交给 {@code adjust_provisions} 的 {@code answerlaw.text}，让它按内容再定位一次条文）。
     *
     * <p>剥不干净也没关系——上游是语义匹配；剥掉的目的只是别让法规名与条号本身
     * 盖过真正的内容。返回值可能是空串，调用方按长度阈值决定发不发 answerlaw。
     */
    public static String contentClue(String quote) {
        if (quote == null) return "";
        String s = BOOK_TITLE.matcher(quote).replaceAll("");
        s = ARTICLE_REF.matcher(s).replaceAll("");
        s = EDGE_NOISE.matcher(s).replaceAll("");
        return s.trim();
    }
}
