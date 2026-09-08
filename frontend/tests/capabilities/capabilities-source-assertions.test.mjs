/**
 * 「能力升级」分区的源码级断言（dev-board#497）。
 *
 * 这个分区的风险不在渲染细节，而在四个整合点：分区必须接在 AdminPane 那条
 * v-if/v-else-if 长链的**末尾**（接错位置是编译期错误，或者更糟——静默不渲染）、
 * api 函数与后端端点对得上、i18n 两语言成对、开发者模式默认开（dev-board#497 维护者裁决）
 * 且打开前有二次确认。这四条都无法用纯函数覆盖，只能读源码钉住。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '../../src')
const ROOT = resolve(SRC, '../..')
const adminPane = readFileSync(resolve(SRC, 'components/admin/AdminPane.vue'), 'utf8')
const api = readFileSync(resolve(SRC, 'services/api.js'), 'utf8')
const workbench = readFileSync(resolve(SRC, 'pages/project-overview/project-overview.vue'), 'utf8')
const slotRegistry = readFileSync(
  resolve(ROOT, 'backend/src/main/java/com/checkba/service/capability/CapabilitySlotRegistry.java'),
  'utf8',
)

test('分区接在 activeNav 长链的末尾，且链头没有被动过', () => {
  const branches = [...adminPane.matchAll(/activeNav === '([a-z_]+)'/g)].map((m) => m[1])
  assert.equal(branches[0], 'ai', '链头必须仍是 v-if="activeNav === \'ai\'"')
  // 链尾不止一个新分区（团队分区 dev-board#496 与本分区都接在原链尾 personal_settings 之后），
  // 所以钉的是「在原链尾之后、且在链上」，不是「绝对最后一个」。
  assert.ok(branches.includes('capabilities'), '能力升级分区必须在 activeNav 长链上')
  assert.ok(branches.indexOf('capabilities') > branches.indexOf('personal_settings'), '新分区必须接在原链尾 personal_settings 之后')
  assert.equal(branches[0], 'ai', '链头必须仍是 ai')
  assert.ok(
    adminPane.includes(`v-else-if="activeNav === 'capabilities'"`),
    '新分区必须是 v-else-if，不能自成一个新的 v-if 链',
  )
})

test('导航项挂在 system 组（管理员可见）且只在桌面端出现', () => {
  const line = adminPane
    .split('\n')
    .find((l) => l.includes(`key: 'capabilities'`) && l.includes('label:'))
  assert.ok(line, 'navItems 里应有 capabilities 一条')
  assert.match(line, /group: 'system'/)
  assert.match(line, /desktopOnly: true/)
})

test('六个 api 函数齐全，端点与后端 CapabilityController 对得上', () => {
  const expected = [
    ['getCapabilities', "'/api/capabilities'", 'GET'],
    ['selectCapability', '/select', 'POST'],
    ['rollbackCapability', '/rollback', 'POST'],
    ['planCapabilityInstall', "'/api/capabilities/plan'", 'POST'],
    ['applyCapabilityPlan', "'/api/capabilities/apply'", 'POST'],
    ['setCapabilityDevMode', "'/api/capabilities/dev-mode'", 'PUT'],
  ]
  for (const [fn, urlPart, method] of expected) {
    const idx = api.indexOf(`export function ${fn}(`)
    assert.ok(idx > 0, `api.js 缺少 ${fn}`)
    const body = api.slice(idx, idx + 400)
    assert.ok(body.includes(urlPart), `${fn} 的 url 应含 ${urlPart}`)
    assert.ok(body.includes(`method: '${method}'`), `${fn} 应是 ${method}`)
  }
})

test('AdminPane 只 import 它真正用到的四个能力接口（plan/apply 走 AI 工具，不在设置页里直接调）', () => {
  for (const fn of ['getCapabilities', 'selectCapability', 'rollbackCapability', 'setCapabilityDevMode']) {
    assert.ok(adminPane.includes(fn), `AdminPane 应引用 ${fn}`)
  }
})

test('开发者模式默认开（维护者裁决 dev-board#497），且打开前必须弹二次确认', () => {
  assert.match(adminPane, /capabilityDevMode: null/, '加载前不认定为关，避免开关闪一下「关」')
  assert.match(
    slotRegistry,
    /systemSettingService\.get\(DEV_MODE_KEY,\s*"true"\)/,
    '后端未设置时的默认字串必须是 "true"',
  )
  const idx = adminPane.indexOf('onToggleCapabilityDevMode(on)')
  assert.ok(idx > 0)
  const body = adminPane.slice(idx, idx + 900)
  assert.ok(body.includes('uni.showModal'), '打开前必须二次确认')
  assert.ok(
    body.includes('if (!on)') && body.indexOf('if (!on)') < body.indexOf('uni.showModal'),
    '关掉时不该弹确认，只有打开才弹',
  )
})

test('「让 AI 升级」在工作台里走 ai-prompt 事件，薄壳页退化为复制到剪贴板', () => {
  assert.match(adminPane, /emits: \['ai-prompt'\]/)
  const idx = adminPane.indexOf('onCapabilityAskAi()')
  assert.ok(idx > 0)
  const body = adminPane.slice(idx, idx + 800)
  assert.ok(body.includes("this.$emit('ai-prompt'"), '嵌入形态必须发 ai-prompt')
  assert.ok(body.includes('uni.setClipboardData'), '非嵌入形态必须有剪贴板兜底')
})

test('工作台两个窗格的 AdminPane 都绑了 ai-prompt——只绑一个的话另一侧点了没反应', () => {
  const bindings = [...workbench.matchAll(/@ai-prompt="onPluginQuickAction"/g)]
  assert.equal(bindings.length, 2, '左右窗格各一处')
})

test('样式一律走 --awd-* 令牌，不写死色值', () => {
  const idx = adminPane.indexOf('.cap-form {')
  assert.ok(idx > 0, '应有 .cap-form 样式')
  const block = adminPane.slice(idx, adminPane.indexOf('.cap-hint {') + 260)
  assert.ok(!/#[0-9a-fA-F]{3,8}\b/.test(block), '能力升级分区的样式里不许出现写死色值')
  assert.ok(block.includes('var(--awd-'), '应使用主题令牌')
})
