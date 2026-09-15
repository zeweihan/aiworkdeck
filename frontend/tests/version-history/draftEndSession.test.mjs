// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 稿上也要能「结束本次工作」并起名字（真机反馈 B2）。
 *
 * 病灶：切到稿之后状态条只剩「回到主线工作 / 采纳这一稿 / 放弃这一稿」，稿上的改动
 * 只能靠防抖存档留下无名版本，采纳完历史里这一稿全是「修改了《X》」。
 *
 * 这里验两件事：稿态真的露出了这个入口，且它走的是**主线那条一模一样的命名流程**
 * （openNaming → end → endWorkSession），不是另造的第二套。
 * 跑法：cd frontend && node --test tests/version-history/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(
  new URL('../../src/components/version/WorkSessionBar.vue', import.meta.url), 'utf8')

/** 稿态那一段模板（v-if="onDraft" 到下一个 template 分支之前）。 */
function draftBlock() {
  const start = source.indexOf('<template v-if="onDraft">')
  const end = source.indexOf('<template v-else-if="working">')
  assert.ok(start > 0 && end > start, '模板结构变了，先来核对这个测试')
  return source.slice(start, end)
}

test('稿态露出「结束本次工作」，点的是主线那个命名弹窗', () => {
  const block = draftBlock()
  assert.match(block, /\$t\('version\.endSession'\)/,
    '稿上没有这个入口，改动就只剩无名的自动存档')
  assert.match(block, /@tap="openNaming">\{\{ \$t\('version\.endSession'\) \}\}/,
    '必须复用主线那条命名流程（openNaming → 弹窗 → end），不另造一套')
})

test('稿态原有的三个动作一个都没丢', () => {
  const block = draftBlock()
  for (const key of ['version.returnToMainline', 'version.adoptDraft', 'version.abandonDraft']) {
    assert.match(block, new RegExp(key.replace('.', '\\.')), key + ' 不该被挤掉')
  }
})

test('end() 把标题交给同一个端点，成功后关弹窗并报 ended', async () => {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  const names = []
  for (const m of script.matchAll(/^import\s+([\s\S]*?)\s+from\s+'[^']+'\s*;?\s*$/gm)) {
    const braced = m[1].match(/\{([\s\S]*?)\}/)
    if (braced) for (const part of braced[1].split(',')) {
      const n = part.split(/\s+as\s+/).pop().trim()
      if (n) names.push(n)
    }
  }
  const body = script
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
    .replace(/export\s+default\s*\{/, 'return {')
  const sent = []
  const stubs = names.map((n) => (n === 'endWorkSession'
    ? async (projectId, title) => { sent.push([projectId, title]); return { data: {} } }
    : () => {}))
  const options = new Function(...names, body)(...stubs)

  const events = []
  const host = Object.assign({}, options.methods, {
    projectId: 42,
    title: '第九条培训天数改为 5 个工作日',
    naming: true,
    busy: false,
    $emit: (name, payload) => events.push([name, payload]),
    $t: (k) => k,
  })
  await host.end()

  assert.deepEqual(sent, [[42, '第九条培训天数改为 5 个工作日']])
  assert.equal(host.naming, false, '成功之后要把命名弹窗关掉')
  assert.deepEqual(events, [['ended', undefined]])
})
