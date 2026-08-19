# 原生资源包分发规范（Native Pack：格式 · 托管 · 安装 · 卸载）

> 状态：**已拍板定稿（2026-08-19）**。四项决议见 §13：签名沿用插件 registry 密钥对；
> 升级资源缺口自动补下载；Phase A 与 Phase B 同轮实施；**v1 即开放第三方提交 pack**（§8）。
> 适用范围：第四种分发形态「原生资源包（native pack）」——重资源功能的运行时下载分发。
> 首个落地对象：诉讼可视化（litviz + graphviz + draw.io，约 45–65 MB）。
> 相邻规范：[PLUGIN_SPEC.md](PLUGIN_SPEC.md)（JAR 插件）、[PLUGIN_DISTRIBUTION.md](PLUGIN_DISTRIBUTION.md)
> （JAR 在线分发）、[SKILL_SPEC.md](SKILL_SPEC.md)（Skill）。

## 0. 为什么需要第四种形态

现有三种插件形态（内置面板型 / Skill 型 / 动态 JAR）没有一种能承载「重资源」：

- Skill 广场只收 `skill.yml` + `prompt.md` 两个文本文件（`BUNDLE_FILES` 白名单）；
- JAR 插件广场只装后端 Java 字节码，官网受理上限 20 MB，且下载端点
  （`GET /api/registry/plugins/{id}/file`）是 Next.js 应用层 `readFileSync` 整读进内存——
  生产机 1.8 GB 内存跑四个站点，几十 MB 级的包走这条路是 OOM 风险，也没有断点续传；
- 内置面板型只能随安装包走。

于是 2026-08-19（PR#433）诉讼可视化、脱敏做的「广场安装」只是**体验对齐**：
`enabled_by_default:false` + `requiresSkill` 门控，点「安装」= 翻启用位，
字节早已全部随安装包分发。代价直接压在安装包上：

| 资源 | mac | win | 消费方 |
|---|---|---|---|
| draw.io 裁剪静态资源 | ~40 MB | ~40 MB | Electron `drawio-server.js`（静态服务） |
| graphviz 平台二进制 | 4.3 MB | 19.7 MB | litviz `cli.py` 子进程（仅流程图布局） |
| litviz Python 引擎 | 1.1 MB | 1.1 MB | 后端 `LitigationVisualService` 子进程 |

本规范定义的 native pack 让这类资源**从插件广场真下载安装**：
安装包瘦身约 45 MB（mac）/ 60 MB（win），用户体验与今天完全一致
（广场点「安装」→（多一步：下载进度）→ 左栏出现入口）。

### 什么资源该走 pack（判据）

**进程外消费的重资源**：子进程运行时（Python 脚本引擎）、平台原生二进制（graphviz）、
静态资产（draw.io）。共同点是宿主代码通过**路径**消费它们，不进 JVM。

**什么不走**：宿主 Java 代码一律随包。脱敏（`SensitiveService`）编译在 backend
app.jar 里、纯 Java、无外部重资源，把它拆出来是后端模块化工程而不是分发问题，
收益为零——脱敏维持现状（随包 + skill 门控启停）。

### 与 PLUGIN_DISTRIBUTION.md §9 分期表的关系

原 Phase 3「进程外插件形态（MCP server）」解决的是**隔离执行**（不可信代码不进宿主
JVM），本规范解决的是**资源分发**（可信资源不进安装包），相邻但不同问题，互不取代。
落地时在 §9 表中为 native pack 加行、指向本文，MCP 形态保留原位（仍未排期）。

## 1. 概念与信任模型

**原生资源包（native pack）= 内置功能的可拆卸资源载荷。**
它不含进 JVM 的字节码（区别于 JAR 插件）、不含 prompt（区别于 Skill）；
消费它的代码（面板组件、后端 service、@Tool）仍然内置在应用里。

