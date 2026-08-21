#!/usr/bin/env node
// 律所标准格式（HOUSE）单源同步：backend/src/main/resources/style-profiles/house-default.json
// 是唯一出处，这里把它复制到两个写端：
//   1. frontend/src/zetaoffice/public/house-default.json（字节副本，供对拍）
//      + house-default.js（worker 经 Module.uno_scripts 载入的包装：self.HOUSE_DEFAULT_JSON）
//   2. office-addin/taskpane/lib/house-default.json（officeExecutor.js 构建时内联）
// 副本入库，`npm run build:zetaoffice` / office-addin `npm run build` 前自动重跑；
// tests/evidence/houseProfile.test.mjs 与 office-addin 的 houseProfile.test.js 断言三份 sha256 一致。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(here, '../..')
export const SOURCE = path.join(repo, 'backend/src/main/resources/style-profiles/house-default.json')
export const WORKER_JSON = path.join(repo, 'frontend/src/zetaoffice/public/house-default.json')
export const WORKER_JS = path.join(repo, 'frontend/src/zetaoffice/public/house-default.js')
export const ADDIN_JSON = path.join(repo, 'office-addin/taskpane/lib/house-default.json')

const JS_HEAD = '// 由 frontend/scripts/sync-house-profile.mjs 生成，勿手改：改 backend/src/main/resources/style-profiles/house-default.json。\n'
  + 'self.HOUSE_DEFAULT_JSON = '
const JS_TAIL = ';\n'

/** 包装 JS 里嵌的 JSON 原文（字符串字面量，解码后与源文件逐字节一致）。 */
export function wrapWorkerJs(jsonText) {
  return JS_HEAD + JSON.stringify(jsonText) + JS_TAIL
}
export function unwrapWorkerJs(jsText) {
  if (!jsText.startsWith(JS_HEAD) || !jsText.endsWith(JS_TAIL)) return null
  return JSON.parse(jsText.slice(JS_HEAD.length, jsText.length - JS_TAIL.length))
}

export function sync() {
  const text = fs.readFileSync(SOURCE, 'utf8')
  JSON.parse(text) // 源文件必须是合法 JSON，坏文件不许扩散到三处
  const written = []
  for (const [dest, content] of [[WORKER_JSON, text], [WORKER_JS, wrapWorkerJs(text)], [ADDIN_JSON, text]]) {
    if (fs.existsSync(dest) && fs.readFileSync(dest, 'utf8') === content) continue
    fs.mkdirSync(path.dirname(dest), { recursive: true })
    fs.writeFileSync(dest, content)
    written.push(dest)
  }
  return written
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const written = sync()
  console.log('[sync-house-profile] ' + (written.length ? '已更新:\n  ' + written.map((p) => path.relative(repo, p)).join('\n  ') : '三处副本已是最新'))
}
