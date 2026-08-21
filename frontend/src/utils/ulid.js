// ulid.js — 26 位 Crockford base32 ULID（前 10 位毫秒时间戳 + 16 位随机），与后端
// service/evidence/Ulid.java 同算法。只用于 EvidenceLink 书签名 `EVID_<ulid>`，不追求单调。

const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

export function ulid(now = Date.now()) {
  const out = new Array(26)
  let t = BigInt(now)
  for (let i = 9; i >= 0; i--) { out[i] = ALPHABET[Number(t & 31n)]; t >>= 5n }
  const r = new Uint8Array(10)
  globalThis.crypto.getRandomValues(r)
  let a = 0n; for (let i = 0; i < 5; i++) a = (a << 8n) | BigInt(r[i])
  let b = 0n; for (let i = 5; i < 10; i++) b = (b << 8n) | BigInt(r[i])
  for (let i = 17; i >= 10; i--) { out[i] = ALPHABET[Number(a & 31n)]; a >>= 5n }
  for (let i = 25; i >= 18; i--) { out[i] = ALPHABET[Number(b & 31n)]; b >>= 5n }
  return out.join('')
}
