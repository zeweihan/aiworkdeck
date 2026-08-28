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

/**
 * 安装页品牌覆盖样式（对齐官网 DESIGN.md：纸面底 + 衬线标题/眉标 + 白卡 + 品牌绿）。
 * 只追加 <style>，vendor 模板与机制 JS 一行不改；选择器全部钉在模板既有类名上。
 * 衬线字体走系统栈（Songti/SimSun 兜底），不引外部字体——安装页要在离线/内网可用。
 */
const BRAND_STYLE = `    <style id="awd-brand">
        body {
            margin: 0;
            padding: 48px 24px 64px;
            background: #F8F9FA;
            color: #212529;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", "Inter", sans-serif;
            -webkit-font-smoothing: antialiased;
        }
        .awd-eyebrow {
            max-width: 760px;
            margin: 0 auto 10px;
            display: flex;
            align-items: center;
            gap: 10px;
            font-size: 11px;
            font-weight: 600;
            letter-spacing: 0.22em;
            color: #1A5336;
        }
        .awd-eyebrow-line { width: 32px; height: 1px; background: #1A5336; }
        .divTitle {
            max-width: 760px;
            margin: 0 auto 28px;
            font-family: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "SimSun", serif;
            font-size: 32px;
            font-weight: 700;
            color: #123A26;
        }
        .addonList {
            max-width: 760px !important; /* 机制 JS 会写 inline 800*dpr，压回 */
            margin: 0 auto;
            padding: 8px 24px 16px;
            background: #fff;
            border: 1px solid rgba(233, 236, 239, 0.9);
            border-radius: 12px;
            box-shadow: 0 18px 40px -18px rgba(18, 58, 38, 0.12);
        }
        .addonItem { font-size: 13px; line-height: 40px; margin-bottom: 0; border-radius: 6px; }
        .addonItem .addonItemName4 { font-size: 12px; line-height: 1.6; color: #868E96; word-break: break-all; }
        .addonItem:hover { border: 0; border-radius: 6px; background: #F1F3F5; }
        .addonItemTitle,
        .addonItemTitle:hover {
            background: transparent;
            border: 0;
            border-bottom: 1px solid #E9ECEF;
            border-radius: 0;
            font-size: 11px;
            letter-spacing: 0.08em;
            color: #868E96;
        }
        .addonItemButton { padding: 5px 14px; background-color: #1A5336; border-radius: 6px; font-size: 12px; }
        .addonItemButton:hover { background-color: #123A26; }
        .ClearAll {
            max-width: 760px !important;
            margin: 24px auto 0;
            box-sizing: border-box;
            font-size: 13px;
            line-height: 40px;
            color: #868E96;
            background: #fff;
            border: 1px solid #E9ECEF;
            border-radius: 8px;
        }
        .ClearAll:hover { border-radius: 8px; border-color: #ADB5BD; background: #F1F3F5; color: #C0392B; }
        /* 空态：WPS 本地服务没连上时表格只剩表头，给一句解释（纯 CSS，不动机制） */
        .addonList:has(.addonItemTitle:only-child)::after {
            content: "未检测到已发布的加载项。请确认本机已安装并启动过 WPS Office，并允许浏览器打开 WPS 以连接本地服务。";
            display: block;
            padding: 28px 0 20px;
            text-align: center;
            font-size: 13px;
            color: #868E96;
        }
    </style>`

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
// 轻量品牌化：只动标题文案 + 注入覆盖样式，不碰任何机制代码。
// 样式对齐官网 DESIGN.md（dev-board#246）：纸面底色 + 衬线大标题/眉标 + 白卡 +
// 品牌绿按钮；vendor 模板保持原样，全部覆盖走这里追加的 <style>。
// 机制 JS 会往 .addonList/.ClearAll 写 inline maxWidth（800*dpr），只能用
// !important 压回；「验证中/正常/无效」状态色也是 inline 写入，刻意不动。
installHtml = installHtml
  .replace('<title>WPS加载项配置</title>', '<title>AI WorkDeck - WPS 加载项安装</title>')
  .replace('>WPS加载项配置</div>', '>WPS 加载项安装</div>')
  .replace('<div class="divTitle">', '<div class="awd-eyebrow"><span class="awd-eyebrow-line"></span>AI WORKDECK</div>\n    <div class="divTitle">')
  .replace('</head>', `${BRAND_STYLE}\n</head>`)
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
