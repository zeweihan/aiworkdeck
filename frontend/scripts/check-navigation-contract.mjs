#!/usr/bin/env node
/**
 * 三级导航契约静态护栏（项目列表页 → 项目概览页 → 工作台）。
 *
 * 存在理由：这套导航散在 launch / login / newproject / project-overview / 两个新页面
 * 共十来处硬编码 URL 上，改错一处不会编译报错，只会在真人走到那一步时落到空白页
 * 或者多跳一次。规则写死在这里，CI 每次跑。
 *
 * 术语（同名不同物，别看串）：
 *   工作台       = pages/project-overview/project-overview（四列干活界面，不改名）
 *   项目概览页   = pages/project-home/project-home（一页纸卷轴）
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

// .vue 源码里做「禁字」断言前先剥注释：注释要能写清楚为什么不做某事
const stripVueComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

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
  const need = [
    '.page-project-list', '.project-list-container', '.main-content',
    '.content-header', '.header-actions', '.btn-primary-small', '.btn-secondary-small',
    '.cloud-accept-entry', '.projects-stats-row', '.stat-card',
    '.project-grid', '.project-item-card', '.card-deco-header', '.action-btn-icon',
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
  if (/background:\s*#(21262|1[0-9a-f]{5})\b/i.test(css)) return '外壳不做深色 chrome'
  return null
})

// ==================== 项目列表页脚本 ====================

check('项目列表页根节点带 e2e 锚点类名', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  return src.includes('class="page-project-list"') ? null : '根节点必须是 .page-project-list（e2e 锚点）'
})

check('项目列表页角色文案收敛到 config/memberRoles.js', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  if (!/from\s+'@\/config\/memberRoles\.js'/.test(src)) return "没有从 '@/config/memberRoles.js' 引入"
  if (/'PARTICIPANT'\s*:/.test(src)) return '页面里还残留自己硬编码的角色映射表'
  return null
})

check('项目列表页点卡片进项目概览页（navigateTo）', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  if (!src.includes('/pages/project-home/project-home?id=')) return 'goToProject 没有指向项目概览页'
  if (src.includes('/pages/project-overview/project-overview')) {
    return '不许从列表页直连工作台，必须先经概览页'
  }
  const i = src.indexOf('goToProject(projectId)')
  if (i < 0) return '找不到 goToProject(projectId)'
  if (!src.slice(i, i + 300).includes('navigateTo')) {
    return '列表页→概览页两端都不是工作台，必须用 navigateTo 不是 reLaunch'
  }
  return null
})

check('项目列表页删掉了写死 0 的两张统计卡', () => {
  // 禁字断言只看实际代码：注释里要写清楚「原先的进行中/已完成是写死的 0」，
  // 那段说明性文字不该把断言判红。
  const src = stripVueComments(readFrontend('src/pages/project-list/project-list.vue'))
  if (src.includes('进行中') || src.includes('已完成')) {
    return 'Project 实体没有状态字段，这两张卡的数字是写死的字面量 0，不许搬过来'
  }
  const cards = (src.match(/class="stat-card"/g) || []).length
  return cards === 1 ? null : '统计条应当只剩「全部项目」一张卡，实际 ' + cards + ' 张'
})

check('项目列表页带全 CloudAcceptDialog 的两个入口', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  if (!src.includes('<CloudAcceptDialog')) return '弹窗组件没搬过来'
  // 1 处 method 定义 + 2 处入口绑定（有项目态顶部按钮 / 空项目态入口）
  const entries = (src.match(/openCloudAccept/g) || []).length
  return entries >= 3 ? null : 'openCloudAccept 只出现 ' + entries + ' 次，两个入口缺一个'
})

check('项目列表页对 CLIENT 收起写操作入口', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  if (!/isClientUser\s*\(\)/.test(src)) return '缺 isClientUser computed'
  if (!src.includes('!isClientUser && projects.length > 0')) return '顶部两个动作按钮没有对 CLIENT 隐藏'
  if (!src.includes('canManageMembers')) return '成员增删没有对 CLIENT 收起'
  return null
})

check('项目列表页别把裸数组当信封解', () => {
  const src = readFrontend('src/pages/project-list/project-list.vue')
  if (/getMyProjects\(\)[\s\S]{0,80}\.data/.test(src)) {
    return 'getMyProjects 返回裸数组（ProjectController.java:193-200），取 .data 会恒空'
  }
  return null
})

// ==================== 个人中心瘦身 ====================

check('个人中心默认 tab 不再是被搬走的 projects', () => {
  const src = readFrontend('src/pages/userprofile/userprofile.vue')
  if (!/activeTab:\s*'work_log'/.test(src)) return "activeTab 默认值必须是 'work_log'"
  if (/key:\s*'projects'/.test(src)) return 'tabs 数组里还留着 projects 项'
  return null
})

check('个人中心默认 tab 有人给它加载数据', () => {
  const src = readFrontend('src/pages/userprofile/userprofile.vue')
  // 工作记录是懒加载的（只在 switchTab 里触发），默认落它就必须在 onLoad 里补一次
  const onLoad = src.slice(src.indexOf('onLoad()'), src.indexOf('methods:'))
  return onLoad.includes('this.loadActivityLogs()')
    ? null
    : 'onLoad 里没有 loadActivityLogs()，默认 tab 会永远空白'
})

check('个人中心已清空项目相关的模板与方法', () => {
  const src = readFrontend('src/pages/userprofile/userprofile.vue')
  const left = [
    'project-item-card', 'panel-projects', 'projects-stats-row',
    'loadProjects', 'goToProject', 'handleDeleteProject', 'confirmRename',
    'CloudAcceptDialog', 'InviteMemberDialog', 'getRoleLabel',
  ].filter((s) => src.includes(s))
  return left.length ? '还残留: ' + left.join(', ') : null
})

check('个人中心已摘掉被搬迁搞成孤儿的导入', () => {
  const src = readFrontend('src/pages/userprofile/userprofile.vue')
  const orphan = ['getMyProjects', 'deleteProject', 'renameProject', 'getProjectMembers', 'removeProjectMember', 'getProjectTypeLabel']
    .filter((s) => src.includes(s))
  return orphan.length ? '孤儿导入: ' + orphan.join(', ') : null
})

check('个人中心保住了不该删的东西', () => {
  const src = readFrontend('src/pages/userprofile/userprofile.vue')
  const gone = ['formatTime(', 'formatDateTime(', '.role-text', '.empty-icon', 'loadActivityLogs', 'loadFavorites', 'addProjectMember', 'inviteClient']
    .filter((s) => !src.includes(s))
  return gone.length ? '误删: ' + gone.join(', ') : null
})

// ==================== 导航入口与出口 ====================

const USERPROFILE_ROUTE = '/pages/userprofile/userprofile'
const countOf = (s, sub) => s.split(sub).length - 1

check('launch 无最近项目兜底落项目列表页', () => {
  const src = readFrontend('src/pages/launch/launch.vue')
  if (src.includes(USERPROFILE_ROUTE)) return '还指着个人中心'
  if (!src.includes("reLaunch({ url: '/pages/project-list/project-list' })")) return '没有指向项目列表页'
  if (!src.includes('/pages/project-overview/project-overview?id=')) return '启动直达工作台那条被改坏了'
  return null
})

check('login 四处落点全改项目列表页', () => {
  const src = readFrontend('src/pages/login/login.vue')
  if (src.includes(USERPROFILE_ROUTE)) return '还有指着个人中心的落点'
  const n = countOf(src, '/pages/project-list/project-list')
  if (n !== 4) return '应当恰好四处（CLIENT 分支 / 无最近项目兜底 / 登录成功 / 注册成功），实际 ' + n
  if (!src.includes('/pages/project-overview/project-overview?id=')) return '会话恢复直达工作台那条被改坏了'
  return null
})

check('newproject 返回项目列表页且仍用 navigateTo', () => {
  const src = readFrontend('src/pages/newproject/index.vue')
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
  const src = readFrontend('src/pages/newproject/index.vue')
  let idx = -1
  while ((idx = src.indexOf('goToProjectList', idx + 1)) !== -1) {
    if (src.slice(idx, idx + 200).includes('个人中心')) {
      return '@tap 指向 goToProjectList，附近文案却还写着「个人中心」，与实际跳转目标不符'
    }
  }
  return null
})

check('工作台「全部项目」用 reLaunch 去项目列表页', () => {
  const src = readFrontend('src/pages/project-overview/project-overview.vue')
  const i = src.indexOf('goAllProjects()')
  if (i < 0) return '找不到 goAllProjects'
  const body = src.slice(i, i + 400)
  if (!body.includes('/pages/project-list/project-list')) return 'goAllProjects 没有指向项目列表页'
  if (!body.includes('reLaunch')) return '工作台参与的跳转一律 reLaunch，不能用 navigateTo'
  return null
})

check('工作台 rail 头像仍 navigateTo 个人中心（不许顺手改）', () => {
  const src = readFrontend('src/pages/project-overview/project-overview.vue')
  const i = src.indexOf('goToUserProfile()')
  if (i < 0) return '找不到 goToUserProfile'
  const body = src.slice(i, i + 200)
  if (!body.includes(USERPROFILE_ROUTE) || !body.includes('navigateTo')) {
    return '它依赖页面栈保留实例以便 onShow 回流刷新，本次不改'
  }
  return null
})

check('五条直达工作台的出口一条都没动', () => {
  const bad = []
  if (!readFrontend('src/App.vue').includes('/pages/project-overview/project-overview?id=')) bad.push('App.vue 应用菜单「最近打开」')
  if (!readFrontend('src/utils/ideOpen.js').includes('/pages/project-overview/project-overview?')) bad.push('ideOpen.js 打开本地文件夹/文件')
  const ov = readFrontend('src/pages/project-overview/project-overview.vue')
  const i = ov.indexOf('switchToProject(p) {') // 方法定义；'switchToProject(p)' 会先命中模板里的 @tap 调用
  if (i < 0 || !ov.slice(i, i + 400).includes('/pages/project-overview/project-overview?id=')) bad.push('顶栏切换器 switchToProject')
  return bad.length ? '被改坏: ' + bad.join(', ') : null
})

check('admin 切换本机工作区仍清最近项目', () => {
  const src = readFrontend('src/pages/admin/admin.vue')
  return src.includes("removeStorageSync('checkba_last_project_id')")
    ? null
    : '删了这行会让切身份之后仍直达上一个身份的项目'
})

// ==================== 工作台通往概览页的入口 ====================

check('工作台切换器有通往项目概览页的入口', () => {
  const src = readFrontend('src/pages/project-overview/project-overview.vue')
  if (!src.includes('switcher-home')) return '模板里缺 .switcher-home 一项'
  const i = src.indexOf('goProjectHome()', src.indexOf('methods:'))
  if (i < 0) return 'goProjectHome 不在 methods 里'
  const body = src.slice(i, i + 320)
  if (!body.includes('/pages/project-home/project-home?id=')) return 'goProjectHome 没有指向项目概览页'
  if (!body.includes('reLaunch')) return '工作台参与的跳转一律 reLaunch'
  // 「项目概览」必须排在「全部项目…」之前（两者的首次出现都在模板里）
  if (src.indexOf('switcher-home') > src.indexOf('switcher-all')) {
    return '「项目概览」应当排在「全部项目…」之前'
  }
  return null
})

check('switcher-home 有对应样式', () => {
  const css = readFrontend('src/pages/project-overview/project-overview.scss')
  return css.includes('.switcher-home') ? null : 'project-overview.scss 里没有 .switcher-home'
})

check('工作台消费概览页带来的 conversationId', () => {
  const src = readFrontend('src/pages/project-overview/project-overview.vue')
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
  const src = readFrontend('src/components/collab/CollabDialog.vue')
  if (!src.includes('打开项目列表')) return '话术被改坏了'
  const list = readFrontend('src/pages/project-list/project-list.vue')
  return list.includes('从团队案件库取一份案卷') ? null : '话术指的入口在项目列表页上不存在'
})

// ==================== 全量层：别的批次的产出 ====================

const HOME_VUE = 'src/pages/project-home/project-home.vue'

checkFull('概览页容器带全三个 e2e 锚点类名', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  const miss = ['page-project-home', 'btn-project-list', 'btn-workbench'].filter((c) => !src.includes(c))
  return miss.length ? '缺 e2e 锚点: ' + miss.join(', ') : null
})

checkFull('概览页挂了五个内容区块', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  const miss = ['<ProfileHeader', '<OverviewStatsBar', '<ActivityFeed', '<TaskSchedule', '<ConversationList']
    .filter((t) => !src.includes(t))
  return miss.length ? '缺子组件: ' + miss.join(', ') : null
})

checkFull('概览页登记最近项目', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  if (!/from\s+'@\/utils\/recentProjects\.js'/.test(src)) return "没有从 '@/utils/recentProjects.js' 引入"
  if (!src.includes('recordProjectVisit(')) return '没有调 recordProjectVisit'
  return null
})

checkFull('概览页用自己的活跃实例指针', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  // 禁字断言只看实际代码：概览页的注释里要解释「为什么不复用 __checkbaActiveOverviewVm」，
  // 那段说明性文字不该把断言判红。「必须出现」那条仍然看整份源码。
  const code = stripVueComments(src)
  if (!src.includes('__checkbaProjectHomeVm')) return '缺活跃实例指针守卫'
  if (code.includes('__checkbaActiveOverviewVm')) {
    return '复用了工作台的指针，会让工作台的全局事件被概览页拦掉'
  }
  return null
})

checkFull('概览页 → 工作台用 reLaunch 并透传 openFileId', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  const i = src.indexOf('goWorkbench()')
  if (i < 0) return '缺 goWorkbench()'
  const body = src.slice(i, i + 500)
  if (!body.includes('reLaunch')) return '进入工作台必须用 reLaunch（工作台参与的跳转一律 reLaunch）'
  if (!body.includes('/pages/project-overview/project-overview')) return '目标不是工作台'
  if (!body.includes('openFileId')) return '没有透传 openFileId'
  return null
})

checkFull('概览页 → 项目列表页按页面栈分流', () => {
  const src = readFrontendOrNull(HOME_VUE)
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

checkFull('概览页轮询纪律', () => {
  const src = readFrontendOrNull(HOME_VUE)
  if (src === null) return NOT_YET
  // 禁字断言只看实际代码：概览页的注释里要写明「绝不调 /version/status」的理由，
  // 那段说明性文字不该把断言判红。
  const code = stripVueComments(src)
  if (code.includes('getVersionStatus') || code.includes('/version/status')) {
    return '不许调 /version/status：它在 enabled 时会跑两次 git add，并与工作台争 per-project 锁'
  }
  if (code.includes('setInterval')) return 'A 期只在 onLoad 与 onShow 各刷一次，不起轮询'
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
    const src = readFrontendOrNull(file)
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
