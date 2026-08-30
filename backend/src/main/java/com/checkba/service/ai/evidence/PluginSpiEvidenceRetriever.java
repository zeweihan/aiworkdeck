package com.checkba.service.ai.evidence;

import com.checkba.plugin.api.evidence.EvidenceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 插件 SPI Provider → 内部 EvidenceRetriever 的适配器（规范 v2.8 P3）。
 *
 * <p>三件事：公开 record ↔ 内部 record 的字段映射、单次调用 10 秒超时（超时/异常
 * 一律空列表降级，不炸编排主流程）、插件禁用即静默（enabledCheck 由 PluginService
 * 提供——禁用的插件其 provider 实例仍在 JVM 里，这道闸保证它拿不到任何查询）。
 *
 * <p>非 Spring Bean：PluginService 扫描到实现类后逐个包装注册。
 */
@Slf4j
public class PluginSpiEvidenceRetriever implements EvidenceRetriever {

    /** 所有插件 provider 共用的守护线程池：只为超时控制，不追求吞吐 */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "plugin-evidence-provider");
        t.setDaemon(true);
        return t;
    });

    static final long TIMEOUT_MS = 10_000L;

    private final String sourceId;
    private final EvidenceProvider provider;
    private final BooleanSupplier enabledCheck;
    private final long timeoutMs;

    public PluginSpiEvidenceRetriever(String sourceId, EvidenceProvider provider, BooleanSupplier enabledCheck) {
        this(sourceId, provider, enabledCheck, TIMEOUT_MS);
    }

    /** 包内可见：测试用短超时 */
    PluginSpiEvidenceRetriever(String sourceId, EvidenceProvider provider,
                               BooleanSupplier enabledCheck, long timeoutMs) {
        this.sourceId = sourceId;
        this.provider = provider;
        this.enabledCheck = enabledCheck;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public List<EvidenceItem> retrieve(EvidenceQuery query) {
        if (!enabledCheck.getAsBoolean()) {
            return List.of();
        }
        com.checkba.plugin.api.evidence.EvidenceQuery publicQuery =
                new com.checkba.plugin.api.evidence.EvidenceQuery(
                        query.workspaceId(), query.query(), query.asOf(),
                        query.sourceFilters(), query.accessContext(), query.limit());
        CompletableFuture<List<com.checkba.plugin.api.evidence.EvidenceItem>> future =
                CompletableFuture.supplyAsync(() -> provider.retrieve(publicQuery), EXECUTOR);
        List<com.checkba.plugin.api.evidence.EvidenceItem> items;
        try {
            items = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            future.cancel(true);
            log.warn("evidence source '{}' 检索失败/超时，按空列表降级: {}", sourceId, e.toString());
            return List.of();
        }
        if (items == null) {
            return List.of();
        }
        List<EvidenceItem> out = new ArrayList<>(items.size());
        for (com.checkba.plugin.api.evidence.EvidenceItem it : items) {
            if (it == null) {
                continue;
            }
            try {
                // 公开 record 的构造器已强制三必填；这里再包一层防御（内部构造器同样强制）
                out.add(new EvidenceItem(it.evidenceId(), it.sourceUri(), it.contentHash(),
                        it.retrievedAt(), it.effectiveDate(), it.locator(), it.excerpt(),
                        it.mimeType(), it.accessPolicy(), it.provenance(),
                        it.supersedes(), it.revokes(), it.supersededAt()));
            } catch (IllegalArgumentException e) {
                log.warn("evidence source '{}' 返回缺必填字段的条目，已丢弃: {}", sourceId, e.getMessage());
            }
        }
        return out;
    }
}
