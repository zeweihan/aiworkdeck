#!/usr/bin/env node
/**
 * 导航契约静态护栏（项目列表页 → 工作台；概览是工作台里的一个标签）。
 *
 * 存在理由：这套导航散在 launch / login / newproject / project-overview / 两个新页面
 * 共十来处硬编码 URL 上，改错一处不会编译报错，只会在真人走到那一步时落到空白页
 * 或者多跳一次。规则写死在这里，CI 每次跑。
 *
 * 2026-08 改动（三级 → 两级）：概览不再是列表与工作台之间的一站独立页，而是
 * 工作台中栏的一个标签（rail 第一个按钮，内容本体 components/project-home/
 * ProjectHomePane.vue 两个宿主共用）。列表点卡片直接 reLaunch 进工作台；
 * 启动一律落项目列表页，不再「有最近项目就直达工作台」。
 * pages/project-home 薄壳保留给直链与深链。
 *
 * 术语（同名不同物，别看串）：
 *   工作台       = pages/project-overview/project-overview（四列干活界面，不改名）
 *   项目概览     = 一页纸卷轴 ProjectHomePane，宿主是工作台标签 / project-home 薄壳页
 *   项目列表页   = pages/project-list/project-list（原个人中心的「我的项目」tab）
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

// 深色 chrome 判定按感知亮度算，不按固定十六进制前缀比对——旧写法要么漏判
// #212629（"21262" 后紧跟同为十六进制字符的 "9"，\b 不成立，正则整条不匹配），
// 要么误伤 #1A5336 森林绿（"1" + 5 位十六进制字符照样能拼出 "1A5336"）。
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

check('项目列表页的两个文件都存在', () => {
  const missing = [
    'src/pages/project-list/project-list.vue',
    'src/pages/project-list/project-list.scss',
  ].filter((f) => !hasFile(f))
  return missing.length ? '缺文件: ' + missing.join(', ') : null
})

// ==================== 项目列表页样式 ====================

// 说明：SCSS 里选择器后面必然跟空格、换行或逗号，用这三种收尾判存在，避免
// 「.stat-card」被「.stat-card-x」这类前缀关系误判成已存在。
const hasSelector = (css, sel) =>
  css.includes(sel + ' ') || css.includes(sel + '\n') || css.includes(sel + ',')

check('project-list.scss 搬齐了必需的样式块', () => {
  const css = readFrontend('src/pages/project-list/project-list.scss')
  // .btn-primary-small 已被 .awd-btn/.awd-btn-primary 取代（命名弹窗按钮改走 awd-* 视觉语言）；
  // .card-deco-header 已随卡片重设计删除（不再用顶部 4px 色条区分项目类型，见「Card 重设计」提交）——
  // 两处都是有意的设计变更，不是搬迁遗漏，从必需清单里去掉。
  const need = [
    '.page-project-list', '.project-list-container', '.main-content',
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

check('project-list.scss 补齐了原页面无定义的三个 class', () => {
  const css = readFrontend('src/pages/project-list/project-list.scss')
  const miss = ['.panel-projects', '.loading-state', '.loading-text'].filter((s) => !css.includes(s))
  return miss.length ? '未补: ' + miss.join(', ') : null
})

check('project-list.scss 不许把两块死样式搬过来', () => {
  const css = readFrontend('src/pages/project-list/project-list.scss')
  const dead = ['.modal-mask', '.project-members', '.member-list'].filter((s) => css.includes(s))
  return dead.length ? '搬进了模板里已无命中的死样式: ' + dead.join(', ') : null
})

check('project-list.scss 守浅色外壳红线', () => {
  const css = readFrontend('src/pages/project-list/project-list.scss')
  if (!css.includes('#1A5336')) return '缺森林绿 #1A5336'
  if (!css.includes('#F8F9FA')) return '缺浅底 #F8F9FA'
  const dark = findDarkChromeBackground(css)
  if (dark) return '外壳不做深色 chrome（' + dark + '）'
  return null
})

// ==================== 项目列表页脚本 ====================

check('项目列表页根节点带 e2e 锚点类名', () => {
  const src = readVue('src/pages/project-list/project-list.vue')
  return src.includes('class="page-project-list"') ? null : '根节点必须是 .page-project-list（e2e 锚点）'
})

check('项目列表页角色文案收敛到 config/memberRoles.js', () => {
  const src = readVue('src/pages/project-list/project-list.vue')
  if (!/from\s+'@\/config\/memberRoles\.js'/.test(src)) return "没有从 '@/config/memberRoles.js' 引入"
  if (/'PARTICIPANT'\s*:/.test(src)) return '页面里还残留自己硬编码的角色映射表'
  return null
})

check('项目列表页点卡片直达工作台（reLaunch）', () => {
  const src = readVue('src/pages/project-list/project-list.vue')
  const body = extractMethodBody(src, 'goToProject(projectId)')
  if (!body) return '找不到 goToProject(projectId)'
  if (!body.includes('/pages/project-overview/project-overview?id=')) {
    return 'goToProject 没有指向工作台（概览已收进工作台标签，中间那一跳已取消）'
  }
  if (!body.includes('uni.reLaunch')) {
    return '工作台参与的跳转一律 reLaunch：navigateTo 会把列表页留在栈里，再进另一个项目就有两个存活的工作台实例'
  }
  if (src.includes('/pages/project-home/project-home')) {
    return '列表页不该再指向概览独立页（那一跳已取消）'
  }
  return null
})

check('项目列表页自带两个新建入口，且没有「打开单个文件」', () => {
  const src = readVue('src/pages/project-list/project-list.vue')
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
  const src = readVue('src/pages/project-list/project-list.vue')
  if (src.includes('进行中') || src.includes('已完成')) {
    return 'Project 实体没有状态字段，这两张卡的数字是写死的字面量 0，不许搬过来'
  }
  const cards = (src.match(/class="stat-card"/g) || []).length
  return cards === 1 ? null : '统计条应当只剩「全部项目」一张卡，实际 ' + cards + ' 张'
})

check('项目列表页「从团队案件库取一份案卷」暂时收起（用户反馈 5），但方法与组件没删', () => {
  const src = readVue('src/pages/project-list/project-list.vue')
  if (!src.includes('<CloudAcceptDialog')) return '弹窗组件没搬过来'
  if (!/const\s+SHOW_CLOUD_ACCEPT\s*=\s*false/.test(src)) {
    return '两个入口应当用 SHOW_CLOUD_ACCEPT 门控收起，不是把整段删掉——日后要开回只改这一个常量'
  }
  // 1 处 method 定义 + 2 处入口绑定（有项目态顶部按钮 / 空项目态入口）；门控只加在
  // 各自的 v-if 上，openCloudAccept 这个方法名出现的次数不会因此减少
  const entries = (src.match(/openCloudAccept/g) || []).length
  return entries >= 3 ? null : 'openCloudAccept 只出现 ' + entries + ' 次，方法定义或两个入口绑定被删掉了'
})

check('项目列表页对 CLIENT 收起写操作入口', () => {
  const src = readVue('src/pages/project-list/project-list.vue')
  if (!/isClientUser\s*\(\)/.test(src)) return '缺 isClientUser computed'
  if (!src.includes('v-if="!isClientUser" class="create-section"')) {
    return '页头下方的新建操作行没有对 CLIENT 隐藏'
  }
  if (!src.includes('canManageMembers')) return '成员增删没有对 CLIENT 收起'
  return null
})

check('项目列表页别把裸数组当信封解', () => {
  const src = readVue('src/pages/project-list/project-list.vue')
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

check('launch 一律落项目列表页', () => {
  const src = readVue('src/pages/launch/launch.vue')
  if (src.includes(USERPROFILE_ROUTE)) return '还指着个人中心'
  if (!src.includes("reLaunch({ url: '/pages/project-list/project-list' })")) return '没有指向项目列表页'
  if (src.includes('/pages/project-overview/project-overview')) {
    return '启动不再直达工作台（2026-08 维护者定的落点）：开机先看见自己有哪些案卷'
  }
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

check('newproject 返回项目列表页且仍用 navigateTo', () => {
  const src = readVue('src/pages/newproject/index.vue')
  if (src.includes(USERPROFILE_ROUTE)) return '还指着个人中心'
  if (!src.includes("navigateTo({ url: '/pages/project-list/project-list' })")) {
    return '两端都不是工作台，应当 navigateTo 到项目列表页'
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

check('工作台「全部项目」用 reLaunch 去项目列表页，且离开前先落盘', () => {
  const src = readVue('src/pages/project-overview/project-overview.vue')
  const body = extractMethodBody(src, 'goAllProjects()')
  if (!body) return '找不到 goAllProjects'
  if (!body.includes('/pages/project-list/project-list')) return 'goAllProjects 没有指向项目列表页'
  if (body.includes('uni.navigateTo')) return '工作台参与的跳转一律 reLaunch，检测到误用 navigateTo'

  // 跳转本身可以直接 reLaunch，也可以走统一出口 leaveWorkbench()——后者在 reLaunch 之前
  // 先 flush 未落盘的编辑器内容（自动保存是防抖的，reLaunch 直接销毁组件树，
  // LibreOfficeEditor 的 beforeUnmount 已经来不及导出）。两种写法都算合规，
  // 但走 leaveWorkbench 时必须确认那个出口自己是 reLaunch + 落盘。
  if (!body.includes('uni.reLaunch')) {
    if (!body.includes('this.leaveWorkbench(')) {
      return '工作台参与的跳转一律 reLaunch，不能用 navigateTo'
    }
    const exit = extractMethodBody(src, 'async leaveWorkbench(url)')
    if (!exit) return 'goAllProjects 走了 leaveWorkbench，但找不到这个统一出口'
    if (!exit.includes('uni.reLaunch')) return 'leaveWorkbench 必须用 reLaunch（工作台参与的跳转一律 reLaunch）'
    if (exit.includes('uni.navigateTo')) return 'leaveWorkbench 里检测到误用 navigateTo'
    if (!exit.includes('flushDirtyEditors')) {
      return '离开工作台前必须先落盘：否则自动保存防抖窗口内的改动会被 reLaunch 静默丢掉'
    }
  }
  return null
})

check('顶栏头像下拉恰好两项：设置 + 退出登录（2026-08-27，dev-board#205）', () => {
  // 沿革：2026-08-20 个人中心并进设置后下拉只剩一项，2026-08-21（dev-board#96）撤下拉、
  // 点头像直开设置；2026-08-27（dev-board#205）「退出登录」要有一级入口，下拉恢复成
  // 两项——恢复的判据正是当年撤它的判据（不止一项了）。个人中心标签那套仍然是死代码。
  const src = readVue('src/pages/project-overview/project-overview.vue')
  for (const dead of ['openUserProfileTab', 'goToUserProfile', "workbench.profile", "'user-profile'"]) {
    if (src.includes(dead)) return '还残留个人中心标签那一套: ' + dead
  }
  const i = src.indexOf('class="avatar-btn"')
  if (i < 0) return '找不到 .avatar-btn'
  const btn = src.slice(i, i + 200)
  if (!btn.includes('avatarMenuOpen')) return '头像没有开下拉（avatarMenuOpen）'
  const menuIdx = src.indexOf('class="avatar-menu"')
  if (menuIdx < 0) return '找不到 .avatar-menu 下拉'
  // 2026-08-27（dev-board#225）：顶栏余额 chip 并进下拉，菜单顶部多了一块账户抬头。
  // 判据因此从「字符窗口里找得到两个动作」改成「动作项恰好两项」——抬头不是动作项，
  // 不占这两项的名额，但也不许再多出第三个动作把退出登录挤下去。
  const menu = src.slice(menuIdx, menuIdx + 1800)
  const actions = menu.match(/class="avatar-menu-item/g) || []
  if (actions.length !== 2) return `下拉动作项应恰好两项，实际 ${actions.length} 项`
  if (!menu.includes('onAvatarMenuSettings')) return '下拉里没有「设置」项（onAvatarMenuSettings）'
  if (!menu.includes('onAvatarMenuSignOut')) return '下拉里没有「退出登录」项（onAvatarMenuSignOut）'
  // 退出必须走唯一编排，不许在页面里自拼 disconnect/deactivate
  if (!src.includes("from '@/utils/signOut.js'")) return '退出登录没有走 utils/signOut.js 唯一编排'
  // 客户也要进得去：个人组的工作记录/账号安全对他一样成立，收系统组是面板自己的事
  const block = src.slice(src.indexOf('class="header-account"'), i + 600)
  if (block.includes('isClientView')) return '设置入口不该按 isClientView 藏起来（客户也有自己的个人组）'
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
  const vis = extractMethodBody(src, 'isTabVisible(file) {')
  if (!vis || !vis.includes("file.tabType === 'admin-settings'")) {
    return "isTabVisible 没放行 admin-settings 标签，点菜单会开一个被 v-show 藏死的标签"
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
