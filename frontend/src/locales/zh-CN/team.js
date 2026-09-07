// 「团队」分区文案（dev-board#496）。设置页「个人」组的一栏。
//
// 文案红线（改这个文件时逐条对一遍）：
//   1. 不许出现「登录」「未授权」「请先」三个子串。api.js 历史上拿它们判掉线并清会话，
//      后端 AccountServiceTest 至今按同一口径断言，两侧必须一致。
//   2. 全站禁 emoji。
//   3. 「节约时间」永远带「估算」二字并把公式摆出来——它是带系数的推算，不是测量值，
//      做成一个看起来精确的数字就是在骗管理者。
export default {
  navTeam: '团队',

  // ---- 三态外壳 ----
  subtitle: '把律所的使用情况汇总给管理者看',
  privacyLine: '团队统计只上传计数与匿名项目编号，不含文档内容、文件名与对话',
  needAccountTitle: '尚未连接账户',
  needAccountDesc: '团队挂在 AI WorkDeck 账户上。到「账户与用量」连接账户后再回来。',
  goAccount: '前往账户与用量',
  loadFailed: '团队信息暂时取不到，稍后重试',
  retry: '重试',

  // ---- 无团队 ----
  createTitle: '创建团队',
  createDesc: '创建后你是负责人，可以按手机号邀请同事加入。',
  teamNamePlaceholder: '团队名称，例如某某律师事务所',
  createButton: '创建团队',
  createFailedEmpty: '团队名称不能为空',
  invitesTitle: '收到的邀请',
  invitesEmpty: '当前没有收到任何团队邀请',
  inviteFrom: '来自 {team}',
  acceptInvite: '接受',

  // ---- KPI ----
  kpiTitle: '团队使用概览',
  kpiActiveMembers: '本周使用人数',
  kpiActiveMembersCaption: '{active} / {total} 人',
  kpiProjectsCreated: '新建项目',
  kpiAppStarts: '打开次数',
  kpiActiveMinutes: '投入时长',
  kpiSavedMinutes: '节约时间（估算）',
  hours: '{hours} 小时',
  savedFormula: '估算口径：每次 AI 编辑按 {perAgentEdit} 分钟、每轮 AI 对话按 {perAiTurn} 分钟折算，仅供横向比较',
  savedFormulaUnknown: '估算口径由服务端下发，当前取不到',
  rangeDays: '最近 {n} 天',

  // ---- 成员 ----
  membersTitle: '成员',
  membersEmpty: '暂无成员数据',
  colMember: '成员',
  colRole: '角色',
  colActiveDays: '活跃天数',
  colMinutes: '投入时长',
  colAiTurns: 'AI 轮次',
  colLastActive: '最近活跃',
  roleOwner: '负责人',
  roleAdmin: '管理员',
  roleMember: '成员',
  changeRole: '改角色',
  removeMember: '移除',
  leaveTeam: '退出团队',
  confirmRemove: '确认把该成员移出团队？移出后他的历史统计仍保留。',
  confirmLeave: '确认退出团队？退出后你看不到团队统计。',

  // ---- 项目 ----
  projectsTitle: '项目',
  projectsEmpty: '暂无项目数据',
  projectsNote: '项目默认以匿名编号出现。管理者可以给编号起一个团队内部叫法。',
  colProject: '项目',
  colProjectMembers: '参与人数',
  setAlias: '起别名',
  aliasPlaceholder: '这个编号在团队里叫什么',

  // ---- 管理区 ----
  manageTitle: '团队管理',
  inviteTitle: '邀请成员',
  invitePhonePlaceholder: '同事的手机号',
  inviteRoleLabel: '加入后的角色',
  inviteButton: '发出邀请',
  invitePhoneEmpty: '手机号不能为空',
  pendingInvitesTitle: '待接受的邀请',
  pendingInvitesEmpty: '没有待接受的邀请',
  revokeInvite: '撤销',
  settingsTitle: '团队设置',
  renameLabel: '团队名称',
  saveName: '保存',
  shareProjectNamesLabel: '共享项目名',
  shareProjectNamesDesc: '打开后，成员上报的统计里会带上项目名；关闭时团队面板只看得到匿名编号。这是整个团队的设置。',

  // ---- 数据共享 ----
  sharingTitle: '向团队共享我的使用统计',
  sharingDesc: '每天上传一条计数（投入时长、AI 轮次、编辑动作数、匿名项目编号），不含任何文档内容。默认关闭。',
  sharingDesktopOnly: '这台机器是团队服务器模式，使用统计按人区分不了，该开关不可用。',
  uploadNow: '立即上报',
  lastUploadAt: '上次上报：{time}',
  lastUploadNever: '尚未上报过',
  uploadDone: '已上报 {days} 天的数据',
  uploadNothing: '没有需要上报的新数据',
  skipDisabled: '共享开关还没打开',
  skipNotLocalMode: '团队服务器模式下不上报使用统计',
  skipNotConnected: '这台机器还没有连接账户',
  skipNoTeam: '你还不在任何团队里',

  // ---- 空态 ----
  emptyData: '还没有统计数据。成员打开数据共享开关后，次日可以看到。',
}
