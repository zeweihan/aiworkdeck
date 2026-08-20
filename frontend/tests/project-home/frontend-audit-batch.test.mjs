// 审计（dev-board#74）确认的三处前端问题的回归断言。
// 都是「源码文本断言」——本仓既有 node:test 用例的一贯写法（组件带 @/ 别名，import 不进来）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

// ---------- 1. 文件类型图标：兜底图标必须带 v-else ----------
//
// 908cc7d3（全站清零 emoji）把 `<text v-else class="fallback-icon">📄</text>` 换成
// 一个 svg 时，把 v-else 一起丢了。于是 docx/xlsx/pptx/pdf 这些**已知类型**会在同一个
// 19x19 的 flex 容器里同时画出真图标和兜底图标（.fallback-icon 既没 v-else 也没
// display:none），两个图标挤在一格里。FileTree / SearchPanel / ProcessCard 都在用。
test('FileTypeIcon 的兜底图标带 v-else，不会和真图标同时渲染', () => {
  const src = stripComments(read('components/FileTypeIcon.vue'))
  const fallback = src.match(/<svg[^>]*class="fallback-icon"[^>]*>/)
  assert.ok(fallback, '找不到 fallback-icon 的 svg')
  assert.match(fallback[0], /v-else/,
    '兜底图标必须 v-else，否则已知类型会同时画出两个图标（908cc7d3 的回归）')
})

test('FileTypeIcon 真图标仍然是 v-if 分支，两者构成互斥', () => {
  const src = stripComments(read('components/FileTypeIcon.vue'))
  assert.match(src, /<svg\s+[\s\S]{0,80}v-if="svgData"/,
    '真图标要保持 v-if="svgData"，与兜底的 v-else 配对')
})

// ---------- 2. 聊天输入框：输入法组合中的 Enter 不是发送 ----------
//
// 中文/日文/韩文输入时，按 Enter 上屏候选词也会派发 keydown（isComposing=true，
// 部分浏览器 keyCode=229）。不挡住就会把还没上屏的拼音直接当消息发出去。
// 编辑器侧（zetaOfficeImeOverlay / editor-main）早就为同一类问题做了 composing 闩。
test('handleEnterKey 在输入法组合期间直接返回，不发送', () => {
  const src = stripComments(read('components/ChatInterface.vue'))
  const start = src.indexOf('const handleEnterKey')
  assert.ok(start > 0, '找不到 handleEnterKey')
  const body = src.slice(start, start + 500)
  assert.match(body, /isComposing/, 'Enter 处理必须判 isComposing')
  assert.match(body, /229/, '还要兜住只给 keyCode=229 的浏览器')
  // 守卫必须排在真正的发送分支之前，否则等于没加
  assert.ok(body.indexOf('isComposing') < body.indexOf('handleSubmit'),
    '组合守卫必须排在 handleSubmit 之前')
})

// ---------- 3. 文本预览：不能把错误信封当正文显示 ----------
//
// uni.request 对 4xx/5xx 不 reject，走的是 success 回调。不看 statusCode 就把
// response.data 当正文，用户会在「文本预览」里读到 {"code":4010,...} 还以为那是文件内容。
test('loadTextContent 检查 statusCode，非 2xx 不当正文显示', () => {
  const src = stripComments(read('components/FilePreview.vue'))
  const start = src.indexOf('async loadTextContent')
  assert.ok(start > 0, '找不到 loadTextContent')
  const body = src.slice(start, src.indexOf('async loadMediaResource'))
  assert.match(body, /statusCode/, '必须检查 statusCode——uni.request 不会因 4xx/5xx reject')
  assert.ok(body.indexOf('statusCode') < body.lastIndexOf('this.textContent = response.data'),
    'statusCode 判断要排在把 data 当正文之前')
  assert.match(body, /files\.loadFailed/, '失败要走既有的失败文案，不要新造一套')
})
