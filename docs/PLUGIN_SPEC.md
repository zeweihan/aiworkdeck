# 插件规范 v2.8（Plugin Spec v2.8）

> 适用版本：v1 自 0.4.x；v2（权限执行 + 启停过滤）自 Phase 3A；v2.1（插件携带 Skill）自 Phase 3B；
> v2.3（Web 插件 + `packs` 依赖）自 native pack Phase B；v2.4（宿主 SPI `plugin-api` + 后台任务）自尽调 P1；
> v2.5（Web 插件直调工具端点 + 桥新增 `tools.invoke`/`chat.send`/`ui.openFile`）自尽调 P1 补充。
> v2.6（主题通道：`init` 带 `themeTokens`、宿主推送 `type:"theme"`、SDK 自动注入 CSS 变量）随深色模式收口（dev-board#274）加入。
> v2.7（生态路线 P0-P2，宿主 0.27.4 起（v0.27.4 发版恰在 #659 合并后切出，v2.7 随它发布），dev-board#280/281/282）：治理地基（`minHostVersion` + 实验 API
> `x-` 前缀 + §12 只加不改章程）、文档读写权（桥 `doc.exec`/`doc.active` + 事件通道 `type:"event"`）、
> AI 调用权（桥 `ai.request` 走平台 Credits + 权限值 `ai`）。设计定稿见 docs/superpowers/specs/
> 2026-08-29-plugin-p0/p1/p2 三篇；生态总路线 docs/PLUGIN_API_ROADMAP.md。
> v2.8（生态路线 P3，宿主 0.28 起，dev-board#283）：evidence.retrieve.v1 升格公开 Provider 协议——
> manifest 新增 `contributes.evidenceSources`（§13），plugin-api 1.2.0 新增 `evidence` 包
> （EvidenceProvider SPI + conformance 执行器）。
> 示例插件：[examples/hello-plugin/](../examples/hello-plugin/)（JAR 工具）、
> [examples/hello-web-plugin/](../examples/hello-web-plugin/)（纯前端）。
> 后端实现：`PluginService`（扫描/解析/启停）、`PluginController`（HTTP API）、
> `PluginWebController`（Web 插件静态服务）；
> 前端管理页：`frontend/src/pages/plugin-market/plugin-market.vue`（插件广场，入口在系统管理侧边栏）；
> Web 插件宿主桥：`frontend/src/components/PluginPane.vue`；SDK 源头：[sdk/plugin-sdk/](../sdk/plugin-sdk/)。

## 1. 目录结构

服务端工作目录下的 `plugins/` 目录（可通过配置 `ai.plugins.dir` 覆盖），每个插件一个子目录：

```
plugins/
└── hello-plugin/
    ├── manifest.json          # 必需，插件元数据（本规范核心）
    ├── hello-plugin-1.0.0.jar # 可选，后端工具 JAR（manifest.backendJars 声明）
    └── web/                   # 可选，Web 插件的静态资源（frontendEntry 指向其中，见 §8）
        └── index.html
```

`ai.plugins.dir` 默认是相对路径 `plugins`，**实际落点由后端进程的工作目录决定**：

| 形态 | 工作目录 | 插件目录 |
|---|---|---|
| 本地开发 | `<repo>/backend` | `backend/plugins/` |
| 打包桌面版 | `~/.aiworkdeck` | `~/.aiworkdeck/plugins/` |

桌面版这个位置在**用户家目录下且可写**——任何以该用户身份运行的本地进程都能往里丢
插件，下次启动即被加载。这是威胁模型里必须记住的一条：`plugins/` 目录的写权限
等价于在宿主 JVM 里执行任意代码。

启动时自动扫描；也可在插件广场点击「重新扫描」或调用 `POST /api/plugins/rescan` 热发现新插件（注意：重扫只能发现新插件/新元数据，已加载进 JVM 的旧类不会被卸载，替换 JAR 需重启后端）。

## 2. manifest.json 字段

```json
{
  "id": "hello-plugin",
  "name": "Hello 示例插件",
  "version": "1.0.0",
  "description": "演示插件规范 v1 的最小示例：提供文本回显与字数统计两个 AI 工具。",
  "icon": "🔌",
  "author": "AI WorkDeck",
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
| `permissions` | string[] | 否 | 插件**自行声明**会用到的能力，见 §3。缺省视为不需要任何敏感能力。注意这是作者的自述，不是运行时授权。 |
| `tools` | object[] | 否 | 工具清单（`name` + 中文 `description` + 可选 `permissions`），用于插件广场展示、人工审查与 v2 权限校验。`name` 应与 JAR 中 `@Tool` 方法名一致；`permissions` 声明**该工具运行所需**的能力（v2 新增，见 §3）。 |
| `frontendEntry` | string | 否 | **v2.3 起激活**：`web/index.html` 这样的相对路径 = Web 插件（见 §8）；`http(s)://` 绝对 URL = 旧形态，宿主直接 iframe 打开外部页面。 |
| `minHostVersion` | string | 否 | **v2.7 新增**：本插件需要的最低宿主版本（semver）。宿主低于它时插件登记但**不生效**（不加载 JAR、不注册工具、不服务 web/），管理页提示「需要宿主 ≥ X，请升级客户端」，enable 明确拒绝；广场安装与 dev 直装在装前就拦。非法格式视为缺省并 WARN。dev 态宿主（版本 `dev`）跳过校验。注意它保护不了 0.27.3 及更老的宿主（它们不认识这个字段）——用了 v2.7 能力的插件应声明 `"0.27.4"`，缺口随存量宿主升级自然收敛。 |
| `backendJars` | string[] | 否 | 相对插件目录的 JAR 文件名列表，启动/重扫时加载其中带 `@Tool` 注解的类。 |
| `skills` | string[] | 否 | **v2.1 新增**：插件携带的 Skill 子目录名列表（相对插件目录），见 §7。 |
| `packs` | string[] | 否 | **v2.3 新增**：依赖的原生资源包 id 列表，见 §9 与 [NATIVE_PACK_DISTRIBUTION.md](NATIVE_PACK_DISTRIBUTION.md)。 |
| `contributes` | object | 否 | **v2.8 新增**：向宿主贡献的声明式内容。当前只有 `evidenceSources`（证据来源，见 §13）；后续贡献点（templates/styleProfiles 等）按只加不改逐个进来。 |

未知字段被忽略（向前兼容）；`permissions` 中出现 v1 未定义的值仅记录 WARN，不拒绝加载。

## 3. permissions 自述与一致性校验（v2）

| 值 | 含义 |
|---|---|
| `file_read` | 读取项目文件内容 |
| `file_write` | 创建 / 修改 / 删除项目文件 |
| `network` | 访问外部网络（HTTP 等出站请求） |
| `editor` | 操作文档编辑器（LOWA/LibreOffice 相关原语） |
| `ai` | **v2.7 新增**：经宿主平台通道调用 AI 模型（桥 `ai.request`，消耗**用户的** Credits）。广场受理时的人工审查重点项 |

