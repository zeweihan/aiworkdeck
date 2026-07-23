package com.checkba.service.ai.evidence;

import java.util.List;

/**
 * evidence.retrieve.v1 检索能力 SPI。
 *
 * 设计取向（RFC #14）：契约稳定、传输可插拔——本地记忆、远程服务、MCP 服务器
 * 都以本接口接入，Skill 通过 requires 声明依赖该能力，插件负责实现。
 *
 * 实现约定：
 * 1. 幂等重放：同一 query（含 asOf）重复调用，返回的 evidenceId 与 contentHash 必须一致；
 * 2. 缺定位符即丢弃：无法给出精确 locator 的内容不得返回（见 {@link EvidenceItem} 构造约束）；
 * 3. 访问被拒/来源不可用时返回空列表并记日志，不抛异常炸掉编排主流程。
 */
public interface EvidenceRetriever {

    String CONTRACT_VERSION = "evidence.retrieve.v1";

    /** 来源标识（如 "memory"、"mcp:pkulaw-semantic"），注册表按此路由 */
    String sourceId();

    /** 按契约检索证据 */
    List<EvidenceItem> retrieve(EvidenceQuery query);

    default String contractVersion() {
        return CONTRACT_VERSION;
    }
}
