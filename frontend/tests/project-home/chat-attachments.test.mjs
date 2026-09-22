// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#793 K14 ④：用户气泡持有完整附件记录，「重新生成」与回退回填按它重建请求。
//
// 病灶：气泡上的 contextFiles 原来只是 {id, name, isDir} 的精简副本，历史回灌出来的气泡
// 一个附件字段都没有。于是 K11 的「重新生成」只能传 fileList: []——同一个问题重问一次，
// 材料却没跟着走，模型当然给出不一样的答案，而用户以为这是「换一份回答」的正常波动。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  attachmentKind,
  attachmentRecord,
  attachmentsFromHistory,
  fileListFromBubble,
} from '../../src/utils/chatAttachments.js'

const CI = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')

/** useAgentStream 把 fileList 映射成 contextItems 的那一段（逐字对应，见其 sendMessage）。 */
const toContextItems = (fileList) => fileList.map((f) => ({
  id: String(f.id),
  name: f.fileName || f.name || 'Unknown',
  isDir: f.isDir === true,
  fileType: f.fileType || '',
}))

test('kind 判据与后端同源：fileType 优先、缺失退回文件名后缀', () => {
  assert.equal(attachmentKind({ name: '现场.png' }), 'image')
  assert.equal(attachmentKind({ name: '现场', fileType: 'image' }), 'image')
  assert.equal(attachmentKind({ name: '扫描.bmp', fileType: 'other' }), 'image')
  assert.equal(attachmentKind({ name: '合同.docx' }), 'file')
  assert.equal(attachmentKind({ name: '合同.pdf' }), 'file', 'PDF 不能直送，不算 image')
  assert.equal(attachmentKind({ name: '证据', isDir: true }), 'folder')
  assert.equal(attachmentKind({ name: '证据', fileType: 'folder' }), 'folder')
})

test('完整记录带上 fileType / wpsFileId / kind，不再是三字段精简副本', () => {
  const r = attachmentRecord({ id: 9, name: '合同.docx', fileType: 'docx', wpsFileId: 'w-1' })
  assert.deepEqual(r, { id: '9', name: '合同.docx', fileType: 'docx', wpsFileId: 'w-1', kind: 'file', isDir: false })
})

test('带附件的一问「重新生成」：重建出来的 contextItems 与原问逐字一致', () => {
  // 原问：handleSubmit 组的 fileList（contextFiles 直接映射）
  const originalFileList = [
    { id: 9, fileName: '股权转让协议.docx', fileType: 'docx', wpsFileId: null, isDir: false },
    { id: 11, fileName: '现场照片.png', fileType: 'image', wpsFileId: null, isDir: false },
    { id: 100, fileName: '证据一', fileType: 'folder', wpsFileId: null, isDir: true },
  ]
  // 气泡上存的完整记录
  const bubble = {
    contextFiles: [
      { id: 9, name: '股权转让协议.docx', fileType: 'docx', wpsFileId: null, isDir: false },
      { id: 11, name: '现场照片.png', fileType: 'image', wpsFileId: null, isDir: false },
      { id: 100, name: '证据一', fileType: 'folder', wpsFileId: null, isDir: true },
    ].map(attachmentRecord),
  }

  assert.deepEqual(toContextItems(fileListFromBubble(bubble)), toContextItems(originalFileList))
})

test('刷新之后照样一致：历史回灌的记录重建出同一份 contextItems', () => {
  // GET /api/ai/history 回带的 attachments 行
  const rows = [
    { fileId: '9', name: '股权转让协议.docx', fileType: 'docx', kind: 'file', visionUsed: false },
    { fileId: '11', name: '现场照片.png', fileType: 'image', kind: 'image', visionUsed: true },
    { fileId: '100', name: '证据一', fileType: 'folder', kind: 'folder', visionUsed: false },
  ]
  const restored = { contextFiles: attachmentsFromHistory(rows) }
  assert.deepEqual(toContextItems(fileListFromBubble(restored)), [
    { id: '9', name: '股权转让协议.docx', isDir: false, fileType: 'docx' },
    { id: '11', name: '现场照片.png', isDir: false, fileType: 'image' },
    { id: '100', name: '证据一', isDir: true, fileType: 'folder' },
  ])
})

test('图片按 kind 走对分支：fileType=image 但文件名没后缀也认得出来', () => {
  const restored = { contextFiles: attachmentsFromHistory([
    { fileId: '11', name: '扫描件', fileType: 'image', kind: 'image', visionUsed: true },
  ]) }
  assert.equal(restored.contextFiles[0].kind, 'image')
  // 后端的双判据靠 fileType 那一半认出它——重建时丢了 fileType 就既不走视觉也不走 OCR
  assert.equal(toContextItems(fileListFromBubble(restored))[0].fileType, 'image')
})