| 形态 | 载荷 | 进 JVM | 分发 | 审核 |
|---|---|---|---|---|
| 内置面板型 | 前端组件 + 宿主 Java | 是（随应用） | 随安装包 | 即应用发布流程 |
| Skill | 纯文本 prompt 包 | 否 | 官网 registry，登录即发布 | 无 |
| 动态 JAR | Java 字节码 | **是** | 官网审核 + 签名 + registry | 人工，无自动通过 |
| **native pack** | 脚本运行时 / 原生二进制 / 静态资产 | 否 | 静态镜像 + 签名 manifest | 第一方直发；**三方人工审核 + 签名**（§8） |

**信任等级与 JAR 插件相同**：pack 里有可执行内容（Python 脚本会被解释执行、
graphviz 是原生二进制会被 spawn），一旦被替换等同任意代码执行。因此：

- Ed25519 签名沿用**现有插件 registry 密钥对**（私钥 = 官网 env
  `AWD_PLUGIN_SIGNING_KEY`，公钥 = 桌面端 `ai.plugins.registry-public-key`，
  默认留空即拒绝一切 pack 安装）——pack 与 JAR 插件同属「广场分发的可执行内容」
  这一信任层级，共用一对密钥，轮换处置同一套（见 [[plugin-distribution-security]]）
  【已拍板】；
- 第三方提交 pack **v1 即开放**【已拍板】，但审核比 JAR 更重：平台二进制没有
  class 常量池那样的可扫描面，自动扫描只能给出条目级线索（§8.2），人工审核
  没有任何自动通过路径，且发布节奏完全由平台控制（审核通过 ≠ 立即上架，
  上架动作是平台侧的发布脚本）。

与 JAR 插件的一个刻意差异：pack 的 manifest 是**托管的静态文件**，签名直接盖在
manifest 原始字节上（旁挂 `.sig`，与增量更新的 `manifest.json.sig` 同型），
不走 canonical JSON 重建——没有「两端各自组装再对拍」的问题，从根上避开跨语言
键序坑。`PluginMarketService` 的 canonical JSON 流程不动。

## 2. pack manifest 格式

每个 pack 一份 `manifest.json`（UTF-8，无 BOM）+ 同目录 `manifest.json.sig`
（对 manifest 原始字节的 Ed25519 签名，base64）：

```jsonc
{
  "schema": 1,
  "id": "litigation-visual",          // ^[a-z0-9][a-z0-9-]{1,49}$，与 skill id 同规则
  "version": "1.0.0",                  // 语义化版本，独立于应用版本号
  "publishedAt": "2026-08-20T00:00:00Z",
  "minAppVersion": "0.21.0",           // 低于此版本的应用拒绝安装（资源↔宿主契约由应用侧保证）
  "engineApi": 1,                      // 资源与宿主的机器契约版本（litviz 即 cli.py 契约），宿主不认识则拒装
  "components": [
    {
      "name": "litviz",
      "platforms": ["*"],              // 平台无关
      "archive": "litviz-1.0.0.tar.gz",
      "size": 1153433,                 // 压缩包字节数（进度条与续传校验用）
      "sha256": "…",                   // 压缩包哈希（签名经 manifest 覆盖到它）
      "unpackDir": "litviz"            // 解压到 <packRoot>/<version>/litviz/
    },
    { "name": "graphviz", "platforms": ["mac-arm64"], "archive": "graphviz-1.0.0-mac-arm64.tar.gz",  "size": 0, "sha256": "…", "unpackDir": "graphviz" },
    { "name": "graphviz", "platforms": ["win-x64"],   "archive": "graphviz-1.0.0-win-x64.tar.gz",    "size": 0, "sha256": "…", "unpackDir": "graphviz" },
    { "name": "drawio",   "platforms": ["*"],         "archive": "drawio-1.0.0.tar.gz",              "size": 0, "sha256": "…", "unpackDir": "drawio" }
  ]
}
```