> **先读这一段，否则会高估它的作用。**
>
> 这不是权限模型，是 manifest 内部的一致性 lint。校验比较的两个集合——「工具所需」
> 与「插件声明」——**都来自插件作者自己写的同一个 manifest.json**，作者改任意一边
> 即可让校验恒过（顶层写满四个权限，或干脆不写 `tools[].permissions`）。
>
> 更重要的是，**声明与实际行为完全不挂钩**：声明了空权限的工具，方法体里照样可以
> 读写任意文件、发任意网络请求。这四个字符串在后端只用于日志、上述集合比较和前端
> 标签展示，没有任何 FileService / HTTP 客户端 / EditorBridge 会去查它们。
>
> 它的真实价值：帮**诚实的**作者暴露"忘了声明"的疏漏，以及给人工审查提供一份可读的
> 能力自述。对恶意插件零防御力。

> **Web 插件（§8）是例外，也是这套声明第一次真正落地的地方。** 那类插件不进 JVM，
> 跑在 opaque origin 的 sandbox iframe 里，唯一的出口是 postMessage 桥；桥的宿主端
> 逐调用比对 `permissions`（缺 `file_read` 时 `files.*` 直接返回 `permission_denied`），
> `network` 还会改写静态响应的 CSP `connect-src`。同一串字符串，在 JAR 插件上是自述，
> 在 Web 插件上是**由浏览器与宿主共同执行的边界**。

两级声明 + 分发时校验（实现在 `PluginService.missingPermissionsForTool()` +
`ToolRegistry.execute()`）：

- **插件级 `permissions`**：插件自述会用到的能力全集，人工审查的对象。
- **工具级 `tools[].permissions`**：单个工具自述运行所需的能力。
- **校验规则**：分发插件工具前检查「工具所需 ⊆ 插件声明」；有所需权限未在插件级
  `permissions` 声明时**拒绝执行**，返回
  `Error: permission denied — tool 'x' requires permission(s) [...] not declared in the plugin manifest "permissions"`。
- **v1 兼容**：工具未列入 `tools[]` 或未写 `permissions` 视为无敏感能力需求，直接放行；
  内置工具（不属于任何插件）不参与校验。

### 真实的信任边界在哪里

插件 JAR 与宿主**同一个 JVM、同一个 Spring 容器、同一份权限**。`URLClassLoader` 的
父加载器就是宿主自己，所以插件可以直接 `import com.checkba.*` 拿到 `DataSource`、
`SystemSettingService`（**AI 供应商 API Key 存在这里**）等任意 Bean，也能读写用户
home 下的任意文件、起子进程、改 JVM 全局状态。

Java 侧没有进程内沙箱可用（`SecurityManager` 已于 JEP 411 废弃、JEP 486 在 JDK 24
永久禁用，OpenJDK 官方给出的替代方案就是进程外隔离）。因此：

**安装一个 JAR 插件 = 信任它等同于信任一个本机应用程序。** 这一点对本地手放的插件
无法通过技术手段缓解，只能靠分发链路（签名、审核、封禁）把不可信来源挡在外面——
见 [docs/PLUGIN_DISTRIBUTION.md](PLUGIN_DISTRIBUTION.md)。

## 4. 后端工具（backendJars）约定

- 工具类需有**无参构造函数**，工具方法用 langchain4j 的 `dev.langchain4j.agent.tool.@Tool` 注解（当前宿主版本 **0.36.0**，编译时以 `provided` 作用域依赖 `langchain4j-core`，运行时由宿主提供）。
- 工具名 = 方法名，全局唯一；与内置工具或其他插件重名时后注册的覆盖先注册的（避免与内置工具重名）。
- 每个 JAR 一个 `URLClassLoader` 实例，**父加载器是宿主的应用 ClassLoader**——这是标准双亲委派，
  不是隔离：插件能看见宿主的全部类（Spring、项目自己的 `com.checkba.*` service/repository）。
  依赖冲突自行规避；无法解析的类会被跳过。
- `backendJars` 的路径必须落在插件目录内（含子目录），`../` 逃逸会被拒绝并记 ERROR。
- **`HostAware`（v2.4）**：工具类若实现 `com.checkba.plugin.api.HostAware`，宿主在无参构造实例化后
  立即调用 `setHost(PluginHost)`，注入按插件 id 绑定的宿主门面（项目文件 / 文本抽取与 OCR / 标签 /
  证据链接 / 后台任务 / 编辑器桥 / 设置 / LLM）。不实现则与旧规范完全一致。方法表见 §11。
  插件代码**只许**引用 `com.checkba.plugin.api.*`：SPI 之外的宿主内部类（`com.checkba.service.*` 等）
  虽然在同一 JVM 里看得见，但不是契约，随时会改；插件仓应有一条「源码不含 `import com.checkba.service`」
  的静态测试（ArchUnit 风格反射断言）。

## 5. 启用 / 禁用

- 默认**启用**；禁用名单持久化在 `system_setting` 表（key = `ai.plugins.disabled`，值为插件 id 的 JSON 数组），重启后保持。
- 查询接口：`PluginService.isEnabled(pluginId)`；工具归属：`PluginService.getPluginIdForTool(toolName)`。
- **v2 起 ToolRegistry 按启停过滤**（Phase 3A）：禁用插件后其工具在三处消费点全部不可见——
  LLM 拿不到工具规格（`getAllSpecifications`）、XML 协议解析不识别（`toolNamesLongestFirst`）、
  分发返回 not found（`resolve`）；重新启用即时恢复，内置工具不受影响。

### 禁用到底停掉了什么

| 时机 | 行为 |
|---|---|
| 启动 / rescan 时已处于禁用 | **JAR 不加载**：静态初始化块与构造器都不会执行，元数据仍登记以便管理页展示与启停 |
| 运行中禁用一个已加载的插件 | 工具立即不可见、不可分发，但**类已在 JVM 中无法卸载**——若插件在构造器里起了线程或注册了全局钩子，这些不会因禁用而消失，**需重启后端才能彻底停掉** |
| 运行中启用一个启动时被禁的插件 | 自动补加载其 JAR（`loadJarsIfAbsent`），工具随即可用 |

> 结论：禁用可以阻止**尚未加载**的插件运行，但不能撤销**已加载**插件造成的影响。
> 处置可疑插件的正确顺序是：禁用 → 重启后端 → 从 `plugins/` 目录移除。

### rescan 的限制

`rescan()` 清空元数据与工具映射后全量重扫，用于**发现新装插件**。已由旧 ClassLoader
加载的类不会卸载；替换同名 JAR 的新版本必须重启后端才能生效，否则运行的仍是旧类。
- 启停查询走内存缓存，TTL（配置 `ai.plugins.disabled-cache-ttl-ms`，默认 5000ms）过期后从
  `system_setting` 重读：同 JVM 内启停即时生效，外部直接改库在 TTL 内收敛，工具调用高频路径不打库。

