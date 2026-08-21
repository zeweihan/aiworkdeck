// 审计（dev-board#74，dev-board#498 待处理清单）第 rF 批：面板/表单/列表侧的
// 请求定序 / 在途守卫缺陷。docs/AUDIT_2026-08-20_REMAINING.md 里给的原始行号
// （780/815/899/906/913/920/927/934/941/948/955/962/969/990）在合入 #506/#517
// 两次补扫与标注之后已经漂移，跟行号旁边给的中文描述对不上——本文件按内容在当前
// 文档里重新定位到的真实位置是 843/878/962/969/976/983/990/997/1004/1011/1018/
// 1025/1032/1053，14 条描述按顺序逐一命中，判定详情见交付回报。
//
// 这批里大半是同一个形状：请求定序（先发的响应比后发的晚回，不许覆盖）或
// 在途重入守卫（请求飞着时再点一次不该再发一次）。能复用 utils/requestGeneration.js
// 的 shouldAcceptResponse() 就复用，不单独写一套代次判断。
//
// 组件带 @/ 别名 import 不进来（本仓 node:test 的一贯限制）。这里统一走「抠出单个
// 方法的函数体，用 new Function 起真身、依赖全部显式注入」的路子（同
// tagmanager-add-inflight.test.mjs / version-timeline-race.test.mjs 的既有写法），
// 只在明确判不出行为差异、只能核实接线的地方才退回源码文本断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { shouldAcceptResponse } from '../../src/utils/requestGeneration.js'

function read(rel) {
  return readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')
}

// 抠出一个方法的花括号函数体。signature 必须精确到能唯一定位「方法定义」本身
// （例如 'async refresh() {'），不能只写方法名——很多方法名在同一个文件里还会
// 以 this.xxx()/@tap="xxx" 的形式作为调用点出现好几次。
function extractMethod(src, signature) {
  const head = src.indexOf(signature)
  assert.ok(head > 0, `找不到方法定义：${signature}`)
  const braceStart = head + signature.length - 1
  assert.equal(src[braceStart], '{', `定位到的不是花括号起点：${signature}`)
  let depth = 0
  for (let i = braceStart; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}' && --depth === 0) return src.slice(braceStart, i + 1)
  }
  throw new Error(`括号没配上：${signature}`)
}

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

// ======================================================================
// 1. FeedbackWidget.vue toggleRecording()：getUserMedia 在飞时无重入守卫
//    （文档现址 843，原始清单行号误标 780）
// ======================================================================

const FW_SRC = read('components/FeedbackWidget.vue')

function makeToggleRecording(getUserMediaImpl) {
  const body = extractMethod(FW_SRC, 'async toggleRecording() {')
  return new Function(
    'navigator', 'MediaRecorder',
    `return (async function toggleRecording() ${body})`,
  )({ mediaDevices: { getUserMedia: getUserMediaImpl } }, function FakeMediaRecorder() {})
}

test('toggleRecording: getUserMedia 在飞时二次点击不会重入，不发出第二个权限请求', async () => {
  let calls = 0
  const gate = deferred()
  const fn = makeToggleRecording(() => { calls++; return gate.promise })
  const ctx = { recording: false, audio: null, $t: (k) => k, setStatus() {} }

  const first = fn.call(ctx)
  const second = fn.call(ctx) // this.recording 要等 await 后才置真，此刻仍是 false

  assert.equal(calls, 1, '权限弹窗结果出来前二次点击必须被挡住，不能开出第二路 getUserMedia')

  gate.reject(new Error('denied'))
  await first
  await second
})

test('toggleRecording: 权限请求失败后闸要放开，允许重新发起录音', async () => {
  let calls = 0
  const fn = makeToggleRecording(() => { calls++; return Promise.reject(new Error('denied')) })
  const ctx = { recording: false, audio: null, $t: (k) => k, setStatus() {} }

  await fn.call(ctx)
  assert.equal(calls, 1)
  await fn.call(ctx)
  assert.equal(calls, 2, '第一次失败后应当能重新点击发起录音，不能被永久锁死')
})

