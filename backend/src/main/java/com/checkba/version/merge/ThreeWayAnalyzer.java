// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.merge.MergeAlgorithm;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化三方比对：给一个冲突路径算出「能不能自动合、哪几处两边都动了、引擎该怎么重放」。
 *
 * <p>docx 的做法是把单元序列（正文段落 + 表格单元，见 {@link DocxUnitReader}）当成「行」
 * 交给 JGit 的 {@link MergeAlgorithm}——每行的内容是该单元归一文字的 SHA-256，
 * 这样文字里的换行、制表符不会把一个单元劈成两行。
 *
 * <p>**相邻也算冲突**：JGit 与 git 同口径，两侧的改动之间没有未改动的单元隔开时判冲突。
 * 这条是刻意保留的保守口径——spike A3 试过的 {@code .uno:MergeDocuments} 对挨着的两处改动
 * 不报冲突、静默叠加成病句，律师事后根本发现不了。宁可多问一次。
 *
 * <p>xlsx 是无序集合，按单元格键比交集；pptx 按 {@code sldId} 对齐页，另外单判页序。
 */
public final class ThreeWayAnalyzer {

    /** 单侧字节超过这个数就不做结构化比对，直接整份。 */
    static final int MAX_BYTES = 20 * 1024 * 1024;

    /** pptx 页序冲突在 {@link Overlap} 里占用的保留键。 */
    static final String ORDER_KEY = "order";

    private ThreeWayAnalyzer() {
    }

