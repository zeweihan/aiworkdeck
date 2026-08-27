/**
 * 轻量 Markdown 渲染的回归用例（dev-board#197）。
 *   node --test office-addin/taskpane/lib/markdown.test.js
 *
 * 钉住三件事：
 * 1. 模型爱用的 **加粗** 不再以星号裸奔（维护者截图的直接病灶）；
 * 2. 3+ 连续换行折叠成一个段落间隔（消息中间的大段空白）；
 * 3. XSS 安全：原始 HTML 一律转义为字面量，链接只放行 http(s)。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { renderMarkdown } from './markdown.js'

test('加粗/斜体/行内代码渲染为标签，星号不外漏', () => {
  const html = renderMarkdown('**第一部分 · 对外** 与 *斜体* 与 `code`')
  assert.ok(html.includes('<strong>第一部分 · 对外</strong>'))
  assert.ok(html.includes('<em>斜体</em>'))
  assert.ok(html.includes('<code>code</code>'))
  assert.ok(!html.includes('**'), '星号不许出现在渲染结果里')
})

test('不成对的星号保持字面量，不吞内容', () => {
  const html = renderMarkdown('净利润*2 与 3*4 的乘积')
  assert.ok(html.includes('净利润'))
  assert.ok(html.includes('乘积'))
})

test('3+ 连续换行折叠，段落间不出现空段', () => {
  const html = renderMarkdown('上一段。\n\n\n\n\n\n下一段。')
  assert.equal(html, '<p>上一段。</p><p>下一段。</p>')
})

test('标题与有序/无序列表', () => {
  const html = renderMarkdown('## 要点\n1. 第一\n2. 第二\n- 甲\n- 乙')
  assert.ok(html.includes('<h4>要点</h4>'))
  assert.ok(html.includes('<ol><li>第一</li><li>第二</li></ol>'))
  assert.ok(html.includes('<ul><li>甲</li><li>乙</li></ul>'))
})

test('围栏代码块整块进 pre，未闭合围栏（流式中）也不报错', () => {
  const html = renderMarkdown('前文\n```\nconst a = 1\n```\n后文')
  assert.ok(html.includes('<pre><code>const a = 1</code></pre>'))
  const streaming = renderMarkdown('前文\n```\n还没写完')
  assert.ok(streaming.includes('<pre><code>还没写完</code></pre>'))
})

test('XSS：原始 HTML 转义为字面量，非 http 链接不成 a 标签', () => {
  const html = renderMarkdown('<script>alert(1)</script> 与 [点我](javascript:alert(1))')
  assert.ok(!html.includes('<script>'))
  assert.ok(html.includes('&lt;script&gt;'))
  assert.ok(!html.includes('href="javascript'))
  const ok = renderMarkdown('[官网](https://aiworkdeck.com/a?b=1&c=2)')
  assert.ok(ok.includes('<a href="https://aiworkdeck.com/a?b=1&amp;c=2"'))
  assert.ok(ok.includes('rel="noopener noreferrer"'))
})

test('段内单换行渲染为 br，保住模型的软换行排版', () => {
  const html = renderMarkdown('第一行\n第二行')
  assert.equal(html, '<p>第一行<br>第二行</p>')
})
