// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#793 K14 / #801 K21：上下文层的判据（当前文档 chip、附件跨轮、上限前置拦截、降级常驻提示）。
//
// 这一层全是「看得见、摘得掉、拦得住」的判据，放在纯模块里跑 node:test；
// 接线本身另有 source 断言（见文件末尾那一组），因为判据对了而没接上去是最典型的静默失效。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  DEFAULT_CONTEXT_LIMITS,
  normalizeContextLimits,
  isImageAttachment,
  visionNoticeKey,
  contextQuotaState,
  admitFileToContext,
  admitPastedImage,
} from '../../src/utils/chatContextLimits.js'

const CI = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')
const UAS = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

// ---------- 上限的单一事实来源 ----------

test('拉不到 /api/ai/config 时用与后端一致的兜底值，不是写死 0 或 Infinity', () => {
  assert.equal(DEFAULT_CONTEXT_LIMITS.maxFilesPerContext, 10)
  assert.equal(DEFAULT_CONTEXT_LIMITS.maxImagesPerTurn, 4)
  assert.equal(DEFAULT_CONTEXT_LIMITS.maxImageBytes, 10 * 1024 * 1024)
})

test('服务端下发的值覆盖兜底值', () => {
  const limits = normalizeContextLimits({ maxFilesPerContext: 3, maxImagesPerTurn: 1, maxImageBytes: 999 })
  assert.equal(limits.maxFilesPerContext, 3)
  assert.equal(limits.maxImagesPerTurn, 1)
  assert.equal(limits.maxImageBytes, 999)
})

test('下发了垃圾值（0/负数/字符串/缺字段）一律退回兜底，绝不拿 0 去拦截', () => {
  for (const bad of [null, undefined, {}, { maxFilesPerContext: 0 }, { maxFilesPerContext: -1 },
    { maxFilesPerContext: 'many' }, { maxFilesPerContext: NaN }]) {
    assert.equal(normalizeContextLimits(bad).maxFilesPerContext, 10,
      `${JSON.stringify(bad)} 不该把上限变成 0——那会让用户一份文件都加不进来`)
  }
})

// ---------- 图片判据（与插件 isImageAttachment 同口径） ----------

test('图片判据取 fileType 与文件名后缀的并集', () => {
  assert.equal(isImageAttachment({ name: '现场.png' }), true)
  assert.equal(isImageAttachment({ name: '现场', fileType: 'image' }), true)
  // 桌面端那张扩展名映射表没有 bmp，项目树里是 'other'——只看 fileType 会漏
  assert.equal(isImageAttachment({ name: '扫描.bmp', fileType: 'other' }), true)
  assert.equal(isImageAttachment({ name: '合同.docx' }), false)
  assert.equal(isImageAttachment({ name: '合同.pdf' }), false, 'PDF 刻意不算图片（不能直送）')
  assert.equal(isImageAttachment(null), false)
})

// ---------- E-6：降级提示必须常驻且覆盖拖进来的项目图片 ----------

test('模型读不了图 + 附件里有图 → 常驻提示（不管图是粘的还是拖的）', () => {
  assert.equal(visionNoticeKey({ modelVision: false, pastedImages: [{}], contextFiles: [] }),
    'chat.imageOcrFallbackNote')
  assert.equal(visionNoticeKey({ modelVision: false, pastedImages: [], contextFiles: [{ name: '现场.png' }] }),
    'chat.imageOcrFallbackNote',
    '拖进来的项目图片走 contextFiles，原来那条提示嵌在 pastedImages 块里，这条路零提示')
})

test('能力未知（null）一律不提示——拉不到模型目录时对所有模型误报是造谣', () => {
  assert.equal(visionNoticeKey({ modelVision: null, pastedImages: [{}], contextFiles: [] }), '')
  assert.equal(visionNoticeKey({ modelVision: undefined, pastedImages: [{}], contextFiles: [] }), '')
})

test('模型能读图就不提示；没有任何图片时也不提示', () => {
  assert.equal(visionNoticeKey({ modelVision: true, pastedImages: [{}], contextFiles: [] }), '')
  assert.equal(visionNoticeKey({ modelVision: false, pastedImages: [], contextFiles: [{ name: 'a.docx' }] }), '')
})

// ---------- E-14 + 配额口径统一 ----------

test('配额把文件与图片算在一起（后端口径：直送的图也占 maxFilesPerContext）', () => {
  const limits = normalizeContextLimits({ maxFilesPerContext: 10 })
  const state = contextQuotaState({
    contextFiles: [{ id: 1 }, { id: 2 }],
    pastedImages: [{}, {}],
    limits,
  })
  assert.equal(state.used, 4)
  assert.equal(state.max, 10)
  assert.equal(state.atCap, false)
})

