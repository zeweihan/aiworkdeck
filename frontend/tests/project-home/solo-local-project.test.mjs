// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 单人本机项目不显示「负责人：本机用户」与皇冠徽标（dev-board#1026 C22）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { isSoloLocalProject } from '../../src/utils/soloLocalProject.js'

test('local-mode 下只有本机用户一人：判为单人', () => {
  assert.equal(isSoloLocalProject({ localMode: true, managerId: 1, members: [{ userId: 1 }] }), true)
  assert.equal(isSoloLocalProject({ localMode: true, managerId: 1, members: [] }), true)
  assert.equal(isSoloLocalProject({ localMode: true, members: [{ userId: 1 }, { userId: 1 }] }), true)
})

test('多一个人（本机成员或案件库成员）就不是单人', () => {
  assert.equal(isSoloLocalProject({ localMode: true, managerId: 1, members: [{ userId: 2 }] }), false)
  // 云端成员 userId 被抹成 null，仍要算人头
  assert.equal(isSoloLocalProject({ localMode: true, managerId: 1, members: [{ userId: 1 }, { id: 'cloud-7', userId: null }] }), false)
})

test('非 local-mode 或尚未读到形态：一律照常显示', () => {
  assert.equal(isSoloLocalProject({ localMode: false, managerId: 1, members: [] }), false)
  assert.equal(isSoloLocalProject({ localMode: null, managerId: 1, members: [] }), false)
  assert.equal(isSoloLocalProject(), false)
})

test('工作台顶栏与项目列表两处都接了这条判定', () => {
  const po = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
  assert.match(po, /v-if="!soloLocalProject"[^>]*>\{\{ \$t\('workbench\.managerLabel'/)
  const pl = readFileSync(new URL('../../src/pages/project-list/project-list.vue', import.meta.url), 'utf8')
  const crowns = pl.match(/class="manager-avatar-wrapper" v-if="project\.managerId && !isSoloLocal\(project\)"/g) || []
  assert.equal(crowns.length, 2, '卡片视图与表格视图两处皇冠都要按单人本机项目隐藏')
})
