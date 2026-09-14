// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 三方合并 lowa-e2e 组的夹具生成器（spec 2026-09-14-docx-three-way-merge-design §7）。
//
// 产出三份同源 docx（base / main / other）与一份「后端本该算出来的」分析结果：
// baseUnits（正文段落 + 表格单元的归一序列）、plan（mainChunks / otherChunks）、
// conflicts、以及「重放 + 全部接受」之后的预期正文。这里不跑 POI、不跑 JGit——
// 编辑是本脚本自己造的，所以每个块的坐标与新文本是**已知量**，直接写出来即可；
// 后端（Task B1 的 ThreeWayAnalyzer）要算的就是同一份东西，形状照 spec §4.2。
//
// 产物**不入库**：run.mjs 直接 import generateMergeFixtures() 在内存里造；
// 单独调试时 `node gen.mjs --out <dir>` 会把三份 docx 与 plan.json 落到磁盘。
//
// 文档形制（与本会话 spike 夹具同形）：420 段正文 + 2 张表 + 3 条批注。
// body 元素序：p0..p100 → 表0(3x3) → p101..p200 → 表1(2x2) → p201..p419。
// 段落键 p{i} 的 i 是**正文段落序**（不含表格里的段落，与引擎 get_paragraph 同构）；
// 表格单元键 t{ti}.{ri}.{ci}，ri/ci 从 0 起，插在该表在 body 里的位置上。

import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import zlib from 'node:zlib'

// ---------- 最小 zip 写入器（docx = zip，不引第三方依赖） ----------
const CRC_TABLE = (() => {
  const t = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c
  }
  return t
})()
function crc32(buf) {
  let c = -1
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ -1) >>> 0
}
function zipBytes(entries) {
  // entries: [{name, data:Buffer}]；固定时间戳，保证同一输入必得同一字节。
  const locals = []
  const central = []
  let offset = 0
  for (const e of entries) {
    const nameBuf = Buffer.from(e.name, 'utf8')
    const comp = zlib.deflateRawSync(e.data, { level: 9 })
    const crc = crc32(e.data)
    const lh = Buffer.alloc(30)
    lh.writeUInt32LE(0x04034b50, 0)
    lh.writeUInt16LE(20, 4)          // version needed
    lh.writeUInt16LE(0x0800, 6)      // UTF-8 名字
    lh.writeUInt16LE(8, 8)           // deflate
    lh.writeUInt16LE(0, 10); lh.writeUInt16LE(0x21, 12) // 固定 1980-01-01
    lh.writeUInt32LE(crc, 14)
    lh.writeUInt32LE(comp.length, 18)
    lh.writeUInt32LE(e.data.length, 22)
    lh.writeUInt16LE(nameBuf.length, 26)
    lh.writeUInt16LE(0, 28)
    locals.push(lh, nameBuf, comp)
    const ch = Buffer.alloc(46)
    ch.writeUInt32LE(0x02014b50, 0)
    ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6)
    ch.writeUInt16LE(0x0800, 8)
    ch.writeUInt16LE(8, 10)
    ch.writeUInt16LE(0, 12); ch.writeUInt16LE(0x21, 14)
    ch.writeUInt32LE(crc, 16)
    ch.writeUInt32LE(comp.length, 20)
    ch.writeUInt32LE(e.data.length, 24)
    ch.writeUInt16LE(nameBuf.length, 28)
    ch.writeUInt32LE(offset, 42)
    central.push(ch, nameBuf)
    offset += 30 + nameBuf.length + comp.length
  }
  const centralBuf = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(centralBuf.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, centralBuf, end])
}

