import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { visibleText, visibleCode } from './_visible-text.mjs'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/OverviewStatsBar.vue'),
  'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = visibleCode(SRC, stripComments)
// 界面文案已外置到 zh locale：断言"显示了这句话"要看组件实际引用的键解析出的中文
const TEXT = visibleText(SRC)

test('e2e 锚点：根节点类名是 overview-stats-bar', () => {
  assert.ok(SRC.includes('class="overview-stats-bar"'), 'e2e 靠这个类名等统计条渲染，不许改名')
})

test('props 契约：stats 对象 + loading 布尔，都有默认值', () => {
  assert.match(SRC, /stats:\s*\{\s*type:\s*Object,\s*default:\s*\(\)\s*=>\s*\(\{\}\)\s*\}/)
  assert.match(SRC, /loading:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('计数措辞走 fileCountLabel，不在组件里自己拼', () => {
  assert.match(SRC, /import\s*\{[^}]*fileCountLabel[^}]*\}\s*from\s*'@\/utils\/projectHomeFormat\.js'/)
  assert.ok(!/已登记/.test(SRC.split('<script>')[0]), '模板里不许自己写 localRoot 措辞')
})

test('不展示项目大小与最近修改（那两个数是假的）', () => {
  for (const bad of ['项目大小', '最近修改', 'totalBytes', 'fileSize'])
    assert.ok(!CODE.includes(bad), '不该出现: ' + bad)
})

test('四个统计格都在：文件 / 文件夹 / 参与人 / 后台任务', () => {
  assert.ok(TEXT.includes('个文件夹'))
  assert.ok(TEXT.includes('位参与人'))
  assert.ok(TEXT.includes('个后台任务'))
  assert.match(SRC, /class="stat-tile"/)
})

test('浅色红线 + 禁 emoji', () => {
  assert.ok(!SRC.includes('#212629'), '外壳不做深色 chrome')
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC), '禁 emoji')
})
