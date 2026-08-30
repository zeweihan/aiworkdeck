package com.checkba.plugin.api.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * evidence.retrieve.v1 一致性自测执行器（插件规范 v2.8）。
 *
 * <p>零依赖纯 Java——不绑任何测试框架：插件仓在自己的 JUnit/TestNG 测试里调
 * {@link #run(EvidenceProvider, EvidenceQuery)}，断言返回列表为空即通过；
 * 非空时每个元素是一条人话失败描述。广场受理要求附全绿运行记录。
 *
 * <p>覆盖契约五场景（docs/EVIDENCE_CONTRACT.md §6）中可黑盒验证的部分：
 * 幂等重放、缺定位符（构造器层强制，恒过）、excerpt 有界、sourceId 稳定性、
 * 空查询降级。「文档变更携带 superseded 信号」「来源冲突都如实返回」依赖
 * 具体数据源的测试夹具，插件仓自行补场景测试（参照宿主
 * MemoryEvidenceRetrieverTest 的写法）。
 */
public final class EvidenceProviderConformanceKit {

    private EvidenceProviderConformanceKit() {
    }

    /**
     * 用给定查询跑一遍一致性检查。
     *
     * @param provider 被测实现
     * @param query    应当命中至少 0 条结果的真实查询（夹具数据由插件仓准备）
     * @return 失败描述列表；空 = 通过
     */
    public static List<String> run(EvidenceProvider provider, EvidenceQuery query) {
        List<String> failures = new ArrayList<>();

        String sourceId = provider.sourceId();
        if (sourceId == null || sourceId.isBlank()) {
            failures.add("sourceId() 返回空——来源标识是注册表路由键，必须非空且稳定");
            return failures;
        }
        if (!sourceId.contains(".")) {
            failures.add("sourceId '" + sourceId + "' 缺 <pluginId>. 前缀——宿主会拒绝注册");
        }
        if (!Objects.equals(sourceId, provider.sourceId())) {
            failures.add("sourceId() 两次调用返回不同值——必须稳定");
        }
        if (!EvidenceProvider.CONTRACT_VERSION.equals(provider.contractVersion())) {
            failures.add("contractVersion() 不是 " + EvidenceProvider.CONTRACT_VERSION);
        }

        List<EvidenceItem> first;
        try {
            first = provider.retrieve(query);
        } catch (RuntimeException e) {
            failures.add("retrieve 抛出异常（应降级为空列表并自行记日志）：" + e);
            return failures;
        }
        if (first == null) {
            failures.add("retrieve 返回 null（应返回空列表）");
            return failures;
        }
        for (EvidenceItem item : first) {
            // record 构造器已强制三必填；这里防御实现绕开 record 的可能性为零，只查有界性
            if (item.excerpt() != null && item.excerpt().length() > EvidenceItem.MAX_EXCERPT_LENGTH) {
                failures.add("excerpt 超过 " + EvidenceItem.MAX_EXCERPT_LENGTH + " 字符：" + item.evidenceId());
            }
            if (query.limit() > 0 && first.size() > query.limit()) {
                failures.add("返回条数 " + first.size() + " 超过 limit " + query.limit());
                break;
            }
        }

        // 幂等重放：同一 query（含 asOf）重复执行，evidenceId 与 contentHash 序列一致
        List<EvidenceItem> second;
        try {
            second = provider.retrieve(query);
        } catch (RuntimeException e) {
            failures.add("幂等重放第二次调用抛出异常：" + e);
            return failures;
        }
        if (second == null || second.size() != first.size()) {
            failures.add("幂等重放条数不一致：first=" + first.size()
                    + " second=" + (second == null ? "null" : second.size()));
        } else {
            for (int i = 0; i < first.size(); i++) {
                if (!Objects.equals(first.get(i).evidenceId(), second.get(i).evidenceId())
                        || !Objects.equals(first.get(i).contentHash(), second.get(i).contentHash())) {
                    failures.add("幂等重放第 " + i + " 条 evidenceId/contentHash 不一致");
                    break;
                }
            }
        }

        // 无效工作区/空查询降级：不抛异常
        try {
            List<EvidenceItem> degraded = provider.retrieve(new EvidenceQuery(
                    "conformance-nonexistent-workspace", query.query(), query.asOf(),
                    query.sourceFilters(), query.accessContext(), query.limit()));
            if (degraded == null) {
                failures.add("未知工作区应返回空列表而不是 null");
            }
        } catch (RuntimeException e) {
            failures.add("未知工作区应降级为空列表而不是抛异常：" + e);
        }

        return failures;
    }
}
