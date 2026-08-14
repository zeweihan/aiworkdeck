import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { visibleText, visibleCode } from './_visible-text.mjs'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/ActivityFeed.vue'),
  'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = visibleCode(SRC, stripComments)
// 界面文案已外置到 zh locale：断言"显示了这句话"要看组件实际引用的键解析出的中文
const TEXT = visibleText(SRC)

test('e2e 锚点：根节点类名是 activity-feed', () => {
  assert.ok(SRC.includes('class="activity-feed"'))
})

test('四个 props 齐全且都有默认值', () => {
  assert.match(SRC, /versions:\s*\{\s*type:\s*Array,\s*default:\s*\(\)\s*=>\s*\[\]\s*\}/)
  assert.match(SRC, /backgroundRuns:\s*\{\s*type:\s*Array,\s*default:\s*\(\)\s*=>\s*\[\]\s*\}/)
  assert.match(SRC, /loading:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
  assert.match(SRC, /unavailable:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('unavailable 走中性引导态而不是错误态', () => {
  assert.match(SRC, /v-else-if="unavailable"/)
  assert.ok(TEXT.includes('这份案卷还没有版本记录'))
  for (const bad of ['读取失败', '加载失败', '出错了', '请重试'])
    assert.ok(!CODE.includes(bad), 'unavailable 不许是错误文案: ' + bad)
})

test('空列表另有一条空态文案，与 unavailable 分开', () => {
  assert.ok(TEXT.includes('还没有动态'))
})

test('不标注 AI/人', () => {
  assert.ok(!CODE.includes('authorName'), '不许读 authorName')
})

test('标题与时间走公共纯函数，不在组件里再实现一遍', () => {
  assert.match(SRC, /import\s*\{[^}]*versionTitle[^}]*\}\s*from\s*'@\/utils\/projectHomeFormat\.js'/)
  assert.ok(SRC.includes('formatDateTime'))
  assert.ok(!/月.*日.*getHours/s.test(SRC), '不许自己格式化时间')
})

test('后台 AI 任务与版本条目合成同一条 feed', () => {
  assert.ok(SRC.includes('runStatusDotClass'))
  assert.match(SRC, /rows\s*\(\)/)
})

test('禁 emoji + 浅色', () => {
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  assert.ok(!SRC.includes('#212629'))
})
