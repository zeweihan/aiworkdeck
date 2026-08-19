#!/usr/bin/env node
/**
 * Office 插件独立安装器构建（仅用 node 内置模块 + 系统工具）：
 *   macOS: pkgbuild（payload-free pkg，postinstall 把 manifest 种进三个 Office 容器的 wef/）
 *   Windows: makensis（写 HKCU\...\WEF\Developer 注册表 sideload 键，免管理员）
 *
 * 两个安装器只携带一份指向托管地址的 manifest（任务窗格本体在服务端），
 * 所以功能更新全部在服务端完成，安装器基本一次安装终身有效，manifest 变更才需要重装。
 *
 * 用法：
 *   node installer/build-installers.mjs [--url https://addin.aiworkdeck.com/office-addin]
 *                                       [--skip-mac] [--skip-win]
 * 版本号取 desktop/package.json（单一来源）。产物在 installer/dist/。
 * makensis 缺失时提示 brew install makensis。签名：pkg 需 Developer ID Installer 证书，
 * 本机没有则产出未签名 pkg（下载后需右键打开/系统设置放行）。
 */
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const addinDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const repoDir = path.resolve(addinDir, '..')

function parseArgs(argv) {
  const args = { url: 'https://addin.aiworkdeck.com/office-addin', skipMac: false, skipWin: false }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--url') args.url = argv[++i] || args.url
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
const buildDir = path.join(addinDir, 'installer', 'build')
const distDir = path.join(addinDir, 'installer', 'dist')
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

// 2. macOS pkg
if (!args.skipMac) {
  const scriptsDir = path.join(buildDir, 'mac-scripts')
  fs.mkdirSync(scriptsDir, { recursive: true })
  fs.copyFileSync(path.join(addinDir, 'installer', 'mac', 'postinstall'), path.join(scriptsDir, 'postinstall'))
  fs.chmodSync(path.join(scriptsDir, 'postinstall'), 0o755)
  fs.copyFileSync(manifestPath, path.join(scriptsDir, 'manifest.xml'))
  const pkgOut = path.join(distDir, `AI-WorkDeck-Office-Addin-${version}.pkg`)
  execFileSync('pkgbuild', [
    '--identifier', 'com.aiworkdeck.office-addin',
    '--version', version,
    '--nopayload',
    '--scripts', scriptsDir,
    pkgOut,
  ], { stdio: 'inherit' })

  // 钥匙串里有 Developer ID Installer 身份就签名（维护者机器 2026-08-19 起有，
  // 证书文件在 5-Tech/DeveloperID_Installer_X9B97KVA84.cer）；没有则保持未签名并告警
  let identity = ''
  try {
    identity = (execFileSync('security', ['find-identity', '-v'], { encoding: 'utf8' })
      .split('\n').find(l => l.includes('Developer ID Installer')) || '').match(/"([^"]+)"/)?.[1] || ''
  } catch { /* 非 mac 或无 security，保持未签名 */ }
  if (identity) {
    const signedTmp = pkgOut.replace(/\.pkg$/, '.signed.pkg')
    execFileSync('productsign', ['--sign', identity, pkgOut, signedTmp], { stdio: 'inherit' })
    fs.renameSync(signedTmp, pkgOut)
    // 公证（可选）：环境变量给齐 ASC API key 三件套才做。维护者本机来源：
    //   5-Tech/5-BQT_Global/fastlane/.env（ASC_KEY_ID / ASC_ISSUER_ID / ASCKey.p8）
    const { NOTARY_KEY_PATH, NOTARY_KEY_ID, NOTARY_ISSUER_ID } = process.env
    if (NOTARY_KEY_PATH && NOTARY_KEY_ID && NOTARY_ISSUER_ID) {
      execFileSync('xcrun', ['notarytool', 'submit', pkgOut,
        '--key', NOTARY_KEY_PATH, '--key-id', NOTARY_KEY_ID, '--issuer', NOTARY_ISSUER_ID,
        '--wait'], { stdio: 'inherit' })
      execFileSync('xcrun', ['stapler', 'staple', pkgOut], { stdio: 'inherit' })
      console.log('[installer] pkg 已签名并公证装订')
    } else {
      console.warn('[installer] pkg 已签名但未公证（缺 NOTARY_KEY_PATH/NOTARY_KEY_ID/NOTARY_ISSUER_ID）')
    }
  } else {
    console.warn('[installer] 未找到 Developer ID Installer 身份，pkg 未签名')
  }
  made.push(pkgOut)
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
