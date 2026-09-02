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
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.QichachaService;
import com.checkba.service.ai.AuxModelResolver;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PlatformAiUserScope;
import com.checkba.service.ai.TokenUsageService;
import com.checkba.service.ai.mcp.McpClientService;
import com.checkba.service.ai.mcp.McpProvider;
import com.checkba.service.insight.DocInsightChecks.Claim;
import com.checkba.service.insight.DocInsightExtraction.Mention;
import com.checkba.service.insight.DocInsightExtraction.Parsed;
import com.checkba.service.insight.DocInsightExtraction.RawEntity;
import com.checkba.service.insight.DocInsightViews.EntityView;
import com.checkba.service.insight.DocInsightViews.FindingView;
import com.checkba.service.insight.DocInsightViews.InsightView;
import com.checkba.service.insight.DocInsightViews.MentionView;
import com.checkba.service.insight.DocInsightViews.RunView;
import com.checkba.service.insight.DocInsightViews.StartResult;
import com.checkba.service.legal.PkulawChannel;
import com.checkba.service.platform.GatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档「解析」管线（dev-board#181 后端 / #182）：通读一份文档 →
 * 抽实体（企业 / 法规 / 案例）→ 逐个打外部库 → 同时做文档内部一致性校验。
 * 结果供前端「依据」窗格轮询展示。
 *
 * <h3>三条硬口径</h3>
 * <ol>
 *   <li><b>写权限才能解析</b>。解析要花 AI token 与外部库额度，与勾稽核查同口径
 *       （只读成员看得到结果，但不能替项目花钱）。</li>
 *   <li><b>外部通道不可用是一等状态，不是失败</b>。法宝点数耗尽、企查查未开放、
 *       案例通道未配置，一律 {@code UNAVAILABLE} + 可读原因写进 retrievalNote；
 *       整轮解析照常跑完。一个通道挂了不该让另外两类实体也查不成。
 *       <b>「查完了，上游说没有」是另一回事</b>（{@code NOT_FOUND}）：那是一次成功的检索，
 *       摘要里按完成计数，note 里也不许出现「稍后重试 / 联系客服」。</li>
 *   <li><b>逐个实体检索完就落库</b>。前端轮询看到的是一个个点亮的过程，
 *       而不是等三分钟后一次性出现。</li>
 * </ol>
 *
 * <h3>法宝走哪条路</h3>
 * 法宝的四个通道（法规 / 案号识别 / 判决书 / 引用校验）一律经
 * {@link PkulawChannel}：平台档走官网网关，自备 Key 档才直连 MCP。
 * 直接打 {@link McpClientService} 等于在用户机器上发一个空 Bearer——
 * 打包态的桌面端没有 {@code PKULAW_TOKEN}，换回来的是 401 Missing Credentials（dev-board#395）。
 * 企查查的模糊搜索（{@link #resolveFullNameViaMcp}）仍直连：网关目前没有对应的 op。
 *
 * <h3>跨线程红线</h3>
 * 管线跑在自己的线程池里，而平台通道的身份（{@link PlatformAiUserScope}）是 ThreadLocal、
 * <b>不跟随线程池</b>。整段管线必须包在 {@code PlatformAiUserScope.run(userId, ...)} 里，
 * 否则云多租户形态下第一次调辅助模型就会抛「本次 AI 调用未携带用户身份」——
 * 一个与真实原因毫无关系的提示（.claude/agents/ai-chat.md 同款地雷）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocInsightService {

    /** 企查查智能体数据平台的 MCP server 名（模糊搜索，把简称解析成工商全称）。 */
    public static final String QICHACHA_MCP_SERVER = "qichacha-company";
    public static final String QICHACHA_MCP_TOOL = "get_company_by_query";
    public static final String PKULAW_SEMANTIC = "pkulaw-semantic";
    public static final String PKULAW_KEYWORD = "pkulaw-keyword";

    /** MCP / 网关返回的失败一律以此开头（{@link McpClientService#callTool} 的既有约定）。 */
    private static final String ERROR_PREFIX = "Error";
    /** 经营范围太长，工商详情里它一个字段能顶半页，截断后存。 */
    private static final int SCOPE_LIMIT = 500;

    /** 案号识别送上去的文本上限（案号本身 + 一句上下文足够了，别把整段搬过去）。 */
    private static final int RECOGNITION_TEXT_LIMIT = 300;
    /** 一次解析最多校验多少条法条引用——防一份「引用了三百条」的怪文档把上游打爆。 */
    private static final int MAX_CITATION_CHECKS = 30;
    /** 引文剥掉「《…》第…条」之后短于这个长度就不发 answerlaw（没有内容线索可给）。 */
    private static final int CITATION_CLUE_MIN = 12;
    /** 权威条文原文的存储上限（一条法条正文可以很长，窗格里也读不完）。 */
    private static final int AUTHORITATIVE_TEXT_LIMIT = 500;
    /** 内容定位候选的摘要上限与条数上限。 */
    private static final int CANDIDATE_SNIPPET_LIMIT = 200;
    private static final int MAX_CANDIDATES = 3;

    /** 「《公司法》第二十条」→ title=公司法, article=第二十条。 */
    private static final Pattern LAW_NAME = Pattern.compile("^《([^《》]+)》\\s*(.*)$");

    private final DocInsightRunRepository runs;
    private final DocInsightEntityRepository entities;
    private final DocInsightFindingRepository findings;
    private final ProjectFileRepository files;
    private final ProjectMemberService members;
    private final DocumentTextService documentTextService;
    private final ChatModelFactory chatModelFactory;
    private final AuxModelResolver auxModelResolver;
    private final TokenUsageService tokenUsageService;
    private final QichachaService qichachaService;
    private final McpClientService mcpClientService;
    private final PkulawChannel pkulawChannel;
    private final InsightProperties props;
    private final ObjectMapper om;

    /** 同一份文档同时只跑一个解析。DB 里的 RUNNING 行是跨重启的兜底，这个集合是同进程的原子闸。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "doc-insight");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    // ---------------------------------------------------------------- 发起

    /** 发起一次解析（异步）。返回时 run 已经以 RUNNING 落库，前端可以立刻开始轮询。 */
    public StartResult startParse(Long userId, Long projectId, Long docFileId) {
        requireWrite(projectId, userId);
        ProjectFile file = requireDoc(projectId, docFileId);

        String key = projectId + ":" + docFileId;
        if (!inFlight.add(key)) {
            throw new IllegalStateException(LangText.of("这份文档正在解析中，请等本次解析结束",
                    "This document is already being parsed"));
        }
        DocInsightRun run;
        try {
            reapOrReject(projectId, docFileId);
            run = new DocInsightRun();
            run.setProjectId(projectId);
            run.setDocFileId(docFileId);
            run.setStatus(DocInsightRun.STATUS_RUNNING);
            run.setPhase(LangText.of("读取文档", "Reading the document"));
            run.setModel(auxModelResolver.auxModelId());
            run.setStartedAt(LocalDateTime.now());
            run = runs.save(run);
        } catch (RuntimeException e) {
            inFlight.remove(key);
            throw e;
        }

        Long runId = run.getId();
        try {
            // 身份不跟随线程池，必须在提交时显式重放（见类注释「跨线程红线」）
            pool.submit(() -> PlatformAiUserScope.run(userId, () -> {
                try {
                    pipeline(runId, projectId, file, userId);
                } finally {
                    inFlight.remove(key);
                }
            }));
        } catch (RuntimeException e) {
            inFlight.remove(key);
            fail(runId, e.getMessage());
            throw e;
        }
        return new StartResult(runId, docFileId, DocInsightRun.STATUS_RUNNING);
    }

    /**
     * 处理 DB 里遗留的 RUNNING：还在时限内 → 拒绝重复发起；超时 → 判定为进程崩溃留下的僵尸并收尸。
     * 不收尸的话，一次崩溃会让这份文档<b>永远</b>解析不了。
     */
    private void reapOrReject(Long projectId, Long docFileId) {
        LocalDateTime stale = LocalDateTime.now().minusMinutes(props.getStaleMinutes());
        for (DocInsightRun r : runs.findByProjectIdAndDocFileIdAndStatus(projectId, docFileId,
                DocInsightRun.STATUS_RUNNING)) {
            if (r.getStartedAt() != null && r.getStartedAt().isAfter(stale)) {
                throw new IllegalStateException(LangText.of("这份文档正在解析中，请等本次解析结束",
                        "This document is already being parsed"));
            }
            r.setStatus(DocInsightRun.STATUS_FAILED);
            r.setError(LangText.of("上一次解析被中断（进程重启）", "The previous run was interrupted"));
            r.setFinishedAt(LocalDateTime.now());
            runs.save(r);
        }
    }

    // ---------------------------------------------------------------- 管线

    private void pipeline(Long runId, Long projectId, ProjectFile file, Long userId) {
        try {
            String text = readText(file);
            boolean truncated = text.length() > props.getMaxChars();
            if (truncated) text = text.substring(0, props.getMaxChars());

            List<RawEntity> raw = new ArrayList<>(DocInsightExtraction.scanDeterministic(text));
            List<Claim> claims = new ArrayList<>();
            extract(runId, text, raw, claims, projectId, userId);

            List<RawEntity> merged = DocInsightExtraction.merge(raw, props.getMaxMentions(), props.getMaxEntities());
            List<DocInsightEntity> rows = persistEntities(runId, projectId, file.getId(), merged);

            Retrieved retrieved = retrieveAll(runId, rows);
            List<DocInsightChecks.Finding> citations = validateCitations(runId, rows);
            int found = persistFindings(runId, projectId, file.getId(), claims, text, citations);

            done(runId, summary(rows.size(), retrieved, found, truncated));
        } catch (Throwable t) {
            log.warn("文档解析失败 runId={} fileId={}: {}", runId, file.getId(), t.toString());
            fail(runId, readable(t));
        }
    }

    private String readText(ProjectFile file) throws Exception {
        String text = documentTextService.extractText(file);
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException(LangText.of(
                    "这份文档读不出可解析的文字（扫描件无文本层 / 格式不支持 / 抽取失败）",
                    "No readable text could be extracted from this document"));
        }
        return text;
    }

    /** 逐块调辅助模型抽取。<b>单块失败只跳过这一块</b>——一份长文档不该因为某一块跑偏就整个作废。 */
    private void extract(Long runId, String text, List<RawEntity> into, List<Claim> claims,
                         Long projectId, Long userId) {
        List<String> chunks = DocInsightExtraction.chunks(text, props.getChunkChars(), props.getChunkOverlap());
        // 模型解析不出来（未配置辅助模型）要整轮失败：抽取是管线的地基，没有它只剩正则那点东西
        ChatLanguageModel model = chatModelFactory.getAuxChatModel();
        String modelId = auxModelResolver.auxModelId();
        for (int i = 0; i < chunks.size(); i++) {
            phase(runId, LangText.of("抽取实体 ", "Extracting entities ") + (i + 1) + "/" + chunks.size());
            try {
                // 走 List 版而不是可变参数版：可变参数是接口的 default 方法，
                // 单测里 mock 掉之后不会转发到真正的实现，stub 会落空
                Response<AiMessage> response = model.generate(
                        List.of(UserMessage.from(DocInsightExtraction.prompt(chunks.get(i)))));
                recordUsage(response, modelId, projectId, userId);
                Parsed parsed = DocInsightExtraction.parse(
                        response.content() == null ? null : response.content().text(), om);
                into.addAll(parsed.entities());
                claims.addAll(parsed.claims());
            } catch (Exception e) {
                log.warn("解析第 {} 块失败，跳过: {}", i + 1, e.getMessage());
            }
        }
    }

    private void recordUsage(Response<AiMessage> response, String modelId, Long projectId, Long userId) {
        if (response == null || response.tokenUsage() == null) return;
        try {
            tokenUsageService.recordUsage(projectId, userId, modelId, response.tokenUsage(), null);
        } catch (Exception e) {
            log.warn("文档解析 token 记账失败（不影响解析）: {}", e.getMessage());
        }
    }

    private List<DocInsightEntity> persistEntities(Long runId, Long projectId, Long docFileId,
                                                   List<RawEntity> merged) {
        List<DocInsightEntity> out = new ArrayList<>(merged.size());
        for (RawEntity e : merged) {
            DocInsightEntity row = new DocInsightEntity();
            row.setRunId(runId);
            row.setProjectId(projectId);
            row.setDocFileId(docFileId);
            row.setKind(e.kind());
            row.setName(e.name());
            row.setNormKey(e.normKey());
            row.setMentionsJson(mentionsJson(e.mentions()));
            row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_PENDING);
            out.add(entities.save(row));
        }
        return out;
    }

    /** 一轮检索的完成情况。NOT_FOUND 也算<b>跑完了</b>——上游明确回「没有这一项」，不是故障。 */
    private record Retrieved(int ok, int notFound) {}

    private Retrieved retrieveAll(Long runId, List<DocInsightEntity> rows) {
        int ok = 0;
        int notFound = 0;
        for (int i = 0; i < rows.size(); i++) {
            phase(runId, LangText.of("检索外部库 ", "Querying external sources ") + (i + 1) + "/" + rows.size());
            DocInsightEntity row = rows.get(i);
            try {
                retrieveOne(row, false);
            } catch (Exception e) {
                // 单个实体的失败绝不连坐其余实体
                row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_ERROR);
                row.setRetrievalNote(readable(e));
                row.setFetchedAt(LocalDateTime.now());
            }
            entities.save(row);   // 逐个落库：窗格轮询看到的是一个个点亮
            if (DocInsightEntity.RETRIEVAL_OK.equals(row.getRetrievalStatus())) ok++;
            else if (DocInsightEntity.RETRIEVAL_NOT_FOUND.equals(row.getRetrievalStatus())) notFound++;
        }
        return new Retrieved(ok, notFound);
    }

    /** @param extra 引用校验步产生的发现（{@link #validateCitations}），与文档内部校验的发现一起落库 */
    private int persistFindings(Long runId, Long projectId, Long docFileId, List<Claim> claims, String text,
                               List<DocInsightChecks.Finding> extra) {
        List<DocInsightChecks.Finding> found = new ArrayList<>(DocInsightChecks.run(claims, text));
        found.addAll(extra);
        for (DocInsightChecks.Finding f : found) {
            DocInsightFinding row = new DocInsightFinding();
            row.setRunId(runId);
            row.setProjectId(projectId);
            row.setDocFileId(docFileId);
            row.setKind(f.kind());
            row.setSeverity(f.severity());
            row.setTitle(f.title());
            row.setDetailJson(writeJson(f.detail()));
            row.setCreatedAt(LocalDateTime.now());
            findings.save(row);
        }
        return found.size();
    }

    // ---------------------------------------------------------------- 检索

    /** @param force true = 绕过 7 天缓存（单实体「重新检索」用） */
    private void retrieveOne(DocInsightEntity row, boolean force) {
        if (!force && copyFromCache(row)) return;
        row.setFetchedAt(LocalDateTime.now());
        switch (row.getKind()) {
            case DocInsightEntity.KIND_COMPANY -> retrieveCompany(row);
            case DocInsightEntity.KIND_LAW -> retrieveLaw(row);
            case DocInsightEntity.KIND_CASE -> retrieveCase(row);
            default -> unavailable(row, LangText.of("未知实体类型", "Unknown entity kind"));
        }
    }

    /**
     * 7 天内同项目同实体的成功结果直接复用，不打上游。
     * 只认 OK：把 UNAVAILABLE 也缓存会让「法宝续了点数」之后一周内都不再尝试。
     */
    private boolean copyFromCache(DocInsightEntity row) {
        LocalDateTime after = LocalDateTime.now().minusDays(props.getCacheDays());
        List<DocInsightEntity> hit = entities
                .findTop1ByProjectIdAndKindAndNormKeyAndRetrievalStatusAndFetchedAtAfterOrderByFetchedAtDesc(
                        row.getProjectId(), row.getKind(), row.getNormKey(),
                        DocInsightEntity.RETRIEVAL_OK, after);
        if (hit.isEmpty()) return false;
        DocInsightEntity src = hit.get(0);
        row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_OK);
        row.setRetrievalSource(src.getRetrievalSource());
        row.setRetrievalJson(src.getRetrievalJson());
        row.setRetrievalNote(null);
        row.setFetchedAt(src.getFetchedAt());
        return true;
    }

    /**
     * 企业：先打企查查 REST（只认工商全称），查不到再用企查查 MCP 做模糊搜索把简称解析成全称、
     * 拿全称重打一次 REST。<b>网关失败一律 UNAVAILABLE + userHint</b>，不当成「查无此企业」。
     *
     * <p>唯一的例外是上游说「查询无结果」：那是一次跑完的检索，落 <b>NOT_FOUND 而不是 UNAVAILABLE</b>，
     * 也不该摆一句「请联系 hi@aiworkdeck.com」——对着一家文中虚构的公司让用户去找客服（dev-board#395）。
     */
    private void retrieveCompany(DocInsightEntity row) {
        String name = row.getName();
        String first = null;
        try {
            String json = qichachaService.queryEciInfoJson(name);
            if (StringUtils.hasText(json)) {
                okCompany(row, json, "qichacha");
                return;
            }
        } catch (GatewayException ge) {
            // 「查询无结果」继续往下走模糊搜索：文中写的多半是简称，那条路正是为它准备的
            if (!emptyUpstream(ge.getMessage())) {
                unavailable(row, join(ge.getMessage(), ge.userHint()));
                return;
            }
            first = ge.getMessage();
        } catch (Exception e) {
            first = readable(e);
        }

        String fullName = resolveFullNameViaMcp(name);
        if (fullName == null || fullName.equals(name)) {
            notFound(row, "qichacha", join(first, LangText.of(
                    "未查询到该企业（企查查只认工商全称，文中可能写的是简称）",
                    "Company not found (the registry only accepts the full registered name)")));
            return;
        }
        try {
            String json = qichachaService.queryEciInfoJson(fullName);
            if (StringUtils.hasText(json)) {
                okCompany(row, json, "qichacha+mcp");
                return;
            }
            notFound(row, "qichacha+mcp", LangText.of("按全称「", "Full name \"") + fullName
                    + LangText.of("」仍未查询到工商信息", "\" still returned no registry record"));
        } catch (GatewayException ge) {
            if (emptyUpstream(ge.getMessage())) {
                notFound(row, "qichacha+mcp", ge.getMessage());
            } else {
                unavailable(row, join(ge.getMessage(), ge.userHint()));
            }
        } catch (Exception e) {
            row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_ERROR);
            row.setRetrievalSource("qichacha+mcp");
            row.setRetrievalNote(readable(e));
        }
    }

    /** 企查查 MCP 模糊搜索：从多个候选里挑与文中写法最接近的全称。通道不可用返回 null。 */
    String resolveFullNameViaMcp(String name) {
        String raw;
        try {
            raw = mcpClientService.callTool(QICHACHA_MCP_SERVER, QICHACHA_MCP_TOOL, Map.of("searchKey", name));
        } catch (Exception e) {
            log.warn("企查查 MCP 模糊搜索失败 name={}: {}", name, e.getMessage());
            return null;
        }
        if (!StringUtils.hasText(raw) || raw.startsWith(ERROR_PREFIX)) return null;
        List<String> candidates = companyNames(raw);
        if (candidates.isEmpty()) return null;

        String key = DocInsightChecks.normalizeSubject(name);
        for (String c : candidates) {
            if (DocInsightChecks.normalizeSubject(c).equals(key)) return c;
        }
        for (String c : candidates) {
            if (DocInsightChecks.normalizeSubject(c).contains(key)) return c;
        }
        return candidates.get(0);
    }

    private static final Set<String> NAME_FIELDS = Set.of("企业名称", "公司名称", "name", "Name", "CompanyName", "companyName");
    private static final Pattern NAME_LINE = Pattern.compile("企业名称[：:]\\s*([^\\s,，、}\"]{4,60})");

    /** 从 MCP 返回里捞候选企业名。返回可能是 JSON、也可能是排版好的文本，两种都要能吃。 */
    List<String> companyNames(String raw) {
        Set<String> out = new LinkedHashSet<>();
        try {
            collectNames(om.readTree(raw), out);
        } catch (Exception ignore) {
            // 不是 JSON，走文本兜底
        }
        if (out.isEmpty()) {
            Matcher m = NAME_LINE.matcher(raw);
            while (m.find()) out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    private void collectNames(JsonNode node, Set<String> out) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(n -> collectNames(n, out));
            return;
        }
        if (!node.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            JsonNode v = f.getValue();
            if (v.isTextual() && NAME_FIELDS.contains(f.getKey()) && StringUtils.hasText(v.asText())) {
                out.add(v.asText().trim());
            } else if (v.isContainerNode()) {
                collectNames(v, out);
            }
        }
    }

    /** 企查查 Result → 裁过的工商摘要。字段缺就缺，不补零不编造。 */
    private void okCompany(DocInsightEntity row, String json, String source) {
        ObjectNode out = om.createObjectNode();
        out.put("source", source);
        ObjectNode basic = out.putObject("basic");
        try {
            JsonNode r = om.readTree(json);
            put(basic, "企业名称", r, "Name");
            put(basic, "统一社会信用代码", r, "CreditCode");
            put(basic, "法定代表人", r, "OperName");
            put(basic, "注册资本", r, "RegistCapi");
            put(basic, "成立日期", r, "StartDate");
            put(basic, "登记状态", r, "Status");
            put(basic, "注册地址", r, "Address");
            String scope = str(r, "Scope");
            if (scope != null) basic.put("经营范围", scope.length() > SCOPE_LIMIT
                    ? scope.substring(0, SCOPE_LIMIT) + "…" : scope);
            JsonNode partners = r.get("Partners");
            if (partners != null && partners.isArray() && !partners.isEmpty()) {
                ArrayNode arr = out.putArray("shareholders");
                partners.forEach(p -> {
                    ObjectNode n = arr.addObject();
                    put(n, "股东", p, "StockName");
                    put(n, "持股比例", p, "StockPercent");
                    put(n, "认缴出资", p, "ShouldCapi");
                });
            }
        } catch (Exception e) {
            // 上游改了形状也不能丢结果：原文照留，窗格至少还能显示点什么
            out.put("raw", json);
        }
        row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_OK);
        row.setRetrievalSource(source);
        row.setRetrievalJson(writeJson(out));
        row.setRetrievalNote(null);
    }

    /**
     * 法规：有条号走语义库的 get_article，没条号走关键词库的 get_law_list。
     *
     * <p><b>点数耗尽（401）会走到这里，是预期内的</b>：法宝按点数计费，账号点数用完时
     * tools/call 直接 401。这不是我们的 bug，也不该让整轮解析失败——落 UNAVAILABLE +
     * 把上游原话写进 note，窗格显示「法规检索本次不可用：…」。
     */
    private void retrieveLaw(DocInsightEntity row) {
        String[] parts = splitLawName(row.getName());
        String title = parts[0];
        String article = parts[1];
        boolean byArticle = StringUtils.hasText(article);
        String server = byArticle ? PKULAW_SEMANTIC : PKULAW_KEYWORD;
        String tool = byArticle ? "get_article" : "get_law_list";
        Map<String, Object> args = byArticle
                ? Map.of("title", title, "number", article)
                : Map.of("title", title);
        callAndStore(row, server, tool, args,
                LangText.of("法规检索本次不可用：", "Statute lookup is unavailable this time: "),
                LangText.of("未检索到该法规条文（可能条号有误或法规名不准）",
                        "No matching statute article was found (the number or the title may be wrong)"));
    }

    /**
     * 案例：2026-08-27 起默认接法宝司法案例语义检索（yml：{@code pkulaw-case-semantic}
     * 的 {@code search_case}，查询参数 {@code text}）。server / 工具 / 参数名全部走
     * {@link InsightProperties}，换通道只改配置不改代码。
     *
     * <p><b>先导步：案号识别</b>（{@code insight.case-number-server}，法宝 {@code anhao_recognition}）。
     * 它把文中案号标准化，并直接给出法院 / 判决书标题 / 法宝链接——拿<b>标题</b>再去打语义检索，
     * 命中率比拿裸案号高得多。识别只是增强：未配置 / 上游报错 / 返回空数组一律<b>静默跳过</b>，
     * 走原来那条路，绝不让 CASE 检索比没有这一步时更差。
     */
    private void retrieveCase(DocInsightEntity row) {
        ObjectNode recognition = recognizeCaseNumber(row);
        String server = props.getCaseServer();
        if (!StringUtils.hasText(server)) {
            // 识别命中时它自己就是有用结果（法院/标题/法宝链接），不该因为全文通道没配就丢掉
            if (recognition != null) {
                okRecognitionOnly(row, recognition, LangText.of(
                        "判决书检索通道未配置，仅返回案号识别结果",
                        "No judgment search channel is configured; only the case-number recognition result is shown"));
            } else {
                unavailable(row, LangText.of("判决书检索通道未配置", "No judgment search channel is configured"));
            }
            return;
        }
        boolean ok = callAndStore(row, server, props.getCaseTool(),
                Map.of(props.getCaseArg(), caseQuery(recognition, row.getName())),
                LangText.of("判决书检索本次不可用：", "Judgment lookup is unavailable this time: "),
                LangText.of("未检索到相关判决书", "No matching judgment was found"));
        if (recognition == null) return;
        if (ok) {
            mergeRetrievalField(row, "recognition", recognition);
        } else {
            okRecognitionOnly(row, recognition, LangText.of("全文检索未命中，仅返回案号识别结果",
                    "Full-text search found nothing; only the case-number recognition result is shown"));
        }
    }

    /**
     * 案号识别先导步。任何一环不成（未配置 / 抛异常 / "Error" 前缀 / 不是数组 / 空数组）
     * 都返回 null 让调用方走原路——这一步永远只做加法。
     */
    private ObjectNode recognizeCaseNumber(DocInsightEntity row) {
        String server = props.getCaseNumberServer();
        if (!StringUtils.hasText(server)) return null;
        String raw;
        try {
            raw = pkulawChannel.callTool(server, props.getCaseNumberTool(),
                    Map.of("text", recognitionText(row)));
        } catch (Exception e) {
            log.warn("案号识别失败（跳过，不影响判决书检索）name={}: {}", row.getName(), e.getMessage());
            return null;
        }
        if (!StringUtils.hasText(raw) || raw.startsWith(ERROR_PREFIX)) return null;
        JsonNode node = unwrapMcp(raw);
        if (node == null || !node.isArray() || node.isEmpty()) return null;

        // 与实体案号归一后相等的优先；一条都对不上就取第一条（上游按出现顺序返回）
        String key = DocInsightExtraction.normalizeCaseNo(row.getName());
        ObjectNode first = null;
        for (JsonNode n : node) {
            if (n == null || !n.isObject()) continue;
            if (first == null) first = ((ObjectNode) n).deepCopy();
            if (!key.isEmpty() && key.equals(DocInsightExtraction.normalizeCaseNo(str(n, "caseFlag")))) {
                return ((ObjectNode) n).deepCopy();
            }
        }
        return first;
    }

    /** 送去识别的文本：实体名（案号）本身；首条出处更长时拼上，给上游一点上下文。 */
    private String recognitionText(DocInsightEntity row) {
        String name = row.getName() == null ? "" : row.getName();
        List<MentionView> ms = mentions(row.getMentionsJson());
        String quote = ms.isEmpty() ? "" : ms.get(0).quote();
        String text = StringUtils.hasText(quote) && quote.length() > name.length() ? name + " " + quote : name;
        return text.length() > RECOGNITION_TEXT_LIMIT ? text.substring(0, RECOGNITION_TEXT_LIMIT) : text;
    }

    /** 识别命中时改用判决书标题去检索（语义库对标题的命中率远高于裸案号），标题为空退回标准化案号。 */
    private static String caseQuery(ObjectNode recognition, String fallback) {
        if (recognition == null) return fallback;
        String title = str(recognition, "title");
        if (StringUtils.hasText(title)) return title;
        String flag = str(recognition, "caseFlag");
        return StringUtils.hasText(flag) ? flag : fallback;
    }

    /** 全文检索没成，但识别拿到了法院/标题/法宝链接——这本身就是结果，记 OK 并写明只有这一半。 */
    private void okRecognitionOnly(DocInsightEntity row, ObjectNode recognition, String note) {
        ObjectNode out = om.createObjectNode();
        out.put("source", props.getCaseNumberServer());
        out.set("recognition", recognition);
        row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_OK);
        row.setRetrievalSource(props.getCaseNumberServer());
        row.setRetrievalJson(writeJson(out));
        row.setRetrievalNote(note);
    }

    /**
     * 打一次法宝（平台档走网关、自备 Key 直连 MCP，见 {@link PkulawChannel}）并落结果。
     *
     * <p>三种不成的分法，别混：
     * <ul>
     *   <li><b>本机没这项凭证</b>（{@link McpProvider#NO_CREDENTIAL_PREFIX}）→ UNAVAILABLE，
     *       note 写「未配置」。绝不用「本次不可用」这种像是暂时故障的话：没走网关的打包态桌面端
     *       这就是恒定状态，让用户等下去等不来（dev-board#395）；</li>
     *   <li><b>上游明确回空</b>（空数组）→ NOT_FOUND，那是一次跑完的检索；</li>
     *   <li>其余（"Error" 前缀、空白、抛异常、网关分类失败）→ UNAVAILABLE + 可读原因。</li>
     * </ul>
     *
     * @return true = 拿到结果并已落进 retrievalJson
     */
    private boolean callAndStore(DocInsightEntity row, String server, String tool,
                                 Map<String, Object> args, String unavailablePrefix, String notFoundNote) {
        row.setRetrievalSource(server);
        String raw;
        try {
            raw = pkulawChannel.callTool(server, tool, args);
        } catch (GatewayException ge) {
            unavailable(row, unavailablePrefix + join(ge.getMessage(), ge.userHint()));
            return false;
        } catch (Exception e) {
            unavailable(row, unavailablePrefix + readable(e));
            return false;
        }
        if (StringUtils.hasText(raw) && raw.startsWith(McpProvider.NO_CREDENTIAL_PREFIX)) {
            unavailable(row, LangText.of("本机未配置该检索通道的凭证（",
                    "This machine has no credential for this lookup channel (")
                    + server + LangText.of("），本次未检索", "); nothing was queried"));
            return false;
        }
        if (!StringUtils.hasText(raw) || raw.startsWith(ERROR_PREFIX)) {
            unavailable(row, unavailablePrefix
                    + (StringUtils.hasText(raw) ? raw : LangText.of("上游返回空结果", "empty response")));
            return false;
        }
        JsonNode unwrapped = unwrapMcp(raw);
        if (unwrapped != null && unwrapped.isArray() && unwrapped.isEmpty()) {
            notFound(row, server, notFoundNote);   // 上游把话说清楚了：没有这一条
            return false;
        }
        ObjectNode out = om.createObjectNode();
        out.put("source", server);
        out.put("tool", tool);
        out.set("query", om.valueToTree(args));
        try {
            out.set("result", om.readTree(raw));
        } catch (Exception e) {
            out.put("result", raw);
        }
        row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_OK);
        row.setRetrievalJson(writeJson(out));
        row.setRetrievalNote(null);
        return true;
    }

    /** 往已落库的 retrievalJson 上补一个字段（案号识别、权威条文原文都走它）。 */
    private void mergeRetrievalField(DocInsightEntity row, String key, JsonNode value) {
        JsonNode node = readJson(row.getRetrievalJson());
        ObjectNode out = node != null && node.isObject() ? (ObjectNode) node : om.createObjectNode();
        out.set(key, value);
        row.setRetrievalJson(writeJson(out));
    }

    /**
     * MCP 回包可能是裸 JSON，也可能裹一层 {@code {"content":[{"type":"text","text":"<JSON>"}]}} 信封。
     * 两种都要能吃——前端 insightDetail.js 的 unwrapResult 是同一口径。
     */
    private JsonNode unwrapMcp(String raw) {
        JsonNode node = readJson(raw);
        if (node == null || !node.isObject()) return node;
        JsonNode content = node.get("content");
        if (content == null || !content.isArray()) return node;
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            String t = str(c, "text");
            if (t != null) sb.append(t);
        }
        JsonNode inner = readJson(sb.toString());
        return inner == null ? node : inner;
    }

    /** 「《公司法》第二十条」→ {公司法, 第二十条}；不带书名号的原样当标题。 */
    static String[] splitLawName(String name) {
        String s = name == null ? "" : name.trim();
        Matcher m = LAW_NAME.matcher(s);
        if (m.matches()) return new String[]{m.group(1).trim(), m.group(2).trim()};
        return new String[]{s, ""};
    }

    private void unavailable(DocInsightEntity row, String note) {
        row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_UNAVAILABLE);
        row.setRetrievalNote(note);
    }

    /** 查完了、上游说没有。note 里<b>不带任何「稍后重试 / 联系客服」</b>：这不是故障。 */
    private void notFound(DocInsightEntity row, String source, String note) {
        row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_NOT_FOUND);
        row.setRetrievalSource(source);
        row.setRetrievalNote(note);
    }

    /** 上游那句话是不是「查完了，没有」。企查查的原话是「【有效请求】查询无结果」。 */
    private static boolean emptyUpstream(String message) {
        if (!StringUtils.hasText(message)) return false;
        return message.contains("查询无结果") || message.contains("未查询到") || message.contains("无相关结果");
    }

    // ---------------------------------------------------------------- 法条引用校验

    /**
     * 法条引用校验（{@code insight.citation-server}，法宝 {@code adjust_provisions}）：
     * 拿文档里「《X》第 N 条」的引用去要权威条文原文，顺带看看引文<b>内容</b>定位到的条文
     * 是不是同一条。跑在 LAW 实体检索之后，产出两类发现 + 给 LAW 实体回填 {@code authoritative}。
     *
     * <h3>三条保守口径</h3>
     * <ul>
     *   <li>server 没配 = 整步跳过（不是失败，也不产生任何发现）；</li>
     *   <li>单条校验抛错 / 上游 "Error" 前缀 / 回包形状不认得 = <b>跳过这一条</b>。
     *       校验通道不可用 ≠ 引用有错，绝不据此报一条发现；</li>
     *   <li>最多校验 {@value #MAX_CITATION_CHECKS} 条——一份引用了三百条法规的文档
     *       不该把上游打爆，也不该让一轮解析卡在这一步。</li>
     * </ul>
     */
    private List<DocInsightChecks.Finding> validateCitations(Long runId, List<DocInsightEntity> rows) {
        List<DocInsightChecks.Finding> out = new ArrayList<>();
        String server = props.getCitationServer();
        if (!StringUtils.hasText(server)) return out;

        int checked = 0;
        for (DocInsightEntity row : rows) {
            if (!DocInsightEntity.KIND_LAW.equals(row.getKind())) continue;
            String[] parts = splitLawName(row.getName());
            if (!StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) continue;
            String arabic = LawArticleNumbers.toArabic(parts[1]);
            if (arabic == null) continue;      // 条号转不成阿拉伯数字（上游只收数字），跳过
            if (checked >= MAX_CITATION_CHECKS) break;
            checked++;
            phase(runId, LangText.of("校验法条引用 ", "Validating citations ") + checked);
            try {
                DocInsightChecks.Finding f = checkCitation(row, server, parts[0], parts[1], arabic);
                if (f != null) out.add(f);
            } catch (Exception e) {
                log.warn("法条引用校验失败（跳过该条，不产生发现）{}: {}", row.getName(), e.getMessage());
            }
        }
        return out;
    }

    /**
     * 校验一条引用。
     * <ul>
     *   <li>上游返回<b>空数组</b> = 这个条号在法宝检索不到 → {@code CITATION_NOT_FOUND}；</li>
     *   <li>返回里有 {@code article_number} 等于引用条号的条目 → 把权威原文回填进该实体的
     *       {@code retrievalJson.authoritative}（窗格当权威原文展示），本身不算错；</li>
     *   <li>发了 {@code answerlaw}（引文里有足够的内容线索）且返回的<b>内容定位候选</b>
     *       （条号与引用不同的那些条目）非空 → {@code CITATION_MISMATCH}。
     *       候选可能来自旧版法规的旧条号，所以只提示人工核对，<b>永不给一键修改</b>。</li>
     * </ul>
     */
    private DocInsightChecks.Finding checkCitation(DocInsightEntity row, String server,
                                                   String title, String citedArticle, String arabic) {
        String quote = longestMention(row);
        String clue = LawArticleNumbers.contentClue(quote);
        boolean withAnswer = clue.length() >= CITATION_CLUE_MIN;

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("userlaw", List.of(Map.of("title", title, "article_number", arabic)));
        if (withAnswer) {
            args.put("answerlaw", List.of(Map.of("title", title, "text", clue)));
            args.put("prompt", quote);
        }
        String raw = pkulawChannel.callTool(server, props.getCitationTool(), args);
        if (!StringUtils.hasText(raw) || raw.startsWith(ERROR_PREFIX)) return null;
        JsonNode node = unwrapMcp(raw);
        if (node == null || !node.isArray()) return null;   // 形状不认得 = 校验不可用，不当成引用有错
        if (node.isEmpty()) return citationNotFound(title, citedArticle, arabic, quote);

        JsonNode matched = null;
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode n : node) {
            if (n == null || !n.isObject()) continue;
            String number = LawArticleNumbers.toArabic(str(n, "article_number"));
            if (number != null && number.equals(arabic)) {
                if (matched == null) matched = n;
            } else {
                candidates.add(n);
            }
        }
        if (matched == null && candidates.isEmpty()) return null;   // 一条对象都没有：形状不认得
        if (matched != null) storeAuthoritative(row, matched);
        if (!withAnswer || candidates.isEmpty()) return null;
        return citationMismatch(title, citedArticle, quote, matched, candidates);
    }

    /** 权威条文原文回填到 LAW 实体（与法宝检索结果并列，窗格单开一段展示）。 */
    private void storeAuthoritative(DocInsightEntity row, JsonNode entry) {
        ObjectNode a = om.createObjectNode();
        putIfText(a, "title", str(entry, "title"));
        putIfText(a, "original_text", clip(str(entry, "original_text"), AUTHORITATIVE_TEXT_LIMIT));
        putIfText(a, "url", str(entry, "url"));
        putIfText(a, "implement_date", str(entry, "implement_date"));
        if (a.isEmpty()) return;
        mergeRetrievalField(row, "authoritative", a);
        entities.save(row);
    }

    private DocInsightChecks.Finding citationNotFound(String title, String citedArticle,
                                                      String arabic, String quote) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("lawTitle", title);
        detail.put("citedArticle", citedArticle);
        detail.put("citedArabic", arabic);
        detail.put("quote", quote == null ? "" : quote);
        detail.put("note", LangText.of("可能条号有误或法规名不准，请人工核对",
                "The article number or the statute name may be wrong; please check manually"));
        detail.put("fixable", false);
        return new DocInsightChecks.Finding(DocInsightChecks.KIND_CITATION_NOT_FOUND,
                DocInsightChecks.SEVERITY_WARN,
                "《" + title + "》" + citedArticle
                        + LangText.of("在北大法宝未检索到", " was not found in the statute database"),
                detail);
    }

    private DocInsightChecks.Finding citationMismatch(String title, String citedArticle, String quote,
                                                      JsonNode matched, List<JsonNode> candidates) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("lawTitle", title);
        detail.put("citedArticle", citedArticle);
        String citedText = matched == null ? null : clip(str(matched, "original_text"), CANDIDATE_SNIPPET_LIMIT);
        if (StringUtils.hasText(citedText)) detail.put("citedText", citedText);
        detail.put("quote", quote == null ? "" : quote);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode c : candidates) {
            if (rows.size() >= MAX_CANDIDATES) break;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("title", nullToEmpty(str(c, "title")));
            one.put("articleNumber", nullToEmpty(str(c, "article_number")));
            one.put("snippet", nullToEmpty(clip(str(c, "original_text"), CANDIDATE_SNIPPET_LIMIT)));
            one.put("url", nullToEmpty(str(c, "url")));
            rows.add(one);
        }
        detail.put("candidates", rows);
        // 旧版重编号是这一条最常见的成因，写进 detail 让窗格原样显示，别让用户照着候选改条号
        detail.put("note", LangText.of("候选可能来自旧版法规（存在条文重编号），请人工核对现行版本",
                "Candidates may come from an earlier version of the statute (articles get renumbered); "
                        + "please check the version in force"));
        detail.put("fixable", false);
        return new DocInsightChecks.Finding(DocInsightChecks.KIND_CITATION_MISMATCH,
                DocInsightChecks.SEVERITY_WARN,
                "《" + title + "》" + citedArticle
                        + LangText.of("的引用内容可能与条文不符", " may not match the content cited for it"),
                detail);
    }

    /** 引用校验拿最长的那条出处：内容线索越完整，按内容定位越准。 */
    private String longestMention(DocInsightEntity row) {
        String best = "";
        for (MentionView m : mentions(row.getMentionsJson())) {
            if (m.quote() != null && m.quote().length() > best.length()) best = m.quote();
        }
        return best;
    }

    private static void putIfText(ObjectNode into, String field, String value) {
        if (StringUtils.hasText(value)) into.put(field, value);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s, int limit) {
        if (s == null) return null;
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    // ---------------------------------------------------------------- 读取

    /** 这份文档最近一次解析的全量结果。还没解析过时 run 为 null（不是报错）。 */
    public InsightView latest(Long userId, Long projectId, Long docFileId) {
        requireRead(projectId, userId);
        if (docFileId == null) {
            throw new IllegalArgumentException(LangText.of("docFileId 必填", "docFileId required"));
        }
        DocInsightRun run = runs.findFirstByProjectIdAndDocFileIdOrderByStartedAtDescIdDesc(projectId, docFileId)
                .orElse(null);
        if (run == null) return new InsightView(null, List.of(), List.of());
        List<EntityView> es = entities.findByRunIdOrderByIdAsc(run.getId()).stream()
                .map(e -> view(e, false)).toList();
        List<FindingView> fs = findings.findByRunIdOrderByIdAsc(run.getId()).stream()
                .map(this::view).toList();
        return new InsightView(view(run), es, fs);
    }

    /** 单个实体的全量检索结果（列表里刻意不带）。 */
    public EntityView entityDetail(Long userId, Long projectId, Long entityId) {
        requireRead(projectId, userId);
        return view(requireEntity(projectId, entityId), true);
    }

    /** 重新检索一个实体，绕过 7 天缓存。要写权限——这会花外部库额度。 */
    public EntityView refreshEntity(Long userId, Long projectId, Long entityId) {
        requireWrite(projectId, userId);
        DocInsightEntity row = requireEntity(projectId, entityId);
        // 检索本身可能打网络，同样包进身份作用域：控制器线程没有它
        PlatformAiUserScope.run(userId, () -> {
            try {
                retrieveOne(row, true);
            } catch (Exception e) {
                row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_ERROR);
                row.setRetrievalNote(readable(e));
                row.setFetchedAt(LocalDateTime.now());
            }
        });
        return view(entities.save(row), true);
    }

    // ---------------------------------------------------------------- 视图

    private RunView view(DocInsightRun r) {
        return new RunView(r.getId(), r.getDocFileId(), r.getStatus(), r.getPhase(), r.getError(),
                r.getModel(), r.getStartedAt(), r.getFinishedAt());
    }

    private EntityView view(DocInsightEntity e, boolean withDetail) {
        boolean hasDetail = StringUtils.hasText(e.getRetrievalJson());
        return new EntityView(e.getId(), e.getKind(), e.getName(), e.getNormKey(),
                e.getRetrievalStatus(), e.getRetrievalSource(), e.getRetrievalNote(),
                hasDetail, e.getFetchedAt(), mentions(e.getMentionsJson()),
                withDetail ? readJson(e.getRetrievalJson()) : null);
    }

    private FindingView view(DocInsightFinding f) {
        return new FindingView(f.getId(), f.getKind(), f.getSeverity(), f.getTitle(),
                readJson(f.getDetailJson()));
    }

    private List<MentionView> mentions(String json) {
        List<MentionView> out = new ArrayList<>();
        JsonNode node = readJson(json);
        if (node == null || !node.isArray()) return out;
        node.forEach(n -> {
            String quote = n.path("quote").asText("");
            if (!quote.isEmpty()) {
                JsonNode p = n.get("paragraph");
                out.add(new MentionView(quote, p == null || p.isNull() ? null : p.asInt()));
            }
        });
        return out;
    }

    private String mentionsJson(List<Mention> mentions) {
        ArrayNode arr = om.createArrayNode();
        for (Mention m : mentions) {
            ObjectNode n = arr.addObject();
            n.put("quote", m.quote());
            if (m.paragraph() == null) n.putNull("paragraph"); else n.put("paragraph", m.paragraph());
        }
        return writeJson(arr);
    }

    // ---------------------------------------------------------------- 状态与工具

    private void phase(Long runId, String phase) {
        runs.findById(runId).ifPresent(r -> {
            r.setPhase(phase);
            runs.save(r);
        });
    }

    private void done(Long runId, String phase) {
        runs.findById(runId).ifPresent(r -> {
            r.setStatus(DocInsightRun.STATUS_DONE);
            r.setPhase(phase);
            r.setFinishedAt(LocalDateTime.now());
            runs.save(r);
        });
    }

    private void fail(Long runId, String error) {
        runs.findById(runId).ifPresent(r -> {
            r.setStatus(DocInsightRun.STATUS_FAILED);
            r.setError(error);
            r.setFinishedAt(LocalDateTime.now());
            runs.save(r);
        });
    }

    private String summary(int entityCount, Retrieved retrieved, int findingCount, boolean truncated) {
        // 未命中单列一项：把「查完了，上游说没有」并进「检索成功 0 个」，
        // 用户读到的是「一次都没查成」——那是通道故障的话术（dev-board#395）
        String notFound = retrieved.notFound() > 0
                ? LangText.of("，未命中 ", ", ") + retrieved.notFound() + LangText.of(" 个", " not found")
                : "";
        String s = LangText.of("完成：实体 ", "Done: ") + entityCount
                + LangText.of(" 个，检索成功 ", " entities, ") + retrieved.ok()
                + LangText.of(" 个", " retrieved") + notFound
                + LangText.of("，发现 ", ", ") + findingCount
                + LangText.of(" 处不一致", " inconsistencies");
        return truncated ? s + LangText.of("（文档过长，只解析了前 ", " (document truncated to the first ")
                + props.getMaxChars() + LangText.of(" 字）", " characters)") : s;
    }

    private JsonNode readJson(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return om.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(ObjectNode into, String field, JsonNode from, String key) {
        String v = str(from, key);
        if (v != null) into.put(field, v);
    }

    private static String str(JsonNode node, String key) {
        JsonNode v = node == null ? null : node.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String join(String a, String b) {
        if (!StringUtils.hasText(a)) return b == null ? "" : b;
        if (!StringUtils.hasText(b)) return a;
        return a + " " + b;
    }

    /** 异常 → 给用户看的一句话。message 为空时退回类名，别给一个空白提示。 */
    private static String readable(Throwable t) {
        String m = t.getMessage();
        return StringUtils.hasText(m) ? m : t.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------- 鉴权

    private void requireRead(Long projectId, Long userId) {
        if (userId == null || projectId == null || !members.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权限访问该项目", "No access to this project"));
        }
    }

    private void requireWrite(Long projectId, Long userId) {
        if (userId == null || projectId == null || !members.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权限修改该项目", "No write access to this project"));
        }
    }

    private ProjectFile requireDoc(Long projectId, Long docFileId) {
        if (docFileId == null) {
            throw new IllegalArgumentException(LangText.of("docFileId 必填", "docFileId required"));
        }
        ProjectFile f = files.findById(docFileId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("文件不存在", "File not found")));
        if (!projectId.equals(f.getProjectId()) || Boolean.TRUE.equals(f.getIsDeleted())) {
            throw new IllegalArgumentException(LangText.of("文件不存在", "File not found"));
        }
        if (Boolean.TRUE.equals(f.getIsFolder())) {
            throw new IllegalArgumentException(LangText.of("文件夹不能解析", "A folder cannot be parsed"));
        }
        return f;
    }

    private DocInsightEntity requireEntity(Long projectId, Long entityId) {
        DocInsightEntity e = entities.findById(entityId)
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("条目不存在", "Entry not found")));
        // 跨项目的 id 一律当作不存在，不泄露它存在于别的项目
        if (!projectId.equals(e.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("条目不存在", "Entry not found"));
        }
        return e;
    }
}
