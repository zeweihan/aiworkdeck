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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 桌面端项目里的文件（dev-board#718 #719），ref 形如 {@code desk:<deviceId>:<projectKey>:<path>}
 * （path 可含 {@code :}，只按前两个 {@code :} 切）。只读（D 决策），唯一的写动作是请桌面端用默认程序
 * 打开文件（{@link #open}），打开后用户在那个文档自己的窗格里继续。
 *
 * <p>往返：{@link ReferenceRequestStore#submit} 登记 → {@link DesktopStreamService#nudge} 按门铃 →
 * 桌面端取件处理后回传 → 本类等 future（最多 60 秒）。离线立即失败并说清楚最后在线时刻，不空等。
 * 在线 = 门铃流在连（spec 第 5 节）；设备还在按 60 秒轮询却没有门铃流的，是旧版桌面端，
 * 单独说「请升级」，免得用户以为桌面端没开。
 *
 * <p>list 三种情形：当前会话项目绑定了桌面项目（归档绑定）→ 只列那个项目；未绑定带关键字 →
 * 在每台在线设备上按文件名跨全部项目搜；未绑定不带关键字 → 不发请求，只列在线设备的项目
 * （ref 的 path 为空），让模型带上文件名关键字再查。
 */
@Component
public class DesktopSource implements RefSource {

    static final String NUDGE_KIND = "ref";
    static final String ALL_PROJECTS = "*";
    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LAST_SEEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_ERROR_CHARS = 300;

    static final String BAD_REF = "无法识别的引用，请先用 ref_list 获取 ref。";
    static final String PROJECT_REF = "这是一个项目，不是文件。请用 ref_list 并带上文件名关键字查找其中的文件。";

    private final DesktopStreamService stream;
    private final ReferenceRequestStore store;
    private final AddinProjectLinkRepository links;
    private final MobileProjectDirRepository dirs;
    private final MobileDeviceStateRepository states;
    private final MobileRelayStoreService relay;

    /** 等桌面端回话的上限。单例上不留可变状态：测试要缩短就另造一个实例。 */
    private final long waitMillis;

    @Autowired
    public DesktopSource(DesktopStreamService stream, ReferenceRequestStore store, AddinProjectLinkRepository links,
                         MobileProjectDirRepository dirs, MobileDeviceStateRepository states,
                         MobileRelayStoreService relay) {
        this(stream, store, links, dirs, states, relay, ReferenceRequestStore.TTL_MS);
    }

    DesktopSource(DesktopStreamService stream, ReferenceRequestStore store, AddinProjectLinkRepository links,
                  MobileProjectDirRepository dirs, MobileDeviceStateRepository states,
                  MobileRelayStoreService relay, long waitMillis) {
        this.stream = stream;
        this.store = store;
        this.links = links;
        this.dirs = dirs;
        this.states = states;
        this.relay = relay;
        this.waitMillis = waitMillis;
    }

    @Override
    public String scheme() {
        return "desk";
    }

    // ==================== list ====================

    @Override
    public List<RefEntry> list(RefQuery q) {
        if (q.userId() == null) return List.of();
        String keyword = q.query() == null || q.query().isBlank() ? null : q.query().trim();

        Optional<AddinProjectLink> bound = boundLink(q);
        if (bound.isPresent()) {
            String deviceId = bound.get().getDeviceId();
            String projectKey = bound.get().getProjectKey();
            requireOnline(q.userId(), deviceId);
            long deadline = System.currentTimeMillis() + waitMillis;
            Map<String, Object> r = await(q.userId(), deviceId,
                    store.submit(q.userId(), deviceId, "LIST", projectKey, null, keyword), deadline);
            // host 里也带上项目名：与未绑定那条分支同一形态，模型看到的来源才一致
            List<MobileProjectDir> rows = dirRows(q.userId(), deviceId);
            return toEntries(deviceId, nameOf(rows, q.userId(), deviceId), projectKey, projectNames(rows), r);
        }

        // 未绑定：按目录镜像认出用户有哪些桌面机（按最近推送排序），只问在线的
        List<MobileProjectDir> dirRows = dirs.findByUserIdOrderByUpdatedAtDesc(q.userId());
        if (dirRows.isEmpty()) return List.of(); // 没用桌面端：安静地不列，不在清单里添一行噪音
        Map<String, List<MobileProjectDir>> byDevice = new LinkedHashMap<>();
        for (MobileProjectDir d : dirRows) {
            byDevice.computeIfAbsent(d.getDeviceId(), k -> new ArrayList<>()).add(d);
        }
        List<String> online = new ArrayList<>();
        for (String deviceId : byDevice.keySet()) {
            if (stream.isOnline(q.userId(), deviceId)) online.add(deviceId);
        }
        if (online.isEmpty()) {
            // 有桌面端却都不在线：说出来，空清单会让模型以为桌面项目里没有这个文件。
            // dirRows 按 updatedAt 倒序，byDevice 是 LinkedHashMap——第一台就是最近推过目录的那台，
            // 多台时报它最可能是用户此刻想用的。设备名用手上这批目录行里的，别为一句报错再回库查
            String offline = byDevice.keySet().iterator().next();
            throw unavailable(q.userId(), offline, nameOf(byDevice.get(offline), q.userId(), offline));
        }

        List<RefEntry> out = new ArrayList<>();
        if (keyword == null) {
            for (String deviceId : online) {
                String name = nameOf(byDevice.get(deviceId), q.userId(), deviceId);
                for (MobileProjectDir d : byDevice.get(deviceId)) {
                    if (out.size() >= ReferenceSourceService.MAX_ENTRIES) return out;
                    out.add(new RefEntry("desk:" + deviceId + ":" + d.getProjectKey() + ":", "desk", d.getName(),
                            d.getName() + "/", "设备《" + name + "》上的桌面端项目", null, null));
                }
            }
            return out;
        }

        // 带关键字：先给每台在线设备都发请求再逐个等，几台设备的等待时间重叠而不是相加
        long deadline = System.currentTimeMillis() + waitMillis;
        Map<String, ReferenceRequestStore.Pending> pending = new LinkedHashMap<>();
        for (String deviceId : online) {
            pending.put(deviceId, store.submit(q.userId(), deviceId, "LIST", ALL_PROJECTS, null, keyword));
            stream.nudge(q.userId(), deviceId, NUDGE_KIND);
        }
        RefSourceException firstFailure = null;
        for (Map.Entry<String, ReferenceRequestStore.Pending> e : pending.entrySet()) {
            String deviceId = e.getKey();
            Map<String, Object> r;
            try {
                r = waitFor(e.getValue(), deadline);
            } catch (RefSourceException ex) {
                if (firstFailure == null) firstFailure = ex;
                continue;
            }
            out.addAll(toEntries(deviceId, nameOf(byDevice.get(deviceId), q.userId(), deviceId), null,
                    projectNames(byDevice.get(deviceId)), r));
        }
        if (out.isEmpty() && firstFailure != null) throw firstFailure;
        return out.size() > ReferenceSourceService.MAX_ENTRIES
                ? new ArrayList<>(out.subList(0, ReferenceSourceService.MAX_ENTRIES)) : out;
    }

    // ==================== read / open ====================

    @Override
    public String read(RefQuery q, String body, String locator) {
        String[] p = split(body);
        requireOnline(q.userId(), p[0]);
        Map<String, Object> r = await(q.userId(), p[0],
                store.submit(q.userId(), p[0], "READ", p[1], p[2], null),
                System.currentTimeMillis() + waitMillis);
        Object text = r.get("text");
        return RefSource.withLocatorNote(locator, text == null ? "" : String.valueOf(text));
    }

    @Override
    public String open(RefQuery q, String body) {
        String[] p = split(body);
        requireOnline(q.userId(), p[0]);
        await(q.userId(), p[0], store.submit(q.userId(), p[0], "OPEN", p[1], p[2], null),
                System.currentTimeMillis() + waitMillis);
        return "已在设备《" + deviceName(q.userId(), p[0]) + "》上用默认程序打开该文件。"
                + "请用户在该文档里打开 AI WorkDeck 窗格后继续。";
    }

    /** body = deviceId:projectKey:path；path 可含 ':'，只按前两个 ':' 切。path 为空 = 项目条目。 */
    static String[] split(String body) {
        String b = body == null ? "" : body;
        int a = b.indexOf(':');
        int c = a < 0 ? -1 : b.indexOf(':', a + 1);
        if (a <= 0 || c <= a + 1) throw new RefSourceException(BAD_REF);
        String path = b.substring(c + 1);
        if (path.isBlank()) throw new RefSourceException(PROJECT_REF);
        return new String[] { b.substring(0, a), b.substring(a + 1, c), path };
    }

    // ==================== 往返 ====================

    private Map<String, Object> await(Long userId, String deviceId, ReferenceRequestStore.Pending pending,
                                      long deadline) {
        // 按不响也照样等：桌面端的 60 秒轮询兜底，可能刚好赶上
        stream.nudge(userId, deviceId, NUDGE_KIND);
        return waitFor(pending, deadline);
    }

    private Map<String, Object> waitFor(ReferenceRequestStore.Pending pending, long deadline) {
        Map<String, Object> r;
        try {
            r = pending.future().get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RefSourceException(ReferenceRequestStore.TIMEOUT_MESSAGE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RefSourceException("读取被中断，可稍后重试。");
        } catch (ExecutionException e) {
            throw new RefSourceException("桌面端处理失败，可稍后重试。");
        }
        if (r == null || !Boolean.TRUE.equals(r.get("ok"))) {
            String err = r == null || r.get("error") == null ? "" : String.valueOf(r.get("error")).trim();
            if (err.isEmpty()) err = "桌面端处理失败，可稍后重试。";
            throw new RefSourceException(err.length() > MAX_ERROR_CHARS ? err.substring(0, MAX_ERROR_CHARS) : err);
        }
        return r;
    }

    /**
     * 把桌面端 LIST 结果映射成 ref 条目。fixedProjectKey 非空时（绑定项目）条目没带 projectKey 就用它；
     * 跨项目搜时（fixedProjectKey 为 null）条目必须自带 projectKey，拼不出 ref 的丢掉而不是乱拼。
     */
    private static List<RefEntry> toEntries(String deviceId, String deviceName, String fixedProjectKey,
                                            Map<String, String> projectNames, Map<String, Object> r) {
        List<RefEntry> out = new ArrayList<>();
        if (!(r.get("entries") instanceof List<?> list)) return out;
        for (Object o : list) {
            if (out.size() >= ReferenceSourceService.MAX_ENTRIES) break;
            if (!(o instanceof Map<?, ?> m)) continue;
            String path = str(m.get("path"));
            // 清单按行排版（ref | source | path），带换行的路径会把一条拆成两行
            if (path == null || path.isBlank() || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) continue;
            String key = str(m.get("projectKey"));
            if (key == null || key.isBlank()) key = fixedProjectKey;
            if (key == null || key.isBlank() || key.indexOf(':') >= 0) continue;
            String name = str(m.get("name"));
            if (name == null || name.isBlank()) name = path.substring(path.lastIndexOf('/') + 1);
            String project = projectNames.get(key);
            String host = "设备《" + deviceName + "》" + (project == null ? "" : "·项目《" + project + "》");
            Boolean openable = m.get("openable") instanceof Boolean ob ? ob : null;
            out.add(new RefEntry("desk:" + deviceId + ":" + key + ":" + path, "desk", name, path, host,
                    str(m.get("updatedAt")), openable));
        }
        return out;
    }

    // ==================== 在线与设备名 ====================

    private Optional<AddinProjectLink> boundLink(RefQuery q) {
        if (q.projectId() == null) return Optional.empty();
        return links.findByCloudProjectId(q.projectId())
                .filter(l -> q.userId().equals(l.getUserId()))
                .filter(l -> l.getDeviceId() != null && l.getProjectKey() != null);
    }

    private void requireOnline(Long userId, String deviceId) {
        if (!stream.isOnline(userId, deviceId)) throw unavailable(userId, deviceId);
    }

    private RefSourceException unavailable(Long userId, String deviceId) {
        return unavailable(userId, deviceId, deviceName(userId, deviceId));
    }

    /** 不在线的原因：旧版桌面端（还在轮询、没有门铃流）与真离线分开说。 */
    private RefSourceException unavailable(Long userId, String deviceId, String name) {
        if (relay.isDeviceOnline(userId, deviceId)) {
            return new RefSourceException("设备《" + name + "》上的桌面端还没有与云端建立常连（多半是版本较旧），"
                    + "读不到其中的项目文件。请把桌面端升级到最新版本后重试，或手动上传文件。");
        }
        String last = lastSeen(userId, deviceId);
        return new RefSourceException("设备《" + name + "》离线" + (last == null ? "" : "，最后在线 " + last)
                + "。请打开桌面端，或手动上传文件。");
    }

    /** 门铃流最后确认连着的时刻；流从没连过（重启后、旧版本）退回设备心跳表。 */
    private String lastSeen(Long userId, String deviceId) {
        OptionalLong ms = stream.lastSeenMs(userId, deviceId);
        if (ms.isPresent()) {
            return LAST_SEEN.format(Instant.ofEpochMilli(ms.getAsLong()).atZone(CST));
        }
        LocalDateTime at = state(userId, deviceId).map(MobileDeviceState::getLastSeenAt).orElse(null);
        if (at == null) return null;
        // 心跳表存的是服务器本地时间；模型看到的「当前时间」一律按北京时间（ContextAssemblerService）
        ZonedDateTime z = at.atZone(ZoneId.systemDefault()).withZoneSameInstant(CST);
        return LAST_SEEN.format(z);
    }

    /** 设备名：心跳表 → 目录镜像 → deviceId 前 8 位。 */
    String deviceName(Long userId, String deviceId) {
        Optional<MobileDeviceState> s = state(userId, deviceId);
        if (s.isPresent() && notBlank(s.get().getDeviceName())) return s.get().getDeviceName();
        return nameOf(dirRows(userId, deviceId), null, deviceId);
    }

    /** 该设备的目录镜像行；查不到就按空处理，一句 host 文案不值得把整次 list 打挂。 */
    private List<MobileProjectDir> dirRows(Long userId, String deviceId) {
        try {
            List<MobileProjectDir> rows = dirs.findByUserIdAndDeviceId(userId, deviceId);
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Map<String, String> projectNames(List<MobileProjectDir> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rows != null) {
            for (MobileProjectDir d : rows) out.put(d.getProjectKey(), d.getName());
        }
        return out;
    }

    /** 已有目录行时先看行里的名字，没有再走 {@link #deviceName}（userId 为 null 时直接退到前缀）。 */
    private String nameOf(List<MobileProjectDir> rows, Long userId, String deviceId) {
        if (rows != null) {
            for (MobileProjectDir d : rows) {
                if (notBlank(d.getDeviceName())) return d.getDeviceName();
            }
        }
        if (userId != null) {
            Optional<MobileDeviceState> s = state(userId, deviceId);
            if (s.isPresent() && notBlank(s.get().getDeviceName())) return s.get().getDeviceName();
        }
        return deviceId == null ? "" : deviceId.length() <= 8 ? deviceId : deviceId.substring(0, 8);
    }

    private Optional<MobileDeviceState> state(Long userId, String deviceId) {
        try {
            return states.findByUserIdAndDeviceId(userId, deviceId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
