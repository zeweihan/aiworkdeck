package com.checkba.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 本地代理端口漂移自愈。
 *
 * <p><b>病灶</b>（2026-08-16 实证）：macOS 上**任何 JVM 启动时都会把系统代理设置灌进
 * {@code http(s).proxyHost/Port} 系统属性**——不需要 {@code -D}、不需要 {@code JAVA_TOOL_OPTIONS}
 * （{@code env -i} 空环境实测依然有）。OkHttp 走 {@code ProxySelector.getDefault()}，
 * 于是全部出站流量被送去本地代理端口。而桌面后端是**长命 JVM**（开 app 起、连跑数天），
 * 把端口冻在启动那一刻；用户的代理软件换端口或重启后（实测 1235 → 8234），
 * 后端一直在拨那个已经没人监听的旧端口，**每一个 AI 请求都 ConnectException**。
 * 修复前这个失败还会被 openai4j 吞掉（旧流式通道的 logResponses NPE 地雷，
 * 见 {@code ChatModelFactory.streamingModel} 的 javadoc；流式通道已换成自有实现），
 * 用户看到的是"点了发送三分钟没反应"。
 *
 * <p><b>为什么必须改属性而不是开 {@code java.net.useSystemProxies}</b>：那个开关在
 * {@code DefaultProxySelector} 类初始化时就固化了，运行期再打开无效（实测返回 DIRECT，
 * 且两个 JDK 的 {@code conf/net.properties} 默认都是 false）。而
 * {@code DefaultProxySelector.select()} 每次调用都重读系统属性，所以
 * {@code System.setProperty} 立刻生效、无需重启——这是本类成立的前提。
 *
 * <p><b>启用条件刻意收窄</b>（只治真正会漂的那一种，见 {@link #resolveEnabled}）：
 * 只在 macOS、且 JVM 启动时继承到的代理指向**回环地址**时才工作。回环 = 本机代理软件，
 * 端口本来就会随重启/改配置而变；非回环的企业代理端口是稳定的，去动它只有风险没有收益。
 * 条件不满足时本类**永久停工**，不产生任何开销。
 *
 * <p><b>刻意不覆盖的场景</b>：JVM 启动时系统压根没开代理 → 本类停工，之后用户再打开代理也不会被接管
 * （流量继续直连）。这不是遗漏：那种情况下 JVM 本来就没有被冻住的旧端口，不存在要治的病；
 * 而放宽成"macOS 一律接管"意味着往一个从没有过代理属性的进程里注入代理，
 * 行为改动和风险都大得多。真要覆盖，正确做法是重启后端而不是放宽这里。
 */
@Service
public class SystemProxyRefresher {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SystemProxyRefresher.class);

    /** 出问题时的关闭开关。本类会改写全局 JVM 属性，影响面是整个进程的出站流量，留个退路。 */
    @Value("${network.proxy.auto-refresh:true}")
    private boolean autoRefresh = true;

    /** null = 尚未判定；判定一次后不再变（启动快照决定是否该管这台机器） */
    private Boolean enabled;

    /** 一条代理端点；host 为 null 表示"系统当前没开代理"。 */
    record Endpoint(String host, int port) {
        static final Endpoint NONE = new Endpoint(null, 0);

        boolean present() {
            return host != null && !host.isBlank() && port > 0;
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refresh() {
        if (!autoRefresh) return;
        if (!isEnabled()) return;
        try {
            readOsProxy().ifPresent(this::applyIfChanged);
        } catch (Exception e) {
            // 自愈失败不该影响任何业务，降噪到 debug：这条每分钟跑一次
            log.debug("System proxy refresh failed", e);
        }
    }

    private boolean isEnabled() {
        Boolean e = enabled;
        if (e == null) {
            e = resolveEnabled(System.getProperty("os.name"), currentJvmProxy());
            enabled = e;
            if (e) {
                log.info("本地代理端口自愈已启用（JVM 启动时继承到 {}）", currentJvmProxy());
            }
        }
        return e;
    }

    /**
     * 只管 macOS + 回环代理这一种组合，理由见类注释。
     * 抽成静态纯函数是为了让判定逻辑可以脱离本机环境被测到。
     */
    static boolean resolveEnabled(String osName, Endpoint jvmProxy) {
        if (osName == null || !osName.toLowerCase(Locale.ROOT).contains("mac")) return false;
        return jvmProxy.present() && isLoopback(jvmProxy.host());
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    /** JVM 当前生效的 https 代理（本类只跟 https 对齐——出站 AI 调用都是 https）。 */
    private static Endpoint currentJvmProxy() {
        String host = System.getProperty("https.proxyHost");
        int port = parsePort(System.getProperty("https.proxyPort"));
        return host == null ? Endpoint.NONE : new Endpoint(host, port);
    }

    private static int parsePort(String raw) {
        try {
            return raw == null ? 0 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 把系统当前代理对齐到 JVM 属性上。返回 true 表示确实改了。
     *
     * <p>系统关掉代理时**清空属性**而不是保留旧值——保留的话流量会继续送去一个
     * 用户已经关掉的端口，比直连更糟。
     */
    boolean applyIfChanged(Endpoint desired) {
        Endpoint current = currentJvmProxy();
        if (desired.present()) {
            if (desired.equals(current)) return false;
            System.setProperty("https.proxyHost", desired.host());
            System.setProperty("https.proxyPort", String.valueOf(desired.port()));
            System.setProperty("http.proxyHost", desired.host());
            System.setProperty("http.proxyPort", String.valueOf(desired.port()));
            log.warn("检测到系统代理已变更：{}:{} -> {}:{}，已就地更新（无需重启）。"
                            + "此前所有出站请求都会连到旧端口失败",
                    current.host(), current.port(), desired.host(), desired.port());
            return true;
        }
        if (!current.present()) return false;
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        log.warn("检测到系统代理已关闭（原 {}:{}），已改为直连", current.host(), current.port());
        return true;
    }

    /** 可被测试覆盖的取数口；线上走 {@code scutil --proxy}。 */
    Optional<Endpoint> readOsProxy() throws Exception {
        return parseScutil(runScutil());
    }

    private String runScutil() throws Exception {
        Process p = new ProcessBuilder("/usr/sbin/scutil", "--proxy")
                .redirectErrorStream(true).start();
        String out;
        try (var in = p.getInputStream()) {
            out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("scutil --proxy 超时");
        }
        return out;
    }

    /**
     * 解析 {@code scutil --proxy} 的字典输出。只认 HTTPS 那组：
     * <pre>
     *   HTTPSEnable : 1
     *   HTTPSPort : 8234
     *   HTTPSProxy : 127.0.0.1
     * </pre>
     * 返回空 = 输出不可解析（此时**什么都不做**，绝不当成"没有代理"去清属性——
     * 解析失败和用户关掉代理是两回事，混淆会把好端端的代理配置抹掉）。
     */
    static Optional<Endpoint> parseScutil(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String enable = field(raw, "HTTPSEnable");
        if (enable == null) return Optional.empty();
        if (!"1".equals(enable)) return Optional.of(Endpoint.NONE);
        String host = field(raw, "HTTPSProxy");
        int port = parsePort(field(raw, "HTTPSPort"));
        if (host == null || port <= 0) return Optional.of(Endpoint.NONE);
        return Optional.of(new Endpoint(host, port));
    }

    private static String field(String raw, String key) {
        for (String line : raw.split("\\R")) {
            String s = line.trim();
            if (!s.startsWith(key)) continue;
            int colon = s.indexOf(':');
            if (colon < 0) continue;
            // 前缀匹配要防 HTTPSPort 命中 HTTPSProxy：键名后必须紧跟空白或冒号
            String name = s.substring(0, colon).trim();
            if (!name.equals(key)) continue;
            return s.substring(colon + 1).trim();
        }
        return null;
    }
}
