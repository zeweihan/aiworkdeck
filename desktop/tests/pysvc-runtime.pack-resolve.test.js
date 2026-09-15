// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 四个 Python 服务的根目录解析（设计 §3.2）。优先级与 LitigationVisualService.resolveRuntime
// 同构：显式 env 覆盖 → pack current 目录（必须带 .pack-complete）→ dev 态 bundled。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const {
  PACK_ID_BY_SERVICE, resolveServiceRoot, libDirFor, appDirFor,
} = require('../main/services/pysvc-runtime')

function makeCtx(root) {
  return { dataDir: path.join(root, '.aiworkdeck'), projectRoot: path.join(root, 'repo'), packaged: true }
}

function installPack(ctx, packId, version, { complete = true, revoked = false } = {}) {
  const dir = path.join(ctx.dataDir, 'packs', packId, version)
  fs.mkdirSync(path.join(dir, 'lib'), { recursive: true })
  fs.mkdirSync(path.join(dir, 'app'), { recursive: true })
  if (complete) fs.writeFileSync(path.join(dir, '.pack-complete'), version)
  fs.writeFileSync(
    path.join(ctx.dataDir, 'packs', packId, 'current.json'),
    JSON.stringify({ version, activatedAt: new Date().toISOString(), revoked })
  )
  return dir
}

test('四个服务与 pack id 的映射是唯一事实来源', () => {
  assert.deepStrictEqual(PACK_ID_BY_SERVICE, {
    'pptx-service': 'pptx-runtime',
    'mineru-service': 'mineru-runtime',
    'kokoro-service': 'kokoro-runtime',
    'asr-service': 'asr-runtime',
  })
})

test('装好的 pack 命中：返回 current.json 指向且带 .pack-complete 的版本目录', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  const dir = installPack(ctx, 'kokoro-runtime', '1.0.0')

  assert.strictEqual(resolveServiceRoot(ctx, 'kokoro-service'), dir)
  assert.strictEqual(libDirFor(ctx, 'kokoro-service'), path.join(dir, 'lib'))
  assert.strictEqual(appDirFor(ctx, 'kokoro-service'), path.join(dir, 'app'))
})

test('没有 .pack-complete 的半成品不算数（安装中途被打断）', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  installPack(ctx, 'asr-runtime', '1.0.0', { complete: false })

  assert.strictEqual(resolveServiceRoot(ctx, 'asr-service'), null)
  assert.strictEqual(libDirFor(ctx, 'asr-service'), null)
})

test('被平台封禁的 pack 视而不见（规范 §8.4）', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  installPack(ctx, 'pptx-runtime', '1.0.0', { revoked: true })

  assert.strictEqual(resolveServiceRoot(ctx, 'pptx-service'), null)
})

test('没装 pack 时返回 null（调用方据此判「组件未安装」，不 spawn）', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  assert.strictEqual(resolveServiceRoot(makeCtx(root), 'mineru-service'), null)
})

test('dev 态回落 desktop/bundled/<plat>/pysvc/<service>；env 覆盖压过 pack', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  const plat = process.platform === 'win32' ? 'win-x64' : 'mac-arm64'
  const dev = path.join(ctx.projectRoot, 'desktop', 'bundled', plat, 'pysvc', 'pptx-service')
  fs.mkdirSync(dev, { recursive: true })
  assert.strictEqual(resolveServiceRoot({ ...ctx, packaged: false }, 'pptx-service'), dev)

  const packDir = installPack(ctx, 'pptx-runtime', '1.0.0')
  assert.strictEqual(resolveServiceRoot(ctx, 'pptx-service'), packDir, '打包态 pack 优先')

  const override = path.join(root, 'override')
  fs.mkdirSync(override, { recursive: true })
  process.env.AIWORKDECK_PYSVC_PPTX_DIR = override
  t.after(() => { delete process.env.AIWORKDECK_PYSVC_PPTX_DIR })
  assert.strictEqual(resolveServiceRoot(ctx, 'pptx-service'), override, 'env 覆盖是最高优先级（排障用）')
})

test('未知服务名不猜路径，直接 null', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-resolve-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  assert.strictEqual(resolveServiceRoot(makeCtx(root), 'litviz-service'), null)
})
