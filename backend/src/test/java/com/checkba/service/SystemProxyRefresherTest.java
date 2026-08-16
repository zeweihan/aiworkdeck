package com.checkba.service;

import com.checkba.service.SystemProxyRefresher.Endpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地代理端口漂移自愈。
 *
 * <p>病灶：macOS 的 JVM 启动时会把系统代理灌进 http(s).proxy* 属性并**冻住**，
 * 桌面后端是长命 JVM，代理软件换端口后（实测 1235 -> 8234）就一直拨死端口，
 * 每个 AI 请求都 ConnectException。
 */
class SystemProxyRefresherTest {

    private static final String[] KEYS = {
            "https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort"};

    private final Map<String, String> saved = new HashMap<>();

    /** 系统属性是全局状态：用例必须自己存档/还原，否则会污染同一 JVM 里的其它用例。 */
    @BeforeEach
    void save() {
        for (String k : KEYS) saved.put(k, System.getProperty(k));
    }

    @AfterEach
    void restore() {
        for (String k : KEYS) {
            String v = saved.get(k);
            if (v == null) System.clearProperty(k); else System.setProperty(k, v);
        }
    }

    private static void setJvmProxy(String host, String port) {
        System.setProperty("https.proxyHost", host);
        System.setProperty("https.proxyPort", port);
    }

    // ==================== 解析 ====================

    /** 本机 `scutil --proxy` 的真实输出（2026-08-16 采集） */
    private static final String REAL_OUTPUT = """
            <dictionary> {
              ExceptionsList : <array> {
                0 : 127.0.0.1
                1 : localhost
              }
              FTPPassive : 1
              HTTPEnable : 1
              HTTPPort : 8234
              HTTPProxy : 127.0.0.1
              HTTPSEnable : 1
              HTTPSPort : 8234
              HTTPSProxy : 127.0.0.1
              ProxyAutoConfigEnable : 0
              SOCKSEnable : 0
            }
            """;

    @Test
    @DisplayName("解析真实 scutil 输出，取 HTTPS 那组")
    void parsesRealScutilOutput() {
        Endpoint e = SystemProxyRefresher.parseScutil(REAL_OUTPUT).orElseThrow();
        assertEquals("127.0.0.1", e.host());
        assertEquals(8234, e.port());
    }

    @Test
    @DisplayName("HTTPSPort 不能被 HTTPSProxy 的前缀匹配串味")
    void doesNotConfusePortWithProxyKey() {
        // 键名前缀相同（HTTPSP...），松散的 startsWith 会把 HTTPSProxy 的值当端口读
        Endpoint e = SystemProxyRefresher.parseScutil(REAL_OUTPUT).orElseThrow();
        assertEquals(8234, e.port(), "端口被相邻键串味了");
    }

    @Test
    @DisplayName("系统关掉代理 -> NONE（而不是解析失败）")
    void proxyDisabledYieldsNone() {
        Endpoint e = SystemProxyRefresher.parseScutil(
                "<dictionary> {\n  HTTPSEnable : 0\n  SOCKSEnable : 0\n}").orElseThrow();
        assertFalse(e.present());
    }

    @Test
    @DisplayName("输出不可解析 -> 空 Optional，绝不能当成「没有代理」")
    void unparseableYieldsEmpty() {
        // 这条是安全红线：解析失败若被当成 NONE，就会把用户好端端的代理配置抹掉
        assertTrue(SystemProxyRefresher.parseScutil("").isEmpty());
        assertTrue(SystemProxyRefresher.parseScutil("command not found").isEmpty());
        assertTrue(SystemProxyRefresher.parseScutil(null).isEmpty());
    }

    // ==================== 启用判定 ====================

    @Test
    @DisplayName("只在 macOS + 回环代理时启用")
    void enabledOnlyForLoopbackProxyOnMac() {
        assertTrue(SystemProxyRefresher.resolveEnabled("Mac OS X", new Endpoint("127.0.0.1", 1235)));
        assertTrue(SystemProxyRefresher.resolveEnabled("Mac OS X", new Endpoint("localhost", 7890)));

        // 企业代理端口稳定，去动它只有风险没有收益
        assertFalse(SystemProxyRefresher.resolveEnabled("Mac OS X", new Endpoint("proxy.corp.com", 8080)));
        // 没继承到代理就没有漂移问题
        assertFalse(SystemProxyRefresher.resolveEnabled("Mac OS X", Endpoint.NONE));
        // 其它系统的 JVM 不会自动继承系统代理，本类无事可做
        assertFalse(SystemProxyRefresher.resolveEnabled("Windows 11", new Endpoint("127.0.0.1", 1235)));
        assertFalse(SystemProxyRefresher.resolveEnabled(null, new Endpoint("127.0.0.1", 1235)));
    }

    // ==================== 应用 ====================

    @Test
    @DisplayName("端口漂了就地更新，http/https 两组一起改")
    void updatesBothSchemesWhenPortDrifts() {
        SystemProxyRefresher r = new SystemProxyRefresher();
        setJvmProxy("127.0.0.1", "1235");

        assertTrue(r.applyIfChanged(new Endpoint("127.0.0.1", 8234)));

        assertEquals("8234", System.getProperty("https.proxyPort"));
        assertEquals("8234", System.getProperty("http.proxyPort"));
        assertEquals("127.0.0.1", System.getProperty("http.proxyHost"));
    }

    @Test
    @DisplayName("没变就不动手（每分钟跑一次，不能每次都写属性刷日志）")
    void noOpWhenUnchanged() {
        SystemProxyRefresher r = new SystemProxyRefresher();
        setJvmProxy("127.0.0.1", "8234");

        assertFalse(r.applyIfChanged(new Endpoint("127.0.0.1", 8234)));
    }

    @Test
    @DisplayName("系统关掉代理就清属性改直连，而不是留着旧端口继续撞")
    void clearsPropertiesWhenProxyTurnedOff() {
        SystemProxyRefresher r = new SystemProxyRefresher();
        setJvmProxy("127.0.0.1", "1235");

        assertTrue(r.applyIfChanged(Endpoint.NONE));

        assertNull(System.getProperty("https.proxyHost"));
        assertNull(System.getProperty("https.proxyPort"));
        assertNull(System.getProperty("http.proxyHost"));
    }

    @Test
    @DisplayName("本来就没代理、系统也没代理 -> 无操作")
    void noOpWhenNeitherSideHasProxy() {
        SystemProxyRefresher r = new SystemProxyRefresher();
        for (String k : KEYS) System.clearProperty(k);

        assertFalse(r.applyIfChanged(Endpoint.NONE));
    }

    // ==================== 端到端（真实 ProxySelector）====================

    @Test
    @DisplayName("更新属性后 ProxySelector 立即改选路——本类成立的前提")
    void proxySelectorPicksUpChangeWithoutRestart() throws Exception {
        // java.net.useSystemProxies 在类初始化时就固化，运行期开无效；
        // 而 DefaultProxySelector.select() 每次重读系统属性，所以 setProperty 立刻生效。
        // 这条前提要是哪天不成立了，本类整个失去意义，必须由用例守住。
        SystemProxyRefresher r = new SystemProxyRefresher();
        java.net.URI target = java.net.URI.create("https://openrouter.ai/api/v1");
        setJvmProxy("127.0.0.1", "1235");
        assertTrue(java.net.ProxySelector.getDefault().select(target).toString().contains("1235"));

        r.applyIfChanged(new Endpoint("127.0.0.1", 8234));

        assertTrue(java.net.ProxySelector.getDefault().select(target).toString().contains("8234"),
                "改了属性但选路没跟上，端口自愈整个是死的");
    }
}
