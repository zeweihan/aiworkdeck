// 应用菜单桥：渲染层这一侧的唯一收口。
//
// 三件事：
//   1. 维护状态快照（在哪个页面、什么角色、面板开没开、有没有文档标签…），
//      变化时把「命令表 + 当前状态」渲染成菜单树下发给主进程；
//   2. 承接主进程回传的菜单动作，查命令表拿到 run 目标；
//   3. 派发——app:* 自己执行，wb:* 经 uni.$emit 交给活跃的工作台实例。
//
// **在 App.vue onLaunch 注册一次。** 不要在页面里注册：navigateTo 会让
// project-overview 存在多个实例，每个都订阅一遍 = 一次点击执行多次（这个项目
// 在剪贴板订阅上踩过，见 utils-tools 领域文档）。工作台侧只负责「报状态、收事件」，
// 并且用既有的 isActiveOverviewInstance() 守卫过滤。
//
// 设计见 docs/superpowers/specs/2026-08-16-desktop-chrome-and-command-menu.md。

import { host, isDesktopHost } from '@/services/host.js'
import { COMMAND_BY_ID, COMMANDS, buildMenuPayload, isEnabled, labelOf } from '@/config/commands/index.js'
import { getAppLanguage, setAppLanguage, APP_LANGUAGE_EVENT } from '@/utils/appLanguage.js'
import { t } from '@/i18n'

/** 工作台命令的事件名。project-overview 侧监听，自带活跃实例守卫。 */
export const COMMAND_EVENT = 'awd:command'

const state = {
  page: '',            // 'workbench' | 'project-list' | 'login' | ...
  role: '',            // 'CLIENT' 时整片能力要收起来（安全边界，见 spec §6.3）
  projectId: null,
  activeView: null,    // 左栏当前面板 key
  flags: {},           // 全是布尔或短枚举——放计数器会让菜单疯狂重建
  views: [],           // [{ key, label }] rail 全量，含 skill 门控与动态插件
  recent: [],          // [{ id, name }]
}

let pushTimer = null
let lastSerialized = ''
let started = false

function currentLang() {
  try { return getAppLanguage() } catch (e) { return 'zh-CN' }
}

/**
 * 下发。**必须浅比较后才推**：Menu.setApplicationMenu 会关掉用户正展开着的菜单，
 * 状态一抖菜单栏就没法用了。100ms 去抖 + 序列化比较，双保险。
 */
function schedulePush() {
  if (!isDesktopHost() || !(host.menu && host.menu.setState)) return
  if (pushTimer) return
  pushTimer = setTimeout(() => {
    pushTimer = null
    try {
      const payload = buildMenuPayload(state, currentLang())
      const s = JSON.stringify(payload)
      if (s === lastSerialized) return
      lastSerialized = s
      host.menu.setState(payload)
    } catch (e) {
      console.warn('[menu] 下发失败:', e)
    }
  }, 100)
}

/** 页面切换时调用：重置成该页面的基线状态，避免上一页的 flags 残留把菜单点亮。 */
export function setMenuPage(page, patch) {
  state.page = String(page || '')
  state.role = (patch && patch.role) || ''
  state.projectId = (patch && patch.projectId) || null
  state.activeView = (patch && patch.activeView) || null
  state.flags = (patch && patch.flags) || {}
  state.views = (patch && patch.views) || []
  schedulePush()
}

/** 局部更新（面板开关、标签切换、修订模式…）。只合并给出的字段。 */
export function patchMenuState(patch) {
  if (!patch) return
  if ('page' in patch) state.page = patch.page
  if ('role' in patch) state.role = patch.role
  if ('projectId' in patch) state.projectId = patch.projectId
  if ('activeView' in patch) state.activeView = patch.activeView
  if (patch.views) state.views = patch.views
  if (patch.flags) state.flags = { ...state.flags, ...patch.flags }
  schedulePush()
}

/** 最近项目（recentProjects.js 解析出名称后调）。 */
export function setMenuRecent(list) {
  state.recent = Array.isArray(list) ? list.slice(0, 8) : []
  schedulePush()
}

/** 命令面板/快捷键速查用：当前语境下可执行的命令。 */
export function listAvailableCommands() {
  const lang = currentLang()
  return COMMANDS
    .filter((c) => isEnabled(c, state))
    .map((c) => ({ id: c.id, label: labelOf(c, lang), accel: c.accel || '', menu: c.menu }))
}

// ---------------------------------------------------------------- 派发

function toast(title) {
  try { uni.showToast({ title, icon: 'none' }) } catch (e) { /* ignore */ }
}

