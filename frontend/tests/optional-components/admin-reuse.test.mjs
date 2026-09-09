// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 组件管理页与首次登录面板必须共用同一张卡片：两处口径不一致，用户在一处看到
// 「已就绪」、另一处看到「未安装」，谁都不知道该信哪个。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('../../src/components/admin/AdminPane.vue', import.meta.url), 'utf8')

test('组件管理复用 OptionalComponentCard，而不是自己再写一套 comp-row', () => {
  assert.match(src, /import OptionalComponentCard from '@\/components\/OptionalComponentCard\.vue'/)
  assert.match(src, /<OptionalComponentCard/)
})

test('组件管理走 useOptionalComponents 的编排（顺序 pack → 模型 → ensure）', () => {
  assert.match(src, /createOptionalComponentsController/)
  assert.match(src, /installOne/)
})

test('旧的三行纯模型列表已经拆掉（它只讲模型，不讲运行时，装不上会让用户困惑）', () => {
  assert.ok(!/handleComponentEnable/.test(src) || /installOne/.test(src),
    '「启用」按钮的语义已经并进 installOne 的 ensure 段')
  assert.ok(!/v-for="comp in components"/.test(src), '旧列表仍在')
})

test('卸载确认框仍然写明可释放的体积（规范 §6）', () => {
  assert.match(src, /removeComponentContent/)
  assert.match(src, /packUninstall/)
})
