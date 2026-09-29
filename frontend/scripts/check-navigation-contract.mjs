#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 导航契约静态护栏（项目列表页 → 工作台；概览是工作台里的一个标签）。
 *
 * 存在理由：这套导航散在 launch / login / newproject / project-overview / 两个新页面
 * 共十来处硬编码 URL 上，改错一处不会编译报错，只会在真人走到那一步时落到空白页
 * 或者多跳一次。规则写死在这里，CI 每次跑。
 *
 * 2026-08 改动（三级 → 两级）：概览不再是列表与工作台之间的一站独立页，而是
 * 工作台中栏的一个标签（rail 第一个按钮，内容本体 components/project-home/
 * ProjectHomePane.vue 两个宿主共用）。列表点卡片直接 reLaunch 进工作台。
 * pages/project-home 薄壳保留给直链与深链。
 *
 * 2026-09-29 改动（dev-board#1047，登录后置 + 欢迎标签）：启动一律落工作台外壳
 * （无项目态，不带 ?id=），中央打开「欢迎」标签。项目列表的内容本体搬进工作台左栏的
 * 「项目」面板（components/project-list/ProjectListPane.vue），pages/project-list 退成
 * 直链薄壳（redirectTo 外壳并开该面板）。rail 底部新增账户入口（AccountRailEntry），
 * 顶栏不再放头像与账户 chip。
 *
 * 术语（同名不同物，别看串）：
 *   工作台       = pages/project-overview/project-overview（四列干活界面，不改名）
 *   项目概览     = 一页纸卷轴 ProjectHomePane，宿主是工作台左栏 / project-home 薄壳页
 *   项目列表页   = pages/project-list/project-list（直链薄壳；内容是工作台左栏「项目」面板）
 *
 * 用法：cd frontend && npm run check:nav
 */
import { readFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const FRONTEND = resolve(dirname(fileURLToPath(import.meta.url)), '..')

const readFrontend = (rel) => readFileSync(resolve(FRONTEND, rel), 'utf8')
const hasFile = (rel) => existsSync(resolve(FRONTEND, rel))
const REPO = resolve(FRONTEND, '..')
const readRepo = (rel) => readFileSync(resolve(REPO, rel), 'utf8')
const readFrontendOrNull = (rel) =>
  existsSync(resolve(FRONTEND, rel)) ? readFileSync(resolve(FRONTEND, rel), 'utf8') : null

// pages.json 带 // 行注释，JSON.parse 之前要剥掉；先吃掉字符串字面量避免误伤 URL 里的 //
const stripJsonComments = (s) =>
  s.replace(/"(?:\\.|[^"\\])*"|\/\/[^\n]*/g, (m) => (m.startsWith('"') ? m : ''))

// .vue 源码里做断言前先剥注释：说明性文字（为什么不做某事、为什么这里要 reLaunch）
// 不该把「必须/禁止出现 X」的断言喂饱——包括「必须出现」的正向断言，不止禁字那几条。
//
// 三种注释语法用一个正则的并列分支一次性处理，不能分三次 .replace() 顺序剥：分次剥的话，
// 一条 // 行注释里如果恰好含有 "/*"（真实例子：admin.vue 里 "google/* 仍可用）"，是行注释
// 里描述 glob 写法的大白话），会被后剥的 /* */ 正则误当成块注释开头，一路吞到全文里下一个
// 不相关的 */ 为止，中间几万字符的真代码全部被吃掉。并列分支保证谁先出现在原文里就按谁的
// 语法剥完一整段，不会被更晚出现的另一种注释符号插队。
const stripVueComments = (s) =>
  s.replace(/<!--[\s\S]*?-->|\/\*[\s\S]*?\*\/|^[ \t]*\/\/.*$/gm, '')

// 读 .vue 源码并统一剥注释，供本文件里所有基于源码文本的断言使用（正向/负向都吃这份）。
// 例外：检查「注释内容本身」是否符合预期的断言（例如 App.vue 的路由埋点注释数字）
// 不能用这份，那种场景注释就是被检查的对象，见下方专门保留 readFrontend 的那条。
const readVue = (rel) => stripVueComments(readFrontend(rel))
const readVueOrNull = (rel) => {
  const s = readFrontendOrNull(rel)
  return s === null ? null : stripVueComments(s)
}

// 按大括号配对切出一个方法体，从 marker（例如 'goProjectHome()'，带括号避免命中模板里
// 不带括号的 @tap="goProjectHome" 绑定）出现处开始找第一个 { 之后配对的 }。
// 不用固定字符数窗口：窗口太窄会把方法体截断，太宽会溢出到下一个方法（连同它的注释）——
// 后者曾经让「上一个方法有 reLaunch」冒充成「这个方法有 reLaunch」，改坏了也测不出来。
const extractMethodBody = (src, marker) => {
  const i = src.indexOf(marker)
  if (i < 0) return null
  const braceStart = src.indexOf('{', i)
  if (braceStart < 0) return null
  let depth = 0
  for (let j = braceStart; j < src.length; j++) {
    if (src[j] === '{') depth++
    else if (src[j] === '}') {
      depth--
      if (depth === 0) return src.slice(i, j + 1)
    }
  }
  return null
}

// 按「方法定义」切方法体：只认行首的 `name(...) {` / `async name(...) {`，跳过模板里
// `@tap="name(x)"` 这类调用点。extractMethodBody 从第一次出现处开始找，模板里带括号的调用
// 会让它配错大括号；组件的模板里调用方法越来越多带参数，新断言一律用这个。
const extractDefinition = (src, name) => {
  const re = new RegExp('^[ \\t]*(?:async[ \\t]+)?' + name.replace(/[$]/g, '\\$') + '[ \\t]*\\([^)]*\\)[ \\t]*\\{', 'm')
  const m = re.exec(src)
  if (!m) return null
  return extractMethodBody(src.slice(m.index), m[0].trim())
}

// 深色 chrome 判定按感知亮度算，不按固定十六进制前缀比对——旧写法要么漏判
// #212629（"21262" 后紧跟同为十六进制字符的 "9"，\b 不成立，正则整条不匹配），
// 要么误伤 #2E5A50 墨竹青（"2" + 5 位十六进制字符照样能拼出 "2E5A50"）。
// 同时把 background-color: 也纳入覆盖面（旧正则只认 background:）。
const findDarkChromeBackground = (css) => {
  const re = /background(?:-color)?:\s*#([0-9a-f]{6}|[0-9a-f]{3})\b/gi
  let m
  while ((m = re.exec(css))) {
    let hex = m[1]
    if (hex.length === 3) hex = [...hex].map((c) => c + c).join('')
    const r = parseInt(hex.slice(0, 2), 16)
    const g = parseInt(hex.slice(2, 4), 16)
    const b = parseInt(hex.slice(4, 6), 16)
    const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b // 相对亮度（sRGB 系数）
    if (luminance < 50) return m[0].trim() + ' 相对亮度 ' + luminance.toFixed(1)
  }
  return null
}

// 标签可见性纯函数（dev-board#394），供下面「系统设置是中栏标签」那条直接询问
const { isTabVisibleInPane } = await import('../src/pages/project-overview/tabVisibility.js')

const failures = []
const check = (name, fn) => {
  let msg
  try {
    msg = fn()
  } catch (e) {
    msg = '检查本身抛异常: ' + (e && e.message)
  }
  if (msg) failures.push(name + ' — ' + msg)
}

// 全量层：只在 CHECK_NAV_FULL=1 时执行（npm run check:nav:full，CI 用它）。
// 它校验的是同一个 PR 里别的批次的产出——概览页容器与它的五个子组件、领域文档、
// app-e2e 旅程。那些还没落地时全量层必红，属预期，所以日常 npm run check:nav 不跑它。
const FULL = process.env.CHECK_NAV_FULL === '1'
let skipped = 0
const checkFull = (name, fn) => {
  if (!FULL) { skipped++; return }
  check(name, fn)
}

const NOT_YET = '文件尚未落地（由项目概览页组 / e2e + 文档组产出）'

const LIST_ROUTE = 'pages/project-list/project-list'
const WORKBENCH_ROUTE = 'pages/project-overview/project-overview'
const HOME_ROUTE = 'pages/project-home/project-home'

// ==================== 路由注册 ====================

const pages = JSON.parse(stripJsonComments(readFrontend('src/pages.json'))).pages
const pageByPath = new Map(pages.map((p) => [p.path, p]))

