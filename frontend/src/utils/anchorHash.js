// anchorHash.js — EvidenceLink 锚点文字归一化 + sha256（前端份）。
//
// 与后端 backend/src/main/java/com/checkba/service/evidence/AnchorHash.java 是
// **同一算法**：NFKC → 删全部空白（含 U+3000）→ 中文标点映射半角 → 引号一律删
// → 保留《》→ sha256 小写 hex。改一处必须同步另一处并更新两份向量
// tests/evidence/anchor-hash-vectors.json（与后端 fixtures 字节一致）。
//
// NFKC 已把 ，（）：；！？ 归半角；句号 。 不归、靠表（向量第 1 条钉这个）。

const PUNCT = {
  '，': ',', '。': '.', '；': ';', '：': ':', '！': '!', '？': '?', '（': '(', '）': ')',
  '「': '', '」': '', '『': '', '』': '', '“': '', '”': '', '‘': '', '’': '', '"': '', "'": '',
}

export function normalizeAnchor(s) {
  if (s == null) return ''
  const n = String(s).normalize('NFKC')
  let out = ''
  for (const ch of n) {
    if (/\s/.test(ch) || ch === '　') continue
    const m = PUNCT[ch]
    out += m === undefined ? ch : m
  }
  return out
}

/** 64 位小写 hex，Promise（浏览器与 node 18+ 都有 globalThis.crypto.subtle）。 */
export async function anchorHash(s) {
  const bytes = new TextEncoder().encode(normalizeAnchor(s))
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('')
}
