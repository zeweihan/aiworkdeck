package com.checkba.version.memory;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.model.entity.MemoryRemote;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.User;
import com.checkba.repository.MemoryEntryRepository;
import com.checkba.repository.MemoryRemoteRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.version.FileChange;
import com.checkba.version.VersionException;
import com.checkba.version.memory.MemoryFileCodec.MemoryFileData;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆 Git 同步编排（spec Phase A）。一个领域（MemoryRealm）一个独立 Git 仓库——
 * **红线：记忆绝不进项目文档仓库主线**（否则退回版本会连记忆一起退回、AI 落记忆
 * 弄脏律师时间线、MERGING 裁决窗口冻结记忆同步）。
 *
 * 同步循环（全部在 per-repoKey 锁内）：
 *   重置工作树到 HEAD → fetch → 整合远端（快进或逐文件 LWW 全自动合并，从不停留在
 *   MERGING）→ 按差异回灌 DB → 导出 DB 到文件（含 uid 回填与本机删除的墓碑化）→
 *   commit → push（被拒 → 再整合一轮 → 重推一次；仍不成 → pendingUpload）。
 *
 * 网络失败纪律照抄 CloudSyncService：云端不可达只置 pendingUpload，绝不抛给调用方、
 * 绝不阻断记忆管线主流程。
 *
 * 防乒乓（关键不变式）：回灌落库会让 JPA 把 updatedAt 推到当前时刻，如果导出/回灌
 * 只按时间戳判断，两台机器会永远互相「更新」对方。因此文件与行的比较一律先比语义
 * 字段（MemoryFileData.semanticallyEquals），语义相同就什么都不做，时间戳只在语义
 * 真的分叉时做 LWW 裁决。
 */
