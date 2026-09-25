# 事项（任务/日程）系统重做 spec

日期：2026-09-25。前身：`2026-08-20-calendar-view-design.md`（dev-board #48-#53，B 期把 project_task 建起来）
与 #872（设置页「我的待办」重做）。本 spec 是在那套骨架上的二次设计，**不推翻数据表，只扩展**。

## 背景：用户的原话与现状诊断

用户（执业律师，产品维护者）2026-09-25：「日历相关功能，日程管理，任务管理这些，又丑又难用，
包括文件和任务的关联啊之类的，@啊之类的，好像都没做，入口也不清晰。」

真机截图核对后（dev 前端接 5269 桌面后端）：

1. **入口散且弱**：全局日历只藏在项目列表页右上角四个小按钮之一（「日历」），工作台里是 rail 倒数第二个
   无文字图标，设置页里还有一个「我的待办」。没有任何一处告诉用户「今天/逾期有几件事」。
2. **丑**：日历页页头是浮着的「返回」胶囊压在标题上；FullCalendar 默认主题（蓝色 today、灰线）没接品牌令牌；
   事件是一坨浅色文字块，看不出类型、时间、项目；右栏「近期截止」是裸列表。弹窗字段只有事项/项目/日期/时间，
   状态切换是一个孤零零的灰按钮。
3. **难用**：四处清单（日历页右栏、工作台 rail 面板、概览页「日程与任务」、设置页「我的待办」）各画各的，
   交互不一致；Esc 关不掉弹窗；工作台 rail 面板用 FullCalendar listMonth 塞在 260px 里。
4. **模型太薄**：只有 title/dueDate/dueTime/status/fileId。没有类型（开庭/截止/会议）、备注、负责人、
   多文件关联、提醒。文件与任务只能在右键「设置截止日」时绑一个，之后既看不到文件上有任务，也无法从任务打开文件。
5. **@ 没做**：AI 输入框有 @文件（`MentionPicker.vue`，dev-board#794 K15），但事项本身不能 @文件 / @成员。
6. **没有提醒**：律师的开庭日、举证期限逾期就是事故，产品却一声不吭。

## 产品决策（已定，实施不再讨论）

- **统一叫「事项」**（英文 Task）。产品文案里「日程」指日历视图，「事项」指条目；「任务」一词只保留在代码与 AI 工具名里。
  AI 单轮的 `todo_write` 步骤条仍叫「进度」，边界不变（见 ai-chat.md）。
- **一个弹窗、一种行**：全产品只有一个 `TaskDialog.vue` 和一个 `TaskRow.vue`，四处清单全部复用。
- **文件 ↔ 事项双向可见**：事项上有文件芯片可直达文件；文件树上有事项的文件显示到期徽标；文件右键「添加事项…」直接开完整弹窗。
- **@ 关联**：事项标题与备注输入 `@` 弹出选择器，选文件 = 加入关联文件，选成员 = 设为负责人。文字里只留 `@名字` 纯文本，结构化关系落字段。
- **提醒走本机**：服务端只存「提前多久」，桌面端由前端定时计算并用系统通知（HTML5 Notification，Electron 直通）提醒；
  不新增出站请求（legal/PRIVACY.md 红线）。
- **入口三层**：项目列表页顶部事项概览条（逾期/今天/本周 + 直达）、工作台 rail「日程」徽标 + 头像菜单「我的日程」+ 命令面板、
  文件树到期徽标。设置页「我的待办」保留（#872 刚做），换用统一行。
- **不做**：周期重复、多人资源排班视图（FullCalendar premium）、期限自动顺延、手机端同步、Office 插件面。

## 一、数据模型（后端，卡 A）

`project_task` 追加列（`ddl-auto: update` 自动加列，全部可空，旧行按默认值解释）：

| 列 | 类型 | 说明 |
|---|---|---|
| type | String(20) | `DEADLINE`(截止日，默认) / `HEARING`(开庭) / `MEETING`(会议) / `TODO`(待办) / `OTHER` |
| priority | String(10) | `NORMAL`(默认) / `HIGH`(重要) |
| notes | String(4000) | 备注纯文本，可含 `@名字` |
| assignee_id | Long | 负责人 userId，必须是项目成员（含 owner）；null = 未指派 |
| remind_before | Integer | 提前多少分钟提醒；null = 不提醒。全天事项以当天 09:00 为基准 |

