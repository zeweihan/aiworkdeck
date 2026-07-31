package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.repository.ProjectRepository;
import io.methvin.watcher.DirectoryWatcher;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 本地文件夹项目的文件系统监听：Finder 里的增删改 → 防抖 → LocalProjectService.reconcileProject。
 *
 * 设计要点：
 * - 只监听 localRoot 非空的项目；托管项目的一切变更都经由应用自身，无需监听。
 * - 对账幂等且**只写数据库、绝不写磁盘**——应用自己的写盘（自动保存/版本退回/切稿）
 *   触发的事件走到对账也只是无事发生，天然没有自写回环。
 * - 版本记录信号不在这里发：createFolder/createOrUpdateFile/delete 服务方法内部自带
 *   signalChange，「Finder 里的动作」因此同样进版本记录。
 * - watcher 起不来（平台不支持/权限被拒）只降级不报错：外部改动退化为
 *   重新打开项目时的全量重扫（openLocalFolder 幂等导入）。
 * - 事件回调与对账都在单线程执行器上跑：同一时刻至多一个对账在进行，天然串行。
 */
@Service
public class LocalRootWatchService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalRootWatchService.class);

    /** 防抖窗口：编辑器保存/Finder 拷贝往往是一串事件，合并成一次对账。 */
    static final long DEBOUNCE_MILLIS = 800;

    private final ProjectRepository projectRepository;
    private final LocalProjectService localProjectService;

    private final Map<Long, DirectoryWatcher> watchers = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    /** 对账/防抖专用单线程：同一时刻至多一个对账，天然串行。 */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-root-reconcile");
        t.setDaemon(true);
        return t;
    });
    /** 监听循环线程池：watchAsync 的监听循环会**永久占用**一条线程，绝不能和对账共用
     *  单线程执行器（那样防抖任务永远排不上队——首版实测踩过）。 */
    private final java.util.concurrent.ExecutorService watchExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "local-root-watch");
        t.setDaemon(true);
        return t;
    });

    public LocalRootWatchService(ProjectRepository projectRepository,
                                 LocalProjectService localProjectService) {
        this.projectRepository = projectRepository;
        this.localProjectService = localProjectService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        for (Project p : projectRepository.findByLocalRootIsNotNull()) {
            if (StringUtils.hasText(p.getLocalRoot())) {
                ensureWatch(p.getId(), p.getLocalRoot());
            }
        }
    }

    @EventListener
    public void onLocalProjectOpened(LocalProjectService.LocalProjectOpened event) {
        ensureWatch(event.projectId(), event.localRoot());
    }

    /** 幂等：已在监听则无事发生。失败只降级（见类注释）。 */
    public void ensureWatch(long projectId, String localRoot) {
        if (watchers.containsKey(projectId)) return;
        Path root = Paths.get(localRoot);
        if (!Files.isDirectory(root)) {
            log.warn("不监听不可达的项目文件夹: project={}, root={}", projectId, root);
            return;
        }
        try {
            DirectoryWatcher watcher = DirectoryWatcher.builder()
                    .path(root)
                    .listener(event -> scheduleReconcile(projectId))
                    .build();
            DirectoryWatcher prev = watchers.putIfAbsent(projectId, watcher);
            if (prev != null) {
                watcher.close();
                return;
            }
            watcher.watchAsync(watchExecutor);
            log.info("已监听项目文件夹: project={}, root={}", projectId, root);
        } catch (Exception e) {
            log.warn("启动文件夹监听失败（降级为重开项目时重扫）: project={}, root={} ({})",
                    projectId, root, e.getMessage());
        }
    }

    public void stopWatch(long projectId) {
        DirectoryWatcher w = watchers.remove(projectId);
        if (w != null) {
            try { w.close(); } catch (Exception ignored) { }
        }
        ScheduledFuture<?> f = pending.remove(projectId);
        if (f != null) f.cancel(false);
    }

    private void scheduleReconcile(long projectId) {
        ScheduledFuture<?> prev = pending.get(projectId);
        if (prev != null) prev.cancel(false);
        pending.put(projectId, executor.schedule(() -> {
            pending.remove(projectId);
            try {
                LocalProjectService.ReconcileResult r = localProjectService.reconcileProject(projectId);
                if (r.rootMissing()) {
                    // 文件夹被移走/外置盘拔出：停监听，等下次打开项目时重新建立
                    stopWatch(projectId);
                }
            } catch (Exception e) {
                log.warn("对账失败: project={} ({})", projectId, e.getMessage());
            }
        }, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS));
    }

    @PreDestroy
    public void shutdown() {
        for (Long id : watchers.keySet()) {
            stopWatch(id);
        }
        executor.shutdownNow();
        watchExecutor.shutdownNow();
    }
}
