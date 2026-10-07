// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 编辑器本机字体（desktop/main/system-fonts.js）：name 表解析、TTC、坏文件、
// 推荐默认集封顶、启用集持久化只认已知 id、HTTP 取文件入口只认启用集。
// 全部在临时目录里造字体，绝不碰真实 ~/.aiworkdeck。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const sf = require('../main/system-fonts')

// ---- 造一个只有 name 表的最小 sfnt -------------------------------------------
function utf16be(s) {
  const le = Buffer.from(s, 'utf16le')
  for (let i = 0; i < le.length; i += 2) { const t = le[i]; le[i] = le[i + 1]; le[i + 1] = t }
  return le
}
function nameTable(records) {
  const strs = records.map((r) => (r.platform === 1 ? Buffer.from(r.str, 'latin1') : utf16be(r.str)))
  const header = Buffer.alloc(6 + records.length * 12)
  header.writeUInt16BE(0, 0)
  header.writeUInt16BE(records.length, 2)
  header.writeUInt16BE(header.length, 4)
  let off = 0
  records.forEach((r, i) => {
    const o = 6 + i * 12
    header.writeUInt16BE(r.platform, o)
    header.writeUInt16BE(r.enc != null ? r.enc : (r.platform === 1 ? 0 : 1), o + 2)
    header.writeUInt16BE(r.lang, o + 4)
    header.writeUInt16BE(r.nameID, o + 6)
    header.writeUInt16BE(strs[i].length, o + 8)
    header.writeUInt16BE(off, o + 10)
    off += strs[i].length
  })
  return Buffer.concat([header, ...strs])
}
// 返回 sfnt 字节；baseOffset = 该 face 在最终文件里的起点（表偏移是文件绝对偏移）
function sfnt(records, baseOffset = 0) {
  const name = nameTable(records)
  const head = Buffer.alloc(12 + 16)
  head.writeUInt32BE(0x00010000, 0)
  head.writeUInt16BE(1, 4)
  head.write('name', 12, 'latin1')
  head.writeUInt32BE(0, 16)
  head.writeUInt32BE(baseOffset + head.length, 20)
  head.writeUInt32BE(name.length, 24)
  return Buffer.concat([head, name])
}
function ttc(faceRecords) {
  const headerLen = 12 + faceRecords.length * 4
  const faces = []
  let off = headerLen
  for (const recs of faceRecords) { const b = sfnt(recs, off); faces.push({ off, b }); off += b.length }
  const h = Buffer.alloc(headerLen)
  h.write('ttcf', 0, 'latin1')
  h.writeUInt32BE(0x00010000, 4)
  h.writeUInt32BE(faceRecords.length, 8)
  faces.forEach((f, i) => h.writeUInt32BE(f.off, 12 + i * 4))
  return Buffer.concat([h, ...faces.map((f) => f.b)])
}
const FANGSONG = [
  { platform: 3, lang: 0x0409, nameID: 1, str: 'FangSong_GB2312' },
  { platform: 3, lang: 0x0804, nameID: 1, str: '仿宋_GB2312' },
  { platform: 3, lang: 0x0409, nameID: 2, str: 'Regular' },
]

function tmpDir(t, prefix) {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
  t.after(() => fs.rmSync(d, { recursive: true, force: true }))
  return d
}

test('name 表：中英文族名都取到，中文名作 primary', (t) => {
  const d = tmpDir(t, 'sf-')
  const f = path.join(d, 'fs.ttf')
  fs.writeFileSync(f, sfnt(FANGSONG))
  const r = sf.parseFontFile(f)
  assert.deepStrictEqual(r.families, ['仿宋_GB2312', 'FangSong_GB2312'])
  assert.strictEqual(r.primary, '仿宋_GB2312')
})

test('name 表：nameID 16（typographic family）优先于 1；Mac Roman 只作兜底', (t) => {
  const d = tmpDir(t, 'sf-')
  const a = path.join(d, 'a.otf')
  fs.writeFileSync(a, sfnt([
    { platform: 3, lang: 0x0409, nameID: 1, str: 'Foo Light' },
    { platform: 3, lang: 0x0409, nameID: 16, str: 'Foo' },
  ]))
  assert.deepStrictEqual(sf.parseFontFile(a).families, ['Foo'])
  const b = path.join(d, 'b.ttf')
  fs.writeFileSync(b, sfnt([{ platform: 1, lang: 0, nameID: 1, str: 'Old Mac Font' }]))
  assert.deepStrictEqual(sf.parseFontFile(b).families, ['Old Mac Font'])
})