// ======================================================================
// 5. FeedbackWidget.vue openMine()：无请求定序（文档现址 976，原始行号误标 913）
// ======================================================================

function makeOpenMine(getMyFeedbackImpl) {
  const body = extractMethod(FW_SRC, 'async openMine() {')
  return new Function(
    'getMyFeedback', 'formatMineItem', 'shouldAcceptResponse',
    `return (async function openMine() ${body})`,
  )(getMyFeedbackImpl, (x) => x, shouldAcceptResponse)
}

test('openMine: 先发后回的响应不能盖掉后发先回的新列表', async () => {
  const gates = [deferred(), deferred()]
  let n = 0
  const fn = makeOpenMine(() => gates[n++].promise)
  const ctx = { view: '', mineLoading: false, mineError: '', mineList: [], $t: (k) => k, _mineRequestSeq: 0 }

  const first = fn.call(ctx)  // myFeedback -> back -> myFeedback 的第一次
  const second = fn.call(ctx) // 很快又点了一次

  gates[1].resolve({ data: { items: [{ id: 'B' }] } })
  await second
  gates[0].resolve({ data: { items: [{ id: 'A-stale' }] } })
  await first

  assert.deepEqual(ctx.mineList, [{ id: 'B' }], '陈旧响应不许覆盖已经渲染的新列表')
  assert.equal(ctx.mineLoading, false)
})

// ======================================================================
// 2. VersionPanel.vue refresh()：无在途守卫（文档现址 878，原始行号误标 815）
// ======================================================================

const VP_SRC = read('components/version/VersionPanel.vue')

function makeVersionPanelVm(api) {
  const vm = {
    loading: true, loadError: false, enabled: false, working: false, changedCount: 0,
    onDraft: null, drafts: [], adoptConflict: null, cloudConflict: null, sessionEndConflict: null,
    cloud: null, hasConnection: false, timelineKey: 0, busy: false, refreshSeq: 0,
    projectId: 1,
    $t: (k) => k,
    $emit: () => {},
  }
  const uni = { showToast() {} }
  for (const name of ['refresh', 'fetchDrafts', 'fetchCloudState']) {
    const body = extractMethod(VP_SRC, `async ${name}() {`)
    vm[name] = new Function(
      'getVersionStatus', 'listDrafts', 'getCloudStatus', 'listCloudConnections',
      'shouldAcceptResponse', 'uni',
      `return (async function ${name}() ${body})`,
    )(api.getVersionStatus, api.listDrafts, api.getCloudStatus, api.listCloudConnections, shouldAcceptResponse, uni).bind(vm)
  }
  return vm
}

test('VersionPanel.refresh: 两次调用乱序回来，陈旧响应不许覆盖新响应写好的状态', async () => {
  const gates = [deferred(), deferred()]
  let n = 0
  const vm = makeVersionPanelVm({
    getVersionStatus: () => gates[n++].promise,
    listDrafts: () => Promise.resolve({ data: { drafts: [] } }),
    getCloudStatus: () => Promise.resolve({ data: null }),
    listCloudConnections: () => Promise.resolve({ data: { connections: [] } }),
  })

  const first = vm.refresh()  // 例如 onReload -> refresh（结束工作段触发），慢
  const second = vm.refresh() // 例如 collabRefreshToken watcher 触发，快

  gates[1].resolve({ data: { enabled: true, working: false, changedCount: 0, onDraft: null } })
  await second
  gates[0].resolve({ data: { enabled: false, working: true, changedCount: 9, onDraft: { x: 1 } } })
  await first

  assert.equal(vm.enabled, true, '陈旧响应不许把新响应写好的 enabled 覆盖回去')
  assert.equal(vm.working, false, '陈旧响应不许让工作段状态倒退')
  assert.equal(vm.changedCount, 0)
  assert.equal(vm.loading, false, '最新一次请求完成后 loading 必须落回 false')
})

