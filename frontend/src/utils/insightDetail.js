// insightDetail.js — 把「依据」窗格拿到的检索详情整形成可渲染的行（dev-board#182）。
//
// 三种 detail 的形状（后端 DocInsightService 落库时定的，见 .claude/agents/doc-insight.md）：
//   COMPANY  {source, basic:{企业名称:…, 统一社会信用代码:…, …}, shareholders:[{股东,持股比例,认缴出资}], raw?}
//   LAW/CASE {source, tool, query, result:<上游 JSON 或原文字符串>}
//
// **上游形状是别人家的**（法宝/企查查随时可能改字段名、外面还可能裹一层 MCP content 信封），
// 所以这里一律「有什么渲染什么」：认得的字段按顺序列出来，认不得的整体落到原文兜底，
// 绝不因为少一个键就把整块内容吞掉。
//
// 纯函数、不 import Vue/uni/i18n——测试 tests/insight/insightDetail.test.mjs 直接导入。

/** 从若干别名里取第一个非空值。 */
function pick(obj, keys) {
  if (!obj || typeof obj !== 'object') return ''
  for (const k of keys) {
    const v = obj[k]
    if (v == null) continue
    if (typeof v === 'string' && v.trim() === '') continue
    if (typeof v === 'object') continue
    return String(v)
  }
  return ''
}

function tryParse(text) {
  try {
    return JSON.parse(text)
  } catch (e) {
    return null
  }
}

/**
 * 剥掉 detail.result 外面可能裹着的层：字符串化的 JSON、MCP 的 {content:[{type:'text',text}]} 信封。
 * 剥不动就原样返回（字符串就是字符串，窗格按原文显示）。
 */
export function unwrapResult(detail) {
  let r = detail ? detail.result : null
  if (r == null) return null
  if (typeof r === 'string') {
    const parsed = tryParse(r)
    return parsed == null ? r : unwrapEnvelope(parsed)
  }
  return unwrapEnvelope(r)
}

function unwrapEnvelope(r) {
  if (!r || typeof r !== 'object') return r
  if (Array.isArray(r.content)) {
    const text = r.content
      .map((c) => (c && typeof c.text === 'string' ? c.text : ''))
      .filter(Boolean)
      .join('\n')
    if (!text) return r
    const parsed = tryParse(text)
    return parsed == null ? text : parsed
  }
  return r
}

/** 上游把结果放在数组里的常见键名。 */
const LIST_KEYS = ['data', 'list', 'items', 'results', 'records', 'rows', 'cases', 'articles']

/**
 * 把「结果」整形成 {records:[...], text:''}：
 * 数组直接是清单；对象里挂着数组的取那个数组；纯对象自成一条；字符串落 text。
 */
export function resultRecords(payload) {
  if (payload == null) return { records: [], text: '' }
  if (typeof payload === 'string') return { records: [], text: payload }
  if (Array.isArray(payload)) return { records: payload.filter((x) => x && typeof x === 'object'), text: '' }
  if (typeof payload !== 'object') return { records: [], text: String(payload) }
  for (const k of LIST_KEYS) {
    const v = payload[k]
    if (Array.isArray(v) && v.length) return { records: v.filter((x) => x && typeof x === 'object'), text: '' }
  }
  return { records: [payload], text: '' }
}

// —————————————————————————— COMPANY ——————————————————————————

/** 工商基本情况表：后端已按固定顺序写好 basic，这里保持它的键序原样列出。 */
export function companyRows(detail) {
  const basic = detail && detail.basic
  if (!basic || typeof basic !== 'object') return []
  const out = []
  for (const k of Object.keys(basic)) {
    const v = basic[k]
    if (v == null || v === '') continue
    out.push({ label: k, value: String(v) })
  }
  return out
}

/** 股东出资（上游没有这一段时返回空数组，整块不渲染）。 */
export function companyShareholders(detail) {
  const arr = detail && detail.shareholders
  if (!Array.isArray(arr)) return []
  return arr
    .filter((s) => s && typeof s === 'object')
    .map((s) => ({
      name: pick(s, ['股东', 'StockName', 'name']),
      percent: pick(s, ['持股比例', 'StockPercent', 'percent']),
      capital: pick(s, ['认缴出资', 'ShouldCapi', 'capital']),
    }))
    .filter((s) => s.name || s.percent || s.capital)
}