要点：

- **平台/架构矩阵**：`platforms` 取值 `*`、`mac-arm64`、`win-x64`（与
  `desktop/bundled/${os}-${arch}` 的命名一致；当前就这两个发行目标）。客户端按
  自身平台过滤 components，取「匹配平台 ∪ `*`」；某平台缺必需组件时如实降级
  （graphviz 的降级语义沿用现状：缺了只报流程图布局不可用）。
- **逐文件哈希**：签名 manifest 只盖**压缩包级** sha256（密码学上已覆盖全部内容）；
  每个压缩包内自带 `contents.sha256`（包内逐文件哈希清单，构建期生成），
  安装端解压后**逐文件复核一遍**才写完成标记。这样 manifest 体积与文件数解耦
  （draw.io 有数百个文件），事后完整性审计也有据可查。
- **压缩格式**：统一 tar.gz 原始字节。**不做 brotli、不复用 `/lowa-engine/` 的
  location**——那个 location 会给 wasm/data 强加 `Content-Encoding: br`
  （见 [[lowa-engine-hosting-convention]] 的事故记录），pack 走自己的 location
  并显式 `gzip off`，杜绝「客户端把压缩字节再解一层」这类编码事故。
- **符号链接**：构建期打 tar 时 `--dereference` 物化所有软链；安装端解压器
  **拒绝** symlink / hardlink / 绝对路径 / `..` 条目（zip-slip 同款防护），
  并限制条目数（≤ 5000）与解压后总体积（≤ 500 MB）。
- **可执行位**：tar 保留 POSIX 权限；Java 解压端恢复 exec bit（graphviz 的
  `dot` 等）。mac 二进制在构建期已由 `prepare-graphviz.js` 做过
  install_name_tool 重定位 + ad-hoc 重签，pack 原样收录（签名在文件字节里，
  下载不破坏）；Java HTTP 下载不写 quarantine xattr，spawn 不过 Gatekeeper 门。

## 3. 托管与下载源

### 3.1 目录布局（镜像上）

```
/www/wwwroot/plugin-packs/
└── litigation-visual/
    ├── manifest.json          # 最新版指针（no-cache）
    ├── manifest.json.sig
    └── 1.0.0/                 # 版本目录，内容不可变（immutable）
        ├── manifest.json      # 该版本的 manifest 快照 + .sig（回退安装用）
        ├── manifest.json.sig
        ├── litviz-1.0.0.tar.gz
        ├── graphviz-1.0.0-mac-arm64.tar.gz
        ├── graphviz-1.0.0-win-x64.tar.gz
        └── drawio-1.0.0.tar.gz
```

nginx 新增 `location ^~ /plugin-packs/`，照抄 `/update/desktop/` 与
`/lowa-engine/` 的成熟做法：`root /www/wwwroot`、静态直出**不经 Node 应用层**、
manifest `no-cache`、版本目录 `immutable`、`gzip off`（防对 tar.gz 二次编码）、
支持 Range（nginx 静态默认支持，断点续传靠它）。北京（宝塔配置）与新加坡
（`doc/nginx-workdeck-ai.conf` 入库）各配一份。

### 3.2 多源降级与境内速度

客户端按序尝试（与 `update-service.js` 的 `urls[]` 降级同型）：

1. `https://www.aiworkdeck.com/plugin-packs/…`（北京主镜像，境内首选）
2. `https://workdeck.ai/plugin-packs/…`（新加坡镜像，境外/EN 版首选；两站顺序按
   应用语言或 `site` 判定对调）
3. GitHub Release 资产（兜底源）

**镜像必须排在 GitHub 之前**：境内 ECS/用户直连 GitHub 实测 12 KB/s
（[[release-mirror-sync-github-throttle]]），几十 MB 的包不能指望它当主源。
官网两站 `data/` 互不相通不影响本方案——pack 走独立静态目录，不进
`data/plugin-files/`，由发布脚本负责双机同步（§7.3）。

