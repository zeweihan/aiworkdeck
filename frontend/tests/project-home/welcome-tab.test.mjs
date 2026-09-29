// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 欢迎标签 + 工作台无项目态 + 左栏「项目」面板（dev-board#1047，spec 2026-09-29-defer-login-welcome-tab-design §4 §6）。
//
// 跑法：cd frontend && node --test tests/project-home/welcome-tab.test.mjs（test:project-home 一起跑）
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { buildRecentProjects, RECENT_MAX } from '../../src/components/welcome/welcomeRecent.js'
import {
  WELCOME_TAB_ID,
  WELCOME_TAB_TYPE,
  welcomeTabMethods,
  loadShowWelcomeOnStartup,
  saveShowWelcomeOnStartup,
  loadTelemetryNoticeDismissed,
  saveTelemetryNoticeDismissed,
} from '../../src/pages/project-overview/welcomeTab.js'
import {
  NO_PROJECT_PANE_KEYS,
  NO_PROJECT_DEFAULT_PANE,
  isPaneAllowedWithoutProject,
  workbenchStorageKey,
} from '../../src/pages/project-overview/noProjectShell.js'
import { isContextEligibleTab, pickActiveContextTab } from '../../src/pages/project-overview/activeTabContext.js'
import { NON_FILE_TAB_TYPES, fileKindKey } from '../../src/pages/project-overview/fileKind.js'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')

// ---------------- Recent 的取数规则 ----------------

const P = (id, name, lastActivityAt) => ({ id, name, lastActivityAt })

test('Recent：按「最近打开」顺序排，名称取实时清单', () => {
  const projects = [P(1, '甲', '2026-09-01T00:00:00'), P(2, '乙', '2026-09-02T00:00:00'), P(3, '丙改名后', '2026-09-03T00:00:00')]
  const out = buildRecentProjects(projects, [3, 1, 2])
  assert.deepEqual(out.map((p) => p.id), [3, 1, 2])
  assert.equal(out[0].name, '丙改名后', '名称来自项目清单（改名不陈旧）')
})

test('Recent：最近打开里已经不存在的项目（删了 / 换了本机身份）静默丢掉，重复 id 只算一次', () => {
  const projects = [P(1, '甲'), P(2, '乙')]
  const out = buildRecentProjects(projects, [9, 2, 2, 1])
  assert.deepEqual(out.map((p) => p.id), [2, 1])
})

test('Recent：最近打开不足时，其余项目按最近活动倒序补齐（新装机器不该是空的）', () => {
  const projects = [
    P(1, '旧', '2026-01-01T00:00:00'),
    P(2, '最新', '2026-09-20T00:00:00'),
    P(3, '次新', '2026-09-10T00:00:00'),
    P(4, '无时间', null),
  ]
  assert.deepEqual(buildRecentProjects(projects, []).map((p) => p.id), [2, 3, 1, 4])
  assert.deepEqual(buildRecentProjects(projects, [1]).map((p) => p.id), [1, 2, 3, 4])
})

test('Recent：最多 8 条', () => {
  assert.equal(RECENT_MAX, 8)
  const projects = Array.from({ length: 20 }, (_, i) => P(i + 1, 'P' + (i + 1), `2026-09-${String(i + 1).padStart(2, '0')}T00:00:00`))
  const out = buildRecentProjects(projects, [5, 6, 7])
  assert.equal(out.length, 8)
  assert.deepEqual(out.slice(0, 3).map((p) => p.id), [5, 6, 7])
  assert.deepEqual(out.slice(3).map((p) => p.id), [20, 19, 18, 17, 16], '其余按最近活动倒序补齐')
})

test('Recent 空态：没有项目时返回空数组（欢迎页显示一句提示，不报错）', () => {
  assert.deepEqual(buildRecentProjects([], [1, 2]), [])
  assert.deepEqual(buildRecentProjects(null, null), [])
  assert.deepEqual(buildRecentProjects(undefined, undefined), [])
})

