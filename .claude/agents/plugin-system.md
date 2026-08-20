---
name: plugin-system
description: 插件系统领域（具体插件实现）。任务涉及尽调/脱敏/股东大会等业务插件、skill 定义与注入、PluginService/SkillRegistry/SkillRouter、动态 JAR 插件时，先读本文档再动代码。
---

# 插件系统 领域地图

职责边界：各具体业务插件与插件/skill 运行机制。不含插件市场页与 registry 同步（plugin-marketplace 领域），不含左栏 UI 本身（sidebar-shell 领域）。

## 四类插件形态

1. **内置面板型**：前端组件直接内嵌 project-overview.vue 面板区，无启停开关，靠 `leftSidebarPlugins.js` 静态配置 + 角色过滤（`getPluginsForUser`，CLIENT 角色只见 dd-files）。
2. **Skill 型**：后端 prompt 包（skill.yml + prompt.md），对话关键词触发。
3. **动态 JAR 插件**：plugins/ 目录下 manifest.json + JAR，前端用 PluginPane iframe 承载。
4. **原生资源包（native pack，2026-08 立项）**：重资源（脚本运行时/平台二进制/静态资产）的运行时下载分发，规范 `docs/NATIVE_PACK_DISTRIBUTION.md`。后端 `service/pack/NativePackService` + `/api/packs`；skill.yml 用 `requires_pack: <packId>` 声明依赖；落盘 `~/.aiworkdeck/packs/<id>/<version>/` + `current.json` 原子指针。首个对象是诉讼可视化（litviz+graphviz+drawio）。

**「面板型插件在广场里启停」的体验对齐（2026-08-19，PR#433）与真下载分发的关系**：诉讼可视化、会议录音、脱敏这几个左栏面板靠 `enabled_by_default:false` + `requiresSkill` 门控在广场呈现「安装/卸载」。资源随包在场时，「安装」仍只是 `POST /api/skills/{id}/enable` 翻启用位（不联网）；skill 声明了 `requires_pack` 且资源不在场（新版本已从安装包摘除、或老用户升级后资源随 .app 替换消失）时，「安装」会先走 `/api/packs/{id}/install` 下载资源包（字节级进度）再启用，后端启动时对「已启用但资源缺失」的 skill 自动补下载。随包资源优先于 pack（老用户不强迫重下）。

## 现有插件清单

**尽调（DD）**：前端 `frontend/src/components/DdFilesPanel.vue` + `DdRequestEditor.vue`；后端 `controller/DdController.java`（/api/dd）+ `service/DdService.java`；实体 DdRequest/DdItem/DdComment + 对应 Repository。无 skill。

**脱敏**：前端 `frontend/src/components/DesensitizePane.vue`；后端 `controller/SensitiveController.java`（/api/sensitive：GET /options、POST /desensitize）+ `service/SensitiveService.java`（PDF 走 PDFBox PDFTextStripper 定位坐标涂黑，Word 走 XWPFDocument 段落级文本遮蔽）+ OcrService 辅助。**skill 型门控（2026-08-19）**：`backend/skills/desensitize/`（`enabled_by_default:false`，广场启停），`leftSidebarPlugins.js` 的 `desensitize` 条目带 `requiresSkill: 'desensitize'`——照搬诉讼可视化那套模式，装了才在左栏出现。**这个 skill 背后没有 AI 编排注入的能力**（无 `allowed_tools`）：命中触发词「脱敏」时 prompt.md 只引导模型把用户指向面板手动操作，不假装能在对话里完成脱敏；面板本身仍是直连 `/api/sensitive` 的老路径，和 AI 编排无关。`languages` 只给 `zh-CN`——`DesensitizePane.vue` 本身已 i18n 化，但策略勾选项文案来自 `SensitiveType` 枚举（label/description 只有中文，`SensitiveController` 直接拼 `"label (example)"` 无语言分支），面板核心内容英文版下会露出中文，等 `SensitiveType` 补英文文案（需要改 `.java`）再解禁双语。

