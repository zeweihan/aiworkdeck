#!/usr/bin/env node
/*
 * 烙制一份最小可用的 graphviz 进 desktop/bundled/<target>/graphviz/，
 * 供 electron-builder 打包（对标 prepare-python-service.js）。
 *
 * 用法：
 *   node scripts/prepare-graphviz.js --from /opt/homebrew/opt/graphviz --out bundled/mac-arm64
 *   node scripts/prepare-graphviz.js --from "C:\Program Files\Graphviz" --out bundled/win-x64
 *
 * --from 指向一份已安装好的 graphviz（CI 里 macOS 用 `brew install graphviz`、
 * Windows 用 `choco install graphviz`）。**不内置下载地址**：graphviz 官方的
 * 分发地址在各平台上形态不一且改过，把 URL 焊进构建脚本，等它哪天变了，
 * CI 会在某次无关的构建里突然红，且错误信息与真正的原因隔着十万八千里。
 *
 * ── 为什么只要这么点东西 ──
 * 诉讼可视化只用 `dot -Tplain`，也就是**只要布局引擎，不要任何渲染后端**。
 * 于是 pango / cairo / gd / rsvg / webp / quartz 这些重量级插件全都不需要——
 * 它们才是 graphviz 安装包的绝大部分体积。实际闭包不到 1 MB。
 *
 * 顺带一提：七种布局里**只有流程图**要 dot。关系网络图虽然叫 graphviz_relation，
 * v1.0.2 起已换成确定性的无 graphviz 布局（见 litviz/README.md）。
 */
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')

const IS_WIN = process.platform === 'win32'

// -Tplain 需要的插件：core 提供 plain 这个输出设备，dot/neato 提供布局引擎。
// （neato 留着是因为引擎在拓扑复杂时会切换引擎；少一个就会在某些图上突然失败。）
const NEEDED_PLUGINS = ['core', 'dot_layout', 'neato_layout']

function parseArgs() {
  const out = {}
  const argv = process.argv.slice(2)
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--') && i + 1 < argv.length) out[argv[i].slice(2)] = argv[++i]
  }
  if (!out.out) {
    console.error('missing --out')
    process.exit(1)
  }
  return out
}

/**
 * 定位一份已安装的 graphviz。
 *
 * `--from` 是可选的：**不传（或传的路径不存在）时从 PATH 上找 dot 反推安装根**。
 * 这不是图省事——在 Windows 的 CI 上，步骤跑在 `shell: bash` 里，写出来的
 * `/c/Program Files/Graphviz` 是 git-bash 风格路径，而 Node 不认它，
 * `path.resolve` 会解成 `C:\c\Program Files\Graphviz`。装包工具（choco/brew）
 * 本来就会把 dot 放进 PATH，从 PATH 反推既绕开了路径风格问题，
 * 也不必把各平台的安装目录硬编码进构建脚本。
 */
function resolveSourceDir(explicit) {
  if (explicit) {
    const p = path.resolve(explicit)
    if (fs.existsSync(path.join(p, 'bin')) || fs.existsSync(path.join(p, 'dot.exe'))) return p
    console.warn(`--from 指向的位置不像 graphviz 安装根（${p}），改从 PATH 上找 dot`)
  }
  let where
  try {
    where = execFileSync(IS_WIN ? 'where' : 'which', ['dot'], { encoding: 'utf8' })
  } catch (e) {
    throw new Error('PATH 上没有 dot，且 --from 也没给出可用路径。'
      + '先装 graphviz（macOS: brew install graphviz / Windows: choco install graphviz）')
  }
  const dot = fs.realpathSync(where.split(/\r?\n/).find((l) => l.trim())?.trim())
  // <root>/bin/dot -> <root>
  return path.dirname(path.dirname(dot))
}

function findFirstDir(base, candidates) {
  for (const c of candidates) {
    const p = path.join(base, c)
    if (fs.existsSync(p)) return p
  }
  return null
}