// ---------- OOXML 片段 ----------
const W_NS = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
function run(text, { bold = false } = {}) {
  const rPr = bold ? '<w:rPr><w:b/></w:rPr>' : ''
  return '<w:r>' + rPr + '<w:t xml:space="preserve">' + esc(text) + '</w:t></w:r>'
}
function para(text, { bold = false, commentId = null } = {}) {
  if (commentId == null) return '<w:p>' + run(text, { bold }) + '</w:p>'
  return '<w:p><w:commentRangeStart w:id="' + commentId + '"/>' + run(text, { bold })
    + '<w:commentRangeEnd w:id="' + commentId + '"/>'
    + '<w:r><w:commentReference w:id="' + commentId + '"/></w:r></w:p>'
}
function tableXml(rows) {
  const cols = rows[0].length
  const grid = '<w:tblGrid>' + '<w:gridCol w:w="2400"/>'.repeat(cols) + '</w:tblGrid>'
  const borders = '<w:tblBorders>'
    + ['top', 'left', 'bottom', 'right', 'insideH', 'insideV']
      .map((s) => '<w:' + s + ' w:val="single" w:sz="4" w:space="0" w:color="000000"/>').join('')
    + '</w:tblBorders>'
  const body = rows.map((r) => '<w:tr>' + r.map((cell) =>
    '<w:tc><w:tcPr><w:tcW w:w="2400" w:type="dxa"/></w:tcPr>' + para(cell) + '</w:tc>').join('') + '</w:tr>').join('')
  return '<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/>' + borders + '</w:tblPr>' + grid + body + '</w:tbl>'
}

const CONTENT_TYPES = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/>
</Types>`
const ROOT_RELS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>`
const DOC_RELS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/>
</Relationships>`
const STYLES = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="${W_NS}">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Times New Roman" w:eastAsia="SimSun" w:hAnsi="Times New Roman"/><w:sz w:val="24"/></w:rPr></w:rPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
</w:styles>`

function commentsXml(comments) {
  return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<w:comments xmlns:w="' + W_NS + '">'
    + comments.map((c, i) => '<w:comment w:id="' + i + '" w:author="' + esc(c.author)
      + '" w:date="2026-09-01T10:0' + i + ':00Z" w:initials="' + esc(c.initials) + '">'
      + para(c.text) + '</w:comment>').join('')
    + '</w:comments>'
}

function docxBytes({ bodyXml, comments }) {
  const document = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    + '<w:document xmlns:w="' + W_NS + '"><w:body>' + bodyXml
    + '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
    + '<w:pgMar w:top="1440" w:right="1080" w:bottom="1440" w:left="1080" w:header="720" w:footer="720" w:gutter="0"/>'
    + '</w:sectPr></w:body></w:document>'
  return zipBytes([
    { name: '[Content_Types].xml', data: Buffer.from(CONTENT_TYPES, 'utf8') },
    { name: '_rels/.rels', data: Buffer.from(ROOT_RELS, 'utf8') },
    { name: 'word/document.xml', data: Buffer.from(document, 'utf8') },
    { name: 'word/_rels/document.xml.rels', data: Buffer.from(DOC_RELS, 'utf8') },
    { name: 'word/styles.xml', data: Buffer.from(STYLES, 'utf8') },
    { name: 'word/comments.xml', data: Buffer.from(commentsXml(comments), 'utf8') },
  ])
}

// ---------- 夹具形制 ----------
export const PARA_COUNT = 420
const TABLE0_AFTER_PARA = 100   // 表0 排在正文第 100 段之后
const TABLE1_AFTER_PARA = 200   // 表1 排在正文第 200 段之后
const TABLE0 = [
  ['款项名称', '金额（元）', '支付期限'],
  ['首付款', '1,000,000', '合同签署后三十日内'],
  ['尾款', '2,000,000', '验收合格后六十日内'],
]
const TABLE1 = [['违约情形', '违约金比例'], ['逾期付款', '每日万分之五']]
const COMMENT_PARAS = [5, 150, 300]
const COMMENTS = [
  { author: '张律师', initials: 'Z', text: '此处账期需与主协议一致。' },
  { author: '张律师', initials: 'Z', text: '请核对该条编号。' },
  { author: '李律师', initials: 'L', text: '建议补充违约责任。' },
]

// 段落原文：每段自带唯一标记【P{i}】，比对与定位都不用猜。
function baseParaText(i) {
  return '第 ' + (i + 1) + ' 条　【P' + i + '】甲乙双方应于三十日内完成本条约定事项，逾期按日万分之五计算违约金。'
}

