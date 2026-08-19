#!/usr/bin/env node
/*
 * build-pack.js — 把一个 native pack 的组件打成 tar.gz + 产出未签名 manifest.json
 * （规范见 docs/NATIVE_PACK_DISTRIBUTION.md §2/§7.3）。
 *
 * 用法：
 *   node desktop/scripts/build-pack.js --id litigation-visual --version 1.0.0 \
 *     --out pack-out/mac --components litviz,drawio,graphviz
 *
 *   # 汇总 job：不在本机构建任何组件，只把别的平台产出的 manifest 合并进来
 *   node desktop/scripts/build-pack.js --id litigation-visual --version 1.0.0 \
 *     --out pack-out/final --merge pack-mac/manifest.json,pack-win/manifest.json
 *
 * 三个组件与其源目录（相对本仓库根，见 desktop/package.json 现有 extraResources）：
 *   litviz    <repo>/litviz                             平台无关
 *   drawio    <repo>/frontend/dist/drawio                平台无关，前置：fetch-drawio-assets.js
 *   graphviz  desktop/bundled/<os>-<arch>/graphviz        平台相关，前置：prepare-graphviz.js
 *
 * manifest 在这里**不签名**——签名私钥只在服务器侧（deploy/publish-pack.sh sign），
 * 不进 CI（NATIVE_PACK_DISTRIBUTION.md §1）。
 */
const fs = require('fs')
const os = require('os')
const path = require('path')
const crypto = require('crypto')
const { execFileSync } = require('child_process')

const REPO_ROOT = path.join(__dirname, '..', '..')
const DESKTOP_ROOT = path.join(__dirname, '..')

const MIN_APP_VERSION = '0.21.0'
const ENGINE_API = 1

function parseArgs() {
  const out = {}
  const argv = process.argv.slice(2)
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--') && i + 1 < argv.length) out[argv[i].slice(2)] = argv[++i]
  }
  for (const k of ['id', 'version', 'out']) {
    if (!out[k]) {
      console.error(`缺少 --${k}`)
      process.exit(1)
    }
  }
  return out
}

// 当前跑这个脚本的平台标签。native pack 目前只发两个目标，与
// desktop/bundled/${os}-${arch} 的命名一致（见 desktop/package.json extraResources）。
function platformTag() {
  if (process.platform === 'darwin' && process.arch === 'arm64') return 'mac-arm64'
  if (process.platform === 'win32' && process.arch === 'x64') return 'win-x64'
  throw new Error(`不支持的平台：${process.platform}-${process.arch}（native pack 目前只发 mac-arm64 / win-x64）`)
}

// ---------------------------------------------------------------------------
// 文件树遍历 + 哈希（用 stat 而非 lstat：跟随符号链接，与打包时 tar -h
// 物化软链的语义保持一致——两边算出来的应当是同一份「解析后」的内容）。
// ---------------------------------------------------------------------------

function sha256File(filePath) {
  const h = crypto.createHash('sha256')
  h.update(fs.readFileSync(filePath))
  return h.digest('hex')
}

// exclude: Array<(relPath, name, stat) => boolean>，命中任一条即跳过（目录命中则整棵子树跳过）
function listFiles(dir, exclude) {
  const out = []
  function walk(d, rel) {
    for (const name of fs.readdirSync(d).sort()) {
      const relPath = rel ? rel + '/' + name : name
      const abs = path.join(d, name)
      let st
      try {
        st = fs.statSync(abs) // 跟随符号链接；断链直接跳过
      } catch (e) {
        continue
      }
      if (exclude.some((fn) => fn(relPath, name, st))) continue
      if (st.isDirectory()) walk(abs, relPath)
      else if (st.isFile()) out.push(relPath)
    }
  }
  walk(dir, '')
  return out
}