配置：`ai.packs.base-urls`（列表，application.yml 给默认值），
`ai.packs.dir`（默认相对 cwd 的 `packs`，同 `ai.plugins.dir` 惯例）。

## 4. 客户端安装（后端负责）

安装器放在**后端 Java**（新 `NativePackService` + `PackController /api/packs`），
不放 Electron：广场 UI 本来就打后端 API；`~/.aiworkdeck` 是后端 cwd；
deploy/web 服务器形态没有 Electron 也能用同一条链路。

### 4.1 落盘布局（DMG 升级不丢）

```
~/.aiworkdeck/packs/                     # ai.packs.dir，打包态后端 cwd 之下
├── .staging/litigation-visual-1.0.0/    # 安装事务工作区（含 *.part 断点文件）
└── litigation-visual/
    ├── current.json                     # 原子指针 {version, activatedAt}（tmp+rename，overlay.js 同款）
    └── 1.0.0/
        ├── .pack-complete               # 逐文件复核通过后才写的完成标记
        ├── litviz/  graphviz/  drawio/
```

`~/.aiworkdeck` 在 DMG 覆盖安装/大版本升级时不被触碰（license.json、plugins/、
models/ 已验证过这一点），pack 同享此保障。

### 4.2 安装事务（幂等、可续传、失败回滚）

1. 拉 `manifest.json` + `.sig`，内置公钥验签，**失败即中止**（不落任何文件）；
   公钥未配置 = 拒绝一切安装（与 JAR 插件同语义）。
2. 校验 `minAppVersion`（不满足则提示升级应用）与 `engineApi`（宿主不认识则拒装）。
3. 按平台过滤 components，逐个下载到 `.staging/<id>-<version>/<archive>.part`：
   HTTP Range 断点续传（本地已有 `.part` 时带 `Range: bytes=<len>-`，服务器不支持
   206 则重头下）；边下边算 sha256；完成后与 manifest 比对，不符**删除重下**
   （最多 3 次，每次换下一个源）。
4. 解压到 staging（zip-slip/软链/条目数/总体积防护，见 §2）；按包内
   `contents.sha256` 逐文件复核；恢复 exec bit。
5. 全部组件就绪后：`rename(staging → <id>/<version>/)`（同文件系统，原子）→
   写 `.pack-complete` → `current.json` 指针原子切换 → 删除旧版本目录（只保留
   current 一版：draw.io 级别的体积不值得本地存两份，回退 = 按旧版本目录的
   manifest 快照重装，镜像上旧版本常年在架）。
6. 失败处置：验签/哈希失败删除对应产物；**网络中断保留 `.part`**（下次续传）；
   staging 里的半成品不会被任何扫描路径看到（资源解析只认 `current.json` 指的、
   带 `.pack-complete` 的版本目录）。
7. 幂等重装：目标版本目录已存在且带完成标记 → 跳过下载，只重写指针。

### 4.3 进度与 API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/packs/list` | 登录 | 各 pack 的 `{id, state, installedVersion, latestVersion, totalSize}` |
| GET | `/api/packs/{id}/status` | 登录 | `{state: idle\|downloading\|verifying\|installing\|ready\|failed, bytesDownloaded, bytesTotal, error}` |
| POST | `/api/packs/{id}/install` | admin | 异步启动安装（已在装则幂等返回当前进度） |
| POST | `/api/packs/{id}/uninstall` | admin | 见 §6 |

下载在后端单线程执行器里跑（同一 pack 串行、去重），前端**轮询 status**
展示字节级进度（先例：组件管理的模型下载）。鉴权与市场写操作一致
（`X-Session-Id` → admin，桌面单机全员 admin）。

### 4.4 生效不重启