// otool -L 的传递闭包，跳过系统库（/usr/lib、/System 由 OS 提供，不能也不该带走）
function macDeps(binary, seen = new Set()) {
  if (seen.has(binary)) return []
  seen.add(binary)
  let txt
  try {
    txt = execFileSync('otool', ['-L', binary], { encoding: 'utf8' })
  } catch (e) {
    return []
  }
  const out = []
  for (const line of txt.split('\n').slice(1)) {
    const p = line.trim().split(' ')[0]
    if (!p || p.startsWith('/usr/lib') || p.startsWith('/System')) continue
    let real
    try { real = fs.realpathSync(p) } catch (e) { continue }
    if (seen.has(real)) continue
    out.push(real, ...macDeps(real, seen))
  }
  return out
}

/**
 * 把 Mach-O 的依赖路径改写成 @loader_path 相对引用。
 *
 * 不做这一步，打出来的 dot 仍然指着构建机的 /opt/homebrew/...，
 * 在用户机器上一启动就是 "image not found"——而且这种失败只在**没装 homebrew 的
 * 干净机器**上出现，构建机自测永远是绿的。
 */
function relocate(file, libDirRelative) {
  const deps = execFileSync('otool', ['-L', file], { encoding: 'utf8' })
  for (const line of deps.split('\n').slice(1)) {
    const p = line.trim().split(' ')[0]
    if (!p || p.startsWith('/usr/lib') || p.startsWith('/System') || p.startsWith('@')) continue
    const base = path.basename(p)
    execFileSync('install_name_tool',
      ['-change', p, `@loader_path/${libDirRelative}/${base}`, file])
  }
  // 自身 id 也换掉，免得别的库按旧绝对路径找它
  if (file.endsWith('.dylib')) {
    execFileSync('install_name_tool', ['-id', `@loader_path/${path.basename(file)}`, file])
  }
  // install_name_tool 改过的 Mach-O，原来的签名立刻失效，Apple Silicon 上内核
  // 直接 SIGKILL（不是报错、不是弹窗，是进程凭空消失）。必须补一个 ad-hoc 签名。
  // CI 里正式签名在 sign-mac-natives.sh 那一步会再覆盖一次，这里只保证可执行。
  execFileSync('codesign', ['--force', '--sign', '-', '--timestamp=none', file],
    { stdio: 'ignore' })
}

function copyInto(dir, src) {
  fs.mkdirSync(dir, { recursive: true })
  const dest = path.join(dir, path.basename(src))
  fs.copyFileSync(src, dest)
  fs.chmodSync(dest, 0o755)
  return dest
}

function prepareMac(fromDir, outRoot) {
  const srcBin = path.join(fromDir, 'bin', 'dot')
  if (!fs.existsSync(srcBin)) throw new Error(`找不到 dot：${srcBin}`)
  const srcPluginDir = findFirstDir(fromDir, ['lib/graphviz'])
  if (!srcPluginDir) throw new Error(`找不到插件目录：${fromDir}/lib/graphviz`)

  const binDir = path.join(outRoot, 'bin')
  const libDir = path.join(outRoot, 'lib')
  const pluginDir = path.join(libDir, 'graphviz')

  const dot = copyInto(binDir, fs.realpathSync(srcBin))
  const libs = [...new Set(macDeps(fs.realpathSync(srcBin)))].map((p) => copyInto(libDir, p))

  for (const name of NEEDED_PLUGINS) {
    // 取带版本号的那个真身（libgvplugin_core.8.dylib），不取符号链接
    const hit = fs.readdirSync(srcPluginDir)
      .filter((f) => f.startsWith(`libgvplugin_${name}.`) && f.endsWith('.dylib'))
      .map((f) => fs.realpathSync(path.join(srcPluginDir, f)))
    if (!hit.length) throw new Error(`缺少插件 libgvplugin_${name}`)
    const plugin = copyInto(pluginDir, hit[0])
    libs.push(...[...new Set(macDeps(hit[0]))].map((p) => copyInto(libDir, p)))
    relocate(plugin, '..')            // plugin 在 lib/graphviz/，dylib 在 lib/
  }

  relocate(dot, '../lib')             // dot 在 bin/，dylib 在 lib/
  for (const l of [...new Set(libs)]) relocate(l, '.')

  // config8 必须重新生成：源文件登记的是整套插件（pango/gd/quartz…），
  // 照抄过来 dot 启动时会去 dlopen 一堆我们没带的库。让**打包后的** dot
  // 自己扫一遍它真能看到的插件，生成的清单才与实际内容一致。
  execFileSync(dot, ['-c'], {
    env: { ...process.env, GVBINDIR: pluginDir },
    stdio: 'inherit'
  })
  return { dot, pluginDir }
}

