package com.checkba.exception;

/**
 * 文件缓存区免费额度已满（PR-C）。
 *
 * <p>语义是**拒绝新增**，不是清理：抛出时缓存区里已有的文件一个都没动。
 * 由 {@code GlobalExceptionHandler} 转成 code=4003 + feature=stage.unlimited，
 * 前端据此显示解锁引导而非通用报错。</p>
 */
public class StageQuotaExceededException extends RuntimeException {

    private final long fileCount;
    private final long totalBytes;
    private final int maxFiles;
    private final long maxBytes;

    public StageQuotaExceededException(String message, long fileCount, long totalBytes, int maxFiles, long maxBytes) {
        super(message);
        this.fileCount = fileCount;
        this.totalBytes = totalBytes;
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
    }

    public long getFileCount() { return fileCount; }

    public long getTotalBytes() { return totalBytes; }

    public int getMaxFiles() { return maxFiles; }

    public long getMaxBytes() { return maxBytes; }
}
