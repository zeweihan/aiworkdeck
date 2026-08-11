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

// ---- 追加位：后续任务把新的 check(...) 加在这一行之前 ----

if (failures.length) {
  console.error('导航契约检查未通过：')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}
console.log('导航契约检查通过')
