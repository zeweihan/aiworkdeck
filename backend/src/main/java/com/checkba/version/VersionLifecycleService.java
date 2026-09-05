package com.checkba.version;

import com.checkba.model.entity.Project;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 版本记录的开关生命周期（dev-board#438）：默认自动开启，律师可以关掉且不会被开回来。
 *
 * <p>「注册一个账号就能直接用版本管理」——版本记录是纯本地 Git 仓库，不需要任何服务器，
 * 唯一挡在律师面前的就是那一下手动开启。所以现在有两个自动触发点：
 * <ul>
 *   <li><b>新建项目之后</b>（{@link ProjectService.ProjectCreatedEvent}）：这时项目是空的，
 *       开起来是一瞬间的事；</li>
 *   <li><b>存量项目收到第一个变更信号时</b>（{@link WorkSessionService.AutoEnableRequest}）：
 *       律师在这个项目里做的第一个动作就把它开起来，不需要额外 hook「打开项目」。</li>
 * </ul>
 *
 * <p>两个触发点都异步执行（{@code taskExecutor}），都受同样三道闸管：开关关掉、
 * 律师自己关过（{@link Project#getVersionOptOut()}）、工作区太大（护栏）。
 * 手动开启（{@code POST /version/enable}）不受护栏管——那是律师自己的决定。
 *
 * <p>为什么监听事件而不是被 {@link WorkSessionService} 直接调用：本服务要调
 * {@code enableVersionRecording}，双向注入就是一圈构造器循环依赖。
 */
@Service
public class VersionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(VersionLifecycleService.class);

    private final WorkSessionService sessionService;
    private final ProjectRepoService repoService;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectRemoteRepository remoteRepository;
    private final Executor executor;

    /** 总开关。关掉之后两个触发点都不动作，律师仍可以手动开。 */
    @Value("${version.auto-enable:true}")
    private boolean autoEnable = true;

    /**
     * 大文件夹护栏：IDE 化本地文件夹项目指向的可能是几十 G 的目录，把它整个收进
     * 版本库既慢又不是律师要的。超过任一阈值就不自动开（律师仍可以手动开）。
     */
    @Value("${version.auto-enable-max-bytes:2147483648}")
    private long maxBytes = 2L * 1024 * 1024 * 1024;

    @Value("${version.auto-enable-max-files:20000}")
    private int maxFiles = 20000;

    /**
     * 被护栏拒过的项目：本进程内不再反复遍历几十 G 的目录去得到同一个结论。
     * 进程重启后会重估一次——文件夹可能已经不同了，重估是对的。
     */
    private final Set<Long> refusedTooLarge = ConcurrentHashMap.newKeySet();

    /** 正在开启中的项目：一次文件夹对账会连发几千个变更信号，去重掉后面全部。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public VersionLifecycleService(WorkSessionService sessionService,
                                   ProjectRepoService repoService,
                                   ProjectRepository projectRepository,
                                   UserRepository userRepository,
                                   ProjectRemoteRepository remoteRepository,
                                   @Qualifier("taskExecutor") Executor executor) {
        this.sessionService = sessionService;
        this.repoService = repoService;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.remoteRepository = remoteRepository;
        this.executor = executor;
    }

    // ---- 测试钩子（包内可见，口径同 ProjectRepoService.setMaxTrackedFileSizeBytesForTest）----
    void setAutoEnableForTest(boolean enabled) { this.autoEnable = enabled; }
    void setGuardrailForTest(long bytes, int files) { this.maxBytes = bytes; this.maxFiles = files; }

    // ==================== 自动开启 ====================

    /**
     * 新建项目之后。{@code @TransactionalEventListener} 而不是普通监听：
     * {@code createProject} 带事务，事务提交前项目行对别的连接还不可见，
     * 异步线程这时候去解析项目根目录/采集文件树会读到半截状态。
     * {@code fallbackExecution} 保证没有事务时（测试、别的调用路径）照样触发。
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onProjectCreated(ProjectService.ProjectCreatedEvent event) {
        request(event.projectId(), event.userId(), null);
    }

    /** 存量项目的第一个变更信号。 */
    @TransactionalEventListener(fallbackExecution = true)
    public void onAutoEnableRequest(WorkSessionService.AutoEnableRequest event) {
        request(event.projectId(), event.userId(), event.userName());
    }

    /**
     * 派发一次自动开启。这里只做两次内存集合判断（一次文件夹对账会连发几千个信号，
     * 而 taskExecutor 是有界队列 + AbortPolicy，每个信号都提交一个任务会把它打爆），
     * 真正的判定与开启在 {@link #autoEnableNow} 里、跑在异步线程上。
     */
    private void request(long projectId, Long userId, String userName) {
        if (!autoEnable) return;
        if (refusedTooLarge.contains(projectId)) return;
        if (!inFlight.add(projectId)) return;
        try {
            executor.execute(() -> {
                try {
                    autoEnableNow(projectId, userId, userName);
                } finally {
                    inFlight.remove(projectId);
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(projectId);
            log.warn("自动开启版本记录派发失败（已忽略）: project={}", projectId, e);
        }
    }

    /**
     * 真正的自动开启：三道闸 → 开。任何异常都吞掉——版本记录是保险，
     * 开不起来也绝不能影响律师手上的事；下一个变更信号会再试一次。
     * 包内可见，供测试直接调（不必绕异步）。
     */
    void autoEnableNow(long projectId, Long userId, String userName) {
        try {
            if (repoService.isInitialized(projectId)) return;
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null) return;          // 事务还没提交/项目已删：下一个信号再说
            if (Boolean.TRUE.equals(project.getVersionOptOut())) return;
            if (!withinGuardrail(projectId)) return;
            sessionService.enableVersionRecording(projectId,
                    authorName(userId, userName, project), authorEmail(userId));
            log.info("已自动开启版本记录: project={}", projectId);
        } catch (Exception e) {
            log.warn("自动开启版本记录失败（已忽略）: project={}", projectId, e);
        }
    }

    /**
     * 工作区体积护栏。超过任一阈值即拒，并记进 {@link #refusedTooLarge} —— 本进程不再
     * 反复估算。遍历早停：撞到阈值立刻 TERMINATE，不会把几十 G 的目录整个走完。
     * 跳过 {@code .awd/}（我们自己写的清单，不算律师的材料）。
     */
    private boolean withinGuardrail(long projectId) {
        Path root;
        try {
            root = repoService.workTree(projectId);
        } catch (Exception e) {
            log.warn("估算工作区体积前解析项目目录失败: project={}", projectId, e);
            return false;
        }
        if (!Files.isDirectory(root)) return true;   // 还没有目录 = 空项目，随便开
        Estimate est = estimate(root, maxBytes, maxFiles);
        if (!est.exceeded()) return true;
        refusedTooLarge.add(projectId);
        log.info("项目文件夹过大，不自动开启版本记录（律师仍可在版本记录面板手动开启）: "
                + "project={}, 已扫描={}字节/{}个文件, 上限={}字节/{}个文件",
                projectId, est.bytes(), est.files(), maxBytes, maxFiles);
        return false;
    }

    record Estimate(long bytes, int files, boolean exceeded) {}

    /** 早停式体积估算。包内可见供测试直接验证「超阈值就不再往下走」。 */
    static Estimate estimate(Path root, long maxBytes, int maxFiles) {
        long[] bytes = {0L};
        int[] files = {0};
        boolean[] exceeded = {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return ".awd".equals(String.valueOf(dir.getFileName()))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    files[0]++;
                    bytes[0] += attrs.size();
                    if (bytes[0] > maxBytes || files[0] > maxFiles) {
                        exceeded[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;   // 读不动的条目跳过，不因为它整体放弃
                }
            });
        } catch (IOException e) {
            // 遍历本身失败（权限/盘拔了）：当作"估不出来"，按超限处理，不冒然开启
            log.warn("估算工作区体积失败: root={}", root, e);
            return new Estimate(bytes[0], files[0], true);
        }
        return new Estimate(bytes[0], files[0], exceeded[0]);
    }

    private String authorName(Long userId, String userName, Project project) {
        if (userName != null && !userName.isBlank()) return userName;
        Long id = userId != null ? userId : project.getUserId();
        if (id == null) return LangText.of("用户", "User");
        return userRepository.findById(id)
                .map(u -> u.getUsername())
                .filter(n -> n != null && !n.isBlank())
                .orElse(LangText.of("用户", "User"));
    }

    /** 口径与 VersionController.email 一致。 */
    private String authorEmail(Long userId) {
        return "user-" + (userId == null ? "auto" : userId) + "@aiworkdeck.local";
    }

    // ==================== 关闭与 opt-out ====================

    /**
     * 关闭版本记录并删除全部历史。
     *
     * <p>先落 opt-out 再动手：中途失败要把标记撤回来——「关闭没成功」和
     * 「以后别再自动开」不能凑成一半一半的状态。
     *
     * <p>已经放进团队案件库的案卷本期直接拒绝：那份历史同事那边还在用，
     * 本地单方面删掉之后的云端语义（还能不能取回最新稿、成员那边看到什么）
     * 没有想清楚之前不做。
     */
    public void disableVersionRecording(long projectId) {
        if (remoteRepository.findByProjectId(projectId).isPresent()) {
            throw VersionException.userFacing(LangText.of(
                    "这份案卷已放进团队案件库，本机不能单独关闭版本记录",
                    "This case file is in the Team Case Library, so version history cannot be turned off on this device alone"));
        }
        setOptOut(projectId, true);
        try {
            sessionService.disableVersionRecording(projectId);
        } catch (RuntimeException e) {
            setOptOut(projectId, false);
            throw e;
        }
        refusedTooLarge.remove(projectId);
    }

    /** 手动开启时清掉 opt-out：律师自己又要了，就不能再拿旧标记拦住后续的自动开启。 */
    public void clearOptOut(long projectId) {
        setOptOut(projectId, false);
    }

    private void setOptOut(long projectId, boolean value) {
        projectRepository.findById(projectId).ifPresent(p -> {
            if (Boolean.valueOf(value).equals(p.getVersionOptOut())) return;
            p.setVersionOptOut(value);
            projectRepository.save(p);
        });
    }
}
