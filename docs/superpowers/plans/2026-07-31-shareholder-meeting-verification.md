# 股东大会核查功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把左栏「股东大会」占位插件实现为完整功能：材料管理面板 + AI 编排核查，产出法律意见书 + 底稿夹。

**Architecture:** 面板（材料槽位/会话管理）→ 后端准备接口（底稿夹 + 材料复制 + kick-off prompt）→ AI 聊天面板 AGENT 模式发送（skill 触发词注入）→ AI 用 extract_file_text/run_python/write_docx 完成交叉核对与文书生成。

**Tech Stack:** Spring Boot（JDK 21）+ JPA/H2 + Tika + HttpClient；uni-app/Vue3；skill.yml + prompt.md。

## Global Constraints

- 本机 `mvn` 必须 JDK 21（系统默认 25 会 SIGBUS）。
- 前端 npm（不是 pnpm）。
- worktree 内编辑与构建同树。
- 全局禁 emoji（代码/UI/文档/commit）。
- 不改 AgentOrchestrator 构造器（EvalHarness 地雷）。
- 后端写文件走 StorageService 路线（AiDocxExportService 模式），不直接 Files 写盘。
- skill allowed_tools 逐一核对 ToolRegistry 真名；新工具同步前端工具名中文映射表。
- 合并 PR 时同步更新 `.claude/agents/plugin-system.md`（维护规则）。

---

### Task 1: Tika 文本抽取 Service + extract_file_text 工具

**Files:**
- Create: `backend/src/main/java/com/checkba/service/DocumentTextService.java`
- Modify: `backend/src/main/java/com/checkba/controller/FileController.java`（extractDocumentText 改调新 Service）
- Modify: `backend/src/main/java/com/checkba/service/ai/tools/FileTools.java`（新增 @Tool extract_file_text）
- Modify: 前端工具名中文映射表（ChatInterface 或对应 map 文件，grep `todo_write` 定位）
- Test: `backend/src/test/java/com/checkba/service/DocumentTextServiceTest.java`

**Interfaces:**
- Produces: `DocumentTextService.extractText(ProjectFile file): String`（Tika parseToString，内部 StorageServiceFactory.load）；工具 `extract_file_text(fileId 或 path)` 返回全文（超长截断带提示，上限 ~80k chars）。

- [ ] 写测试：POI 造一个内存 docx/xlsx，Tika 抽文本断言包含预期字符串
- [ ] 实现 Service；FileController 改调；FileTools 加 @Tool（@ToolMeta 只读）
- [ ] 前端映射表加「提取文档文本」
- [ ] `mvn test -pl . -Dtest=DocumentTextServiceTest`（JDK 21）通过后 commit

### Task 2: ShareholderMeetingCheck 实体 + Repository + Controller CRUD

**Files:**
- Create: `backend/src/main/java/com/checkba/model/entity/ShareholderMeetingCheck.java`
- Create: `backend/src/main/java/com/checkba/repository/ShareholderMeetingCheckRepository.java`
- Create: `backend/src/main/java/com/checkba/controller/ShareholderMeetingController.java`
- Create: `backend/src/main/java/com/checkba/service/ShareholderMeetingService.java`

**Interfaces:**
- 实体字段：id, projectId, companyName, stockCode, meetingName, meetingDate(LocalDate), status(String: DRAFT/READY/RUNNING/DONE), noticeFileId, resolutionFileId, voteResultFileIds(String JSON), templateFileId, otherFileIds(String JSON), conversationId, workpaperFolderId, createdBy, createdAt, updatedAt
- API（均带 requireMember 鉴权，抄 DdController）：
  - `GET /api/shareholder-meeting/projects/{projectId}` 列表
  - `POST /api/shareholder-meeting/projects/{projectId}` 创建
  - `PUT /api/shareholder-meeting/{checkId}` 更新基本信息/材料引用
  - `DELETE /api/shareholder-meeting/{checkId}`
  - `POST /api/shareholder-meeting/{checkId}/materials` body {slot: notice|resolution|voteResult|template|other, fileId}（关联项目已有文件；追加语义 for voteResult/other）
  - `DELETE /api/shareholder-meeting/{checkId}/materials?slot=&fileId=`

- [ ] 实体 + Repository + Service + Controller（DTO 用 Controller 内 static 类，风格对齐 DdController）
- [ ] `mvn test` 冒烟（上下文启动测试会验证 bean 装配）后 commit

### Task 3: CninfoAnnouncementService（巨潮拉取移植）

**Files:**
- Create: `backend/src/main/java/com/checkba/service/CninfoAnnouncementService.java`
- Modify: `ShareholderMeetingController`（`POST /{checkId}/fetch-cninfo`）
- Test: `backend/src/test/java/com/checkba/service/CninfoAnnouncementServiceTest.java`

**Interfaces:**
- `fetchAnnouncements(stockCode, market, meetingDate): FetchResult`（notice/resolution 元数据 + 候选列表 + errors）
- `pickShareholdersNotice(List<Announcement>, LocalDate)` / `pickBoardResolution(...)` 为纯函数，移植 Python 启发式（标题含「召开」+「股东」+「通知」；决议按“董事会+决议”评分、时间接近度）
- Controller 端点：拉取成功 → 下载 PDF 落底稿夹「01/02」子目录（走 ProjectFileService.createFile + StorageService.save）并自动填 noticeFileId/resolutionFileId；失败返回 errors 供面板提示上传