// —————————————————————————— LAW ——————————————————————————

const LAW_TITLE = ['title', 'law_name', 'lawName', 'name', '法规名称', '标题', '名称']
const LAW_ARTICLE = ['article', 'articleNo', 'number', 'no', '条号', '条文序号']
const LAW_TIMELINESS = ['timeliness', 'validity', 'status', 'effectiveness', '时效性', '效力级别']
const LAW_CONTENT = ['content', 'text', 'articleContent', 'body', 'fullText', '条文内容', '正文', '内容']

/**
 * 法条原文。返回 {title, article, timeliness, content, more:[…]}——
 * more 是同名法规的其余候选（关键词库 get_law_list 会返回一串）。
 */
export function lawArticle(detail) {
  const { records, text } = resultRecords(unwrapResult(detail))
  if (!records.length) return { title: '', article: '', timeliness: '', content: text, more: [] }
  const first = records[0]
  return {
    title: pick(first, LAW_TITLE),
    article: pick(first, LAW_ARTICLE),
    timeliness: pick(first, LAW_TIMELINESS),
    content: pick(first, LAW_CONTENT),
    more: records.slice(1).map((r) => pick(r, LAW_TITLE)).filter(Boolean),
  }
}

// —————————————————————————— CASE ——————————————————————————

const CASE_TITLE = ['title', 'case_name', 'caseName', 'name', '案件名称', '标题']
const CASE_NUMBER = ['case_number', 'caseNumber', 'caseNo', 'number', '案号']
const CASE_COURT = ['courthouse_name', 'courthouseName', 'court', 'court_name', 'courtName', '法院', '审理法院']
const CASE_DATE = ['decision_date', 'decisionDate', 'judge_date', 'judgeDate', 'date', '裁判日期', '判决日期']
const CASE_TYPE = ['case_type', 'caseType', 'trial_procedure', '案件类型', '审理程序']

/** 判决书正文的分段：认得的段落各成一块，键名即小标题。 */
const CASE_SECTIONS = [
  { key: 'ascertain', aliases: ['ascertain', 'fact', 'facts', '查明事实', '事实'] },
  { key: 'reason', aliases: ['reason', 'reasoning', 'judgeReason', '裁判理由', '本院认为'] },
  { key: 'result', aliases: ['result', 'judgment', 'judgeResult', '裁判结果', '判决结果'] },
  { key: 'gist', aliases: ['gist', 'summary', 'abstract', '裁判要旨', '摘要'] },
  { key: 'fullText', aliases: ['full_text', 'fullText', 'content', 'text', '正文', '全文'] },
]

/**
 * 判决书。返回 {title, caseNumber, court, date, caseType, sections:[{key,text}], more:[标题…]}。
 * 法宝 search_case 返回一串候选：首条展开，其余只列标题（点开是另一次检索的事，本期不做）。
 */
export function caseRecord(detail) {
  const { records, text } = resultRecords(unwrapResult(detail))
  if (!records.length) {
    return { title: '', caseNumber: '', court: '', date: '', caseType: '', sections: text ? [{ key: 'fullText', text }] : [], more: [] }
  }
  const first = records[0]
  const sections = []
  const used = new Set()
  for (const s of CASE_SECTIONS) {
    const v = pick(first, s.aliases)
    if (!v) continue
    if (used.has(v)) continue // 全文段与某一段内容相同时不重复渲染
    used.add(v)
    sections.push({ key: s.key, text: v })
  }
  return {
    title: pick(first, CASE_TITLE),
    caseNumber: pick(first, CASE_NUMBER),
    court: pick(first, CASE_COURT),
    date: pick(first, CASE_DATE),
    caseType: pick(first, CASE_TYPE),
    sections,
    more: records.slice(1).map((r) => pick(r, CASE_TITLE) || pick(r, CASE_NUMBER)).filter(Boolean),
  }
}

