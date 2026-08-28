package com.checkba;

import com.checkba.model.entity.AccountBinding;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.tools.WebTools;
import com.checkba.service.mobile.MobileRelayStoreService;
import com.checkba.service.mobile.TransferBillingClient;
import com.checkba.storage.StorageServiceFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * /api/mobile/transfer/* 全组端点的运行时集成测试（dev-board#251，spec 2.6）：真实走
 * HTTP → 控制器参数绑定 → MobileTransferService → H2。环境配方同
 * {@code MobileRelayEndpointIntegrationTest}；{@link TransferBillingClient} 用 @MockBean
 * 换成可控桩，不真的打网络到官网内部记账口。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mobile-transfer-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false",
        "storage.local.root-path=${java.io.tmpdir}/mobile-transfer-e2e-store"
})
@AutoConfigureMockMvc
@ActiveProfiles("desktop")
class MobileTransferEndpointIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private AccountBindingRepository accountBindingRepository;
    @Autowired
    private MobileRelayStoreService relayStore;
    @Autowired
    private ProjectFileService projectFileService;
    @Autowired
    private StorageServiceFactory storageServiceFactory;

    @MockBean
    private TransferBillingClient billing;
    @MockBean
    private WebTools webTools;

    private String register(String username) throws Exception {
        String body = om.writeValueAsString(Map.of(
                "username", username, "password", "pw123456", "displayName", username));
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = om.readTree(r.getResponse().getContentAsString());
        String sid = json.path("data").path("sessionId").asText();
        assertFalse(sid.isEmpty(), "注册应返回 sessionId：" + r.getResponse().getContentAsString());
        return sid;
    }

    private Long userIdOf(String sessionId) throws Exception {
        MvcResult r = mvc.perform(get("/api/auth/me").header("X-Session-Id", sessionId))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void bindAccount(String sessionId, String accountId) throws Exception {
        Long userId = userIdOf(sessionId);
        AccountBinding b = new AccountBinding();
        b.setUserId(userId);
        b.setExternalAccountId(accountId);
        b.setCreatedAt(LocalDateTime.now());
        accountBindingRepository.save(b);
    }

    @Test
    void unauthenticatedRequestsGet4010Envelope() throws Exception {
        mvc.perform(get("/api/mobile/transfer/quote").param("bytes", "1024"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4010));
        mvc.perform(post("/api/mobile/transfer/list").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4010));
        mvc.perform(get("/api/mobile/transfer/commands").param("deviceId", "dev-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4010));
    }

    @Test
    void listPendingOfflineDeviceRejected() throws Exception {
        String a = register("xfer_a_" + System.nanoTime());
        String body = """
                {"deviceId":"dev-never-seen","projectKey":"42","requestId":"%s"}""".formatted(java.util.UUID.randomUUID());
        mvc.perform(post("/api/mobile/transfer/list").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不在线")));
    }

    @Test
    void listRoundTrip() throws Exception {
        String a = register("xfer_list_a_" + System.nanoTime());
        String b = register("xfer_list_b_" + System.nanoTime());
        Long userIdA = userIdOf(a);
        relayStore.touchDevice(userIdA, "dev-a");

        String requestId = java.util.UUID.randomUUID().toString();
        String listBody = """
                {"deviceId":"dev-a","projectKey":"42","requestId":"%s"}""".formatted(requestId);
        MvcResult created = mvc.perform(post("/api/mobile/transfer/list").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(listBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        long id = om.readTree(created.getResponse().getContentAsString()).path("id").asLong();

        // B（同账号，另一次登录）拉命令：应看到这条 LIST PENDING，且 hot=true（刚建的活跃行）
        mvc.perform(get("/api/mobile/transfer/commands").param("deviceId", "dev-a").header("X-Session-Id", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.commands.length()").value(1))
                .andExpect(jsonPath("$.commands[0].kind").value("LIST"))
                .andExpect(jsonPath("$.hot").value(true));

        // B 应答清单
        String filesBody = """
                {"files":[{"id":"f1","name":"合同.docx","path":"合同.docx","size":100},
                          {"id":"f2","name":"附件/发票.pdf","path":"附件/发票.pdf","size":200}]}""";
        mvc.perform(post("/api/mobile/transfer/" + id + "/files").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(filesBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // A 查详情：DONE + files
        mvc.perform(get("/api/mobile/transfer/" + id).header("X-Session-Id", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfer.status").value("DONE"))
                .andExpect(jsonPath("$.transfer.files.length()").value(2))
                .andExpect(jsonPath("$.transfer.files[0].name").value("合同.docx"));

        // 另一个用户看不到这条（越权）
        mvc.perform(get("/api/mobile/transfer/" + id).header("X-Session-Id", b))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        verifyNoInteractions(billing); // LIST 全程不扣费
    }

    @Test
    void pullRoundTripChargesAndLandsInProject() throws Exception {
        String a = register("xfer_pull_a_" + System.nanoTime());
        bindAccount(a, "acct-pull-a");
        Long userIdA = userIdOf(a);
        relayStore.touchDevice(userIdA, "dev-a");

        when(billing.quote(eq("acct-pull-a"), eq(9L)))
                .thenReturn(new TransferBillingClient.QuoteResult(2, 500L));
        when(billing.charge(eq("acct-pull-a"), eq(9L), anyString(), anyString()))
                .thenReturn(new TransferBillingClient.ChargeResult(2, "ledger-e2e-1"));

        // 1. 报价
        mvc.perform(get("/api/mobile/transfer/quote").param("bytes", "9").header("X-Session-Id", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.credits").value(2))
                .andExpect(jsonPath("$.balanceCents").value(500));

        // 2. 发起拉取
        String requestId = java.util.UUID.randomUUID().toString();
        String pullBody = """
                {"deviceId":"dev-a","projectKey":"42","remoteFileId":"file-9",
                 "fileName":"备忘录.txt","fileSize":9,"requestId":"%s"}""".formatted(requestId);
        MvcResult pulled = mvc.perform(post("/api/mobile/transfer/pull").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(pullBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.credits").value(2))
                .andReturn();
        long id = om.readTree(pulled.getResponse().getContentAsString()).path("id").asLong();

        // requestId 幂等：重复调用不重复扣费
        mvc.perform(post("/api/mobile/transfer/pull").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(pullBody))
                .andExpect(jsonPath("$.id").value(id));
        verify(billing, times(1)).charge(eq("acct-pull-a"), eq(9L), anyString(), anyString());

        // 3. B 拉命令，看到 PULL PENDING
        mvc.perform(get("/api/mobile/transfer/commands").param("deviceId", "dev-a").header("X-Session-Id", a))
                .andExpect(jsonPath("$.commands[0].kind").value("PULL"))
                .andExpect(jsonPath("$.commands[0].fileName").value("备忘录.txt"));

        // 4. B 上传字节
        MockMultipartFile file = new MockMultipartFile(
                "file", "备忘录.txt", "application/octet-stream", "MEMO-BYTES".getBytes());
        mvc.perform(multipart("/api/mobile/transfer/" + id + "/upload").file(file)
                        .header("X-Session-Id", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 5. A 建项目并保存
        String projectBody = """
                {"name":"跨设备落地测试","projectType":"MAJOR_ASSET_RESTRUCTURING",
                 "listedCompanyName":"listco","targetCompanyName":"targetco"}""";
        MvcResult projectResult = mvc.perform(post("/api/projects").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(projectBody))
                .andExpect(status().isOk()).andReturn();
        JsonNode projectJson = om.readTree(projectResult.getResponse().getContentAsString());
        long projectId = projectJson.path("data").isMissingNode()
                ? projectJson.path("id").asLong() : projectJson.path("data").path("id").asLong();

        String saveBody = "{\"projectId\":" + projectId + "}";
        mvc.perform(post("/api/mobile/transfer/" + id + "/save-to-project").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(saveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.name").exists());

        // 幂等：已 DELIVERED 再调，找到同一个文件直接返回
        mvc.perform(post("/api/mobile/transfer/" + id + "/save-to-project").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(saveBody))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void pullOverSizeLimitRejected() throws Exception {
        String a = register("xfer_big_a_" + System.nanoTime());
        bindAccount(a, "acct-big-a");
        Long userIdA = userIdOf(a);
        relayStore.touchDevice(userIdA, "dev-a");

        long overLimit = 200L * 1024 * 1024 + 1;
        String requestId = java.util.UUID.randomUUID().toString();
        String pullBody = """
                {"deviceId":"dev-a","projectKey":"42","remoteFileId":"file-huge",
                 "fileName":"huge.mov","fileSize":%d,"requestId":"%s"}""".formatted(overLimit, requestId);
        mvc.perform(post("/api/mobile/transfer/pull").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(pullBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        verifyNoInteractions(billing);
    }

    @Test
    void pushRoundTripContentContractAndAck() throws Exception {
        String a = register("xfer_push_a_" + System.nanoTime());
        bindAccount(a, "acct-push-a");
        Long userIdA = userIdOf(a);

        // 建项目
        String projectBody = """
                {"name":"投送来源项目","projectType":"MAJOR_ASSET_RESTRUCTURING",
                 "listedCompanyName":"listco","targetCompanyName":"targetco"}""";
        MvcResult projectResult = mvc.perform(post("/api/projects").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(projectBody))
                .andExpect(status().isOk()).andReturn();
        JsonNode projectJson = om.readTree(projectResult.getResponse().getContentAsString());
        long projectId = projectJson.path("data").isMissingNode()
                ? projectJson.path("id").asLong() : projectJson.path("data").path("id").asLong();

        // 直接走 service 层落一份带真实字节的项目文件——REST 的 /api/files/{id}/upload 是
        // 「先建空文件记录再分片写字节」两步流程，跟本测试要验的传输链路无关，走 service 更直接
        byte[] pushBytes = "PUSH-BYTES".getBytes(StandardCharsets.UTF_8);
        String storagePath = String.format("projects/%d/推送文件.txt", projectId);
        storageServiceFactory.getStorageService().save(storagePath, new ByteArrayInputStream(pushBytes));
        ProjectFile created = projectFileService.createFile(projectId, null, "推送文件.txt",
                "txt", (long) pushBytes.length, storagePath, null, userIdA);
        long fileId = created.getId();

        when(billing.charge(eq("acct-push-a"), anyLong(), anyString(), anyString()))
                .thenReturn(new TransferBillingClient.ChargeResult(1, "ledger-e2e-push"));

        String requestId = java.util.UUID.randomUUID().toString();
        String pushBody = "{\"targetDeviceId\":\"dev-b\",\"projectKey\":\"1\",\"fileId\":"
                + fileId + ",\"requestId\":\"" + requestId + "\"}";
        MvcResult pushed = mvc.perform(post("/api/mobile/transfer/push").header("X-Session-Id", a)
                        .contentType(APPLICATION_JSON).content(pushBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        long id = om.readTree(pushed.getResponse().getContentAsString()).path("id").asLong();

        // B 拉命令：应看到 PUSH STAGED
        mvc.perform(get("/api/mobile/transfer/commands").param("deviceId", "dev-b").header("X-Session-Id", a))
                .andExpect(jsonPath("$.commands[0].kind").value("PUSH"));

        // 契约红线：内容必须是 2xx + application/octet-stream + 裸字节
        MvcResult content = mvc.perform(get("/api/mobile/transfer/" + id + "/content").header("X-Session-Id", a))
                .andExpect(status().isOk()).andReturn();
        assertEquals("PUSH-BYTES", content.getResponse().getContentAsString());
        assertTrue(content.getResponse().getContentType().startsWith("application/octet-stream"));

        mvc.perform(post("/api/mobile/transfer/" + id + "/ack").header("X-Session-Id", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // ack 后内容不再可读
        mvc.perform(get("/api/mobile/transfer/" + id + "/content").header("X-Session-Id", a))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    void billingDisabledGivesReadableMessage() throws Exception {
        String a = register("xfer_disabled_a_" + System.nanoTime());
        bindAccount(a, "acct-disabled-a");
        when(billing.quote(anyString(), anyLong()))
                .thenThrow(new TransferBillingClient.TransferBillingException(
                        TransferBillingClient.TransferBillingException.Kind.DISABLED,
                        "跨设备传输未在此服务器开通"));
        mvc.perform(get("/api/mobile/transfer/quote").param("bytes", "10").header("X-Session-Id", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("跨设备传输未在此服务器开通"));
    }
}
