package com.checkba.service.ai.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合同结构的确定性审计（dev-board#375）：纯函数，不碰编辑器、不调模型。
 *
 * <p>存在意义：同一份合约，Claude Code 靠临时写脚本做到了「编号从第 10 条起全乱」
 * 「序文 C 940,670 万元单位差 10 倍」「2.1 被前一轮删到只剩半句」这类机械核对，
 * 而工作台里的模型只有分页读正文这一只眼睛——这些活交给弱模型靠肉眼数，必然漏。
 * 本类把机械核对做成工具（{@code doc_audit_structure}），报告喂给模型，模型只负责判断。
 *
 * <p>七项检查，全部只报事实、不下法律结论：
 * <ol>
 *   <li>字形：全文以繁體还是简体为主，哪些段落与主体字形不一致（简体文字混进繁體合约是真实故障）；</li>
 *   <li>编号：第X条 / N. / N.M / (一) / (a) / (1) 各套编号在其作用域内是否连续；</li>
 *   <li>交叉引用：正文里提到的「第X条」「附表X」有没有对应的定义；</li>
 *   <li>空白与待定：下划线、空括号、【】、待定/暫定、TBD；</li>
 *   <li>金额与数量：金额/股数台账、同段「股数 × 每股价 = 总价」算术复核、万元/元换算；</li>
 *   <li>币种：全文出现了哪几种货币；</li>
 *   <li>既有修订：前一轮修订按作者/类型汇总，大段删除单独点名（条款可能被删残）。</li>
 * </ol>
 */
public final class ContractStructureAudit {

    private ContractStructureAudit() {
    }

    /** 一个段落：编辑器 get_document_text 返回的 index（0 基）+ 正文。 */
    public record Paragraph(int index, String text) {
    }

    /** 一条既有修订：来自编辑器 list_revisions（text/paragraph 均已被 worker 截到 120 字符）。 */
    public record Revision(String type, String author, String text, String paragraph) {
    }

    /** 报告条目：段落号 + 一句话事实。 */
    public record Finding(int paragraph, String message) {
    }

    /** 审计结果。{@link #render()} 输出给模型看的中文报告。 */
    public static final class Report {
        public String dominantScript = "unknown";
        public int traditionalChars;
        public int simplifiedChars;
        public final List<Finding> scriptOutliers = new ArrayList<>();
        public final List<String> clauseSequence = new ArrayList<>();
        public final List<Finding> numbering = new ArrayList<>();
        public final List<Finding> danglingReferences = new ArrayList<>();
        public final List<Finding> blanks = new ArrayList<>();
        public final List<Finding> amountLedger = new ArrayList<>();
        public final List<Finding> arithmetic = new ArrayList<>();
        public final Map<String, Integer> currencies = new LinkedHashMap<>();
        public final Map<String, Integer> revisionsByAuthor = new LinkedHashMap<>();
        public final Map<String, Integer> revisionsByType = new LinkedHashMap<>();
        public final List<String> largeDeletions = new ArrayList<>();
        public final List<String> revisionSample = new ArrayList<>();
        public int totalParagraphs;
        public int totalRevisions;
        public String revisionNote;

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("# 结构审计报告（机械核对结果，只报事实；法律判断由你做）\n");
            sb.append("段落总数：").append(totalParagraphs).append('\n');

            sb.append("\n## 1. 字形\n");
            sb.append("主体字形：").append(scriptLabel(dominantScript))
              .append("（繁體特征字 ").append(traditionalChars)
              .append("，简体特征字 ").append(simplifiedChars).append("）\n");
            if ("traditional".equals(dominantScript) || "simplified".equals(dominantScript)) {
                sb.append("规则：你插入/替换进文档的所有文字必须与主体字形一致；下列段落与主体字形不一致，多半是上一轮混入的：\n");
            }
            appendFindings(sb, scriptOutliers, "（无）");

