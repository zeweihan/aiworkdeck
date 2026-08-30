/**
 * Office.js 文档访问：宿主检测 + 读取当前文档内容，作为 activeContext 内联正文
 * 随对话请求上送。后端上限 200k 字符，客户端先行截断少传流量。
 *
 * 宿主支持：Word（正文纯文本）/ Excel（活动工作表已用区域，TSV 文本）/
 * PowerPoint（各页形状文本清单，需 PowerPointApi 1.4）。
 */
const MAX_BODY_CHARS = 200_000
// Excel 内容读取的单元格上限（超大表只取前若干行，避免卡死任务窗格）
const MAX_EXCEL_ROWS = 2000
// PPT 内容读取的页数上限
const MAX_PPT_SLIDES = 100
/** 内联正文的装饰说明（两个 PPT 宿主面同一份文案） */
const PPT_INLINE_NOTE = '（以下由插件读取当前演示文稿生成。行首的「第N页：」与形状之间的「 | 」是插件加的分隔标记，不是文稿里的字；查找/替换/锚点请只用分隔标记之间的正文，不要把标记本身抄进去。）\n'

export function officeAvailable() {
  return typeof Office !== 'undefined' && typeof Office.context !== 'undefined'
}

/**
 * 当前宿主：'word' | 'excel' | 'powerpoint' | ''（未知/非 Office 环境）。
 * 随 chat 请求以 officeHost 字段上送，后端据此细分 office_* 工具可见性。
 */
export function detectHost() {
  try {
    const host = Office.context.host
    if (host === Office.HostType.Word) return 'word'
    if (host === Office.HostType.Excel) return 'excel'
    if (host === Office.HostType.PowerPoint) return 'powerpoint'
  } catch (e) { /* office.js 未初始化 */ }
  // 兜底：按全局对象判断（个别宿主 Office.context.host 取不到）
  if (typeof Word !== 'undefined') return 'word'
  if (typeof Excel !== 'undefined') return 'excel'
  if (typeof PowerPoint !== 'undefined') return 'powerpoint'
  return ''
}

function documentDisplayName(fallback) {
  let name = fallback
  try {
    const url = Office.context && Office.context.document && Office.context.document.url
    if (url) {
      const seg = String(url).split(/[\\/]/).pop()
      if (seg) name = seg
    }
  } catch (e) { /* 文档未保存时可能拿不到 url，用通称即可 */ }
  return name
}

async function readWordBody() {
  const text = await Word.run(async (context) => {
    const body = context.document.body
    body.load('text')
    await context.sync()
    return body.text || ''
  })
  return { text, name: documentDisplayName('当前 Word 文档'), fileType: 'docx' }
}

async function readExcelSheet() {
  const text = await Excel.run(async (context) => {
    const sheet = context.workbook.worksheets.getActiveWorksheet()
    sheet.load('name')
    const used = sheet.getUsedRangeOrNullObject(true)
    used.load('values,address,isNullObject')
    await context.sync()
    if (used.isNullObject) return `工作表「${sheet.name}」为空`
    const rows = used.values.slice(0, MAX_EXCEL_ROWS)
    const lines = rows.map((row) => row.map((v) => (v == null ? '' : String(v))).join('\t'))
    let out = `工作表「${sheet.name}」（区域 ${used.address}）：\n` + lines.join('\n')
    if (used.values.length > MAX_EXCEL_ROWS) {
      out += `\n...（共 ${used.values.length} 行，仅附前 ${MAX_EXCEL_ROWS} 行）`
    }
    return out
  })
  return { text, name: documentDisplayName('当前 Excel 工作簿'), fileType: 'xlsx' }
}

