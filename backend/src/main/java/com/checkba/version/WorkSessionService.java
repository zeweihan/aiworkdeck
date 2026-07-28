package com.checkba.version;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    private final ProjectRepoService repoService;
    private final ProjectTreeManifestService manifestService;
    private final WorkSessionRepository sessionRepository;
    private final TaskScheduler taskScheduler;

    /** 防抖静默期。测试里调短或调长以取得确定性。 */
    private long debounceMillis = 2 * 60 * 1000L;

    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
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
                              TaskScheduler taskScheduler) {
        this.repoService = repoService;
        this.manifestService = manifestService;
        this.sessionRepository = sessionRepository;
        this.taskScheduler = taskScheduler;
    }

    public void setDebounceMillis(long millis) { this.debounceMillis = millis; }

    public Optional<WorkSession> activeSession(long projectId) {
        return sessionRepository.findFirstByProjectIdAndStatus(
                projectId, WorkSession.Status.ACTIVE);
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
     */
    public void onChangeSignal(long projectId, Long userId, String userName) {
        if (!repoService.isInitialized(projectId)) return;

        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            ensureSession(projectId, userId);
        } finally {
            lock.unlock();
        }
        actors.put(projectId, new PendingActor(userId, userName));

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

    private WorkSession ensureSession(long projectId, Long userId) {
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
        log.info("开始一段工作: project={}, branch={}", projectId, branch);
        return sessionRepository.save(s);
    }

    /** 立即落一笔自动存档。无变更时返回 null。 */
    public String commitNow(long projectId, Long userId, String userName, String message) {
        if (!repoService.isInitialized(projectId)) return null;
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            ensureSession(projectId, userId);
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));
            String msg = message != null ? message : describePendingChanges(projectId);
            return repoService.commitAll(projectId, msg, "auto", null, userName, email(userName));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 结束本次工作：收尾提交 → 切回主线 → 合并 → 关闭工作段。
     * 单人场景下主线在工作期间不会变，合并总是快进。
     */
    public String endSession(long projectId, Long userId, String userName, String title) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            WorkSession s = activeSession(projectId)
                    .orElseThrow(() -> VersionException.userFacing("当前没有进行中的工作"));

            cancelPending(projectId);
            commitNow(projectId, userId, userName, null);

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
            return outcome.mergeSha();
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

    /**
     * 退回到某一版。**不是历史重写**：把目标版本的内容还原到工作区，
     * 再作为一个新版本提交。时间线只会往前长。
     */
    public String revertTo(long projectId, String ref, Long userId, String userName) {
        ReentrantLock lock = repoLock(projectId);
        lock.lock();
        try {
            cancelPending(projectId);

            // 先给当前状态留一笔，保证「退回」这个动作本身可撤销
            commitNow(projectId, userId, userName, null);

            restoreWorkTreeFrom(projectId, ref);
            syncManifestFromRef(projectId, ref);
            manifestService.writeToWorkTree(projectId, manifestService.capture(projectId));

            String sha = repoService.commitAll(projectId,
                    "退回到早先的版本", "session", null, userName, email(userName));
            log.info("退回: project={}, ref={}, newSha={}", projectId, ref, sha);
            return sha;
        } finally {
            lock.unlock();
        }
    }

    // ---- helpers ----------------------------------------------------------

    private void cancelPending(long projectId) {
        ScheduledFuture<?> f = pending.remove(projectId);
        if (f != null) f.cancel(false);
        actors.remove(projectId);
    }

    /** 把目标版本的所有文件覆盖回工作区；目标版本没有的文件删掉。 */
    private void restoreWorkTreeFrom(long projectId, String ref) {
        Path work = repoService.workTree(projectId);
        try {
            List<FileChange> changes = repoService.diffNameStatus(projectId, ref, "HEAD");
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