check('pages.json 注册 ' + LIST_ROUTE, () => {
  const p = pageByPath.get(LIST_ROUTE)
  if (!p) return '未注册'
  if (!p.style || p.style.navigationStyle !== 'custom') {
    return 'style.navigationStyle 必须显式写 custom（globalStyle 里没有这一项，漏写会得到系统导航栏）'
  }
  return null
})

check('工作台路由不许改名', () =>
  pageByPath.has(WORKBENCH_ROUTE) ? null : WORKBENCH_ROUTE + ' 不在 pages.json 里'
)

// 项目列表的内容本体（dev-board#1047 起是工作台左栏「项目」面板）与它的样式
const LIST_PANE = 'src/components/project-list/ProjectListPane.vue'
const LIST_PANE_SCSS = 'src/components/project-list/project-list-pane.scss'

check('项目列表页薄壳与「项目」面板的文件都存在', () => {
  const missing = [
    'src/pages/project-list/project-list.vue',
    LIST_PANE,
    LIST_PANE_SCSS,
  ].filter((f) => !hasFile(f))
  if (hasFile('src/pages/project-list/project-list.scss')) {
    return '页面样式已随内容本体搬进 ' + LIST_PANE_SCSS + '，薄壳页不该再有一份'
  }
  return missing.length ? '缺文件: ' + missing.join(', ') : null
})

// ==================== 项目列表页样式 ====================

// 说明：SCSS 里选择器后面必然跟空格、换行或逗号，用这三种收尾判存在，避免
// 「.stat-card」被「.stat-card-x」这类前缀关系误判成已存在。
const hasSelector = (css, sel) =>
  css.includes(sel + ' ') || css.includes(sel + '\n') || css.includes(sel + ',')

check('project-list-pane.scss 搬齐了必需的样式块', () => {
  const css = readFrontend(LIST_PANE_SCSS)
  // .btn-primary-small 已被 .awd-btn/.awd-btn-primary 取代（命名弹窗按钮改走 awd-* 视觉语言）；
  // .card-deco-header 已随卡片重设计删除（不再用顶部 4px 色条区分项目类型，见「Card 重设计」提交）——
  // 两处都是有意的设计变更，不是搬迁遗漏，从必需清单里去掉。
  const need = [
    '.project-list-pane', '.project-list-container', '.main-content',
    '.content-header', '.header-actions', '.awd-btn', '.awd-btn-primary', '.btn-secondary-small',
    '.cloud-accept-entry', '.projects-stats-row', '.stat-card',
    '.project-grid', '.project-item-card', '.action-btn-icon',
    '.project-title-new', '.card-footer-new', '.member-avatar-new', '.add-member-btn-new',
    '.enter-btn-arrow', '.empty-state-dashed', '.dashed-icon',
    '.project-role-badge', '.role-owner', '.role-text',
    '.manager-avatar-wrapper', '.members-split-container', '.clients-group',
    '.act-glyph', '.badge-glyph',
  ].filter((sel) => !hasSelector(css, sel))
  return need.length ? '缺样式块: ' + need.join(', ') : null
})

check('project-list-pane.scss 补齐了原页面无定义的三个 class', () => {
  const css = readFrontend(LIST_PANE_SCSS)
  const miss = ['.panel-projects', '.loading-state', '.loading-text'].filter((s) => !css.includes(s))
  return miss.length ? '未补: ' + miss.join(', ') : null
})

check('project-list-pane.scss 不许把两块死样式搬过来', () => {
  const css = readFrontend(LIST_PANE_SCSS)
  const dead = ['.modal-mask', '.project-members', '.member-list'].filter((s) => css.includes(s))
  return dead.length ? '搬进了模板里已无命中的死样式: ' + dead.join(', ') : null
})

check('project-list-pane.scss 守浅色外壳红线', () => {
  const css = readFrontend(LIST_PANE_SCSS)
  if (!css.includes('#2E5A50')) return '缺墨竹青 #2E5A50'
  if (!css.includes('#F1EFE7')) return '缺浅底 #F1EFE7'
  const dark = findDarkChromeBackground(css)
  if (dark) return '外壳不做深色 chrome（' + dark + '）'
  return null
})

// ==================== 项目列表页脚本 ====================

check('项目列表页薄壳根节点带 e2e 锚点类名，「项目」面板根节点是 .project-list-pane', () => {
  const src = readVue('src/pages/project-list/project-list.vue')
  if (!src.includes('class="page-project-list"')) return '薄壳页根节点必须是 .page-project-list（e2e 锚点）'
  const pane = readVue(LIST_PANE)
  return /class="project-list-pane"/.test(pane) ? null : '「项目」面板根节点必须是 .project-list-pane'
})

