---
name: plugin-system
description: 插件系统领域（具体插件实现）。任务涉及尽调/脱敏/股东大会等业务插件、skill 定义与注入、PluginService/SkillRegistry/SkillRouter、动态 JAR 插件时，先读本文档再动代码。
---

# 插件系统 领域地图

职责边界：各具体业务插件与插件/skill 运行机制。不含插件市场页与 registry 同步（plugin-marketplace 领域），不含左栏 UI 本身（sidebar-shell 领域）。

## 三类插件形态

1. **内置面板型**：前端组件直接内嵌 project-overview.vue 面板区，无启停开关，靠 `leftSidebarPlugins.js` 静态配置 + 角色过滤（`getPluginsForUser`，CLIENT 角色只见 dd-files）。
2. **Skill 型**：后端 prompt 包（skill.yml + prompt.md），对话关键词触发。
3. **动态 JAR 插件**：plugins/ 目录下 manifest.json + JAR，前端用 PluginPane iframe 承载。

## 现有插件清单

**尽调（DD）**：前端 `frontend/src/components/DdFilesPanel.vue` + `DdRequestEditor.vue`；后端 `controller/DdController.java`（/api/dd）+ `service/DdService.java`；实体 DdRequest/DdItem/DdComment + 对应 Repository。无 skill。

**脱敏**：前端 `frontend/src/components/DesensitizePane.vue`；后端 `controller/SensitiveController.java`（/api/sensitive：GET /options、POST /desensitize）+ `service/SensitiveService.java`（PDFBox PDFTextStripper 定位涂黑）+ OcrService 辅助。无 skill。

**股东大会核查**：面板 + AI 编排混合型（三层齐备）。前端 `frontend/src/components/ShareholderMeetingPanel.vue`（会话列表/五组材料槽位/巨潮拉取/开始核查，选文件用 FilePickerDialog 的 accept 过滤）；后端 `controller/ShareholderMeetingController.java`（/api/shareholder-meeting）+ `service/ShareholderMeetingService.java`（底稿夹 `股东大会核查/<公司>_<届次>/01..05` 五子目录、材料复制幂等、kick-off prompt 组装）+ `service/CninfoAnnouncementService.java`（巨潮拉取，挑选启发式移植自内核 skill 且有单测锁定）；skill `backend/skills/shareholder-meeting-verification/`。执行链路：面板 start 接口返回 prompt（以触发词「股东大会核查」开头）→ project-overview 经 `ChatInterface.sendExternalPrompt`（expose）以 AGENT 模式发送 → skill 注入 → AI 用 extract_file_text/run_python/write_docx（带 parentFolderId）产出核查底稿表与法律意见书到 04/05 子目录。**地雷**：pinnedSkillId 只裁剪工具不注入 prompt，触发词必须在 prompt 文本里；ASK 模式跳过注入。

**上市路径选择（Skill 型）**：`backend/skills/listing-pathway/`（skill.yml + prompt.md）。无独立面板，对话触发。

**诉讼可视化**：面板 + skill + 专用工具三层齐备，详见 `.claude/agents/litigation-visual.md`。skill 在 `backend/skills/litigation-visual/`。

**会议录音**：面板 + skill + 专用工具三层齐备（2026-08-14）。前端 `frontend/src/components/MeetingRecordingPanel.vue`（一键录音/列表/转写稿/说话人改名/生成纪要）+ **模块级录音单例** `frontend/src/utils/meetingRecorder.js`（MediaRecorder 5s 分片边录边追加上传，页面跳转不断录）+ 跨页面浮动指示器 `utils/recordingIndicator.js`→`MeetingRecordingIndicator.vue`（body 级挂载，feedbackWidget 同模式）；后端 `controller/MeetingRecordingController.java`（/api/meetings）+ `service/meeting/`（MeetingRecordingService 生命周期、MeetingTranscriptionService 转写编排：JavaCV 转码 mp3 → OSS 签名 URL → 通义听悟 CreateTask（说话人分离 SpeakerCount=0 + 章节/摘要/待办）→ **poll-on-read** 收结果、TingwuClient/MeetingOssClient 接口+SDK 实现、MeetingTranscriptParser 纯函数解析）；工具 `service/ai/tools/MeetingTools.java`（meeting_list_recordings/meeting_get_transcript）；skill `backend/skills/meeting-recorder/`（`enabled_by_default:false`，广场启停，触发词「会议纪要」）。凭证五件套（AK/SK/听悟 AppKey/OSS bucket/endpoint）存 system_setting `meeting.asr.*`/`meeting.oss.*`，admin 页「会议转写」卡片可改（AdminConfigController TingwuConfig）；未配置时录音存档可用、转写降级提示。**地雷**：听悟只收公网 URL（必须 OSS 中转，转写完即删）；kick-off prompt 以「会议纪要」开头；录音单例绝不能搬进页面组件（reLaunch 即断录）。