- **后端侧**：`LitigationVisualService.resolveRuntime()` 本就是每次调用现算的
  链式回退（config → env → dev 目录爬升），末位追加 pack 查询
  （`NativePackService.currentDir(id, component)`）。装完即用，无需重启。
- **Electron 侧（draw.io 静态服务）**：`drawio-server.js` 从单根改多根
  （`zetaoffice-server.js` 的 `editorRoots()` 双根同款）：请求时按
  `[AIWORKDECK_DRAWIO_DIR 覆盖, 内置 Resources（若存在）, ~/.aiworkdeck/packs/litigation-visual/<current>/drawio]`
  顺序找文件。根列表每次请求惰性解析（读 `current.json`，fs 开销可忽略），
  装完即生效，Electron 不重启。

## 5. 资源解析优先级（老版本兼容）

对 litviz / graphviz / drawio 三类资源，统一优先级：

1. **显式覆盖**（config `litviz.*` / env `LITVIZ_DIR` 等）——dev 与测试用；
2. **随包内置**（extraResources；打包态由 backend-service.js 注入 env，摘除后
   注入逻辑改为 `fs.existsSync` 判定，目录不存在不注入）——**老版本已装用户
   的随包资源仍在就优先用，不强迫重下**；
3. dev 目录爬升（现状，仅 dev 态命中）;
4. **pack current 目录**。

**大版本升级的资源缺口**：老用户升级到摘除后的大版本，Resources 里的随包资源
随 .app 替换消失，而本地还没有 pack——此时「skill 已启用」与「资源缺失」并存。
处置（已拍板，§13-2）：后端启动时与面板打开时检测
「skill enabled && 资源全链缺失」→ **自动触发 pack 安装**（用户已用启用状态表达过
要这个功能，升级不该让它变哑），左栏面板顶部显示下载进度条；
`litigation_render` 等工具在资源就绪前如实返回「资源包下载中/未安装」的引导文本。

## 6. 卸载语义

- 广场「卸载」一个带 pack 的面板型 skill = 停用 skill（rail 入口消失，现状语义）
  **+ 删除 `packs/<id>/` 整目录**（前端确认框注明可释放体积）。
- 随包内置形态下（老版本）「卸载」维持只停用——Resources 删不掉，也不该删。
- 重装 = 重下（镜像常年在架）。卸载应用不清 `~/.aiworkdeck`（现状一致）。
- `ai.skills.seeded` 的种子化语义不变：pack 卸载不重置用户的启停记忆。

## 7. 与现有体系的接线

### 7.1 skill 门控（体验不变）

- `skill.yml` 新增可选字段 `requires_pack: <packId>`（旧版本应用忽略未知字段，
  向前兼容）。litigation-visual 的 skill.yml 加上它；`requiresSkill` 显隐机制
  一个字节不动。
- 市场前端（MarketSidebarPanel / MarketDetailPane）对带 `requires_pack` 的面板
  skill 扩展状态机：未安装（显示「需下载 ~45 MB」）→ 下载中（进度）→ 已安装。
  「安装」按钮编排：`POST /api/packs/{id}/install` 轮询到 ready → 
  `POST /api/skills/{id}/enable`。资源已就绪（随包或已装 pack）时跳过下载步，
  与今天的体验逐像素一致。
- `/api/skills/list` 的 SkillView 增加 `packId` / `packReady` 两个字段，
  省得前端为每行再打一次 `/api/packs/status`。

### 7.2 desktop 打包摘除

- `desktop/package.json` extraResources 删三项：`../litviz`、
  `bundled/${os}-${arch}/graphviz`、`../frontend/dist/drawio`。
  `skills`（文本）与 `python`（pysvc 还要用）**保留**。
- `backend-service.js`：`LITVIZ_DIR` / `LITVIZ_GRAPHVIZ_DIR` 注入加
  `fs.existsSync` 守卫（drawio 侧 §4.4）；`AWD_PYTHON_HOME`、
  `AI_SKILLS_BUILTIN_DIR` 不动。
