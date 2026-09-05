// 截图框选浮层的样式契约（dev-board#474）。
// 浮层的底图是一张冻结帧截图，框选矩形与提示条都叠在它上面：
//  (a) 选区背景必须半透明——PR#657 令牌化时换成 var(--awd-info-soft)，浅色主题下
//      该令牌是不透明 #EFF6FF，用户框住的内容整块被盖掉，只剩一片浅蓝；
//  (b) 提示条不能钉在左上角——那里是交通灯与项目标题，压上去像是界面坏了。
// 样式在 .scss 里，组件无法真渲染（本仓 node:test 一贯限制），按源码文本断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const scss = readFileSync(new URL('../../src/pages/project-overview/project-overview.scss', import.meta.url), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '')

const block = (selector) => {
  const start = scss.indexOf(`\n${selector} {`)
  assert.ok(start >= 0, `找不到 ${selector} 规则`)
  const end = scss.indexOf('\n}', start)
  return scss.slice(start, end)
}

test('.ocr-selection 背景是半透明字面量，不引用可能不透明的主题令牌', () => {
  const b = block('.ocr-selection')
  assert.doesNotMatch(b, /--awd-info-soft/, '浅色主题下 --awd-info-soft 是不透明的 #EFF6FF，会盖住冻结帧')
  const m = b.match(/background:\s*rgba\(\s*\d+\s*,\s*\d+\s*,\s*\d+\s*,\s*(0?\.\d+)\s*\)/)
  assert.ok(m, '选区背景必须写成 rgba(r,g,b,a) 字面量')
  assert.ok(Number(m[1]) <= 0.3, `选区背景 alpha 必须 <= 0.3，实际 ${m[1]}`)
})

test('.ocr-overlay-hintline 提示条离开窗口标题区（不再 left:14px/top:14px 压交通灯与项目名）', () => {
  const b = block('.ocr-overlay-hintline')
  assert.doesNotMatch(b, /\btop:\s*14px/, '提示条不能贴在窗口顶部标题区')
  assert.match(b, /\bbottom:\s*\d+px/, '提示条应贴在底部')
  assert.match(b, /left:\s*50%/, '提示条应水平居中')
  assert.match(b, /translateX\(-50%\)/, '提示条应水平居中')
})
