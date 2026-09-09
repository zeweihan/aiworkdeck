#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/*
 * 烙制"共享 Python 运行时 + 单服务 site-packages + 服务源码"进 desktop/bundled/，
 * 供 electron-builder extraResources 打包（对标 prepare-backend.js 的 jar+JRE 链路）。
 *
 * 用法：
 *   node scripts/prepare-python-service.js \
 *     --service pptx-service \
 *     --src ../pptx-service/backend \
 *     --requirements ../pptx-service/requirements.lock \
 *     --out bundled/mac-arm64
 *
 * 平台按构建宿主原生解析（mac 仅支持 Apple Silicon——2026-07-03 决策放弃 Intel Mac，
 * 因 onnxruntime/pikepdf 等依赖已停发 x86_64 wheel，交叉烙制不可持续）。
 * 共享运行时：同一 out 目录下多次调用只下载/解压一次 python/。
 */
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')

const PBS_RELEASE = '20250409'
const PY_VERSION = '3.11.12'

function pbsTriple() {
  if (process.platform === 'darwin') return 'aarch64-apple-darwin'
  if (process.platform === 'win32') return 'x86_64-pc-windows-msvc'
  return 'x86_64-unknown-linux-gnu'
}

function parseArgs() {
  const out = {}
  const argv = process.argv.slice(2)
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      const key = argv[i].slice(2)
      if (i + 1 < argv.length && !argv[i + 1].startsWith('--')) {
        out[key] = argv[++i]
      } else {
        out[key] = true
      }
    }
  }
  // --src 可选：mineru 这类纯 pip 包服务没有自有源码，只烙依赖
  for (const k of ['service', 'requirements', 'out']) {
    if (!out[k]) {
      console.error(`missing --${k}`)
      process.exit(1)
    }
  }
  return out
}

function pythonBin(pyRoot) {
  return process.platform === 'win32'
    ? path.join(pyRoot, 'python.exe')
    : path.join(pyRoot, 'bin', 'python3.11')
}

function pythonMarker(pyRoot) {
  return path.join(pyRoot, '.pbs-extract-complete')
}

// 缓存判据不能只看 python3.11 二进制在不在：tar 中途被打断（本地 Ctrl-C、OOM-kill、
// 磁盘满，或 CI 任务被取消/重跑但 workspace 保留）时，bin/python3.11 可能已经落地，
// 但 stdlib/site-packages 支撑文件还没解压完就没了——下一次调用如果只查这一个文件，
// 会当"已经装好"直接复用这个残缺运行时，打包出一个每个 Python 子服务启动即崩的安装包。
// marker 文件是整个下载+解压成功之后最后一步才写的：被打断的流程不可能留下它，
// 用它而不是"多查几个文件"是因为查再多文件也只是缩小遗漏窗口，marker 才是
// "流程完整跑完"本身的证明。
function isPythonRuntimeComplete(pyRoot) {
  return fs.existsSync(pythonBin(pyRoot)) && fs.existsSync(pythonMarker(pyRoot))
}

function ensurePython(outDir) {
  const pyRoot = path.join(outDir, 'python')
  if (isPythonRuntimeComplete(pyRoot)) {
    console.log(`python runtime already present: ${pyRoot}`)
    return pyRoot
  }
  // 缓存判无效（首次 / 上次被打断过）：整个目录清掉重来，避免半成品残留文件与
  // 本次新解压的文件混在一起，产出一个"部分新、部分旧"的更难排查的运行时
  fs.rmSync(pyRoot, { recursive: true, force: true })
  const triple = pbsTriple()
  const name = `cpython-${PY_VERSION}+${PBS_RELEASE}-${triple}-install_only.tar.gz`
  const url = process.env.PBS_BASE_URL
    ? `${process.env.PBS_BASE_URL}/${name}`
    : `https://github.com/astral-sh/python-build-standalone/releases/download/${PBS_RELEASE}/${name}`
  const tarball = path.join(outDir, name)
  fs.mkdirSync(outDir, { recursive: true })
  console.log(`downloading ${url}`)
  execFileSync('curl', ['-fSL', '--retry', '3', '-o', tarball, url], { stdio: 'inherit' })
  // install_only 包解压即得顶层 python/ 目录。
  // cwd + 相对文件名：Windows 上 GNU tar 会把 "D:\..." 的冒号当远程主机（host:file 语法）
  execFileSync('tar', ['-xzf', name], { cwd: outDir, stdio: 'inherit' })
  fs.rmSync(tarball)
  if (!fs.existsSync(pythonBin(pyRoot))) {
    console.error(`unexpected layout after extract: ${pyRoot}`)
    process.exit(1)
  }
  fs.writeFileSync(pythonMarker(pyRoot), '')
  return pyRoot
}

