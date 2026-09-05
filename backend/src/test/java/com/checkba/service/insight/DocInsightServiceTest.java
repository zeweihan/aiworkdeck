package com.checkba.service.insight;

import com.checkba.model.entity.DocInsightEntity;
import com.checkba.model.entity.DocInsightFinding;
import com.checkba.model.entity.DocInsightRun;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.DocInsightEntityRepository;
import com.checkba.repository.DocInsightFindingRepository;
import com.checkba.repository.DocInsightRunRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.DocumentTextService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.QichachaService;
import com.checkba.service.ai.AuxModelResolver;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.TokenUsageService;
import com.checkba.service.ai.mcp.McpClientService;
import com.checkba.service.insight.DocInsightViews.EntityView;
import com.checkba.service.insight.DocInsightViews.InsightView;
import com.checkba.service.insight.DocInsightViews.StartResult;
import com.checkba.service.legal.PkulawChannel;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
import com.checkba.service.platform.GatewayException;
import com.checkba.service.platform.PlatformGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档解析管线的契约（dev-board#181/#182）：异步起跑与 RUNNING 中间态、单飞、
 * 企查查「REST 查不到 → MCP 模糊 → 拿全称重打」的降级链、法宝不可用不连坐、
 * 7 天缓存、一致性发现落库、鉴权与跨项目 IDOR。
 *
 * <p>全部依赖为 mock，不起 Spring 上下文。中间态断言用 {@link CountDownLatch} 卡住后台
 * （先落中间态再 submit 的服务，不卡住就是间歇红——#394 的教训）。
 */
class DocInsightServiceTest {

    static final long PID = 1L;
    static final long UID = 9L;
    static final long DOC = 10L;

    /** 正文里三处引文都是逐字的，claims 的 quote 能在这里 contains 到（fixable 的前置条件之一）。 */
    static final String TEXT = """
            本次交易由京微资易科技持有标的公司股权，依据《中华人民共和国公司法》第二十条，
            参见（2021）京01民终1234号判决。标的公司名下房产共 58 项。
            附表二：房产明细共 39 项。
            """;

    static final String MODEL_JSON = """
            {"companies":[{"name":"京微资易科技","quote":"本次交易由京微资易科技持有标的公司股权"}],
             "laws":[{"name":"《中华人民共和国公司法》","article":"第二十条","quote":"依据《中华人民共和国公司法》第二十条"}],
             "cases":[{"caseNo":"（2021）京01民终1234号","title":"","quote":"参见（2021）京01民终1234号判决"}],
             "claims":[{"subject":"标的公司","metric":"房产","value":58,"unit":"项","numberText":"58",
                        "quote":"标的公司名下房产共 58 项"},
                       {"subject":"标的公司","metric":"房产","value":39,"unit":"项","numberText":"39",
                        "quote":"附表二：房产明细共 39 项"}]}
            """;

    static final String QCC_FULL = """
            {"Name":"京微资易科技有限公司","CreditCode":"91330100799655058B","OperName":"张三",
             "RegistCapi":"1000万元","Status":"存续","Scope":"技术开发",
             "Partners":[{"StockName":"张三","StockPercent":"60%","ShouldCapi":"600万元"}]}
            """;

    DocInsightRunRepository runs = mock(DocInsightRunRepository.class);
    DocInsightEntityRepository entityRepo = mock(DocInsightEntityRepository.class);
    DocInsightFindingRepository findingRepo = mock(DocInsightFindingRepository.class);
    ProjectFileRepository files = mock(ProjectFileRepository.class);
    ProjectMemberService members = mock(ProjectMemberService.class);
    DocumentTextService docText = mock(DocumentTextService.class);
    ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
    AuxModelResolver auxModelResolver = mock(AuxModelResolver.class);
    TokenUsageService tokenUsageService = mock(TokenUsageService.class);
    QichachaService qichacha = mock(QichachaService.class);
    McpClientService mcp = mock(McpClientService.class);
    ExternalProviderResolver providerResolver = mock(ExternalProviderResolver.class);
    PlatformGatewayClient gateway = mock(PlatformGatewayClient.class);
    ChatLanguageModel model = mock(ChatLanguageModel.class);
    /** 真件而非 mock：这条分发本身就是被测契约（平台档走网关 / 自备 Key 直连）。 */
    PkulawChannel pkulaw = new PkulawChannel(mcp, providerResolver, gateway);

    final Map<Long, DocInsightRun> runStore = new ConcurrentHashMap<>();
    final Map<Long, DocInsightEntity> entityStore = new ConcurrentHashMap<>();
    final List<DocInsightFinding> findingStore = new ArrayList<>();
    final AtomicLong seq = new AtomicLong();

    InsightProperties props = new InsightProperties();
    DocInsightService svc;

