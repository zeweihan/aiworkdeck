// 锁 zh-CN / en-US 两侧命名空间与键集合完全一致（漏译=构建期红，不是运行期回退）。
// 用法：node scripts/check-locale-parity.mjs（npm run check:locales）
import { readdirSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import path from 'node:path'

const root = path.resolve(process.cwd(), 'src/locales')
const langs = ['zh-CN', 'en-US']

function listNs(lang) {
  try {
    return readdirSync(path.join(root, lang)).filter((f) => f.endsWith('.js')).sort()
  } catch (e) {
    return []
  }
}

function flatten(obj, prefix = '') {
  const keys = []
  for (const [k, v] of Object.entries(obj || {})) {
    const full = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object') keys.push(...flatten(v, full))
    else keys.push(full)
  }
  return keys
}

let failed = false
const [zhFiles, enFiles] = langs.map(listNs)

for (const f of new Set([...zhFiles, ...enFiles])) {
  if (!zhFiles.includes(f)) { console.error(`✗ zh-CN 缺少命名空间文件 ${f}`); failed = true; continue }
  if (!enFiles.includes(f)) { console.error(`✗ en-US 缺少命名空间文件 ${f}`); failed = true; continue }
  const [zh, en] = await Promise.all(langs.map(async (lang) => {
    const mod = await import(pathToFileURL(path.join(root, lang, f)).href)
    return new Set(flatten(mod.default))
  }))
  for (const k of zh) if (!en.has(k)) { console.error(`✗ ${f}: en-US 缺键 ${k}`); failed = true }
  for (const k of en) if (!zh.has(k)) { console.error(`✗ ${f}: zh-CN 缺键 ${k}`); failed = true }
}

if (failed) process.exit(1)
console.log(`✓ locale 键对拍通过（${zhFiles.length} 个命名空间）`)