    /** 按扩展名认类型，大小写不敏感。 */
    public static MergeKind kindOf(String path) {
        if (path == null) {
            return MergeKind.WHOLE;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return MergeKind.WHOLE;
        }
        return switch (path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)) {
            case "docx", "docm" -> MergeKind.DOCX;
            case "xlsx", "xlsm" -> MergeKind.XLSX;
            case "pptx" -> MergeKind.PPTX;
            default -> MergeKind.WHOLE;
        };
    }

    public static Analysis analyze(String path, byte[] base, byte[] main, byte[] other) {
        MergeKind kind = kindOf(path);
        if (base == null || main == null || other == null) {
            return whole(base == null ? MergeKind.WHOLE : kind, MergeReason.NO_BASE);
        }
        if (kind == MergeKind.WHOLE) {
            return whole(MergeKind.WHOLE, MergeReason.BINARY);
        }
        if (base.length > MAX_BYTES || main.length > MAX_BYTES || other.length > MAX_BYTES) {
            return whole(kind, MergeReason.TOO_LARGE);
        }
        try {
            return switch (kind) {
                case DOCX -> analyzeSequence(DocxUnitReader.read(base), DocxUnitReader.read(main),
                        DocxUnitReader.read(other));
                case XLSX -> analyzeCells(XlsxCellReader.read(base), XlsxCellReader.read(main),
                        XlsxCellReader.read(other));
                case PPTX -> analyzeSlides(PptxSlideReader.read(base), PptxSlideReader.read(main),
                        PptxSlideReader.read(other));
                case WHOLE -> whole(MergeKind.WHOLE, MergeReason.BINARY);
            };
        } catch (Exception e) {
            return whole(kind, MergeReason.PARSE_FAILED);
        }
    }

    // ------------------------------------------------------------------ docx

    private static Analysis analyzeSequence(List<Unit> base, List<Unit> main, List<Unit> other) {
        MergeResult<RawText> result = new MergeAlgorithm()
                .merge(RawTextComparator.DEFAULT, hashLines(base), hashLines(main), hashLines(other));

        List<MergeChunk> chunks = new ArrayList<>();
        for (MergeChunk chunk : result) {
            chunks.add(chunk);
        }

        List<Chunk> mainChunks = new ArrayList<>();
        List<Chunk> otherChunks = new ArrayList<>();
        List<Overlap> overlaps = new ArrayList<>();

        int basePos = 0;
        int i = 0;
        while (i < chunks.size()) {
            MergeChunk chunk = chunks.get(i);
            if (chunk.getConflictState() == MergeChunk.ConflictState.NO_CONFLICT) {
                if (chunk.getSequenceIndex() == 0) {
                    basePos = chunk.getEnd();
                    i++;
                    continue;
                }
                int baseEnd = nextBaseBegin(chunks, i + 1, base.size());
                List<String> texts = textsOf(chunk.getSequenceIndex() == 1 ? main : other, chunk);
                Chunk converted = toChunk(basePos, baseEnd, texts, false);
                (chunk.getSequenceIndex() == 1 ? mainChunks : otherChunks).add(converted);
                basePos = baseEnd;
                i++;
                continue;
            }

            // 冲突组：FIRST(ours) / BASE / NEXT(theirs) 连着出现
            int groupEnd = i;
            MergeChunk mainPart = null;
            MergeChunk otherPart = null;
            MergeChunk basePart = null;
            while (groupEnd < chunks.size()
                    && chunks.get(groupEnd).getConflictState() != MergeChunk.ConflictState.NO_CONFLICT) {
                MergeChunk part = chunks.get(groupEnd);
                switch (part.getSequenceIndex()) {
                    case 0 -> basePart = part;
                    case 1 -> mainPart = part;
                    default -> otherPart = part;
                }
                groupEnd++;
            }
            int baseStart = basePart != null ? basePart.getBegin() : basePos;
            int baseEnd = basePart != null ? basePart.getEnd() : nextBaseBegin(chunks, groupEnd, base.size());
            List<String> mainTexts = mainPart == null ? List.of() : textsOf(main, mainPart);
            List<String> otherTexts = otherPart == null ? List.of() : textsOf(other, otherPart);
            if (mainPart != null) {
                mainChunks.add(toChunk(baseStart, baseEnd, mainTexts, true));
            }
            if (otherPart != null) {
                otherChunks.add(toChunk(baseStart, baseEnd, otherTexts, true));
            }
            overlaps.addAll(overlapsOf(base, baseStart, baseEnd, mainTexts, otherTexts));
            basePos = baseEnd;
            i = groupEnd;
        }

        boolean conflicted = !overlaps.isEmpty();
        return new Analysis(MergeKind.DOCX,
                conflicted ? MergeDecision.MANUAL : MergeDecision.AUTO,
                conflicted ? MergeReason.OVERLAP : MergeReason.CLEAN,
                countChanges(mainChunks), countChanges(otherChunks),
                List.copyOf(overlaps), List.of(), List.of(),
                new MergePlan(List.copyOf(mainChunks), List.copyOf(otherChunks)),
                List.copyOf(base));
    }

    private static int nextBaseBegin(List<MergeChunk> chunks, int from, int baseSize) {
        for (int i = from; i < chunks.size(); i++) {
            if (chunks.get(i).getSequenceIndex() == 0) {
                return chunks.get(i).getBegin();
            }
        }
        return baseSize;
    }

    private static Chunk toChunk(int baseStart, int baseEnd, List<String> texts, boolean conflict) {
        String type;
        if (baseEnd == baseStart) {
            type = "INSERT";
        } else if (texts.isEmpty()) {
            type = "DELETE";
        } else {
            type = "MODIFY";
        }
        return new Chunk(type, baseStart, baseEnd, List.copyOf(texts), List.of(), conflict);
    }

    private static List<String> textsOf(List<Unit> units, MergeChunk chunk) {
        List<String> texts = new ArrayList<>();
        for (int i = chunk.getBegin(); i < chunk.getEnd() && i < units.size(); i++) {
            texts.add(units.get(i).text());
        }
        return texts;
    }

    private static List<Overlap> overlapsOf(List<Unit> base, int baseStart, int baseEnd,
                                            List<String> mainTexts, List<String> otherTexts) {
        List<Overlap> overlaps = new ArrayList<>();
        if (baseEnd > baseStart) {
            for (int i = baseStart; i < baseEnd && i < base.size(); i++) {
                int offset = i - baseStart;
                overlaps.add(new Overlap(base.get(i).key(), base.get(i).text(),
                        offset < mainTexts.size() ? mainTexts.get(offset) : "",
                        offset < otherTexts.size() ? otherTexts.get(offset) : ""));
            }
            return overlaps;
        }
        // 两边在同一处各插了内容：基线没有对应单元，挂在插入点那个单元上
        String key = baseStart < base.size() ? base.get(baseStart).key() : "p" + base.size();
        overlaps.add(new Overlap(key, "", String.join("\n", mainTexts), String.join("\n", otherTexts)));
        return overlaps;
    }

    private static int countChanges(List<Chunk> chunks) {
        int total = 0;
        for (Chunk chunk : chunks) {
            total += Math.max(chunk.texts().size(), chunk.baseEnd() - chunk.baseStart());
        }
        return total;
    }

    private static RawText hashLines(List<Unit> units) {
        StringBuilder sb = new StringBuilder();
        for (Unit unit : units) {
            sb.append(sha256Hex(unit.norm())).append('\n');
        }
        return new RawText(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ xlsx

    private static Analysis analyzeCells(Map<String, String> base, Map<String, String> main,
                                         Map<String, String> other) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(base.keySet());
        keys.addAll(main.keySet());
        keys.addAll(other.keySet());

        List<String> mainOnly = new ArrayList<>();
        List<String> otherOnly = new ArrayList<>();
        List<Overlap> overlaps = new ArrayList<>();
        int mainChanges = 0;
        int otherChanges = 0;
        for (String key : keys) {
            String b = base.getOrDefault(key, "");
            String m = main.getOrDefault(key, "");
            String o = other.getOrDefault(key, "");
            boolean mainChanged = !b.equals(m);
            boolean otherChanged = !b.equals(o);
            if (mainChanged) {
                mainChanges++;
            }
            if (otherChanged) {
                otherChanges++;
            }
            if (mainChanged && otherChanged) {
                overlaps.add(new Overlap(key, b, m, o));
            } else if (mainChanged) {
                mainOnly.add(key);
            } else if (otherChanged) {
                otherOnly.add(key);
            }
        }

        List<Unit> baseUnits = new ArrayList<>();
        base.forEach((k, v) -> baseUnits.add(new Unit(k, v, DocxUnitReader.normalize(v))));
        boolean conflicted = !overlaps.isEmpty();
        return new Analysis(MergeKind.XLSX,
                conflicted ? MergeDecision.MANUAL : MergeDecision.AUTO,
                conflicted ? MergeReason.OVERLAP : MergeReason.CLEAN,
                mainChanges, otherChanges, List.copyOf(overlaps), List.copyOf(mainOnly), List.copyOf(otherOnly),
                new MergePlan(List.of(), List.of()), List.copyOf(baseUnits));
    }

    // ------------------------------------------------------------------ pptx

    private static Analysis analyzeSlides(List<Slide> base, List<Slide> main, List<Slide> other) {
        Map<String, Slide> baseById = byId(base);
        Map<String, Slide> mainById = byId(main);
        Map<String, Slide> otherById = byId(other);

        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(baseById.keySet());
        ids.addAll(mainById.keySet());
        ids.addAll(otherById.keySet());

        List<String> mainOnly = new ArrayList<>();
        List<String> otherOnly = new ArrayList<>();
        List<Overlap> overlaps = new ArrayList<>();
        List<Chunk> mainChunks = new ArrayList<>();
        List<Chunk> otherChunks = new ArrayList<>();
        int mainChanges = 0;
        int otherChanges = 0;

        for (String id : ids) {
            Slide b = baseById.get(id);
            Slide m = mainById.get(id);
            Slide o = otherById.get(id);
            boolean mainChanged = changed(b, m);
            boolean otherChanged = changed(b, o);
            if (!mainChanged && !otherChanged) {
                continue;
            }
            String key = keyOf(b, m, o);
            if (mainChanged) {
                mainChanges++;
                mainChunks.add(slideChunk(base, b, m, id, mainChanged && otherChanged));
            }
            if (otherChanged) {
                otherChanges++;
                otherChunks.add(slideChunk(base, b, o, id, mainChanged && otherChanged));
            }
            if (mainChanged && otherChanged) {
                overlaps.add(new Overlap(key, b == null ? "" : b.text(), m == null ? "" : m.text(),
                        o == null ? "" : o.text()));
            } else if (mainChanged) {
                mainOnly.add(key);
            } else {
                otherOnly.add(key);
            }
        }

        String baseOrder = orderOf(base, baseById.keySet());
        String mainOrder = orderOf(main, baseById.keySet());
        String otherOrder = orderOf(other, baseById.keySet());
        if (!baseOrder.equals(mainOrder) && !baseOrder.equals(otherOrder)) {
            overlaps.add(new Overlap(ORDER_KEY, baseOrder, mainOrder, otherOrder));
        }

        boolean conflicted = !overlaps.isEmpty();
        return new Analysis(MergeKind.PPTX,
                conflicted ? MergeDecision.MANUAL : MergeDecision.AUTO,
                conflicted ? MergeReason.OVERLAP : MergeReason.CLEAN,
                mainChanges, otherChanges, List.copyOf(overlaps), List.copyOf(mainOnly), List.copyOf(otherOnly),
                new MergePlan(List.copyOf(mainChunks), List.copyOf(otherChunks)), List.copyOf(slideUnits(base)));
    }

    private static Map<String, Slide> byId(List<Slide> slides) {
        Map<String, Slide> map = new LinkedHashMap<>();
        for (Slide slide : slides) {
            map.put(slide.sldId(), slide);
        }
        return map;
    }

    private static boolean changed(Slide base, Slide side) {
        if (base == null || side == null) {
            return base != side;
        }
        return !DocxUnitReader.normalize(base.text()).equals(DocxUnitReader.normalize(side.text()));
    }

    /** 页键取基线页序；基线里没有这一页（该侧新增）时退回该侧页序。 */
    private static String keyOf(Slide base, Slide main, Slide other) {
        Slide any = base != null ? base : (main != null ? main : other);
        return "s" + any.ordinal();
    }

    private static Chunk slideChunk(List<Slide> base, Slide baseSlide, Slide sideSlide, String sldId,
                                    boolean conflict) {
        int baseIndex = baseSlide == null ? base.size() : baseSlide.ordinal() - 1;
        int baseEnd = baseSlide == null ? baseIndex : baseIndex + 1;
        List<String> texts = sideSlide == null ? List.of() : List.of(sideSlide.text());
        String type;
        if (baseSlide == null) {
            type = "INSERT";
        } else if (sideSlide == null) {
            type = "DELETE";
        } else {
            type = "MODIFY";
        }
        return new Chunk(type, baseIndex, baseEnd, texts, List.of(sldId), conflict);
    }

    /** 只看两边都还留着的那些页的相对次序，新增/删除不算「调了页序」。 */
    private static String orderOf(List<Slide> slides, Set<String> baseIds) {
        List<String> ids = new ArrayList<>();
        for (Slide slide : slides) {
            if (baseIds.contains(slide.sldId())) {
                ids.add(slide.sldId());
            }
        }
        return String.join(",", ids);
    }

    private static List<Unit> slideUnits(List<Slide> slides) {
        List<Unit> units = new ArrayList<>();
        for (Slide slide : slides) {
            units.add(new Unit("s" + slide.ordinal(), slide.text(), DocxUnitReader.normalize(slide.text())));
        }
        return units;
    }

    // ----------------------------------------------------------------- 公共

    private static Analysis whole(MergeKind kind, MergeReason reason) {
        return new Analysis(kind, MergeDecision.WHOLE, reason, 0, 0, List.of(), List.of(), List.of(),
                new MergePlan(List.of(), List.of()), List.of());
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
