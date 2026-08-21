// 锚点文字归一化 + sha256。与后端 service/evidence/AnchorHash.java 逐字对拍，
// 改一处必须同步另一处并更新向量 tests/evidence/anchor-hash-vectors.json。
//
// 规则：NFKC → 删除全部 Unicode 空白（含 U+3000）→ 中文标点映射（，。；：！？（））
// → 引号一律删 → 保留《》。NFKC 已把全角逗号 U+FF0C 归成 ','，句号 U+3002 不变要靠表。

const PUNCT = {
  '，': ',', '。': '.', '；': ';', '：': ':', '！': '!', '？': '?', '（': '(', '）': ')',
  '「': '', '」': '', '『': '', '』': '', '“': '', '”': '', '‘': '', '’': '',
  '"': '', "'": '',
}

// Java Character.isWhitespace 的范围（不含 NBSP U+00A0/U+2007/U+202F，含 U+001C-U+001F）；
// 另外显式加 U+3000。NFKC 已把 NBSP 归成普通空格，所以这里不必再列。
const WS = /[\t\n\u000B\f\r\u001C-\u001F \u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]/

export function normalizeAnchor(s) {
  if (s == null) return ''
  const n = String(s).normalize('NFKC')
  let out = ''
  for (const c of n) {
    if (WS.test(c)) continue
    const m = PUNCT[c]
    out += m !== undefined ? m : c
  }
  return out
}

// 64 位小写 hex；浏览器与 node 18+ 都有 globalThis.crypto.subtle。
export async function anchorHash(s) {
  const bytes = new TextEncoder().encode(normalizeAnchor(s))
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}
