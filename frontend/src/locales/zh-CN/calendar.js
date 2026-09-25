// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 日历/任务系统（全局日历页 pages/calendar、项目内日历面板、文件右键设截止日、TaskSchedule 扩展）
// spec: docs/superpowers/specs/2026-08-20-calendar-view-design.md
export default {
  pageTitle: '日历',
  backToProjects: '返回项目',
  today: '今天',
  viewMonth: '月',
  viewWeek: '周',
  viewList: '列表',
  loading: '加载中...',
  loadFailed: '加载失败，请稍后重试',

  // 近期截止列表（全局页侧栏）
  upcomingTitle: '近期截止',
  upcomingEmpty: '暂无未完成的截止事项',
  dueToday: '今天到期',
  daysLeft: '{count} 天后',
  overdueDays: '已逾期 {count} 天',

  // 任务创建/编辑弹窗
  createTask: '新建日程',
  editTask: '编辑日程',
  taskTitleLabel: '事项',
  taskTitlePlaceholder: '如：提交答辩状、开庭',
  projectLabel: '项目',
  selectProject: '选择项目',
  dateLabel: '日期',
  timeLabel: '时间（可选）',
  fileLabel: '关联文件',
  noLinkedFile: '无',
  statusOpen: '未完成',
  statusDone: '已完成',
  markDone: '标记完成',
  markOpen: '恢复未完成',
  save: '保存',
  cancel: '取消',
  delete: '删除',
  deleteConfirmTitle: '删除日程',
  deleteConfirmContent: '确定删除「{title}」吗？',
  requiredTitle: '请填写事项名称',
  requiredDate: '请选择日期',
  requiredProject: '请选择项目',
  saved: '已保存',
  deleted: '已删除',
  saveFailed: '保存失败',
  deleteFailed: '删除失败',
  openProject: '进入项目',

  // 节假日角标（chinese-days：法定节假日休、调休补班）
  holidayRest: '休',
  holidayWork: '班',
  aiSourceTag: 'AI',

  // 项目内日历面板（rail）
  paneEmpty: '本月暂无日程',
  addQuick: '添加',
  openGlobalCalendar: '查看全盘日历',

  // 文件右键「设置截止日」
  setDeadline: '设置截止日',
  deadlineDialogTitle: '设置截止日',
  deadlineForFile: '为「{name}」设置截止日',
  deadlineSet: '截止日已设置',

  // TaskSchedule（概览页日程块）扩展
  showDone: '显示已完成',

  // ==================== 事项系统重做（dev-board#896，spec 2026-09-25-task-calendar-redesign） ====================
  // 产品口径：「事项」指条目（英文 Task），「日程」指日历视图（英文 Schedule）。
  // 本段一次加齐后续各卡（日程页、工作台 rail、项目列表概览条、文件树、命令面板）要用的键。

  // 类型
  typeLabel: '类型',
  typeDeadline: '截止日',
  typeHearing: '开庭',
  typeMeeting: '会议',
  typeTodo: '待办',
  typeOther: '其他',
  priorityHigh: '重要',

  // 提醒
  remindLabel: '提醒',
  remindNone: '不提醒',
  remindAtTime: '准时',
  remind30m: '提前 30 分钟',
  remind1h: '提前 1 小时',
  remind1d: '提前 1 天',
  remind3d: '提前 3 天',
  remind1w: '提前 1 周',
  remindAllDayHint: '全天事项以当天 9:00 为准',
  remindSet: '已设提醒',

  // 议程分组
  groupOverdue: '已逾期',
  groupToday: '今天',
  groupWeek: '本周',
  groupLater: '之后',
  groupDone: '已完成',
  groupDoneCount: '已完成（{count}）',

  // 到期徽标（taskUtils.dueBadge）
  dueOverdueDays: '逾期 {count} 天',
  dueTodayShort: '今天',
  dueTomorrow: '明天',
  dueInDays: '{count} 天后',
  dueMonthDay: '{month}月{day}日',
  dueYearMonthDay: '{year}年{month}月{day}日',

  // 事项弹窗
  dialogCreateTitle: '新建事项',
  dialogEditTitle: '编辑事项',
  titlePlaceholder: "事项名称，输入 {'@'} 关联文件或成员",
  timeField: '时间',
  timePlaceholder: '全天',
  timeClear: '清除时间',
  assigneeLabel: '负责人',
  unassigned: '未指派',
  filesLabel: '关联文件',
  addFile: '添加文件',
  filePickerTitle: '选择要关联的文件',
  fileMissing: '文件已删除',
  removeFile: '移除',
  moreFiles: '另有 {count} 个文件',
  notesLabel: '备注',
  notesPlaceholder: "补充说明，输入 {'@'} 关联文件或成员",
  openFile: '打开文件',
  saving: '保存中...',
  saveShortcutHint: 'Ctrl/⌘ + Enter 保存',
  noWritableProject: '没有可以新建事项的项目',
  pickProjectFirst: '先选择项目',
  edit: '编辑',
  doneLabel: '已完成',

  // 事项输入框 @ 选择器
  mentionTitle: '关联文件或成员',
  mentionHint: '↑↓ 选择，Enter 确认，Esc 关闭',
  mentionNoMatch: '没有匹配的文件或成员',

  // 日程页页头 / 视图 / 筛选
  schedulePageTitle: '日程',
  back: '返回',
  prevPeriod: '上一页',
  nextPeriod: '下一页',
  monthTitle: '{year} 年 {month} 月',
  viewAgenda: '议程',
  newTask: '新建事项',
  filter: '筛选',
  filterProjects: '项目',
  filterTypes: '类型',
  filterAllProjects: '全部项目',
  filterAllTypes: '全部类型',
  filterIncludeDone: '含已完成',
  filterReset: '重置',
  agendaStats: '逾期 {overdue} · 今天 {today} · 本周 {week}',

  // 空态引导（三种创建方式）
  emptyGuideTitle: '还没有事项',
  emptyGuideCreate: '点右上角「新建事项」直接添加',
  emptyGuideFile: '在文件上右键「添加事项…」，事项会关联到这份文件',
  emptyGuideAi: '对 AI 说「下周三开庭，记一下」',

  // 项目列表页概览条
  overviewProjects: '项目',
  overviewOverdue: '已逾期',
  overviewToday: '今天',
  overviewWeek: '本周',
  viewSchedule: '查看日程',

  // 入口：rail / 头像菜单 / 命令面板 / 工作台面板
  railSchedule: '日程',
  mySchedule: '我的日程',
  cmdSchedule: '日程',
  cmdNewTask: '新建事项…',
  viewFullSchedule: '查看全盘日程',
  paneEmptyTasks: '这个项目还没有事项',

  // 文件右键
  fileAddTask: '添加事项…',
  fileViewTasks: '查看事项 ({count})',

  // 本机提醒（taskReminders）
  notifyTitle: '{type}：{title}',
  notifyBody: '{project} · {when}',
  notifyWhenAllDay: '{date} 全天',
  notifyWhenTime: '{date} {time}',
  notifyToast: '{type}提醒：{title}（{when}）',

  // 当日摘要（项目列表页启动时，每天一次）
  digestTodayAndOverdue: '今天有 {count} 件事项，{overdue} 件已逾期',
  digestTodayOnly: '今天有 {count} 件事项',
  digestOverdueOnly: '有 {overdue} 件事项已逾期',

  // 项目列表页概览条下方「最近到期」一行（dev-board#898）
  overviewNextDue: '最近：{when} {title}',

  // 日程页议程：有事项但被筛光（dev-board#897）
  agendaNoMatch: '没有符合筛选条件的事项',

  // 工作台日程面板 / rail 徽标（dev-board#899）
  paneAll: '全部',
  paneAllTasks: '全部事项',
  paneOnlyFile: '仅看：{name}',
  paneClearFilter: '清除筛选',
  paneEmptyMonth: '这个月没有事项',
  paneEmptyFile: '这份文件还没有关联事项',
  railScheduleTitle: '日程（逾期 {overdue} · 今天 {today}）',
}
