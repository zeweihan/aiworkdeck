// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const afterPack = require('../scripts/after-pack')
const { pruneFrameworkLocales } = afterPack

// 安装包瘦身 dev-board#528：electronLanguages 在 mac 上只裁 Contents/Resources/*.lproj，
// 真正的语言包（Electron Framework 里的 55 个 lproj / 约 37MB）要靠 afterPack 自己删。
// 这组用例守两件事：① 只有白名单里的 lproj 留下来，其余连同字节一起消失；
// ② 非 darwin 的构建一个文件都不许动——Windows 的 locales/*.pak 归 electronLanguages 管，
// 这里再插一手只会删错东西。

const FRAMEWORK_RESOURCES = path.join(
  'Contents', 'Frameworks', 'Electron Framework.framework', 'Versions', 'A', 'Resources',
)

/** 造一份最小的 .app：五个 lproj 各放一个 locale.pak，外加一个非 lproj 的邻居。 */
function makeApp(root, productFilename = 'AI WorkDeck') {
  const resourcesDir = path.join(root, `${productFilename}.app`, FRAMEWORK_RESOURCES)
  fs.mkdirSync(resourcesDir, { recursive: true })
  const sizes = { 'en.lproj': 10, 'zh_CN.lproj': 20, 'Base.lproj': 30, 'ja.lproj': 40, 'de.lproj': 50 }
  for (const [name, size] of Object.entries(sizes)) {
    fs.mkdirSync(path.join(resourcesDir, name))
    fs.writeFileSync(path.join(resourcesDir, name, 'locale.pak'), Buffer.alloc(size, 1))
  }
  // lproj 之外的东西必须原样留下
  fs.writeFileSync(path.join(resourcesDir, 'Info.plist'), 'plist')
  fs.mkdirSync(path.join(resourcesDir, 'MainMenu.nib'))
  fs.writeFileSync(path.join(resourcesDir, 'MainMenu.nib', 'keyedobjects.nib'), 'nib')
  return { resourcesDir, sizes }
}

function mkTmp() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'awd-after-pack-'))
}

test('pruneFrameworkLocales 留白名单、删其余，返回删除清单与字节数', () => {
  const root = mkTmp()
  try {
    const { resourcesDir } = makeApp(root)
    const res = pruneFrameworkLocales(resourcesDir, ['en.lproj', 'zh_CN.lproj', 'Base.lproj'])

    assert.deepStrictEqual(res.removed.sort(), ['de.lproj', 'ja.lproj'])
    assert.strictEqual(res.keptCount, 3)
    assert.strictEqual(res.bytes, 40 + 50)

    const left = fs.readdirSync(resourcesDir).sort()
    assert.deepStrictEqual(left, [
      'Base.lproj', 'Info.plist', 'MainMenu.nib', 'en.lproj', 'zh_CN.lproj',
    ].sort())
    // 保留的 lproj 内容原封不动
    assert.strictEqual(fs.readFileSync(path.join(resourcesDir, 'en.lproj', 'locale.pak')).length, 10)
    assert.strictEqual(fs.readFileSync(path.join(resourcesDir, 'Info.plist'), 'utf8'), 'plist')
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('白名单里没有的目录不存在时不报错（Base.lproj 当前 Electron 版本里就没有）', () => {
  const root = mkTmp()
  try {
    const { resourcesDir } = makeApp(root)
    fs.rmSync(path.join(resourcesDir, 'Base.lproj'), { recursive: true })
    const res = pruneFrameworkLocales(resourcesDir, afterPack.KEEP_LPROJ)
    assert.strictEqual(res.keptCount, 2)
    assert.deepStrictEqual(res.removed.sort(), ['de.lproj', 'ja.lproj'])
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('Resources 目录不存在时抛错，不静默 no-op', () => {
  const root = mkTmp()
  try {
    assert.throws(
      () => pruneFrameworkLocales(path.join(root, 'nope'), ['en.lproj']),
      /Electron Framework Resources not found/,
    )
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('afterPack 钩子在 darwin 上裁掉多余语言包', async () => {
  const root = mkTmp()
  try {
    const { resourcesDir } = makeApp(root)
    await afterPack({
      appOutDir: root,
      electronPlatformName: 'darwin',
      packager: { appInfo: { productFilename: 'AI WorkDeck' } },
    })
    assert.deepStrictEqual(
      fs.readdirSync(resourcesDir).filter((n) => n.endsWith('.lproj')).sort(),
      ['Base.lproj', 'en.lproj', 'zh_CN.lproj'],
    )
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('afterPack 钩子在非 darwin 上一个文件都不动', async () => {
  const root = mkTmp()
  try {
    const { resourcesDir } = makeApp(root)
    const before = fs.readdirSync(resourcesDir).sort()
    for (const platform of ['win32', 'linux']) {
      await afterPack({
        appOutDir: root,
        electronPlatformName: platform,
        packager: { appInfo: { productFilename: 'AI WorkDeck' } },
      })
    }
    assert.deepStrictEqual(fs.readdirSync(resourcesDir).sort(), before)
    assert.strictEqual(before.filter((n) => n.endsWith('.lproj')).length, 5)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})