**股东大会核查（已下线，2026-08-17）**：维护者决定不做了。`leftSidebarPlugins.js` 里的 rail 入口已移除、skill 改成 `enabled_by_default: false`，**其余三层代码一律保留**（面板组件、controller、service、实体、api.js 端点），想恢复只需把 rail 条目加回去。存量安装里 skill 仍是启用状态（`SkillRegistry` 的种子化只在首次见到该 id 时生效），要在插件广场手动停用。下面这段是它下线前的实现地图，恢复或翻旧账时照读。

面板 + AI 编排混合型（三层齐备）。**注**：下段那条「pinnedSkillId 只裁剪工具不注入 prompt」的地雷已在 2026-08 修掉（判据同源收敛到 `SkillRouter.activateForTurn`，见上文 skill 注入链路一节）；触发词必须在 prompt 文本里这条仍然成立——面板 kick-off 走的是自动匹配，不带 skillIds。前端 `frontend/src/components/ShareholderMeetingPanel.vue`（会话列表/五组材料槽位/巨潮拉取/开始核查，选文件用 FilePickerDialog 的 accept 过滤）；后端 `controller/ShareholderMeetingController.java`（/api/shareholder-meeting）+ `service/ShareholderMeetingService.java`（底稿夹 `股东大会核查/<公司>_<届次>/01..05` 五子目录、材料复制幂等、kick-off prompt 组装）+ `service/CninfoAnnouncementService.java`（巨潮拉取，挑选启发式移植自内核 skill 且有单测锁定）；skill `backend/skills/shareholder-meeting-verification/`。执行链路：面板 start 接口返回 prompt（以触发词「股东大会核查」开头）→ project-overview 经 `ChatInterface.sendExternalPrompt`（expose）以 AGENT 模式发送 → skill 注入 → AI 用 extract_file_text/run_python/write_docx（带 parentFolderId）产出核查底稿表与法律意见书到 04/05 子目录。**地雷**：pinnedSkillId 只裁剪工具不注入 prompt，触发词必须在 prompt 文本里；ASK 模式跳过注入。

**上市路径选择（Skill 型）**：`backend/skills/listing-pathway/`（skill.yml + prompt.md）。无独立面板，对话触发。

**诉讼可视化**：面板 + skill + 专用工具三层齐备，详见 `.claude/agents/litigation-visual.md`。skill 在 `backend/skills/litigation-visual/`。

**会议录音**：面板 + skill + 专用工具三层齐备（2026-08-14）。前端 `frontend/src/components/MeetingRecordingPanel.vue`（一键录音/列表/转写稿/说话人改名/生成纪要）+ **模块级录音单例** `frontend/src/utils/meetingRecorder.js`（MediaRecorder 5s 分片边录边追加上传，页面跳转不断录）+ 跨页面浮动指示器 `utils/recordingIndicator.js`→`MeetingRecordingIndicator.vue`（body 级挂载，feedbackWidget 同模式）；后端 `controller/MeetingRecordingController.java`（/api/meetings）+ `service/meeting/`（MeetingRecordingService 生命周期、MeetingTranscriptionService 转写编排：JavaCV 转码 mp3 → OSS 签名 URL → 通义听悟 CreateTask（说话人分离 SpeakerCount=0 + 章节/摘要/待办）→ **poll-on-read** 收结果、TingwuClient/MeetingOssClient 接口+SDK 实现、MeetingTranscriptParser 纯函数解析）；工具 `service/ai/tools/MeetingTools.java`（meeting_list_recordings/meeting_get_transcript）；skill `backend/skills/meeting-recorder/`（`enabled_by_default:false`，广场启停，触发词「会议纪要」）。凭证五件套（AK/SK/听悟 AppKey/OSS bucket/endpoint）存 system_setting `meeting.asr.*`/`meeting.oss.*`，admin 页「会议转写」卡片可改（AdminConfigController TingwuConfig）；未配置时录音存档可用、转写降级提示。转写三档（`external.asr.provider` = platform | byok | local，分档在 `MeetingTranscriptionService` 编排层）：platform 走网关、byok 用自己的听悟凭证、**local 走本机 `asr-service`（faster-whisper，音频零出网，没有说话人分离）**，档位与就绪判定见 `.claude/agents/licensing-billing.md` 地雷 36-39。**地雷**：听悟只收公网 URL（必须 OSS 中转，转写完即删）；kick-off prompt 以「会议纪要」开头；录音单例绝不能搬进页面组件（reLaunch 即断录）；local 档全程没有 taskId，「转写中」的自愈判据是进程内 `inFlight` 集合而不是 taskId。

