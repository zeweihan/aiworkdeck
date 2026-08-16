// 命令注册表汇总 + 「状态快照 → 菜单树」的纯函数。
//
// 本文件**不许 import Vue / uni / 任何浏览器 API**——它要能被 node --test 直接
// 导入做加速键冲突与 when 求值的断言（tests/commands/commands.test.mjs）。
// 副作用全部在 utils/appMenuBridge.js 那一侧。

import { APP_COMMANDS } from './app.js'
import { FILE_COMMANDS } from './file.js'
import { EDIT_COMMANDS } from './edit.js'
import { DOCUMENT_COMMANDS } from './document.js'
import { AI_COMMANDS } from './ai.js'
import { VIEW_COMMANDS } from './view.js'
import { GO_COMMANDS } from './go.js'
import { TOOLS_COMMANDS } from './tools.js'
import { HELP_COMMANDS } from './help.js'

export const COMMANDS = [
  ...APP_COMMANDS, ...FILE_COMMANDS, ...EDIT_COMMANDS, ...DOCUMENT_COMMANDS,
  ...AI_COMMANDS, ...VIEW_COMMANDS, ...GO_COMMANDS, ...TOOLS_COMMANDS, ...HELP_COMMANDS,
]

export const COMMAND_BY_ID = new Map(COMMANDS.map((c) => [c.id, c]))

/** 菜单栏从左到右的顺序与标题。'app' 由主进程用应用名渲染，这里不给 label。 */
export const MENU_ORDER = [
  { id: 'app' },
  { id: 'file', label: { zh: '文件', en: 'File' } },
  { id: 'edit', label: { zh: '编辑', en: 'Edit' } },
  { id: 'document', label: { zh: '文档', en: 'Document' } },
  { id: 'ai', label: { zh: 'AI', en: 'AI' } },
  { id: 'view', label: { zh: '视图', en: 'View' } },
  { id: 'go', label: { zh: '转到', en: 'Go' } },
  { id: 'tools', label: { zh: '工具', en: 'Tools' } },
  { id: 'help', label: { zh: '帮助', en: 'Help' } },
]

/**
 * 编辑器保留键：这些裸键在 LibreOffice Writer 里有既定语义，菜单不许征用
 * （macOS 上 NSMenu 的 key equivalent 先于响应链，挂上去等于永久抢走）。
 * 例外是 A 档「语义同构」的那几个——菜单项做的事就是编辑器里那个键做的事。
 * 口径见 spec §4，断言在 tests/commands/commands.test.mjs。
 */
export const EDITOR_RESERVED_EXCEPTIONS = new Set([
  'CmdOrCtrl+O', 'CmdOrCtrl+W', 'CmdOrCtrl+F', 'CmdOrCtrl+,',
])

const lang = (l) => (String(l || '').startsWith('en') ? 'en' : 'zh')

/** 取本地化文案。label 是 {zh,en}，没有对应语言时回落中文。 */
export function labelOf(item, appLang) {
  const l = item && item.label
  if (!l) return ''
  return (lang(appLang) === 'en' ? l.en : l.zh) || l.zh || l.en || ''
}

/**
 * when 求值：枚举全部满足才通过。不做表达式引擎（YAGNI，见 README）。
 * 同一个判定同时决定「菜单项是否 enabled」和「命令是否允许执行」——
 * 客户视图下按加速键必须什么都不发生，不能只是菜单项不渲染（spec §6.3）。
 */
export function isEnabled(cmd, state) {
  const tokens = (cmd && cmd.when) || []
  if (!tokens.length) return true
  const s = state || {}
  const f = s.flags || {}
  for (const t of tokens) {
    switch (t) {
      case 'workbench': if (s.page !== 'workbench') return false; break
      case 'project': if (!f.hasProject) return false; break
      case 'tab': if (!f.hasTab) return false; break
      case 'docTab': if (!f.isDocTab) return false; break
      case 'split': if (!f.splitMode) return false; break
      case 'notClient': if (s.role === 'CLIENT') return false; break
      case 'aiRunning': if (!f.aiRunning) return false; break
      case 'notAiRunning': if (f.aiRunning) return false; break
      // 未知 token 一律判否：写错了要立刻看得见，而不是悄悄放行
      default: return false
    }
  }
  return true
}

