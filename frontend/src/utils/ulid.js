// 26 位 Crockford base32 ULID：前 10 位毫秒时间戳，后 16 位随机。
// 与后端 service/evidence/Ulid.java 同算法；只用于书签名（EVID_<ulid>），不追求单调。

const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

export function ulid(now = Date.now()) {
  const out = new Array(26)
  let t = now
  for (let i = 9; i >= 0; i--) { out[i] = ALPHABET[t % 32]; t = Math.floor(t / 32) }
  const r = new Uint8Array(10)
  globalThis.crypto.getRandomValues(r)
  // 80 位随机拆成两个 40 位块各取 8 个 5 位字符（与 Java 实现同切法）
  let a = 0; for (let i = 0; i < 5; i++) a = a * 256 + r[i]
  let b = 0; for (let i = 5; i < 10; i++) b = b * 256 + r[i]
  for (let i = 17; i >= 10; i--) { out[i] = ALPHABET[a % 32]; a = Math.floor(a / 32) }
  for (let i = 25; i >= 18; i--) { out[i] = ALPHABET[b % 32]; b = Math.floor(b / 32) }
  return out.join('')
}
