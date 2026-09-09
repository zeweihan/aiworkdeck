// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 四个服务的启动门（设计 §3.2）。pptx/asr 只判 pack 在场；mineru/kokoro 还要判模型。
// asr 刻意保持「pack 在场就起、模型没下也起」：就绪探测必须能分清
// RUNTIME_MISSING / SERVICE_DOWN / MODEL_MISSING 三件事（见 LocalAsrClient 四态）。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')

const { createPptxDescriptor } = require('../main/services/pptx-service')
const { createMineruDescriptor } = require('../main/services/mineru-service')
const { createKokoroDescriptor } = require('../main/services/kokoro-service')
const { createAsrDescriptor } = require('../main/services/asr-service')

function ctxWith(root, packs) {
  const dataDir = path.join(root, '.aiworkdeck')
  for (const id of packs) {
    const dir = path.join(dataDir, 'packs', id, '1.0.0')
    fs.mkdirSync(path.join(dir, 'lib'), { recursive: true })
    fs.mkdirSync(path.join(dir, 'app'), { recursive: true })
    fs.writeFileSync(path.join(dir, '.pack-complete'), '1.0.0')
    fs.writeFileSync(path.join(dataDir, 'packs', id, 'current.json'), JSON.stringify({ version: '1.0.0' }))
  }
  return { dataDir, projectRoot: path.join(root, 'repo'), packaged: true, resourcesPath: path.join(root, 'res'), ports: {} }
}

const models = (installed) => ({ isInstalled: (id) => installed.includes(id) })

test('pack 未装：四个服务全部不启动', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'svc-gate-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = ctxWith(root, [])
  assert.strictEqual(createPptxDescriptor().enabled(ctx), false)
  assert.strictEqual(createAsrDescriptor().enabled(ctx), false)
  assert.strictEqual(createMineruDescriptor(models(['mineru-models'])).enabled(ctx), false,
    '模型下了但运行时没装，起不来——不能因为模型在就 spawn 一个找不到 lib 的进程')
  assert.strictEqual(createKokoroDescriptor(models(['kokoro-models'])).enabled(ctx), false)
})

test('pack 装了：pptx 与 asr 直接启动（asr 不等模型）', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'svc-gate-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = ctxWith(root, ['pptx-runtime', 'asr-runtime'])
  assert.strictEqual(createPptxDescriptor().enabled(ctx), true)
  assert.strictEqual(createAsrDescriptor().enabled(ctx), true)
})

test('pack 装了但模型没下：mineru / kokoro 仍不启动（模型加载是它们的启动前提）', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'svc-gate-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = ctxWith(root, ['mineru-runtime', 'kokoro-runtime'])
  assert.strictEqual(createMineruDescriptor(models([])).enabled(ctx), false)
  assert.strictEqual(createKokoroDescriptor(models([])).enabled(ctx), false)
  assert.strictEqual(createMineruDescriptor(models(['mineru-models'])).enabled(ctx), true)
  assert.strictEqual(createKokoroDescriptor(models(['kokoro-models'])).enabled(ctx), true)
})

test('dev 态（未打包）恒启用，不被 pack 判定挡住', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'svc-gate-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = { ...ctxWith(root, []), packaged: false }
  assert.strictEqual(createPptxDescriptor().enabled(ctx), true)
  assert.strictEqual(createAsrDescriptor().enabled(ctx), true)
})
