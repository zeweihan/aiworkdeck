# Skill 规范 v1（Skill Spec v1）

> 自 Phase 3B 引入。内置示例见 [backend/skills/listing-pathway/](../backend/skills/listing-pathway/)。
> 后端实现：`SkillRegistry`（扫描/解析/启停）、`SkillRouter`（触发匹配/prompt 注入/工具裁剪）、
> `SkillController`（HTTP API）；前端管理入口：插件广场页面的「Skill」区块
> （`frontend/src/pages/plugin-market/plugin-market.vue`）。

## 1. 概念

**Skill = prompt 模板 + 工具白名单 + 触发条件 + 输出约定的打包格式。**

用户输入命中某个 Skill 的触发关键词时，该轮对话会：

1. 把 Skill 的 prompt 模板注入本轮系统消息（由 `ContextAssemblerService` 在组装时追加，
   ASK 模式跳过——Skill 指引以工具流程为主，与 ASK 禁用工具的约束冲突）；
2. 把本轮 LLM 可见的工具集裁剪为 `allowed_tools ∪ 基础工具集`
   （复用 Phase 3A 的可见性出口：对 `ToolRegistry.getAllSpecifications()` 的结果做白名单过滤）。

未命中任何 Skill 时行为与无 Skill 体系时**完全一致**（不注入、不裁剪）。

> 边界：裁剪只影响"可见性"（LLM 看不到即不会调用），**不拦截分发**——与插件启停的可见性
> 语义一致，老对话历史里的工具调用仍可回放；XML 协议下模型若凭记忆写出白名单外的工具调用，
> 分发层不会拒绝（system prompt 中的静态工具清单不随 Skill 裁剪变化）。

## 2. 打包格式（目录式）

服务端工作目录下的 `skills/` 目录（可通过配置 `ai.skills.dir` 覆盖，做法同 `ai.plugins.dir`），
每个 Skill 一个子目录：

```
skills/
└── listing-pathway/
    ├── skill.yml    # 必需，Skill 元数据（本规范核心）
    └── prompt.md    # 必需（文件名可在 skill.yml 的 prompt 字段改），prompt 模板
```

启动时自动扫描；也可调用 `POST /api/skills/rescan` 热发现。**解析失败的 skill 跳过不阻断**
（YAML 语法错误、缺 `id`、缺 `triggers`、prompt 文件缺失都只记日志）。

## 3. skill.yml 字段

```yaml
id: listing-pathway
name: 上市路径选择比较分析
description: 面向中国资本市场律师的上市/证券化路径比较分析。
triggers:
  - 上市路径
  - IPO
  - 科创板
prompt: prompt.md
allowed_tools:
  - law_search
  - search_web
  - write_docx
output: |
  最终交付一份结构化的《上市路径比较分析》……
```

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `id` | string | **是** | 全局唯一、稳定的 Skill 标识（kebab-case）。缺失则跳过；重复 id 只加载先扫描到的。启停状态以 id 为键持久化。 |
| `name` | string | 否 | 展示名称（中文优先），缺省用 id。 |
| `description` | string | 否 | 一句话描述，展示在管理页卡片上。 |
| `triggers` | string[] | **是** | 触发条件：关键词列表（v1 只支持关键词）。用户输入**包含**任一关键词即命中，不区分大小写。为空则跳过（永远不可能命中）。 |
| `prompt` | string | 否 | prompt 模板文件名（相对 skill 目录），默认 `prompt.md`。文件缺失则整个 skill 跳过。 |
| `allowed_tools` | string[] | 否 | 工具白名单（真实注册的工具名，见 `ToolRegistry`）。命中后本轮 LLM 可见工具 = `allowed_tools ∪ ai.skills.base-tools`；白名单与已注册工具零交集时回退为不裁剪（误配置保护）。缺省 `[]` = 只剩基础工具集。 |
| `output` | string | 否 | 输出结构约定（自然语言），随 prompt 模板一起注入系统消息。 |
| `requires` | string[] | 否 | 声明依赖的能力契约（如 `evidence.retrieve.v1`，见 `docs/EVIDENCE_CONTRACT.md`）。v1 仅声明不阻断加载：Skill 描述"需要什么能力"，插件/内置实现负责提供，实现缺失时相关工具自然不可见。 |

未知字段被忽略（向前兼容）。

## 4. 触发与路由语义

- **匹配**：`SkillRouter.match(userInput)` 在所有**可用** skill（自身启用、且所属插件未被禁用）
  中查找命中关键词的 skill。生效方式为 `manual` 的 skill 不参与自动匹配（见 §7）。