## 6. HTTP API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/plugins/list` | 登录 | 插件列表：元数据 + `permissions` + `tools` + `toolCount` + `enabled` |
| POST | `/api/plugins/{id}/enable` | admin | 启用插件 |
| POST | `/api/plugins/{id}/disable` | admin | 禁用插件 |
| POST | `/api/plugins/rescan` | admin | 重新扫描 plugins/ 目录，返回 `{ code, pluginCount, toolCount }` |
| GET | `/api/plugin-web/{id}/**` | 无 | Web 插件静态资源（`plugins/<id>/web/` 之下），见 §8.2 |
| POST | `/api/plugins/{id}/tools/{tool}` | 登录 + 项目写权限 | **v2.5 新增**：直调插件工具，见 §8.7 |

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

## 8. Web 插件（v2.3）

`frontendEntry` 从「预留，v1 不加载」正式激活。一个只会写 HTML/JS 的开发者由此能造出
看得见的东西——不需要 Java，不需要编译，`web/index.html` 就是全部。

```
plugins/
└── my-web-plugin/
    ├── manifest.json        # "frontendEntry": "web/index.html", "permissions": ["file_read"]
    └── web/
        ├── index.html
        └── awd-plugin-sdk.js
```

纯 Web 插件**可以没有任何 JAR**（`backendJars` 留空或不写）：这类插件不进 JVM，
风险量级比 JAR 低一整档。

### 8.1 frontendEntry 的两种形态

| 形态 | 例子 | 行为 |
|---|---|---|
| `web/` 之下的相对路径 | `web/index.html` | Web 插件：后端静态服务 + sandbox iframe + postMessage 桥 |
| `http(s)://` 绝对 URL | `https://example.com/panel` | 旧形态：iframe 直接打开外部页面，不加 sandbox、不握手、不响应桥调用 |

扫描时校验相对路径：必须以 `web/` 开头、canonical path 落在 `<pluginDir>/web/` 之下、
且文件存在。任一不满足则**置空并记 WARN**，当作没有前端入口——宁可这个插件在左栏显示
空面板，也不能让一个指到 `../../` 的入口把插件目录之外的文件服务出去。

### 8.2 静态服务与 CSP

`PluginWebController`（`GET /api/plugin-web/{id}/**`）把 `plugins/<id>/web/` 服务出来。

**不需要登录态**：这里只有插件包自带的静态资产，没有任何用户数据；而承载它的 iframe
是 opaque origin，本来也带不出任何凭据。要登录既没有安全收益，还会让 iframe 直接白屏。

四道守卫：

1. id 必须过 `^[a-z0-9][a-z0-9-]{1,49}$`；
2. 目标文件 canonical path 必须落在 `<pluginDir>/web/` 之下（同时挡 `../` 与符号链接）；
3. 只服务**已启用**插件——未安装 / 已禁用 / 被平台封禁一律 404（不是 403：不泄露
   「这个 id 存在但被禁用了」），与「禁用即不加载 JAR」同一口径；
4. 响应头：

| 头 | 值 |
|---|---|
| `Content-Security-Policy` | `default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'none'` |
| 同上，manifest 声明了 `network` 时 | 末段换成 `connect-src https:` |
| `X-Content-Type-Options` | `nosniff` |
| `Cache-Control` | `no-cache` |
| `Content-Type` | 按扩展名（html/js/css/json/svg/png/jpg/gif/webp/ico/woff/woff2/ttf/otf/txt/map），其余 `application/octet-stream` |

### 8.3 sandbox 与桥

宿主端在 `PluginPane.vue`：iframe 带 `sandbox="allow-scripts allow-forms"`，
**绝不含 `allow-same-origin`**。

> 这一条是整个模型的地基。iframe 一旦与应用同源，插件脚本就能读到 localStorage 里的
> `X-Session-Id` 并打全部 `/api/*`，等于把宿主的全部权限白送出去。没有它，iframe 是
> opaque origin，除了 postMessage 桥没有第二条路。

协议（宿主端、SDK、官网模板与宿主模拟器共用同一份契约，任何一方单独改动都会让插件跑不起来）：

```
握手  宿主 -> 插件   { awd: 1, type: "init", context: { pluginId, projectId, language, theme, themeTokens } }
请求  插件 -> 宿主   { awd: 1, type: "call", seq, method, params }
响应  宿主 -> 插件   { awd: 1, type: "result", seq, ok, result | error: { code, message } }
主题  宿主 -> 插件   { awd: 1, type: "theme", theme: "light"|"dark", tokens: { "--awd-*": "..." } }   (v2.6)
事件  宿主 -> 插件   { awd: 1, type: "event", event, data }   (v2.7，需先 events.subscribe，见 §8.8)
```

**主题通道（v2.6，宿主 0.27.4 起）**：照 VS Code 给 webview 注入 `--vscode-*` 变量的机制。
`init.context.themeTokens` 与 `theme` 推送里的 `tokens` 是同一张表——宿主当前生效主题的全部
语义色令牌（`--awd-bg` / `--awd-surface` / `--awd-text` / `--awd-border` / `--awd-accent` 等，
名单以宿主 `frontend/src/utils/appTheme.js` 的 `THEME_TOKEN_NAMES` 为准）。SDK 收到即自动：
`documentElement` 挂 `data-theme="light|dark"`、`body` 挂 `awd-theme-light`/`awd-theme-dark`
class、逐个令牌写成 iframe 内的 CSS 自定义属性。插件因此**写 CSS 就能跟随主题**：

```css
body { background: var(--awd-surface, #fff); color: var(--awd-text, #1a1a1a); }
```

（fallback 值是给老宿主准备的：老宿主的 `init` 只有 `theme` 字符串没有 `themeTokens`，
SDK 降级为只挂 `data-theme`/class，不注入变量；老宿主也不发 `theme` 推送，面板停在握手
快照。老 SDK 遇到新宿主的 `theme` 推送则静默忽略——两个方向都不会坏。）
需要脚本联动的用 `awd.theme.onChange(cb)`（见 §8.5）。

来源校验是双向的：宿主校验 `event.source === iframe.contentWindow`，插件校验
`event.source === window.parent`。两侧 `postMessage` 的 targetOrigin 都只能是 `'*'`——
opaque origin 使然，不能靠 `event.origin` 判断。

握手时机：宿主在 iframe `load` 之后立刻发 `init`。**SDK 必须用同步 `<script>` 引入且
排在业务脚本之前**，晚注册监听会错过握手，`ready()` 将永远挂起。

### 8.4 v1 方法表

| 方法 | 参数 | 返回 | 权限 |
|---|---|---|---|
| `context.get` | `{}` | context 对象本身（不包层） | — |
| `files.list` | `{}` | `{ files: [{ path, name, size }] }`，`path` 是项目内相对路径 | `file_read` |
| `files.read` | `{ path }` | `{ path, content, truncated }`，文本上限 5 MB，超限截断且 `truncated: true`（不报错） | `file_read` |
| `ui.toast` | `{ message }` | `{}` | — |
| `storage.get` | `{ key }` | `{ key, value }`，不存在时 `value: null` | — |
| `storage.set` | `{ key, value }` | `{}` | — |
| `evidence.link` | `{ anchor: { selection: true } \| { quote }, docPath?, targets: [{ path, locator?, relation?, method?, note? }] }` | `{ linkKey, targetIds: [] }` | `editor` |
| `evidence.list` | `{ docPath?, path?, sectionPath?, status? }` | `{ links: [{ linkKey, docPath, anchorText, sectionPath, status, targets: [{ targetId, path, locator, relation, method }] }] }` | `file_read` |
| `evidence.locate` | `{ linkKey, targetId? }` | `{}` | `editor` |

