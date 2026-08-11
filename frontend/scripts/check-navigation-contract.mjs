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

const LIST_ROUTE = 'pages/project-list/project-list'
const WORKBENCH_ROUTE = 'pages/project-overview/project-overview'

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

// ---- 追加位：后续任务把新的 check(...) 加在这一行之前 ----

if (failures.length) {
  console.error('导航契约检查未通过：')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}
console.log('导航契约检查通过')
