# 日历视图（任务/截止日系统）设计 spec

日期：2026-08-20。状态：用户已拍板（月历为主 / FullCalendar / 一次走完所有期，目标 v0.22.0）。
看板：dev-board #48（立项）及实施卡。

## 背景与决策

律师工作时间敏感：每个项目要有日历，每个人要有跨项目全盘日历，文件右键可设截止日
（起诉/答辩/开庭等）。调研结论：

- 概览页 A 期已预埋任务系统契约：`GET /api/projects/{projectId}/tasks` 恒空桩
  （ProjectOverviewController，注释约定 B 期只换实现、CRUD 另起 TaskController `/api/tasks`）；
  前端 `TaskSchedule.vue` 预设字段形状 `uid/status/dueDate`。本功能就是把 B 期建出来。
- 组件选型：FullCalendar（核心 MIT + 官方 `@fullcalendar/vue3`；月/周/list/interaction 均免费，
  不引入任何 premium 插件——resource 系列视图禁止使用，有许可证边界）。
- 节假日：组件不内置，用 `chinese-days`（MIT）叠背景标记。
- 用户决策：全局页月历为主、列表为辅。

## 数据模型

新表 `project_task`，JPA entity `ProjectTask`（`ddl-auto: update` 自动建表，PG/H2 双吃）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long IDENTITY | 主键 |
| uid | String(36) | UUID，创建时生成（对齐 ProjectFile.uid 惯例，供未来云同步） |
| projectId | Long, not null | 所属项目 |
| fileId | Long, nullable | 锚定文件（ProjectFile.id）；项目级事件为 null |
| title | String(500), not null | 事项标题 |
| dueDate | LocalDate, not null | 截止日 |
| dueTime | LocalTime, nullable | 具体时刻（如开庭 09:30），null=全天 |
| status | String(20) | `open` / `done` |
| source | String(20) | `user` / `ai`（AI 建议的任务标 ai，界面可区分） |
| userId | Long, not null | 创建者 |
| createdAt / updatedAt | LocalDateTime | 惯例字段 |

硬删除（无软删）。文件被删除时任务保留（fileId 悬空按 null 对待展示）。

## API 契约

鉴权一律 `X-Session-Id` + `getUserIdFromSession`，两种模式（local-mode/云端）透明。

- `GET /api/projects/{projectId}/tasks?from=&to=`（换掉恒空桩实现，from/to 可选，ISO 日期）
  → `[{id, uid, projectId, fileId, fileName, title, dueDate, dueTime, status, source}]`
  （fileName 由 join ProjectFile 得出，悬空为 null）
- `TaskController`：
  - `POST /api/tasks` body `{projectId, fileId?, title, dueDate, dueTime?}` → 创建，source=user
  - `PUT /api/tasks/{id}` body `{title?, dueDate?, dueTime?, status?}` → 更新（校验归属：任务的
    projectId 须属于当前用户可见项目，照现有 controller 的归属校验惯例）
  - `DELETE /api/tasks/{id}`
- `CalendarController`：`GET /api/calendar?from=&to=` → 跨项目聚合当前 userId 的任务，
  `[{...同上, projectName}]`。不新建表，聚合查询。

## 前端

依赖（npm，frontend/）：`@fullcalendar/core` `@fullcalendar/vue3` `@fullcalendar/daygrid`
`@fullcalendar/timegrid` `@fullcalendar/list` `@fullcalendar/interaction` `chinese-days`。

### 挂载点一：全局日历页（新路由）

- `pages/calendar/calendar.vue`，pages.json 注册（必须显式 `navigationStyle: custom`）。
- 项目列表页 `.content-header` 加「日历」入口按钮，`uni.navigateTo`（与旧个人中心按钮同模式）。
- 页面布局：月历为主（dayGridMonth 默认），工具栏可切 周(timeGridWeek)/列表(listMonth)；
  右侧或下方辅一条「近期截止」列表（按剩余天数排序，7 天内标红）。
- 事件着色按项目区分；事件点击弹编辑框（改期/完成/删除/跳转项目——跳转工作台用 reLaunch）；
  空白日期点击创建任务（选项目）；拖拽改期（eventDrop → PUT）。
- 节假日：chinese-days 标记法定节假日/调休补班，背景色+角标「休/班」；周末浅灰。
- 数据：`GET /api/calendar`，随视图区间 from/to 拉取。

### 挂载点二：项目内

- `TaskSchedule.vue`（ProjectHomePane 内）：换真数据（现有 `getProjectTasks`），
  按 dueDate 升序展示未完成 + 剩余天数徽标，可勾选完成、可删除，顶部「+ 添加」快捷创建。
- 左栏 rail 新面板「日历」：`leftSidebarPlugins.js` 注册 key=`calendar`（不做 requiresSkill 门控，
  内置功能），`project-overview.vue` sidebar-content 加分支，新组件
  `components/project-calendar/ProjectCalendarPane.vue`：窄栏（260px）不放月历网格，
  用 FullCalendar listMonth 列表视图 + 快捷创建；数据走项目级端点。

### 挂载点三：文件右键「设置截止日」

- `FileTree.vue` 右键菜单加一项（文件与文件夹都可设），照「管理标签」弹窗模式：
  弹窗含标题输入（默认预填文件名）、日期、可选时刻；提交 = `POST /api/tasks`（带 fileId）。
- 日期输入组件：新建 `components/AwdDatePicker.vue`，H5-only 产品面，内部用原生
  `<input type="date">`（Electron/Chromium 自带日历弹层），外观对齐 AwdSelect 的浅色专业风。
  时刻用 `<input type="time">` 同理。

### i18n

新增键统一放 `config.calendar.*` / `calendar.*` 命名空间，zh-CN 与 en-US 同步，
`npm run check:locales` 必须过。全局禁 emoji，浅色专业风。

## AI 工具（ai-chat 领域）

ToolRegistry 注册两个工具（读 .claude/agents/ai-chat.md 契约后按惯例接）：

- `task_create`：AI 为当前项目创建截止日任务（title/dueDate/dueTime/fileId 可选），source=ai。
- `task_list`：AI 查询当前项目任务列表（供「帮我看看最近有什么期限」类问题）。

不做「AI 自动扫描文档提取日期」的主动行为，只给 AI 工具能力（用户对话中要求时可用）。

## 测试与验证

- 后端：TaskController/CalendarController/聚合查询单测（照 MobileRelayStoreServiceTest 惯例），
  mvn test 全绿（JDK 21）。
- 前端：npm run build 过、check:locales / check:emits 过。
- 集成走查：真后端起本地实测三个挂载点链路（原语级测试不够，按 UI 链路验证惯例）。
- 发版前全量 app-e2e 由发版流程执行。

## 明确不做（YAGNI）

- 不做提醒推送/通知（后续另立项）。
- 不做多律师资源排班视图（FullCalendar premium，许可证不允许且无诉求）。
- 不做期限自动顺延计算（只做节假日可视标记；顺延规则法律上有歧义，需要单独产品设计）。
- 不做手机端同步（relay 机制现成，另立项接）。
- 不做重复/周期任务。