// 归一（与后端 DocxUnitReader.normalize 同口径）：trim + 连续空白折一个 + NFC。
export function normalize(s) {
  return String(s == null ? '' : s).replace(/\s+/g, ' ').trim().normalize('NFC')
}

// 主线侧（我方）改动：5 个段落各换一处期限用词。
const MAIN_EDITS = [
  { para: 10, from: '三十日', to: '六十日' },
  { para: 50, from: '三十日', to: '九十日' },
  { para: 120, from: '万分之五', to: '万分之三' },
  { para: 250, from: '三十日', to: '十日' },
  { para: 300, from: '约定事项', to: '约定的全部事项' },
]
// 同一段两边都改了（冲突，不重放）。
const CONFLICT_PARA = 400
const MAIN_CONFLICT = { from: '三十日', to: '十五日' }
const OTHER_CONFLICT = { from: '三十日', to: '四十五日' }
// 另一侧（律师乙）改动：4 段文字 + 1 段插入 + 1 段删除 + 1 个表格单元 + 1 段纯格式。
const OTHER_EDITS = [
  { para: 20, from: '三十日', to: '七日' },
  { para: 60, from: '万分之五', to: '万分之八' },
  { para: 130, from: '约定事项', to: '约定的交付事项' },
  { para: 260, from: '三十日', to: '二十日' },
]
const OTHER_INSERT_AFTER_PARA = 150
const OTHER_INSERT_TEXT = '第 150 条之一　【新增】乙方另行承诺：本条项下的保证责任不因主合同变更而免除。'
const OTHER_DELETE_PARA = 310
const OTHER_TABLE_CELL = { table: 0, row: 2, col: 2, to: '验收合格后三十日内' }
const OTHER_FORMAT_ONLY_PARA = 350   // 只加粗、文字一字不改

function applyEdit(text, edit) {
  if (text.indexOf(edit.from) < 0) throw new Error('夹具编辑落空: ' + JSON.stringify(edit))
  return text.replace(edit.from, edit.to)
}

// 正文段落序 → 单元序列下标（表格单元插在该表在 body 里的位置上）。
function unitIndexOfPara(i) {
  const t0 = TABLE0.length * TABLE0[0].length
  const t1 = TABLE1.length * TABLE1[0].length
  if (i <= TABLE0_AFTER_PARA) return i
  if (i <= TABLE1_AFTER_PARA) return i + t0
  return i + t0 + t1
}
function unitIndexOfCell(ti, ri, ci) {
  const cols = ti === 0 ? TABLE0[0].length : TABLE1[0].length
  const within = ri * cols + ci
  return ti === 0
    ? TABLE0_AFTER_PARA + 1 + within
    : TABLE1_AFTER_PARA + TABLE0.length * TABLE0[0].length + 1 + within
}

function buildBody(side) {
  const parts = []
  const paraTexts = []
  for (let i = 0; i < PARA_COUNT; i++) {
    if (i === TABLE0_AFTER_PARA + 1) parts.push(tableXml(side === 'other' ? otherTable(0) : TABLE0))
    if (i === TABLE1_AFTER_PARA + 1) parts.push(tableXml(side === 'other' ? otherTable(1) : TABLE1))
    if (side === 'other' && i === OTHER_DELETE_PARA) continue      // 律师乙删掉这一段
    let text = baseParaText(i)
    let bold = false
    if (side === 'main') {
      const e = MAIN_EDITS.find((x) => x.para === i)
      if (e) text = applyEdit(text, e)
      if (i === CONFLICT_PARA) text = applyEdit(text, MAIN_CONFLICT)
    } else if (side === 'other') {
      const e = OTHER_EDITS.find((x) => x.para === i)
      if (e) text = applyEdit(text, e)
      if (i === CONFLICT_PARA) text = applyEdit(text, OTHER_CONFLICT)
      if (i === OTHER_FORMAT_ONLY_PARA) bold = true
    }
    const ci = COMMENT_PARAS.indexOf(i)
    parts.push(para(text, { bold, commentId: ci >= 0 ? ci : null }))
    paraTexts.push(text)
    if (side === 'other' && i === OTHER_INSERT_AFTER_PARA) {
      parts.push(para(OTHER_INSERT_TEXT))
      paraTexts.push(OTHER_INSERT_TEXT)
    }
  }
  return { xml: parts.join(''), paraTexts }
}
function otherTable(ti) {
  const rows = (ti === 0 ? TABLE0 : TABLE1).map((r) => r.slice())
  if (OTHER_TABLE_CELL.table === ti) rows[OTHER_TABLE_CELL.row][OTHER_TABLE_CELL.col] = OTHER_TABLE_CELL.to
  return rows
}

