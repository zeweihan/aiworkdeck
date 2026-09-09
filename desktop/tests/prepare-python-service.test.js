// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const { isPythonRuntimeComplete, pythonBin, pythonMarker, prune } = require('../scripts/prepare-python-service')

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

// ---- prune() 体积裁剪（安装包瘦身 dev-board#528）----
// pysvc.tar.gz 796MB（解压 2.9GB）里有一大块是运行期永远不会被读到的东西：
// torch 的 C++ 头文件、magika/ruff 两个独立可执行文件、pocketsphinx 的离线声学模型、
// 各包自带的测试套件、gradio 的前端 sourcemap。这组用例守两头：该删的删了，
// 以及**该留的一个都没少**——删错一样东西的代价是整个服务起不来，而这只会在
// CI 冒烟或用户机上才暴露。

function mkFile(p, content = 'x') {
  fs.mkdirSync(path.dirname(p), { recursive: true })
  fs.writeFileSync(p, content)
}

/** 造一棵有代表性的 site-packages：既有该删的，也有长得很像但必须留下的。 */
function makeLibTree(libDir) {
  const f = (...seg) => path.join(libDir, ...seg)
  // 删：torch 的非运行时目录
  mkFile(f('torch', 'include', 'ATen', 'ATen.h'))
  mkFile(f('torch', 'test', 'test_api.cpp'))
  mkFile(f('torch', 'share', 'cmake', 'Torch', 'TorchConfig.cmake'))
  // 留：torch 本体
  mkFile(f('torch', 'lib', 'libtorch_cpu.dylib'))
  mkFile(f('torch', '__init__.py'))
  // 留：torch/testing 是 torch/__init__.py 里 `testing as testing` 那一行的目标
  mkFile(f('torch', 'testing', '__init__.py'))
  // 删：bin/ 里两个独立可执行文件；留：其余入口脚本
  mkFile(f('bin', 'magika'))
  mkFile(f('bin', 'ruff'))
  mkFile(f('bin', 'magika.exe'))
  mkFile(f('bin', 'uvicorn'))
  mkFile(f('bin', 'mineru'))
  // 删：pocketsphinx 离线模型；留：speech_recognition 本体
  mkFile(f('speech_recognition', 'pocketsphinx-data', 'en-US', 'language-model.lm.bin'))
  mkFile(f('speech_recognition', '__init__.py'))
  // 删：一级包下的测试套件
  mkFile(f('pandas', 'tests', 'test_frame.py'))
  mkFile(f('pandas', '__init__.py'))
  mkFile(f('youtube_transcript_api', 'test', 'test_api.py'))
  // 留：testing/ 是对外 API，不许碰
  mkFile(f('numpy', 'testing', '__init__.py'))
  mkFile(f('sqlalchemy', 'testing', 'plugin.py'))
  // 留：嵌套在包内部的 tests/（只扫一级，不递归乱删）
  mkFile(f('sympy', 'core', 'tests', 'test_expr.py'))
  // 删：gradio 的 sourcemap；留：它旁边的 js 本体
  mkFile(f('gradio', 'templates', 'node', 'build', 'PlotlyPlot.js.map'))
  mkFile(f('gradio', 'templates', 'node', 'build', 'PlotlyPlot.js'))
  mkFile(f('gradio', '__init__.py'))
  // 留：dist-info 全套，尤其 RECORD
  mkFile(f('torch-2.12.1.dist-info', 'RECORD'))
  mkFile(f('torch-2.12.1.dist-info', 'METADATA'))
  // 删：老规矩，字节码缓存与 flac 转码器
  mkFile(f('pandas', '__pycache__', '__init__.cpython-311.pyc'))
  mkFile(f('speech_recognition', 'flac-mac'))
}

