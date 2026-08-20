package com.checkba.service.optimizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * 子进程超时必须真的会触发。
 *
 * <p>病灶：原实现是 <b>先</b> {@code read(p.getInputStream())} 读到 EOF、<b>再</b>
 * {@code p.waitFor(timeout)}。而 {@code readAllBytes} 只有在进程退出（或自己关掉 stdout）
 * 时才返回——于是「卡住但没退出」的进程会把调用线程永久阻塞在 read 里，
 * {@code waitFor} 的超时根本没机会执行。
 *
 * <p>而这正是超时存在的唯一理由：进程正常退出时 waitFor 本来就立刻返回，
 * 需要超时兜底的恰恰是「不退出」这一种。优化者跑的是编码 Agent CLI，
 * 卡住就意味着那条线程再也回不来。
 */
class ProcessRunnerTimeoutTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final ProcessRunner runner = new ProcessRunner.Default();

    @Test
    @DisplayName("进程卡住不退出：必须在超时后返回，而不是永久阻塞")
    void hungProcessHitsTheTimeout() {
        assumeFalse(WINDOWS, "用例用 sh 造一个不退出的进程");

        long start = System.currentTimeMillis();
        // sleep 不会关掉继承来的 stdout，管道一直开着——这正是原实现卡死的形状
        ProcessRunner.Result r = runner.run(List.of("sh", "-c", "sleep 60"), null, 2);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(r.ok(), "超时必须算失败");
        assertEquals(-1, r.exitCode());
        assertTrue(r.stderr().contains("超时"), "要说明是超时终止的，实际是：" + r.stderr());
        assertTrue(elapsed < 30_000,
                "超时没生效——调用线程被 read 卡住了（原病灶）。实际耗时 " + elapsed + "ms");
    }

    @Test
    @DisplayName("进程有输出但迟迟不退出：同样要按超时收场，不许被输出流吊住")
    void processThatPrintsThenHangsStillTimesOut() {
        assumeFalse(WINDOWS, "用例用 sh 造一个不退出的进程");

        long start = System.currentTimeMillis();
        ProcessRunner.Result r = runner.run(
                List.of("sh", "-c", "echo hello; sleep 60"), null, 2);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(r.ok());
        assertTrue(elapsed < 30_000, "实际耗时 " + elapsed + "ms");
    }

    @Test
    @DisplayName("正常退出的进程：输出完整、退出码正确（既有行为不变）")
    void normalProcessStillReturnsItsOutput() {
        assumeFalse(WINDOWS, "用例用 sh");

        ProcessRunner.Result r = runner.run(List.of("sh", "-c", "echo 合同已生成"), null, 10);

        assertTrue(r.ok(), "实际 exit=" + r.exitCode() + " stderr=" + r.stderr());
        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("合同已生成"), "输出不能丢，实际是：" + r.stdout());
    }

    @Test
    @DisplayName("非零退出码原样带回（失败原因要看得见）")
    void nonZeroExitIsReported() {
        assumeFalse(WINDOWS, "用例用 sh");

        ProcessRunner.Result r = runner.run(List.of("sh", "-c", "echo boom >&2; exit 3"), null, 10);

        assertFalse(r.ok());
        assertEquals(3, r.exitCode());
        // redirectErrorStream(true)：stderr 并进 stdout
        assertTrue(r.stdout().contains("boom"), "实际是：" + r.stdout());
    }
}
