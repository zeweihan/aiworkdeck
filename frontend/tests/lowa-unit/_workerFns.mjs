// 从 office_thread.js（worker 脚本，非模块）里按名字抠出顶层纯函数，在 node 里直接跑。
// 只适用于不碰 UNO 对象的纯函数（minimalEdits 一族）；有 UNO 依赖的原语走 lowa-e2e。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
export const WORKER = path.resolve(here, '../../src/zetaoffice/public/office_thread.js')

// 找到 `function NAME(` 起、按花括号配平到函数体结束（这些函数的字符串/注释里不许出现花括号）。
function sliceFunction(src, name) {
  const head = 'function ' + name + '('
  const at = src.indexOf('\n' + head)
  if (at < 0) throw new Error('office_thread.js 里找不到顶层函数 ' + name)
  let i = src.indexOf('{', at)
  let depth = 0
  for (; i < src.length; i++) {
    const ch = src[i]
    if (ch === '{') depth++
    else if (ch === '}') { depth--; if (depth === 0) return src.slice(at + 1, i + 1) }
  }
  throw new Error('函数 ' + name + ' 花括号不配平')
}

export function loadWorkerFunctions(names) {
  const src = fs.readFileSync(WORKER, 'utf8')
  const body = names.map((n) => sliceFunction(src, n)).join('\n')
  // 同族常量（MINIMAL_EDITS_* ）也一并抠出来
  const consts = [...src.matchAll(/^const (MINIMAL_EDITS_[A-Z_]+) = ([^;]+);/gm)].map((m) => m[0]).join('\n')
  const fn = new Function(consts + '\n' + body + '\nreturn {' + names.join(',') + '};')
  return fn()
}
