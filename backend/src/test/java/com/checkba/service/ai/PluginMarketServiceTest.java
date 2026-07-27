package com.checkba.service.ai;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PluginMarketService 测试：签名验证（与官网 lib/plugin-signing.ts 的 canonical
 * 形式必须逐字节一致）、哈希校验、路径逃逸防护、安装后默认禁用。
 */
class PluginMarketServiceTest {

    @TempDir
    Path pluginsDir;

    private KeyPair keyPair;
    private String publicKeyPem;
    private final Map<String, String> settingStore = new HashMap<>();
    private PluginService pluginService;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";

        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), anyString())).thenAnswer(inv ->
                settingStore.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        doAnswer(inv -> settingStore.put(inv.getArgument(0), inv.getArgument(1)))
                .when(settings).set(anyString(), anyString());
        pluginService = new PluginService(settings, pluginsDir.toString());
    }

    /** 与官网 canonicalPayload() 相同的规则：顶层 files<id<publishedAt<version，files 内键排序 */
    private String canonical(String id, String version, String publishedAt, Map<String, String> files) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("files", new TreeMap<>(files));
        payload.put("id", id);
        payload.put("publishedAt", publishedAt);
        payload.put("version", version);
        return cn.hutool.json.JSONUtil.toJsonStr(payload);
    }

    private String sign(String canonical) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(keyPair.getPrivate());
        s.update(canonical.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private PluginMarketService service(String pubKey) {
        return new PluginMarketService("http://registry.test/plugins", pubKey, pluginsDir.toString(), pluginService);
    }

    @Test
    @DisplayName("签名验证：正确签名通过，篡改任一字段即失败")
    void signatureVerification() throws Exception {
        Map<String, String> files = new TreeMap<>();
        files.put("manifest.json", "aa11");
        files.put("tool.jar", "bb22");
        String sig = sign(canonical("demo", "1.0.0", "2026-07-27T00:00:00Z", files));

        PluginMarketService svc = service(publicKeyPem);
        assertTrue(svc.verifySignature("demo", "1.0.0", "2026-07-27T00:00:00Z", files, sig));

        assertFalse(svc.verifySignature("other", "1.0.0", "2026-07-27T00:00:00Z", files, sig), "改 id 应失败");
        assertFalse(svc.verifySignature("demo", "9.9.9", "2026-07-27T00:00:00Z", files, sig), "改版本应失败");
        assertFalse(svc.verifySignature("demo", "1.0.0", "2026-01-01T00:00:00Z", files, sig), "改时间应失败");

        Map<String, String> tampered = new TreeMap<>(files);
        tampered.put("tool.jar", "deadbeef");
        assertFalse(svc.verifySignature("demo", "1.0.0", "2026-07-27T00:00:00Z", tampered, sig), "改文件哈希应失败");
    }

    @Test
    @DisplayName("files 键的顺序不影响验签（两端都按字典序重建）")
    void fileOrderIndependent() throws Exception {
        Map<String, String> sorted = new TreeMap<>();
        sorted.put("a.jar", "1");
        sorted.put("manifest.json", "2");
        String sig = sign(canonical("demo", "1.0.0", "T", sorted));

        // 用插入顺序相反的 map 验签，结果应相同
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("manifest.json", "2");
        reversed.put("a.jar", "1");
        assertTrue(service(publicKeyPem).verifySignature("demo", "1.0.0", "T", new TreeMap<>(reversed), sig));
    }

    @Test
    @DisplayName("未配置公钥时拒绝安装")
    void refuseInstallWithoutPublicKey() {
        PluginMarketService svc = service("");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.install("demo"));
        assertTrue(e.getMessage().contains("公钥"));
    }

    @Test
    @DisplayName("非法插件 id 被拒绝")
    void rejectInvalidId() {
        PluginMarketService svc = service(publicKeyPem);
        assertThrows(IllegalArgumentException.class, () -> svc.install("../evil"));
        assertThrows(IllegalArgumentException.class, () -> svc.install("UPPER"));
        assertThrows(IllegalArgumentException.class, () -> svc.uninstall("a/b"));
    }

    @Test
    @DisplayName("清单里的路径逃逸被拒绝")
    void unsafePathsRejected() {
        assertFalse(PluginMarketService.isSafeRelPath("../evil.jar"));
        assertFalse(PluginMarketService.isSafeRelPath("/etc/passwd"));
        assertFalse(PluginMarketService.isSafeRelPath("a\\b.jar"));
        assertFalse(PluginMarketService.isSafeRelPath("C:/x.jar"));
        assertFalse(PluginMarketService.isSafeRelPath("a/../../b"));
        assertTrue(PluginMarketService.isSafeRelPath("manifest.json"));
        assertTrue(PluginMarketService.isSafeRelPath("lib/tool.jar"));
    }

    @Test
    @DisplayName("安装：验签与逐文件哈希校验通过后落盘，且插件默认禁用")
    void installHappyPath() throws Exception {
        String manifest = "{\"id\":\"demo\",\"name\":\"演示\",\"version\":\"1.0.0\",\"backendJars\":[\"tool.jar\"]}";
        byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
        byte[] jarBytes = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x01};

        Map<String, String> files = new TreeMap<>();
        files.put("manifest.json", PluginMarketService.sha256Hex(manifestBytes));
        files.put("tool.jar", PluginMarketService.sha256Hex(jarBytes));
        String sig = sign(canonical("demo", "1.0.0", "2026-07-27T00:00:00Z", files));

        PluginMarketService svc = stubbed(publicKeyPem, files, sig, manifestBytes, jarBytes);
        assertEquals("demo", svc.install("demo"));

        assertTrue(Files.exists(pluginsDir.resolve("demo").resolve("manifest.json")));
        assertArrayEquals(jarBytes, Files.readAllBytes(pluginsDir.resolve("demo").resolve("tool.jar")));
        assertFalse(pluginService.isEnabled("demo"), "在线安装的插件必须默认禁用，等用户确认");
        assertTrue(pluginService.getPluginTools().isEmpty(), "禁用状态下不应加载 JAR 注册工具");
    }

    @Test
    @DisplayName("安装：文件内容与签名清单不符时中止且不落盘")
    void installAbortsOnHashMismatch() throws Exception {
        byte[] manifestBytes = "{\"id\":\"demo\",\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> files = new TreeMap<>();
        files.put("manifest.json", PluginMarketService.sha256Hex(manifestBytes));
        String sig = sign(canonical("demo", "1.0.0", "2026-07-27T00:00:00Z", files));

        // 下载到的内容与清单声明的哈希不符（模拟传输中被替换）
        PluginMarketService svc = stubbed(publicKeyPem, files, sig,
                "tampered".getBytes(StandardCharsets.UTF_8), null);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.install("demo"));
        assertTrue(e.getMessage().contains("校验失败"));
        assertFalse(Files.exists(pluginsDir.resolve("demo")), "校验失败不得留下半成品");
    }

    @Test
    @DisplayName("安装：签名无效时中止，不发起任何文件下载")
    void installAbortsOnBadSignature() throws Exception {
        Map<String, String> files = new TreeMap<>();
        files.put("manifest.json", "00");
        String badSig = Base64.getEncoder().encodeToString(new byte[64]);

        boolean[] downloaded = {false};
        PluginMarketService svc = new PluginMarketService(
                "http://registry.test/plugins", publicKeyPem, pluginsDir.toString(), pluginService) {
            @Override
            protected String httpGet(String url) {
                return bundleJson("demo", "1.0.0", "2026-07-27T00:00:00Z", files, badSig);
            }
            @Override
            protected byte[] httpGetBytes(String url) {
                downloaded[0] = true;
                return new byte[0];
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.install("demo"));
        assertTrue(e.getMessage().contains("签名验证失败"));
        assertFalse(downloaded[0], "验签失败必须在下载任何文件之前中止");
    }

    @Test
    @DisplayName("封禁的插件不允许被重新启用")
    void revokedPluginCannotBeEnabled() throws Exception {
        Path dir = pluginsDir.resolve("bad");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{\"id\":\"bad\",\"name\":\"坏插件\"}");
        pluginService.init();
        assertTrue(pluginService.isEnabled("bad"));

        pluginService.applyRevocations(Map.of("bad", "窃取凭据"));
        assertFalse(pluginService.isEnabled("bad"), "封禁应强制禁用");
        assertEquals("窃取凭据", pluginService.revokedReason("bad"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> pluginService.setEnabled("bad", true));
        assertTrue(e.getMessage().contains("已被平台下架"));
    }

    @Test
    @DisplayName("语义化版本比较用于判断可更新")
    void semverCompare() {
        assertTrue(PluginMarketService.compareSemver("1.0.1", "1.0.0") > 0);
        assertTrue(PluginMarketService.compareSemver("1.2.0", "1.10.0") < 0);
        assertEquals(0, PluginMarketService.compareSemver("2.0.0", "2.0.0"));
        assertTrue(PluginMarketService.compareSemver("1.0.0", null) > 0);
    }

    private static String bundleJson(String id, String version, String publishedAt,
                                     Map<String, String> files, String sig) {
        cn.hutool.json.JSONObject o = new cn.hutool.json.JSONObject();
        o.set("id", id).set("version", version).set("publishedAt", publishedAt)
                .set("files", files).set("signature", sig);
        return o.toString();
    }

    // ==== 生产公钥回归 ====

    /**
     * application.yml 里配置的生产公钥，与官网服务器 AWD_PLUGIN_SIGNING_KEY 成对。
     * 上面那些用例用临时密钥对验的是算法与 canonical 形式；这一条钉的是
     * "线上这把公钥确实能验开线上私钥签出来的名"——配错或轮换失误时直接红。
     */
    private static final String PROD_PUBLIC_KEY = """
            -----BEGIN PUBLIC KEY-----
            MCowBQYDK2VwAyEAjAbnyl44SiQF/CTyn59/uHAXCeRTEI0h0Bn5HEv7T4Y=
            -----END PUBLIC KEY-----
            """;

    /** 由配对私钥用 Node 的 crypto.sign 对下方载荷签出，固化在此做跨语言对拍 */
    private static final String PROD_SIGNATURE =
            "XXoQuS5ogi3F/uhnSZtB0gPgiWCAjFmgqAVRSgyr+0GONi825hoo+8d2fPzBl0OSOdqAF2mGzDJhLnOryg3SCg==";

    @Test
    @DisplayName("生产公钥能验开官网私钥签出的真实签名（跨语言对拍）")
    void productionKeyVerifiesRealSignature() throws Exception {
        Map<String, String> files = new TreeMap<>();
        files.put("manifest.json", "a".repeat(64));
        files.put("tool.jar", "b".repeat(64));

        PluginMarketService svc = service(PROD_PUBLIC_KEY);
        assertTrue(
                svc.verifySignature("demo-plugin", "1.0.0", "2026-07-27T00:00:00.000Z", files, PROD_SIGNATURE),
                "application.yml 的公钥与官网签名私钥不配对——检查是否漏配或轮换未同步");

        // 同一把公钥必须拒绝被篡改的载荷，排除"恒真"式的假通过
        assertFalse(svc.verifySignature("evil-plugin", "1.0.0", "2026-07-27T00:00:00.000Z", files, PROD_SIGNATURE));
    }

    @Test
    @DisplayName("application.yml 中确实配置了公钥（防止合并时被清空）")
    void applicationYmlHasPublicKeyConfigured() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertTrue(yml.contains("BEGIN PUBLIC KEY"),
                "ai.plugins.registry-public-key 为空会让所有在线插件安装被拒绝");
    }

    /** 打桩 HTTP：bundle 返回给定清单，file 按路径返回对应内容 */
    private PluginMarketService stubbed(String pubKey, Map<String, String> files, String sig,
                                        byte[] manifestBytes, byte[] jarBytes) {
        return new PluginMarketService("http://registry.test/plugins", pubKey, pluginsDir.toString(), pluginService) {
            @Override
            protected String httpGet(String url) {
                return bundleJson("demo", "1.0.0", "2026-07-27T00:00:00Z", files, sig);
            }
            @Override
            protected byte[] httpGetBytes(String url) {
                return url.contains("tool.jar") ? jarBytes : manifestBytes;
            }
        };
    }
}