function prepareWin(fromDir, outRoot) {
  const srcBin = findFirstDir(fromDir, ['bin'])
  if (!srcBin) throw new Error(`找不到 bin 目录：${fromDir}`)
  const binDir = path.join(outRoot, 'bin')
  fs.mkdirSync(binDir, { recursive: true })
  // Windows 的 DLL 就在 exe 同目录解析，不需要任何重定位；插件也在 bin/ 下。
  // 体积不值得为了几百 KB 去逐个筛，整份 bin 拷走反而不会漏。
  for (const f of fs.readdirSync(srcBin)) {
    const s = path.join(srcBin, f)
    if (fs.statSync(s).isFile()) fs.copyFileSync(s, path.join(binDir, f))
  }
  const dot = path.join(binDir, 'dot.exe')
  if (!fs.existsSync(dot)) throw new Error(`找不到 dot.exe：${dot}`)
  execFileSync(dot, ['-c'], { stdio: 'inherit' })
  return { dot, pluginDir: binDir }
}

function main() {
  const args = parseArgs()
  const fromDir = resolveSourceDir(args.from)
  console.log(`graphviz 源：${fromDir}`)
  const outRoot = path.resolve(args.out, 'graphviz')
  fs.rmSync(outRoot, { recursive: true, force: true })
  fs.mkdirSync(outRoot, { recursive: true })

  const { dot, pluginDir } = IS_WIN ? prepareWin(fromDir, outRoot) : prepareMac(fromDir, outRoot)

  // 自检：用**打包后的** dot 跑一次真实布局。
  // 只 -V 是不够的——版本号不碰插件，而我们真正会失败的地方恰恰是插件加载。
  //
  // env 必须清干净（不继承 process.env）。否则构建机上的 PATH/DYLD 会让 dot
  // 悄悄用回系统那份 graphviz，自检通过、用户机器上照炸。
  const probe = execFileSync(dot, ['-Tplain'], {
    input: 'digraph{a->b->c; a->c}',
    encoding: 'utf8',
    env: { GVBINDIR: pluginDir }
  })
  if (!/^graph /m.test(probe) || !/^node /m.test(probe)) {
    throw new Error('自检失败：dot -Tplain 没有产出布局\n' + probe)
  }

  // 反向自检：不给 GVBINDIR 就**必须**失败。
  // graphviz 把插件目录编译期焊死在 libgvc 里，指向构建机的安装路径。
  // 构建机上那个路径真的存在，所以"不设 GVBINDIR 也能跑"恰恰说明它读的是
  // 系统那份插件，打包内容根本没被验证到——这种假绿会一路 ship 到用户手里。
  // litviz/cli.py 在运行时负责设 GVBINDIR；这条断言守住那个契约还在。
  let fellBack = false
  try {
    const sneaky = execFileSync(dot, ['-Tplain'], {
      input: 'digraph{a->b}', encoding: 'utf8', env: { GVBINDIR: path.join(outRoot, '__no_such_dir__') }
    })
    fellBack = /^node /m.test(sneaky)
  } catch (e) {
    fellBack = false     // 如期失败
  }
  if (fellBack) {
    throw new Error('自检失败：GVBINDIR 指向空目录时 dot 仍能出图，'
      + '说明它加载的是构建机上的系统插件而不是打包内容')
  }

  let bytes = 0
  const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => {
    const p = path.join(d, e.name)
    e.isDirectory() ? walk(p) : (bytes += fs.statSync(p).size)
  })
  walk(outRoot)
  console.log(`graphviz 烙制完成：${outRoot}  ${(bytes / 1024 / 1024).toFixed(2)} MB  自检通过`)
}

main()
