// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
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

test('startNewChat 只断开当前面板，不得取消仍在后台运行的对话', () => {
  const src = stripComments(read('components/ChatInterface.vue'))
  const body = extractBlock(src, 'const startNewChat = () =>')
  assert.doesNotMatch(body, /\babort\(\)/,
    '新建对话只应 detach；取消后台任务必须由独立的停止按钮触发')
  assert.match(body, /setConversationId\(null\)/)
  assert.match(body, /clearBubbles\(\)/)
})

test('handleAbort 确实调用同一个 abort()（核实两处复用的是同一份取消逻辑，不是各写一份）', () => {
  const src = stripComments(read('components/ChatInterface.vue'))
  const body = extractBlock(src, 'const handleAbort = () =>')
  assert.match(body, /\babort\(\)/)
})

// ======================================================================
// 2. ChatInterface.vue：bubbles 全树空 deep watcher 每个 token 全量重遍历
// ======================================================================

test('bubbles 上不应该再挂一个空回调体的 deep watcher（增长观察不遍历历史消息）', () => {
  const src = stripComments(read('components/ChatInterface.vue'))
  assert.doesNotMatch(src, /watch\(bubbles,/,
    '不能再对 bubbles 整棵对象图做 deep watch——原来的回调体是空的，纯粹白白遍历一遍')
  assert.doesNotMatch(src, /\{\s*deep:\s*true\s*\}/,
    '本组件不该再有任何 { deep: true } 的 watch（唯一一处曾经存在的就是本条要删的这个）')
  assert.match(src, /watch\(\(\) => bubbles\.value\.length, \(\) => \{\s*if \(followLatest.value\) scrollToBottom\(\)/,
    '仅当用户跟随最新消息时，新增气泡才滚到底部')
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
