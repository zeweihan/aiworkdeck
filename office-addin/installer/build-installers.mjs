#!/usr/bin/env node
/**
 * Office 插件独立安装器构建（仅用 node 内置模块 + 系统工具）：
 *   macOS: swiftc 编译用户态安装器 .app（通用二进制），装进 DMG 分发。
 *          不用 pkg：macOS 26 起应用容器保护拒绝 root 安装脚本写他人容器（dev-board#68），
 *          只有用户会话内的 .app 能经「访问其他 App 的数据」授权弹窗写进 wef/。
 *   Windows: makensis（写 HKCU\...\WEF\Developer 注册表 sideload 键，免管理员）
 *
 * 两个安装器只携带一份指向托管地址的 manifest（任务窗格本体在服务端），
 * 所以功能更新全部在服务端完成，安装器基本一次安装终身有效，manifest 变更才需要重装。
 *
 * **安装器不是「与站点无关」的**（2026-08-29 发版后核对纠正的一条误解）：托管地址被
 * 焙进包里的 manifest，装哪个包就决定了任务窗格从哪个站加载、用哪个 office.js CDN。
 * 双主站各要一份自己的安装器：
 *   国内 --url https://addin.aiworkdeck.com/office-addin   （任务窗格走世纪互联 CDN）
 *   国际 --url https://addin.workdeck.ai/office-addin      （任务窗格走全球版 CDN）
 * 两份产物文件名相同（下载地址靠 host 区分），所以**必须用 --dist 分开输出目录**，
 * 否则后构建的那份会静默覆盖前一份。
 *
 * 用法：
 *   node installer/build-installers.mjs [--url https://addin.aiworkdeck.com/office-addin]
 *                                       [--dist installer/dist] [--skip-mac] [--skip-win]
 * 版本号取 desktop/package.json（单一来源）。产物默认在 installer/dist/。
 * makensis 缺失时提示 brew install makensis。签名：.app 需 Developer ID Application 证书，
 * 本机没有则产出未签名 app（下载后需右键打开/系统设置放行）。
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const addinDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const repoDir = path.resolve(addinDir, '..')

function parseArgs(argv) {
  const args = { url: 'https://addin.aiworkdeck.com/office-addin', dist: '', skipMac: false, skipWin: false }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--url') args.url = argv[++i] || args.url
    else if (a === '--dist') args.dist = argv[++i] || args.dist
    else if (a === '--skip-mac') args.skipMac = true
    else if (a === '--skip-win') args.skipWin = true
    else {
      console.error(`未知参数：${a}`)
      process.exit(1)
    }
  }
  return args
}

const args = parseArgs(process.argv.slice(2))
const version = JSON.parse(fs.readFileSync(path.join(repoDir, 'desktop', 'package.json'), 'utf8')).version
// 构建区必须在仓库外：仓库坐在 ~/Documents（iCloud 同步范围），FileProvider 会在签名后
// 异步给 .app 重新挂 FinderInfo 等 xattr，hdiutil 封进 DMG 后严格校验必挂、
// LaunchServices 拒启（-10810）。系统临时目录不受 iCloud 管
const buildDir = path.join(os.tmpdir(), 'awd-office-addin-installer-build')
const distDir = args.dist ? path.resolve(repoDir, args.dist) : path.join(addinDir, 'installer', 'dist')
fs.rmSync(buildDir, { recursive: true, force: true })
fs.mkdirSync(buildDir, { recursive: true })
fs.mkdirSync(distDir, { recursive: true })

// 1. 生产 manifest：复用 build-manifest.mjs 的 URL 替换（不需要 dist/，只要 manifest）
const manifestOutDir = path.join(buildDir, 'deploy')
execFileSync(process.execPath, [
  path.join(addinDir, 'scripts', 'build-manifest.mjs'),
  '--url', args.url,
  '--out', path.relative(addinDir, manifestOutDir),
], { cwd: addinDir, stdio: 'inherit' })
const manifestPath = path.join(manifestOutDir, 'manifest.xml')
if (!fs.existsSync(manifestPath)) {
  console.error('[installer] 生产 manifest 生成失败')
  process.exit(1)
}

const made = []

// 2. macOS 安装器 .app + DMG
if (!args.skipMac) {
  const appName = '安装 AI WorkDeck Office 插件.app'
  const stageDir = path.join(buildDir, 'mac-dmg')
  const appDir = path.join(stageDir, appName)
  const macosDir = path.join(appDir, 'Contents', 'MacOS')
  const resDir = path.join(appDir, 'Contents', 'Resources')
  fs.mkdirSync(macosDir, { recursive: true })
  fs.mkdirSync(resDir, { recursive: true })

  // swiftc 出双架构再 lipo（维护者 Mac 有 Xcode 工具链）
  const swiftSrc = path.join(addinDir, 'installer', 'mac', 'main.swift')
  const binOut = path.join(macosDir, 'installer')
  for (const arch of ['arm64', 'x86_64']) {
    execFileSync('swiftc', ['-O', '-target', `${arch}-apple-macos11`, swiftSrc,
      '-o', `${binOut}.${arch}`], { stdio: 'inherit' })
  }
  execFileSync('lipo', ['-create', `${binOut}.arm64`, `${binOut}.x86_64`, '-output', binOut], { stdio: 'inherit' })
  fs.rmSync(`${binOut}.arm64`); fs.rmSync(`${binOut}.x86_64`)

  fs.writeFileSync(path.join(appDir, 'Contents', 'Info.plist'), `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>installer</string>
  <key>CFBundleIconFile</key><string>installer</string>
  <key>CFBundleIdentifier</key><string>com.aiworkdeck.office-addin.installer</string>
  <key>CFBundleName</key><string>AI WorkDeck Office 插件安装器</string>
  <key>CFBundleDisplayName</key><string>安装 AI WorkDeck Office 插件</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>${version}</string>
  <key>CFBundleVersion</key><string>${version}</string>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
</dict></plist>
`)

  // manifest 两份：包内资源（安装动作的源）+ DMG 根目录（TCC 被拒时的手动拖拽兜底）
  fs.copyFileSync(manifestPath, path.join(resDir, 'manifest.xml'))
  fs.copyFileSync(manifestPath, path.join(stageDir, 'manifest.xml'))

  // 图标复用桌面端品牌 icon（png → icns），失败不阻断构建
  try {
    const iconsetDir = path.join(buildDir, 'installer.iconset')
    fs.mkdirSync(iconsetDir, { recursive: true })
    const srcIcon = path.join(repoDir, 'desktop', 'build', 'icon.png')
    for (const size of [16, 32, 128, 256, 512]) {
      execFileSync('sips', ['-z', String(size), String(size), srcIcon, '--out',
        path.join(iconsetDir, `icon_${size}x${size}.png`)], { stdio: 'ignore' })
      execFileSync('sips', ['-z', String(size * 2), String(size * 2), srcIcon, '--out',
        path.join(iconsetDir, `icon_${size}x${size}@2x.png`)], { stdio: 'ignore' })
    }
    const icnsPath = path.join(buildDir, 'installer.icns')
    execFileSync('iconutil', ['-c', 'icns', iconsetDir, '-o', icnsPath], { stdio: 'inherit' })
    fs.copyFileSync(icnsPath, path.join(resDir, 'installer.icns'))
  } catch (err) {
    console.warn(`[installer] 图标生成失败（不影响功能）：${err.message}`)
  }

  // 钥匙串里有 Developer ID Application 身份就签名（证书 fastlane/certs/devid-app-local.cer，
  // 私钥同目录 devid-app-local.key.pem，均在维护者钥匙串）；没有则保持未签名并告警
  let identity = ''
  try {
    identity = (execFileSync('security', ['find-identity', '-v', '-p', 'codesigning'], { encoding: 'utf8' })
      .split('\n').find(l => l.includes('Developer ID Application')) || '').match(/"([^"]+)"/)?.[1] || ''
  } catch { /* 非 mac 或无 security，保持未签名 */ }
  if (identity) {
    // sips/复制会给包内文件挂扩展属性，codesign 见 resource fork 直接拒签，先清干净
    execFileSync('xattr', ['-cr', appDir], { stdio: 'inherit' })
    execFileSync('codesign', ['--force', '--deep', '--options', 'runtime', '--timestamp',
      '--sign', identity, appDir], { stdio: 'inherit' })
  } else {
    console.warn('[installer] 未找到 Developer ID Application 身份，app 未签名')
  }

  const dmgOut = path.join(distDir, `AI-WorkDeck-Office-Addin-${version}.dmg`)
  fs.rmSync(dmgOut, { force: true })

  // DMG 视觉：品牌背景（hidpi TIFF，系统 tiffutil 合成入库的 1x/2x PNG）+ Finder 图标排版。
  // .DS_Store 让本机 Finder 自己写——这是兼容面最广的做法：electron-builder 内嵌 dmgbuild
  // 生成的 pBBk 背景书签在 macOS 26.2+ 被 Finder 拒读导致背景消失（electron-builder#9072），
  // Finder 亲手写的没这个问题。Finder 自动化被拒/超时则降级为无排版朴素 DMG，不阻断构建。
  const volName = 'AI WorkDeck Office 插件'
  const bgDir = path.join(stageDir, '.background')
  fs.mkdirSync(bgDir, { recursive: true })
  execFileSync('tiffutil', ['-cathidpicheck',
    path.join(addinDir, 'installer', 'mac', 'dmg-background.png'),
    path.join(addinDir, 'installer', 'mac', 'dmg-background@2x.png'),
    '-out', path.join(bgDir, 'background.tiff')], { stdio: 'inherit' })

  // 打包前兜底再清一次 xattr（签名内容不含 xattr，清掉不破坏签名），并严格校验后再封盘
  execFileSync('xattr', ['-cr', stageDir], { stdio: 'inherit' })
  if (identity) execFileSync('codesign', ['-v', '--strict', '--deep', appDir], { stdio: 'inherit' })

  const rwDmg = path.join(buildDir, 'addin-rw.dmg')
  execFileSync('hdiutil', ['create', '-volname', volName,
    '-srcfolder', stageDir, '-ov', '-format', 'UDRW', rwDmg], { stdio: 'inherit' })
  try { execFileSync('hdiutil', ['detach', `/Volumes/${volName}`, '-force'], { stdio: 'ignore' }) } catch { /* 没挂载 */ }
  execFileSync('hdiutil', ['attach', rwDmg, '-readwrite', '-noverify', '-noautoopen'], { stdio: 'inherit' })
  try {
    // 窗口 660x442（内容区 660x420 = 背景图逻辑尺寸 + 22 标题栏）；图标中心与
    // art/dmg-background.html 的光晕/文案联动：安装器 app (330, 200)，manifest 兜底 (566, 350)
    execFileSync('osascript', ['-e', `
      tell application "Finder"
        tell disk "${volName}"
          open
          set current view of container window to icon view
          set toolbar visible of container window to false
          set statusbar visible of container window to false
          set the bounds of container window to {200, 120, 860, 562}
          set viewOpts to the icon view options of container window
          set arrangement of viewOpts to not arranged
          set icon size of viewOpts to 112
          set text size of viewOpts to 13
          set background picture of viewOpts to file ".background:background.tiff"
          set position of item "${appName}" to {330, 200}
          set position of item "manifest.xml" to {572, 316}
          update without registering applications
          delay 2
          close
        end tell
      end tell`], { stdio: 'inherit', timeout: 60000 })
    // Finder 的视图设置是异步落盘的（close 之后才写 .DS_Store），立刻 detach 会把空壳
    // .DS_Store 封进只读 DMG——排版白做且无报错（首建实测踩过）。轮询到 icvp 记录出现
    // 才继续；中途 nudge 一次（再开再关触发 flush）；最终没等到就明确告警降级。
    const dsPath = `/Volumes/${volName}/.DS_Store`
    let styled = false
    for (let i = 0; i < 20; i++) {
      execFileSync('sleep', ['1'])
      try { if (fs.readFileSync(dsPath).includes('icvp')) { styled = true; break } } catch { /* 还没写出来 */ }
      if (i === 6) {
        try {
          execFileSync('osascript', ['-e',
            `tell application "Finder" to open disk "${volName}"`, '-e',
            'delay 1', '-e',
            `tell application "Finder" to close every window whose name is "${volName}"`,
          ], { stdio: 'inherit', timeout: 30000 })
        } catch { /* nudge 失败不致命，继续等 */ }
      }
    }
    if (!styled) console.warn('[installer] Finder 视图设置未落盘（.DS_Store 无 icvp），本次产物为朴素 DMG')
    else console.log('[installer] DMG 背景与图标排版已落盘')
  } catch (err) {
    console.warn(`[installer] Finder 排版失败（自动化权限/锁屏？），降级为朴素 DMG：${err.message}`)
  }
  execFileSync('sync')
  execFileSync('hdiutil', ['detach', `/Volumes/${volName}`], { stdio: 'inherit' })
  execFileSync('hdiutil', ['convert', rwDmg, '-format', 'UDZO', '-o', dmgOut], { stdio: 'inherit' })
  fs.rmSync(rwDmg, { force: true })

  // 公证（可选）：环境变量给齐 ASC API key 三件套才做。维护者本机来源：
  //   5-Tech/5-BQT_Global/fastlane/.env（ASC_KEY_ID / ASC_ISSUER_ID / ASCKey.p8）
  const { NOTARY_KEY_PATH, NOTARY_KEY_ID, NOTARY_ISSUER_ID } = process.env
  if (identity && NOTARY_KEY_PATH && NOTARY_KEY_ID && NOTARY_ISSUER_ID) {
    execFileSync('xcrun', ['notarytool', 'submit', dmgOut,
      '--key', NOTARY_KEY_PATH, '--key-id', NOTARY_KEY_ID, '--issuer', NOTARY_ISSUER_ID,
      '--wait'], { stdio: 'inherit' })
    execFileSync('xcrun', ['stapler', 'staple', dmgOut], { stdio: 'inherit' })
    console.log('[installer] dmg 已签名并公证装订')
  } else if (identity) {
    console.warn('[installer] app 已签名但未公证（缺 NOTARY_KEY_PATH/NOTARY_KEY_ID/NOTARY_ISSUER_ID）')
  }
  made.push(dmgOut)
}

// 3. Windows exe
if (!args.skipWin) {
  const exeOut = path.join(distDir, `AI-WorkDeck-Office-Addin-${version}.exe`)
  try {
    // LC_ALL 必须是 UTF-8 locale：locale 为空时 makensis 的 iconv 对非 ASCII 直接
    // 报 Bad text encoding，纯 ASCII 也会 std::bad_alloc（Homebrew nsis 3.12 实测）
    execFileSync('makensis', [
      `-DVERSION=${version}`,
      `-DMANIFEST=${manifestPath}`,
      `-DOUTFILE=${exeOut}`,
      `-DARTDIR=${path.join(addinDir, 'installer', 'win')}`,
      path.join(addinDir, 'installer', 'win', 'installer.nsi'),
    ], { stdio: 'inherit', env: { ...process.env, LC_ALL: 'en_US.UTF-8', LANG: 'en_US.UTF-8' } })
    made.push(exeOut)
  } catch (err) {
    if (err.code === 'ENOENT') {
      console.error('[installer] 未找到 makensis（brew install makensis），已跳过 Windows 安装器')
      process.exit(1)
    }
    throw err
  }
}

console.log('\n[installer] 完成：')
for (const f of made) console.log('  ' + path.relative(addinDir, f))
