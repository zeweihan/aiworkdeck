# 证据检索契约 evidence.retrieve.v1

> 源起：RFC [#14](https://github.com/zeweihan/aiworkdeck/issues/14) 社区讨论（@mheilimo 的评论）。
> 核心取向：**契约稳定、传输可插拔**——MCP 只是传输适配层之一，不是证据契约本身。
> 本地记忆、远程服务、MCP 服务器都以同一契约接入；Skill 声明依赖能力，插件负责实现。

## 1. 分层

```
Skill（requires: evidence.retrieve.v1）
   │  声明"需要什么能力"，把证据对象映射进法律工作流
   ▼
EvidenceRetrieverRegistry（能力发现）
   │
   ├─ MemoryEvidenceRetriever      本地：记忆证据账本（PR#155）
   ├─ McpEvidenceRetriever          MCP 传输适配（ai.evidence.mcp-sources 配置驱动）
   └─ （插件 JAR 实现 EvidenceRetriever 接口即可接入）
```

代码位置：`backend/src/main/java/com/checkba/service/ai/evidence/`。
LLM 入口：工具 `retrieve_evidence`（`EvidenceTools`）。

## 2. 请求（EvidenceQuery）

| 字段 | 类型 | 说明 |
|---|---|---|
| `workspace_id` | string | 工作区标识。本地实现解释为项目 ID（纯数字或 `project:<id>`） |
| `query` | string | 检索文本 |
| `as_of` | date? | 时点语义：只返回该日期（含）之前生效的证据；缺省 = 现在 |
| `source_filters` | string[] | 来源过滤（本地实现按记忆作用域解释），空 = 不过滤 |
| `access_context` | map | 访问上下文（userId / conversationId 等），实现据此做权限裁剪 |
| `limit` | int | 结果上限 |

## 3. 单条证据（EvidenceItem）

| 字段 | 必填 | 说明 |
|---|---|---|
| `evidence_id` | **是** | 稳定证据 ID：同一来源同一内容重复检索必须相同（幂等重放的基础） |
| `source_uri` | **是** | 来源 URI |
| `locator` | **是** | 精确定位符（段落号/条文号/记录主键）。**缺定位符的内容必须丢弃，不得编造** |
| `content_hash` | 否 | 内容哈希（sha256），内容变即哈希变 |
| `retrieved_at` | 否 | 本次检索时间 |
| `effective_date` | 否 | 生效日期 |
| `excerpt` | 否 | 有界摘录（≤500 字符，超长截断） |
| `mime_type` | 否 | 内容类型 |
| `access_policy` | 否 | 访问策略标注（如 `project`、`user:protected`） |
| `provenance` | 否 | 溯源链（有序） |
| `supersedes` / `revokes` | 否 | 可选事件：本条取代/吊销的证据 ID |
| `superseded_at` | 否 | 更新信号：本条已有更晚版本时，最新版本时间（证据账本语义） |

## 4. 主张关联（ClaimLink）——与检索分层

检索只负责拿回证据；"证据支持/矛盾哪个主张"是独立的解释层对象：
`claim_id`、`evidence_ids`、`relation`（supports / contradicts / context）、`confidence`、`reviewer`、`missing_evidence`。

**硬性不变式：证据缺失绝不允许被改写为矛盾。** `CONTRADICTS` 必须援引至少一条真实证据；
"查无此据"用 `missing_evidence` 表达。构造器强制校验（`ClaimLink.java`）。

## 5. MCP 接入（传输适配）

```yaml
ai:
  evidence:
    mcp-sources:
      - source-id: caselaw
        server: some-mcp-server    # 须已在 mcp.servers 配置
        tool: retrieve_evidence    # 远端工具按本契约返回 {"items":[...]}
```

远端 MCP 工具收到第 2 节的请求字段（snake_case），返回 `{"items":[EvidenceItem...]}`。
适配层行为：缺 `evidence_id`/`source_uri`/`locator` 的条目丢弃并告警；来源报错/不可用返回空列表降级，不炸编排主流程。

## 6. 一致性要求（conformance）

任何实现必须通过以下场景（现有用例见 `backend/src/test/java/com/checkba/service/ai/evidence/`）：

1. **文档变更**：内容有更新版本时携带 `superseded_at`/`supersedes` 信号，旧证据不冒充最新；
2. **来源冲突**：相互矛盾的证据都如实返回（哈希不同），不静默合并，矛盾判定交给 claim_link 层；
3. **缺定位符**：无法给出精确 locator 的内容直接丢弃，不编造定位符；
4. **权限吊销**：过期/被吊销/无权访问的内容不返回，来源不可用时降级为空列表；
5. **幂等重放**：同一请求重复执行，`evidence_id` 与 `content_hash` 序列一致。

## 7. 版本演进

字段只增不改不删；破坏性变更升 `evidence.retrieve.v2`（新接口共存，旧契约继续可用）。
与 `docs/AI_ARCHITECTURE.md` 的不变式一致：新增证据来源不修改编排器。