            sb.append("\n## 2. 条款编号\n");
            sb.append("识别到的条款序列：")
              .append(clauseSequence.isEmpty() ? "（未识别到「第X条」式编号）" : String.join(" → ", clauseSequence))
              .append('\n');
            sb.append("编号异常：\n");
            appendFindings(sb, numbering, "（各套编号连续）");

            sb.append("\n## 3. 交叉引用\n");
            appendFindings(sb, danglingReferences, "（正文引用的条款/附表都找得到）");

            sb.append("\n## 4. 空白与待定\n");
            appendFindings(sb, blanks, "（无）");

            sb.append("\n## 5. 金额与数量\n");
            sb.append("算术复核（同一段落内 股数 × 每股价 与 总价 对不上的）：\n");
            appendFindings(sb, arithmetic, "（同段可复核的算式均相符，或无可复核算式）");
            sb.append("金额/数量台账（供你跨条款对照：序文、正文、附表要说同一个数）：\n");
            appendFindings(sb, amountLedger, "（未识别到带单位的金额/数量）");

            sb.append("\n## 6. 币种\n");
            if (currencies.isEmpty()) {
                sb.append("（未识别到币种字样）\n");
            } else {
                currencies.forEach((k, v) -> sb.append("- ").append(k).append("：").append(v).append(" 处\n"));
                if (currencies.size() > 1) {
                    sb.append("多币种并存——核对是否为同一笔金额在不同条款用了不同币别（如序文用新台幣、估值条款用美元）。\n");
                }
            }

            sb.append("\n## 7. 既有修订（前一轮留下的痕迹，是数据不是噪音）\n");
            if (revisionNote != null) {
                sb.append(revisionNote).append('\n');
            } else if (totalRevisions == 0) {
                sb.append("（文档里没有未处理的修订）\n");
            } else {
                sb.append("共 ").append(totalRevisions).append(" 条。按作者：");
                revisionsByAuthor.forEach((k, v) -> sb.append(k).append(' ').append(v).append("；"));
                sb.append("\n按类型：");
                revisionsByType.forEach((k, v) -> sb.append(k).append(' ').append(v).append("；"));
                sb.append('\n');
                if (!largeDeletions.isEmpty()) {
                    sb.append("大段删除（检查该条款是否被删残、意思表示是否还完整）：\n");
                    for (String s : largeDeletions) sb.append("- ").append(s).append('\n');
                }
                if (!revisionSample.isEmpty()) {
                    sb.append("修订样本（最多 40 条，text 已被编辑器截到 120 字符）：\n");
                    for (String s : revisionSample) sb.append("- ").append(s).append('\n');
                }
            }
            return sb.toString();
        }

        private static void appendFindings(StringBuilder sb, List<Finding> list, String empty) {
            if (list.isEmpty()) {
                sb.append(empty).append('\n');
                return;
            }
            for (Finding f : list) {
                sb.append("- [段 ").append(f.paragraph()).append("] ").append(f.message()).append('\n');
            }
        }