**语音合成**：前端 `frontend/src/components/EasyVoicePane.vue`，与会议录音同占 rail `voice` 位、面板内两个 tab（`project-overview.vue` 的 `effectiveVoiceTab` 计算属性解出实际渲染哪个）。skill `backend/skills/text-to-speech/`——**默认启用**（`enabled_by_default: true`，与会议录音/脱敏/诉讼可视化的默认关闭相反：语音合成此前无门控，老用户升级后入口不能消失，装了广场里能停用即可）。和脱敏同配方：无 `allowed_tools`，命中触发词时 prompt 只引导去用面板，不假装能在对话里合成语音；模型下载走 desktop `modelManager`（约 300MB，本机离线引擎），与广场安装动作解耦——广场「安装」只是 `enable` 翻启用位。`leftSidebarPlugins.js` 的 `PANEL_SKILL_IDS` 里手工列了这个 id（和 `meeting-recorder` 一样，因为 `voice` rail 位本身没有 `requiresSkill` 字段，两个 tab 的门控都在面板内部做）。

**动态 JAR / Web 插件**：前端 `frontend/src/components/PluginPane.vue`（props url/pluginId/permissions/projectId，url 空则报"未配置入口地址"；加载哪个插件由父页面按 leftPaneKey + dynamicPlugins[].frontendEntry 决定）；后端 `service/ai/PluginService.java` + `controller/ai/PluginController.java`（/api/plugins）+ `controller/ai/PluginWebController.java`（/api/plugin-web，见下）。

### 三方 Web 插件（规范 v2.3，docs/PLUGIN_SPEC.md §8）

`manifest.frontendEntry` 从「预留」激活。两种形态在 PluginPane 里**行为刻意不同**：

- **`web/` 相对路径** = Web 插件。后端 `GET /api/plugin-web/{id}/**` 静态服务 `plugins/<id>/web/`；PluginPane 给 iframe 加 `sandbox="allow-scripts allow-forms"`，与插件只走 postMessage 桥。
- **`http(s)://` 绝对 URL** = 旧形态。不加 sandbox、不发握手、不响应桥调用——改它只会打断存量插件。判据是 URL 里有没有 `/api/plugin-web/`（`PluginPane.isWebPlugin`）。

**绝不给 sandbox 加 `allow-same-origin`。** 同源的 iframe 能读 localStorage 里的 `X-Session-Id` 并打全部 `/api/*`，等于白送宿主权限。

桥协议（`PluginPane.vue` 宿主端 / `sdk/plugin-sdk/awd-plugin-sdk.js` 插件端 / 官网模板与宿主模拟器，**三处同一份契约**）：`init` 握手 → `call{seq,method,params}` → `result{seq,ok,result|error}`；双向来源校验（宿主认 `event.source === iframe.contentWindow`，插件认 `window.parent`），targetOrigin 只能 `'*'`（opaque origin）。v1 方法：`context.get` / `files.list` / `files.read` / `ui.toast` / `storage.get` / `storage.set`；错误码 `permission_denied` / `unknown_method` / `quota_exceeded` / `not_found`。

**这是 manifest permissions 第一次成为真实边界**：缺 `file_read` 时 `files.*` 直接 `permission_denied`；`network` 决定 PluginWebController 下发的 CSP 是 `connect-src 'none'` 还是 `connect-src https:`。JAR 插件同 JVM 同权限，做不到这一点。

插件级 KV 存宿主 `localStorage` 的 `awd_plugin_kv_<pluginId>`，总量 64 KB；`files.read` 文本上限 5 MB（超限截断且 `truncated:true`，不报错），扩展名不在可抽取文本白名单里的按二进制拒绝。

`manifest.packs: ["<packId>"]`（v2.3）：在线安装成功后 `PluginMarketService` 逐个 `NativePackService.installAsync`，**装不上不回滚插件只记 WARN**。

示例：`examples/hello-web-plugin/`；SDK 源头 `sdk/plugin-sdk/`（官网模板里那份是分发副本，必须逐字节一致）。

