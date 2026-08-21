// HOUSE 单源对拍（dev-board#111）：backend/src/main/resources/style-profiles/house-default.json
// 是律所标准格式的唯一出处，worker（frontend/src/zetaoffice/public）与 Office 插件
// （office-addin/taskpane/lib）各持一份字节副本，由 scripts/sync-house-profile.mjs 同步。
// 三份 sha256 不一致 = 有人改了副本或改了源没重跑同步——两种都不许静默通过。
//   cd frontend && npm run test:evidence
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import crypto from 'node:crypto'
import { SOURCE, WORKER_JSON, WORKER_JS, ADDIN_JSON, unwrapWorkerJs, wrapWorkerJs } from '../../scripts/sync-house-profile.mjs'

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex')

test('后端资源 / worker 副本 / office-addin 副本三份字节 sha256 一致', () => {
  const src = fs.readFileSync(SOURCE)
  assert.ok(src.length > 0, '源文件为空: ' + SOURCE)
  for (const copy of [WORKER_JSON, ADDIN_JSON]) {
    assert.ok(fs.existsSync(copy), '副本缺失（跑 node scripts/sync-house-profile.mjs）: ' + copy)
    assert.equal(sha256(fs.readFileSync(copy)), sha256(src), '副本与源不一致（跑 node scripts/sync-house-profile.mjs）: ' + copy)
  }
})

test('worker 包装脚本 house-default.js 内嵌的 JSON 与源逐字节一致', () => {
  const js = fs.readFileSync(WORKER_JS, 'utf8')
  const embedded = unwrapWorkerJs(js)
  assert.ok(embedded != null, '包装脚本格式不对（勿手改，重跑 sync-house-profile）')
  assert.equal(sha256(Buffer.from(embedded, 'utf8')), sha256(fs.readFileSync(SOURCE)))
  assert.equal(js, wrapWorkerJs(fs.readFileSync(SOURCE, 'utf8')))
})

test('源文件本身是 schemaVersion 1 的画像且关键叶子齐全（worker 读这些字段）', () => {
  const p = JSON.parse(fs.readFileSync(SOURCE, 'utf8'))
  assert.equal(p.schemaVersion, 1)
  assert.equal(p.body.font.eastAsia, '楷体_GB2312')
  assert.equal(p.body.font.western, 'Arial')
  assert.deepEqual(p.body.size, { value: 12, unit: 'pt' })
  assert.equal(p.headings.length, 6)
  assert.equal(p.headings[0].level, 1)
  assert.deepEqual(p.table.cell.size, { value: 10, unit: 'pt' })
  assert.deepEqual(p.table.borders.outside.width, { value: 1.5, unit: 'pt' })
})
