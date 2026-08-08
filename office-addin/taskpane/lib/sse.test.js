/**
 * 标签流解析器的回归用例。插件仓没有测试框架，用 Node 自带的 node:test 跑，零依赖：
 *   node --test office-addin/taskpane/lib/sse.test.js
 *
 * 为什么值得钉住：本文件覆盖的是一组「显示什么、吞掉什么」的取舍——
 * 未知标签默认不外漏（否则用户看到裸 XML），但判据只收到「协议标签的形状」，
 * 合同正文里的 <甲方>/<Party A> 这类占位符必须原样出现。这条边界改坏了不会报错，
 * 只会让律师看到源码或丢掉正文，所以用例比注释更可靠。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { createTagStreamParser } from './sse.js'

/** 把整段文本喂给解析器，返回三路输出 */
function parse(chunks) {
  let main = ''
  let thinking = ''
  const questions = []
  const p = createTagStreamParser({
    onMainText: (t) => { main += t },
    onThinkingText: (t) => { thinking += t },
    onQuestion: (q) => { questions.push(q) }
  })
  for (const c of [].concat(chunks)) p.feed(c)
  p.flush()
  return { main, thinking, questions }
}

test('反问：正文进主文本、选项单独交出、标签不外漏', () => {
  const { main, questions } = parse(
    '<question>\n这份合同按哪种方式结算？\n<option>按月结算</option>\n<option>一次性付清</option>\n</question>')
  assert.ok(main.includes('这份合同按哪种方式结算？'))
  assert.ok(!main.includes('<question>'))
  assert.ok(!main.includes('<option>'))
  // 选项文案不能混进正文，否则气泡里会出现两遍
  assert.ok(!main.includes('按月结算'))
  assert.deepEqual(questions.length, 1)
  assert.deepEqual(questions[0].options, ['按月结算', '一次性付清'])
})

test('反问：没有选项时正文照样可见，选项集为空（界面回落到输入框作答）', () => {
  const { main, questions } = parse('<question>请提供案号或当事人信息。</question>')
  assert.equal(main.trim(), '请提供案号或当事人信息。')
  assert.deepEqual(questions.length, 1)
  assert.deepEqual(questions[0].options, [])
})

test('反问：标签被切成两段字节也要认出来', () => {
  const { main, questions } = parse(['<que', 'stion>甲方是谁？<opt', 'ion>公司</option></question>'])
  assert.equal(main, '甲方是谁？')
  assert.deepEqual(questions[0].options, ['公司'])
})

test('反问：流被截断（question 未闭合）时已解析的选项不丢', () => {
  const { main, questions } = parse('<question>选哪个？<option>甲</option><option>乙</option>')
  assert.equal(main, '选哪个？')
  assert.deepEqual(questions.length, 1)
  assert.deepEqual(questions[0].options, ['甲', '乙'])
})

test('合同占位符不算标签：<甲方> / <Party A> 原样留在正文里', () => {
  const { main } = parse('由 <甲方> 与 <Party A> 签署，见 <乙方 全称>。')
  assert.equal(main, '由 <甲方> 与 <Party A> 签署，见 <乙方 全称>。')
})

test('未知但形状像协议标签：吞掉标记，内容仍然可见（不给用户看源码，也不给空气泡）', () => {
  const { main } = parse('<answer>结论如上。</answer>')
  assert.equal(main, '结论如上。')
})

test('已知的机器标签仍然整块不渲染，<final> 与思考通道不受影响', () => {
  const { main, thinking } = parse(
    '<thinking>先查法条</thinking><process name="x"><tool_code>read_document()</tool_code></process><final>正文</final>')
  assert.equal(main, '正文')
  assert.equal(thinking, '先查法条')
})
