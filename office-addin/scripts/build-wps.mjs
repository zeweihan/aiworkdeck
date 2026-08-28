#!/usr/bin/env node
/**
 * WPS 加载项部署产物生成器（仅用 node 内置模块，无新依赖）。
 *
 * WPS 侧没有 manifest.xml——分发走「在线模式」：WPS 启动时从我们服务器拉
 * url/ribbon.xml 与 url/index.html；安装动作由 install.html（官方 publish.html
 * 模板的定制版）经本机 WPS 常驻服务（127.0.0.1:58890）把加载项记录写进用户的
 * jsaddons/publish.xml。个人版 12.1.0.16910 起这是唯一受支持的安装通路；
 * 企业版私有部署另出一份 jsplugins.xml（配 oem.ini 的 JSPluginsServer 用）。
 *
 * 产物布局（--out 目录，默认 dist-wps/，整体上传到 <baseUrl> 对应路径）：
 *   wps/                加载项本体（在线模式的 url 指这里，必须以 / 结尾可达）
 *     ribbon.xml        功能区（三宿主共用）
 *     index.html        入口页（引 main.js）
 *     main.js  js/      ribbon 薄壳（vanilla JS，不进 Vite）
 *     images/           ribbon 图标（构建时从 assets/ 拷入）
 *     ui/               任务窗格 = Vite 产物整份拷贝（taskpane-wps.html 是 WPS 入口）
 *   install.html        一键安装页（官方模板 + 我们的三宿主清单）
 *   jsplugins.xml       企业版私有部署模板
 *
 * 用法：
 *   node scripts/build-wps.mjs [--url https://addin.aiworkdeck.com/wps-addin] [--out dist-wps]
 *   部署地址也可用环境变量 WPS_ADDIN_BASE_URL 提供；先跑 npm run build 出 dist/。
 *
 * 与 wpsjs CLI 的关系：机制一比一对拍其 publish.js（PUBLISH_REPLACE_STRING /
 * SERVERID_REPLEASE_STRING 两处替换），模板 wps/vendor/publish-template.html 来自
 * wpsjs@2.2.3 npm 包 src/lib/res/publish.html，原样 vendor（金山许可允许再分发）。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const DEFAULT_BASE_URL = 'https://addin.aiworkdeck.com/wps-addin'
/** 加载项注册名（写进用户本机 publish.xml；三宿主同名不同 type） */
const ADDON_NAME = 'aiworkdeck'

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function parseArgs(argv) {
  const args = { url: process.env.WPS_ADDIN_BASE_URL || DEFAULT_BASE_URL, out: 'dist-wps' }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--url') args.url = argv[++i] || ''
    else if (a === '--out') args.out = argv[++i] || args.out
    else if (a === '--help' || a === '-h') args.help = true
    else {
      console.error(`未知参数：${a}`)
      args.help = true
    }
  }
  return args
}

function fail(message) {
  console.error(`[build-wps] ${message}`)
  process.exit(1)
}

const args = parseArgs(process.argv.slice(2))
if (args.help) {
  console.log('用法：node scripts/build-wps.mjs [--url https://addin.example.com/wps-addin] [--out dist-wps]')
  process.exit(0)
}

const baseUrl = (args.url || '').trim().replace(/\/+$/, '')
if (!baseUrl) fail('缺少部署地址：--url（或环境变量 WPS_ADDIN_BASE_URL）')
if (!/^https?:\/\/[^\s/]+/.test(baseUrl)) fail(`部署地址必须是 http(s) origin：${baseUrl}`)
if (!baseUrl.startsWith('https://')) {
  console.warn('[build-wps] 警告：非 https 地址，仅内网私有部署可接受')
}
// 在线模式的加载项 url 必须以 / 结尾（WPS 按 url+ribbon.xml 拉取）
const addonUrl = `${baseUrl}/wps/`

const distDir = path.join(rootDir, 'dist')
if (!fs.existsSync(path.join(distDir, 'taskpane-wps.html'))) {
  fail('未找到 dist/taskpane-wps.html：先跑 npm run build')
}

const outDir = path.resolve(rootDir, args.out)
fs.rmSync(outDir, { recursive: true, force: true })
fs.mkdirSync(path.join(outDir, 'wps', 'images'), { recursive: true })

// 1) ribbon 薄壳
const shellDir = path.join(rootDir, 'wps')
for (const f of ['ribbon.xml', 'index.html', 'main.js']) {
  fs.copyFileSync(path.join(shellDir, f), path.join(outDir, 'wps', f))
}
fs.cpSync(path.join(shellDir, 'js'), path.join(outDir, 'wps', 'js'), { recursive: true })
fs.copyFileSync(path.join(rootDir, 'assets', 'icon-32.png'), path.join(outDir, 'wps', 'images', 'icon-32.png'))

// 2) 任务窗格：Vite 产物整份进 ui/
fs.cpSync(distDir, path.join(outDir, 'wps', 'ui'), { recursive: true })

// 3) install.html：官方 publish.html 模板 + 我们的清单（对拍 wpsjs publish.js 的两处替换）
const addons = ['wps', 'et', 'wpp'].map((type) => ({
  name: ADDON_NAME,
  addonType: type,
  online: 'true',
  multiUser: 'true',
  customDomain: '',
  url: addonUrl
}))
let installHtml = fs.readFileSync(path.join(shellDir, 'vendor', 'publish-template.html'), 'utf-8')
if (!installHtml.includes('PUBLISH_REPLACE_STRING') || !installHtml.includes('SERVERID_REPLEASE_STRING')) {
  fail('publish-template.html 缺少替换占位符（模板被改动过？）')
}
installHtml = installHtml.replace(/PUBLISH_REPLACE_STRING/, JSON.stringify(addons))
// multiUser=true 对应 CLI 的 getServerId() 分支（Linux 多用户场景，Windows 单用户无副作用）
installHtml = installHtml.replace(/SERVERID_REPLEASE_STRING/, 'getServerId()')
// 轻量品牌化：只动标题文案，不碰任何机制代码
installHtml = installHtml
  .replace('<title>WPS加载项配置</title>', '<title>AI WorkDeck - WPS 加载项安装</title>')
  .replace('>WPS加载项配置</div>', '>AI WorkDeck - WPS 加载项安装</div>')
fs.writeFileSync(path.join(outDir, 'install.html'), installHtml)

// 4) 企业版私有部署模板（oem.ini 的 JSPluginsServer 指向这份文件的部署地址）
const jsplugins = ['<jsplugins>']
for (const a of addons) {
  jsplugins.push(`    <jspluginonline name="${a.name}" type="${a.addonType}" url="${a.url}"/>`)
}
jsplugins.push('</jsplugins>', '')
fs.writeFileSync(path.join(outDir, 'jsplugins.xml'), jsplugins.join('\n'))

console.log(`[build-wps] 完成：${outDir}`)
console.log(`  加载项 url：${addonUrl}（部署后 GET ${addonUrl}ribbon.xml 必须直接可达，别过 SPA 回退/鉴权）`)
console.log(`  安装页：${baseUrl}/install.html`)
console.log('  缓存口径：ribbon.xml / index.html / main.js / js/* 建议 no-cache；ui/assets/* 带 hash 可长缓存')