test('欢迎页的 Recent 三态都有渲染：加载中 / 失败可重试 / 空态提示', () => {
  const src = read('components/welcome/WelcomePane.vue')
  assert.match(src, /recentState === 'loading'/)
  assert.match(src, /recentState === 'error'[^>]*@tap="loadRecent"/, '失败态要能点击重试')
  assert.match(src, /!recentProjects\.length[^>]*>\{\{ \$t\('welcome\.recentEmpty'\) \}\}/)
  assert.match(src, /buildRecentProjects\(list, getRecentProjectIds\(\)\)/)
})

// ---------------- 欢迎标签：单例、不是文档 ----------------

function fakeWorkbench(overrides = {}) {
  const vm = {
    leftFiles: [],
    rightFiles: [],
    activeFileIdLeft: null,
    activeFileIdRight: null,
    focusedPane: 'left',
    splitMode: false,
    resized: 0,
    $t: (k) => k,
    $nextTick: (fn) => fn && fn(),
    triggerWorkbenchResize() { this.resized++ },
    ...overrides,
  }
  vm.openWelcomeTab = welcomeTabMethods.openWelcomeTab.bind(vm)
  return vm
}

test('openWelcomeTab：未分屏落左侧并激活，id / tabType 恒为 welcome', () => {
  const vm = fakeWorkbench()
  vm.openWelcomeTab()
  assert.equal(vm.leftFiles.length, 1)
  assert.deepEqual(vm.leftFiles[0], { id: WELCOME_TAB_ID, tabType: WELCOME_TAB_TYPE, name: 'welcome.tabName' })
  assert.equal(vm.activeFileIdLeft, 'welcome')
  assert.equal(vm.focusedPane, 'left')
})

test('openWelcomeTab：单例——任一侧已开着只激活，不开第二个', () => {
  const vm = fakeWorkbench({ splitMode: true, focusedPane: 'left' })
  vm.rightFiles.push({ id: 'welcome', tabType: 'welcome', name: '欢迎' })
  vm.leftFiles.push({ id: 12, name: '合同.docx', fileType: 'docx' })
  vm.activeFileIdLeft = 12
  vm.openWelcomeTab()
  vm.openWelcomeTab()
  assert.equal(vm.leftFiles.length, 1, '左侧没有多出第二个欢迎标签')
  assert.equal(vm.rightFiles.length, 1)
  assert.equal(vm.activeFileIdRight, 'welcome')
  assert.equal(vm.focusedPane, 'right')
  assert.equal(vm.activeFileIdLeft, 12, '另一侧正在看的文档不被顶掉')
})

test('openWelcomeTab：分屏时新开在当前焦点窗格', () => {
  const vm = fakeWorkbench({ splitMode: true, focusedPane: 'right' })
  vm.openWelcomeTab()
  assert.equal(vm.rightFiles.length, 1)
  assert.equal(vm.leftFiles.length, 0)
  assert.equal(vm.activeFileIdRight, 'welcome')
})

test('欢迎标签不能当活跃文档、不能拖进 AI 上下文，也不按扩展名上色', () => {
  const welcome = { id: WELCOME_TAB_ID, tabType: WELCOME_TAB_TYPE, name: '欢迎' }
  assert.ok(NON_FILE_TAB_TYPES.includes('welcome'))
  assert.equal(isContextEligibleTab(welcome), false)
  // 就算将来有人给它塞了数字 id，tabType 那道闸也挡得住
  assert.equal(isContextEligibleTab({ ...welcome, id: 42 }), false)
  assert.equal(fileKindKey('md', 'welcome'), '')
  const doc = { id: '7', name: 'a.docx', fileType: 'docx' }
  assert.equal(pickActiveContextTab({ focusedPane: 'left', activeFileLeft: welcome, activeFileRight: doc }), doc,
    '聚焦在欢迎标签上时，AI 的「当前文档」落到另一侧真正的文档')
  assert.equal(pickActiveContextTab({ focusedPane: 'left', activeFileLeft: welcome, activeFileRight: null }), null)
})

