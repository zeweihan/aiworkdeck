// doc.exec 白名单对拍（插件规范 v2.7 P1）：前端 PLUGIN_DOC_ACTIONS 必须与
// 后端宿主 SPI PluginHostImpl.DOC_ACTIONS 是同一份清单（JAR 与 Web 插件同一张能力面）。
// 配方照 PluginHostImplTest 扫源码字面量：漏一个/多一个都红。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')

function backendActions() {
  const java = readFileSync(resolve(root,
    'backend/src/main/java/com/checkba/service/plugin/PluginHostImpl.java'), 'utf8')
  const start = java.indexOf('DOC_ACTIONS = Set.of(')
  assert.ok(start > 0, '找不到 PluginHostImpl.DOC_ACTIONS 定义')
  const end = java.indexOf(');', start)
  const block = java.slice(start, end)
  const names = [...block.matchAll(/"([a-z0-9_]+)"/g)].map(m => m[1])
  assert.ok(names.length > 50, 'DOC_ACTIONS 解析出的条目数异常: ' + names.length)
  return new Set(names)
}

async function frontendActions() {
  const mod = await import(resolve(root, 'frontend/src/config/pluginDocActions.js'))
  return mod.PLUGIN_DOC_ACTIONS
}

test('PLUGIN_DOC_ACTIONS 与 PluginHostImpl.DOC_ACTIONS 逐项一致', async () => {
  const backend = backendActions()
  const frontend = await frontendActions()
  const missing = [...backend].filter(a => !frontend.has(a))
  const extra = [...frontend].filter(a => !backend.has(a))
  assert.deepEqual(missing, [], '前端白名单缺少（后端有）: ' + missing.join(', '))
  assert.deepEqual(extra, [], '前端白名单多出（后端无）: ' + extra.join(', '))
})

test('宿主自用与诊断原语不在白名单里', async () => {
  const frontend = await frontendActions()
  for (const forbidden of ['load_document', 'export_document', 'doc_open_file_sync', 'set_zoom', 'debug_revisions', 'ui_command']) {
    assert.ok(!frontend.has(forbidden), forbidden + ' 不应对插件开放')
  }
})
