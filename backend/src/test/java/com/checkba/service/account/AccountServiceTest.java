package com.checkba.service.account;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定账户连接契约（商业化改造 PR-B）：
 * - connect 校验通过才落盘，且文件权限 0600（存着明文 awdk_ Key）；
 * - 失败分类：网络不可达 / 鉴权失败 / 业务冲突各走各的分支，5xx 归网络不能清连接；
 * - 任何错误信息都是中文且**不含 Key 明文**；
 * - 官网契约文档记录的两处偏差：ledger 是 {entries:[...]} 包裹，不是裸数组。
 */
class AccountServiceTest {

    private static final String KEY = "awdk_" + "S3cretKeyMaterial0123456789abcdefghij";

    @TempDir
    Path tempDir;

    /** 可编排的出站桩：按入队顺序返回，同时记录收到的请求。 */
    static class StubTransport implements AccountTransport {
        final Deque<Reply> replies = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        String lastBearer;

        StubTransport enqueue(int status, String body) {
            replies.add(new Reply(status, body));
            return this;
        }

        StubTransport enqueueNetworkFailure() {
            replies.add(new Reply(Reply.NETWORK_FAILURE, null));
            return this;
        }

        @Override
        public Reply send(String method, String url, String bearerKey, String jsonBody) {
            calls.add(method + " " + url);
            lastBearer = bearerKey;
            if (replies.isEmpty()) {
                throw new AssertionError("桩没有为 " + method + " " + url + " 准备响应");
            }
            return replies.poll();
        }
    }

    private StubTransport transport;

    private AccountService service() {
        return new AccountService("https://www.aiworkdeck.com", tempDir.toString(), transport);
    }

    private AccountService connected() {
        transport = new StubTransport().enqueue(200,
                "{\"username\":\"hanzewei\",\"displayName\":\"韩泽伟\",\"balanceCents\":1980,\"plan\":\"paid\"}");
        AccountService service = service();
        service.connect(KEY);
        return service;
    }

    // ==================== connect ====================

    @Test
    @DisplayName("连接成功：落盘 + status 带用户名，且绝不返回 Key 明文")
    void connectPersistsAndNeverExposesKey() {
        AccountService service = connected();

        assertEquals("GET https://www.aiworkdeck.com/api/account/me", transport.calls.get(0));
        assertEquals(KEY, transport.lastBearer, "出站请求必须带 Bearer <key>");

        Map<String, Object> status = service.status();
        assertEquals(true, status.get("connected"));
        assertEquals("hanzewei", status.get("username"));
        assertEquals("韩泽伟", status.get("displayName"));
        assertNotNull(status.get("connectedAt"));
        assertFalse(status.toString().contains(KEY), "status 不允许出现 Key 明文: " + status);
        assertTrue(String.valueOf(status.get("keyMasked")).startsWith("awdk_****"));

        assertTrue(Files.exists(tempDir.resolve("account.json")));
        // 新实例（模拟重启）从盘上恢复
        transport = new StubTransport();
        assertTrue(service().isConnected());
    }

