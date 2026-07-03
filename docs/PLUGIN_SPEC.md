# 插件规范 v2.1（Plugin Spec v2.1）

> 适用版本：v1 自 0.4.x；v2（权限执行 + 启停过滤）自 Phase 3A；v2.1（插件携带 Skill）自 Phase 3B。示例插件见 [examples/hello-plugin/](../examples/hello-plugin/)。
> 后端实现：`PluginService`（扫描/解析/启停）、`PluginController`（HTTP API）；
> 前端管理页：`frontend/src/pages/plugin-market/plugin-market.vue`（插件广场，入口在系统管理侧边栏）。

## 1. 目录结构

服务端工作目录下的 `plugins/` 目录（可通过配置 `ai.plugins.dir` 覆盖），每个插件一个子目录：

```
plugins/
└── hello-plugin/
    ├── manifest.json          # 必需，插件元数据（本规范核心）
    └── hello-plugin-1.0.0.jar # 可选，后端工具 JAR（manifest.backendJars 声明）
```

启动时自动扫描；也可在插件广场点击「重新扫描」或调用 `POST /api/plugins/rescan` 热发现新插件（注意：重扫只能发现新插件/新元数据，已加载进 JVM 的旧类不会被卸载，替换 JAR 需重启后端）。

## 2. manifest.json 字段

```json
{
  "id": "hello-plugin",
  "name": "Hello 示例插件",
  "version": "1.0.0",
  "description": "演示插件规范 v1 的最小示例：提供文本回显与字数统计两个 AI 工具。",
  "icon": "🔌",
  "author": "AI Workdeck",
  "homepage": "https://github.com/zeweihan/checkba_cloud",
  "permissions": ["network"],
  "tools": [
    { "name": "helloEcho", "description": "原样回显输入文本，用于验证插件链路" },
    { "name": "helloWordCount", "description": "统计输入文本的字符数与词数" }
  ],
  "frontendEntry": null,
  "backendJars": ["hello-plugin-1.0.0.jar"]
}
```

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `id` | string | **是** | 全局唯一、稳定的插件标识（kebab-case）。缺失则整个插件被跳过；重复 id 只加载先扫描到的目录。启停状态以 id 为键持久化。 |
| `name` | string | 是 | 展示名称（中文优先）。 |
| `version` | string | 是 | 语义化版本，如 `1.0.0`。 |
| `description` | string | 否 | 一句话描述，展示在插件广场卡片上。 |
| `icon` | string | 否 | emoji（如 `🔌`）或图片 URL/绝对路径；缺省时前端显示 `🧩`。 |
| `author` | string | 否 | 作者 / 组织名。 |
| `homepage` | string | 否 | 主页或仓库 URL。 |
| `permissions` | string[] | 否 | 声明插件**被授予**的能力，见 §3。缺省视为不需要任何敏感能力。 |
| `tools` | object[] | 否 | 工具清单（`name` + 中文 `description` + 可选 `permissions`），用于插件广场展示、人工审查与 v2 权限校验。`name` 应与 JAR 中 `@Tool` 方法名一致；`permissions` 声明**该工具运行所需**的能力（v2 新增，见 §3）。 |
| `frontendEntry` | string | 否 | 前端入口（预留，v1 不加载）。 |
| `backendJars` | string[] | 否 | 相对插件目录的 JAR 文件名列表，启动/重扫时加载其中带 `@Tool` 注解的类。 |
| `skills` | string[] | 否 | **v2.1 新增**：插件携带的 Skill 子目录名列表（相对插件目录），见 §7。 |

未知字段被忽略（向前兼容）；`permissions` 中出现 v1 未定义的值仅记录 WARN，不拒绝加载。

## 3. permissions 权限模型（v2）

| 值 | 含义 |
|---|---|
| `file_read` | 读取项目文件内容 |
| `file_write` | 创建 / 修改 / 删除项目文件 |
| `network` | 访问外部网络（HTTP 等出站请求） |
| `editor` | 操作文档编辑器（LOWA/LibreOffice 相关原语） |

### v2 执行语义（分发前校验）

两级声明 + 分发时校验（实现在 `PluginService.missingPermissionsForTool()` +
`ToolRegistry.execute()`）：

- **插件级 `permissions`**：插件被授予的能力全集，管理员在插件广场审查的对象。
- **工具级 `tools[].permissions`**：单个工具运行所需的能力。
- **校验规则**：分发插件工具前检查「工具所需 ⊆ 插件声明」；有所需权限未在插件级
  `permissions` 声明时**拒绝执行**，返回
  `Error: permission denied — tool 'x' requires permission(s) [...] not declared in the plugin manifest "permissions"`。
