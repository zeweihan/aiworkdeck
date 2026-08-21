package com.checkba.service.ai.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 修复：run_python 的 stdout/stderr 排空线程原来把子进程输出无上限地塞进
 * JVM 堆里的 StringBuilder（AI 脚本 print 一份大文档，或死循环脚本在 120 秒
 * 超时前疯狂打印，都能把堆撑爆，殃及同一后端 JVM 上不相干的请求）。
 * pumpStream 必须边读边丢弃超限部分，而不是先攒满再判。
 */
class PythonToolsOutputCapTest {

    private static PythonTools tools() {
        return new PythonTools(null, null, null, null);
    }

    /** 构造一份远超上限的多行输出（约 20MB），不依赖真实子进程/Docker。 */
    private static String bigMultilineText(int lineCount, int lineLength) {
        String oneLine = "x".repeat(lineLength);
        StringBuilder raw = new StringBuilder(lineCount * (lineLength + 1));
        for (int i = 0; i < lineCount; i++) {
            raw.append(oneLine).append('\n');
        }
        return raw.toString();
    }

    @Test
    @DisplayName("修复：超限输出必须被截断，不能无上限塞进 StringBuilder")
    void pumpStreamCapsUnboundedOutput() throws InterruptedException {
        String raw = bigMultilineText(20_000, 1000); // 约 20,000,000 字符，远超任何合理上限
        InputStream in = new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8));

        StringBuilder sink = new StringBuilder();
        Thread t = tools().pumpStream(in, sink);
        t.join(15_000);

        assertFalse(t.isAlive(), "排空线程应在有限流读完后正常结束");
        synchronized (sink) {
            assertTrue(sink.length() < raw.length(),
                    "超限内容必须被截断，实际长度 " + sink.length() + "，原始长度 " + raw.length());
            assertTrue(sink.length() <= PythonTools.MAX_OUTPUT_CHARS + 2000,
                    "截断后的长度应贴着上限，不能明显超出，实际长度 " + sink.length());
        }
    }

    @Test
    @DisplayName("未超限的正常输出原样保留，不受截断逻辑影响")
    void pumpStreamKeepsNormalOutputIntact() throws InterruptedException {
        String line = "hello from python";
        InputStream in = new ByteArrayInputStream((line + "\n").getBytes(StandardCharsets.UTF_8));
        StringBuilder sink = new StringBuilder();
        Thread t = tools().pumpStream(in, sink);
        t.join(5000);
        synchronized (sink) {
            assertTrue(sink.toString().contains(line));
        }
    }
}
