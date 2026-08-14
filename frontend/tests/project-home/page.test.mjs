import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../src')
const SRC = readFileSync(resolve(ROOT, 'pages/project-home/project-home.vue'), 'utf8')
const ZH = readFileSync(new URL('../../src/locales/zh-CN/projects.js', import.meta.url), 'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

// pages.json 不是纯 JSON（:2 行尾有 // 注释，注释里还带 https:// ），
// 用逐字符扫描剥注释，别用 /\/\/.*$/ ——那会把字符串里的 URL 也砍掉。
function stripJsonComments(text) {
  let out = ''
  let inStr = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (inStr) {
      out += ch
      if (ch === '\\') { out += text[++i]; continue }
      if (ch === '"') inStr = false
      continue
    }
    if (ch === '"') { inStr = true; out += ch; continue }
    if (ch === '/' && text[i + 1] === '/') { while (i < text.length && text[i] !== '\n') i++; out += '\n'; continue }
    if (ch === '/' && text[i + 1] === '*') { i += 2; while (i < text.length && !(text[i] === '*' && text[i + 1] === '/')) i++; i++; continue }
    out += ch
  }
  return out
}
const PAGES = JSON.parse(stripJsonComments(readFileSync(resolve(ROOT, 'pages.json'), 'utf8')))

test('pages.json 注册了两个新页且都显式写 navigationStyle: custom', () => {
  const home = PAGES.pages.find((x) => x.path === 'pages/project-home/project-home')
  assert.ok(home, 'pages.json 里没有 project-home（归 Task 16 组注册）')
  assert.equal(home.style.navigationStyle, 'custom', 'globalStyle 里没有这一项，漏写会得到系统导航栏')
  assert.equal(home.style.navigationBarTitleText, '项目概览页')
  const list = PAGES.pages.find((x) => x.path === 'pages/project-list/project-list')
  assert.ok(list, 'pages.json 里没有 project-list')
  assert.equal(list.style.navigationStyle, 'custom')
  // 工作台那一项一行都不许动（改名要动 9 处硬编码 URL + 11 个模块文件）
  assert.ok(PAGES.pages.some((x) => x.path === 'pages/project-overview/project-overview'))
})

test('App.vue 的路由埋点注释与 pages.json 的页面数一致', () => {
  const app = readFileSync(resolve(ROOT, 'App.vue'), 'utf8')
  const m = app.match(/pages\.json 里的 (\d+) 个页面/)
  assert.ok(m, 'App.vue 里找不到路由埋点注释')
  assert.equal(Number(m[1]), PAGES.pages.length, '加了新页就要同步这条注释')
})

test('e2e 锚点类名齐全', () => {
  for (const c of ['page-project-home', 'btn-project-list', 'btn-workbench', 'home-topbar-title'])
    assert.ok(SRC.includes(c), '缺 e2e 锚点: ' + c)
  // i18n 之后顶栏标题是 {{ $t('projects.overviewPageTitle') }}，不再是字面量。
  // e2e 仍按渲染后的中文找它，所以两头都要锁：组件引用这个 key、locale 里是「项目概览」。
  assert.ok(SRC.includes('overviewPageTitle'), '顶栏标题要走 projects.overviewPageTitle')
  assert.ok(ZH.includes("overviewPageTitle: '项目概览'"), 'e2e 用顶栏标题文案做 blur 触发点')
})

test('轮询纪律：不起定时器，不调 /version/status', () => {
  assert.ok(!CODE.includes('setInterval'), 'A 期不许起轮询')
  assert.ok(!CODE.includes('getVersionStatus'), '/version/status 会跑两次 git add')
  assert.ok(!CODE.includes('version/status'))
})

test('timeline 失败落 unavailable 引导态而不是 toast', () => {
  const i = SRC.indexOf('async loadActivity(')
  assert.ok(i > 0)
  const body = SRC.slice(i, SRC.indexOf('async loadTasks('))
  assert.match(body, /this\.activityUnavailable\s*=\s*true/)
  assert.ok(!body.includes('showToast'), 'timeline 失败不许弹 toast')
})

