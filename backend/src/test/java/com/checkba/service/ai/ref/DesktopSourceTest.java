// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import com.checkba.model.entity.AddinProjectLink;
import com.checkba.model.entity.MobileDeviceState;
import com.checkba.model.entity.MobileProjectDir;
import com.checkba.repository.AddinProjectLinkRepository;
import com.checkba.repository.MobileDeviceStateRepository;
import com.checkba.repository.MobileProjectDirRepository;
import com.checkba.service.mobile.DesktopStreamService;
import com.checkba.service.mobile.MobileRelayStoreService;
import com.checkba.service.mobile.ReferenceRequestStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 桌面端项目文件作为参考来源（dev-board#718 #719）：离线立即失败并说清楚最后在线时刻、
 * 在线则经门铃叫桌面端来取件并等结果；列目录三种情形（绑定项目 / 未绑定带关键字跨项目搜 /
 * 未绑定不带关键字只列项目）。
 */
class DesktopSourceTest {

    final DesktopStreamService stream = mock(DesktopStreamService.class);
    final ReferenceRequestStore store = mock(ReferenceRequestStore.class);
    final AddinProjectLinkRepository links = mock(AddinProjectLinkRepository.class);
    final MobileProjectDirRepository dirs = mock(MobileProjectDirRepository.class);
    final MobileDeviceStateRepository states = mock(MobileDeviceStateRepository.class);
    final MobileRelayStoreService relay = mock(MobileRelayStoreService.class);
    final DesktopSource src = new DesktopSource(stream, store, links, dirs, states, relay);
    final RefQuery q = new RefQuery(7L, 11L, "conv-a", null);

    static ReferenceRequestStore.Pending done(String id, Map<String, Object> result) {
        return new ReferenceRequestStore.Pending(id, CompletableFuture.completedFuture(result));
    }

    static AddinProjectLink link(Long userId, String deviceId, String key) {
        AddinProjectLink l = new AddinProjectLink();
        l.setUserId(userId);
        l.setDeviceId(deviceId);
        l.setProjectKey(key);
        l.setCloudProjectId(11L);
        return l;
    }

    static MobileProjectDir dir(String deviceId, String deviceName, String key, String name) {
        MobileProjectDir d = new MobileProjectDir();
        d.setUserId(7L);
        d.setDeviceId(deviceId);
        d.setDeviceName(deviceName);
        d.setProjectKey(key);
        d.setName(name);
        d.setUpdatedAt(LocalDateTime.now());
        return d;
    }

    // ==================== read / open ====================