async function readPptSlides() {
  const supported = (() => {
    try { return Office.context.requirements.isSetSupported('PowerPointApi', '1.4') } catch (e) { return false }
  })()
  if (!supported) {
    // 旧版宿主读不到形状文本：不附正文，让调用方按「读不到」处理
    throw new Error('当前 PowerPoint 版本不支持读取幻灯片文本（需要 PowerPointApi 1.4）')
  }
  const text = await PowerPoint.run(async (context) => {
    const slides = context.presentation.slides
    slides.load('items')
    await context.sync()
    const items = slides.items.slice(0, MAX_PPT_SLIDES)
    // 先把所有形状的 textFrame 排入加载队列，再一次 sync
    const perSlide = items.map((slide) => {
      slide.shapes.load('items')
      return slide
    })
    await context.sync()
    const frames = perSlide.map((slide) =>
      slide.shapes.items.map((shape) => {
        const tf = shape.getTextFrameOrNullObject()
        tf.load('hasText,isNullObject')
        tf.textRange.load('text')
        return tf
      }))
    await context.sync()
    const lines = frames.map((slideFrames, i) => {
      const texts = slideFrames
        .filter((tf) => !tf.isNullObject && tf.hasText)
        .map((tf) => (tf.textRange.text || '').trim())
        .filter(Boolean)
      return `第${i + 1}页：${texts.join(' | ') || '（无文本）'}`
    })
    // 装饰文字必须交代清楚（dev-board#286），口径与 wpsDoc.readWppSlides 同源
    let out = PPT_INLINE_NOTE + lines.join('\n')
    if (slides.items.length > MAX_PPT_SLIDES) {
      out += `\n...（共 ${slides.items.length} 页，仅附前 ${MAX_PPT_SLIDES} 页）`
    }
    return out
  })
  return { text, name: documentDisplayName('当前 PowerPoint 演示文稿'), fileType: 'pptx' }
}

/**
 * 正文内容哈希（SHA-256 十六进制小写），用于「文档没变就不重传正文」的省传判定。
 * 口径与后端 InlineContentCache.sha256Hex 一致（UTF-8 字节）。
 *
 * crypto.subtle 只在 secure context 可用——任务窗格是 https，常态都有；
 * 取不到或算失败时返回空串，调用方据此降级为恒传全文（永远不会因此丢正文）。
 */
export async function hashContent(text) {
  try {
    const subtle = globalThis.crypto && globalThis.crypto.subtle
    if (!subtle) return ''
    const bytes = new TextEncoder().encode(text || '')
    const digest = await subtle.digest('SHA-256', bytes)
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('')
  } catch (e) {
    return ''
  }
}

/**
 * 只取文档元信息（名字/类型），不读正文。给「不附带正文」场景用：
 * activeContext 仍要上送壳（id/name/fileType），否则后端 ContextAssemblerService
 * 的整段 office 工具指引都不注入，模型连「该操作当前文档」都不知道（dev-board#150）。
 */
export function readDocumentMeta() {
  const host = detectHost()
  if (!host) return null
  const fallback = host === 'word' ? '当前 Word 文档'
    : host === 'excel' ? '当前 Excel 工作簿' : '当前 PowerPoint 演示文稿'
  const fileType = host === 'word' ? 'docx' : host === 'excel' ? 'xlsx' : 'pptx'
  return { id: 'office-current-document', name: documentDisplayName(fallback), fileType }
}

export async function readActiveDocument() {
  if (!officeAvailable()) return null
  try {
    const host = detectHost()
    let doc
    if (host === 'word') doc = await readWordBody()
    else if (host === 'excel') doc = await readExcelSheet()
    else if (host === 'powerpoint') doc = await readPptSlides()
    else return null
    const text = doc.text || ''
    return {
      // 该文档在后端没有 fileId，用固定合成 id 满足 activeContext 契约；
      // 正文经 inlineContent 内联上送，后端不会拿这个 id 去读库
      id: 'office-current-document',
      name: doc.name,
      fileType: doc.fileType,
      inlineContent: text.length > MAX_BODY_CHARS ? text.slice(0, MAX_BODY_CHARS) : text
    }
  } catch (e) {
    console.warn('[Addin] 读取文档内容失败', e)
    return null
  }
}