### 插件开发形态（dev-board#61，2026-08-20）

第五种信任路径：**本机用户自己写的插件免签直装**（区别于广场的审核+验签+装后默认禁用）。
链路：项目根「插件开发/<id>/」文件夹是源码（manifest.json + web/，文件树可见、CodeMirror
可编辑、进版本记录）→ `PluginDevService.install` 校验后拷进本机 `plugins/<id>/` + rescan +
**启用**。装出的目录带 `.awd-dev` 标记（JSON：projectId/folderId/installedAt）——
装机拒绝覆盖无标记（=广场装的）同名目录，`dev/uninstall` 也只认带标记的目录。

- **安全红线：dev 安装只收纯 Web 插件**——manifest 的 backendJars / tools / skills / packs
  任一非空一律拒装（JAR 与宿主同 JVM 同权限，免审路径会把签名闸变成摆设；沙箱 Web 插件
  才配「写完直接跑」）。校验错误逐条拼在 IllegalArgumentException.message 里，
  面板与 AI 工具都按原文展示/返回（AI 靠它自我修复迭代）。
- 端点 `/api/plugins/dev/*`（scaffold/status/install/uninstall，PluginDevController，
  写操作 admin 同市场口径）；AI 工具 `plugin_dev_scaffold` / `plugin_dev_install`
  （PluginDevTools，新工具组件记得同步 RealToolBeans——已加）。
- 内置 skill `backend/skills/plugin-dev/`（enabled_by_default:false，requiresSkill 门控
  左栏「插件开发」面板 PluginDevPanel.vue，rail 排在 market 之后）。**prompt.md 是
  Web 插件开发的权威 spec**（目录契约/manifest 规则/沙箱边界/SDK 桥 v1 全量 API/迭代流程），
  改桥协议或 manifest 规则时必须同步它，否则 AI 会按旧契约写插件。
- 骨架模板在 `backend/src/main/resources/plugin-dev/`（template-index.html +
  awd-plugin-sdk.js 副本）。**SDK 至此有四份分发副本 + 宿主端实现**（原三份 + 本 classpath
  副本），classpath 副本与源头的逐字节一致由 `PluginDevSdkParityTest` 守着。
- 文本扩展名白名单已放宽到代码文件（json/js/mjs/css/html/htm/yml/yaml），前后端两张表
  必须一致：`TextFileEditTools.PLAIN_TEXT_TYPES` 与 `fileOpenTabs.js` 的 `PLAIN_TEXT_TYPES`。

## 注册与加载链路

1. 静态注册：`frontend/src/config/leftSidebarPlugins.js` 导出 LEFT_SIDEBAR_PLUGINS + `getPluginsForUser(role)`。
2. project-overview.vue 计算属性（~:1581）合并静态列表与 dynamicPlugins。
3. 动态插件：`loadDynamicPlugins()`（~:6261）调 `GET /api/plugins/list`，映射成 `{key:'plugin-<id>', pluginId, label, icon, isDynamic, permissions, frontendEntry}`。`frontendEntry` 经 `api.js` 的 `resolvePluginEntryUrl(id, entry)`：相对路径 → `<apiBase>/api/plugin-web/<id>/<entry>`，绝对 URL 原样。**`key` 是 `plugin-<id>`，`pluginId` 才是原始 id**——桥的握手上下文与 KV 分区键用后者，混用会让插件存储串到别的键上。
4. 面板分发（~:531-563）按 leftPaneKey：dd-files→DdFilesPanel、desensitize→DesensitizePane、easyvoice→EasyVoicePane、search→SearchPanel、files→文件树、动态→PluginPane、其余→占位符。
5. 后端 PluginService：@PostConstruct 扫 `plugins/`（可配 `ai.plugins.dir`），读 manifest.json → PluginMetadata；有 backendJars 时独立 URLClassLoader 加载，扫 langchain4j @Tool 类注册进 ToolRegistry。`POST /api/plugins/rescan` 热重扫。

## skill 文件格式（docs/SKILL_SPEC.md、docs/PLUGIN_SPEC.md）

