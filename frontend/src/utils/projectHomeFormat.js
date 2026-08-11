/**
 * 项目概览页（pages/project-home）的纯展示逻辑。
 *
 * 抽出来的理由：这几条口径全部是硬约束（localRoot 措辞、时间线 6 种文案形状、
 * source=default 弱化、空预览兜底），而 .vue 模板在本仓没有单测手段。
 * 本文件不 import 任何东西、不碰 uni.*，才能被 node --test 直接跑。
 *
 * 与工作台 project-overview.vue:4769-4790 的 convStatusLabel/convDotClass 形状相同
 * 但不共用：概览页是新页面，重构工作台不在本次范围内。两边的取值表必须一起改。
 */

function pad2(n) {
  return String(n).padStart(2, '0')
}

/** ISO 串（Instant 带 Z 或 LocalDateTime 不带 Z）→「8 月 8 日 10:11」；坏值返回空串。 */
export function formatDateTime(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日 ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

/**
 * 时间线条目标题。note 优先于 message（同 VersionTimeline.vue:111-113 的 titleOf）。
 * 原样返回，不做任何空白归一——未命名工作的默认名是「8 月 8 日下午的工作」，
 * 空格来自 WorkSessionService 的 TITLE_FMT "M 月 d 日"，压掉就是错别字。
 */
export function versionTitle(entry) {
  if (!entry) return ''
  return entry.note || entry.message || ''
}

/**
 * 统计条的文件计数措辞。localRoot 项目说「已登记 N 项」而不是「共 N 个文件」：
 * 3000 条上限 + 20 层深度 + 隐藏项跳过，外置盘拔出时数字还会冻结在最后一次
 * 成功对账的快照上。
 */
export function fileCountLabel(stats) {
  const n = Number((stats && stats.fileCount) || 0)
  return stats && stats.isLocalRoot ? `已登记 ${n} 项` : `${n} 个文件`
}

/**
 * 后台 AI 任务状态文案。取值是 AgentRunStateService.RunStatus 的 8 个枚举值
 * （service/ai/AgentRunStateService.java:30-57）。
 * 「待回答」(AWAITING_INPUT) 必须与「待审批」(AWAITING_APPROVAL) 分开：
 * 前者是模型缺信息在问你，后者是有草案等你点头。
 */
export function runStatusLabel(status) {
  if (status === 'RUNNING') return '运行中'
  if (status === 'PAUSED') return '待继续'
  if (status === 'INTERRUPTED') return '已中断'
  if (status === 'AWAITING_APPROVAL') return '待审批'
  if (status === 'AWAITING_INPUT') return '待回答'
  if (status === 'ERROR') return '出错'
  return ''
}

/** 状态点样式类。跑完/取消不打点。 */
export function runStatusDotClass(status) {
  if (status === 'RUNNING') return 'dot-running'
  if (status === 'PAUSED' || status === 'AWAITING_APPROVAL'
    || status === 'AWAITING_INPUT' || status === 'INTERRUPTED') return 'dot-attention'
  if (status === 'ERROR') return 'dot-error'
  return ''
}

/**
 * 档案是否全空。source==='default' 的 openedAt 是服务端用建档时间派生的，
 * 不算有人填过——否则新建项目永远进不了引导态。
 */
export function isProfileEmpty(fields) {
  if (!Array.isArray(fields)) return true
  return !fields.some((f) => f && f.fieldValue && f.source !== 'default')
}

/** 字段值下方的弱化说明。律师不能把模型猜的立项日期当事实。 */
export function profileFieldHint(field) {
  if (!field) return ''
  if (field.source === 'default') return '取自建档时间'
  if (field.source === 'ai') return 'AI 读文件得出，请核对'
  return ''
}

/**
 * 会话预览是否有内容。extractPreview 对以 import/def/function/class/const/let/var/
 * public/private 开头的正文直接返回空串，此时不要留一个空行。
 */
export function hasConversationPreview(conversation) {
  return !!(conversation && typeof conversation.lastMessage === 'string' && conversation.lastMessage.trim())
}

/**
 * 当前用户在这个项目里能不能改档案。myRole 取自 ProjectCardDTO（model/dto/ProjectCardDTO.java:19）。
 *
 * 集合对应后端 hasWritePermission（ProjectMemberService.java:159-169，放行 owner + ADMIN + PARTICIPANT）。
 * MANAGER 是**前端侧的历史键**：全仓 grep 后端无一处产生这个值（ProjectService.java:175 给
 * owner 写死 "OWNER"，:179 从 project_member 行取），memberRoles.js:18-19 里它与 OWNER 同为
 * 「负责人」，project-overview.vue:1905 也按管理员待遇。这里放行它是与既有前端口径一致；
 * 万一将来真出现一个非 owner 的 MANAGER，写入会被后端拒，概览页 onProfileSave 的 catch 会 toast。
 */
export function canEditProfile(myRole) {
  return ['OWNER', 'MANAGER', 'ADMIN', 'PARTICIPANT'].indexOf(myRole) !== -1
}
