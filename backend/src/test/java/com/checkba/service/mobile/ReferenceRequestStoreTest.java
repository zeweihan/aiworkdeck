// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.mobile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桌面端参考读取的内存登记簿（dev-board#718）：取件只下发一次、只有属主能交回结果、
 * 过期请求由清扫以失败结果收尾（等待方拿到可转述的原因，而不是一直挂着）。
 */
class ReferenceRequestStoreTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Test
    void submitTakeCompleteRoundTrip() throws Exception {
        var store = new ReferenceRequestStore(now::get);
        var p = store.submit(7L, "dev1", "READ", "42", "合同/A.docx", null);

        var taken = store.take(7L, "dev1");
        assertThat(taken).hasSize(1).first().satisfies(m -> {
            assertThat(m.get("id")).isEqualTo(p.id());
            assertThat(m.get("kind")).isEqualTo("READ");
            assertThat(m.get("projectKey")).isEqualTo("42");
            assertThat(m.get("path")).isEqualTo("合同/A.docx");
            assertThat(m).doesNotContainKey("keyword");
        });
        assertThat(store.take(7L, "dev1")).isEmpty(); // 不重复下发

        assertThat(store.complete(8L, p.id(), Map.of("ok", true)))
                .isEqualTo(ReferenceRequestStore.Outcome.FORBIDDEN);
        assertThat(p.future()).isNotDone(); // 别人交的结果不算数

        assertThat(store.complete(7L, p.id(), Map.of("ok", true, "text", "t")))
                .isEqualTo(ReferenceRequestStore.Outcome.OK);
        assertThat(p.future().get(1, TimeUnit.SECONDS)).containsEntry("text", "t");

        assertThat(store.complete(7L, p.id(), Map.of("ok", true)))
                .isEqualTo(ReferenceRequestStore.Outcome.STALE);
    }

    @Test
    void takeIsScopedToUserAndDeviceAndKeepsSubmitOrder() {
        var store = new ReferenceRequestStore(now::get);
        var first = store.submit(7L, "dev1", "LIST", "42", null, "清单");
        store.submit(7L, "dev2", "LIST", "42", null, null);
        store.submit(8L, "dev1", "LIST", "42", null, null);
        now.addAndGet(1);
        var second = store.submit(7L, "dev1", "OPEN", "42", "a.txt", null);

        List<Map<String, Object>> taken = store.take(7L, "dev1");
        assertThat(taken).extracting(m -> m.get("id")).containsExactly(first.id(), second.id());
        assertThat(taken.get(0)).containsEntry("keyword", "清单").doesNotContainKey("path");
    }

    @Test
    void unknownIdIsStale() {
        var store = new ReferenceRequestStore(now::get);
        assertThat(store.complete(7L, "no-such-id", Map.of("ok", true)))
                .isEqualTo(ReferenceRequestStore.Outcome.STALE);
        assertThat(store.complete(7L, null, Map.of("ok", true)))
                .isEqualTo(ReferenceRequestStore.Outcome.STALE);
    }

    @Test
    void expiredRequestsCompleteWithTimeout() throws Exception {
        var store = new ReferenceRequestStore(now::get);
        var p = store.submit(7L, "dev1", "LIST", "42", null, null);
        now.addAndGet(60_001);
        store.sweep();

        Map<String, Object> r = p.future().get(1, TimeUnit.SECONDS);
        assertThat(r).containsEntry("ok", false);
        assertThat(String.valueOf(r.get("error"))).contains("60 秒内未响应");
        // 清扫之后既不再下发，也不再接受迟到的结果
        assertThat(store.take(7L, "dev1")).isEmpty();
        assertThat(store.complete(7L, p.id(), Map.of("ok", true)))
                .isEqualTo(ReferenceRequestStore.Outcome.STALE);
    }

    @Test
    void expiredButNotYetSweptIsNeitherHandedOutNorAccepted() throws Exception {
        var store = new ReferenceRequestStore(now::get);
        var p = store.submit(7L, "dev1", "READ", "42", "a.txt", null);
        now.addAndGet(60_001);

        assertThat(store.take(7L, "dev1")).isEmpty();
        assertThat(store.complete(7L, p.id(), Map.of("ok", true, "text", "t")))
                .isEqualTo(ReferenceRequestStore.Outcome.STALE);
        assertThat(p.future().get(1, TimeUnit.SECONDS)).containsEntry("ok", false);
    }

    @Test
    void sweepLeavesFreshRequestsAlone() {
        var store = new ReferenceRequestStore(now::get);
        var p = store.submit(7L, "dev1", "READ", "42", "a.txt", null);
        now.addAndGet(59_000);
        store.sweep();
        assertThat(p.future()).isNotDone();
        assertThat(store.take(7L, "dev1")).hasSize(1);
    }

    @Test
    void nullResultStillSettlesTheWaiter() throws Exception {
        var store = new ReferenceRequestStore(now::get);
        var p = store.submit(7L, "dev1", "READ", "42", "a.txt", null);
        assertThat(store.complete(7L, p.id(), null)).isEqualTo(ReferenceRequestStore.Outcome.OK);
        assertThat(p.future().get(1, TimeUnit.SECONDS)).containsEntry("ok", false);
    }
}