目录式：`skills/<id>/skill.yml + prompt.md`。skill.yml 字段：`id`（必需，kebab-case，启停键）、`name`、`description`、`triggers`（必需，关键词数组，用户输入"包含"即命中）、`prompt`（默认 prompt.md）、`allowed_tools`（须为 ToolRegistry 真实工具名）、`output`、`requires`（如 evidence.retrieve.v1，v1 仅声明）。未知字段忽略；解析失败跳过不阻断。

**应用语言字段（EN 版 PR5，全部可选）**：`languages`（数组，可用的应用语言；**缺省 = 只在 zh-CN 可用**——存量第三方 skill 没这个字段，英文版自动隐藏，方向安全）、`name_en` / `triggers_en` / `output_en`（英文侧文本；triggers_en 只在 en-US 参与匹配，zh-CN 匹配行为不变）、目录下可放 `prompt.en.md`（存在即加载，英文注入优先用它，缺省回退 prompt.md）。语言过滤收口在 `SkillRegistry.isAvailable`（match/钉选/注入三条路径共用，不会只滤列表不滤注入）；内置三 skill：股东大会核查与上市路径 `languages: [zh-CN]`（中国法深度绑定，且后者触发词含 IPO/SPAC/VIE 会命中英文输入，必须真隐藏），诉讼可视化双语（带 triggers_en + prompt.en.md）。守卫在 BuiltinSkillsTest / SkillRouterTest 的语言组测试。注意 `/api/skills/list` 与广场列表**不做**语言过滤（管理面照常展示，只是英文模式下 zh-only skill 永不注入）。**因此该列表带了 `available` 字段（= `SkillRegistry.isAvailable`）与 `nameEn`**：对话面板那个「主动加载技能」选择器必须自己按 `available` 滤一道，否则英文界面下用户能勾中一个 zh-only skill，勾了永远不生效也没有提示。

插件携带 skill：manifest.json `skills` 字段列子目录名，PluginService 只收集目录（`getPluginSkillDirs()`），解析/启停归 SkillRegistry，记 sourcePluginId。

manifest.json 要点：id（必需）/name/version/icon/author/permissions（file_read/file_write/network/editor）/tools[]/frontendEntry/backendJars[]/skills[]。

## skill 注入对话链路（backend/src/main/java/com/checkba/service/ai/skill/）

- `SkillRegistry.java` — 发现/加载：扫内置 skills/ 目录 + 插件携带目录，SnakeYAML 解析，id 去重（先扫到优先）；`isAvailable` = 自身启用 且 所属插件未禁用。
- `SkillRouter.java` — `match(userInput)` 取最长命中关键词（自动匹配仍是单选）；`activateForTurn(conv, input, pinnedSkillId, manualSkillIds)` 每条用户消息刷新一次**生效集合**；`activeSkills(conv)` 返回 `List<ActiveSkill(definition, displayName, source)>`（`activeSkill` 是它的单值出口）；`visibleTools` 按整个集合的 allowed_tools 并集 ∪ baseTools ∪ `ORCHESTRATION_TOOLS` 裁剪（业务工具零命中则不裁剪）；`promptInjectionFor(skill)` 拼一个 skill 的注入块；`displayName(skill)` 按应用语言解析展示名。
- **生效集合 = 手动选择 ∪ 触发词自动命中**（2026-08 AI 面板 skill 可见性改造）：
  - 手动选择来自 `POST /api/agent/chat` 的 `skillIds`（旧字段 `pinnedSkillId` 收编为「只有一项的手动列表」，已 `@Deprecated`）。**无状态**，前端每轮携带，后端不持久化。
  - **并集而不是覆盖**：手动选择表达的是「这轮务必带上它」，不是「只准用它」。集合顺序把手动放在前面，于是 `activeSkill` 这个单值出口仍返回用户明确选的那个（旧的「钉选优先于触发词匹配」语义因此保持）。
  - 同一个 skill 既被手动选中又命中触发词时只出现一次，source 标 `manual`。
  - 埋点 `skill.activated` 每个生效的 skill 各一条（`how` 取值仍是旧字面量 pinned/matched，官网账本按它分组）；`matter.classified` 只取首个——一轮对话只能有一个事项类型。
