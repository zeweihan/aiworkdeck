package com.checkba.service.team;

import com.checkba.model.entity.TelemetryEvent;
import com.checkba.model.entity.TokenUsage;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.TelemetryEventRepository;
import com.checkba.repository.TokenUsageRepository;
import com.checkba.service.telemetry.InstallIdentityService;
import com.checkba.version.WorkSession;
import com.checkba.version.WorkSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队使用统计的日聚合（dev-board#496，设计 docs/superpowers/specs/2026-09-07-law-firm-team-usage-design.md §5）。
 *
 * <p>产出的 payload <b>只有计数、枚举与不可逆短码</b>：没有项目名、文件名、工作段标题、
 * 对话文本，一个字符串内容都没有。这是本服务唯一的验收标准——多带一个可读字段，
 * 就同时违反 docs/ANALYTICS_TELEMETRY_DESIGN.md §3 的不可协商清单与 legal/PRIVACY.md。
 *
 * <p><b>为什么不复用 {@code TelemetryRollupService}：</b>那一条是匿名通道，
 * 它的 {@code rollupFor} 直接把 payload 落进 telemetry_rollup 表、不返回 counters，
 * 想复用就得把它改成「算完返回」并让两个调用方共享——那会把一条有公开隐私承诺的链路
 * 改成两用，风险远大于这里重算一遍六个计数。派生口径逐条照抄（{@code editor.action}
 * 按 {@code attrs.agent} 分 agent/manual、{@code ai.turn}/{@code ai.tool}/
 * {@code project.created}/{@code app.start} 直接计数），改那边的口径时这里要跟着改。
 */
@Slf4j
@Service
public class TeamUsageRollupService {

    /** 单日投入时长上限：16 小时。超过这个数的多半是没结束干净的段，不是真工时。 */
    static final long MAX_ACTIVE_MINUTES = 16 * 60L;

    private final WorkSessionRepository workSessionRepository;
    private final TelemetryEventRepository eventRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final ProjectRepository projectRepository;
    private final InstallIdentityService identity;
    private final TeamSettingsCache teamSettingsCache;
    private final ObjectMapper mapper = new ObjectMapper();

    public TeamUsageRollupService(WorkSessionRepository workSessionRepository,
                                  TelemetryEventRepository eventRepository,
                                  TokenUsageRepository tokenUsageRepository,
                                  ProjectRepository projectRepository,
                                  InstallIdentityService identity,
                                  TeamSettingsCache teamSettingsCache) {
        this.workSessionRepository = workSessionRepository;
        this.eventRepository = eventRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.projectRepository = projectRepository;
        this.identity = identity;
        this.teamSettingsCache = teamSettingsCache;
    }

    /**
     * 聚合某一天、某个本机用户的使用计数。纯函数式：只读库、不写库、不发网络。
     *
     * @param date   本地日期
     * @param userId 本机用户 id（local-mode 下即 {@code LocalIdentityService.localUserId()}）
     * @return 第 5 节的 payload（可直接序列化上报）
     */
    public Map<String, Object> rollupFor(LocalDate date, Long userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", date.toString());

        Map<Long, Long> minutesByProject = new LinkedHashMap<>();
        long activeMinutes = activeMinutes(date, userId, minutesByProject);

        Counters counters = countEvents(date);
        payload.put("appStarts", counters.appStarts);
        payload.put("activeMinutes", activeMinutes);
        payload.put("aiTurns", counters.aiTurns);
        payload.put("aiToolCalls", counters.aiToolCalls);
        payload.put("agentEditActions", counters.agentEditActions);
        payload.put("manualEditActions", counters.manualEditActions);
        payload.put("projectsCreated", counters.projectsCreated);

        Map<Long, Long> llmCallsByProject = new LinkedHashMap<>();
        payload.put("tokens", tokens(date, userId, llmCallsByProject));
        payload.put("projects", projects(minutesByProject, llmCallsByProject));
        return payload;
    }