// ---------------- 本机记忆 ----------------

function memStorage(init = {}) {
  const m = new Map(Object.entries(init))
  return {
    getStorageSync: (k) => (m.has(k) ? m.get(k) : ''),
    setStorageSync: (k, v) => m.set(k, v),
    raw: m,
  }
}

test('「启动时显示欢迎页」默认开；关掉后记住；存储坏了按默认开', () => {
  const st = memStorage()
  assert.equal(loadShowWelcomeOnStartup(st), true)
  saveShowWelcomeOnStartup(st, false)
  assert.equal(loadShowWelcomeOnStartup(st), false)
  saveShowWelcomeOnStartup(st, true)
  assert.equal(loadShowWelcomeOnStartup(st), true)
  assert.equal(loadShowWelcomeOnStartup({ getStorageSync() { throw new Error('boom') } }), true)
})

test('匿名统计提示：关掉之后不再出现（本机记忆）', () => {
  const st = memStorage()
  assert.equal(loadTelemetryNoticeDismissed(st), false)
  saveTelemetryNoticeDismissed(st)
  assert.equal(loadTelemetryNoticeDismissed(st), true)
})

// ---------------- 无项目态 ----------------

test('无项目态存储键落 global_*，不再写出 project_null_*', () => {
  assert.equal(workbenchStorageKey(null, 'leftPaneKey'), 'global_leftPaneKey')
  assert.equal(workbenchStorageKey(undefined, 'activeTabsByMode'), 'global_activeTabsByMode')
  assert.equal(workbenchStorageKey('null', 'leftPaneKey'), 'global_leftPaneKey')
  assert.equal(workbenchStorageKey('', 'leftPaneKey'), 'global_leftPaneKey')
  assert.equal(workbenchStorageKey(12, 'leftPaneKey'), 'project_12_leftPaneKey')
  assert.equal(workbenchStorageKey('12', 'leftPaneKey'), 'project_12_leftPaneKey')
})

test('无项目态 rail 只留全局面板：项目 / 日程 / 插件中心 / 剪贴板', () => {
  assert.deepEqual([...NO_PROJECT_PANE_KEYS].sort(), ['calendar', 'clipboard', 'market', 'projects'])
  assert.equal(NO_PROJECT_DEFAULT_PANE, 'projects')
  for (const k of ['files', 'search', 'version', 'voice', 'desensitize', 'litigation-visual', 'home', 'insight', 'favorites', 'dev', 'staging', 'plugin-foo']) {
    assert.equal(isPaneAllowedWithoutProject(k), false, k + ' 要项目，无项目态不许挂载')
  }
})

// ---------------- 左栏「项目」面板：抽取后的形态 ----------------

