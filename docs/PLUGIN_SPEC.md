# 插件规范 v1（Plugin Spec v1）

> 适用版本：0.4.x 起。示例插件见 [examples/hello-plugin/](../examples/hello-plugin/)。
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
| `permissions` | string[] | 否 | 声明插件需要的能力，见 §3。缺省视为不需要任何敏感能力。 |
| `tools` | object[] | 否 | 工具清单（`name` + 中文 `description`），用于插件广场展示与人工审查。`name` 应与 JAR 中 `@Tool` 方法名一致。 |
| `frontendEntry` | string | 否 | 前端入口（预留，v1 不加载）。 |
| `backendJars` | string[] | 否 | 相对插件目录的 JAR 文件名列表，启动/重扫时加载其中带 `@Tool` 注解的类。 |

未知字段被忽略（向前兼容）；`permissions` 中出现 v1 未定义的值仅记录 WARN，不拒绝加载。

## 3. permissions 权限声明（v1）

v1 权限为**声明式**：用于插件广场展示与管理员审查，运行时沙箱强制执行在 Phase 3 后续（见 docs/AI_ARCHITECTURE.md TODO）。

| 值 | 含义 |
|---|---|
| `file_read` | 读取项目文件内容 |
| `file_write` | 创建 / 修改 / 删除项目文件 |
| `network` | 访问外部网络（HTTP 等出站请求） |
| `editor` | 操作文档编辑器（LOWA/LibreOffice 相关原语） |

## 4. 后端工具（backendJars）约定

- 工具类需有**无参构造函数**，工具方法用 langchain4j 的 `dev.langchain4j.agent.tool.@Tool` 注解（当前宿主版本 **0.36.0**，编译时以 `provided` 作用域依赖 `langchain4j-core`，运行时由宿主提供）。
- 工具名 = 方法名，全局唯一；与内置工具或其他插件重名时后注册的覆盖先注册的（避免与内置工具重名）。
- JAR 由独立 `URLClassLoader` 加载（父加载器为应用 ClassLoader），依赖冲突自行规避；无法解析的类会被跳过。

## 5. 启用 / 禁用

- 默认**启用**；禁用名单持久化在 `system_setting` 表（key = `ai.plugins.disabled`，值为插件 id 的 JSON 数组），重启后保持。
- 查询接口：`PluginService.isEnabled(pluginId)`；工具归属：`PluginService.getPluginIdForTool(toolName)`。
- v1 范围：启停状态已持久化并在插件广场展示，**ToolRegistry 按启停过滤工具的接入是 Phase 3 后续**（接入点见 docs/AI_ARCHITECTURE.md TODO），即 v1 中禁用插件后其工具暂不会立即从 Agent 可用工具中移除。

## 6. HTTP API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/plugins/list` | 登录 | 插件列表：元数据 + `permissions` + `tools` + `toolCount` + `enabled` |
| POST | `/api/plugins/{id}/enable` | admin | 启用插件 |
| POST | `/api/plugins/{id}/disable` | admin | 禁用插件 |
| POST | `/api/plugins/rescan` | admin | 重新扫描 plugins/ 目录，返回 `{ code, pluginCount, toolCount }` |

管理接口鉴权与 AdminConfigController 一致：`X-Session-Id` 请求头 → session 用户名为 `admin`。

## 7. 版本演进

- **v1（当前）**：声明式 manifest + 启停持久化 + 插件广场展示。
- 规划中（Phase 3 后续，见 AI_ARCHITECTURE.md）：ToolRegistry 按启停/权限过滤、运行时沙箱强制 permissions、frontendEntry 动态加载、插件签名与来源校验。
