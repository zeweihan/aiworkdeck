# 插件生态 P0：治理地基——minHostVersion + 实验 API + 只加不改章程（设计定稿）

> dev-board#280，延续 #275 生态路线（docs/PLUGIN_API_ROADMAP.md §3 P0）。
> 对位 VS Code 的 `engines.vscode` + proposed API + finalization 流程：
> 十几年不破坏兼容靠的是流程，不是天赋。本篇随 P1/P2 同批实施（规范 v2.7）。

## 1. manifest.minHostVersion

### 1.1 字段

```json
{ "id": "my-plugin", "minHostVersion": "0.28.0" }
```

可选，语义化版本字符串：「本插件需要的最低宿主版本」。缺省 = 不限（存量插件零影响）。
非法格式（不过 `isSemver`）→ WARN + 视为缺省（与 permissions 未知值同口径）。

### 1.2 宿主版本从哪来（既有链路，零新建）

`desktop/package.json` → Electron `app.getVersion()` → 后端进程环境变量
`AWD_APP_VERSION`（backend-service.js 注入）→ `application.yml` `app-version: ${AWD_APP_VERSION:dev}`。
dev 态值为 `"dev"`（非 semver）——**跳过校验并 WARN**，照抄
`NativePackService.checkCompatibility` 的既有先例（开发环境放行）。

### 1.3 四道执行点

| 执行点 | 行为 |
|---|---|
| `PluginService` 加载/rescan | 不达标 → 登记元数据但**不加载 JAR、不注册工具、不服务 web/**；新增 `incompatibleReason(id)`（文案「需要宿主 ≥ X，当前 Y，请升级客户端」）。实现取巧且稳妥：`isEnabled(id)` 在不兼容时返回 false（有效启用态），既有全部消费点（ToolRegistry 三处 / PluginWebController / invokeTool / skill isAvailable）**免改自动生效**；用户的启停意愿位不动，升级宿主后自然恢复 |
| `PluginController` enable 端点 | 对不兼容插件启用 → 明确报错（不静默翻一个不生效的位） |
| `PluginMarketService.install` | staging 落盘后、原子替换前解析 staged manifest 校验；不达标中止安装并清理，报「请先升级客户端」——**宁可装不上，不可装成半残** |
| `PluginDevService.validateManifest` | 同校验，错误进既有 errors 列表（AI 靠原文自我修复） |

`PluginView` 透传 `minHostVersion` 与 `incompatibleReason`；MarketPane 卡片渲染不兼容
提示（顺带把后端早已算好、前端从未消费的 `revokedReason` 一起接上——同一处 UI，同一种样式）。

semver 比较：仓里已有两份包私有 `compareSemver`（NativePackService / PluginMarketService），
不动存量；新建 `com.checkba.util.Semver`（isSemver/compare）供新代码用，两份旧拷贝
收敛为独立清理任务。

### 1.4 兼容性诚实声明

minHostVersion 保护不了比它自己更老的宿主（0.27.x 不认识这个字段，照旧忽略）。
这是任何 engines 机制的固有引导缺口，VS Code 也一样。从 0.28 起的所有宿主都会执行；
模板默认生成该字段，缺口随存量宿主升级自然收敛。

## 2. 实验 API 机制（x- 前缀）

- **命名**：未定稿的桥方法一律带 `x-` 前缀（如 `x-ai.requestStream`）；转正 = 去前缀 +
  写进规范方法表 + SDK 出正式包装。`x-` 方法可以改、可以删、不承诺兼容。
- **执行边界（本期落地）**：`PluginPane.handleCall` 对 `x-` 开头的方法加闸——仅
  **dev 免签直装**的插件（`.awd-dev` 标记，`PluginView` 新增 `devInstalled` 透传）可调，
  其余一律返回新错误码 `experimental_not_allowed`。运行时闸是真保证：广场装的插件
  **物理上调不到**实验方法，比受理扫描更硬。
- **受理规则（规范条款 + 审核清单）**：广场拒收使用 `x-` 方法的投稿；官网受理侧的
  自动扫描是后续增强，不阻塞本期。
- 本期 `x-` 方法集合为空——机制先行，第一个实验方法随 P2 的流式增强进来。

## 3. 只加不改章程（写进 PLUGIN_SPEC 新章节）

1. **已发布即冻结**：桥方法名/参数/返回字段/错误码、manifest 字段、SPI 签名与 record
   字段，发布后不改不删；扩展一律新增（新方法 / 新可选参数 / record 末位追加字段）。
2. **破坏性变更 = 新名字**：语义变了就换名字双轨共存（`editor_command`/`wps_command`
   双轨、`evidence.retrieve.v2` 都是先例），旧名至少保留一个发行周期并在规范标注。
3. **版本声明**：宿主每次扩桥升 PLUGIN_SPEC 小版本 + SDK 版本号；插件用新能力时
   声明 `minHostVersion`；对老宿主按 `unknown_method` 降级是 SDK 的既定契约。
4. **四处同步纪律**：桥协议任何变更必须同批改齐 PluginPane / `sdk/plugin-sdk`（含
   examples 与 backend classpath 两份逐字节副本）/ 官网 `lib/plugin-template.ts`（SDK
   内联 + 模拟器）/ `backend/skills/plugin-dev/prompt.md`，并升 PLUGIN_SPEC。守护：
   仓内 parity 测试（Java + node 两处）已有；官网内联副本无自动对拍（本轮已实测漂移
   一处注释），受理为独立欠账记卡。
5. **实验先行**：形状拿不准的能力先走 `x-`，有真实插件用过、示例能跑、形状复核过
   （finalization 三问）再转正。
6. **官方吃狗粮**：新官方面板能力先问「插件 API 能不能做」，做不了就先补 API。

## 4. 验证方案

- `PluginServiceTest`：minHostVersion 达标/不达标/非法格式/dev 态放行/enable 拒绝五条；
- `PluginMarketServiceTest`：install 版本闸（staged 清理断言）；
- `PluginDevServiceTest`：validateManifest 版本项报错文案；
- 前端：MarketPane 不兼容态渲染走既有 UI 走查配方；
- x- 闸：PluginPane 逻辑via e2e/手测 + sdk 测试断言错误码常量存在。

## 5. finalization 三问

1. **真实插件要用**：所有用 v2.7 新能力（doc.*/events/ai.request）的插件都需要
   minHostVersion；第一个写这个字段的就是模板默认生成的每一个新插件。
2. **能跑的示例**：hello-web-plugin manifest 加 `minHostVersion`；模板默认生成。
3. **过窄/过宽**：单字段最小形状；没有做 engines 式的范围表达（`^0.28`）——
   宿主只加不改的前提下「最低版本」即够，范围语法留给真实需求出现再说。