错误码：`permission_denied`（manifest 未声明所需权限，或读的不是可抽取文本的格式）、
`unknown_method`、`quota_exceeded`（插件级 KV 超 64 KB）、`not_found`（文件 / 链接 / 底稿位置不存在）、
`anchor_ambiguous`（`anchor.quote` 在文档里 0 或多处命中，或 anchor 形状不对）、
`no_selection`（`anchor.selection: true` 但编辑器当前没有选区）、
`no_active_document`（当前聚焦窗格没有打开的 Word 文档，或 `docPath` 不是它）。

**v2.5 新增方法**（宿主 0.27 起；老宿主对未知方法一律回 `unknown_method`，插件需按此降级）：

| 方法 | 参数 | 返回 | 权限 |
|---|---|---|---|
| `tools.invoke` | `{ name, args? }` | `{ output }`，`output` 是工具原始字符串输出（通常为 JSON，插件自行 `JSON.parse`） | 无独立 permission；工具名必须是本插件 manifest `tools` 里声明的，见 §8.7 |
| `chat.send` | `{ prompt }`，上限 4000 字 | `{}` | — |
| `ui.openFile` | `{ path }` | `{}` | `file_read` |

对应错误码：`invalid_params`（`tools.invoke` 缺 `name`，或 `chat.send` 的 `prompt` 为空）、
`invoke_failed`（`tools.invoke` 的目标工具未声明 / 执行出错）、
`quota_exceeded`（`chat.send` 的 `prompt` 超 4000 字）、
`not_found`（`ui.openFile` 的 `path` 在项目文件里找不到）。

`evidence.*`（EvidenceLink，P0）：

- 路径口径与 `files.*` 一致（项目内相对路径），宿主负责 path 与 fileId 的互换；
- `evidence.link` 只在**当前聚焦的 Word 文档**上建锚点：`{ selection: true }` 用当前选区，
  `{ quote }` 要求 `find_text_locations` **恰好 1 个**命中；锚点是命名书签（名 = `linkKey`，
  `EVID_<ULID>`），随后落库为 `createdByKind: 'plugin'` 的链接；`targets` 至少一条，
  `locator` 原样存为 JSON（页码 1 基、坐标 0..1）；
- `evidence.list` 不给 `docPath`/`path` 时查当前聚焦文档；`path` 是反查某份底稿被谁引用；
- `evidence.locate` 给 `targetId` 打开对应底稿（定位参数由工作台接手），不给则跳到文档里的书签。

插件级 KV 存在宿主的 `localStorage`，键为 `awd_plugin_kv_<pluginId>`，
每个插件总量上限 64 KB。

**v2.7 新增方法**（宿主 0.27.4 起；老宿主一律回 `unknown_method`，SDK 已内置降级）：

| 方法 | 参数 | 返回 | 权限 |
|---|---|---|---|
| `doc.exec` | `{ action, params? }` | `{ result }`，result 是原语的原始返回对象 | `editor` |
| `doc.active` | `{}` | `{ fileId, kind }`，kind ∈ writer/calc/impress；没有打开的文档时 `fileId: null` | `editor` |
| `events.subscribe` | `{ events: [名字…] }` | `{ subscribed: [当前生效集合] }` | 按事件（§8.8） |
| `events.unsubscribe` | `{ events: [名字…] }` | 同上 | — |
| `ai.request` | `{ prompt, system?, purpose? }` | `{ text, modelId }` | `ai` |

`doc.exec`（对位 VS Code TextDocument，生态路线 P1）：

- 目标恒为**当前聚焦窗格打开的文档**（与 `evidence.link` 同口径），没有 → `no_active_document`；
- `action`/`params` 与 AI 工具面的下发名**同一套**（doc_\*/sheet_\*/slide_\* 的安全子集）。
  白名单 = 宿主 SPI `PluginHostImpl.DOC_ACTIONS` 的同一份清单（§11.2 `Docs.exec` 行有全文）——
  JAR 与 Web 插件同一张能力面，不另造子集；前端镜像在 `frontend/src/config/pluginDocActions.js`，
  parity 测试 `doc-actions-parity.test.mjs` 对拍两份清单，漏一个就红。白名单外 → `action_not_allowed`；
- 写入自动带 `__agent` 标记：Writer 文档走修订、署名 "AI WorkDeck"，用户可逐条接受/拒绝；
  **Calc/Impress 没有修订机制，插件写入直接生效**——批量写入前插件应在自己的 UI 上先请用户确认；
- doc.exec 之后还有 executor 层 `EDITOR_ACTIONS` 第二道既有闸，插件绕不过。

`ai.request`（对位 `vscode.lm`，生态路线 P2）：

- 经平台 Credits 通道调**辅助模型**（便宜档，与自动打标签同一条），插件免带 Key；
  计费记在发起用户头上，日志带 pluginId 与 `purpose`（≤64 字符的用途自述）；
- `prompt + system` 合计 ≤ 16000 字符；每插件 **10 次/分钟**（`quota_exceeded`）；
- 服务端落点 `POST /api/plugins/{id}/ai/complete`（§8.7 同款安全闸：登录 → 插件启用未封禁 →
  manifest 声明 `ai` → 项目写权限 → 长度/频控 → 平台通道余额闸与记账全部继承既有链路）；
- v1 刻意不开模型选择与流式——放宽走 `x-` 实验通道（§12.4）验证后再转正；
- 分工：**要工具、要落文档、要让用户看见过程 → `chat.send`；面板内一次性静默推理 → `ai.request`**。

对应新错误码：`action_not_allowed`（doc.exec 原语不对插件开放）、`ai_failed`（模型调用失败）、
`experimental_not_allowed`（x- 实验方法仅对 dev 安装开放，§12.4）。

### 8.5 SDK 表面

源头在 [sdk/plugin-sdk/awd-plugin-sdk.js](../sdk/plugin-sdk/awd-plugin-sdk.js)；
官网插件模板 zip 里的那份是**逐字节一致的分发副本**。

```html
<script src="awd-plugin-sdk.js"></script>
<script>
  const ctx = await awd.ready();      // resolve 值即 awd.context
  const files = await awd.files.list();   // 糖衣：直接是数组
  const doc = await awd.files.read(files[0].path);  // 原始 result
  const n = await awd.storage.get('clicks');        // 糖衣：直接是值
  await awd.storage.set('clicks', (n || 0) + 1);
  await awd.ui.toast('你好');
</script>
```

