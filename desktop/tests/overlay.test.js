const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const overlay = require('../main/services/overlay')

function makeCtx(root, appVersion = '0.11.0') {
  return { packaged: true, dataDir: root, appVersion }
}

function stageComponent(ctx, name, files) {
  const dir = path.join(overlay.stagingDir(ctx), name)
  for (const [rel, content] of Object.entries(files)) {
    const fp = path.join(dir, rel)
    fs.mkdirSync(path.dirname(fp), { recursive: true })
    fs.writeFileSync(fp, content)
  }
  return dir
}

test('版本解析与比较', () => {
  assert.strictEqual(overlay.majorOf('0.11.2'), '0.11')
  assert.strictEqual(overlay.compareVersions('0.11.2', '0.11.1'), 1)
  assert.strictEqual(overlay.compareVersions('0.11.2', '0.12.0'), -1)
  assert.strictEqual(overlay.compareVersions('0.11.10', '0.11.9'), 1) // 数值而非字典序
  assert.strictEqual(overlay.compareVersions('bogus', '0.0.1'), -1)
})

test('无 overlay 时全部走内置', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ovl-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  assert.strictEqual(overlay.readCurrent(ctx), null)
  assert.strictEqual(overlay.effectiveVersion(ctx), '0.11.0')
  assert.strictEqual(overlay.componentDir(ctx, 'backend-app'), null)
  assert.strictEqual(overlay.installedComponentVersion(ctx, 'backend-app'), '0.11.0')
})

test('激活补丁：组件落位 + 指针推进 + 生效版本变化', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ovl-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  const staged = stageComponent(ctx, 'backend-app', { 'app.jar': 'v1-bytes' })
  overlay.activate(ctx, '0.11.1', { 'backend-app': staged })

  assert.strictEqual(overlay.effectiveVersion(ctx), '0.11.1')
  const dir = overlay.componentDir(ctx, 'backend-app')
  assert.ok(dir && dir.endsWith(path.join('backend-app', '0.11.1')))
  assert.strictEqual(fs.readFileSync(path.join(dir, 'app.jar'), 'utf8'), 'v1-bytes')
  // 未打补丁的组件仍回内置基线
  assert.strictEqual(overlay.componentDir(ctx, 'frontend-h5'), null)
})

test('拒绝降级激活', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ovl-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  overlay.activate(ctx, '0.11.2', { 'backend-app': stageComponent(ctx, 'backend-app', { 'app.jar': 'v2' }) })
  assert.throws(() => overlay.activate(ctx, '0.11.1', {}), /拒绝激活/)
  assert.throws(() => overlay.activate(ctx, '0.11.2', {}), /拒绝激活/)
})

test('二次激活保留未变组件 + previous 链只留一层', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ovl-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  overlay.activate(ctx, '0.11.1', {
    'backend-app': stageComponent(ctx, 'backend-app', { 'app.jar': 'v1' }),
    'frontend-h5': stageComponent(ctx, 'frontend-h5', { 'index.html': 'h5-v1' })
  })
  // 0.11.2 只更新 backend-app；frontend-h5 组件版本沿用 0.11.1
  overlay.activate(ctx, '0.11.2', {
    'backend-app': stageComponent(ctx, 'backend-app', { 'app.jar': 'v2' })
  })
  const cur = overlay.readCurrent(ctx)
  assert.strictEqual(cur.version, '0.11.2')
  assert.strictEqual(cur.components['backend-app'].version, '0.11.2')
  assert.strictEqual(cur.components['frontend-h5'].version, '0.11.1')
  assert.strictEqual(cur.previous.version, '0.11.1')
  assert.strictEqual(cur.previous.previous, undefined)
  const h5 = overlay.componentDir(ctx, 'frontend-h5')
  assert.strictEqual(fs.readFileSync(path.join(h5, 'index.html'), 'utf8'), 'h5-v1')
})

