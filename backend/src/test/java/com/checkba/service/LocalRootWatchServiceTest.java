package com.checkba.service;

import io.methvin.watcher.hashing.FileHash;
import io.methvin.watcher.hashing.FileHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
}