新表 `project_task_file`（实体 `ProjectTaskFile`）：`id`, `task_id`, `file_id`，唯一索引 (task_id, file_id)。
旧列 `file_id` 保留并继续维护 = 关联文件里的第一个（向后兼容 FileTree 右键、TaskTools 与旧客户端）。
删除事项时级联删关联行（服务层显式删，不靠 JPA cascade）。文件被删时关联行保留、展示为悬空（沿用现有 fileName=null 口径）。

### API（全部沿用 `X-Session-Id` 鉴权与现有归属校验）

响应 DTO 统一形状（`ProjectTaskService.toMap`）：

```json
{ "id":1, "uid":"…", "projectId":242, "projectName":"…（仅 /api/calendar 带）",
  "title":"…", "type":"HEARING", "priority":"HIGH", "notes":"…",
  "dueDate":"2026-10-12", "dueTime":"09:30", "status":"OPEN", "source":"user",
  "assigneeId":2, "assigneeName":"韩泽伟", "remindBefore":1440,
  "fileId":2391, "fileName":"起诉状.docx",
  "files":[{"fileId":2391,"fileName":"起诉状.docx"},{"fileId":2400,"fileName":"证据清单.xlsx"}],
  "createdAt":"…", "updatedAt":"…" }
```

- `POST /api/tasks` body 增加可选 `type, priority, notes, assigneeId, remindBefore, fileIds:[]`；仍接受旧 `fileId`（等价于 `fileIds:[fileId]`）。
- `PUT /api/tasks/{id}` 同上字段全部可选；`fileIds` 出现即整体替换关联集合；显式 `null` 清空 assigneeId/remindBefore/notes/dueTime。
- `GET /api/projects/{projectId}/tasks?from=&to=&fileId=`：新增可选 `fileId` 过滤（含 file_id 列与关联表任一命中）。
- `GET /api/calendar?from=&to=`：不变，响应带新字段。
- `GET /api/calendar/summary`：新增。返回 `{ overdue, today, week, nextDue: {…task 或 null} }`（当前用户可见项目内 OPEN 事项；
  week = 今天起 7 天内含今天）。给项目列表页概览条、rail 徽标用（rail 徽标按项目：`GET /api/projects/{id}/tasks/summary` 同形状）。
- 校验：type/priority 不在枚举 → 400；assigneeId 不是该项目成员 → 400；fileIds 任一不属于项目 → 400（复用 `validateFileInProject`）。

### AI 工具（`TaskTools.java`）

- `task_create` 增加可选参数 `type, notes, priority, fileIds(逗号分隔), assigneeId, remindBefore`。描述里给类型枚举与中文含义。
- 新增 `task_update(taskId, title?, dueDate?, dueTime?, status?, type?, notes?, priority?)`：AI 可「把开庭改到下周三」「标记已完成」。归属校验同 controller。
- `task_list` 输出行带类型中文、时间、负责人、关联文件名；空结果文案不变（不能空串）。
- 单测照 `TaskToolsTest` 惯例补齐。

## 二、前端基础件（卡 B）

### `components/calendar/taskUtils.js`（扩展，仍是唯一出处）

新增导出：
- `TASK_TYPES = ['DEADLINE','HEARING','MEETING','TODO','OTHER']`；`typeMeta(type) → { key, label(t), color, icon }`，color 用 `--awd-*` 令牌，
  五种类型五个色相（截止日=品牌绿、开庭=朱砂红、会议=竹月青、待办=灰、其他=浅褐）。**不再按项目着色**（`eventColors.js` 保留供项目芯片小圆点用）。
