// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#814「音频附件可行动提示 + 转写稿关联」（审查 E-12）。
//
// 把一段庭审录音拖进对话框，AI 只会收到一句「读不出来，试试 OCR」——而这个产品自己
// 就带着会议录音与听悟转写。后端那半在 ProjectFileTextExtractor（已转写的自动注入转写稿，
// 没转写的给一句可行动的下一步）；前端这半要在发送之前就告诉用户「AI 只能看到文件名」，
// 并给一个当场能点的「转写」。
//
// 这里钉四件事：
// ① 扩展名表与后端 MeetingRecordingService.AUDIO_EXTENSIONS 逐项一致（前端多判/漏判都不报错）；
// ② 只有「没有转写稿」的音频才提示——有转写稿的 AI 真读得到，提示会变成谎话；
// ③ 判据认的是 TRANSCRIBED，转写中/失败/空结果都还要提示；
// ④ 文案键两套 locale 齐备，且 FileTree 与 ChatInterface 用的是同一张表（不许再各写一份）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  AUDIO_EXTENSIONS,
  audioNeedingTranscription,
  isAudioFile,
  transcribedAudioFileIds,
} from '../../src/utils/audioAttachment.js'

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8')

test('音频扩展名表与后端 MeetingRecordingService 逐项一致', () => {
  const java = read('../../../backend/src/main/java/com/checkba/service/meeting/MeetingRecordingService.java')
  const block = java.match(/AUDIO_EXTENSIONS\s*=\s*Set\.of\(([\s\S]*?)\);/)
  assert.ok(block, '后端的 AUDIO_EXTENSIONS 常量没找到——表被改名或改形状了，对拍失效')
  const backend = [...block[1].matchAll(/"([a-z0-9]+)"/g)].map((m) => m[1]).sort()
  assert.deepEqual([...AUDIO_EXTENSIONS].sort(), backend,
    '前后端音频表漂移：前端漏判 = 用户看不到「转写」入口；前端多判 = 点了转写后端拒收')
})

test('判据认扩展名，文件夹与非音频一律为假', () => {
  assert.equal(isAudioFile({ name: '开庭录音.mp3' }), true)
  assert.equal(isAudioFile({ name: '会见录音.M4A' }), true, '扩展名大小写不敏感')
  assert.equal(isAudioFile({ name: '录音', fileType: 'wav' }), true, '没有扩展名时退回 fileType')
  assert.equal(isAudioFile({ name: '股权转让协议.docx' }), false)
  assert.equal(isAudioFile({ name: '录音.mp3', isFolder: true }), false, '文件夹可以叫这个名字')
  assert.equal(isAudioFile({ name: '录音.mp3', isDir: true }), false, '附件那边用的是 isDir')
  assert.equal(isAudioFile(null), false)
  // 「多判」的方向同样要守：一个被标成 wav 的 docx 不是音频
  assert.equal(isAudioFile({ name: '笔录.docx', fileType: 'wav' }), false)
})

test('只有没有转写稿的音频才提示', () => {
  const files = [
    { id: 1, name: '开庭录音.mp3' },
    { id: 2, name: '股权转让协议.docx' },
    { id: 3, name: '会见录音.m4a' },
  ]
  const done = transcribedAudioFileIds([
    { audioFileId: 3, status: 'TRANSCRIBED' },
  ])

  const pending = audioNeedingTranscription(files, done)

  assert.deepEqual(pending.map((f) => f.id), [1], '有转写稿的不该再提示——AI 真读得到')
  assert.deepEqual(audioNeedingTranscription([], done), [])
  assert.deepEqual(audioNeedingTranscription(files, new Set()).map((f) => f.id), [1, 3])
})

test('id 跨 HTTP 往返后类型会变，比对必须按字符串', () => {
  const done = transcribedAudioFileIds([{ audioFileId: 7, status: 'TRANSCRIBED' }])
  assert.deepEqual(audioNeedingTranscription([{ id: '7', name: 'a.mp3' }], done), [],
    '附件 id 是字符串、会议记录里是数字，按 === 比会永远不命中')
})

test('只有 TRANSCRIBED 算已转写：转写中/失败/无人声照样提示', () => {
  for (const status of ['TRANSCRIBING', 'FAILED', 'EMPTY', 'RECORDED', 'RECORDING']) {
    const done = transcribedAudioFileIds([{ audioFileId: 5, status }])
    assert.deepEqual(audioNeedingTranscription([{ id: 5, name: '录音.mp3' }], done).length, 1,
      `${status} 的记录同样挂着 audioFileId，算成已转写会让提示消失而 AI 照样读不到`)
  }
})

test('FileTree 与 ChatInterface 共用同一张表，locale 两套齐备', () => {
  const tree = read('../../src/components/FileTree.vue')
  const chat = read('../../src/components/ChatInterface.vue')
  for (const [name, src] of [['FileTree.vue', tree], ['ChatInterface.vue', chat]]) {
    assert.ok(/from ['"]@?[./]*[^'"]*utils\/audioAttachment(\.js)?['"]/.test(src),
      `${name} 必须从 utils/audioAttachment 取判据，不许再内联一份扩展名数组`)
  }
  assert.ok(!/'mp3',\s*'m4a'/.test(tree), 'FileTree 里那份内联副本应当已经删掉')

  const zh = read('../../src/locales/zh-CN/chat.js')
  const en = read('../../src/locales/en-US/chat.js')
  for (const key of ['audioNotTranscribed', 'audioNotTranscribedMore', 'audioTranscribeAction']) {
    assert.ok(zh.includes(`${key}:`), `zh-CN 缺 chat.${key}`)
    assert.ok(en.includes(`${key}:`), `en-US 缺 chat.${key}`)
  }
  assert.ok(/\$t\('chat\.audioTranscribeAction'\)/.test(chat), '「转写」按钮文案必须走 i18n')
  assert.ok(/emit\('transcribe-audio'/.test(chat),
    '「转写」要复用文件树右键那条动作（project-overview 的 onTranscribeAudio），不另起一套')
})

// 载荷字段名选错了不会报错——列表恒空、提示恒出现，看着「功能正常」，
// 而已经转写好的音频照样被说成「尚未转写」。所以这条按后端控制器的真实形状钉。
test('已转写集合读的是 GET /api/meetings 的 meetings 字段', () => {
  const chat = read('../../src/components/ChatInterface.vue')
  assert.ok(/transcribedAudioFileIds\(res && res\.meetings\)/.test(chat),
    'MeetingRecordingController.list 回的是 { meetings, configured }，不是裸数组、也不是 { data }')

  const controller = read('../../../backend/src/main/java/com/checkba/controller/MeetingRecordingController.java')
  assert.ok(/result\.put\("meetings", meetings\)/.test(controller),
    '后端改了字段名，前端这一侧要跟着改')
})

test('project-overview 真的接上了 ChatInterface 的 transcribe-audio', () => {
  const page = read('../../src/pages/project-overview/project-overview.vue')
  const tag = page.slice(page.indexOf('<ChatInterface'))
  const binding = tag.slice(0, tag.indexOf('/>'))
  assert.ok(/@transcribe-audio="onTranscribeAudio"/.test(binding),
    'emit 了没人听 = 按钮点下去什么都不发生，而且 check:emits 查不出这个方向')
})
