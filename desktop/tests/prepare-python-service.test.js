const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const { isPythonRuntimeComplete, pythonBin, pythonMarker } = require('../scripts/prepare-python-service')

// dev-board#74 稳定性审计：ensurePython() 原来的缓存判据只看 python3.11 二进制在不在。
// tar 中途被打断（Ctrl-C/OOM-kill/磁盘满，或 CI 任务被取消/重跑但 workspace 保留）时，
// bin/python3.11 可能已经落地，但 stdlib/site-packages 支撑文件还没解压完——下一次调用
// 会把这个残缺运行时当"已经装好"直接复用，打包出一个每个 Python 子服务启动即崩的安装包。
// isPythonRuntimeComplete() 现在还要求一个"整个下载+解压流程最后一步才写"的 marker 文件
// 存在，被打断的流程不可能留下它。

function mkTmpOut() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'prepare-python-service-test-'))
}

test('全新目录（从未装过）判定为不完整', () => {
  const outDir = mkTmpOut()
  try {
    const pyRoot = path.join(outDir, 'python')
    assert.strictEqual(isPythonRuntimeComplete(pyRoot), false)
  } finally {
    fs.rmSync(outDir, { recursive: true, force: true })
  }
})

test('tar 被打断的现场（二进制落地但 marker 没写）必须判定为不完整', () => {
  const outDir = mkTmpOut()
  try {
    const pyRoot = path.join(outDir, 'python')
    // 复现"被打断"的现场：只有 bin/python3.11 落地，marker 文件不存在
    // （tar 是不是恰好先写出这一个文件不重要，关键是 marker 只在全部完成后才写）
    fs.mkdirSync(path.dirname(pythonBin(pyRoot)), { recursive: true })
    fs.writeFileSync(pythonBin(pyRoot), '')
    assert.strictEqual(fs.existsSync(pythonMarker(pyRoot)), false, '前置条件：marker 不存在')
    assert.strictEqual(isPythonRuntimeComplete(pyRoot), false,
      '只有二进制、没有 marker 时必须判定为不完整——这正是旧实现会误判为"已装好"的场景')
  } finally {
    fs.rmSync(outDir, { recursive: true, force: true })
  }
})

test('真正跑完一轮（二进制 + marker 都在）判定为完整，不会白白重装', () => {
  const outDir = mkTmpOut()
  try {
    const pyRoot = path.join(outDir, 'python')
    fs.mkdirSync(path.dirname(pythonBin(pyRoot)), { recursive: true })
    fs.writeFileSync(pythonBin(pyRoot), '')
    fs.writeFileSync(pythonMarker(pyRoot), '')
    assert.strictEqual(isPythonRuntimeComplete(pyRoot), true)
  } finally {
    fs.rmSync(outDir, { recursive: true, force: true })
  }
})

test('只有 marker、二进制缺失（比如运行时被误删）也必须判定为不完整', () => {
  const outDir = mkTmpOut()
  try {
    const pyRoot = path.join(outDir, 'python')
    fs.mkdirSync(pyRoot, { recursive: true })
    fs.writeFileSync(pythonMarker(pyRoot), '')
    assert.strictEqual(isPythonRuntimeComplete(pyRoot), false)
  } finally {
    fs.rmSync(outDir, { recursive: true, force: true })
  }
})