- `remindAtOf(task) → Date|null`：dueTime 有值取 dueDate+dueTime，否则 dueDate 09:00，减 remindBefore 分钟。
- `REMIND_OPTIONS = [null, 0, 30, 60, 1440, 4320, 10080]`（不提醒/准时/提前30分/1小时/1天/3天/1周），全天事项时隐藏 30/60。
- `dueBadge` 扩展：返回 `{ text, kind, time }`，text 规则：逾期 N 天 / 今天 / 明天 / N 天后 / M月D日；time 为 `HH:mm` 或空。
- `groupByDue(tasks, today) → { overdue, today, week, later, done }`（与 `personalCollections.groupTodos` 合并成一份：把那边的实现挪到这里、那边只 re-export）。

### `utils/taskStore.js`（新）

模块级响应式缓存，所有前端读写事项一律经它，保证四处清单与徽标同步：

```js
export const taskStore = reactive({ byProject: {}, global: { list: [], summary: null, loadedAt: 0 } })
export async function loadProjectTasks(projectId, { force } = {})   // GET /api/projects/{id}/tasks（不带 from/to，全量）
export async function loadGlobal({ from, to, force } = {})          // GET /api/calendar
export async function loadSummary()                                 // GET /api/calendar/summary
export async function createTask(body) / updateTask(id, patch) / deleteTask(id)   // 写后就地更新 byProject 与 global，并 bump summary
export function tasksForFile(projectId, fileId)                     // FileTree 徽标用
export function subscribe(fn) → unsubscribe                         // 变更广播（rail 徽标、通知调度）
```

API 函数放 `services/api.js`（现有 `getProjectTasks`/`createTask`… 旁边补 summary 与 fileId 过滤）。

### `components/calendar/TaskRow.vue`（新，唯一的事项行）

props：`task`, `showProject`(bool), `showFiles`(bool, 默认 true), `density`('normal'|'compact')。
emits：`toggle`(task), `open`(task), `open-file`({task, fileId}), `open-project`(task), `delete`(task)。
结构：左侧圆形勾选框（完成动画 150ms）→ 类型色条 + 类型图标 → 标题（HIGH 前置小旗）→ 第二行芯片：项目（showProject）、
文件（可点，最多显 2 个 +N）、负责人（头像首字）→ 右侧到期徽标（kind 决定色：overdue 红底/today 绿底/soon 琥珀/later 灰）+ 提醒铃小图标（remindBefore 非空）。
hover 露出「编辑 / 删除」两个 icon 按钮。已完成行标题划线、整行 60% 透明。根类名 `task-row`，e2e 锚点 `data-task-id`。

### `components/calendar/MentionInput.vue`（新）

基于原生 `<textarea>`（单行模式 `rows=1` 自动增高）而不是 contenteditable。props：`modelValue`, `placeholder`, `files:[]`, `members:[]`,
`multiline`。行为：光标前出现 `@` 开始收集查询词 → 弹 `MentionPicker`（复用 `AgentMessage/MentionPicker.vue`，**给它加 `item.kind`
=`'file'|'member'` 的可选渲染分支**：member 显示头像首字+姓名+角色；不改现有 chat 用法）→ 上下键/Enter 选中 → 文本里替换为 `@名字 `，
emit `mention-file(file)` / `mention-member(member)`。Esc 只关选择器不冒泡。文件匹配复用 `utils/aiContextFiles.js` 的 `matchProjectFiles`
与 `excludeSystemFolders`；成员匹配按 displayName/username 前缀。

### `components/calendar/TaskDialog.vue`（重做，唯一的事项弹窗）

props：`visible`, `mode`('create'|'edit'), `task`(edit 用), `projectId`(锁定项目时), `projects:[]`(全局创建时选), `presetFileIds:[]`,
`presetDate`, `presetTime`。宿主给 `projectId` 后弹窗自己拉 `GET /api/projects/{id}/files`（复用 `aiContextFiles` 的加载口径）与 members。
布局（宽 560px，浅色专业风，全部 `--awd-*` 令牌，无 emoji）：