    @BeforeEach
    void setUp() throws Exception {
        props.setChunkChars(100000);   // 一块跑完，断言不受切块影响
        props.setCaseServer("");       // 默认没有判决书检索通道

        // 默认自备 Key 档：既有用例里那一堆 mcp.callTool 的 stub 照旧生效
        when(providerResolver.resolve(ExternalServiceProvider.PKULAW))
                .thenReturn(ExternalServiceProvider.BYOK);

        when(members.hasReadPermission(PID, UID)).thenReturn(true);
        when(members.hasWritePermission(PID, UID)).thenReturn(true);
        when(files.findById(DOC)).thenReturn(Optional.of(doc()));
        when(docText.extractText(any())).thenReturn(TEXT);
        when(chatModelFactory.getAuxChatModel()).thenReturn(model);
        when(auxModelResolver.auxModelId()).thenReturn("qwen/qwen3.7-flash");
        when(model.generate(anyList())).thenReturn(modelReply(MODEL_JSON));

        when(runs.save(any())).thenAnswer(inv -> {
            DocInsightRun r = inv.getArgument(0, DocInsightRun.class);
            if (r.getId() == null) r.setId(seq.incrementAndGet());
            runStore.put(r.getId(), r);
            return r;
        });
        when(runs.findById(anyLong())).thenAnswer(inv ->
                Optional.ofNullable(runStore.get(inv.getArgument(0, Long.class))));
        when(runs.findByProjectIdAndDocFileIdAndStatus(anyLong(), anyLong(), anyString())).thenAnswer(inv ->
                runStore.values().stream()
                        .filter(r -> r.getProjectId().equals(inv.getArgument(0, Long.class))
                                && r.getDocFileId().equals(inv.getArgument(1, Long.class))
                                && r.getStatus().equals(inv.getArgument(2, String.class)))
                        .toList());
        when(runs.findFirstByProjectIdAndDocFileIdOrderByStartedAtDescIdDesc(anyLong(), anyLong())).thenAnswer(inv ->
                runStore.values().stream()
                        .filter(r -> r.getProjectId().equals(inv.getArgument(0, Long.class))
                                && r.getDocFileId().equals(inv.getArgument(1, Long.class)))
                        .max(Comparator.comparing(DocInsightRun::getId)));

        when(entityRepo.save(any())).thenAnswer(inv -> {
            DocInsightEntity e = inv.getArgument(0, DocInsightEntity.class);
            if (e.getId() == null) e.setId(seq.incrementAndGet());
            entityStore.put(e.getId(), e);
            return e;
        });
        when(entityRepo.findById(anyLong())).thenAnswer(inv ->
                Optional.ofNullable(entityStore.get(inv.getArgument(0, Long.class))));
        when(entityRepo.findByRunIdOrderByIdAsc(anyLong())).thenAnswer(inv ->
                entityStore.values().stream()
                        .filter(e -> e.getRunId().equals(inv.getArgument(0, Long.class)))
                        .sorted(Comparator.comparing(DocInsightEntity::getId)).toList());
        when(entityRepo.findTop1ByProjectIdAndKindAndNormKeyAndRetrievalStatusAndFetchedAtAfterOrderByFetchedAtDesc(
                anyLong(), anyString(), anyString(), anyString(), any())).thenReturn(List.of());

        when(findingRepo.save(any())).thenAnswer(inv -> {
            DocInsightFinding f = inv.getArgument(0, DocInsightFinding.class);
            if (f.getId() == null) f.setId(seq.incrementAndGet());
            findingStore.add(f);
            return f;
        });
        when(findingRepo.findByRunIdOrderByIdAsc(anyLong())).thenAnswer(inv ->
                findingStore.stream().filter(f -> f.getRunId().equals(inv.getArgument(0, Long.class))).toList());

        svc = newService();
    }

    /** 带 tokenUsage 的回包——不带的话记账那条断言永远是空的（真实通道一定会回 usage）。 */
    private static Response<AiMessage> modelReply(String text) {
        return Response.from(AiMessage.from(text), new dev.langchain4j.model.output.TokenUsage(120, 80));
    }

    private DocInsightService newService() {
        return new DocInsightService(runs, entityRepo, findingRepo, files, members, docText,
                chatModelFactory, auxModelResolver, tokenUsageService, qichacha, mcp, pkulaw, props,
                new ObjectMapper());
    }

    private static ProjectFile doc() {
        ProjectFile f = new ProjectFile();
        f.setId(DOC);
        f.setProjectId(PID);
        f.setName("股权转让协议.docx");
        f.setIsFolder(false);
        f.setIsDeleted(false);
        return f;
    }

    /** 等到 run 走到某个终态；超时即失败（不要用 sleep 猜时间）。 */
    private DocInsightRun awaitStatus(Long runId, String status) throws Exception {
        for (int i = 0; i < 200; i++) {
            DocInsightRun r = runStore.get(runId);
            if (r != null && status.equals(r.getStatus())) return r;
            Thread.sleep(25);
        }
        DocInsightRun r = runStore.get(runId);
        return fail("run " + runId + " 没有走到 " + status + "，当前 "
                + (r == null ? "null" : r.getStatus() + " / " + r.getError()));
    }

    private DocInsightEntity entityOf(String kind) {
        return entityStore.values().stream().filter(e -> kind.equals(e.getKind())).findFirst()
                .orElseThrow(() -> new AssertionError("没有抽到 " + kind + " 实体"));
    }

    // ---------------------------------------------------------------- 中间态与单飞