- 编排接入（纯旁路两处）：`AgentOrchestrator.java` activateForTurn + 发 SSE `skill_update`（~:430）+ visibleTools（~:1160）；`ContextAssemblerService.java` **activeSkills→promptInjectionFor 逐个注入**（~:165）。ASK 模式跳过注入，且手动选择在 ASK 下整体不参与激活。
- **地雷已修（别改回去）**：`ContextAssemblerService` 原来在注入处自己 `match(userPrompt)` 重新匹配了一遍，判据与编排器裁工具用的那套不是同一个——于是 pinnedSkillId **只裁工具不注入 prompt**，`enabled_by_default` 之外最阴险的一类静默故障。现在两者同源读 `skillRouter.activeSkills(conversationId)`。**注入侧一律不许再 match 一次。**
- 配置：`SkillProperties.java`（ai.skills.dir / base-tools / disabled-cache-ttl-ms / registry-url）。

### allowed_tools、base-tools 与编排类工具（写 skill 前必读）

命中 skill 后模型可见的工具 = **allowed_tools ∪ base-tools ∪ 编排类工具**，三份来源语义不同，别合并：

- `allowed_tools`（skill.yml）——本 skill 的业务能力清单。**不是**「常用工具默认都在」，需要的每个工具都得逐个列出，漏一个就等于对模型隐藏了这个能力。
- `base-tools`（application.yml `ai.skills.base-tools`，只有三个：`read_document / list_files / query_memory`）——业务能力兜底，随部署形态可调。
- 编排类工具（`SkillRouter.ORCHESTRATION_TOOLS`，当前 `todo_write` / `dispatch_subtask`）——**恒定可见，任何 skill 都裁不掉**。编排能力属于「Agent 怎么干活」，不属于任何业务领域。刻意写死在代码里不做成配置项：做成 yml 的话 prod/desktop 覆写一次就能重新裁掉它，而这个故障是静默的。护栏是 `SkillRouterTest.orchestrationToolsAlwaysVisible`（用一个 allowed_tools 故意不含它们的假 skill 断言）。反问走 `<question>` 标签、不是工具，所以不在这组里。

顺带的语义变化：白名单零命中的回退判据现在排除编排类工具（「业务工具零交集」才回退不裁剪），否则「零交集」永远至少剩那两个，原来的误配置保护会被静默废掉。

裁剪只影响可见性、不拦分发（SkillRouter 类注释）。后果是：漏列的工具在**原生 function calling** 下模型压根看不见（永远不会用），但在 **XML 兜底协议**下模型凭 system_prompt 的记忆写出 `<tool_code>` 仍能被分发成功。**同一个 skill 的能力边界因此取决于当前模型走哪套协议**——这类 bug 在换模型时才暴露，排查时先看协议再看白名单。

2026-08 实例：两个自带 skill（`shareholder-meeting-verification`、`listing-pathway`）都漏了 `dispatch_subtask`，而它们恰好是 `prompts/system_prompt.md` 第 6.5 节明确要委派子 Agent 的长程任务。已补上（#323），并由回放评测用例 `skill-shareholder-meeting-dispatch-subtask-visible`（`backend/src/test/resources/ai-eval/cases/cases-skill.json`）守住「skill 命中时 dispatch_subtask 在可见工具集里」。这两处显式声明在编排类工具恒定可见之后**已经冗余，但刻意保留**——显式声明无害且自文档，删掉会让 skill.yml 看起来「不需要子 Agent」。

## skill 目录：可写 vs 只读内置（2026-08）

`SkillRegistry` 扫**两个**目录：

1. `ai.skills.dir`（默认相对目录 `skills`，**可写**）——广场安装/卸载的落点。
   打包态后端 cwd 是用户数据目录，所以它解析到 `<userData>/skills`。
2. `ai.skills.builtin-dir`（**只读**，绝对路径）——随发行版分发的内置 skill。
   桌面端用 `AI_SKILLS_BUILTIN_DIR` 注入 `Resources/skills`；dev 态留空。

**先扫可写目录**：id 去重是「先扫到优先」，于是广场装的同 id skill 能覆盖随包内置的
那份——内置 skill 出问题可以走广场热修，不必等客户端发版。

