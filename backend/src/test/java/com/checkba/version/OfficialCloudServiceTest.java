package com.checkba.version;

import com.checkba.model.entity.CloudConnection;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.service.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 官方案件库零配置直连（dev-board#439 第 2/3 环）。
 *
 * 形状照 MobileRelayClientService 的既有先例：本机 awdk_ 调 {base}/api/auth/awdk-login
 * 换 awdt_ 设备令牌，账户指纹变了才重桥。HTTP 走 httpPost seam 打桩，不联网。
 */
class OfficialCloudServiceTest {

    private static final long ME = 42L;
    private static final String OFFICIAL = "https://case.aiworkdeck.com";

    private AccountService accountService;
    private CloudConnectionRepository connectionRepository;
    private CloudSyncService cloudSyncService;
    private Map<Long, CloudConnection> rows;
    private long nextId;

    private String lastUrl;
    private String lastBody;
    private int httpCalls;
    private String canned;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        cloudSyncService = mock(CloudSyncService.class);
        rows = new HashMap<>();
        nextId = 1L;
        connectionRepository = mock(CloudConnectionRepository.class);
        when(connectionRepository.save(any(CloudConnection.class))).thenAnswer(i -> {
            CloudConnection c = i.getArgument(0);
            if (c.getId() == null) c.setId(nextId++);
            rows.put(c.getId(), c);
            return c;
        });
        when(connectionRepository.findFirstByUserIdAndServerUrl(any(), any())).thenAnswer(i ->
                rows.values().stream()
                        .filter(c -> i.getArgument(0).equals(c.getUserId())
                                && i.getArgument(1).equals(c.getServerUrl()))
                        .findFirst());
        lastUrl = null;
        lastBody = null;
        httpCalls = 0;
        canned = "{\"code\":0,\"data\":{\"token\":\"awdt_first\",\"userId\":9,"
                + "\"username\":\"awd_hanzewei\",\"displayName\":\"韩泽伟\",\"tokenId\":31}}";
    }

    private OfficialCloudService service(String configured, String accountBaseUrl) {
        return new OfficialCloudService(configured, accountBaseUrl, accountService,
                connectionRepository, cloudSyncService) {
            @Override
            protected String httpPost(String url, String jsonBody) {
                lastUrl = url;
                lastBody = jsonBody;
                httpCalls++;
                return canned;
            }
        };
    }

    private OfficialCloudService mainland() {
        return service("", "https://www.aiworkdeck.com");
    }

    private void connectedAccount(String key, String fingerprint) {
        when(accountService.currentKeyOrNull()).thenReturn(key);
        when(accountService.accountFingerprintOrNull()).thenReturn(fingerprint);
    }

    // ---- 桥接 ---------------------------------------------------------

    @Test
    void connectOfficialBridgesWithTheLocalAccountKeyAndStoresTheDeviceToken() {
        connectedAccount("awdk_abc", "fp-1");

        CloudConnection conn = mainland().connectOfficial(ME);

        assertEquals(OFFICIAL + "/api/auth/awdk-login", lastUrl);
        assertTrue(lastBody.contains("awdk_abc"), "桥接必须把本机 Key 带上去: " + lastBody);
        assertEquals(OFFICIAL, conn.getServerUrl());
        assertEquals(ME, conn.getUserId());
        assertEquals("awd_hanzewei", conn.getUsername());
        assertEquals("awdt_first", conn.getDeviceToken());
        assertEquals(31L, conn.getTokenId());
        assertEquals("fp-1", conn.getAccountFingerprint());
    }

    /** 幂等：每个本机用户对官方地址至多一条连接，重复点不再桥接、不再建行。 */
    @Test
    void secondConnectOfficialReusesTheSameRowWithoutBridgingAgain() {
        connectedAccount("awdk_abc", "fp-1");
        OfficialCloudService svc = mainland();
        CloudConnection first = svc.connectOfficial(ME);

        CloudConnection second = svc.connectOfficial(ME);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, httpCalls, "指纹没变就不该再桥一次");
        assertEquals(1, rows.size(), "官方连接只该有一条");
    }

    /** 换了官网账户（指纹变了）：同一行就地重桥换令牌，不留下第二条连接。 */
    @Test
    void changingTheAccountRebridgesInPlaceInsteadOfLeavingTwoConnections() {
        connectedAccount("awdk_abc", "fp-1");
        OfficialCloudService svc = mainland();
        CloudConnection first = svc.connectOfficial(ME);

        canned = "{\"code\":0,\"data\":{\"token\":\"awdt_second\",\"userId\":10,"
                + "\"username\":\"awd_other\",\"displayName\":\"别人\",\"tokenId\":32}}";
        connectedAccount("awdk_xyz", "fp-2");
        CloudConnection second = svc.connectOfficial(ME);

        assertEquals(first.getId(), second.getId());
        assertEquals(2, httpCalls);
        assertEquals(1, rows.size());
        assertEquals("awdt_second", second.getDeviceToken());
        assertEquals("fp-2", second.getAccountFingerprint());
    }

    /**
     * 没连官网账户：给一句律师看得懂的业务错误，且不得含「登录」「未授权」「请先」
     * ——那三个词会被误读成掉线（licensing 地雷 1 的历史口径）。
     */
    @Test
    void withoutAnAccountKeyTheMessageIsBusinessLikeAndDoesNotLookLikeALogoutNotice() {
        connectedAccount(null, null);

        VersionException ex = assertThrows(VersionException.class, () -> mainland().connectOfficial(ME));

        assertTrue(ex.isUserFacing(), "这是业务错误，要原样给律师看");
        for (String forbidden : new String[]{"登录", "未授权", "请先"}) {
            assertFalse(ex.getMessage().contains(forbidden),
                    "文案不得含「" + forbidden + "」: " + ex.getMessage());
        }
        assertEquals(0, httpCalls);
    }

    /** 国际站：官方案件库暂不提供，不能拿国际站账户往大陆的服务器上桥。 */
    @Test
    void internationalSiteHasNoOfficialCaseLibrary() {
        OfficialCloudService svc = service("", "https://www.workdeck.ai");

        assertFalse(svc.available());
        assertNull(svc.officialBaseUrl());
        VersionException ex = assertThrows(VersionException.class, () -> svc.connectOfficial(ME));
        assertTrue(ex.isUserFacing());
        assertEquals(0, httpCalls);
    }

    @Test
    void statusReportsAvailabilityAndWhetherThisMachineIsAlreadyConnected() {
        connectedAccount("awdk_abc", "fp-1");
        OfficialCloudService svc = mainland();

        Map<String, Object> before = svc.status(ME);
        assertEquals(Boolean.TRUE, before.get("available"));
        assertEquals(Boolean.FALSE, before.get("connected"));
        assertEquals(OFFICIAL, before.get("serverUrl"));

        svc.connectOfficial(ME);

        Map<String, Object> after = svc.status(ME);
        assertEquals(Boolean.TRUE, after.get("connected"));
        assertEquals("awd_hanzewei", after.get("username"));
    }

    /** status 绝不能把设备令牌带出去（同 CloudController.connectionListItem 的纪律）。 */
    @Test
    void statusNeverLeaksTheDeviceToken() {
        connectedAccount("awdk_abc", "fp-1");
        OfficialCloudService svc = mainland();
        svc.connectOfficial(ME);

        assertFalse(svc.status(ME).toString().contains("awdt_"), "status 里出现了设备令牌");
    }

    // ---- 一键放进案件库 -------------------------------------------------

    @Test
    void sharingWithoutAConnectionIdConnectsToTheOfficialLibraryFirst() {
        connectedAccount("awdk_abc", "fp-1");
        when(cloudSyncService.shareToCloud(anyLong(), anyLong(), any())).thenReturn(Map.of("remoteProjectId", 5L));

        mainland().shareProject(7L, ME, null);

        long connId = rows.keySet().iterator().next();
        verify(cloudSyncService).shareToCloud(7L, connId, ME);
        assertEquals(1, httpCalls);
    }

    /** 显式指定了案件库（自建/多库场景）就照它来，不去碰官方地址。 */
    @Test
    void sharingWithAnExplicitConnectionIdDoesNotTouchTheOfficialLibrary() {
        when(cloudSyncService.shareToCloud(anyLong(), anyLong(), any())).thenReturn(Map.of("remoteProjectId", 5L));

        mainland().shareProject(7L, ME, 88L);

        verify(cloudSyncService).shareToCloud(7L, 88L, ME);
        assertEquals(0, httpCalls);
        verify(accountService, never()).currentKeyOrNull();
    }

    /** 官方服务器拒绝桥接（Key 被吊销等）：业务错误照实说，别让它冒充"内部错误"。 */
    @Test
    void aRejectedBridgeIsReportedAsAUserFacingError() {
        connectedAccount("awdk_abc", "fp-1");
        canned = "{\"code\":1,\"message\":\"账户 Key 无效或已被撤销\"}";

        VersionException ex = assertThrows(VersionException.class, () -> mainland().connectOfficial(ME));

        assertTrue(ex.isUserFacing());
        assertTrue(ex.getMessage().contains("账户 Key 无效或已被撤销"), ex.getMessage());
        assertEquals(0, rows.size(), "桥接失败不该留下半条连接");
    }

    @Test
    void anExistingConnectionIsReusedWhenTheAccountIsNoLongerConnected() {
        connectedAccount("awdk_abc", "fp-1");
        OfficialCloudService svc = mainland();
        svc.connectOfficial(ME);

        // 账户断开后再点：既没有 Key 也没有指纹，不能拿旧行冒充"已连接"继续桥接
        connectedAccount(null, null);
        assertThrows(VersionException.class, () -> svc.connectOfficial(ME));
        assertEquals(1, httpCalls);
    }

    /** 连接按人隔离：别人的官方连接不能被当成"我已经连过了"（同 CloudConnection.userId 的既有纪律）。 */
    @Test
    void anotherUsersOfficialConnectionIsNotReused() {
        connectedAccount("awdk_abc", "fp-1");
        OfficialCloudService svc = mainland();
        svc.connectOfficial(ME);

        CloudConnection mine = svc.connectOfficial(99L);

        assertEquals(2, rows.size());
        assertEquals(99L, mine.getUserId());
    }

    @Test
    void statusForAUserWithoutAnyConnectionSaysNotConnected() {
        Map<String, Object> s = mainland().status(ME);
        assertEquals(Boolean.FALSE, s.get("connected"));
        assertNull(s.get("username"));
    }
}