function toItem(cmd, state, appLang) {
  const item = {
    id: cmd.id,
    label: labelOf(cmd, appLang),
    enabled: isEnabled(cmd, state),
  }
  if (cmd.accel) item.accel = cmd.accel
  if (cmd.type === 'checkbox') {
    item.type = 'checkbox'
    item.checked = !!((state && state.flags) || {})[cmd.checked]
  }
  return item
}

/** 同一 group 的连成一块，跨 group 之间插分隔线。 */
function withSeparators(entries) {
  const out = []
  let prev = null
  for (const e of entries) {
    if (prev !== null && e.group !== prev) out.push({ type: 'separator' })
    out.push(e.item)
    prev = e.group
  }
  return out
}

/**
 * 把状态快照渲染成可 JSON 序列化的菜单树，交给主进程变成 NSMenu。
 *
 * 动态子菜单（最近打开 / 切换项目 / 打开视图）在这里现场生成——它们的条目数
 * 随用户与运行时（角色、已启用 skill、动态插件）变化，写不进静态表。
 */
export function buildMenuPayload(state, appLang) {
  const s = state || {}
  const menus = []
  for (const m of MENU_ORDER) {
    const entries = COMMANDS
      .filter((c) => c.menu === m.id)
      .map((c) => ({ group: c.group || 0, item: toItem(c, s, appLang) }))

    // 文件 → 打开最近（group 2，夹在打开动作与导入之间）
    if (m.id === 'file') {
      const recent = (s.recent || []).map((r) => ({
        id: 'file.openRecent:' + r.id,
        label: String(r.name || r.id),
        enabled: true,
      }))
      entries.push({
        group: 2,
        item: {
          id: 'file.openRecent',
          label: lang(appLang) === 'en' ? 'Open Recent' : '打开最近',
          enabled: recent.length > 0,
          submenu: recent.length ? recent : [{
            id: 'file.openRecent.empty',
            label: lang(appLang) === 'en' ? 'No Recent Projects' : '暂无最近项目',
            enabled: false,
          }],
        },
      })
    }

    // 视图 → 打开视图（rail 全量，含 skill 门控与动态插件）
    if (m.id === 'view') {
      const views = (s.views || []).map((v) => ({
        id: 'view.open:' + v.key,
        label: String(v.label || v.key),
        enabled: true,
        type: 'checkbox',
        checked: s.activeView === v.key,
      }))
      if (views.length) {
        entries.push({
          group: 2,
          item: {
            id: 'view.openView',
            label: lang(appLang) === 'en' ? 'Open View' : '打开视图',
            enabled: s.page === 'workbench',
            submenu: views,
          },
        })
      }
    }

    // 转到 → 切换项目（最近项目里排除当前这个）
    if (m.id === 'go') {
      const others = (s.recent || []).filter((r) => String(r.id) !== String(s.projectId))
      if (others.length) {
        entries.push({
          group: 3,
          item: {
            id: 'go.switchProject',
            label: lang(appLang) === 'en' ? 'Switch Project' : '切换项目',
            enabled: true,
            submenu: others.map((r) => ({
              id: 'go.switchProject:' + r.id,
              label: String(r.name || r.id),
              enabled: true,
            })),
          },
        })
      }
    }

    if (!entries.length) continue
    entries.sort((a, b) => a.group - b.group)
    menus.push({
      id: m.id,
      label: m.label ? labelOf(m, appLang) : undefined,
      items: withSeparators(entries),
    })
  }
  return { menus }
}