`awd.call(method, params)` 原样调用任意 v1 方法并返回宿主的 `result`；
只有 `files.list()` 与 `storage.get()` 做了解包糖衣，`files.read()` 与 `call()` 返回原始 result。
`awd.evidence.link(params)` / `awd.evidence.list(params)` / `awd.evidence.locate(params)` 是
三个 `call` 的直通包装，同样返回原始 result。

**v2.5**：SDK 版本号 `1.0.0` → `1.1.0`（`awd.version`，与桥协议版本 `PROTOCOL` 无关），新增
`awd.tools.invoke(name, args)`（解包糖衣，直接返回 `output` 字符串）、`awd.chat.send(prompt)`、
`awd.ui.openFile(path)`，均为对应新方法的直通/糖衣包装。

**v2.6**：SDK 版本号 `1.1.0` → `1.2.0`。新增 `awd.theme.get()`（返回
`{ mode: 'light'|'dark', tokens }`）与 `awd.theme.onChange(cb)`（返回退订函数；
`cb(mode, tokens)` 在宿主推送主题切换时触发）。主题的自动应用（data-theme/class/CSS 变量）
不需要调用任何 API，SDK 收到消息即做。

**v2.7**：SDK 版本号 `1.2.0` → `1.3.0`。新增 `awd.doc.exec(action, params)`（解包糖衣，直接返回
原语结果）、`awd.doc.active()`、五个高频糖衣 `awd.doc.getText()/getSelection()/find(text)/
insertText(text)/addComment(anchorText, text)`、`awd.events.on(name, cb)`（返回退订函数，
自动向宿主 subscribe/unsubscribe；老宿主上照常返回退订函数、永不触发，插件不用写版本分支）、
`awd.ai.request(prompt, { system?, purpose? })`（解包糖衣，直接返回输出文本）。

### 8.6 开发工作流

官网插件模板 zip 带一份 `dev/host-simulator.html`——一个假扮宿主桥的静态页，
起个本地静态服务就能在浏览器里开发调试，不必装桌面端。模拟器的 sandbox 属性与桌面端
一致（同样没有 `allow-same-origin`）：调试时为了方便加上它，装进桌面端会立刻失败。

### 8.7 直调插件工具端点（v2.5）

`PluginController.invokeTool`：`POST /api/plugins/{id}/tools/{tool}`。设计意图：Web 面板做
结构化操作时直调自家 JAR 工具，绕过模型、不绕过任何安全闸——是桥 `tools.invoke` 方法
（见 §8.4）的服务端落点。

请求体：`{ projectId, args }`；鉴权走会话（`X-Session-Id`），不是插件自己的 postMessage 桥。

响应：`{ code: 0 | 1, output }`，`output` 是工具执行的原始字符串（`code: 1` 时装的是失败信息，
不是 HTTP 层错误）。

安全闸（自上而下，任一不过直接拒绝，不进 ToolRegistry）：

1. 登录会话有效（否则 401）；
2. 插件存在、已启用、未被平台封禁（否则 404，口径与 §8.2 静态服务一致——不泄露「存在但被禁」）；
3. `tool` 必须是该插件 manifest `tools` 里声明的名字（否则 404：不能借这条路调到别的插件或
   宿主内置工具）；
4. 调用者对 `projectId` 有**项目写权限**（`ProjectMemberService.hasWritePermission`，否则 403）；
5. 落到 `ToolRegistry.execute`：manifest `permissions` 校验（§3）、宿主 SPI 配额（§11.1）、
   `ToolContext` 的 `projectId`/`userId` 以服务端解析结果为准（不信任请求体里的用户身份）——
   与 AI 编排器调用同一插件工具时完全同一套闸，直调端点只是换了个触发源。

### 8.8 事件通道（v2.7）

对位 VS Code 的 `onDid*` 事件系统——此前插件感知不到宿主里发生的任何变化（尽调工作台
只能打开时拉一次）。显式订阅制：宿主只向订阅了的 iframe 推送，默认全静音。

```
订阅  插件 -> 宿主   events.subscribe   { events: [...] }  ->  { subscribed: [...] }
退订  插件 -> 宿主   events.unsubscribe { events: [...] }  ->  { subscribed: [...] }
推送  宿主 -> 插件   { awd: 1, type: "event", event, data }
```

首批三事件：

| 事件 | 订阅所需权限 | data | 触发时机 | 宿主侧节流 |
|---|---|---|---|---|
| `files.changed` | `file_read` | `{ projectId }` | 项目文件清单任何刷新（AI 建改文件、用户增删改、外部改动对账后） | 500ms 合并 |
| `selection.changed` | `editor` | `{ fileId }` | 编辑器光标/选区变化 | 300ms 合并 |
| `project.switched` | 无 | `{ projectId }` | 面板存活期间项目切换（当前架构下切项目会重建面板重新握手，此事件为未来面板持久化预留语义） | 无 |

- **payload 刻意为空/极小**：事件是「该重拉了」的信号，不是数据通道——选区内容、文件清单
  由插件经 `doc.exec get_selection` / `files.list` 按各自权限闸拉取，推送本身不成为权限旁路；
- 权限不足或未知的事件名在 subscribe 时**静默剔除**（不报错），以回声的 `subscribed` 集合为准；
  未知事件名的宽容是向前兼容：新事件名发给老宿主不炸；
- 换插件（面板复用换 src）时订阅集合清零，新插件必须自己重新订阅；
- 宿主侧事件源：`FileTree.loadFiles()` 成功后与 `LibreOfficeEditor` 的 selection 中继各发一个
  应用级 `uni.$emit`（`awd:files-changed` / `awd:selection-changed`），`PluginPane` 按订阅转发。

## 9. 插件依赖原生资源包（packs，v2.3）

```json
{ "id": "my-plugin", "packs": ["litviz-fonts"] }
```

- 每项必须过与插件 id 同一套正则，非法项在解析时丢弃并记 WARN——这串字符会被拼进
  注册表 URL 与磁盘路径。
- **在线安装该插件成功后**，`PluginMarketService` 对每个 packId 调
  `NativePackService.installAsync()`。
- **装不上不回滚插件，只记 WARN**：pack 是独立分发物，有自己的状态机、进度条与重试面
  （`/api/packs/{id}/status`）。一次网络抖动不该吃掉用户刚装好的插件。

用途见 [NATIVE_PACK_DISTRIBUTION.md](NATIVE_PACK_DISTRIBUTION.md) §11.4：三方插件要带重资源时
声明 pack 依赖，不把 registry 的 20 MB 受理线撑大。

## 10. 版本演进

- **v1（0.4.x）**：声明式 manifest + 启停持久化 + 插件广场展示。
- **v2（Phase 3A）**：ToolRegistry 按启停过滤三处消费点 + `tools[].permissions`
  分发前权限校验（诚实声明模型）+ 启停缓存 TTL。
- **v2.1（Phase 3B）**：manifest 新增 `skills` 字段，插件可携带 Skill（见 §7 与
  docs/SKILL_SPEC.md）。
