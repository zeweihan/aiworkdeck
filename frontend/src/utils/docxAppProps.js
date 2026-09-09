// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * 给编辑器导出的 OOXML 字节打产品标识（可溯源性设计规范附录 B4）。
 *
 * 为什么在宿主侧做 zip 定点补丁，而不是让引擎写：真机探针实测（2026-09-09，
 * 引擎 24.2.8-zhcn-r4）——
 *   · `xModel.getDocumentProperties()` 上根本没有 setGenerator/getGenerator 方法
 *     （zetajs 把 XDocumentProperties 的 Generator 暴露成属性）；
 *   · 属性写法 `dp.Generator = 'AI WorkDeck 0.36.0'` **写得进也读得回**，但导出件的
 *     `docProps/app.xml` 里 `<Application>` 纹丝不动，仍是
 *     `ZetaOffice/24.2.8.0.beta1$Emscripten_x86 LibreOffice_project/<buildid>`；
 *   · `UserDefinedProperties.addProperty('Application', ...)` 只会多出一个
 *     `docProps/custom.xml`，标准的 `<Application>` 字段照旧不受影响。
 * 也就是说 oox 导出器把 Application 硬写成 `utl::DocInfoHelper::GetGeneratorString()`，
 * UNO API 够不着。要写只能在拿到字节之后改。
 *
 * 为什么是 async：引擎导出的每个条目都是 DEFLATE（真机实测 method=8、flag=0x0808，
 * 即带 data descriptor + UTF-8 名），要改 app.xml 的文字就得先解压。解压走浏览器/Node
 * 自带的 `DecompressionStream('deflate-raw')`（Chrome 103+ / Node 20+），是异步流 API；
 * 不引 jszip 之类的库——那会把整包重新压一遍，大文档在保存路径上不可接受。
 * 新写回的 app.xml 用 STORED（不压缩），省掉一次压缩、也省掉压缩参数的不确定性。
 *
 * 红线：**保存链路是数据安全红线**。这个函数宁可不打标，也绝不许弄坏用户的文件——
 * 任何一步不如预期（不是 zip、找不到 EOCD、ZIP64、没有 app.xml、解析抛异常、
 * 自检不过）一律原样返回输入字节。除 `docProps/app.xml` 外的每一个条目，其本地头
 * 与数据都逐字节原样拷贝（不重新压缩），只重写中央目录里的偏移量。
 */

const LOCAL_SIG = 0x04034b50
const CENTRAL_SIG = 0x02014b50
const EOCD_SIG = 0x06054b50
const ZIP64_LOCATOR_SIG = 0x07064b50
const APP_XML = 'docProps/app.xml'
const U32_MAX = 0xffffffff

// ---------------------------------------------------------------- CRC-32
let CRC_TABLE = null
function crcTable() {
  if (CRC_TABLE) return CRC_TABLE
  const t = new Int32Array(256)
  for (let i = 0; i < 256; i++) {
    let c = i
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[i] = c
  }
  CRC_TABLE = t
  return t
}
function crc32(u8) {
  const t = crcTable()
  let c = -1
  for (let i = 0; i < u8.length; i++) c = t[(c ^ u8[i]) & 0xff] ^ (c >>> 8)
  return (c ^ -1) >>> 0
}

// ---------------------------------------------------------------- 读写小工具
const u16 = (b, o) => b[o] | (b[o + 1] << 8)
const u32 = (b, o) => ((b[o] | (b[o + 1] << 8) | (b[o + 2] << 16)) + b[o + 3] * 0x1000000) >>> 0
function putU16(b, o, v) { b[o] = v & 0xff; b[o + 1] = (v >>> 8) & 0xff }
function putU32(b, o, v) {
  b[o] = v & 0xff; b[o + 1] = (v >>> 8) & 0xff
  b[o + 2] = (v >>> 16) & 0xff; b[o + 3] = (v >>> 24) & 0xff
}

/** EOCD 起点；找不到返回 -1。zip 注释最长 65535，所以只回扫 65557 字节。 */
function findEocd(b) {
  const min = Math.max(0, b.length - 65557)
  for (let i = b.length - 22; i >= min; i--) {
    if (u32(b, i) === EOCD_SIG) return i
  }
  return -1
}

/**
 * 解析中央目录。任何不支持的形态（ZIP64、多卷、条目数超 16 位）返回 null。
 * 返回 { entries, cdOffset, cdSize, eocdAt, comment }，entries 按中央目录原序。
 */