test('TTC：逐个 face 解析并去重合并', (t) => {
  const d = tmpDir(t, 'sf-')
  const f = path.join(d, 'simsun.ttc')
  fs.writeFileSync(f, ttc([
    [{ platform: 3, lang: 0x0409, nameID: 1, str: 'SimSun' }, { platform: 3, lang: 0x0804, nameID: 1, str: '宋体' }],
    [{ platform: 3, lang: 0x0409, nameID: 1, str: 'NSimSun' }, { platform: 3, lang: 0x0804, nameID: 1, str: '新宋体' }],
    [{ platform: 3, lang: 0x0409, nameID: 1, str: 'SimSun' }],
  ]))
  const r = sf.parseFontFile(f)
  assert.strictEqual(r.faces, 3)
  assert.deepStrictEqual(r.families, ['宋体', '新宋体', 'SimSun', 'NSimSun'])
  assert.strictEqual(r.primary, '宋体')
})

test('坏文件：解析返回 null，扫描跳过且不抛', (t) => {
  const d = tmpDir(t, 'sf-')
  fs.writeFileSync(path.join(d, 'garbage.ttf'), Buffer.from('this is not a font at all, just text'))
  fs.writeFileSync(path.join(d, 'empty.otf'), Buffer.alloc(0))
  const bad = sfnt(FANGSONG); bad.writeUInt32BE(0xffffff00, 20) // name 表偏移指到文件外
  fs.writeFileSync(path.join(d, 'badoff.ttf'), bad)
  const ttcBad = Buffer.alloc(16); ttcBad.write('ttcf', 0, 'latin1'); ttcBad.writeUInt32BE(0xffffffff, 8)
  fs.writeFileSync(path.join(d, 'bad.ttc'), ttcBad)
  fs.writeFileSync(path.join(d, 'ok.ttf'), sfnt(FANGSONG))
  fs.writeFileSync(path.join(d, 'readme.txt'), 'x')
  for (const n of ['garbage.ttf', 'empty.otf', 'badoff.ttf', 'bad.ttc']) assert.strictEqual(sf.parseFontFile(path.join(d, n)), null, n)
  const inst = sf.createSystemFonts({ dataDir: path.join(d, 'data'), dirs: [{ dir: d, kind: 'user' }, { dir: path.join(d, 'missing'), kind: 'system' }] })
  const r = inst.listSystemFonts()
  assert.deepStrictEqual(r.fonts.map((f) => f.file), ['ok.ttf'])
})

test('真实字体文件（若构建产物里有）也能解析', (t) => {
  const root = path.join(__dirname, '../../frontend/dist/zetaoffice')
  const cands = ['cjk-kai.ttf', 'cjk-fangsong.ttf', 'cjk-serif.otf', 'cjk.ttc'].map((n) => path.join(root, n)).filter((p) => fs.existsSync(p))
  if (!cands.length) { t.skip('no built fonts'); return }
  for (const p of cands) {
    const r = sf.parseFontFile(p)
    assert.ok(r && r.families.length, p)
  }
})

// ---- 推荐默认集 ---------------------------------------------------------------
const MB = 1024 * 1024
function entry(id, families, sizeMB, kind = 'system', file) {
  return { id, families, sizeBytes: sizeMB * MB, kind, file: file || id + '.ttf' }
}

test('默认集：只选清单内字体；单文件超 40MB 跳过；总量封顶 120MB；按清单优先级装入', () => {
  const entries = [
    entry('misans', ['MiSans'], 5),
    entry('fs', ['仿宋_GB2312', 'FangSong_GB2312'], 4),
    entry('songti', ['宋体'], 50), // 单文件过大
    entry('arial', ['Arial'], 1),
    entry('xbs', ['方正小标宋简体'], 4),
    entry('yahei', ['Microsoft YaHei'], 30),
    entry('dengxian', ['DengXian'], 30),
    entry('kaiti', ['KaiTi'], 39),
    entry('heiti', ['SimHei'], 39),
  ]
  const ids = sf.selectDefaultIds(entries)
  assert.ok(!ids.includes('misans'))
  assert.ok(!ids.includes('songti'))
  assert.deepStrictEqual(ids.slice(0, 2), ['fs', 'xbs'])
  const total = ids.reduce((s, id) => s + entries.find((e) => e.id === id).sizeBytes, 0)
  assert.ok(total <= sf.MAX_DEFAULT_TOTAL_BYTES)
  // 4+4+39+39 = 86，再加 30 = 116，再加 30 超 → dengxian 被挤掉；Arial 1MB 仍能装下
  assert.deepStrictEqual(ids, ['fs', 'xbs', 'kaiti', 'heiti', 'yahei', 'arial'])
})

