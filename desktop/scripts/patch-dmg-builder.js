// macOS 26.2 起 Finder 拒读 dmgbuild 写进 .DS_Store 的 pBBk 背景书签，DMG 背景整个
// 不显示（同版 Obsidian/Podman Desktop 全中招；electron-builder#9072 / dmgbuild#273）。
// 上游修复没有随 electron-builder 24.x 发布，这里在 npm postinstall 时对 vendored
// core.py 做定点补丁：跳过 Bookmark 生成（icvp 里的 alias 通道保留，老系统照常工作）。
// 若未来升级 electron-builder 后此处报「结构已变」，先确认新版是否已自带修复，再删本补丁。
const fs = require('fs')
const path = require('path')

const target = path.join(__dirname, '..', 'node_modules', 'dmg-builder', 'vendor', 'dmgbuild', 'core.py')
if (!fs.existsSync(target)) {
  // 依赖布局变化或裁剪安装（如 --omit=optional 的极端环境）：不拦安装，打包 mac 时自然会暴露
  console.log('[patch-dmg-builder] 未找到 dmg-builder vendored core.py，跳过')
  process.exit(0)
}

const PATCHED = 'background_bmk = None  # patched: macOS 26.2+ Finder rejects pBBk (electron-builder#9072)'
const BUGGY = 'background_bmk = Bookmark.for_file(background_file)'

const src = fs.readFileSync(target, 'utf8')
if (src.includes(PATCHED)) {
  console.log('[patch-dmg-builder] 已打过补丁')
  process.exit(0)
}
if (!src.includes(BUGGY)) {
  console.error('[patch-dmg-builder] core.py 结构已变（可能升级了 electron-builder），请人工核对 pBBk 修复是否仍需要')
  process.exit(1)
}
fs.writeFileSync(target, src.replace(BUGGY, PATCHED))
console.log('[patch-dmg-builder] 已修补 pBBk 背景书签（DMG 背景在 macOS 26.2+ 可见）')