// tar czh：-h 在归档时物化软链（写入被指向的真实内容），与 contents.sha256
// 用 fs.statSync/readFileSync（同样跟随符号链接）算出来的哈希对得上。
// 用系统 tar：win runner 自带 bsdtar 支持同一组短选项。
//
// **-T <文件清单> 而不是整目录打包**：清单来自 listFiles(srcDir, exclude)，
// 与 contents.sha256 用的是同一份文件列表——这样「哪些文件被排除」只有一处
// 判据，不会出现「哈希清单排除了 __pycache__，但 tar 整目录打包时忘了排除」
// 这种两条平行逻辑对不上的漂移。
//
// 包内顶层目录名固定为 topName（= manifest 的 unpackDir），**不依赖 srcDir 自身
// 的目录名**：现有三个组件恰好 srcDir 的 basename 就是组件名（litviz/drawio/
// graphviz），但显式控制更稳妥，也让打包函数可以对着任意名字的临时目录测试。
// 做法是建一个指向 srcDir、名字是 topName 的软链，清单里的路径写成
// topName/<relFile>，tar -h 经软链解析到真实内容。
function tarPack(srcDir, relFiles, archivePath, topName) {
  const linkParent = fs.mkdtempSync(path.join(os.tmpdir(), 'aiworkdeck-pack-link-'))
  const linkPath = path.join(linkParent, topName)
  const listPath = path.join(linkParent, '.tar-filelist')
  try {
    fs.symlinkSync(srcDir, linkPath, 'dir')
    fs.writeFileSync(listPath, relFiles.map((rel) => `${topName}/${rel}`).join('\n') + '\n')
    const env = { ...process.env }
    if (process.platform === 'darwin') env.COPYFILE_DISABLE = '1' // 防 AppleDouble（._ 文件）
    execFileSync('tar', ['-czhf', archivePath, '-C', linkParent, '-T', listPath], { stdio: 'inherit', env })
  } finally {
    fs.rmSync(linkParent, { recursive: true, force: true })
  }
}

function packComponent(ctx, { name, srcDir, exclude, archive, platforms, unpackDir }) {
  if (!fs.existsSync(srcDir)) throw new Error(`组件 ${name} 的源目录不存在：${srcDir}`)
  const topName = unpackDir || name
  const contentsRel = 'contents.sha256'
  const contentsPath = path.join(srcDir, contentsRel)
  fs.rmSync(contentsPath, { force: true }) // 防止上一次构建的残留干扰本次遍历/哈希

  const files = listFiles(srcDir, exclude)
  const lines = files.map((rel) => `${sha256File(path.join(srcDir, rel))}  ${rel}`)
  fs.writeFileSync(contentsPath, lines.join('\n') + '\n')
  try {
    const archivePath = path.join(ctx.outDir, archive)
    console.log(`打包 ${name} -> ${archivePath}（${files.length} 个文件）`)
    tarPack(srcDir, [...files, contentsRel], archivePath, topName)
    const st = fs.statSync(archivePath)
    return {
      name,
      platforms,
      archive,
      size: st.size,
      sha256: sha256File(archivePath),
      unpackDir: topName,
    }
  } finally {
    // 源树（尤其 litviz，是仓库里跟踪的源码目录）打包后必须恢复干净，不留生成物。
    fs.rmSync(contentsPath, { force: true })
  }
}

// ---------------------------------------------------------------------------
// 各组件
// ---------------------------------------------------------------------------

function buildLitviz(ctx) {
  const srcDir = path.join(REPO_ROOT, 'litviz')
  const exclude = [
    (relPath, name, st) => st.isDirectory() && name === '__pycache__',
    (relPath, name, st) => st.isFile() && name.endsWith('.pyc'),
  ]
  return packComponent(ctx, {
    name: 'litviz',
    srcDir,
    exclude,
    archive: `litviz-${ctx.version}.tar.gz`,
    platforms: ['*'],
  })
}

function buildDrawio(ctx) {
  const srcDir = path.join(REPO_ROOT, 'frontend', 'dist', 'drawio')
  if (!fs.existsSync(path.join(srcDir, 'index.html'))) {
    throw new Error(
      `draw.io 资源未就位（${srcDir}）。先跑：node desktop/scripts/fetch-drawio-assets.js`
    )
  }
  return packComponent(ctx, {
    name: 'drawio',
    srcDir,
    exclude: [],
    archive: `drawio-${ctx.version}.tar.gz`,
    platforms: ['*'],
  })
}

