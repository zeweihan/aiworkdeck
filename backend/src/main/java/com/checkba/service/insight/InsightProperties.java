package com.checkba.service.insight;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档解析（dev-board#181/#182）的可调参数，前缀 {@code insight}。
 *
 * <p>{@link #caseServer} 2026-08-27 起默认接法宝司法案例语义检索（yml 里
 * {@code pkulaw-case-semantic} → {@code search_case}，查询参数 {@code text}）。
 * 换别家案例 MCP 只要在 {@code mcp.servers} 里加一条、改这三项配置即可接入，
 * 不动一行代码——这正是把 server/工具/参数名都做成配置而不是常量的原因。
 * 代码内缺省仍为空（= 通道未配置，案例实体落 UNAVAILABLE），真实缺省值在 yml。
 *
 * <p>{@link #caseNumberServer}（案号识别先导步）与 {@link #citationServer}（法条引用校验）
 * 照同一个先例：代码内缺省为空 = 这一步不做，yml 里给法宝的默认 server/工具名。
 */
@Data
@Component
@ConfigurationProperties(prefix = "insight")
public class InsightProperties {

    /** 判决书检索的 MCP server 名（对应 {@code mcp.servers[].name}）。空 = 该通道未配置。 */
    private String caseServer = "";

    /** 判决书检索的工具名。 */
    private String caseTool = "search_judgment";

    /** 判决书检索工具的查询参数名（法宝 search_case 用 text）。 */
    private String caseArg = "text";

    /**
     * 案号识别的 MCP server 名（法宝 {@code pkulaw-case-number}）。
     * <b>空 = 跳过这一先导步</b>，CASE 实体照旧拿案号原文直接打全文检索。
     */
    private String caseNumberServer = "";

    /** 案号识别的工具名（法宝 {@code anhao_recognition}，参数 {@code text}）。 */
    private String caseNumberTool = "anhao_recognition";

    /**
     * 法条引用校验的 MCP server 名（法宝 {@code pkulaw-citation-validator}）。
     * <b>空 = 整步跳过</b>，不产生引用类发现，也不回填权威条文原文。
     */
    private String citationServer = "";

    /** 法条引用校验的工具名（法宝 {@code adjust_provisions}）。 */
    private String citationTool = "adjust_provisions";

    /** 每块送给辅助模型的字符数。 */
    private int chunkChars = 10000;

    /** 相邻块的重叠字符数，防止实体正好被切在块边界上。 */
    private int chunkOverlap = 500;

    /** 全文上限：超出部分不解析，并在 run.phase 里写明截断。 */
    private int maxChars = 200000;

    /** 外部检索结果的复用天数（同项目同实体）。 */
    private int cacheDays = 7;

    /** 一次解析最多处理多少个实体（去重后）。防一份怪文档把上游打爆。 */
    private int maxEntities = 200;

    /** 单个实体最多记多少条出处。 */
    private int maxMentions = 20;

    /** RUNNING 超过这么多分钟视为进程崩溃留下的僵尸，允许重新发起解析。 */
    private int staleMinutes = 30;
}
