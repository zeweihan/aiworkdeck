# 插件在线分发规范（提交 · 审核 · 签名 · 封禁）

> 适用范围：**JAR 插件**的在线分发。Skill 的分发见 [SKILL_SPEC.md](SKILL_SPEC.md) §8，
> 它是纯文本、风险量级完全不同，走的是无审核的直接发布流程，两者不要混用。

## 0. 为什么需要这一套

插件 JAR 与宿主同一个 JVM、同一份权限（原因见 [PLUGIN_SPEC.md](PLUGIN_SPEC.md) §3
「真实的信任边界在哪里」）。Java 侧没有进程内沙箱可用，所以**运行时拦不住任何东西**，
安全只能前移到分发链路。

这与 VS Code / JetBrains 的选择一致——两者都明确不做运行时沙箱，靠的是签名、
市场审核与封禁。JetBrains 2026-06 的恶意 AI 插件事件（伪装正常功能、等用户填入
AI provider 的 API key 后窃取、装 JVM 全局 TrustManager 关掉 TLS 校验、明文外传）
说明这套机制并非万无一失，但它是目前唯一可行的路径。

我们的用户是律师，本机存有客户机密与案件材料，应用内保存着 AI 供应商密钥——
风险敞口比通用 IDE 更大，因此审核环节**不设自动通过**。

## 1. 三条分发渠道

| 渠道 | 形态 | 审核 | 信任前提 |
|---|---|---|---|
| 内置 | 随应用发版的 JAR | 我们自己的发布流程 | 等同应用自身 |
| 本地 | 用户手动放入 `plugins/` | 无 | 用户自担，等同安装一个本机应用 |
| **在线广场** | 经审核签名的 JAR | **人工审核 + 自动扫描** | 平台背书 + 可远程封禁 |

本文只规范第三条。

## 2. 状态机

```
                 ┌──────────► rejected（记原因，作者可修改后重新提交）
                 │
提交 ──► pending ─┴──► approved ──► 平台签名 ──► 上架 registry
                                        │
                                        └──► revoked（封禁，客户端自动禁用）
```

- `pending`：已提交待审，**不出现在 registry**。
- `approved`：审核通过并已签名，出现在 registry 供安装。
- `rejected`：驳回，附 `reviewNote`；作者改完重新提交生成新版本。
- `revoked`：曾上架但事后发现问题，进入封禁列表；客户端拉到后自动禁用已安装的该插件。

**没有自动通过的路径。** 自动扫描只产出报告供审核人参考，不改变状态。

## 3. 提交包格式

作者上传一个 zip，解开后必须是**单个插件目录的内容**（不是外面再套一层目录）：

```
（zip 根）
├── manifest.json          必需，规范见 PLUGIN_SPEC.md §2
├── <name>.jar             manifest.backendJars 声明的 JAR
└── <skill-dir>/           可选，manifest.skills 声明的 Skill 目录
    ├── skill.yml
    └── prompt.md
```

服务端受理时的硬性检查（任一不过直接拒收，不进审核队列）：

| 检查 | 约束 |
|---|---|
| 包体积 | ≤ 20 MB |
| 条目数 | ≤ 2000 |
| 单文件解压后体积 | ≤ 50 MB（zip bomb 防护） |
| 路径 | 不得含 `..`、绝对路径、符号链接（zip slip 防护） |
| manifest | 存在、可解析、`id` 匹配 `^[a-z0-9][a-z0-9-]{1,49}$`、`version` 为语义化版本 |
| id 归属 | 新 id 归提交者；已存在的 id 只有原作者能提交新版本 |
| version | 必须严格大于该 id 已有的最高版本 |
| backendJars | 声明的每个文件都必须在包内且落在包根之下 |

## 4. 自动扫描（审核辅助，不做判定）

对包内每个 `.class` 文件做常量池字符串匹配。Java class 的常量池以明文存放引用的类名与
方法名，因此无需反编译即可发现引用关系。命中项**不代表恶意**，只标记需要人工重点核对的位置。

| 类别 | 匹配特征 | 关注原因 |
|---|---|---|
| 进程执行 | `java/lang/Runtime`、`ProcessBuilder` | 可执行任意本机命令 |
| TLS 篡改 | `javax/net/ssl/X509TrustManager`、`SSLContext.init`、`setDefaultSSLSocketFactory` | JetBrains 事件的关键手法：关掉证书校验后明文外传 |
| 网络 | `java/net/Socket`、`HttpURLConnection`、`java/net/http` | 与 `permissions` 是否声明 `network` 交叉验证 |
| 文件 | `java/io/File`、`java/nio/file/Files` | 与 `file_read` / `file_write` 交叉验证 |
| 反射突破 | `setAccessible`、`sun/misc/Unsafe`、`MethodHandles` | 绕过封装访问宿主内部状态 |
| 宿主内部 | `com/checkba/`、`ApplicationContext`、`DataSource` | 直接摸 Spring 容器与数据库 |
| 硬编码出口 | 形如 IPv4 字面量、`http://` 明文 URL | 固定 C2 地址的典型特征 |

**交叉验证是这一步最有价值的产出**：`permissions` 自述与实际引用不符（例如未声明
`network` 却引用了 `Socket`）应当直接驳回。这让 manifest 里的权限声明第一次具备实际
约束力——它不再只是作者的自说自话，而是审核时的对照基准。

