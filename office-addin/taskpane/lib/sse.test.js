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
import { createTagStreamParser, createSseConnection } from './sse.js'

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

test('工具输出里的协议标签已被后端中和，不会顶掉标签栈把载荷漏进正文', () => {
  // 后端 AgentTagProtocol 把载荷里的 </tool_output> 起始 < 换成 &lt;（历史回灌走同一条解析）。
  // 不中和的话这里的标签栈会在载荷中间弹空，后半段载荷就当正文发给用户了。
  const { main } = parse(
    '<process name="读取文件"><tool_code>read_file()</tool_code>' +
    '<tool_output status="SUCCESS">读到 &lt;/tool_output> 与 &lt;final>不该出现的半截</tool_output>' +
    '</process><final>已读完。</final>')
  assert.equal(main, '已读完。')
})

// ==================== 孤立 '<' 与闭合标签相邻（dev-board#70） ====================
// 真机「金冠纾困」会话实况：思考文本含「净利润<0」这类比较式时，
// 「'<' 到最近 '>'」的候选串会把真正的 </thinking> 包进去；旧实现整段放行，
// 闭合被当正文吞掉、标签栈错位，后续 <process>/<tool_code> 载荷全部漏进思考区。
// 修复后非标签候选只放行 '<' 本身、从下一字符重扫。

const LEAK_CASE = '<thinking>净利润<0，需追加担保</thinking>' +
  '<process name="x"><tool_code>foo()</tool_code></process><final>ok</final>'

test('孤立 < 紧邻闭合标签：闭合不被吞、工具载荷不漏进思考区（一次性喂入=历史回放）', () => {
  const { main, thinking } = parse(LEAK_CASE)
  assert.equal(thinking, '净利润<0，需追加担保')
  assert.equal(main, 'ok')
})

test('同一输入逐字节喂入（实时流式）结果一致', () => {
  const { main, thinking } = parse(LEAK_CASE.split(''))
  assert.equal(thinking, '净利润<0，需追加担保')
  assert.equal(main, 'ok')
})

test('正文数值比较 <80%> 原样放行（数字开头不像协议标签）', () => {
  const { main } = parse('<final>担保比例<80%>时豁免</final>')
  assert.equal(main, '担保比例<80%>时豁免')
})

// ==================== XHR 降级通道（WPS 老内核，无流式 fetch） ====================
// createSseConnection 在建连时探测流式 fetch 能力，探测不过整条走 XHR onprogress。
// 这里临时抹掉 ReadableStream 迫使降级，验证：行协议解析一致、增量消费、
// close() 只触发一次 onClose（XHR abort 是同步收尾，曾有双触发隐患）。

test('XHR 降级通道：增量消费事件、请求头带令牌、close 单次收尾', async () => {
  const savedRS = globalThis.ReadableStream
  const savedXHR = globalThis.XMLHttpRequest
  const instances = []
  class FakeXhr {
    constructor() {
      instances.push(this)
      this.readyState = 0
      this.status = 0
      this.responseText = ''
      this.headers = {}
      this.aborted = false
    }
    open(method, url) { this.url = url }
    setRequestHeader(k, v) { this.headers[k] = v }
    send() {
      setTimeout(() => {
        this.status = 200
        this.readyState = 2
        this.onreadystatechange && this.onreadystatechange()
        this.pushChunk('event: text_delta\ndata: {"a":1}\n\n')
      }, 0)
    }
    pushChunk(t) {
      this.readyState = 3
      this.responseText += t
      this.onreadystatechange && this.onreadystatechange()
    }
    abort() {
      this.aborted = true
      this.readyState = 4
      this.onabort && this.onabort()
    }
  }
  globalThis.ReadableStream = undefined
  globalThis.XMLHttpRequest = FakeXhr
  try {
    const events = []
    let closes = 0
    const conn = createSseConnection({
      baseUrl: 'https://x.example',
      token: 'awdt_xhr',
      conversationId: 'conv-xhr-1',
      onEvent: (name, data) => events.push([name, data]),
      onClose: () => { closes++ }
    })
    await conn.ready
    await new Promise((r) => setTimeout(r, 5))
    const xhr = instances[0]
    assert.equal(xhr.url, 'https://x.example/api/agent/connect/conv-xhr-1')
    assert.equal(xhr.headers['X-Session-Id'], 'awdt_xhr')
    assert.deepEqual(events[0], ['text_delta', '{"a":1}'])
    // 多字节分段推进：跨 chunk 的半行要接得上
    xhr.pushChunk('event: bubble_end\ndata: {"do')
    xhr.pushChunk('ne":true}\n\n')
    assert.deepEqual(events[1], ['bubble_end', '{"done":true}'])
    conn.close()
    assert.ok(xhr.aborted, 'close 必须 abort 掉 XHR')
    assert.equal(closes, 1, 'onClose 只许触发一次（同步 abort 曾有双触发隐患）')
  } finally {
    globalThis.ReadableStream = savedRS
    globalThis.XMLHttpRequest = savedXHR
  }
})

test('XHR 降级通道：非 200 首连 ready 即拒绝并带状态码（自愈判定依赖它）', async () => {
  const savedRS = globalThis.ReadableStream
  const savedXHR = globalThis.XMLHttpRequest
  class Fake403 {
    open() {}
    setRequestHeader() {}
    send() {
      setTimeout(() => {
        this.status = 403
        this.readyState = 2
        this.onreadystatechange && this.onreadystatechange()
      }, 0)
    }
    abort() { this.readyState = 4 }
  }
  globalThis.ReadableStream = undefined
  globalThis.XMLHttpRequest = Fake403
  try {
    const conn = createSseConnection({
      baseUrl: 'https://x.example', token: 't', conversationId: 'c',
      onEvent: () => {}, onClose: () => {}
    })
    await assert.rejects(conn.ready, (e) => e.status === 403)
    conn.close()
  } finally {
    globalThis.ReadableStream = savedRS
    globalThis.XMLHttpRequest = savedXHR
  }
})