- **多命中**：取"最长命中关键词"的 skill（更长的关键词 = 更 specific 的意图）；并列时取先注册的
  （内置 `skills/` 目录按目录名排序优先于插件携带的）。
- **用户钉选**：对话请求可带 `pinnedSkillId`，**优先于触发词匹配**——用户明确指定的意图不该被
  关键词猜测覆盖。钉选 id 不存在或不可用时退回自动匹配，避免前端状态过期导致本轮无 skill。
- **每轮刷新**：每条用户消息重新匹配一次；本轮未命中即清除激活状态，回到无 Skill 行为。
- **注入位置**：系统消息中模式约束（MODE OVERRIDE）之后、Current Context 之前，
  以 `# Active Skill: <name>` 开头。

## 5. 插件携带 Skill（插件规范 v2.1）

插件 `manifest.json` 可通过 `skills` 字段携带 Skill（值为插件目录内的 skill 子目录名列表）：

```json
{ "id": "my-plugin", "skills": ["my-skill"] }
```

`PluginService` 扫描时收集这些目录（`getPluginSkillDirs()`），`SkillRegistry` 启动/重扫时注册，
并记录 `sourcePluginId`。**所属插件被禁用时其 skill 不参与触发匹配**（但仍在列表中供管理页展示）。
详见 docs/PLUGIN_SPEC.md §8。

## 6. 配置（application.yml）

```yaml
ai:
  skills:
    dir: skills                                        # 扫描目录
    base-tools: [read_document, list_files, query_memory]  # 基础工具集
    disabled-cache-ttl-ms: 5000                        # 启停名单缓存 TTL
```

对应 `SkillProperties`（前缀 `ai.skills`）。基础工具集保证被裁剪的回合仍具备最基本的
读取/记忆能力，按部署需要调整，不要写死在代码里。

## 7. 生效方式（三档）

| 档位 | 含义 |
|---|---|
| `auto` | 默认。命中触发词时自动生效 |
| `manual` | 不参与自动匹配，只能由用户在对话中钉选生效 |
| `disabled` | 停用，既不自动匹配也不能被钉选 |

- 存储上是**两个正交名单**：`ai.skills.disabled` 与 `ai.skills.manual`（均为 skill id 的
  JSON 数组，存 `system_setting` 表），`SkillRegistry.activationMode()` 组合成三档对外呈现，
  `disabled` 优先。`setActivationMode()` 是三档的唯一写入口。
- `manual` 的 skill **`isAvailable` 仍为真**——它只是不参与自动匹配，钉选时照常生效。
- 状态查询走内存缓存（TTL 配置 `ai.skills.disabled-cache-ttl-ms`，默认 5000ms）。
- 旧的 `setEnabled` / enable / disable 端点保留，只增删 disabled 名单，不影响 manual 标记。

## 8. HTTP API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/skills/list` | 登录 | Skill 列表：id/name/description/triggers/allowedTools/sourcePluginId/enabled/activationMode |
| POST | `/api/skills/{id}/enable` | admin | 启用 skill |
| POST | `/api/skills/{id}/disable` | admin | 禁用 skill |
| POST | `/api/skills/{id}/activation` | admin | 设置生效方式，body `{"mode":"auto"\|"manual"\|"disabled"}` |
| POST | `/api/skills/rescan` | admin | 重扫 skills/ 目录与插件携带的 skill，返回 `{ code, skillCount }` |

管理接口鉴权与 PluginController 一致：`X-Session-Id` 请求头 → session 用户名为 `admin`。

## 9. 内置 Skill

| id | 名称 | 触发（节选） | 工具白名单 |
|---|---|---|---|
| `listing-pathway` | 上市路径选择比较分析 | 上市路径 / IPO / 科创板 / 北交所 / 红筹 / VIE / 借壳 / 证券化 / SPAC… | law_search、law_search_keyword、get_law_article、law_recognition、search_web、browse_url、write_docx |

## 10. 与架构不变式的关系（docs/AI_ARCHITECTURE.md §2）

- 编排器仅新增对 `SkillRouter` 的两处调用（触发匹配、可见工具过滤），不含业务逻辑，
  不感知任何具体 skill/工具；
- prompt 注入在读侧 `ContextAssemblerService` 组装时完成，不涉及记忆写入；
- 所有限值/目录/基础工具集在 `SkillProperties`（`ai.skills.*`）配置外置；
- SSE 事件字典与 chat 出入参未变，Skill 能力纯后端旁路，向后兼容。

## 11. 版本演进

- **v1（当前，Phase 3B）**：目录式打包、关键词触发、prompt 注入、工具可见性裁剪、
  启停持久化、插件携带 skill。
- 规划中：语义触发（embedding 相似度）、skill 参数化模板、命中事件透出到前端展示。