test('VersionPanel.refresh: 正常单次调用照常把状态写进去（不能过度守卫）', async () => {
  const vm = makeVersionPanelVm({
    getVersionStatus: () => Promise.resolve({ data: { enabled: true, working: true, changedCount: 3, onDraft: { name: 'd1' } } }),
    listDrafts: () => Promise.resolve({ data: { drafts: [{ id: 1 }] } }),
    getCloudStatus: () => Promise.resolve({ data: { linked: true } }),
    listCloudConnections: () => Promise.resolve({ data: { connections: [{ id: 9 }] } }),
  })
  await vm.refresh()
  assert.equal(vm.enabled, true)
  assert.equal(vm.changedCount, 3)
  assert.deepEqual(vm.drafts, [{ id: 1 }])
  assert.equal(vm.hasConnection, true)
  assert.equal(vm.loading, false)
})

// ======================================================================
// 3. AdminPane.vue loadFeedbackList()：反馈筛选切换竞态（文档现址 962，原始行号误标 899）
// ======================================================================

const AP_SRC = read('components/admin/AdminPane.vue')

function makeAdminFeedbackVm(getFeedbackListImpl) {
  const body = extractMethod(AP_SRC, 'async loadFeedbackList() {')
  const vm = {
    feedbackFilter: '', feedbackList: [], feedbackLoading: false, feedbackListSeq: 0,
    $t: (k) => k,
  }
  vm.loadFeedbackList = new Function(
    'getFeedbackList', 'shouldAcceptResponse', 'uni',
    `return (async function loadFeedbackList() ${body})`,
  )(getFeedbackListImpl, shouldAcceptResponse, { showToast() {} }).bind(vm)
  return vm
}

test('AdminPane.loadFeedbackList: 切筛选档太快，陈旧档位的响应不许覆盖新档位的列表', async () => {
  const gates = { A: deferred(), B: deferred() }
  const vm = makeAdminFeedbackVm((filterKey) => gates[filterKey].promise)

  vm.feedbackFilter = 'A'
  const first = vm.loadFeedbackList()  // 点了「全部」
  vm.feedbackFilter = 'B'
  const second = vm.loadFeedbackList() // 很快又点了「待处理」

  gates.B.resolve({ data: { items: [{ id: 'b1' }] } })
  await second
  gates.A.resolve({ data: { items: [{ id: 'a1-stale' }] } })
  await first

  assert.deepEqual(vm.feedbackList, [{ id: 'b1' }], '陈旧筛选档的响应不许覆盖当前筛选档已经渲染好的列表')
  assert.equal(vm.feedbackLoading, false)
})

// ======================================================================
// 4. AdminPane.vue onNavTap()：重点「记忆同步」丢弃未保存表单（文档现址 969，原始行号误标 906）
// ======================================================================

function makeOnNavTapVm() {
  const body = extractMethod(AP_SRC, 'onNavTap(nav) {')
  const calls = {
    loadMemoryRepos: 0, loadCloudConnections: 0, loadPlatformServices: 0,
    loadSite: 0, loadAccount: 0, loadStorageLocation: 0, loadIdentityCandidates: 0,
    reloadFeedbackPanel: 0, refreshEntitlements: 0,
  }
  const onNavTap = new Function(
    'refreshEntitlements',
    `return (function onNavTap(nav) ${body})`,
  )(() => { calls.refreshEntitlements++ })
  const vm = {
    activeNav: 'ai',
    loadMemoryRepos() { calls.loadMemoryRepos++ },
    loadCloudConnections() { calls.loadCloudConnections++ },
    loadPlatformServices() { calls.loadPlatformServices++ },
    loadSite() { calls.loadSite++ },
    loadAccount() { calls.loadAccount++ },
    loadStorageLocation() { calls.loadStorageLocation++ },
    loadIdentityCandidates() { calls.loadIdentityCandidates++ },
    reloadFeedbackPanel() { calls.reloadFeedbackPanel++ },
  }
  vm.onNavTap = onNavTap.bind(vm)
  return { vm, calls }
}