**历史坑（v0.11.1 及以前）**：`backend/skills/` 从未进过安装包，而打包态 `skills`
相对目录解析到一个空目录——上市路径与股东大会核查两个内置 skill **在发行版里根本不
存在，只在 dev 生效**。已实证（发行版 Resources 下无任何 skill 痕迹）。修复即上面的
双目录 + extraResources。`SkillRegistryTest` 有六条测试钉住这套语义。

内置 skill 的 `allowed_tools` 是否都是真实工具名，由 `BuiltinSkillsTest` 反射扫
`@Tool` 方法名逐条核对——上面那条「部分写错更阴险」的地雷从此会在 CI 里红。

## 启停存储与过滤

- 存 `system_setting` 表（key/value 键值），值为禁用 id 的 JSON 数组，默认全启用，内存缓存 TTL 5s：插件 `ai.plugins.disabled`（PluginService），skill `ai.skills.disabled`（SkillRegistry）。
- **插件工具的过滤在 ToolRegistry 而非 PluginService**（Phase 3A）：getAllSpecifications / toolNamesLongestFirst / resolve 三处消费点全部隐藏禁用插件的工具；分发前还有 `missingPermissionsForTool` 权限校验。
- skill 过滤在 SkillRouter.match 的 isAvailable 检查。

## 已知地雷

- 新增面板型插件三步缺一不可：leftSidebarPlugins.js 注册 + project-overview.vue 面板区加 v-else-if 分支 + 组件本身；漏第二步就是"加载中..."占位符（股东大会曾长期如此，现已实现）。
- **下线一个面板型插件只删 rail 那一条就够**：`v-else-if` 分支与组件留着不会被渲染（`leftPaneKey` 永远取不到那个值），删了反而让恢复变成重写。股东大会核查就是这么下的。
- **面板不要自画标题**：左栏标题由外壳的 `.sidebar-header` 统一出，面板里再画一份就是同屏出现两次（诉讼可视化/会议录音/股东大会核查都犯过）。面板内部只画分组头，密度用 `App.vue` 的 `--awd-panel-*` 令牌，见 sidebar-shell.md。
- skill 的 allowed_tools 写错工具名不会报错，只是白名单零命中回退不裁剪——排查工具可见性问题时先核对 ToolRegistry 真名。**部分**写错更阴险：剩下的名字还能命中，裁剪照常生效，写错的那个工具就静默消失了。
- `RealToolBeans.instantiateAll()`（评测用的工具 bean 清单）与生产的 `AgentToolComponent` 实现集**不是自动同步的**：`TodoTools` 就不在里面，所以 `todo_write` 在回放评测里根本没注册，评测断言不到它的可见性。新增工具组件时要顺手补进去。
- 插件启停语义只影响可见性，不拦截历史工具调用回放。
- **Web 插件的 `frontendEntry` 校验失败是静默降级**：指到 `web/` 之外或文件不存在时 `PluginService` 把它置空并记 WARN，前端表现为「未配置入口地址」的空面板——面板空白先查后端日志的这条 WARN，别去前端找。
- **`/api/plugin-web` 不要加登录闸**：iframe 是 opaque origin，带不出凭据，加了只会让面板白屏；那里也没有用户数据。禁用/未安装/被封禁一律 404（不是 403，不泄露 id 存在性）。
- 改 AgentOrchestrator 构造器（如注入新服务）必须同步 EvalHarness（踩过两次）。
- **「先落中间态再 `executor.submit`」的服务（会议转写就是），测试里断中间态必须先卡住后台线程**：`save` mock 成原样返回入参时，方法返回的对象与测试持有的是同一个可变实例，后台那个瞬间返回的 mock 会抢先把它改成终态，断言成败取决于 runner 调度（#394 修的就是这个间歇红）。用 `CountDownLatch` 卡住后台调用的那个 mock，断完中间态再 `countDown` 放行。
- SubAgentTools 曾因循环依赖断启动，用 @Lazy 解决（PR#98），插件/工具类注入编排器时注意。

## 验证

- 后端：`cd backend && mvn test`（JDK 21）。
- skill 触发链路：起后端后对话输入触发词验证注入；skill 管理 API `/api/skills/list|{id}/enable|{id}/disable|rescan`（admin）。
- 前端面板：`cd frontend && npm run test:app-e2e` 覆盖主要旅程。