check('项目列表页是直链薄壳：redirectTo 工作台外壳并打开「项目」面板', () => {
  const src = readVue('src/pages/project-list/project-list.vue')
  const body = extractMethodBody(src, 'onLoad()')
  if (!body) return '薄壳页缺 onLoad()'
  const SHELL = '/pages/project-overview/project-overview?pane=projects'
  if (!src.includes(SHELL)) return '没有转到 ' + SHELL
  if (!body.includes('uni.redirectTo({ url: SHELL_URL })')) {
    return '单页栈（reLaunch 进来：登录成功 / 菜单「关闭项目」）要用 redirectTo 同级替换，栈深度保持 1'
  }
  if (!body.includes('getCurrentPages') || !body.includes('uni.reLaunch({ url: SHELL_URL })')) {
    return '栈里还压着别的页时必须改 reLaunch：底下若是活着的工作台，redirectTo 会让两个工作台实例并存'
  }
  if (/uni\.navigateTo\(/.test(src)) return '薄壳页不许 navigateTo'
  if (src.includes('<ProjectListPane') || src.includes('getMyProjects')) {
    return '薄壳页只做转发，内容本体在工作台左栏的「项目」面板里'
  }
  return null
})

check('项目列表页角色文案收敛到 config/memberRoles.js', () => {
  const src = readVue(LIST_PANE)
  if (!/from\s+'@\/config\/memberRoles\.js'/.test(src)) return "没有从 '@/config/memberRoles.js' 引入"
  if (/'PARTICIPANT'\s*:/.test(src)) return '页面里还残留自己硬编码的角色映射表'
  return null
})

check('「项目」面板点卡片直达工作台（走注入的 leaveWorkbench 先落盘，回落 reLaunch）', () => {
  const src = readVue(LIST_PANE)
  const body = extractDefinition(src, 'goToProject')
  if (!body) return '找不到 goToProject 的定义'
  if (!body.includes('/pages/project-overview/project-overview?id=')) {
    return 'goToProject 没有指向工作台（概览已收进工作台，中间那一跳已取消）'
  }
  if (!body.includes('this.leaveWorkbench(')) {
    return '面板挂在工作台里：进另一个项目是离开当前工作台，必须先走注入的 leaveWorkbench 落盘'
  }
  if (!body.includes('uni.reLaunch')) {
    return '没有注入时的回落必须是 reLaunch（navigateTo 会堆出两个存活的工作台实例）'
  }
  if (src.includes('/pages/project-home/project-home')) {
    return '列表不该再指向概览独立页（那一跳已取消）'
  }
  return null
})

check('项目列表页自带两个新建入口，且没有「打开单个文件」', () => {
  const src = readVue(LIST_PANE)
  const miss = ['openFolderFlow', 'createFolderFlow'].filter((f) => !src.includes(f))
  if (miss.length) return '缺新建入口: ' + miss.join(', ')
  if (src.includes('openFileFlow')) {
    return '「打开单个文件」造出的是没有归属的临时项目，已从新建入口去掉'
  }
  if (!src.includes('namingVisible')) return '新建项目文件夹的命名弹窗没搬过来'
  return null
})

check('项目列表页删掉了写死 0 的两张统计卡', () => {
  // 禁字断言只看实际代码：注释里要写清楚「原先的进行中/已完成是写死的 0」，
  // 那段说明性文字不该把断言判红。
  const src = readVue(LIST_PANE)
  if (src.includes('进行中') || src.includes('已完成')) {
    return 'Project 实体没有状态字段，这两张卡的数字是写死的字面量 0，不许搬过来'
  }
  const cards = (src.match(/class="stat-card"/g) || []).length
  return cards === 1 ? null : '统计条应当只剩「全部项目」一张卡，实际 ' + cards + ' 张'
})

check('项目列表页「从团队案件库取一份案卷」入口开放（官方案件库 dev-board#439/#440），仍经 SHOW_CLOUD_ACCEPT 门控', () => {
  // 曾因自建案件库令人困惑而收起（用户反馈 5，SHOW_CLOUD_ACCEPT=false）；官方案件库零配置直连后
  // 它是被邀请方取回案卷的唯一入口（#444 邀请话术第 2 步指的就是它），必须开着。
  const src = readVue(LIST_PANE)
  if (!src.includes('<CloudAcceptDialog')) return '弹窗组件没搬过来'
  if (!/const\s+SHOW_CLOUD_ACCEPT\s*=\s*true/.test(src)) {
    return '两个入口应当经 SHOW_CLOUD_ACCEPT 门控且为 true——被邀请的同事没有别的取回入口'
  }
  // 1 处 method 定义 + 2 处入口绑定（有项目态顶部按钮 / 空项目态入口）；门控只加在
  // 各自的 v-if 上，openCloudAccept 这个方法名出现的次数不会因此减少
  const entries = (src.match(/openCloudAccept/g) || []).length
  return entries >= 3 ? null : 'openCloudAccept 只出现 ' + entries + ' 次，方法定义或两个入口绑定被删掉了'
})

check('项目列表页对 CLIENT 收起写操作入口', () => {
  const src = readVue(LIST_PANE)
  if (!/isClientUser\s*\(\)/.test(src)) return '缺 isClientUser computed'
  if (!src.includes('v-if="!isClientUser" class="create-section"')) {
    return '页头下方的新建操作行没有对 CLIENT 隐藏'
  }
  if (!src.includes('canManageMembers')) return '成员增删没有对 CLIENT 收起'
  return null
})

check('项目列表页别把裸数组当信封解', () => {
  const src = readVue(LIST_PANE)
  if (/getMyProjects\(\)[\s\S]{0,80}\.data/.test(src)) {
    return 'getMyProjects 返回裸数组（ProjectController.java:193-200），取 .data 会恒空'
  }
  return null
})

// ==================== 个人中心并入统一「设置」页 ====================
// 2026-08-20：个人中心不再是独立面板，它是 components/admin/AdminPane.vue 里
// 「个人」组的四个栏目（内容各自成组件，放在 components/userprofile/ 下）。
// 下面这几条接着守原来那五条的东西：默认不落空白页、项目那摊没被带回来、
// 该留的没被搬丢；外加两条新不变式（旧实体已消失、定时器仍在清）。

const PERSONAL_PANELS = [
  'src/components/userprofile/PersonalWorkLogPanel.vue',
  'src/components/userprofile/PersonalFavoritesPanel.vue',
  'src/components/userprofile/PersonalTodosPanel.vue',
  'src/components/userprofile/PersonalSettingsPanel.vue',
]

check('统一设置页有完整的「个人」组，且没把搬走的 projects 带回来', () => {
  const src = readVue('src/components/admin/AdminPane.vue')
  const missing = ['work_log', 'favorites', 'todos', 'personal_settings']
    .filter((k) => !new RegExp("key: '" + k + "'[^\\n]*group: 'personal'").test(src))
  if (missing.length) return '个人组缺: ' + missing.join(', ')
  if (/key:\s*'projects'/.test(src)) return "navItems 里冒出了 projects——那一栏 2026-08 搬去项目列表页了"
  if (!src.includes("group: 'system'")) return '系统组的 group 标记没了，两组会挤成一堆'
  return null
})

check('统一设置页的默认落点是可见的面板', () => {
  const src = readVue('src/components/admin/AdminPane.vue')
  // 非管理员看不见「系统」组，默认值还写死 'ai' 的话他进来就是一张空白页
  if (!src.includes("activeNav: cachedIsAdmin() ? 'ai' : 'work_log'")) {
    return "activeNav 默认值要按 cachedIsAdmin() 分流（管理员 'ai'，其余 'work_log'）"
  }
  if (!src.includes("if (n.group === 'system' && !this.isAdminUser) return false")) {
    return 'visibleNavItems 没有把系统组按 isAdmin 收起（原个人中心 checkAdminTab 那条规则）'
  }
  return null
})

check('个人组四栏各自有人给它加载数据', () => {
  // 四段内容只在被选中时渲染，加载时机就是各自的 mounted。少一处就是一张永远空白的栏目。
  const log = readVue('src/components/userprofile/PersonalWorkLogPanel.vue')
  if (!extractMethodBody(log, 'mounted()').includes('this.loadActivityLogs()')) {
    return '工作记录的 mounted 里没有 loadActivityLogs()'
  }
  const fav = readVue('src/components/userprofile/PersonalFavoritesPanel.vue')
  if (!extractMethodBody(fav, 'mounted()').includes('this.loadFavorites()')) {
    return '我的收藏的 mounted 里没有 loadFavorites()'
  }
  const set = readVue('src/components/userprofile/PersonalSettingsPanel.vue')
  if (!extractMethodBody(set, 'mounted()').includes('this.loadUserInfo()')) {
    return '账户与安全的 mounted 里没有 loadUserInfo()'
  }
  return null
})

check('个人组没把项目那摊带回来', () => {
  const left = []
  for (const f of PERSONAL_PANELS) {
    const src = readVue(f)
    for (const s of ['project-item-card', 'panel-projects', 'loadProjects', 'goToProject',
      'handleDeleteProject', 'CloudAcceptDialog', 'InviteMemberDialog', 'getRoleLabel',
      'getMyProjects', 'deleteProject', 'renameProject', 'getProjectMembers', 'getProjectTypeLabel']) {
      if (src.includes(s)) left.push(f.split('/').pop() + ':' + s)
    }
  }
  return left.length ? '还残留: ' + left.join(', ') : null
})

check('个人组保住了不该删的东西', () => {
  const log = readVue('src/components/userprofile/PersonalWorkLogPanel.vue')
  const gone = ['formatTime(', 'formatDateTime(', 'loadActivityLogs', 'exportLogsToExcel']
    .filter((s) => !log.includes(s))
  const fav = readVue('src/components/userprofile/PersonalFavoritesPanel.vue')
  gone.push(...['loadFavorites', 'deleteFavorite', 'getFavoriteImageUrl'].filter((s) => !fav.includes(s)))
  const set = readVue('src/components/userprofile/PersonalSettingsPanel.vue')
  gone.push(...['totpSetup', 'bindPhone', 'bindEmail', 'listDeviceTokens', 'getLicenseStatus',
    'setAppLanguage', 'signOut'].filter((s) => !set.includes(s)))
  return gone.length ? '误删: ' + gone.join(', ') : null
})

check('账户与安全仍然清那两个验证码倒计时', () => {
  // 统一设置页是常驻工作台的标签，不会随导航销毁重建；不清定时器会跨标签泄漏。
  // 这是修过的坑（PR#424），搬家时最容易掉的就是它。
  const src = readVue('src/components/userprofile/PersonalSettingsPanel.vue')
  const body = extractMethodBody(src, 'beforeUnmount()')
  if (!body) return '缺 beforeUnmount()'
  for (const t of ['bindCountdownTimer', 'bindEmailCountdownTimer']) {
    if (!body.includes('clearInterval(this.' + t + ')')) return '没清 ' + t
  }
  return null
})

check('旧的个人中心实体已经不在了', () => {
  if (hasFile('src/components/userprofile/UserProfilePane.vue')) {
    return 'UserProfilePane.vue 还在——两个设置入口的根因就是它，内容已并进 AdminPane'
  }
  for (const f of ['src/pages/userprofile/userprofile.vue', 'src/pages/project-overview/project-overview.vue',
    'src/components/admin/AdminPane.vue']) {
    if (readVue(f).includes('UserProfilePane')) return f + ' 还引用着 UserProfilePane'
  }
  return null
})

// ==================== 导航入口与出口 ====================

const USERPROFILE_ROUTE = '/pages/userprofile/userprofile'
const countOf = (s, sub) => s.split(sub).length - 1

check('launch 一律落工作台外壳（无项目态，不带 id），启动不设解锁门（dev-board#1047）', () => {
  const src = readVue('src/pages/launch/launch.vue')
  if (src.includes(USERPROFILE_ROUTE)) return '还指着个人中心'
  if (!src.includes("uni.reLaunch({ url: '/pages/project-overview/project-overview' })")) {
    return '没有 reLaunch 到不带 id 的工作台外壳'
  }
  if (src.includes('/pages/project-overview/project-overview?')) {
    return '启动不直达某一个项目：落无项目态外壳，欢迎标签的 Recent 负责「回到上次那个」'
  }
  if (src.includes('/pages/project-list/project-list')) return '启动不再落项目列表页（它已是工作台左栏的「项目」面板）'
  if (src.includes('/pages/unlock/unlock')) return '启动不设解锁门：需要账户的功能在用到时就地登录'
  if (/status\.unlocked/.test(src)) return 'unlocked 不再参与启动分流'
  if (src.includes('getMyProjects')) return '项目清单不再是启动分流条件（拉不到也照样进外壳）'
  return null
})

check('login 四处落点全改项目列表页', () => {
  const src = readVue('src/pages/login/login.vue')
  if (src.includes(USERPROFILE_ROUTE)) return '还有指着个人中心的落点'
  const n = countOf(src, '/pages/project-list/project-list')
  if (n !== 4) return '应当恰好四处（CLIENT 分支 / 无最近项目兜底 / 登录成功 / 注册成功），实际 ' + n
  // 只数 URL 出现次数不看跳转方式，四处里有一处被悄悄换成 navigateTo 也测不出来
  // （navigateTo 会把登录页留在页面栈里，退回去又是登录表单）。逐处校验紧邻的调用。
  let idx = -1
  for (let k = 0; k < n; k++) {
    idx = src.indexOf('/pages/project-list/project-list', idx + 1)
    const before = src.slice(Math.max(0, idx - 40), idx)
    if (before.includes('navigateTo')) return '第 ' + (k + 1) + ' 处误用 navigateTo，登录页会留在页面栈里'
    if (!before.includes('reLaunch')) return '第 ' + (k + 1) + ' 处不是 reLaunch'
  }
  if (!src.includes('/pages/project-overview/project-overview?id=')) return '会话恢复直达工作台那条被改坏了'
  return null
})

// v0.49.0 BUG-07：newproject 既会被列表页 navigateTo 进来，也会被菜单「文件 > 新建项目…」
// reLaunch 进来（栈里只有它自己）。无脑 navigateTo 回列表要么堆出第二个列表实例，要么把栈
// 压成两层——两种情形全局返回键都会残留。与概览薄壳页 goProjectList 同一分流。
check('newproject 返回项目列表页按页面栈分流（navigateBack / redirectTo，不许 navigateTo）', () => {
  const src = readVue('src/pages/newproject/index.vue')
  if (src.includes(USERPROFILE_ROUTE)) return '还指着个人中心'
  if (src.includes("navigateTo({ url: '/pages/project-list/project-list' })")) {
    return 'navigateTo 回列表会堆实例或把 reLaunch 进来的单页栈压成两层，全局返回键残留'
  }
  const i = src.indexOf('goToProjectList() {')
  if (i < 0) return '缺 goToProjectList()'
  const body = src.slice(i, i + 1200)
  if (!body.includes('getCurrentPages') || !body.includes('navigateBack') ||
      !body.includes("redirectTo({ url: '/pages/project-list/project-list' })")) {
    return '必须两条分支：栈里上一页是列表页就 navigateBack，否则 redirectTo'
  }
  if (src.includes('goToUserProfile')) return '方法名还叫 goToUserProfile，与它现在的去向不符'
  if (countOf(src, 'goToProjectList') !== 3) {
    return 'goToProjectList 应当恰好 3 处（1 处定义 + 模板两处绑定），实际 ' + countOf(src, 'goToProjectList')
  }
  return null
})

check('newproject 按钮文案与跳转目标一致（不许挂着"个人中心"却跳项目列表）', () => {
  const src = readVue('src/pages/newproject/index.vue')
  let idx = -1
  while ((idx = src.indexOf('goToProjectList', idx + 1)) !== -1) {
    if (src.slice(idx, idx + 200).includes('个人中心')) {
      return '@tap 指向 goToProjectList，附近文案却还写着「个人中心」，与实际跳转目标不符'
    }
  }
  return null
})

check('工作台「全部项目」开左栏「项目」面板，不离开工作台；统一出口 leaveWorkbench 仍先落盘', () => {
  const src = readVue('src/pages/project-overview/project-overview.vue')
  const body = extractMethodBody(src, 'goAllProjects()')
  if (!body) return '找不到 goAllProjects'
  if (!body.includes('this.openProjectsPane()')) return 'goAllProjects 应当调 openProjectsPane() 打开左栏「项目」面板'
  if (/uni\.(reLaunch|navigateTo|redirectTo)/.test(body) || body.includes('leaveWorkbench')) {
    return '项目列表已经是工作台里的面板，「全部项目…」不该再离开工作台'
  }
  const exit = extractMethodBody(src, 'async leaveWorkbench(url)')
  if (!exit) return '找不到统一出口 leaveWorkbench'
  if (!exit.includes('uni.reLaunch')) return 'leaveWorkbench 必须用 reLaunch（工作台参与的跳转一律 reLaunch）'
  if (!exit.includes('flushDirtyEditors')) return '离开工作台前必须先落盘'
  return null
})

check('账户入口在 rail 底部（AccountRailEntry），下拉恰好三项：我的日程 + 设置 + 退出登录（dev-board#205 / #899 / #1047）', () => {
  // 沿革：2026-08-20 个人中心并进设置后下拉只剩一项，2026-08-21（dev-board#96）撤下拉、
  // 点头像直开设置；2026-08-27（dev-board#205）「退出登录」要有一级入口，下拉恢复成
  // 两项；2026-09-25（dev-board#899）加「我的日程」成三项。2026-09-29（dev-board#1047）
  // 头像连同下拉从顶栏挪到 rail 底部的账户入口（对应 VS Code 的 Accounts），顶栏不再放账户态。
  const src = readVue('src/pages/project-overview/project-overview.vue')
  for (const dead of ['openUserProfileTab', 'goToUserProfile', "workbench.profile", "'user-profile'"]) {
    if (src.includes(dead)) return '还残留个人中心标签那一套: ' + dead
  }
  for (const gone of ['class="header-account"', 'class="avatar-btn"', 'class="trial-chip', 'avatarMenuOpen']) {
    if (src.includes(gone)) return '顶栏不再放账户态与 chip，残留: ' + gone
  }
  const rail = src.slice(src.indexOf('<view class="left-rail">'), src.indexOf('<FilePickerDialog'))
  const tag = rail.slice(rail.indexOf('<AccountRailEntry'), rail.indexOf('/>', rail.indexOf('<AccountRailEntry')))
  if (!tag.startsWith('<AccountRailEntry')) return 'rail 里没有 <AccountRailEntry>'
  if (/\bv-if=/.test(tag)) return '账户入口有无项目两态都渲染、不按 isClientView 收（客户也有自己的个人组）'
  for (const [ev, handler] of [['@schedule', 'onAvatarMenuSchedule'], ['@settings', 'onAvatarMenuSettings'], ['@sign-out', 'onAvatarMenuSignOut'], ['@login', 'onAccountLogin']]) {
    if (!tag.includes(`${ev}="${handler}"`)) return `账户入口没有把 ${ev} 接到 ${handler}`
  }
  // 「我的日程」开中栏日程标签（dev-board#1048），不再离开工作台
  const sched = extractMethodBody(src, 'onAvatarMenuSchedule() {')
  const goCal = extractMethodBody(src, 'goCalendar() {')
  if (!sched || !(sched.includes('this.openCalendarTab(') || (sched.includes('this.goCalendar(') && goCal && goCal.includes('this.openCalendarTab(')))) {
    return '「我的日程」没有开中栏日程标签（openCalendarTab）'
  }
  // 退出必须走唯一编排，不许在页面里自拼 disconnect/deactivate
  if (!src.includes("from '@/utils/signOut.js'")) return '退出登录没有走 utils/signOut.js 唯一编排'

  const entry = readVue('src/components/account/AccountRailEntry.vue')
  const menuIdx = entry.indexOf('class="avatar-menu')
  if (menuIdx < 0) return 'AccountRailEntry 里找不到下拉 .avatar-menu'
  const menu = entry.slice(menuIdx, entry.indexOf('</template>', menuIdx))
  const actions = menu.match(/class="avatar-menu-item/g) || []
  if (actions.length !== 3) return `下拉动作项应恰好三项，实际 ${actions.length} 项`
  for (const ev of ['schedule', 'settings', 'sign-out']) {
    if (!menu.includes(`emitAndClose('${ev}')`)) return `下拉里没有 ${ev} 项`
  }
  // 登录就地弹层（dev-board#1046）合入前的留位：未登录点击发 login，宿主暂时开 unlock 薄壳页
  const onTap = extractMethodBody(entry, 'onTap() {')
  if (!onTap || !onTap.includes("this.$emit('login')")) return '未登录态点击没有 emit login'
  if (!readFrontend('src/components/account/AccountRailEntry.vue').includes('TODO(#1046): requireAccount')) {
    return '「登录」处缺 TODO(#1046): requireAccount 留位标记'
  }
  return null
})

check('四条直达工作台的出口一条都没动', () => {
  const bad = []
  // 2026-08-16：应用菜单的派发从 App.vue 收口进 appMenuBridge，「最近打开」与
  // 「切换项目」两条都落在那里。行为不变（仍是 reLaunch 直达工作台），锚点跟着搬。
  if (!readFrontend('src/utils/appMenuBridge.js').includes('/pages/project-overview/project-overview?id=')) bad.push('appMenuBridge 应用菜单「最近打开」')
  if (!readFrontend('src/utils/ideOpen.js').includes('/pages/project-overview/project-overview?')) bad.push('ideOpen.js 打开本地文件夹/文件')
  const ov = readVue('src/pages/project-overview/project-overview.vue')
  const i = ov.indexOf('switchToProject(p) {') // 方法定义；'switchToProject(p)' 会先命中模板里的 @tap 调用
  if (i < 0 || !ov.slice(i, i + 400).includes('/pages/project-overview/project-overview?id=')) bad.push('顶栏切换器 switchToProject')
  return bad.length ? '被改坏: ' + bad.join(', ') : null
})

check('admin 切换本机工作区仍清最近项目', () => {
  // 设置的实体 2026-08-19 搬进 components/admin/AdminPane.vue（pages/admin 退成薄壳），
  // 断言跟着搬。
  const src = readVue('src/components/admin/AdminPane.vue')
  return src.includes("removeStorageSync('checkba_last_project_id')")
    ? null
    : '删了这行会让切身份之后仍直达上一个身份的项目'
})

check('系统设置在工作台里是中栏标签，不是跳页', () => {
  const src = readVue('src/pages/project-overview/project-overview.vue')
  const body = extractMethodBody(src, 'goToSystemSettings(opts)')
  if (!body) return '找不到 goToSystemSettings'
  if (body.includes('/pages/admin/admin')) {
    return '不许再跳独立页：那等于把整个工作台（标签、编辑器、AI 会话）换成一页设置'
  }
  if (!body.includes('openSettingsTab')) return '应当调 openSettingsTab() 开中栏标签'
  const tab = extractMethodBody(src, 'openSettingsTab(opts)')
  if (!tab) return '缺 openSettingsTab()'
  if (!tab.includes("tabType: 'admin-settings'")) return '标签没有带 tabType: admin-settings'
  // 深链（nav / service）在 tab 形态下要有等价物：网关错误提示的逃生门指着它
  if (!tab.includes('opts.nav') || !tab.includes('opts.service')) {
    return 'openSettingsTab 没有接 nav/service，?nav=platform&service=ocr 的逃生门在工作台里就断了'
  }
  // 标签可见性 2026-09-02 起收敛成纯函数 tabVisibility.js（dev-board#394：标签常驻、
  // 与左栏面板解耦）。这里直接问那个函数，而不是 grep 组件里的分支——分支已经没了。
  const vis = extractMethodBody(src, 'isTabVisible(file) {')
  if (!vis || !vis.includes('isTabVisibleInPane(')) {
    return 'isTabVisible 不再委托 tabVisibility.js 的 isTabVisibleInPane，可见性契约失去单测覆盖'
  }
  for (const pane of ['files', 'home', 'market', 'version']) {
    if (!isTabVisibleInPane({ id: 'admin', tabType: 'admin-settings' }, pane)) {
      return `isTabVisibleInPane 在左栏 ${pane} 面板下藏了 admin-settings 标签，点菜单会开一个被 v-show 藏死的标签`
    }
  }
  return null
})

check('pages/admin 薄壳页仍在，且把 query 透给 AdminPane', () => {
  const src = readVue('src/pages/admin/admin.vue')
  if (!src.includes('<AdminPane')) return '薄壳页没有挂 AdminPane'
  if (!src.includes('query.nav') || !src.includes('query.service')) {
    return '薄壳页没有透传 ?nav= / ?service=，仓里十来处深链会全部落在默认面板上'
  }
  return null
})

check('pages/userprofile 薄壳页仍在，且挂的是统一设置面板', () => {
  // 选项 A：路由与仓里既有的 navigateTo '/pages/userprofile/userprofile'
  //（应用菜单「账户」、项目列表页按钮）一条都不动，只把落地内容换成统一设置页，
  // 初始落在个人组第一栏——与老页面进来看到的东西一致。
  const src = readVue('src/pages/userprofile/userprofile.vue')
  if (!src.includes('<AdminPane')) return '薄壳页没有挂 AdminPane'
  if (!src.includes('initial-nav="work_log"')) return '薄壳页没有落在个人组的「工作记录」'
  return null
})

// ==================== 工作台通往概览页的入口 ====================

check('工作台里「项目概览」是开左栏面板，不是跳页也不是中栏标签', () => {
  const src = readVue('src/pages/project-overview/project-overview.vue')
  if (!src.includes('switcher-home')) return '模板里缺 .switcher-home 一项'
  const body = extractMethodBody(src, 'goProjectHome()')
  if (!body) return 'goProjectHome 不在 methods 里'
  if (body.includes('/pages/project-home/project-home')) {
    return '不许再跳独立页：那等于把整个工作台（标签、编辑器、AI 会话）拆掉换成一页只读卷轴'
  }
  // 2026-08-19：概览从中栏标签改成左栏面板——rail 上的按钮点了应该开左栏，
  // 这是 rail 其余每一项的语义，概览不该例外。
  if (!body.includes("toggleLeftPane('home')")) {
    return "应当调 toggleLeftPane('home') 打开左栏概览面板"
  }
  // 「项目概览」必须排在「全部项目…」之前（两者的首次出现都在模板里）
  if (src.indexOf('switcher-home') > src.indexOf('switcher-all')) {
    return '「项目概览」应当排在「全部项目…」之前'
  }
  return null
})

check('rail 第一项是项目概览，左栏渲染 ProjectHomePane', () => {
  const rail = readFrontend('src/config/leftSidebarPlugins.js')
  const i = rail.indexOf('LEFT_SIDEBAR_PLUGINS = [')
  if (i < 0) return '找不到 LEFT_SIDEBAR_PLUGINS'
  const firstKey = rail.slice(i).match(/key:\s*'([^']+)'/)
  if (!firstKey || firstKey[1] !== 'home') {
    return 'rail 第一项应当是项目概览（key: home），实际是 ' + (firstKey ? firstKey[1] : '空')
  }
  const src = readVue('src/pages/project-overview/project-overview.vue')
  if (!src.includes('<ProjectHomePane')) return '左栏没有渲染 ProjectHomePane'
  if (!src.includes("leftPaneKey === 'home'")) {
    return "左栏没有 leftPaneKey === 'home' 这条分支，点 rail 会落到「加载中…」占位符"
  }
  return null
})

check('leftPaneKey 存量值有迁移兜底', () => {
  const cfg = readFrontend('src/config/leftSidebarPlugins.js')
  if (!cfg.includes('migrateLeftPaneKey')) return '缺 migrateLeftPaneKey'
  // 语音两项合并（easyvoice / meeting-recorder → voice）后，存量 storage 里的
  // 旧 key 必须映射得到；不映射就会落在一个没有面板分支命中的 key 上
  for (const k of ['easyvoice', 'meeting-recorder']) {
    if (!cfg.includes(`'${k}'`) && !cfg.includes(`${k}:`)) return '迁移表里没有 ' + k
  }
  const src = readVue('src/pages/project-overview/project-overview.vue')
  return src.includes('migrateLeftPaneKey(savedKey)')
    ? null
    : '工作台恢复 leftPaneKey 时没有过迁移表'
})

check('switcher-home 有对应样式', () => {
  const css = readFrontend('src/pages/project-overview/project-overview.scss')
  return css.includes('.switcher-home') ? null : 'project-overview.scss 里没有 .switcher-home'
})

check('工作台消费概览页带来的 conversationId', () => {
  const src = readVue('src/pages/project-overview/project-overview.vue')
  const i = src.indexOf('onLoad(query)')
  if (i < 0) return '找不到 onLoad(query)'
  const body = src.slice(i, i + 3000)
  if (!body.includes('query.conversationId')) {
    return 'onLoad 没有读 conversationId——概览页点历史对话进来会停在当前会话'
  }
  if (!body.includes('loadHistoryChat(')) return '读了 conversationId 却没有打开那条会话'
  if (!body.includes('showAiPanel')) return '右侧 AI 面板默认收起，不打开它 $refs.chatInterface 不存在'
  return null
})

// ==================== 概览页路由与埋点注释 ====================

check('pages.json 注册 ' + HOME_ROUTE, () => {
  const p = pageByPath.get(HOME_ROUTE)
  if (!p) return '未注册'
  if (!p.style || p.style.navigationStyle !== 'custom') {
    return 'style.navigationStyle 必须显式写 custom（globalStyle 里没有这一项，漏写会得到系统导航栏）'
  }
  return null
})

check('App.vue 的路由埋点注释与 pages.json 对得上', () => {
  const src = readFrontend('src/App.vue')
  const n = pages.length
  return src.includes(`pages.json 里的 ${n} 个页面`)
    ? null
    : `注释里的页面数与 pages.json 实际的 ${n} 个对不上`
})

check('CI 跑导航护栏', () => {
  const yml = readRepo('.github/workflows/ci.yml')
  return yml.includes('npm run check:nav:full') ? null : 'ci.yml 里没有 check:nav:full 这一步'
})

check('邀请话术仍指向真看得见的入口', () => {
  // i18n 迁移后 zh 文案实体在 locale 文件里（组件里只剩 $t 键），
  // 契约不变：话术短语必须存在于组件或对应 zh locale 之一
  const src = readVue('src/components/collab/CollabDialog.vue')
    + readVue('src/locales/zh-CN/version.js')
  if (!src.includes('打开项目列表')) return '话术被改坏了'
  const list = readVue('src/pages/project-list/project-list.vue')
    + readVue('src/locales/zh-CN/projects.js')
  return list.includes('从团队案件库取一份案卷') ? null : '话术指的入口在项目列表页上不存在'
})

// ==================== 全量层：别的批次的产出 ====================

const HOME_VUE = 'src/pages/project-home/project-home.vue'

checkFull('概览页容器带全三个 e2e 锚点类名', () => {
  const src = readVueOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  const miss = ['page-project-home', 'btn-project-list', 'btn-workbench'].filter((c) => !src.includes(c))
  return miss.length ? '缺 e2e 锚点: ' + miss.join(', ') : null
})

checkFull('概览的五个内容区块在 ProjectHomePane 里（两个宿主共用同一份）', () => {
  const src = readVueOrNull('src/components/project-home/ProjectHomePane.vue')
  if (src === null) return NOT_YET
  const miss = ['<ProfileHeader', '<OverviewStatsBar', '<ActivityFeed', '<TaskSchedule', '<ConversationList']
    .filter((t) => !src.includes(t))
  if (miss.length) return '缺子组件: ' + miss.join(', ')
  const shell = readVueOrNull(HOME_VUE)
  if (shell === null) return NOT_YET
  if (!shell.includes('<ProjectHomePane')) return '概览薄壳页没有挂 ProjectHomePane'
  return null
})

checkFull('概览页登记最近项目', () => {
  const src = readVueOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  if (!/from\s+'@\/utils\/recentProjects\.js'/.test(src)) return "没有从 '@/utils/recentProjects.js' 引入"
  if (!src.includes('recordProjectVisit(')) return '没有调 recordProjectVisit'
  return null
})

checkFull('概览页用自己的活跃实例指针', () => {
  const src = readVueOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  // 「必须出现」与「不许出现」都看去注释后的代码：概览页的注释里要解释
  // 「为什么不复用 __checkbaActiveOverviewVm」，那段说明性文字不该把任何一条断言判红/判绿。
  if (!src.includes('__checkbaProjectHomeVm')) return '缺活跃实例指针守卫'
  if (src.includes('__checkbaActiveOverviewVm')) {
    return '复用了工作台的指针，会让工作台的全局事件被概览页拦掉'
  }
  return null
})

checkFull('概览页 → 工作台用 reLaunch 并透传 openFileId', () => {
  const src = readVueOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  const body = extractMethodBody(src, 'goWorkbench()')
  if (!body) return '缺 goWorkbench()'
  if (!body.includes('reLaunch')) return '进入工作台必须用 reLaunch（工作台参与的跳转一律 reLaunch）'
  if (!body.includes('/pages/project-overview/project-overview')) return '目标不是工作台'
  if (!body.includes('openFileId')) return '没有透传 openFileId'
  return null
})

checkFull('概览页 → 项目列表页按页面栈分流', () => {
  const src = readVueOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  const i = src.indexOf('goProjectList()')
  if (i < 0) return '缺 goProjectList()'
  const body = src.slice(i, i + 600)
  if (!body.includes('getCurrentPages')) return '没有判页面栈，无脑 navigateTo/redirectTo 会堆出多个列表页实例'
  if (!body.includes('navigateBack') || !body.includes('redirectTo')) {
    return '必须两条分支：栈里上一页是列表页就 navigateBack，否则 redirectTo'
  }
  return null
})

checkFull('概览轮询纪律', () => {
  const src = readVueOrNull('src/components/project-home/ProjectHomePane.vue')
  if (src === null) return NOT_YET
  // 禁字断言只看实际代码：概览页的注释里要写明「绝不调 /version/status」的理由，
  // 那段说明性文字不该把断言判红。
  if (src.includes('getVersionStatus') || src.includes('/version/status')) {
    return '不许调 /version/status：它在 enabled 时会跑两次 git add，并与工作台争 per-project 锁'
  }
  if (src.includes('setInterval')) return 'A 期只在 onLoad 与 onShow 各刷一次，不起轮询'
  return null
})

checkFull('五个子组件的根节点类名是 e2e 锚点', () => {
  const map = {
    'src/components/project-home/ProfileHeader.vue': 'profile-header',
    'src/components/project-home/OverviewStatsBar.vue': 'overview-stats-bar',
    'src/components/project-home/ActivityFeed.vue': 'activity-feed',
    'src/components/project-home/TaskSchedule.vue': 'task-schedule',
    'src/components/project-home/ConversationList.vue': 'conversation-list',
  }
  const bad = []
  for (const [file, cls] of Object.entries(map)) {
    const src = readVueOrNull(file)
    if (src === null) bad.push(file + '(未落地)')
    else if (!src.includes(`class="${cls}`)) bad.push(file + ' 缺 .' + cls)
  }
  return bad.length ? bad.join(', ') : null
})

checkFull('CLAUDE.md 写下了三个同名不同物的术语', () => {
  const md = readRepo('CLAUDE.md')
  const miss = [HOME_ROUTE, LIST_ROUTE, '工作台'].filter((s) => !md.includes(s))
  return miss.length ? '缺: ' + miss.join(', ') : null
})

checkFull('sidebar-shell.md 的页面路由一节收录了两个新页', () => {
  const md = readRepo('.claude/agents/sidebar-shell.md')
  const miss = ['project-list', 'project-home'].filter((s) => !md.includes(s))
  return miss.length ? '缺: ' + miss.join(', ') : null
})

checkFull('app-e2e 走三级跳而不是把个人中心当必经之路', () => {
  const src = readFrontend('tests/app-e2e/run.mjs')
  if (!src.includes(LIST_ROUTE)) return 'J3 没有从项目列表页出发'
  if (!src.includes(HOME_ROUTE)) return 'J3 没有经过项目概览页'
  if (src.includes("mouseClickText('我的项目')")) return '个人中心已经没有「我的项目」tab 了'
  const i = src.indexOf('解锁成功')
  if (i < 0 || !src.slice(i, i + 500).includes(LIST_ROUTE)) {
    return '解锁后的落点断言还没放行项目列表页'
  }
  return null
})

// 工作台里渲染的组件自己跳页，同样是「离开工作台」（2026-09-10，走查抓到 CollabDialog
// 「去团队设置」直接 reLaunch）：上面几条只看 project-overview.vue 自己的方法，组件里的
// 跳转从来没人管，防抖窗口里没落盘的文档改动就这么随组件树一起销毁。
// 规则：project-overview 把 leaveWorkbench provide 出去；它直接 import 的组件里，
// 凡是出现 uni.reLaunch / navigateTo / redirectTo 的方法，要么同一个方法里优先走注入的
// this.leaveWorkbench(...)（直调只作为「宿主不是工作台」时的回落），要么进下面的名单并写明理由。
// 目的地是设置页的，同一个方法里优先走注入的 this.openSettingsTab(...)（工作台里设置是标签，
// 根本不该离开，dev-board#582）也算合规。
const WORKBENCH_NAV_ALLOWLIST = {
  // 以下两处早于本条护栏，行为（navigateTo 保留工作台在栈里 / 切身份整站重走启动链）
  // 各有产品含义，改动要单独评估，先记名在此不许再新增。
  'components/ChatInterface.vue#goToSkillManagement': '预存在：技能下拉跳插件广场，待单独评估',
  'components/admin/AdminPane.vue#onSwitchIdentity': '预存在：切换本机身份后整站回启动链重建',
}

check('工作台 provide 出 leaveWorkbench，给里面的组件用', () => {
  const src = readVue('src/pages/project-overview/project-overview.vue')
  const provide = extractMethodBody(src, 'provide()')
  if (!provide) return 'project-overview 没有 provide()'
  if (!provide.includes('leaveWorkbench')) return 'provide() 里没有 leaveWorkbench'
  if (!provide.includes('openSettingsTab')) return 'provide() 里没有 openSettingsTab'
  return null
})

check('工作台里「去团队设置 / 去账户」开设置标签，不跳出独立设置页（dev-board#582）', () => {
  const sites = [
    ['src/components/InviteMemberDialog.vue', 'goTeamSettings()'],
    ['src/components/collab/CollabDialog.vue', 'goTeamSettings()'],
    ['src/components/MarketSidebarPanel.vue', 'goToAccountSettings()'],
    ['src/components/MarketDetailPane.vue', 'goToAccountSettings()'],
  ]
  const bad = []
  for (const [rel, marker] of sites) {
    const src = readVue(rel)
    const body = extractMethodBody(src, marker)
    if (!body || !body.includes('this.openSettingsTab(') || !/openSettingsTab:\s*\{\s*default:\s*null/.test(src)) {
      bad.push(rel + '#' + marker)
    }
  }
  return bad.length ? '没走注入的 openSettingsTab: ' + bad.join(', ') : null
})

check('工作台里渲染的组件跳出工作台必须走 leaveWorkbench（先落盘）', () => {
  const host = readVue('src/pages/project-overview/project-overview.vue')
  const imported = [...host.matchAll(/from '@\/(components\/[^']+\.vue)'/g)].map((m) => m[1])
  const NAV = /uni\.(reLaunch|navigateTo|redirectTo)\(/g
  // 方法头：选项式 `  name(...) {` / `  async name(...) {`，组合式 `const name = (...) => {`
  const HEADER = /^\s*(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{|^\s*const\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?\([^)]*\)\s*=>\s*\{/
  const bad = []
  for (const rel of [...new Set(imported)]) {
    const src = readVueOrNull('src/' + rel)
    if (!src) continue
    const lines = src.split('\n')
    let m
    while ((m = NAV.exec(src))) {
      const lineNo = src.slice(0, m.index).split('\n').length - 1
      let name = null
      for (let i = lineNo; i >= 0 && !name; i--) {
        const h = lines[i].match(HEADER)
        if (h && !/^\s*(if|for|while|switch|catch|function)\b/.test(lines[i])) name = h[1] || h[2]
      }
      const key = rel + '#' + name
      if (WORKBENCH_NAV_ALLOWLIST[key]) continue
      // 先按方法定义切（模板里 `@tap="name(x)"` 这类调用点会让「第一次出现」配错大括号），
      // 找不到定义再退回老办法
      const body = name ? (extractDefinition(src, name) || extractMethodBody(src, name + '(')) : null
      if (body && (body.includes('this.leaveWorkbench(') || body.includes('this.openSettingsTab('))) continue
      bad.push(key)
    }
  }
  return bad.length ? '直接跳页、没走注入的 leaveWorkbench: ' + [...new Set(bad)].join(', ') : null
})

// ==================== 无项目态外壳与欢迎标签（dev-board#1047） ====================

check('rail 上有「项目」面板，左栏渲染 ProjectListPane', () => {
  const rail = readFrontend('src/config/leftSidebarPlugins.js')
  if (!/key:\s*'projects'/.test(rail)) return "LEFT_SIDEBAR_PLUGINS 里没有 key: 'projects'"
  const src = readVue('src/pages/project-overview/project-overview.vue')
  if (!src.includes('<ProjectListPane') || !src.includes("leftPaneKey === 'projects'")) {
    return "左栏没有 leftPaneKey === 'projects' 渲染 ProjectListPane 的分支"
  }
  return null
})

check('无项目态：项目面板不挂载、右栏 AI 不渲染、暂存区不建，存储键不写 project_null_*', () => {
  const src = readVue('src/pages/project-overview/project-overview.vue')
  if (!extractDefinition(src, 'hasProject')) return '缺 hasProject 计算属性'
  const sw = readFrontend('src/pages/project-overview/panelSwitching.js')
  if (!/if \(!this\.hasProject && !isPaneAllowedWithoutProject\(key\)\) return/.test(sw)) {
    return 'toggleLeftPane 没有在无项目态拦下项目面板的 key'
  }
  if (!src.includes('v-if="aiPanelMounted && hasProject"')) return '右栏 AI 面板在无项目态仍会渲染'
  const tog = extractDefinition(src, 'toggleAiPanel')
  if (!tog || !tog.includes('if (!this.hasProject) return')) return 'toggleAiPanel 在无项目态没有直接返回'
  const onLoad = extractMethodBody(src, 'onLoad(query)')
  const stagingAt = onLoad.indexOf('this.ensureStagingFolder()')
  if (stagingAt < 0 || onLoad.lastIndexOf('if (query && query.id)', stagingAt) < 0) {
    return 'ensureStagingFolder 必须留在 query.id 分支里（无项目态不建暂存区）'
  }
  for (const f of ['src/pages/project-overview/project-overview.vue', 'src/pages/project-overview/panelSwitching.js']) {
    if (/`project_\$\{this\.projectId\}_(leftPaneKey|activeTabsByMode)`/.test(readFrontend(f))) {
      return f + ' 还在直接拼 project_${this.projectId}_*（无项目态会写出 project_null_*），改走 workbenchStorageKey'
    }
  }
  return null
})

check('欢迎标签：单例 tabType welcome，左右两条渲染链都有，菜单「帮助 → 欢迎」能开', () => {
  const src = readVue('src/pages/project-overview/project-overview.vue')
  const n = countOf(src, "tabType === 'welcome'")
  if (n !== 2) return `左右两条渲染链应各有一条 welcome 分支，实际 ${n} 处`
  const tab = readFrontend('src/pages/project-overview/welcomeTab.js')
  if (!tab.includes("export const WELCOME_TAB_ID = 'welcome'")) return '欢迎标签的单例 id 不是 welcome'
  const kind = readFrontend('src/pages/project-overview/fileKind.js')
  if (!/NON_FILE_TAB_TYPES = \[[^\]]*'welcome'/.test(kind)) return "NON_FILE_TAB_TYPES 里没有 'welcome'（会被当成文档、能拖进 AI 上下文）"
  const help = readFrontend('src/config/commands/help.js')
  if (!help.includes("run: 'wb:openWelcome'")) return '帮助菜单没有「欢迎」（wb:openWelcome）'
  const menu = readFrontend('src/pages/project-overview/menuCommands.js')
  if (!menu.includes("case 'openWelcome': this.openWelcomeTab()")) return 'wb:openWelcome 没有接到 openWelcomeTab'
  return null
})

// ==================== 日程标签与标签快照（dev-board#1048 / #1049） ====================

check('日程是工作台里的中栏标签：四处入口开 openCalendarTab，不再 leaveWorkbench 去日程页（dev-board#1048）', () => {
  const src = readVue('src/pages/project-overview/project-overview.vue')
  if (/leaveWorkbench\(\s*['"`]\/pages\/calendar\/calendar/.test(src)) return '工作台里还有 leaveWorkbench 去日程页的调用'
  // 1. 命令「日程」/ 账户下拉「我的日程」共用 goCalendar
  const goCal = extractDefinition(src, 'goCalendar')
  if (!goCal || !goCal.includes('this.openCalendarTab(') || /leaveWorkbench|pages\/calendar/.test(goCal)) {
    return 'goCalendar 应当调 openCalendarTab()，不离开工作台'
  }
  // 2. 左栏日程面板的「查看全盘日程」
  const pane = readVue('src/components/project-calendar/ProjectCalendarPane.vue')
  const open = extractDefinition(pane, 'openGlobalCalendar')
  if (!open || !open.includes("this.$emit('open-calendar')") || /pages\/calendar|leave-workbench/.test(open)) {
    return 'ProjectCalendarPane.openGlobalCalendar 应当 emit open-calendar'
  }
  if (!src.includes('@open-calendar="openCalendarTab()"')) return '工作台没有把日程面板的 open-calendar 接到 openCalendarTab'
  if (src.includes('@leave-workbench="leaveWorkbench"')) return '日程面板的 leave-workbench 绑定应已撤掉'
  // 3. 左栏「项目」面板的事项概览格 / 查看日程 / 下一件
  const list = readVue('src/components/project-list/ProjectListPane.vue')
  if (list.includes('/pages/calendar/calendar')) return 'ProjectListPane 还在往日程页跳'
  if (!/openCalendarTab:\s*\{\s*default:\s*null/.test(list)) return 'ProjectListPane 没有 inject openCalendarTab'
  for (const m of ['goToCalendar', 'goToScheduleGroup', 'goToNextDue']) {
    const body = extractDefinition(list, m)
    if (!body || !body.includes('this.openSchedule(')) return `ProjectListPane.${m} 没有走 openSchedule`
  }
  const sched = extractDefinition(list, 'openSchedule')
  if (!sched || !sched.includes('this.openCalendarTab(opts)') || /leaveWorkbench|uni\./.test(sched)) {
    return 'ProjectListPane.openSchedule 应当只调注入的 openCalendarTab'
  }
  // 4. provide 出 openCalendarTab（设置里「个人 → 事项」等子组件用）
  const provide = extractMethodBody(src, 'provide()')
  if (!provide || !provide.includes('openCalendarTab')) return 'provide() 里没有 openCalendarTab'
  const todos = readVue('src/components/userprofile/PersonalTodosPanel.vue')
  const oc = extractDefinition(todos, 'openCalendar')
  if (!oc || !oc.includes('this.openCalendarTab(')) return 'PersonalTodosPanel.openCalendar 在工作台里应开日程标签'
  return null
})

check('日程标签：单例 tabType calendar，左右两条渲染链 embedded + 全局视图，非文件标签', () => {
  const src = readVue('src/pages/project-overview/project-overview.vue')
  const n = countOf(src, "tabType === 'calendar'")
  if (n !== 2) return `左右两条渲染链应各有一条 calendar 分支，实际 ${n} 处`
  for (const side of ['Left', 'Right']) {
    const at = src.indexOf(`v-else-if="activeFile${side}.tabType === 'calendar'"`)
    const tag = src.slice(src.lastIndexOf('<CalendarPane', at), src.indexOf('/>', at))
    if (!tag.startsWith('<CalendarPane')) return `${side} 分支渲染的不是 CalendarPane`
    if (!/\bembedded\b/.test(tag)) return `${side} 分支没有 embedded`
    if (!tag.includes(':project-id="null"')) return `${side} 分支应传 :project-id="null"（全局视图，筛选里按项目收窄）`
    for (const ev of ['@open-project="onCalendarOpenProject"', '@open-file="onCalendarOpenFile"', '@close="closeCalendarTab"']) {
      if (!tag.includes(ev)) return `${side} 分支缺 ${ev}`
    }
  }
  const tab = readFrontend('src/pages/project-overview/calendarTab.js')
  if (!tab.includes("export const CALENDAR_TAB_ID = 'calendar'")) return '日程标签的单例 id 不是 calendar'
  const op = extractDefinition(tab, 'onCalendarOpenProject')
  if (!op || !op.includes('this.leaveWorkbench(')) return '日程标签跨项目「进入项目」要走 leaveWorkbench'
  const of = extractDefinition(tab, 'onCalendarOpenFile')
  if (!of || !of.includes('this.onTaskOpenFile(') || !of.includes('this.leaveWorkbench(')) {
    return '日程标签「打开文件」：同项目就地 onTaskOpenFile、跨项目 leaveWorkbench'
  }
  const kind = readFrontend('src/pages/project-overview/fileKind.js')
  if (!/NON_FILE_TAB_TYPES = \[[^\]]*'calendar'/.test(kind)) return "NON_FILE_TAB_TYPES 里没有 'calendar'"
  // 标签形态下 CalendarPane 的跳转只 emit、不自己 reLaunch（它是 defineAsyncComponent 引入的，
  // 上面「组件跳出工作台」那条按 import 语句扫不到它，这里单独钉住）
  const cp = readVue('src/components/calendar/CalendarPane.vue')
  for (const m of ['goToProject', 'onOpenFile', 'goBack']) {
    const body = extractDefinition(cp, m)
    const emitAt = body ? body.indexOf('if (this.embedded)') : -1
    const navAt = body ? body.search(/uni\.(reLaunch|navigateTo|redirectTo)\(/) : -1
    if (emitAt < 0 || (navAt >= 0 && navAt < emitAt)) return `CalendarPane.${m} 在 embedded 时必须先 emit 再 return`
  }
  if (!/v-if="!embedded" class="cal-back"/.test(cp)) return '标签形态下页头「返回」应隐藏'
  return null
})

check('日程直链薄壳与提醒落点：进来即 reLaunch 工作台开日程标签（dev-board#1048）', () => {
  const page = readVue('src/pages/calendar/calendar.vue')
  if (page.includes('<CalendarPane')) return '日程页应已退成薄壳，不再渲染 CalendarPane'
  if (!page.includes('uni.reLaunch({ url: calendarShellTarget(query) })')) return '日程薄壳没有 reLaunch 进工作台'
  if (!page.includes("params.push('tab=calendar')")) return '日程薄壳转进工作台没带 tab=calendar'
  const src = readVue('src/pages/project-overview/project-overview.vue')
  const onLoad = extractMethodBody(src, 'onLoad(query)')
  if (!onLoad || !/query\.tab === 'calendar'[\s\S]{0,120}this\.openCalendarTab\(/.test(onLoad)) {
    return '工作台 onLoad 没有消费 ?tab=calendar'
  }
  const rem = readFrontend('src/utils/taskReminders.js')
  const target = extractDefinition(rem, 'export function openReminderTarget') || extractMethodBody(rem, 'export function openReminderTarget(')
  if (!target || !target.includes('vm.openCalendarTab(')) return '提醒点击在工作台里时应开日程标签'
  if (!rem.includes('openReminderTarget(task.id)')) return '通知 onclick 没有走 openReminderTarget'
  return null
})

check('标签快照：两份键、恢复在 onLoad、leaveWorkbench 前同步写（dev-board#1049）', () => {
  const snap = readFrontend('src/pages/project-overview/tabSnapshot.js')
  if (!snap.includes("export const TAB_SNAPSHOT_SUFFIX = 'tabs'")) return '快照键后缀不是 tabs'
  if (!snap.includes('workbenchStorageKey(this.projectId, TAB_SNAPSHOT_SUFFIX)')) return '快照键没有走 workbenchStorageKey（global_tabs / project_${id}_tabs）'
  const src = readVue('src/pages/project-overview/project-overview.vue')
  const onLoad = extractMethodBody(src, 'onLoad(query)')
  if (!onLoad || !onLoad.includes('this.restoreTabSnapshot()')) return 'onLoad 没有恢复标签快照'
  const exit = extractMethodBody(src, 'async leaveWorkbench(url)')
  const flushAt = exit ? exit.indexOf('this.flushTabSnapshot()') : -1
  const navAt = exit ? exit.indexOf('uni.reLaunch') : -1
  if (flushAt < 0 || flushAt > navAt) return 'leaveWorkbench 在 reLaunch 之前没有同步写标签快照'
  if (!src.includes('tabSnapshotSignature()')) return '缺标签快照的变化信号（watch → 节流写）'
  return null
})

// ---- 追加位：后续任务把新的 check(...) 加在这一行之前 ----

if (failures.length) {
  console.error('导航契约检查未通过：')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}
console.log(
  '导航契约检查通过' +
    (FULL ? '（含全量层）' : `（跳过 ${skipped} 条全量层断言，用 npm run check:nav:full 跑全量）`)
)
