// services/api.js 引用了 uni.* 与 @/ 别名，node 无法 import，只能做源码级契约断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/services/api.js'), 'utf8')

const NAMES = ['getProjectOverviewStats', 'getProjectProfile', 'saveProjectProfileField',
  'getProjectConversations', 'getProjectTasks']

test('五个函数都有具名导出', () => {
  for (const n of NAMES) assert.match(SRC, new RegExp('export function ' + n + '\\('), '缺具名导出: ' + n)
})

test('五个函数都进了默认导出对象', () => {
  const start = SRC.indexOf('export default {')
  assert.ok(start > 0, '找不到 export default 对象')
  const end = SRC.indexOf('\n}', start)
  const block = SRC.slice(start, end)
  for (const n of NAMES) assert.ok(block.includes(n), '默认导出对象里缺: ' + n)
})

test('URL 与后端契约逐字一致', () => {
  assert.ok(SRC.includes('`/api/projects/${projectId}/overview/stats`'))
  assert.ok(SRC.includes('`/api/projects/${projectId}/profile`'))
  assert.ok(SRC.includes('`/api/projects/${projectId}/profile/${encodeURIComponent(fieldKey)}`'))
  assert.ok(SRC.includes('`/api/projects/${projectId}/conversations`'))
  assert.ok(SRC.includes('`/api/projects/${projectId}/tasks`'))
})

test('saveProjectProfileField 是 PUT + JSON body {value}', () => {
  const i = SRC.indexOf('export function saveProjectProfileField(')
  const body = SRC.slice(i, i + 420)
  assert.match(body, /method:\s*'PUT'/)
  assert.match(body, /data:\s*\{\s*value\s*\}/)
  assert.match(body, /'Content-Type':\s*'application\/json'/)
})

test('getProjectConversations 走 params 而不是拼字符串', () => {
  const i = SRC.indexOf('export function getProjectConversations(')
  const body = SRC.slice(i, i + 420)
  assert.match(body, /params:\s*\{/)
  assert.match(body, /limit:\s*options\.limit\s*\|\|\s*20/)
  assert.match(body, /options\.before\s*\?\s*\{\s*before:\s*options\.before\s*\}/)
  assert.match(body, /options\.beforeId\s*\?\s*\{\s*beforeId:\s*options\.beforeId\s*\}/)
  assert.ok(!/conversations\?limit=/.test(body), 'query 不许拼进 url')
})