test('onNavTap: 已经在记忆同步页时再点一次同一个导航项，不重新触发 loadMemoryRepos', () => {
  const { vm, calls } = makeOnNavTapVm()
  vm.onNavTap({ key: 'memory' })
  assert.equal(calls.loadMemoryRepos, 1, '第一次进入必须加载')
  vm.onNavTap({ key: 'memory' })
  assert.equal(calls.loadMemoryRepos, 1, '已经在该页时再点一次不该重新拉，否则会冲掉未保存的表单')
})

test('onNavTap: 从别的导航项切进记忆同步页仍要正常加载，别的导航项行为不受影响', () => {
  const { vm, calls } = makeOnNavTapVm()
  vm.onNavTap({ key: 'feedback' })
  vm.onNavTap({ key: 'memory' })
  assert.equal(calls.reloadFeedbackPanel, 1)
  assert.equal(calls.loadMemoryRepos, 1)
  vm.onNavTap({ key: 'account' })
  vm.onNavTap({ key: 'memory' }) // 离开又回来：正常重新加载
  assert.equal(calls.loadMemoryRepos, 2)
})

// ======================================================================
// 14. AdminPane.vue handleSave()：无重入守卫（文档现址 1053，原始行号误标 990）
// ======================================================================

test('AdminPane.handleSave: 请求飞着时二次点击不会重复发出保存请求', async () => {
  const gate = deferred()
  let calls = 0
  const body = extractMethod(AP_SRC, 'async handleSave() {')
  const vm = { saving: false, form: { x: 1 }, loadModelCatalog() {}, $t: (k) => k }
  vm.handleSave = new Function(
    'saveAdminConfig', 'uni',
    `return (async function handleSave() ${body})`,
  )(() => { calls++; return gate.promise }, { showToast() {} }).bind(vm)

  const first = vm.handleSave()
  const second = vm.handleSave()
  assert.equal(calls, 1, '第一个保存请求在飞时二次点击不该再发一次')

  gate.resolve({})
  await first
  await second
  assert.equal(vm.saving, false)
})

test('AdminPane: 每个「保存配置」按钮都绑了 :disabled="saving"（此前只绑 :loading）', () => {
  // 2026-08-21 平台服务面板的保存按钮随 BYOK 表单一起撤掉（dev-board#98），只剩 AI 面板一处；
  // 口径改成「有几个保存按钮就有几个 :disabled」，别再写死个数。
  const buttons = (AP_SRC.match(/\$t\('admin\.saveConfigButton'\)/g) || []).length
  const count = (AP_SRC.match(/:disabled="saving"/g) || []).length
  assert.ok(buttons >= 1, '至少得有一个保存按钮')
  assert.equal(count, buttons, '每个保存按钮都要有 :disabled 绑定，跟 handleSave 的重入闸配套')
})

// ======================================================================
// 6. EasyVoicePane.vue：生成的音频 Blob URL 卸载时从不 revoke
//    文档现址 983（原始行号误标 927）——复核结论：**已经修复，不需要改动**。
//
//    beforeUnmount() 里已经有：
//      this._unmounted = true
//      if (this.audioUrl) { URL.revokeObjectURL(this.audioUrl); this.audioUrl = '' }
//    并且已有专门的回归测试锁住这两行：
//      tests/project-home/audit-b2-source-assertions.test.mjs
//      「beforeUnmount 置卸载位并释放 audioUrl」
//    大概率是上一轮 PR#510「TTS 卸载后自动播放泄漏」顺带修的（同一个 _unmounted
//    判据、同一个 beforeUnmount 钩子）。这里不重复造一份测试，只留下这条说明，
//    交付回报里也会点出来，别被空跑的红/绿状态误导成"这条我修的"。
// ======================================================================

// ======================================================================
// 7. login.vue：登录/注册/客户登录按钮只绑 :loading 不绑 :disabled，允许重复提交
//    （文档现址 990，原始行号误标 934）
// ======================================================================

const LOGIN_SRC = read('pages/login/login.vue')

