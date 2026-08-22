package com.checkba.service.evidence;

import com.checkba.model.entity.EvidenceLink;
import com.checkba.model.entity.EvidenceLinkTarget;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.Tag;
import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.repository.EvidenceLinkTargetRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.TagRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.evidence.EvidenceChecks.Check;
import com.checkba.service.evidence.EvidenceChecks.Party;
import com.checkba.service.evidence.EvidenceVerifyViews.BatchQuery;
import com.checkba.service.evidence.EvidenceVerifyViews.BatchResult;
import com.checkba.service.evidence.EvidenceVerifyViews.LinkVerdict;
import com.checkba.service.evidence.EvidenceVerifyViews.TargetVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 勾稽核查（P2，dev-board#116）：对一条 {@link EvidenceLink} 做「陈述 ↔ 底稿」一致性判定，
 * 把结论落到它的每个 {@link EvidenceLinkTarget} 上。
 *
 * <h3>判什么</h3>
 * 输入 = link 的 {@code anchorText}（报告里那句陈述）+ 每个 target 指向文件的可读文本
 * （{@link EvidenceTextExtractor}，文档走 Tika/PDFBox、图片走 OCR）。判定全在
 * {@link EvidenceChecks}（纯函数，四类可机器校验的要素），<b>不调 LLM、不做语义判定</b>。
 *
 * <h3>写什么</h3>
 * 每个 target 写 {@code relation}（supports/partial/contradicts，沿用 P0 枚举）、
 * {@code confidence}（0-100）、{@code verify_json}（结构化 findings，整条覆盖、幂等）。
 * <b>不写 {@link EvidenceLink#getStatus()}</b>——P0 定死状态只由 worker 核对结果与用户动作驱动，
 * 核查结论走 relation/confidence/verify_json 三处表达，四个状态值一个不加（spec §1.3）。
 *
 * <h3>缺证据 ≠ 矛盾</h3>
 * 底稿读不出文字、陈述里没有可核要素、要素在底稿里查不到，一律 {@code unverifiable}：
 * {@code relation} 原样不动、{@code confidence} 置空。绝不因为"没找到"就判 contradicts。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvidenceVerifyService {

    /** 一次批量最多核查多少条 link（够一章，且能在一次 HTTP 请求里跑完）。 */
    public static final int MAX_BATCH_LINKS = 200;
    public static final int DEFAULT_BATCH_LINKS = 50;
    /** 单份底稿抽文本的超时：Tika/PDFBox/OCR 都可能在坏文件上卡死，不能让它拖垮整批。 */
    static final long TEXT_TIMEOUT_SECONDS = 45;
    /** 批量的墙钟预算：到点就停下回 nextOffset，让客户端接着调，而不是把请求挂死。 */
    static final long BATCH_DEADLINE_MS = 90_000;
    /** 批内底稿文本缓存条数（一份 20 万字符上限，16 份约 6MB）。 */
    static final int TEXT_CACHE_SIZE = 16;

    private static final String PARTY = "PARTY";

    private final EvidenceLinkRepository links;
    private final EvidenceLinkTargetRepository targets;
    private final ProjectFileRepository files;
    private final TagRepository tags;
    private final ProjectMemberService members;
    private final EvidenceTextExtractor extractor;
    private final ObjectMapper om;

    /** 在跑的批次：key = projectId:userId，value = 取消标记。照 PluginJobService 的 AtomicBoolean 轮询法。 */
    private final Map<String, AtomicBoolean> runningBatches = new ConcurrentHashMap<>();

    private final ExecutorService textPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "evidence-verify-text");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        textPool.shutdownNow();
    }

    // ---------------------------------------------------------------- 单条

    /**
     * 核查一条 link 的全部 target。核查是写操作，要写权限。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：核查是长跑、可续、可取消、幂等的过程，
     * 每条 link 各自落库才是要的语义——被取消或超时时已核的部分留在库里，而不是整批回滚；
     * 批量最长能跑 90 秒，包在一个事务里等于把连接占住 90 秒。
     * Repository 的 save 自带事务，够用。
     */
    public LinkVerdict verifyLink(Long userId, Long projectId, String linkKey) {
        requireWrite(projectId, userId);
        EvidenceLink l = requireLink(projectId, linkKey);
        return verifyOne(userId, l, parties(projectId), new TextCache(userId));
    }

    // ---------------------------------------------------------------- 批量

    /**
     * 按报告（必填）+ 章节前缀 / 状态筛一批 link 依次核查。
     * 撞 {@code limit}、撞墙钟预算或被取消都会停下并回 {@code nextOffset}——原样再调一次即续跑。
     * 同上，不包事务。
     */
    public BatchResult verifyBatch(Long userId, Long projectId, BatchQuery q) {
        requireWrite(projectId, userId);
        if (q == null || q.docFileId() == null) {
            throw new IllegalArgumentException(LangText.of("docFileId 必填", "docFileId required"));
        }
        List<EvidenceLink> all = select(projectId, q);
        int offset = q.offset() == null || q.offset() < 0 ? 0 : q.offset();
        int limit = q.limit() == null || q.limit() <= 0 ? DEFAULT_BATCH_LINKS : Math.min(q.limit(), MAX_BATCH_LINKS);

        String key = projectId + ":" + userId;
        AtomicBoolean cancel = new AtomicBoolean();
        runningBatches.put(key, cancel);
        List<Party> parties = parties(projectId);
        TextCache cache = new TextCache(userId);
        Map<String, Integer> tally = new LinkedHashMap<>();
        List<LinkVerdict> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + BATCH_DEADLINE_MS;
        int i = offset;
        try {
            while (i < all.size() && out.size() < limit) {
                if (cancel.get() || System.currentTimeMillis() > deadline) break;
                LinkVerdict v = verifyOne(userId, all.get(i), parties, cache);
                out.add(v);
                tally.merge(v.verdict(), 1, Integer::sum);
                i++;
            }
        } finally {
            runningBatches.remove(key, cancel);
        }
        Integer next = i < all.size() ? i : null;
        return new BatchResult(all.size(), offset, out.size(), next, cancel.get(), tally, out);
    }

    /** 取消该用户在该项目里正在跑的批次；没有在跑的返回 false。取消要写权限（与核查一致，只读成员看不到也停不了别人）。 */
    public boolean cancelBatch(Long userId, Long projectId) {
        requireWrite(projectId, userId);
        AtomicBoolean flag = runningBatches.get(projectId + ":" + userId);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }

    private List<EvidenceLink> select(Long projectId, BatchQuery q) {
        List<EvidenceLink> rows = StringUtils.hasText(q.sectionPath())
                ? links.findByProjectIdAndDocFileIdAndSectionPathStartingWithOrderByIdAsc(projectId, q.docFileId(), q.sectionPath().trim())
                : links.findByProjectIdAndDocFileIdOrderByIdAsc(projectId, q.docFileId());
        if (StringUtils.hasText(q.status())) {
            String s = q.status().trim();
            rows = rows.stream().filter(l -> s.equals(l.getStatus())).toList();
        }
        return rows;
    }

    // ---------------------------------------------------------------- 核心

    private LinkVerdict verifyOne(Long userId, EvidenceLink l, List<Party> parties, TextCache cache) {
        List<EvidenceLinkTarget> rows = targets.findByLinkIdOrderBySortOrderAscIdAsc(l.getId());
        Map<Long, ProjectFile> briefs = fileBriefs(rows);
        LocalDateTime now = LocalDateTime.now();
        List<TargetVerdict> out = new ArrayList<>(rows.size());
        for (EvidenceLinkTarget t : rows) {
            ProjectFile f = briefs.get(t.getFileId());
            String draft = f == null ? null : cache.get(f);
            List<Check> checks;
            String verdict;
            String note = null;
            if (draft == null) {
                // 读不出文字 ≠ 底稿与陈述矛盾。这里必须原样保留 relation，只把 confidence 置空。
                checks = List.of();
                verdict = EvidenceChecks.VERDICT_UNVERIFIABLE;
                note = f == null
                        ? LangText.of("底稿文件不存在或已删除", "Target file is missing or deleted")
                        : LangText.of("底稿读不出可核对的文字（扫描件无文本层 / 格式不支持 / 抽取失败）",
                        "No readable text from the target file");
            } else {
                checks = EvidenceChecks.run(l.getAnchorText(), draft, parties);
                verdict = EvidenceChecks.verdict(checks);
                if (checks.isEmpty()) {
                    note = LangText.of("这句陈述里没有可机器校验的要素（代码/日期/金额/主体）",
                            "No machine-checkable element in this statement");
                }
            }
            Short confidence = EvidenceChecks.confidence(checks);
            String priorRelation = t.getRelation();
            if (!EvidenceChecks.VERDICT_UNVERIFIABLE.equals(verdict)) {
                t.setRelation(verdict);
            }
            t.setConfidence(confidence);
            t.setVerifyJson(findings(now, verdict, checks, note, priorRelation));
            targets.save(t);
            out.add(new TargetVerdict(t.getId(), t.getFileId(), f == null ? null : f.getName(),
                    t.getRelation(), confidence, verdict, checks));
        }
        l.setUpdatedAt(now);
        links.save(l);
        return new LinkVerdict(l.getLinkKey(), l.getDocFileId(), l.getSectionPath(), l.getAnchorText(),
                worst(out), out);
    }

    /** link 的结论 = 各 target 里最坏的一个；一个 target 都没有时按 unverifiable。 */
    private static String worst(List<TargetVerdict> targets) {
        String w = EvidenceChecks.VERDICT_UNVERIFIABLE;
        for (TargetVerdict t : targets) {
            if (EvidenceChecks.VERDICT_CONTRADICTS.equals(t.verdict())) return EvidenceChecks.VERDICT_CONTRADICTS;
            if (rank(t.verdict()) > rank(w)) w = t.verdict();
        }
        return w;
    }

    private static int rank(String verdict) {
        return switch (verdict) {
            case EvidenceChecks.VERDICT_CONTRADICTS -> 3;
            case EvidenceChecks.VERDICT_PARTIAL -> 2;
            case EvidenceChecks.VERDICT_SUPPORTS -> 1;
            default -> 0;
        };
    }

    /** {@code {checkedAt, verdict, checks:[{kind,expected,found,ok,note}], note?, priorRelation?}}，整条覆盖。 */
    private String findings(LocalDateTime at, String verdict, List<Check> checks, String note, String priorRelation) {
        ObjectNode root = om.createObjectNode();
        root.put("checkedAt", at.toString());
        root.put("verdict", verdict);
        ArrayNode arr = root.putArray("checks");
        for (Check c : checks) {
            ObjectNode n = arr.addObject();
            n.put("kind", c.kind());
            n.put("expected", c.expected());
            n.put("found", c.found());
            if (c.ok() == null) n.putNull("ok"); else n.put("ok", c.ok());
            if (c.note() != null) n.put("note", c.note());
        }
        if (note != null) root.put("note", note);
        // 人工改过的 relation 会被本次结论覆盖，先把旧值留在案卷里，面板要回溯时不至于查无对证
        if (priorRelation != null) root.put("priorRelation", priorRelation);
        try {
            return om.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 辅助

    /** 项目里的 PARTY 标签 = 主体名词表；别名取自 {@code Tag.description}（须带标签）与派生简称。 */
    private List<Party> parties(Long projectId) {
        List<Party> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Tag t : tags.findByProjectId(projectId)) {
            if (!PARTY.equals(t.getType()) || !StringUtils.hasText(t.getName()) || !seen.add(t.getName())) continue;
            out.add(new Party(t.getName(), EvidenceChecks.aliasesOf(t.getName(), t.getDescription())));
        }
        return out;
    }

    private Map<Long, ProjectFile> fileBriefs(List<EvidenceLinkTarget> rows) {
        Map<Long, ProjectFile> out = new LinkedHashMap<>();
        if (rows.isEmpty()) return out;
        List<Long> ids = rows.stream().map(EvidenceLinkTarget::getFileId).distinct().toList();
        for (ProjectFile f : files.findAllById(ids)) out.put(f.getId(), f);
        return out;
    }

    private void requireWrite(Long projectId, Long userId) {
        if (userId == null || projectId == null || !members.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权限修改该项目", "No write access to this project"));
        }
    }

    private EvidenceLink requireLink(Long projectId, String linkKey) {
        if (!StringUtils.hasText(linkKey)) {
            throw new IllegalArgumentException(LangText.of("linkKey 不能为空", "linkKey must not be empty"));
        }
        return links.findByProjectIdAndLinkKey(projectId, linkKey.trim())
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("链接不存在", "Link not found")));
    }

    /**
     * 批内的底稿文本缓存 + 单份超时保护。
     * 超时/失败都缓存成"取不到"，同一份坏文件在一批里只卡一次。
     */
    private final class TextCache {
        private final long userId;
        /** 只在发起核查的那个线程上用（抽文本才跳线程，缓存本身不跨线程），所以不加锁。 */
        private final Map<Long, String> byFile = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
                return size() > TEXT_CACHE_SIZE;
            }
        };
        private final Set<Long> missing = new LinkedHashSet<>();

        TextCache(long userId) {
            this.userId = userId;
        }

        String get(ProjectFile f) {
            if (missing.contains(f.getId())) return null;
            String cached = byFile.get(f.getId());
            if (cached != null) return cached;
            String text = withTimeout(f);
            if (text == null) {
                missing.add(f.getId());
                return null;
            }
            byFile.put(f.getId(), text);
            return text;
        }

        private String withTimeout(ProjectFile f) {
            Future<String> task = textPool.submit(() -> extractor.textOf(f, userId));
            try {
                return task.get(TEXT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                task.cancel(true);
                log.warn("勾稽核查抽底稿文本超时 fileId={} name={}", f.getId(), f.getName());
                return null;
            } catch (InterruptedException e) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.warn("勾稽核查抽底稿文本失败 fileId={}: {}", f.getId(), e.getMessage());
                return null;
            }
        }
    }
}
