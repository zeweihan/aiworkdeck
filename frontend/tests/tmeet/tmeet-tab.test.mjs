// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  TMEET_TRANSCRIPT_TAB_TYPE,
  tmeetTabId,
  tmeetTabMethods
} from '../../src/pages/project-overview/tmeetTab.js'
import { serializeTab, tabKind } from '../../src/pages/project-overview/tabSnapshot.js'
import { fileKindKey } from '../../src/pages/project-overview/fileKind.js'

function createFakeVm(initial = {}) {
  return {
    leftFiles: initial.leftFiles || [],
    rightFiles: initial.rightFiles || [],
    activeFileIdLeft: initial.activeFileIdLeft || null,
    activeFileIdRight: initial.activeFileIdRight || null,
    splitMode: initial.splitMode || false,
    focusedPane: initial.focusedPane || 'left',
    $nextTick(cb) { if (cb) cb() },
    triggerWorkbenchResize() {},
    ...tmeetTabMethods
  }
}

test('openTmeetTranscriptTab: 新开腾讯会议逐字稿标签页', () => {
  const vm = createFakeVm()
  const meeting = {
    id: 101,
    subject: '股权激励宣讲会',
    meetingCode: '888-999-111'
  }

  vm.openTmeetTranscriptTab(meeting)

  assert.equal(vm.leftFiles.length, 1)
  const tab = vm.leftFiles[0]
  assert.equal(tab.id, 'tmeet-101')
  assert.equal(tab.tabType, TMEET_TRANSCRIPT_TAB_TYPE)
  assert.equal(tab.name, '股权激励宣讲会')
  assert.equal(tab.meetingSpec.meetingCode, '888-999-111')
  assert.equal(vm.activeFileIdLeft, 'tmeet-101')
})

test('openTmeetTranscriptTab: 已开标签再次打开时激活并不重复添加', () => {
  const meeting = {
    id: 101,
    subject: '股权激励宣讲会',
    meetingCode: '888-999-111'
  }
  const existingTab = {
    id: 'tmeet-101',
    tabType: TMEET_TRANSCRIPT_TAB_TYPE,
    name: '股权激励宣讲会',
    meetingSpec: meeting
  }
  const vm = createFakeVm({
    leftFiles: [existingTab],
    activeFileIdLeft: 'other-file'
  })

  vm.openTmeetTranscriptTab(meeting)

  assert.equal(vm.leftFiles.length, 1, '不应重复添加标签')
  assert.equal(vm.activeFileIdLeft, 'tmeet-101', '应激活已有标签')
})

test('tabSnapshot: 腾讯会议标签正确归类与序列化', () => {
  const meeting = {
    id: 101,
    subject: '股权激励宣讲会',
    meetingCode: '888-999-111'
  }
  const tab = {
    id: 'tmeet-101',
    tabType: 'tmeet-transcript',
    name: '股权激励宣讲会',
    meetingSpec: meeting
  }

  assert.equal(tabKind(tab), 'tmeet-transcript')
  const serialized = serializeTab(tab)
  assert.ok(serialized)
  assert.equal(serialized.id, 'tmeet-101')
  assert.equal(serialized.tabType, 'tmeet-transcript')
  assert.equal(serialized.name, '股权激励宣讲会')
  assert.equal(serialized.meetingSpec.id, 101)
})

test('fileKindKey: 腾讯会议逐字稿标签不着色', () => {
  assert.equal(fileKindKey(undefined, 'tmeet-transcript'), '')
})