test('多实例守卫用自己的指针名', () => {
  assert.ok(SRC.includes('window.__checkbaProjectHomeVm'))
  assert.ok(!CODE.includes('__checkbaActiveOverviewVm'), '不许复用工作台的指针')
  assert.match(SRC, /window\.__checkbaProjectHomeVm === this/)
})

test('onLoad 记最近项目', () => {
  assert.match(SRC, /import\s*\{\s*recordProjectVisit\s*\}\s*from\s*'@\/utils\/recentProjects\.js'/)
  assert.match(SRC, /recordProjectVisit\(/)
})

test('getMyProjects 按裸数组解（不许照抄 admin.vue 的 res.data）', () => {
  const i = SRC.indexOf('async loadProjectCard(')
  const body = SRC.slice(i, SRC.indexOf('async loadProfile('))
  assert.match(body, /Array\.isArray\(res\)\s*\?\s*res\s*:\s*\[\]/)
})

test('信封端点一律再取一层 data', () => {
  assert.ok(SRC.includes('res.data.fields'))
  assert.ok(SRC.includes('res.data.versions'))
  assert.ok(SRC.includes('res.data.tasks'))
})

test('导航出口：工作台 reLaunch、列表按页面栈分流', () => {
  assert.match(SRC, /uni\.reLaunch\(\{\s*url\s*\}\)/)
  assert.ok(SRC.includes('conversationId=${encodeURIComponent(conversationId)}'))
  assert.ok(SRC.includes("prev.route === 'pages/project-list/project-list'"))
  assert.ok(SRC.includes('uni.navigateBack'))
  assert.ok(SRC.includes("uni.redirectTo({ url: '/pages/project-list/project-list' })"))
  assert.ok(!SRC.includes("uni.navigateTo({ url: '/pages/project-list"), '双向 navigateTo 会堆出多个列表实例')
})

test('翻页带回复合游标的第二维', () => {
  assert.ok(SRC.includes('nextBeforeId'), '复合游标第二维不能在前端丢掉')
})

test('五个区块按卷轴顺序排列', () => {
  const order = ['ProfileHeader', 'OverviewStatsBar', 'ActivityFeed', 'TaskSchedule', 'ConversationList']
  const tpl = SRC.slice(0, SRC.indexOf('</template>'))
  let at = -1
  for (const c of order) {
    const i = tpl.indexOf('<' + c)
    assert.ok(i > at, '卷轴顺序不对: ' + c)
    at = i
  }
})

test('概览页不内嵌 ChatInterface；样式外置；禁 emoji；浅色', () => {
  assert.ok(!CODE.includes('ChatInterface'))
  assert.match(SRC, /<style lang="scss" scoped src="\.\/project-home\.scss"><\/style>/)
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  const scss = readFileSync(resolve(ROOT, 'pages/project-home/project-home.scss'), 'utf8')
  assert.ok(!scss.includes('#212629'), '外壳保持浅色')
  assert.ok(scss.includes('#5BD197') && scss.includes('#1A5336'))
})

test('保存失败必须通过 ref 调 ProfileHeader.restoreEdit，否则输入静默丢失', () => {
  const i = SRC.indexOf('async onProfileSave(')
  assert.ok(i > 0, '找不到 onProfileSave')
  const body = SRC.slice(i, SRC.indexOf('goWorkbench('))
  const catchIdx = body.indexOf('catch')
  assert.ok(catchIdx > 0, 'onProfileSave 没有 catch 分支')
  const catchBody = body.slice(catchIdx)
  assert.match(catchBody, /restoreEdit\(/, 'catch 里必须调 restoreEdit，否则保存失败时用户刚敲的字会静默消失')
  assert.match(SRC, /<ProfileHeader[\s\S]*?ref="profileHeader"/, 'ProfileHeader 必须挂 ref 才能被父级调用 restoreEdit')
})

// 请求代守卫：isActiveInstance() 只挡跨实例（切到别的项目）的过期写入，挡不住同一实例内
// 两轮 loadAll() 之间的乱序——弱网下第一轮的慢请求可能在第二轮已经刷新完之后才 resolve，
// 用旧数据覆盖刚刷新的新数据。下面几条断言源码里确实有「记代号 → 写回前比对代号」这一层。

function methodBody(startMarker, endMarker) {
  const i = SRC.indexOf(startMarker)
  assert.ok(i > 0, '找不到方法: ' + startMarker)
  const end = SRC.indexOf(endMarker)
  assert.ok(end > i, '找不到方法边界: ' + startMarker + ' -> ' + endMarker)
  return SRC.slice(i, end)
}

test('loadAll 每轮自增请求代', () => {
  assert.match(SRC, /loadGeneration:\s*0/, "data() 里缺 loadGeneration 初值")
  const body = methodBody('loadAll()', 'async loadProjectCard(')
  assert.match(body, /this\.loadGeneration\+\+/, 'loadAll 必须先自增请求代，否则同实例内两轮取数无法区分新旧')
})

test('loadProjectCard / loadProfile 写回前比对请求代', () => {
  for (const [name, next] of [
    ['async loadProjectCard(', 'async loadProfile('],
    ['async loadProfile(', 'async loadStats('],
  ]) {
    const body = methodBody(name, next)
    assert.match(body, /const gen = this\.loadGeneration/, name + ' 没有在方法体开头记下请求代')
    assert.match(body, /gen !== this\.loadGeneration/, name + ' 写回前没有比对请求代')
  }
})

test('loadStats / loadActivity / loadTasks 的成功与失败两个分支都要比对请求代', () => {
  for (const [name, next] of [
    ['async loadStats(', 'async loadActivity('],
    ['async loadActivity(', 'async loadTasks('],
    ['async loadTasks(', 'async loadConversations('],
  ]) {
    const body = methodBody(name, next)
    const catchIdx = body.indexOf('catch')
    assert.ok(catchIdx > 0, name + ' 没有 catch 分支')
    const tryBody = body.slice(0, catchIdx)
    const catchBody = body.slice(catchIdx)
    assert.match(tryBody, /gen !== this\.loadGeneration/, name + ' 的成功分支写回前没有比对请求代')
    assert.match(
      catchBody,
      /gen !== this\.loadGeneration/,
      name + ' 的失败分支写回前没有比对请求代——过期代的错误响应会清掉后来那轮已经写好的新数据'
    )
  }
})

test('loadConversations：翻页不自增请求代，但仍要比对请求代丢弃过期响应', () => {
  const body = methodBody('async loadConversations(', 'onLoadMoreConversations(')
  assert.match(body, /const gen = this\.loadGeneration/, '没有在方法体开头记下请求代')
  const catchIdx = body.indexOf('catch')
  assert.ok(catchIdx > 0, '没有 catch 分支')
  const tryBody = body.slice(0, catchIdx)
  const catchBody = body.slice(catchIdx)
  assert.match(tryBody, /gen !== this\.loadGeneration/, '成功分支写回前没有比对请求代')
  assert.match(catchBody, /gen !== this\.loadGeneration/, '失败分支写回前没有比对请求代')
  // this.loadGeneration++ 只许在 loadAll 出现一次；翻页（reset=false）时自增会作废
  // 自己正常的追加结果——「代号变了就该丢」只对「别人刷新了整页」成立，不对「我自己在翻页」成立。
  const incrCount = (CODE.match(/this\.loadGeneration\+\+/g) || []).length
  assert.equal(incrCount, 1, 'this.loadGeneration++ 应当只在 loadAll 出现一次')
})