- CI `desktop-build.yml`：安装包链路里的 `prepare-graphviz` 与
  `fetch-drawio-assets` 步骤移除（转入 pack 构建链，§7.3）。
- **patch-gate 约束**：以上全是 desktop/ 改动，只能随大版本走——本项整体落
  **v0.21.0**。

### 7.3 pack 构建与发布链

- 新 workflow `pack-release.yml`（workflow_dispatch，输入 packId+version）：
  mac/win 双 runner 各产 graphviz 组件（win 的 19.7 MB DLL 闭包只能在 win 上出），
  litviz/drawio 平台无关组件单 runner 产出；汇总 job 生成 manifest、
  用 CI secret 里的签名私钥出 `.sig`、把全部产物挂上 GitHub Release
  （tag `pack-<id>-v<version>`，与应用 `v*` tag 井水不犯河水，不触发 desktop-build）。
  **release 必须标 prerelease**：GitHub `releases/latest` 是仓库级的，正式 pack
  release 会顶掉应用版——2026-08-19 实测污染镜像同步脚本与官网 fromGithub 达
  2.5 小时（镜像停更、/start 发旧版）。prerelease 不影响资产直链下载。
- 发布脚本 `deploy/publish-pack.sh`（照 `publish-lowa-engine.sh` 的骨架）：
  从 GitHub Release 拉产物（或本机中转）→ 验签验哈希 → 推北京 + 新加坡两台
  `/www/wwwroot/plugin-packs/<id>/<version>/` → 终验（两站各 curl 首尾字节 +
  sha256 对账）→ 最后才切 `<id>/manifest.json` 指针。**双机都传完才切指针**
  （lowa r4 只传北京、CI 从新加坡拉到 404 的教训）。
- 私钥沿用 `AWD_PLUGIN_SIGNING_KEY` 对应的那把（§1）；进 CI secret 之前先按
  `.agent/ACCOUNTS.md` 核对本机备份位置（已拍板沿用此密钥对，§13-1）。

### 7.4 官网仓改动（很小）

pack 不进 registry API、不进 `data/plugin-files/`（那条路是为 ≤20 MB 的审核件
设计的）。官网仓 v1 只需：

- `doc/nginx-workdeck-ai.conf` 加 `^~ /plugin-packs/` location（北京侧宝塔同步改）；
- `/zh/plugins` 页与桌面广场对面板型 skill 的展示口径不变（它们本来就不在
  官网 registry 里）。

## 8. 三方 pack 提交与审核（v1 即开放，已拍板）

三方 pack 的意义：pack 本体是「被代码消费的资源」，单独一个 pack 什么都不做。
三方 pack 的消费方是**三方插件**——JAR 或 Web 插件在 manifest 里声明
`packs: ["<packId>"]`（PLUGIN_SPEC 新增字段），插件安装时联动安装其依赖的 pack；
JAR 插件代码经 Spring 容器取 `NativePackService.currentDir(packId)` 拿到资源路径。
第一方 pack（litigation-visual）由内置 skill 的 `requires_pack` 消费，不经此流程。

### 8.1 提交与受理（官网）

- 提交物 = 单个 zip：未签名的 `manifest.json` 草案（无 `publishedAt`/签名）+
  manifest 里声明的全部 `*.tar.gz` 组件。
- **上传不走 `formData()`**（Next 会把整包缓冲进内存）：新端点以 octet-stream
  流式落盘 `data/pack-submissions/<id>/<version>/`，nginx 对该路由单独放大
  `client_max_body_size`（其余路由维持原值）。
- 受理硬检查（任一不过直接拒收）：id 正则 `^[a-z0-9][a-z0-9-]{1,49}$` 且
  **不得撞第一方保留名单**（litigation-visual 等）；id 归属同插件规则
  （新 id 归提交者，老 id 只有原作者能提新版本）；version 严格递增；
  每个 tar.gz 可解且逐条目过 §2 的四重防护；manifest 里的 sha256/size 与实物
  一致；平台键合法；总体积 ≤ 300 MB。