test('没有附件的一问重建出空数组（不是 undefined，调用方可以直接展开）', () => {
  assert.deepEqual(fileListFromBubble({}), [])
  assert.deepEqual(fileListFromBubble(null), [])
  assert.deepEqual(fileListFromBubble({ contextFiles: [] }), [])
  assert.deepEqual(attachmentsFromHistory(undefined), [])
})

test('缺 id 的脏记录直接丢掉，不要造一个 id=undefined 的 contextItem', () => {
  // id 原样透传（记录里存的已经是 attachmentRecord 归一过的字符串），
  // 这里故意喂一个没归一过的数字 id，断言它不被二次加工
  assert.deepEqual(fileListFromBubble({ contextFiles: [{ name: 'x' }, { id: 1, name: 'y' }] }),
    [{ id: 1, fileName: 'y', fileType: '', wpsFileId: null, isDir: false }])
})

// ---------- 接线 ----------

test('live 与历史两条路都用同一个记录形状（形状分叉 = 刷新前能重新生成、刷新后不能）', () => {
  assert.ok(CI.includes('contextFiles.value.map(attachmentRecord)'), 'live 那条路')
  assert.ok(CI.includes('attachmentsFromHistory(msg.attachments)'), '历史那条路')
})

test('回退回填把那一轮的附件标签也还原回去', () => {
  // 取材料的那条断言与「重新生成」那一条共用同一处代码，详见下面那个测试；
  // 这里只钉「回退这条路真的把标签插回输入框」。
  assert.ok(CI.includes('restoreAttachmentsToInput(rolledBackAttachments)'))
  const body = CI.slice(CI.indexOf('const confirmRollback'), CI.indexOf('const clearAttachmentDraft'))
  assert.ok(body.indexOf('const rolledBackAttachments') < body.indexOf('rollbackToMessage(targetIndex)'),
    '取附件必须排在截断之前')
})

test('fileListFromBubble 从 setup 暴露出去，「重新生成」可以直接用', () => {
  assert.ok(/\n\s+fileListFromBubble,/.test(CI),
    'K11 的重发分支要用它重建 fileList，不暴露就只能继续传空数组')
})

test('带附件的一问点「重新生成」：重发的 fileList 就是原问那份，不是空数组', () => {
  // K11（dev-board#790）原来传 fileList: []——同一个问题重问一次、材料却没跟着走，
  // 模型当然给出不一样的答案，而用户以为这是「换一份回答」的正常波动。
  const body = CI.slice(CI.indexOf('const confirmRollback'), CI.indexOf('const clearAttachmentDraft'))
  assert.ok(!/fileList:\s*\[\]/.test(body), '重发分支里不许再出现 fileList: []')
  assert.ok(body.includes('fileList: rolledBackAttachments'), '重发要带原问那份材料')

  // 取材料必须在截断之前：rollbackToMessage 会把那条气泡摘掉
  assert.ok(body.includes('const rolledBackAttachments = fileListFromBubble(source)'))
  assert.ok(body.indexOf('const rolledBackAttachments') < body.indexOf('rollbackToMessage(targetIndex)'),
    '取晚了气泡已经没了，拿到的是空数组——正是原来那个 bug 的形态')

  // 回退才回填输入框；重新生成不碰输入框（用户此刻可能已经打了别的东西）
  assert.ok(body.includes('if (!resend) restoreAttachmentsToInput(rolledBackAttachments)'))
})

test('原问 → 气泡记录 → 重新生成，contextItems 三段逐字一致（端到端的那半是纯函数）', () => {
  const originalFileList = [
    { id: 9, fileName: '股权转让协议.docx', fileType: 'docx', wpsFileId: null, isDir: false },
    { id: 11, fileName: '现场照片.png', fileType: 'image', wpsFileId: null, isDir: false },
  ]
  // handleSubmit 挂到用户气泡上的那份完整记录
  const bubble = { contextFiles: originalFileList.map((f) =>
    attachmentRecord({ id: f.id, name: f.fileName, fileType: f.fileType, wpsFileId: f.wpsFileId, isDir: f.isDir })) }
  // confirmRollback 的重发分支交给 sendMessage 的 fileList
  assert.deepEqual(toContextItems(fileListFromBubble(bubble)), toContextItems(originalFileList))
})
