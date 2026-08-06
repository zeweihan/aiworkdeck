/**
 * office_command 执行器（Phase C 工具桥）：
 * 后端 OfficeBridgeService 经 SSE client_action 下发 {tool:'office_command',
 * requestId, command, args, conversationId}，本模块按 command 分发到 Office.js
 * 实现并返回 {ok, data|error}，由调用方 POST /api/agent/office/result 回传。
 *
 * 硬规则：后端注册的每个 office_* 工具都必须在这里有对应实现——
 * 没有客户端实现的远端工具 = 30 秒超时空转（PptxEditTools 死路径教训）。
 * 未知 command 立即回 {ok:false, error:'unsupported command'}，绝不静默吞掉。
 *
 * 修改类命令（replace_text / insert_text）执行前把 document.changeTrackingMode
 * 设为 TrackAll（Word 原生修订），执行后恢复原值；宿主不支持 WordApi 1.4 时
 * 降级为直接修改并在结果里标注 tracked:false。
 */

import { officeAvailable } from './wordDoc.js'

// 与后端 ContextAssemblerService.MAX_INLINE_CONTENT_CHARS 一致的截断上限
const MAX_TEXT_CHARS = 200_000
// search 命中上下文的最大条数（防超长工具输出撑爆模型上下文）
const MAX_SEARCH_HITS = 20

function trackingSupported() {
  try {
    return Office.context.requirements.isSetSupported('WordApi', '1.4')
  } catch (e) {
    return false
  }
}

function truncate(text) {
  const s = text || ''
  return s.length > MAX_TEXT_CHARS
    ? { text: s.slice(0, MAX_TEXT_CHARS), truncated: true, totalChars: s.length }
    : { text: s, truncated: false, totalChars: s.length }
}

/**
 * 在开启 Word 原生修订（TrackAll）的前提下执行 fn，结束后恢复原模式。
 * 返回 fn 的结果并附 tracked 标记。
 */
async function withTracking(context, fn) {
  if (!trackingSupported()) {
    const data = await fn()
    return { ...data, tracked: false }
  }
  const doc = context.document
  doc.load('changeTrackingMode')
  await context.sync()
  const previousMode = doc.changeTrackingMode
  doc.changeTrackingMode = Word.ChangeTrackingMode.trackAll
  await context.sync()
  try {
    const data = await fn()
    return { ...data, tracked: true }
  } finally {
    doc.changeTrackingMode = previousMode
    await context.sync()
  }
}

/** body.search 定位锚点，返回命中 Range 数组（未命中返回空数组） */
async function searchRanges(context, needle, matchCase) {
  const results = context.document.body.search(needle, { matchCase: !!matchCase })
  results.load('items')
  await context.sync()
  return results.items
}

const HANDLERS = {
  async get_text() {
    return Word.run(async (context) => {
      const body = context.document.body
      body.load('text')
      await context.sync()
      return truncate(body.text)
    })
  },

  async get_selection() {
    return Word.run(async (context) => {
      const selection = context.document.getSelection()
      selection.load('text')
      await context.sync()
      return truncate(selection.text)
    })
  },

  async search(args) {
    const query = String(args.query || '')
    if (!query) throw new Error('查找文本不能为空')
    return Word.run(async (context) => {
      const items = await searchRanges(context, query, false)
      const hits = items.slice(0, MAX_SEARCH_HITS)
      // 命中上下文 = 命中所在段落的完整文本
      const paragraphs = hits.map((range) => {
        const p = range.paragraphs.getFirst()
        p.load('text')
        return p
      })
      await context.sync()
      return {
        count: items.length,
        shown: hits.length,
        matches: hits.map((range, i) => ({
          index: i + 1,
          context: (paragraphs[i].text || '').slice(0, 500)
        }))
      }
    })
  },

  async replace_text(args) {
    const searchText = String(args.searchText || '')
    const replaceText = args.replaceText == null ? '' : String(args.replaceText)
    const replaceAll = !!args.replaceAll
    if (!searchText) throw new Error('查找文本不能为空')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const items = await searchRanges(context, searchText, true)
        if (!items.length) {
          throw new Error('未找到目标文本，请确认 searchText 与文档内容精确一致（可先用 search 命令核对）')
        }
        const targets = replaceAll ? items : [items[0]]
        for (const range of targets) {
          range.insertText(replaceText, Word.InsertLocation.replace)
        }
        await context.sync()
        return { replaced: targets.length, totalMatches: items.length }
      })
    })
  },

  async insert_text(args) {
    const text = String(args.text || '')
    const anchorText = String(args.anchorText || '')
    const position = args.position === 'before' ? 'before' : 'after'
    if (!text) throw new Error('插入文本不能为空')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        if (anchorText) {
          const items = await searchRanges(context, anchorText, true)
          if (!items.length) {
            throw new Error('未找到锚点文本，请确认 anchorText 与文档内容精确一致')
          }
          const location = position === 'before' ? Word.InsertLocation.before : Word.InsertLocation.after
          items[0].insertText(text, location)
        } else {
          // 无锚点：落在用户当前光标/选区处（选区被替换，与光标插入语义一致）
          context.document.getSelection().insertText(text, Word.InsertLocation.replace)
        }
        await context.sync()
        return { inserted: true, anchored: !!anchorText, position: anchorText ? position : 'selection' }
      })
    })
  },

  async add_comment(args) {
    const anchorText = String(args.anchorText || '')
    const comment = String(args.comment || '')
    if (!anchorText) throw new Error('批注目标文本不能为空')
    if (!comment) throw new Error('批注内容不能为空')
    if (!trackingSupported()) {
      // insertComment 同属 WordApi 1.4
      throw new Error('当前 Word 版本不支持插入批注（需要 WordApi 1.4）')
    }
    return Word.run(async (context) => {
      const items = await searchRanges(context, anchorText, true)
      if (!items.length) {
        throw new Error('未找到批注目标文本，请确认 anchorText 与文档内容精确一致')
      }
      items[0].insertComment(comment)
      await context.sync()
      return { commented: true }
    })
  }
}

/** 每个 command 的固定中文名（对话流中的工具活动 chip；与后端 @ToolMeta displayName 对齐） */
export const COMMAND_DISPLAY_NAMES = {
  get_text: '读取文档',
  get_selection: '读取选区',
  search: '查找文本',
  replace_text: '替换文本（修订）',
  insert_text: '插入文本（修订）',
  add_comment: '插入批注'
}

export function commandDisplayName(command) {
  return COMMAND_DISPLAY_NAMES[command] || `文档操作（${command}）`
}

/**
 * 执行一条 office_command。永不 throw：一律返回 {ok:true, data} 或 {ok:false, error}。
 */
export async function executeOfficeCommand(command, args) {
  if (!officeAvailable()) {
    return { ok: false, error: 'Word 环境不可用：请在 Word 任务窗格中使用本插件' }
  }
  const handler = HANDLERS[command]
  if (!handler) {
    return { ok: false, error: `unsupported command: ${command}` }
  }
  try {
    const data = await handler(args || {})
    return { ok: true, data: data == null ? {} : data }
  } catch (e) {
    const message = (e && e.message) || String(e)
    console.warn('[Addin] office_command 执行失败', command, e)
    return { ok: false, error: message }
  }
}