    @Test
    void readOfflineDeviceFailsFastWithLastSeen() {
        when(stream.isOnline(7L, "dev1")).thenReturn(false);
        // 2026-09-18 10:05:00 北京时间
        when(stream.lastSeenMs(7L, "dev1")).thenReturn(OptionalLong.of(1_789_697_100_000L));

        assertThatThrownBy(() -> src.read(q, "dev1:42:合同/A.docx", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("离线")
                .hasMessageContaining("最后在线 2026-09-18 10:05");
        verifyNoInteractions(store);
    }

    @Test
    void offlineMessageNamesTheDevice() {
        MobileDeviceState s = new MobileDeviceState();
        s.setDeviceName("办公室 Mac");
        s.setLastSeenAt(LocalDateTime.now().minusHours(3));
        when(states.findByUserIdAndDeviceId(7L, "dev1")).thenReturn(Optional.of(s));
        when(stream.lastSeenMs(7L, "dev1")).thenReturn(OptionalLong.empty());

        assertThatThrownBy(() -> src.read(q, "dev1:42:A.docx", null))
                .hasMessageContaining("设备《办公室 Mac》离线")
                .hasMessageContaining("最后在线"); // 流没连过时退回心跳表的时刻
    }

    @Test
    void heartbeatOnlineButNoDoorbellSaysDesktopIsTooOld() {
        // 设备还在按 60 秒轮询（心跳窗口内），只是没有门铃流：多半是桌面端旧版本，
        // 说「离线」会让用户以为桌面端没开
        when(stream.isOnline(7L, "dev1")).thenReturn(false);
        when(relay.isDeviceOnline(7L, "dev1")).thenReturn(true);

        assertThatThrownBy(() -> src.read(q, "dev1:42:A.docx", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("升级")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("离线"));
        verifyNoInteractions(store);
    }

    @Test
    void readWaitsForDesktopText() {
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(store.submit(7L, "dev1", "READ", "42", "合同/A.docx", null))
                .thenReturn(done("r1", Map.of("ok", true, "text", "正文")));

        assertThat(src.read(q, "dev1:42:合同/A.docx", null)).isEqualTo("正文");
        verify(stream).nudge(7L, "dev1", "ref");
    }

    @Test
    void readWithLocatorSaysItIsTheWholeFile() {
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(store.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(done("r1", Map.of("ok", true, "text", "正文")));

        assertThat(src.read(q, "dev1:42:A.docx", "page:3"))
                .startsWith("未打开的文件无法按页定位，以下为全文").endsWith("正文");
    }

    @Test
    void pathMayContainColons() {
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(store.submit(7L, "dev1", "READ", "42", "附件/10:30 会议.txt", null))
                .thenReturn(done("r1", Map.of("ok", true, "text", "t")));

        assertThat(src.read(q, "dev1:42:附件/10:30 会议.txt", null)).isEqualTo("t");
    }

    @Test
    void desktopErrorIsSurfacedVerbatim() {
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(store.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(done("r1", Map.of("ok", false, "error", "项目里没有这个文件，请用 ref_list 重新查找")));

        assertThatThrownBy(() -> src.read(q, "dev1:42:A.docx", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessage("项目里没有这个文件，请用 ref_list 重新查找");
    }

    @Test
    void readTimesOutWithRetryHint() {
        // 等待上限只在构造时定；共享单例上不留可变状态，缩短等待就另造一个实例
        DesktopSource fast = new DesktopSource(stream, store, links, dirs, states, relay, 50);
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(store.submit(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ReferenceRequestStore.Pending("r1", new CompletableFuture<>()));

        assertThatThrownBy(() -> fast.read(q, "dev1:42:A.docx", null))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("60 秒内未响应");
    }

    @Test
    void malformedRefsAreRejectedBeforeAnyRoundTrip() {
        assertThatThrownBy(() -> src.read(q, "dev1", null)).hasMessageContaining("ref_list");
        assertThatThrownBy(() -> src.read(q, "dev1:42", null)).hasMessageContaining("ref_list");
        assertThatThrownBy(() -> src.read(q, ":42:a.txt", null)).hasMessageContaining("ref_list");
        assertThatThrownBy(() -> src.read(q, "dev1::a.txt", null)).hasMessageContaining("ref_list");
        // 未绑定时列出的是项目条目（path 为空）：读它要告诉模型怎么继续，而不是「无法识别」
        assertThatThrownBy(() -> src.read(q, "dev1:42:", null))
                .hasMessageContaining("这是一个项目")
                .hasMessageContaining("关键字");
        verifyNoInteractions(store, stream);
    }

    @Test
    void openAsksDesktopAndNamesTheMachine() {
        MobileDeviceState s = new MobileDeviceState();
        s.setDeviceName("办公室 Mac");
        when(states.findByUserIdAndDeviceId(7L, "dev1")).thenReturn(Optional.of(s));
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(store.submit(7L, "dev1", "OPEN", "42", "合同/A.docx", null))
                .thenReturn(done("r9", Map.of("ok", true, "opened", true)));

        String out = src.open(q, "dev1:42:合同/A.docx");

        assertThat(out).contains("《办公室 Mac》").contains("AI WorkDeck 窗格");
        verify(stream).nudge(7L, "dev1", "ref");
    }

    @Test
    void deviceNameFallsBackToDirectoryThenIdPrefix() {
        when(dirs.findByUserIdAndDeviceId(7L, "dev1")).thenReturn(List.of(dir("dev1", "家里的 PC", "42", "某某案")));
        assertThatThrownBy(() -> src.read(q, "dev1:42:A.docx", null)).hasMessageContaining("《家里的 PC》");

        assertThatThrownBy(() -> src.read(q, "0123456789abcdef:42:A.docx", null))
                .hasMessageContaining("《01234567》");
    }

    /**
     * 红线：参考读取不计费，与 PULL/PUSH 的账目完全分离（spec 第 6 节）。整条参考链路上不许
     * 出现计费客户端——最后一句是正控制：同一断言在真的接了计费的 MobileTransferService 上必须
     * 认得出来，否则这条守卫就是空断言。
     */
    @Test
    void referenceReadPathIsNotWiredToBilling() {
        for (Class<?> c : List.of(DesktopSource.class, ReferenceRequestStore.class, DesktopStreamService.class,
                com.checkba.controller.MobileRefController.class)) {
            assertThat(fieldTypes(c)).as(c.getSimpleName()).noneMatch(n -> n.contains("Billing"));
        }
        assertThat(fieldTypes(com.checkba.service.mobile.MobileTransferService.class))
                .anyMatch(n -> n.contains("Billing"));
    }

    private static List<String> fieldTypes(Class<?> c) {
        List<String> out = new java.util.ArrayList<>();
        for (java.lang.reflect.Field f : c.getDeclaredFields()) out.add(f.getType().getName());
        return out;
    }

    // ==================== list ====================

    @Test
    void listUsesBoundProject() {
        when(links.findByCloudProjectId(11L)).thenReturn(Optional.of(link(7L, "dev1", "42")));
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(store.submit(eq(7L), eq("dev1"), eq("LIST"), eq("42"), isNull(), isNull()))
                .thenReturn(done("r2", Map.of("ok", true, "entries", List.of(
                        Map.of("path", "合同/A.docx", "name", "A.docx", "openable", true,
                                "updatedAt", "2026-09-18T10:00:00")))));

        List<RefEntry> out = src.list(q);

        assertThat(out).extracting(RefEntry::ref).containsExactly("desk:dev1:42:合同/A.docx");
        assertThat(out.get(0).source()).isEqualTo("desk");
        assertThat(out.get(0).name()).isEqualTo("A.docx");
        assertThat(out.get(0).path()).isEqualTo("合同/A.docx");
        assertThat(out.get(0).openable()).isTrue();
        assertThat(out.get(0).updatedAt()).isEqualTo("2026-09-18T10:00:00");
        verify(stream).nudge(7L, "dev1", "ref");
    }

    @Test
    void boundListHostNamesBothTheDeviceAndTheProject() {
        // 绑定分支的 host 以前只有设备名，与未绑定分支不一致，模型看不出条目属于哪个项目
        when(links.findByCloudProjectId(11L)).thenReturn(Optional.of(link(7L, "dev1", "42")));
        when(dirs.findByUserIdAndDeviceId(7L, "dev1")).thenReturn(List.of(dir("dev1", "办公室 Mac", "42", "某某案")));
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(store.submit(eq(7L), eq("dev1"), eq("LIST"), eq("42"), isNull(), isNull()))
                .thenReturn(done("r2", Map.of("ok", true, "entries", List.of(
                        Map.of("path", "合同/A.docx", "name", "A.docx")))));

        List<RefEntry> out = src.list(q);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).host()).contains("办公室 Mac").contains("某某案");
    }

    @Test
    void listPassesTheKeywordToTheBoundProject() {
        when(links.findByCloudProjectId(11L)).thenReturn(Optional.of(link(7L, "dev1", "42")));
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(store.submit(7L, "dev1", "LIST", "42", null, "清单"))
                .thenReturn(done("r2", Map.of("ok", true, "entries", List.of())));

        assertThat(src.list(new RefQuery(7L, 11L, "conv-a", " 清单 "))).isEmpty();
        verify(store).submit(7L, "dev1", "LIST", "42", null, "清单");
    }

    @Test
    void boundDeviceOfflineMakesTheSourceUnavailable() {
        when(links.findByCloudProjectId(11L)).thenReturn(Optional.of(link(7L, "dev1", "42")));
        when(stream.isOnline(7L, "dev1")).thenReturn(false);

        assertThatThrownBy(() -> src.list(q)).isInstanceOf(RefSourceException.class).hasMessageContaining("离线");
        verifyNoInteractions(store);
    }

    @Test
    void someoneElsesLinkIsIgnored() {
        when(links.findByCloudProjectId(11L)).thenReturn(Optional.of(link(8L, "devX", "42")));
        when(dirs.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of());

        assertThat(src.list(q)).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void unboundProjectListsOnlineDevicesProjects() {
        when(links.findByCloudProjectId(11L)).thenReturn(Optional.empty());
        when(dirs.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of(
                dir("dev1", "办公室 Mac", "42", "某某案"),
                dir("dev2", "家里的 PC", "5", "另一案")));
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(stream.isOnline(7L, "dev2")).thenReturn(false);

        List<RefEntry> out = src.list(q);

        // 未绑定且没有关键字时不下发 LIST，只列在线设备的项目，模型再带关键字查
        assertThat(out).extracting(RefEntry::name).containsExactly("某某案");
        assertThat(out).extracting(RefEntry::ref).containsExactly("desk:dev1:42:");
        assertThat(out.get(0).host()).contains("办公室 Mac");
        verifyNoInteractions(store);
    }

    @Test
    void unboundWithQuerySearchesAllProjectsOfOnlineDevices() {
        when(links.findByCloudProjectId(11L)).thenReturn(Optional.empty());
        when(dirs.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of(
                dir("dev1", "办公室 Mac", "42", "某某案"),
                dir("dev1", "办公室 Mac", "43", "第三案"),
                dir("dev2", "家里的 PC", "5", "另一案")));
        when(stream.isOnline(7L, "dev1")).thenReturn(true);
        when(stream.isOnline(7L, "dev2")).thenReturn(false);
        when(store.submit(7L, "dev1", "LIST", "*", null, "清单"))
                .thenReturn(done("r3", Map.of("ok", true, "entries", List.of(
                        Map.of("path", "附件/清单.xlsx", "name", "清单.xlsx", "openable", true, "projectKey", "42"),
                        // 跨项目搜出来的条目没带 projectKey：拼不出 ref，丢掉而不是乱拼
                        Map.of("path", "无主/清单.xlsx", "name", "清单.xlsx")))));

        List<RefEntry> out = src.list(new RefQuery(7L, 11L, "conv-a", "清单"));

        assertThat(out).extracting(RefEntry::ref).containsExactly("desk:dev1:42:附件/清单.xlsx");
        assertThat(out.get(0).host()).contains("办公室 Mac").contains("某某案");
        verify(store, never()).submit(eq(7L), eq("dev2"), anyString(), any(), any(), any());
    }

    @Test
    void noDesktopsAtAllListsNothingQuietly() {
        when(links.findByCloudProjectId(11L)).thenReturn(Optional.empty());
        when(dirs.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of());

        assertThat(src.list(q)).isEmpty();
        assertThat(src.list(new RefQuery(7L, null, "conv-a", "清单"))).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void knownDesktopsAllOfflineIsReportedNotSilentlyEmpty() {
        // 用户有桌面端、只是都不在线：返回空清单会让模型以为桌面项目里没有这个文件
        when(links.findByCloudProjectId(11L)).thenReturn(Optional.empty());
        when(dirs.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of(dir("dev1", "办公室 Mac", "42", "某某案")));
        when(stream.isOnline(7L, "dev1")).thenReturn(false);

        assertThatThrownBy(() -> src.list(new RefQuery(7L, 11L, "conv-a", "清单")))
                .isInstanceOf(RefSourceException.class)
                .hasMessageContaining("《办公室 Mac》").hasMessageContaining("离线");
        verifyNoInteractions(store);
    }
}