@Service
public class MemorySyncService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MemorySyncService.class);

    static final String AUTHOR_NAME = "记忆同步";
    static final String AUTHOR_EMAIL = "memory-sync@aiworkdeck.local";

    /**
     * 仓库内合法的记忆文件路径：{scope}/{uid}.md。既是格式约定也是路径安全护栏——
     * 远端内容不可信（地雷 #33），合并/回灌只处理匹配这个形状的路径，其余跳过告警。
     */
    private static final Pattern FILE_PATH =
            Pattern.compile("^(user|global|project|conversation|file)/([0-9a-fA-F-]{8,64})\\.md$");

    private final MemoryRepoService repoService;
    private final MemoryEntryRepository entryRepository;
    private final MemoryRemoteRepository remoteRepository;
    private final MemoryManager memoryManager;
    private final ProjectFileRepository projectFileRepository;
    private final UserRepository userRepository;
    private final TaskScheduler taskScheduler;

    /** 防抖静默期（写侧：记忆管线一轮可能落多条，攒一波再导出）。测试里调短取得确定性。 */
    private long debounceMillis = 30_000L;

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public MemorySyncService(MemoryRepoService repoService,
                             MemoryEntryRepository entryRepository,
                             MemoryRemoteRepository remoteRepository,
                             @Lazy MemoryManager memoryManager,
                             ProjectFileRepository projectFileRepository,
                             UserRepository userRepository,
                             TaskScheduler taskScheduler) {
        this.repoService = repoService;
        this.entryRepository = entryRepository;
        this.remoteRepository = remoteRepository;
        this.memoryManager = memoryManager;
        this.projectFileRepository = projectFileRepository;
        this.userRepository = userRepository;
        this.taskScheduler = taskScheduler;
    }

    void setDebounceMillis(long millis) {
        this.debounceMillis = millis;
    }

    ReentrantLock repoLock(String repoKey) {
        return locks.computeIfAbsent(repoKey, k -> new ReentrantLock());
    }

    /** 与本服务一切改仓库路径同一把锁（GitHttpController 的 receive-pack 用）。可重入。 */
    public void runLocked(String repoKey, Runnable body) {
        ReentrantLock lock = repoLock(repoKey);
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    // ==================== 写侧触发（防抖） ====================

    /**
     * 记忆管线一轮落库后调用（MemoryPipelineService 挂钩）。失败绝不外抛——
     * 记忆同步是保险，不能反过来阻断记忆管线。只对「已配置远端或本机已有记忆仓库」
     * 的领域生效：没配过的用户不产生任何新仓库、零开销。
     */
    public void onMemoriesTouched(Long projectId, Long userId) {
        try {
            if (projectId != null) scheduleIfEligible(MemoryRealm.project(projectId));
            if (userId != null) scheduleIfEligible(MemoryRealm.user(userId));
        } catch (Exception e) {
            log.warn("记忆同步触发失败（已吞）: project={}, user={}", projectId, userId, e);
        }
    }

    private void scheduleIfEligible(MemoryRealm realm) {
        String repoKey = realm.repoKey();
        if (remoteRepository.findByRepoKey(repoKey).isEmpty() && !repoService.isInitialized(repoKey)) {
            return;
        }
        ScheduledFuture<?> prev = pending.put(repoKey, taskScheduler.schedule(() -> {
            pending.remove(repoKey);
            try {
                syncNow(realm);
            } catch (Exception e) {
                log.warn("记忆同步失败（已吞）: {}", repoKey, e);
            }
        }, Instant.now().plusMillis(debounceMillis)));
        if (prev != null) prev.cancel(false);
    }

    // ==================== 读侧定时轮询 ====================

    /** 定时对所有已配置远端的记忆仓库做一轮同步（读侧 fetch + 回灌，顺带把 pending 的推掉）。 */
    @Scheduled(fixedDelayString = "${memory.sync.poll-ms:300000}",
               initialDelayString = "${memory.sync.initial-delay-ms:120000}")
    public void pollRemotes() {
        List<MemoryRemote> remotes;
        try {
            remotes = remoteRepository.findAll();
        } catch (Exception e) {
            log.warn("记忆同步轮询读取配置失败", e);
            return;
        }
        for (MemoryRemote r : remotes) {
            MemoryRealm realm = MemoryRealm.parse(r.getRepoKey());
            if (realm == null) continue;
            try {
                syncNow(realm);
            } catch (Exception e) {
                log.warn("记忆同步轮询失败（已吞）: {}", r.getRepoKey(), e);
            }
        }
    }

    // ==================== 同步主流程 ====================

    /**
     * 立即同步一个领域。返回状态快照（configured/offline/pendingUpload/committed 等）。
     * 网络失败不抛（转 pendingUpload）；本地 Git/DB 异常抛 VersionException 由调用方处置
     * （防抖/轮询路径已各自吞掉）。
     */
    public Map<String, Object> syncNow(MemoryRealm realm) {
        String repoKey = realm.repoKey();
        ReentrantLock lock = repoLock(repoKey);
        lock.lock();
        try {
            MemoryRemote cfg = remoteRepository.findByRepoKey(repoKey).orElse(null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("repoKey", repoKey);
            result.put("configured", cfg != null);
            if (cfg == null && !repoService.isInitialized(repoKey)) {
                result.put("synced", false);
                return result;
            }
            repoService.init(repoKey);
            resetWorkTreeToHead(repoKey);

            boolean offline = false;
            String fetchedSha = null;
            if (cfg != null) {
                try {
                    fetchedSha = repoService.fetchFromRemote(
                            repoKey, cfg.getUrl(), cfg.getUsername(), cfg.getSecret());
                } catch (VersionException e) {
                    offline = true;
                    log.warn("记忆仓库拉取失败，按离线处理: {}", repoKey, e);
                }
            }
            if (!offline && fetchedSha != null) {
                integrateRemote(realm, fetchedSha);
            }
            String committed = exportRealm(realm);

            boolean pushed = false;
            if (cfg != null && !offline) {
                pushed = pushWithReintegrate(realm, cfg);
                cfg.setPendingUpload(!pushed);
            } else if (cfg != null) {
                // 离线：这轮有新提交或之前就欠着推，都记待推
                if (committed != null) cfg.setPendingUpload(true);
            }
            if (cfg != null) {
                cfg.setLastSyncAt(LocalDateTime.now());
                remoteRepository.save(cfg);
                result.put("pendingUpload", Boolean.TRUE.equals(cfg.getPendingUpload()));
            }
            result.put("synced", true);
            result.put("offline", offline);
            result.put("committed", committed != null);
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 服务端 post-receive：一次 push 让 master 从 oldSha 前进到 newSha 后，把变化的
     * 文件回灌进本机 DB（团队服务器自己也是一个有 DB 的后端，服务器侧的检索要能命中
     * 同步上来的记忆）。尽力而为：失败只 log，绝不让 push 响应受影响。
     */
    public void ingestPushedMemory(String repoKey, String oldSha, String newSha) {
        MemoryRealm realm = MemoryRealm.parse(repoKey);
        if (realm == null) return;
        ReentrantLock lock = repoLock(repoKey);
        lock.lock();
        try {
            String from = (oldSha == null || oldSha.matches("^0+$")) ? null : oldSha;
            importChanged(realm, from, newSha);
        } catch (Exception e) {
            log.warn("push 回灌记忆失败（已吞，下一轮同步自愈）: {}", repoKey, e);
        } finally {
            lock.unlock();
        }
    }

    /** 工作树是可弃的物化区：每轮同步开始重置到 HEAD，杜绝「陈旧工作树把已推进的历史提交回去」。 */
    private void resetWorkTreeToHead(String repoKey) {
        String head = repoService.resolveRef(repoKey, "HEAD");
        if (head != null) {
            repoService.hardResetTo(repoKey, head);
        }
    }

    /**
     * 把 origin/master 整合进本地 master：快进优先，分叉走逐文件 LWW 全自动合并
     * （单边改取该边；双边改同 uid 比 updatedAt，墓碑无条件胜；平手按字节序决定性
     * 裁决——两台机器各自合并会选同一边，不会打乒乓）。整合后按差异回灌 DB。
     * 从不停留在 MERGING：合并在这一个方法内原子完成。
     */
    private void integrateRemote(MemoryRealm realm, String remoteSha) {
        String repoKey = realm.repoKey();
        String tipBefore = repoService.resolveRef(repoKey, MemoryRepoService.MAIN_BRANCH);
        if (remoteSha.equals(tipBefore)) return;
        if (tipBefore != null && repoService.isAncestor(repoKey, remoteSha, MemoryRepoService.MAIN_BRANCH)) {
            return; // 本地领先，无需整合
        }
        if (tipBefore == null || repoService.isAncestor(repoKey, MemoryRepoService.MAIN_BRANCH, remoteSha)) {
            repoService.hardResetTo(repoKey, remoteSha);
        } else {
            lwwMerge(repoKey, tipBefore, remoteSha);
        }
        importChanged(realm, tipBefore, "HEAD");
    }

    private void lwwMerge(String repoKey, String localSha, String remoteSha) {
        String base = repoService.mergeBase(repoKey, localSha, remoteSha);
        List<FileChange> remoteChanges = repoService.diffNameStatus(repoKey, base, remoteSha);
        Set<String> localChanged = new HashSet<>();
        for (FileChange c : repoService.diffNameStatus(repoKey, base, localSha)) {
            localChanged.add(c.path());
        }
        Path work = repoService.workTree(repoKey);
        for (FileChange rc : remoteChanges) {
            String path = rc.path();
            Matcher m = FILE_PATH.matcher(path);
            if (!m.matches()) {
                log.warn("记忆合并跳过不合规路径: {} {}", repoKey, path);
                continue;
            }
            String uid = m.group(2);
            byte[] remoteBytes = rc.type() == FileChange.Type.DELETE
                    ? null : repoService.readBlobAtCommit(repoKey, remoteSha, path);
            byte[] winner;
            if (!localChanged.contains(path)) {
                winner = remoteBytes; // 单边改取该边（null = 远端删除，违规形态，照做会丢——保留判断在下面）
            } else {
                byte[] localBytes = repoService.readBlobAtCommit(repoKey, localSha, path);
                winner = resolveBothChanged(uid, localBytes, remoteBytes);
            }
            try {
                Path target = work.resolve(path).normalize();
                if (!target.startsWith(work)) continue; // 双保险：FILE_PATH 已排除，仍不信任
                if (winner == null) {
                    // 只有「远端删了文件」会走到这（墓碑纪律下不该发生）：不跟删，保留本地
                    log.warn("远端直接删除了记忆文件（应使用墓碑），保留本地: {} {}", repoKey, path);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.write(target, winner);
            } catch (Exception e) {
                throw new VersionException("写入记忆合并结果失败: " + repoKey + " " + path, e);
            }
        }
        repoService.commitMergeWithSecondParent(repoKey, remoteSha, "记忆合并",
                AUTHOR_NAME, AUTHOR_EMAIL);
    }

    /** 双边改同一 uid 的裁决。解析失败的一侧输给能解析的一侧；都失败保本地。 */
    private byte[] resolveBothChanged(String uid, byte[] localBytes, byte[] remoteBytes) {
        if (remoteBytes == null) return localBytes;
        if (localBytes == null) return remoteBytes;
        MemoryFileData local = MemoryFileCodec.decode(uid, localBytes);
        MemoryFileData remote = MemoryFileCodec.decode(uid, remoteBytes);
        if (local == null) return remote == null ? localBytes : remoteBytes;
        if (remote == null) return localBytes;
        if (local.tombstone() != remote.tombstone()) {
            return local.tombstone() ? localBytes : remoteBytes; // 墓碑胜（防陈旧端复活）
        }
        long lt = local.updatedAtMs() == null ? 0L : local.updatedAtMs();
        long rt = remote.updatedAtMs() == null ? 0L : remote.updatedAtMs();
        if (lt != rt) return lt > rt ? localBytes : remoteBytes;
        // 决定性平手裁决：按字节序，两台机器各自合并会得到同一个赢家
        return compareBytes(localBytes, remoteBytes) >= 0 ? localBytes : remoteBytes;
    }

    private int compareBytes(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int c = Byte.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return Integer.compare(a.length, b.length);
    }

    // ==================== 回灌（Git → DB） ====================

    /**
     * 把 fromRef 到 toRef 之间变化的记忆文件回灌进 DB。fromRef 为 null 表示全量
     * （首推/首次接入）。只处理变化的路径：本机已删而远端没动的行不会被机械复活——
     * 它们留给导出阶段墓碑化。
     */
    private void importChanged(MemoryRealm realm, String fromRef, String toRef) {
        String repoKey = realm.repoKey();
        List<FileChange> changes = repoService.diffNameStatus(repoKey, fromRef, toRef);
        if (changes.isEmpty()) return;
        Map<String, MemoryEntry> byUid = realmRowsByUid(realm);
        ImportContext ctx = new ImportContext(realm);
        for (FileChange c : changes) {
            Matcher m = FILE_PATH.matcher(c.path());
            if (!m.matches()) continue;
            String scope = m.group(1);
            String uid = m.group(2);
            if (!realm.scopes().contains(scope)) continue;
            if (c.type() == FileChange.Type.DELETE) {
                log.warn("远端直接删除了记忆文件（应使用墓碑），忽略: {} {}", repoKey, c.path());
                continue;
            }
            try {
                byte[] bytes = repoService.readBlobAtCommit(repoKey, toRef, c.path());
                if (bytes == null) continue;
                MemoryFileData data = MemoryFileCodec.decode(uid, bytes);
                if (data == null) {
                    log.warn("记忆文件格式不合法，跳过: {} {}", repoKey, c.path());
                    continue;
                }
                applyFileToDatabase(realm, data, byUid, ctx);
            } catch (Exception e) {
                log.warn("回灌单条记忆失败，跳过: {} {}", repoKey, c.path(), e);
            }
        }
    }

    private void applyFileToDatabase(MemoryRealm realm, MemoryFileData data,
                                     Map<String, MemoryEntry> byUid, ImportContext ctx) {
        MemoryEntry row = byUid.get(data.uid());
        if (data.tombstone()) {
            if (row != null) {
                entryRepository.delete(row);
                byUid.remove(data.uid());
            }
            return;
        }
        if (row != null) {
            MemoryFileData current = toFileData(realm, row, ctx);
            if (data.semanticallyEquals(current)) return; // 防乒乓：语义相同不落库
            long fileTs = data.updatedAtMs() == null ? 0L : data.updatedAtMs();
            long rowTs = current.updatedAtMs() == null ? 0L : current.updatedAtMs();
            if (fileTs < rowTs) return; // 本地更晚（LWW 本地胜），导出阶段会把本地内容写回文件
            copyInto(row, data, realm, ctx);
            saveWithEmbedding(row);
        } else {
            MemoryEntry entry = new MemoryEntry();
            entry.setUid(data.uid());
            copyInto(entry, data, realm, ctx);
            entry.setCreatedAt(MemoryFileCodec.fromEpochMs(data.createdAtMs()));
            entry.setUpdatedAt(MemoryFileCodec.fromEpochMs(data.updatedAtMs()));
            saveWithEmbedding(entry);
            if (entry.getId() != null) byUid.put(data.uid(), entry);
        }
    }

    private void copyInto(MemoryEntry entry, MemoryFileData data, MemoryRealm realm, ImportContext ctx) {
        entry.setScope(data.scope());
        entry.setMemoryType(data.memoryType() == null ? "fact" : data.memoryType());
        entry.setMemoryKey(data.memoryKey());
        entry.setMemoryValue(data.memoryValue() == null ? "" : data.memoryValue());
        entry.setImportanceScore(data.importanceScore());
        entry.setIsProtected(data.isProtected());
        entry.setConversationId(data.conversationId());
        entry.setMetadata(data.metadata());
        if (realm.kind() == MemoryRealm.Kind.PROJECT) {
            entry.setProjectId(realm.ownerId());
            // author（用户名）→ 本机 userId：查得到就映射，查不到保留原值（update）或空（insert）。
            // 数字 userId 绝不进文件——本机 id 在另一台机器上毫无意义（地雷 #27）。
            Long resolved = ctx.resolveAuthor(data.author());
            if (resolved != null) entry.setUserId(resolved);
            entry.setSourceFileId(ctx.resolveSourceFile(data.sourceFileUid()));
        } else {
            entry.setUserId(realm.ownerId());
            entry.setProjectId(null);
        }
    }

    /** 落库 + 现有机制重建向量嵌入（MemoryManager.saveMemory 内部嵌入失败自会降级只落库）。 */
    private void saveWithEmbedding(MemoryEntry entry) {
        try {
            memoryManager.saveMemory(entry);
        } catch (Exception e) {
            log.warn("经 MemoryManager 落库失败，退化为仅保存: uid={}", entry.getUid(), e);
            entryRepository.save(entry);
        }
    }

    // ==================== 导出（DB → Git） ====================

    /**
     * 把领域内全部 DB 行导出为 {scope}/{uid}.md（缺 uid 先回填，幂等），并把
     * 「仓库里有、DB 里没有」的活文件墓碑化——那正是本机删掉的记忆，墓碑让删除
     * 传播到别的机器且防复活。返回提交 sha；无变化返回 null。
     */
    String exportRealm(MemoryRealm realm) {
        String repoKey = realm.repoKey();
        List<MemoryEntry> rows = ensureUids(realmRows(realm));
        Map<String, MemoryEntry> byUid = new HashMap<>();
        for (MemoryEntry r : rows) {
            if (r.getUid() != null) byUid.put(r.getUid(), r);
        }
        ImportContext ctx = new ImportContext(realm);
        Path work = repoService.workTree(repoKey);
        try {
            // 1) DB 行 → 文件
            for (MemoryEntry row : rows) {
                if (!MemoryFileCodec.isValidUid(row.getUid())) continue;
                String scope = row.getScope() == null ? "project" : row.getScope();
                if (!realm.scopes().contains(scope)) continue;
                String rel = scope + "/" + row.getUid() + ".md";
                Path target = work.resolve(rel).normalize();
                if (!target.startsWith(work)) continue;
                MemoryFileData desired = toFileData(realm, row, ctx);
                if (Files.exists(target)) {
                    MemoryFileData existing = MemoryFileCodec.decode(row.getUid(), Files.readAllBytes(target));
                    if (existing != null && existing.tombstone()) {
                        // 墓碑胜：文件已墓碑而 DB 仍有行（陈旧行）→ 删行、文件不动
                        entryRepository.delete(row);
                        byUid.remove(row.getUid());
                        continue;
                    }
                    if (existing != null && existing.semanticallyEquals(desired)) continue; // 防乒乓
                }
                Files.createDirectories(target.getParent());
                Files.write(target, MemoryFileCodec.encode(desired));
            }
            // 2) 仓库有、DB 没有 → 墓碑化（本机删除的传播）
            for (String path : repoService.listPaths(repoKey, "HEAD")) {
                Matcher m = FILE_PATH.matcher(path);
                if (!m.matches()) continue;
                if (!realm.scopes().contains(m.group(1))) continue;
                String uid = m.group(2);
                if (byUid.containsKey(uid)) continue;
                Path target = work.resolve(path).normalize();
                if (!target.startsWith(work) || !Files.exists(target)) continue;
                MemoryFileData existing = MemoryFileCodec.decode(uid, Files.readAllBytes(target));
                if (existing == null || existing.tombstone()) continue;
                MemoryFileData tomb = new MemoryFileData(uid, existing.scope(), existing.memoryType(),
                        existing.memoryKey(), existing.memoryValue(), existing.importanceScore(),
                        existing.isProtected(), existing.conversationId(), existing.author(),
                        existing.sourceFileUid(), existing.metadata(), existing.createdAtMs(),
                        System.currentTimeMillis(), true);
                Files.write(target, MemoryFileCodec.encode(tomb));
            }
        } catch (VersionException e) {
            throw e;
        } catch (Exception e) {
            throw new VersionException("导出记忆失败: " + repoKey, e);
        }
        return repoService.commitAll(repoKey, "记忆更新", AUTHOR_NAME, AUTHOR_EMAIL);
    }

    /** 缺 uid 的存量行就地回填 UUID（仿清单 v2 回填），幂等：已有 uid 的行分毫不动。 */
    List<MemoryEntry> ensureUids(List<MemoryEntry> rows) {
        List<MemoryEntry> out = new ArrayList<>(rows.size());
        for (MemoryEntry r : rows) {
            if (r.getUid() == null || r.getUid().isBlank()) {
                r.setUid(UUID.randomUUID().toString());
                out.add(entryRepository.save(r));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    private List<MemoryEntry> realmRows(MemoryRealm realm) {
        return realm.kind() == MemoryRealm.Kind.PROJECT
                ? entryRepository.findByProjectIdAndScopeIn(realm.ownerId(), realm.scopes())
                : entryRepository.findByUserIdAndScopeIn(realm.ownerId(), realm.scopes());
    }

    private Map<String, MemoryEntry> realmRowsByUid(MemoryRealm realm) {
        Map<String, MemoryEntry> out = new HashMap<>();
        for (MemoryEntry r : realmRows(realm)) {
            if (r.getUid() != null && !r.getUid().isBlank()) out.put(r.getUid(), r);
        }
        return out;
    }

    /** DB 行 → 文件形态（导出与回灌比较共用，保证两个方向的语义比较对得上）。 */
    private MemoryFileData toFileData(MemoryRealm realm, MemoryEntry row, ImportContext ctx) {
        String scope = row.getScope() == null ? "project" : row.getScope();
        return new MemoryFileData(
                row.getUid(),
                scope,
                row.getMemoryType(),
                row.getMemoryKey(),
                row.getMemoryValue() == null ? "" : row.getMemoryValue(),
                row.getImportanceScore(),
                row.getIsProtected(),
                row.getConversationId(),
                realm.kind() == MemoryRealm.Kind.PROJECT ? ctx.authorNameOf(row.getUserId()) : null,
                realm.kind() == MemoryRealm.Kind.PROJECT ? ctx.sourceFileUidOf(row.getSourceFileId()) : null,
                row.getMetadata(),
                MemoryFileCodec.toEpochMs(row.getCreatedAt()),
                MemoryFileCodec.toEpochMs(row.getUpdatedAt()),
                false);
    }

    private boolean pushWithReintegrate(MemoryRealm realm, MemoryRemote cfg) {
        String repoKey = realm.repoKey();
        if (repoService.resolveRef(repoKey, MemoryRepoService.MAIN_BRANCH) == null) {
            return true; // 空领域从未有提交：没有可推的内容，不算欠推
        }
        try {
            MemoryRepoService.PushOutcome out = repoService.pushToRemote(
                    repoKey, cfg.getUrl(), cfg.getUsername(), cfg.getSecret());
            if (out.pushed()) return true;
            // 被拒 = 远端被别的机器推进：整合一轮再推一次
            String fetched = repoService.fetchFromRemote(
                    repoKey, cfg.getUrl(), cfg.getUsername(), cfg.getSecret());
            if (fetched != null) {
                integrateRemote(realm, fetched);
                exportRealm(realm);
            }
            out = repoService.pushToRemote(repoKey, cfg.getUrl(), cfg.getUsername(), cfg.getSecret());
            if (!out.rejected()) return out.pushed();
            log.warn("记忆仓库重推仍被拒，转入待推: {} {}", repoKey, out.message());
            return false;
        } catch (VersionException e) {
            log.warn("记忆仓库推送失败，转入待推: {}", repoKey, e);
            return false;
        }
    }

    /**
     * 一次回灌/导出周期内的懒加载映射缓存：author（用户名）与本机 userId 互换、
     * sourceFileUid 与本机 sourceFileId 互换。跨机器只有用户名/uid 可信，数字 id 恒为
     * 本机派生值。
     */
    private class ImportContext {
        private final MemoryRealm realm;
        private Map<Long, String> usernameById;
        private Map<String, Long> idByUsername;
        private Map<Long, String> fileUidById;
        private Map<String, Long> fileIdByUid;

        ImportContext(MemoryRealm realm) {
            this.realm = realm;
        }

        String authorNameOf(Long userId) {
            if (userId == null) return null;
            if (usernameById == null) usernameById = new HashMap<>();
            return usernameById.computeIfAbsent(userId, id -> {
                try {
                    return userRepository.findById(id).map(User::getUsername).orElse(null);
                } catch (Exception e) {
                    return null;
                }
            });
        }

        Long resolveAuthor(String username) {
            if (username == null || username.isBlank()) return null;
            if (idByUsername == null) idByUsername = new HashMap<>();
            return idByUsername.computeIfAbsent(username, name -> {
                try {
                    return userRepository.findByUsername(name).map(User::getId).orElse(null);
                } catch (Exception e) {
                    return null;
                }
            });
        }

        String sourceFileUidOf(Long sourceFileId) {
            if (sourceFileId == null || realm.kind() != MemoryRealm.Kind.PROJECT) return null;
            loadProjectFiles();
            return fileUidById.get(sourceFileId);
        }

        Long resolveSourceFile(String sourceFileUid) {
            if (sourceFileUid == null || realm.kind() != MemoryRealm.Kind.PROJECT) return null;
            loadProjectFiles();
            return fileIdByUid.get(sourceFileUid);
        }

        private void loadProjectFiles() {
            if (fileUidById != null) return;
            fileUidById = new HashMap<>();
            fileIdByUid = new HashMap<>();
            try {
                for (ProjectFile f : projectFileRepository.findByProjectId(realm.ownerId())) {
                    if (f.getId() == null || f.getUid() == null || f.getUid().isBlank()) continue;
                    fileUidById.put(f.getId(), f.getUid());
                    fileIdByUid.put(f.getUid(), f.getId());
                }
            } catch (Exception e) {
                log.warn("加载项目文件 uid 映射失败: project={}", realm.ownerId(), e);
            }
        }
    }
}
