// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 BUG-28 的真根因：mac 打包产物 Contents/Resources 下一个 *.lproj 都没有。
//
// electron-builder（app-builder-lib/out/electron/ElectronFramework.js 的
// removeUnusedLanguagesIfNeeded）拿 lproj 目录名去**精确匹配** electronLanguages：
// mac 上的目录叫 en.lproj / zh_CN.lproj，而顶层配置写的是 Windows 的 pak 名
// "en-US" / "zh-CN"，于是两个都被当成「不要的语言」删掉。macOS 按主 bundle 里的
// lproj 决定应用语言，一个都没有就退回 en——Chromium 内置 PDF 查看器的「更多」菜单
// （Two page view / Annotations / Document properties）因此在中文系统上也是英文，
// app.getLocale() 也恒为 en-US。
//
// 实测（J1 复核，dev Electron 30.5.1 复制一份改 Resources）：删光 Resources/*.lproj →
// locale en-US、PDF 菜单英文；只留 en.lproj + zh_CN.lproj → 中文系统上 locale zh-CN、
// 菜单「双页视图 / 注释 / 文档属性」。修法：mac 段单独给 electronLanguages 写 mac 的
// 目录名（平台段优先于顶层，Windows 仍按顶层的 pak 名裁）。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const PKG = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'))

/** 与 app-builder-lib 同一条裁剪规则：平台段优先、目录名精确匹配。 */
function kept(files, ext, platformLangs, topLangs) {
  const wanted = [].concat(platformLangs || topLangs || [])
  if (!wanted.length) return files
  return files.filter((f) => !f.endsWith(ext) || wanted.includes(f.slice(0, f.length - ext.length)))
}

test('金丝雀：app-builder-lib 仍按「平台段 || 顶层」取 electronLanguages，且按目录名精确匹配', () => {
  let src
  try {
    src = fs.readFileSync(require.resolve('app-builder-lib/out/electron/ElectronFramework.js'), 'utf8')
  } catch (e) {
    return // 没装 devDependencies 的环境（只跑单测）跳过；打包机上一定在
  }
  assert.match(src, /platformSpecificBuildOptions\.electronLanguages \|\| config\.electronLanguages/)
  assert.match(src, /wantedLanguages\.includes\(language\)/)
  assert.match(src, /langFileExt: "\.lproj"/)
})

test('mac 打包后 Contents/Resources 留得住 en.lproj 与 zh_CN.lproj（否则应用语言恒为英文）', () => {
  const macResources = ['en.lproj', 'zh_CN.lproj', 'ja.lproj', 'de.lproj', 'af.lproj', 'app.asar']
  const out = kept(macResources, '.lproj', PKG.build.mac && PKG.build.mac.electronLanguages, PKG.build.electronLanguages)
  assert.ok(out.includes('en.lproj'), 'en.lproj 被裁掉了')
  assert.ok(out.includes('zh_CN.lproj'), 'zh_CN.lproj 被裁掉了——中文系统上 PDF 查看器与 app.getLocale() 都会是英文')
  assert.ok(!out.includes('ja.lproj'), '其它语言照旧裁掉，不为这一条把体积放回去')
  assert.ok(out.includes('app.asar'))
})

test('Windows 的 locales/*.pak 仍按顶层 electronLanguages 裁，保留 en-US 与 zh-CN', () => {
  const winLocales = ['en-US.pak', 'zh-CN.pak', 'ja.pak', 'de.pak']
  const out = kept(winLocales, '.pak', PKG.build.win && PKG.build.win.electronLanguages, PKG.build.electronLanguages)
  assert.deepStrictEqual(out, ['en-US.pak', 'zh-CN.pak'])
})
