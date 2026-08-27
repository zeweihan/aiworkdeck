// 命令注册表守卫。把 spec §4 的快捷键口径从「文档里的约定」变成「CI 里的断言」——
// 口径写在文档里会腐烂，写成测试才拦得住人。
//
// 跑法：cd frontend && npm run test:commands

import test from 'node:test'
import assert from 'node:assert/strict'

import {
  COMMANDS, COMMAND_BY_ID, MENU_ORDER, EDITOR_RESERVED_EXCEPTIONS,
  isEnabled, buildMenuPayload, labelOf,
} from '../../src/config/commands/index.js'

const withAccel = COMMANDS.filter((c) => c.accel)

test('命令 id 唯一', () => {
  assert.equal(COMMAND_BY_ID.size, COMMANDS.length, '有重复的命令 id')
})

test('每条命令都有 label 的中英两版、归属菜单、run 目标', () => {
  for (const c of COMMANDS) {
    assert.ok(c.label && c.label.zh && c.label.en, c.id + ' 缺中文或英文文案')
    assert.ok(MENU_ORDER.some((m) => m.id === c.menu), c.id + ' 的 menu 不在 MENU_ORDER 里: ' + c.menu)
    assert.ok(typeof c.run === 'string' && c.run.length, c.id + ' 缺 run')
    assert.ok(/^(app|wb):/.test(c.run), c.id + ' 的 run 必须是 app: 或 wb: 命名空间')
  }
})

test('加速键不重复', () => {
  const seen = new Map()
  for (const c of withAccel) {
    const prev = seen.get(c.accel)
    assert.equal(prev, undefined, '加速键 ' + c.accel + ' 被 ' + prev + ' 和 ' + c.id + ' 同时占用')
    seen.set(c.accel, c.id)
  }
})

// spec §4：外壳里嵌着 Word 编辑器，放进原生菜单的加速键会被永久从编辑器手里
// 拿走（NSMenu 的 key equivalent 先于响应链）。裸 Cmd+字母 / Cmd+数字 一律让给
// 编辑器，只有语义同构的那几个例外。
test('不征用编辑器保留的裸 Cmd+字母 / Cmd+数字', () => {
  const bare = /^CmdOrCtrl\+[A-Za-z0-9\\,.]$/
  for (const c of withAccel) {
    if (!bare.test(c.accel)) continue
    assert.ok(
      EDITOR_RESERVED_EXCEPTIONS.has(c.accel),
      c.id + ' 用了裸键 ' + c.accel + '，会从编辑器手里抢走。'
      + '要么换 Alt+CmdOrCtrl+*，要么确认它语义同构后加进 EDITOR_RESERVED_EXCEPTIONS'
    )
  }
})

test('Esc / Enter / Tab 不做加速键', () => {
  for (const c of withAccel) {
    assert.doesNotMatch(
      c.accel, /(^|\+)(Esc|Escape|Enter|Return|Tab)$/i,
      c.id + ' 用了 ' + c.accel + '，会吞掉编辑器和所有输入框的对应键'
    )
  }
})

test('不与 macOS 系统截图键撞车', () => {
  for (const c of withAccel) {
    assert.doesNotMatch(
      c.accel, /^Shift\+CmdOrCtrl\+[345]$/,
      c.id + ' 用了系统截图键 ' + c.accel + '，系统优先级更高，应用根本收不到'
    )
  }
})

test('checkbox 型命令必须声明 checked 读哪个 flag', () => {
  for (const c of COMMANDS) {
    if (c.type !== 'checkbox') continue
    assert.equal(typeof c.checked, 'string', c.id + ' 的 checked 必须是 flags 的键名字符串')
  }
})

test('整张表可 JSON 序列化（要经 IPC 下发给主进程）', () => {
  for (const c of COMMANDS) {
    for (const [k, v] of Object.entries(c)) {
      assert.notEqual(typeof v, 'function', c.id + '.' + k + ' 是函数，过不了 IPC')
    }
  }
  assert.doesNotThrow(() => JSON.stringify(COMMANDS))
})

test('when 的未知 token 判否而不是放行', () => {
  const cmd = { id: 'x', when: ['显然不存在的token'] }
  assert.equal(isEnabled(cmd, { page: 'workbench' }), false)
})

