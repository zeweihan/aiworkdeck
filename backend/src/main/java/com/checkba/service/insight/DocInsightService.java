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
 *       整轮解析照常跑完。一个通道挂了不该让另外两类实体也查不成。</li>
 *   <li><b>逐个实体检索完就落库</b>。前端轮询看到的是一个个点亮的过程，
 *       而不是等三分钟后一次性出现。</li>
 * </ol>
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

            int ok = retrieveAll(runId, rows);
            int found = persistFindings(runId, projectId, file.getId(), claims, text);

            done(runId, summary(rows.size(), ok, found, truncated));
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

    private int retrieveAll(Long runId, List<DocInsightEntity> rows) {
        int ok = 0;
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
        }
        return ok;
    }

    private int persistFindings(Long runId, Long projectId, Long docFileId, List<Claim> claims, String text) {
        List<DocInsightChecks.Finding> found = DocInsightChecks.run(claims, text);
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
            unavailable(row, join(ge.getMessage(), ge.userHint()));
            return;
        } catch (Exception e) {
            first = readable(e);
        }

        String fullName = resolveFullNameViaMcp(name);
        if (fullName == null || fullName.equals(name)) {
            row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_ERROR);
            row.setRetrievalSource("qichacha");
            row.setRetrievalNote(join(first, LangText.of(
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
            row.setRetrievalStatus(DocInsightEntity.RETRIEVAL_ERROR);
            row.setRetrievalSource("qichacha+mcp");
            row.setRetrievalNote(LangText.of("按全称「", "Full name \"") + fullName
                    + LangText.of("」仍未查询到工商信息", "\" still returned no registry record"));
        } catch (GatewayException ge) {
            unavailable(row, join(ge.getMessage(), ge.userHint()));
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
                LangText.of("法规检索本次不可用：", "Statute lookup is unavailable this time: "));
    }

    /**
     * 案例：2026-08-27 起默认接法宝司法案例语义检索（yml：{@code pkulaw-case-semantic}
     * 的 {@code search_case}，查询参数 {@code text}）。server / 工具 / 参数名全部走
     * {@link InsightProperties}，换通道只改配置不改代码。
     */
    private void retrieveCase(DocInsightEntity row) {
        String server = props.getCaseServer();
        if (!StringUtils.hasText(server)) {
            unavailable(row, LangText.of("判决书检索通道未配置", "No judgment search channel is configured"));
            return;
        }
        callAndStore(row, server, props.getCaseTool(), Map.of(props.getCaseArg(), row.getName()),
                LangText.of("判决书检索本次不可用：", "Judgment lookup is unavailable this time: "));
    }

    /** 打一次 MCP 并落结果。返回 "Error..." 前缀或空白一律判通道不可用（{@link McpClientService} 的既有约定）。 */
    private void callAndStore(DocInsightEntity row, String server, String tool,
                              Map<String, Object> args, String unavailablePrefix) {
        row.setRetrievalSource(server);
        String raw;
        try {
            raw = mcpClientService.callTool(server, tool, args);
        } catch (Exception e) {
            unavailable(row, unavailablePrefix + readable(e));
            return;
        }
        if (!StringUtils.hasText(raw) || raw.startsWith(ERROR_PREFIX)) {
            unavailable(row, unavailablePrefix
                    + (StringUtils.hasText(raw) ? raw : LangText.of("上游返回空结果", "empty response")));
            return;
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

    private String summary(int entityCount, int ok, int findingCount, boolean truncated) {
        String s = LangText.of("完成：实体 ", "Done: ") + entityCount
                + LangText.of(" 个，检索成功 ", " entities, ") + ok
                + LangText.of(" 个，发现 ", " retrieved, ") + findingCount
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
