#!/usr/bin/env node
/**
 * 生产部署产物生成器（仅用 node 内置模块，无新依赖）：
 * 1. 把 manifest.xml 里的开发态 URL（https://localhost:3000）整体替换为部署地址；
 * 2. 若 dist/ 存在（先跑 npm run build），把 dist 整体拷入输出目录，得到可直接
 *    上传托管的完整目录（页面 + 图标 + manifest）；
 * 3. --china：世纪互联（21Vianet）运营的中国版 Microsoft 365 环境要求 office.js
 *    从其专属 CDN 加载，替换输出目录中 taskpane.html 的 office.js 地址。
 *    dev 流程不受影响（源文件 taskpane.html 始终指向全球版 CDN）。
 * 4. 品牌站地址（SupportUrl / GetStarted.LearnMoreUrl）按托管地址的 host 派生，
 *    见 BRAND_SITE_BY_ADDIN_HOST。这两个链接会出现在 Office 的加载项信息面板里，
 *    是用户直接点得到的位置——国际站的插件挂着国内中文站是品牌串台（2026-08-29 实测）。
 *
 * 用法：
 *   node scripts/build-manifest.mjs --url https://addin.yourfirm.com [--china]
 *                                   [--brand-url https://yourfirm.com] [--out dist-deploy]
 *   部署地址也可用环境变量 ADDIN_BASE_URL 提供。
 *
 * 校验：npx office-addin-manifest validate <out>/manifest.xml
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const DEV_BASE_URL = 'https://localhost:3000'
const GLOBAL_OFFICE_JS = 'https://appsforoffice.microsoft.com/lib/1/hosted/office.js'
const CHINA_OFFICE_JS = 'https://appsforoffice.cdn.partner.office365.cn/appsforoffice/lib/1/hosted/office.js'

/**
 * manifest 源里写死的品牌站地址（SupportUrl 与 GetStarted.LearnMoreUrl 两处）。
 * 它不是托管地址——托管地址走 DEV_BASE_URL 那条整体替换。
 */
const DEV_BRAND_URL = 'https://www.aiworkdeck.com'

/**
 * 托管 host → 品牌站 origin。双主站架构下两站各有自己的品牌站，
 * 国际站的插件如果挂国内中文站，用户在 Office 加载项信息面板点「支持」会被带去
 * 另一个站（2026-08-29 发版后核对实测到）。
 * 不在表里的 host（律所私有部署）保持源文件里的默认值，或用 --brand-url 显式指定。
 */
const BRAND_SITE_BY_ADDIN_HOST = {
  'addin.aiworkdeck.com': 'https://www.aiworkdeck.com',
  'addin.workdeck.ai': 'https://www.workdeck.ai'
}

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function parseArgs(argv) {
  const args = { url: process.env.ADDIN_BASE_URL || '', china: false, out: 'dist-deploy', brandUrl: '' }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--url') args.url = argv[++i] || ''
    else if (a === '--china') args.china = true
    else if (a === '--brand-url') args.brandUrl = argv[++i] || ''
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
  console.error(`[build-manifest] ${message}`)
  process.exit(1)
}

const args = parseArgs(process.argv.slice(2))
if (args.help) {
  console.log('用法：node scripts/build-manifest.mjs --url https://addin.yourfirm.com [--china] [--out dist-deploy]')
  process.exit(args.help === true && process.argv.length > 2 ? 1 : 0)
}

const baseUrl = (args.url || '').trim().replace(/\/+$/, '')
if (!baseUrl) fail('缺少部署地址：--url https://addin.yourfirm.com（或环境变量 ADDIN_BASE_URL）')
if (!/^https:\/\/[^\s/]+/.test(baseUrl)) {
  fail(`部署地址必须是 https origin（Office 只加载 https 任务窗格页面）：${baseUrl}`)
}

const outDir = path.resolve(rootDir, args.out)
fs.mkdirSync(outDir, { recursive: true })

// 1. manifest：整体替换开发态 URL
const manifestSrc = fs.readFileSync(path.join(rootDir, 'manifest.xml'), 'utf8')
if (!manifestSrc.includes(DEV_BASE_URL)) {
  fail(`manifest.xml 中未找到开发态地址 ${DEV_BASE_URL}，模板可能已被改动`)
}
let manifestOut = manifestSrc.split(DEV_BASE_URL).join(baseUrl)

// 品牌站地址：显式 --brand-url 优先，否则按托管 host 派生；都没有就保持源文件默认值。
// 先替换托管地址再替换品牌站，两者字面不同、互不吃掉（品牌站是 www.aiworkdeck.com，
// 托管地址是 localhost:3000），顺序其实无关，这里按可读性排。
const addinHost = new URL(baseUrl).host
const brandUrl = (args.brandUrl || BRAND_SITE_BY_ADDIN_HOST[addinHost] || '').trim().replace(/\/+$/, '')
if (brandUrl) {
  if (!/^https:\/\/[^\s/]+/.test(brandUrl)) fail(`品牌站地址必须是 https origin：${brandUrl}`)
  if (brandUrl !== DEV_BRAND_URL) {
    if (!manifestOut.includes(DEV_BRAND_URL)) {
      fail(`manifest.xml 中未找到品牌站地址 ${DEV_BRAND_URL}，模板可能已被改动`)
    }
    manifestOut = manifestOut.split(DEV_BRAND_URL).join(brandUrl)
  }
} else {
  console.warn(`[build-manifest] 警告：${addinHost} 未登记品牌站，SupportUrl/LearnMoreUrl 保持 ${DEV_BRAND_URL}（需要的话用 --brand-url 指定）`)
}

fs.writeFileSync(path.join(outDir, 'manifest.xml'), manifestOut)
console.log(`[build-manifest] manifest.xml -> ${path.relative(rootDir, outDir)}/manifest.xml（URL: ${baseUrl}，品牌站: ${brandUrl || DEV_BRAND_URL}）`)

// 2. dist：拷入输出目录，得到完整可托管产物
const distDir = path.join(rootDir, 'dist')
if (fs.existsSync(path.join(distDir, 'taskpane.html'))) {
  fs.cpSync(distDir, outDir, { recursive: true })
  console.log(`[build-manifest] dist/ 已拷入 ${path.relative(rootDir, outDir)}/`)
} else {
  console.warn('[build-manifest] 警告：dist/taskpane.html 不存在（未跑 npm run build？），仅生成 manifest')
}

// 3. 世纪互联变体：替换输出目录中 taskpane.html 的 office.js CDN
if (args.china) {
  const pagePath = path.join(outDir, 'taskpane.html')
  if (!fs.existsSync(pagePath)) {
    fail('--china 需要输出目录中存在 taskpane.html（先跑 npm run build）')
  }
  const page = fs.readFileSync(pagePath, 'utf8')
  if (!page.includes(GLOBAL_OFFICE_JS)) {
    fail('taskpane.html 中未找到全球版 office.js CDN 地址，模板可能已被改动')
  }
  fs.writeFileSync(pagePath, page.split(GLOBAL_OFFICE_JS).join(CHINA_OFFICE_JS))
  console.log('[build-manifest] 世纪互联变体：office.js CDN 已替换为 partner.office365.cn')
}

console.log('[build-manifest] 完成。校验：npx office-addin-manifest validate ' +
  path.relative(rootDir, path.join(outDir, 'manifest.xml')))
