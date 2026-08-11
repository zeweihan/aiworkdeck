// 项目概览页纯展示逻辑的单测。零依赖：只用 node 内置 test runner，
// node_modules 未安装也能跑（本仓前端没有 vitest/jest）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { MATTER_TYPES } from '../../src/config/matterTypes.js'
import {
  formatDateTime, versionTitle, fileCountLabel, runStatusLabel, runStatusDotClass,
  isProfileEmpty, profileFieldHint, hasConversationPreview, canEditProfile,
} from '../../src/utils/projectHomeFormat.js'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')

test('MATTER_TYPES 与后端分类表逐字一致', () => {
  assert.equal(MATTER_TYPES.length, 11)
  assert.equal(MATTER_TYPES[0], '公司治理')
  const java = readFileSync(
    resolve(REPO, 'backend/src/main/java/com/checkba/service/telemetry/MatterClassifierService.java'),
    'utf8')
  const line = java.split('\n').find((l) => l.includes('可选类别：'))
  assert.ok(line, '后端 prompt 里找不到「可选类别：」')
  const backend = line.split('可选类别：')[1].replace(/。\s*$/, '').split('、')
  assert.deepEqual(backend, MATTER_TYPES)
})

test('formatDateTime 吃 LocalDateTime 串，坏值返回空串', () => {
  assert.equal(formatDateTime('2026-08-08T10:11:12'), '8 月 8 日 10:11')
  assert.equal(formatDateTime('2026-08-08T09:05:00'), '8 月 8 日 09:05')
  assert.equal(formatDateTime(''), '')
  assert.equal(formatDateTime(null), '')
  assert.equal(formatDateTime('not-a-date'), '')
  // VersionEntry.when 是 Instant，Spring Boot 默认序列化成带 Z 的 ISO 串。
  // 不断言具体值（会跟着 CI 机器时区飘），只断言能解析出东西。
  assert.notEqual(formatDateTime('2026-08-08T02:11:12Z'), '')
})

test('versionTitle 容纳 6 种文案形状且不动空白', () => {
  // 未命名工作的默认名带空格（WorkSessionService 的 TITLE_FMT "M 月 d 日"），压掉就成错别字
  assert.equal(versionTitle({ message: '8 月 8 日下午的工作' }), '8 月 8 日下午的工作')
  assert.equal(versionTitle({ message: '采纳：老王的稿' }), '采纳：老王的稿')
  assert.equal(versionTitle({ message: '退回到早先的版本' }), '退回到早先的版本')
  assert.equal(versionTitle({ message: '初始版本' }), '初始版本')
  assert.equal(versionTitle({ message: '取回最新稿' }), '取回最新稿')
  // note 优先于 message（与 VersionTimeline.vue:111-113 的 titleOf 同口径）
  assert.equal(versionTitle({ message: '8 月 8 日下午的工作', note: '尽调清单第一轮' }), '尽调清单第一轮')
  assert.equal(versionTitle(null), '')
})

test('fileCountLabel 对 localRoot 项目改口径', () => {
  assert.equal(fileCountLabel({ fileCount: 12, isLocalRoot: false }), '12 个文件')
  assert.equal(fileCountLabel({ fileCount: 12, isLocalRoot: true }), '已登记 12 项')
  assert.equal(fileCountLabel({}), '0 个文件')
  assert.equal(fileCountLabel(null), '0 个文件')
})

test('runStatusLabel / runStatusDotClass 覆盖 RunStatus 全部 8 个枚举值', () => {
  assert.equal(runStatusLabel('RUNNING'), '运行中')
  assert.equal(runStatusLabel('PAUSED'), '待继续')
  assert.equal(runStatusLabel('INTERRUPTED'), '已中断')
  assert.equal(runStatusLabel('AWAITING_APPROVAL'), '待审批')
  // AWAITING_INPUT 必须与 AWAITING_APPROVAL 分开：这是后端新增它的全部目的
  assert.equal(runStatusLabel('AWAITING_INPUT'), '待回答')
  assert.equal(runStatusLabel('ERROR'), '出错')
  assert.equal(runStatusLabel('FINISHED'), '')
  assert.equal(runStatusLabel('CANCELLED'), '')
  assert.equal(runStatusLabel(null), '')
  assert.equal(runStatusDotClass('RUNNING'), 'dot-running')
  assert.equal(runStatusDotClass('AWAITING_APPROVAL'), 'dot-attention')
  assert.equal(runStatusDotClass('AWAITING_INPUT'), 'dot-attention')
  assert.equal(runStatusDotClass('ERROR'), 'dot-error')
  assert.equal(runStatusDotClass('FINISHED'), '')
})

test('isProfileEmpty：openedAt 的 default 值不算有人填过', () => {
  const blank = [
    { fieldKey: 'client', fieldValue: null, source: null },
    { fieldKey: 'openedAt', fieldValue: '2026-08-01', source: 'default' },
    { fieldKey: 'nextStep', fieldValue: null, source: null },
  ]
  assert.equal(isProfileEmpty(blank), true)
  assert.equal(isProfileEmpty([...blank, { fieldKey: 'client', fieldValue: '某公司', source: 'user' }]), false)
  assert.equal(isProfileEmpty([{ fieldKey: 'client', fieldValue: '某公司', source: 'ai' }]), false)
  assert.equal(isProfileEmpty([]), true)
  assert.equal(isProfileEmpty(null), true)
})

test('profileFieldHint 弱化 default 与 ai', () => {
  assert.equal(profileFieldHint({ source: 'default' }), '取自建档时间')
  assert.equal(profileFieldHint({ source: 'ai' }), 'AI 读文件得出，请核对')
  assert.equal(profileFieldHint({ source: 'user' }), '')
  assert.equal(profileFieldHint(null), '')
})

test('hasConversationPreview 对空预览兜底（extractPreview 会返回空串）', () => {
  assert.equal(hasConversationPreview({ lastMessage: '已核对通知与决议的届次' }), true)
  assert.equal(hasConversationPreview({ lastMessage: '' }), false)
  assert.equal(hasConversationPreview({ lastMessage: '   ' }), false)
  assert.equal(hasConversationPreview({}), false)
  assert.equal(hasConversationPreview(null), false)
})

test('canEditProfile 与后端 hasWritePermission 放行集合一致', () => {
  for (const r of ['OWNER', 'MANAGER', 'ADMIN', 'PARTICIPANT']) assert.equal(canEditProfile(r), true)
  for (const r of ['READ_ONLY', 'CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC', null, undefined, ''])
    assert.equal(canEditProfile(r), false)
})