function installDeps(pyRoot, requirements, libDir) {
  fs.rmSync(libDir, { recursive: true, force: true })
  fs.mkdirSync(libDir, { recursive: true })
  execFileSync(pythonBin(pyRoot), ['-m', 'pip', 'install', '--no-compile', '--target', libDir, '-r', requirements], { stdio: 'inherit' })
}

function copyAppSource(srcDir, appDir) {
  fs.rmSync(appDir, { recursive: true, force: true })
  const EXCLUDES = new Set(['tests', 'instance', '__pycache__', '.pytest_cache', 'Dockerfile', 'run.bat', 'run.sh'])
  fs.cpSync(srcDir, appDir, {
    recursive: true,
    filter: (src) => !EXCLUDES.has(path.basename(src))
  })
}

// 包内固定路径的非运行时目录（安装包瘦身 dev-board#528）。
// torch/include 是 C++ 扩展的头文件（61MB×两个服务），只有 torch.utils.cpp_extension
// 现场编译扩展才要；torch/test 是 C++ 单测；torch/share 是 cmake 配置。
// **torch/lib 不在表内**——那是 libtorch 本体，删了 import torch 直接完蛋。
// speech_recognition/pocketsphinx-data 是离线英文声学模型（37MB），本仓走的是
// 听悟/faster-whisper 两条转写通道，pocketsphinx 一行代码都没调。
const PRUNE_PATHS = [
  'torch/include',
  'torch/test',
  'torch/share',
  'speech_recognition/pocketsphinx-data'
]

// bin/（pip --target 的 console_scripts 落点）里两个独立可执行文件：
// magika 27MB 是 Rust CLI（markitdown 走的是 `import magika` 的 Python API，
// 不 fork 这个二进制）、ruff 24MB 是 lint 工具，运行期都不会被调用。
// 其余 bin/ 条目是几百字节的 Python 入口脚本，留着不占地方也免得误伤。
const PRUNE_BIN_EXECUTABLES = ['magika', 'ruff']

/**
 * 逐个一级包目录下的测试套件。**只删 tests/ 与 test/，不删 testing/**：
 * torch/__init__.py 里有一行 `testing as testing`（torch 2.12 的 :2317），
 * 删掉 torch/testing 会让 `import torch` 当场炸掉，kokoro 与 mineru 一起死；
 * numpy.testing / sqlalchemy.testing / sympy.testing 同样是对外 API。
 * 这一类加起来只有约 12MB，不值得为它冒整条服务起不来的险。
 */
const PRUNE_PKG_TEST_DIRS = ['tests', 'test']

/**
 * 按服务的裁剪表（dev-board#529）。mineru 的 requirements.in 是 `mineru[core]`，
 * 把整个 Gradio Web UI 拖进 lock（P1 删完 *.js.map 之后仍约 80MB 未压缩）；
 * 服务只跑 `-m mineru.cli.fast_api`，Web UI 从不启动——已在真实的 mineru lib 上
 * 实测 `import mineru.cli.fast_api` 之后 sys.modules 里没有任何 gradio* 模块。
 * 只对表里点名的服务生效，别的服务的同名包一律不碰。
 */
const PRUNE_BY_SERVICE = {
  'mineru-service': ['gradio', 'gradio_client', 'gradio_pdf']
}

function rmIfExists(p) {
  if (fs.existsSync(p)) fs.rmSync(p, { recursive: true, force: true })
}

