/**
 * 文档镜像采集（dev-board#299）：拿当前文档的**原始文件字节**（.docx/.xlsx/.pptx）。
 *
 * 能力矩阵（官方文档实锤，spec 2026-08-30-addin-project-binding-and-mirrors-design.md）：
 * - Office 桌面版（Win/Mac）三宿主：Office.context.document.getFileAsync(Compressed)，
 *   4MB 分片；网页版 Word/Excel 拿不到 Compressed（PPT 网页版可以）。
 * - WPS 家族：Document.FullName + Application.FileSystem.readAsBinaryString 的理论链
 *   （代码库零先例、真机未验证）——全程 try/catch 探测，且产物必须过 ZIP 头校验。
 *
 * 纪律（维护者拍板点 4）：拿不到字节的环境**只提示不硬凑**——绝不上传文本重构的假文档。
 * getFileAsync 取到的是内存态还是上次保存态官方无断言：镜像语义按「可能滞后于未保存
 * 编辑」接受，不构成数据安全问题（权威文档永远是宿主里那份）。
 */
import { hostFamily, detectHost, readDocumentMeta } from './hostBridge.js'

/** 单次快照上限：超大文档不进镜像通道（3GB 共池配额，别让一份文件独吞）。 */
export const SNAPSHOT_MAX_BYTES = 50 * 1024 * 1024

/**
 * office_command 里的纯读命令：这些执行过不算「文档被改过」，不触发快照。
 * 名单按 officeExecutor/wpsExecutor 的 HANDLERS 读取面维护；不在名单里的一律按写处理
 * （宁多拍一张——内容哈希不变时上传会被跳过，多拍无害，漏拍才是丢归档）。
 */
const READ_ONLY_COMMANDS = new Set([
  'get_text', 'get_selection', 'search', 'get_formatting', 'get_comments', 'get_revisions',
  'table_read',
  'excel_get_range', 'excel_search', 'excel_get_overview', 'excel_get_comments',
  'ppt_get_slides', 'ppt_get_slide_details', 'ppt_table_read'
])

export function isReadOnlyCommand(command) {
  return READ_ONLY_COMMANDS.has(String(command || ''))
}

/** OOXML 一定是 ZIP：头两字节 'PK'。编码存疑的读取路（WPS binary string）靠它兜底。 */
function looksLikeZip(bytes) {
  return bytes && bytes.length > 4 && bytes[0] === 0x50 && bytes[1] === 0x4b
}

function extForHost(host) {
  return host === 'excel' ? '.xlsx' : host === 'powerpoint' ? '.pptx' : '.docx'
}

/** 快照文件名：文档显示名 + 按宿主补扩展名（显示名常不带扩展名）。 */
function snapshotFileName(preferredName) {
  const host = detectHost() || 'word'
  const meta = readDocumentMeta()
  let name = (preferredName || (meta && meta.name) || 'document').trim()
  name = name.replace(/[\\/]/g, '_')
  const ext = extForHost(host)
  if (!/\.(docx?|xlsx?|pptx?)$/i.test(name)) name += ext
  return name
}

function toUint8(data) {
  if (data instanceof Uint8Array) return data
  if (data instanceof ArrayBuffer) return new Uint8Array(data)
  if (Array.isArray(data)) return Uint8Array.from(data)
  if (typeof data === 'string') {
    // binary string（每字符一个字节）：readAsBinaryString / 某些宿主的 slice 形态
    const out = new Uint8Array(data.length)
    for (let i = 0; i < data.length; i++) out[i] = data.charCodeAt(i) & 0xff
    return out
  }
  return null
}

/** Office 面：getFileAsync(Compressed) 分片取全量。不支持/失败返回 null，绝不抛。 */
async function captureOfficeBytes() {
  let OfficeGlobal
  try {
    OfficeGlobal = typeof Office !== 'undefined' ? Office : null
  } catch {
    OfficeGlobal = null
  }
  const doc = OfficeGlobal && OfficeGlobal.context && OfficeGlobal.context.document
  if (!doc || typeof doc.getFileAsync !== 'function') return null

  const file = await new Promise((resolve) => {
    try {
      doc.getFileAsync(OfficeGlobal.FileType.Compressed, { sliceSize: 4194304 }, (r) => {
        resolve(r && r.status === OfficeGlobal.AsyncResultStatus.Succeeded ? r.value : null)
      })
    } catch {
      resolve(null)
    }
  })
  if (!file) return null

  try {
    if (file.size > SNAPSHOT_MAX_BYTES) return null
    const chunks = []
    for (let i = 0; i < file.sliceCount; i++) {
      const data = await new Promise((resolve) => {
        try {
          file.getSliceAsync(i, (r) => {
            resolve(r && r.status === OfficeGlobal.AsyncResultStatus.Succeeded ? r.value.data : null)
          })
        } catch {
          resolve(null)
        }
      })
      const u8 = toUint8(data)
      if (!u8) return null
      chunks.push(u8)
    }
    const total = chunks.reduce((n, c) => n + c.length, 0)
    const out = new Uint8Array(total)
    let off = 0
    for (const c of chunks) { out.set(c, off); off += c.length }
    return out
  } finally {
    // 文档明令：内存里最多两个 File 对象，不 close 后续 getFileAsync 会失败
    try { file.closeAsync(() => {}) } catch { /* ignore */ }
  }
}

/**
 * WPS 面：FullName 拿磁盘路径 → FileSystem.readAsBinaryString 读字节（上次保存态）。
 * 整链未经真机确证：任一环缺失/抛异常/产物不过 ZIP 校验 → null（诚实降级，不硬凑）。
 */
function captureWpsBytes() {
  try {
    const w = typeof window !== 'undefined' ? window : {}
    const app = w.Application || (w.wps && w.wps.Application) || null
    if (!app) return null
    const host = detectHost()
    const doc = host === 'word' ? app.ActiveDocument
      : host === 'excel' ? app.ActiveWorkbook : app.ActivePresentation
    const fullName = doc && doc.FullName
    // 没保存过的新文档 FullName 只有裸名字（无路径分隔符）——磁盘上还没有文件可读
    if (!fullName || (!String(fullName).includes('/') && !String(fullName).includes('\\'))) return null
    const fs = app.FileSystem || (w.wps && w.wps.FileSystem) || null
    if (!fs || typeof fs.readAsBinaryString !== 'function') return null
    const raw = fs.readAsBinaryString(String(fullName))
    if (typeof raw !== 'string' || !raw) return null
    if (raw.length > SNAPSHOT_MAX_BYTES) return null
    const bytes = toUint8(raw)
    if (!looksLikeZip(bytes)) return null
    const base = String(fullName).replace(/\\/g, '/').split('/').pop()
    return { bytes, fileName: base }
  } catch {
    return null
  }
}

/**
 * 采集当前文档原始字节。成功 {bytes: Uint8Array, fileName}；该环境拿不到则 null。
 */
export async function captureDocumentBytes() {
  if (hostFamily() === 'wps') {
    return captureWpsBytes()
  }
  const bytes = await captureOfficeBytes()
  if (!bytes || !looksLikeZip(bytes)) return null
  return { bytes, fileName: snapshotFileName('') }
}

/** SHA-256 十六进制（内容去重闸）。非 secure context 下 crypto.subtle 缺失时返回空串。 */
export async function sha256Hex(bytes) {
  try {
    const digest = await crypto.subtle.digest('SHA-256', bytes)
    return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('')
  } catch {
    return ''
  }
}
