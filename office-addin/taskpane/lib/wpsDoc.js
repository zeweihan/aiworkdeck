/**
 * WPS 加载项 JSAPI 文档访问（与 wordDoc.js 同职责的 WPS 家族实现）：
 * 宿主检测 + 读取当前文档内容，作为 activeContext 内联正文随对话请求上送。
 *
 * 入口对象：WPS 任务窗格 webview 注入的 window.wps——文字宿主 wps.WpsApplication()、
 * 表格宿主 wps.EtApplication()、演示宿主 wps.WppApplication()，对象模型为 VBA 同构
 * 的同步 API。宿主归一到与 Office 家族相同的三值（word/excel/powerpoint），
 * 后端 officeHost 契约不感知家族差异。
 */

// 与 wordDoc.js 一致的上限口径
const MAX_BODY_CHARS = 200_000
const MAX_EXCEL_ROWS = 2000
const MAX_PPT_SLIDES = 100

export function wpsAvailable() {
  return typeof wps !== 'undefined' && wps != null
}

/**
 * 当前 WPS 宿主：'word' | 'excel' | 'powerpoint' | ''。
 * 三个 Application 入口只有当前宿主的可用，其余的不存在或调用即抛——
 * 逐个 try 是官方示例的标准姿势。
 */
export function detectWpsHost() {
  if (!wpsAvailable()) return ''
  try {
    if (typeof wps.WpsApplication === 'function' && wps.WpsApplication()) return 'word'
  } catch (e) { /* 非文字宿主 */ }
  try {
    if (typeof wps.EtApplication === 'function' && wps.EtApplication()) return 'excel'
  } catch (e) { /* 非表格宿主 */ }
  try {
    if (typeof wps.WppApplication === 'function' && wps.WppApplication()) return 'powerpoint'
  } catch (e) { /* 非演示宿主 */ }
  return ''
}

/** 当前宿主的 Application 对象；不可用时抛错（调用方兜 try/catch） */
export function wpsApp() {
  const host = detectWpsHost()
  if (host === 'word') return wps.WpsApplication()
  if (host === 'excel') return wps.EtApplication()
  if (host === 'powerpoint') return wps.WppApplication()
  throw new Error('WPS 环境不可用')
}

function documentDisplayName(fallback) {
  try {
    const host = detectWpsHost()
    const app = wpsApp()
    const doc = host === 'word' ? app.ActiveDocument
      : host === 'excel' ? app.ActiveWorkbook : app.ActivePresentation
    if (doc && doc.Name) return String(doc.Name)
  } catch (e) { /* 未打开文档时用通称 */ }
  return fallback
}

function readWordBody() {
  const app = wps.WpsApplication()
  const doc = app.ActiveDocument
  if (!doc) throw new Error('当前没有打开的文档')
  const text = String(doc.Range().Text || '')
  return { text, name: documentDisplayName('当前 WPS 文档'), fileType: 'docx' }
}

/** Address 在 JSAPI 是带参属性=按函数调（$A$1 绝对引用关掉）；个别版本可能是纯属性，兜一手 */
function rangeAddress(range) {
  try {
    if (typeof range.Address === 'function') return String(range.Address(false, false) || '')
    return String(range.Address || '')
  } catch (e) {
    return ''
  }
}

function readEtSheet() {
  const app = wps.EtApplication()
  const sheet = app.ActiveSheet
  if (!sheet) throw new Error('当前没有打开的工作簿')
  const used = sheet.UsedRange
  const name = String(sheet.Name || '')
  if (!used) return { text: `工作表「${name}」为空`, name: documentDisplayName('当前 WPS 工作簿'), fileType: 'xlsx' }
  const rowCount = used.Rows.Count
  const colCount = used.Columns.Count
  const shownRows = Math.min(rowCount, MAX_EXCEL_ROWS)
  // 跨进程桥逐格取值极慢（官方性能口径约 0.2ms/调用），必须 Value2 批量读；
  // 单格时 Value2 是标量，归一成二维再统一处理
  let values = used.Value2
  if (!Array.isArray(values)) values = [[values]]
  const lines = []
  for (let r = 0; r < shownRows && r < values.length; r++) {
    const row = Array.isArray(values[r]) ? values[r] : [values[r]]
    const cells = []
    for (let c = 0; c < colCount && c < row.length; c++) {
      const v = row[c]
      cells.push(v == null ? '' : String(v))
    }
    lines.push(cells.join('\t'))
  }
  let out = `工作表「${name}」（区域 ${rangeAddress(used)}）：\n` + lines.join('\n')
  if (rowCount > MAX_EXCEL_ROWS) {
    out += `\n...（共 ${rowCount} 行，仅附前 ${MAX_EXCEL_ROWS} 行）`
  }
  return { text: out, name: documentDisplayName('当前 WPS 工作簿'), fileType: 'xlsx' }
}

function readWppSlides() {
  const app = wps.WppApplication()
  const pres = app.ActivePresentation
  if (!pres) throw new Error('当前没有打开的演示文稿')
  const total = pres.Slides.Count
  const shown = Math.min(total, MAX_PPT_SLIDES)
  const lines = []
  for (let i = 1; i <= shown; i++) {
    const slide = pres.Slides.Item(i)
    const texts = []
    const shapeCount = slide.Shapes.Count
    for (let s = 1; s <= shapeCount; s++) {
      const shape = slide.Shapes.Item(s)
      try {
        if (shape.TextFrame && shape.TextFrame.HasText) {
          const t = String(shape.TextFrame.TextRange.Text || '').trim()
          if (t) texts.push(t)
        }
      } catch (e) { /* 个别形状没有文本框架 */ }
    }
    lines.push(`第${i}页：${texts.join(' | ') || '（无文本）'}`)
  }
  let out = lines.join('\n')
  if (total > MAX_PPT_SLIDES) {
    out += `\n...（共 ${total} 页，仅附前 ${MAX_PPT_SLIDES} 页）`
  }
  return { text: out, name: documentDisplayName('当前 WPS 演示文稿'), fileType: 'pptx' }
}

/** 只取文档元信息（名字/类型），不读正文——契约同 wordDoc.readDocumentMeta */
export function readWpsDocumentMeta() {
  const host = detectWpsHost()
  if (!host) return null
  const fallback = host === 'word' ? '当前 WPS 文档'
    : host === 'excel' ? '当前 WPS 工作簿' : '当前 WPS 演示文稿'
  const fileType = host === 'word' ? 'docx' : host === 'excel' ? 'xlsx' : 'pptx'
  return { id: 'office-current-document', name: documentDisplayName(fallback), fileType }
}

export async function readWpsActiveDocument() {
  if (!wpsAvailable()) return null
  try {
    const host = detectWpsHost()
    let doc
    if (host === 'word') doc = readWordBody()
    else if (host === 'excel') doc = readEtSheet()
    else if (host === 'powerpoint') doc = readWppSlides()
    else return null
    const text = doc.text || ''
    return {
      id: 'office-current-document',
      name: doc.name,
      fileType: doc.fileType,
      inlineContent: text.length > MAX_BODY_CHARS ? text.slice(0, MAX_BODY_CHARS) : text
    }
  } catch (e) {
    console.warn('[Addin] 读取 WPS 文档内容失败', e)
    return null
  }
}