        private static String scriptLabel(String s) {
            return switch (s) {
                case "traditional" -> "繁體中文";
                case "simplified" -> "简体中文";
                case "mixed" -> "繁简混杂（无明显主体）";
                default -> "无法判断（中文特征字太少）";
            };
        }
    }

    // ------------------------------------------------------------------ 入口

    public static Report run(List<Paragraph> paragraphs, List<Revision> revisions, String revisionNote) {
        Report r = new Report();
        List<Paragraph> paras = paragraphs == null ? List.of() : paragraphs;
        r.totalParagraphs = paras.size();
        auditScript(paras, r);
        auditNumbering(paras, r);
        auditCrossReferences(paras, r);
        auditBlanks(paras, r);
        auditAmounts(paras, r);
        auditRevisions(revisions, revisionNote, r);
        return r;
    }

    // ------------------------------------------------------------------ 1. 字形

    /**
     * 简体/繁體特征字对照：偶数位简体、奇数位繁體。只收「简体字形在繁體正字里不存在、
     * 繁體字形在简体里不存在」的一对一字，避免 后/後、台/臺、于/於 这类两边都合法的字。
     */
    static final String SCRIPT_PAIRS =
            "国國会會应應术術与與发發权權条條义義务務责責约約让讓认認购購价價双雙关關经經议議决決对對说說时時实實现現体體产產办辦这這为為无無从從业業员員开開间間问問题題东東车車电電书書长長门門见見贝貝页頁风風马馬鸟鳥龙龍齐齊飞飛鱼魚学學号號处處变變报報担擔损損赔賠偿償违違补補归歸计計设設证證诉訴讼訟审審转轉签簽订訂协協记記录錄单單该該则則规規财財资資债債账賬货貨贷貸费費缴繳纳納税稅币幣额額结結续續终終给給级級组組织織统統总總线線维維网網检檢测測验驗标標择擇举舉执執声聲请請语語词詞译譯详詳误誤谈談论論调調读讀谓謂属屬层層尽盡届屆尔爾优優亿億传傳伪偽众眾伤傷华華卫衛厂廠历歷压壓厅廳参參县縣复復汇匯汉漢满滿灭滅灯燈环環竞競笔筆简簡纪紀纠糾纷紛缔締绝絕继繼罚罰习習乡鄉买買卖賣严嚴丽麗乐樂争爭亏虧兰蘭冻凍减減凭憑击擊刚剛创創剂劑势勢动動劳勞医醫区區厉厲叙敘叠疊叹嘆听聽启啟响響唤喚团團园園围圍图圖圆圓场場块塊坚堅备備够夠头頭夹夾夺奪奖獎妇婦娱娛孙孫宁寧宽寬宝寶将將寻尋导導岁歲师師带帶帮幫张張弹彈当當彻徹忆憶态態怀懷恶惡惊驚惯慣愿願战戰户戶扩擴扫掃扬揚拟擬拥擁换換摄攝数數断斷显顯机機杂雜来來极極构構栏欄样樣档檔楼樓欢歡气氣沟溝没沒济濟灵靈点點热熱独獨获獲画畫疗療监監盘盤确確础礎离離种種积積称稱稳穩类類紧緊纯純细細编編缓緩缩縮联聯职職胜勝脑腦脱脫节節药藥营營虑慮装裝观觀视視览覽觉覺讨討训訓讯訊讲講许許访訪评評识識试試诺諾谁誰谢謝负負败敗质質贸貿赖賴赛賽赠贈轻輕载載较較辑輯输輸边邊达達过過运運还還进進远遠连連迟遲适適选選递遞释釋铁鐵银銀错錯锁鎖键鍵闭閉闻聞阅閱队隊阶階际際陆陸陈陳险險随隨隐隱难難静靜项項顺順须須预預领領频頻颁頒龄齡";

    private static final Set<Character> SIMPLIFIED = new java.util.HashSet<>();
    private static final Set<Character> TRADITIONAL = new java.util.HashSet<>();

    static {
        for (int i = 0; i + 1 < SCRIPT_PAIRS.length(); i += 2) {
            SIMPLIFIED.add(SCRIPT_PAIRS.charAt(i));
            TRADITIONAL.add(SCRIPT_PAIRS.charAt(i + 1));
        }
    }

    /** 单段的字形倾向：{traditionalCount, simplifiedCount}。 */
    static int[] scriptCounts(String text) {
        int t = 0;
        int s = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (TRADITIONAL.contains(c)) t++;
            else if (SIMPLIFIED.contains(c)) s++;
        }
        return new int[]{t, s};
    }

    private static void auditScript(List<Paragraph> paras, Report r) {
        int[][] per = new int[paras.size()][];
        for (int i = 0; i < paras.size(); i++) {
            per[i] = scriptCounts(paras.get(i).text());
            r.traditionalChars += per[i][0];
            r.simplifiedChars += per[i][1];
        }
        int total = r.traditionalChars + r.simplifiedChars;
        if (total < 10) {
            r.dominantScript = "unknown";
            return;
        }
        double tradRatio = (double) r.traditionalChars / total;
        if (tradRatio >= 0.8) r.dominantScript = "traditional";
        else if (tradRatio <= 0.2) r.dominantScript = "simplified";
        else r.dominantScript = "mixed";
        if ("mixed".equals(r.dominantScript)) return;

        boolean trad = "traditional".equals(r.dominantScript);
        for (int i = 0; i < paras.size() && r.scriptOutliers.size() < 30; i++) {
            int mine = trad ? per[i][0] : per[i][1];
            int other = trad ? per[i][1] : per[i][0];
            // 判据：异体字至少 2 个，且多于本体字——一两个错别字不报，整句混入才报
            if (other >= 2 && other > mine) {
                r.scriptOutliers.add(new Finding(paras.get(i).index(),
                        (trad ? "简体字混入繁體正文" : "繁體字混入简体正文") + "（异体字 " + other
                                + " 个）：" + snippet(paras.get(i).text(), 60)));
            }
        }
    }

    // ------------------------------------------------------------------ 2. 编号

    private static final String CN_NUM = "[一二三四五六七八九十百千零〇0-9１-９]+";
    static final Pattern P_TIAO = Pattern.compile("^\\s*第\\s*(" + CN_NUM + ")\\s*[条條]");
    static final Pattern P_ZHANG = Pattern.compile("^\\s*第\\s*(" + CN_NUM + ")\\s*[章节節编編]");
    static final Pattern P_ENUM = Pattern.compile("^\\s*([一二三四五六七八九十]{1,3})\\s*[、．.]");
    static final Pattern P_ARABIC = Pattern.compile("^\\s*(\\d{1,3})\\s*[.、．]\\s*(?!\\d)");
    static final Pattern P_DOTTED = Pattern.compile("^\\s*(\\d{1,3})\\.(\\d{1,3})(?![.\\d])");
    static final Pattern P_PAREN_CN = Pattern.compile("^\\s*[（(]\\s*([一二三四五六七八九十]{1,3})\\s*[)）]");
    static final Pattern P_PAREN_LATIN = Pattern.compile("^\\s*[（(]\\s*([a-zA-Z])\\s*[)）]");
    static final Pattern P_PAREN_NUM = Pattern.compile("^\\s*[（(]\\s*(\\d{1,2})\\s*[)）]");

    private static final class Run {
        int scope = -1;
        int last;
        int lastParagraph;
    }

    /**
     * 三层作用域：第X条/第X章（顶层）→ N. / N.M（次层）→ (一) / (a) / (1)（末层）。
     * 每套编号只在「上一层最近一次换号」之后要求连续：第 8 条里的 (a)…(k) 和第 9 条里重新从 (a)
     * 起是正常的；同一条里 (j) 之后直接 (l) 才是异常。没有「第X条」的合同以「一、」为顶层。
     */
    private static void auditNumbering(List<Paragraph> paras, Report r) {
        Map<String, Run> runs = new LinkedHashMap<>();
        int scope0 = 0;          // 顶层作用域：第X条 / 第X章 / （无条时）一、
        int scope1 = 0;          // 次层作用域：随顶层换号，也随 N. / N.M 换号
        int lastTiao = 0;
        int lastTiaoParagraph = -1;
        boolean hasTiao = paras.stream().anyMatch(p -> P_TIAO.matcher(p.text()).find());
        Set<Integer> seenTiao = new LinkedHashSet<>();
        for (Paragraph p : paras) {
            String t = p.text();
            Matcher m;
            if ((m = P_TIAO.matcher(t)).find()) {
                int n = ChineseNumerals.parse(m.group(1));
                scope0++;
                scope1++;
                if (n > 0) {
                    r.clauseSequence.add("第" + n + "条");
                    if (seenTiao.contains(n)) {
                        r.numbering.add(new Finding(p.index(), "第" + n + "条重复出现（上一次在段 " + lastTiaoParagraph + "）"));
                    } else if (lastTiao > 0 && n != lastTiao + 1) {
                        r.numbering.add(new Finding(p.index(), n > lastTiao
                                ? "第" + lastTiao + "条之后直接是第" + n + "条，中间缺 " + (n - lastTiao - 1) + " 条（可能有条款没编号，或编号漏排）"
                                : "第" + lastTiao + "条之后出现第" + n + "条，编号倒退"));
                    }
                    seenTiao.add(n);
                    lastTiao = n;
                    lastTiaoParagraph = p.index();
                }
                continue;
            }
            if (P_ZHANG.matcher(t).find()) {
                scope0++;
                scope1++;
                continue;
            }
            if ((m = P_ENUM.matcher(t)).find()) {
                // 有「第X条」时 一、二、 是条内子项（作用域=所在条）；没有时它就是顶层（全文一条序列）
                check(runs, "一、", hasTiao ? scope0 : -1, ChineseNumerals.parse(m.group(1)), p, r);
                if (!hasTiao) scope0++;
                scope1++;
                continue;
            }
            if ((m = P_DOTTED.matcher(t)).find()) {
                int major = Integer.parseInt(m.group(1));
                int minor = Integer.parseInt(m.group(2));
                check(runs, "N.M(" + major + ")", scope0, minor, p, r);
                if (hasTiao && lastTiao > 0 && major != lastTiao) {
                    r.numbering.add(new Finding(p.index(), "「" + major + "." + minor + "」出现在第" + lastTiao
                            + "条范围内，主号与所在条不一致"));
                }
                scope1++;
                continue;
            }
            if ((m = P_ARABIC.matcher(t)).find()) {
                check(runs, "N.", scope0, Integer.parseInt(m.group(1)), p, r);
                scope1++;
                continue;
            }
            if ((m = P_PAREN_CN.matcher(t)).find()) {
                check(runs, "(一)", scope1, ChineseNumerals.parse(m.group(1)), p, r);
                continue;
            }
            if ((m = P_PAREN_LATIN.matcher(t)).find()) {
                check(runs, "(a)", scope1, Character.toLowerCase(m.group(1).charAt(0)) - 'a' + 1, p, r);
                continue;
            }
            if ((m = P_PAREN_NUM.matcher(t)).find()) {
                check(runs, "(1)", scope1, Integer.parseInt(m.group(1)), p, r);
            }
        }
        if (r.numbering.size() > 40) {
            List<Finding> cut = new ArrayList<>(r.numbering.subList(0, 40));
            r.numbering.clear();
            r.numbering.addAll(cut);
            r.numbering.add(new Finding(-1, "（编号异常超过 40 条，已截断）"));
        }
    }

    private static void check(Map<String, Run> runs, String scheme, int scope, int n, Paragraph p, Report r) {
        if (n <= 0) return;
        Run run = runs.computeIfAbsent(scheme, k -> new Run());
        String label = scheme.startsWith("N.M") ? "N.M" : scheme;
        if (run.scope != scope) {
            // 新作用域里的第一条：不是从 1 起就点名（(k) 款接在上一条的 (j) 之后是常见错位）
            if (n != 1 && !"一、".equals(scheme)) {
                r.numbering.add(new Finding(p.index(), "「" + label + "」编号在新条款里从 " + render(scheme, n)
                        + " 开始而不是从头起（上一处同类编号在段 " + run.lastParagraph + "）"));
            }
        } else if (n != run.last + 1) {
            r.numbering.add(new Finding(p.index(), n > run.last
                    ? "「" + label + "」编号从 " + render(scheme, run.last) + " 跳到 " + render(scheme, n)
                    : "「" + label + "」编号在 " + render(scheme, run.last) + " 之后出现 " + render(scheme, n) + "（重复或倒退）"));
        }
        run.scope = scope;
        run.last = n;
        run.lastParagraph = p.index();
    }

    private static String render(String scheme, int n) {
        if ("(a)".equals(scheme)) return "(" + (char) ('a' + n - 1) + ")";
        return String.valueOf(n);
    }

    // ------------------------------------------------------------------ 3. 交叉引用

    private static final Pattern P_REF_TIAO = Pattern.compile("第\\s*(\\d{1,3}|[一二三四五六七八九十百]{1,4})(?:\\.(\\d{1,3}))?\\s*(?:\\([a-z0-9]{1,2}\\)|（[a-z0-9]{1,2}）)?\\s*[条條]");
    private static final Pattern P_REF_ANNEX = Pattern.compile("(附[表件錄录])\\s*([A-Z]|[一二三四五六七八九十]{1,2}|\\d{1,2})(?![\\p{L}\\d])");
    private static final Pattern P_DEF_ANNEX = Pattern.compile("^\\s*(附[表件錄录])\\s*([A-Z]|[一二三四五六七八九十]{1,2}|\\d{1,2})(?![\\p{L}\\d])");

    private static void auditCrossReferences(List<Paragraph> paras, Report r) {
        Set<Integer> tiao = new LinkedHashSet<>();
        Set<String> dotted = new LinkedHashSet<>();
        Set<String> annexes = new LinkedHashSet<>();
        for (Paragraph p : paras) {
            Matcher m = P_TIAO.matcher(p.text());
            if (m.find()) tiao.add(ChineseNumerals.parse(m.group(1)));
            m = P_DOTTED.matcher(p.text());
            if (m.find()) dotted.add(m.group(1) + "." + m.group(2));
            m = P_DEF_ANNEX.matcher(p.text());
            if (m.find()) annexes.add(m.group(1).charAt(0) + normalizeAnnex(m.group(2)));
        }
        if (tiao.isEmpty() && annexes.isEmpty()) return;
        Set<String> reported = new LinkedHashSet<>();
        for (Paragraph p : paras) {
            String t = p.text();
            int headerEnd = 0;
            Matcher h = P_TIAO.matcher(t);
            if (h.find()) headerEnd = h.end();
            Matcher m = P_REF_TIAO.matcher(t);
            while (m.find() && r.danglingReferences.size() < 40) {
                if (m.start() < headerEnd) continue;
                int n = ChineseNumerals.parse(m.group(1));
                if (n <= 0 || tiao.isEmpty()) continue;
                String ref = m.group().replaceAll("\\s+", "");
                if (!tiao.contains(n)) {
                    if (reported.add("t" + n)) {
                        r.danglingReferences.add(new Finding(p.index(), "引用了「" + ref + "」，但全文没有第" + n + "条"));
                    }
                } else if (m.group(2) != null && !dotted.isEmpty() && !dotted.contains(n + "." + m.group(2))) {
                    String key = n + "." + m.group(2);
                    if (reported.add("d" + key)) {
                        r.danglingReferences.add(new Finding(p.index(), "引用了「" + ref + "」，但全文没有编号 " + key + " 的款"));
                    }
                }
            }
            if (annexes.isEmpty()) continue;
            Matcher a = P_REF_ANNEX.matcher(t);
            while (a.find() && r.danglingReferences.size() < 40) {
                if (a.start() == 0 || P_DEF_ANNEX.matcher(t).find()) continue;
                String key = a.group(1).charAt(0) + normalizeAnnex(a.group(2));
                if (!annexes.contains(key) && reported.add("a" + key)) {
                    r.danglingReferences.add(new Finding(p.index(), "引用了「" + a.group().trim() + "」，但全文没有以它开头的附表/附件标题"));
                }
            }
        }
    }

    private static String normalizeAnnex(String s) {
        if (s.matches("[A-Z]")) return s;
        int n = ChineseNumerals.parse(s);
        return n > 0 ? String.valueOf(n) : s;
    }

    // ------------------------------------------------------------------ 4. 空白

    private static final Pattern P_BLANK = Pattern.compile(
            "[_＿]{2,}|【[\\s\\u3000]*】|\\[[\\s\\u3000]*\\]|［[\\s\\u3000]*］|（[\\s\\u3000]*）|\\([\\s\\u3000]*\\)|[□]|\\[●\\]|【●】|[●]{1,}|[×X]{2,}(?![a-zA-Z])|待定|暫定|暂定|待補|待补|待填|TBD|TBC");

    private static void auditBlanks(List<Paragraph> paras, Report r) {
        for (Paragraph p : paras) {
            Matcher m = P_BLANK.matcher(p.text());
            while (m.find() && r.blanks.size() < 40) {
                r.blanks.add(new Finding(p.index(), "「" + m.group() + "」：" + around(p.text(), m.start(), m.end(), 30)));
            }
        }
    }

    // ------------------------------------------------------------------ 5. 金额与数量

    private static final String NUM = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)";
    private static final Pattern P_AMOUNT = Pattern.compile(
            "(?:(NT\\$|US\\$|USD|RMB|HK\\$|新台幣|新臺幣|新台币|人民幣|人民币|美元|美金|港幣|港币|歐元|欧元)\\s*)?"
                    + NUM + "\\s*(萬|万|億|亿)?\\s*(元|股|美元|美金|新台幣|新臺幣|新台币|人民幣|人民币|港幣|港币)?");
    private static final Pattern P_PER_SHARE = Pattern.compile("每股\\D{0,12}?" + NUM + "\\s*(萬|万)?\\s*元");
    private static final Pattern P_SHARES = Pattern.compile(NUM + "\\s*(萬|万)?\\s*股");
    private static final Pattern P_TOTAL = Pattern.compile(NUM + "\\s*(萬|万|億|亿)?\\s*元");
    private static final Pattern P_CURRENCY = Pattern.compile(
            "新台幣|新臺幣|新台币|NT\\$|人民幣|人民币|RMB|美元|美金|US\\$|USD|港幣|港币|HK\\$|歐元|欧元|EUR|日圓|日元|JPY");

    private static void auditAmounts(List<Paragraph> paras, Report r) {
        for (Paragraph p : paras) {
            String t = p.text();
            Matcher c = P_CURRENCY.matcher(t);
            while (c.find()) r.currencies.merge(canonicalCurrency(c.group()), 1, Integer::sum);

            List<String> found = new ArrayList<>();
            Matcher m = P_AMOUNT.matcher(t);
            while (m.find()) {
                boolean hasUnit = m.group(1) != null || m.group(3) != null || m.group(4) != null;
                if (!hasUnit) continue;
                String raw = m.group().trim();
                // 纯编号（第 1 条 / 2026 年）不带单位所以不会进来；带单位但很小的数也照记
                found.add(raw);
            }
            if (!found.isEmpty() && r.amountLedger.size() < 60) {
                r.amountLedger.add(new Finding(p.index(), String.join(" / ", new LinkedHashSet<>(found))
                        + " —— " + snippet(t, 40)));
            }
            checkArithmetic(p, r);
        }
    }

    /** 同一段里 股数 × 每股价 = 总价 的算术复核；三者齐备才算，缺一不报。 */
    private static void checkArithmetic(Paragraph p, Report r) {
        String t = p.text();
        Matcher ps = P_PER_SHARE.matcher(t);
        if (!ps.find()) return;
        double perShare = number(ps.group(1)) * scale(ps.group(2));
        Matcher sh = P_SHARES.matcher(t);
        if (!sh.find()) return;
        double shares = number(sh.group(1)) * scale(sh.group(2));
        if (shares <= 0 || perShare <= 0) return;
        double expected = shares * perShare;
        // 总价：段里所有「元」金额中排除每股价本身，取与 expected 数量级最接近的一个来比
        Matcher tot = P_TOTAL.matcher(t);
        Double best = null;
        String bestRaw = null;
        while (tot.find()) {
            if (tot.start() >= ps.start() && tot.start() < ps.end()) continue;
            double v = number(tot.group(1)) * scale(tot.group(2));
            if (v == perShare) continue;
            if (best == null || Math.abs(Math.log10(v / expected)) < Math.abs(Math.log10(best / expected))) {
                best = v;
                bestRaw = tot.group().trim();
            }
        }
        if (best == null) return;
        double diff = Math.abs(best - expected) / expected;
        if (diff > 0.01) {
            double ratio = best / expected;
            String hint = Math.abs(Math.log10(ratio) - Math.round(Math.log10(ratio))) < 0.02
                    ? "，差 " + (ratio >= 1 ? Math.round(ratio) : "1/" + Math.round(1 / ratio)) + " 倍——多半是万元/元单位写错"
                    : "";
            r.arithmetic.add(new Finding(p.index(), String.format(Locale.ROOT,
                    "%s股 × 每股 %s = %s，但文中写的是「%s」%s",
                    plain(shares), plain(perShare), plainWithWan(expected), bestRaw, hint)));
        }
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double scale(String unit) {
        if (unit == null) return 1;
        return switch (unit) {
            case "萬", "万" -> 10_000;
            case "億", "亿" -> 100_000_000;
            default -> 1;
        };
    }

    private static String plain(double v) {
        if (v == Math.rint(v)) return String.format(Locale.ROOT, "%,d", (long) v);
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    private static String plainWithWan(double v) {
        String elem = plain(v) + " 元";
        if (v >= 10_000 && v % 10_000 == 0) return elem + "（= " + plain(v / 10_000) + " 万元）";
        if (v >= 10_000) return elem + "（≈ " + String.format(Locale.ROOT, "%,.1f", v / 10_000) + " 万元）";
        return elem;
    }

    private static String canonicalCurrency(String s) {
        return switch (s) {
            case "新台幣", "新臺幣", "新台币", "NT$" -> "新台币";
            case "人民幣", "人民币", "RMB" -> "人民币";
            case "美元", "美金", "US$", "USD" -> "美元";
            case "港幣", "港币", "HK$" -> "港币";
            case "歐元", "欧元", "EUR" -> "欧元";
            case "日圓", "日元", "JPY" -> "日元";
            default -> s;
        };
    }

    // ------------------------------------------------------------------ 7. 既有修订

    /** 一次删掉 ≥ 40 字（约一整句）才算「大段删除」——单字/单词的润色不点名。worker 把 text 截到 120。 */
    static final int LARGE_DELETION_CHARS = 40;

    private static void auditRevisions(List<Revision> revisions, String note, Report r) {
        if (note != null) {
            r.revisionNote = note;
            return;
        }
        if (revisions == null) return;
        r.totalRevisions = revisions.size();
        for (Revision rev : revisions) {
            r.revisionsByAuthor.merge(blank(rev.author()) ? "（未知作者）" : rev.author(), 1, Integer::sum);
            r.revisionsByType.merge(blank(rev.type()) ? "?" : rev.type(), 1, Integer::sum);
            String text = rev.text() == null ? "" : rev.text();
            String para = rev.paragraph() == null ? "" : rev.paragraph();
            if ("Delete".equalsIgnoreCase(rev.type()) && text.length() >= LARGE_DELETION_CHARS && r.largeDeletions.size() < 20) {
                r.largeDeletions.add("[" + safe(rev.author()) + "] 删了「" + text + "」 @ 「" + snippet(para, 50) + "」");
            }
            if (r.revisionSample.size() < 40) {
                r.revisionSample.add("[" + safe(rev.type()) + "][" + safe(rev.author()) + "] 「" + snippet(text, 80)
                        + "」 @ 「" + snippet(para, 40) + "」");
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String s) {
        return blank(s) ? "?" : s;
    }

    static String snippet(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String around(String s, int start, int end, int radius) {
        int a = Math.max(0, start - radius);
        int b = Math.min(s.length(), end + radius);
        return (a > 0 ? "…" : "") + s.substring(a, b).replaceAll("\\s+", " ") + (b < s.length() ? "…" : "");
    }

    /** 供测试与工具类对照：特征字表偶数位/奇数位是否成对。 */
    static Map<Character, Character> pairs() {
        Map<Character, Character> m = new TreeMap<>();
        for (int i = 0; i + 1 < SCRIPT_PAIRS.length(); i += 2) {
            m.put(SCRIPT_PAIRS.charAt(i), SCRIPT_PAIRS.charAt(i + 1));
        }
        return Collections.unmodifiableMap(m);
    }
}
