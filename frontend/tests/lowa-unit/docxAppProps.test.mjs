// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// docxAppProps.stampApplication 的契约（可溯源性设计规范附录 B4）：
//   ① docProps/app.xml 的 <Application> 换成给定的串；
//   ② 其余条目**逐个**解出来与原件字节相等（这条是数据安全红线：保存链路上
//      弄坏用户文档比不打标严重得多）；
//   ③ 任何坏输入原样返回入参（同一个对象引用，证明连拷贝都没做）。
// 夹具用 lowa-e2e 的两份真 OOXML（docx 与 pptx），不手搓 zip。

import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import JSZip from 'jszip'
import { stampApplication } from '../../src/utils/docxAppProps.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const fixture = (n) => new Uint8Array(fs.readFileSync(path.join(here, '../lowa-e2e/fixtures', n)))
const APP = 'AI WorkDeck 9.9.9'

// flexmark-table.docx 的 app.xml 是 docx4j 出的空壳（自闭合 <properties:Properties/>，
// 里面根本没有 <Application> 元素）。本函数的契约是**只替换不插入**——碰不到元素就
// 原样返回，这条单独断言（见末尾用例）。docx 这一侧的正例用它当底座、把 Application
// 补进去再重新打包（jszip 出的包没有 data descriptor，与引擎导出的形态互补）。
async function docxWithApplication() {
  const zip = await JSZip.loadAsync(Buffer.from(fixture('flexmark-table.docx')))
  zip.file('docProps/app.xml',
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    + '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">'
    + '<Template></Template><TotalTime>0</TotalTime>'
    + '<Application>ZetaOffice/24.2.8.0.beta1$Emscripten_x86 LibreOffice_project/dced3bc</Application>'
    + '<AppVersion>15.0000</AppVersion><Pages>1</Pages><Words>7</Words></Properties>')
  return new Uint8Array(await zip.generateAsync({ type: 'uint8array' }))
}

async function entriesOf(bytes) {
  const zip = await JSZip.loadAsync(Buffer.from(bytes))
  const out = new Map()
  for (const name of Object.keys(zip.files)) {
    if (zip.files[name].dir) continue
    out.set(name, new Uint8Array(await zip.files[name].async('uint8array')))
  }
  return out
}

const SOURCES = [
  ['docx（补了 Application 的 flexmark-table）', docxWithApplication],
  ['pptx（impress-smoke，原件带 Microsoft PowerPoint 的 Application）', async () => fixture('impress-smoke.pptx')],
]

for (const [name, load] of SOURCES) {
  test(name + '：只有 app.xml 变，其余条目内容逐字节相等', async () => {
    const input = await load()
    const before = await entriesOf(input)
    assert.ok(before.has('docProps/app.xml'), '夹具本身要带 docProps/app.xml')

    const out = await stampApplication(input, APP)
    assert.notEqual(out, input, '应该产出了新字节')

    const after = await entriesOf(out)
    assert.deepEqual([...after.keys()].sort(), [...before.keys()].sort(), '条目名单不许变')

    for (const [k, v] of before) {
      if (k === 'docProps/app.xml') continue
      assert.deepEqual(after.get(k), v, '未改动条目的内容必须逐字节相等: ' + k)
    }

    const appXml = new TextDecoder().decode(after.get('docProps/app.xml'))
    assert.match(appXml, new RegExp('<Application>' + APP + '</Application>'))
    assert.equal((appXml.match(/<Application>/g) || []).length, 1, '只能有一个 Application 元素')
    // 统计字段等其余内容原样保留（Word 的「属性」面板要用）
    const oldXml = new TextDecoder().decode(before.get('docProps/app.xml'))
    const strip = (s) => s.replace(/<Application>[\s\S]*?<\/Application>/, '')
    assert.equal(strip(appXml), strip(oldXml), 'app.xml 里除 Application 外一个字符都不该动')
  })

  test(name + '：产出件的 CRC 与结构自洽（jszip 能无警告解开）', async () => {
    const out = await stampApplication(await load(), APP)
    // jszip 默认按 CRC 校验；checkCRC32 显式打开，坏 CRC 会抛
    const zip = await JSZip.loadAsync(Buffer.from(out), { checkCRC32: true })
    assert.ok(zip.file('docProps/app.xml'))
  })
}