function makeLoginHandler(name, apiParamName, apiImpl) {
  const body = extractMethod(LOGIN_SRC, `async ${name}() {`)
  return new Function(
    apiParamName, 'uni',
    `return (async function ${name}() ${body})`,
  )(apiImpl, { showToast() {}, reLaunch() {} })
}

test('login: handleLogin 请求飞着时二次点击不会重复发出登录请求', async () => {
  let calls = 0
  const gate = deferred()
  const fn = makeLoginHandler('handleLogin', 'login', () => { calls++; return gate.promise })
  const ctx = { loginLoading: false, loginForm: { username: 'u', password: 'p' }, $t: (k) => k, finishLogin() {} }

  const first = fn.call(ctx)
  const second = fn.call(ctx)
  assert.equal(calls, 1, '第一个登录请求在飞时二次点击不该再发一次')

  gate.reject(new Error('bad credentials'))
  await first
  await second
  assert.equal(ctx.loginLoading, false, '失败后闸要放开')
})

test('login: handleSmsLogin 请求飞着时二次提交验证码不会重复请求', async () => {
  let calls = 0
  const gate = deferred()
  const fn = makeLoginHandler('handleSmsLogin', 'login', () => { calls++; return gate.promise })
  const ctx = {
    loginLoading: false, loginForm: { username: 'u', password: 'p' },
    smsCodeInput: '123456', $t: (k) => k, finishLogin() {}, resetSmsStep() {},
  }

  const first = fn.call(ctx)
  const second = fn.call(ctx)
  assert.equal(calls, 1)

  gate.reject(new Error('bad code'))
  await first
  await second
})

test('login: handleRegister 请求飞着时二次点击不会重复发出注册请求', async () => {
  let calls = 0
  const gate = deferred()
  const fn = makeLoginHandler('handleRegister', 'register', () => { calls++; return gate.promise })
  const ctx = {
    registerLoading: false,
    registerForm: { username: 'u', password: 'password1', passwordConfirm: 'password1', displayName: '' },
    $t: (k) => k,
  }

  const first = fn.call(ctx)
  const second = fn.call(ctx)
  assert.equal(calls, 1)

  gate.reject(new Error('username taken'))
  await first
  await second
})

test('login: handleClientLogin 请求飞着时二次点击不会重复发出访问码登录请求', async () => {
  let calls = 0
  const gate = deferred()
  const fn = makeLoginHandler('handleClientLogin', 'clientLogin', () => { calls++; return gate.promise })
  const ctx = { clientLoginLoading: false, clientForm: { accessCode: 'code123' }, $t: (k) => k }

  const first = fn.call(ctx)
  const second = fn.call(ctx)
  assert.equal(calls, 1)

  gate.reject(new Error('invalid code'))
  await first
  await second
})

test('login: 四个提交按钮都绑了 :disabled，跟随各自的 loading 标志（此前只绑 :loading）', () => {
  assert.match(LOGIN_SRC, /:disabled="loginLoading" :loading="loginLoading" @tap="handleLogin"/)
  assert.match(LOGIN_SRC, /:disabled="loginLoading" :loading="loginLoading" @tap="handleSmsLogin"/)
  assert.match(LOGIN_SRC, /:disabled="registerLoading" :loading="registerLoading" @tap="handleRegister"/)
  assert.match(LOGIN_SRC, /:disabled="clientLoginLoading" :loading="clientLoginLoading" @tap="handleClientLogin"/)
})

// ======================================================================
// 8. VariablePanel.vue：并发 refresh 竞态让慢响应盖掉更新的变量/字段数据
//    （文档现址 997，原始行号误标 941）
// ======================================================================

const VARP_SRC = read('components/VariablePanel.vue')