function prune(libDir, service) {
  // 体积裁剪：字节码缓存（保守起见不动 dist-info——pip/importlib.metadata 需要，
  // 尤其是 RECORD：删了它 importlib.metadata 的 files() 与后续 pip 操作都会瞎）
  const stack = [libDir]
  while (stack.length) {
    const dir = stack.pop()
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, entry.name)
      if (!entry.isDirectory()) continue
      if (entry.name === '__pycache__') fs.rmSync(p, { recursive: true, force: true })
      else stack.push(p)
    }
  }
  // SpeechRecognition 自带的预编译 flac 转码器（flac-mac 用 <10.9 SDK 构建）会被
  // Apple 公证整包拒绝；音频转写不在 AI PPT 链路上，直接剔除（缺了它只在真正调用
  // 音频转 FLAC 时才会抛错）
  const srDir = path.join(libDir, 'speech_recognition')
  if (fs.existsSync(srDir)) {
    for (const entry of fs.readdirSync(srDir)) {
      if (entry.startsWith('flac-')) fs.rmSync(path.join(srDir, entry), { recursive: true, force: true })
    }
  }
  for (const rel of PRUNE_PATHS) rmIfExists(path.join(libDir, ...rel.split('/')))
  // pip --target 在 POSIX 上把入口脚本放 bin/，Windows 上放 Scripts/
  for (const binDir of ['bin', 'Scripts']) {
    for (const name of PRUNE_BIN_EXECUTABLES) {
      rmIfExists(path.join(libDir, binDir, name))
      rmIfExists(path.join(libDir, binDir, `${name}.exe`))
    }
  }
  for (const entry of fs.readdirSync(libDir, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name.endsWith('.dist-info')) continue
    for (const name of PRUNE_PKG_TEST_DIRS) rmIfExists(path.join(libDir, entry.name, name))
  }
  // gradio 的前端产物带着 1000 多个 sourcemap（mineru 实测 118MB）。mineru 走的是
  // `-m mineru.cli.fast_api`，gradio 的 Web UI 根本不启动，何况 sourcemap 只服务浏览器
  // devtools，删掉对任何运行路径都没有影响。
  const gradioDir = path.join(libDir, 'gradio')
  if (fs.existsSync(gradioDir)) {
    const gstack = [gradioDir]
    while (gstack.length) {
      const dir = gstack.pop()
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, entry.name)
        if (entry.isDirectory()) gstack.push(p)
        else if (entry.name.endsWith('.js.map')) fs.rmSync(p, { force: true })
      }
    }
  }
  // 按服务的整包裁剪放在最后：上面的通用规则先跑完，这里只做点名删除。
  // service 为空（老调用方/单测）时不走这张表，行为与 P1 完全一致。
  for (const rel of PRUNE_BY_SERVICE[service] || []) rmIfExists(path.join(libDir, rel))
}

function main() {
  const args = parseArgs()
  const outDir = path.resolve(args.out)
  const pyRoot = ensurePython(outDir)
  const svcDir = path.join(outDir, 'pysvc', args.service)
  const libDir = path.join(svcDir, 'lib')
  const appDir = path.join(svcDir, 'app')
  installDeps(pyRoot, path.resolve(args.requirements), libDir)
  if (args.src) copyAppSource(path.resolve(args.src), appDir)
  prune(libDir, args.service)
  console.log(`bundled ${args.service}:`)
  console.log(`  runtime: ${pyRoot}`)
  console.log(`  lib:     ${libDir}`)
  console.log(`  app:     ${appDir}`)
}

// require.main 判断：直接执行（node prepare-python-service.js / 构建流水线调用）时
// 行为不变，照旧跑 main() 真下载真解压；被测试文件 require() 拿 isPythonRuntimeComplete
// 时不触发下载/解压副作用（同 fetch-lowa-assets.js 的 checkMagic 那套用法）。
if (require.main === module) {
  main()
}

module.exports = { isPythonRuntimeComplete, pythonBin, pythonMarker, prune }
