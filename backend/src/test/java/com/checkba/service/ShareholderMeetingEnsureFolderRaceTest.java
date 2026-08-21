package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ShareholderMeetingCheck;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ShareholderMeetingCheckRepository;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * 病灶：ensureFolder（经 ensureWorkpaperFolders 被 start()/fetchFromCninfo() 调用）是
 * 「查不到就建」，查和建之间没有锁，两次并发调用（双击「开始核查」、前端网络重试）都能
 * 查到「不存在」再各自建一个，产生同名重复文件夹。
 *
 * <p>与被判定"锁是假修"的另外两条 check-then-act 竞态（缓存区额度、DdService.ensureFolder）
 * 不同：这条链路里 ensureWorkpaperFolders/ensureFolder/start() 自己都不是 @Transactional，
 * 真正的提交只发生在 projectFileService.createFolder() 这个独立代理调用内部、同步完成——
 * 所以把锁包在 ensureWorkpaperFolders 外层能如实盖住这次提交，不是「锁在提交前就放了」
 * 那种假修。
 */
@ExtendWith(MockitoExtension.class)
class ShareholderMeetingEnsureFolderRaceTest {

    @Mock private ShareholderMeetingCheckRepository checkRepository;
    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private ProjectFileService projectFileService;
    @Mock private StorageServiceFactory storageServiceFactory;
    @Mock private CninfoAnnouncementService cninfoService;

    private ShareholderMeetingService svc;

    @BeforeEach
    void setUp() {
        svc = new ShareholderMeetingService(checkRepository, projectFileRepository,
                projectFileService, storageServiceFactory, cninfoService);
        // ensureFolder 现在经 self 转发到 REQUIRES_NEW 的 ensureFolderTx（同批另一条修复）。
        // 纯 Mockito 测试里没有 Spring 代理，把 self 指回自己：事务语义在这条用例里本来
        // 就测不到（它测的是互斥），但不接上 self 的话调用直接 NPE。
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "self", svc);
    }

    private static String key(Long parentId, String name) {
        return parentId + "/" + name;
    }

    @Test
    @DisplayName("同一 checkId 并发调用 ensureWorkpaperFolders 不产生重复的底稿夹根目录")
    void concurrentEnsureWorkpaperFoldersDoesNotDuplicateRootFolder() throws Exception {
        ShareholderMeetingCheck check = new ShareholderMeetingCheck();
        check.setId(1L);
        check.setProjectId(100L);
        check.setCompanyName("某公司");
        check.setMeetingName("2026年年度股东大会");

        Map<String, ProjectFile> store = new ConcurrentHashMap<>();
        AtomicLong idSeq = new AtomicLong(1);
        AtomicInteger rootCreateCount = new AtomicInteger(0);

        Thread[] threadARef = new Thread[1];
        CountDownLatch aPausedInCreate = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);

        when(projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(
                anyLong(), nullable(Long.class), anyString()))
                .thenAnswer(inv -> {
                    Long parentId = inv.getArgument(1);
                    String name = inv.getArgument(2);
                    return Optional.ofNullable(store.get(key(parentId, name)));
                });

        when(projectFileService.createFolder(anyLong(), nullable(Long.class), anyString(), anyLong()))
                .thenAnswer(inv -> {
                    Long parentId = inv.getArgument(1);
                    String name = inv.getArgument(2);
                    if (ShareholderMeetingService.WORKPAPER_ROOT.equals(name)) {
                        rootCreateCount.incrementAndGet();
                        // 只让线程 A 在"创建根目录"这一步暂停，模拟查到"不存在"之后、
                        // 真正落库完成之前的窗口——这正是 TOCTOU 的那个缝隙。
                        // 线程 B（如果没有锁）会在这段暂停期间闯进来，同样查到"不存在"、
                        // 同样调用 createFolder，于是 rootCreateCount 会变成 2。
                        if (Thread.currentThread() == threadARef[0]) {
                            aPausedInCreate.countDown();
                            assertTrue(releaseA.await(5, TimeUnit.SECONDS), "测试主线程应该及时放行 A");
                        }
                    }
                    ProjectFile f = new ProjectFile();
                    f.setId(idSeq.getAndIncrement());
                    f.setName(name);
                    f.setIsFolder(true);
                    store.put(key(parentId, name), f);
                    return f;
                });

        Thread a = new Thread(() -> {
            threadARef[0] = Thread.currentThread();
            svc.ensureWorkpaperFolders(check, 1L);
        });
        a.start();
        assertTrue(aPausedInCreate.await(5, TimeUnit.SECONDS), "线程 A 应该先进入根目录创建的临界窗口");

        // 此时线程 A 卡在"根目录已判定不存在、createFolder 尚未返回"的缝隙里。
        // 加了锁的话，线程 B 这次调用应该整个卡在方法入口，等 A 彻底做完才轮到自己；
        // 没加锁的话，B 会立刻查到"不存在"，也调一次 createFolder，制造重复根目录。
        Thread b = new Thread(() -> svc.ensureWorkpaperFolders(check, 1L));
        b.start();

        Thread.sleep(300); // 给 B 机会在无锁情况下抢跑；有锁的话这段时间 B 应该原地卡住
        releaseA.countDown();

        a.join(5000);
        b.join(5000);

        assertEquals(1, rootCreateCount.get(),
                "底稿夹根目录只应该被创建一次，重复创建说明 ensureWorkpaperFolders 没有互斥");
    }
}