test('自愈回滚：连续两次后端启动失败回到上一版本，再失败回内置', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ovl-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  overlay.activate(ctx, '0.11.1', { 'backend-app': stageComponent(ctx, 'backend-app', { 'app.jar': 'v1' }) })
  overlay.activate(ctx, '0.11.2', { 'backend-app': stageComponent(ctx, 'backend-app', { 'app.jar': 'v2-bad' }) })

  assert.deepStrictEqual(overlay.noteBackendBootFailure(ctx), { reverted: false })
  assert.strictEqual(overlay.effectiveVersion(ctx), '0.11.2') // 一次失败还不动
  assert.deepStrictEqual(overlay.noteBackendBootFailure(ctx), { reverted: true })
  assert.strictEqual(overlay.effectiveVersion(ctx), '0.11.1') // 回滚到上一版本

  assert.deepStrictEqual(overlay.noteBackendBootFailure(ctx), { reverted: false })
  assert.deepStrictEqual(overlay.noteBackendBootFailure(ctx), { reverted: true })
  assert.strictEqual(overlay.effectiveVersion(ctx), '0.11.0') // 无 previous → 撤销 overlay 回内置
  assert.strictEqual(overlay.readCurrent(ctx), null)
})

test('markBootOk 清零计数并修剪无引用版本目录', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ovl-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  overlay.activate(ctx, '0.11.1', { 'backend-app': stageComponent(ctx, 'backend-app', { 'app.jar': 'v1' }) })
  overlay.activate(ctx, '0.11.2', { 'backend-app': stageComponent(ctx, 'backend-app', { 'app.jar': 'v2' }) })
  overlay.activate(ctx, '0.11.3', { 'backend-app': stageComponent(ctx, 'backend-app', { 'app.jar': 'v3' }) })
  overlay.noteBackendBootFailure(ctx)
  overlay.markBootOk(ctx)
  const cur = overlay.readCurrent(ctx)
  assert.strictEqual(cur.bootFailures, 0)
  const versions = fs.readdirSync(path.join(overlay.overlayRoot(ctx), 'backend-app')).sort()
  // current(0.11.3) + previous(0.11.2) 保留，0.11.1 修剪
  assert.deepStrictEqual(versions, ['0.11.2', '0.11.3'])
})

test('壳版本升级后：陈旧大版本与不高于壳版本的补丁全部清理', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ovl-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  // 0.11 时代留下的 overlay
  const oldCtx = makeCtx(root, '0.11.0')
  overlay.activate(oldCtx, '0.11.2', { 'backend-app': stageComponent(oldCtx, 'backend-app', { 'app.jar': 'v2' }) })

  // 大版本升级到 0.12：0.11 命名空间整体删除
  const newCtx = makeCtx(root, '0.12.0')
  overlay.cleanupStaleMajors(newCtx)
  assert.ok(!fs.existsSync(path.join(root, 'overlay', '0.11')))

  // 同大版本全量重装到 0.12.5，残留 0.12.3 补丁失效并被清理
  const midCtx = makeCtx(root, '0.12.0')
  overlay.activate(midCtx, '0.12.3', { 'backend-app': stageComponent(midCtx, 'backend-app', { 'app.jar': 'v3' }) })
  const reinstalled = makeCtx(root, '0.12.5')
  assert.strictEqual(overlay.readCurrent(reinstalled), null) // 读取层已拒绝
  overlay.cleanupStaleMajors(reinstalled)
  assert.ok(!fs.existsSync(path.join(root, 'overlay', '0.12', 'current.json')))
})

test('dev 态（packaged=false）overlay 永不生效', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ovl-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const ctx = makeCtx(root)
  overlay.activate(ctx, '0.11.1', { 'backend-app': stageComponent(ctx, 'backend-app', { 'app.jar': 'v1' }) })
  const devCtx = { ...ctx, packaged: false }
  assert.strictEqual(overlay.readCurrent(devCtx), null)
  assert.strictEqual(overlay.componentDir(devCtx, 'backend-app'), null)
})

