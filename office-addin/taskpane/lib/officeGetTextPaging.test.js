// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * get_text 分页的**接线**（dev-board#806，审计 B-05）：
 *   node --test office-addin/taskpane/lib/officeGetTextPaging.test.js
 *
 * textPaging.test.js 证明的是切法本身；这里证明两个家族的 handler 真的把 args 交给了它——
 * 「原语级测试通过但接线没接上」是本仓反复踩过的一类空覆盖。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

function setGlobals(g) {
  const saved = {}
  for (const k of ['Office', 'Word']) {
    saved[k] = globalThis[k]
    if (k in g) globalThis[k] = g[k]
    else delete globalThis[k]
  }
  return () => {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete globalThis[k]
      else globalThis[k] = saved[k]
    }
  }
}

function installWord(text) {
  return setGlobals({
    Office: {
      HostType: { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' },
      context: { host: 'Word', requirements: { isSetSupported: () => true } }
    },
    Word: {
      run: async (cb) => cb({
        document: { body: { text, load() {} } },
        sync: async () => {}
      })
    }
  })
}

const { executeOfficeCommand } = await import('./officeExecutor.js')

const LONG = Array.from({ length: 120_000 }, (_, i) => String(i % 10)).join('')

test('Office 面：默认只给 5 万字，并给出 nextStart', async () => {
  const restore = installWord(LONG)
  try {
    const r = await executeOfficeCommand('get_text', {})
    assert.equal(r.ok, true)
    assert.equal(r.data.returned, 50_000)
    assert.equal(r.data.totalChars, 120_000)
    assert.equal(r.data.truncated, true)
    assert.equal(r.data.nextStart, 50_000)
  } finally { restore() }
})

test('Office 面：startChar/maxChars 真的被 handler 用上（接线，不是只在纯函数里成立）', async () => {
  const restore = installWord(LONG)
  try {
    const r = await executeOfficeCommand('get_text', { startChar: 100, maxChars: 10 })
    assert.equal(r.data.text, LONG.slice(100, 110))
    assert.equal(r.data.startChar, 100)
    assert.equal(r.data.nextStart, 110)
  } finally { restore() }
})

test('Office 面：读到文末不再给 nextStart（模型据此停下）', async () => {
  const restore = installWord('短文档')
  try {
    const r = await executeOfficeCommand('get_text', { startChar: 0, maxChars: 1000 })
    assert.equal(r.data.text, '短文档')
    assert.equal(r.data.truncated, false)
    assert.equal('nextStart' in r.data, false)
  } finally { restore() }
})