test('when 逐条求值', () => {
  const st = { page: 'workbench', role: 'LAWYER', flags: { hasProject: true, hasTab: false, isDocTab: false } }
  assert.equal(isEnabled({ when: ['workbench'] }, st), true)
  assert.equal(isEnabled({ when: ['workbench', 'project'] }, st), true)
  assert.equal(isEnabled({ when: ['workbench', 'tab'] }, st), false)
  assert.equal(isEnabled({ when: ['docTab'] }, st), false)
  assert.equal(isEnabled({}, st), true, '没有 when 的命令恒可用')
})

// spec §6.3：客户视图看不到 AI / 插件广场 / 系统设置，这是安全边界不是排版偏好。
test('客户视图下 AI 菜单整条不可用', () => {
  const client = { page: 'workbench', role: 'CLIENT', flags: { hasProject: true, hasTab: true, isDocTab: true } }
  const ai = COMMANDS.filter((c) => c.menu === 'ai')
  assert.ok(ai.length > 0)
  for (const c of ai) {
    assert.equal(isEnabled(c, client), false, c.id + ' 在客户视图下仍可执行')
  }
})

test('客户视图拿不到插件广场与底部工具', () => {
  const client = { page: 'workbench', role: 'CLIENT', flags: { hasProject: true } }
  for (const id of ['ai.pluginMarket', 'view.toolsPanel', 'view.toolFavorites', 'tools.ocrCapture']) {
    assert.equal(isEnabled(COMMAND_BY_ID.get(id), client), false, id + ' 对客户可用了')
  }
})

test('不在工作台时，工作台命令全部置灰', () => {
  const st = { page: 'login', role: 'LAWYER', flags: {} }
  for (const c of COMMANDS) {
    if (!(c.when || []).includes('workbench')) continue
    assert.equal(isEnabled(c, st), false, c.id + ' 在登录页仍可执行')
  }
})

test('buildMenuPayload 产出的树可序列化且分隔线不出现在首尾', () => {
  const st = {
    page: 'workbench', role: 'LAWYER', projectId: 7,
    flags: { hasProject: true, hasTab: true, isDocTab: true, aiPanelOpen: true },
    recent: [{ id: 7, name: '金冠纾困' }, { id: 8, name: '中棉集团' }],
    views: [{ key: 'files', label: '资源管理器' }, { key: 'search', label: '搜索' }],
    activeView: 'files',
  }
  const payload = buildMenuPayload(st, 'zh-CN')
  assert.doesNotThrow(() => JSON.stringify(payload))
  assert.ok(payload.menus.length >= 8, '菜单数量不对: ' + payload.menus.length)
  for (const m of payload.menus) {
    assert.notEqual(m.items[0] && m.items[0].type, 'separator', m.id + ' 以分隔线开头')
    assert.notEqual(m.items[m.items.length - 1] && m.items[m.items.length - 1].type, 'separator', m.id + ' 以分隔线结尾')
    for (let i = 1; i < m.items.length; i++) {
      if (m.items[i].type === 'separator') {
        assert.notEqual(m.items[i - 1].type, 'separator', m.id + ' 有连续分隔线')
      }
    }
  }
})

test('切换项目子菜单排除当前项目', () => {
  const st = {
    page: 'workbench', role: 'LAWYER', projectId: 7, flags: { hasProject: true },
    recent: [{ id: 7, name: 'A' }, { id: 8, name: 'B' }],
  }
  const go = buildMenuPayload(st, 'zh-CN').menus.find((m) => m.id === 'go')
  const sw = go.items.find((i) => i.id === 'go.switchProject')
  assert.ok(sw, '没有切换项目子菜单')
  assert.deepEqual(sw.submenu.map((i) => i.id), ['go.switchProject:8'])
})

test('没有最近项目时「打开最近」置灰而不是消失', () => {
  const st = { page: 'workbench', role: 'LAWYER', flags: {}, recent: [] }
  const file = buildMenuPayload(st, 'zh-CN').menus.find((m) => m.id === 'file')
  const recent = file.items.find((i) => i.id === 'file.openRecent')
  assert.ok(recent)
  assert.equal(recent.enabled, false)
  assert.equal(recent.submenu.length, 1)
  assert.equal(recent.submenu[0].enabled, false)
})

test('英文语境下取英文文案', () => {
  assert.equal(labelOf(COMMAND_BY_ID.get('file.openFolder'), 'en-US'), 'Open Folder…')
  assert.equal(labelOf(COMMAND_BY_ID.get('file.openFolder'), 'zh-CN'), '打开文件夹…')
  const en = buildMenuPayload({ page: 'workbench', flags: {} }, 'en-US')
  assert.equal(en.menus.find((m) => m.id === 'document').label, 'Document')
})
