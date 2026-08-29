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

/**
 * Value2 读回的形状归一成 rows x cols 二维数组。
 * 单格是标量；**单行/单列是否降成一维，官方未成文**（wpsEtHandlers.read2D 同款防御）。
 * 不归一的话，一行数据的工作表会只读到第一格——「随消息附带表格内容」直接丢数据，
 * 而且丢得无声无息。
 */
function normalizeValues(v, rows, cols) {
  if (!Array.isArray(v)) return [[v]]
  if (!Array.isArray(v[0])) {
    if (rows === 1) return [v]
    if (cols === 1) return v.map((x) => [x])
  }
  return v
}

function readEtSheet() {
  const app = wps.EtApplication()
  const sheet = app.ActiveSheet
  if (!sheet) throw new Error('当前没有打开的工作簿')
  const used = sheet.UsedRange
  const name = String(sheet.Name || '')
  const emptyResult = { text: `工作表「${name}」为空`, name: documentDisplayName('当前 WPS 工作簿'), fileType: 'xlsx' }
  if (!used) return emptyResult
  const rowCount = used.Rows.Count
  const colCount = used.Columns.Count
  // 空表的 UsedRange 不是空引用而是 A1 单格（VBA 口径，真机实测确认）——不这样判的话
  // 空工作表会被描述成「区域 A1」外加一个空单元格，而不是老实说「为空」
  if (rowCount === 1 && colCount === 1) {
    const only = used.Value2
    if (only == null || only === '') return emptyResult
  }
  const shownRows = Math.min(rowCount, MAX_EXCEL_ROWS)
  // 跨进程桥逐格取值极慢（约 0.2ms/调用），必须 Value2 批量读。
  // **先 Resize 到要展示的行数再取值**：既然只展示前 MAX_EXCEL_ROWS 行，把整片已用
  // 区域搬过桥就是白搬——十万行的工作簿会把任务窗格拖到长时间无响应。
  const source = rowCount > shownRows ? used.Resize(shownRows, colCount) : used
  const values = normalizeValues(source.Value2, shownRows, colCount)
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

/** MsoShapeType：组合 */
const MSO_GROUP = 6

/**
 * 收一个形状里的全部文字。除了普通文本框，还要管两类——**演示稿里承载正文的
 * 恰恰常常是它们**：
 * - 表格形状（对比表、时间表、条款对照）：文字在 Table.Cell(r,c).Shape 里，
 *   父形状的 TextFrame 是空的，只看 TextFrame 会把整页读成「（无文本）」；
 * - 组合形状（图示+标注、SmartArt 转出来的组合）：文字在子形状里，要递归。
 * 任何一步失败都只跳过这一个形状，不能让整篇文稿读不出来。
 */
function collectShapeText(shape, out, depth = 0) {
  if (depth > 4) return // 组合套组合，防病态嵌套
  try {
    if (shape.HasTable) {
      const table = shape.Table
      const rows = table.Rows.Count
      const cols = table.Columns.Count
      const cells = []
      for (let r = 1; r <= rows; r++) {
        const row = []
        for (let c = 1; c <= cols; c++) {
          try {
            row.push(String(table.Cell(r, c).Shape.TextFrame.TextRange.Text || '').replace(/\r/g, ' ').trim())
          } catch (e) { row.push('') }
        }
        cells.push(row.join('\t'))
      }
      const joined = cells.join('\n').trim()
      if (joined) out.push(joined)
      return
    }
  } catch (e) { /* 没有 HasTable 属性的宿主版本，按普通形状继续 */ }
  try {
    if (shape.Type === MSO_GROUP) {
      const items = shape.GroupItems
      const n = items.Count
      for (let k = 1; k <= n; k++) collectShapeText(items.Item(k), out, depth + 1)
      return
    }
  } catch (e) { /* 取不到组合子项就按普通形状处理 */ }
  try {
    const frame = shape.TextFrame
    if (frame && frame.HasText) {
      const t = String(frame.TextRange.Text || '').replace(/\r/g, ' ').trim()
      if (t) out.push(t)
    }
  } catch (e) { /* 个别形状没有文本框架 */ }
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
    const shapes = slide.Shapes
    const shapeCount = shapes.Count
    for (let s = 1; s <= shapeCount; s++) {
      collectShapeText(shapes.Item(s), texts)
    }
    lines.push(`第${i}页：${texts.join(' | ') || '（无文本）'}`)
  }
  let out = lines.join('\n')
  if (total > MAX_PPT_SLIDES) {
    out += `\n...（共 ${total} 页，仅附前 ${MAX_PPT_SLIDES} 页）`
  }
  return { text: out, name: documentDisplayName('当前 WPS 演示文稿'), fileType: 'pptx' }
}

/**
 * 任务窗格 id 在 PluginStorage 里的键。**必须按宿主分**：三个宿主共用同一个加载项
 * 注册与一份 PluginStorage，而窗格 id 是各宿主进程内从 1 开始自增的——同一个键会让
 * 文字里存下的 id 被表格拿去开关它自己的同号窗格（可能是别家加载项的）。
 * 后缀与 ribbon 壳的 `AwdHostTag()` 严格同源——两边改一个就得改另一个。
 */
const WPS_HOST_TAG = { word: 'wps', excel: 'et', powerpoint: 'wpp' }
function taskPaneKey() {
  return 'awd_taskpane_id_' + (WPS_HOST_TAG[detectWpsHost()] || 'unknown')
}

/**
 * 从任务窗格内部把自己收起（Visible=false）。
 * 为什么需要：WPS 平台 bug（bbs 93291，12.1.0.26895 起）——JS 停靠任务窗格打开
 * 期间整条 ribbon 拒收鼠标事件，而关窗格的按钮恰恰在 ribbon 上，用户会被锁死。
 * 窗格页自己有 window.wps，从内部藏掉窗格即可解锁 ribbon；重开走 ribbon 的
 * 「AI 助手」按钮（toggle）。窗格 id 从 ribbon 壳写进 PluginStorage 的同一个
 * 按宿主键里取（wps/js/ribbon.js 的 `AwdPaneKey()`）。
 */
export function hideWpsTaskPane() {
  try {
    const id = wps.PluginStorage.getItem(taskPaneKey())
    if (!id) return false
    const pane = wps.GetTaskPane(id)
    if (pane) {
      pane.Visible = false
      return true
    }
  } catch (e) {
    console.warn('[Addin] 收起 WPS 任务窗格失败', e)
  }
  return false
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