- **v2.2**：加载期收口——禁用即不加载 JAR（§5）、`backendJars` 路径逃逸校验（§4）；
  在线分发落地：平台 Ed25519 签名 + 人工审核 + 客户端验签 + 远程封禁，见
  [docs/PLUGIN_DISTRIBUTION.md](PLUGIN_DISTRIBUTION.md)。
- **v2.3**：`frontendEntry` 从「预留」激活为 Web 插件形态（§8）——`web/` 静态服务、
  sandbox iframe、postMessage 桥、CSP；**permissions 在 Web 插件上第一次成为真实的执行边界**。
  manifest 新增 `packs` 字段（§9）。
- **v2.4**：JAR 插件的宿主 SPI——独立 Maven 工件 `com.checkba:plugin-api:1.0.0`
  （`backend/plugin-api/`，纯接口、无第三方依赖、版本独立于桌面端、只加不破），`HostAware` 注入（§4），
  `PluginHost` 八个子接口（§11），后台任务 `plugin_job` 表 + `/api/plugin-jobs` + SSE
  `client_action: plugin_job_progress`，每插件每分钟 60 次宿主调用配额。manifest 无新增字段。
- **v2.5**：Web 插件直调工具端点 `POST /api/plugins/{id}/tools/{tool}`（§8.7），
  服务端落点在 `PluginController.invokeTool`；桥/SDK 新增 `tools.invoke`/`chat.send`/`ui.openFile`
  三个方法（§8.4、§8.5），SDK 版本号 `1.0.0` → `1.1.0`。manifest 无新增字段。
- **v2.6**：主题通道（§8.3/§8.5）——`init.context.themeTokens`、宿主推送 `type:"theme"`、
  SDK 自动注入 CSS 变量与 `awd.theme.*`。SDK `1.1.0` → `1.2.0`。manifest 无新增字段。
- **v2.8（当前，宿主 0.28 起）**：生态路线 P3（dev-board#283）——evidence.retrieve.v1 升格公开
  Provider 协议：manifest 新增 `contributes.evidenceSources`（§13），plugin-api 1.1.0 → 1.2.0
  新增 `com.checkba.plugin.api.evidence` 包（EvidenceProvider / EvidenceQuery / EvidenceItem /
  EvidenceProviderConformanceKit）；宿主侧 EvidenceRetrieverRegistry 开放外部注册。
- **v2.7（宿主 0.27.4 起）**：生态路线 P0-P2 同批落地（dev-board#280/281/282）。
  P0 治理：manifest 新增 `minHostVersion`（§2）、实验 API `x-` 前缀机制与只加不改章程（§12）、
  管理页展示不兼容/封禁原因；P1 文档读写权：桥 `doc.exec`/`doc.active`（§8.4）+ 事件通道
  `events.*` 与 `type:"event"` 推送（§8.8）；P2 AI 调用权：桥 `ai.request` + 权限值 `ai`（§3）+
  端点 `POST /api/plugins/{id}/ai/complete`。SDK `1.2.0` → `1.3.0`。
- 规划中：进程外插件形态（MCP server）为不需要独立 UI 的插件提供真正的隔离边界。
  **进程内沙箱不在规划中**——Java 侧无此能力（§3），不要再把它列为待办；
  Web 插件那条路已经用「不同源 + 桥」拿到了同等效果，代价是能力必须逐个显式开放。

## 11. 宿主 SPI（`plugin-api`，v2.4）

工件 `com.checkba:plugin-api`（当前 **1.2.0**：1.1.0 加 `Evidence.linkAtQuote`，1.2.0 加
`evidence` 包——见 §13；源码 `backend/plugin-api/`，Java 21，无第三方依赖）。
不在远端仓库：本地 `mvn -q -f backend/plugin-api/pom.xml install` 后，插件以 `provided` 依赖它
（示例 `examples/hello-plugin/pom.xml`）。**只加不破**：已发布的方法签名与 record 字段不改不删，
新增能力走新方法 / 新 record 字段追加。

### 11.1 注入与调用上下文

```java
public interface HostAware { void setHost(PluginHost host); }
public interface PluginHost {
    String pluginId(); ToolCall call();
    Files files(); Text text(); Tags tags(); Evidence evidence();
    Jobs jobs(); Docs docs(); Settings settings(); Llm llm();
}
public record ToolCall(Long projectId, String conversationId, Long userId, String modelId) {}
```

- `call()` 是**服务端**的调用上下文（ThreadLocal）：工具分发期由 `ToolRegistry` 在分发插件工具前绑定、
  分发后清除；后台任务体内由宿主按 `Jobs.start` 时的快照重新绑定。模型传进工具参数里的 projectId /
  userId 一律不可信，以 `call()` 为准。
- 两种线程之外（插件自己起的线程、静态初始化）调用宿主 → `IllegalStateException`。
- **鉴权**：每个方法先校 `call().userId()` 对 projectId 的读/写权限（项目成员表），非成员抛
  `IllegalArgumentException`；fileId 必须属于该 projectId（IDOR 防护）。
- **配额**：每插件每分钟宿主调用上限（滑动窗口）——工具调用线程 60 次，后台任务线程（`JobContext` 绑定期间）1200 次，两者分开计数；超限抛 `HostQuotaException`。这是防 runaway，
  不是计费——`Llm` / `Text.ocr` 的钱按**用户 Credits** 在平台网关算，插件不自带 key。

### 11.2 方法表