**动态 JAR**：前端 `frontend/src/components/PluginPane.vue`（纯 iframe 壳：props url/pluginId，url 空则报"未配置入口地址"；加载哪个插件由父页面按 leftPaneKey + dynamicPlugins[].frontendEntry 决定）；后端 `service/ai/PluginService.java` + `controller/ai/PluginController.java`（/api/plugins）。

## 注册与加载链路

1. 静态注册：`frontend/src/config/leftSidebarPlugins.js` 导出 LEFT_SIDEBAR_PLUGINS + `getPluginsForUser(role)`。
2. project-overview.vue 计算属性（~:1581）合并静态列表与 dynamicPlugins。
3. 动态插件：`loadDynamicPlugins()`（~:6261）调 `GET /api/plugins/list`，映射成 `{key:'plugin-<id>', label, icon, isDynamic, frontendEntry}`。
4. 面板分发（~:531-563）按 leftPaneKey：dd-files→DdFilesPanel、desensitize→DesensitizePane、easyvoice→EasyVoicePane、search→SearchPanel、files→文件树、动态→PluginPane、其余→占位符。
5. 后端 PluginService：@PostConstruct 扫 `plugins/`（可配 `ai.plugins.dir`），读 manifest.json → PluginMetadata；有 backendJars 时独立 URLClassLoader 加载，扫 langchain4j @Tool 类注册进 ToolRegistry。`POST /api/plugins/rescan` 热重扫。

## skill 文件格式（docs/SKILL_SPEC.md、docs/PLUGIN_SPEC.md）

目录式：`skills/<id>/skill.yml + prompt.md`。skill.yml 字段：`id`（必需，kebab-case，启停键）、`name`、`description`、`triggers`（必需，关键词数组，用户输入"包含"即命中）、`prompt`（默认 prompt.md）、`allowed_tools`（须为 ToolRegistry 真实工具名）、`output`、`requires`（如 evidence.retrieve.v1，v1 仅声明）。未知字段忽略；解析失败跳过不阻断。

插件携带 skill：manifest.json `skills` 字段列子目录名，PluginService 只收集目录（`getPluginSkillDirs()`），解析/启停归 SkillRegistry，记 sourcePluginId。

manifest.json 要点：id（必需）/name/version/icon/author/permissions（file_read/file_write/network/editor）/tools[]/frontendEntry/backendJars[]/skills[]。

## skill 注入对话链路（backend/src/main/java/com/checkba/service/ai/skill/）

- `SkillRegistry.java` — 发现/加载：扫内置 skills/ 目录 + 插件携带目录，SnakeYAML 解析，id 去重（先扫到优先）；`isAvailable` = 自身启用 且 所属插件未禁用。
- `SkillRouter.java` — `match(userInput)` 取最长命中关键词；`activateForTurn` 每条用户消息刷新命中态；`visibleTools` 命中时裁剪为 allowed_tools ∪ baseTools ∪ `ORCHESTRATION_TOOLS`（业务工具零命中则不裁剪）；`promptInjectionFor` 拼 prompt 注入。
- 编排接入（纯旁路两处）：`AgentOrchestrator.java` activateForTurn（~:255）+ visibleTools（~:709）；`ContextAssemblerService.java` match→promptInjectionFor（~:146）。ASK 模式跳过注入。
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
- skill 的 allowed_tools 写错工具名不会报错，只是白名单零命中回退不裁剪——排查工具可见性问题时先核对 ToolRegistry 真名。**部分**写错更阴险：剩下的名字还能命中，裁剪照常生效，写错的那个工具就静默消失了。
- `RealToolBeans.instantiateAll()`（评测用的工具 bean 清单）与生产的 `AgentToolComponent` 实现集**不是自动同步的**：`TodoTools` 就不在里面，所以 `todo_write` 在回放评测里根本没注册，评测断言不到它的可见性。新增工具组件时要顺手补进去。
- 插件启停语义只影响可见性，不拦截历史工具调用回放。
- 改 AgentOrchestrator 构造器（如注入新服务）必须同步 EvalHarness（踩过两次）。
- SubAgentTools 曾因循环依赖断启动，用 @Lazy 解决（PR#98），插件/工具类注入编排器时注意。

## 验证

- 后端：`cd backend && mvn test`（JDK 21）。
- skill 触发链路：起后端后对话输入触发词验证注入；skill 管理 API `/api/skills/list|{id}/enable|{id}/disable|rescan`（admin）。
- 前端面板：`cd frontend && npm run test:app-e2e` 覆盖主要旅程。