test('默认集：同名同族文件优先用户安装的那份', () => {
  const ids = sf.selectDefaultIds([
    entry('sys', ['SimHei', '黑体'], 10, 'system', 'simhei.ttf'),
    entry('usr', ['黑体', 'SimHei'], 10, 'user', 'SimHei.TTF'),
  ])
  assert.deepStrictEqual(ids, ['usr'])
})

// ---- 持久化 + 取文件入口 --------------------------------------------------------
function fixture(t) {
  const d = tmpDir(t, 'sf-')
  const fonts = path.join(d, 'fonts')
  fs.mkdirSync(fonts)
  fs.writeFileSync(path.join(fonts, 'fs.ttf'), sfnt(FANGSONG))
  fs.writeFileSync(path.join(fonts, 'misans.ttf'), sfnt([{ platform: 3, lang: 0x0409, nameID: 1, str: 'MiSans' }]))
  const dataDir = path.join(d, 'data')
  return { d, dataDir, inst: sf.createSystemFonts({ dataDir, dirs: [{ dir: fonts, kind: 'user' }] }) }
}

test('未保存过：启用集 = 推荐默认集，且不写配置文件', (t) => {
  const { dataDir, inst } = fixture(t)
  const r = inst.listSystemFonts()
  const byFile = Object.fromEntries(r.fonts.map((f) => [f.file, f]))
  assert.strictEqual(byFile['fs.ttf'].enabled, true)
  assert.strictEqual(byFile['fs.ttf'].recommended, true)
  assert.strictEqual(byFile['misans.ttf'].enabled, false)
  assert.strictEqual(r.maxRecommendedBytes, 200 * MB)
  assert.strictEqual(r.enabledBytes, byFile['fs.ttf'].sizeBytes)
  assert.ok(!fs.existsSync(path.join(dataDir, 'editor-fonts.json')))
})

test('setEnabledFonts：忽略未知 id，按绝对路径持久化，用户选择原样生效', (t) => {
  const { dataDir, inst } = fixture(t)
  const ids = Object.fromEntries(inst.listSystemFonts().fonts.map((f) => [f.file, f.id]))
  const res = inst.setEnabledFonts([ids['misans.ttf'], 'deadbeefdeadbeef', '../../etc/passwd', 42])
  assert.strictEqual(res.ok, true)
  assert.strictEqual(res.count, 1)
  const saved = JSON.parse(fs.readFileSync(path.join(dataDir, 'editor-fonts.json'), 'utf8'))
  assert.strictEqual(saved.version, 1)
  assert.strictEqual(saved.enabledPaths.length, 1)
  assert.ok(path.isAbsolute(saved.enabledPaths[0]) && saved.enabledPaths[0].endsWith('misans.ttf'))
  const after = Object.fromEntries(inst.listSystemFonts().fonts.map((f) => [f.file, f.enabled]))
  assert.deepStrictEqual(after, { 'fs.ttf': false, 'misans.ttf': true })
  // 全部取消也要被尊重（不能回落到默认集）
  inst.setEnabledFonts([])
  assert.deepStrictEqual(inst.enabledFontsForEditor(), [])
})

test('resolveEnabledFontPath：只认当前启用集里的 id，其它一律 null', (t) => {
  const { inst } = fixture(t)
  const ids = Object.fromEntries(inst.listSystemFonts().fonts.map((f) => [f.file, f.id]))
  assert.ok(inst.resolveEnabledFontPath(ids['fs.ttf']).endsWith('fs.ttf')) // 默认集里
  assert.strictEqual(inst.resolveEnabledFontPath(ids['misans.ttf']), null) // 扫到了但没启用
  for (const bad of ['', 'deadbeefdeadbeef', '../fs.ttf', '/etc/passwd', null, undefined, {}]) {
    assert.strictEqual(inst.resolveEnabledFontPath(bad), null, String(bad))
  }
  inst.setEnabledFonts([ids['misans.ttf']])
  assert.strictEqual(inst.resolveEnabledFontPath(ids['fs.ttf']), null)
  assert.ok(inst.resolveEnabledFontPath(ids['misans.ttf']).endsWith('misans.ttf'))
  const forEditor = inst.enabledFontsForEditor()
  assert.deepStrictEqual(forEditor.map((f) => f.families), [['MiSans']])
})

test('模块顶层不依赖 electron（纯 node 可加载）', () => {
  const src = fs.readFileSync(path.join(__dirname, '../main/system-fonts.js'), 'utf8')
  assert.ok(!/require\(['"]electron['"]\)/.test(src))
})
