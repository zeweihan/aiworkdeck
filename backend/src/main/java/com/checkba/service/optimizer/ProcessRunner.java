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
                String out = read(p.getInputStream());
                boolean done = p.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    return new Result(-1, out, "[超时 " + timeoutSeconds + "s，已终止]");
                }
                return new Result(p.exitValue(), out, "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(-1, "", "被中断: " + e);
            } catch (Exception e) {
                return new Result(-1, "", String.valueOf(e));
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
