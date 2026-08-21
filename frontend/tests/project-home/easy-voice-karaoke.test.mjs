// 审计（dev-board#74）：EasyVoicePane 的卡拉OK 高亮与正在播的音频脱钩。
// 源码文本断言——本仓既有 node:test 用例的一贯写法（组件带 @/ 别名，import 不进来）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/EasyVoicePane.vue', import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

// sentences 是「当前这段音频的时间轴」，不是「文本框里现在有什么」。
// 导入时就把它整块换掉，而旧音频还在播：ontimeupdate 会拿旧音频的时长算出下标，
// 去新文档的句子数组里取字符串 emit 出去，编辑器的选区就跟着旧音频在新文档里乱跳。
test('importFromDoc 不改写 sentences（时间轴归音频，不归文本框）', () => {
  const start = CODE.indexOf('async importFromDoc()')
  assert.ok(start > 0, '找不到 importFromDoc')
  const body = CODE.slice(start, CODE.indexOf('async handleGenerate()'))
  assert.ok(body.length > 0 && body.length < 2000, 'importFromDoc 函数体切取失败')
  assert.ok(!/this\.sentences\s*=/.test(body),
    '导入正文不许改 sentences：旧音频还在播时会把高亮串到新文档去')
})

test('sentences 的唯一写点在 handleGenerate（生成前重算）', () => {
  const writes = CODE.match(/this\.sentences\s*=/g) || []
  assert.equal(writes.length, 1, 'sentences 只许在生成音频前赋值一次，实际 ' + writes.length + ' 处')
  const start = CODE.indexOf('async handleGenerate()')
  const body = CODE.slice(start, CODE.indexOf('downloadAudio()'))
  assert.match(body, /this\.sentences\s*=\s*this\.splitTextToSentences\(this\.text\)/,
    'handleGenerate 必须在生成前按当前正文重算 sentences')
})
