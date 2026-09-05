# 插件生态 P3：数据源 Provider——evidence.retrieve.v1 升格公开协议（设计稿）

> dev-board#283，延续 #275 生态路线（docs/PLUGIN_API_ROADMAP.md §3 P3）。
> 对位 VS Code 的 FileSystemProvider/LSP：**协议层杠杆**——数据源按协议接一次，
> 所有依据/尽调/核查场景全能用。本篇是形状与可行性定稿，实施另开工。
> 底盘契约：docs/EVIDENCE_CONTRACT.md（evidence.retrieve.v1，PR#186）。

## 1. 现状与差距

已有的三层（`backend/src/main/java/com/checkba/service/ai/evidence/`）：

- `EvidenceRetriever` SPI + `EvidenceRetrieverRegistry`（能力发现）；
- `MemoryEvidenceRetriever`（本地记忆账本）、`McpEvidenceRetriever`（yaml 配置驱动的 MCP 适配）；
- 契约五条 conformance 场景已有测试钉住。

差距：**接口是宿主内部类，不是插件契约**。EVIDENCE_CONTRACT.md 写着「插件 JAR 实现
EvidenceRetriever 接口即可接入」，但该接口在 `com.checkba.service.ai.evidence` 包——
按 v2.4 规矩（插件只许引用 `com.checkba.plugin.api.*`）这条路今天其实是违规的；
MCP 来源接入要改宿主 yaml，第三方做不到「装个插件就生效」。

## 2. 可行性结论

**可行，且是五期里改动最小/杠杆最大的一期。** Registry、契约、conformance 测试、
MCP 适配全部已存在，缺的只是两条「插件 → Registry」的注册通道 + 治理规则。
不需要新协议设计——evidence.retrieve.v1 的字段与不变式原样升格，一个字不改。

## 3. 设计：两条接入通道

### 3.1 通道 A：JAR 插件 SPI（plugin-api 1.2.0）

`plugin-api` 新增（只加不改）：

```java
package com.checkba.plugin.api.evidence;

public interface EvidenceProvider {
    String sourceId();                       // 全局唯一，见 §5 命名规则
    List<EvidenceItem> retrieve(EvidenceQuery query);
}
// EvidenceQuery / EvidenceItem / ProvenanceEntry 为 record，
// 字段照 docs/EVIDENCE_CONTRACT.md §2/§3 逐一映射（snake_case → camelCase）。
// 新增字段永远追加在 record 末位（与 FileInfo.updatedAt 同规矩）。
```

- 发现方式：JAR 扫描时（`PluginService.loadJar`）对实现了 `EvidenceProvider` 的类
  实例化（无参构造 + `HostAware` 注入照旧），包装成宿主内部 `EvidenceRetriever`
  适配器注册进 `EvidenceRetrieverRegistry`。
- manifest 同步声明：`contributes.evidenceSources: [{ "sourceId": "...", "name": "...", "description": "..." }]`
  ——用于广场展示与人工审查；声明与实现类的 `sourceId()` 不一致时拒绝注册并记 ERROR
  （与 tools[] 声明校验同一取向：声明是审查对象，必须真实）。
- 启停联动：插件禁用 → 适配器从 Registry 摘除（Registry 查询时按
  `PluginService.isEnabled` 过滤，与 ToolRegistry 三消费点同口径）。

### 3.2 通道 B：MCP 声明（零 Java 代码接入）

manifest 直接声明 MCP 证据来源，等价于今天手改 yaml 的 `ai.evidence.mcp-sources`：

```json
"contributes": {
  "evidenceSources": [
    { "sourceId": "caselaw-cn", "transport": "mcp",
      "server": { "command": "npx", "args": ["-y", "some-mcp-server"] },
      "tool": "retrieve_evidence" }
  ]
}
```

- 宿主为该插件起 MCP 连接（复用既有 `McpEvidenceRetriever` 适配层），
  远端工具按契约返回 `{"items":[...]}`；
- **安全定位**：`transport: "mcp"` 且 `server.command` 非空 = 会在本机起子进程，
  风险量级等同 JAR——广场受理按 JAR 插件同一档审核；只声明 `server.url`
  （远程 MCP over HTTP/SSE）的属于 `network` 档。dev 免签直装通道**不收**任何
  evidenceSources（与「dev 只收纯 Web 插件」同一条红线）。

### 3.3 不做的（本期明确排除）

- Web 插件（sandbox iframe）做 provider：不做。检索是宿主编排器发起的同步调用，
  iframe 生命周期跟着面板走（不开面板就没有 provider），语义站不住；Web 插件要接
  数据源走通道 B 的远程 MCP。
- 自动爬取类 provider：红线照旧（2026-08-21 拍板）——验证码/合规风险的联网抓取
  不受理，审核清单明写。

## 4. 治理与运行时规则

| 规则 | 内容 |
|---|---|
| sourceId 命名 | 必须 `<pluginId>.<name>`（如 `qcc-pro.company-registry`），宿主注册时强制校验前缀；内置来源（memory 等）保留无点前缀命名空间 |
| 冲突 | 同 sourceId 重复注册：先注册的生效，后来的拒绝并记 ERROR（与插件 id 去重同口径） |
| 降级 | provider 抛异常/超时（单次 10s）→ 该来源返回空列表 + WARN，不炸编排主流程（契约 §5 既有行为） |
| 配额 | JAR provider 的宿主调用照 `PluginHostQuota` 计数；provider 自身被调用不计（它是被叫方） |
| conformance | 契约五场景做成可复用测试基类（plugin-api 的 test-jar 发布 `EvidenceProviderConformanceTest`），插件仓 extend 即得全套；广场受理要求附 conformance 通过截图/日志 |
| 两大不变式 | 缺定位符即丢弃、缺证据≠矛盾——适配层继续在宿主侧强制（丢弃缺 locator 条目并告警），不信任 provider 自觉 |

## 5. 版本与兼容

- 协议版本就是 `evidence.retrieve.v1`：字段只增不改；破坏性变更升 v2 双轨共存（契约 §7 既有条款）。
- plugin-api 1.1.0 → 1.2.0（只加不改）；老插件零影响。
- manifest `contributes` 是新增顶层字段，老宿主忽略未知字段（§2 既有行为）+
  `minHostVersion`（P0）声明兜底。

## 6. finalization 三问

1. **有没有真实插件要用**：有——法宝/企查查接入今天是内置硬编码（doc-insight 领域），
   第一个狗粮就是把「判决书通道」重构为一个官方 evidenceSources 插件；尽调插件的
   网核 zip 来源是第二个。
2. **有没有能跑的示例**：实施时 examples/ 加 `hello-evidence-plugin`（内存假数据 +
   conformance 全绿）。
3. **形状过窄/过宽**：形状 = 已运行半年的 v1 契约原样升格，经过 MemoryRetriever 与
   MCP 两个异构实现检验，宽窄已被实践校准。新增的只有注册通道与治理规则。

## 7. 实施拆单（另开工，预估一个 PR）

1. plugin-api 1.2.0：`evidence` 包（接口 + 三 record + conformance 测试基类）；
2. 宿主：JAR 扫描注册适配器 + manifest `contributes.evidenceSources` 解析（两种 transport）+ 启停过滤；
3. 广场受理清单与审核文案更新（website 仓）；
4. 示例插件 + PLUGIN_SPEC 升版记录。
