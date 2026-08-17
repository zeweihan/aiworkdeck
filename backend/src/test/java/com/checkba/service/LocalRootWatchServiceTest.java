package com.checkba.service;

import io.methvin.watcher.hashing.FileHash;
import io.methvin.watcher.hashing.FileHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 文件夹监听的性能契约：**建立监听时不许读文件内容**。
 *
 * 背景见 {@link LocalRootWatchService#WATCH_FILE_HASHER}——DirectoryWatcher 默认的内容哈希
 * 会在 watchAsync 时把整棵树逐字节读一遍，在 iCloud「优化 Mac 储存空间」的文件夹上
 * 等价于「打开项目 = 把整个文件夹拉回本地」。
 *
 * 测试用命名管道（FIFO）扮演「未下载的 iCloud 文件」：stat 秒回、read 会一直挂着，
 * 正是 dataless 占位文件的行为放大版。换回内容哈希，本用例会直接超时。
 */
class LocalRootWatchServiceTest {

    @Test
    void watchFileHasherReadsMetadataOnlyAndNeverBlocksOnFileContent(@TempDir Path dir) throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().startsWith("win"), "命名管道是 POSIX 特性");
        Path evicted = dir.resolve("evicted.docx");
        assumeTrue(new ProcessBuilder("mkfifo", evicted.toString()).start().waitFor() == 0,
                "mkfifo 不可用，跳过");

        FileHash hash = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> LocalRootWatchService.WATCH_FILE_HASHER.hash(evicted),
                "指纹算法读了文件内容——在 iCloud 文件夹上这会把每个文件都下载一遍");

        // DirectoryWatcher 的 pathHashes 是 ConcurrentSkipListMap，塞 null 会 NPE 掀掉监听线程
        assertNotNull(hash, "指纹不能为 null");
        assertNotSame(FileHasher.DEFAULT_FILE_HASHER, LocalRootWatchService.WATCH_FILE_HASHER,
                "默认哈希是「完整读一遍文件」，不能用在用户自己的文件夹上");
    }

    /**
     * 监听状态必须是可观测的：挂不上就说挂不上，别一边失败一边打「已监听」。
     *
     * 以前 ensureWatch 无返回值、也不接 watchAsync 的 future，注册失败（名额耗尽、权限被拒、
     * 目录刚好被移走）和事件循环中途死亡全都静默——用户在 Finder 里的改动从此不再同步，
     * 日志里却看不出任何异常。测试锁住新契约：ensureWatch 的返回值与 isWatching 都可信。
     */
    @Test
    void ensureWatchReportsWhetherItActuallyWatches(@TempDir Path dir) {
        LocalRootWatchService svc = new LocalRootWatchService(
                mock(com.checkba.repository.ProjectRepository.class), mock(LocalProjectService.class));
        try {
            assertFalse(svc.ensureWatch(1L, dir.resolve("并不存在的目录").toString()),
                    "目录不可达时不能自称在监听");
            assertFalse(svc.isWatching(1L));

            assertTrue(svc.ensureWatch(2L, dir.toString()), "正常目录应挂载成功");
            assertTrue(svc.isWatching(2L));
            assertTrue(svc.ensureWatch(2L, dir.toString()), "幂等：重复挂载仍报在监听");

            svc.stopWatch(2L);
            assertFalse(svc.isWatching(2L), "停掉之后不能还报在监听");
        } finally {
            svc.shutdown();
        }
    }
}