test('ProjectListPane 是面板不是页面：没有页面级生命周期，跳出工作台走注入的 leaveWorkbench', () => {
  const pane = read('components/project-list/ProjectListPane.vue')
  assert.ok(!/\n  onLoad\(|\n  onShow\(/.test(pane), '组件里写 onLoad/onShow 不会被调用（那是页面钩子）')
  assert.match(pane, /inject:\s*\{\s*leaveWorkbench:\s*\{\s*default:\s*null\s*\}/)
  assert.match(pane, /\n  mounted\(\) \{[\s\S]*?this\.loadProjects\(\)/, '挂载即拉项目清单')
  assert.match(pane, /class="project-list-pane"/)
  // 点的是当前打开的那个项目：什么都不做（重进一次等于把标签与编辑器全拆了）
  assert.match(pane, /goToProject\(projectId\) \{\s*if \(this\.currentProjectId != null && Number\(projectId\) === Number\(this\.currentProjectId\)\) return/)
})

test('项目列表页退成薄壳：路由保留，不再承载内容', () => {
  const shell = read('pages/project-list/project-list.vue')
  assert.ok(shell.split('\n').length < 60, '薄壳页应当只剩转发逻辑')
  assert.match(shell, /\?pane=projects/)
  const pages = read('pages.json')
  assert.match(pages, /"path": "pages\/project-list\/project-list"/)
})

// ---------------- 凭访问码进入案卷（桌面端连案件库服务器，依赖 dev-board#1050） ----------------

const CLIENT_FORM = read('components/account/ClientAccessCodeForm.vue')

function methodBody(src, header) {
  const i = src.indexOf(header)
  assert.ok(i >= 0, '找不到方法定义：' + header)
  let depth = 0
  for (let j = src.indexOf('{', i); j < src.length; j++) {
    if (src[j] === '{') depth++
    else if (src[j] === '}' && --depth === 0) return src.slice(src.indexOf('{', i), j + 1)
  }
  throw new Error('方法体不闭合：' + header)
}

function remoteVm(requestImpl, serverInput) {
  const emitted = []
  const vm = {
    serverInput,
    loading: false,
    errorText: '',
    noticeText: '',
    $t: (k, p) => (p ? k + JSON.stringify(p) : k),
    $emit: (name, payload) => emitted.push([name, payload]),
    requestRemote: requestImpl,
  }
  vm.submitRemote = new Function(`return (async function submitRemote(code) ${methodBody(CLIENT_FORM, 'async submitRemote(code) {')})`)().bind(vm)
  return { vm, emitted }
}

test('访问码（桌面端）：服务器地址不是 http(s) 时不发请求，给出可读提示', async () => {
  let calls = 0
  const { vm } = remoteVm(() => { calls++ }, 'case.example.com')
  await vm.submitRemote('abc')
  assert.equal(calls, 0)
  assert.equal(vm.errorText, 'welcome.caseServerInvalid')
})

test('访问码（桌面端）：打的是案件库服务器的 /api/auth/client-login，失败时把服务器原文完整显示、不伪造成功', async () => {
  const seen = []
  const { vm, emitted } = remoteVm(async (base, code) => {
    seen.push([base, code])
    return { code: 1, message: '访问码无效或已过期，请联系承办律师重新发送。' }
  }, 'https://case.example.com///')
  await vm.submitRemote('code-1')
  assert.deepEqual(seen, [['https://case.example.com', 'code-1']], '去掉末尾斜杠后拼接')
  assert.equal(vm.errorText, '访问码无效或已过期，请联系承办律师重新发送。')
  assert.equal(vm.noticeText, '')
  assert.deepEqual(emitted, [])
  assert.equal(vm.loading, false)
})

test('访问码（桌面端）：兑换成功也不写本机会话、不跳进本机工作台（TODO(#1050)），只如实告知', async () => {
  const { vm, emitted } = remoteVm(async () => ({ code: 0, data: { sessionId: 's', projectId: 7, user: { role: 'CLIENT' } } }), 'https://case.example.com')
  await vm.submitRemote('ok')
  assert.equal(vm.noticeText, 'welcome.accessCodeRemoteVerified')
  assert.deepEqual(emitted, [['remote-verified', { serverUrl: 'https://case.example.com', projectId: 7 }]])
  // 只看真代码：注释里要写明「为什么不写会话」，那段说明不该把断言判红
  const body = methodBody(CLIENT_FORM, 'async submitRemote(code) {').replace(/^\s*\/\/.*$/gm, '')
  assert.ok(!/saveSession|reLaunch|leaveWorkbench|enterCase/.test(body), '远端兑换这条路不许动本机会话与路由')
})

test('访问码：登录页与欢迎标签共用同一个组件，欢迎标签在桌面端给出服务器地址栏', () => {
  assert.match(read('pages/login/login.vue'), /<ClientAccessCodeForm \/>/)
  const welcome = read('components/welcome/WelcomePane.vue')
  assert.match(welcome, /<ClientAccessCodeForm compact autofocus :server-url="caseServerUrl" :show-server-field="isDesktop" \/>/)
  assert.match(welcome, /getOfficialCloud\(\)/, '默认服务器取官方案件库地址（cloud.collab.base-url）')
})
