// 工具输出的可读化（dev-board#178）。
//
// 执行过程卡的折叠区此前直接展示工具返回的原始 JSON——对律师是一段「代码」，
// 观感差且没有信息组织。但整个折叠区不能删：它是 PR#180 口径下「律师必须能
// 点开看工具到底查到了什么」的入口（例如 find 的 12 处命中）。
// 取中：保留点开能力，点开后**绝不显示原始代码**——能解析成 JSON 的一律
// 渲染成缩进的「键：值」可读文本，解析不了的按原样当纯文本展示（那本来就
// 是给人看的话）。
//
// 刻意丢弃的东西（这正是「太底层」的来源）：null/空串/空数组/空对象字段、
// 括号引号逗号这些语法噪音。超长字符串与超长清单截断——模型看到的是全文，
// 这里只是给人扫一眼的视图。

const MAX_STRING = 200
const MAX_LINES = 200
const MAX_ARRAY_ITEMS = 30
const MAX_DEPTH = 6

function isEmptyValue(v) {
  if (v === null || v === undefined) return true
  if (typeof v === 'string' && v.trim() === '') return true
  if (Array.isArray(v) && v.length === 0) return true
  if (typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === 0) return true
  return false
}

function scalarText(v) {
  if (typeof v === 'string') {
    const s = v.trim()
    return s.length > MAX_STRING ? s.slice(0, MAX_STRING) + '…' : s
  }
  return String(v)
}

function renderInto(lines, value, indent, depth) {
  if (lines.length >= MAX_LINES) return
  const pad = '  '.repeat(indent)
  if (Array.isArray(value)) {
    const shown = value.filter((v) => !isEmptyValue(v)).slice(0, MAX_ARRAY_ITEMS)
    for (const v of shown) {
      if (lines.length >= MAX_LINES) return
      if (typeof v === 'object' && v !== null) {
        lines.push(pad + '-')
        if (depth < MAX_DEPTH) renderInto(lines, v, indent + 1, depth + 1)
      } else {
        lines.push(pad + '- ' + scalarText(v))
      }
    }
    const hidden = value.length - shown.length
    if (hidden > 0 && lines.length < MAX_LINES) lines.push(pad + `…（另 ${hidden} 条略）`)
    return
  }
  for (const [k, v] of Object.entries(value)) {
    if (lines.length >= MAX_LINES) return
    if (isEmptyValue(v)) continue
    if (typeof v === 'object' && v !== null) {
      lines.push(pad + k + '：')
      if (depth < MAX_DEPTH) renderInto(lines, v, indent + 1, depth + 1)
    } else {
      lines.push(pad + k + '：' + scalarText(v))
    }
  }
}

/**
 * 把工具输出转成给人看的文本。
 * @param {string} raw 工具输出原文
 * @returns {string|null} 可读文本；解析不出 JSON 时返回 null（调用方按纯文本展示原文）
 */
export function humanizeToolOutput(raw) {
  const text = String(raw || '').trim()
  if (!text) return null
  const first = text.charAt(0)
  if (first !== '{' && first !== '[') return null
  // SSE 截断标记拼在 JSON 之后会让整段 parse 失败——剥掉后缀再试一次，
  // 剥完仍失败（截断落在 JSON 中间）就退回纯文本，不猜。
  let body = text
  for (const suffix of ['...(截断)', '...(truncated)']) {
    if (body.endsWith(suffix)) body = body.slice(0, -suffix.length).trim()
  }
  let parsed
  try { parsed = JSON.parse(body) } catch (e) { return null }
  if (parsed === null || typeof parsed !== 'object') return null
  const lines = []
  renderInto(lines, parsed, 0, 0)
  if (lines.length >= MAX_LINES) lines.push('…（其余略）')
  return lines.length ? lines.join('\n') : null
}