function buildGraphviz(ctx) {
  const plat = platformTag()
  const srcDir = path.join(DESKTOP_ROOT, 'bundled', plat, 'graphviz')
  if (!fs.existsSync(srcDir)) {
    throw new Error(
      `graphviz 未打包（${srcDir}）。先跑：node desktop/scripts/prepare-graphviz.js --out desktop/bundled/${plat}`
    )
  }
  return packComponent(ctx, {
    name: 'graphviz',
    srcDir,
    exclude: [],
    archive: `graphviz-${ctx.version}-${plat}.tar.gz`,
    platforms: [plat],
  })
}

const COMPONENT_BUILDERS = { litviz: buildLitviz, drawio: buildDrawio, graphviz: buildGraphviz }

// ---------------------------------------------------------------------------
// manifest 合并（汇总 job：mac 产的 litviz/drawio/graphviz-mac 与 win 产的
// graphviz-win 各自半成品 manifest 合并成一份全量 manifest）。
// ---------------------------------------------------------------------------

function componentKey(c) {
  return `${c.name}:${[...c.platforms].sort().join(',')}`
}

function mergeManifest(manifest, mergePath) {
  const raw = fs.readFileSync(mergePath, 'utf8')
  let other
  try {
    other = JSON.parse(raw)
  } catch (e) {
    throw new Error(`--merge 指向的文件不是合法 JSON：${mergePath}`)
  }
  if (other.id !== manifest.id || other.version !== manifest.version) {
    throw new Error(
      `--merge 的 manifest（${other.id}@${other.version}）与本次构建（${manifest.id}@${manifest.version}）不匹配：${mergePath}`
    )
  }
  const seen = new Set(manifest.components.map(componentKey))
  for (const c of other.components || []) {
    const key = componentKey(c)
    if (seen.has(key)) {
      console.warn(`  跳过重复组件 ${key}（已存在于当前 manifest，来自 ${mergePath}）`)
      continue
    }
    manifest.components.push(c)
    seen.add(key)
  }
  return manifest
}

// ---------------------------------------------------------------------------

function main() {
  const args = parseArgs()
  const outDir = path.resolve(args.out)
  fs.mkdirSync(outDir, { recursive: true })
  const ctx = { id: args.id, version: args.version, outDir }

  const componentNames = (args.components || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)

  const components = []
  for (const name of componentNames) {
    const builder = COMPONENT_BUILDERS[name]
    if (!builder) throw new Error(`未知组件：${name}（可选 ${Object.keys(COMPONENT_BUILDERS).join('/')}）`)
    components.push(builder(ctx))
  }

  let manifest = {
    schema: 1,
    id: ctx.id,
    version: ctx.version,
    publishedAt: new Date().toISOString(),
    minAppVersion: MIN_APP_VERSION,
    engineApi: ENGINE_API,
    components,
  }

  if (args.merge) {
    for (const mergePath of args.merge.split(',').map((s) => s.trim()).filter(Boolean)) {
      console.log(`合并 manifest：${mergePath}`)
      manifest = mergeManifest(manifest, mergePath)
    }
  }

  if (manifest.components.length === 0) {
    throw new Error('manifest 没有任何组件：既没有 --components 构建任何东西，--merge 也没带来组件')
  }

  const manifestPath = path.join(outDir, 'manifest.json')
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n')
  console.log(`manifest（未签名）写入：${manifestPath}`)
  for (const c of manifest.components) {
    console.log(
      `  - ${c.name} [${c.platforms.join(',')}] ${c.archive}  ${(c.size / 1024 / 1024).toFixed(2)} MB  sha256=${c.sha256.slice(0, 12)}…`
    )
  }
}

// CLI 入口只在直接执行本文件时跑；被 require() 当模块用时（单测）只取函数，
// 不触发任何文件系统写入或子进程调用。
if (require.main === module) {
  try {
    main()
  } catch (e) {
    console.error('build-pack 失败:', e.message)
    process.exit(1)
  }
}

module.exports = { packComponent, sha256File, listFiles, mergeManifest, componentKey, platformTag }
