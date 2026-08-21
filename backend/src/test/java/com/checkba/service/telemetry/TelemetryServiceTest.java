package com.checkba.service.telemetry;

import com.checkba.model.entity.TelemetryEvent;
import com.checkba.repository.TelemetryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 锁定隐私红线：白名单外字段（文件名/路径/消息文本等）必须被剔除，
 * 未知事件整条拒绝，采集层任何异常不外溢到业务调用方。
 */
class TelemetryServiceTest {

    @TempDir
    Path dir;

    TelemetryEventRepository repo;
    TelemetryService svc;

    @BeforeEach
    void setup() {
        repo = mock(TelemetryEventRepository.class);
        svc = new TelemetryService(repo, new InstallIdentityService(dir.toString()), "0.0.0-test");
    }

    @Test
    void legalEventIsPersistedWithWhitelistedAttrsOnly() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("toolName", "doc_replace_text");
        attrs.put("success", true);
        attrs.put("durationMs", 120);
        // 隐私红线字段：必须被剔除
        attrs.put("fileName", "某某公司股权转让协议.docx");
        attrs.put("message", "帮我审查这份合同");
        attrs.put("path", "/Users/x/案件/秘密.docx");

        svc.record("ai.tool", attrs);
        svc.flush();

        ArgumentCaptor<TelemetryEvent> cap = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(repo).save(cap.capture());
        String json = cap.getValue().getAttrs();
        assertTrue(json.contains("doc_replace_text"));
        assertFalse(json.contains("股权转让"));
        assertFalse(json.contains("审查"));
        assertFalse(json.contains("/Users"));
        assertEquals("ai.tool", cap.getValue().getEventName());
        assertEquals("0.0.0-test", cap.getValue().getAppVersion());
        assertTrue(svc.droppedCount() >= 3);
    }

    @Test
    void unknownEventNameIsRejectedEntirely() throws Exception {
        svc.record("made.up.event", Map.of("x", 1));
        svc.flush();
        verify(repo, never()).save(any());
        assertEquals(1, svc.droppedCount());
    }

    @Test
    void overlongStringValueIsDropped() throws Exception {
        svc.record("ai.tool", Map.of("toolName", "x".repeat(65)));
        svc.flush();
        ArgumentCaptor<TelemetryEvent> cap = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(repo).save(cap.capture());
        assertNull(cap.getValue().getAttrs());
    }

    @Test
    void convKeyIsDerivedAndRawIdNeverStored() throws Exception {
        String conv = "conv-1754460000000";
        svc.recordConv("ai.turn", conv, Map.of("outcome", "FINISHED"));
        svc.flush();
        ArgumentCaptor<TelemetryEvent> cap = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(repo).save(cap.capture());
        String key = cap.getValue().getConvKey();
        assertNotNull(key);
        assertEquals(16, key.length());
        assertFalse(key.contains("1754460000000"));
    }

    @Test
    void repositoryFailureNeverPropagates() throws Exception {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> {
            svc.record("file.changed", null);
            svc.flush();
        });
    }

    /**
     * 病灶：类注释写的是"独立于业务线程池……有界队列，满了直接丢"，但
     * {@code Executors.newSingleThreadExecutor(...)} 背后是无界 {@code LinkedBlockingQueue}——
     * 名不副实。DB 持续卡顿时，排队的 Runnable（每个都攥着一个 Map + Instant）会无界堆积，
     * 把这条"过载安全阀"变成了一条会把后端堆内存吃满的隐患，而 droppedCount() 因为
     * "满了丢弃"这条路径从没真正触发过，还会低报积压。
     *
     * <p>验证方式：让唯一的消费线程卡住（repo.save 阻塞），持续提交超过队列容量的事件，
     * 断言超出部分被记成丢弃而不是无限排队——不直接读内部队列大小（那是实现细节），
     * 用 droppedCount() 这个已有的自监控指标即可稳定判定。
     */
    @Test
    void queueIsBoundedAndDropsOverflowInsteadOfGrowingWithoutLimit() throws Exception {
        java.util.concurrent.CountDownLatch releaseConsumer = new java.util.concurrent.CountDownLatch(1);
        when(repo.save(any())).thenAnswer(inv -> {
            releaseConsumer.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return inv.getArgument(0);
        });

        try {
            // 第一条被唯一的消费线程立刻取走并卡住；之后的都得排队。提交数刻意比队列容量多
            // 一截，超出的那部分必须被丢弃计数，而不是内存无限堆积。
            // 断言就放在这个循环之后：拒绝策略在 execute() 调用当下、调用方线程上同步执行，
            // 不需要等消费线程排空——此时它还卡着，不能用 flush()（那会去抢同一条队列，
            // 队列满的话 flush 自己的探测任务也会被拒绝而永远等不到结果，白等超时）。
            int overflow = 50;
            for (int i = 0; i < TelemetryService.QUEUE_CAPACITY + overflow; i++) {
                svc.record("ai.tool", Map.of("toolName", "doc_replace_text", "success", true));
            }

            assertTrue(svc.droppedCount() >= overflow - 1,
                    "队列满了之后继续提交应该被丢弃计数，实际 droppedCount()=" + svc.droppedCount());
        } finally {
            releaseConsumer.countDown(); // 放行消费线程，不留卡住的后台线程
        }
    }
}
