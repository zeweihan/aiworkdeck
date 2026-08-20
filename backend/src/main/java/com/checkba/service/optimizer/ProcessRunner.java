package com.checkba.service.optimizer;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 子进程执行器：git / gh / 编码 Agent 都经这里出去。
 *
 * <p>抽成接口是为了让 {@link OptimizerCodeFixRunner} 可以被测试**完整跑一遍**——
 * 那段逻辑的价值恰恰在于「哪些命令、按什么顺序、什么时候拒绝执行」，
 * 用真 git 跑不出「分支等于 master 时必须拒绝推送」这类断言。
 */
public interface ProcessRunner {

    record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }

        public String tail(int max) {
            String s = (stdout + (stderr.isEmpty() ? "" : "\n" + stderr)).trim();
            return s.length() <= max ? s : s.substring(s.length() - max);
        }
    }

    Result run(List<String> command, File workingDir, int timeoutSeconds);

    @Component
    class Default implements ProcessRunner {
        @Override
        public Result run(List<String> command, File workingDir, int timeoutSeconds) {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                if (workingDir != null) pb.directory(workingDir);
                // 交互式 Agent CLI 在没有 TTY 时可能等输入；显式给个空 stdin 让它立刻收到 EOF
                pb.redirectInput(ProcessBuilder.Redirect.from(new File(nullDevice())));
                // 两条流合一：只读 stdout 再 waitFor 的写法，会在 stderr 缓冲区写满时死锁
                pb.redirectErrorStream(true);
                Process p = pb.start();
                // 输出必须在**另一条线程**上读。原来是先 read(...) 读到 EOF、再 waitFor(timeout)：
                // readAllBytes 只有在进程退出（或自己关掉 stdout）时才返回，于是「卡住但没退出」
                // 的进程会把调用线程永久阻塞在 read 里，waitFor 的超时根本没机会执行——
                // 而这正是超时存在的唯一理由（进程正常退出时 waitFor 本来就立刻返回）。
                java.util.concurrent.CompletableFuture<String> reader =
                        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                            try {
                                return read(p.getInputStream());
                            } catch (Exception e) {
                                return "";
                            }
                        }, OUTPUT_READERS);
                boolean done = p.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    // 强杀后管道关闭，reader 随即返回已读到的部分输出；再等一小会儿就够
                    return new Result(-1, partial(reader), "[超时 " + timeoutSeconds + "s，已终止]");
                }
                return new Result(p.exitValue(), partial(reader), "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(-1, "", "被中断: " + e);
            } catch (Exception e) {
                return new Result(-1, "", String.valueOf(e));
            }
        }

        /** 读子进程输出的守护线程池：绝不能是非守护线程，否则卡住的读会拖住 JVM 退出。 */
        private static final java.util.concurrent.ExecutorService OUTPUT_READERS =
                java.util.concurrent.Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "proc-output-reader");
                    t.setDaemon(true);
                    return t;
                });

        /** 取已读到的输出；读线程若还卡着就返回空串，绝不把调用方再拖进去。 */
        private static String partial(java.util.concurrent.CompletableFuture<String> reader) {
            try {
                return reader.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                return "";
            }
        }

        private static String nullDevice() {
            return System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null";
        }

        private static String read(InputStream in) throws Exception {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
