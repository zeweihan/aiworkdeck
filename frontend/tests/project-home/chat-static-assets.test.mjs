// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#986（BUG-83）：对话区「已修改 / 新建文件」弹层的图标引用 /static/file.png，
// 仓库里没有这张图，弹层每一行图标位都是裂图。uni 不会因为静态资源缺失而构建失败，只能靠这里守住：
// frontend/src 下 .vue/.js/.mjs 引用的每一张 static 图片都必须在 frontend/src/static 下真实存在。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = fileURLToPath(new URL('../../src/', import.meta.url))

// 已知缺失、由另一批次修掉的引用。PR #1005（批次 U）删掉了 FileStagingArea.vue 的
// file.png / word.png / pdf.png 三处引用——PR #1005 合入后删除白名单。
const ALLOW_MISSING = new Set([
  'components/FileStagingArea.vue:file.png',
  'components/FileStagingArea.vue:word.png',
  'components/FileStagingArea.vue:pdf.png',
])

function walk(dir, out = []) {
  for (const ent of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, ent.name)
    if (ent.isDirectory()) {
      if (ent.name !== 'static' && ent.name !== 'node_modules') walk(p, out)
    } else if (/\.(vue|js|mjs)$/.test(ent.name)) out.push(p)
  }
  return out
}

/** 去掉注释再扫：注释里提到「不存在的 /static/x」是在解释，不是引用 */
function stripComments(code) {
  return code
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/\s\/\/\s.*$/gm, '')
}

function scan() {
  const refs = []
  for (const file of walk(SRC)) {
    const code = stripComments(readFileSync(file, 'utf8'))
    for (const m of code.matchAll(/\.?\/static\/([\w\-./]+\.(?:png|jpe?g|svg|gif|webp))/g)) {
      refs.push({ file: relative(SRC, file).split('\\').join('/'), ref: m[1] })
    }
  }
  return refs
}

test('frontend/src 引用的 static 图片全部存在', () => {
  const refs = scan()
  assert.ok(refs.length >= 20, '扫描范围不对，只扫到 ' + refs.length + ' 处')
  const missing = refs
    .filter((r) => !existsSync(join(SRC, 'static', r.ref)))
    .filter((r) => !ALLOW_MISSING.has(r.file + ':' + r.ref))
    .map((r) => r.file + ' -> static/' + r.ref)
  assert.deepEqual([...new Set(missing)], [], 'frontend/src/static 下不存在')
})

test('白名单里的条目仍然缺失（修掉之后要把白名单一起删掉）', () => {
  const stillMissing = new Set(scan()
    .filter((r) => !existsSync(join(SRC, 'static', r.ref)))
    .map((r) => r.file + ':' + r.ref))
  for (const k of ALLOW_MISSING) assert.ok(stillMissing.has(k), '白名单已过期：' + k)
})

test('裸 Image 对象的拖拽徽标在 file:// 打包页面下解析到包内（dev-board#975 同族）', () => {
  const code = readFileSync(join(SRC, 'utils/dragImage.js'), 'utf8')
  const m = code.match(/src\.src = '([^']+)'/)
  assert.ok(m, '找不到拖拽徽标的赋值')
  const dist = '/Applications/AI%20WorkDeck.app/Contents/Resources/frontend/dist/build/h5/'
  const resolved = new URL(m[1], 'file://' + dist + 'index.html#/pages/project-overview/project-overview')
  assert.ok(resolved.pathname.startsWith(dist + 'static/'), `${m[1]} 解析成了 ${resolved.href}，落在包外`)
})
