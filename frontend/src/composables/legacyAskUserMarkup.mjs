// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

const OPEN = '<ask_user>'
const CLOSE = '</ask_user>'

// This is a display compatibility path, never a tool-call parser. The server already
// pauses on the nested <question>; no client-generated id grants tool authority.
function readQuestion(raw, terminal = false) {
  const end = raw.endsWith(CLOSE) ? raw.slice(OPEN.length, -CLOSE.length) : terminal ? raw.slice(OPEN.length) : null
  if (end == null) return null
  const match = /^\s*<question>([\s\S]*?)<\/question>\s*(?:<options>([\s\S]*?)<\/options>\s*)?$/.exec(end)
  if (!match || !match[1].trim()) return null
  let options = []
  try { options = match[2] == null ? [] : JSON.parse(match[2]) } catch { return null }
  if (!Array.isArray(options) || options.some(o => !o || typeof o.label !== 'string' || !o.label.trim()
    || (o.description != null && typeof o.description !== 'string'))) return null
  return { question: match[1].trim(), options }
}

/** Incrementally hold only the legacy wrapper, so its nested protocol cannot leak
 * into body text or produce a half-built card. Literal examples bypass the XML
 * parser as a whole. Normal text is released on every input chunk. */
export function createLegacyAskUserMarkup() {
  let pending = ''
  let wrapper = ''
  let literal = false
  let line = ''
  let fence = null
  let inlineTicks = 0

  function track(text) {
    for (const ch of text) {
      if (ch === '\n') {
        const m = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line)
        if (m && !inlineTicks) {
          if (!fence) fence = { char: m[1][0], size: m[1].length }
          else if (m[1][0] === fence.char && m[1].length >= fence.size && !m[2].trim()) fence = null
        }
        if (!m && !fence) {
          for (const ticks of line.match(/`+/g) || []) {
            if (!inlineTicks) inlineTicks = ticks.length
            else if (ticks.length === inlineTicks) inlineTicks = 0
          }
        }
        line = ''
      } else line += ch
    }
  }

  return {
    push(text, terminal = false) {
      pending += text || ''
      const result = []
      const emit = (kind, text) => { if (text) result.push({ kind, text }) }
      while (pending) {
        if (wrapper) {
          const end = pending.indexOf(CLOSE)
          if (end < 0 && !terminal && wrapper.length + pending.length <= 65536) {
            // Keep a possible split closing tag in pending, not in the opaque body.
            const take = Math.max(0, pending.length - CLOSE.length + 1)
            wrapper += pending.slice(0, take)
            pending = pending.slice(take)
            break
          }
          const take = end >= 0 ? end + CLOSE.length : pending.length
          wrapper += pending.slice(0, take)
          pending = pending.slice(take)
          const question = !literal && readQuestion(wrapper, terminal)
          if (question) result.push({ kind: 'question', question, raw: wrapper })
          else emit('literal', wrapper)
          track(wrapper)
          wrapper = ''
          continue
        }
        const at = pending.indexOf(OPEN)
        if (at >= 0) {
          const before = pending.slice(0, at)
          emit('text', before)
          track(before)
          // A wrapper in a code fence or prose/code span is an example, not a UI
          // instruction. Only a standalone line may introduce a question card.
          literal = !!fence || !!inlineTicks || !/^ {0,3}$/.test(line)
          wrapper = OPEN
          pending = pending.slice(at + OPEN.length)
          continue
        }
        let keep = 0
        if (!terminal) {
          for (let n = 1; n < OPEN.length; n++) if (pending.endsWith(OPEN.slice(0, n))) keep = n
        }
        const ready = pending.slice(0, pending.length - keep)
        emit('text', ready)
        track(ready)
        pending = pending.slice(pending.length - keep)
        break
      }
      // An opening wrapper can be the very last bytes of a cancelled response.
      if (terminal && wrapper && !pending) {
        emit('literal', wrapper)
        track(wrapper)
        wrapper = ''
      }
      return result
    }
  }
}
