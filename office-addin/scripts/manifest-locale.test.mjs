// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * manifest.xml 的 en-US 本地化守卫（dev-board#696）。
 *
 * 为什么要这条测试：AppSource 的商店列表语言必须是清单声明支持的 locale
 * （提交指南第 6 步：「These must also be languages and locales that are supported
 * in your app manifest」）。add-in only 清单里「声明支持 en-US」的唯一方式就是
 * 给可本地化元素挂 en-US Override——漏掉任意一条，Partner Center 就不让开英文
 * 列表，或者开了之后功能区/GetStarted 在英文 Office 里露出中文。
 *
 * 跑法：npm test（已并入 package.json 的 test 脚本）
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const manifest = fs.readFileSync(path.join(rootDir, 'manifest.xml'), 'utf8')

const EN = 'en-US'

/** 取出某个成对/自闭合元素的整块文本（非贪婪，清单里这些元素不嵌套同名元素）。 */
function elementBlock(tag) {
  const pair = new RegExp(`<${tag}\\b[^>]*>[\\s\\S]*?</${tag}>`)
  const self = new RegExp(`<${tag}\\b[^>]*/>`)
  return (manifest.match(pair) || manifest.match(self) || [null])[0]
}

/** 取出所有 <bt:String id="X" ...>...</bt:String> 或自闭合形态。 */
function allBtStrings() {
  const re = /<bt:String\b[^>]*?id="([^"]+)"[^>]*?(?:\/>|>([\s\S]*?)<\/bt:String>)/g
  const out = []
  let m
  while ((m = re.exec(manifest)) !== null) {
    out.push({ id: m[1], inner: m[2] || '', selfClosing: m[0].endsWith('/>') })
  }
  return out
}

function hasEnOverride(block) {
  // DisplayName/Description 用默认命名空间的 <Override>，Resources 里的用 <bt:Override>。
  return new RegExp(`<(?:bt:)?Override\\b[^>]*Locale="${EN}"[^>]*Value="[^"]+"`).test(block)
}

test('DefaultLocale 保持 zh-CN', () => {
  assert.match(manifest, /<DefaultLocale>zh-CN<\/DefaultLocale>/)
})

test('DisplayName 与 Description 都有 en-US Override', () => {
  for (const tag of ['DisplayName', 'Description']) {
    const block = elementBlock(tag)
    assert.ok(block, `manifest.xml 里找不到 <${tag}>`)
    assert.ok(
      hasEnOverride(block),
      `<${tag}> 缺少 en-US Override——AppSource 英文列表要求清单声明支持 en-US`
    )
  }
})

test('Resources 里每条 bt:String 都有 en-US Override', () => {
  const strings = allBtStrings()
  assert.ok(strings.length > 0, '清单里没有 bt:String，Resources 结构可能被改动')
  const missing = strings
    .filter((s) => s.selfClosing || !hasEnOverride(s.inner))
    .map((s) => s.id)
  assert.deepEqual(
    missing,
    [],
    `以下 bt:String 缺少 en-US Override：${missing.join(', ')}`
  )
})

test('en-US Override 的值都不是中文（防止复制粘贴漏改）', () => {
  const re = /<(?:bt:)?Override\b[^>]*Locale="en-US"[^>]*Value="([^"]*)"/g
  let m
  let count = 0
  while ((m = re.exec(manifest)) !== null) {
    count++
    assert.ok(!/[一-鿿]/.test(m[1]), `en-US Override 的值含中文：${m[1]}`)
  }
  assert.ok(count >= 7, `en-US Override 条数偏少（${count}），可能有元素被漏掉`)
})

test('高分辨率图标是 64x64（任务窗格加载项的商店要求）', () => {
  // 微软文档「Create effective listings」：task pane add-in 的 HighResolutionIconUrl
  // 必须是 64x64、IconUrl 必须是 32x32。写死 icon-64/icon-32 挡住误改回 icon-80。
  assert.match(manifest, /<HighResolutionIconUrl DefaultValue="[^"]*\/icon-64\.png"\/>/)
  assert.match(manifest, /<IconUrl DefaultValue="[^"]*\/icon-32\.png"\/>/)
})