// —————————————————————————— 法宝升级件 ——————————————————————————
//
// 两个字段是后端在检索结果之外补上去的（dev-board#181 升级件）：
//   LAW.authoritative   引用校验（adjust_provisions）回填的权威条文原文；
//   CASE.recognition    案号识别（anhao_recognition）的标准化案号 / 法院 / 判决书标题 / 法宝链接。
// 与 result 并列，不受上游检索成败影响——检索没命中时它们可能是这一条<b>仅有</b>的内容。

const AUTH_TITLE = ['title', 'law_name', 'lawName', '法规名称', '标题']
const AUTH_TEXT = ['original_text', 'originalText', 'content', 'text', '条文内容', '正文']
const AUTH_URL = ['url', 'link', '链接']
const AUTH_DATE = ['implement_date', 'implementDate', 'effective_date', '施行日期', '实施日期']

/** 权威条文原文段。没有这个字段（或一个认得的键都没有）返回 null，整段不渲染。 */
export function authoritative(detail) {
  const a = detail && detail.authoritative
  if (!a || typeof a !== 'object') return null
  const out = {
    title: pick(a, AUTH_TITLE),
    text: pick(a, AUTH_TEXT),
    url: pick(a, AUTH_URL),
    date: pick(a, AUTH_DATE),
  }
  return out.title || out.text || out.url || out.date ? out : null
}

const REC_NUMBER = ['caseFlag', 'case_flag', 'caseNumber', 'case_number', '案号', 'text']
const REC_COURT = ['court', 'courthouse_name', 'courtName', '法院', '审理法院']
const REC_TITLE = ['title', 'case_name', 'caseName', '案件名称', '标题']
const REC_URL = ['url', 'link', '链接']

/** 案号识别行。同上：认不出任何一个键就返回 null。 */
export function caseRecognition(detail) {
  const r = detail && detail.recognition
  if (!r || typeof r !== 'object') return null
  const out = {
    caseNumber: pick(r, REC_NUMBER),
    court: pick(r, REC_COURT),
    title: pick(r, REC_TITLE),
    url: pick(r, REC_URL),
  }
  return out.caseNumber || out.court || out.title || out.url ? out : null
}

/**
 * 两类引用发现（CITATION_NOT_FOUND / CITATION_MISMATCH）的 detail 整形。
 * 不是这两类返回 null（USCC/数量矛盾各有各的渲染路径）。
 *
 * <b>永远不带修改建议</b>：候选条号可能来自旧版法规（条文会重编号），机械改写必然出错，
 * 所以后端 fixable 恒 false、不下发 numberText，前端这边也只列不改。
 */
export function citationDetail(finding) {
  const kind = finding && finding.kind
  if (kind !== 'CITATION_NOT_FOUND' && kind !== 'CITATION_MISMATCH') return null
  const d = finding.detail
  if (!d || typeof d !== 'object') return null
  const candidates = Array.isArray(d.candidates) ? d.candidates : []
  return {
    kind,
    lawTitle: d.lawTitle == null ? '' : String(d.lawTitle),
    citedArticle: d.citedArticle == null ? '' : String(d.citedArticle),
    citedText: d.citedText == null ? '' : String(d.citedText),
    quote: d.quote == null ? '' : String(d.quote),
    note: d.note == null ? '' : String(d.note),
    candidates: candidates
      .filter((c) => c && typeof c === 'object')
      .map((c) => ({
        title: pick(c, ['title', '法规名称', '标题']),
        articleNumber: pick(c, ['articleNumber', 'article_number', '条号']),
        snippet: pick(c, ['snippet', 'original_text', 'content', '条文内容']),
        url: pick(c, AUTH_URL),
      })),
  }
}

// —————————————————————————— 兜底 ——————————————————————————

/**
 * 什么都认不出来时给窗格的原文（company 的 raw / 结果里的裸字符串 / 整个 JSON）。
 * 有长度上限——几百 KB 的工商全文塞进 <text> 会把面板卡住。
 */
export function rawFallback(detail, limit = 4000) {
  if (!detail) return ''
  if (typeof detail.raw === 'string' && detail.raw) return detail.raw.slice(0, limit)
  const payload = unwrapResult(detail)
  if (payload == null) return ''
  if (typeof payload === 'string') return payload.slice(0, limit)
  try {
    return JSON.stringify(payload, null, 2).slice(0, limit)
  } catch (e) {
    return ''
  }
}
