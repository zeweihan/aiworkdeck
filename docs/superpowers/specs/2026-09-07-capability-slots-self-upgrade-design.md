# 功能原子化与 AI 自迭代升级：能力槽 + 能力包（初稿）

dev-board#497。状态：三处决策已拍板（2026-09-08，见第 8 节）。

## 1. 问题

各方面迭代都快，但功能是整块编译进客户端的。以「诉讼可视化」为例，它本质是「画图」，
可上游引擎一有改进，只能等桌面端发版或发插件，时效性跟不上用户。维护者希望：
在系统设置里对 AI 说「帮我把画图功能升级成这个」并附一个 GitHub 链接，AI 自动完成升级。

## 2. 调研结论（细节见 scratch 报告，证据已核对）

1. 仓库已有六种扩展形态。其中 Skill、Web 沙箱插件、native pack 三种**装完即生效，不用重启**；
   JAR 插件不能真热更（旧 ClassLoader 不卸载）。
2. 「画图」的稳定切口在 `litviz/cli.py` 的命令行协议（我们自己写、有契约测试、
   只有 `LitigationVisualService` 一个消费点），不在 vendor 的 semantic-map。
   `resolveLitvizDir` 已是四档可替换（配置 / 环境变量 / cwd 爬升 / pack componentDir），
   插一档「能力槽」是一行量级的改动。
3. 编排器没有任何「从 URL 拉取代码到本机」的工具，插件安装只有市场按 id、
   项目目录 dev 直装、手动丢文件三条路。这是必须补的第一块。
4. 安全模型的硬结论：AI 自装不等于免审。AI 能自动完成的上限是
   **Web 沙箱插件 / 声明式数据包 / Skill 文本**（三者都有真实边界或零执行面）；
   会在宿主机执行代码的形态（JAR、process 型引擎）必须有平台签名。
5. 画图的时效性问题，最短的正解是把 litviz 的发布链切到已存在的 pack 通道并补
   「版本追新」（现在 pack 只在缺失时补装，装好后不会升级）。

## 3. 设计目标与非目标

目标：
- 把「一项能力」抽象成**槽**（capability slot）：每个槽有内置实现和若干候选实现，
  用户或 AI 可切换，切换即生效，可回滚。
- 提供**一个**AI 工具 `capability_install(url)`，让「粘 GitHub 链接 + 一句话」能走通；
  能自动装的自动装，不能自动装的把原因和替代路径说清楚。
- 设置页一个「能力升级」分区：输入框 + 槽列表 + 已装包与回滚。

非目标（本期不做）：
- 不做运行时沙箱（既有裁决，不推翻）。
- 不做第七种分发形态：能力包就是带 `contributes.capabilities` 的插件包，
  复用插件 id、启停、封禁、rescan、签名全套设施。
- 不改 ToolRegistry 做工具级替换；本期槽只落在「进程型引擎目录」这一类。

## 4. 概念模型

```
capability slot  litigation.diagram
  ├─ builtin          随包内置的 litviz/（永远存在，作为兜底）
  ├─ pack:litigation-visual@1.4.0   签名 pack 的 componentDir（现有）
  └─ plugin:<pluginId>:<capId>      能力包声明的实现目录（新增）
selected = "plugin:acme-litviz:engine"   存 system_setting  capability.litigation.diagram.selected
```

- 槽 id 用点分命名，首期只注册一个：`litigation.diagram`，协议 `litviz-cli/1`。
- 选择位的形状与降级链**照抄** `ai.styleProfile.selected`（`<pluginId>:<profileId>`，
  插件禁用或目录损坏自动退回下一级，最终退到 builtin）。
- 候选实现按「kind」分三档，档位决定谁能装：

| kind | 内容 | 谁能装 | 安装路径 |
|---|---|---|---|
| `web` | 沙箱 iframe 前端 | AI 自动 | 现有 dev 直装 |
| `data` | 模板 / 样式画像 / l10n / skill 文本 | AI 自动 | 现有 dev 直装（需放宽「必须有 frontendEntry」） |
| `process` | 宿主机执行的引擎目录（python 等） | 仅签名 pack 或市场签名插件；开发者模式例外 | 现有 pack / 市场 |

## 5. 组件与数据流

### 5.1 manifest 扩展（PLUGIN_SPEC 新增 §15，规范 v2.10）

```json
"contributes": {
  "capabilities": [{
    "capability": "litigation.diagram",
    "id": "engine",
    "kind": "process",
    "entry": "engine/",
    "protocol": "litviz-cli/1",
    "runtime": "python>=3.11"
  }]
}
```

- `entry` 是插件目录内的相对路径，process 型要求其下存在 `cli.py`；
  `../` 逃逸沿用 `backendJars` 的同款拒绝。
- `protocol` 不匹配槽声明的协议即拒装（这是能力包与槽之间唯一的契约点）。

### 5.2 后端新增