function readCentral(b) {
  const eocdAt = findEocd(b)
  if (eocdAt < 0) return null
  if (u16(b, eocdAt + 4) !== 0 || u16(b, eocdAt + 6) !== 0) return null // 多卷
  const total = u16(b, eocdAt + 10)
  if (u16(b, eocdAt + 8) !== total) return null
  if (total === 0 || total === 0xffff) return null // 0xffff 只可能是 ZIP64
  const cdSize = u32(b, eocdAt + 12)
  const cdOffset = u32(b, eocdAt + 16)
  if (cdSize === U32_MAX || cdOffset === U32_MAX) return null
  if (cdOffset + cdSize > b.length) return null
  // ZIP64 end-of-central-directory locator 紧挨在 EOCD 之前
  if (eocdAt >= 20 && u32(b, eocdAt - 20) === ZIP64_LOCATOR_SIG) return null

  const entries = []
  let p = cdOffset
  const dec = new TextDecoder('utf-8')
  for (let i = 0; i < total; i++) {
    if (p + 46 > cdOffset + cdSize) return null
    if (u32(b, p) !== CENTRAL_SIG) return null
    const nameLen = u16(b, p + 28)
    const extraLen = u16(b, p + 30)
    const commentLen = u16(b, p + 32)
    const headLen = 46 + nameLen + extraLen + commentLen
    if (p + headLen > cdOffset + cdSize) return null
    const compSize = u32(b, p + 20)
    const uncompSize = u32(b, p + 24)
    const localOffset = u32(b, p + 42)
    if (compSize === U32_MAX || uncompSize === U32_MAX || localOffset === U32_MAX) return null
    if (localOffset >= cdOffset) return null
    entries.push({
      name: dec.decode(b.subarray(p + 46, p + 46 + nameLen)),
      method: u16(b, p + 10),
      compSize,
      localOffset,
      centralAt: p,
      centralLen: headLen,
    })
    p += headLen
  }
  if (p !== cdOffset + cdSize) return null
  const comment = b.subarray(eocdAt + 22, eocdAt + 22 + u16(b, eocdAt + 20))
  if (eocdAt + 22 + comment.length !== b.length) return null
  return { entries, cdOffset, cdSize, eocdAt, comment }
}

/**
 * 每个条目在文件里占的区间 [start, end)：按本地头偏移排序，下一条的起点即本条的终点，
 * 最后一条到中央目录起点为止。这样连 data descriptor、对齐填充都原样带过去，
 * 不需要去猜本地头里那些可能与中央目录不一致的长度字段。
 */
function regionsOf(entries, cdOffset, b) {
  const sorted = entries.slice().sort((x, y) => x.localOffset - y.localOffset)
  for (let i = 0; i < sorted.length; i++) {
    const start = sorted[i].localOffset
    const end = i + 1 < sorted.length ? sorted[i + 1].localOffset : cdOffset
    if (end <= start) return null
    if (u32(b, start) !== LOCAL_SIG) return null
    sorted[i].start = start
    sorted[i].end = end
  }
  return sorted
}