function makeVariablePanelVm(api) {
  const vm = {
    projectId: 1, projectVars: [], userVars: [], docFields: [],
    _projectVarsSeq: 0, _userVarsSeq: 0, _docFieldsSeq: 0,
    getEditor: api.getEditor || null,
  }
  const deps = {
    getProjectVariables: api.getProjectVariables || (() => Promise.resolve([])),
    getUserVariables: api.getUserVariables || (() => Promise.resolve([])),
  }
  for (const name of ['fetchProjectVars', 'fetchUserVars', 'fetchDocFields']) {
    const body = extractMethod(VARP_SRC, `async ${name}() {`)
    vm[name] = new Function(
      'getProjectVariables', 'getUserVariables', 'shouldAcceptResponse',
      `return (async function ${name}() ${body})`,
    )(deps.getProjectVariables, deps.getUserVariables, shouldAcceptResponse).bind(vm)
  }
  return vm
}

test('VariablePanel.fetchProjectVars: 两次调用乱序回来，陈旧响应不许覆盖新响应的变量列表', async () => {
  const gates = [deferred(), deferred()]
  let n = 0
  const vm = makeVariablePanelVm({ getProjectVariables: () => gates[n++].promise })

  const first = vm.fetchProjectVars()  // 切到 Project 标签触发的一次
  const second = vm.fetchProjectVars() // 紧接着删除一条变量触发的刷新

  gates[1].resolve([{ name: 'fresh' }])
  await second
  gates[0].resolve([{ name: 'stale-should-be-deleted' }])
  await first

  assert.deepEqual(vm.projectVars, [{ name: 'fresh' }], '刚删掉的变量不许因为陈旧响应又冒出来')
})

test('VariablePanel.fetchUserVars: 独立写操作触发的调用跟 refresh() 触发的调用共享同一份代次', async () => {
  const gates = [deferred(), deferred()]
  let n = 0
  const vm = makeVariablePanelVm({ getUserVariables: () => gates[n++].promise })

  const fromRefresh = vm.fetchUserVars() // 假设是 refresh() 触发的
  const fromDelete = vm.fetchUserVars()  // 用户紧接着删除一条又单独触发了一次

  gates[1].resolve([{ name: 'after-delete' }])
  await fromDelete
  gates[0].resolve([{ name: 'before-delete-stale' }])
  await fromRefresh

  assert.deepEqual(vm.userVars, [{ name: 'after-delete' }])
})

test('VariablePanel.fetchDocFields: 两次调用乱序回来，陈旧响应不许覆盖新响应的字段列表', async () => {
  const gates = [deferred(), deferred()]
  let n = 0
  const editor = { listVariableFields: () => gates[n++].promise }
  const vm = makeVariablePanelVm({ getEditor: () => editor })

  const first = vm.fetchDocFields()
  const second = vm.fetchDocFields()

  gates[1].resolve([{ varName: 'fresh' }])
  await second
  gates[0].resolve([{ varName: 'stale' }])
  await first

  assert.deepEqual(vm.docFields, [{ varName: 'fresh' }])
})

// ======================================================================
// 9. project-list.vue confirmRename()：blur 在途时把 renamingProjectId 置空
//    （文档现址 1004，原始行号误标 948）
// ======================================================================

const PL_SRC = read('pages/project-list/project-list.vue')

function makeProjectListRenameVm(renameProjectImpl) {
  const confirmBody = extractMethod(PL_SRC, 'async confirmRename() {')
  const cancelBody = extractMethod(PL_SRC, 'cancelRename() {')
  const vm = {
    renamingProjectId: 1,
    renameValue: '新名字',
    projects: [{ id: 1, name: '旧名字' }, { id: 2, name: '另一个项目' }],
    $t: (k) => k,
  }
  vm.confirmRename = new Function(
    'renameProject', 'uni',
    `return (async function confirmRename() ${confirmBody})`,
  )(renameProjectImpl, { showToast() {} }).bind(vm)
  vm.cancelRename = new Function(`return (function cancelRename() ${cancelBody})`)().bind(vm)
  return vm
}

test('confirmRename: 请求飞着时 blur 触发 cancelRename 清空 id，续写仍要能改到卡片名字', async () => {
  const gate = deferred()
  const vm = makeProjectListRenameVm(() => gate.promise)

  const p = vm.confirmRename() // 用户按 Enter 提交，请求飞着
  vm.cancelRename()            // 紧接着点了别处：renamingProjectId/renameValue 被清空

  assert.equal(vm.renamingProjectId, null, '前置条件：blur 确实清空了')

  gate.resolve({})
  await p

  const project = vm.projects.find((x) => x.id === 1)
  assert.equal(project.name, '新名字', '异步续写必须仍然改到正确的那张卡片，不能因为 id 被清空就丢更新')
})

