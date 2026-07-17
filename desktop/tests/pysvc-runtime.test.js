const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFileSync } = require('child_process')
const { pysvcPath, ensurePysvcExtracted, MARKER } = require('../main/services/pysvc-runtime')

const PACK_SCRIPT = path.join(__dirname, '..', 'scripts', 'pack-pysvc.js')

// 造一个最小 pysvc 树（服务名/子目录结构对齐真实布局），返回 bundle 目录
function makeFakeBundle(root) {
  const bundle = path.join(root, 'bundle')
  const files = {
    'pysvc/mineru-service/lib/mineru/__init__.py': 'print("mineru")',
    'pysvc/kokoro-service/app/app.py': 'print("kokoro")',
    'pysvc/kokoro-service/lib/pkg/mod.py': 'x = 1',
    'pysvc/pptx-service/app/alembic.ini': '[alembic]'
  }
  for (const [rel, content] of Object.entries(files)) {
    const fp = path.join(bundle, rel)
    fs.mkdirSync(path.dirname(fp), { recursive: true })
    fs.writeFileSync(fp, content)
  }
  return { bundle, files }
}

test('pack-pysvc.js produces tarball + meta with correct totals', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-pack-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const { bundle, files } = makeFakeBundle(root)

  execFileSync(process.execPath, [PACK_SCRIPT, '--bundle', bundle])

  assert.ok(fs.existsSync(path.join(bundle, 'pysvc.tar.gz')))
  const meta = JSON.parse(fs.readFileSync(path.join(bundle, 'pysvc.meta.json'), 'utf8'))
  const expectedBytes = Object.values(files).reduce((s, c) => s + Buffer.byteLength(c), 0)
  assert.strictEqual(meta.fileCount, Object.keys(files).length)
  assert.strictEqual(meta.totalBytes, expectedBytes)
})

test('ensurePysvcExtracted: extract, marker, reuse, old-version cleanup', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-extract-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const { bundle, files } = makeFakeBundle(root)
  execFileSync(process.execPath, [PACK_SCRIPT, '--bundle', bundle])
  const archive = path.join(bundle, 'pysvc.tar.gz')
  const metaFile = path.join(bundle, 'pysvc.meta.json')

  // 模拟一份旧版本残留，成功解压后应被清掉
  const userData = path.join(root, 'userData')
  const oldDir = path.join(userData, 'pysvc-0.0.1')
  fs.mkdirSync(path.join(oldDir, 'pysvc'), { recursive: true })
  fs.writeFileSync(path.join(oldDir, MARKER), 'old')

  const versionDir = path.join(userData, 'pysvc-1.2.3')
  const r1 = await ensurePysvcExtracted({ archive, metaFile, versionDir })
  assert.strictEqual(r1.ok, true)
  assert.strictEqual(r1.reused, false)
  assert.ok(fs.existsSync(path.join(versionDir, MARKER)))
  for (const [rel, content] of Object.entries(files)) {
    assert.strictEqual(fs.readFileSync(path.join(versionDir, rel), 'utf8'), content, rel)
  }
  assert.ok(!fs.existsSync(oldDir), 'old pysvc-<version> dir should be cleaned up')

  // marker 命中 → 幂等复用，不重复解压
  const r2 = await ensurePysvcExtracted({ archive, metaFile, versionDir })
  assert.strictEqual(r2.ok, true)
  assert.strictEqual(r2.reused, true)
})

test('ensurePysvcExtracted: missing archive fails without throwing', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pysvc-miss-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const r = await ensurePysvcExtracted({
    archive: path.join(root, 'nope.tar.gz'),
    metaFile: path.join(root, 'nope.json'),
    versionDir: path.join(root, 'pysvc-9.9.9')
  })
  assert.strictEqual(r.ok, false)
  assert.ok(r.message)
})

test('pysvcPath: packaged uses pysvcRoot, dev falls back to resourcesPath/pysvc', () => {
  const packaged = { pysvcRoot: path.join('u', 'pysvc-1.0.0', 'pysvc'), resourcesPath: 'res' }
  assert.strictEqual(
    pysvcPath(packaged, 'mineru-service', 'lib'),
    path.join('u', 'pysvc-1.0.0', 'pysvc', 'mineru-service', 'lib')
  )
  const dev = { pysvcRoot: null, resourcesPath: 'res' }
  assert.strictEqual(pysvcPath(dev, 'kokoro-service', 'app'), path.join('res', 'pysvc', 'kokoro-service', 'app'))
})
