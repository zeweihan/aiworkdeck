// 项目列表页 + 项目概览页（project-list.vue / project-home.vue 及其五个 project-home/* 子组件）
export default {
  // project-list.vue：页头与空态
  myProjects: '我的项目',
  pullFromTeamLibrary: '从团队案件库取一份案卷',
  personalCenter: '个人中心',
  allProjects: '全部项目',
  loading: '加载中...',
  clientEmptyHint: '律师把案卷分享给你之后，会出现在这里',
  newProject: '新建项目',
  createSectionTitle: '新建',
  emptyHint: '还没有案卷。从下面开始：打开一个已有的文件夹，或者新建一个。',
  // project-list.vue：视图切换与列表视图列名
  gridView: '方块视图',
  listView: '列表视图',
  nameColumn: '名称',
  clientColumn: '客户',
  // 档案里没填客户时，列表显示的是推断值（客户成员/上市公司），得让人看得出区别
  clientInferred: '推断',
  // 「详情」开关：把档案里其余四项补出来（客户是一等列、常显，不在这里）
  detailToggle: '详情',
  detailToggleHint: '显示项目档案里的事项类型、对方、立项时间与下一步（在项目概览里填）',
  matterTypeField: '事项类型',
  counterpartyField: '对方',
  openedAtField: '立项时间',
  nextStepField: '下一步',
  createdColumn: '创建时间',
  updatedColumn: '最近修改',
  membersColumn: '成员',
  createdAtShort: '创建于 {time}',
  rename: '重命名',
  // project-list.vue：卡片
  delete: '删除',
  managerLabel: '项目负责人: {name}',
  unknown: '未知',
  clientLabel: '客户',
  clientMemberTitle: '{name} (客户)',
  clientInitial: '客',
  listedCompany: '上市公司',
  targetCompany: '标的公司',
  // project-list.vue：成员移除
  removeConfirmTitle: '确认移除',
  removeConfirmContent: '确定要移除该成员吗？',
  cancel: '取消',
  confirm: '确认',
  removeSuccess: '移除成功',
  removeFailed: '移除失败',
  // project-list.vue：加载/重命名/删除
  loadFailedRetry: '加载失败，请稍后重试',
  projectNameEmpty: '项目名称不能为空',
  renameSuccess: '重命名成功',
  renameFailed: '重命名失败',
  deleteConfirmTitle: '确认删除',
  deleteConfirmContent: '确定要删除这个项目吗？删除后无法恢复。',
  deleteSuccess: '删除成功',
  deleteFailedRetry: '删除失败，请稍后重试',

  // project-home.vue
  backToList: '返回项目列表',
  overviewPageTitle: '项目概览',
  enterWorkbench: '进入工作台',
  activitySectionTitle: '动态',
  taskSectionTitle: '日程与任务',
  conversationSectionTitle: 'AI 对话',
  missingProjectParam: '缺少项目参数',
  saveFailed: '保存失败',

  // ProfileHeader.vue
  profileEmptyGuideDesc: '这份案卷的档案还是空的。先把客户和事项类型填上，同事和客户点进来一眼就知道这是什么案子。',
  startFilling: '开始填写',
  selectMatterType: '选择事项类型',
  notFilled: '未填写',
  placeholderClient: '例如：北京某某科技有限公司',
  placeholderOpenedAt: '例如：2026-08-01',
  placeholderNextStep: '例如：8 月 15 日前出尽调报告初稿',
  placeholderCounterparty: '例如：上海某某贸易有限公司',

  // OverviewStatsBar.vue
  statsLoadingHint: '正在读取项目情况…',
  folderCountLabel: '{count} 个文件夹',
  folderCaption: '不含系统目录',
  memberCountLabel: '{count} 位参与人',
  memberCaption: '含负责人',
  runCountLabel: '{count} 个后台任务',
  noRunningTasks: '当前没有在跑的任务',
  recentRunPrefix: '最近一个：{status}',
  runFinished: '已结束',
  localRootCaption: '本机文件夹，取自最近一次对账',
  defaultFileCaption: '不含缓存区与 AI 生成目录',

  // ActivityFeed.vue
  activityLoadingHint: '正在读取动态…',
  noVersionHistoryTitle: '这份案卷还没有版本记录',
  noVersionHistoryDesc: '开启之后，每次改动都会自动留底，这里会按时间列出做过什么。开启的入口在工作台右侧的「版本」面板里。',
  noActivityTitle: '还没有动态',
  noActivityDesc: '在工作台里改过文件、或者让 AI 跑过一次任务之后，这里就会有记录。',
  aiTaskLabel: 'AI 任务 {status}',

  // TaskSchedule.vue
  tasksLoadingHint: '正在读取任务…',
  noTasksTitle: '还没有排任务',
  noTasksDesc: '交付日期、待办和提醒以后会排在这里。',
  statusDoing: '进行中',
  statusDone: '已完成',
  statusOpen: '待办',

  // ConversationList.vue
  conversationsLoadingHint: '正在读取对话历史…',
  noConversationsTitle: '这份案卷还没有 AI 对话',
  noConversationsDesc: '进工作台打开 AI 面板问第一个问题，之后每次对话都会记在这里。',
  loadMoreConversations: '看更早的对话',
}