1. 标题输入（MentionInput 单行，自动聚焦，placeholder「事项名称，输入 @ 关联文件或成员」）
2. 类型分段选择（5 个芯片，选中用类型色）+ 右侧「重要」开关（priority）
3. 一行三列：日期（AwdDatePicker）| 时间（可选，AwdDatePicker time，清除按钮）| 提醒（AwdSelect，REMIND_OPTIONS）
4. 项目（仅全局创建：AwdSelect，可写项目 `writableProjects`）| 负责人（AwdSelect，成员列表，默认「未指派」）
5. 关联文件：芯片列表（可移除）+「添加文件」按钮 → 复用 `components/FilePickerDialog.vue`（若其接口不合，加最小 props，不复制一份）
6. 备注（MentionInput 多行，3 行起）
7. 页脚：左「删除」（edit）、「打开文件 / 进入项目」；右「取消」「保存」。Esc 关闭；Cmd/Ctrl+Enter 保存；保存中禁双击。

emit：`saved(task)`, `deleted(task)`, `open-project(task)`, `open-file({task,fileId})`, `close`。写操作走 `taskStore`。
把 `presetFileIds` 对应的文件预置为芯片。选择器里选到文件时同步加进芯片；选到成员设负责人。

### `utils/taskReminders.js`（新）

`startTaskReminders()`（幂等，App 生命周期内单例）：每 5 分钟 + `taskStore.subscribe` 触发时，对 `loadGlobal({from: today-30, to: today+14})`
的 OPEN 事项算 `remindAtOf`，`now >= remindAt` 且未通知过（localStorage `awd_task_notified` = `{ [uid]: remindAtISO }`）→
`new Notification(typeLabel + '：' + title, { body: projectName + ' · ' + 日期 时间, tag: uid })`，点击 → `uni.navigateTo('/pages/calendar/calendar?focus=' + id)`。
`Notification.permission` 为 `default` 时先 `requestPermission()`；被拒或非安全上下文时降级为应用内 toast（`AwdToastHost`）。
另暴露 `todayDigest()`：返回逾期/今天数量，项目列表页启动时若 >0 显示一次「今天有 N 件事项，M 件已逾期」toast（每天一次，localStorage 记日期）。

### i18n

新键全部放 `calendar` 命名空间（`locales/zh-CN/calendar.js` + `en-US/calendar.js`），`npm run check:locales` 必过。
卡 B **一次把后续各卡要用的键都加齐**（类型名、提醒选项、分组名、弹窗字段、入口文案、概览条、通知文案、空态文案），
后续卡缺键再补，补前先重读文件。

## 三、全局日程页重做（卡 D，`pages/calendar/calendar.vue` + `UpcomingList.vue`）

- 页头改为正经的页面头（同项目列表页 `.content-header` 的字号与留白）：左「← 返回」文字按钮 + 标题「日程」；中间 FullCalendar 的
  prev/today/next + 「2026 年 9 月」；右侧视图切换（月/周/列表分段）+ 筛选（项目多选、类型多选、「含已完成」开关，筛选收进一个「筛选」下拉）+ 主按钮「新建事项」。
  FullCalendar 自带 headerToolbar 关掉（`headerToolbar: false`），导航按钮由页面自己画并调 `calendarApi`。
- FullCalendar 主题接令牌：去掉默认蓝；today 底色 `--awd-mint` 极淡；格线 `--awd-border`；周末底色略深；节假日「休/班」角标保留；
  事件渲染用 `eventContent` 自定义：类型色左条 + `HH:mm`（有时间才显）+ 标题 + HIGH 小旗；已完成事件灰化划线；hover 显示原生 title（项目 · 文件名）。
- 月视图每格最多 3 条，多的「+N」走 FullCalendar `dayMaxEvents: 3` 弹层。
- 右栏改成「议程」：分组 已逾期 / 今天 / 本周 / 之后（`groupByDue`），每组用 `TaskRow`（showProject），已完成折叠；顶部小统计（逾期 N · 今天 N · 本周 N）。
  点行 → 编辑弹窗；点文件芯片 → `reLaunch` 工作台 `?id=pid&openFileId=fid`；进入项目 → `reLaunch` 工作台。
