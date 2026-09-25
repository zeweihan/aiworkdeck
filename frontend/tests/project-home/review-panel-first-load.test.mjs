// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 真机 BUG-39（C5-07）：审阅面板每次打开先闪「没有待处理的修订 / 修订 0」，
// 再跳成真实数量。
//
// 病灶：面板是 v-if 挂载的，每次打开都从 revisions: [] 起步，第一次 list_revisions
// 回来之前模板就按「清单为空」渲染了空态文案和 0 计数。
// 修法：第一次读回来之前是「加载中」——空态文案与标签计数都要等第一次读成功。
// 读失败不算读过（dev-board#460 的纪律：读失败不许把计数说成 0）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { makeReviewVm } from '../_lib/review-panel-vm.mjs'

const SRC = readFileSync(new URL('../../src/components/ReviewPanel.vue', import.meta.url), 'utf8')
const TEMPLATE = SRC.slice(SRC.indexOf('<template>'), SRC.lastIndexOf('</template>'))

function executor(ok) {
  return {
    async executeCommand(action) {
      await Promise.resolve()
      if (!ok) return { success: false, message: 'timeout' }
      if (action === 'list_revisions') return { success: true, revisions: [] }
      if (action === 'list_comments') return { success: true, comments: [] }
      return { success: true }
    },
  }
}

test('第一次读回来之前处于加载态；读成功后才算读过', async () => {
  const vm = makeReviewVm(executor(true))
  assert.equal(vm.revLoaded, false, '刚挂上还没读，不能当成「读过了、是空的」')
  assert.equal(vm.cmtLoaded, false)
  await vm.reload()
  assert.equal(vm.revLoaded, true)
  assert.equal(vm.cmtLoaded, true)
})

test('读失败不算读过：不许因此落到「没有待处理的修订」', async () => {
  const vm = makeReviewVm(executor(false))
  await vm.reload()
  assert.equal(vm.revLoaded, false)
  assert.equal(vm.cmtLoaded, false)
})

test('模板：修订/批注空态与标签计数都挂在「读过」之后，读之前显示加载中', () => {
  assert.match(TEMPLATE,
    /<view v-if="!revLoaded" class="rp-empty">\s*<text class="rp-empty-t">\{\{ \$t\('editor\.review\.loading'\) \}\}<\/text>\s*<\/view>\s*<view v-else-if="!revisions\.length" class="rp-empty">/,
    '修订空态没有等第一次读回来')
  assert.match(TEMPLATE,
    /<view v-if="!cmtLoaded" class="rp-empty">\s*<text class="rp-empty-t">\{\{ \$t\('editor\.review\.loading'\) \}\}<\/text>\s*<\/view>\s*<view v-else-if="!comments\.length" class="rp-empty">/,
    '批注空态没有等第一次读回来')
  assert.match(TEMPLATE, /<text v-if="revLoaded" class="rp-tab-n">\{\{ allGroups\.length \}\}<\/text>/,
    '修订标签计数在读回来之前就显示 0')
  assert.match(TEMPLATE, /<text v-if="cmtLoaded" class="rp-tab-n">\{\{ comments\.length \}\}<\/text>/,
    '批注标签计数在读回来之前就显示 0')
})

test('zh-CN / en-US 都有加载态文案', async () => {
  const zh = (await import('../../src/locales/zh-CN/editor.js')).default
  const en = (await import('../../src/locales/en-US/editor.js')).default
  assert.ok(zh.review.loading)
  assert.ok(en.review.loading)
})
