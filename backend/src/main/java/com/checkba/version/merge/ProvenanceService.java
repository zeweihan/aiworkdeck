// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import com.checkba.service.LangText;
import com.checkba.version.CloudSyncService;
import com.checkba.version.HistoryTypeClassifier;
import com.checkba.version.ProjectRepoService;
import com.checkba.version.VersionAuthorResolver;
import com.checkba.version.VersionEntry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FollowFilter;
import org.eclipse.jgit.revwalk.RenameCallback;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 逐段溯源（dev-board#632，设计稿 §4.7）：这份文件的每一段 / 每一格 / 每一页，
 * 最后是哪一版改的、谁改的、什么时候改的。
 *
 * <h3>归属规则（只有两条）</h3>
 * 沿这份文件的历史从 {@code ref} 往回走，对每一版 c：
 * <ol>
 *   <li>这一处文字在**某个父版本里原样存在** → 继承那个父版本的归属；
 *       <b>先看第一父，再看第二父</b>；</li>
 *   <li>都找不到 → 这一处就是 c 改的。</li>
 * </ol>
 * 第二父那一步是这套东西里最值钱的一行：合并提交里来自另一侧的段落必须归**对方那一版**，
 * 归到「我按下确认那一刻」的合并提交上，律师就再也看不出这份合同里哪几段是对方加的。
 *
 * <h3>「同一处文字」怎么判（降级口径，spike B1）</h3>
 * 引擎导出 docx 会把 {@code w14:paraId} 整类丢掉，注入隐藏书签又会改写用户产物，
 * 所以**没有稳定 id 可用**。docx 按归一文字的哈希序列跑一次 {@link HistogramDiff}，
 * 落在未变块里的段落算同一处；xlsx 按单元格键、pptx 按 {@code sldId} 对齐，值相等即同一处。
 * 精度损失就这些：改了一个字的段落、被剪切粘到别处的段落归本版（「移动」本来就该算一次改动），
 * 两段一模一样的文字按位置对齐、对错了也是同样的文字。规格不假装有稳定 id。
 *
 * <h3>预算</h3>
 * 最多回溯 {@link #MAX_HISTORY} 版，更早的只说「更早的版本」（{@code sha=null}）并置
 * {@code truncated}。逐版结果缓存在 {@code <gitdir>/awd-cache/provenance/}——放 gitdir
 * 不放工作区，{@code git add .} 收不到它，也不会混进律师的文件树。缓存命中即停。
 * 单次请求最多等 {@link #REQUEST_BUDGET}，超了回 {@code computing:true}（后台继续算，
 * 前端过几秒再问一次），绝不把一个锦上添花的侧栏挂成几十秒的白屏。
 */
@Service
public class ProvenanceService {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceService.class);

    /** 最多回溯多少版。再往前的一律记「更早的版本」——这是产品口径，不是实现细节。 */
    public static final int MAX_HISTORY = 500;

    /** 单次请求最多等多久；超了回 computing 让前端过几秒再问。 */
    private static final Duration REQUEST_BUDGET = Duration.ofSeconds(30);

    /** 缓存文件格式版本：口径变了就换号，老文件自然失效。 */
    private static final int CACHE_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectRepoService repoService;

    /**
     * 署名解析与案件库展示名映射。两者都是字段注入且允许缺席：单测手工 new 本服务，
     * 没有它们时退回 git 署名、{@code self} 一律 false——溯源少一个「你」字，不影响归属本身。
     */
    @Autowired(required = false)
    private VersionAuthorResolver authorResolver;

    @Autowired(required = false)
    private CloudSyncService cloudSyncService;

    /**
     * 后台算溯源用既有的 awd-async 池（{@code AsyncExecutorConfig.taskExecutor}）。
     * 缺席（单测手工 new）时退回一个自带的两线程守护池。
     */
    @Autowired(required = false)
    @Qualifier("taskExecutor")
    private Executor springExecutor;

    private volatile ExecutorService fallbackExecutor;

    /**
     * 同一个 (项目, 路径, 版本) 只算一遍：请求超时返回 computing 之后后台还在跑，
     * 三秒后的重试要搭上同一趟车，不能各算各的。
     */
    private final Map<String, CompletableFuture<Computed>> inflight = new ConcurrentHashMap<>();

    private volatile int maxHistory = MAX_HISTORY;

    public ProvenanceService(ProjectRepoService repoService) {
        this.repoService = repoService;
    }

    /** 只给测试用：把回溯上限调小，免得为了验一条规则去造 500 笔提交。 */
    void setMaxHistoryForTest(int limit) {
        this.maxHistory = limit;
    }

    // ==================================================================== 对外

    /**
     * @param relPath 仓库内相对路径（由调用方从 fileId 解析并校验归属）
     * @param ref     版本引用，通常是 {@code HEAD}
     * @return {@code {ref, kind, units, truncated, computing}}；units 见 {@link ProvenanceUnit}
     */
    public Map<String, Object> provenance(long projectId, long userId, String relPath, String ref) {
        String wanted = ref == null || ref.isBlank() ? "HEAD" : ref.trim();
        MergeKind kind = ThreeWayAnalyzer.kindOf(relPath);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", wanted);
        out.put("kind", kind.name().toLowerCase(Locale.ROOT));
        out.put("units", List.of());
        out.put("truncated", false);
        out.put("computing", false);
        // 文件级版本身份（dev-board#672 复测）：小条出不出现看 versioned，说什么看后两个
        out.put("versioned", false);
        out.put("fileVersion", null);
        out.put("dirty", false);

        String sha = repoService.resolveRef(projectId, wanted);
        if (sha == null) {
            return out;
        }
        out.put("ref", sha);
        out.put("versioned", true);
        putFileVersion(out, projectId, userId, relPath, sha);

        Computed computed;
        try {
            computed = submit(projectId, relPath, sha).get(REQUEST_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 后台继续算，算完落缓存；前端过几秒再问一次就命中了
            out.put("computing", true);
            return out;
        } catch (Exception e) {
            // 溯源是锦上添花：算不出来就当没有，不把编辑器的侧栏挂成一个错误弹窗
            log.warn("算溯源失败: project={} path={} ref={}", projectId, relPath, sha, e);
            return out;
        }

        out.put("units", enrich(projectId, userId, computed.units()));
        out.put("truncated", computed.truncated());
        return out;
    }

    /**
     * 提交钩子：这一版落定之后，把本次变更过的文档的溯源在后台先算好，
     * 律师下次打开侧栏就是秒开。任何异常只记日志——**绝不影响已经落定的提交**。
     */
    public void precomputeAsync(long projectId, String sha, List<String> relPaths) {
        if (sha == null || sha.isBlank() || relPaths == null || relPaths.isEmpty()) {
            return;
        }
        for (String path : new LinkedHashSet<>(relPaths)) {
            if (path == null || path.isBlank() || path.startsWith(".awd/")) {
                continue;
            }
            if (ThreeWayAnalyzer.kindOf(path) == MergeKind.WHOLE) {
                continue; // 整份文件那一档只有一条记录，现算也快，不值得占后台线程
            }
            try {
                submit(projectId, path, sha);
            } catch (Exception e) {
                log.debug("预算溯源没排上队: project={} path={}", projectId, path, e);
            }
        }
    }

    // ==================================================================== 调度

    private CompletableFuture<Computed> submit(long projectId, String relPath, String sha) {
        String key = projectId + ":" + sha + ":" + relPath;
        CompletableFuture<Computed> mine = new CompletableFuture<>();
        CompletableFuture<Computed> running = inflight.putIfAbsent(key, mine);
        if (running != null) {
            return running;
        }
        executor().execute(() -> {
            try {
                mine.complete(compute(projectId, relPath, sha));
            } catch (Throwable t) {
                mine.completeExceptionally(t);
            } finally {
                inflight.remove(key, mine);
            }
        });
        return mine;
    }

    private Executor executor() {
        if (springExecutor != null) {
            return springExecutor;
        }
        ExecutorService local = fallbackExecutor;
        if (local == null) {
            synchronized (this) {
                if (fallbackExecutor == null) {
                    fallbackExecutor = Executors.newFixedThreadPool(2, r -> {
                        Thread t = new Thread(r, "awd-provenance");
                        t.setDaemon(true);
                        return t;
                    });
                }
                local = fallbackExecutor;
            }
        }
        return local;
    }

    // ==================================================================== 计算

    /** 一次计算的结果：逐单元的归属 + 是否回溯到了窗口边界。 */
    record Computed(List<Attr> units, boolean truncated) {
    }

    /**
     * 一个单元的归属。{@code id} 是按键对齐用的稳定身份（xlsx 单元格键 / pptx sldId /
     * 整份文件那一档的常量），docx 没有稳定 id、按序列对齐，所以是 null。
     * {@code sha} 为 null 表示「更早的版本」。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Attr(String key, String id, String hash, String sha) {
    }

    /** 缓存文件的形状。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CacheFile(int v, String path, boolean truncated, List<Attr> units) {
    }

    private Computed compute(long projectId, String relPath, String headSha) throws IOException {
        CacheFile hit = readCache(projectId, relPath, headSha);
        if (hit != null) {
            return new Computed(hit.units(), hit.truncated());
        }

        MergeKind kind = ThreeWayAnalyzer.kindOf(relPath);
        int limit = Math.max(1, maxHistory);

        try (Repository repo = repoService.open(projectId); RevWalk walk = new RevWalk(repo)) {
            ObjectId head = repo.resolve(headSha);
            if (head == null) {
                return new Computed(List.of(), false);
            }

            // 跟着改名走：回调把历史上的旧路径攒起来，逐版读字节时按这张表挨个试。
            // 不靠「第几版之后换路径」去对号入座——JGit 什么时候发现改名与什么时候吐出
            // 那一版之间隔着一层重写父提交的生成器，按顺序对位很容易差一版，差一版
            // 就等于那一版的段落全部错签。
            List<String> candidates = new ArrayList<>();
            candidates.add(relPath);
            FollowFilter follow = FollowFilter.create(relPath, renameAwareDiffConfig(repo));
            follow.setRenameCallback(new RenameCallback() {
                @Override
                public void renamed(DiffEntry entry) {
                    String old = entry.getOldPath();
                    if (old != null && !old.equals(DiffEntry.DEV_NULL) && !candidates.contains(old)) {
                        candidates.add(old);
                    }
                }
            });
            walk.setTreeFilter(follow);
            walk.sort(RevSort.TOPO);
            walk.markStart(walk.parseCommit(head));

            List<RevCommit> ordered = new ArrayList<>();
            for (RevCommit c : walk) {
                ordered.add(c);
                if (ordered.size() > limit) {
                    break;
                }
            }
            boolean capped = ordered.size() > limit;
            if (capped) {
                ordered.remove(ordered.size() - 1);
            }
            if (ordered.isEmpty()) {
                return new Computed(List.of(), false);
            }

            // TOPO 保证子在父之前，倒着来就是「先父后子」——每一版算到时父版本一定已经就位
            Map<String, List<Attr>> memo = new HashMap<>();
            for (int i = ordered.size() - 1; i >= 0; i--) {
                RevCommit c = ordered.get(i);
                CacheFile cached = readCache(projectId, relPath, c.getName());
                if (cached != null) {
                    memo.put(c.getName(), cached.units());
                    continue;
                }
                List<Attr> attrs = attribute(repo, walk, c, candidates, kind, memo);
                if (attrs == null) {
                    continue; // 这一版读不出这份文件（体积闸/解析失败），跳过，交给它的子版本自己认领
                }
                memo.put(c.getName(), attrs);
                writeCache(projectId, relPath, c.getName(),
                        new CacheFile(CACHE_VERSION, relPath, anyEarlier(attrs), attrs));
            }

            // 请求的那一版自己可能根本没动过这份文件（follow 只吐动过的版本）：
            // 那就是「离它最近的、动过这份文件的那一版」的结果，内容逐字节相同。
            List<Attr> answer = memo.get(ordered.get(0).getName());
            if (answer == null) {
                return new Computed(List.of(), false);
            }
            // truncated 按**结果**说话而不是按「走没走到头」：回溯截断了但每一处都还是认到了
            // 某一版，对律师来说就没有「更早的版本」这回事，不该在界面上多出一句提醒。
            boolean truncated = anyEarlier(answer);
            if (!ordered.get(0).getName().equals(headSha)) {
                writeCache(projectId, relPath, headSha, new CacheFile(CACHE_VERSION, relPath, truncated, answer));
            }
            return new Computed(answer, truncated);
        }
    }

    /** 有没有哪一处只说得出「更早的版本」。 */
    private static boolean anyEarlier(List<Attr> attrs) {
        return attrs.stream().anyMatch(a -> a.sha() == null);
    }

    /**
     * 算一版的归属：先第一父、再第二父，都对不上的算本版改的。
     *
     * @return null 表示这一版读不出这份文件
     */
    private List<Attr> attribute(Repository repo, RevWalk walk, RevCommit c, List<String> candidates,
                                 MergeKind kind, Map<String, List<Attr>> memo) throws IOException {
        List<Attr> unitsC = unitsAt(repo, c, candidates, kind);
        if (unitsC == null) {
            return null;
        }
        String[] owner = new String[unitsC.size()];
        boolean[] taken = new boolean[unitsC.size()];

        for (RevCommit rawParent : c.getParents()) {
            RevCommit parent = walk.parseCommit(rawParent.getId());
            List<Attr> attrsP = memo.get(parent.getName());
            if (attrsP == null) {
                // 窗口之外的父版本：文字仍要对齐（不然这一版会把没改过的段落全认成自己改的），
                // 但归属只说得出「更早的版本」。
                List<Attr> unitsP = unitsAt(repo, parent, candidates, kind);
                if (unitsP == null) {
                    continue;
                }
                attrsP = unitsP.stream().map(u -> new Attr(u.key(), u.id(), u.hash(), null)).toList();
            }
            int[] map = align(kind, attrsP, unitsC);
            for (int k = 0; k < map.length; k++) {
                if (!taken[k] && map[k] >= 0) {
                    taken[k] = true;
                    owner[k] = attrsP.get(map[k]).sha();
                }
            }
        }

        List<Attr> out = new ArrayList<>(unitsC.size());
        for (int k = 0; k < unitsC.size(); k++) {
            Attr u = unitsC.get(k);
            out.add(new Attr(u.key(), u.id(), u.hash(), taken[k] ? owner[k] : c.getName()));
        }
        return out;
    }

    /**
     * 把两版的单元对上：{@code 返回值[子下标] = 父下标}，没对上的是 -1。
     *
     * <p>docx 没有稳定 id，只能按归一文字的哈希序列做一次 {@link HistogramDiff}，
     * 未变块里的才算同一处；xlsx/pptx 有稳定键（单元格地址 / sldId），按键对齐再比文字。
     */
    private int[] align(MergeKind kind, List<Attr> parent, List<Attr> child) {
        int[] map = new int[child.size()];
        Arrays.fill(map, -1);
        if (parent.isEmpty() || child.isEmpty()) {
            return map;
        }
        if (kind == MergeKind.DOCX) {
            EditList edits = new HistogramDiff().diff(RawTextComparator.DEFAULT,
                    hashLines(parent), hashLines(child));
            int a = 0;
            int b = 0;
            for (Edit e : edits) {
                for (int k = 0; k < e.getBeginA() - a && b + k < child.size(); k++) {
                    map[b + k] = a + k;
                }
                a = e.getEndA();
                b = e.getEndB();
            }
            for (int k = 0; a + k < parent.size() && b + k < child.size(); k++) {
                map[b + k] = a + k;
            }
            return map;
        }
        Map<String, Integer> byId = new HashMap<>();
        for (int j = 0; j < parent.size(); j++) {
            byId.putIfAbsent(parent.get(j).id(), j);
        }
        for (int k = 0; k < child.size(); k++) {
            Integer j = byId.get(child.get(k).id());
            if (j != null && java.util.Objects.equals(parent.get(j).hash(), child.get(k).hash())) {
                map[k] = j;
            }
        }
        return map;
    }

    private static RawText hashLines(List<Attr> units) {
        StringBuilder sb = new StringBuilder();
        for (Attr u : units) {
            sb.append(u.hash()).append('\n');
        }
        return new RawText(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ==================================================================== 读单元

    /**
     * 某一版里这份文件的单元序列（还没定归属，{@code sha} 恒为 null）。
     * 文件在这一版里不存在 / 超过体积闸 / 解析不了 → null。
     */
    private List<Attr> unitsAt(Repository repo, RevCommit commit, List<String> candidates, MergeKind kind)
            throws IOException {
        for (String path : candidates) {
            try (TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree())) {
                if (tw == null) {
                    continue;
                }
                ObjectId blobId = tw.getObjectId(0);
                if (kind == MergeKind.WHOLE) {
                    // 整份文件那一档：blob 的 id 就是它的「文字」，一个字节都不用读
                    return List.of(new Attr("file", "file", blobId.name(), null));
                }
                ObjectLoader loader = repo.open(blobId);
                if (loader.getSize() > ThreeWayAnalyzer.MAX_BYTES) {
                    return null;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                loader.copyTo(bos);
                return parse(bos.toByteArray(), kind);
            }
        }
        return null;
    }

    private List<Attr> parse(byte[] bytes, MergeKind kind) {
        try {
            return switch (kind) {
                case DOCX -> DocxUnitReader.read(bytes).stream()
                        .map(u -> new Attr(u.key(), null, ThreeWayAnalyzer.sha256Hex(u.norm()), null))
                        .toList();
                case XLSX -> XlsxCellReader.read(bytes).entrySet().stream()
                        .map(e -> new Attr(e.getKey(), e.getKey(),
                                ThreeWayAnalyzer.sha256Hex(DocxUnitReader.normalize(e.getValue())), null))
                        .toList();
                case PPTX -> PptxSlideReader.read(bytes).stream()
                        .map(s -> new Attr("s" + s.ordinal(), s.sldId(),
                                ThreeWayAnalyzer.sha256Hex(DocxUnitReader.normalize(s.text())), null))
                        .toList();
                case WHOLE -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 显式打开改名跟随。实测（JGit 6.9）{@link FollowFilter} 不看 {@code diff.renames}
     * 这个开关照样跟得动改名——把它设成 false 测试仍然全绿；把 {@code FollowFilter} 换成
     * {@code PathFilter} 才会让 {@code followsRename} 转红。这里仍然显式写上是为了不把
     * 「改名跟随到底靠什么」交给用户那份仓库配置或某一版 JGit 的默认值去决定。
     */
    private static DiffConfig renameAwareDiffConfig(Repository repo) {
        Config config = new Config(repo.getConfig());
        config.setString("diff", null, "renames", "true");
        return config.get(DiffConfig.KEY);
    }

    // ==================================================================== 缓存

    private Path cacheDir(long projectId, String relPath) {
        return repoService.gitDir(projectId)
                .resolve("awd-cache").resolve("provenance")
                .resolve(ThreeWayAnalyzer.sha256Hex(relPath).substring(0, 16));
    }

    private CacheFile readCache(long projectId, String relPath, String sha) {
        Path file = cacheDir(projectId, relPath).resolve(sha + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            CacheFile cached = JSON.readValue(Files.readAllBytes(file), CacheFile.class);
            if (cached == null || cached.v() != CACHE_VERSION || cached.units() == null) {
                return null;
            }
            return cached;
        } catch (Exception e) {
            log.debug("溯源缓存读不了，重算: {}", file, e);
            return null;
        }
    }

    private void writeCache(long projectId, String relPath, String sha, CacheFile payload) {
        Path dir = cacheDir(projectId, relPath);
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(sha + ".json"), JSON.writeValueAsBytes(payload));
        } catch (Exception e) {
            // 缓存写不了只是下次重算，不能让它吃掉这一次的结果
            log.debug("溯源缓存写不了: project={} path={} sha={}", projectId, relPath, sha, e);
        }
    }

    // ==================================================================== 出参

    /** 把逐单元的 sha 翻成律师读得懂的一行：谁、哪一版、什么时候、那一版叫什么。 */
    private List<ProvenanceUnit> enrich(long projectId, long userId, List<Attr> attrs) {
        Set<String> shas = new LinkedHashSet<>();
        for (Attr a : attrs) {
            if (a.sha() != null) {
                shas.add(a.sha());
            }
        }
        Map<String, VersionEntry> entries = repoService.entriesByShas(projectId, shas);
        Map<String, String> folded = foldAutosaves(projectId, entries);
        Map<String, String> remoteNames = remoteDisplayNames(projectId);
        String earlier = LangText.of("更早的版本", "Earlier versions");

        List<ProvenanceUnit> out = new ArrayList<>(attrs.size());
        for (Attr a : attrs) {
            VersionEntry e = a.sha() == null ? null : entries.get(folded.getOrDefault(a.sha(), a.sha()));
            if (e == null) {
                out.add(new ProvenanceUnit(a.key(), a.hash(), null, null, null, false, null, earlier, null));
                continue;
            }
            out.add(toUnit(a.key(), a.hash(), e, remoteNames, projectId, userId));
        }
        return out;
    }

    /** 一笔版本翻成律师读得懂的一条：谁、哪一版、什么时候、那一版叫什么。逐段与文件级共用。 */
    private ProvenanceUnit toUnit(String key, String hash, VersionEntry e,
                                  Map<String, String> remoteNames, long projectId, long userId) {
        String title = e.note() != null && !e.note().isBlank() ? e.note() : e.message();
        return new ProvenanceUnit(
                key,
                hash,
                e.sha(),
                e.sha().length() < 7 ? e.sha() : e.sha().substring(0, 7),
                VersionAuthorResolver.preferredAuthorName(e, remoteNames),
                isSelf(e, projectId, userId),
                e.when(),
                title,
                HistoryTypeClassifier.classify(e.message(), e.kind()));
    }

    /**
     * 文件级版本身份（dev-board#672 复测，2026-09-16 用户拍板）：编辑器顶上那条小条
     * 不再逐段跟光标，改说「这份文件最近一次有名字的版本」+「本机未保存的改动」。
     * 逐段溯源本身一个字没动——{@code units} 照旧。
     *
     * <p>这两样**算在昂贵的逐段回溯之前**：回溯超预算时整条请求回 {@code computing:true}，
     * 小条不该跟着空着几十秒（它本来只要一次早停 walk 加一次单路径 status）。
     *
     * <p>两样都是尽力而为：算不出来就少说一句，绝不把编辑器顶栏变成一个错误提示。
     */
    private void putFileVersion(Map<String, Object> out, long projectId, long userId,
                                String relPath, String ref) {
        try {
            out.put("dirty", repoService.isPathDirty(projectId, relPath));
        } catch (Exception e) {
            log.debug("读单文件脏位失败: project={} path={}", projectId, relPath, e);
        }
        try {
            VersionEntry e = repoService.latestNamedVersionForPath(projectId, ref, relPath, maxHistory);
            if (e != null) {
                out.put("fileVersion", toUnit(null, null, e, remoteDisplayNames(projectId), projectId, userId));
            }
        } catch (Exception ex) {
            log.warn("读文件级版本身份失败: project={} path={}", projectId, relPath, ex);
        }
    }

    /**
     * 自动存档折进「它所属的那一版」（dev-board 真机反馈 A2）。
     *
     * <p>归属算的是**精确**那一笔提交，而工作段内的每一次防抖存档都是一笔
     * {@code kind=auto} 的无名提交——律师在时间线上根本看不到它们（{@code VersionTimeline}
     * 的 {@code grouped} 把 auto 折进上一条命名节点里），却会在编辑器顶上那条溯源里
     * 读到「修改了《采购合同》」这种自动生成的句子，而不是他自己给这段工作起的名字。
     * 这里按**和时间线完全相同的分组口径**做一次折叠：沿 HEAD 的历史从新往旧走，
     * {@code kind=session} 的那一条开一组，其后（更旧）的每一条 auto 都归到它头上。
     *
     * <p>还没收尾的那段工作照旧不折（它的 auto 比任何命名版本都新，找不到归宿）——
     * 这时界面上说「自动存档」是对的：那一段确实还没有名字。
     *
     * @return {@code {auto 的 sha -> 它所属的那一版的 sha}}；没有 auto 要折时是空表
     */
    private Map<String, String> foldAutosaves(long projectId, Map<String, VersionEntry> entries) {
        boolean anyAuto = entries.values().stream().anyMatch(ProvenanceService::isAutosave);
        if (!anyAuto) {
            return Map.of();
        }
        List<VersionEntry> history;
        try {
            history = repoService.log(projectId, "HEAD", Math.max(1, maxHistory));
        } catch (Exception e) {
            // 折叠是出参侧的锦上添花，读不出历史就照原样显示自动存档
            log.warn("折叠自动存档时读历史失败: project={}", projectId, e);
            return Map.of();
        }
        Map<String, String> fold = foldMap(history);
        // 折过去的那些版本自己也要有 entry 才翻得出标题/署名
        Set<String> extra = new LinkedHashSet<>(fold.values());
        extra.removeAll(entries.keySet());
        if (!extra.isEmpty()) {
            entries.putAll(repoService.entriesByShas(projectId, extra));
        }
        return fold;
    }

    /**
     * {@code auto 的 sha -> 它所属的那一版}。{@code history} 必须是新在前
     * （{@link ProjectRepoService#log} 的既有顺序），口径与前端 {@code VersionTimeline.grouped}
     * 逐字相同：只有 {@code session} 开组，其余归到最近一条 session 上。包内可见供直接测。
     */
    static Map<String, String> foldMap(List<VersionEntry> history) {
        Map<String, String> fold = new LinkedHashMap<>();
        String named = null;
        for (VersionEntry e : history) {
            if (e == null || e.sha() == null) {
                continue;
            }
            if (!isAutosave(e)) {
                named = e.sha();
            } else if (named != null) {
                fold.put(e.sha(), named);
            }
        }
        return fold;
    }

    private static boolean isAutosave(VersionEntry e) {
        return e != null && "auto".equals(e.kind());
    }

    /** 读列表一律不许联网（allowFetch=false），与提交历史出参同口径。 */
    private Map<String, String> remoteDisplayNames(long projectId) {
        try {
            if (cloudSyncService == null) {
                return Map.of();
            }
            Map<String, String> names = cloudSyncService.remoteDisplayNames(projectId, false);
            return names == null ? Map.of() : names;
        } catch (Exception e) {
            log.warn("读案件库展示名失败，溯源按 git 署名显示: project={}", projectId, e);
            return Map.of();
        }
    }

    private boolean isSelf(VersionEntry e, long projectId, long userId) {
        try {
            return authorResolver != null && authorResolver.isSelf(e, projectId, userId);
        } catch (Exception ex) {
            return false;
        }
    }
}