    @Test
    @DisplayName("account.json 存明文 Key，权限必须收敛为 0600")
    void accountFileIsOwnerOnly() throws Exception {
        connected();
        Path file = tempDir.resolve("account.json");
        Assumptions.assumeTrue(
                Files.getFileAttributeView(file, PosixFileAttributeView.class) != null, "非 POSIX 文件系统跳过");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    @DisplayName("401：分类为鉴权失败，中文提示，不落盘")
    void unauthorizedIsClassifiedAndNotPersisted() {
        transport = new StubTransport().enqueue(401, "{\"error\":\"unauthorized\"}");
        AccountException e = assertThrows(AccountException.class, () -> service().connect(KEY));
        assertEquals(AccountException.Kind.UNAUTHORIZED, e.getKind());
        assertTrue(e.getMessage().contains("账户 Key 无效"), e.getMessage());
        assertFalse(e.getMessage().contains(KEY), "错误信息不允许出现 Key 明文");
        assertFalse(Files.exists(tempDir.resolve("account.json")));
    }

    @Test
    @DisplayName("连接超时/断网：分类为网络不可达，中文提示")
    void networkFailureIsClassified() {
        transport = new StubTransport().enqueueNetworkFailure();
        AccountException e = assertThrows(AccountException.class, () -> service().connect(KEY));
        assertEquals(AccountException.Kind.NETWORK, e.getKind());
        assertTrue(e.getMessage().contains("网络"), e.getMessage());
    }

    @Test
    @DisplayName("5xx 归为网络不可达：服务器故障不等于凭据失效，不能据此清连接")
    void serverErrorIsNetworkNotUnauthorized() {
        AccountService service = connected();
        transport.enqueue(503, "gateway down");
        AccountException e = assertThrows(AccountException.class, service::fetchEntitlements);
        assertEquals(AccountException.Kind.NETWORK, e.getKind());
        assertTrue(service.isConnected(), "5xx 之后本地连接必须还在");
    }

    @Test
    @DisplayName("Key 格式不对：本地直接拒绝，一个请求都不发")
    void malformedKeyRejectedWithoutRequest() {
        transport = new StubTransport();
        AccountException e = assertThrows(AccountException.class, () -> service().connect("sk-not-an-awdk"));
        assertEquals(AccountException.Kind.UNAUTHORIZED, e.getKind());
        assertTrue(e.getMessage().contains("awdk_"), e.getMessage());
        assertTrue(transport.calls.isEmpty(), "格式不对不该产生出站请求");
    }

    @Test
    @DisplayName("未连接时拉数据：分类为 NOT_CONNECTED，请求不发出")
    void notConnectedShortCircuits() {
        transport = new StubTransport();
        AccountException e = assertThrows(AccountException.class, () -> service().fetchLedger());
        assertEquals(AccountException.Kind.NOT_CONNECTED, e.getKind());
        assertTrue(transport.calls.isEmpty());
    }

    // ==================== disconnect ====================

    @Test
    @DisplayName("断开：清除本地凭据")
    void disconnectClearsCredential() {
        AccountService service = connected();
        Map<String, Object> status = service.disconnect();
        assertEquals(false, status.get("connected"));
        assertFalse(service.isConnected());
        assertFalse(Files.exists(tempDir.resolve("account.json")));
    }

    // ==================== 数据拉取 ====================

    @Test
    @DisplayName("ledger 按官网契约解 {entries:[...]}，不是裸数组")
    void ledgerUnwrapsEntries() {
        AccountService service = connected();
        transport.enqueue(200, "{\"entries\":[{\"kind\":\"recharge\",\"amountCents\":1000},"
                + "{\"kind\":\"ai_alloc\",\"amountCents\":-500}]}");
        List<Map<String, Object>> entries = service.fetchLedger();
        assertEquals(2, entries.size());
        assertEquals("ai_alloc", entries.get(1).get("kind"));
    }

    @Test
    @DisplayName("entitlements 解 {entitlements:[...]}，并刷新 lastSyncAt")
    void entitlementsUnwrapAndTouchSync() {
        AccountService service = connected();
        transport.enqueue(200, "{\"entitlements\":[{\"feature\":\"clipboard.unlimited\","
                + "\"purchasedAt\":\"2026-08-01T00:00:00Z\",\"orderId\":\"o1\"}]}");
        List<Map<String, Object>> list = service.fetchEntitlements();
        assertEquals(1, list.size());
        assertEquals("clipboard.unlimited", list.get(0).get("feature"));
        assertNotNull(service.status().get("lastSyncAt"));
    }

    @Test
    @DisplayName("ai-key 409 no_allocation：给出「去官网分配额度」的中文引导")
    void aiKeyNoAllocationGivesGuidance() {
        AccountService service = connected();
        transport.enqueue(409, "{\"error\":\"no_allocation\"}");
        AccountException e = assertThrows(AccountException.class, service::fetchAiKey);
        assertEquals(AccountException.Kind.CONFLICT, e.getKind());
        assertEquals("请先在官网账户页分配 AI 额度", e.getMessage());
    }

    @Test
    @DisplayName("ai-key 成功：返回 openrouterKey 与 limitUsd")
    void aiKeySuccess() {
        AccountService service = connected();
        transport.enqueue(200, "{\"openrouterKey\":\"sk-or-v1-provisioned\",\"limitUsd\":5.0}");
        Map<String, Object> body = service.fetchAiKey();
        assertEquals("sk-or-v1-provisioned", body.get("openrouterKey"));
        assertEquals(5.0, ((Number) body.get("limitUsd")).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("官网返回非 JSON：分类为 MALFORMED，不当成鉴权失败")
    void malformedBodyIsClassified() {
        AccountService service = connected();
        transport.enqueue(200, "<html>502 bad gateway</html>");
        AccountException e = assertThrows(AccountException.class, service::fetchLedger);
        assertEquals(AccountException.Kind.MALFORMED, e.getKind());
    }

    // ==================== 配置红线 ====================

    @Test
    @DisplayName("base-url 配成 http 直接拒绝：明文 Key 不允许走未加密通道")
    void httpBaseUrlRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AccountService("http://www.aiworkdeck.com", tempDir.toString(), new StubTransport()));
        assertTrue(e.getMessage().contains("https"), e.getMessage());
    }
}
