package com.checkba.service.mobile;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.MobileMediaInbox;
import com.checkba.model.entity.MobileTransferRequest;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.MobileMediaInboxRepository;
import com.checkba.repository.MobileTransferRequestRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.tools.WebTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 跨设备文件传输服务（dev-board#251，spec 2.6）：状态机、requestId 幂等、在线闸、
 * 属主校验、配额共池、TTL 退款重试、billing 未配置的 DISABLED 文案、200MB 上限。
 *
 * <p>装配用真实 Spring 容器（同 MobileRelayEndpointIntegrationTest 的 H2 + 本地存储配方）：
 * ProjectFileService 依赖链很深（RAG/版本记录/额度/埋点/证据链接），手工 new 成本远高于
 * 起一个真实上下文；{@link TransferBillingClient} 用 @MockBean 换成可控桩，
 * 覆盖 quote/charge/refund 三个动作而不真的打网络。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mobile-transfer-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false",
        "storage.local.root-path=${java.io.tmpdir}/mobile-transfer-test-store",
        "mobile.transfer.billing.base-url=http://127.0.0.1:1",
        "mobile.transfer.billing.secret=test-secret"
})
@ActiveProfiles("desktop")
class MobileTransferServiceTest {

    @Autowired
    private MobileTransferService service;
    @Autowired
    private MobileTransferRequestRepository transferRepository;
    @Autowired
    private MobileMediaInboxRepository mediaInboxRepository;
    @Autowired
    private MobileRelayStoreService relayStore;
    @Autowired
    private AccountBindingRepository accountBindingRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectFileService projectFileService;

    @MockBean
    private TransferBillingClient billing;
    // 拉起完整上下文时 WebTools 会尝试联网初始化搜索工具，与 MobileRelayEndpointIntegrationTest
    // 一样 mock 掉，避免测试环境里的无关网络依赖
    @MockBean
    private WebTools webTools;

    private static final Long USER_A = 9001L;
    private static final Long USER_B = 9002L;

    @BeforeEach
    void setUp() {
        reset(billing);
        bindAccount(USER_A, "acct-a");
        bindAccount(USER_B, "acct-b");
    }

    /**
     * Spring 测试上下文按配置缓存复用（同一个内存 H2 实例贯穿本类全部用例），配额相关用例
     * 会故意占满 USER_A 的配额——不清干净会把后面所有用到 USER_A 的用例一起拖垮。
     */
    @AfterEach
    void tearDown() {
        transferRepository.deleteAll();
        mediaInboxRepository.deleteAll();
    }

    private void bindAccount(Long userId, String accountId) {
        if (accountBindingRepository.findByUserId(userId).isPresent()) return;
        AccountBinding b = new AccountBinding();
        b.setUserId(userId);
        b.setExternalAccountId(accountId);
        b.setCreatedAt(LocalDateTime.now());
        accountBindingRepository.save(b);
    }

    private String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private Project newProject(Long userId, String name) {
        Project p = new Project();
        p.setName(name);
        p.setProjectType("MAJOR_ASSET_RESTRUCTURING");
        p.setListedCompanyName("listco");
        p.setTargetCompanyName("targetco");
        p.setUserId(userId);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return projectRepository.save(p);
    }

    // ==================== LIST：在线闸 + requestId 幂等 ====================