test('confirmRename: blur 期间用户对另一个项目开了新一轮改名，前一轮成功不许把它关掉', async () => {
  const gate = deferred()
  const vm = makeProjectListRenameVm(() => gate.promise)

  const p = vm.confirmRename() // 对项目 1 改名，请求飞着
  vm.cancelRename()
  vm.renamingProjectId = 2     // 用户接着对项目 2 开了新一轮改名（新的 startRename）
  vm.renameValue = '项目2的新名字'

  gate.resolve({})
  await p

  assert.equal(vm.renamingProjectId, 2, '项目 1 的改名成功续写不许把项目 2 正在进行的改名输入框关掉')
})

// ======================================================================
// 10. project-list.vue loadProjects()：无在途/定序守卫
//     （文档现址 1011，原始行号误标 955）
// ======================================================================

function makeLoadProjectsVm(getMyProjectsImpl, getProjectMembersImpl) {
  const body = extractMethod(PL_SRC, 'async loadProjects() {')
  const vm = { projects: [], projectsLoading: false, loadProjectsSeq: 0, isDesktop: true, $t: (k) => k }
  vm.loadProjects = new Function(
    'getMyProjects', 'getProjectMembers', 'shouldAcceptResponse', 'uni',
    `return (async function loadProjects() ${body})`,
  )(getMyProjectsImpl, getProjectMembersImpl, shouldAcceptResponse, { showToast() {}, reLaunch() {} }).bind(vm)
  return vm
}

test('loadProjects: 两次调用乱序回来，陈旧快照不许把刚删除的项目复活', async () => {
  const gates = [deferred(), deferred()]
  let call = 0
  const getProjectMembers = () => Promise.resolve({ data: [] })
  const vm = makeLoadProjectsVm(() => gates[call++].promise, getProjectMembers)

  const first = vm.loadProjects()  // 例如 onShow 触发的一次，慢
  const second = vm.loadProjects() // 例如删除后触发的一次，快

  gates[1].resolve([{ id: 1, name: 'P1' }])                          // 新的一次先回：项目2已经删了
  await second
  gates[0].resolve([{ id: 1, name: 'P1' }, { id: 2, name: 'P2-deleted' }]) // 旧的一次后回：还带着已删的项目2
  await first

  assert.deepEqual(vm.projects.map((p) => p.id), [1], '陈旧快照不许把刚删除的项目复活进列表')
  assert.equal(vm.projectsLoading, false)
})

// ======================================================================
// 11. VersionNodeDetail.vue confirmRevert()：唯一没有忙碌重入守卫的写操作
//     （文档现址 1018，原始行号误标 962——凑巧这个数字在当前文档里对应另一条，
//     已在交付回报里说明）
// ======================================================================

const VND_SRC = read('components/version/VersionNodeDetail.vue')

function makeVersionNodeDetailVm(revertToVersionImpl) {
  const body = extractMethod(VND_SRC, 'confirmRevert() {')
  const modalCalls = []
  const uni = { showModal: (o) => modalCalls.push(o), showToast() {} }
  const vm = { busy: false, projectId: 1, version: { sha: 'abc123', when: Date.now() }, $t: (k) => k, $emit: () => {} }
  vm.confirmRevert = new Function(
    'revertToVersion', 'uni',
    `return (function confirmRevert() ${body})`,
  )(revertToVersionImpl, uni).bind(vm)
  return { vm, modalCalls }
}

