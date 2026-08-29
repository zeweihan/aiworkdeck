// Web 插件 SDK 副本守卫：源头 sdk/plugin-sdk/awd-plugin-sdk.js 与仓内两份分发副本必须逐字节一致
// （官网 lib/plugin-template.ts 的内联副本在另一个仓，靠 PR 同步；后端 classpath 副本另有
// PluginDevSdkParityTest 守着，这里一并比对让前端一条命令就能发现漏同步）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const source = readFileSync(resolve(root, 'sdk/plugin-sdk/awd-plugin-sdk.js'))

for (const copy of [
  'examples/hello-web-plugin/web/awd-plugin-sdk.js',
  'backend/src/main/resources/plugin-dev/awd-plugin-sdk.js'
]) {
  test('SDK 副本逐字节一致：' + copy, () => {
    assert.ok(source.equals(readFileSync(resolve(root, copy))), copy + ' 与 sdk/plugin-sdk/awd-plugin-sdk.js 不一致')
  })
}

test('SDK 源码不含模板字符串字符（官网 lib/plugin-template.ts 把它内联进反引号字符串）', () => {
  const text = source.toString('utf8')
  assert.equal(text.indexOf('`'), -1)
  assert.equal(text.indexOf('${'), -1)
})

test('SDK 暴露 evidence.link / list / locate', () => {
  const text = source.toString('utf8')
  for (const m of ['evidence.link', 'evidence.list', 'evidence.locate']) {
    assert.ok(text.indexOf("call('" + m + "'") >= 0, '缺 ' + m)
  }
})

test('SDK 暴露 v2.5 的 tools.invoke / chat.send / ui.openFile', () => {
  const text = source.toString('utf8')
  for (const m of ['tools.invoke', 'chat.send', 'ui.openFile']) {
    assert.ok(text.indexOf("call('" + m + "'") >= 0, '缺 ' + m)
  }
})

test('SDK 暴露 v2.7 的 doc.exec / doc.active / events.* / ai.request', () => {
  const text = source.toString('utf8')
  for (const m of ['doc.exec', 'doc.active', 'events.subscribe', 'events.unsubscribe', 'ai.request']) {
    assert.ok(text.indexOf("call('" + m + "'") >= 0 || text.indexOf("'" + m + "'") >= 0, '缺 ' + m)
  }
  assert.ok(text.indexOf('1.3.0') >= 0, 'SDK 版本号应为 1.3.0')
})