## 5. 签名

**算法**：Ed25519（Node `crypto` 与 JDK 15+ 均原生支持，无需第三方库与证书链）。

**密钥**：平台持有一对密钥。私钥仅存在于官网服务器环境变量 `AWD_PLUGIN_SIGNING_KEY`
（PKCS#8 PEM），公钥硬编码在桌面端 `ai.plugins.registry-public-key` 配置默认值中。
私钥泄露的处置方式是换密钥 + 客户端随版本更新公钥 + 全量重签。

**签名对象**：审核通过时对下面这个 canonical JSON（键按字典序、无多余空白）签名：

```json
{"files":{"manifest.json":"<sha256>","tool.jar":"<sha256>"},"id":"my-plugin","publishedAt":"2026-07-27T00:00:00Z","version":"1.0.0"}
```

签名覆盖**每个文件的 SHA-256**，而不只是 JAR——manifest 同样关键（它决定加载哪些 JAR、
声明什么权限）。客户端安装时逐文件比对哈希，任一不符即中止。

## 6. registry 接口契约

桌面端 `PluginMarketService` 消费。字段勿随意改名/删除。

### `GET /api/registry/plugins`

返回 `approved` 状态的插件元数据数组（不含二进制）：

```jsonc
[{
  "id": "dd-workbench",
  "name": "尽调工作台",
  "description": "...",
  "version": "2.1.0",
  "author": "legal-ops",            // 提交者 username
  "authorDisplayName": "法务运营组",
  "permissions": ["file_read"],      // manifest 自述，客户端安装前展示
  "tools": [{"name": "dd_checklist", "description": "生成尽调清单"}],
  "size": 1048576,                   // 包总字节数
  "downloads": 0,
  "publishedAt": "2026-07-27T00:00:00Z",
  "homepage": "https://www.aiworkdeck.com/zh/plugins/dd-workbench"
}]
```

### `GET /api/registry/plugins/{id}/bundle`

返回安装所需的清单与签名，**不含二进制**：

```jsonc
{
  "id": "dd-workbench",
  "version": "2.1.0",
  "publishedAt": "2026-07-27T00:00:00Z",
  "files": { "manifest.json": "<sha256>", "tool.jar": "<sha256>" },
  "signature": "<base64 Ed25519 签名>"
}
```

### `GET /api/registry/plugins/{id}/file?path=<相对路径>`

按 `files` 中的键逐个下载文件二进制。`path` 必须是 `files` 里出现过的键，否则 404
（避免变成任意文件读取接口）。

### `GET /api/registry/plugins/revoked`

封禁列表，客户端启动时与每 24 小时拉取一次：

```jsonc
[{"id": "bad-plugin", "version": "1.0.0", "reason": "窃取凭据", "revokedAt": "..."}]
```

`version` 为 `"*"` 时表示该 id 的所有版本均被封禁。

## 7. 客户端安装流程

1. 拉 `/bundle`，用内置公钥验签——**失败即中止**，不落任何文件；
2. 逐个下载 `files` 中的文件到临时目录，边下边算 SHA-256，与清单比对；
3. 全部匹配后，原子移动到 `plugins/<id>/`（先写临时目录再 rename，避免半成品被扫描到）；
4. 弹出信任确认，展示：插件名、作者、版本、**权限自述**、工具清单，以及一句
   「安装插件等同于在本机安装一个应用程序，它能读写你的文件并访问网络」；
5. 用户确认后 `rescan()` 生效；未确认则删除已落盘内容。

**默认安装后处于禁用状态**，需用户在已安装列表手动启用——配合 PLUGIN_SPEC §5 的
「禁用即不加载」，这意味着用户点确认之前，插件代码一行都不会执行。

## 8. 封禁生效

客户端拉到封禁列表后，对命中的已安装插件：写入禁用名单 → 记录审计日志 →
在插件广场标红提示「该插件已被平台下架：<原因>，建议卸载」。

不自动删除文件——留给用户处置，避免误封时数据丢失。但**禁用是强制的**，
用户无法在广场里重新启用被封禁的插件。

## 9. 分期

- **Phase 1**：官网提交 + 人工审核 + 自动扫描报告 + 签名 + registry 四个接口。（已落地）
- **Phase 2**：桌面端安装（验签 + 逐文件哈希校验 + 信任确认）+ 封禁拉取与强制禁用。（已落地）
- **Phase 2.5（2026-08 立项）**：第四种分发形态「原生资源包（native pack）」——
  重资源（脚本运行时 / 平台二进制 / 静态资产）不随安装包、不进 registry 应用层，
  走签名 manifest + 静态镜像的运行时下载；含三方 pack 提交审核与三方 Web 插件 SDK。
  规范整体在 [NATIVE_PACK_DISTRIBUTION.md](NATIVE_PACK_DISTRIBUTION.md)，本文的
  状态机 / 签名密钥 / 封禁语义被它沿用。
- **Phase 3**（未排期）：进程外插件形态（MCP server），为不需要独立 UI 的插件提供
  真正的隔离边界；届时在线渠道优先推荐该形态，JAR 保留给必须有自己界面的插件。
