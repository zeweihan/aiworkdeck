#!/usr/bin/env node
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
  for (const k of ['service', 'src', 'requirements', 'out']) {
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

function ensurePython(outDir) {
  const pyRoot = path.join(outDir, 'python')
  if (fs.existsSync(pythonBin(pyRoot))) {
    console.log(`python runtime already present: ${pyRoot}`)
    return pyRoot
  }
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

function prune(libDir) {
  // 体积裁剪：字节码缓存（保守起见不动 dist-info——pip/importlib.metadata 需要）
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
}

function main() {
  const args = parseArgs()
  const outDir = path.resolve(args.out)
  const pyRoot = ensurePython(outDir)
  const svcDir = path.join(outDir, 'pysvc', args.service)
  const libDir = path.join(svcDir, 'lib')
  const appDir = path.join(svcDir, 'app')
  installDeps(pyRoot, path.resolve(args.requirements), libDir)
  copyAppSource(path.resolve(args.src), appDir)
  prune(libDir)
  console.log(`bundled ${args.service}:`)
  console.log(`  runtime: ${pyRoot}`)
  console.log(`  lib:     ${libDir}`)
  console.log(`  app:     ${appDir}`)
}

main()
