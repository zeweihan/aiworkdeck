# 插件规范 v2.3（Plugin Spec v2.3）

> 适用版本：v1 自 0.4.x；v2（权限执行 + 启停过滤）自 Phase 3A；v2.1（插件携带 Skill）自 Phase 3B；
> v2.3（Web 插件 + `packs` 依赖）自 native pack Phase B。
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
| `backendJars` | string[] | 否 | 相对插件目录的 JAR 文件名列表，启动/重扫时加载其中带 `@Tool` 注解的类。 |
| `skills` | string[] | 否 | **v2.1 新增**：插件携带的 Skill 子目录名列表（相对插件目录），见 §7。 |
| `packs` | string[] | 否 | **v2.3 新增**：依赖的原生资源包 id 列表，见 §9 与 [NATIVE_PACK_DISTRIBUTION.md](NATIVE_PACK_DISTRIBUTION.md)。 |

未知字段被忽略（向前兼容）；`permissions` 中出现 v1 未定义的值仅记录 WARN，不拒绝加载。

## 3. permissions 自述与一致性校验（v2）

| 值 | 含义 |
|---|---|
| `file_read` | 读取项目文件内容 |
| `file_write` | 创建 / 修改 / 删除项目文件 |
| `network` | 访问外部网络（HTTP 等出站请求） |
| `editor` | 操作文档编辑器（LOWA/LibreOffice 相关原语） |

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
握手  宿主 -> 插件   { awd: 1, type: "init", context: { pluginId, projectId, language, theme } }
请求  插件 -> 宿主   { awd: 1, type: "call", seq, method, params }
响应  宿主 -> 插件   { awd: 1, type: "result", seq, ok, result | error: { code, message } }
```

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

### 8.6 开发工作流

官网插件模板 zip 带一份 `dev/host-simulator.html`——一个假扮宿主桥的静态页，
起个本地静态服务就能在浏览器里开发调试，不必装桌面端。模拟器的 sandbox 属性与桌面端
一致（同样没有 `allow-same-origin`）：调试时为了方便加上它，装进桌面端会立刻失败。

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
- **v2.3（当前）**：`frontendEntry` 从「预留」激活为 Web 插件形态（§8）——`web/` 静态服务、
  sandbox iframe、postMessage 桥、CSP；**permissions 在 Web 插件上第一次成为真实的执行边界**。
  manifest 新增 `packs` 字段（§9）。
- 规划中：进程外插件形态（MCP server）为不需要独立 UI 的插件提供真正的隔离边界。
  **进程内沙箱不在规划中**——Java 侧无此能力（§3），不要再把它列为待办；
  Web 插件那条路已经用「不同源 + 桥」拿到了同等效果，代价是能力必须逐个显式开放。
