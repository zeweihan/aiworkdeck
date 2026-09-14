// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把另一侧的页合进主线侧的 pptx（spec 2026-09-14 §4.5）。
 *
 * <p>以**主线侧文件为底**改页，不是重新拼一份：母版、版式、主题、页面尺寸没人动过就不该被合并动到。
 * 换一页的内容走 {@link XSLFSlide#importContent}，新增页 {@code createSlide()} 之后同样 import，
 * 另一侧删掉的页从主线这份里删掉。
 *
 * <p><b>两条对齐路径</b>：
 * <ol>
 *   <li>正常情况按 {@code sldId}（{@code presentation.xml} 里 {@code <p:sldId id>}）对齐，
 *       {@link Analysis#plan()} 里每个块的 {@link Chunk#ids()} 就是该侧那几页的 sldId；</li>
 *   <li>两份文件的 sldId 一个都对不上时（某些导出链路不保留 sldId，会把每一页都重编号）
 *       退回按「标题 + 文本」相似度对齐，低于 {@value #SIMILARITY_FLOOR} 的页才当作删除 + 新增。
 *       退回路径不信 {@code plan}（它是按 sldId 算的、这时必然是「整份删光再整份新增」），
 *       改用 {@link Analysis#baseUnits()} 里的共同上一版页文本自己做一遍三方比较：
 *       只有「另一侧改了、主线没改」的页才换内容，否则主线这一页原样留着。</li>
 * </ol>
 *
 * <p><b>实测（2026-09-14，LOWA 24.2.8-zhcn-r5，无头）：引擎导出的 pptx 不保留 sldId</b>。
 * 把一份 sldId 为 {@code 901/777/512} 的三页 pptx 经 {@code load_document} + {@code export_document}
 * 往返一次，导出件的 sldId 变成 {@code 256/257/258}——即「页序 + 256」，页数与页内容、页序都没变。
 * 也就是说**凡是经过编辑器保存过的 pptx，它的 sldId 只是页码的另一种写法，不是页的身份**：
 * 两份都出自引擎、页数相同时 sldId 会「碰巧全对上」，这时按 sldId 对齐等同于按页序对齐，
 * 一旦中间插过页就会整体错位。下面的相似度退回路径因此不是边角料，是引擎链路上的常态；
 * 但判据 {@link #sharesAnyId} 只能识别「一个都对不上」，识别不了「对上了但没意义」——
 * 这一层要在 {@link ThreeWayAnalyzer}（页键与 overlaps 也是按 sldId 算的）一起改才自洽，
 * 不在本类单独绕开，否则裁决界面上的页键与这里的对齐会各说各话。
 *
 * <p>{@code decisions} 为空 = 自动合：不冲突的块全部合入。非空 = 逐页裁决：
 * 明确判给另一侧的页（{@code side=T action=A}，或等价的 {@code side=M action=R}）换成另一侧的，
 * 没被问到的非冲突改动仍然自动合入——律师只被问了两边都动过的那几页，没被问到的不该因此丢掉。
 * 页序冲突用保留键 {@code order}：判给另一侧才按另一侧的页序重排。
 */
public final class PptxMerger {

    /** 相似度低于这个值就不认为是同一页（spec §4.5）。 */
    static final double SIMILARITY_FLOOR = 0.6;

    /** {@link Analysis#overlaps()} 里页序冲突占用的保留键。 */
    private static final String ORDER_KEY = "order";

    private PptxMerger() {
    }

    public static byte[] merge(byte[] main, byte[] other, Analysis analysis, List<Decision> decisions) {
        try {
            List<Slide> mainSlides = PptxSlideReader.read(main);
            List<Slide> otherSlides = PptxSlideReader.read(other);
            List<Op> ops = sharesAnyId(mainSlides, otherSlides)
                    ? opsFromPlan(analysis, decisions, mainSlides, otherSlides)
                    : opsFromSimilarity(analysis, decisions, mainSlides, otherSlides);
            return apply(main, other, mainSlides, otherSlides, ops, takesOther(decisions, ORDER_KEY));
        } catch (IOException e) {
            throw new UncheckedIOException("演示文稿合并失败", e);
        }
    }

    /** 一处要落到主线那份文件上的改动。{@code mainIndex}/{@code otherIndex} 都是 0 基页序，用不上的写 -1。 */
    private record Op(String type, int mainIndex, int otherIndex) {
    }

    // ------------------------------------------------------------ sldId 对齐

    private static boolean sharesAnyId(List<Slide> mainSlides, List<Slide> otherSlides) {
        Set<String> ids = new HashSet<>();
        for (Slide slide : mainSlides) {
            ids.add(slide.sldId());
        }
        for (Slide slide : otherSlides) {
            if (ids.contains(slide.sldId())) {
                return true;
            }
        }
        return false;
    }

    private static List<Op> opsFromPlan(Analysis analysis, List<Decision> decisions,
                                        List<Slide> mainSlides, List<Slide> otherSlides) {
        Map<String, Integer> mainIndex = indexById(mainSlides);
        Map<String, Integer> otherIndex = indexById(otherSlides);
        List<Op> ops = new ArrayList<>();
        for (Chunk chunk : analysis.plan().otherChunks()) {
            if (chunk.ids().isEmpty()) {
                continue;
            }
            String sldId = chunk.ids().get(0);
            Integer mi = mainIndex.get(sldId);
            Integer oi = otherIndex.get(sldId);
            String key = "INSERT".equals(chunk.type()) && oi != null
                    ? "s" + (oi + 1)
                    : "s" + (chunk.baseStart() + 1);
            if (!accept(key, chunk.conflict(), decisions)) {
                continue;
            }
            switch (chunk.type()) {
                case "DELETE" -> {
                    if (mi != null) {
                        ops.add(new Op("DELETE", mi, -1));
                    }
                }
                case "INSERT" -> {
                    if (oi != null) {
                        ops.add(new Op("INSERT", -1, oi));
                    }
                }
                default -> {
                    if (mi != null && oi != null) {
                        ops.add(new Op("MODIFY", mi, oi));
                    }
                }
            }
        }
        return ops;
    }

    private static Map<String, Integer> indexById(List<Slide> slides) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < slides.size(); i++) {
            map.put(slides.get(i).sldId(), i);
        }
        return map;
    }

    // ------------------------------------------------------- 相似度对齐（退回）

    private static List<Op> opsFromSimilarity(Analysis analysis, List<Decision> decisions,
                                              List<Slide> mainSlides, List<Slide> otherSlides) {
        List<Unit> baseUnits = analysis.baseUnits();
        if (baseUnits.isEmpty()) {
            // 连共同上一版的页文本都没有，无从判断谁改了什么——宁可原样不动
            return List.of();
        }
        List<String> baseTexts = new ArrayList<>();
        for (Unit unit : baseUnits) {
            baseTexts.add(unit.norm());
        }
        int[] toMain = align(baseTexts, textsOf(mainSlides));
        int[] toOther = align(baseTexts, textsOf(otherSlides));

        List<Op> ops = new ArrayList<>();
        Set<Integer> matchedOther = new HashSet<>();
        for (int i = 0; i < baseTexts.size(); i++) {
            int mi = toMain[i];
            int oi = toOther[i];
            if (oi >= 0) {
                matchedOther.add(oi);
            }
            String baseText = baseTexts.get(i);
            boolean mainChanged = mi < 0 || !normOf(mainSlides.get(mi)).equals(baseText);
            boolean otherChanged = oi < 0 || !normOf(otherSlides.get(oi)).equals(baseText);
            if (!otherChanged) {
                continue;
            }
            String key = "s" + (i + 1);
            if (!accept(key, mainChanged, decisions)) {
                continue;
            }
            if (oi < 0) {
                if (mi >= 0) {
                    ops.add(new Op("DELETE", mi, -1));
                }
            } else if (mi >= 0) {
                ops.add(new Op("MODIFY", mi, oi));
            }
        }
        for (int oi = 0; oi < otherSlides.size(); oi++) {
            if (!matchedOther.contains(oi) && accept("s" + (oi + 1), false, decisions)) {
                ops.add(new Op("INSERT", -1, oi));
            }
        }
        return ops;
    }

    private static List<String> textsOf(List<Slide> slides) {
        List<String> texts = new ArrayList<>();
        for (Slide slide : slides) {
            texts.add(normOf(slide));
        }
        return texts;
    }

    /**
     * 归一化口径必须与 {@link ThreeWayAnalyzer} 给 {@link Analysis#baseUnits()} 的那一套逐字相同，
     * 否则退回路径里每一页都会被判成「两边都改了」。{@link Slide#text()} 本身已经含标题占位符的文字
     * （{@link PptxSlideReader} 按形状序拼），所以这里不要再把 {@link Slide#title()} 拼一遍。
     */
    private static String normOf(Slide slide) {
        return DocxUnitReader.normalize(slide.text());
    }

    /**
     * 贪心对齐：相似度从高到低配对，一页只配一次，低于 {@link #SIMILARITY_FLOOR} 不配。
     *
     * @return 长度与 {@code left} 相同的数组，元素是配上的 {@code right} 下标，没配上是 -1
     */
    private static int[] align(List<String> left, List<String> right) {
        int[] pairs = new int[left.size()];
        java.util.Arrays.fill(pairs, -1);
        List<int[]> candidates = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < left.size(); i++) {
            for (int j = 0; j < right.size(); j++) {
                double score = similarity(left.get(i), right.get(j));
                if (score >= SIMILARITY_FLOOR) {
                    candidates.add(new int[]{i, j});
                    scores.add(score);
                }
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> scores.get(i)).reversed());
        Set<Integer> usedRight = new HashSet<>();
        for (int i : order) {
            int[] pair = candidates.get(i);
            if (pairs[pair[0]] >= 0 || usedRight.contains(pair[1])) {
                continue;
            }
            pairs[pair[0]] = pair[1];
            usedRight.add(pair[1]);
        }
        return pairs;
    }

    /** 字符二元组的 Dice 系数：两串完全相同是 1，毫无重叠是 0。 */
    static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        Set<String> left = bigrams(a);
        Set<String> right = bigrams(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        int shared = 0;
        for (String gram : left) {
            if (right.contains(gram)) {
                shared++;
            }
        }
        return 2.0 * shared / (left.size() + right.size());
    }

    private static Set<String> bigrams(String s) {
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            grams.add(s.substring(i, i + 2));
        }
        return grams;
    }

    // ------------------------------------------------------------------ 落盘

    private static byte[] apply(byte[] main, byte[] other, List<Slide> mainSlides, List<Slide> otherSlides,
                                List<Op> ops, boolean adoptOtherOrder) throws IOException {
        if (ops.isEmpty() && !adoptOtherOrder) {
            return main;
        }
        try (XMLSlideShow mainShow = new XMLSlideShow(new ByteArrayInputStream(main));
             XMLSlideShow otherShow = new XMLSlideShow(new ByteArrayInputStream(other));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<XSLFSlide> otherXslf = otherShow.getSlides();
            Map<String, XSLFSlide> mainById = new LinkedHashMap<>();
            List<XSLFSlide> mainXslf = mainShow.getSlides();
            for (int i = 0; i < mainSlides.size() && i < mainXslf.size(); i++) {
                mainById.put(mainSlides.get(i).sldId(), mainXslf.get(i));
            }

            for (Op op : ops) {
                if ("MODIFY".equals(op.type())) {
                    XSLFSlide target = mainShow.getSlides().get(op.mainIndex());
                    target.clear();
                    target.importContent(otherXslf.get(op.otherIndex()));
                }
            }

            List<Integer> removals = new ArrayList<>();
            for (Op op : ops) {
                if ("DELETE".equals(op.type())) {
                    removals.add(op.mainIndex());
                }
            }
            removals.sort(Comparator.reverseOrder());
            for (int index : removals) {
                XSLFSlide removed = mainShow.getSlides().get(index);
                mainById.values().remove(removed);
                mainShow.removeSlide(index);
            }

            if (adoptOtherOrder) {
                int target = 0;
                for (Slide slide : otherSlides) {
                    XSLFSlide existing = mainById.get(slide.sldId());
                    if (existing != null && target < mainShow.getSlides().size()) {
                        mainShow.setSlideOrder(existing, target++);
                    }
                }
            }

            for (Op op : ops) {
                if (!"INSERT".equals(op.type())) {
                    continue;
                }
                XSLFSlide created = mainShow.createSlide();
                created.importContent(otherXslf.get(op.otherIndex()));
                int target = Math.min(op.otherIndex(), mainShow.getSlides().size() - 1);
                if (target >= 0) {
                    mainShow.setSlideOrder(created, target);
                }
            }

            mainShow.write(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ 裁决

    /**
     * 这一处该不该换成另一侧的：没有裁决清单时不冲突就合、冲突就留主线的；
     * 有裁决清单时按清单，清单里没提到的还是按「不冲突就合」。
     */
    private static boolean accept(String key, boolean conflict, List<Decision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return !conflict;
        }
        for (Decision decision : decisions) {
            if (decision.key().equals(key)) {
                return isTakeOther(decision);
            }
        }
        return !conflict;
    }

    private static boolean takesOther(List<Decision> decisions, String key) {
        if (decisions == null) {
            return false;
        }
        for (Decision decision : decisions) {
            if (decision.key().equals(key)) {
                return isTakeOther(decision);
            }
        }
        return false;
    }

    private static boolean isTakeOther(Decision decision) {
        return ("T".equals(decision.side()) && "A".equals(decision.action()))
                || ("M".equals(decision.side()) && "R".equals(decision.action()));
    }
}
