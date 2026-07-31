package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一次工作的生命周期（spec 5.2）。
 *
 * 律师第一次动了任何东西 → 隐式开始一段工作（建分支并切过去）；
 * 期间的自动存档攒在这个分支上；结束时整段合并回主线。
 *
 * 本服务的所有公开方法失败时抛 VersionException，调用方必须捕获后降级——
 * 版本记录是保险，不是主流程，绝不允许阻断编辑或保存。
 */
@Service
public class WorkSessionService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WorkSessionService.class);

    private static final DateTimeFormatter TITLE_FMT =
            DateTimeFormatter.ofPattern("M 月 d 日");

    /** AI 轮次自动存档的固定署名，让律师在时间线上分辨哪些改动是 AI 做的。 */
    private static final String AI_AUTHOR_NAME = "AI Workdeck";
    private static final String AI_AUTHOR_EMAIL = "ai@aiworkdeck.local";

    private final ProjectRepoService repoService;
    private final ProjectTreeManifestService manifestService;
    private final WorkSessionRepository sessionRepository;
    private final TaskScheduler taskScheduler;
    private final ProjectFileRepository fileRepository;

    /** 防抖静默期。测试里调短或调长以取得确定性。 */
    private long debounceMillis = 2 * 60 * 1000L;

    /** 空闲多久没有变更信号就自动结束工作段（spec 5.2）。测试里调短取得确定性。 */
    private long idleEndMillis = 30 * 60 * 1000L;

    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> idleTimers = new ConcurrentHashMap<>();
    private final Map<Long, PendingActor> actors = new ConcurrentHashMap<>();

    /**
     * 按项目维度的可重入互斥，包住所有会改仓库状态的路径（切分支/提交/合并/删分支）。
     * 必须可重入：endSession/revertTo 内部都会再调 commitNow，同一线程二次进入
     * 同一把锁不能死锁。ReentrantLock 而非 synchronized(projectId) ——
     * 装箱的 Long 相同数值不保证是同一对象，拿它当锁语义不可靠。
     */
    private final Map<Long, ReentrantLock> repoLocks = new ConcurrentHashMap<>();

    /**
     * push 落库延后的项目 → 最早的基线 sha（Task 6）。内存态，服务端重启即丢失——
     * 下一次 push 的 PostReceiveHook 或工作段/裁决收尾会自愈（补做一次全量物化）。
     */
    private final Map<Long, String> pendingIngestBase = new ConcurrentHashMap<>();

    private record PendingActor(Long userId, String userName) {}

    private ReentrantLock repoLock(long projectId) {
        return repoLocks.computeIfAbsent(projectId, id -> new ReentrantLock());
    }

    public WorkSessionService(ProjectRepoService repoService,
                              ProjectTreeManifestService manifestService,
                              WorkSessionRepository sessionRepository,
                              TaskScheduler taskScheduler,
                              ProjectFileRepository fileRepository) {
        this.repoService = repoService;
        this.manifestService = manifestService;
        this.sessionRepository = sessionRepository;
        this.taskScheduler = taskScheduler;
        this.fileRepository = fileRepository;
    }

    public void setDebounceMillis(long millis) { this.debounceMillis = millis; }

    public void setIdleEndMillis(long millis) { this.idleEndMillis = millis; }

    /**
     * 开启版本记录。ProjectRepoService 只认识 Git，不认识文件树清单，
     * 所以清单要在这里、调用 repoService.init 之前先写进工作区——
     * 这样「初始版本」那一笔提交天然带上 .awd/tree.json，退回到它时
     * syncManifestFromRef 才有清单可读，不会静默变成空操作。
     * 只此一笔提交，repoService.init 本身不认识清单也不需要改。
     */
    public void enableVersionRecording(long projectId, String authorName, String authorEmail) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            if (repoService.isInitialized(projectId)) return;
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
            repoService.init(projectId, authorName, authorEmail);
        } finally {
            lock.unlock();
        }
    }

    public Optional<WorkSession> activeSession(long projectId) {
        return sessionRepository.findFirstByProjectIdAndStatusAndSessionType(
                projectId, WorkSession.Status.ACTIVE, WorkSession.SessionType.WORK);
    }

    /**
     * 当前分支是稿（{@code draft/*}）时，返回该稿对应的 ACTIVE DRAFT 行；否则 empty。
     * 稿分支与工作段互不相干——不能用 {@link #activeSession} 查到。
     */
    public Optional<WorkSession> activeDraftOnBranch(long projectId) {
        if (!onDraftBranch(projectId)) return Optional.empty();
        String branch = repoService.currentBranch(projectId);
        return sessionRepository.findByProjectIdAndStatusAndSessionTypeOrderByStartedAtDesc(
                        projectId, WorkSession.Status.ACTIVE, WorkSession.SessionType.DRAFT)
                .stream()
                .filter(s -> branch.equals(s.getBranchName()))
                .findFirst();
    }

    /**
     * 稿分支守卫：当前分支是否是 {@code draft/*}。查询分支失败按主线处理（返回 false）——
     * 版本记录是保险，不是主流程，绝不能因为守卫本身查询失败而阻断改动信号/自动存档。
     */
    private boolean onDraftBranch(long projectId) {
        try {
            String branch = repoService.currentBranch(projectId);
            return branch != null && branch.startsWith("draft/");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 裁决现场守卫：采纳遇到冲突后，仓库停在待裁决状态，工作区里是带冲突标记的半成品，
     * 律师正对着三选一的弹窗。这期间任何自动存档都绝不能落地——MERGE_HEAD 还在磁盘上，
     * JGit 会把这一笔写成双亲提交、顺手清掉合并状态，等于一次后台自动保存悄悄"完成"了
     * 一场没人裁决过的采纳，还把冲突标记永久写进主线。
     *
     * 查询失败按「不在裁决中」处理，口径同 {@link #onDraftBranch}：版本记录是保险，
     * 不能因为守卫自己查询失败反而阻断保存。
     */
    private boolean awaitingAdoptResolution(long projectId) {
        try {
            return repoService.repositoryMerging(projectId);
        } catch (Exception e) {
            return false;
        }
    }

    /** 上次没正常结束的工作段（崩溃或强杀留下的）。 */
    public Optional<WorkSession> pendingRecovery(long projectId) {
        return activeSession(projectId);
    }

    /** 继续上次没结束的工作：切回该分支。 */
    public void resumeSession(long projectId) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            // 待裁决期间工作区是裁决现场，切分支会被 JGit 拒绝或毁掉现场，
            // 口径与 endSession/discardSession/revertTo/切线/开稿一致。
            requireNotMerging(projectId);
            WorkSession s = activeSession(projectId)
                    .orElseThrow(() -> VersionException.userFacing("当前没有未结束的工作"));
            repoService.checkoutBranch(projectId, s.getBranchName());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 收到一个变更信号：文件保存成功、文件树增删改移。
     * 没有进行中的工作段就隐式开一个，然后重排防抖提交。
     *
     * 稿分支（{@code draft/*}）例外：稿不受工作段管辖——不隐式开工作段（改动信号
     * 不能把稿悄悄拖进工作段体系），也不武装空闲定时器（稿绝不因为空着 30 分钟就被
     * 自动结束）。防抖自动存档仍然照排，稿上的改动也需要落盘存档。
     */
    public void onChangeSignal(long projectId, Long userId, String userName) {
        if (!repoService.isInitialized(projectId)) return;
        // 采纳裁决期间连信号都不接：既不开段，也不排自动存档（见 awaitingAdoptResolution）。
        if (awaitingAdoptResolution(projectId)) return;

        boolean draft = onDraftBranch(projectId);
        Long sessionId = null;
        if (!draft) {
            ReentrantLock lock = repoLock(projectId);
            lock.lock();
            try {
                sessionId = ensureSession(projectId, userId, userName).getId();
            } finally {
                lock.unlock();
            }
        }
        actors.put(projectId, new PendingActor(userId, userName));
        scheduleDebounceCommit(projectId);

        if (!draft) {
            // spec 5.2 三个结束触发之一：30 分钟无变更信号自动结束工作段。
            // 每次信号都重排——只要还有动静就不断推迟，真正空闲满时长才触发。
            armIdleTimer(projectId, sessionId);
        }
    }

    /** 重排（取消旧的、排一个新的）防抖自动存档。 */
    private void scheduleDebounceCommit(long projectId) {
        ScheduledFuture<?> prev = pending.remove(projectId);
        if (prev != null) prev.cancel(false);

        ScheduledFuture<?> next = taskScheduler.schedule(
                () -> {
                    pending.remove(projectId);
                    PendingActor a = actors.get(projectId);
                    if (a == null) return;
                    try {
                        commitNow(projectId, a.userId(), a.userName(), null);
                    } catch (Exception e) {
                        log.warn("自动存档失败: project={}", projectId, e);
                    }
                },
                Instant.now().plusMillis(debounceMillis));
        pending.put(projectId, next);
    }

    /**
     * 武装（或重新武装）空闲定时器，绑定当时的 sessionId——触发时如果活跃工作段
     * 已经不是这个 id，说明是陈旧定时器，直接放弃，不去结束一个它不认识的新工作段。
     */
    private void armIdleTimer(long projectId, Long sessionId) {
        ScheduledFuture<?> prevIdle = idleTimers.remove(projectId);
        if (prevIdle != null) prevIdle.cancel(false);
        ScheduledFuture<?> idleFuture = taskScheduler.schedule(
                () -> autoEndIfIdle(projectId, sessionId),
                Instant.now().plusMillis(idleEndMillis));
        idleTimers.put(projectId, idleFuture);
    }

    /**
     * 空闲定时器触发：仍在工作中的话自动结束，标题走默认命名。任何异常只记日志。
     *
     * 第二层防御（P3-T3 台账）：稿的双向切线会把用户从一段 ACTIVE 工作段的分支切到
     * 稿上，同时取消该工作段的空闲定时器（见 {@link #dockAndSwitchTo}）——但定时器
     * 取消与触发之间总有极小的竞态窗口，万一陈旧定时器还是跑到了这里，即便
     * sessionId 对得上，只要当前 checkout 已经不是这段工作自己的分支，就绝不能把
     * 它当"空闲"结束：那会把用户从稿上硬切回主线。查询分支本身失败也按不结束处理——
     * 版本记录是保险，不能因为自己查询失败反而制造事故。
     */
    private void autoEndIfIdle(long projectId, Long armedSessionId) {
        idleTimers.remove(projectId);
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            Optional<WorkSession> current = activeSession(projectId);
            if (current.isEmpty()) return;
            if (armedSessionId != null && !armedSessionId.equals(current.get().getId())) {
                // 陈旧定时器：它武装时对应的工作段已经不是现在这一段，不能错杀新段。
                return;
            }
            String currentBranch;
            try {
                currentBranch = repoService.currentBranch(projectId);
            } catch (Exception e) {
                log.warn("空闲结束前读取当前分支失败，放弃本次自动结束: project={}", projectId, e);
                return;
            }
            if (!current.get().getBranchName().equals(currentBranch)) {
                log.info("空闲定时器触发时用户已不在这段工作的分支上（很可能切去了稿上），跳过自动结束: project={}",
                        projectId);
                return;
            }
            PendingActor a = actors.get(projectId);
            Long userId = a != null ? a.userId() : null;
            String userName = a != null ? a.userName() : null;
            endSession(projectId, userId, userName, null);
            log.info("空闲超时自动结束一段工作: project={}", projectId);
        } catch (Exception e) {
            log.warn("空闲自动结束工作失败: project={}", projectId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 没有 ACTIVE 工作段则建分支并切过去。**任何隐式开段的入口都会走到这里**——
     * onChangeSignal / commitNow（进而 endSession/discardSession/revertTo）——
     * 所以「真正创建新工作段」这个分支必须顺手武装空闲定时器，否则由 commitNow
     * 间接触发的隐式开段（revertTo 就是这样）永远不会被空闲自动结束覆盖到，
     * 工作会一直挂着「工作中」。已存在 ACTIVE 段时直接复用，不重新武装——
     * 那种情况下定时器该不该动由调用方自己的重排逻辑负责（见 onChangeSignal）。
     */
    private WorkSession ensureSession(long projectId, Long userId, String userName) {
        Optional<WorkSession> existing = activeSession(projectId);
        if (existing.isPresent()) return existing.get();

        String branch = "work/" + System.currentTimeMillis();
        repoService.createBranch(projectId, branch, "HEAD");
        repoService.checkoutBranch(projectId, branch);

        WorkSession s = new WorkSession();
        s.setProjectId(projectId);
        s.setBranchName(branch);
        s.setStartedAt(LocalDateTime.now());
        s.setStatus(WorkSession.Status.ACTIVE);
        s.setUserId(userId);
        WorkSession saved = sessionRepository.save(s);
        log.info("开始一段工作: project={}, branch={}", projectId, branch);

        actors.put(projectId, new PendingActor(userId, userName));
        armIdleTimer(projectId, saved.getId());
        return saved;
    }

    /**
     * 供 /status 只读查询用：repoService.pendingChanges 内部会做两次 git add，
     * 与防抖定时器的 commitNow 并发时会抢同一把 .git/index.lock，谁输谁炸。
     * 包一层锁，职责只在这层——pendingChanges 本身不加锁（commitNow 内部会调用它，
     * 锁虽可重入但保持「谁改仓库状态谁负责加锁」的边界更清楚）。
     */
    public List<FileChange> pendingChangesLocked(long projectId) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            retryPendingIngest(projectId);
            return repoService.pendingChanges(projectId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 供 RepoMaintenanceJob 的每日 GC 用：gc 本身不改可达历史，但线程池不再是 1，
     * 可能跟自动存档/合并并发抢 .git 索引，同样要过按项目维度的这把锁。
     *
     * 待裁决窗口整段跳过（保守化）：那期间工作区/索引是律师还没做完选择的裁决现场，
     * 而 GC 会重打包并清理不可达对象。稿分支本身可达、裁决不会丢，但没有任何理由
     * 在这个窗口里动仓库——每日维护晚跑一天毫无代价。
     */
    public void gcLocked(long projectId) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            if (awaitingAdoptResolution(projectId)) {
                log.info("采纳裁决进行中，跳过这次仓库维护: project={}", projectId);
                return;
            }
            repoService.gc(projectId);
        } finally {
            lock.unlock();
        }
    }

    /** 让 Git 接收端整个跑在本项目的可重入锁内，与一切本地提交路径互斥。 */
    public void runLocked(long projectId, Runnable body) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * pre-receive 停靠：HEAD 在主线、工作区脏、又没有工作段兜着（「脏但无段」）时，
     * 先落一笔无主 auto 存档。master 因此前进，这次 push 的 old-sha 对不上会被
     * git 原生拒绝——客户端走「被拒 → 从云端更新 → 重推」的正常循环，
     * 网页端未存档的编辑分毫不丢。失败吞掉（版本记录不阻断主流程）。
     */
    public void dockDirtyMainlineForReceive(long projectId) {
        try {
            if (awaitingAdoptResolution(projectId)) return;
            if (onDraftBranch(projectId)) return;
            if (activeSession(projectId).isPresent()) return;
            if (!repoService.mainBranch().equals(repoService.currentBranch(projectId))) return;
            if (repoService.pendingChanges(projectId).isEmpty()) return;
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
            repoService.commitAll(projectId, "自动存档", "auto", null,
                    "AI Workdeck", "system@aiworkdeck.local");
            log.info("push 前停靠了主线脏区: project={}", projectId);
        } catch (Exception e) {
            log.warn("push 前停靠失败（不阻断接收）: project={}", projectId, e);
        }
    }

    /**
     * push 使 master 前进后的落库：路径级物化工作区 + 清单同步数据库。
     * 守卫不满足时记 pending 延后（保留最早的基线 sha），由
     * {@link #retryPendingIngest} 补做。调用方已持锁（runLocked 内），锁可重入。
     */
    public void ingestPushedMainline(long projectId, String oldSha, String newSha) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            if (awaitingAdoptResolution(projectId)
                    || activeSession(projectId).isPresent()
                    || onDraftBranch(projectId)
                    || !repoService.mainBranch().equals(repoService.currentBranch(projectId))) {
                pendingIngestBase.putIfAbsent(projectId, oldSha);
                log.info("push 落库延后: project={}, base={}", projectId, oldSha);
                return;
            }
            // 口径同 revertTo：diffNameStatus(目标, 现状) + restoreWorkTreeFrom(目标)。
            // 现状 = 工作区还端着的 oldSha 内容，目标 = 新 master。
            List<FileChange> changes = repoService.diffNameStatus(projectId, newSha, oldSha);
            restoreWorkTreeFrom(projectId, newSha, changes);
            syncManifestFromRef(projectId, "HEAD");
            pendingIngestBase.remove(projectId);
            log.info("push 落库完成: project={}, {} 个文件", projectId, changes.size());
        } catch (Exception e) {
            pendingIngestBase.putIfAbsent(projectId, oldSha);
            log.warn("push 落库失败，转入待同步: project={}", projectId, e);
        } finally {
            lock.unlock();
        }
    }

    /** 有延后的落库且守卫已清空时补做。挂在 pendingChangesLocked 与工作段收尾处。 */
    public void retryPendingIngest(long projectId) {
        String base = pendingIngestBase.get(projectId);
        if (base == null) return;
        String head = repoService.resolveRef(projectId, repoService.mainBranch());
        if (head == null) return;
        pendingIngestBase.remove(projectId);
        ingestPushedMainline(projectId, base, head);
    }

    /**
     * 立即落一笔自动存档。无变更时返回 null。
     * 稿分支（{@code draft/*}）上跳过 {@link #ensureSession}——稿不隐式开工作段，
     * 但清单写入与提交照旧，稿上的防抖自动存档必须正常工作。
     */
    public String commitNow(long projectId, Long userId, String userName, String message) {
        if (!repoService.isInitialized(projectId)) return null;
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            if (awaitingAdoptResolution(projectId)) {
                log.info("采纳裁决进行中，跳过这次自动存档: project={}", projectId);
                return null;
            }
            if (!onDraftBranch(projectId)) {
                ensureSession(projectId, userId, userName);
            }
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
            String msg = message != null ? message : describePendingChanges(projectId);
            return repoService.commitAll(projectId, msg, "auto", null, userName, email(userName));
        } finally {
            lock.unlock();
        }
    }

    /**
     * AI 轮次结束的落版：以 AI 身份（AI Workdeck &lt;ai@aiworkdeck.local&gt;）落一笔自动存档，
     * 让时间线能看出「哪些改动是 AI 做的」。无变更返回 null。
     * 稿分支（{@code draft/*}）上跳过 {@link #ensureSession}——稿不隐式开工作段，
     * 但清单写入与提交照旧，稿上的 AI 轮次也必须正常落版。
     * 已知局限（与文档检查点同源）：编辑器自动保存是异步的，轮次结束时未 flush 的
     * 改动不在本笔里，会随后续保存进入普通存档。
     */
    public String commitAiRound(long projectId, Long userId) {
        if (!repoService.isInitialized(projectId)) return null;
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            if (awaitingAdoptResolution(projectId)) {
                log.info("采纳裁决进行中，跳过这次 AI 轮次落版: project={}", projectId);
                return null;
            }
            if (!onDraftBranch(projectId)) {
                ensureSession(projectId, userId, AI_AUTHOR_NAME);
            }
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
            String msg = describePendingChanges(projectId);
            return repoService.commitAll(projectId, msg, "auto", null,
                    AI_AUTHOR_NAME, AI_AUTHOR_EMAIL);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 结束工作的结果。{@code notice} 非空表示「结束成功，但有一句话要告诉律师」——
     * 目前只有一种：整段工作没有任何改动、没生成版本。
     *
     * 这条路径刻意**不抛异常**：抛异常前它已经把状态改完了（删了分支、工作段标
     * DISCARDED），而前端的 catch 分支只负责 toast，不会关命名弹窗、也不会刷新
     * 状态条——律师看到的是「工作中」和一个卡住的弹窗，而后台其实已经结束了。
     * 「改了状态再抛异常」这种混合语义一律用返回值表达。
     */
    public record SessionEndResult(String sha, String notice) {}

    /**
     * 结束本次工作：收尾提交 → 切回主线 → 合并 → 关闭工作段。
     * 单人场景下主线在工作期间不会变，合并总是快进。
     */
    public SessionEndResult endSession(long projectId, Long userId, String userName, String title) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            requireNotMerging(projectId);
            WorkSession s = activeSession(projectId)
                    .orElseThrow(() -> VersionException.userFacing("当前没有进行中的工作"));

            cancelPending(projectId);
            commitNow(projectId, userId, userName, null);

            // 空工作段：整段工作分支 tip 跟 master tip 完全相同（一次提交都没有）。
            // 走下去合并会是 ALREADY_UP_TO_DATE，静默"成功"却不会在时间线上留下
            // 任何节点——律师起的名字凭空消失。这里必须在 checkout master 之前判断，
            // 因为判断依据是"工作分支自己的 tip"，checkout 之后当前分支就变了。
            String branchTip = repoService.resolveRef(projectId, s.getBranchName());
            String mainTip = repoService.resolveRef(projectId, repoService.mainBranch());
            if (branchTip != null && branchTip.equals(mainTip)) {
                repoService.checkoutBranch(projectId, repoService.mainBranch());
                repoService.deleteBranch(projectId, s.getBranchName(), true);
                s.setStatus(WorkSession.Status.DISCARDED);
                s.setEndedAt(LocalDateTime.now());
                sessionRepository.save(s);
                log.info("空工作段结束，未产生版本: project={}, branch={}", projectId, s.getBranchName());
                retryPendingIngest(projectId);
                return new SessionEndResult(null, "本次工作没有任何改动，未生成版本");
            }

            String finalTitle = (title == null || title.isBlank())
                    ? defaultTitle(s.getStartedAt()) : title.trim();

            repoService.checkoutBranch(projectId, repoService.mainBranch());
            MergeOutcome outcome = repoService.merge(
                    projectId, s.getBranchName(), finalTitle, userName, email(userName));

            if (!outcome.success()) {
                // 合并没成，把用户放回他的工作段，改动一个都不能丢
                repoService.checkoutBranch(projectId, s.getBranchName());
                throw VersionException.userFacing("本次工作还没能收尾，你的改动都还在");
            }

            s.setStatus(WorkSession.Status.MERGED);
            s.setEndedAt(LocalDateTime.now());
            s.setTitle(finalTitle);
            sessionRepository.save(s);
            log.info("结束一段工作: project={}, branch={}, title={}",
                    projectId, s.getBranchName(), finalTitle);
            retryPendingIngest(projectId);
            return new SessionEndResult(outcome.mergeSha(), null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 丢弃整段工作：删分支，工作区回到主线状态，数据库文件树跟着回去。
     *
     * 返回被这次丢弃改写过、且仍在数据库里的文件 id——机制与理由同 {@link #revertTo}：
     * 磁盘已经被 checkout 改写，而打开中的编辑器还端着被丢弃分支的内容，不重载的话
     * 下一次 autosave 会把刚被丢弃的工作原样写回去。
     */
    public List<Long> discardSession(long projectId, Long userId) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            requireNotMerging(projectId);
            WorkSession s = activeSession(projectId)
                    .orElseThrow(() -> VersionException.userFacing("当前没有进行中的工作"));

            cancelPending(projectId);

            // 残局修复：endSession 有一条路径会先 checkout 主线、再让合并异常直接
            // 逃逸出去——session 还是 ACTIVE，但 HEAD 已经在主线上。这种状态下如果
            // 直接往下走，commitNow 的预提交会落在当前 HEAD（也就是主线）上，
            // 一个署名「废弃的工作」的提交就永久污染了主线（历史永不重写，删不掉）。
            // 所以这里先确认当前分支就是这段工作自己的分支，不是的话切回去再继续。
            if (!s.getBranchName().equals(repoService.currentBranch(projectId))) {
                repoService.checkoutBranch(projectId, s.getBranchName());
            }

            // 先把一切（含未提交、含 untracked 新文件）收进这条即将被删除的分支——
            // 不这样做的话：(a) 已存档后又编辑过的文件在工作区里是脏的，checkout 主线
            // 要删它会被 JGit 拒绝（CheckoutConflictException）；(b) 最后一次自动存档
            // 之后新建的 untracked 文件 checkout 根本不会碰，会留在磁盘上。这一笔提交
            // 随分支一起删掉，不影响「历史永不重写」——它从未合并进主线。
            // WorkSession 只存了 userId、没存 userName，JGit 的 PersonIdent 又不接受
            // null 作者名，这里用一个占位名——这笔提交的署名不会被任何人看到。
            commitNow(projectId, userId, "废弃的工作", null);

            // 变更清单必须在 checkout 之前算好（口径同 revertTo/dockAndSwitchTo）：
            // checkout 之后 HEAD 已经是主线，再 diff 就什么都看不到了。
            List<FileChange> changes = repoService.diffNameStatus(
                    projectId, "HEAD", repoService.mainBranch());

            repoService.checkoutBranch(projectId, repoService.mainBranch());
            repoService.deleteBranch(projectId, s.getBranchName(), true);
            syncManifestFromRef(projectId, "HEAD");
            List<Long> affected = resolveAffectedFileIds(projectId, changes);

            s.setStatus(WorkSession.Status.DISCARDED);
            s.setEndedAt(LocalDateTime.now());
            sessionRepository.save(s);
            log.info("丢弃一段工作: project={}, branch={}", projectId, s.getBranchName());
            retryPendingIngest(projectId);
            return affected;
        } finally {
            lock.unlock();
        }
    }

    /** 退回结果：新版本的提交号，以及被这次退回改动过、且仍在数据库中的文件 id 列表。 */
    public record RevertResult(String sha, List<Long> affectedFileIds) {}

    /**
     * 退回到某一版。**不是历史重写**：把目标版本的内容还原到工作区，
     * 再作为一个新版本提交。时间线只会往前长。
     *
     * 磁盘文件已经被退回改写，但打开中的编辑器还端着退回前的内容——不重载的话
     * 下一次 autosave 就会把律师刚做的退回冲掉。这里不走 SSE 通知编辑器
     * （EditorBridgeService 的会话 ThreadLocal 只在 AI 工具调用期间才有值，
     * revertTo 唯一的调用方是普通 REST 端点 VersionController.revert，线程上永远
     * 没有会话 id，SSE 通知发不出去——响应驱动：把受影响文件的 id 随返回值带回去，
     * 前端自己决定重载哪些打开中的标签。
     */
    public RevertResult revertTo(long projectId, String ref, Long userId, String userName) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            requireNotMerging(projectId);
            cancelPending(projectId);

            // 先给当前状态留一笔，保证「退回」这个动作本身可撤销
            commitNow(projectId, userId, userName, null);

            // 变更列表必须在覆盖工作区之前算好——覆盖之后 HEAD 已经变了（下面这笔
            // "退回到早先的版本"提交），再 diff(ref, HEAD) 结果就不对了。这份列表
            // 之后还要拿来匹配受影响文件，覆盖前留存、不要事后再 diff 一次。
            List<FileChange> changes = repoService.diffNameStatus(projectId, ref, "HEAD");

            restoreWorkTreeFrom(projectId, ref, changes);
            syncManifestFromRef(projectId, ref);
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));

            String sha = repoService.commitAll(projectId,
                    "退回到早先的版本", "session", null, userName, email(userName));
            log.info("退回: project={}, ref={}, newSha={}", projectId, ref, sha);

            List<Long> affectedFileIds = sha == null
                    ? List.of() : resolveAffectedFileIds(projectId, changes);

            // cancelPending 上面把空闲定时器连 actors 一起清了（它本来是给防抖存档
            // 用的），而 commitNow 里的 ensureSession 只在**新建**分支时武装定时器——
            // 在一段已经活着的工作里退回，等于把这段工作的空闲自动结束永久拆掉，
            // 律师之后不点「结束本次工作」就一直挂着「工作中」。这里显式补武装，
            // 执行者按发起退回的人记（后续自动结束的合并要用他署名）。
            //
            // 稿分支上例外：稿不受空闲结束管辖（口径同 onChangeSignal），而这时的
            // activeSession 查到的是主线那边挂着的另一段工作——给它武装定时器，
            // 30 分钟后会在律师还站在稿上时触发一次自动结束（autoEndIfIdle 的分支
            // 校验会兜住，但没必要一开始就制造这个陈旧定时器）。
            if (!onDraftBranch(projectId)) {
                activeSession(projectId).ifPresent(s -> {
                    actors.put(projectId, new PendingActor(userId, userName));
                    armIdleTimer(projectId, s.getId());
                });
            }

            return new RevertResult(sha, affectedFileIds);
        } finally {
            lock.unlock();
        }
    }

    // ---- 稿：创建与双向切线（spec 第 3 期 Task 3） -------------------------

    /** 一次切线（切去某一线）的结果：切到的分支，以及这次切换改动过的文件 id。 */
    public record LineSwitchResult(String branch, List<Long> affectedFileIds) {}

    /** 另起一稿的结果：新建的 DRAFT 行，以及这次开稿本身也是一次切线的切线结果。 */
    public record DraftCreateResult(WorkSession draft, LineSwitchResult lineSwitch) {}

    /**
     * 另起一稿：从某个版本（{@code ref} 空则取当前 HEAD）拉一条长命分支并命名，
     * 独立于工作段体系存在——不自动合并、不受空闲结束管辖
     * （见 {@link WorkSession.SessionType#DRAFT}）。
     *
     * MERGING 态（正在进行的采纳裁决）拒绝开新稿：那期间工作区被冲突标记占用，
     * 开稿要做的 checkout 会把裁决现场冲掉。
     *
     * 受影响文件按「停靠后的 HEAD」与 {@code ref} 之间的差异计算——这份差异也正是
     * checkout 到 {@code ref} 之后工作区实际会变化的那些文件，语义与
     * {@link #dockAndSwitchTo} 一致，只是这里的目标分支是新建的，创建/checkout
     * 的顺序不同，未直接复用该方法。
     */
    public DraftCreateResult createDraft(long projectId, String ref, String name,
                                         Long userId, String userName) {
        String draftName = validateDraftName(name);
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            requireNotMerging(projectId);
            String effectiveRef = (ref == null || ref.isBlank()) ? "HEAD" : ref;

            cancelPending(projectId);
            dockCurrentLine(projectId, userId, userName);
            String beforeHead = repoService.resolveRef(projectId, "HEAD");
            List<FileChange> changes = repoService.diffNameStatus(projectId, beforeHead, effectiveRef);

            String branch = "draft/" + System.currentTimeMillis();
            repoService.createBranch(projectId, branch, effectiveRef);
            repoService.checkoutBranch(projectId, branch);
            syncManifestFromRef(projectId, "HEAD");
            List<Long> affected = resolveAffectedFileIds(projectId, changes);

            WorkSession s = new WorkSession();
            s.setProjectId(projectId);
            s.setBranchName(branch);
            s.setStartedAt(LocalDateTime.now());
            s.setStatus(WorkSession.Status.ACTIVE);
            s.setSessionType(WorkSession.SessionType.DRAFT);
            s.setTitle(draftName);
            s.setUserId(userId);
            WorkSession saved = sessionRepository.save(s);
            log.info("另起一稿: project={}, branch={}, name={}", projectId, branch, draftName);

            return new DraftCreateResult(saved, new LineSwitchResult(branch, affected));
        } finally {
            lock.unlock();
        }
    }

    /** 本项目所有还在进行中的稿，按建立时间倒序——律师最近开的稿排在最前面。 */
    public List<WorkSession> listDrafts(long projectId) {
        return sessionRepository.findByProjectIdAndStatusAndSessionTypeOrderByStartedAtDesc(
                projectId, WorkSession.Status.ACTIVE, WorkSession.SessionType.DRAFT);
    }

    /**
     * 切到某一稿。已经在这一稿上是幂等操作——{@link #dockAndSwitchTo} 停靠后计算的
     * 差异天然为空，不需要额外的分支判断。
     */
    public LineSwitchResult switchToDraft(long projectId, long draftId, Long userId, String userName) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            requireNotMerging(projectId);
            WorkSession draft = requireActiveDraft(projectId, draftId);
            return dockAndSwitchTo(projectId, draft.getBranchName(), userId, userName);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 切回主线：目标分支优先取 ACTIVE 工作段自己的分支（律师之前手上那段工作还在，
     * 不是 master），没有 ACTIVE 工作段时才是 master。
     *
     * 切回后如果确实存在 ACTIVE 工作段，必须重新武装它的空闲定时器——
     * 这段工作在切去稿上的这段时间里，空闲定时器已经被 {@link #cancelPending}
     * 拆掉了（{@link #dockAndSwitchTo} 内部调用），不重新武装的话这段工作从此
     * 不会再被「30 分钟无动静自动结束」覆盖到，会永远挂着「工作中」。
     */
    public LineSwitchResult switchToMainline(long projectId, Long userId, String userName) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            requireNotMerging(projectId);
            Optional<WorkSession> activeWork = activeSession(projectId);
            String target = activeWork.map(WorkSession::getBranchName)
                    .orElseGet(repoService::mainBranch);

            LineSwitchResult result = dockAndSwitchTo(projectId, target, userId, userName);

            activeWork.ifPresent(s -> {
                actors.put(projectId, new PendingActor(userId, userName));
                armIdleTimer(projectId, s.getId());
            });

            return result;
        } finally {
            lock.unlock();
        }
    }

    // ---- 稿：采纳 / 裁决 / 中止 / 放弃（spec 第 3 期 Task 4） -----------------

    /**
     * 采纳一稿的结果。{@code success=false} 时 {@code conflictingPaths} 非空、仓库停在
     * 待裁决状态，等 {@link #resolveAdopt} 或 {@link #abortAdopt}；{@code affectedFileIds}
     * 两种情况下都可能非空——冲突时它是「回到主线侧」这一步已经改写过的文件，磁盘已经变了，
     * 打开中的编辑器该重载就得重载，不能因为采纳没走完就瞒着前端。{@code notice} 非空表示
     * 「采纳成功，但有一句话要告诉律师」——目前只有一种：稿 tip 是主线的祖先（比如从旧
     * 版本另起一稿、一笔都没改就采纳），没有任何实质内容可采纳，未生成版本。
     */
    public record AdoptOutcome(boolean success, String sha,
                               List<String> conflictingPaths, List<Long> affectedFileIds,
                               String notice) {}

    /** 逐文件三选一：用主线的 / 用这一稿的 / 两份都留。 */
    public enum Resolution { MAIN, DRAFT, BOTH }

    /**
     * 中止采纳后要告诉律师的那句话（spec 第七节原句）。
     * {@link #abortAdopt} 本身不抛异常也不返回它——中止是成功路径，
     * 由控制器把这个常量放进响应的 message 字段。
     */
    public static final String ADOPT_ABORTED_NOTICE = "这次采纳没有完成，你的两份稿件都还在";

    /**
     * 采纳一稿：把稿合并回主线，稿从此结束。
     *
     * 前置三条：没有进行中的工作（一段活着的工作段自己还没收尾，把稿并进来会让两件事
     * 缠在一起——先让律师收尾或丢弃）、目标是本项目 ACTIVE 的稿、仓库不在待裁决状态。
     *
     * 锁内先无条件走一次 {@link #switchToMainline}：律师按下「采纳这一稿」时通常正站在
     * 稿上，必须先停靠稿、回到主线侧才能合并；已经在主线上时这次切换只做停靠（工作区
     * 不干净的话 JGit 会拒绝合并）。切换本身也会改写磁盘（稿的内容换回主线的内容），
     * 这些文件同样要进最终的重载列表。
     *
     * 冲突时仓库留在待裁决状态（{@link ProjectRepoService#mergeKeepingConflicts}），
     * 稿的行与分支一个都不动——中止一次采纳必须能让两边分毫无损。
     */
    public AdoptOutcome adoptDraft(long projectId, long draftId, Long userId, String userName) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            requireNotMerging(projectId);
            if (activeSession(projectId).isPresent()) {
                throw VersionException.userFacing("请先结束或丢弃当前工作，再采纳这一稿");
            }
            WorkSession draft = requireActiveDraft(projectId, draftId);

            LineSwitchResult back = switchToMainline(projectId, userId, userName);

            // 合并还没提交，HEAD 就是合并前的主线 tip；稿 tip 要在停靠之后才准。
            String mainTipBefore = repoService.resolveRef(projectId, "HEAD");
            String draftTip = repoService.resolveRef(projectId, draft.getBranchName());

            // 干净路径也不让 JGit 自己提交（mergeNoCommit）：两条路都要以数据库为源
            // 写清单、清单必须进同一个采纳提交，见 completeAdopt。
            MergeOutcome outcome = repoService.mergeNoCommit(projectId,
                    draft.getBranchName(), adoptMessage(draft), userName, email(userName));

            if (outcome.success()) {
                if (outcome.mergeSha() != null) {
                    // mergeNoCommit 的契约（见其 Javadoc）：干净合并永远不自己提交，
                    // mergeSha 非空只可能来自 ALREADY_UP_TO_DATE 这一条路——稿 tip
                    // 已经是主线的祖先，JGit 压根没进入合并流程，没有 MERGE_HEAD，
                    // 无从「稍后提交」。典型场景：从旧版本另起一稿，一笔都没改就
                    // 点了采纳。不能落到 completeAdopt：那边的 unionApply 会把稿
                    // tip（旧版本）的清单当成「新内容」按 draft-wins 覆盖进当前
                    // 数据库，把文件树静默改回旧版模样——磁盘、Git 历史都不动，
                    // 纯数据库层面的静默损坏，且没有任何提交可供事后追查。
                    return finishEmptyAdopt(projectId, draft);
                }
                // MERGED_NOT_COMMITTED：合并有实质内容，交给 completeAdopt 补齐
                // 清单并落成采纳提交。
                return completeAdopt(projectId, draft, draftTip, mainTipBefore,
                        null, back.affectedFileIds(), userName);
            }

            List<String> conflicts = userVisibleConflicts(outcome.conflictingPaths());
            if (!repoService.repositoryMerging(projectId)) {
                // 合并没成、也没留下待裁决现场（FAILED / CHECKOUT_CONFLICT / ABORTED）。
                // 这不是「有冲突等着律师三选一」——既没有 MERGE_HEAD 也没有冲突索引，
                // 后面的自裁/裁决路径一步都走不通，必须显式失败，不能误当自裁路径。
                log.warn("采纳没能开始: project={}, branch={}, conflicts={}",
                        projectId, draft.getBranchName(), outcome.conflictingPaths());
                throw VersionException.userFacing("这次采纳没能开始，请稍后重试");
            }
            if (conflicts.isEmpty()) {
                // 只有内部的文件树清单冲突。律师不认识这个文件、也无从选择，
                // 而清单并集本来就要按并集规则重写它——自己裁决掉，别去打扰他。
                return completeAdopt(projectId, draft, draftTip, mainTipBefore,
                        null, back.affectedFileIds(), userName);
            }
            log.info("采纳一稿遇到冲突，停在待裁决: project={}, branch={}, files={}",
                    projectId, draft.getBranchName(), conflicts.size());
            // 冲突合并同样会把**非冲突**的稿侧改动检出到工作区，这些文件的磁盘字节已经变了。
            // 只带「回到主线侧」那一步的差异是不够的——律师本来就站在主线上按采纳时那一步
            // 是空的，打开中的编辑器端着旧字节，一次 autosave 就把稿的改动写回去，随后
            // commitMergeResolution 的 git add . 把它收进采纳提交，稿的改动无声丢失。
            return new AdoptOutcome(false, null, conflicts,
                    mergeAffected(projectId, back.affectedFileIds(), mainTipBefore, draftTip),
                    null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 逐文件裁决后完成采纳。{@code resolutions} 必须覆盖全部待选择的文件。
     *
     * 「主线那一份」的字节取自 {@code HEAD}：合并尚未提交，HEAD 仍然停在合并前的主线
     * tip（{@link ProjectRepoService#commitMergeResolution} 才会推进它）。「这一稿那一份」
     * 取自 {@code MERGE_HEAD}，也就是稿的 tip。
     */
    public AdoptOutcome resolveAdopt(long projectId, long draftId,
                                     Map<String, Resolution> resolutions,
                                     Long userId, String userName) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            WorkSession draft = requireActiveDraft(projectId, draftId);
            if (!repoService.repositoryMerging(projectId)) {
                throw VersionException.userFacing("现在没有等着做选择的采纳");
            }
            String draftTip = repoService.mergeHeadRef(projectId);
            if (draftTip == null
                    || !draftTip.equals(repoService.resolveRef(projectId, draft.getBranchName()))) {
                throw VersionException.userFacing("正在处理的是另一稿，请先把它处理完");
            }
            String mainTipBefore = repoService.resolveRef(projectId, "HEAD");

            // 保险带：仓库还在合并中、却一条冲突记录都没有，等价于 MERGING_RESOLVED——
            // 索引里的冲突被什么东西 add 掉了。此时下面的「覆盖全部冲突」校验会空转、
            // 逐文件裁决一次都不执行，带 <<<<<<< 标记的半成品会被当成裁决结果提交进
            // 主线、稿分支还会被删，不可逆。修掉 pendingChanges 的冲突窗口守卫后这
            // 一步不该再发生，发生即 bug——技术档异常，不给 userFacing 粉饰。
            List<String> rawConflicts = repoService.conflictingPaths(projectId);
            if (rawConflicts.isEmpty()) {
                throw new VersionException(
                        "冲突记录已丢失，无法安全完成采纳: project=" + projectId
                                + " draft=" + draft.getBranchName());
            }

            List<String> conflicts = userVisibleConflicts(rawConflicts);
            Map<String, Resolution> choices = resolutions == null ? Map.of() : resolutions;
            for (String path : conflicts) {
                if (choices.get(path) == null) {
                    throw VersionException.userFacing("还有文件没有做出选择");
                }
            }

            for (String path : conflicts) {
                applyResolution(projectId, path, choices.get(path),
                        mainTipBefore, draftTip, draft.getTitle());
            }

            return completeAdopt(projectId, draft, draftTip, mainTipBefore,
                    null, List.of(), userName);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 中止这次采纳：工作区回到合并前，主线与稿都分毫无损（历史一笔不动）。
     * 稿保持 ACTIVE 原样，律师可以继续在上面改、或改天再采纳。
     * 不在待裁决状态时是真正的 no-op（守卫在 {@link ProjectRepoService#abortMerge} 里）。
     */
    public void abortAdopt(long projectId) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            repoService.abortMerge(projectId);
            log.info("中止一次采纳: project={}", projectId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 放弃这一稿：稿上的改动整条丢掉（分支 force 删除），稿标 DISCARDED。
     * 站在这一稿上时先切回主线侧（复用 {@link #switchToMainline}——不先离开就不能删
     * 当前分支，而且稿上未提交的改动要先停靠进这条即将消失的分支，否则 checkout
     * 会被 JGit 拒绝）；不在这一稿上时当前这条线一动不动，受影响文件为空。
     */
    public LineSwitchResult abandonDraft(long projectId, long draftId, Long userId, String userName) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            requireNotMerging(projectId);
            WorkSession draft = requireActiveDraft(projectId, draftId);

            LineSwitchResult result;
            if (draft.getBranchName().equals(repoService.currentBranch(projectId))) {
                result = switchToMainline(projectId, userId, userName);
            } else {
                result = new LineSwitchResult(repoService.currentBranch(projectId), List.of());
            }

            repoService.deleteBranch(projectId, draft.getBranchName(), true);
            draft.setStatus(WorkSession.Status.DISCARDED);
            draft.setEndedAt(LocalDateTime.now());
            sessionRepository.save(draft);
            log.info("放弃一稿: project={}, branch={}, name={}",
                    projectId, draft.getBranchName(), draft.getTitle());
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** 时间线上这个采纳节点的名字。裁决路径与干净路径必须用同一句，否则同一动作两种叫法。 */
    private static String adoptMessage(WorkSession draft) {
        return "采纳：" + draft.getTitle();
    }

    /**
     * 采纳收尾：清单并集 → （尚未提交时）重写清单并落裁决提交 → 稿标 MERGED → 删稿分支
     * → 汇总受影响文件。
     *
     * {@code committedSha} 非空表示合并已经自己提交过了（干净合并），此时不能也不必再
     * 提交一次；为空表示仓库还停在待裁决状态，工作区里是裁决后的最终内容，由
     * {@link ProjectRepoService#commitMergeResolution} 落成双亲提交。
     *
     * 清单读的是**稿 tip 那一版**，而不是合并后的 HEAD：合并后的清单是 Git 对两份 JSON
     * 做的文本合并，冲突时更是带着冲突标记的半成品，都不能当数据源。
     */
    private AdoptOutcome completeAdopt(long projectId, WorkSession draft, String draftTip,
                                       String mainTipBefore, String committedSha,
                                       List<Long> extraAffected, String userName) {
        TreeManifest draftManifest = manifestService.readAtRef(projectId, draftTip);
        if (draftManifest != null) manifestService.unionApply(projectId, draftManifest);

        String sha = committedSha;
        if (sha == null) {
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
            sha = repoService.commitMergeResolution(projectId, adoptMessage(draft),
                    userName, email(userName));
        }

        draft.setStatus(WorkSession.Status.MERGED);
        draft.setEndedAt(LocalDateTime.now());
        sessionRepository.save(draft);
        repoService.deleteBranch(projectId, draft.getBranchName(), true);

        List<Long> affected = mergeAffected(projectId, extraAffected, mainTipBefore, "HEAD");

        log.info("采纳一稿: project={}, branch={}, name={}, sha={}",
                projectId, draft.getBranchName(), draft.getTitle(), sha);
        return new AdoptOutcome(true, sha, List.of(), affected, null);
    }

    /**
     * 稿没有任何实质内容时的采纳收尾（ALREADY_UP_TO_DATE：稿 tip 已经是主线的祖先）。
     * 主线本就包含稿的全部内容，没有东西可合并、也没有东西可提交——跳过
     * {@link #completeAdopt} 整条链路（不读稿的清单、不 unionApply、不写清单、不提交），
     * 只兑现「收下这稿」的意图：稿标 MERGED、删分支，并告诉律师这一稿没有生成新版本。
     */
    private AdoptOutcome finishEmptyAdopt(long projectId, WorkSession draft) {
        draft.setStatus(WorkSession.Status.MERGED);
        draft.setEndedAt(LocalDateTime.now());
        sessionRepository.save(draft);
        repoService.deleteBranch(projectId, draft.getBranchName(), true);
        log.info("采纳一稿但没有实质内容，未生成版本: project={}, branch={}, name={}",
                projectId, draft.getBranchName(), draft.getTitle());
        return new AdoptOutcome(true, null, List.of(), List.of(),
                "这一稿没有任何改动，未生成版本");
    }

    /**
     * 内部的文件树清单（{@code .awd/tree.json}）永远不出现在律师的选择清单里：他不认识
     * 这个文件，而它的正确内容由清单并集算出来、由 {@link #completeAdopt} 重写。
     * 排序只为了让前端拿到的顺序稳定。
     */
    private static List<String> userVisibleConflicts(List<String> paths) {
        return paths.stream().filter(p -> !p.startsWith(".awd/")).sorted().toList();
    }

    /**
     * 一个文件的裁决落地到工作区。索引里的冲突标记不用手工清——
     * {@link ProjectRepoService#commitMergeResolution} 的两次 add（含 update）会把
     * 工作区的最终状态整体收进去，包括「裁决结果是这个文件不存在」这种情况。
     */
    private void applyResolution(long projectId, String path, Resolution choice,
                                 String mainTip, String draftTip, String draftName) {
        String rel = safeRepoPath(path);
        Path work = repoService.workTree(projectId);
        byte[] mainBytes = repoService.readBlobAtCommit(projectId, mainTip, rel);
        byte[] draftBytes = repoService.readBlobAtCommit(projectId, draftTip, rel);

        switch (choice) {
            case MAIN -> writeOrDelete(work.resolve(rel), mainBytes);
            case DRAFT -> writeOrDelete(work.resolve(rel), draftBytes);
            case BOTH -> {
                writeOrDelete(work.resolve(rel), mainBytes);
                // 稿把这个文件删掉了的话，「两份都留」里稿的那一份根本不存在，
                // 留主线那一份就是全部。
                if (draftBytes != null) {
                    String copyRel = sideBySideRelPath(projectId, rel, draftName);
                    writeOrDelete(work.resolve(copyRel), draftBytes);
                    createSideBySideRow(projectId, rel, copyRel, draftBytes.length);
                }
            }
        }
    }

    private void writeOrDelete(Path target, byte[] bytes) {
        try {
            if (bytes == null) {
                Files.deleteIfExists(target);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (Exception e) {
            throw new VersionException("写入裁决结果失败: " + target, e);
        }
    }

    /**
     * 「两份都留」时稿那一份的落脚路径：同目录、原名后面缀上《（来自：{稿名}）》，
     * 扩展名保持不变（律师双击还是要能打开）。撞名了就在后面追加序号。
     */
    private String sideBySideRelPath(long projectId, String rel, String draftName) {
        int slash = rel.lastIndexOf('/');
        String dir = slash < 0 ? "" : rel.substring(0, slash + 1);
        String fileName = rel.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        String suffix = "（来自：" + draftName + "）";

        Path work = repoService.workTree(projectId);
        String candidate = dir + base + suffix + ext;
        for (int n = 2; Files.exists(work.resolve(candidate)); n++) {
            candidate = dir + base + suffix + n + ext;
        }
        return candidate;
    }

    /**
     * 「两份都留」另存出来的那份文件也要在文件树里露出来，否则律师只在磁盘上有它、
     * 界面里根本看不见。复制原行的父目录、类型与创建者，排序紧跟在原行后面。
     * 原行在数据库里找不到（例如这份文件从来没进过文件树）时不建行——文件本身已经
     * 写在磁盘上，不能因为这个让整次采纳失败。
     */
    private void createSideBySideRow(long projectId, String originalRel, String copyRel, long size) {
        String originalFilePath = "projects/" + projectId + "/" + originalRel;
        ProjectFile origin = fileRepository.findByProjectId(projectId).stream()
                .filter(f -> originalFilePath.equals(f.getFilePath()))
                .findFirst()
                .orElse(null);
        if (origin == null) {
            log.warn("两份都留：原文件不在文件树里，跳过建行: project={}, path={}",
                    projectId, originalRel);
            return;
        }

        ProjectFile copy = new ProjectFile();
        copy.setProjectId(projectId);
        copy.setParentId(origin.getParentId());
        copy.setUserId(origin.getUserId());
        copy.setFileType(origin.getFileType());
        copy.setIsFolder(false);
        copy.setIsDeleted(false);
        copy.setName(copyRel.substring(copyRel.lastIndexOf('/') + 1));
        copy.setFilePath("projects/" + projectId + "/" + copyRel);
        copy.setFileSize(size);
        copy.setSortOrder(origin.getSortOrder() == null ? 0 : origin.getSortOrder() + 1);
        copy.setCreatedAt(LocalDateTime.now());
        fileRepository.save(copy);
    }

    /**
     * 切线共用内核：停靠当前线（{@link #commitNow}，同时把陈旧的防抖/空闲定时器一并
     * 清掉）→ 记下停靠后的 HEAD → 算好目标分支相对它的变更清单（必须在 checkout 之前
     * 算，checkout 之后 HEAD 已经变了）→ checkout 目标分支（这是真正切到另一条已存在
     * 的历史线，JGit 的 checkout 本身会把工作区文件改写成目标分支的内容——跟
     * {@link #revertTo} 手工 restoreWorkTreeFrom 不同，那边是在同一条线上新造一笔
     * 提交，绝不切分支）→ 清单同步回数据库（机制同 revertTo：读目标分支当前 HEAD 的
     * 清单，applyToDatabase）→ 受影响文件 id。
     */
    private LineSwitchResult dockAndSwitchTo(long projectId, String targetBranch,
                                             Long userId, String userName) {
        cancelPending(projectId);
        dockCurrentLine(projectId, userId, userName);
        String beforeHead = repoService.resolveRef(projectId, "HEAD");
        List<FileChange> changes = repoService.diffNameStatus(projectId, beforeHead, targetBranch);
        repoService.checkoutBranch(projectId, targetBranch);
        syncManifestFromRef(projectId, "HEAD");
        List<Long> affected = resolveAffectedFileIds(projectId, changes);
        return new LineSwitchResult(targetBranch, affected);
    }

    /**
     * 只在「确实站在一条需要停靠的线上」时才调用 {@link #commitNow}：当前在稿分支上
     * （commitNow 的守卫会跳过 ensureSession，只管落盘，必须总是执行），或者已经存在
     * ACTIVE 工作段（commitNow 里的 ensureSession 只是复用，不会新建分支）。
     *
     * 干净的主线、且没有任何 ACTIVE 工作段时，什么都不用停靠——什么都不做。否则
     * commitNow 内部的 ensureSession 会在主线上凭空开一段从未被律师编辑过的空工作，
     * 这段工作会一直挂着「工作中」，还会把之后「切回主线」的目标从 master 错误地
     * 带偏到这段凭空冒出来的分支上。
     *
     * 但「有没有段」不是「工作区干不干净」的可靠代理：AI artifact 保存、尽调插件
     * 上传、分片上传中途、解压时序缺陷等几条路径会弄脏主线工作区却不经过
     * {@link #onChangeSignal} 发信号（没有信号就不会隐式开段）。这种「脏但无段」的
     * 状态下如果照旧什么都不做，随后 checkout 目标分支会因为工作区有未提交改动
     * 而被 JGit 拒绝（CheckoutConflictException），开稿/切线以一句笼统的技术错误
     * 反复失败。所以这里补一条兜底：脏则落一笔无主的 auto 自动存档——但绝不能
     * 调用 commitNow，它内部的 ensureSession 会在主线上凭空孵出一段从未被律师
     * 编辑过的 WORK 工作段，制造出跟上面同一段注释警告的"幽灵段"问题。
     * pendingChanges 本身不加锁（见 {@link #pendingChangesLocked} 的注释），这里
     * 调用方已经持有本项目的锁，直接调用即可。
     */
    private void dockCurrentLine(long projectId, Long userId, String userName) {
        if (onDraftBranch(projectId) || activeSession(projectId).isPresent()) {
            commitNow(projectId, userId, userName, null);
        } else if (!repoService.pendingChanges(projectId).isEmpty()) {
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
            String msg = describePendingChanges(projectId);
            repoService.commitAll(projectId, msg, "auto", null, userName, email(userName));
        }
    }

    /** 仓库处于保留冲突态的合并中时，拒绝一切切线/开稿——那期间工作区是裁决现场。 */
    private void requireNotMerging(long projectId) {
        if (repoService.repositoryMerging(projectId)) {
            throw VersionException.userFacing("请先处理正在进行的采纳");
        }
    }

    /** 目标必须是本项目里 ACTIVE 的 DRAFT，否则给一句律师能懂的话，不暴露内部状态。 */
    private WorkSession requireActiveDraft(long projectId, long draftId) {
        WorkSession draft = sessionRepository.findById(draftId).orElse(null);
        if (draft == null
                || !draft.getProjectId().equals(projectId)
                || draft.getStatus() != WorkSession.Status.ACTIVE
                || draft.getSessionType() != WorkSession.SessionType.DRAFT) {
            throw VersionException.userFacing("这一稿不存在或已处理");
        }
        return draft;
    }

    /** 稿名口径照里程碑命名（VersionController.markMilestone）：必填，最多 64 字。 */
    private static String validateDraftName(String name) {
        if (name == null || name.isBlank()) {
            throw VersionException.userFacing("请给这一稿起个名字");
        }
        String trimmed = name.strip();
        if (trimmed.length() > 64) {
            throw VersionException.userFacing("名字太长了，请控制在 64 字以内");
        }
        return trimmed;
    }

    /**
     * 把一次改动（退回 / 开稿 / 切线）涉及的仓库相对路径，匹配到当前数据库里的
     * ProjectFile 记录，收集受影响文件的 id。只是给前端重载编辑器用的辅助信息，
     * 不是主流程——匹配失败绝不能让调用方的主操作失败，这里整体包死，出错就退化
     * 成空列表。
     */
    private List<Long> resolveAffectedFileIds(long projectId, List<FileChange> changes) {
        try {
            List<ProjectFile> files = fileRepository.findByProjectId(projectId);
            List<Long> ids = new ArrayList<>();
            for (FileChange c : changes) {
                String path = c.path();
                if (path.startsWith(".awd/")) continue;
                String targetPath = "projects/" + projectId + "/" + path;
                for (ProjectFile f : files) {
                    if (targetPath.equals(f.getFilePath())) {
                        ids.add(f.getId());
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("退回后匹配受影响文件失败: project={}", projectId, e);
            return List.of();
        }
    }

    /**
     * 把 {@code fromRef→toRef} 之间改动过的文件并进一份已有的重载列表（去重、保序）。
     * 采纳的两条返回路径共用：冲突路径带的是「合并已经改写的文件」，收尾路径带的是
     * 「采纳提交相对合并前主线 tip 改写的文件」。
     */
    private List<Long> mergeAffected(long projectId, List<Long> already,
                                     String fromRef, String toRef) {
        List<Long> out = new ArrayList<>(already);
        for (Long id : resolveAffectedFileIds(projectId,
                repoService.diffNameStatus(projectId, fromRef, toRef))) {
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------

    private void cancelPending(long projectId) {
        ScheduledFuture<?> f = pending.remove(projectId);
        if (f != null) f.cancel(false);
        ScheduledFuture<?> idle = idleTimers.remove(projectId);
        if (idle != null) idle.cancel(false);
        actors.remove(projectId);
    }

    /**
     * 把目标版本的所有文件覆盖回工作区；目标版本没有的文件删掉。
     * changes 由调用方在覆盖工作区之前算好传入——覆盖发生后 HEAD 已经变了，
     * 这里不能自己再重新 diff 一次。
     */
    private void restoreWorkTreeFrom(long projectId, String ref, List<FileChange> changes) {
        Path work = repoService.workTree(projectId);
        try {
            for (FileChange c : changes) {
                Path target = work.resolve(c.path());
                byte[] bytes = repoService.readBlobAtCommit(projectId, ref, c.path());
                if (bytes == null) {
                    Files.deleteIfExists(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                }
            }
        } catch (Exception e) {
            throw new VersionException("还原文件失败: project=" + projectId + " ref=" + ref, e);
        }
    }

    private void syncManifestFromRef(long projectId, String ref) {
        TreeManifest m = manifestService.readAtRef(projectId, ref);
        if (m != null) manifestService.applyToDatabase(projectId, m);
    }

    /** 校验外部传入的仓库相对路径。非法即抛（技术档，不回显内容）。 */
    static String safeRepoPath(String path) {
        if (path == null || path.isBlank() || path.contains("\\") || path.startsWith("/")) {
            throw new VersionException("非法路径");
        }
        String normalized = path.strip();
        for (String seg : normalized.split("/", -1)) {
            if (seg.isEmpty() || seg.equals("..") || seg.equals(".")) {
                throw new VersionException("非法路径");
            }
        }
        if (normalized.equals(".awd") || normalized.startsWith(".awd/")) {
            throw new VersionException("非法路径");
        }
        return normalized;
    }

    /** ProjectFile.filePath（projects/{id}/...）→ 仓库相对路径。归属不符即拒。 */
    static String repoRelativePath(com.checkba.model.entity.ProjectFile f) {
        String fp = f == null ? null : f.getFilePath();
        if (f == null || fp == null) throw new VersionException("文件没有物理路径");
        String prefix = "projects/" + f.getProjectId() + "/";
        if (!fp.startsWith(prefix) || fp.length() <= prefix.length()) {
            throw new VersionException("文件路径不在本项目内: fileId=" + f.getId());
        }
        return safeRepoPath(fp.substring(prefix.length()));
    }

    /**
     * 生成律师在时间线上看到的那句话。清单文件是内部机制，不出现在描述里。
     */
    static String describeChanges(List<FileChange> changes) {
        List<String> names = changes.stream()
                .map(FileChange::path)
                .filter(p -> !p.startsWith(".awd/"))
                .map(WorkSessionService::displayName)
                .toList();
        if (names.isEmpty()) return "整理了文件结构";
        if (names.size() == 1) return "修改了《" + names.get(0) + "》";
        return "修改了《" + names.get(0) + "》等 " + names.size() + " 份文件";
    }

    /** 取文件名并去掉扩展名——律师习惯说《股权转让协议》，不说 .docx。 */
    private static String displayName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String describePendingChanges(long projectId) {
        try {
            return describeChanges(repoService.pendingChanges(projectId));
        } catch (Exception e) {
            log.warn("生成变更描述失败: project={}", projectId, e);
            return "修改了项目文件";
        }
    }

    private String defaultTitle(LocalDateTime startedAt) {
        LocalDateTime t = startedAt != null ? startedAt : LocalDateTime.now();
        String half = t.getHour() < 12 ? "上午" : (t.getHour() < 18 ? "下午" : "晚上");
        return t.format(TITLE_FMT) + half + "的工作";
    }

    private String email(String userName) {
        return (userName == null ? "user" : userName) + "@aiworkdeck.local";
    }
}