test('到顶就是到顶', () => {
  const limits = normalizeContextLimits({ maxFilesPerContext: 2 })
  const state = contextQuotaState({ contextFiles: [{ id: 1 }, { id: 2 }], pastedImages: [], limits })
  assert.equal(state.atCap, true)
})

test('addFile 到顶时拒绝并给出可行动的原因，不是静默砍尾', () => {
  const limits = normalizeContextLimits({ maxFilesPerContext: 2 })
  const ok = admitFileToContext({ file: { id: 3 }, contextFiles: [{ id: 1 }], pastedImages: [], limits })
  assert.equal(ok.ok, true)

  const full = admitFileToContext({
    file: { id: 3 }, contextFiles: [{ id: 1 }, { id: 2 }], pastedImages: [], limits,
  })
  assert.equal(full.ok, false)
  assert.equal(full.reason, 'cap')
  assert.equal(full.max, 2, '提示里要写清上限是几，用户才知道该删掉几份')
})

test('重复添加同一个文件不占额度也不报「到顶」', () => {
  const limits = normalizeContextLimits({ maxFilesPerContext: 1 })
  const again = admitFileToContext({ file: { id: 1 }, contextFiles: [{ id: 1 }], pastedImages: [], limits })
  assert.equal(again.ok, false)
  assert.equal(again.reason, 'duplicate')
})

test('贴第 N+1 张图就地拒绝，理由是「张数」而不是「文件数」', () => {
  const limits = normalizeContextLimits({ maxImagesPerTurn: 2, maxFilesPerContext: 10 })
  assert.equal(admitPastedImage({ size: 10, pastedImages: [{}], contextFiles: [], limits }).ok, true)
  const over = admitPastedImage({ size: 10, pastedImages: [{}, {}], contextFiles: [], limits })
  assert.equal(over.ok, false)
  assert.equal(over.reason, 'imageCount')
  assert.equal(over.max, 2)
})

test('超大图就地拒绝，理由是体积', () => {
  const limits = normalizeContextLimits({ maxImageBytes: 100 })
  const over = admitPastedImage({ size: 500, pastedImages: [], contextFiles: [], limits })
  assert.equal(over.ok, false)
  assert.equal(over.reason, 'imageBytes')
  assert.equal(over.max, 100)
})

test('拿不到文件体积时不拦（size 未知不等于超限）', () => {
  const limits = normalizeContextLimits({ maxImageBytes: 100 })
  assert.equal(admitPastedImage({ size: undefined, pastedImages: [], contextFiles: [], limits }).ok, true)
  assert.equal(admitPastedImage({ size: 0, pastedImages: [], contextFiles: [], limits }).ok, true)
})

// ---------- 接线：判据对了但没接上去是最典型的静默失效 ----------