// ---------------------------------------------------------------- app.xml 改写
function xmlEscape(s) {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 只换 <Application> 的文字；文件里没有这个元素就返回 null（此时整体放弃打标）。 */
function rewriteAppXml(text, application) {
  const re = /<Application>[\s\S]*?<\/Application>/
  if (!re.test(text)) return null
  return text.replace(re, '<Application>' + xmlEscape(application) + '</Application>')
}

// ---------------------------------------------------------------- 主函数
/**
 * 把 OOXML（docx/xlsx/pptx）字节里的 docProps/app.xml 的 `<Application>` 换成
 * `application`，其余条目逐字节原样保留。
 *
 * @param {Uint8Array} bytes 原始 zip 字节
 * @param {string} application 形如 `AI WorkDeck 0.36.0`
 * @returns {Promise<Uint8Array>} 打过标的新字节；任何一步不满足就是**原样的入参**
 */
export async function stampApplication(bytes, application) {
  try {
    if (!(bytes instanceof Uint8Array) || bytes.length < 22) return bytes
    if (typeof application !== 'string' || !application.trim()) return bytes
    if (bytes[0] !== 0x50 || bytes[1] !== 0x4b) return bytes // 'PK'

    const cd = readCentral(bytes)
    if (!cd) return bytes
    const target = cd.entries.find((e) => e.name === APP_XML)
    if (!target) return bytes

    const regions = regionsOf(cd.entries, cd.cdOffset, bytes)
    if (!regions) return bytes

    const appText = await readEntryText(bytes, target)
    if (appText == null) return bytes
    const newText = rewriteAppXml(appText, application)
    if (newText == null) return bytes
    const newData = new TextEncoder().encode(newText)
    const nameBytes = new TextEncoder().encode(APP_XML)
    const newCrc = crc32(newData)

    // ---- 拼新文件：本地条目按原文件顺序，app.xml 换成自造的 STORED 条目
    const parts = []
    let offset = 0
    const newOffsets = new Map()
    for (const e of regions) {
      newOffsets.set(e.name, offset)
      if (e.name === APP_XML) {
        const lh = new Uint8Array(30 + nameBytes.length)
        putU32(lh, 0, LOCAL_SIG)
        putU16(lh, 4, 20)          // version needed
        putU16(lh, 6, 0)           // flags：清掉 data descriptor / UTF-8 位（名字是纯 ASCII）
        putU16(lh, 8, 0)           // method = stored
        putU16(lh, 10, u16(bytes, e.localOffset + 10)) // mod time 沿用原值
        putU16(lh, 12, u16(bytes, e.localOffset + 12)) // mod date 沿用原值
        putU32(lh, 14, newCrc)
        putU32(lh, 18, newData.length)
        putU32(lh, 22, newData.length)
        putU16(lh, 26, nameBytes.length)
        putU16(lh, 28, 0)          // extra len
        lh.set(nameBytes, 30)
        parts.push(lh, newData)
        offset += lh.length + newData.length
      } else {
        const region = bytes.subarray(e.start, e.end)
        parts.push(region)
        offset += region.length
      }
    }

    // ---- 中央目录：原序、原字节，只改偏移；app.xml 那条重建
    const newCdOffset = offset
    for (const e of cd.entries) {
      const newOff = newOffsets.get(e.name)
      if (newOff === undefined) return bytes
      if (e.name === APP_XML) {
        const ch = new Uint8Array(46 + nameBytes.length)
        const src = e.centralAt
        putU32(ch, 0, CENTRAL_SIG)
        putU16(ch, 4, u16(bytes, src + 4))   // version made by
        putU16(ch, 6, 20)                    // version needed
        putU16(ch, 8, 0)                     // flags
        putU16(ch, 10, 0)                    // method = stored
        putU16(ch, 12, u16(bytes, src + 12)) // mod time
        putU16(ch, 14, u16(bytes, src + 14)) // mod date
        putU32(ch, 16, newCrc)
        putU32(ch, 20, newData.length)
        putU32(ch, 24, newData.length)
        putU16(ch, 28, nameBytes.length)
        putU16(ch, 30, 0)                    // extra len
        putU16(ch, 32, 0)                    // comment len
        putU16(ch, 34, 0)                    // disk start
        putU16(ch, 36, u16(bytes, src + 36)) // internal attrs
        putU32(ch, 38, u32(bytes, src + 38)) // external attrs
        putU32(ch, 42, newOff)
        ch.set(nameBytes, 46)
        parts.push(ch)
        offset += ch.length
      } else {
        const ch = bytes.slice(e.centralAt, e.centralAt + e.centralLen)
        putU32(ch, 42, newOff)
        parts.push(ch)
        offset += ch.length
      }
    }
    const newCdSize = offset - newCdOffset

    const eocd = new Uint8Array(22 + cd.comment.length)
    putU32(eocd, 0, EOCD_SIG)
    putU16(eocd, 8, cd.entries.length)
    putU16(eocd, 10, cd.entries.length)
    putU32(eocd, 12, newCdSize)
    putU32(eocd, 16, newCdOffset)
    putU16(eocd, 20, cd.comment.length)
    eocd.set(cd.comment, 22)
    parts.push(eocd)
    offset += eocd.length
    if (offset > U32_MAX) return bytes

    const out = new Uint8Array(offset)
    let at = 0
    for (const part of parts) { out.set(part, at); at += part.length }

    // ---- 自检：把自己的产物再解析一遍，任何一条对不上就退回原件
    const verify = readCentral(out)
    if (!verify) return bytes
    if (verify.entries.length !== cd.entries.length) return bytes
    if (verify.entries.map((e) => e.name).join('\n') !== cd.entries.map((e) => e.name).join('\n')) return bytes
    if (!regionsOf(verify.entries, verify.cdOffset, out)) return bytes
    const back = verify.entries.find((e) => e.name === APP_XML)
    if (!back || back.method !== 0) return bytes
    if ((await readEntryText(out, back)) !== newText) return bytes
    // 未改动的条目：数据区必须与原件逐字节相等
    for (const e of cd.entries) {
      if (e.name === APP_XML) continue
      const b2 = verify.entries.find((x) => x.name === e.name)
      const src = regions.find((x) => x.name === e.name)
      if (!b2 || !src) return bytes
      const len = src.end - src.start
      if (!sameBytes(bytes, src.start, out, b2.localOffset, len)) return bytes
    }
    return out
  } catch (e) {
    return bytes
  }
}

/** 条目的原始数据区（本地头之后、compSize 长）；越界返回 null。 */
function rawDataOf(b, entry) {
  const lo = entry.localOffset
  if (lo + 30 > b.length) return null
  if (u32(b, lo) !== LOCAL_SIG) return null
  const start = lo + 30 + u16(b, lo + 26) + u16(b, lo + 28)
  const end = start + entry.compSize
  if (end > b.length) return null
  return b.subarray(start, end)
}

/** 取条目的 UTF-8 文本；只支持 STORED(0) 与 DEFLATE(8)，其余（含加密）返回 null。 */
async function readEntryText(b, entry) {
  const raw = rawDataOf(b, entry)
  if (!raw) return null
  if (entry.method === 0) return new TextDecoder('utf-8').decode(raw)
  if (entry.method !== 8) return null
  if (typeof DecompressionStream !== 'function') return null
  try {
    const ds = new DecompressionStream('deflate-raw')
    const stream = new Blob([raw]).stream().pipeThrough(ds)
    const buf = new Uint8Array(await new Response(stream).arrayBuffer())
    return new TextDecoder('utf-8').decode(buf)
  } catch (e) {
    return null
  }
}

function sameBytes(a, aOff, b, bOff, len) {
  if (aOff + len > a.length || bOff + len > b.length) return false
  for (let i = 0; i < len; i++) if (a[aOff + i] !== b[bOff + i]) return false
  return true
}