    @Test
    @DisplayName("startParse 先落 RUNNING 再异步跑；跑的过程中重复发起被拒")
    void 中间态与单飞() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.generate(anyList())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return modelReply(MODEL_JSON);
        });
        stubExternalsOk();

        StartResult started = svc.startParse(UID, PID, DOC);
        assertNotNull(started.runId());
        assertEquals(DocInsightRun.STATUS_RUNNING, started.status());

        assertTrue(entered.await(5, TimeUnit.SECONDS), "后台线程没有进到模型调用");
        DocInsightRun mid = runStore.get(started.runId());
        assertEquals(DocInsightRun.STATUS_RUNNING, mid.getStatus());
        assertNotNull(mid.getPhase(), "RUNNING 期间必须有可读进度短语");
        assertEquals("qwen/qwen3.7-flash", mid.getModel());

        IllegalStateException again = assertThrows(IllegalStateException.class,
                () -> svc.startParse(UID, PID, DOC));
        assertTrue(again.getMessage().contains("正在解析"), again.getMessage());

        release.countDown();
        DocInsightRun done = awaitStatus(started.runId(), DocInsightRun.STATUS_DONE);
        assertNull(done.getError());
        assertNotNull(done.getFinishedAt());
    }

    @Test
    @DisplayName("跑完一轮：三类实体各一个 + 一条数量矛盾；token 记账带模型 ID")
    void 完整跑通() throws Exception {
        stubExternalsOk();
        StartResult started = svc.startParse(UID, PID, DOC);
        DocInsightRun done = awaitStatus(started.runId(), DocInsightRun.STATUS_DONE);
        assertTrue(done.getPhase().contains("完成"), done.getPhase());

        assertEquals(3, entityStore.size(), "企业 / 法规 / 案例各一个（LLM 与正则抽到的同一个应当合并）");
        assertEquals("《中华人民共和国公司法》第二十条", entityOf(DocInsightEntity.KIND_LAW).getName());
        assertEquals("（2021）京01民终1234号", entityOf(DocInsightEntity.KIND_CASE).getName());

        assertEquals(1, findingStore.size());
        DocInsightFinding f = findingStore.get(0);
        assertEquals(DocInsightFinding.KIND_COUNT_MISMATCH, f.getKind());
        assertTrue(f.getDetailJson().contains("\"numberText\":\"58\""), f.getDetailJson());
        assertTrue(f.getDetailJson().contains("\"fixable\":true"), f.getDetailJson());

        verify(tokenUsageService).recordUsage(eq(PID), eq(UID), eq("qwen/qwen3.7-flash"), any(), eq(null));
    }

    // ---------------------------------------------------------------- 企查查降级链

    @Test
    @DisplayName("企查查：REST 按简称查不到 → MCP 模糊拿全称 → 用全称重打 REST 成功")
    void 企查查降级链() throws Exception {
        when(qichacha.queryEciInfoJson("京微资易科技"))
                .thenThrow(new RuntimeException("未查询到相关企业信息"));
        when(mcp.callTool(eq(DocInsightService.QICHACHA_MCP_SERVER), eq(DocInsightService.QICHACHA_MCP_TOOL), anyMap()))
                .thenReturn("{\"data\":[{\"企业名称\":\"京微资易科技有限公司\",\"统一社会信用代码\":\"91330100799655058B\"}]}");
        when(qichacha.queryEciInfoJson("京微资易科技有限公司")).thenReturn(QCC_FULL);
        stubPkulawUnavailable();

        StartResult started = svc.startParse(UID, PID, DOC);
        awaitStatus(started.runId(), DocInsightRun.STATUS_DONE);

        DocInsightEntity company = entityOf(DocInsightEntity.KIND_COMPANY);
        assertEquals(DocInsightEntity.RETRIEVAL_OK, company.getRetrievalStatus());
        assertEquals("qichacha+mcp", company.getRetrievalSource());
        assertTrue(company.getRetrievalJson().contains("91330100799655058B"), company.getRetrievalJson());
        assertTrue(company.getRetrievalJson().contains("股东"), "股东名单要一并存下来");
        verify(qichacha).queryEciInfoJson("京微资易科技有限公司");
    }

    @Test
    @DisplayName("企查查 MCP 也不可用时给可读原因，且不把它说成通道故障")
    void 企查查两条路都查不到() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenThrow(new RuntimeException("未查询到相关企业信息"));
        when(mcp.callTool(eq(DocInsightService.QICHACHA_MCP_SERVER), anyString(), anyMap()))
                .thenReturn("Error: Unknown MCP server: qichacha-company");
        stubPkulawUnavailable();

        awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);
        DocInsightEntity company = entityOf(DocInsightEntity.KIND_COMPANY);
        assertEquals(DocInsightEntity.RETRIEVAL_NOT_FOUND, company.getRetrievalStatus(),
                "查完了、上游说没有 ≠ 通道故障");
        assertTrue(company.getRetrievalNote().contains("未查询到该企业"), company.getRetrievalNote());
    }

    @Test
    @DisplayName("网关失败是 UNAVAILABLE（不是查无此企业），并带结构化原因码 hint=NO_CREDITS")
    void 网关失败落不可用() throws Exception {
        when(qichacha.queryEciInfoJson(anyString()))
                .thenThrow(new GatewayException(GatewayException.Kind.NO_CREDITS, "账户 Credits 余额不足"));
        stubPkulawUnavailable();

        awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);
        DocInsightEntity company = entityOf(DocInsightEntity.KIND_COMPANY);
        assertEquals(DocInsightEntity.RETRIEVAL_UNAVAILABLE, company.getRetrievalStatus());
        assertEquals(DocInsightEntity.HINT_NO_CREDITS, company.getRetrievalHint());
        assertTrue(company.getRetrievalNote().contains("余额不足"), company.getRetrievalNote());
        verify(mcp, never()).callTool(eq(DocInsightService.QICHACHA_MCP_SERVER), anyString(), anyMap());
    }

    // ---------------------------------------------------------------- 法宝与案例

    @Test
    @DisplayName("法宝返回 Error: 前缀（点数耗尽是预期内）→ UNAVAILABLE，其余实体照常跑完")
    void 法宝不可用不连坐() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        when(mcp.callTool(eq(DocInsightService.PKULAW_SEMANTIC), eq("get_article"), anyMap()))
                .thenReturn("Error: MCP call failed: 401 checking remaining points");

        awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        DocInsightEntity law = entityOf(DocInsightEntity.KIND_LAW);
        assertEquals(DocInsightEntity.RETRIEVAL_UNAVAILABLE, law.getRetrievalStatus());
        assertEquals(DocInsightService.PKULAW_SEMANTIC, law.getRetrievalSource());
        assertTrue(law.getRetrievalNote().contains("401"), law.getRetrievalNote());
        assertEquals(DocInsightEntity.RETRIEVAL_OK, entityOf(DocInsightEntity.KIND_COMPANY).getRetrievalStatus(),
                "一个通道挂了不该连坐另一类实体");
    }

    @Test
    @DisplayName("案例通道未配置 → UNAVAILABLE 且写明原因；配上 server 名就自动接入")
    void 案例通道() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        stubPkulawUnavailable();

        awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);
        DocInsightEntity c = entityOf(DocInsightEntity.KIND_CASE);
        assertEquals(DocInsightEntity.RETRIEVAL_UNAVAILABLE, c.getRetrievalStatus());
        assertTrue(c.getRetrievalNote().contains("未配置"), c.getRetrievalNote());

        // 配上就走配置里的 server / tool，代码一行不改
        entityStore.clear();
        findingStore.clear();
        props.setCaseServer("some-judgment-mcp");
        props.setCaseTool("search_judgment");
        when(mcp.callTool(eq("some-judgment-mcp"), eq("search_judgment"), anyMap()))
                .thenReturn("{\"docs\":[{\"title\":\"判决书\"}]}");

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);
        DocInsightEntity c2 = entityOf(DocInsightEntity.KIND_CASE);
        assertEquals(DocInsightEntity.RETRIEVAL_OK, c2.getRetrievalStatus());
        assertEquals("some-judgment-mcp", c2.getRetrievalSource());
    }

    // ---------------------------------------------------------------- 案号识别先导步

    static final String CASE_NO = "（2021）京01民终1234号";
    static final String CASE_TITLE = "甲与乙合同纠纷二审民事判决书";
    static final String ANHAO_HIT = """
            [{"text":"（2021）京01民终1234号","caseFlag":"（2021）京01民终1234号",
              "court":"北京市第一中级人民法院","title":"甲与乙合同纠纷二审民事判决书",
              "url":"https://www.pkulaw.com/pfnl/abc","gid":"abc"}]
            """;

    @Test
    @DisplayName("案号识别命中 → 改用判决书标题去打全文检索，识别结果一并存进 recognition")
    void 案号识别命中改用标题检索() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        stubPkulawUnavailable();
        stubCaseChannel();
        when(mcp.callTool(eq("pkulaw-case-number"), eq("anhao_recognition"), anyMap())).thenReturn(ANHAO_HIT);
        when(mcp.callTool(eq("pkulaw-case-semantic"), eq("search_case"), anyMap()))
                .thenReturn("{\"docs\":[{\"title\":\"甲与乙合同纠纷二审民事判决书\"}]}");

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        // 裸案号语义搜命中率低，识别拿到标题就该改用标题
        verify(mcp).callTool(eq("pkulaw-case-semantic"), eq("search_case"), eq(Map.of("text", CASE_TITLE)));
        DocInsightEntity c = entityOf(DocInsightEntity.KIND_CASE);
        assertEquals(DocInsightEntity.RETRIEVAL_OK, c.getRetrievalStatus());
        assertTrue(c.getRetrievalJson().contains("recognition"), c.getRetrievalJson());
        assertTrue(c.getRetrievalJson().contains("北京市第一中级人民法院"), c.getRetrievalJson());
    }

    @Test
    @DisplayName("案号识别不可用 → 静默走原路（拿案号原文检索），不比没有这一步更差")
    void 案号识别失败走原路() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        stubPkulawUnavailable();
        stubCaseChannel();
        when(mcp.callTool(eq("pkulaw-case-number"), eq("anhao_recognition"), anyMap()))
                .thenReturn("Error: MCP call failed: 401 checking remaining points");
        when(mcp.callTool(eq("pkulaw-case-semantic"), eq("search_case"), anyMap()))
                .thenReturn("{\"docs\":[{\"title\":\"判决书\"}]}");

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        verify(mcp).callTool(eq("pkulaw-case-semantic"), eq("search_case"), eq(Map.of("text", CASE_NO)));
        DocInsightEntity c = entityOf(DocInsightEntity.KIND_CASE);
        assertEquals(DocInsightEntity.RETRIEVAL_OK, c.getRetrievalStatus());
        assertFalse(c.getRetrievalJson().contains("recognition"), c.getRetrievalJson());
    }

    @Test
    @DisplayName("识别命中但全文检索没命中 → 仍记 OK（法院/标题/法宝链接已经是有用结果），note 写明只有这一半")
    void 识别命中而全文失败仍算命中() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        stubPkulawUnavailable();
        stubCaseChannel();
        when(mcp.callTool(eq("pkulaw-case-number"), eq("anhao_recognition"), anyMap())).thenReturn(ANHAO_HIT);
        when(mcp.callTool(eq("pkulaw-case-semantic"), eq("search_case"), anyMap()))
                .thenReturn("Error: MCP call failed: 401 checking remaining points");

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        DocInsightEntity c = entityOf(DocInsightEntity.KIND_CASE);
        assertEquals(DocInsightEntity.RETRIEVAL_OK, c.getRetrievalStatus());
        assertEquals("pkulaw-case-number", c.getRetrievalSource());
        assertTrue(c.getRetrievalNote().contains("仅返回案号识别结果"), c.getRetrievalNote());
        assertTrue(c.getRetrievalJson().contains("https://www.pkulaw.com/pfnl/abc"), c.getRetrievalJson());
    }

    @Test
    @DisplayName("全文检索因余额不足失败、识别命中 → 记 OK 且原因码清掉（别对一条有结果的案例摆「去充值」）")
    void 识别命中后不留旧原因码() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        when(providerResolver.resolve(ExternalServiceProvider.PKULAW))
                .thenReturn(ExternalServiceProvider.PLATFORM);
        stubCaseChannel();
        stubGatewayRaw(ANHAO_HIT);
        when(gateway.call(eq("pkulaw"), eq("search_case"), anyMap(), anyInt()))
                .thenThrow(new GatewayException(GatewayException.Kind.NO_CREDITS, "账户 Credits 余额不足"));

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        DocInsightEntity c = entityOf(DocInsightEntity.KIND_CASE);
        assertEquals(DocInsightEntity.RETRIEVAL_OK, c.getRetrievalStatus());
        assertNull(c.getRetrievalHint(), "这一行最终是 OK，配置类引导不该挂在它上面");
    }

    // ---------------------------------------------------------------- 法条引用校验

    static final String CITATION_SERVER = "pkulaw-citation-validator";
    static final String AUTHORITATIVE = """
            {"title":"中华人民共和国公司法（2023 修订）","article_number":"20",
             "original_text":"公司股东应当遵守法律、行政法规和公司章程，依法行使股东权利。",
             "url":"https://www.pkulaw.com/chl/gsf20","implement_date":"2024-07-01"}
            """;
    static final String OLD_VERSION_CANDIDATE = """
            {"title":"中华人民共和国公司法（2018 修正）","article_number":"16",
             "original_text":"公司向其他企业投资或者为他人提供担保，依照公司章程的规定…",
             "url":"https://www.pkulaw.com/chl/gsf2018"}
            """;

    @Test
    @DisplayName("条号查得到 → 权威原文回填进 LAW 实体的 authoritative，不产生发现")
    void 引用校验回填权威原文() throws Exception {
        stubExternalsOk();
        stubCitation("[" + AUTHORITATIVE + "]");

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        DocInsightEntity law = entityOf(DocInsightEntity.KIND_LAW);
        assertTrue(law.getRetrievalJson() != null && law.getRetrievalJson().contains("authoritative"),
                String.valueOf(law.getRetrievalJson()));
        assertTrue(law.getRetrievalJson().contains("2024-07-01"), law.getRetrievalJson());
        assertTrue(citationFindings().isEmpty(), "查得到就不是发现");
    }

    @Test
    @DisplayName("条号在法宝检索不到（返回空数组）→ CITATION_NOT_FOUND，永不给一键修改")
    void 引用条号查不到() throws Exception {
        stubExternalsOk();
        stubCitation("[]");

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        List<DocInsightFinding> found = citationFindings();
        assertEquals(1, found.size());
        DocInsightFinding f = found.get(0);
        assertEquals(DocInsightFinding.KIND_CITATION_NOT_FOUND, f.getKind());
        assertTrue(f.getTitle().contains("第二十条"), f.getTitle());
        assertTrue(f.getDetailJson().contains("\"citedArabic\":\"20\""), f.getDetailJson());
        assertTrue(f.getDetailJson().contains("\"fixable\":false"), f.getDetailJson());
        assertFalse(f.getDetailJson().contains("numberText"), "引用类发现绝不下发可替换的数字原文");
    }

    @Test
    @DisplayName("按内容定位到别的条号 → CITATION_MISMATCH，候选只提示人工核对（旧版重编号陷阱）")
    void 引用内容与条文不符() throws Exception {
        stubExternalsOk();
        stubCitation("[" + AUTHORITATIVE + "," + OLD_VERSION_CANDIDATE + "]");

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        List<DocInsightFinding> found = citationFindings();
        assertEquals(1, found.size());
        DocInsightFinding f = found.get(0);
        assertEquals(DocInsightFinding.KIND_CITATION_MISMATCH, f.getKind());
        assertTrue(f.getDetailJson().contains("\"articleNumber\":\"16\""), f.getDetailJson());
        assertTrue(f.getDetailJson().contains("2018 修正"), f.getDetailJson());
        assertTrue(f.getDetailJson().contains("\"fixable\":false"), f.getDetailJson());
        assertFalse(f.getDetailJson().contains("numberText"), "条文重编号决定了机械改写条号必然出错");
        // 引用条目本身仍然回填成权威原文
        assertTrue(entityOf(DocInsightEntity.KIND_LAW).getRetrievalJson().contains("authoritative"));
    }

    @Test
    @DisplayName("校验通道未配置 → 整步跳过（校验不可用 ≠ 引用有错）")
    void 引用校验未配置整步跳过() throws Exception {
        stubExternalsOk();
        awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        verify(mcp, never()).callTool(eq(CITATION_SERVER), anyString(), anyMap());
        assertTrue(citationFindings().isEmpty());
    }

    @Test
    @DisplayName("校验通道自身报错 → 跳过该条，不产生发现")
    void 引用校验不可用不报发现() throws Exception {
        stubExternalsOk();
        stubCitation("Error: MCP call failed: 401 checking remaining points");

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);
        assertTrue(citationFindings().isEmpty());
    }

    @Test
    @DisplayName("引用再多也最多校验 30 条，一份怪文档打不爆上游")
    void 引用校验上限() throws Exception {
        StringBuilder sb = new StringBuilder("引用清单：");
        for (int i = 1; i <= 35; i++) sb.append("《测试法》第").append(i).append("条；");
        when(docText.extractText(any())).thenReturn(sb.toString());
        when(model.generate(anyList())).thenReturn(modelReply("{}"));   // 只留正则那条腿
        stubExternalsOk();
        stubCitation("[" + AUTHORITATIVE + "]");

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        assertEquals(35, entityStore.size(), "35 条引用应当抽成 35 个 LAW 实体");
        verify(mcp, org.mockito.Mockito.times(30))
                .callTool(eq(CITATION_SERVER), eq("adjust_provisions"), anyMap());
    }

    // ---------------------------------------------------------------- 缓存

    @Test
    @DisplayName("7 天内同项目同实体的成功结果直接复用，不打上游")
    void 缓存复用() throws Exception {
        DocInsightEntity cached = new DocInsightEntity();
        cached.setRetrievalStatus(DocInsightEntity.RETRIEVAL_OK);
        cached.setRetrievalSource("qichacha");
        cached.setRetrievalJson("{\"basic\":{\"企业名称\":\"京微资易科技有限公司\"}}");
        cached.setFetchedAt(LocalDateTime.now().minusDays(1));
        when(entityRepo.findTop1ByProjectIdAndKindAndNormKeyAndRetrievalStatusAndFetchedAtAfterOrderByFetchedAtDesc(
                eq(PID), eq(DocInsightEntity.KIND_COMPANY), anyString(),
                eq(DocInsightEntity.RETRIEVAL_OK), any())).thenReturn(List.of(cached));
        stubPkulawUnavailable();

        awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        DocInsightEntity company = entityOf(DocInsightEntity.KIND_COMPANY);
        assertEquals(DocInsightEntity.RETRIEVAL_OK, company.getRetrievalStatus());
        assertEquals(cached.getRetrievalJson(), company.getRetrievalJson());
        verify(qichacha, never()).queryEciInfoJson(anyString());
    }

    @Test
    @DisplayName("单实体重新检索绕过缓存")
    void 重新检索绕过缓存() throws Exception {
        stubExternalsOk();
        awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);
        DocInsightEntity company = entityOf(DocInsightEntity.KIND_COMPANY);

        // 摆一条「新鲜的缓存」在那儿：refresh 必须绕过它去打上游，否则「重新检索」按钮是假的
        DocInsightEntity stale = new DocInsightEntity();
        stale.setRetrievalStatus(DocInsightEntity.RETRIEVAL_OK);
        stale.setRetrievalSource("qichacha");
        stale.setRetrievalJson("{\"basic\":{\"企业名称\":\"缓存里的旧值\"}}");
        stale.setFetchedAt(LocalDateTime.now());
        when(entityRepo.findTop1ByProjectIdAndKindAndNormKeyAndRetrievalStatusAndFetchedAtAfterOrderByFetchedAtDesc(
                anyLong(), anyString(), anyString(), anyString(), any())).thenReturn(List.of(stale));

        EntityView v = svc.refreshEntity(UID, PID, company.getId());
        assertEquals(DocInsightEntity.RETRIEVAL_OK, v.retrievalStatus());
        assertNotNull(v.detail(), "单实体接口要带全量检索结果");
        assertFalse(v.detail().toString().contains("缓存里的旧值"), "refresh 必须绕过 7 天缓存");
        assertTrue(v.detail().toString().contains("91330100799655058B"));
    }

    // ---------------------------------------------------------------- 读取与瘦身

    @Test
    @DisplayName("列表不带检索详情，只给 hasDetail；findings 带完整 detail")
    void 列表瘦身而发现不瘦身() throws Exception {
        stubExternalsOk();
        awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        InsightView view = svc.latest(UID, PID, DOC);
        assertEquals(DocInsightRun.STATUS_DONE, view.run().status());
        assertEquals(3, view.entities().size());
        EntityView company = view.entities().stream()
                .filter(e -> DocInsightEntity.KIND_COMPANY.equals(e.kind())).findFirst().orElseThrow();
        assertNull(company.detail(), "列表里不搬全文");
        assertTrue(company.hasDetail());
        assertFalse(company.mentions().isEmpty(), "出处要带上，前端靠它定位");

        assertEquals(1, view.findings().size());
        assertNotNull(view.findings().get(0).detail(), "一致性发现必须带完整 detail，否则一键修改没数据");
        assertTrue(view.findings().get(0).detail().path("claims").isArray());

        assertNotNull(svc.entityDetail(UID, PID, company.id()).detail());
    }

    @Test
    @DisplayName("没解析过时 run 为 null，不是报错")
    void 没解析过() {
        InsightView view = svc.latest(UID, PID, DOC);
        assertNull(view.run());
        assertTrue(view.entities().isEmpty());
    }

    // ---------------------------------------------------------------- 失败与鉴权

    @Test
    @DisplayName("读不出文字 → FAILED + 可读原因")
    void 读不出文字() throws Exception {
        when(docText.extractText(any())).thenReturn("   ");
        DocInsightRun r = awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_FAILED);
        assertTrue(r.getError().contains("读不出"), r.getError());
    }

    @Test
    @DisplayName("辅助模型未配置 → 整轮 FAILED，把「去设置里选一个」原样透出")
    void 辅助模型未配置() throws Exception {
        when(chatModelFactory.getAuxChatModel())
                .thenThrow(new com.checkba.exception.FeatureNotConfiguredException("ai-aux-model", "辅助模型不在可用清单内"));
        DocInsightRun r = awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_FAILED);
        assertTrue(r.getError().contains("辅助模型"), r.getError());
    }

    @Test
    @DisplayName("单块模型输出坏掉只跳过这一块，不炸整轮")
    void 模型输出坏掉不炸整轮() throws Exception {
        when(model.generate(anyList())).thenReturn(modelReply("对不起，我不能这么做。"));
        stubPkulawUnavailable();
        DocInsightRun r = awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);
        assertNotNull(r);
        // 正则那条腿仍在：法规与案例照抽
        assertEquals(2, entityStore.size());
    }

    @Test
    void 解析要写权限而读结果只要读权限() {
        when(members.hasWritePermission(PID, UID)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> svc.startParse(UID, PID, DOC));
        assertNotNull(svc.latest(UID, PID, DOC));

        when(members.hasReadPermission(PID, UID)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> svc.latest(UID, PID, DOC));
        assertThrows(IllegalArgumentException.class, () -> svc.startParse(null, PID, DOC));
    }

    @Test
    @DisplayName("跨项目的 entityId 一律当作不存在")
    void 跨项目条目拒绝() throws Exception {
        stubExternalsOk();
        awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);
        Long id = entityOf(DocInsightEntity.KIND_COMPANY).getId();

        when(members.hasReadPermission(2L, UID)).thenReturn(true);
        when(members.hasWritePermission(2L, UID)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> svc.entityDetail(UID, 2L, id));
        assertThrows(IllegalArgumentException.class, () -> svc.refreshEntity(UID, 2L, id));
    }

    @Test
    void 文件夹与他项目文件不能解析() {
        ProjectFile folder = doc();
        folder.setId(11L);
        folder.setIsFolder(true);
        when(files.findById(11L)).thenReturn(Optional.of(folder));
        assertThrows(IllegalArgumentException.class, () -> svc.startParse(UID, PID, 11L));

        ProjectFile other = doc();
        other.setId(12L);
        other.setProjectId(2L);
        when(files.findById(12L)).thenReturn(Optional.of(other));
        assertThrows(IllegalArgumentException.class, () -> svc.startParse(UID, PID, 12L));
        assertThrows(IllegalArgumentException.class, () -> svc.startParse(UID, PID, null));
    }

    // ---------------------------------------------------------------- 辅助

    // ---------------------------------------------------------------- 平台网关（dev-board#395）

    /** 网关回包与 BYOK 档同形：JSON-RPC 正文放在 data.raw 里，由桌面端既有解析器解析。 */
    private void stubGatewayRaw(String raw) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode data = new ObjectMapper().createObjectNode();
            data.put("raw", raw);
            when(gateway.call(eq("pkulaw"), anyString(), anyMap(), anyInt()))
                    .thenReturn(new PlatformGatewayClient.Result(data, 1, 1, "call"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("平台档：法规/案号识别/判决书/引用校验全部走网关，一次法宝 MCP 都不打")
    void 平台档法宝全部走网关() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        when(providerResolver.resolve(ExternalServiceProvider.PKULAW))
                .thenReturn(ExternalServiceProvider.PLATFORM);
        stubCaseChannel();
        props.setCitationServer(CITATION_SERVER);
        props.setCitationTool("adjust_provisions");
        stubGatewayRaw(ANHAO_HIT);

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        // op 就是法宝的工具名；端点写死在网关服务端
        verify(gateway).call(eq("pkulaw"), eq("get_article"), anyMap(), anyInt());
        verify(gateway).call(eq("pkulaw"), eq("anhao_recognition"), anyMap(), anyInt());
        verify(gateway).call(eq("pkulaw"), eq("search_case"), anyMap(), anyInt());
        verify(gateway).call(eq("pkulaw"), eq("adjust_provisions"), anyMap(), anyInt());
        // 打包态的桌面端没有法宝 token，直连 MCP 只会换回 401 Missing Credentials
        verify(mcp, never()).callTool(startsWith("pkulaw"), anyString(), anyMap());

        assertEquals(DocInsightEntity.RETRIEVAL_OK, entityOf(DocInsightEntity.KIND_LAW).getRetrievalStatus());
        assertEquals(DocInsightEntity.RETRIEVAL_OK, entityOf(DocInsightEntity.KIND_CASE).getRetrievalStatus());
    }

    @Test
    @DisplayName("平台档余额不足：UNAVAILABLE + hint=NO_CREDITS，原因保留，不当成查无此条文")
    void 平台档网关失败落不可用() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        when(providerResolver.resolve(ExternalServiceProvider.PKULAW))
                .thenReturn(ExternalServiceProvider.PLATFORM);
        when(gateway.call(eq("pkulaw"), anyString(), anyMap(), anyInt()))
                .thenThrow(new GatewayException(GatewayException.Kind.NO_CREDITS, "账户 Credits 余额不足"));

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        DocInsightEntity law = entityOf(DocInsightEntity.KIND_LAW);
        assertEquals(DocInsightEntity.RETRIEVAL_UNAVAILABLE, law.getRetrievalStatus());
        assertEquals(DocInsightEntity.HINT_NO_CREDITS, law.getRetrievalHint());
        assertTrue(law.getRetrievalNote().contains("余额不足"), law.getRetrievalNote());
        assertFalse(law.getRetrievalNote().contains("本次不可用"), law.getRetrievalNote());
        assertEquals(DocInsightEntity.HINT_NO_CREDITS, lawView().retrievalHint(), "原因码必须上到 REST 视图");
    }

    @Test
    @DisplayName("平台档未连接账户：配置类失败不说「本次不可用」（恒定状态，等不来）")
    void 未连接账户不说本次不可用() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        when(providerResolver.resolve(ExternalServiceProvider.PKULAW))
                .thenReturn(ExternalServiceProvider.PLATFORM);
        when(gateway.call(eq("pkulaw"), anyString(), anyMap(), anyInt()))
                .thenThrow(new GatewayException(GatewayException.Kind.NOT_CONNECTED, "尚未连接 AI WorkDeck 账户"));

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        DocInsightEntity law = entityOf(DocInsightEntity.KIND_LAW);
        assertEquals(DocInsightEntity.RETRIEVAL_UNAVAILABLE, law.getRetrievalStatus());
        assertFalse(law.getRetrievalNote().contains("本次不可用"), law.getRetrievalNote());
        assertEquals(DocInsightEntity.HINT_NOT_CONNECTED, lawView().retrievalHint());
    }

    @Test
    @DisplayName("本机没有该通道凭证：note 说「未配置」，不说「本次不可用」（那是等不来的等待）")
    void 未配置凭证不说本次不可用() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        when(mcp.callTool(eq(DocInsightService.PKULAW_SEMANTIC), anyString(), anyMap()))
                .thenReturn(com.checkba.service.ai.mcp.McpProvider.NO_CREDENTIAL_PREFIX
                        + DocInsightService.PKULAW_SEMANTIC);

        awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        DocInsightEntity law = entityOf(DocInsightEntity.KIND_LAW);
        assertEquals(DocInsightEntity.RETRIEVAL_UNAVAILABLE, law.getRetrievalStatus());
        assertTrue(law.getRetrievalNote().contains("未配置"), law.getRetrievalNote());
        assertFalse(law.getRetrievalNote().contains("本次不可用"), law.getRetrievalNote());
        assertEquals(DocInsightEntity.HINT_NO_CREDENTIAL, lawView().retrievalHint());
    }

    @Test
    @DisplayName("上游明确回空 = NOT_FOUND，且按「跑完了」计进摘要（不是检索成功 0 个）")
    void 未命中算跑完的检索() throws Exception {
        when(qichacha.queryEciInfoJson(anyString()))
                .thenThrow(new RuntimeException("未查询到相关企业信息"));
        when(mcp.callTool(eq(DocInsightService.QICHACHA_MCP_SERVER), anyString(), anyMap()))
                .thenReturn("Error: Unknown MCP server: qichacha-company");
        when(mcp.callTool(eq(DocInsightService.PKULAW_SEMANTIC), anyString(), anyMap())).thenReturn("[]");

        DocInsightRun done = awaitStatus(svc.startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        assertEquals(DocInsightEntity.RETRIEVAL_NOT_FOUND, entityOf(DocInsightEntity.KIND_LAW).getRetrievalStatus());
        assertEquals(DocInsightEntity.RETRIEVAL_NOT_FOUND,
                entityOf(DocInsightEntity.KIND_COMPANY).getRetrievalStatus());
        assertTrue(done.getPhase().contains("未命中 2 个"),
                "未命中必须单列，否则用户读到的是「一次都没查成」：" + done.getPhase());
        assertFalse(entityOf(DocInsightEntity.KIND_LAW).getRetrievalNote().contains("hi@aiworkdeck.com"),
                "查无此条文不该指向客服");
    }

    @Test
    @DisplayName("瞬时失败（上游故障）不带原因码：窗格照旧给「本次不可用」+ 重试")
    void 瞬时失败不带原因码() throws Exception {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        when(providerResolver.resolve(ExternalServiceProvider.PKULAW))
                .thenReturn(ExternalServiceProvider.PLATFORM);
        when(gateway.call(eq("pkulaw"), anyString(), anyMap(), anyInt()))
                .thenThrow(new GatewayException(GatewayException.Kind.UPSTREAM_FAILED, "上游供应商超时"));

        awaitStatus(newService().startParse(UID, PID, DOC).runId(), DocInsightRun.STATUS_DONE);

        DocInsightEntity law = entityOf(DocInsightEntity.KIND_LAW);
        assertEquals(DocInsightEntity.RETRIEVAL_UNAVAILABLE, law.getRetrievalStatus());
        assertNull(law.getRetrievalHint(), "瞬时故障重试是真出路，不许摆成配置引导");
        assertTrue(law.getRetrievalNote().contains("本次不可用"), law.getRetrievalNote());
        assertTrue(law.getRetrievalNote().contains("稍后重试"), law.getRetrievalNote());
    }

    /** 法规实体的 REST 视图（原因码是给前端的契约，只断言实体字段不够）。 */
    private EntityView lawView() {
        return svc.latest(UID, PID, DOC).entities().stream()
                .filter(e -> DocInsightEntity.KIND_LAW.equals(e.kind())).findFirst().orElseThrow();
    }

    private void stubExternalsOk() {
        when(qichacha.queryEciInfoJson(anyString())).thenReturn(QCC_FULL);
        stubPkulawUnavailable();
    }

    /** 判决书通道 + 案号识别先导步都配上（yml 的默认形态）。 */
    private void stubCaseChannel() {
        props.setCaseServer("pkulaw-case-semantic");
        props.setCaseTool("search_case");
        props.setCaseArg("text");
        props.setCaseNumberServer("pkulaw-case-number");
        props.setCaseNumberTool("anhao_recognition");
    }

    private void stubCitation(String reply) {
        props.setCitationServer(CITATION_SERVER);
        props.setCitationTool("adjust_provisions");
        when(mcp.callTool(eq(CITATION_SERVER), eq("adjust_provisions"), anyMap())).thenReturn(reply);
    }

    private List<DocInsightFinding> citationFindings() {
        return findingStore.stream().filter(f -> f.getKind().startsWith("CITATION_")).toList();
    }

    /** 法宝当前点数耗尽，真调必失败——测试里把这条当常态，正好覆盖降级路径。 */
    private void stubPkulawUnavailable() {
        when(mcp.callTool(eq(DocInsightService.PKULAW_SEMANTIC), anyString(), anyMap()))
                .thenReturn("Error: MCP call failed: 401 checking remaining points");
        when(mcp.callTool(eq(DocInsightService.PKULAW_KEYWORD), anyString(), anyMap()))
                .thenReturn("Error: MCP call failed: 401 checking remaining points");
    }
}
