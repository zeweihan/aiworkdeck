# 插件生态 P1：开放文档读写权——桥 doc.* 命名空间 + 事件通道（设计定稿）

> dev-board#281，延续 #275 生态路线（docs/PLUGIN_API_ROADMAP.md §3 P1）。
> 对位 VS Code 的 TextDocument：让 Web 插件拿到与内置功能同深的文档操作能力，
> 走既有 `editor` 权限与既定白名单同闸。与 P0/P2 同一发版周期实施（规范 v2.7）。

## 1. 现状事实（探查结论）

- **执行旁路已存在**：`PluginPane` 持有宿主注入的 `getActiveEditor()` →
  `{ fileId, executor(action, params) }`（`project-overview.vue` `getPluginActiveEditor`），
  `evidence.link/locate` 已经在用它派发 `set_selection`/`goto_bookmark`。executor 最终
  过 `libreofficeExecutorClient.js` 的 `EDITOR_ACTIONS` 白名单——**任何调用方都逃不过这道闸**。
- **选区变化推送已存在但没出组件**：worker `XSelectionChangeListener` → `sel_changed` →
  editor-main `relaySelection()`（150ms 节流）→ `LibreOfficeEditor.vue` 的
  `subscribeHostEvents` `type==='selection'` 分支，目前只做 `uiRefreshKey++`。
- **文件刷新的单一汇聚点**：`FileTree.loadFiles()`——SSE `refresh_files`、窗口 focus
  兜底、用户增删改、目录/项目切换全部流经它。
- **项目切换 = `uni.reLaunch` 整页重建**：PluginPane 随页销毁重建、重新握手；
  同一实例内 projectId 不会变（只有同项目内换插件才复用组件换 src）。
- **第一个用户的真实痛点**：尽调面板（DdFilesPanel）今天**零感知**——无轮询、无订阅，
  只在 mounted 拉一次。

## 2. doc.* 命名空间

### 2.1 桥方法

```
doc.exec    { action, params? }  ->  { result }        权限 editor
doc.active  {}                   ->  { fileId, kind } | { fileId: null }   权限 editor
```

- **`doc.exec` 是唯一的执行面**：不给每个原语各造一个桥方法（120+ 个原语逐个包装
  必然漂移），action 名与参数**原样沿用** AI 工具面已文档化的下发名——AI 一次能写对
  的 API 面，对「专业人士 + AI 写插件」的作者画像同样成立。SDK 糖衣只在最高频五个：
  `awd.doc.getText()` / `getSelection()` / `find(text)` / `insertText(text)` /
  `addComment(anchorText, text)`（内部都是 `doc.exec` 直通）。
- **action 白名单 = 宿主 SPI `PluginHostImpl.DOC_ACTIONS` 同一份清单**（规范 §11.2：
  writer/calc/impress 全集 + EvidenceLink 书签原语；宿主自用 `load_document`/
  `export_document`/`doc_open_file_sync` 与诊断原语不开放）。JAR 插件（SPI `Docs.exec`）
  与 Web 插件（桥 `doc.exec`）从此是**同一张能力面、同一张白名单**，不另造子集——
  两份清单必然漂移，一份没有维护成本。
- 前端落点：新文件 `frontend/src/config/pluginDocActions.js` 导出 `PLUGIN_DOC_ACTIONS`；
  **parity 测试**（node .mjs）读 `PluginHostImpl.java` 源码的 DOC_ACTIONS 字面量逐项对拍，
  漏一个就红（照 `PluginHostImplTest` 扫 `DocumentEditTools` 源码的既有配方）。
- 执行语义：目标恒为**当前聚焦窗格打开的文档**（与 `evidence.link` 同口径）；
  无活动编辑器 → `no_active_document`；action 不在白名单 → `action_not_allowed`（新错误码）；
  params 附 `__agent: true` 下发——修订署名 "AI WorkDeck"、Writer 修订模式行为与
  AI 管线完全一致（插件改动可被用户逐条接受/拒绝，这是安全网，不是限制）。
- `doc.active` 返回当前聚焦文档的 `fileId` 与 `kind`（writer/calc/impress），
  给插件判断「现在开的是什么」再决定调 doc_*/sheet_*/slide_* 哪族原语。

### 2.2 安全分析

- 权限闸：manifest 未声明 `editor` → `permission_denied`（桥端逐调用比对，与 files.* 同机制）；
- 能力闸：PLUGIN_DOC_ACTIONS（插件层）→ EDITOR_ACTIONS（executor 层）双白名单，
  第二道是既有闸、插件绕不过；
- 越权面：doc.exec 只碰**当前打开的文档**（用户看得见的那份），不能指定任意 fileId
  打开/导出文件——`load_document`/`export_document` 刻意不在白名单；打开文件走
  既有 `ui.openFile`（file_read 权限 + 走宿主统一链路）；