test('幂等：对已打过标的字节再打一次，结果仍只有一个 Application 且可解', async () => {
  const once = await stampApplication(await docxWithApplication(), APP)
  const twice = await stampApplication(once, APP)
  const after = await entriesOf(twice)
  const appXml = new TextDecoder().decode(after.get('docProps/app.xml'))
  assert.equal((appXml.match(/<Application>/g) || []).length, 1)
  assert.match(appXml, new RegExp('<Application>' + APP + '</Application>'))
})

test('XML 特殊字符被转义', async () => {
  const out = await stampApplication(await docxWithApplication(), 'A & B <x> 1.0')
  const after = await entriesOf(out)
  const appXml = new TextDecoder().decode(after.get('docProps/app.xml'))
  assert.match(appXml, /<Application>A &amp; B &lt;x&gt; 1\.0<\/Application>/)
})

test('坏输入一律原样返回入参对象本身', async () => {
  const good = await docxWithApplication()
  const cases = [
    ['非 zip（不以 PK 开头）', new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3])],
    ['太短', new Uint8Array([0x50, 0x4b, 3, 4])],
    ['空数组', new Uint8Array(0)],
    ['PK 开头但没有 EOCD', (() => { const b = new Uint8Array(200); b[0] = 0x50; b[1] = 0x4b; return b })()],
    ['尾部被截断（EOCD 指向的中央目录越界）', good.slice(0, good.length - 40)],
  ]
  for (const [label, input] of cases) {
    const out = await stampApplication(input, APP)
    assert.equal(out, input, label + ' 应原样返回入参')
  }
  // application 串本身不合法
  for (const bad of [null, undefined, '', '   ', 42]) {
    assert.equal(await stampApplication(good, bad), good, 'application=' + String(bad) + ' 应原样返回')
  }
  // 不是 Uint8Array
  const buf = Buffer.from(good) // Buffer 是 Uint8Array 子类，仍应正常处理
  assert.notEqual(await stampApplication(buf, APP), buf)
  const arr = Array.from(good.slice(0, 30))
  assert.equal(await stampApplication(arr, APP), arr, '普通数组应原样返回')
})

test('没有 docProps/app.xml 的 zip 原样返回', async () => {
  const zip = new JSZip()
  zip.file('hello.txt', 'hi')
  const bytes = new Uint8Array(await zip.generateAsync({ type: 'uint8array' }))
  assert.equal(await stampApplication(bytes, APP), bytes)
})

test('app.xml 里没有 <Application> 元素时原样返回（只替换，不插入）', async () => {
  const raw = fixture('flexmark-table.docx')
  assert.doesNotMatch(
    new TextDecoder().decode((await entriesOf(raw)).get('docProps/app.xml')),
    /<Application>/,
    '夹具前提：这份 docx 的 app.xml 里确实没有 Application 元素')
  assert.equal(await stampApplication(raw, APP), raw)
})

test('中央目录被人为改坏时原样返回（自检兜底）', async () => {
  const good = await docxWithApplication()
  const broken = good.slice()
  // 把第一个中央目录条目的签名打掉：readCentral 会拒绝
  const eocdAt = (() => {
    for (let i = broken.length - 22; i >= 0; i--) {
      if (broken[i] === 0x50 && broken[i + 1] === 0x4b && broken[i + 2] === 5 && broken[i + 3] === 6) return i
    }
    return -1
  })()
  assert.ok(eocdAt > 0, '夹具里应能找到 EOCD')
  const cdOffset = broken[eocdAt + 16] | (broken[eocdAt + 17] << 8) | (broken[eocdAt + 18] << 16) | (broken[eocdAt + 19] * 0x1000000)
  broken[cdOffset] = 0x00
  assert.equal(await stampApplication(broken, APP), broken)
})
