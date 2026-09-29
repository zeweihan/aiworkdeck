// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1050：尽调清单入口只对「已放进案件库」的案卷恢复给律师，本机项目维持 08-19 的隐藏，
// CLIENT 仍只见尽调清单。leftSidebarPlugins.js 引了 '@/i18n'，node 解析不了别名——剥掉 import、
// 注入一个 t 桩再求值（桩法同 agent-editor-booting-wait）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolveTrack, TRACK } from '../../src/utils/memberLookup.js'

const SRC = readFileSync(new URL('../../src/config/leftSidebarPlugins.js', import.meta.url), 'utf8')
const OVERVIEW = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')

function load() {
  const body = SRC.replace(/^import .*$/gm, '').replace(/^export /gm, '')
    + '\nreturn { getPluginsForUser, LEFT_SIDEBAR_PLUGINS, DD_FILES_PLUGIN }'
  return new Function('t', body)((k) => k)
}
const { getPluginsForUser } = load()
const keys = (list) => list.map((p) => p.key)
const cloudTrack = (localMode, linked) => resolveTrack({ localMode, linked }) === TRACK.CLOUD

test('云端轨道 + 律师：rail 含 dd-files（排在静态项之后）', () => {
  const list = keys(getPluginsForUser('ADMIN', { cloudTrack: cloudTrack(true, true) }))
  assert.ok(list.includes('dd-files'))
  assert.equal(list[list.length - 1], 'dd-files')
  assert.equal(list[0], 'home')
})

test('本机项目 + 律师：不含 dd-files（08-19 的隐藏维持）', () => {
  assert.ok(!keys(getPluginsForUser('ADMIN', { cloudTrack: cloudTrack(true, false) })).includes('dd-files'))
  assert.ok(!keys(getPluginsForUser('PARTICIPANT')).includes('dd-files'))
  // 自建多用户服务器（localMode=false）不是云端轨道，即便 linked 也不给
  assert.ok(!keys(getPluginsForUser('ADMIN', { cloudTrack: cloudTrack(false, true) })).includes('dd-files'))
})

test('CLIENT：只含 dd-files，与云端轨道无关', () => {
  assert.deepEqual(keys(getPluginsForUser('CLIENT')), ['dd-files'])
  assert.deepEqual(keys(getPluginsForUser('CLIENT', { cloudTrack: true })), ['dd-files'])
})

test('工作台按云端轨道判据喂 getPluginsForUser，且断开后回落资源管理器', () => {
  assert.match(OVERVIEW, /getPluginsForUser\(user && user\.role, \{ cloudTrack: this\.ddCloudTrack \}\)/)
  assert.match(OVERVIEW, /resolveTrack\(\{ localMode: this\.localMode, linked: this\.collabLinked \}\) === TRACK\.CLOUD/)
  assert.match(OVERVIEW, /ddCloudTrack\(on\) \{\s*if \(!on && this\.leftPaneKey === 'dd-files'/)
})