- **v1 兼容**：工具未列入 `tools[]` 或未写 `permissions` 视为无敏感能力需求，直接放行；
  内置工具（不属于任何插件）不参与校验。

> 边界：v2 校验发生在分发层（诚实声明模型），插件代码本身仍与宿主同进程运行，
> **进程级沙箱（真正阻止未声明的文件/网络访问）是后续项**，见 docs/AI_ARCHITECTURE.md Phase 3 TODO。

## 4. 后端工具（backendJars）约定

- 工具类需有**无参构造函数**，工具方法用 langchain4j 的 `dev.langchain4j.agent.tool.@Tool` 注解（当前宿主版本 **0.36.0**，编译时以 `provided` 作用域依赖 `langchain4j-core`，运行时由宿主提供）。
- 工具名 = 方法名，全局唯一；与内置工具或其他插件重名时后注册的覆盖先注册的（避免与内置工具重名）。
- JAR 由独立 `URLClassLoader` 加载（父加载器为应用 ClassLoader），依赖冲突自行规避；无法解析的类会被跳过。

## 5. 启用 / 禁用

- 默认**启用**；禁用名单持久化在 `system_setting` 表（key = `ai.plugins.disabled`，值为插件 id 的 JSON 数组），重启后保持。
- 查询接口：`PluginService.isEnabled(pluginId)`；工具归属：`PluginService.getPluginIdForTool(toolName)`。
- **v2 起 ToolRegistry 按启停过滤**（Phase 3A）：禁用插件后其工具在三处消费点全部不可见——
  LLM 拿不到工具规格（`getAllSpecifications`）、XML 协议解析不识别（`toolNamesLongestFirst`）、
  分发返回 not found（`resolve`）；重新启用即时恢复，内置工具不受影响。
- 启停查询走内存缓存，TTL（配置 `ai.plugins.disabled-cache-ttl-ms`，默认 5000ms）过期后从
  `system_setting` 重读：同 JVM 内启停即时生效，外部直接改库在 TTL 内收敛，工具调用高频路径不打库。

## 6. HTTP API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/plugins/list` | 登录 | 插件列表：元数据 + `permissions` + `tools` + `toolCount` + `enabled` |
| POST | `/api/plugins/{id}/enable` | admin | 启用插件 |
| POST | `/api/plugins/{id}/disable` | admin | 禁用插件 |
| POST | `/api/plugins/rescan` | admin | 重新扫描 plugins/ 目录，返回 `{ code, pluginCount, toolCount }` |

管理接口鉴权与 AdminConfigController 一致：`X-Session-Id` 请求头 → session 用户名为 `admin`。

## 7. 插件携带 Skill（v2.1）

插件可通过 manifest 的 `skills` 字段携带 Skill（Skill 规范见 [docs/SKILL_SPEC.md](SKILL_SPEC.md)）：

```
plugins/
└── my-plugin/
    ├── manifest.json        # "skills": ["my-skill"]
    └── my-skill/
        ├── skill.yml
        └── prompt.md
```

- `skills` 中的每一项是**相对插件目录**的 skill 子目录名；目录不存在时记 WARN 跳过。
- `PluginService` 扫描时只**收集目录**（`getPluginSkillDirs()`），skill.yml 的解析、注册与
  启停统一由 `SkillRegistry` 负责，并记录来源插件 id（`sourcePluginId`）。
- **插件被禁用时，其携带的 skill 不参与触发匹配**（管理页仍可见）；插件重新启用即恢复。
- skill 自身的启停独立持久化（`ai.skills.disabled`），与插件启停叠加生效。

## 8. 版本演进

- **v1（0.4.x）**：声明式 manifest + 启停持久化 + 插件广场展示。
- **v2（Phase 3A）**：ToolRegistry 按启停过滤三处消费点 + `tools[].permissions`
  分发前权限校验（诚实声明模型）+ 启停缓存 TTL。
- **v2.1（当前，Phase 3B）**：manifest 新增 `skills` 字段，插件可携带 Skill（见 §7 与
  docs/SKILL_SPEC.md）。
- 规划中（见 AI_ARCHITECTURE.md Phase 3 TODO）：进程级运行时沙箱（真正强制 file/network 隔离）、
  frontendEntry 动态加载、插件签名与来源校验。
