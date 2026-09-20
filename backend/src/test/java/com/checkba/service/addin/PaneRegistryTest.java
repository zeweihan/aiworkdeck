// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.addin;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件窗格登记簿（dev-board#717）：按账号隔离、90 秒无心跳过期、告别即删、
 * 重复心跳覆盖会话 id。跨文档读写靠它回答「这个账号现在开着哪些文档」。
 */
class PaneRegistryTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final PaneRegistry registry = new PaneRegistry(now::get);

    private PaneRegistry.PaneInfo pane(String id, String conv) {
        return new PaneRegistry.PaneInfo(id, 7L, "word", "office", id + ".docx", 11L, conv, 0);
    }

    @Test
    void heartbeatThenListExcludesSelf() {
        registry.heartbeat(7L, pane("A", "conv-a"));
        registry.heartbeat(7L, pane("B", "conv-b"));
        assertThat(registry.list(7L, "A")).extracting(PaneRegistry.PaneInfo::paneId).containsExactly("B");
    }

    @Test
    void otherUsersPanesAreInvisible() {
        registry.heartbeat(7L, pane("A", "conv-a"));
        assertThat(registry.list(8L, null)).isEmpty();
        assertThat(registry.find(8L, "A")).isEmpty();
        assertThat(registry.paneOfConversation(8L, "conv-a")).isEmpty();
    }

    @Test
    void expiresAfter90Seconds() {
        registry.heartbeat(7L, pane("B", "conv-b"));
        now.addAndGet(90_001);
        assertThat(registry.list(7L, null)).isEmpty();
        assertThat(registry.find(7L, "B")).isEmpty();
    }

    @Test
    void stillListedJustBeforeExpiry() {
        registry.heartbeat(7L, pane("B", "conv-b"));
        now.addAndGet(90_000);
        assertThat(registry.find(7L, "B")).isPresent();
    }

    @Test
    void heartbeatRefreshesLastSeen() {
        registry.heartbeat(7L, pane("B", "conv-b"));
        now.addAndGet(60_000);
        registry.heartbeat(7L, pane("B", "conv-b"));
        now.addAndGet(60_000);
        assertThat(registry.find(7L, "B")).isPresent();
    }

    @Test
    void byeRemovesImmediately() {
        registry.heartbeat(7L, pane("B", "conv-b"));
        registry.bye(7L, "B");
        assertThat(registry.find(7L, "B")).isEmpty();
    }

    @Test
    void byeFromAnotherUserDoesNotRemove() {
        registry.heartbeat(7L, pane("B", "conv-b"));
        registry.bye(8L, "B");
        assertThat(registry.find(7L, "B")).isPresent();
    }

    @Test
    void reHeartbeatUpdatesConversation() {
        registry.heartbeat(7L, pane("B", "conv-1"));
        registry.heartbeat(7L, pane("B", "conv-2"));
        assertThat(registry.find(7L, "B").orElseThrow().conversationId()).isEqualTo("conv-2");
        assertThat(registry.paneOfConversation(7L, "conv-2")).isPresent();
        assertThat(registry.paneOfConversation(7L, "conv-1")).isEmpty();
    }

    /**
     * 同一条会话上挂着两个窗格（两份未保存的新文档算出同一个会话键）：登记簿按 paneId 存，
     * 两条都在。这时「哪一个是这条会话的窗格」没有答案——随便挑一个会让跨文档写入
     * 署错来源文档，所以 paneOfConversation 不猜，调用方改看 panesOfConversation。
     */
    @Test
    void twoPanesCanShareOneConversationAndThenNeitherIsTheOne() {
        registry.heartbeat(7L, pane("N1", "conv-x"));
        registry.heartbeat(7L, pane("N2", "conv-x"));
        assertThat(registry.panesOfConversation(7L, "conv-x"))
                .extracting(PaneRegistry.PaneInfo::paneId).containsExactlyInAnyOrder("N1", "N2");
        assertThat(registry.paneOfConversation(7L, "conv-x")).isEmpty();

        registry.bye(7L, "N2");
        assertThat(registry.paneOfConversation(7L, "conv-x")).map(PaneRegistry.PaneInfo::paneId).contains("N1");
        assertThat(registry.panesOfConversation(7L, null)).isEmpty();
        assertThat(registry.panesOfConversation(8L, "conv-x")).isEmpty();
    }

    @Test
    void storedEntryCarriesServerSideUserAndClock() {
        // 请求体里的 userId / lastSeenMs 不可信：一律以鉴权出的账号与服务端时钟为准
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("B", 99L, "word", "office", "B.docx", 11L, "c", 5L));
        PaneRegistry.PaneInfo stored = registry.find(7L, "B").orElseThrow();
        assertThat(stored.userId()).isEqualTo(7L);
        assertThat(stored.lastSeenMs()).isEqualTo(1_000_000L);
    }

    @Test
    void blankPaneIdIgnored() {
        registry.heartbeat(7L, pane("", "c"));
        registry.heartbeat(7L, pane(null, "c"));
        registry.heartbeat(null, pane("A", "c"));
        assertThat(registry.list(7L, null)).isEmpty();
    }

    @Test
    void expiredEntriesArePurgedOnHeartbeatToo() {
        // 窗格每次载入都换一个 paneId；只在 list() 时清理的话，从不触发跨文档读取的账号
        // 会把每一个没来得及告别的旧窗格永远留在内存里
        registry.heartbeat(7L, pane("old", "c-old"));
        now.addAndGet(90_001);
        registry.heartbeat(7L, pane("new", "c-new"));
        assertThat(registry.size(7L)).isEqualTo(1);
    }
}