### 8.2 审核（人工，无自动通过）

审核台展示：manifest 全文、组件清单、解包条目列表（路径 + 大小 + exec 位 +
文件类型嗅探）、二进制字符串提取出的 URL/IP 字面量、作者的用途自述。
原生二进制无法像 class 常量池那样交叉验证，**审核结论主要依赖来源可信度**
（作者是否提供可复现构建脚本/上游开源地址），拿不准就驳回。

### 8.3 签名与发布

审核通过后由平台侧完成（提交者无法触发）：补 `publishedAt` → 用
`AWD_PLUGIN_SIGNING_KEY` 对 manifest 字节签名 → 产物移入
`/www/wwwroot/plugin-packs/<id>/<version>/` → 同步新加坡镜像（SG 侧
`sg_to_bj` 密钥拉取，同 lowa-engine 惯例）→ **双机都验证过才切**
`<id>/manifest.json` 指针。

### 8.4 封禁

官网新增 `GET /api/registry/packs/revoked`（形制同插件封禁表）。客户端沿
`PluginRevocationService` 的节奏（启动 + 每 24h）拉取，命中的已装 pack：
在 `current.json` 写入 revoked 标记 → 资源解析链对它视而不见（消费入口全部
如实报「资源包已被平台下架」）→ 广场标红建议卸载。镜像同时删档 + 指针回退，
阻止新装。不自动删本地文件（与插件封禁同理，防误封丢数据）。

## 9. 测试与验收

- 后端单测：manifest 验签（正/负/未配公钥）、平台过滤、断点续传（本地 HTTP 桩，
  update-service.test.js 同款手法）、zip-slip/软链拒绝、哈希不符重试换源、
  幂等重装、指针原子性、卸载守卫（只删 packs 正下方目录，canonical 校验）。
- 资源解析矩阵：env 覆盖 / 随包在场 / 仅 pack / 全缺四态 ×（litviz、graphviz、
  drawio）——「随包优先于 pack」必须有断言钉住。
- e2e：app-e2e 加旅程「广场安装诉讼可视化 →（桩 pack 源）下载 → rail 出现 →
  出一张 numbered_point_timeline」；desktop `npm test` 盖 drawio-server 多根。
- 发布链：`publish-pack.sh` 带 `check` 子命令本地验产物（不碰网络），
  正反自检同 prepare-graphviz 惯例。

## 10. 安全边界备忘

- `~/.aiworkdeck/packs/` 与 `plugins/` 同级同威胁模型：本机同用户进程可写 =
  可注入可执行内容。这不是 pack 引入的新敞口（PLUGIN_SPEC §1 已声明），
  验签保护的是**分发链路**（下载途中与源站被篡改），不是本机落盘后。
- 安装端对压缩包的四重防护（路径、软链、条目数、总体积）必须在解压**每个条目前**
  检查，不是解完再看。
- pack 安装不执行任何包内代码：litviz 只在用户触发出图时才被 spawn，
  与「JAR 插件装后默认禁用」的精神一致（安装动作本身零执行）。

## 11. 三方 Web 插件与 SDK（Track B）

> 本章回答「三方上传插件 + 用 Web coding 方式开发」的路线。已拍板与 Phase A 同轮实施。

### 11.1 现状缺口

第三方今天已经能提交两种东西：Skill（官网表单，纯文本，登录即发布）与 JAR 插件
（官网提交 → 人工审核 → 签名上架）。缺口是**带界面的插件**：`manifest.frontendEntry`
一直是「预留，v1 不加载」，提交包格式也不收前端资源——一个只会写 Web 的开发者
在这个生态里造不出任何看得见的东西。

### 11.2 形态：web bundle 插件

