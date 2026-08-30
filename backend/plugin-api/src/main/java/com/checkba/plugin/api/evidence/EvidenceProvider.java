package com.checkba.plugin.api.evidence;

import java.util.List;

/**
 * 证据检索 Provider（插件规范 v2.8，evidence.retrieve.v1 的公开 SPI 通道）。
 *
 * <p>JAR 插件实现本接口（无参构造；可同时实现 {@code HostAware} 拿宿主门面），
 * 宿主扫描时自动注册进证据检索注册表——数据源接一次，所有依据/尽调/核查场景全能用。
 * 契约字段与不变式见 docs/EVIDENCE_CONTRACT.md；一致性自测用
 * {@link EvidenceProviderConformanceKit}。
 *
 * <p>实现约定（与宿主内置 retriever 同一套）：
 * <ul>
 *   <li>幂等重放：同一 query（含 asOf）重复调用，evidenceId 与 contentHash 序列一致；</li>
 *   <li>缺定位符即丢弃：给不出精确 locator 的内容不返回、不编造（{@link EvidenceItem}
 *       构造器强制 evidenceId/sourceUri/locator 非空）；</li>
 *   <li>来源不可用/访问被拒：返回空列表并自行记日志，不抛异常——宿主侧仍有
 *       try/catch 与超时兜底，但不要依赖它。</li>
 * </ul>
 */
public interface EvidenceProvider {

    String CONTRACT_VERSION = "evidence.retrieve.v1";

    /**
     * 来源标识。必须以 {@code <pluginId>.} 为前缀（如 {@code qcc-pro.company-registry}），
     * 且与 manifest {@code contributes.evidenceSources[].sourceId} 逐字一致——
     * 任一不满足宿主拒绝注册并记 ERROR。
     */
    String sourceId();

    /** 按契约检索；单次调用宿主侧超时 10 秒，超时该来源按空列表降级。 */
    List<EvidenceItem> retrieve(EvidenceQuery query);

    default String contractVersion() {
        return CONTRACT_VERSION;
    }
}