- 空态：日历有事项前，右栏显示引导卡（三种创建方式：这里新建 / 文件右键「添加事项」/ 对 AI 说「下周三开庭记一下」）。
- `?focus=<id>` 深链：打开即定位到该事项所在月并弹出编辑。
- 交互保留：点空白日建（带 presetDate；周视图带 presetTime）、拖拽改期、事件点击编辑。`loadSeq` 竞态护栏保留。

## 四、入口重整（卡 E1 工作台侧、卡 E2 列表页与两处清单）

### E2：项目列表页 `pages/project-list/project-list.vue`

- 现在的「20 全部项目」大统计卡改成 **概览条**：四格 `项目 N | 已逾期 N | 今天 N | 本周 N`（逾期 >0 红字），右侧「查看日程 →」；
  数据 `loadSummary()`。点逾期/今天/本周任一格 → 进日程页并把右栏滚到对应分组（query `?group=overdue|today|week`）。
- 顶栏「日历」按钮改「日程」并带徽标（overdue+today，0 不显）。
- 启动时 `todayDigest()` toast（每天一次）。
- `TaskSchedule.vue`（概览页块）与 `PersonalTodosPanel.vue`（设置页）换用 `TaskRow` + 统一弹窗；保留根类名 `task-schedule` / `panel-todos`
  （app-e2e 锚点，见 #981）。TaskSchedule 的「+ 添加」改开 TaskDialog（projectId 锁定）而不是内联两个输入框。

### E1：工作台 `pages/project-overview/project-overview.vue` 等

- rail `calendar` 项：label 改「日程」；按钮右上角徽标 = 当前项目 overdue+today（`taskStore` 订阅，0 不显）。徽标样式与 rail 现有任何计数一致，没有就新做一个 6px 圆点 + 数字。
- `ProjectCalendarPane.vue` 重做：去掉 FullCalendar listMonth，改成议程式：顶部「新建事项」按钮 + 迷你月份条（上/下月、月份名、只是过滤范围不是网格），
  下面 已逾期/今天/本周/之后 分组的 `TaskRow`（compact，不显项目），已完成折叠；底部「查看全盘日程」（保留 `leave-workbench` 出栈刷写口径，#489）。
  点行开 TaskDialog（宿主 project-overview 挂一个全局 `TaskDialog`，面板与文件树共用）。
- 头像下拉菜单加「我的日程」（在「设置」上方），走 `leave-workbench` 同款 flush 后 `navigateTo` 日程页。
- 命令面板/菜单 `config/commands/go.js` 加 `go.calendar`（「日程」，when workbench）→ `wb:goCalendar`；`file.js` 或 `tools.js` 加 `task.new`
  （「新建事项…」，when workbench+project）→ `wb:newTask` 打开 TaskDialog。命令注册表测试 `npm run test:commands` 必过。
- `FileTree.vue`：右键「设置截止日」改为「添加事项…」→ 开全局 TaskDialog（presetFileIds=[该文件]），删掉旧的内联小弹窗；文件有 OPEN 事项时右键多一项
  「查看事项 (N)」→ 打开 rail 日程面板并按该文件过滤；文件行名字右侧显示最近到期徽标（`tasksForFile`，kind 色同 TaskRow，紧凑 10px 字）。
- 工作台挂载时 `startTaskReminders()`（列表页也调，幂等）。

## 五、验证

- 后端：`mvn -q test -Dtest='TaskControllerTest,CalendarControllerTest,ProjectTaskServiceTest,TaskToolsTest'`（JDK 21）。
- 前端：新增 node 测试 `tests/calendar/`（taskUtils 分组与提醒时刻、taskStore 就地更新、MentionInput 查询词提取、TaskRow 渲染）+
  `npm run check:locales` + `npm run check:emits` + `npm run test:commands` + `npm run test:project-home` + `npm run build:h5`。
- 真渲染走查：dev 前端接 5269 桌面后端，puppeteer 截图：项目列表概览条、日程页月/周/列表 + 弹窗（含 @ 选择器）、工作台 rail 徽标 + 面板、
  文件树徽标 + 右键、设置页待办。中英各一遍。
- app-e2e：不在本批新增旅程，但 `.task-schedule`/`.panel-todos` 锚点不能丢；发版前全量跑。