    @Test
    @DisplayName("LIST：对方设备不在线时当场拒绝")
    void listRejectsWhenDeviceOffline() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.list(USER_A, "dev-never-seen", "42", newRequestId()));
        assertTrue(e.getMessage().contains("不在线"), "实际: " + e.getMessage());
    }

    @Test
    @DisplayName("LIST：在线时建行为 PENDING；requestId 幂等，重复调用返回同一行且不重复建行")
    void listCreatesRowAndIsIdempotent() {
        relayStore.touchDevice(USER_A, "dev-a");
        String requestId = newRequestId();

        MobileTransferRequest first = service.list(USER_A, "dev-a", "42", requestId);
        assertEquals("LIST", first.getKind());
        assertEquals("PENDING", first.getStatus());

        MobileTransferRequest second = service.list(USER_A, "dev-a", "42", requestId);
        assertEquals(first.getId(), second.getId());
        assertEquals(1, transferRepository.count());
    }

    // ==================== PULL：在线闸 + 大小上限 + 扣费幂等 + 状态机 ====================

    @Test
    @DisplayName("PULL：超过 200MB 一律拒绝，且不触发扣费")
    void pullRejectsOverSizeLimit() {
        relayStore.touchDevice(USER_A, "dev-a");
        long overLimit = MobileTransferService.MAX_TRANSFER_BYTES + 1;
        assertThrows(IllegalArgumentException.class, () -> service.pull(
                USER_A, "dev-a", "42", "file-1", "big.mov", overLimit, newRequestId()));
        verify(billing, never()).charge(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("PULL：requestId 幂等——重复调用只扣一次费，第二次直接返回既有行")
    void pullChargesOnceAndIsIdempotent() {
        relayStore.touchDevice(USER_A, "dev-a");
        when(billing.charge(eq("acct-a"), eq(100L), anyString(), anyString()))
                .thenReturn(new TransferBillingClient.ChargeResult(5, "ledger-1"));
        String requestId = newRequestId();

        MobileTransferRequest first = service.pull(USER_A, "dev-a", "42", "file-1", "a.pdf", 100, requestId);
        assertEquals("PULL", first.getKind());
        assertEquals("PENDING", first.getStatus());
        assertEquals(5, first.getChargedCredits());
        assertEquals("ledger-1", first.getChargeLedgerId());

        MobileTransferRequest second = service.pull(USER_A, "dev-a", "42", "file-1", "a.pdf", 100, requestId);
        assertEquals(first.getId(), second.getId());
        verify(billing, times(1)).charge(eq("acct-a"), eq(100L), eq("xfer-" + requestId), eq(requestId));
        assertEquals(1, transferRepository.count());
    }

    @Test
    @DisplayName("PULL：账户未绑定官网账户时拒绝，不调用计费")
    void pullRejectsWhenAccountNotBound() {
        relayStore.touchDevice(999L, "dev-x");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.pull(
                999L, "dev-x", "42", "file-1", "a.pdf", 100, newRequestId()));
        assertTrue(e.getMessage().contains("未与官网账户关联"), "实际: " + e.getMessage());
        verifyNoInteractions(billing);
    }

    @Test
    @DisplayName("PULL：余额不足（NO_CREDITS）翻成可读文案，不建行")
    void pullTranslatesNoCreditsException() {
        relayStore.touchDevice(USER_A, "dev-a");
        when(billing.charge(anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new TransferBillingClient.TransferBillingException(
                        TransferBillingClient.TransferBillingException.Kind.NO_CREDITS,
                        "余额不足", 8, 120L));
        String requestId = newRequestId();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.pull(
                USER_A, "dev-a", "42", "file-1", "a.pdf", 100, requestId));
        assertTrue(e.getMessage().contains("Credits"), "实际: " + e.getMessage());
        assertTrue(transferRepository.findByUserIdAndRequestId(USER_A, requestId).isEmpty());
    }

    @Test
    @DisplayName("上传/取件/落项目 状态机：PENDING -> STAGED（upload）-> DELIVERED（save-to-project）；已 DELIVERED 再调幂等返回")
    void pullFullLifecycleAndIdempotentDelivery() throws Exception {
        relayStore.touchDevice(USER_A, "dev-a");
        when(billing.charge(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(new TransferBillingClient.ChargeResult(3, "ledger-2"));

        String requestId = newRequestId();
        MobileTransferRequest row = service.pull(USER_A, "dev-a", "42", "file-1", "hello.txt", 5, requestId);

        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        service.upload(USER_A, row.getId(), new ByteArrayInputStream(bytes), bytes.length);
        MobileTransferRequest staged = transferRepository.findById(row.getId()).orElseThrow();
        assertEquals("STAGED", staged.getStatus());
        assertNotNull(staged.getStoragePath());

        // 幂等：已 STAGED 重传直接 ok，不重复写 blob 记录状态
        service.upload(USER_A, row.getId(), new ByteArrayInputStream(bytes), bytes.length);
        assertEquals("STAGED", transferRepository.findById(row.getId()).orElseThrow().getStatus());

        Project project = newProject(USER_A, "落地项目");
        Map<String, Object> result = service.saveToProject(USER_A, row.getId(), project.getId());
        assertNotNull(result.get("fileId"));
        MobileTransferRequest delivered = transferRepository.findById(row.getId()).orElseThrow();
        assertEquals("DELIVERED", delivered.getStatus());
        assertNull(delivered.getStoragePath(), "投递后 blob 定位符要清空");

        // 幂等：再次 save-to-project 找到同一个文件，不重复落盘/不报错
        Map<String, Object> again = service.saveToProject(USER_A, row.getId(), project.getId());
        assertEquals(result.get("fileId"), again.get("fileId"));

        List<ProjectFile> root = projectFileService.getFilesByParent(project.getId(), null);
        assertTrue(root.stream().anyMatch(f -> Boolean.TRUE.equals(f.getIsFolder()) && "跨设备文件".equals(f.getName())));
    }

    @Test
    @DisplayName("save-to-project：projectId 不属于当前用户时拒绝")
    void saveToProjectRejectsNonOwnedProject() {
        relayStore.touchDevice(USER_A, "dev-a");
        when(billing.charge(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(new TransferBillingClient.ChargeResult(1, "ledger-3"));
        MobileTransferRequest row = service.pull(USER_A, "dev-a", "42", "file-1", "a.pdf", 10, newRequestId());
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        service.upload(USER_A, row.getId(), new ByteArrayInputStream(bytes), bytes.length);

        Project othersProject = newProject(USER_B, "别人的项目");
        assertThrows(IllegalArgumentException.class,
                () -> service.saveToProject(USER_A, row.getId(), othersProject.getId()));
    }

    @Test
    @DisplayName("传输请求只认属主：他人 id 访问一律拒绝")
    void ownershipIsEnforced() {
        relayStore.touchDevice(USER_A, "dev-a");
        MobileTransferRequest row = service.list(USER_A, "dev-a", "42", newRequestId());
        assertThrows(IllegalArgumentException.class, () -> service.get(USER_B, row.getId()));
        assertThrows(IllegalArgumentException.class, () -> service.cancel(USER_B, row.getId()));
    }

    // ==================== PUSH：属主校验 + 配额共池 ====================

    @Test
    @DisplayName("PUSH：fileId 所属项目不是当前用户的，拒绝且不扣费")
    void pushRejectsFileNotOwnedByUser() {
        Project othersProject = newProject(USER_B, "别人的项目");
        ProjectFile folder = projectFileService.createFolder(othersProject.getId(), null, "文件夹", USER_B);
        ProjectFile file = projectFileService.createFile(othersProject.getId(), folder.getId(), "a.txt",
                "txt", 3L, null, null, USER_B);

        assertThrows(IllegalArgumentException.class, () -> service.push(
                USER_A, "dev-a", "1", file.getId(), newRequestId()));
        verify(billing, never()).charge(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("PUSH：配额共池——影像中转占满配额时投送被拒，且扣的费会被立即退回")
    void pushRejectsWhenQuotaFullAndRefundsTheCharge() throws Exception {
        Project project = newProject(USER_A, "我的项目");
        ProjectFile folder = projectFileService.createFolder(project.getId(), null, "文件夹", USER_A);
        ProjectFile file = projectFileService.createFile(project.getId(), folder.getId(), "a.txt",
                "txt", 5L, null, null, USER_A);

        // 用影像中转占满配额（配额共池：media + transfer 两表之和）
        MobileMediaInbox big = new MobileMediaInbox();
        big.setUserId(USER_A);
        big.setDeviceId("dev-a");
        big.setProjectKey("42");
        big.setClientMediaId(UUID.randomUUID().toString());
        big.setFileName("huge.mov");
        big.setMediaType("video");
        big.setFileSize(MobileRelayStoreService.QUOTA_BYTES);
        big.setStoragePath("placeholder-blob-path");
        big.setCreatedAt(LocalDateTime.now());
        mediaInboxRepository.saveAndFlush(big);

        when(billing.charge(eq("acct-a"), anyLong(), anyString(), anyString()))
                .thenReturn(new TransferBillingClient.ChargeResult(2, "ledger-4"));

        String requestId = newRequestId();
        assertThrows(IllegalArgumentException.class,
                () -> service.push(USER_A, "dev-b", "1", file.getId(), requestId));

        verify(billing, times(1)).charge(eq("acct-a"), anyLong(), anyString(), anyString());
        verify(billing, times(1)).refund(eq("acct-a"), eq("ledger-4"), eq("xferrf-" + requestId));
        assertTrue(transferRepository.findByUserIdAndRequestId(USER_A, requestId).isEmpty(),
                "配额检查失败不该留下一行 PUSH 记录");
    }

    @Test
    @DisplayName("PUSH：正常投送建 STAGED 行并携带扣费结果，requestId 幂等不重复扣费")
    void pushCreatesStagedRowAndIsIdempotent() {
        Project project = newProject(USER_A, "我的项目");
        ProjectFile folder = projectFileService.createFolder(project.getId(), null, "文件夹", USER_A);
        ProjectFile file = projectFileService.createFile(project.getId(), folder.getId(), "a.txt",
                "txt", 5L, null, null, USER_A);
        when(billing.charge(eq("acct-a"), eq(5L), anyString(), anyString()))
                .thenReturn(new TransferBillingClient.ChargeResult(1, "ledger-5"));

        String requestId = newRequestId();
        MobileTransferRequest row = service.push(USER_A, "dev-b", "1", file.getId(), requestId);
        assertEquals("PUSH", row.getKind());
        assertEquals("STAGED", row.getStatus());
        assertNotNull(row.getStoragePath());

        MobileTransferRequest again = service.push(USER_A, "dev-b", "1", file.getId(), requestId);
        assertEquals(row.getId(), again.getId());
        verify(billing, times(1)).charge(eq("acct-a"), eq(5L), anyString(), anyString());
    }

    // ==================== billing 未配置：DISABLED 文案 ====================

    @Test
    @DisplayName("billing 未配置（DISABLED）：quote 直接给出可读文案，不发请求")
    void quoteFailsWithDisabledMessageWhenBillingUnconfigured() {
        when(billing.quote(anyString(), anyLong()))
                .thenThrow(new TransferBillingClient.TransferBillingException(
                        TransferBillingClient.TransferBillingException.Kind.DISABLED,
                        "跨设备传输未在此服务器开通"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.quote(USER_A, 1024));
        assertEquals("跨设备传输未在此服务器开通", e.getMessage());
    }

    @Test
    @DisplayName("billing 网络失败（UNAVAILABLE）翻成「稍后再试」，绝不免费放行")
    void chargeUnavailableNeverFreelyProceeds() {
        relayStore.touchDevice(USER_A, "dev-a");
        when(billing.charge(anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new TransferBillingClient.TransferBillingException(
                        TransferBillingClient.TransferBillingException.Kind.UNAVAILABLE, "计费服务暂不可用，请稍后再试"));
        String requestId = newRequestId();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.pull(
                USER_A, "dev-a", "42", "file-1", "a.pdf", 100, requestId));
        assertTrue(e.getMessage().contains("暂不可用"), "实际: " + e.getMessage());
        assertTrue(transferRepository.findByUserIdAndRequestId(USER_A, requestId).isEmpty());
    }

    // ==================== fail/cancel：退款 ====================

    @Test
    @DisplayName("cancel：LIST PENDING 可取消（不扣费，不发起退款）")
    void cancelListRequestNoRefundNeeded() {
        relayStore.touchDevice(USER_A, "dev-a");
        MobileTransferRequest row = service.list(USER_A, "dev-a", "42", newRequestId());
        service.cancel(USER_A, row.getId());
        assertEquals("FAILED", transferRepository.findById(row.getId()).orElseThrow().getStatus());
        verifyNoInteractions(billing);
    }

    @Test
    @DisplayName("cancel：PULL PENDING 取消会触发退款")
    void cancelPullRequestRefunds() {
        relayStore.touchDevice(USER_A, "dev-a");
        when(billing.charge(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(new TransferBillingClient.ChargeResult(4, "ledger-6"));
        MobileTransferRequest row = service.pull(USER_A, "dev-a", "42", "file-1", "a.pdf", 10, newRequestId());

        service.cancel(USER_A, row.getId());

        MobileTransferRequest after = transferRepository.findById(row.getId()).orElseThrow();
        assertEquals("FAILED", after.getStatus());
        assertNotNull(after.getRefundedAt());
        verify(billing).refund(eq("acct-a"), eq("ledger-6"), eq("xferrf-" + row.getRequestId()));
    }

    @Test
    @DisplayName("fail：B 报确定性失败——已扣费的行退款+删 blob；已经是终态的再调是幂等 no-op")
    void failRefundsAndIsIdempotent() {
        relayStore.touchDevice(USER_A, "dev-a");
        when(billing.charge(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(new TransferBillingClient.ChargeResult(2, "ledger-7"));
        MobileTransferRequest row = service.pull(USER_A, "dev-a", "42", "file-1", "a.pdf", 10, newRequestId());

        service.fail(USER_A, row.getId(), "项目不存在");
        MobileTransferRequest failed = transferRepository.findById(row.getId()).orElseThrow();
        assertEquals("FAILED", failed.getStatus());
        assertEquals("项目不存在", failed.getErrorMessage());
        assertNotNull(failed.getRefundedAt());

        // 再调一次：已是终态，幂等 no-op，不重复退款
        service.fail(USER_A, row.getId(), "again");
        verify(billing, times(1)).refund(anyString(), anyString(), anyString());
    }

    // ==================== TTL 退款重试 ====================

    @Test
    @DisplayName("TTL 清扫：PULL PENDING 超 24 小时过期+删 blob+退款；退款失败时下一轮清扫继续重试")
    void cleanupExpiresPullPendingAndRetriesFailedRefund() {
        relayStore.touchDevice(USER_A, "dev-a");
        when(billing.charge(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(new TransferBillingClient.ChargeResult(6, "ledger-8"));
        // 第一轮清扫退款失败，第二轮成功——验证"下一轮清扫兜底重试"这条不变式
        doThrow(new RuntimeException("网络抖动"))
                .doNothing()
                .when(billing).refund(eq("acct-a"), eq("ledger-8"), anyString());

        MobileTransferRequest row = service.pull(USER_A, "dev-a", "42", "file-1", "a.pdf", 10, newRequestId());
        row = transferRepository.findById(row.getId()).orElseThrow();
        row.setCreatedAt(LocalDateTime.now().minusHours(25));
        row.setUpdatedAt(row.getCreatedAt());
        transferRepository.saveAndFlush(row);

        service.cleanupExpired();
        MobileTransferRequest afterFirstSweep = transferRepository.findById(row.getId()).orElseThrow();
        assertEquals("EXPIRED", afterFirstSweep.getStatus());
        assertNull(afterFirstSweep.getRefundedAt(), "第一轮退款失败，refundedAt 应留空等下一轮重试");

        service.cleanupExpired();
        MobileTransferRequest afterSecondSweep = transferRepository.findById(row.getId()).orElseThrow();
        assertNotNull(afterSecondSweep.getRefundedAt(), "第二轮清扫应该补上退款");

        verify(billing, times(2)).refund(eq("acct-a"), eq("ledger-8"), anyString());
    }

    @Test
    @DisplayName("TTL 清扫：LIST PENDING 超 10 分钟过期（不扣费，不需要退款）")
    void cleanupExpiresListPending() {
        relayStore.touchDevice(USER_A, "dev-a");
        MobileTransferRequest row = service.list(USER_A, "dev-a", "42", newRequestId());
        row = transferRepository.findById(row.getId()).orElseThrow();
        row.setCreatedAt(LocalDateTime.now().minusMinutes(11));
        row.setUpdatedAt(row.getCreatedAt());
        transferRepository.saveAndFlush(row);

        service.cleanupExpired();
        assertEquals("EXPIRED", transferRepository.findById(row.getId()).orElseThrow().getStatus());
        verifyNoInteractions(billing);
    }
}