- 修订可回溯：`__agent` 署名 + Writer 修订机制 + 文档检查点（AI 管线既有）之外，
  插件写入不经编排器、没有 run 级检查点——**规范明写**：Calc/Impress 无修订机制，
  插件对表格/演示的写入直接生效，作者须在 UI 上先请求用户确认批量写入。

## 3. 事件通道

### 3.1 协议（新消息类型 `event` + 订阅方法）

```
订阅  插件 -> 宿主   events.subscribe   { events: ["files.changed", ...] }  ->  { subscribed: [...] }
退订  插件 -> 宿主   events.unsubscribe { events: [...] }                   ->  { subscribed: [...] }
推送  宿主 -> 插件   { awd: 1, type: "event", event: "files.changed", data: { ... } }
```

- 显式订阅制：宿主只向订阅了的 iframe 推送（默认全静音，不为没人听的事件付节流成本）；
  `subscribed` 回声当前生效集合。订阅无独立权限，但**每个事件名有自己的权限门**，
  未达权限的事件名从订阅集合里剔除（不报错，回声里自然缺席——与老宿主
  `unknown_method` 降级同一取向：插件按回声判断实际生效面）。
- SDK：`awd.events.on(name, cb)` 返回退订函数；首个监听者自动 `events.subscribe`，
  最后一个退订自动 `events.unsubscribe`；老宿主上 subscribe 抛 `unknown_method`，
  SDK 静默吞掉（`on` 照常返回退订函数，永不触发）——插件代码不用条件分支。

### 3.2 首批三事件

| 事件 | 权限 | data | 宿主触发点 | 节流 |
|---|---|---|---|---|
| `files.changed` | `file_read` | `{}` | `FileTree.loadFiles()` 成功后 `uni.$emit('awd:files-changed')`，PluginPane 转发 | 500ms 合并 |
| `selection.changed` | `editor` | `{}` | `LibreOfficeEditor.vue` selection 分支补一行 `uni.$emit('awd:selection-changed')`，PluginPane 转发 | 300ms 合并 |
| `project.switched` | 无 | `{ projectId }` | PluginPane watch projectId prop（当前架构下 reLaunch 重建、事件极少触发，为未来面板持久化预留语义） | 无 |

- **payload 刻意为空**：事件是「该重拉了」的信号，不是数据通道。选区文本/文件清单
  由插件按需经 `doc.exec get_selection` / `files.list` 拉取（拉取走各自权限闸）——
  否则推送本身就成了绕过权限的旁路，且宿主要为每次推送付一次 worker 往返。
- 第一个用户：尽调工作台是 Web 插件（私有仓 aiworkdeck-dd-plugin），今天靠打开面板
  时拉一次、之后零感知；本期 hello-web-plugin 示例先把订阅链路跑通（狗粮），
  尽调插件升级 SDK 1.3.0 后即可接 `files.changed` 实时刷新底稿清单。

## 4. 兼容策略

- 新消息类型 `event`：老 SDK 对未知 type 静默忽略（v2.6 已验证的既有行为）；
- 新方法 `doc.exec`/`doc.active`/`events.*`：老宿主回 `unknown_method`，SDK 降级如 §3.1；
- 模板默认生成 `minHostVersion: "0.28.0"`（P0）；
- 四处同步：PluginPane / sdk/plugin-sdk（1.2.0 → 1.3.0）/ 官网 `lib/plugin-template.ts`
  （SDK 内联副本 + 模拟器补方法）/ 宿主模拟器 + `backend/skills/plugin-dev/prompt.md` +
  PLUGIN_SPEC 升 v2.7。模拟器实现：`doc.exec` 返回固定假结果、事件面板加手动触发按钮。

## 5. 验证方案

- parity：`pluginDocActions` ↔ `PluginHostImpl.DOC_ACTIONS` 对拍测试（node，读 Java 源码）；
- SDK 行为：`frontend/tests/plugin-sdk/` 补 `events-channel.test.mjs`（订阅/推送/退订/
  老宿主降级/权限剔除）与 doc 方法存在性断言；
- 桥宿主端：`PluginPane` 逻辑抽为可测函数有难度（组件耦合），主验证走 e2e：
  hello-web-plugin 增加「读全文/插入一句话/订阅文件变化」三个按钮，app-e2e 走查；
- 真机：修订署名与白名单双闸各做一次「还原病灶即转红」检查（去掉白名单校验应当
  能调 load_document——测试断言它被拒）。

## 6. finalization 三问

1. **真实插件**：尽调工作台（文件变化感知今天是零）、hello-web-plugin（狗粮）、
   插件开发 skill 生成的第三方插件（prompt.md 同步后 AI 就会写）。
2. **能跑的示例**：§5 的三按钮示例。
3. **过窄/过宽**：`doc.exec` 单方法 + 全量白名单——比逐个包装宽（能力全集一次到位）、
   比开放 executor 窄（宿主自用 action 不漏）；事件 payload 空载荷是刻意窄，
   宽化（带增量数据）留给 `x-` 实验通道验证真实需求后再做。
