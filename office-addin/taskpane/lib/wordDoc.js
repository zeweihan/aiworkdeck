/**
 * Office.js 文档访问：读取当前 Word 文档正文，作为 activeContext 内联正文随对话请求上送。
 * 后端上限 200k 字符，客户端先行截断少传流量。
 */
const MAX_BODY_CHARS = 200_000

export function officeAvailable() {
  return typeof Office !== 'undefined' && typeof Word !== 'undefined'
}

export async function readActiveDocument() {
  if (!officeAvailable()) return null
  try {
    const text = await Word.run(async (context) => {
      const body = context.document.body
      body.load('text')
      await context.sync()
      return body.text || ''
    })
    let name = '当前 Word 文档'
    try {
      const url = Office.context && Office.context.document && Office.context.document.url
      if (url) {
        const seg = String(url).split(/[\\/]/).pop()
        if (seg) name = seg
      }
    } catch (e) { /* 文档未保存时可能拿不到 url，用通称即可 */ }
    return {
      // 该文档在后端没有 fileId，用固定合成 id 满足 activeContext 契约；
      // 正文经 inlineContent 内联上送，后端不会拿这个 id 去读库
      id: 'office-current-document',
      name,
      fileType: 'docx',
      inlineContent: text.length > MAX_BODY_CHARS ? text.slice(0, MAX_BODY_CHARS) : text
    }
  } catch (e) {
    console.warn('[Addin] 读取文档正文失败', e)
    return null
  }
}