test('confirmRevert: 请求飞着时再次点击不会弹出第二个确认框', async () => {
  const gate = deferred()
  let calls = 0
  const { vm, modalCalls } = makeVersionNodeDetailVm(() => { calls++; return gate.promise })

  vm.confirmRevert()
  assert.equal(modalCalls.length, 1)
  const successPromise = modalCalls[0].success({ confirm: true }) // 用户确认，revertToVersion 飞着
  assert.equal(vm.busy, true, '确认之后应立即同步置忙碌，不能等请求回来才置')

  vm.confirmRevert() // 忙碌期间再点一次「退回到这一版」
  assert.equal(modalCalls.length, 1, '忙碌期间不该再弹出第二个确认框')

  gate.resolve({ data: { affectedFileIds: [] } })
  await successPromise

  assert.equal(calls, 1, '只应发出一次 revertToVersion 请求')
  assert.equal(vm.busy, false, '完成后闸要放开，允许下一次退回')
})

// ======================================================================
// 12. ProjectFavoritesPanel.vue：收藏搜索竞态（文档现址 1025，原始行号误标 969
//     ——凑巧这个数字在当前文档里对应另一条，已在交付回报里说明）
// ======================================================================

const PFP_SRC = read('components/ProjectFavoritesPanel.vue')

function makeFavoritesVm(getProjectFavoritesImpl) {
  const body = extractMethod(PFP_SRC, 'async refresh(force = false) {')
  const vm = {
    projectId: 1, query: '', items: [], loading: false,
    _lastRefreshAt: 0, _lastRefreshQuery: undefined, _refreshSeq: 0,
    $t: (k) => k,
  }
  vm.refresh = new Function(
    'getProjectFavorites', 'shouldAcceptResponse', 'uni',
    `return (async function refresh(force = false) ${body})`,
  )(getProjectFavoritesImpl, shouldAcceptResponse, { showToast() {} }).bind(vm)
  return vm
}

test('ProjectFavoritesPanel.refresh: 搜索关键字连续变化，陈旧关键字的响应不许覆盖新关键字的结果', async () => {
  const gates = { a: deferred(), ab: deferred() }
  const vm = makeFavoritesVm((_pid, q) => gates[q || 'a'].promise)

  vm.query = 'a'
  const first = vm.refresh(true)  // force 绕开节流，只隔离测请求定序
  vm.query = 'ab'
  const second = vm.refresh(true)

  gates.ab.resolve([{ id: 'ab-1' }])
  await second
  gates.a.resolve([{ id: 'a-1-stale' }])
  await first

  assert.deepEqual(vm.items, [{ id: 'ab-1' }], '陈旧关键字的响应不许覆盖已经渲染的新搜索结果')
})

// ======================================================================
// 13. ClipboardPanel.vue：剪贴板搜索竞态（文档现址 1032，原始行号误标 969
//     ——与上一条撞到同一个原始行号，本身就是原始清单行号已经过时的证据之一）
// ======================================================================

const CBP_SRC = read('components/ClipboardPanel.vue')

function makeClipboardVm(listClipboardImpl) {
  const body = extractMethod(CBP_SRC, 'async refresh() {')
  const vm = { query: '', items: [], loading: false, hiddenCount: 0, _refreshSeq: 0, $t: (k) => k }
  vm.refresh = new Function(
    'listClipboard', 'shouldAcceptResponse', 'uni',
    `return (async function refresh() ${body})`,
  )(listClipboardImpl, shouldAcceptResponse, { showToast() {} }).bind(vm)
  return vm
}

test('ClipboardPanel.refresh: 搜索关键字连续变化，陈旧关键字的响应不许覆盖新关键字的结果', async () => {
  const gates = { a: deferred(), ab: deferred() }
  const vm = makeClipboardVm((q) => gates[q || 'a'].promise)

  vm.query = 'a'
  const first = vm.refresh()
  vm.query = 'ab'
  const second = vm.refresh()

  gates.ab.resolve({ items: [{ id: 'ab-1' }], limited: false })
  await second
  gates.a.resolve({ items: [{ id: 'a-1-stale' }], limited: false })
  await first

  assert.deepEqual(vm.items, [{ id: 'ab-1' }], '陈旧关键字的响应不许覆盖已经渲染的新搜索结果')
})
