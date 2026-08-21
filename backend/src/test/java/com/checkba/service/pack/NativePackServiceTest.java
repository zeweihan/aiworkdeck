package com.checkba.service.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NativePackService 单测（规范 docs/NATIVE_PACK_DISTRIBUTION.md §9）：
 * manifest 验签正/负/未配公钥、平台过滤、HTTP Range 断点续传、哈希不符换源重试、
 * 解压四重防护、contents.sha256 复核失败回滚、幂等重装、卸载守卫、封禁后资源不可见。
 *
 * 网络这一层用 com.sun.net.httpserver 起本地桩（支持 Range），不打真实网络。
 */
class NativePackServiceTest {

    private static final String PACK_ID = "litigation-visual";
    private static final String VERSION = "1.0.0";

    @TempDir
    Path tempDir;

    private KeyPair keyPair;
    private String publicKeyPem;
    private StubMirror primary;
    private StubMirror secondary;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        keyPair = gen.generateKeyPair();
        publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        primary = new StubMirror();
        secondary = new StubMirror();
    }

    @AfterEach
    void tearDown() {
        if (primary != null) primary.stop();
        if (secondary != null) secondary.stop();
    }

    // ==================== 验签 ====================

    @Test
    @DisplayName("签名正确时安装成功，资源目录可解析")
    void installsWhenSignatureValid() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "print(1)".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        assertEquals(VERSION, svc.install(PACK_ID));

        assertTrue(svc.isReady(PACK_ID));
        Path dir = svc.componentDir(PACK_ID, "litviz").orElseThrow();
        assertEquals("print(1)", Files.readString(dir.resolve("cli.py")));
        assertEquals(NativePackService.STATE_READY, svc.status(PACK_ID).getState());
        // 半成品工作区装完即清
        assertFalse(Files.exists(packsRoot().resolve(".staging").resolve(PACK_ID + "-" + VERSION)));
    }

    @Test
    @DisplayName("签名不符即中止，不落任何文件")
    void refusesWhenSignatureInvalid() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "print(1)".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), false);

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.install(PACK_ID));
        assertTrue(e.getMessage().contains("签名") || e.getMessage().contains("signature"), e.getMessage());
        assertFalse(svc.isReady(PACK_ID));
        assertFalse(Files.exists(packsRoot().resolve(PACK_ID)));
        assertEquals(NativePackService.STATE_FAILED, svc.status(PACK_ID).getState());
    }

    @Test
    @DisplayName("未配置公钥 = 拒绝一切安装（与 JAR 插件同语义）")
    void refusesWhenPublicKeyMissing() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);

        NativePackService svc = service("", primary.baseUrl());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.install(PACK_ID));
        assertTrue(e.getMessage().contains("registry-public-key"), e.getMessage());
        // 一个字节都不该下
        assertTrue(primary.requests.stream().noneMatch(r -> r.path().endsWith(".tar.gz")));
    }

    // ==================== 平台过滤 ====================

    @Test
    @DisplayName("只装「匹配自身平台 ∪ *」的组件")
    void filtersComponentsByPlatform() throws Exception {
        byte[] shared = tarGzWithContents(Map.of("cli.py", "shared".getBytes(StandardCharsets.UTF_8)));
        byte[] mac = tarGzWithContents(Map.of("bin/dot", "mac".getBytes(StandardCharsets.UTF_8)));
        byte[] win = tarGzWithContents(Map.of("bin/dot.exe", "win".getBytes(StandardCharsets.UTF_8)));

        List<Map<String, Object>> components = List.of(
                component("litviz", List.of("*"), "litviz.tar.gz", shared, "litviz"),
                component("graphviz", List.of("mac-arm64"), "graphviz-mac.tar.gz", mac, "graphviz"),
                component("graphviz", List.of("win-x64"), "graphviz-win.tar.gz", win, "graphviz"));
        publishManifest(primary, components, true, "0.1.0", 1, 1);
        primary.files.put("/" + PACK_ID + "/" + VERSION + "/litviz.tar.gz", shared);
        primary.files.put("/" + PACK_ID + "/" + VERSION + "/graphviz-mac.tar.gz", mac);
        primary.files.put("/" + PACK_ID + "/" + VERSION + "/graphviz-win.tar.gz", win);

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        svc.install(PACK_ID);

        assertEquals("mac", Files.readString(svc.componentDir(PACK_ID, "graphviz").orElseThrow().resolve("bin/dot")));
        // win 组件不该被下载
        assertTrue(primary.requests.stream().noneMatch(r -> r.path().endsWith("graphviz-win.tar.gz")));
        // info 的体积也只算本平台
        assertEquals(shared.length + mac.length, svc.info(PACK_ID).totalSize());
    }

    // ==================== 断点续传 ====================

    @Test
    @DisplayName("本地已有 .part 时带 Range 续传，最终 sha256 仍正确")
    void resumesWithRangeHeader() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "0123456789".repeat(500).getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);

        // 预置一段真实前缀，模拟上次下到一半被网络掐断
        int have = archive.length / 3;
        Path part = packsRoot().resolve(".staging").resolve(PACK_ID + "-" + VERSION).resolve("litviz.tar.gz.part");
        Files.createDirectories(part.getParent());
        Files.write(part, java.util.Arrays.copyOf(archive, have));

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        svc.install(PACK_ID);

        StubMirror.Request archiveReq = primary.requests.stream()
                .filter(r -> r.path().endsWith(".tar.gz")).findFirst().orElseThrow();
        assertEquals("bytes=" + have + "-", archiveReq.range());
        assertEquals(1, primary.rangeServed.size());
        assertEquals("0123456789".repeat(500),
                Files.readString(svc.componentDir(PACK_ID, "litviz").orElseThrow().resolve("cli.py")));
    }

    @Test
    @DisplayName("越界 Range 收到 416 时清空 .part 并在本轮重试里自愈，不必等用户再点一次安装")
    void selfHealsAfterOutOfRange416() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "print(1)".getBytes(StandardCharsets.UTF_8)));
        // manifest 声明的 size 比实际压缩包大（历史遗留 stale size 的现实场景），这样
        // 构造性防御（.part >= 声明 size 才预先清空）不会拦下它，真实走到 416 分支。
        Map<String, Object> comp = component("litviz", List.of("*"), "litviz.tar.gz", archive, "litviz",
                archive.length + 1000);
        publishManifest(primary, List.of(comp), true, "0.1.0", 1, 1);
        primary.files.put("/" + PACK_ID + "/" + VERSION + "/litviz.tar.gz", archive);

        // 本地已有一份"完整"的 .part（等于真实内容长度，但比声明 size 小）——下一次续传
        // 请求的 Range 起点 == 真实内容长度，服务器如实回 416（线上事故复现的确切分支）。
        Path part = packsRoot().resolve(".staging").resolve(PACK_ID + "-" + VERSION).resolve("litviz.tar.gz.part");
        Files.createDirectories(part.getParent());
        Files.write(part, archive);

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        // 单次 install() 调用即可自愈：不是「首次失败、用户再点一次才成功」
        assertEquals(VERSION, svc.install(PACK_ID));

        assertTrue(svc.isReady(PACK_ID));
        assertEquals("print(1)",
                Files.readString(svc.componentDir(PACK_ID, "litviz").orElseThrow().resolve("cli.py")));
        // 服务器确实回过一次 416（证明真的走了这个分支，不是被构造性防御绕过）
        assertTrue(primary.requests.stream().anyMatch(r -> r.range() != null
                && r.range().equals("bytes=" + archive.length + "-")));
        // .part 没有作为「网络中断」残留下来（此次跑完 staging 应已清空）
        assertFalse(Files.exists(part));
    }

    // ==================== 哈希不符换源 ====================

    @Test
    @DisplayName("下载内容哈希不符时删产物、换下一个源重试")
    void retriesOnNextSourceWhenHashMismatches() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "good".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);
        publish(secondary, archive, "litviz", List.of("*"), true);
        // 主源的压缩包被掉包（manifest 与签名仍正确）
        byte[] tampered = tarGzWithContents(Map.of("cli.py", "evil".getBytes(StandardCharsets.UTF_8)));
        primary.files.put("/" + PACK_ID + "/" + VERSION + "/litviz.tar.gz", tampered);

        NativePackService svc = service(publicKeyPem, primary.baseUrl(), secondary.baseUrl());
        svc.install(PACK_ID);

        assertEquals("good", Files.readString(svc.componentDir(PACK_ID, "litviz").orElseThrow().resolve("cli.py")));
        assertTrue(primary.requests.stream().anyMatch(r -> r.path().endsWith(".tar.gz")));
        assertTrue(secondary.requests.stream().anyMatch(r -> r.path().endsWith(".tar.gz")));
    }

    // ==================== 解压防护 ====================

    @Test
    @DisplayName("解压拒绝软链条目")
    void rejectsSymlinkEntry() throws Exception {
        byte[] bad = tarGz(tar -> tar.symlink("evil", "/etc/passwd"));
        assertExtractRejected(bad, "链接");
    }

    @Test
    @DisplayName("解压拒绝绝对路径条目")
    void rejectsAbsolutePathEntry() throws Exception {
        byte[] bad = tarGz(tar -> tar.file("/etc/evil", "x".getBytes(StandardCharsets.UTF_8)));
        assertExtractRejected(bad, "非法路径");
    }

    @Test
    @DisplayName("解压拒绝含 .. 的条目（zip-slip）")
    void rejectsParentTraversalEntry() throws Exception {
        byte[] bad = tarGz(tar -> tar.file("../evil", "x".getBytes(StandardCharsets.UTF_8)));
        assertExtractRejected(bad, "非法路径");
    }

    @Test
    @DisplayName("解压拒绝条目数超限的压缩包")
    void rejectsTooManyEntries() throws Exception {
        byte[] bad = tarGz(tar -> {
            for (int i = 0; i <= 5001; i++) {
                tar.file("f" + i, new byte[]{1});
            }
        });
        assertExtractRejected(bad, "条目数");
    }

    // ==================== contents.sha256 复核 ====================

    @Test
    @DisplayName("包内 contents.sha256 复核失败即回滚，不留版本目录与指针")
    void rollsBackWhenContentsListMismatches() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("cli.py", "real".getBytes(StandardCharsets.UTF_8));
        byte[] archive = tarGz(tar -> {
            tar.file("cli.py", files.get("cli.py"));
            // 清单里写的是另一份内容的哈希
            String line = sha256Hex("tampered".getBytes(StandardCharsets.UTF_8)) + "  cli.py\n";
            tar.file(CONTENTS, line.getBytes(StandardCharsets.UTF_8));
        });
        publish(primary, archive, "litviz", List.of("*"), true);

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.install(PACK_ID));
        assertTrue(e.getMessage().contains("文件校验失败") || e.getMessage().contains("File verification failed"), e.getMessage());
        assertFalse(svc.isReady(PACK_ID));
        assertFalse(Files.exists(packsRoot().resolve(PACK_ID).resolve(VERSION)));
        assertFalse(Files.exists(packsRoot().resolve(PACK_ID).resolve("current.json")));
    }

    // ==================== 进度跨线程可见性 ====================

    @Test
    @DisplayName("PackStatus 字段全部 volatile：写者是安装用执行器线程，读者是任意一条处理 /status 的请求线程")
    void packStatusFieldsAreVolatile() throws Exception {
        // 2026-08-20 排查 app-e2e J13「下载进度卡在固定字节」时顺带发现的：真根因其实
        // 是 run.mjs 那侧一处未限定容器的 DOM 文本断言（详见 run.mjs J13 段注释），
        // 不是这里；但 PackStatus 的字段本来就该是 volatile（写者/读者分属不同线程，
        // 普通字段没有 happens-before 保证），顺手钉住这条契约，别被后续改动悄悄剥掉。
        for (String field : new String[] {"id", "state", "installedVersion", "bytesDownloaded", "bytesTotal", "error"}) {
            java.lang.reflect.Field f = NativePackService.PackStatus.class.getDeclaredField(field);
            assertTrue(java.lang.reflect.Modifier.isVolatile(f.getModifiers()), "PackStatus." + field + " 应为 volatile");
        }
    }

    // ==================== 幂等 / 卸载 / 封禁 ====================

    @Test
    @DisplayName("已就绪的版本重装不再下载任何字节")
    void reinstallIsIdempotent() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        svc.install(PACK_ID);
        long firstCount = primary.requests.stream().filter(r -> r.path().endsWith(".tar.gz")).count();
        svc.install(PACK_ID);
        long secondCount = primary.requests.stream().filter(r -> r.path().endsWith(".tar.gz")).count();

        assertEquals(1, firstCount);
        assertEquals(firstCount, secondCount);
        assertTrue(svc.isReady(PACK_ID));
    }

    @Test
    @DisplayName("卸载删掉整个 pack 目录；非法 id 被守卫挡下")
    void uninstallRemovesPackAndGuardsId() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        svc.install(PACK_ID);
        assertThrows(IllegalArgumentException.class, () -> svc.uninstall("../evil"));
        assertThrows(IllegalArgumentException.class, () -> svc.uninstall("/etc"));
        assertTrue(svc.isReady(PACK_ID));

        svc.uninstall(PACK_ID);
        assertFalse(svc.isReady(PACK_ID));
        assertFalse(Files.exists(packsRoot().resolve(PACK_ID)));
        assertTrue(svc.componentDir(PACK_ID, "litviz").isEmpty());
    }

    @Test
    @DisplayName("从未装成功时卸载不抛异常，只清 staging 残留")
    void uninstallNeverInstalledClearsStagingWithoutThrowing() throws Exception {
        // 模拟一次失败的安装尝试留下的残留：pack 目录从未落地，但 staging 里有 .part
        Path leftover = packsRoot().resolve(".staging").resolve(PACK_ID + "-" + VERSION)
                .resolve("litviz.tar.gz.part");
        Files.createDirectories(leftover.getParent());
        Files.write(leftover, new byte[]{1, 2, 3});

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        assertFalse(svc.isReady(PACK_ID));

        svc.uninstall(PACK_ID); // 不该抛异常——卸载是幂等的收口动作

        assertFalse(Files.exists(leftover));
        assertFalse(svc.isReady(PACK_ID));
        assertFalse(Files.exists(packsRoot().resolve(PACK_ID)));
    }

    @Test
    @DisplayName("修复：安装/封禁/卸载都会触发登记的状态变化回调，供资源消费方失效自己的运行时解析缓存")
    void notifiesRegisteredListenerOnInstallRevokeAndUninstall() throws Exception {
        // 病灶：LitigationVisualService.resolved 是懒加载且只算一次的缓存，此前
        // NativePackService 全仓没有任何生产调用点会碰 invalidate()——用户在广场点
        // 「安装/卸载」是不重启后端的 live 操作，装完 pack 面板仍显示上次探测出的「不可用」。
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);
        primary.files.put("/revoked", ("[{\"id\":\"" + PACK_ID + "\",\"version\":\"*\",\"reason\":\"test\"}]")
                .getBytes(StandardCharsets.UTF_8));

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger(0);
        svc.onPackChanged(PACK_ID, fired::incrementAndGet);

        svc.install(PACK_ID);
        assertEquals(1, fired.get(), "安装成功应触发一次回调");

        assertEquals(List.of(PACK_ID), svc.syncRevoked());
        assertEquals(2, fired.get(), "被平台封禁应再触发一次回调");

        svc.uninstall(PACK_ID);
        assertEquals(3, fired.get(), "卸载应再触发一次回调");
    }

    @Test
    @DisplayName("命中封禁表后资源解析视而不见，但本地文件不删")
    void revokedPackBecomesInvisible() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);
        primary.files.put("/revoked", ("[{\"id\":\"" + PACK_ID + "\",\"version\":\"*\",\"reason\":\"test\"}]")
                .getBytes(StandardCharsets.UTF_8));

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        svc.install(PACK_ID);
        assertTrue(svc.isReady(PACK_ID));

        assertEquals(List.of(PACK_ID), svc.syncRevoked());
        assertFalse(svc.isReady(PACK_ID));
        assertTrue(svc.componentDir(PACK_ID, "litviz").isEmpty());
        assertEquals(NativePackService.STATE_REVOKED, svc.status(PACK_ID).getState());
        // 不自动删本地文件（防误封丢数据）
        assertTrue(Files.exists(packsRoot().resolve(PACK_ID).resolve(VERSION).resolve("litviz").resolve("cli.py")));
    }

    @Test
    @DisplayName("被封禁的 pack 重装时不得把封禁位抹掉——平台仍在封时拒装")
    void reinstallDoesNotResurrectRevokedPack() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);
        primary.files.put("/revoked", ("[{\"id\":\"" + PACK_ID + "\",\"version\":\"*\",\"reason\":\"test\"}]")
                .getBytes(StandardCharsets.UTF_8));

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        svc.install(PACK_ID);
        assertEquals(List.of(PACK_ID), svc.syncRevoked());
        assertFalse(svc.isReady(PACK_ID));

        // 重启后的自动补下载 / 用户再点一次「安装」都会走到这里：
        // 目标版本已完整落盘，幂等分支只重写指针——绝不能顺手把 revoked 写回 false
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.install(PACK_ID));
        assertTrue(e.getMessage().contains("封禁") || e.getMessage().toLowerCase().contains("revoked"), e.getMessage());
        assertFalse(svc.isReady(PACK_ID), "平台仍在封禁，重装后资源不该重新可见");
        assertTrue(svc.componentDir(PACK_ID, "litviz").isEmpty());
        // 拒装不是「安装失败」：状态要如实停在 revoked，否则用户只会一遍遍重试
        assertEquals(NativePackService.STATE_REVOKED, svc.status(PACK_ID).getState());
    }

    @Test
    @DisplayName("平台撤销封禁后，重装可正常复活")
    void reinstallClearsRevokedFlagOncePlatformLiftsIt() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);
        primary.files.put("/revoked", ("[{\"id\":\"" + PACK_ID + "\",\"version\":\"*\",\"reason\":\"test\"}]")
                .getBytes(StandardCharsets.UTF_8));

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        svc.install(PACK_ID);
        svc.syncRevoked();
        assertFalse(svc.isReady(PACK_ID));

        // 平台把它从封禁表里撤了
        primary.files.put("/revoked", "[]".getBytes(StandardCharsets.UTF_8));
        assertEquals(VERSION, svc.install(PACK_ID));
        assertTrue(svc.isReady(PACK_ID));
        assertEquals(NativePackService.STATE_READY, svc.status(PACK_ID).getState());
    }

    @Test
    @DisplayName("封禁表拉不到时，被封禁的 pack 维持封禁（宁可装不上也不无声复活）")
    void reinstallRefusesWhenRevocationListUnreachable() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);
        primary.files.put("/revoked", ("[{\"id\":\"" + PACK_ID + "\",\"version\":\"*\",\"reason\":\"test\"}]")
                .getBytes(StandardCharsets.UTF_8));

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        svc.install(PACK_ID);
        svc.syncRevoked();

        // 端点没了（未部署 / 网络不通）
        primary.files.remove("/revoked");
        assertThrows(IllegalStateException.class, () -> svc.install(PACK_ID));
        assertFalse(svc.isReady(PACK_ID));
    }

    @Test
    @DisplayName("封禁端点 404 时静默跳过，不影响已装 pack")
    void revocationEndpointMissingIsSilent() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);

        NativePackService svc = service(publicKeyPem, primary.baseUrl());
        svc.install(PACK_ID);
        assertEquals(List.of(), svc.syncRevoked());
        assertTrue(svc.isReady(PACK_ID));
    }

    @Test
    @DisplayName("engineApi / minAppVersion 不满足时拒装")
    void refusesIncompatibleManifest() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        List<Map<String, Object>> components = List.of(
                component("litviz", List.of("*"), "litviz.tar.gz", archive, "litviz"));

        publishManifest(primary, components, true, "0.1.0", 2, 1);
        primary.files.put("/" + PACK_ID + "/" + VERSION + "/litviz.tar.gz", archive);
        NativePackService unknownApi = service(publicKeyPem, primary.baseUrl());
        assertTrue(assertThrows(IllegalStateException.class, () -> unknownApi.install(PACK_ID))
                .getMessage().contains("engineApi"));

        publishManifest(primary, components, true, "9.9.9", 1, 1);
        NativePackService tooOld = service(publicKeyPem, primary.baseUrl());
        assertTrue(assertThrows(IllegalStateException.class, () -> tooOld.install(PACK_ID))
                .getMessage().contains("9.9.9"));
    }

    @Test
    @DisplayName("ai.packs.enabled=false 时安装被旁路")
    void disabledBypassesInstall() throws Exception {
        byte[] archive = tarGzWithContents(Map.of("cli.py", "x".getBytes(StandardCharsets.UTF_8)));
        publish(primary, archive, "litviz", List.of("*"), true);

        PackProperties props = props(primary.baseUrl());
        props.setEnabled(false);
        NativePackService svc = new TestPackService(props, publicKeyPem, "0.21.0");
        assertThrows(IllegalStateException.class, () -> svc.install(PACK_ID));
        assertTrue(primary.requests.isEmpty());
    }

    // ==================== 脚手架 ====================

    private static final String CONTENTS = "contents.sha256";

    /** 平台固定为 mac-arm64，别让断言随构建机的 os.arch 变化 */
    private static class TestPackService extends NativePackService {
        TestPackService(PackProperties props, String pem, String appVersion) {
            super(props, pem, appVersion);
        }

        @Override
        protected String platform() {
            return "mac-arm64";
        }
    }

    private Path packsRoot() {
        return tempDir.resolve("packs").toAbsolutePath().normalize();
    }

    private PackProperties props(String... baseUrls) {
        PackProperties props = new PackProperties();
        props.setDir(packsRoot().toString());
        props.setBaseUrls(new ArrayList<>(List.of(baseUrls)));
        props.setRevokedUrl(baseUrls[0].replaceAll("/plugin-packs$", "") + "/revoked");
        return props;
    }

    private NativePackService service(String pem, String... baseUrls) {
        return new TestPackService(props(baseUrls), pem, "0.21.0");
    }

    /** 单组件 pack 的一站式发布（manifest + sig + 压缩包） */
    private void publish(StubMirror mirror, byte[] archive, String unpackDir,
                         List<String> platforms, boolean validSignature) throws Exception {
        List<Map<String, Object>> components = List.of(
                component("litviz", platforms, "litviz.tar.gz", archive, unpackDir));
        publishManifest(mirror, components, validSignature, "0.1.0", 1, 1);
        mirror.files.put("/" + PACK_ID + "/" + VERSION + "/litviz.tar.gz", archive);
    }

    private Map<String, Object> component(String name, List<String> platforms, String archiveName,
                                          byte[] archive, String unpackDir) {
        return component(name, platforms, archiveName, archive, unpackDir, archive.length);
    }

    /** 声明大小可与实际压缩包不同——用于构造「本地 .part 未达声明大小但已达真实内容长度」的场景 */
    private Map<String, Object> component(String name, List<String> platforms, String archiveName,
                                          byte[] archive, String unpackDir, long declaredSize) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("platforms", platforms);
        c.put("archive", archiveName);
        c.put("size", declaredSize);
        c.put("sha256", sha256Hex(archive));
        c.put("unpackDir", unpackDir);
        return c;
    }

    private void publishManifest(StubMirror mirror, List<Map<String, Object>> components,
                                 boolean validSignature, String minAppVersion,
                                 int engineApi, int schema) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schema\":").append(schema)
                .append(",\"id\":\"").append(PACK_ID).append('"')
                .append(",\"version\":\"").append(VERSION).append('"')
                .append(",\"publishedAt\":\"2026-08-20T00:00:00Z\"")
                .append(",\"minAppVersion\":\"").append(minAppVersion).append('"')
                .append(",\"engineApi\":").append(engineApi)
                .append(",\"components\":[");
        for (int i = 0; i < components.size(); i++) {
            Map<String, Object> c = components.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(c.get("name")).append('"')
                    .append(",\"platforms\":[");
            @SuppressWarnings("unchecked")
            List<String> platforms = (List<String>) c.get("platforms");
            for (int p = 0; p < platforms.size(); p++) {
                if (p > 0) sb.append(',');
                sb.append('"').append(platforms.get(p)).append('"');
            }
            sb.append("],\"archive\":\"").append(c.get("archive")).append('"')
                    .append(",\"size\":").append(c.get("size"))
                    .append(",\"sha256\":\"").append(c.get("sha256")).append('"')
                    .append(",\"unpackDir\":\"").append(c.get("unpackDir")).append("\"}");
        }
        sb.append("]}");

        byte[] manifest = sb.toString().getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(validSignature ? manifest : "not-this-manifest".getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(signer.sign());

        mirror.files.put("/" + PACK_ID + "/manifest.json", manifest);
        mirror.files.put("/" + PACK_ID + "/manifest.json.sig", sig.getBytes(StandardCharsets.UTF_8));
    }

    private void assertExtractRejected(byte[] archive, String expectedFragment) throws IOException {
        Path file = tempDir.resolve("bad-" + System.nanoTime() + ".tar.gz");
        Files.write(file, archive);
        NativePackService svc = service(publicKeyPem, "https://example.invalid/plugin-packs");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.extract(file, tempDir.resolve("out-" + System.nanoTime())));
        assertTrue(e.getMessage().contains(expectedFragment), e.getMessage());
    }

    // ---------- tar.gz 构造 ----------

    private static byte[] tarGz(java.util.function.Consumer<TarBuilder> writer) throws IOException {
        TarBuilder tar = new TarBuilder();
        writer.accept(tar);
        return tar.gzip();
    }

    /** 常规组件包：内容文件 + 自动生成的 contents.sha256 */
    private static byte[] tarGzWithContents(Map<String, byte[]> files) throws IOException {
        Map<String, byte[]> ordered = new LinkedHashMap<>(files);
        return tarGz(tar -> {
            StringBuilder list = new StringBuilder();
            for (Map.Entry<String, byte[]> e : ordered.entrySet()) {
                tar.file(e.getKey(), e.getValue());
                list.append(sha256Hex(e.getValue())).append("  ").append(e.getKey()).append('\n');
            }
            tar.file(CONTENTS, list.toString().getBytes(StandardCharsets.UTF_8));
        });
    }

    /**
     * 极简 ustar 写入器。
     *
     * <p>不用 commons-compress 的 {@code TarArchiveOutputStream}：它的写入端依赖
     * commons-lang3 3.14+（ArrayFill），而 Spring Boot BOM 把 lang3 锁在 3.13，
     * 一写就 NoClassDefFoundError。读取端（被测代码走的那条路）不受影响，
     * 所以只在测试里自己拼 512 字节头，别为了造测试数据去动生产依赖的版本。
     */
    private static class TarBuilder {
        private final ByteArrayOutputStream raw = new ByteArrayOutputStream();

        void file(String name, byte[] data) {
            entry(name, data, '0', "", 0644);
        }

        void symlink(String name, String target) {
            entry(name, new byte[0], '2', target, 0777);
        }

        private void entry(String name, byte[] data, char type, String linkName, int mode) {
            byte[] h = new byte[512];
            ascii(h, 0, name, 100);
            octal(h, 100, 8, mode);
            octal(h, 108, 8, 0);
            octal(h, 116, 8, 0);
            octal(h, 124, 12, data.length);
            octal(h, 136, 12, 0);
            for (int i = 148; i < 156; i++) h[i] = ' ';
            h[156] = (byte) type;
            ascii(h, 157, linkName, 100);
            ascii(h, 257, "ustar", 6);
            h[263] = '0';
            h[264] = '0';
            int sum = 0;
            for (byte b : h) sum += b & 0xFF;
            octal(h, 148, 7, sum);
            h[154] = 0;
            h[155] = ' ';
            raw.writeBytes(h);
            raw.writeBytes(data);
            int pad = (512 - (data.length % 512)) % 512;
            raw.writeBytes(new byte[pad]);
        }

        byte[] gzip() throws IOException {
            raw.writeBytes(new byte[1024]);          // 两个空块 = 归档结束
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
                gz.write(raw.toByteArray());
            }
            return out.toByteArray();
        }

        private static void ascii(byte[] h, int off, String s, int len) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(b, 0, h, off, Math.min(b.length, len - 1));
        }

        private static void octal(byte[] h, int off, int len, long value) {
            String s = String.format("%0" + (len - 1) + "o", value);
            System.arraycopy(s.getBytes(StandardCharsets.US_ASCII), 0, h, off, len - 1);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 本地 HTTP 桩（支持 Range） ----------

    private static class StubMirror {
        record Request(String path, String range) {}

        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final List<String> rangeServed = new CopyOnWriteArrayList<>();
        private final HttpServer server;

        StubMirror() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/plugin-packs";
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath().replaceFirst("^/plugin-packs", "");
            String range = ex.getRequestHeaders().getFirst("Range");
            requests.add(new Request(path, range));
            byte[] body = files.get(path);
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            int from = 0;
            if (range != null && range.startsWith("bytes=")) {
                from = Integer.parseInt(range.substring("bytes=".length()).replace("-", ""));
                rangeServed.add(range);
                if (from >= body.length) {
                    // 如实模拟真实服务器对越界 Range 的回应（RFC 7233）：416，不是把 from
                    // 截回 body.length 再假装 206——这正是线上事故里客户端从未见过的分支。
                    ex.getResponseHeaders().add("Content-Range", "bytes */" + body.length);
                    ex.sendResponseHeaders(416, -1);
                    ex.close();
                    return;
                }
            }
            byte[] slice = java.util.Arrays.copyOfRange(body, Math.min(from, body.length), body.length);
            if (from > 0) {
                ex.getResponseHeaders().add("Content-Range",
                        "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
                ex.sendResponseHeaders(206, slice.length);
            } else {
                ex.sendResponseHeaders(200, slice.length);
            }
            try (OutputStream out = ex.getResponseBody()) {
                out.write(slice);
            }
            ex.close();
        }
    }
}
