// dev 态品牌名：把 node_modules 里那个 Electron.app 就地改名成 AI WorkDeck。
//
// 为什么必须改包而不是改代码：macOS 菜单栏最左边那个粗体应用名由 AppKit 从**当前运行的
// .app 包**的 Info.plist:CFBundleName 里读，跟 app.name、跟菜单模板第一项的 label 都无关。
// 实测（Electron 30 / macOS 26）：app.setName('AI WorkDeck') + 模板 label 也写 'AI WorkDeck'，
// 菜单栏照旧显示 Electron；只把包里的 CFBundleName 改掉，菜单栏立刻变成 AI WorkDeck。
// 打包版没这个问题（electron-builder 按 build.productName 写 Info.plist），只有
// `npm run dev` 跑的是 node_modules/electron/dist/Electron.app，于是左上角写着 Electron。
//
// 安全性：Electron 的 dist 包是 linker-signed adhoc 签名，`codesign -dv` 报
// 「Info.plist=not bound」——Info.plist 不在签名覆盖范围内，改它不会让包启动不了（已实测）。
//
// 幂等；失败只警告不阻断（predev 钩子，绝不能因为改不了名字就让人跑不起 dev）。
// npm install 重装 electron 后会被还原，下次 npm run dev 再改一次即可。

const { execFileSync } = require('child_process')
const fs = require('fs')
const path = require('path')

const DISPLAY_NAME = 'AI WorkDeck'
const PLIST = path.join(__dirname, '..', 'node_modules', 'electron', 'dist',
  'Electron.app', 'Contents', 'Info.plist')

function plist(...args) {
  return execFileSync('/usr/libexec/PlistBuddy', [...args, PLIST], { encoding: 'utf8' }).trim()
}

function setKey(key) {
  let current = null
  try {
    current = plist('-c', `Print :${key}`)
  } catch {
    // 键不存在（CFBundleDisplayName 在某些版本里没有）——补一个
    plist('-c', `Add :${key} string ${DISPLAY_NAME}`)
    return true
  }
  if (current === DISPLAY_NAME) return false
  plist('-c', `Set :${key} ${DISPLAY_NAME}`)
  return true
}

if (process.platform !== 'darwin') {
  process.exit(0)
}
if (!fs.existsSync(PLIST)) {
  // 还没 npm install，或 electron 装在了别处——不是错误
  process.exit(0)
}

try {
  const changed = ['CFBundleName', 'CFBundleDisplayName'].map(setKey).some(Boolean)
  if (changed) {
    console.log(`[brand-dev-electron] dev 态应用名已改为「${DISPLAY_NAME}」`)
  }
} catch (e) {
  console.warn(`[brand-dev-electron] 跳过（dev 菜单栏会显示 Electron）: ${e.message}`)
}
