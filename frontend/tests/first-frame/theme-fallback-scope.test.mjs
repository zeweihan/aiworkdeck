// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// 守 frontend/index.html 的首屏背景兜底（dev-board#731）。
//
// 这条护栏是从一次真回归里长出来的，不是假想：
// 配色换代时为了消除首屏白闪，给 index.html 加了 html/body 的背景色，
// 深色分支写成了裸的 @media (prefers-color-scheme: dark)。结果是
// 「系统深色 + 应用设为浅色」的用户整个外壳被按成深色——正是 CLAUDE.md
// 里已被否决的深色 chrome。真渲染走查时 htmlBg 实测出 rgb(25,23,19) 才抓到。
//
// 不变式：跟随系统的兜底只在 data-theme 还没挂上去（Vue 挂载前第一帧）时生效；
// 挂上之后一律听 utils/appTheme.js 挂的 data-theme。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))
const INDEX = readFileSync(path.join(here, '../../index.html'), 'utf8')

// 只看 <style> 块，避免把 <meta name="theme-color" media="..."> 误判进来
// （那两行是给浏览器 UI 染色的，本来就该跟随系统，不受本护栏约束）。
const STYLE = (INDEX.match(/<style>([\s\S]*?)<\/style>/) || [, ''])[1]

test('首屏兜底：深色分支必须限定在 data-theme 未挂载时', () => {
  const darkBlock = STYLE.match(/@media\s*\(prefers-color-scheme:\s*dark\)\s*\{([\s\S]*?)\n\s*\}/)
  assert.ok(darkBlock, 'index.html 的 <style> 里找不到 prefers-color-scheme: dark 兜底块')

  const body = darkBlock[1]
  assert.ok(
    body.includes(':not([data-theme])'),
    '深色兜底块缺少 :not([data-theme]) 限定。裸的媒体查询会盖过应用自己的 data-theme，'
      + '让系统深色的用户被永久按成深色外壳，破掉「外壳保持浅色」红线。'
  )

  // 块里出现的每一条选择器都必须带限定，漏一条就等于没限定
  for (const sel of body.split('{')[0].split(',')) {
    const s = sel.trim()
    if (!s) continue
    assert.ok(
      s.includes(':not([data-theme])'),
      `深色兜底块里的选择器 "${s}" 没有 :not([data-theme]) 限定`
    )
  }
})

test('首屏兜底：浅色默认值走令牌，挂载后由 data-theme 决定', () => {
  assert.match(
    STYLE,
    /html,\s*body\s*\{\s*background:\s*var\(--awd-bg,\s*#F1EFE7\)/,
    'html/body 的背景应写成 var(--awd-bg, #F1EFE7)：挂载前用字面量兜底，挂载后跟随令牌。'
      + '写死字面量会让浅/深主题切换对 html 背景失效。'
  )
})

test('首屏兜底的字面量必须与色源一致', () => {
  const palette = JSON.parse(
    readFileSync(path.join(here, '../../../design/tokens/awd-palette.json'), 'utf8')
  )
  const lightBg = palette.roles.light.bg.toUpperCase()
  const darkBg = palette.roles.dark.bg.toUpperCase()

  assert.ok(
    STYLE.toUpperCase().includes(lightBg),
    `首屏浅色兜底值与色源 roles.light.bg (${lightBg}) 不一致`
  )
  assert.ok(
    STYLE.toUpperCase().includes(darkBg),
    `首屏深色兜底值与色源 roles.dark.bg (${darkBg}) 不一致`
  )
})