test('E-4 互斥已去除：activeContext 不再看有没有附件', () => {
  // 只看赋值形态：那串条件在注释里作为「原来是这么写的」留着，是有用的病灶记录
  assert.ok(!/const\s+activeContext\s*=\s*\(?\s*!hasFiles/.test(CI),
    '这一条把最常见的跨材料工作流整条切断：挂了附件 → 活跃文档整段消失')
  assert.ok(/const\s+activeContext\s*=\s*chipTab\s*\?/.test(CI),
    'activeContext 改由 chip 决定：chip 在就带、被摘掉就不带')
})

test('当前文档 chip 用 isContextEligibleTab 判可见性，且带摘除入口', () => {
  assert.ok(CI.includes('isContextEligibleTab'), 'chip 的合格性判据必须复用 #914 的那一份')
  assert.ok(CI.includes('activeDocDismissed'), '摘除状态')
  assert.ok(CI.includes('chat.activeDocChipTitle'), 'chip 文案走 i18n')
})

test('附件跨轮保留：发送后不再把 contextFiles 过滤掉，改成淡态', () => {
  assert.ok(!/contextFiles\.value\s*=\s*contextFiles\.value\.filter\(\(file\)\s*=>\s*!contextFilesToShow/.test(CI),
    '原来收到 receipt 就把本轮附件从草稿里删了，第二轮追问模型手上一个字都没有')
  assert.ok(CI.includes('is-carried'), '淡态样式类')
  assert.ok(CI.includes('carriedFileIds'), '哪些是上一轮带过的')
})

test('重新挂回附件这件事不许挂在 shouldClearChatDraft 分支里（真机实测的两个坑）', () => {
  // ① 首条消息发出去时输入卡片整块被 v-if 换掉（空状态 ⇄ 有对话），editorHtml 已经是空串、
  //    指纹必然不匹配，挂在分支里的话第一条之后附件全没了、第二条之后才正常；
  // ② 换掉之后必须等 nextTick 再插标签，否则写进的是马上被销毁的那个 div。
  const submit = CI.slice(CI.indexOf('const handleSubmit'), CI.indexOf('const handleAbort'))
  const branch = submit.slice(submit.indexOf('if (draftUnchanged) {'))
  const call = branch.indexOf('await restoreCarriedTags(')
  const close = branch.indexOf('\n      }')
  assert.ok(call > 0, '发送后必须调 restoreCarriedTags')
  assert.ok(close > 0 && call > close, 'restoreCarriedTags 必须在「草稿没变」那个 if 块之外')

  const fn = CI.slice(CI.indexOf('const restoreCarriedTags'), CI.indexOf('const confirmCarriedFile'))
  assert.ok(fn.indexOf('await nextTick()') < fn.indexOf('insertContextTagToInput'),
    '插标签之前必须等重渲染落地')
  assert.ok(fn.includes('present.get'), '只补缺的、不整段重建——用户可能已经打了新的字')
})

// BUG-19（v0.49.0 真机测试 C5-04）：用户看不懂虚线标签是什么。跨轮携带是 K14 ③ 的刻意设计，
// 修法是把可发现性做足（常驻一句提示 + × 常显 + title），而不是发后即清。
test('上轮附件的虚线标签：常驻提示、× 常显、× 能移除', () => {
  const zh = readFileSync(new URL('../../src/locales/zh-CN/chat.js', import.meta.url), 'utf8')
  const en = readFileSync(new URL('../../src/locales/en-US/chat.js', import.meta.url), 'utf8')
  assert.match(zh, /carriedAttachmentHint: '[^']*上轮附件[^']*下一条继续使用[^']*点 × 移除/)
  assert.match(en, /carriedAttachmentHint: '[^']*Click x to remove/)
  assert.equal((CI.match(/v-if="carriedHintVisible" class="carried-attachment-hint">\{\{ \$t\('chat\.carriedAttachmentHint'\) \}\}/g) || []).length, 2,
    '空态与常态两张输入卡片各挂一份提示')
  const hint = CI.slice(CI.indexOf('const carriedHintVisible'), CI.indexOf('const carriedHintVisible') + 200)
  assert.ok(hint.includes('carriedFileIds.value') && hint.includes('contextFiles.value'),
    '提示只在输入框里还挂着上轮附件时出现，移除后自然消失')
  assert.ok(/\.context-tag-inline\.is-carried \.tag-close\)\s*\{\s*display: flex;/.test(CI), '虚线标签的 × 不等悬停就显示')
  assert.ok(CI.includes("t('chat.carriedAttachmentTitle'"), '虚线标签带 title 说明')

  // × 的点击分支必须排在「点本体确认沿用」之前并且 return，否则点 × 只会把淡态摘掉、附件还在
  const click = CI.slice(CI.indexOf('const handleInputClick'), CI.indexOf('const syncContextFilesWithInlineTags'))
  const close = click.indexOf("classList.contains('tag-close')")
  assert.ok(close > 0 && close < click.indexOf('confirmCarriedFile('), '× 分支先于确认沿用')
  const closeBranch = click.slice(close, click.indexOf('confirmCarriedFile('))
  for (const step of ['tag.remove()', 'carriedFileIds.value = carriedFileIds.value.filter', 'syncContextFilesWithInlineTags()', 'return'])
    assert.ok(closeBranch.includes(step), '× 分支缺少：' + step)
})

test('附件跨轮保留只在同一段对话里成立：换会话/换项目必须清干净', () => {
  // 不清的话上一段对话挂着的材料会跟进下一段；换项目更糟——那个 fileId 属于别的项目，
  // 后端 ToolFileGuard 会拒，用户看到「该附件内容暂不可读」而不知道自己带了这份东西。
  assert.ok(CI.includes('const clearAttachmentDraft'), '要有一个统一的清理口')
  const newChat = CI.slice(CI.indexOf('const startNewChat'), CI.indexOf('const handleSubmit'))
  assert.ok(newChat.includes('clearAttachmentDraft()'), '新对话')
  const load = CI.slice(CI.indexOf('const loadMessages'), CI.indexOf('const recentDotClass'))
  assert.ok(load.includes('clearAttachmentDraft()'), '切到历史会话')
  assert.ok(/watch\(\(\)\s*=>\s*props\.projectId,\s*\(\)\s*=>\s*\{\s*clearAttachmentDraft\(\)/.test(CI), '换项目')
})

test('context_notice 前端认得，且挂在用户气泡上（历史回灌不重放）', () => {
  assert.ok(UAS.includes("evt === 'context_notice'"), 'SSE 事件要有消费者')
  assert.ok(UAS.includes('contextNotices'), '落到气泡字段上，渲染才是响应式的')
  assert.ok(CI.includes('contextNotices'), '气泡下要真的渲染出来')
})

test('createUserBubble 预声明 contextNotices（运行时才挂的字段 Vue 追踪不到）', () => {
  const block = UAS.slice(UAS.indexOf('const createUserBubble'), UAS.indexOf('const resetInboxState'))
  assert.ok(/contextNotices\s*:\s*\[\]/.test(block))
})
