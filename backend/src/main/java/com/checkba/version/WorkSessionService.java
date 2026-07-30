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

    /** 上次没正常结束的工作段（崩溃或强杀留下的）。 */
    public Optional<WorkSession> pendingRecovery(long projectId) {
        return activeSession(projectId);
    }

    /** 继续上次没结束的工作：切回该分支。 */
    public void resumeSession(long projectId) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
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

    /** 空闲定时器触发：仍在工作中的话自动结束，标题走默认命名。任何异常只记日志。 */
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
            return repoService.pendingChanges(projectId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 供 RepoMaintenanceJob 的每日 GC 用：gc 本身不改可达历史，但线程池不再是 1，
     * 可能跟自动存档/合并并发抢 .git 索引，同样要过按项目维度的这把锁。
     */
    public void gcLocked(long projectId) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            repoService.gc(projectId);
        } finally {
            lock.unlock();
        }
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
     * 已知局限（与文档检查点同源）：编辑器自动保存是异步的，轮次结束时未 flush 的
     * 改动不在本笔里，会随后续保存进入普通存档。
     */
    public String commitAiRound(long projectId, Long userId) {
        if (!repoService.isInitialized(projectId)) return null;
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            ensureSession(projectId, userId, AI_AUTHOR_NAME);
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
            return new SessionEndResult(outcome.mergeSha(), null);
        } finally {
            lock.unlock();
        }
    }

    /** 丢弃整段工作：删分支，工作区回到主线状态，数据库文件树跟着回去。 */
    public void discardSession(long projectId, Long userId) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
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
            repoService.checkoutBranch(projectId, repoService.mainBranch());
            repoService.deleteBranch(projectId, s.getBranchName(), true);
            syncManifestFromRef(projectId, "HEAD");

            s.setStatus(WorkSession.Status.DISCARDED);
            s.setEndedAt(LocalDateTime.now());
            sessionRepository.save(s);
            log.info("丢弃一段工作: project={}, branch={}", projectId, s.getBranchName());
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
            activeSession(projectId).ifPresent(s -> {
                actors.put(projectId, new PendingActor(userId, userName));
                armIdleTimer(projectId, s.getId());
            });

            return new RevertResult(sha, affectedFileIds);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 把退回改动过的仓库相对路径，匹配到当前数据库里的 ProjectFile 记录，收集受影响
     * 文件的 id。只是给前端重载编辑器用的辅助信息，不是主流程——匹配失败绝不能让
     * 退回本身失败，这里整体包死，出错就退化成空列表。
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