| 子接口 | 方法 | 权限 | 宿主落点 / 备注 |
|---|---|---|---|
| `Files` | `list(projectId, parentId, recursive)` | 读 | 返回 `FileInfo{id,name,parentId,folder,fileType,size,path,sha256?,metaJson,updatedAt?}`（`updatedAt` 为 epoch millis、可 null，1.0.0 增量字段、永远是最后一个分量），`path` 从项目根起 |
| | `get(projectId, fileId)` / `open(projectId, fileId)` | 读 | `open` 返回整份字节的 InputStream |
| | `createFolderPath(projectId, segments)` | 写 | `ProjectFileService.ensureFolderPath`，逐级确保；某段是文件则抛 |
| | `write(projectId, parentId, name, bytes, ConflictPolicy)` | 写 | `RENAME` 同名自动 " (n)"，`FAIL` 同名抛；fileType 取扩展名 |
| | `move` / `rename` | 写 | 与前端文件树右键同一条服务路径（同名校验/环检测/物理搬迁都继承） |
| | `setMeta(projectId, fileId, patch)` | 写 | 浅合并进 `ProjectFile.metaJson`，值为 null 的键删除 |
| | `sha256(projectId, fileId)` | 读 | 算一次缓存到 `metaJson.sha256` + `sha256At`（= updatedAt），同版本不再读存储 |
| `Text` | `extract(projectId, fileId, maxChars)` | 读 | `DocumentTextService`（Tika / PDFBox） |
| | `ocr(projectId, fileId, OcrOptions)` | 读 | 图片直接、PDF 逐页渲染（150 dpi，上限 50 页）后走 `OcrService`（平台网关，扣 Credits）；`blocks=true` 时块粒度到页（网关不回坐标） |
| | `pdfPageTexts(projectId, fileId, from, to)` | 读 | PDFBox 分页文本，页码从 1 起闭区间，`to<=0` = 到末页 |
| `Tags` | `getOrCreate(projectId, name, type)` | 写 | `TagService.getOrCreateTag`：同名不同型复用不改型；type 空 = NORMAL |
| | `tagFile` / `tagsOf` | 写 / 读 | 标签必须属于同一项目 |
| `Evidence` | `create(...)` / `addTargets(...)` | 写 | `EvidenceLinkService`，`createdByKind=plugin`（状态 active） |
| | `listByDoc` / `listByFile` | 读 | 精简 `LinkView{id,linkKey,docFileId,anchorText,sectionPath,sectionTitle,status,targets[]}` |
| `Jobs` | `start(kind, title, JobBody)` | 写（有 projectId 时） | 每插件 2 并发、多余排队；任务体在发起用户的计费作用域里跑；`JobContext.progress/checkCancelled/call/result` |
| | `status(jobId)` / `cancel(jobId)` | 读（status）/ 写（cancel），job 有 projectId 时；无 projectId 只认本插件 | 只认本插件的任务（别的插件的当不存在）；取消 = 标记 + 中断线程，任务体要在 `checkCancelled` 处配合 |
| `Docs` | `exec(action, params)` | 会话 | `EditorBridgeService.executeEditorCommand`；无 conversationId 抛 `IllegalStateException("no active conversation")`。action 白名单（`PluginHostImpl.DOC_ACTIONS`）**= AI 工具已暴露的 doc_\*/sheet_\*/slide_\* 下发名 ∪ EvidenceLink 书签原语**，完整清单：writer `insert_at_cursor replace_selection find_replace get_selection find_text_locations replace_nth_match delete_match delete_text get_paragraph modify_paragraph get_outline goto set_selection replace_at_position clear_anchors get_document_text get_cursor_context get_clauses select_paragraph collapse_selection delete_selection format_selection set_paragraph_format undo redo insert_paragraph insert_table insert_break insert_image insert_under_heading format_table get_formatting set_style set_numbering edit_header_footer apply_house_style add_comment list_comments reply_comment set_comment_resolved delete_comment list_revisions resolve_revision resolve_all_revisions set_hyperlink_at_anchor insert_footnote insert_endnote table_read table_set_cell table_add_row table_delete_row table_add_col table_delete_col set_style_profile apply_style_profile insert_toc set_page_setup`；EvidenceLink 书签/链接原语 `bookmark_selection get_bookmark_context goto_bookmark check_link_anchors get_selection_hyperlink set_selection_hyperlink insert_link_with_bookmark`（尽调插件建锚点必需，2026-08-22 复核裁决保留；`insert_link_with_bookmark` 的 url 只放行 `http(s)://` 与 `checkba://`，其它 scheme 双字段拒绝）；calc `sheet_get_overview sheet_read_range sheet_write_cells sheet_format_cells sheet_set_borders sheet_merge_cells sheet_set_row_col sheet_edit_rows_cols sheet_manage_sheets sheet_search sheet_select_range sheet_sort_range sheet_set_autofilter sheet_freeze_panes sheet_conditional_format sheet_set_data_validation sheet_define_name sheet_group_rows_cols sheet_protect_sheet sheet_add_chart sheet_add_pivot_table sheet_add_comment sheet_get_comments sheet_delete_comment`；impress `slide_get_overview slide_get_page slide_goto slide_add_page slide_delete_page slide_move_page slide_add_text_box slide_add_shape slide_add_table slide_delete_shape slide_format_shape slide_format_text slide_read_notes slide_write_notes slide_set_layout slide_set_shape_text slide_replace_text slide_set_shape_geometry slide_set_hyperlink slide_table_read slide_table_set_cell slide_table_set_style`。宿主自用（`load_document` / `export_document` / `doc_open_file_sync` / `set_zoom` …）与诊断原语（`debug_revisions`）不开放；`PluginHostImplTest` 扫 `DocumentEditTools` / `SlideEditTools` 源码里的下发名字面量，漏一个就红 |
| | `refreshFiles()` / `openFile(fileId, locator)` | 会话 | 刷新文件树 / 打开文件（locator 非空时追发 `client_action: plugin_open_locator {fileId, locator}`，工作台 `agentClientActions.js` 接到后走 `openFileLinkTarget` 打开并定位，与审阅面板「查看底稿」同一条路）；配额只计一次 |
| `Settings` | `get(key)` / `set(key, value)` | - | `system_setting`，键自动加前缀 `plugin.<id>.` |
| | `projectStyleProfileJson(projectId)` | 读 | 委托 `StyleProfileResolver`：项目 `_模板/画像.json` > `dd.styleProfile.default` > house-default，选中的画像 merge 到 house-default 之上后返回完整 JSON（与 `write_docx` / `doc_open_file` 同一份），永不为 null |
| `Llm` | `complete(system, user, LlmOptions)` | - | 平台通道；`modelId` 空 = 辅助模型（便宜档，与自动打标签同一条）；token 记账带 pluginId 日志 |

### 11.3 后台任务的外部面

- 表 `plugin_job`：`id`（26 位 ULID）、`plugin_id`、`kind`、`title`、`status`（queued / running / done /
  failed / cancelled）、`done` / `total` / `message`、`result_json`、`error`、`project_id`、`user_id`、
  `conversation_id`、时间戳。进度写库按 500ms 节流，内存态才是实时值；终态必落库；宿主重启时
  库里还在 queued / running 的统一标 failed（宿主重启）。
- REST：`GET /api/plugin-jobs?projectId=`、`GET /api/plugin-jobs/{id}`（登录 + 项目成员）、
  `POST /api/plugin-jobs/{id}/cancel`（写权限）。
- SSE：有 conversationId 时每次落库同时发 `client_action` `{action:"plugin_job_progress", jobId, pluginId,
  kind, title, status, done, total, message, error, conversationId}`；前端并入
  `BackgroundTaskIndicator`（类型 `PLUGIN_JOB`），挂载时按项目补拉在跑的。

### 11.4 示例

`examples/hello-plugin` 的 `helloListFiles`：实现 `HostAware`，经 `host.files().list(projectId, null, false)`
列项目根目录；manifest 为它声明 `file_read`。

## 12. 演进章程（v2.7 起，只加不改）

生态路线（docs/PLUGIN_API_ROADMAP.md）P0 的落地条款。VS Code 十几年不破坏兼容靠的是
流程不是天赋——本章是那套流程的本仓版本，**对规范本身的修改也受本章约束**。

### 12.1 只加不改

已发布即冻结：桥方法名/参数/返回字段/错误码、manifest 字段、SPI 方法签名与 record 字段，
发布后不改语义、不删除。扩展一律新增——新方法、新可选参数、record 末位追加字段
（`FileInfo.updatedAt` 先例）。

### 12.2 破坏性变更 = 新名字

语义变了就换名字、双轨共存（`editor_command`/`wps_command`、`evidence.retrieve.v1`→v2 先例）；
旧名至少保留一个发行周期，并在 §10 版本史标注摘除计划。

