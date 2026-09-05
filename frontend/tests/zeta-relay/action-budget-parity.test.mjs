/**
 * 「三处同表」的预算表契约（dev-board#108 立的规矩，dev-board#464 补上守卫）。
 *
 * 后端 EditorBridgeService.ACTION_TIMEOUT_SECONDS、前端
 * libreofficeExecutorClient.js 与 zetaOfficeRelay.js 的 ACTION_BUDGET_MS 是同一张表
 * （后端秒、前端毫秒）。此前只有注释要求同步，没有任何用例守着——dev-board#464 就是
 * 整段插入类 action 三处都漏登记，30s 默认预算下后端先放弃、模型被告知失败后重发，
 * 同一份长报告以修订插了两遍。
 *
 *   cd frontend && npm run test:zeta-relay
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ACTION_BUDGET_MS } from '../../src/composables/libreofficeExecutorClient.js'

const root = fileURLToPath(new URL('../../..', import.meta.url))

/** 从一段源码里抠出某张表的「键 -> 数值」（键可带引号，值必须是整数字面量）。 */
function parseTable(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker)
  assert.ok(start >= 0, `没找到表的起点：${startMarker}`)
  const end = source.indexOf(endMarker, start)
  assert.ok(end > start, `没找到表的终点：${endMarker}`)
  const body = source.slice(start + startMarker.length, end)
  const out = {}
  for (const m of body.matchAll(/["']?([a-z_]+)["']?\s*[:,]\s*(\d+)/g)) out[m[1]] = Number(m[2])
  return out
}

const backend = parseTable(
  readFileSync(root + 'backend/src/main/java/com/checkba/service/ai/EditorBridgeService.java', 'utf8'),
  'ACTION_TIMEOUT_SECONDS = Map.', ');')
const relay = parseTable(
  readFileSync(root + 'frontend/src/composables/zetaOfficeRelay.js', 'utf8'),
  'const ACTION_BUDGET_MS = {', '}')

test('整段插入类 action 在两张前端预算表里都是 120s', () => {
  for (const action of ['insert_at_cursor', 'insert_under_heading', 'replace_selection', 'modify_paragraph']) {
    assert.equal(ACTION_BUDGET_MS[action], 120000, `libreofficeExecutorClient 缺 ${action}`)
    assert.equal(relay[action], 120000, `zetaOfficeRelay 缺 ${action}`)
  }
})

test('三处同表：键集合一致，且毫秒 = 秒 × 1000', () => {
  const keys = (t) => Object.keys(t).sort()
  // 前端多一个 load_document（宿主自发的装载），后端对应的是 doc_open_file_sync。
  const backendKeys = keys(backend).map((k) => (k === 'doc_open_file_sync' ? 'load_document' : k))
  assert.deepEqual(keys(ACTION_BUDGET_MS), backendKeys.sort(), 'executorClient 与后端表键集合不一致')
  assert.deepEqual(keys(relay), keys(ACTION_BUDGET_MS), 'relay 与 executorClient 表键集合不一致')
  for (const [action, seconds] of Object.entries(backend)) {
    const key = action === 'doc_open_file_sync' ? 'load_document' : action
    assert.equal(ACTION_BUDGET_MS[key], seconds * 1000, `${key} 前后端预算不等价`)
    assert.equal(relay[key], seconds * 1000, `${key} relay 预算不等价`)
  }
})