    /** 上报体的 JSON 形态。序列化失败抛 IllegalStateException——payload 全是基本类型，不该失败。 */
    public String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("团队日聚合序列化失败", e);
        }
    }

    // ==================== 投入时长 ====================

    /**
     * 当日投入时长：work_session 中 <b>WORK 段且已结束</b>（status != ACTIVE）的时长之和。
     *
     * <p>两条排除各有理由，缺一条数字就没有工时语义：
     * <ul>
     *   <li>DRAFT 是「另起一稿」的长命分支，可以挂几周，时长与人在不在电脑前无关；</li>
     *   <li>ACTIVE 是还没结束的段，endedAt 为 null，此刻算它等于算「到现在为止」，
     *       同一天反复聚合会得到不断变大的值。</li>
     * </ul>
     *
     * <p>另有一个已知的口径误差：WORK 段空闲 30 分钟才自动结束，所以每段最多含 30 分钟
     * 挂机尾巴。这是版本记录模块的既有行为，此处不做修正（改它会动到版本记录的语义）。
     */
    private long activeMinutes(LocalDate date, Long userId, Map<Long, Long> minutesByProject) {
        if (userId == null) return 0;
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();
        long total = 0;
        for (WorkSession s : workSessionRepository.findByUserIdAndStartedAtBetween(userId, from, to)) {
            if (s.getSessionType() != WorkSession.SessionType.WORK) continue;
            if (s.getStatus() == WorkSession.Status.ACTIVE) continue;
            if (s.getStartedAt() == null || s.getEndedAt() == null) continue;
            long minutes = Duration.between(s.getStartedAt(), s.getEndedAt()).toMinutes();
            if (minutes <= 0) continue;
            total += minutes;
            if (s.getProjectId() != null) {
                minutesByProject.merge(s.getProjectId(), minutes, Long::sum);
            }
        }
        // 上限同样落在每个项目上：只封总数的话，单个项目行仍可能出现 40 小时这种明显是脏数据的值
        minutesByProject.replaceAll((k, v) -> Math.min(v, MAX_ACTIVE_MINUTES));
        return Math.min(total, MAX_ACTIVE_MINUTES);
    }

    // ==================== 事件计数 ====================

    private static final class Counters {
        long appStarts;
        long aiTurns;
        long aiToolCalls;
        long agentEditActions;
        long manualEditActions;
        long projectsCreated;
    }

    /**
     * 当日六个计数，来自本机 telemetry 账本。
     *
     * <p><b>已知口径缺口</b>：{@code telemetry_event} 表刻意没有 userId 与 projectId
     * （匿名设计），所以这六个数是<b>整机口径</b>，不是「这个用户的」。单机桌面版一台机器
     * 一个人，两者等价；一台机器被多人共用时会全部记在当前本机用户名下。
     * 这不是可以靠给 telemetry 加列修的——加了就毁掉那条通道的匿名承诺。
     */
    private Counters countEvents(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        Counters c = new Counters();
        for (TelemetryEvent e : eventRepository.findByTsBetween(from, to)) {
            switch (String.valueOf(e.getEventName())) {
                case "app.start" -> c.appStarts++;
                case "ai.turn" -> c.aiTurns++;
                case "ai.tool" -> c.aiToolCalls++;
                case "project.created" -> c.projectsCreated++;
                case "editor.action" -> {
                    if (Boolean.TRUE.equals(parseAttrs(e.getAttrs()).get("agent"))) {
                        c.agentEditActions++;
                    } else {
                        c.manualEditActions++;
                    }
                }
                default -> { }
            }
        }
        return c;
    }

    // ==================== token ====================

    /**
     * token 按 costSource 分桶。两套口径<b>不得合并</b>（既有契约）：
     * platform 是官网真实结算，estimate 是 BYOK 的本地单价表估算，不是账单。
     */
    private Map<String, Long> tokens(LocalDate date, Long userId, Map<Long, Long> llmCallsByProject) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("platform", 0L);
        out.put("estimate", 0L);
        for (TokenUsage u : tokenUsageRepository.findByCreatedAtAfter(date.atStartOfDay())) {
            if (u.getCreatedAt() == null || !u.getCreatedAt().toLocalDate().equals(date)) continue;
            if (userId != null && !userId.equals(u.getUserId())) continue;
            String src = "platform".equals(u.getCostSource()) ? "platform" : "estimate";
            out.merge(src, u.getTotalTokens() == null ? 0L : u.getTotalTokens().longValue(), Long::sum);
            if (u.getProjectId() != null) {
                llmCallsByProject.merge(u.getProjectId(), 1L, Long::sum);
            }
        }
        return out;
    }

    // ==================== 项目维度 ====================

    /**
     * 项目行。项目以不可逆短码出现，<b>项目名只在团队显式打开「共享项目名」时才带</b>
     * （{@code team.shareProjectNames} 缓存在本机，见 {@link #shareProjectNames()}）；
     * 默认不带，管理者在面板里给短码起别名。
     *
     * <p><b>{@code editActions} 恒为 0，这是数据源缺口不是 bug</b>：编辑动作只存在于
     * telemetry 账本，而那张表没有 projectId。要填它得给匿名表加列，代价是毁掉匿名承诺。
     * {@code aiTurns} 用当日该项目的 token_usage <b>行数</b>近似——一行是一次 LLM 调用，
     * 带工具循环的一轮对话会产生多行，所以这个数偏大，且各项目之和不等于顶层的 aiTurns。
     * 相对比较（哪个案件用 AI 更多）可信，绝对值不可信。
     */
    private List<Map<String, Object>> projects(Map<Long, Long> minutesByProject,
                                               Map<Long, Long> llmCallsByProject) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        ids.addAll(minutesByProject.keySet());
        ids.addAll(llmCallsByProject.keySet());
        boolean shareNames = teamSettingsCache.shareProjectNames();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long projectId : ids) {
            String key = identity.projectKey(projectId);
            if (key == null) continue; // 盐读不出来时宁可少一行，也不能退回明文 id
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectKey", key);
            row.put("label", shareNames ? projectName(projectId) : null);
            row.put("minutes", minutesByProject.getOrDefault(projectId, 0L));
            row.put("aiTurns", llmCallsByProject.getOrDefault(projectId, 0L));
            row.put("editActions", 0L);
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get("minutes")).reversed());
        return rows;
    }

    /** 只在共享开关打开时才会被调用；查不到的项目返回 null（不编一个名字出来）。 */
    private String projectName(Long projectId) {
        try {
            return projectRepository.findById(projectId).map(p -> p.getName()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseAttrs(String json) {
        if (json == null || json.isEmpty()) return Map.of();
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructMapType(HashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }
}