test('backend-service javaLaunchArgs：split 布局 + overlay 覆盖 + fat 兼容', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ovl-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const { backendLayout, javaLaunchArgs } = require('../main/services/backend-service')
  const resources = path.join(root, 'resources')
  const ctx = { packaged: true, resourcesPath: resources, dataDir: root, appVersion: '0.11.0' }

  // 旧布局（fat jar）兼容
  fs.mkdirSync(path.join(resources, 'backend'), { recursive: true })
  fs.writeFileSync(path.join(resources, 'backend', 'backend.jar'), 'fat')
  assert.strictEqual(backendLayout(ctx).kind, 'fat')
  assert.deepStrictEqual(javaLaunchArgs(ctx), ['-jar', path.join(resources, 'backend', 'backend.jar')])

  // 新布局：-cp app.jar<sep>lib/* 主类直启
  fs.writeFileSync(path.join(resources, 'backend', 'app.jar'), 'builtin-app')
  fs.mkdirSync(path.join(resources, 'backend', 'lib'), { recursive: true })
  const args = javaLaunchArgs(ctx)
  const sep = process.platform === 'win32' ? ';' : ':'
  assert.strictEqual(args[0], '-cp')
  assert.strictEqual(args[1], path.join(resources, 'backend', 'app.jar') + sep + path.join(resources, 'backend', 'lib', '*'))
  assert.strictEqual(args[2], 'com.checkba.CheckbaApplication')

  // overlay 覆盖 app.jar
  const staged = stageComponent(ctx, 'backend-app', { 'app.jar': 'patched' })
  overlay.activate(ctx, '0.11.1', { 'backend-app': staged })
  const patched = backendLayout(ctx)
  assert.ok(patched.appJar.includes(path.join('overlay', '0.11', 'backend-app', '0.11.1')))
  // lib 永远指向内置
  assert.strictEqual(patched.libDir, path.join(resources, 'backend', 'lib'))
})

test('pysvc syncSrcPatch：应用/幂等/换版本/还原', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ovl-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const { syncSrcPatch } = require('../main/services/pysvc-runtime')
  const pysvcRoot = path.join(root, 'pysvc')
  fs.mkdirSync(path.join(pysvcRoot, 'kokoro-service', 'app'), { recursive: true })
  fs.writeFileSync(path.join(pysvcRoot, 'kokoro-service', 'app', 'app.py'), 'original')

  const patch1 = path.join(root, 'patch1')
  fs.mkdirSync(path.join(patch1, 'kokoro-service', 'app'), { recursive: true })
  fs.writeFileSync(path.join(patch1, 'kokoro-service', 'app', 'app.py'), 'patched-v1')
  fs.writeFileSync(path.join(patch1, 'kokoro-service', 'app', 'new.py'), 'brand-new')

  assert.deepStrictEqual(syncSrcPatch(pysvcRoot, patch1, '0.11.1'), { applied: true })
  assert.strictEqual(fs.readFileSync(path.join(pysvcRoot, 'kokoro-service', 'app', 'app.py'), 'utf8'), 'patched-v1')
  assert.strictEqual(fs.readFileSync(path.join(pysvcRoot, 'kokoro-service', 'app', 'new.py'), 'utf8'), 'brand-new')
  // 幂等：同版本不重复应用
  assert.deepStrictEqual(syncSrcPatch(pysvcRoot, patch1, '0.11.1'), { applied: false })

  // 换版本：先还原基线再应用（v1 的 new.py 不残留）
  const patch2 = path.join(root, 'patch2')
  fs.mkdirSync(path.join(patch2, 'kokoro-service', 'app'), { recursive: true })
  fs.writeFileSync(path.join(patch2, 'kokoro-service', 'app', 'app.py'), 'patched-v2')
  assert.deepStrictEqual(syncSrcPatch(pysvcRoot, patch2, '0.11.2'), { applied: true })
  assert.strictEqual(fs.readFileSync(path.join(pysvcRoot, 'kokoro-service', 'app', 'app.py'), 'utf8'), 'patched-v2')
  assert.ok(!fs.existsSync(path.join(pysvcRoot, 'kokoro-service', 'app', 'new.py')))

  // 补丁撤销：还原原件
  assert.deepStrictEqual(syncSrcPatch(pysvcRoot, null, null), { reverted: true })
  assert.strictEqual(fs.readFileSync(path.join(pysvcRoot, 'kokoro-service', 'app', 'app.py'), 'utf8'), 'original')
})
