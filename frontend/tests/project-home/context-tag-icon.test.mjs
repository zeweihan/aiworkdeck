// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#975（BUG-79 / QA C9-10）：AI 输入框里的 @文件 标签图标裂图，× 只在悬停时出现。
//
// 病灶：标签是 insertContextTagToInput 拼出来的一段裸 HTML，<img src="/static/document.png">
// 不经过 uni <image> 组件的 getRealPath。桌面端主窗口是 loadFile 出来的 file:// 页面，
// 以 / 开头的路径被解析成 file:///static/document.png（磁盘根），naturalWidth=0。
// uni <image> 在 router.base='./' 下把 /static/x 换成 ./static/x，裸 HTML 得自己写成这样。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'

const CI = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')

function tagBuilderBody() {
  const start = CI.indexOf('const insertContextTagToInput = ')
  assert.ok(start > 0, '找不到 insertContextTagToInput')
  const end = CI.indexOf('inputPrompt.value = richInput.value.innerText', start)
  assert.ok(end > start)
  return CI.slice(start, end)
}

test('标签图标在 file:// 打包页面下解析到 dist/static 里的真实文件', () => {
  const body = tagBuilderBody()
  const srcs = [...body.matchAll(/'([^']*static\/[^']+\.png)'/g)].map((m) => m[1])
  assert.ok(srcs.length >= 2, '文件与文件夹两种图标都应出现: ' + JSON.stringify(srcs))
  // 与 desktop/main/main.js 的 loadFile(dist/index.html) 同形；hash 路由下文档 URL 恒为 index.html
  const page = 'file:///Applications/AI%20WorkDeck.app/Contents/Resources/frontend/dist/build/h5/index.html#/pages/project-overview/project-overview?id=1'
  const distDir = '/Applications/AI%20WorkDeck.app/Contents/Resources/frontend/dist/build/h5/'
  for (const src of srcs) {
    const resolved = new URL(src, page)
    assert.ok(resolved.pathname.startsWith(distDir + 'static/'), `${src} 解析成了 ${resolved.href}，落在包外`)
    const rel = resolved.pathname.slice(distDir.length + 'static/'.length)
    assert.ok(existsSync(new URL('../../src/static/' + rel, import.meta.url)), `static/${rel} 不存在`)
  }
})

test('dev server（http://host/ + hash 路由）下同一写法也落在 /static/', () => {
  const body = tagBuilderBody()
  const srcs = [...body.matchAll(/'([^']*static\/[^']+\.png)'/g)].map((m) => m[1])
  for (const src of srcs) {
    assert.ok(new URL(src, 'http://localhost:5173/#/pages/project-overview/project-overview').pathname.startsWith('/static/'))
  }
})

test('标签的 × 常显，不靠悬停（对齐标签页 ×、暂存区 ×）', () => {
  const base = CI.match(/:deep\(\.tag-close\)\s*\{([^}]*)\}/)
  assert.ok(base, '找不到 .tag-close 基础样式')
  assert.doesNotMatch(base[1], /display:\s*none/, '.tag-close 基础态不许 display:none')
  assert.doesNotMatch(CI, /:deep\(\.context-tag-inline:hover \.tag-close\)/, '× 不该再由 hover 才显出')
  // × 是绝对定位在右侧的，常显就得常留出位置，否则压住文件名
  const tag = CI.match(/:deep\(\.context-tag-inline\)\s*\{([^}]*)\}/)
  assert.match(tag[1], /padding(-right)?:[^;]*\b22px/, "常态就要给 × 留出右侧位置")
  // 气泡的 contentHtml 是输入框 innerHTML 的快照，× 也在里面；常显之后必须在气泡里单独藏掉。
  // v-html 内容不带 scoped 的 data-v 属性，不走 :deep 的选择器根本匹配不上
  assert.match(CI, /\.user-bubble :deep\(\.context-tag-inline \.tag-close\)\s*\{[^}]*display:\s*none;?\s*\}/, '已发出的气泡里不该出现 ×')
})
