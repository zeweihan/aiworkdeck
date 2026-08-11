import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/ProfileHeader.vue'),
  'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

test('e2e 锚点：根类名 profile-header + 输入框类名 profile-field-input', () => {
  assert.ok(SRC.includes('class="profile-header"'))
  assert.ok(SRC.includes('class="profile-field-input"'), 'e2e 靠这个类名找输入框，不许改名')
})

test('props 契约', () => {
  assert.match(SRC, /projectId:\s*\{\s*type:\s*Number,\s*required:\s*true\s*\}/)
  assert.match(SRC, /projectName:\s*\{\s*type:\s*String,\s*default:\s*''\s*\}/)
  assert.match(SRC, /fields:\s*\{\s*type:\s*Array,\s*default:\s*\(\)\s*=>\s*\[\]\s*\}/)
  assert.match(SRC, /canEdit:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('emits save 已声明且 payload 是 {fieldKey, value}', () => {
  assert.match(SRC, /emits:\s*\['save'\]/)
  assert.match(SRC, /\$emit\('save',\s*\{\s*fieldKey:[^}]*value:[^}]*\}\s*\)/)
})

test('label 用服务端下发的，不在前端写第二份中文文案表', () => {
  assert.ok(SRC.includes('f.label'))
  for (const bad of ["'客户'", "'事项类型'", "'立项时间'", "'下一步'", "'对方'"])
    assert.ok(!SRC.includes(bad), '中文标签的单一来源是服务端: ' + bad)
})

test('不自己补齐或排序 fields（服务端保证恒 5 条顺序固定）', () => {
  assert.match(SRC, /v-for="f in fields"/)
  assert.ok(!SRC.includes('.sort('), '不许排序')
  assert.ok(!SRC.includes('FIELD_ORDER'), '不许在前端复制一份字段顺序表')
})

test('行内编辑而不是弹窗（awd-* 样式没有集中定义）', () => {
  assert.ok(!CODE.includes('awd-'), '不引入 awd-* 类名就不用自带 scoped 副本')
  assert.ok(!SRC.includes('uni.showModal'))
})

test('值没变就不发请求（blur 与 confirm 会各触发一次）', () => {
  assert.ok(SRC.includes('if (value === beforeValue) return'))
})

test('A 期不渲染「重新分析」死按钮，且标注了 Plan 2 要改回 AI 引导', () => {
  assert.ok(!CODE.includes('重新分析'))
  assert.ok(!CODE.includes('analyze'))
  assert.ok(SRC.includes('Plan 2 上线 AI 抽取后'), '空态文案是切片裁剪的有意偏离，必须留标注')
})

test('ai / default 都弱化标记，走 profileFieldHint', () => {
  assert.match(SRC, /import\s*\{[^}]*profileFieldHint[^}]*\}\s*from\s*'@\/utils\/projectHomeFormat\.js'/)
  assert.match(SRC, /profile-field-weak/)
})

test('空态引导 + 事项类型下拉用 MATTER_TYPES', () => {
  assert.match(SRC, /import\s*\{\s*MATTER_TYPES\s*\}\s*from\s*'@\/config\/matterTypes\.js'/)
  assert.match(SRC, /isProfileEmpty/)
  assert.ok(SRC.includes('这份案卷的档案还是空的'))
})

test('禁 emoji + 浅色', () => {
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  assert.ok(!SRC.includes('#212629'))
})

// 修复轮 1：commitEdit 是乐观退出（emit 后立刻清空编辑态），保存失败时草稿会
// 随之丢失。restoreEdit 是父级容器在保存失败 catch 里的恢复入口。
test('保存失败时的恢复入口：restoreEdit(fieldKey, value) 存在，重新进入该字段编辑态、draft 是传入的失败值', () => {
  const m = SRC.match(/restoreEdit\(fieldKey,\s*value\)\s*\{([\s\S]*?)\n\s*\},/)
  assert.ok(m, 'restoreEdit(fieldKey, value) 方法应存在')
  assert.match(m[1], /this\.editingKey\s*=\s*fieldKey/)
  assert.match(m[1], /this\.draft\s*=\s*value/)
})

test('留了路标注释：父级容器必须在保存失败的 catch 里调用 restoreEdit，否则用户输入会丢', () => {
  assert.ok(SRC.includes('父级容器必须在保存失败的 catch 里调用它，否则用户输入会丢'))
})
