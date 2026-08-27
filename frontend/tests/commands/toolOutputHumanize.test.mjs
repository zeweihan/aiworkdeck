// 工具输出可读化（dev-board#178）：执行过程卡点开后不许出现原始 JSON 代码。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { humanizeToolOutput } from '../../src/utils/toolOutputHumanize.js'

test('企查查形态的 JSON 渲染成键值文本：无括号引号、空字段剥除', () => {
  const raw = JSON.stringify({
    QccCode: 'QCN7VUF3P6',
    Partners: [{
      StockName: '韩泽伟', StockType: '自然人股东', StockPercent: '56.00%',
      InvestType: '', RealCapi: '', PaidUpCapital: '', CapiDate: '',
      TagsList: ['大股东', '实际控制人'],
    }],
    Address: '',
  })
  const out = humanizeToolOutput(raw)
  assert.ok(out.includes('StockName：韩泽伟'))
  assert.ok(out.includes('- 大股东'))
  assert.ok(!out.includes('{') && !out.includes('"'), '不许残留 JSON 语法噪音')
  assert.ok(!out.includes('InvestType') && !out.includes('Address'), '空字段该剥掉')
})

test('非 JSON 输出返回 null（调用方按纯文本展示原文）', () => {
  assert.equal(humanizeToolOutput('已找到 12 处匹配，详见文档。'), null)
  assert.equal(humanizeToolOutput(''), null)
})

test('带 SSE 截断后缀的 JSON：剥后缀能解析则渲染，截在 JSON 中间则退回纯文本', () => {
  const whole = JSON.stringify({ a: 1 }) + '...(截断)'
  assert.ok(humanizeToolOutput(whole).includes('a：1'))
  const midway = '{"a":1,"b":"xx...(truncated)'
  assert.equal(humanizeToolOutput(midway), null)
})

test('超长字符串与超长清单被截断', () => {
  const raw = JSON.stringify({ text: 'x'.repeat(500), list: Array.from({ length: 60 }, (_, i) => 'i' + i) })
  const out = humanizeToolOutput(raw)
  assert.ok(out.includes('…'))
  assert.ok(out.includes('另 30 条略'))
})
