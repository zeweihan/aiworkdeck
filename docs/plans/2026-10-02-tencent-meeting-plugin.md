# 腾讯会议官方插件实施计划 (Tencent Meeting Official Plugin)

> **For Claude / Agent:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 AI WorkDeck 开发官方腾讯会议插件，接入官方 `tmeet` CLI，支持扫码登录、定时同步会议、在右侧/中栏工作台标签页查看交互式逐字稿与智能纪要，并结合内置 AI 提取 ToDo List（落入日程）及生成专业会议纪要。

**Architecture:** 
- **后端**：`TmeetCliService` 封装官方 `tmeet` CLI 调用（扫码授权/状态探测/会议拉取/逐字稿段落解析/智能纪要获取）；`TencentMeetingService` 处理同步生命周期、去重过滤、文档导出与 AI kick-off 提示词构建；`TencentMeetingScheduler` 处理后台定时同步；`TencentMeetingController` 暴露 REST 接口；`TencentMeetingTools` 提供 LangChain4j AI 工具；内置 `tencent-meeting` Skill 注入专业纪要与待办提取指令。
- **前端**：左侧栏 rail 入口 `tmeet`（requiresSkill 门控）；`TencentMeetingPanel.vue` 展示登录状态/扫码授权/定时同步设置/会议卡片列表；工作台标签页 `TencentMeetingTranscriptPane.vue`（`tabType: 'tmeet-transcript'`）提供时间轴逐字稿、说话人标记、全文检索与一键 AI 分析入口；`project-overview.vue` 调度 AI 生成纪要与 ToDo List。

**Tech Stack:** Java 21, Spring Boot, JPA/Hibernate, uni-app/Vue 3, tmeet CLI, LangChain4j.

---

### Task 1: 后端实体与持久化层 (Entities & Repositories)

**Files:**
- Create: `backend/src/main/java/com/checkba/model/entity/TencentMeetingRecord.java`
- Create: `backend/src/main/java/com/checkba/model/entity/TencentMeetingSyncConfig.java`
- Create: `backend/src/main/java/com/checkba/repository/TencentMeetingRecordRepository.java`
- Create: `backend/src/main/java/com/checkba/repository/TencentMeetingSyncConfigRepository.java`
- Test: `backend/src/test/java/com/checkba/repository/TencentMeetingRecordRepositoryTest.java`

**Step 1: 编写实体与 Repository 单元测试**
**Step 2: 运行测试验证失败**
**Step 3: 编写实体与 Repository 实现（包含 SPDX 头）**
**Step 4: 运行测试验证通过**
**Step 5: 提交代码**

---

### Task 2: 后端 tmeet CLI 封装层 (TmeetCliService)

**Files:**
- Create: `backend/src/main/java/com/checkba/service/tmeet/TmeetCliService.java`
- Create: `backend/src/main/java/com/checkba/service/tmeet/dto/TmeetAuthStatus.java`
- Create: `backend/src/main/java/com/checkba/service/tmeet/dto/TmeetMeetingItem.java`
- Create: `backend/src/main/java/com/checkba/service/tmeet/dto/TmeetParagraph.java`
- Test: `backend/src/test/java/com/checkba/service/tmeet/TmeetCliServiceTest.java`

**Step 1: 编写 TmeetCliService 单元测试（测试路径定位、状态解析、输出反序列化等）**
**Step 2: 运行测试验证失败**
**Step 3: 编写 TmeetCliService 核心逻辑（执行 CLI、提取授权 URL、处理错误、超时保护）**
**Step 4: 运行测试验证通过**
**Step 5: 提交代码**

---

### Task 3: 后端业务逻辑与定时任务 (TencentMeetingService & Scheduler)

**Files:**
- Create: `backend/src/main/java/com/checkba/service/tmeet/TencentMeetingService.java`
- Create: `backend/src/main/java/com/checkba/service/tmeet/TencentMeetingScheduler.java`
- Create: `backend/src/main/java/com/checkba/controller/TencentMeetingController.java`
- Test: `backend/src/test/java/com/checkba/service/tmeet/TencentMeetingServiceTest.java`
- Test: `backend/src/test/java/com/checkba/controller/TencentMeetingControllerTest.java`

**Step 1: 编写 Controller 与 Service 测试**
**Step 2: 运行测试验证失败**
**Step 3: 编写业务服务、同步逻辑、Kickoff Prompt 构造器与 Controller**
**Step 4: 运行测试验证通过**
**Step 5: 提交代码**

---

### Task 4: AI 编排集成：专用工具与 Skill 定义 (TencentMeetingTools & Skill)

**Files:**
- Create: `backend/src/main/java/com/checkba/service/ai/tools/TencentMeetingTools.java`
- Create: `backend/skills/tencent-meeting/skill.yml`
- Create: `backend/skills/tencent-meeting/prompt.md`
- Test: `backend/src/test/java/com/checkba/service/ai/tools/TencentMeetingToolsTest.java`
- Test: `backend/src/test/java/com/checkba/service/ai/skill/TencentMeetingSkillTest.java`

**Step 1: 编写 AI 工具与 Skill 测试（校验 allowed_tools 包含编辑面与 task_create、触发词匹配）**
**Step 2: 运行测试验证失败**
**Step 3: 实现 TencentMeetingTools 并注册，添加 skill.yml 和 prompt.md**
**Step 4: 运行测试验证通过**
**Step 5: 提交代码**

---

### Task 5: 前端左栏插件入口与面板 (leftSidebarPlugins & TencentMeetingPanel.vue)

**Files:**
- Modify: `frontend/src/config/leftSidebarPlugins.js`
- Modify: `frontend/src/config/icons.js`
- Modify: `frontend/src/services/api.js`
- Create: `frontend/src/components/tmeet/TencentMeetingPanel.vue`
- Modify: `frontend/src/locales/zh-CN.json`
- Modify: `frontend/src/locales/en-US.json`
- Test: `frontend/tests/tmeet/left-sidebar-tmeet.test.mjs`

**Step 1: 编写侧边栏插件注册与过滤单测**
**Step 2: 编写 api.js 接口，配置 rail 图标与文案**
**Step 3: 实现 TencentMeetingPanel.vue（扫码/登录态、同步配置、会议列表与快捷操作）**
**Step 4: 运行测试验证通过**
**Step 5: 提交代码**

---

### Task 6: 前端右侧/中栏逐字稿标签页 (TencentMeetingTranscriptPane.vue & Workbench 集成)

**Files:**
- Create: `frontend/src/components/tmeet/TencentMeetingTranscriptPane.vue`
- Modify: `frontend/src/pages/project-overview/fileKind.js`
- Modify: `frontend/src/pages/project-overview/tabSnapshot.js`
- Modify: `frontend/src/pages/project-overview/fileOpenTabs.js`
- Modify: `frontend/src/pages/project-overview/project-overview.vue`
- Test: `frontend/tests/tmeet/tmeet-tab.test.mjs`

**Step 1: 编写标签页打开与快照还原测试**
**Step 2: 实现 TencentMeetingTranscriptPane.vue（时间轴逐字稿、智能纪要、搜索过滤、AI 纪要/待办派发）**
**Step 3: 在 project-overview.vue 中接入面板与标签页渲染分支，连接 AI External Prompt 链路**
**Step 4: 运行测试验证通过**
**Step 5: 提交代码**

---

### Task 7: 完整端到端走查、截图验收与 PR 合并

**Files:**
- 运行全量前后端单测
- 启动工作台进行渲染走查
- 捕获插件面板及逐字稿标签页真实截图
- 校验许可证头与规范（SPDX）
- 提交所有改动，合并到主分支并汇报给用户
