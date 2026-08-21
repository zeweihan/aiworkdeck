package com.checkba.service.ai.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计条目：「TOCTOU race on _request_ready marker can replay a stale tool request or
 * cross-deliver results」。Java 处理完一条 Python 侧的工具调用请求后，此前会在仅 100ms 之后
 * 立刻又检查一次 requestReadyPath——如果 Python 侧还没来得及清理这个标记（两边各自独立
 * 按 100ms 轮询，没有加锁），Java 会把同一条请求当成新请求重新处理一遍。
 *
 * pollForToolRequest 是纯文件系统状态机（static 方法，不需要真的起 Docker 容器），
 * 直接用真实临时目录里的文件驱动，验证"标记还在就不重复处理，标记消失过一次才认下一次是新请求"。
 */
@DisplayName("PythonTools：_request_ready 标记的 TOCTOU")
class PythonToolsPollingTest {

    @TempDir
    Path tempDir;

    private Path requestReadyPath;
    private AtomicInteger handlerCalls;

    @BeforeEach
    void setUp() {
        requestReadyPath = tempDir.resolve("_request_ready");
        handlerCalls = new AtomicInteger(0);
    }

    private PythonTools.ThrowingRunnable countingHandler() {
        return handlerCalls::incrementAndGet;
    }

    @Test
    @DisplayName("没有标记文件时不处理，也不进入 awaitingCleanup 状态")
    void noMarkerMeansNoRequest() throws Exception {
        boolean awaiting = PythonTools.pollForToolRequest(requestReadyPath, false, countingHandler());

        assertFalse(awaiting);
        assertEquals(0, handlerCalls.get());
    }

    @Test
    @DisplayName("标记首次出现：处理一次并进入 awaitingCleanup 状态")
    void firstMarkerIsHandledOnce() throws Exception {
        Files.writeString(requestReadyPath, "1");

        boolean awaiting = PythonTools.pollForToolRequest(requestReadyPath, false, countingHandler());

        assertTrue(awaiting, "处理完一条请求后应进入等待 Python 清理的状态");
        assertEquals(1, handlerCalls.get());
    }

    @Test
    @DisplayName("修复：Python 还没清理标记时，同一个标记不能被重复当成新请求处理")
    void staleMarkerIsNotReprocessedBeforeCleanup() throws Exception {
        Files.writeString(requestReadyPath, "1");

        // 第一次：真实处理这条请求
        boolean awaiting = PythonTools.pollForToolRequest(requestReadyPath, false, countingHandler());
        assertEquals(1, handlerCalls.get());
        assertTrue(awaiting);

        // 模拟审计条目描述的窗口：Python 还没来得及 os.remove(_request_ready)，
        // Java 下一轮（100ms 后）又检查了一次——标记文件本身没有任何变化。
        awaiting = PythonTools.pollForToolRequest(requestReadyPath, awaiting, countingHandler());

        assertEquals(1, handlerCalls.get(), "标记没被 Python 清理过，不能被当成新请求重复处理");
        assertTrue(awaiting, "仍应停留在等待清理的状态");
    }

    @Test
    @DisplayName("Python 清理标记后，下一次同一标记消失的检查不触发处理，之后新标记才会被当成新请求")
    void newMarkerAfterCleanupIsHandledAsNewRequest() throws Exception {
        Files.writeString(requestReadyPath, "1");
        boolean awaiting = PythonTools.pollForToolRequest(requestReadyPath, false, countingHandler());
        assertEquals(1, handlerCalls.get());

        // Python 清理了标记（对应 PYTHON_API_BRIDGE 里的 os.remove）
        Files.delete(requestReadyPath);
        awaiting = PythonTools.pollForToolRequest(requestReadyPath, awaiting, countingHandler());
        assertFalse(awaiting, "标记已消失，翻篇");
        assertEquals(1, handlerCalls.get(), "清理动作本身不应该触发处理");

        // Python 发出下一条真正的新请求
        Files.writeString(requestReadyPath, "1");
        awaiting = PythonTools.pollForToolRequest(requestReadyPath, awaiting, countingHandler());

        assertTrue(awaiting);
        assertEquals(2, handlerCalls.get(), "清理过一次之后的新标记，必须被当成新请求处理");
    }
}
