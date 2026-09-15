// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 契约：用户名（username）是 uid，任何界面都不再当名字显示
 * （设计见 docs/superpowers/specs/2026-09-10-identity-display-source-design.md §2 第 5 条）。
 *
 * 为什么要一条扫描式的测试而不是逐处断言：这个病是**一次退化就再也没人发现**的类型——
 * 手机号注册的同事，用户名是 `u`+随机串，展示名是打码手机号。哪天有人在参与人列表、
 * 时间线、批注作者那里顺手加一句 `|| m.username` 兜底，界面上不报错、测试不红，
 * 只是同事在案卷里变成一串随机字母。所以守的是「整棵 src 里一处都不许有」，
 * 而不是「这三个文件对」。
 *
 * 两条规则：
 *  ① 模板插值 {{ ... }} 里出现 username；
 *  ② 任何位置的 `|| xxx.username` 兜底（比较式 `|| u.username === 'admin'` 不算，
 *     那是判定不是显示）。
 * 例外写在同目录的 display-name-only.allowlist.json，每条必须带理由。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SRC = path.resolve(HERE, '../../src')
const ALLOWLIST_PATH = path.join(HERE, 'display-name-only.allowlist.json')

/** {{ ... }} 里出现 username（含 `@{{ x.username }}` 这种小字 handle）。 */
const INTERPOLATION = /\{\{(?:(?!\}\})[\s\S])*\}\}/g
/** `|| a.b.username`，但后面不跟比较符（`=== 'admin'` 那种是权限判定，不是展示）。 */
const USERNAME_FALLBACK = /\|\|\s*[A-Za-z_$][\w$]*(?:\s*\??\.\s*[A-Za-z_$][\w$]*)*\s*\.\s*username\b(?!\s*[=!]=)/g

function listVueFiles(dir) {
  const out = []
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry.startsWith('.')) continue
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...listVueFiles(full))
    else if (entry.endsWith('.vue')) out.push(full)
  }
  return out.sort()
}

function findViolations(relPath, source) {
  const violations = []
  const lines = source.split('\n')
  // ① 插值：先在全文里找出每个 {{ }}（可能跨行），再换算回行号
  for (const m of source.matchAll(INTERPOLATION)) {
    if (!/\busername\b/.test(m[0])) continue
    const line = source.slice(0, m.index).split('\n').length
    violations.push({ file: relPath, line, rule: 'interpolation', snippet: m[0].replace(/\s+/g, ' ').trim() })
  }
  // ② `|| x.username` 兜底：模板与脚本一视同仁
  lines.forEach((text, i) => {
    for (const m of text.matchAll(USERNAME_FALLBACK)) {
      violations.push({ file: relPath, line: i + 1, rule: 'fallback', snippet: text.trim(), matched: m[0] })
    }
  })
  return violations
}

/** 命中允许名单：同一个文件、且名单里那段 pattern 出现在这一行（或这段插值）里。 */
function isAllowed(violation, allowlist, lineText) {
  return allowlist.some((entry) =>
    entry.file === violation.file &&
    typeof entry.pattern === 'string' && entry.pattern.length > 0 &&
    (lineText.includes(entry.pattern) || violation.snippet.includes(entry.pattern)))
}

test('src/**/*.vue 里不许把 username 当名字显示', () => {
  const allowlist = JSON.parse(readFileSync(ALLOWLIST_PATH, 'utf8'))
  for (const entry of allowlist) {
    assert.ok(entry.reason && entry.reason.trim().length >= 8,
      `允许名单每条都要写清理由：${entry.file} / ${entry.pattern}`)
  }

  const offenders = []
  for (const full of listVueFiles(SRC)) {
    const rel = path.relative(path.resolve(HERE, '../..'), full).split(path.sep).join('/')
    const source = readFileSync(full, 'utf8')
    const lines = source.split('\n')
    for (const v of findViolations(rel, source)) {
      if (isAllowed(v, allowlist, lines[v.line - 1] || '')) continue
      offenders.push(`${v.file}:${v.line} [${v.rule}] ${v.snippet}`)
    }
  }

  assert.deepEqual(offenders, [],
    '用户名是 uid，不是名字：改用 displayName，兜底用文案键（如 version.unnamedColleague）。\n' +
    '确属内部用途（登录表单、后台用户管理、本机工作区选择）就加进 ' +
    'tests/identity/display-name-only.allowlist.json 并写清理由。\n' + offenders.join('\n'))
})

test('允许名单里的每一条都还对得上真实代码（名单不许烂在这里）', () => {
  const allowlist = JSON.parse(readFileSync(ALLOWLIST_PATH, 'utf8'))
  const root = path.resolve(HERE, '../..')
  for (const entry of allowlist) {
    const source = readFileSync(path.join(root, entry.file), 'utf8')
    assert.ok(source.includes(entry.pattern),
      `允许名单里的 ${entry.file} / ${entry.pattern} 在代码里已经不存在了，删掉这条`)
  }
})
