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
}
