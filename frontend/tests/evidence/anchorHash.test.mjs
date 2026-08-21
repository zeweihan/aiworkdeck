// 锚点归一化与 sha256 必须与后端 AnchorHash.java 双端对拍：向量文件 anchor-hash-vectors.json
// 由单元 A 从 Java 算出写死（in/norm/hash 三字段），这里逐条断言。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { normalizeAnchor, anchorHash } from '../../src/utils/anchorHash.js'

const vectors = JSON.parse(readFileSync(new URL('./anchor-hash-vectors.json', import.meta.url)))

test('向量文件非空且三字段齐全', () => {
  assert.ok(vectors.length >= 3)
  for (const v of vectors) {
    assert.equal(typeof v.in, 'string')
    assert.equal(typeof v.norm, 'string')
    assert.match(v.hash, /^[0-9a-f]{64}$/)
  }
})

test('normalize 与共享向量逐条一致', () => {
  for (const v of vectors) assert.equal(normalizeAnchor(v.in), v.norm, JSON.stringify(v.in))
})

test('hash 与 Java 算出的向量逐条一致（真正的双端对拍）', async () => {
  for (const v of vectors) assert.equal(await anchorHash(v.in), v.hash, JSON.stringify(v.in))
})

test('hash 是 64 位小写 hex 且对空白不敏感', async () => {
  assert.match(await anchorHash('abc'), /^[0-9a-f]{64}$/)
  assert.equal(await anchorHash('a b'), await anchorHash('ab'))
  assert.equal(await anchorHash('a　b\n'), await anchorHash('ab'))
})

test('null / undefined 归一成空串', () => {
  assert.equal(normalizeAnchor(null), '')
  assert.equal(normalizeAnchor(undefined), '')
})

test('引号删除、《》保留、全角标点映射', () => {
  assert.equal(normalizeAnchor('“甲”说《合同》：好！'), '甲说《合同》:好!')
  assert.equal(normalizeAnchor("it's"), 'its')
})
