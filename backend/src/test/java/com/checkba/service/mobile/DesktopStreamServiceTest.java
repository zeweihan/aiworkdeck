// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.mobile;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桌面端门铃流（dev-board#719）的纯内存语义：单连接（后连顶掉先连）、在线=流在连、
 * nudge 返回是否送达、发送失败即摘掉连接但保留「最后在线」时刻。
 * 真实 HTTP 写出（event:ready / superseded / nudge 的线上形态）见 MobileRefControllerTest。
 */
class DesktopStreamServiceTest {

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    @Test
    void nudgeReturnsFalseWhenOffline() {
        var svc = new DesktopStreamService(now::get);
        assertThat(svc.nudge(7L, "dev1", "ref")).isFalse();
        assertThat(svc.isOnline(7L, "dev1")).isFalse();
        assertThat(svc.lastSeenMs(7L, "dev1")).isEmpty();
    }

    @Test
    void connectMakesDeviceOnlineAndNudgeIsDelivered() {
        var svc = new DesktopStreamService(now::get);
        svc.connect(7L, "dev1");
        assertThat(svc.isOnline(7L, "dev1")).isTrue();
        assertThat(svc.isOnline(7L, "dev2")).isFalse();
        assertThat(svc.isOnline(8L, "dev1")).isFalse(); // 设备在线按 (userId, deviceId) 判，不跨账号
        assertThat(svc.nudge(7L, "dev1", "ref")).isTrue();
        assertThat(svc.lastSeenMs(7L, "dev1")).hasValue(now.get());
    }

    @Test
    void secondConnectSupersedesFirst() {
        var svc = new DesktopStreamService(now::get);
        SseEmitter a = svc.connect(7L, "dev1");
        SseEmitter b = svc.connect(7L, "dev1");
        assertThat(svc.isOnline(7L, "dev1")).isTrue();
        assertThat(a).isNotSameAs(b);
        assertThat(svc.nudge(7L, "dev1", "transfer")).isTrue(); // 送到的是 b，a 已被收尾
    }

    @Test
    void deadConnectionIsDroppedOnPingButLastSeenIsKept() {
        var svc = new DesktopStreamService(now::get);
        SseEmitter a = svc.connect(7L, "dev1");
        long connectedAt = now.get();
        a.complete(); // 模拟对端已断：之后往它写会失败
        now.addAndGet(15_000);

        svc.ping();

        assertThat(svc.isOnline(7L, "dev1")).isFalse();
        assertThat(svc.nudge(7L, "dev1", "ref")).isFalse();
        assertThat(svc.lastSeenMs(7L, "dev1")).hasValue(connectedAt);
    }

    @Test
    void successfulPingRefreshesLastSeen() {
        var svc = new DesktopStreamService(now::get);
        svc.connect(7L, "dev1");
        now.addAndGet(15_000);
        svc.ping();
        assertThat(svc.lastSeenMs(7L, "dev1")).hasValue(now.get());
    }

    @Test
    void lastSeenOfLongGoneDevicesIsPrunedButLiveOnesAreKept() {
        var svc = new DesktopStreamService(now::get);
        SseEmitter dead = svc.connect(7L, "old-device");
        dead.complete();
        svc.connect(7L, "live-device");

        now.addAndGet(DesktopStreamService.LAST_SEEN_TTL_MS + 1);
        svc.ping(); // ping 会把断开的那条摘掉，并顺手清「最后在线」表

        assertThat(svc.lastSeenMs(7L, "old-device")).isEmpty();
        assertThat(svc.lastSeenMs(7L, "live-device")).isPresent(); // 还连着的不许被清掉
    }

    @Test
    void lastSeenStopsGrowingWhenDeviceIdsKeepChanging() {
        // deviceId 是客户端自带的：反复换着连也不能让「最后在线」表无限长
        var svc = new DesktopStreamService(now::get);
        for (int i = 0; i < DesktopStreamService.MAX_LAST_SEEN + 200; i++) {
            SseEmitter e = svc.connect(7L, "dev-" + i);
            e.complete(); // 连完就断，模拟换一个 deviceId 再连
            now.addAndGet(1);
        }
        svc.ping();

        int kept = 0;
        for (int i = 0; i < DesktopStreamService.MAX_LAST_SEEN + 200; i++) {
            if (svc.lastSeenMs(7L, "dev-" + i).isPresent()) kept++;
        }
        assertThat(kept).isLessThanOrEqualTo(DesktopStreamService.MAX_LAST_SEEN);
        // 淘汰从最老的开始：最近连过的那台仍答得出「最后在线」
        assertThat(svc.lastSeenMs(7L, "dev-" + (DesktopStreamService.MAX_LAST_SEEN + 199))).isPresent();
    }

    @Test
    void failedNudgeDropsTheConnection() {
        var svc = new DesktopStreamService(now::get);
        SseEmitter a = svc.connect(7L, "dev1");
        a.complete();
        assertThat(svc.nudge(7L, "dev1", "ref")).isFalse();
        assertThat(svc.isOnline(7L, "dev1")).isFalse();
    }
}
