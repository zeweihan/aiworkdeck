/**
 * 锚点跨段降级选段（dev-board#149）：Office.js 的 body.search 不跨段落，模型摘的
 * anchorText/searchText 若含 \n/\r 会首次必然「未找到」。pickAnchorFallback 把
 * 「按段落拆分、挑一段再 search」这个此前只能靠模型换短锚点重试的动作抽成纯函数，
 * 便于在没有 Office 全局的 node --test 环境下单测。
 *   node --test office-addin/taskpane/lib/anchorFallback.test.js
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { pickAnchorFallback, boundForSearch } from './officeExecutor.js'

test('无方向（replace_text）：跨段锚点取最长的一段', () => {
  const anchor = '短的\n这一段明显更长一些用来验证最长段选取逻辑\n中等长度的段落'
  assert.equal(pickAnchorFallback(anchor, null), '这一段明显更长一些用来验证最长段选取逻辑')
})

test('position=after（insert_text 末尾插入）：取最后一段', () => {
  const anchor = '第一段比较长长长长\n第二段\n第三段末尾插入锚点'
  assert.equal(pickAnchorFallback(anchor, 'after'), '第三段末尾插入锚点')
})

test('position=before（insert_text 开头插入）：取第一段', () => {
  const anchor = '开头插入锚点第一段\n第二段\n第三段比较长长长长'
  assert.equal(pickAnchorFallback(anchor, 'before'), '开头插入锚点第一段')
})

test('小于 4 字符的段被跳过，不参与选取', () => {
  const anchor = '短\n这一段够长可以用\n短'
  assert.equal(pickAnchorFallback(anchor, null), '这一段够长可以用')
  // 方向模式下也要跳过太短的首/尾段，改取次优的一段
  const anchorAfter = '开头这段够长\nAB\n短'
  assert.equal(pickAnchorFallback(anchorAfter, 'after'), '开头这段够长')
})

test('全部段落都太短时返回 null', () => {
  assert.equal(pickAnchorFallback('短\n段\n落', null), null)
  assert.equal(pickAnchorFallback('', 'after'), null)
})

test('支持 \\r\\n 与单独 \\r 换行符', () => {
  const anchor = '第一段落文本\r\n第二段落文本更长一些哦'
  assert.equal(pickAnchorFallback(anchor, null), '第二段落文本更长一些哦')
  const anchorCR = '第一段落文本\r第二段落文本更长一些哦'
  assert.equal(pickAnchorFallback(anchorCR, null), '第二段落文本更长一些哦')
})

test('boundForSearch：超过 255 字符截取前 255 字符前缀', () => {
  const long = 'a'.repeat(300)
  const bounded = boundForSearch(long)
  assert.equal(bounded.length, 255)
  assert.equal(bounded, long.slice(0, 255))
})

test('boundForSearch：不超限时原样返回', () => {
  assert.equal(boundForSearch('短文本'), '短文本')
})