// ---------- 对外接口 ----------
export function generateMergeFixtures() {
  const base = buildBody('base')
  const main = buildBody('main')
  const other = buildBody('other')

  // baseUnits：正文段落 + 表格单元，表格单元跟在它所在表的 body 位置之后。
  const baseUnits = []
  const pushPara = (i) => baseUnits.push({ key: 'p' + i, norm: normalize(baseParaText(i)) })
  const pushTable = (ti, rows) => {
    for (let r = 0; r < rows.length; r++) {
      for (let c = 0; c < rows[r].length; c++) {
        baseUnits.push({ key: 't' + ti + '.' + r + '.' + c, norm: normalize(rows[r][c]) })
      }
    }
  }
  for (let i = 0; i < PARA_COUNT; i++) {
    pushPara(i)
    if (i === TABLE0_AFTER_PARA) pushTable(0, TABLE0)
    if (i === TABLE1_AFTER_PARA) pushTable(1, TABLE1)
  }

  // plan：后端 ThreeWayAnalyzer 本该给出的块序列（坐标 = baseUnits 下标）。
  const mainChunks = MAIN_EDITS.map((e) => ({
    type: 'MODIFY', baseStart: unitIndexOfPara(e.para), baseEnd: unitIndexOfPara(e.para) + 1,
    texts: [applyEdit(baseParaText(e.para), e)], ids: [], conflict: false,
  }))
  mainChunks.push({
    type: 'MODIFY', baseStart: unitIndexOfPara(CONFLICT_PARA), baseEnd: unitIndexOfPara(CONFLICT_PARA) + 1,
    texts: [applyEdit(baseParaText(CONFLICT_PARA), MAIN_CONFLICT)], ids: [], conflict: true,
  })
  mainChunks.sort((a, b) => a.baseStart - b.baseStart)

  const otherChunks = OTHER_EDITS.map((e) => ({
    type: 'MODIFY', baseStart: unitIndexOfPara(e.para), baseEnd: unitIndexOfPara(e.para) + 1,
    texts: [applyEdit(baseParaText(e.para), e)], ids: [], conflict: false,
  }))
  otherChunks.push({
    type: 'MODIFY',
    baseStart: unitIndexOfCell(OTHER_TABLE_CELL.table, OTHER_TABLE_CELL.row, OTHER_TABLE_CELL.col),
    baseEnd: unitIndexOfCell(OTHER_TABLE_CELL.table, OTHER_TABLE_CELL.row, OTHER_TABLE_CELL.col) + 1,
    texts: [OTHER_TABLE_CELL.to], ids: [], conflict: false,
  })
  otherChunks.push({
    type: 'INSERT', baseStart: unitIndexOfPara(OTHER_INSERT_AFTER_PARA) + 1,
    baseEnd: unitIndexOfPara(OTHER_INSERT_AFTER_PARA) + 1,
    texts: [OTHER_INSERT_TEXT], ids: [], conflict: false,
  })
  otherChunks.push({
    type: 'DELETE', baseStart: unitIndexOfPara(OTHER_DELETE_PARA), baseEnd: unitIndexOfPara(OTHER_DELETE_PARA) + 1,
    texts: [], ids: [], conflict: false,
  })
  otherChunks.push({
    type: 'MODIFY', baseStart: unitIndexOfPara(CONFLICT_PARA), baseEnd: unitIndexOfPara(CONFLICT_PARA) + 1,
    texts: [applyEdit(baseParaText(CONFLICT_PARA), OTHER_CONFLICT)], ids: [], conflict: true,
  })
  otherChunks.sort((a, b) => a.baseStart - b.baseStart)

  // 「重放 + 全部接受」之后的预期正文段落（表格里的段落不计入，与引擎同构）。
  const expectedParagraphs = []
  for (let i = 0; i < PARA_COUNT; i++) {
    if (i === OTHER_DELETE_PARA) continue
    let text = baseParaText(i)
    const m = MAIN_EDITS.find((x) => x.para === i)
    if (m) text = applyEdit(text, m)
    const o = OTHER_EDITS.find((x) => x.para === i)
    if (o) text = applyEdit(text, o)
    if (i === CONFLICT_PARA) text = applyEdit(text, MAIN_CONFLICT)   // 冲突段留主线侧
    expectedParagraphs.push(text)
    if (i === OTHER_INSERT_AFTER_PARA) expectedParagraphs.push(OTHER_INSERT_TEXT)
  }

  return {
    base: docxBytes({ bodyXml: base.xml, comments: COMMENTS }),
    main: docxBytes({ bodyXml: main.xml, comments: COMMENTS }),
    other: docxBytes({ bodyXml: other.xml, comments: COMMENTS }),
    baseUnits,
    plan: { mainChunks, otherChunks },
    conflicts: ['p' + CONFLICT_PARA],
    expected: {
      paragraphs: expectedParagraphs,
      paragraphCount: expectedParagraphs.length,
      conflictParaKey: CONFLICT_PARA,
      conflictMainText: applyEdit(baseParaText(CONFLICT_PARA), MAIN_CONFLICT),
      conflictOtherText: applyEdit(baseParaText(CONFLICT_PARA), OTHER_CONFLICT),
      // 冲突段被「用律师乙的」替换后，整份正文的样子。
      takeOtherParagraphs: expectedParagraphs.map((t) =>
        t === applyEdit(baseParaText(CONFLICT_PARA), MAIN_CONFLICT)
          ? applyEdit(baseParaText(CONFLICT_PARA), OTHER_CONFLICT) : t),
      formatOnlyParaKey: OTHER_FORMAT_ONLY_PARA,
      formatOnlyText: baseParaText(OTHER_FORMAT_ONLY_PARA),
      insertedText: OTHER_INSERT_TEXT,
      deletedText: baseParaText(OTHER_DELETE_PARA),
      tableCell: { table: 0, row: 2, col: 2, text: OTHER_TABLE_CELL.to },
      mainEditCount: MAIN_EDITS.length + 1,      // 含冲突段
      otherReplayCount: OTHER_EDITS.length + 3,  // 4 段文字 + 表格单元 + 插入 + 删除
      commentCount: COMMENTS.length,
    },
  }
}

// ---------- CLI（调试用；产物不入库） ----------
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const outIdx = process.argv.indexOf('--out')
  const out = outIdx > 0 ? process.argv[outIdx + 1] : path.join(process.env.TMPDIR || '/tmp', 'awd-merge-fixtures')
  fs.mkdirSync(out, { recursive: true })
  const f = generateMergeFixtures()
  for (const name of ['base', 'main', 'other']) fs.writeFileSync(path.join(out, name + '.docx'), f[name])
  fs.writeFileSync(path.join(out, 'plan.json'), JSON.stringify({
    baseUnits: f.baseUnits, plan: f.plan, conflicts: f.conflicts, expected: f.expected,
  }, null, 2))
  console.log('已生成 / generated: ' + out)
  console.log('  base.docx ' + f.base.length + ' B, main.docx ' + f.main.length + ' B, other.docx ' + f.other.length + ' B')
  console.log('  baseUnits ' + f.baseUnits.length + '，mainChunks ' + f.plan.mainChunks.length
    + '，otherChunks ' + f.plan.otherChunks.length + '，预期合并后 ' + f.expected.paragraphCount + ' 段')
}
