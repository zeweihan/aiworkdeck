// 可停靠面板注册表（dev-board#180）：哪些面板能换停靠位、默认停在哪、允许停在哪。
//
// **本文件不许 import Vue / uni / @/i18n**——它要能被 node --test 直接导入
// （tests/panel-dock/dock-resolve.test.mjs 断言回落规则），照 config/commands/index.js 的先例。
// 文案因此只给 i18n key（labelKey），由宿主 $t 渲染；图标从同目录 icons.js 取（纯数据、无副作用）。
//
// 三个停靠位就是工作台的三块可收起区域：
//   left   = 左栏 sidebar-left（rail 上一个按钮 = 一个面板，走既有的 toggleLeftPane 语义）
//   right  = 右侧面板 side-panel-ai（AI 对话之外的 tab 宿主，rightPaneKey 选中哪一个）
//   bottom = 底部抽屉 bottom-panel（activeToolKey 选中哪一个）
//
// 加一个可停靠面板的步骤（后续「依据」面板照此接入，详见 .claude/agents/sidebar-shell.md）：
//   1. 这里加一条（key / labelKey / defaultDock / allowedDocks / svgPaths）；
//   2. project-overview.vue 在**每个** allowedDocks 对应的显式 v-else-if 分支链里加一条渲染
//      （右栏 rightPaneKey === '<key>' / 左栏 leftPaneKey === '<key>' / 底栏 activeToolKey === '<key>'），
//      props 与 @event 逐个显式写——**不许改成 <component :is>**：check-emit-bindings.mjs
//      是静态扫描，动态绑定会静默失去覆盖；
//   3. 文案补 locales/{zh-CN,en-US} 两份。

import { ICONS } from './icons.js'

/** 停靠位全集。顺序即右键菜单里的排列顺序。 */
export const DOCKS = ['left', 'right', 'bottom']

const toPaths = (paths) => (paths || []).map((d) => ({ d }))

/**
 * 可移动面板清单。顺序 = 底栏 tab / 右栏 tab / rail 追加按钮的排列顺序（单一出处）。
 *
 * 变量库/收藏夹/剪贴板原本就是底部抽屉的三项（原 config/tools.js 的 WORKBENCH_TOOLS，
 * 已被本表取代）；语音原本是 rail 上的一个左栏面板。
 */
export const MOVABLE_PANELS = [
  {
    key: 'variables',
    labelKey: 'config.tools.variables',
    defaultDock: 'bottom',
    allowedDocks: ['left', 'right', 'bottom'],
    svgPaths: toPaths(ICONS.braces),
  },
  {
    key: 'favorites',
    labelKey: 'config.tools.favorites',
    defaultDock: 'bottom',
    allowedDocks: ['left', 'right', 'bottom'],
    svgPaths: toPaths(ICONS.star),
  },
  {
    key: 'clipboard',
    labelKey: 'config.tools.clipboard',
    defaultDock: 'bottom',
    allowedDocks: ['left', 'right', 'bottom'],
    svgPaths: toPaths(ICONS.copyDoc),
  },
  {
    // 语音（语音合成 + 会议录音）。它是**左栏原生面板**，也是第一个允许停到右侧的：
    // 一边听转写一边在编辑器里改稿是真实用法。底栏放不下它的两个 tab，所以不给 bottom。
    key: 'voice',
    labelKey: 'config.sidebar.voice',
    defaultDock: 'left',
    allowedDocks: ['left', 'right'],
    svgPaths: toPaths(ICONS.audioLines),
  },
  {
    // 依据（dev-board#182）：编辑器「解析」按钮的联动窗格——外部检索 + 一致性校验。
    // 默认停右侧：它要和正文并排看（点条目就在文档里定位、点建议就把文档改好），
    // 底栏那点高度放不下判决书全文，所以不给 bottom。
    key: 'insight',
    labelKey: 'insight.title',
    defaultDock: 'right',
    allowedDocks: ['left', 'right'],
    svgPaths: toPaths(ICONS.bookSearch),
  },
]

const BY_KEY = new Map(MOVABLE_PANELS.map((p) => [p.key, p]))

/** 这个 key 是不是可停靠面板（rail/tab 上要不要挂拖拽与右键菜单的判据）。 */
export function isMovablePanel(key) {
  return BY_KEY.has(key)
}

export function getMovablePanel(key) {
  return BY_KEY.get(key) || null
}

/** 这个面板允不允许停到该位置（右键菜单只列允许的，拖拽只高亮允许的投放区）。 */
export function isDockAllowed(key, dock) {
  const p = BY_KEY.get(key)
  return !!p && p.allowedDocks.includes(dock)
}

/**
 * 单个面板的生效停靠位：override 合法才用它，否则一律回落 defaultDock。
 * 「合法」= 该 key 确实是可停靠面板、值在 DOCKS 里、且在这个面板的 allowedDocks 里。
 */
export function resolveDock(key, overrides) {
  const p = BY_KEY.get(key)
  if (!p) return null
  const want = overrides && overrides[key]
  if (want && DOCKS.includes(want) && p.allowedDocks.includes(want)) return want
  return p.defaultDock
}

/**
 * 全量停靠分配：{ left: [panel...], right: [...], bottom: [...] }，各档内按注册表顺序。
 * 纯函数（本模块唯一的状态入口），单测就打这里。
 */
export function resolveDocks(overrides) {
  const out = { left: [], right: [], bottom: [] }
  for (const p of MOVABLE_PANELS) {
    out[resolveDock(p.key, overrides)].push(p)
  }
  return out
}

/**
 * 清洗持久化读回来的 overrides：丢掉不认识的 key 与非法/不被允许的值。
 * storage 是全局键（本机习惯），版本回退、面板下线都会让里面躺着过期的值。
 */
export function sanitizeDockOverrides(raw) {
  const out = {}
  if (!raw || typeof raw !== 'object') return out
  for (const [key, dock] of Object.entries(raw)) {
    if (!BY_KEY.has(key)) continue
    if (!DOCKS.includes(dock)) continue
    if (!BY_KEY.get(key).allowedDocks.includes(dock)) continue
    out[key] = dock
  }
  return out
}