- `CapabilitySlotRegistry`（`com.checkba.service.capability`）：槽表（首期硬编码一条），
  `candidates(slotId)` 聚合 builtin / pack / 已启用插件的声明；`resolve(slotId)` 按选择位 +
  降级链返回实现目录；`select(slotId, ref)`；`rollback(slotId)` 回到上一次选择
  （上一次值存 `capability.<slot>.previous`）。
- `CapabilitySourceFetchService`：只收 `https://github.com/<owner>/<repo>[/tree/<ref>]`，
  经 `SsrfGuard` 后拉 codeload tarball，解包到临时目录；限额与 `PluginDevService` 同口径
  （200 文件 / 单文件 5MB / 总 20MB）；返回 `{owner, repo, ref, commit, manifest, fileList}`。
- `CapabilityInstallService.plan(url)` → 只拉取与校验，返回「安装计划」
  （档位、权限、文件清单、会落到哪个槽、能否自动装、拒绝理由）；
  `apply(planId)` → 落盘并安装。process 档：默认放行（开发者模式默认开，dev-board#497
  维护者裁决），关闭开发者模式后才拒绝，返回「需签名 pack 或开启开发者模式」。
  开发者模式（`capability.dev-mode`，未设置时视为 `true`，设置页显式开关 + 打开前二次确认文案）
  下允许以 `.awd-dev` 标记安装，但候选项在 UI 上永远带「未签名」标签。
- `LitigationVisualService.resolveLitvizDir` 最前面插一档：`slotRegistry.resolve("litigation.diagram")`。
- 端点：`GET /api/capabilities`（槽 + 候选 + 当前选择）、
  `POST /api/capabilities/{slot}/select {ref}`、`POST /api/capabilities/{slot}/rollback`、
  `POST /api/capabilities/plan {url}`、`POST /api/capabilities/apply {planId}`、
  `GET/PUT /api/capabilities/dev-mode`。鉴权沿用设置页 admin 判定。
- AI 工具（新类 `CapabilityTools`）：`capability_list`、`capability_install(url)`、
  `capability_select(slot, ref)`。`capability_install` 内部先 `plan`，把计划用
  `<question>` 协议交用户确认后再 `apply`。工具闸写在代码里（登录 + admin），不靠 skill 白名单。

### 5.3 前端

设置页 `AdminPane.vue` 新增 `system` 组分区 `capabilities`（「能力升级」）：

1. 顶部一行：GitHub 链接输入 + 一句话说明 + 「让 AI 升级」按钮。点击后把两者拼成一句话
   发进当前对话（走既有对话通道，进度就是对话里的工具事件），不新开 SSE。
2. 槽列表：每个槽一张卡，显示当前实现、候选实现（来源标签：内置 / 签名包 / 本机开发 / 未签名），
   切换用 `AwdSelect`，「回滚」按钮。
3. 开发者模式开关（`AwdSwitch`），打开时出确认弹窗说明风险。
4. 样式全部用 `--awd-*` 令牌，复用 `components` 分区的行状态机写法。

### 5.4 与「时效性」直接相关的配套改动

pack 版本追新：`NativePackService` 启动与每 24h 比对 registry 的 latestVersion，
已启用且有新版本时静默下载并切指针（失败保持旧版）。这一条独立成卡，本期不实现。

## 6. 错误处理

- URL 不是 GitHub / 私有仓 / 超限：`plan` 直接返回可读原因，不落盘。
- manifest 缺失或 kind 不合法：返回逐条错误（沿用 `validateManifest` 的报错风格，AI 可自修）。
- 撞 id：与广场已装同名即在 `plan` 阶段报，不进入 `apply`。
- 选中实现损坏：`resolve` 静默降级并记 WARN，`GET /api/capabilities` 里标「已降级」。

## 7. 测试

- 后端：`CapabilitySlotRegistryTest`（选择/降级/回滚）、`CapabilitySourceFetchServiceTest`
  （URL 白名单、限额、tarball 解包用本地 fixture 不上网）、`CapabilityInstallServiceTest`
  （三档分流、dev-mode 关闭时 process 拒装、默认未设置时 process 档放行）、
  `LitigationVisualService` 现有测试补一条「槽选中目录优先于 cwd 爬升」。
- 前端：`frontend/tests/capabilities/*.test.mjs` 源码级断言（分区接在链尾、i18n 键成对、
  开关默认开），接进 `ci.yml`。

## 8. 已拍板（2026-09-08）：保留且默认开；首期一个槽；pack 版本追新立刻做（dev-board#499）

1. 开发者模式保留，默认开。它让「从 GitHub 拉一个未签名 python 引擎」在本机可行，代价是把
   宿主机执行权交给用户点的那个仓库；不需要时可在设置页关闭。
2. 首期只做 `litigation.diagram` 一个槽。第二个槽候选：文书模板包、OCR 引擎、语音转写引擎。
3. pack 版本追新立刻做（见 dev-board#499），不再作为独立后续项搁置。