- 提交包新增 `web/` 目录（纯静态 HTML/JS/CSS，入口 `web/index.html`），
  `frontendEntry` 激活为指向它。可以**不带任何 JAR**——纯前端插件是合法形态，
  这类插件不进 JVM，风险量级低于 JAR 一档。
- 承载：现成的 `PluginPane.vue` iframe 壳。后端把 `plugins/<id>/web/` 静态
  服务出来。
- **安全关键——绝不同源**：iframe 若与应用同源，插件脚本直接拿到
  `X-Session-Id` 打全部 `/api/*`，等于白给宿主权限。方案：
  `sandbox="allow-scripts allow-forms"`（**不含** allow-same-origin，opaque
  origin），插件与宿主只通过 postMessage 桥通信；静态服务响应带严格 CSP
  （`default-src 'self'`，声明了 `network` 权限的插件才按 manifest 放开
  `connect-src`）。
- 能力经桥、按 manifest `permissions` 裁剪：桥的宿主端在 PluginPane 里实现，
  逐调用校验来源 iframe 与权限声明，转发到既有 api.js——权限声明从「自述 lint」
  第一次变成 Web 插件上的**真实执行边界**（JAR 侧做不到的，这里做得到）。

### 11.3 SDK（`@aiworkdeck/plugin-sdk`）

- 单文件 JS（npm 包 + 官网可下模板内联），封装 postMessage 桥与握手：
  `awd.ready()`、`awd.context()`（项目 id/语言/主题令牌）、
  `awd.files.list/read`（按 file_read 权限）、`awd.tools.call(name, args)`
  （按 manifest tools 白名单）、`awd.ui.toast/confirm`、`awd.storage`
  （插件级 KV，宿主代存）。
- 开发工作流：官网插件模板 zip（已有 Java 模板先例 `lib/plugin-template.ts`）
  加一份 web 模板——vite 骨架 + SDK + **宿主模拟器**（一个静态页假扮宿主桥，
  `npm run dev` 在浏览器里就能开发调试，不需要装桌面端）。
- 审核：沿用 PLUGIN_DISTRIBUTION 状态机与签名（web 文件同样进 `files` 哈希表
  签名覆盖）。JS 没有常量池可扫，自动扫描降级为「外联 URL 字面量提取 +
  权限交叉验证」，人工审核为主；sandbox + CSP 是运行时的真实兜底。

### 11.4 与 pack 的关系

Web 插件包仍走官网 20 MB 受理线与 registry `file` 端点（体积小，应用层扛得住）。
三方插件要带重资源时，在 manifest 声明 `packs: [...]` 依赖（§8），
不把 registry 端点撑大。

## 12. 分期

| 期 | 内容 | 仓库 |
|---|---|---|
| **Phase A** | native pack 机制全量（§2–§10）+ 诉讼可视化 pack 化；安装包摘除**在 pack 上架双镜像并验证后**单独出 PR（保证 master 任一状态都能发出功能完整的版本），随 v0.21.0 大版本生效 | 本仓为主；官网仓 nginx conf + §8 提交审核链 |
| **Phase B** | 三方 Web 插件：提交包收 `web/`、frontendEntry 激活、sandbox 桥、SDK + 模板 + 宿主模拟器（§11） | 双仓 |
| **Phase C（未排期）** | 进程外 MCP 插件形态（原 PLUGIN_DISTRIBUTION §9 Phase 3，保留原位） | 双仓 |

## 13. 拍板记录（2026-08-19）

1. **签名密钥**：沿用插件 registry 密钥对（`AWD_PLUGIN_SIGNING_KEY` /
   `ai.plugins.registry-public-key`）。
2. **老用户升级后的资源缺口**：自动补下载 + 面板进度条（§5）。
3. **Phase 顺序**：A、B 同轮实施。
4. **三方 pack**：v1 即开放提交（§8），人工审核无自动通过。