### 12.3 版本声明与降级

- 宿主每次扩桥：PLUGIN_SPEC 升小版本 + SDK 升版本号（两者独立，见 §8.5）；
- 插件用新能力：manifest 声明 `minHostVersion`（§2），官网模板默认生成；
- 老宿主对新方法回 `unknown_method` 是**契约**而非缺陷，SDK 的既定降级行为
  （events 静默、其余如实抛错）插件可以依赖。

### 12.4 实验 API（x- 前缀）

- 未定稿的桥方法一律带 `x-` 前缀（如 `x-ai.requestStream`）；`x-` 方法**可改可删**，不承诺兼容；
- **运行时闸**：宿主只对本机 dev 免签直装（`.awd-dev` 标记）的插件放行 `x-` 方法，
  其余一律 `experimental_not_allowed`——广场装的插件物理上调不到实验方法；
- **受理规则**：广场拒收调用 `x-` 方法的投稿（审核清单项；自动扫描是后续增强）；
- 转正流程（照抄 VS Code finalization）：有真实插件用过 + 有能跑的示例 + 形状复核
  （过窄/过宽三问）→ 去前缀进 §8.4 正式方法表 + SDK 出正式包装。

### 12.5 桥变更四处同步纪律

桥协议（§8.3-§8.8）任何变更，同一个 PR 里必须改齐：

1. 宿主端 `frontend/src/components/PluginPane.vue`；
2. SDK 源头 `sdk/plugin-sdk/awd-plugin-sdk.js` 及两份仓内逐字节副本
   （`examples/hello-web-plugin/web/`、`backend/src/main/resources/plugin-dev/`，
   parity 测试守着：前端 `sdk-parity.test.mjs`、后端 `PluginDevSdkParityTest`）；
3. 官网仓 `lib/plugin-template.ts`（SDK 内联副本 + `WEB_SDK_METHODS` + `dev/host-simulator.html`
   模拟器实现，**无自动对拍**，靠同批 PR 纪律——2026-08-29 实测漂移过一处注释，别再犯）；
4. `backend/skills/plugin-dev/prompt.md`（AI 写插件的权威 spec，不同步它 AI 就按旧契约写插件）。

外加：本规范升版（§10 记条目）；涉及 doc.exec 白名单时同步 `PluginHostImpl.DOC_ACTIONS`
与 `frontend/src/config/pluginDocActions.js`（`doc-actions-parity.test.mjs` 守着）。

### 12.6 官方吃狗粮

新官方面板能力先问「插件 API 能不能做」；做不了，优先补 API 而不是走私有通道。
不留私有超级 API，第三方才相信「官方能做的我也能做」。

## 13. 证据来源贡献点（contributes.evidenceSources，v2.8）

evidence.retrieve.v1（[docs/EVIDENCE_CONTRACT.md](EVIDENCE_CONTRACT.md)）升格为公开 Provider
协议：第三方按契约接新数据源（工商/裁判文书/财务库/行业库），宿主统一检索与展示——
数据源接一次，所有依据/尽调/核查场景全能用。设计定稿
`docs/superpowers/specs/2026-08-29-plugin-p3-evidence-provider-protocol.md`。

### 13.1 两条接入通道

**通道 A：JAR SPI**（plugin-api 1.2.0，包 `com.checkba.plugin.api.evidence`）

```java
public class MyRegistryProvider implements EvidenceProvider {
    public String sourceId() { return "my-plugin.company-registry"; }
    public List<EvidenceItem> retrieve(EvidenceQuery query) { ... }
}
```

- 宿主扫描 JAR 时自动实例化（无参构造 + `HostAware` 注入照旧）并注册进证据注册表；
- **双校验**：`sourceId()` 必须是 `<pluginId>.<name>`，且与 manifest 声明逐字一致——
  任一不满足拒绝注册并记 ERROR（声明是审查对象，必须真实）；
- 宿主侧每次调用 10 秒超时；异常/超时/返回 null 一律空列表降级，不炸编排主流程；
- 三必填（evidenceId/sourceUri/locator）由 `EvidenceItem` 构造器强制——缺定位符即丢弃、
  不得编造是契约红线，公开 record 与宿主内部各拦一道。

**通道 B：远程 MCP 声明**（零 Java 代码）

```json
"contributes": {
  "evidenceSources": [
    { "sourceId": "my-plugin.caselaw", "name": "判例库", "transport": "mcp",
      "server": { "url": "https://mcp.example.com/sse", "tokenSettingKey": "plugin.my-plugin.mcpToken" },
      "tool": "retrieve_evidence" }
  ]
}
```

- 只收 `http(s)` 的 `server.url`（streamable-http 传输）；远端工具收契约 §2 的
  snake_case 请求字段，返回 `{"items":[EvidenceItem...]}`，缺三必填的条目适配层丢弃并告警；
- **本地命令型（子进程）MCP 不受理**：那是 JAR 档的风险量级，等进程外插件形态立项后
  按其安全模型开放；声明了 `transport: "mcp"` 但没给合法 url 的条目解析时丢弃并 WARN；
- token 建议走 `tokenSettingKey`（`plugin.<id>.` 前缀的系统设置，可在线改），
  不要把密钥写死进 manifest。

### 13.2 声明字段

`contributes.evidenceSources[]`：`sourceId`（必填，`<pluginId>.<name>`，名段
`[A-Za-z0-9][A-Za-z0-9_-]*`）、`name`/`description`（广场展示与审查）、`transport`
（`spi` 缺省 / `mcp`）、`server{url, token?, tokenSettingKey?, timeoutSeconds?}` 与
`tool`（仅 mcp）。非法条目解析时丢弃并 WARN。

### 13.3 治理规则

| 规则 | 内容 |
|---|---|
| 冲突 | 同 sourceId 重复注册先到先得，后来的拒绝并记 ERROR |
| 启停 | 插件禁用/版本不兼容 → 其来源静默返回空列表（适配器自带启用位闸），重新启用即恢复 |
| rescan | 插件来源整批清空重建；内置来源（memory / ai.evidence.mcp-sources 配置的）不动 |
| dev 直装 | **不收** evidenceSources（数据源接入需广场人工审核；dev 校验直接报错） |
| conformance | `EvidenceProviderConformanceKit.run(provider, query)`（零依赖，任何测试框架可调）返回空列表 = 通过；广场受理要求附全绿运行记录 |
| 红线 | 自动爬取类 provider 不受理（验证码/合规风险，2026-08-21 拍板口径不变） |
| 版本 | 协议就是 evidence.retrieve.v1：字段只增不改，破坏性变更升 v2 双轨共存 |

示例插件：[examples/hello-evidence-plugin/](../examples/hello-evidence-plugin/)（SPI 实现 +
conformance 自测全绿）。宿主落点：`PluginService.registerEvidenceProvider` /
`registerDeclaredMcpEvidenceSources`、`EvidenceRetrieverRegistry.registerExternal`、
适配器 `PluginSpiEvidenceRetriever`。