/** app:* —— 与页面无关的命令，桥自己执行。 */
async function runAppCommand(verb, arg) {
  switch (verb) {
    case 'newProject':
      uni.reLaunch({ url: '/pages/newproject/index' })
      return
    case 'openFolder': {
      const { openFolderFlow } = await import('@/utils/ideOpen.js')
      await openFolderFlow()
      return
    }
    case 'openFile': {
      const { openFileFlow } = await import('@/utils/ideOpen.js')
      await openFileFlow()
      return
    }
    case 'closeProject':
      uni.reLaunch({ url: '/pages/project-list/project-list' })
      return
    case 'openSettings':
      uni.navigateTo({ url: '/pages/admin/admin' })
      return
    case 'openAccount':
      uni.navigateTo({ url: '/pages/userprofile/userprofile' })
      return
    case 'checkUpdate': {
      if (!(host.update && host.update.check)) return
      toast(t('shell.menuCheckingUpdate'))
      const r = await host.update.check()
      const phase = r && r.phase
      if (phase === 'error') toast((r && r.error) || t('shell.menuUpdateFailed'))
      else if (phase === 'idle' || phase === 'up-to-date') toast(t('shell.menuUpToDate'))
      // 其余阶段（下载中/待重启）由更新器自己的 UI 接管，别在这里抢话
      return
    }
    case 'setLang':
      if (arg) setAppLanguage(arg)
      return
    case 'openFeedback':
      uni.$emit('awd:open-feedback')
      return
    case 'openUrl':
      if (arg && host.shell && host.shell.openExternal) host.shell.openExternal(arg)
      return
    case 'viewLogs': {
      if (!(host.shell && host.shell.revealLogs)) return
      const r = await host.shell.revealLogs()
      if (r && r.ok === false) toast(r.message || t('shell.menuLogsUnavailable'))
      return
    }
    case 'showShortcuts': {
      const rows = listAvailableCommands().filter((c) => c.accel)
      const body = rows.map((c) => c.accel.replace(/CmdOrCtrl/g, '⌘').replace(/Alt/g, '⌥').replace(/Shift/g, '⇧').replace(/\+/g, '') + '  ' + c.label)
      // ⌘P 是渲染层局部键位、不在命令表里（编辑器优先，见 spec §4.1），
      // 但它是最常用的一条，速查表里不能没有。
      body.unshift('⌘P  ' + t('shell.menuShortcutQuickOpen'))
      uni.showModal({ title: t('shell.menuShortcutsTitle'), content: body.join('\n'), showCancel: false })
      return
    }
    default:
      console.warn('[menu] 未知 app 命令:', verb)
  }
}

/**
 * 承接一条菜单动作。**执行前统一走一次 when 求值**——菜单项置灰只是视觉，
 * 加速键在客户视图下按下去必须什么都不发生（spec §6.3）。
 */
async function handleAction(action) {
  if (!action) return

  // 动态条目：id 带冒号后缀
  if (action.startsWith('file.openRecent:')) {
    const id = action.slice('file.openRecent:'.length)
    uni.reLaunch({ url: '/pages/project-overview/project-overview?id=' + encodeURIComponent(id) })
    return
  }
  if (action.startsWith('go.switchProject:')) {
    const id = action.slice('go.switchProject:'.length)
    uni.reLaunch({ url: '/pages/project-overview/project-overview?id=' + encodeURIComponent(id) })
    return
  }
  if (action.startsWith('view.open:')) {
    uni.$emit(COMMAND_EVENT, { id: action, run: 'wb:openView', arg: action.slice('view.open:'.length) })
    return
  }
  // Dock/访达「打开方式」进来的路径：主进程直发，不是命令表里的条目
  if (action === 'open-path') return

  const cmd = COMMAND_BY_ID.get(action)
  if (!cmd) {
    console.warn('[menu] 未知命令:', action)
    return
  }
  if (!isEnabled(cmd, state)) return

  const [ns, verb, arg] = splitRun(cmd.run)
  try {
    if (ns === 'app') await runAppCommand(verb, arg)
    else uni.$emit(COMMAND_EVENT, { id: cmd.id, run: cmd.run, verb, arg })
  } catch (e) {
    toast((e && e.message) || t('shell.menuActionFailed'))
  }
}

/**
 * 按 id 执行一条命令。命令面板走这个入口——和菜单栏是**同一条**派发链，
 * 因此共用同一份 when 判定与同一份实现，两个入口不会漂。
 */
export function runCommandById(id) {
  return handleAction(id)
}

/** 'wb:setAiMode:AGENT' → ['wb', 'setAiMode', 'AGENT']；参数里可能还有冒号（URL）。 */
function splitRun(run) {
  const s = String(run || '')
  const i = s.indexOf(':')
  if (i < 0) return ['', s, '']
  const ns = s.slice(0, i)
  const rest = s.slice(i + 1)
  const j = rest.indexOf(':')
  if (j < 0) return [ns, rest, '']
  return [ns, rest.slice(0, j), rest.slice(j + 1)]
}

export function initAppMenuBridge() {
  if (started) return
  started = true
  if (!isDesktopHost()) return
  if (host.menu && host.menu.onAction) {
    host.menu.onAction((data) => { handleAction(data && data.action) })
  }
  // 语言切换要重下发一次（业务菜单文案在渲染层，骨架文案主进程自己换）
  try { uni.$on(APP_LANGUAGE_EVENT, () => { lastSerialized = ''; schedulePush() }) } catch (e) { /* ignore */ }
  schedulePush()
}

/** 测试与调试用：读当前快照（只读副本）。 */
export function getMenuState() {
  return JSON.parse(JSON.stringify(state))
}
