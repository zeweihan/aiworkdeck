// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 自动字幕的纯逻辑（规格 3.5）。输入是会议记录 transcriptJson 解析出的段
// [{ speaker, start, end, text }]（毫秒，后端 MeetingTranscriptParser 落库形状）。

/** 容错解析：非法 JSON / 非数组 → []；数组里的非对象元素剔掉。 */
export function parseSegments(transcriptJson) {
  let arr = transcriptJson
  if (typeof transcriptJson === 'string') {
    if (!transcriptJson) return []
    try {
      arr = JSON.parse(transcriptJson)
    } catch (e) {
      return []
    }
  }
  if (!Array.isArray(arr)) return []
  return arr.filter(s => s && typeof s === 'object' && !Array.isArray(s))
}

function isWide(cp) {
  return (cp >= 0x2E80 && cp <= 0x9FFF) || // CJK 部首、标点、假名、统一汉字
    (cp >= 0xAC00 && cp <= 0xD7AF) ||     // 韩文
    (cp >= 0xF900 && cp <= 0xFAFF) ||     // 兼容汉字
    (cp >= 0xFE30 && cp <= 0xFE4F) ||     // 兼容形式
    (cp >= 0xFF00 && cp <= 0xFFEF) ||     // 全角
    cp >= 0x20000                         // 扩展区
}

/** CJK 计 1，其余 0.5。 */
export function textWidth(str) {
  if (!str) return 0
  let w = 0
  for (const ch of String(str)) w += isWide(ch.codePointAt(0)) ? 1 : 0.5
  return w
}

function parseNames(speakerNamesJson) {
  if (!speakerNamesJson) return {}
  if (typeof speakerNamesJson === 'object') return speakerNamesJson
  try {
    const o = JSON.parse(speakerNamesJson)
    return o && typeof o === 'object' ? o : {}
  } catch (e) {
    return {}
  }
}

function hasSpeaker(sp) {
  return sp !== undefined && sp !== null && sp !== ''
}

/**
 * 说话人显示名：speakerNames 里有非空名字用名字，否则按原始说话人 id 命名
 * （zh「说话人{id}」与会议面板 meeting.speakerDefaultName 同形；locale 以 en 开头用 Speaker {id}）。
 * 与会议面板完全同口径，同一说话人在两处叫同一个名字。count 为去重后的说话人数。
 * @returns {{ labels: Object<string,string>, count: number }}
 */
export function buildSpeakerLabels(segments, speakerNamesJson, locale) {
  const names = parseNames(speakerNamesJson)
  const en = typeof locale === 'string' && locale.toLowerCase().startsWith('en')
  const labels = {}
  let count = 0
  for (const seg of Array.isArray(segments) ? segments : []) {
    if (!seg || !hasSpeaker(seg.speaker)) continue
    const id = String(seg.speaker)
    if (Object.prototype.hasOwnProperty.call(labels, id)) continue
    count += 1
    const given = names[id]
    labels[id] = typeof given === 'string' && given.trim()
      ? given.trim()
      : (en ? 'Speaker ' + id : '说话人' + id)
  }
  return { labels, count }
}

// 中文标点后一律可切；英文标点只在后面跟空白时切（避免把 3.5 这种小数切开）
const SPLIT_RE = /(?<=[。！？；，、])|(?<=[.!?;,])(?=\s)/

function splitSegment(seg, segIndex, maxChars, minMs) {
  const { start, end } = seg
  const text = String(seg.text)
  const base = { speaker: seg.speaker, segIndex }
  const whole = [{ start, end, text: text.trim(), ...base }]
  if (textWidth(text) <= maxChars) return whole

  const pieces = text.split(SPLIT_RE).filter(p => p.trim())
  if (pieces.length < 2) return whole

  const widths = pieces.map(p => Math.max(textWidth(p.trim()), 0.5))
  const total = widths.reduce((a, b) => a + b, 0)
  const dur = end - start
  let acc = 0
  let cues = pieces.map((p, i) => {
    const s = start + Math.round((dur * acc) / total)
    acc += widths[i]
    const e = i === pieces.length - 1 ? end : start + Math.round((dur * acc) / total)
    return { start: s, end: e, text: p }
  })

  // 不足 minMs 的并进相邻 cue（优先并进更短的那个邻居），直到都达标或只剩一个
  for (;;) {
    if (cues.length < 2) break
    let worst = -1
    for (let i = 0; i < cues.length; i++) {
      const d = cues[i].end - cues[i].start
      if (d < minMs && (worst < 0 || d < cues[worst].end - cues[worst].start)) worst = i
    }
    if (worst < 0) break
    let other
    if (worst === 0) other = 1
    else if (worst === cues.length - 1) other = worst - 1
    else {
      const prevD = cues[worst - 1].end - cues[worst - 1].start
      const nextD = cues[worst + 1].end - cues[worst + 1].start
      other = prevD <= nextD ? worst - 1 : worst + 1
    }
    const a = Math.min(worst, other)
    const merged = { start: cues[a].start, end: cues[a + 1].end, text: cues[a].text + cues[a + 1].text }
    cues = [...cues.slice(0, a), merged, ...cues.slice(a + 2)]
  }

  return cues.map(c => ({ start: c.start, end: c.end, text: c.text.trim(), ...base }))
}

/**
 * 段 → cue，按 start 排序。宽度超过 maxChars 的段按标点切分、时长按宽度比例分配，
 * 子 cue 不足 minMs 与相邻合并；segIndex 指回原段在输入数组里的下标。
 * 空文本、时间非法（非数或 end < start）的段跳过。
 */
export function buildCues(segments, { maxChars = 42, minMs = 800 } = {}) {
  if (!Array.isArray(segments)) return []
  const valid = []
  segments.forEach((seg, i) => {
    if (!seg || typeof seg.text !== 'string' || !seg.text.trim()) return
    const start = Number(seg.start)
    const end = Number(seg.end)
    if (typeof seg.start !== 'number' && typeof seg.start !== 'string') return
    if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return
    valid.push({ seg: { ...seg, start, end }, i })
  })
  valid.sort((x, y) => x.seg.start - y.seg.start || x.i - y.i)
  const out = []
  for (const { seg, i } of valid) out.push(...splitSegment(seg, i, maxChars, minMs))
  return out
}

/**
 * 二分查找当前 cue：ms 落在 [start,end] 内返回它；落在 end 之后 gapMs 内且尚未进入
 * 下一 cue 也返回它（相邻段小间隙不闪烁）；否则 -1。cues 须按 start 排序。
 */
export function cueIndexAt(cues, ms, { gapMs = 300 } = {}) {
  if (!Array.isArray(cues) || !cues.length || !Number.isFinite(ms)) return -1
  let lo = 0
  let hi = cues.length - 1
  let found = -1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (cues[mid].start <= ms) {
      found = mid
      lo = mid + 1
    } else {
      hi = mid - 1
    }
  }
  if (found < 0) return -1
  return ms <= cues[found].end + gapMs ? found : -1
}
