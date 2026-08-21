// 审计（dev-board#74）本轮（claude/audit-rE-fe1）四条源码文本断言：ChatInterface.vue
// 与 FileTree.vue 体量太大（4500+/5300+ 行、几十个 @/ 别名 import），本仓 node:test
// 一贯限制是这类文件抠 <script> 求值风险太高（需要枚举全部 import 做桩，任何一个
// 漏了都会整份炸掉），照 audit-b2-source-assertions.test.mjs 的先例走源码文本核实：
// 判定条件本身能抽出纯函数的都已经在各自独立的 *.test.mjs 里用真实逻辑跑过
// （createSerialQueue 见 async-serialize.test.mjs，shouldFlushDocStream 见
// doc-stream-target-mismatch.test.mjs）；这里覆盖的是"确认真的接上了线"。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

// 抠出 name(...) { ... } 形式的一个方法体（按大括号配平截断），用于在大文件里
// 定位一段代码而不必整份 new Function。
function extractBlock(src, marker, braceOpenOffset) {
  const start = src.indexOf(marker)
  assert.ok(start > 0, '找不到 ' + JSON.stringify(marker))
  let i = start + (braceOpenOffset != null ? braceOpenOffset : marker.length)
  while (src[i] !== '{') i++
  let depth = 0
  const openIdx = i
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break } }
  }
  return src.slice(start, i)
}

// ======================================================================
// 1. ChatInterface.vue：流式中途「新建对话」不发取消请求
// ======================================================================

test('startNewChat 在 isStreaming 时必须调用 abort()（内部会 POST /api/agent/cancel），且排在清空会话状态之前', () => {
  const src = stripComments(read('components/ChatInterface.vue'))
  const body = extractBlock(src, 'const startNewChat = () =>')
  assert.match(body, /if \(isStreaming\.value\) abort\(\)/,
    '必须在 isStreaming 时调用 abort()——这是唯一真正向后端发取消请求的路径（handleAbort 用的就是它）')
  const abortIdx = body.indexOf('abort()')
  const setConvIdx = body.indexOf('setConversationId(null)')
  assert.ok(abortIdx > 0 && setConvIdx > abortIdx,
    'abort() 必须排在 setConversationId(null) 之前——发完取消请求再清前端状态，不能反过来')
})

test('handleAbort 确实调用同一个 abort()（核实两处复用的是同一份取消逻辑，不是各写一份）', () => {
  const src = stripComments(read('components/ChatInterface.vue'))
  const body = extractBlock(src, 'const handleAbort = () =>')
  assert.match(body, /\babort\(\)/)
})

// ======================================================================
// 2. ChatInterface.vue：bubbles 全树空 deep watcher 每个 token 全量重遍历
// ======================================================================

test('bubbles 上不应该再挂一个空回调体的 deep watcher（唯一保留的 watch 只看 length）', () => {
  const src = stripComments(read('components/ChatInterface.vue'))
  assert.doesNotMatch(src, /watch\(bubbles,/,
    '不能再对 bubbles 整棵对象图做 deep watch——原来的回调体是空的，纯粹白白遍历一遍')
  assert.doesNotMatch(src, /\{\s*deep:\s*true\s*\}/,
    '本组件不该再有任何 { deep: true } 的 watch（唯一一处曾经存在的就是本条要删的这个）')
  assert.match(src, /watch\(\(\) => bubbles\.value\.length, \(\) => \{\s*scrollToBottom\(\)/,
    '新气泡出现时滚到底部的浅层 watch 必须保留——这是唯一还需要的行为')
})

// ======================================================================
// 3. FileTree.vue：并发批量上传生成重复临时 id
// ======================================================================

test('uploadSingleFile 的 tempId 生成必须叠加一个自增序号，不能是裸 Date.now()', () => {
  const src = stripComments(read('components/FileTree.vue'))
  const body = extractBlock(src, 'async uploadSingleFile(projectId, file, parentId, pendingTempId = null)')
  assert.doesNotMatch(body, /const tempId = Date\.now\(\)\n/,
    '裸 Date.now() 在并发批量上传（CONCURRENCY=3 背靠背同步调用）里可能在同一毫秒内撞出重复 id')
  assert.match(body, /const tempId = Date\.now\(\) \* 1000 \+ \(this\._uploadTempIdSeq/,
    '必须叠加一个组件级自增序号，保证同一毫秒内也互不相同')
})

test('接线核实：processNext 确实是同步背靠背调用（本条缺陷的触发前提仍然成立）', () => {
  const src = stripComments(read('components/FileTree.vue'))
  const body = extractBlock(src, 'for (let i = 0; i < Math.min(CONCURRENCY, uploadQueue.length); i++)', 0)
  assert.match(body, /processNext\(\)/)
  assert.doesNotMatch(body, /await processNext\(\)/,
    '如果这里改成了 await，触发条件就不成立了——本用例的前提失效，需要重新评估这条修复是否还有必要')
})

// ======================================================================
// 4. FileTree.vue：按下标解析拖拽目标会与后台重载竞态导致移进错的文件夹
// ======================================================================

test('handleDragStart 必须记下 draggedFileId（不能只记下标）', () => {
  const src = stripComments(read('components/FileTree.vue'))
  const body = extractBlock(src, 'handleDragStart(e, item, index) {', 0)
  assert.match(body, /this\.draggedFileId = \(item && item\.id != null\) \? item\.id : null/)
})

for (const [marker, offset] of [
  ['async handleDrop(e, index) {', 0],
  ['async onRootDrop(e) {', 0],
]) {
  test(marker + ' 必须按 draggedFileId 重新定位拖拽源，不能再用 displayFiles[this.draggedIndex]', () => {
    const src = stripComments(read('components/FileTree.vue'))
    const body = extractBlock(src, marker, offset)
    assert.doesNotMatch(body, /displayFiles\[this\.draggedIndex\]/,
      '直接按下标取，dragstart 与 drop 之间的后台重载（比如并发上传完成触发的 loadFiles）会让下标指向另一个文件')
    assert.match(body, /this\.displayFiles\.find\(f => f\.id === this\.draggedFileId\)/,
      '必须按 dragstart 时记下的 id 重新查找')
    // 查不到必须提前退出，不能带着 undefined 继续往下调 moveFile
    const findIdx = body.indexOf('this.displayFiles.find(f => f.id === this.draggedFileId)')
    const notFoundGuardIdx = body.indexOf('if (!draggedItem)', findIdx)
    const moveFileIdx = body.indexOf('moveFile(projectId', findIdx)
    assert.ok(notFoundGuardIdx > findIdx && notFoundGuardIdx < moveFileIdx,
      '查不到时必须提前 return，不能拿 undefined.id 去调 moveFile')
  })
}

test('draggedIndex 与 draggedFileId 必须在同一批复位点一起清空（不能只清一个，留另一个变成陈旧值）', () => {
  const src = stripComments(read('components/FileTree.vue'))
  // 逐个统计两个字段被赋值为"复位"的次数，必须相等（每次 draggedIndex = -1 都配一次 draggedFileId = null）
  const idxResets = (src.match(/this\.draggedIndex = -1/g) || []).length
  const fileIdResets = (src.match(/this\.draggedFileId = null/g) || []).length
  assert.ok(idxResets >= 4, '预期至少 4 处复位点（handleDrop 早退/handleDrop 未命中/handleDrop 尾部/handleDragEnd/onRootDrop 未命中/onRootDrop 尾部）')
  assert.equal(fileIdResets, idxResets, 'draggedFileId 的复位次数必须与 draggedIndex 完全一致，不能有遗漏')
})