test('prune() 删掉运行期用不到的东西，且一个该留的都没少', () => {
  const outDir = mkTmpOut()
  try {
    const libDir = path.join(outDir, 'lib')
    makeLibTree(libDir)
    const exists = (...seg) => fs.existsSync(path.join(libDir, ...seg))

    // 前置条件：还原病灶——所有待删项此刻确实在场，否则这条用例证明不了任何事
    for (const gone of [
      ['torch', 'include', 'ATen', 'ATen.h'],
      ['bin', 'magika'],
      ['gradio', 'templates', 'node', 'build', 'PlotlyPlot.js.map'],
      ['pandas', 'tests', 'test_frame.py']
    ]) assert.ok(exists(...gone), `前置条件：${gone.join('/')} 应当存在`)

    prune(libDir)

    // 该删的
    assert.ok(!exists('torch', 'include'), 'torch/include')
    assert.ok(!exists('torch', 'test'), 'torch/test')
    assert.ok(!exists('torch', 'share'), 'torch/share')
    assert.ok(!exists('bin', 'magika'), 'bin/magika')
    assert.ok(!exists('bin', 'magika.exe'), 'bin/magika.exe')
    assert.ok(!exists('bin', 'ruff'), 'bin/ruff')
    assert.ok(!exists('speech_recognition', 'pocketsphinx-data'), 'pocketsphinx-data')
    assert.ok(!exists('pandas', 'tests'), 'pandas/tests')
    assert.ok(!exists('youtube_transcript_api', 'test'), 'youtube_transcript_api/test')
    assert.ok(!exists('gradio', 'templates', 'node', 'build', 'PlotlyPlot.js.map'), 'gradio sourcemap')
    assert.ok(!exists('pandas', '__pycache__'), '__pycache__')
    assert.ok(!exists('speech_recognition', 'flac-mac'), 'flac-mac')

    // 该留的（删错这里任何一条，服务就起不来了）
    assert.ok(exists('torch', 'lib', 'libtorch_cpu.dylib'), 'torch/lib 是 libtorch 本体')
    assert.ok(exists('torch', '__init__.py'), 'torch/__init__.py')
    assert.ok(exists('torch', 'testing', '__init__.py'),
      'torch/testing 被 torch/__init__.py 直接 import，删掉 import torch 就炸')
    assert.ok(exists('numpy', 'testing', '__init__.py'), 'numpy.testing 是对外 API')
    assert.ok(exists('sqlalchemy', 'testing', 'plugin.py'), 'sqlalchemy.testing 是对外 API')
    assert.ok(exists('sympy', 'core', 'tests', 'test_expr.py'), '只扫一级包，不递归删嵌套 tests/')
    assert.ok(exists('bin', 'uvicorn') && exists('bin', 'mineru'), 'bin/ 里的入口脚本')
    assert.ok(exists('speech_recognition', '__init__.py'), 'speech_recognition 本体')
    assert.ok(exists('gradio', 'templates', 'node', 'build', 'PlotlyPlot.js'), 'gradio 的 js 本体')
    assert.ok(exists('gradio', '__init__.py'), 'gradio 本体')
    assert.ok(exists('torch-2.12.1.dist-info', 'RECORD'),
      'dist-info/RECORD 一律不动——importlib.metadata 与后续 pip 操作都要它')
    assert.ok(exists('torch-2.12.1.dist-info', 'METADATA'), 'dist-info/METADATA')
  } finally {
    fs.rmSync(outDir, { recursive: true, force: true })
  }
})

test('prune() 对没有这些目录的精简树也能跑完（asr 那种小依赖集）', () => {
  const outDir = mkTmpOut()
  try {
    const libDir = path.join(outDir, 'lib')
    mkFile(path.join(libDir, 'faster_whisper', '__init__.py'))
    prune(libDir)
    assert.ok(fs.existsSync(path.join(libDir, 'faster_whisper', '__init__.py')))
  } finally {
    fs.rmSync(outDir, { recursive: true, force: true })
  }
})

// ---- 按服务的裁剪表（dev-board#529 Task 1b）----
// mineru 的 requirements.in 是 mineru[core]，把整个 Gradio Web UI 拖进 lock；
// 服务只跑 `-m mineru.cli.fast_api`，Web UI 从不启动（已在一份真实的 mineru lib 上
// 实测 import mineru.cli.fast_api 之后 sys.modules 里没有任何 gradio* 模块）。
// 这条只对 mineru 生效：别的服务里的 gradio 一律不碰。

test('prune(libDir, "mineru-service") 删掉 gradio 三件套；其它服务不动 gradio', () => {
  const root = mkTmpOut()
  try {
    for (const svc of ['mineru-service', 'pptx-service']) {
      const lib = path.join(root, svc)
      for (const d of ['gradio', 'gradio_client', 'gradio_pdf', 'mineru']) {
        fs.mkdirSync(path.join(lib, d), { recursive: true })
        fs.writeFileSync(path.join(lib, d, '__init__.py'), '')
      }
      prune(lib, svc)
    }
    for (const d of ['gradio', 'gradio_client', 'gradio_pdf']) {
      assert.ok(!fs.existsSync(path.join(root, 'mineru-service', d)), `mineru 仍有 ${d}`)
      assert.ok(fs.existsSync(path.join(root, 'pptx-service', d)), `pptx 的 ${d} 不该被动`)
    }
    assert.ok(fs.existsSync(path.join(root, 'mineru-service', 'mineru')))
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})
