// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#972（v0.49.0 BUG-76 / C9-07）：图片 / SVG 预览底部的缩放工具条
// （− 100% + 1:1 适应窗口 旋转）下半截被底部状态栏压住。
//   cd frontend && node --test tests/project-home/image-preview-toolbar-inset.test.mjs
//
// 根因不在状态栏：FilePreview.vue 的 .preview-image 写的是 height:100% + padding:24rpx
// （=12px），uni 的 <view> 是 content-box（uni-h5 只给 uni-page* 设了 border-box），
// 于是它比 .preview-body 高出上下两份 padding（24px）。工具条 position:absolute;
// bottom:24rpx 是相对这个撑高的盒子定位的，结果落在 .preview-body 下沿之下 12px——
// 正是真机量到的 bottom 1026 vs 状态栏 top 1014。无头 Chromium 按同一套规则复算：
// content-box 时工具条 bottom = body 下沿 + 12，border-box 时 = body 下沿 - 12。
// 同时 applyImageView 用 el.clientHeight 算「适应窗口」，撑高的盒子也让居中偏下。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/FilePreview.vue', import.meta.url), 'utf8')
const STYLE = SRC.slice(SRC.indexOf('<style'))

function block(selector) {
  const re = new RegExp(`\\n${selector.replace(/[.]/g, '\\.')}\\s*\\{([^}]*)\\}`)
  const m = STYLE.match(re)
  assert.ok(m, `找不到 ${selector} 规则`)
  return m[1]
}

test('.preview-image 是 height:100% 带 padding 的视口，必须 border-box，否则撑出 .preview-body', () => {
  const css = block('.preview-image')
  assert.match(css, /height:\s*100%;/)
  assert.match(css, /padding:\s*24rpx;/)
  assert.match(css, /box-sizing:\s*border-box;/)
})

test('工具条仍然贴视口底部定位（修法是把视口收回容器内，不是给工具条加 magic 偏移）', () => {
  const css = block('.image-toolbar')
  assert.match(css, /position:\s*absolute;/)
  assert.match(css, /bottom:\s*24rpx;/)
})