- [ ] 挑选启发式纯函数 + 单测（fixture JSON 造多候选/延期后通知等场景）
- [ ] HTTP 拉取（浏览器 UA；org_id 查 szse_stock.json）+ 下载落库
- [ ] `mvn test -Dtest=CninfoAnnouncementServiceTest` 通过后 commit

### Task 4: 「开始核查」准备接口 + kick-off prompt 组装

**Files:**
- Modify: `ShareholderMeetingService` + Controller（`POST /{checkId}/start`）
- Test: `backend/src/test/java/com/checkba/service/ShareholderMeetingPromptTest.java`

**Interfaces:**
- `start(checkId, userId): StartResult { prompt, workpaperFolderId }`
- 行为：建 `股东大会核查/<公司>_<届次>/01..05` 文件夹树（ProjectFileService.createFolder，幂等）；已关联材料 batchCopy 进对应子目录；组装 prompt：固定触发词「股东大会核查」开头 + 会议要素 + 材料清单（文件树路径 + fileId）+ 缺失材料声明 + 产出路径约定（04/05 子目录）；status → READY，记录 workpaperFolderId
- prompt 模板存 Service 内常量；conversationId 由前端发送后回写（`PUT /{checkId}/conversation`）

- [ ] prompt 组装单测（含缺通知/缺决议时的「未经交叉核对」声明分支）
- [ ] 文件夹树 + 复制 + start 端点实现
- [ ] `mvn test` 通过后 commit

### Task 5: Skill 文件

**Files:**
- Create: `backend/skills/shareholder-meeting-verification/skill.yml`
- Create: `backend/skills/shareholder-meeting-verification/prompt.md`

**Interfaces:**
- skill.yml：id `shareholder-meeting-verification`；triggers：股东大会核查、股东会核查、股东大会见证、见证法律意见书；allowed_tools：extract_file_text, read_file, list_files, search_project_files, write_docx, run_python, todo_write
- prompt.md（按 spec）：工作流七步（读材料→要素与议案清单→12 项字段交叉核对→议案三标签校验→表决复算→核查底稿表→意见书生成/填充）+ 内置意见书模板（金杜样例结构）+ 硬约束（可追溯/见证边界/未核对声明/规范用语/不臆断年份）+ 输出约定（写入 04、05 子目录）

- [ ] 写 skill.yml + prompt.md；起后端验证 `/api/skills/list` 出现且触发词命中注入
- [ ] commit

### Task 6: 前端 API 层 + FilePickerDialog accept 过滤

**Files:**
- Modify: `frontend/src/services/api.js`（shareholderMeeting 系列具名导出）
- Modify: `frontend/src/components/FilePickerDialog.vue`（新增 prop `accept: Array<String>` 扩展名过滤，空则不过滤）

- [ ] api.js：list/create/update/delete/attachMaterial/detachMaterial/fetchCninfo/start/bindConversation
- [ ] FilePickerDialog accept prop（过滤逻辑 + 不破坏 EasyVoice 现有用法）
- [ ] commit

### Task 7: ShareholderMeetingPanel.vue

**Files:**
- Create: `frontend/src/components/ShareholderMeetingPanel.vue`

**Interfaces:**
- props: `projectId [String, Number]`, `currentUser Object`；emits: `['start-verification', 'open-file']`
- 结构：会话列表（状态徽标）→ 新建表单（公司/代码/届次名/日期）→ 会话详情：材料槽位四组（通知/决议/投票结果多选/模板可选，各带「从项目选择」FilePickerDialog + 移除）、「从巨潮拉取」、「开始核查」（调 start → emit('start-verification', {prompt, check})）、产出区（底稿夹快捷打开 emit('open-file')）
- 上传不在面板内做（用户先传到文件树再关联，或用巨潮拉取），MVP 减复杂度

- [ ] 组件实现（样式对齐 DdFilesPanel 的 awd-* 体系）
- [ ] commit

### Task 8: project-overview 接入 + ChatInterface expose

**Files:**
- Modify: `frontend/src/components/ChatInterface.vue`（expose `sendExternalPrompt(prompt)`：AGENT 模式走 handleSubmit 等价路径）
- Modify: `frontend/src/pages/project-overview/project-overview.vue`（components 注册 + 面板分发区 v-else-if 分支 + handleShareholderStart/handleOpenFile handler）
- 检查 `/static/meeting_unselected.png` 与 `meeting_selected.png` 是否存在，缺则补 svgPaths

- [ ] ChatInterface expose sendExternalPrompt（强制 mode AGENT、复用现有 sendMessage 链路、发送后回写 conversationId 到 check）
- [ ] 分发区分支（插在 PluginPane 动态分支前）+ handler + 图标确认
- [ ] `npm run check:emits` 通过后 commit

### Task 9: 验证与收尾

- [ ] `cd backend && mvn test`（JDK 21）全绿
- [ ] `cd frontend && npm run build:h5` 无错
- [ ] 端到端：测试项目导入罗欣药业通知+决议 PDF + 自造投票结果 xlsx（POI 或 python 脚本造交易所网络投票统计格式），dev Electron 走完整链路；对照金杜内核后终稿检查产出意见书结构、票数、比例、特别决议标注
- [ ] 更新 `.claude/agents/plugin-system.md`（股东大会条目从占位改为实现描述 + extract_file_text 工具备注）
- [ ] PR：feat 分支 → master，标题「股东大会核查：面板+AI 编排+意见书与底稿生成」
